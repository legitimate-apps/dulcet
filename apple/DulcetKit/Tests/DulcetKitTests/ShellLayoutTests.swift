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
func aPausedOrFinishedPlayerShowsWhereItIsNotABareWord() {
    // A finished queue presents its last track paused with no progress in this session.
    #expect(DulcetNowPlayingView.showsProgressIndicator(progressBegan: false, phase: .paused))
    #expect(DulcetNowPlayingView.showsProgressIndicator(progressBegan: true, phase: .progressing))
    // Opening and buffering before any progress keep their words.
    #expect(!DulcetNowPlayingView.showsProgressIndicator(progressBegan: false, phase: .buffering))
    #expect(!DulcetNowPlayingView.showsProgressIndicator(progressBegan: false, phase: .ready))
}

#endif
