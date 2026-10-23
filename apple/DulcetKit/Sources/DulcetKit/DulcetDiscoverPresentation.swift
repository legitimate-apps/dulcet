import Foundation
import Observation

public enum DulcetDiscoverSearchScope: String, CaseIterable, Sendable {
    case server, everywhere
    public func update(_ query: String, local: (String) -> Void, discover: (String) -> Void) {
        switch self { case .server: local(query); case .everywhere: discover(query) }
    }
}

public enum DulcetDiscoverDestination: Hashable, Sendable {
    case artist(mbid: String)
    case album(mbid: String, releaseId: String?)
    case item(DulcetDiscoverItem)
    public init(item: DulcetDiscoverItem) {
        switch item.target?.kind {
        case "artist": self = .artist(mbid: item.target!.mbid)
        case "album": self = .album(mbid: item.target!.mbid, releaseId: nil)
        default: self = .item(item)
        }
    }
}

public struct DulcetDiscoverItemPresentation: Sendable {
    public let item: DulcetDiscoverItem
    public init(item: DulcetDiscoverItem) { self.item = item }
    public var canWish: Bool { item.wishable && item.target != nil }
    public var availabilityText: String {
        let availability = item.availability
        if availability.status == "partlyHeld", let count = availability.matchedTrackCount, let total = availability.totalTrackCount {
            return DulcetStrings.discoverPartial(count, total)
        }
        if availability.status == "held" { return DulcetStrings.discoverHeld }
        if availability.status == "notHeld" { return DulcetStrings.discoverNotHeld }
        return DulcetStrings.discoverUnknown
    }
    public var libraryReferences: [DulcetDiscoverLibraryRef] {
        guard ["held", "partlyHeld"].contains(item.availability.status) else { return [] }
        return item.availability.libraryItems
    }
}

public struct DulcetDiscoverStatus: Sendable {
    public let age: DulcetDiscoverAge?
    public let error: DulcetDiscoverFailure?
    public let connection: String
    public let now: Date
    public init(age: DulcetDiscoverAge?, error: DulcetDiscoverFailure?, connection: String, now: Date = .now) {
        self.age = age; self.error = error; self.connection = connection; self.now = now
    }
    public var canRetry: Bool {
        guard connection != "offline", error?.retryable == true else { return false }
        return error?.retryAt.map { $0 <= now } ?? true
    }
    public var notice: String? {
        guard let error else { return nil }
        if error.code == "rate-limited" {
            let seconds = max(0, Int(ceil(error.retryAt?.timeIntervalSince(now) ?? 60)))
            return DulcetStrings.discoverBusy(seconds)
        }
        if error.code == "cancelled" { return nil }
        return connection == "offline" || error.code == "offline" ? DulcetStrings.discoverOffline : DulcetStrings.discoverReadFailed
    }
    public var ageText: String? {
        guard let age else { return connection == "offline" ? DulcetStrings.discoverOffline : nil }
        // Keep server age and device elapsed age distinct. Unknown source age is never zero.
        let elapsed = max(0, Int(now.timeIntervalSince(age.receivedAt)))
        let seconds: Int64? = age.sourceAgeSeconds.map { max(0, $0) + Int64(elapsed) }
            ?? age.fetchedAt.map { Int64(max(0, now.timeIntervalSince($0))) }
        guard age.stale || age.fromDeviceCache || connection == "offline" else { return nil }
        let prefix = connection == "offline" ? DulcetStrings.discoverOffline : age.stale ? DulcetStrings.discoverCachedAnswer : DulcetStrings.discoverCached
        return seconds.map { DulcetStrings.discoverAge(prefix, $0) } ?? DulcetStrings.discoverAgeUnknown(prefix)
    }
}

/// Per-screen request selection only. Query debounce, server backoff, caching and durable intents
/// remain in the account reader. A closed/replaced detail never navigates from a late callback.
@MainActor public final class DulcetDiscoverDetailSelection {
    private var generation = 0
    public init() {}
    public func begin() -> Int { generation += 1; return generation }
    public func accepts(_ value: Int) -> Bool { value == generation }
    public func close() { generation += 1 }
}

@MainActor @Observable final class DulcetDiscoverModel {
    let reader: any DulcetDiscoverReading
    var publication: DulcetDiscoverPublication?
    var mutationError: DulcetDiscoverFailure?
    @ObservationIgnored private var subscription: (any DulcetLibraryReaderCancellable)?
    @ObservationIgnored private var operations: [any DulcetLibraryReaderCancellable] = []
    @ObservationIgnored private var active = false
    init(reader: any DulcetDiscoverReading) { self.reader = reader }
    func open() {
        guard !active else { return }; active = true
        subscription = reader.observe { [weak self] publication in
            guard let self, self.active else { return }
            if let previous = self.publication {
                guard previous.providerInstanceId == publication.providerInstanceId,
                      previous.generation <= publication.generation else { return }
            }
            self.publication = publication
        }
    }
    func close() {
        active = false; subscription?.cancel(); subscription = nil
        let pending = operations; operations = []
        pending.forEach { $0.cancel() }
    }
    func row(for item: DulcetDiscoverItem) -> DulcetWishRow? {
        guard let target = item.target else { return nil }
        return publication?.wishes.first { ($0.wish?.target ?? $0.pendingAdd?.target) == target }
    }
    func add(_ item: DulcetDiscoverItem, source: DulcetWishSource) {
        mutationError = nil
        operations.append(reader.add(item: item, source: source) { [weak self] result in
            guard let self, self.active else { return }
            if case let .failure(error) = result, error.code != "cancelled" { self.mutationError = error }
        })
    }
    func remove(_ key: String) {
        mutationError = nil
        operations.append(reader.remove(rowKey: key) { [weak self] result in
            guard let self, self.active else { return }
            if case let .failure(error) = result, error.code != "cancelled" { self.mutationError = error }
        })
    }
    func reject(_ item: DulcetDiscoverItem, ref: DulcetDiscoverLibraryRef, wishId: String?) {
        mutationError = nil
        operations.append(reader.reject(item: item, libraryItem: ref, wishId: wishId) { [weak self] result in
            guard let self, self.active else { return }
            if case let .failure(error) = result, error.code != "cancelled" { self.mutationError = error }
        })
    }
}

@MainActor @Observable final class DulcetDiscoverDetailModel {
    let reader: any DulcetDiscoverReading
    var artist: DulcetDiscoverArtist?
    var album: DulcetDiscoverAlbum?
    var error: DulcetDiscoverFailure?
    var loading = false
    var requestedReleaseId: String?
    @ObservationIgnored private let selection = DulcetDiscoverDetailSelection()
    @ObservationIgnored private var operation: (any DulcetLibraryReaderCancellable)?
    init(reader: any DulcetDiscoverReading) { self.reader = reader }
    func load(_ destination: DulcetDiscoverDestination) {
        switch destination {
        case let .artist(mbid): loadArtist(mbid)
        case let .album(mbid, releaseId): loadAlbum(mbid, releaseId: releaseId)
        case .item: break
        }
    }
    func loadArtist(_ mbid: String) {
        let generation = selection.begin(); operation?.cancel(); loading = true; error = nil
        operation = reader.artist(mbid: mbid) { [weak self] result in
            guard let self, self.selection.accepts(generation) else { return }
            self.loading = false
            switch result { case let .success(value): self.artist = value; case let .failure(error): self.error = error }
        }
    }
    func loadAlbum(_ mbid: String, releaseId: String?) {
        let generation = selection.begin(); operation?.cancel(); requestedReleaseId = releaseId; loading = true; error = nil
        operation = reader.album(mbid: mbid, releaseId: releaseId) { [weak self] result in
            guard let self, self.selection.accepts(generation) else { return }
            self.loading = false
            switch result { case let .success(value): self.album = value; case let .failure(error): self.error = error }
        }
    }
    func loadMoreArtist(_ mbid: String) {
        guard let artist, let cursor = artist.albums.nextCursor, !loading else { return }
        let generation = selection.begin(); loading = true
        operation = reader.artistAlbums(mbid: mbid, cursor: cursor) { [weak self] result in
            guard let self, self.selection.accepts(generation) else { return }; self.loading = false
            switch result {
            case let .success(page):
                let old = self.artist?.albums.items ?? []
                let ids = Set(old.map(\.id))
                self.artist?.albums = .init(items: old + page.items.filter { !ids.contains($0.id) }, nextCursor: page.nextCursor, age: page.age)
            case let .failure(error): self.error = error
            }
        }
    }
    func loadMoreTracks(_ mbid: String) {
        guard let album, let releaseId = album.selectedReleaseId, let cursor = album.tracks.nextCursor, !loading else { return }
        let generation = selection.begin(); loading = true
        operation = reader.albumTracks(mbid: mbid, releaseId: releaseId, cursor: cursor) { [weak self] result in
            guard let self, self.selection.accepts(generation) else { return }; self.loading = false
            switch result {
            case let .success(page):
                let old = self.album?.tracks.items ?? []; let ids = Set(old.map(\.id))
                self.album?.tracks = .init(items: old + page.items.filter { !ids.contains($0.id) }, nextCursor: page.nextCursor, age: page.age)
            case let .failure(error): self.error = error
            }
        }
    }
    func close() { selection.close(); operation?.cancel(); operation = nil; loading = false }
}
