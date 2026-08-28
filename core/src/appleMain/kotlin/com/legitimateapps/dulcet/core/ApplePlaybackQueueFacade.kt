package com.legitimateapps.dulcet.core

import platform.Foundation.NSUUID
import kotlin.time.Duration.Companion.milliseconds

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

/**
 * Objective-C-compatible queue facade. Its owner serializes calls; every public operation closes
 * failures into [ApplePlaybackQueueTransitionDto] instead of allowing a Kotlin exception to cross.
 */
public class ApplePlaybackQueueClient private constructor(
    compositionResult: ApplePlaybackQueueCompositionResult,
) {
    private val databaseStore: DulcetDatabaseStore?
    private val controller: PlaybackQueueController?
    private val initializationErrorKind: String?

    init {
        databaseStore = compositionResult.composition?.databaseStore
        controller = compositionResult.composition?.controller
        initializationErrorKind = compositionResult.errorKind
    }

    public constructor(databaseName: String) : this(openApplePlaybackQueue(databaseName))

    internal constructor(controller: PlaybackQueueController) : this(
        ApplePlaybackQueueCompositionResult(
            composition = ApplePlaybackQueueComposition(null, controller),
            errorKind = null,
        ),
    )

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

    public fun setShuffle(enabled: Boolean): ApplePlaybackQueueTransitionDto = runClosed {
        controllerOrThrow().setShuffle(enabled)
    }

    public fun cycleRepeatMode(): ApplePlaybackQueueTransitionDto = runClosed {
        controllerOrThrow().cycleRepeatMode()
    }

    public fun snapshot(): ApplePlaybackQueueTransitionDto = runClosed {
        PlaybackQueueTransition(controllerOrThrow().snapshot(), null, emptyList())
    }

    public fun close() {
        try {
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
            operation().toAppleDto()
        } catch (_: IllegalArgumentException) {
            ApplePlaybackQueueTransitionDto(null, null, "input")
        } catch (_: Throwable) {
            ApplePlaybackQueueTransitionDto(null, null, "persistence")
        }
    }

    private fun controllerOrThrow(): PlaybackQueueController = checkNotNull(controller)
}

private data class ApplePlaybackQueueComposition(
    val databaseStore: DulcetDatabaseStore?,
    val controller: PlaybackQueueController,
)

private data class ApplePlaybackQueueCompositionResult(
    val composition: ApplePlaybackQueueComposition?,
    val errorKind: String?,
)

private fun openApplePlaybackQueue(databaseName: String): ApplePlaybackQueueCompositionResult = try {
    require(databaseName.isNotBlank())
    val databaseStore = DulcetDriverFactory(databaseName = databaseName).openDulcetDatabase()
    val database = databaseStore.database
    ApplePlaybackQueueCompositionResult(
        composition = ApplePlaybackQueueComposition(
            databaseStore = databaseStore,
            controller = PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = PersistentResumePositionStore(database),
                identities = PlaybackIdentitySource { prefix ->
                    "$prefix:${NSUUID().UUIDString}"
                },
            ),
        ),
        errorKind = null,
    )
} catch (_: IllegalArgumentException) {
    ApplePlaybackQueueCompositionResult(null, "input")
} catch (_: Throwable) {
    ApplePlaybackQueueCompositionResult(null, "persistence")
}

private fun String.toQueueSourceKind(): QueueSourceKind = when (this) {
    "album" -> QueueSourceKind.Album
    "playlist" -> QueueSourceKind.Playlist
    "search" -> QueueSourceKind.Search
    "artist" -> QueueSourceKind.Artist
    else -> throw IllegalArgumentException("Unknown queue source kind")
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
