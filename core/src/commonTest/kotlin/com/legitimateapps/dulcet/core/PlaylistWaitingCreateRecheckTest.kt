package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A create waiting for the person's choice is checked against the server's list at each flush
 * (spec §18.6): a candidate deleted there leaves the choice, so the person is never asked about a
 * playlist that no longer exists, and a create left with none is looked at again.
 */
class PlaylistWaitingCreateRecheckTest {
    /** A create whose answer was lost, waiting between what its send made and one another client made. */
    private suspend fun TestScope.waitingBetweenTwo(env: PlaylistEnv): Triple<LibraryReaderSession, String, Pair<FakePlaylistServer.Playlist, FakePlaylistServer.Playlist>> {
        val session = env.session()
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
    fun aListingThatFailsLeavesTheChoiceAsItWas() = playlistTest { env ->
        val (session, localId, pair) = waitingBetweenTwo(env)
        env.server.playlists.remove(pair.second)
        env.server.failWithError["getPlaylists"] = DomainError.Transport.Timeout
        val report = session.playlists.flush()
        advanceUntilIdle()
        assertNull(report.stoppedBy)
        val waiting = session.playlists.pendingChanges().single()
        assertEquals(listOf(pair.first.id, pair.second.id), waiting.candidates, "nothing is decided without the list")
        assertEquals(1, env.outcomes.size)
        assertIs<PlaylistEditOutcome.PossibleDuplicate>(env.outcomes.single())
        assertEquals(localId, waiting.playlistId)
        assertEquals(1, env.server.count("createPlaylist"))
    }
}
