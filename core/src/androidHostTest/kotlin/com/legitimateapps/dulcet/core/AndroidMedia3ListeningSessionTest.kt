package com.legitimateapps.dulcet.core

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidMedia3ListeningSessionTest {
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RuntimeEnvironment.getApplication().getSharedPreferences("listening-session", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun timedSleepFadesOnElapsedTimeCancelRestoresGainAndDeadlinePauses() {
        Fixture().use { f ->
            f.play("one", "two")
            f.controller.setSleepTimer(SleepTimerChoice.Minutes15)
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(895_000))
            f.controller.tickSleepTimer()
            assertEquals(5_000L, f.controller.state.value.sleepRemainingMilliseconds)
            assertEquals(0.5f, f.probe.volume)
            assertTrue(f.controller.state.value.playWhenReady)
            f.controller.setSleepTimer(null)
            assertEquals(1f, f.probe.volume)
            ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(20))
            f.controller.tickSleepTimer()
            assertTrue(f.controller.state.value.playWhenReady, "Cancellation must prevent the old deadline pausing")
            f.controller.setSleepTimer(SleepTimerChoice.Minutes30)
            ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(30))
            f.controller.tickSleepTimer()
            assertFalse(f.controller.state.value.playWhenReady)
            assertFalse(f.probe.requested)
            assertNull(f.controller.state.value.sleepTimer)
            assertEquals(1f, f.probe.volume, "Deadline restores gain for a later Play")
        }
    }

    @Test fun endOfTrackSelectsTheNextEntryPausedAndCancelAllowsItsNaturalAdvance() {
        Fixture().use { f ->
            f.play("one", "two")
            f.controller.setSleepTimer(SleepTimerChoice.EndOfTrack)
            f.end()
            assertEquals(1, f.controller.state.value.currentIndex)
            assertFalse(f.controller.state.value.playWhenReady)
            assertNull(f.controller.state.value.sleepTimer)
            f.play("one", "two")
            f.controller.setSleepTimer(SleepTimerChoice.EndOfTrack)
            f.controller.setSleepTimer(null)
            f.end()
            assertEquals(1, f.controller.state.value.currentIndex)
            assertTrue(f.controller.state.value.playWhenReady)
        }
    }

    @Test fun endOfQueuePlaysUserEntriesThenStopsBeforeAutoplay() {
        Fixture(candidates = { _, _, _ -> listOf(track("auto")) }).use { f ->
            f.play("one", "two")
            assertTrue(f.controller.state.value.queue.any { it.isAutoplay }, "Control: the server supplied AutoPlay")
            f.controller.setSleepTimer(SleepTimerChoice.EndOfQueue)
            f.end()
            assertEquals("two", f.controller.state.value.queue[f.controller.state.value.currentIndex!!].track.rawId)
            assertTrue(f.controller.state.value.playWhenReady)
            f.end()
            assertFalse(f.controller.state.value.playWhenReady)
            assertNull(f.controller.state.value.sleepTimer)
            assertTrue(f.prepared.none { it.itemId.rawId == "auto" && f.probe.requested })
        }
    }

    @Test fun autoplayAnswersOnceAndUserAdditionsStayBeforeItsSectionThenOffRemovesIt() {
        var calls = 0
        val answer = CompletableDeferred<List<AndroidTrack>>()
        Fixture(candidates = { _, excluded, _ ->
            calls++
            assertTrue("one" in excluded)
            answer.await()
        }).use { f ->
            f.play("one")
            assertEquals(1, calls)
            repeat(5) { f.controller.rememberTracks(listOf(track("known$it"))) }
            assertEquals(1, calls, "Repeated publications cannot issue a request twice")
            answer.complete(listOf(track("auto")))
            f.controller.addToQueue(listOf(track("user")), AndroidQueueInsertion.AddToQueue, AndroidQueueSource.Search, "Search")
            assertEquals(listOf("one", "user", "auto"), f.controller.state.value.queue.map { it.track.rawId })
            assertEquals(listOf(false, false, true), f.controller.state.value.queue.map { it.isAutoplay })
            f.controller.setAutoplay(false)
            assertEquals(listOf("one", "user"), f.controller.state.value.queue.map { it.track.rawId })
            assertFalse(f.controller.state.value.autoplayEnabled)
        }
        Fixture().use { f -> assertFalse(f.controller.state.value.autoplayEnabled, "Device preference survives recreation") }
    }

    @Test fun confirmationsFollowAppliedEditsAndEmptyPlayerOnlyStartsPlayback() {
        Fixture().use { f ->
            f.controller.setAutoplay(false)
            assertTrue(f.controller.addToQueue(listOf(track("one")), AndroidQueueInsertion.PlayNext, AndroidQueueSource.Search, "Search"))
            assertNull(f.controller.state.value.queueConfirmation)
            for (insertion in AndroidQueueInsertion.entries) {
                assertTrue(f.controller.addToQueue(listOf(track(insertion.name)), insertion, AndroidQueueSource.Search, "Search"))
                val notice = assertNotNull(f.controller.state.value.queueConfirmation)
                assertEquals(insertion, notice.insertion)
                assertTrue(f.controller.state.value.queue.any { it.track.rawId == insertion.name })
                assertTrue(f.controller.dismissQueueConfirmation(notice.sequence))
                assertFalse(f.controller.dismissQueueConfirmation(notice.sequence), "Only one foreground surface claims it")
            }
        }
    }

    @Test fun crossfadePreferencePersistsAndSameAlbumKeepsTheExistingBoundary() {
        Fixture().use { f ->
            f.controller.setAutoplay(false)
            f.controller.setCrossfade(5)
            f.controller.playQueue(listOf(track("one").copy(albumRawId = "album"), track("two").copy(albumRawId = "album")),
                0, AndroidQueueSource.Search, "Search")
            f.progress(35)
            assertEquals(listOf("one"), f.prepared.map { it.itemId.rawId }, "Same-album zero boundary must not preload or end early")
            f.end()
            assertEquals(listOf("one", "two"), f.prepared.map { it.itemId.rawId })
        }
        Fixture().use { f -> assertEquals(5, f.controller.state.value.crossfadeSeconds) }
    }

    @Test fun controllerRegistersAndPromotesOverlapThenAnIncomingFailureReleasesTheTail() {
        val incoming = PlayerProbe()
        Fixture(overlap = incoming).use { f ->
            f.controller.setAutoplay(false)
            f.controller.setCrossfade(5)
            f.play("one", "two")
            assertEquals(listOf("one"), f.prepared.map { it.itemId.rawId }, "Preload waits for progressing media time")
            incoming.state = androidx.media3.common.Player.STATE_READY
            f.progress(35)
            assertEquals(listOf("one", "two"), f.prepared.map { it.itemId.rawId })
            assertEquals(1, f.controller.state.value.currentIndex)
            assertEquals("Song two", f.controller.state.value.title)
            assertTrue(incoming.requested && f.probe.requested)
            assertNull(f.probe.listener, "Outgoing tail is detached at promotion")
            incoming.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            assertTrue(f.probe.released, "An incoming failure cannot leave the old tail playing")
            assertFalse(f.controller.state.value.playWhenReady)
        }
    }

    @Test fun mediaSessionBridgeFollowsTheIncomingPlayersMetadataAndIntent() {
        val context = RuntimeEnvironment.getApplication()
        val outgoing = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        val incoming = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        val bridge = AndroidSwitchingSessionPlayer(outgoing)
        try {
            outgoing.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("outgoing").setUri("dulcet://fixture/outgoing").build())
            incoming.setMediaItem(androidx.media3.common.MediaItem.Builder().setMediaId("incoming").setUri("dulcet://fixture/incoming").build())
            incoming.playWhenReady = true
            bridge.switchTo(incoming)
            assertEquals("incoming", bridge.currentMediaItem?.mediaId)
            assertTrue(bridge.playWhenReady)
            bridge.pause()
            assertFalse(incoming.playWhenReady)
            assertFalse(outgoing.playWhenReady)
        } finally { bridge.release(); outgoing.release() }
    }

    private class Fixture(val overlap: PlayerProbe? = null, val candidates: suspend (String, Set<String>, Int) -> List<AndroidTrack> = { _, _, _ -> emptyList() }) : AutoCloseable {
        val probe = PlayerProbe()
        val prepared = mutableListOf<RemotePlaybackWirePlan>()
        private val context = RuntimeEnvironment.getApplication()
        private val name = "listening-${java.util.UUID.randomUUID()}.db"
        private val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, name).createDriver())
        val controller = AndroidPlaybackController(context, PlaybackEndpointAccount("provider", "http://127.0.0.1:4533", "fixture", "fixture", true),
            AndroidPlaybackControllerBoundaries(store, probe.player, { prepared += it }, { id -> song(id) },
                resolve = { r -> PlaybackResolutionResult.Resolved(RemotePlaybackWirePlan(r.playbackSessionId, r.attemptId,
                    r.itemId, PlaybackDeliveryPath.Legacy, PlaybackDeliveryProtocol.HttpProgressive, AudioContainer.Wav,
                    PlaybackWireTranscodeDecision.LegacyHint(null, null), endpoint = "stream", parameters = mapOf("id" to r.itemId.rawId), resolutionRequest = r)) },
                enqueueDelivery = {}, overlapPlayer = overlap?.let { { it.player } }, autoplayCandidates = candidates))
        fun play(vararg ids: String) { controller.playQueue(ids.map(::track), 0, AndroidQueueSource.Search, "Search") }
        fun end() { probe.position = 40_000; probe.state = androidx.media3.common.Player.STATE_ENDED; probe.events() }
        fun progress(seconds: Int) {
            probe.position = 0; probe.state = androidx.media3.common.Player.STATE_READY; probe.events()
            repeat(seconds * 2) { probe.position += 500; shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500)) }
        }
        override fun close() { controller.close(); store.close(); context.deleteDatabase(name) }
    }
    companion object {
        private fun track(id: String) = AndroidTrack("provider", id, "Song $id", durationMilliseconds = 40_000)
        private fun song(id: String) = AuthenticatedEndpointResponse(200,
            """{"subsonic-response":{"status":"ok","song":{"id":"$id","title":"Song $id","duration":40,"suffix":"wav"}}}""".toByteArray(),
            "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
            RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
                emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))
    }
}
