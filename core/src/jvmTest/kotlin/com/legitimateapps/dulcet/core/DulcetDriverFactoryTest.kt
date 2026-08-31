package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.legitimateapps.dulcet.database.DulcetDatabase
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DulcetDriverFactoryTest {
    @Test
    fun jvmFactoryEnablesForeignKeys() {
        val driver = DulcetDriverFactory(":memory:").createDriver()
        try {
            assertTrue(driver.foreignKeysEnabled())
        } finally {
            driver.close()
        }
    }

    @Test
    fun jvmFactoryCreatesAndReopensTheVersionedDatabase() {
        val path = Files.createTempFile("dulcet-driver-factory-", ".db")
        Files.delete(path)
        try {
            val first = DulcetDriverFactory(path.toString()).openDulcetDatabase()
            first.updateCommittedGeneration(73)
            first.close()

            val reopened = DulcetDriverFactory(path.toString()).openDulcetDatabase()
            assertEquals(DULCET_SCHEMA_VERSION, reopened.metadata().schemaVersion)
            assertEquals(73, reopened.metadata().committedGeneration)
            reopened.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun interruptedVersionTwoMigrationRollsBackSchemaAndVersionMarkers() {
        val path = Files.createTempFile("dulcet-interrupted-migration-", ".db")
        try {
            createVersionTwoDatabase(path.toString())
            val interruptedSchema = object : SqlSchema<QueryResult.Value<Unit>> {
                override val version: Long = DulcetDatabase.Schema.version

                override fun create(driver: SqlDriver): QueryResult.Value<Unit> =
                    DulcetDatabase.Schema.create(driver)

                override fun migrate(
                    driver: SqlDriver,
                    oldVersion: Long,
                    newVersion: Long,
                    vararg callbacks: AfterVersion,
                ): QueryResult.Value<Unit> {
                    DulcetDatabase.Schema.migrate(driver, oldVersion, newVersion, *callbacks).value
                    driver.execute(null, "CREATE TABLE interrupted_migration_marker(id INTEGER)", 0)
                        .value
                    throw MigrationInterruption
                }
            }

            assertFailsWith<MigrationInterruption> {
                DulcetDriverFactory(path.toString(), interruptedSchema).createDriver()
            }

            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                assertEquals(2, connection.pragmaUserVersion())
                assertEquals(2, connection.metadataSchemaVersion())
                assertFalse(connection.tableExists("music_folder"))
                assertFalse(connection.tableExists("interrupted_migration_marker"))
            }

            val recovered = DulcetDriverFactory(path.toString()).openDulcetDatabase()
            assertEquals(DULCET_SCHEMA_VERSION, recovered.metadata().schemaVersion)
            recovered.close()
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun disagreeingMigrationVersionMarkersFailClosed() {
        val path = Files.createTempFile("dulcet-partial-migration-", ".db")
        try {
            createVersionTwoDatabase(path.toString())
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE partial_migration_marker(id INTEGER)")
                    statement.execute("PRAGMA user_version = 3")
                }
            }

            assertFailsWith<IllegalStateException> {
                DulcetDriverFactory(path.toString()).createDriver()
            }

            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                assertTrue(connection.tableExists("partial_migration_marker"))
                assertEquals(3, connection.pragmaUserVersion())
                assertEquals(2, connection.metadataSchemaVersion())
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun createVersionTwoDatabase(path: String) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE schema_meta (
                      singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
                      schema_version INTEGER NOT NULL CHECK (schema_version > 0),
                      cache_format_version INTEGER NOT NULL CHECK (cache_format_version > 0),
                      committed_generation INTEGER NOT NULL CHECK (committed_generation >= 0)
                    )
                    """.trimIndent(),
                )
                statement.execute("INSERT INTO schema_meta VALUES (1, 2, 1, 42)")
                // v2 already shipped the protected download table. The original interruption
                // fixture omitted it because v2 -> v3 did not read it; v3 -> v4 legitimately does.
                statement.execute(
                    """
                    CREATE TABLE download (
                      server_id TEXT NOT NULL,
                      raw_id TEXT NOT NULL,
                      transcode_profile TEXT NOT NULL,
                      download_id TEXT NOT NULL,
                      state TEXT NOT NULL CHECK (
                        state IN ('queued', 'downloading', 'interrupted', 'complete', 'stale')
                      ),
                      file_relative_path TEXT NOT NULL,
                      expected_byte_length INTEGER,
                      file_size_bytes INTEGER NOT NULL DEFAULT 0 CHECK (file_size_bytes >= 0),
                      platform_resume_data BLOB,
                      resume_data_created_at_wall_clock INTEGER,
                      PRIMARY KEY (server_id, raw_id, transcode_profile),
                      UNIQUE (download_id),
                      UNIQUE (file_relative_path),
                      CHECK (expected_byte_length IS NULL OR expected_byte_length >= 0)
                    )
                    """.trimIndent(),
                )
                statement.execute("PRAGMA user_version = 2")
            }
        }
    }

    private fun java.sql.Connection.pragmaUserVersion(): Long =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next())
                result.getLong(1)
            }
        }

    private fun java.sql.Connection.metadataSchemaVersion(): Long =
        createStatement().use { statement ->
            statement.executeQuery(
                "SELECT schema_version FROM schema_meta WHERE singleton_id = 1",
            ).use { result ->
                check(result.next())
                result.getLong(1)
            }
        }

    private fun java.sql.Connection.tableExists(name: String): Boolean =
        prepareStatement(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        ).use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { result -> result.next() && result.getLong(1) == 1L }
        }

    private object MigrationInterruption : RuntimeException()
}
