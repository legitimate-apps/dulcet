package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

internal actual fun createReleasedSchemaTestDriver(statements: List<String>): SqlDriver =
    JdbcSqliteDriver(
        url = JdbcSqliteDriver.IN_MEMORY,
        properties = Properties().apply { put("foreign_keys", "true") },
    ).also { driver -> statements.forEach { driver.execute(null, it, 0) } }
