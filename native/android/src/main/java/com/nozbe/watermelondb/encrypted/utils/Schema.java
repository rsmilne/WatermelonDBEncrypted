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
}
