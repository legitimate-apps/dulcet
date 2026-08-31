package com.legitimateapps.dulcet.core

import okio.Path.Companion.toPath

internal class DownloadControlEnvironment(
    val engine: DownloadPolicyEngine,
    private val database: DulcetDatabaseStore,
    private val cleanup: () -> Unit,
) {
    fun close() {
        try {
            database.close()
        } finally {
            cleanup()
        }
    }
}

internal expect fun createDownloadControlEnvironment(): DownloadControlEnvironment

public data class DownloadControlPayload(
    val serverId: String,
    val rawId: String,
    val transcodeProfile: String,
    val container: AudioContainer,
    val contentType: String?,
    val contentLength: PlaybackContentLength?,
    val bytes: ByteArray,
) {
    init {
        require(bytes.isNotEmpty())
    }
}

public data class AtomicPromotionControlResult(
    val promotedByteCount: Long,
    val storedExactByteCount: Long,
    val temporaryFileRemoved: Boolean,
    val exactMismatchRejected: Boolean,
    val exactMismatchLeftNoDestination: Boolean,
    val duplicateDeliveryWasIdempotent: Boolean,
)

public data class OfflinePlaybackControlResult(
    val plan: LocalPlaybackPlan,
    val loadedBytes: ByteArray,
)

/** Executable controls use the same SQL store, filesystem, validator, and promotion path as core. */
public object DownloadPolicyContract {
    public fun validatedAtomicPromotion(
        payload: DownloadControlPayload,
    ): AtomicPromotionControlResult {
        val environment = createDownloadControlEnvironment()
        try {
            val engine = environment.engine
            engine.reconcile(emptyList(), mapOf(payload.serverId to 1L))
            val row = engine.enqueue(payload.request(rawId = payload.rawId))
            engine.writeCompletedTemporaryFile(row.downloadId, payload.bytes)
            val promoted = engine.promote(
                row.downloadId,
                DownloadResponseMetadata(payload.contentType, payload.contentLength),
            ) as? DownloadPromotionResult.Promoted
                ?: error("control payload did not promote")
            val tempRemoved = engine.temporaryFilePath(row.downloadId).let { path ->
                !okio.FileSystem.SYSTEM.exists(path.toPath())
            }

            val mismatchLength = payload.bytes.size.toLong() + 1L
            val mismatch = engine.enqueue(
                payload.request(rawId = "${payload.rawId}:exact-mismatch", exactLength = mismatchLength),
            )
            engine.writeCompletedTemporaryFile(mismatch.downloadId, payload.bytes)
            val mismatchResult = engine.promote(
                mismatch.downloadId,
                DownloadResponseMetadata(
                    payload.contentType,
                    PlaybackContentLength.Exact(mismatchLength),
                ),
            )
            val mismatchPlan = engine.offlinePlaybackPlan(mismatch.identity)

            val duplicate = engine.promote(
                row.downloadId,
                DownloadResponseMetadata(payload.contentType, payload.contentLength),
            )
            return AtomicPromotionControlResult(
                promotedByteCount = payload.bytes.size.toLong(),
                storedExactByteCount = promoted.record.exactByteLength
                    ?: error("promotion failed to record observed exact length"),
                temporaryFileRemoved = tempRemoved,
                exactMismatchRejected = mismatchResult is DownloadPromotionResult.Rejected,
                exactMismatchLeftNoDestination = mismatchPlan is OfflinePlaybackPlanResult.NotDownloaded,
                duplicateDeliveryWasIdempotent = duplicate is DownloadPromotionResult.AlreadyPromoted,
            )
        } finally {
            environment.close()
        }
    }

    public fun offlinePlayback(payload: DownloadControlPayload): OfflinePlaybackControlResult {
        val environment = createDownloadControlEnvironment()
        try {
            val engine = environment.engine
            engine.reconcile(emptyList(), mapOf(payload.serverId to 1L))
            val row = engine.enqueue(payload.request(rawId = payload.rawId))
            engine.writeCompletedTemporaryFile(row.downloadId, payload.bytes)
            check(
                engine.promote(
                    row.downloadId,
                    DownloadResponseMetadata(payload.contentType, payload.contentLength),
                ) is DownloadPromotionResult.Promoted,
            )
            val plan = (engine.offlinePlaybackPlan(row.identity) as? OfflinePlaybackPlanResult.Available)
                ?.plan ?: error("promoted download produced no local plan")
            val loaded = engine.loadOffline(plan) as? OfflinePlaybackLoadResult.Audio
                ?: error("local plan did not load from the promoted file")
            return OfflinePlaybackControlResult(plan, loaded.bytes)
        } finally {
            environment.close()
        }
    }
}

private fun DownloadControlPayload.request(
    rawId: String,
    exactLength: Long? = null,
): DownloadRequest = DownloadRequest(
    identity = DownloadIdentity(serverId, rawId, transcodeProfile),
    expectedContainer = container,
    declaredContentLength = exactLength?.let(PlaybackContentLength::Exact) ?: contentLength,
    serverSnapshot = DownloadServerSnapshot(
        durationMilliseconds = null,
        sizeBytes = bytes.size.toLong(),
    ),
    credentialGeneration = 1,
    wallClockMilliseconds = 1_800_000_000_000,
)
