import Foundation

@MainActor
public protocol DulcetAccountConnectOperation: AnyObject {
    func cancel()
}

@MainActor
public protocol DulcetAccountConnecting: AnyObject {
    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation
}

/// Live presentation source for account setup. Network and persistence adapters stay replaceable.
@MainActor
public final class DulcetAccountDataSource: DulcetDataSource {
    private let connector: any DulcetAccountConnecting
    private var snapshotHandler: (@MainActor (DulcetSnapshot) -> Void)?
    private var activeOperation: (any DulcetAccountConnectOperation)?
    private var generation = 0

    public private(set) var currentSnapshot: DulcetSnapshot

    public init(
        connector: any DulcetAccountConnecting,
        initialRequest: DulcetAccountConnectRequest = .empty
    ) {
        self.connector = connector
        currentSnapshot = Self.snapshot(
            state: .accountConnectIdle,
            form: initialRequest,
            status: .idle
        )
    }

    public func setSnapshotHandler(
        _ handler: @escaping @MainActor (DulcetSnapshot) -> Void
    ) {
        snapshotHandler = handler
    }

    public func send(_ action: DulcetPresentationAction) {
        switch action {
        case let .selectDestination(destination):
            if destination == .settings {
                publish(
                    state: currentSnapshot.state.accountStateOrIdle,
                    form: currentSnapshot.accountForm,
                    status: currentSnapshot.accountConnection
                )
            }
        case .updateSearchQuery:
            break
        case let .submitAccountConnection(request):
            submit(request)
        case .cancelAccountConnection:
            activeOperation?.cancel()
        }
    }

    private func submit(_ request: DulcetAccountConnectRequest) {
        generation += 1
        let submissionGeneration = generation
        activeOperation = nil
        publish(state: .accountConnecting, form: request, status: .connecting)

        let operation = connector.connect(request) { [weak self] outcome in
            guard let self, self.generation == submissionGeneration else { return }
            self.activeOperation = nil
            switch outcome {
            case let .connected(account):
                self.publish(
                    state: .accountConnected,
                    form: request,
                    status: .connected(account)
                )
            case let .failed(failure) where failure.kind == .transportCancelled:
                self.publish(
                    state: .accountConnectIdle,
                    form: request,
                    status: .idle
                )
            case let .failed(failure):
                self.publish(
                    state: failure.kind.family.presentationState,
                    form: request,
                    status: .failed(failure)
                )
            }
        }
        if generation == submissionGeneration,
           currentSnapshot.state == .accountConnecting {
            activeOperation = operation
        }
    }

    private func publish(
        state: DulcetPresentationState,
        form: DulcetAccountConnectRequest,
        status: DulcetAccountConnectionStatus
    ) {
        currentSnapshot = Self.snapshot(state: state, form: form, status: status)
        snapshotHandler?(currentSnapshot)
    }

    private static func snapshot(
        state: DulcetPresentationState,
        form: DulcetAccountConnectRequest,
        status: DulcetAccountConnectionStatus
    ) -> DulcetSnapshot {
        let connectivity: DulcetConnectivity = switch status {
        case .idle, .connecting:
            .unavailable
        case let .connected(account):
            .online(serverName: account.serverName)
        case let .failed(failure):
            .connectionFailed(.account(failure))
        }
        return DulcetSnapshot(
            state: state,
            selectedDestination: .settings,
            accountConnected: {
                if case .connected = status { return true }
                return false
            }(),
            connectivity: connectivity,
            albums: [],
            looseTracks: [],
            recentlyAddedTracks: [],
            captureDate: Date(timeIntervalSince1970: 0),
            accountForm: form,
            accountConnection: status
        )
    }
}

private extension DulcetPresentationState {
    var accountStateOrIdle: DulcetPresentationState {
        switch self {
        case .accountConnectIdle, .accountConnecting, .accountConnected,
             .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            self
        case .emptyLibraryNoAccount, .libraryBrowse, .albumDetailMultiDisc, .nowPlaying,
             .searchMixedSources, .tlsUntrusted, .offlineMetadataOnly:
            .accountConnectIdle
        }
    }
}

private extension DulcetAccountErrorFamily {
    var presentationState: DulcetPresentationState {
        switch self {
        case .input: .accountErrorInput
        case .transport: .accountErrorTransport
        case .security: .accountErrorSecurity
        case .protocol: .accountErrorProtocol
        case .server: .accountErrorServer
        case .authentication: .accountErrorAuthentication
        case .capability: .accountErrorCapability
        case .persistence: .accountErrorPersistence
        }
    }
}
