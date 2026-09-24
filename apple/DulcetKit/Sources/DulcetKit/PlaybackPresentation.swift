import Foundation

public struct DulcetPlaybackAccount: Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let providerInstanceID: String
    public let normalizedServerURL: String
    public let username: String
    public let password: String
    public let allowLocalHTTP: Bool
    public let credentialGeneration: Int64

    public init(
        providerInstanceID: String,
        normalizedServerURL: String,
        username: String,
        password: String,
        allowLocalHTTP: Bool,
        credentialGeneration: Int64 = 0
    ) {
        self.providerInstanceID = providerInstanceID
        self.normalizedServerURL = normalizedServerURL
        self.username = username
        self.password = password
        self.allowLocalHTTP = allowLocalHTTP
        self.credentialGeneration = credentialGeneration
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
    case search
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
    /// Try Again, after the current entry failed to play. What that starts is the controller's
    /// decision (spec §12.1): a new attempt of the same session when it failed before starting,
    /// a new play from where it stopped when it failed partway.
    case retry
}

/// What the player can say, and offer, about a track that could not be played.
public struct DulcetFailedPlayback: Sendable, Hashable {
    /// The track that failed, when the controller can name it.
    public let track: DulcetTrack?
    /// Whether Skip has a different entry to go to: one follows the failed entry, or the queue
    /// repeats onto another. Never true for the only entry of a repeating queue, where Skip would
    /// start the failed track again.
    public let canSkip: Bool
    /// Whether the failed entry can be started again.
    public let canRetry: Bool
    /// The track played for a while and then stopped, rather than failing to start.
    public let stoppedPartway: Bool

    public init(track: DulcetTrack?, canSkip: Bool, canRetry: Bool, stoppedPartway: Bool = false) {
        self.track = track
        self.canSkip = canSkip
        self.canRetry = canRetry
        self.stoppedPartway = stoppedPartway
    }

    /// A failure the controller cannot describe: nothing to name, nowhere to go.
    public static let undescribed = Self(track: nil, canSkip: false, canRetry: false)
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
    /// Set with a `.failed` status: which track failed, and whether Skip and Retry can act.
    public let failure: DulcetFailedPlayback?

    public init(
        status: DulcetPlaybackSurfaceStatus,
        nowPlaying: DulcetNowPlaying?,
        failure: DulcetFailedPlayback? = nil
    ) {
        self.status = status
        self.nowPlaying = nowPlaying
        self.failure = status == .failed ? failure : nil
    }

    public static let unavailable = Self(status: .unavailable, nowPlaying: nil)
}

/// How much of the library the catalog handed to restoration can speak for.
///
/// Absence means two different things and they must not share one value. Under `wholeLibrary`,
/// a queue entry missing from the catalog cannot resolve, and the saved selection is cleared so
/// no launch tries to start it again. Under `partial` the catalog is whatever track lists have
/// been read so far, so absence proves nothing — clearing a saved position on that basis would
/// throw away a good position from no evidence at all.
public enum DulcetLibraryCatalogCoverage: Sendable, Hashable {
    /// Every album's track list is present, so the catalog is authoritative about absence.
    case wholeLibrary
    /// Some albums have not been read, so absence is unknown rather than gone.
    case partial
}

@MainActor
public protocol DulcetPlaybackControlling: AnyObject {
    var currentPresentation: DulcetPlaybackPresentation { get }
    func setPresentationHandler(
        _ handler: @escaping @MainActor (DulcetPlaybackPresentation) -> Void
    )
    func configure(account: DulcetPlaybackAccount)
    func restorePersistedQueue(
        with tracks: [DulcetTrack],
        catalogCoverage: DulcetLibraryCatalogCoverage
    )
    func replaceQueueAndPlay(_ intent: DulcetPlaybackQueueIntent)
    func send(_ intent: DulcetPlaybackControlIntent)
    func disconnect()
}

/// What starting playback does to the surface the person is looking at.
///
/// Where a persistent now-playing bar exists (iPhone, iPad, Mac), playing a track leaves the
/// person where they were — on the album, the artist or the search results — and the bar shows
/// what is playing; the full player is one tap on that bar away. tvOS has no such bar, so there
/// starting playback still moves the app to Now Playing, which is the only place its transport
/// controls live.
public enum DulcetPlaybackStartNavigation: Sendable, Hashable {
    case showNowPlaying
    case stayOnCurrentSurface

    public static var platformDefault: Self {
#if os(tvOS)
        .showNowPlaying
#else
        .stayOnCurrentSurface
#endif
    }
}
