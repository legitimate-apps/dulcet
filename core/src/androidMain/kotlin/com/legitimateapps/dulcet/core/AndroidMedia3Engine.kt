package com.legitimateapps.dulcet.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** Media3 transport observations are translated into the existing core event vocabulary. */
internal class AndroidMedia3Engine(
    player: Player,
    /** Sets the player's source for a streamed or a downloaded plan; the engine prepares it. */
    private val prepareSource: (PlaybackPlan) -> Unit,
    private val handler: Handler = Handler(player.applicationLooper),
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    /**
     * Told when the platform, not the user, changes the play intent: a headphone unplug, a
     * permanent audio-focus loss, a remote controller. The owner's own requested state must follow,
     * or its Pause button and its next preparation would still assume playback was wanted.
     */
    private val onSystemPlayWhenReady: (Boolean) -> Unit = {},
    private val createOverlapPlayer: (() -> Player)? = null,
    private val prepareOverlapSource: ((PlaybackPlan, Player) -> Unit)? = null,
    private val onPlayerChanged: (Player, PlaybackPlan) -> Unit = { _, _ -> },
    private val onPreloadFailed: (AttemptId, PlaybackPlan, DomainError) -> Unit = { _, _, _ -> },
) : PlaybackEngine {
    internal var player: Player = player
        private set
    internal var isAdvancingOverlap = false
        private set
    private var overlapMilliseconds = 0L
    private var preloaded: Pair<PlaybackPlan, Player>? = null
    private var tail: Player? = null
    private var fadeStarted = 0L
    private var fadeLength = 0L
    private var sleepGain = 1.0
    private var userVolume = 1.0
    // Smooth the overlap independently of the slower core progress/reporting sampler.
    private val fader = object : Runnable {
        override fun run() {
            if (tail == null || released) return
            applyGains()
            if (tail != null) handler.postDelayed(this, 50)
        }
    }
    private val preloadCallbacks = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val plan = preloaded?.first ?: return
            discardPreload()
            onPreloadFailed(plan.attemptId, plan, sanitizeAndroidPlaybackFailure(error))
        }
    }
    internal fun setCrossfade(milliseconds: Long) { overlapMilliseconds = milliseconds.coerceIn(0, 12_000) }
    internal fun setSleepGain(gain: Double) { sleepGain = gain.coerceIn(0.0, 1.0); applyGains() }
    private fun applyGains() {
        val fraction = if (tail == null || fadeLength == 0L) 1.0 else
            ((monotonicMillis() - fadeStarted).toDouble() / fadeLength).coerceIn(0.0, 1.0)
        player.volume = (userVolume * sleepGain * fraction).toFloat()
        tail?.volume = (userVolume * sleepGain * (1.0 - fraction)).toFloat()
        if (fraction >= 1.0) releaseTail()
    }
    private fun releaseTail() { handler.removeCallbacks(fader); tail?.release(); tail = null }
    private fun discardPreload() {
        preloaded?.second?.let { it.removeListener(preloadCallbacks); it.release() }
        preloaded = null
    }
    /** Only a nonzero core boundary may advance early. The tail has no reporting listener. */
    private fun advanceOverlap(): Boolean {
        val incoming = preloaded ?: return false
        if (overlapMilliseconds == 0L || incoming.second.playbackState != Player.STATE_READY) return false
        val outgoing = current ?: return false
        val oldPosition = position
        terminal = true
        player.removeListener(callbacks)
        releaseTail()
        tail = player
        preloaded = null
        incoming.second.removeListener(preloadCallbacks)
        // End while the outgoing attempt is current; advance under the registered incoming identity.
        isAdvancingOverlap = true
        emit(PlaybackEngineEvent.EndedNaturally(outgoing.attemptId, oldPosition))
        player = incoming.second
        current = incoming.first
        ready = false; began = false; terminal = false; buffering = false; interrupted = false
        lastPosition = 0; duration = null; startWall = null
        fadeStarted = monotonicMillis()
        fadeLength = overlapMilliseconds
        // One focus owner: the detached tail ignores focus, the incoming current player owns it.
        (tail as? androidx.media3.exoplayer.ExoPlayer)?.let { it.setAudioAttributes(it.audioAttributes, false) }
        (player as? androidx.media3.exoplayer.ExoPlayer)?.let { it.setAudioAttributes(it.audioAttributes, true) }
        onPlayerChanged(player, incoming.first)
        emit(PlaybackEngineEvent.AdvancedToPreloaded(outgoing.attemptId, incoming.first.attemptId))
        isAdvancingOverlap = false
        player.addListener(callbacks)
        status = PlaybackObservationStatus.Preparing
        emit(PlaybackEngineEvent.Preparing(incoming.first.attemptId))
        callbacks.onEvents(player, Player.Events(androidx.media3.common.FlagSet.Builder()
            .add(Player.EVENT_PLAYBACK_STATE_CHANGED).build()))
        applyGains()
        handler.postDelayed(fader, 50)
        player.play()
        return true
    }
    private var listener: PlaybackEngineEventListener? = null
    private var current: PlaybackPlan? = null
    private val PlaybackPlan.attemptId: AttemptId get() = checkNotNull(androidAttemptId)
    private val PlaybackPlan.playbackSessionId: PlaybackSessionId get() = checkNotNull(androidSessionId)
    private var released = false
    private var ready = false
    private var began = false
    private var terminal = false
    private var buffering = false
    private var interrupted = false
    private var lastPosition = 0L
    private var duration: Long? = null
    private var startWall: PlaybackWallClockTime? = null
    private var sampling = false
    private var status = PlaybackObservationStatus.Stopped
    private val position get() = player.currentPosition.coerceAtLeast(0).milliseconds
    /**
     * A transcoded stream is never seekable here, whatever the extractor reports: seeking would ask
     * for a byte range of a transcode the server does not serve by range (the data source rejects
     * the 200 it answers with), and server-offset seeking is not built. Direct streams follow Media3.
     */
    internal val seekability get() = if ((current as? RemotePlaybackWirePlan)?.isTranscoded() != true &&
        player.isCurrentMediaItemSeekable)
        PlaybackSeekability.Seekable else PlaybackSeekability.NotSeekable

    private val sampler = object : Runnable {
        override fun run() {
            sampling = false
            sample()
            armSampler()
        }
    }

    private val callbacks = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (current == null || terminal || released) return
            val id = current!!.attemptId
            val length = player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
            if (player.playbackState == Player.STATE_READY) {
                if (!ready) {
                    ready = true
                    status = PlaybackObservationStatus.Ready
                    duration = length
                    emit(PlaybackEngineEvent.Ready(id, length?.milliseconds, seekability))
                } else if (length != null && length != duration) {
                    duration = length
                    emit(PlaybackEngineEvent.DurationChanged(id, length.milliseconds))
                }
            }
            val nowBuffering = player.playbackState == Player.STATE_BUFFERING
            if (nowBuffering != buffering) {
                buffering = nowBuffering
                emit(if (buffering) PlaybackEngineEvent.Buffering(id, position)
                    else PlaybackEngineEvent.BufferingEnded(id, position))
                if (buffering) status = PlaybackObservationStatus.Buffering
                lastPosition = player.currentPosition.coerceAtLeast(0)
            }
            val suppressed = player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
            if (suppressed != interrupted) {
                interrupted = suppressed
                emit(if (suppressed) PlaybackEngineEvent.InterruptionBegan(id, player.playWhenReady)
                    else PlaybackEngineEvent.InterruptionEnded(id, player.playWhenReady))
                lastPosition = player.currentPosition.coerceAtLeast(0)
            }
            if (player.playbackState == Player.STATE_ENDED) {
                if (!advanceOverlap()) {
                    releaseTail()
                    terminal = true
                    status = PlaybackObservationStatus.Stopped
                    emit(PlaybackEngineEvent.EndedNaturally(id, position))
                }
            }
            armSampler()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val id = current?.attemptId ?: return
            if (terminal || released) return
            lastPosition = player.currentPosition.coerceAtLeast(0)
            status = if (playWhenReady) PlaybackObservationStatus.Ready else PlaybackObservationStatus.Paused
            emit(if (playWhenReady) PlaybackEngineEvent.Resumed(id, position)
                else PlaybackEngineEvent.Paused(id, position))
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                emit(PlaybackEngineEvent.RouteChanged(id, PlaybackRouteKind.Unknown, PlaybackRouteKind.Unknown, true))
            armSampler()
            if (!playWhenReady) tail?.pause()
            else tail?.play()
            if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) onSystemPlayWhenReady(playWhenReady)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            current?.let { emit(PlaybackEngineEvent.RateChanged(it.attemptId, playbackParameters.speed.toDouble())) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // A seek adjustment is the engine completing the same user operation at an actual
            // decodable position. Report its old/new positions too; never count the snap as time played.
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) current?.let {
                emit(PlaybackEngineEvent.SeekCompleted(it.attemptId,
                    oldPosition.positionMs.coerceAtLeast(0).milliseconds,
                    newPosition.positionMs.coerceAtLeast(0).milliseconds))
                // SeekCompleted resets the core accumulator's anchor to the destination (§15.2), so
                // only progress after the destination is credited; the adapter's own witness
                // follows so the next sample compares against the destination as well.
                lastPosition = newPosition.positionMs.coerceAtLeast(0)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val plan = current ?: return
            if (terminal) return
            terminal = true
            releaseTail()
            status = PlaybackObservationStatus.Failed
            val failure = sanitizeAndroidPlaybackFailure(error)
            emit(if (began) PlaybackEngineEvent.FailedAfterPartial(plan.attemptId, position, failure)
                else PlaybackEngineEvent.FailedBeforeStart(plan.attemptId, failure))
            if (failure == DomainError.Auth.InvalidCredentials)
                emit(PlaybackEngineEvent.SourceRefreshRequired(plan.attemptId, PlaybackSourceRefreshReason.Unauthorized))
            armSampler()
        }
    }

    init {
        check(Looper.myLooper() == player.applicationLooper)
        player.addListener(callbacks)
    }

    override fun setEventListener(listener: PlaybackEngineEventListener) {
        check(Looper.myLooper() == player.applicationLooper)
        this.listener = listener
        current?.let {
            emit(PlaybackEngineEvent.ObservationResynced(it.attemptId, PlaybackObservationSnapshot(
                status, position, duration?.milliseconds, seekability,
                player.playbackParameters.speed.toDouble(), startWall,
            )))
        }
    }

    override suspend fun execute(command: PlaybackCommand): PlaybackCommandOutcome = withContext(Dispatchers.Main.immediate) {
        executeOnPlayerThread(command)
    }

    internal fun executeOnPlayerThread(command: PlaybackCommand): PlaybackCommandOutcome {
        check(Looper.myLooper() == player.applicationLooper)
        fun rejected(reason: PlaybackCommandRejectionReason = PlaybackCommandRejectionReason.InvalidState) =
            PlaybackCommandOutcome.CommandRejected(command.commandId, reason)
        if (released) return rejected(PlaybackCommandRejectionReason.EngineReleased)
        return try {
            when (command) {
                is PlaybackCommand.Prepare, is PlaybackCommand.ReplaceCurrent -> {
                    // A streamed plan over HTTP, or a downloaded one read from its promoted file.
                    val plan = (if (command is PlaybackCommand.Prepare) command.plan
                        else (command as PlaybackCommand.ReplaceCurrent).plan)
                        .takeIf { it is AndroidLocalPlaybackPlan ||
                            (it is RemotePlaybackWirePlan && it.deliveryProtocol == PlaybackDeliveryProtocol.HttpProgressive) }
                        ?: return rejected(PlaybackCommandRejectionReason.Unsupported)
                    val id = if (command is PlaybackCommand.Prepare) command.attemptId
                        else (command as PlaybackCommand.ReplaceCurrent).attemptId
                    if (id != plan.attemptId) return rejected(PlaybackCommandRejectionReason.Unsupported)
                    if (command is PlaybackCommand.Prepare && current != null) return rejected()
                    if (command is PlaybackCommand.ReplaceCurrent &&
                        (current?.playbackSessionId != plan.playbackSessionId || current?.attemptId == plan.attemptId)) return rejected()
                    current?.let { emit(PlaybackEngineEvent.AttemptReplaced(it.attemptId, plan.attemptId)) }
                    current = plan
                    ready = false; began = false; terminal = false; buffering = false; interrupted = false
                    lastPosition = 0; duration = null; startWall = null
                    status = PlaybackObservationStatus.Preparing
                    emit(PlaybackEngineEvent.Preparing(plan.attemptId))
                    // A fresh preparation starts paused; the controller applies current intent.
                    // Replacement keeps the current session and its transport intent.
                    if (command is PlaybackCommand.Prepare) player.pause()
                    prepareSource(plan)
                    player.prepare()
                    return PlaybackCommandOutcome.CommandAccepted(command.commandId)
                }
                is PlaybackCommand.Play -> {
                    if (current == null || terminal) return rejected()
                    player.play()
                    tail?.play()
                    return PlaybackCommandOutcome.CommandAccepted(command.commandId)
                }
                is PlaybackCommand.Pause -> { if (current == null) return rejected(); player.pause(); tail?.pause() }
                is PlaybackCommand.Stop -> {
                    current?.takeUnless { terminal }?.let { emit(PlaybackEngineEvent.Skipped(it.attemptId, position, PlaybackSkipReason.User)) }
                    current = null
                    discardPreload(); releaseTail()
                    player.pause(); player.stop(); player.clearMediaItems()
                    status = PlaybackObservationStatus.Stopped
                    armSampler()
                }
                is PlaybackCommand.Seek -> {
                    if (current == null || seekability != PlaybackSeekability.Seekable || command.position.isNegative() ||
                        !command.position.isFinite()) return rejected()
                    releaseTail()
                    player.seekTo(command.position.inWholeMilliseconds)
                    applyGains()
                }
                is PlaybackCommand.SetVolume -> {
                    if (!command.volume.isFinite() || command.volume !in 0.0..1.0) return rejected()
                    userVolume = command.volume
                    applyGains()
                }
                is PlaybackCommand.SetRate -> {
                    if (!command.rate.isFinite() || command.rate <= 0 || command.rate > 2)
                        return rejected(PlaybackCommandRejectionReason.Unsupported)
                    player.setPlaybackSpeed(command.rate.toFloat())
                }
                is PlaybackCommand.PreloadNext -> {
                    if (createOverlapPlayer == null || prepareOverlapSource == null || overlapMilliseconds == 0L)
                        return rejected(PlaybackCommandRejectionReason.Unsupported)
                    if (command.plan.androidAttemptId != command.attemptId) return rejected()
                    discardPreload()
                    val incoming = createOverlapPlayer.invoke()
                    preloaded = command.plan to incoming
                    incoming.volume = 0f
                    incoming.addListener(preloadCallbacks)
                    prepareOverlapSource.invoke(command.plan, incoming)
                    incoming.prepare()
                    return PlaybackCommandOutcome.CommandAccepted(command.commandId)
                }
                is PlaybackCommand.DiscardPreloaded -> {
                    if (preloaded?.first?.androidAttemptId == command.attemptId) discardPreload()
                }
                is PlaybackCommand.Release -> {
                    current?.let { emit(PlaybackEngineEvent.EngineTornDown(it.attemptId, PlaybackEngineTeardownReason.Released)) }
                    released = true; current = null
                    discardPreload(); releaseTail()
                    handler.removeCallbacks(sampler); sampling = false
                    player.removeListener(callbacks); player.release()
                }
            }
            PlaybackCommandOutcome.CommandCompleted(command.commandId, PlaybackCommandCompletedWithoutData)
        } catch (_: Exception) {
            rejected(PlaybackCommandRejectionReason.Failed(DomainError.Transport.Unreachable))
        }
    }

    private fun armSampler() {
        val active = current != null && !terminal && !released && player.isPlaying && !interrupted
        if (!active) { handler.removeCallbacks(sampler); sampling = false }
        else if (!sampling) { sampling = true; handler.postDelayed(sampler, 500) }
    }

    internal fun sample() {
        val plan = current ?: return
        if (released || terminal || !ready || !player.isPlaying || interrupted) return
        if (tail != null) applyGains()
        val now = player.currentPosition.coerceAtLeast(0)
        if (now > lastPosition) {
            if (!began) {
                began = true
                startWall = PlaybackWallClockTime(wallMillis())
                emit(PlaybackEngineEvent.PlaybackProgressBegan(plan.attemptId, startWall!!, now.milliseconds))
            }
            status = PlaybackObservationStatus.Progressing
            emit(PlaybackEngineEvent.PositionChanged(plan.attemptId, now.milliseconds,
                PlaybackMonotonicTime(monotonicMillis().milliseconds)))
        }
        lastPosition = now
        val length = duration
        if (overlapMilliseconds > 0 && length != null && now >= length - overlapMilliseconds) advanceOverlap()
    }

    private fun emit(event: PlaybackEngineEvent) { listener?.onPlaybackEngineEvent(event) }
}

internal fun sanitizeAndroidPlaybackFailure(error: PlaybackException): DomainError {
    var cause: Throwable? = error
    repeat(12) {
        if (cause is AndroidPlaybackIOException) return cause.error
        cause = cause?.cause
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> DomainError.Transport.Timeout
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> DomainError.Protocol.UnexpectedBinary
        // The bytes arrived and validated, and the item itself could not be decoded: the track's
        // own failure, as AVFoundation's decode failures are on Apple (spec §12.2, §12.12).
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> DomainError.Playback.NoPlayableSource
        else -> DomainError.Transport.Unreachable
    }
}
