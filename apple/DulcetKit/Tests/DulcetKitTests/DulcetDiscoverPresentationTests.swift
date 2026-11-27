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
        #expect(presentation.ageText == DulcetStrings.discoverShowingOlderResults(relative(7200, now: now)),
                "a stale answer says the refresh failed, with its age in words")
        #expect(presentation.ageText?.contains("7200") == false, "the age is never a count of seconds")
    }
    @Test func aCurrentOrDeviceKeptAnswerSaysNothing() {
        let now = Date(timeIntervalSince1970: 100_000)
        let kept = DulcetDiscoverAge(fetchedAt: now.addingTimeInterval(-111), receivedAt: now, fromDeviceCache: true)
        let quiet = DulcetDiscoverStatus(age: kept, error: nil, connection: "online", now: now)
        #expect(quiet.ageText == nil, "an answer this device kept from earlier draws no line while it refreshes")
        #expect(quiet.notice == nil)
        #expect(quiet.accessibleAge == DulcetStrings.discoverUpdated(relative(111, now: now)),
                "the age stays available to accessibility")
        let live = DulcetDiscoverStatus(age: .init(sourceAgeSeconds: 5, receivedAt: now), error: nil, connection: "online", now: now)
        #expect(live.ageText == nil)
        #expect(live.accessibleAge == DulcetStrings.discoverUpdated(DulcetStrings.readerJustNow))
        let cancelled = DulcetDiscoverStatus(age: kept, error: .init(code: "cancelled", retryAt: nil, retryable: false), connection: "online", now: now)
        #expect(cancelled.ageText == nil, "a cancelled refresh is not a failure")
    }
    @Test func aFailedRefreshOrOfflineSaysSoOnceWithTheAge() {
        let now = Date(timeIntervalSince1970: 100_000)
        let kept = DulcetDiscoverAge(fetchedAt: now.addingTimeInterval(-3 * 3600), receivedAt: now, fromDeviceCache: true)
        let failure = DulcetDiscoverFailure(code: "upstream-unavailable", retryAt: nil, retryable: true)
        let failed = DulcetDiscoverStatus(age: kept, error: failure, connection: "online", now: now)
        #expect(failed.ageText == DulcetStrings.discoverShowingOlderResults(relative(3 * 3600, now: now)))
        #expect(failed.notice == nil, "the age line already says the refresh failed; no second line")
        let offline = DulcetDiscoverStatus(age: kept, error: nil, connection: "offline", now: now)
        #expect(offline.ageText == nil)
        #expect(offline.notice == nil)
        let transport = DulcetDiscoverFailure(code: "offline", retryable: true)
        let shelves = DulcetDiscoverShelf.allCases.map { DulcetDiscoverShelfState(id: $0.rawValue, error: transport) }
        let home = DulcetDiscoverStatus.home(.init(shelves: shelves), connection: "online")
        #expect(home.serverReadFailed)
        #expect(home.notice == nil && home.ageText == nil)
        #expect(!DulcetDiscoverStatus.home(.init(shelves: []), connection: "online").serverReadFailed)
        let unknown = DulcetDiscoverStatus(age: .init(receivedAt: now, stale: true), error: nil, connection: "online", now: now)
        #expect(unknown.ageText == DulcetStrings.discoverShowingEarlierResults, "an unknown age is never zero")
        let nothingShown = DulcetDiscoverStatus(age: nil, error: failure, connection: "online", now: now)
        #expect(nothingShown.notice == DulcetStrings.discoverReadFailed, "without an answer the failure is stated in its place")
    }
    private func relative(_ seconds: TimeInterval, now: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: now.addingTimeInterval(-seconds), relativeTo: now)
    }
    @Test func heldPartialUnknownAndMissingMbidRenderDistinctActions() {
        let partial = DulcetDiscoverItem(id: "a", kind: "album", title: "Live", availability: .init(status: "partlyHeld", completeness: "known", matchedTrackCount: 8, totalTrackCount: 12))
        #expect(DulcetDiscoverItemPresentation(item: partial).availabilityText == "8 of 12 in your library")
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
