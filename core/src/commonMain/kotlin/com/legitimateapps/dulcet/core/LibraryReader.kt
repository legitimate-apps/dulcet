package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The reader of spec §16.8–§16.20, one per account: live source, seen-cache, catalog epoch,
 * windows, detail look-ahead and eviction. Every screen is published FROM the cache, online and
 * offline alike; a live response is written through first and never rendered beside a cached one.
 *
 * **Threading — confined, and enforced (§16.18).** A reader lives on ONE dedicated serial thread
 * that is never the main thread: production code runs it on [newLibraryReaderDispatcher]. It is
 * created on that thread, and every entry point — here and on every handle — checks that it is
 * called there and throws otherwise. All database work, including eviction, therefore happens off
 * the main thread, and nothing here needs a lock. Listeners are called on the reader's thread; the
 * platform facade hops each publication to the main thread. Nothing raised inside the reader
 * escapes [scope]: a handle converts a failure into a publication that says so, and
 * [uncaughtFailures] records anything that still reaches the backstop handler.
 *
 * **Hooks for other phases**, each a small interface with an inert default:
 * [LibraryMutationOverlay] (R1d: pending stars and ratings overlaid at publish time, §16.20),
 * [DownloadedTrackSource] (R4: playability and the downloaded-album recheck), and
 * [ReconnectOutboxes] (the outbox flush that comes first on reconnect, §16.14).
 */
internal class LibraryReader(
    internal val cache: BoundSeenCache,
    private val transport: LibraryEndpointTransport,
    scope: CoroutineScope,
    internal val config: LibraryReaderConfig = LibraryReaderConfig(),
    internal val overlay: LibraryMutationOverlay = LibraryMutationOverlay.None,
    internal val downloads: DownloadedTrackSource = DownloadedTrackSource.None,
    private val outboxes: ReconnectOutboxes = ReconnectOutboxes.None,
) {
    private val owner = currentThreadIdentity()

    /** Failures that reached the backstop handler instead of a publication; empty when healthy. */
    internal val uncaughtFailures = mutableListOf<Throwable>()

    /**
     * The reader's own scope: the caller's dispatcher (the reader's thread), a supervisor so one
     * failing read never cancels its siblings, and a handler so nothing escapes to the process.
     */
    internal val scope: CoroutineScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) +
            CoroutineExceptionHandler { _, failure -> uncaughtFailures += failure },
    )

    private val permits = Semaphore(config.serverConcurrency)
    private val handles = mutableListOf<ReaderHandle>()
    internal val lookAhead = DetailLookAhead(this)

    /** One writer per list: two handles on the same list serialize their live operations. */
    private val listLocks = mutableMapOf<String, Mutex>()

    internal fun listLock(listKey: String): Mutex = listLocks.getOrPut(listKey) { Mutex() }

    /** Whether the server is reachable, as the platform reports it. Offline issues no request. */
    var online: Boolean = true
        private set

    /** Low Data Mode or a metered connection: no speculative reads (§16.13). */
    var networkConstrained: Boolean = false
        private set

    /**
     * The latest epoch reading of this session, or null before the first one. Every successful
     * `getScanStatus` — the two-request reading and each page's *after* — updates it, keeping the
     * folder set of the last full reading.
     */
    internal var sessionEpoch: CatalogEpoch? = null
        private set

    /**
     * Whether the latest successful reading had no stamp at all (absent, or the first-scan
     * sentinel). This is the account-level fact §16.12 states once per account; a FAILED reading
     * never sets it, so a transient failure cannot label a server stamp-less.
     */
    val serverReportsNoEpoch: Boolean get() = sessionEpoch?.let { it.stamp == null } ?: false

    /** Detail rows read live in this session, for the 60-second revalidation rule. */
    internal val liveDetailReads = mutableMapOf<String, Long>()

    /** Albums whose latest detail read ran while the server was scanning: unverified (§16.12). */
    internal val detailsReadWhileScanning = mutableSetOf<String>()

    /** Lists read live in this session, by list key: the wall-clock of the latest live read. */
    internal val liveListReads = mutableMapOf<String, Long>()

    /** When each list last had its last-access refreshed; touches are throttled (review S7). */
    internal val lastTouches = mutableMapOf<String, Long>()

    /** Throws unless called on the reader's own thread (see the class comment). */
    internal fun checkConfined() {
        check(currentThreadIdentity() == owner) {
            "LibraryReader is confined to its own thread; dispatch onto its dispatcher before calling it"
        }
    }

    // ---- Lifecycle ----------------------------------------------------------------------------------

    /** The connect-time epoch reading (two requests). Returns null when it could not be read. */
    suspend fun connect(): CatalogEpoch? {
        checkConfined()
        return readEpoch()
    }

    /**
     * Reconnect or return to the foreground online (§16.14), in this order and nothing else:
     * flush the outboxes (user-authored data first); read the catalog epoch; revalidate the visible
     * screen — every open handle; and, only if the epoch changed, re-read the albums that contain
     * downloads, one at a time. No catch-up walk, no bulk refetch, nothing re-read because it is old.
     */
    suspend fun reconnect() {
        checkConfined()
        online = true
        outboxes.flush()
        val before = (sessionEpoch ?: cache.storedEpoch()?.let(CatalogEpoch::fromStored))?.key
        val epoch = readEpoch() ?: return
        visibleHandles().forEach { it.revalidate(RevalidateCause.Reconnect) }
        if (before == null || before != epoch.key) recheckDownloadedAlbums()
    }

    /**
     * The in-foreground epoch cadence (§16.11 policy 1): every [LibraryReaderConfig.epochIntervalMillis]
     * while the app is in the foreground and a library screen is open. Nothing reads the epoch in
     * the background. The platform reports foreground transitions; the cadence itself is core policy.
     */
    fun setForeground(foreground: Boolean) {
        checkConfined()
        periodicEpoch?.cancel()
        periodicEpoch = null
        if (!foreground) return
        periodicEpoch = scope.launch {
            while (true) {
                delay(config.epochIntervalMillis)
                if (online && handles.isNotEmpty()) refreshEpoch()
            }
        }
    }

    private var periodicEpoch: Job? = null

    /**
     * Reads the epoch and, if the catalog or the scanning state changed, revalidates the visible
     * screen (§16.11 policy 3) and re-reads the albums that contain downloads (policy 4).
     */
    suspend fun refreshEpoch() {
        checkConfined()
        val previous = sessionEpoch
        val epoch = readEpoch() ?: return
        val changed = previous == null || previous.key != epoch.key
        if (changed || previous?.scanning != epoch.scanning) {
            visibleHandles().forEach { it.revalidate(RevalidateCause.EpochChanged) }
        } else {
            // A window whose stamp kept moving is re-read at the next quiet reading, or its
            // `unverified(changing)` label would outlive the change that caused it.
            visibleHandles().filter { it.awaitsQuietEpoch() }.forEach { it.revalidate(RevalidateCause.EpochChanged) }
        }
        if (changed) recheckDownloadedAlbums()
    }

    fun setOnline(reachable: Boolean) {
        checkConfined()
        if (online == reachable) return
        online = reachable
        if (!reachable) lookAhead.cancelAll()
        visibleHandles().forEach { it.republish() }
    }

    fun setNetworkConstrained(constrained: Boolean) {
        checkConfined()
        networkConstrained = constrained
        if (constrained) lookAhead.cancelAll()
    }

    /**
     * R1d's hook: a pending local change to these entities must be in the next publication. Called
     * on the reader's thread (enforced), it republishes synchronously, before any request.
     */
    fun republishPendingChanges(rawIds: Set<String>) {
        checkConfined()
        visibleHandles().filter { it.mentionsAny(rawIds) }.forEach { it.republish() }
    }

    // ---- Opening ------------------------------------------------------------------------------------

    /**
     * Opens one screen. The first publication — cached content if there is any — is delivered
     * synchronously, before this returns and therefore before any request is issued (CONF-76).
     */
    fun open(query: LibraryQuery, listener: (LibraryPublication) -> Unit): LibraryWindowHandle {
        checkConfined()
        val handle: ReaderHandle = when (query) {
            is LibraryQuery.Album -> AlbumDetailWindow(this, query, listener)
            is LibraryQuery.Artist -> CollectionDetailWindow(this, query, listener)
            is LibraryQuery.Playlist -> CollectionDetailWindow(this, query, listener)
            else -> ListWindow(this, query, ListRequestSpec.of(query), listener)
        }
        handles += handle
        handle.start()
        return handle
    }

    /**
     * A home screen: N independent single-page windows, one per row (§16.9). Each row paints from
     * its own cache at once and is replaced when its own read lands; a failing row keeps its cached
     * content with that failure, and the others are unaffected. The rows share the session's epoch
     * reading and never wait for one another.
     */
    fun openHome(
        rows: List<LibraryHomeRow>,
        listener: (rowIndex: Int, LibraryPublication) -> Unit,
    ): LibraryHomeHandle {
        checkConfined()
        require(rows.isNotEmpty())
        val windows = rows.mapIndexed { index, row ->
            ListWindow(this, row.query, ListRequestSpec.homeRow(row, config.homeRowSize)) { listener(index, it) }
        }
        handles += windows
        windows.forEach(ListWindow::start)
        return LibraryHomeHandle(windows)
    }

    internal fun closed(handle: ReaderHandle) {
        handles -= handle
    }

    private fun visibleHandles(): List<ReaderHandle> = handles.toList()

    // ---- Requests -----------------------------------------------------------------------------------

    /**
     * Every request goes through the per-server concurrency bound of §16.5 rule 6. Its issue
     * sequence, and the epoch reading it is checked against (its *before*), are both taken once the
     * request holds a slot and is about to be SENT — never while it waits — so a request that waited
     * is ordered by when it went out, and its *before* is provably earlier than the request.
     */
    internal suspend fun send(endpoint: String, parameters: Map<String, String> = emptyMap()): SentResponse =
        permits.withPermit {
            val seq = cache.issue()
            val before = sessionEpoch?.let { ScanStatusReading(it.lastScan, it.scanning) }
            SentResponse(seq, before, transport.request(endpoint, parameters))
        }

    /** A sent request whose envelope must be `ok`; a failure envelope throws its [DomainError]. */
    internal suspend fun sendChecked(endpoint: String, parameters: Map<String, String> = emptyMap()): SentResponse =
        send(endpoint, parameters).requireOk(endpoint, parameters)

    private val bounded = LibraryEndpointTransport { endpoint, parameters -> send(endpoint, parameters).response }

    internal val epochReader = CatalogEpochReader(bounded)

    /** Reads the full epoch, stores it, and returns it; null when it could not be read. */
    internal suspend fun readEpoch(): CatalogEpoch? {
        val epoch = try {
            epochReader.readFull()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            return null
        }
        adoptEpoch(epoch)
        return epoch
    }

    /** The session reading, read now if this session has none (a window cannot open without one). */
    internal suspend fun ensureEpoch(): CatalogEpoch? = sessionEpoch ?: readEpoch()

    /**
     * A page's *after* reading. Every successful one becomes the session's reading — it is the
     * newest thing known about the server, and it is the next page's *before* — keeping the folder
     * set of the last full reading, which is checked only at window open (§16.12's recorded exposure).
     */
    internal fun adoptScanStatus(after: ScanStatusReading): CatalogEpoch {
        val folders = sessionEpoch?.folderIds ?: emptySet()
        return CatalogEpoch(after.lastScan, folders, after.scanning).also(::adoptEpoch)
    }

    private fun adoptEpoch(epoch: CatalogEpoch) {
        if (sessionEpoch == epoch) return
        sessionEpoch = epoch
        cache.saveEpoch(StoredCatalogEpoch(epoch.lastScan, epoch.folderIds, epoch.scanning, cache.now()))
    }

    /** §16.11 rule 4 and §16.14 step 4: the albums that contain downloads, at concurrency 1. */
    private suspend fun recheckDownloadedAlbums() {
        val albums = downloads.downloadedTrackRawIds(cache.serverId)
            .mapNotNull { cache.track(it)?.record?.albumRawId }
            .distinct()
            .sorted()
        for (album in albums) {
            readAlbumDetail(album)
        }
        if (albums.isNotEmpty()) visibleHandles().forEach { it.republish() }
    }

    /**
     * One `getAlbum`, written through. Used by the album screen, look-ahead and the recheck. A
     * detail read while the server scans is stored with no detail epoch — never current — and
     * remembered, so the scan's end re-reads it like a window (§16.12, review finding S2).
     */
    internal suspend fun readAlbumDetail(albumRawId: String): DetailReadResult {
        var seq = 0L
        return try {
            val parameters = mapOf("id" to albumRawId)
            val sent = send("getAlbum", parameters)
            // The sequence is known before the envelope is judged: a not-found is recorded with it.
            seq = sent.issueSeq
            sent.requireOk("getAlbum", parameters)
            val scanning = sent.before?.scanning ?: true
            val epochKey = sessionEpoch?.key
            val (album, tracks) = parseReaderAlbum(sent.response.body, albumRawId)
            val write = cache.writeAlbumDetail(
                CacheWriteStamp(seq, cache.now(), epochKey),
                album,
                tracks,
                detailEpoch = if (scanning) null else epochKey,
            )
            if (scanning) detailsReadWhileScanning += albumRawId else detailsReadWhileScanning -= albumRawId
            liveDetailReads[albumRawId] = cache.now()
            cache.evictIfNeeded()
            if (write.albumGone) DetailReadResult.Gone else DetailReadResult.Read
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            val error = failure.asReaderError()
            if (error.isNotFound() && seq > 0) {
                cache.markAlbumNotFound(seq, albumRawId)
                liveDetailReads[albumRawId] = cache.now()
                DetailReadResult.Gone
            } else {
                DetailReadResult.Failed(error)
            }
        }
    }

    /**
     * Whether a detail was read live under the current epoch, with no scan running, within the
     * revalidation interval — judged by the epoch its MEMBERSHIP was read under (review finding S1).
     */
    internal fun detailIsFresh(rawId: String, detailFetchedEpoch: String?): Boolean {
        val readAt = liveDetailReads[rawId] ?: return false
        val current = sessionEpoch ?: return false
        return detailFetchedEpoch != null && detailFetchedEpoch == current.key &&
            cache.now() - readAt < config.revalidateWithinMillis
    }

    internal fun trackPlayability(rawId: String, downloaded: Set<String>): LibraryPlayability = when {
        rawId in downloaded -> LibraryPlayability.Downloaded
        online -> LibraryPlayability.Streamable
        else -> LibraryPlayability.UnavailableOffline
    }
}

/** A response, the issue sequence it was sent with, and the reading that was current as it went. */
internal class SentResponse(
    val issueSeq: Long,
    val before: ScanStatusReading?,
    val response: LibraryEndpointResponse,
) {
    /** Returns this response when its envelope is `ok`; a failure envelope throws its [DomainError]. */
    suspend fun requireOk(endpoint: String, parameters: Map<String, String>): SentResponse {
        LibraryEndpointTransport { _, _ -> response }.checkedRequest(endpoint, parameters)
        return this
    }
}

/**
 * The dispatcher a production reader runs on: one dedicated thread, never the main one. The facade
 * creates the reader on it and hops publications to the main thread (§16.18).
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
internal fun newLibraryReaderDispatcher(): CloseableCoroutineDispatcher = newSingleThreadContext("dulcet-library-reader")

/** An identity for the calling thread, for [LibraryReader.checkConfined]. */
internal expect fun currentThreadIdentity(): Long

internal sealed interface DetailReadResult {
    data object Read : DetailReadResult
    data object Gone : DetailReadResult
    data class Failed(val error: DomainError) : DetailReadResult
}

internal data class LibraryReaderConfig(
    /** Rows asked for per window page; the protocol caps a page at 500 (§16.2 rule 1). */
    val pageSize: Int = 100,
    /** Rows per home-screen row (ASSUMED size, §16.9). */
    val homeRowSize: Int = 20,
    /** A screen read live under the current epoch this recently is not re-read (ASSUMED, §16.11). */
    val revalidateWithinMillis: Long = 60_000,
    /** Per-server request concurrency (§16.5 rule 6). */
    val serverConcurrency: Int = 4,
    /** Detail look-ahead bounds (§16.13, ASSUMED values). */
    val lookAheadSettleMillis: Long = 300,
    val lookAheadMaxPerViewport: Int = 24,
    val lookAheadInFlight: Int = 2,
    /** How often a torn page is re-read before the window is labelled unverified instead. */
    val maxTearRetries: Int = 3,
    /** The in-foreground epoch cadence while a library screen is visible (ASSUMED, §16.11). */
    val epochIntervalMillis: Long = 5 * 60_000,
    /** How often showing a list refreshes its rows' last access (LRU needs minutes, not frames). */
    val touchIntervalMillis: Long = 60_000,
) {
    init {
        require(pageSize in 1..500)
        require(homeRowSize in 1..500)
        require(serverConcurrency > 0 && lookAheadInFlight in 1..serverConcurrency)
        require(lookAheadMaxPerViewport >= 0 && lookAheadSettleMillis >= 0 && maxTearRetries > 0)
        require(epochIntervalMillis > 0)
    }
}

// ---- Queries ------------------------------------------------------------------------------------------

/** The closed set of screens of spec §16.9. Search is not here: it is §16.15's. */
internal sealed interface LibraryQuery {
    data class AlbumList(
        val type: AlbumListType,
        val fromYear: Int? = null,
        val toYear: Int? = null,
        val genre: String? = null,
        val musicFolderId: String? = null,
    ) : LibraryQuery {
        init {
            require(type != AlbumListType.ByYear || (fromYear != null && toYear != null))
            require(type != AlbumListType.ByGenre || !genre.isNullOrBlank())
        }
    }

    data class Artists(val musicFolderId: String? = null) : LibraryQuery
    data class Artist(val rawId: String) : LibraryQuery
    data class Album(val rawId: String) : LibraryQuery
    data object Playlists : LibraryQuery
    data class Playlist(val rawId: String) : LibraryQuery
    data object Starred : LibraryQuery
    data object Genres : LibraryQuery
    data class SongsByGenre(val genre: String, val musicFolderId: String? = null) : LibraryQuery
}

/**
 * `getAlbumList2` types. Activity-ordered ones and `random` are single pages, never offset-paged:
 * their order moves with the person's own plays, which no scan stamp sees, and `random` reseeds.
 */
internal enum class AlbumListType(val wireName: String, val paged: Boolean) {
    AlphabeticalByName("alphabeticalByName", true),
    AlphabeticalByArtist("alphabeticalByArtist", true),
    Newest("newest", true),
    ByYear("byYear", true),
    ByGenre("byGenre", true),
    Recent("recent", false),
    Frequent("frequent", false),
    Highest("highest", false),
    Starred("starred", false),
    Random("random", false),
}

/** One row of a home screen: a single page of an album list, or the favourites. */
internal sealed interface LibraryHomeRow {
    val query: LibraryQuery

    data class Albums(val type: AlbumListType) : LibraryHomeRow {
        override val query: LibraryQuery get() = LibraryQuery.AlbumList(type)
    }

    data object Favourites : LibraryHomeRow {
        override val query: LibraryQuery get() = LibraryQuery.Starred
    }
}

// ---- Publications -------------------------------------------------------------------------------------

/**
 * What a screen shows, computed in the core and copied to each shell (§16.14, §16.18). A window may
 * publish many times — cached, live, after a rebase, after a local change — and nothing about a
 * publication means "the open finished".
 */
internal data class LibraryPublication(
    val query: LibraryQuery,
    /** 1-based, per handle: the order publications were delivered in. */
    val sequence: Int,
    val freshness: LibraryFreshness,
    /** Lists only; null for a detail screen. */
    val coverage: LibraryCoverage?,
    /** The server's `X-Total-Count` when known. */
    val total: Int?,
    /** A detail screen's entity (album, artist, playlist); null for a list. */
    val header: LibraryItem?,
    val items: List<LibraryItem>,
    val itemsState: LibraryItemsState,
    /** Whether [items] are the server's order or a locally sorted offline view (§16.14). */
    val order: LibraryItemsOrder,
    /** After a rebase: where the viewport should be so the first visible item stays first. */
    val anchor: LibraryAnchor? = null,
    /**
     * The server position of `items[0]`. Above zero, rows precede the window that are not loaded:
     * the shell shows a gap there and calls [LibraryWindowHandle.loadBefore] as the person scrolls
     * up (§16.12: a rebased window is open on both sides of the viewport).
     */
    val leadingOffset: Int = 0,
)

/** The freshness of §16.14, plus [Loading], which is only ever published with nothing cached. */
internal sealed interface LibraryFreshness {
    /** Read from the server in this session under the current epoch. */
    data object Live : LibraryFreshness

    /** Served from the seen-cache; [asOfWall] is absent when the age is unknown (seeded rows). */
    data class Cached(val asOfWall: Long?, val reason: LibraryCachedReason) : LibraryFreshness

    /** Nothing cached and a live read in flight: the only state a loading indicator may show. */
    data object Loading : LibraryFreshness

    /** Nothing cached and nothing can be read. A statement of fact, never a spinner. */
    data class Unavailable(val reason: LibraryUnavailableReason) : LibraryFreshness
}

internal sealed interface LibraryCachedReason {
    data object Revalidating : LibraryCachedReason
    data object Offline : LibraryCachedReason
    data class Failed(val error: DomainError) : LibraryCachedReason
    data object Stale : LibraryCachedReason

    /** The reader itself failed while building or refreshing this screen (a defect, not the server). */
    data object InternalFailure : LibraryCachedReason
}

internal sealed interface LibraryUnavailableReason {
    /** Never read on this device, and offline: "Connect to your server to see it." */
    data object NotCachedOffline : LibraryUnavailableReason

    /** The server says it no longer exists (§16.11). */
    data object Gone : LibraryUnavailableReason

    data class Failed(val error: DomainError) : LibraryUnavailableReason

    /** The reader itself failed while building this screen (a defect, not the server). */
    data object InternalFailure : LibraryUnavailableReason
}

/**
 * Coverage of §16.12. [UnverifiedChanging]: the stamp moved on every re-read within the retry
 * bound while no scan was reported — the list is shown, and says it may not be one state.
 */
internal enum class LibraryCoverage { Complete, Open, UnverifiedScanning, UnverifiedNoEpoch, UnverifiedChanging }

/** For a detail screen: whether its child list is shown, still loading, or cannot be shown. */
internal enum class LibraryItemsState { Present, Loading, Unavailable }

internal enum class LibraryItemsOrder { Server, LocalView }

internal enum class LibraryPlayability { Downloaded, Streamable, UnavailableOffline }

internal data class LibraryAnchor(val itemRawId: String?, val index: Int)

/** One row, as flat values. Per-user state is the cached value with pending changes overlaid. */
internal sealed interface LibraryItem {
    val rawId: String

    data class Album(
        override val rawId: String,
        val title: String,
        val artistName: String?,
        val artistRawId: String?,
        val year: Int?,
        val genre: String?,
        val durationMilliseconds: Long?,
        val songCount: Int?,
        val artworkKey: String?,
        val starred: Boolean?,
        val userRating: Int?,
        val playCount: Long?,
        val detailComplete: Boolean,
    ) : LibraryItem

    data class Artist(
        override val rawId: String,
        val name: String,
        val albumCount: Int?,
        val artworkKey: String?,
        val starred: Boolean?,
        val userRating: Int?,
    ) : LibraryItem

    data class Track(
        override val rawId: String,
        val title: String?,
        val albumRawId: String?,
        val albumTitle: String?,
        val artistName: String?,
        val artistRawId: String?,
        val discNumber: Int?,
        val trackNumber: Int?,
        val durationMilliseconds: Long?,
        val sourceContainer: AudioContainer?,
        val artworkKey: String?,
        val starred: Boolean?,
        val userRating: Int?,
        val playCount: Long?,
        val playability: LibraryPlayability,
        val metadataMissing: Boolean,
    ) : LibraryItem

    data class Playlist(
        override val rawId: String,
        val name: String,
        val songCount: Int?,
        val durationMilliseconds: Long?,
        val owner: String?,
        val artworkKey: String?,
    ) : LibraryItem

    data class Genre(override val rawId: String) : LibraryItem
}

// ---- Handles ------------------------------------------------------------------------------------------

internal interface LibraryWindowHandle {
    val query: LibraryQuery

    /** Reads the next page of a paged list, at most one page beyond the viewport. */
    fun loadMore()

    /**
     * Reads the page before a window that does not start at the top (after a rebase), at most one
     * page before the viewport — the other half of "open on both sides" (§16.12).
     */
    fun loadBefore()

    /**
     * The visible range, as indexes into the latest publication's [LibraryPublication.items].
     * Drives rebasing around the viewport and detail look-ahead.
     */
    fun setViewport(firstIndex: Int, lastIndex: Int)

    /** An explicit refresh: re-reads the visible pages whatever their age. */
    fun refresh()

    /** Idempotent; cancels this handle's in-flight reads. Nothing is published after it. */
    fun close()
}

/** Every method is confined to the reader's thread, like the handles it delegates to. */
internal class LibraryHomeHandle(private val rows: List<LibraryWindowHandle>) {
    fun row(index: Int): LibraryWindowHandle = rows[index]

    fun refresh() = rows.forEach(LibraryWindowHandle::refresh)

    fun close() = rows.forEach(LibraryWindowHandle::close)
}

internal enum class RevalidateCause { Open, Reconnect, Refresh, EpochChanged }

// ---- Hooks --------------------------------------------------------------------------------------------

/** A pending local change to one entity's user state (§16.20). Null fields are not pending. */
internal data class PendingUserState(val starred: Boolean? = null, val userRating: Int? = null)

/**
 * R1d implements this over `mutation_outbox`: the latest pending value per field, overlaid at
 * publish time. The cache row keeps what the server last said; the overlay is never written into it.
 */
internal fun interface LibraryMutationOverlay {
    fun pending(serverId: String, rawIds: Set<String>): Map<String, PendingUserState>

    companion object {
        val None = LibraryMutationOverlay { _, _ -> emptyMap() }
    }
}

/** R4 implements this over the `download` table: the tracks whose files are complete. */
internal fun interface DownloadedTrackSource {
    fun downloadedTrackRawIds(serverId: String): Set<String>

    companion object {
        val None = DownloadedTrackSource { emptySet() }
    }
}

/** The scrobble and mutation outboxes, flushed first on reconnect (§16.14 step 1). */
internal fun interface ReconnectOutboxes {
    suspend fun flush()

    companion object {
        val None = ReconnectOutboxes { }
    }
}

// ---- Errors -------------------------------------------------------------------------------------------

internal fun Throwable.asReaderError(): DomainError = when (this) {
    is TimeoutCancellationException -> DomainError.Transport.Timeout
    is LibraryRequestFailure -> error
    is AuthenticatedEndpointFailure -> error
    else -> mapAccountConnectionFailure(this)
}

/** Subsonic code 70, "the requested data was not found". */
internal fun DomainError.isNotFound(): Boolean =
    (this is DomainError.Server.Known && code == 70) || (this is DomainError.Server.Unknown && code == 70)
