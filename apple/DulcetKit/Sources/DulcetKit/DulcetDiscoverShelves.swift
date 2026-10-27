import Foundation

public struct DulcetDiscoverPlaylistSummary: Sendable, Hashable {
    public var id: String
    public var title: String
    public var creator: String?
    public var trackCount: Int?
    public init(id: String, title: String, creator: String? = nil, trackCount: Int? = nil) {
        self.id = id
        self.title = title
        self.creator = creator
        self.trackCount = trackCount
    }
}

public struct DulcetDiscoverPlaylistEntry: Sendable, Hashable {
    public var position: Int
    public var item: DulcetDiscoverItem
    public init(position: Int, item: DulcetDiscoverItem) {
        self.position = position
        self.item = item
    }
}

public struct DulcetDiscoverPlaylistList: Sendable, Hashable {
    public var items: [DulcetDiscoverPlaylistSummary]
    public var nextCursor: String?
    public var age: DulcetDiscoverAge
    public var refreshError: DulcetDiscoverFailure?
    public init(items: [DulcetDiscoverPlaylistSummary] = [], nextCursor: String? = nil, age: DulcetDiscoverAge, refreshError: DulcetDiscoverFailure? = nil) {
        self.items = items
        self.nextCursor = nextCursor
        self.age = age
        self.refreshError = refreshError
    }
}

public struct DulcetDiscoverPlaylist: Sendable, Hashable {
    public var summary: DulcetDiscoverPlaylistSummary
    public var entries: [DulcetDiscoverPlaylistEntry]
    public var nextCursor: String?
    public var age: DulcetDiscoverAge
    public var refreshError: DulcetDiscoverFailure?
    public init(summary: DulcetDiscoverPlaylistSummary, entries: [DulcetDiscoverPlaylistEntry] = [], nextCursor: String? = nil, age: DulcetDiscoverAge, refreshError: DulcetDiscoverFailure? = nil) {
        self.summary = summary
        self.entries = entries
        self.nextCursor = nextCursor
        self.age = age
        self.refreshError = refreshError
    }
}

public struct DulcetDiscoverShelfPage: Sendable, Hashable {
    public var id: String
    public var items: [DulcetDiscoverItem]
    public var playlists: [DulcetDiscoverPlaylistSummary]
    public var nextCursor: String?
    public var age: DulcetDiscoverAge
    public var refreshError: DulcetDiscoverFailure?
    public init(id: String, items: [DulcetDiscoverItem] = [], playlists: [DulcetDiscoverPlaylistSummary] = [], nextCursor: String? = nil, age: DulcetDiscoverAge, refreshError: DulcetDiscoverFailure? = nil) {
        self.id = id
        self.items = items
        self.playlists = playlists
        self.nextCursor = nextCursor
        self.age = age
        self.refreshError = refreshError
    }
}

public struct DulcetDiscoverShelfState: Sendable, Hashable {
    public var id: String
    public var page: DulcetDiscoverShelfPage?
    public var loading: Bool
    public var error: DulcetDiscoverFailure?
    public init(id: String, page: DulcetDiscoverShelfPage? = nil, loading: Bool = false, error: DulcetDiscoverFailure? = nil) {
        self.id = id
        self.page = page
        self.loading = loading
        self.error = error
    }
}

public struct DulcetDiscoverHome: Sendable, Hashable {
    public var shelves: [DulcetDiscoverShelfState]
    public var recentWishes: [DulcetWishRow]
    public var wishesLoading: Bool
    public var wishesSettled: Bool
    public var wishesError: DulcetDiscoverFailure?
    public init(shelves: [DulcetDiscoverShelfState] = [], recentWishes: [DulcetWishRow] = [], wishesLoading: Bool = false, wishesSettled: Bool = false, wishesError: DulcetDiscoverFailure? = nil) {
        self.shelves = shelves
        self.recentWishes = recentWishes
        self.wishesLoading = wishesLoading
        self.wishesSettled = wishesSettled
        self.wishesError = wishesError
    }
}

