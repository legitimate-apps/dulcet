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
 * 2. **Every page read ends with a `getScanStatus`** (the *after*). A page joins a guarded window
 *    only when *after* shows no scan running and the window's stamp ([checkPage]).
 * 3. **A window is torn** by a fired check, by a stored epoch that differs from the current one at
 *    its first live read in this session, or by a changed folder set — and a torn window is never
 *    extended. It is **rebased**: only the pages intersecting the viewport are re-read, under the
 *    current epoch, and every other page is dropped, with the first visible item kept first.
 * 4. **While the server scans**, pages append unguarded and the window is `unverified(scanning)`;
 *    the first reading with no scan running rebases it.
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
     * One live operation per window at a time. An open's revalidation and a `loadMore` issued
     * before it lands would otherwise both see a stale window and rebase it twice.
     */
    private val lock = Mutex()

    /** In-flight live reads; while positive, cached content is `cached(revalidating)`. */
    protected var inFlight = 0

    /**
     * Set when an open decided to revalidate, BEFORE its first publication: the cached content it
     * paints is already `revalidating`, so no later frame has to correct it (§16.14).
     */
    protected var revalidationPending = false

    /** The last live read's failure, shown beside the cached content until a read succeeds. */
    protected var failure: DomainError? = null

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
        emit(snapshot())
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

    /** Revalidates under this window's lock; the reader's reconnect calls this. */
    suspend fun revalidate(cause: RevalidateCause) {
        lock.withLock { performRevalidate(cause) }
    }

    /** Called with the window's lock held. */
    protected abstract suspend fun performRevalidate(cause: RevalidateCause)

    /** Builds the publication from the cache as it stands now. */
    protected abstract fun snapshot(): LibraryPublication

    abstract fun mentionsAny(rawIds: Set<String>): Boolean

    fun republish() {
        if (!closed) emit(snapshot())
    }

    protected fun emit(publication: LibraryPublication) {
        if (closed) return
        sequence += 1
        published = publication.items
        listener(publication.copy(sequence = sequence))
    }

    protected fun launchRead(block: suspend () -> Unit) {
        if (closed) return
        val job = reader.scope.launch { lock.withLock { block() } }
        jobs += job
        job.invokeOnCompletion { jobs -= job }
    }

    /** Runs one live read, publishing `revalidating` while it is in flight. */
    protected suspend fun <T> live(block: suspend () -> T): T {
        // From here the in-flight count carries "revalidating"; the open's pending flag must not
        // outlive the read, or the final publication would still say revalidating.
        revalidationPending = false
        inFlight += 1
        try {
            return block()
        } finally {
            inFlight -= 1
        }
    }

    override fun refresh() {
        if (reader.online) launchRead { performRevalidate(RevalidateCause.Refresh) }
    }

    override fun close() {
        if (closed) return
        closed = true
        jobs.toList().forEach { it.cancel() }
        reader.lookAhead.windowClosed(this)
        reader.closed(this)
    }

    // ---- Shared presentation ------------------------------------------------------------------------

    /** Freshness of content that exists in the cache (§16.14). */
    protected fun cachedFreshness(asOfWall: Long?, liveUnderCurrentEpoch: Boolean): LibraryFreshness = when {
        !reader.online -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.Offline)
        inFlight > 0 || revalidationPending -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.Revalidating)
        failure != null -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.Failed(failure!!))
        liveUnderCurrentEpoch -> LibraryFreshness.Live
        else -> LibraryFreshness.Cached(asOfWall, LibraryCachedReason.Stale)
    }

    /** Freshness when nothing is cached. */
    protected fun emptyFreshness(): LibraryFreshness = when {
        !reader.online -> LibraryFreshness.Unavailable(LibraryUnavailableReason.NotCachedOffline)
        failure != null -> LibraryFreshness.Unavailable(LibraryUnavailableReason.Failed(failure!!))
        else -> LibraryFreshness.Loading
    }

    protected fun resolve(members: List<CacheListMember>): List<LibraryItem> {
        val pending = reader.overlay.pending(cache.serverId, members.mapTo(mutableSetOf()) { it.rawId })
        val downloaded = reader.downloads.downloadedTrackRawIds(cache.serverId)
        val seen = mutableSetOf<Pair<CacheItemKind, String>>()
        return members.mapNotNull { member ->
            // Deduplicated by opaque id within a window in any case (§16.12). A playlist keeps its
            // duplicates, and is resolved by the collection detail, not here.
            if (!seen.add(member.kind to member.rawId)) return@mapNotNull null
            resolveOne(member, pending, downloaded)
        }
    }

    protected fun resolveOne(
        member: CacheListMember,
        pending: Map<String, PendingUserState>,
        downloaded: Set<String>,
    ): LibraryItem? = when (member.kind) {
        CacheItemKind.Album -> cache.album(member.rawId)?.takeUnless { it.row.gone }?.toItem(pending[member.rawId])
        CacheItemKind.Artist -> cache.artist(member.rawId)?.takeUnless { it.row.gone }?.toItem(pending[member.rawId])
        CacheItemKind.Track -> cache.track(member.rawId)?.takeUnless { it.row.gone }
            ?.toItem(pending[member.rawId], reader.trackPlayability(member.rawId, downloaded))
        CacheItemKind.Playlist -> cache.playlist(member.rawId)?.takeUnless { it.row.gone }?.toItem()
        CacheItemKind.Genre -> LibraryItem.Genre(member.rawId)
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

/** A page read and its *after* reading. */
private data class PageRead(
    val offset: Int,
    val requested: Int,
    val parsed: ParsedList,
    val totalCount: Int?,
    val after: ScanStatusReading?,
    val issueSeq: Long,
)

/** One list screen: a paged window, or a single response. */
internal class ListWindow(
    reader: LibraryReader,
    query: LibraryQuery,
    private val spec: ListRequestSpec,
    listener: (LibraryPublication) -> Unit,
) : ReaderHandle(reader, query, listener) {
    private val pageSize = reader.config.pageSize

    /** The viewport, in server positions. */
    private var viewport: IntRange = 0..0

    /** Positions of the last publication's items, parallel to [published]. */
    private var publishedPositions: List<Int> = emptyList()

    /** Bumped by every rebase; a page read from an older generation is discarded on arrival. */
    private var generation = 0

    private var pendingAnchor: LibraryAnchor? = null

    /**
     * Whether this list was read live in this session — by this handle or an earlier one for the
     * same list (§16.12 tear rule 2 applies at the FIRST live read of the session).
     */
    private val liveThisSession: Boolean get() = spec.listKey in reader.liveListReads

    init {
        cache.listState(spec.listKey)?.let { viewport = it.firstLoadedOffset..it.firstLoadedOffset }
    }

    override fun needsRevalidation(): Boolean {
        val epoch = reader.sessionEpoch ?: return true
        val state = cache.listState(spec.listKey)
        if (state?.coverage == CacheCoverage.UnverifiedScanning && !epoch.scanning) return true
        return !readRecently(state, epoch)
    }

    override fun mentionsAny(rawIds: Set<String>): Boolean = published.any { it.rawId in rawIds }

    // ---- Revalidation -------------------------------------------------------------------------------

    override suspend fun performRevalidate(cause: RevalidateCause) {
        if (closed || !reader.online) return
        if (cause != RevalidateCause.Open && cache.listState(spec.listKey) != null) {
            inFlight += 1
            republish()
            inFlight -= 1
        }
        live {
            val epoch = reader.ensureEpoch()
            if (epoch == null) {
                failure = DomainError.Transport.Unreachable
                return@live
            }
            if (!spec.paged) {
                readWhole(epoch)
                return@live
            }
            val state = cache.listState(spec.listKey)
            val torn = state == null ||
                state.windowEpoch != epoch.key ||
                (state.coverage == CacheCoverage.UnverifiedScanning && !epoch.scanning)
            // The 60-second rule spares a fresh window a re-read; it never spares a torn one.
            if (!torn && cause != RevalidateCause.Refresh && readRecently(state, epoch)) return@live
            if (torn) rebase(epoch, attempt = 0) else revalidateViewport(epoch, state!!)
        }
        emit(snapshot())
    }

    private fun readRecently(state: CachedListState?, epoch: CatalogEpoch): Boolean {
        val readAt = reader.liveListReads[spec.listKey] ?: return false
        return state != null && state.windowEpoch == epoch.key &&
            cache.now() - readAt < reader.config.revalidateWithinMillis
    }

    private suspend fun readWhole(epoch: CatalogEpoch) {
        val seq = cache.issue()
        val parameters = spec.parameters + listOfNotNull(spec.singlePageSize?.let { "size" to it.toString() })
        val response = try {
            reader.checked(spec.endpoint, parameters)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            this.failure = failure.asReaderError()
            return
        }
        val parsed = try {
            spec.parse(response.body)
        } catch (failure: LibraryRequestFailure) {
            this.failure = failure.error
            return
        }
        val now = cache.now()
        cache.writeWholeList(
            CacheWriteStamp(seq, now, epoch.key),
            CacheEntitySource.ListPage,
            CachedListState(spec.listKey, epoch.key, epoch.folderIds, 0, parsed.members.size, response.totalCount, CacheCoverage.Complete, now, now, seq),
            parsed.members,
            parsed.entities,
        )
        markLive()
        cache.evictIfNeeded()
    }

    private suspend fun revalidateViewport(epoch: CatalogEpoch, state: CachedListState) {
        val offsets = viewportPageOffsets(state)
        for (offset in offsets) {
            val gen = generation
            val read = readPage(offset) ?: return
            if (gen != generation || closed) return
            when (checkPage(epoch.stamp, read.after)) {
                PageCheck.Fired -> {
                    rebase(reader.adoptScanStatus(read.after!!), attempt = 1)
                    return
                }
                PageCheck.Guarded -> writePage(read, CacheCoverage.Open, epoch, replacing = true)
                PageCheck.Scanning -> writePage(read, CacheCoverage.UnverifiedScanning, epoch, replacing = true)
                PageCheck.NoEpoch -> writePage(read, CacheCoverage.UnverifiedNoEpoch, epoch, replacing = true)
            }
        }
        markLive()
    }

    /**
     * Rebase (§16.12): re-read only the pages intersecting the viewport under [epoch], and drop
     * every other page, in one transaction per rebase. The first visible item stays first.
     */
    private suspend fun rebase(epoch: CatalogEpoch, attempt: Int) {
        generation += 1
        val gen = generation
        val anchorBefore = firstVisibleAnchorSource()
        val state = cache.listState(spec.listKey)
        val offsets = viewportPageOffsets(state)
        val reads = coroutineScope { offsets.map { offset -> async { readPage(offset) } }.awaitAll() }
        if (gen != generation || closed) return
        if (reads.any { it == null }) return
        val pages = reads.filterNotNull().sortedBy { it.offset }
        val checks = pages.map { checkPage(epoch.stamp, it.after) }
        if (PageCheck.Fired in checks) {
            val moved = pages[checks.indexOf(PageCheck.Fired)].after!!
            val next = reader.adoptScanStatus(moved)
            if (attempt < reader.config.maxTearRetries) {
                rebase(next, attempt + 1)
                return
            }
        }
        val coverage = when {
            PageCheck.Scanning in checks || PageCheck.Fired in checks -> CacheCoverage.UnverifiedScanning
            PageCheck.NoEpoch in checks || epoch.key == null -> CacheCoverage.UnverifiedNoEpoch
            else -> CacheCoverage.Open
        }
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
        val complete = first == 0 && (lastRows == 0 || (total != null && end >= total))
        val now = cache.now()
        val newState = CachedListState(
            spec.listKey, epoch.key, epoch.folderIds, first, end, total,
            if (complete && coverage == CacheCoverage.Open) CacheCoverage.Complete else coverage,
            now, now, pages.maxOf { it.issueSeq },
        )
        cache.writeWindowPage(
            stamp = CacheWriteStamp(pages.maxOf { it.issueSeq }, now, epoch.key),
            source = CacheEntitySource.ListPage,
            state = newState,
            pageStart = first,
            replacedEnd = end,
            members = members,
            entities = entities.merge(),
            keepRange = first until maxOf(first + members.size, first + 1),
        )
        cache.evictIfNeeded()
        markLive()
        pendingAnchor = anchorAfterRebase(anchorBefore)
    }

    private suspend fun readPage(offset: Int): PageRead? {
        val seq = cache.issue()
        val parameters = spec.parameters + (spec.sizeParameter!! to pageSize.toString()) + ("offset" to offset.toString())
        val response = try {
            reader.checked(spec.endpoint, parameters)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            this.failure = failure.asReaderError()
            return null
        }
        val parsed = try {
            spec.parse(response.body)
        } catch (failure: LibraryRequestFailure) {
            this.failure = failure.error
            return null
        }
        // The *after* reading is issued only once the page's response has arrived (§16.12).
        val after = try {
            reader.epochReader.readScanStatus()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            null
        }
        return PageRead(offset, pageSize, parsed, response.totalCount, after, seq)
    }

    /** Appends or replaces one page in an intact window, deduplicating against the rest of it. */
    private fun writePage(read: PageRead, coverage: CacheCoverage, epoch: CatalogEpoch, replacing: Boolean) {
        val state = cache.listState(spec.listKey)
        val pageEnd = read.offset + read.parsed.members.size
        val elsewhere = cache.listMembers(spec.listKey)
            .filter { it.position < read.offset || it.position >= maxOf(pageEnd, read.offset + read.requested) }
            .mapTo(mutableSetOf()) { it.member.kind to it.member.rawId }
        val members = read.parsed.members.filter { elsewhere.add(it.kind to it.rawId) }
        val previousRows = cache.listMembers(spec.listKey)
            .count { it.position >= read.offset && it.position < read.offset + read.requested }
        val first = minOf(state?.firstLoadedOffset ?: read.offset, read.offset)
        val end = if (replacing) maxOf(state?.endLoadedOffset ?: pageEnd, pageEnd) else pageEnd
        val total = read.totalCount ?: state?.total
        val complete = first == 0 && (
            (read.parsed.members.isEmpty() && !replacing) ||
                (total != null && end >= total) ||
                // Re-reading a page of a complete window keeps it complete only if the page kept its size.
                (replacing && state?.coverage == CacheCoverage.Complete && members.size == previousRows)
            )
        val mergedCoverage = when {
            coverage != CacheCoverage.Open -> coverage
            state?.coverage == CacheCoverage.UnverifiedScanning || state?.coverage == CacheCoverage.UnverifiedNoEpoch -> state.coverage
            complete -> CacheCoverage.Complete
            else -> CacheCoverage.Open
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
            replacedEnd = read.offset + read.requested,
            members = members,
            entities = read.parsed.entities,
        )
        cache.evictIfNeeded()
    }

    // ---- Paging -------------------------------------------------------------------------------------

    override fun loadMore() {
        if (closed || !reader.online || !spec.paged) return
        launchRead { appendNext(confirming = false) }
    }

    private suspend fun appendNext(confirming: Boolean) {
        val state = cache.listState(spec.listKey) ?: return
        if (state.coverage == CacheCoverage.Complete) return
        // Never more than one page beyond the viewport (§16.12).
        if (!confirming && state.endLoadedOffset > viewport.last + pageSize) return
        val epoch = reader.ensureEpoch() ?: return
        // Tear rule 2: a window not yet read live this session is revalidated, not extended.
        if (!liveThisSession) {
            performRevalidate(RevalidateCause.Open)
            return
        }
        live {
            val gen = generation
            val read = readPage(state.endLoadedOffset) ?: return@live
            if (gen != generation || closed) return@live
            val windowStamp = windowStamp(state)
            val check = checkPage(windowStamp, read.after)
            val scanEnded = state.coverage == CacheCoverage.UnverifiedScanning && check != PageCheck.Scanning
            when {
                check == PageCheck.Fired || scanEnded -> {
                    rebase(reader.adoptScanStatus(read.after!!), attempt = 1)
                }
                else -> {
                    val coverage = when (check) {
                        PageCheck.Scanning -> CacheCoverage.UnverifiedScanning
                        PageCheck.NoEpoch -> CacheCoverage.UnverifiedNoEpoch
                        else -> CacheCoverage.Open
                    }
                    writePage(read, coverage, epoch, replacing = false)
                    reader.liveListReads[spec.listKey] = cache.now()
                    val rows = read.parsed.members.size
                    val after = cache.listState(spec.listKey)
                    // A short page is only a CANDIDATE end: with no total it is confirmed by one
                    // more request, and only an empty page (or the total) completes the window.
                    if (!confirming && rows in 1 until read.requested && after?.total == null &&
                        after?.coverage != CacheCoverage.Complete
                    ) {
                        appendNext(confirming = true)
                    }
                }
            }
        }
        emit(snapshot())
    }

    private fun windowStamp(state: CachedListState): String? {
        val key = state.windowEpoch ?: return null
        return stampOfEpochKey(key)
    }

    override fun setViewport(firstIndex: Int, lastIndex: Int) {
        if (closed || publishedPositions.isEmpty()) return
        val first = publishedPositions[firstIndex.coerceIn(0, publishedPositions.lastIndex)]
        val last = publishedPositions[lastIndex.coerceIn(firstIndex.coerceAtLeast(0), publishedPositions.lastIndex)]
        viewport = first..last
        if (query is LibraryQuery.AlbumList) {
            val from = firstIndex.coerceIn(0, published.lastIndex)
            val to = lastIndex.coerceIn(from, published.lastIndex)
            reader.lookAhead.viewportChanged(this, published, from, to)
        }
    }

    // ---- Presentation -------------------------------------------------------------------------------

    private fun markLive() {
        failure = null
        reader.liveListReads[spec.listKey] = cache.now()
    }

    override fun snapshot(): LibraryPublication {
        val state = cache.listState(spec.listKey)
        if (state != null) cache.touchList(spec.listKey)
        val positions = state?.let { cache.listMembers(spec.listKey) }.orEmpty()
        if (state == null || (positions.isEmpty() && !liveThisSession && state.coverage != CacheCoverage.Complete)) {
            return localViewOrEmpty()
        }
        val items = mutableListOf<LibraryItem>()
        val itemPositions = mutableListOf<Int>()
        val pending = reader.overlay.pending(cache.serverId, positions.mapTo(mutableSetOf()) { it.member.rawId })
        val downloaded = reader.downloads.downloadedTrackRawIds(cache.serverId)
        val seen = mutableSetOf<Pair<CacheItemKind, String>>()
        positions.forEach { position ->
            if (!seen.add(position.member.kind to position.member.rawId)) return@forEach
            resolveOne(position.member, pending, downloaded)?.let {
                items += it
                itemPositions += position.position
            }
        }
        publishedPositions = itemPositions
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
        )
    }

    /**
     * A list never read: offline, the local view of the cached entities of its kind, sorted
     * locally and labelled as such (§16.14); otherwise nothing, loading or unavailable.
     */
    private fun localViewOrEmpty(): LibraryPublication {
        val local: List<LibraryItem> = if (!reader.online) {
            val pending = reader.overlay.pending(cache.serverId, emptySet())
            when (query) {
                is LibraryQuery.AlbumList -> cache.localAlbums().map { it.toItem(pending[it.record.rawId]) }
                is LibraryQuery.Artists -> cache.localArtists().map { it.toItem(pending[it.record.rawId]) }
                else -> emptyList()
            }
        } else {
            emptyList()
        }
        publishedPositions = local.indices.toList()
        val freshness = if (local.isNotEmpty()) {
            LibraryFreshness.Cached(null, LibraryCachedReason.Offline)
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
    private fun viewportPageOffsets(state: CachedListState?): List<Int> {
        val first = (viewport.first / pageSize) * pageSize
        val last = (viewport.last / pageSize) * pageSize
        val offsets = (first..last step pageSize).toList()
        return if (state == null && offsets.isEmpty()) listOf(0) else offsets.ifEmpty { listOf(0) }
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

    override fun needsRevalidation(): Boolean {
        val cached = cache.album(album.rawId) ?: return true
        return !(cached.detailComplete && reader.detailIsFresh(album.rawId, cached.row.fetchedEpoch))
    }

    override fun mentionsAny(rawIds: Set<String>): Boolean =
        album.rawId in rawIds || published.any { it.rawId in rawIds }

    override suspend fun performRevalidate(cause: RevalidateCause) {
        if (closed || !reader.online) return
        val cached = cache.album(album.rawId)
        // A detail read live under the current epoch within the interval is not re-read — which is
        // what lets a looked-ahead album open with zero requests (CONF-87).
        if (cause != RevalidateCause.Refresh && cached != null && cached.detailComplete &&
            reader.detailIsFresh(album.rawId, cached.row.fetchedEpoch)
        ) {
            revalidationPending = false
            emit(snapshot())
            return
        }
        reader.lookAhead.cancel(album.rawId)
        if (cached != null && cause != RevalidateCause.Open) {
            inFlight += 1
            republish()
            inFlight -= 1
        }
        val result = live {
            reader.ensureEpoch()
            reader.readAlbumDetail(album.rawId)
        }
        when (result) {
            DetailReadResult.Read -> { failure = null; gone = false }
            DetailReadResult.Gone -> { failure = null; gone = true }
            is DetailReadResult.Failed -> failure = result.error
        }
        cache.evictIfNeeded()
        emit(snapshot())
    }

    override fun snapshot(): LibraryPublication {
        val cached = cache.album(album.rawId)
        if (cached != null) cache.touchAlbum(album.rawId)
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
        val pending = reader.overlay.pending(cache.serverId, setOf(album.rawId))
        val header = cached.toItem(pending[album.rawId])
        val itemsState: LibraryItemsState
        val items: List<LibraryItem>
        if (cached.detailComplete) {
            val tracks = cache.albumTracks(album.rawId)
            items = resolve(tracks.map { CacheListMember(CacheItemKind.Track, it.rawId) })
            itemsState = LibraryItemsState.Present
        } else {
            items = emptyList()
            itemsState = if (reader.online && failure == null) LibraryItemsState.Loading else LibraryItemsState.Unavailable
        }
        val live = reader.liveDetailReads.containsKey(album.rawId) &&
            cached.row.fetchedEpoch == reader.sessionEpoch?.key && reader.sessionEpoch != null
        return LibraryPublication(
            query, 0, cachedFreshness(cached.row.fetchedAtWall, live), null, null, header, items, itemsState,
            LibraryItemsOrder.Server,
        )
    }

    override fun loadMore() = Unit

    override fun setViewport(firstIndex: Int, lastIndex: Int) = Unit
}

/** An artist (its albums) or a playlist (its entries, duplicates kept). One response each. */
internal class CollectionDetailWindow(
    reader: LibraryReader,
    query: LibraryQuery,
    listener: (LibraryPublication) -> Unit,
) : ReaderHandle(reader, query, listener) {
    private val rawId = when (query) {
        is LibraryQuery.Artist -> query.rawId
        is LibraryQuery.Playlist -> query.rawId
        else -> error("not a collection detail")
    }
    private val listKey = when (query) {
        is LibraryQuery.Artist -> canonicalListKey("getArtist", mapOf("id" to rawId))
        else -> playlistDetailListKey(rawId)
    }
    private var gone = false
    private val liveThisSession: Boolean get() = listKey in reader.liveListReads

    override fun needsRevalidation(): Boolean {
        val readAt = reader.liveListReads[listKey] ?: return true
        val state = cache.listState(listKey) ?: return true
        return state.windowEpoch != reader.sessionEpoch?.key || cache.now() - readAt >= reader.config.revalidateWithinMillis
    }

    override fun mentionsAny(rawIds: Set<String>): Boolean = rawId in rawIds || published.any { it.rawId in rawIds }

    override suspend fun performRevalidate(cause: RevalidateCause) {
        if (closed || !reader.online) return
        if (cache.listState(listKey) != null && cause != RevalidateCause.Open) {
            inFlight += 1
            republish()
            inFlight -= 1
        }
        live {
            val epoch = reader.ensureEpoch()
            val seq = cache.issue()
            val now = cache.now()
            try {
                if (query is LibraryQuery.Artist) {
                    val response = reader.checked("getArtist", mapOf("id" to rawId))
                    val (artist, albums) = parseReaderArtist(response.body, rawId)
                    cache.writeWholeList(
                        CacheWriteStamp(seq, now, epoch?.key), CacheEntitySource.Detail,
                        CachedListState(listKey, epoch?.key, epoch?.folderIds, 0, albums.size, null, CacheCoverage.Complete, now, now, seq),
                        albums.map { CacheListMember(CacheItemKind.Album, it.rawId) },
                        CacheEntities(artists = listOf(artist), albums = albums),
                    )
                } else {
                    val response = reader.checked("getPlaylist", mapOf("id" to rawId))
                    // Entries keep their duplicates (§18.6): positions are distinct, ids may repeat.
                    val (playlist, entries) = parseReaderPlaylist(response.body, rawId)
                    cache.writeWholeList(
                        CacheWriteStamp(seq, now, epoch?.key), CacheEntitySource.Detail,
                        CachedListState(listKey, epoch?.key, epoch?.folderIds, 0, entries.size, null, CacheCoverage.Complete, now, now, seq),
                        entries.map { CacheListMember(CacheItemKind.Track, it.rawId) },
                        CacheEntities(playlists = listOf(playlist), tracks = entries.distinctBy { it.rawId }),
                    )
                    cache.markPlaylistDetail(seq, rawId)
                }
                failure = null
                gone = false
                reader.liveListReads[listKey] = now
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (thrown: Throwable) {
                val error = thrown.asReaderError()
                if (error.isNotFound()) {
                    gone = true
                    failure = null
                    if (query is LibraryQuery.Artist) cache.markArtistNotFound(rawId) else cache.markPlaylistNotFound(seq, rawId)
                } else {
                    failure = error
                }
            }
        }
        cache.evictIfNeeded()
        emit(snapshot())
    }

    override fun snapshot(): LibraryPublication {
        val header: LibraryItem? = when (query) {
            is LibraryQuery.Artist -> cache.artist(rawId)?.takeUnless { it.row.gone }?.toItem(null)
            else -> cache.playlist(rawId)?.takeUnless { it.row.gone }?.toItem()
        }
        val state = cache.listState(listKey)
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
        cache.touchList(listKey)
        val members = cache.listMembers(listKey).map { it.member }
        val pending = reader.overlay.pending(cache.serverId, members.mapTo(mutableSetOf()) { it.rawId })
        val downloaded = reader.downloads.downloadedTrackRawIds(cache.serverId)
        // A playlist keeps its duplicate entries (§18.6); an artist's albums are distinct anyway.
        val items = members.mapNotNull { resolveOne(it, pending, downloaded) }
        val itemsState = when {
            state != null -> LibraryItemsState.Present
            reader.online && failure == null -> LibraryItemsState.Loading
            else -> LibraryItemsState.Unavailable
        }
        val live = liveThisSession && reader.sessionEpoch != null && state?.windowEpoch == reader.sessionEpoch?.key
        return LibraryPublication(
            query, 0, cachedFreshness(state?.fetchedAtWall, live), null, null, header, items, itemsState,
            LibraryItemsOrder.Server,
        )
    }

    override fun loadMore() = Unit

    override fun setViewport(firstIndex: Int, lastIndex: Int) = Unit
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
        starred = pending?.starred ?: record?.userState?.starred,
        userRating = pending?.userRating ?: record?.userState?.userRating,
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
)
