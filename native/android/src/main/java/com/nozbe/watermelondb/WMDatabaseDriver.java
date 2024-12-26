package com.nozbe.watermelondb;

import android.content.Context;
import net.sqlcipher.Cursor;
import net.sqlcipher.database.SQLiteDatabase;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class WMDatabaseDriver {
    private final WMDatabase database;
    private final String dbName;
    private final Logger logger;
    private final Map<String, List<String>> cachedRecords;

    public WMDatabaseDriver(String dbName, Context context, String encryptionKey) {
        this.database = WMDatabase.getInstance(dbName, context, encryptionKey);
        this.dbName = dbName;
        this.logger = Logger.getLogger("WMDatabaseDriver");
        this.cachedRecords = new HashMap<>();
    }

    public WMDatabase getDatabase() {
        return database;
    }

    public boolean find(String table, String id) {
        String[] args = new String[]{id};
        try (Cursor cursor = database.rawQuery("select * from `" + table + "` where id == ? limit 1", args)) {
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

    public WritableArray cachedQuery(String table, String query, ReadableArray args) {
        String[] stringArgs = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            stringArgs[i] = String.valueOf(args.getString(i));
        }
        try (Cursor cursor = database.rawQuery(query, stringArgs)) {
            WritableArray resultArray = Arguments.createArray();
            if (cursor.getCount() > 0 && cursor.getColumnNames()[0].equals("id")) {
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
            return resultArray;
        }
    }

    public WritableArray queryIds(String query, ReadableArray args) {
        String[] stringArgs = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            stringArgs[i] = String.valueOf(args.getString(i));
        }
        try (Cursor cursor = database.rawQuery(query, stringArgs)) {
            WritableArray resultArray = Arguments.createArray();
            if (cursor.getCount() > 0 && cursor.getColumnNames()[0].equals("id")) {
                while (cursor.moveToNext()) {
                    int columnIndex = cursor.getColumnIndex("id");
                    resultArray.pushString(cursor.getString(columnIndex));
                }
            }
            return resultArray;
        }
    }

    public WritableArray unsafeQueryRaw(String query, ReadableArray args) {
        String[] stringArgs = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            stringArgs[i] = String.valueOf(args.getString(i));
        }
        try (Cursor cursor = database.rawQuery(query, stringArgs)) {
            WritableArray resultArray = Arguments.createArray();
            if (cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    resultArray.pushMap(DatabaseUtils.cursorToMap(cursor));
                }
            }
            return resultArray;
        }
    }

    public int count(String query, ReadableArray args) {
        String[] stringArgs = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            stringArgs[i] = String.valueOf(args.getString(i));
        }
        try (Cursor cursor = database.rawQuery(query, stringArgs)) {
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

    public void batch(ReadableArray operations) {
        logger.info("Executing batch operations");
        try {
            for (int i = 0; i < operations.size(); i++) {
                ReadableArray operation = operations.getArray(i);
                int cacheBehavior = operation.getInt(0);
                String table = cacheBehavior != 0 ? operation.getString(1) : "";
                String sql = operation.getString(2);
                ReadableArray argBatches = operation.getArray(3);

                for (int j = 0; j < argBatches.size(); j++) {
                    ReadableArray argArray = argBatches.getArray(j);
                    Object[] args = new Object[argArray.size()];
                    for (int k = 0; k < argArray.size(); k++) {
                        switch (argArray.getType(k)) {
                            case Number:
                                args[k] = argArray.getDouble(k);
                                break;
                            case String:
                                args[k] = argArray.getString(k);
                                break;
                            case Boolean:
                                args[k] = argArray.getBoolean(k) ? 1 : 0;
                                break;
                            case Null:
                                args[k] = null;
                                break;
                            default:
                                throw new IllegalArgumentException("Unsupported argument type: " + argArray.getType(k));
                        }
                    }
                    
                    database.execute(sql, args);
                    
                    if (cacheBehavior != 0 && args.length > 0) {
                        String id = args[0].toString();
                        if (cacheBehavior == 1) {
                            markAsCached(table, id);
                        } else if (cacheBehavior == -1) {
                            removeFromCache(table, id);
                        }
                    }
                }
            }
            logger.info("Batch operations completed successfully");
        } catch (Exception e) {
            logger.severe("Error executing batch operations: " + e.getMessage());
            throw e;
        }
    }

    public void unsafeResetDatabase(Schema schema) {
        logger.info("Resetting database");
        database.unsafeExecuteStatements(schema.getSql());
        logger.info("Database reset completed");
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
}
