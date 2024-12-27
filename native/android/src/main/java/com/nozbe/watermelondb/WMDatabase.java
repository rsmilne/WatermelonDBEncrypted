package com.nozbe.watermelondb;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabase as SQLCipherDatabase;
import net.sqlcipher.database.SQLiteDatabaseHook;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class WMDatabase {
    private SQLiteDatabase db;
    private SQLCipherDatabase encryptedDb;
    private final Context context;
    private final String name;
    private final String encryptionKey;
    private final boolean isEncrypted;

    private static Map<String, WMDatabase> INSTANCES = new HashMap<>();

    private WMDatabase(Context context, String name, String encryptionKey) {
        this.context = context;
        this.name = name;
        this.encryptionKey = encryptionKey;
        this.isEncrypted = encryptionKey != null && !encryptionKey.isEmpty();
        
        if (isEncrypted) {
            SQLCipherDatabase.loadLibs(context);
        }
        
        if (isEncrypted) {
            String path = getDatabasePath();
            encryptedDb = SQLCipherDatabase.openOrCreateDatabase(
                path, 
                encryptionKey,
                null,
                new SQLiteDatabaseHook() {
                    @Override
                    public void preKey(SQLCipherDatabase database) {}

                    @Override
                    public void postKey(SQLCipherDatabase database) {
                        database.rawExecSQL("PRAGMA cipher_compatibility = 4");
                        database.rawExecSQL("PRAGMA cipher_page_size = 4096");
                        database.rawExecSQL("PRAGMA kdf_iter = 64000");
                        database.rawExecSQL("PRAGMA cipher_hmac_algorithm = HMAC_SHA512");
                        database.rawExecSQL("PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA512");
                    }
                }
            );
            encryptedDb.enableWriteAheadLogging();
        } else {
            String path = getDatabasePath();
            db = SQLiteDatabase.openDatabase(
                path,
                null,
                SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            );
        }
        
        initialize();
    }

    public static WMDatabase getInstance(String name, Context context) {
        return getInstance(name, context, null);
    }

    public static WMDatabase getInstance(String name, Context context, String encryptionKey) {
        synchronized (WMDatabase.class) {
            WMDatabase instance = INSTANCES.get(name);
            if (instance == null || !instance.isOpen()) {
                instance = new WMDatabase(context, name, encryptionKey);
                INSTANCES.put(name, instance);
            }
            return instance;
        }
    }

    private String getDatabasePath() {
        if (name.equals(":memory:") || name.contains("mode=memory")) {
            context.getCacheDir().delete();
            return new File(context.getCacheDir(), name).getPath();
        } else {
            return context.getDatabasePath("" + name + ".db").getPath().replace("/databases", "");
        }
    }

    private void initialize() {
    }

    public void execute(String query, Object[] args) {
        if (isEncrypted) {
            encryptedDb.execSQL(query, args);
        } else {
            db.execSQL(query, args);
        }
    }

    public void execute(String query) {
        if (isEncrypted) {
            encryptedDb.execSQL(query);
        } else {
            db.execSQL(query);
        }
    }

    public Cursor rawQuery(String sql, Object[] args) {
        if (isEncrypted) {
            String[] stringArgs = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                stringArgs[i] = args[i] != null ? args[i].toString() : null;
            }
            return encryptedDb.rawQuery(sql, stringArgs);
        } else {
            return db.rawQueryWithFactory(
                (db1, driver, editTable, query) -> {
                    for (int i = 0; i < args.length; i++) {
                        Object arg = args[i];
                        if (arg instanceof String) {
                            query.bindString(i + 1, (String) arg);
                        } else if (arg instanceof Boolean) {
                            query.bindLong(i + 1, (Boolean) arg ? 1 : 0);
                        } else if (arg instanceof Double) {
                            query.bindDouble(i + 1, (Double) arg);
                        } else if (arg == null) {
                            query.bindNull(i + 1);
                        } else {
                            throw new IllegalArgumentException("Bad query arg type: " + arg.getClass().getCanonicalName());
                        }
                    }
                    return new android.database.sqlite.SQLiteCursor(driver, editTable, query);
                },
                sql,
                new String[args.length],
                null,
                null
            );
        }
    }

    public void transaction(Runnable function) {
        if (isEncrypted) {
            encryptedDb.beginTransaction();
            try {
                function.run();
                encryptedDb.setTransactionSuccessful();
            } finally {
                encryptedDb.endTransaction();
            }
        } else {
            db.beginTransaction();
            try {
                function.run();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    public boolean isOpen() {
        return isEncrypted ? (encryptedDb != null && encryptedDb.isOpen()) : (db != null && db.isOpen());
    }

    public void close() {
        if (isEncrypted) {
            if (encryptedDb != null && encryptedDb.isOpen()) {
                encryptedDb.close();
            }
        } else {
            if (db != null && db.isOpen()) {
                db.close();
            }
        }
    }

    public boolean isInTransaction() {
        return isEncrypted ? encryptedDb.inTransaction() : db.inTransaction();
    }

    public void setUserVersion(int version) {
        execute("PRAGMA user_version = " + version);
    }

    public int getUserVersion() {
        try (Cursor cursor = rawQuery("PRAGMA user_version", new Object[]{})) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    public int count(String query, Object[] args) {
        try (Cursor cursor = rawQuery(query, args)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    public void unsafeExecuteStatements(String statements) {
        transaction(() -> {
            for (String statement : statements.split(";")) {
                if (!statement.trim().isEmpty()) {
                    execute(statement);
                }
            }
        });
    }
}
