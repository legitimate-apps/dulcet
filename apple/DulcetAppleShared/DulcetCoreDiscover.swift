import DulcetCore
import DulcetKit
import Foundation

extension DulcetDiscoverTarget {
    init(copy v: AppleDiscoverTarget) {
        self.init(
            kind: v.kind,
            mbid: v.mbid
        )
    }
}

extension DulcetDiscoverLibraryRef {
    init(copy v: AppleDiscoverLibraryRef) {
        self.init(
            providerInstanceId: v.providerInstanceId,
            type: v.type,
            id: v.id
        )
    }
}

extension DulcetDiscoverLink {
    init(copy v: AppleDiscoverLink) {
        self.init(
            relation: v.relation,
            url: v.url
        )
    }
}

extension DulcetDiscoverAge {
    init(copy v: AppleDiscoverAge) {
        self.init(
            fetchedAt: v.fetchedAtMillis.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) },
            sourceAgeSeconds: v.sourceAgeSeconds?.int64Value,
            receivedAt: Date(timeIntervalSince1970: Double(v.receivedAtMillis) / 1000),
            stale: v.stale,
            fromDeviceCache: v.fromDeviceCache
        )
    }
}

extension DulcetDiscoverAvailability {
    init(copy v: AppleDiscoverAvailability) {
        self.init(
            status: v.status,
            completeness: v.completeness,
            matchedBy: v.matchedBy,
            matchedTrackCount: v.matchedTrackCount?.intValue,
            totalTrackCount: v.totalTrackCount?.intValue,
            editionReleaseId: v.editionReleaseId,
            libraryItems: (0..<v.libraryItemCount).compactMap { v.libraryItemAt(index: $0) }.map { DulcetDiscoverLibraryRef(copy: $0) }
        )
    }
}

extension DulcetDiscoverItem {
    init(copy v: AppleDiscoverItem) {
        self.init(
            id: v.id,
            kind: v.kind,
            target: v.target.map { DulcetDiscoverTarget(copy: $0) },
            title: v.title,
            artist: v.artist,
            year: v.year?.intValue,
            releaseDate: v.releaseDate,
            artworkId: v.artworkId,
            disc: v.disc?.intValue,
            position: v.position?.intValue,
            listenCount: v.listenCount?.int64Value,
            primaryType: v.primaryType,
            links: (0..<v.linkCount).compactMap { v.linkAt(index: $0) }.map { DulcetDiscoverLink(copy: $0) },
            wishable: v.wishable,
            availability: DulcetDiscoverAvailability(copy: v.availability)
        )
    }
}

extension DulcetDiscoverPage {
    init(copy v: AppleDiscoverPage) {
        self.init(
            items: (0..<v.itemCount).compactMap { v.itemAt(index: $0) }.map { DulcetDiscoverItem(copy: $0) },
            nextCursor: v.nextCursor,
            age: DulcetDiscoverAge(copy: v.age)
        )
    }
}

extension DulcetDiscoverEdition {
    init(copy v: AppleDiscoverEdition) {
        self.init(
            releaseId: v.releaseId,
            releaseDate: v.releaseDate,
            trackCount: v.trackCount?.intValue,
            links: (0..<v.linkCount).compactMap { v.linkAt(index: $0) }.map { DulcetDiscoverLink(copy: $0) }
        )
    }
}

extension DulcetDiscoverArtist {
    init(copy v: AppleDiscoverArtist) {
        self.init(
            item: DulcetDiscoverItem(copy: v.item),
            albums: DulcetDiscoverPage(copy: v.albums)
        )
    }
}

extension DulcetDiscoverAlbum {
    init(copy v: AppleDiscoverAlbum) {
        self.init(
            item: DulcetDiscoverItem(copy: v.item),
            editions: (0..<v.editionCount).compactMap { v.editionAt(index: $0) }.map { DulcetDiscoverEdition(copy: $0) },
            selectedReleaseId: v.selectedReleaseId,
            tracks: DulcetDiscoverPage(copy: v.tracks)
        )
    }
}

extension DulcetDiscoverFailure {
    init(copy v: AppleDiscoverFailure) {
        self.init(
            code: v.code,
            retryAt: v.retryAtMillis.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) },
            retryable: v.retryable
        )
    }
}

extension DulcetWishSnapshot {
    init(copy v: AppleWishSnapshot) {
        self.init(
            title: v.title,
            artist: v.artist,
            year: v.year?.intValue,
            artworkId: v.artworkId
        )
    }
}

extension DulcetWishSource {
    init(copy v: AppleWishSource) {
        self.init(
            type: v.type,
            id: v.id
        )
    }
}

extension DulcetDiscoverWish {
    init(copy v: AppleDiscoverWish) {
        self.init(
            id: v.id,
            userId: v.userId,
            target: v.target.map { DulcetDiscoverTarget(copy: $0) },
            kind: v.kind,
            snapshot: DulcetWishSnapshot(copy: v.snapshot),
            source: DulcetWishSource(copy: v.source),
            addedAt: Date(timeIntervalSince1970: Double(v.addedAtMillis) / 1000),
            updatedAt: Date(timeIntervalSince1970: Double(v.updatedAtMillis) / 1000),
            state: v.state,
            note: v.note,
            noteAt: v.noteAtMillis.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) },
            fulfilledAt: v.fulfilledAtMillis.map { Date(timeIntervalSince1970: Double($0.int64Value) / 1000) },
            libraryItem: v.libraryItem.map { DulcetDiscoverLibraryRef(copy: $0) },
            availability: DulcetDiscoverAvailability(copy: v.availability),
            revision: v.revision
        )
    }
}

extension DulcetWishFilter {
    init(copy v: AppleWishFilter) {
        self.init(
            kind: v.kind,
            state: v.state,
            includeFulfilledHistory: v.includeFulfilledHistory
        )
    }
}

extension DulcetDiscoverFeatureAccess {
    init(copy v: AppleDiscoverFeatureAccess) {
        self.init(
            detection: v.detection,
            error: v.error.map { DulcetDiscoverFailure(copy: $0) },
            discoverAvailable: v.discoverAvailable,
            wishlistAvailable: v.wishlistAvailable,
            canSearch: v.canSearch,
            canReadWishlist: v.canReadWishlist,
            canAddWish: v.canAddWish,
            canRemoveWish: v.canRemoveWish,
            canRejectMatch: v.canRejectMatch
        )
    }
}

extension DulcetDiscoverGroup {
    init(copy v: AppleDiscoverGroup) {
        self.init(
            kind: v.kind,
            page: v.page.map { DulcetDiscoverPage(copy: $0) },
            loading: v.loading,
            error: v.error.map { DulcetDiscoverFailure(copy: $0) }
        )
    }
}

extension DulcetPendingWishAdd {
    init(copy v: ApplePendingWishAdd) {
        self.init(
            operationId: v.operationId,
            actionTime: Date(timeIntervalSince1970: Double(v.actionTimeMillis) / 1000),
            target: DulcetDiscoverTarget(copy: v.target),
            snapshot: DulcetWishSnapshot(copy: v.snapshot),
            source: DulcetWishSource(copy: v.source)
        )
    }
}

extension DulcetWishRow {
    init(copy v: AppleWishRow) {
        self.init(
            key: v.key,
            wish: v.wish.map { DulcetDiscoverWish(copy: $0) },
            pendingAdd: v.pendingAdd.map { DulcetPendingWishAdd(copy: $0) },
            pending: v.pending,
            error: v.error.map { DulcetDiscoverFailure(copy: $0) }
        )
    }
}

extension DulcetDiscoverPublication {
    init(copy v: AppleDiscoverPublication) {
        self.init(
            providerInstanceId: v.providerInstanceId,
            generation: v.generation,
            access: DulcetDiscoverFeatureAccess(copy: v.access),
            connection: v.connection,
            query: v.query,
            groups: (0..<v.groupCount).compactMap { v.groupAt(index: $0) }.map { DulcetDiscoverGroup(copy: $0) },
            wishes: (0..<v.wishCount).compactMap { v.wishAt(index: $0) }.map { DulcetWishRow(copy: $0) },
            wishlistLoading: v.wishlistLoading,
            wishlistError: v.wishlistError.map { DulcetDiscoverFailure(copy: $0) },
            wishlistSettled: v.wishlistSettled,
            pendingChangeCount: v.pendingChangeCount?.int64Value
        )
    }
}

extension DulcetWishEnqueued {
    init(copy v: AppleWishEnqueued) {
        self.init(
            operationId: v.operationId,
            rowKey: v.rowKey
        )
    }
}

extension DulcetCoreLibraryReader: DulcetDiscoverProviding {
    func openDiscover() -> any DulcetDiscoverReading {
        if let discoverAdapter { return discoverAdapter }
        let adapter = DulcetCoreDiscover(reader: client)
        discoverAdapter = adapter
        return adapter
    }
}

@MainActor final class DulcetCoreDiscover: DulcetDiscoverReading, DulcetDiscoverLibraryMetadataProviding {
    private let client: AppleDiscoverClient
    private let lifetime = DulcetDiscoverLifetime()
    private var closed = false
    // Core item constructors are private. Only the bridge retains core command arguments;
    // equality with the copied value prevents substituting an unrelated or modified payload.
    private var items: [DulcetDiscoverItem: AppleDiscoverItem] = [:]
    func title(for reference: DulcetDiscoverLibraryRef) -> String? {
        items.keys.first { $0.availability.libraryItems.contains(reference) }?.title
    }
    init(reader: AppleLibraryReaderClient) { client = AppleDiscoverClient(reader: reader) }
    private func remember(_ item: AppleDiscoverItem) { items[DulcetDiscoverItem(copy: item)] = item }
    private func remember(_ page: AppleDiscoverPage) {
        for index in 0..<page.itemCount { if let item = page.itemAt(index: index) { remember(item) } }
    }
    func observe(_ listener: @escaping @MainActor (DulcetDiscoverPublication) -> Void) -> any DulcetLibraryReaderCancellable {
        let bridge = DulcetCoreDiscoverListener { [weak self] delivery in
            let value = delivery.value
            guard let self, !self.closed else { return }
            for index in 0..<value.groupCount { if let page = value.groupAt(index: index)?.page { self.remember(page) } }
            listener(DulcetDiscoverPublication(copy: value))
        }
        let subscription = client.observe(listener: bridge)
        return lifetime.operation { [bridge] in _ = bridge; subscription.close() }
    }
    func setQuery(_ query: String) { guard !closed else { return }; client.setQuery(query: query) }
    func loadMore(kind: String) { guard !closed else { return }; client.loadMore(kind: kind) }
    func refreshWishlist(_ filter: DulcetWishFilter) {
        guard !closed else { return }
        client.refreshWishlist(filter: AppleWishFilter(kind: filter.kind, state: filter.state, includeFulfilledHistory: filter.includeFulfilledHistory))
    }
    private func result<T>(_ value: T?, _ error: AppleDiscoverFailure?) -> Result<T, DulcetDiscoverFailure> {
        if let value { return .success(value) }
        return .failure(error.map(DulcetDiscoverFailure.init(copy:)) ?? .init(code: "invalid-response"))
    }
    func artist(mbid: String, completion: @escaping @MainActor (Result<DulcetDiscoverArtist, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        let deliver = lifetime.delivery(completion)
        guard !closed else { return DulcetDiscoverCancellation {} }
        let operation = client.artist(mbid: mbid) { [weak self] response in
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                if let value = response.value { self.remember(value.item); self.remember(value.albums) }
                deliver(self.result(response.value.map { DulcetDiscoverArtist(copy: $0) }, response.error))
            }
        }
        return lifetime.operation { operation.cancel(); deliver(.failure(.init(code: "cancelled"))) }
    }
    func artistAlbums(mbid: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        let deliver = lifetime.delivery(completion)
        guard !closed else { return DulcetDiscoverCancellation {} }
        let operation = client.artistAlbums(mbid: mbid, cursor: cursor) { [weak self] response in
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                if let value = response.value { self.remember(value) }
                deliver(self.result(response.value.map { DulcetDiscoverPage(copy: $0) }, response.error))
            }
        }
        return lifetime.operation { operation.cancel(); deliver(.failure(.init(code: "cancelled"))) }
    }
    func album(mbid: String, releaseId: String?, completion: @escaping @MainActor (Result<DulcetDiscoverAlbum, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        let deliver = lifetime.delivery(completion)
        guard !closed else { return DulcetDiscoverCancellation {} }
        let operation = client.album(mbid: mbid, releaseId: releaseId) { [weak self] response in
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                if let value = response.value { self.remember(value.item); self.remember(value.tracks) }
                deliver(self.result(response.value.map { DulcetDiscoverAlbum(copy: $0) }, response.error))
            }
        }
        return lifetime.operation { operation.cancel(); deliver(.failure(.init(code: "cancelled"))) }
    }
    func albumTracks(mbid: String, releaseId: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        let deliver = lifetime.delivery(completion)
        guard !closed else { return DulcetDiscoverCancellation {} }
        let operation = client.albumTracks(mbid: mbid, releaseId: releaseId, cursor: cursor) { [weak self] response in
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                if let value = response.value { self.remember(value) }
                deliver(self.result(response.value.map { DulcetDiscoverPage(copy: $0) }, response.error))
            }
        }
        return lifetime.operation { operation.cancel(); deliver(.failure(.init(code: "cancelled"))) }
    }
    func artwork(artworkId: String, completion: @escaping @MainActor (Result<Data, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        let deliver = lifetime.delivery(completion)
        guard !closed else { return DulcetDiscoverCancellation {} }
        let operation = client.artwork(artworkId: artworkId) { [weak self] response in
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                deliver(self.result(response.bytes.map { $0 as Data }, response.error))
            }
        }
        return lifetime.operation { operation.cancel(); deliver(.failure(.init(code: "cancelled"))) }
    }
    func add(item: DulcetDiscoverItem, source: DulcetWishSource, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        let deliver = lifetime.delivery(completion)
        guard !closed else { return DulcetDiscoverCancellation {} }
        guard let coreItem = items[item] else { deliver(.failure(.init(code: "invalid-response"))); return DulcetDiscoverCancellation {} }
        let operation = client.add(item: coreItem, source: AppleWishSource(type: source.type, id: source.id)) { [weak self] response in
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                deliver(self.result(response.value.map { DulcetWishEnqueued(copy: $0) }, response.error))
            }
        }
        return lifetime.operation { operation.cancel(); deliver(.failure(.init(code: "cancelled"))) }
    }
    func remove(rowKey: String, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        let deliver = lifetime.delivery(completion)
        guard !closed else { return DulcetDiscoverCancellation {} }
        let operation = client.remove(rowKey: rowKey) { [weak self] response in
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                deliver(self.result(response.value.map { DulcetWishEnqueued(copy: $0) }, response.error))
            }
        }
        return lifetime.operation { operation.cancel(); deliver(.failure(.init(code: "cancelled"))) }
    }
    func reject(item: DulcetDiscoverItem, libraryItem: DulcetDiscoverLibraryRef, wishId: String?, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        let deliver = lifetime.delivery(completion)
        guard !closed else { return DulcetDiscoverCancellation {} }
        guard let coreItem = items[item] else { deliver(.failure(.init(code: "invalid-response"))); return DulcetDiscoverCancellation {} }
        let operation = client.reject(item: coreItem, libraryItem: AppleDiscoverLibraryRef(providerInstanceId: libraryItem.providerInstanceId, type: libraryItem.type, id: libraryItem.id), wishId: wishId) { [weak self] response in
            MainActor.assumeIsolated {
                guard let self, !self.closed else { return }
                deliver(self.result(response.value.map { DulcetWishEnqueued(copy: $0) }, response.error))
            }
        }
        return lifetime.operation { operation.cancel(); deliver(.failure(.init(code: "cancelled"))) }
    }
    func close() {
        guard !closed else { return }; closed = true
        client.close()
        lifetime.close()
        items.removeAll()
    }
}

private final class DulcetCoreDiscoverListener: NSObject, AppleDiscoverListener, @unchecked Sendable {
    private let handler: @MainActor (DulcetCoreDiscoverDelivery) -> Void
    init(_ handler: @escaping @MainActor (DulcetCoreDiscoverDelivery) -> Void) { self.handler = handler }
    func onPublication(publication: AppleDiscoverPublication) {
        let delivery = DulcetCoreDiscoverDelivery(value: publication)
        MainActor.assumeIsolated { handler(delivery) }
    }
}

private struct DulcetCoreDiscoverDelivery: @unchecked Sendable { let value: AppleDiscoverPublication }
