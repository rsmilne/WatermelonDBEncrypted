package com.nozbe.watermelondb.encrypted.utils;

import android.database.Cursor;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

public class DatabaseUtils {
    public static WritableMap cursorToMap(Cursor cursor) {
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
        return map;
    }
}
