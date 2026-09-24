#if os(macOS) || os(iOS)
import SwiftUI

/// The persistent now-playing bar: what is playing, play/pause and next, on every screen while a
/// queue exists. Activating the bar itself opens the full player.
///
/// It reads the snapshot's playback status as well as its now-playing value, so a track that is
/// still opening, or one that failed, is said out loud rather than making the bar vanish and
/// reappear between tracks. A failed track is not a dead end: the bar names it and offers Try
/// Again and Skip in place of play/pause and next, and can be dismissed.
struct DulcetNowPlayingBar: View {
    enum Style {
        /// Artwork, title, play/pause and next. iPhone and iPad.
        case compact
        /// Adds previous and the elapsed/remaining progress. The Mac window's bottom bar.
        case expanded
    }

    @Bindable var store: DulcetPresentationStore
    var style: Style = .compact
    let onOpen: () -> Void

    static let identifier = "dulcet.mini-player"

    var body: some View {
        HStack(spacing: style == .expanded ? DulcetSpacing.md : DulcetSpacing.sm) {
            openButton
            if style == .expanded {
                Spacer(minLength: DulcetSpacing.sm)
                if let player = store.snapshot.nowPlaying {
                    DulcetNowPlayingBarProgress(player: player)
                        .frame(maxWidth: 420)
                }
                Spacer(minLength: DulcetSpacing.sm)
            }
            if store.snapshot.playbackFailed {
                failureActions
            } else {
                transport
            }
        }
        .padding(.leading, DulcetSpacing.xs)
        .padding(.trailing, DulcetSpacing.sm)
        .padding(.vertical, DulcetSpacing.xs)
        // A bar that grew with accessibility text sizes would cover the screen it floats over
        // and push its own controls off the edge; the full player is where large text goes.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(Self.identifier)
    }

    private var openButton: some View {
        Button(action: onOpen) {
            HStack(spacing: DulcetSpacing.sm) {
                artwork
                identity
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .dulcetHoverEffect()
        .accessibilityIdentifier("\(Self.identifier).open")
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(DulcetStrings.openNowPlayingHint)
        .dulcetForeground(.primaryTextOnRegularMaterial)
    }

    @ViewBuilder
    private var artwork: some View {
        let size: CGFloat = style == .expanded ? 44 : 40
        if !store.snapshot.playbackFailed, let player = store.snapshot.nowPlaying {
            DulcetArtworkView(artwork: player.current.artwork, size: size)
                .shadow(color: .black.opacity(0.12), radius: 3, y: 1)
        } else {
            RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius * 0.6, style: .continuous)
                .fill(Color.dulcetControl)
                .frame(width: size, height: size)
                .overlay {
                    if store.snapshot.playbackStatus == .preparing {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "exclamationmark.triangle")
                            .dulcetForeground(.secondaryTextOnControl)
                    }
                }
                .accessibilityHidden(true)
        }
    }

    /// One line each while playing, as a music player's bar is. A failure's lines may take two:
    /// with three actions beside them, a narrow phone at the largest text size cut both the track
    /// and "Couldn't play this track" to their first words, and that line is the one that must
    /// be read.
    private var identity: some View {
        let lines = store.snapshot.playbackFailed ? 2 : 1
        return VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(lines)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("\(Self.identifier).title")
            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnRegularMaterial)
                    .lineLimit(lines)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var transport: some View {
        HStack(spacing: style == .expanded ? DulcetSpacing.sm : DulcetSpacing.xxs) {
            let player = store.snapshot.nowPlaying
            if style == .expanded {
                transportButton(
                    symbol: "backward.fill",
                    label: DulcetStrings.previous,
                    identifier: "previous",
                    enabled: player?.canGoPrevious == true
                ) { store.sendPlaybackControl(.previous) }
            }
            transportButton(
                symbol: player?.isPlaying == true ? "pause.fill" : "play.fill",
                label: player?.isPlaying == true ? DulcetStrings.pause : DulcetStrings.play,
                identifier: "play-pause",
                enabled: player != nil,
                prominent: true
            ) {
                store.sendPlaybackControl(player?.isPlaying == true ? .pause : .play)
            }
            transportButton(
                symbol: "forward.fill",
                label: DulcetStrings.next,
                identifier: "next",
                enabled: player?.canGoNext == true
            ) { store.sendPlaybackControl(.next) }
        }
        .dulcetForeground(.primaryTextOnRegularMaterial)
    }

    /// Try Again, Skip and Dismiss, in place of play/pause and next while the track has failed.
    /// Skip is shown only when it has somewhere to go, as in the player: a disabled Skip here and
    /// none there said two different things about the same failure.
    private var failureActions: some View {
        let failure = store.snapshot.playbackFailure ?? .undescribed
        return HStack(spacing: style == .expanded ? DulcetSpacing.sm : DulcetSpacing.xxs) {
            if failure.canRetry {
                transportButton(
                    symbol: "arrow.clockwise",
                    label: DulcetStrings.playbackRetry,
                    identifier: "retry",
                    enabled: true
                ) { store.sendPlaybackControl(.retry) }
            }
            if failure.canSkip {
                transportButton(
                    symbol: "forward.end.fill",
                    label: DulcetStrings.playbackSkip,
                    identifier: "skip",
                    enabled: true
                ) { store.sendPlaybackControl(.next) }
            }
            transportButton(
                symbol: "xmark",
                label: DulcetStrings.playbackFailureDismiss,
                identifier: "dismiss",
                enabled: true
            ) { store.dismissPlaybackFailure() }
        }
        .dulcetForeground(.primaryTextOnRegularMaterial)
    }

    private func transportButton(
        symbol: String,
        label: String,
        identifier: String,
        enabled: Bool,
        prominent: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(prominent ? .title2 : .title3)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .dulcetHoverEffect()
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .accessibilityLabel(label)
        .accessibilityIdentifier("\(Self.identifier).\(identifier)")
#if os(macOS)
        .help(label)
#endif
    }

    /// The failed track's own name leads, as a playing track's does -- a bar this narrow
    /// truncated "Couldn't play" plus the name to the first letters of the name -- and the line
    /// under it says it failed. VoiceOver hears the failure first.
    private var failedTrack: DulcetTrack? {
        store.snapshot.playbackFailed ? store.snapshot.playbackFailure?.track : nil
    }

    private var title: String {
        if store.snapshot.playbackFailed {
            return failedTrack?.title ?? DulcetStrings.playbackFailedShort
        }
        if let player = store.snapshot.nowPlaying { return player.current.title }
        return DulcetStrings.playbackLoading
    }

    private var subtitle: String? {
        if store.snapshot.playbackFailed {
            return failedTrack == nil ? nil : store.snapshot.playbackFailure.map(Self.failureLine(for:))
        }
        return artists(of: store.snapshot.nowPlaying?.current)
    }

    private func artists(of track: DulcetTrack?) -> String? {
        guard let track else { return nil }
        let artists = DulcetStrings.artistNames(track.artistNames)
        return artists.isEmpty ? nil : artists
    }

    private var accessibilityLabel: String {
        if store.snapshot.playbackFailed {
            return DulcetStrings.miniPlayerAccessibility(
                title: store.snapshot.playbackFailure.map(Self.failureAnnouncement(for:))
                    ?? DulcetStrings.playbackFailedShort,
                artists: artists(of: failedTrack) ?? ""
            )
        }
        return DulcetStrings.miniPlayerAccessibility(title: title, artists: subtitle ?? "")
    }

    /// The line under a failed track's name. It says what the player says (§3.1): a track that
    /// played and then stopped stopped partway, and is not said to have failed to play.
    static func failureLine(for failure: DulcetFailedPlayback) -> String {
        failure.stoppedPartway ? DulcetStrings.playbackStoppedPartwayShort : DulcetStrings.playbackFailedShort
    }

    /// What VoiceOver hears first for a failed track, drawn from the same distinction.
    static func failureAnnouncement(for failure: DulcetFailedPlayback) -> String {
        guard let track = failure.track else { return failureLine(for: failure) }
        return failure.stoppedPartway
            ? DulcetStrings.playbackStoppedPartway(title: track.title)
            : DulcetStrings.playbackFailed(title: track.title)
    }
}

/// The expanded bar's elapsed/remaining line. Read-only: seeking lives in the full player.
private struct DulcetNowPlayingBarProgress: View {
    let player: DulcetNowPlaying

    var body: some View {
        VStack(spacing: 2) {
            ProgressView(value: fraction)
                .progressViewStyle(.linear)
                .tint(.secondary)
            HStack {
                Text(player.elapsed.dulcetDuration)
                Spacer()
                Text(DulcetStrings.remaining(remaining.dulcetDuration))
            }
            .font(.caption2.monospacedDigit())
            .dulcetForeground(.secondaryTextOnRegularMaterial)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(DulcetStrings.nowPlaying)
        .accessibilityValue(DulcetStrings.playbackProgress(
            elapsed: player.elapsed.dulcetDuration,
            duration: player.current.duration.dulcetDuration
        ))
    }

    private var fraction: Double {
        let total = player.current.duration.dulcetSeconds
        guard total > 0 else { return 0 }
        return min(1, max(0, player.elapsed.dulcetSeconds / total))
    }

    private var remaining: Duration {
        max(.zero, player.current.duration - player.elapsed)
    }
}

/// Places the now-playing bar along the content's bottom edge: a floating card on iPhone and
/// iPad, a docked full-width bar on the Mac.
struct DulcetNowPlayingBarPlacement: ViewModifier {
    enum Placement {
        case floating
        case docked
    }

    @Bindable var store: DulcetPresentationStore
    var placement: Placement = .floating
    var isSuppressed = false
    let onOpen: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            if !isSuppressed, store.showsNowPlayingBar {
                // With Reduce Motion the bar fades in place rather than sliding up the screen.
                bar.transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy, value: store.showsNowPlayingBar)
    }

    @ViewBuilder
    private var bar: some View {
        switch placement {
        case .floating:
            DulcetNowPlayingBar(store: store, style: .compact, onOpen: onOpen)
                .background(
                    .regularMaterial,
                    in: RoundedRectangle(cornerRadius: 16, style: .continuous)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(Color.dulcetSeparator.opacity(0.45), lineWidth: 0.5)
                }
                // Dropping a track or album on the bar adds it to the end of the queue.
                .dulcetQueueDropTarget(store: store, cornerRadius: 16)
                .shadow(color: .black.opacity(0.14), radius: 12, y: 4)
                .padding(.horizontal, DulcetSpacing.sm)
                .padding(.bottom, DulcetSpacing.xs)
        case .docked:
            VStack(spacing: 0) {
                Divider()
                DulcetNowPlayingBar(store: store, style: .expanded, onOpen: onOpen)
                    .padding(.horizontal, DulcetSpacing.xs)
            }
            .background(.regularMaterial)
        }
    }
}
#endif
