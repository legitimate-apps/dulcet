package com.legitimateapps.dulcet.core

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidPlaybackControllerTest {
    @Before fun dispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun lateMetadataAfterNewSelectionCannotReplaceTheNewQueue() {
        var pending: Continuation<AuthenticatedEndpointResponse>? = null
        var lateReturns = 0
        Fixture(loadSong = { id ->
            if (id == "old") suspendCoroutine<AuthenticatedEndpointResponse> { pending = it }.also { lateReturns++ }
            else song(id)
        }).use { f ->
            f.controller.playSong(OWNER, "old", "Old")
            val old = assertNotNull(pending)
            f.controller.playSong(OWNER, "new", "New")
            assertEquals(listOf("new"), f.prepared.map { it.itemId.rawId })
            old.resume(song("old"))
            assertEquals(1, lateReturns, "The cancelled operation must actually return late")
            assertEquals(listOf("new"), f.prepared.map { it.itemId.rawId })
            assertEquals("New", f.controller.state.value.title)
        }
    }

    @Test fun latePlanAfterStopCannotPrepareOrPlay() {
        var pending: Continuation<PlaybackResolutionResult>? = null
        var request: PlaybackResolveRequest? = null
        var lateReturns = 0
        Fixture(resolve = { value ->
            request = value
            suspendCoroutine<PlaybackResolutionResult> { pending = it }.also { lateReturns++ }
        }).use { f ->
            f.controller.playSong(OWNER, "song", "Song")
            val delayed = assertNotNull(pending)
            f.controller.stop()
            delayed.resume(resolved(assertNotNull(request)))
            assertEquals(1, lateReturns, "The stopped resolution must actually return")
            assertTrue(f.prepared.isEmpty())
            assertFalse(f.probe.requested)
        }
    }

    @Test fun pauseWhileMetadataLoadsAfterStopOverridesThePreviousPlayIntent() {
        var pending: Continuation<AuthenticatedEndpointResponse>? = null
        Fixture(loadSong = { id ->
            if (id == "second") suspendCoroutine { pending = it } else song(id)
        }).use { f ->
            f.controller.playSong(OWNER, "first", "First")
            assertTrue(f.probe.requested)
            f.controller.stop()
            f.controller.playSong(OWNER, "second", "Second")
            val delayed = assertNotNull(pending)
            f.controller.pause()
            delayed.resume(song("second"))
            assertEquals(listOf("first", "second"), f.prepared.map { it.itemId.rawId })
            assertFalse(f.probe.requested, "Preparing the second selection must honor Pause")
            f.controller.play()
            assertTrue(f.probe.requested)
        }
    }

    @Test fun submittedPlayIsInTheRealOutboxBeforeDeliveryHandoff() {
        var handoffs = 0
        Fixture(onDelivery = { f, event ->
            if (event is RecordedPlaybackEvent.SubmittedPlay) {
                handoffs++
                val pending = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { 1L }).pending(ServerId(OWNER))
                assertEquals(1, pending.size, "Delivery was scheduled before durable persistence")
                assertEquals(event.itemId.rawId, pending.single().rawId)
                assertEquals(event.sessionStartWallClock, pending.single().sessionStartWallClock)
            }
        }).use { f ->
            f.controller.playSong(OWNER, "song", "Song")
            assertEquals(1, f.prepared.size)
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            repeat(46) {
                f.probe.position += 500
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            }
            assertEquals(1, handoffs, "The controller must cross the threshold and hand off a submitted play")
        }
    }

    @Test fun restoredQueuePreparesPausedOnlyForItsOwningAccount() {
        for (savedOwner in listOf(OWNER, "different-account")) {
            val loaded = mutableListOf<String>()
            Fixture(savedOwner = savedOwner, loadSong = { id -> loaded += id; song(id) }).use { f ->
                if (savedOwner == OWNER) {
                    assertEquals(listOf("saved-song"), loaded)
                    assertEquals(listOf("saved-song"), f.prepared.map { it.itemId.rawId })
                    assertFalse(f.probe.requested, "Restoration must not auto-play")
                    assertEquals(OWNER, f.prepared.single().itemId.providerInstanceId)
                } else {
                    assertTrue(loaded.isEmpty(), "Another account's queue must not even load metadata")
                    assertTrue(f.prepared.isEmpty())
                    assertFalse(f.probe.requested)
                }
            }
        }
    }

    @Test fun stopThenPlayPreservesRestoredQueueAndRestartsTheCurrentSelection() {
        Fixture(savedOwner = OWNER, savedSongs = listOf("A", "B", "C")).use { f ->
            val queues = PersistentQueueStore(f.store.database)
            val original = queues.load(ServerId(OWNER))
            assertEquals(listOf("A", "B", "C"), original.entries.map { it.providerItemId.rawId })
            assertEquals(3, original.entries.map { it.queueEntryId }.toSet().size)
            for (index in 0..1) {
                if (index > 0) f.controller.next()
                val before = queues.load(ServerId(OWNER))
                val session = f.controller.state.value.playbackSessionId
                assertEquals(index, before.currentIndex)
                f.controller.stop()
                assertEquals(before, queues.load(ServerId(OWNER)), "Stop must not edit persistence")
                f.controller.play()
                assertEquals(before, queues.load(ServerId(OWNER)), "Play must retain entries, IDs, order and selection")
                assertEquals(original.entries, queues.load(ServerId(OWNER)).entries)
                assertEquals(before.entries[index].providerItemId, f.prepared.last().itemId)
                assertEquals(before.entries[index].queueEntryId.value, f.controller.state.value.queueEntryId)
                assertNotEquals(session, f.controller.state.value.playbackSessionId, "Restart begins a fresh session")
                assertTrue(f.probe.requested, "Transport Play must actually request playback")
            }
        }
    }

    @Test fun productionPreparationInstallsAnAttemptScopedMediaSourceOnTheRealPlayer() {
        Fixture(realPlayer = true, resolve = { resolved(it) }).use { f ->
            assertEquals(0, f.controller.sessionPlayer.mediaItemCount)
            f.controller.playSong(OWNER, "source-song", "Source song")
            val player = f.controller.sessionPlayer
            assertEquals(1, player.mediaItemCount, "The production source must be installed, not just a recorded plan")
            val item = player.getMediaItemAt(0)
            assertEquals(f.controller.state.value.attemptId, item.mediaId)
            assertEquals("Source song", item.mediaMetadata.title.toString())
            assertEquals("dulcet://resource", item.localConfiguration?.uri.toString())
            assertTrue(player.playWhenReady)
        }
    }

    @Test fun progressionSendsSubmittedScrobbleThroughTheLiveConsumerWorkerAndSender() {
        ScrobbleReceiver().use { receiver ->
            Fixture(baseUrl = receiver.url, onDelivery = null, resolve = { resolved(it) }).use { f ->
                f.controller.playSong(OWNER, "live-song", "Live song")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                repeat(46) {
                    f.probe.position += 500
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
                }
                val outbox = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { System.currentTimeMillis() })
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while ((receiver.requests.none { it["submission"] == "true" } || outbox.pending(ServerId(OWNER)).isNotEmpty()) &&
                    System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                val submitted = receiver.requests.filter { it["submission"] == "true" }
                assertEquals(1, submitted.size, "The receiving socket must observe a submitted scrobble")
                assertEquals("live-song", submitted.single()["id"])
                assertTrue(assertNotNull(submitted.single()["time"]).toLong() > 0)
                assertTrue(receiver.requests.any { it["submission"] == "false" && it["id"] == "live-song" },
                    "The same live consumer must send now-playing")
                assertTrue(outbox.pending(ServerId(OWNER)).isEmpty(), "A successful server response must acknowledge the outbox row")
            }
        }
    }

    /** Receives real HTTP from the production sender. No controller handoff or sender is replaced. */
    private class ScrobbleReceiver : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val requests = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
        private val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            client.soTimeout = 5000
                            val reader = client.getInputStream().bufferedReader()
                            val line = assertNotNull(reader.readLine()).split(' ')
                            val uri = java.net.URI(line[1])
                            assertEquals("GET", line[0])
                            assertEquals("/rest/scrobble.view", uri.path)
                            while (!reader.readLine().isNullOrEmpty()) { /* drain request headers */ }
                            val query = uri.rawQuery.split('&').associate {
                                val pair = it.split('=', limit = 2)
                                java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
                            }
                            requests += query
                            val body = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
                            client.getOutputStream().apply {
                                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(body); flush()
                            }
                        }
                    } catch (error: Throwable) { if (!socket.isClosed) failures += error }
                }
            }
        }
        override fun close() {
            socket.close(); executor.shutdown()
            assertTrue(executor.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "Scrobble receiving fixture failed: $failures")
        }
    }

    private class Fixture(
        savedOwner: String? = null,
        savedSongs: List<String> = listOf("saved-song"),
        loadSong: suspend (String) -> AuthenticatedEndpointResponse = { song(it) },
        resolve: (suspend (PlaybackResolveRequest) -> PlaybackResolutionResult)? = null,
        onDelivery: ((Fixture, RecordedPlaybackEvent) -> Unit)? = { _, _ -> },
        realPlayer: Boolean = false,
        baseUrl: String = "http://127.0.0.1:4533",
    ) : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val databaseName = "playback-controller-${java.util.UUID.randomUUID()}.db"
        // JDBC's process-wide DriverManager retains sandbox-loaded drivers, which are invisible
        // to ordinary host tests. Use the production Android driver in this Android sandbox.
        val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())
        val probe = PlayerProbe()
        val prepared = mutableListOf<RemotePlaybackWirePlan>()
        val controller: AndroidPlaybackController
        init {
            if (savedOwner != null) {
                PlaybackQueueController(PersistentQueueStore(store.database), PersistentResumePositionStore(store.database),
                    PlaybackIdentitySource { "$it:${java.util.UUID.randomUUID()}" }).replaceAndStart(PlaybackQueueRequest(
                    savedSongs.map { PlaybackQueueItem(ProviderItemId(savedOwner, it), 40.seconds) },
                    QueueSourceContext(QueueSourceKind.Search, null, "Search"), 0, false))
            }
            controller = AndroidPlaybackController(RuntimeEnvironment.getApplication(),
                PlaybackEndpointAccount(OWNER, baseUrl, "controller-canary", "controller-password-canary", true),
                AndroidPlaybackControllerBoundaries(store, if (realPlayer) null else probe.player,
                    if (realPlayer) null else { plan -> prepared += plan }, loadSong, resolve,
                    onDelivery?.let { callback -> { event -> callback(this, event) } }))
        }
        override fun close() {
            try {
                controller.close()
                assertNull(probe.listener, "Controller shutdown must detach the Media3 listener")
                controller.close() // Service teardown may close the owner more than once.
            } finally {
                store.close()
                assertTrue(context.deleteDatabase(databaseName), "The fixture database must be deleted")
                assertFalse(context.getDatabasePath(databaseName).exists())
            }
        }
    }

    companion object {
        private const val OWNER = "provider:owner"
        private fun resolved(r: PlaybackResolveRequest) = PlaybackResolutionResult.Resolved(RemotePlaybackWirePlan(
            r.playbackSessionId, r.attemptId, r.itemId, PlaybackDeliveryPath.Legacy, PlaybackDeliveryProtocol.HttpProgressive,
            r.sourceContainer, PlaybackWireTranscodeDecision.LegacyHint(null, null), endpoint = "stream",
            parameters = mapOf("id" to r.itemId.rawId), resolutionRequest = r))
        private fun song(id: String) = AuthenticatedEndpointResponse(200,
            """{"subsonic-response":{"status":"ok","song":{"id":"$id","suffix":"wav","duration":40}}}""".toByteArray(),
            "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
            RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
                emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))
    }
}
