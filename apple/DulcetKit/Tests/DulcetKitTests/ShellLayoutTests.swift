#if os(macOS) || os(iOS)
import SwiftUI
import Testing
@testable import DulcetKit
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// The width a view asks for when offered `width` points, measured through the platform's own
/// hosting controller rather than reasoned about.
@MainActor
private func fittedSize<Content: View>(_ view: Content, width: CGFloat) -> CGSize {
#if os(macOS)
    let controller = NSHostingController(rootView: view)
#else
    let controller = UIHostingController(rootView: view)
#endif
    return controller.sizeThatFits(in: CGSize(width: width, height: 10_000))
}

@Test
func albumHeaderStacksOnEveryPhoneWidthAndAtAccessibilitySizes() {
    // iPhone SE, iPhone 17 Pro, a phone in landscape, and a narrow iPad split column.
    for width: CGFloat in [320, 375, 402, 568] {
        #expect(DulcetAlbumDetailView.headerLayout(width: width, accessibilitySize: false) == .stacked,
                "width \(width)")
    }
    #expect(DulcetAlbumDetailView.headerLayout(width: 700, accessibilitySize: false) == .sideBySide)
    #expect(DulcetAlbumDetailView.headerLayout(width: 1_200, accessibilitySize: true) == .stacked)
}

@Test @MainActor
func albumHeaderFitsTheWidthItIsGivenInBothArrangements() throws {
    let snapshot = DulcetDeterministicFixture().snapshot(for: .albumDetailMultiDisc)
    let album = try #require(snapshot.selectedAlbum)
    let store = DulcetPresentationStore(
        source: DulcetDeterministicDataSource(initialState: .albumDetailMultiDisc)
    )
    for width: CGFloat in [320, 402, 600, 947] {
        let inset = DulcetLibraryMetrics.horizontalInset(forWidth: width)
        let available = width - 2 * inset
        let layout = DulcetAlbumDetailView.headerLayout(width: width, accessibilitySize: false)
        let artwork = DulcetAlbumDetailView.artworkSize(
            width: width,
            inset: inset,
            accessibilitySize: false
        )
        #expect(artwork <= available, "artwork \(artwork) wider than \(available)")
        let size = fittedSize(
            DulcetAlbumHeader(
                album: album,
                layout: layout,
                artworkSize: artwork,
                onPlay: {},
                onShuffle: {}
            )
            .environment(store),
            width: available
        )
        #expect(size.width <= available + 0.5, "\(layout) at \(width) asked for \(size.width) of \(available)")
        // A header whose text collapsed to a sliver wraps into a tower: bound the height too.
        #expect(size.height < (layout == .stacked ? artwork + 320 : 420),
                "\(layout) at \(width) is \(size.height) tall")
    }
}

@Test @MainActor
func equalWidthActionsFallBackToAColumnInsteadOfWrappingLabels() {
    let wide = fittedSize(
        DulcetEqualWidthActions {
            Button("Play All") {}.buttonStyle(.borderedProminent)
        } secondary: {
            Button("Shuffle") {}.buttonStyle(.bordered)
        },
        width: 288
    )
    let narrow = fittedSize(
        DulcetEqualWidthActions {
            Button("Play All") {}.buttonStyle(.borderedProminent)
        } secondary: {
            Button("Shuffle") {}.buttonStyle(.bordered)
        },
        width: 90
    )
    #expect(wide.width <= 288.5)
    // At 90 points neither label fits in a half, so the pair stacks: taller, never wider.
    #expect(narrow.height > wide.height * 1.5, "narrow \(narrow) wide \(wide)")
}

@Test
func nowPlayingGoesToOneColumnBelowItsSideBySideWidth() {
    #expect(!DulcetNowPlayingView.usesSideBySideLayout(width: 402, accessibilitySize: false))
    #expect(!DulcetNowPlayingView.usesSideBySideLayout(width: 700, accessibilitySize: false))
    #expect(DulcetNowPlayingView.usesSideBySideLayout(width: 947, accessibilitySize: false))
    #expect(!DulcetNowPlayingView.usesSideBySideLayout(width: 1_400, accessibilitySize: true))
}

@Test @MainActor
func nowPlayingBarShowsOnlyWhileSomethingIsQueued() {
    #expect(!DulcetPresentationStore(source: DulcetDeterministicDataSource(
        initialState: .libraryBrowse
    )).showsNowPlayingBar)
    #expect(DulcetPresentationStore(source: DulcetDeterministicDataSource(
        initialState: .nowPlaying
    )).showsNowPlayingBar)

    let source = DulcetDeterministicDataSource(
        initialState: .albumDetailMultiDisc,
        playbackStartNavigation: .stayOnCurrentSurface
    )
    let store = DulcetPresentationStore(source: source)
    let album = try! #require(store.snapshot.selectedAlbum)
    store.activateTrack(albumID: album.id, trackID: album.tracks[1].id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.showsNowPlayingBar)
    #expect(store.snapshot.nowPlaying?.current.id == album.tracks[1].id)
    store.selectDestination(.search)
    #expect(store.showsNowPlayingBar)
}
@Test
func sideBySideNowPlayingArtworkFitsTheWindowItIsGiven() {
    // iPad 13-inch: the full player column. iPad mini in landscape: smaller artwork, never so
    // large that the transport is pushed below the window, never smaller than a legible cover.
    #expect(DulcetNowPlayingView.sideBySideArtworkSize(height: 1_000) == 520)
    #expect(DulcetNowPlayingView.sideBySideArtworkSize(height: 700) == 320)
    #expect(DulcetNowPlayingView.sideBySideArtworkSize(height: 500) == 280)
}

@Test
func theSideBySideQueueColumnTakesThePlayersMeasuredHeight() {
    #expect(DulcetNowPlayingView.sideBySideQueueHeight(windowHeight: 1_000, playerHeight: 812) == 812)
    // A window shorter than the player gives the queue all of it.
    #expect(DulcetNowPlayingView.sideBySideQueueHeight(windowHeight: 700, playerHeight: 812) == 700)
    // Before the player has been measured, the whole window.
    #expect(DulcetNowPlayingView.sideBySideQueueHeight(windowHeight: 1_000, playerHeight: nil) == 1_000)
}

@Test
func aPausedOrFinishedPlayerShowsWhereItIsNotABareWord() {
    // A finished queue presents its last track paused with no progress in this session.
    #expect(DulcetNowPlayingView.showsProgressIndicator(progressBegan: false, phase: .paused))
    #expect(DulcetNowPlayingView.showsProgressIndicator(progressBegan: true, phase: .progressing))
    // Opening and buffering before any progress keep their words.
    #expect(!DulcetNowPlayingView.showsProgressIndicator(progressBegan: false, phase: .buffering))
    #expect(!DulcetNowPlayingView.showsProgressIndicator(progressBegan: false, phase: .ready))
}


/// A skip notice's card always contains its sentence (spec §12.12 rule 5). Its corners are
/// continuous, so they bend away from the straight edge over `cornerExtent` × the radius along
/// each side; a horizontal padding at least that wide keeps every line of text inside the card
/// however many lines it wraps to. A capsule's radius grows with a wrapped card's height, which
/// is how its first and last lines ended up outside the material.
@Test
func aNoticesPaddingClearsItsCardsCornersAtAnyHeight() {
    #expect(DulcetPlaybackNotice.horizontalPadding
        >= DulcetPlaybackNotice.cornerRadius * DulcetPlaybackNotice.cornerExtent)
}

/// A notice naming a long title wraps rather than truncating: twice the title is taller still,
/// where a line limit would stop the growth.
@Test @MainActor
func aLongNoticeWrapsAndIsNeverTruncated() {
    let width: CGFloat = 402
    func height(_ title: String) -> CGFloat {
        let notice = DulcetSkippedTrackNotice(sequence: 1, title: title)
        return fittedSize(DulcetPlaybackNoticeStack(notices: DulcetPlaybackNotices(skipMessage: notice.message)),
                          width: width).height
    }
    let long = String(repeating: "A Very Long Title That Wraps ", count: 4)
    let short = height("Probe")
    let once = height(long)
    let twice = height(long + long)
    print("DULCET NOTICE HEIGHTS short=\(short) long=\(once) twice-as-long=\(twice)")
    // A line of callout text is about 15 points: 10 is more than rounding, less than a line.
    #expect(once > short + 10, "a long title must wrap onto more lines; \(once) vs \(short)")
    #expect(twice > once + 10, "twice the title must be taller still; \(twice) vs \(once)")
}

#if os(macOS)
/// The first and last columns of a render holding anything drawn at all.
@MainActor
private func drawnColumns<Content: View>(_ view: Content, size: CGSize) throws -> ClosedRange<Int>? {
    try drawnBounds(view, size: size)?.columns
}

/// The first and last columns, and rows from the top, of a render holding anything drawn at all.
@MainActor
private func drawnBounds<Content: View>(
    _ view: Content,
    size: CGSize
) throws -> (columns: ClosedRange<Int>, rows: ClosedRange<Int>)? {
    let renderer = ImageRenderer(content: view.frame(width: size.width, height: size.height))
    renderer.scale = 1
    let image = try #require(renderer.cgImage, "the renderer must produce an image")
    let width = image.width, height = image.height
    var alpha = [UInt8](repeating: 0, count: width * height)
    let drawn = alpha.withUnsafeMutableBytes { raw -> Bool in
        guard let context = CGContext(
            data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(),
            bitmapInfo: CGImageAlphaInfo.alphaOnly.rawValue
        ) else { return false }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return true
    }
    try #require(drawn, "the render must redraw into an alpha buffer")
    var first = Int.max, last = Int.min, top = Int.max, bottom = Int.min
    for y in 0..<height {
        for x in 0..<width where alpha[y * width + x] > 0 {
            first = min(first, x); last = max(last, x)
            top = min(top, y); bottom = max(bottom, y)
        }
    }
    return first <= last ? (first...last, top...bottom) : nil
}

/// A notice that wraps stays a card clear of the screen's sides, never running edge to edge:
/// nothing of it is drawn within the outer margin, at a phone's width, however long the title.
@Test @MainActor
func aWrappingNoticeStaysClearOfTheScreensSides() throws {
    let size = CGSize(width: 402, height: 600)
    let title = String(repeating: "A Very Long Title That Wraps ", count: 4)
    let notices = DulcetPlaybackNotices(skipMessage: DulcetSkippedTrackNotice(sequence: 1, title: title).message)
    let columns = try #require(
        try drawnColumns(DulcetPlaybackNoticeStack(notices: notices).environment(\.colorScheme, .light), size: size),
        "the notice must be drawn"
    )
    let margin = Int(DulcetSpacing.lg)
    print("DULCET NOTICE COLUMNS \(columns) of 0...\(Int(size.width) - 1), margin=\(margin)")
    #expect(columns.lowerBound >= margin, "the notice reaches \(columns.lowerBound) pt from the left side")
    #expect(Int(size.width) - 1 - columns.upperBound >= margin,
            "the notice reaches \(Int(size.width) - 1 - columns.upperBound) pt from the right side")
    // The experiment is the one intended: the notice spans most of the width, so it wrapped.
    #expect(columns.count > Int(size.width) / 2, "the notice must wrap across the width; \(columns)")
}

/// The notice never grows over navigation and is never truncated (spec §12.12 rule 5): drawn in
/// the page's frame, it is offered a share of that height, and a sentence naming a title too long
/// for it gives way to the sentence without the title. Rendered in a region sized so the full
/// sentence cannot fit its share and the short one can, the notice drawn is the short one, inside
/// the region; given room, the same notice names its title.
@Test @MainActor
func aNoticeTooTallForItsShareOfThePageShowsTheSentenceWithoutTheTitle() throws {
    let width: CGFloat = 402
    let title = String(repeating: "A Very Long Title That Wraps ", count: 4)
    let notices = DulcetPlaybackNotices(skipMessage: DulcetSkippedTrackNotice(sequence: 1, title: title).message)
    let full = fittedSize(DulcetPlaybackNoticeStack(notices: notices), width: width).height
    let short = fittedSize(
        DulcetPlaybackNoticeStack(notices: DulcetPlaybackNotices(
            skipMessage: DulcetSkippedTrackNotice(sequence: 1, title: nil).message
        )),
        width: width
    ).height
    // The experiment is the one intended: the two sentences differ by lines, not rounding.
    try #require(full > short + 10, "the long title's sentence must be lines taller; \(full) vs \(short)")

    // The region sits below a strip standing in for the navigation bar, which must stay clear.
    let navigation = 100
    func drawnHeight(regionHeight: CGFloat) throws -> (height: Int, top: Int) {
        let page = VStack(spacing: 0) {
            Color.clear.frame(height: CGFloat(navigation))
            DulcetPlaybackNoticeRegion {
                DulcetPlaybackNoticeStack(notices: notices)
            }
            .frame(height: regionHeight)
        }
        let bounds = try #require(
            try drawnBounds(
                page.environment(\.colorScheme, .light),
                size: CGSize(width: width, height: CGFloat(navigation) + regionHeight)
            ),
            "the notice must be drawn in a region \(regionHeight) tall"
        )
        return (bounds.rows.count, bounds.rows.lowerBound)
    }

    // The share lies between the two sentences' heights.
    let tight = 3 * (full + short) / 2
    let constrained = try drawnHeight(regionHeight: tight)
    // Room for the whole sentence within the share.
    let roomy = try drawnHeight(regionHeight: 3 * full + 30)
    print("DULCET NOTICE FALLBACK full=\(full) short=\(short) region=\(tight) "
        + "drawn-constrained=\(constrained.height) drawn-roomy=\(roomy.height)")

    #expect(CGFloat(constrained.height) <= short, "the shorter sentence must be the one drawn; \(constrained)")
    #expect(CGFloat(constrained.height) <= tight * DulcetPlaybackNoticeRegion.maximumShare,
            "the notice must stay inside its share of the page; \(constrained)")
    #expect(constrained.top >= navigation, "nothing may be drawn over the navigation strip; \(constrained)")
    #expect(CGFloat(roomy.height) > short + 10, "with room, the notice names its title; \(roomy)")
}
#endif

#if os(iOS)
/// A transient notice's text stops growing at ``DulcetPlaybackNoticeStack/largestTextSize``: at the
/// largest accessibility size it measures exactly as at that one. The control is the same notice
/// outside the stack, which does grow -- this host scales Dynamic Type, so equality is the cap.
@Test @MainActor
func aNoticesTextStopsGrowingAtItsLargestTextSize() {
    let width: CGFloat = 402
    let message = DulcetSkippedTrackNotice(sequence: 1, title: "Unplayable Probe").message
    func stack(_ size: DynamicTypeSize) -> CGFloat {
        fittedSize(
            DulcetPlaybackNoticeStack(notices: DulcetPlaybackNotices(skipMessage: message))
                .environment(\.dynamicTypeSize, size),
            width: width
        ).height
    }
    func bare(_ size: DynamicTypeSize) -> CGFloat {
        fittedSize(
            DulcetPlaybackNotice(text: message, systemImage: "forward.end").environment(\.dynamicTypeSize, size),
            width: width
        ).height
    }
    print("DULCET NOTICE CAP stack-ax2=\(stack(.accessibility2)) stack-ax5=\(stack(.accessibility5)) "
        + "bare-ax2=\(bare(.accessibility2)) bare-ax5=\(bare(.accessibility5))")
    #expect(bare(.accessibility5) > bare(.accessibility2) + 10, "this host must scale Dynamic Type")
    #expect(stack(.accessibility5) == stack(.accessibility2), "the notice's text must stop at its cap")
}
#endif

#endif
