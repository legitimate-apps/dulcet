import Foundation

public struct DulcetPlaybackAccount: Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let providerInstanceID: String
    public let normalizedServerURL: String
    public let username: String
    public let password: String
    public let allowLocalHTTP: Bool

    public init(
        providerInstanceID: String,
        normalizedServerURL: String,
        username: String,
        password: String,
        allowLocalHTTP: Bool
    ) {
        self.providerInstanceID = providerInstanceID
        self.normalizedServerURL = normalizedServerURL
        self.username = username
        self.password = password
        self.allowLocalHTTP = allowLocalHTTP
    }

    public var description: String { "DulcetPlaybackAccount(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [("playbackAccount", "<redacted>" as Any)], displayStyle: .struct)
    }
}

public enum DulcetPlaybackQueueSourceKind: String, Sendable, Hashable {
    case library
    case album
}

public struct DulcetPlaybackQueueIntent: Sendable, Hashable {
    public let tracks: [DulcetTrack]
    public let sourceKind: DulcetPlaybackQueueSourceKind
    public let sourceID: DulcetProviderItemID?
    public let sourceDisplayName: String
    public let startIndex: Int?
    public let shuffle: Bool

    public init(
        tracks: [DulcetTrack],
        sourceKind: DulcetPlaybackQueueSourceKind,
        sourceID: DulcetProviderItemID?,
        sourceDisplayName: String,
        startIndex: Int?,
        shuffle: Bool
    ) {
        self.tracks = tracks
        self.sourceKind = sourceKind
        self.sourceID = sourceID
        self.sourceDisplayName = sourceDisplayName
        self.startIndex = startIndex
        self.shuffle = shuffle
    }
}

public enum DulcetPlaybackControlIntent: Sendable, Hashable {
    case play
    case pause
    case toggle
    case next
    case previous
    case seek(Duration)
    case setShuffle(Bool)
    case cycleRepeat
}

public enum DulcetPlaybackSurfaceStatus: Sendable, Hashable {
    case unavailable
    case preparing
    case ready
    case failed
}

public struct DulcetPlaybackPresentation: Sendable, Hashable {
    public let status: DulcetPlaybackSurfaceStatus
    public let nowPlaying: DulcetNowPlaying?

    public init(status: DulcetPlaybackSurfaceStatus, nowPlaying: DulcetNowPlaying?) {
        self.status = status
        self.nowPlaying = nowPlaying
    }

    public static let unavailable = Self(status: .unavailable, nowPlaying: nil)
}

@MainActor
public protocol DulcetPlaybackControlling: AnyObject {
    var currentPresentation: DulcetPlaybackPresentation { get }
    func setPresentationHandler(
        _ handler: @escaping @MainActor (DulcetPlaybackPresentation) -> Void
    )
    func configure(account: DulcetPlaybackAccount)
    func restorePersistedQueue(with tracks: [DulcetTrack])
    func replaceQueueAndPlay(_ intent: DulcetPlaybackQueueIntent)
    func send(_ intent: DulcetPlaybackControlIntent)
    func disconnect()
}
