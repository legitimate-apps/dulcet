package com.legitimateapps.dulcet.core

/*
 * The reader's publications as the Android shell sees them (spec §16.14, §16.15, §16.18).
 *
 * The core's own publication types are internal, so the Android facade copies each one into the
 * public values below. Every product rule — freshness, coverage, playability, search scope and the
 * overlay of a pending favourite or rating — is computed in the core; these types only carry the
 * result. Every closed value is mapped by an exhaustive `when` with no `else` branch, so a value the
 * core adds later fails this file's compilation instead of reaching a shell as a guess.
 *
 * Nothing here can carry a URL, a query string or server error text: an error is a [DomainError],
 * which has no field that can hold one (CORPUS §4 line 5, CLAUDE.md trap 12). Titles and names are
 * the server's catalog text, which is what they are for.
 *
 * These are plain Kotlin values, consumed by the Android shell only. The shell turns them into a
 * `StateFlow` for Compose; no `Flow` appears in this public surface (§16.18).
 */

/** Which screen to open (§16.9). */
public sealed interface AndroidLibraryQuery {
    public data class AlbumList(
        val type: AndroidAlbumListType,
        val fromYear: Int? = null,
        val toYear: Int? = null,
        val genre: String? = null,
        val musicFolderId: String? = null,
    ) : AndroidLibraryQuery

    public data class Artists(val musicFolderId: String? = null) : AndroidLibraryQuery
    public data class Artist(val rawId: String) : AndroidLibraryQuery
    public data class Album(val rawId: String) : AndroidLibraryQuery
    public data object Playlists : AndroidLibraryQuery
    public data class Playlist(val rawId: String) : AndroidLibraryQuery
    public data object Starred : AndroidLibraryQuery
    public data object Genres : AndroidLibraryQuery
    public data class SongsByGenre(val genre: String, val musicFolderId: String? = null) : AndroidLibraryQuery
}

/** `getAlbumList2` types. Activity-ordered ones and `random` are single pages (§16.9). */
public enum class AndroidAlbumListType {
    AlphabeticalByName,
    AlphabeticalByArtist,
    Newest,
    ByYear,
    ByGenre,
    Recent,
    Frequent,
    Highest,
    Starred,
    Random,
}

/** One row of a home screen: one page of an album list, or the favourites (§16.9, CONF-86). */
public sealed interface AndroidLibraryHomeRow {
    public data class Albums(val type: AndroidAlbumListType) : AndroidLibraryHomeRow
    public data object Favourites : AndroidLibraryHomeRow
}

/** How current a publication is (§16.14). */
public sealed interface AndroidLibraryFreshness {
    /** Read from the server in this session under the current epoch. Presented with nothing extra. */
    public data object Live : AndroidLibraryFreshness

    /**
     * Served from what this device has seen. [asOfEpochMillis] is the wall clock of the live read the
     * content came from, or null when its age is unknown (rows seeded on upgrade, §16.17).
     */
    public data class Cached(val asOfEpochMillis: Long?, val reason: AndroidLibraryCachedReason) : AndroidLibraryFreshness

    /** Nothing cached and a live read in flight: the only state a loading indicator may show. */
    public data object Loading : AndroidLibraryFreshness

    /** Nothing cached and nothing can be read: a statement of fact, never a spinner. */
    public data class Unavailable(val reason: AndroidLibraryUnavailableReason) : AndroidLibraryFreshness
}

public sealed interface AndroidLibraryCachedReason {
    /** A live read is in flight. */
    public data object Revalidating : AndroidLibraryCachedReason

    /** The server is unreachable. */
    public data object Offline : AndroidLibraryCachedReason

    /** The live read failed with [error]. */
    public data class Failed(val error: DomainError) : AndroidLibraryCachedReason

    /** The catalog changed and no live read has landed yet. */
    public data object Stale : AndroidLibraryCachedReason

    /**
     * Read live, but a "load more" or "load before" this screen owes is still to be made, and did not
     * fail; the next revalidation makes it (§16.14). Never `live` while one is owed.
     */
    public data object Owed : AndroidLibraryCachedReason

    /** The reader itself failed while refreshing this screen: a defect on the device, not the server. */
    public data object InternalFailure : AndroidLibraryCachedReason
}

public sealed interface AndroidLibraryUnavailableReason {
    /** Never read on this device, and offline: "Connect to your server to see it." */
    public data object NotCachedOffline : AndroidLibraryUnavailableReason

    /** The server says it no longer exists (§16.11). */
    public data object Gone : AndroidLibraryUnavailableReason

    public data class Failed(val error: DomainError) : AndroidLibraryUnavailableReason

    /** The reader itself failed while building this screen. */
    public data object InternalFailure : AndroidLibraryUnavailableReason

    /** The reader was closed; nothing more will be published. */
    public data object Closed : AndroidLibraryUnavailableReason
}

/** Coverage of a list (§16.12), which the shell states (§16.14). */
public enum class AndroidLibraryCoverage { Complete, Open, UnverifiedScanning, UnverifiedNoEpoch, UnverifiedChanging }

/** For a detail screen: whether its child list is shown, still loading, or cannot be shown. */
public enum class AndroidLibraryItemsState { Present, Loading, Unavailable }

/** The server's order, or a list sorted on this device from what it has seen, while offline. */
public enum class AndroidLibraryItemsOrder { Server, LocalView }

/** A track's playability: a badge and a filter predicate (§16.14). Tracks only. */
public enum class AndroidLibraryPlayability { Downloaded, Streamable, UnavailableOffline }

/** One row, as flat values. [favourite] and [rating] already carry any pending local change. */
public sealed interface AndroidLibraryItem {
    public val rawId: String

    public data class Album(
        override val rawId: String,
        val title: String,
        val artistName: String?,
        val artistRawId: String?,
        val year: Int?,
        val genre: String?,
        val durationMilliseconds: Long?,
        val songCount: Int?,
        val artworkKey: String?,
        val favourite: Boolean?,
        val rating: Int?,
        val playCount: Long?,
        /** The album's track list has been read, so an empty one means no tracks (§16.11). */
        val detailComplete: Boolean,
    ) : AndroidLibraryItem

    public data class Artist(
        override val rawId: String,
        val name: String,
        val albumCount: Int?,
        val artworkKey: String?,
        val favourite: Boolean?,
        val rating: Int?,
    ) : AndroidLibraryItem

    public data class Track(
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
        val favourite: Boolean?,
        val rating: Int?,
        val playCount: Long?,
        val playability: AndroidLibraryPlayability,
        /** Known only by id (a playlist entry whose metadata was never read). */
        val metadataMissing: Boolean,
    ) : AndroidLibraryItem

    public data class Playlist(
        override val rawId: String,
        val name: String,
        val songCount: Int?,
        val durationMilliseconds: Long?,
        val owner: String?,
        val artworkKey: String?,
    ) : AndroidLibraryItem

    public data class Genre(override val rawId: String) : AndroidLibraryItem
}

/**
 * One publication of a window (§16.18). A window publishes many times — cached, live, after a
 * rebase, after a local change — and nothing about a publication means "the open finished".
 *
 * [sequence] is 1-based per window, in the order the facade emitted them. [leadingOffset] is the
 * server position of `items[0]`: above zero, rows precede the window that are not loaded, and the
 * shell calls [AndroidLibraryWindow.loadBefore] as the person scrolls up (§16.12).
 */
public data class AndroidLibraryPublication(
    val sequence: Int,
    val freshness: AndroidLibraryFreshness,
    /** Lists only; null for a detail screen unless its track list was read while the server scanned. */
    val coverage: AndroidLibraryCoverage?,
    val total: Int?,
    val leadingOffset: Int,
    /** A detail screen's entity; null for a list. */
    val header: AndroidLibraryItem?,
    val items: List<AndroidLibraryItem>,
    val itemsState: AndroidLibraryItemsState,
    val order: AndroidLibraryItemsOrder,
    /** After a rebase: the item that should stay first in the viewport, and where it now is. */
    val anchorRawId: String?,
    val anchorIndex: Int?,
    /**
     * Why [items] cannot be shown when [itemsState] is `unavailable`: never opened on this device and
     * offline, the read's failure, or the reader's own; for a screen unavailable as a whole, the same
     * reason as its [freshness]. Null otherwise. The core publishes it, because the header's freshness
     * cannot say it: a revalidation in flight is `cached(revalidating)` whatever the list's state.
     */
    val itemsUnavailableReason: AndroidLibraryUnavailableReason? = null,
)

/** The seen-cache's own counts, so "no match" can be told from "no match among what this device has seen". */
public data class AndroidLibrarySeenCounts(val artists: Long, val albums: Long, val tracks: Long)

/** The honest scope of a whole search result list (§16.15). */
public sealed interface AndroidLibrarySearchScope {
    /** The server search completed and this device's rows were merged into it. */
    public data object ServerAndDevice : AndroidLibrarySearchScope

    /**
     * Fewer than two characters, or the server's answer is pending: "On this device". Which of the
     * two is [AndroidLibrarySearchPublication.serverPending].
     */
    public data object DeviceWhileServerPending : AndroidLibrarySearchScope

    /** The server is unreachable: what this device has seen, with its counts. */
    public data class DeviceOffline(val seen: AndroidLibrarySeenCounts) : AndroidLibrarySearchScope

    /** The server search failed with [error]; this device's rows stand, with their counts. */
    public data class DeviceServerFailed(val error: DomainError, val seen: AndroidLibrarySeenCounts) : AndroidLibrarySearchScope

    /** The reader itself failed, or was closed; the rows already shown stand. Not a server state. */
    public data object ReaderFailed : AndroidLibrarySearchScope
}

/** Where a search row came from: the server's search for this query, or only this device. */
public enum class AndroidLibrarySearchRowSource { Server, Device }

/**
 * One search row. [favourite] and [rating] carry any pending local change. [playability] is set for
 * tracks only, by the windows' rule, so an offline search can dim what cannot play (§16.14).
 */
public data class AndroidLibrarySearchRow(
    val item: SearchResultItem,
    val source: AndroidLibrarySearchRowSource,
    val favourite: Boolean?,
    val rating: Int?,
    val playability: AndroidLibraryPlayability?,
)

public data class AndroidLibrarySearchPublication(
    /** The text as typed that these rows answer. */
    val query: String,
    val sequence: Int,
    val scope: AndroidLibrarySearchScope,
    val rows: List<AndroidLibrarySearchRow>,
    /**
     * The server's answer to [query] is still coming. False for a query too short to ask the server
     * (§18.1), which this device alone answers: the only case in which a search may show progress.
     */
    val serverPending: Boolean = false,
)

/** Something a person can favourite or rate. */
public enum class AndroidLibraryEntityKind { Artist, Album, Track }

public data class AndroidLibraryEntity(val kind: AndroidLibraryEntityKind, val rawId: String)

public enum class AndroidLibraryChangeField { Favourite, Rating }

/** How one favourite or rating change ended; every outcome except [Saved] needs words (§16.20). */
public sealed interface AndroidLibraryChangeOutcome {
    public val target: AndroidLibraryEntity
    public val field: AndroidLibraryChangeField

    public data class Saved(override val target: AndroidLibraryEntity, override val field: AndroidLibraryChangeField, val value: Int) :
        AndroidLibraryChangeOutcome

    /** The server refused it, or it failed repeatedly: the server's value shows again. */
    public data class NotSaved(override val target: AndroidLibraryEntity, override val field: AndroidLibraryChangeField, val error: DomainError) :
        AndroidLibraryChangeOutcome

    /**
     * Kept unsent, with every change after it: the server asked to wait ([error] is `Server.Busy`),
     * or this change's request was refused access and the `ping` sent to check the account failed
     * too, other than by a network failure ([error] is the ping's). A later flush sends it once the server accepts it;
     * the item shows the change meanwhile.
     */
    public data class Held(override val target: AndroidLibraryEntity, override val field: AndroidLibraryChangeField, val error: DomainError) :
        AndroidLibraryChangeOutcome

    /** It was changed elsewhere after this change was made; the server's value wins (§18.3). */
    public data class Superseded(override val target: AndroidLibraryEntity, override val field: AndroidLibraryChangeField, val serverValue: Int) :
        AndroidLibraryChangeOutcome

    /** The device could not record it at all; nothing is shown as changed. */
    public data class NotRecorded(override val target: AndroidLibraryEntity, override val field: AndroidLibraryChangeField) :
        AndroidLibraryChangeOutcome
}

/**
 * How one reconnect ended — what THIS call read, never an earlier reading.
 *
 * [epochRead] is true when the catalog epoch was read, which is the only way the reader is back
 * online (§16.14). Otherwise [error] names why (null with [internalFailure] when the device itself
 * failed, or with [closed] when the reader was closed first).
 */
public data class AndroidLibraryConnection(
    val epochRead: Boolean,
    val serverReportsNoEpoch: Boolean,
    /**
     * Changes discarded because the account's username changed (§16.10), until
     * [AndroidLibraryReader.acknowledgeDiscardedChanges]; zero after it.
     */
    val discardedPendingChanges: Long,
    val error: DomainError?,
    val internalFailure: Boolean,
    val closed: Boolean,
    /**
     * Whether the reader is online after this call. A failed reconnect leaves an offline reader
     * offline; a reader that was already online — a fresh one is — stays online and keeps reading.
     */
    val readerOnline: Boolean = false,
)

// ---- Mapping from the core --------------------------------------------------------------------------

internal fun AndroidAlbumListType.toCore(): AlbumListType = when (this) {
    AndroidAlbumListType.AlphabeticalByName -> AlbumListType.AlphabeticalByName
    AndroidAlbumListType.AlphabeticalByArtist -> AlbumListType.AlphabeticalByArtist
    AndroidAlbumListType.Newest -> AlbumListType.Newest
    AndroidAlbumListType.ByYear -> AlbumListType.ByYear
    AndroidAlbumListType.ByGenre -> AlbumListType.ByGenre
    AndroidAlbumListType.Recent -> AlbumListType.Recent
    AndroidAlbumListType.Frequent -> AlbumListType.Frequent
    AndroidAlbumListType.Highest -> AlbumListType.Highest
    AndroidAlbumListType.Starred -> AlbumListType.Starred
    AndroidAlbumListType.Random -> AlbumListType.Random
}

/** Throws [IllegalArgumentException] for a query that names nothing the core can open. */
internal fun AndroidLibraryQuery.toCore(): LibraryQuery = when (this) {
    is AndroidLibraryQuery.AlbumList -> LibraryQuery.AlbumList(
        type.toCore(),
        fromYear,
        toYear,
        genre?.takeIf { it.isNotBlank() },
        musicFolderId?.takeIf { it.isNotBlank() },
    )
    is AndroidLibraryQuery.Artists -> LibraryQuery.Artists(musicFolderId?.takeIf { it.isNotBlank() })
    is AndroidLibraryQuery.Artist -> LibraryQuery.Artist(rawId.nonBlankId())
    is AndroidLibraryQuery.Album -> LibraryQuery.Album(rawId.nonBlankId())
    AndroidLibraryQuery.Playlists -> LibraryQuery.Playlists
    is AndroidLibraryQuery.Playlist -> LibraryQuery.Playlist(rawId.nonBlankId())
    AndroidLibraryQuery.Starred -> LibraryQuery.Starred
    AndroidLibraryQuery.Genres -> LibraryQuery.Genres
    is AndroidLibraryQuery.SongsByGenre -> LibraryQuery.SongsByGenre(
        genre.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing genre"),
        musicFolderId?.takeIf { it.isNotBlank() },
    )
}

private fun String.nonBlankId(): String = takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing id")

internal fun AndroidLibraryHomeRow.toCore(): LibraryHomeRow = when (this) {
    is AndroidLibraryHomeRow.Albums -> {
        val type = type.toCore()
        // A home row carries no year range or genre, so the two list types that need one cannot be rows.
        require(type != AlbumListType.ByYear && type != AlbumListType.ByGenre) { "not a home row type" }
        LibraryHomeRow.Albums(type)
    }
    AndroidLibraryHomeRow.Favourites -> LibraryHomeRow.Favourites
}

internal fun LibraryPublication.toAndroid(sequence: Int): AndroidLibraryPublication = AndroidLibraryPublication(
    sequence = sequence,
    freshness = freshness.toAndroid(),
    coverage = coverage?.toAndroid(),
    total = total,
    leadingOffset = leadingOffset,
    header = header?.toAndroid(),
    items = items.map(LibraryItem::toAndroid),
    itemsState = when (itemsState) {
        LibraryItemsState.Present -> AndroidLibraryItemsState.Present
        LibraryItemsState.Loading -> AndroidLibraryItemsState.Loading
        LibraryItemsState.Unavailable -> AndroidLibraryItemsState.Unavailable
    },
    order = when (order) {
        LibraryItemsOrder.Server -> AndroidLibraryItemsOrder.Server
        LibraryItemsOrder.LocalView -> AndroidLibraryItemsOrder.LocalView
    },
    anchorRawId = anchor?.itemRawId,
    anchorIndex = anchor?.index,
    itemsUnavailableReason = if (itemsState == LibraryItemsState.Unavailable) {
        // The core's own reason, or a whole screen's; a list unavailable for no stated reason is the
        // reader's own failure, never "offline".
        (itemsUnavailableReason ?: (freshness as? LibraryFreshness.Unavailable)?.reason)?.toAndroid()
            ?: AndroidLibraryUnavailableReason.InternalFailure
    } else {
        null
    },
)

internal fun LibraryFreshness.toAndroid(): AndroidLibraryFreshness = when (this) {
    LibraryFreshness.Live -> AndroidLibraryFreshness.Live
    LibraryFreshness.Loading -> AndroidLibraryFreshness.Loading
    is LibraryFreshness.Cached -> AndroidLibraryFreshness.Cached(
        asOfWall,
        when (val cause = reason) {
            LibraryCachedReason.Revalidating -> AndroidLibraryCachedReason.Revalidating
            LibraryCachedReason.Offline -> AndroidLibraryCachedReason.Offline
            is LibraryCachedReason.Failed -> AndroidLibraryCachedReason.Failed(cause.error)
            LibraryCachedReason.Stale -> AndroidLibraryCachedReason.Stale
            LibraryCachedReason.Owed -> AndroidLibraryCachedReason.Owed
            LibraryCachedReason.InternalFailure -> AndroidLibraryCachedReason.InternalFailure
        },
    )
    is LibraryFreshness.Unavailable -> AndroidLibraryFreshness.Unavailable(reason.toAndroid())
}

internal fun LibraryUnavailableReason.toAndroid(): AndroidLibraryUnavailableReason = when (this) {
    LibraryUnavailableReason.NotCachedOffline -> AndroidLibraryUnavailableReason.NotCachedOffline
    LibraryUnavailableReason.Gone -> AndroidLibraryUnavailableReason.Gone
    is LibraryUnavailableReason.Failed -> AndroidLibraryUnavailableReason.Failed(error)
    LibraryUnavailableReason.InternalFailure -> AndroidLibraryUnavailableReason.InternalFailure
}

internal fun LibraryCoverage.toAndroid(): AndroidLibraryCoverage = when (this) {
    LibraryCoverage.Complete -> AndroidLibraryCoverage.Complete
    LibraryCoverage.Open -> AndroidLibraryCoverage.Open
    LibraryCoverage.UnverifiedScanning -> AndroidLibraryCoverage.UnverifiedScanning
    LibraryCoverage.UnverifiedNoEpoch -> AndroidLibraryCoverage.UnverifiedNoEpoch
    LibraryCoverage.UnverifiedChanging -> AndroidLibraryCoverage.UnverifiedChanging
}

internal fun LibraryPlayability.toAndroid(): AndroidLibraryPlayability = when (this) {
    LibraryPlayability.Downloaded -> AndroidLibraryPlayability.Downloaded
    LibraryPlayability.Streamable -> AndroidLibraryPlayability.Streamable
    LibraryPlayability.UnavailableOffline -> AndroidLibraryPlayability.UnavailableOffline
}

internal fun LibraryItem.toAndroid(): AndroidLibraryItem = when (this) {
    is LibraryItem.Album -> AndroidLibraryItem.Album(
        rawId, title, artistName, artistRawId, year, genre, durationMilliseconds, songCount, artworkKey,
        favourite = starred, rating = userRating, playCount = playCount, detailComplete = detailComplete,
    )
    is LibraryItem.Artist -> AndroidLibraryItem.Artist(rawId, name, albumCount, artworkKey, favourite = starred, rating = userRating)
    is LibraryItem.Track -> AndroidLibraryItem.Track(
        rawId, title, albumRawId, albumTitle, artistName, artistRawId, discNumber, trackNumber, durationMilliseconds,
        sourceContainer, artworkKey, favourite = starred, rating = userRating, playCount = playCount,
        playability = playability.toAndroid(), metadataMissing = metadataMissing,
    )
    is LibraryItem.Playlist -> AndroidLibraryItem.Playlist(rawId, name, songCount, durationMilliseconds, owner, artworkKey)
    is LibraryItem.Genre -> AndroidLibraryItem.Genre(rawId)
}

internal fun SeenCacheCounts.toAndroid(): AndroidLibrarySeenCounts = AndroidLibrarySeenCounts(artists, albums, tracks)

internal fun LibrarySearchPublication.toAndroid(
    sequence: Int,
    minimumServerQueryLength: Int = LibrarySearchConfig().minimumServerQueryLength,
): AndroidLibrarySearchPublication = AndroidLibrarySearchPublication(
    // The same test the search itself applies before asking the server (Search.kt `query`).
    serverPending = scope == SearchScope.DeviceWhileServerPending &&
        query.trim().let { normalizeSearchText(it).isNotEmpty() && it.length >= minimumServerQueryLength },
    query = query,
    sequence = sequence,
    scope = when (val current = scope) {
        SearchScope.ServerAndDevice -> AndroidLibrarySearchScope.ServerAndDevice
        SearchScope.DeviceWhileServerPending -> AndroidLibrarySearchScope.DeviceWhileServerPending
        is SearchScope.DeviceOffline -> AndroidLibrarySearchScope.DeviceOffline(current.seen.toAndroid())
        is SearchScope.DeviceServerFailed -> AndroidLibrarySearchScope.DeviceServerFailed(current.error, current.seen.toAndroid())
    },
    rows = rows.map { row ->
        AndroidLibrarySearchRow(
            item = row.item,
            source = when (row.source) {
                SearchResultSource.Server -> AndroidLibrarySearchRowSource.Server
                SearchResultSource.Device -> AndroidLibrarySearchRowSource.Device
            },
            favourite = row.favourite,
            rating = row.rating,
            playability = row.playability?.toAndroid(),
        )
    },
)

internal fun AndroidLibraryEntityKind.toCore(): LibraryEntityKind = when (this) {
    AndroidLibraryEntityKind.Artist -> LibraryEntityKind.Artist
    AndroidLibraryEntityKind.Album -> LibraryEntityKind.Album
    AndroidLibraryEntityKind.Track -> LibraryEntityKind.Track
}

internal fun LibraryEntityRef.toAndroid(): AndroidLibraryEntity = AndroidLibraryEntity(
    when (kind) {
        LibraryEntityKind.Artist -> AndroidLibraryEntityKind.Artist
        LibraryEntityKind.Album -> AndroidLibraryEntityKind.Album
        LibraryEntityKind.Track -> AndroidLibraryEntityKind.Track
    },
    rawId,
)

internal fun MutationField.toAndroid(): AndroidLibraryChangeField = when (this) {
    MutationField.Starred -> AndroidLibraryChangeField.Favourite
    MutationField.Rating -> AndroidLibraryChangeField.Rating
}

internal fun MutationOutcome.toAndroid(): AndroidLibraryChangeOutcome = when (this) {
    is MutationOutcome.Saved -> AndroidLibraryChangeOutcome.Saved(target.toAndroid(), field.toAndroid(), value)
    is MutationOutcome.NotSaved -> AndroidLibraryChangeOutcome.NotSaved(target.toAndroid(), field.toAndroid(), error)
    is MutationOutcome.Held -> AndroidLibraryChangeOutcome.Held(target.toAndroid(), field.toAndroid(), error)
    is MutationOutcome.Superseded -> AndroidLibraryChangeOutcome.Superseded(target.toAndroid(), field.toAndroid(), serverValue)
    is MutationOutcome.NotRecorded -> AndroidLibraryChangeOutcome.NotRecorded(target.toAndroid(), field.toAndroid())
}
