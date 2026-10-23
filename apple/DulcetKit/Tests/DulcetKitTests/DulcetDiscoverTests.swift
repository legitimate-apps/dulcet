import Foundation
import Testing
@testable import DulcetKit

@Suite @MainActor struct DulcetDiscoverTests {
    @Test func copiesEveryFieldIntoSwiftValues() {
        let ref = DulcetDiscoverLibraryRef(providerInstanceId: "account-a", type: "album", id: "opaque")
        let availability = DulcetDiscoverAvailability(status: "partlyHeld", completeness: "known", matchedBy: "mbid", matchedTrackCount: 8, totalTrackCount: 12, editionReleaseId: "edition", libraryItems: [ref])
        let item = DulcetDiscoverItem(id: "external", kind: "album", target: .init(kind: "album", mbid: "mbid"), title: "Version (Live)", availability: availability)
        #expect(item.availability.matchedTrackCount == 8)
        #expect(item.availability.totalTrackCount == 12)
        #expect(item.year == nil && item.listenCount == nil)
        let time = Date(timeIntervalSince1970: 123)
        let wish = DulcetDiscoverWish(id: "wish", userId: "user", kind: "future", snapshot: .init(title: "Title"), source: .init(type: "album"), addedAt: time, updatedAt: time, state: "future-state", note: "<plain text>", noteAt: time, availability: availability, revision: "rev")
        #expect(wish.note == "<plain text>" && wish.noteAt == time)
        #expect(wish.state == "future-state")
        #expect(wish.availability.libraryItems == [ref])
    }
    @Test func pendingOperationDoesNotCrossAccountAdapterReplacement() {
        let lifetime = DulcetDiscoverLifetime()
        var delivered = [String]()
        let complete = lifetime.delivery { (value: Result<String, DulcetDiscoverFailure>) in
            if case let .success(value) = value { delivered.append(value) }
        }
        lifetime.close()
        complete(.success("late A"))
        #expect(delivered.isEmpty)
    }
    @Test func cacheClearAndAccountRemovalKeepTheirExistingSemantics() {
        // Shell closure is cancellation only: it never issues a remove. Destructive account
        // deletion remains the existing account-data flow, after the reader drains.
        let lifetime = DulcetDiscoverLifetime()
        var cancellationCount = 0
        let operation = lifetime.operation { cancellationCount += 1 }
        lifetime.close()
        operation.cancel()
        #expect(cancellationCount == 1)
    }
}
