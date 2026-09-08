package com.legitimateapps.dulcet.core

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidMedia3EngineTest {
    @Test fun seamCommandsChangeTheRealMedia3Player() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val exo = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        val engine: PlaybackEngine = AndroidMedia3Engine(exo, prepareSource = {})
        try {
            assertIs<PlaybackCommandOutcome.CommandCompleted>(engine.execute(PlaybackCommand.SetVolume(commandId(), 0.25)))
            assertEquals(0.25f, exo.volume)
            assertIs<PlaybackCommandOutcome.CommandCompleted>(engine.execute(PlaybackCommand.SetRate(commandId(), 1.5)))
            assertEquals(1.5f, exo.playbackParameters.speed)
            assertIs<PlaybackCommandOutcome.CommandRejected>(engine.execute(PlaybackCommand.Play(commandId())))
            assertIs<PlaybackCommandOutcome.CommandRejected>(engine.execute(PlaybackCommand.SetVolume(commandId(), Double.NaN)))
            println("ANDROID MEDIA3 OBSERVED seam=PlaybackEngine real-player=ExoPlayer volume=0.25 rate=1.5 invalid-play=rejected decoder-executed=false")
        } finally {
            engine.execute(PlaybackCommand.Release(commandId()))
            assertIs<PlaybackCommandOutcome.CommandRejected>(engine.execute(PlaybackCommand.Play(commandId())))
            Dispatchers.resetMain()
        }
    }

    @Test fun periodicSamplerDrivesCoreThresholdAndExcludesPauseBufferingAndSeek() {
        val fake = PlayerProbe()
        val core = PlaybackCoreStateMachine()
        val plan = playbackPlan()
        core.startPlaying(PlaybackSessionStart(QueueEntryId("queue:opaque"), plan.playbackSessionId,
            plan.attemptId, plan.itemId, 40.seconds))
        val events = mutableListOf<PlaybackEngineEvent>()
        val effects = mutableListOf<PlaybackCoreEffect>()
        val engine = AndroidMedia3Engine(fake.player, prepareSource = { assertSame(plan, it) })
        engine.setEventListener { events += it; effects += core.recordPlaybackEvent(it).effects }
        assertIs<PlaybackCommandOutcome.CommandAccepted>(engine.executeOnPlayerThread(PlaybackCommand.Prepare(commandId(), plan.attemptId, plan)))
        engine.executeOnPlayerThread(PlaybackCommand.Play(commandId()))
        fake.state = Player.STATE_READY
        fake.events()
        // isPlaying without position advancement is not progress.
        advance(1000)
        assertTrue(events.none { it is PlaybackEngineEvent.PlaybackProgressBegan })
        fun progress(ticks: Int) { repeat(ticks) { fake.position += 500; advance(500) } }
        progress(10)
        assertEquals(1, events.count { it is PlaybackEngineEvent.PlaybackProgressBegan })
        val beforePause = core.currentSession!!.accumulator.accruedMediaTime
        engine.executeOnPlayerThread(PlaybackCommand.Pause(commandId()))
        fake.position += 10_000
        advance(10_000)
        assertEquals(beforePause, core.currentSession!!.accumulator.accruedMediaTime)
        assertTrue(events.any { it is PlaybackEngineEvent.Paused })
        engine.executeOnPlayerThread(PlaybackCommand.Play(commandId()))
        fake.state = Player.STATE_BUFFERING; fake.events()
        advance(10_000)
        assertEquals(beforePause, core.currentSession!!.accumulator.accruedMediaTime)
        assertTrue(events.any { it is PlaybackEngineEvent.Buffering })
        fake.state = Player.STATE_READY; fake.events()
        progress(2)
        val beforeSeek = core.currentSession!!.accumulator.accruedMediaTime
        val seekFrom = fake.position
        val seekTo = seekFrom + 120_000
        assertIs<PlaybackCommandOutcome.CommandCompleted>(engine.executeOnPlayerThread(
            PlaybackCommand.Seek(commandId(), seekTo.milliseconds)))
        assertEquals(listOf(seekTo), fake.seekCommands)
        val seekEvent = events.filterIsInstance<PlaybackEngineEvent.SeekCompleted>().single()
        assertEquals(plan.attemptId, seekEvent.attemptId)
        assertEquals(seekFrom.milliseconds, seekEvent.from)
        assertEquals(seekTo.milliseconds, seekEvent.to)
        // A stationary post-seek sample must not manufacture the first progression observation.
        fake.adjustSeek(seekTo + 250)
        val adjustment = events.filterIsInstance<PlaybackEngineEvent.SeekCompleted>().last()
        assertEquals(2, events.filterIsInstance<PlaybackEngineEvent.SeekCompleted>().size)
        assertEquals(seekTo.milliseconds, adjustment.from)
        assertEquals((seekTo + 250).milliseconds, adjustment.to)
        fake.position += 500
        advance(500)
        assertEquals(beforeSeek + 500.milliseconds, core.currentSession!!.accumulator.accruedMediaTime)
        assertTrue(core.diagnostics.discontinuityCount > 0)
        progress(45)
        val records = effects.filterIsInstance<PlaybackCoreEffect.RecordPlaybackEvent>().map { it.event }
        assertEquals(1, records.filterIsInstance<RecordedPlaybackEvent.NowPlaying>().size)
        assertEquals(1, records.filterIsInstance<RecordedPlaybackEvent.SubmittedPlay>().size)
        assertTrue(core.currentSession!!.accumulator.accruedMediaTime >= 20.seconds)
        engine.executeOnPlayerThread(PlaybackCommand.Stop(commandId()))
        assertEquals(1, effects.filterIsInstance<PlaybackCoreEffect.RecordPlaybackEvent>()
            .count { it.event is RecordedPlaybackEvent.SubmittedPlay })
        engine.executeOnPlayerThread(PlaybackCommand.Release(commandId()))
        val eventCount = events.size
        advance(5000)
        assertEquals(eventCount, events.size)
        println("ANDROID SAMPLER OBSERVED trigger=periodic-handler simulated-position=true core-submissions=1 pause=encountered buffering=encountered seek-discarded=true server-submission=unmeasured")
    }

    @Test fun smallForwardSeekExcludesTheJumpButCreditsSubsequentProgress() {
        val probe = PlayerProbe()
        val plan = playbackPlan()
        val core = PlaybackCoreStateMachine()
        core.startPlaying(PlaybackSessionStart(QueueEntryId("queue:small-seek"), plan.playbackSessionId,
            plan.attemptId, plan.itemId, 100.seconds))
        val engine = AndroidMedia3Engine(probe.player, prepareSource = {})
        engine.setEventListener { core.recordPlaybackEvent(it) }
        try {
            engine.executeOnPlayerThread(PlaybackCommand.Prepare(commandId(), plan.attemptId, plan))
            engine.executeOnPlayerThread(PlaybackCommand.Play(commandId()))
            probe.state = Player.STATE_READY; probe.events()
            repeat(20) { probe.position += 500; advance(500) }
            assertEquals(10000L, probe.position)
            val before = core.currentSession!!.accumulator.accruedMediaTime
            engine.executeOnPlayerThread(PlaybackCommand.Seek(commandId(), 13.seconds))
            probe.position += 500; advance(500)
            assertEquals(before + 500.milliseconds, core.currentSession!!.accumulator.accruedMediaTime)
        } finally { engine.executeOnPlayerThread(PlaybackCommand.Release(commandId())) }
    }

    @Test fun replacementKeepsSessionAndRejectsMismatchedAttemptAndOtherSession() {
        val fake = PlayerProbe()
        val engine = AndroidMedia3Engine(fake.player, prepareSource = {})
        val events = mutableListOf<PlaybackEngineEvent>()
        val first = playbackPlan()
        val core = PlaybackCoreStateMachine()
        val entry = QueueEntryId("queue:distinct-from-session-and-attempt")
        core.startPlaying(PlaybackSessionStart(entry, first.playbackSessionId, first.attemptId, first.itemId, 40.seconds))
        engine.setEventListener { events += it; core.recordPlaybackEvent(it) }
        engine.executeOnPlayerThread(PlaybackCommand.Prepare(commandId(), first.attemptId, first))
        val second = playbackPlan(attempt = "attempt:second")
        assertIs<PlaybackCommandOutcome.CommandRejected>(engine.executeOnPlayerThread(
            PlaybackCommand.ReplaceCurrent(commandId(), first.attemptId, second)))
        assertIs<PlaybackCommandOutcome.CommandAccepted>(engine.executeOnPlayerThread(
            PlaybackCommand.ReplaceCurrent(commandId(), second.attemptId, second)))
        val replacement = events.filterIsInstance<PlaybackEngineEvent.AttemptReplaced>().single()
        assertEquals(first.attemptId, replacement.oldAttemptId)
        assertEquals(second.attemptId, replacement.newAttemptId)
        assertEquals(entry, core.currentSession!!.queueEntryId)
        assertEquals(first.playbackSessionId, core.currentSession!!.playbackSessionId)
        assertEquals(second.attemptId, core.currentSession!!.currentAttempt.attemptId)
        val other = playbackPlan(session = "session:other", attempt = "attempt:other")
        assertIs<PlaybackCommandOutcome.CommandRejected>(engine.executeOnPlayerThread(
            PlaybackCommand.ReplaceCurrent(commandId(), other.attemptId, other)))
        engine.executeOnPlayerThread(PlaybackCommand.Release(commandId()))
    }

    @Test fun platformErrorAndNestedCredentialCanariesNeverReachTheCoreEvent() {
        val fake = PlayerProbe()
        val engine = AndroidMedia3Engine(fake.player, prepareSource = {})
        val events = mutableListOf<PlaybackEngineEvent>()
        engine.setEventListener { events += it }
        val plan = playbackPlan()
        engine.executeOnPlayerThread(PlaybackCommand.Prepare(commandId(), plan.attemptId, plan))
        val canaries = listOf("USER_CANARY", "TOKEN_CANARY", "SALT_CANARY", "PASSWORD_CANARY")
        fake.listener!!.onPlayerError(PlaybackException(
            "https://example.invalid/rest/stream?u=${canaries[0]}&t=${canaries[1]}&s=${canaries[2]}",
            IllegalStateException(canaries[3]), PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertEquals(1, events.filterIsInstance<PlaybackEngineEvent.FailedBeforeStart>().size)
        for (canary in canaries) assertFalse(events.joinToString().contains(canary))
        engine.executeOnPlayerThread(PlaybackCommand.Release(commandId()))
    }

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(millis))
}

internal fun commandId() = PlaybackCommandId(java.util.UUID.randomUUID().toString())
internal fun playbackPlan(session: String = "session:opaque", attempt: String = "attempt:opaque", container: AudioContainer = AudioContainer.Wav): RemotePlaybackWirePlan {
    val request = PlaybackResolveRequest(PlaybackSessionId(session), AttemptId(attempt), ProviderItemId("provider:opaque", "song:opaque"),
        container, false, PlaybackDeviceProfile("Dulcet", "Android", 320000, 320000,
            listOf(DirectPlayAudioProfile(listOf(container), listOf("pcm"), maxAudioChannels = 2)),
            listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2))), LegacyPlaybackPreference(null, null))
    return RemotePlaybackWirePlan(request.playbackSessionId, request.attemptId, request.itemId, PlaybackDeliveryPath.Legacy,
        PlaybackDeliveryProtocol.HttpProgressive, container, PlaybackWireTranscodeDecision.LegacyHint(null, null),
        endpoint = "stream", parameters = mapOf("id" to request.itemId.rawId), resolutionRequest = request)
}

internal class PlayerProbe {
    val seekCommands = mutableListOf<Long>()
    var listener: Player.Listener? = null
    var state = Player.STATE_IDLE
    var position = 0L
    var requested = false
    val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
        when (method.name) {
            "getApplicationLooper" -> Looper.getMainLooper()
            "addListener" -> { listener = args!![0] as Player.Listener; null }
            "removeListener" -> { listener = null; null }
            "getPlaybackState" -> state
            "getCurrentPosition" -> position
            "getDuration" -> 40_000L
            "getPlayWhenReady" -> requested
            "isPlaying" -> requested && state == Player.STATE_READY
            "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
            "isCurrentMediaItemSeekable" -> true
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "play", "pause" -> { requested = method.name == "play"
                listener?.onPlayWhenReadyChanged(requested, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST); null }
            "seekTo" -> {
                val target = args!![0] as Long
                seekCommands += target
                discontinuity(target, Player.DISCONTINUITY_REASON_SEEK)
                null
            }
            "prepare", "stop", "clearMediaItems", "release" -> null
            "toString" -> "PlayerProbe"
            else -> throw AssertionError("Unmodeled Player call ${method.name}")
        }
    } as Player
    fun adjustSeek(target: Long) = discontinuity(target, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
    private fun discontinuity(target: Long, reason: Int) {
        fun info(value: Long) = Player.PositionInfo(null, 0, null, null, 0, value, value, C.INDEX_UNSET, C.INDEX_UNSET)
        val from = position
        position = target
        listener!!.onPositionDiscontinuity(info(from), info(target), reason)
    }
    fun events() { listener!!.onEvents(player, Player.Events(FlagSet.Builder().add(Player.EVENT_PLAYBACK_STATE_CHANGED).build())) }
}
