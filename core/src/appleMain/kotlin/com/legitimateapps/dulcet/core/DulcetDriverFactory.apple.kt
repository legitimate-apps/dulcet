package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.legitimateapps.dulcet.database.DulcetDatabase

internal actual class DulcetDriverFactory(
    private val databaseName: String = "dulcet.db",
    private val inMemory: Boolean = false,
) {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = DulcetDatabase.Schema,
        name = databaseName,
        onConfiguration = { configuration ->
            configuration.copy(
                inMemory = inMemory,
                extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true),
            )
        },
    )
}
