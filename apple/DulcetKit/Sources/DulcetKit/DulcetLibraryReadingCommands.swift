#if os(macOS)
import SwiftUI

/// The Mac's Library menu: every library section by Command-number, in sidebar order, Back by
/// Command-[ as in any browser of pages, and the playing song's heart. Each item does exactly
/// what its on-screen counterpart does, and is disabled when that counterpart is absent.
public struct DulcetLibraryCommands: Commands {
    private let store: DulcetPresentationStore

    public init(store: DulcetPresentationStore) {
        self.store = store
    }

    public var body: some Commands {
        CommandMenu(DulcetStrings.library) {
            ForEach(Array(DulcetLibrarySection.allCases.enumerated()), id: \.element) { index, section in
                Button(section.title) {
                    store.selectLibrarySection(section)
                }
                .keyboardShortcut(KeyEquivalent(Character(String(index + 1))), modifiers: .command)
                .disabled(!store.readerOwnsLibrary)
            }

            Divider()

            Button(DulcetStrings.menuBack) {
                store.goBackInLibrary()
            }
            .keyboardShortcut("[", modifiers: .command)
            .disabled(!store.canGoBackInLibrary)

            Divider()

            Button(favourite?.isFavourite == true
                ? DulcetStrings.menuUnfavoritePlaying
                : DulcetStrings.menuFavoritePlaying) {
                store.togglePlayingTrackFavourite()
            }
            .disabled(favourite == nil)
        }
    }

    private var favourite: (target: DulcetFavouriteTarget, isFavourite: Bool)? {
        store.playingTrackFavourite
    }
}
#endif
