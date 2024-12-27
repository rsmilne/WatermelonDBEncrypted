package com.nozbe.watermelondb.encrypted;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.nozbe.watermelondb.encrypted.WMDatabaseDriver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.lang.reflect.Method;
import java.security.SecureRandom;

@ReactModule(name = WMDatabaseBridge.NAME)
public class WMDatabaseBridge extends ReactContextBaseJavaModule {
    static final String NAME = "WMDatabaseBridge";
    private final ReactApplicationContext reactContext;
    private final Map<Integer, Connection> connections = new HashMap<>();
    private static final Logger logger = Logger.getLogger(WMDatabaseBridge.class.getName());

    private static abstract class Connection {
        WMDatabase database;
        List<Runnable> queue;

        Connection(WMDatabase database) {
            this.database = database;
            this.queue = new ArrayList<>();
        }

        List<Runnable> getQueue() {
            return queue;
        }
    }

    private static class ConnectedConnection extends Connection {
        WMDatabaseDriver driver;

        ConnectedConnection(WMDatabaseDriver driver) {
            super(driver.getDatabase());
            this.driver = driver;
        }
    }

    private static class WaitingConnection extends Connection {
        WaitingConnection(WMDatabase database, List<Runnable> queue) {
            super(database);
            this.queue = queue;
        }
    }

    public WMDatabaseBridge(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    private WritableMap makeVersionMap(String code, int version) {
        WritableMap map = Arguments.createMap();
        map.putString("code", code);
        map.putInt("version", version);
        return map;
    }

    @ReactMethod
    public void initialize(String dbName, int schemaVersion, String encryptionKey, Promise promise) {
        try {
            WMDatabase database = WMDatabase.getInstance(dbName, getReactApplicationContext(), encryptionKey);
            int version = database.getUserVersion();

            if (version == 0) {
                promise.resolve(makeVersionMap("schema_needed", 0));
                return;
            }

            if (version < schemaVersion) {
                promise.resolve(makeVersionMap("migrations_needed", version));
                return;
            }

            if (version > schemaVersion) {
                promise.reject("schema_version_mismatch", "Database has newer schema version than app schema version");
                return;
            }

            promise.resolve(makeVersionMap("ok", version));
        } catch (Exception e) {
            promise.reject("initialize_error", e);
        }
    }

    @ReactMethod
    public void setUpWithSchema(String dbName, String schema, int schemaVersion, String encryptionKey, Promise promise) {
        try {
            WMDatabase database = WMDatabase.getInstance(dbName, getReactApplicationContext(), encryptionKey);
            database.unsafeExecuteStatements(schema);
            database.setUserVersion(schemaVersion);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("setup_error", e);
        }
    }

    @ReactMethod
    public void setUpWithMigrations(String dbName, ReadableArray migrations, int fromVersion, int toVersion, String encryptionKey, Promise promise) {
        try {
            WMDatabase database = WMDatabase.getInstance(dbName, getReactApplicationContext(), encryptionKey);
            int databaseVersion = database.getUserVersion();
            if (databaseVersion != fromVersion) {
                promise.reject("invalid_database_version",
                        "Invalid database version: " + databaseVersion + ", expected: " + fromVersion);
                return;
            }

            for (int i = 0; i < migrations.size(); i++) {
                String migration = migrations.getString(i);
                database.unsafeExecuteStatements(migration);
            }

            database.setUserVersion(toVersion);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("migration_error", e);
        }
    }

    @ReactMethod
    public void getLocal(String key, Promise promise) {
        try {
            String value = null;
            for (Connection connection : connections.values()) {
                if (connection instanceof ConnectedConnection) {
                    value = ((ConnectedConnection) connection).driver.getLocal(key);
                    break;
                }
            }
            WritableArray result = Arguments.createArray();
            result.pushString("result");
            result.pushString(value);
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("get_local_error", e);
        }
    }

    @Override
    public void invalidate() {
        try {
            logger.info("Invalidating WMDatabaseBridge");
            // Close all database connections
            for (Connection connection : connections.values()) {
                if (connection.database != null) {
                    connection.database.close();
                }
            }
            connections.clear();
            super.invalidate();
        } catch (Exception e) {
            logger.severe("Error during invalidate: " + e.getMessage());
        }
    }
}
