import Foundation
import Testing
@testable import DulcetKit

/// Every translation between "row in the Up Next section" and "position in the whole queue".
struct UpNextModelTests {
    @Test
    func theModelSplitsHistoryCurrentAndUpcomingByEntryNotByTrack() {
        // The same track twice: an edit must name the entry, never the track.
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["a", "b", "a", "c"], current: 1))

        #expect(model.history.map(\.id.rawValue) == ["entry-0"])
        #expect(model.current?.id.rawValue == "entry-1")
        #expect(model.upcoming.map(\.id.rawValue) == ["entry-2", "entry-3"])
        #expect(model.upcoming.map(\.track.title) == ["a", "c"])
        #expect(model.sourceDisplayName == "Album")
    }

    @Test
    func swiftUIMoveOffsetsBecomeWholeQueueIndices() {
        // queue: h0 cur1 | u2 u3 u4 u5   (upcoming rows 0...3 are queue indices 2...5)
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["h", "cur", "u0", "u1", "u2", "u3"], current: 1))

        // Drag row 0 below row 1: SwiftUI reports destination 2 (before removal).
        #expect(model.moveIntent(fromOffsets: [0], toOffset: 2) == .move(.init("entry-2"), toIndex: 3))
        // Drag row 3 to the top of Up Next.
        #expect(model.moveIntent(fromOffsets: [3], toOffset: 0) == .move(.init("entry-5"), toIndex: 2))
        // Drag row 1 to the very end.
        #expect(model.moveIntent(fromOffsets: [1], toOffset: 4) == .move(.init("entry-3"), toIndex: 5))
        // No-ops and malformed gestures produce no edit.
        #expect(model.moveIntent(fromOffsets: [1], toOffset: 1) == nil)
        #expect(model.moveIntent(fromOffsets: [1], toOffset: 2) == nil)
        #expect(model.moveIntent(fromOffsets: [0, 1], toOffset: 3) == nil)
        #expect(model.moveIntent(fromOffsets: [9], toOffset: 0) == nil)
        #expect(model.moveIntent(fromOffsets: [0], toOffset: 9) == nil)
    }

    @Test
    func removalAndJumpNameEntriesAndNeverTheCurrentOne() {
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["cur", "u0", "u1"], current: 0))

        #expect(model.removeIntents(atOffsets: [0, 1]) == [.remove(.init("entry-1")), .remove(.init("entry-2"))])
        #expect(model.removeIntents(atOffsets: [7]).isEmpty)
        #expect(model.jumpIntent(to: model.upcoming[1]) == .jump(.init("entry-2")))
    }

    @Test
    func withoutACurrentEntryEverythingIsUpcomingAndNothingPlayingIsEmpty() {
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["a", "b"], current: nil))
        #expect(model.current == nil)
        #expect(model.upcoming.count == 2)
        #expect(model.moveIntent(fromOffsets: [1], toOffset: 0) == .move(.init("entry-1"), toIndex: 0))

        let empty = DulcetUpNextModel(nowPlaying: nil)
        #expect(empty.upcoming.isEmpty && empty.history.isEmpty && empty.current == nil)
    }

    private func nowPlaying(_ titles: [String], current: Int?) -> DulcetNowPlaying {
        let entries = titles.enumerated().map { offset, title in
            DulcetQueueEntry(id: .init("entry-\(offset)"), track: track(title))
        }
        return DulcetNowPlaying(
            current: entries[current ?? 0].track,
            queue: entries.map(\.track),
            currentIndex: current ?? 0,
            sourceDisplayName: "Album",
            elapsed: .zero,
            isPlaying: true,
            outputName: "This Device",
            volume: 1,
            audioFormat: .init(codec: "MP3", sampleRateKilohertz: 0),
            queueEntries: entries,
            currentEntryIndex: current
        )
    }

    private func track(_ title: String) -> DulcetTrack {
        DulcetTrack(
            id: .init(providerInstanceID: "server", rawID: title),
            title: title,
            credits: [],
            albumTitle: "Album",
            duration: .seconds(180),
            mediaSourceID: nil,
            artwork: DulcetArtwork(seed: title, palette: .indigoCoral)
        )
    }
}
