package com.legitimateapps.dulcet.core

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The seventh review round of the playlist editor (§18.6, §28 revision 104 item 21). A create whose
 * first send is in doubt is sent again, edited or deleted here while that re-send is out, and the
 * re-send is answered 429: only the FIRST send may have made anything, so the create is looked for
 * again as that send — its name, its songs, the playlists listed before it. And a refusal of a create
 * the person deleted is nothing to tell them.
 */
class PlaylistEditingSeventhReviewTest {
    private fun hooked(block: suspend TestScope.(PlaylistEditingFifthReviewTest.Env) -> Unit) = runTest {
        val driver = createTestDriver()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val database = DulcetDatabaseStore.open(driver)
            val clock = ManualWallClock(now = 3_000_000)
            val server = FakePlaylistServer(now = { clock.now })
            block(
                PlaylistEditingFifthReviewTest.Env(
                    driver, server, PlaylistEditingFifthReviewTest.Hooked(server), database, SeenCacheStore(database, clock), scope,
                ),
            )
        } finally {
            scope.cancel()
            driver.close()
        }
    }

    private fun status(code: Int, retryAfter: String? = null) =
        LibraryEndpointResponse(code, "<html>no</html>", "http://fixture.invalid/rest", retryAfter = retryAfter)

    private fun refused(code: Int) = LibraryEndpointResponse(
        200,
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"refused"}}}""",
        "http://fixture.invalid/rest",
    )

    /** A `createPlaylist` that makes a playlist — not the whole-list write, which names `playlistId`. */
    private fun isNewCreate(endpoint: String, parameters: List<Pair<String, String>>) =
        endpoint == "createPlaylist" && parameters.none { it.first == "playlistId" }

    private fun TestScope.timed(env: PlaylistEditingFifthReviewTest.Env) =
        env.session(LibraryReaderConfig(lookAheadMaxPerViewport = 0, monotonic = testScheduler.timeSource))

    /**
     * "Road" is sent and its answer lost; the person renames it "Trip"; the next flush finds nothing
     * named "Road" and sends "Trip". While that re-send is out the person renames it again, the first
     * send commits (late, residual 4 of §18.6), and the re-send is answered 429. Looked for as the
     * re-send, the create would be sought as "Trip", find nothing and be sent a third time: two
     * playlists for one. Looked for as the first send, "Road" is found and adopted, and the rename
     * follows it.
     */
    @Test
    fun aCreateEditedWhileItsResendIsOutIsLookedForAsItsFirstSendAndNeverDuplicated() = hooked { env ->
        val session = timed(env)
        var creates = 0
        var localId: String? = null
        var late: FakePlaylistServer.Playlist? = null
        env.hooked.before = { endpoint, parameters ->
            if (isNewCreate(endpoint, parameters)) {
                creates++
                when (creates) {
                    1 -> status(500)
                    2 -> {
                        assertEquals("Trip", parameters.first { it.first == "name" }.second, "fixture: the re-send carries the rename")
                        late = env.server.add("Road", listOf("song-1")) // the first send, committed late
                        session.playlists.rename(localId!!, "Journey")
                        status(429, null)
                    }
                    else -> null
                }
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        // Its reconnect flushes both outboxes first (§16.14 step 1): that is the flush.
        runCurrent()
        runCurrent()
        assertEquals(1, creates, "fixture: the first send's answer proves nothing")
        session.playlists.rename(localId!!, "Trip")
        session.playlists.flush()
        runCurrent()
        assertEquals(2, creates, "fixture: looked for as Road, not found, sent again as Trip, edited while out, 429")
        advanceTimeBy(10_000); runCurrent()
        session.playlists.flush()
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, creates, "sent a third time although the first send had made the playlist: ${env.outcomes}")
        assertEquals(1, env.server.playlists.size, "one intended playlist, ${env.server.playlists.size} on the server: ${env.server.playlists.map { it.name }}")
        val x = assertNotNull(late)
        assertEquals(x.id, session.reader.playlistOverlay.resolve(localId!!), "the first send's playlist was not adopted: ${env.outcomes}")
        assertEquals("Journey", x.name, "the rename did not follow the adopted playlist")
        assertEquals(0L, session.playlists.pendingCount())
    }

    /**
     * The songs half: "Road" is sent holding song-1 then song-2 and its answer lost; the person moves
     * song-2 first; the re-send carries that order, is edited while out, and is answered 429; the first
     * send commits late. Looked for as the re-send, the late playlist does not hold what was "sent" and
     * the person is asked; looked for as the first send, it is adopted.
     */
    @Test
    fun aCreateWhoseSongsChangedBeforeItsResendIsLookedForWithItsFirstSendsSongs() = hooked { env ->
        val session = timed(env)
        var creates = 0
        var localId: String? = null
        var late: FakePlaylistServer.Playlist? = null
        env.hooked.before = { endpoint, parameters ->
            if (isNewCreate(endpoint, parameters)) {
                creates++
                when (creates) {
                    1 -> status(500)
                    2 -> {
                        assertEquals(listOf("song-2", "song-1"), parameters.filter { it.first == "songId" }.map { it.second }, "fixture: the re-send carries the move")
                        late = env.server.add("Road", listOf("song-1", "song-2"))
                        session.playlists.rename(localId!!, "Journey")
                        status(429, null)
                    }
                    else -> null
                }
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road", listOf("song-1", "song-2")).localId)
        session.setOnline(true)
        // Its reconnect flushes both outboxes first (§16.14 step 1): that is the flush.
        runCurrent()
        runCurrent()
        assertEquals(PlaylistEditRecord.Pending, session.playlists.move(localId!!, 0, 1, listOf("song-1", "song-2")), "fixture: the move")
        session.playlists.flush()
        runCurrent()
        assertEquals(2, creates, "fixture: sent again with the moved songs, edited while out, 429")
        advanceTimeBy(10_000); runCurrent()
        session.playlists.flush()
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, creates, "sent a third time: ${env.outcomes}")
        val x = assertNotNull(late)
        assertTrue(env.outcomes.none { it is PlaylistEditOutcome.PossibleDuplicate }, "the person was asked about the first send's own playlist: ${env.outcomes}")
        assertEquals(x.id, session.reader.playlistOverlay.resolve(localId!!), "the first send's playlist was not adopted: ${env.outcomes}")
        assertEquals(1, env.server.playlists.size, "${env.server.playlists.map { it.name }}")
        assertEquals(listOf("song-2", "song-1"), x.entries, "the move did not follow the adopted playlist")
    }

    /**
     * The restore keeps the FIRST send's listing only (eighth review round, reversing this round's
     * first form). "Other" is made by another client after the first send and listed by the lookup
     * that leads to the re-send; renamed "Road" by that client while the re-send is out, it is a
     * candidate beside the first send's late playlist, and the person is asked about both. The lookup
     * matches by name, so its listing cannot prove a playlist is not the first send's: a question is
     * the safe answer, a silent duplicate is not (see the eighth round's p4).
     */
    @Test
    fun aPlaylistListedBeforeTheResendAndRenamedInIsAskedAboutNotExcluded() = hooked { env ->
        val session = timed(env)
        var creates = 0
        var localId: String? = null
        var late: FakePlaylistServer.Playlist? = null
        var other: FakePlaylistServer.Playlist? = null
        env.hooked.before = { endpoint, parameters ->
            if (isNewCreate(endpoint, parameters)) {
                creates++
                when (creates) {
                    1 -> status(500)
                    2 -> {
                        other!!.name = "Road"
                        late = env.server.add("Road", listOf("song-1"))
                        session.playlists.rename(localId!!, "Journey")
                        status(429, null)
                    }
                    else -> null
                }
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        // Its reconnect flushes both outboxes first (§16.14 step 1): that is the flush.
        runCurrent()
        runCurrent()
        other = env.server.add("Other", listOf("song-9"))
        session.playlists.flush()
        runCurrent()
        assertEquals(2, creates, "fixture: the lookup listed Other, found nothing named Road, sent again")
        advanceTimeBy(10_000); runCurrent()
        session.playlists.flush()
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, creates, "sent a third time: ${env.outcomes}")
        val asked = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossibleDuplicate>()
        assertEquals(1, asked.size, "the person was not asked: ${env.outcomes}")
        assertEquals(setOf(assertNotNull(other).id, assertNotNull(late).id), asked.single().candidates.toSet(), "${env.outcomes}")
        assertEquals(localId, session.reader.playlistOverlay.resolve(localId!!), "adopted without asking: ${env.outcomes}")
    }

    /**
     * The same, deleted instead of edited while the re-send is out. The tombstone is looked for as the
     * first send, so the playlist that send made late is named to the person; looked for as the
     * re-send, it would be sought as "Trip" and the row dropped with that playlist never mentioned.
     */
    @Test
    fun aCreateDeletedWhileItsResendIsOutNamesWhatItsFirstSendMadeLate() = hooked { env ->
        val session = timed(env)
        val names = mutableListOf<String?>()
        var localId: String? = null
        var late: FakePlaylistServer.Playlist? = null
        env.hooked.before = { endpoint, parameters ->
            if (isNewCreate(endpoint, parameters)) {
                names += parameters.firstOrNull { it.first == "name" }?.second
                when (names.size) {
                    1 -> status(500)
                    2 -> {
                        late = env.server.add("Road", listOf("song-1"))
                        session.playlists.delete(localId!!)
                        status(429, null)
                    }
                    else -> null
                }
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        // The reconnect's flush (§16.14 step 1) is the first send. A second explicit flush here would
        // re-send "Road" before the rename, and the restore would never differ from the re-send.
        session.setOnline(true)
        runCurrent()
        assertEquals(listOf<String?>("Road"), names, "fixture: the reconnect's flush sent the create once")
        session.playlists.rename(localId!!, "Trip")
        session.playlists.flush()
        runCurrent()
        // The condition under test: a re-send that differs from the first send, deleted while out.
        assertEquals(listOf<String?>("Road", "Trip"), names, "fixture: sent again as Trip, deleted while out, 429")
        advanceTimeBy(10_000); runCurrent()
        session.playlists.flush()
        runCurrent()
        advanceUntilIdle()
        assertEquals(2, names.size, "the deleted create was sent again: $names")
        val x = assertNotNull(late)
        assertTrue(
            PlaylistEditOutcome.PossiblyCreated(localId!!, "Road", listOf(x.id)) in env.outcomes,
            "the playlist the first send made was never named to the person: ${env.outcomes}",
        )
        assertEquals(1, env.server.playlists.size, "nothing is deleted on inference")
        assertEquals(0L, session.playlists.pendingCount())
    }

    /** Decision 4: a server's refusal of a create the person deleted while its only send was out is not told. */
    @Test
    fun aRefusalOfACreateDeletedWhileItsOnlySendIsOutIsNotTold() = hooked { env ->
        val session = timed(env)
        var creates = 0
        var localId: String? = null
        env.hooked.before = { endpoint, parameters ->
            if (isNewCreate(endpoint, parameters)) {
                creates++
                session.playlists.delete(localId!!)
                refused(10)
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road").localId)
        session.setOnline(true)
        val report = session.playlists.flush()
        runCurrent()
        assertEquals(1, creates, "fixture: sent once, deleted while out, refused")
        assertEquals(emptyList(), env.outcomes.filterIsInstance<PlaylistEditOutcome.NotSaved>(), "told not saved for a create the person deleted: ${env.outcomes}")
        assertEquals(0, report.refused, "counted as refused: $report")
        assertEquals(0L, session.playlists.pendingCount())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, creates, "the deleted create was sent again")
        assertEquals(0, env.server.playlists.size)
    }

    /** Decision 4's control: a refusal of a create the person kept is still told. */
    @Test
    fun aRefusalOfACreateThePersonKeptIsStillTold() = hooked { env ->
        val session = timed(env)
        env.hooked.before = { endpoint, parameters -> if (isNewCreate(endpoint, parameters)) refused(10) else null }
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Road").localId)
        session.setOnline(true)
        session.playlists.flush()
        runCurrent()
        assertTrue(env.outcomes.any { it is PlaylistEditOutcome.NotSaved && it.playlistId == localId }, "${env.outcomes}")
    }
}
