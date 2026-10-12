#if os(macOS) || os(iOS)
import SwiftUI
import Testing
@testable import DulcetKit
#if os(macOS)
import AppKit
#else
import UIKit
#endif

// Heights are a sheet's content area under its toolbar and above the home indicator: an iPhone
// 17 Pro's (402 x 707, observed in the design tour, rounded down) and an iPhone SE's.
private let phone = CGSize(width: 402, height: 700)
private let smallPhone = CGSize(width: 375, height: 560)

/// On a phone at the standard text sizes the cover spans the column -- the same leading and
/// trailing edges as the title and scrubber -- and the controls fit beneath it without
/// scrolling: the gaps between the controls are fixed and tight, so the cover never has to
/// give up width for them. (Before, shrinking the cover was the only lever, and it fell to
/// two-thirds of the column with the controls' gaps left wide.)
@Test(arguments: [DynamicTypeSize.large, .xxLarge])
@MainActor
func onAPhoneTheCoverSpansTheColumnAndTheControlsStillFit(textSize: DynamicTypeSize) async throws {
    let layout = try await laidOutPlayer(
        DulcetNowPlayingView(player: fitPlayer(titled: "Twenty Nine Seconds"), presentation: .sheet),
        size: phone,
        textSize: textSize
    )
    #expect(abs(layout.cover.width - layout.column.width) <= 1,
            "\(textSize): cover \(layout.cover.width) wide in a \(layout.column.width) column")
    #expect(abs(layout.cover.minX - layout.column.minX) <= 1, "\(textSize): cover and title share a leading edge")
    #expect(layout.footer.maxY <= phone.height, "\(textSize): footer ends at \(layout.footer.maxY)")
}

/// Where the controls genuinely cannot fit under a full-width cover -- accessibility text, a
/// small phone at large text -- the cover gives up size before any control is pushed below the
/// window's edge, and stays centred in the column.
@Test(arguments: [
    (DynamicTypeSize.large, smallPhone),
    (DynamicTypeSize.xxxLarge, CGSize(width: 375, height: 620)),
    (DynamicTypeSize.accessibility1, phone),
    (DynamicTypeSize.accessibility3, phone),
])
@MainActor
func theOneColumnPlayerFitsItsWindowCoverToFooter(textSize: DynamicTypeSize, size: CGSize) async throws {
    let layout = try await laidOutPlayer(
        DulcetNowPlayingView(player: fitPlayer(), presentation: .sheet), size: size, textSize: textSize
    )
    #expect(layout.footer.maxY <= size.height, "\(textSize) at \(size): footer ends at \(layout.footer.maxY)")
    #expect(abs(layout.cover.midX - layout.column.midX) <= 1,
            "\(textSize) at \(size): cover centred at \(layout.cover.midX), column at \(layout.column.midX)")
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
    let layout = try await laidOutPlayer(DulcetNowPlayingView(player: fitPlayer()), size: size, textSize: .large)
    #expect(layout.footer.maxY <= size.height, "\(size): footer ends at \(layout.footer.maxY)")
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
private func fitPlayer(titled title: String? = nil) -> DulcetNowPlaying {
    let entries = ["Before", "Current", "After"].enumerated().map { offset, name in
        DulcetQueueEntry(id: .init("fit-\(offset)"), track: DulcetTrack(
            id: .init(providerInstanceID: "server", rawID: name),
            title: offset == 1 ? title ?? "\(name) With A Title Long Enough To Wrap"
                : "\(name) With A Title Long Enough To Wrap",
            credits: [DulcetCredit(role: .artist, name: "Fit Artist", id: nil)],
            albumTitle: "Fit Album",
            duration: .seconds(180),
            mediaSourceID: nil,
            artwork: DulcetArtwork(seed: name, palette: .oceanMint)
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

private struct PlayerLayout {
    var cover: CGRect
    var column: CGRect
    var footer: CGRect
}

/// Where the player's cover, title column and footer are, in points from the top of a window of
/// `size`, once the view has laid out and measured itself -- through the platform's own hosting
/// view, so on iOS with the system's fonts and volume slider.
@MainActor
private func laidOutPlayer(
    _ view: DulcetNowPlayingView,
    size: CGSize,
    textSize: DynamicTypeSize
) async throws -> PlayerLayout {
    let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .nowPlaying))
    let probe = LayoutProbe()
    let root = view
        .environment(store)
        .environment(\.dynamicTypeSize, textSize)
        .frame(width: size.width, height: size.height)
        .overlayPreferenceValue(DulcetPlayerLayoutKey.self) { anchors in
            GeometryReader { proxy in
                let rects = anchors.mapValues { proxy[$0] }
                Color.clear.onAppear { probe.rects = rects }
                    .onChange(of: rects) { _, value in probe.rects = value }
            }
        }
#if os(macOS)
    let hostingView = NSHostingView(rootView: root)
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
#else
    let controller = UIHostingController(rootView: root)
    let window = UIWindow(frame: CGRect(origin: .zero, size: size))
    window.rootViewController = controller
    window.makeKeyAndVisible()
    defer { window.isHidden = true }
    for _ in 0..<10 {
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        try? await Task.sleep(for: .milliseconds(20))
    }
#endif
    let rects = probe.rects
    return PlayerLayout(
        cover: try #require(rects[.cover], "the cover reported where it is"),
        column: try #require(rects[.column], "the title column reported where it is"),
        footer: try #require(rects[.footer], "the footer reported where it is")
    )
}

@MainActor
private final class LayoutProbe {
    var rects: [DulcetPlayerLayoutPart: CGRect] = [:]
}
#endif
