import SwiftUI
import Testing
@testable import DulcetKit
#if os(macOS)
import AppKit
#endif

// The player's tint (spec §3.1): the cover's own colours glow out from behind it, reaching
// `DulcetArtworkGlow.reach` past its edge and no further.
// Every text colour on the player is a registered contrast pair (`DulcetRegisteredContrastPair`)
// measured against the window colour and nothing else -- the registry has no pair measured against
// artwork -- so cover colour under any of that text would void its measurement. These tests show
// the glow stays within `DulcetArtworkGlow.reach` of the cover, that nothing on the player is drawn
// that close to it, and that Reduce Transparency removes it.

#if !os(tvOS)
/// The layout half, stated as numbers: every gap between the cover and the nearest text is wider
/// than the glow's reach.
@Test
func theGlowReachesLessFarThanEveryGapBetweenTheCoverAndText() {
    let gaps: [(String, CGFloat)] = [
        ("cover to title", DulcetNowPlayingView.coverToTitleSpacing),
        ("player to queue column", DulcetNowPlayingView.playerToQueueSpacing),
        ("sheet top", DulcetNowPlayingView.sheetVerticalPadding),
        ("narrow side padding", DulcetNowPlayingView.minimumHorizontalPadding),
    ]
    for (name, gap) in gaps {
        #expect(DulcetArtworkGlow.reach < gap, "the glow reaches \(DulcetArtworkGlow.reach) but \(name) is \(gap)")
    }
}
#endif

#if os(tvOS)
/// tvOS draws no glow: its player is the one it had before the tint.
@Test @MainActor
func theTVOSPlayerDrawsNoGlow() {
    let glow = DulcetArtworkGlow(artwork: DulcetArtwork(seed: "tv", palette: .emberRose), size: 400)
    #expect(glow.body is EmptyView, "tvOS glow body is \(type(of: glow.body))")
}
#endif

#if os(macOS)
/// A render redrawn once into 8-bit sRGB, so every pixel is read from one buffer in one colour
/// space rather than converted one `NSColor` at a time.
private struct RenderedPixels {
    let width: Int
    let height: Int
    /// Pixels per point.
    let scale: CGFloat
    private let bytes: [UInt8]

    init(image: CGImage, scale: CGFloat) throws {
        let width = image.width
        let height = image.height
        var buffer = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = buffer.withUnsafeMutableBytes { raw -> Bool in
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(
                      data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
                      bytesPerRow: width * 4, space: space,
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                  ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        self.width = width
        self.height = height
        self.scale = scale
        bytes = buffer
        try #require(drawn, "the render must redraw into sRGB")
    }

    /// Row 0 is the top of the render.
    func rgb(_ x: Int, _ y: Int) -> SIMD3<Double> {
        let offset = (y * width + x) * 4
        return SIMD3(Double(bytes[offset]), Double(bytes[offset + 1]), Double(bytes[offset + 2])) / 255
    }
}

private func channelDelta(_ lhs: SIMD3<Double>, _ rhs: SIMD3<Double>) -> Double {
    max(abs(lhs.x - rhs.x), abs(lhs.y - rhs.y), abs(lhs.z - rhs.z))
}

/// Distance in points from a pixel to a rectangle given in points; zero inside it.
private func distance(fromPixel x: Int, _ y: Int, to rect: CGRect, scale: CGFloat) -> CGFloat {
    let px = (CGFloat(x) + 0.5) / scale
    let py = (CGFloat(y) + 0.5) / scale
    let dx = max(rect.minX - px, 0, px - rect.maxX)
    let dy = max(rect.minY - py, 0, py - rect.maxY)
    return (dx * dx + dy * dy).squareRoot()
}

/// The cover and its glow alone, through SwiftUI's own renderer: it draws the blur and the mask
/// as the screen does, where an AppKit snapshot draws neither.
@MainActor
private func renderGlow(size: CGFloat, margin: CGFloat, reduceTransparency: Bool?, palette: DulcetArtworkPalette) throws -> RenderedPixels {
    let artwork = DulcetArtwork(seed: "tint", palette: palette)
    let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .nowPlaying))
    let canvas = size + 2 * margin
    let content = ZStack {
        Color.dulcetWindow
        DulcetArtworkView(artwork: artwork, size: size)
            .background { DulcetArtworkGlow(artwork: artwork, size: size) }
    }
    .frame(width: canvas, height: canvas)
    .environment(store)
    .environment(\.colorScheme, .light)
    .environment(\.dulcetReduceTransparencyOverride, reduceTransparency)
    let renderer = ImageRenderer(content: content)
    renderer.scale = 2
    let image = try #require(renderer.cgImage, "the renderer must produce an image")
    return try RenderedPixels(image: image, scale: 2)
}

/// Cover colour reaches no further than `reach` past the cover, the glow is really drawn inside
/// that (so the confinement is not satisfied by an absent glow), and Reduce Transparency removes it.
@Test @MainActor
func theGlowStaysWithinItsReachAndReduceTransparencyRemovesIt() throws {
    let reach = DulcetArtworkGlow.reach
    let margin = reach + 16
    for size in [CGFloat(120), 360, 520] {
        let cover = CGRect(x: margin, y: margin, width: size, height: size)
        let glowing = try renderGlow(size: size, margin: margin, reduceTransparency: false, palette: .emberRose)
        let reduced = try renderGlow(size: size, margin: margin, reduceTransparency: true, palette: .emberRose)
        let window = reduced.rgb(1, 1)

        var beyondReach = 0
        var glowInRing = 0
        var reducedOutsideCover = 0
        for y in 0..<glowing.height {
            for x in 0..<glowing.width {
                let fromCover = distance(fromPixel: x, y, to: cover, scale: glowing.scale)
                guard fromCover > 1 else { continue } // the cover and its one-point edge stroke
                let lit = channelDelta(glowing.rgb(x, y), window) > 2.0 / 255
                if fromCover > reach, lit { beyondReach += 1 }
                if fromCover <= reach, channelDelta(glowing.rgb(x, y), reduced.rgb(x, y)) > 4.0 / 255 {
                    glowInRing += 1
                }
                if channelDelta(reduced.rgb(x, y), window) > 2.0 / 255 { reducedOutsideCover += 1 }
            }
        }
        print("DULCET TINT GLOW size=\(size) reach=\(reach) beyond-reach=\(beyondReach)"
            + " glow-in-ring=\(glowInRing) reduce-transparency-outside-cover=\(reducedOutsideCover)")
        #expect(beyondReach == 0, "cover colour \(beyondReach) px past the reach at size \(size)")
        #expect(glowInRing > 0, "the glow must be drawn within its reach at size \(size)")
        #expect(reducedOutsideCover == 0, "Reduce Transparency left \(reducedOutsideCover) px of glow at size \(size)")
    }
}

private func tintTrack(_ title: String, palette: DulcetArtworkPalette) -> DulcetTrack {
    DulcetTrack(
        id: .init(providerInstanceID: "server", rawID: title),
        title: title,
        credits: [DulcetCredit(role: .artist, name: "Tint Artist", id: nil)],
        albumTitle: "Tint Album",
        duration: .seconds(180),
        mediaSourceID: nil,
        artwork: DulcetArtwork(seed: title, palette: palette)
    )
}

/// The real player, drawn by AppKit in the dark appearance, where the cover's shadow is all but
/// invisible and text is light on a dark window, so "anything but window colour" is the cover or
/// something drawn on the player.
@MainActor
private func renderPlayer(
    palette: DulcetArtworkPalette,
    size: CGSize,
    observedPairs: inout Set<DulcetRegisteredContrastPair>
) throws -> RenderedPixels {
    let entries = ["Before", "Current", "After", "Later"].enumerated().map { offset, title in
        DulcetQueueEntry(id: .init("tint-\(offset)"), track: tintTrack(title, palette: palette))
    }
    let player = DulcetNowPlaying(
        current: entries[1].track,
        queue: entries.map(\.track),
        currentIndex: 1,
        sourceDisplayName: "Tint Album",
        elapsed: .seconds(40),
        isPlaying: true,
        outputName: "This Mac",
        volume: 1,
        audioFormat: .init(codec: "MP3", sampleRateKilohertz: 44.1),
        queueEntries: entries,
        currentEntryIndex: 1
    )
    let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .nowPlaying))
    var pairs: Set<DulcetRegisteredContrastPair> = []
    let view = NSHostingView(rootView: DulcetNowPlayingView(player: player)
        .environment(store)
        .environment(\.colorScheme, .dark)
        // Only the cover here: AppKit's snapshot does not draw the glow's blur, so the glow is
        // measured by the renderer test above, and this one measures the layout around the cover.
        .environment(\.dulcetReduceTransparencyOverride, true)
        .onDulcetRegisteredContrastPairs { pairs.formUnion($0) })
    view.frame = NSRect(origin: .zero, size: size)
    view.appearance = NSAppearance(named: .darkAqua)
    view.layoutSubtreeIfNeeded()
    view.displayIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.05))
    view.layoutSubtreeIfNeeded()
    view.displayIfNeeded()
    let bitmap = try #require(view.bitmapImageRepForCachingDisplay(in: view.bounds))
    view.cacheDisplay(in: view.bounds, to: bitmap)
    observedPairs.formUnion(pairs)
    let image = try #require(bitmap.cgImage, "the snapshot must produce an image")
    return try RenderedPixels(image: image, scale: CGFloat(bitmap.pixelsWide) / size.width)
}

/// Nothing on the real player -- no text, no control -- is drawn within the glow's reach of the
/// cover, in the one-column and the side-by-side layouts. The cover is found as the pixels that
/// change with its palette, and its size is checked against the layout, so a render that lost
/// the cover cannot pass by finding nothing near it.
@Test @MainActor
func nothingOnThePlayerIsDrawnWithinTheGlowsReachOfTheCover() throws {
    let reach = DulcetArtworkGlow.reach
    let layouts: [(name: String, size: CGSize, cover: CGFloat)] = [
        ("one-column", CGSize(width: 430, height: 900), 360),
        ("side-by-side", CGSize(width: 1_180, height: 820),
         DulcetNowPlayingView.sideBySideArtworkSize(height: 820)),
    ]
    var observedPairs: Set<DulcetRegisteredContrastPair> = []
    for layout in layouts {
        let first = try renderPlayer(palette: .emberRose, size: layout.size, observedPairs: &observedPairs)
        let second = try renderPlayer(palette: .oceanMint, size: layout.size, observedPairs: &observedPairs)
        var minX = Int.max, minY = Int.max, maxX = Int.min, maxY = Int.min
        for y in 0..<first.height {
            for x in 0..<first.width where channelDelta(first.rgb(x, y), second.rgb(x, y)) > 0.02 {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        try #require(maxX >= minX, "\(layout.name): the cover must be drawn")
        let scale = first.scale
        let cover = CGRect(
            x: CGFloat(minX) / scale, y: CGFloat(minY) / scale,
            width: CGFloat(maxX - minX + 1) / scale, height: CGFloat(maxY - minY + 1) / scale
        )
        // The experiment is the one intended: the cover found is the size the layout gives it.
        #expect(abs(cover.width - layout.cover) <= 1 && abs(cover.height - layout.cover) <= 1,
                "\(layout.name): found a \(cover.size) cover, expected \(layout.cover)")

        let window = first.rgb(1, 1)
        var nearest = CGFloat.infinity
        var nearestBelow = CGFloat.infinity
        var nearestBeside = CGFloat.infinity
        for y in 0..<first.height {
            for x in 0..<first.width where channelDelta(first.rgb(x, y), window) > 0.15 {
                let fromCover = distance(fromPixel: x, y, to: cover, scale: scale)
                guard fromCover > 1 else { continue } // the cover and its one-point edge stroke
                nearest = min(nearest, fromCover)
                let px = (CGFloat(x) + 0.5) / scale, py = (CGFloat(y) + 0.5) / scale
                if py > cover.maxY, px >= cover.minX, px <= cover.maxX { nearestBelow = min(nearestBelow, fromCover) }
                if px > cover.maxX, py >= cover.minY, py <= cover.maxY { nearestBeside = min(nearestBeside, fromCover) }
            }
        }
        print("DULCET TINT LAYOUT layout=\(layout.name) cover=\(cover) reach=\(reach)"
            + " nearest-drawn=\(nearest) below=\(nearestBelow) beside=\(nearestBeside)")
        #expect(nearest > reach, "\(layout.name): something is drawn \(nearest) pt from the cover, within its \(reach) pt glow")
        // The instrument sees text at all: the title sits one spacing step under the cover.
        #expect(nearestBelow <= DulcetNowPlayingView.coverToTitleSpacing + 16,
                "\(layout.name): the title must be found under the cover; nearest below is \(nearestBelow)")
    }
    // Every pair the player registered is measured against the window (and the fills drawn on
    // it), never against artwork: the registry has no pair for text on cover colour.
    print("DULCET TINT REGISTERED PAIRS \(observedPairs.map(\.rawValue).sorted())")
    #expect(observedPairs.contains(.primaryTextOnWindow), "the player's title must register its pair")
}
#endif
