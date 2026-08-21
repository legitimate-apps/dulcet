import Observation

public enum DulcetPresentationAction: Sendable, Hashable {
    case selectDestination(DulcetSidebarDestination)
    case updateSearchQuery(String)
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
    public var selectedDestination: DulcetSidebarDestination {
        didSet {
            guard !isApplyingSourceSnapshot, selectedDestination != oldValue else { return }
            source.send(.selectDestination(selectedDestination))
        }
    }
    public var searchQuery: String {
        didSet {
            guard !isApplyingSourceSnapshot, searchQuery != oldValue else { return }
            source.send(.updateSearchQuery(searchQuery))
        }
    }

    public init(source: any DulcetDataSource) {
        self.source = source
        let initialSnapshot = source.currentSnapshot
        snapshot = initialSnapshot
        selectedDestination = initialSnapshot.selectedDestination
        searchQuery = initialSnapshot.searchQuery

        source.setSnapshotHandler { [weak self] snapshot in
            self?.receive(snapshot)
        }
    }

    private func receive(_ snapshot: DulcetSnapshot) {
        isApplyingSourceSnapshot = true
        self.snapshot = snapshot
        selectedDestination = snapshot.selectedDestination
        searchQuery = snapshot.searchQuery
        isApplyingSourceSnapshot = false
    }
}
