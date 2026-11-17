import SwiftUI
import Testing
@testable import DulcetKit
#if os(iOS)
import UIKit
#endif

@Test
func artworkCornerRadiusScalesWithSize() {
    // A fixed 12 pt radius turned a 28 pt thumbnail nearly into a circle.
    #expect(DulcetMetrics.artworkCornerRadius(forSize: 28) == 5)
    #expect(DulcetMetrics.artworkCornerRadius(forSize: 32) == 5)
    #expect(DulcetMetrics.artworkCornerRadius(forSize: 48) == 6)
    #expect(DulcetMetrics.artworkCornerRadius(forSize: 64) == 6)
    #expect(DulcetMetrics.artworkCornerRadius(forSize: 88) == 8)
    #expect(DulcetMetrics.artworkCornerRadius(forSize: 120) == 8)
    #expect(DulcetMetrics.artworkCornerRadius(forSize: 220) == DulcetMetrics.artworkCornerRadius)
    // Never more than a quarter of the side, so no cover reads as a circle.
    for size: CGFloat in [20, 28, 32, 40, 48, 64, 72, 88, 120, 160, 220, 400] {
        #expect(DulcetMetrics.artworkCornerRadius(forSize: size) <= size / 4, "size \(size)")
    }
}

@Test
func contentMarginIsOneValuePerPlatformAndWidth() {
#if os(tvOS)
    #expect(DulcetMetrics.contentMargin(forWidth: 1920) == DulcetMetrics.tvPageInset)
#elseif os(macOS)
    for width: CGFloat in [400, 700, 1200] {
        #expect(DulcetMetrics.contentMargin(forWidth: width) == 24)
        #expect(DulcetLibraryMetrics.horizontalInset(forWidth: width) == 24)
    }
#else
    #expect(DulcetMetrics.contentMargin(forWidth: 402) == 16)
    #expect(DulcetMetrics.contentMargin(forWidth: 440) == 20)
    #expect(DulcetMetrics.contentMargin(forWidth: 820) == 20)
    #expect(DulcetLibraryMetrics.horizontalInset(forWidth: 402) == DulcetMetrics.contentMargin(forWidth: 402))
#endif
}

@Test
func rowMetricsKeepTouchTargetsAndArtworkInsideTheRow() {
    #expect(DulcetMetrics.rowMinHeight(artwork: true) >= DulcetMetrics.rowArtworkSize)
    #expect(DulcetMetrics.rowMinHeight(artwork: true) > DulcetMetrics.rowMinHeight(artwork: false))
    #expect(DulcetMetrics.rowArtworkSize <= DulcetMetrics.rowArtworkMaxSize)
#if os(iOS)
    #expect(DulcetMetrics.rowMinHeight(artwork: false) >= DulcetMetrics.minimumTouchTarget)
#endif
}

@Test
func prominentActionDefaultsToTheRegularControlSize() {
    let action = DulcetProminentAction("Play") {}
    #expect(action.controlSize == .regular)
    let large = DulcetProminentAction("Try Again", controlSize: .large) {}
    #expect(large.controlSize == .large)
}

#if os(iOS)
/// The iPhone and iPad content margin mirrors the system's minimum layout margin, which has no
/// SwiftUI API. This checks the mirror against UIKit at phone, large-phone and iPad widths.
@Test @MainActor
func contentMarginMatchesTheSystemLayoutMargin() {
    for width: CGFloat in [375, 402, 440, 820] {
        let controller = UIViewController()
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 900))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.layoutIfNeeded()
        let system = controller.systemMinimumLayoutMargins.leading
        #expect(system > 0, "width \(width): no system margin measured")
        #expect(DulcetMetrics.contentMargin(forWidth: width) == system,
                "width \(width): system margin \(system)")
        window.isHidden = true
    }
}
#endif
