import Observation

@MainActor
public protocol DulcetDataSource {
    func snapshot(for state: DulcetFixtureState) -> DulcetSnapshot
}

@MainActor
@Observable
public final class DulcetPresentationStore {
    @ObservationIgnored private let source: any DulcetDataSource

    public private(set) var snapshot: DulcetSnapshot
    public var selectedState: DulcetFixtureState {
        didSet {
            snapshot = source.snapshot(for: selectedState)
            selectedDestination = snapshot.selectedDestination
            searchQuery = snapshot.searchQuery
        }
    }
    public var selectedDestination: DulcetSidebarDestination
    public var searchQuery: String

    public init(
        source: any DulcetDataSource,
        initialState: DulcetFixtureState = .libraryBrowse
    ) {
        self.source = source
        selectedState = initialState
        let initialSnapshot = source.snapshot(for: initialState)
        snapshot = initialSnapshot
        selectedDestination = initialSnapshot.selectedDestination
        searchQuery = initialSnapshot.searchQuery
    }

    public func show(_ state: DulcetFixtureState) {
        selectedState = state
    }
}
