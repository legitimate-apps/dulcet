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
    var body: some View {
        if let session = store.librarySession, session.myLibrary.availability == .available {
            // Read the observable publication so a membership change invalidates this control.
            let _ = session.myLibrary
            let membership = session.myLibraryMembership(target)
            let on = membership == .added
            let title = on ? DulcetStrings.removeFromLibrary : DulcetStrings.addToLibrary
            Button { session.toggleMyLibrary(target) } label: {
                if menu { Label(title, systemImage: on ? "minus.circle" : "plus.circle") }
                else { Image(systemName: on ? "checkmark.circle.fill" : "plus.circle") }
            }
            .accessibilityLabel(title)
            .accessibilityIdentifier("dulcet.membership.\(target.kind.rawValue).\(target.id.rawID)")
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
    let library: DulcetMyLibrarySnapshot
    let onRetry: () -> Void

    var body: some View {
        if let error = library.error {
            DulcetDestinationStatus(title: DulcetStrings.myLibraryRefreshFailed(DulcetStrings.readerErrorPhrase(error)),
                symbol: "exclamationmark.circle", retry: onRetry)
        } else if library.syncState == "syncing" {
            ProgressView()
        } else if library.syncState == "cached", let date = library.lastReadAt, Date().timeIntervalSince(date) > 86_400 {
            if let detail = DulcetReaderCopy.visibleFreshnessLine(.cached(.stale, asOf: date)) {
                DulcetDestinationStatus(title: detail)
            }
        }
    }
}

/// The empty library's invitation; the Browse action reads and acts as a button.
struct DulcetMyLibraryEmptyState: View {
    @Environment(DulcetPresentationStore.self) private var store

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            Text(DulcetStrings.myLibraryEmptyTitle).font(.title2.weight(.semibold))

            Button(DulcetStrings.browseSection) { store.navigate(to: .browse) }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
#if os(iOS)
                .dulcetForeground(.labelOnAccentFill)
#endif
                .accessibilityIdentifier("dulcet.myLibrary.browse")
        }
    }
}

/// The albums, artists and songs of one My Library section, on the root or a pushed page.
struct DulcetMyLibraryCollection: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let library: DulcetMyLibrarySnapshot
    let section: DulcetMyLibrarySection
    let width: CGFloat

    var body: some View {
        switch section {
        case .recent:
            tiles(library.albums)
            tiles(library.artists)
            tracks(library.songs)
        case .albums: tiles(library.albums)
        case .artists: tiles(library.artists)
        case .songs: tracks(library.songs)
        case .playlists: EmptyView()
        }
    }

    private func tiles(_ items: [DulcetReaderItem]) -> some View {
        DulcetArtworkGrid(items: items, width: width) { item, _, size in
            DulcetReaderTile(item: item, width: size)
        }
    }

    private func tracks(_ items: [DulcetReaderItem]) -> some View {
        LazyVStack(alignment: .leading) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                DulcetReaderTrackRow(item: item, index: index + 1) { track in
                    store.playReaderTracks(items.compactMap(\.playableTrack), startingAt: track, sourceKind: .library, sourceID: nil, sourceName: DulcetStrings.library)
                }
            }
        }
    }
}

/// A My Library section pushed on the reader's stack: a compact Library root's rows open these.
struct DulcetMyLibrarySectionPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let section: DulcetMyLibrarySection

    var body: some View {
        Group {
            if section == .playlists {
                DulcetPlaylistsView()
            } else {
                GeometryReader { geometry in
                    ScrollView {
                        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                            if let library = store.librarySession?.myLibrary {
                                if section != .recent {
                                    DulcetLibraryCategoryControls(category: "myLibrary.\(section.rawValue)", offersArtist: section != .artists)
                                }
                                DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                                if section == .songs { DulcetMyLibraryShuffle(songs: library.songs) }
                                if section == .songs && library.songs.isEmpty && library.syncState != "syncing" {
                                    ContentUnavailableView(DulcetStrings.readerEmptyList, systemImage: "music.note")
                                } else if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
                                    if library.syncState != "syncing" { DulcetMyLibraryEmptyState() }
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
        .dulcetForeground(.primaryTextOnWindow)
        .dulcetNavigationTitle(section.title, keepsTVNavigationBar: false)
        .accessibilityIdentifier("dulcet.myLibrary.sectionPage.\(section.rawValue)")
        .onAppear { applySort() }
        .onChange(of: store.librarySession?.readerGeneration) { _, _ in applySort() }
    }

    private func applySort() {
        (store.librarySession?.reader as? any DulcetMyLibraryReading)?.setMyLibrarySort(section == .recent ? .recentlyAdded : .alphabetical)
    }
    private func refresh() { (store.librarySession?.reader as? any DulcetMyLibraryReading)?.refreshMyLibrary() }
}

struct DulcetMyLibraryView: View {
    @Environment(DulcetPresentationStore.self) private var store
    private var section: DulcetMyLibrarySection { store.myLibrarySection }
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// A phone lists the sections as rows that push their pages; a regular width has the sidebar's.
    private var listsSections: Bool { horizontalSizeClass == .compact }
#endif
#if os(tvOS)
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
    @FocusState private var focusedSection: DulcetMyLibrarySection?
#endif
    var body: some View {
        root
            .dulcetForeground(.primaryTextOnWindow)
            .dulcetNavigationTitle(DulcetStrings.library, keepsTVNavigationBar: true)
            .accessibilityIdentifier("dulcet.myLibrary")
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
    private var compactHome: some View {
        GeometryReader { geometry in
            List {
                DulcetReaderAccountBanner()
                Section {
                    ForEach([DulcetMyLibrarySection.playlists, .artists, .albums, .songs]) { choice in
                        DulcetReaderNavigationLink(title: choice.title, symbol: choice.symbolName,
                            route: .myLibrarySection(choice), identifier: "dulcet.myLibrary.section.\(choice.rawValue)")
                    }
                    DulcetPersonalLibraryLinks()
                }
                if let library = store.librarySession?.myLibrary {
                    DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                    if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
                        if library.syncState != "syncing" { DulcetMyLibraryEmptyState() }
                    } else {
                        Section {
                            DulcetMyLibraryCollection(library: library, section: .recent, width: geometry.size.width)
                                .listRowSeparator(.hidden)
                        } header: {
                            DulcetShelfHeader(title: DulcetStrings.recentlyAdded,
                                identifier: "dulcet.myLibrary.section.recent") {
                                store.pushReaderPage(.myLibrarySection(.recent))
                            }
                            .textCase(nil)
                        }
                    }
                }
            }
            .listStyle(.plain)
#if !os(tvOS)
            .refreshable { refresh() }
#endif
        }
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
                            if section == .songs || section == .recent { DulcetMyLibraryShuffle(songs: library.songs) }
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
        List {
            DulcetReaderAccountBanner()
            DulcetReaderNavigationLink(title: DulcetStrings.readerPlaylists, symbol: "music.note.list",
                route: .section(.playlists), identifier: "dulcet.reader.section.playlists")
            DulcetPersonalLibraryLinks()
        }
        .listStyle(.plain)
        .dulcetNavigationTitle(DulcetStrings.library, keepsTVNavigationBar: true)
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
