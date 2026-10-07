package com.legitimateapps.dulcet.core

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** What a person sees of one download. `Stale` still plays; the server's copy changed since. */
public enum class AndroidDownloadState { Queued, Downloading, Interrupted, Downloaded, Stale }

/**
 * One track's download as the shells draw it. [bytesWritten] and [totalBytes] describe the transfer
 * under way ([totalBytes] null when the server named no exact length). [needsAttention] is a download
 * the policy will not retry by itself — the server refused the account — so the person is told
 * rather than shown a download that never moves.
 */
public data class AndroidDownloadStatus(
    val rawId: String,
    val state: AndroidDownloadState,
    val bytesWritten: Long = 0,
    val totalBytes: Long? = null,
    val retryNotBeforeWallClock: Long? = null,
    val needsAttention: Boolean = false,
) {
    val playsOffline: Boolean get() = state == AndroidDownloadState.Downloaded || state == AndroidDownloadState.Stale
}

/** A track to download: its opaque id and the container the server said it is stored in. */
public data class AndroidDownloadItem(
    val rawId: String,
    val sourceContainer: AudioContainer,
    val durationMilliseconds: Long?,
)

/**
 * The platform executor's task registry (spec §14.5): WorkManager in the app, whose tag for each task
 * is the row's `downloadId`, so a relaunched process maps a task back to its row. The core decides
 * what runs and when; this only starts, cancels and enumerates tasks.
 */
public interface AndroidDownloadTasks {
    /** The downloadIds of every task the platform still holds unfinished, from any earlier process. */
    public suspend fun outstanding(): List<String>

    /** Starts the task for [downloadId]; the task calls [AndroidDownloadController.runTask]. */
    public fun start(downloadId: String)

    public fun cancel(downloadId: String)

    /** Asks for a scheduling pass ([AndroidDownloadController.scheduleNext]) no earlier than [wallClockMilliseconds]. */
    public fun wakeAt(wallClockMilliseconds: Long)
}

/** How one executor task ended. The row, not this value, is the durable record. */
public enum class AndroidDownloadRunOutcome {
    /** Validated and atomically promoted, or found already promoted. */
    Downloaded,

    /** Refused or failed; the policy recorded its retry boundary. */
    WillRetry,

    /** Not a download this task may run: removed, finished, or never started. */
    NotRunnable,
}

/**
 * The Android executor over the core's download policy (spec §14.5), for one account.
 *
 * The core owns every decision — identity, order, disk budget, retry and backoff, validation and
 * atomic promotion — through [DownloadPolicyEngine] and the `download` table. This class owns only
 * what is Android's: the HTTP transfer into the row's temporary file, the WorkManager tasks (through
 * [AndroidDownloadTasks]) and the process's view of progress. Its guarantees:
 *
 * - **Relaunch reconciliation runs first.** Nothing touches the subsystem until the platform's
 *   outstanding tasks have been matched to rows ([awaitReconciled]); tasks with no row are cancelled.
 * - **A file is written only after its row, and only under the name the row gives it**
 *   (`<downloadId>.partial` in the temporary directory, the row's `file_relative_path` once
 *   promoted), which is what the launch sweep of spec §14.7 requires.
 * - **Nothing is promoted unvalidated.** The temporary file passes the §12.4 validator — envelope
 *   detection first, then the container signature, from a bounded prefix — and its on-disk length
 *   must match an exact server length before the atomic rename. A server error envelope delivered
 *   with HTTP 200 is rejected there. No file is ever read whole.
 * - **Every download is the original file**: legacy `stream` with `format=raw`, which a transcoding
 *   the server has configured for this player cannot override.
 * - **A stopped transfer resumes.** The executor's work may be stopped at any point — WorkManager
 *   stops ordinary work after about ten minutes — and runs again; the next run asks for the rest of
 *   the file with a `Range` request from the temporary file's length, and appends only when the
 *   server answers with exactly that range of a file of the length it first declared. Any other
 *   answer restarts from byte zero, so a changed file is never stitched onto an old prefix.
 * - **One transfer at a time**, so a download never competes with playback for more than one
 *   connection.
 *
 * Every public call is safe from any thread; database work is confined to one thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class AndroidDownloadController internal constructor(
    private val account: PlaybackEndpointAccount,
    openStore: () -> DulcetDatabaseStore,
    private val downloadRoot: File,
    private val tasks: AndroidDownloadTasks,
    private val wall: () -> Long,
    /** Bytes the downloads may occupy in all, completed ones included (spec §14.6). */
    private val diskBudgetBytes: (usedBytes: Long) -> Long,
    /** Opens the transfer for a resolved plan; production signs and sends it over HTTP. */
    private val openTransfer: ((RemotePlaybackWirePlan) -> AndroidPlaybackResource)?,
    /** Told the raw ids whose downloaded state changed, so library screens republish their badges. */
    private val onDownloadedChanged: (Set<String>) -> Unit,
    /** The file system the download directory lives on; a test substitutes one that faults. */
    fileSystem: okio.FileSystem = okio.FileSystem.SYSTEM,
) : AutoCloseable {
    public constructor(context: Context, account: PlaybackEndpointAccount, tasks: AndroidDownloadTasks) : this(
        account = account,
        openStore = { DulcetDriverFactory(context.applicationContext).openDulcetDatabase() },
        downloadRoot = AndroidAccountData.downloadRootFor(context.applicationContext),
        tasks = tasks,
        wall = System::currentTimeMillis,
        diskBudgetBytes = { used -> productionDiskBudget(AndroidAccountData.downloadRootFor(context.applicationContext), used) },
        openTransfer = null,
        onDownloadedChanged = { rawIds -> AndroidLibraryReader.notifyDownloadsChanged(rawIds) },
    )

    public val providerInstanceId: String get() = account.providerInstanceId

    /** True once [close] has run; a closed controller runs and schedules nothing. */
    public val isClosed: Boolean get() = closed

    private val database = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store: DulcetDatabaseStore = openStore()
    private val engine = DownloadPolicyEngine(
        store.database,
        DownloadFileStore(downloadRoot.path, fileSystem),
    )
    @Volatile private var wire: PlaybackWireClient? = PlaybackWireClient(account)
    @Volatile private var requests: AuthenticatedEndpointClient? = AuthenticatedEndpointClient(
        AuthenticatedEndpointCredentials(account.normalizedBaseUrl, account.username, account.password, account.allowLocalHttp),
        "download.android",
    )
    /** The account's seen-cache namespace (spec §16.13), bound on first use; database thread only. */
    private var boundCache: BoundSeenCache? = null
    private val reconciled = CompletableDeferred<Boolean>()
    private val progress = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long?>>()
    private val mutableStatuses = MutableStateFlow<Map<String, AndroidDownloadStatus>>(emptyMap())
    @Volatile private var closed = false
    private val runningTasks = MutableStateFlow(0)
    /**
     * Held from a scheduling decision until its task is started, so a scheduling pass never returns
     * while another's start is still in flight. Without it, the pass that relaunch reconciliation
     * launches could mark a new row started while [download]'s own pass found it already running and
     * returned: [download] would return with its task not yet started (OBSERVED on a CI host).
     */
    private val scheduling = Mutex()

    /** Every download of the account, by raw id, with the transfer under way's progress. */
    public val statuses: StateFlow<Map<String, AndroidDownloadStatus>> = mutableStatuses

    init {
        scope.launch {
            val outcome = try {
                val outstanding = tasks.outstanding()
                val result = withContext(database) {
                    engine.reconcile(
                        outstanding.filter(String::isNotBlank).distinct().map { OutstandingDownloadTask(DownloadId(it)) },
                        // Account ids are never reused and an account's credentials never change
                        // under its id on Android, so its generation is constant.
                        mapOf(account.providerInstanceId to CREDENTIAL_GENERATION),
                        // A row of this account holding Range resume data — the exact length its
                        // first response declared — has a partial file the next run continues with
                        // a Range request. The worker that recorded its end has finished, so no
                        // task owns the file at relaunch; the run's own rules decide whether the
                        // file is still usable (a mismatched answer restarts from zero).
                        resumesTemporaryFile = { row ->
                            row.identity.serverId == account.providerInstanceId &&
                                row.platformResumeData?.let(::decodeRangeResume) != null
                        },
                    )
                }
                result.taskIdsToCancel.forEach { tasks.cancel(it.value) }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            reconciled.complete(outcome)
            if (outcome) {
                publish()
                scheduleNext()
            }
        }
    }

    /** The temporary file a task writes, for tests that observe a transfer part-way. */
    internal suspend fun temporaryPathForTest(downloadId: String): String =
        withContext(database) { engine.temporaryFilePath(DownloadId(downloadId)) }

    /** True once relaunch reconciliation has run; false when the subsystem could not be opened. */
    public suspend fun awaitReconciled(): Boolean = reconciled.await()

    /**
     * Downloads [items], each the original file (spec §14.5 identity `(server, raw id, original)`).
     * A track already downloaded or under way is left as it is. Each row is written, and its track's
     * metadata pinned against seen-cache eviction (§16.13), before any file exists: the pin's track
     * row exists from then on, as an identity alone until its metadata is read. A track the library
     * never read — asked for by its id alone — has its metadata read with `getSong` when its task
     * runs, before the transfer, so every downloaded track has a title to show offline. It returns once
     * scheduling has settled: the next download's task is started, or a wake is requested, whichever
     * scheduling pass made that decision.
     */
    public suspend fun download(items: List<AndroidDownloadItem>) {
        if (items.isEmpty() || !awaitReconciled()) return
        withContext(database) {
            for (item in items) {
                val identity = identity(item.rawId)
                val existing = engine.record(identity)
                if (existing != null && existing.state != DownloadState.Interrupted) continue
                store.database.transaction {
                    engine.enqueue(DownloadRequest(
                        identity = identity,
                        expectedContainer = item.sourceContainer,
                        declaredContentLength = null,
                        serverSnapshot = DownloadServerSnapshot(item.durationMilliseconds?.takeIf { it >= 0 }, null),
                        credentialGeneration = CREDENTIAL_GENERATION,
                        wallClockMilliseconds = wall(),
                    ))
                    // A pin never points at nothing: an identity-only track row comes with it.
                    cache().pin(CacheItemKind.Track, item.rawId, CachePinReason.Download)
                }
                // An interrupted row asked for again is retried now, not at its backoff boundary.
                if (existing?.state == DownloadState.Interrupted) {
                    store.database.downloadsQueries.resetDownloadRetry(wall(), identity.serverId, identity.rawId, identity.transcodeProfile)
                    store.database.downloadsQueries.markDownloadQueued(existing.downloadId.value)
                }
            }
        }
        publish()
        scheduleNext()
    }

    /**
     * Removes the downloads of [rawIds] at the person's request: the platform task is cancelled, the
     * files deleted, then the rows (spec §14.6), and the tracks' download pins released.
     */
    public suspend fun remove(rawIds: Collection<String>) {
        if (rawIds.isEmpty() || !awaitReconciled()) return
        val removed = withContext(database) {
            rawIds.mapNotNull { rawId ->
                engine.remove(identity(rawId))?.also {
                    store.database.seenCacheQueries.deletePin(account.providerInstanceId, "track", rawId, "download")
                }
            }
        }
        removed.forEach { row ->
            tasks.cancel(row.downloadId.value)
            progress.remove(row.identity.rawId)
        }
        publish()
        if (removed.isNotEmpty()) onDownloadedChanged(removed.mapTo(mutableSetOf()) { it.identity.rawId })
        scheduleNext()
    }

    /**
     * Starts the next queued download if none is running and the disk budget and retry boundaries
     * allow (spec §14.5, §14.6); otherwise asks to be woken at the earliest retry boundary.
     */
    public suspend fun scheduleNext() {
        if (closed || !awaitReconciled()) return
        scheduling.withLock { scheduleNextLocked() }
    }

    private suspend fun scheduleNextLocked() {
        if (closed) return
        val decision = withContext(database) {
            val rows = engine.records(account.providerInstanceId)
            if (rows.any { it.state == DownloadState.Downloading }) return@withContext null
            val used = rows.filter { it.state == DownloadState.Complete || it.state == DownloadState.Stale }
                .sumOf(DownloadRecord::fileSizeBytes)
            val now = wall()
            when (val scheduled = engine.schedule(DownloadSchedulingContext(
                serverId = account.providerInstanceId,
                wallClockMilliseconds = now,
                diskBudgetBytes = diskBudgetBytes(used).coerceAtLeast(0),
                unknownLengthReservationBytes = UNKNOWN_LENGTH_RESERVATION_BYTES,
                activeTranscodes = ActiveTranscodeCounts(),
            ))) {
                is DownloadScheduleResult.Start -> Schedule.Start(scheduled.platformTaskIdentifier)
                else -> engine.nextRetryNotBefore(account.providerInstanceId)?.let(Schedule::Wake)
            }
        }
        when (decision) {
            is Schedule.Start -> { tasks.start(decision.downloadId); publish() }
            is Schedule.Wake -> tasks.wakeAt(decision.wallClockMilliseconds)
            null -> Unit
        }
    }

    /**
     * The executor's body for one task: transfers the row's file into its temporary path, then asks
     * the core to validate and promote it. A cancellation (the task was stopped or the download
     * removed) leaves the row and its partial file as they are — WorkManager runs a stopped task
     * again, which resumes from the file's length, and a removed one has no row. Every other failure,
     * an [Error] included, is recorded with the core, which sets the retry boundary: nothing thrown
     * may leave a row `Downloading` with no task behind it. A transport failure keeps the partial
     * file for the retry to resume; any other failure discards it.
     */
    public suspend fun runTask(downloadId: String): AndroidDownloadRunOutcome {
        runningTasks.update { it + 1 }
        try {
            return runTaskCounted(downloadId)
        } finally {
            runningTasks.update { it - 1 }
        }
    }

    /**
     * Waits until no [runTask] is running, at most [timeoutMilliseconds]; true when none is. After
     * [close] a running transfer stops at its next read, so a sign-out that deletes the account's
     * files waits here first and no transfer writes into a directory being deleted.
     */
    public suspend fun awaitTasksStopped(timeoutMilliseconds: Long): Boolean =
        withTimeoutOrNull(timeoutMilliseconds) { runningTasks.first { it == 0 } } != null

    private suspend fun runTaskCounted(downloadId: String): AndroidDownloadRunOutcome {
        if (closed || !awaitReconciled()) return AndroidDownloadRunOutcome.NotRunnable
        val id = DownloadId(downloadId)
        val row = withContext(database) { engine.record(id) }
            ?.takeIf { it.state == DownloadState.Downloading && it.identity.serverId == account.providerInstanceId }
            ?: return AndroidDownloadRunOutcome.NotRunnable
        val temporary = File(withContext(database) { engine.temporaryFilePath(id) })
        // Best effort and outside the transfer's failure handling: no lookup outcome fails the run or
        // reaches the catch below, which deletes a partial file the failure does not keep.
        ensureTrackMetadata(row.identity.rawId)
        val outcome = try {
            val metadata = transfer(row, temporary)
            withContext(database) {
                when (val promoted = engine.promote(id, metadata)) {
                    is DownloadPromotionResult.Promoted, is DownloadPromotionResult.AlreadyPromoted -> AndroidDownloadRunOutcome.Downloaded
                    is DownloadPromotionResult.Rejected -> {
                        // A rejected body is never kept: it is not the file, and a retry starts over.
                        temporary.delete()
                        engine.recordFailure(id, promoted.error, wall())
                        AndroidDownloadRunOutcome.WillRetry
                    }
                    DownloadPromotionResult.MissingTemporaryFile -> {
                        engine.recordFailure(id, DomainError.Transport.Unreachable, wall())
                        AndroidDownloadRunOutcome.WillRetry
                    }
                    // Removed while it transferred: the bytes belong to no row.
                    DownloadPromotionResult.UnknownDownload -> { temporary.delete(); AndroidDownloadRunOutcome.NotRunnable }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val error = (failure as? AndroidDownloadTransferFailure)?.error
                ?: (failure as? AndroidPlaybackIOException)?.error
                ?: DomainError.Transport.Unreachable
            // The recording must happen even when the task's own job is being torn down around it.
            withContext(database + NonCancellable) {
                if (!failure.keepsPartialFile()) temporary.delete()
                if (engine.record(id) != null) {
                    engine.recordFailure(id, error, wall())
                    AndroidDownloadRunOutcome.WillRetry
                } else {
                    AndroidDownloadRunOutcome.NotRunnable
                }
            }
        } finally {
            progress.remove(row.identity.rawId)
        }
        publish()
        if (outcome == AndroidDownloadRunOutcome.Downloaded) onDownloadedChanged(setOf(row.identity.rawId))
        return outcome
    }

    /**
     * The local plan for [rawId]'s complete download, or null when it has none or its file is gone.
     * The file itself is validated as the player reads it ([AndroidLocalFilePlaybackResource]), so
     * this reads nothing but the row and the file's presence.
     */
    public suspend fun localPlan(rawId: String): LocalPlaybackPlan? {
        if (!awaitReconciled()) return null
        return withContext(database) {
            (engine.offlinePlaybackPlan(identity(rawId)) as? OfflinePlaybackPlanResult.Available)?.plan
        }
    }

    /**
     * The metadata kept with [rawId]'s download (spec §16.13), or null when it has no download or no
     * metadata yet. Read from the device alone, so the player names a downloaded song — restored
     * after a relaunch, or played offline — with no request.
     */
    public suspend fun localTrack(rawId: String): AndroidTrack? {
        if (!awaitReconciled()) return null
        return withContext(database) {
            if (closed || engine.record(identity(rawId)) == null) return@withContext null
            val record = cache().track(rawId)?.record?.takeIf { it.title.isNotBlank() } ?: return@withContext null
            AndroidTrack(account.providerInstanceId, rawId, record.title, record.credits.firstOrNull()?.name,
                record.albumTitle, record.durationMilliseconds, record.artworkKey)
        }
    }

    /** The raw ids whose downloads are complete and present, for the library's playability (§16.14). */
    public fun downloadedRawIds(): Set<String> =
        statuses.value.values.filter(AndroidDownloadStatus::playsOffline).mapTo(mutableSetOf(), AndroidDownloadStatus::rawId)

    /** Closes every network client, keeping the durable policy and local files usable. */
    public fun closeNetworkAccess() {
        requests?.close(); requests = null
        wire?.close(); wire = null
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        closeNetworkAccess()
        // The store is released on the database thread, after anything already queued there.
        CoroutineScope(database).launch { store.close() }
    }

    /** Database thread only. The reader binds the same account's identity, so this never purges. */
    private fun cache(): BoundSeenCache = boundCache ?: SeenCacheStore(store, SeenCacheWallClock { wall() })
        .bind(CacheBinding(account.providerInstanceId, account.normalizedBaseUrl, account.username))
        .also { boundCache = it }

    /**
     * Reads [rawId]'s own metadata with `getSong` when the seen-cache holds none (spec §16.13), and
     * writes what it learns as a song lookup, which never clears `gone`. Nothing is read for a track
     * the library already read. Best effort: the download needs only `stream`, so a refused,
     * unreadable or failed lookup leaves the identity-only row and the transfer runs as before; only
     * a cancellation leaves here.
     */
    private suspend fun ensureTrackMetadata(rawId: String) {
        try {
            val issueSeq = withContext(database) {
                val cached = cache().track(rawId)
                if (cached?.record != null && !cached.metadataMissing) null else cache().issue()
            } ?: return
            val response = (requests ?: return).request("getSong", mapOf("id" to rawId))
            if (response.statusCode !in 200..299) return
            val track = parseLookupSong(response.body.decodeToString(), rawId) ?: return
            withContext(database) {
                cache().writeEntities(CacheWriteStamp(issueSeq, wall(), null), CacheEntitySource.SongLookup, CacheEntities(tracks = listOf(track)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The lookup is never the download: its failure leaves the placeholder.
        }
    }

    private suspend fun transfer(row: DownloadRecord, temporary: File): DownloadResponseMetadata {
        val wireClient = wire ?: throw AndroidDownloadTransferFailure(DomainError.Transport.Cancelled)
        val plan = when (val resolved = wireClient.resolve(PlaybackResolveRequest(
            PlaybackSessionId("download-session:${UUID.randomUUID()}"),
            AttemptId("download-attempt:${UUID.randomUUID()}"),
            ProviderItemId(account.providerInstanceId, row.identity.rawId),
            row.expectedContainer,
            supportsTranscodingExtension = false,
            deviceProfile = DOWNLOAD_PROFILE,
            legacyPreference = LegacyPlaybackPreference(format = null, maxBitRateKbps = null),
        ))) {
            is PlaybackResolutionResult.Failed -> throw AndroidDownloadTransferFailure(resolved.error)
            is PlaybackResolutionResult.Resolved -> resolved.plan.asOriginalFileDownload()
        }
        val resource = openTransfer?.invoke(plan)
            ?: AndroidHttpPlaybackResource(account, plan, requests ?: throw AndroidDownloadTransferFailure(DomainError.Transport.Cancelled))
        val resumable = withContext(database) {
            (engine.resumeDecision(row.downloadId, wall()) as? DownloadResumeDecision.Resume)?.data
        }?.let(::decodeRangeResume)
        return withContext(Dispatchers.IO) {
            val partial = if (temporary.exists()) temporary.length() else 0L
            val resumeAt = resumable?.takeIf { partial in 1 until it }?.let { partial }
            var response = resource.open(resumeAt ?: 0L, -1)
            var continuing = false
            if (resumeAt != null) {
                // Only exactly the rest of a file of the first-declared length continues the prefix.
                // A 200 is the whole file again; any other range means the file is not the one the
                // prefix came from, so the prefix goes and the transfer starts over from zero.
                continuing = response.status == 206 &&
                    response.headers.contentRange?.trim() == "bytes $resumeAt-${resumable - 1}/$resumable"
                if (!continuing && response.status != 200 && (response.status == 206 || response.status == 416)) {
                    try { response.close() } catch (_: Exception) { }
                    response = resource.open(0L, -1)
                }
            }
            try {
                if (response.status !in 200..299 || (!continuing && response.status != 200)) {
                    throw AndroidDownloadTransferFailure(when (response.status) {
                        401 -> DomainError.Auth.InvalidCredentials
                        403 -> DomainError.Auth.Forbidden
                        429 -> DomainError.Server.Busy(parseRetryAfterSeconds(response.headers.retryAfter))
                        in 200..299 -> DomainError.Protocol.UnexpectedBinary
                        else -> DomainError.Server.Unknown(response.status)
                    })
                }
                val exact = if (continuing) resumable else (response.headers.contentLength as? PlaybackContentLength.Exact)?.byteCount
                if (!continuing) {
                    // The declared length is what a later run's range must match; without one there
                    // is nothing to check a continuation against, so such a transfer never resumes.
                    withContext(database) {
                        if (exact != null && exact > 0) {
                            engine.recordResumeData(row.downloadId, encodeRangeResume(exact), wall())
                        } else {
                            engine.clearResumeData(row.downloadId)
                        }
                    }
                }
                var written = if (continuing) partial else 0L
                var reported = written
                progress[row.identity.rawId] = written to exact
                publish()
                temporary.parentFile?.mkdirs()
                FileOutputStream(temporary, continuing).use { output ->
                    try {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            // A closed controller's account is being signed out: stop as a stopped task does.
                            if (closed) throw CancellationException("download controller closed")
                            val count = response.input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                            if (written - reported >= PROGRESS_STEP_BYTES) {
                                reported = written
                                progress[row.identity.rawId] = written to exact
                                publish()
                            }
                        }
                    } finally {
                        // The bytes reach the disk before the core may rename them into place, and
                        // before a stopped run's successor resumes from the file's length.
                        output.flush()
                        output.fd.sync()
                    }
                }
                DownloadResponseMetadata(
                    response.headers.contentType,
                    if (continuing) PlaybackContentLength.Exact(requireNotNull(resumable)) else response.headers.contentLength,
                )
            } finally {
                try { response.close() } catch (_: Exception) { }
            }
        }
    }

    private suspend fun publish() {
        if (closed) return
        val rows = try {
            withContext(database) { engine.records(account.providerInstanceId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        mutableStatuses.value = rows.associate { row ->
            val transfer = progress[row.identity.rawId]
            row.identity.rawId to AndroidDownloadStatus(
                rawId = row.identity.rawId,
                state = when (row.state) {
                    DownloadState.Queued -> AndroidDownloadState.Queued
                    DownloadState.Downloading -> AndroidDownloadState.Downloading
                    DownloadState.Interrupted -> AndroidDownloadState.Interrupted
                    DownloadState.Complete -> AndroidDownloadState.Downloaded
                    DownloadState.Stale -> AndroidDownloadState.Stale
                },
                bytesWritten = if (row.state == DownloadState.Downloading) transfer?.first ?: 0 else row.fileSizeBytes,
                totalBytes = if (row.state == DownloadState.Downloading) transfer?.second else row.exactByteLength,
                retryNotBeforeWallClock = row.retryNotBeforeWallClock,
                needsAttention = row.retryNotBeforeWallClock == Long.MAX_VALUE,
            )
        }
    }

    private fun identity(rawId: String) =
        DownloadIdentity(account.providerInstanceId, rawId, DownloadIdentity.ORIGINAL_PROFILE)

    private sealed interface Schedule {
        data class Start(val downloadId: String) : Schedule
        data class Wake(val wallClockMilliseconds: Long) : Schedule
    }

    internal companion object {
        const val CREDENTIAL_GENERATION = 1L
        const val UNKNOWN_LENGTH_RESERVATION_BYTES = 512L * 1024 * 1024
        const val DISK_BUDGET_CEILING_BYTES = 10L * 1024 * 1024 * 1024
        /** Free space always left to the rest of the device when downloads are budgeted. */
        const val DISK_FREE_FLOOR_BYTES = 256L * 1024 * 1024
        const val PROGRESS_STEP_BYTES = 256L * 1024

        /** Ten GiB, or what the device can spare beyond a floor, whichever is smaller (spec §14.6). */
        fun productionDiskBudget(root: File, usedBytes: Long): Long {
            root.mkdirs()
            val available = try { StatFs(root.path).availableBytes } catch (_: IllegalArgumentException) { 0L }
            return minOf(DISK_BUDGET_CEILING_BYTES, usedBytes + available - DISK_FREE_FLOOR_BYTES)
        }

        private val DOWNLOAD_PROFILE = PlaybackDeviceProfile("Dulcet download", "Android", 1_411_200, 320_000,
            listOf(DirectPlayAudioProfile(AudioContainer.entries, listOf("mp3", "aac", "flac", "opus", "vorbis", "pcm"), maxAudioChannels = 8)),
            listOf(TranscodingAudioProfile(AudioContainer.Mp3, "mp3", maxAudioChannels = 2)))
    }
}

/**
 * True for a failure of the connection, whose partial file a retry resumes from. A refusal, a
 * rejected body or an [Error] discards it: none of them is a prefix worth continuing.
 */
private fun Throwable.keepsPartialFile(): Boolean = when (this) {
    is AndroidDownloadTransferFailure -> error is DomainError.Transport
    is AndroidPlaybackIOException -> error is DomainError.Transport
    is IOException -> true
    else -> false
}

/** The resume record Android keeps on a row: the exact length the first response declared. */
private fun encodeRangeResume(totalBytes: Long): ByteArray = "$RANGE_RESUME_PREFIX$totalBytes".encodeToByteArray()

private fun decodeRangeResume(data: ByteArray): Long? = data.decodeToString()
    .takeIf { it.startsWith(RANGE_RESUME_PREFIX) }
    ?.removePrefix(RANGE_RESUME_PREFIX)
    ?.toLongOrNull()
    ?.takeIf { it > 0 }

private const val RANGE_RESUME_PREFIX = "android-range:1:"

/** A transfer that failed for a reason the core has a word for. Carries no URL. */
internal class AndroidDownloadTransferFailure(val error: DomainError) : IOException("download transfer failed")
