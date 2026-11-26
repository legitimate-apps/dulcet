import Testing
@testable import DulcetKit

/// The Top Result the core names and the keyboard giving way when a result is used (a song's mini
/// player showing, an album's page opening). The card and the field's focus are drawn by SwiftUI;
/// what is decided, and so what a wrong change would flip, is tested here.
@MainActor
struct SearchTopResultTests {
    private func row(_ kind: String, _ rawID: String, title: String) throws -> DulcetReaderSearchRow {
        try #require(DulcetReaderSearchRow(
            kind: kind, providerInstanceID: "provider-top", rawID: rawID, title: title,
            credits: [(role: "artist", name: "Pavement", rawID: nil)], albumTitle: nil, year: nil,
            durationMilliseconds: 160_000, discNumber: nil, trackNumber: nil, sourceContainer: nil,
            mediaSourceID: nil, artworkKey: "al-\(rawID)", source: "server", favourite: nil, rating: nil,
            playability: nil
        ))
    }

    private func publication(_ rows: [DulcetReaderSearchRow], top: Int?) -> DulcetReaderSearchPublication {
        DulcetReaderSearchPublication(
            query: "gold", sequence: 1, scope: .serverAndDevice, rows: rows, topResultIndex: top)
    }

    private func store() -> DulcetPresentationStore {
        DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .libraryBrowse))
    }

    @Test func theTopResultIsTheRowTheCoreNamedNotTheFirstOne() throws {
        let rows = [try row("track", "t1", title: "Gold Soundz"), try row("artist", "ar1", title: "Gold Artist")]
        #expect(publication(rows, top: 1).topResult?.id.rawID == "ar1")
        #expect(publication(rows, top: 0).topResult?.id.rawID == "t1")
    }

    @Test func noTopResultWhenTheCoreNamedNoneOrNamedARowThatIsNotThere() throws {
        let rows = [try row("track", "t1", title: "Gold Soundz")]
        #expect(publication(rows, top: nil).topResult == nil)
        #expect(publication(rows, top: 1).topResult == nil)
        #expect(publication(rows, top: -1).topResult == nil)
        #expect(publication([], top: 0).topResult == nil)
    }

    @Test func aPublicationBuiltWithoutAnIndexLeadsNothing() throws {
        let plain = DulcetReaderSearchPublication(
            query: "gold", sequence: 1, scope: .serverAndDevice, rows: [try row("track", "t1", title: "Gold Soundz")])
        #expect(plain.topResult == nil)
    }

    @Test func usingAnyKindOfResultLetsGoOfTheKeyboardOncePerUse() throws {
        let store = store()
        let rows = [
            try row("track", "t1", title: "Gold Soundz"),
            try row("album", "al1", title: "Gold Soundz EP"),
            try row("artist", "ar1", title: "Gold Artist"),
        ]
        let pub = publication(rows, top: 0)
        #expect(store.searchKeyboardDismissals == 0, "control: nothing has been used yet")
        for (count, row) in rows.enumerated() {
            store.activateReaderSearchRow(row, in: pub)
            #expect(store.searchKeyboardDismissals == count + 1, "\(row.result.kind) use asks the field to let go")
        }
    }

    @Test func theTopResultsPlayLetsGoOfTheKeyboardToo() throws {
        let store = store()
        let album = try row("album", "al1", title: "Gold Soundz EP")
        let pub = publication([album], top: 0)
        store.playSearchTopResult(album, in: pub)
        #expect(store.searchKeyboardDismissals == 1)
        let song = try row("track", "t1", title: "Gold Soundz")
        store.playSearchTopResult(song, in: publication([song], top: 0))
        #expect(store.searchKeyboardDismissals == 2, "a song's Play is one use, not two")
    }
}
