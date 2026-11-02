import Foundation

/// Observes OS privacy only while Connections is visible. A generic route outage never means
/// Local Network access was denied. Results are scoped to one owner, address and actual check.
@MainActor
public final class DulcetConnectionAccessPresentation {
    private struct Key: Equatable {
        let owner: DulcetConnectionOwnership
        let address: String
        let checkedAt: Date
    }
    private final class Entry {
        let key: Key
        var denied = false
        var watch: (any DulcetLocalNetworkAccessWatch)?
        init(_ key: Key) { self.key = key }
    }
    private let probe: any DulcetLocalNetworkAccessProbing
    private var entries: [String: Entry] = [:]

    public init(probe: (any DulcetLocalNetworkAccessProbing)? = nil) {
        self.probe = probe ?? DulcetNetworkLocalNetworkAccessProbe()
    }

    public func refresh(_ state: DulcetConnectionsState, changed: @escaping @MainActor () -> Void) -> DulcetConnectionsState {
        let pending = Dictionary(uniqueKeysWithValues: state.routes.compactMap { row -> (String, Key)? in
            guard !row.removed, row.checkResult == "Unavailable", let checkedAt = row.checkedAt else { return nil }
            return (row.id, Key(owner: state.ownership, address: row.address, checkedAt: checkedAt))
        })
        for (id, entry) in entries where pending[id] != entry.key {
            entries[id] = nil
            entry.watch?.cancel()
        }
        for (id, key) in pending where entries[id] == nil {
            let entry = Entry(key)
            entries[id] = entry // Install the fence before a probe that may answer synchronously.
            entry.watch = probe.watch(serverURL: key.address) { [weak self, weak entry] access in
                guard let self, let entry, self.entries[id] === entry else { return }
                let denied = access == .denied
                guard denied != entry.denied else { return }
                entry.denied = denied
                changed()
            }
        }
        return DulcetConnectionsState(ownership: state.ownership, routes: state.routes.map { row in
            DulcetConnectionRow(id: row.id, address: row.address, label: row.label, enabled: row.enabled,
                removed: row.removed, selected: row.selected, checkResult: row.checkResult, checkedAt: row.checkedAt,
                localNetworkAccessDenied: entries[row.id]?.denied == true)
        }, enrolled: state.enrolled, canUpgrade: state.canUpgrade, error: state.error)
    }

    public func cancel() {
        let old = entries
        entries = [:]
        for entry in old.values { entry.watch?.cancel() }
    }
}
