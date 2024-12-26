# WatermelonDB Encryption

This fork of WatermelonDB adds support for database encryption using SQLCipher. This allows you to securely store your data with strong encryption while maintaining all the powerful features of WatermelonDB.

## Installation

```bash
yarn add @nozbe/watermelondb-encrypted
# or
npm install @nozbe/watermelondb-encrypted
```

## Usage

To use encryption, simply provide an encryption key when initializing your database:

```javascript
import { Database } from '@nozbe/watermelondb-encrypted'

// Initialize database with encryption
const database = new Database({
  adapter: {
    schema,
    // ... other adapter options
    encryptionKey: 'your-secure-encryption-key', // Add this line to enable encryption
  },
})
```

### Important Security Notes

1. **Key Storage**: Never hardcode your encryption key in your source code. Instead, use secure key storage mechanisms appropriate for your platform:
   - iOS: Keychain
   - Android: EncryptedSharedPreferences
   - Web: WebCrypto API for key derivation

2. **Key Generation**: Use cryptographically secure methods to generate your encryption key. A simple example:
   ```javascript
   import { randomBytes } from 'crypto'
   
   const generateEncryptionKey = () => {
     return randomBytes(32).toString('hex')
   }
   ```

3. **Backup Considerations**: If you lose the encryption key, there is NO WAY to recover the data. Make sure to:
   - Securely back up encryption keys
   - Have a key rotation strategy
   - Document your key management procedures

## SQLCipher Configuration

The database uses the following SQLCipher configuration:
- Cipher: AES-256-CBC
- KDF Iterations: 64,000
- Page Size: 4096 bytes

These settings provide a good balance between security and performance. You can modify these settings by editing the Database.js file if needed.

## Migration from Unencrypted Database

If you're migrating from an unencrypted database to an encrypted one, you'll need to:

1. Back up your existing data
2. Create a new encrypted database
3. Migrate the data to the new database

Example migration code:

```javascript
import { Database } from '@nozbe/watermelondb-encrypted'

async function migrateToEncrypted(oldDatabase, newEncryptionKey) {
  // Create new encrypted database
  const newDatabase = new Database({
    adapter: {
      schema: oldDatabase.schema,
      // ... other adapter options
      encryptionKey: newEncryptionKey,
    },
  })

  // Migrate your data
  // ... implement your migration logic here

  return newDatabase
}
```

## Performance Considerations

Encryption adds some overhead to database operations. To maintain optimal performance:

1. Keep your database schema efficient
2. Use batch operations when possible
3. Implement proper indexing
4. Consider using a less intensive KDF iteration count if performance is critical (though this reduces security)

## Contributing

If you find bugs or have suggestions for improving the encryption implementation, please open an issue or submit a pull request.
