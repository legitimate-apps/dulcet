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
            .buttonStyle(.dulcetProminent)
            .accessibilityIdentifier("dulcet.library.shuffleAll")
            Text(DulcetStrings.libraryRandomQueueHint).font(.caption).dulcetForeground(.secondaryTextOnWindow)
        }
    }
}

struct DulcetLibraryCategoryControls: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
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
                    if dynamicTypeSize.isAccessibilitySize {
                        // Stacked, each control at its own width: side by side, a flexible filter
                        // squeezed the sort button until "Title" broke as "Ti-tle" and ran into
                        // the row below (round 11 XXXL Artists and Playlists).
                        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                            sortPicker(sort).fixedSize()
                            if category.hasSuffix("playlists") {
                                categoryFilter(filter)
                            } else {
                                categoryFilter(filter).fixedSize()
                            }
                        }
                        .padding(.bottom, DulcetSpacing.xs)
                    } else {
                        ViewThatFits(in: .horizontal) {
                            HStack { sortPicker(sort); categoryFilter(filter) }
                            VStack(alignment: .leading) { sortPicker(sort); categoryFilter(filter) }
                        }
                    }
                    if albumSort && sort.wrappedValue == "releaseDate" {
                        Text(DulcetStrings.libraryUndatedAlbums).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                    }
                }
#if os(iOS)
                // Inside a `List` cell a menu picker draws as a settings row, its label ("Sort
                // By") at the leading edge and the value at the trailing one. Outside one it shows
                // only the value; hidden labels keep that in both, and stay the accessibility label.
                .labelsHidden()
#endif
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

    private var scrollsStatus: Bool {
#if os(macOS)
        downloaded
#else
        false
#endif
    }

    var body: some View {
        DulcetReaderScreen(query: downloaded ? .downloaded : .songs, showsDestinationStatus: !scrollsStatus) { model in
            DulcetReaderListPage(title: title, freshness: model.window?.freshness) { _ in
                // Keep the Downloaded status in the scrolling document. Its intrinsic text
                // height must not become a minimum height for the Mac split-view column.
                DulcetReaderListHeader {
                    if scrollsStatus { DulcetReaderStatusView(window: model.window, localCollection: true) }
                    Text(downloaded ? DulcetStrings.libraryDownloadedHint : DulcetStrings.librarySongsHint)
                        .font(.subheadline).dulcetForeground(.secondaryTextOnWindow)
                    DulcetLibraryCategoryControls(category: downloaded ? "downloaded" : "songs")
                    Button(DulcetStrings.libraryShuffleAll, systemImage: "shuffle") {
                        if downloaded {
                            store.playReaderTracks(model.window?.playableTracks ?? [], shuffle: true, sourceKind: .library, sourceID: nil, sourceName: title)
                        } else { store.librarySession?.shuffleLibrarySongs(using: store) }
                    }
                    .buttonStyle(.dulcetProminent)
                    .disabled((downloaded || store.librarySession?.isOnline != true) && (model.window?.playableTracks.isEmpty ?? true))
                    .accessibilityIdentifier("dulcet.songs.shuffleAll")
                    if !downloaded && store.librarySession?.isOnline == true {
                        Text(DulcetStrings.libraryRandomQueueHint).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                    }
                }
#if os(iOS)
                DulcetReaderListRows(model: model) { window in
                    songRows(window, model: model)
                }
#else
                DulcetReaderListBody(model: model) { window in
                    // Row appearances drive paging: offscreen songs must not report themselves
                    // as the viewport and eagerly extend the window to the whole catalogue.
                    LazyVStack(alignment: .leading, spacing: 0) {
                        songRows(window, model: model)
                    }
                }
#endif
            }
        }
    }

    @ViewBuilder
    private func songRows(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
        ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
            DulcetReaderTrackRow(item: item, index: index + 1, showsLibraryQuickAction: !downloaded) { track in
                store.playReaderTracks(window.playableTracks, startingAt: track, sourceKind: .library, sourceID: nil, sourceName: title)
            }
            .dulcetReaderRow(index, in: model)
            .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: true, artwork: true)
        }
    }
}
/// The ways Downloaded is browsed, as Music's Downloaded is.
enum DulcetDownloadedSection: String, CaseIterable, Identifiable {
    case albums, artists, playlists, songs
    var id: String { rawValue }
    var title: String {
        switch self {
        case .albums: DulcetStrings.readerAlbums
        case .artists: DulcetStrings.readerArtists
        case .playlists: DulcetStrings.readerPlaylists
        case .songs: DulcetStrings.readerSongs
        }
    }
    var query: DulcetLibraryQuery {
        switch self {
        case .albums: .downloadedAlbums
        case .artists: .downloadedArtists
        case .playlists: .downloadedPlaylists
        case .songs: .downloaded
        }
    }
}

/// Library › Downloaded: what is on this device, by album, artist, playlist or song, with Shuffle
/// All over every downloaded song. It never states the server library's freshness.
struct DulcetDownloadedPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    @SceneStorage("dulcet.downloaded.section") private var section = DulcetDownloadedSection.albums
    private var title: String { DulcetStrings.libraryDownloaded }

    var body: some View {
        DulcetReaderScreen(query: .downloaded) { songs in
            DulcetReaderListPage(title: title, freshness: songs.window?.freshness) { width in
                DulcetReaderListHeader {
                    Text(DulcetStrings.libraryDownloadedHint)
                        .font(.subheadline).dulcetForeground(.secondaryTextOnWindow)
                    Button(DulcetStrings.libraryShuffleAll, systemImage: "shuffle") {
                        store.playReaderTracks(songs.window?.playableTracks ?? [], shuffle: true, sourceKind: .library, sourceID: nil, sourceName: title)
                    }
                    .buttonStyle(.dulcetProminent)
                    .disabled(songs.window?.playableTracks.isEmpty ?? true)
                    .accessibilityIdentifier("dulcet.songs.shuffleAll")
                    Picker(title, selection: $section) {
                        ForEach(DulcetDownloadedSection.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .accessibilityIdentifier("dulcet.downloaded.section")
                    if section == .songs {
                        DulcetLibraryCategoryControls(category: "downloaded")
                    }
                }
                switch section {
                case .songs:
                    songList(songs)
                default:
                    DulcetDownloadedCollection(section: section, width: width)
                        .id(section)
                }
            }
        }
    }

    @ViewBuilder
    private func songList(_ model: DulcetLibraryWindowModel) -> some View {
#if os(iOS)
        DulcetReaderListRows(model: model, emptyState: Self.emptyState) { window in
            downloadedSongRows(window, model: model)
        }
#else
        DulcetReaderListBody(model: model, emptyState: Self.emptyState) { window in
            LazyVStack(alignment: .leading, spacing: 0) {
                downloadedSongRows(window, model: model)
            }
        }
#endif
    }

    @ViewBuilder
    private func downloadedSongRows(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel) -> some View {
        ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
            DulcetReaderTrackRow(item: item, index: index + 1) { track in
                store.playReaderTracks(window.playableTracks, startingAt: track, sourceKind: .library, sourceID: nil, sourceName: title)
            }
            .dulcetReaderRow(index, in: model)
            .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: true, artwork: true)
        }
    }

    static let emptyState = DulcetReaderEmptyState(title: DulcetStrings.downloadedEmptyTitle,
        hint: DulcetStrings.downloadedEmptyHint, systemImage: "arrow.down.circle")
}

/// Downloaded albums or playlists as tiles, artists as rows.
private struct DulcetDownloadedCollection: View {
    let section: DulcetDownloadedSection
    let width: CGFloat

    var body: some View {
        DulcetReaderScreen(query: section.query) { model in
            DulcetReaderListBody(model: model, emptyState: DulcetDownloadedPage.emptyState) { window in
                if section == .artists {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                            DulcetReaderListRow(item: item)
                                .dulcetReaderRow(index, in: model)
                            DulcetTrackListSeparator()
                        }
                    }
                } else {
                    DulcetReaderGrid(model: model, width: width)
                }
            }
        }
    }
}

enum DulcetArtistTopSongs {
    static let displayLimit = 10

    /// Top Songs shows itself only once it has a song to show.
    nonisolated static func showsSection(_ window: DulcetLibraryWindow?) -> Bool {
        guard let window else { return false }
        return !window.items.isEmpty
    }
}

/// Top Songs drawn from a subscription the page already holds.
struct DulcetArtistTopSongsSection: View {
    @Environment(DulcetPresentationStore.self) private var store
    let id: DulcetProviderItemID
    let model: DulcetLibraryWindowModel

    var body: some View {
        // An artist page leaves out a section with nothing in it, as Music does: no heading
        // over a spinner or an empty-state view in the middle of a page that has albums.
        if DulcetArtistTopSongs.showsSection(model.window) {
            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                Text(DulcetStrings.libraryTopSongs).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                DulcetReaderListBody(model: model, displayLimit: DulcetArtistTopSongs.displayLimit) { window in
                    // One row pitch with every other song list: no stack spacing between rows.
                    VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(window.items.prefix(DulcetArtistTopSongs.displayLimit).enumerated()), id: \.element.id) { index, item in
                        DulcetReaderTrackRow(item: item, index: index + 1) { track in
                            store.playReaderTracks(window.playableTracks, startingAt: track, sourceKind: .artist,
                                                   sourceID: id, sourceName: DulcetStrings.libraryTopSongs)
                        }
                        .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: true, artwork: true)
                    }
                    }
                }
            }
            .padding(.bottom, DulcetSpacing.md)
        }
    }
}

struct DulcetArtistDiscography: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let items: [DulcetReaderItem]
    let width: CGFloat
    /// The width the tiles share, when the page has already taken its insets off `width`.
    var contentWidth: CGFloat? = nil

    private static let albumsGroup = "albums"
    private static let groups = [albumsGroup, "epsSingles", "compilations", "appearsOn"]

    private static func group(for item: DulcetReaderItem) -> String {
        item.discographyGroup ?? albumsGroup
    }

    static func albumCountLabel(_ albums: [DulcetReaderItem]) -> String? {
        let count = albums.count { group(for: $0) == albumsGroup }
        return count > 0 ? DulcetStrings.readerCount(.albums, count) : nil
    }
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
        ForEach(Self.groups, id: \.self) { group in
            let albums = items.filter { Self.group(for: $0) == group }
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
#if os(tvOS)
        // Beside an artist's short column of albums, a second line beats a shortened name.
        let titleLineLimit = 2
#else
        let titleLineLimit = 1
#endif
        return DulcetReaderTile(item: item, width: width,
                                subtitle: item.originalReleaseDate ?? item.year.map(String.init) ?? DulcetStrings.libraryDateUnknown,
                                titleLineLimit: titleLineLimit)
    }
}
#endif
