package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

internal expect fun createTestDriver(): SqlDriver

internal fun SqlDriver.foreignKeysEnabled(): Boolean =
    executeQuery(
        identifier = null,
        sql = "PRAGMA foreign_keys",
        mapper = { cursor ->
            QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L)
        },
        parameters = 0,
    ).value
