package com.legitimateapps.dulcet.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The migration fixtures' `stored_bytes` are written by `tools/generate_migration_fixture.py`,
 * which mirrors [lyricsStoredBytes] in Python. This ties the mirror to the Kotlin function: every
 * lyrics document in every committed fixture must store exactly what the store would have
 * computed for it, so a change to either side that the other does not follow fails here.
 */
class LyricsFixtureStoredBytesTest {
    @Test
    fun everyFixtureLyricsDocumentStoresWhatTheStoreComputes() {
        val fixtures = Path.of("../tools/migration-fixtures")
        assertTrue(fixtures.isDirectory(), "run from the core module: no $fixtures")
        var documents = 0
        var layers = 0
        for (fixture in fixtures.listDirectoryEntries().filter { it.name.matches(Regex("v[0-9]+")) }.sortedBy { it.name }) {
            val database = fixture.resolve("database.db")
            if (!database.exists()) continue
            // A copy: opening the committed file could leave a journal beside it.
            val copy = Files.createTempFile("dulcet-fixture-", ".db")
            try {
                Files.copy(database, copy, StandardCopyOption.REPLACE_EXISTING)
                DriverManager.getConnection("jdbc:sqlite:$copy").use { connection ->
                    if (!connection.hasTable("cache_lyrics")) return@use
                    for (row in connection.lyricsRows()) {
                        val stored = connection.layersOf(row.serverId, row.rawId)
                        layers += stored.size
                        documents += 1
                        assertEquals(
                            lyricsStoredBytes(row.serverId, row.rawId, lyricsSourceFromCacheWireName(row.source), stored),
                            row.storedBytes,
                            "${fixture.name}: ${row.rawId}",
                        )
                    }
                }
            } finally {
                Files.deleteIfExists(copy)
            }
        }
        // The control: the check read documents, and at least one with a layer and its lines.
        assertTrue(documents >= 2, "no lyrics documents found in any fixture ($documents)")
        assertTrue(layers >= 1, "no lyrics layers found in any fixture")
    }

    private class Row(val serverId: String, val rawId: String, val source: String, val storedBytes: Long)

    private fun Connection.hasTable(name: String): Boolean =
        prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { it.next() }
        }

    private fun Connection.lyricsRows(): List<Row> =
        createStatement().use { statement ->
            statement.executeQuery("SELECT server_id, raw_id, source, stored_bytes FROM cache_lyrics ORDER BY server_id, raw_id").use { rows ->
                buildList {
                    while (rows.next()) add(Row(rows.getString(1), rows.getString(2), rows.getString(3), rows.getLong(4)))
                }
            }
        }

    private fun Connection.layersOf(serverId: String, rawId: String): List<LyricsLayer> {
        val layers = prepareStatement(
            "SELECT layer_ordinal, language, synced, offset_milliseconds, kind, display_artist, display_title " +
                "FROM cache_lyrics_layer WHERE server_id = ? AND raw_id = ? ORDER BY layer_ordinal",
        ).use { statement ->
            statement.setString(1, serverId)
            statement.setString(2, rawId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            listOf(
                                rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getLong(4),
                                rows.getString(5), rows.getString(6), rows.getString(7),
                            ),
                        )
                    }
                }
            }
        }
        return layers.map { layer ->
            val ordinal = layer[0] as Long
            val lines = prepareStatement(
                "SELECT start_milliseconds, text FROM cache_lyrics_line " +
                    "WHERE server_id = ? AND raw_id = ? AND layer_ordinal = ? ORDER BY line_ordinal",
            ).use { statement ->
                statement.setString(1, serverId)
                statement.setString(2, rawId)
                statement.setLong(3, ordinal)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val start = rows.getLong(1).takeUnless { rows.wasNull() }
                            add(LyricsLine(rows.getString(2), start = start?.milliseconds))
                        }
                    }
                }
            }
            LyricsLayer(
                language = layer[1] as String?,
                synced = layer[2] == 1L,
                offset = (layer[3] as Long).milliseconds,
                lines = lines,
                kind = layer[4] as String?,
                displayArtist = layer[5] as String?,
                displayTitle = layer[6] as String?,
            )
        }
    }
}
