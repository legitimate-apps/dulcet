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
                    // The generation the released v4 sync committed; the additive step seeds from it.
                    sql.execute("INSERT INTO sync_generation(generation, server_id, stability) VALUES (7, 'account', 'verified')")
                    sql.execute("""INSERT INTO artist(server_id, raw_id, name, media_source_id,
                        content_key, valid_from_generation, valid_to_generation)
                        VALUES ('account', 'opaque:legacy/not-int', 'École Straße', NULL, 'key', 7, NULL)""")
                }
            }
            repeat(2) {
                val store = DulcetDriverFactory(path.toString()).openDulcetDatabase()
                try {
                    assertEquals(DULCET_SCHEMA_VERSION, store.metadata().schemaVersion)
                    assertEquals(7, store.metadata().committedGeneration)
                    assertEquals(listOf("opaque:legacy/not-int"),
                        LocalLibrarySearch(store).search("account", "ecole strasse").map { it.id.rawId })
                    assertEquals(1, store.database.libraryQueries.selectSearchIndexVersion().executeAsOne())
                    assertEquals(emptyList(), store.database.libraryQueries.selectArtistSearchBackfill().executeAsList())
                    // Revision 99 (§16.15, §16.17): the reader's local half searches the seen-cache, which
                    // the additive step seeded from this committed generation. The seeded row carries no
                    // normalized text of its own until open backfills it.
                    val seen = SeenCacheStore(store, SeenCacheWallClock { 1L })
                        .bind(CacheBinding("account", "https://music.example", "listener"))
                    assertEquals(listOf("opaque:legacy/not-int"),
                        SeenCacheSearch(seen).search("ecole strasse").map { it.id.rawId })
                    assertEquals(listOf("opaque:legacy/not-int"),
                        SeenCacheSearch(seen).search("ÉCOLE").map { it.id.rawId })
                    assertEquals(1L, SeenCacheSearch(seen).counts().artists)
                } finally { store.close() }
            }
        } finally { Files.deleteIfExists(path) }
    }
}
