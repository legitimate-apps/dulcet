#if os(macOS) || os(iOS)
import SwiftUI

struct DulcetEmptyLibraryView: View {
    var connected = false
    var onConnect: () -> Void = {}

    var body: some View {
        VStack(spacing: DulcetSpacing.xl) {
            Spacer(minLength: DulcetSpacing.xl)

            ZStack {
                Circle()
                    .fill(Color.dulcetAccent.opacity(0.10))
                    .frame(width: 112, height: 112)
                Image(systemName: "music.note.house")
                    .font(.system(size: 42, weight: .medium))
                    .symbolRenderingMode(.hierarchical)
                    .dulcetForeground(.accentIconOnTint)
                    .accessibilityHidden(true)
            }

            VStack(spacing: DulcetSpacing.sm) {
                Text(connected ? DulcetStrings.connectedEmptyTitle : DulcetStrings.firstRunTitle)
                    .font(.largeTitle.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                Text(connected ? DulcetStrings.connectedEmptyBody : DulcetStrings.firstRunBody)
                    .font(.title3)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .frame(maxWidth: 560)
            }

            if !connected {
                VStack(spacing: DulcetSpacing.sm) {
                    Button(DulcetStrings.connectServer, systemImage: "plus", action: onConnect)
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                        .accessibilityLabel(DulcetStrings.connectServer)

                    Button(DulcetStrings.browseHelp) {}
                        .dulcetLinkButtonStyle()
                        .accessibilityLabel(DulcetStrings.browseHelp)
                }
            }

            Text(connected ? DulcetStrings.connectedEmptyFootnote : DulcetStrings.firstRunFootnote)
                .font(.footnote)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
                .frame(maxWidth: 520)

            Spacer(minLength: DulcetSpacing.xl)
        }
        .padding(DulcetSpacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
    }
}

struct DulcetLibraryLoadingView: View {
    var body: some View {
        VStack(spacing: DulcetSpacing.lg) {
            ProgressView()
                .controlSize(.large)
                .accessibilityLabel(DulcetStrings.libraryLoadingTitle)
            Text(DulcetStrings.libraryLoadingTitle)
                .font(.title2.weight(.semibold))
            Text(DulcetStrings.libraryLoadingBody)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 560)
        }
        .padding(DulcetSpacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
    }
}

struct DulcetLibraryErrorView: View {
    let failure: DulcetLibraryFailure?
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: DulcetSpacing.lg) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 38, weight: .medium))
                .dulcetForeground(.dangerIconOnTint)
                .accessibilityHidden(true)
            Text(DulcetStrings.libraryErrorTitle)
                .font(.title2.weight(.semibold))
            Text(message)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 560)
            Button(DulcetStrings.tryAgain, systemImage: "arrow.clockwise", action: onRetry)
                .buttonStyle(.borderedProminent)
                .accessibilityLabel(DulcetStrings.tryAgain)
        }
        .padding(DulcetSpacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
    }

    private var message: String {
        switch failure?.kind {
        case .timeout: DulcetStrings.libraryErrorTimeout
        case .authentication: DulcetStrings.libraryErrorAuthentication
        case .tlsUntrusted, .security: DulcetStrings.libraryErrorSecurity
        case .protocol, .server: DulcetStrings.libraryErrorProtocol
        case .unreachable, .input, .capability, nil: DulcetStrings.libraryErrorGeneric
        }
    }
}

struct DulcetUnavailableDestinationView: View {
    let symbol: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: DulcetSpacing.lg) {
            Image(systemName: symbol)
                .font(.system(size: 38, weight: .medium))
                .dulcetForeground(.accentIconOnTint)
                .accessibilityHidden(true)
            Text(title)
                .font(.title2.weight(.semibold))
            Text(message)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 560)
        }
        .padding(DulcetSpacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
    }
}

struct DulcetLibraryBrowseView: View {
    let snapshot: DulcetSnapshot
    var onSelectAlbum: (DulcetAlbum) -> Void = { _ in }

    private var totalTracks: Int {
        snapshot.albums.reduce(0) { $0 + $1.tracks.count } + snapshot.looseTracks.count
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                DulcetLibraryHeader(
                    title: DulcetStrings.library,
                    subtitle: DulcetStrings.librarySummary(
                        albumCount: snapshot.albums.count,
                        trackCount: totalTracks
                    )
                )

                if !snapshot.musicFolders.isEmpty {
                    Text(DulcetStrings.musicFolderSummary(snapshot.musicFolders.map(\.name)))
                        .font(.subheadline)
                        .dulcetForeground(.secondaryTextOnWindow)
                }

                if !snapshot.artists.isEmpty {
                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        Text(DulcetStrings.artists)
                            .font(.title2.weight(.semibold))
                        LazyVGrid(
                            columns: [GridItem(.adaptive(minimum: 180), alignment: .leading)],
                            alignment: .leading,
                            spacing: DulcetSpacing.sm
                        ) {
                            ForEach(snapshot.artists) { artist in
                                Text(artist.name)
                                    .font(.headline)
                                    .lineLimit(nil)
                            }
                        }
                    }
                }

                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    Text(DulcetStrings.albums)
                        .font(.title2.weight(.semibold))

                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 150), alignment: .top)],
                        alignment: .leading,
                        spacing: DulcetSpacing.md
                    ) {
                        ForEach(snapshot.albums) { album in
                            DulcetAlbumShelfItem(album: album) {
                                onSelectAlbum(album)
                            }
                        }
                    }
                }
            }
            .padding(DulcetSpacing.lg)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
    }
}

struct DulcetLibraryHeader: View {
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.md) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(title)
                    .font(.largeTitle.weight(.bold))
                    .lineLimit(nil)
                Text(subtitle)
                    .font(.subheadline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(nil)
            }
            Spacer(minLength: DulcetSpacing.md)
            HStack(spacing: DulcetSpacing.xs) {
                Button(DulcetStrings.playAll, systemImage: "play.fill") {}
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
                    .accessibilityLabel(DulcetStrings.playAll)
                Button(DulcetStrings.shuffle, systemImage: "shuffle") {}
                    .buttonStyle(.bordered)
                    .accessibilityLabel(DulcetStrings.shuffle)
            }
        }
    }
}

struct DulcetAlbumShelfItem: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let album: DulcetAlbum
    var offline = false
    var onSelect: () -> Void = {}

    var body: some View {
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                ZStack(alignment: .bottomTrailing) {
                    DulcetArtworkView(artwork: album.artwork, size: 126, muted: offline)
                    if offline {
                        Image(systemName: "cloud.slash.fill")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.white) // dulcet-contrast-waiver: decorative-artwork-overlay
                            .padding(6)
                            .background(Color.black.opacity(0.64), in: Circle())
                            .padding(6)
                            .accessibilityHidden(true)
                    }
                }

                Text(album.title)
                    .font(.headline)
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                Text(DulcetStrings.artistNames(album.albumArtists))
                    .font(.subheadline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                Text(DulcetStrings.trackCount(album.tracks.count))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
            .frame(width: dynamicTypeSize.isAccessibilitySize ? 190 : 126, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(DulcetStrings.albumAccessibility(
            album.title,
            artists: DulcetStrings.artistNames(album.albumArtists),
            tracks: DulcetStrings.trackCount(album.tracks.count)
        ))
        .accessibilityHint(offline ? DulcetStrings.offlineUnavailable : DulcetStrings.play)
    }
}

enum DulcetTrackRowSurface {
    case window
    case control
    case regularMaterial

    var primaryPair: DulcetRegisteredContrastPair {
        switch self {
        case .window: .primaryTextOnWindow
        case .control: .primaryTextOnControl
        case .regularMaterial: .primaryTextOnRegularMaterial
        }
    }

    var secondaryPair: DulcetRegisteredContrastPair {
        switch self {
        case .window: .secondaryTextOnWindow
        case .control: .secondaryTextOnControl
        case .regularMaterial: .secondaryTextOnRegularMaterial
        }
    }

    var offlinePair: DulcetRegisteredContrastPair {
        .offlineLabelOnControl
    }
}

struct DulcetTrackRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let track: DulcetTrack
    let showAlbum: Bool
    let index: Int
    var offline = false
    var surface: DulcetTrackRowSurface = .window

    var body: some View {
        Button(action: {}) {
            HStack(alignment: .center, spacing: DulcetSpacing.xs) {
                Group {
                    if offline {
                        Image(systemName: "cloud.slash")
                            .dulcetForeground(surface.offlinePair)
                    } else {
                        Text(String(index))
                            .font(.caption.monospacedDigit())
                            .dulcetForeground(surface.secondaryPair)
                    }
                }
                .frame(width: 20)
                .accessibilityHidden(true)

                DulcetArtworkView(
                    artwork: track.artwork,
                    size: DulcetMetrics.denseRowArtworkSize,
                    muted: offline
                )

                VStack(alignment: .leading, spacing: 0) {
                    Text(track.title)
                        .font(.callout.weight(.medium))
                        .dulcetForeground(surface.primaryPair)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    Text(trackSubtitle)
                        .font(.caption)
                        .dulcetForeground(surface.secondaryPair)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                }

                Spacer(minLength: DulcetSpacing.xs)

                if offline {
                    Text(DulcetStrings.offlineUnavailable)
                        .font(.caption.weight(.medium))
                        .dulcetForeground(surface.offlinePair)
                        .lineLimit(nil)
                } else {
                    Text(track.duration.dulcetDuration)
                        .font(.caption.monospacedDigit())
                        .dulcetForeground(surface.secondaryPair)
                }

                Image(systemName: "ellipsis")
                    .dulcetForeground(surface.secondaryPair)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, DulcetSpacing.xs)
            .padding(.vertical, DulcetMetrics.denseRowVerticalPadding)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .dulcetForeground(surface.primaryPair)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(offline ? DulcetStrings.offlineUnavailable : DulcetStrings.play)
    }

    private var trackSubtitle: String {
        let artists = DulcetStrings.artistNames(track.artistNames)
        if showAlbum, let album = track.albumTitle {
            return DulcetStrings.trackSubtitle(artists: artists, album: album)
        }
        if track.albumTitle == nil {
            return DulcetStrings.trackSubtitle(artists: artists, album: DulcetStrings.withoutAlbum)
        }
        return artists
    }

    private var accessibilityLabel: String {
        if offline {
            return DulcetStrings.unavailableTrackAccessibility(
                title: track.title,
                subtitle: trackSubtitle,
                duration: track.duration.dulcetDuration
            )
        }
        return DulcetStrings.trackAccessibility(
            title: track.title,
            subtitle: trackSubtitle,
            duration: track.duration.dulcetDuration
        )
    }
}

struct DulcetAlbumDetailView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let album: DulcetAlbum

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.xl) {
                albumHeader

                ForEach(album.discNumbers, id: \.self) { disc in
                    VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                        Text(DulcetStrings.discTitle(disc))
                            .font(.title2.weight(.semibold))
                            .accessibilityAddTraits(.isHeader)

                        VStack(spacing: 0) {
                            let tracks = album.tracks.filter { ($0.discNumber ?? 1) == disc }
                            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                                DulcetTrackRow(
                                    track: track,
                                    showAlbum: false,
                                    index: index + 1,
                                    surface: .window
                                )
                                if track.id != tracks.last?.id {
                                    Divider().padding(.leading, DulcetMetrics.denseRowSeparatorInset)
                                }
                            }
                        }
                    }
                }
            }
            .padding(DulcetSpacing.xl)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(album.title)
    }

    @ViewBuilder
    private var albumHeader: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                DulcetArtworkView(artwork: album.artwork, size: 180)
                albumIdentity
                albumActions
            }
        } else {
            HStack(alignment: .bottom, spacing: DulcetSpacing.lg) {
                DulcetArtworkView(artwork: album.artwork, size: 188)
                albumIdentity
                Spacer(minLength: DulcetSpacing.md)
                albumActions
                    .fixedSize(horizontal: true, vertical: false)
            }
        }
    }

    private var albumIdentity: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(DulcetStrings.album.uppercased())
                .font(.caption.weight(.semibold))
                .dulcetForeground(.secondaryTextOnWindow)
            Text(album.title)
                .font(.largeTitle.weight(.bold))
                .lineLimit(nil)
            Text(DulcetStrings.artistNames(album.albumArtists))
                .font(.title3)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
            Text(DulcetStrings.albumMetadata(
                year: album.year,
                tracks: DulcetStrings.trackCount(album.tracks.count),
                duration: album.totalDuration.dulcetDuration
            ))
                .font(.subheadline)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
        }
    }

    private var albumActions: some View {
        HStack(spacing: DulcetSpacing.xs) {
            Button(DulcetStrings.play, systemImage: "play.fill") {}
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .accessibilityLabel(DulcetStrings.play)
            Button(DulcetStrings.shuffle, systemImage: "shuffle") {}
                .buttonStyle(.bordered)
                .accessibilityLabel(DulcetStrings.shuffle)
            Button(DulcetStrings.more, systemImage: "ellipsis") {}
                .buttonStyle(.bordered)
                .accessibilityLabel(DulcetStrings.more)
        }
    }
}

struct DulcetOfflineLibraryView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let snapshot: DulcetSnapshot

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                offlineBanner

                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                        Text(DulcetStrings.library)
                            .font(.largeTitle.weight(.bold))
                        if case let .offline(lastSynced) = snapshot.connectivity {
                            Text(DulcetStrings.lastSynced(lastSynced))
                                .font(.subheadline)
                                .dulcetForeground(.secondaryTextOnWindow)
                                .lineLimit(nil)
                        }
                    }
                    Spacer()
                    Button(DulcetStrings.tryAgain, systemImage: "arrow.clockwise") {}
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                        .accessibilityLabel(DulcetStrings.tryAgain)
                }

                if dynamicTypeSize.isAccessibilitySize {
                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 190), spacing: DulcetSpacing.md, alignment: .top)],
                        alignment: .leading,
                        spacing: DulcetSpacing.md
                    ) {
                        ForEach(snapshot.albums.prefix(6)) { album in
                            DulcetAlbumShelfItem(album: album, offline: true)
                        }
                    }
                } else {
                    ScrollView(.horizontal) {
                        HStack(alignment: .top, spacing: DulcetSpacing.md) {
                            ForEach(snapshot.albums.prefix(6)) { album in
                                DulcetAlbumShelfItem(album: album, offline: true)
                            }
                        }
                        .padding(.bottom, DulcetSpacing.xs)
                    }
                    .scrollIndicators(.hidden)
                }

                VStack(spacing: 0) {
                    let tracks = Array(snapshot.albums.prefix(3).flatMap(\.tracks).prefix(5))
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                        DulcetTrackRow(
                            track: track,
                            showAlbum: true,
                            index: index + 1,
                            offline: true,
                            surface: .control
                        )
                        if track.id != tracks.last?.id {
                            Divider().padding(.leading, DulcetMetrics.denseRowSeparatorInset)
                        }
                    }
                }
                .background(Color.dulcetControl.opacity(0.52), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .padding(DulcetSpacing.lg)
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
    }

    private var offlineBanner: some View {
        HStack(alignment: .top, spacing: DulcetSpacing.md) {
            Image(systemName: "wifi.slash")
                .font(.title2.weight(.semibold))
                .dulcetForeground(.offlineIconOnTint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.offlineTitle)
                    .font(.headline)
                    .dulcetForeground(.primaryTextOnOfflineTint)
                Text(DulcetStrings.offlineBody)
                    .font(.body)
                    .dulcetForeground(.secondaryTextOnOfflineTint)
                    .lineLimit(nil)
            }
            Spacer(minLength: 0)
        }
        .padding(DulcetSpacing.md)
        .background(Color.dulcetOffline.opacity(0.10), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Color.dulcetOffline.opacity(0.35), lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
    }
}
#endif
