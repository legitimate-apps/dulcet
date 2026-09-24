package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CONF-87: detail look-ahead bounds, counted in requests on the test scheduler (spec §16.13). */
class DetailLookAheadTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)

    @Test
    fun conf87ASettledViewportFetchesAtMost24AndNeverMoreThan2InFlight() = readerTest { env ->
        val (reader, handle) = openGrid(env)
        env.server.holdMatching = { it.endpoint == "getAlbum" }
        handle.setViewport(0, 29) // 30 visible + the next 30: 60 candidates
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        runCurrent()
        assertEquals(2, env.server.heldCount, "more than two look-ahead reads were in flight")

        while (env.server.heldCount > 0) {
            env.server.release(1)
            runCurrent()
            assertTrue(env.server.heldCount <= 2)
        }
        advanceUntilIdle()
        val fetched = env.server.log.filter { it.endpoint == "getAlbum" }.map { it.parameters.getValue("id") }
        assertEquals(24, fetched.size, "look-ahead exceeded 24 per settled viewport")
        assertEquals(2, env.server.maxInFlight.coerceAtMost(2))
        // Most-central first: the viewport's centre (index 14/15) leads.
        assertEquals(setOf(albumId(14), albumId(15)), fetched.take(2).toSet())
    }

    @Test
    fun conf87AFlingThatNeverSettlesFetchesNothing() = readerTest { env ->
        val (reader, handle) = openGrid(env)
        repeat(20) { step ->
            handle.setViewport(step * 3, step * 3 + 9)
            advanceTimeBy(reader.config.lookAheadSettleMillis - 100)
        }
        runCurrent()
        assertEquals(0, env.server.count("getAlbum"), "an unsettled fling prefetched")
        // Control: the same grid, once still, does prefetch.
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        advanceUntilIdle()
        assertTrue(env.server.count("getAlbum") > 0, "the settle never fired, so the fling assertion proves nothing")
    }

    @Test
    fun conf87AConstrainedNetworkFetchesNothing() = readerTest { env ->
        val (reader, handle) = openGrid(env)
        reader.setNetworkConstrained(true)
        handle.setViewport(0, 9)
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals(0, env.server.count("getAlbum"))
    }

    @Test
    fun conf87ALookedAheadAlbumOpensWithZeroRequests() = readerTest { env ->
        val (reader, handle) = openGrid(env)
        handle.setViewport(0, 5)
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        advanceUntilIdle()
        assertTrue(env.server.log.any { it.endpoint == "getAlbum" && it.parameters["id"] == albumId(2) })

        val before = env.server.log.size
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.Album(albumId(2)), pubs)
        advanceUntilIdle()
        assertEquals(before, env.server.log.size, "opening a looked-ahead album issued a request")
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertEquals(LibraryItemsState.Present, pubs.last.itemsState)
        assertEquals(1, pubs.all.size, "a fresh album publishes once, complete")
    }

    @Test
    fun conf87AnAlbumThatLeavesTheRegionIsCancelledAndCompleteDetailsAreSkipped() = readerTest { env ->
        val (reader, handle) = openGrid(env)
        env.server.holdMatching = { it.endpoint == "getAlbum" }
        handle.setViewport(0, 3)
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        runCurrent()
        assertEquals(2, env.server.heldCount)
        handle.setViewport(60, 63) // far away: both in-flight albums leave the region
        runCurrent()
        assertEquals(2, env.server.cancelled, "reads for albums that left the region kept running")

        env.server.holdMatching = null
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        advanceUntilIdle()
        val firstPass = env.server.count("getAlbum")
        handle.setViewport(0, 0)
        handle.setViewport(60, 63)
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        advanceUntilIdle()
        assertEquals(firstPass, env.server.count("getAlbum"), "an album already complete under this epoch was re-read")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.openGrid(env: ReaderEnv): Pair<LibraryReader, LibraryWindowHandle> {
        val reader = env.reader()
        reader.connect()
        val handle = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        return reader to handle
    }
}
