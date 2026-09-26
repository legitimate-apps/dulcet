package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The third independent review of `feat/playlists-core` (spec §18.6, §28 revision 104 item 21): the
 * reviewer's failing probes q1–q10 and fav403 as permanent tests (fav403 lives with the favourites,
 * in [MutationOutboxReviewTest]), under the maintainer's round-3 decisions — a lost create identified
 * by the playlists listed before its send and never sent again on a guess; a refusal of access held
 * only when a ping is refused too; a 429 waited out with a doubling floor and a cap; an append in
 * doubt that never arrived kept an append; and every pending change withdrawable. Each probe here
 * failed on the reviewed commit, except q7, whose adoption decision 1 makes the stated residual and
 * which is pinned as that.
 */
class PlaylistEditingThirdReviewTest {
    // ---- X1: a per-request refusal fails that change alone; the account's holds every change ------

    @Test
    fun q1_aPerRequest403FailsThatChangeAloneAndLaterChangesAreSent() = playlistTest { env ->
        // Reviewer probe q1: a proxy rule refuses deletePlaylist; the ping is answered.
        val a = env.server.add("A", listOf("song-1"))
        val b = env.server.add("B", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlists)
        env.open(session, LibraryQuery.Playlist(b.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.delete(a.id)
        session.playlists.rename(b.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["deletePlaylist"] = 403
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals("Evening", b.name, "the rename is sent past the refused delete")
        repeat(PlaylistEditor.MAX_FAILURES - 1) {
            session.playlists.flush()
            advanceUntilIdle()
        }
        assertEquals(PlaylistEditor.MAX_FAILURES, env.server.count("deletePlaylist"))
        assertEquals(PlaylistEditor.MAX_FAILURES, env.server.count("ping"), "one ping per flush that met the refusal")
        assertEquals(0L, session.playlists.pendingCount())
        assertEquals(PlaylistEditOutcome.NotSaved(a.id, PlaylistRowKind.Delete, DomainError.Server.HttpStatus(403)), env.outcomes.last())
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.Held }, "${env.outcomes}")
        assertEquals(2, env.server.playlists.size, "nothing deleted")
    }

    private suspend fun TestScope.renameRefusedAlone(env: PlaylistEnv, status: Int, error: DomainError) {
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = status
        repeat(PlaylistEditor.MAX_FAILURES) {
            assertEquals(null, session.playlists.flush().stoppedBy, "a refusal of one request stops nothing")
        }
        assertEquals(0L, session.playlists.pendingCount())
        assertEquals(PlaylistEditOutcome.NotSaved(p.id, PlaylistRowKind.Details, error), env.outcomes.last())
    }

    @Test
    fun aPerRequest401FailsThatChangeAlone() = playlistTest { env ->
        renameRefusedAlone(env, 401, DomainError.Auth.InvalidCredentials)
    }

    @Test
    fun aPerRequest407FailsThatChangeAlone() = playlistTest { env ->
        renameRefusedAlone(env, 407, DomainError.Server.HttpStatus(407))
    }

    @Test
    fun aPingAnsweredOnceServesEveryRefusalOfThatFlush() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val q = env.server.add("Other", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        env.open(session, LibraryQuery.Playlist(q.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.playlists.rename(q.id, "Morning")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 403
        val report = session.playlists.flush()
        assertEquals(2, env.server.count("updatePlaylist"))
        assertEquals(1, env.server.count("ping"), "one ping for the flush, not one per refusal")
        assertEquals(2, report.deferred)
    }

    @Test
    fun aPingWithNoAnswerStopsTheFlushSilently() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 403
        env.server.failWithError["ping"] = DomainError.Transport.Timeout
        val report = session.playlists.flush()
        assertEquals(DomainError.Transport.Timeout, report.stoppedBy)
        assertTrue(env.outcomes.isEmpty(), "nothing to tell while the server cannot be asked: ${env.outcomes}")
        assertEquals(1L, session.playlists.pendingCount())
    }

    @Test
    fun aPingMet429HoldsAndWaits() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session(config = LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource))
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 403
        env.server.httpStatus["ping"] = 429
        env.server.retryAfter = "10"
        val report = session.playlists.flush()
        assertIs<DomainError.Server.Busy>(report.stoppedBy)
        assertIs<PlaylistEditOutcome.Held>(env.outcomes.last())
        env.server.httpStatus.clear()
        advanceTimeBy(9_999); runCurrent()
        assertEquals(1, env.server.count("updatePlaylist"), "nothing inside the wait")
        advanceTimeBy(1); runCurrent()
        assertEquals("Evening", p.name)
    }

    // ---- X1, the other half: whatever holds a change, the person can withdraw it --------------------

    @Test
    fun aHeldChangeCanBeWithdrawnAndIsNeverSent() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val q = env.server.add("Other", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        env.open(session, LibraryQuery.Playlist(q.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.playlists.rename(q.id, "Morning")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 403
        env.server.httpStatus["ping"] = 403
        session.playlists.flush()
        assertIs<PlaylistEditOutcome.Held>(env.outcomes.last())
        assertEquals(
            listOf(PendingPlaylistChange(p.id, PlaylistRowKind.Details, inDoubt = false, failures = 0, candidates = null), PendingPlaylistChange(q.id, PlaylistRowKind.Details, false, 0, null)),
            session.playlists.pendingChanges(),
        )
        assertEquals(PlaylistEditRecord.CompactedAway, session.playlists.withdraw(p.id, PlaylistRowKind.Details))
        assertEquals(PlaylistEditRecord.Unchanged, session.playlists.withdraw(p.id, PlaylistRowKind.Details), "nothing left to withdraw")
        assertEquals(listOf(q.id), session.playlists.pendingChanges().map { it.playlistId })
        env.server.httpStatus.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals("Mix", p.name, "the withdrawn rename was never sent")
        assertEquals("Morning", q.name)
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aCreateNeverSentIsWithdrawnWithoutARequest() = playlistTest { env ->
        val session = env.session()
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        assertEquals(PlaylistEditRecord.CompactedAway, session.playlists.withdraw(localId, PlaylistRowKind.Create))
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        // The reachable report's reconnect reads the epoch (§16.14 step 2); nothing else is sent.
        assertEquals(listOf("getScanStatus", "getMusicFolders"), env.server.log.map { it.endpoint }, "${env.server.log}")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aCreateInDoubtWithdrawnNamesWhatItMayHaveMadeAndDeletesNothing() = playlistTest { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        assertTrue(session.playlists.pendingChanges().single().inDoubt)
        assertEquals(PlaylistEditRecord.AlreadySent, session.playlists.withdraw(localId, PlaylistRowKind.Create), "too late to undo")
        advanceUntilIdle()
        val made = env.server.playlists.single()
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "Road", listOf(made.id)), env.outcomes.last())
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(0L, session.playlists.pendingCount())
    }

    // ---- X2: a 429 waits max(Retry-After, a doubling floor), capped; told once; never sent early ----

    private suspend fun TestScope.renameMet429(env: PlaylistEnv, retryAfter: String?): Pair<FakePlaylistServer.Playlist, LibraryReaderSession> {
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session(config = LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource))
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 429
        env.server.retryAfter = retryAfter
        return p to session
    }

    @Test
    fun q2_aRetryAfterOfZeroDoesNotLoop() = playlistTest { env ->
        // Reviewer probe q2: 199 requests in no virtual time on the reviewed commit.
        val (_, session) = renameMet429(env, "0")
        session.playlists.flush()
        runCurrent(); runCurrent(); runCurrent()
        advanceTimeBy(10); runCurrent()
        assertEquals(1, env.server.count("updatePlaylist"))
        assertEquals(1, env.outcomes.count { it is PlaylistEditOutcome.Held })
    }

    @Test
    fun q2b_aPersistent429WithRetryAfterOneSecondForAMinute() = playlistTest { env ->
        // Reviewer probe q2b: 61 requests and 61 notices on the reviewed commit. Floors of 2, 4, 8,
        // 16 and 32 seconds put the retries at 2, 6, 14 and 30 seconds; the next is at 62.
        val (_, session) = renameMet429(env, "1")
        session.playlists.flush()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(5, env.server.count("updatePlaylist"))
        assertEquals(1, env.outcomes.count { it is PlaylistEditOutcome.Held }, "told once per run of 429s")
        assertEquals(1L, session.playlists.pendingCount(), "counted toward nothing")
    }

    @Test
    fun theFloorDoublesPerRetry() = playlistTest { env ->
        val (_, session) = renameMet429(env, null)
        session.playlists.flush()
        val expected = listOf(2_000L to 2, 4_000L to 3, 8_000L to 4, 16_000L to 5)
        expected.forEach { (floor, count) ->
            advanceTimeBy(floor - 1); runCurrent()
            assertEquals(count - 1, env.server.count("updatePlaylist"), "not before the ${floor}ms floor")
            advanceTimeBy(1); runCurrent()
            assertEquals(count, env.server.count("updatePlaylist"), "at the ${floor}ms floor")
        }
    }

    @Test
    fun aLongerRetryAfterThanTheFloorIsHonoured() = playlistTest { env ->
        val (p, session) = renameMet429(env, "20")
        session.playlists.flush()
        env.server.httpStatus.clear()
        advanceTimeBy(19_999); runCurrent()
        assertEquals(1, env.server.count("updatePlaylist"))
        advanceTimeBy(1); runCurrent()
        assertEquals(2, env.server.count("updatePlaylist"))
        assertEquals("Evening", p.name)
    }

    @Test
    fun q3_anAbsurdRetryAfterIsCappedAtFiveMinutesNotReportedAsLocal() = playlistTest { env ->
        // Reviewer probes q3 and q3c (N2): Long.MAX_VALUE seconds made an invalid Busy, reported as the
        // device's own failure. Now read as the ceiling, and waited out no longer than the cap.
        val (p, session) = renameMet429(env, Long.MAX_VALUE.toString())
        val report = session.playlists.flush()
        assertFalse(report.interruptedLocally, "$report")
        assertEquals(86_400L, RETRY_AFTER_CEILING_SECONDS)
        assertEquals(DomainError.Server.Busy(1.days), report.stoppedBy)
        assertIs<PlaylistEditOutcome.Held>(env.outcomes.last())
        env.server.httpStatus.clear()
        advanceTimeBy(LIBRARY_BUSY_CAP.inWholeMilliseconds - 1); runCurrent()
        assertEquals(1, env.server.count("updatePlaylist"))
        advanceTimeBy(1); runCurrent()
        assertEquals(2, env.server.count("updatePlaylist"))
        assertEquals("Evening", p.name)
        assertEquals(5.minutes, LIBRARY_BUSY_CAP)
    }

    @Test
    fun theRetryAfterParserReadsAnAbsurdValueAsTheCeiling() {
        // N2 at its root, shared with playback and scrobbling: a Busy holds only a finite interval.
        assertEquals(5.seconds, parseRetryAfterSeconds("5"))
        assertEquals(1.days, parseRetryAfterSeconds("86400"))
        assertEquals(1.days, parseRetryAfterSeconds("86401"))
        assertEquals(1.days, parseRetryAfterSeconds(Long.MAX_VALUE.toString()))
        assertEquals(DomainError.Server.Busy(1.days), DomainError.Server.Busy(parseRetryAfterSeconds(Long.MAX_VALUE.toString())))
    }

    @Test
    fun anEditDuringTheWaitDoesNotSendEarly() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val other = env.server.add("Other", listOf("song-1"))
        val session = env.session(config = LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource))
        env.open(session, LibraryQuery.Playlist(p.id))
        env.open(session, LibraryQuery.Playlist(other.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = 429
        env.server.retryAfter = "30"
        session.playlists.flush()
        runCurrent() // the reachable report's reconnect: its flushes are stopped by the wait, and it reads on
        env.server.httpStatus.clear()
        val requests = env.server.log.size
        session.playlists.rename(other.id, "Morning")
        session.favourites.setFavourite(LibraryEntityRef(LibraryEntityKind.Track, "song-1"), true)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(requests, env.server.log.size, "no request inside the wait, whatever triggers a flush")
        assertEquals(1, env.outcomes.count { it is PlaylistEditOutcome.Held }, "a gated flush tells nothing new")
        advanceTimeBy(1); runCurrent()
        assertEquals("Evening", p.name)
        assertEquals("Morning", other.name)
        assertEquals(1, env.server.count("star"))
    }

    @Test
    fun aDeliveredChangeEndsTheRunOf429s() = playlistTest { env ->
        val (p, session) = renameMet429(env, null)
        session.playlists.flush()
        env.server.httpStatus.clear()
        advanceTimeBy(2_000); runCurrent()
        assertEquals("Evening", p.name)
        // A new 429 after a delivery starts afresh: the two-second floor, and told again.
        session.playlists.rename(p.id, "Night")
        env.server.httpStatus["updatePlaylist"] = 429
        runCurrent()
        assertEquals(2, env.outcomes.count { it is PlaylistEditOutcome.Held })
        env.server.httpStatus.clear()
        advanceTimeBy(1_999); runCurrent()
        assertEquals("Evening", p.name)
        advanceTimeBy(1); runCurrent()
        assertEquals("Night", p.name)
    }

    // ---- X3, X4, X5, X7: a lost create is found by what was listed before its send ----------------

    @Test
    fun q10_aSystematic504WithAnUnreadableCreatedMakesOnePlaylist() = playlistTest { env ->
        env.server.createdText = { "2026-09-24T10:00:00" }
        val session = env.session()
        env.server.applyThenStatus["createPlaylist"] = 504
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        repeat(6) { env.clock.now += 1_000; session.playlists.flush(); advanceUntilIdle() }
        assertEquals(1, env.server.playlists.size)
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(PlaylistEditOutcome.Created(localId, env.server.playlists.single().id), env.outcomes.last())
    }

    @Test
    fun q10b_aSystematic504WithNoOwnerStatedMakesOnePlaylist() = playlistTest { env ->
        env.server.omitOwner = true
        val session = env.session()
        env.server.applyThenStatus["createPlaylist"] = 504
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        repeat(6) { env.clock.now += 1_000; session.playlists.flush(); advanceUntilIdle() }
        assertEquals(1, env.server.playlists.size)
        assertEquals(PlaylistEditOutcome.Created(localId, env.server.playlists.single().id), env.outcomes.last())
    }

    @Test
    fun q4_anEmptyOkCreateBesideARecentNamesakeAdoptsItsOwn() = playlistTest { env ->
        env.server.createAnswersWithPlaylist = false
        val namesake = env.server.add("New Playlist", emptyList(), created = env.clock.now - 60_000)
        val session = env.session()
        val localId = assertNotNull(session.playlists.create("New Playlist").localId)
        advanceUntilIdle()
        repeat(3) { env.clock.now += 1_000; session.playlists.flush(); advanceUntilIdle() }
        assertEquals(2, env.server.playlists.size, "the namesake and the create's own")
        assertEquals(1, env.server.count("createPlaylist"))
        val made = env.server.playlists.single { it.id != namesake.id }
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.Created(localId, made.id)), env.outcomes)
    }

    @Test
    fun q4b_anEmptyOkCreateOnAnOwnerUnstatedServerMakesOnePlaylist() = playlistTest { env ->
        env.server.createAnswersWithPlaylist = false
        env.server.omitOwner = true
        val session = env.session()
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        repeat(3) { env.clock.now += 1_000; session.playlists.flush(); advanceUntilIdle() }
        assertEquals(1, env.server.playlists.size)
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.Created(localId, env.server.playlists.single().id)), env.outcomes)
    }

    @Test
    fun anEmptyOkCreateIsFoundInTheSameFlushNeverCountedAsAFailure() = playlistTest { env ->
        // A server that answers every create with an empty `ok` made the playlist; it is looked for
        // at once. Deferring the lookup to the next flush also ends in adoption, one flush late and
        // with a failure counted toward the drop on every create — so this asserts the first flush.
        env.server.createAnswersWithPlaylist = false
        val session = env.session()
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        val made = env.server.playlists.single()
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.Created(localId, made.id)), env.outcomes, "told in the create's own flush")
        assertEquals(0L, session.playlists.pendingCount())
        assertEquals(1, env.server.count("createPlaylist"))
    }

    @Test
    fun anEmptyOkThatMadeNothingIsThisChangesFailureNeverADuplicate() = playlistTest { env ->
        env.server.createAnswersWithPlaylist = false
        env.server.createIgnored = true
        val session = env.session()
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        repeat(PlaylistEditor.MAX_FAILURES - 1) { session.playlists.flush(); advanceUntilIdle() }
        assertTrue(env.server.playlists.isEmpty())
        assertEquals(PlaylistEditor.MAX_FAILURES, env.server.count("createPlaylist"))
        assertEquals(PlaylistEditOutcome.NotSaved(localId, PlaylistRowKind.Create, DomainError.Protocol.MalformedEnvelope), env.outcomes.last())
    }

    @Test
    fun anEmptyOkBesideAnotherNewNamesakeWaitsForThePerson() = playlistTest { env ->
        // Empty ok, and another client made the same name in the round trip: two playlists the send
        // may have made. Nothing is sent again.
        env.server.createAnswersWithPlaylist = false
        env.server.beforeWrite = { if (it.endpoint == "createPlaylist") env.server.add("Road", listOf("song-1")) }
        val session = env.session()
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.beforeWrite = {}
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(2, env.server.playlists.size)
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossibleDuplicate(localId, "Road", env.server.playlists.map { it.id })), env.outcomes)
    }

    @Test
    fun q8_aLaterSendCutShortIsFoundByTheNextFlush() = runTest {
        // Reviewer probe q8: the first send times out and never arrives; three days later the resend
        // lands and the app is killed before its answer. Round 2 bounded its window by the first send.
        val driver = createTestDriver()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val database = DulcetDatabaseStore.open(driver)
            val clock = ManualWallClock(now = 3_000_000)
            val server = FakePlaylistServer(now = { clock.now })
            var killAfterCreate = false
            val wrapper = object : LibraryEndpointTransport {
                override suspend fun request(endpoint: String, parameters: Map<String, String>) = server.request(endpoint, parameters)
                override suspend fun requestRepeated(endpoint: String, parameters: List<Pair<String, String>>, formPost: Boolean): LibraryEndpointResponse {
                    val answer = server.requestRepeated(endpoint, parameters, formPost)
                    if (endpoint == "createPlaylist" && killAfterCreate) {
                        killAfterCreate = false
                        throw CancellationException("app killed")
                    }
                    return answer
                }
            }
            val session = LibraryReaderSession(
                database.database, SeenCacheStore(database, clock).bind(PlaylistEnv.BINDING), wrapper, scope,
                LibraryReaderConfig(lookAheadMaxPerViewport = 0), formPost = true, foreground = false,
            )
            val outcomes = mutableListOf<PlaylistEditOutcome>()
            session.playlists.addOutcomeListener(outcomes::add)
            server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
            val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
            advanceUntilIdle()
            server.failWithError.clear()
            clock.now += 3 * 24 * 3_600_000L
            killAfterCreate = true
            try {
                session.playlists.flush()
            } catch (_: CancellationException) {
            }
            advanceUntilIdle()
            assertEquals(1, server.playlists.size, "fixture: the resend landed")
            clock.now += 1_000
            session.playlists.flush()
            advanceUntilIdle()
            assertEquals(1, server.playlists.size, "a silent duplicate: $outcomes")
            assertEquals(PlaylistEditOutcome.Created(localId, server.playlists.single().id), outcomes.last())
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    @Test
    fun q9_anUnreadableCreatedNeverOffersAnOldNamesakeForDeletion() = playlistTest { env ->
        env.server.createdText = { "2026-09-24 10:00:00" }
        val ancient = env.server.add("New Playlist", listOf("song-7"), created = env.clock.now - 3L * 365 * 24 * 3_600_000)
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("New Playlist").localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        session.playlists.delete(localId)
        session.playlists.flush()
        advanceUntilIdle()
        val made = env.server.playlists.single { it.id != ancient.id }
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "New Playlist", listOf(made.id)), env.outcomes.last())
    }

    @Test
    fun aNamesakeMadeWhileTheSendsProvablyFailedIsListedBeforeTheNextSendAndNeverAdopted() = playlistTest { env ->
        // A send that provably never arrived leaves nothing in doubt, and the list is taken again
        // before the next send: a playlist made elsewhere meanwhile is listed before it, never adopted.
        val session = env.session()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Unreachable
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.clock.now += 2 * 24 * 3_600_000L
        val elsewhere = env.server.add("Road", listOf("song-1"))
        env.server.failWithError.clear()
        session.playlists.flush()
        advanceUntilIdle()
        val created = assertIs<PlaylistEditOutcome.Created>(env.outcomes.last())
        assertEquals(localId, created.localId)
        assertTrue(created.playlistId != elsewhere.id, "adopted the playlist made elsewhere")
        assertEquals(2, env.server.playlists.size)
    }

    @Test
    fun q7_aNamesakeWithTheSameSongsMadeAfterASendInDoubtIsAdoptedTheStatedResidual() = playlistTest { env ->
        // Reviewer probe q7 (N1), pinned as §18.6 states it: a playlist of the same name and songs
        // made elsewhere after a send in doubt, before the next flush, cannot be told from the send's
        // own. Alone, it is adopted — the two merge. Its `created` stamp, however old it reads, is
        // never compared: round 3 made the identity clock-free, so this is the price, stated.
        val session = env.session()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.failWithError.clear()
        val elsewhere = env.server.add("Road", listOf("song-1"), created = env.clock.now - 2 * 24 * 3_600_000L)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(PlaylistEditOutcome.Created(localId, elsewhere.id), env.outcomes.last())
        assertEquals(1, env.server.count("createPlaylist"))
    }

    // ---- Decision 2: never sent again on a guess; the person chooses ------------------------------

    private suspend fun TestScope.ambiguousLostCreate(env: PlaylistEnv): Triple<LibraryReaderSession, String, Pair<String, String>> {
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val lost = env.server.playlists.single()
        val elsewhere = env.server.add("Once", listOf("song-1"))
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(PlaylistEditOutcome.PossibleDuplicate(localId, "Once", listOf(lost.id, elsewhere.id)), env.outcomes.last())
        return Triple(session, localId, lost.id to elsewhere.id)
    }

    @Test
    fun aCreateWaitingForAChoiceIsListedAndPassedOverByLaterFlushes() = playlistTest { env ->
        val (session, localId, ids) = ambiguousLostCreate(env)
        val other = env.server.add("Mix", listOf("song-1"))
        env.open(session, LibraryQuery.Playlist(other.id))
        advanceUntilIdle()
        session.playlists.rename(other.id, "Evening")
        advanceUntilIdle()
        repeat(2) { session.playlists.flush(); advanceUntilIdle() }
        assertEquals("Evening", other.name, "a later change is not held behind the choice")
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(1, env.outcomes.count { it is PlaylistEditOutcome.PossibleDuplicate }, "told once, not every flush")
        val waiting = session.playlists.pendingChanges().single()
        assertEquals(PendingPlaylistChange(localId, PlaylistRowKind.Create, inDoubt = true, failures = 0, candidates = listOf(ids.first, ids.second)), waiting)
    }

    @Test
    fun thePersonChoosesACandidateAndItIsAdopted() = playlistTest { env ->
        val (session, localId, ids) = ambiguousLostCreate(env)
        assertEquals(PlaylistEditRecord.Invalid, session.playlists.chooseCreated(localId, "pl-999"), "only a candidate can be chosen")
        assertEquals(PlaylistEditRecord.Pending, session.playlists.chooseCreated(localId, ids.second))
        advanceUntilIdle()
        assertEquals(PlaylistEditOutcome.Created(localId, ids.second), env.outcomes.last())
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(2, env.server.playlists.size, "the other candidate is left as it is")
        assertEquals(ids.second, session.reader.playlistOverlay.resolve(localId))
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun thePersonSaysNoneIsTheirsAndTheCreateIsSent() = playlistTest { env ->
        val (session, localId, ids) = ambiguousLostCreate(env)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.chooseCreated(localId, null))
        advanceUntilIdle()
        assertEquals(2, env.server.count("createPlaylist"))
        val created = assertIs<PlaylistEditOutcome.Created>(env.outcomes.last())
        assertTrue(created.playlistId !in listOf(ids.first, ids.second))
        assertEquals(3, env.server.playlists.size)
        assertEquals(PlaylistEditRecord.Invalid, session.playlists.chooseCreated(localId, null), "no longer waiting")
    }

    @Test
    fun aCreateWaitingForAChoiceCanBeWithdrawn() = playlistTest { env ->
        val (session, localId, ids) = ambiguousLostCreate(env)
        assertEquals(PlaylistEditRecord.AlreadySent, session.playlists.withdraw(localId, PlaylistRowKind.Create), "too late to undo")
        advanceUntilIdle()
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "Once", listOf(ids.first, ids.second)), env.outcomes.last())
        assertEquals(0, env.server.count("deletePlaylist"))
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun aRowWithNoPreSendListAdoptsNothingOnItsOwn() = playlistTest { env ->
        // A create in doubt whose pre-send list was never recorded (a row from before it existed):
        // every playlist of the name is a candidate, so the one there is named, never adopted.
        val older = env.server.add("Road", listOf("song-1"), created = env.clock.now - 3_600_000)
        val session = env.session()
        env.server.failWithError["createPlaylist"] = DomainError.Transport.Timeout
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.failWithError.clear()
        env.driver.execute(
            null,
            "UPDATE mutation_outbox SET value = replace(value, ',\"seenBeforeSend\":[\"${older.id}\"]', '') WHERE target_id = '$localId'",
            0,
        )
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(PlaylistEditOutcome.PossibleDuplicate(localId, "Road", listOf(older.id)), env.outcomes.last())
        assertEquals(1, env.server.count("createPlaylist"))
    }

    // ---- X6: an append in doubt that never arrived stays an append, however reshaped here ---------

    @Test
    fun q5_aReshapedAppendThatNeverArrivedOnA700SongListIsStillAppended() = playlistTest { env ->
        val all = (100..799).map { "song-$it" }
        val p = env.server.add("Long", all)
        val session = env.session(formPost = false)
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.failWithError["updatePlaylist"] = DomainError.Transport.Timeout // never arrives
        session.playlists.append(p.id, listOf("song-1", "song-2"))
        advanceUntilIdle()
        env.server.failWithError.clear()
        session.setOnline(false)
        val view = detail.last.items.map { it.rawId }
        assertEquals(PlaylistEditRecord.Pending, session.playlists.remove(p.id, setOf(view.lastIndex), view)) // drops song-2
        session.setOnline(true)
        env.server.log.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(all + "song-1", p.entries)
        assertEquals(listOf("updatePlaylist"), env.server.writes().map { it.endpoint })
        assertEquals(listOf("song-1"), env.server.writes().single().all("songIdToAdd"))
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun q6_aReshapedAppendThatNeverArrivedIsSentAsAnAppend() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.failWithError["updatePlaylist"] = DomainError.Transport.Timeout
        session.playlists.append(p.id, listOf("song-2", "song-3"))
        advanceUntilIdle()
        env.server.failWithError.clear()
        session.setOnline(false)
        val view = detail.last.items.map { it.rawId }
        session.playlists.remove(p.id, setOf(2), view)
        session.setOnline(true)
        env.server.log.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2"), p.entries)
        assertEquals(listOf("updatePlaylist"), env.server.writes().map { it.endpoint }, "an append, never the whole list")
        assertEquals(listOf("song-2"), env.server.writes().single().all("songIdToAdd"))
    }

    @Test
    fun aReshapedAppendThatNeverArrivedMovedAmongItsSongsIsStillAnAppend() = playlistTest { env ->
        // Never arrived is judged against the list it was sent onto; what remains is the row's own
        // edit, which still only adds at the end — so an append, never the whole list.
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.failWithError["updatePlaylist"] = DomainError.Transport.Timeout
        session.playlists.append(p.id, listOf("song-2", "song-3"))
        advanceUntilIdle()
        env.server.failWithError.clear()
        session.setOnline(false)
        val view = detail.last.items.map { it.rawId }
        session.playlists.move(p.id, 2, 1, view) // song-3 before song-2: reshaped, still only adds
        session.setOnline(true)
        env.server.log.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-3", "song-2"), p.entries)
        assertEquals(listOf("updatePlaylist"), env.server.writes().map { it.endpoint })
        assertEquals(listOf("song-3", "song-2"), env.server.writes().single().all("songIdToAdd"))
    }
}
