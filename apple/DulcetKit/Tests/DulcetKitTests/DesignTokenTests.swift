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

@Test @MainActor
func readerGridsUseConsistentTileGeometry() {
    typealias Grid = DulcetArtworkGrid<EmptyView>
#if os(tvOS)
    let grid = Grid.geometry(available: 1_200, containerWidth: 1_200, accessibility: false)
    #expect(grid.columns == 4)
    #expect(grid.tileWidth == DulcetMetrics.readerShelfTileWidth(forWidth: 1_200, accessibility: false))
#else
#if os(macOS)
    let sizes = [(119.0, 1), (351.0, 2), (728.5, 5), (976.0, 7)]
#else
    let sizes = [(119.0, 1), (351.0, 2), (728.5, 3), (976.0, 5)]
#endif
    for (width, columns) in sizes {
        let grid = Grid.geometry(available: width, containerWidth: width + 48, accessibility: false)
        #expect(grid.columns == columns)
        let rightEdge = CGFloat(grid.columns) * grid.tileWidth + CGFloat(grid.columns - 1) * grid.spacing
        #expect(abs(rightEdge - width) < 0.001)
    }

    let offline = DulcetResponsiveGridLayout.albums(
        availableWidth: 1_132, compact: false, accessibility: false, spacing: 16,
        regularMinimum: 120, maximumColumns: 6
    )
    #expect(offline.columns == 6)
    #expect(abs(6 * offline.tileWidth + 5 * offline.spacing - 1_132) < 0.001)

    let phone = Grid.geometry(available: 370, containerWidth: 402, accessibility: true)
    #expect(phone.columns == 1)
    #expect(phone.tileWidth == 370)

    let regularPane = Grid.geometry(available: 584, containerWidth: 649, accessibility: true)
    #expect(regularPane.columns == 2)
    #expect(regularPane.tileWidth == 284)
#endif
}

@Test @MainActor
func compactShelfCaptionsKeepEqualHeightWhenTitlesWrap() throws {
    func height(for title: String, shelfTitleLines: Int?) throws -> Int {
        let caption = DulcetReaderTileCaption(title: title, subtitle: "Fixture Artist", rank: nil,
            shelfTitleLines: shelfTitleLines)
            .frame(width: 120, alignment: .leading)
            .dynamicTypeSize(.large)
        return try #require(ImageRenderer(content: caption).cgImage).height
    }

    let oneLineCaption = try height(for: "Short", shelfTitleLines: 1)
    let shortCaption = try height(for: "Short", shelfTitleLines: 2)
    let longTitle = "An Album Title Long Enough to Wrap"
    let wrappedCaption = try height(for: longTitle, shelfTitleLines: 2)
    let gridCaption = try height(for: longTitle, shelfTitleLines: nil)
    #expect(oneLineCaption < shortCaption)
    #expect(shortCaption == wrappedCaption)
    #expect(shortCaption == gridCaption)
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
