package com.legitimateapps.dulcet.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.yield
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
    private suspend fun TestScope.lostCreateDeletedOffline(
        env: PlaylistEnv,
        session: LibraryReaderSession = env.session(),
    ): Pair<LibraryReaderSession, String> {
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
        // The whole sequence: the flush's own listing (step 1), ONE epoch read (step 2), then the owed
        // re-read (step 3). A re-read sent before the epoch read would bring a second epoch read of its
        // own window with it, so counting either side of the first one would not tell them apart.
        assertEquals(
            listOf("getPlaylists", "getScanStatus", "getMusicFolders", "getPlaylists"),
            env.server.log.map { it.endpoint },
        )
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

    /** Reports the server unreachable once [condition] holds of the endpoints sent so far. */
    private fun TestScope.cutWhen(session: LibraryReaderSession, env: PlaylistEnv, condition: (List<String>) -> Boolean): () -> Boolean {
        var cut = false
        val watcher = launch {
            repeat(100_000) {
                if (condition(env.server.log.map { it.endpoint })) {
                    session.setOnline(false)
                    cut = true
                    return@launch
                }
                yield()
            }
        }
        return { watcher.cancel(); cut }
    }

    @Test
    fun anOwedReReadCutOffByAnUnreachableReportStaysOwed() = playlistTest { env ->
        val (session, _) = lostCreateDeletedOffline(env)
        session.setOnline(true)
        val cut = cutWhen(session, env) { endpoints ->
            val epoch = endpoints.indexOf("getScanStatus")
            epoch >= 0 && "getPlaylists" in endpoints.drop(epoch)
        }
        session.reader.reconnect()
        advanceUntilIdle()
        assertTrue(cut(), "condition: unreachable was reported while the owed re-read was out: ${env.server.log.map { it.endpoint }}")
        assertFalse(session.reader.online)
        val made = env.server.playlists.single()
        env.server.log.clear()
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertEquals(listOf("getScanStatus", "getMusicFolders", "getPlaylists"), env.server.log.map { it.endpoint }, "the next reconnect makes the owed re-read")
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(made.id), "the candidate is known")
    }

    @Test
    fun aListOpenedWhileTheReconnectRevalidatesDoesNotSwallowTheOwe() = playlistTest { env ->
        val other = env.server.add("Other", listOf("song-9"))
        val session = env.session()
        val list = env.open(session, LibraryQuery.Playlists)
        advanceUntilIdle()
        assertEquals(1, env.server.count("getPlaylists"), "the list was read live")
        list.handle.close()
        lostCreateDeletedOffline(env, session)
        // A screen never read live, opened offline: the reconnect's revalidation reads it.
        env.open(session, LibraryQuery.Playlist(other.id))
        env.server.log.clear()
        session.setOnline(true)
        var reopened: PlaylistEnv.Opened? = null
        val watcher = launch {
            repeat(100_000) {
                if (env.server.log.any { it.endpoint == "getPlaylist" }) {
                    // Read live less than a minute ago: this open reads nothing itself.
                    reopened = env.open(session, LibraryQuery.Playlists)
                    return@launch
                }
                yield()
            }
        }
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        watcher.cancel()
        assertNotNull(reopened, "condition: the list was opened while the reconnect's revalidation read was out: ${env.server.log.map { it.endpoint }}")
        val endpoints = env.server.log.map { it.endpoint }
        assertEquals(listOf("getPlaylists", "getScanStatus", "getMusicFolders", "getPlaylist", "getPlaylists"), endpoints, "the owed re-read is made")
        val made = env.server.playlists.single { it.name == "Road" }
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(made.id), "the candidate is known")
    }

    @Test
    fun anOwedReReadIsMadeOnceNotAtEveryReconnect() = playlistTest { env ->
        val (session, _) = lostCreateDeletedOffline(env)
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertEquals(2, env.server.count("getPlaylists"), "the flush's listing and the owed re-read")
        session.setOnline(false)
        env.server.log.clear()
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertEquals(listOf("getScanStatus", "getMusicFolders"), env.server.log.map { it.endpoint }, "nothing owed at the second reconnect")
    }

    @Test
    fun aListWhoseScreenTheReconnectRevalidatedIsNotReadTwice() = playlistTest { env ->
        val session = env.session()
        val list = env.open(session, LibraryQuery.Playlists)
        advanceUntilIdle()
        lostCreateDeletedOffline(env, session)
        env.server.log.clear()
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertEquals(listOf("getPlaylists", "getScanStatus", "getMusicFolders", "getPlaylists"), env.server.log.map { it.endpoint })
        val made = env.server.playlists.single()
        assertTrue(list.last.items.any { it.rawId == made.id }, "the one read after the epoch read is the screen's own")
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(made.id))
    }

    @Test
    fun aCandidateOfferedIsDeletableWhenOfferedEvenIfTheReconnectFails() = playlistTest { env ->
        val (session, _) = lostCreateDeletedOffline(env)
        env.server.failWithError["getScanStatus"] = DomainError.Auth.InvalidCredentials
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Failed>(session.reader.reconnect())
        advanceUntilIdle()
        assertFalse(session.reader.online)
        assertEquals(listOf("getPlaylists", "getScanStatus"), env.server.log.map { it.endpoint }, "no read of the list but the flush's own")
        val offered = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossiblyCreated>().single().candidates.single()
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(offered), "the offer's own action, taken when offered")
        env.server.failWithError.clear()
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertTrue(env.server.playlists.isEmpty(), "the confirmed delete landed")
    }

    @Test
    fun aListReadThatAFlushSeesGoOfflineIsOwedToTheNextReconnect() = playlistTest { env ->
        // A flush that is not a reconnect's: going offline does not cancel it, so its re-read returns.
        val gone = env.server.add("Gone", listOf("song-1"))
        env.server.add("Kept", listOf("song-2"))
        val session = env.session()
        val list = env.open(session, LibraryQuery.Playlists)
        advanceUntilIdle()
        list.handle.close()
        env.server.log.clear()
        val cut = cutWhen(session, env) { endpoints ->
            val delete = endpoints.indexOf("deletePlaylist")
            delete >= 0 && "getPlaylists" in endpoints.drop(delete)
        }
        assertEquals(PlaylistEditRecord.Pending, session.playlists.delete(gone.id))
        advanceUntilIdle()
        assertTrue(cut(), "condition: unreachable was reported while the flush's re-read was out: ${env.server.log.map { it.endpoint }}")
        assertEquals(listOf("deletePlaylist", "getPlaylists"), env.server.log.map { it.endpoint }, "the flush's re-read went out and returned")
        assertFalse(session.reader.online)
        env.server.log.clear()
        session.setOnline(true)
        assertIs<ReaderConnectionOutcome.Read>(session.reader.reconnect())
        advanceUntilIdle()
        assertEquals(listOf("getScanStatus", "getMusicFolders", "getPlaylists"), env.server.log.map { it.endpoint }, "owed, and made after the epoch read")
    }
}
