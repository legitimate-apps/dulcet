package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A playlist write whose connection drops after it was sent (spec §18.3, §18.6). The HTTP client
 * reports that as unreachable, as it reports a connection never made, but only the second proves the
 * request never arrived: a connection lost once made may have carried it, and the server may have
 * applied it. So the write is in doubt, as a timed-out one is, and never simply sent again: a create
 * sent again makes a second playlist.
 */
class PlaylistDroppedConnectionTest {
    private fun dropped() = kotlinx.io.IOException("connection reset")

    @Test
    fun aCreateWhoseConnectionDropsAfterItLandedIsAdoptedAndNeverSentAgain() = playlistTest { env ->
        val session = env.session()
        env.server.applyThenDrop["createPlaylist"] = dropped()
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1", "song-2")).localId)
        advanceUntilIdle()
        val made = env.server.playlists.single()
        assertEquals(listOf("song-1", "song-2"), made.entries, "the create landed")
        assertTrue(session.playlists.pendingChanges().single().inDoubt, "a dropped connection leaves the create in doubt")

        env.server.applyThenDrop.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"), "the create was sent again: ${env.server.playlists}")
        assertEquals(listOf(made.id), env.server.playlists.map { it.id })
        assertTrue(PlaylistEditOutcome.Created(localId, made.id) in env.outcomes, "${env.outcomes}")
        assertEquals(0L, session.playlists.pendingCount())
    }

    /**
     * An append is guarded besides: this passed with a dropped connection read as never arrived too
     * (a mutant run), so it holds the append path to adding once, not this classification.
     */
    @Test
    fun anAppendWhoseConnectionDropsAfterItLandedAddsItsSongsOnce() = playlistTest { env ->
        val p = env.server.add("Mix", listOf("song-1"))
        val session = env.session()
        env.open(session, LibraryQuery.Playlist(p.id))
        advanceUntilIdle()
        env.server.applyThenDrop["updatePlaylist"] = dropped()
        session.playlists.append(p.id, listOf("song-2", "song-3"))
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries, "the append landed")

        env.server.applyThenDrop.clear()
        session.playlists.flush()
        advanceUntilIdle()
        assertEquals(listOf("song-1", "song-2", "song-3"), p.entries, "the append was applied twice")
        assertEquals(0L, session.playlists.pendingCount())
    }

    /**
     * The control: the same drop before anything was applied. The client cannot tell the two apart,
     * so the create is in doubt here too, and with nothing of its name listed it is sent once more.
     */
    @Test
    fun aCreateWhoseConnectionDropsBeforeItLandedIsSentOnceMore() = playlistTest { env ->
        val session = env.session()
        env.server.failWithThrowable["createPlaylist"] = dropped()
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        advanceUntilIdle()
        assertTrue(env.server.playlists.isEmpty())
        assertTrue(session.playlists.pendingChanges().single().inDoubt)

        env.server.failWithThrowable.clear()
        session.playlists.flush()
        advanceUntilIdle()
        val made = env.server.playlists.single()
        assertEquals(2, env.server.count("createPlaylist"))
        assertTrue(PlaylistEditOutcome.Created(localId, made.id) in env.outcomes, "${env.outcomes}")
    }

    /** The local-HTTP policy refuses before any socket opens: nothing left, so nothing is in doubt. */
    @Test
    fun aCreateTheLocalHttpPolicyStoppedIsNotInDoubt() = playlistTest { env ->
        val session = env.session()
        env.server.failWithThrowable["createPlaylist"] =
            LocalHttpPolicyFailure(DomainError.Security.LocalExceptionViolated)
        session.playlists.create("Road", listOf("song-1"))
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"), "fixture: the create was tried")
        assertFalse(session.playlists.pendingChanges().single().inDoubt, "a create never sent was left in doubt")
    }
}
