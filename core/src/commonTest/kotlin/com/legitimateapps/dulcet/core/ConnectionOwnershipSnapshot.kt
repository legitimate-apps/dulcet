package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** Exact owned-row witness shared by real SQLite native executor proofs. */
internal fun SqlDriver.connectionOwnershipSnapshot(): Map<String, List<String>> {
    fun strings(sql: String, column: Int = 0): List<String> = executeQuery(null, sql, { cursor ->
        val values = mutableListOf<String>()
        while (cursor.next().value) values += cursor.getString(column).orEmpty()
        QueryResult.Value(values)
    }, 0).value
    return strings("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .filterNot { it in setOf("connection_enrollment", "server_route", "android_metadata") }
        .associateWith { table ->
            val columns = strings("PRAGMA table_info(\"$table\")", 1)
            strings("SELECT ${columns.joinToString(" || char(31) || ") { "quote(\"$it\")" }} FROM \"$table\"").sorted()
        }
}
