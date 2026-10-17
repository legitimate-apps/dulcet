import Foundation

/// Device preferences for one server account. Pinning does not add music to My Library.
public struct DulcetLibraryDepthState: Sendable {
    public var sorts: [String: String]
    public var filters: [String: String]
    public var pins: Set<String>
    public var recentSearches: [String]
    public var error: DulcetReaderErrorKind?

    public init(sorts: [String: String] = [:], filters: [String: String] = [:], pins: Set<String> = [], recentSearches: [String] = [], errorKind: String? = nil) {
        self.sorts = sorts; self.filters = filters; self.pins = pins; self.recentSearches = recentSearches
        error = errorKind.map { DulcetReaderErrorKind(coreName: $0) }
    }
    public func isPinned(_ item: DulcetReaderItem) -> Bool { pins.contains("\(item.kind.rawValue)\u{0}\(item.id.rawID)") }
}

@MainActor
public protocol DulcetLibraryDepthReading: AnyObject {
    func readLibraryDepth(_ completion: @escaping @MainActor (DulcetLibraryDepthState) -> Void)
    func setLibraryCategory(_ category: String, sort: String, filter: String) -> Bool
    func setLibraryPin(_ item: DulcetReaderItem, pinned: Bool) -> Bool
    func rememberSearch(_ text: String) -> Bool
    func clearRecentSearches() -> Bool
}

public extension DulcetLibrarySession {
    func refreshLibraryDepth() {
        guard let depth = reader as? any DulcetLibraryDepthReading else { return }
        let generation = readerGeneration
        libraryDepthReadGeneration += 1
        let readGeneration = libraryDepthReadGeneration
        depth.readLibraryDepth { [weak self] state in
            guard let self, self.readerGeneration == generation,
                  self.libraryDepthReadGeneration == readGeneration else { return }
            if let error = state.error {
                self.post(DulcetStrings.readerChangeNotSaved(DulcetStrings.readerErrorPhrase(error)))
                return
            }
            self.libraryDepth = state
            self.libraryDepthRevision += 1
        }
    }

    func setLibraryCategory(_ category: String, sort: String? = nil, filter: String? = nil) {
        guard let depth = reader as? any DulcetLibraryDepthReading else { return }
        let newSort = sort ?? libraryDepth.sorts[category] ?? "title"
        let newFilter = filter ?? libraryDepth.filters[category] ?? "all"
        if depth.setLibraryCategory(category, sort: newSort, filter: newFilter) {
            // The reader accepted the write. Keep the next gesture based on that write,
            // even when the confirming read has not returned yet.
            libraryDepth.sorts[category] = newSort
            libraryDepth.filters[category] = newFilter
            libraryDepthRevision += 1
            refreshLibraryDepth()
        } else { post(DulcetStrings.readerChangeNotRecorded) }
    }

    func toggleLibraryPin(_ item: DulcetReaderItem) {
        guard account?.providerInstanceID == item.id.providerInstanceID,
              !(item.kind == .playlist && item.local),
              let depth = reader as? any DulcetLibraryDepthReading else { return }
        let pinned = !libraryDepth.isPinned(item)
        if depth.setLibraryPin(item, pinned: pinned) {
            let key = "\(item.kind.rawValue)\u{0}\(item.id.rawID)"
            if pinned { libraryDepth.pins.insert(key) } else { libraryDepth.pins.remove(key) }
            libraryDepthRevision += 1
            refreshLibraryDepth()
        }
        else { post(DulcetStrings.readerChangeNotRecorded) }
    }

    func rememberSearch(_ text: String) {
        guard let depth = reader as? any DulcetLibraryDepthReading, depth.rememberSearch(text) else { return }
        refreshLibraryDepth()
    }

    func shuffleLibrarySongs(using store: DulcetPresentationStore) {
        let query: DulcetLibraryQuery = isOnline ? .shuffleSongs : .songs
        resolveTracks(of: query, waitForFresh: isOnline) { [weak self, weak store] tracks, _ in
            guard let self, let store else { return }
            guard !tracks.isEmpty else {
                self.post(DulcetStrings.readerPlaysOnReconnect)
                return
            }
            store.playReaderTracks(tracks, shuffle: true, sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.librarySongs)
        }
    }

    func clearRecentSearches() {
        guard let depth = reader as? any DulcetLibraryDepthReading, depth.clearRecentSearches() else { return }
        refreshLibraryDepth()
    }
}

#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

struct DulcetLibraryPinControl: View {
    @Environment(DulcetPresentationStore.self) private var store
    let item: DulcetReaderItem
    var menu = false

    var body: some View {
        if item.kind != .genre, !(item.kind == .playlist && item.local), let session = store.librarySession {
            let pinned = session.libraryDepth.isPinned(item)
            Button { session.toggleLibraryPin(item) } label: {
                if menu { Label(pinned ? DulcetStrings.libraryUnpin : DulcetStrings.libraryPin, systemImage: pinned ? "pin.slash" : "pin") }
                else { Image(systemName: pinned ? "pin.fill" : "pin") }
            }
            .buttonStyle(.borderless)
            .frame(minWidth: menu ? nil : 36, minHeight: menu ? nil : 36)
            .accessibilityLabel(DulcetStrings.libraryPinAccessibility(pinned: pinned, title: item.displayTitle))
            .accessibilityIdentifier("dulcet.pin.\(item.kind.rawValue).\(item.id.rawID)")
        }
    }
}

/// The same entry points are visible on a phone, sidebar-sized window and television.
struct DulcetLibraryDepthHome: View {
    @Environment(DulcetPresentationStore.self) private var store
    let width: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if !(store.librarySession?.libraryDepth.pins.isEmpty ?? true) {
                Text(DulcetStrings.libraryPinned).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                DulcetReaderScreen(query: .pinned) { model in
                    if let window = model.window {
                        ForEach(window.items) { item in
                            if item.kind == .track {
                                DulcetReaderTrackRow(item: item, index: 0, showsIndex: false) { track in
                                    store.playReaderTracks([track], sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.libraryPinned)
                                }
                            } else { DulcetReaderListRow(item: item) }
                        }
                    }
                }
                .id(store.librarySession?.libraryDepthRevision)
                Text(DulcetStrings.libraryPinsDevice)
                    .font(.caption).dulcetForeground(.secondaryTextOnWindow)
            }
            DulcetReaderHomeLink(title: store.myLibraryAvailable ? DulcetStrings.libraryServerSongs : DulcetStrings.librarySongs, symbolName: "music.note", identifier: "dulcet.library.songs") {
                store.pushReaderPage(.songs)
            }
            if store.downloadsEnabled {
                DulcetReaderHomeLink(title: DulcetStrings.libraryDownloaded, symbolName: "arrow.down.circle", identifier: "dulcet.library.downloaded") {
                    store.pushReaderPage(.downloaded)
                }
            }
            Button(store.myLibraryAvailable ? DulcetStrings.libraryShuffleServer : DulcetStrings.libraryShuffleAll, systemImage: "shuffle") {
                store.librarySession?.shuffleLibrarySongs(using: store)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("dulcet.library.shuffleAll")
            Text(DulcetStrings.libraryRandomQueueHint).font(.caption).dulcetForeground(.secondaryTextOnWindow)
        }
    }
}

struct DulcetLibraryCategoryControls: View {
    @Environment(DulcetPresentationStore.self) private var store
    let category: String
    var albumSort = false
    var offersArtist = true

    var body: some View {
        if let session = store.librarySession {
            let sort = Binding(get: { session.libraryDepth.sorts[category] ?? "title" },
                               set: { session.setLibraryCategory(category, sort: $0) })
            let filter = Binding(get: { session.libraryDepth.filters[category] ?? "all" },
                                 set: { session.setLibraryCategory(category, filter: $0) })
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                ViewThatFits(in: .horizontal) {
                    HStack { sortPicker(sort); categoryFilter(filter) }
                    VStack(alignment: .leading) { sortPicker(sort); categoryFilter(filter) }
                }
                if albumSort && sort.wrappedValue == "releaseDate" {
                    Text(DulcetStrings.libraryUndatedAlbums).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                }
            }
        }
    }

    private func sortPicker(_ binding: Binding<String>) -> some View {
        Picker(DulcetStrings.readerSortBy, selection: binding) {
            Text(DulcetStrings.readerSortTitle).tag("title")
            if offersArtist {
                Text(DulcetStrings.readerSortArtist).tag("artist")
                Text(DulcetStrings.libraryReleaseDate).tag("releaseDate")
                Text(DulcetStrings.readerSortMostPlayed).tag("mostPlayed")
            } else {
                Text(DulcetStrings.librarySortDescending).tag("titleDescending")
            }
            if albumSort || category.hasPrefix("myLibrary.") {
                Text(DulcetStrings.readerSortRecentlyAdded).tag("recentlyAdded")
            }
        }
        .accessibilityIdentifier("dulcet.library.\(category).sort")
    }

    @ViewBuilder
    private func categoryFilter(_ binding: Binding<String>) -> some View {
        if category.hasSuffix("playlists") {
            TextField(DulcetStrings.libraryFilter, text: Binding(get: { binding.wrappedValue == "all" ? "" : binding.wrappedValue },
                                                                set: { binding.wrappedValue = $0.isEmpty ? "all" : $0 }))
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("dulcet.library.\(category).filter")
        } else { filterPicker(binding) }
    }

    private func filterPicker(_ binding: Binding<String>) -> some View {
        Picker(DulcetStrings.libraryFilter, selection: binding) {
            Text(DulcetStrings.libraryAll).tag("all")
            Text(DulcetStrings.libraryFilterFavorites).tag("favorites")
            if store.downloadsEnabled { Text(DulcetStrings.libraryDownloaded).tag("downloaded") }
        }
        .accessibilityIdentifier("dulcet.library.\(category).filter")
    }
}

struct DulcetLibrarySongsPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let downloaded: Bool
    private var title: String { downloaded ? DulcetStrings.libraryDownloaded : store.myLibraryAvailable ? DulcetStrings.libraryServerSongs : DulcetStrings.librarySongs }

    var body: some View {
        DulcetReaderScreen(query: downloaded ? .downloaded : .songs) { model in
            DulcetReaderPage(title: title) { _ in
                Text(downloaded ? DulcetStrings.libraryDownloadedHint : DulcetStrings.librarySongsHint)
                    .font(.subheadline).dulcetForeground(.secondaryTextOnWindow)
                DulcetLibraryCategoryControls(category: downloaded ? "downloaded" : "songs")
                DulcetReaderListBody(model: model) { window in
                    Button(DulcetStrings.libraryShuffleAll, systemImage: "shuffle") {
                        if downloaded {
                            store.playReaderTracks(window.playableTracks, shuffle: true, sourceKind: .library, sourceID: nil, sourceName: title)
                        } else { store.librarySession?.shuffleLibrarySongs(using: store) }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(window.playableTracks.isEmpty)
                    .accessibilityIdentifier("dulcet.songs.shuffleAll")
                    ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                        DulcetReaderTrackRow(item: item, index: index + 1) { track in
                            store.playReaderTracks(window.playableTracks, startingAt: track, sourceKind: .library, sourceID: nil, sourceName: title)
                        }
                        .dulcetReaderRow(index, in: model)
                    }
                }
            }
        }
    }
}
struct DulcetArtistTopSongs: View {
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID

    var body: some View {
        DulcetReaderScreen(query: .artistTopSongs(rawID: id.rawID)) { model in
            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                Text(DulcetStrings.libraryTopSongs).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Text(DulcetStrings.libraryTopSongsHint)
                    .font(.caption).dulcetForeground(.secondaryTextOnWindow)
                DulcetReaderListBody(model: model) { window in
                    ForEach(Array(window.items.prefix(10).enumerated()), id: \.element.id) { index, item in
                        DulcetReaderTrackRow(item: item, index: index + 1) { track in
                            store.playReaderTracks(window.playableTracks, startingAt: track, sourceKind: .library,
                                                   sourceID: id, sourceName: DulcetStrings.libraryTopSongs)
                        }
                    }
                }
            }
        }
        .accessibilityIdentifier("dulcet.artist.topSongs")
    }
}

struct DulcetArtistDiscography: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let items: [DulcetReaderItem]
    let width: CGFloat

    private let groups = ["albums", "epsSingles", "compilations", "appearsOn"]
    private func title(_ group: String) -> String {
        switch group {
        case "epsSingles": DulcetStrings.libraryEpsSingles
        case "compilations": DulcetStrings.libraryCompilations
        case "appearsOn": DulcetStrings.libraryAppearsOn
        default: DulcetStrings.readerAlbums
        }
    }

    var body: some View {
        let tileWidth: CGFloat = dynamicTypeSize.isAccessibilitySize ? 260 : (width < 500 ? 150 : 180)
        ForEach(groups, id: \.self) { group in
            let albums = items.filter { ($0.discographyGroup ?? "albums") == group }
            if !albums.isEmpty {
                Text(title(group)).font(.headline).accessibilityAddTraits(.isHeader)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: tileWidth), alignment: .topLeading)], alignment: .leading) {
                    ForEach(albums) { item in
                        DulcetReaderTile(item: item, width: tileWidth,
                                         subtitle: item.originalReleaseDate ?? item.year.map(String.init) ?? DulcetStrings.libraryDateUnknown)
                    }
                }
#if os(tvOS)
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
#endif
            }
        }
    }
}
#endif
