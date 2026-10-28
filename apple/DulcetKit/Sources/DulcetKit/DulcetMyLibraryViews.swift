#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

struct DulcetReaderCollectionRoot: View {
    @Environment(DulcetPresentationStore.self) private var store
    let destination: DulcetSidebarDestination
    var body: some View {
        if destination == .library && store.myLibraryAvailable {
            DulcetMyLibraryView()
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
            Text(DulcetStrings.myLibraryRefreshFailed(DulcetStrings.readerErrorPhrase(error)))
            Button(DulcetStrings.tryAgain, action: onRetry)
        } else if library.syncState == "syncing" {
            ProgressView()
        } else if library.syncState == "cached", let date = library.lastReadAt {
            Text(date, style: .relative)
                .dulcetForeground(.secondaryTextOnWindow)
        }
    }
}

#if os(iOS)
/// Status layouts occupy different heights above the compact Library collection.
enum DulcetMyLibraryStatusLayout: Hashable {
    case unchanged, hidden, progress, cached, failure

    init(_ library: DulcetMyLibrarySnapshot) {
        guard #available(iOS 27, *) else { self = .unchanged; return }
        if library.error != nil { self = .failure }
        else if library.syncState == "syncing" { self = .progress }
        else if library.syncState == "cached", library.lastReadAt != nil { self = .cached }
        else { self = .hidden }
    }
}
#endif

/// The empty library's invitation; the Browse action reads and acts as a button.
struct DulcetMyLibraryEmptyState: View {
    @Environment(DulcetPresentationStore.self) private var store

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            Text(DulcetStrings.myLibraryEmptyTitle).font(.title2.weight(.semibold))
            Text(DulcetStrings.libraryPersonalEmpty).dulcetForeground(.secondaryTextOnWindow)
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
        let tileWidth: CGFloat = dynamicTypeSize.isAccessibilitySize ? 260 : (width < 500 ? 150 : 180)
        return LazyVGrid(columns: [GridItem(.adaptive(minimum: tileWidth), alignment: .topLeading)], alignment: .leading) {
            ForEach(items) { item in DulcetReaderTile(item: item, width: tileWidth) }
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
#if os(iOS)
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

    @ViewBuilder
    private var root: some View {
#if os(tvOS)
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            sectionBar
            sectionContent(section)
        }
#elseif os(iOS)
        if listsSections {
            compactHome
        } else {
            sectionContent(section)
        }
#else
        sectionContent(section)
#endif
    }

#if os(iOS)
    /// The phone's Library root: every section as a row, then what was added recently as a grid --
    /// the platform's own music app's layout. The rows push their pages onto the stack.
    private var compactHome: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    Text(DulcetStrings.libraryPersonalTitle).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                    Text(DulcetStrings.libraryPersonalHint).font(.subheadline).dulcetForeground(.secondaryTextOnWindow)
                    if store.librarySession?.discoverPublication?.access.showsWishlistDestination == true {
                        DulcetReaderHomeLink(title: DulcetStrings.wishlist, symbolName: "bookmark", identifier: "wishlist.destination") { store.pushReaderPage(.wishlist) }
                    }
                    DulcetLibraryDepthHome(width: geometry.size.width)
                    if let library = store.librarySession?.myLibrary {
                        DulcetMyLibraryShuffle(songs: library.songs)
                        DulcetMyLibraryStatusLines(library: library, onRetry: refresh)
                        VStack(spacing: 0) {
                            ForEach(DulcetMyLibrarySection.allCases) { choice in
                                DulcetReaderHomeLink(
                                    title: choice.title,
                                    symbolName: choice.symbolName,
                                    identifier: "dulcet.myLibrary.section.\(choice.rawValue)"
                                ) {
                                    store.pushReaderPage(.myLibrarySection(choice))
                                }
                            }
                        }
                        if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
                            DulcetMyLibraryEmptyState()
                        } else {
                            Text(DulcetStrings.recentlyAdded)
                                .font(.title2.weight(.semibold))
                                .accessibilityAddTraits(.isHeader)
                            DulcetMyLibraryCollection(library: library, section: .recent, width: geometry.size.width)
                                // On iOS 27, a layout move can leave a lazy AX node childless with a null frame.
                                // Recreate it for status height changes; remove when status no longer moves lazy content.
                                .id(DulcetMyLibraryStatusLayout(library))
                        }
                    }
                }
                .padding(DulcetSpacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .refreshable { refresh() }
        }
    }
#endif

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
#endif
