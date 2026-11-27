import Foundation

/// The one message a screen can show for a connection problem (spec §16.14).
public struct DulcetConnectionIndicator: Equatable, Sendable {
    public enum Cause: Equatable, Sendable {
        case deviceOffline
        case serverUnreachable
        case connectionFailed
    }

    public let cause: Cause

    public var title: String {
        switch cause {
        case .deviceOffline: DulcetStrings.readerPageOffline
        case .serverUnreachable: DulcetStrings.readerPageServerUnreachable
        case .connectionFailed: DulcetStrings.connectionFailed
        }
    }

    public var symbol: String { cause == .deviceOffline ? "wifi.slash" : "exclamationmark.circle" }
    public var offersRetry: Bool { cause != .deviceOffline }

    /// The path monitor has its own grace. Wait for unanswered requests too, so a
    /// handoff or a delayed response that clears the failure does not flash a message.
    var presentationDelay: Duration { cause == .serverUnreachable ? .seconds(3) : .zero }

    /// A read can fail while the broader session is still connected. Its fallback is
    /// evidence about the server, while only the platform can establish no network path.
    public static func choose(
        mode: DulcetLibraryReaderMode,
        deviceOffline: Bool,
        connectionFailure: DulcetReaderErrorKind?,
        connecting: Bool,
        serverReadFailed: Bool = false
    ) -> DulcetConnectionIndicator? {
        guard mode != .none else { return nil }
        if connectionFailure == .serverIdentityChanged { return .init(cause: .connectionFailed) }
        if deviceOffline { return .init(cause: .deviceOffline) }
        if let connectionFailure {
            return .init(cause: connectionFailure.isTransportFailure ? .serverUnreachable : .connectionFailed)
        }
        if connecting { return nil }
        if mode == .deviceOnly || serverReadFailed { return .init(cause: .serverUnreachable) }
        return nil
    }
}

extension DulcetReaderErrorKind {
    var isTransportFailure: Bool { self == .unreachable || self == .timeout }
}

extension DulcetReaderUnavailableReason {
    var isConnectionFailure: Bool {
        switch self {
        case .notCachedOffline: true
        case let .failed(kind): kind.isTransportFailure
        default: false
        }
    }
}

extension DulcetReaderFreshness {
    var isConnectionFailure: Bool {
        switch self {
        case .cached(.offline, _): true
        case let .cached(.failed(kind), _): kind.isTransportFailure
        case let .unavailable(reason): reason.isConnectionFailure
        default: false
        }
    }
}

extension DulcetReaderSearchScope {
    var isConnectionFailure: Bool {
        switch self {
        case .deviceOffline: true
        case let .deviceServerFailed(kind, _): kind.isTransportFailure
        default: false
        }
    }
}
