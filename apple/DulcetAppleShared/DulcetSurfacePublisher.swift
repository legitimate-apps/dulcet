import DulcetKit
import Foundation
import Observation
#if !os(tvOS)
import WidgetKit
#endif

/// Publishes only when displayed metadata changes, never on the player's position samples.
@MainActor
final class DulcetSurfacePublisher {
    static let shared = DulcetSurfacePublisher()
    private weak var store: DulcetPresentationStore?
    private var previous: DulcetSurfaceSnapshot?
    private var accountID: String?
    func attach(_ store: DulcetPresentationStore) { self.store = store; observe() }
    private func observe() {
        withObservationTracking { refresh() } onChange: {
            Task { @MainActor [weak self] in self?.observe() }
        }
    }
    private func refresh() {
        guard let store else { return }
        let provider = store.librarySession?.account?.providerInstanceID
        #if !os(tvOS)
        let defaults = Bundle.main.bundleIdentifier.flatMap { UserDefaults(suiteName: DulcetSurfaceSnapshot.groupID(bundleID: $0)) }
        if previous == nil, defaults?.string(forKey: "dulcet.surface.account") == provider, provider != nil {
            previous = .read(from: defaults)
            accountID = provider
        }
        #endif
        if accountID != provider { previous = nil; accountID = provider }
        let now = provider == nil ? nil : store.snapshot.nowPlaying
        var value = DulcetSurfaceSnapshot(
            title: now?.current.title ?? "Dulcet",
            subtitle: now?.current.albumTitle ?? "Open your music library",
            isPlaying: now?.isPlaying ?? false,
            updatedAt: previous?.updatedAt ?? Date(),
            recent: store.librarySession?.account == nil ? [] : previous?.recent ?? []
        )
        if let current = now?.current {
            let id = current.id.providerInstanceID + ":" + current.id.rawID
            value.recent.removeAll { $0.id == id }
            value.recent.insert(.init(id: id, title: current.title, providerID: current.id.providerInstanceID, rawID: current.id.rawID), at: 0)
            value.recent = Array(value.recent.prefix(4))
        }
        guard value != previous else { return }
        value.updatedAt = Date()
        previous = value
        #if !os(tvOS)
        guard let bundleID = Bundle.main.bundleIdentifier,
              ["com.legitimateapps.dulcet", "com.legitimateapps.dulcet.dev"].contains(bundleID) else { return }
        let group = DulcetSurfaceSnapshot.groupID(bundleID: bundleID)
        let sharedDefaults = UserDefaults(suiteName: group)
        value.write(to: sharedDefaults)
        sharedDefaults?.set(provider, forKey: "dulcet.surface.account")
        WidgetCenter.shared.reloadTimelines(ofKind: "DulcetNowPlaying")
        WidgetCenter.shared.reloadTimelines(ofKind: "DulcetRecent")
        #endif
    }
}
