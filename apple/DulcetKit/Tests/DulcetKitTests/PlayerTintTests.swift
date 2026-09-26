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
// Only the renders run on the main actor, and the tests yield between them. Each render is
// short: 0.2 to 0.6 s for the whole player in a full run, up to about 1 s when it is the first
// thing the process draws, and a few milliseconds for the cover alone (each prints its own
// figure). The pixel scans that read the
// renders take seconds over millions of pixels in a debug build, so they run off the main actor.
// A scan held on it starves every other main-actor test in a parallel `swift test` run until
// those tests' own deadlines expire.
//
// Off the main actor a scan still takes a thread of the pool every other test's tasks run on,
// for seconds, and it is work no test is waiting on a deadline for. So the three tests run one
// at a time, and their scans run below the priority of the tasks around them.
@Suite(.serialized)
struct PlayerTintRendering {}

/// A render redrawn once into 8-bit sRGB, so every pixel is read from one buffer in one colour
/// space rather than converted one `NSColor` at a time.
private struct RenderedPixels: Sendable {
    let width: Int
    let height: Int
    /// Pixels per point.
    let scale: CGFloat
    /// RGBA, premultiplied, row 0 at the top of the render.
    fileprivate let bytes: [UInt8]

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
}

/// The largest difference in any one colour channel, each channel as a fraction of full scale.
/// It is computed exactly as it always was -- each channel divided by 255 before subtracting --
/// so a pixel on a threshold is counted as before, and the counts the tests print are unchanged.
private func channelDelta(_ lhs: UnsafeBufferPointer<UInt8>, _ lhsOffset: Int,
                          _ rhs: UnsafeBufferPointer<UInt8>, _ rhsOffset: Int) -> Double {
    func channel(_ bytes: UnsafeBufferPointer<UInt8>, _ offset: Int) -> Double { Double(bytes[offset]) / 255 }
    return max(abs(channel(lhs, lhsOffset) - channel(rhs, rhsOffset)),
               abs(channel(lhs, lhsOffset + 1) - channel(rhs, rhsOffset + 1)),
               abs(channel(lhs, lhsOffset + 2) - channel(rhs, rhsOffset + 2)))
}

/// Whether ``channelDelta`` exceeds `threshold`, deciding most pixels from the integer difference.
/// The exact value is within a rounding error of the integer difference over 255, so a pixel more
/// than half a level from the threshold is decided by the integer alone. Only a pixel that close
/// pays for the exact computation, and the answer is always the one ``channelDelta`` gives.
private func differs(_ lhs: UnsafeBufferPointer<UInt8>, _ lhsOffset: Int,
                     _ rhs: UnsafeBufferPointer<UInt8>, _ rhsOffset: Int, by threshold: Double) -> Bool {
    let levels = max(abs(Int(lhs[lhsOffset]) - Int(rhs[rhsOffset])),
                     abs(Int(lhs[lhsOffset + 1]) - Int(rhs[rhsOffset + 1])),
                     abs(Int(lhs[lhsOffset + 2]) - Int(rhs[rhsOffset + 2])))
    let scaled = threshold * 255
    if Double(levels) + 0.5 < scaled { return false }
    if Double(levels) - 0.5 > scaled { return true }
    return channelDelta(lhs, lhsOffset, rhs, rhsOffset) > threshold
}

/// Distance in points from a pixel to a rectangle given in points; zero inside it.
private func distance(fromPixel x: Int, _ y: Int, to rect: CGRect, scale: CGFloat) -> CGFloat {
    let px = (CGFloat(x) + 0.5) / scale
    let py = (CGFloat(y) + 0.5) / scale
    let dx = max(rect.minX - px, 0, px - rect.maxX)
    let dy = max(rect.minY - py, 0, py - rect.maxY)
    return (dx * dx + dy * dy).squareRoot()
}

/// Runs one render on the main actor and prints how long it held it, so the budget above is
/// visible in every run.
@MainActor
private func timedRender(_ label: String, _ render: () throws -> RenderedPixels) rethrows -> RenderedPixels {
    let start = ContinuousClock.now
    let pixels = try render()
    print("DULCET TINT RENDER \(label) main-actor=\(ContinuousClock.now - start)")
    return pixels
}

/// Runs a pixel scan off the main actor, below the priority of the tests around it, and waits
/// for it there.
private func scannedOffTheMainActor<T: Sendable>(_ scan: @escaping @Sendable () -> T) async -> T {
    await Task.detached(priority: .utility) { scan() }.value
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

/// What the glow test counts, outside the cover and its one-point edge stroke.
private struct GlowScan: Sendable {
    /// Pixels past the reach that differ from the window: cover colour where none may be.
    var beyondReach = 0
    /// Pixels within the reach where the glow and Reduce Transparency renders differ.
    var glowInRing = 0
    /// Pixels of the Reduce Transparency render that differ from the window.
    var reducedOutsideCover = 0
}

private func scanGlow(glowing: RenderedPixels, reduced: RenderedPixels, cover: CGRect, reach: CGFloat) -> GlowScan {
    var scan = GlowScan()
    glowing.bytes.withUnsafeBufferPointer { lit in
        reduced.bytes.withUnsafeBufferPointer { plain in
            let window = (1 * reduced.width + 1) * 4
            for y in 0..<glowing.height {
                for x in 0..<glowing.width {
                    let offset = (y * glowing.width + x) * 4
                    let glowLit = differs(lit, offset, plain, window, by: 2.0 / 255)
                    let glowDiffers = differs(lit, offset, plain, offset, by: 4.0 / 255)
                    let reducedLit = differs(plain, offset, plain, window, by: 2.0 / 255)
                    guard glowLit || glowDiffers || reducedLit else { continue }
                    let fromCover = distance(fromPixel: x, y, to: cover, scale: glowing.scale)
                    guard fromCover > 1 else { continue } // the cover and its one-point edge stroke
                    if fromCover > reach, glowLit { scan.beyondReach += 1 }
                    if fromCover <= reach, glowDiffers { scan.glowInRing += 1 }
                    if reducedLit { scan.reducedOutsideCover += 1 }
                }
            }
        }
    }
    return scan
}

extension PlayerTintRendering {
    /// Cover colour reaches no further than `reach` past the cover, the glow is really drawn inside
    /// that (so the confinement is not satisfied by an absent glow), and Reduce Transparency removes it.
    @Test @MainActor
    func theGlowStaysWithinItsReachAndReduceTransparencyRemovesIt() async throws {
        let reach = DulcetArtworkGlow.reach
        let margin = reach + 16
        for size in [CGFloat(120), 360, 520] {
            let cover = CGRect(x: margin, y: margin, width: size, height: size)
            let glowing = try timedRender("glow \(size)") {
                try renderGlow(size: size, margin: margin, reduceTransparency: false, palette: .emberRose)
            }
            await Task.yield()
            let reduced = try timedRender("glow \(size) reduce-transparency") {
                try renderGlow(size: size, margin: margin, reduceTransparency: true, palette: .emberRose)
            }
            let scan = await scannedOffTheMainActor {
                scanGlow(glowing: glowing, reduced: reduced, cover: cover, reach: reach)
            }
            print("DULCET TINT GLOW size=\(size) reach=\(reach) beyond-reach=\(scan.beyondReach)"
                + " glow-in-ring=\(scan.glowInRing) reduce-transparency-outside-cover=\(scan.reducedOutsideCover)")
            #expect(scan.beyondReach == 0, "cover colour \(scan.beyondReach) px past the reach at size \(size)")
            #expect(scan.glowInRing > 0, "the glow must be drawn within its reach at size \(size)")
            #expect(scan.reducedOutsideCover == 0, "Reduce Transparency left \(scan.reducedOutsideCover) px of glow at size \(size)")
        }
    }
}

/// The cover as the player composes it (``DulcetPlayerCover``): artwork, shadow and glow, at a
/// given scale, through SwiftUI's own renderer.
@MainActor
private func renderCover(size: CGFloat, margin: CGFloat, scale: CGFloat, reduceTransparency: Bool) throws -> RenderedPixels {
    let artwork = DulcetArtwork(seed: "tint", palette: .emberRose)
    let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .nowPlaying))
    let canvas = size + 2 * margin
    let content = ZStack {
        Color.dulcetWindow
        DulcetPlayerCover(artwork: artwork, size: size, isPlaying: scale == 1, scale: scale)
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

/// Where the glow of a composed cover lies, outside the drawn cover and its edge stroke.
private struct CoverGlowScan: Sendable {
    var beyondReach = 0
    var glowNear = 0
    var farthest: CGFloat = 0
}

private func scanCoverGlow(glowing: RenderedPixels, reduced: RenderedPixels, cover: CGRect, reach: CGFloat) -> CoverGlowScan {
    var scan = CoverGlowScan()
    glowing.bytes.withUnsafeBufferPointer { lit in
        reduced.bytes.withUnsafeBufferPointer { plain in
            for y in 0..<glowing.height {
                for x in 0..<glowing.width {
                    let offset = (y * glowing.width + x) * 4
                    // The glow is what differs between the two renders.
                    guard differs(lit, offset, plain, offset, by: 2.0 / 255) else { continue }
                    let fromCover = distance(fromPixel: x, y, to: cover, scale: glowing.scale)
                    guard fromCover > 1 else { continue }
                    scan.farthest = max(scan.farthest, fromCover)
                    if fromCover > reach { scan.beyondReach += 1 } else { scan.glowNear += 1 }
                }
            }
        }
    }
    return scan
}

/// The share of the playing glow's area the paused glow may cover; see below.
private let pausedGlowAreaShare = 0.72...0.85

extension PlayerTintRendering {
    /// A presented player's cover shrinks while paused, and its glow shrinks with it: the glow reaches
    /// no further than `reach` × the cover's scale past the cover as drawn -- 12 points playing, 10.8
    /// paused, the figure the spec states -- so paused it ends further inside the cover's layout frame
    /// than it does playing, and every gap to text measured above still holds.
    ///
    /// That bound alone does not catch a glow sized to the full reach around the drawn cover: the
    /// glow fades out before its clip, so drawn at 12 around a paused cover it is measured at 10.75,
    /// inside 10.8. Nor, with a real margin, does its farthest pixel: the playing glow's farthest,
    /// 11.25 × 0.9, plus one pixel, is 10.625, and the regression reaches 10.75 -- a quarter of a
    /// pixel, which a renderer that feathers the edge a little differently would erase. So the paused
    /// glow is held to its AREA instead. A glow that shrinks with the cover covers about 0.9² = 0.81
    /// of the playing glow's pixels (measured 0.81 at 120 points and 0.80 at 360); the regression,
    /// whose blur and fade keep their playing size, covers about 0.9 (measured 0.89 and 0.90). The
    /// check allows 0.85, some 5% from each. It is bounded below as well, at 0.72: a glow shrunk twice
    /// -- the scale applied to the glow and again to the cover it surrounds -- covers about 0.81² =
    /// 0.66, and would pass an upper bound alone as well as the reach bound.
    /// The glow is isolated as the difference between the render with it and the render under Reduce
    /// Transparency, which share everything else, the shadow included.
    @Test @MainActor
    func thePausedCoversGlowShrinksWithTheCover() async throws {
        let reach = DulcetArtworkGlow.reach
        let margin = reach + 40
        for size in [CGFloat(120), 360] {
            var playingArea: Int?
            for scale in [CGFloat(1), DulcetPlayerCover.pausedScale] {
                let glowing = try timedRender("cover \(size)x\(scale)") {
                    try renderCover(size: size, margin: margin, scale: scale, reduceTransparency: false)
                }
                await Task.yield()
                let reduced = try timedRender("cover \(size)x\(scale) reduce-transparency") {
                    try renderCover(size: size, margin: margin, scale: scale, reduceTransparency: true)
                }
                let drawn = size * scale
                let inset = (size - drawn) / 2
                let cover = CGRect(x: margin + inset, y: margin + inset, width: drawn, height: drawn)
                // The glow scales with the cover it surrounds: its bound is the reach at that scale.
                let bound = reach * scale
                let scan = await scannedOffTheMainActor {
                    scanCoverGlow(glowing: glowing, reduced: reduced, cover: cover, reach: bound)
                }
                print("DULCET TINT COVER size=\(size) scale=\(scale) reach=\(reach) bound=\(bound) farthest-glow=\(scan.farthest)"
                    + " beyond-bound=\(scan.beyondReach) glow-near=\(scan.glowNear)")
                #expect(scan.beyondReach == 0, "glow \(scan.beyondReach) px past \(bound) pt from the drawn cover, size \(size) scale \(scale)")
                #expect(scan.glowNear > 0, "the glow must be drawn at all, size \(size) scale \(scale)")
                if scale == 1 {
                    playingArea = scan.glowNear
                } else {
                    let playing = try #require(playingArea, "the playing render comes first")
                    let ratio = Double(scan.glowNear) / Double(playing)
                    print("DULCET TINT COVER size=\(size) paused-to-playing-glow-area=\(ratio)")
                    #expect(pausedGlowAreaShare.contains(ratio),
                            "paused glow covers \(ratio) of the playing glow's area, outside \(pausedGlowAreaShare); size \(size)")
                }
            }
        }
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

/// The bounds, in points, of the pixels that differ between two renders with different cover
/// palettes -- the cover -- or nil when nothing differs.
private func scanCoverBounds(_ first: RenderedPixels, _ second: RenderedPixels) -> CGRect? {
    var minX = Int.max, minY = Int.max, maxX = Int.min, maxY = Int.min
    first.bytes.withUnsafeBufferPointer { one in
        second.bytes.withUnsafeBufferPointer { two in
            for y in 0..<first.height {
                for x in 0..<first.width {
                    let offset = (y * first.width + x) * 4
                    guard differs(one, offset, two, offset, by: 0.02) else { continue }
                    minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                }
            }
        }
    }
    guard maxX >= minX else { return nil }
    let scale = first.scale
    return CGRect(
        x: CGFloat(minX) / scale, y: CGFloat(minY) / scale,
        width: CGFloat(maxX - minX + 1) / scale, height: CGFloat(maxY - minY + 1) / scale
    )
}

/// The nearest pixel drawn on the player -- clearly not window colour -- outside the cover and its
/// one-point edge stroke: anywhere, directly below the cover, and directly beside it.
private struct NearestDrawn: Sendable {
    var nearest = CGFloat.infinity
    var below = CGFloat.infinity
    var beside = CGFloat.infinity
}

private func scanNearestDrawn(_ render: RenderedPixels, cover: CGRect) -> NearestDrawn {
    var found = NearestDrawn()
    let scale = render.scale
    render.bytes.withUnsafeBufferPointer { pixels in
        let window = (1 * render.width + 1) * 4
        for y in 0..<render.height {
            for x in 0..<render.width {
                let offset = (y * render.width + x) * 4
                guard differs(pixels, offset, pixels, window, by: 0.15) else { continue }
                let fromCover = distance(fromPixel: x, y, to: cover, scale: scale)
                guard fromCover > 1 else { continue } // the cover and its one-point edge stroke
                found.nearest = min(found.nearest, fromCover)
                let px = (CGFloat(x) + 0.5) / scale, py = (CGFloat(y) + 0.5) / scale
                if py > cover.maxY, px >= cover.minX, px <= cover.maxX { found.below = min(found.below, fromCover) }
                if px > cover.maxX, py >= cover.minY, py <= cover.maxY { found.beside = min(found.beside, fromCover) }
            }
        }
    }
    return found
}

extension PlayerTintRendering {
    /// Nothing on the real player -- no text, no control -- is drawn within the glow's reach of the
    /// cover, in the one-column and the side-by-side layouts. The cover is found as the pixels that
    /// change with its palette, and its size is checked against the layout, so a render that lost
    /// the cover cannot pass by finding nothing near it.
    @Test @MainActor
    func nothingOnThePlayerIsDrawnWithinTheGlowsReachOfTheCover() async throws {
        let reach = DulcetArtworkGlow.reach
        let layouts: [(name: String, size: CGSize, cover: CGFloat)] = [
            ("one-column", CGSize(width: 430, height: 900), 360),
            ("side-by-side", CGSize(width: 1_180, height: 820),
             DulcetNowPlayingView.sideBySideArtworkSize(height: 820)),
        ]
        var observedPairs: Set<DulcetRegisteredContrastPair> = []
        for layout in layouts {
            let first = try timedRender("player \(layout.name)") {
                try renderPlayer(palette: .emberRose, size: layout.size, observedPairs: &observedPairs)
            }
            await Task.yield()
            let second = try timedRender("player \(layout.name) second palette") {
                try renderPlayer(palette: .oceanMint, size: layout.size, observedPairs: &observedPairs)
            }
            let found = await scannedOffTheMainActor { scanCoverBounds(first, second) }
            let cover = try #require(found, "\(layout.name): the cover must be drawn")
            // The experiment is the one intended: the cover found is the size the layout gives it.
            #expect(abs(cover.width - layout.cover) <= 1 && abs(cover.height - layout.cover) <= 1,
                    "\(layout.name): found a \(cover.size) cover, expected \(layout.cover)")

            let drawn = await scannedOffTheMainActor { scanNearestDrawn(first, cover: cover) }
            print("DULCET TINT LAYOUT layout=\(layout.name) cover=\(cover) reach=\(reach)"
                + " nearest-drawn=\(drawn.nearest) below=\(drawn.below) beside=\(drawn.beside)")
            #expect(drawn.nearest > reach, "\(layout.name): something is drawn \(drawn.nearest) pt from the cover, within its \(reach) pt glow")
            // The instrument sees text at all: the title sits one spacing step under the cover.
            #expect(drawn.below <= DulcetNowPlayingView.coverToTitleSpacing + 16,
                    "\(layout.name): the title must be found under the cover; nearest below is \(drawn.below)")
        }
        // Every pair the player registered is measured against the window (and the fills drawn on
        // it), never against artwork: the registry has no pair for text on cover colour.
        print("DULCET TINT REGISTERED PAIRS \(observedPairs.map(\.rawValue).sorted())")
        #expect(observedPairs.contains(.primaryTextOnWindow), "the player's title must register its pair")
    }
}
#endif
