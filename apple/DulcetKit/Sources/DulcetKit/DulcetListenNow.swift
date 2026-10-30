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
    var body: some View {
        DulcetReaderPage(title: DulcetStrings.listenNow) { width in
            ForEach(DulcetListenNowShelf.allCases) { shelf in
                DulcetListenNowShelfView(shelf: shelf, width: width, recentItems: recentItems)
            }
        }
        .onPreferenceChange(DulcetRecentlyPlayedItems.self) { recentItems = $0 }
#if !os(tvOS)
        .refreshable { store.librarySession?.reconnect() }
#endif
#if os(macOS) || os(tvOS)
        .toolbar { ToolbarItem(placement: .primaryAction) { DulcetListenNowRefresh() } }
#endif
        .accessibilityIdentifier("dulcet.listenNow")
        .overlay(alignment: .top) { DulcetReaderAccountBanner() }
    }
}

private struct DulcetListenNowRefresh: View {
    @Environment(DulcetPresentationStore.self) private var store
#if os(tvOS)
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
    @FocusState private var focused: Bool
#endif
    var body: some View {
        Button(DulcetStrings.homeRefresh, systemImage: "arrow.clockwise") { store.librarySession?.reconnect() }
            .accessibilityIdentifier("dulcet.listenNow.refresh")
#if os(macOS)
            .keyboardShortcut("r", modifiers: .command)
#endif
#if os(tvOS)
            .focused($focused)
            .onAppear(perform: claimFocus)
            .onChange(of: arrivalFocus?.wantsSectionFocus == true) { _, wants in
                if wants { claimFocus() }
            }
            .onChange(of: focused) { _, value in
                if value { arrivalFocus?.sectionControlFocused() }
            }
#endif
    }
#if os(tvOS)
    private func claimFocus() {
        guard let arrivalFocus, arrivalFocus.pending, store.readerPath.isEmpty else { return }
        focused = true
    }
#endif
}

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
    let shelf: DulcetListenNowShelf
    let width: CGFloat
    let recentItems: [DulcetProviderItemID]
    var body: some View {
        DulcetReaderScreen(query: .listenNow(shelf)) { model in
            // Keep a concrete view alive before the first publication. An EmptyView does not
            // receive the reader's onAppear, so it can never open the query that would fill it.
            VStack(alignment: .leading, spacing: 0) {
            if let window = model.window {
                // Unsupported and empty personalized shelves disappear on a plain server. Newest
                // and Explore remain useful for an account without history.
                if window.hasContent && !(shelf == .frequent && window.items.map(\.id) == recentItems) {
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        DulcetShelfHeader(title: shelf.title, identifier: "dulcet.listenNow.\(shelf.rawValue).all") {
                            store.pushReaderPage(.listenNowShelf(shelf))
                        }
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(alignment: .top, spacing: DulcetSpacing.sm) {
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
                                    } else {
                                        DulcetReaderTile(item: item, width: tileWidth)
                                            .dulcetReaderRow(index, in: model)
                                    }
                                }
                            }
                            .padding(.vertical, DulcetSpacing.xs)
                        }
#if os(tvOS)
                        .focusSection()
#endif
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("dulcet.listenNow.\(shelf.rawValue)")
                    .dulcetReaderFreshness(window.freshness)
                } else if shelf == .newest {
                    if case let .unavailable(reason) = window.freshness {
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
        }
    }
    private var tileWidth: CGFloat {
#if os(tvOS)
        220
#else
        textSize.isAccessibilitySize ? min(260, width - 48) : min(200, max(140, (width - 48) / 2.2))
#endif
    }
}
struct DulcetListenNowShelfPage: View {
    @Environment(DulcetPresentationStore.self) private var store
    let shelf: DulcetListenNowShelf
    var body: some View {
        DulcetReaderScreen(query: .listenNow(shelf), showsDestinationStatus: true) { model in
            DulcetReaderPage(title: shelf.title) { width in
                DulcetReaderListBody(model: model) { window in
                    if window.items.first?.kind == .track {
                        ForEach(Array(window.items.enumerated()), id: \.element.id) { index, item in
                            DulcetReaderTrackRow(item: item, index: index + 1) { track in
                                store.playReaderTracks(window.playableTracks, startingAt: track,
                                    sourceKind: .library, sourceID: nil, sourceName: shelf.title)
                            }
                            .dulcetReaderRow(index, in: model)
                        }
                    } else {
                        DulcetReaderGrid(model: model, width: width)
                    }
                }
            }
        }
    }
}
#endif
