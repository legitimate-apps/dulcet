package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Windows (spec §16.12): a contiguous run of pages presented as one list, all read under one epoch.
 *
 * The consistency rules, in the order this file enforces them:
 *
 * 1. **A page is one server read**, written in one transaction with its entities.
 * 2. **Every page is bracketed**: the reading current when its request was sent (*before*) and a
 *    `getScanStatus` issued after its response arrived (*after*). It joins a guarded window only
 *    when both are idle and both carry the window's stamp ([checkPage]) — never on *after* alone.
 * 3. **A window is torn** by a failed check, by a stored epoch that differs from the current one at
 *    its first live read in this session, or by a changed folder set — and a torn window is never
 *    extended. The check runs BEFORE the write that would advance the window. A torn window is
 *    **rebased**: only the pages intersecting the viewport are re-read, under the current epoch,
 *    every other page is dropped, and the first visible item stays first. The rebased window is
 *    open on both sides: [LibraryPublication.leadingOffset] says where it starts, and
 *    [LibraryWindowHandle.loadBefore] reads towards the top under the same check.
 * 4. **While the server scans**, pages append unguarded and the window is `unverified(scanning)`;
 *    the first reading with no scan running rebases it.
 * 5. **A reading that fails concludes nothing**: the page is not used and nothing is relabelled.
 *    Only a server that answers with no stamp is `unverified(noEpoch)`.
 */
internal abstract class ReaderHandle(
    protected val reader: LibraryReader,
    final override val query: LibraryQuery,
    private val listener: (LibraryPublication) -> Unit,
) : LibraryWindowHandle {
    protected val cache: BoundSeenCache get() = reader.cache
    private var sequence = 0
    protected var closed = false
        private set
    private val jobs = mutableListOf<Job>()

    /**
     * One live operation at a time. A list window shares its lock with every other handle on the
     * same list key ([LibraryReader.listLock]), so two screens on one list have one writer.
     */
    protected open val lock: Mutex by lazy { Mutex() }

    /** In-flight live reads; while positive, cached content is `cached(revalidating)`. */
    protected var inFlight = 0

    /**
     * Set when an open decided to revalidate, BEFORE its first publication: the cached content it
     * paints is already `revalidating`, so no later frame has to correct it (§16.14).
     */
    protected var revalidationPending = false

    /** The last live read's failure, shown beside the cached content until a read succeeds. */
    protected var failure: DomainError? = null

    /** Set when the reader itself threw while serving this screen; cleared by the next success. */
    protected var internalFailure = false

    /**
     * Set when a read of this screen was not sent because the reader went offline while it waited
     * ([ReaderSendRefused], or the offline check before a request): the read was not made, so no
     * failure is recorded, and it is OWED. The next reconnect's revalidation makes it whatever the
     * screen's age — as a refresh would — and clears the flag. A read of the screen's own pages;
     * an owed "load more" beyond them is [owesMore], and the reconnect makes that too.
     */
    protected var readOwed = false

    /** The last publication built, for [emit]'s duplicate check while a reconnect completes. */
    private var lastBuilt: LibraryPublication? = null

    /**
     * Set at a transition for a screen with no read coming ([prepareForReconnect]). Until the
     * reconnect has run every step it goes on saying what it said while offline — never
     * `revalidating` with nothing coming for it, and never `live` before the sequence is complete —
     * so it publishes nothing unless its content changes (a tap still shows at once); at the end it
     * says `live` once. The label follows the read: once the screen starts a read of its own (the
     * person scrolls, or pages), it says `revalidating` from then on, and the flag is cleared.
     */
    private var quietUntilReconnectEnds = false

    /** The last publication's items, which [setViewport] indexes into. */
    protected var published: List<LibraryItem> = emptyList()

    /** Whether opening now would issue a live read; false when the screen is fresh (§16.11 rule 3). */
    protected abstract fun needsRevalidation(): Boolean

    /**
     * The first publication is built from the cache and delivered synchronously, before any request
     * is issued and before any loading state is published (CONF-76).
     */
    fun start() {
        revalidationPending = reader.online && needsRevalidation()
        emitSnapshot()
        if (revalidationPending) {
            launchRead {
                try {
                    performRevalidate(RevalidateCause.Open)
                } finally {
                    revalidationPending = false
                }
            }
        }
    }

    /** Revalidates under this window's lock; the reader's reconnect and epoch refresh call this. */
    suspend fun revalidate(cause: RevalidateCause) {
        guarded { lock.withLock { performRevalidate(cause) } }
    }

    /** Whether this window is waiting for a reading with a stamp that holds still. */
    open fun awaitsQuietEpoch(): Boolean = false

    /** Called with the window's lock held. */
    protected abstract suspend fun performRevalidate(requested: RevalidateCause)

    /**
     * The cause a revalidation runs under: a refresh if a read is [readOwed] — made whatever the
     * screen's age — and the flag cleared, for a read that is refused again sets it again.
     */
    protected fun takeOwed(cause: RevalidateCause): RevalidateCause {
        if (!readOwed) return cause
        readOwed = false
        return RevalidateCause.Refresh
    }

    /**
     * Records why a read failed — unless it was refused unsent because the reader went offline
     * ([ReaderSendRefused]): that read is owed, and the screen keeps saying what it said.
     */
    protected fun readFailed(thrown: Throwable) {
        if (thrown is ReaderSendRefused) readOwed = true else failure = thrown.asReaderError()
    }

    /**
     * Whether a read beyond the screen's own pages is owed — a "load more" refused unsent because
     * the reader went offline, made by the next reconnect after the screen's revalidation.
     */
    protected open fun owesMore(): Boolean = false

    /** Builds the publication from the cache as it stands now. */
    protected abstract fun snapshot(): LibraryPublication

    abstract fun mentionsAny(rawIds: Set<String>): Boolean

    fun republish() {
        if (!closed) emitSnapshot()
    }

    /**
     * Whether a reconnect's revalidation of this screen issues a read. Defaults to
     * [needsRevalidation]; a screen that re-reads on every reconnect whatever its age says so.
     */
    protected open fun readsOnReconnect(): Boolean = needsRevalidation()

    /**
     * The reader has just come back online and is about to revalidate every screen in turn
     * ([LibraryReader.reconnect]). A screen whose read is coming is republished now, so it says
     * `cached(revalidating)` or `loading` at once rather than `offline` until its turn. A fresh one
     * publishes nothing until the reconnect has run every step, and then says `live` once: never a
     * spinner with nothing coming, and never `live` before the sequence is complete. The flag is set
     * here only when [readsOnReconnect] says a read is coming, the revalidation decides the same way
     * before it publishes anything, and it clears the flag whether or not it reads.
     */
    fun prepareForReconnect() {
        if (closed) return
        val coming = try {
            readOwed || owesMore() || readsOnReconnect()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            true // the revalidation will run, and will say what failed
        }
        if (coming) revalidationPending = true
        quietUntilReconnectEnds = !coming
        if (coming) emitSnapshot()
    }

    /**
     * Publishes the cache as it stands. If building that publication itself throws, the screen is
     * told so with a publication that needs nothing from the cache — never silence, never a crash.
     */
    protected fun emitSnapshot() {
        if (closed) return
        val publication = try {
            snapshot()
        } catch (failure: CancellationException) {
            throw failure
        } catch (thrown: Throwable) {
            internalFailure = true
            LibraryPublication(
                query, 0, LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure), null, null, null,
                emptyList(), LibraryItemsState.Unavailable, LibraryItemsOrder.Server,
            )
        }
        emit(publication)
    }

    /**
     * Delivers to the listener. A listener that throws is the listener's failure, not this
     * screen's or the reader's: it is dropped here, so it can never abort the step that published —
     * a reconnect, a revalidation, a page (the facade guards its own listeners the same way).
     */
    private fun emit(publication: LibraryPublication) {
        if (closed) return
        // While a reconnect completes, a frame identical to the last one says nothing new, so it is
        // not delivered: the sequence's end publishes each screen once more.
        if (reader.completingReconnect && publication == lastBuilt) return
        lastBuilt = publication
        sequence += 1
        published = publication.items
        try {
            listener(publication.copy(sequence = sequence))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
        }
    }

    /** Converts anything a live operation throws into a publication that says the screen failed. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Throwable) {
            inFlight = 0
            revalidationPending = false
            internalFailure = true
            emitSnapshot()
        }
    }

    protected fun launchRead(block: suspend () -> Unit) {
        if (closed) return
        val job = reader.scope.launch { guarded { lock.withLock { block() } } }
        jobs += job
        job.invokeOnCompletion { jobs -= job }
    }

    /** Runs one live read, publishing `revalidating` while it is in flight. */
    protected suspend fun <T> live(block: suspend () -> T): T {
        // From here the in-flight count carries "revalidating"; the open's pending flag must not
        // outlive the read, or the final publication would still say revalidating.
        revalidationPending = false
        quietUntilReconnectEnds = false
        inFlight += 1
        try {
            return block()
        } finally {
            inFlight -= 1
        }
    }

    /**
     * Re-reads this screen now, whatever its age. While the reader is offline it does nothing:
     * there is nothing it may send (§16.14). A "Try again" for a screen saying `offline`, or the
     * reconnect failure that keeps the reader offline ([LibraryReader.standstill]), must call
     * reconnect instead, which runs the whole sequence and then re-reads every screen.
     */
    override fun refresh() {
        reader.checkConfined()
        if (reader.online) launchRead { performRevalidate(RevalidateCause.Refresh) }
    }

    override fun close() {
        reader.checkConfined()
        if (closed) return
        closed = true
        jobs.toList().forEach { it.cancel() }
        reader.lookAhead.windowClosed(this)
        reader.closed(this)
    }

    // ---- Shared presentation ------------------------------------------------------------------------

    /** Freshness of content that exists in the cache (§16.14). */
    protected fun cachedFreshness(asOfWall: Long?, liveUnderCurrentEpoch: Boolean): LibraryFreshness {
        val error = failure
        return when {
            !reader.online -> LibraryFreshness.Cached(asOfWall, offlineReason())
            // The label follows the read: a read in flight says so, quiet or not.
            inFlight > 0 || revalidationPending -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.Revalidating)
            reader.completingReconnect && quietUntilReconnectEnds -> LibraryFreshness.Cached(asOfWall, offlineReason())
            // Nothing is `live` until a reconnect has run every step (§16.14).
            reader.completingReconnect -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.Revalidating)
            internalFailure -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.InternalFailure)
            error != null -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.Failed(error))
            liveUnderCurrentEpoch -> LibraryFreshness.Live
            else -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.Stale)
        }
    }

    /** Freshness when nothing is cached. A read in flight or coming says so, as [cachedFreshness] does. */
    protected fun emptyFreshness(): LibraryFreshness {
        val error = failure
        return when {
            !reader.online -> LibraryFreshness.Unavailable(
                when (val standstill = reader.standstill) {
                    null -> LibraryUnavailableReason.NotCachedOffline
                    is ReaderStandstill.Failed -> LibraryUnavailableReason.Failed(standstill.error)
                    ReaderStandstill.InternalFailure -> LibraryUnavailableReason.InternalFailure
                },
            )
            inFlight > 0 || revalidationPending || reader.completingReconnect -> LibraryFreshness.Loading
            internalFailure -> LibraryFreshness.Unavailable(LibraryUnavailableReason.InternalFailure)
            error != null -> LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(error))
            else -> LibraryFreshness.Loading
        }
    }

    /**
     * Why cached content is shown while the reader is offline: `offline`, or — when the platform
     * reports the server reachable and the last reconnect failed in a way no timer retries — that
     * failure ([LibraryReader.standstill]).
     */
    protected fun offlineReason(): LibraryCachedReason = when (val standstill = reader.standstill) {
        null -> LibraryCachedReason.Offline
        is ReaderStandstill.Failed -> LibraryCachedReason.Failed(standstill.error)
        ReaderStandstill.InternalFailure -> LibraryCachedReason.InternalFailure
    }

    /** Items for rows read in one batch; per-user state is overlaid, never written back. */
    protected fun itemsOf(rows: List<CachedListRow>, dedupe: Boolean): Pair<List<LibraryItem>, List<Int>> {
        val pending = reader.overlay.pending(cache.serverId, rows.mapTo(mutableSetOf()) { CacheListMember(it.kind, it.rawId) })
        val downloaded = reader.downloads.downloadedTrackRawIds(cache.serverId)
        val seen = mutableSetOf<Pair<CacheItemKind, String>>()
        val items = mutableListOf<LibraryItem>()
        val positions = mutableListOf<Int>()
        rows.forEach { row ->
            // Deduplicated by opaque id within a window in any case (§16.12). A playlist keeps its
            // duplicate entries (§18.6), so its caller passes dedupe = false.
            if (dedupe && !seen.add(row.kind to row.rawId)) return@forEach
            val overlay = pending[CacheListMember(row.kind, row.rawId)]
            val item: LibraryItem = row.album?.toItem(overlay)
                ?: row.artist?.toItem(overlay)
                ?: row.track?.toItem(overlay, reader.trackPlayability(row.rawId, downloaded))
                ?: row.playlist?.toItem()
                ?: LibraryItem.Genre(row.rawId)
            items += item
            positions += row.position
        }
        return items to positions
    }

    /** Refreshes last access for a shown list, at most once per [LibraryReaderConfig.touchIntervalMillis]. */
    protected fun touchShown(listKey: String) {
        val now = cache.now()
        val last = reader.lastTouches[listKey]
        if (last != null && now - last < reader.config.touchIntervalMillis) return
        reader.lastTouches[listKey] = now
        cache.touchList(listKey)
    }
}

/** How one list query is requested and cached. */
internal data class ListRequestSpec(
    val endpoint: String,
    val parameters: Map<String, String>,
    val listKey: String,
    /** Offset-paged under the window rules, or one single response. */
    val paged: Boolean,
    /** The paging parameter's name: `size` for `getAlbumList2`, `count` for `getSongsByGenre`. */
    val sizeParameter: String?,
    /** For a single-page list: the size asked for (none for one-response endpoints). */
    val singlePageSize: Int?,
    val parse: (String) -> ParsedList,
) {
    companion object {
        fun of(query: LibraryQuery): ListRequestSpec = when (query) {
            is LibraryQuery.AlbumList -> albumList(query, homeRowSize = null)
            is LibraryQuery.Artists -> whole("getArtists", listOfNotNull(query.musicFolderId?.let { "musicFolderId" to it }).toMap()) {
                val artists = parseReaderArtists(it)
                ParsedList(artists.map { a -> CacheListMember(CacheItemKind.Artist, a.rawId) }, CacheEntities(artists = artists))
            }
            LibraryQuery.Playlists -> whole("getPlaylists", emptyMap()) {
                val playlists = parseReaderPlaylists(it)
                ParsedList(playlists.map { p -> CacheListMember(CacheItemKind.Playlist, p.rawId) }, CacheEntities(playlists = playlists))
            }
            LibraryQuery.Starred -> whole("getStarred2", emptyMap()) {
                val entities = parseReaderStarred(it)
                ParsedList(
                    entities.artists.map { a -> CacheListMember(CacheItemKind.Artist, a.rawId) } +
                        entities.albums.map { a -> CacheListMember(CacheItemKind.Album, a.rawId) } +
                        entities.tracks.map { t -> CacheListMember(CacheItemKind.Track, t.rawId) },
                    entities,
                )
            }
            LibraryQuery.Genres -> whole("getGenres", emptyMap()) {
                ParsedList(parseReaderGenres(it).map { g -> CacheListMember(CacheItemKind.Genre, g) }, CacheEntities())
            }
            is LibraryQuery.SongsByGenre -> {
                val parameters = listOfNotNull("genre" to query.genre, query.musicFolderId?.let { "musicFolderId" to it }).toMap()
                ListRequestSpec("getSongsByGenre", parameters, canonicalListKey("getSongsByGenre", parameters), true, "count", null) {
                    val tracks = parseReaderSongsByGenre(it)
                    ParsedList(tracks.map { t -> CacheListMember(CacheItemKind.Track, t.rawId) }, CacheEntities(tracks = tracks))
                }
            }
            is LibraryQuery.Album, is LibraryQuery.Artist, is LibraryQuery.Playlist ->
                error("a detail query is not a list")
        }

        fun homeRow(row: LibraryHomeRow, homeRowSize: Int): ListRequestSpec = when (row) {
            is LibraryHomeRow.Albums -> albumList(LibraryQuery.AlbumList(row.type), homeRowSize)
            LibraryHomeRow.Favourites -> of(LibraryQuery.Starred)
        }

        private fun albumList(query: LibraryQuery.AlbumList, homeRowSize: Int?): ListRequestSpec {
            val parameters = listOfNotNull(
                "type" to query.type.wireName,
                query.fromYear?.let { "fromYear" to it.toString() },
                query.toYear?.let { "toYear" to it.toString() },
                query.genre?.let { "genre" to it },
                query.musicFolderId?.let { "musicFolderId" to it },
            ).toMap()
            val key = canonicalListKey("getAlbumList2", parameters)
            val paged = query.type.paged && homeRowSize == null
            return ListRequestSpec(
                endpoint = "getAlbumList2",
                parameters = parameters,
                // A home row is one page of 20 and must not share a key with the windowed grid of
                // the same request, or writing the row would replace the grid's cached pages.
                listKey = if (homeRowSize != null) "$key#home" else key,
                paged = paged,
                sizeParameter = "size",
                singlePageSize = if (paged) null else (homeRowSize ?: SINGLE_PAGE_SIZE),
            ) {
                val albums = parseReaderAlbumList(it)
                ParsedList(albums.map { a -> CacheListMember(CacheItemKind.Album, a.rawId) }, CacheEntities(albums = albums))
            }
        }

        private fun whole(endpoint: String, parameters: Map<String, String>, parse: (String) -> ParsedList) =
            ListRequestSpec(endpoint, parameters, canonicalListKey(endpoint, parameters), false, null, null, parse)

        /** Activity-ordered lists and `random` are one page of this size (§16.9). */
        const val SINGLE_PAGE_SIZE = 100
    }
}

internal data class ParsedList(val members: List<CacheListMember>, val entities: CacheEntities)

/** A page read, bracketed by its two readings. */
private data class PageRead(
    val offset: Int,
    val requested: Int,
    val parsed: ParsedList,
    val totalCount: Int?,
    val before: ScanStatusReading?,
    val after: ScanStatusReading?,
    val afterError: DomainError?,
    val issueSeq: Long,
)

private enum class PageMode { Append, Replace, Prepend }

/** One list screen: a paged window, or a single response. */
internal class ListWindow(
    reader: LibraryReader,
    query: LibraryQuery,
    private val spec: ListRequestSpec,
    listener: (LibraryPublication) -> Unit,
) : ReaderHandle(reader, query, listener) {
    private val pageSize = reader.config.pageSize

    override val lock: Mutex get() = reader.listLock(spec.listKey)

    /**
     * The viewport, in server positions. A new handle opens at the TOP of its list, whatever depth
     * a previous session's window was left at: a window that starts deeper is rebased around the
     * top on its first live read, so the start of a list is never unreachable (review finding B2).
     */
    private var viewport: IntRange = 0..0

    /**
     * Bumped by every viewport the shell sets. A rebase's re-anchor moves [viewport] only if this is
     * unchanged since the rebase began: a viewport the person set meanwhile wins.
     */
    private var viewportSerial = 0

    /** Positions of the last publication's items, parallel to [published]. */
    private var publishedPositions: List<Int> = emptyList()

    /** Bumped by every rebase; a page read from an older generation is discarded on arrival. */
    private var generation = 0

    /**
     * Extends ("load more", "load before") that were not made because the reader was offline —
     * asked for while offline, or refused unsent at the queue. They are OWED, like a refused page
     * read ([readOwed]), but beyond the screen's own pages, so a revalidation of the viewport does
     * not make them: the reconnect re-issues each after this screen's revalidation (§16.14).
     */
    private val owedExtends = mutableSetOf<PageMode>()

    override fun owesMore(): Boolean = owedExtends.isNotEmpty()

    private var pendingAnchor: LibraryAnchor? = null

    /**
     * Whether this list was read live in this session — by this handle or an earlier one for the
     * same list (§16.12 tear rule 2 applies at the FIRST live read of the session).
     */
    private val liveThisSession: Boolean get() = spec.listKey in reader.liveListReads

    override fun needsRevalidation(): Boolean {
        val epoch = reader.sessionEpoch ?: return true
        val state = cache.listState(spec.listKey) ?: return true
        return mustRebase(state, epoch) || !readRecently(state, epoch)
    }

    /** A whole (unpaged) list is re-read by every revalidation; a paged one keeps the 60-second rule. */
    override fun readsOnReconnect(): Boolean = !spec.paged || needsRevalidation()

    override fun mentionsAny(rawIds: Set<String>): Boolean = published.any { it.rawId in rawIds }

    override fun awaitsQuietEpoch(): Boolean = cache.listState(spec.listKey)?.coverage == CacheCoverage.UnverifiedChanging

    // ---- Revalidation -------------------------------------------------------------------------------

    /**
     * Whether the stored window cannot be revalidated in place: its epoch is not the current one
     * (tear rules 2 and 3), it holds unguarded pages and the server is no longer scanning, its stamp
     * kept moving last time, or the viewport lies outside it.
     */
    private fun mustRebase(state: CachedListState, epoch: CatalogEpoch): Boolean =
        state.windowEpoch != epoch.key ||
            (state.coverage == CacheCoverage.UnverifiedScanning && !epoch.scanning) ||
            state.coverage == CacheCoverage.UnverifiedChanging ||
            viewport.first < state.firstLoadedOffset ||
            (viewport.first >= state.endLoadedOffset && state.endLoadedOffset > state.firstLoadedOffset)

    /**
     * Whether this revalidation will read, decided BEFORE anything says `revalidating`: a whole list
     * always; a paged one when it is torn, not read recently under the current epoch, or refreshed.
     * The same rule as [readsOnReconnect], and the one [performRevalidate] applies once it has the
     * epoch.
     */
    private fun readComing(cause: RevalidateCause): Boolean {
        if (!spec.paged) return true
        val epoch = reader.sessionEpoch ?: return true
        val state = cache.listState(spec.listKey) ?: return true
        return cause == RevalidateCause.Refresh || mustRebase(state, epoch) || !readRecently(state, epoch)
    }

    override suspend fun performRevalidate(requested: RevalidateCause) {
        if (closed || !reader.online) return
        val cause = takeOwed(requested)
        // Owed extends are made after the revalidation, whether or not it reads anything. Taken
        // here, so one re-owed meanwhile is made by the next revalidation; and one not yet made when
        // this one throws is owed again, never lost with the failure (round-7 review).
        val extends = owedExtends.toMutableList()
        owedExtends.clear()
        try {
            revalidateThenExtend(cause, extends)
        } catch (thrown: Throwable) {
            owedExtends += extends
            throw thrown
        }
    }

    /** [performRevalidate]'s body; [extends] holds the owed extends not yet made. */
    private suspend fun revalidateThenExtend(cause: RevalidateCause, extends: MutableList<PageMode>) {
        if (!readComing(cause)) {
            // Fresh: nothing is read, so nothing says `revalidating` — and nothing is republished,
            // unless a `revalidating` this handle already published must be taken back.
            if (extends.isNotEmpty()) {
                makeOwedExtends(extends)
                return
            }
            if (revalidationPending) {
                revalidationPending = false
                emitSnapshot()
            }
            return
        }
        if (cause != RevalidateCause.Open && cache.listState(spec.listKey) != null) {
            inFlight += 1
            emitSnapshot()
            inFlight -= 1
        }
        live {
            val epoch = reader.ensureEpoch()
            if (epoch == null) {
                // Not read because the reader went offline meanwhile: owed, not failed.
                if (reader.online) failure = DomainError.Transport.Unreachable else readOwed = true
                return@live
            }
            if (!spec.paged) {
                readWhole(epoch)
                return@live
            }
            val state = cache.listState(spec.listKey)
            val torn = state == null || mustRebase(state, epoch)
            // The 60-second rule spares a fresh window a re-read; it never spares a torn one.
            if (!torn && state != null && cause != RevalidateCause.Refresh && readRecently(state, epoch)) return@live
            if (torn || state == null) rebase(epoch, attempt = 0) else revalidateViewport(epoch)
        }
        if (extends.isNotEmpty()) makeOwedExtends(extends) else emitSnapshot()
    }

    /**
     * Makes the extends that were owed; one refused again is owed again. Each leaves [extends] only
     * once made, so an extend that throws stays in it for [performRevalidate] to owe again.
     */
    private suspend fun makeOwedExtends(extends: MutableList<PageMode>) {
        while (extends.isNotEmpty()) {
            if (closed) return
            val mode = extends.first()
            if (reader.online) extend(mode, confirming = false) else owedExtends += mode
            extends.removeAt(0)
        }
        // An extend that found nothing to read (the window complete, or the viewport moved away)
        // leaves nothing coming: a `revalidating` published at the transition is taken back.
        revalidationPending = false
        emitSnapshot()
    }

    private fun readRecently(state: CachedListState, epoch: CatalogEpoch): Boolean {
        val readAt = reader.liveListReads[spec.listKey] ?: return false
        return state.windowEpoch == epoch.key && cache.now() - readAt < reader.config.revalidateWithinMillis
    }

    private suspend fun readWhole(epoch: CatalogEpoch) {
        val parameters = spec.parameters + listOfNotNull(spec.singlePageSize?.let { "size" to it.toString() })
        val sent = try {
            reader.sendChecked(spec.endpoint, parameters)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Throwable) {
            readFailed(thrown)
            return
        }
        val parsed = try {
            spec.parse(sent.response.body)
        } catch (thrown: LibraryRequestFailure) {
            failure = thrown.error
            return
        }
        val now = cache.now()
        cache.writeWholeList(
            CacheWriteStamp(sent.issueSeq, now, epoch.key),
            CacheEntitySource.ListPage,
            CachedListState(spec.listKey, epoch.key, epoch.folderIds, 0, parsed.members.size, sent.response.totalCount, CacheCoverage.Complete, now, now, sent.issueSeq),
            parsed.members,
            parsed.entities,
        )
        markLive()
        cache.evictIfNeeded()
    }

    private suspend fun revalidateViewport(epoch: CatalogEpoch) {
        for (offset in viewportPageOffsets()) {
            val gen = generation
            val read = readPage(offset) ?: return
            if (gen != generation || closed) return
            when (checkPage(epoch.stamp, read.before, read.after)) {
                PageCheck.Unread -> {
                    failure = read.afterError
                    return
                }
                PageCheck.Fired, PageCheck.ScanEnded -> {
                    rebase(reader.sessionEpoch ?: epoch, attempt = 1)
                    return
                }
                PageCheck.Guarded -> writePage(read, CacheCoverage.Open, epoch, PageMode.Replace)
                PageCheck.Scanning -> writePage(read, CacheCoverage.UnverifiedScanning, epoch, PageMode.Replace)
                PageCheck.NoEpoch -> writePage(read, CacheCoverage.UnverifiedNoEpoch, epoch, PageMode.Replace)
            }
        }
        markLive()
    }

    /**
     * Rebase (§16.12): re-read only the pages intersecting the viewport under [epoch], and drop
     * every other page, in one transaction per rebase. The first visible item stays first.
     *
     * A page whose stamp moved, or whose scan ended while it was read, is re-read under the newer
     * reading, up to [LibraryReaderConfig.maxTearRetries] times. If the stamp is still moving after
     * that with no scan reported, the window is written as `unverified(changing)` — an honest label,
     * not "scanning" — and the next revalidation rebases it again.
     */
    private suspend fun rebase(epoch: CatalogEpoch, attempt: Int, anchorBefore: AnchorSource? = firstVisibleAnchorSource()) {
        generation += 1
        val gen = generation
        val serial = viewportSerial
        val offsets = viewportPageOffsets()
        val reads = coroutineScope { offsets.map { offset -> async { readPage(offset) } }.awaitAll() }
        if (gen != generation || closed) return
        val pages = reads.filterNotNull().sortedBy { it.offset }
        if (pages.size != reads.size) return
        val checks = pages.map { checkPage(epoch.stamp, it.before, it.after) }
        if (PageCheck.Unread in checks) {
            failure = pages[checks.indexOf(PageCheck.Unread)].afterError
            return
        }
        val moved = PageCheck.Fired in checks || PageCheck.ScanEnded in checks
        val latest = reader.sessionEpoch ?: epoch
        if (moved && attempt < reader.config.maxTearRetries) {
            rebase(latest, attempt + 1, anchorBefore)
            return
        }
        val coverage = when {
            PageCheck.Scanning in checks -> CacheCoverage.UnverifiedScanning
            moved -> CacheCoverage.UnverifiedChanging
            PageCheck.NoEpoch in checks -> CacheCoverage.UnverifiedNoEpoch
            else -> CacheCoverage.Open
        }
        val windowEpoch = if (moved) latest else epoch
        val first = pages.first().offset
        val members = mutableListOf<CacheListMember>()
        val ids = mutableSetOf<Pair<CacheItemKind, String>>()
        var end = first
        val entities = mutableListOf<CacheEntities>()
        var lastRows = 0
        for (page in pages) {
            if (page.offset > end) break // a gap: keep the contiguous run only
            page.parsed.members.forEach { if (ids.add(it.kind to it.rawId)) members += it }
            end = page.offset + page.parsed.members.size
            lastRows = page.parsed.members.size
            entities += page.parsed.entities
        }
        val total = pages.lastOrNull { it.totalCount != null }?.totalCount
        if (first > 0 && (members.isEmpty() || (total != null && first >= total))) {
            // The anchor lies at or past the list's end — it shrank. Re-anchor on the last page that
            // exists, so no stored row at or beyond the total the server just reported is kept,
            // shown or labelled live. With no total to go by (or one that contradicts the empty
            // page), on the top. Each re-anchor reads a page strictly before this one, so it ends.
            // Offline, it stops here: nothing is sent (§16.14), and the window stays as stored.
            if (!reader.online) return
            val next = if (moved) latest else epoch
            if (viewportSerial != serial) {
                // The person set a viewport while this rebase read: theirs wins, and the window is
                // rebased around it instead. Bounded by the viewports the shell sends meanwhile.
                rebase(next, attempt, firstVisibleAnchorSource())
                return
            }
            val lastExisting = if (total != null && total in 1..first) total - 1 else 0
            viewport = lastExisting..lastExisting
            rebase(next, attempt, anchorBefore)
            return
        }
        val complete = coverage == CacheCoverage.Open && first == 0 &&
            (lastRows == 0 || (total != null && end >= total))
        val now = cache.now()
        val seq = pages.maxOf { it.issueSeq }
        cache.writeWindowPage(
            stamp = CacheWriteStamp(seq, now, windowEpoch.key),
            source = CacheEntitySource.ListPage,
            state = CachedListState(
                spec.listKey, windowEpoch.key, windowEpoch.folderIds, first, end, total,
                if (complete) CacheCoverage.Complete else coverage, now, now, seq,
            ),
            pageStart = first,
            replacedEnd = end,
            members = members,
            entities = entities.merge(),
            // Exactly the rows just read: an empty read keeps nothing.
            keepRange = first until first + members.size,
        )
        cache.evictIfNeeded()
        markLive()
        pendingAnchor = anchorAfterRebase(anchorBefore)
    }

    /**
     * One page read and its *after* reading. The *before* is the reading that was current when the
     * page request was SENT ([LibraryReader.send]); every successful *after* becomes the session's
     * reading, and so the next page's *before*.
     *
     * Offline, it sends nothing (§16.14) and returns null WITHOUT recording a failure — the screen
     * says offline, not that a read failed — and the read is owed to the next reconnect
     * ([readOwed]): no page request once the reader is offline, including one refused at the queue
     * ([ReaderSendRefused]), and for a page answered after the unreachable report, no *after*
     * reading, so the page is not used.
     */
    private suspend fun readPage(offset: Int, owe: () -> Unit = { readOwed = true }): PageRead? {
        val sizeParameter = spec.sizeParameter ?: return null
        if (!reader.online) {
            owe()
            return null
        }
        val parameters = spec.parameters + (sizeParameter to pageSize.toString()) + ("offset" to offset.toString())
        val sent = try {
            reader.sendChecked(spec.endpoint, parameters)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Throwable) {
            if (thrown is ReaderSendRefused) owe() else readFailed(thrown)
            return null
        }
        val parsed = try {
            spec.parse(sent.response.body)
        } catch (thrown: LibraryRequestFailure) {
            failure = thrown.error
            return null
        }
        // The *after* reading is issued only once the page's response has arrived (§16.12).
        if (!reader.online) {
            owe()
            return null
        }
        var afterError: DomainError? = null
        val after = try {
            reader.epochReader.readScanStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: ReaderSendRefused) {
            owe()
            return null
        } catch (thrown: Throwable) {
            afterError = thrown.asReaderError()
            null
        }
        after?.let(reader::adoptScanStatus)
        return PageRead(offset, pageSize, parsed, sent.response.totalCount, sent.before, after, afterError, sent.issueSeq)
    }

    /** Writes one page into an intact window, deduplicating against the rest of it. */
    private fun writePage(read: PageRead, coverage: CacheCoverage, epoch: CatalogEpoch, mode: PageMode) {
        val state = cache.listState(spec.listKey)
        val rows = read.parsed.members.size
        val pageRange = read.offset until read.offset + read.requested
        val existing = cache.listMembers(spec.listKey)
        val elsewhere = existing
            .filter { it.position !in pageRange }
            .mapTo(mutableSetOf()) { it.member.kind to it.member.rawId }
        val members = read.parsed.members.filter { elsewhere.add(it.kind to it.rawId) }
        val previousRows = existing.count { it.position in pageRange }
        val first = minOf(state?.firstLoadedOffset ?: read.offset, read.offset)
        val end = when (mode) {
            PageMode.Append -> read.offset + rows
            PageMode.Replace -> maxOf(state?.endLoadedOffset ?: (read.offset + rows), read.offset + rows)
            PageMode.Prepend -> state?.endLoadedOffset ?: (read.offset + rows)
        }
        val total = read.totalCount ?: state?.total
        val complete = first == 0 && (
            (rows == 0 && mode == PageMode.Append) ||
                (total != null && end >= total) ||
                // Re-reading a page of a complete window keeps it complete only if the page kept its size.
                (mode == PageMode.Replace && state?.coverage == CacheCoverage.Complete && members.size == previousRows)
            )
        val mergedCoverage = when {
            coverage != CacheCoverage.Open -> coverage
            state != null && state.coverage != CacheCoverage.Open && state.coverage != CacheCoverage.Complete -> state.coverage
            complete -> CacheCoverage.Complete
            else -> CacheCoverage.Open
        }
        val replacedEnd = when (mode) {
            PageMode.Prepend -> minOf(read.offset + read.requested, state?.firstLoadedOffset ?: Int.MAX_VALUE)
            else -> read.offset + read.requested
        }
        val now = cache.now()
        cache.writeWindowPage(
            stamp = CacheWriteStamp(read.issueSeq, now, epoch.key),
            source = CacheEntitySource.ListPage,
            state = CachedListState(
                spec.listKey, state?.windowEpoch ?: epoch.key, state?.folderIds ?: epoch.folderIds,
                first, end, total, mergedCoverage, minOf(state?.fetchedAtWall ?: now, now), now,
                maxOf(state?.issueSeq ?: 0, read.issueSeq),
            ),
            pageStart = read.offset,
            replacedEnd = replacedEnd,
            members = members,
            entities = read.parsed.entities,
        )
        reader.liveListReads[spec.listKey] = now
        cache.evictIfNeeded()
    }

    // ---- Paging -------------------------------------------------------------------------------------

    override fun loadMore() {
        reader.checkConfined()
        if (closed || !spec.paged) return
        if (!reader.online) {
            owedExtends += PageMode.Append // made by the next reconnect (§16.14)
            return
        }
        launchRead { extend(PageMode.Append, confirming = false) }
    }

    override fun loadBefore() {
        reader.checkConfined()
        if (closed || !spec.paged) return
        if (!reader.online) {
            owedExtends += PageMode.Prepend
            return
        }
        launchRead { extend(PageMode.Prepend, confirming = false) }
    }

    /**
     * Extends the window by one page at its end ([PageMode.Append]) or its start
     * ([PageMode.Prepend]). The bracketed check runs BEFORE the write that would advance the
     * window; anything but [PageCheck.Guarded] on an intact window leaves the window as it was.
     */
    private suspend fun extend(mode: PageMode, confirming: Boolean) {
        val state = cache.listState(spec.listKey) ?: return
        val offset = when (mode) {
            PageMode.Append -> {
                if (state.coverage == CacheCoverage.Complete) return
                // Never more than one page beyond the viewport (§16.12).
                if (!confirming && state.endLoadedOffset > viewport.last + pageSize) return
                state.endLoadedOffset
            }
            PageMode.Prepend -> {
                if (state.firstLoadedOffset == 0) return
                if (viewport.first >= state.firstLoadedOffset + pageSize) return
                maxOf(0, state.firstLoadedOffset - pageSize)
            }
            PageMode.Replace -> return
        }
        val epoch = reader.ensureEpoch() ?: run {
            if (!reader.online) owedExtends += mode
            return
        }
        // Tear rule 2: a window not yet read live this session is revalidated, not extended.
        if (!liveThisSession) {
            performRevalidate(RevalidateCause.Open)
            return
        }
        live {
            val gen = generation
            val read = readPage(offset) { owedExtends += mode } ?: return@live
            if (gen != generation || closed) return@live
            val unguardedWindow = state.coverage != CacheCoverage.Open && state.coverage != CacheCoverage.Complete
            when (checkPage(windowStamp(state), read.before, read.after)) {
                PageCheck.Unread -> failure = read.afterError
                PageCheck.Fired, PageCheck.ScanEnded -> rebase(reader.sessionEpoch ?: epoch, attempt = 1)
                PageCheck.Scanning -> writePage(read, CacheCoverage.UnverifiedScanning, epoch, mode)
                PageCheck.NoEpoch -> writePage(read, CacheCoverage.UnverifiedNoEpoch, epoch, mode)
                PageCheck.Guarded -> if (unguardedWindow) {
                    // The window holds unguarded pages and the server is now idle under one stamp:
                    // that is the reading that ends scanning mode, so the window is rebased.
                    rebase(reader.sessionEpoch ?: epoch, attempt = 1)
                } else {
                    failure = null
                    writePage(read, CacheCoverage.Open, epoch, mode)
                    val rows = read.parsed.members.size
                    val after = cache.listState(spec.listKey)
                    // A short page is only a CANDIDATE end: with no total it is confirmed by one
                    // more request, and only an empty page (or the total) completes the window.
                    if (mode == PageMode.Append && !confirming && rows in 1 until read.requested &&
                        after?.total == null && after?.coverage != CacheCoverage.Complete
                    ) {
                        extend(PageMode.Append, confirming = true)
                    }
                }
            }
        }
        emitSnapshot()
    }

    private fun windowStamp(state: CachedListState): String? {
        val key = state.windowEpoch ?: return null
        return stampOfEpochKey(key)
    }

    /**
     * Any range is accepted and clamped to the latest publication: a shell's indexes can be past
     * its end once a revalidation has shortened the list, and that is a moment to keep reading, never
     * a failure. A range entirely past the end means the last item; a reversed one, its first index.
     */
    override fun setViewport(firstIndex: Int, lastIndex: Int) {
        reader.checkConfined()
        if (closed || publishedPositions.isEmpty()) return
        val from = firstIndex.coerceIn(0, publishedPositions.lastIndex)
        val to = lastIndex.coerceIn(from, publishedPositions.lastIndex)
        viewport = publishedPositions[from]..publishedPositions[to]
        viewportSerial += 1
        if (query is LibraryQuery.AlbumList && published.isNotEmpty()) {
            val itemsFrom = firstIndex.coerceIn(0, published.lastIndex)
            val itemsTo = lastIndex.coerceIn(itemsFrom, published.lastIndex)
            reader.lookAhead.viewportChanged(this, published, itemsFrom, itemsTo)
        }
    }

    // ---- Presentation -------------------------------------------------------------------------------

    private fun markLive() {
        failure = null
        internalFailure = false
        reader.liveListReads[spec.listKey] = cache.now()
    }

    override fun snapshot(): LibraryPublication {
        val state = cache.listState(spec.listKey)
        if (state == null || (state.endLoadedOffset == state.firstLoadedOffset && !liveThisSession && state.coverage != CacheCoverage.Complete)) {
            return localViewOrEmpty()
        }
        touchShown(spec.listKey)
        val (read, readPositions) = itemsOf(cache.listRows(spec.listKey), dedupe = true)
        // Pending playlist creates, deletes and renames are overlaid like a pending star (§18.6).
        val items = if (query == LibraryQuery.Playlists) reader.playlistOverlay.overlayList(read) else read
        val positions = if (items === read) readPositions else items.indices.toList()
        publishedPositions = positions
        val current = reader.sessionEpoch
        val liveUnderCurrent = liveThisSession && current != null && state.windowEpoch == current.key
        val anchor = pendingAnchor.also { pendingAnchor = null }
        return LibraryPublication(
            query = query,
            sequence = 0,
            freshness = cachedFreshness(state.fetchedAtWall, liveUnderCurrent),
            coverage = state.coverage.toPublic(),
            total = state.total,
            header = null,
            items = items,
            itemsState = LibraryItemsState.Present,
            order = LibraryItemsOrder.Server,
            anchor = anchor,
            leadingOffset = positions.firstOrNull() ?: state.firstLoadedOffset,
        )
    }

    /**
     * A list never read: offline, the local view of the cached entities of its kind, sorted
     * locally and labelled as such (§16.14); otherwise nothing, loading or unavailable.
     */
    private fun localViewOrEmpty(): LibraryPublication {
        val local: List<LibraryItem> = if (!reader.online) {
            when (query) {
                is LibraryQuery.AlbumList -> cache.localAlbums().let { albums ->
                    val members = albums.map { CacheListMember(CacheItemKind.Album, it.record.rawId) }
                    val pending = reader.overlay.pending(cache.serverId, members.toSet())
                    albums.zip(members) { album, member -> album.toItem(pending[member]) }
                }
                is LibraryQuery.Artists -> cache.localArtists().let { artists ->
                    val members = artists.map { CacheListMember(CacheItemKind.Artist, it.record.rawId) }
                    val pending = reader.overlay.pending(cache.serverId, members.toSet())
                    artists.zip(members) { artist, member -> artist.toItem(pending[member]) }
                }
                // Never read, offline: only playlists created on this device can be shown.
                LibraryQuery.Playlists -> reader.playlistOverlay.overlayList(emptyList())
                else -> emptyList()
            }
        } else {
            emptyList()
        }
        publishedPositions = local.indices.toList()
        val freshness = if (local.isNotEmpty()) {
            LibraryFreshness.Cached(null, offlineReason())
        } else {
            emptyFreshness()
        }
        return LibraryPublication(
            query = query,
            sequence = 0,
            freshness = freshness,
            coverage = if (local.isNotEmpty()) LibraryCoverage.Open else null,
            total = null,
            header = null,
            items = local,
            itemsState = if (local.isEmpty() && freshness == LibraryFreshness.Loading) LibraryItemsState.Loading else LibraryItemsState.Present,
            order = if (local.isNotEmpty()) LibraryItemsOrder.LocalView else LibraryItemsOrder.Server,
        )
    }

    // ---- Viewport geometry --------------------------------------------------------------------------

    /** Offsets of the pages intersecting the viewport, aligned to the page size. */
    private fun viewportPageOffsets(): List<Int> {
        val first = (viewport.first / pageSize) * pageSize
        val last = (viewport.last / pageSize) * pageSize
        return (first..last step pageSize).toList().ifEmpty { listOf(0) }
    }

    private data class AnchorSource(val firstVisibleIndex: Int, val firstVisiblePosition: Int, val order: List<String>)

    private fun firstVisibleAnchorSource(): AnchorSource? {
        if (published.isEmpty()) return null
        val index = publishedPositions.indexOfFirst { it >= viewport.first }.takeIf { it >= 0 } ?: 0
        return AnchorSource(index, publishedPositions.getOrElse(index) { 0 }, published.map { it.rawId })
    }

    /**
     * Keeps the first visible item first across a rebase (§16.12): by id; else the nearest item
     * that preceded it in the old order and survives; else the numeric offset, clamped.
     */
    private fun anchorAfterRebase(before: AnchorSource?): LibraryAnchor? {
        before ?: return null
        val members = cache.listMembers(spec.listKey)
        val newIds = members.map { it.member.rawId }
        val byId = newIds.indexOf(before.order[before.firstVisibleIndex])
        if (byId >= 0) return LibraryAnchor(newIds[byId], byId)
        for (index in before.firstVisibleIndex - 1 downTo 0) {
            val found = newIds.indexOf(before.order[index])
            if (found >= 0) return LibraryAnchor(newIds[found], found)
        }
        val start = members.firstOrNull()?.position ?: 0
        val clamped = (before.firstVisiblePosition - start).coerceIn(0, (newIds.size - 1).coerceAtLeast(0))
        return LibraryAnchor(null, clamped)
    }
}

/** The stamp inside a stored epoch key (see [catalogEpochKey]). */
private fun stampOfEpochKey(key: String): String? = try {
    val array = LIBRARY_JSON.parseToJsonElement(key) as? kotlinx.serialization.json.JsonArray
    (array?.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content
} catch (_: IllegalArgumentException) {
    null
}

private fun List<CacheEntities>.merge(): CacheEntities = CacheEntities(
    artists = flatMap { it.artists },
    albums = flatMap { it.albums },
    tracks = flatMap { it.tracks },
    playlists = flatMap { it.playlists },
)

private fun CacheCoverage.toPublic(): LibraryCoverage = when (this) {
    CacheCoverage.Complete -> LibraryCoverage.Complete
    CacheCoverage.Open -> LibraryCoverage.Open
    CacheCoverage.UnverifiedScanning -> LibraryCoverage.UnverifiedScanning
    CacheCoverage.UnverifiedNoEpoch -> LibraryCoverage.UnverifiedNoEpoch
    CacheCoverage.UnverifiedChanging -> LibraryCoverage.UnverifiedChanging
}

// ---- Detail screens -----------------------------------------------------------------------------------

/**
 * One album. An album seen only in a grid opens with its cached summary header at once and only
 * its track list loading (§16.14); one never seen publishes `unavailable` offline.
 */
internal class AlbumDetailWindow(
    reader: LibraryReader,
    private val album: LibraryQuery.Album,
    listener: (LibraryPublication) -> Unit,
) : ReaderHandle(reader, album, listener) {
    private var gone = false

    /** Fresh only when its MEMBERSHIP was read under the current epoch (review finding S1). */
    private fun fresh(cached: CachedAlbum): Boolean =
        cached.detailComplete && reader.detailIsFresh(album.rawId, cached.detailFetchedEpoch)

    override fun needsRevalidation(): Boolean {
        val cached = cache.album(album.rawId) ?: return true
        return !fresh(cached)
    }

    override fun mentionsAny(rawIds: Set<String>): Boolean =
        album.rawId in rawIds || published.any { it.rawId in rawIds }

    override suspend fun performRevalidate(requested: RevalidateCause) {
        if (closed || !reader.online) return
        val cause = takeOwed(requested)
        val cached = cache.album(album.rawId)
        // A detail read live under the current epoch within the interval is not re-read — which is
        // what lets a looked-ahead album open with zero requests (CONF-87). Nothing is read, so
        // nothing is republished either — unless a `revalidating` it published must be taken back.
        if (cause != RevalidateCause.Refresh && cached != null && fresh(cached)) {
            if (revalidationPending) {
                revalidationPending = false
                emitSnapshot()
            }
            return
        }
        reader.lookAhead.cancel(album.rawId)
        if (cached != null && cause != RevalidateCause.Open) {
            inFlight += 1
            emitSnapshot()
            inFlight -= 1
        }
        val result = live {
            reader.ensureEpoch()
            reader.readAlbumDetail(album.rawId)
        }
        when (result) {
            DetailReadResult.Read -> {
                failure = null
                internalFailure = false
                gone = false
            }
            DetailReadResult.Gone -> {
                failure = null
                internalFailure = false
                gone = true
            }
            is DetailReadResult.Failed -> failure = result.error
            DetailReadResult.Refused -> readOwed = true
        }
        emitSnapshot()
    }

    override fun snapshot(): LibraryPublication {
        val cached = cache.album(album.rawId)
        if (cached == null || cached.row.gone) {
            val freshness = when {
                gone || cached?.row?.gone == true -> LibraryFreshness.Unavailable(LibraryUnavailableReason.Gone)
                else -> emptyFreshness()
            }
            return LibraryPublication(
                query, 0, freshness, null, null, null, emptyList(),
                if (freshness == LibraryFreshness.Loading) LibraryItemsState.Loading else LibraryItemsState.Unavailable,
                LibraryItemsOrder.Server,
            )
        }
        cache.touchAlbum(album.rawId)
        val tracks = if (cached.detailComplete) cache.albumTracks(album.rawId) else emptyList()
        val albumMember = CacheListMember(CacheItemKind.Album, album.rawId)
        val pending = reader.overlay.pending(
            cache.serverId,
            tracks.mapTo(mutableSetOf(albumMember)) { CacheListMember(CacheItemKind.Track, it.rawId) },
        )
        val header = cached.toItem(pending[albumMember])
        val itemsState: LibraryItemsState
        val items: List<LibraryItem>
        if (cached.detailComplete) {
            val downloaded = reader.downloads.downloadedTrackRawIds(cache.serverId)
            items = tracks.map { it.toItem(pending[CacheListMember(CacheItemKind.Track, it.rawId)], reader.trackPlayability(it.rawId, downloaded)) }
            itemsState = LibraryItemsState.Present
        } else {
            items = emptyList()
            itemsState = if (reader.online && failure == null && !internalFailure) LibraryItemsState.Loading else LibraryItemsState.Unavailable
        }
        val current = reader.sessionEpoch
        val live = reader.liveDetailReads.containsKey(album.rawId) && current != null &&
            cached.detailFetchedEpoch == current.key
        // A track list read while the server scanned is not a verified membership (review S2).
        val coverage = if (cached.detailComplete && album.rawId in reader.detailsReadWhileScanning) {
            LibraryCoverage.UnverifiedScanning
        } else {
            null
        }
        return LibraryPublication(
            query, 0, cachedFreshness(cached.row.fetchedAtWall, live), coverage, null, header, items, itemsState,
            LibraryItemsOrder.Server,
        )
    }

    override fun loadMore() = Unit

    override fun loadBefore() = Unit

    override fun setViewport(firstIndex: Int, lastIndex: Int) = Unit
}

/** An artist (its albums) or a playlist (its entries, duplicates kept). One response each. */
internal class CollectionDetailWindow(
    reader: LibraryReader,
    query: LibraryQuery,
    listener: (LibraryPublication) -> Unit,
) : ReaderHandle(reader, query, listener) {
    private val queriedId = when (query) {
        is LibraryQuery.Artist -> query.rawId
        is LibraryQuery.Playlist -> query.rawId
        else -> error("not a collection detail")
    }

    /**
     * The id read: a playlist created on this device is opened under its local id and, once the
     * server has created it, reads as the server's id (§18.6). A local id is never sent.
     */
    private val rawId: String
        get() = if (query is LibraryQuery.Playlist) reader.playlistOverlay.resolve(queriedId) else queriedId
    private val isLocalPlaylist: Boolean get() = query is LibraryQuery.Playlist && reader.playlistOverlay.isLocal(rawId)
    private val listKey: String
        get() = when (query) {
            is LibraryQuery.Artist -> canonicalListKey("getArtist", mapOf("id" to rawId))
            else -> playlistDetailListKey(rawId)
        }
    override val lock: Mutex get() = reader.listLock(listKey)
    private var gone = false
    private val liveThisSession: Boolean get() = listKey in reader.liveListReads

    override fun needsRevalidation(): Boolean {
        if (isLocalPlaylist) return false
        val readAt = reader.liveListReads[listKey] ?: return true
        val state = cache.listState(listKey) ?: return true
        return state.windowEpoch != reader.sessionEpoch?.key || cache.now() - readAt >= reader.config.revalidateWithinMillis
    }

    /** An artist or playlist is re-read by every revalidation, whatever its age. */
    override fun readsOnReconnect(): Boolean = true

    override fun mentionsAny(rawIds: Set<String>): Boolean =
        rawId in rawIds || queriedId in rawIds || published.any { it.rawId in rawIds }

    override suspend fun performRevalidate(requested: RevalidateCause) {
        if (closed || !reader.online) return
        if (isLocalPlaylist) {
            emitSnapshot()
            return
        }
        val cause = takeOwed(requested)
        if (cache.listState(listKey) != null && cause != RevalidateCause.Open) {
            inFlight += 1
            emitSnapshot()
            inFlight -= 1
        }
        live {
            val epoch = reader.ensureEpoch()
            var seq = 0L
            try {
                if (query is LibraryQuery.Artist) {
                    val sent = reader.send("getArtist", mapOf("id" to rawId))
                    seq = sent.issueSeq
                    sent.requireOk("getArtist", mapOf("id" to rawId))
                    val now = cache.now()
                    val (artist, albums) = parseReaderArtist(sent.response.body, rawId)
                    cache.writeWholeList(
                        CacheWriteStamp(seq, now, epoch?.key), CacheEntitySource.Detail,
                        CachedListState(listKey, epoch?.key, epoch?.folderIds, 0, albums.size, null, CacheCoverage.Complete, now, now, seq),
                        albums.map { CacheListMember(CacheItemKind.Album, it.rawId) },
                        CacheEntities(artists = listOf(artist), albums = albums),
                    )
                } else {
                    reader.readPlaylistDetail(rawId, epoch) { seq = it }
                }
                failure = null
                internalFailure = false
                gone = false
                reader.liveListReads[listKey] = cache.now()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: ReaderSendRefused) {
                readOwed = true
            } catch (thrown: Throwable) {
                val error = thrown.asReaderError()
                // A not-found is only a finding once the request was actually sent with a sequence.
                if (error.isNotFound() && seq > 0) {
                    gone = true
                    failure = null
                    if (query is LibraryQuery.Artist) cache.markArtistNotFound(rawId) else cache.markPlaylistNotFound(seq, rawId)
                } else {
                    failure = error
                }
            }
        }
        cache.evictIfNeeded()
        emitSnapshot()
    }

    override fun snapshot(): LibraryPublication {
        val header: LibraryItem? = when (query) {
            is LibraryQuery.Artist -> cache.artist(rawId)?.takeUnless { it.row.gone }?.toItem(
                CacheListMember(CacheItemKind.Artist, rawId).let { reader.overlay.pending(cache.serverId, setOf(it))[it] },
            )
            else -> cache.playlist(rawId)?.takeUnless { it.row.gone }?.toItem()
        }
        val state = cache.listState(listKey)
        if (query is LibraryQuery.Playlist) return playlistSnapshot(header as LibraryItem.Playlist?, state)
        if (gone) {
            return LibraryPublication(
                query, 0, LibraryFreshness.Unavailable(LibraryUnavailableReason.Gone), null, null, null,
                emptyList(), LibraryItemsState.Unavailable, LibraryItemsOrder.Server,
            )
        }
        if (header == null && state == null) {
            val freshness = emptyFreshness()
            return LibraryPublication(
                query, 0, freshness, null, null, null, emptyList(),
                if (freshness == LibraryFreshness.Loading) LibraryItemsState.Loading else LibraryItemsState.Unavailable,
                LibraryItemsOrder.Server,
            )
        }
        touchShown(listKey)
        // A playlist keeps its duplicate entries (§18.6); an artist's albums are distinct anyway.
        val (items, _) = if (state != null) itemsOf(cache.listRows(listKey), dedupe = false) else emptyList<LibraryItem>() to emptyList()
        val itemsState = when {
            state != null -> LibraryItemsState.Present
            reader.online && failure == null && !internalFailure -> LibraryItemsState.Loading
            else -> LibraryItemsState.Unavailable
        }
        val live = liveThisSession && reader.sessionEpoch != null && state?.windowEpoch == reader.sessionEpoch?.key
        return LibraryPublication(
            query, 0, cachedFreshness(state?.fetchedAtWall, live), null, null, header, items, itemsState,
            LibraryItemsOrder.Server,
        )
    }

    /**
     * A playlist: the cached header and entries with pending edits overlaid (§18.6). A playlist the
     * server no longer has — told by this window's read, or by an edit's read of the same row — is
     * `gone`; so is one deleted on this device, before the server confirms it.
     */
    private fun playlistSnapshot(cachedHeader: LibraryItem.Playlist?, state: CachedListState?): LibraryPublication {
        val goneOnServer = gone || cache.playlist(rawId)?.row?.gone == true
        val entries = if (state != null) itemsOf(cache.listRows(listKey), dedupe = false).first else null
        val view = reader.playlistOverlay.overlayDetail(rawId, cachedHeader, entries)
        if (view.deletedLocally || (goneOnServer && view.header?.local != true)) {
            return LibraryPublication(
                query, 0, LibraryFreshness.Unavailable(LibraryUnavailableReason.Gone), null, null, null,
                emptyList(), LibraryItemsState.Unavailable, LibraryItemsOrder.Server,
            )
        }
        if (view.header == null && view.entries == null) {
            val freshness = emptyFreshness()
            return LibraryPublication(
                query, 0, freshness, null, null, null, emptyList(),
                if (freshness == LibraryFreshness.Loading) LibraryItemsState.Loading else LibraryItemsState.Unavailable,
                LibraryItemsOrder.Server,
            )
        }
        if (state != null) touchShown(listKey)
        val itemsState = when {
            view.entries != null -> LibraryItemsState.Present
            reader.online && failure == null && !internalFailure -> LibraryItemsState.Loading
            else -> LibraryItemsState.Unavailable
        }
        val live = liveThisSession && reader.sessionEpoch != null && state?.windowEpoch == reader.sessionEpoch?.key
        return LibraryPublication(
            query, 0, cachedFreshness(state?.fetchedAtWall, live), null, null, view.header, view.entries.orEmpty(), itemsState,
            LibraryItemsOrder.Server,
        )
    }

    override fun loadMore() = Unit

    override fun loadBefore() = Unit

    override fun setViewport(firstIndex: Int, lastIndex: Int) = Unit
}

/**
 * One `getPlaylist`, written through: the playlist and its ordered entries (duplicates kept, §18.6)
 * in one transaction. The playlist screen and playlist editing both read through this; the caller
 * holds the playlist's list lock (one writer per list). [slot]: sent on a slot the caller already
 * holds ([LibraryReader.withOneSlot]). [issued] learns the request's sequence before the envelope
 * is judged, so a not-found can be recorded with it.
 */
internal suspend fun LibraryReader.readPlaylistDetail(
    rawId: String,
    epoch: CatalogEpoch?,
    slot: LibraryReader.HeldSlot? = null,
    issued: (Long) -> Unit = {},
): PlaylistDetailRead {
    val parameters = mapOf("id" to rawId)
    val sent = slot?.send("getPlaylist", parameters) ?: send("getPlaylist", parameters)
    issued(sent.issueSeq)
    sent.requireOk("getPlaylist", parameters)
    val now = cache.now()
    val listKey = playlistDetailListKey(rawId)
    // Entries keep their duplicates (§18.6): positions are distinct, ids may repeat.
    val (playlist, entries) = parseReaderPlaylist(sent.response.body, rawId)
    cache.writeWholeList(
        CacheWriteStamp(sent.issueSeq, now, epoch?.key), CacheEntitySource.Detail,
        CachedListState(listKey, epoch?.key, epoch?.folderIds, 0, entries.size, null, CacheCoverage.Complete, now, now, sent.issueSeq),
        entries.map { CacheListMember(CacheItemKind.Track, it.rawId) },
        CacheEntities(playlists = listOf(playlist), tracks = entries.distinctBy { it.rawId }),
    )
    cache.markPlaylistDetail(sent.issueSeq, rawId)
    liveListReads[listKey] = cache.now()
    return PlaylistDetailRead(playlist, entries)
}

/** A playlist as one `getPlaylist` read it: its header and its entries in the server's order. */
internal class PlaylistDetailRead(val playlist: CachePlaylistRecord, val entries: List<CacheTrackRecord>) {
    val ids: List<String> get() = entries.map { it.rawId }
}

// ---- Item mapping -------------------------------------------------------------------------------------

internal fun CachedAlbum.toItem(pending: PendingUserState?): LibraryItem.Album {
    val artist = record.credits.firstOrNull()
    return LibraryItem.Album(
        rawId = record.rawId,
        title = record.title,
        artistName = artist?.name,
        artistRawId = artist?.artistRawId,
        year = record.year,
        genre = record.genre,
        durationMilliseconds = record.durationMilliseconds,
        songCount = record.songCount,
        artworkKey = record.artworkKey,
        starred = pending?.starred ?: record.userState.starred,
        userRating = pending?.userRating ?: record.userState.userRating,
        playCount = record.userState.playCount,
        detailComplete = detailComplete,
    )
}

internal fun CachedArtist.toItem(pending: PendingUserState?) = LibraryItem.Artist(
    rawId = record.rawId,
    name = record.name,
    albumCount = record.albumCount,
    artworkKey = record.artworkKey,
    starred = pending?.starred ?: record.userState.starred,
    userRating = pending?.userRating ?: record.userState.userRating,
)

internal fun CachedTrack.toItem(pending: PendingUserState?, playability: LibraryPlayability): LibraryItem.Track {
    val artist = record?.credits?.firstOrNull()
    return LibraryItem.Track(
        rawId = rawId,
        title = record?.title,
        albumRawId = record?.albumRawId,
        albumTitle = record?.albumTitle,
        artistName = artist?.name,
        artistRawId = artist?.artistRawId,
        discNumber = record?.discNumber,
        trackNumber = record?.trackNumber,
        durationMilliseconds = record?.durationMilliseconds,
        sourceContainer = record?.sourceContainer,
        artworkKey = record?.artworkKey,
        starred = pending?.starred ?: userState.starred,
        userRating = pending?.userRating ?: userState.userRating,
        playCount = record?.userState?.playCount,
        playability = playability,
        metadataMissing = metadataMissing,
    )
}

internal fun CachedPlaylist.toItem() = LibraryItem.Playlist(
    rawId = record.rawId,
    name = record.name,
    songCount = record.songCount,
    durationMilliseconds = record.durationMilliseconds,
    owner = record.owner,
    artworkKey = record.artworkKey,
    comment = record.comment,
    isPublic = record.isPublic,
    // Only the server's own `readonly: false`; a server that does not say is decided by the
    // playlist overlay, which knows the account (§18.6).
    editable = record.readonly == false,
)
