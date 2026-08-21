import Foundation

public enum DulcetFixtureState: String, CaseIterable, Identifiable, Sendable {
    case emptyLibraryNoAccount = "empty-library-no-account"
    case libraryBrowse = "library-browse"
    case albumDetailMultiDisc = "album-detail-multi-disc"
    case nowPlaying = "now-playing"
    case searchMixedSources = "search-mixed-sources"
    case tlsUntrusted = "error-tls-untrusted"
    case offlineMetadataOnly = "offline-metadata-only"

    public var id: String { rawValue }
}

public enum DulcetSidebarDestination: String, CaseIterable, Identifiable, Sendable {
    case library
    case search
    case nowPlaying
    case settings

    public var id: String { rawValue }
}

public enum DulcetConnectivity: Sendable, Hashable {
    case online(serverName: String)
    case offline(lastSyncedDescription: String)
    case unavailable
}

public enum DulcetMediaAvailability: String, Sendable, Hashable {
    case playable
    case metadataOnly
}

public enum DulcetArtworkPalette: String, Sendable, Hashable, CaseIterable {
    case indigoCoral
    case mossGold
    case plumIce
    case oceanMint
    case emberRose
    case duskLavender
    case slateApricot
    case tealSun
}

public struct DulcetArtwork: Sendable, Hashable {
    public let seed: String
    public let palette: DulcetArtworkPalette

    public init(seed: String, palette: DulcetArtworkPalette) {
        self.seed = seed
        self.palette = palette
    }
}

public struct DulcetArtist: Identifiable, Sendable, Hashable {
    public let id: String
    public let name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

public struct DulcetTrack: Identifiable, Sendable, Hashable {
    public let id: String
    public let title: String
    public let artistNames: [String]
    public let albumTitle: String?
    public let discNumber: Int?
    public let trackNumber: Int?
    public let durationSeconds: Int
    public let artwork: DulcetArtwork
    public let availability: DulcetMediaAvailability
    public let isFavorite: Bool

    public init(
        id: String,
        title: String,
        artistNames: [String],
        albumTitle: String?,
        discNumber: Int? = nil,
        trackNumber: Int? = nil,
        durationSeconds: Int,
        artwork: DulcetArtwork,
        availability: DulcetMediaAvailability = .playable,
        isFavorite: Bool = false
    ) {
        self.id = id
        self.title = title
        self.artistNames = artistNames
        self.albumTitle = albumTitle
        self.discNumber = discNumber
        self.trackNumber = trackNumber
        self.durationSeconds = durationSeconds
        self.artwork = artwork
        self.availability = availability
        self.isFavorite = isFavorite
    }
}

public struct DulcetAlbum: Identifiable, Sendable, Hashable {
    public let id: String
    public let title: String
    public let albumArtists: [String]
    public let year: Int
    public let artwork: DulcetArtwork
    public let tracks: [DulcetTrack]

    public init(
        id: String,
        title: String,
        albumArtists: [String],
        year: Int,
        artwork: DulcetArtwork,
        tracks: [DulcetTrack]
    ) {
        self.id = id
        self.title = title
        self.albumArtists = albumArtists
        self.year = year
        self.artwork = artwork
        self.tracks = tracks
    }

    public var totalDurationSeconds: Int {
        tracks.reduce(0) { $0 + $1.durationSeconds }
    }

    public var discNumbers: [Int] {
        Array(Set(tracks.compactMap(\.discNumber))).sorted()
    }
}

public enum DulcetSearchResultKind: String, Sendable, Hashable {
    case track
    case album
    case artist
}

public enum DulcetSearchSource: String, Sendable, Hashable, CaseIterable {
    case local
    case server
    case localAndServer
}

public struct DulcetSearchResult: Identifiable, Sendable, Hashable {
    public let id: String
    public let title: String
    public let subtitle: String
    public let kind: DulcetSearchResultKind
    public let source: DulcetSearchSource
    public let artwork: DulcetArtwork
    public let refreshedFromServer: Bool

    public init(
        id: String,
        title: String,
        subtitle: String,
        kind: DulcetSearchResultKind,
        source: DulcetSearchSource,
        artwork: DulcetArtwork,
        refreshedFromServer: Bool = false
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.kind = kind
        self.source = source
        self.artwork = artwork
        self.refreshedFromServer = refreshedFromServer
    }
}

public struct DulcetNowPlaying: Sendable, Hashable {
    public let current: DulcetTrack
    public let queue: [DulcetTrack]
    public let elapsedSeconds: Int
    public let isPlaying: Bool
    public let outputName: String

    public init(
        current: DulcetTrack,
        queue: [DulcetTrack],
        elapsedSeconds: Int,
        isPlaying: Bool,
        outputName: String
    ) {
        self.current = current
        self.queue = queue
        self.elapsedSeconds = elapsedSeconds
        self.isPlaying = isPlaying
        self.outputName = outputName
    }
}

public struct DulcetTLSFailure: Sendable, Hashable {
    public let serverName: String
    public let reason: String
    public let technicalDetail: String

    public init(serverName: String, reason: String, technicalDetail: String) {
        self.serverName = serverName
        self.reason = reason
        self.technicalDetail = technicalDetail
    }
}

public struct DulcetSnapshot: Sendable, Hashable {
    public let state: DulcetFixtureState
    public let selectedDestination: DulcetSidebarDestination
    public let accountConnected: Bool
    public let connectivity: DulcetConnectivity
    public let albums: [DulcetAlbum]
    public let looseTracks: [DulcetTrack]
    public let selectedAlbum: DulcetAlbum?
    public let nowPlaying: DulcetNowPlaying?
    public let searchQuery: String
    public let searchResults: [DulcetSearchResult]
    public let tlsFailure: DulcetTLSFailure?
    public let captureDate: Date

    public init(
        state: DulcetFixtureState,
        selectedDestination: DulcetSidebarDestination,
        accountConnected: Bool,
        connectivity: DulcetConnectivity,
        albums: [DulcetAlbum],
        looseTracks: [DulcetTrack],
        selectedAlbum: DulcetAlbum? = nil,
        nowPlaying: DulcetNowPlaying? = nil,
        searchQuery: String = "",
        searchResults: [DulcetSearchResult] = [],
        tlsFailure: DulcetTLSFailure? = nil,
        captureDate: Date
    ) {
        self.state = state
        self.selectedDestination = selectedDestination
        self.accountConnected = accountConnected
        self.connectivity = connectivity
        self.albums = albums
        self.looseTracks = looseTracks
        self.selectedAlbum = selectedAlbum
        self.nowPlaying = nowPlaying
        self.searchQuery = searchQuery
        self.searchResults = searchResults
        self.tlsFailure = tlsFailure
        self.captureDate = captureDate
    }
}
