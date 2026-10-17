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
    var body: some View {
        DulcetReaderPage(title: DulcetStrings.listenNow) { width in
            DulcetReaderAccountBanner()
            Text(DulcetStrings.homeTitle)
                .font(.largeTitle.bold())
                .accessibilityAddTraits(.isHeader)
            Text(DulcetStrings.homeBody)
                .font(.body)
                .dulcetForeground(.secondaryTextOnWindow)
            HStack {
                Button(DulcetStrings.library) { store.navigate(to: .library) }
                if store.myLibraryAvailable {
                    Button(DulcetStrings.browseSection) { store.navigate(to: .browse) }
                }
                Spacer()
                DulcetListenNowRefresh()
            }
            if let player = store.snapshot.nowPlaying, player.sessionID != nil {
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    Text(DulcetStrings.continueListening).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    Text(player.sourceDisplayName ?? player.current.title).font(.headline)
                    Text(player.current.title).dulcetForeground(.secondaryTextOnWindow)
                    Button(DulcetStrings.resume, systemImage: "play.fill") { store.sendPlaybackControl(.play) }
                        .accessibilityIdentifier("dulcet.listenNow.resume")
                }
            }
            ForEach(DulcetListenNowShelf.allCases) { shelf in
                DulcetListenNowShelfView(shelf: shelf, width: width)
            }
        }
        .accessibilityIdentifier("dulcet.listenNow")
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

private struct DulcetListenNowShelfView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.dynamicTypeSize) private var textSize
    let shelf: DulcetListenNowShelf
    let width: CGFloat
    var body: some View {
        DulcetReaderScreen(query: .listenNow(shelf)) { model in
            // Keep a concrete view alive before the first publication. An EmptyView does not
            // receive the reader's onAppear, so it can never open the query that would fill it.
            VStack(alignment: .leading, spacing: 0) {
            if let window = model.window {
                // Unsupported and empty personalized shelves disappear on a plain server. Newest
                // and Explore remain useful for an account without history.
                if window.hasContent || window.freshness != .live {
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        HStack {
                            Text(shelf.title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                            Spacer()
                            if !window.playableTracks.isEmpty {
                                Button(DulcetStrings.play, systemImage: "play.fill") {
                                    store.playReaderTracks(window.playableTracks, sourceKind: .library,
                                        sourceID: nil, sourceName: shelf.title)
                                }
                                .accessibilityLabel(DulcetStrings.homePlay(shelf.title))
                            }
                        }
                        if let line = DulcetReaderCopy.freshnessLine(window.freshness) {
                            Text(line).font(.footnote).dulcetForeground(.secondaryTextOnWindow)
                        }
                        if case let .unavailable(reason) = window.freshness, !window.hasContent {
                            Text(DulcetReaderCopy.unavailableLine(reason, subject: .list))
                                .font(.footnote).dulcetForeground(.secondaryTextOnWindow)
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
                        if window.freshness == .loading && !window.hasContent {
                            ProgressView().frame(maxWidth: .infinity, minHeight: 100)
                        }
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("dulcet.listenNow.\(shelf.rawValue)")
                }
            } else {
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    Text(shelf.title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    ProgressView().frame(maxWidth: .infinity, minHeight: 100)
                }
            }
            }
        }
    }
    private var tileWidth: CGFloat {
#if os(tvOS)
        220
#else
        textSize.isAccessibilitySize ? min(240, width - 48) : 160
#endif
    }
}
#endif
