package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import com.legitimateapps.dulcet.database.DulcetDatabase

internal actual fun createTestDriver(): SqlDriver = NativeSqliteDriver(
    schema = DulcetDatabase.Schema,
    name = "dulcet-test.db",
    onConfiguration = { configuration ->
        configuration.copy(
            inMemory = true,
            extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true),
        )
    },
)
