package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.database.DulcetDatabase

internal actual fun createTestDriver(): SqlDriver = AndroidSqliteDriver(DulcetDatabase.Schema,
    InstrumentationRegistry.getInstrumentation().targetContext, null)

internal actual fun createReleasedSchemaTestDriver(statements: List<String>): SqlDriver = AndroidSqliteDriver(
    object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            statements.forEach { driver.execute(null, it, 0) }; return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> =
            error("Released-schema fixture is migrated explicitly by its test")
    }, InstrumentationRegistry.getInstrumentation().targetContext, null)
