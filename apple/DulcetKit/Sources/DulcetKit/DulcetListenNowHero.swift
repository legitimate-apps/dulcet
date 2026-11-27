#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

/// The sizes behind Listen Now's Top Picks, kept apart from the views so each can be tested.
enum DulcetListenNowHeroMetrics {
    /// A card is a little taller than wide, the shape of Music's Top Picks: a square cover over a
    /// band of its own colour that carries the title.
    static let heightPerWidth: CGFloat = 1.3
    static let maximumCardWidth: CGFloat = 330
    /// How much of the next card shows at the trailing edge on a phone, so the row says it scrolls.
    static let nextCardPeek: CGFloat = 44
    static let cornerRadius: CGFloat = 22
    static let playButtonSize: CGFloat = 46
    static let shadowRadius: CGFloat = 14
    static let shadowOffset: CGFloat = 6
    /// How far a card's cover drifts against its card while the row pages (motion off: none).
    static let parallax: CGFloat = 40
    /// A card leaving the centre of the row settles back a little, as Music's do.
    static let pagedScale: CGFloat = 0.93
    static let pressedScale: CGFloat = 0.97
    /// Pulled down, the row grows from its bottom edge by this fraction of the pull's distance.
    static let stretchPerPoint: CGFloat = 0.0012
    /// Scrolled up, the row fades out across this distance as the page passes over it.
    static let fadeDistance: CGFloat = 420

    /// The width of one card on a page `pageWidth` wide with `inset` margins: on a phone one card
    /// and a peek of the next, elsewhere never wider than `maximumCardWidth`.
    static func cardWidth(pageWidth: CGFloat, inset: CGFloat) -> CGFloat {
        max(200, min(maximumCardWidth, pageWidth - inset * 2 - nextCardPeek))
    }

    /// The row's scale for a top edge `offset` points below where it rests: up to grow on a pull,
    /// never below 1.
    static func stretchScale(offset: CGFloat) -> CGFloat {
        1 + max(0, offset) * stretchPerPoint
    }

    /// The row's opacity once it has scrolled `offset` points above where it rests.
    static func scrolledOpacity(offset: CGFloat) -> Double {
        Double(max(0.25, min(1, 1 + min(0, offset) / fadeDistance)))
    }
}

#if !os(tvOS)
/// Listen Now's first section: a row of large cards made from the listener's own music, each
/// tinted with its artwork's colours, with a reason over it and a Play that plays it in one tap.
/// Hidden when nothing personal is known yet; the shelves below still show.
struct DulcetListenNowHero: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dulcetListenNowZoom) private var zoom
    let picks: [DulcetListenNowPick]
    let width: CGFloat

    var body: some View {
        if !picks.isEmpty {
            let inset = DulcetLibraryMetrics.horizontalInset(forWidth: width)
            let cardWidth = DulcetListenNowHeroMetrics.cardWidth(pageWidth: width, inset: inset)
            let motion = !reduceMotion
            VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                Text(DulcetStrings.homeTopPicks)
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: DulcetLibraryMetrics.shelfTileSpacing) {
                        ForEach(Array(picks.enumerated()), id: \.element.id) { index, pick in
                            DulcetListenNowPickCard(pick: pick, index: index, width: cardWidth)
                                .scrollTransition(axis: .horizontal) { content, phase in
                                    content
                                        .scaleEffect(motion && !phase.isIdentity ? DulcetListenNowHeroMetrics.pagedScale : 1)
                                        .opacity(phase.isIdentity ? 1 : 0.8)
                                }
                                .transition(motion ? .opacity.combined(with: .scale(scale: 0.96)) : .opacity)
                        }
                    }
                    .scrollTargetLayout()
                    .padding(.vertical, DulcetSpacing.xs)
                }
                .scrollTargetBehavior(.viewAligned)
                .modifier(DulcetEdgeToEdgeShelf(pageWidth: width))
            }
            // Pulled down, the cards grow toward the pull; scrolled away, they fade under the
            // page instead of sliding off as flat paper.
            .visualEffect { content, proxy in
                let offset = proxy.frame(in: .named("dulcet.detail.scroll")).minY - DulcetSpacing.md
                return content
                    .scaleEffect(motion ? DulcetListenNowHeroMetrics.stretchScale(offset: offset) : 1, anchor: .bottom)
                    .opacity(motion ? DulcetListenNowHeroMetrics.scrolledOpacity(offset: offset) : 1)
            }
            .animation(motion ? .smooth(duration: 0.45) : nil, value: picks.map(\.id))
            .padding(.bottom, DulcetSpacing.lg)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("dulcet.listenNow.hero")
        }
    }
}

/// One Top Pick: the reason above, then the card. The card opens its page; its Play plays it.
private struct DulcetListenNowPickCard: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dulcetListenNowZoom) private var zoom
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let pick: DulcetListenNowPick
    let index: Int
    let width: CGFloat

    private var height: CGFloat { width * DulcetListenNowHeroMetrics.heightPerWidth }

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(pick.reason.text)
                .font(.subheadline.weight(.semibold))
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(1)
                .frame(width: width, alignment: .leading)
                .accessibilityHidden(true)
            ZStack(alignment: .bottomTrailing) {
                Button {
                    if let route = pick.route { store.pushReaderPage(route) }
                } label: { card }
                .buttonStyle(DulcetPressedCardStyle(reduceMotion: reduceMotion))
                .dulcetHoverEffect(.lift)
                .accessibilityLabel(DulcetStrings.homePickOpen(pick.reason.text, pick.title, pick.subtitle))
                .accessibilityIdentifier("dulcet.listenNow.hero.card.\(index)")
                playButton
                    .padding(DulcetSpacing.md)
            }
            .frame(width: width, height: height)
            .dulcetZoomSource(pick.route, in: zoom)
        }
    }

    private var card: some View {
        ZStack(alignment: .bottomLeading) {
            // The card's colour is its artwork's: the field the player draws behind the same cover.
            DulcetPlayerBackdrop(artwork: pick.artworks.first ?? DulcetArtwork.derived(seed: pick.id, serverID: String(), artworkKey: nil))
            cover
                .frame(width: width, height: width)
                .scrollTransition(axis: .horizontal) { content, phase in
                    content.offset(x: reduceMotion ? 0 : phase.value * -DulcetListenNowHeroMetrics.parallax)
                }
                // The cover runs down into its colour rather than stopping at a hard edge.
                .mask {
                    LinearGradient(stops: [.init(color: .black, location: 0.6), .init(color: .clear, location: 1)],
                                   startPoint: .top, endPoint: .bottom)
                }
                .frame(maxHeight: .infinity, alignment: .top)
            DulcetListenNowPickCaption(title: pick.title, subtitle: pick.subtitle)
            .multilineTextAlignment(.leading)
            .padding(.leading, DulcetSpacing.md)
            // Clear of the Play button at the trailing corner.
            .padding(.trailing, DulcetListenNowHeroMetrics.playButtonSize + DulcetSpacing.md * 2)
            .padding(.bottom, DulcetSpacing.md)
        }
        .frame(width: width, height: height)
        .clipShape(RoundedRectangle(cornerRadius: DulcetListenNowHeroMetrics.cornerRadius, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: DulcetListenNowHeroMetrics.cornerRadius, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: DulcetListenNowHeroMetrics.shadowRadius,
                y: DulcetListenNowHeroMetrics.shadowOffset)
    }

    @ViewBuilder private var cover: some View {
        let artworks = pick.artworks
        if artworks.count == 4 {
            // A mix shows four of the albums it plays.
            let half = width / 2
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    DulcetArtworkView(artwork: artworks[0], size: half)
                    DulcetArtworkView(artwork: artworks[1], size: half)
                }
                HStack(spacing: 0) {
                    DulcetArtworkView(artwork: artworks[2], size: half)
                    DulcetArtworkView(artwork: artworks[3], size: half)
                }
            }
        } else if let artwork = artworks.first {
            DulcetArtworkView(artwork: artwork, size: width)
        }
    }

    private var playButton: some View {
        let target = playTarget
        let playing = target.map { store.playsTarget($0) } ?? mixIsPlaying
        let canStart: Bool
        switch pick.content {
        case .collection: canStart = true
        case .mix: canStart = !pick.playableMixTracks.isEmpty
        }
        return Button {
            DulcetHaptic.playPause.play()
            if playing {
                store.sendPlaybackControl(.pause)
            } else {
                store.pressPlay(target) { play() }
            }
        } label: {
            Image(systemName: playing ? "pause.fill" : "play.fill")
                .font(.title3.weight(.semibold))
                .dulcetForeground(.primaryTextOnArtworkTint)
                .frame(width: DulcetListenNowHeroMetrics.playButtonSize, height: DulcetListenNowHeroMetrics.playButtonSize)
                .contentTransition(.symbolEffect(.replace))
                .dulcetHeroGlassCircle()
        }
        .buttonStyle(DulcetPressedCardStyle(reduceMotion: reduceMotion))
        .accessibilityLabel(playing ? DulcetStrings.pause : DulcetStrings.homePlay(pick.title))
        .accessibilityIdentifier("dulcet.listenNow.hero.play.\(index)")
        .disabled(!playing && !canStart)
    }

    /// A mix has no id the core records it by: it is the library source it was started as, under
    /// this card's name, sounding one of this card's tracks. That is what the card's Pause means.
    private var mixIsPlaying: Bool {
        guard case let .mix(_, tracks) = pick.content, let now = store.snapshot.nowPlaying,
              now.isPlaying, now.source?.kind == .library, now.source?.id == nil,
              now.sourceDisplayName == pick.title else { return false }
        return tracks.contains { $0.playableTrack?.id == now.current.id }
    }

    /// An album or a server playlist shows Pause while its own music plays, as its page's Play does.
    private var playTarget: DulcetPlayTarget? {
        guard case let .collection(item) = pick.content, let id = item.playbackSourceID,
              item.playbackSourceKind != .library else { return nil }
        return .collection(item.playbackSourceKind, id)
    }

    private func play() {
        switch pick.content {
        case let .collection(item):
            store.resolveAndPlay(item, shuffle: false)
        case .mix:
            store.playReaderTracks(pick.playableMixTracks, sourceKind: .library, sourceID: nil,
                                   sourceName: pick.title)
        }
    }
}

/// A card or a button that gives under the finger, as Music's do, and springs back.
/// A card's title and subtitle on its artwork's colour field.
struct DulcetListenNowPickCaption: View {
    let title: String
    let subtitle: String
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            Text(verbatim: title)
                .font(.title3.bold())
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 2)
                .dulcetForeground(.primaryTextOnArtworkTint)
            if !subtitle.isEmpty {
                Text(verbatim: subtitle)
                    .font(.subheadline)
                    .lineLimit(2)
                    .dulcetForeground(.secondaryTextOnArtworkTint)
            }
        }
    }
}

private struct DulcetPressedCardStyle: ButtonStyle {
    let reduceMotion: Bool
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? DulcetListenNowHeroMetrics.pressedScale : 1)
            .opacity(configuration.isPressed && reduceMotion ? 0.7 : 1)
            .animation(reduceMotion ? nil : .spring(response: 0.28, dampingFraction: 0.62), value: configuration.isPressed)
    }
}

private extension View {
    /// The Play button's disc: Liquid Glass where the system draws it, a dark material before.
    @ViewBuilder func dulcetHeroGlassCircle() -> some View {
        if #available(iOS 26.0, macOS 26.0, *) {
            glassEffect(.regular.tint(.black.opacity(0.18)).interactive(), in: .circle)
        } else {
            background(.ultraThinMaterial, in: Circle())
        }
    }
}
#endif

// MARK: - Zoom from Listen Now into the page a tile opens

/// A pushed page that zooms out of its Listen Now tile when Listen Now opened it directly. The
/// choice is made once, as the page is created: the path changes under a page while it is popped,
/// and a transition that changed then would replace the page mid-gesture.
struct DulcetListenNowZoomPage: ViewModifier {
    let route: DulcetReaderRoute
    let namespace: Namespace.ID
    @State private var zooms: Bool

    init(route: DulcetReaderRoute, namespace: Namespace.ID, zooms: Bool) {
        self.route = route
        self.namespace = namespace
        _zooms = State(initialValue: zooms)
    }

    func body(content: Content) -> some View {
        content.dulcetZoomDestination(route, in: zooms ? namespace : nil)
    }
}

private struct DulcetListenNowZoomKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

private struct DulcetListenNowShownKey: EnvironmentKey {
    static let defaultValue: [DulcetListenNowShelf: [DulcetReaderItem]] = [:]
}

extension EnvironmentValues {
    /// The namespace Listen Now's tiles and cards register their artwork in, so the page they open
    /// grows out of the tile pressed. Nil elsewhere: no other page zooms.
    var dulcetListenNowZoom: Namespace.ID? {
        get { self[DulcetListenNowZoomKey.self] }
        set { self[DulcetListenNowZoomKey.self] = newValue }
    }

    /// What each Listen Now shelf last drew, handed to the page it opens so that page has rows
    /// from its first frame.
    var dulcetListenNowShown: [DulcetListenNowShelf: [DulcetReaderItem]] {
        get { self[DulcetListenNowShownKey.self] }
        set { self[DulcetListenNowShownKey.self] = newValue }
    }
}

extension View {
    /// Registers this view as where `route`'s page zooms out of and back into. iPhone and iPad on
    /// iOS 18 and later; elsewhere the page pushes as before.
    @ViewBuilder func dulcetZoomSource(_ route: DulcetReaderRoute?, in namespace: Namespace.ID?) -> some View {
#if os(iOS)
        if #available(iOS 18.0, *), let route, let namespace {
            matchedTransitionSource(id: route, in: namespace)
        } else {
            self
        }
#else
        self
#endif
    }

    /// The page for `route` zooms out of its Listen Now tile. Nil namespace: the ordinary push.
    @ViewBuilder func dulcetZoomDestination(_ route: DulcetReaderRoute, in namespace: Namespace.ID?) -> some View {
#if os(iOS)
        if #available(iOS 18.0, *), let namespace {
            navigationTransition(.zoom(sourceID: route, in: namespace))
        } else {
            self
        }
#else
        self
#endif
    }
}
#endif
