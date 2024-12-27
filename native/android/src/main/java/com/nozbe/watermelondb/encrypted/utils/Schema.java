package com.nozbe.watermelondb.encrypted.utils;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;

public class Schema {
    private final ReadableArray tables;
    private final ReadableMap schemaVersion;

    public Schema(ReadableArray tables, ReadableMap schemaVersion) {
        this.tables = tables;
        this.schemaVersion = schemaVersion;
    }

    public ReadableArray getTables() {
        return tables;
    }

    public ReadableMap getSchemaVersion() {
        return schemaVersion;
    }

    public String getSql() {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            ReadableMap table = tables.getMap(i);
            if (table.hasKey("sql")) {
                sql.append(table.getString("sql")).append(";");
            }
        }
        return sql.toString();
    }
}
