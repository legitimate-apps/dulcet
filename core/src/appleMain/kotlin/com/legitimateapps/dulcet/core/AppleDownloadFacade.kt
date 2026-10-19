package com.legitimateapps.dulcet.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.posix.memcpy
import kotlin.time.Duration.Companion.milliseconds

/** Synchronous cancellation handle for one exported download preparation operation. */
public interface AppleDownloadOperation {
    public fun cancel()
}

/** Closed result of relaunch reconciliation. All identifiers remain opaque strings. */
public class AppleDownloadReconciliationOutcomeDto internal constructor(
    public val taskIdentifiersToCancel: List<String>,
    public val interruptedDownloadIdentifiers: List<String>,
    public val recoveredDownloadIdentifiers: List<String>,
    public val errorKind: String?,
)

/** A complete, signed request ready for the platform-owned URLSession executor. */
public class ApplePreparedDownloadDto internal constructor(
    public val downloadIdentifier: String,
    public val rawId: String,
    public val url: String,
    public val hostHeader: String?,
    public val temporaryFilePath: String,
) {
    override fun toString(): String = "ApplePreparedDownloadDto(<redacted>)"
}

/** Exactly one of [prepared], [terminalState], and [errorKind] is populated. */
public class AppleDownloadPreparationOutcomeDto internal constructor(
    public val prepared: ApplePreparedDownloadDto?,
    /** `downloaded`, `queued`, or null. */
    public val terminalState: String?,
    public val errorKind: String?,
    public val retryNotBeforeWallClock: Long?,
)

/** Closed result after the platform executor has closed and delivered its response body. */
public class AppleDownloadPromotionOutcomeDto internal constructor(
    public val state: String,
    public val exactByteLength: Long,
    public val errorKind: String?,
)

/** Resume data remains opaque to the core and the platform. */
public class AppleDownloadResumeOutcomeDto internal constructor(
    public val data: NSData?,
    public val restartFromZero: Boolean,
    public val errorKind: String?,
)

/** Opaque wrapper around a core-owned, revalidated local playback plan. */
public class AppleLocalPlaybackPlanDto internal constructor(
    internal val corePlan: LocalPlaybackPlan,
) {
    public val downloadIdentifier: String get() = corePlan.downloadId.value
    public val rawId: String get() = corePlan.identity.rawId
    public val expectedContainer: String get() = corePlan.container.name
    public val exactByteLength: Long get() = corePlan.exactByteLength
    public val filePath: String get() = corePlan.absolutePath

    override fun toString(): String = "AppleLocalPlaybackPlanDto(<local-file>)"
}

public class AppleLocalPlaybackPlanOutcomeDto internal constructor(
    public val plan: AppleLocalPlaybackPlanDto?,
    /** `notDownloaded`, `missingFile`, `invalidFile`, or null. */
    public val unavailableKind: String?,
    public val errorKind: String?,
)

public class AppleDownloadStatusOutcomeDto internal constructor(
    /** `notDownloaded`, `queued`, `downloading`, `interrupted`, `downloaded`, or `stale`. */
    public val state: String,
    public val errorKind: String?,
    public val retryNotBeforeWallClock: Long?,
)

public class AppleDownloadAccountRemovalOutcomeDto internal constructor(
    public val errorKind: String?,
)

public class AppleDownloadFileTargetOutcomeDto internal constructor(
    public val temporaryFilePath: String?,
    public val rawId: String?,
    public val errorKind: String?,
)

public class AppleOfflineLibraryHydrationDto internal constructor(
    public val enabled: Boolean,
    public val albumIds: List<String>,
    public val trackIds: List<String>,
    public val playlistIds: List<String>,
)

public class AppleOfflineLibraryStorageDto internal constructor(
    public val automaticDownloadsEnabled: Boolean,
    public val cacheBudgetBytes: Long,
    public val downloadBytes: Long,
    public val cacheBytes: Long,
    public val artworkBytes: Long,
    public val queuedAutomaticCount: Int,
    public val completedAutomaticCount: Int,
    public val errorKind: String?,
)

public class AppleOfflineLibraryChangeDto internal constructor(
    public val taskIdentifiersToCancel: List<String>,
    public val hasChanges: Boolean,
    public val errorKind: String?,
)

/**
 * Objective-C-compatible facade over the durable download policy.
 *
 * The platform owns URLSession and file delivery. Every public method catches all failures, returns
 * closed DTOs, and never permits a Kotlin exception or credential-bearing diagnostic to cross.
 */
public class AppleDownloadClient(
    private val account: PlaybackEndpointAccount,
    private val databaseName: String,
    private val downloadRootPath: String,
) {
    private val scope: CoroutineScope = MainScope()
    private var database: DulcetDatabaseStore? = null
    private var engine: DownloadPolicyEngine? = null
    private var offline: OfflineLibraryPolicy? = null
    private var wireClient: PlaybackWireClient? = null
    private var requestClient: AuthenticatedEndpointClient? = null
    private var reconciled = false

    public fun reconcile(
        outstandingTaskIdentifiers: List<String>,
        credentialGeneration: Long,
    ): AppleDownloadReconciliationOutcomeDto = try {
        check(!reconciled)
        require(credentialGeneration >= 0)
        val policy = openPolicy()
        val result = policy.reconcile(
            outstandingTasks = outstandingTaskIdentifiers
                .filter(String::isNotBlank)
                .distinct()
                .map { OutstandingDownloadTask(DownloadId(it)) },
            currentCredentialGenerations = mapOf(
                account.providerInstanceId to credentialGeneration,
            ),
        )
        reconciled = true
        AppleDownloadReconciliationOutcomeDto(
            taskIdentifiersToCancel = result.taskIdsToCancel.map { it.value },
            interruptedDownloadIdentifiers = result.interruptedRows.map { it.value },
            recoveredDownloadIdentifiers = result.recoveredPromotions.map { it.value },
            errorKind = null,
        )
    } catch (_: Throwable) {
        AppleDownloadReconciliationOutcomeDto(emptyList(), emptyList(), emptyList(), "persistence")
    }

    public fun startPrepareOriginalDownload(
        rawId: String,
        sourceContainer: AudioContainer,
        durationMilliseconds: Long,
        credentialGeneration: Long,
        wallClockMilliseconds: Long,
        diskBudgetBytes: Long,
        unknownLengthReservationBytes: Long,
        completion: (AppleDownloadPreparationOutcomeDto) -> Unit,
    ): AppleDownloadOperation = AppleDownloadOperationImpl(scope) {
        val outcome = try {
            requireReconciled()
            require(rawId.isNotBlank())
            val policy = requireNotNull(engine)
            val identity = DownloadIdentity(
                serverId = account.providerInstanceId,
                rawId = rawId,
                transcodeProfile = DownloadIdentity.ORIGINAL_PROFILE,
            )
            val existing = policy.record(identity)
            if (existing != null) policy.enqueue(DownloadRequest(identity, sourceContainer, null,
                DownloadServerSnapshot(durationMilliseconds.takeIf { it >= 0 }, null),
                credentialGeneration, wallClockMilliseconds))
            if (existing?.state == DownloadState.Complete || existing?.state == DownloadState.Stale) {
                AppleDownloadPreparationOutcomeDto(null, "downloaded", null, null)
            } else if (existing?.state == DownloadState.Downloading) {
                AppleDownloadPreparationOutcomeDto(null, "downloading", null, null)
            } else {
                policy.enqueue(
                    DownloadRequest(
                        identity = identity,
                        expectedContainer = sourceContainer,
                        declaredContentLength = null,
                        serverSnapshot = DownloadServerSnapshot(
                            durationMilliseconds = durationMilliseconds.takeIf { it >= 0 },
                            sizeBytes = null,
                        ),
                        credentialGeneration = credentialGeneration,
                        wallClockMilliseconds = wallClockMilliseconds,
                    ),
                )
                prepareScheduled(
                    policy,
                    schedulingContext(
                        wallClockMilliseconds,
                        diskBudgetBytes,
                        unknownLengthReservationBytes,
                    ),
                )
            }
        } catch (_: CancellationException) {
            AppleDownloadPreparationOutcomeDto(null, null, "cancelled", null)
        } catch (_: Throwable) {
            AppleDownloadPreparationOutcomeDto(null, null, "persistence", null)
        }
        try {
            completion(outcome)
        } catch (_: Throwable) {
            // A presentation callback cannot escape into or abort the core operation.
        }
    }

    public fun startPrepareNextDownload(
        wallClockMilliseconds: Long,
        diskBudgetBytes: Long,
        unknownLengthReservationBytes: Long,
        completion: (AppleDownloadPreparationOutcomeDto) -> Unit,
    ): AppleDownloadOperation = AppleDownloadOperationImpl(scope) {
        val outcome = try {
            requireReconciled()
            prepareScheduled(
                requireNotNull(engine),
                schedulingContext(
                    wallClockMilliseconds,
                    diskBudgetBytes,
                    unknownLengthReservationBytes,
                ),
            )
        } catch (_: CancellationException) {
            AppleDownloadPreparationOutcomeDto(null, null, "cancelled", null)
        } catch (_: Throwable) {
            AppleDownloadPreparationOutcomeDto(null, null, "persistence", null)
        }
        try {
            completion(outcome)
        } catch (_: Throwable) {
            // A presentation callback cannot escape into or abort the core operation.
        }
    }

    public fun finishDownload(
        downloadIdentifier: String,
        statusCode: Int,
        contentType: String?,
        contentLength: Long,
        retryAfterMilliseconds: Long,
        wallClockMilliseconds: Long,
    ): AppleDownloadPromotionOutcomeDto = try {
        requireReconciled()
        val policy = requireNotNull(engine)
        val downloadId = DownloadId(downloadIdentifier)
        if (statusCode !in 200..299) {
            val failure = when (statusCode) {
                401 -> DomainError.Auth.InvalidCredentials
                403 -> DomainError.Auth.Forbidden
                429 -> DomainError.Server.Busy(
                    retryAfterMilliseconds.takeIf { it >= 0 }?.milliseconds,
                )
                else -> DomainError.Server.Unknown(statusCode)
            }
            policy.recordFailure(downloadId, failure, wallClockMilliseconds)
            return AppleDownloadPromotionOutcomeDto("interrupted", -1, failure.appleDownloadKind())
        }
        when (
            val promoted = policy.promote(
                downloadId,
                DownloadResponseMetadata(
                    contentType = contentType,
                    contentLength = contentLength.takeIf { it >= 0 }
                        ?.let(PlaybackContentLength::Exact),
                ),
            )
        ) {
            is DownloadPromotionResult.Promoted -> AppleDownloadPromotionOutcomeDto(
                "downloaded",
                promoted.record.exactByteLength ?: -1,
                null,
            )
            is DownloadPromotionResult.AlreadyPromoted -> AppleDownloadPromotionOutcomeDto(
                "downloaded",
                promoted.record.exactByteLength ?: -1,
                null,
            )
            is DownloadPromotionResult.Rejected -> {
                policy.recordFailure(downloadId, promoted.error, wallClockMilliseconds)
                AppleDownloadPromotionOutcomeDto("interrupted", -1, promoted.error.appleDownloadKind())
            }
            DownloadPromotionResult.MissingTemporaryFile ->
                AppleDownloadPromotionOutcomeDto("interrupted", -1, "missingTemporaryFile")
            DownloadPromotionResult.UnknownDownload ->
                AppleDownloadPromotionOutcomeDto("notDownloaded", -1, "unknownDownload")
        }
    } catch (_: Throwable) {
        AppleDownloadPromotionOutcomeDto("interrupted", -1, "persistence")
    }

    public fun recordFailure(
        downloadIdentifier: String,
        wallClockMilliseconds: Long,
    ): AppleDownloadStatusOutcomeDto = try {
        requireReconciled()
        val retry = requireNotNull(engine).recordFailure(
            DownloadId(downloadIdentifier),
            DomainError.Transport.Unreachable,
            wallClockMilliseconds,
        )
        AppleDownloadStatusOutcomeDto("interrupted", null, retry?.retryAtWallClock)
    } catch (_: Throwable) {
        AppleDownloadStatusOutcomeDto("interrupted", "persistence", null)
    }

    @OptIn(ExperimentalForeignApi::class)
    public fun recordResumeData(
        downloadIdentifier: String,
        data: NSData,
        wallClockMilliseconds: Long,
    ): AppleDownloadStatusOutcomeDto = try {
        requireReconciled()
        val policy = requireNotNull(engine)
        val downloadId = DownloadId(downloadIdentifier)
        policy.recordResumeData(
            downloadId,
            data.toDownloadByteArray(),
            wallClockMilliseconds,
        )
        val retry = policy.recordFailure(
            downloadId,
            DomainError.Transport.Unreachable,
            wallClockMilliseconds,
        )
        AppleDownloadStatusOutcomeDto("interrupted", null, retry?.retryAtWallClock)
    } catch (_: Throwable) {
        AppleDownloadStatusOutcomeDto("interrupted", "persistence", null)
    }

    @OptIn(ExperimentalForeignApi::class)
    public fun resumeData(
        downloadIdentifier: String,
        wallClockMilliseconds: Long,
    ): AppleDownloadResumeOutcomeDto = try {
        requireReconciled()
        when (
            val decision = requireNotNull(engine).resumeDecision(
                DownloadId(downloadIdentifier),
                wallClockMilliseconds,
            )
        ) {
            is DownloadResumeDecision.Resume -> AppleDownloadResumeOutcomeDto(
                decision.data.toNSData(),
                restartFromZero = false,
                errorKind = null,
            )
            DownloadResumeDecision.RestartFromZero ->
                AppleDownloadResumeOutcomeDto(null, restartFromZero = true, errorKind = null)
        }
    } catch (_: Throwable) {
        AppleDownloadResumeOutcomeDto(null, restartFromZero = true, errorKind = "persistence")
    }

    public fun rejectResumeData(downloadIdentifier: String): AppleDownloadStatusOutcomeDto = try {
        requireReconciled()
        requireNotNull(engine).rejectResumeData(DownloadId(downloadIdentifier))
        AppleDownloadStatusOutcomeDto("queued", null, null)
    } catch (_: Throwable) {
        AppleDownloadStatusOutcomeDto("interrupted", "persistence", null)
    }

    public fun status(rawId: String): AppleDownloadStatusOutcomeDto = try {
        requireReconciled()
        val record = requireNotNull(engine).record(originalIdentity(rawId))
        val cached = requireNotNull(engine).recordsForItem(account.providerInstanceId, rawId).firstOrNull {
            it.state in setOf(DownloadState.Complete, DownloadState.Stale)
        }
        AppleDownloadStatusOutcomeDto(if (cached != null && record?.state !in setOf(DownloadState.Complete, DownloadState.Stale)) (if (cached.state == DownloadState.Stale) "stale" else "cached") else record?.state.appleState(), null, record?.retryNotBeforeWallClock)
    } catch (_: Throwable) {
        AppleDownloadStatusOutcomeDto("notDownloaded", "persistence", null)
    }

    public fun fileTarget(downloadIdentifier: String): AppleDownloadFileTargetOutcomeDto = try {
        requireReconciled()
        val policy = requireNotNull(engine)
        val record = policy.record(DownloadId(downloadIdentifier))
            ?: return AppleDownloadFileTargetOutcomeDto(null, null, "unknownDownload")
        AppleDownloadFileTargetOutcomeDto(
            temporaryFilePath = policy.temporaryFilePath(record.downloadId),
            rawId = record.identity.rawId,
            errorKind = null,
        )
    } catch (_: Throwable) {
        AppleDownloadFileTargetOutcomeDto(null, null, "persistence")
    }

    public fun evaluateRedirect(
        sourceUrl: String,
        proposedUrl: String,
        redirectsAlreadyFollowed: Int,
    ): ApplePlaybackRedirectDecisionDto = try {
        when (
            val decision = AccountConnectionContract.redirectDecision(
                sourceUrl,
                proposedUrl,
                redirectsAlreadyFollowed,
            )
        ) {
            RedirectPolicyDecision.PreserveCredentials ->
                ApplePlaybackRedirectDecisionDto("preserve", emptyList())
            is RedirectPolicyDecision.Reject -> if (
                decision.reason == RedirectRejectionReason.CrossOrigin
            ) {
                ApplePlaybackRedirectDecisionDto("strip", listOf("u", "t", "s"))
            } else {
                ApplePlaybackRedirectDecisionDto("reject", emptyList())
            }
        }
    } catch (_: Throwable) {
        ApplePlaybackRedirectDecisionDto("reject", emptyList())
    }

    public fun localPlaybackPlan(rawId: String): AppleLocalPlaybackPlanOutcomeDto = try {
        requireReconciled()
        val policy = requireNotNull(engine)
        when (val result = requireNotNull(offline).offlinePlaybackPlan(account.providerInstanceId, rawId, (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong())) {
            is OfflinePlaybackPlanResult.Available -> when (policy.verifyOffline(result.plan)) {
                OfflinePlaybackVerification.Valid -> AppleLocalPlaybackPlanOutcomeDto(
                    AppleLocalPlaybackPlanDto(result.plan),
                    unavailableKind = null,
                    errorKind = null,
                )
                OfflinePlaybackVerification.InvalidFile ->
                    AppleLocalPlaybackPlanOutcomeDto(null, "invalidFile", null)
                OfflinePlaybackVerification.MissingFile ->
                    AppleLocalPlaybackPlanOutcomeDto(null, "missingFile", null)
            }
            OfflinePlaybackPlanResult.NotDownloaded ->
                AppleLocalPlaybackPlanOutcomeDto(null, "notDownloaded", null)
            OfflinePlaybackPlanResult.MissingFile ->
                AppleLocalPlaybackPlanOutcomeDto(null, "missingFile", null)
        }
    } catch (_: Throwable) {
        AppleLocalPlaybackPlanOutcomeDto(null, "notDownloaded", "persistence")
    }

    public fun missingAutomaticDownloadDetails(): AppleOfflineLibraryHydrationDto = try {
        requireReconciled()
        val policy = requireNotNull(offline)
        AppleOfflineLibraryHydrationDto(policy.settings(account.providerInstanceId).automaticDownloadsEnabled,
            policy.missingAutomaticDownloadAlbumIds(account.providerInstanceId),
            policy.missingAutomaticDownloadTrackIds(account.providerInstanceId),
            policy.missingAutomaticDownloadPlaylistIds(account.providerInstanceId))
    } catch (_: Throwable) { AppleOfflineLibraryHydrationDto(false, emptyList(), emptyList(), emptyList()) }

    public fun offlineLibraryStorage(): AppleOfflineLibraryStorageDto = try {
        requireReconciled()
        val policy = requireNotNull(offline)
        val settings = policy.settings(account.providerInstanceId)
        val stats = policy.stats(account.providerInstanceId)
        AppleOfflineLibraryStorageDto(settings.automaticDownloadsEnabled, settings.cacheBudgetBytes,
            stats.downloadBytes, stats.cacheBytes, stats.artworkBytes, stats.queuedAutomaticCount, stats.completedAutomaticCount, null)
    } catch (_: Throwable) { AppleOfflineLibraryStorageDto(false, 0, 0, 0, 0, 0, 0, "persistence") }

    public fun setAutomaticDownloadsEnabled(enabled: Boolean, allowNewDownloads: Boolean, credentialGeneration: Long, wallClockMilliseconds: Long): AppleOfflineLibraryChangeDto = try {
        requireReconciled()
        val policy = requireNotNull(offline)
        val changed = policy.setAutomaticDownloadsEnabled(account.providerInstanceId, enabled)
        val reconciled = if (enabled) policy.reconcileFromSeenCache(account.providerInstanceId, credentialGeneration, wallClockMilliseconds,
            allowNewDownloads = allowNewDownloads) else changed
        AppleOfflineLibraryChangeDto(reconciled.removed.map { it.downloadId.value }, true, null)
    } catch (_: Throwable) { AppleOfflineLibraryChangeDto(emptyList(), false, "persistence") }

    public fun reconcileMyLibrary(allowNewDownloads: Boolean, credentialGeneration: Long, wallClockMilliseconds: Long): AppleOfflineLibraryChangeDto = try {
        requireReconciled()
        val changed = requireNotNull(offline).reconcileFromSeenCache(account.providerInstanceId, credentialGeneration, wallClockMilliseconds,
            allowNewDownloads = allowNewDownloads)
        AppleOfflineLibraryChangeDto(changed.removed.map { it.downloadId.value }, changed.queued.isNotEmpty() || changed.removed.isNotEmpty(), null)
    } catch (_: Throwable) { AppleOfflineLibraryChangeDto(emptyList(), false, "persistence") }

    public fun setCacheBudgetBytes(bytes: Long): Boolean = try {
        requireReconciled()
        requireNotNull(offline).setCacheBudgetBytes(account.providerInstanceId, bytes)
        true
    } catch (_: Throwable) { false }

    public fun clearStreamingCache(): Boolean = try {
        requireReconciled()
        requireNotNull(offline).clearCache(account.providerInstanceId)
        true
    } catch (_: Throwable) { false }

    public fun protectPlaybackItems(rawIds: List<String>) {
        try {
            requireReconciled()
            val ids = rawIds.toSet()
            requireNotNull(offline).setProtectedIdentities(requireNotNull(engine).records(account.providerInstanceId)
                .filter { it.identity.rawId in ids }.map { it.identity }.toSet() +
                rawIds.flatMap { rawId -> AudioContainer.entries.map { container ->
                    DownloadIdentity(account.providerInstanceId, rawId, "stream:${container.name}")
                } })
        } catch (_: Throwable) { }
    }

    public fun beginStreamingCache(rawId: String, container: AudioContainer, totalLength: Long,
        durationMilliseconds: Long, rangeStart: Long, credentialGeneration: Long, wallClockMilliseconds: Long): String? = try {
        requireReconciled()
        val source = database?.database?.offlineLibraryQueries?.selectSourceSnapshot(account.providerInstanceId, rawId)?.executeAsOneOrNull()
        requireNotNull(offline).beginCacheWrite(DownloadRequest(
            DownloadIdentity(account.providerInstanceId, rawId, "stream:${container.name}"), container,
            PlaybackContentLength.Exact(totalLength), DownloadServerSnapshot(source?.duration_milliseconds ?: durationMilliseconds.takeIf { it >= 0 }, source?.size_bytes),
            credentialGeneration, wallClockMilliseconds), responseStatus = 206, rangeStart = rangeStart)?.writeId
    } catch (_: Throwable) { null }

    public fun appendStreamingCache(writeId: String, bytes: NSData, offset: Long): Boolean = try {
        requireNotNull(offline).appendCacheWrite(writeId, bytes.toDownloadByteArray(), offset)
    } catch (_: Throwable) { false }

    public fun isStreamingCacheWriteComplete(writeId: String): Boolean =
        offline?.isCacheWriteComplete(writeId) == true

    public fun planArtworkCache(evictableBytes: Long, pinnedBytes: Long, reservationBytes: Long): ArtworkCacheBudgetPlan? = try {
        requireReconciled()
        requireNotNull(offline).planArtworkCache(account.providerInstanceId, evictableBytes, pinnedBytes, reservationBytes)
    } catch (_: Throwable) { null }

    public fun planArtworkCacheForBudget(evictableBytes: Long, pinnedBytes: Long, reservationBytes: Long,
        budgetBytes: Long): ArtworkCacheBudgetPlan? = try {
        requireReconciled()
        requireNotNull(offline).planArtworkCache(account.providerInstanceId, evictableBytes, pinnedBytes,
            reservationBytes, budgetBytesOverride = budgetBytes)
    } catch (_: Throwable) { null }

    public fun planClearArtworkCache(evictableBytes: Long, pinnedBytes: Long): ArtworkCacheBudgetPlan? = try {
        requireReconciled()
        requireNotNull(offline).planClearCache(account.providerInstanceId, evictableBytes, pinnedBytes)
    } catch (_: Throwable) { null }

    public fun finishStreamingCache(writeId: String, totalLength: Long, wallClockMilliseconds: Long): Boolean = try {
        val result = requireNotNull(offline).finishCacheWrite(writeId,
            DownloadResponseMetadata(null, PlaybackContentLength.Exact(totalLength)), wallClockMilliseconds)
        result is DownloadPromotionResult.Promoted || result is DownloadPromotionResult.AlreadyPromoted
    } catch (_: Throwable) { false }

    public fun abortStreamingCache(writeId: String) {
        try { offline?.abortCacheWrite(writeId) } catch (_: Throwable) { }
    }

    public fun removeAccountData(): AppleDownloadAccountRemovalOutcomeDto = try {
        openPolicy()
        offline?.close()
        requireNotNull(engine).removeAccountData(account.providerInstanceId)
        AppleDownloadAccountRemovalOutcomeDto(null)
    } catch (_: Throwable) {
        AppleDownloadAccountRemovalOutcomeDto("persistence")
    }

    public fun close() {
        try { offline?.close() } catch (_: Throwable) { }
        try {
            requestClient?.close()
        } catch (_: Throwable) {
        }
        try {
            wireClient?.close()
        } catch (_: Throwable) {
        }
        try {
            database?.close()
        } catch (_: Throwable) {
        }
        requestClient = null
        wireClient = null
        database = null
        engine = null
        offline = null
        reconciled = false
    }

    /** Closes every network client while retaining the durable policy and local-file access. */
    public fun closeNetworkAccess() {
        try {
            requestClient?.close()
        } catch (_: Throwable) {
        }
        try {
            wireClient?.close()
        } catch (_: Throwable) {
        }
        requestClient = null
        wireClient = null
    }

    private fun openPolicy(): DownloadPolicyEngine {
        engine?.let { return it }
        require(databaseName.isNotBlank())
        require(downloadRootPath.isNotBlank())
        val opened = DulcetDriverFactory(databaseName = databaseName).openDulcetDatabase()
        database = opened
        wireClient = PlaybackWireClient(account)
        requestClient = AuthenticatedEndpointClient(
            credentials = AuthenticatedEndpointCredentials(
                normalizedBaseUrl = account.normalizedBaseUrl,
                username = account.username,
                password = account.password,
                allowLocalHttp = account.allowLocalHttp,
            ),
            operationName = "download.apple-background",
        )
        return DownloadPolicyEngine(
            opened.database,
            DownloadFileStore(downloadRootPath, okio.FileSystem.SYSTEM),
        ).also {
            engine = it
            offline = OfflineLibraryPolicy(opened.database, it)
        }
    }

    private suspend fun prepare(
        scheduled: DownloadScheduleResult.Start,
        failureWallClockMilliseconds: Long,
    ): AppleDownloadPreparationOutcomeDto {
        val row = scheduled.record
        val sessionId = PlaybackSessionId("download-session:${secureRandomBytes(16).toLowerHex()}")
        val attemptId = AttemptId("download-attempt:${secureRandomBytes(16).toLowerHex()}")
        val resolution = requireNotNull(wireClient).resolve(
            PlaybackResolveRequest(
                playbackSessionId = sessionId,
                attemptId = attemptId,
                itemId = ProviderItemId(account.providerInstanceId, row.identity.rawId),
                sourceContainer = row.expectedContainer,
                supportsTranscodingExtension = false,
                deviceProfile = downloadDeviceProfile(),
                legacyPreference = LegacyPlaybackPreference(format = null, maxBitRateKbps = null),
                legacyTimeOffset = null,
            ),
        )
        if (resolution is PlaybackResolutionResult.Failed) {
            val retry = requireNotNull(engine).recordFailure(
                row.downloadId,
                resolution.error,
                failureWallClockMilliseconds,
            )
            return AppleDownloadPreparationOutcomeDto(
                null,
                "interrupted",
                resolution.error.appleDownloadKind(),
                retry?.retryAtWallClock,
            )
        }
        val plan = (resolution as PlaybackResolutionResult.Resolved).plan.asOriginalFileDownload()
        val prepared = requireNotNull(requestClient).prepareGetRequest(
            endpoint = plan.endpoint,
            parameters = plan.parameters,
        )
        return AppleDownloadPreparationOutcomeDto(
            prepared = ApplePreparedDownloadDto(
                downloadIdentifier = scheduled.platformTaskIdentifier,
                rawId = row.identity.rawId,
                url = prepared.url,
                hostHeader = prepared.hostHeader,
                temporaryFilePath = scheduled.temporaryFilePath,
            ),
            terminalState = null,
            errorKind = null,
            retryNotBeforeWallClock = null,
        )
    }

    private suspend fun prepareScheduled(
        policy: DownloadPolicyEngine,
        context: DownloadSchedulingContext,
    ): AppleDownloadPreparationOutcomeDto = when (val scheduled = policy.schedule(context)) {
        is DownloadScheduleResult.Start -> prepare(scheduled, context.wallClockMilliseconds)
        DownloadScheduleResult.NothingQueued -> AppleDownloadPreparationOutcomeDto(
            null,
            "queued",
            null,
            policy.nextRetryNotBefore(account.providerInstanceId),
        )
        DownloadScheduleResult.DiskBudgetExceeded ->
            AppleDownloadPreparationOutcomeDto(null, null, "diskBudgetExceeded", null)
        DownloadScheduleResult.TranscodeBudgetReservedForPlayback ->
            AppleDownloadPreparationOutcomeDto(null, "queued", null, null)
    }

    private fun schedulingContext(
        wallClockMilliseconds: Long,
        diskBudgetBytes: Long,
        unknownLengthReservationBytes: Long,
    ): DownloadSchedulingContext = DownloadSchedulingContext(
        serverId = account.providerInstanceId,
        wallClockMilliseconds = wallClockMilliseconds,
        diskBudgetBytes = diskBudgetBytes,
        unknownLengthReservationBytes = unknownLengthReservationBytes,
        activeTranscodes = ActiveTranscodeCounts(),
        playbackSessionActive = false,
    )

    private fun originalIdentity(rawId: String): DownloadIdentity = DownloadIdentity(
        account.providerInstanceId,
        rawId,
        DownloadIdentity.ORIGINAL_PROFILE,
    )

    private fun requireReconciled() {
        check(reconciled) { "download reconciliation must finish before subsystem use" }
    }
}

private class AppleDownloadOperationImpl(
    scope: CoroutineScope,
    block: suspend () -> Unit,
) : AppleDownloadOperation {
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) { block() }

    init {
        scope.launch { job.start() }
    }

    override fun cancel() {
        job.cancel()
    }
}

private fun DownloadState?.appleState(): String = when (this) {
    null -> "notDownloaded"
    DownloadState.Queued -> "queued"
    DownloadState.Downloading -> "downloading"
    DownloadState.Interrupted -> "interrupted"
    DownloadState.Complete -> "downloaded"
    DownloadState.Stale -> "stale"
}

private fun DomainError.appleDownloadKind(): String = when (this) {
    DomainError.Transport.Cancelled -> "cancelled"
    DomainError.Transport.Timeout -> "timeout"
    DomainError.Transport.Unreachable -> "transport"
    is DomainError.Security.TlsUntrusted -> "tlsUntrusted"
    is DomainError.Security -> "security"
    DomainError.Auth.InvalidCredentials -> "authentication"
    DomainError.Auth.Forbidden -> "forbidden"
    is DomainError.Auth -> "authentication"
    is DomainError.Server.Busy -> "serverBusy"
    is DomainError.Server -> "server"
    is DomainError.Protocol -> "protocol"
    DomainError.Playback.NoPlayableSource -> "unsupportedPlan"
    is DomainError.Input.InvalidServerUrl -> "input"
    is DomainError.CapabilityUnsupported -> "unsupportedPlan"
}

private fun downloadDeviceProfile(): PlaybackDeviceProfile = PlaybackDeviceProfile(
    name = "Dulcet download",
    platform = "macOS",
    maxAudioBitrate = 1_000_000,
    maxTranscodingAudioBitrate = 320_000,
    directPlayProfiles = listOf(
        DirectPlayAudioProfile(
            containers = AudioContainer.entries,
            audioCodecs = listOf("mp3", "aac", "flac", "vorbis", "pcm"),
            maxAudioChannels = 8,
        ),
    ),
    transcodingProfiles = listOf(
        TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2),
    ),
)

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toDownloadByteArray(): ByteArray {
    if (length == 0UL) return ByteArray(0)
    val result = ByteArray(length.toInt())
    result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return result
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    val data = NSMutableData()
    data.setLength(size.toULong())
    memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong())
    data
}
