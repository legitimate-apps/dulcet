package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
internal actual fun createLibrarySyncControlDatabase(): LibrarySyncControlDatabase {
    val databaseName = "dulcet-library-sync-control-${NSUUID().UUIDString}.db"
    val primary = DulcetDriverFactory(databaseName = databaseName).openDulcetDatabase()
    val databasePath = primary.driver.databaseFilePath()
    // The commit probe reads on the primary's thread before it commits. A shared pool would
    // join that thread's transaction and expose its uncommitted generation to the observer.
    val observer = DulcetDatabaseStore.open(
        DulcetDriverFactory(databaseName = databaseName).createIndependentDriver(),
    )
    return LibrarySyncControlDatabase(primary, observer) {
        NSFileManager.defaultManager.removeItemAtPath(databasePath, null)
    }
}

private fun SqlDriver.databaseFilePath(): String =
    executeQuery(
        identifier = null,
        sql = "PRAGMA database_list",
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getString(2)))
        },
        parameters = 0,
    ).value
