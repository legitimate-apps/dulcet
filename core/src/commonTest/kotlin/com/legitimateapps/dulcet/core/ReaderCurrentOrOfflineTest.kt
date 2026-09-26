package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §16.8's governing rule, from both sides: a reader never shows a server-backed screen as live
 * unless it has read it under the current epoch, and it sends nothing while it believes it is
 * offline. Each test drives the production [LibraryReaderSession] through a failure or a race that
 * used to break one half of it. Request counts, publication sequences and virtual time are
 * asserted, never wall time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderCurrentOrOfflineTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    private val storesTheEpoch: (String) -> Boolean = { it.contains("INTO cache_epoch") }

    private fun LibraryFreshness.label(): String =
        (this as? LibraryFreshness.Cached)?.reason?.let { "cached(" + it::class.simpleName + ")" } ?: this::class.simpleName.orEmpty()

    /** A session whose downloads hook names album 3's first track, throwing while [failing] says so. */
    private suspend fun TestScope.withDownloadedAlbum3(env: SessionEnv, failing: () -> Boolean, calls: () -> Unit): LibraryReaderSession {
        val session = LibraryReaderSession(
            env.database.database, env.cache(), env.server, env.scope,
            LibraryReaderConfig(lookAheadMaxPerViewport = 0),
            downloads = DownloadedTrackSource {
                calls()
                if (failing()) error("hook failed") else setOf("album-0003-track-0")
            },
        )
        session.reader.connect()
        session.reader.open(LibraryQuery.Album(albumId(3))) {}.also { advanceUntilIdle() }.close()
        assertTrue(env.cache().track("album-0003-track-0") != null, "fixture: the downloaded track is cached")
        return session
    }

    /** Loads the alphabetical grid to all 250 rows, so a shrink can put the viewport past the end. */
    private suspend fun TestScope.loadedTo250(handle: LibraryWindowHandle, pubs: Recorder<LibraryPublication>) {
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore()
        advanceUntilIdle()
        handle.setViewport(190, 199)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(250, pubs.last.items.size, "fixture: the whole list is loaded")
    }

    // ---- An epoch is adopted only once it is stored (S-1) -------------------------------------------------

    /**
     * An online reader whose foreground reconnect cannot STORE the new epoch keeps the old one, so
     * the next epoch-cadence reading still sees the change and re-reads the screen: a deleted album
     * stops showing as live at that reading, and the stored epoch is the server's. It used to adopt
     * the reading in memory first, so the cadence saw "unchanged" and the grid kept showing the
     * deleted album as live until the next foreground transition.
     */
    @Test
    fun anEpochTheReaderCouldNotStoreIsNotAdoptedSoTheNextReadingReReads() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness, "fixture: live")
        assertEquals(albumId(0), pubs.last.items.first().rawId, "fixture")
        env.server.base.albums.removeAt(0)
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        env.driver.failWrite = storesTheEpoch
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        env.driver.failWrite = null
        assertEquals(ReaderConnectionOutcome.InternalFailure, outcome, "fixture: the epoch could not be stored")
        assertTrue(session.reader.online, "an already-online reader stays online")

        val lists = env.server.count("getAlbumList2")
        session.reader.refreshEpoch() // the foreground cadence's next reading
        advanceUntilIdle()
        assertTrue(env.server.count("getAlbumList2") > lists, "the next reading did not re-read the screen")
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertEquals(albumId(1), pubs.last.items.first().rawId, "a deleted album is still shown")
        assertEquals("2026-09-24T10:00:00Z", env.cache().storedEpoch()?.lastScan, "the stored epoch is not the server's")
    }

    // ---- A retry re-runs the whole sequence (S-2, K1) -----------------------------------------------------

    /**
     * The downloaded-album recheck (§16.14 step 4) throws, and the reader is offline again. The next
     * report's reconnect rechecks the downloaded albums for that epoch change: the failed run had
     * adopted the reading, and the retry used to compare against it, see "unchanged", and skip it.
     */
    @Test
    fun aRetryAfterAnInternalFailureRechecksTheDownloadedAlbums() = sessionTest { env ->
        var failing = false
        var hookCalls = 0
        val session = withDownloadedAlbum3(env, { failing }, { hookCalls += 1 })
        session.setOnline(false)
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        failing = true
        val calls = hookCalls
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        assertTrue(hookCalls > calls, "fixture: the recheck ran and threw")
        assertEquals(ReaderConnectionOutcome.InternalFailure, outcome, "fixture")
        assertFalse(session.reader.online, "fixture: offline again")

        failing = false
        val mark = env.server.log.size
        val hooks = hookCalls
        session.setOnline(true)
        advanceUntilIdle()
        assertTrue(session.reader.online)
        assertEquals(
            listOf(albumId(3)),
            env.server.log.drop(mark).filter { it.endpoint == "getAlbum" }.map { it.parameters["id"] },
            "the retry did not recheck the downloaded album",
        )
        assertTrue(hookCalls > hooks, "the retry never asked for the downloaded tracks")
    }

    /**
     * K1, decided: an ALREADY-online reader whose reconnect fails internally stays online (its
     * screens were revalidated, or say what failed), and the next epoch-cadence reading runs the
     * step it still owes — here the downloaded-album recheck.
     */
    @Test
    fun anOnlineReaderStaysOnlineAfterAnInternalFailureAndTheNextReadingRunsTheOwedStep() = sessionTest { env ->
        var failing = false
        var hookCalls = 0
        val session = withDownloadedAlbum3(env, { failing }, { hookCalls += 1 })
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        failing = true
        val calls = hookCalls
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        assertTrue(hookCalls > calls, "fixture: the recheck ran and threw")
        assertEquals(ReaderConnectionOutcome.InternalFailure, outcome, "fixture")
        assertTrue(session.reader.online, "an already-online reader went offline on an internal failure")

        failing = false
        val mark = env.server.log.size
        session.reader.refreshEpoch()
        advanceUntilIdle()
        assertEquals(
            listOf(albumId(3)),
            env.server.log.drop(mark).filter { it.endpoint == "getAlbum" }.map { it.parameters["id"] },
            "the next reading did not run the recheck the failed reconnect owed",
        )
        // Completed now: a further reading under the same epoch owes nothing.
        val done = env.server.log.size
        session.reader.refreshEpoch()
        advanceUntilIdle()
        assertEquals(listOf("getScanStatus", "getMusicFolders"), env.server.endpoints().drop(done))
    }

    // ---- Nothing is sent while offline (S-3, K4) ----------------------------------------------------------

    /**
     * A rebase whose page read is in flight when the platform reports the server unreachable: once
     * the answer arrives nothing more is sent — no *after* reading, no re-anchor read — and nothing
     * is written. It used to send `getScanStatus`, a new `getAlbumList2` at offset 100 and another
     * `getScanStatus`, and show the rows it read under `offline`. Back online, the window is rebased
     * properly.
     */
    @Test
    fun nothingIsSentAfterAnUnreachableReport() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        loadedTo250(handle, pubs)
        handle.setViewport(210, 220)
        env.server.base.albums.subList(150, env.server.base.albums.size).clear()
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        env.server.base.holdMatching = { it.endpoint == "getAlbumList2" }
        env.scope.launch { session.reader.refreshEpoch() }
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "fixture: the rebase's page read is held")
        session.setOnline(false)
        advanceUntilIdle()
        val mark = env.server.log.size
        val stored = env.cache().listMembers(ListRequestSpec.of(grid).listKey)
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertEquals(emptyList(), env.server.log.drop(mark).map { it.endpoint + (it.parameters["offset"]?.let { o -> "@$o" } ?: "") })
        assertEquals(stored, env.cache().listMembers(ListRequestSpec.of(grid).listKey), "a page answered offline was written")
        assertEquals("cached(Offline)", pubs.last.freshness.label())

        session.setOnline(true)
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        assertEquals(albumId(149), pubs.last.items.last().rawId)
        assertTrue(session.reader.uncaughtFailures.isEmpty(), "uncaught: ${session.reader.uncaughtFailures}")
    }

    /**
     * A request that was started before the unreachable report and reaches the front of the queue
     * after it is refused unsent: the per-server bound is one slot, held by another read, and the
     * album screen's read waits for it. It used to go out once the slot freed.
     */
    @Test
    fun aRequestQueuedBeforeTheUnreachableReportIsNeverSent() = sessionTest { env ->
        val session = env.session(config = LibraryReaderConfig(lookAheadMaxPerViewport = 0, serverConcurrency = 1, lookAheadInFlight = 1))
        session.reader.connect()
        env.server.base.holdMatching = { it.endpoint == "getAlbum" && it.parameters["id"] == albumId(3) }
        session.reader.open(LibraryQuery.Album(albumId(3))) {}
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "fixture: album 3's read holds the only slot")
        val queued = Recorder<LibraryPublication>(env.server)
        session.reader.open(LibraryQuery.Album(albumId(5)), queued)
        advanceUntilIdle()
        assertEquals(0, env.server.log.count { it.parameters["id"] == albumId(5) }, "fixture: album 5's read waits for the slot")
        session.setOnline(false)
        advanceUntilIdle()
        val mark = env.server.log.size
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertEquals(emptyList(), env.server.endpoints().drop(mark), "a request queued before the report was sent offline")
        assertIs<LibraryFreshness.Unavailable>(queued.last.freshness)
        assertEquals(LibraryUnavailableReason.NotCachedOffline, (queued.last.freshness as LibraryFreshness.Unavailable).reason)
    }

    /**
     * A page answered after the unreachable report sends no *after* reading and leaves no failure
     * behind: back online under the same epoch the fresh window says `live`, not that a read failed.
     */
    @Test
    fun aPageAnsweredAfterTheUnreachableReportLeavesNoFailureBehind() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        env.server.base.holdMatching = { it.endpoint == "getAlbumList2" && it.parameters["offset"] == "100" }
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "fixture: the next page is held")
        session.setOnline(false)
        advanceUntilIdle()
        val mark = env.server.log.size
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertEquals(emptyList(), env.server.endpoints().drop(mark), "the page's after reading was sent offline")
        session.setOnline(true)
        advanceUntilIdle()
        assertTrue(session.reader.online)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness, "an offline page left a failure on the screen")
    }

    /**
     * A page read queued before the unreachable report and started after it sends nothing and
     * leaves no failure behind: it is not a failed read, it is one the reader did not make. Back
     * online under the same epoch the fresh window says `live`.
     */
    @Test
    fun aPageReadStartedAfterTheUnreachableReportLeavesNoFailureBehind() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(90, 99)
        handle.loadMore() // queued on the reader's scope, not yet run
        session.setOnline(false)
        val mark = env.server.log.size
        advanceUntilIdle()
        assertEquals(emptyList(), env.server.endpoints().drop(mark), "a page read was sent offline")
        session.setOnline(true)
        advanceUntilIdle()
        assertTrue(session.reader.online)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness, "a read the reader never made left a failure on the screen")
    }

    /**
     * K4's state: the platform reports the server reachable while the reader is offline (a
     * reconnect failed internally). `connect` still reads nothing — it is gated on the reader being
     * online, and every request on [LibraryReader.send]'s offline check besides.
     */
    @Test
    fun connectWhileReachableButOfflineSendsNothing() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.setOnline(false)
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        env.driver.failWrite = storesTheEpoch
        session.setOnline(true)
        runCurrent()
        env.driver.failWrite = null
        assertTrue(session.reader.reachable && !session.reader.online, "fixture: reachable, but offline after an internal failure")
        val mark = env.server.log.size
        assertNull(session.reader.connect())
        assertEquals(ReaderConnectionOutcome.Failed(DomainError.Transport.Unreachable), session.reader.connectReporting())
        runCurrent()
        assertEquals(emptyList(), env.server.endpoints().drop(mark), "connect read the server while offline")
        session.setOnline(false) // ends the retry the failure scheduled
    }

    // ---- One failed reading never ends the cadence (S-5) --------------------------------------------------

    /**
     * The foreground cadence's reading throws once (the epoch cannot be stored). The failure is
     * recorded, and the readings continue at their usual interval: exactly one per interval after
     * it. The cadence used to end at the first throw, for the rest of the foreground session.
     */
    @Test
    fun oneFailedReadingNeverEndsTheForegroundCadence() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.reader.open(grid) {}
        advanceUntilIdle()
        val interval = session.reader.config.epochIntervalMillis
        session.reader.setForeground(true)
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        env.driver.failWrite = storesTheEpoch
        val readings0 = env.server.count("getMusicFolders")
        advanceTimeBy(interval + 1)
        runCurrent()
        env.driver.failWrite = null
        assertEquals(1, env.server.count("getMusicFolders") - readings0, "fixture: one reading in the failing interval")
        assertEquals(1, session.reader.uncaughtFailures.size, "the failure was not reported")
        val readings1 = env.server.count("getMusicFolders")
        advanceTimeBy(interval * 3)
        runCurrent()
        assertEquals(3, env.server.count("getMusicFolders") - readings1, "readings over the three intervals after a failure")
        session.reader.setForeground(false)
    }

    // ---- A reachable reader retries an internal failure by itself (NIT 7) ---------------------------------

    /**
     * Offline, the platform reports the server reachable, and the reconnect fails internally. No
     * further report comes — a stable network sends none — yet the reader retries after
     * [LibraryReaderConfig.reconnectRetryInitialMillis], and not before.
     */
    @Test
    fun anInternalFailureWhileReachableIsRetriedWithoutAnotherReport() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.reader.open(grid) {}
        advanceUntilIdle()
        session.setOnline(false)
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        env.driver.failWrite = storesTheEpoch
        session.setOnline(true)
        runCurrent()
        env.driver.failWrite = null
        assertFalse(session.reader.online, "fixture: the reconnect failed internally")
        val readings = env.server.count("getMusicFolders")
        val wait = session.reader.config.reconnectRetryInitialMillis
        advanceTimeBy(wait - 1)
        runCurrent()
        assertEquals(readings, env.server.count("getMusicFolders"), "retried before the backoff")
        assertFalse(session.reader.online)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(readings + 1, env.server.count("getMusicFolders"), "no retry without a new report")
        assertTrue(session.reader.online)
    }

    /**
     * A failure that persists: the retries come after 2, 4, 8, 16, 32 and then 60 s (the cap), and
     * none after an unreachable report. A success resets the backoff, so the next internal failure
     * is retried after the first wait again.
     */
    @Test
    fun theRetryBacksOffDoublingToItsCapStopsWhenOfflineAndResetsOnSuccess() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.setOnline(false)
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        val readingTimes = mutableListOf<Long>()
        env.server.base.beforeRespond = { if (it.endpoint == "getMusicFolders") readingTimes += currentTime }
        env.driver.failWrite = storesTheEpoch
        session.setOnline(true)
        advanceTimeBy(200_000)
        runCurrent()
        assertFalse(session.reader.online, "fixture: every attempt failed")
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), readingTimes.zipWithNext { a, b -> b - a })

        session.setOnline(false)
        val stopped = readingTimes.size
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(stopped, readingTimes.size, "retried after an unreachable report")

        env.driver.failWrite = null
        session.setOnline(true)
        runCurrent()
        assertTrue(session.reader.online, "fixture: the reconnect succeeded")
        session.setOnline(false)
        env.server.base.lastScan = "2026-09-24T11:00:00Z"
        env.driver.failWrite = storesTheEpoch
        val start = currentTime
        readingTimes.clear()
        session.setOnline(true)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf(start, start + 2_000L), readingTimes, "the backoff was not reset by the success")
        session.setOnline(false)
    }

    /**
     * An internal failure while the platform reports the server UNREACHABLE is not retried — the
     * next reachable report runs the reconnect — and does not grow the backoff: the failure after
     * that report is retried after the first wait.
     */
    @Test
    fun anInternalFailureWhileUnreachableIsNotRetriedAndDoesNotGrowTheBackoff() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.setOnline(false)
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        val readingTimes = mutableListOf<Long>()
        env.server.base.beforeRespond = { if (it.endpoint == "getMusicFolders") readingTimes += currentTime }
        env.driver.failWrite = storesTheEpoch
        assertEquals(ReaderConnectionOutcome.InternalFailure, session.reader.reconnect(), "fixture")
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, readingTimes.size, "retried while the platform reports the server unreachable")
        val start = currentTime
        readingTimes.clear()
        session.setOnline(true)
        advanceTimeBy(session.reader.config.reconnectRetryInitialMillis + 1)
        runCurrent()
        assertEquals(listOf(start, start + session.reader.config.reconnectRetryInitialMillis), readingTimes, "the backoff grew while unreachable")
        session.setOnline(false)
    }

    /** Closing the reader (its scope) stops a pending retry. */
    @Test
    fun closingTheReaderStopsTheRetry() = sessionTest { env ->
        val readerScope = CoroutineScope(env.scope.coroutineContext + Job(env.scope.coroutineContext[Job]))
        val session = LibraryReaderSession(
            env.database.database, env.cache(), env.server, readerScope, LibraryReaderConfig(lookAheadMaxPerViewport = 0),
        )
        session.reader.connect()
        session.setOnline(false)
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        env.driver.failWrite = storesTheEpoch
        session.setOnline(true)
        runCurrent()
        assertFalse(session.reader.online, "fixture: the reconnect failed internally")
        val readings = env.server.count("getMusicFolders")
        readerScope.cancel()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(readings, env.server.count("getMusicFolders"), "a closed reader retried")
    }

    // ---- The person's viewport wins over a re-anchor (NIT 8) ----------------------------------------------

    /**
     * While a rebase's page read is in flight the person scrolls to the top. The list shrank past the
     * old viewport, so the rebase re-anchors — around the person's viewport, not its own: the
     * window starts at the top, covering it. It used to re-anchor on row 149
     * and overwrite the person's viewport with it.
     */
    @Test
    fun aViewportSetDuringARebaseWinsOverTheReAnchor() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        loadedTo250(handle, pubs)
        handle.setViewport(210, 220)
        env.server.base.albums.subList(150, env.server.base.albums.size).clear()
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        env.server.base.holdMatching = { it.endpoint == "getAlbumList2" && it.parameters["offset"] == "200" }
        env.scope.launch { session.reader.refreshEpoch() }
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "fixture: the rebase's page read is held")
        handle.setViewport(0, 5) // the person scrolled to the top meanwhile
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertEquals(listOf<Any?>(0, 100, albumId(0)), listOf(pubs.last.leadingOffset, pubs.last.items.size, pubs.last.items.first().rawId))
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    // ---- A foreground reconnect republishes only on change (NIT 9) ----------------------------------------

    /**
     * A fresh album screen reads nothing at a foreground reconnect under the same epoch, and so
     * publishes nothing: no redundant `live` frame. A fresh grid, the control, publishes nothing too.
     */
    @Test
    fun aFreshAlbumScreenPublishesNothingAtAForegroundReconnect() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val album = Recorder<LibraryPublication>(env.server)
        session.reader.open(LibraryQuery.Album(albumId(3)), album)
        val list = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, list)
        advanceUntilIdle()
        assertEquals(listOf(LibraryFreshness.Live, LibraryFreshness.Live), listOf(album.last.freshness, list.last.freshness), "fixture")
        val marks = album.all.size to list.all.size
        val albums = env.server.count("getAlbum")
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertEquals(albums, env.server.count("getAlbum"), "fixture: the album screen is fresh")
        assertEquals(listOf(0, 0), listOf(album.all.size - marks.first, list.all.size - marks.second), "[album, grid] publications")
    }

    // ---- The re-anchor boundary (K2) and an unanswerable reconnect check (K7) -----------------------------

    /**
     * A window anchored at row 20 of a list that shrinks to EXACTLY 20 rows re-anchors on row 19,
     * the last row that exists — its page is 10..19 — not on the top.
     */
    @Test
    fun aListThatShrinksToExactlyTheAnchorReAnchorsOnItsLastRow() = sessionTest { env ->
        val session = env.session(config = LibraryReaderConfig(lookAheadMaxPerViewport = 0, pageSize = 10))
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        val handle = session.reader.open(grid, pubs)
        advanceUntilIdle()
        handle.setViewport(5, 9)
        handle.loadMore()
        advanceUntilIdle()
        handle.setViewport(15, 19)
        handle.loadMore()
        advanceUntilIdle()
        assertEquals(30, pubs.last.items.size, "fixture: three pages of ten")
        handle.setViewport(20, 25)
        env.server.base.albums.subList(20, env.server.base.albums.size).clear()
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        session.reader.refreshEpoch()
        advanceUntilIdle()
        assertEquals(
            listOf<Any?>(20, 10, albumId(10), albumId(19)),
            listOf(pubs.last.total, pubs.last.leadingOffset, pubs.last.items.first().rawId, pubs.last.items.last().rawId),
        )
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    /**
     * A window whose own reconnect check cannot be answered (its stored state cannot be read ONCE)
     * assumes a read is coming: the transition publishes it `revalidating`, never `live` unread, and
     * its revalidation then settles it.
     */
    @Test
    fun aWindowWhoseReconnectCheckThrowsSaysRevalidatingAtTheTransition() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.setOnline(false)
        assertEquals("cached(Offline)", pubs.last.freshness.label(), "fixture")
        var refused = 0
        env.driver.failRead = { sql ->
            sql.startsWith("SELECT") && sql.contains("FROM cache_list WHERE server_id = ? AND list_key = ?") && refused++ == 0
        }
        val mark = pubs.all.size
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        env.driver.failRead = null
        assertEquals(1, env.driver.failedReads, "fixture: exactly the reconnect check could not be read")
        assertEquals(listOf("cached(Revalidating)", "Live"), pubs.all.drop(mark).map { it.value.freshness.label() })
    }
}
