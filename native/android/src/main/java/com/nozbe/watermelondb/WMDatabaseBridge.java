package com.nozbe.watermelondb;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ReactModule(name = WMDatabaseBridge.NAME)
public class WMDatabaseBridge extends ReactContextBaseJavaModule {
    public static final String NAME = "WMDatabaseBridge";
    private WMDatabaseDriver driver = null;
    private Map<Integer, Connection> connections = new HashMap<>();

    public WMDatabaseBridge(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @NonNull
    @Override
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public void initialize(Integer tag, String dbName, int schemaVersion, boolean unsafeNativeReuse, String encryptionKey, Promise promise) {
        if (connections.containsKey(tag)) {
            throw new IllegalStateException("A driver with tag " + tag + " already set up");
        }
        WritableMap promiseMap = Arguments.createMap();
        try {
            connections.put(tag, new Connection.Connected(new WMDatabaseDriver(getReactApplicationContext(), dbName, schemaVersion, unsafeNativeReuse, encryptionKey)));
            promiseMap.putString("code", "ok");
            promise.resolve(promiseMap);
        } catch (SchemaNeededError e) {
            connections.put(tag, new Connection.Waiting(new ArrayList<>()));
            promiseMap.putString("code", "schema_needed");
            promise.resolve(promiseMap);
        } catch (MigrationNeededError e) {
            connections.put(tag, new Connection.Waiting(new ArrayList<>()));
            promiseMap.putString("code", "migrations_needed");
            promiseMap.putInt("databaseVersion", e.databaseVersion);
            promise.resolve(promiseMap);
        } catch (Exception e) {
            promise.reject(e);
        }
    }

    @ReactMethod
    public void setUpWithSchema(String dbName, ReadableMap schema, boolean schemaVersion, String encryptionKey, Promise promise) {
        try {
            Context context = getReactApplicationContext();
            driver = new WMDatabaseDriver(context, dbName, new Schema(schema), false, encryptionKey);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("setup_failed", e);
        }
    }

    @ReactMethod
    public void setUpWithMigrations(String dbName, ReadableMap migrations, String encryptionKey, Promise promise) {
        try {
            Context context = getReactApplicationContext();
            driver = new WMDatabaseDriver(context, dbName, new MigrationSet(migrations), false, encryptionKey);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("setup_failed", e);
        }
    }

    @ReactMethod
    public void find(String table, String id, Promise promise) {
        try {
            Object result = driver.find(table, id);
            promise.resolve(result != null ? (WritableArray) result : null);
        } catch (Exception e) {
            promise.reject("find_failed", e);
        }
    }

    @ReactMethod
    public void query(String table, String query, ReadableArray args, Promise promise) {
        try {
            Object[] queryArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                switch (args.getType(i)) {
                    case Null:
                        queryArgs[i] = null;
                        break;
                    case Boolean:
                        queryArgs[i] = args.getBoolean(i);
                        break;
                    case Number:
                        queryArgs[i] = args.getDouble(i);
                        break;
                    case String:
                        queryArgs[i] = args.getString(i);
                        break;
                }
            }
            WritableArray results = driver.query(table, query, queryArgs);
            promise.resolve(results);
        } catch (Exception e) {
            promise.reject("query_failed", e);
        }
    }

    @ReactMethod
    public void count(String query, ReadableArray args, Promise promise) {
        try {
            Object[] queryArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                switch (args.getType(i)) {
                    case Null:
                        queryArgs[i] = null;
                        break;
                    case Boolean:
                        queryArgs[i] = args.getBoolean(i);
                        break;
                    case Number:
                        queryArgs[i] = args.getDouble(i);
                        break;
                    case String:
                        queryArgs[i] = args.getString(i);
                        break;
                }
            }
            int count = driver.count(query, queryArgs);
            promise.resolve(count);
        } catch (Exception e) {
            promise.reject("count_failed", e);
        }
    }

    @ReactMethod
    public void batch(ReadableArray operations, Promise promise) {
        try {
            driver.batch(operations);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("batch_failed", e);
        }
    }

    @ReactMethod
    public void unsafeResetDatabase(Promise promise) {
        try {
            driver.unsafeResetDatabase();
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("reset_failed", e);
        }
    }

    @ReactMethod
    public void getLocal(String key, Promise promise) {
        try {
            String value = driver.getLocal(key);
            promise.resolve(value);
        } catch (Exception e) {
            promise.reject("get_local_failed", e);
        }
    }

    @ReactMethod
    public void setLocal(String key, String value, Promise promise) {
        try {
            driver.setLocal(key, value);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("set_local_failed", e);
        }
    }

    @ReactMethod
    public void removeLocal(String key, Promise promise) {
        try {
            driver.removeLocal(key);
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("remove_local_failed", e);
        }
    }

    private static class Connection {
        public static class Connected {
            public WMDatabaseDriver driver;

            public Connected(WMDatabaseDriver driver) {
                this.driver = driver;
            }
        }

        public static class Waiting {
            public List<Runnable> callbacks;

            public Waiting(List<Runnable> callbacks) {
                this.callbacks = callbacks;
            }
        }
    }

    private static class SchemaNeededError extends Exception {
    }

    private static class MigrationNeededError extends Exception {
        public int databaseVersion;

        public MigrationNeededError(int databaseVersion) {
            this.databaseVersion = databaseVersion;
        }
    }

    private static class MigrationSet {
        public MigrationSet(ReadableMap migrations) {
        }
    }

    private static class Schema {
        public Schema(ReadableMap schema) {
        }
    }
}
