# WatermelonDB with SQLCipher for Android

This guide explains how to use the encrypted version of WatermelonDB in your React Native Android project.

## Installation

1. Add the dependency to your React Native project:
```json
{
  "dependencies": {
    "@nozbe/watermelondb": "github:rodneysmilne/WatermelonDBEncrypted#main"
  }
}
```

2. Add SQLCipher dependencies to your Android project. In `android/app/build.gradle`:
```gradle
dependencies {
    implementation "net.zetetic:android-database-sqlcipher:4.5.3"
    implementation "androidx.sqlite:sqlite:2.3.1"
    // ... other dependencies
}
```

## Usage

1. Initialize the database with encryption:
```javascript
import { Database } from '@nozbe/watermelondb'
import SQLiteAdapter from '@nozbe/watermelondb/adapters/sqlite'

const adapter = new SQLiteAdapter({
  dbName: 'myapp',
  schema: mySchema,
  // Add encryption key
  encryptionKey: 'your-secure-encryption-key',
})

const database = new Database({
  adapter,
  modelClasses: [
    // ... your models
  ],
})
```

2. Securely store your encryption key:
```javascript
import EncryptedStorage from 'react-native-encrypted-storage'

// Store key
async function storeEncryptionKey(key) {
  try {
    await EncryptedStorage.setItem(
      "database_key",
      key
    )
  } catch (error) {
    console.error('Error storing key:', error)
  }
}

// Retrieve key
async function getEncryptionKey() {
  try {
    return await EncryptedStorage.getItem("database_key")
  } catch (error) {
    console.error('Error reading key:', error)
    return null
  }
}
```

## Security Considerations

1. Key Generation:
```javascript
import { Buffer } from 'buffer'

function generateEncryptionKey() {
  return Buffer.from(Array(32).fill(0).map(() => Math.floor(Math.random() * 256))).toString('hex')
}
```

2. Key Storage:
- Never store the encryption key in plain text
- Use Android's EncryptedSharedPreferences or a secure key storage solution
- Consider using biometric authentication for key access

3. Database Migration:
When migrating from unencrypted to encrypted database:
```javascript
async function migrateToEncrypted(oldDb, newKey) {
  // Backup old data
  const backup = await oldDb.backup()
  
  // Create new encrypted database
  const newDb = new Database({
    adapter: new SQLiteAdapter({
      schema: oldDb.schema,
      // ... other options
      encryptionKey: newKey,
    }),
    // ... other options
  })
  
  // Restore data to encrypted database
  await newDb.restore(backup)
  
  return newDb
}
```

## Performance

SQLCipher adds some overhead to database operations. To maintain good performance:

1. Use batch operations when possible
2. Keep your queries efficient
3. Use appropriate indexes
4. Consider adjusting encryption parameters if needed:
```java
// In WMDatabase.java
private static final String DEFAULT_CIPHER_SETTINGS = 
    "PRAGMA cipher_compatibility = 4; " +
    "PRAGMA kdf_iter = 64000; " +     // Adjust this for performance vs security
    "PRAGMA cipher_page_size = 4096;";
```

## Troubleshooting

1. If you get build errors:
```bash
cd android
./gradlew clean
cd ..
npx react-native run-android
```

2. If you get runtime errors about SQLCipher:
- Make sure SQLCipher dependencies are properly included
- Check that the encryption key is being passed correctly
- Verify the database file permissions

3. For migration issues:
- Always backup your data before migration
- Test migration process thoroughly
- Consider implementing a fallback mechanism
