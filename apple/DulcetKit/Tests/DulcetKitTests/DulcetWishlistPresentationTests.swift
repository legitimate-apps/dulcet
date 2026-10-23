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
        #expect(DulcetWishlistRowPresentation(row: .init(key: "key", pendingAdd: .init(operationId: "op", actionTime: .now, target: item.target!, snapshot: .init(title: item.title), source: .init(type: "search")), pending: true)).pendingText == "Waiting to sync")
    }
    @Test func pendingRemoveNeverReturnsFromLateArrival() {
        let selection = DulcetDiscoverDetailSelection()
        let removedGeneration = selection.begin()
        _ = selection.begin()
        #expect(!selection.accepts(removedGeneration))
        let deliberateReadd = DulcetWishRow(key: "new-generation", wish: wish("wanted"))
        #expect(DulcetWishlistRowPresentation(row: deliberateReadd).stateTitle == "Wanted")
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
        let failed = DulcetWishlistRowPresentation(row: .init(key: "key", error: .init(code: "forbidden")))
        #expect(failed.errorText == "Not saved")
        #expect(failed.pendingText == nil)
    }
}
