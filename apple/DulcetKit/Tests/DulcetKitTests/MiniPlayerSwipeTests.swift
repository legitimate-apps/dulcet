#if os(macOS) || os(iOS)
import CoreGraphics
import Testing
@testable import DulcetKit

/// The mini player's horizontal swipe: left is Next, right is Previous, a vertical pan is never
/// a skip (it opens the player), and a tap's jitter never skips.
@Suite struct MiniPlayerSwipeTests {
    private func action(_ dx: CGFloat, _ dy: CGFloat = 0, projected: CGFloat? = nil,
                        next: Bool = true, previous: Bool = true) -> DulcetMiniPlayerSwipe? {
        DulcetMiniPlayerSwipe.action(
            translation: CGSize(width: dx, height: dy),
            predictedEndTranslation: CGSize(width: projected ?? dx, height: dy),
            canGoNext: next,
            canGoPrevious: previous
        )
    }

    @Test func leftIsNextAndRightIsPrevious() {
        #expect(action(-80) == .next)
        #expect(action(80) == .previous)
    }

    @Test func aShortSlowDragSettlesBack() {
        #expect(action(-30) == nil)
        #expect(action(30) == nil)
    }

    @Test func aFlickSkipsFromAShortTravel() {
        #expect(action(-24, projected: -200) == .next)
        #expect(action(24, projected: 200) == .previous)
        // Jitter, however fast, is a tap.
        #expect(action(-8, projected: -200) == nil)
        // A flick back against the drag does not skip the way the drag went.
        #expect(action(-30, projected: 150) == nil)
    }

    @Test func aMostlyVerticalPanIsNeverASkip() {
        #expect(action(-80, -120) == nil)
        #expect(DulcetMiniPlayerSwipe.axis(of: CGSize(width: 6, height: -14)) == .vertical)
        #expect(DulcetMiniPlayerSwipe.axis(of: CGSize(width: -14, height: 6)) == .horizontal)
    }

    @Test func aSkipThatCannotHappenDoesNothingAndRubberBands() {
        #expect(action(-120, next: false) == nil)
        #expect(action(120, previous: false) == nil)
        let blocked = DulcetMiniPlayerSwipe.follow(-120, canGoNext: false, canGoPrevious: true)
        #expect(blocked < 0 && blocked > -40)
        #expect(DulcetMiniPlayerSwipe.follow(-120, canGoNext: true, canGoPrevious: true) == -120)
    }
}
#endif
