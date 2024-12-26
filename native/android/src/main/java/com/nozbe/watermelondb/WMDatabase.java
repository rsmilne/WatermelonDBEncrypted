package com.nozbe.watermelondb.encrypted;

import android.content.Context;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteCursor;
import net.sqlcipher.database.SQLiteCursorDriver;
import net.sqlcipher.database.SQLiteQuery;
import net.sqlcipher.database.SQLiteDatabase.CursorFactory;
import net.sqlcipher.Cursor;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class WMDatabase {
    private final SQLiteDatabase db;
    private static final String DEFAULT_CIPHER_SETTINGS = 
        "PRAGMA cipher_compatibility = 4;" + 
        "PRAGMA kdf_iter = 64000;" + 
        "PRAGMA cipher_page_size = 4096;" +
        "PRAGMA journal_mode = WAL;";

    private WMDatabase(SQLiteDatabase db) {
        this.db = db;
    }

    public static Map<String, WMDatabase> INSTANCES = new HashMap<>();

    public static WMDatabase getInstance(String name, Context context) {
        return getInstance(name, context, null);
    }

    public static WMDatabase getInstance(String name, Context context, String encryptionKey) {
        synchronized (WMDatabase.class) {
            WMDatabase instance = INSTANCES.getOrDefault(name, null);
            if (instance == null || !instance.isOpen()) {
                WMDatabase database = buildDatabase(name, context, encryptionKey);
                INSTANCES.put(name, database);
                return database;
            } else {
                return instance;
            }
        }
    }

    public static WMDatabase buildDatabase(String name, Context context, String encryptionKey) {
        SQLiteDatabase sqLiteDatabase = WMDatabase.createSQLiteDatabase(name, context, encryptionKey);
        return new WMDatabase(sqLiteDatabase);
    }

    private static SQLiteDatabase createSQLiteDatabase(String name, Context context, String encryptionKey) {
        String path;
        if (name.equals(":memory:") || name.contains("mode=memory")) {
            context.getCacheDir().delete();
            path = new File(context.getCacheDir(), name).getPath();
        } else {
            path = context.getDatabasePath(name + ".db").getPath();
        }

        // Initialize SQLCipher
        SQLiteDatabase.loadLibs(context);
        
        SQLiteDatabase database;
        if (encryptionKey != null && !encryptionKey.isEmpty()) {
            // Open or create encrypted database
            database = SQLiteDatabase.openOrCreateDatabase(path, encryptionKey, null);
            // Configure SQLCipher settings
            database.execSQL(DEFAULT_CIPHER_SETTINGS);
        } else {
            // Open or create unencrypted database with empty password
            database = SQLiteDatabase.openOrCreateDatabase(path, "", null);
            // Enable WAL mode
            database.execSQL("PRAGMA journal_mode = WAL;");
        }
        
        return database;
    }

    public void close() {
        if (db != null && db.isOpen()) {
            db.close();
        }
    }

    public boolean isOpen() {
        return db != null && db.isOpen();
    }

    public int getUserVersion() {
        try (Cursor cursor = db.rawQuery("PRAGMA user_version", null)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    public void setUserVersion(int version) {
        db.execSQL("PRAGMA user_version = " + version);
    }

    public Cursor rawQuery(String sql, String[] args) {
        return db.rawQueryWithFactory(new CursorFactory() {
            @Override
            public Cursor newCursor(SQLiteDatabase db,
                                  SQLiteCursorDriver driver,
                                  String editTable,
                                  SQLiteQuery query) {
                return new SQLiteCursor(db, driver, editTable, query);
            }
        }, sql, args, null);
    }

    public void execute(String sql) {
        db.execSQL(sql);
    }

    public void execute(String sql, Object[] args) {
        db.execSQL(sql, args);
    }

    public void unsafeExecuteStatements(String sql) {
        String[] statements = sql.split(";(\\s)*[\\r\\n]+");
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                db.execSQL(trimmed);
            }
        }
    }
}
