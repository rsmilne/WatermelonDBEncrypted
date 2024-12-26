package com.nozbe.watermelondb;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

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
    public void find(int tag, String table, String id, Promise promise) {
        withDriver(tag, promise, (driver) -> driver.find(table, id), "find " + id);
    }

    @ReactMethod
    public void query(int tag, String table, String query, ReadableArray args, Promise promise) {
        withDriver(tag, promise, (driver) -> driver.cachedQuery(table, query, args.toArrayList().toArray()), "query");
    }

    @ReactMethod
    public void queryIds(int tag, String query, ReadableArray args, Promise promise) {
        withDriver(tag, promise, (driver) -> driver.queryIds(query, args.toArrayList().toArray()), "queryIds");
    }

    @ReactMethod
    public void unsafeQueryRaw(int tag, String query, ReadableArray args, Promise promise) {
        withDriver(tag, promise, (driver) -> driver.unsafeQueryRaw(query, args.toArrayList().toArray()), "unsafeQueryRaw");
    }

    @ReactMethod
    public void count(int tag, String query, ReadableArray args, Promise promise) {
        withDriver(tag, promise, (driver) -> driver.count(query, args.toArrayList().toArray()), "count");
    }

    @ReactMethod
    public void batch(int tag, ReadableArray operations, Promise promise) {
        withDriver(tag, promise, (driver) -> {
            driver.batch(operations);
            return true;
        }, "batch");
    }

    @ReactMethod
    public void unsafeResetDatabase(int tag, String schema, int schemaVersion, Promise promise) {
        withDriver(tag, promise, (driver) -> {
            driver.unsafeResetDatabase(new Schema(schemaVersion, schema));
            return null;
        }, "unsafeResetDatabase");
    }

    @ReactMethod
    public void getLocal(int tag, String key, Promise promise) {
        withDriver(tag, promise, (driver) -> driver.getLocal(key), "getLocal");
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public WritableArray unsafeGetLocalSynchronously(int tag, String key) {
        try {
            Connection connection = connections.get(tag);
            if (connection == null) {
                throw new Exception("No driver with tag " + tag + " available");
            }
            if (connection instanceof ConnectedConnection) {
                String value = ((ConnectedConnection) connection).driver.getLocal(key);
                WritableArray result = Arguments.createArray();
                result.pushString("result");
                result.pushString(value);
                return result;
            } else if (connection instanceof WaitingConnection) {
                throw new Exception("Waiting connection unexpected for unsafeGetLocalSynchronously");
            }
        } catch (Exception e) {
            WritableArray result = Arguments.createArray();
            result.pushString("error");
            result.pushString(e.getMessage());
            return result;
        }
        return null;
    }

    private List<Runnable> getQueue(int connectionTag) {
        List<Runnable> queue;
        if (connections.containsKey(connectionTag)) {
            Connection connection = connections.get(connectionTag);
            if (connection != null) {
                queue = connection.getQueue();
            } else {
                queue = new ArrayList<>();
            }
        } else {
            queue = new ArrayList<>();
        }
        return queue;
    }

    interface ParamFunction {
        Object applyParamFunction(WMDatabaseDriver arg);
    }

    private void withDriver(final int tag, final Promise promise, final ParamFunction function, String functionName) {
        try {
            Connection connection = connections.get(tag);
            if (connection == null) {
                promise.reject(new Exception("No driver with tag " + tag + " available"));
            } else if (connection instanceof ConnectedConnection) {
                Object result = function.applyParamFunction(((ConnectedConnection) connection).driver);
                promise.resolve(result == Void.TYPE ? true : result);
            } else if (connection instanceof WaitingConnection) {
                // try again when driver is ready
                connection.getQueue().add(() -> withDriver(tag, promise, function, functionName));
                connections.put(tag, new WaitingConnection(connection.database, connection.getQueue()));
            }
        } catch (Exception e) {
            promise.reject(functionName, e);
        }
    }

    private void connectDriver(int connectionTag, WMDatabaseDriver driver, Promise promise) {
        List<Runnable> queue = getQueue(connectionTag);
        connections.put(connectionTag, new ConnectedConnection(driver));

        for (Runnable operation : queue) {
            operation.run();
        }
        promise.resolve(true);
    }

    private void disconnectDriver(int connectionTag) {
        List<Runnable> queue = getQueue(connectionTag);

        connections.remove(connectionTag);

        for (Runnable operation : queue) {
            operation.run();
        }
    }

    @ReactMethod
    public void provideSyncJson(int id, String json, Promise promise) {
        // Note: WatermelonJSI is optional on Android, but we don't want users to have to set up
        // yet another NativeModule, so we're using Reflection to access it from here
        try {
            Class<?> clazz = Class.forName("com.nozbe.watermelondb.jsi.WatermelonJSI");
            Method method = clazz.getDeclaredMethod("provideSyncJson", int.class, byte[].class);
            method.invoke(null, id, json.getBytes());
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject(e);
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public WritableArray getRandomBytes(int count) {
        if (count != 256) {
            throw new IllegalStateException("Expected getRandomBytes to be called with 256");
        }

        byte[] randomBytes = new byte[256];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(randomBytes);

        WritableArray result = Arguments.createArray();
        for (byte value : randomBytes) {
            result.pushInt(Byte.toUnsignedInt(value));
        }
        return result;
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
