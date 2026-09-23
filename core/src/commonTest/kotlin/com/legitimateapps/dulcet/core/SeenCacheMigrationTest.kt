package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * CONF-81, the additive step (spec §16.17): a database exactly as released schema 5 left it is
 * migrated by the real `5.sqm`, then opened by the production store.
 *
 * Each assertion names the seeded shape it pins, and the unverified case is the control that the
 * verified case's rows came from the seeding rule and not from something unconditional.
 */
class SeenCacheMigrationTest {
    @Test
    fun conf81VerifiedGenerationSeedsTheExactShapeAndPinsEveryDownloadAndQueueEntry() = withMigratedDatabase(
        stability = "verified",
    ) { store, migratedAt ->
        val queries = store.database.seenCacheQueries

        // Seeded from the committed generation only: closed and future rows are not copied.
        assertEquals(setOf("album:one", "album:two"), albumIds(store))
        assertEquals(setOf("artist:one"), artistIds(store))
        assertNull(queries.selectArtist(SERVER, "artist:closed").executeAsOneOrNull(), "a row closed before the committed generation was seeded")
        assertNull(queries.selectArtist(OTHER_SERVER, "artist:other").executeAsOneOrNull(), "another account's rows were seeded from this account's generation")

        val albumOne = assertNotNull(queries.selectAlbum(SERVER, "album:one").executeAsOneOrNull())
        assertNull(albumOne.fetched_epoch, "seeded rows are stale: no epoch")
        assertNull(albumOne.fetched_at_wall, "seeded rows have unknown age, never a fabricated one")
        assertTrue(albumOne.last_access_wall in migratedAt, "last access is the migration time, got ${albumOne.last_access_wall}")
        assertEquals(1, albumOne.detail_complete, "a verified generation holds every album's full membership")
        assertEquals(2, albumOne.song_count)
        assertEquals(1, albumOne.starred, "starred comes from library_starred")
        assertNull(albumOne.starred_at, "starred time is unknown")
        assertNull(albumOne.user_rating, "per-user fields are unknown, never false")
        assertNull(albumOne.play_count)
        assertNull(albumOne.played)
        assertEquals(0, albumOne.gone)
        assertEquals("Album One", albumOne.title)
        assertEquals("album one", albumOne.normalized_title, "open backfills normalization the mirror never had")

        val albumTwo = assertNotNull(queries.selectAlbum(SERVER, "album:two").executeAsOneOrNull())
        assertNull(albumTwo.starred, "an item absent from library_starred is unknown, not false")

        val trackTwo = assertNotNull(queries.selectTrack(SERVER, "track:two").executeAsOneOrNull())
        assertEquals(1, trackTwo.starred)
        assertEquals(0, trackTwo.metadata_missing)
        assertEquals("album:one", trackTwo.album_raw_id)
        assertNull(queries.selectTrack(SERVER, "track:one").executeAsOne().starred)

        assertEquals(
            listOf("album_artist" to "Artist One"),
            queries.selectCredits(SERVER, "album", "album:one").executeAsList().map { it.role to it.name },
        )

        assertEquals(0, queries.countLists(SERVER).executeAsOne(), "the mirror has no server order: no windows")

        assertEquals(
            setOf(
                Triple("track", "track:one", "download"),
                Triple("album", "album:one", "download"),
                Triple("track", "track:missing", "download"),
                Triple("track", "track:two", "queue"),
                Triple("album", "album:one", "queue"),
            ),
            pins(store, SERVER),
        )
        // album:two and track:three are referenced by no download or queue entry: unpinned.
        val missing = assertNotNull(queries.selectTrack(SERVER, "track:missing").executeAsOneOrNull())
        assertEquals(1, missing.metadata_missing, "a download absent from the generation keeps an identity-only row")
        assertNull(missing.title)

        assertEquals(PROTECTED_ROWS, protectedRows(store.driver), "protected rows changed")
        assertEquals(DULCET_SCHEMA_VERSION, store.metadata().schemaVersion)
        assertEquals(7, store.metadata().committedGeneration, "the mirror stays readable until R5")
    }

    @Test
    fun conf81UnverifiedGenerationSeedsNothingButThePins() = withMigratedDatabase(
        stability = "unverified",
    ) { store, _ ->
        assertEquals(emptySet(), albumIds(store), "an unverified generation seeded albums")
        assertEquals(emptySet(), artistIds(store))
        assertEquals(
            setOf(
                Triple("track", "track:one", "download"),
                Triple("track", "track:missing", "download"),
                Triple("track", "track:two", "queue"),
            ),
            pins(store, SERVER),
            "pins must not depend on the generation's stability; albums are pinned only when cached",
        )
        val one = assertNotNull(store.database.seenCacheQueries.selectTrack(SERVER, "track:one").executeAsOneOrNull())
        assertEquals(1, one.metadata_missing)
        assertEquals(PROTECTED_ROWS, protectedRows(store.driver))
    }

    @Test
    fun conf81ReopeningTheMigratedDatabaseIsIdempotent() = withMigratedDatabase(stability = "verified") { store, _ ->
        val before = pins(store, SERVER) to albumIds(store)
        val reopened = DulcetDatabaseStore.open(store.driver)
        assertEquals(before, pins(reopened, SERVER) to albumIds(reopened))
        assertFalse(store.database.seenCacheQueries.selectIssueCounter().executeAsList().isEmpty())
    }

    /**
     * Per account (review nit): the mirror commits one account at a time, so another account's
     * rows are valid at ITS latest generation, not the globally committed one. A zero-track album
     * is seeded without a complete detail — "no tracks in the mirror" is not "an album with none".
     */
    @Test
    fun conf81EachAccountIsSeededFromItsOwnLatestVerifiedGeneration() = withMigratedDatabase(
        stability = "verified",
        extra = listOf(
            "INSERT INTO sync_generation VALUES (5, '$OTHER_SERVER', 'verified')",
            // Generation numbers are global across accounts.
            "INSERT INTO sync_generation VALUES (3, '$THIRD_SERVER', 'verified')",
            "INSERT INTO sync_generation VALUES (4, '$THIRD_SERVER', 'unverified')",
            "INSERT INTO artist(server_id, raw_id, name, media_source_id, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$THIRD_SERVER', 'artist:third', 'Third', NULL, 'k', 3, NULL)",
            "INSERT INTO album(server_id, raw_id, title, artist_name, artist_raw_id, year, duration_milliseconds, media_source_id, artwork_key, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$SERVER', 'album:empty', 'Empty', NULL, NULL, NULL, 0, NULL, NULL, 'k', 7, NULL)",
        ),
    ) { store, _ ->
        val queries = store.database.seenCacheQueries
        assertNotNull(queries.selectArtist(OTHER_SERVER, "artist:other").executeAsOneOrNull(), "another account was left unseeded")
        assertNull(
            queries.selectArtist(THIRD_SERVER, "artist:third").executeAsOneOrNull(),
            "an account whose latest generation is unverified was seeded from an older one",
        )
        val empty = assertNotNull(queries.selectAlbum(SERVER, "album:empty").executeAsOneOrNull())
        assertEquals(0, empty.detail_complete, "a zero-track album was seeded as a complete, empty membership")
        // The control: an album WITH tracks in the same generation is complete, with its members ordered.
        assertEquals(1, queries.selectAlbum(SERVER, "album:one").executeAsOne().detail_complete)
        assertEquals(
            listOf("track:one", "track:two"),
            store.driver.strings("SELECT raw_id FROM cache_track WHERE server_id = '$SERVER' AND album_raw_id = 'album:one' ORDER BY album_ordinal"),
        )
    }

    private fun withMigratedDatabase(
        stability: String,
        extra: List<String> = emptyList(),
        block: (DulcetDatabaseStore, LongRange) -> Unit,
    ) {
        val driver = createReleasedSchemaTestDriver(RELEASED_SCHEMA_5_STATEMENTS)
        try {
            seedVersionFive(driver, stability)
            extra.forEach { driver.execute(null, it, 0) }
            val before = Clock.System.now().toEpochMilliseconds()
            DulcetDatabase.Schema.migrate(driver, 5, DulcetDatabase.Schema.version)
            val after = Clock.System.now().toEpochMilliseconds()
            val store = DulcetDatabaseStore.open(driver)
            // strftime('%s') truncates to the second.
            block(store, (before / 1000 * 1000)..(after / 1000 * 1000 + 1000))
        } finally {
            driver.close()
        }
    }

    private fun seedVersionFive(driver: SqlDriver, stability: String) {
        listOf(
            "INSERT INTO schema_meta VALUES (1, 5, 1, 7)",
            "INSERT INTO sync_generation VALUES (7, '$SERVER', '$stability')",
            "INSERT INTO sync_generation VALUES (6, '$SERVER', 'verified')",
            "INSERT INTO artist(server_id, raw_id, name, media_source_id, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$SERVER', 'artist:one', 'Artist One', NULL, 'k', 5, NULL)",
            "INSERT INTO artist(server_id, raw_id, name, media_source_id, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$SERVER', 'artist:closed', 'Closed', NULL, 'k', 3, 7)",
            "INSERT INTO artist(server_id, raw_id, name, media_source_id, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$OTHER_SERVER', 'artist:other', 'Other', NULL, 'k', 5, NULL)",
            "INSERT INTO album(server_id, raw_id, title, artist_name, artist_raw_id, year, duration_milliseconds, media_source_id, artwork_key, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$SERVER', 'album:one', 'Album One', 'Artist One', 'artist:one', 2001, 120000, NULL, 'al-1', 'k', 7, NULL)",
            "INSERT INTO album(server_id, raw_id, title, artist_name, artist_raw_id, year, duration_milliseconds, media_source_id, artwork_key, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$SERVER', 'album:two', 'Album Two', NULL, NULL, NULL, 60000, NULL, NULL, 'k', 6, NULL)",
            "INSERT INTO album(server_id, raw_id, title, artist_name, artist_raw_id, year, duration_milliseconds, media_source_id, artwork_key, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$SERVER', 'album:future', 'Future', NULL, NULL, NULL, 1, NULL, NULL, 'k', 8, NULL)",
            trackInsert("track:one", "album:one", "One"),
            trackInsert("track:two", "album:one", "Two"),
            trackInsert("track:three", "album:two", "Three"),
            "INSERT INTO credit(server_id, seen_key, owner_kind, owner_raw_id, role, ordinal, name, artist_raw_id, content_key, valid_from_generation, valid_to_generation) " +
                "VALUES ('$SERVER', 'c1', 'album', 'album:one', 'album_artist', 0, 'Artist One', 'artist:one', 'k', 7, NULL)",
            "INSERT INTO library_starred VALUES ('$SERVER', 'album', 'album:one', 's1', 7, NULL)",
            "INSERT INTO library_starred VALUES ('$SERVER', 'track', 'track:two', 's2', 7, NULL)",
            "INSERT INTO library_starred VALUES ('$SERVER', 'album', 'album:two', 's3', 3, 6)",
            "INSERT INTO download(server_id, raw_id, transcode_profile, download_id, state, file_relative_path, expected_byte_length, file_size_bytes) " +
                "VALUES ('$SERVER', 'track:one', 'direct', 'd1', 'complete', 'downloads/one.bin', 4, 4)",
            "INSERT INTO download(server_id, raw_id, transcode_profile, download_id, state, file_relative_path, expected_byte_length, file_size_bytes) " +
                "VALUES ('$SERVER', 'track:missing', 'direct', 'd2', 'queued', 'downloads/missing.bin', NULL, 0)",
            "INSERT INTO queue_state VALUES ('$SERVER', 0, 'off', 0)",
            "INSERT INTO queue_entry VALUES ('$SERVER', 'q1', 'track:two', 'album', 'album:one', 'Album One', 'play_now', 0, 0)",
        ).forEach { driver.execute(null, it, 0) }
    }

    private fun trackInsert(rawId: String, album: String, title: String) =
        "INSERT INTO track(server_id, raw_id, album_raw_id, title, artist_name, artist_raw_id, album_title, disc_number, track_number, duration_milliseconds, source_container, media_source_id, artwork_key, content_key, valid_from_generation, valid_to_generation) " +
            "VALUES ('$SERVER', '$rawId', '$album', '$title', NULL, NULL, NULL, 1, 1, 1000, 'flac', NULL, NULL, 'k', 6, NULL)"

    private fun albumIds(store: DulcetDatabaseStore): Set<String> =
        store.driver.strings("SELECT raw_id FROM cache_album WHERE server_id = '$SERVER'").toSet()

    private fun artistIds(store: DulcetDatabaseStore): Set<String> =
        store.driver.strings("SELECT raw_id FROM cache_artist WHERE server_id = '$SERVER'").toSet()

    private fun pins(store: DulcetDatabaseStore, server: String): Set<Triple<String, String, String>> =
        store.database.seenCacheQueries.selectPins(server).executeAsList()
            .mapTo(mutableSetOf()) { Triple(it.item_kind, it.raw_id, it.reason) }

    private fun protectedRows(driver: SqlDriver): List<String> =
        driver.strings("SELECT server_id || '|' || raw_id || '|' || download_id || '|' || state || '|' || file_relative_path || '|' || file_size_bytes FROM download ORDER BY download_id") +
            driver.strings("SELECT server_id || '|' || queue_entry_id || '|' || raw_id || '|' || playback_position FROM queue_entry ORDER BY queue_entry_id")

    private companion object {
        const val SERVER = "server:account"
        const val OTHER_SERVER = "server:other"
        const val THIRD_SERVER = "server:third"
        val PROTECTED_ROWS = listOf(
            "server:account|track:one|d1|complete|downloads/one.bin|4",
            "server:account|track:missing|d2|queued|downloads/missing.bin|0",
            "server:account|q1|track:two|0",
        )
    }
}

internal fun SqlDriver.strings(sql: String): List<String> = executeQuery(
    identifier = null,
    sql = sql,
    mapper = { cursor ->
        val values = mutableListOf<String>()
        while (cursor.next().value) values += cursor.getString(0) ?: "<null>"
        QueryResult.Value(values)
    },
    parameters = 0,
).value
