#if os(iOS) || os(macOS)
import SwiftUI

struct DulcetDiscoverHomeView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @State private var model: DulcetDiscoverModel
    @Bindable var navigation: DulcetDiscoverNavigation
    init(reader: any DulcetDiscoverReading, navigation: DulcetDiscoverNavigation) {
        _model = State(initialValue: .init(reader: reader)); self.navigation = navigation
    }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: DulcetSpacing.lg) {
#if os(macOS)
                // The Mac window's title bar is small, so the page carries its own large title.
                // On iPhone and iPad the navigation bar's large title is the page's one title.
                HStack {
                    Text(DulcetStrings.discoverTitle).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                    Spacer()
                    refreshButton
                }
#endif
                if let home = model.publication?.home {
                    ForEach(DulcetDiscoverShelf.allCases, id: \.rawValue) { shelf in
                        if let state = home.shelves.first(where: { $0.id == shelf.rawValue }) {
                            shelfView(shelf, state)
                                .id(shelf.rawValue)
                        }
                    }
                    if model.publication?.access.wishlistAvailable == true { wishes(home).id("recentWishes") }
                } else { ProgressView(DulcetStrings.discoverLoading) }
                Text(DulcetStrings.discoverCredit).font(.caption).accessibilityIdentifier("discover.credit")
            }
            .padding(DulcetSpacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .scrollTargetLayout()
        }
        .scrollPosition(id: $navigation.homeScroll, anchor: .top)
#if os(iOS)
        // A refresh is pulled, as in every iOS list; the shelves keep what they show meanwhile.
        .refreshable { await refreshHomeAndSettle() }
        .toolbar { ToolbarItem(placement: .primaryAction) { refreshButton } }
#endif
        .accessibilityIdentifier("discover.home")
        .navigationTitle(DulcetStrings.discoverTitle)
        .dulcetForeground(.primaryTextOnWindow)
        .onAppear { model.open(); model.reader.refreshHome() }
        .onDisappear { model.close() }
    }
    private var refreshButton: some View {
        Button(DulcetStrings.homeRefresh, systemImage: "arrow.clockwise") { model.reader.refreshHome() }
            .accessibilityIdentifier("discover.home.refresh")
    }
    /// Holds the pull-to-refresh indicator until the shelves have answered, for at most ten seconds.
    private func refreshHomeAndSettle() async {
        model.reader.refreshHome()
        let deadline = ContinuousClock.now + .seconds(10)
        try? await Task.sleep(for: .milliseconds(300))
        while ContinuousClock.now < deadline, !Task.isCancelled,
              model.publication?.home?.shelves.contains(where: \.loading) == true {
            try? await Task.sleep(for: .milliseconds(150))
        }
    }
    private func shelfView(_ shelf: DulcetDiscoverShelf, _ state: DulcetDiscoverShelfState) -> some View {
        let presentation = DulcetDiscoverShelfPresentation(state)
        let prefix = "discover.shelf.\(shelf.rawValue)"
        // The shelf's age is read with its heading rather than printed under it.
        let shelfAge = DulcetDiscoverStatus(age: state.page?.age, error: nil, connection: "online").accessibleAge ?? String()
        return VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if shelf == .trendingArtists {
                Text(DulcetStrings.discoverTrending).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            }
            HStack {
                Text(shelf.title).font(.title3.bold()).accessibilityAddTraits(.isHeader)
                    .accessibilityValue(shelfAge)
                Spacer()
                if presentation.showsAll {
                    Button(DulcetStrings.discoverSeeAll) { navigation.path.append(shelf == .publicPlaylists ? .playlists : .shelf(shelf.rawValue)) }
                        .accessibilityLabel(DulcetStrings.discoverSeeAllNamed(shelf.title)).accessibilityIdentifier(prefix + ".all")
                }
            }
            if shelf == .similarArtists {
                Text(DulcetStrings.discoverSuggestionsCredit).font(.caption).accessibilityIdentifier("discover.suggestionsCredit")
            }
            DulcetDiscoverShelfStatus(age: state.page?.age, error: state.error ?? state.page?.refreshError,
                                     loading: state.loading, empty: presentation.isEmpty,
                                     emptyText: shelf.emptyText, connection: model.publication?.connection ?? "online", prefix: prefix) {
                model.reader.refreshHome()
            }
            if let page = state.page {
                ScrollView(.horizontal) {
                    HStack(alignment: .top, spacing: DulcetSpacing.xs) {
                        ForEach(page.items, id: \.id) { item in
                            DulcetDiscoverShelfItem(model: model, item: item, source: presentation.source,
                                identifier: prefix + ".item." + item.id, style: .tile, navigate: { navigation.path.append(.metadata($0, presentation.source)) }, openLibrary: store.openDiscoverLibrary)
                        }
                        ForEach(page.playlists, id: \.id) { playlist in
                            playlistButton(playlist, prefix: prefix + ".playlist.")
                        }
                    }
                }
                .scrollClipDisabled()
            }
        }.accessibilityElement(children: .contain).accessibilityIdentifier(prefix)
    }
    private func playlistButton(_ playlist: DulcetDiscoverPlaylistSummary, prefix: String) -> some View {
        Button { navigation.path.append(.playlist(playlist.id)) } label: { DulcetDiscoverPlaylistTile(summary: playlist) }
            .buttonStyle(.plain).accessibilityIdentifier(prefix + playlist.id)
    }
    private func wishes(_ home: DulcetDiscoverHome) -> some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            HStack {
                Text(DulcetStrings.discoverRecentWishes).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                Spacer()
                Button(DulcetStrings.discoverSeeAll) { store.openWishlist() }.accessibilityLabel(DulcetStrings.discoverSeeAllRecentWishes).accessibilityIdentifier("discover.recentWishes.all")
            }
            DulcetDiscoverShelfStatus(age: nil, error: home.wishesError, loading: home.wishesLoading,
                hasContent: !home.recentWishes.isEmpty,
                empty: home.wishesSettled && home.recentWishes.isEmpty && home.wishesError == nil,
                emptyText: DulcetStrings.discoverRecentWishesEmpty, connection: model.publication?.connection ?? "online", prefix: "discover.recentWishes") { model.reader.refreshHome() }
            ForEach(home.recentWishes, id: \.key) { row in
                let value = DulcetWishlistRowPresentation(row: row)
                Button { store.openWishlist(rowKey: row.key) } label: {
                    VStack(alignment: .leading) {
                        Text(verbatim: value.snapshot?.title ?? "").font(.headline)
                        if let artist = value.snapshot?.artist { Text(verbatim: artist) }
                        Text(value.stateTitle).font(.caption)
                        if let pending = value.pendingText { Text(pending) }
                        if let error = value.errorText { Text(error) }
                    }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityIdentifier("discover.recentWishes.item." + row.key)
            }
        }.accessibilityElement(children: .contain).accessibilityIdentifier("discover.recentWishes")
    }
}

struct DulcetDiscoverShelfStatus: View {
    let age: DulcetDiscoverAge?
    let error: DulcetDiscoverFailure?
    let loading: Bool
    /// Whether the shelf shows rows; by default, whether it has an answer with an age.
    var hasContent: Bool? = nil
    let empty: Bool
    let emptyText: String
    let connection: String
    let prefix: String
    let retry: () -> Void
    @State private var retryNow = Date.distantPast
    private var status: DulcetDiscoverStatus { .init(age: age, error: error, connection: connection, now: max(retryNow, .now)) }
    private var failureNotice: String {
        if error?.code == "rate-limited" { return status.notice ?? DulcetStrings.discoverUnavailable }
        if connection == "offline" || error?.code == "offline" { return DulcetStrings.discoverConnect }
        return DulcetStrings.discoverUnavailable
    }
    private var showsRows: Bool { hasContent ?? (age != nil) }
    private var failed: Bool { error.map { $0.code != "cancelled" } ?? false }
    private var rateLimited: Bool { error?.code == "rate-limited" }
    // A shelf with its answer says nothing while that answer is current or refreshing (§28,
    // 2026-11-24). Offline, a stale answer or a failed refresh is one plain line with its age;
    // a failure without an answer to show is stated in its place.
    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            if age != nil, let line = status.ageText {
                Text(line).font(.caption)
                    .accessibilityIdentifier(prefix + (failed && !rateLimited ? ".error" : ".age"))
            }
            if failed {
                if age == nil || rateLimited {
                    Text(failureNotice)
                        .accessibilityIdentifier(prefix + ".error")
                }
                if error?.retryable == true {
                    if let deadline = error?.retryAt, !status.canRetry, connection != "offline" {
                        Text(DulcetStrings.discoverRetryAt(deadline.formatted(date: .omitted, time: .shortened))).font(.caption)
                    }
                    Button(DulcetStrings.discoverRetry, action: retry).disabled(!status.canRetry).accessibilityIdentifier(prefix + ".retry")
                }
            }
            // A refresh over rows already shown is quiet; only a shelf with nothing yet shows progress.
            if loading && !showsRows { ProgressView(DulcetStrings.discoverLoading).accessibilityIdentifier(prefix + ".loading") }
            if empty { Text(emptyText).accessibilityIdentifier(prefix + ".empty") }
        }
        .dulcetForeground(.secondaryTextOnWindow)
        .fixedSize(horizontal: false, vertical: true)
        .task(id: error?.retryAt) {
            retryNow = .distantPast
            guard let deadline = error?.retryAt else { return }
            try? await Task.sleep(for: .seconds(max(0, deadline.timeIntervalSinceNow)))
            if !Task.isCancelled { retryNow = .now }
        }
    }
}

/// One Discover entry. On the home shelves it is an artwork tile: the cover leads, the title and one
/// quiet line sit under it, and the library and wish state are small marks on the cover rather than
/// a sentence and a button under every tile. In lists it is a row with the same marks trailing.
struct DulcetDiscoverShelfItem: View {
    enum Style { case tile, row }
    static let tileSide = DulcetDiscoverMetrics.tileSide
    let model: DulcetDiscoverModel
    let item: DulcetDiscoverItem
    let source: DulcetWishSource
    let identifier: String
    var style: Style = .row
    let navigate: (DulcetDiscoverDestination) -> Void
    let openLibrary: (DulcetDiscoverLibraryRef) -> Void
    private var row: DulcetWishRow? { model.row(for: item) }
    private var accessibilityTitle: String {
        [item.title, item.artist, DulcetDiscoverItemPresentation(item: item).availabilityText,
         row?.pending == true ? DulcetStrings.discoverWaiting : nil].compactMap { $0 }.joined(separator: ", ")
    }
    private var wished: Bool { row?.wish != nil || row?.pending == true }
    private var held: Bool { item.availability.status == "held" }
    private var canWish: Bool { model.publication?.access.canAddWish == true && DulcetDiscoverItemPresentation(item: item).canWish }
    /// The one library reference that a tap or the menu may open, as the tap has always required.
    private var libraryReference: DulcetDiscoverLibraryRef? {
        guard item.target?.kind == item.kind, ["artist", "album", "song"].contains(item.kind),
              held, (item.kind != "album" || item.availability.completeness == "known"),
              item.availability.libraryItems.count == 1 else { return nil }
        return item.availability.libraryItems.first
    }
    private func open() {
        if let libraryReference { openLibrary(libraryReference) } else { navigate(.init(item: item, source: source)) }
    }
    private var subtitle: String? {
        if let artist = item.artist { return artist }
        return item.listenCount.map { DulcetStrings.discoverListens($0) }
    }
    var body: some View {
        Group {
            switch style {
            case .tile: tile
            case .row: listRow
            }
        }
        .contextMenu {
            if canWish && !wished {
                Button(DulcetStrings.discoverWishFor(item.title), systemImage: "bookmark") { model.add(item, source: source) }
            }
            if let libraryReference {
                Button(DulcetStrings.discoverOpenLibrary, systemImage: "music.note.list") { openLibrary(libraryReference) }
            }
        }
    }
    private var tile: some View {
        let side = Self.tileSide
        let circle = item.kind == "artist"
        return VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            ZStack(alignment: .topLeading) {
                Button(action: open) {
                    VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                        DulcetDiscoverArtworkView(reader: model.reader, artworkId: item.artworkId, size: side,
                                                  shape: circle ? .circle : .rounded, title: item.title)
                        VStack(alignment: circle ? .center : .leading, spacing: DulcetDiscoverMetrics.captionLineSpacing) {
                            Text(verbatim: item.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                                .dulcetForeground(.primaryTextOnWindow)
                            if let subtitle {
                                Text(verbatim: subtitle).font(.caption).lineLimit(1).dulcetForeground(.secondaryTextOnWindow)
                            }
                        }.frame(width: side, alignment: circle ? .center : .leading)
                         .multilineTextAlignment(circle ? .center : .leading)
                    }.contentShape(Rectangle())
                }.buttonStyle(.plain).accessibilityIdentifier(identifier).accessibilityLabel(accessibilityTitle)
                marks.padding(circle ? DulcetDiscoverMetrics.circleMarkInset : 0).frame(width: side, height: side, alignment: .bottomTrailing)
            }
            notes
        }.frame(width: side, alignment: .leading)
    }
    private var listRow: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            HStack(spacing: 0) {
                Button(action: open) { DulcetDiscoverItemRow(item: item, reader: model.reader, quietAvailability: true) }
                    .buttonStyle(.plain).accessibilityIdentifier(identifier).accessibilityLabel(accessibilityTitle)
                marks.padding(.leading, DulcetSpacing.xxs)
            }
            notes
        }.fixedSize(horizontal: false, vertical: true)
    }
    /// A wish already made, a held item, or the one quiet control to make a wish. Never a sentence.
    @ViewBuilder private var marks: some View {
        if wished {
            badge("bookmark.fill").accessibilityLabel(DulcetStrings.discoverWishedFor(item.title))
                .accessibilityIdentifier(identifier + ".wished").allowsHitTesting(false)
        } else if canWish {
            Button { model.add(item, source: source) } label: { badge("bookmark") }
                .buttonStyle(.plain).accessibilityLabel(DulcetStrings.discoverWishFor(item.title))
                .accessibilityIdentifier(identifier + ".wish")
        } else if held {
            badge("checkmark").accessibilityHidden(true).allowsHitTesting(false)
        }
    }
    private func badge(_ symbol: String) -> some View {
        Image(systemName: symbol).font(.footnote.weight(.semibold)).dulcetForeground(.primaryTextOnRegularMaterial)
            .frame(width: 30, height: 30).background(.regularMaterial, in: Circle())
            .padding(DulcetDiscoverMetrics.markHitInset).contentShape(Rectangle())
    }
    @ViewBuilder private var notes: some View {
        if row?.pending == true { Text(DulcetStrings.discoverWaiting).font(.caption) }
        if row?.error != nil || model.mutationError != nil { Text(DulcetStrings.discoverNotSaved).font(.caption) }
        if item.target == nil {
            Text(DulcetStrings.discoverRecordingUnavailable).font(.caption).dulcetForeground(.secondaryTextOnWindow)
        }
    }
}

/// A public playlist as a tile on the home shelf: no cover to show, so a quiet square with the list mark.
struct DulcetDiscoverPlaylistTile: View {
    let summary: DulcetDiscoverPlaylistSummary
    var body: some View {
        let side = DulcetDiscoverShelfItem.tileSide
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            Color.dulcetControl.frame(width: side, height: side)
                .overlay { Image(systemName: "music.note.list").font(.system(size: side * 0.3)).dulcetForeground(.secondaryTextOnControl) }
                .clipShape(RoundedRectangle(cornerRadius: DulcetDiscoverMetrics.artworkCornerRadius(forSize: side), style: .continuous)).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: DulcetDiscoverMetrics.captionLineSpacing) {
                Text(verbatim: summary.title).font(.subheadline.weight(.semibold)).lineLimit(1).dulcetForeground(.primaryTextOnWindow)
                if let creator = summary.creator { Text(verbatim: creator).font(.caption).lineLimit(1).dulcetForeground(.secondaryTextOnWindow) }
            }.frame(width: side, alignment: .leading)
        }.multilineTextAlignment(.leading).contentShape(Rectangle())
    }
}

struct DulcetDiscoverPlaylistLabel: View {
    let summary: DulcetDiscoverPlaylistSummary
    var body: some View {
        HStack {
            Image(systemName: "music.note.list").accessibilityHidden(true)
            VStack(alignment: .leading) {
                Text(verbatim: summary.title).font(.headline)
                if let creator = summary.creator { Text(verbatim: creator).font(.subheadline) }
                if let count = summary.trackCount { Text(DulcetStrings.discoverSongCount(count)).font(.caption) }
            }
            Spacer(minLength: 0)
        }.multilineTextAlignment(.leading).padding(.vertical, DulcetSpacing.sm).contentShape(Rectangle())
    }
}

struct DulcetDiscoverListView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Bindable var page: DulcetDiscoverListModel
    @State private var model: DulcetDiscoverModel
    @Bindable var navigation: DulcetDiscoverNavigation
    init(page: DulcetDiscoverListModel, navigation: DulcetDiscoverNavigation) {
        self.page = page; self.navigation = navigation; _model = State(initialValue: .init(reader: page.reader))
    }
    private var prefix: String {
        switch page.route {
        case let .shelf(id): "discover.shelfList." + id
        case .playlists: "discover.playlists"
        case let .playlist(id): "discover.playlist." + id
        case .metadata: "discover.detail"
        }
    }
    private var title: String {
        switch page.route {
        case let .shelf(id): DulcetDiscoverShelf(rawValue: id)?.title ?? DulcetStrings.discoverTitle
        case .playlists: "Public playlists"
        case .playlist: page.playlist?.summary.title ?? "Public playlist"
        case .metadata: DulcetStrings.discoverTitle
        }
    }
    private var emptyText: String {
        switch page.route {
        case let .shelf(id): DulcetDiscoverShelf(rawValue: id)?.emptyText ?? ""
        case .playlists: "No public playlists found."
        case .playlist: "This playlist has no songs."
        case .metadata: ""
        }
    }
    private var empty: Bool {
        page.hasPage && !page.loading && page.error == nil && (page.shelf?.items.isEmpty ?? true) &&
        (page.playlists?.items.isEmpty ?? true) && (page.playlist?.entries.isEmpty ?? true)
    }
    var body: some View {
        VStack(alignment: .leading) {
            if page.route == .playlists {
                HStack {
                    TextField(DulcetStrings.discoverPlaylistSearchPrompt, text: $page.query).textFieldStyle(.roundedBorder)
                        .onSubmit { page.submitQuery() }.accessibilityIdentifier("discover.playlists.query")
                    Button(DulcetStrings.search) { page.submitQuery() }.accessibilityIdentifier("discover.playlists.submit")
                }.padding(.horizontal, DulcetSpacing.md)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    if page.route == .playlists, page.hasPage, page.answerQuery != page.submittedQuery {
                        Text(page.answerQuery.isEmpty ? DulcetStrings.discoverShowingAllPlaylists : DulcetStrings.discoverShowingResults(page.answerQuery)).font(.caption)
                    }
                    if let summary = page.playlist?.summary { DulcetDiscoverPlaylistLabel(summary: summary) }
                    DulcetDiscoverShelfStatus(age: page.age, error: page.error, loading: page.loading, empty: empty,
                        emptyText: emptyText, connection: model.publication?.connection ?? "online", prefix: prefix) { page.load() }
                    ForEach(page.shelf?.items ?? [], id: \.id) { item in
                        itemView(item, id: prefix + ".item." + item.id).id(item.id)
                    }
                    ForEach(page.playlists?.items ?? [], id: \.id) { summary in
                        Button { navigation.path.append(.playlist(summary.id)) } label: { DulcetDiscoverPlaylistLabel(summary: summary) }
                            .buttonStyle(.plain).accessibilityIdentifier("discover.playlists.item." + summary.id).id(summary.id)
                    }
                    ForEach(page.playlist?.entries ?? [], id: \.position) { entry in
                        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                            Text(DulcetStrings.discoverSongPosition(entry.position)).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                            itemView(entry.item, id: prefix + ".entry.\(entry.position)")
                        }.id(String(entry.position))
                    }
                    if page.nextCursor != nil {
                        Button(DulcetStrings.discoverLoadMore) { page.more() }.disabled(page.loading).accessibilityIdentifier(prefix + ".more").id("more")
                    }
                    Text(DulcetStrings.discoverCredit).font(.caption)
                }.padding(DulcetSpacing.md).frame(maxWidth: .infinity, alignment: .leading).scrollTargetLayout()
            }.scrollPosition(id: $page.scrollID, anchor: .top)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(prefix).navigationTitle(title).dulcetForeground(.primaryTextOnWindow)
        .onAppear { model.open(); page.open() }.onDisappear { page.close(); model.close() }
    }
    private func itemView(_ item: DulcetDiscoverItem, id: String) -> some View {
        DulcetDiscoverShelfItem(model: model, item: item, source: page.source, identifier: id,
            navigate: { navigation.path.append(.metadata($0, page.source)) }, openLibrary: store.openDiscoverLibrary)
    }
}

struct DulcetDiscoverHomeDestination: View {
    @Environment(DulcetPresentationStore.self) private var store
    var route: DulcetDiscoverHomeRoute?
    var body: some View {
        if let session = store.librarySession, session.discoverPublication?.access.discoverAvailable == true,
           let reader = session.discover {
            Group {
                if let route {
                    switch route {
                    case let .metadata(destination, source):
                        DulcetDiscoverDetailView(reader: reader, destination: destination, openLibrary: store.openDiscoverLibrary,
                            openMetadata: { store.discoverNavigation.path.append(.metadata($0, source)) }, wishSourceOverride: source)
                    default: DulcetDiscoverListView(page: store.discoverNavigation.page(route, reader: reader), navigation: store.discoverNavigation)
                    }
                } else { DulcetDiscoverHomeView(reader: reader, navigation: store.discoverNavigation) }
            }.id(session.readerGeneration)
        }
    }
}
/// Discover's own layout tokens: the cover-led tile, its caption and the wish mark on the cover.
enum DulcetDiscoverMetrics {
    /// A shelf tile's cover, square, or a circle for an artist.
    static let tileSide: CGFloat = 156
    /// Between a tile's title and its quiet second line.
    static let captionLineSpacing: CGFloat = 2
    /// Pulls the wish mark in from the edge of a circular artist cover so it sits on the circle.
    static let circleMarkInset: CGFloat = 6
    /// Padding around the 30 pt wish mark so the tap target reaches 44 pt.
    static let markHitInset: CGFloat = 7
    /// The default artwork side for a Discover row.
    static let rowArtworkSide: CGFloat = 48
    /// Corner radius for Discover artwork `size` points wide: 10 pt on tiles, 8 pt on rows.
    static func artworkCornerRadius(forSize size: CGFloat) -> CGFloat { size > 80 ? 10 : 8 }
}
#endif
