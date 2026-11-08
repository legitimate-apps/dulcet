package com.legitimateapps.dulcet.core

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import kotlin.test.*

/**
 * What a player and the controller hold for playback with the screen off. These prove what is
 * asked of the platform; no host test can show what a real radio or a real suspend then does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlaybackWakeModeTest {
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowPowerManager.clearWakeLocks()
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test @Config(sdk = [33])
    fun aStreamAsksForTheWifiLockWhereTheScreenOffPlatformHonoursIt() {
        Fixture().use { f ->
            f.play("one")
            assertEquals(listOf(C.WAKE_MODE_NETWORK), f.current.wakeModes)
        }
    }

    @Test @Config(sdk = [34])
    fun aStreamKeepsOnlyTheWakeLockOnceTheWifiLockWouldBeForegroundLowLatency() {
        Fixture().use { f ->
            f.play("one")
            assertEquals(listOf(C.WAKE_MODE_LOCAL), f.current.wakeModes)
        }
    }

    @Test @Config(sdk = [33])
    fun aDownloadedSongAsksForNoWifiLockAndAReusedPlayerFollowsEachPlan() {
        Fixture(localPlan = { id -> if (id == "down") LOCAL_PLAN else null }).use { f ->
            f.controller.setAutoplay(false)
            f.play("down", "wire")
            assertEquals(listOf(C.WAKE_MODE_LOCAL), f.current.wakeModes)
            f.end()
            assertEquals(listOf(C.WAKE_MODE_LOCAL, C.WAKE_MODE_NETWORK), f.current.wakeModes,
                "The same player moved from a file to the wire")
        }
    }

    @Test @Config(sdk = [33])
    fun theOverlapPlayerAsksForTheSameModeAsTheCurrentOne() {
        val incoming = RecordingExoPlayer()
        Fixture(overlap = incoming).use { f ->
            f.controller.setAutoplay(false)
            f.controller.setCrossfade(5)
            f.play("one", "two")
            assertEquals(emptyList(), incoming.wakeModes, "Preload waits for progressing media time")
            incoming.probe.state = Player.STATE_READY
            f.progress(35)
            assertEquals(listOf(C.WAKE_MODE_NETWORK), incoming.wakeModes)
        }
    }

    @Test fun aTrackBoundaryHoldsTheCpuWhileTheNextStreamResolves() {
        val gate = CompletableDeferred<Unit>()
        Fixture(resolveGate = { id -> if (id == "two") gate.await() }).use { f ->
            f.controller.setAutoplay(false)
            f.play("one", "two")
            assertFalse(heldStartLock(), "The first track resolved and the player owns its own lock")
            f.current.probe.state = Player.STATE_READY
            f.current.probe.events()
            f.end()
            assertEquals(Player.STATE_ENDED, f.current.probe.state, "Media3 holds nothing in this state")
            assertTrue(heldStartLock(), "The next song is resolving and no player holds a lock")
            gate.complete(Unit)
            idle()
            assertEquals(listOf("one", "two"), f.prepared)
            assertTrue(f.current.probe.requested, "The player was told to play before the lock went")
            assertFalse(heldStartLock(), "The playing player owns the lock from here")
        }
    }

    @Test fun nothingIsHeldForAStartThatWillNotPlay() {
        val gate = CompletableDeferred<Unit>()
        Fixture(resolveGate = { id -> if (id == "two") gate.await() }).use { f ->
            f.controller.setAutoplay(false)
            f.play("one", "two")
            f.controller.pause()
            f.end()
            assertFalse(heldStartLock(), "A paused queue has no sound to keep going")
            gate.complete(Unit)
            idle()
            assertEquals(listOf("one", "two"), f.prepared, "The second track's start must really have run")
        }
    }

    @Test fun aNewerStartKeepsTheLockWhenTheStartItReplacedEndsLate() {
        // A file lookup is not cancellable, so the replaced start can outlive the next one's hold.
        val lookup = CompletableDeferred<Unit>()
        val resolve = CompletableDeferred<Unit>()
        Fixture(localPlan = { id -> if (id == "two") lookup.await(); null },
            resolveGate = { id -> if (id == "three") resolve.await() }).use { f ->
            f.controller.setAutoplay(false)
            f.play("one", "two", "three")
            f.end()
            assertTrue(heldStartLock())
            f.controller.next()
            assertTrue(heldStartLock())
            lookup.complete(Unit)
            idle()
            assertTrue(heldStartLock(), "The replaced start ended; the newer one is still resolving")
            resolve.complete(Unit)
            idle()
            assertEquals(listOf("one", "three"), f.prepared)
            assertFalse(heldStartLock())
        }
    }

    @Test fun aStartThatNeverFinishesLetsTheLockLapse() {
        val gate = CompletableDeferred<Unit>()
        Fixture(resolveGate = { id -> if (id == "two") gate.await() }).use { f ->
            f.controller.setAutoplay(false)
            f.play("one", "two")
            f.end()
            assertTrue(heldStartLock())
            ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(2))
            assertFalse(heldStartLock(), "A silent wait must not hold the CPU forever")
            gate.complete(Unit)
            idle()
        }
    }

    @Test fun closingDuringAStartReleasesTheLock() {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(resolveGate = { id -> if (id == "two") gate.await() })
        f.controller.setAutoplay(false)
        f.play("one", "two")
        f.end()
        assertTrue(heldStartLock())
        f.close()
        idle()
        assertFalse(heldStartLock())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Only the controller's lock: the fixture's players are proxies and hold none. */
    private fun heldStartLock(): Boolean = ShadowPowerManager.getLatestWakeLock()?.isHeld == true

    /** The real [ExoPlayer] interface over a [PlayerProbe], recording what the controller asks for. */
    private class RecordingExoPlayer {
        val probe = PlayerProbe()
        val wakeModes = mutableListOf<Int>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            val onPlayer = runCatching { Player::class.java.getMethod(method.name, *method.parameterTypes) }.getOrNull()
            when {
                method.name == "setWakeMode" -> { wakeModes += args!![0] as Int; null }
                method.name == "getAudioAttributes" -> AudioAttributes.DEFAULT
                method.name == "setAudioAttributes" -> null
                onPlayer != null ->
                    try { onPlayer.invoke(probe.player, *(args ?: emptyArray())) }
                    catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
                else -> throw AssertionError("Unmodeled ExoPlayer call ${method.name}")
            }
        } as ExoPlayer
    }

    private class Fixture(
        overlap: RecordingExoPlayer? = null,
        localPlan: (suspend (String) -> LocalPlaybackPlan?)? = null,
        resolveGate: suspend (String) -> Unit = {},
    ) : AutoCloseable {
        val current = RecordingExoPlayer()
        val prepared = mutableListOf<String>()
        private val context: Context = RuntimeEnvironment.getApplication()
        private val name = "wake-${java.util.UUID.randomUUID()}.db"
        private val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, name).createDriver())
        val controller = AndroidPlaybackController(context,
            PlaybackEndpointAccount("provider", "http://127.0.0.1:4533", "fixture", "fixture", true),
            AndroidPlaybackControllerBoundaries(store, current.player, { prepared += it.itemId.rawId }, { id -> song(id) },
                resolve = { r ->
                    resolveGate(r.itemId.rawId)
                    PlaybackResolutionResult.Resolved(RemotePlaybackWirePlan(r.playbackSessionId, r.attemptId,
                        r.itemId, PlaybackDeliveryPath.Legacy, PlaybackDeliveryProtocol.HttpProgressive, AudioContainer.Wav,
                        PlaybackWireTranscodeDecision.LegacyHint(null, null), endpoint = "stream",
                        parameters = mapOf("id" to r.itemId.rawId), resolutionRequest = r))
                },
                enqueueDelivery = {}, overlapPlayer = overlap?.let { { it.player } },
                localPlans = localPlan?.let { source -> AndroidLocalPlaybackSource { id -> source(id) } },
                prepareLocalSource = { prepared += it.itemId.rawId }))
        fun play(vararg ids: String) {
            controller.playQueue(ids.map { AndroidTrack("provider", it, "Song $it", durationMilliseconds = 40_000) },
                0, AndroidQueueSource.Search, "Search")
        }
        fun end() { current.probe.position = 40_000; current.probe.state = Player.STATE_ENDED; current.probe.events() }
        fun progress(seconds: Int) {
            current.probe.position = 0; current.probe.state = Player.STATE_READY; current.probe.events()
            repeat(seconds * 2) {
                current.probe.position += 500
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            }
        }
        override fun close() { controller.close(); store.close(); context.deleteDatabase(name) }
    }

    private companion object {
        val LOCAL_PLAN = LocalPlaybackPlan(DownloadId("download:down"),
            DownloadIdentity("provider", "down", DownloadIdentity.ORIGINAL_PROFILE), AudioContainer.Wav, 44, "fixture.wav")
        fun song(id: String) = AuthenticatedEndpointResponse(200,
            """{"subsonic-response":{"status":"ok","song":{"id":"$id","title":"Song $id","albumId":"album:wire","duration":40,"suffix":"wav"}}}""".toByteArray(),
            "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
            RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
                emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))
    }
}
