package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CONF-82, driven through the production reader: the three tear rules, rebasing only the
 * viewport's pages, scanning mode, and the anchor kept by id (spec §16.12).
 *
 * Every assertion is on request counts, cache contents and publications — never wall time.
 */
class LibraryWindowTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)

    @Test
    fun aFreshWindowPaintsItsFirstPageWithOnePageReadAndOneScanStatus() = readerTest { env ->
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val before = env.server.log.size
        val pubs = Publications(env.server)
        reader.open(grid, pubs)
        advanceUntilIdle()

        assertEquals(listOf("getAlbumList2[offset=0][type=alphabeticalByName]", "getScanStatus"), env.server.log.drop(before).map(Any::toString))
        assertEquals(LibraryFreshness.Loading, pubs.all.first().publication.freshness)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertEquals((0 until 100).map(::albumId), pubs.ids())
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
        assertEquals(250, pubs.last.total)
    }

    /**
     * Tear rule 1. A scan removes an album BEFORE the cursor while page 1 is being read, so the
     * server's page 1 is shifted by one: stitched to the old page 0 it would silently skip
     * album-0100 (the §16.1 silent skip, OBSERVED 2026-09-22). The after-reading fires, the window
     * is rebased around the viewport, and paging on yields every surviving album exactly once.
     */
    @Test
    fun conf82AFiredCheckTearsTheWindowAndPagingOnSkipsNothing() = readerTest { env ->
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        env.server.beforeRespond = scanRemovingAlbumFiveDuringPage(env.server, offset = "100")

        val before = env.server.log.size
        handle.loadMore()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "getAlbumList2[offset=100][type=alphabeticalByName]",
                "getScanStatus", // fired: the stamp moved
                "getAlbumList2[offset=0][type=alphabeticalByName]", // rebase: the viewport's page only
                "getScanStatus",
            ),
            env.server.log.drop(before).map(Any::toString),
        )
        val survivors = env.server.albums.map { it.id }
        assertEquals(survivors.take(100), pubs.ids(), "the torn page must not be appended; the window is the re-read page")

        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(survivors.take(200), pubs.ids(), "a window read across a scan skipped or duplicated an album")
        pubs.all.forEach { assertConsistent(it.publication) }
    }

    /**
     * The epoch check must sit BEFORE the write that advances the window. Here the rebase that a
     * fired check starts cannot reach the server, so the damage — a torn page already stitched on —
     * would stay visible. It must never be published, and the retry one call further out must
     * still produce a window with no skip.
     */
    @Test
    fun conf82TheCheckPrecedesTheAdvancingWriteEvenWhenTheRebaseFails() = readerTest { env ->
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        val race = scanRemovingAlbumFiveDuringPage(env.server, offset = "100")
        var armed = false
        env.server.beforeRespond = { request ->
            race(request)
            if (request.endpoint == "getScanStatus" && env.server.lastScan == STAMP_AFTER_SCAN && !armed) {
                armed = true
                env.server.failing += "getAlbumList2" // the rebase's re-read fails
            }
        }
        handle.loadMore()
        advanceUntilIdle()

        val window = env.store.bind(ReaderEnv.BINDING).listState(GRID_KEY)
        assertEquals(100, window?.endLoadedOffset, "the torn page advanced the window before the check")
        assertIs<LibraryFreshness.Cached>(pubs.last.freshness)
        pubs.all.forEach { assertConsistent(it.publication) }

        env.server.failing.clear()
        handle.setViewport(90, 99)
        handle.loadMore() // the retry: stored window epoch is stale -> rebase, not append
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(env.server.albums.map { it.id }.take(200), pubs.ids())
        pubs.all.forEach { assertConsistent(it.publication) }
    }

    /** Tear rule 2: a window cached under last session's stamp is rebased at its first live read. */
    @Test
    fun conf82AStoredEpochMismatchAtTheFirstLiveReadRebasesAndIsNeverExtended() = readerTest { env ->
        loadTwoPages(env)
        env.server.lastScan = STAMP_AFTER_SCAN
        env.clock.now += 3_600_000

        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        assertEquals(200, pubs.last.items.size, "the cached window paints first")
        handle.setViewport(90, 99)
        val before = env.server.log.size
        // Extending before the first live read must rebase, never stitch today's page 2 onto
        // yesterday's pages 0 and 1.
        handle.loadMore()
        advanceUntilIdle()

        val issued = env.server.log.drop(before).map(Any::toString)
        assertEquals(
            listOf(
                "getAlbumList2[offset=0][type=alphabeticalByName]", "getScanStatus", // the rebase
                "getAlbumList2[offset=100][type=alphabeticalByName]", "getScanStatus", // then the extension
            ),
            issued,
            "a stale window was extended before it was rebased",
        )
        val window = assertNotNull(env.store.bind(ReaderEnv.BINDING).listState(GRID_KEY))
        assertEquals(catalogEpochKey(STAMP_AFTER_SCAN, setOf("folder-1")), window.windowEpoch)
        assertEquals(0, window.firstLoadedOffset)
        assertEquals(200, window.endLoadedOffset)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    /** Tear rule 3: the same stamp but a different folder set is a different catalog for this user. */
    @Test
    fun conf82AFolderSetChangeTearsTheWindow() = readerTest { env ->
        loadTwoPages(env)
        env.server.folders = listOf("folder-1", "folder-2")

        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        reader.open(grid, Publications(env.server))
        advanceUntilIdle()

        val window = assertNotNull(env.store.bind(ReaderEnv.BINDING).listState(GRID_KEY))
        assertEquals(catalogEpochKey(STAMP, setOf("folder-1", "folder-2")), window.windowEpoch)
        assertEquals(100, window.endLoadedOffset, "a folder-set change must rebase, dropping page 1")
    }

    /** The control for rules 2 and 3: an unchanged epoch revalidates in place and keeps every page. */
    @Test
    fun conf82AnUnchangedEpochRevalidatesInPlaceAndKeepsTheWindow() = readerTest { env ->
        loadTwoPages(env)
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        assertEquals(200, env.store.bind(ReaderEnv.BINDING).listState(GRID_KEY)?.endLoadedOffset)
    }

    /** A rebase re-reads the pages intersecting the viewport — not the depth of the scroll. */
    @Test
    fun conf82ARebaseReadsOnlyTheViewportPagesAndKeepsTheAnchorById() = readerTest { env ->
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        repeat(2) {
            handle.setViewport(pubs.last.items.size - 10, pubs.last.items.size - 1)
            handle.loadMore()
            advanceUntilIdle()
        }
        assertEquals(300 - 50, pubs.last.items.size) // 250 albums, three pages
        handle.setViewport(150, 160) // looking at album-0150..album-0160, inside page 1
        env.server.albums.removeAt(5)
        env.server.lastScan = STAMP_AFTER_SCAN

        val before = env.server.log.size
        reader.reconnect()
        advanceUntilIdle()

        val pageReads = env.server.log.drop(before).filter { it.endpoint == "getAlbumList2" }.map(Any::toString)
        assertEquals(listOf("getAlbumList2[offset=100][type=alphabeticalByName]"), pageReads)
        val published = pubs.last
        assertEquals(env.server.albums.map { it.id }.subList(100, 200), pubs.ids(published))
        val anchor = assertNotNull(published.anchor)
        assertEquals(albumId(150), anchor.itemRawId, "the first visible item must stay first")
        assertEquals(published.items.indexOfFirst { it.rawId == albumId(150) }, anchor.index)
    }

    @Test
    fun conf82WhenTheFirstVisibleItemIsGoneTheNearestSurvivingPredecessorAnchors() = readerTest { env ->
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(40, 50)
        env.server.albums.removeAll { it.id == albumId(40) }
        env.server.lastScan = STAMP_AFTER_SCAN
        reader.reconnect()
        advanceUntilIdle()
        assertEquals(LibraryAnchor(albumId(39), 39), pubs.last.anchor)
    }

    /**
     * Scanning mode: pages append (scrolling never freezes), unguarded and labelled; the first
     * reading with no scan running rebases the window.
     *
     * The server publishes the new stamp BEFORE it clears `scanning` (OBSERVED 12 of 12 trials,
     * spec §16.11), so a window opened late in a scan already holds the final stamp, and the
     * reading that ends the scan shows an UNCHANGED stamp. Only "scanning cleared" can trigger the
     * rebase then — a stamp comparison alone would append a guarded page to an unguarded window.
     */
    @Test
    fun conf82WhileScanningPagesAppendUnverifiedAndTheScanEndRebasesEvenUnderAnUnchangedStamp() = readerTest { env ->
        env.server.scanning = true
        env.server.lastScan = STAMP_AFTER_SCAN // already published, scan still running
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage)
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(200, pubs.last.items.size, "scanning must not freeze scrolling")
        assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage)

        env.server.scanning = false // the stamp does not move again
        handle.setViewport(190, 199)
        val before = env.server.log.size
        handle.loadMore()
        advanceUntilIdle()

        val issued = env.server.log.drop(before).map(Any::toString)
        assertEquals(
            listOf(
                "getAlbumList2[offset=200][type=alphabeticalByName]", "getScanStatus",
                "getAlbumList2[offset=100][type=alphabeticalByName]", "getScanStatus",
            ),
            issued,
            "the scan's end must rebase around the viewport, not append",
        )
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
        assertEquals((100 until 200).map(::albumId), pubs.ids())
        // The rebased window is open on BOTH sides (review B2): it says where it starts, and the
        // top of the list is reachable again under the same bracketed check.
        assertEquals(100, pubs.last.leadingOffset)
        val beforeTop = env.server.log.size
        handle.loadBefore()
        advanceUntilIdle()
        assertEquals(
            listOf("getAlbumList2[offset=0][type=alphabeticalByName]", "getScanStatus"),
            env.server.log.drop(beforeTop).map(Any::toString),
        )
        assertEquals((0 until 200).map(::albumId), pubs.ids())
        assertEquals(0, pubs.last.leadingOffset)
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
    }

    @Test
    fun conf82AReconnectThatSeesTheScanEndedRebasesAWindowReadWhileScanning() = readerTest { env ->
        env.server.scanning = true
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage)

        env.server.scanning = false
        reader.reconnect()
        advanceUntilIdle()
        assertEquals(LibraryCoverage.Open, pubs.last.coverage, "the label must clear once no scan is running")
    }

    @Test
    fun aServerWithNoEpochIsUnverifiedAndDeduplicatedByIdOnly() = readerTest { env ->
        env.server.lastScan = "0001-01-01T00:00:00Z"
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryCoverage.UnverifiedNoEpoch, pubs.last.coverage)
        // An insertion before the cursor shifts page 1 by one: the duplicate is dropped by id.
        env.server.albums.add(0, FakeReaderServer.Album("album-aaaa", "Aaaa", mutableListOf("x")))
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(pubs.ids().distinct(), pubs.ids())
        assertEquals(199, pubs.last.items.size)
    }

    @Test
    fun theWindowCompletesOnTheTotalAndOnAConfirmedShortPage() = readerTest { env ->
        env.server.albums.subList(150, 250).clear()
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(LibraryCoverage.Complete, pubs.last.coverage, "row count equals X-Total-Count")
        assertEquals(150, pubs.last.items.size)

        // Without a total, a short page is only a candidate end, confirmed by one more request.
        env.server.sendTotalCount = false
        val other = env.reader(NO_LOOK_AHEAD)
        other.connect()
        val songs = Publications(env.server)
        val newest = other.open(LibraryQuery.AlbumList(AlbumListType.Newest), songs)
        advanceUntilIdle()
        newest.setViewport(90, 99)
        val before = env.server.log.size
        newest.loadMore()
        advanceUntilIdle()
        val offsets = env.server.log.drop(before).filter { it.endpoint == "getAlbumList2" }.map { it.parameters["offset"] }
        assertEquals(listOf("100", "150"), offsets, "a short page must be confirmed by exactly one more request")
        assertEquals(LibraryCoverage.Complete, songs.last.coverage)
    }

    @Test
    fun loadMoreNeverReadsMoreThanOnePageBeyondTheViewport() = readerTest { env ->
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(0, 9)
        handle.loadMore()
        advanceUntilIdle()
        val before = env.server.log.size
        handle.loadMore() // the viewport is still at the top: page 2 would be two pages beyond it
        advanceUntilIdle()
        assertEquals(before, env.server.log.size)
    }

    // ---- Fixtures -----------------------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.loadTwoPages(env: ReaderEnv) {
        val reader = env.reader(NO_LOOK_AHEAD)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(200, pubs.last.items.size)
        handle.close()
    }

    /** A scan that removes album-0005 between a page's arrival at the server and its answer. */
    private fun scanRemovingAlbumFiveDuringPage(server: FakeReaderServer, offset: String): (FakeReaderServer.Request) -> Unit {
        var done = false
        return { request ->
            if (!done && request.endpoint == "getAlbumList2" && request.parameters["offset"] == offset) {
                done = true
                server.albums.removeAll { it.id == albumId(5) }
                server.lastScan = STAMP_AFTER_SCAN
            }
        }
    }

    /**
     * A published list is consistent when it could have been read from one server state: it never
     * holds album-0005 (only in the pre-scan catalog) together with album-0200 at position 199
     * (only reachable in the post-scan catalog's second page), and never repeats an id.
     */
    private fun assertConsistent(publication: LibraryPublication) {
        val ids = publication.items.map { it.rawId }
        assertEquals(ids.distinct(), ids, "a published window repeated an id")
        val stitched = albumId(5) in ids && ids.indexOf(albumId(200)) == 199
        assertFalse(stitched, "a page read under one stamp was stitched to a page read under another")
        assertTrue(ids.size <= 250)
    }

    private companion object {
        const val STAMP = "2026-09-22T10:00:00.123+02:00"
        const val STAMP_AFTER_SCAN = "2026-09-22T10:05:00.5+02:00"
        const val GRID_KEY = "getAlbumList2?type=alphabeticalByName"

        /** Look-ahead has its own tests (CONF-87); here it would add getAlbum reads to every count. */
        val NO_LOOK_AHEAD = LibraryReaderConfig(lookAheadMaxPerViewport = 0)
    }
}
