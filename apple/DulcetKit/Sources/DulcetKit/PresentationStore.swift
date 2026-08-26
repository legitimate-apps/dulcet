import Observation

public enum DulcetPresentationAction: Sendable, Hashable {
    case selectDestination(DulcetSidebarDestination)
    case updateSearchQuery(String)
    case loadMoreSearchResults(DulcetSearchResultKind)
    case retrySearch
    case selectAlbum(DulcetProviderItemID)
    case submitAccountConnection(DulcetAccountConnectRequest)
    case cancelAccountConnection
    case removeAccount
    case dismissAccountRemovalFailure
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

    public func removeAccount() {
        source.send(.removeAccount)
    }

    public func dismissAccountRemovalFailure() {
        source.send(.dismissAccountRemovalFailure)
    }

    public func selectAlbum(_ id: DulcetProviderItemID) {
        source.send(.selectAlbum(id))
    }

    public func loadMoreSearchResults(_ kind: DulcetSearchResultKind) {
        source.send(.loadMoreSearchResults(kind))
    }

    public func retrySearch() {
        source.send(.retrySearch)
    }

    @discardableResult
    public func loadArtwork(
        _ reference: DulcetArtworkReference,
        sizeBucket: DulcetArtworkSizeBucket,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> (any DulcetArtworkFetchOperation)? {
        guard let loader = source as? any DulcetArtworkLoading else {
            completion(.unavailable)
            return nil
        }
        return loader.loadArtwork(reference, sizeBucket: sizeBucket, completion: completion)
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
