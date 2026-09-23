#if os(macOS) || os(iOS)
import SwiftUI

/// The persistent now-playing bar: what is playing, play/pause and next, on every screen while a
/// queue exists. Activating the bar itself opens the full player.
///
/// It reads the snapshot's playback status as well as its now-playing value, so a track that is
/// still opening, or one that failed, is said out loud rather than making the bar vanish and
/// reappear between tracks.
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

    /// Whether the bar has anything to show. Nothing queued means no bar at all.
    static func isVisible(for snapshot: DulcetSnapshot) -> Bool {
        snapshot.nowPlaying != nil
            || snapshot.playbackStatus == .preparing
            || snapshot.playbackStatus == .failed
    }

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
            transport
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
        .accessibilityIdentifier("\(Self.identifier).open")
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(DulcetStrings.openNowPlayingHint)
        .dulcetForeground(.primaryTextOnRegularMaterial)
    }

    @ViewBuilder
    private var artwork: some View {
        let size: CGFloat = style == .expanded ? 44 : 40
        if let player = store.snapshot.nowPlaying {
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

    private var identity: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .accessibilityIdentifier("\(Self.identifier).title")
            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnRegularMaterial)
                    .lineLimit(1)
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
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .accessibilityLabel(label)
        .accessibilityIdentifier("\(Self.identifier).\(identifier)")
#if os(macOS)
        .help(label)
#endif
    }

    private var title: String {
        if let player = store.snapshot.nowPlaying { return player.current.title }
        return store.snapshot.playbackStatus == .failed
            ? DulcetStrings.playbackFailedShort
            : DulcetStrings.playbackLoading
    }

    private var subtitle: String? {
        guard let player = store.snapshot.nowPlaying else { return nil }
        let artists = DulcetStrings.artistNames(player.current.artistNames)
        return artists.isEmpty ? nil : artists
    }

    private var accessibilityLabel: String {
        DulcetStrings.miniPlayerAccessibility(title: title, artists: subtitle ?? "")
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

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            if !isSuppressed, DulcetNowPlayingBar.isVisible(for: store.snapshot) {
                bar.transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.snappy, value: DulcetNowPlayingBar.isVisible(for: store.snapshot))
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
