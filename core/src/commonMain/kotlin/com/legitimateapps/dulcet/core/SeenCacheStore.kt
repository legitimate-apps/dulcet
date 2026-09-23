package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.Cache_album
import com.legitimateapps.dulcet.database.Cache_artist
import com.legitimateapps.dulcet.database.Cache_list
import com.legitimateapps.dulcet.database.Cache_playlist
import com.legitimateapps.dulcet.database.Cache_track
import com.legitimateapps.dulcet.database.DulcetDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The seen-cache of spec §16.10: normalized entities, list windows and pins, keyed by provider
 * instance. This file is the storage half of the reader; [LibraryReader] is the only intended
 * writer of list windows, and every read the UI makes goes through a [BoundSeenCache].
 *
 * Three properties are enforced HERE, structurally, rather than remembered by callers:
 *
 * 1. **Binding (CONF-80).** The only way to read or write a namespace is [SeenCacheStore.bind],
 *    which compares the stored binding with the presenting account and purges the namespace in
 *    one transaction on any difference, before it returns a handle.
 * 2. **Issue order.** Every write carries the [CacheWriteStamp.issueSeq] its request took when it
 *    was SENT ([BoundSeenCache.issue]); a row is rewritten only by a higher sequence, so a slow
 *    response never overwrites a faster, later one.
 * 3. **Pins survive eviction (CONF-78).** Eviction selects candidates with SQL that excludes
 *    pinned rows and rows still referenced by a window or a complete detail, so no ceiling can
 *    reach them.
 */
internal class SeenCacheStore(
    private val store: DulcetDatabaseStore,
    private val clock: SeenCacheWallClock,
    private val ceilings: SeenCacheCeilings = SeenCacheCeilings.DEFAULT,
) {
    private val database: DulcetDatabase get() = store.database

    /**
     * Returns the namespace for [binding], purging it first if it was filled from a different
     * server URL or username. A namespace with no binding yet adopts [binding] and keeps its rows:
     * those can only have been seeded on upgrade from this same provider instance's mirror
     * (spec §16.17), which carried no binding of its own.
     */
    fun bind(binding: CacheBinding): BoundSeenCache {
        val purged = database.transactionWithResult {
            val queries = database.seenCacheQueries
            val stored = queries.selectBinding(binding.serverId).executeAsOneOrNull()
            val matches = stored != null &&
                stored.normalized_base_url == binding.normalizedBaseUrl &&
                stored.username == binding.username
            if (stored != null && !matches) {
                queries.purgeNamespace(binding.serverId)
                check(queries.countNamespaceRows(binding.serverId).executeAsOne().sum == 0L) {
                    "binding purge left rows behind"
                }
            }
            if (!matches) {
                queries.insertBinding(
                    binding.serverId,
                    binding.normalizedBaseUrl,
                    binding.username,
                    clock.nowEpochMilliseconds(),
                )
            }
            stored != null && !matches
        }
        return BoundSeenCache(binding.serverId, database, clock, ceilings, purgedOnBind = purged)
    }
}

internal fun interface SeenCacheWallClock {
    fun nowEpochMilliseconds(): Long
}

/** Row-count ceilings per account (spec §16.13); deterministic and testable, unlike byte sizes. */
internal data class SeenCacheCeilings(
    val albums: Long,
    val tracks: Long,
    val artists: Long,
    val lists: Long,
) {
    init {
        require(albums > 0 && tracks > 0 && artists > 0 && lists > 0)
    }

    companion object {
        val DEFAULT = SeenCacheCeilings(albums = 50_000, tracks = 500_000, artists = 20_000, lists = 2_000)
    }
}

internal data class CacheBinding(
    val serverId: String,
    val normalizedBaseUrl: String,
    val username: String,
) {
    init {
        require(serverId.isNotBlank())
    }

    override fun toString(): String = "CacheBinding(serverId=$serverId, <redacted>)"
}

internal enum class CacheItemKind(val wireName: String) {
    Artist("artist"),
    Album("album"),
    Track("track"),
    Playlist("playlist"),
    Genre("genre");

    companion object {
        fun fromWireName(value: String): CacheItemKind =
            entries.firstOrNull { it.wireName == value } ?: error("unknown cache item kind")
    }
}

internal enum class CachePinReason(val wireName: String) {
    Download("download"),
    Queue("queue"),
    Playing("playing");

    companion object {
        fun fromWireName(value: String): CachePinReason =
            entries.firstOrNull { it.wireName == value } ?: error("unknown pin reason")
    }
}

internal enum class CacheCoverage(val wireName: String) {
    Complete("complete"),
    Open("open"),
    UnverifiedScanning("unverified_scanning"),
    UnverifiedNoEpoch("unverified_no_epoch");

    companion object {
        fun fromWireName(value: String): CacheCoverage =
            entries.firstOrNull { it.wireName == value } ?: error("unknown coverage")
    }
}

/**
 * Where an entity write came from. Only a read that LISTS the entity as present may clear `gone`:
 * a list page, a detail read's membership, or a search result. [SongLookup] never can — under
 * the server's default missing-file setting `getSong` answers `ok` with full metadata for a file
 * that no longer exists (spec §16.11 rule 4), so its answer is not evidence of presence.
 */
internal enum class CacheEntitySource(val clearsGone: Boolean) {
    ListPage(true),
    Detail(true),
    Search(true),
    SongLookup(false),
}

/** The issue sequence, wall-clock and epoch a response is written under. */
internal data class CacheWriteStamp(
    val issueSeq: Long,
    val fetchedAtWall: Long,
    val fetchedEpoch: String?,
) {
    init {
        require(issueSeq > 0) { "issue sequences start at 1; 0 is reserved for seeded rows" }
    }
}

/** Per-user state. Every field is nullable because unknown is not false (spec §16.10). */
internal data class CacheUserState(
    val starred: Boolean? = null,
    val starredAt: String? = null,
    val userRating: Int? = null,
    val playCount: Long? = null,
    val played: String? = null,
)

internal data class CacheCredit(
    val role: CreditRole,
    val name: String,
    val artistRawId: String?,
)

internal data class CacheArtistRecord(
    val rawId: String,
    val name: String,
    val albumCount: Int? = null,
    val artworkKey: String? = null,
    val userState: CacheUserState = CacheUserState(),
)

internal data class CacheAlbumRecord(
    val rawId: String,
    val title: String,
    val credits: List<CacheCredit> = emptyList(),
    val year: Int? = null,
    val genre: String? = null,
    val durationMilliseconds: Long? = null,
    val songCount: Int? = null,
    val artworkKey: String? = null,
    val userState: CacheUserState = CacheUserState(),
)

internal data class CacheTrackRecord(
    val rawId: String,
    val albumRawId: String?,
    val title: String,
    val albumTitle: String? = null,
    val credits: List<CacheCredit> = emptyList(),
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val durationMilliseconds: Long? = null,
    val sourceContainer: AudioContainer? = null,
    val artworkKey: String? = null,
    val userState: CacheUserState = CacheUserState(),
)

internal data class CachePlaylistRecord(
    val rawId: String,
    val name: String,
    val songCount: Int? = null,
    val durationMilliseconds: Long? = null,
    val owner: String? = null,
    val artworkKey: String? = null,
)

/** Entities carried by one response, written with it in one transaction. */
internal data class CacheEntities(
    val artists: List<CacheArtistRecord> = emptyList(),
    val albums: List<CacheAlbumRecord> = emptyList(),
    val tracks: List<CacheTrackRecord> = emptyList(),
    val playlists: List<CachePlaylistRecord> = emptyList(),
)

internal data class CacheListMember(val kind: CacheItemKind, val rawId: String)

internal data class CachedListState(
    val listKey: String,
    val windowEpoch: String?,
    val folderIds: Set<String>?,
    val firstLoadedOffset: Int,
    val endLoadedOffset: Int,
    val total: Int?,
    val coverage: CacheCoverage,
    val fetchedAtWall: Long?,
    val lastAccessWall: Long,
    val issueSeq: Long,
)

internal data class CachedListPosition(val position: Int, val member: CacheListMember)

internal data class StoredCatalogEpoch(
    val lastScan: String?,
    val folderIds: Set<String>,
    val scanning: Boolean,
    val readAtWall: Long,
)

/** What a cached entity carries beyond its record: how and when it was learned. */
internal data class CacheRowState(
    val fetchedAtWall: Long?,
    val fetchedEpoch: String?,
    val issueSeq: Long,
    val lastAccessWall: Long,
    val gone: Boolean,
)

internal data class CachedArtist(val record: CacheArtistRecord, val row: CacheRowState)

internal data class CachedAlbum(
    val record: CacheAlbumRecord,
    val row: CacheRowState,
    val detailComplete: Boolean,
)

internal data class CachedTrack(
    val record: CacheTrackRecord?,
    val rawId: String,
    val row: CacheRowState,
    val metadataMissing: Boolean,
)

internal data class CachedPlaylist(
    val record: CachePlaylistRecord,
    val row: CacheRowState,
    val detailComplete: Boolean,
)

internal data class CachePin(val kind: CacheItemKind, val rawId: String, val reason: CachePinReason)

internal data class EvictionReport(
    val lists: List<String>,
    val albums: List<String>,
    val tracks: List<String>,
    val artists: List<String>,
) {
    val isEmpty: Boolean get() = lists.isEmpty() && albums.isEmpty() && tracks.isEmpty() && artists.isEmpty()
}

/** The outcome of writing a successful album detail read (spec §16.11 gone rules 1 and 3). */
internal data class AlbumDetailWrite(
    val albumGone: Boolean,
    val tracksMarkedGone: List<String>,
    val membershipApplied: Boolean,
)

/** Seen-cache counts for the offline search label (spec §16.15). */
internal data class SeenCacheCounts(val artists: Long, val albums: Long, val tracks: Long)

/**
 * One account's namespace. Obtainable only through [SeenCacheStore.bind]; there is no other
 * constructor path, which is what makes the binding check impossible to skip.
 */
internal class BoundSeenCache internal constructor(
    val serverId: String,
    private val database: DulcetDatabase,
    private val clock: SeenCacheWallClock,
    private val ceilings: SeenCacheCeilings,
    /** True when [SeenCacheStore.bind] found a different binding and purged this namespace. */
    val purgedOnBind: Boolean,
) {
    private val queries get() = database.seenCacheQueries

    /**
     * Takes the issue sequence for a request about to be SENT. Durable, so a request issued after
     * a relaunch still outranks every row written before it; the counter is shared across
     * accounts, which costs nothing and keeps a single ordering.
     */
    fun issue(): Long = database.transactionWithResult {
        queries.initializeIssueCounter()
        queries.advanceIssueCounter()
        queries.selectIssueCounter().executeAsOne()
    }

    fun now(): Long = clock.nowEpochMilliseconds()

    // ---- Epoch ----------------------------------------------------------------------------------

    fun storedEpoch(): StoredCatalogEpoch? =
        queries.selectEpoch(serverId).executeAsOneOrNull()?.let {
            StoredCatalogEpoch(it.last_scan, decodeIdSet(it.folder_ids), it.scanning == 1L, it.read_at_wall)
        }

    fun saveEpoch(epoch: StoredCatalogEpoch) {
        queries.saveEpoch(
            serverId,
            epoch.lastScan,
            encodeIdSet(epoch.folderIds),
            if (epoch.scanning) 1 else 0,
            epoch.readAtWall,
        )
    }

    // ---- Entity writes --------------------------------------------------------------------------

    /**
     * Writes entities carried by one response. Each row is rewritten only when [stamp] outranks
     * the row's issue sequence. Returns the raw ids of rows this write actually changed.
     */
    fun writeEntities(stamp: CacheWriteStamp, source: CacheEntitySource, entities: CacheEntities): Set<String> =
        database.transactionWithResult { writeEntitiesInTransaction(stamp, source, entities) }

    private fun writeEntitiesInTransaction(
        stamp: CacheWriteStamp,
        source: CacheEntitySource,
        entities: CacheEntities,
    ): Set<String> {
        val written = mutableSetOf<String>()
        entities.artists.forEach { if (upsertArtist(stamp, it)) written += it.rawId }
        entities.albums.forEach { if (upsertAlbumSummary(stamp, source, it)) written += it.rawId }
        entities.tracks.forEach { if (upsertTrack(stamp, source, it, albumOrdinal = null)) written += it.rawId }
        entities.playlists.forEach { if (upsertPlaylist(stamp, it)) written += it.rawId }
        return written
    }

    private fun upsertArtist(stamp: CacheWriteStamp, artist: CacheArtistRecord): Boolean {
        val existing = queries.selectArtistIssueSeq(serverId, artist.rawId).executeAsOneOrNull()
        val state = artist.userState
        if (existing == null) {
            queries.insertArtist(
                serverId, artist.rawId, artist.name, normalizeSearchText(artist.name),
                artist.albumCount?.toLong(), artist.artworkKey, state.starred.asFlag(), state.starredAt,
                state.userRating?.toLong(), stamp.fetchedAtWall, stamp.fetchedEpoch, stamp.issueSeq,
                now(),
            )
            return true
        }
        if (existing >= stamp.issueSeq) return false
        queries.updateArtist(
            name = artist.name,
            normalized_name = normalizeSearchText(artist.name),
            album_count = artist.albumCount?.toLong(),
            artwork_key = artist.artworkKey,
            starred = state.starred.asFlag(),
            starred_at = state.starredAt,
            user_rating = state.userRating?.toLong(),
            fetched_at_wall = stamp.fetchedAtWall,
            fetched_epoch = stamp.fetchedEpoch,
            issue_seq = stamp.issueSeq,
            server_id = serverId,
            raw_id = artist.rawId,
        )
        return true
    }

    private fun upsertAlbumSummary(
        stamp: CacheWriteStamp,
        source: CacheEntitySource,
        album: CacheAlbumRecord,
    ): Boolean {
        val existing = queries.selectAlbumSequences(serverId, album.rawId).executeAsOneOrNull()
        val state = album.userState
        val artist = album.credits.firstOrNull()
        if (existing == null) {
            queries.insertAlbum(
                serverId, album.rawId, album.title, normalizeSearchText(album.title), artist?.name,
                artist?.artistRawId, album.year?.toLong(), album.genre, album.durationMilliseconds,
                album.songCount?.toLong(), album.artworkKey, state.starred.asFlag(), state.starredAt,
                state.userRating?.toLong(), state.playCount, state.played, stamp.fetchedAtWall,
                stamp.fetchedEpoch, stamp.issueSeq, now(),
            )
        } else {
            if (existing.issue_seq >= stamp.issueSeq) return false
            queries.updateAlbumSummary(
                title = album.title,
                normalized_title = normalizeSearchText(album.title),
                artist_name = artist?.name,
                artist_raw_id = artist?.artistRawId,
                year = album.year?.toLong(),
                genre = album.genre,
                duration_milliseconds = album.durationMilliseconds,
                song_count = album.songCount?.toLong(),
                artwork_key = album.artworkKey,
                starred = state.starred.asFlag(),
                starred_at = state.starredAt,
                user_rating = state.userRating?.toLong(),
                play_count = state.playCount,
                played = state.played,
                fetched_at_wall = stamp.fetchedAtWall,
                fetched_epoch = stamp.fetchedEpoch,
                issue_seq = stamp.issueSeq,
                clears_gone = if (source.clearsGone) 1 else 0,
                server_id = serverId,
                raw_id = album.rawId,
            )
        }
        replaceCredits("album", album.rawId, album.credits)
        return true
    }

    private fun upsertTrack(
        stamp: CacheWriteStamp,
        source: CacheEntitySource,
        track: CacheTrackRecord,
        albumOrdinal: Int?,
    ): Boolean {
        val existing = queries.selectTrackIssueSeq(serverId, track.rawId).executeAsOneOrNull()
        val state = track.userState
        val artist = track.credits.firstOrNull()
        if (existing == null) {
            queries.insertTrack(
                serverId, track.rawId, track.albumRawId, albumOrdinal?.toLong(), track.title,
                normalizeSearchText(track.title), track.albumTitle,
                normalizeSearchText(track.albumTitle.orEmpty()), artist?.name, artist?.artistRawId,
                track.discNumber?.toLong(), track.trackNumber?.toLong(), track.durationMilliseconds,
                track.sourceContainer?.cacheWireName(), track.artworkKey, state.starred.asFlag(),
                state.starredAt, state.userRating?.toLong(), state.playCount, state.played,
                stamp.fetchedAtWall, stamp.fetchedEpoch, stamp.issueSeq, now(),
            )
        } else {
            if (existing >= stamp.issueSeq) return false
            queries.updateTrack(
                album_raw_id = track.albumRawId,
                album_ordinal = albumOrdinal?.toLong(),
                title = track.title,
                normalized_title = normalizeSearchText(track.title),
                album_title = track.albumTitle,
                normalized_album_title = normalizeSearchText(track.albumTitle.orEmpty()),
                artist_name = artist?.name,
                artist_raw_id = artist?.artistRawId,
                disc_number = track.discNumber?.toLong(),
                track_number = track.trackNumber?.toLong(),
                duration_milliseconds = track.durationMilliseconds,
                source_container = track.sourceContainer?.cacheWireName(),
                artwork_key = track.artworkKey,
                starred = state.starred.asFlag(),
                starred_at = state.starredAt,
                user_rating = state.userRating?.toLong(),
                play_count = state.playCount,
                played = state.played,
                fetched_at_wall = stamp.fetchedAtWall,
                fetched_epoch = stamp.fetchedEpoch,
                issue_seq = stamp.issueSeq,
                clears_gone = if (source.clearsGone) 1 else 0,
                server_id = serverId,
                raw_id = track.rawId,
            )
        }
        replaceCredits("track", track.rawId, track.credits)
        return true
    }

    private fun upsertPlaylist(stamp: CacheWriteStamp, playlist: CachePlaylistRecord): Boolean {
        val existing = queries.selectPlaylistSequences(serverId, playlist.rawId).executeAsOneOrNull()
        if (existing == null) {
            queries.insertPlaylist(
                serverId, playlist.rawId, playlist.name, playlist.songCount?.toLong(),
                playlist.durationMilliseconds, playlist.owner, playlist.artworkKey,
                stamp.fetchedAtWall, stamp.fetchedEpoch, stamp.issueSeq, now(),
            )
            return true
        }
        if (existing.issue_seq >= stamp.issueSeq) return false
        queries.updatePlaylist(
            name = playlist.name,
            song_count = playlist.songCount?.toLong(),
            duration_milliseconds = playlist.durationMilliseconds,
            owner = playlist.owner,
            artwork_key = playlist.artworkKey,
            fetched_at_wall = stamp.fetchedAtWall,
            fetched_epoch = stamp.fetchedEpoch,
            issue_seq = stamp.issueSeq,
            server_id = serverId,
            raw_id = playlist.rawId,
        )
        return true
    }

    private fun replaceCredits(ownerKind: String, ownerRawId: String, credits: List<CacheCredit>) {
        queries.deleteCredits(serverId, ownerKind, ownerRawId)
        credits.groupBy { it.role }.forEach { (role, forRole) ->
            forRole.forEachIndexed { ordinal, credit ->
                queries.insertCredit(
                    serverId, ownerKind, ownerRawId, role.cacheWireName(), ordinal.toLong(),
                    credit.name, credit.artistRawId, normalizeSearchText(credit.name),
                )
            }
        }
    }

    // ---- Detail writes (spec §16.11 gone rules) -------------------------------------------------

    /**
     * A successful `getAlbum`: the album's complete track list NOW. A cached track of the album
     * that is absent from it is `gone` (rule 1); an album whose list is empty is `gone` (rule 3).
     * Membership is rewritten in full, in one transaction, and only when [stamp] outranks the
     * album's previous detail read.
     */
    fun writeAlbumDetail(
        stamp: CacheWriteStamp,
        album: CacheAlbumRecord,
        tracks: List<CacheTrackRecord>,
    ): AlbumDetailWrite = database.transactionWithResult {
        upsertAlbumSummary(stamp, CacheEntitySource.Detail, album)
        val sequences = queries.selectAlbumSequences(serverId, album.rawId).executeAsOne()
        if (sequences.detail_issue_seq >= stamp.issueSeq) {
            return@transactionWithResult AlbumDetailWrite(albumGone = false, tracksMarkedGone = emptyList(), membershipApplied = false)
        }
        tracks.forEachIndexed { ordinal, track ->
            upsertTrack(stamp, CacheEntitySource.Detail, track.copy(albumRawId = album.rawId), ordinal)
        }
        val present = tracks.mapTo(mutableSetOf()) { it.rawId }
        val absent = queries.selectAlbumTrackIds(serverId, album.rawId).executeAsList()
            .filter { it !in present }
        val markedGone = absent.filter { rawId ->
            queries.markTrackGone(issue_seq = stamp.issueSeq, server_id = serverId, raw_id = rawId).value > 0
        }
        val albumGone = tracks.isEmpty()
        if (albumGone) {
            queries.markAlbumGoneByDetail(stamp.issueSeq, serverId, album.rawId)
        } else {
            queries.markAlbumDetail(detail_issue_seq = stamp.issueSeq, gone = 0, server_id = serverId, raw_id = album.rawId)
        }
        AlbumDetailWrite(albumGone = albumGone, tracksMarkedGone = markedGone, membershipApplied = true)
    }

    /**
     * Code 70 on a detail read (rule 2). The album is gone, and with it every cached track of it:
     * a Subsonic album exists because its songs do, so a missing album has no present songs.
     */
    fun markAlbumNotFound(issueSeq: Long, albumRawId: String): Boolean = database.transactionWithResult {
        val applied = queries.markAlbumGoneByDetail(issueSeq, serverId, albumRawId).value > 0
        if (applied) {
            queries.selectAlbumTrackIds(serverId, albumRawId).executeAsList().forEach { rawId ->
                queries.markTrackGone(issue_seq = issueSeq, server_id = serverId, raw_id = rawId)
            }
        }
        applied
    }

    fun markArtistNotFound(artistRawId: String) {
        queries.markArtistGone(serverId, artistRawId)
    }

    fun markPlaylistNotFound(issueSeq: Long, playlistRawId: String) = database.transaction {
        queries.markPlaylistGoneByDetail(issueSeq, serverId, playlistRawId)
        queries.deleteList(serverId, playlistDetailListKey(playlistRawId))
    }

    fun markPlaylistDetail(issueSeq: Long, playlistRawId: String) {
        queries.markPlaylistDetail(issueSeq, serverId, playlistRawId)
    }

    // ---- Lists and windows ----------------------------------------------------------------------

    /**
     * Writes one page of a window and its entities in one transaction (spec §16.10: a cached window
     * never references an entity that is not cached). [members] replace positions
     * `[pageStart, pageStart + members.size)`; when [keepRange] is given, every member outside it is
     * dropped in the same transaction (a rebase, §16.12).
     */
    fun writeWindowPage(
        stamp: CacheWriteStamp,
        source: CacheEntitySource,
        state: CachedListState,
        pageStart: Int,
        replacedEnd: Int,
        members: List<CacheListMember>,
        entities: CacheEntities,
        keepRange: IntRange? = null,
    ) = database.transaction {
        writeEntitiesInTransaction(stamp, source, entities)
        saveListInTransaction(state)
        queries.deleteListMembersInRange(serverId, state.listKey, pageStart.toLong(), maxOf(replacedEnd, pageStart + members.size).toLong())
        members.forEachIndexed { index, member ->
            queries.insertListMember(serverId, state.listKey, (pageStart + index).toLong(), member.kind.wireName, member.rawId)
        }
        if (keepRange != null) {
            queries.deleteListMembersOutside(serverId, state.listKey, keepRange.first.toLong(), keepRange.last.toLong() + 1)
        }
    }

    /** Replaces a whole single-response list (one server read, §16.12) and its entities. */
    fun writeWholeList(
        stamp: CacheWriteStamp,
        source: CacheEntitySource,
        state: CachedListState,
        members: List<CacheListMember>,
        entities: CacheEntities,
    ) = database.transaction {
        writeEntitiesInTransaction(stamp, source, entities)
        saveListInTransaction(state)
        queries.deleteListMembers(serverId, state.listKey)
        members.forEachIndexed { index, member ->
            queries.insertListMember(serverId, state.listKey, index.toLong(), member.kind.wireName, member.rawId)
        }
    }

    fun saveListState(state: CachedListState) = saveListInTransaction(state)

    private fun saveListInTransaction(state: CachedListState) {
        val folders = state.folderIds?.let(::encodeIdSet)
        queries.insertListIfAbsent(
            serverId, state.listKey, state.windowEpoch, folders,
            state.firstLoadedOffset.toLong(), state.endLoadedOffset.toLong(), state.total?.toLong(),
            state.coverage.wireName, state.fetchedAtWall, state.lastAccessWall, state.issueSeq,
        )
        queries.updateList(
            window_epoch = state.windowEpoch,
            folder_ids = folders,
            first_loaded_offset = state.firstLoadedOffset.toLong(),
            end_loaded_offset = state.endLoadedOffset.toLong(),
            total = state.total?.toLong(),
            coverage = state.coverage.wireName,
            fetched_at_wall = state.fetchedAtWall,
            last_access_wall = state.lastAccessWall,
            issue_seq = state.issueSeq,
            server_id = serverId,
            list_key = state.listKey,
        )
    }

    fun listState(listKey: String): CachedListState? =
        queries.selectList(serverId, listKey).executeAsOneOrNull()?.toState()

    fun listMembers(listKey: String): List<CachedListPosition> =
        queries.selectListMembers(serverId, listKey).executeAsList().map {
            CachedListPosition(it.position.toInt(), CacheListMember(CacheItemKind.fromWireName(it.item_kind), it.raw_id))
        }

    fun deleteList(listKey: String) = database.transaction { deleteListInTransaction(listKey) }

    private fun deleteListInTransaction(listKey: String) {
        queries.deleteList(serverId, listKey)
        playlistRawIdOfDetailKey(listKey)?.let { queries.clearPlaylistDetail(serverId, it) }
    }

    /** Records that a window and its entities were SHOWN (§16.13: eviction is by last access). */
    fun touchList(listKey: String) = database.transaction {
        val now = now()
        queries.touchList(now, serverId, listKey)
        queries.touchListAlbums(now, serverId, listKey)
        queries.touchListArtists(now, serverId, listKey)
        queries.touchListTracks(now, serverId, listKey)
        queries.touchListPlaylists(now, serverId, listKey)
    }

    fun touchAlbum(albumRawId: String) = database.transaction {
        val now = now()
        queries.touchAlbum(now, serverId, albumRawId)
        queries.touchAlbumTracks(now, serverId, albumRawId)
    }

    // ---- Entity reads ---------------------------------------------------------------------------

    fun artist(rawId: String): CachedArtist? =
        queries.selectArtist(serverId, rawId).executeAsOneOrNull()?.toCached()

    fun album(rawId: String): CachedAlbum? =
        queries.selectAlbum(serverId, rawId).executeAsOneOrNull()?.toCached(credits("album", rawId))

    fun track(rawId: String): CachedTrack? =
        queries.selectTrack(serverId, rawId).executeAsOneOrNull()?.toCached(credits("track", rawId))

    fun playlist(rawId: String): CachedPlaylist? =
        queries.selectPlaylist(serverId, rawId).executeAsOneOrNull()?.toCached()

    /** The album's present tracks in server order (gone tracks excluded). */
    fun albumTracks(albumRawId: String): List<CachedTrack> =
        queries.selectAlbumTracks(serverId, albumRawId).executeAsList().map { it.toCached(credits("track", it.raw_id)) }

    /** Every cached album, for the local "Available offline" view (§16.14), sorted locally. */
    fun localAlbums(): List<CachedAlbum> =
        queries.selectLocalAlbums(serverId).executeAsList().map { it.toCached(credits("album", it.raw_id)) }

    fun localArtists(): List<CachedArtist> =
        queries.selectLocalArtists(serverId).executeAsList().map { it.toCached() }

    private fun credits(ownerKind: String, rawId: String): List<CacheCredit> =
        queries.selectCredits(serverId, ownerKind, rawId).executeAsList().map {
            CacheCredit(creditRoleFromCacheWireName(it.role), it.name, it.artist_raw_id)
        }

    // ---- Local search support (consumed by the local half of §16.15) ------------------------------

    fun counts(): SeenCacheCounts = SeenCacheCounts(
        artists = queries.countSearchableArtists(serverId).executeAsOne(),
        albums = queries.countSearchableAlbums(serverId).executeAsOne(),
        tracks = queries.countSearchableTracks(serverId).executeAsOne(),
    )

    /** Rows matching an already-normalized query; ranking belongs to the search layer. */
    fun searchArtists(normalizedQuery: String): List<CachedArtist> =
        queries.searchCachedArtists(serverId, normalizedQuery).executeAsList().mapNotNull { artist(it.raw_id) }

    fun searchAlbums(normalizedQuery: String): List<CachedAlbum> =
        queries.searchCachedAlbums(serverId, normalizedQuery).executeAsList().mapNotNull { album(it.raw_id) }

    fun searchTracks(normalizedQuery: String): List<CachedTrack> =
        queries.searchCachedTracks(serverId, normalizedQuery).executeAsList().mapNotNull { track(it.raw_id) }

    // ---- Pins (spec §16.13) ---------------------------------------------------------------------

    /**
     * Pins an entity. A track that is not cached gets an identity-only row marked
     * metadata-missing, refilled by its next live read — a pin never points at nothing.
     */
    fun pin(kind: CacheItemKind, rawId: String, reason: CachePinReason) = database.transaction {
        require(kind != CacheItemKind.Genre)
        if (kind == CacheItemKind.Track) queries.insertIdentityOnlyTrack(serverId, rawId, now())
        queries.insertPin(serverId, kind.wireName, rawId, reason.wireName)
    }

    fun unpin(kind: CacheItemKind, rawId: String, reason: CachePinReason) {
        queries.deletePin(serverId, kind.wireName, rawId, reason.wireName)
    }

    fun pins(): List<CachePin> = queries.selectPins(serverId).executeAsList().map {
        CachePin(CacheItemKind.fromWireName(it.item_kind), it.raw_id, CachePinReason.fromWireName(it.reason))
    }

    // ---- Eviction (spec §16.13, CONF-78) --------------------------------------------------------

    /**
     * Runs after a write that may have crossed a ceiling, in its own transaction.
     *
     * Order: windows over the window ceiling go first, least-recently-accessed first; then, for each
     * entity kind over its ceiling, orphaned entities go least-recently-accessed first; and only
     * when no orphan of that kind is left is another whole window released to create more. An
     * entity is an orphan when it is unpinned and no window or complete detail references it — the
     * candidate queries encode that, so a pinned row cannot be selected whatever the ceiling.
     */
    fun evictIfNeeded(): EvictionReport = database.transactionWithResult {
        val lists = mutableListOf<String>()
        val albums = mutableListOf<String>()
        val tracks = mutableListOf<String>()
        val artists = mutableListOf<String>()

        fun evictOneList(): Boolean {
            val key = queries.selectLeastRecentlyAccessedList(serverId).executeAsOneOrNull() ?: return false
            deleteListInTransaction(key)
            lists += key
            return true
        }

        while (queries.countLists(serverId).executeAsOne() > ceilings.lists) {
            if (!evictOneList()) break
        }

        fun reduce(count: () -> Long, ceiling: Long, candidates: (Long) -> List<String>, delete: (String) -> Unit) {
            while (true) {
                val excess = count() - ceiling
                if (excess <= 0) return
                val evictable = candidates(excess)
                if (evictable.isNotEmpty()) {
                    evictable.forEach(delete)
                } else if (!evictOneList()) {
                    return
                }
            }
        }

        reduce(
            count = { queries.countAlbums(serverId).executeAsOne() },
            ceiling = ceilings.albums,
            candidates = { queries.selectEvictableAlbums(serverId, it).executeAsList() },
            delete = { rawId ->
                queries.deleteCredits(serverId, "album", rawId)
                queries.deleteAlbum(serverId, rawId)
                albums += rawId
            },
        )
        reduce(
            count = { queries.countTracks(serverId).executeAsOne() },
            ceiling = ceilings.tracks,
            candidates = { queries.selectEvictableTracks(serverId, it).executeAsList() },
            delete = { rawId ->
                queries.deleteCredits(serverId, "track", rawId)
                queries.deleteTrack(serverId, rawId)
                tracks += rawId
            },
        )
        reduce(
            count = { queries.countArtists(serverId).executeAsOne() },
            ceiling = ceilings.artists,
            candidates = { queries.selectEvictableArtists(serverId, it).executeAsList() },
            delete = { rawId ->
                queries.deleteArtist(serverId, rawId)
                artists += rawId
            },
        )
        EvictionReport(lists, albums, tracks, artists)
    }

    // ---- Mapping --------------------------------------------------------------------------------

    private fun Cache_list.toState() = CachedListState(
        listKey = list_key,
        windowEpoch = window_epoch,
        folderIds = folder_ids?.let(::decodeIdSet),
        firstLoadedOffset = first_loaded_offset.toInt(),
        endLoadedOffset = end_loaded_offset.toInt(),
        total = total?.toInt(),
        coverage = CacheCoverage.fromWireName(coverage),
        fetchedAtWall = fetched_at_wall,
        lastAccessWall = last_access_wall,
        issueSeq = issue_seq,
    )

    private fun Cache_artist.toCached() = CachedArtist(
        CacheArtistRecord(
            rawId = raw_id,
            name = name,
            albumCount = album_count?.toInt(),
            artworkKey = artwork_key,
            userState = CacheUserState(starred.asBoolean(), starred_at, user_rating?.toInt()),
        ),
        CacheRowState(fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone == 1L),
    )

    private fun Cache_album.toCached(credits: List<CacheCredit>) = CachedAlbum(
        CacheAlbumRecord(
            rawId = raw_id,
            title = title,
            credits = credits,
            year = year?.toInt(),
            genre = genre,
            durationMilliseconds = duration_milliseconds,
            songCount = song_count?.toInt(),
            artworkKey = artwork_key,
            userState = CacheUserState(starred.asBoolean(), starred_at, user_rating?.toInt(), play_count, played),
        ),
        CacheRowState(fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone == 1L),
        detailComplete = detail_complete == 1L,
    )

    private fun Cache_track.toCached(credits: List<CacheCredit>) = CachedTrack(
        record = title?.let {
            CacheTrackRecord(
                rawId = raw_id,
                albumRawId = album_raw_id,
                title = it,
                albumTitle = album_title,
                credits = credits,
                discNumber = disc_number?.toInt(),
                trackNumber = track_number?.toInt(),
                durationMilliseconds = duration_milliseconds,
                sourceContainer = source_container?.let(::audioContainerFromCacheWireName),
                artworkKey = artwork_key,
                userState = CacheUserState(starred.asBoolean(), starred_at, user_rating?.toInt(), play_count, played),
            )
        },
        rawId = raw_id,
        row = CacheRowState(fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone == 1L),
        metadataMissing = metadata_missing == 1L,
    )

    private fun Cache_playlist.toCached() = CachedPlaylist(
        CachePlaylistRecord(raw_id, name, song_count?.toInt(), duration_milliseconds, owner, artwork_key),
        CacheRowState(fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone == 1L),
        detailComplete = detail_complete == 1L,
    )
}

/** The list key under which a playlist's ordered entries are cached (duplicates kept, §18.6). */
internal fun playlistDetailListKey(playlistRawId: String): String =
    canonicalListKey("getPlaylist", mapOf("id" to playlistRawId))

private fun playlistRawIdOfDetailKey(listKey: String): String? {
    val prefix = "getPlaylist?id="
    if (!listKey.startsWith(prefix) || listKey.indexOf('&') >= 0) return null
    return decodeListKeyComponent(listKey.removePrefix(prefix))
}

/**
 * The list key of spec §16.9: the endpoint plus its sorted non-credential parameters, excluding
 * paging and protocol parameters. Components are percent-encoded so that an opaque id containing
 * `&` or `=` cannot make two different requests share a key.
 */
internal fun canonicalListKey(endpoint: String, parameters: Map<String, String>): String {
    val kept = parameters.entries
        .filter { it.key !in LIST_KEY_EXCLUDED_PARAMETERS }
        .sortedBy { it.key }
    if (kept.isEmpty()) return endpoint
    return endpoint + "?" + kept.joinToString("&") { entry ->
        encodeListKeyComponent(entry.key) + "=" + encodeListKeyComponent(entry.value)
    }
}

private val LIST_KEY_EXCLUDED_PARAMETERS = setOf("offset", "size", "count", "u", "t", "s", "p", "v", "c", "f")

private fun encodeListKeyComponent(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '%', '&', '=', '?' -> append('%').append(character.code.toString(16).uppercase().padStart(2, '0'))
            else -> append(character)
        }
    }
}

private fun decodeListKeyComponent(value: String): String =
    value.replace("%3F", "?").replace("%3D", "=").replace("%26", "&").replace("%25", "%")

internal fun encodeIdSet(ids: Set<String>): String = JsonArray(ids.sorted().map(::JsonPrimitive)).toString()

internal fun decodeIdSet(encoded: String): Set<String> =
    LIBRARY_JSON.parseToJsonElement(encoded).jsonArray.mapTo(mutableSetOf()) { it.jsonPrimitive.content }

private fun Boolean?.asFlag(): Long? = this?.let { if (it) 1L else 0L }

private fun Long?.asBoolean(): Boolean? = this?.let { it == 1L }

private fun CreditRole.cacheWireName(): String = when (this) {
    CreditRole.Artist -> "artist"
    CreditRole.AlbumArtist -> "album_artist"
}

private fun creditRoleFromCacheWireName(value: String): CreditRole = when (value) {
    "artist" -> CreditRole.Artist
    "album_artist" -> CreditRole.AlbumArtist
    else -> error("unknown credit role")
}

internal fun AudioContainer.cacheWireName(): String = when (this) {
    AudioContainer.Mp3 -> "mp3"
    AudioContainer.Mp4 -> "mp4"
    AudioContainer.Wav -> "wav"
    AudioContainer.Flac -> "flac"
    AudioContainer.Ogg -> "ogg"
    AudioContainer.AdtsAac -> "adts_aac"
}

private fun audioContainerFromCacheWireName(value: String): AudioContainer? =
    AudioContainer.entries.firstOrNull { it.cacheWireName() == value }

/**
 * Normalizes seen-cache rows that carry no normalized search text — rows seeded on upgrade from a
 * mirror that was never backfilled. Idempotent: it selects only rows with a NULL column.
 */
internal fun backfillSeenCacheNormalization(database: DulcetDatabase) {
    val queries = database.seenCacheQueries
    queries.selectArtistsMissingNormalization().executeAsList().forEach {
        queries.updateArtistNormalization(normalizeSearchText(it.name), it.server_id, it.raw_id)
    }
    queries.selectAlbumsMissingNormalization().executeAsList().forEach {
        queries.updateAlbumNormalization(normalizeSearchText(it.title), it.server_id, it.raw_id)
    }
    queries.selectTracksMissingNormalization().executeAsList().forEach {
        queries.updateTrackNormalization(
            normalizeSearchText(it.title.orEmpty()),
            normalizeSearchText(it.album_title.orEmpty()),
            it.server_id,
            it.raw_id,
        )
    }
    queries.selectCreditsMissingNormalization().executeAsList().forEach {
        queries.updateCreditNormalization(
            normalizeSearchText(it.name),
            it.server_id,
            it.owner_kind,
            it.owner_raw_id,
            it.role,
            it.ordinal,
        )
    }
}
