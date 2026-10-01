import Foundation

// The reader's publications as Swift values (spec §7.1, §16.18).
//
// The core computes every rule -- freshness, coverage, playability, search scope, the overlay of a
// pending change -- and the facade hands each one across the Objective-C boundary as a closed
// vocabulary of strings. These types copy them, once, into Swift enums. Each initializer takes the
// facade's own primitive values, so the app target's copy is field-for-field forwarding and every
// decision about a word lives here, where `swift test` reaches it.
//
// A word the vocabulary does not contain never becomes a guess about the server: it becomes the
// reader's own failure (`internalFailure`), which the screens state as "something went wrong on
// this device" rather than inventing a freshness the core did not publish.

/// The reader facade's closed error vocabulary (§18.12).
public enum DulcetReaderErrorKind: String, Sendable, Hashable, CaseIterable {
    case cancelled
    case timeout
    case unreachable
    case tlsUntrusted
    case security
    case invalidCredentials
    case forbidden
    case authentication
    case `protocol`
    case serverBusy
    case notFound
    case server
    case playback
    case input
    case capability
    case internalFailure
    case closed

    /// A word outside the vocabulary is the reader's own failure, never a guessed server error.
    public init(coreName: String?) {
        self = coreName.flatMap(Self.init(rawValue:)) ?? .internalFailure
    }
}

/// Why cached content is showing (§16.14).
public enum DulcetReaderCachedReason: Sendable, Hashable {
    /// A live read is in flight.
    case revalidating
    /// The reader is offline.
    case offline
    /// The live read failed with this kind.
    case failed(DulcetReaderErrorKind)
    /// The catalog changed and no live read has landed yet.
    case stale
    /// Read live, but a "load more" or "load before" the screen owes is still to be made.
    case owed
    /// The reader itself failed.
    case internalFailure
}

/// Why a screen has nothing to show (§16.14).
public enum DulcetReaderUnavailableReason: Sendable, Hashable {
    case notCachedOffline
    case gone
    case failed(DulcetReaderErrorKind)
    case internalFailure
}

/// How current a publication is (§16.14).
public enum DulcetReaderFreshness: Sendable, Hashable {
    /// Read from the server in this session under the current epoch.
    case live
    /// Nothing cached, and a live read is in flight: the only state that may show a spinner.
    case loading
    /// Served from what this device has seen. `asOf` is the wall clock of that live read, or nil
    /// when its age is unknown.
    case cached(DulcetReaderCachedReason, asOf: Date?)
    /// Nothing cached and nothing can be read.
    case unavailable(DulcetReaderUnavailableReason)

    public init(kind: String, reason: String?, errorKind: String?, asOfEpochMillis: Int64?) {
        let error = DulcetReaderErrorKind(coreName: errorKind)
        switch kind {
        case "live":
            self = .live
        case "loading":
            self = .loading
        case "cached":
            let asOf = asOfEpochMillis.map { Date(timeIntervalSince1970: Double($0) / 1_000) }
            let cachedReason: DulcetReaderCachedReason = switch reason {
            case "revalidating": .revalidating
            case "offline": .offline
            case "failed": .failed(error)
            case "stale": .stale
            case "owed": .owed
            default: .internalFailure
            }
            self = .cached(cachedReason, asOf: asOf)
        case "unavailable":
            let unavailableReason: DulcetReaderUnavailableReason = switch reason {
            case "notCachedOffline": .notCachedOffline
            case "gone": .gone
            case "failed": .failed(error)
            default: .internalFailure
            }
            self = .unavailable(unavailableReason)
        default:
            self = .unavailable(.internalFailure)
        }
    }

    public var isLive: Bool { self == .live }

    /// The reader is not reading the server for this screen: offline, or held offline by a
    /// reconnect that failed.
    public var isOffline: Bool {
        switch self {
        case .cached(.offline, _), .unavailable(.notCachedOffline): true
        default: false
        }
    }

    /// Whether this screen offers "Try again" -- which is always the client's reconnect, never the
    /// screen's own refresh (§16.14): offline, a failure, or an extend still owed.
    public var offersRetry: Bool {
        switch self {
        case .live, .loading: false
        case let .cached(reason, _):
            switch reason {
            case .offline, .failed, .owed, .internalFailure: true
            case .revalidating, .stale: false
            }
        case let .unavailable(reason):
            switch reason {
            case .notCachedOffline, .failed, .internalFailure: true
            case .gone: false
            }
        }
    }
}

/// How much of a list the window holds, and whether it could be verified (§16.12).
public enum DulcetReaderCoverage: String, Sendable, Hashable, CaseIterable {
    case complete
    case open
    case unverifiedScanning
    case unverifiedNoEpoch
    case unverifiedChanging

    public init?(coreName: String?) {
        guard let coreName, let value = Self(rawValue: coreName) else { return nil }
        self = value
    }
}

/// Whether a track can play now (§16.14): both a badge and a filter.
public enum DulcetReaderPlayability: String, Sendable, Hashable, CaseIterable {
    case downloaded
    case streamable
    case unavailableOffline

    public init?(coreName: String?) {
        guard let coreName, let value = Self(rawValue: coreName) else { return nil }
        self = value
    }
}

public enum DulcetReaderItemsState: String, Sendable, Hashable {
    case present
    case loading
    case unavailable

    public init(coreName: String) {
        self = Self(rawValue: coreName) ?? .unavailable
    }
}

public enum DulcetReaderItemsOrder: String, Sendable, Hashable {
    case server
    /// Sorted on this device while offline, from what it has seen (§16.14).
    case localView

    public init(coreName: String) {
        self = Self(rawValue: coreName) ?? .server
    }
}

public enum DulcetReaderItemKind: String, Sendable, Hashable, CaseIterable {
    case album
    case artist
    case track
    case playlist
    case genre
}

/// One entity in a publication, as the core published it -- the pending-change overlay included.
public struct DulcetReaderItem: Identifiable, Sendable, Hashable {
    public let kind: DulcetReaderItemKind
    public let id: DulcetProviderItemID
    public let title: String?
    public let artistName: String?
    public let artistID: DulcetProviderItemID?
    public let albumTitle: String?
    public let albumID: DulcetProviderItemID?
    public let year: Int?
    public let genre: String?
    public let duration: Duration?
    public let songCount: Int?
    public let albumCount: Int?
    public let discNumber: Int?
    public let trackNumber: Int?
    public let sourceContainer: DulcetAudioContainer?
    public let artworkKey: String?
    public let owner: String?
    /// Nil when the server never said.
    public let isFavourite: Bool?
    public let rating: Int?
    public let playCount: Int64?
    /// Tracks only.
    public let playability: DulcetReaderPlayability?
    /// Whether this album's track list has been read, which is how an album with no tracks is told
    /// from one whose tracks were never read -- never `songCount` (§16.11).
    public let detailComplete: Bool
    public let metadataMissing: Bool
    /// Playlists only (§18.6): the account may edit it. False for another user's playlist, which
    /// is drawn read-only with its owner shown -- no edit affordance at all.
    public let editable: Bool
    /// Playlists only: edits made on this device the server has not yet confirmed are shown.
    public let pendingChanges: Bool
    /// Playlists only: made on this device and not yet on the server; its id is a local one.
    public let local: Bool
    public let comment: String?
    public let isPublic: Bool?

    /// Returns nil for a kind outside the vocabulary: the core never publishes one, and an item
    /// that cannot be named is not drawn as some other kind.
    public init?(
        kind: String,
        providerInstanceID: String,
        rawID: String,
        title: String?,
        artistName: String?,
        artistRawID: String?,
        albumTitle: String?,
        albumRawID: String?,
        year: Int?,
        genre: String?,
        durationMilliseconds: Int64?,
        songCount: Int?,
        albumCount: Int?,
        discNumber: Int?,
        trackNumber: Int?,
        sourceContainer: String?,
        artworkKey: String?,
        owner: String?,
        favourite: Bool?,
        rating: Int?,
        playCount: Int64?,
        playability: String?,
        detailComplete: Bool,
        metadataMissing: Bool,
        editable: Bool = false,
        pendingChanges: Bool = false,
        local: Bool = false,
        comment: String? = nil,
        isPublic: Bool? = nil
    ) {
        guard let itemKind = DulcetReaderItemKind(rawValue: kind) else { return nil }
        self.kind = itemKind
        id = DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: rawID)
        self.title = title
        self.artistName = artistName
        artistID = artistRawID.map { DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: $0) }
        self.albumTitle = albumTitle
        albumID = albumRawID.map { DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: $0) }
        self.year = year
        self.genre = genre
        duration = durationMilliseconds.map { .milliseconds($0) }
        self.songCount = songCount
        self.albumCount = albumCount
        self.discNumber = discNumber
        self.trackNumber = trackNumber
        self.sourceContainer = sourceContainer.flatMap(DulcetAudioContainer.init(coreName:))
        self.artworkKey = artworkKey
        self.owner = owner
        isFavourite = favourite
        self.rating = rating
        self.playCount = playCount
        self.playability = DulcetReaderPlayability(coreName: playability)
        self.detailComplete = detailComplete
        self.metadataMissing = metadataMissing
        self.editable = itemKind == .playlist && editable
        self.pendingChanges = itemKind == .playlist && pendingChanges
        self.local = itemKind == .playlist && local
        self.comment = comment
        self.isPublic = isPublic
    }

    /// The name to draw. A genre's name is its id; an item whose name the server never gave says
    /// so rather than drawing an empty row.
    public var displayTitle: String {
        if let title, !title.isEmpty { return title }
        return kind == .genre ? id.rawID : DulcetStrings.readerUntitled
    }

    public var artwork: DulcetArtwork {
        DulcetArtwork.derived(seed: id.rawID, serverID: id.providerInstanceID, artworkKey: artworkKey)
    }

    /// Whether a favourite can be set on this kind (§16.20): artists, albums and tracks.
    public var favouriteTarget: DulcetFavouriteTarget? {
        let targetKind: DulcetFavouriteTargetKind
        switch kind {
        case .artist: targetKind = .artist
        case .album: targetKind = .album
        case .track: targetKind = .track
        case .playlist, .genre: return nil
        }
        return DulcetFavouriteTarget(kind: targetKind, id: id)
    }

    /// Offline, a track that cannot play is dimmed, badged and labelled (§16.14).
    public var isUnavailableOffline: Bool { playability == .unavailableOffline }

    /// The credits a track or album row can link from.
    public var credits: [DulcetCredit] {
        guard let artistName, !artistName.isEmpty else { return [] }
        let role: DulcetCreditRole = kind == .album ? .albumArtist : .artist
        return [DulcetCredit(role: role, name: artistName, id: artistID)]
    }

    /// The track the player needs, for a track the device knows how to ask the server for. Nil for
    /// anything else, and for a track whose duration the server never gave.
    public var playableTrack: DulcetTrack? {
        guard kind == .track, let duration else { return nil }
        return DulcetTrack(
            id: id,
            title: displayTitle,
            credits: credits,
            albumTitle: albumTitle,
            discNumber: discNumber,
            trackNumber: trackNumber,
            duration: duration,
            sourceContainer: sourceContainer,
            mediaSourceID: nil,
            artwork: artwork,
            availability: isUnavailableOffline ? .metadataOnly : .playable,
            isFavorite: isFavourite == true,
            albumID: albumID
        )
    }
}

/// One publication of one screen (§16.18). A window publishes many times, and nothing about a
/// publication means "the open finished".
public struct DulcetLibraryWindow: Sendable, Hashable {
    public let sequence: Int
    public let freshness: DulcetReaderFreshness
    /// Lists only; for a detail screen nil, or `unverifiedScanning`.
    public let coverage: DulcetReaderCoverage?
    public let total: Int?
    /// The server position of `items[0]`. Above zero, rows precede the window that are not loaded.
    public let leadingOffset: Int
    /// A detail screen's own entity: the album, artist or playlist.
    public let header: DulcetReaderItem?
    public let items: [DulcetReaderItem]
    public let itemsState: DulcetReaderItemsState
    public let order: DulcetReaderItemsOrder
    public let anchorID: String?
    public let anchorIndex: Int?

    public init(
        sequence: Int,
        freshness: DulcetReaderFreshness,
        coverage: String?,
        total: Int?,
        leadingOffset: Int,
        header: DulcetReaderItem?,
        items: [DulcetReaderItem],
        itemsState: String,
        order: String,
        anchorRawID: String?,
        anchorIndex: Int?
    ) {
        self.sequence = sequence
        self.freshness = freshness
        self.coverage = DulcetReaderCoverage(coreName: coverage)
        self.total = total
        self.leadingOffset = max(0, leadingOffset)
        self.header = header
        self.items = items
        self.itemsState = DulcetReaderItemsState(coreName: itemsState)
        self.order = DulcetReaderItemsOrder(coreName: order)
        anchorID = anchorRawID
        self.anchorIndex = anchorIndex
    }

    /// Whether the screen has anything of its own to draw.
    public var hasContent: Bool { header != nil || !items.isEmpty }

    /// The tracks in this publication the player can be handed, in order.
    public var playableTracks: [DulcetTrack] {
        items.compactMap(\.playableTrack).filter { $0.availability == .playable }
    }
}

// MARK: - Requests

/// A `getAlbumList2` order (§16.9).
public enum DulcetAlbumListType: String, Sendable, Hashable, CaseIterable, Identifiable {
    case alphabeticalByName
    case alphabeticalByArtist
    case newest
    case recent
    case frequent
    case highest
    case starred
    case random

    public var id: String { rawValue }
}

/// The home screen's rows, each one subscription (CONF-86).
public enum DulcetHomeRow: String, Sendable, Hashable, CaseIterable, Identifiable {
    case recentlyAdded
    case recentlyPlayed
    case mostPlayed
    case favourites

    public var id: String { rawValue }

    /// The facade's `listType` for this row.
    public var listType: String {
        switch self {
        case .recentlyAdded: DulcetAlbumListType.newest.rawValue
        case .recentlyPlayed: DulcetAlbumListType.recent.rawValue
        case .mostPlayed: DulcetAlbumListType.frequent.rawValue
        case .favourites: "favourites"
        }
    }
}

/// Which screen to open (§16.9).
public enum DulcetLibraryQuery: Sendable, Hashable {
    case albums(DulcetAlbumListType)
    case artists
    case artist(rawID: String)
    case album(rawID: String)
    case playlists
    case playlist(rawID: String)
    case favourites
    case genres
    case songsByGenre(String)
    case homeRow(DulcetHomeRow)

    /// The facade request's fields, in the facade's words.
    public var request: (kind: String, rawID: String?, listType: String?, genre: String?) {
        switch self {
        case let .albums(type): ("albumList", nil, type.rawValue, nil)
        case .artists: ("artists", nil, nil, nil)
        case let .artist(rawID): ("artist", rawID, nil, nil)
        case let .album(rawID): ("album", rawID, nil, nil)
        case .playlists: ("playlists", nil, nil, nil)
        case let .playlist(rawID): ("playlist", rawID, nil, nil)
        case .favourites: ("starred", nil, nil, nil)
        case .genres: ("genres", nil, nil, nil)
        case let .songsByGenre(genre): ("songsByGenre", nil, nil, genre)
        case let .homeRow(row): ("homeRow", nil, row.listType, nil)
        }
    }
}

// MARK: - Search

public enum DulcetReaderSearchScope: Sendable, Hashable {
    /// The server search completed and device rows were merged.
    case serverAndDevice
    /// Fewer than two characters, or the server request is in flight.
    case deviceWhileServerPending
    /// The server is unreachable; counts are what this device has seen.
    case deviceOffline(DulcetReaderSeenCounts?)
    /// The server search failed with this kind.
    case deviceServerFailed(DulcetReaderErrorKind, DulcetReaderSeenCounts?)

    public init(scope: String, errorKind: String?, seen: DulcetReaderSeenCounts?) {
        switch scope {
        case "serverAndDevice": self = .serverAndDevice
        case "deviceWhileServerPending": self = .deviceWhileServerPending
        case "deviceOffline": self = .deviceOffline(seen)
        default: self = .deviceServerFailed(DulcetReaderErrorKind(coreName: errorKind), seen)
        }
    }
}

public struct DulcetReaderSeenCounts: Sendable, Hashable {
    public let artists: Int64
    public let albums: Int64
    public let tracks: Int64

    /// Nil unless the core reported all three.
    public init?(artists: Int64?, albums: Int64?, tracks: Int64?) {
        guard let artists, let albums, let tracks else { return nil }
        self.artists = artists
        self.albums = albums
        self.tracks = tracks
    }
}

public enum DulcetReaderSearchSource: String, Sendable, Hashable {
    case server
    case device
}

public struct DulcetReaderSearchRow: Identifiable, Sendable, Hashable {
    public let result: DulcetSearchResult
    public let source: DulcetReaderSearchSource
    public let isFavourite: Bool?
    public let rating: Int?
    public let playability: DulcetReaderPlayability?

    public var id: DulcetProviderItemID { result.id }

    public init?(
        kind: String,
        providerInstanceID: String,
        rawID: String,
        title: String,
        credits: [(role: String, name: String, rawID: String?)],
        albumTitle: String?,
        year: Int?,
        durationMilliseconds: Int64?,
        discNumber: Int?,
        trackNumber: Int?,
        sourceContainer: String?,
        mediaSourceID: String?,
        artworkKey: String?,
        source: String,
        favourite: Bool?,
        rating: Int?,
        playability: String?
    ) {
        guard let resultKind = DulcetSearchResultKind(rawValue: kind) else { return nil }
        result = DulcetSearchResult(
            id: DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: rawID),
            title: title,
            kind: resultKind,
            credits: credits.map { credit in
                DulcetCredit(
                    role: credit.role == "albumArtist" ? .albumArtist : .artist,
                    name: credit.name,
                    id: credit.rawID.map { DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: $0) }
                )
            },
            albumTitle: albumTitle,
            year: year,
            duration: durationMilliseconds.map { .milliseconds($0) },
            mediaSourceID: mediaSourceID,
            artwork: DulcetArtwork.derived(seed: rawID, serverID: providerInstanceID, artworkKey: artworkKey),
            discNumber: discNumber,
            trackNumber: trackNumber,
            sourceContainer: sourceContainer.flatMap(DulcetAudioContainer.init(coreName:))
        )
        self.source = source == "device" ? .device : .server
        isFavourite = favourite
        self.rating = rating
        self.playability = DulcetReaderPlayability(coreName: playability)
    }

    public var isUnavailableOffline: Bool { playability == .unavailableOffline }

    public var favouriteTarget: DulcetFavouriteTarget? {
        let kind: DulcetFavouriteTargetKind = switch result.kind {
        case .artist: .artist
        case .album: .album
        case .track: .track
        }
        return DulcetFavouriteTarget(kind: kind, id: id)
    }
}

public struct DulcetReaderSearchPublication: Sendable, Hashable {
    public let query: String
    public let sequence: Int
    public let scope: DulcetReaderSearchScope
    public let rows: [DulcetReaderSearchRow]

    public init(query: String, sequence: Int, scope: DulcetReaderSearchScope, rows: [DulcetReaderSearchRow]) {
        self.query = query
        self.sequence = sequence
        self.scope = scope
        self.rows = rows
    }
}

// MARK: - Favourites

public enum DulcetFavouriteTargetKind: String, Sendable, Hashable {
    case artist
    case album
    case track
}

public struct DulcetFavouriteTarget: Sendable, Hashable {
    public let kind: DulcetFavouriteTargetKind
    public let id: DulcetProviderItemID

    public init(kind: DulcetFavouriteTargetKind, id: DulcetProviderItemID) {
        self.kind = kind
        self.id = id
    }
}

/// How one favourite or rating change ended, or why it waits (§16.20, §18.3).
public enum DulcetFavouriteOutcomeKind: Sendable, Hashable {
    case saved
    /// Refused, or failed too often: the server's value shows again, and the person is told.
    case notSaved(DulcetReaderErrorKind)
    /// Kept unsent: the account was refused access, or the server asked to wait.
    case held(DulcetReaderErrorKind)
    /// Changed elsewhere after this device last saw it; the server's value wins.
    case superseded
    /// This device could not record it.
    case notRecorded
}

public enum DulcetFavouriteField: String, Sendable, Hashable {
    case favourite
    case rating
}

public struct DulcetFavouriteOutcome: Sendable, Hashable {
    public let kind: DulcetFavouriteOutcomeKind
    public let targetRawID: String
    public let targetKind: DulcetFavouriteTargetKind?
    public let field: DulcetFavouriteField
    /// For `saved`, the value now on the server; nil when the core did not say.
    public let value: Int?
    /// For `superseded`, the server's value, which wins (§18.3).
    public let serverValue: Int?

    public init(
        kind: String,
        targetKind: String,
        rawID: String,
        field: String,
        errorKind: String?,
        value: Int? = nil,
        serverValue: Int? = nil
    ) {
        self.value = value
        self.serverValue = serverValue
        let error = DulcetReaderErrorKind(coreName: errorKind)
        self.kind = switch kind {
        case "saved": .saved
        case "notSaved": .notSaved(error)
        case "held": .held(error)
        case "superseded": .superseded
        default: .notRecorded
        }
        targetRawID = rawID
        self.targetKind = DulcetFavouriteTargetKind(rawValue: targetKind)
        self.field = field == "rating" ? .rating : .favourite
    }
}

// MARK: - Connection

public struct DulcetReaderConnection: Sendable, Hashable {
    /// This call read the catalog epoch. After a reconnect the reader is online and the screen
    /// was revalidated.
    public let epochKnown: Bool
    /// The server reports no scan stamp at all: stated once for the account (§16.12).
    public let serverReportsNoEpoch: Bool
    /// Changes queued as a different username, discarded by this session's binding (§16.10).
    public let discardedPendingChanges: Int64
    public let error: DulcetReaderErrorKind?

    public init(epochKnown: Bool, serverReportsNoEpoch: Bool, discardedPendingChanges: Int64, errorKind: String?) {
        self.epochKnown = epochKnown
        self.serverReportsNoEpoch = serverReportsNoEpoch
        self.discardedPendingChanges = discardedPendingChanges
        error = errorKind.map { DulcetReaderErrorKind(coreName: $0) }
    }
}

/// The changes that have not reached the server, for the sign-out offer (§14.7 step 2). `count` is
/// nil when it could not be read -- never a guessed zero, because a zero says nothing will be lost.
public struct DulcetPendingChanges: Sendable, Hashable {
    public let count: Int64?

    public init(count: Int64?) {
        self.count = count
    }
}

// MARK: - Shared helpers

public extension DulcetAudioContainer {
    /// The core's container names, as every facade sends them.
    init?(coreName: String) {
        switch coreName {
        case "Mp3": self = .mp3
        case "Mp4": self = .mp4
        case "Wav": self = .wav
        case "Flac": self = .flac
        case "Ogg": self = .ogg
        case "AdtsAac": self = .adtsAAC
        default: return nil
        }
    }
}

public extension DulcetArtwork {
    /// Artwork for an item: the server's image when it has a key, over a palette chosen from the
    /// item's id so the placeholder is stable across launches.
    static func derived(seed: String, serverID: String, artworkKey: String?) -> DulcetArtwork {
        let palettes = DulcetArtworkPalette.allCases
        let index = seed.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % palettes.count }
        return DulcetArtwork(
            seed: seed,
            palette: palettes[index],
            remoteReference: artworkKey.map { DulcetArtworkReference(serverID: serverID, artworkKey: $0) }
        )
    }
}
