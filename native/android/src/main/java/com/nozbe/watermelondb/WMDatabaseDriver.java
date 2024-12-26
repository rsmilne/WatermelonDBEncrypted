package com.nozbe.watermelondb;

import android.content.Context;
import android.database.Cursor;
import net.sqlcipher.database.SQLiteDatabase;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.nozbe.watermelondb.utils.MigrationSet;
import com.nozbe.watermelondb.utils.Pair;
import com.nozbe.watermelondb.utils.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class WMDatabaseDriver {
    private final WMDatabase database;
    private final String dbName;

    private final Logger log;
    private final Map<String, List<String>> cachedRecords;

    public WMDatabaseDriver(Context context, String dbName) {
        this(context, dbName, false);
    }

    public WMDatabaseDriver(Context context, String dbName, int schemaVersion, boolean unsafeNativeReuse) {
        this(context, dbName, unsafeNativeReuse);
        SchemaCompatibility compatibility = isCompatible(schemaVersion);
        if (compatibility instanceof SchemaCompatibility.NeedsSetup) {
            throw new SchemaNeededError();
        } else if (compatibility instanceof SchemaCompatibility.NeedsMigration) {
            throw new MigrationNeededError(
                    ((SchemaCompatibility.NeedsMigration) compatibility).fromVersion
            );

        }

    }

    public WMDatabaseDriver(Context context, String dbName, Schema schema, boolean unsafeNativeReuse) {
        this(context, dbName, unsafeNativeReuse);
        unsafeResetDatabase(schema);
    }

    public WMDatabaseDriver(Context context, String dbName, MigrationSet migrations, boolean unsafeNativeReuse) {
        this(context, dbName, unsafeNativeReuse);
        migrate(migrations);
    }

    public WMDatabaseDriver(Context context, String dbName, boolean unsafeNativeReuse) {
        this.database = unsafeNativeReuse ? WMDatabase.getInstance(dbName, context,
                SQLiteDatabase.CREATE_IF_NECESSARY |
                        SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING) :
                WMDatabase.buildDatabase(dbName, context,
                        SQLiteDatabase.CREATE_IF_NECESSARY |
                                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
        this.dbName = dbName;
        if (BuildConfig.DEBUG) {
            this.log = Logger.getLogger("DB_Driver");
        } else {
            this.log = null;
        }
        this.cachedRecords = new HashMap<>();
    }

    public WMDatabase getDatabase() {
        return database;
    }

    public Object find(String table, String id) {
        if (isCached(table, id)) {
            return id;
        }
        String[] args = new String[]{id};
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

    public void provideSyncJson(int id, byte[] syncPullJson) {
        // Implement if needed
    }

    public void unsafeResetDatabase(Schema schema) {
        database.unsafeExecuteStatements(schema.getSql());
    }

    public boolean find(String table, String id, String[] args) {
        String[] newArgs = new String[]{id};
        try (Cursor cursor =
                     database.rawQuery("select * from `" + table + "` where id == ? limit 1", newArgs)) {
            return cursor.getCount() > 0;
        }
    }

    public String query(String table, String id) {
        return query(table, new String[]{id});
    }

    public String query(String table, String[] ids) {
        StringBuilder builder = new StringBuilder();
        builder.append("select * from `").append(table).append("` where id in (");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                builder.append(",");
            }
            builder.append("?");
        }
        builder.append(")");

        try (Cursor cursor = database.rawQuery(builder.toString(), ids)) {
            return encodeCursor(cursor);
        }
    }

    public String queryIds(String query, String[] args) {
        try (Cursor cursor = database.rawQuery(query, args)) {
            StringBuilder builder = new StringBuilder();
            builder.append("[");
            if (cursor.moveToFirst()) {
                do {
                    if (builder.length() > 1) {
                        builder.append(",");
                    }
                    builder.append("\"").append(cursor.getString(0)).append("\"");
                } while (cursor.moveToNext());
            }
            builder.append("]");
            return builder.toString();
        }
    }

    public String unsafeQueryRaw(String query, String[] args) {
        try (Cursor cursor = database.rawQuery(query, args)) {
            return encodeCursor(cursor);
        }
    }

    public int count(String query, String[] args) {
        try (Cursor cursor = database.rawQuery(query, args)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    public String getLocal(String key) {
        try (Cursor cursor = database.rawQuery("select value from local_storage where key = ?", new String[]{key})) {
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
            return null;
        }
    }

    public void batch(String operations) {
        database.unsafeExecuteStatements(operations);
    }

    public void unsafeDestroyEverything() {
        database.unsafeExecuteStatements("drop table if exists local_storage");
    }

    private String encodeCursor(Cursor cursor) {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        if (cursor.moveToFirst()) {
            do {
                if (builder.length() > 1) {
                    builder.append(",");
                }
                encodeRow(cursor, builder);
            } while (cursor.moveToNext());
        }
        builder.append("]");
        return builder.toString();
    }

    private void encodeRow(Cursor cursor, StringBuilder builder) {
        builder.append("[");
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            if (i > 0) {
                builder.append(",");
            }
            encodeValue(cursor, i, builder);
        }
        builder.append("]");
    }

    private void encodeValue(Cursor cursor, int index, StringBuilder builder) {
        if (cursor.isNull(index)) {
            builder.append("null");
            return;
        }

        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER:
                builder.append(cursor.getLong(index));
                break;
            case Cursor.FIELD_TYPE_FLOAT:
                builder.append(cursor.getDouble(index));
                break;
            case Cursor.FIELD_TYPE_STRING:
                builder.append("\"").append(escapeString(cursor.getString(index))).append("\"");
                break;
            default:
                builder.append("null");
        }
    }

    private String escapeString(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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

    public void close() {
        database.close();
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
