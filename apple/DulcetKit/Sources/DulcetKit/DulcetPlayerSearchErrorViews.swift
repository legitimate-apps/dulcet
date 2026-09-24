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

    var body: some View {
        let failure = failure ?? .undescribed
        let offersActions = failure.canRetry || failure.canSkip
        VStack(spacing: DulcetSpacing.lg) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 38, weight: .medium))
                .dulcetForeground(.accentIconOnTint)
                .accessibilityHidden(true)
            Text(failure.track.map { DulcetStrings.playbackFailed(title: $0.title) }
                ?? DulcetStrings.nowPlayingFailedTitle)
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("dulcet.now-playing.failure")
            Text(offersActions ? DulcetStrings.playbackFailedActionsBody : DulcetStrings.nowPlayingFailedBody)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 560)
            if offersActions {
                HStack(spacing: DulcetSpacing.sm) {
                    if failure.canRetry {
                        Button(DulcetStrings.playbackRetry, systemImage: "arrow.clockwise") {
                            onControl(.retry)
                        }
                        .buttonStyle(.borderedProminent)
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
            }
        }
        .padding(DulcetSpacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.nowPlaying)
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
                HStack(alignment: .top, spacing: DulcetSpacing.xl) {
                    ScrollView {
                        playerPanel(
                            artworkSize: Self.sideBySideArtworkSize(height: geometry.size.height),
                            alignment: .center,
                            showsQueueToggle: false
                        )
                        .padding(.vertical, DulcetSpacing.xl)
                        // Centred in the window's height when it fits, as the system player
                        // sits; it scrolls only when it does not.
                        .frame(minHeight: geometry.size.height, alignment: .center)
                    }
                    // A player that fits does not rubber-band, so a downward swipe on the
                    // artwork reaches the dismissal rather than bouncing the scroll view.
                    .scrollBounceBehavior(.basedOnSize)
                    // The artwork's shadow reaches past the column; clipping it drew a band.
                    .scrollClipDisabled()
                    .frame(maxWidth: 520)
                    queueColumn
                        .frame(maxWidth: 390)
                }
                .padding(.horizontal, padding)
                .frame(maxWidth: .infinity)
            } else if showingQueue {
                // The queue replaces the artwork, as the system player's does; the footer stays,
                // so the control that opened it closes it.
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    queueColumn
                    footer(alignment: .leading, showsQueueToggle: true)
                        .padding(.horizontal, padding)
                        .padding(.bottom, DulcetSpacing.md)
                }
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
            } else {
                ScrollView {
                    playerPanel(
                        artworkSize: stackedArtworkSize(innerWidth: max(0, width - 2 * padding)),
                        alignment: presentation == .sheet ? .leading : .center,
                        showsQueueToggle: true
                    )
                    .frame(maxWidth: 560)
                    .padding(.horizontal, padding)
                    .padding(.vertical, presentation == .sheet ? DulcetSpacing.md : DulcetSpacing.xl)
                    .frame(maxWidth: .infinity)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .background(Color.dulcetWindow.ignoresSafeArea())
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.nowPlaying)
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
        width < 500 ? DulcetSpacing.lg : DulcetSpacing.xl
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
        VStack(alignment: alignment, spacing: DulcetSpacing.lg) {
            DulcetArtworkView(artwork: player.current.artwork, size: artworkSize)
                .shadow(color: .black.opacity(player.isPlaying ? 0.28 : 0.14), radius: 18, y: 8)
                .scaleEffect(player.isPlaying || presentation == .destination || reduceMotion ? 1 : 0.9)
                // Reduce Motion keeps the cover still: the play state is carried by the control.
                .animation(reduceMotion ? nil : .spring(duration: 0.4), value: player.isPlaying)
                .frame(maxWidth: .infinity)
                .modifier(DulcetSwipeDownToDismiss(onDismiss: onDismiss))

            trackIdentity(alignment: alignment)

            playbackProgress

            transportControls

            footer(alignment: alignment, showsQueueToggle: showsQueueToggle)
        }
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
                symbol: player.shuffleEnabled ? "shuffle.circle.fill" : "shuffle",
                font: .title3,
                label: DulcetStrings.shuffle,
                value: player.shuffleEnabled ? DulcetStrings.controlOn : DulcetStrings.controlOff
            ) {
                onControl(.setShuffle(!player.shuffleEnabled))
            }
            Spacer(minLength: DulcetSpacing.xs)
            controlButton(symbol: "backward.fill", font: .title, label: DulcetStrings.previous) {
                onControl(.previous)
            }
            .disabled(!player.canGoPrevious)
            Spacer(minLength: DulcetSpacing.xs)
            controlButton(
                symbol: player.isPlaying ? "pause.circle.fill" : "play.circle.fill",
                font: .system(size: 56),
                label: player.isPlaying ? DulcetStrings.pause : DulcetStrings.play
            ) {
                onControl(player.isPlaying ? .pause : .play)
            }
            .dulcetForeground(.accentIconOnWindow)
            Spacer(minLength: DulcetSpacing.xs)
            controlButton(symbol: "forward.fill", font: .title, label: DulcetStrings.next) {
                onControl(.next)
            }
            .disabled(!player.canGoNext)
            Spacer(minLength: DulcetSpacing.xs)
            controlButton(
                symbol: repeatSymbol,
                font: .title3,
                label: DulcetStrings.repeatMode,
                value: repeatAccessibilityValue
            ) {
                onControl(.cycleRepeat)
            }
        }
        .frame(maxWidth: 420)
        .frame(maxWidth: .infinity)
        // Five controls in one row: at the accessibility text sizes their symbols outgrew an
        // iPhone's width and the outer ones were pushed off screen. The row stops growing at the
        // largest standard size, as the system player's does; the title above still grows.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    @ViewBuilder
    private func controlButton(
        symbol: String,
        font: Font,
        label: String,
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
            // tvOS routes and sets volume with the remote and the TV, so it names the output.
            formatBadge
            Label(DulcetStrings.playingOn(player.outputName), systemImage: "hifispeaker.2")
                .font(.caption)
                .dulcetForeground(.secondaryTextOnWindow)
#else
            if DulcetSystemVolumeSlider.isAvailable {
                DulcetSystemVolumeSlider()
            }
            HStack(spacing: DulcetSpacing.sm) {
                formatBadge
                Spacer(minLength: 0)
                DulcetAirPlayRoutePicker(tint: .dulcetAccent)
                if showsQueueToggle {
                    Button {
                        withAnimation(reduceMotion ? nil : .snappy) { showingQueue.toggle() }
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
        !player.queueEntries.isEmpty && (!showsQueueToggle || showingQueue)
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
                playbackProgressIndicator
                    .accessibilityLabel(DulcetStrings.nowPlaying)
                    .accessibilityValue(DulcetStrings.playbackProgress(
                        elapsed: displayedElapsed.dulcetDuration,
                        duration: player.current.duration.dulcetDuration
                    ))

                HStack {
                    Text(displayedElapsed.dulcetDuration)
                        .accessibilityLabel(DulcetStrings.elapsedTime)
                        .accessibilityValue(displayedElapsed.dulcetDuration)
                    Spacer()
                    Text(DulcetStrings.remaining(displayedRemaining.dulcetDuration))
                        .accessibilityLabel(DulcetStrings.remainingTime)
                        .accessibilityValue(displayedRemaining.dulcetDuration)
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
            Slider(
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
        case .off: "repeat"
        case .all: "repeat.circle.fill"
        case .one: "repeat.1.circle.fill"
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

/// A downward swipe that closes a presented player. Vertical-dominant only, so a horizontal drag
/// near the artwork never closes it. Nothing where there is no dismissal or no touch.
private struct DulcetSwipeDownToDismiss: ViewModifier {
    let onDismiss: (() -> Void)?

    func body(content: Content) -> some View {
#if os(iOS)
        if let onDismiss {
            // Simultaneous: the player sits in a scroll view, whose own pan would otherwise take
            // the drag before this gesture saw it.
            content.simultaneousGesture(DragGesture(minimumDistance: 24).onEnded { value in
                let down = value.translation.height
                guard down > 120, abs(value.translation.width) < down / 2 else { return }
                onDismiss()
            })
        } else {
            content
        }
#else
        content
#endif
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
        .dulcetQueueEditFeedback(store: store)
        .presentationDragIndicator(.visible)
        .presentationBackground(Color.dulcetWindow)
    }
}
#endif

struct DulcetSearchView: View {
#if os(macOS)
    @State private var selectedResultID: DulcetSearchResult.ID?
#endif
#if os(iOS)
    @FocusState private var searchFieldFocused: Bool
    @Environment(DulcetPresentationStore.self) private var store
#endif
    let snapshot: DulcetSnapshot
    @Binding var searchQuery: String
    let onLoadMore: (DulcetSearchResultKind) -> Void
    let onRetry: () -> Void
    let onActivateResult: (DulcetSearchResult.ID) -> Void
    /// A search command asked for the field's focus; `onFocusRequestHandled` clears the request.
    var focusRequested = false
    var onFocusRequestHandled: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
#if !os(iOS)
                // On iOS the navigation bar's title already says it.
                Text(DulcetStrings.searchTitle)
                    .font(.title.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
#endif
                TextField(DulcetStrings.searchPrompt, text: $searchQuery)
                    .dulcetSearchFieldStyle()
#if os(macOS)
                    // The small AppKit field's SwiftUI ideal height differs from the hosted
                    // NSTextField's runtime intrinsic height. Use the native regular metric so
                    // the header priority below can preserve the control's actual ideal size.
                    .controlSize(.regular)
#else
                    .controlSize(.small)
#endif
#if os(iOS)
                    // A search field takes the platform's search submit key and resigns on
                    // submit, so the keyboard clears the results without a second gesture.
                    .focused($searchFieldFocused)
                    .submitLabel(.search)
                    .onSubmit { searchFieldFocused = false }
                    .onAppear(perform: takeRequestedFocus)
                    .onChange(of: focusRequested) { _, _ in takeRequestedFocus() }
#endif
                    // Deliberately no `.focused` binding on the Mac. MEASURED in the hosted
                    // search proof: with one attached -- even never set -- Return on a selected
                    // result row stopped reaching the table's primary action, so activating a
                    // result from the keyboard silently did nothing.
                    .accessibilityLabel(DulcetStrings.searchPrompt)
                    .accessibilityIdentifier("dulcet.search.field")
                Text(DulcetStrings.searchSummary)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
#if os(macOS)
            // Allocate the header before the flexible result surface, preserving its native field.
            // A vertical fixedSize here also preserves the header's wrapped ideal height during
            // NavigationSplitView's zero-width minimum-size probe. That inflated the navigation
            // minimum above the window height and clipped the first table row out of existence.
            .layoutPriority(1)
#endif

            searchContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(DulcetSpacing.lg)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.search)
    }

#if os(iOS)
    private func takeRequestedFocus() {
        guard focusRequested else { return }
        searchFieldFocused = true
        onFocusRequestHandled()
    }
#endif

    @ViewBuilder
    private var searchContent: some View {
        switch snapshot.state {
        case .searchLoading:
            VStack(spacing: DulcetSpacing.sm) {
                ProgressView()
                Text(DulcetStrings.searchLoading)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
        case .searchResults:
            resultsTable
        case .searchEmpty:
            searchMessage(
                symbol: "magnifyingglass",
                title: DulcetStrings.searchEmptyTitle,
                body: DulcetStrings.searchEmptyBody
            )
        case .searchError:
            VStack(spacing: DulcetSpacing.md) {
                searchMessage(
                    symbol: "exclamationmark.magnifyingglass",
                    title: DulcetStrings.searchErrorTitle,
                    body: DulcetStrings.searchErrorBody
                )
                Button(DulcetStrings.searchRetry, action: onRetry)
                    .buttonStyle(.borderedProminent)
            }
        default:
            searchMessage(
                symbol: "magnifyingglass",
                title: DulcetStrings.searchIdleTitle,
                body: DulcetStrings.searchIdleBody
            )
        }
    }

    private var resultsTable: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.bestMatches)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Text(DulcetStrings.searchResultCount(snapshot.searchResults.count))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
            }

#if os(macOS)
            Table(snapshot.searchResults, selection: $selectedResultID) {
                TableColumn(DulcetStrings.resultColumn) { result in
                    DulcetSearchResultIdentity(
                        result: result,
                        rank: snapshot.searchResults.firstIndex(of: result)
                    )
                }
                .width(min: 320, ideal: 620)

                TableColumn(DulcetStrings.typeColumn) { result in
                    Text(result.kind.displayTitle)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
                .width(min: 72, ideal: 90, max: 120)
            }
            .alternatingRowBackgrounds(.disabled)
            .contextMenu(forSelectionType: DulcetSearchResult.ID.self) { selection in
                if let id = selection.first,
                   let result = snapshot.searchResults.first(where: { $0.id == id }) {
                    DulcetSearchResultMenuItems(result: result) {
                        selectedResultID = id
                        onActivateResult(id)
                    }
                }
            } primaryAction: { selection in
                guard let id = selection.first else { return }
                selectedResultID = id
                onActivateResult(id)
            }
#else
            // Selection is the macOS Table's idiom; touch and the remote share the library's
            // single-activation idiom instead -- one press on a row routes its semantic action
            // (play a track, open an album or artist) through onActivateResult.
            ScrollView {
                LazyVStack(spacing: DulcetSpacing.sm) {
                    ForEach(Array(snapshot.searchResults.enumerated()), id: \.element.id) { index, result in
                        Button {
                            onActivateResult(result.id)
                        } label: {
                            HStack(spacing: DulcetSpacing.md) {
                                DulcetSearchResultIdentity(result: result, rank: index)
                                Spacer(minLength: DulcetSpacing.md)
                                Text(result.kind.displayTitle)
                                    .font(.callout.weight(.semibold))
                                    .dulcetForeground(.secondaryTextOnWindow)
                            }
                            .padding(DulcetSpacing.sm)
                            .contentShape(Rectangle())
                        }
                        .dulcetMediaButtonStyle()
                        // A button flattens its children into one accessibility element, so the
                        // stable rank identifier lives on the button here; on macOS it stays on
                        // the row's title text inside the Table.
                        .accessibilityIdentifier("dulcet.search.result.\(index)")
                        .accessibilityLabel(DulcetStrings.searchResultAccessibility(
                            title: result.title,
                            subtitle: result.subtitle,
                            kind: result.kind.displayTitle
                        ))
#if os(iOS)
                        .contextMenu {
                            DulcetSearchResultMenuItems(result: result) {
                                onActivateResult(result.id)
                            }
                        } preview: {
                            DulcetContextMenuPreview(
                                store: store,
                                artwork: result.artwork,
                                title: result.title,
                                subtitle: result.subtitle
                            )
                        }
                        .dulcetQueueDragSource(
                            store: store,
                            artwork: result.artwork,
                            title: result.title,
                            isEnabled: result.playableTrack != nil
                        ) { result.playableTrack.map(DulcetQueueAddition.searchResult) }
#endif
                    }
                }
            }
#if os(iOS)
            // `automatic` would already dismiss on scroll here - it only keeps the keyboard for
            // a TextEditor, and this is plain scrollable content. What `interactively` adds is that
            // the keyboard tracks the drag itself, so a partial drag can be reversed to cancel the
            // dismissal instead of committing to it the moment the scroll begins.
            .scrollDismissesKeyboard(.interactively)
#endif
#endif

            HStack(spacing: DulcetSpacing.xs) {
                ForEach(pagedKinds, id: \.rawValue) { kind in
                    Button(loadMoreTitle(for: kind)) {
                        onLoadMore(kind)
                    }
                    .disabled(snapshot.searchLoadingMoreKind != nil)
                }
                if snapshot.searchLoadingMoreKind != nil {
                    ProgressView()
                        .controlSize(.small)
                    Text(DulcetStrings.loadingMore)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
            }
        }
    }

    private var pagedKinds: [DulcetSearchResultKind] {
        [.track, .album, .artist].filter(snapshot.searchHasMoreKinds.contains)
    }

    private func loadMoreTitle(for kind: DulcetSearchResultKind) -> String {
        switch kind {
        case .track: DulcetStrings.loadMoreTracks
        case .album: DulcetStrings.loadMoreAlbums
        case .artist: DulcetStrings.loadMoreArtists
        }
    }

    private func searchMessage(symbol: String, title: String, body: String) -> some View {
        VStack(spacing: DulcetSpacing.sm) {
            Image(systemName: symbol)
                .font(.system(size: 36, weight: .medium))
                .dulcetForeground(.secondaryTextOnWindow)
                .accessibilityHidden(true)
            Text(title)
                .font(.title2.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text(body)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
        }
        .frame(maxWidth: 440)
    }
}

#if os(iOS) || os(macOS)
/// A search result's context menu: its own activation (play a track, open an album or artist),
/// then Go to Album and Go to Artist where the library has those pages.
private struct DulcetSearchResultMenuItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    let result: DulcetSearchResult
    let onActivate: () -> Void

    var body: some View {
        switch result.kind {
        case .track:
            Button(DulcetStrings.play, systemImage: "play", action: onActivate)
            if let track = result.playableTrack {
                DulcetQueueInsertionMenuItems(addition: .searchResult(track))
            }
            if let track = result.playableTrack, let albumID = store.libraryAlbumID(for: track) {
                Button(DulcetStrings.goToAlbum, systemImage: "square.stack") {
                    store.showAlbum(albumID)
                }
            }
        case .album:
            Button(DulcetStrings.goToAlbum, systemImage: "square.stack", action: onActivate)
        case .artist:
            Button(DulcetStrings.goToArtist, systemImage: "music.mic", action: onActivate)
        }
        if result.kind != .artist {
            ForEach(artistTargets, id: \.id) { target in
                Button(
                    artistTargets.count == 1 ? DulcetStrings.goToArtist : target.name,
                    systemImage: "music.mic"
                ) {
                    store.showArtist(target.id)
                }
            }
        }
    }

    private var artistTargets: [(name: String, id: DulcetProviderItemID)] {
        var seen = Set<DulcetProviderItemID>()
        return result.credits.compactMap { credit in
            guard let id = store.libraryArtistID(for: credit), seen.insert(id).inserted else {
                return nil
            }
            return (credit.name, id)
        }
    }
}
#endif

private extension View {
    @ViewBuilder
    func dulcetSearchFieldStyle() -> some View {
#if os(tvOS)
        textFieldStyle(.plain)
            .padding(.horizontal, DulcetSpacing.md)
            .padding(.vertical, DulcetSpacing.sm)
            .background(Color.dulcetControl, in: RoundedRectangle(cornerRadius: 14))
#else
        textFieldStyle(.roundedBorder)
#endif
    }
}

private struct DulcetSearchResultIdentity: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let result: DulcetSearchResult
    let rank: Int?

    var body: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
            DulcetArtworkView(artwork: result.artwork, size: DulcetMetrics.denseRowArtworkSize)

            VStack(alignment: .leading, spacing: 0) {
                Text(result.title)
                    .font(.callout.weight(.medium))
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
#if os(macOS)
                    // iOS/tvOS rows are buttons, which absorb child identifiers into the row's
                    // own accessibility element; there the button carries this identifier.
                    .accessibilityIdentifier(rank.map { "dulcet.search.result.\($0)" } ?? "")
#endif
                if !result.subtitle.isEmpty {
                    Text(result.subtitle)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                }
            }
        }
        .accessibilityLabel(DulcetStrings.searchResultAccessibility(
            title: result.title,
            subtitle: result.subtitle,
            kind: result.kind.displayTitle
        ))
    }
}

extension DulcetSearchResultKind {
    var displayTitle: String {
        switch self {
        case .track: DulcetStrings.track
        case .album: DulcetStrings.album
        case .artist: DulcetStrings.artist
        }
    }
}

struct DulcetTLSUntrustedView: View {
    let failure: DulcetTLSFailure
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

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
