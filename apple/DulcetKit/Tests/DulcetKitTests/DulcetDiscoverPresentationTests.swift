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
        let track = DulcetDiscoverItem(id: "track", kind: "song", title: "Track", availability: .init(status: "notHeld", completeness: "unknown"))
        let destination = DulcetDiscoverDestination(item: track, source: .init(type: "album", id: "parent-album"))
        if case let .item(_, source) = destination { #expect(source == .init(type: "album", id: "parent-album")) }
        else { Issue.record("A track retains its source page") }
    }
    @Test func editionSelectionCancelsOldPage() {
        let source = DiscoverProofReader()
        let detail = DulcetDiscoverDetailModel(reader: source)
        detail.loadAlbum("group", releaseId: "old")
        detail.loadAlbum("group", releaseId: "selected")
        #expect(source.albumCancellationCount == 1)
        let item = DulcetDiscoverItem(id: "album", kind: "album", title: "Album", availability: .init(status: "unknown", completeness: "unknown"))
        source.albumCompletions[1](.success(.init(item: item, selectedReleaseId: "selected", tracks: .init(items: [], age: .init(receivedAt: .now)))))
        source.albumCompletions[0](.success(.init(item: item, selectedReleaseId: "old", tracks: .init(items: [], age: .init(receivedAt: .now)))))
        #expect(detail.album?.selectedReleaseId == "selected")
        detail.close()
        source.albumCompletions[1](.success(.init(item: item, selectedReleaseId: "closed", tracks: .init(items: [], age: .init(receivedAt: .now)))))
        #expect(detail.album?.selectedReleaseId == "selected")
    }
}
