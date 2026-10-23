import Foundation
import Testing
@testable import DulcetKit

@Suite @MainActor struct DulcetDiscoverPresentationTests {
    @Test func scopeControlsOnlyTheSelectedSearchReader() {
        var local = [String](); var discover = [String]()
        DulcetDiscoverSearchScope.server.update("local", local: { local.append($0) }, discover: { discover.append($0) })
        DulcetDiscoverSearchScope.everywhere.update("outside", local: { local.append($0) }, discover: { discover.append($0) })
        #expect(local == ["local"] && discover == ["outside"])
    }
    @Test func busyAndStaleHaveVisibleAgeAndRetry() {
        let now = Date(timeIntervalSince1970: 1000)
        let failure = DulcetDiscoverFailure(code: "rate-limited", retryAt: now.addingTimeInterval(120), retryable: true)
        let presentation = DulcetDiscoverStatus(age: .init(fetchedAt: now.addingTimeInterval(-7200), sourceAgeSeconds: 7200, receivedAt: now, stale: true), error: failure, connection: "online", now: now)
        #expect(!presentation.canRetry)
        #expect(presentation.notice?.contains("120") == true)
        #expect(presentation.ageText?.contains("Cached answer") == true)
        #expect(presentation.ageText?.contains("7200") == true)
    }
    @Test func heldPartialUnknownAndMissingMbidRenderDistinctActions() {
        let partial = DulcetDiscoverItem(id: "a", kind: "album", title: "Live", availability: .init(status: "partlyHeld", completeness: "known", matchedTrackCount: 8, totalTrackCount: 12))
        #expect(DulcetDiscoverItemPresentation(item: partial).availabilityText == "8 of 12 here")
        #expect(!DulcetDiscoverItemPresentation(item: partial).canWish)
        let future = DulcetDiscoverItem(id: "b", kind: "future", title: "Unknown", availability: .init(status: "future", completeness: "unknown"))
        #expect(DulcetDiscoverItemPresentation(item: future).libraryReferences.isEmpty)
        #expect(!DulcetDiscoverItemPresentation(item: future).canWish)
    }
    @Test func editionSelectionCancelsOldPage() {
        let selection = DulcetDiscoverDetailSelection()
        let old = selection.begin()
        let current = selection.begin()
        #expect(!selection.accepts(old))
        #expect(selection.accepts(current))
        selection.close()
        #expect(!selection.accepts(current))
    }
}
