package com.legitimateapps.dulcet.core

/*
 * The reader's publications as they cross the Objective-C boundary (spec §7.1, §16.18).
 *
 * Every class here is final and immutable and holds only primitives, `String`s, nullable boxed
 * numbers and `List`s of classes from this file. Every closed kind is a `String` from a fixed
 * vocabulary, mapped from a core value by an exhaustive `when` with no `else` branch: a value the
 * core adds later fails this file's compilation instead of crossing as a guess. No core entity,
 * no sealed hierarchy, no `Flow` and no SQLDelight type crosses, and no field can hold a URL or
 * server error text — errors cross as a closed kind only (CORPUS §4 line 5, trap 12). Titles and
 * names are the server's catalog text, which is what they are for.
 *
 * `DulcetKit` copies each of these into a hand-written Swift struct (§7.1); the Swift half is a
 * separate phase.
 */

/**
 * How current a publication is (§16.14).
 *
 * - [kind]: `live`, `cached`, `loading` or `unavailable`.
 * - [reason]: for `cached` — `revalidating`, `offline`, `failed`, `stale` or `internalFailure`;
 *   for `unavailable` — `notCachedOffline`, `gone`, `failed` or `internalFailure`; otherwise null.
 * - [errorKind]: set only when [reason] is `failed` (see [readerErrorKind] for the vocabulary).
 * - [asOfEpochMillis]: for `cached`, the wall clock of the live read the content came from, or
 *   null when its age is unknown (rows seeded on upgrade, §16.17).
 */
public class AppleLibraryReaderFreshness internal constructor(
    public val kind: String,
    public val reason: String?,
    public val errorKind: String?,
    public val asOfEpochMillis: Long?,
)

/** A credit on a search row: [role] is `artist` or `albumArtist`; [rawId] is null when unlinked. */
public class AppleLibraryReaderCredit internal constructor(
    public val role: String,
    public val name: String,
    public val rawId: String?,
)

/**
 * One row of a window, or a detail screen's header, as flat values.
 *
 * [kind] is `album`, `artist`, `track`, `playlist` or `genre`; the fields that do not apply to a
 * kind are null. [favourite] and [rating] already carry any pending local change (§16.20).
 * [playability] is `downloaded`, `streamable` or `unavailableOffline` for a track and null for
 * every other kind, because the core computes playability per track only. [sourceContainer] is
 * the track's file container as the server reported it: `Mp3`, `Mp4`, `Wav`, `Flac`, `Ogg` or
 * `AdtsAac` (the same words the other facades use), or null when unknown.
 */
public class AppleLibraryReaderItem internal constructor(
    public val kind: String,
    public val providerInstanceId: String,
    public val rawId: String,
    /** An album's or track's title, an artist's or playlist's name, a genre's name. */
    public val title: String?,
    public val artistName: String?,
    public val artistRawId: String?,
    public val albumTitle: String?,
    public val albumRawId: String?,
    public val year: Int?,
    public val genre: String?,
    public val durationMilliseconds: Long?,
    public val songCount: Int?,
    public val albumCount: Int?,
    public val discNumber: Int?,
    public val trackNumber: Int?,
    public val sourceContainer: String?,
    public val artworkKey: String?,
    public val owner: String?,
    public val favourite: Boolean?,
    public val rating: Int?,
    public val playCount: Long?,
    public val playability: String?,
    /** An album's track list has been read, so an empty one means no tracks (§16.11). */
    public val detailComplete: Boolean,
    /** A track known only by id (a playlist entry whose metadata was never read). */
    public val metadataMissing: Boolean,
)

/**
 * One publication of a window (§16.18). A window publishes many times — cached, live, after a
 * rebase, after a local change — and nothing about a publication means "the open finished".
 *
 * - [sequence]: 1-based per subscription, in delivery order.
 * - [coverage]: lists only — `complete`, `open`, `unverifiedScanning`, `unverifiedNoEpoch` or
 *   `unverifiedChanging`; for a detail screen null, or `unverifiedScanning` when its track list was
 *   read while the server scanned.
 * - [total]: the server's total when it reported one.
 * - [leadingOffset]: the server position of `items[0]`. Above zero, rows precede the window that
 *   are not loaded: show a gap and call `loadBefore()` as the person scrolls up (§16.12).
 * - [itemsState]: `present`, `loading` or `unavailable` — for a detail screen, whether its child
 *   list is shown, still loading, or cannot be shown.
 * - [order]: `server`, or `localView` for an offline list sorted on the device (§16.14).
 * - [anchorRawId]/[anchorIndex]: after a rebase, where the viewport should be so the first visible
 *   item stays first; both null otherwise, and [anchorRawId] alone may be null.
 */
public class AppleLibraryWindowPublication internal constructor(
    public val sequence: Int,
    public val freshness: AppleLibraryReaderFreshness,
    public val coverage: String?,
    public val total: Int?,
    public val leadingOffset: Int,
    public val header: AppleLibraryReaderItem?,
    public val items: List<AppleLibraryReaderItem>,
    public val itemsState: String,
    public val order: String,
    public val anchorRawId: String?,
    public val anchorIndex: Int?,
)

/**
 * One search result row (§16.15). [kind] is `artist`, `album` or `track`; [source] is `server`
 * (the server's search returned it for this query) or `device` (found only in what this device has
 * seen). [favourite] and [rating] carry any pending local change. [sourceContainer] uses the
 * window rows' vocabulary. [playability] is set for a track only — `downloaded`, `streamable` or
 * `unavailableOffline`, by the windows' rule — so an offline search can dim and badge what cannot
 * play (§16.14); null for an album or an artist.
 */
public class AppleLibrarySearchRow internal constructor(
    public val kind: String,
    public val providerInstanceId: String,
    public val rawId: String,
    public val title: String,
    public val credits: List<AppleLibraryReaderCredit>,
    public val albumTitle: String?,
    public val year: Int?,
    public val durationMilliseconds: Long?,
    public val discNumber: Int?,
    public val trackNumber: Int?,
    public val sourceContainer: String?,
    public val mediaSourceId: String?,
    public val artworkKey: String?,
    public val source: String,
    public val favourite: Boolean?,
    public val rating: Int?,
    public val playability: String?,
)

/**
 * One search publication (§16.15).
 *
 * - [scope]: `serverAndDevice`, `deviceWhileServerPending`, `deviceOffline` or
 *   `deviceServerFailed`.
 * - [errorKind]: set for `deviceServerFailed` only.
 * - The seen counts are this device's own, set for `deviceOffline` and for a `deviceServerFailed`
 *   the core reports (so "no match" can be told from "no match among what this device has seen"),
 *   and null otherwise — including a `deviceServerFailed` whose [errorKind] is `internalFailure` or
 *   `closed`, which the facade reports when it could not ask the core at all.
 */
public class AppleLibrarySearchPublication internal constructor(
    public val query: String,
    public val sequence: Int,
    public val scope: String,
    public val errorKind: String?,
    public val seenArtistCount: Long?,
    public val seenAlbumCount: Long?,
    public val seenTrackCount: Long?,
    public val rows: List<AppleLibrarySearchRow>,
)

/**
 * How one favourite or rating change ended (§16.20, §18.3).
 *
 * - [kind]: `saved` (the server acknowledged it), `notSaved` (refused, or failed too often — the
 *   server's value shows again and the person is told), `held` (kept unsent, with every change after
 *   it: the account was refused access, or the server asked to wait; a later flush sends it, and the
 *   person can withdraw it), `superseded` (changed elsewhere after this device last saw it; the
 *   server's value wins) or `notRecorded` (the device could not record it).
 * - [targetKind]: `artist`, `album` or `track`. [field]: `favourite` or `rating`.
 * - [value]: for `saved`, the value now on the server — `1`/`0` for a favourite, `0...5` for a
 *   rating (`0` removes it). [serverValue]: for `superseded`, the server's value.
 * - [errorKind]: for `notSaved`, and for `held` why it is held — `invalidCredentials` or
 *   `authentication`, `server` (a proxy refusing access), or `serverBusy`.
 */
public class AppleLibraryFavouriteOutcome internal constructor(
    public val kind: String,
    public val targetKind: String,
    public val rawId: String,
    public val field: String,
    public val value: Int?,
    public val serverValue: Int?,
    public val errorKind: String?,
)

/**
 * The account-level result of `connect` or `reconnect`.
 *
 * - [epochKnown]: THIS call read the catalog epoch. After `reconnect`, the reader is online and has
 *   revalidated the visible screen. False when the reading failed — nothing after the reading
 *   ran, so a reader that was offline is still offline and no screen was revalidated, though the
 *   outbox flush before it may already have sent changes. False also with `internalFailure`, when
 *   the reader itself failed: a reader that was offline is offline again — if the failure came
 *   after it was back online, every screen was republished saying so — and one that was already
 *   online stays online. `connect` while the reader is offline issues no request and is false with
 *   `unreachable`.
 * - [serverReportsNoEpoch]: the reading had no scan stamp at all; the shell states this once for
 *   the account, not per list (§16.14). False when [epochKnown] is false.
 * - [discardedPendingChanges]: changes queued as a different username that this session's
 *   binding discarded (§16.10, §14.7); the shell tells the person once.
 * - [errorKind]: null when the epoch was read. Otherwise why not: a server error kind (see
 *   [readerErrorKind] — `unreachable` also when the platform reported the server unreachable while
 *   the reconnect ran), or `cancelled`, `closed` or `internalFailure`.
 */
public class AppleLibraryReaderConnection internal constructor(
    public val epochKnown: Boolean,
    public val serverReportsNoEpoch: Boolean,
    public val discardedPendingChanges: Long,
    public val errorKind: String?,
)

/**
 * Changes made on this device that have not reached the server, for the sign-out offer (§14.7).
 * [count] is null when it could not be read — never a guessed zero, because a zero tells the
 * person nothing will be lost. [errorKind] is then `cancelled`, `closed` or `internalFailure`.
 */
public class AppleLibraryPendingChanges internal constructor(
    public val count: Long?,
    public val errorKind: String?,
)

// ---- Mapping (reader thread) ------------------------------------------------------------------------

internal fun LibraryFreshness.toApple(): AppleLibraryReaderFreshness = when (this) {
    LibraryFreshness.Live -> AppleLibraryReaderFreshness("live", null, null, null)
    LibraryFreshness.Loading -> AppleLibraryReaderFreshness("loading", null, null, null)
    is LibraryFreshness.Cached -> AppleLibraryReaderFreshness(
        kind = "cached",
        reason = when (reason) {
            LibraryCachedReason.Revalidating -> "revalidating"
            LibraryCachedReason.Offline -> "offline"
            is LibraryCachedReason.Failed -> "failed"
            LibraryCachedReason.Stale -> "stale"
            LibraryCachedReason.InternalFailure -> "internalFailure"
        },
        errorKind = (reason as? LibraryCachedReason.Failed)?.error?.readerErrorKind(),
        asOfEpochMillis = asOfWall,
    )
    is LibraryFreshness.Unavailable -> AppleLibraryReaderFreshness(
        kind = "unavailable",
        reason = when (reason) {
            LibraryUnavailableReason.NotCachedOffline -> "notCachedOffline"
            LibraryUnavailableReason.Gone -> "gone"
            is LibraryUnavailableReason.Failed -> "failed"
            LibraryUnavailableReason.InternalFailure -> "internalFailure"
        },
        errorKind = (reason as? LibraryUnavailableReason.Failed)?.error?.readerErrorKind(),
        asOfEpochMillis = null,
    )
}

internal fun LibraryCoverage.appleKind(): String = when (this) {
    LibraryCoverage.Complete -> "complete"
    LibraryCoverage.Open -> "open"
    LibraryCoverage.UnverifiedScanning -> "unverifiedScanning"
    LibraryCoverage.UnverifiedNoEpoch -> "unverifiedNoEpoch"
    LibraryCoverage.UnverifiedChanging -> "unverifiedChanging"
}

internal fun LibraryItemsState.appleKind(): String = when (this) {
    LibraryItemsState.Present -> "present"
    LibraryItemsState.Loading -> "loading"
    LibraryItemsState.Unavailable -> "unavailable"
}

internal fun LibraryItemsOrder.appleKind(): String = when (this) {
    LibraryItemsOrder.Server -> "server"
    LibraryItemsOrder.LocalView -> "localView"
}

/** The same words as the enum's names, which the other facades send; spelled out so none can drift. */
internal fun AudioContainer.appleKind(): String = when (this) {
    AudioContainer.Mp3 -> "Mp3"
    AudioContainer.Mp4 -> "Mp4"
    AudioContainer.Wav -> "Wav"
    AudioContainer.Flac -> "Flac"
    AudioContainer.Ogg -> "Ogg"
    AudioContainer.AdtsAac -> "AdtsAac"
}

internal fun LibraryPlayability.appleKind(): String = when (this) {
    LibraryPlayability.Downloaded -> "downloaded"
    LibraryPlayability.Streamable -> "streamable"
    LibraryPlayability.UnavailableOffline -> "unavailableOffline"
}

internal fun SearchScope.appleKind(): String = when (this) {
    SearchScope.ServerAndDevice -> "serverAndDevice"
    SearchScope.DeviceWhileServerPending -> "deviceWhileServerPending"
    is SearchScope.DeviceOffline -> "deviceOffline"
    is SearchScope.DeviceServerFailed -> "deviceServerFailed"
}

internal fun SearchResultSource.appleKind(): String = when (this) {
    SearchResultSource.Server -> "server"
    SearchResultSource.Device -> "device"
}

internal fun SearchResultType.appleKind(): String = when (this) {
    SearchResultType.Artist -> "artist"
    SearchResultType.Album -> "album"
    SearchResultType.Track -> "track"
}

internal fun CreditRole.appleKind(): String = when (this) {
    CreditRole.Artist -> "artist"
    CreditRole.AlbumArtist -> "albumArtist"
}

internal fun MutationField.appleKind(): String = when (this) {
    MutationField.Starred -> "favourite"
    MutationField.Rating -> "rating"
}

/**
 * The closed error vocabulary of the reader facade. It keeps the existing facades' words and splits
 * three of them where the reader's screens need different copy: a refused password from a refused
 * change (`invalidCredentials`, `forbidden`), a busy server from a failed one (`serverBusy`, §18.12:
 * "it must be its own class"), and an item the server no longer has (`notFound`, code 70).
 * Two more words belong to the facade itself: `internalFailure` and `closed`.
 */
internal fun DomainError.readerErrorKind(): String = when (this) {
    DomainError.Transport.Cancelled -> "cancelled"
    DomainError.Transport.Timeout -> "timeout"
    DomainError.Transport.Unreachable -> "unreachable"
    is DomainError.Security.TlsUntrusted -> "tlsUntrusted"
    is DomainError.Security -> "security"
    DomainError.Auth.InvalidCredentials -> "invalidCredentials"
    DomainError.Auth.Forbidden -> "forbidden"
    is DomainError.Auth -> "authentication"
    is DomainError.Protocol -> "protocol"
    is DomainError.Server.Busy -> "serverBusy"
    is DomainError.Server -> if (isNotFound()) "notFound" else "server"
    is DomainError.Playback -> "playback"
    is DomainError.Input -> "input"
    is DomainError.CapabilityUnsupported -> "capability"
}

internal fun LibraryItem.toApple(providerInstanceId: String): AppleLibraryReaderItem = when (this) {
    is LibraryItem.Album -> AppleLibraryReaderItem(
        kind = "album", providerInstanceId = providerInstanceId, rawId = rawId, title = title,
        artistName = artistName, artistRawId = artistRawId, albumTitle = null, albumRawId = null,
        year = year, genre = genre, durationMilliseconds = durationMilliseconds, songCount = songCount,
        albumCount = null, discNumber = null, trackNumber = null, sourceContainer = null,
        artworkKey = artworkKey, owner = null, favourite = starred, rating = userRating,
        playCount = playCount, playability = null, detailComplete = detailComplete, metadataMissing = false,
    )
    is LibraryItem.Artist -> AppleLibraryReaderItem(
        kind = "artist", providerInstanceId = providerInstanceId, rawId = rawId, title = name,
        artistName = null, artistRawId = null, albumTitle = null, albumRawId = null,
        year = null, genre = null, durationMilliseconds = null, songCount = null,
        albumCount = albumCount, discNumber = null, trackNumber = null, sourceContainer = null,
        artworkKey = artworkKey, owner = null, favourite = starred, rating = userRating,
        playCount = null, playability = null, detailComplete = false, metadataMissing = false,
    )
    is LibraryItem.Track -> AppleLibraryReaderItem(
        kind = "track", providerInstanceId = providerInstanceId, rawId = rawId, title = title,
        artistName = artistName, artistRawId = artistRawId, albumTitle = albumTitle, albumRawId = albumRawId,
        year = null, genre = null, durationMilliseconds = durationMilliseconds, songCount = null,
        albumCount = null, discNumber = discNumber, trackNumber = trackNumber,
        sourceContainer = sourceContainer?.appleKind(), artworkKey = artworkKey, owner = null,
        favourite = starred, rating = userRating, playCount = playCount,
        playability = playability.appleKind(), detailComplete = false, metadataMissing = metadataMissing,
    )
    is LibraryItem.Playlist -> AppleLibraryReaderItem(
        kind = "playlist", providerInstanceId = providerInstanceId, rawId = rawId, title = name,
        artistName = null, artistRawId = null, albumTitle = null, albumRawId = null,
        year = null, genre = null, durationMilliseconds = durationMilliseconds, songCount = songCount,
        albumCount = null, discNumber = null, trackNumber = null, sourceContainer = null,
        artworkKey = artworkKey, owner = owner, favourite = null, rating = null,
        playCount = null, playability = null, detailComplete = false, metadataMissing = false,
    )
    is LibraryItem.Genre -> AppleLibraryReaderItem(
        kind = "genre", providerInstanceId = providerInstanceId, rawId = rawId, title = rawId,
        artistName = null, artistRawId = null, albumTitle = null, albumRawId = null,
        year = null, genre = null, durationMilliseconds = null, songCount = null,
        albumCount = null, discNumber = null, trackNumber = null, sourceContainer = null,
        artworkKey = null, owner = null, favourite = null, rating = null,
        playCount = null, playability = null, detailComplete = false, metadataMissing = false,
    )
}

internal fun LibraryPublication.toApple(providerInstanceId: String, sequence: Int): AppleLibraryWindowPublication =
    AppleLibraryWindowPublication(
        sequence = sequence,
        freshness = freshness.toApple(),
        coverage = coverage?.appleKind(),
        total = total,
        leadingOffset = leadingOffset,
        header = header?.toApple(providerInstanceId),
        items = items.map { it.toApple(providerInstanceId) },
        itemsState = itemsState.appleKind(),
        order = order.appleKind(),
        anchorRawId = anchor?.itemRawId,
        anchorIndex = anchor?.index,
    )

internal fun LibrarySearchPublication.toApple(): AppleLibrarySearchPublication {
    val seen = when (val scope = scope) {
        is SearchScope.DeviceOffline -> scope.seen
        is SearchScope.DeviceServerFailed -> scope.seen
        SearchScope.ServerAndDevice, SearchScope.DeviceWhileServerPending -> null
    }
    return AppleLibrarySearchPublication(
        query = query,
        sequence = sequence,
        scope = scope.appleKind(),
        errorKind = (scope as? SearchScope.DeviceServerFailed)?.error?.readerErrorKind(),
        seenArtistCount = seen?.artists,
        seenAlbumCount = seen?.albums,
        seenTrackCount = seen?.tracks,
        rows = rows.map(LibrarySearchRow::toApple),
    )
}

internal fun LibrarySearchRow.toApple(): AppleLibrarySearchRow = AppleLibrarySearchRow(
    kind = item.type.appleKind(),
    providerInstanceId = item.id.providerInstanceId,
    rawId = item.id.rawId,
    title = item.title,
    credits = item.credits.map { AppleLibraryReaderCredit(it.role.appleKind(), it.name, it.id?.rawId) },
    albumTitle = item.albumTitle,
    year = item.year,
    durationMilliseconds = item.duration?.inWholeMilliseconds,
    discNumber = item.discNumber,
    trackNumber = item.trackNumber,
    sourceContainer = item.sourceContainer?.appleKind(),
    mediaSourceId = item.mediaSourceId,
    artworkKey = item.artworkKey,
    source = source.appleKind(),
    favourite = favourite,
    rating = rating,
    playability = playability?.appleKind(),
)

internal fun MutationOutcome.toApple(): AppleLibraryFavouriteOutcome {
    val (kind, value, serverValue, error) = when (this) {
        is MutationOutcome.Saved -> OutcomeFields("saved", value, null, null)
        is MutationOutcome.NotSaved -> OutcomeFields("notSaved", null, null, error.readerErrorKind())
        is MutationOutcome.Held -> OutcomeFields("held", null, null, error.readerErrorKind())
        is MutationOutcome.Superseded -> OutcomeFields("superseded", null, serverValue, null)
        is MutationOutcome.NotRecorded -> OutcomeFields("notRecorded", null, null, null)
    }
    return AppleLibraryFavouriteOutcome(kind, target.kind.wireName, target.rawId, field.appleKind(), value, serverValue, error)
}

private data class OutcomeFields(val kind: String, val value: Int?, val serverValue: Int?, val errorKind: String?)

/**
 * The publication a window gets when the facade itself could not serve it (see the facade). What
 * was shown stays, labelled as cached with its age: [previousLiveAt] is when [previous] was
 * published, used when [previous] was live and so carries no age of its own.
 */
internal fun readerFailurePublication(
    sequence: Int,
    previous: AppleLibraryWindowPublication?,
    errorKind: String?,
    previousLiveAt: Long? = null,
): AppleLibraryWindowPublication {
    // An error never replaces cached content, and cached content carries its age (CORPUS §4 item 11).
    if (previous != null && (previous.items.isNotEmpty() || previous.header != null)) {
        return AppleLibraryWindowPublication(
            sequence = sequence,
            freshness = AppleLibraryReaderFreshness(
                "cached",
                if (errorKind == null) "internalFailure" else "failed",
                errorKind,
                previous.freshness.asOfEpochMillis ?: previousLiveAt.takeIf { previous.freshness.kind == "live" },
            ),
            coverage = previous.coverage,
            total = previous.total,
            leadingOffset = previous.leadingOffset,
            header = previous.header,
            items = previous.items,
            itemsState = previous.itemsState,
            order = previous.order,
            anchorRawId = null,
            anchorIndex = null,
        )
    }
    return AppleLibraryWindowPublication(
        sequence = sequence,
        freshness = AppleLibraryReaderFreshness(
            "unavailable",
            if (errorKind == null) "internalFailure" else "failed",
            errorKind,
            null,
        ),
        coverage = null,
        total = null,
        leadingOffset = 0,
        header = null,
        items = emptyList(),
        itemsState = "unavailable",
        order = "server",
        anchorRawId = null,
        anchorIndex = null,
    )
}
