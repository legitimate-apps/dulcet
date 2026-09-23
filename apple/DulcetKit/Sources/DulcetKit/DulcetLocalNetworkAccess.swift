import Foundation
import Network

/// Whether the system's local-network privacy is what stands between Dulcet and a server.
public enum DulcetLocalNetworkAccess: Sendable, Hashable {
    /// Local-network access is refused, or the person has not answered the prompt yet.
    case denied
    /// Local-network privacy is not the obstacle: the server was reached, or failed for another
    /// reason, or the question could not be settled in time.
    case notDenied
}

@MainActor
public protocol DulcetLocalNetworkAccessWatch: AnyObject {
    func cancel()
}

/// Asks the operating system -- not an address heuristic -- whether local-network privacy is
/// blocking a server. Only a destination on the local network can be refused for that reason, so
/// a public server simply answers `.notDenied`.
@MainActor
public protocol DulcetLocalNetworkAccessProbing: AnyObject {
    /// Reports the current answer once, then keeps watching while the answer is `.denied` and
    /// reports `.notDenied` if access is granted later -- the prompt answered, or the setting
    /// turned on. Watching ends after a `.notDenied` answer or on `cancel()`.
    func watch(
        serverURL: String,
        onChange: @escaping @MainActor (DulcetLocalNetworkAccess) -> Void
    ) -> any DulcetLocalNetworkAccessWatch
}

/// The Network framework's answer: a TCP connection to the server's host and port whose path the
/// system reports as unsatisfied with `.localNetworkDenied`. Nothing is sent on the connection;
/// it is cancelled as soon as it is ready.
public final class DulcetNetworkLocalNetworkAccessProbe: DulcetLocalNetworkAccessProbing {
    /// How long to wait for a first answer before concluding privacy is not the obstacle.
    private let firstAnswerTimeout: Duration

    public init(firstAnswerTimeout: Duration = .seconds(3)) {
        self.firstAnswerTimeout = firstAnswerTimeout
    }

    public func watch(
        serverURL: String,
        onChange: @escaping @MainActor (DulcetLocalNetworkAccess) -> Void
    ) -> any DulcetLocalNetworkAccessWatch {
        let watch = Watch(onChange: onChange)
        guard let components = URLComponents(string: serverURL),
              let host = components.host, !host.isEmpty else {
            watch.report(.notDenied)
            return watch
        }
        let defaultPort = components.scheme?.lowercased() == "https" ? 443 : 80
        guard let port = NWEndpoint.Port(rawValue: UInt16(components.port ?? defaultPort)) else {
            watch.report(.notDenied)
            return watch
        }
        watch.start(host: host, port: port, timeout: firstAnswerTimeout)
        return watch
    }

    @MainActor
    private final class Watch: DulcetLocalNetworkAccessWatch {
        private let onChange: @MainActor (DulcetLocalNetworkAccess) -> Void
        private var connection: NWConnection?
        private var timeoutTask: Task<Void, Never>?
        private var lastReported: DulcetLocalNetworkAccess?
        private var finished = false

        init(onChange: @escaping @MainActor (DulcetLocalNetworkAccess) -> Void) {
            self.onChange = onChange
        }

        func start(host: String, port: NWEndpoint.Port, timeout: Duration) {
            let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)
            self.connection = connection
            connection.stateUpdateHandler = { [weak self, weak connection] state in
                let deniedPath = connection?.currentPath?.unsatisfiedReason == .localNetworkDenied
                Task { @MainActor in self?.receive(state, deniedPath: deniedPath) }
            }
            connection.pathUpdateHandler = { [weak self] path in
                let denied = path.unsatisfiedReason == .localNetworkDenied
                Task { @MainActor in self?.receivePath(denied: denied) }
            }
            connection.start(queue: .global(qos: .userInitiated))
            timeoutTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: timeout)
                guard let self, !Task.isCancelled, self.lastReported == nil else { return }
                self.report(.notDenied)
            }
        }

        private func receive(_ state: NWConnection.State, deniedPath: Bool) {
            switch state {
            case .ready:
                report(.notDenied)
            case .waiting:
                report(deniedPath ? .denied : .notDenied)
            case .failed:
                report(.notDenied)
            case .setup, .preparing, .cancelled:
                break
            @unknown default:
                break
            }
        }

        private func receivePath(denied: Bool) {
            if denied {
                report(.denied)
            } else if lastReported == .denied {
                // Access granted while denied: the path is usable now.
                report(.notDenied)
            }
        }

        func report(_ access: DulcetLocalNetworkAccess) {
            guard !finished, access != lastReported else { return }
            lastReported = access
            if access == .notDenied { finish() }
            onChange(access)
        }

        private func finish() {
            finished = true
            timeoutTask?.cancel()
            connection?.cancel()
            connection = nil
        }

        func cancel() {
            finish()
        }
    }
}
