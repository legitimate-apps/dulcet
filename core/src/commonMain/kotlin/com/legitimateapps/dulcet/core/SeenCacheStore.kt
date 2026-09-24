package com.legitimateapps.dulcet.core

import com.legitimateapps.dulcet.database.Cache_album
import com.legitimateapps.dulcet.database.Cache_artist
import com.legitimateapps.dulcet.database.Cache_list
import com.legitimateapps.dulcet.database.Cache_playlist
import com.legitimateapps.dulcet.database.Cache_track
import com.legitimateapps.dulcet.database.DulcetDatabase
import com.legitimateapps.dulcet.database.SelectListAlbumRows
import com.legitimateapps.dulcet.database.SelectListArtistRows
import com.legitimateapps.dulcet.database.SelectListPlaylistRows
import com.legitimateapps.dulcet.database.SelectListTrackRows
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
     * Row counts per namespace, kept in memory so the eviction check after a write is O(1) instead
     * of four `count(*)` scans (spec §16.13: eviction runs after a write that CROSSES a ceiling).
     * Counted exactly once per bind and whenever an eviction pass runs; maintained by the inserts
     * and deletes this store performs. A writer outside the store can only make them stale high,
     * which costs one pass that recounts and finds nothing to do.
     */
    private val counts = mutableMapOf<String, CacheRowCounts>()

    /**
     * Returns the namespace for [binding], purging it first if it was filled from a different
     * server URL or username. A namespace with no binding yet adopts [binding] and keeps its rows:
     * those can only have been seeded on upgrade from this same provider instance's mirror
     * (spec §16.17), which carried no binding of its own.
     */
    fun bind(binding: CacheBinding): BoundSeenCache {
        var discarded = 0L
        val purged = database.transactionWithResult {
            val queries = database.seenCacheQueries
            val stored = queries.selectBinding(binding.serverId).executeAsOneOrNull()
            val matches = stored != null &&
                stored.normalized_base_url == binding.normalizedBaseUrl &&
                stored.username == binding.username
            if (stored != null && stored.username != binding.username) {
                // Changes queued as another user must never be sent as this one (§16.10, §14.7).
                // A server-address change for the same username keeps them.
                val outbox = database.protectedReservedDataQueries
                discarded = outbox.countMutationsForServer(binding.serverId).executeAsOne()
                outbox.deleteMutationsForServer(binding.serverId)
            }
            if (stored != null && !matches) {
                queries.purgeNamespace(binding.serverId)
                check(queries.countNamespaceRows(binding.serverId).executeAsOne().sum == 0L) {
                    "binding purge left rows behind"
                }
            }
            if (stored != null && !matches) counts.remove(binding.serverId)
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
        return BoundSeenCache(binding.serverId, database, clock, ceilings, purgedOnBind = purged, discardedPendingChanges = discarded) {
            counts.getOrPut(binding.serverId) { CacheRowCounts.measure(database, binding.serverId) }
        }
    }
}

/** Row counts of one namespace (see [SeenCacheStore]). */
internal class CacheRowCounts(var albums: Long, var tracks: Long, var artists: Long, var lists: Long) {
    fun measureFrom(other: CacheRowCounts) {
        albums = other.albums
        tracks = other.tracks
        artists = other.artists
        lists = other.lists
    }

    companion object {
        fun measure(database: DulcetDatabase, serverId: String): CacheRowCounts {
            val q = database.seenCacheQueries
            return CacheRowCounts(
                q.countAlbums(serverId).executeAsOne(),
                q.countTracks(serverId).executeAsOne(),
                q.countArtists(serverId).executeAsOne(),
                q.countLists(serverId).executeAsOne(),
            )
        }
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
    UnverifiedNoEpoch("unverified_no_epoch"),

    /** The stamp kept moving across every bounded re-read with no scan reported (§16.12). */
    UnverifiedChanging("unverified_changing");

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
    val comment: String? = null,
    /** Navidrome omits `public` when it is false; null is "not stated". */
    val isPublic: Boolean? = null,
    /**
     * The server's statement that this user may not edit the playlist (OpenSubsonic `readonly`).
     * Null when the server did not say — never read as "editable" (spec §18.6).
     */
    val readonly: Boolean? = null,
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
    /**
     * The epoch the album's MEMBERSHIP was read under — never its summary's, which a later list
     * page refreshes without re-reading the tracks. Null when unknown (seeded) or when the detail
     * was read while the server was scanning: either way, not current.
     */
    val detailFetchedEpoch: String? = null,
)

internal data class CachedTrack(
    val record: CacheTrackRecord?,
    val rawId: String,
    val row: CacheRowState,
    val metadataMissing: Boolean,
    /** Per-user state, carried even for an identity-only row, whose record is null (§16.20 rule 4). */
    val userState: CacheUserState = record?.userState ?: CacheUserState(),
)

internal data class CachedPlaylist(
    val record: CachePlaylistRecord,
    val row: CacheRowState,
    val detailComplete: Boolean,
)

/** One window member with its entity, as [BoundSeenCache.listRows] returns it. */
internal data class CachedListRow(
    val position: Int,
    val kind: CacheItemKind,
    val album: CachedAlbum? = null,
    val artist: CachedArtist? = null,
    val track: CachedTrack? = null,
    val playlist: CachedPlaylist? = null,
    val genre: String? = null,
) {
    init {
        require(listOfNotNull(album, artist, track, playlist, genre).size == 1) { "a row carries exactly one entity" }
    }

    val rawId: String
        get() = album?.record?.rawId ?: artist?.record?.rawId ?: track?.rawId ?: playlist?.record?.rawId
            ?: checkNotNull(genre)
}

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
    /** Queued favourite and rating changes this bind discarded because the username changed. */
    val discardedPendingChanges: Long = 0,
    private val rowCounts: () -> CacheRowCounts,
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
            rowCounts().artists += 1
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
            rowCounts().albums += 1
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
            rowCounts().tracks += 1
        } else {
            if (existing >= stamp.issueSeq) return false
            queries.updateTrack(
                album_raw_id = track.albumRawId,
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
                playlist.comment, playlist.isPublic?.toLong(), playlist.readonly?.toLong(),
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
            comment = playlist.comment,
            is_public = playlist.isPublic?.toLong(),
            readonly = playlist.readonly?.toLong(),
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
     *
     * Two orders apply, deliberately separately (§16.10): each track's SUMMARY fields are rewritten
     * only when [stamp] outranks that row's `issue_seq`, while the album's MEMBERSHIP — which rows
     * are its tracks and in what order — is rewritten in full, in one transaction, whenever [stamp]
     * outranks the album's previous DETAIL read, however recently a search or list touched a row.
     * [detailEpoch] is the epoch the membership was read under, or null while the server scans.
     */
    fun writeAlbumDetail(
        stamp: CacheWriteStamp,
        album: CacheAlbumRecord,
        tracks: List<CacheTrackRecord>,
        detailEpoch: String? = stamp.fetchedEpoch,
    ): AlbumDetailWrite = database.transactionWithResult {
        upsertAlbumSummary(stamp, CacheEntitySource.Detail, album)
        val sequences = queries.selectAlbumSequences(serverId, album.rawId).executeAsOne()
        if (sequences.detail_issue_seq >= stamp.issueSeq) {
            return@transactionWithResult AlbumDetailWrite(albumGone = false, tracksMarkedGone = emptyList(), membershipApplied = false)
        }
        tracks.forEachIndexed { ordinal, track ->
            upsertTrack(stamp, CacheEntitySource.Detail, track.copy(albumRawId = album.rawId), albumOrdinal = null)
            queries.updateTrackMembership(album.rawId, ordinal.toLong(), serverId, track.rawId)
        }
        val present = tracks.mapTo(mutableSetOf()) { it.rawId }
        val absent = queries.selectAlbumTrackIds(serverId, album.rawId).executeAsList().filter { it !in present }
        absent.forEach { rawId -> queries.markTrackGone(issue_seq = stamp.issueSeq, server_id = serverId, raw_id = rawId) }
        val markedGone = absent.filter { queries.selectTrack(serverId, it).executeAsOneOrNull()?.gone == 1L }
        val albumGone = tracks.isEmpty()
        if (albumGone) {
            queries.markAlbumGoneByDetail(stamp.issueSeq, serverId, album.rawId)
        } else {
            queries.markAlbumDetail(stamp.issueSeq, detailEpoch, serverId, album.rawId)
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
        if (queries.deleteList(serverId, playlistDetailListKey(playlistRawId)).value > 0) rowCounts().lists -= 1
    }

    fun markPlaylistDetail(issueSeq: Long, playlistRawId: String) {
        queries.markPlaylistDetail(issueSeq, serverId, playlistRawId)
    }

    // ---- Lists and windows ----------------------------------------------------------------------

    /**
     * Writes one page of a window and its entities in one transaction (spec §16.10: a cached window
     * never references an entity that is not cached). [members] replace positions
     * `[pageStart, maxOf(replacedEnd, pageStart + members.size))`; when [keepRange] is given, every
     * member outside it is dropped in the same transaction (a rebase, §16.12) — an empty [keepRange]
     * keeps nothing. And when the state carries the server's total, every member at or beyond it is
     * dropped: a page that came back short or empty names positions the server no longer has, and
     * the replaced range alone can be empty (a page of no rows at its own start replaces nothing).
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
        state.total?.let { total ->
            queries.deleteListMembersInRange(serverId, state.listKey, maxOf(total, 0).toLong(), Long.MAX_VALUE)
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
        val inserted = queries.insertListIfAbsent(
            serverId, state.listKey, state.windowEpoch, folders,
            state.firstLoadedOffset.toLong(), state.endLoadedOffset.toLong(), state.total?.toLong(),
            state.coverage.wireName, state.fetchedAtWall, state.lastAccessWall, state.issueSeq,
        ).value
        if (inserted > 0) rowCounts().lists += 1
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
        if (queries.deleteList(serverId, listKey).value > 0) rowCounts().lists -= 1
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
        queries.selectAlbumTracks(serverId, albumRawId, ::Cache_track).executeAsList().map { it.toCached(credits("track", it.raw_id)) }

    /** Every cached album, for the local "Available offline" view (§16.14), sorted locally. */
    fun localAlbums(): List<CachedAlbum> =
        queries.selectLocalAlbums(serverId).executeAsList().map { it.toCached(credits("album", it.raw_id)) }

    fun localArtists(): List<CachedArtist> =
        queries.selectLocalArtists(serverId).executeAsList().map { it.toCached() }

    /**
     * A whole window's members with their entities, in server order, in one statement per item
     * kind — a publication's cost must not grow by statements per item (review finding S7). Gone
     * entities are excluded. Credits are the row's primary artist, which is all a list row shows.
     */
    fun listRows(listKey: String): List<CachedListRow> {
        val rows = mutableListOf<CachedListRow>()
        queries.selectListAlbumRows(serverId, listKey).executeAsList().forEach {
            val table = it.asTable()
            rows += CachedListRow(it.position.toInt(), CacheItemKind.Album, album = table.toCached(primaryCredit(CreditRole.AlbumArtist, table.artist_name, table.artist_raw_id)))
        }
        queries.selectListArtistRows(serverId, listKey).executeAsList().forEach {
            rows += CachedListRow(it.position.toInt(), CacheItemKind.Artist, artist = it.asTable().toCached())
        }
        queries.selectListTrackRows(serverId, listKey).executeAsList().forEach {
            val table = it.asTable()
            rows += CachedListRow(it.position.toInt(), CacheItemKind.Track, track = table.toCached(primaryCredit(CreditRole.Artist, table.artist_name, table.artist_raw_id)))
        }
        queries.selectListPlaylistRows(serverId, listKey).executeAsList().forEach {
            rows += CachedListRow(it.position.toInt(), CacheItemKind.Playlist, playlist = it.asTable().toCached())
        }
        queries.selectListGenreRows(serverId, listKey).executeAsList().forEach {
            rows += CachedListRow(it.position.toInt(), CacheItemKind.Genre, genre = it.raw_id)
        }
        rows.sortBy { it.position }
        return rows
    }

    private fun primaryCredit(role: CreditRole, name: String?, artistRawId: String?): List<CacheCredit> =
        listOfNotNull(name?.let { CacheCredit(role, it, artistRawId) })

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
        if (kind == CacheItemKind.Track && queries.insertIdentityOnlyTrack(serverId, rawId, now()).value > 0) {
            rowCounts().tracks += 1
        }
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
     * Called after writes; cheap unless a ceiling has actually been crossed, because it compares
     * the in-memory counts first (spec §16.13). A pass then recounts exactly and evicts in one
     * transaction, in a BATCH down to [SeenCacheCeilings] minus 1%, so the next write does not
     * trigger another pass.
     *
     * Order within a pass: windows over the window ceiling go first, least-recently-accessed first.
     * Then, per entity kind over its ceiling, orphans of that kind go least-recently-accessed first.
     * When no orphan remains: under TRACK pressure the least-recently-accessed complete album detail
     * is released (the album keeps its summary; its tracks stop being members and become orphans);
     * under album or artist pressure — or when no detail is left to release — a further whole window
     * is released. An entity is an orphan when it is unpinned and no window or complete detail
     * references it; the candidate queries encode that, so no ceiling can select a pinned row.
     */
    fun evictIfNeeded(): EvictionReport {
        val known = rowCounts()
        if (known.lists <= ceilings.lists && known.albums <= ceilings.albums &&
            known.tracks <= ceilings.tracks && known.artists <= ceilings.artists
        ) {
            return EvictionReport(emptyList(), emptyList(), emptyList(), emptyList())
        }
        return database.transactionWithResult { evictInTransaction(known) }
    }

    private fun evictInTransaction(known: CacheRowCounts): EvictionReport {
        known.measureFrom(CacheRowCounts.measure(database, serverId))
        val lists = mutableListOf<String>()
        val albums = mutableListOf<String>()
        val tracks = mutableListOf<String>()
        val artists = mutableListOf<String>()

        fun target(ceiling: Long): Long = ceiling - maxOf(1L, ceiling / 100)

        fun releaseOneList(): Boolean {
            val key = queries.selectLeastRecentlyAccessedList(serverId).executeAsOneOrNull() ?: return false
            deleteListInTransaction(key)
            lists += key
            return true
        }

        fun releaseOneDetail(): Boolean {
            val album = queries.selectReleasableDetails(serverId, 1).executeAsOneOrNull() ?: return false
            queries.releaseDetail(serverId, album)
            return true
        }

        if (known.lists > ceilings.lists) {
            while (known.lists > target(ceilings.lists) && releaseOneList()) Unit
        }

        fun reduce(
            count: () -> Long,
            ceiling: Long,
            candidates: (Long) -> List<String>,
            delete: (String) -> Unit,
            release: () -> Boolean,
        ) {
            if (count() <= ceiling) return
            while (count() > target(ceiling)) {
                val evictable = candidates(count() - target(ceiling))
                if (evictable.isNotEmpty()) evictable.forEach(delete) else if (!release()) return
            }
        }

        reduce(
            count = { known.albums },
            ceiling = ceilings.albums,
            candidates = { queries.selectEvictableAlbums(serverId, it).executeAsList() },
            delete = { rawId ->
                queries.deleteCredits(serverId, "album", rawId)
                queries.deleteAlbum(serverId, rawId)
                known.albums -= 1
                albums += rawId
            },
            release = ::releaseOneList,
        )
        reduce(
            count = { known.tracks },
            ceiling = ceilings.tracks,
            candidates = { queries.selectEvictableTracks(serverId, it).executeAsList() },
            delete = { rawId ->
                queries.deleteCredits(serverId, "track", rawId)
                queries.deleteTrack(serverId, rawId)
                known.tracks -= 1
                tracks += rawId
            },
            release = { releaseOneDetail() || releaseOneList() },
        )
        reduce(
            count = { known.artists },
            ceiling = ceilings.artists,
            candidates = { queries.selectEvictableArtists(serverId, it).executeAsList() },
            delete = { rawId ->
                queries.deleteArtist(serverId, rawId)
                known.artists -= 1
                artists += rawId
            },
            release = ::releaseOneList,
        )
        return EvictionReport(lists, albums, tracks, artists)
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
        detailFetchedEpoch = detail_fetched_epoch,
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
        userState = CacheUserState(starred.asBoolean(), starred_at, user_rating?.toInt(), play_count, played),
    )

    private fun Cache_playlist.toCached() = CachedPlaylist(
        CachePlaylistRecord(
            raw_id, name, song_count?.toInt(), duration_milliseconds, owner, artwork_key,
            comment, is_public?.let { it == 1L }, readonly?.let { it == 1L },
        ),
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

private fun SelectListAlbumRows.asTable() = Cache_album(server_id, raw_id, title, normalized_title, artist_name, artist_raw_id, year, genre, duration_milliseconds, song_count, artwork_key, starred, starred_at, user_rating, play_count, played, detail_complete, detail_issue_seq, detail_fetched_epoch, fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone)
private fun SelectListArtistRows.asTable() = Cache_artist(server_id, raw_id, name, normalized_name, album_count, artwork_key, starred, starred_at, user_rating, fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone)
private fun SelectListTrackRows.asTable() = Cache_track(server_id, raw_id, album_raw_id, album_ordinal, title, normalized_title, album_title, normalized_album_title, artist_name, artist_raw_id, disc_number, track_number, duration_milliseconds, source_container, artwork_key, starred, starred_at, user_rating, play_count, played, fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone, metadata_missing)
private fun SelectListPlaylistRows.asTable() = Cache_playlist(server_id, raw_id, name, song_count, duration_milliseconds, owner, artwork_key, detail_complete, detail_issue_seq, fetched_at_wall, fetched_epoch, issue_seq, last_access_wall, gone, comment, is_public, readonly)

private fun Boolean.toLong(): Long = if (this) 1L else 0L
