package com.nozbe.watermelondb;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import android.os.Trace;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import com.nozbe.watermelondb.utils.MigrationSet;
import com.nozbe.watermelondb.utils.Pair;
import com.nozbe.watermelondb.utils.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class WMDatabaseDriver {
    private final Context context;
    private final String dbName;
    private final String encryptionKey;
    private WMDatabase database;

    private final Logger log;
    private final Map<String, List<String>> cachedRecords;

    public WMDatabaseDriver(Context context, String dbName, String encryptionKey) {
        this.context = context;
        this.dbName = dbName;
        this.encryptionKey = encryptionKey;
        if (BuildConfig.DEBUG) {
            this.log = Logger.getLogger("DB_Driver");
        } else {
            this.log = null;
        }
        this.cachedRecords = new HashMap<>();
    }

    public void initialize() {
        if (database == null) {
            database = WMDatabase.getInstance(dbName, context, encryptionKey);
            database.initialize();
        }
    }

    public WMDatabaseDriver(Context context, String dbName, int schemaVersion, boolean unsafeNativeReuse, String encryptionKey) {
        this.database = unsafeNativeReuse ? 
            WMDatabase.getInstance(dbName, context, encryptionKey) :
            WMDatabase.getInstance(dbName, context, encryptionKey);

        if (BuildConfig.DEBUG) {
            this.log = Logger.getLogger("DB_Driver");
        } else {
            this.log = null;
        }
        this.cachedRecords = new HashMap<>();

        SchemaCompatibility compatibility = isCompatible(schemaVersion);
        if (compatibility instanceof SchemaCompatibility.NeedsSetup) {
            throw new SchemaNeededError();
        } else if (compatibility instanceof SchemaCompatibility.NeedsMigration) {
            throw new MigrationNeededError(((SchemaCompatibility.NeedsMigration) compatibility).fromVersion);
        }
    }

    public WMDatabaseDriver(Context context, String dbName, Schema schema, boolean unsafeNativeReuse, String encryptionKey) {
        this(context, dbName, encryptionKey);
        unsafeResetDatabase(schema);
    }

    public WMDatabaseDriver(Context context, String dbName, MigrationSet migrations, boolean unsafeNativeReuse, String encryptionKey) {
        this(context, dbName, encryptionKey);
        migrate(migrations);
    }

    public WMDatabaseDriver(Context context, String dbName, boolean unsafeNativeReuse, String encryptionKey) {
        this(context, dbName, encryptionKey);
        database = unsafeNativeReuse ? WMDatabase.getInstance(dbName, context, encryptionKey,
                SQLiteDatabase.CREATE_IF_NECESSARY |
                        SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING) :
                WMDatabase.buildDatabase(dbName, context, encryptionKey,
                        SQLiteDatabase.CREATE_IF_NECESSARY |
                                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
    }

    public Object find(String table, String id) {
        if (isCached(table, id)) {
            return id;
        }
        Object[] args = {id};
        try (Cursor cursor =
                     database.rawQuery("select * from `" + table + "` where id == ? limit 1", args)) {
            if (cursor.getCount() <= 0) {
                return null;
            }
            markAsCached(table, id);
            cursor.moveToFirst();
            return DatabaseUtils.cursorToMap(cursor);
        }
    }

    public WritableArray cachedQuery(String table, String query, Object[] args) {
        WritableArray resultArray = Arguments.createArray();
        try (Cursor cursor = database.rawQuery(query, args)) {
            if (cursor.getCount() > 0 && DatabaseUtils.arrayContains(cursor.getColumnNames(), "id")) {
                int idColumnIndex = cursor.getColumnIndex("id");
                while (cursor.moveToNext()) {
                    String id = cursor.getString(idColumnIndex);
                    if (isCached(table, id)) {
                        resultArray.pushString(id);
                    } else {
                        markAsCached(table, id);
                        resultArray.pushMap(DatabaseUtils.cursorToMap(cursor));
                    }
                }
            }
        }
        return resultArray;
    }

    public WritableArray queryIds(String query, Object[] args) {
        WritableArray resultArray = Arguments.createArray();
        try (Cursor cursor = database.rawQuery(query, args)) {
            if (cursor.getCount() > 0 && DatabaseUtils.arrayContains(cursor.getColumnNames(), "id")) {
                while (cursor.moveToNext()) {
                    int columnIndex = cursor.getColumnIndex("id");
                    resultArray.pushString(cursor.getString(columnIndex));
                }
            }
        }
        return resultArray;
    }

    public WritableArray unsafeQueryRaw(String query, Object[] args) {
        WritableArray resultArray = Arguments.createArray();
        try (Cursor cursor = database.rawQuery(query, args)) {
            if (cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    resultArray.pushMap(DatabaseUtils.cursorToMap(cursor));
                }
            }
        }
        return resultArray;
    }

    public int count(String query, Object[] args) {
        return database.count(query, args);
    }

    public String getLocal(String key) {
        return database.getFromLocalStorage(key);
    }

    public void batch(ReadableArray operations) {
        List<Pair<String, String>> newIds = new ArrayList<>();
        List<Pair<String, String>> removedIds = new ArrayList<>();

        Trace.beginSection("Batch");
        try {
            database.transaction(() -> {
                for (int i = 0; i < operations.size(); i++) {
                    ReadableArray operation = operations.getArray(i);
                    int cacheBehavior = operation.getInt(0);
                    String table = cacheBehavior != 0 ? operation.getString(1) : "";
                    String sql = operation.getString(2);
                    ReadableArray argBatches = operation.getArray(3);

                    for (int j = 0; j < argBatches.size(); j++) {
                        Object[] args = argBatches.getArray(j).toArrayList().toArray();
                        database.execute(sql, args);
                        if (cacheBehavior != 0) {
                            String id = (String) args[0];
                            if (cacheBehavior == 1) {
                                newIds.add(Pair.create(table, id));
                            } else if (cacheBehavior == -1) {
                                removedIds.add(Pair.create(table, id));
                            }
                        }
                    }
                }
            });
        } finally {
            Trace.endSection();
        }

        Trace.beginSection("updateCaches");
        for (Pair<String, String> it : newIds) {
            markAsCached(it.first, it.second);
        }
        for (Pair<String, String> it : removedIds) {
            removeFromCache(it.first, it.second);
        }
        Trace.endSection();
    }

    public WritableArray query(String table, String query, Object[] args) {
        WritableArray result = Arguments.createArray();
        try (Cursor cursor = database.rawQuery(query, args)) {
            while (cursor.moveToNext()) {
                WritableMap map = Arguments.createMap();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String columnName = cursor.getColumnName(i);
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL:
                            map.putNull(columnName);
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            map.putDouble(columnName, cursor.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            map.putDouble(columnName, cursor.getDouble(i));
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            map.putString(columnName, cursor.getString(i));
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            map.putString(columnName, new String(cursor.getBlob(i)));
                            break;
                    }
                }
                result.pushMap(map);
            }
        }
        return result;
    }

    public void batch(ReadableArray operations) {
        database.inTransaction(() -> {
            for (int i = 0; i < operations.size(); i++) {
                ReadableMap operation = operations.getMap(i);
                String table = operation.getString("table");
                String query = operation.getString("query");
                ReadableArray args = operation.getArray("args");
                
                Object[] queryArgs = new Object[args.size()];
                for (int j = 0; j < args.size(); j++) {
                    switch (args.getType(j)) {
                        case Null:
                            queryArgs[j] = null;
                            break;
                        case Boolean:
                            queryArgs[j] = args.getBoolean(j);
                            break;
                        case Number:
                            queryArgs[j] = args.getDouble(j);
                            break;
                        case String:
                            queryArgs[j] = args.getString(j);
                            break;
                    }
                }
                
                database.execute(query, queryArgs);
            }
        });
    }

    public void close() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private void markAsCached(String table, String id) {
        // log.info("Mark as cached " + id);
        List<String> cache = cachedRecords.get(table);
        if (cache == null) {
            cache = new ArrayList<>();
        }
        cache.add(id);
        cachedRecords.put(table, cache);
    }

    private boolean isCached(String table, String id) {
        List<String> cache = cachedRecords.get(table);
        return cache != null && cache.contains(id);
    }

    private void removeFromCache(String table, String id) {
        List<String> cache = cachedRecords.get(table);
        if (cache != null) {
            cache.remove(id);
            cachedRecords.put(table, cache);
        }
    }

    private void migrate(MigrationSet migrations) {
        int databaseVersion = database.getUserVersion();
        if (databaseVersion != migrations.from) {
            throw new IllegalArgumentException("Incompatible migration set applied. " +
                    "DB: " + databaseVersion + ", migration: " + migrations.from);
        }
        database.transaction(() -> {
            database.unsafeExecuteStatements(migrations.sql);
            database.setUserVersion(migrations.to);
        });
    }

    public void unsafeResetDatabase(Schema schema) {
        if (log != null) {
            log.info("Unsafe reset database");
        }
        database.unsafeDestroyEverything();
        cachedRecords.clear();
        database.transaction(() -> {
            database.unsafeExecuteStatements(schema.sql);
            database.setUserVersion(schema.version);
        });
    }

    private static class SchemaCompatibility {
        static class Compatible extends SchemaCompatibility {
        }

        static class NeedsSetup extends SchemaCompatibility {
        }

        static class NeedsMigration extends SchemaCompatibility {
            final int fromVersion;

            NeedsMigration(int fromVersion) {
                this.fromVersion = fromVersion;
            }
        }
    }

    private SchemaCompatibility isCompatible(int schemaVersion) {
        int databaseVersion = database.getUserVersion();
        if (databaseVersion == schemaVersion) {
            return new SchemaCompatibility.Compatible();
        } else if (databaseVersion == 0) {
            return new SchemaCompatibility.NeedsSetup();
        } else if (databaseVersion < schemaVersion) {
            return new SchemaCompatibility.NeedsMigration(databaseVersion);
        } else {
            log.info("com.nozbe.watermelondb.Database has newer version (" + databaseVersion + ") than what the " +
                    "app supports (" + schemaVersion + "). Will reset database.");
            return new SchemaCompatibility.NeedsSetup();
        }
    }
}
