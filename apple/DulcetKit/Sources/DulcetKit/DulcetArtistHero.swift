#if os(macOS) || os(iOS) || os(tvOS)
import CoreGraphics
import SwiftUI

/// The numbers behind the iPhone artist page's hero, kept apart from the views so each can be
/// tested without drawing anything.
enum DulcetArtistHeroLayout {
    /// The scroll view's coordinate space. The same name as every other detail page, so the
    /// title's `dulcetDetailTitleMarker` reports in it.
    static let scrollSpace = "dulcet.detail.scroll"

    /// The hero's height, status bar included: as tall as the screen is wide, the shape most
    /// artist photos and every cover has, but never more than 55% of the screen, so a phone in
    /// landscape still shows the page beneath it.
    static func heroHeight(width: CGFloat, screenHeight: CGFloat) -> CGFloat {
        max(260, min(width, screenHeight * 0.55))
    }

    /// The navigation bar takes the artist's name only once the name on the hero has scrolled
    /// up under the bar. Both edges are measured from the top of the screen, which is where the
    /// hero's scroll view starts.
    static func titleBelongsInBar(nameBottom: CGFloat, barBottom: CGFloat) -> Bool {
        nameBottom < barBottom
    }

    /// The hero's name and buttons are fully drawn until their top comes within `fadeDistance`
    /// of the bar's bottom edge, then fade out linearly, gone as they reach it.
    static let fadeDistance: CGFloat = 56 // design-token: allow scroll threshold, not layout
    static func identityOpacity(identityTop: CGFloat, barBottom: CGFloat) -> Double {
        Double(min(1, max(0, (identityTop - barBottom) / fadeDistance)))
    }

    /// How dark the scrim behind white text has to be over a picture whose brightest part
    /// under the text has relative luminance `luminance` (0-1).
    ///
    /// White text needs 4.5:1, so what is under it may be at most luminance 0.18
    /// ((1 + 0.05) / (0.18 + 0.05) = 4.57). Black at opacity `a` over the picture leaves at
    /// most `(1 - a) * L` in linear light (less once composited in sRGB), so `a = 1 - 0.18 / L`.
    /// A floor keeps a little depth under the name on a dark picture. On a white picture this
    /// comes to 0.82, which still lets the picture show through above the name.
    static func scrimOpacity(luminance: Double) -> Double {
        let required = luminance <= 0.18 ? 0 : 1 - 0.18 / min(1, luminance)
        return max(0.25, required)
    }

    /// Relative luminance of the brightest cell in each band of an 8 x 8 sample of `image`:
    /// `top` over the top two rows (behind the status bar and back button), `bottom` over the
    /// bottom three (behind the name). Nil when the image cannot be drawn.
    static func bandLuminance(_ image: CGImage) -> (top: Double, bottom: Double)? {
        let side = 8
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: side, height: side,
                bitsPerComponent: 8, bytesPerRow: side * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard drawn else { return nil }
        func linear(_ value: Double) -> Double {
            value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        // A bitmap context's first row in memory is the image's top row.
        func brightest(rows: Range<Int>) -> Double {
            var result = 0.0
            for row in rows {
                for column in 0..<side {
                    let offset = (row * side + column) * 4
                    let rgb = (0..<3).map { linear(Double(pixels[offset + $0]) / 255) }
                    result = max(result, 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2])
                }
            }
            return result
        }
        return (top: brightest(rows: 0..<2), bottom: brightest(rows: (side - 3)..<side))
    }

    /// The album whose cover stands in for an artist with no photo: the newest release, by
    /// original release date and then year, the first listed when none is dated.
    static func newestAlbum(_ albums: [DulcetReaderItem]) -> DulcetReaderItem? {
        let covered = albums.filter { $0.artworkKey != nil }
        guard let first = covered.first else { return nil }
        return covered.reduce(first) { newest, album in
            let lhs = (newest.originalReleaseDate ?? newest.year.map(String.init) ?? "")
            let rhs = (album.originalReleaseDate ?? album.year.map(String.init) ?? "")
            return rhs > lhs ? album : newest
        }
    }
}
#endif

#if os(iOS)
/// The artist page on a compact iPhone width: a hero of the artist's picture edge to edge,
/// under the status bar, with the name and Play over its bottom; it stretches when pulled down
/// and scrolls away under the content, and the navigation bar takes the name once it has.
struct DulcetArtistHeroPage<Hero: View, Content: View>: View {
    let title: String
    /// Given the page width, the hero's height and the bar's bottom edge (status bar plus
    /// navigation bar, measured from the top of the screen).
    @ViewBuilder let hero: (_ width: CGFloat, _ height: CGFloat, _ barBottom: CGFloat) -> Hero
    @ViewBuilder let content: (CGFloat) -> Content
    @State private var titleInBar = false

    var body: some View {
        GeometryReader { geometry in
            let barBottom = geometry.safeAreaInsets.top
            let height = DulcetArtistHeroLayout.heroHeight(
                width: geometry.size.width, screenHeight: geometry.size.height + barBottom
            )
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    hero(geometry.size.width, height, barBottom)
                    VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                        content(geometry.size.width)
                    }
                    .padding(.horizontal, DulcetLibraryMetrics.horizontalInset(forWidth: geometry.size.width))
                    .padding(.vertical, DulcetSpacing.md)
                    // At least a screen of page under the hero, so the hero can always scroll
                    // away and hand the name to the bar, even for an artist with one album.
                    .frame(maxWidth: .infinity, minHeight: geometry.size.height, alignment: .topLeading)
                }
            }
            .modifier(DulcetSystemScrollChrome())
            .coordinateSpace(name: DulcetArtistHeroLayout.scrollSpace)
            // The hero runs under the status bar and the navigation bar, as Music's does.
            .ignoresSafeArea(.container, edges: .top)
            .onPreferenceChange(DulcetDetailTitleBottom.self) { bottom in
                let inBar = DulcetArtistHeroLayout.titleBelongsInBar(nameBottom: bottom, barBottom: barBottom)
                if inBar != titleInBar { titleInBar = inBar }
            }
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .modifier(DulcetArtistHeroBar(title: title, titleInBar: titleInBar))
    }
}

/// The navigation bar over the hero: clear, its controls drawn for a dark picture, while the
/// hero is under it; the page's own opaque bar with the name once the hero has scrolled away.
private struct DulcetArtistHeroBar: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let title: String
    let titleInBar: Bool

    func body(content: Content) -> some View {
        let bar = content
            .navigationTitle(titleInBar ? title : "")
            .toolbarTitleDisplayMode(.inline)
            .toolbarBackground(titleInBar ? Color.dulcetWindow : .clear, for: .navigationBar)
            .toolbarBackground(titleInBar ? .visible : .hidden, for: .navigationBar)
            // White status bar and bar glyphs over the picture; the window's own once it is gone.
            .toolbarColorScheme(titleInBar ? colorScheme : .dark, for: .navigationBar)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: titleInBar)
        if #available(iOS 26.0, *) {
            // The bar's soft edge blurred the top of the picture while the hero was under it.
            bar.scrollEdgeEffectHidden(!titleInBar, for: .top)
        } else {
            bar
        }
    }
}

/// The hero itself: the picture, its scrims, the name and the actions.
struct DulcetArtistHero<Actions: View>: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let title: String
    let metadata: String?
    /// The artist's own picture.
    let artwork: DulcetArtwork
    /// The newest album's cover, drawn blurred when the artist has no picture.
    let fallback: DulcetArtwork?
    let height: CGFloat
    let barBottom: CGFloat
    @ViewBuilder let actions: () -> Actions
    @State private var picture = DulcetArtistHeroPicture()
    @State private var identityHeight: CGFloat = 120 // design-token: allow first-frame guess, replaced by the measured height
    /// The hero's top edge in the scroll view: 0 at rest, positive pulled, negative scrolled.
    @State private var heroMinY: CGFloat = 0

    var body: some View {
        GeometryReader { proxy in
            let minY = proxy.frame(in: .named(DulcetArtistHeroLayout.scrollSpace)).minY
            // Pulled down past the top: the picture grows to fill the gap, anchored to its bottom.
            let pull = max(0, minY)
            // Scrolled up: the picture drifts at a third of the scroll, so the page slides over it.
            let drift = reduceMotion ? 0 : max(0, -minY) / 3
            backdrop
                .frame(width: proxy.size.width, height: height + pull)
                .clipped()
                .offset(y: -pull + drift)
        }
        .frame(height: height)
        // Clip the drifting picture at the hero's bottom edge only; above, the stretch shows.
        .mask(alignment: .bottom) { Rectangle().frame(height: height + 2000) } // design-token: allow headroom for the pull, not layout
        .overlay(alignment: .bottom) { bottomScrim }
        .overlay(alignment: .bottomLeading) {
            // The name and buttons fade as they reach the bar, rather than passing under the
            // back button; the bar takes the name as they go.
            identity.opacity(DulcetArtistHeroLayout.identityOpacity(
                identityTop: heroMinY + height - identityHeight, barBottom: barBottom))
        }
        .onGeometryChange(for: CGFloat.self) { proxy in
            proxy.frame(in: .named(DulcetArtistHeroLayout.scrollSpace)).minY
        } action: { heroMinY = $0 }
        .onAppear { picture.load(artwork: artwork, fallback: fallback, pixelHeight: height * 3, store: store) }
        .onChange(of: artwork) { _, _ in picture.load(artwork: artwork, fallback: fallback, pixelHeight: height * 3, store: store) }
        .onChange(of: fallback) { _, _ in picture.load(artwork: artwork, fallback: fallback, pixelHeight: height * 3, store: store) }
        .onDisappear { picture.cancel() }
    }

    private var backdrop: some View {
        ZStack(alignment: .top) {
            // The Now Playing tint of the same picture: what shows while it loads, and all
            // that shows when neither the artist nor any album has a picture.
            DulcetPlayerBackdrop(artwork: fallback ?? artwork)
            if let image = picture.image {
                Image(decorative: image, scale: 1, orientation: .up)
                    .resizable()
                    .scaledToFill()
                    // A cover standing in for the artist is not their photo: blurred to a field
                    // of its colours, it reads as the page's colour rather than as an album.
                    .blur(radius: picture.isFallback ? 36 : 0, opaque: true)
                    .saturation(picture.isFallback ? 1.15 : 1)
                    .transition(.opacity)
            }
            // Behind the status bar and the back button.
            LinearGradient(
                colors: [.black.opacity(DulcetArtistHeroLayout.scrimOpacity(luminance: picture.topLuminance) * 0.8), .clear],
                startPoint: .top, endPoint: .bottom
            )
            .frame(height: barBottom + DulcetSpacing.lg)
        }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.35), value: picture.image != nil)
        .accessibilityHidden(true)
    }

    /// Sized from the text block it sits under, and as dark as the picture under it needs.
    private var bottomScrim: some View {
        let opacity = DulcetArtistHeroLayout.scrimOpacity(luminance: picture.bottomLuminance)
        let band = min(height, identityHeight + DulcetSpacing.xl * 2)
        let solid = max(0, min(1, identityHeight / band))
        return LinearGradient(
            stops: [
                .init(color: .black.opacity(0), location: 0),
                .init(color: .black.opacity(opacity * 0.7), location: 1 - solid),
                .init(color: .black.opacity(opacity), location: 1),
            ],
            startPoint: .top, endPoint: .bottom
        )
        .frame(height: band)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var identity: some View {
        let stacked = dynamicTypeSize.isAccessibilitySize
        let layout = stacked
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: DulcetSpacing.sm))
            : AnyLayout(HStackLayout(alignment: .bottom, spacing: DulcetSpacing.sm))
        return layout {
            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                Text(title)
                    .font(.largeTitle.weight(.bold))
                    .foregroundStyle(.white) // dulcet-contrast-waiver: decorative-artwork-overlay
                    .lineLimit(3)
                    .minimumScaleFactor(0.75)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("dulcet.artist.title")
                    .dulcetDetailTitleMarker()
                if let metadata, !metadata.isEmpty {
                    Text(metadata)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white.opacity(0.85)) // dulcet-contrast-waiver: decorative-artwork-overlay
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .contain)
            .accessibilitySortPriority(2)
            actions()
                .accessibilityElement(children: .contain)
                .accessibilitySortPriority(1)
        }
        .padding(.horizontal, DulcetSpacing.md)
        .padding(.bottom, DulcetSpacing.md)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { identityHeight = $0 }
        .accessibilityElement(children: .contain)
    }
}

/// Play and Shuffle on the hero, at the bottom right of the picture where a thumb rests: Play
/// the larger and prominent, Shuffle beside it on glass.
struct DulcetArtistHeroActions: View {
    let isEnabled: Bool
    let play: () -> Void
    let shuffle: () -> Void

    var body: some View {
        HStack(spacing: DulcetSpacing.sm) {
            Button(action: shuffle) {
                Image(systemName: "shuffle")
                    .font(.title3.weight(.semibold))
                    .frame(width: DulcetMetrics.heroSecondaryGlyphFrame, height: DulcetMetrics.heroSecondaryGlyphFrame)
            }
            .modifier(DulcetHeroButtonStyle(prominent: false))
            .accessibilityLabel(DulcetStrings.shuffle)
            .accessibilityIdentifier("dulcet.artist.shuffle")
            .accessibilitySortPriority(0)
            Button(action: play) {
                Image(systemName: "play.fill")
                    .font(.title2.weight(.semibold))
                    .frame(width: DulcetMetrics.heroPlayGlyphFrame, height: DulcetMetrics.heroPlayGlyphFrame)
            }
            .modifier(DulcetHeroButtonStyle(prominent: true))
            .accessibilityLabel(DulcetStrings.play)
            .accessibilityIdentifier("dulcet.artist.play")
            // VoiceOver reaches Play before Shuffle, as it reads every other page's pair.
            .accessibilitySortPriority(1)
        }
        .disabled(!isEnabled)
    }
}

private struct DulcetHeroButtonStyle: ViewModifier {
    let prominent: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            if prominent {
                content.buttonStyle(.glassProminent).buttonBorderShape(.circle)
            } else {
                content.buttonStyle(.glass).buttonBorderShape(.circle).environment(\.colorScheme, .dark)
            }
        } else if prominent {
            content.buttonStyle(.borderedProminent).buttonBorderShape(.circle)
        } else {
            content.buttonStyle(.bordered).buttonBorderShape(.circle).tint(.white).environment(\.colorScheme, .dark)
        }
    }
}

/// Loads the hero's picture: the artist's own, or, when the server has none (no reference, no
/// answer, or its stock "no photo" picture), the newest album's cover.
@MainActor
@Observable
final class DulcetArtistHeroPicture {
    private(set) var image: CGImage?
    private(set) var isFallback = false
    /// The brightest luminance behind the status bar and behind the name; until a picture
    /// arrives, the tint behind them, which the backdrop holds at 0.14 or below.
    private(set) var topLuminance = 0.14
    private(set) var bottomLuminance = 0.14
    @ObservationIgnored private var operation: (any DulcetArtworkFetchOperation)?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored weak var store: DulcetPresentationStore?

    func load(artwork: DulcetArtwork, fallback: DulcetArtwork?, pixelHeight: CGFloat, store: DulcetPresentationStore) {
        self.store = store
        cancel()
        let bucket = DulcetArtworkSizeBucket.containing(pixelSize: pixelHeight)
        fetch(artwork.remoteReference, bucket: bucket, isFallback: false) { [weak self] in
            self?.fetch(fallback?.remoteReference, bucket: .pixels512, isFallback: true, otherwise: {})
        }
    }

    func cancel() {
        generation += 1
        operation?.cancel()
        operation = nil
    }

    private func fetch(
        _ reference: DulcetArtworkReference?, bucket: DulcetArtworkSizeBucket, isFallback: Bool,
        otherwise: @escaping @MainActor () -> Void
    ) {
        guard let reference, let store else { return otherwise() }
        let requested = generation
        let accept: (CGImage) -> Void = { [weak self] image in
            guard let self else { return }
            if !isFallback, DulcetStockArtistImage.isStock(image) { return otherwise() }
            self.image = image
            self.isFallback = isFallback
            let bands = DulcetArtistHeroLayout.bandLuminance(image) ?? (top: 0.5, bottom: 0.5)
            self.topLuminance = bands.top
            self.bottomLuminance = bands.bottom
        }
        if let cached = store.cachedArtwork(reference, sizeBucket: bucket, allowingOtherSizes: false) {
            return accept(cached)
        }
        operation = store.loadArtwork(reference, sizeBucket: bucket) { [weak self] outcome in
            guard let self, requested == self.generation else { return }
            self.operation = nil
            if case let .loaded(data) = outcome,
               let image = DulcetArtworkMemoryCache.decode(data, maximumPixelSize: bucket.rawValue) {
                accept(image)
            } else if case .cancelled = outcome {
                return
            } else {
                otherwise()
            }
        }
    }
}
#endif
