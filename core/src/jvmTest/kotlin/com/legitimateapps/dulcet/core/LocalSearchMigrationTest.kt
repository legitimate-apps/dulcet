package com.legitimateapps.dulcet.core

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSearchMigrationTest {
    @Test fun releasedVersionFourLibraryIsSearchableAfterMigrationAndSecondReopen() {
        val path = Files.createTempFile("dulcet-search-migration-", ".db")
        try {
            Files.copy(Path.of("src/commonMain/sqldelight/databases/4.db"), path,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("PRAGMA user_version = 4")
                    sql.execute("INSERT OR REPLACE INTO schema_meta VALUES (1, 4, 1, 7)")
                    sql.execute("""INSERT INTO artist(server_id, raw_id, name, media_source_id,
                        content_key, valid_from_generation, valid_to_generation)
                        VALUES ('account', 'opaque:legacy/not-int', 'École Straße', NULL, 'key', 7, NULL)""")
                }
            }
            repeat(2) {
                val store = DulcetDriverFactory(path.toString()).openDulcetDatabase()
                try {
                    assertEquals(5, store.metadata().schemaVersion)
                    assertEquals(7, store.metadata().committedGeneration)
                    assertEquals(listOf("opaque:legacy/not-int"),
                        LocalLibrarySearch(store).search("account", "ecole strasse").map { it.id.rawId })
                    assertEquals(1, store.database.libraryQueries.selectSearchIndexVersion().executeAsOne())
                    assertEquals(emptyList(), store.database.libraryQueries.selectArtistSearchBackfill().executeAsList())
                } finally { store.close() }
            }
        } finally { Files.deleteIfExists(path) }
    }
}
