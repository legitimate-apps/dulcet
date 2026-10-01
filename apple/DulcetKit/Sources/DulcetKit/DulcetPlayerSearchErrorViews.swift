#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

struct DulcetPlaybackPreparingView: View {
    var body: some View {
        VStack(spacing: DulcetSpacing.md) {
            ProgressView()
                .controlSize(.large)
            Text(DulcetStrings.nowPlayingPreparingTitle)
                .font(.title2.weight(.semibold))
            Text(DulcetStrings.nowPlayingPreparingBody)
                .font(.body)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
        }
        .padding(DulcetSpacing.xl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.nowPlaying)
    }
}

/// A track that could not be played, said plainly with its name, and what can be done about
/// it: Try Again and Skip wherever the controller says they can act, and otherwise the way back
/// to the library. The same view on every platform, so a failure reads the same everywhere.
struct DulcetPlaybackFailedView: View {
    let failure: DulcetFailedPlayback?
    let onControl: (DulcetPlaybackControlIntent) -> Void
#if os(iOS)
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
#endif

    var body: some View {
        let failure = failure ?? .undescribed
#if os(iOS)
        // At the accessibility text sizes the name, the message and the two actions outgrow a
        // phone, and a fixed frame truncated all four to a few letters (OBSERVED on an iPhone SE at
        // the largest size). The text wraps, the actions stack, and the page scrolls when it has
        // to; centred in the height when it fits, as before.
        GeometryReader { geometry in
            ScrollView {
                content(failure)
                    .padding(dynamicTypeSize.isAccessibilitySize ? DulcetSpacing.lg : DulcetSpacing.xxl)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.nowPlaying)
#else
        content(failure)
            .padding(DulcetSpacing.xxl)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.dulcetWindow)
            .dulcetForeground(.primaryTextOnWindow)
            .navigationTitle(DulcetStrings.nowPlaying)
#endif
    }

    private func content(_ failure: DulcetFailedPlayback) -> some View {
        VStack(spacing: DulcetSpacing.lg) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 38, weight: .medium))
                .dulcetForeground(.accentIconOnTint)
                .accessibilityHidden(true)
            Text(failure.track.map { DulcetStrings.playbackFailed(title: $0.title) }
                ?? DulcetStrings.nowPlayingFailedTitle)
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("dulcet.now-playing.failure")
            Text(Self.message(for: failure))
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 560)
            if failure.canRetry || failure.canSkip {
                // Side by side while both labels fit; stacked otherwise, rather than squeezed.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: DulcetSpacing.sm) { actions(failure) }
                    VStack(spacing: DulcetSpacing.sm) { actions(failure) }
                }
            }
        }
    }

    @ViewBuilder
    private func actions(_ failure: DulcetFailedPlayback) -> some View {
        if failure.canRetry {
            DulcetProminentAction(DulcetStrings.playbackRetry, systemImage: "arrow.clockwise") {
                onControl(.retry)
            }
            .accessibilityIdentifier("dulcet.now-playing.retry")
        }
        if failure.canSkip {
            Button(DulcetStrings.playbackSkipShort, systemImage: "forward.end.fill") {
                onControl(.next)
            }
            .dulcetSecondaryActionStyle()
            .accessibilityLabel(DulcetStrings.playbackSkip)
            .accessibilityIdentifier("dulcet.now-playing.skip")
        }
    }

    /// What the player says under the failed track's name: what happened, and only what is on
    /// offer. Skipping is mentioned only when Skip is shown, and a track that played and then
    /// stopped is not said to have failed to start.
    static func message(for failure: DulcetFailedPlayback) -> String {
        switch (failure.stoppedPartway, failure.canRetry, failure.canSkip) {
        case (_, false, false): DulcetStrings.nowPlayingFailedBody
        case (false, true, true): DulcetStrings.playbackFailedToStartRetryOrSkip
        case (false, true, false): DulcetStrings.playbackFailedToStartRetry
        case (false, false, true): DulcetStrings.playbackFailedToStartSkip
        case (true, true, true): DulcetStrings.playbackStoppedPartwayRetryOrSkip
        case (true, true, false): DulcetStrings.playbackStoppedPartwayRetry
        case (true, false, true): DulcetStrings.playbackStoppedPartwaySkip
        }
    }
}

struct DulcetNowPlayingView: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    enum Presentation {
        /// A navigation destination: the sidebar's Now Playing on iPad and Mac, the tvOS section.
        case destination
        /// The player presented from the iPhone's now-playing bar, as a sheet dragged down to
        /// dismiss.
        case sheet
        /// The player covering an iPad's whole window, presented from its now-playing bar.
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
                let artworkSize = Self.sideBySideArtworkSize(height: geometry.size.height)
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
                }
                .padding(.horizontal, padding)
                .frame(maxWidth: .infinity)
            } else if showingLyrics {
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    lyricsPanel
                        .padding(.horizontal, padding)
                    footer(alignment: .leading, showsQueueToggle: true)
                        .padding(.horizontal, padding)
                        .padding(.bottom, DulcetSpacing.md)
                }
                .frame(maxWidth: Self.oneColumnLyricsMaxWidth)
                .frame(maxWidth: .infinity)
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
                // screen, and Down from the section bar found nothing to land on.
                ScrollView {
                    HStack(alignment: .center, spacing: DulcetSpacing.xxl) {
                        playerCover(size: Self.televisionArtworkSize(height: geometry.size.height))
                        VStack(alignment: .center, spacing: Self.coverToTitleSpacing) {
                            trackIdentity(alignment: .center)
                            playbackProgress
                            transportControls
                            footer(alignment: .center, showsQueueToggle: true)
                        }
                        .frame(maxWidth: Self.televisionControlsWidth)
                    }
                    .padding(.horizontal, padding)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: geometry.size.height, alignment: .center)
                }
                .scrollBounceBehavior(.basedOnSize)
#else
                ScrollView {
                    playerPanel(
                        artworkSize: stackedArtworkSize(innerWidth: max(0, width - 2 * padding)),
                        alignment: presentation == .sheet ? .leading : .center,
                        showsQueueToggle: true
                    )
                    .frame(maxWidth: 560)
                    .padding(.horizontal, padding)
                    .padding(.vertical, presentation == .sheet ? Self.sheetVerticalPadding : DulcetSpacing.xl)
                    .frame(maxWidth: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
#endif
            }
        }
        .background(Color.dulcetWindow.ignoresSafeArea())
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.nowPlaying)
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
    static let playerToQueueSpacing = DulcetSpacing.xl
    static let sheetVerticalPadding = DulcetSpacing.md
    /// Each of the player's rating stars: a touch target, and on a television a focus target.
    static var ratingStarSide: CGFloat {
#if os(tvOS)
        64
#else
        44
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
        return marks + [.lyrics]
#elseif os(macOS)
        return [.airPlay, .lyrics] + (showsQueueToggle ? [.upNext] : [])
#else
        return marks + [.airPlay, .lyrics] + (showsQueueToggle ? [.upNext] : [])
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

    /// Artwork as large as the player column allows while the title, scrubber, transport and
    /// footer still fit beneath it without scrolling: an iPad 13-inch window gets the full
    /// column, an iPad mini in landscape a smaller cover rather than controls pushed off screen.
    static func sideBySideArtworkSize(height: CGFloat) -> CGFloat {
        min(520, max(280, height - 380))
    }

    /// Apple TV's cover, beside the controls: as large as the height allows after the vertical
    /// margins, and never so large that the controls' column is squeezed, or so small it stops
    /// reading as the cover from across a room.
    static func televisionArtworkSize(height: CGFloat) -> CGFloat {
        min(560, max(280, height - 2 * DulcetSpacing.xl - 120))
    }

    /// Apple TV's controls column: wide enough that a long title is not broken after two words.
    static let televisionControlsWidth: CGFloat = 760

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

    private var lyricsPanel: some View {
        DulcetLyricsPanel(track: player.current, elapsed: player.elapsed, isPlaying: player.isPlaying)
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
                .frame(minWidth: 44, minHeight: 44)
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
        DulcetNowPlayingFavouriteButton(track: player.current, size: .title3, minimumSide: 44)
#if os(tvOS)
            .focused($focusedFooterControl, equals: .favourite)
#endif
    }

    /// The playing track's stars (§16.20), beside the heart.
    private var ratingControl: some View {
        DulcetNowPlayingRatingControl(track: player.current, size: .title3, minimumSide: Self.ratingStarSide)
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
                .frame(minWidth: 44, minHeight: 44)
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
        if player.queueEntries.isEmpty {
            ScrollView { queuePanel.padding(DulcetSpacing.lg) }
        } else {
            DulcetUpNextList(nowPlaying: player, onEdit: onEdit)
                .scrollContentBackground(.hidden)
                .dulcetQueueDropTarget(store: store, cornerRadius: 12)
        }
#endif
    }

    private func stackedArtworkSize(innerWidth: CGFloat) -> CGFloat {
        let cap: CGFloat = dynamicTypeSize.isAccessibilitySize ? 220 : 360
        return max(120, min(innerWidth, cap))
    }

    private func playerPanel(
        artworkSize: CGFloat,
        alignment: HorizontalAlignment,
        showsQueueToggle: Bool
    ) -> some View {
        VStack(alignment: alignment, spacing: Self.coverToTitleSpacing) {
            playerCover(size: artworkSize)
                .frame(maxWidth: .infinity)

            trackIdentity(alignment: alignment)

            playbackProgress

            transportControls

            footer(alignment: alignment, showsQueueToggle: showsQueueToggle)
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
#if os(tvOS)
            .focused($playPauseFocused)
#endif
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
        VStack(alignment: alignment, spacing: DulcetSpacing.sm) {
#if os(tvOS)
            // The heart, its stars and lyrics sit under the transport, where Down from it lands.
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
/// The player presented from the now-playing bar: a sheet dragged down to dismiss on a compact
/// width, a cover over the whole window on a regular one. It reads the store directly, so it
/// follows the queue as tracks change and says so when playback is still opening or has failed.
struct DulcetNowPlayingSheet: View {
    @Bindable var store: DulcetPresentationStore
    var presentation: DulcetNowPlayingView.Presentation = .sheet
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            Group {
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
                        onDismiss: presentation == .fullScreen ? onClose : nil
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
        .presentationDragIndicator(.visible)
        .presentationBackground(Color.dulcetWindow)
    }
}
#endif

struct DulcetTLSUntrustedView: View {
    let failure: DulcetTLSFailure
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
#if os(macOS)
    @Environment(\.controlActiveState) private var controlActiveState
#endif

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                // Dynamic Type is the semantic reason this header needs to stack. Keep that
                // decision explicit so ordinary window-width changes do not alter a layout
                // that already fits, while accessibility sizes receive the intended reading
                // order.
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        shield
                        heading
                    }
                } else {
                    HStack(alignment: .top, spacing: DulcetSpacing.sm) {
                        shield
                        heading
                    }
                }

                whyPanel
                remedyPanel

                HStack(spacing: DulcetSpacing.sm) {
#if !os(tvOS)
                    Link(destination: DulcetLinks.certificateInstallationGuide) {
                        Label(DulcetStrings.openCertificateHelp, systemImage: "key.horizontal")
                            .dulcetForeground(prominentLinkPair)
                    }
                        .buttonStyle(.borderedProminent)
                        .dulcetDefaultActionShortcut()
                        .accessibilityLabel(DulcetStrings.openCertificateHelp)
#endif
                    Button(DulcetStrings.connectionSettings, systemImage: "slider.horizontal.3") {}
                        .dulcetSecondaryActionStyle()
                        .accessibilityLabel(DulcetStrings.connectionSettings)
                }
            }
            .padding(DulcetSpacing.md)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.settings)
    }

    /// The prominent help link's label pair, as ``DulcetProminentAction`` picks it: the
    /// accent-fill pair while the window is key, the control's text colour while it is not.
    private var prominentLinkPair: DulcetRegisteredContrastPair {
#if os(macOS)
        controlActiveState == .key ? .labelOnAccentFill : .primaryTextOnControl
#else
        .labelOnAccentFill
#endif
    }

    private var shield: some View {
        ZStack {
            Circle()
                .fill(Color.dulcetDanger.opacity(0.11))
                .frame(width: 56, height: 56)
            Image(systemName: "exclamationmark.shield.fill")
                .font(.system(size: 26, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .dulcetForeground(.dangerIconOnTint)
                .accessibilityHidden(true)
        }
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(DulcetStrings.tlsTitle)
                .font(.title.weight(.bold))
                .lineLimit(nil)
                .accessibilityAddTraits(.isHeader)
            Text(failure.serverName)
                .font(.headline)
                .lineLimit(nil)
            Text(DulcetStrings.tlsBody)
                .font(.callout)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
        }
    }

    @ViewBuilder
    private var whyPanel: some View {
#if os(tvOS)
        DulcetTVInformationPanel(title: DulcetStrings.tlsWhy) {
            whyPanelContent
        }
#else
        GroupBox(DulcetStrings.tlsWhy) {
            whyPanelContent
        }
#endif
    }

    private var whyPanelContent: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
            Text(failure.reason)
                .font(.headline)
                .lineLimit(nil)
            Text(failure.technicalDetail)
                .font(.callout)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var remedyPanel: some View {
#if os(tvOS)
        DulcetTVInformationPanel(title: DulcetStrings.tlsRemedyTitle) {
            remedyPanelContent
        }
#else
        GroupBox(DulcetStrings.tlsRemedyTitle) {
            remedyPanelContent
        }
#endif
    }

    private var remedyPanelContent: some View {
        Label {
            Text(DulcetStrings.tlsRemedyBody)
                .lineLimit(nil)
        } icon: {
            Image(systemName: "checkmark.shield")
                .dulcetForeground(.accentIconOnWindow)
        }
        .font(.callout)
    }
}

#if os(tvOS)
private struct DulcetTVInformationPanel<Content: View>: View {
    let title: String
    let content: Content

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            Text(title)
                .font(.headline)
            content
        }
        .padding(DulcetSpacing.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.dulcetControl.opacity(0.72), in: RoundedRectangle(cornerRadius: 18))
    }
}
#endif

struct DulcetDeliberatelyBadControlView: View {
    let snapshot: DulcetSnapshot

    var body: some View {
        HStack(spacing: 3) {
            VStack(alignment: .leading, spacing: 29) {
                Text(DulcetStrings.controlBad)
                    .font(.system(size: 9, weight: .black, design: .rounded))
                    .foregroundStyle(badAccent) // dulcet-contrast-waiver: deliberately-bad-control
                Text(DulcetStrings.library)
                    .font(.system(size: 31, weight: .thin, design: .serif))
                    .foregroundStyle(Color.green) // dulcet-contrast-waiver: deliberately-bad-control
                Text(DulcetStrings.search)
                    .font(.caption2)
                Text(DulcetStrings.nowPlaying)
                    .font(.title2.monospaced())
                    .foregroundStyle(Color.cyan) // dulcet-contrast-waiver: deliberately-bad-control
                Spacer()
                Button(DulcetStrings.playAll) {}
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                    .accessibilityLabel(DulcetStrings.playAll)
            }
            .padding(.top, 7)
            .padding(.horizontal, 13)
            .frame(width: 184)
            .background(Color(red: 0.22, green: 0.28, blue: 0.03))

            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(DulcetStrings.library)
                        .font(.system(size: 54, weight: .heavy, design: .rounded))
                        .foregroundStyle(badAccent) // dulcet-contrast-waiver: deliberately-bad-control
                    Spacer()
                    Button(DulcetStrings.shuffle) {}
                        .buttonStyle(.borderedProminent)
                        .tint(.green)
                        .accessibilityLabel(DulcetStrings.shuffle)
                }

                HStack(alignment: .top, spacing: 7) {
                    ForEach(snapshot.albums.prefix(4)) { album in
                        VStack(alignment: .leading, spacing: album.tracks.count.isMultiple(of: 2) ? 2 : 13) {
                            DulcetArtworkView(artwork: album.artwork, size: album.tracks.count > 10 ? 92 : 142)
                                .shadow(color: badAccent.opacity(0.8), radius: 17, x: 9, y: 11)
                            Text(album.title)
                                .font(album.tracks.count > 10 ? .caption2 : .largeTitle)
                                .foregroundStyle(album.tracks.count > 10 ? Color.green : Color.primary) // dulcet-contrast-waiver: deliberately-bad-control
                                .lineLimit(1)
                        }
                        .padding(album.tracks.count.isMultiple(of: 2) ? 3 : 17)
                        .background(Color.cyan.opacity(0.18), in: RoundedRectangle(cornerRadius: 27))
                    }
                }

                Text(snapshot.albums.first?.tracks.first?.title ?? DulcetStrings.controlBad)
                    .font(.system(size: 8, weight: .ultraLight, design: .rounded))
                    .padding(.top, 29)
                    .foregroundStyle(badAccent) // dulcet-contrast-waiver: deliberately-bad-control

                Spacer()
                RoundedRectangle(cornerRadius: 31)
                    .fill(badAccent)
                    .frame(height: 17)
                    .overlay(alignment: .leading) {
                        Text(DulcetStrings.nowPlaying)
                            .font(.system(size: 7, design: .serif))
                            .foregroundStyle(Color.green) // dulcet-contrast-waiver: deliberately-bad-control
                            .padding(.leading, 71)
                    }
            }
            .padding(.leading, 3)
            .padding(.trailing, 29)
            .padding(.vertical, 13)
        }
        .background(Color.yellow.opacity(0.22))
        .tint(badAccent)
    }

    private var badAccent: Color {
        Color(red: 1, green: 0, blue: 0.78)
    }
}
#endif
