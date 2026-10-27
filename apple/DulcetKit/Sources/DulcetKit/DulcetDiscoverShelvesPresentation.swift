import Foundation
import Observation

public enum DulcetDiscoverShelf: String, CaseIterable, Sendable {
    case trendingArtists, trendingAlbums, trendingSongs, newReleases, similarArtists, publicPlaylists
    public var title: String {
        switch self {
        case .trendingArtists: "Artists"
        case .trendingAlbums: "Albums"
        case .trendingSongs: "Songs"
        case .newReleases: "New releases"
        case .similarArtists: "Artists like yours"
        case .publicPlaylists: "Public playlists"
        }
    }
    public var emptyText: String {
        switch self {
        case .trendingArtists: "No trending artists right now."
        case .trendingAlbums: "No trending albums right now."
        case .trendingSongs: "No trending songs right now."
        case .newReleases: "No new releases right now."
        case .similarArtists: "Play or favourite music on your server to find artists like yours."
        case .publicPlaylists: "No public playlists found."
        }
    }
}

public struct DulcetDiscoverShelfPresentation {
    public let state: DulcetDiscoverShelfState
    public init(_ state: DulcetDiscoverShelfState) { self.state = state }
    public var isEmpty: Bool {
        !state.loading && state.error == nil && state.page?.refreshError == nil &&
        state.page.map { $0.items.isEmpty && $0.playlists.isEmpty } == true
    }
    public var showsAll: Bool { state.page?.nextCursor != nil }
    public var source: DulcetWishSource { .init(type: "shelf", id: state.id) }
}

enum DulcetDiscoverHomeRoute: Hashable {
    case shelf(String), playlists, playlist(String), metadata(DulcetDiscoverDestination, DulcetWishSource)
}

/// Retained by the presentation store across compact/sidebar reconstruction, scoped to its reader.
@MainActor @Observable final class DulcetDiscoverNavigation {
    var path: [DulcetDiscoverHomeRoute] = []
    var homeScroll: String?
    private var pages: [DulcetDiscoverHomeRoute: DulcetDiscoverListModel] = [:]
    func page(_ route: DulcetDiscoverHomeRoute, reader: any DulcetDiscoverReading) -> DulcetDiscoverListModel {
        if let page = pages[route] { return page }
        let page = DulcetDiscoverListModel(reader: reader, route: route)
        pages[route] = page
        return page
    }
    func close() { pages.values.forEach { $0.close() }; pages = [:]; path = []; homeScroll = nil }
}

/// UI request selection and page assembly only. Admission, size, cursors, cache and retry
/// deadlines belong to the account core. A cursor expiry replaces, and never appends page one.
@MainActor @Observable final class DulcetDiscoverListModel {
    let reader: any DulcetDiscoverReading
    let route: DulcetDiscoverHomeRoute
    var shelf: DulcetDiscoverShelfPage?
    var playlists: DulcetDiscoverPlaylistList?
    var playlist: DulcetDiscoverPlaylist?
    var query = ""
    var loading = false
    var error: DulcetDiscoverFailure?
    var scrollID: String?
    @ObservationIgnored private var operation: (any DulcetLibraryReaderCancellable)?
    @ObservationIgnored private let selection = DulcetDiscoverDetailSelection()
    init(reader: any DulcetDiscoverReading, route: DulcetDiscoverHomeRoute) { self.reader = reader; self.route = route }
    var hasPage: Bool { shelf != nil || playlists != nil || playlist != nil }
    var nextCursor: String? { shelf?.nextCursor ?? playlists?.nextCursor ?? playlist?.nextCursor }
    var age: DulcetDiscoverAge? { shelf?.age ?? playlists?.age ?? playlist?.age }
    var source: DulcetWishSource {
        switch route {
        case let .shelf(id): .init(type: "shelf", id: id)
        case let .playlist(id): .init(type: "playlist", id: id)
        default: .init(type: "shelf", id: "publicPlaylists")
        }
    }
    func open() { if !hasPage { load() } }
    func more() { guard !loading, let cursor = nextCursor else { return }; load(cursor: cursor) }
    func load(cursor: String? = nil) {
        let generation = selection.begin()
        operation?.cancel(); loading = true; error = nil
        switch route {
        case let .shelf(id):
            operation = reader.shelf(id: id, cursor: cursor) { [weak self] result in
                guard let self, self.selection.accepts(generation) else { return }
                self.loading = false
                switch result {
                case var .success(page):
                    if cursor != nil { page.items = (self.shelf?.items ?? []) + page.items; page.playlists = (self.shelf?.playlists ?? []) + page.playlists }
                    self.shelf = page; self.error = page.refreshError
                case let .failure(error): self.failed(error, cursor: cursor)
                }
            }
        case .playlists:
            operation = reader.playlists(query: query, cursor: cursor) { [weak self] result in
                guard let self, self.selection.accepts(generation) else { return }
                self.loading = false
                switch result {
                case var .success(page):
                    if cursor != nil { page.items = (self.playlists?.items ?? []) + page.items }
                    self.playlists = page; self.error = page.refreshError
                case let .failure(error): self.failed(error, cursor: cursor)
                }
            }
        case let .playlist(id):
            operation = reader.playlist(mbid: id, cursor: cursor) { [weak self] result in
                guard let self, self.selection.accepts(generation) else { return }
                self.loading = false
                switch result {
                case var .success(page):
                    // Entry position, never recording id, identifies an occurrence.
                    if cursor != nil { page.entries = (self.playlist?.entries ?? []) + page.entries }
                    self.playlist = page; self.error = page.refreshError
                case let .failure(error): self.failed(error, cursor: cursor)
                }
            }
        case .metadata: loading = false
        }
    }
    private func failed(_ error: DulcetDiscoverFailure, cursor: String?) {
        if error.code == "cursor-expired", cursor != nil { load(); return }
        if error.code != "cancelled" { self.error = error }
    }
    func submitQuery() {
        // Keep a failed replacement's previous answer visible, labelled with its query.
        load()
    }
    func close() { selection.close(); operation?.cancel(); operation = nil; loading = false }
}
