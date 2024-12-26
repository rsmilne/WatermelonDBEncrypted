package com.nozbe.watermelondb;

public class Schema {
    private final int version;
    private final String sql;

    public Schema(int version, String sql) {
        this.version = version;
        this.sql = sql;
    }

    public int getVersion() {
        return version;
    }

    public String getSql() {
        return sql;
    }
}
