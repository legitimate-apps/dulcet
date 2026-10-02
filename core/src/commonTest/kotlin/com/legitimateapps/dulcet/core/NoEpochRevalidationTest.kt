package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "No epoch" is never "unchanged" (spec §16.11, CONF-82). The 60-second rule spares a screen a
 * re-read only when it was read live UNDER THE CURRENT EPOCH; a server that reports no stamp — the
 * first-scan sentinel or an absent `lastScan` — has no current epoch, so two no-epoch readings
 * never make a window "read recently". The control: under a real, unchanged stamp the rule still
 * spares the re-read.
 */
class NoEpochRevalidationTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    private val playlist = LibraryQuery.Playlist("playlist-1")

    @Test
    fun aGridReopenedWithinTheIntervalIsReReadWhenTheServerReportsTheSentinel() = readerTest { env ->
        env.server.lastScan = "0001-01-01T00:00:00Z"
        assertEquals(1, reopenWithinInterval(env, grid, "getAlbumList2"), "a no-epoch window was treated as read under the current epoch")
    }

    @Test
    fun aGridReopenedWithinTheIntervalIsReReadWhenTheServerReportsNoLastScan() = readerTest { env ->
        env.server.lastScan = null
        assertEquals(1, reopenWithinInterval(env, grid, "getAlbumList2"), "a no-epoch window was treated as read under the current epoch")
    }

    @Test
    fun controlAGridReopenedWithinTheIntervalUnderAnUnchangedStampIsNotReRead() = readerTest { env ->
        assertTrue(env.server.lastScan != null && !isFirstScanSentinel(env.server.lastScan!!), "fixture: a real stamp")
        assertEquals(0, reopenWithinInterval(env, grid, "getAlbumList2"), "the 60-second rule must still spare a fresh window")
    }

    @Test
    fun aPlaylistReopenedWithinTheIntervalIsReReadWhenTheServerReportsNoEpoch() = readerTest { env ->
        env.server.lastScan = "0001-01-01T00:00:00Z"
        assertEquals(1, reopenWithinInterval(env, playlist, "getPlaylist"), "a no-epoch playlist was treated as read under the current epoch")
    }

    @Test
    fun controlAPlaylistReopenedWithinTheIntervalUnderAnUnchangedStampIsNotReRead() = readerTest { env ->
        assertEquals(0, reopenWithinInterval(env, playlist, "getPlaylist"), "the 60-second rule must still spare a fresh playlist")
    }

    /**
     * Opens [query], closes it, advances the wall clock well inside the interval, reopens it in the
     * same session, and returns how many [endpoint] requests the reopen made.
     */
    private suspend fun TestScope.reopenWithinInterval(env: ReaderEnv, query: LibraryQuery, endpoint: String): Int {
        val reader = env.reader(LibraryReaderConfig(lookAheadMaxPerViewport = 0))
        reader.connect()
        val first = Publications(env.server)
        reader.open(query, first).also { advanceUntilIdle() }.close()
        assertEquals(1, env.server.count(endpoint), "fixture: the first open reads once")
        assertEquals(LibraryFreshness.Live, first.last.freshness, "fixture: the first open was read live")

        env.clock.now += LibraryReaderConfig().revalidateWithinMillis / 6
        val before = env.server.count(endpoint)
        val second = Publications(env.server)
        reader.open(query, second)
        advanceUntilIdle()
        assertTrue(env.uncaught.isEmpty(), "uncaught: ${env.uncaught}")
        return env.server.count(endpoint) - before
    }
}
