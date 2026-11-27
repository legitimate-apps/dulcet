#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

/// Strings are the core's ListenNowShelf identifiers; no ranking or protocol policy lives here.
public enum DulcetListenNowShelf: String, CaseIterable, Sendable, Hashable, Identifiable {
    case recent, recentPlaylists, frequent, newest, rediscover, dailyMix, favourites, artistStation, genreStation, random
    public var id: String { rawValue }
    var title: String {
        switch self {
        case .recent: DulcetStrings.homeRecent
        case .recentPlaylists: DulcetStrings.homeRecentPlaylists
        case .frequent: DulcetStrings.homeFrequent
        case .newest: DulcetStrings.homeNewest
        case .rediscover: DulcetStrings.homeRediscover
        case .dailyMix: DulcetStrings.homeDailyMix
        case .favourites: DulcetStrings.homeFavourites
        case .artistStation: DulcetStrings.homeArtistStation
        case .genreStation: DulcetStrings.homeGenreStation
        case .random: DulcetStrings.homeRandom
        }
    }
}

struct DulcetListenNowView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @State private var recentItems: [DulcetProviderItemID] = []
    @State private var shownShelves: Set<DulcetListenNowShelf> = []
    @State private var serverReadFailed = false
    /// What each shown shelf drew, from which the Top Picks are chosen.
    @State private var shownItems: [DulcetListenNowShelf: [DulcetReaderItem]] = [:]
    var body: some View {
        let picks = DulcetListenNowPicks.plan(shownItems)
        DulcetReaderPage(title: DulcetStrings.listenNow, sectionSpacing: 0) { width in
#if !os(tvOS)
            // Apple TV keeps its shelves first: its remote arrives on the first shelf's first tile.
            DulcetListenNowHero(picks: picks, width: width)
#endif
            ForEach(DulcetListenNowShelf.allCases) { shelf in
                DulcetListenNowShelfView(shelf: shelf, width: width, recentItems: recentItems,
                    takesArrivalFocus: shelf == arrivalShelf, heroRoutes: Set(picks.compactMap(\.route)))
            }
        }
        .onPreferenceChange(DulcetRecentlyPlayedItems.self) { recentItems = $0 }
        .onPreferenceChange(DulcetListenNowShelfItems.self) { items in
            if items != shownItems { shownItems = items }
        }
        .onPreferenceChange(DulcetShownListenNowShelves.self) { shownShelves = $0 }
        .onPreferenceChange(DulcetReaderServerFailure.self) { serverReadFailed = $0 }
#if !os(tvOS)
        .refreshable { store.librarySession?.reconnect() }
#endif
#if os(macOS)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(DulcetStrings.homeRefresh, systemImage: "arrow.clockwise") { store.librarySession?.reconnect() }
                    .keyboardShortcut("r", modifiers: .command)
                    .accessibilityIdentifier("dulcet.listenNow.refresh")
            }
        }
#endif
        .accessibilityIdentifier("dulcet.listenNow")
        // Content passes under the account's status as it does under the bars, and starts below
        // it at rest: an overlay drew the status over the first shelf's heading.
        .dulcetAccountStatusBar(serverReadFailed: serverReadFailed)
        .environment(\.dulcetAccountMessageShown,
            store.readerConnectionIndicator(serverReadFailed: serverReadFailed) != nil)
    }

    /// Apple TV has no Refresh control (the section refreshes when it is entered, see
    /// `DulcetTVSectionNavigation`). Arrival focus lands on the first tile of the first shelf on
    /// the page instead.
    private var arrivalShelf: DulcetListenNowShelf? {
        DulcetListenNowShelf.allCases.first(where: shownShelves.contains)
    }
}

/// The items each shelf drew, for the Top Picks over them.
struct DulcetListenNowShelfItems: PreferenceKey {
    static let defaultValue: [DulcetListenNowShelf: [DulcetReaderItem]] = [:]
    static func reduce(value: inout [DulcetListenNowShelf: [DulcetReaderItem]],
                       nextValue: () -> [DulcetListenNowShelf: [DulcetReaderItem]]) {
        value.merge(nextValue()) { _, next in next }
    }
}

/// The shelves that drew content, so the page knows which one is first.
private struct DulcetShownListenNowShelves: PreferenceKey {
    static let defaultValue: Set<DulcetListenNowShelf> = []
    static func reduce(value: inout Set<DulcetListenNowShelf>, nextValue: () -> Set<DulcetListenNowShelf>) {
        value.formUnion(nextValue())
    }
}

/// Apple TV: the first tile of the first shelf takes focus from the section bar when the section
/// arrives without the person choosing it there. Elsewhere it changes nothing.
private struct DulcetListenNowArrivalFocus: ViewModifier {
    let active: Bool
#if os(tvOS)
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
    @Environment(\.dulcetTVArrivalScope) private var arrivalScope
    @Environment(\.resetFocus) private var resetFocus
    @FocusState private var focused: Bool
#endif

    // One structure whether or not this tile is the claimant. Which shelf comes first changes as
    // shelves publish, and an if/else here would replace a tile that holds focus with a new view.
    @ViewBuilder func body(content: Content) -> some View {
#if os(tvOS)
        content
            .focused($focused)
            .modifier(DulcetListenNowDefaultFocus(active: active, scope: arrivalScope))
            .onAppear { if active { claimFocus() } }
            // Shelves publish in any order. Focus the page placed follows the first shelf up; left
            // on a later one, the remote starts three shelves down with the first scrolled away.
            .onChange(of: active) { _, isActive in
                if isActive, arrivalFocus?.focusIsStillTheApps == true, store.readerPath.isEmpty {
                    focused = true
                }
            }
            .onChange(of: arrivalFocus?.wantsSectionFocus == true) { _, wants in
                if wants, active { claimFocus() }
            }
            // A claim made before the native bar has finished its launch restoration is dropped
            // with no callback, which left launch focus on the bar. Ask again while the bar holds
            // focus and arrival is open; any move by the person closes arrival and ends this.
            .task(id: active && arrivalFocus?.wantsSectionFocus == true) {
                guard active, arrivalFocus?.wantsSectionFocus == true else { return }
                for _ in 0..<40 {
                    try? await Task.sleep(for: .milliseconds(500))
                    guard !Task.isCancelled, arrivalFocus?.wantsSectionFocus == true else { return }
                    claimFocus()
                    if let arrivalScope { resetFocus(in: arrivalScope) }
                }
            }
            // A claim made before the native bar has finished its launch restoration is dropped
            // with no callback, which left launch focus on the bar. Ask again while the bar holds
            // focus and arrival is open; any move by the person closes arrival and ends this.
            .task(id: arrivalFocus?.wantsSectionFocus == true) {
                guard arrivalFocus?.wantsSectionFocus == true else { return }
                for _ in 0..<40 {
                    try? await Task.sleep(for: .milliseconds(500))
                    guard !Task.isCancelled, arrivalFocus?.wantsSectionFocus == true else { return }
                    claimFocus()
                    if let arrivalScope { resetFocus(in: arrivalScope) }
                }
            }
            .onChange(of: focused) { _, value in
                if value { arrivalFocus?.sectionControlFocused() }
            }
#else
        content
#endif
    }
#if os(tvOS)
    private func claimFocus() {
        guard let arrivalFocus, arrivalFocus.pending, store.readerPath.isEmpty else { return }
        if focused {
            // A UIKit tab restoration does not necessarily clear SwiftUI's focus binding.
            // Reissue the request on the next view update, only while arrival is still open.
            focused = false
            DispatchQueue.main.async {
                if arrivalFocus.wantsSectionFocus { focused = true }
            }
        } else {
            focused = true
        }
    }
#endif
}

#if os(tvOS)
private struct DulcetListenNowDefaultFocus: ViewModifier {
    let active: Bool
    let scope: Namespace.ID?
    @ViewBuilder func body(content: Content) -> some View {
        if let scope { content.prefersDefaultFocus(active, in: scope) } else { content }
    }
}
#endif

private struct DulcetRecentlyPlayedItems: PreferenceKey {
    static let defaultValue: [DulcetProviderItemID] = []
    static func reduce(value: inout [DulcetProviderItemID], nextValue: () -> [DulcetProviderItemID]) {
        let next = nextValue()
        if !next.isEmpty { value = next }
    }
}

private struct DulcetListenNowShelfView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dynamicTypeSize) private var textSize
    @Environment(\.dulcetListenNowZoom) private var zoom
    let shelf: DulcetListenNowShelf
    let width: CGFloat
    let recentItems: [DulcetProviderItemID]
    var takesArrivalFocus = false
    /// Pages a Top Pick already zooms out of; a tile for the same page leaves the zoom to it.
    var heroRoutes: Set<DulcetReaderRoute> = []
    var body: some View {
        DulcetReaderScreen(query: .listenNow(shelf)) { model in
            // Keep a concrete view alive before the first publication. An EmptyView does not
            // receive the reader's onAppear, so it can never open the query that would fill it.
            VStack(alignment: .leading, spacing: 0) {
            if let window = model.window {
                // Unsupported and empty personalized shelves disappear on a plain server. Newest
                // and Explore remain useful for an account without history.
                if shows(window) {
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        DulcetShelfHeader(title: DulcetListenNowPicks.shelfTitle(shelf, items: window.items), identifier: "dulcet.listenNow.\(shelf.rawValue).all") {
                            store.pushReaderPage(.listenNowShelf(shelf))
                        }
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: DulcetLibraryMetrics.shelfTileSpacing) {
                                ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                                    if item.kind == .track, let track = item.playableTrack {
                                        Button {
                                            store.playReaderTracks(window.playableTracks, startingAt: track,
                                                sourceKind: .library, sourceID: nil, sourceName: shelf.title)
                                        } label: {
                                            VStack(alignment: .leading) {
                                                DulcetArtworkView(artwork: item.artwork, size: tileWidth)
                                                Text(item.displayTitle).lineLimit(textSize.isAccessibilitySize ? nil : 2)
                                                Text(verbatim: item.artistName ?? "").font(.caption)
                                            }.frame(width: tileWidth, alignment: .leading)
                                        }
                                        .dulcetMediaButtonStyle(hover: .lift)
                                        .accessibilityLabel(DulcetStrings.homePlay(item.displayTitle))
                                        .accessibilityIdentifier("dulcet.listenNow.\(shelf.rawValue).track.\(index)")
                                        .modifier(DulcetListenNowArrivalFocus(active: takesArrivalFocus && index == 0))
                                    } else {
                                        DulcetReaderTile(item: item, width: tileWidth)
                                            .dulcetZoomSource(item.route.flatMap { heroRoutes.contains($0) ? nil : $0 }, in: zoom)
                                            .modifier(DulcetListenNowArrivalFocus(active: takesArrivalFocus && index == 0))
                                            .dulcetReaderRow(index, in: model)
                                    }
                                }
                            }
                            .padding(.vertical, DulcetSpacing.xs)
                        }
                        // The shelf runs to the page's edges and scrolls under them, its first
                        // tile still on the page's leading inset (Music's shelves do the same).
                        .modifier(DulcetEdgeToEdgeShelf(pageWidth: width))
#if os(tvOS)
                        .focusSection()
#endif
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("dulcet.listenNow.\(shelf.rawValue)")
                    .dulcetReaderFreshness(window.freshness)
                    // Only a shown shelf is spaced: hidden ones between two shelves once added
                    // a stack gap each, three of them about 50 points of blank page.
                    .padding(.bottom, DulcetSpacing.md)
                } else if shelf == .newest {
                    if case let .unavailable(reason) = window.freshness, !reason.isConnectionFailure {
                        DulcetReaderUnavailableView(reason: reason, subject: .list)
                    } else if window.freshness == .loading {
                        ProgressView().frame(maxWidth: .infinity, minHeight: 100)
                    }
                }
            } else if shelf == .newest {
                ProgressView().frame(maxWidth: .infinity, minHeight: 100)
            }
            }
            .preference(key: DulcetRecentlyPlayedItems.self,
                value: shelf == .recent ? model.window?.items.map(\.id) ?? [] : [])
            .preference(key: DulcetReaderServerFailure.self, value: model.window?.freshness.isConnectionFailure == true)
            .preference(key: DulcetShownListenNowShelves.self,
                value: model.window.map(shows) == true ? [shelf] : [])
            // The hero can still open a saved shelf whose standalone row is hidden offline.
            .preference(key: DulcetListenNowShelfItems.self,
                value: model.window.flatMap { $0.hasContent ? [shelf: $0.items] : nil } ?? [:])
        }
    }
    private func shows(_ window: DulcetLibraryWindow) -> Bool {
        let offline = store.librarySession?.ownsConnectionMessage == true
        if offline && [.newest, .random, .rediscover].contains(shelf) { return false }
        return window.hasContent && !(shelf == .frequent && window.items.map(\.id) == recentItems)
    }
    private var tileWidth: CGFloat {
#if os(tvOS)
        // Five and a half across the screen: titles stay readable from the sofa, and the cut
        // tile says the shelf scrolls.
        300
#else
        textSize.isAccessibilitySize ? min(260, width - 48) : min(200, max(140, (width - 48) / 2.2))
#endif
    }
}
struct DulcetListenNowShelfPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dulcetListenNowShown) private var shown
    let shelf: DulcetListenNowShelf
    var body: some View {
        DulcetReaderScreen(query: .listenNow(shelf), showsDestinationStatus: true) { model in
            DulcetReaderListPage(title: DulcetListenNowPicks.shelfTitle(shelf, items: model.window?.items ?? handedOver),
                                 freshness: model.window?.freshness) { width in
#if os(iOS)
                if model.window == nil, !handedOver.isEmpty {
                    // The page opens as it zooms out of its card: the tracks Listen Now already
                    // drew stand in for the few frames its own window takes to open, rather than
                    // a spinner on an empty page.
                    handedOverRows
                } else {
                    DulcetReaderListRows(model: model) { window in
                        shelfRows(window, model: model, width: width)
                    }
                }
#else
                DulcetReaderListBody(model: model) { window in
                    shelfRows(window, model: model, width: width)
                }
#endif
            }
        }
    }

    /// What Listen Now showed of this shelf, when it is tracks.
    private var handedOver: [DulcetReaderItem] {
        let items = shown[shelf] ?? []
        return items.first?.kind == .track ? items : []
    }

#if os(iOS)
    private var handedOverRows: some View {
        let items = handedOver
        return ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
            DulcetReaderTrackRow(item: item, index: index + 1) { track in
                store.playReaderTracks(items.compactMap(\.playableTrack), startingAt: track,
                    sourceKind: .library, sourceID: nil, sourceName: shelf.title)
            }
            .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: true, artwork: true)
        }
    }
#endif

    @ViewBuilder
    private func shelfRows(_ window: DulcetLibraryWindow, model: DulcetLibraryWindowModel, width: CGFloat) -> some View {
        if window.items.first?.kind == .track {
            ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                DulcetReaderTrackRow(item: item, index: index + 1) { track in
                    store.playReaderTracks(window.playableTracks, startingAt: track,
                        sourceKind: .library, sourceID: nil, sourceName: shelf.title)
                }
                .dulcetReaderRow(index, in: model)
                .dulcetSongRowSeparator(above: index > 0, songRowShowingIndex: true, artwork: true)
            }
        } else {
            DulcetReaderGrid(model: model, width: width)
        }
    }
}
#endif
