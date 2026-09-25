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
    var first = Int.max, last = Int.min
    for y in 0..<height {
        for x in 0..<width where alpha[y * width + x] > 0 {
            first = min(first, x); last = max(last, x)
        }
    }
    return first <= last ? first...last : nil
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
#endif

#endif
