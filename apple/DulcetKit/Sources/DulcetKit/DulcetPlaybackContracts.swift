import Foundation

public struct DulcetPlaybackSessionID: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String

    public init(_ rawValue: String) {
        precondition(!rawValue.isEmpty)
        self.rawValue = rawValue
    }

    public var description: String { "DulcetPlaybackSessionID(<opaque>)" }
}

public struct DulcetPlaybackAttemptID: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String

    public init(_ rawValue: String) {
        precondition(!rawValue.isEmpty)
        self.rawValue = rawValue
    }

    public var description: String { "DulcetPlaybackAttemptID(<opaque>)" }
}

public struct DulcetPlaybackCommandID: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String

    public init(_ rawValue: String) {
        precondition(!rawValue.isEmpty)
        self.rawValue = rawValue
    }

    public var description: String { "DulcetPlaybackCommandID(<opaque>)" }
}

public enum DulcetPlaybackDeliveryProtocol: Sendable {
    case httpProgressive
    case hls
}

public enum DulcetAudioContainer: Sendable {
    case mp3
    case mp4
    case wav
    case flac
    case ogg
    case adtsAAC
}

/// Opaque platform bridge carried by a plan. Its representation must never expose a signed URL.
public protocol DulcetPlaybackResource: AnyObject, Sendable, CustomStringConvertible {}

public struct DulcetNowPlayingMetadata: Equatable, Sendable {
    public let title: String
    public let artist: String?
    public let albumTitle: String?

    public init(title: String, artist: String? = nil, albumTitle: String? = nil) {
        self.title = title
        self.artist = artist
        self.albumTitle = albumTitle
    }
}

public struct DulcetPlaybackPlan: Sendable, CustomStringConvertible, CustomDebugStringConvertible,
    CustomReflectable {
    public let playbackSessionID: DulcetPlaybackSessionID
    public let attemptID: DulcetPlaybackAttemptID
    public let deliveryProtocol: DulcetPlaybackDeliveryProtocol
    public let expectedContainer: DulcetAudioContainer
    public let resource: any DulcetPlaybackResource
    public let metadata: DulcetNowPlayingMetadata

    public init(
        playbackSessionID: DulcetPlaybackSessionID,
        attemptID: DulcetPlaybackAttemptID,
        deliveryProtocol: DulcetPlaybackDeliveryProtocol,
        expectedContainer: DulcetAudioContainer,
        resource: any DulcetPlaybackResource,
        metadata: DulcetNowPlayingMetadata
    ) {
        self.playbackSessionID = playbackSessionID
        self.attemptID = attemptID
        self.deliveryProtocol = deliveryProtocol
        self.expectedContainer = expectedContainer
        self.resource = resource
        self.metadata = metadata
    }

    public var description: String { "DulcetPlaybackPlan(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [("playbackPlan", "<redacted>" as Any)], displayStyle: .struct)
    }
}

public enum DulcetPlaybackCommand: Sendable {
    case prepare(commandID: DulcetPlaybackCommandID, plan: DulcetPlaybackPlan)
    case play(commandID: DulcetPlaybackCommandID)
    case pause(commandID: DulcetPlaybackCommandID)
    case stop(commandID: DulcetPlaybackCommandID)
    case seek(commandID: DulcetPlaybackCommandID, position: TimeInterval)
    case setVolume(commandID: DulcetPlaybackCommandID, volume: Double)
    case setRate(commandID: DulcetPlaybackCommandID, rate: Double)
    case replaceCurrent(commandID: DulcetPlaybackCommandID, plan: DulcetPlaybackPlan)
    case preloadNext(commandID: DulcetPlaybackCommandID, plan: DulcetPlaybackPlan)
    case release(commandID: DulcetPlaybackCommandID)

    public var commandID: DulcetPlaybackCommandID {
        switch self {
        case let .prepare(commandID, _), let .play(commandID), let .pause(commandID),
             let .stop(commandID), let .seek(commandID, _), let .setVolume(commandID, _),
             let .setRate(commandID, _), let .replaceCurrent(commandID, _),
             let .preloadNext(commandID, _), let .release(commandID):
            commandID
        }
    }
}

/// Closed, content-free adapter failures. Raw AVFoundation errors and URLs never cross this type.
public enum DulcetPlaybackFailure: Equatable, Sendable {
    case authentication
    case forbidden
    case serverBusy(retryAfter: TimeInterval?)
    case protocolViolation
    case transport
    case tlsUntrusted
    case sourceUnavailable
    case unsupportedPlan
    case engine
}

public enum DulcetPlaybackCommandRejectionReason: Equatable, Sendable {
    case invalidState
    case unsupported
    case engineReleased
    case failed(DulcetPlaybackFailure)
}

public enum DulcetPlaybackCommandResult: Equatable, Sendable {
    case withoutData
}

public enum DulcetPlaybackCommandOutcome: Equatable, Sendable {
    case accepted(commandID: DulcetPlaybackCommandID)
    case rejected(commandID: DulcetPlaybackCommandID, reason: DulcetPlaybackCommandRejectionReason)
    case completed(commandID: DulcetPlaybackCommandID, result: DulcetPlaybackCommandResult)

    public var commandID: DulcetPlaybackCommandID {
        switch self {
        case let .accepted(commandID), let .rejected(commandID, _), let .completed(commandID, _):
            commandID
        }
    }
}

public enum DulcetPlaybackSeekability: Equatable, Sendable {
    case seekable
    case notSeekable
    case unknown
}

public enum DulcetPlaybackSkipReason: Equatable, Sendable {
    case user
    case autoAdvance
    case queueReplacement
}

public enum DulcetPlaybackRouteKind: Equatable, Sendable {
    case builtIn
    case wired
    case bluetooth
    case hdmi
    case remote
    case unknown
}

public enum DulcetPlaybackEngineTeardownReason: Equatable, Sendable {
    case backgroundLimit
    case lifecycle
    case systemReclaimed
    case released
    case unknown
}

public enum DulcetPlaybackSourceRefreshReason: Equatable, Sendable {
    case unauthorized
    case expired
    case validationFailed
}

public enum DulcetPlaybackObservationStatus: Equatable, Sendable {
    case preparing
    case ready
    case progressing
    case buffering
    case paused
    case stopped
    case failed
}

/// Nanoseconds from one monotonic clock origin. This value is transient and must never be persisted.
public struct DulcetMonotonicInstant: Equatable, Comparable, Sendable {
    public let uptimeNanoseconds: UInt64

    public init(uptimeNanoseconds: UInt64) {
        self.uptimeNanoseconds = uptimeNanoseconds
    }

    public static func < (lhs: Self, rhs: Self) -> Bool {
        lhs.uptimeNanoseconds < rhs.uptimeNanoseconds
    }
}

public struct DulcetPlaybackObservationSnapshot: Equatable, Sendable {
    public let status: DulcetPlaybackObservationStatus
    public let mediaPosition: TimeInterval?
    public let duration: TimeInterval?
    public let seekability: DulcetPlaybackSeekability
    public let rate: Double
    public let progressStartWallClock: Date?

    public init(
        status: DulcetPlaybackObservationStatus,
        mediaPosition: TimeInterval?,
        duration: TimeInterval?,
        seekability: DulcetPlaybackSeekability,
        rate: Double,
        progressStartWallClock: Date? = nil
    ) {
        self.status = status
        self.mediaPosition = mediaPosition
        self.duration = duration
        self.seekability = seekability
        self.rate = rate
        self.progressStartWallClock = progressStartWallClock
    }
}

public enum DulcetPlaybackEvent: Equatable, Sendable {
    case preparing(attemptID: DulcetPlaybackAttemptID)
    case ready(
        attemptID: DulcetPlaybackAttemptID,
        duration: TimeInterval?,
        seekability: DulcetPlaybackSeekability
    )
    case playbackProgressBegan(
        attemptID: DulcetPlaybackAttemptID,
        wallClock: Date,
        mediaPosition: TimeInterval
    )
    case buffering(attemptID: DulcetPlaybackAttemptID, position: TimeInterval)
    case bufferingEnded(attemptID: DulcetPlaybackAttemptID, position: TimeInterval)
    case paused(attemptID: DulcetPlaybackAttemptID, position: TimeInterval)
    case resumed(attemptID: DulcetPlaybackAttemptID, position: TimeInterval)
    case positionChanged(
        attemptID: DulcetPlaybackAttemptID,
        mediaPosition: TimeInterval,
        monotonicTime: DulcetMonotonicInstant
    )
    case durationChanged(attemptID: DulcetPlaybackAttemptID, duration: TimeInterval)
    case seekCompleted(
        attemptID: DulcetPlaybackAttemptID,
        from: TimeInterval,
        to: TimeInterval
    )
    case seekFailed(
        attemptID: DulcetPlaybackAttemptID,
        from: TimeInterval,
        to: TimeInterval
    )
    case endedNaturally(attemptID: DulcetPlaybackAttemptID, finalPosition: TimeInterval)
    case skipped(
        attemptID: DulcetPlaybackAttemptID,
        position: TimeInterval,
        reason: DulcetPlaybackSkipReason
    )
    case failedBeforeStart(attemptID: DulcetPlaybackAttemptID, error: DulcetPlaybackFailure)
    case failedAfterPartial(
        attemptID: DulcetPlaybackAttemptID,
        position: TimeInterval,
        error: DulcetPlaybackFailure
    )
    case routeChanged(
        attemptID: DulcetPlaybackAttemptID,
        old: DulcetPlaybackRouteKind,
        new: DulcetPlaybackRouteKind,
        didPause: Bool
    )
    case interruptionBegan(attemptID: DulcetPlaybackAttemptID, shouldResume: Bool)
    case interruptionEnded(attemptID: DulcetPlaybackAttemptID, shouldResume: Bool)
    case attemptReplaced(
        oldAttemptID: DulcetPlaybackAttemptID,
        newAttemptID: DulcetPlaybackAttemptID
    )
    case advancedToPreloaded(
        oldAttemptID: DulcetPlaybackAttemptID,
        newAttemptID: DulcetPlaybackAttemptID
    )
    case rateChanged(attemptID: DulcetPlaybackAttemptID, rate: Double)
    case engineTornDown(
        attemptID: DulcetPlaybackAttemptID,
        reason: DulcetPlaybackEngineTeardownReason
    )
    case sourceRefreshRequired(
        attemptID: DulcetPlaybackAttemptID,
        reason: DulcetPlaybackSourceRefreshReason
    )
    case observationResynced(
        attemptID: DulcetPlaybackAttemptID,
        snapshot: DulcetPlaybackObservationSnapshot
    )

    public var attemptID: DulcetPlaybackAttemptID {
        switch self {
        case let .preparing(attemptID), let .ready(attemptID, _, _),
             let .playbackProgressBegan(attemptID, _, _), let .buffering(attemptID, _),
             let .bufferingEnded(attemptID, _), let .paused(attemptID, _),
             let .resumed(attemptID, _), let .positionChanged(attemptID, _, _),
             let .durationChanged(attemptID, _), let .seekCompleted(attemptID, _, _),
             let .seekFailed(attemptID, _, _), let .endedNaturally(attemptID, _),
             let .skipped(attemptID, _, _), let .failedBeforeStart(attemptID, _),
             let .failedAfterPartial(attemptID, _, _), let .routeChanged(attemptID, _, _, _),
             let .interruptionBegan(attemptID, _), let .interruptionEnded(attemptID, _),
             let .rateChanged(attemptID, _), let .engineTornDown(attemptID, _),
             let .sourceRefreshRequired(attemptID, _), let .observationResynced(attemptID, _):
            attemptID
        case let .attemptReplaced(oldAttemptID, _), let .advancedToPreloaded(oldAttemptID, _):
            oldAttemptID
        }
    }
}

public typealias DulcetPlaybackEventHandler = @Sendable (DulcetPlaybackEvent) -> Void
public typealias DulcetPlaybackCommandCompletion = @Sendable (DulcetPlaybackCommandOutcome) -> Void

/// Bidirectional Apple playback boundary. Implementations preserve event order per attempt.
public protocol DulcetApplePlaybackEngine: AnyObject, Sendable {
    func execute(
        _ command: DulcetPlaybackCommand,
        completion: @escaping DulcetPlaybackCommandCompletion
    )

    /// Attaching a non-nil listener immediately publishes ObservationResynced when an attempt exists.
    func setEventListener(_ listener: DulcetPlaybackEventHandler?)
}
