package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryReaderTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)

    // ---- CONF-76: the publication sequence ----------------------------------------------------------

    /**
     * An open with anything cached publishes it FIRST — before any request is issued and before any
     * loading state — and the whole sequence is asserted, because a spinner frame between two
     * correct states passes every test that checks only the end.
     */
    @Test
    fun conf76ACachedOpenPublishesBeforeAnyRequestAndNeverShowsLoading() = readerTest { env ->
        primeGrid(env)
        env.clock.now += 120_000

        val reader = env.reader()
        reader.connect()
        val issuedBeforeOpen = env.server.log.size
        val pubs = Publications(env.server)
        reader.open(grid, pubs)

        val first = pubs.all.first()
        assertEquals(issuedBeforeOpen, first.requestsIssued, "a request was issued before the cached paint")
        assertEquals(100, first.publication.items.size)
        val cached = assertIs<LibraryFreshness.Cached>(first.publication.freshness)
        assertEquals(LibraryCachedReason.Revalidating, cached.reason)
        assertNotNull(cached.asOfWall)

        advanceUntilIdle()
        assertTrue(pubs.freshness().none { it == LibraryFreshness.Loading }, "a loading frame appeared: ${pubs.freshness()}")
        assertTrue(pubs.all.all { it.publication.items.size == 100 }, "a frame dropped the cached content")
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    @Test
    fun conf76AGridOnlyAlbumPaintsItsCachedHeaderFirstAndOnlyItsTracksLoad() = readerTest { env ->
        val reader = primeGrid(env, keepReader = true)
        val issuedBeforeOpen = env.server.log.size
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.Album(albumId(7)), pubs)

        val first = pubs.all.first()
        assertEquals(issuedBeforeOpen, first.requestsIssued)
        assertEquals(albumId(7), first.publication.header?.rawId, "the cached summary header must paint at once")
        assertEquals(LibraryItemsState.Loading, first.publication.itemsState)

        advanceUntilIdle()
        assertTrue(pubs.all.all { it.publication.header != null }, "a frame lost the header")
        assertEquals(listOf("getAlbum[id=${albumId(7)}]"), env.server.log.drop(issuedBeforeOpen).map(Any::toString))
        assertEquals(LibraryItemsState.Present, pubs.last.itemsState)
        assertEquals(listOf("${albumId(7)}-track-0", "${albumId(7)}-track-1"), pubs.ids())
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    @Test
    fun conf76OfflineANeverOpenedAlbumIsUnavailableAndAGridOnlyAlbumShowsItsHeader() = readerTest { env ->
        val reader = primeGrid(env, keepReader = true)
        reader.setOnline(false)
        val before = env.server.log.size

        val never = Publications(env.server)
        reader.open(LibraryQuery.Album("album-never-seen"), never)
        assertEquals(
            listOf<LibraryFreshness>(LibraryFreshness.Unavailable(LibraryUnavailableReason.NotCachedOffline)),
            never.freshness(),
        )

        val gridOnly = Publications(env.server)
        reader.open(LibraryQuery.Album(albumId(3)), gridOnly)
        assertEquals(albumId(3), gridOnly.last.header?.rawId)
        assertEquals(LibraryItemsState.Unavailable, gridOnly.last.itemsState, "tracks never read are unavailable, not empty")
        assertEquals(LibraryFreshness.Cached(gridOnly.last.freshness.asOf(), LibraryCachedReason.Offline), gridOnly.last.freshness)

        advanceUntilIdle()
        assertEquals(before, env.server.log.size, "offline must issue no request")
    }

    @Test
    fun offlineAListNeverReadShowsTheLocalViewLabelledAsSuch() = readerTest { env ->
        val reader = primeGrid(env, keepReader = true)
        reader.setOnline(false)
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.AlbumList(AlbumListType.Newest), pubs)
        assertEquals(LibraryItemsOrder.LocalView, pubs.last.order)
        assertEquals(100, pubs.last.items.size)
    }

    // ---- CONF-77: the reconnect budget --------------------------------------------------------------

    @Test
    fun conf77ReconnectAfterAnEpochChangeIsFlushEpochVisibleScreenAndDownloadedAlbumsOnly() = readerTest { env ->
        val downloaded = setOf("${albumId(20)}-track-0", "${albumId(30)}-track-1")
        val reader = env.reader(
            downloads = { downloaded },
            outboxes = { env.server.markEvent("outbox-flush") },
        )
        reader.connect()
        val pubs = Publications(env.server)
        reader.open(grid, pubs)
        advanceUntilIdle()
        reader.readAlbumDetail(albumId(20))
        reader.readAlbumDetail(albumId(30))
        reader.setOnline(false)
        env.server.lastScan = "2026-09-22T11:00:00+02:00"
        env.clock.now += 120_000

        val before = env.server.events.size
        reader.reconnect()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "outbox-flush",
                "getScanStatus", "getMusicFolders",
                "getAlbumList2[offset=0][type=alphabeticalByName]", "getScanStatus",
                "getAlbum[id=${albumId(20)}]", "getAlbum[id=${albumId(30)}]",
            ),
            env.server.events.drop(before),
        )
    }

    @Test
    fun conf77ReconnectUnderAnUnchangedEpochRereadsOnlyTheVisibleScreen() = readerTest { env ->
        val reader = env.reader(downloads = { setOf("${albumId(20)}-track-0") }, outboxes = { env.server.markEvent("outbox-flush") })
        reader.connect()
        reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        reader.readAlbumDetail(albumId(20))
        env.clock.now += 120_000

        val before = env.server.events.size
        reader.reconnect()
        advanceUntilIdle()
        assertEquals(
            listOf("outbox-flush", "getScanStatus", "getMusicFolders", "getAlbumList2[offset=0][type=alphabeticalByName]", "getScanStatus"),
            env.server.events.drop(before),
            "user state is not covered by the epoch, so the visible screen is re-read; downloads are not",
        )

        // Within the revalidation interval, not even the visible screen is re-read.
        val again = env.server.events.size
        reader.reconnect()
        advanceUntilIdle()
        assertEquals(listOf("outbox-flush", "getScanStatus", "getMusicFolders"), env.server.events.drop(again))
    }

    @Test
    fun theForegroundEpochCadenceReadsTwoRequestsAndRevalidatesOnlyOnAChange() = readerTest { env ->
        val reader = env.reader(LibraryReaderConfig(lookAheadMaxPerViewport = 0))
        reader.connect()
        val pubs = Publications(env.server)
        reader.open(grid, pubs)
        advanceUntilIdle()
        reader.setForeground(true)
        env.clock.now += 400_000

        var before = env.server.events.size
        advanceTimeBy(reader.config.epochIntervalMillis)
        runCurrent()
        assertEquals(listOf("getScanStatus", "getMusicFolders"), env.server.events.drop(before), "an unchanged epoch re-reads nothing")

        env.server.lastScan = "2026-09-22T12:00:00+02:00"
        before = env.server.events.size
        advanceTimeBy(reader.config.epochIntervalMillis)
        runCurrent()
        assertEquals(
            listOf("getScanStatus", "getMusicFolders", "getAlbumList2[offset=0][type=alphabeticalByName]", "getScanStatus"),
            env.server.events.drop(before),
        )

        reader.setForeground(false)
        before = env.server.events.size
        advanceTimeBy(10 * reader.config.epochIntervalMillis)
        advanceUntilIdle()
        assertEquals(before, env.server.events.size, "nothing reads the epoch in the background")
    }

    // ---- CONF-83: gone-ness through the reader ------------------------------------------------------

    @Test
    fun conf83UnderPurgeMissingAlwaysAPinnedTrackAbsentFromGetAlbumIsGoneAndCode70MarksTheAlbum() = readerTest { env ->
        env.server.purgeMissingAlways = true
        val reader = env.reader()
        reader.connect()
        val album = albumId(9)
        reader.readAlbumDetail(album)
        val cache = env.store.bind(ReaderEnv.BINDING)
        cache.pin(CacheItemKind.Track, "$album-track-1", CachePinReason.Download)

        env.server.albums.first { it.id == album }.songs.removeAt(1)
        env.clock.now += 120_000
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.Album(album), pubs)
        advanceUntilIdle()
        assertEquals(true, cache.track("$album-track-1")?.row?.gone)
        assertTrue(cache.pins().any { it.rawId == "$album-track-1" }, "a gone pinned track keeps its pin")
        assertEquals(listOf("$album-track-0"), pubs.ids())

        env.server.removedAlbums += album
        env.clock.now += 120_000
        val removed = Publications(env.server)
        reader.open(LibraryQuery.Album(album), removed)
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Gone), removed.last.freshness)
        assertEquals(true, cache.album(album)?.row?.gone)
        assertEquals(true, cache.track("$album-track-0")?.row?.gone)
        assertFalse(env.server.log.any { it.endpoint == "getSong" }, "getSong is never a presence test")
    }

    @Test
    fun conf83UnderTheDefaultSettingAZeroSongAlbumIsGoneNotEmpty() = readerTest { env ->
        env.server.purgeMissingAlways = false
        val reader = env.reader()
        reader.connect()
        val album = albumId(11)
        reader.readAlbumDetail(album)
        val cache = env.store.bind(ReaderEnv.BINDING)
        cache.pin(CacheItemKind.Track, "$album-track-0", CachePinReason.Queue)

        env.server.removedAlbums += album // ok, songCount unchanged, zero songs
        env.clock.now += 120_000
        val pubs = Publications(env.server)
        reader.open(LibraryQuery.Album(album), pubs)
        advanceUntilIdle()

        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.Gone), pubs.last.freshness)
        assertEquals(true, cache.album(album)?.row?.gone)
        assertEquals(true, cache.track("$album-track-0")?.row?.gone, "a pinned track absent from getAlbum is gone")
        assertNotNull(cache.track("$album-track-0"), "and is kept, because it is pinned")
        assertEquals(2, cache.album(album)?.record?.songCount, "songCount is not a membership count")
    }

    // ---- CONF-86: home rows -------------------------------------------------------------------------

    @Test
    fun conf86HomeRowsPublishIndependentlyAndOneFailingRowLeavesTheOthersLive() = readerTest { env ->
        env.server.albums.take(3).forEach { it.starred = true }
        val rows = listOf(
            LibraryHomeRow.Albums(AlbumListType.Newest),
            LibraryHomeRow.Albums(AlbumListType.Recent),
            LibraryHomeRow.Favourites,
        )
        val first = env.reader()
        first.connect()
        first.openHome(rows) { _, _ -> }
        advanceUntilIdle()

        env.clock.now += 120_000
        env.server.failing += "getAlbumList2[type=recent]"
        env.server.holdMatching = { it.endpoint == "getStarred2" }
        val reader = env.reader()
        reader.connect()
        val before = env.server.log.size
        val byRow = List(3) { mutableListOf<Publications.Seen>() }
        reader.openHome(rows) { row, publication -> byRow[row] += Publications.Seen(publication, env.server.log.size) }

        byRow.forEachIndexed { row, seen ->
            val cached = assertIs<LibraryFreshness.Cached>(seen.first().publication.freshness, "row $row")
            assertEquals(LibraryCachedReason.Revalidating, cached.reason)
            assertEquals(before, seen.first().requestsIssued, "row $row painted after a request")
        }
        assertEquals(20, byRow[0].first().publication.items.size, "a home row is one page of 20")

        runCurrent()
        advanceUntilIdle()
        // Favourites is still held: the other rows did not wait for it.
        assertEquals(LibraryFreshness.Live, byRow[0].last().publication.freshness)
        val failed = assertIs<LibraryFreshness.Cached>(byRow[1].last().publication.freshness)
        assertIs<LibraryCachedReason.Failed>(failed.reason)
        assertEquals(20, byRow[1].last().publication.items.size, "an error never replaces cached content")
        assertEquals(LibraryCachedReason.Revalidating, (byRow[2].last().publication.freshness as LibraryFreshness.Cached).reason)

        env.server.release()
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, byRow[2].last().publication.freshness)
        assertEquals(3, env.server.log.size - before, "N rows cost N requests with a session epoch in hand")
        assertEquals(setOf("getAlbumList2", "getStarred2"), env.server.log.drop(before).map { it.endpoint }.toSet())
    }

    // ---- Overlay hook (consumed by R1d) -------------------------------------------------------------

    @Test
    fun aPendingLocalChangeIsOverlaidOnEveryPublicationAndNeverWrittenIntoTheCache() = readerTest { env ->
        var pending = emptyMap<String, PendingUserState>()
        val reader = env.reader(overlay = { _, ids -> pending.filterKeys { it in ids } })
        reader.connect()
        val pubs = Publications(env.server)
        reader.open(grid, pubs)
        advanceUntilIdle()
        pending = mapOf(albumId(4) to PendingUserState(starred = true))
        val before = env.server.log.size
        reader.republishPendingChanges(setOf(albumId(4)))
        assertEquals(before, pubs.all.last().requestsIssued, "the change must be in the next publication, before any request")
        assertEquals(true, (pubs.last.items[4] as LibraryItem.Album).starred)
        assertEquals(false, env.store.bind(ReaderEnv.BINDING).album(albumId(4))?.record?.userState?.starred)
    }

    // ---- Fixtures -----------------------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.primeGrid(env: ReaderEnv, keepReader: Boolean = false): LibraryReader {
        val reader = env.reader()
        reader.connect()
        val handle = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        if (!keepReader) handle.close()
        return reader
    }

    private fun LibraryFreshness.asOf(): Long? = (this as? LibraryFreshness.Cached)?.asOfWall
}
