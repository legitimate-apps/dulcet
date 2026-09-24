package com.legitimateapps.dulcet.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reconnect's step sequence (§16.14), driven through the production [LibraryReaderSession]:
 * one sequence at a time; a failure inside it strands nothing; a screen says `revalidating` only
 * when a read is coming; an epoch change reaches open searches; and the platform's reachability
 * report is the reader's, never overwritten. Request counts and publication sequences are
 * asserted, never wall time.
 */
class ReaderReconnectStepsTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    private val album3 = LibraryEntityRef(LibraryEntityKind.Album, albumId(3))

    /** Opens the alphabetical grid once so the cache holds albums 0000..0099 and their epoch. */
    private suspend fun TestScope.primed(env: SessionEnv, session: LibraryReaderSession = env.session()): LibraryReaderSession {
        session.reader.connect()
        session.reader.open(grid) {}.also { advanceUntilIdle() }.close()
        return session
    }

    private suspend fun TestScope.offlineSearch(session: LibraryReaderSession, env: SessionEnv): Recorder<LibrarySearchPublication> {
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(LibrarySearchConfig(debounceMillis = 0), pubs).updateQuery("Album 002")
        advanceUntilIdle()
        assertIs<SearchScope.DeviceOffline>(pubs.last.scope, "fixture: the search says offline")
        return pubs
    }

    private fun LibraryFreshness.label(): String =
        (this as? LibraryFreshness.Cached)?.reason?.let { "cached(" + it::class.simpleName + ")" } ?: this::class.simpleName.orEmpty()

    // ---- A failure inside the sequence strands nothing ----------------------------------------------------

    /**
     * A screen's listener that throws at the transition is the listener's failure, not the
     * reconnect's: the sequence runs on, and the open search is revalidated. It used to end the
     * reconnect as `InternalFailure` with the reader online and the search still saying offline.
     */
    @Test
    fun aThrowingWindowListenerNeverAbortsTheReconnect() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        var throwing = false
        var thrown = 0
        session.reader.open(grid) {
            if (throwing) {
                thrown += 1
                error("listener failed")
            }
        }
        val search = offlineSearch(session, env)
        throwing = true
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        assertTrue(thrown > 0, "fixture: the listener threw during the reconnect")
        assertIs<ReaderConnectionOutcome.Read>(outcome, "a throwing listener aborted the reconnect")
        assertTrue(session.reader.online)
        assertEquals(SearchScope.ServerAndDevice, search.last.scope, "the open search was never revalidated")
    }

    /**
     * A step that throws after the transition (here the downloaded-album recheck, §16.14 step 4)
     * leaves the reader offline again — every open search back on the device — and the next
     * reachability report runs the whole sequence again. It used to leave the reader online, so
     * that report did nothing.
     */
    @Test
    fun anInternalFailureAfterTheTransitionLeavesTheReaderOfflineAndTheNextReportRunsItAgain() = sessionTest { env ->
        var failing = false
        var hookCalls = 0
        val session = primed(
            env,
            LibraryReaderSession(
                env.database.database, env.cache(), env.server, env.scope,
                LibraryReaderConfig(lookAheadMaxPerViewport = 0),
                downloads = DownloadedTrackSource {
                    hookCalls += 1
                    if (failing) error("hook failed") else emptySet()
                },
            ),
        )
        session.setOnline(false)
        val search = offlineSearch(session, env)
        env.server.base.lastScan = "2026-09-24T10:00:00Z" // a changed epoch, so the recheck runs
        failing = true
        val calls = hookCalls
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        assertTrue(hookCalls > calls, "fixture: the recheck ran and threw")
        assertEquals(ReaderConnectionOutcome.InternalFailure, outcome)
        assertFalse(session.reader.online, "an InternalFailure after the transition left the reader online")
        assertIs<SearchScope.DeviceOffline>(search.last.scope, "the search says it is connected to a reader that is not")

        failing = false
        val scans = env.server.count("getScanStatus")
        session.setOnline(true)
        advanceUntilIdle()
        assertTrue(env.server.count("getScanStatus") > scans, "the next report did not run the sequence again")
        assertTrue(session.reader.online)
        assertEquals(SearchScope.ServerAndDevice, search.last.scope)
    }

    /**
     * A window whose own reconnect check cannot be answered (its stored state cannot be read) says
     * so on its own screen, and the reconnect runs on for everything else: the reader is online
     * and the open search is revalidated.
     */
    @Test
    fun aWindowWhoseReconnectCheckThrowsDoesNotStopTheReconnect() = sessionTest { env ->
        val session = primed(env)
        val window = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, window)
        advanceUntilIdle()
        session.setOnline(false)
        val search = offlineSearch(session, env)
        env.driver.failRead = { sql -> sql.startsWith("SELECT") && sql.contains("FROM cache_list WHERE server_id = ? AND list_key = ?") }
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        env.driver.failRead = null
        assertTrue(env.driver.failedReads > 0, "fixture: the window's stored state could not be read")
        assertIs<ReaderConnectionOutcome.Read>(outcome, "one window's failing check stopped the reconnect")
        assertTrue(session.reader.online)
        assertEquals(SearchScope.ServerAndDevice, search.last.scope)
    }

    // ---- One sequence at a time -----------------------------------------------------------------------------

    /**
     * A reachability report and a foreground reconnect arriving together, plus a second reconnect,
     * run the step sequence ONCE: one epoch reading, one re-read of the window.
     */
    @Test
    fun aReportAndTwoReconnectsTogetherRunTheStepsOnce() = sessionTest { env ->
        val session = primed(env)
        session.reader.open(grid) {}
        advanceUntilIdle()
        session.setOnline(false)
        env.server.base.lastScan = "2026-09-24T11:00:00Z" // a changed epoch: the window re-reads
        val mark = env.server.log.size
        session.setOnline(true)
        val outcomes = mutableListOf<ReaderConnectionOutcome>()
        env.scope.launch { outcomes += session.reader.reconnect() }
        env.scope.launch { outcomes += session.reader.reconnect() }
        advanceUntilIdle()
        val sent = env.server.endpoints().drop(mark)
        assertEquals(1, sent.count { it == "getMusicFolders" }, "the step sequence ran more than once: $sent")
        assertEquals(1, sent.count { it == "getAlbumList2" }, "the window was re-read more than once: $sent")
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it is ReaderConnectionOutcome.Read })
        assertEquals(1, outcomes.distinct().size, "the joined reconnects saw different readings")
    }

    /** "Reachable, while online: no effect" — no request and no publication. */
    @Test
    fun aReachableReportWhileOnlineDoesNothing() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        val mark = env.server.log.size
        val published = pubs.all.size
        session.setOnline(true)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), env.server.endpoints().drop(mark))
        assertEquals(published, pubs.all.size)
    }

    // ---- Never a spinner with nothing coming ------------------------------------------------------------------

    /**
     * A foreground reconnect of a reader that is already online, with nothing changed: a fresh
     * window is not re-read and is not republished at all — no `revalidating` with nothing coming,
     * and no transition relabelling, because there was no transition.
     */
    @Test
    fun aForegroundReconnectOfAnOnlineReaderRepublishesNoFreshWindow() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness, "fixture")
        val mark = env.server.log.size
        val published = pubs.all.size
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        assertIs<ReaderConnectionOutcome.Read>(outcome)
        assertEquals(listOf("getScanStatus", "getMusicFolders"), env.server.endpoints().drop(mark))
        assertEquals(emptyList<String>(), pubs.all.drop(published).map { it.value.freshness.label() }, "a fresh window was republished")
    }

    /**
     * Back online after less than the re-read interval, under the same epoch: a fresh window says
     * `live` at the transition and nothing after it — never `revalidating` when no list is read.
     */
    @Test
    fun aFreshWindowAtTheTransitionNeverSaysRevalidating() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.setOnline(false)
        advanceUntilIdle()
        val mark = env.server.log.size
        val from = pubs.all.size
        session.setOnline(true)
        advanceUntilIdle()
        assertEquals(listOf("getScanStatus", "getMusicFolders"), env.server.endpoints().drop(mark), "fixture: no list is read")
        assertEquals(listOf("Live"), pubs.all.drop(from).map { it.value.freshness.label() })
    }

    /**
     * A whole (unpaged) list is re-read by every reconnect, however fresh: at the transition it says
     * `revalidating` — never `live` — until that read lands.
     */
    @Test
    fun aWholeListAtTheTransitionSaysRevalidatingUntilItsReadLands() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(LibraryQuery.Artists(), pubs)
        advanceUntilIdle()
        assertEquals(LibraryFreshness.Live, pubs.last.freshness, "fixture: the list is fresh")
        session.setOnline(false)
        advanceUntilIdle()
        val mark = env.server.log.size
        val from = pubs.all.size
        session.setOnline(true)
        advanceUntilIdle()
        val read = env.server.log.withIndex().drop(mark).single { it.value.endpoint == "getArtists" }.index
        val beforeTheRead = pubs.all.drop(from).filter { it.requestsIssued <= read }.map { it.value.freshness.label() }
        assertTrue(beforeTheRead.isNotEmpty(), "fixture: the transition republished the list")
        assertTrue(beforeTheRead.all { it == "cached(Revalidating)" }, "the list said something else while its read was coming: $beforeTheRead")
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    // ---- The visible screen includes open searches -------------------------------------------------------

    /**
     * An epoch change seen by the foreground cadence re-reads open searches as well as windows
     * (§16.15: a search is part of the visible screen) — quietly, with no label flip.
     */
    @Test
    fun anEpochChangeSeenByTheCadenceReReadsAnOpenSearch() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        session.openSearch(LibrarySearchConfig(debounceMillis = 0), pubs).updateQuery("Album 002")
        advanceUntilIdle()
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope, "fixture")
        val mark = env.server.log.size
        val from = pubs.all.size
        env.server.base.lastScan = "2026-09-24T12:00:00Z"
        session.reader.refreshEpoch()
        advanceUntilIdle()
        assertEquals(1, env.server.endpoints().drop(mark).count { it == "search3" }, "the open search was not re-read")
        assertTrue(pubs.all.drop(from).all { it.value.scope == SearchScope.ServerAndDevice }, "the label flipped during the re-read")
    }

    /**
     * A search still waiting for its server answer is left to it by a reconnect: no re-run, no
     * publication, and its request is not cancelled.
     */
    @Test
    fun aSearchWaitingForTheServerIsLeftToItByAReconnect() = sessionTest { env ->
        val session = primed(env)
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(LibrarySearchConfig(debounceMillis = 0), pubs)
        env.server.holdBeforeApply += "search3"
        search.updateQuery("Album 002")
        advanceUntilIdle()
        assertEquals(1, env.server.heldCount, "fixture: the server answer is pending")
        assertEquals(SearchScope.DeviceWhileServerPending, pubs.last.scope, "fixture")
        val published = pubs.all.size
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(published, pubs.all.size, "a search waiting for the server was re-run")
        assertEquals(0, env.server.cancelledWhileHeld, "the pending request was cancelled")
        env.server.holdBeforeApply.clear()
        env.server.release()
        advanceUntilIdle()
        assertEquals(1, env.server.count("search3"))
        assertEquals(SearchScope.ServerAndDevice, pubs.last.scope)
    }

    /**
     * A quiet re-read keeps the rows shown in their places. Here the rows shown are the device's
     * first and the server's after them; by the re-read the device knows them all, and ranking
     * them afresh would reorder the screen under the person's finger.
     */
    @Test
    fun aQuietReReadKeepsTheRowsInPlace() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibrarySearchPublication>(env.server)
        val search = session.openSearch(LibrarySearchConfig(debounceMillis = 0), pubs)
        env.server.searchExcludes += (10..14).map(::albumId)
        search.updateQuery("Album 001") // the device learns albums 15..19 only
        advanceUntilIdle()
        env.server.searchExcludes.clear()
        search.refresh() // the device's 15..19 first, then the server's 10..14
        advanceUntilIdle()
        val shown = pubs.last.rows.map { it.item.id.rawId }
        assertEquals((15..19).map(::albumId).toSet(), shown.take(5).toSet(), "fixture: the device's rows lead: $shown")
        assertEquals((10..19).map(::albumId).toSet(), shown.toSet(), "fixture: $shown")
        env.clock.now += LibraryReaderConfig().revalidateWithinMillis + 1
        val reads = env.server.count("search3")
        session.reader.reconnect()
        advanceUntilIdle()
        assertEquals(reads + 1, env.server.count("search3"), "fixture: the older answer was re-read")
        assertEquals(shown, pubs.last.rows.map { it.item.id.rawId }, "a quiet re-read moved the rows shown")
    }

    // ---- The platform's reachability is the platform's -------------------------------------------------------

    /**
     * A reconnect that fails while the platform last reported the server unreachable leaves that
     * report standing: a tap afterwards is kept, not sent.
     */
    @Test
    fun aFailedReconnectNeverOverridesAnUnreachableReport() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        env.server.failWithError["getScanStatus"] = DomainError.Transport.Unreachable
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        env.server.failWithError.clear()
        assertEquals(ReaderConnectionOutcome.Failed(DomainError.Transport.Unreachable), outcome, "fixture")
        assertFalse(session.reader.reachable, "a failed reconnect overwrote the platform's report")
        val stars = env.server.count("star")
        session.favourites.setFavourite(album3, true)
        advanceUntilIdle()
        assertEquals(stars, env.server.count("star"), "a tap was sent while the platform said unreachable")
        assertEquals(1L, session.favourites.pendingCount())
    }

    /**
     * A reconnect alone that succeeds brings the reader back whatever the platform last reported:
     * the reader is connected, so a tap is sent — the platform's report is not rewritten to get there.
     */
    @Test
    fun aReconnectAloneThatSucceedsLetsATapBeSent() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        val outcome = session.reader.reconnect()
        advanceUntilIdle()
        assertIs<ReaderConnectionOutcome.Read>(outcome, "fixture")
        assertFalse(session.reader.reachable, "the reconnect overwrote the platform's report")
        val stars = env.server.count("star")
        session.favourites.setFavourite(album3, true)
        advanceUntilIdle()
        assertEquals(stars + 1, env.server.count("star"), "a connected reader kept a tap")
        assertEquals(0L, session.favourites.pendingCount())
    }

    /** While the reader is offline, connect issues no request; the epoch is read at the reconnect. */
    @Test
    fun connectWhileOfflineIssuesNoRequest() = sessionTest { env ->
        val session = primed(env)
        session.setOnline(false)
        val mark = env.server.log.size
        val epoch = session.reader.connect()
        val outcome = session.reader.connectReporting()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), env.server.endpoints().drop(mark), "connect read the server while offline")
        assertNull(epoch)
        assertEquals(ReaderConnectionOutcome.Failed(DomainError.Transport.Unreachable), outcome)
        assertFalse(session.reader.online)

        session.setOnline(true)
        advanceUntilIdle()
        assertTrue(session.reader.online)
        assertEquals(listOf("getScanStatus", "getMusicFolders"), env.server.endpoints().drop(mark).take(2))
    }
}
