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

    private class Fixture(
        savedOwner: String? = null,
        savedSongs: List<String> = listOf("saved-song"),
        loadSong: suspend (String) -> AuthenticatedEndpointResponse = { song(it) },
        resolve: (suspend (PlaybackResolveRequest) -> PlaybackResolutionResult)? = null,
        onDelivery: (Fixture, RecordedPlaybackEvent) -> Unit = { _, _ -> },
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
                PlaybackEndpointAccount(OWNER, "http://127.0.0.1:4533", "controller-canary", "controller-password-canary", true),
                AndroidPlaybackControllerBoundaries(store, probe.player, { prepared += it }, loadSong, resolve,
                    { onDelivery(this, it) }))
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
