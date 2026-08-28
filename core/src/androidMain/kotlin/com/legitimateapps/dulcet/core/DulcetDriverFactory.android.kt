package com.legitimateapps.dulcet.core

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.legitimateapps.dulcet.database.DulcetDatabase

internal actual class DulcetDriverFactory(
    private val context: Context,
    private val databaseName: String = "dulcet.db",
) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(DulcetDatabase.Schema, context, databaseName)
}
