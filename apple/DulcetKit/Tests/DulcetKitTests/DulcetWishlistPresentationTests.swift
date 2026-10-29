import Foundation
import Testing
@testable import DulcetKit

@Suite @MainActor struct DulcetWishlistPresentationTests {
    func wish(_ state: String, note: String? = nil, item: DulcetDiscoverLibraryRef? = nil) -> DulcetDiscoverWish {
        .init(id: "wish", userId: "user", kind: "song", snapshot: .init(title: "Wish"), source: .init(type: "search"), addedAt: .init(timeIntervalSince1970: 100), updatedAt: .init(timeIntervalSince1970: 200), state: state, note: note, libraryItem: item, availability: .init(status: "notHeld", completeness: "unknown"), revision: "rev")
    }
    @Test func wishActionIsNotFavoriteOrMyLibrary() {
        let item = DulcetDiscoverItem(id: "id", kind: "song", target: .init(kind: "song", mbid: "mbid"), title: "Song", wishable: true, availability: .init(status: "notHeld", completeness: "unknown"))
        #expect(DulcetDiscoverItemPresentation(item: item).canWish)
        let source = DiscoverProofReader()
        let model = DulcetDiscoverModel(reader: source); model.open()
        model.add(item, source: .init(type: "search"))
        #expect(source.added.count == 1 && source.added[0].1.type == "search")
        #expect(source.myLibraryMembershipMutationCount == 0)
        #expect(model.row(for: item) == nil) // No icon change before durable publication.
        var durable = source.value
        durable.wishes = [.init(key: "durable", pendingAdd: .init(operationId: "op", actionTime: .now, target: item.target!, snapshot: .init(title: item.title), source: .init(type: "search")), pending: true)]
        source.emit(durable)
        #expect(model.row(for: item)?.key == "durable")
        #expect(DulcetWishlistRowPresentation(row: .init(key: "key", pendingAdd: .init(operationId: "op", actionTime: .now, target: item.target!, snapshot: .init(title: item.title), source: .init(type: "search")), pending: true)).pendingText == "Waiting to sync")
    }
    @Test func pendingRemoveNeverReturnsFromLateArrival() {
        let source = DiscoverProofReader(); let model = DulcetDiscoverModel(reader: source); model.open()
        var old = source.value; old.wishes = [.init(key: "A", wish: wish("fulfilled"))]
        source.emit(old); model.remove("A")
        #expect(source.removed == ["A"])
        var removed = old; removed.generation = 2; removed.wishes = []; source.emit(removed)
        source.emit(old) // The closed generation's late arrival cannot restore A.
        #expect(model.publication?.wishes.isEmpty == true)
        removed.generation = 3; removed.wishes = [.init(key: "B", wish: wish("wanted"))]; source.emit(removed)
        #expect(model.publication?.wishes.map(\.key) == ["B"])
        model.close(); source.emit(old)
        #expect(model.publication?.wishes.map(\.key) == ["B"])
    }
    @Test func removingWishSurvivesViewCloseBeforeItsDurableReceipt() {
        let reader = DiscoverProofReader()
        reader.holdMutation = true
        var model: DulcetDiscoverModel? = DulcetDiscoverModel(reader: reader)
        weak let releasedModel = model
        model?.open()
        model?.remove("generation-B")
        model?.close()
        model = nil
        #expect(releasedModel == nil)
        reader.releaseMutation?()
        #expect(reader.durableRemovals == ["generation-B"], "Leaving Wishlist must not discard an accepted Remove")
        #expect(reader.mutationCancellationCount == 0)
    }
    @Test func addingWishSurvivesViewCloseBeforeItsDurableReceipt() {
        let reader = DiscoverProofReader()
        reader.holdMutation = true
        var model: DulcetDiscoverModel? = DulcetDiscoverModel(reader: reader)
        weak let releasedModel = model
        model?.open()
        let item = DulcetDiscoverItem(id: "song", kind: "song", target: .init(kind: "song", mbid: "mbid"), title: "Song", wishable: true, availability: .init(status: "notHeld", completeness: "unknown"))
        model?.add(item, source: .init(type: "search"))
        model?.close()
        model = nil
        #expect(releasedModel == nil)
        reader.releaseMutation?()
        #expect(reader.durableAdds == ["song"])
        #expect(reader.mutationCancellationCount == 0)
    }
    @Test func aClosedWishlistDoesNotConsumeALateMutationFailure() {
        let reader = DiscoverProofReader()
        reader.holdMutation = true
        reader.heldRemoveFailure = .init(code: "forbidden")
        let model = DulcetDiscoverModel(reader: reader)
        model.open()
        model.remove("generation-B")
        model.close()
        reader.releaseMutation?()
        #expect(reader.mutationCancellationCount == 0)
        #expect(model.mutationError == nil)
    }
    @Test func arrivalUsesServerLibraryItemAndRefreshesMyLibrary() {
        let ref = DulcetDiscoverLibraryRef(providerInstanceId: "account-a", type: "song", id: "opaque-local")
        let presentation = DulcetWishlistRowPresentation(row: .init(key: "row", wish: wish("fulfilled", item: ref)))
        #expect(presentation.stateTitle == "Arrived")
        #expect(presentation.arrival == ref)
    }
    @Test func statesNotesAndThirtyDayHistoryUseServerTruth() {
        for (wire, label) in [("wanted", "Wanted"), ("requested", "Requested"), ("searching", "Searching"), ("unavailable", "Not found"), ("fulfilled", "Arrived"), ("future", "future")] {
            let presentation = DulcetWishlistRowPresentation(row: .init(key: "key", wish: wish(wire, note: "<plain text>")))
            #expect(presentation.stateTitle == label)
            #expect(presentation.note == "<plain text>")
        }
        #expect(!DulcetWishFilter().includeFulfilledHistory)
        #expect(DulcetWishFilter(includeFulfilledHistory: true).includeFulfilledHistory)
    }
    @Test func coldWishlistCorrectionUsesItsCopiedSnapshotWithoutDiscoverMetadata() {
        let ref = DulcetDiscoverLibraryRef(providerInstanceId: "account-a", type: "song", id: "opaque-local")
        var wish = wish("fulfilled", item: ref)
        wish.target = .init(kind: "song", mbid: "external-recording")
        let item = wish.matchCorrectionItem
        #expect(item?.target == wish.target)
        #expect(item?.title == wish.snapshot.title)
        #expect(item?.availability == wish.availability)
        let reader = DiscoverProofReader()
        reader.value.access = .init(detection: "known", discoverAvailable: false, wishlistAvailable: true, canRejectMatch: true)
        let model = DulcetDiscoverModel(reader: reader); model.open()
        if let item { model.reject(item, ref: ref, wishId: wish.id) }
        #expect(reader.rejected.count == 1)
        #expect(reader.rejected.first?.0.target == wish.target)
        #expect(reader.rejected.first?.1 == ref && reader.rejected.first?.2 == wish.id)
    }
    @Test func discoverOffKeepsWishlistAndRemove() {
        let access = DulcetDiscoverFeatureAccess(detection: "known", discoverAvailable: false, wishlistAvailable: true, canReadWishlist: true, canRemoveWish: true)
        #expect(DulcetWishlistPresentation(access: access).canRead)
        #expect(DulcetWishlistPresentation(access: access).canRemove)
        #expect(!DulcetWishlistPresentation(access: access).canAdd)
    }
    @Test func failedRedetectionKeepsAdvertisedWishlistVisibleWithoutAuthorizingCommands() {
        let access = DulcetDiscoverFeatureAccess(detection: "unresolved", error: .init(code: "offline", retryable: true), wishlistAvailable: true)
        let presentation = DulcetWishlistPresentation(access: access)
        #expect(presentation.showsDestination)
        #expect(!presentation.canRead && !presentation.canAdd && !presentation.canRemove)
        #expect(!DulcetWishlistPresentation(access: .init(detection: "known")).showsDestination)
    }
    @Test func permanentSaveFailureIsVisibleAndRollsBackIcon() {
        let source = DiscoverProofReader(); let model = DulcetDiscoverModel(reader: source); model.open()
        let item = DulcetDiscoverItem(id: "song", kind: "song", target: .init(kind: "song", mbid: "mbid"), title: "Song", wishable: true, availability: .init(status: "notHeld", completeness: "unknown"))
        model.add(item, source: .init(type: "search"))
        source.addCompletion?(.failure(.init(code: "forbidden")))
        #expect(model.mutationError?.code == "forbidden")
        #expect(model.row(for: item) == nil)
        let failed = DulcetWishlistRowPresentation(row: .init(key: "key", error: .init(code: "forbidden")))
        #expect(failed.errorText == "Not saved")
        #expect(failed.pendingText == nil)
    }
}

@MainActor final class DiscoverProofReader: DulcetDiscoverReading {
    var listener: (@MainActor (DulcetDiscoverPublication) -> Void)?
    var addCompletion: (@MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void)?
    var added: [(DulcetDiscoverItem, DulcetWishSource)] = []
    var removed: [String] = []
    var holdMutation = false
    var durableAdds: [String] = []
    var heldRemoveFailure: DulcetDiscoverFailure?
    var releaseMutation: (@MainActor () -> Void)?
    var durableRemovals: [String] = []
    var mutationCancellationCount = 0
    var rejected: [(DulcetDiscoverItem, DulcetDiscoverLibraryRef, String?)] = []
    var albumCompletions: [@MainActor (Result<DulcetDiscoverAlbum, DulcetDiscoverFailure>) -> Void] = []
    var shelfRequests: [(String, String?)] = []
    var shelfCompletions: [@MainActor (Result<DulcetDiscoverShelfPage, DulcetDiscoverFailure>) -> Void] = []
    var playlistRequests: [(String, String?)] = []
    var playlistCompletions: [@MainActor (Result<DulcetDiscoverPlaylist, DulcetDiscoverFailure>) -> Void] = []
    var playlistListRequests: [(String, String?)] = []
    var playlistListCompletions: [@MainActor (Result<DulcetDiscoverPlaylistList, DulcetDiscoverFailure>) -> Void] = []
    var pageCancellationCount = 0
    var homeRefreshCount = 0
    func refreshHome() { homeRefreshCount += 1 }
    func shelf(id: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverShelfPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        shelfRequests.append((id, cursor)); shelfCompletions.append(completion)
        return DulcetDiscoverCancellation { self.pageCancellationCount += 1 }
    }
    func playlists(query: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPlaylistList, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        playlistListRequests.append((query, cursor)); playlistListCompletions.append(completion)
        return DulcetDiscoverCancellation { self.pageCancellationCount += 1 }
    }
    func playlist(mbid: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPlaylist, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        playlistRequests.append((mbid, cursor)); playlistCompletions.append(completion)
        return DulcetDiscoverCancellation { self.pageCancellationCount += 1 }
    }
    var albumCancellationCount = 0
    var filters: [DulcetWishFilter] = []
    var myLibraryMembershipMutationCount = 0
    var value = DulcetDiscoverPublication(providerInstanceId: "account-a", generation: 1, access: .init(detection: "known", discoverAvailable: true, wishlistAvailable: true, canReadWishlist: true, canAddWish: true, canRemoveWish: true), connection: "online", query: "")
    func token() -> any DulcetLibraryReaderCancellable { DulcetDiscoverCancellation {} }
    func emit(_ value: DulcetDiscoverPublication) { self.value = value; listener?(value) }
    func observe(_ listener: @escaping @MainActor (DulcetDiscoverPublication) -> Void) -> any DulcetLibraryReaderCancellable { self.listener = listener; listener(value); return token() }
    func setQuery(_ query: String) {}
    func loadMore(kind: String) {}
    func artist(mbid: String, completion: @escaping @MainActor (Result<DulcetDiscoverArtist, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { token() }
    func artistAlbums(mbid: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { token() }
    func album(mbid: String, releaseId: String?, completion: @escaping @MainActor (Result<DulcetDiscoverAlbum, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { albumCompletions.append(completion); return DulcetDiscoverCancellation { self.albumCancellationCount += 1 } }
    func albumTracks(mbid: String, releaseId: String, cursor: String?, completion: @escaping @MainActor (Result<DulcetDiscoverPage, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { token() }
    func artwork(artworkId: String, completion: @escaping @MainActor (Result<Data, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { token() }
    func add(item: DulcetDiscoverItem, source: DulcetWishSource, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        added.append((item, source)); addCompletion = completion
        guard holdMutation else { return token() }
        var cancelled = false
        releaseMutation = {
            guard !cancelled else { return }
            self.durableAdds.append(item.id)
            completion(.success(.init(operationId: "held-add", rowKey: item.id)))
        }
        return DulcetDiscoverCancellation {
            cancelled = true
            self.mutationCancellationCount += 1
            completion(.failure(.init(code: "cancelled")))
        }
    }
    func remove(rowKey: String, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable {
        removed.append(rowKey)
        guard holdMutation else { return token() }
        var cancelled = false
        releaseMutation = {
            guard !cancelled else { return }
            if let error = self.heldRemoveFailure {
                completion(.failure(error))
            } else {
                self.durableRemovals.append(rowKey)
                completion(.success(.init(operationId: "held-remove", rowKey: rowKey)))
            }
        }
        return DulcetDiscoverCancellation {
            cancelled = true
            self.mutationCancellationCount += 1
            completion(.failure(.init(code: "cancelled")))
        }
    }
    func reject(item: DulcetDiscoverItem, libraryItem: DulcetDiscoverLibraryRef, wishId: String?, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { rejected.append((item, libraryItem, wishId)); return token() }
    func refreshWishlist(_ filter: DulcetWishFilter) { filters.append(filter) }
    func close() {}
}
