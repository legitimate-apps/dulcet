package com.legitimateapps.dulcet.core

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A playlist flush run while the reader is OFFLINE — a reconnect's first step (§16.14), which flushes
 * before the epoch read that brings the reader back — cannot re-read the playlist list: every window
 * reads only while the reader is online. The re-read the flush owes after its own write is made by
 * the reconnect that brings the reader back, or the playlist it wrote is unknown here. OBSERVED as
 * CONF-89 against the reference server: a lost create deleted here was named by a reconnect's flush,
 * and the delete the person then confirmed by that id was refused as `NotCached`.
 */
class PlaylistReconnectFlushTest {
    /**
     * A create sent online whose answer was lost, then deleted here while offline: the next flush
     * names what the send made. Returns the session and the create's local id, offline.
     */
    private suspend fun TestScope.lostCreateDeletedOffline(env: PlaylistEnv): Pair<LibraryReaderSession, String> {
        val session = env.session()
        env.server.applyThenLose += "createPlaylist"
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1", "song-2")).localId)
        advanceUntilIdle()
        assertEquals(1, env.server.count("createPlaylist"), "the create was sent once")
        env.server.applyThenLose.clear()
        session.setOnline(false)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(localId), "a create in doubt is cancelled, not dropped")
        env.server.log.clear()
        return session to localId
    }

    @Test
    fun aCandidateNamedByAReconnectsFlushCanBeDeletedOnceTheReconnectEnds() = playlistTest { env ->
        val (session, localId) = lostCreateDeletedOffline(env)
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        val made = env.server.playlists.single()
        // The condition this test exists for: the candidate was named by the reconnect's flush, before
        // its epoch read — while the reader was offline — not by a flush of an online reader.
        val endpoints = env.server.log.map { it.endpoint }
        assertTrue(endpoints.indexOf("getPlaylists") in 0 until endpoints.indexOf("getScanStatus"), "named in §16.14 step 1: $endpoints")
        assertEquals(PlaylistEditOutcome.PossiblyCreated(localId, "Road", listOf(made.id)), env.outcomes.last())
        assertTrue(session.reader.online)
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(made.id), "the candidate named can be deleted by its id")
        advanceUntilIdle()
        assertTrue(env.server.playlists.isEmpty(), "the confirmed delete landed")
        assertEquals(PlaylistEditOutcome.Saved(made.id, PlaylistRowKind.Delete), env.outcomes.last())
    }

    @Test
    fun theListReadAfterAReconnectsFlushIsMadeAfterTheEpochReadNeverBefore() = playlistTest { env ->
        val (session, _) = lostCreateDeletedOffline(env)
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        val endpoints = env.server.log.map { it.endpoint }
        val epochRead = endpoints.indexOf("getScanStatus")
        // Before the epoch read: the flush's own listing, and nothing else of the list.
        assertEquals(1, endpoints.subList(0, epochRead).count { it == "getPlaylists" }, "only the flush's own listing before step 2: $endpoints")
        // The owed re-read, made by the reconnect once the reader is back: exactly one.
        assertEquals(1, endpoints.drop(epochRead).count { it == "getPlaylists" }, "the owed re-read, once: $endpoints")
    }

    @Test
    fun aReconnectThatFailsKeepsTheListReadOwedToTheNextOne() = playlistTest { env ->
        val (session, _) = lostCreateDeletedOffline(env)
        env.server.failWithError["getScanStatus"] = DomainError.Auth.InvalidCredentials
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Failed>(session.reader.reconnect())
        advanceUntilIdle()
        val made = env.server.playlists.single()
        assertFalse(session.reader.online)
        assertEquals(1, env.server.count("getPlaylists"), "the flush's listing only: nothing re-read while offline")
        env.server.failWithError.clear()
        env.server.log.clear()
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertEquals(1, env.server.count("getPlaylists"), "the next reconnect makes the owed re-read")
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(made.id), "and the candidate is known")
        advanceUntilIdle()
        assertTrue(env.server.playlists.isEmpty())
    }
}
