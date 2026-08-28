package com.legitimateapps.dulcet.core

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import com.legitimateapps.dulcet.database.DulcetDatabase

internal actual class DulcetDriverFactory(
    private val context: Context,
    private val databaseName: String = "dulcet.db",
) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = DulcetDatabase.Schema,
            context = context,
            name = databaseName,
            callback = object : AndroidSqliteDriver.Callback(DulcetDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
        )
}
