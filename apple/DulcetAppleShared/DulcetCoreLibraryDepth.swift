import DulcetCore
import DulcetKit
import Foundation

extension DulcetCoreLibraryReader: DulcetLibraryDepthReading {
    func readLibraryDepth(_ completion: @escaping @MainActor (DulcetLibraryDepthState) -> Void) {
        client.libraryDepthState { state in
            let categories = ["albums", "artists", "songs", "playlists", "downloaded", "myLibrary.recent", "myLibrary.albums", "myLibrary.artists", "myLibrary.songs", "myLibrary.playlists"]
            var pins = Set<String>()
            for index in 0..<state.pinCount {
                pins.insert("\(state.pinKind(index: index))\u{0}\(state.pinRawId(index: index))")
            }
            let copy = DulcetLibraryDepthState(
                sorts: Dictionary(uniqueKeysWithValues: categories.map { ($0, state.sort(category: $0)) }),
                filters: Dictionary(uniqueKeysWithValues: categories.map { ($0, state.filter(category: $0)) }),
                pins: pins,
                recentSearches: (0..<state.recentSearchCount).map { state.recentSearch(index: $0) },
                errorKind: state.errorKind
            )
            MainActor.assumeIsolated { completion(copy) }
        }
    }

    func setLibraryCategory(_ category: String, sort: String, filter: String) -> Bool {
        client.setLibraryCategory(category: category, sort: sort, filter: filter)
    }
    func setLibraryPin(_ item: DulcetReaderItem, pinned: Bool) -> Bool {
        client.setLibraryPin(kind: item.kind.rawValue, rawId: item.id.rawID, pinned: pinned)
    }
    func rememberSearch(_ text: String) -> Bool { client.recordSearch(text: text) }
    func clearRecentSearches() -> Bool { client.clearRecentSearches() }
}
