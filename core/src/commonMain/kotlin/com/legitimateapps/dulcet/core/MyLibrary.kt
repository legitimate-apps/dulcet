package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * My Library on a server that advertises the OpenSubsonic extension `dulcetLibrary` v1 (spec §29.9,
 * wire contract `docs/dulcet-server/dulcet-library-extension.md`): what THIS account has added,
 * apart from everything the server holds (Browse).
 *
 * The parts, each replaceable on its own:
 *
 * - [MyLibraryTransport] — every wire call: detection, paging, add and remove. [OpenSubsonicMyLibraryTransport]
 *   is the only implementation; another protocol slots in here without touching the rest.
 * - [MyLibraryStore] — the read model on the device (`my_library_state`, `my_library_item`), one
 *   namespace per account, written by full reads, by additions, by the `dulcetInLibrary` flag of
 *   every live response and by acknowledged writes, each ordered by the seen-cache issue sequence.
 * - The favourites outbox ([MutationField.InLibrary]) — adds and removes, shown at once, durable,
 *   compacted, delivered at least once (both are set-to-value) and rolled back on a permanent failure.
 * - [MyLibrary] — detection, refresh, membership and the presentation snapshot the shells draw.
 *
 * **The reader traps hold here too (§16.1, TRAPS 18–19).** There is no change token: `changed` is a
 * clock compared for equality only. Offset paging is not a snapshot: a full read is accepted only
 * when the clock and the totals are the same on its first and last page. `lastScan` never covers
 * user state, and nothing here reads it.
 */
internal const val DULCET_LIBRARY_EXTENSION = "dulcetLibrary"
internal const val DULCET_IN_LIBRARY_ATTRIBUTE = "dulcetInLibrary"
private val DULCET_LIBRARY_IMPLEMENTED_VERSIONS = setOf(1)

/**
 * The `dulcetLibrary` version this client speaks with the server whose capabilities these are, or
 * null when the server does not advertise one this client implements (or speaks only legacy
 * Subsonic). Null means: no My Library, and the apps behave exactly as without it.
 */
public fun CapabilitySet.dulcetLibraryVersion(): Int? =
    if (legacySubsonic) null else extensions[DULCET_LIBRARY_EXTENSION].orEmpty().filter { it in DULCET_LIBRARY_IMPLEMENTED_VERSIONS }.maxOrNull()

/** Whether the server advertises a `dulcetLibrary` version this client implements. */
public fun CapabilitySet.supportsMyLibrary(): Boolean = dulcetLibraryVersion() != null

internal fun dulcetLibraryVersionIn(versions: Set<Int>): Int? = versions.filter { it in DULCET_LIBRARY_IMPLEMENTED_VERSIONS }.maxOrNull()

/** Whether this account's server offers My Library, as last detected. Persisted per account. */
internal enum class MyLibraryAvailability { Unknown, Available, Unavailable }

/** An item's place in My Library, which drives its Add and Remove buttons. */
internal enum class MyLibraryMembership {
    /** The item itself is a member — a pending add included. */
    Added,

    /** An album that is not itself a member, some of whose songs are. */
    Partial,

    NotAdded,

    /** Nothing read yet says either way: no library read has completed and no flag was seen. */
    Unknown,
}

internal enum class MyLibrarySort { RecentlyAdded, Alphabetical }

// ---- Transport ------------------------------------------------------------------------------------

/** One page of the library as the transport read it, with the issue sequence it was sent under. */
internal data class MyLibraryPage(
    val issueSeq: Long,
    val libraryClock: String?,
    val artistCount: Int,
    val albumCount: Int,
    val songCount: Int,
    val entities: CacheEntities,
) {
    val witness: MyLibraryWitness get() = MyLibraryWitness(libraryClock, artistCount, albumCount, songCount)
}

/** What must be the same on a read's first and last page for the read to be one state. */
internal data class MyLibraryWitness(val libraryClock: String?, val artistCount: Int, val albumCount: Int, val songCount: Int)

/**
 * Every wire call My Library makes, behind one seam. Each throws [LibraryRequestFailure] (or its
 * [ReaderSendRefused]) with a [DomainError], exactly as the reader's own requests do, so the outbox
 * classifies a membership send like any other.
 */
internal interface MyLibraryTransport {
    /** The `dulcetLibrary` versions the server advertises; empty when it advertises none. */
    suspend fun advertisedVersions(): Set<Int>

    /** One page of every kind at [offset] (per kind), newest addition first; [sinceEpochMillis] limits it to additions after then. */
    suspend fun page(offset: Int, size: Int, sinceEpochMillis: Long?): MyLibraryPage

    /** Adds [target] to, or removes it from, this account's library. Idempotent on the server. */
    suspend fun setMembership(target: LibraryEntityRef, member: Boolean)
}

/** [MyLibraryTransport] over OpenSubsonic `/rest`, through the reader's own slots, issue order and offline rule. */
internal class OpenSubsonicMyLibraryTransport(private val reader: LibraryReader) : MyLibraryTransport {
    override suspend fun advertisedVersions(): Set<Int> {
        val sent = try {
            reader.sendChecked("getOpenSubsonicExtensions")
        } catch (failure: LibraryRequestFailure) {
            // A server that answers the call with an error envelope has no extension list: a
            // Subsonic server without OpenSubsonic. Anything else (no answer) is not an answer.
            if (failure !is ReaderSendRefused && failure.error is DomainError.Server.Known) return emptySet()
            if (failure !is ReaderSendRefused && failure.error is DomainError.Server.Unknown) return emptySet()
            throw failure
        }
        return parseOpenSubsonicExtensionVersions(sent.response.body, DULCET_LIBRARY_EXTENSION)
    }

    override suspend fun page(offset: Int, size: Int, sinceEpochMillis: Long?): MyLibraryPage {
        val parameters = buildMap {
            put("size", size.toString())
            put("offset", offset.toString())
            sinceEpochMillis?.let { put("since", it.toString()) }
        }
        val sent = reader.sendChecked("getDulcetLibrary", parameters)
        val body = parseReaderDulcetLibrary(sent.response.body)
        return MyLibraryPage(sent.issueSeq, body.libraryClock, body.artistCount, body.albumCount, body.songCount, body.entities)
    }

    override suspend fun setMembership(target: LibraryEntityRef, member: Boolean) {
        val parameter = when (target.kind) {
            LibraryEntityKind.Track -> "id"
            LibraryEntityKind.Album -> "albumId"
            LibraryEntityKind.Artist -> "artistId"
        }
        reader.sendChecked(if (member) "addToDulcetLibrary" else "removeFromDulcetLibrary", mapOf(parameter to target.rawId))
    }
}

/** The versions [name] is advertised at in a `getOpenSubsonicExtensions` answer; empty when absent. */
internal fun parseOpenSubsonicExtensionVersions(body: String, name: String): Set<Int> {
    val payload = parseLibraryEnvelope(body)?.payload ?: throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
    val list = payload["openSubsonicExtensions"] as? kotlinx.serialization.json.JsonArray ?: return emptySet()
    return list.asSequence()
        .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
        .filter { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content == name }
        .flatMap { extension ->
            (extension["versions"] as? kotlinx.serialization.json.JsonArray).orEmpty().asSequence()
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { p -> p.isString }?.content?.toIntOrNull() }
        }
        .toSet()
}

// ---- Store ----------------------------------------------------------------------------------------

internal data class MyLibraryState(
    val advertised: Boolean?,
    val libraryClock: String?,
    val complete: Boolean,
    val readIssueSeq: Long,
    val readAtWall: Long?,
)

internal data class MyLibraryMember(val kind: CacheItemKind, val rawId: String, val rank: Long)

/**
 * The read model of one account's library. Every write is ordered by issue sequence, so a slow read
 * never overwrites what a newer read or acknowledgement wrote; rows saying "not a member" are kept
 * only until a full read issued after them, so they cannot be undone by an older read.
 */
internal class MyLibraryStore(private val database: DulcetDatabase, private val cache: BoundSeenCache) {
    private val queries get() = database.myLibraryQueries
    private val serverId: String get() = cache.serverId

    fun state(): MyLibraryState? = queries.selectState(serverId).executeAsOneOrNull()?.let {
        MyLibraryState(it.advertised?.let { flag -> flag == 1L }, it.library_clock, it.complete == 1L, it.read_issue_seq, it.read_at_wall)
    }

    fun setAdvertised(advertised: Boolean) = database.transaction {
        queries.ensureState(serverId)
        queries.updateAdvertised(if (advertised) 1L else 0L, serverId)
    }

    /** The server's membership of [target] as last read, and the issue of that read; null when unknown. */
    fun serverState(target: LibraryEntityRef): Pair<Int?, Long>? {
        val row = queries.selectItem(serverId, target.kind.cacheKind.wireName, target.rawId).executeAsOneOrNull()
        if (row != null) return row.member.toInt() to row.issue_seq
        val state = state() ?: return null
        return if (state.complete) 0 to state.readIssueSeq else null
    }

    /** Membership as the device knows it, without pending changes; null when unknown. */
    fun isMember(kind: CacheItemKind, rawId: String): Boolean? {
        val row = queries.selectItem(serverId, kind.wireName, rawId).executeAsOneOrNull()
        if (row != null) return row.member == 1L
        return if (state()?.complete == true) false else null
    }

    fun members(): List<MyLibraryMember> = queries.selectMembers(serverId).executeAsList().map {
        MyLibraryMember(CacheItemKind.fromWireName(it.item_kind), it.raw_id, it.added_rank)
    }

    /** Adopt-on-echo for a membership change the server acknowledged (§16.20 rule 5). */
    fun adopt(target: LibraryEntityRef, member: Boolean, issueSeq: Long) = database.transaction {
        write(target.kind.cacheKind, target.rawId, member, issueSeq, insertIfAbsent = true)
    }

    /** The `dulcetInLibrary` flag of a live response. A "not a member" flag only updates a row the device has. */
    fun noteFlag(kind: CacheItemKind, rawId: String, member: Boolean, issueSeq: Long) = database.transaction {
        write(kind, rawId, member, issueSeq, insertIfAbsent = member)
    }

    private fun write(kind: CacheItemKind, rawId: String, member: Boolean, issueSeq: Long, insertIfAbsent: Boolean) {
        queries.ensureState(serverId)
        val row = queries.selectItem(serverId, kind.wireName, rawId).executeAsOneOrNull()
        if (row == null && !insertIfAbsent) return
        if (row != null && row.issue_seq >= issueSeq) return
        val rank = when {
            !member -> row?.added_rank ?: 0L
            row != null && row.member == 1L -> row.added_rank
            else -> takeRanks(1)
        }
        queries.upsertItem(serverId, kind.wireName, rawId, if (member) 1L else 0L, rank, issueSeq)
    }

    private fun takeRanks(count: Int): Long {
        val next = queries.selectState(serverId).executeAsOne().next_rank
        queries.updateNextRank(next + count, serverId)
        return next
    }

    /**
     * A full read: [listed] holds every member, per kind newest first, each with the issue of the
     * page that listed it; [firstIssueSeq] is the first page's. Members not listed, written by
     * anything issued before the read, are no longer members.
     */
    fun applyFull(listed: Map<CacheItemKind, List<Pair<String, Long>>>, firstIssueSeq: Long, libraryClock: String?, wall: Long) =
        database.transaction {
            queries.ensureState(serverId)
            writeListed(listed)
            val keep = listed.flatMap { (kind, items) -> items.map { kind.wireName to it.first } }.toSet()
            queries.selectMembersIssuedBefore(serverId, firstIssueSeq).executeAsList()
                .filter { (it.item_kind to it.raw_id) !in keep }
                .forEach { queries.demoteItem(firstIssueSeq, serverId, it.item_kind, it.raw_id) }
            queries.deleteNonMembers(serverId, firstIssueSeq)
            queries.updateRead(libraryClock, 1L, firstIssueSeq, wall, serverId)
        }

    /** An additions-only read the totals proved complete: [listed] are added; nothing is demoted. */
    fun applyAdditions(listed: Map<CacheItemKind, List<Pair<String, Long>>>, firstIssueSeq: Long, libraryClock: String?, wall: Long) =
        database.transaction {
            queries.ensureState(serverId)
            writeListed(listed)
            queries.updateRead(libraryClock, 1L, maxOf(firstIssueSeq, state()?.readIssueSeq ?: 0L), wall, serverId)
        }

    /**
     * Writes listed members, ranked by the server's newest-first order above every rank given so
     * far. A listing ranks a row its own page wrote (the flag of the same response) but never one a
     * later read or acknowledgement wrote.
     *
     * Each list is ordered within its kind only, and the wire carries no added time, so ranks are
     * aligned from the newest of each kind: the newest artist, album and song of one read share a
     * rank. Order across kinds — an album shown for its songs among albums added themselves — is
     * exact only for additions this device made or read in a later refresh (spec §29.9).
     */
    private fun writeListed(listed: Map<CacheItemKind, List<Pair<String, Long>>>) {
        val longest = listed.values.maxOfOrNull { it.size } ?: 0
        if (longest == 0) return
        val base = takeRanks(longest + 1)
        listed.forEach { (kind, items) ->
            items.forEachIndexed { index, (rawId, issueSeq) ->
                val row = queries.selectItem(serverId, kind.wireName, rawId).executeAsOneOrNull()
                if (row != null && row.issue_seq > issueSeq) return@forEachIndexed
                val rank = if (row != null && row.member == 1L && row.issue_seq < issueSeq && row.added_rank >= base) row.added_rank
                    else base + (longest - index)
                queries.upsertItem(serverId, kind.wireName, rawId, 1L, rank, maxOf(issueSeq, row?.issue_seq ?: 0L))
            }
        }
    }

    /** Every member id of [kind], without pending changes. */
    fun memberIds(kind: CacheItemKind): Set<String> = queries.selectMemberIds(serverId, kind.wireName).executeAsList().toSet()
}

// ---- Presentation ---------------------------------------------------------------------------------

internal data class MyLibraryAlbum(
    val record: CacheAlbumRecord,
    val explicitlyAdded: Boolean,
    val memberSongCount: Int,
    val pending: Boolean,
    val rank: Long,
)

internal data class MyLibraryArtist(
    val record: CacheArtistRecord,
    val explicitlyAdded: Boolean,
    val pending: Boolean,
    val rank: Long,
)

internal data class MyLibrarySong(val record: CacheTrackRecord, val pending: Boolean, val rank: Long)

/** Where the device's copy of the library stands. */
internal enum class MyLibrarySyncState { Never, Syncing, Current, Cached, Failed }

internal data class MyLibrarySnapshot(
    val availability: MyLibraryAvailability,
    val syncState: MyLibrarySyncState,
    val lastError: DomainError?,
    val lastReadAtWall: Long?,
    val albums: List<MyLibraryAlbum>,
    val artists: List<MyLibraryArtist>,
    val songs: List<MyLibrarySong>,
    /** Effective explicit members (pending changes applied), by kind. */
    val memberIds: Map<CacheItemKind, Set<String>>,
    /** Albums with at least one effective member song. */
    val albumsWithMemberSongs: Set<String>,
    /** True when the device has a complete read, so absence means "not a member". */
    val complete: Boolean,
) {
    fun membership(kind: CacheItemKind, rawId: String): MyLibraryMembership = when {
        availability != MyLibraryAvailability.Available -> MyLibraryMembership.Unknown
        rawId in memberIds[kind].orEmpty() -> MyLibraryMembership.Added
        kind == CacheItemKind.Album && rawId in albumsWithMemberSongs -> MyLibraryMembership.Partial
        complete -> MyLibraryMembership.NotAdded
        else -> MyLibraryMembership.Unknown
    }
}

internal sealed interface MyLibraryRefreshOutcome {
    /** The server does not advertise `dulcetLibrary`: nothing was read and My Library is not offered. */
    data object Unavailable : MyLibraryRefreshOutcome

    /** The library clock is the one the device holds: nothing changed. */
    data object Unchanged : MyLibraryRefreshOutcome

    /** Only additions; the totals proved nothing was removed. */
    data class Incremental(val added: Int) : MyLibraryRefreshOutcome

    /** Every member was read again. [stable] is false when the library kept changing across every attempt. */
    data class Full(val members: Int, val stable: Boolean) : MyLibraryRefreshOutcome

    data class Failed(val error: DomainError) : MyLibraryRefreshOutcome
}

/**
 * My Library for one reader session. Confined to the reader's thread like everything the session
 * holds; listeners are called there, and nothing thrown leaves an entry point except a refresh's
 * failure of the device's own database, which [refresh] reports as [MyLibraryRefreshOutcome.Failed]
 * with no server error — never a Kotlin exception to the boundary (TRAPS 17).
 */
internal class MyLibrary(
    private val reader: LibraryReader,
    private val store: MyLibraryStore,
    private val favourites: LibraryFavourites,
    private val outbox: MutationOutbox,
    private val transport: MyLibraryTransport = OpenSubsonicMyLibraryTransport(reader),
    private val config: MyLibraryConfig = MyLibraryConfig(),
) : MembershipDelivery {
    private val lock = Mutex()
    private val listeners = mutableListOf<() -> Unit>()
    private var detectedThisConnection = false
    private var syncing = false
    private var lastError: DomainError? = null
    private var refreshedThisSession = false

    init {
        favourites.membershipDelivery = this
        if (availability() == MyLibraryAvailability.Available) installSink()
        // Outcomes, not change listeners: the session's change-listener count stays the searches'
        // and windows' own (a closed search must leave it at zero).
        favourites.addOutcomeListener { outcome -> if (outcome.field == MutationField.InLibrary) changed() }
        reader.addVisibleSurface {
            // A successful reconnect's revalidation, on an account whose server offered My Library:
            // detect again (the server may have changed) and refresh. Any other account costs no
            // request here; it is detected when a shell asks ([refresh]), so a server without the
            // extension sees exactly the requests it saw before My Library existed.
            if (reader.online) {
                detectedThisConnection = false
                if (availability() == MyLibraryAvailability.Available) launchRefresh()
            }
        }
    }

    // ---- MembershipDelivery -----------------------------------------------------------------------

    override fun availability(): MyLibraryAvailability = guarded(MyLibraryAvailability.Unknown) {
        when (store.state()?.advertised) {
            true -> MyLibraryAvailability.Available
            false -> MyLibraryAvailability.Unavailable
            null -> MyLibraryAvailability.Unknown
        }
    }

    override suspend fun setMembership(target: LibraryEntityRef, member: Boolean) = transport.setMembership(target, member)

    // ---- Entry points -----------------------------------------------------------------------------

    fun addChangeListener(listener: () -> Unit) {
        reader.checkConfined()
        listeners += listener
    }

    fun removeChangeListener(listener: () -> Unit) {
        reader.checkConfined()
        listeners.removeAll { it === listener }
    }

    /** Adds [target]. Shown at once; sent when online, else on reconnect. */
    fun add(target: LibraryEntityRef): MutationRecord {
        reader.checkConfined()
        return favourites.setInLibrary(target, true).also { changed() }
    }

    /**
     * Removes [target]. An album also takes its member songs out, so an album shown because of
     * its songs leaves My Library when the person removes it. Returns the album's own record.
     */
    fun remove(target: LibraryEntityRef): MutationRecord {
        reader.checkConfined()
        if (target.kind == LibraryEntityKind.Album) {
            val snapshot = snapshot(MyLibrarySort.RecentlyAdded)
            snapshot.memberIds[CacheItemKind.Track].orEmpty()
                .filter { trackId -> guarded(null) { reader.cache.track(trackId)?.record?.albumRawId } == target.rawId }
                .forEach { favourites.setInLibrary(LibraryEntityRef(LibraryEntityKind.Track, it), false) }
            changed()
            if (snapshot.memberIds[CacheItemKind.Album].orEmpty().contains(target.rawId).not() &&
                snapshot.membership(CacheItemKind.Album, target.rawId) != MyLibraryMembership.Unknown
            ) {
                return MutationRecord.Unchanged
            }
        }
        return favourites.setInLibrary(target, false).also { changed() }
    }

    fun membership(target: LibraryEntityRef): MyLibraryMembership {
        reader.checkConfined()
        return snapshot(MyLibrarySort.RecentlyAdded).membership(target.kind.cacheKind, target.rawId)
    }

    /**
     * Detects the extension if this connection has not, then brings the device's library up to the
     * server's. One runs at a time; a call made while one runs waits for it and runs again.
     */
    suspend fun refresh(): MyLibraryRefreshOutcome {
        reader.checkConfined()
        return lock.withLock {
            syncing = true
            changed()
            val outcome = try {
                refreshLocked()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LibraryRequestFailure) {
                MyLibraryRefreshOutcome.Failed(failure.error)
            } catch (failure: Throwable) {
                reader.uncaughtFailures += failure
                MyLibraryRefreshOutcome.Failed(DomainError.Protocol.MalformedEnvelope)
            } finally {
                syncing = false
            }
            lastError = (outcome as? MyLibraryRefreshOutcome.Failed)?.error
            refreshedThisSession = refreshedThisSession || outcome !is MyLibraryRefreshOutcome.Failed
            changed()
            outcome
        }
    }

    /** What the shells draw: every list sorted by [sort], and every item's membership. */
    fun snapshot(sort: MyLibrarySort): MyLibrarySnapshot {
        reader.checkConfined()
        return guarded(null) { buildSnapshot(sort) } ?: MyLibrarySnapshot(
            availability(), MyLibrarySyncState.Failed, lastError, null, emptyList(), emptyList(), emptyList(), emptyMap(), emptySet(), false,
        )
    }

    // ---- Internals --------------------------------------------------------------------------------

    private suspend fun refreshLocked(): MyLibraryRefreshOutcome {
        if (!detectedThisConnection) {
            val advertised = dulcetLibraryVersionIn(transport.advertisedVersions()) != null
            val before = availability()
            store.setAdvertised(advertised)
            detectedThisConnection = true
            if (advertised) installSink() else reader.cache.membershipSink = null
            if (advertised && before != MyLibraryAvailability.Available && reader.canSend) {
                // Adds and removes made before the extension was known were kept; send them now.
                reader.scope.launch { guardedSuspend { favourites.flush() } }
            }
        }
        if (availability() != MyLibraryAvailability.Available) return MyLibraryRefreshOutcome.Unavailable
        val state = store.state()
        val probe = transport.page(0, 1, null)
        writePage(probe)
        if (state?.complete == true && state.libraryClock != null && probe.libraryClock == state.libraryClock) {
            return MyLibraryRefreshOutcome.Unchanged
        }
        if (state?.complete == true && state.libraryClock != null) {
            additions(state.libraryClock)?.let { return it }
        }
        return full()
    }

    /** A `since` read of additions, accepted only when the totals prove nothing was removed; else null. */
    private suspend fun additions(heldClock: String): MyLibraryRefreshOutcome? {
        val heldMillis = parseIsoInstantMillis(heldClock) ?: return null
        val read = readAll(sinceEpochMillis = heldMillis - config.sinceSlackMillis) ?: return null
        val (pages, listed) = read
        val last = pages.last().witness
        val held = mapOf(
            CacheItemKind.Artist to store.memberIds(CacheItemKind.Artist),
            CacheItemKind.Album to store.memberIds(CacheItemKind.Album),
            CacheItemKind.Track to store.memberIds(CacheItemKind.Track),
        )
        fun union(kind: CacheItemKind) = (held[kind].orEmpty() + listed[kind].orEmpty().map { it.first }).size
        // N ⊆ held ∪ added-since and N ⊇ added-since; equal sizes make them equal (§29.9).
        if (union(CacheItemKind.Artist) != last.artistCount ||
            union(CacheItemKind.Album) != last.albumCount ||
            union(CacheItemKind.Track) != last.songCount
        ) {
            return null
        }
        store.applyAdditions(listed, pages.first().issueSeq, last.libraryClock, reader.cache.now())
        return MyLibraryRefreshOutcome.Incremental(listed.values.sumOf { it.size })
    }

    private suspend fun full(): MyLibraryRefreshOutcome {
        var attempt = 0
        while (true) {
            attempt += 1
            val read = readAll(sinceEpochMillis = null)
            if (read != null) {
                val (pages, listed) = read
                store.applyFull(listed, pages.first().issueSeq, pages.last().libraryClock, reader.cache.now())
                return MyLibraryRefreshOutcome.Full(listed.values.sumOf { it.size }, stable = true)
            }
            if (attempt >= config.maxFullAttempts) {
                // The library kept changing under every read: keep what the device has, mark the
                // read incomplete so the next refresh reads again, and say so.
                return MyLibraryRefreshOutcome.Full(store.members().size, stable = false)
            }
        }
    }

    /**
     * Pages until every kind's list ends. Null when the witness moved between the first and last
     * page — the read was not one state — so the caller must not treat it as complete.
     */
    private suspend fun readAll(sinceEpochMillis: Long?): Pair<List<MyLibraryPage>, Map<CacheItemKind, List<Pair<String, Long>>>>? {
        val pages = mutableListOf<MyLibraryPage>()
        val listed = mapOf<CacheItemKind, MutableList<Pair<String, Long>>>(
            CacheItemKind.Artist to mutableListOf(), CacheItemKind.Album to mutableListOf(), CacheItemKind.Track to mutableListOf(),
        )
        val seen = mutableSetOf<Pair<CacheItemKind, String>>()
        var offset = 0
        while (true) {
            val page = transport.page(offset, config.pageSize, sinceEpochMillis)
            writePage(page)
            pages += page
            fun take(kind: CacheItemKind, ids: List<String>) = ids.forEach { id ->
                if (seen.add(kind to id)) listed.getValue(kind) += id to page.issueSeq
            }
            take(CacheItemKind.Artist, page.entities.artists.map { it.rawId })
            take(CacheItemKind.Album, page.entities.albums.map { it.rawId })
            take(CacheItemKind.Track, page.entities.tracks.map { it.rawId })
            val more = page.entities.artists.size >= config.pageSize ||
                page.entities.albums.size >= config.pageSize ||
                page.entities.tracks.size >= config.pageSize
            if (!more) break
            offset += config.pageSize
            if (pages.size > config.maxPages) throw LibraryRequestFailure(DomainError.Protocol.MalformedEnvelope)
        }
        if (pages.first().witness != pages.last().witness) return null
        return pages to listed
    }

    private fun writePage(page: MyLibraryPage) {
        reader.cache.writeEntities(CacheWriteStamp(page.issueSeq, reader.cache.now(), null), CacheEntitySource.ListPage, page.entities)
    }

    private fun installSink() {
        reader.cache.membershipSink = { kind, rawId, member, issueSeq ->
            if (kind != CacheItemKind.Playlist && kind != CacheItemKind.Genre) {
                val before = store.isMember(kind, rawId)
                store.noteFlag(kind, rawId, member, issueSeq)
                if (before != member) scheduleChanged()
            }
        }
    }

    private var changeScheduled = false

    /** A flag changed inside a cache write's transaction: tell listeners once it has committed. */
    private fun scheduleChanged() {
        if (changeScheduled) return
        changeScheduled = true
        reader.scope.launch {
            changeScheduled = false
            changed()
        }
    }

    private fun launchRefresh() {
        reader.scope.launch { guardedSuspend { refresh() } }
    }

    private fun changed() {
        listeners.toList().forEach { listener -> guarded(Unit) { listener() } }
    }

    private fun buildSnapshot(sort: MyLibrarySort): MyLibrarySnapshot {
        val availability = availability()
        val state = store.state()
        val ranks = mutableMapOf<Pair<CacheItemKind, String>, Long>()
        store.members().forEach { ranks[it.kind to it.rawId] = it.rank }
        val pendingKeys = mutableSetOf<Pair<CacheItemKind, String>>()
        outbox.all().filter { it.field == MutationField.InLibrary }.forEach { change ->
            val key = change.target.kind.cacheKind to change.target.rawId
            pendingKeys += key
            if (change.value == 1) ranks[key] = Long.MAX_VALUE else ranks.remove(key)
        }
        val members = ranks.keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        val cache = reader.cache

        val songs = members[CacheItemKind.Track].orEmpty().mapNotNull { id ->
            val record = cache.track(id)?.record ?: return@mapNotNull null
            MyLibrarySong(record, (CacheItemKind.Track to id) in pendingKeys, ranks.getValue(CacheItemKind.Track to id))
        }
        val songsByAlbum = songs.filter { it.record.albumRawId != null }.groupBy { it.record.albumRawId!! }
        val memberTrackAlbums = members[CacheItemKind.Track].orEmpty().mapNotNullTo(mutableSetOf()) { cache.track(it)?.record?.albumRawId }

        val albumIds = members[CacheItemKind.Album].orEmpty() + songsByAlbum.keys
        val albums = albumIds.mapNotNull { id ->
            val explicit = id in members[CacheItemKind.Album].orEmpty()
            val record = cache.album(id)?.record ?: songsByAlbum[id]?.first()?.record?.let { song ->
                CacheAlbumRecord(rawId = id, title = song.albumTitle ?: return@let null, credits = song.credits, artworkKey = song.artworkKey)
            } ?: return@mapNotNull null
            val memberSongs = songsByAlbum[id].orEmpty()
            val rank = maxOf(if (explicit) ranks.getValue(CacheItemKind.Album to id) else Long.MIN_VALUE, memberSongs.maxOfOrNull { it.rank } ?: Long.MIN_VALUE)
            MyLibraryAlbum(record, explicit, memberSongs.size, (CacheItemKind.Album to id) in pendingKeys || memberSongs.any { it.pending }, rank)
        }

        val artistRanks = mutableMapOf<String, Long>()
        val artistPending = mutableSetOf<String>()
        fun contribute(artistId: String?, rank: Long, pending: Boolean) {
            if (artistId == null) return
            artistRanks[artistId] = maxOf(artistRanks[artistId] ?: Long.MIN_VALUE, rank)
            if (pending) artistPending += artistId
        }
        members[CacheItemKind.Artist].orEmpty().forEach { contribute(it, ranks.getValue(CacheItemKind.Artist to it), (CacheItemKind.Artist to it) in pendingKeys) }
        albums.forEach { album -> album.record.credits.forEach { contribute(it.artistRawId, album.rank, album.pending) } }
        songs.forEach { song -> song.record.credits.forEach { contribute(it.artistRawId, song.rank, song.pending) } }
        val artists = artistRanks.mapNotNull { (id, rank) ->
            val record = cache.artist(id)?.record ?: run {
                val name = (albums.flatMap { it.record.credits } + songs.flatMap { it.record.credits })
                    .firstOrNull { it.artistRawId == id }?.name ?: return@mapNotNull null
                CacheArtistRecord(rawId = id, name = name)
            }
            MyLibraryArtist(record, id in members[CacheItemKind.Artist].orEmpty(), id in artistPending, rank)
        }

        val syncState = when {
            syncing -> MyLibrarySyncState.Syncing
            lastError != null -> MyLibrarySyncState.Failed
            state?.complete != true -> MyLibrarySyncState.Never
            refreshedThisSession -> MyLibrarySyncState.Current
            else -> MyLibrarySyncState.Cached
        }
        return MyLibrarySnapshot(
            availability = availability,
            syncState = syncState,
            lastError = lastError,
            lastReadAtWall = state?.readAtWall,
            albums = albums.sortedWith(order(sort, { it.rank }, { it.record.title }, { it.record.rawId })),
            artists = artists.sortedWith(order(sort, { it.rank }, { it.record.name }, { it.record.rawId })),
            songs = songs.sortedWith(order(sort, { it.rank }, { it.record.title }, { it.record.rawId })),
            memberIds = members,
            albumsWithMemberSongs = memberTrackAlbums,
            complete = state?.complete == true,
        )
    }

    private fun <T> order(sort: MyLibrarySort, rank: (T) -> Long, name: (T) -> String, id: (T) -> String): Comparator<T> = when (sort) {
        MyLibrarySort.RecentlyAdded -> compareByDescending<T> { rank(it) }.thenBy { id(it) }
        MyLibrarySort.Alphabetical -> compareBy<T> { normalizeSearchText(name(it)) }.thenBy { id(it) }
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        fallback
    }

    private suspend inline fun guardedSuspend(block: () -> Unit) {
        try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            reader.uncaughtFailures += failure
        }
    }
}

internal data class MyLibraryConfig(
    val pageSize: Int = 500,
    val maxFullAttempts: Int = 3,
    /** How far before the held clock a `since` read starts; the totals check makes any value safe. */
    val sinceSlackMillis: Long = 60_000,
    /** A library this many pages deep is not one; the read stops rather than paging for ever. */
    val maxPages: Int = 2_000,
)

/** Where a My Library change is sent, and whether it can be (spec §29.9). */
internal interface MembershipDelivery {
    fun availability(): MyLibraryAvailability
    suspend fun setMembership(target: LibraryEntityRef, member: Boolean)
}

/**
 * Epoch milliseconds of an ISO-8601 UTC instant as the server writes `changed`
 * (`2026-10-12T18:04:11.532Z`), or null when it is not one. Used only to start a `since` read;
 * the clock itself is compared for equality, never ordered.
 */
internal fun parseIsoInstantMillis(value: String): Long? {
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$""").matchEntire(value) ?: return null
    val (y, mo, d, h, mi, s, frac) = match.destructured
    val year = y.toInt()
    val month = mo.toInt()
    val day = d.toInt()
    if (month !in 1..12 || day !in 1..31) return null
    // Days from civil (Howard Hinnant's algorithm).
    val yy = if (month <= 2) year - 1 else year
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097 + doe - 719468
    val millis = frac.padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
    return ((days * 24 + h.toLong()) * 60 + mi.toLong()) * 60_000 + s.toLong() * 1_000 + millis
}
