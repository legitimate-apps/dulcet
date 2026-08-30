package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import java.sql.DriverManager
import java.util.Properties

internal actual class DulcetDriverFactory(
    private val databasePath: String,
    private val schema: SqlSchema<QueryResult.Value<Unit>> = DulcetDatabase.Schema,
) {
    init {
        require(databasePath.isNotBlank())
    }

    actual fun createDriver(): SqlDriver {
        val url = "jdbc:sqlite:$databasePath"
        val inspection = inspectSchema(url)
        if (inspection.hasSchema) {
            val metadataVersion = inspection.metadataVersion
            val pragmaVersion = inspection.pragmaVersion
            check(pragmaVersion == 0L || metadataVersion == null || pragmaVersion == metadataVersion) {
                "Dulcet database schema version markers disagree"
            }
            val oldVersion = pragmaVersion.takeIf { it > 0 } ?: metadataVersion
                ?: error("Existing Dulcet database has no recoverable schema version")
            check(oldVersion <= schema.version) {
                "Dulcet database schema is newer than this build"
            }
            if (pragmaVersion == 0L) setSqliteSchemaVersion(url, oldVersion)
        }
        return JdbcSqliteDriver(
            url = url,
            properties = Properties().apply { put("foreign_keys", "true") },
            schema = MetadataRecordingSchema(schema),
        )
    }

    private fun inspectSchema(url: String): SchemaInspection =
        DriverManager.getConnection(url).use { connection ->
            val hasSchema = connection.prepareStatement(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'schema_meta'",
            ).use { statement ->
                statement.executeQuery().use { result -> result.next() && result.getLong(1) == 1L }
            }
            if (!hasSchema) return@use SchemaInspection(false, 0, null)
            val pragmaVersion = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
            val metadataVersion = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT schema_version FROM schema_meta WHERE singleton_id = 1",
                ).use { result -> if (result.next()) result.getLong(1) else null }
            }
            SchemaInspection(true, pragmaVersion, metadataVersion)
        }

    private fun setSqliteSchemaVersion(url: String, version: Long) {
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA user_version = $version")
            }
        }
    }

    private data class SchemaInspection(
        val hasSchema: Boolean,
        val pragmaVersion: Long,
        val metadataVersion: Long?,
    )

    private class MetadataRecordingSchema(
        private val delegate: SqlSchema<QueryResult.Value<Unit>>,
    ) : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = delegate.version

        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = delegate.create(driver)

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> {
            delegate.migrate(driver, oldVersion, newVersion, *callbacks).value
            driver.execute(
                identifier = null,
                sql = "UPDATE schema_meta SET schema_version = $newVersion WHERE singleton_id = 1",
                parameters = 0,
            ).value
            return QueryResult.Value(Unit)
        }
    }
}
