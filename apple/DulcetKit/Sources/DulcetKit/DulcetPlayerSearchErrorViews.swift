#if os(macOS)
import SwiftUI

struct DulcetNowPlayingView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let player: DulcetNowPlaying

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

            VStack(spacing: DulcetSpacing.xs) {
                Slider(
                    value: .constant(Double(player.elapsedSeconds)),
                    in: 0...Double(player.current.durationSeconds)
                )
                .accessibilityLabel(DulcetStrings.nowPlaying)
                .accessibilityValue(DulcetStrings.playbackProgress(
                    elapsed: player.elapsedSeconds.dulcetDuration,
                    duration: player.current.durationSeconds.dulcetDuration
                ))

                HStack {
                    Text(player.elapsedSeconds.dulcetDuration)
                    Spacer()
                    Text(player.current.durationSeconds.dulcetDuration)
                }
                .font(.caption.monospacedDigit())
                .dulcetForeground(.secondaryTextOnWindow)
            }

            HStack(spacing: DulcetSpacing.lg) {
                Button(action: {}) {
                    Image(systemName: "shuffle")
                }
                    .buttonStyle(.plain)
                    .accessibilityLabel(DulcetStrings.shuffle)
                Button(action: {}) {
                    Image(systemName: "backward.fill")
                }
                    .buttonStyle(.plain)
                    .font(.title2)
                    .accessibilityLabel(DulcetStrings.previous)
                Button(player.isPlaying ? DulcetStrings.pause : DulcetStrings.play, systemImage: player.isPlaying ? "pause.fill" : "play.fill") {}
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .font(.title2)
                    .keyboardShortcut(.space, modifiers: [])
                    .accessibilityLabel(player.isPlaying ? DulcetStrings.pause : DulcetStrings.play)
                Button(action: {}) {
                    Image(systemName: "forward.fill")
                }
                    .buttonStyle(.plain)
                    .font(.title2)
                    .accessibilityLabel(DulcetStrings.next)
                Button(action: {}) {
                    Image(systemName: player.current.isFavorite ? "heart.fill" : "heart")
                }
                    .buttonStyle(.plain)
                    .accessibilityLabel(player.current.isFavorite ? DulcetStrings.unfavorite : DulcetStrings.favorite)
            }

            HStack(spacing: DulcetSpacing.sm) {
                Image(systemName: "speaker.wave.2")
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityHidden(true)
                Slider(value: .constant(player.volume), in: 0...1)
                    .frame(maxWidth: 180)
                    .accessibilityLabel(DulcetStrings.volume)
                    .accessibilityValue(DulcetStrings.volumeValue(player.volume))
                Text(DulcetStrings.audioFormat(
                    codec: player.audioFormat.codec,
                    sampleRateKilohertz: player.audioFormat.sampleRateKilohertz
                ))
                    .font(.caption.monospaced())
                    .dulcetForeground(.secondaryTextOnRegularMaterial)
                    .padding(.horizontal, DulcetSpacing.xs)
                    .padding(.vertical, DulcetSpacing.xxs)
                    .background(.regularMaterial, in: Capsule())
            }

            Label(DulcetStrings.playingOn(player.outputName), systemImage: "hifispeaker.2")
                .font(.caption)
                .dulcetForeground(.secondaryTextOnWindow)
        }
    }

    private var queuePanel: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
            HStack {
                Text(DulcetStrings.queue)
                    .font(.title2.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button(DulcetStrings.more, systemImage: "ellipsis.circle") {}
                    .buttonStyle(.plain)
                    .accessibilityLabel(DulcetStrings.more)
            }

            VStack(spacing: 0) {
                ForEach(Array(player.queue.enumerated()), id: \.element.id) { index, track in
                    DulcetTrackRow(
                        track: track,
                        showAlbum: true,
                        index: index + 1,
                        surface: .regularMaterial
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

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.searchTitle)
                    .font(.title.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                TextField(DulcetStrings.searchPrompt, text: $searchQuery)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel(DulcetStrings.searchPrompt)
                Text(DulcetStrings.searchSummary)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }

            HStack(alignment: .firstTextBaseline) {
                Text(DulcetStrings.bestMatches)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Text(DulcetStrings.trackCount(snapshot.searchResults.count))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
            }

            Table(snapshot.searchResults, selection: $selectedResultID) {
                TableColumn(DulcetStrings.resultColumn) { result in
                    DulcetSearchResultIdentity(result: result)
                }
                .width(min: 320, ideal: 500)

                TableColumn(DulcetStrings.typeColumn) { result in
                    Text(result.kind.displayTitle)
                        .dulcetForeground(.secondaryTextOnWindow)
                }
                .width(min: 72, ideal: 90, max: 110)

                TableColumn(DulcetStrings.sourceColumn) { result in
                    Label(result.source.displayTitle, systemImage: result.source.symbolName)
                        .labelStyle(.titleAndIcon)
                        .dulcetForeground(.primaryTextOnWindow)
                        .accessibilityLabel(result.source.displayTitle)
                }
                .width(min: 132, ideal: 150, max: 180)
            }
            .tableStyle(.inset(alternatesRowBackgrounds: false))
            .controlSize(.small)
        }
        .padding(DulcetSpacing.lg)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(DulcetStrings.search)
    }
}

private struct DulcetSearchResultIdentity: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let result: DulcetSearchResult

    var body: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
            DulcetArtworkView(artwork: result.artwork, size: DulcetMetrics.denseRowArtworkSize)

            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: DulcetSpacing.xxs) {
                    Text(result.title)
                        .font(.callout.weight(.medium))
                        .dulcetForeground(.primaryTextOnWindow)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    if result.refreshedFromServer {
                        Image(systemName: "arrow.clockwise")
                            .font(.caption)
                            .dulcetForeground(.secondaryTextOnWindow)
                            .accessibilityLabel(DulcetStrings.refreshed)
                    }
                }
                Text(result.subtitle)
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
            }
        }
        .accessibilityLabel(DulcetStrings.searchResultAccessibility(
            title: result.title,
            subtitle: result.subtitle,
            kind: result.kind.displayTitle,
            source: result.source.displayTitle
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

extension DulcetSearchSource {
    var displayTitle: String {
        switch self {
        case .local: DulcetStrings.local
        case .server: DulcetStrings.server
        case .localAndServer: DulcetStrings.localAndServer
        }
    }

    var symbolName: String {
        switch self {
        case .local: "laptopcomputer"
        case .server: "server.rack"
        case .localAndServer: "arrow.triangle.2.circlepath"
        }
    }
}

struct DulcetTLSUntrustedView: View {
    let failure: DulcetTLSFailure

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .top, spacing: DulcetSpacing.sm) {
                        shield
                        heading
                    }
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        shield
                        heading
                    }
                }

                GroupBox(DulcetStrings.tlsWhy) {
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

                GroupBox(DulcetStrings.tlsRemedyTitle) {
                    Label {
                        Text(DulcetStrings.tlsRemedyBody)
                            .lineLimit(nil)
                    } icon: {
                        Image(systemName: "checkmark.shield")
                            .dulcetForeground(.accentIconOnWindow)
                    }
                    .font(.callout)
                }

                HStack(spacing: DulcetSpacing.sm) {
                    Link(destination: DulcetLinks.certificateInstallationGuide) {
                        Label(DulcetStrings.openCertificateHelp, systemImage: "key.horizontal")
                    }
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                        .accessibilityLabel(DulcetStrings.openCertificateHelp)
                    Button(DulcetStrings.connectionSettings, systemImage: "slider.horizontal.3") {}
                        .buttonStyle(.bordered)
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
}

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
