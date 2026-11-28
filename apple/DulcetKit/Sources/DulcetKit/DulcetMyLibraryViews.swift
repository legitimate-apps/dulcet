#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

struct DulcetReaderCollectionRoot: View {
    @Environment(DulcetPresentationStore.self) private var store
    let destination: DulcetSidebarDestination
    var body: some View {
        if destination == .library {
            if store.myLibraryAvailable { DulcetMyLibraryView() }
            else { DulcetPersonalLibraryRoot() }
        } else {
            DulcetReaderLibraryRoot()
                .dulcetNavigationTitle(destination.windowTitle, keepsTVNavigationBar: true)
        }
    }
}

struct DulcetMyLibraryButton: View {
    @Environment(DulcetPresentationStore.self) private var store
    let target: DulcetFavouriteTarget
    var menu = false
    var quickAction = false
    var itemTitle: String? = nil

    var body: some View {
        if let session = store.librarySession, session.myLibrary.availability == .available {
            // Read the observable publication so a membership change invalidates this control.
            let _ = session.myLibrary
            let membership = session.myLibraryMembership(target)
            let on = membership == .added
            let title = on ? DulcetStrings.removeFromLibrary : DulcetStrings.addToLibrary
            let identifier = quickAction ? "dulcet.membership.quick" : "dulcet.membership"
            if quickAction {
                Button { session.toggleMyLibrary(target) } label: {
                    Image(systemName: on ? "checkmark.circle.fill" : "plus.circle")
                        .font(.body.weight(.medium))
                        .frame(minWidth: DulcetMetrics.minimumTouchTarget, minHeight: DulcetMetrics.minimumTouchTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(itemTitle.map { DulcetStrings.readerRowAccessibility(title, $0) } ?? title)
                .accessibilityIdentifier("\(identifier).\(target.kind.rawValue).\(target.id.rawID)")
            } else {
                Button { session.toggleMyLibrary(target) } label: {
                    if menu { Label(title, systemImage: on ? "minus.circle" : "plus.circle") }
                    else { Image(systemName: on ? "checkmark.circle.fill" : "plus.circle") }
                }
                .accessibilityLabel(title)
                .accessibilityIdentifier("\(identifier).\(target.kind.rawValue).\(target.id.rawID)")
            }
            if menu && membership == .partial {
                Button(DulcetStrings.removeFromLibrary, systemImage: "minus.circle") {
                    session.setMyLibraryMembership(target, added: false)
                }
            }
        }
    }
}

extension DulcetMyLibrarySection {
    var title: String {
        switch self {
        case .recent: DulcetStrings.recentlyAdded
        case .artists: DulcetStrings.artists
        case .albums: DulcetStrings.albums
        case .songs: DulcetStrings.myLibrarySongs
        case .playlists: DulcetStrings.readerPlaylists
        }
    }

    /// The leading symbol of the section's row or pill, the same vocabulary Browse uses.
    var symbolName: String {
        switch self {
        case .recent: "clock"
        case .artists: "music.mic"
        case .albums: "square.stack"
        case .songs: "music.note"
        case .playlists: "music.note.list"
        }
    }
}

/// The sync status and failure lines a My Library surface states above its content.
struct DulcetMyLibraryStatusLines: View {
    /// Optional: a host with no store (a render test) draws the failure lines and no indicator.
    @Environment(DulcetPresentationStore.self) private var store: DulcetPresentationStore?
    let library: DulcetMyLibrarySnapshot
    let onRetry: () -> Void

    var body: some View {
        if let store, let indicator = store.readerConnectionIndicator(
            serverReadFailed: library.syncState == "offline" || library.error?.isTransportFailure == true) {
            let deviceOnly = store.librarySession?.mode == .deviceOnly
            DulcetConnectionStatus(indicator: indicator,
                identifier: deviceOnly ? "dulcet.reader.deviceOnly" : "dulcet.myLibrary.offline",
                retryIdentifier: deviceOnly ? "dulcet.reader.reconnect" : "dulcet.reader.retry",
                retry: { dulcetReaderRetry(store) }, accountBannerOpaque: true)
        } else if let error = library.error, library.syncState != "offline", !error.isTransportFailure {
            DulcetDestinationStatus(title: DulcetStrings.myLibraryRefreshFailedTitle,
                symbol: "exclamationmark.circle", retry: onRetry)
                .accessibilityLabel(DulcetStrings.myLibraryRefreshFailed(DulcetStrings.readerErrorPhrase(error)))
                .dulcetContentMarginPadding()
                .background(.background, ignoresSafeAreaEdges: [])
        } else if library.syncState == "syncing" {
            ProgressView()
                .dulcetContentMarginPadding()
                .background(.background, ignoresSafeAreaEdges: [])
        }
    }
}

/// The empty library's invitation; the Browse action reads and acts as a button. It follows the
/// Library's sections inside their scrolling page. On Library's home it is `compact`: a short
/// leading-aligned block, so that under the seven section rows the whole of it, Browse included,
/// sits above the tab bar and the playing bar instead of behind them.
struct DulcetMyLibraryEmptyState: View {
    @Environment(DulcetPresentationStore.self) private var store
    var compact = false

    var body: some View {
#if !os(tvOS)
        if compact { compactInvitation } else { fullInvitation }
#else
        fullInvitation
#endif
    }

#if !os(tvOS)
    private var compactInvitation: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(DulcetStrings.myLibraryEmptyTitle)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
            Text(DulcetStrings.myLibraryEmptyBody)
                .font(.subheadline)
                .dulcetForeground(.secondaryTextOnWindow)
                .fixedSize(horizontal: false, vertical: true)
            Button(DulcetStrings.browseSection, systemImage: "globe") { store.navigate(to: .browse) }
                .buttonStyle(.bordered)
                .padding(.top, DulcetSpacing.xs)
                .accessibilityIdentifier("dulcet.myLibrary.browse")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, DulcetSpacing.md)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("dulcet.myLibrary.invitation")
    }
#endif

    @ViewBuilder
    private var fullInvitation: some View {
#if os(tvOS)
        // Apple TV keeps the compact invitation until its page frame moves (tvOS polish): under
        // the seven section rows, the full state message's glyph would push Browse past the
        // bottom safe area before it can take focus.
        VStack(alignment: .center, spacing: DulcetSpacing.sm) {
            Text(DulcetStrings.myLibraryEmptyTitle).font(.title2.weight(.semibold))
            Text(DulcetStrings.myLibraryEmptyBody)
                .font(.callout)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 1200)
            Button(DulcetStrings.browseSection) { store.navigate(to: .browse) }
                .buttonStyle(DulcetTVActionButtonStyle())
                .controlSize(.large)
                .accessibilityIdentifier("dulcet.myLibrary.browse")
        }
        .frame(maxWidth: .infinity)
        .padding(.top, DulcetSpacing.sm)
        // Keep its action inside the TV safe area even before it receives focus.
        .padding(.bottom, 80)
#else
        DulcetStateMessage(
            symbol: "music.note.house",
            title: DulcetStrings.myLibraryEmptyTitle,
            message: DulcetStrings.myLibraryEmptyBody,
            action: DulcetStateAction(
                DulcetStrings.browseSection,
                isDefaultAction: false,
                accessibilityIdentifier: "dulcet.myLibrary.browse"
            ) { store.navigate(to: .browse) }
        )
        .embeddedInScrollingPage()
#endif
    }
}

/// The albums, artists and songs of one My Library section, on the root or a pushed page.
struct DulcetMyLibraryCollection: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let library: DulcetMyLibrarySnapshot
    let section: DulcetMyLibrarySection
    let width: CGFloat
    var onFirstGridWidthChange: ((CGFloat) -> Void)? = nil

    var body: some View {
        switch section {
        case .recent:
            tiles(library.albums, observesWidth: true)
            tiles(library.artists)
            tracks(library.songs)
        case .albums: tiles(library.albums)
        case .artists: tiles(library.artists)
        case .songs: tracks(library.songs)
        case .playlists: EmptyView()
        }
    }

    @ViewBuilder
    private func tiles(_ items: [DulcetReaderItem], observesWidth: Bool = false) -> some View {
        let grid = DulcetArtworkGrid(items: items, width: width) { item, _, size in
            DulcetReaderTile(item: item, width: size)
        }
#if os(iOS)
        if #available(iOS 27, *), observesWidth, let onFirstGridWidthChange {
            grid.onGeometryChange(for: CGFloat.self) { proxy in
                proxy.size.width
            } action: { width in onFirstGridWidthChange(width) }
        } else { grid }
#else
        grid
#endif
    }

    @ViewBuilder
    private func tracks(_ items: [DulcetReaderItem]) -> some View {
#if os(iOS)
        trackRows(items)
#else
        LazyVStack(alignment: .leading, spacing: 0) {
            trackRows(items)
        }
#endif
    }

    @ViewBuilder
    private func trackRows(_ items: [DulcetReaderItem]) -> some View {
        ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
            DulcetReaderTrackRow(item: item, index: index + 1) { track in
                store.playReaderTracks(items.compactMap(\.playableTrack), startingAt: track, sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.library)
            }
            .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: true, artwork: true)
        }
    }
}

/// A My Library section pushed on the reader's stack: a compact Library root's rows open these.
struct DulcetMyLibrarySectionPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let section: DulcetMyLibrarySection

    private var title: String {
#if os(tvOS)
        section.title
#else
        section == .albums ? DulcetStrings.myLibraryAlbums : section.title
#endif
    }

    var body: some View {
        Group {
            if section == .playlists {
                DulcetPlaylistsView()
            } else {
                GeometryReader { geometry in
#if os(iOS)
                    let inset = DulcetSpacing.md
                    List {
                        Group {
                            if let library = store.librarySession?.myLibrary {
                                if section == .songs && !library.songs.isEmpty {
                                    DulcetMyLibraryShuffle(songs: library.songs)
                                        .padding(.bottom, DulcetSpacing.md)
                                }
                                collection(library, width: max(0, geometry.size.width - inset * 2))
                            }
                        }
                        .listRowInsets(EdgeInsets(top: 0, leading: inset, bottom: 0, trailing: inset))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .environment(\.defaultMinListRowHeight, 0)
                    .contentMargins(.vertical, DulcetSpacing.md, for: .scrollContent)
                    .modifier(DulcetSystemScrollChrome())
                    .refreshable { refresh() }
#else
                    ScrollView {
                        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                            if let library = store.librarySession?.myLibrary {
                                if section == .songs && !library.songs.isEmpty { DulcetMyLibraryShuffle(songs: library.songs) }
                                collection(library, width: geometry.size.width)
                            }
                        }
                        .padding(DulcetSpacing.md)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .modifier(DulcetSystemScrollChrome())
#if !os(tvOS)
                    .refreshable { refresh() }
#endif
#endif
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            // Playlists is a reader screen with its own status: two statements of one state
            // stacked over it were the offline page's second "You're offline".
            if section != .playlists, let library = store.librarySession?.myLibrary {
                DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
            }
        }
        .dulcetForeground(.primaryTextOnWindow)
        .dulcetNavigationTitle(title, keepsTVNavigationBar: section == .songs)
        .accessibilityIdentifier("dulcet.myLibrary.sectionPage.\(section.rawValue)")
        .toolbar {
            if section != .recent && section != .playlists {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        DulcetLibraryCategoryControls(category: "myLibrary.\(section.rawValue)", offersArtist: section != .artists, menuPresentation: true)
                    } label: { Label(DulcetStrings.readerSortBy, systemImage: "arrow.up.arrow.down") }
                    .accessibilityIdentifier("dulcet.myLibrary.\(section.rawValue).options")
                }
            }
#if os(macOS)
            ToolbarItem(placement: .primaryAction) {
                Button(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise", action: refresh)
                    .keyboardShortcut("r", modifiers: .command)
                    .accessibilityIdentifier("dulcet.myLibrary.refresh")
            }
#endif
        }
        .onAppear { applySort() }
        .onChange(of: store.librarySession?.readerGeneration) { _, _ in applySort() }
    }

    @ViewBuilder private func collection(_ library: DulcetMyLibrarySnapshot, width: CGFloat) -> some View {
#if !os(tvOS)
        if section == .albums {
            DulcetMyLibraryCollection(library: library, section: section, width: width)
            if library.albums.count <= 2 && (library.syncState != "syncing" || !library.albums.isEmpty) {
                browseInvitation(DulcetStrings.myLibraryAlbumHint, identifier: "dulcet.myLibrary.albums.browse") {
                    store.showServerAlbums()
                }
            }
        } else if section == .songs && library.songs.isEmpty && library.syncState != "syncing" {
            browseInvitation(DulcetStrings.myLibrarySongHint, identifier: "dulcet.myLibrary.songs.empty") {
                store.navigate(to: .browse)
            }
        } else if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
            if library.syncState != "syncing" { DulcetMyLibraryEmptyState() }
        } else {
            DulcetMyLibraryCollection(library: library, section: section, width: width)
        }
#else
        if section == .songs && library.songs.isEmpty && library.syncState != "syncing" {
            DulcetStateMessage(symbol: "music.note", title: DulcetStrings.readerEmptyList,
                               accessibilityIdentifier: "dulcet.myLibrary.songs.empty")
                .embeddedInScrollingPage()
        } else if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
            if library.syncState != "syncing" { DulcetMyLibraryEmptyState() }
        } else {
            DulcetMyLibraryCollection(library: library, section: section, width: width)
        }
#endif
    }

#if !os(tvOS)
    private func browseInvitation(_ hint: String, identifier: String, action: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            Text(hint)
                .font(.subheadline)
                .dulcetForeground(.secondaryTextOnWindow)
            Button(action: action) {
                Label(section == .albums ? DulcetStrings.browseServerAlbums : DulcetStrings.browseSection, systemImage: "globe")
                    .dulcetForeground(.accentTextOnWindow)
                    .frame(minHeight: DulcetMetrics.minimumTouchTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityIdentifier(identifier)
        }
        .padding(.top, DulcetSpacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
#endif

    private func applySort() {
        (store.librarySession?.reader as? any DulcetMyLibraryReading)?.setMyLibrarySort(section == .recent ? .recentlyAdded : .alphabetical)
    }
    private func refresh() { (store.librarySession?.reader as? any DulcetMyLibraryReading)?.refreshMyLibrary() }
}

/// What Library's home shows under its sections. My Library starts empty on purpose: it is what
/// each person adds, never seeded from their playlists, plays or downloads. Until they add
/// something, the home still shows music that is already theirs -- their playlists and what is
/// on this device -- and invites them to Browse only when there is none.
enum DulcetLibraryHomeContent: Equatable {
    /// A read the decision depends on has not answered yet: draw nothing rather than a poster
    /// that a moment later gives way to the person's music.
    case waiting
    case recentlyAdded
    case yourMusic(playlists: Bool, downloads: Bool)
    case invitation

    /// The most tiles one playlists or downloads shelf shows; See All opens the rest.
    static let shelfLimit = 8

    enum Shelf: Equatable {
        case pending
        case empty
        case filled

        init(_ window: DulcetLibraryWindow?) {
            guard let window else { self = .pending; return }
            if !window.items.isEmpty { self = .filled }
            else if window.freshness == .loading || window.itemsState == .loading { self = .pending }
            else { self = .empty }
        }
    }

    static func resolve(library: DulcetMyLibrarySnapshot, playlists: Shelf, downloads: Shelf) -> Self {
        if !(library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty) { return .recentlyAdded }
        if playlists == .filled || downloads == .filled {
            return .yourMusic(playlists: playlists == .filled, downloads: downloads == .filled)
        }
        if library.syncState == "syncing" || playlists == .pending || downloads == .pending { return .waiting }
        return .invitation
    }
}

struct DulcetMyLibraryView: View {
    @Environment(DulcetPresentationStore.self) private var store
    private var section: DulcetMyLibrarySection { store.myLibrarySection }
    @Environment(\.displayScale) private var gridDisplayScale
    @State private var recentGridWidth: CGFloat?
    @State private var recentGridGeneration = 0
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// A phone lists the sections as rows that push their pages; a regular width has the sidebar's.
    private var listsSections: Bool { horizontalSizeClass == .compact }
#endif
#if os(tvOS)
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
    @FocusState private var focusedSection: DulcetMyLibrarySection?
#endif
    private var title: String {
#if os(tvOS)
        DulcetStrings.library
#else
        DulcetStrings.myLibraryTitle
#endif
    }

    var body: some View {
        root
            .dulcetForeground(.primaryTextOnWindow)
            .dulcetNavigationTitle(title, keepsTVNavigationBar: true)
            .accessibilityIdentifier("dulcet.myLibrary")
            .safeAreaInset(edge: .top, spacing: 0) {
                if let library = store.librarySession?.myLibrary {
                    DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                }
            }
#if os(macOS)
            // Refresh belongs to the bar: a label button under the title truncated at the
            // accessibility text sizes, and a plain-text one read as body copy.
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { refresh() } label: {
                        Label(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise")
                    }
                    .labelStyle(.iconOnly)
                    .accessibilityLabel(DulcetStrings.myLibraryRefresh)
                    .accessibilityIdentifier("dulcet.myLibrary.refresh")
                    .keyboardShortcut("r", modifiers: .command)
                }
            }
#endif
            .onAppear {
                applySort()
                refresh()
#if os(tvOS)
                claimArrivalFocus()
#endif
            }
#if os(tvOS)
            .onChange(of: arrivalFocus?.wantsSectionFocus == true) { _, wants in
                if wants { claimArrivalFocus() }
            }
            .onChange(of: focusedSection) { _, current in
                if current != nil { arrivalFocus?.sectionControlFocused() }
            }
#endif
            .onChange(of: section) { _, _ in applySort() }
            .onChange(of: store.librarySession?.readerGeneration) { _, _ in
                applySort()
                refresh()
            }
    }

    private var root: some View { compactHome }

    /// One personal root on every platform; category selection opens an actual page.
    ///
    /// Under the sections it shows the person's own music: Recently Added once they have added
    /// something; before that, the playlists and downloads they already have; the invitation only
    /// when there is none of these. Playlists and downloads are read outside the List: an empty
    /// conditional row inside it never receives the appearance that would start its read.
    private var compactHome: some View {
        DulcetReaderScreen(query: .playlists) { playlists in
            DulcetReaderScreen(query: .downloadedAlbums) { downloads in
                homeList(playlists: playlists.window, downloads: store.downloadsEnabled ? downloads.window : nil)
            }
        }
    }

    private func homeList(playlists: DulcetLibraryWindow?, downloads: DulcetLibraryWindow?) -> some View {
        GeometryReader { geometry in
            List {
                Section {
                    ForEach([DulcetMyLibrarySection.playlists, .artists, .albums, .songs]) { choice in
                        DulcetReaderNavigationLink(title: choice.title, symbol: choice.symbolName,
                            route: .myLibrarySection(choice), identifier: "dulcet.myLibrary.section.\(choice.rawValue)")
                    }
                    DulcetPersonalLibraryLinks()
                }
                if !(store.librarySession?.libraryDepth.pins.isEmpty ?? true) {
                    DulcetPinnedLibraryItems().dulcetHideListRowSeparator()
                }
                if let library = store.librarySession?.myLibrary {
                    let content = DulcetLibraryHomeContent.resolve(
                        library: library,
                        playlists: DulcetLibraryHomeContent.Shelf(playlists),
                        downloads: store.downloadsEnabled ? DulcetLibraryHomeContent.Shelf(downloads) : .empty
                    )
                    switch content {
                    case .waiting:
                        EmptyView()
                    case .invitation:
                        DulcetMyLibraryEmptyState(compact: true)
                            .dulcetHideListRowSeparator()
                    case .recentlyAdded:
                        Section {
                            DulcetMyLibraryCollection(library: library, section: .recent, width: geometry.size.width,
                                onFirstGridWidthChange: recordRecentGridWidth)
                                .id(recentGridGeneration)
                                .dulcetHideListRowSeparator()
                        } header: {
                            DulcetShelfHeader(title: DulcetStrings.recentlyAdded,
                                identifier: "dulcet.myLibrary.section.recent") {
                                store.pushReaderPage(.myLibrarySection(.recent))
                            }
                            .textCase(nil)
                        }
                    case let .yourMusic(showsPlaylists, showsDownloads):
                        if showsPlaylists, let playlists {
                            homeShelf(DulcetStrings.readerPlaylists, identifier: "dulcet.myLibrary.home.playlists",
                                items: playlists.items, width: geometry.size.width, observesWidth: true) {
                                store.pushReaderPage(.myLibrarySection(.playlists))
                            }
                        }
                        if showsDownloads, let downloads {
                            homeShelf(DulcetStrings.libraryDownloaded, identifier: "dulcet.myLibrary.home.downloaded",
                                items: downloads.items, width: geometry.size.width, observesWidth: !showsPlaylists) {
                                store.pushReaderPage(.downloaded)
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .modifier(DulcetSystemScrollChrome())
#if os(macOS)
            .environment(\.defaultMinListRowHeight, 36)
#endif
#if !os(tvOS)
            .refreshable { refresh() }
#endif
        }
    }

    /// One shelf of the person's own playlists or downloads, as artwork tiles under a See All heading.
    private func homeShelf(_ title: String, identifier: String, items: [DulcetReaderItem], width: CGFloat,
                           observesWidth: Bool, seeAll: @escaping () -> Void) -> some View {
        Section {
            let grid = DulcetArtworkGrid(items: Array(items.prefix(DulcetLibraryHomeContent.shelfLimit)), width: width) { item, _, size in
                DulcetReaderTile(item: item, width: size)
            }
            Group {
#if os(iOS)
                if #available(iOS 27, *), observesWidth {
                    grid.onGeometryChange(for: CGFloat.self) { proxy in proxy.size.width } action: { recordRecentGridWidth($0) }
                } else { grid }
#else
                grid
#endif
            }
            .id(recentGridGeneration)
            .dulcetHideListRowSeparator()
        } header: {
            DulcetShelfHeader(title: title, identifier: identifier, onSeeAll: seeAll)
                .textCase(nil)
        }
    }

    private func recordRecentGridWidth(_ width: CGFloat) {
#if os(iOS)
        guard #available(iOS 27, *), width.isFinite else { return }
        // Initial narrow-to-wide proposals can stale the lazy grid's accessibility node.
        // List owns the scroll offset, so observe width rather than its moving viewport origin.
        let pixelWidth = (width * gridDisplayScale).rounded() / gridDisplayScale
        guard recentGridWidth != pixelWidth else { return }
        if recentGridWidth != nil { recentGridGeneration += 1 }
        recentGridWidth = pixelWidth
#endif
    }

    /// The whole Library for one section, chosen by the sidebar or the television's section row.
    @ViewBuilder
    private func sectionContent(_ section: DulcetMyLibrarySection) -> some View {
        if section == .playlists {
            DulcetPlaylistsView()
        } else {
            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: DulcetSpacing.md) {
#if os(macOS)
                        Button(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise") { refresh() }
                            .accessibilityIdentifier("dulcet.myLibrary.refresh")
#endif
                        DulcetLibraryDepthHome(width: geometry.size.width)
                        if section != .recent {
                            DulcetLibraryCategoryControls(category: "myLibrary.\(section.rawValue)", offersArtist: section != .artists)
                        }
                        if let library = store.librarySession?.myLibrary {
                            if (section == .songs || section == .recent) && !library.songs.isEmpty { DulcetMyLibraryShuffle(songs: library.songs) }
                            DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                            if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
                                DulcetMyLibraryEmptyState()
                            } else {
                                DulcetMyLibraryCollection(library: library, section: section, width: geometry.size.width)
                            }
                        }
                    }
                    .padding(DulcetSpacing.md)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
#if !os(tvOS)
                .refreshable { refresh() }
#endif
            }
        }
    }

#if os(tvOS)
    /// The sections and Refresh across the top of Library, as the reader's own bar lays them out:
    /// one horizontal, scrollable row of focusable items -- never wrapped pills.
    private var sectionBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: DulcetSpacing.xs) {
                ForEach(DulcetMyLibrarySection.allCases) { choice in
                    Button {
                        store.selectMyLibrarySection(choice)
                    } label: {
                        Label(choice.title, systemImage: choice.symbolName)
                            .font(.callout.weight(section == choice ? .semibold : .regular))
                    }
                    .buttonStyle(.bordered)
                    .focused($focusedSection, equals: choice)
                    .accessibilityIdentifier("dulcet.myLibrary.section.\(choice.rawValue)")
                    .accessibilityAddTraits(section == choice ? .isSelected : [])
                }
                Button {
                    refresh()
                } label: {
                    Label(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise")
                        .font(.callout)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("dulcet.myLibrary.refresh")
            }
            .padding(.horizontal, DulcetSpacing.lg)
            .padding(.vertical, DulcetSpacing.xs)
        }
        .focusSection()
    }

    private func claimArrivalFocus() {
        guard let arrivalFocus, arrivalFocus.pending, store.readerPath.isEmpty else { return }
        focusedSection = section
    }
#endif

    /// The section whose content the root draws: always Recently Added on a phone, where the rows
    /// push their pages; elsewhere the sidebar's or section row's choice.
    private var shownSection: DulcetMyLibrarySection {
#if os(iOS)
        if listsSections { return .recent }
#endif
        return section
    }

    private func applySort() {
        (store.librarySession?.reader as? any DulcetMyLibraryReading)?.setMyLibrarySort(shownSection == .recent ? .recentlyAdded : .alphabetical)
    }
    private func refresh() { (store.librarySession?.reader as? any DulcetMyLibraryReading)?.refreshMyLibrary() }
}
struct DulcetMyLibraryShuffle: View {
    @Environment(DulcetPresentationStore.self) private var store
    let songs: [DulcetReaderItem]
    var body: some View {
        Button(DulcetStrings.libraryShufflePersonal, systemImage: "shuffle") {
            store.playReaderTracks(songs.compactMap(\.playableTrack).filter { $0.availability == .playable }, shuffle: true,
                                   sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.libraryPersonalTitle)
        }
        .buttonStyle(.bordered)
        .disabled(songs.compactMap(\.playableTrack).allSatisfy { $0.availability != .playable })
        .accessibilityIdentifier("dulcet.myLibrary.shuffleAll")
    }
}
/// Personal destinations available without the membership extension.
struct DulcetPersonalLibraryRoot: View {
    @Environment(DulcetPresentationStore.self) private var store
    var body: some View {
#if os(macOS)
        // Open the publication outside the List's lazy row lifecycle. An empty conditional
        // section inside a List can otherwise never receive the appearance that starts it.
        DulcetReaderScreen(query: .favourites) { model in personalList(model.window) }
#else
        personalList(nil)
#endif
    }

    private func personalList(_ favorites: DulcetLibraryWindow?) -> some View {
        List {
            DulcetReaderNavigationLink(title: DulcetStrings.readerPlaylists, symbol: "music.note.list",
                route: .section(.playlists), identifier: "dulcet.reader.section.playlists")
            DulcetPersonalLibraryLinks()
            if !(store.librarySession?.libraryDepth.pins.isEmpty ?? true) {
                DulcetPinnedLibraryItems().dulcetHideListRowSeparator()
            }
#if os(macOS)
            if let favorites, favorites.hasContent {
                Section {
                    ForEach(favorites.items.prefix(8)) { item in
                        if item.kind == .track {
                            DulcetReaderTrackRow(item: item, index: 1, showsIndex: false) { track in
                                store.playReaderTracks(favorites.items.compactMap(\.playableTrack), startingAt: track,
                                    sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.readerFavorites)
                            }
                        } else {
                            DulcetReaderListRow(item: item)
                        }
                    }
                } header: { Text(DulcetStrings.readerFavorites) }
            }
#endif
        }
        .listStyle(.plain)
        .modifier(DulcetSystemScrollChrome())
#if os(macOS)
        .environment(\.defaultMinListRowHeight, 36)
#endif
        .dulcetNavigationTitle(DulcetStrings.library, keepsTVNavigationBar: true)
        .safeAreaInset(edge: .top, spacing: 0) { DulcetReaderAccountBanner() }
    }
}

struct DulcetPersonalLibraryLinks: View {
    @Environment(DulcetPresentationStore.self) private var store
    var body: some View {
        if store.downloadsEnabled {
            DulcetReaderNavigationLink(title: DulcetStrings.libraryDownloaded, symbol: "arrow.down.circle",
                route: .downloaded, identifier: "dulcet.library.downloaded")
        }
        DulcetReaderNavigationLink(title: DulcetStrings.readerFavorites, symbol: "heart",
            route: .section(.favourites), identifier: "dulcet.reader.section.favourites")
#if !os(tvOS)
        if store.librarySession?.discoverPublication?.access.showsWishlistDestination == true {
            DulcetReaderNavigationLink(title: DulcetStrings.wishlist, symbol: "bookmark",
                route: .wishlist, identifier: "wishlist.destination")
        }
#endif
    }
}
#endif
