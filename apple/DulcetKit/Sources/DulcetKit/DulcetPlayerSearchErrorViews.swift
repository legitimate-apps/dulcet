#if os(macOS)
import SwiftUI

struct DulcetNowPlayingView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let player: DulcetNowPlaying

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    player.current.artwork.palette.colors[0].opacity(0.26),
                    Color.dulcetWindow,
                    player.current.artwork.palette.colors[1].opacity(0.10),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

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
                Text(player.current.artistNames.joined(separator: ", "))
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                if let album = player.current.albumTitle {
                    Text(album)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(nil)
                }
            }

            VStack(spacing: DulcetSpacing.xs) {
                Slider(
                    value: .constant(Double(player.elapsedSeconds)),
                    in: 0...Double(player.current.durationSeconds)
                )
                .accessibilityLabel(DulcetStrings.nowPlaying)
                .accessibilityValue("\(player.elapsedSeconds.dulcetDuration) / \(player.current.durationSeconds.dulcetDuration)")

                HStack {
                    Text(player.elapsedSeconds.dulcetDuration)
                    Spacer()
                    Text(player.current.durationSeconds.dulcetDuration)
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
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
                    .tint(.dulcetAccent)
                    .foregroundStyle(Color.dulcetOnAccent)
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
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                Slider(value: .constant(0.68), in: 0...1)
                    .frame(maxWidth: 180)
                    .accessibilityLabel(DulcetStrings.volume)
                    .accessibilityValue("68%")
                Text("FLAC · 44.1 kHz")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, DulcetSpacing.xs)
                    .padding(.vertical, DulcetSpacing.xxs)
                    .background(.regularMaterial, in: Capsule())
            }

            Label("\(DulcetStrings.playingOn) \(player.outputName)", systemImage: "hifispeaker.2")
                .font(.caption)
                .foregroundStyle(.secondary)
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
                    DulcetTrackRow(track: track, showAlbum: true, index: index + 1)
                    if track.id != player.queue.last?.id {
                        Divider().padding(.leading, 52)
                    }
                }
            }
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.dulcetSeparator.opacity(0.55), lineWidth: 1)
            }
        }
    }
}

struct DulcetSearchView: View {
    let snapshot: DulcetSnapshot
    @Binding var searchQuery: String

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    Text(DulcetStrings.searchTitle)
                        .font(.largeTitle.weight(.bold))
                        .accessibilityAddTraits(.isHeader)
                    TextField(DulcetStrings.searchPrompt, text: $searchQuery)
                        .textFieldStyle(.roundedBorder)
                        .font(.title3)
                        .accessibilityLabel(DulcetStrings.searchPrompt)
                    Text(DulcetStrings.searchSummary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(nil)
                }

                HStack(alignment: .firstTextBaseline) {
                    Text(DulcetStrings.bestMatches)
                        .font(.title2.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Text(DulcetStrings.trackCount(snapshot.searchResults.count))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                VStack(spacing: 0) {
                    ForEach(snapshot.searchResults) { result in
                        DulcetSearchResultRow(result: result)
                        if result.id != snapshot.searchResults.last?.id {
                            Divider().padding(.leading, 76)
                        }
                    }
                }
                .background(Color.dulcetControl.opacity(0.52), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .stroke(Color.dulcetSeparator.opacity(0.55), lineWidth: 1)
                }
            }
            .padding(DulcetSpacing.xl)
        }
        .background(Color.dulcetWindow)
        .navigationTitle(DulcetStrings.search)
    }
}

private struct DulcetSearchResultRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let result: DulcetSearchResult

    var body: some View {
        Button(action: {}) {
            HStack(alignment: .center, spacing: DulcetSpacing.md) {
                DulcetArtworkView(artwork: result.artwork, size: 48)

                VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                    HStack(spacing: DulcetSpacing.xs) {
                        Text(result.title)
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                        Text(kindTitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(result.subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    if result.refreshedFromServer {
                        Label(DulcetStrings.refreshed, systemImage: "arrow.clockwise")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer(minLength: DulcetSpacing.sm)
                DulcetSourceBadge(source: result.source)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .padding(DulcetSpacing.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(result.title), \(result.subtitle), \(kindTitle), \(sourceTitle)")
        .accessibilityHint(DulcetStrings.play)
    }

    private var kindTitle: String {
        switch result.kind {
        case .track: DulcetStrings.track
        case .album: DulcetStrings.album
        case .artist: DulcetStrings.artist
        }
    }

    private var sourceTitle: String {
        switch result.source {
        case .local: DulcetStrings.local
        case .server: DulcetStrings.server
        case .localAndServer: DulcetStrings.localAndServer
        }
    }
}

struct DulcetTLSUntrustedView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let failure: DulcetTLSFailure

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.xl) {
                ViewThatFits(in: .horizontal) {
                    HStack(alignment: .top, spacing: DulcetSpacing.lg) {
                        shield
                        heading
                    }
                    VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                        shield
                        heading
                    }
                }

                GroupBox(DulcetStrings.tlsWhy) {
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        Text(failure.reason)
                            .font(.headline)
                            .lineLimit(nil)
                        Text(failure.technicalDetail)
                            .font(.body)
                            .foregroundStyle(.secondary)
                            .lineLimit(nil)
                    }
                    .padding(.vertical, DulcetSpacing.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                GroupBox(DulcetStrings.tlsRemedyTitle) {
                    Label {
                        Text(DulcetStrings.tlsRemedyBody)
                            .lineLimit(nil)
                    } icon: {
                        Image(systemName: "checkmark.shield")
                            .foregroundStyle(Color.dulcetAccent)
                    }
                    .font(.body)
                    .padding(.vertical, DulcetSpacing.xs)
                }

                HStack(spacing: DulcetSpacing.sm) {
                    Button(DulcetStrings.connectionSettings, systemImage: "slider.horizontal.3") {}
                        .buttonStyle(.borderedProminent)
                        .tint(.dulcetAccent)
                        .foregroundStyle(Color.dulcetOnAccent)
                        .controlSize(.large)
                        .keyboardShortcut(.defaultAction)
                        .accessibilityLabel(DulcetStrings.connectionSettings)
                    Button(DulcetStrings.openCertificateHelp, systemImage: "questionmark.circle") {}
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                        .accessibilityLabel(DulcetStrings.openCertificateHelp)
                }
            }
            .padding(dynamicTypeSize.isAccessibilitySize ? DulcetSpacing.lg : DulcetSpacing.xxl)
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .background(Color.dulcetWindow)
        .navigationTitle(DulcetStrings.settings)
    }

    private var shield: some View {
        ZStack {
            Circle()
                .fill(Color.dulcetDanger.opacity(0.11))
                .frame(width: 96, height: 96)
            Image(systemName: "exclamationmark.shield.fill")
                .font(.system(size: 42, weight: .semibold))
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(Color.dulcetDanger)
                .accessibilityHidden(true)
        }
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            Text(DulcetStrings.tlsTitle)
                .font(.largeTitle.weight(.bold))
                .lineLimit(nil)
                .accessibilityAddTraits(.isHeader)
            Text(failure.serverName)
                .font(.title3.weight(.medium))
                .lineLimit(nil)
            Text(DulcetStrings.tlsBody)
                .font(.body)
                .foregroundStyle(.secondary)
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
                    .foregroundStyle(badAccent)
                Text(DulcetStrings.library)
                    .font(.system(size: 31, weight: .thin, design: .serif))
                    .foregroundStyle(Color.green)
                Text(DulcetStrings.search)
                    .font(.caption2)
                Text(DulcetStrings.nowPlaying)
                    .font(.title2.monospaced())
                    .foregroundStyle(Color.cyan)
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
                        .foregroundStyle(badAccent)
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
                                .foregroundStyle(album.tracks.count > 10 ? Color.green : Color.primary)
                                .lineLimit(1)
                        }
                        .padding(album.tracks.count.isMultiple(of: 2) ? 3 : 17)
                        .background(Color.cyan.opacity(0.18), in: RoundedRectangle(cornerRadius: 27))
                    }
                }

                Text(snapshot.albums.first?.tracks.first?.title ?? DulcetStrings.controlBad)
                    .font(.system(size: 8, weight: .ultraLight, design: .rounded))
                    .padding(.top, 29)
                    .foregroundStyle(badAccent)

                Spacer()
                RoundedRectangle(cornerRadius: 31)
                    .fill(badAccent)
                    .frame(height: 17)
                    .overlay(alignment: .leading) {
                        Text(DulcetStrings.nowPlaying)
                            .font(.system(size: 7, design: .serif))
                            .foregroundStyle(Color.green)
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
