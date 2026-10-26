import Testing
@testable import DulcetKit

// What the search screen says for each reader publication (spec §16.15). A failed or offline
// server search with nothing on the device is never drawn as "no matches", and only a pending
// one with no rows draws the waiting state, which the core bounds.

@MainActor
struct SearchPresentationTests {
    private func content(_ query: String, _ scope: DulcetReaderSearchScope?) -> DulcetReaderSearchContent {
        DulcetReaderSearchContent(
            query: query,
            publication: scope.map { DulcetReaderSearchPublication(query: query, sequence: 1, scope: $0, rows: []) },
            onActivate: { _ in }
        )
    }

    private let seen = DulcetReaderSeenCounts(artists: 3, albums: 4, tracks: 5)

    @Test func missingSearchTitlesUseTheReaderFallbackInRowsAndPlayback() {
        for title in ["", "  ", "\n\t", "A real title"] {
            let result = DulcetSearchResult(
                id: DulcetProviderItemID(providerInstanceID: "fixture", rawID: "track"),
                title: title, kind: .track, credits: [], albumTitle: nil, year: nil,
                duration: .seconds(60), mediaSourceID: nil,
                artwork: DulcetArtwork(seed: "track", palette: .indigoCoral)
            )
            let expected = title == "A real title" ? title : DulcetStrings.readerUntitled
            #expect(result.displayTitle == expected)
            #expect(result.playableTrack?.title == expected)
            let unknownDuration = DulcetSearchResult(
                id: result.id, title: title, kind: .track, credits: [], albumTitle: nil,
                year: nil, duration: nil, mediaSourceID: nil, artwork: result.artwork
            )
            #expect(unknownDuration.playableTrack == nil)
        }
    }

    @Test func aTimedOutServerSearchWithNothingOnTheDeviceIsAFailureNotNoMatches() {
        #expect(content("Thirty One Seconds", .deviceServerFailed(.timeout, seen)).presentation == .failed(.timeout))
        #expect(content("Thirty One Seconds", .deviceServerFailed(.timeout, nil)).presentation == .failed(.timeout))
    }

    @Test func anOfflineSearchWithNothingOnTheDeviceSaysSoNotNoServerMatches() {
        #expect(content("ab", .deviceOffline(seen)).presentation == .offlineNoMatches(seen))
    }

    @Test func onlyAnAnsweredServerSearchSaysNoMatches() {
        #expect(content("ab", .serverAndDevice).presentation == .noMatches)
        #expect(content("ab", .deviceWhileServerPending).presentation == .waiting)
        #expect(content("a", .deviceWhileServerPending).presentation == .noMatches)
        #expect(content("ab", nil).presentation == .waiting)
        #expect(content("  ", nil).presentation == .idle)
    }

    @Test func theFailureStringNamesTheKind() {
        let body = DulcetStrings.readerSearchFailedBody(DulcetStrings.readerErrorPhrase(.timeout))
        #expect(body.contains(DulcetStrings.readerErrorPhrase(.timeout)))
        #expect(body != DulcetStrings.searchEmptyBody)
    }
}
