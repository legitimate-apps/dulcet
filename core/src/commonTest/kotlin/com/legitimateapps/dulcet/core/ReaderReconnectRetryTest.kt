package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What brings an offline reader back when the platform reports the server reachable and sends no
 * further report (§16.14). A TRANSIENT failure of the reconnect's epoch read is retried by itself,
 * on a capped backoff, only while the app is in the foreground. Any other failure is never retried
 * on a timer, and every screen says what it is. A screen says `live` only once a reconnect has run
 * every step. Virtual time and request counts are asserted, never wall time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderReconnectRetryTest {
    private val grid = LibraryQuery.AlbumList(AlbumListType.AlphabeticalByName)
    private val storesTheEpoch: (String) -> Boolean = { it.contains("INTO cache_epoch") }

    /**
     * The downloaded-album recheck's lookup of a downloaded track (SQLDelight expands its `*` into
     * the column list): failing it fails only the recheck.
     */
    private val recheckLookup: (String) -> Boolean = {
        it.startsWith("SELECT cache_track.server_id,") && it.endsWith("FROM cache_track WHERE server_id = ? AND raw_id = ?")
    }

    private fun LibraryFreshness.label(): String = when (this) {
        is LibraryFreshness.Cached -> when (val reason = reason) {
            is LibraryCachedReason.Failed -> "cached(Failed(" + reason.error::class.simpleName + "))"
            else -> "cached(" + reason::class.simpleName + ")"
        }
        else -> this::class.simpleName.orEmpty()
    }

    /** Every `getScanStatus` the server answers, by virtual time; each fails while [failure] is set. */
    private class Readings {
        val times = mutableListOf<Long>()
        var failure: DomainError? = null
    }

    private fun TestScope.readings(env: SessionEnv): Readings {
        val readings = Readings()
        env.server.base.beforeRespond = {
            if (it.endpoint == "getScanStatus") {
                readings.times += currentTime
                readings.failure?.let { error -> throw LibraryRequestFailure(error) }
            }
        }
        return readings
    }

    /** A session whose downloads hook always names album 3's first track, with that track cached. */
    private suspend fun TestScope.withDownloadedAlbum3(env: SessionEnv): LibraryReaderSession {
        val session = LibraryReaderSession(
            env.database.database, env.cache(), env.server, env.scope, LibraryReaderConfig(lookAheadMaxPerViewport = 0),
            downloads = DownloadedTrackSource { setOf("album-0003-track-0") },
            formPost = false,
            foreground = false,
        )
        session.reader.connect()
        session.reader.open(LibraryQuery.Album(albumId(3))) {}.also { advanceUntilIdle() }.close()
        assertTrue(env.cache().track("album-0003-track-0") != null, "fixture: the downloaded track is cached")
        return session
    }

    // ---- A transient failure is retried, in the foreground only ----------------------------------------

    /**
     * In the foreground, offline, the platform reports the server reachable and the reconnect's
     * epoch read times out. No further report comes — a stable network sends none — yet the reader
     * tries again after [LibraryReaderConfig.reconnectRetryInitialMillis], and not before.
     */
    @Test
    fun aTransientReadFailureInTheForegroundIsRetriedWithoutAnotherReport() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.reader.open(grid) {}
        advanceUntilIdle()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        val readings = readings(env)
        readings.failure = DomainError.Transport.Timeout
        session.setOnline(true)
        runCurrent()
        assertEquals(1, readings.times.size, "fixture: the report's reconnect read once")
        assertFalse(session.reader.online, "fixture: its read timed out")
        readings.failure = null
        val wait = session.reader.config.reconnectRetryInitialMillis
        advanceTimeBy(wait - 1)
        runCurrent()
        assertEquals(1, readings.times.size, "retried before the backoff")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, readings.times.size, "no retry without a new report")
        assertTrue(session.reader.online)
        session.reader.setForeground(false)
    }

    /**
     * A transient failure that persists: the retries come after 2, 4, 8, 16, 32 and then 60 s (the
     * cap). The background stops them; a return to the foreground tries at once, the backoff reset;
     * an unreachable report stops them; a success resets the backoff.
     */
    @Test
    fun theRetryBacksOffToItsCapStopsInTheBackgroundOrOfflineAndResets() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        val readings = readings(env)
        readings.failure = DomainError.Transport.Timeout
        session.setOnline(true)
        advanceTimeBy(200_000)
        runCurrent()
        assertFalse(session.reader.online, "fixture: every attempt failed")
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), readings.times.zipWithNext { a, b -> b - a })

        session.reader.setForeground(false)
        val backgrounded = readings.times.size
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(backgrounded, readings.times.size, "retried in the background")

        readings.times.clear()
        val back = currentTime
        session.reader.setForeground(true)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf(back, back + 2_000L), readings.times, "a return to the foreground did not try at once with the backoff reset")

        session.setOnline(false)
        val unreachable = readings.times.size
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(unreachable, readings.times.size, "retried after an unreachable report")

        readings.failure = null
        session.setOnline(true)
        runCurrent()
        assertTrue(session.reader.online, "fixture: the reconnect succeeded")
        session.setOnline(false)
        runCurrent()
        readings.failure = DomainError.Transport.Timeout
        readings.times.clear()
        val start = currentTime
        session.setOnline(true)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf(start, start + 2_000L), readings.times, "the backoff was not reset by the success")
        session.setOnline(false)
        session.reader.setForeground(false)
    }

    // ---- What the server actually answers, through the checked request path (round-6 review, R6-1) ----

    /** The reference server's own busy answer (CLAUDE.md trap 24): HTTP 429, `Retry-After`, and a code-0 envelope. */
    private fun busy(retryAfter: String) = LibraryEndpointResponse(
        429,
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":0,"message":"busy"}}}""",
        "http://fixture.invalid/rest",
        retryAfter = retryAfter,
    )

    /** A status with a page and no envelope: the server or a reverse proxy in front of it answering for itself. */
    private fun page(status: Int) =
        LibraryEndpointResponse(status, "<html><body><h1>$status</h1></body></html>", "http://fixture.invalid/rest")

    /**
     * Every `getScanStatus` by virtual time, answered with [answer]'s response while it returns one:
     * the exact bytes a server or proxy sends, which the reader's checked request path classifies.
     */
    private fun TestScope.answering(env: SessionEnv, answer: () -> LibraryEndpointResponse?): MutableList<Long> {
        val times = mutableListOf<Long>()
        env.server.answerInstead = { endpoint ->
            if (endpoint == "getScanStatus") {
                times += currentTime
                answer()
            } else {
                null
            }
        }
        return times
    }

    /** Offline in the foreground with the grid open: the state every case below starts from. */
    private suspend fun TestScope.offlineInTheForeground(env: SessionEnv): Pair<LibraryReaderSession, Recorder<LibraryPublication>> {
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        return session to pubs
    }

    /**
     * The reference server's busy answer — HTTP 429 with only the generic code 0 in its envelope —
     * is `busy` from its STATUS, and its `Retry-After` is the floor of the wait (CLAUDE.md trap 24).
     * Until round 7 this test threw `Server.Busy` from the fixture, which no production path
     * produced: the real answer read as code 0, a failure no timer retries.
     */
    @Test
    fun aBusyServersRetryAfterIsTheFloorOfTheWait() = sessionTest { env ->
        val (session, pubs) = offlineInTheForeground(env)
        var answer: LibraryEndpointResponse? = busy("10")
        val times = answering(env) { answer }
        session.setOnline(true)
        runCurrent()
        assertEquals(1, times.size, "fixture: the report's reconnect read once")
        assertEquals("cached(Offline)", pubs.last.freshness.label(), "a busy server was shown as a failure the person must clear")
        advanceTimeBy(20_001)
        runCurrent()
        assertEquals(listOf(10_000L, 10_000L), times.zipWithNext { a, b -> b - a }, "the wait did not follow Retry-After")
        answer = null
        advanceTimeBy(20_001)
        runCurrent()
        assertTrue(session.reader.online, "the reader did not come back once the server had capacity")
        session.reader.setForeground(false)
    }

    /**
     * A `Retry-After` past the busy cap is read as the cap (§18.6), as an outbox reads it: a server
     * asking for a day does not leave the reader offline for one.
     */
    @Test
    fun aRetryAfterPastTheCapWaitsTheCap() = sessionTest { env ->
        val (session, _) = offlineInTheForeground(env)
        val times = answering(env) { busy("86400") }
        session.setOnline(true)
        runCurrent()
        assertEquals(1, times.size, "fixture: the report's reconnect read once")
        val cap = LIBRARY_BUSY_CAP.inWholeMilliseconds
        advanceTimeBy(cap - 1)
        runCurrent()
        assertEquals(1, times.size, "retried before the cap")
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, times.size, "a Retry-After past the cap was waited out in full")
        session.reader.setForeground(false)
    }

    /**
     * A reverse proxy's 503 page while the server restarts is transient: retried on the backoff,
     * never shown as a malformed envelope or "not a Subsonic server", and the reader is live once
     * the server is back — with no report, as on a stable network.
     */
    @Test
    fun aProxysGatewayPageDuringARestartIsRetriedAndTheReaderIsLiveAfterward() = sessionTest { env ->
        val (session, pubs) = offlineInTheForeground(env)
        var restarting = true
        val times = answering(env) { if (restarting) page(503) else null }
        session.setOnline(true)
        runCurrent()
        assertEquals(1, times.size, "fixture: the report's reconnect read once")
        assertFalse(session.reader.online, "fixture: the proxy answered")
        assertEquals("cached(Offline)", pubs.last.freshness.label(), "a restart behind a proxy was shown as a failure the person must clear")
        advanceTimeBy(2_001)
        runCurrent()
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(listOf(2_000L, 4_000L), times.zipWithNext { a, b -> b - a }, "not retried on the backoff")
        restarting = false
        advanceTimeBy(8_001)
        runCurrent()
        assertEquals(4, times.size, "fixture: the retry after the restart read")
        assertTrue(session.reader.online, "the reader stayed offline after the server came back")
        assertEquals(LibraryFreshness.Live, pubs.last.freshness, "the grid is not live after the server came back")
        session.reader.setForeground(false)
    }

    /**
     * 502 and 504 are the same gateway failure as 503, and each is retried. (The backoff carries over
     * from one to the next, so each is given the longest wait.)
     */
    @Test
    fun everyGatewayStatusIsRetried() = sessionTest { env ->
        val (session, _) = offlineInTheForeground(env)
        for (status in listOf(502, 504)) {
            session.setOnline(false)
            runCurrent()
            val times = answering(env) { page(status) }
            session.setOnline(true)
            runCurrent()
            advanceTimeBy(session.reader.config.reconnectRetryMaxMillis + 1)
            runCurrent()
            assertTrue(times.size > 1, "HTTP $status was not retried: ${times.size} read")
        }
        session.reader.setForeground(false)
    }

    /**
     * A status refusing access (401, 403, 407) or a request too large (413, 414) is not transient: one
     * read over ten foreground minutes, and every screen says what failed. 401 reads as refused
     * credentials, as playback names it.
     */
    @Test
    fun aRefusingStatusIsNeverRetriedOnATimer() = sessionTest { env ->
        val (session, pubs) = offlineInTheForeground(env)
        for ((status, shown) in listOf(
            401 to "cached(Failed(InvalidCredentials))",
            403 to "cached(Failed(HttpStatus))",
            407 to "cached(Failed(HttpStatus))",
            413 to "cached(Failed(HttpStatus))",
            414 to "cached(Failed(HttpStatus))",
        )) {
            session.setOnline(false)
            runCurrent()
            val times = answering(env) { page(status) }
            session.setOnline(true)
            repeat(20) { advanceTimeBy(30_000); runCurrent() }
            assertEquals(1, times.size, "HTTP $status was retried on a timer")
            assertEquals(shown, pubs.last.freshness.label(), "HTTP $status")
        }
        session.reader.setForeground(false)
    }

    /**
     * A code-0 envelope at HTTP 200 is the server's answer, not a capacity signal: never retried on a
     * timer. The busy test above is this test's positive control — the same instrument, observing a
     * retry.
     */
    @Test
    fun aCodeZeroEnvelopeAtHttp200IsNotRetried() = sessionTest { env ->
        val (session, pubs) = offlineInTheForeground(env)
        val times = answering(env) { busy("10").copy(statusCode = 200, retryAfter = null) }
        session.setOnline(true)
        repeat(20) { advanceTimeBy(30_000); runCurrent() }
        assertEquals(1, times.size, "a code-0 answer was retried on a timer")
        assertEquals("cached(Failed(Known))", pubs.last.freshness.label())
        session.reader.setForeground(false)
    }

    /**
     * The reviewer's probe, transient: the app is in the background when the report's reconnect
     * times out. Over ten virtual minutes nothing else is read — the report's attempt is the only
     * one — and a return to the foreground tries at once.
     */
    @Test
    fun nothingIsRetriedInTheBackground() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.reader.setForeground(true)
        session.reader.setForeground(false)
        session.setOnline(false)
        runCurrent()
        val readings = readings(env)
        readings.failure = DomainError.Transport.Timeout
        val lists = env.server.count("getAlbumList2")
        session.setOnline(true)
        repeat(20) {
            advanceTimeBy(30_000)
            env.clock.now += 30_000
            runCurrent()
        }
        assertEquals(1, readings.times.size, "read in the background")
        assertEquals(lists, env.server.count("getAlbumList2"), "a list was read in the background")
        assertEquals("cached(Offline)", pubs.last.freshness.label(), "a transient failure is shown as offline")
        readings.failure = null
        val reconnects = env.server.count("getMusicFolders")
        session.reader.setForeground(true)
        runCurrent()
        // Counted by `getMusicFolders`, which only an epoch read issues: the grid's page read that
        // follows takes its own `getScanStatus` after-reading (§16.12).
        assertEquals(1, env.server.count("getMusicFolders") - reconnects, "a return to the foreground did not try again")
        assertTrue(session.reader.online)
        session.reader.setForeground(false)
    }

    /**
     * A transient failure while the platform reports the server UNREACHABLE — the shell asked for a
     * reconnect anyway — is not retried: the next reachable report runs the reconnect.
     */
    @Test
    fun aTransientFailureWhileReportedUnreachableIsNotRetried() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        val readings = readings(env)
        readings.failure = DomainError.Transport.Timeout
        assertEquals(ReaderConnectionOutcome.Failed(DomainError.Transport.Timeout), session.reader.reconnect(), "fixture")
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, readings.times.size, "retried while the platform reports the server unreachable")
        session.reader.setForeground(false)
    }

    // ---- Any other failure waits, and says what it is ---------------------------------------------------

    /**
     * The server refuses the reconnect's epoch read with an authentication failure. It is never
     * retried on a timer — a timer would repeat a failing login — and every screen says so instead
     * of `offline`. A return to the foreground tries once more; an unreachable report makes the
     * screens say `offline` again.
     */
    @Test
    fun anAuthFailureIsNeverRetriedOnATimerAndEveryScreenSaysSo() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        val readings = readings(env)
        readings.failure = DomainError.Auth.InvalidCredentials
        session.setOnline(true)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, readings.times.size, "an authentication failure was retried on a timer")
        assertFalse(session.reader.online)
        assertEquals("cached(Failed(InvalidCredentials))", pubs.last.freshness.label())

        session.reader.setForeground(false)
        session.reader.setForeground(true)
        runCurrent()
        assertEquals(2, readings.times.size, "a return to the foreground did not try again")
        assertEquals("cached(Failed(InvalidCredentials))", pubs.last.freshness.label())

        session.setOnline(false)
        runCurrent()
        assertEquals("cached(Offline)", pubs.last.freshness.label(), "an unreachable report did not say offline again")
        session.reader.setForeground(false)
    }

    /**
     * The reader itself fails during the reconnect (the epoch cannot be stored). It is never retried
     * on a timer; every screen says `internalFailure`, and one that has nothing cached says so too.
     * A return to the foreground runs the whole reconnect again.
     */
    @Test
    fun anInternalFailureIsNeverRetriedOnATimerAndEveryScreenSaysSo() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        val readings = readings(env)
        env.driver.failWrite = storesTheEpoch
        session.setOnline(true)
        advanceTimeBy(600_000)
        runCurrent()
        env.driver.failWrite = null
        assertEquals(1, readings.times.size, "an internal failure was retried on a timer")
        assertFalse(session.reader.online)
        assertEquals("cached(InternalFailure)", pubs.last.freshness.label())
        val unseen = Recorder<LibraryPublication>(env.server)
        session.reader.open(LibraryQuery.Album("an-album-never-seen"), unseen) // no cached row, no local view
        assertEquals(LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure), unseen.last.freshness)

        session.reader.setForeground(false)
        session.reader.setForeground(true)
        runCurrent() // not advanceUntilIdle: the foreground cadence never idles
        assertTrue(session.reader.online, "a return to the foreground did not reconnect")
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
        session.reader.setForeground(false)
    }

    // ---- Live only once the whole sequence has run --------------------------------------------------------

    /**
     * The reviewer's persistent failure after the transition: the downloaded-album recheck fails at
     * every reconnect. Over ten virtual minutes in the foreground there is one reconnect (the
     * report's), and the grid never says `live`: it says `revalidating` while the reconnect runs and
     * then `internalFailure`. It used to flip `live`/`offline` and re-read every 60 s, forever.
     */
    @Test
    fun aFailureAfterTheTransitionIsNotRetriedAndNeverShowsAScreenLive() = sessionTest { env ->
        val session = withDownloadedAlbum3(env)
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent() // not advanceUntilIdle: the foreground cadence never idles
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        val readings = env.server.count("getMusicFolders")
        val lists = env.server.count("getAlbumList2")
        val mark = pubs.all.size
        env.driver.failRead = recheckLookup
        session.setOnline(true)
        repeat(20) {
            advanceTimeBy(30_000)
            env.clock.now += 30_000
            runCurrent()
        }
        env.driver.failRead = null
        assertTrue(env.driver.failedReads >= 1, "fixture: the recheck failed")
        assertFalse(session.reader.online)
        assertEquals(1, env.server.count("getMusicFolders") - readings, "reconnects over ten minutes")
        assertEquals(1, env.server.count("getAlbumList2") - lists, "list reads over ten minutes")
        val labels = pubs.all.drop(mark).map { it.value.freshness.label() }
        assertEquals("cached(InternalFailure)", labels.last(), "labels: $labels")
        assertEquals(listOf("cached(Revalidating)"), labels.dropLast(1).distinct(), "labels: $labels")
        session.reader.setForeground(false)
    }

    /**
     * While the recheck of a reconnect is still running, the grid it has already re-read says
     * `revalidating`, not `live`; it says `live` once the recheck is done.
     */
    @Test
    fun aScreenSaysLiveOnlyOnceTheReconnectHasRunEveryStep() = sessionTest { env ->
        val session = withDownloadedAlbum3(env)
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.setOnline(false)
        advanceUntilIdle()
        env.server.base.lastScan = "2026-09-24T10:00:00Z"
        env.server.base.holdMatching = { it.endpoint == "getAlbum" && it.parameters["id"] == albumId(3) }
        val lists = env.server.count("getAlbumList2")
        session.setOnline(true)
        advanceUntilIdle()
        assertEquals(1, env.server.base.heldCount, "fixture: the recheck is held")
        assertEquals(1, env.server.count("getAlbumList2") - lists, "fixture: the grid was re-read")
        assertEquals("cached(Revalidating)", pubs.last.freshness.label(), "live before the reconnect ran every step")
        env.server.base.holdMatching = null
        env.server.base.release()
        advanceUntilIdle()
        assertTrue(session.reader.online)
        assertEquals(LibraryFreshness.Live, pubs.last.freshness)
    }

    // ---- The review's K4 and C1 ---------------------------------------------------------------------------

    /**
     * K4. The platform says unreachable, yet a reconnect the shell asks for reads the epoch: the
     * reader is ONLINE while `reachable` is false. `connect` reads — it is gated on the reader being
     * connected, not on the platform's report.
     */
    @Test
    fun connectOnAnOnlineReaderReportedUnreachableReads() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.setOnline(false)
        runCurrent()
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect(), "fixture: the reconnect read the epoch")
        runCurrent()
        assertTrue(session.reader.online, "fixture: online")
        assertFalse(session.reader.reachable, "fixture: the platform's report is unchanged")
        val before = env.server.count("getScanStatus")
        assertIs<ReaderConnectionOutcome.Read>(session.reader.connectReporting(), "an online reader's connect refused to read")
        assertEquals(1, env.server.count("getScanStatus") - before)
    }

    /**
     * C1. Once the reader is closed nothing of it is left scheduled: with a retry pending, the
     * scheduler goes idle without advancing virtual time.
     */
    @Test
    fun noTimerOutlivesAClosedReader() = sessionTest { env ->
        val readerScope = CoroutineScope(env.scope.coroutineContext + Job(env.scope.coroutineContext[Job]))
        val session = LibraryReaderSession(
            env.database.database, env.cache(), env.server, readerScope, LibraryReaderConfig(lookAheadMaxPerViewport = 0),
            formPost = false,
            foreground = false,
        )
        session.reader.connect()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        val readings = readings(env)
        readings.failure = DomainError.Transport.Timeout
        session.setOnline(true)
        runCurrent()
        assertEquals(1, readings.times.size, "fixture: the reconnect's read timed out, and a retry is pending")
        readerScope.cancel()
        val closedAt = currentTime
        advanceUntilIdle()
        assertEquals(closedAt, currentTime, "a timer outlived the closed reader")
        assertEquals(1, readings.times.size, "a closed reader retried")
    }

    // ---- The foreground at construction (round-6 review) ----------------------------------------------

    /**
     * The app's foreground state at launch is a required constructor argument. A session constructed
     * in the foreground retries a transient reconnect failure by itself, with no `setForeground`
     * call. It used to start out in the background whatever the app was doing, so a shell that
     * reported only CHANGES never retried until the app went to the background and came back.
     */
    @Test
    fun aSessionConstructedInTheForegroundRetriesWithNoForegroundReport() = sessionTest { env ->
        val session = env.session(foreground = true)
        assertTrue(session.reader.foreground, "the construction's foreground state was not taken")
        session.reader.connect()
        session.reader.open(grid) {}
        runCurrent()
        session.setOnline(false)
        runCurrent()
        val readings = readings(env)
        readings.failure = DomainError.Transport.Timeout
        session.setOnline(true)
        runCurrent()
        assertEquals(1, readings.times.size, "fixture: the report's reconnect read once")
        readings.failure = null
        advanceTimeBy(session.reader.config.reconnectRetryInitialMillis + 1)
        runCurrent()
        assertEquals(2, readings.times.size, "a session launched in the foreground did not retry")
        assertTrue(session.reader.online)
        session.reader.setForeground(false)
    }

    // ---- The four retry survivors of round 5, each observable (the round-6 review's probes) -----------

    /** A reader scope of its own, so a test can count what is still scheduled in it. */
    private fun TestScope.scoped(env: SessionEnv): Pair<LibraryReaderSession, Job> {
        val job = Job(env.scope.coroutineContext[Job])
        val scope = CoroutineScope(env.scope.coroutineContext + job)
        return LibraryReaderSession(
            env.database.database, env.cache(), env.server, scope, LibraryReaderConfig(lookAheadMaxPerViewport = 0),
            formPost = false,
            foreground = false,
        ) to job
    }

    /** Active coroutines under [this], counted recursively: the reader's scope is a child of the one given. */
    private fun Job.active(): Int = children.sumOf { (if (it.isActive) 1 else 0) + it.active() }

    private fun failScans(env: SessionEnv, error: () -> DomainError?) {
        env.server.base.beforeRespond = { if (it.endpoint == "getScanStatus") error()?.let { e -> throw LibraryRequestFailure(e) } }
    }

    /** R53a1. A transient failure in the background schedules nothing: no timer is left pending. */
    @Test
    fun aTransientFailureInTheBackgroundLeavesNoTimer() = sessionTest { env ->
        val (session, _) = scoped(env)
        session.reader.connect()
        session.reader.setForeground(true)
        session.reader.setForeground(false)
        session.setOnline(false)
        runCurrent()
        failScans(env) { DomainError.Transport.Timeout }
        session.setOnline(true)
        runCurrent()
        assertFalse(session.reader.online, "fixture: the reconnect failed")
        val at = currentTime
        advanceUntilIdle()
        assertEquals(at, currentTime, "a timer was left pending in the background")
    }

    /** R53b1. A move to the background drops a pending retry: nothing is left scheduled. */
    @Test
    fun aMoveToTheBackgroundDropsAPendingRetry() = sessionTest { env ->
        val (session, _) = scoped(env)
        session.reader.connect()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        failScans(env) { DomainError.Transport.Timeout }
        session.setOnline(true)
        runCurrent()
        session.reader.setForeground(false)
        val at = currentTime
        advanceUntilIdle()
        assertEquals(at, currentTime, "a retry outlived the move to the background")
    }

    /** N7f. An unreachable report drops a pending retry: one coroutine fewer, against a control. */
    @Test
    fun anUnreachableReportDropsAPendingRetry() = sessionTest { env ->
        val (session, job) = scoped(env)
        session.reader.connect()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        val control = job.active()
        failScans(env) { DomainError.Transport.Timeout }
        session.setOnline(true)
        runCurrent()
        assertEquals(control + 1, job.active(), "fixture: a retry is pending")
        session.setOnline(false)
        runCurrent()
        assertEquals(control, job.active(), "a retry outlived the unreachable report")
        session.reader.setForeground(false)
    }

    /**
     * N7g. A reconnect the shell asks for while the platform reports the server unreachable times
     * out: it schedules nothing, and does not grow the backoff — the first retry after the next
     * reachable report still waits the initial 2 s.
     */
    @Test
    fun aFailureWhileReportedUnreachableNeitherSchedulesNorGrowsTheBackoff() = sessionTest { env ->
        val (session, job) = scoped(env)
        session.reader.connect()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        val control = job.active()
        val times = mutableListOf<Long>()
        env.server.base.beforeRespond = {
            if (it.endpoint == "getScanStatus") {
                times += currentTime
                throw LibraryRequestFailure(DomainError.Transport.Timeout)
            }
        }
        session.reader.reconnect()
        runCurrent()
        assertEquals(1, times.size, "fixture: the asked-for reconnect read the epoch")
        assertEquals(control, job.active(), "a retry was scheduled while reported unreachable")
        session.setOnline(true)
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        val gaps = times.zipWithNext { a, b -> b - a }
        assertEquals(session.reader.config.reconnectRetryInitialMillis, gaps[1], "the first retry after the report: $gaps")
        session.reader.setForeground(false)
    }

    /**
     * Five transient failures in the foreground, then success: the grid says `live` once, last, and
     * never `offline` in between — no flicker over the whole chain. This pins behaviour that was
     * already right.
     */
    @Test
    fun aChainOfTransientFailuresNeverFlickers() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        val pubs = Recorder<LibraryPublication>(env.server)
        session.reader.open(grid, pubs)
        advanceUntilIdle()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        var failures = 5
        env.server.base.beforeRespond = {
            if (it.endpoint == "getScanStatus" && failures > 0) {
                failures -= 1
                throw LibraryRequestFailure(DomainError.Transport.Timeout)
            }
        }
        val from = pubs.all.size
        session.setOnline(true)
        repeat(40) {
            advanceTimeBy(5_000)
            env.clock.now += 5_000
            runCurrent()
        }
        assertEquals(0, failures, "fixture: every failure was spent")
        assertTrue(session.reader.online)
        val labels = pubs.all.drop(from).map { it.value.freshness.label() }
        assertEquals(1, labels.count { it == "Live" }, "labels: $labels")
        assertEquals("Live", labels.last(), "labels: $labels")
        assertFalse(labels.any { it == "cached(Offline)" }, "labels: $labels")
        session.reader.setForeground(false)
    }
}
