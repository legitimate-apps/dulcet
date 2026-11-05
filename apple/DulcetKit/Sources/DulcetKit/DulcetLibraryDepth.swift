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
    /// The glyph's size beside a header's heart, so the two read as one row of controls.
    var size: Font = .body

    var body: some View {
        if item.kind != .genre, !(item.kind == .playlist && item.local), let session = store.librarySession {
            let pinned = session.libraryDepth.isPinned(item)
            Button { session.toggleLibraryPin(item) } label: {
                if menu { Label(pinned ? DulcetStrings.libraryUnpin : DulcetStrings.libraryPin, systemImage: pinned ? "pin.slash" : "pin") }
                else {
                    // The heart beside it is drawn in the accent; a header's controls share a tone.
                    Image(systemName: pinned ? "pin.fill" : "pin")
                        .font(size)
                        .dulcetForeground(.accentIconOnWindow)
                }
            }
            .buttonStyle(.borderless)
            .frame(minWidth: menu ? nil : 36, minHeight: menu ? nil : 36)
            .accessibilityLabel(DulcetStrings.libraryPinAccessibility(pinned: pinned, title: item.displayTitle))
            .accessibilityIdentifier("dulcet.pin.\(item.kind.rawValue).\(item.id.rawID)")
        }
    }
}

/// Device pins remain a personal collection, independent of server membership.
struct DulcetPinnedLibraryItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if !(store.librarySession?.libraryDepth.pins.isEmpty ?? true) {
                Text(DulcetStrings.libraryPinned).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("dulcet.library.pinned.title")
                DulcetReaderScreen(query: .pinned) { model in
                    // Mount a real view before the first window so ReaderScreen's onAppear
                    // opens the subscription even when there are no published rows yet.
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        if let window = model.window {
                            ForEach(window.items) { item in
                                if item.kind == .track {
                                    DulcetReaderTrackRow(item: item, index: 0, showsIndex: false) { track in
                                        store.playReaderTracks([track], sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.libraryPinned)
                                    }
                                } else { DulcetReaderListRow(item: item) }
                            }
                        }
                        if model.window == nil { ProgressView().controlSize(.small) }
                    }
                }
                .id(store.librarySession?.libraryDepthRevision)
            }
        }
    }
}

/// The same entry points are visible on a phone, sidebar-sized window and television.
struct DulcetLibraryDepthHome: View {
    @Environment(DulcetPresentationStore.self) private var store
    let width: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            DulcetPinnedLibraryItems()
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
    var menuPresentation = false

    var body: some View {
        if let session = store.librarySession {
            let sort = Binding(get: { session.libraryDepth.sorts[category] ?? "title" },
                               set: { session.setLibraryCategory(category, sort: $0) })
            let filter = Binding(get: { session.libraryDepth.filters[category] ?? "all" },
                                 set: { session.setLibraryCategory(category, filter: $0) })
#if os(tvOS)
            tvControls(sort: sort, filter: filter)
#else
            if menuPresentation {
                // The menu already says what it holds; an inline picker's own label would repeat
                // it as a header inside (Music's Sort menu has none).
                Menu {
                    sortChoices(sort).pickerStyle(.inline).labelsHidden()
                } label: { Text(DulcetStrings.readerSortBy) }
                .accessibilityIdentifier("dulcet.library.\(category).sort")
                .accessibilityValue(sortTitle(sort.wrappedValue))
                Menu {
                    filterChoices(filter).pickerStyle(.inline).labelsHidden()
                } label: { Text(DulcetStrings.libraryFilter) }
                .accessibilityIdentifier("dulcet.library.\(category).filter")
            } else {
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
#endif
        }
    }

#if os(tvOS)
    /// Every library list sorts and filters the same way on Apple TV: a pill in the page naming what
    /// is chosen, opening its choices. Horizontal presses on a segmented control would change the
    /// sort on the way past it.
    @ViewBuilder
    private func tvControls(sort: Binding<String>, filter: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            HStack(spacing: DulcetSpacing.sm) {
                Menu {
                    sortChoices(sort).pickerStyle(.inline).labelsHidden()
                } label: { Label(sortTitle(sort.wrappedValue), systemImage: "arrow.up.arrow.down") }
                .menuStyle(.button)
                .buttonStyle(DulcetTVActionButtonStyle())
                .accessibilityLabel(DulcetStrings.readerSortBy)
                .accessibilityValue(sortTitle(sort.wrappedValue))
                .accessibilityIdentifier("dulcet.library.\(category).sort")
                if category.hasSuffix("playlists") {
                    categoryFilter(filter)
                } else {
                    Menu {
                        filterChoices(filter).pickerStyle(.inline).labelsHidden()
                    } label: { Label(filterTitle(filter.wrappedValue), systemImage: "line.3.horizontal.decrease") }
                    .menuStyle(.button)
                    .buttonStyle(DulcetTVActionButtonStyle())
                    .accessibilityLabel(DulcetStrings.libraryFilter)
                    .accessibilityValue(filterTitle(filter.wrappedValue))
                    .accessibilityIdentifier("dulcet.library.\(category).filter")
                }
            }
            if albumSort && sort.wrappedValue == "releaseDate" {
                Text(DulcetStrings.libraryUndatedAlbums).font(.caption).dulcetForeground(.secondaryTextOnWindow)
            }
        }
        .focusSection()
    }

    private func filterTitle(_ value: String) -> String {
        switch value {
        case "favorites": DulcetStrings.libraryFilterFavorites
        case "downloaded": DulcetStrings.libraryDownloaded
        default: DulcetStrings.libraryAll
        }
    }
#endif

    private func sortPicker(_ binding: Binding<String>) -> some View {
        sortChoices(binding)
            .accessibilityIdentifier("dulcet.library.\(category).sort")
    }

    private func sortChoices(_ binding: Binding<String>) -> some View {
        Picker(DulcetStrings.readerSortBy, selection: binding) {
            Text(titleChoiceLabel).tag("title")
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
    }

    private func sortTitle(_ value: String) -> String {
        switch value {
        case "artist": DulcetStrings.readerSortArtist
        case "releaseDate": DulcetStrings.libraryReleaseDate
        case "mostPlayed": DulcetStrings.readerSortMostPlayed
        case "titleDescending": DulcetStrings.librarySortDescending
        case "recentlyAdded": DulcetStrings.readerSortRecentlyAdded
        default: titleChoiceLabel
        }
    }

    /// An artist or a playlist has a name, on the pill that shows the choice as well as in its menu.
    private var titleChoiceLabel: String {
#if os(tvOS)
        offersArtist ? DulcetStrings.readerSortTitle : DulcetStrings.readerSortName
#else
        DulcetStrings.readerSortTitle
#endif
    }

    @ViewBuilder
    private func categoryFilter(_ binding: Binding<String>) -> some View {
        if category.hasSuffix("playlists") {
            TextField(DulcetStrings.libraryFilter, text: Binding(get: { binding.wrappedValue == "all" ? "" : binding.wrappedValue },
                                                                set: { binding.wrappedValue = $0.isEmpty ? "all" : $0 }))
#if os(tvOS)
                .dulcetTVFieldPlate()
                .frame(maxWidth: 520)
#else
                .textFieldStyle(.roundedBorder)
#endif
                .accessibilityIdentifier("dulcet.library.\(category).filter")
        } else { filterPicker(binding) }
    }

    private func filterPicker(_ binding: Binding<String>) -> some View {
        filterChoices(binding)
            .accessibilityIdentifier("dulcet.library.\(category).filter")
    }

    private func filterChoices(_ binding: Binding<String>) -> some View {
        Picker(DulcetStrings.libraryFilter, selection: binding) {
            Text(DulcetStrings.libraryAll).tag("all")
            Text(DulcetStrings.libraryFilterFavorites).tag("favorites")
            if store.downloadsEnabled && category != "downloaded" { Text(DulcetStrings.libraryDownloaded).tag("downloaded") }
        }
    }
}

struct DulcetLibrarySongsPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let downloaded: Bool
    private var title: String { downloaded ? DulcetStrings.libraryDownloaded : store.myLibraryAvailable ? DulcetStrings.libraryServerSongs : DulcetStrings.librarySongs }

    var body: some View {
        DulcetReaderScreen(query: downloaded ? .downloaded : .songs, showsDestinationStatus: true) { model in
            DulcetReaderPage(title: title) { _ in
                Text(downloaded ? DulcetStrings.libraryDownloadedHint : DulcetStrings.librarySongsHint)
                    .font(.subheadline).dulcetForeground(.secondaryTextOnWindow)
                DulcetLibraryCategoryControls(category: downloaded ? "downloaded" : "songs")
                Button(DulcetStrings.libraryShuffleAll, systemImage: "shuffle") {
                    if downloaded {
                        store.playReaderTracks(model.window?.playableTracks ?? [], shuffle: true, sourceKind: .library, sourceID: nil, sourceName: title)
                    } else { store.librarySession?.shuffleLibrarySongs(using: store) }
                }
                .buttonStyle(.borderedProminent)
                .disabled((downloaded || store.librarySession?.isOnline != true) && (model.window?.playableTracks.isEmpty ?? true))
                .accessibilityIdentifier("dulcet.songs.shuffleAll")
                if !downloaded && store.librarySession?.isOnline == true {
                    Text(DulcetStrings.libraryRandomQueueHint).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                }
                DulcetReaderListBody(model: model) { window in
                    // Row appearances drive paging: offscreen songs must not report themselves
                    // as the viewport and eagerly extend the window to the whole catalogue.
                    LazyVStack(alignment: .leading, spacing: DulcetSpacing.sm) {
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
}
struct DulcetArtistTopSongs: View {
    static let displayLimit = 10
    let id: DulcetProviderItemID

    var body: some View {
        DulcetReaderScreen(query: .artistTopSongs(rawID: id.rawID), showsDestinationStatus: true) { model in
            DulcetArtistTopSongsSection(id: id, model: model)
        }
        .accessibilityIdentifier("dulcet.artist.topSongs")
    }

    /// Top Songs shows itself only once it has a song to show.
    nonisolated static func showsSection(_ window: DulcetLibraryWindow?) -> Bool {
        guard let window else { return false }
        return !window.items.isEmpty
    }
}

/// Top Songs drawn from a subscription the page already holds.
struct DulcetArtistTopSongsSection: View {
    @Environment(DulcetPresentationStore.self) private var store
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
#endif
    let id: DulcetProviderItemID
    let model: DulcetLibraryWindowModel

    var body: some View {
        // An artist page leaves out a section with nothing in it, as Music does: no heading
        // over a spinner or an empty-state view in the middle of a page that has albums.
        if DulcetArtistTopSongs.showsSection(model.window) {
            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                Text(DulcetStrings.libraryTopSongs).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                DulcetReaderListBody(model: model, displayLimit: DulcetArtistTopSongs.displayLimit) { window in
                    ForEach(Array(window.items.prefix(DulcetArtistTopSongs.displayLimit).enumerated()), id: \.element.id) { index, item in
                        DulcetReaderTrackRow(item: item, index: index + 1, showsDuration: !compactWidth) { track in
                            store.playReaderTracks(window.playableTracks, startingAt: track, sourceKind: .library,
                                                   sourceID: id, sourceName: DulcetStrings.libraryTopSongs)
                        }
                    }
                }
            }
            .padding(.bottom, DulcetSpacing.md)
        }
    }

    /// A compact width keeps a row's title and album readable by leaving out the duration, as
    /// Music's Top Songs rows do on iPhone.
    private var compactWidth: Bool {
#if os(iOS)
        horizontalSizeClass == .compact
#else
        false
#endif
    }
}

struct DulcetArtistDiscography: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let items: [DulcetReaderItem]
    let width: CGFloat
    /// The width the tiles share, when the page has already taken its insets off `width`.
    var contentWidth: CGFloat? = nil

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
#if os(tvOS)
        let tileWidth: CGFloat = 260
#endif
        ForEach(groups, id: \.self) { group in
            let albums = items.filter { ($0.discographyGroup ?? "albums") == group }
            if !albums.isEmpty {
                Text(title(group)).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
#if os(tvOS)
                // Keep the first row concrete so its album targets exist while offscreen.
                // Following rows can still be realized as the grid scrolls into the viewport.
                let available = contentWidth ?? (width - DulcetLibraryMetrics.horizontalInset(forWidth: width) * 2)
                let columns = max(1, Int((available + DulcetSpacing.lg) / (tileWidth + DulcetSpacing.lg)))
                VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                    HStack(alignment: .top, spacing: DulcetSpacing.lg) {
                        ForEach(albums.prefix(columns)) { item in
                            albumTile(item, width: tileWidth)
                        }
                    }
                    if albums.count > columns {
                        LazyVGrid(columns: Array(repeating: GridItem(.fixed(tileWidth), spacing: DulcetSpacing.lg,
                                                                     alignment: .topLeading), count: columns),
                                  alignment: .leading, spacing: DulcetSpacing.lg) {
                            ForEach(albums.dropFirst(columns)) { item in
                                albumTile(item, width: tileWidth)
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .focusSection()
#else
                let grid = DulcetResponsiveGridLayout.albums(
                    availableWidth: width - DulcetLibraryMetrics.horizontalInset(forWidth: width) * 2,
                    compact: width < 600, accessibility: dynamicTypeSize.isAccessibilitySize,
                    regularMinimum: DulcetResponsiveGridLayout.platformRegularMinimum
                )
                LazyVGrid(columns: grid.gridItems, alignment: .leading, spacing: DulcetSpacing.md) {
                    ForEach(albums) { item in
                        albumTile(item, width: grid.tileWidth)
                    }
                }
#endif
            }
        }
    }

    private func albumTile(_ item: DulcetReaderItem, width: CGFloat) -> some View {
        DulcetReaderTile(item: item, width: width,
                         subtitle: item.originalReleaseDate ?? item.year.map(String.init) ?? DulcetStrings.libraryDateUnknown)
    }
}
#endif
