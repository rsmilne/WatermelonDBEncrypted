// @flow

import { type ResultCallback } from '../../../utils/common'
import type { SQL } from '../index'
import { logger } from '../../../utils/common'
import { type SchemaVersion } from '../../../Schema'
import type { SchemaMigrations } from '../../../Schema/migrations'

import Database from './Database'
import type { DispatcherType } from './dispatcher'

export type SqliteDispatcher = DispatcherType<Database>

type Migrations = {
  from: SchemaVersion,
  to: SchemaVersion,
  sql: SQL,
}

class MigrationNeededError extends Error {
  databaseVersion: number

  type: string

  constructor(databaseVersion: number): void {
    super('MigrationNeededError')
    this.databaseVersion = databaseVersion
    this.type = 'MigrationNeededError'
    this.message = 'MigrationNeededError'
  }
}

class SchemaNeededError extends Error {
  type: string

  constructor(): void {
    super('SchemaNeededError')
    this.type = 'SchemaNeededError'
    this.message = 'SchemaNeededError'
  }
}

export function getPath(dbName: string): string {
  if (dbName === ':memory:' || dbName === 'file::memory:') {
    return dbName
  }

  let path =
    dbName.startsWith('/') || dbName.startsWith('file:') ? dbName : `${process.cwd()}/${dbName}`
  if (path.indexOf('.db') === -1) {
    if (path.indexOf('?') >= 0) {
      const index = path.indexOf('?')
      path = `${path.substring(0, index)}.db${path.substring(index)}`
    } else {
      path = `${path}.db`
    }
  }

  return path
}

export default class DatabaseDriver {
  static sharedMemoryConnections: { [dbName: string]: Database } = {}

  database: Database

  cachedRecords: any = {}

  initialize(dbName: string, schemaVersion: number, encryptionKey: ?string): void {
    logger.log('[DB] Initializing DatabaseDriver')

    this.init(dbName, encryptionKey)

    logger.log('[DB] Checking schema version...')
    this.isCompatible(schemaVersion)
  }

  setUpWithSchema(dbName: string, schema: SQL, schemaVersion: number, encryptionKey: ?string): void {
    logger.log('[DB] Setting up database with schema')

    // TODO: Remove this check when CLI no longer generates schemas with semicolons
    if (schema[schema.length - 1] === ';') {
      logger.warn(
        '[DB] Warning: Schema contains semicolon at the end. This is no longer necessary and will be removed in a future version of WatermelonDB',
      )
    }

    this.init(dbName, encryptionKey)
    this.unsafeResetDatabase({ version: schemaVersion, sql: schema })
    this.isCompatible(schemaVersion)
  }

  setUpWithMigrations(dbName: string, migrations: Migrations, encryptionKey: ?string): void {
    logger.log('[DB] Setting up database with migrations')

    this.init(dbName, encryptionKey)
    this.migrate(migrations)
    this.isCompatible(migrations.to)
  }

  init(dbName: string, encryptionKey: ?string): void {
    if (this.database) {
      return
    }

    // Share database connection between multiple WatermelonDB instances
    if (dbName.includes('?mode=memory') || dbName === ':memory:') {
      const sharedMemoryConnection = DatabaseDriver.sharedMemoryConnections[dbName]
      if (sharedMemoryConnection) {
        logger.log('[DB] Reusing existing shared memory connection')
        this.database = sharedMemoryConnection
        return
      }

      logger.log('[DB] Creating new shared memory connection')
      this.database = new Database(dbName, encryptionKey)
      DatabaseDriver.sharedMemoryConnections[dbName] = this.database
      return
    }

    this.database = new Database(dbName, encryptionKey)
  }

  find(table: string, id: string): any | null | string {
    if (this.isCached(table, id)) {
      return id
    }

    const query = `SELECT * FROM '${table}' WHERE id == ? LIMIT 1`
    const results = this.database.queryRaw(query, [id])

    if (results.length === 0) {
      return null
    }

    this.markAsCached(table, id)
    return results[0]
  }

  cachedQuery(table: string, query: string, args: any[]): any[] {
    const results = this.database.queryRaw(query, args)
    return results.map((row: any) => {
      const id = `${row.id}`
      if (this.isCached(table, id)) {
        return id
      }
      this.markAsCached(table, id)
      return row
    })
  }

  queryIds(query: string, args: any[]): string[] {
    return this.database.queryRaw(query, args).map((row) => `${row.id}`)
  }

  unsafeQueryRaw(query: string, args: any[]): any[] {
    return this.database.queryRaw(query, args)
  }

  count(query: string, args: any[]): number {
    return this.database.count(query, args)
  }

  batch(operations: any[]): void {
    const newIds = []
    const removedIds = []

    this.database.inTransaction(() => {
      operations.forEach((operation: any[]) => {
        const [cacheBehavior, table, sql, argBatches] = operation
        argBatches.forEach((args) => {
          this.database.execute(sql, args)
          if (cacheBehavior === 1) {
            newIds.push([table, args[0]])
          } else if (cacheBehavior === -1) {
            removedIds.push([table, args[0]])
          }
        })
      })
    })

    newIds.forEach(([table, id]) => {
      this.markAsCached(table, id)
    })

    removedIds.forEach(([table, id]) => {
      this.removeFromCache(table, id)
    })
  }

  // MARK: - LocalStorage

  getLocal(key: string): any | null {
    const results = this.database.queryRaw('SELECT `value` FROM `local_storage` WHERE `key` = ?', [
      key,
    ])

    if (results.length > 0) {
      return results[0].value
    }

    return null
  }

  // MARK: - Record caching

  hasCachedTable(table: string): any {
    // $FlowFixMe
    return Object.prototype.hasOwnProperty.call(this.cachedRecords, table)
  }

  isCached(table: string, id: string): boolean {
    if (this.hasCachedTable(table)) {
      return this.cachedRecords[table].has(id)
    }
    return false
  }

  markAsCached(table: string, id: string): void {
    if (!this.hasCachedTable(table)) {
      this.cachedRecords[table] = new Set()
    }
    this.cachedRecords[table].add(id)
  }

  removeFromCache(table: string, id: string): void {
    if (this.hasCachedTable(table) && this.cachedRecords[table].has(id)) {
      this.cachedRecords[table].delete(id)
    }
  }

  // MARK: - Other private details

  isCompatible(schemaVersion: number): void {
    const databaseVersion = this.database.userVersion
    if (schemaVersion !== databaseVersion) {
      if (databaseVersion > 0 && databaseVersion < schemaVersion) {
        throw new MigrationNeededError(databaseVersion)
      } else {
        throw new SchemaNeededError()
      }
    }
  }

  unsafeResetDatabase(schema: { sql: string, version: number }): void {
    this.database.unsafeDestroyEverything()
    this.cachedRecords = {}

    this.database.inTransaction(() => {
      this.database.executeStatements(schema.sql)
      this.database.userVersion = schema.version
    })
  }

  migrate(migrations: Migrations): void {
    const databaseVersion = this.database.userVersion

    if (`${databaseVersion}` !== `${migrations.from}`) {
      throw new Error(
        `Incompatbile migration set applied. DB: ${databaseVersion}, migration: ${migrations.from}`,
      )
    }

    this.database.inTransaction(() => {
      this.database.executeStatements(migrations.sql)
      this.database.userVersion = migrations.to
    })
  }
}
