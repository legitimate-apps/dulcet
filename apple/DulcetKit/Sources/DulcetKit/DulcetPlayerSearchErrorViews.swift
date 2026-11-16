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
        .dulcetNavigationTitle(DulcetStrings.nowPlaying)
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
        .dulcetNavigationTitle(DulcetStrings.nowPlaying)
#else
        content(failure)
            .padding(DulcetSpacing.xxl)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.dulcetWindow)
            .dulcetForeground(.primaryTextOnWindow)
            .dulcetNavigationTitle(DulcetStrings.nowPlaying)
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
        .dulcetNavigationTitle(DulcetStrings.settings)
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
