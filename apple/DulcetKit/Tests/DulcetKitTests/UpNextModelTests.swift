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

    /// The handle drag, as a person makes it: rows 60 pt tall, the second one taller (a wrapped
    /// title). Each drop must name the entry and land it where the gap showed.
    @Test
    func aHandleDragLandsTheRowWhereTheGapOpenedAndNamesTheRightQueueMove() throws {
        // queue: h0 cur1 | u2 u3 u4 u5   (upcoming rows 0...3 are queue indices 2...5)
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["h", "cur", "u0", "u1", "u2", "u3"], current: 1))
        let frames = [
            CGRect(x: 0, y: 0, width: 320, height: 60),
            CGRect(x: 0, y: 60, width: 320, height: 80),
            CGRect(x: 0, y: 140, width: 320, height: 60),
            CGRect(x: 0, y: 200, width: 320, height: 60),
        ]

        // Row 0 dragged down past row 1's middle (100) but not row 2's (170): it lands second.
        var down = try #require(DulcetQueueHandleDrag(source: 0, frames: frames))
        down.translation = 80
        #expect(down.target == 1)
        #expect(down.displacement(of: 1) == -60, "the row it passed steps up by the dragged row's height")
        #expect(down.displacement(of: 2) == 0)
        #expect(down.displacement(of: 0) == 0, "the dragged row follows the finger, not the gap")
        let downMove = try #require(down.moveDestination)
        #expect(model.moveIntent(fromOffsets: [0], toOffset: downMove) == .move(.init("entry-2"), toIndex: 3))

        // Row 3 dragged all the way up: it lands first and every row above steps down.
        var up = try #require(DulcetQueueHandleDrag(source: 3, frames: frames))
        up.translation = -230
        #expect(up.target == 0)
        #expect((0...2).map(up.displacement(of:)) == [60, 60, 60])
        let upMove = try #require(up.moveDestination)
        #expect(model.moveIntent(fromOffsets: [3], toOffset: upMove) == .move(.init("entry-5"), toIndex: 2))

        // Past the bottom: the last place, and no further.
        var end = try #require(DulcetQueueHandleDrag(source: 1, frames: frames))
        end.translation = 900
        #expect(end.target == 3)
        let endMove = try #require(end.moveDestination)
        #expect(model.moveIntent(fromOffsets: [1], toOffset: endMove) == .move(.init("entry-3"), toIndex: 5))

        // A small wobble is no move at all, and a row that is not there is no drag.
        var wobble = try #require(DulcetQueueHandleDrag(source: 2, frames: frames))
        wobble.translation = 20
        #expect(wobble.moveDestination == nil)
        #expect(DulcetQueueHandleDrag(source: 4, frames: frames) == nil)
    }

    /// The iPhone case: a lazy list has laid out only the rows on screen (here rows 0...4 of 12,
    /// 92 pt apart with 4 pt between them), so the rows below have no frame. Dragging the first
    /// row 228 pt down -- the device's drag to the gap after the third row -- must land it third,
    /// not past every row the list had not laid out (OBSERVED 2026-11-27: it landed near the end).
    @Test
    func aHandleDragCountsRowsTheListHasNotLaidOutWhereTheyAreNotAtTheTop() throws {
        let frames = lazyFrames(count: 12, laidOut: 0...4)
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["cur"] + (0..<12).map { "u\($0)" }, current: 0))

        var drag = try #require(DulcetQueueHandleDrag(source: 0, frames: frames))
        #expect(drag.spacing == 4)
        // Each distance by the row middles it passes: 92 pt a row.
        for (distance, landing) in [(40, 0), (100, 1), (180, 1), (190, 2), (228, 2), (280, 3), (400, 4), (700, 7)] {
            drag.translation = CGFloat(distance)
            #expect(drag.target == landing, "\(distance) pt down lands at \(landing), not \(drag.target)")
        }
        drag.translation = 228
        #expect((1...2).map(drag.displacement(of:)) == [-92, -92], "the passed rows step up a whole pitch")
        #expect(drag.displacement(of: 3) == 0)
        let move = try #require(drag.moveDestination)
        #expect(model.moveIntent(fromOffsets: [0], toOffset: move) == .move(.init("entry-1"), toIndex: 3))

        // A drag that starts low in the screen and goes up, past rows the list scrolled away
        // from: their stale frames are dropped for estimates where they are now.
        var stale = frames
        stale[0] = CGRect(x: 0, y: -900, width: 320, height: 88)
        var up = try #require(DulcetQueueHandleDrag(source: 4, frames: stale))
        up.translation = -200
        #expect(up.target == 2)
        up.translation = -400
        #expect(up.target == 0)
        #expect(up.slots[0].minY == 0, "the stale row 0 sits at its estimated place, above row 1")
    }

    /// A long move scrolls the list under the held row: the drop counts the scroll as distance,
    /// rows the scroll lays out replace their estimates, and the lifted row stays under the finger.
    @Test
    func aHandleDragThatScrollsTheListLandsWhereTheFingerIsInTheContent() throws {
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["cur"] + (0..<12).map { "u\($0)" }, current: 0))
        var drag = try #require(DulcetQueueHandleDrag(source: 0, frames: lazyFrames(count: 12, laidOut: 0...4)))
        // The finger holds at the bottom edge, 380 pt down; the list scrolls 460 pt (five rows).
        drag.translation = 380
        #expect(drag.target == 4)
        let scrolled = 460.0
        var now: [Int: CGRect] = [:]
        for index in 5...9 {
            // Row 7 wraps its title: 30 pt taller than the estimate.
            let extra: CGFloat = index > 7 ? 30 : 0
            now[index] = CGRect(x: 0, y: CGFloat(index) * 92 - scrolled + extra, width: 320,
                                height: index == 7 ? 118 : 88)
        }
        now[0] = CGRect(x: 0, y: -scrolled, width: 320, height: 88)
        drag.observe(currentFrames: now)
        #expect(drag.scroll == 460)
        #expect(drag.liftedOffset == 840, "the lifted row is carried down with its place")
        #expect(drag.slots[8].minY == 766, "row 8 is where the list laid it out, below the tall row 7")
        // The finger's point in the content is 44 + 380 + 460 = 884: past row 8's middle (810),
        // short of row 9's (902). Without the scroll it would be 424, short of row 5.
        #expect(drag.target == 8)
        let move = try #require(drag.moveDestination)
        #expect(model.moveIntent(fromOffsets: [0], toOffset: move) == .move(.init("entry-1"), toIndex: 9))

        // Back up the other way: the list scrolls back to the top and the finger rises.
        now = [0: CGRect(x: 0, y: 0, width: 320, height: 88), 1: CGRect(x: 0, y: 92, width: 320, height: 88)]
        drag.observe(currentFrames: now)
        #expect(drag.scroll == 0)
        drag.translation = 100
        #expect(drag.target == 1)
    }

    /// The edge zone that scrolls the list: only near an edge, and only toward rows that remain.
    @Test
    func aHandleDragScrollsOnlyFromTheEdgesTowardRowsThatRemain() throws {
        var drag = try #require(DulcetQueueHandleDrag(source: 1, frames: lazyFrames(count: 12, laidOut: 0...4)))
        let viewport = CGRect(x: 0, y: 0, width: 320, height: 460)
        #expect(drag.autoscrollDirection(viewport: viewport, margin: 56) == nil)
        drag.translation = 300 // middle at 436, within 56 of 460
        #expect(drag.autoscrollDirection(viewport: viewport, margin: 56) == .down)
        drag.translation = -120 // middle at 16
        #expect(drag.autoscrollDirection(viewport: viewport, margin: 56) == nil, "nothing above the first row")
        var last = try #require(DulcetQueueHandleDrag(source: 11, frames: lazyFrames(count: 12, laidOut: 9...11)))
        last.translation = -900
        #expect(last.autoscrollDirection(viewport: CGRect(x: 0, y: 700, width: 320, height: 460), margin: 56) == .up)
    }

    /// Rows 92 pt apart (88 tall, 4 between), with frames only for the rows the list laid out.
    private func lazyFrames(count: Int, laidOut: ClosedRange<Int>) -> [CGRect?] {
        (0..<count).map { index in
            laidOut.contains(index) ? CGRect(x: 0, y: CGFloat(index) * 92, width: 320, height: 88) : nil
        }
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

    @Test
    func historyReadsMostRecentFirstAndPlayingARowAgainNamesItsEntry() {
        // The same track twice, before and after the current one: history is entries, not titles.
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["a", "b", "a", "cur", "c"], current: 3))
        #expect(model.recentHistory.map(\.id.rawValue) == ["entry-2", "entry-1", "entry-0"])
        #expect(model.jumpIntent(to: model.recentHistory[0]) == .jump(.init("entry-2")))
        // Nothing has played before the first entry, and nothing is history without a current one.
        #expect(DulcetUpNextModel(nowPlaying: nowPlaying(["cur", "u0"], current: 0)).recentHistory.isEmpty)
        #expect(DulcetUpNextModel(nowPlaying: nowPlaying(["a", "b"], current: nil)).recentHistory.isEmpty)
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

/// Which swipe on the player's artwork asks for what -- and which ask nothing.
struct ArtworkSwipeTests {
    private func action(
        _ width: CGFloat, _ height: CGFloat,
        next: Bool = true, previous: Bool = true, dismiss: Bool = true
    ) -> DulcetArtworkSwipe? {
        DulcetArtworkSwipe.action(
            for: CGSize(width: width, height: height),
            canGoNext: next, canGoPrevious: previous, canDismiss: dismiss
        )
    }

    @Test
    func leftIsNextRightIsPreviousAndDownCloses() {
        #expect(action(-120, 10) == .next)
        #expect(action(120, -10) == .previous)
        #expect(action(8, 160) == .dismiss)
    }

    @Test
    func shortDiagonalOrUnavailableSwipesAskForNothing() {
        // Short of the threshold.
        #expect(action(-60, 0) == nil)
        #expect(action(0, 100) == nil)
        // Diagonal: neither direction dominates, so neither is guessed.
        #expect(action(-120, 90) == nil)
        #expect(action(90, 150) == nil)
        // A control that is not available is not reached by swiping either.
        #expect(action(-120, 0, next: false) == nil)
        #expect(action(120, 0, previous: false) == nil)
        #expect(action(0, 200, dismiss: false) == nil)
        // Upward is not a gesture here.
        #expect(action(0, -200) == nil)
    }
}
