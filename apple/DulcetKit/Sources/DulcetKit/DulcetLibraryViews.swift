#if os(macOS)
import SwiftUI

struct DulcetEmptyLibraryView: View {
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
                    .foregroundStyle(Color.dulcetAccent)
                    .accessibilityHidden(true)
            }

            VStack(spacing: DulcetSpacing.sm) {
                Text(DulcetStrings.firstRunTitle)
                    .font(.largeTitle.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                Text(DulcetStrings.firstRunBody)
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .frame(maxWidth: 560)
            }

            VStack(spacing: DulcetSpacing.sm) {
                Button(DulcetStrings.connectServer, systemImage: "plus") {}
                    .buttonStyle(.borderedProminent)
                    .tint(.dulcetAccent)
                    .foregroundStyle(Color.dulcetOnAccent)
                    .keyboardShortcut(.defaultAction)
                    .accessibilityLabel(DulcetStrings.connectServer)

                Button(DulcetStrings.browseHelp) {}
                    .buttonStyle(.link)
                    .accessibilityLabel(DulcetStrings.browseHelp)
            }

            Text(DulcetStrings.firstRunFootnote)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
                .frame(maxWidth: 520)

            Spacer(minLength: DulcetSpacing.xl)
        }
        .padding(DulcetSpacing.xxl)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.dulcetWindow)
    }
}

struct DulcetLibraryBrowseView: View {
    let snapshot: DulcetSnapshot

    private var totalTracks: Int {
        snapshot.albums.reduce(0) { $0 + $1.tracks.count } + snapshot.looseTracks.count
    }

    private var recentTracks: [DulcetTrack] {
        let requestedIDs = [
            "album-etudes-between-stations-track-1",
            "double-lines-d1-t2",
            "shared-credit",
            "track-no-album",
            "deliberately-long-title",
            "paging-150",
        ]
        let all = snapshot.albums.flatMap(\.tracks) + snapshot.looseTracks
        return requestedIDs.compactMap { id in all.first { $0.id == id } }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                DulcetLibraryHeader(
                    title: DulcetStrings.library,
                    subtitle: "\(DulcetStrings.albumCount(snapshot.albums.count)) · \(DulcetStrings.trackCount(totalTracks))"
                )

                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    Text(DulcetStrings.albums)
                        .font(.title2.weight(.semibold))

                    ScrollView(.horizontal) {
                        HStack(alignment: .top, spacing: DulcetSpacing.md) {
                            ForEach(snapshot.albums.prefix(7)) { album in
                                DulcetAlbumShelfItem(album: album)
                            }
                        }
                        .padding(.bottom, DulcetSpacing.xs)
                    }
                    .scrollIndicators(.hidden)
                }

                VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                    HStack {
                        Text(DulcetStrings.recentlyAdded)
                            .font(.title2.weight(.semibold))
                        Spacer()
                        Text(DulcetStrings.duration)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    }

                    VStack(spacing: 0) {
                        ForEach(Array(recentTracks.enumerated()), id: \.element.id) { index, track in
                            DulcetTrackRow(track: track, showAlbum: true, index: index + 1)
                            if track.id != recentTracks.last?.id {
                                Divider().padding(.leading, 52)
                            }
                        }
                    }
                    .background(Color.dulcetControl.opacity(0.52), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(Color.dulcetSeparator.opacity(0.55), lineWidth: 1)
                    }
                }
            }
            .padding(DulcetSpacing.lg)
        }
        .background(Color.dulcetWindow)
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
                    .foregroundStyle(.secondary)
                    .lineLimit(nil)
            }
            Spacer(minLength: DulcetSpacing.md)
            HStack(spacing: DulcetSpacing.xs) {
                Button(DulcetStrings.playAll, systemImage: "play.fill") {}
                    .buttonStyle(.borderedProminent)
                    .tint(.dulcetAccent)
                    .foregroundStyle(Color.dulcetOnAccent)
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

    var body: some View {
        Button(action: {}) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                ZStack(alignment: .bottomTrailing) {
                    DulcetArtworkView(artwork: album.artwork, size: 126, muted: offline)
                    if offline {
                        Image(systemName: "cloud.slash.fill")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.white)
                            .padding(6)
                            .background(Color.black.opacity(0.64), in: Circle())
                            .padding(6)
                            .accessibilityHidden(true)
                    }
                }

                Text(album.title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                Text(album.albumArtists.joined(separator: ", "))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                Text(DulcetStrings.trackCount(album.tracks.count))
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .frame(width: dynamicTypeSize.isAccessibilitySize ? 190 : 126, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(album.title), \(album.albumArtists.joined(separator: ", ")), \(DulcetStrings.trackCount(album.tracks.count))")
        .accessibilityHint(offline ? DulcetStrings.offlineUnavailable : DulcetStrings.play)
    }
}

struct DulcetTrackRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let track: DulcetTrack
    let showAlbum: Bool
    let index: Int
    var offline = false

    var body: some View {
        Button(action: {}) {
            HStack(alignment: .center, spacing: DulcetSpacing.sm) {
                Group {
                    if offline {
                        Image(systemName: "cloud.slash")
                            .foregroundStyle(Color.dulcetOffline)
                    } else {
                        Text(String(index))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(width: 24)
                .accessibilityHidden(true)

                DulcetArtworkView(artwork: track.artwork, size: 34, muted: offline)

                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    Text(trackSubtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                }

                Spacer(minLength: DulcetSpacing.sm)

                if offline {
                    Text(DulcetStrings.offlineUnavailable)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Color.dulcetOffline)
                        .lineLimit(nil)
                } else {
                    Text(track.durationSeconds.dulcetDuration)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                Image(systemName: "ellipsis")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, DulcetSpacing.sm)
            .padding(.vertical, DulcetSpacing.xs)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(offline ? DulcetStrings.offlineUnavailable : DulcetStrings.play)
    }

    private var trackSubtitle: String {
        let artists = track.artistNames.joined(separator: ", ")
        if showAlbum, let album = track.albumTitle {
            return "\(artists) · \(album)"
        }
        if track.albumTitle == nil {
            return "\(artists) · \(DulcetStrings.withoutAlbum)"
        }
        return artists
    }

    private var accessibilityLabel: String {
        let availability = offline ? ", \(DulcetStrings.offlineUnavailable)" : ""
        return "\(track.title), \(trackSubtitle), \(track.durationSeconds.dulcetDuration)\(availability)"
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
                            let tracks = album.tracks.filter { $0.discNumber == disc }
                            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                                DulcetTrackRow(track: track, showAlbum: false, index: index + 1)
                                if track.id != tracks.last?.id {
                                    Divider().padding(.leading, 52)
                                }
                            }
                        }
                    }
                }
            }
            .padding(DulcetSpacing.xl)
        }
        .background(Color.dulcetWindow)
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
                .foregroundStyle(.secondary)
            Text(album.title)
                .font(.largeTitle.weight(.bold))
                .lineLimit(nil)
            Text(album.albumArtists.joined(separator: ", "))
                .font(.title3)
                .foregroundStyle(.secondary)
                .lineLimit(nil)
            Text("\(album.year) · \(DulcetStrings.trackCount(album.tracks.count)) · \(album.totalDurationSeconds.dulcetDuration)")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(nil)
        }
    }

    private var albumActions: some View {
        HStack(spacing: DulcetSpacing.xs) {
            Button(DulcetStrings.play, systemImage: "play.fill") {}
                .buttonStyle(.borderedProminent)
                .tint(.dulcetAccent)
                .foregroundStyle(Color.dulcetOnAccent)
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
                            Text("\(DulcetStrings.lastSynced) \(lastSynced)")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(nil)
                        }
                    }
                    Spacer()
                    Button(DulcetStrings.tryAgain, systemImage: "arrow.clockwise") {}
                        .buttonStyle(.borderedProminent)
                        .tint(.dulcetOffline)
                        .foregroundStyle(Color.dulcetOnAccent)
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
                        DulcetTrackRow(track: track, showAlbum: true, index: index + 1, offline: true)
                        if track.id != tracks.last?.id {
                            Divider().padding(.leading, 52)
                        }
                    }
                }
                .background(Color.dulcetControl.opacity(0.52), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .padding(DulcetSpacing.lg)
        }
        .background(Color.dulcetWindow)
    }

    private var offlineBanner: some View {
        HStack(alignment: .top, spacing: DulcetSpacing.md) {
            Image(systemName: "wifi.slash")
                .font(.title2.weight(.semibold))
                .foregroundStyle(Color.dulcetOffline)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                Text(DulcetStrings.offlineTitle)
                    .font(.headline)
                Text(DulcetStrings.offlineBody)
                    .font(.body)
                    .foregroundStyle(.secondary)
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
