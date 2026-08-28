package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import java.sql.DriverManager

internal actual class DulcetDriverFactory(
    private val databasePath: String,
) {
    init {
        require(databasePath.isNotBlank())
    }

    actual fun createDriver(): SqlDriver {
        val url = "jdbc:sqlite:$databasePath"
        val driver = JdbcSqliteDriver(url)
        val inspection = inspectSchema(url)
        if (!inspection.hasSchema) {
            DulcetDatabase.Schema.create(driver)
            setSqliteSchemaVersion(driver, DulcetDatabase.Schema.version)
            return driver
        }
        val oldVersion = inspection.version
            ?: error("Existing Dulcet database has no recoverable schema version")
        check(oldVersion <= DulcetDatabase.Schema.version) {
            "Dulcet database schema is newer than this build"
        }
        if (oldVersion < DulcetDatabase.Schema.version) {
            DulcetDatabase.Schema.migrate(driver, oldVersion, DulcetDatabase.Schema.version)
            setSqliteSchemaVersion(driver, DulcetDatabase.Schema.version)
        }
        return driver
    }

    private fun inspectSchema(url: String): SchemaInspection =
        DriverManager.getConnection(url).use { connection ->
            val hasSchema = connection.prepareStatement(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'schema_meta'",
            ).use { statement ->
                statement.executeQuery().use { result -> result.next() && result.getLong(1) == 1L }
            }
            if (!hasSchema) return@use SchemaInspection(false, null)
            val pragmaVersion = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
            val version = if (pragmaVersion > 0) {
                pragmaVersion
            } else {
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT schema_version FROM schema_meta WHERE singleton_id = 1",
                    ).use { result -> if (result.next()) result.getLong(1) else null }
                }
            }
            SchemaInspection(true, version)
        }

    private fun setSqliteSchemaVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }

    private data class SchemaInspection(val hasSchema: Boolean, val version: Long?)
}
