package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The second independent review of `feat/playlists-core` (spec §18.6, §28 revision 99 item 20): the
 * reviewer's probes p3–p10 as permanent tests, and one test per surviving mutant (`s5a…`–`s5f…`).
 * Each probe was seen failing on the reviewed commit before its fix. The lost-create and access
 * sections are restated as round 3 decided them (the third review, [PlaylistEditingThirdReviewTest]).
 */
class PlaylistEditingSecondReviewTest {
    // ---- S1, as round 3 made it: a lost create is found by what was listed before its send -------
    // (§18.6). No clock is compared, so skew, whole-second stamps, an answer seen late, an unreadable
    // `created` and an owner the server does not state all adopt alike.

    /** A create whose answer is lost while the server's clock reads [skewMillis] off the device's. */
    private suspend fun TestScope.lostCreateUnderSkew(env: PlaylistEnv, skewMillis: Long): String {
        env.server.now = { env.clock.now + skewMillis }
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        env.clock.now += 5_000
        session.playlists.flush()
        advanceUntilIdle()
        return localId
    }

    private fun assertAdopted(env: PlaylistEnv, localId: String) {
        assertEquals(1, env.server.playlists.size, "adopted, not duplicated")
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(PlaylistEditOutcome.Created(localId, env.server.playlists.single().id), env.outcomes.last())
    }

    @Test
    fun p3_aServerClockOneMillisecondBehindStillAdopts() = playlistTest { env ->
        assertAdopted(env, lostCreateUnderSkew(env, -1))
    }

    @Test
    fun p3_aServerClockTwoMinutesBehindStillAdopts() = playlistTest { env ->
        assertAdopted(env, lostCreateUnderSkew(env, -120_000))
    }

    @Test
    fun p3_aServerClockTwoMinutesAheadStillAdopts() = playlistTest { env ->
        assertAdopted(env, lostCreateUnderSkew(env, 120_000))
    }

    @Test
    fun aServerClockADayBehindStillAdopts() = playlistTest { env ->
        // Round 2 pinned this as a silent duplicate beyond a five-minute tolerance; no clock is
        // compared now, so a day of skew changes nothing.
        assertAdopted(env, lostCreateUnderSkew(env, -24 * 3_600_000L))
    }

    @Test
    fun aServerClockADayAheadStillAdopts() = playlistTest { env ->
        assertAdopted(env, lostCreateUnderSkew(env, 24 * 3_600_000L))
    }

    @Test
    fun p3b_aCreatedStampInWholeSecondsStillAdopts() = playlistTest { env ->
        // Reviewer probe p3b: the device sends at .500, the server stamps the whole second before.
        env.clock.now += 500
        env.server.now = { env.clock.now / 1_000 * 1_000 }
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        assertTrue(env.server.playlists.single().created % 1_000 == 0L, "fixture: a whole-second stamp")
        session.playlists.flush()
        advanceUntilIdle()
        assertAdopted(env, localId)
    }

    @Test
    fun anAnswerSeenLateStillAdopts() = playlistTest { env ->
        // A send the server applied six minutes after it went out (a slow proxy).
        val session = env.session()
        env.server.beforeWrite = { if (it.endpoint == "createPlaylist") env.clock.now += 6 * 60_000 }
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        env.server.beforeWrite = {}
        session.playlists.flush()
        advanceUntilIdle()
        assertAdopted(env, localId)
    }

    @Test
    fun anOwnerSpelledInAnotherCaseIsAdopted() = playlistTest(serverUser = "Listener") { env ->
        assertAdopted(env, lostCreateUnderSkew(env, 0))
    }

    @Test
    fun p4_anOwnerUnstatedLostCreateIsAdopted() = playlistTest { env ->
        // Reviewer probe p4, as round 3 decided: an owner the server does not state rules nothing
        // out, and the one playlist of the name not listed before the send is the create's own.
        env.server.omitOwner = true
        assertAdopted(env, lostCreateUnderSkew(env, 0))
    }

    @Test
    fun aCreatedTimeTheDeviceCannotReadIsNeverConsulted() = playlistTest { env ->
        env.server.createdText = { "sometime today" }
        assertAdopted(env, lostCreateUnderSkew(env, 0))
    }

    @Test
    fun twoCandidatesForAStillWantedCreateAreNamedAndNothingIsSentAgain() = playlistTest { env ->
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val lost = env.server.playlists.single()
        val elsewhere = env.server.add("Once", listOf("song-1"))
        repeat(3) {
            session.playlists.flush()
            advanceUntilIdle()
        }
        assertEquals(2, env.server.playlists.size)
        assertEquals(1, env.server.count("createPlaylist"), "never sent again on a guess")
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossibleDuplicate(localId, "Once", listOf(lost.id, elsewhere.id))), env.outcomes)
        assertEquals(1L, session.playlists.pendingCount())
    }

    // ---- N1: the removal's residual, stated precisely -------------------------------------------

    @Test
    fun p6_anEntryReplacedAtTheChosenPositionInTheWindowIsTheStatedResidual() = playlistTest { env ->
        // Reviewer probe p6, pinned as the residual §18.6 states: another client changed ONLY the
        // chosen position in the round trip, so the read-back equals what was meant and nothing can
        // tell. Its song-9 is removed and the outcome is Saved.
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3", "song-4"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = { if (it.endpoint == "updatePlaylist") p.entries[2] = "song-9" }
        session.playlists.remove(p.id, setOf(2), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-4"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    // ---- S2: a batched removal resumes from its last verified batch -------------------------------

    @Test
    fun p7_aBatchedRemovalInterruptedByATimeoutResumes() = playlistTest { env ->
        val all = (100..899).map { "song-$it" }
        val p = env.server.add("Mix", all)
        val session = env.session(formPost = false)
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = {
            env.server.beforeWrite = {}
            env.server.failWithError["updatePlaylist"] = DomainError.Transport.Timeout // batch 2 never arrives
        }
        session.playlists.remove(p.id, (0 until 700).toSet(), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertTrue(p.entries.size in 101 until 800, "fixture: the first batch landed and the second did not (${p.entries.size})")
        env.server.failWithError.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(all.drop(700), p.entries, "the removal was completed")
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.ChangedElsewhere }, "${env.outcomes}")
    }

    @Test
    fun p7b_aBatchedRemovalWhoseSecondAnswerIsLostResumes() = playlistTest { env ->
        val all = (100..1399).map { "song-$it" }
        val p = env.server.add("Mix", all)
        val session = env.session(formPost = false)
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        var writes = 0
        env.server.beforeWrite = { writes += 1; if (writes == 2) env.server.applyThenLose += "updatePlaylist" }
        session.playlists.remove(p.id, (0 until 1200).toSet(), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        env.server.beforeWrite = {}
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(all.drop(1200), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    // ---- S3: an append in doubt, reshaped here, is positional against the list it was sent onto ---

    /** Server [song-1]; an append of song-2, song-3 lands and its answer is lost. */
    private suspend fun TestScope.appendInDoubt(env: PlaylistEnv): Triple<FakePlaylistServer.Playlist, LibraryReaderSession, List<String>> {
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenLose += "updatePlaylist"
        session.playlists.append(p.id, listOf("song-2", "song-3"))
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries, "fixture: landed, answer lost")
        val view = detail.last.items.map { it.rawId }
        assertEquals(listOf("song-1", "song-2", "song-3"), view)
        return Triple(p, session, view)
    }

    @Test
    fun p8_anAppendInDoubtThenAChangeElsewhereThenALocalRemovalNeverAppendsTwice() = playlistTest { env ->
        val (p, session, view) = appendInDoubt(env)
        p.entries.add(0, "song-9") // another client
        val writesBefore = env.server.writes().size
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.remove(p.id, setOf(2), view)) // drop song-3
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-9", "song-1", "song-2", "song-3"), p.entries, "nothing written over the change elsewhere")
        assertEquals(writesBefore, env.server.writes().size)
        assertEquals(PlaylistEditOutcome.ChangedElsewhere(p.id, listOf("song-9", "song-1", "song-2", "song-3")), env.outcomes.last())
    }

    @Test
    fun anAppendInDoubtThenAMoveAmongItsSongsIsWrittenAgainstTheLandedList() = playlistTest { env ->
        // Still only additions at the end of the list it was made on, but no longer the songs in
        // doubt at their head: an append would call the landed [2, 3] done and lose the move.
        val (p, session, view) = appendInDoubt(env)
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.move(p.id, 2, 1, view))
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-3", "song-2"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    @Test
    fun anAppendInDoubtThenRemovingItsFirstSongIsWrittenAgainstTheLandedList() = playlistTest { env ->
        val (p, session, view) = appendInDoubt(env)
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.remove(p.id, setOf(1), view))
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-3"), p.entries)
        assertEquals(PlaylistEditOutcome.Saved(p.id, PlaylistRowKind.Entries), env.outcomes.last())
    }

    // ---- S4, as round 3 made it: a refusal of access holds every change only when a ping is refused
    // too (the account's); a 429 is honoured and never counted (§18.6 "Failures") ------------------

    private suspend fun TestScope.renameHeldBy(env: PlaylistEnv, status: Int, retryAfter: String? = null): Pair<FakePlaylistServer.Playlist, LibraryReaderSession> {
        val p = env.server.add("Mix", listOf("song-1"))
        val q = env.server.add("Other", listOf("song-1"))
        val session = env.session(config = LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource))
        env.open(session, LibraryQuery.Playlist(p.id))
        env.open(session, LibraryQuery.Playlist(q.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.playlists.rename(q.id, "Morning")
        session.setOnline(true)
        env.server.httpStatus["updatePlaylist"] = status
        env.server.retryAfter = retryAfter
        return p to session
    }

    /** The account refused: the change's request and the ping that checks both meet [status]. */
    private suspend fun TestScope.heldAcrossFlushes(env: PlaylistEnv, status: Int, error: DomainError) {
        val (p, session) = renameHeldBy(env, status)
        env.server.httpStatus["ping"] = status
        repeat(PlaylistEditor.MAX_FAILURES + 1) {
            val report = session.playlists.flush()
            assertEquals(error, report.stoppedBy)
            runCurrent()
        }
        assertEquals(PlaylistEditor.MAX_FAILURES + 1, env.server.count("updatePlaylist"), "one send per flush: the second change waits behind the first")
        assertEquals(PlaylistEditor.MAX_FAILURES + 1, env.server.count("ping"), "one ping per flush")
        assertEquals(2L, session.playlists.pendingCount(), "every change kept, none counted toward a drop")
        assertEquals(PlaylistEditOutcome.Held(p.id, PlaylistRowKind.Details, error), env.outcomes.last())
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.NotSaved }, "${env.outcomes}")
        env.server.httpStatus.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals("Evening", p.name, "sent once access is back")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun p9_aProxy401HoldsEveryChangeForSignInAgain() = playlistTest { env ->
        heldAcrossFlushes(env, 401, DomainError.Auth.InvalidCredentials)
    }

    @Test
    fun aProxy403HoldsEveryChangeAndIsTold() = playlistTest { env ->
        heldAcrossFlushes(env, 403, DomainError.Server.HttpStatus(403))
    }

    @Test
    fun aProxy407HoldsEveryChange() = playlistTest { env ->
        heldAcrossFlushes(env, 407, DomainError.Server.HttpStatus(407))
    }

    @Test
    fun a429WithoutRetryAfterHoldsEveryChangeAndCountsTowardNothing() = playlistTest { env ->
        val (p, session) = renameHeldBy(env, 429)
        val first = session.playlists.flush()
        assertEquals(DomainError.Server.Busy(null), first.stoppedBy)
        assertEquals(PlaylistEditOutcome.Held(p.id, PlaylistRowKind.Details, DomainError.Server.Busy(null)), env.outcomes.last())
        // Without a Retry-After the floor still holds: the scheduled flushes retry, never counting.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2L, session.playlists.pendingCount(), "every change kept, none counted toward a drop")
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.NotSaved }, "${env.outcomes}")
        env.server.httpStatus.clear()
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals("Evening", p.name, "sent once the server takes changes again")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun a429IsHonouredUntilItsRetryAfterThenOneFlushSendsEverything() = playlistTest { env ->
        val (p, session) = renameHeldBy(env, 429, retryAfter = "5")
        val first = session.playlists.flush()
        assertEquals(DomainError.Server.Busy(5.seconds), first.stoppedBy)
        assertEquals(PlaylistEditOutcome.Held(p.id, PlaylistRowKind.Details, DomainError.Server.Busy(5.seconds)), env.outcomes.last())
        assertEquals(1, env.server.count("updatePlaylist"))
        env.server.httpStatus.clear()
        advanceTimeBy(2_000)
        val early = session.playlists.flush()
        assertIs<DomainError.Server.Busy>(early.stoppedBy, "within the Retry-After nothing is sent")
        assertEquals(0, early.sent)
        assertEquals(1, env.server.count("updatePlaylist"))
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(3, env.server.count("updatePlaylist"), "the flush the Retry-After scheduled sent both changes")
        assertEquals("Evening", p.name)
        assertEquals(0L, session.playlists.pendingCount())
    }

    // ---- S6: the device's own database failing is reported as local ------------------------------

    @Test
    fun p10_aDatabaseFailureMidDeliveryIsReportedAsLocal() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.driver.execute(null, "DROP TABLE cache_playlist", 0)
        val report = session.playlists.flush()
        assertTrue(report.interruptedLocally, "a local failure reported as $report")
        assertNull(report.stoppedBy)
    }

    @Test
    fun aDatabaseFailureInsideTheSendIsReportedAsLocal() = playlistTest { env ->
        // The issue sequence is taken as the request goes out: its failure is the device's own.
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.driver.execute(null, "DROP TABLE cache_meta", 0)
        val report = session.playlists.flush()
        assertTrue(report.interruptedLocally, "a local failure reported as $report")
        assertNull(report.stoppedBy)
        assertEquals(0, env.server.count("updatePlaylist"))
    }

    @Test
    fun aFailureTheHttpClientThrowsIsStillTheServers() = playlistTest { env ->
        // The other side of the line above: what the transport throws is classified as the server's,
        // never reported as the device's own database.
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        session.setOnline(true)
        env.server.failWithThrowable["updatePlaylist"] = kotlinx.io.IOException("connection reset")
        val report = session.playlists.flush()
        assertEquals(false, report.interruptedLocally, "$report")
        assertEquals(DomainError.Transport.Unreachable, report.stoppedBy)
        assertEquals(1L, session.playlists.pendingCount())
    }

    // ---- S5: the reviewer's surviving mutants ------------------------------------------------------

    @Test
    fun s5a_withoutFormPostBatchesAreMeasuredPercentEncoded() = playlistTest { env ->
        // Song ids are opaque; one that needs percent-encoding takes up to three bytes per byte.
        // Measured by the HTTP client's own encoding, independently of the editor's estimate.
        val ids = (1..400).map { "ä/ö+ü&%-$it" }
        env.server.songs += ids
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session(formPost = false)
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.playlists.append(p.id, ids)
        advanceUntilIdle()
        assertEquals(listOf("song-1") + ids, p.entries)
        val appends = env.server.log.filter { it.endpoint == "updatePlaylist" }
        assertTrue(appends.size > 1, "batched: ${appends.size}")
        appends.forEach { assertTrue(queryStringBytes(it.parameters) <= PlaylistEditor.QUERY_BUDGET_BYTES, "${queryStringBytes(it.parameters)} bytes") }
    }

    private suspend fun TestScope.moveWithHeaderChangedInItsWindow(env: PlaylistEnv, change: (FakePlaylistServer.Playlist) -> Unit) {
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = { if (it.endpoint == "createPlaylist") change(p) }
        session.playlists.move(p.id, 2, 0, detail.last.items.map { it.rawId })
        advanceUntilIdle()
        val target = listOf("song-3", "song-1", "song-2")
        assertEquals(PlaylistEditOutcome.Diverged(p.id, PlaylistRowKind.Entries, target, target), env.outcomes.last())
    }

    @Test
    fun s5b_aCommentChangedInAMovesWindowIsReported() = playlistTest { env ->
        moveWithHeaderChangedInItsWindow(env) { it.comment = "changed elsewhere" }
    }

    @Test
    fun s5b_aVisibilityChangedInAMovesWindowIsReported() = playlistTest { env ->
        moveWithHeaderChangedInItsWindow(env) { it.isPublic = true }
    }

    @Test
    fun s5c_aVisibilityChangeMadeWhileTheCreateIsInFlightIsSent() = playlistTest { env ->
        val session = env.session()
        var localId = ""
        env.server.beforeWrite = {
            if (it.endpoint == "createPlaylist") {
                env.server.beforeWrite = {}
                assertEquals(PlaylistEditRecord.Pending, session.playlists.setPublic(localId, true))
            }
        }
        localId = assertNotNull(session.playlists.create("Fresh", listOf("song-1")).localId)
        advanceUntilIdle()
        assertTrue(env.server.playlists.single().isPublic, "the visibility changed in flight reached the server")
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun s5d_aCreatesFollowUpHeaderThatFailsIsKeptAndSentLater() = playlistTest { env ->
        val session = env.session()
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Fresh", listOf("song-1")).localId)
        session.playlists.setComment(localId, "for the road")
        session.playlists.setPublic(localId, true)
        session.setOnline(true)
        env.server.failWithError["updatePlaylist"] = DomainError.Transport.Timeout
        session.playlists.flush()
        advanceUntilIdle()
        val p = env.server.playlists.single()
        assertNull(p.comment, "fixture: the follow-up failed")
        assertEquals(1L, session.playlists.pendingCount(), "the comment and visibility are kept as the new playlist's change")
        env.server.failWithError.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals("for the road", p.comment)
        assertTrue(p.isPublic)
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun s5e_code70OnAnEditRecordsThePlaylistAsGone() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        session.setOnline(false)
        session.playlists.rename(p.id, "Evening")
        env.server.playlists -= p // deleted by another client
        session.setOnline(true)
        session.playlists.flush()
        advanceUntilIdle()
        val refused = assertIs<PlaylistEditOutcome.NotSaved>(env.outcomes.last())
        assertTrue(refused.error.isNotFound(), "${refused.error}")
        assertEquals(PlaylistEditRecord.Deleted, session.playlists.rename(p.id, "Again"), "recorded as gone here")
    }

    @Test
    fun s5f_aMoveInsideARemovalsWindowIsReportedWithWhatWasMeant() = playlistTest { env ->
        // Same size, other songs: the read-back is compared by content, never by count.
        val p = env.server.add("Mix", listOf("song-1", "song-2", "song-3", "song-4"))
        val session = env.session()
        val detail = env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.beforeWrite = { if (it.endpoint == "updatePlaylist") p.entries.add(p.entries.removeAt(0)) }
        session.playlists.remove(p.id, setOf(2), detail.last.items.map { it.rawId })
        advanceUntilIdle()
        assertEquals(listOf("song-2", "song-3", "song-1"), p.entries, "fixture: the moved list lost song-4 at the chosen position")
        assertEquals(
            PlaylistEditOutcome.Diverged(p.id, PlaylistRowKind.Entries, listOf("song-2", "song-3", "song-1"), listOf("song-1", "song-2", "song-4")),
            env.outcomes.last(),
        )
    }
}
