package com.legitimateapps.dulcet.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One test per finding of the independent review of `d3968e45`, and per item R0 added. Each was
 * written first and observed failing against that commit; the failure it produced is quoted.
 */
class ReaderReviewFixesTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    private val noLookAhead = LibraryReaderConfig(lookAheadMaxPerViewport = 0)

    // ---- B1 -----------------------------------------------------------------------------------------

    /** Was: `NullPointerException` from `adoptScanStatus(read.after!!)`, uncaught in the reader scope. */
    @Test
    fun b1AFailedAfterReadingOnAScanningWindowNeitherCrashesNorAppends() = readerTest { env ->
        env.server.scanning = true
        val reader = env.reader(noLookAhead)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage)
        handle.setViewport(90, 99)
        env.server.failing += "getScanStatus"
        handle.loadMore()
        advanceUntilIdle()

        assertEquals(emptyList(), env.uncaught, "an exception escaped the reader's scope")
        assertEquals(100, pubs.last.items.size, "a page whose after-reading failed was appended")
        val cached = assertIs<LibraryFreshness.Cached>(pubs.last.freshness)
        assertIs<LibraryCachedReason.Failed>(cached.reason)
        assertEquals(LibraryCoverage.UnverifiedScanning, pubs.last.coverage, "a transient failure relabelled the window")
    }

    /** Was: the hook's exception escaped the reader's scope. */
    @Test
    fun b1NoExceptionEscapesTheReaderAndTheScreenSaysItFailed() = readerTest { env ->
        var explode = false
        val reader = env.reader(noLookAhead, overlay = { _, _ -> if (explode) error("hook failure") else emptyMap() })
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        explode = true
        handle.refresh()
        advanceUntilIdle()
        assertEquals(emptyList(), env.uncaught, "an exception escaped the reader's scope")
        // Building the publication itself threw, so the screen gets the raw internal-failure frame.
        val failed = assertIs<LibraryFreshness.Unavailable>(pubs.last.freshness)
        assertEquals(LibraryUnavailableReason.InternalFailure, failed.reason)

        // The next successful read clears it.
        explode = false
        handle.refresh()
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertEquals(100, pubs.last.items.size)
    }

    /**
     * A throw in a live operation OUTSIDE the publication build — here the store's write — is
     * caught by the operation itself, never by the scope's last-resort handler, and the screen
     * keeps its cached content labelled as an internal failure.
     */
    @Test
    fun b1AFailedWriteKeepsTheCachedContentAndSaysTheReaderFailed() = readerTest { env ->
        val reader = env.reader(noLookAhead)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        env.driver.failWrite = { it.contains("cache_list_member", ignoreCase = true) && it.trimStart().startsWith("INSERT", ignoreCase = true) }
        env.server.lastScan = "2026-09-22T11:00:00+02:00" // forces a rebase, which rewrites the window
        handle.refresh()
        advanceUntilIdle()
        assertEquals(emptyList(), env.uncaught, "the failure reached the scope's last-resort handler")
        val cached = assertIs<LibraryFreshness.Cached>(pubs.last.freshness)
        assertEquals(LibraryCachedReason.InternalFailure, cached.reason)
        assertEquals(100, pubs.last.items.size, "the cached content was replaced by the failure")
    }

    /** Confinement is enforced, not documented: an entry point off the reader's thread throws. */
    @Test
    fun b3EveryEntryPointRefusesACallerOffTheReadersThread() = readerTest { env ->
        val reader = env.reader(noLookAhead)
        reader.connect()
        val handle = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        val refused = mutableListOf<String>()
        val calls: List<Pair<String, suspend () -> Unit>> = listOf(
            "open" to { reader.open(grid, Publications(env.server)) },
            "setOnline" to { reader.setOnline(false) },
            "setForeground" to { reader.setForeground(true) },
            "republishPendingChanges" to { reader.republishPendingChanges(setOf("x")) },
            "connect" to { reader.connect() },
            "loadMore" to { handle.loadMore() },
            "loadBefore" to { handle.loadBefore() },
            "setViewport" to { handle.setViewport(0, 1) },
            "refresh" to { handle.refresh() },
            "close" to { handle.close() },
        )
        for ((name, call) in calls) {
            val thrown = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { call() }.exceptionOrNull()
            }
            if (thrown is IllegalStateException) refused += name
        }
        assertEquals(calls.map { it.first }, refused)
        // The positive control: the same calls on the reader's own thread are accepted.
        handle.setViewport(0, 1)
        reader.setOnline(true)
    }

    /**
     * A stamp that moves on every re-read with no scan reported is labelled `unverified(changing)`
     * once the bounded retries are spent — never `unverified(scanning)`, which would be a false
     * statement about the server (review nit: label exhausted tear retries honestly).
     */
    @Test
    fun anExhaustedTearRetryIsLabelledChangingNotScanning() = readerTest { env ->
        val reader = env.reader(noLookAhead)
        reader.connect()
        var tick = 0
        env.server.beforeRespond = { request ->
            if (request.endpoint == "getAlbumList2") {
                tick += 1
                env.server.lastScan = "2026-09-22T12:00:${tick.toString().padStart(2, '0')}+02:00"
            }
        }
        val pubs = Publications(env.server)
        reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(1 + reader.config.maxTearRetries, env.server.count("getAlbumList2"), "the retries are bounded")
        assertEquals(LibraryCoverage.UnverifiedChanging, pubs.last.coverage)
        assertEquals(100, pubs.last.items.size, "the content is shown, labelled")

        // Once the stamp holds still, the next revalidation rebases it to a guarded window.
        env.server.beforeRespond = {}
        reader.refreshEpoch()
        advanceUntilIdle()
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
    }

    // ---- B2 -----------------------------------------------------------------------------------------

    /** Was: `the top of the alphabetical grid is gone expected:<album-0000> but was:<album-0100>`. */
    @Test
    fun b2ANewSessionReopensAListFromItsStartAfterADeepRebase() = readerTest { env ->
        val reader = env.reader(noLookAhead)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        handle.setViewport(150, 160)
        env.server.lastScan = "2026-09-22T11:00:00+02:00"
        reader.refreshEpoch()
        advanceUntilIdle()
        assertEquals(albumId(100), pubs.ids().first(), "precondition: the rebase kept only the viewport's page")
        handle.close()

        val second = env.reader(noLookAhead)
        second.connect()
        val reopened = Publications(env.server)
        second.open(grid, reopened)
        advanceUntilIdle()
        assertEquals(albumId(0), reopened.ids().first(), "the top of the alphabetical grid is gone")
    }

    // ---- S1 -----------------------------------------------------------------------------------------

    /** Was: `look-ahead treated a detail read under the OLD epoch as current`. */
    @Test
    fun s1LookAheadJudgesADetailByTheEpochItWasReadUnderNotItsSummary() = readerTest { env ->
        val reader = env.reader()
        reader.connect()
        val handle = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        handle.setViewport(0, 5)
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        advanceUntilIdle()
        env.server.albums[2].songs += "${albumId(2)}-track-new"
        env.server.lastScan = "2026-09-22T11:00:00+02:00"
        reader.refreshEpoch() // re-reads the grid: every summary now carries the new epoch
        advanceUntilIdle()

        val before = env.server.log.size
        handle.setViewport(0, 5)
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        advanceUntilIdle()
        assertTrue(
            env.server.log.drop(before).any { it.endpoint == "getAlbum" && it.parameters["id"] == albumId(2) },
            "look-ahead treated a detail read under the OLD epoch as current",
        )
    }

    // ---- S2 -----------------------------------------------------------------------------------------

    /** Was: the open album was never re-read after the scan (1 `getAlbum`, expected 2). */
    @Test
    fun s2ADetailReadDuringAScanIsReReadWhenTheScanEnds() = readerTest { env ->
        env.server.scanning = true
        val reader = env.reader(noLookAhead)
        reader.connect()
        reader.open(LibraryQuery.Album(albumId(3)), Publications(env.server))
        advanceUntilIdle()
        env.server.scanning = false
        reader.refreshEpoch()
        advanceUntilIdle()
        assertEquals(2, env.server.log.count { it.endpoint == "getAlbum" }, "a detail read during a scan was treated as verified")
    }

    // ---- S3 -----------------------------------------------------------------------------------------

    /** Was: `getAlbum's order was not applied expected:<[t1, t2]> but was:<[t2, t1]>`. */
    @Test
    fun s3DetailMembershipIsOrderedByTheDetailReadNotBySummaries() = withSeenCache { handle, _ ->
        val cache = handle.bind(CacheBinding("s", "https://x", "u"))
        val detailSeq = cache.issue()
        val searchSeq = cache.issue()
        cache.writeEntities(CacheWriteStamp(searchSeq, 1, "e"), CacheEntitySource.Search, CacheEntities(tracks = listOf(CacheTrackRecord("t1", "A", "one"))))
        cache.writeAlbumDetail(CacheWriteStamp(detailSeq, 1, "e"), CacheAlbumRecord("A", "A"), listOf(CacheTrackRecord("t1", "A", "one"), CacheTrackRecord("t2", "A", "two")))
        assertEquals(listOf("t1", "t2"), cache.albumTracks("A").map { it.rawId }, "getAlbum's order was not applied")
    }

    /** Was: `expected:<[t1, t2]> but was:<[t2]>` — a list row moved t1 into album B. */
    @Test
    fun s3AListOrSearchRowNeverMovesAlbumMembership() = withSeenCache { handle, _ ->
        val cache = handle.bind(CacheBinding("s", "https://x", "u"))
        cache.writeAlbumDetail(CacheWriteStamp(cache.issue(), 1, "e"), CacheAlbumRecord("A", "A"), listOf(CacheTrackRecord("t1", "A", "one"), CacheTrackRecord("t2", "A", "two")))
        cache.writeEntities(CacheWriteStamp(cache.issue(), 1, "e"), CacheEntitySource.ListPage, CacheEntities(tracks = listOf(CacheTrackRecord("t1", "B", "one"))))
        assertEquals(listOf("t1", "t2"), cache.albumTracks("A").map { it.rawId })
        assertEquals(emptyList(), cache.albumTracks("B").map { it.rawId })
    }

    // ---- S4 -----------------------------------------------------------------------------------------

    /** Was: `released [list-0, list-1, list-2] and evicted no track`. */
    @Test
    fun s4TrackPressureReleasesTheLeastRecentlyAccessedAlbumDetailNotTheWindows() = withSeenCache(
        SeenCacheCeilings(albums = 50_000, tracks = 1, artists = 20_000, lists = 2_000),
    ) { handle, _ ->
        val cache = handle.bind(CacheBinding("s", "https://x", "u"))
        cache.writeAlbumDetail(CacheWriteStamp(cache.issue(), 1, "e"), CacheAlbumRecord("A", "A"), listOf(CacheTrackRecord("t1", "A", "one"), CacheTrackRecord("t2", "A", "two")))
        repeat(3) { index ->
            val seq = cache.issue()
            cache.writeWindowPage(
                CacheWriteStamp(seq, 1, "e"), CacheEntitySource.ListPage,
                CachedListState("list-$index", "e", setOf("f"), 0, 1, 1, CacheCoverage.Complete, 1, 1, seq),
                0, 1, listOf(CacheListMember(CacheItemKind.Album, "B$index")),
                CacheEntities(albums = listOf(CacheAlbumRecord("B$index", "B$index"))),
            )
        }
        val report = cache.evictIfNeeded()
        assertEquals(emptyList(), report.lists, "track pressure released windows")
        assertEquals(setOf("t1", "t2"), report.tracks.toSet())
        assertFalse(cache.album("A")!!.detailComplete, "the released detail must no longer claim its tracks")
        assertTrue(cache.album("A") != null, "releasing a detail keeps the album's summary")
    }

    // ---- S5 -----------------------------------------------------------------------------------------

    /** Was: `expected: not <UnverifiedNoEpoch>` — one failed status read labelled the window for good. */
    @Test
    fun s5ATransientStatusFailureIsRetriedAndNeverLabelsTheWindowNoEpoch() = readerTest { env ->
        val reader = env.reader(noLookAhead)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        env.server.failing += "getScanStatus"
        handle.loadMore()
        advanceUntilIdle()
        assertNotEquals(LibraryCoverage.UnverifiedNoEpoch, pubs.last.coverage)
        env.server.failing.clear()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(200, pubs.last.items.size)
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
    }

    // ---- S6 -----------------------------------------------------------------------------------------

    /** Was: `expected:<1> but was:<2>` — two handles read and wrote the same page concurrently. */
    @Test
    fun s6TwoHandlesOnOneListShareOneWriter() = readerTest { env ->
        val reader = env.reader(noLookAhead)
        reader.connect()
        val first = reader.open(grid, Publications(env.server))
        val second = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        first.setViewport(90, 99)
        second.setViewport(90, 99)
        val before = env.server.log.size
        // Hold the page so the first handle is still reading it when the second one starts.
        env.server.holdMatching = { it.endpoint == "getAlbumList2" && it.parameters["offset"] == "100" }
        first.loadMore()
        second.loadMore()
        runCurrent()
        env.server.holdMatching = null
        env.server.release()
        advanceUntilIdle()
        val pageReads = env.server.log.drop(before).count { it.endpoint == "getAlbumList2" && it.parameters["offset"] == "100" }
        assertEquals(1, pageReads)
    }

    // ---- S7 -----------------------------------------------------------------------------------------

    /** Was: hundreds of statements to republish 100 albums (two per item plus five touches). */
    @Test
    fun s7ARepublishCostsAFewStatementsNotSeveralPerItem() = readerTest { env ->
        val reader = env.reader(noLookAhead)
        reader.connect()
        reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        val mark = env.driver.statements.size
        reader.republishPendingChanges(setOf(albumId(5)))
        runCurrent()
        val used = env.driver.countSince(mark)
        assertTrue(used <= 12, "one republish of 100 albums ran $used statements")
    }

    /** Was: every open ran the four normalization scans over the whole cache. */
    @Test
    fun s7NormalizationBackfillRunsOnceNotOnEveryOpen() = readerTest { env ->
        val mark = env.driver.statements.size
        DulcetDatabaseStore.open(env.driver)
        val scans = env.driver.statements.drop(mark).count { it.contains("normalized_name IS NULL") || it.contains("normalized_title IS NULL") }
        assertEquals(0, scans, "reopening scanned the whole cache for unnormalized rows")
    }

    // ---- Untested rules the review's mutations survived ------------------------------------------------

    /** M1: the window's own dedupe, asserted in the store, where the publication cannot mask it. */
    @Test
    fun m1AWindowStoresEachIdOnceWhenAPageRepeatsOne() = readerTest { env ->
        val reader = env.reader(noLookAhead)
        reader.connect()
        val handle = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        // An insertion before the cursor that does not move the stamp shifts page 1 by one row.
        env.server.albums.add(0, FakeReaderServer.Album("album-aaaa", "Aaaa", mutableListOf("x")))
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        val ids = reader.cache.listMembers("getAlbumList2?type=alphabeticalByName").map { it.member.rawId }
        assertEquals(ids.distinct(), ids, "the window stored a repeated id")
    }

    /** M2: going offline cancels look-ahead that is already in flight. */
    @Test
    fun m2GoingOfflineCancelsInFlightLookAhead() = readerTest { env ->
        val reader = env.reader()
        reader.connect()
        val handle = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        env.server.holdMatching = { it.endpoint == "getAlbum" }
        handle.setViewport(0, 9)
        advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
        runCurrent()
        assertEquals(2, env.server.heldCount)
        reader.setOnline(false)
        runCurrent()
        assertEquals(2, env.server.cancelled, "look-ahead kept running offline")
    }

    /** M5: showing a window refreshes its albums' last access, which decides eviction order. */
    @Test
    fun m5ShowingAWindowRefreshesItsAlbumsLastAccess() = withSeenCache(
        // Ceiling 3: a pass over it evicts down to 2 (ceiling minus max(1, 1%)), so two go.
        SeenCacheCeilings(albums = 3, tracks = 1_000, artists = 1_000, lists = 1_000),
    ) { handle, clock ->
        val cache = handle.bind(CacheBinding("s", "https://x", "u"))
        clock.now = 1_000
        val seq = cache.issue()
        cache.writeWindowPage(
            CacheWriteStamp(seq, 1, "e"), CacheEntitySource.ListPage,
            CachedListState("list", "e", setOf("f"), 0, 1, 1, CacheCoverage.Complete, 1, 1, seq),
            0, 1, listOf(CacheListMember(CacheItemKind.Album, "shown")),
            CacheEntities(albums = listOf(CacheAlbumRecord("shown", "Shown"))),
        )
        clock.now = 2_000
        cache.writeEntities(CacheWriteStamp(cache.issue(), 1, "e"), CacheEntitySource.Search, CacheEntities(albums = listOf(CacheAlbumRecord("oldest", "Oldest"))))
        clock.now = 3_000
        cache.writeEntities(CacheWriteStamp(cache.issue(), 1, "e"), CacheEntitySource.Search, CacheEntities(albums = listOf(CacheAlbumRecord("older", "Older"))))
        clock.now = 5_000
        cache.touchList("list")
        cache.deleteList("list")
        clock.now = 6_000
        cache.writeEntities(CacheWriteStamp(cache.issue(), 1, "e"), CacheEntitySource.Search, CacheEntities(albums = listOf(CacheAlbumRecord("newest", "Newest"))))
        cache.evictIfNeeded()
        assertNull(cache.album("oldest"), "the least recently SHOWN albums must go first")
        assertNull(cache.album("older"), "the least recently SHOWN albums must go first")
        assertTrue(cache.album("shown") != null)
    }

    // ---- Nits ---------------------------------------------------------------------------------------

    /** Was: the detail summary kept `Search` — the request's sequence was taken before it waited. */
    @Test
    fun theIssueSequenceIsTakenWhenARequestIsSentNotWhileItWaits() = readerTest { env ->
        val reader = env.reader(LibraryReaderConfig(serverConcurrency = 1, lookAheadInFlight = 1, lookAheadMaxPerViewport = 0))
        reader.connect()
        env.server.holdMatching = { it.endpoint == "getAlbum" && it.parameters["id"] == albumId(1) }
        launch { reader.readAlbumDetail(albumId(1)) }
        runCurrent()
        launch { reader.readAlbumDetail(albumId(2)) } // waits for the only permit
        runCurrent()
        val search = reader.cache.issue() // a search SENT while getAlbum(2) was still waiting
        reader.cache.writeEntities(CacheWriteStamp(search, 1, null), CacheEntitySource.Search, CacheEntities(albums = listOf(CacheAlbumRecord(albumId(2), "From search"))))
        env.server.holdMatching = null
        env.server.release()
        advanceUntilIdle()
        assertEquals("Album 0002", reader.cache.album(albumId(2))?.record?.title, "a later-sent getAlbum lost to an earlier-sent search")
    }

    @Test
    fun anAlbumProvedGoneNoLongerClaimsACompleteDetail() = withSeenCache { handle, _ ->
        val cache = handle.bind(CacheBinding("s", "https://x", "u"))
        cache.writeAlbumDetail(CacheWriteStamp(cache.issue(), 1, "e"), CacheAlbumRecord("A", "A"), listOf(CacheTrackRecord("t1", "A", "one")))
        cache.markAlbumNotFound(cache.issue(), "A")
        assertFalse(cache.album("A")!!.detailComplete)
    }

    /** Was: 48 `getAlbum` for one unchanged viewport settled twice. */
    @Test
    fun lookAheadStaysWithin24AcrossOverlappingSettles() = readerTest { env ->
        val reader = env.reader()
        reader.connect()
        val handle = reader.open(grid, Publications(env.server))
        advanceUntilIdle()
        env.server.holdMatching = { it.endpoint == "getAlbum" }
        repeat(2) {
            handle.setViewport(0, 29)
            advanceTimeBy(reader.config.lookAheadSettleMillis + 1)
            runCurrent()
        }
        env.server.holdMatching = null
        env.server.release()
        advanceUntilIdle()
        assertTrue(env.server.count("getAlbum") <= 24, "look-ahead read ${env.server.count("getAlbum")} albums for one viewport")
    }

    // ---- R0 -----------------------------------------------------------------------------------------

    /**
     * R0's measured race (spec §16.12): a page read during a scan returns the list as it was before
     * the scan's change, and the status read after it reports the scan's stamp with no scan
     * running. Judged by its after-reading alone that page is accepted. Its BEFORE reading showed a
     * scan running, so the bracketed check must refuse it and re-read. Was: album-0005 (removed by
     * the scan) stayed in a window labelled `open`, read once.
     */
    @Test
    fun r0APageIsNeverAcceptedOnItsAfterReadingAlone() = readerTest { env ->
        env.server.scanning = true
        env.server.lastScan = "2026-09-22T10:05:00+02:00" // the scan's stamp, published before it clears
        val reader = env.reader(noLookAhead)
        reader.connect()
        var landed = false
        env.server.beforeRespond = { request ->
            if (request.endpoint == "getScanStatus" && !landed && env.server.log.any { it.endpoint == "getAlbumList2" }) {
                landed = true
                env.server.albums.removeAll { it.id == albumId(5) } // the scan's change becomes visible
                env.server.scanning = false
            }
        }
        val pubs = Publications(env.server)
        reader.open(grid, pubs)
        advanceUntilIdle()

        assertFalse(albumId(5) in pubs.ids(), "a page read during the scan was accepted as guarded")
        assertEquals(2, env.server.count("getAlbumList2"), "the stale page must be re-read once the scan is over")
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
    }

    /** The first-scan sentinel is no epoch; the first real stamp then rebases the window. */
    @Test
    fun r0TheSentinelIsNoEpochAndTheFirstRealStampRebases() = readerTest { env ->
        env.server.lastScan = "0001-01-01T00:00:00Z"
        val reader = env.reader(noLookAhead)
        reader.connect()
        val pubs = Publications(env.server)
        val handle = reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryCoverage.UnverifiedNoEpoch, pubs.last.coverage)
        env.server.lastScan = "2026-09-22T10:00:02+02:00"
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
    }

    @Test
    fun r0AWindowWithoutXTotalCountHasAnUnknownTotal() = readerTest { env ->
        env.server.sendTotalCount = false
        val reader = env.reader(noLookAhead)
        reader.connect()
        val pubs = Publications(env.server)
        reader.open(grid, pubs)
        advanceUntilIdle()
        assertNull(pubs.last.total)
        assertEquals(LibraryCoverage.Open, pubs.last.coverage)
    }
}
