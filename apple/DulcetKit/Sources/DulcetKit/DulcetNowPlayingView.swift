#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

/// Where the player's cover, its title column and its footer -- the last row of controls -- are
/// laid out, for layout tests that check the cover spans the column and the footer is on screen.
enum DulcetPlayerLayoutPart: Hashable { case cover, column, footer }

struct DulcetPlayerLayoutKey: PreferenceKey {
    static let defaultValue: [DulcetPlayerLayoutPart: Anchor<CGRect>] = [:]
    static func reduce(
        value: inout [DulcetPlayerLayoutPart: Anchor<CGRect>],
        nextValue: () -> [DulcetPlayerLayoutPart: Anchor<CGRect>]
    ) {
        value.merge(nextValue()) { first, _ in first }
    }
}

struct DulcetNowPlayingView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    enum Presentation {
        /// A navigation destination: the sidebar's Now Playing on iPad and Mac, the tvOS section.
        case destination
        /// Compact styling for the player presented from the now-playing bar.
        case sheet
        /// Regular-width styling for the player presented from its now-playing bar.
        case fullScreen
    }

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var scrubPosition: Double?
    @State private var showingQueue = false
    /// The lyrics panel in place of the queue beside the player, or of the cover in one column.
    @State private var showingLyrics = false
#if os(tvOS)
    /// Remote focus on the footer's controls. Showing or hiding lyrics swaps the layout under the
    /// control that did it, so focus is put back on that control rather than wherever the focus
    /// engine lands in the new layout.
    @FocusState private var focusedFooterControl: FooterControl?
    /// Remote focus on play/pause, where Now Playing puts it when it arrives without the person
    /// choosing it -- a search result activated, the control that held focus gone with Search.
    @FocusState private var playPauseFocused: Bool
    @Environment(DulcetArrivalFocus.self) private var arrivalFocus: DulcetArrivalFocus?
#endif
    /// The side-by-side player's own height, cover to footer, once laid out.
    @State private var sideBySidePlayerHeight: CGFloat?
    /// Everything under the cover -- title, scrubber, transport, footer -- as laid out. The cover
    /// takes what the height leaves after it, so no control is ever below the screen's edge.
    @State private var playerChromeHeight: CGFloat?
    let player: DulcetNowPlaying
    var presentation: Presentation = .destination
    var onControl: (DulcetPlaybackControlIntent) -> Void = { _ in }
    /// Queue edits from Up Next: jump, reorder, remove, clear.
    var onEdit: (DulcetQueueEditIntent) -> Void = { _ in }
    /// Runs before following a link out of the player, so a sheet can get out of the way.
    var onNavigate: () -> Void = {}
    /// A full-screen cover has no sheet to drag, so a downward swipe on the artwork closes it.
    var onDismiss: (() -> Void)?

    var body: some View {
        // The reader consumes the proposal before any ScrollView, so the layout decision below
        // depends only on the width the surface was given.
        GeometryReader { geometry in
            let width = geometry.size.width
            let padding = horizontalPadding(for: width)
            if sideBySide(width: width) {
                let artworkSize = Self.fittedArtworkSize(
                    widthLimit: 520,
                    availableHeight: geometry.size.height - 2 * DulcetSpacing.xl,
                    chromeHeight: playerChromeHeight,
                    minimum: Self.sideBySideMinimumArtwork,
                    unmeasured: Self.sideBySideArtworkSize(height: geometry.size.height)
                )
                // Centred together: the queue sits beside the cover and controls it belongs to,
                // not pinned to the window's top with the space under it empty.
                HStack(alignment: .center, spacing: Self.playerToQueueSpacing) {
                    ScrollView {
                        playerPanel(
                            artworkSize: artworkSize,
                            alignment: .center,
                            showsQueueToggle: false
                        )
                        // Measured, not estimated: the queue column beside it takes this height.
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: {
                            sideBySidePlayerHeight = $0
                        }
                        .padding(.vertical, DulcetSpacing.xl)
                        // Centred in the window's height when it fits; it scrolls only when it
                        // does not.
                        .frame(minHeight: geometry.size.height, alignment: .center)
                    }
                    // A player that fits does not rubber-band, so a downward swipe on the
                    // artwork reaches the dismissal rather than bouncing the scroll view.
                    .scrollBounceBehavior(.basedOnSize)
                    // The artwork's shadow reaches past the column; clipping it drew a band.
                    .scrollClipDisabled()
                    .frame(maxWidth: 520)
                    sideColumn
                        .frame(maxWidth: 390)
                        .frame(height: Self.sideBySideQueueHeight(
                            windowHeight: geometry.size.height,
                            playerHeight: sideBySidePlayerHeight
                        ))
                        // Lyrics beside the player sit over the cover's colours, as the system
                        // player draws its lyrics pane; the queue stays on the plain window.
                        .background {
                            if showingLyrics {
                                DulcetLyricsBackdrop(artwork: player.current.artwork)
                                    .clipShape(RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous))
                            }
                        }
                }
                .padding(.horizontal, padding)
                .frame(maxWidth: .infinity)
            } else if showingLyrics {
#if os(tvOS)
                // A lyrics screen, not a list adrift on an empty screen: the cover, the track's
                // name and the transport stay on the leading side -- the way back to the
                // transport the lyrics button left -- and the lines take the rest of the screen
                // at a size that reads from a sofa.
                HStack(alignment: .center, spacing: DulcetSpacing.xxl) {
                    VStack(alignment: .leading, spacing: Self.coverToTitleSpacing) {
                        playerCover(size: Self.televisionLyricsArtworkSize)
                        trackIdentity(alignment: .leading)
                        transportControls
                        footer(alignment: .leading, showsQueueToggle: true)
                    }
                    .frame(width: Self.televisionLyricsColumnWidth)
                    lyricsPanel
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .padding(.horizontal, padding)
                .frame(maxWidth: .infinity)
                .frame(minHeight: geometry.size.height, alignment: .center)
                .background { DulcetLyricsBackdrop(artwork: player.current.artwork).ignoresSafeArea() }
#else
                // As the system player's lyrics: the track named small at the top, the lines
                // taking the screen over the cover's colours, and the scrubber and transport kept
                // at the bottom, so pausing or skipping never means leaving the lyrics.
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    lyricsHeader
                    lyricsPanel
                    playbackProgress
                    transportControls
                    footer(alignment: .leading, showsQueueToggle: true)
                }
                .padding(.horizontal, padding)
                .padding(.top, DulcetSpacing.xs)
                .padding(.bottom, DulcetSpacing.md)
                .frame(maxWidth: Self.oneColumnLyricsMaxWidth)
                .frame(maxWidth: .infinity)
                .background { DulcetLyricsBackdrop(artwork: player.current.artwork).ignoresSafeArea() }
#endif
            } else if showingQueue {
                // The queue replaces the artwork; the footer stays, so the control that opened it
                // closes it. (That the system player lays it out this way is ASSUMED, not compared.)
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    queueColumn
                    footer(alignment: .leading, showsQueueToggle: true)
                        .padding(.horizontal, padding)
                        .padding(.bottom, DulcetSpacing.md)
                }
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
            } else {
#if os(tvOS)
                // A television is wide and short: the cover beside everything else, sized from
                // the height, so the transport and the heart under it sit on screen, where the
                // remote reaches them. Stacked, they fell below the bottom edge of a 1080-point
                // screen, and Down from the section bar found nothing to land on. The pair leads
                // from the edge and the controls take the width the cover leaves, as the system's
                // TV player lays it out; centred in a narrow column, the screen's trailing half
                // sat empty (observed).
                ScrollView {
                    HStack(alignment: .center, spacing: DulcetSpacing.xxl) {
                        playerCover(size: Self.televisionArtworkSize(height: geometry.size.height))
                        VStack(alignment: .leading, spacing: Self.coverToTitleSpacing) {
                            trackIdentity(alignment: .leading)
                            playbackProgress
                            transportControls
                            footer(alignment: .leading, showsQueueToggle: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, padding)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: geometry.size.height, alignment: .center)
                }
                .scrollBounceBehavior(.basedOnSize)
#else
                let verticalPadding = presentation == .sheet ? Self.sheetVerticalPadding : DulcetSpacing.xl
                let bottomPadding = presentation == .sheet ? Self.sheetBottomPadding : DulcetSpacing.xl
                ScrollView {
                    playerPanel(
                        artworkSize: stackedArtworkSize(
                            innerWidth: max(0, min(560, width - 2 * padding)),
                            availableHeight: geometry.size.height - verticalPadding - bottomPadding
                        ),
                        alignment: presentation == .sheet ? .leading : .center,
                        showsQueueToggle: true
                    )
                    .frame(maxWidth: 560)
                    .padding(.horizontal, padding)
                    .padding(.top, verticalPadding)
                    .padding(.bottom, bottomPadding)
                    .frame(maxWidth: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
#endif
            }
        }
        .background(Color.dulcetWindow.ignoresSafeArea())
        .dulcetForeground(.primaryTextOnWindow)
        .dulcetNavigationTitle(DulcetStrings.nowPlaying)
#if os(tvOS)
        .onAppear(perform: claimArrivalFocus)
        // The focus engine can fall back to the section bar after this appeared.
        .onChange(of: arrivalFocus?.wantsSectionFocus == true) { _, wants in
            if wants { claimArrivalFocus() }
        }
        .onChange(of: playPauseFocused) { _, focused in
            if focused { arrivalFocus?.sectionControlFocused() }
        }
#endif
    }

#if os(tvOS)
    private func claimArrivalFocus() {
        guard let arrivalFocus, arrivalFocus.pending, !showingLyrics, !showingQueue else { return }
        playPauseFocused = true
    }
#endif

    /// The gaps between the cover and the nearest text on every side. The cover's glow reaches
    /// less far than the smallest of them (``DulcetArtworkGlow/reach``), so no text on the player
    /// ever sits on cover colour: every text colour here is a registered contrast pair measured
    /// against the window colour (spec §3.1).
    static let coverToTitleSpacing = DulcetSpacing.lg
    /// The fixed gaps under the cover: title to scrubber, scrubber to transport, transport to
    /// footer. Tight, as the system player sets them, so on a phone the cover keeps the column's
    /// full width and the controls still fit beneath it; each shrinks where the rows themselves
    /// carry air (the transport's and the footer's 44-point targets around smaller glyphs). Not
    /// the cover's own gap, which the glow sets. Apple TV's player keeps its wider gaps.
#if os(tvOS)
    static let titleToScrubberSpacing = coverToTitleSpacing
    static let scrubberToTransportSpacing = coverToTitleSpacing
    static let transportToFooterSpacing = coverToTitleSpacing
#else
    static let titleToScrubberSpacing = DulcetSpacing.sm
    static let scrubberToTransportSpacing = DulcetSpacing.xs
    static let transportToFooterSpacing = DulcetSpacing.xxs
#endif
    /// Under a phone sheet's player: the home indicator's own inset already clears the footer.
    static let sheetBottomPadding = DulcetSpacing.xxs
    static let playerToQueueSpacing = DulcetSpacing.xl
    static let sheetVerticalPadding = DulcetSpacing.md
    /// One target size for the player footer's marks -- heart, each star, lyrics, Up Next: a
    /// touch target, and on a television a focus target. One size for all of them, so their
    /// focus platters match and the row's spacing keeps every platter clear of the next.
    static var footerControlSide: CGFloat {
#if os(tvOS)
        64
#else
        44
#endif
    }
    /// Between the footer's rows. On a television a focused mark's platter reaches past its
    /// target, and the format badge under the stars sat inside that reach (observed).
    static var footerStackSpacing: CGFloat {
#if os(tvOS)
        DulcetSpacing.md
#else
        // Each mark is a 44-point target around a smaller glyph, so the targets' own margins
        // already set the rows apart; a wider gap only pushed the cover smaller.
        DulcetSpacing.xxs
#endif
    }
    static let minimumHorizontalPadding = DulcetSpacing.lg

    /// Lyrics in one column: a phone's or a narrow window's width, and on a television wide enough
    /// that a line set large enough to read across a room is not broken after two words.
    static var oneColumnLyricsMaxWidth: CGFloat {
#if os(tvOS)
        1_200
#else
        600
#endif
    }

    /// The controls in the player's footer row, in order.
    enum FooterControl: Hashable {
        /// The playing track's heart (§16.20).
        case favourite
        /// The playing track's stars, always directly after the heart (§16.20).
        case rating
        case airPlay
        /// The sleep timer's menu (§12.13).
        case sleepTimer
        case lyrics
        case upNext
    }

    /// Which footer controls the player offers. The heart and the stars beside it are offered only
    /// while the reader holds the playing track's account -- one rule for both, so they never
    /// part; on macOS they are in the window's toolbar instead. Apple TV routes audio with the
    /// remote and the TV and has no Up Next toggle, but it has lyrics, the heart and the stars, all
    /// reached by focus.
    static func footerControls(offersFavourite: Bool, showsQueueToggle: Bool) -> [FooterControl] {
        let marks: [FooterControl] = offersFavourite ? [.favourite, .rating] : []
#if os(tvOS)
        return marks + [.sleepTimer, .lyrics]
#elseif os(macOS)
        return [.airPlay, .sleepTimer, .lyrics] + (showsQueueToggle ? [.upNext] : [])
#else
        return marks + [.airPlay, .sleepTimer, .lyrics] + (showsQueueToggle ? [.upNext] : [])
#endif
    }

    /// The footer's rows. On iPhone and iPad five 44-point stars, the heart and three 44-point
    /// controls do not fit a phone's width beside the format badge, so the track's own marks --
    /// heart, then stars -- take a row of their own above the others. Apple TV's controls column
    /// fits them all in one row, which Down from the transport reaches.
    static func footerRows(_ controls: [FooterControl]) -> [[FooterControl]] {
#if os(iOS)
        let marks = controls.filter { $0 == .favourite || $0 == .rating }
        let rest = controls.filter { $0 != .favourite && $0 != .rating }
        return [marks, rest].filter { !$0.isEmpty }
#else
        [controls]
#endif
    }

    /// Side by side only when both panels fit at their natural widths; otherwise one column.
    /// Dynamic Type at accessibility sizes always gets one column, whatever the width.
    static func usesSideBySideLayout(width: CGFloat, accessibilitySize: Bool) -> Bool {
        !accessibilitySize && width >= 820
    }

    /// The side-by-side cover before the controls under it have been measured: an estimate
    /// from the height alone. Once measured, ``fittedArtworkSize(widthLimit:availableHeight:chromeHeight:minimum:unmeasured:)``
    /// decides.
    static func sideBySideArtworkSize(height: CGFloat) -> CGFloat {
        min(520, max(280, height - 380))
    }

    static let sideBySideMinimumArtwork: CGFloat = 160
    static let stackedMinimumArtwork: CGFloat = 80

    /// The cover as large as the width allows while the title, scrubber, transport and footer
    /// -- `chromeHeight`, as laid out -- still fit beneath it without scrolling. The system player
    /// shrinks its artwork the same way at large text sizes, so no control is ever pushed below
    /// the screen's edge. A height too short even for the `minimum` cover keeps the minimum and
    /// scrolls. Until the controls have been measured, `unmeasured`.
    static func fittedArtworkSize(
        widthLimit: CGFloat,
        availableHeight: CGFloat,
        chromeHeight: CGFloat?,
        minimum: CGFloat,
        unmeasured: CGFloat
    ) -> CGFloat {
        guard let chromeHeight else { return unmeasured }
        let left = availableHeight - chromeHeight - coverToTitleSpacing
        return max(minimum, min(widthLimit, left)).rounded(.down)
    }

    /// Apple TV's cover, beside the controls: as large as the height allows after the vertical
    /// margins, and never so large that the controls' column is squeezed, or so small it stops
    /// reading as the cover from across a room.
    static func televisionArtworkSize(height: CGFloat) -> CGFloat {
        min(640, max(280, height - 2 * DulcetSpacing.xl - 120))
    }

    /// The lyrics screen's player column on Apple TV: the small cover, the track's name and the
    /// transport, wide enough for the transport's slots and the footer's row of marks.
    static let televisionLyricsColumnWidth: CGFloat = 700

    /// The small cover on Apple TV's lyrics screen; the lines, not the cover, are the screen.
    static let televisionLyricsArtworkSize: CGFloat = 320

    /// A transport control's slot on a television. A focused glyph's platter is drawn at several
    /// times the glyph (measured ≈3x on tvOS 26), so adjacent slots' centers sit further apart
    /// than the platters reach; jammed together, the focused Play/Pause pill overlapped Previous
    /// and Next (observed).
    static let televisionTransportPlayPauseSlot: CGFloat = 144
    static let televisionTransportSkipSlot: CGFloat = 120
    static let televisionTransportEdgeSlot: CGFloat = 108
    static let televisionTransportSlotHeight: CGFloat = 96

    /// The queue column spans the player beside it -- cover, title, scrubber, transport and
    /// footer, as measured -- rather than the whole window, so the two read as one row; a window
    /// too short for that gives the queue all of it. Until the player has been measured, the
    /// whole window.
    static func sideBySideQueueHeight(windowHeight: CGFloat, playerHeight: CGFloat?) -> CGFloat {
        min(windowHeight, playerHeight ?? windowHeight)
    }

    private func sideBySide(width: CGFloat) -> Bool {
#if os(tvOS)
        false
#else
        // A phone's sheet stays one column however wide a landscape phone is: its queue is one
        // tap away, and a split sheet leaves both halves too short to use.
        presentation != .sheet
            && Self.usesSideBySideLayout(
                width: width,
                accessibilitySize: dynamicTypeSize.isAccessibilitySize
            )
#endif
    }

    private func horizontalPadding(for width: CGFloat) -> CGFloat {
        width < 500 ? Self.minimumHorizontalPadding : DulcetSpacing.xl
    }

    /// Beside the player: the lyrics when shown, else Up Next.
    @ViewBuilder
    private var sideColumn: some View {
        if showingLyrics { lyricsPanel } else { queueColumn }
    }

#if !os(tvOS)
    /// The one-column lyrics screen's header: a small cover beside the track's name and artist.
    private var lyricsHeader: some View {
        HStack(spacing: DulcetSpacing.sm) {
            DulcetArtworkView(artwork: player.current.artwork, size: Self.lyricsHeaderArtworkSize)
            VStack(alignment: .leading, spacing: 2) {
                Text(player.current.title)
                    .font(.headline)
                    .lineLimit(2)
                    // Not the player's title: tests read `dulcet.now-playing.title` as "the player
                    // column is on screen", and in one column the lyrics take its place.
                    .accessibilityIdentifier("dulcet.lyrics.header.title")
                DulcetArtistLink(
                    credits: player.current.credits.filter { $0.role == .artist },
                    font: .subheadline,
                    onNavigate: onNavigate
                )
                .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        // The header is the track's name, not a reading surface: it stops growing at the
        // largest standard size and leaves the screen to the lines.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    static let lyricsHeaderArtworkSize: CGFloat = 56
#endif

    private var lyricsPanel: some View {
        DulcetLyricsPanel(
            track: player.current,
            elapsed: player.elapsed,
            isPlaying: player.isPlaying,
            canSeek: player.seekability == .seekable,
            onSeek: { onControl(.seek($0)) }
        )
#if os(iOS)
            .dulcetPlayerGestureRegion()
#endif
#if os(tvOS)
            // A section, so Right from the footer reaches the panel's lines and Try Again: they
            // sit high in the right column, out of the footer's row, and without a section the
            // focus engine found nothing to the right (OBSERVED on tvOS 26.5: four presses of
            // Right left focus on the lyrics toggle).
            .focusSection()
#endif
    }

    private var lyricsToggle: some View {
        Button {
            withAnimation(reduceMotion ? nil : .snappy) {
                showingLyrics.toggle()
                if showingLyrics { showingQueue = false }
            }
#if os(tvOS)
            // The layout under the button changes; keep the remote on it, so the same press
            // closes what it opened.
            DispatchQueue.main.async { focusedFooterControl = .lyrics }
#endif
        } label: {
            Image(systemName: showingLyrics ? "quote.bubble.fill" : "quote.bubble")
                .font(.title3)
                .frame(minWidth: Self.footerControlSide, minHeight: Self.footerControlSide)
                .contentShape(Rectangle())
        }
        .dulcetMediaButtonStyle()
#if os(tvOS)
        .focused($focusedFooterControl, equals: .lyrics)
#endif
        .accessibilityLabel(showingLyrics ? DulcetStrings.lyricsHide : DulcetStrings.lyricsShow)
        .accessibilityIdentifier("dulcet.now-playing.lyrics")
    }

    private var favouriteButton: some View {
        DulcetNowPlayingFavouriteButton(track: player.current, size: .title3, minimumSide: Self.footerControlSide)
#if os(tvOS)
            .focused($focusedFooterControl, equals: .favourite)
#endif
    }

    /// The playing track's stars (§16.20), beside the heart.
    private var ratingControl: some View {
        DulcetNowPlayingRatingControl(track: player.current, size: .title3, minimumSide: Self.footerControlSide)
    }

    /// The sleep timer: a length, the end of this track or of the queue, or off. While one is set
    /// the moon is filled, and its label says until when.
    private var sleepTimerMenu: some View {
        let timer = player.sleepTimer
        return Menu {
            Picker(DulcetStrings.sleepTimer, selection: Binding(
                get: { timer?.choice },
                set: { store.setSleepTimer($0) }
            )) {
                Text(DulcetStrings.sleepTimerOff).tag(DulcetSleepTimerChoice?.none)
                ForEach(DulcetSleepTimerChoice.allCases) { choice in
                    Text(choice.label).tag(Optional(choice))
                }
            }
            .pickerStyle(.inline)
        } label: {
            Image(systemName: timer == nil ? "moon.zzz" : "moon.zzz.fill")
                .font(.title3)
                .frame(minWidth: Self.footerControlSide, minHeight: Self.footerControlSide)
                .contentShape(Rectangle())
        }
        .menuIndicator(.hidden)
        .dulcetMediaButtonStyle()
#if os(tvOS)
        .focused($focusedFooterControl, equals: .sleepTimer)
#endif
        .accessibilityLabel(DulcetStrings.sleepTimer)
        .accessibilityValue(Self.sleepTimerValue(timer))
        .accessibilityIdentifier("dulcet.now-playing.sleep-timer")
    }

    /// What the sleep timer's control says it is set to.
    static func sleepTimerValue(_ timer: DulcetSleepTimerState?) -> String {
        guard let timer else { return DulcetStrings.sleepTimerOff }
        switch timer.choice {
        case .endOfTrack: return DulcetStrings.sleepTimerAfterTrack
        case .endOfQueue: return DulcetStrings.sleepTimerAfterQueue
        default:
            guard let endsAt = timer.endsAt else { return timer.choice.label }
            return DulcetStrings.sleepTimerUntil(endsAt.formatted(date: .omitted, time: .shortened))
        }
    }

    private var upNextToggle: some View {
        Button {
            withAnimation(reduceMotion ? nil : .snappy) {
                showingQueue.toggle()
                if showingQueue { showingLyrics = false }
            }
        } label: {
            Image(systemName: showingQueue ? "list.bullet.circle.fill" : "list.bullet")
                .font(.title3)
                .frame(minWidth: Self.footerControlSide, minHeight: Self.footerControlSide)
                .contentShape(Rectangle())
        }
        .dulcetMediaButtonStyle()
        .accessibilityLabel(showingQueue ? DulcetStrings.hideUpNext : DulcetStrings.showUpNext)
        .accessibilityIdentifier("dulcet.now-playing.up-next")
    }

    @ViewBuilder
    private func footerControl(_ control: FooterControl) -> some View {
        switch control {
        case .favourite: favouriteButton
        case .rating: ratingControl
        case .sleepTimer: sleepTimerMenu
        case .lyrics: lyricsToggle
        case .upNext: upNextToggle
        case .airPlay:
#if os(tvOS)
            EmptyView()
#else
            DulcetAirPlayRoutePicker(tint: .primary)
#endif
        }
    }

    private func footerControls(showsQueueToggle: Bool) -> [FooterControl] {
        Self.footerControls(
            offersFavourite: store.nowPlayingFavourite(for: player.current) != nil,
            showsQueueToggle: showsQueueToggle
        )
    }

    /// Up Next, editable, when the queue carries entry identities -- a track can be queued twice,
    /// so edits name entries. A source without identities gets the read-only listing instead.
    @ViewBuilder
    private var queueColumn: some View {
#if os(tvOS)
        ScrollView { queuePanel.padding(DulcetSpacing.xl) }
#else
        Group {
            if player.queueEntries.isEmpty {
                ScrollView { queuePanel.padding(DulcetSpacing.lg) }
            } else {
                DulcetUpNextList(
                    nowPlaying: player,
                    onEdit: onEdit,
                    autoplay: store.listening.map { listening in
                        DulcetAutoplayToggle(
                            isOn: listening.autoplayEnabled,
                            isActive: player.autoplayActive,
                            set: { store.setAutoplay($0) }
                        )
                    }
                )
                    .scrollContentBackground(.hidden)
                    .dulcetQueueDropTarget(store: store, cornerRadius: 12)
            }
        }
#if os(iOS)
        .dulcetPlayerGestureRegion()
#endif
#endif
    }

    private func stackedArtworkSize(innerWidth: CGFloat, availableHeight: CGFloat) -> CGFloat {
        let cap: CGFloat = dynamicTypeSize.isAccessibilitySize ? 220 : 360
        let widthLimit = max(Self.stackedMinimumArtwork, min(innerWidth, cap))
        return Self.fittedArtworkSize(
            widthLimit: widthLimit,
            availableHeight: availableHeight,
            chromeHeight: playerChromeHeight,
            minimum: Self.stackedMinimumArtwork,
            unmeasured: widthLimit
        )
    }

    private func playerPanel(
        artworkSize: CGFloat,
        alignment: HorizontalAlignment,
        showsQueueToggle: Bool
    ) -> some View {
        VStack(alignment: alignment, spacing: Self.coverToTitleSpacing) {
            playerCover(size: artworkSize)
                .anchorPreference(key: DulcetPlayerLayoutKey.self, value: .bounds) { [.cover: $0] }
                .frame(maxWidth: .infinity)

            VStack(alignment: alignment, spacing: 0) {
                trackIdentity(alignment: alignment)
                    .anchorPreference(key: DulcetPlayerLayoutKey.self, value: .bounds) { [.column: $0] }

                playbackProgress
                    .padding(.top, Self.titleToScrubberSpacing)

                transportControls
                    .padding(.top, Self.scrubberToTransportSpacing)

                footer(alignment: alignment, showsQueueToggle: showsQueueToggle)
                    .padding(.top, Self.transportToFooterSpacing)
            }
            // Measured, not estimated: the cover's size is the height this leaves. Its width is
            // the column's, never the cover's, so the measurement does not feed back into itself.
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: {
                playerChromeHeight = $0
            }
        }
    }

    private func playerCover(size: CGFloat) -> some View {
        DulcetPlayerCover(
            artwork: player.current.artwork,
            size: size,
            isPlaying: player.isPlaying,
            // Reduce Motion keeps the cover still: the play state is carried by the control.
            scale: player.isPlaying || presentation == .destination || reduceMotion
                ? 1 : DulcetPlayerCover.pausedScale
        )
        .modifier(DulcetArtworkSwipes(player: player, onControl: onControl, onDismiss: onDismiss))
    }

    private func trackIdentity(alignment: HorizontalAlignment) -> some View {
        let textAlignment: TextAlignment = alignment == .leading ? .leading : .center
        return VStack(alignment: alignment, spacing: DulcetSpacing.xxs) {
            Text(player.current.title)
                .font(presentation == .sheet ? .title2.weight(.bold) : .largeTitle.weight(.bold))
                .multilineTextAlignment(textAlignment)
                .lineLimit(nil)
                .accessibilityIdentifier("dulcet.now-playing.title")
            DulcetArtistLink(
                credits: player.current.credits.filter { $0.role == .artist },
                font: .title3,
                onNavigate: onNavigate
            )
            .multilineTextAlignment(textAlignment)
            if let album = player.current.albumTitle {
                DulcetAlbumLink(track: player.current, title: album, onNavigate: onNavigate)
                    .multilineTextAlignment(textAlignment)
            }
        }
        .frame(maxWidth: .infinity, alignment: Alignment(horizontal: alignment, vertical: .center))
    }

    private var transportControls: some View {
#if os(tvOS)
        // A remote's transport: each control in a slot whose center sits further from its
        // neighbours than a focused glyph's platter reaches (observed: jammed together, the
        // focused Play/Pause pill overlapped Previous and Next), at glyph sizes that read from
        // across a room.
        HStack(spacing: 0) {
            controlButton(
                symbol: "shuffle",
                font: .title3,
                label: DulcetStrings.shuffle,
                value: player.shuffleEnabled ? DulcetStrings.controlOn : DulcetStrings.controlOff
            ) {
                onControl(.setShuffle(!player.shuffleEnabled))
            }
            .dulcetForeground(player.shuffleEnabled ? .accentIconOnWindow : .primaryTextOnWindow)
            .frame(width: Self.televisionTransportEdgeSlot, height: Self.televisionTransportSlotHeight)
            controlButton(
                symbol: "backward.fill",
                font: .system(size: 34, weight: .medium),
                label: DulcetStrings.previous,
                enabled: player.canGoPrevious
            ) {
                onControl(.previous)
            }
            .frame(width: Self.televisionTransportSkipSlot, height: Self.televisionTransportSlotHeight)
            // The glyph itself, as the system player draws it: no disc behind play/pause.
            controlButton(
                symbol: player.isPlaying ? "pause.fill" : "play.fill",
                font: .system(size: 44, weight: .medium),
                label: player.isPlaying ? DulcetStrings.pause : DulcetStrings.play
            ) {
                onControl(player.isPlaying ? .pause : .play)
            }
            .focused($playPauseFocused)
            .frame(width: Self.televisionTransportPlayPauseSlot, height: Self.televisionTransportSlotHeight)
            controlButton(
                symbol: "forward.fill",
                font: .system(size: 34, weight: .medium),
                label: DulcetStrings.next,
                enabled: player.canGoNext
            ) {
                onControl(.next)
            }
            .frame(width: Self.televisionTransportSkipSlot, height: Self.televisionTransportSlotHeight)
            controlButton(
                symbol: repeatSymbol,
                font: .title3,
                label: DulcetStrings.repeatMode,
                value: repeatAccessibilityValue
            ) {
                onControl(.cycleRepeat)
            }
            .dulcetForeground(player.repeatMode != .off ? .accentIconOnWindow : .primaryTextOnWindow)
            .frame(width: Self.televisionTransportEdgeSlot, height: Self.televisionTransportSlotHeight)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
#else
        HStack(spacing: 0) {
            controlButton(
                symbol: "shuffle",
                font: .title3,
                label: DulcetStrings.shuffle,
                value: player.shuffleEnabled ? DulcetStrings.controlOn : DulcetStrings.controlOff
            ) {
                onControl(.setShuffle(!player.shuffleEnabled))
            }
            .dulcetForeground(player.shuffleEnabled ? .accentIconOnWindow : .primaryTextOnWindow)
            Spacer(minLength: DulcetSpacing.xs)
            controlButton(
                symbol: "backward.fill",
                font: .title,
                label: DulcetStrings.previous,
                enabled: player.canGoPrevious
            ) {
                onControl(.previous)
            }
            Spacer(minLength: DulcetSpacing.xs)
            // The glyph itself, as the system player draws it: no disc behind play/pause.
            controlButton(
                symbol: player.isPlaying ? "pause.fill" : "play.fill",
                font: .system(size: 34, weight: .medium),
                label: player.isPlaying ? DulcetStrings.pause : DulcetStrings.play
            ) {
                onControl(player.isPlaying ? .pause : .play)
            }
            Spacer(minLength: DulcetSpacing.xs)
            controlButton(
                symbol: "forward.fill",
                font: .title,
                label: DulcetStrings.next,
                enabled: player.canGoNext
            ) {
                onControl(.next)
            }
            Spacer(minLength: DulcetSpacing.xs)
            controlButton(
                symbol: repeatSymbol,
                font: .title3,
                label: DulcetStrings.repeatMode,
                value: repeatAccessibilityValue
            ) {
                onControl(.cycleRepeat)
            }
            .dulcetForeground(player.repeatMode != .off ? .accentIconOnWindow : .primaryTextOnWindow)
        }
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity)
        // Five controls in one row: at the accessibility text sizes their symbols outgrew an
        // iPhone's width and the outer ones were pushed off screen. The row stops growing at the
        // largest standard size; the title above still grows. ASSUMED, not compared: that the
        // system player caps its transport row the same way.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
#endif
    }

    @ViewBuilder
    private func controlButton(
        symbol: String,
        font: Font,
        label: String,
        enabled: Bool = true,
        value: String? = nil,
        action: @escaping () -> Void
    ) -> some View {
        let button = Button(action: action) {
            Image(systemName: symbol)
                .font(font)
                .symbolRenderingMode(.hierarchical)
                .contentTransition(.symbolEffect(.replace))
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .dulcetMediaButtonStyle()
        // A plain style does not grey its own label when disabled; a control that cannot act
        // is dimmed, as the system player's are.
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.3)
        .accessibilityLabel(label)
        if let value {
            button.accessibilityValue(value)
        } else {
            button
        }
    }

    @ViewBuilder
    private func footer(alignment: HorizontalAlignment, showsQueueToggle: Bool) -> some View {
        VStack(alignment: alignment, spacing: Self.footerStackSpacing) {
#if os(tvOS)
            // The heart, its stars and lyrics sit under the transport, where Down from it lands.
            // One row of equal targets, so every focus platter matches and none touches the next.
            HStack(spacing: DulcetSpacing.lg) {
                ForEach(footerControls(showsQueueToggle: showsQueueToggle), id: \.self) { control in
                    footerControl(control)
                }
            }
            .focusSection()
            // tvOS routes and sets volume with the remote and the TV, so it names the output.
            formatBadge
            Label(DulcetStrings.playingOn(player.outputName), systemImage: "hifispeaker.2")
                .font(.caption)
                .dulcetForeground(.secondaryTextOnWindow)
#else
            if DulcetSystemVolumeSlider.isAvailable {
                DulcetSystemVolumeSlider()
#if os(iOS)
                    .dulcetPlayerGestureRegion()
#endif
            }
            let rows = Self.footerRows(footerControls(showsQueueToggle: showsQueueToggle))
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                HStack(spacing: DulcetSpacing.sm) {
                    // The badge leads the last row, the one with the player's own controls.
                    if index == rows.count - 1 {
                        formatBadge
                    }
                    Spacer(minLength: 0)
                    ForEach(row, id: \.self) { control in
                        footerControl(control)
                    }
                }
                // As the transport row: the stars stop growing at the largest standard size, so
                // five of them never push the heart off a phone's width.
                .dynamicTypeSize(...(row.contains(.rating) ? DynamicTypeSize.xxxLarge : DynamicTypeSize.accessibility5))
            }
#endif
            // Up Next's header already says where the queue is playing from; with the list on
            // screen, a second copy under the controls only repeats it.
            if let sourceDisplayName = player.sourceDisplayName, !upNextListVisible(showsQueueToggle) {
                Text(DulcetStrings.playingFrom(sourceDisplayName))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
        }
        .frame(maxWidth: .infinity, alignment: Alignment(horizontal: alignment, vertical: .center))
        .anchorPreference(key: DulcetPlayerLayoutKey.self, value: .bounds) { [.footer: $0] }
    }

    /// Whether the editable Up Next list, whose header names the queue's source, is on screen
    /// beside this footer: always in the side-by-side layout, and once opened in one column.
    private func upNextListVisible(_ showsQueueToggle: Bool) -> Bool {
#if os(tvOS)
        false
#else
        !player.queueEntries.isEmpty && !showingLyrics && (!showsQueueToggle || showingQueue)
#endif
    }

    private var formatBadge: some View {
        Text(player.audioFormat.sampleRateKilohertz > 0
            ? DulcetStrings.audioFormat(
                codec: player.audioFormat.codec,
                sampleRateKilohertz: player.audioFormat.sampleRateKilohertz
            )
            : player.audioFormat.codec)
            .font(.caption.monospaced())
            // One line, scaled down rather than wrapped: at accessibility sizes the row's
            // controls leave it little width, and a badge wrapped over several lines pushed the
            // footer below a phone's window.
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .dulcetForeground(.secondaryTextOnRegularMaterial)
            .padding(.horizontal, DulcetSpacing.xs)
            .padding(.vertical, DulcetSpacing.xxs)
            .background(.regularMaterial, in: Capsule())
    }

    /// The scrubber, once media time has advanced -- and whenever the player is paused, which
    /// includes a finished queue: its last track stays shown, stopped, with Play replaying it, so
    /// the scrubber says where it is rather than a bare "Paused". Opening and buffering keep
    /// their words; a scrubber there would imply progress that has not happened.
    static func showsProgressIndicator(
        progressBegan: Bool,
        phase: DulcetPlaybackPresentationPhase
    ) -> Bool {
        progressBegan || phase == .paused
    }

    private var playbackProgress: some View {
        VStack(spacing: DulcetSpacing.xxs) {
            if Self.showsProgressIndicator(progressBegan: player.progressBegan, phase: player.phase) {
                // The times flank the bar, as the system player lays them out.
                HStack(alignment: .center, spacing: DulcetSpacing.xs) {
                    Text(displayedElapsed.dulcetDuration)
                        .accessibilityLabel(DulcetStrings.elapsedTime)
                        .accessibilityValue(displayedElapsed.dulcetDuration)
                        .lineLimit(1)
                    playbackProgressIndicator
                        .accessibilityLabel(DulcetStrings.nowPlaying)
                        .accessibilityValue(DulcetStrings.playbackProgress(
                            elapsed: displayedElapsed.dulcetDuration,
                            duration: player.current.duration.dulcetDuration
                        ))
                    Text(DulcetStrings.remaining(displayedRemaining.dulcetDuration))
                        .accessibilityLabel(DulcetStrings.remainingTime)
                        .accessibilityValue(displayedRemaining.dulcetDuration)
                        .lineLimit(1)
                }
                .font(.caption.monospacedDigit())
                .dulcetForeground(.secondaryTextOnWindow)
            } else {
                Text(playbackPhaseLabel)
                    .font(.subheadline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityLabel(playbackPhaseLabel)
                    .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder
    private var playbackProgressIndicator: some View {
#if os(tvOS)
        ProgressView(value: displayedSeconds, total: durationSeconds)
#else
        if player.seekability == .seekable {
            DulcetScrubber(
                value: Binding(
                    get: { displayedSeconds },
                    set: { scrubPosition = $0 }
                ),
                in: 0...durationSeconds,
                onEditingChanged: { editing in
                    guard !editing, let seconds = scrubPosition else { return }
                    scrubPosition = nil
                    onControl(.seek(.milliseconds(Int64((seconds * 1_000).rounded()))))
                }
            )
#if os(iOS)
            .dulcetPlayerGestureRegion()
#endif
        } else {
            ProgressView(value: displayedSeconds, total: durationSeconds)
        }
#endif
    }

    private var displayedSeconds: Double {
        min(max(0, scrubPosition ?? player.elapsed.dulcetSeconds), durationSeconds)
    }

    private var displayedElapsed: Duration {
        .milliseconds(Int64((displayedSeconds * 1_000).rounded()))
    }

    private var displayedRemaining: Duration {
        max(.zero, player.current.duration - displayedElapsed)
    }

    private var durationSeconds: Double {
        max(1, player.current.duration.dulcetSeconds)
    }

    private var playbackPhaseLabel: String {
        switch player.phase {
        case .buffering: DulcetStrings.buffering
        case .paused: DulcetStrings.paused
        case .ready, .progressing: DulcetStrings.readyToPlay
        }
    }

    private var repeatSymbol: String {
        switch player.repeatMode {
        case .off, .all: "repeat"
        case .one: "repeat.1"
        }
    }

    private var repeatAccessibilityValue: String {
        switch player.repeatMode {
        case .off: DulcetStrings.repeatOff
        case .all: DulcetStrings.repeatAll
        case .one: DulcetStrings.repeatOne
        }
    }

    private var queuePanel: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
            Text(DulcetStrings.queue)
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)

            VStack(spacing: 0) {
                ForEach(Array(player.queue.enumerated()), id: \.element.id) { index, track in
                    DulcetTrackRow(
                        track: track,
                        showAlbum: true,
                        index: index + 1,
                        surface: .regularMaterial,
                        isCurrent: index == player.currentIndex
                    )
                    .dulcetTrackContextMenu(track: track, onNavigate: onNavigate)
                    if track.id != player.queue.last?.id {
                        Divider().padding(.leading, DulcetMetrics.denseRowSeparatorInset)
                    }
                }
            }
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .dulcetForeground(.primaryTextOnRegularMaterial)
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.dulcetSeparator.opacity(0.55), lineWidth: 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// What a swipe across the player's artwork asks for: left for the next track, right for the
/// previous one, as the system player does, and down to close a presented player. Each
/// direction must dominate the other, so a diagonal drag does nothing rather than guessing, and
/// a control that is unavailable is not reached by swiping either.
enum DulcetArtworkSwipe: Equatable {
    case next
    case previous
    case dismiss

    static let horizontalThreshold: CGFloat = 80
    static let dismissThreshold: CGFloat = 120

    static func action(
        for translation: CGSize,
        canGoNext: Bool,
        canGoPrevious: Bool,
        canDismiss: Bool
    ) -> DulcetArtworkSwipe? {
        let across = translation.width
        let down = translation.height
        if abs(across) > horizontalThreshold, abs(down) < abs(across) / 2 {
            if across < 0 { return canGoNext ? .next : nil }
            return canGoPrevious ? .previous : nil
        }
        if canDismiss, down > dismissThreshold, abs(across) < down / 2 {
            return .dismiss
        }
        return nil
    }
}

/// Swipes on the artwork: next, previous, and -- for a presented player -- close. The cover
/// follows a horizontal drag a little, so the swipe is seen to be taken; under Reduce Motion it
/// stays still. Touch or pointer drag: an iPad's pointer drives the same gesture, and on a Mac a
/// drag on the cover means nothing.
private struct DulcetArtworkSwipes: ViewModifier {
    let player: DulcetNowPlaying
    let onControl: (DulcetPlaybackControlIntent) -> Void
    let onDismiss: (() -> Void)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @GestureState private var followOffset: CGFloat = 0

    func body(content: Content) -> some View {
#if os(iOS)
        content
            .offset(x: reduceMotion ? 0 : followOffset)
            .animation(reduceMotion ? nil : .spring(duration: 0.3), value: followOffset)
            // Simultaneous: the player sits in a scroll view, whose own pan would otherwise take
            // the drag before this gesture saw it.
            .simultaneousGesture(
                DragGesture(minimumDistance: 24)
                    .updating($followOffset) { value, offset, _ in
                        let across = value.translation.width
                        offset = abs(across) > abs(value.translation.height) ? across * 0.35 : 0
                    }
                    .onEnded { value in
                        let action = DulcetArtworkSwipe.action(
                            for: value.translation,
                            canGoNext: player.canGoNext,
                            canGoPrevious: player.canGoPrevious,
                            canDismiss: onDismiss != nil
                        )
                        DulcetUIProofMarkers.record("swipe:\(action.map { "\($0)" } ?? "none")")
                        switch action {
                        case .next: onControl(.next)
                        case .previous: onControl(.previous)
                        case .dismiss: onDismiss?()
                        case nil: break
                        }
                    }
            )
#else
        content
#endif
    }
}

/// The player's cover: the artwork, its shadow, and its glow behind it, shrunk a little while
/// paused in a presented player.
///
/// The glow is attached before the scale, so it shrinks with the cover: paused, it reaches
/// ``DulcetArtworkGlow/reach`` × ``pausedScale`` past the drawn cover, never further out than
/// ``DulcetArtworkGlow/reach`` past the cover's layout frame, so every gap that clears the glow
/// at full size clears it paused too.
struct DulcetPlayerCover: View {
    /// How far a presented player's cover shrinks while paused.
    static let pausedScale: CGFloat = 0.9
    let artwork: DulcetArtwork
    let size: CGFloat
    let isPlaying: Bool
    let scale: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        DulcetArtworkView(artwork: artwork, size: size)
            .shadow(color: .black.opacity(isPlaying ? 0.28 : 0.14), radius: 18, y: 8)
            .background {
                DulcetArtworkGlow(artwork: artwork, size: size)
            }
            .scaleEffect(scale)
            .animation(reduceMotion ? nil : .spring(duration: 0.4), value: isPlaying)
    }
}

/// The artwork's own colours, blurred, glowing out from behind the cover: the player takes on
/// the album's tint. Deliberately a glow around the cover rather than a blurred background under
/// the whole player: every text colour on the player is a registered contrast pair measured
/// against the window colour, and an arbitrary cover under that text would void the
/// measurement. So the glow is clipped to ``reach`` points past the cover's edge, which is less
/// than every gap between the cover and text (``DulcetNowPlayingView/coverToTitleSpacing`` and
/// its siblings), and fades to nothing before the clip. Reduce Transparency removes it. Not on
/// tvOS, whose player is unchanged.
struct DulcetArtworkGlow: View {
    /// How far past the cover's edge the glow reaches, and not a point further.
    static let reach: CGFloat = DulcetSpacing.sm
    let artwork: DulcetArtwork
    let size: CGFloat
    @Environment(\.accessibilityReduceTransparency) private var systemReduceTransparency
    @Environment(\.dulcetReduceTransparencyOverride) private var reduceTransparencyOverride

    var body: some View {
#if !os(tvOS)
        if !(reduceTransparencyOverride ?? systemReduceTransparency) {
            let outer = size + 2 * Self.reach
            DulcetArtworkView(artwork: artwork, size: size)
                .scaleEffect(outer / max(size, 1))
                // A layout frame of the glow's full extent, so the mask and the clip below are
                // measured from it rather than from the cover's own frame.
                .frame(width: outer, height: outer)
                .blur(radius: Self.reach / 2)
                .opacity(0.55)
                // Fades out inside the clip, so the clip never shows as an edge.
                .mask {
                    RoundedRectangle(cornerRadius: Self.reach * 2, style: .continuous)
                        .padding(Self.reach / 2)
                        .blur(radius: Self.reach / 4)
                }
                .clipped()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
#endif
    }
}

private struct DulcetReduceTransparencyOverrideKey: EnvironmentKey {
    static let defaultValue: Bool? = nil
}

extension EnvironmentValues {
    /// Stands in for the system's Reduce Transparency where a test renders the player: SwiftUI
    /// does not let an environment set `accessibilityReduceTransparency` itself. Nil, the
    /// default, reads the system setting.
    var dulcetReduceTransparencyOverride: Bool? {
        get { self[DulcetReduceTransparencyOverrideKey.self] }
        set { self[DulcetReduceTransparencyOverrideKey.self] = newValue }
    }
}

#if os(iOS)
/// The player presented from the now-playing bar over the whole window. It reads the store, so it
/// follows the queue as tracks change and says so when playback is still opening or has failed.
struct DulcetNowPlayingSheet: View {
    @Bindable var store: DulcetPresentationStore
    var presentation: DulcetNowPlayingView.Presentation = .sheet
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            // The toolbar belongs to this stable container, not to each playback branch.
            // A Group distributes it to its child: short AutoPlay tracks repeatedly replace
            // the close control while moving between the player and Preparing playback.
            ZStack {
                if store.snapshot.playbackFailed {
                    DulcetPlaybackFailedView(
                        failure: store.snapshot.playbackFailure,
                        onControl: store.sendPlaybackControl
                    )
                } else if let player = store.snapshot.nowPlaying {
                    DulcetNowPlayingView(
                        player: player,
                        presentation: presentation,
                        onControl: store.sendPlaybackControl,
                        onEdit: store.editQueue,
                        onNavigate: onClose,
                        onDismiss: nil
                    )
                } else if store.snapshot.playbackStatus == .preparing {
                    DulcetPlaybackPreparingView()
                } else {
                    DulcetUnavailableDestinationView(
                        symbol: "waveform",
                        title: DulcetStrings.nowPlayingUnavailableTitle,
                        message: DulcetStrings.nowPlayingUnavailableBody
                    )
                }
            }
            .toolbarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: onClose) {
                        Image(systemName: "chevron.down")
                            .font(.body.weight(.semibold))
                    }
                    .accessibilityLabel(DulcetStrings.closeNowPlaying)
                    .accessibilityIdentifier("dulcet.now-playing.close")
                }
            }
        }
        // The player has no now-playing bar to draw them above, so it draws its own, along its
        // bottom edge -- clear of the close button in its navigation bar.
        .dulcetPlaybackFeedback(store: store, drawsNotices: true)
        .dulcetUIProofMarkers()
        .background(Color.dulcetWindow.ignoresSafeArea())
    }
}
#endif

#endif
