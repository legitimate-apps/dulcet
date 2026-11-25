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
    case item(DulcetDiscoverItem, source: DulcetWishSource)
    public init(item: DulcetDiscoverItem, source: DulcetWishSource? = nil) {
        guard ["artist", "album", "song"].contains(item.kind), item.target?.kind == item.kind else {
            self = .item(item, source: source ?? .init(type: "search", id: item.id)); return
        }
        switch item.target?.kind {
        case "artist": self = .artist(mbid: item.target!.mbid)
        case "album": self = .album(mbid: item.target!.mbid, releaseId: nil)
        default: self = .item(item, source: source ?? .init(type: "search", id: item.id))
        }
    }
}

public struct DulcetDiscoverItemPresentation: Sendable {
    public let item: DulcetDiscoverItem
    public init(item: DulcetDiscoverItem) { self.item = item }
    public var canWish: Bool { item.wishable && item.target?.kind == item.kind && ["artist", "album", "song"].contains(item.kind) }
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
        guard item.target?.kind == item.kind, ["artist", "album", "song"].contains(item.kind),
              ["held", "partlyHeld"].contains(item.availability.status) else { return [] }
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
        // An answer still on screen already says the refresh failed, with its age (`ageText`).
        if age != nil { return nil }
        return connection == "offline" || error.code == "offline" ? DulcetStrings.discoverOffline : DulcetStrings.discoverReadFailed
    }
    /// Whether the answer on screen is older than it should be: the server served a stale copy
    /// because its source was unreachable, or this device's refresh failed.
    public var refreshFailed: Bool {
        guard let age else { return false }
        if age.stale { return true }
        guard let error else { return false }
        return error.code != "cancelled"
    }
    /// How old the answer is, or nil when that is unknown. Server age and device elapsed age stay
    /// distinct, and an unknown source age is never zero.
    public var ageSeconds: Int64? {
        guard let age else { return nil }
        let elapsed = max(0, Int(now.timeIntervalSince(age.receivedAt)))
        return age.sourceAgeSeconds.map { max(0, $0) + Int64(elapsed) }
            ?? age.fetchedAt.map { Int64(max(0, now.timeIntervalSince($0))) }
    }
    /// The one visible line about an answer on screen (§28, 2026-11-24). A current answer, and
    /// one this device kept from earlier while it refreshes, say nothing: that is how a music app
    /// behaves, and the age stays available to accessibility (`accessibleAge`). Offline, or a
    /// refresh that failed, say so in plain words with how old the answer is.
    public var ageText: String? {
        guard age != nil else { return connection == "offline" ? DulcetStrings.discoverOffline : nil }
        let offline = connection == "offline" || error?.code == "offline"
        guard offline || refreshFailed else { return nil }
        let since = ageSeconds.flatMap { $0 >= 60 ? Self.relative($0, now: now) : nil }
        if offline { return since.map(DulcetStrings.discoverOfflineShowing) ?? DulcetStrings.discoverOfflineShowingEarlier }
        return since.map(DulcetStrings.discoverRefreshFailedShowing) ?? DulcetStrings.discoverRefreshFailedShowingEarlier
    }
    /// The answer's age for accessibility, whether or not a line is drawn.
    public var accessibleAge: String? {
        guard age != nil else { return nil }
        guard let seconds = ageSeconds else { return DulcetStrings.discoverAgeUnavailable }
        return DulcetStrings.discoverUpdated(seconds < 60 ? DulcetStrings.readerJustNow : Self.relative(seconds, now: now))
    }
    private static func relative(_ seconds: Int64, now: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: now.addingTimeInterval(-TimeInterval(seconds)), relativeTo: now)
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
        // Accepted mutations belong to the account reader, including enqueues still waiting
        // for its queue. Leaving the view stops observation; account retirement cancels work.
        operations = []
    }
    func row(for item: DulcetDiscoverItem) -> DulcetWishRow? {
        guard let target = item.target else { return nil }
        return ((publication?.wishes ?? []) + (publication?.home?.recentWishes ?? [])).first { ($0.wish?.target ?? $0.pendingAdd?.target) == target }
    }
    func add(_ item: DulcetDiscoverItem, source: DulcetWishSource) {
        mutationError = nil
        operations.append(reader.add(item: item, source: source) { [weak self] result in
            guard let self, self.active else { return }
            if case let .failure(error) = result, error.code != "cancelled" { self.mutationError = error }
        })
    }
    func remove(_ key: String) {
        DulcetUIProofMarkers.detail("wishlist.model.remove", context: "key=\(key) active=\(active)")
        mutationError = nil
        operations.append(reader.remove(rowKey: key) { [weak self] result in
            DulcetUIProofMarkers.detail("wishlist.remove.result", context: "result=\(result)")
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
        let generation = selection.begin()
        DulcetUIProofMarkers.detail("loadArtist", context: "model=\(ObjectIdentifier(self)) reader=\(ObjectIdentifier(reader)) mbid=\(mbid) generation=\(generation)")
        operation?.cancel(); loading = true; error = nil
        operation = reader.artist(mbid: mbid) { [weak self] result in
            guard let self else { return }
            let accepted = self.selection.accepts(generation)
            let outcome = switch result { case .success: "success"; case let .failure(error): error.code }
            DulcetUIProofMarkers.detail("artistCompletion", context: "model=\(ObjectIdentifier(self)) generation=\(generation) accepted=\(accepted) result=\(outcome)")
            guard accepted else { return }
            self.loading = false
            switch result { case let .success(value): self.artist = value; case let .failure(error): self.error = error }
        }
    }
    func loadAlbum(_ mbid: String, releaseId: String?) {
        let generation = selection.begin()
        DulcetUIProofMarkers.detail("loadAlbum", context: "model=\(ObjectIdentifier(self)) reader=\(ObjectIdentifier(reader)) mbid=\(mbid) release=\(releaseId ?? "nil") generation=\(generation)")
        operation?.cancel(); requestedReleaseId = releaseId; loading = true; error = nil
        operation = reader.album(mbid: mbid, releaseId: releaseId) { [weak self] result in
            guard let self else { return }
            let accepted = self.selection.accepts(generation)
            let outcome = switch result { case .success: "success"; case let .failure(error): error.code }
            DulcetUIProofMarkers.detail("albumCompletion", context: "model=\(ObjectIdentifier(self)) generation=\(generation) accepted=\(accepted) result=\(outcome)")
            guard accepted else { return }
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
    func close() {
        DulcetUIProofMarkers.detail("close", context: "model=\(ObjectIdentifier(self)) reader=\(ObjectIdentifier(reader)) loading=\(loading) albumPresent=\(album != nil)")
        selection.close(); operation?.cancel(); operation = nil; loading = false
    }
}
