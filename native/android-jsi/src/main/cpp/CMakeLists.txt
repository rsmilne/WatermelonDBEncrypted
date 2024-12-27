PROJECT(watermelondb-jsi C CXX)
cmake_minimum_required(VERSION 3.4.1)

# inspired by https://github.com/ericlewis/react-native-hostobject-demo/blob/6f16c01db80f928ccd294c8cc5d4668b0f8c15ec/android/app/CMakeLists.txt
# execute_process (COMMAND ln "-s" "src" "../../../../../node_modules/react-native/third-party/double-conversion-1.1.6/double-conversion")

# NOTE: This may need to be bumped sometimes to force CMake caches to clear
set(WMELON_JSI_BUMP 4)

# -------------------------------------------------
# Figure out where node_modules is
# (surely there's a better way to do this)
# This has to work with standard RN project, Nozbe's unusual folder structure and internal Watermelon tester

get_filename_component(_nativeTesterPath "../../../../../node_modules/@nozbe/sqlite/" REALPATH)
get_filename_component(_nozbePath "../../../../../../../../../native/node_modules/react-native/ReactCommon/jsi/jsi/" REALPATH)

if(EXISTS "${_nativeTesterPath}")
        # these paths work for WatermelonDB native tester
        set(NODE_MODULES_PATH_WM ../../../../../node_modules/)
        set(NODE_MODULES_PATH_RN ../../../../../node_modules/)
elseif(EXISTS "${_nozbePath}")
        # these paths work for Nozbe
        set(NODE_MODULES_PATH_WM ../../../../../../../)
        set(NODE_MODULES_PATH_RN ../../../../../../../../../native/node_modules/)
else()
        # these paths should work for a standard RN project
        set(NODE_MODULES_PATH_WM ../../../../../../../)
        set(NODE_MODULES_PATH_RN ../../../../../../../)
endif()

# -------------------------------------------------
# Header search paths

set(SQLITE_VERSION sqlite-amalgamation-3400100)

include_directories(
        ../../../../shared
        ${NODE_MODULES_PATH_WM}/@nozbe/sqlite/${SQLITE_VERSION}/
        ${NODE_MODULES_PATH_WM}/@nozbe/simdjson/src/
        ${NODE_MODULES_PATH_RN}/react-native/React
        ${NODE_MODULES_PATH_RN}/react-native/React/Base
        ${NODE_MODULES_PATH_RN}/react-native/ReactCommon
        ${NODE_MODULES_PATH_RN}/react-native/ReactCommon/jsi
)

# -------------------------------------------------
# Build configuration

# SQLCipher configuration
add_definitions(
    -DSQLITE_HAS_CODEC
    -DSQLITE_TEMP_STORE=2
    -DSQLITE_THREADSAFE=1
    -DSQLITE_ENABLE_COLUMN_METADATA
    -DSQLITE_ENABLE_MEMORY_MANAGEMENT
    -DSQLITE_ENABLE_LOAD_EXTENSION
    -DSQLITE_ENABLE_RTREE
    -DSQLITE_ENABLE_STAT3
    -DSQLITE_ENABLE_STAT4
    -DSQLITE_ENABLE_JSON1
    -DSQLITE_ENABLE_FTS4
    -DSQLITE_ENABLE_FTS5
    -DSQLITE_ENABLE_COLUMN_METADATA
    -DSQLITE_ENABLE_UNLOCK_NOTIFY
    -DSQLITE_ENABLE_DBSTAT_VTAB
)

# simdjson is slow without optimization
set(CMAKE_CXX_FLAGS_DEBUG "-Os") # comment out for JSI debugging
set(CMAKE_CXX_FLAGS_RELEASE "-Os")

# -------------------------------------------------
# Source files

file(GLOB ANDROID_JSI_SRC_FILES ./*.cpp)
file(GLOB SHARED_SRC_FILES ../../../../shared/*.cpp)

add_library(watermelondb-jsi SHARED
        # vendor files
        ${NODE_MODULES_PATH_WM}/@nozbe/sqlite/${SQLITE_VERSION}/sqlite3.c
        ${NODE_MODULES_PATH_WM}/@nozbe/simdjson/src/simdjson.cpp
        # our sources
        ${ANDROID_JSI_SRC_FILES}
        ${SHARED_SRC_FILES}
        # this seems necessary to use almost any JSI API - otherwise we get linker errors
        ${NODE_MODULES_PATH_RN}/react-native/ReactCommon/jsi/jsi/jsi.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(watermelondb-jsi
                      ${android-lib}
                      ${log-lib})
