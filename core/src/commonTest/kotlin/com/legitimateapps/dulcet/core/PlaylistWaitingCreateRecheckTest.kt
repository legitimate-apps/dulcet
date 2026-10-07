package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * A create waiting for the person's choice is checked against the server's list at each flush
 * (spec §18.6): a candidate deleted there leaves the choice, so the person is never asked about a
 * playlist that no longer exists, and a create left with none is looked at again.
 */
class PlaylistWaitingCreateRecheckTest {
    /** A create whose answer was lost, waiting between what its send made and one another client made. */
    private suspend fun TestScope.waitingBetweenTwo(
        env: PlaylistEnv,
        config: LibraryReaderConfig = LibraryReaderConfig(lookAheadMaxPerViewport = 0),
    ): Triple<LibraryReaderSession, String, Pair<FakePlaylistServer.Playlist, FakePlaylistServer.Playlist>> {
        val session = env.session(config = config)
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Once", listOf("song-1")).localId)
        advanceUntilIdle()
        env.server.applyThenLose.clear()
        val lost = env.server.playlists.single()
        val elsewhere = env.server.add("Once", listOf("song-1"))
        session.playlists.flush()
        advanceUntilIdle()
        // Fixture: the create waits for the person between both, and nothing was sent again.
        assertEquals(listOf(lost.id, elsewhere.id), session.playlists.pendingChanges().single().candidates)
        assertEquals(listOf<PlaylistEditOutcome>(PlaylistEditOutcome.PossibleDuplicate(localId, "Once", listOf(lost.id, elsewhere.id))), env.outcomes)
        return Triple(session, localId, lost to elsewhere)
    }

    @Test
    fun aCandidateDeletedElsewhereLeavesTheChoiceAndTheRestIsAskedAgain() = playlistTest { env ->
        val (session, localId, pair) = waitingBetweenTwo(env)
        val (lost, elsewhere) = pair
        env.server.playlists.remove(elsewhere)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf(lost.id), session.playlists.pendingChanges().single().candidates, "the deleted one is no longer offered")
        assertEquals(PlaylistEditOutcome.PossibleDuplicate(localId, "Once", listOf(lost.id)), env.outcomes.last(), "asked again with what remains")
        // Never adopted on its own, though it is now the only one and holds what was sent: the person
        // passed over it once.
        assertEquals(1, env.server.count("createPlaylist"))
        assertEquals(listOf(lost.id), env.server.playlists.map { it.id })
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(2, env.outcomes.size, "an unchanged choice is not asked again")
    }

    @Test
    fun aCreateWhoseCandidatesAreAllDeletedIsLookedAtAgainAndSent() = playlistTest { env ->
        val (session, localId, pair) = waitingBetweenTwo(env)
        env.server.playlists.remove(pair.first)
        env.server.playlists.remove(pair.second)
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(2, env.server.count("createPlaylist"), "nothing of the name is listed, so it is sent again")
        val made = env.server.playlists.single()
        assertEquals(listOf("song-1"), made.entries)
        assertEquals(PlaylistEditOutcome.Created(localId, made.id), env.outcomes.last())
        assertEquals(0L, session.playlists.pendingCount())
    }

    @Test
    fun eachFlushListsOnceWhileACreateWaitsAndNotAtAllOtherwise() = playlistTest { env ->
        val (session, _, _) = waitingBetweenTwo(env)
        val before = env.server.count("getPlaylists")
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(before + 1, env.server.count("getPlaylists"), "one listing serves the waiting create")
        session.playlists.withdraw(session.playlists.pendingChanges().single().playlistId, PlaylistRowKind.Create)
        advanceUntilIdle()
        val idle = env.server.count("getPlaylists")
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(idle, env.server.count("getPlaylists"), "nothing waits, so nothing is listed")
    }

    @Test
    fun aListingThatTimesOutStopsTheFlushAndLeavesTheChoiceAsItWas() = playlistTest { env ->
        val (session, localId, pair) = waitingBetweenTwo(env)
        env.server.playlists.remove(pair.second)
        env.server.failWithError["getPlaylists"] = DomainError.Transport.Timeout
        val report = session.playlists.flush()
        advanceUntilIdle()
        assertEquals(DomainError.Transport.Timeout, report.stoppedBy, "stopped as a change's own timeout stops it")
        val waiting = session.playlists.pendingChanges().single()
        assertEquals(localId, waiting.playlistId)
        assertEquals(listOf(pair.first.id, pair.second.id), waiting.candidates, "nothing is decided without the list")
        assertEquals(1, env.outcomes.size)
        assertIs<PlaylistEditOutcome.PossibleDuplicate>(env.outcomes.single())
        assertEquals(1, env.server.count("createPlaylist"))
    }

    @Test
    fun aListingAnswered429HoldsEveryChangeUntilItsRetryAfter() = playlistTest { env ->
        val (session, localId, _) = waitingBetweenTwo(env, LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource))
        val p = env.server.add("Mix", listOf("song-1"))
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.httpStatus["getPlaylists"] = 429
        env.server.retryAfter = "5"
        // The rename's own flush lists for the waiting create first.
        session.playlists.rename(p.id, "Evening")
        runCurrent()
        assertEquals(PlaylistEditOutcome.Held(localId, PlaylistRowKind.Create, DomainError.Server.Busy(5.seconds)), env.outcomes.last())
        assertEquals(0, env.server.count("updatePlaylist"), "the rename queued behind it is not sent into the 429")
        env.server.httpStatus.clear()
        advanceTimeBy(2_000)
        runCurrent()
        val early = session.playlists.flush()
        assertIs<DomainError.Server.Busy>(early.stoppedBy, "within the Retry-After nothing is sent")
        assertEquals(0, env.server.count("updatePlaylist"))
        advanceTimeBy(3_001)
        runCurrent()
        advanceUntilIdle()
        assertEquals("Evening", p.name, "sent once the server takes changes again")
    }

    @Test
    fun aListingRefusedOnItsOwnLetsTheChangesBehindItGo() = playlistTest { env ->
        val (session, _, pair) = waitingBetweenTwo(env)
        val p = env.server.add("Mix", listOf("song-1"))
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.failWithError["getPlaylists"] = DomainError.Server.Known(0)
        // The rename's own flush lists for the waiting create first.
        session.playlists.rename(p.id, "Evening")
        advanceUntilIdle()
        assertEquals("Evening", p.name, "the listing's own failure holds nothing else")
        assertEquals(listOf(pair.first.id, pair.second.id), session.playlists.pendingChanges().single().candidates)
    }
}
