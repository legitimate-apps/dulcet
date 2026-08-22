import Foundation

public enum DulcetPresentationState: String, CaseIterable, Identifiable, Sendable {
    case accountConnectIdle = "account-connect-idle"
    case accountConnecting = "account-connecting"
    case accountConnected = "account-connected"
    case accountErrorInput = "account-error-input"
    case accountErrorTransport = "account-error-transport"
    case accountErrorSecurity = "account-error-security"
    case accountErrorProtocol = "account-error-protocol"
    case accountErrorServer = "account-error-server"
    case accountErrorAuthentication = "account-error-authentication"
    case accountErrorCapability = "account-error-capability"
    case accountErrorPersistence = "account-error-persistence"
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
    case connectionFailed(DulcetConnectionFailure)
    case offline(lastSyncedDescription: String)
    case unavailable
}

public enum DulcetConnectionFailure: Sendable, Hashable {
    case tlsUntrusted(DulcetTLSFailure)
    case account(DulcetAccountFailurePresentation)

    public var serverName: String {
        switch self {
        case let .tlsUntrusted(failure): failure.serverName
        case let .account(failure): failure.serverName
        }
    }
}

public struct DulcetAccountConnectRequest: Sendable, Hashable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let serverURL: String
    public let username: String
    public let password: String
    public let allowLocalHTTP: Bool

    public init(
        serverURL: String,
        username: String,
        password: String,
        allowLocalHTTP: Bool
    ) {
        self.serverURL = serverURL
        self.username = username
        self.password = password
        self.allowLocalHTTP = allowLocalHTTP
    }

    public static let empty = DulcetAccountConnectRequest(
        serverURL: "",
        username: "",
        password: "",
        allowLocalHTTP: false
    )

    public var description: String {
        "DulcetAccountConnectRequest(<redacted>)"
    }

    public var debugDescription: String { description }

    public var customMirror: Mirror {
        Mirror(
            self,
            children: [("accountConnectRequest", "<redacted>" as Any)],
            displayStyle: .struct
        )
    }
}

public struct DulcetConnectedAccountSummary: Sendable, Hashable {
    public let serverName: String
    public let normalizedServerURL: String

    public init(serverName: String, normalizedServerURL: String) {
        self.serverName = serverName
        self.normalizedServerURL = normalizedServerURL
    }
}

public enum DulcetAccountErrorFamily: String, CaseIterable, Sendable, Hashable {
    case input
    case transport
    case security
    case `protocol`
    case server
    case authentication
    case capability
    case persistence
}

public enum DulcetAccountFailureKind: String, CaseIterable, Sendable, Hashable {
    case invalidServerURL
    case transportUnreachable
    case transportTimeout
    case transportCancelled
    case tlsUntrusted
    case localNetworkPolicyRejected
    case redirectRejected
    case malformedEnvelope
    case incompatibleProtocol
    case notASubsonicServer
    case knownServerError
    case unknownServerError
    case invalidCredentials
    case tokenAuthenticationUnsupported
    case forbidden
    case unsupportedAuthenticationChallenge
    case crossOriginRedirectRejected
    case capabilityUnsupported
    case credentialPersistenceFailed

    public var family: DulcetAccountErrorFamily {
        switch self {
        case .invalidServerURL:
            .input
        case .transportUnreachable, .transportTimeout, .transportCancelled:
            .transport
        case .tlsUntrusted, .localNetworkPolicyRejected, .redirectRejected:
            .security
        case .malformedEnvelope, .incompatibleProtocol, .notASubsonicServer:
            .protocol
        case .knownServerError, .unknownServerError:
            .server
        case .invalidCredentials, .tokenAuthenticationUnsupported, .forbidden,
             .unsupportedAuthenticationChallenge, .crossOriginRedirectRejected:
            .authentication
        case .capabilityUnsupported:
            .capability
        case .credentialPersistenceFailed:
            .persistence
        }
    }
}

public struct DulcetAccountFailurePresentation: Sendable, Hashable {
    public let kind: DulcetAccountFailureKind
    public let serverName: String
    public let title: String
    public let message: String
    public let recovery: String
    public let targetHost: String?

    public init(
        kind: DulcetAccountFailureKind,
        serverName: String,
        title: String,
        message: String,
        recovery: String,
        targetHost: String? = nil
    ) {
        self.kind = kind
        self.serverName = serverName
        self.title = title
        self.message = message
        self.recovery = recovery
        self.targetHost = targetHost
    }
}

public enum DulcetAccountConnectOutcome: Sendable, Hashable {
    case connected(DulcetConnectedAccountSummary)
    case failed(DulcetAccountFailurePresentation)
}

public enum DulcetAccountConnectionStatus: Sendable, Hashable {
    case idle
    case connecting
    case connected(DulcetConnectedAccountSummary)
    case failed(DulcetAccountFailurePresentation)
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
    public let volume: Double
    public let audioFormat: DulcetAudioFormat

    public init(
        current: DulcetTrack,
        queue: [DulcetTrack],
        elapsedSeconds: Int,
        isPlaying: Bool,
        outputName: String,
        volume: Double,
        audioFormat: DulcetAudioFormat
    ) {
        self.current = current
        self.queue = queue
        self.elapsedSeconds = elapsedSeconds
        self.isPlaying = isPlaying
        self.outputName = outputName
        self.volume = volume
        self.audioFormat = audioFormat
    }
}

public struct DulcetAudioFormat: Sendable, Hashable {
    public let codec: String
    public let sampleRateKilohertz: Double

    public init(codec: String, sampleRateKilohertz: Double) {
        self.codec = codec
        self.sampleRateKilohertz = sampleRateKilohertz
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

public struct DulcetSnapshot: Sendable, Hashable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let state: DulcetPresentationState
    public let selectedDestination: DulcetSidebarDestination
    public let accountConnected: Bool
    public let connectivity: DulcetConnectivity
    public let albums: [DulcetAlbum]
    public let looseTracks: [DulcetTrack]
    public let recentlyAddedTracks: [DulcetTrack]
    public let selectedAlbum: DulcetAlbum?
    public let nowPlaying: DulcetNowPlaying?
    public let searchQuery: String
    public let searchResults: [DulcetSearchResult]
    public let captureDate: Date
    public let accountForm: DulcetAccountConnectRequest
    public let accountConnection: DulcetAccountConnectionStatus

    public init(
        state: DulcetPresentationState,
        selectedDestination: DulcetSidebarDestination,
        accountConnected: Bool,
        connectivity: DulcetConnectivity,
        albums: [DulcetAlbum],
        looseTracks: [DulcetTrack],
        recentlyAddedTracks: [DulcetTrack],
        selectedAlbum: DulcetAlbum? = nil,
        nowPlaying: DulcetNowPlaying? = nil,
        searchQuery: String = "",
        searchResults: [DulcetSearchResult] = [],
        captureDate: Date,
        accountForm: DulcetAccountConnectRequest = .empty,
        accountConnection: DulcetAccountConnectionStatus = .idle
    ) {
        self.state = state
        self.selectedDestination = selectedDestination
        self.accountConnected = accountConnected
        self.connectivity = connectivity
        self.albums = albums
        self.looseTracks = looseTracks
        self.recentlyAddedTracks = recentlyAddedTracks
        self.selectedAlbum = selectedAlbum
        self.nowPlaying = nowPlaying
        self.searchQuery = searchQuery
        self.searchResults = searchResults
        self.captureDate = captureDate
        self.accountForm = accountForm
        self.accountConnection = accountConnection
    }

    public var description: String {
        "DulcetSnapshot(state=\(state.rawValue), accountConnected=\(accountConnected), "
            + "accountForm=<redacted>)"
    }

    public var debugDescription: String { description }

    public var customMirror: Mirror {
        Mirror(
            self,
            children: [
                ("state", state.rawValue as Any),
                ("accountConnected", accountConnected as Any),
                ("accountForm", "<redacted>" as Any),
            ],
            displayStyle: .struct
        )
    }
}
