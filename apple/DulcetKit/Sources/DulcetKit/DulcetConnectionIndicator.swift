import Foundation

/// The one thing a screen says about the connection, chosen once for every screen (spec §16.14).
///
/// Two different facts are kept apart, because each has its own cure:
///  - **the device is offline** (the platform reports no network path): nothing to retry, the
///    network's return is what brings Dulcet back, so no Try Again is offered;
///  - **the server is unreachable** (a path exists, the server does not answer): Try Again asks
///    the reconnect again.
/// A screen draws at most this one indicator, in one style, and never also a freshness line that
/// says the same thing.
public struct DulcetConnectionIndicator: Equatable, Sendable {
    public enum Cause: Equatable, Sendable {
        /// No network path. "You're offline."
        case deviceOffline
        /// A path exists and the server does not answer. "Can't reach your server."
        case serverUnreachable
        /// The server answered with a refusal or something unusable.
        case connectionFailed
        /// The account is reconnecting while the device's music shows.
        case reconnecting
    }

    public let cause: Cause

    /// The wording over a whole screen (Library home, Listen Now): states what shows.
    public var bannerTitle: String {
        switch cause {
        case .deviceOffline: DulcetStrings.readerOfflineNotice
        case .serverUnreachable: DulcetStrings.connectionsUnreachableNotice
        case .connectionFailed: DulcetStrings.connectionFailed
        case .reconnecting: DulcetStrings.readerOfflineConnectingNotice
        }
    }

    /// The short wording over a page of content.
    public var pageTitle: String {
        switch cause {
        case .deviceOffline: DulcetStrings.readerPageOffline
        case .serverUnreachable: DulcetStrings.readerPageServerUnreachable
        case .connectionFailed: DulcetStrings.connectionFailed
        case .reconnecting: DulcetStrings.readerOfflineConnectingNotice
        }
    }

    public var symbol: String {
        switch cause {
        case .deviceOffline: "wifi.slash"
        case .serverUnreachable, .connectionFailed: "exclamationmark.circle"
        case .reconnecting: "arrow.triangle.2.circlepath"
        }
    }

    /// Try Again is offered only where asking again can change something.
    public var offersRetry: Bool {
        switch cause {
        case .deviceOffline: false
        case .serverUnreachable, .connectionFailed, .reconnecting: true
        }
    }

    /// Whether the retry is pressable now: not while the reconnect it would start is running.
    public var retryEnabled: Bool { cause != .reconnecting }

    /// What to say, or nil for nothing.
    /// - `deviceOffline`: the platform's report, after the unreachable grace.
    /// - `connectionFailure`: why the last reconnect could not read the server, while that keeps
    ///   the reader offline.
    /// - `connecting`: the saved account's own reconnect is running.
    public static func choose(
        mode: DulcetLibraryReaderMode,
        deviceOffline: Bool,
        connectionFailure: DulcetReaderErrorKind?,
        connecting: Bool
    ) -> DulcetConnectionIndicator? {
        guard mode != .none else { return nil }
        // No path outranks everything else: a failure recorded while the network was gone says
        // nothing about the server.
        if deviceOffline, connectionFailure != .serverIdentityChanged { return .init(cause: .deviceOffline) }
        if mode == .deviceOnly {
            return .init(cause: connecting ? .reconnecting : .serverUnreachable)
        }
        guard let failure = connectionFailure else { return nil }
        return .init(cause: failure == .unreachable ? .serverUnreachable : .connectionFailed)
    }
}
