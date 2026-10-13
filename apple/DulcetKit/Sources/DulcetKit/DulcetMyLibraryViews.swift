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
                    _ = (session.reader as? any DulcetMyLibraryReading)?.setMyLibraryMembership(target, added: false)
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
}

struct DulcetMyLibraryView: View {
    @Environment(DulcetPresentationStore.self) private var store
    private var section: DulcetMyLibrarySection { store.myLibrarySection }
#if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
#endif
#if os(tvOS)
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
    @FocusState private var focusedSection: DulcetMyLibrarySection?
#endif
    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
#if os(tvOS)
            sectionBar
#elseif os(iOS)
            if horizontalSizeClass == .compact { sectionBar }
#endif
            Button(DulcetStrings.myLibraryRefresh, systemImage: "arrow.clockwise") { refresh() }
                .padding(.horizontal, DulcetSpacing.md)
                .accessibilityIdentifier("dulcet.myLibrary.refresh")
            if section == .playlists {
                DulcetPlaylistsView()
            } else {
                GeometryReader { geometry in
                    ScrollView {
                        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                            if let library = store.librarySession?.myLibrary {
                                if let error = library.error {
                                    Text(DulcetStrings.readerChangeNotSaved(DulcetStrings.readerErrorPhrase(error)))
                                    Button(DulcetStrings.tryAgain) { refresh() }
                                } else if library.syncState == "syncing" {
                                    ProgressView()
                                } else if library.syncState == "cached", let date = library.lastReadAt {
                                    Text(date, style: .relative)
                                        .dulcetForeground(.secondaryTextOnWindow)
                                }
                                if library.albums.isEmpty && library.artists.isEmpty && library.songs.isEmpty {
                                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                                        Text(DulcetStrings.myLibraryEmptyTitle).font(.title2.weight(.semibold))
                                        Text(DulcetStrings.myLibraryEmptyBody).dulcetForeground(.secondaryTextOnWindow)
                                        Button(DulcetStrings.browseSection) { store.navigate(to: .browse) }
                                            .accessibilityIdentifier("dulcet.myLibrary.browse")
                                    }
                                } else {
                                    collection(library, width: geometry.size.width)
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
        .dulcetNavigationTitle(DulcetStrings.library, keepsTVNavigationBar: true)
        .accessibilityIdentifier("dulcet.myLibrary")
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

    private var sectionBar: some View {
        ViewThatFits(in: .horizontal) {
            HStack { sectionButtons }
            VStack(alignment: .leading) { sectionButtons }
        }
        .padding(.horizontal, DulcetSpacing.md)
    }
    private var sectionButtons: some View {
        ForEach(DulcetMyLibrarySection.allCases) { choice in
            Button(choice.title) { store.selectMyLibrarySection(choice) }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("dulcet.myLibrary.section.\(choice.rawValue)")
#if os(tvOS)
                .focused($focusedSection, equals: choice)
#endif
                .accessibilityAddTraits(section == choice ? .isSelected : [])
        }
    }
#if os(tvOS)
    private func claimArrivalFocus() {
        guard let arrivalFocus, arrivalFocus.pending, store.readerPath.isEmpty else { return }
        focusedSection = section
    }
#endif
    private func applySort() {
        (store.librarySession?.reader as? any DulcetMyLibraryReading)?.setMyLibrarySort(section == .recent ? .recentlyAdded : .alphabetical)
    }
    private func refresh() { (store.librarySession?.reader as? any DulcetMyLibraryReading)?.refreshMyLibrary() }

    @ViewBuilder
    private func collection(_ library: DulcetMyLibrarySnapshot, width: CGFloat) -> some View {
        switch section {
        case .recent:
            tiles(library.albums, width: width)
            tiles(library.artists, width: width)
            tracks(library.songs)
        case .albums: tiles(library.albums, width: width)
        case .artists: tiles(library.artists, width: width)
        case .songs: tracks(library.songs)
        case .playlists: EmptyView()
        }
    }
    private func tiles(_ items: [DulcetReaderItem], width: CGFloat) -> some View {
        let tileWidth: CGFloat = width < 500 ? 150 : 180
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
#endif
