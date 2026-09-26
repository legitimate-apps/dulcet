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
            env.database.database, env.cache(), env.server, env.scope,
            LibraryReaderConfig(lookAheadMaxPerViewport = 0),
            downloads = DownloadedTrackSource { setOf("album-0003-track-0") },
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

    /** A busy server's `Retry-After` is the floor of the wait (CLAUDE.md trap 24). */
    @Test
    fun aBusyServersRetryAfterIsTheFloorOfTheWait() = sessionTest { env ->
        val session = env.session()
        session.reader.connect()
        session.reader.setForeground(true)
        session.setOnline(false)
        runCurrent()
        val readings = readings(env)
        readings.failure = DomainError.Server.Busy(10.seconds)
        session.setOnline(true)
        advanceTimeBy(20_001)
        runCurrent()
        assertEquals(listOf(10_000L, 10_000L), readings.times.zipWithNext { a, b -> b - a })
        session.setOnline(false)
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
}
