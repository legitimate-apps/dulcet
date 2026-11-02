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
                HStack {
                    Text(DulcetStrings.discoverTitle).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button(DulcetStrings.homeRefresh, systemImage: "arrow.clockwise") { model.reader.refreshHome() }
                        .accessibilityIdentifier("discover.home.refresh")
                }
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
        .accessibilityIdentifier("discover.home")
        .navigationTitle(DulcetStrings.discoverTitle)
        .dulcetForeground(.primaryTextOnWindow)
        .onAppear { model.open(); model.reader.refreshHome() }
        .onDisappear { model.close() }
    }
    private func shelfView(_ shelf: DulcetDiscoverShelf, _ state: DulcetDiscoverShelfState) -> some View {
        let presentation = DulcetDiscoverShelfPresentation(state)
        let prefix = "discover.shelf.\(shelf.rawValue)"
        return VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            if shelf == .trendingArtists {
                Text(DulcetStrings.discoverTrending).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            }
            HStack {
                Text(shelf.title).font(.title3.bold()).accessibilityAddTraits(.isHeader)
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
                    HStack(alignment: .top, spacing: DulcetSpacing.md) {
                        ForEach(page.items, id: \.id) { item in
                            DulcetDiscoverShelfItem(model: model, item: item, source: presentation.source,
                                identifier: prefix + ".item." + item.id, navigate: { navigation.path.append(.metadata($0, presentation.source)) }, openLibrary: store.openDiscoverLibrary)
                                .frame(width: 300, alignment: .leading)
                        }
                        ForEach(page.playlists, id: \.id) { playlist in
                            playlistButton(playlist, prefix: prefix + ".playlist.")
                                .frame(width: 260, alignment: .leading)
                        }
                    }
                }
            }
        }.accessibilityElement(children: .contain).accessibilityIdentifier(prefix)
    }
    private func playlistButton(_ playlist: DulcetDiscoverPlaylistSummary, prefix: String) -> some View {
        Button { navigation.path.append(.playlist(playlist.id)) } label: { DulcetDiscoverPlaylistLabel(summary: playlist) }
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
    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            if age != nil, let ageText = status.ageText {
                VStack(alignment: .leading) {
                    Text(ageText.contains(DulcetStrings.discoverCachedAnswer) ? ageText : DulcetStrings.discoverCachedAnswerWithAge(ageText))
                    if let fetchedAt = age?.fetchedAt { Text(DulcetStrings.discoverFetchedAt(fetchedAt.formatted(date: .abbreviated, time: .shortened))) }
                    if age?.sourceAgeSeconds == nil && age?.fetchedAt == nil { Text(DulcetStrings.discoverAgeUnavailable) }
                }.font(.caption).accessibilityElement(children: .combine).accessibilityIdentifier(prefix + ".age")
            }
            if let error, error.code != "cancelled" {
                Text(failureNotice)
                    .accessibilityIdentifier(prefix + ".error")
                if error.retryable {
                    if let deadline = error.retryAt, !status.canRetry, connection != "offline" {
                        Text(DulcetStrings.discoverRetryAt(deadline.formatted(date: .omitted, time: .shortened))).font(.caption)
                    }
                    Button(DulcetStrings.discoverRetry, action: retry).disabled(!status.canRetry).accessibilityIdentifier(prefix + ".retry")
                }
            }
            if loading { ProgressView(DulcetStrings.discoverLoading).accessibilityIdentifier(prefix + ".loading") }
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

struct DulcetDiscoverShelfItem: View {
    let model: DulcetDiscoverModel
    let item: DulcetDiscoverItem
    let source: DulcetWishSource
    let identifier: String
    let navigate: (DulcetDiscoverDestination) -> Void
    let openLibrary: (DulcetDiscoverLibraryRef) -> Void
    private var row: DulcetWishRow? { model.row(for: item) }
    private var accessibilityTitle: String {
        [item.title, item.artist, DulcetDiscoverItemPresentation(item: item).availabilityText,
         row?.pending == true ? DulcetStrings.discoverWaiting : nil].compactMap { $0 }.joined(separator: ", ")
    }
    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Button {
                if item.target?.kind == item.kind, ["artist", "album", "song"].contains(item.kind),
                   item.availability.status == "held", (item.kind != "album" || item.availability.completeness == "known"),
                   item.availability.libraryItems.count == 1, let ref = item.availability.libraryItems.first {
                    openLibrary(ref)
                } else { navigate(.init(item: item, source: source)) }
            } label: { DulcetDiscoverItemRow(item: item, reader: model.reader) }
                .buttonStyle(.plain).accessibilityIdentifier(identifier)
                .accessibilityLabel(accessibilityTitle)
            if row?.pending == true { Text(DulcetStrings.discoverWaiting).font(.caption) }
            if row?.error != nil { Text(DulcetStrings.discoverNotSaved).font(.caption) }
            if row?.wish != nil || row?.pending == true {
                Label(DulcetStrings.discoverWishedFor(item.title), systemImage: "bookmark.fill")
                    .font(.callout).dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityIdentifier(identifier + ".wished")
            } else if model.publication?.access.canAddWish == true, DulcetDiscoverItemPresentation(item: item).canWish {
                Button(DulcetStrings.discoverWishFor(item.title), systemImage: "bookmark") { model.add(item, source: source) }
                    .accessibilityIdentifier(identifier + ".wish")
            }
            if item.target == nil {
                Text(DulcetStrings.discoverRecordingUnavailable).font(.caption).dulcetForeground(.secondaryTextOnWindow)
            }
            if model.mutationError != nil { Text(DulcetStrings.discoverNotSaved).font(.caption) }
        }.fixedSize(horizontal: false, vertical: true)
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
#endif
