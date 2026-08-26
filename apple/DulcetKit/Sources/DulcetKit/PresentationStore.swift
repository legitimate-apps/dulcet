import Observation

public enum DulcetPresentationAction: Sendable, Hashable {
    case selectDestination(DulcetSidebarDestination)
    case updateSearchQuery(String)
    case selectAlbum(DulcetProviderItemID)
    case submitAccountConnection(DulcetAccountConnectRequest)
    case cancelAccountConnection
}

/// The presentation boundary implemented by the deterministic fixture today and live data later.
///
/// Sources accept semantic user actions and can push replacement snapshots at any time. The
/// protocol deliberately has no fixture-state API, so a network-backed source does not need to
/// emulate capture scenarios.
@MainActor
public protocol DulcetDataSource: AnyObject {
    var currentSnapshot: DulcetSnapshot { get }
    func setSnapshotHandler(_ handler: @escaping @MainActor (DulcetSnapshot) -> Void)
    func send(_ action: DulcetPresentationAction)
}

@MainActor
@Observable
public final class DulcetPresentationStore {
    @ObservationIgnored private let source: any DulcetDataSource
    @ObservationIgnored private var isApplyingSourceSnapshot = false

    public private(set) var snapshot: DulcetSnapshot
    public private(set) var selectedDestination: DulcetSidebarDestination
    public var searchQuery: String {
        didSet {
            guard !isApplyingSourceSnapshot, searchQuery != oldValue else { return }
            source.send(.updateSearchQuery(searchQuery))
        }
    }
    public var accountServerURL: String
    public var accountUsername: String
    public var accountPassword: String
    public var accountAllowLocalHTTP: Bool

    public init(source: any DulcetDataSource) {
        self.source = source
        let initialSnapshot = source.currentSnapshot
        snapshot = initialSnapshot
        selectedDestination = initialSnapshot.selectedDestination
        searchQuery = initialSnapshot.searchQuery
        accountServerURL = initialSnapshot.accountForm.serverURL
        accountUsername = initialSnapshot.accountForm.username
        accountPassword = initialSnapshot.accountForm.password
        accountAllowLocalHTTP = initialSnapshot.accountForm.allowLocalHTTP

        source.setSnapshotHandler { [weak self] snapshot in
            self?.receive(snapshot)
        }
    }

    public func selectDestination(_ destination: DulcetSidebarDestination) {
        guard !isApplyingSourceSnapshot else { return }
        selectedDestination = destination
        source.send(.selectDestination(destination))
    }

    public func submitAccountConnection() {
        source.send(.submitAccountConnection(DulcetAccountConnectRequest(
            serverURL: accountServerURL,
            username: accountUsername,
            password: accountPassword,
            allowLocalHTTP: accountAllowLocalHTTP
        )))
    }

    public func cancelAccountConnection() {
        source.send(.cancelAccountConnection)
    }

    public func selectAlbum(_ id: DulcetProviderItemID) {
        source.send(.selectAlbum(id))
    }

    private func receive(_ snapshot: DulcetSnapshot) {
        isApplyingSourceSnapshot = true
        self.snapshot = snapshot
        selectedDestination = snapshot.selectedDestination
        searchQuery = snapshot.searchQuery
        accountServerURL = snapshot.accountForm.serverURL
        accountUsername = snapshot.accountForm.username
        accountPassword = snapshot.accountForm.password
        accountAllowLocalHTTP = snapshot.accountForm.allowLocalHTTP
        isApplyingSourceSnapshot = false
    }
}
