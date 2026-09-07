package com.legitimateapps.dulcet.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** Media3 transport observations are translated into the existing core event vocabulary. */
internal class AndroidMedia3Engine(
    internal val player: Player,
    private val prepareSource: (RemotePlaybackWirePlan) -> Unit,
    private val handler: Handler = Handler(player.applicationLooper),
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) : PlaybackEngine {
    private var listener: PlaybackEngineEventListener? = null
    private var current: RemotePlaybackWirePlan? = null
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
    private val seekability get() = if (player.isCurrentMediaItemSeekable)
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
                terminal = true
                status = PlaybackObservationStatus.Stopped
                emit(PlaybackEngineEvent.EndedNaturally(id, position))
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
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            current?.let { emit(PlaybackEngineEvent.RateChanged(it.attemptId, playbackParameters.speed.toDouble())) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) current?.let {
                emit(PlaybackEngineEvent.SeekCompleted(it.attemptId,
                    oldPosition.positionMs.coerceAtLeast(0).milliseconds,
                    newPosition.positionMs.coerceAtLeast(0).milliseconds))
                // Reset only the adapter witness. The core retains its old delta anchor to discard
                // the seek discontinuity rather than counting a forward jump as played time.
                lastPosition = newPosition.positionMs.coerceAtLeast(0)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val plan = current ?: return
            if (terminal) return
            terminal = true
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
                    val plan = (if (command is PlaybackCommand.Prepare) command.plan
                        else (command as PlaybackCommand.ReplaceCurrent).plan) as? RemotePlaybackWirePlan
                        ?: return rejected(PlaybackCommandRejectionReason.Unsupported)
                    val id = if (command is PlaybackCommand.Prepare) command.attemptId
                        else (command as PlaybackCommand.ReplaceCurrent).attemptId
                    if (id != plan.attemptId || plan.deliveryProtocol != PlaybackDeliveryProtocol.HttpProgressive)
                        return rejected(PlaybackCommandRejectionReason.Unsupported)
                    if (command is PlaybackCommand.Prepare && current != null) return rejected()
                    if (command is PlaybackCommand.ReplaceCurrent &&
                        (current?.playbackSessionId != plan.playbackSessionId || current?.attemptId == plan.attemptId)) return rejected()
                    current?.let { emit(PlaybackEngineEvent.AttemptReplaced(it.attemptId, plan.attemptId)) }
                    current = plan
                    ready = false; began = false; terminal = false; buffering = false; interrupted = false
                    lastPosition = 0; duration = null; startWall = null
                    status = PlaybackObservationStatus.Preparing
                    emit(PlaybackEngineEvent.Preparing(plan.attemptId))
                    prepareSource(plan)
                    player.prepare()
                    return PlaybackCommandOutcome.CommandAccepted(command.commandId)
                }
                is PlaybackCommand.Play -> {
                    if (current == null || terminal) return rejected()
                    player.play()
                    return PlaybackCommandOutcome.CommandAccepted(command.commandId)
                }
                is PlaybackCommand.Pause -> { if (current == null) return rejected(); player.pause() }
                is PlaybackCommand.Stop -> {
                    current?.takeUnless { terminal }?.let { emit(PlaybackEngineEvent.Skipped(it.attemptId, position, PlaybackSkipReason.User)) }
                    current = null
                    player.stop(); player.clearMediaItems()
                    status = PlaybackObservationStatus.Stopped
                    armSampler()
                }
                is PlaybackCommand.Seek -> {
                    if (current == null || !player.isCurrentMediaItemSeekable || command.position.isNegative() ||
                        !command.position.isFinite()) return rejected()
                    player.seekTo(command.position.inWholeMilliseconds)
                }
                is PlaybackCommand.SetVolume -> {
                    if (!command.volume.isFinite() || command.volume !in 0.0..1.0) return rejected()
                    player.volume = command.volume.toFloat()
                }
                is PlaybackCommand.SetRate -> {
                    if (!command.rate.isFinite() || command.rate <= 0 || command.rate > 2)
                        return rejected(PlaybackCommandRejectionReason.Unsupported)
                    player.setPlaybackSpeed(command.rate.toFloat())
                }
                // The core owns advancement. Do not let Media3 autonomously begin an unregistered
                // session. Explicit unsupported is part of the seam's command-outcome contract.
                is PlaybackCommand.PreloadNext -> return rejected(PlaybackCommandRejectionReason.Unsupported)
                is PlaybackCommand.Release -> {
                    current?.let { emit(PlaybackEngineEvent.EngineTornDown(it.attemptId, PlaybackEngineTeardownReason.Released)) }
                    released = true; current = null
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
    }

    private fun emit(event: PlaybackEngineEvent) { listener?.onPlaybackEngineEvent(event) }
}

internal fun sanitizeAndroidPlaybackFailure(error: PlaybackException): DomainError {
    var cause: Throwable? = error
    repeat(12) {
        if (cause is AndroidPlaybackIOException) return (cause as AndroidPlaybackIOException).error
        cause = cause?.cause
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> DomainError.Transport.Timeout
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> DomainError.Protocol.UnexpectedBinary
        else -> DomainError.Transport.Unreachable
    }
}
