package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.legitimateapps.dulcet.database.DulcetDatabase

internal actual class DulcetDriverFactory(
    private val databaseName: String = "dulcet.db",
) {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(DulcetDatabase.Schema, databaseName)
}
