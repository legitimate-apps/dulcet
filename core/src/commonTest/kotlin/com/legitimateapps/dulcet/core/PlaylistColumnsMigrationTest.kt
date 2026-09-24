package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Schema 7 (spec §18.6): `6.sqm` adds a playlist's comment, visibility and `readonly` to
 * `cache_playlist`. A database exactly as released schema 6 left it, holding a cached playlist, is
 * migrated by the real `6.sqm` and opened by the production store; a fresh install is built from the
 * current schema. Both paths must end with one table — the same columns in the same order — that
 * keeps what the cache writes, and the playlist cached before the upgrade is still there, its new
 * fields unknown.
 */
class PlaylistColumnsMigrationTest {
    @Test
    fun anUpgradeFromSchemaSixKeepsTheCachedPlaylistWithItsNewFieldsUnknown() = withUpgraded { driver ->
        val store = DulcetDatabaseStore.open(driver)
        assertEquals(7L, store.metadata().schemaVersion)
        assertEquals(listOf("pl-1|Road|<null>|<null>|<null>"), driver.strings(PLAYLIST_ROWS), "the cached playlist did not survive")
        val cached = assertNotNull(SeenCacheStore(store, ManualWallClock()).bind(BINDING).playlist("pl-1"))
        assertEquals(CachePlaylistRecord("pl-1", "Road", songCount = 2, owner = "listener"), cached.record)
    }

    @Test
    fun anUpgradedTableAndAFreshOneAreTheSameTable() {
        val fresh = withFresh { it.strings(COLUMNS) }
        val upgraded = withUpgraded { it.strings(COLUMNS) }
        // The control: the three columns are in the fresh install, so "equal" is not two empty lists.
        assertTrue(fresh.takeLast(3).map { it.substringBefore('|') } == listOf("comment", "is_public", "readonly"), "fresh: $fresh")
        assertEquals(fresh, upgraded)
    }

    @Test
    fun bothPathsKeepWhatTheCacheWrites() {
        val stated = CachePlaylistRecord("pl-1", "Road", songCount = 2, owner = "listener", comment = "for the car", isPublic = true, readonly = false)
        val unstated = CachePlaylistRecord("pl-2", "Morning", songCount = 0, owner = "listener")
        val paths = listOf<Pair<String, ((SqlDriver) -> Unit) -> Unit>>("fresh" to { withFresh(it) }, "upgraded" to { withUpgraded(it) })
        paths.forEach { (path, with) ->
            with { driver ->
                val cache = SeenCacheStore(DulcetDatabaseStore.open(driver), ManualWallClock()).bind(BINDING)
                cache.writeEntities(CacheWriteStamp(cache.issue(), 2, null), CacheEntitySource.Detail, CacheEntities(playlists = listOf(stated, unstated)))
                assertEquals(stated, assertNotNull(cache.playlist("pl-1")).record, path)
                assertEquals(unstated, assertNotNull(cache.playlist("pl-2")).record, path)
                assertEquals(listOf("pl-1|Road|for the car|1|0", "pl-2|Morning|<null>|<null>|<null>"), driver.strings(PLAYLIST_ROWS), path)
            }
        }
    }

    private fun <T> withFresh(block: (SqlDriver) -> T): T {
        val driver = createTestDriver()
        try {
            DulcetDatabaseStore.open(driver)
            return block(driver)
        } finally {
            driver.close()
        }
    }

    private fun <T> withUpgraded(block: (SqlDriver) -> T): T {
        val driver = createReleasedSchemaTestDriver(RELEASED_SCHEMA_6_STATEMENTS)
        try {
            driver.execute(null, "INSERT INTO schema_meta VALUES (1, 6, 1, 0)", 0)
            driver.execute(null, SEED_PLAYLIST, 0)
            DulcetDatabase.Schema.migrate(driver, 6, DulcetDatabase.Schema.version)
            return block(driver)
        } finally {
            driver.close()
        }
    }

    private companion object {
        const val SERVER = "server:account"
        val BINDING = CacheBinding(SERVER, "https://music.example", "listener")

        /** A playlist as schema 6 cached it — every column that schema has, and nothing else — seeded at issue 0. */
        const val SEED_PLAYLIST =
            "INSERT INTO cache_playlist(server_id, raw_id, name, song_count, duration_milliseconds, owner, artwork_key, " +
                "detail_complete, detail_issue_seq, fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone) " +
                "VALUES ('$SERVER', 'pl-1', 'Road', 2, NULL, 'listener', NULL, 0, 0, 1, NULL, 0, 1, 0)"

        const val PLAYLIST_ROWS =
            "SELECT raw_id || '|' || name || '|' || coalesce(comment, '<null>') || '|' || coalesce(is_public, '<null>') || '|' || " +
                "coalesce(readonly, '<null>') FROM cache_playlist ORDER BY raw_id"

        const val COLUMNS =
            "SELECT name || '|' || type || '|' || \"notnull\" || '|' || coalesce(dflt_value, '<none>') || '|' || pk " +
                "FROM pragma_table_info('cache_playlist') ORDER BY cid"
    }
}
