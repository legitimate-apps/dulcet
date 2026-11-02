import Foundation

// Values copied at the Objective-C boundary. Unknown wire strings and optional numbers stay intact.
public struct DulcetDiscoverTarget: Sendable, Hashable {
    public var kind: String
    public var mbid: String
    public init(kind: String, mbid: String) {
        self.kind = kind
        self.mbid = mbid
    }
}

public struct DulcetDiscoverLibraryRef: Sendable, Hashable {
    public var providerInstanceId: String
    public var type: String
    public var id: String
    public init(providerInstanceId: String, type: String, id: String) {
        self.providerInstanceId = providerInstanceId
        self.type = type
        self.id = id
    }
}

public struct DulcetDiscoverLink: Sendable, Hashable {
    public var relation: String
    public var url: String
    public init(relation: String, url: String) {
        self.relation = relation
        self.url = url
    }
}

public struct DulcetDiscoverAge: Sendable, Hashable {
    public var fetchedAt: Date?
    public var sourceAgeSeconds: Int64?
    public var receivedAt: Date
    public var stale: Bool
    public var fromDeviceCache: Bool
    public init(fetchedAt: Date? = nil, sourceAgeSeconds: Int64? = nil, receivedAt: Date, stale: Bool = false, fromDeviceCache: Bool = false) {
        self.fetchedAt = fetchedAt
        self.sourceAgeSeconds = sourceAgeSeconds
        self.receivedAt = receivedAt
        self.stale = stale
        self.fromDeviceCache = fromDeviceCache
    }
}

public struct DulcetDiscoverAvailability: Sendable, Hashable {
    public var status: String
    public var completeness: String
    public var matchedBy: String?
    public var matchedTrackCount: Int?
    public var totalTrackCount: Int?
    public var editionReleaseId: String?
    public var libraryItems: [DulcetDiscoverLibraryRef]
    public init(status: String, completeness: String, matchedBy: String? = nil, matchedTrackCount: Int? = nil, totalTrackCount: Int? = nil, editionReleaseId: String? = nil, libraryItems: [DulcetDiscoverLibraryRef] = []) {
        self.status = status
        self.completeness = completeness
        self.matchedBy = matchedBy
        self.matchedTrackCount = matchedTrackCount
        self.totalTrackCount = totalTrackCount
        self.editionReleaseId = editionReleaseId
        self.libraryItems = libraryItems
    }
}

public struct DulcetDiscoverItem: Sendable, Hashable {
    public var id: String
    public var kind: String
    public var target: DulcetDiscoverTarget?
    public var title: String
    public var artist: String?
    public var year: Int?
    public var releaseDate: String?
    public var artworkId: String?
    public var disc: Int?
    public var position: Int?
    public var listenCount: Int64?
    public var primaryType: String?
    public var links: [DulcetDiscoverLink]
    public var wishable: Bool
    public var availability: DulcetDiscoverAvailability
    public init(id: String, kind: String, target: DulcetDiscoverTarget? = nil, title: String, artist: String? = nil, year: Int? = nil, releaseDate: String? = nil, artworkId: String? = nil, disc: Int? = nil, position: Int? = nil, listenCount: Int64? = nil, primaryType: String? = nil, links: [DulcetDiscoverLink] = [], wishable: Bool = false, availability: DulcetDiscoverAvailability) {
        self.id = id
        self.kind = kind
        self.target = target
        self.title = title
        self.artist = artist
        self.year = year
        self.releaseDate = releaseDate
        self.artworkId = artworkId
        self.disc = disc
        self.position = position
        self.listenCount = listenCount
        self.primaryType = primaryType
        self.links = links
        self.wishable = wishable
        self.availability = availability
    }
}

public struct DulcetDiscoverPage: Sendable, Hashable {
    public var items: [DulcetDiscoverItem]
    public var nextCursor: String?
    public var age: DulcetDiscoverAge
    public init(items: [DulcetDiscoverItem] = [], nextCursor: String? = nil, age: DulcetDiscoverAge) {
        self.items = items
        self.nextCursor = nextCursor
        self.age = age
    }
}

public struct DulcetDiscoverEdition: Sendable, Hashable {
    public var releaseId: String
    public var releaseDate: String?
    public var trackCount: Int?
    public var links: [DulcetDiscoverLink]
    public init(releaseId: String, releaseDate: String? = nil, trackCount: Int? = nil, links: [DulcetDiscoverLink] = []) {
        self.releaseId = releaseId
        self.releaseDate = releaseDate
        self.trackCount = trackCount
        self.links = links
    }
}

public struct DulcetDiscoverArtist: Sendable, Hashable {
    public var item: DulcetDiscoverItem
    public var albums: DulcetDiscoverPage
    public init(item: DulcetDiscoverItem, albums: DulcetDiscoverPage) {
        self.item = item
        self.albums = albums
    }
}

public struct DulcetDiscoverAlbum: Sendable, Hashable {
    public var item: DulcetDiscoverItem
    public var editions: [DulcetDiscoverEdition]
    public var selectedReleaseId: String?
    public var tracks: DulcetDiscoverPage
    public init(item: DulcetDiscoverItem, editions: [DulcetDiscoverEdition] = [], selectedReleaseId: String? = nil, tracks: DulcetDiscoverPage) {
        self.item = item
        self.editions = editions
        self.selectedReleaseId = selectedReleaseId
        self.tracks = tracks
    }
}

public struct DulcetDiscoverFailure: Error, Sendable, Hashable {
    public var code: String
    public var retryAt: Date?
    public var retryable: Bool
    public init(code: String, retryAt: Date? = nil, retryable: Bool = false) {
        self.code = code
        self.retryAt = retryAt
        self.retryable = retryable
    }
}

public struct DulcetWishSnapshot: Sendable, Hashable {
    public var title: String
    public var artist: String?
    public var year: Int?
    public var artworkId: String?
    public init(title: String, artist: String? = nil, year: Int? = nil, artworkId: String? = nil) {
        self.title = title
        self.artist = artist
        self.year = year
        self.artworkId = artworkId
    }
}

public struct DulcetWishSource: Sendable, Hashable {
    public var type: String
    public var id: String?
    public init(type: String, id: String? = nil) {
        self.type = type
        self.id = id
    }
}

public struct DulcetDiscoverWish: Sendable, Hashable {
    public var id: String
    public var userId: String
    public var target: DulcetDiscoverTarget?
    public var kind: String
    public var snapshot: DulcetWishSnapshot
    public var source: DulcetWishSource
    public var addedAt: Date
    public var updatedAt: Date
    public var state: String
    public var note: String?
    public var noteAt: Date?
    public var fulfilledAt: Date?
    public var libraryItem: DulcetDiscoverLibraryRef?
    public var availability: DulcetDiscoverAvailability
    public var revision: String
    public init(id: String, userId: String, target: DulcetDiscoverTarget? = nil, kind: String, snapshot: DulcetWishSnapshot, source: DulcetWishSource, addedAt: Date, updatedAt: Date, state: String, note: String? = nil, noteAt: Date? = nil, fulfilledAt: Date? = nil, libraryItem: DulcetDiscoverLibraryRef? = nil, availability: DulcetDiscoverAvailability, revision: String) {
        self.id = id
        self.userId = userId
        self.target = target
        self.kind = kind
        self.snapshot = snapshot
        self.source = source
        self.addedAt = addedAt
        self.updatedAt = updatedAt
        self.state = state
        self.note = note
        self.noteAt = noteAt
        self.fulfilledAt = fulfilledAt
        self.libraryItem = libraryItem
        self.availability = availability
        self.revision = revision
    }
}

public struct DulcetWishFilter: Sendable, Hashable {
    public var kind: String?
    public var state: String?
    public var includeFulfilledHistory: Bool
    public init(kind: String? = nil, state: String? = nil, includeFulfilledHistory: Bool = false) {
        self.kind = kind
        self.state = state
        self.includeFulfilledHistory = includeFulfilledHistory
    }
}

public struct DulcetDiscoverFeatureAccess: Sendable, Hashable {
    public var detection: String
    public var error: DulcetDiscoverFailure?
    public var discoverAvailable: Bool
    public var wishlistAvailable: Bool
    public var canSearch: Bool
    public var canReadWishlist: Bool
    public var canAddWish: Bool
    public var canRemoveWish: Bool
    public var canRejectMatch: Bool
    public init(detection: String, error: DulcetDiscoverFailure? = nil, discoverAvailable: Bool = false, wishlistAvailable: Bool = false, canSearch: Bool = false, canReadWishlist: Bool = false, canAddWish: Bool = false, canRemoveWish: Bool = false, canRejectMatch: Bool = false) {
        self.detection = detection
        self.error = error
        self.discoverAvailable = discoverAvailable
        self.wishlistAvailable = wishlistAvailable
        self.canSearch = canSearch
        self.canReadWishlist = canReadWishlist
        self.canAddWish = canAddWish
        self.canRemoveWish = canRemoveWish
        self.canRejectMatch = canRejectMatch
    }
}

public struct DulcetDiscoverGroup: Sendable, Hashable {
    public var kind: String
    public var page: DulcetDiscoverPage?
    public var loading: Bool
    public var error: DulcetDiscoverFailure?
    public init(kind: String, page: DulcetDiscoverPage? = nil, loading: Bool = false, error: DulcetDiscoverFailure? = nil) {
        self.kind = kind
        self.page = page
        self.loading = loading
        self.error = error
    }
}

public struct DulcetPendingWishAdd: Sendable, Hashable {
    public var operationId: String
    public var actionTime: Date
    public var target: DulcetDiscoverTarget
    public var snapshot: DulcetWishSnapshot
    public var source: DulcetWishSource
    public init(operationId: String, actionTime: Date, target: DulcetDiscoverTarget, snapshot: DulcetWishSnapshot, source: DulcetWishSource) {
        self.operationId = operationId
        self.actionTime = actionTime
        self.target = target
        self.snapshot = snapshot
        self.source = source
    }
}

public struct DulcetWishRow: Sendable, Hashable {
    public var key: String
    public var wish: DulcetDiscoverWish?
    public var pendingAdd: DulcetPendingWishAdd?
    public var pending: Bool
    public var error: DulcetDiscoverFailure?
    public init(key: String, wish: DulcetDiscoverWish? = nil, pendingAdd: DulcetPendingWishAdd? = nil, pending: Bool = false, error: DulcetDiscoverFailure? = nil) {
        self.key = key
        self.wish = wish
        self.pendingAdd = pendingAdd
        self.pending = pending
        self.error = error
    }
}

public struct DulcetDiscoverPublication: Sendable, Hashable {
    public var providerInstanceId: String
    public var generation: Int64
    public var access: DulcetDiscoverFeatureAccess
    public var connection: String
    public var query: String
    public var groups: [DulcetDiscoverGroup]
    public var wishes: [DulcetWishRow]
    public var wishlistLoading: Bool
    public var wishlistError: DulcetDiscoverFailure?
    public var wishlistSettled: Bool
    public var home: DulcetDiscoverHome?
    public var pendingChangeCount: Int64?
    public init(providerInstanceId: String, generation: Int64, access: DulcetDiscoverFeatureAccess, connection: String, query: String, groups: [DulcetDiscoverGroup] = [], wishes: [DulcetWishRow] = [], wishlistLoading: Bool = false, wishlistError: DulcetDiscoverFailure? = nil, wishlistSettled: Bool = false, pendingChangeCount: Int64? = nil, home: DulcetDiscoverHome? = nil) {
        self.providerInstanceId = providerInstanceId
        self.generation = generation
        self.access = access
        self.connection = connection
        self.query = query
        self.groups = groups
        self.wishes = wishes
        self.wishlistLoading = wishlistLoading
        self.wishlistError = wishlistError
        self.wishlistSettled = wishlistSettled
        self.home = home
        self.pendingChangeCount = pendingChangeCount
    }
}

public struct DulcetWishEnqueued: Sendable, Hashable {
    public var operationId: String
    public var rowKey: String
    public init(operationId: String, rowKey: String) {
        self.operationId = operationId
        self.rowKey = rowKey
    }
}
@MainActor public protocol DulcetDiscoverReading: AnyObject {
    func observe(_ listener: @escaping @MainActor (DulcetDiscoverPublication) -> Void) -> any DulcetLibraryReaderCancellable
    func refreshHome()
    @discardableResult func shelf(id: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverShelfPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func playlists(query: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPlaylistList, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func playlist(mbid: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPlaylist, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func setQuery(_ query: String)
    func loadMore(kind: String)
    @discardableResult func artist(mbid: String, completion: @escaping @MainActor (Result<DulcetDiscoverArtist, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func artistAlbums(mbid: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func album(mbid: String, releaseId: String?, completion: @escaping @MainActor (Result<DulcetDiscoverAlbum, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func albumTracks(mbid: String, releaseId: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func artwork(artworkId: String, completion: @escaping @MainActor (Result<Data, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func add(item: DulcetDiscoverItem, source: DulcetWishSource, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func remove(rowKey: String, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult func reject(item: DulcetDiscoverItem, libraryItem: DulcetDiscoverLibraryRef, wishId: String?, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable
    func refreshWishlist(_ filter: DulcetWishFilter)
    func close()
}

/// A delivery fence shared by the adapter's callbacks and returned cancellation handles.
/// Closing completes outstanding operations once with cancellation before revoking late results.
@MainActor public final class DulcetDiscoverLifetime {
    private var closed = false
    private var operations: [UUID: DulcetDiscoverCancellation] = [:]
    public init() {}
    public func operation(_ cancel: @escaping @MainActor () -> Void) -> any DulcetLibraryReaderCancellable {
        let id = UUID()
        let token = DulcetDiscoverCancellation { [weak self] in
            self?.operations[id] = nil
            cancel()
        }
        if closed { token.cancel() } else { operations[id] = token }
        return token
    }
    public func delivery<T>(_ completion: @escaping @MainActor (Result<T, DulcetDiscoverFailure>) -> Void) -> @MainActor (Result<T, DulcetDiscoverFailure>) -> Void {
        var completed = false
        let token = operation {
            guard !completed else { return }; completed = true
            completion(.failure(.init(code: "cancelled")))
        }
        return { result in
            guard !completed else { return }; completed = true
            completion(result)
            token.cancel()
        }
    }
    public func close() {
        guard !closed else { return }; closed = true
        let pending = Array(operations.values)
        for operation in pending { operation.cancel() }
        operations.removeAll()
    }
}

@MainActor public final class DulcetDiscoverCancellation: DulcetLibraryReaderCancellable {
    private var action: (@MainActor () -> Void)?
    public init(_ action: @escaping @MainActor () -> Void) { self.action = action }
    public func cancel() { let action = action; self.action = nil; action?() }
}

@MainActor public protocol DulcetDiscoverProviding: AnyObject {
    /// One lazily constructed client per account reader. Construction sends no requests.
    func openDiscover() -> any DulcetDiscoverReading
}

public extension DulcetLibrarySession {
    var discover: (any DulcetDiscoverReading)? {
#if os(tvOS)
        nil
#else
        (reader as? any DulcetDiscoverProviding)?.openDiscover()
#endif
    }
}

/// Optional metadata already delivered by Discover; used to resolve an exact scoped Library song
/// through the existing catalog reader. No external id is interpreted as a Library id.
@MainActor public protocol DulcetDiscoverLibraryMetadataProviding: AnyObject {
    func title(for reference: DulcetDiscoverLibraryRef) -> String?
    func item(for target: DulcetDiscoverTarget) -> DulcetDiscoverItem?
}


extension DulcetDiscoverWish {
    /// Wishlist already carries the correction's target and display snapshot, even on a cold
    /// launch with Discover disabled. No metadata fetch is needed to reject its library match.
    var matchCorrectionItem: DulcetDiscoverItem? {
        guard let target else { return nil }
        return .init(id: id, kind: kind, target: target, title: snapshot.title,
                     artist: snapshot.artist, year: snapshot.year, artworkId: snapshot.artworkId,
                     availability: availability)
    }
}


/// Exact local metadata over the owning reader. A library reference is scoped to its account;
/// a title or a Discover identifier is never used to choose a playable song.
@MainActor public protocol DulcetLibrarySongLookingUp: AnyObject {
    func lookupLibrarySong(_ reference: DulcetDiscoverLibraryRef,
                           completion: @escaping @MainActor (DulcetReaderItem?) -> Void) -> any DulcetLibraryReaderCancellable
}
