#include "Database.h"

namespace watermelondb {

using platform::consoleError;
using platform::consoleLog;

Database::Database(jsi::Runtime *runtime, std::string path, bool usesExclusiveLocking, std::string encryptionKey) : runtime_(runtime), mutex_() {
    db_ = std::make_unique<SqliteDb>(path);

    std::string initSql = "";

    #ifdef SQLITE_HAS_CODEC
    // Initialize encryption if key is provided
    if (!encryptionKey.empty()) {
        // Load SQLCipher
        sqlite3_activate_see();
        
        // Configure SQLCipher
        initSql += "PRAGMA key = '" + encryptionKey + "';";
        initSql += "PRAGMA cipher_compatibility = 4;";
        initSql += "PRAGMA cipher_page_size = 4096;";
        initSql += "PRAGMA kdf_iter = 64000;";
        initSql += "PRAGMA cipher_hmac_algorithm = HMAC_SHA512;";
        initSql += "PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA512;";
    }
    #endif

    // FIXME: On Android, Watermelon often errors out on large batches with an IO error, because it
    // can't find a temp store... I tried setting sqlite3_temp_directory to /tmp/something, but that
    // didn't work. Setting temp_store to memory seems to fix the issue, but causes a significant
    // slowdown, at least on iOS (not confirmed on Android). Worth investigating if the slowdown is
    // also present on Android, and if so, investigate the root cause. Perhaps we need to set the temp
    // directory by interacting with JNI and finding a path within the app's sandbox?
    #ifdef ANDROID
    initSql += "pragma temp_store = memory;";
    #endif

    initSql += "pragma journal_mode = WAL;";

    // set timeout before SQLITE_BUSY error is returned
    initSql += "pragma busy_timeout = 5000;";

    #ifdef ANDROID
    // NOTE: This was added in an attempt to fix mysterious `database disk image is malformed` issue when using
    // headless JS services
    // NOTE: This slows things down
    initSql += "pragma synchronous = FULL;";
    #endif
    if (usesExclusiveLocking) {
        // this seems to fix the headless JS service issue but breaks if you have multiple readers
        initSql += "pragma locking_mode = EXCLUSIVE;";
    }

    executeMultiple(initSql);
}

void Database::destroy() {
    const std::lock_guard<std::mutex> lock(mutex_);

    if (isDestroyed_) {
        return;
    }
    isDestroyed_ = true;
    for (auto const &cachedStatement : cachedStatements_) {
        sqlite3_stmt *statement = cachedStatement.second;
        sqlite3_finalize(statement);
    }
    cachedStatements_ = {};
    db_->destroy();
}

Database::~Database() {
    destroy();
}

bool Database::isCached(std::string cacheKey) {
    return cachedRecords_.find(cacheKey) != cachedRecords_.end();
}
void Database::markAsCached(std::string cacheKey) {
    cachedRecords_.insert(cacheKey);
}
void Database::removeFromCache(std::string cacheKey) {
    cachedRecords_.erase(cacheKey);
}

void Database::unsafeResetDatabase(jsi::String &schema, int schemaVersion) {
    auto &rt = getRt();
    const std::lock_guard<std::mutex> lock(mutex_);

    // TODO: in non-memory mode, just delete the DB files
    // NOTE: As of iOS 14, selecting tables from sqlite_master and deleting them does not work
    // They seem to be enabling "defensive" config. So we use another obscure method to clear the database
    // https://www.sqlite.org/c3ref/c_dbconfig_defensive.html#sqlitedbconfigresetdatabase

    if (sqlite3_db_config(db_->sqlite, SQLITE_DBCONFIG_RESET_DATABASE, 1, 0) != SQLITE_OK) {
        throw jsi::JSError(rt, "Failed to enable reset database mode");
    }
    // NOTE: We can't VACUUM in a transaction
    executeMultiple("vacuum");

    if (sqlite3_db_config(db_->sqlite, SQLITE_DBCONFIG_RESET_DATABASE, 0, 0) != SQLITE_OK) {
        throw jsi::JSError(rt, "Failed to disable reset database mode");
    }

    beginTransaction();
    try {
        cachedRecords_ = {};

        // Reinitialize schema
        executeMultiple(schema.utf8(rt));
        setUserVersion(schemaVersion);

        commit();
    } catch (const std::exception &ex) {
        rollback();
        throw;
    }
}

void Database::migrate(jsi::String &migrationSql, int fromVersion, int toVersion) {
    auto &rt = getRt();
    const std::lock_guard<std::mutex> lock(mutex_);

    beginTransaction();
    try {
        assert(getUserVersion() == fromVersion && "Incompatible migration set");

        executeMultiple(migrationSql.utf8(rt));
        setUserVersion(toVersion);

        commit();
    } catch (const std::exception &ex) {
        rollback();
        throw;
    }
}

std::string Database::getFromLocalStorage(std::string key) {
    std::string result;
    const std::lock_guard<std::mutex> lock(mutex_);
    sqlite3_stmt *statement;
    int rc = sqlite3_prepare_v2(db_->sqlite, "SELECT value FROM local_storage WHERE key = ?", -1, &statement, 0);
    if (rc != SQLITE_OK) {
        throw jsi::JSError(getRt(), "Failed to prepare statement");
    }
    sqlite3_bind_text(statement, 1, key.c_str(), -1, SQLITE_STATIC);
    rc = sqlite3_step(statement);
    if (rc == SQLITE_ROW) {
        result = reinterpret_cast<const char*>(sqlite3_column_text(statement, 0));
    }
    sqlite3_finalize(statement);
    return result;
}

std::vector<std::string> Database::getAllTables() {
    std::vector<std::string> allTables;
    const std::lock_guard<std::mutex> lock(mutex_);
    sqlite3_stmt *statement;
    int rc = sqlite3_prepare_v2(db_->sqlite, "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')", -1, &statement, 0);
    if (rc != SQLITE_OK) {
        throw jsi::JSError(getRt(), "Failed to prepare statement");
    }
    while ((rc = sqlite3_step(statement)) == SQLITE_ROW) {
        allTables.push_back(reinterpret_cast<const char*>(sqlite3_column_text(statement, 0)));
    }
    sqlite3_finalize(statement);
    return allTables;
}

void Database::unsafeDestroyEverything() {
    const std::lock_guard<std::mutex> lock(mutex_);
    beginTransaction();
    try {
        for (const auto &tableName : getAllTables()) {
            executeMultiple("DROP TABLE " + tableName);
        }
        executeMultiple("pragma writable_schema=1");
        executeMultiple("delete from sqlite_master where type in ('table', 'index', 'trigger')");
        executeMultiple("pragma user_version=0");
        executeMultiple("pragma writable_schema=0");
        commit();
    } catch (const std::exception &ex) {
        rollback();
        throw;
    }
}

} // namespace watermelondb
