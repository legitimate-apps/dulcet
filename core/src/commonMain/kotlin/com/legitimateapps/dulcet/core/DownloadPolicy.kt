package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.math.max
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Opaque server and provider identifiers remain strings throughout the download subsystem. */
public data class DownloadIdentity(
    val serverId: String,
    val rawId: String,
    val transcodeProfile: String,
) {
    init {
        require(serverId.isNotBlank())
        require(rawId.isNotBlank())
        require(transcodeProfile.isNotBlank())
    }

    public val isTranscoded: Boolean get() = transcodeProfile != ORIGINAL_PROFILE

    public companion object {
        public const val ORIGINAL_PROFILE: String = "original"
    }
}

public data class DownloadId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

public enum class DownloadState {
    Queued,
    Downloading,
    Interrupted,
    Complete,
    Stale,
}

public data class DownloadServerSnapshot(
    val durationMilliseconds: Long?,
    val sizeBytes: Long?,
) {
    init {
        require(durationMilliseconds == null || durationMilliseconds >= 0)
        require(sizeBytes == null || sizeBytes >= 0)
    }
}

public data class DownloadRequest(
    val identity: DownloadIdentity,
    val expectedContainer: AudioContainer,
    val declaredContentLength: PlaybackContentLength?,
    val serverSnapshot: DownloadServerSnapshot,
    val credentialGeneration: Long,
    val wallClockMilliseconds: Long,
) {
    init {
        require(credentialGeneration >= 0)
    }
}

public data class DownloadRecord(
    val identity: DownloadIdentity,
    val downloadId: DownloadId,
    val state: DownloadState,
    val fileRelativePath: String,
    /** Exact server length before promotion, then the exact observed closed-file length. */
    val exactByteLength: Long?,
    val fileSizeBytes: Long,
    val expectedContainer: AudioContainer,
    val serverSnapshot: DownloadServerSnapshot,
    val enqueueSequence: Long,
    val credentialGeneration: Long,
    val retryAttempt: Long,
    val retryNotBeforeWallClock: Long?,
    val updatedAtWallClock: Long,
    val platformResumeData: ByteArray?,
    val resumeDataCreatedAtWallClock: Long?,
) {
    init {
        require(fileSizeBytes >= 0)
        require(enqueueSequence >= 0)
        require(credentialGeneration >= 0)
        require(retryAttempt >= 0)
    }

    override fun equals(other: Any?): Boolean = other is DownloadRecord &&
        identity == other.identity &&
        downloadId == other.downloadId &&
        state == other.state &&
        fileRelativePath == other.fileRelativePath &&
        exactByteLength == other.exactByteLength &&
        fileSizeBytes == other.fileSizeBytes &&
        expectedContainer == other.expectedContainer &&
        serverSnapshot == other.serverSnapshot &&
        enqueueSequence == other.enqueueSequence &&
        credentialGeneration == other.credentialGeneration &&
        retryAttempt == other.retryAttempt &&
        retryNotBeforeWallClock == other.retryNotBeforeWallClock &&
        updatedAtWallClock == other.updatedAtWallClock &&
        platformResumeData.contentEqualsNullable(other.platformResumeData) &&
        resumeDataCreatedAtWallClock == other.resumeDataCreatedAtWallClock

    override fun hashCode(): Int = downloadId.hashCode()
}

public data class OutstandingDownloadTask(val downloadId: DownloadId)

public data class DownloadReconciliationResult(
    val taskIdsToCancel: Set<DownloadId>,
    val interruptedRows: Set<DownloadId>,
    val recoveredPromotions: Set<DownloadId>,
    val deletedTemporaryFiles: Set<DownloadId>,
)

public sealed interface DownloadScheduleResult {
    public data class Start(
        val record: DownloadRecord,
        /** Stable task identifier; executors must also persist this as their platform task tag. */
        val platformTaskIdentifier: String,
        val temporaryFilePath: String,
    ) : DownloadScheduleResult

    public data object NothingQueued : DownloadScheduleResult
    public data object DiskBudgetExceeded : DownloadScheduleResult
    public data object TranscodeBudgetReservedForPlayback : DownloadScheduleResult
}

public data class DownloadSchedulingContext(
    val serverId: String,
    val wallClockMilliseconds: Long,
    val diskBudgetBytes: Long,
    val unknownLengthReservationBytes: Long,
    val activeTranscodes: ActiveTranscodeCounts,
    /** True even for direct playback, so a future transcode slot remains available. */
    val playbackSessionActive: Boolean = false,
) {
    init {
        require(serverId.isNotBlank())
        require(diskBudgetBytes >= 0)
        require(unknownLengthReservationBytes >= 0)
    }
}

public sealed interface DownloadPromotionResult {
    public data class Promoted(val record: DownloadRecord) : DownloadPromotionResult
    public data class AlreadyPromoted(val record: DownloadRecord) : DownloadPromotionResult
    public data class Rejected(val error: DomainError) : DownloadPromotionResult
    public data object MissingTemporaryFile : DownloadPromotionResult
    public data object UnknownDownload : DownloadPromotionResult
}

public data class DownloadResponseMetadata(
    val contentType: String?,
    val contentLength: PlaybackContentLength?,
)

public class LocalPlaybackPlan internal constructor(
    val downloadId: DownloadId,
    val identity: DownloadIdentity,
    val container: AudioContainer,
    val exactByteLength: Long,
    internal val absolutePath: String,
) : PlaybackPlan

public sealed interface OfflinePlaybackPlanResult {
    public data class Available(val plan: LocalPlaybackPlan) : OfflinePlaybackPlanResult
    public data object NotDownloaded : OfflinePlaybackPlanResult
    public data object MissingFile : OfflinePlaybackPlanResult
}

public sealed interface OfflinePlaybackLoadResult {
    public data class Audio(val bytes: ByteArray, val plan: LocalPlaybackPlan) : OfflinePlaybackLoadResult
    public data object MissingFile : OfflinePlaybackLoadResult
    public data object InvalidFile : OfflinePlaybackLoadResult
}

public enum class OfflinePlaybackVerification {
    Valid,
    MissingFile,
    InvalidFile,
}

public sealed interface DownloadResumeDecision {
    public data class Resume(val data: ByteArray) : DownloadResumeDecision
    public data object RestartFromZero : DownloadResumeDecision
}

public data class DownloadRetryDecision(
    val attempt: Long,
    val retryAtWallClock: Long,
)

internal class DownloadPolicyEngine(
    private val database: DulcetDatabase,
    private val files: DownloadFileStore,
    private val transcodeBudget: PlaybackTranscodeBudget = PlaybackTranscodeBudget(),
) {
    private val store = SqlDownloadStore(database)
    private var reconciled = false

    /**
     * Relaunch reconciliation (spec §14.5): matches the platform's outstanding tasks to rows, and
     * deletes every temporary file no outstanding task owns — except one that [resumesTemporaryFile]
     * accepts. Only a row whose transfer ENDED and was recorded is asked: `Queued` or `Interrupted`
     * before reconciliation, with no destination file and no credential change. A row still
     * `Downloading` with no task died mid-write, so its file is never offered for resumption. The
     * default keeps none, which is Apple's behaviour: its resumable state is the OS's resume data,
     * not a file of ours.
     */
    fun reconcile(
        outstandingTasks: List<OutstandingDownloadTask>,
        currentCredentialGenerations: Map<String, Long>,
        resumesTemporaryFile: (DownloadRecord) -> Boolean = { false },
    ): DownloadReconciliationResult {
        check(!reconciled) { "download reconciliation may run only once per subsystem launch" }
        require(currentCredentialGenerations.values.all { it >= 0 })
        files.ensureDirectories()

        val rows = store.all()
        val rowsById = rows.associateBy(DownloadRecord::downloadId)
        val activeTaskIds = outstandingTasks.map(OutstandingDownloadTask::downloadId).toSet()
        val cancel = activeTaskIds.filterTo(mutableSetOf()) { it !in rowsById }
        val interrupted = mutableSetOf<DownloadId>()
        val recovered = mutableSetOf<DownloadId>()
        val resumable = mutableSetOf<DownloadId>()

        rows.forEach { row ->
            val currentCredentialGeneration = currentCredentialGenerations[row.identity.serverId]
            if (
                currentCredentialGeneration != null &&
                currentCredentialGeneration != row.credentialGeneration
            ) {
                if (row.downloadId in activeTaskIds) cancel += row.downloadId
                store.requeueForCredentialChange(row, currentCredentialGeneration)
                interrupted += row.downloadId
                return@forEach
            }

            if (files.destinationExists(row)) {
                if (recoverDestination(row)) recovered += row.downloadId
                return@forEach
            }

            if (row.state == DownloadState.Complete || row.state == DownloadState.Stale) {
                store.markInterrupted(row.downloadId)
                interrupted += row.downloadId
            } else if (row.state == DownloadState.Downloading && row.downloadId !in activeTaskIds) {
                store.markInterrupted(row.downloadId)
                interrupted += row.downloadId
            } else if (
                (row.state == DownloadState.Queued || row.state == DownloadState.Interrupted) &&
                resumesTemporaryFile(row)
            ) {
                resumable += row.downloadId
            }
        }

        val deletedTemps = files.deleteUnownedTemporaryFiles(activeTaskIds + resumable)
        reconciled = true
        return DownloadReconciliationResult(cancel, interrupted, recovered, deletedTemps)
    }

    fun enqueue(request: DownloadRequest): DownloadRecord {
        requireReconciled()
        store.byIdentity(request.identity)?.let { return it }
        val downloadId = DownloadId("download:${secureRandomBytes(16).toLowerHex()}")
        val destination = "${downloadId.value}.${request.expectedContainer.fileExtension()}"
        return store.insert(request, downloadId, destination)
    }

    fun schedule(context: DownloadSchedulingContext): DownloadScheduleResult {
        requireReconciled()
        val rows = store.forServer(context.serverId)
        val eligible = rows.firstOrNull { row ->
            row.state in setOf(DownloadState.Queued, DownloadState.Interrupted) &&
                (row.retryNotBeforeWallClock == null ||
                    row.retryNotBeforeWallClock <= context.wallClockMilliseconds)
        } ?: return DownloadScheduleResult.NothingQueued

        val usedBytes = rows
            .filter { it.state == DownloadState.Complete || it.state == DownloadState.Stale }
            .sumOf(DownloadRecord::fileSizeBytes)
        val reservation = eligible.exactByteLength
            ?: eligible.serverSnapshot.sizeBytes
            ?: context.unknownLengthReservationBytes
        val remainingBytes = (context.diskBudgetBytes - usedBytes).coerceAtLeast(0)
        if (reservation > remainingBytes) {
            return DownloadScheduleResult.DiskBudgetExceeded
        }
        if (
            eligible.identity.isTranscoded &&
            context.playbackSessionActive &&
            context.activeTranscodes.currentPlayback +
            context.activeTranscodes.preload +
            context.activeTranscodes.downloads >=
            (transcodeBudget.maximumConcurrentTranscodes - 1).coerceAtLeast(0)
        ) {
            return DownloadScheduleResult.TranscodeBudgetReservedForPlayback
        }
        if (
            !transcodeBudget.maySchedule(
                PlaybackWireRequestPurpose.Download,
                eligible.identity.isTranscoded,
                context.activeTranscodes,
            )
        ) {
            return DownloadScheduleResult.TranscodeBudgetReservedForPlayback
        }
        store.markStarted(eligible.downloadId)
        val started = requireNotNull(store.byId(eligible.downloadId))
        return DownloadScheduleResult.Start(
            record = started,
            platformTaskIdentifier = started.downloadId.value,
            temporaryFilePath = files.temporaryPath(started).toString(),
        )
    }

    fun nextRetryNotBefore(serverId: String): Long? {
        requireReconciled()
        require(serverId.isNotBlank())
        return store.forServer(serverId)
            .asSequence()
            .filter { it.state == DownloadState.Queued || it.state == DownloadState.Interrupted }
            .mapNotNull(DownloadRecord::retryNotBeforeWallClock)
            .filter { it != Long.MAX_VALUE }
            .minOrNull()
    }

    fun temporaryFilePath(downloadId: DownloadId): String {
        requireReconciled()
        return store.byId(downloadId)?.let(files::temporaryPath)?.toString()
            ?: error("unknown download")
    }

    fun destinationExists(downloadId: DownloadId): Boolean {
        requireReconciled()
        return store.byId(downloadId)?.let(files::destinationExists) ?: false
    }

    fun temporaryExists(downloadId: DownloadId): Boolean {
        requireReconciled()
        return store.byId(downloadId)?.let(files::temporaryExists) ?: false
    }

    fun writeCompletedTemporaryFile(downloadId: DownloadId, bytes: ByteArray) {
        requireReconciled()
        val row = store.byId(downloadId) ?: error("unknown download")
        files.writeTemporary(row, bytes)
    }

    fun promote(
        downloadId: DownloadId,
        metadata: DownloadResponseMetadata,
    ): DownloadPromotionResult {
        requireReconciled()
        val row = store.byId(downloadId) ?: return DownloadPromotionResult.UnknownDownload
        if (files.destinationExists(row)) {
            val recovered = recoverDestination(row)
            val completed = store.byId(downloadId)
            return if (recovered && completed != null) {
                DownloadPromotionResult.AlreadyPromoted(completed)
            } else {
                DownloadPromotionResult.Rejected(DomainError.Protocol.UnexpectedBinary)
            }
        }
        if (!files.temporaryExists(row)) return DownloadPromotionResult.MissingTemporaryFile

        // The executor has closed the response body before delivery reaches this method, so the temp
        // file's on-disk length is the terminal-body boundary for a response that declared no exact
        // length. Only a bounded prefix is read: a file is never loaded whole, whatever its size
        // (spec §14.5).
        val validation = validateDownloadFile(
            files = files,
            path = files.temporaryPath(row),
            container = row.expectedContainer,
            contentType = metadata.contentType,
            contentLength = metadata.contentLength,
        )
        if (validation.result is PlaybackStreamValidationResult.Failure) {
            return DownloadPromotionResult.Rejected(validation.result.error)
        }

        // This ordering is the integrity boundary: no destination can appear before validation.
        files.atomicPromote(row)
        store.markComplete(downloadId, validation.fileLength)
        return DownloadPromotionResult.Promoted(requireNotNull(store.byId(downloadId)))
    }

    fun offlinePlaybackPlan(identity: DownloadIdentity): OfflinePlaybackPlanResult {
        requireReconciled()
        val row = store.byIdentity(identity) ?: return OfflinePlaybackPlanResult.NotDownloaded
        if (row.state != DownloadState.Complete && row.state != DownloadState.Stale) {
            return OfflinePlaybackPlanResult.NotDownloaded
        }
        if (!files.destinationExists(row)) return OfflinePlaybackPlanResult.MissingFile
        val exactLength = row.exactByteLength ?: return OfflinePlaybackPlanResult.MissingFile
        return OfflinePlaybackPlanResult.Available(
            LocalPlaybackPlan(
                downloadId = row.downloadId,
                identity = row.identity,
                container = row.expectedContainer,
                exactByteLength = exactLength,
                absolutePath = files.destinationPath(row).toString(),
            ),
        )
    }

    /**
     * Checks [plan]'s file from a bounded prefix and its on-disk length, never reading it whole. A
     * platform hands the path to its player afterwards; this is the check that must stay cheap.
     */
    fun verifyOffline(plan: LocalPlaybackPlan): OfflinePlaybackVerification {
        requireReconciled()
        val path = plan.absolutePath.toPath()
        if (!files.exists(path)) return OfflinePlaybackVerification.MissingFile
        val validation = validateDownloadFile(
            files = files,
            path = path,
            container = plan.container,
            contentType = "application/octet-stream",
            contentLength = PlaybackContentLength.Exact(plan.exactByteLength),
        )
        return if (validation.result is PlaybackStreamValidationResult.Audio) {
            OfflinePlaybackVerification.Valid
        } else {
            OfflinePlaybackVerification.InvalidFile
        }
    }

    /**
     * [verifyOffline], then the file's bytes. This reads the whole file into memory, so it is for the
     * conformance contract and tests only; product code plays from [LocalPlaybackPlan.absolutePath].
     */
    fun loadOffline(plan: LocalPlaybackPlan): OfflinePlaybackLoadResult =
        when (verifyOffline(plan)) {
            OfflinePlaybackVerification.MissingFile -> OfflinePlaybackLoadResult.MissingFile
            OfflinePlaybackVerification.InvalidFile -> OfflinePlaybackLoadResult.InvalidFile
            OfflinePlaybackVerification.Valid -> files.readOrNull(plan.absolutePath.toPath())
                ?.let { OfflinePlaybackLoadResult.Audio(it, plan) }
                ?: OfflinePlaybackLoadResult.MissingFile
        }

    fun recordFailure(
        downloadId: DownloadId,
        error: DomainError,
        wallClockMilliseconds: Long,
    ): DownloadRetryDecision? {
        requireReconciled()
        val row = store.byId(downloadId) ?: return null
        val nextAttempt = row.retryAttempt + 1
        val retryDelay = when (error) {
            DomainError.Auth.InvalidCredentials,
            DomainError.Auth.Forbidden,
            -> Long.MAX_VALUE
            is DomainError.Server.Busy -> max(
                retryBackoffMilliseconds(nextAttempt),
                error.retryAfter?.inWholeMilliseconds ?: 0,
            )
            else -> retryBackoffMilliseconds(nextAttempt)
        }
        val retryAt = if (retryDelay == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            wallClockMilliseconds.saturatingAdd(retryDelay)
        }
        store.recordRetry(row, nextAttempt, retryAt, wallClockMilliseconds)
        store.markInterrupted(downloadId)
        return DownloadRetryDecision(nextAttempt, retryAt)
    }

    fun recordResumeData(downloadId: DownloadId, data: ByteArray, wallClockMilliseconds: Long) {
        requireReconciled()
        require(data.isNotEmpty())
        store.updateResumeData(downloadId, data, wallClockMilliseconds)
    }

    fun resumeDecision(downloadId: DownloadId, wallClockMilliseconds: Long): DownloadResumeDecision {
        requireReconciled()
        val row = store.byId(downloadId) ?: return DownloadResumeDecision.RestartFromZero
        val data = row.platformResumeData
        val created = row.resumeDataCreatedAtWallClock
        if (
            data == null ||
            created == null ||
            wallClockMilliseconds - created > RESUME_DATA_MAX_AGE.inWholeMilliseconds
        ) {
            store.clearResumeData(downloadId)
            return DownloadResumeDecision.RestartFromZero
        }
        return DownloadResumeDecision.Resume(data.copyOf())
    }

    /** Forgets [downloadId]'s resume data without changing its state. */
    fun clearResumeData(downloadId: DownloadId) {
        requireReconciled()
        store.clearResumeData(downloadId)
    }

    fun rejectResumeData(downloadId: DownloadId) {
        requireReconciled()
        store.clearResumeData(downloadId)
        store.markQueued(downloadId)
    }

    fun reconcileServerItem(
        identity: DownloadIdentity,
        snapshot: DownloadServerSnapshot,
        wallClockMilliseconds: Long,
    ): DownloadRecord? {
        requireReconciled()
        val row = store.byIdentity(identity) ?: return null
        val changed = listOf(
            row.serverSnapshot.durationMilliseconds to snapshot.durationMilliseconds,
            row.serverSnapshot.sizeBytes to snapshot.sizeBytes,
        ).any { (before, after) -> before != null && after != null && before != after }
        if (changed && row.state in setOf(DownloadState.Complete, DownloadState.Stale)) {
            store.markStale(row.downloadId)
        }
        store.updateServerSnapshot(row, snapshot, wallClockMilliseconds)
        return store.byId(row.downloadId)
    }

    fun evictAbandonedPartials(wallClockMilliseconds: Long): Set<DownloadId> {
        requireReconciled()
        val evicted = mutableSetOf<DownloadId>()
        store.all().forEach { row ->
            if (
                row.state == DownloadState.Interrupted &&
                wallClockMilliseconds - row.updatedAtWallClock >
                ABANDONED_PARTIAL_MAX_AGE.inWholeMilliseconds
            ) {
                files.deleteTemporary(row)
                store.delete(row.downloadId)
                evicted += row.downloadId
            }
        }
        return evicted
    }

    /**
     * Deletes every durable artifact owned by one account.
     *
     * File deletion intentionally precedes the database transaction. If the process stops between
     * the two phases, the rows retain enough identity to retry the idempotent removal on relaunch;
     * deleting the rows first would orphan private media with no durable owner.
     */
    fun removeAccountData(serverId: String) {
        require(serverId.isNotBlank())
        val rows = store.forServer(serverId)
        rows.forEach { row ->
            files.deleteTemporary(row)
            files.deleteDestination(row)
        }
        val queries = database.serverDataQueries
        database.transaction {
            queries.deleteMutationOutboxForServer(serverId)
            queries.deleteDownloadsForServer(serverId)
            queries.deleteResumePositionsForServer(serverId)
            queries.deleteScrobblesForServer(serverId)
            queries.deleteQueueEntriesForServer(serverId)
            queries.deleteActiveQueueForServer(serverId)
            queries.deleteQueueStateForServer(serverId)
            queries.deletePlaylistEntriesForServer(serverId)
            queries.deletePlaylistsForServer(serverId)
            queries.deleteStarredForServer(serverId)
            queries.deleteCreditsForServer(serverId)
            queries.deleteTracksForServer(serverId)
            queries.deleteAlbumsForServer(serverId)
            queries.deleteArtistsForServer(serverId)
            queries.deleteMusicFoldersForServer(serverId)
            queries.deleteGenresForServer(serverId)
            queries.deleteSyncSeenForServer(serverId)
            queries.deleteSyncCheckpointsForServer(serverId)
            queries.deleteDeletionReconciliationsForServer(serverId)
            queries.deleteSyncGenerationsForServer(serverId)
            queries.deleteSeenCacheForServer(serverId)
        }
        check(queries.countRowsForServer(serverId).executeAsOne().sum == 0L)
    }

    /**
     * Removes one download at the person's request (§14.6: a complete explicit download is evicted
     * only by explicit user action or account removal). Files go first, then the row, for the
     * reason [removeAccountData] gives: a crash between the two leaves a row that names nothing,
     * which the next relaunch reconciliation marks interrupted, never a file with no owner. Returns
     * the removed row, or null when there was none. An executor still writing the row's temporary
     * file afterwards writes under a name no row gives, which the launch sweep deletes (§14.5).
     */
    fun remove(identity: DownloadIdentity): DownloadRecord? {
        requireReconciled()
        val row = store.byIdentity(identity) ?: return null
        files.deleteTemporary(row)
        files.deleteDestination(row)
        store.delete(row.downloadId)
        return row
    }

    /** Every row of one server, in enqueue order. */
    fun records(serverId: String): List<DownloadRecord> {
        requireReconciled()
        require(serverId.isNotBlank())
        return store.forServer(serverId)
    }

    fun record(downloadId: DownloadId): DownloadRecord? {
        requireReconciled()
        return store.byId(downloadId)
    }

    fun record(identity: DownloadIdentity): DownloadRecord? {
        requireReconciled()
        return store.byIdentity(identity)
    }

    private fun recoverDestination(row: DownloadRecord): Boolean {
        val validation = validateDownloadFile(
            files = files,
            path = files.destinationPath(row),
            container = row.expectedContainer,
            contentType = "application/octet-stream",
            contentLength = row.exactByteLength?.let(PlaybackContentLength::Exact),
        )
        return if (validation.result is PlaybackStreamValidationResult.Audio) {
            store.markComplete(row.downloadId, validation.fileLength)
            true
        } else {
            files.deleteDestination(row)
            store.markInterrupted(row.downloadId)
            false
        }
    }

    private fun requireReconciled() {
        check(reconciled) { "download reconciliation must run before subsystem use" }
    }

    private companion object {
        val RESUME_DATA_MAX_AGE = 7.days
        val ABANDONED_PARTIAL_MAX_AGE = 30.days
    }
}

internal class DownloadFileStore(
    rootPath: String,
    private val fileSystem: FileSystem,
) {
    private val root = rootPath.toPath(normalize = true)
    private val temporaryRoot = root / ".tmp"

    fun ensureDirectories() {
        fileSystem.createDirectories(root)
        fileSystem.createDirectories(temporaryRoot)
    }

    fun temporaryPath(row: DownloadRecord): Path = temporaryRoot / "${row.downloadId.value}.partial"
    fun destinationPath(row: DownloadRecord): Path = root / row.fileRelativePath
    fun temporaryExists(row: DownloadRecord): Boolean = fileSystem.exists(temporaryPath(row))
    fun destinationExists(row: DownloadRecord): Boolean = fileSystem.exists(destinationPath(row))

    fun writeTemporary(row: DownloadRecord, bytes: ByteArray) {
        fileSystem.write(temporaryPath(row)) { write(bytes) }
    }

    fun exists(path: Path): Boolean = fileSystem.exists(path)

    /** The file's length on disk, or null when it is gone. */
    fun lengthOrNull(path: Path): Long? = fileSystem.metadataOrNull(path)?.size

    /** At most [maxBytes] leading bytes of [path], streamed; never the whole of a larger file. */
    fun readPrefix(path: Path, maxBytes: Long): ByteArray = fileSystem.read(path) {
        val prefix = Buffer()
        while (prefix.size < maxBytes) {
            if (read(prefix, maxBytes - prefix.size) == -1L) break
        }
        prefix.readByteArray()
    }

    fun readOrNull(path: Path): ByteArray? = if (fileSystem.exists(path)) {
        fileSystem.read(path) { readByteArray() }
    } else {
        null
    }

    fun atomicPromote(row: DownloadRecord) {
        fileSystem.atomicMove(temporaryPath(row), destinationPath(row))
    }

    fun deleteTemporary(row: DownloadRecord) {
        fileSystem.delete(temporaryPath(row), mustExist = false)
    }

    fun deleteDestination(row: DownloadRecord) {
        fileSystem.delete(destinationPath(row), mustExist = false)
    }

    /** Every file in the download directory and in its temporary directory, for [deleteUnnamed]. */
    fun listFiles(): List<Path> =
        (fileSystem.listOrNull(root).orEmpty() + fileSystem.listOrNull(temporaryRoot).orEmpty())
            .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true }

    /**
     * Deletes each of [listed] that no row in [named] names, as its destination or as its temporary
     * file, and returns how many it deleted. [listed] must be taken BEFORE [named] is read: a row is
     * written before any of its files, so a file listed first whose row appears meanwhile is named.
     */
    fun deleteUnnamed(listed: List<Path>, named: List<Pair<DownloadId, String>>): Int {
        val kept = named.flatMapTo(mutableSetOf()) { (id, relativePath) ->
            listOf(root / relativePath, temporaryRoot / "${id.value}.partial")
        }
        val unnamed = listed.filter { it !in kept }
        unnamed.forEach { fileSystem.delete(it, mustExist = false) }
        return unnamed.size
    }

    /** Deletes every `<id>.partial` whose id is not in [keptIds]: those an outstanding task owns or a row resumes. */
    fun deleteUnownedTemporaryFiles(keptIds: Set<DownloadId>): Set<DownloadId> {
        val deleted = mutableSetOf<DownloadId>()
        fileSystem.listOrNull(temporaryRoot).orEmpty().forEach { path ->
            val name = path.name
            if (!name.endsWith(".partial")) return@forEach
            val id = DownloadId(name.removeSuffix(".partial"))
            if (id !in keptIds) {
                fileSystem.delete(path, mustExist = false)
                deleted += id
            }
        }
        return deleted
    }
}

private class SqlDownloadStore(private val database: DulcetDatabase) {
    private val queries = database.downloadsQueries

    fun insert(request: DownloadRequest, id: DownloadId, relativePath: String): DownloadRecord {
        val sequence = (queries.selectMaximumEnqueueSequence(request.identity.serverId)
            .executeAsOne().max ?: -1L) + 1L
        database.transaction {
            queries.insertDownload(
                server_id = request.identity.serverId,
                raw_id = request.identity.rawId,
                transcode_profile = request.identity.transcodeProfile,
                download_id = id.value,
                file_relative_path = relativePath,
                expected_byte_length = (request.declaredContentLength as? PlaybackContentLength.Exact)
                    ?.byteCount,
            )
            queries.insertDownloadPolicyState(
                server_id = request.identity.serverId,
                raw_id = request.identity.rawId,
                transcode_profile = request.identity.transcodeProfile,
                expected_container = request.expectedContainer.databaseValue(),
                source_duration_milliseconds = request.serverSnapshot.durationMilliseconds,
                source_size_bytes = request.serverSnapshot.sizeBytes,
                enqueue_sequence = sequence,
                auth_generation = request.credentialGeneration,
                updated_at_wall_clock = request.wallClockMilliseconds,
            )
        }
        return requireNotNull(byId(id))
    }

    fun byIdentity(identity: DownloadIdentity): DownloadRecord? = queries.selectDownloadByIdentity(
        identity.serverId,
        identity.rawId,
        identity.transcodeProfile,
        mapper = ::downloadRecord,
    ).executeAsOneOrNull()

    fun byId(id: DownloadId): DownloadRecord? = queries.selectDownloadById(
        id.value,
        mapper = ::downloadRecord,
    ).executeAsOneOrNull()

    fun forServer(serverId: String): List<DownloadRecord> =
        queries.selectDownloadsForServer(serverId, mapper = ::downloadRecord).executeAsList()

    fun all(): List<DownloadRecord> = queries.selectAllDownloads(mapper = ::downloadRecord)
        .executeAsList()

    fun markStarted(id: DownloadId) { queries.markDownloadStarted(id.value) }
    fun markInterrupted(id: DownloadId) { queries.markDownloadInterrupted(id.value) }
    fun markQueued(id: DownloadId) { queries.markDownloadQueued(id.value) }
    fun markStale(id: DownloadId) { queries.markDownloadStale(id.value) }
    fun markComplete(id: DownloadId, observedLength: Long) {
        queries.markDownloadComplete(observedLength, observedLength, id.value)
    }

    fun updateResumeData(id: DownloadId, data: ByteArray, createdAt: Long) {
        queries.updateDownloadResumeData(data, createdAt, id.value)
    }

    fun clearResumeData(id: DownloadId) { queries.clearDownloadResumeData(id.value) }

    fun recordRetry(row: DownloadRecord, attempt: Long, retryAt: Long, updatedAt: Long) {
        queries.updateDownloadRetry(
            retry_attempt = attempt,
            retry_not_before_wall_clock = retryAt,
            updated_at_wall_clock = updatedAt,
            server_id = row.identity.serverId,
            raw_id = row.identity.rawId,
            transcode_profile = row.identity.transcodeProfile,
        )
    }

    fun requeueForCredentialChange(row: DownloadRecord, generation: Long) {
        database.transaction {
            queries.updateDownloadCredentialGeneration(
                auth_generation = generation,
                updated_at_wall_clock = row.updatedAtWallClock,
                server_id = row.identity.serverId,
                raw_id = row.identity.rawId,
                transcode_profile = row.identity.transcodeProfile,
            )
            queries.resetDownloadRetry(
                updated_at_wall_clock = row.updatedAtWallClock,
                server_id = row.identity.serverId,
                raw_id = row.identity.rawId,
                transcode_profile = row.identity.transcodeProfile,
            )
            queries.markDownloadQueued(row.downloadId.value)
        }
    }

    fun updateServerSnapshot(row: DownloadRecord, snapshot: DownloadServerSnapshot, updatedAt: Long) {
        queries.updateDownloadServerSnapshot(
            source_duration_milliseconds = snapshot.durationMilliseconds,
            source_size_bytes = snapshot.sizeBytes,
            updated_at_wall_clock = updatedAt,
            server_id = row.identity.serverId,
            raw_id = row.identity.rawId,
            transcode_profile = row.identity.transcodeProfile,
        )
    }

    fun delete(id: DownloadId) { queries.deleteDownload(id.value) }
}

private fun downloadRecord(
    serverId: String,
    rawId: String,
    transcodeProfile: String,
    downloadId: String,
    state: String,
    fileRelativePath: String,
    expectedByteLength: Long?,
    fileSizeBytes: Long,
    platformResumeData: ByteArray?,
    resumeDataCreatedAtWallClock: Long?,
    expectedContainer: String,
    sourceDurationMilliseconds: Long?,
    sourceSizeBytes: Long?,
    enqueueSequence: Long,
    credentialGeneration: Long,
    retryAttempt: Long,
    retryNotBeforeWallClock: Long?,
    updatedAtWallClock: Long,
): DownloadRecord = DownloadRecord(
    identity = DownloadIdentity(serverId, rawId, transcodeProfile),
    downloadId = DownloadId(downloadId),
    state = state.toDownloadState(),
    fileRelativePath = fileRelativePath,
    exactByteLength = expectedByteLength,
    fileSizeBytes = fileSizeBytes,
    expectedContainer = expectedContainer.toAudioContainer(),
    serverSnapshot = DownloadServerSnapshot(sourceDurationMilliseconds, sourceSizeBytes),
    enqueueSequence = enqueueSequence,
    credentialGeneration = credentialGeneration,
    retryAttempt = retryAttempt,
    retryNotBeforeWallClock = retryNotBeforeWallClock,
    updatedAtWallClock = updatedAtWallClock,
    platformResumeData = platformResumeData,
    resumeDataCreatedAtWallClock = resumeDataCreatedAtWallClock,
)

/** A validation of a file on disk, with the length it was measured at. */
private class DownloadFileValidation(val result: PlaybackStreamValidationResult, val fileLength: Long)

/**
 * Validates the file at [path] from a bounded prefix plus its on-disk length: the envelope and
 * signature checks see at most [DOWNLOAD_VALIDATION_WINDOW_BYTES] leading bytes (widened only as far
 * as an ID3v2 tag needs, up to [DOWNLOAD_VALIDATION_CEILING_BYTES]), and the declared exact length is
 * compared with the length on disk. Memory stays bounded however large the file is.
 */
private fun validateDownloadFile(
    files: DownloadFileStore,
    path: Path,
    container: AudioContainer,
    contentType: String?,
    contentLength: PlaybackContentLength?,
): DownloadFileValidation {
    val fileLength = files.lengthOrNull(path) ?: 0L
    var prefix = files.readPrefix(path, DOWNLOAD_VALIDATION_WINDOW_BYTES)
    val needed = prefix.audioSignatureWindowBytes().coerceAtMost(DOWNLOAD_VALIDATION_CEILING_BYTES)
    if (needed > prefix.size && prefix.size.toLong() < fileLength) {
        prefix = files.readPrefix(path, needed)
    }
    return DownloadFileValidation(
        result = validateDownloadBytes(prefix, fileLength, container, contentType, contentLength),
        fileLength = fileLength,
    )
}

/** Enough for every envelope and every audio signature that does not follow an ID3v2 tag. */
internal const val DOWNLOAD_VALIDATION_WINDOW_BYTES = 64L * 1024

/** The most a large ID3v2 tag of embedded artwork may widen the validation window to. */
internal const val DOWNLOAD_VALIDATION_CEILING_BYTES = 16L * 1024 * 1024

private fun validateDownloadBytes(
    bytes: ByteArray,
    totalLength: Long,
    container: AudioContainer,
    contentType: String?,
    contentLength: PlaybackContentLength?,
): PlaybackStreamValidationResult = PlaybackStreamValidator.validate(
    response = AuthenticatedEndpointResponse(
        statusCode = 200,
        body = bytes,
        redactedUrl = "<download>",
        headers = AuthenticatedEndpointResponseHeaders(
            contentType = contentType,
            contentLength = contentLength,
            retryAfter = null,
            acceptRanges = null,
            contentRange = null,
        ),
        requestTrace = RequestTrace.observed(
            endpoint = "download",
            method = "LOCAL",
            redactedUrl = "<download>",
            authenticationLocation = AuthenticationLocation.None,
            queryAuthenticationParameters = emptySet(),
            formAuthenticationParameters = emptySet(),
            channels = emptySet(),
            requestedProtocolVersion = null,
            saltFingerprint = null,
        ),
    ),
    expectedContainer = container,
    totalBodyLength = totalLength,
)

private fun AudioContainer.databaseValue(): String = when (this) {
    AudioContainer.Mp3 -> "mp3"
    AudioContainer.Mp4 -> "mp4"
    AudioContainer.Wav -> "wav"
    AudioContainer.Flac -> "flac"
    AudioContainer.Ogg -> "ogg"
    AudioContainer.AdtsAac -> "adts_aac"
}

private fun String.toAudioContainer(): AudioContainer = when (this) {
    "mp3" -> AudioContainer.Mp3
    "mp4" -> AudioContainer.Mp4
    "wav" -> AudioContainer.Wav
    "flac" -> AudioContainer.Flac
    "ogg" -> AudioContainer.Ogg
    "adts_aac" -> AudioContainer.AdtsAac
    else -> error("unknown persisted audio container")
}

private fun AudioContainer.fileExtension(): String = when (this) {
    AudioContainer.Mp3 -> "mp3"
    AudioContainer.Mp4 -> "m4a"
    AudioContainer.Wav -> "wav"
    AudioContainer.Flac -> "flac"
    AudioContainer.Ogg -> "ogg"
    AudioContainer.AdtsAac -> "aac"
}

private fun String.toDownloadState(): DownloadState = when (this) {
    "queued" -> DownloadState.Queued
    "downloading" -> DownloadState.Downloading
    "interrupted" -> DownloadState.Interrupted
    "complete" -> DownloadState.Complete
    "stale" -> DownloadState.Stale
    else -> error("unknown persisted download state")
}

private fun retryBackoffMilliseconds(attempt: Long): Long {
    require(attempt > 0)
    val exponent = (attempt - 1).coerceAtMost(8).toInt()
    return (5.seconds * (1 shl exponent)).coerceAtMost(15.minutes).inWholeMilliseconds
}

private fun Long.saturatingAdd(other: Long): Long =
    if (other > 0 && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
}
