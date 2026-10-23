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
    @Test func discoverOffKeepsWishlistAndRemove() {
        let access = DulcetDiscoverFeatureAccess(detection: "known", discoverAvailable: false, wishlistAvailable: true, canReadWishlist: true, canRemoveWish: true)
        #expect(DulcetWishlistPresentation(access: access).canRead)
        #expect(DulcetWishlistPresentation(access: access).canRemove)
        #expect(!DulcetWishlistPresentation(access: access).canAdd)
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
    var albumCompletions: [@MainActor (Result<DulcetDiscoverAlbum, DulcetDiscoverFailure>) -> Void] = []
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
    func add(item: DulcetDiscoverItem, source: DulcetWishSource, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { added.append((item, source)); addCompletion = completion; return token() }
    func remove(rowKey: String, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { removed.append(rowKey); return token() }
    func reject(item: DulcetDiscoverItem, libraryItem: DulcetDiscoverLibraryRef, wishId: String?, completion: @escaping @MainActor (Result<DulcetWishEnqueued, DulcetDiscoverFailure>) -> Void) -> any DulcetLibraryReaderCancellable { token() }
    func refreshWishlist(_ filter: DulcetWishFilter) { filters.append(filter) }
    func close() {}
}
