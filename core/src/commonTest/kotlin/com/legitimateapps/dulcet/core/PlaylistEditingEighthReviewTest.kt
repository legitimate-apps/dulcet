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
 * The eighth review round of the playlist editor (§18.6, §28 revision 104 item 21). The re-check of
 * the seventh round found two things. A deleted create's tombstone whose OWN lookup failed out was
 * dropped with nothing told, though its send may have made a playlist (p6, p7); the silence belongs
 * only to a refused send of a create deleted while that send was out (p2, p3). And the restore for
 * a re-send in doubt kept the re-send's listing, which the lookup cannot vouch for, since it matches
 * by name: a first-send playlist renamed away and back was then excluded and the create sent again
 * (p4). The restore keeps the first send's listing only, on both branches (p5).
 */
class PlaylistEditingEighthReviewTest {
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

    private suspend fun TestScope.deletedCreateWhoseLookupFails(env: PlaylistEditingFifthReviewTest.Env, lookup: () -> LibraryEndpointResponse) = run {
        val session = timed(env)
        var creates = 0
        var lookups = 0
        var localId: String? = null
        var failLookups = false
        env.hooked.before = { endpoint, parameters ->
            when {
                isNewCreate(endpoint, parameters) -> {
                    creates++
                    env.server.add("Road", listOf("song-1")) // the send committed; its answer is lost
                    session.playlists.delete(localId!!)
                    failLookups = true
                    status(500)
                }
                endpoint == "getPlaylists" && failLookups -> { lookups++; lookup() }
                else -> null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        // Its reconnect flushes both outboxes first (§16.14 step 1): that flush makes the send, and the
        // tombstone's lookups are the flushes counted below.
        runCurrent()
        assertEquals(1, creates, "fixture: the reconnect's flush made the send")
        var refused = 0
        repeat(4) {
            refused += session.playlists.flush().refused
            runCurrent()
        }
        advanceTimeBy(60_000); runCurrent(); advanceUntilIdle()
        assertEquals(1, creates, "fixture: one send, committed, deleted while out, answered 500")
        Quadruple(session, localId!!, lookups, refused)
    }

    private data class Quadruple(val session: LibraryReaderSession, val localId: String, val lookups: Int, val refused: Int)

    /** p6. The tombstone's lookup fails three times with a bare 500: the row goes, and that is told. */
    @Test
    fun p6_aDeletedCreateWhoseOwnLookupFailsOutIsToldNotSaved() = hooked { env ->
        val (session, localId, lookups, refused) = deletedCreateWhoseLookupFails(env) { status(500) }
        assertEquals(3, lookups, "fixture: the tombstone's lookup failed out")
        assertEquals(1, refused, "the dropped tombstone was not counted as refused")
        assertTrue(
            env.outcomes.any { it is PlaylistEditOutcome.NotSaved && it.playlistId == localId && it.kind == PlaylistRowKind.Create },
            "the deleted create's send may have made a playlist, its lookup failed out and nothing was told: ${env.outcomes}",
        )
        assertEquals(0L, session.playlists.pendingCount())
    }

    /** p7. The tombstone's lookup refused outright (code 70): told, as in p6. */
    @Test
    fun p7_aDeletedCreateWhoseOwnLookupIsRefusedIsToldNotSaved() = hooked { env ->
        val (session, localId, lookups, refusedCount) = deletedCreateWhoseLookupFails(env) { refused(70) }
        assertEquals(1, lookups, "fixture: the tombstone looked once and was refused")
        assertEquals(1, refusedCount, "the refused tombstone was not counted as refused")
        assertTrue(
            env.outcomes.any { it is PlaylistEditOutcome.NotSaved && it.playlistId == localId && it.kind == PlaylistRowKind.Create },
            "the deleted create's lookup was refused and nothing was told: ${env.outcomes}",
        )
        assertEquals(0L, session.playlists.pendingCount())
    }

    /**
     * p2. A refused RE-SEND of a create deleted while it was out, with the first send in doubt: the
     * refusal is not told — it proves the re-send made nothing — and the surviving tombstone names what
     * the first send made late.
     */
    @Test
    fun p2_aRefusedResendOfADeletedCreateIsUntoldAndItsTombstoneNamesTheLatePlaylist() = hooked { env ->
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
                        late = env.server.add("Road", listOf("song-1"))
                        session.playlists.delete(localId!!)
                        refused(10)
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
        session.playlists.flush(); runCurrent()
        session.playlists.rename(localId!!, "Trip")
        session.playlists.flush(); runCurrent()
        assertEquals(2, creates, "fixture: sent again as Trip, deleted while out, refused")
        assertEquals(emptyList(), env.outcomes.filterIsInstance<PlaylistEditOutcome.NotSaved>(), "the refused re-send of a deleted create was told: ${env.outcomes}")
        advanceTimeBy(10_000); runCurrent()
        session.playlists.flush(); runCurrent(); advanceUntilIdle()
        assertEquals(2, creates, "the deleted create was sent again")
        assertTrue(
            PlaylistEditOutcome.PossiblyCreated(localId!!, "Road", listOf(assertNotNull(late).id)) in env.outcomes,
            "the playlist the first send made was never named: ${env.outcomes}",
        )
        assertEquals(emptyList(), env.outcomes.filterIsInstance<PlaylistEditOutcome.NotSaved>(), "${env.outcomes}")
    }

    /** p3. The last failure of a deleted create's only send, proven unapplied (code 0): untold, uncounted. */
    @Test
    fun p3_aDeletedCreatesLastProvenUnappliedFailureIsNotTold() = hooked { env ->
        val session = timed(env)
        var creates = 0
        var localId: String? = null
        env.hooked.before = { endpoint, parameters ->
            if (isNewCreate(endpoint, parameters)) {
                creates++
                if (creates == 3) session.playlists.delete(localId!!)
                refused(0)
            } else {
                null
            }
        }
        session.setOnline(false)
        localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        val reports = mutableListOf<PlaylistFlushReport>()
        repeat(3) {
            reports += session.playlists.flush()
            runCurrent()
        }
        assertEquals(3, creates, "fixture: three sends, the third deleted while out")
        assertEquals(emptyList(), env.outcomes.filterIsInstance<PlaylistEditOutcome.NotSaved>(), "${env.outcomes}")
        assertEquals(0, reports.sumOf { it.refused }, "$reports")
        assertEquals(0L, session.playlists.pendingCount())
        assertEquals(0, env.server.playlists.size)
    }

    /** p8. The control: the same refused lookup for a create the person KEPT is told. */
    @Test
    fun p8_aKeptCreateWhoseLookupIsRefusedIsTold() = hooked { env ->
        val session = timed(env)
        var failLookups = false
        env.hooked.before = { endpoint, parameters ->
            when {
                isNewCreate(endpoint, parameters) -> { env.server.add("Road", listOf("song-1")); failLookups = true; status(500) }
                endpoint == "getPlaylists" && failLookups -> refused(70)
                else -> null
            }
        }
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        repeat(3) {
            session.playlists.flush()
            runCurrent()
        }
        assertTrue(env.outcomes.any { it is PlaylistEditOutcome.NotSaved && it.playlistId == localId }, "${env.outcomes}")
    }

    /**
     * p4. The first send commits and another client renames its playlist "Elsewhere" at once. The
     * lookup lists it, finds nothing named "Road", and the create is sent again as "Trip". While that
     * re-send is out the playlist is renamed back, the person renames the create, and the re-send is
     * answered 429. With the re-send's listing kept, the first send's playlist is excluded and the
     * create sent a third time: a silent duplicate. With the first send's listing, it is adopted.
     */
    @Test
    fun p4_aFirstSendPlaylistRenamedAwayAndBackIsAdoptedNotDuplicated() = hooked { env ->
        val session = timed(env)
        var creates = 0
        var localId: String? = null
        var first: FakePlaylistServer.Playlist? = null
        env.hooked.before = { endpoint, parameters ->
            if (isNewCreate(endpoint, parameters)) {
                creates++
                when (creates) {
                    1 -> {
                        first = env.server.add("Road", listOf("song-1"))
                        first!!.name = "Elsewhere" // another client renames it at once
                        status(500)
                    }
                    2 -> {
                        first!!.name = "Road"
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
        session.playlists.flush(); runCurrent()
        session.playlists.rename(localId!!, "Trip")
        session.playlists.flush(); runCurrent()
        assertEquals(2, creates, "fixture: the lookup listed Elsewhere, found no Road, sent again as Trip")
        advanceTimeBy(10_000); runCurrent()
        session.playlists.flush(); runCurrent(); advanceUntilIdle()
        assertEquals(2, creates, "sent a third time although the first send's playlist is listed as Road: ${env.outcomes}")
        assertEquals(1, env.server.playlists.size, "${env.server.playlists.map { it.id to it.name }}")
        assertEquals(assertNotNull(first).id, session.reader.playlistOverlay.resolve(localId!!), "${env.outcomes}")
    }

    /**
     * p5. The seventh round's listing scenario with no edit while the re-send is out — the unchanged
     * branch, which restores the first send's record whole. It asks the same question the edited
     * branch now asks ([PlaylistEditingSeventhReviewTest.aPlaylistListedBeforeTheResendAndRenamedInIsAskedAboutNotExcluded]).
     */
    @Test
    fun p5_theUnchangedAndEditedBranchesAskTheSameQuestion() = hooked { env ->
        val session = timed(env)
        var creates = 0
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
                        status(429, null)
                    }
                    else -> null
                }
            } else {
                null
            }
        }
        session.setOnline(false)
        val localId = assertNotNull(session.playlists.create("Road", listOf("song-1")).localId)
        session.setOnline(true)
        // Its reconnect flushes both outboxes first (§16.14 step 1): that is the flush.
        runCurrent()
        other = env.server.add("Other", listOf("song-9"))
        session.playlists.flush(); runCurrent()
        assertEquals(2, creates)
        advanceTimeBy(10_000); runCurrent()
        session.playlists.flush(); runCurrent(); advanceUntilIdle()
        assertEquals(2, creates, "${env.outcomes}")
        val asked = env.outcomes.filterIsInstance<PlaylistEditOutcome.PossibleDuplicate>()
        assertEquals(1, asked.size, "${env.outcomes}")
        assertEquals(setOf(assertNotNull(other).id, assertNotNull(late).id), asked.single().candidates.toSet(), "${env.outcomes}")
    }
}
