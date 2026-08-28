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

struct DulcetNowPlayingView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var scrubPosition: Double?
    let player: DulcetNowPlaying
    var onControl: (DulcetPlaybackControlIntent) -> Void = { _ in }

    var body: some View {
        ZStack {
            Color.dulcetWindow.ignoresSafeArea()

            ScrollView {
                Group {
                    if dynamicTypeSize.isAccessibilitySize {
                        VStack(alignment: .leading, spacing: DulcetSpacing.xl) {
                            playerPanel
                            queuePanel
                        }
                    } else {
                        HStack(alignment: .top, spacing: DulcetSpacing.xl) {
                            playerPanel
                                .frame(maxWidth: 520)
                            queuePanel
                                .frame(maxWidth: 390)
                        }
                    }
                }
                .padding(DulcetSpacing.xl)
                .frame(maxWidth: .infinity)
            }
        }
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.nowPlaying)
    }

    private var playerPanel: some View {
        VStack(spacing: DulcetSpacing.lg) {
            DulcetArtworkView(
                artwork: player.current.artwork,
                size: dynamicTypeSize.isAccessibilitySize ? 220 : 332
            )

            VStack(spacing: DulcetSpacing.xs) {
                Text(player.current.title)
                    .font(.largeTitle.weight(.bold))
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                Text(DulcetStrings.artistNames(player.current.artistNames))
                    .font(.title3)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                if let album = player.current.albumTitle {
                    Text(album)
                        .font(.subheadline)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .lineLimit(nil)
                }
            }

            playbackProgress

            HStack(spacing: DulcetSpacing.lg) {
                Button {
                    onControl(.setShuffle(!player.shuffleEnabled))
                } label: {
                    Image(systemName: player.shuffleEnabled ? "shuffle.circle.fill" : "shuffle")
                }
                    .dulcetMediaButtonStyle()
                    .accessibilityLabel(DulcetStrings.shuffle)
                    .accessibilityValue(player.shuffleEnabled
                        ? DulcetStrings.controlOn : DulcetStrings.controlOff)
                Button {
                    onControl(.previous)
                } label: {
                    Image(systemName: "backward.fill")
                }
                    .dulcetMediaButtonStyle()
                    .font(.title2)
                    .accessibilityLabel(DulcetStrings.previous)
                    .disabled(!player.canGoPrevious)
                Button(
                    player.isPlaying ? DulcetStrings.pause : DulcetStrings.play,
                    systemImage: player.isPlaying ? "pause.fill" : "play.fill"
                ) {
                    onControl(player.isPlaying ? .pause : .play)
                }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .font(.title2)
                    .accessibilityLabel(player.isPlaying ? DulcetStrings.pause : DulcetStrings.play)
                Button {
                    onControl(.next)
                } label: {
                    Image(systemName: "forward.fill")
                }
                    .dulcetMediaButtonStyle()
                    .font(.title2)
                    .accessibilityLabel(DulcetStrings.next)
                    .disabled(!player.canGoNext)
                Button {
                    onControl(.cycleRepeat)
                } label: {
                    Image(systemName: repeatSymbol)
                }
                    .dulcetMediaButtonStyle()
                    .accessibilityLabel(DulcetStrings.repeatMode)
                    .accessibilityValue(repeatAccessibilityValue)
            }

            if player.audioFormat.sampleRateKilohertz > 0 {
                Text(DulcetStrings.audioFormat(
                        codec: player.audioFormat.codec,
                        sampleRateKilohertz: player.audioFormat.sampleRateKilohertz
                    ))
                    .font(.caption.monospaced())
                    .dulcetForeground(.secondaryTextOnRegularMaterial)
                    .padding(.horizontal, DulcetSpacing.xs)
                    .padding(.vertical, DulcetSpacing.xxs)
                    .background(.regularMaterial, in: Capsule())
            } else {
                Text(player.audioFormat.codec)
                    .font(.caption.monospaced())
                    .dulcetForeground(.secondaryTextOnRegularMaterial)
                    .padding(.horizontal, DulcetSpacing.xs)
                    .padding(.vertical, DulcetSpacing.xxs)
                    .background(.regularMaterial, in: Capsule())
            }

            Label(DulcetStrings.playingOn(player.outputName), systemImage: "hifispeaker.2")
                .font(.caption)
                .dulcetForeground(.secondaryTextOnWindow)

            if let sourceDisplayName = player.sourceDisplayName {
                Text(DulcetStrings.playingFrom(sourceDisplayName))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
        }
    }

    private var playbackProgress: some View {
        VStack(spacing: DulcetSpacing.xs) {
            if player.progressBegan {
                playbackProgressIndicator
                    .accessibilityLabel(DulcetStrings.nowPlaying)
                    .accessibilityValue(DulcetStrings.playbackProgress(
                        elapsed: displayedElapsed.dulcetDuration,
                        duration: player.current.duration.dulcetDuration
                    ))

                HStack {
                    Text(displayedElapsed.dulcetDuration)
                    Spacer()
                    Text(player.current.duration.dulcetDuration)
                }
                .font(.caption.monospacedDigit())
                .dulcetForeground(.secondaryTextOnWindow)
            } else {
                Text(playbackPhaseLabel)
                    .font(.subheadline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityLabel(playbackPhaseLabel)
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
    }
}

struct DulcetSearchView: View {
    @State private var selectedResultID: DulcetSearchResult.ID?
    let snapshot: DulcetSnapshot
    @Binding var searchQuery: String
    let onLoadMore: (DulcetSearchResultKind) -> Void
    let onRetry: () -> Void
    let onActivateResult: (DulcetSearchResult.ID) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.searchTitle)
                    .font(.title.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                TextField(DulcetStrings.searchPrompt, text: $searchQuery)
                    .dulcetSearchFieldStyle()
                    .controlSize(.small)
                    .accessibilityLabel(DulcetStrings.searchPrompt)
                Text(DulcetStrings.searchSummary)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }

            searchContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(DulcetSpacing.lg)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.search)
    }

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

#if os(tvOS)
            ScrollView {
                LazyVStack(spacing: DulcetSpacing.sm) {
                    ForEach(snapshot.searchResults) { result in
                        Button {
                            selectedResultID = result.id
                        } label: {
                            HStack(spacing: DulcetSpacing.md) {
                                DulcetSearchResultIdentity(result: result)
                                Spacer(minLength: DulcetSpacing.md)
                                Text(result.kind.displayTitle)
                                    .font(.callout.weight(.semibold))
                                    .dulcetForeground(.secondaryTextOnWindow)
                            }
                            .padding(DulcetSpacing.sm)
                            .contentShape(Rectangle())
                        }
                        .dulcetMediaButtonStyle()
                        .accessibilityLabel(DulcetStrings.searchResultAccessibility(
                            title: result.title,
                            subtitle: result.subtitle,
                            kind: result.kind.displayTitle
                        ))
                    }
                }
            }
#else
            Table(snapshot.searchResults, selection: $selectedResultID) {
                TableColumn(DulcetStrings.resultColumn) { result in
                    DulcetSearchResultIdentity(result: result)
                }
                .width(min: 320, ideal: 620)

                TableColumn(DulcetStrings.typeColumn) { result in
                    Text(result.kind.displayTitle)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
                .width(min: 72, ideal: 90, max: 120)
            }
#if os(macOS)
            .alternatingRowBackgrounds(.disabled)
            .contextMenu(forSelectionType: DulcetSearchResult.ID.self) { _ in
                EmptyView()
            } primaryAction: { selection in
                guard let id = selection.first else { return }
                selectedResultID = id
                onActivateResult(id)
            }
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

    var body: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
            DulcetArtworkView(artwork: result.artwork, size: DulcetMetrics.denseRowArtworkSize)

            VStack(alignment: .leading, spacing: 0) {
                Text(result.title)
                    .font(.callout.weight(.medium))
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
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
