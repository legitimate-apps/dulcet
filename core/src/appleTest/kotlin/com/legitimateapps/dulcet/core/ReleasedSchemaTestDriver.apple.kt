package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import platform.Foundation.NSUUID

/** Unique name for the same reason as [createTestDriver]: SQLiter shares in-memory databases BY NAME. */
internal actual fun createReleasedSchemaTestDriver(statements: List<String>): SqlDriver = NativeSqliteDriver(
    schema = ReleasedSchema(statements),
    name = "dulcet-released-schema-${NSUUID().UUIDString}.db",
    onConfiguration = { configuration ->
        configuration.copy(
            inMemory = true,
            extendedConfig = DatabaseConfiguration.Extended(foreignKeyConstraints = true),
        )
    },
)

private class ReleasedSchema(private val statements: List<String>) : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        statements.forEach { driver.execute(null, it, 0) }
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = error("a released-schema test driver is never migrated by the driver")
}
