package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Provenance follows the playing entry, its durable event and its authenticated connection. */
class PlaylistPlayContextTest {
    @Test
    fun progressingPlaylistSessionSurvivesQueueEditsAdvanceAndRestoreWhileInsertedSearchHasNoContext() {
        val driver = createTestDriver()
        try {
            val db = DulcetDatabaseStore.open(driver).database
            var identity = 0
            fun controller() = PlaybackQueueController(PersistentQueueStore(db), PersistentResumePositionStore(db),
                PlaybackIdentitySource { "$it:${identity++}" })
            val queue = controller()
            val start = queue.replaceAndStart(PlaybackQueueRequest(listOf(item("a"), item("b")),
                PLAYLIST_SOURCE, 0, false)).startDirective!!
            queue.enqueue(PlaybackQueueInsertion(listOf(item("inserted")),
                QueueSourceContext(QueueSourceKind.Search, null, "Search"), QueueInsertionMode.PlayNext))
            queue.setShuffle(true)
            queue.setShuffle(false)
            val submitted = progress(queue, start.attemptId).single()
            assertEquals(PLAYLIST, submitted.playlistContextId)
            val insertion = queue.next().startDirective!!
            assertEquals("inserted", insertion.itemId.rawId)
            assertNull(progress(queue, insertion.attemptId).single().playlistContextId)
            queue.next()
            val restored = controller()
            val resumed = restored.restoreCurrentPaused().startDirective!!
            assertEquals("b", resumed.itemId.rawId)
            assertEquals(PLAYLIST, progress(restored, resumed.attemptId).single().playlistContextId)
            // The outgoing event remains the old song/context after queue advancement and restore.
            assertEquals("a", submitted.itemId.rawId)
            assertEquals(PLAYLIST, submitted.playlistContextId)
        } finally { driver.close() }
    }

    @Test
    fun repeatRetainsPlaylistButCreatesADistinctSessionAndSubmission() {
        val machine = PlaybackCoreStateMachine()
        val first = PlaybackSessionStart(QueueEntryId("entry"), PlaybackSessionId("first"), AttemptId("a"),
            SONG, 60.seconds, PLAYLIST)
        machine.startPlaying(first)
        val events = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
        fun play(attempt: AttemptId, wall: Long) {
            machine.recordPlaybackEvent(PlaybackEngineEvent.PlaybackProgressBegan(attempt, PlaybackWallClockTime(wall), Duration.ZERO))
            for (second in 1..31) {
                val result = machine.recordPlaybackEvent(PlaybackEngineEvent.PositionChanged(attempt, second.seconds,
                    PlaybackMonotonicTime(second.seconds)))
                events += result.effects.mapNotNull { (it as? PlaybackCoreEffect.RecordPlaybackEvent)?.event as? RecordedPlaybackEvent.SubmittedPlay }
            }
        }
        play(first.attemptId, 1000)
        machine.repeatOne(PlaybackSessionId("second"), AttemptId("b"))
        play(AttemptId("b"), 50000)
        assertEquals(listOf(PLAYLIST, PLAYLIST), events.map { it.playlistContextId })
        assertEquals(listOf(1000L, 50000L), events.map { it.sessionStartWallClock.epochMilliseconds })
    }

    @Test
    fun durableContextSurvivesReopenFailedDiscoveryRetryAndFutureTimestampClamp() = runTest {
        val driver = createTestDriver()
        try {
            var now = 1000L
            var mono = Duration.ZERO
            val db = DulcetDatabaseStore.open(driver).database
            PersistentScrobbleOutbox(db, OutboxWallClock { now }).persistSynchronously(EVENT)
            val outbox = PersistentScrobbleOutbox(DulcetDatabaseStore.open(driver).database, OutboxWallClock { now })
            assertEquals(EVENT, outbox.pending(SERVER).single().toSubmittedPlay())
            val wire = Wire(ArrayDeque(listOf(response("{bad"), advertised())))
            val worker = ScrobbleOutboxDeliveryWorker(SERVER, outbox, ScrobbleEndpointSender(wire),
                OutboxWallClock { now }, OutboxMonotonicClock { mono }, ScrobbleOutboxDiagnosticSink {})
            assertEquals(0, worker.onForeground().deliveredCount)
            assertEquals(EVENT, outbox.pending(SERVER).single().toSubmittedPlay())
            assertEquals(1, outbox.pending(SERVER).single().attemptCount)
            assertEquals(emptyList(), wire.sent)
            // No row from this provider may drain under another account.
            assertEquals(emptyList(), outbox.pending(ServerId("other")))
            now = 2000L; mono = 2.seconds
            assertEquals(1, worker.onReachable().deliveredCount)
            assertEquals(mapOf("id" to SONG.rawId, "time" to "2000", "submission" to "true",
                "dulcetContextType" to "playlist", "dulcetContextId" to PLAYLIST.rawId), wire.sent.single())
            assertEquals(0, outbox.count())
        } finally { driver.close() }
    }

    @Test
    fun releasedRowsMigrateToNullAndFreshAndMigratedOutboxColumnsMatch() {
        val old = createReleasedSchemaTestDriver(RELEASED_SCHEMA_6_STATEMENTS)
        val fresh = createTestDriver()
        try {
            old.execute(null, "INSERT INTO scrobble_outbox VALUES ('provider', 'song', 100, 200, 2)", 0)
            DulcetDatabase.Schema.migrate(old, 6, DulcetDatabase.Schema.version)
            val db = DulcetDatabaseStore.open(old).database
            val entry = PersistentScrobbleOutbox(db, OutboxWallClock { 500 }).pending(SERVER).single()
            assertNull(entry.playlistRawId)
            assertNull(entry.toSubmittedPlay().playlistContextId)
            assertEquals(2, entry.attemptCount)
            val columns = "SELECT name || '|' || type || '|' || \"notnull\" FROM pragma_table_info('scrobble_outbox') ORDER BY cid"
            assertEquals(fresh.strings(columns), old.strings(columns))
        } finally { old.close(); fresh.close() }
    }

    @Test
    fun ordinaryAdvertisementsUnsupportedEndpointsAndNowPlayingKeepExistingScrobbleParameters() = runTest {
        val absences = listOf(advertised("[]"), advertised("[{\"name\":\"dulcetHome\",\"versions\":[2]}]"),
            response("", 404), response("", 501), response("""{"subsonic-response":{"status":"failed","error":{"code":70}}}"""))
        for (absence in absences) {
            val wire = Wire(ArrayDeque(listOf(absence)))
            val sender = ScrobbleEndpointSender(wire)
            assertIs<ScrobbleSendResult.Sent>(sender.send(ScrobbleEndpointRequest(EVENT)))
            assertIs<ScrobbleSendResult.Sent>(sender.send(ScrobbleEndpointRequest(EVENT)))
            assertEquals(1, wire.discoveries)
            assertEquals(mapOf("id" to SONG.rawId, "time" to "10000", "submission" to "true"), wire.sent.first())
            assertEquals(wire.sent.first(), wire.sent.last())
        }
        val wire = Wire(ArrayDeque())
        val sender = ScrobbleEndpointSender(wire)
        sender.send(ScrobbleEndpointRequest(RecordedPlaybackEvent.NowPlaying(SONG)))
        sender.send(ScrobbleEndpointRequest(EVENT.copy(playlistContextId = null)))
        assertEquals(0, wire.discoveries)
        assertEquals(mapOf("id" to SONG.rawId, "submission" to "false"), wire.sent.first())
        assertEquals(setOf("id", "time", "submission"), wire.sent.last().keys)
    }

    @Test
    fun failedDiscoveryIsRetriedAndReconnectInvalidatesPositiveAndNegativeAdvertisements() = runTest {
        val failures = listOf(response("{bad"), response("""{"subsonic-response":{"status":"ok"}}"""),
            advertised("[{\"name\":\"dulcetHome\",\"versions\":[\"1\"]}]"),
            response("""{"subsonic-response":{"status":"failed","error":{"code":40}}}"""),
            response("server unavailable", 503))
        for (failure in failures) {
            val wire = Wire(ArrayDeque(listOf(failure, advertised(), advertised("[]"), advertised())))
            val sender = ScrobbleEndpointSender(wire)
            assertIs<ScrobbleSendResult.Failed>(sender.send(ScrobbleEndpointRequest(EVENT)))
            assertEquals(emptyList(), wire.sent)
            assertIs<ScrobbleSendResult.Sent>(sender.send(ScrobbleEndpointRequest(EVENT)))
            assertEquals(PLAYLIST.rawId, wire.sent.last()["dulcetContextId"])
            sender.invalidateCapabilities()
            sender.send(ScrobbleEndpointRequest(EVENT))
            assertNull(wire.sent.last()["dulcetContextId"])
            sender.invalidateCapabilities()
            sender.send(ScrobbleEndpointRequest(EVENT))
            assertEquals(PLAYLIST.rawId, wire.sent.last()["dulcetContextId"])
            assertEquals(4, wire.discoveries)
        }
    }

    @Test
    fun concurrentSendsSerializeDiscoveryAndDiscardAnAnswerFromBeforeReconnect() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val wire = object : ScrobbleEndpointTransport {
            var discoveries = 0
            val sent = mutableListOf<Map<String, String>>()
            override suspend fun extensions(): AuthenticatedEndpointResponse {
                discoveries++
                if (discoveries == 1) { entered.complete(Unit); release.await(); return advertised() }
                return advertised("[]")
            }
            override suspend fun request(parameters: Map<String, String>): AuthenticatedEndpointResponse {
                sent += parameters; return response("""{"subsonic-response":{"status":"ok"}}""")
            }
        }
        val sender = ScrobbleEndpointSender(wire)
        val first = async { sender.send(ScrobbleEndpointRequest(EVENT)) }
        entered.await()
        val second = async { sender.send(ScrobbleEndpointRequest(EVENT)) }
        sender.invalidateCapabilities()
        release.complete(Unit)
        assertIs<ScrobbleSendResult.Sent>(first.await()); assertIs<ScrobbleSendResult.Sent>(second.await())
        assertEquals(2, wire.discoveries)
        assertEquals(listOf(null, null), wire.sent.map { it["dulcetContextId"] })
    }

    @Test
    fun closingTheSenderDuringHeldDiscoveryRetainsDurablePlaylistContextWithoutSendingIds() = runTest {
        val driver = createTestDriver()
        try {
            val outbox = PersistentScrobbleOutbox(DulcetDatabaseStore.open(driver).database, OutboxWallClock { 20000 })
            outbox.persistSynchronously(EVENT)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<Map<String, String>>()
            val sender = ScrobbleEndpointSender(object : ScrobbleEndpointTransport {
                override suspend fun extensions(): AuthenticatedEndpointResponse {
                    entered.complete(Unit)
                    release.await()
                    return advertised()
                }
                override suspend fun request(parameters: Map<String, String>): AuthenticatedEndpointResponse {
                    sent += parameters
                    return response("""{"subsonic-response":{"status":"ok"}}""")
                }
            })
            val worker = ScrobbleOutboxDeliveryWorker(SERVER, outbox, sender, OutboxWallClock { 20000 },
                OutboxMonotonicClock { Duration.ZERO }, ScrobbleOutboxDiagnosticSink {})
            val drain = async { worker.onForeground() }
            entered.await()
            sender.close()
            release.complete(Unit)
            val result = drain.await()
            assertEquals(emptyList(), sent, "A retired sender must not disclose stored song or playlist IDs after discovery returns")
            assertEquals(0, result.deliveredCount)
            assertEquals(EVENT, outbox.pending(SERVER).single().toSubmittedPlay(),
                "Retiring network delivery must retain the durable play and its original playlist provenance")
        } finally { driver.close() }
    }

    @Test
    fun anotherAccountsPlaylistCannotBeAttachedToASong() {
        assertFailsWith<IllegalArgumentException> { EVENT.copy(playlistContextId = ProviderItemId("other", "playlist")) }
    }

    private class Wire(val answers: ArrayDeque<AuthenticatedEndpointResponse>) : ScrobbleEndpointTransport {
        var discoveries = 0
        val sent = mutableListOf<Map<String, String>>()
        override suspend fun extensions(): AuthenticatedEndpointResponse { discoveries++; return answers.removeFirst() }
        override suspend fun request(parameters: Map<String, String>): AuthenticatedEndpointResponse {
            sent += parameters; return response("""{"subsonic-response":{"status":"ok"}}""")
        }
    }

    private fun progress(queue: PlaybackQueueController, attempt: AttemptId): List<RecordedPlaybackEvent.SubmittedPlay> {
        queue.recordPlaybackEvent(PlaybackEngineEvent.Ready(attempt, 60.seconds, PlaybackSeekability.Seekable))
        queue.recordPlaybackEvent(PlaybackEngineEvent.PlaybackProgressBegan(attempt, PlaybackWallClockTime(1000), Duration.ZERO))
        return (1..31).flatMap { second -> queue.recordPlaybackEvent(PlaybackEngineEvent.PositionChanged(attempt,
            second.seconds, PlaybackMonotonicTime(second.seconds))).effects }.mapNotNull {
                (it as? PlaybackCoreEffect.RecordPlaybackEvent)?.event as? RecordedPlaybackEvent.SubmittedPlay
            }
    }

    private companion object {
        val SERVER = ServerId("provider")
        val SONG = ProviderItemId(SERVER.value, "song")
        val PLAYLIST = ProviderItemId(SERVER.value, "playlist:/opaque & unicode ♪")
        val PLAYLIST_SOURCE = QueueSourceContext(QueueSourceKind.Playlist, PLAYLIST, "Road")
        val EVENT = RecordedPlaybackEvent.SubmittedPlay(SONG, PlaybackWallClockTime(10000), PLAYLIST)
        fun item(id: String) = PlaybackQueueItem(ProviderItemId(SERVER.value, id), 60.seconds)
        fun advertised(extensions: String = """[{"name":"dulcetHome","versions":[1]}]""") =
            response("""{"subsonic-response":{"status":"ok","openSubsonicExtensions":$extensions}}""")
        fun response(body: String, status: Int = 200) = AuthenticatedEndpointResponse(status, body.encodeToByteArray(),
            "http://fixture.invalid/rest/endpoint?<redacted>", AuthenticatedEndpointResponseHeaders(null, null, null, null, null),
            RequestTrace.observed("scrobble", "GET", "http://fixture.invalid/rest/endpoint?<redacted>",
                AuthenticationLocation.Query, emptySet(), emptySet(), emptySet(), "1.16.1", "fixture"))
    }
}
