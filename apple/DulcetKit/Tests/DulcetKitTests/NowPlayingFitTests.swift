#if os(macOS)
import AppKit
import SwiftUI
import Testing
@testable import DulcetKit

/// The one-column player fits its window, cover to footer, without scrolling: the cover gives up
/// size before a control is pushed below the window's edge. At an iPhone's sheet height and the
/// larger text sizes, a width-sized cover left the footer's row -- lyrics, Up Next -- clipped
/// off the bottom.
// Heights are a sheet's content area under its toolbar: an iPhone 17 Pro's and an iPhone SE's.
@Test(arguments: [
    (DynamicTypeSize.large, CGSize(width: 402, height: 700)),
    (DynamicTypeSize.large, CGSize(width: 375, height: 560)),
    (DynamicTypeSize.xxLarge, CGSize(width: 402, height: 700)),
    (DynamicTypeSize.xxxLarge, CGSize(width: 375, height: 620)),
    (DynamicTypeSize.accessibility1, CGSize(width: 402, height: 700)),
])
@MainActor
func theOneColumnPlayerFitsItsWindowCoverToFooter(textSize: DynamicTypeSize, size: CGSize) async throws {
    let footerBottom = try await laidOutFooterMaxY(
        in: DulcetNowPlayingView(player: fitPlayer(), presentation: .sheet),
        size: size,
        textSize: textSize
    )
    #expect(footerBottom <= size.height, "\(textSize) at \(size): footer ends at \(footerBottom)")
}

/// The side-by-side player (iPad, Mac) fits its height the same way: an iPad mini in landscape
/// gets a smaller cover, not controls below the window.
// An iPad mini in landscape under its bar, an iPad Air, a small Mac window.
@Test(arguments: [
    CGSize(width: 1_133, height: 600),
    CGSize(width: 1_180, height: 700),
    CGSize(width: 960, height: 560),
])
@MainActor
func theSideBySidePlayerFitsItsWindowCoverToFooter(size: CGSize) async throws {
    let footerBottom = try await laidOutFooterMaxY(
        in: DulcetNowPlayingView(player: fitPlayer()),
        size: size,
        textSize: .large
    )
    #expect(footerBottom <= size.height, "\(size): footer ends at \(footerBottom)")
}

@Test
func theFittedCoverTakesWhatTheControlsLeave() {
    let spacing = DulcetNowPlayingView.coverToTitleSpacing
    // Room for the full width-limited cover.
    #expect(DulcetNowPlayingView.fittedArtworkSize(
        widthLimit: 360, availableHeight: 900, chromeHeight: 300, minimum: 120, unmeasured: 360
    ) == 360)
    // Less room: the cover is what is left, to the point.
    #expect(DulcetNowPlayingView.fittedArtworkSize(
        widthLimit: 360, availableHeight: 600, chromeHeight: 300, minimum: 120, unmeasured: 360
    ) == (300 - spacing).rounded(.down))
    // Too little even for the minimum: the minimum, and the player scrolls.
    #expect(DulcetNowPlayingView.fittedArtworkSize(
        widthLimit: 360, availableHeight: 300, chromeHeight: 300, minimum: 120, unmeasured: 360
    ) == 120)
    // Not yet measured.
    #expect(DulcetNowPlayingView.fittedArtworkSize(
        widthLimit: 360, availableHeight: 300, chromeHeight: nil, minimum: 120, unmeasured: 333
    ) == 333)
}

@MainActor
private func fitPlayer() -> DulcetNowPlaying {
    let entries = ["Before", "Current", "After"].enumerated().map { offset, title in
        DulcetQueueEntry(id: .init("fit-\(offset)"), track: DulcetTrack(
            id: .init(providerInstanceID: "server", rawID: title),
            title: "\(title) With A Title Long Enough To Wrap",
            credits: [DulcetCredit(role: .artist, name: "Fit Artist", id: nil)],
            albumTitle: "Fit Album",
            duration: .seconds(180),
            mediaSourceID: nil,
            artwork: DulcetArtwork(seed: title, palette: .oceanMint)
        ))
    }
    return DulcetNowPlaying(
        current: entries[1].track,
        queue: entries.map(\.track),
        currentIndex: 1,
        sourceDisplayName: "Fit Album",
        elapsed: .seconds(40),
        isPlaying: true,
        outputName: "This Mac",
        volume: 1,
        audioFormat: .init(codec: "FLAC", sampleRateKilohertz: 44.1),
        queueEntries: entries,
        currentEntryIndex: 1
    )
}

/// Where the player's footer ends, in points from the top of a window of `size`, once the
/// view has laid out and measured itself.
@MainActor
private func laidOutFooterMaxY(
    in view: DulcetNowPlayingView,
    size: CGSize,
    textSize: DynamicTypeSize
) async throws -> CGFloat {
    let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .nowPlaying))
    let probe = FooterProbe()
    let hostingView = NSHostingView(rootView: view
        .environment(store)
        .environment(\.dynamicTypeSize, textSize)
        .frame(width: size.width, height: size.height)
        .overlayPreferenceValue(DulcetPlayerFooterBoundsKey.self) { anchor in
            GeometryReader { proxy in
                Color.clear.onAppear { probe.maxY = anchor.map { proxy[$0].maxY } }
                    .onChange(of: anchor.map { proxy[$0].maxY }) { _, value in probe.maxY = value }
            }
        })
    let window = NSWindow(
        contentRect: NSRect(origin: .zero, size: size),
        styleMask: [.borderless],
        backing: .buffered,
        defer: false
    )
    window.isReleasedWhenClosed = false
    window.contentView = hostingView
    window.orderFront(nil)
    defer { window.close() }
    for _ in 0..<10 {
        hostingView.layoutSubtreeIfNeeded()
        try? await Task.sleep(for: .milliseconds(20))
    }
    return try #require(probe.maxY, "the footer reported where it is")
}

@MainActor
private final class FooterProbe {
    var maxY: CGFloat?
}
#endif
