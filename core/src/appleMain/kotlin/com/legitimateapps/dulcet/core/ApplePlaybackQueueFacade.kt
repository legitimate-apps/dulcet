package com.legitimateapps.dulcet.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID
import platform.posix.time
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

public class ApplePlaybackQueueItemDto(
    public val providerInstanceId: String,
    public val rawId: String,
    /** Negative means unknown. */
    public val durationMilliseconds: Long,
)

public class ApplePlaybackQueueRequestDto(
    public val items: List<ApplePlaybackQueueItemDto>,
    /** `album`, `playlist`, `search`, or `artist`. */
    public val sourceKind: String,
    public val sourceRawId: String?,
    public val sourceDisplayName: String,
    /** Negative means the head after optional shuffling. */
    public val startIndex: Int,
    public val shuffle: Boolean,
)

public class ApplePlaybackQueueEntryDto internal constructor(
    public val queueEntryId: String,
    public val providerInstanceId: String,
    public val rawId: String,
)

public class ApplePlaybackCoreSessionDto internal constructor(
    public val queueEntryId: String,
    public val playbackSessionId: String,
    public val attemptId: String,
    public val providerInstanceId: String,
    public val rawId: String,
    public val phase: String,
    /** Negative means unknown. */
    public val positionMilliseconds: Long,
    /** Negative means unknown. */
    public val durationMilliseconds: Long,
    public val seekability: String,
    public val rate: Double,
)

public class ApplePlaybackQueueSnapshotDto internal constructor(
    public val entries: List<ApplePlaybackQueueEntryDto>,
    /** Negative means no current entry. */
    public val currentIndex: Int,
    /** `off`, `all`, or `one`. */
    public val repeatMode: String,
    public val shuffleEnabled: Boolean,
    public val currentSession: ApplePlaybackCoreSessionDto?,
)

public class ApplePlaybackStartDirectiveDto internal constructor(
    public val queueEntryId: String,
    public val playbackSessionId: String,
    public val attemptId: String,
    public val providerInstanceId: String,
    public val rawId: String,
    /** Negative means unknown. */
    public val durationMilliseconds: Long,
    /** Negative means no saved position. */
    public val resumePositionMilliseconds: Long,
)

public class ApplePlaybackQueueTransitionDto internal constructor(
    public val snapshot: ApplePlaybackQueueSnapshotDto?,
    public val startDirective: ApplePlaybackStartDirectiveDto?,
    /** `input`, `persistence`, or null. */
    public val errorKind: String?,
)

public class ApplePlaybackDeliveryConfigurationOutcomeDto internal constructor(
    public val configured: Boolean,
    /** `input`, `persistence`, or null. */
    public val errorKind: String?,
)

/**
 * Objective-C-compatible queue facade. Its owner serializes calls; every public operation closes
 * failures into [ApplePlaybackQueueTransitionDto] instead of allowing a Kotlin exception to cross.
 */
public class ApplePlaybackQueueClient private constructor(
    compositionResult: ApplePlaybackQueueCompositionResult,
) {
    private val databaseStore: DulcetDatabaseStore?
    private val database: com.legitimateapps.dulcet.database.DulcetDatabase?
    private val controller: PlaybackQueueController?
    private val resumePositions: ResumePositionStore?
    private val initializationErrorKind: String?
    private val pendingEffects = ArrayDeque<PlaybackCoreEffect>()
    private val deliveryScope = MainScope()
    private val deliveryEvents = Channel<RecordedPlaybackEvent>(Channel.UNLIMITED)
    private var delivery: ApplePlaybackDeliveryComposition? = null

    init {
        databaseStore = compositionResult.composition?.databaseStore
        database = compositionResult.composition?.database
        controller = compositionResult.composition?.controller
        resumePositions = compositionResult.composition?.resumePositions
        initializationErrorKind = compositionResult.errorKind
        deliveryScope.launch {
            for (event in deliveryEvents) deliverNetworkEvent(event)
        }
    }

    public constructor(databaseName: String) : this(openApplePlaybackQueue(databaseName))

    internal constructor(controller: PlaybackQueueController) : this(
        ApplePlaybackQueueCompositionResult(
            composition = ApplePlaybackQueueComposition(null, null, controller, null),
            errorKind = null,
        ),
    )

    internal constructor(
        database: com.legitimateapps.dulcet.database.DulcetDatabase,
        controller: PlaybackQueueController,
        resumePositions: ResumePositionStore,
    ) : this(
        ApplePlaybackQueueCompositionResult(
            composition = ApplePlaybackQueueComposition(
                databaseStore = null,
                database = database,
                controller = controller,
                resumePositions = resumePositions,
            ),
            errorKind = null,
        ),
    )

    public fun configureDelivery(
        account: PlaybackEndpointAccount,
    ): ApplePlaybackDeliveryConfigurationOutcomeDto {
        val database = database
            ?: return ApplePlaybackDeliveryConfigurationOutcomeDto(false, "persistence")
        return try {
            delivery?.sender?.close()
            val sender = ScrobbleEndpointSender(account)
            val outbox = PersistentScrobbleOutbox(database, ApplePlaybackWallClock)
            val monotonicOrigin = TimeSource.Monotonic.markNow()
            val worker = ScrobbleOutboxDeliveryWorker(
                serverId = ServerId(account.providerInstanceId),
                outbox = outbox,
                sender = sender,
                wallClock = ApplePlaybackWallClock,
                monotonicClock = OutboxMonotonicClock { monotonicOrigin.elapsedNow() },
                diagnosticSink = ScrobbleOutboxDiagnosticSink { },
            )
            delivery = ApplePlaybackDeliveryComposition(sender, outbox, worker)
            val waiting = pendingEffects.toList()
            pendingEffects.clear()
            captureEffects(waiting)
            deliveryScope.launch { worker.onForeground() }
            ApplePlaybackDeliveryConfigurationOutcomeDto(true, null)
        } catch (_: IllegalArgumentException) {
            ApplePlaybackDeliveryConfigurationOutcomeDto(false, "input")
        } catch (_: Throwable) {
            ApplePlaybackDeliveryConfigurationOutcomeDto(false, "persistence")
        }
    }

    public fun replaceAndStart(
        request: ApplePlaybackQueueRequestDto,
    ): ApplePlaybackQueueTransitionDto = runClosed {
        val first = request.items.firstOrNull()
            ?: throw IllegalArgumentException("A playback queue cannot be empty")
        val sourceKind = request.sourceKind.toQueueSourceKind()
        val providerInstanceId = first.providerInstanceId
        val sourceId = request.sourceRawId?.let {
            ProviderItemId(providerInstanceId, it)
        }
        require(request.items.all { it.providerInstanceId == providerInstanceId })
        controllerOrThrow().replaceAndStart(
            PlaybackQueueRequest(
                items = request.items.map { item ->
                    PlaybackQueueItem(
                        itemId = ProviderItemId(item.providerInstanceId, item.rawId),
                        duration = item.durationMilliseconds
                            .takeIf { it >= 0 }
                            ?.milliseconds,
                    )
                },
                sourceContext = QueueSourceContext(
                    kind = sourceKind,
                    sourceId = sourceId,
                    displayName = request.sourceDisplayName,
                ),
                startIndex = request.startIndex.takeIf { it >= 0 },
                shuffle = request.shuffle,
            ),
        )
    }

    public fun next(): ApplePlaybackQueueTransitionDto = runClosed {
        controllerOrThrow().next()
    }

    public fun previous(): ApplePlaybackQueueTransitionDto = runClosed {
        controllerOrThrow().previous()
    }

    public fun nextForSession(playbackSessionId: String): ApplePlaybackQueueTransitionDto =
        runClosed {
            controllerOrThrow().nextForSession(PlaybackSessionId(playbackSessionId))
        }

    public fun previousForSession(playbackSessionId: String): ApplePlaybackQueueTransitionDto =
        runClosed {
            controllerOrThrow().previousForSession(PlaybackSessionId(playbackSessionId))
        }

    public fun acceptsCommand(
        playbackSessionId: String,
        requiresSeekable: Boolean,
    ): Boolean = try {
        initializationErrorKind == null &&
            controllerOrThrow().acceptsCommand(
                PlaybackSessionId(playbackSessionId),
                requiresSeekable,
            )
    } catch (_: Throwable) {
        false
    }

    public fun setShuffle(enabled: Boolean): ApplePlaybackQueueTransitionDto = runClosed {
        controllerOrThrow().setShuffle(enabled)
    }

    public fun cycleRepeatMode(): ApplePlaybackQueueTransitionDto = runClosed {
        controllerOrThrow().cycleRepeatMode()
    }

    public fun snapshot(): ApplePlaybackQueueTransitionDto = runClosed {
        PlaybackQueueTransition(controllerOrThrow().snapshot(), null, emptyList())
    }

    public fun recordPreparing(attemptId: String): ApplePlaybackQueueTransitionDto =
        record(PlaybackEngineEvent.Preparing(AttemptId(attemptId)))

    public fun recordReady(
        attemptId: String,
        durationMilliseconds: Long,
        seekability: String,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.Ready(
            AttemptId(attemptId),
            durationMilliseconds.takeIf { it >= 0 }?.milliseconds,
            seekability.toPlaybackSeekability(),
        ),
    )

    public fun recordPlaybackProgressBegan(
        attemptId: String,
        wallClockEpochMilliseconds: Long,
        mediaPositionMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.PlaybackProgressBegan(
            AttemptId(attemptId),
            PlaybackWallClockTime(wallClockEpochMilliseconds),
            mediaPositionMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordBuffering(
        attemptId: String,
        positionMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.Buffering(
            AttemptId(attemptId),
            positionMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordBufferingEnded(
        attemptId: String,
        positionMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.BufferingEnded(
            AttemptId(attemptId),
            positionMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordPaused(
        attemptId: String,
        positionMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.Paused(
            AttemptId(attemptId),
            positionMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordResumed(
        attemptId: String,
        positionMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.Resumed(
            AttemptId(attemptId),
            positionMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordPositionChanged(
        attemptId: String,
        mediaPositionMilliseconds: Long,
        monotonicUptimeNanoseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.PositionChanged(
            AttemptId(attemptId),
            mediaPositionMilliseconds.nonNegativeMilliseconds(),
            PlaybackMonotonicTime(monotonicUptimeNanoseconds.nonNegativeNanoseconds()),
        ),
    )

    public fun recordDurationChanged(
        attemptId: String,
        durationMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.DurationChanged(
            AttemptId(attemptId),
            durationMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordSeekCompleted(
        attemptId: String,
        fromMilliseconds: Long,
        toMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.SeekCompleted(
            AttemptId(attemptId),
            fromMilliseconds.nonNegativeMilliseconds(),
            toMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordSeekFailed(
        attemptId: String,
        fromMilliseconds: Long,
        toMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.SeekFailed(
            AttemptId(attemptId),
            fromMilliseconds.nonNegativeMilliseconds(),
            toMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordEndedNaturally(
        attemptId: String,
        finalPositionMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.EndedNaturally(
            AttemptId(attemptId),
            finalPositionMilliseconds.nonNegativeMilliseconds(),
        ),
    )

    public fun recordSkipped(
        attemptId: String,
        positionMilliseconds: Long,
        reason: String,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.Skipped(
            AttemptId(attemptId),
            positionMilliseconds.nonNegativeMilliseconds(),
            reason.toPlaybackSkipReason(),
        ),
    )

    public fun recordFailedBeforeStart(
        attemptId: String,
        errorKind: String,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.FailedBeforeStart(
            AttemptId(attemptId),
            errorKind.toClosedPlaybackDomainError(),
        ),
    )

    public fun recordFailedAfterPartial(
        attemptId: String,
        positionMilliseconds: Long,
        errorKind: String,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.FailedAfterPartial(
            AttemptId(attemptId),
            positionMilliseconds.nonNegativeMilliseconds(),
            errorKind.toClosedPlaybackDomainError(),
        ),
    )

    public fun recordRouteChanged(
        attemptId: String,
        oldRoute: String,
        newRoute: String,
        didPause: Boolean,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.RouteChanged(
            AttemptId(attemptId),
            oldRoute.toPlaybackRouteKind(),
            newRoute.toPlaybackRouteKind(),
            didPause,
        ),
    )

    public fun recordInterruptionBegan(
        attemptId: String,
        shouldResume: Boolean,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.InterruptionBegan(AttemptId(attemptId), shouldResume),
    )

    public fun recordInterruptionEnded(
        attemptId: String,
        shouldResume: Boolean,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.InterruptionEnded(AttemptId(attemptId), shouldResume),
    )

    public fun recordAttemptReplaced(
        oldAttemptId: String,
        newAttemptId: String,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.AttemptReplaced(AttemptId(oldAttemptId), AttemptId(newAttemptId)),
    )

    public fun recordAdvancedToPreloaded(
        oldAttemptId: String,
        newAttemptId: String,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.AdvancedToPreloaded(
            AttemptId(oldAttemptId),
            AttemptId(newAttemptId),
        ),
    )

    public fun recordRateChanged(
        attemptId: String,
        rate: Double,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.RateChanged(AttemptId(attemptId), rate),
    )

    public fun recordEngineTornDown(
        attemptId: String,
        reason: String,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.EngineTornDown(
            AttemptId(attemptId),
            reason.toPlaybackEngineTeardownReason(),
        ),
    )

    public fun recordSourceRefreshRequired(
        attemptId: String,
        reason: String,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.SourceRefreshRequired(
            AttemptId(attemptId),
            reason.toPlaybackSourceRefreshReason(),
        ),
    )

    public fun recordObservationResynced(
        attemptId: String,
        status: String,
        mediaPositionMilliseconds: Long,
        durationMilliseconds: Long,
        seekability: String,
        rate: Double,
        progressStartWallClockEpochMilliseconds: Long,
    ): ApplePlaybackQueueTransitionDto = record(
        PlaybackEngineEvent.ObservationResynced(
            AttemptId(attemptId),
            PlaybackObservationSnapshot(
                status = status.toPlaybackObservationStatus(),
                mediaPosition = mediaPositionMilliseconds.takeIf { it >= 0 }?.milliseconds,
                duration = durationMilliseconds.takeIf { it >= 0 }?.milliseconds,
                seekability = seekability.toPlaybackSeekability(),
                rate = rate,
                sessionStartWallClock = progressStartWallClockEpochMilliseconds
                    .takeIf { it >= 0 }
                    ?.let(::PlaybackWallClockTime),
            ),
        ),
    )

    public fun close() {
        try {
            deliveryEvents.close()
            deliveryScope.cancel()
            delivery?.sender?.close()
            delivery = null
            databaseStore?.close()
        } catch (_: Throwable) {
            // Closing is best effort and exports no failure-bearing resource.
        }
    }

    private fun runClosed(
        operation: () -> PlaybackQueueTransition,
    ): ApplePlaybackQueueTransitionDto {
        val initializationFailure = initializationErrorKind
        if (initializationFailure != null) {
            return ApplePlaybackQueueTransitionDto(null, null, initializationFailure)
        }
        return try {
            operation().also { captureEffects(it.effects) }.toAppleDto()
        } catch (_: IllegalArgumentException) {
            ApplePlaybackQueueTransitionDto(null, null, "input")
        } catch (_: Throwable) {
            ApplePlaybackQueueTransitionDto(null, null, "persistence")
        }
    }

    private fun controllerOrThrow(): PlaybackQueueController = checkNotNull(controller)

    private fun record(event: PlaybackEngineEvent): ApplePlaybackQueueTransitionDto = runClosed {
        controllerOrThrow().recordPlaybackEvent(event)
    }

    internal fun drainPendingEffects(): List<PlaybackCoreEffect> = buildList {
        while (pendingEffects.isNotEmpty()) add(pendingEffects.removeFirst())
    }

    internal fun pendingSubmittedPlayCount(): Long = delivery?.outbox?.count() ?: 0

    internal fun configurePersistenceOnlyDelivery(outbox: PersistentScrobbleOutbox) {
        delivery = ApplePlaybackDeliveryComposition(null, outbox, null)
        val waiting = pendingEffects.toList()
        pendingEffects.clear()
        captureEffects(waiting)
    }

    private fun captureEffects(effects: List<PlaybackCoreEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is PlaybackCoreEffect.RecordPlaybackEvent -> {
                    val activeDelivery = delivery
                    if (activeDelivery == null) {
                        pendingEffects += effect
                    } else {
                        if (effect.event is RecordedPlaybackEvent.SubmittedPlay) {
                            activeDelivery.outbox.persistSynchronously(effect.event)
                        }
                        check(deliveryEvents.trySend(effect.event).isSuccess)
                    }
                }
                is PlaybackCoreEffect.PersistResumePosition -> {
                    val store = resumePositions
                    if (store == null) pendingEffects += effect
                    else store.save(effect.itemId, effect.position)
                }
                is PlaybackCoreEffect.ClearResumePosition -> {
                    val store = resumePositions
                    if (store == null) pendingEffects += effect
                    else store.clear(effect.itemId)
                }
                is PlaybackCoreEffect.AccumulatorDiagnostic -> Unit
            }
        }
    }

    private suspend fun deliverNetworkEvent(event: RecordedPlaybackEvent) {
        val activeDelivery = delivery ?: run {
            pendingEffects += PlaybackCoreEffect.RecordPlaybackEvent(event)
            return
        }
        when (event) {
            is RecordedPlaybackEvent.NowPlaying -> {
                activeDelivery.sender?.send(ScrobbleEndpointRequest(event))
            }
            is RecordedPlaybackEvent.SubmittedPlay -> {
                activeDelivery.worker?.onForeground()
            }
        }
    }
}

private data class ApplePlaybackQueueComposition(
    val databaseStore: DulcetDatabaseStore?,
    val database: com.legitimateapps.dulcet.database.DulcetDatabase?,
    val controller: PlaybackQueueController,
    val resumePositions: ResumePositionStore?,
)

private data class ApplePlaybackDeliveryComposition(
    val sender: ScrobbleEndpointSender?,
    val outbox: PersistentScrobbleOutbox,
    val worker: ScrobbleOutboxDeliveryWorker?,
)

private data class ApplePlaybackQueueCompositionResult(
    val composition: ApplePlaybackQueueComposition?,
    val errorKind: String?,
)

private fun openApplePlaybackQueue(databaseName: String): ApplePlaybackQueueCompositionResult = try {
    require(databaseName.isNotBlank())
    val databaseStore = DulcetDriverFactory(databaseName = databaseName).openDulcetDatabase()
    val database = databaseStore.database
    val resumePositions = PersistentResumePositionStore(database)
    ApplePlaybackQueueCompositionResult(
        composition = ApplePlaybackQueueComposition(
            databaseStore = databaseStore,
            database = database,
            controller = PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = resumePositions,
                identities = PlaybackIdentitySource { prefix ->
                    "$prefix:${NSUUID().UUIDString}"
                },
            ),
            resumePositions = resumePositions,
        ),
        errorKind = null,
    )
} catch (_: IllegalArgumentException) {
    ApplePlaybackQueueCompositionResult(null, "input")
} catch (_: Throwable) {
    ApplePlaybackQueueCompositionResult(null, "persistence")
}

private object ApplePlaybackWallClock : OutboxWallClock {
    @OptIn(ExperimentalForeignApi::class)
    override fun nowEpochMilliseconds(): Long =
        time(null) * 1_000L
}

private fun String.toQueueSourceKind(): QueueSourceKind = when (this) {
    "album" -> QueueSourceKind.Album
    "playlist" -> QueueSourceKind.Playlist
    "search" -> QueueSourceKind.Search
    "artist" -> QueueSourceKind.Artist
    else -> throw IllegalArgumentException("Unknown queue source kind")
}

private fun Long.nonNegativeMilliseconds() = also { require(it >= 0) }.milliseconds

private fun Long.nonNegativeNanoseconds() = also { require(it >= 0) }.nanoseconds

private fun String.toPlaybackSeekability(): PlaybackSeekability = when (this) {
    "seekable" -> PlaybackSeekability.Seekable
    "notSeekable" -> PlaybackSeekability.NotSeekable
    "unknown" -> PlaybackSeekability.Unknown
    else -> throw IllegalArgumentException("Unknown playback seekability")
}

private fun String.toPlaybackSkipReason(): PlaybackSkipReason = when (this) {
    "user" -> PlaybackSkipReason.User
    "autoAdvance" -> PlaybackSkipReason.AutoAdvance
    "queueReplacement" -> PlaybackSkipReason.QueueReplacement
    else -> throw IllegalArgumentException("Unknown playback skip reason")
}

private fun String.toPlaybackRouteKind(): PlaybackRouteKind = when (this) {
    "builtIn" -> PlaybackRouteKind.BuiltIn
    "wired" -> PlaybackRouteKind.Wired
    "bluetooth" -> PlaybackRouteKind.Bluetooth
    "hdmi" -> PlaybackRouteKind.Hdmi
    "remote" -> PlaybackRouteKind.Remote
    "unknown" -> PlaybackRouteKind.Unknown
    else -> throw IllegalArgumentException("Unknown playback route")
}

private fun String.toPlaybackEngineTeardownReason(): PlaybackEngineTeardownReason = when (this) {
    "backgroundLimit" -> PlaybackEngineTeardownReason.BackgroundLimit
    "lifecycle" -> PlaybackEngineTeardownReason.Lifecycle
    "systemReclaimed" -> PlaybackEngineTeardownReason.SystemReclaimed
    "released" -> PlaybackEngineTeardownReason.Released
    "unknown" -> PlaybackEngineTeardownReason.Unknown
    else -> throw IllegalArgumentException("Unknown engine teardown reason")
}

private fun String.toPlaybackSourceRefreshReason(): PlaybackSourceRefreshReason = when (this) {
    "unauthorized" -> PlaybackSourceRefreshReason.Unauthorized
    "expired" -> PlaybackSourceRefreshReason.Expired
    "validationFailed" -> PlaybackSourceRefreshReason.ValidationFailed
    else -> throw IllegalArgumentException("Unknown source refresh reason")
}

private fun String.toPlaybackObservationStatus(): PlaybackObservationStatus = when (this) {
    "preparing" -> PlaybackObservationStatus.Preparing
    "ready" -> PlaybackObservationStatus.Ready
    "progressing" -> PlaybackObservationStatus.Progressing
    "buffering" -> PlaybackObservationStatus.Buffering
    "paused" -> PlaybackObservationStatus.Paused
    "stopped" -> PlaybackObservationStatus.Stopped
    "failed" -> PlaybackObservationStatus.Failed
    else -> throw IllegalArgumentException("Unknown observation status")
}

private fun String.toClosedPlaybackDomainError(): DomainError = when (this) {
    "authentication" -> DomainError.Auth.InvalidCredentials
    "forbidden" -> DomainError.Auth.Forbidden
    "serverBusy" -> DomainError.Server.Busy(null)
    "protocolViolation" -> DomainError.Protocol.MalformedEnvelope
    "sourceUnavailable", "unsupportedPlan" -> DomainError.Playback.NoPlayableSource
    "transport", "tlsUntrusted", "engine" -> DomainError.Transport.Unreachable
    else -> throw IllegalArgumentException("Unknown playback failure")
}

private fun PlaybackQueueTransition.toAppleDto() = ApplePlaybackQueueTransitionDto(
    snapshot = snapshot.toAppleDto(),
    startDirective = startDirective?.let { directive ->
        ApplePlaybackStartDirectiveDto(
            queueEntryId = directive.queueEntryId.value,
            playbackSessionId = directive.playbackSessionId.value,
            attemptId = directive.attemptId.value,
            providerInstanceId = directive.itemId.providerInstanceId,
            rawId = directive.itemId.rawId,
            durationMilliseconds = directive.duration?.inWholeMilliseconds ?: -1,
            resumePositionMilliseconds = directive.resumePosition?.inWholeMilliseconds ?: -1,
        )
    },
    errorKind = null,
)

private fun PlaybackQueueSnapshot.toAppleDto() = ApplePlaybackQueueSnapshotDto(
    entries = entries.map { entry ->
        ApplePlaybackQueueEntryDto(
            queueEntryId = entry.queueEntryId.value,
            providerInstanceId = entry.itemId.providerInstanceId,
            rawId = entry.itemId.rawId,
        )
    },
    currentIndex = currentIndex ?: -1,
    repeatMode = repeatMode.storageValue,
    shuffleEnabled = shuffleState == QueueShuffleState.Enabled,
    currentSession = currentSession?.let { session ->
        ApplePlaybackCoreSessionDto(
            queueEntryId = session.queueEntryId.value,
            playbackSessionId = session.playbackSessionId.value,
            attemptId = session.currentAttempt.attemptId.value,
            providerInstanceId = session.itemId.providerInstanceId,
            rawId = session.itemId.rawId,
            phase = session.currentAttempt.phase.name,
            positionMilliseconds = session.currentAttempt.position?.inWholeMilliseconds ?: -1,
            durationMilliseconds = session.currentAttempt.duration?.inWholeMilliseconds ?: -1,
            seekability = session.currentAttempt.seekability.name,
            rate = session.currentAttempt.rate,
        )
    },
)
