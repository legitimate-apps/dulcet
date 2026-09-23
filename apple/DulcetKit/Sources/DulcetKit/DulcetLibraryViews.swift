#if os(macOS) || os(iOS) || os(tvOS)
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
                        .dulcetDefaultActionShortcut()
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

struct DulcetSavedAccountLibraryView: View {
    let serverName: String
    let onReconnect: () -> Void

    var body: some View {
        VStack(spacing: DulcetSpacing.lg) {
            Image(systemName: "bolt.horizontal.circle")
                .font(.system(size: 42, weight: .medium))
                .dulcetForeground(.accentIconOnTint)
                .accessibilityHidden(true)
            Text(DulcetStrings.reconnectToServer(serverName))
                .font(.largeTitle.weight(.semibold))
                .multilineTextAlignment(.center)
                .lineLimit(nil)
            Text(DulcetStrings.savedAccountDisconnectedBody)
                .font(.title3)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
                .frame(maxWidth: 560)
            Button(DulcetStrings.reconnect, systemImage: "arrow.clockwise", action: onReconnect)
                .buttonStyle(.borderedProminent)
                .dulcetDefaultActionShortcut()
                .accessibilityLabel(DulcetStrings.reconnectToServer(serverName))
            Text(DulcetStrings.savedAccountDisconnectedFootnote)
                .font(.footnote)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(.center)
                .lineLimit(nil)
                .frame(maxWidth: 520)
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

    private var message: String { Self.message(for: failure) }

    /// Shared with the album-detail track-list failure so one library failure kind cannot be
    /// explained two different ways.
    static func message(for failure: DulcetLibraryFailure?) -> String {
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

enum DulcetResponsiveGridLayout {
    static func columns(
        containerWidth: CGFloat,
        horizontalInsets: CGFloat,
        minimumItemWidth: CGFloat,
        spacing: CGFloat,
        alignment: Alignment
    ) -> [GridItem] {
        let availableWidth = max(0, containerWidth - horizontalInsets)
        let count = columnCount(
            availableWidth: availableWidth,
            minimumItemWidth: minimumItemWidth,
            spacing: spacing
        )
        return Array(
            repeating: GridItem(
                .flexible(minimum: min(minimumItemWidth, availableWidth)),
                spacing: spacing,
                alignment: alignment
            ),
            count: count
        )
    }

    static func columnCount(
        availableWidth: CGFloat,
        minimumItemWidth: CGFloat,
        spacing: CGFloat
    ) -> Int {
        max(1, Int((max(0, availableWidth) + spacing) / (minimumItemWidth + spacing)))
    }
}

struct DulcetLibraryBrowseView: View {
    let snapshot: DulcetSnapshot
    var onSelectAlbum: (DulcetAlbum) -> Void = { _ in }
    var onSelectArtist: ((DulcetArtist) -> Void)?
    var onPlayAll: () -> Void = {}
    var onShuffle: () -> Void = {}

    private var totalTracks: Int {
        // The album list declares each album's track count, so this is right before any track
        // list has been read.
        snapshot.albums.reduce(0) { $0 + $1.trackCount } + snapshot.looseTracks.count
    }

    var body: some View {
        // Consume the screen's proposed width outside the ScrollView. Window resizing
        // remains responsive without feeding a measured child size back into view
        // selection or relying on GridItem.adaptive candidate resolution.
        GeometryReader { geometry in
            let insets = DulcetLibraryMetrics.horizontalInset(forWidth: geometry.size.width)
            ScrollView {
                VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                    DulcetLibraryHeader(
                        title: DulcetStrings.library,
                        subtitle: DulcetStrings.librarySummary(
                            albumCount: snapshot.albums.count,
                            trackCount: totalTracks
                        ),
                        playbackEnabled: snapshot.canPlayWholeLibrary,
                        onPlayAll: onPlayAll,
                        onShuffle: onShuffle
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
                                .accessibilityAddTraits(.isHeader)
                            LazyVGrid(
                                columns: DulcetResponsiveGridLayout.columns(
                                    containerWidth: geometry.size.width,
                                    horizontalInsets: insets * 2,
                                    minimumItemWidth: 160,
                                    spacing: DulcetSpacing.xs,
                                    alignment: .leading
                                ),
                                alignment: .leading,
                                spacing: DulcetSpacing.xs
                            ) {
                                ForEach(snapshot.artists) { artist in
                                    DulcetArtistNameCell(artist: artist, onSelect: onSelectArtist)
                                }
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                        Text(DulcetStrings.albums)
                            .font(.title2.weight(.semibold))
                            .accessibilityAddTraits(.isHeader)

                        let minimumTile = DulcetLibraryMetrics.shelfItemMinimumWidth(
                            forWidth: geometry.size.width
                        )
                        LazyVGrid(
                            columns: DulcetResponsiveGridLayout.columns(
                                containerWidth: geometry.size.width,
                                horizontalInsets: insets * 2,
                                minimumItemWidth: minimumTile,
                                spacing: DulcetSpacing.sm,
                                alignment: .topLeading
                            ),
                            alignment: .leading,
                            spacing: DulcetSpacing.md
                        ) {
                            ForEach(snapshot.albums) { album in
                                DulcetAlbumShelfItem(
                                    album: album,
                                    tileWidth: DulcetLibraryMetrics.tileWidth(
                                        containerWidth: geometry.size.width - insets * 2,
                                        minimumItemWidth: minimumTile,
                                        spacing: DulcetSpacing.sm
                                    )
                                ) {
                                    onSelectAlbum(album)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, insets)
                .padding(.vertical, DulcetSpacing.lg)
            }
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
    }
}

/// Layout numbers the library surfaces share, derived from the width they were given rather than
/// from the platform, so a narrow iPad split column behaves like a phone and a wide one does not.
enum DulcetLibraryMetrics {
    /// Below this width the header and album pages switch from side by side to one column.
    static let compactWidthThreshold: CGFloat = 600

    static func horizontalInset(forWidth width: CGFloat) -> CGFloat {
        width < compactWidthThreshold ? DulcetSpacing.md : DulcetSpacing.lg
    }

    /// Two album columns on a phone, however narrow: a 126-point tile beside a second one needs
    /// 290 points, and an iPhone SE-width screen offers 288 once inset.
    static func shelfItemMinimumWidth(forWidth width: CGFloat) -> CGFloat {
        width < compactWidthThreshold ? 130 : 150
    }

    /// A tile fills its column, as album grids do, up to a size where artwork stops reading as a
    /// grid of covers.
    static func tileWidth(
        containerWidth: CGFloat,
        minimumItemWidth: CGFloat,
        spacing: CGFloat
    ) -> CGFloat {
        let count = CGFloat(DulcetResponsiveGridLayout.columnCount(
            availableWidth: containerWidth,
            minimumItemWidth: minimumItemWidth,
            spacing: spacing
        ))
        let fill = (max(0, containerWidth) - spacing * (count - 1)) / count
        return min(max(minimumItemWidth * 0.8, fill.rounded(.down)), 220)
    }
}

/// One artist in the library's artist list. A link to the artist's page when one can be shown.
private struct DulcetArtistNameCell: View {
    let artist: DulcetArtist
    let onSelect: ((DulcetArtist) -> Void)?

    var body: some View {
        if let onSelect {
            Button {
                onSelect(artist)
            } label: {
                HStack(spacing: DulcetSpacing.xxs) {
                    Text(artist.name)
                        .font(.headline)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Image(systemName: "chevron.forward")
                        .font(.caption.weight(.semibold))
                        .dulcetForeground(.secondaryTextOnWindow)
                        .accessibilityHidden(true)
                }
                .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                .contentShape(Rectangle())
            }
            .dulcetMediaButtonStyle()
            .accessibilityLabel(artist.name)
            .accessibilityAddTraits(.isLink)
            .accessibilityIdentifier("dulcet.library.artist")
        } else {
            Text(artist.name)
                .font(.headline)
                .lineLimit(nil)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/// The library's heading with Play All and Shuffle.
///
/// Side by side when it fits; otherwise the title above two equal-width buttons. The decision is
/// ViewThatFits over the header's own ideal width, so a long localized title, a narrow phone, a
/// narrow iPad split column and a large Dynamic Type size all take the stacked form without a
/// platform check. The button labels never wrap: a label that does not fit on one line is the
/// signal to stack, not to break "Shuffle" one letter per line.
struct DulcetLibraryHeader: View {
    let title: String
    let subtitle: String
    var playbackEnabled = true
    var onPlayAll: () -> Void = {}
    var onShuffle: () -> Void = {}

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: DulcetSpacing.md) {
                heading
                Spacer(minLength: DulcetSpacing.md)
                HStack(spacing: DulcetSpacing.xs) {
                    playAllButton
                    shuffleButton
                }
                .fixedSize()
            }
            VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                heading
                DulcetEqualWidthActions {
                    playAllButton
                } secondary: {
                    shuffleButton
                }
            }
        }
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
#if !os(iOS)
            // On iOS the navigation bar's large title already says it.
            Text(title)
                .font(.largeTitle.weight(.bold))
                .lineLimit(nil)
                .accessibilityAddTraits(.isHeader)
#endif
            Text(subtitle)
                .font(.subheadline)
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
        }
    }

    private var playAllButton: some View {
        Button(action: onPlayAll) {
            Label(DulcetStrings.playAll, systemImage: "play.fill")
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .dulcetForeground(.labelOnAccentFill)
        }
        .buttonStyle(.borderedProminent)
        .dulcetDefaultActionShortcut()
        .disabled(!playbackEnabled)
        .help(playbackEnabled ? DulcetStrings.playAll : DulcetStrings.libraryTracksLoading)
        .accessibilityLabel(DulcetStrings.playAll)
    }

    private var shuffleButton: some View {
        Button(action: onShuffle) {
            Label(DulcetStrings.shuffle, systemImage: "shuffle")
                .lineLimit(1)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(!playbackEnabled)
        .help(playbackEnabled ? DulcetStrings.shuffle : DulcetStrings.libraryTracksLoading)
        .accessibilityLabel(DulcetStrings.shuffle)
    }
}

/// Two actions of equal width across the available space, side by side; stacked when either
/// label cannot fit on one line at half the width (large Dynamic Type on a narrow screen).
struct DulcetEqualWidthActions<Primary: View, Secondary: View>: View {
    @ViewBuilder let primary: Primary
    @ViewBuilder let secondary: Secondary

    var body: some View {
        ViewThatFits(in: .horizontal) {
            DulcetEqualWidthHStack(spacing: DulcetSpacing.sm) {
                primary
                secondary
            }
            VStack(spacing: DulcetSpacing.xs) {
                primary
                secondary
            }
        }
        .controlSize(.large)
    }
}

/// Lays its children out in one row of equal widths.
///
/// Its ideal width is the widest child's ideal width times the child count, so a ViewThatFits
/// around it falls back to another arrangement exactly when one child could not show its label
/// in an equal share. Given more room than that, it fills it, still in equal shares.
struct DulcetEqualWidthHStack: Layout {
    var spacing: CGFloat

    func sizeThatFits(
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) -> CGSize {
        guard !subviews.isEmpty else { return .zero }
        let ideal = subviews.map { $0.sizeThatFits(.unspecified) }
        let widest = ideal.map(\.width).max() ?? 0
        let tallest = ideal.map(\.height).max() ?? 0
        let minimum = widest * CGFloat(subviews.count) + totalSpacing(subviews.count)
        guard let proposed = proposal.width, proposed.isFinite else {
            return CGSize(width: minimum, height: tallest)
        }
        return CGSize(width: max(minimum, proposed), height: tallest)
    }

    func placeSubviews(
        in bounds: CGRect,
        proposal: ProposedViewSize,
        subviews: Subviews,
        cache: inout ()
    ) {
        guard !subviews.isEmpty else { return }
        let width = (bounds.width - totalSpacing(subviews.count)) / CGFloat(subviews.count)
        var x = bounds.minX
        for subview in subviews {
            subview.place(
                at: CGPoint(x: x, y: bounds.midY),
                anchor: .leading,
                proposal: ProposedViewSize(width: width, height: bounds.height)
            )
            x += width + spacing
        }
    }

    private func totalSpacing(_ count: Int) -> CGFloat {
        spacing * CGFloat(max(0, count - 1))
    }
}

struct DulcetAlbumShelfItem: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let album: DulcetAlbum
    var offline = false
    /// The grid's column width, when the tile should fill its column; otherwise the fixed shelf
    /// size.
    var tileWidth: CGFloat?
    var onSelect: () -> Void = {}

    private var width: CGFloat {
        tileWidth ?? (dynamicTypeSize.isAccessibilitySize ? 190 : 126)
    }

    var body: some View {
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                ZStack(alignment: .bottomTrailing) {
                    DulcetArtworkView(artwork: album.artwork, size: tileWidth ?? 126, muted: offline)
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
                Text(DulcetStrings.trackCount(album.trackCount))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
            }
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
        }
        .dulcetMediaButtonStyle()
        .accessibilityLabel(DulcetStrings.albumAccessibility(
            album.title,
            artists: DulcetStrings.artistNames(album.albumArtists),
            tracks: DulcetStrings.trackCount(album.trackCount)
        ))
        .accessibilityHint(offline ? DulcetStrings.offlineUnavailable : DulcetStrings.play)
        .dulcetAlbumContextMenu(album: album, isEnabled: !offline)
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
    var isCurrent = false
    /// Album pages number their rows and leave artwork to the header, as a track listing does.
    var showsArtwork = true
    /// The album's own artists, for a row without artwork: the row names its artists only when
    /// they differ.
    var albumArtists: [String] = []
    var onActivate: (() -> Void)?
    var onDownload: (() -> Void)?

    private var unavailableOffline: Bool {
        offline && !track.downloadState.isLocallyPlayable
    }

    @ViewBuilder
    var body: some View {
        if unavailableOffline {
            rowContent
                .dulcetForeground(surface.primaryPair)
                .accessibilityLabel(rowAccessibilityLabel)
                .accessibilityHint(DulcetStrings.offlineUnavailable)
        } else if onActivate == nil {
            rowContent
                .dulcetForeground(surface.primaryPair)
                .accessibilityLabel(rowAccessibilityLabel)
        } else {
        #if os(macOS)
            rowContent
                .contentShape(Rectangle())
                .gesture(
                    TapGesture(count: 2)
                        .exclusively(before: TapGesture(count: 1))
                        .onEnded { _ in performActivation() }
                )
                .focusable()
                .onKeyPress(keys: [.return, .space]) { _ in
                    performActivation()
                    return .handled
                }
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { performActivation() }
                .dulcetForeground(surface.primaryPair)
                .accessibilityLabel(rowAccessibilityLabel)
                .accessibilityHint(DulcetStrings.play)
        #else
            Button(action: performActivation) {
                rowContent
            }
            .dulcetMediaButtonStyle()
            .dulcetForeground(surface.primaryPair)
            .accessibilityLabel(rowAccessibilityLabel)
            .accessibilityHint(DulcetStrings.play)
        #endif
        }
    }

    private var rowContent: some View {
        HStack(alignment: .center, spacing: DulcetSpacing.xs) {
                Group {
                    if unavailableOffline {
                        Image(systemName: "cloud.slash")
                            .dulcetForeground(surface.offlinePair)
                    } else if isCurrent {
                        Image(systemName: "speaker.wave.2.fill")
                            .dulcetForeground(surface.primaryPair)
                    } else {
                        Text(String(track.trackNumber.map { showsArtwork ? index : $0 } ?? index))
                            .font(showsArtwork ? .caption.monospacedDigit() : .callout.monospacedDigit())
                            .dulcetForeground(surface.secondaryPair)
                    }
                }
                .frame(minWidth: 20)
                .accessibilityHidden(true)

                if showsArtwork {
                    DulcetArtworkView(
                        artwork: track.artwork,
                        size: DulcetMetrics.denseRowArtworkSize,
                        muted: unavailableOffline
                    )
                }

                VStack(alignment: .leading, spacing: 0) {
                    Text(track.title)
                        .font(showsArtwork ? .callout.weight(.medium) : .body)
                        .dulcetForeground(surface.primaryPair)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    if showsArtwork || showsArtistLine {
                        Text(trackSubtitle)
                            .font(.caption)
                            .dulcetForeground(surface.secondaryPair)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    }
                }

                Spacer(minLength: DulcetSpacing.xs)

                if unavailableOffline {
                    Text(DulcetStrings.offlineUnavailable)
                        .font(.caption.weight(.medium))
                        .dulcetForeground(surface.offlinePair)
                        .lineLimit(nil)
                } else {
                    Text(track.duration.dulcetDuration)
                        .font(.caption.monospacedDigit())
                        .dulcetForeground(surface.secondaryPair)
                }

                #if os(macOS)
                if !offline, let onDownload {
                    downloadControl(action: onDownload)
                }
                #endif

        }
        .padding(.horizontal, DulcetSpacing.xs)
        .padding(.vertical, DulcetMetrics.denseRowVerticalPadding)
#if os(iOS)
        // A touch listing gets full-height rows; the dense form is a pointer layout.
        .frame(minHeight: showsArtwork ? nil : 44)
#endif
        .contentShape(Rectangle())
    }

    private func performActivation() {
        guard !unavailableOffline, let onActivate else { return }
        onActivate()
    }

    /// An album listing repeats the album artist on every row only when this track's artists
    /// differ from it, which is when the line carries information.
    private var showsArtistLine: Bool {
        !track.artistNames.isEmpty && Set(track.artistNames) != Set(albumArtists)
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
        if unavailableOffline {
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

    private var rowAccessibilityLabel: String {
        isCurrent ? DulcetStrings.currentTrackAccessibility(accessibilityLabel) : accessibilityLabel
    }

    #if os(macOS)
    @ViewBuilder
    private func downloadControl(action: @escaping () -> Void) -> some View {
        switch track.downloadState {
        case .notDownloaded:
            Button(DulcetStrings.download, systemImage: "arrow.down.circle", action: action)
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .help(DulcetStrings.download)
                .accessibilityLabel(DulcetStrings.download)
        case .queued, .downloading:
            ProgressView()
                .controlSize(.small)
                .help(DulcetStrings.downloading)
                .accessibilityLabel(DulcetStrings.downloading)
        case .interrupted, .failed:
            Button(DulcetStrings.retryDownload, systemImage: "arrow.clockwise", action: action)
                .labelStyle(.iconOnly)
                .buttonStyle(.borderless)
                .help(DulcetStrings.retryDownload)
                .accessibilityLabel(DulcetStrings.retryDownload)
        case .downloaded:
            Image(systemName: "checkmark.circle.fill")
                .dulcetForeground(surface.secondaryPair)
                .help(DulcetStrings.downloaded)
                .accessibilityLabel(DulcetStrings.downloaded)
        case .stale:
            Image(systemName: "exclamationmark.arrow.triangle.2.circlepath")
                .dulcetForeground(surface.secondaryPair)
                .help(DulcetStrings.downloadUpdateAvailable)
                .accessibilityLabel(DulcetStrings.downloadUpdateAvailable)
        }
    }
    #endif
}

struct DulcetAlbumDetailView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let album: DulcetAlbum
    var tracksFailure: DulcetLibraryFailure?
    var onPlay: () -> Void = {}
    var onShuffle: () -> Void = {}
    var onActivateTrack: (DulcetTrack) -> Void = { _ in }
    var onDownloadTrack: ((DulcetTrack) -> Void)?
    var onRetryTracks: () -> Void = {}

    var body: some View {
        // The width is read before the ScrollView, as the library grid does, so the header's
        // arrangement follows the space the page was given and nothing measured inside it.
        GeometryReader { geometry in
            let width = geometry.size.width
            let inset = DulcetLibraryMetrics.horizontalInset(forWidth: width)
            ScrollView {
                VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                    DulcetAlbumHeader(
                        album: album,
                        layout: Self.headerLayout(
                            width: width,
                            accessibilitySize: dynamicTypeSize.isAccessibilitySize
                        ),
                        artworkSize: Self.artworkSize(
                            width: width,
                            inset: inset,
                            accessibilitySize: dynamicTypeSize.isAccessibilitySize
                        ),
                        onPlay: onPlay,
                        onShuffle: onShuffle
                    )

                    if let tracksFailure {
                        trackListFailure(tracksFailure)
                    } else if !album.areTracksLoaded {
                        trackListLoading
                    }

                    trackListing
                }
                .padding(.horizontal, inset)
                .padding(.vertical, DulcetSpacing.lg)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(album.title)
#if os(iOS)
        .toolbarTitleDisplayMode(.inline)
#endif
    }

    enum HeaderLayout: Equatable {
        /// Artwork beside the album's identity and actions. Wide windows.
        case sideBySide
        /// Artwork above a centered identity, with Play and Shuffle as two equal-width buttons.
        case stacked
    }

    /// One decision, on the width the page was given: a narrow iPad split column is laid out
    /// like a phone, and a phone in landscape like a narrow window. Accessibility text sizes
    /// always stack, because a large title beside artwork has no room to wrap.
    static func headerLayout(width: CGFloat, accessibilitySize: Bool) -> HeaderLayout {
        accessibilitySize || width < DulcetLibraryMetrics.compactWidthThreshold
            ? .stacked
            : .sideBySide
    }

    static func artworkSize(width: CGFloat, inset: CGFloat, accessibilitySize: Bool) -> CGFloat {
        switch headerLayout(width: width, accessibilitySize: accessibilitySize) {
        case .sideBySide:
            return 220
        case .stacked:
            // Most of the width, leaving the page's rhythm visible either side: large, but not
            // edge to edge, and capped so a landscape phone does not push the tracks off screen.
            let available = max(0, width - 2 * inset)
            let cap: CGFloat = accessibilitySize ? 220 : 300
            return max(120, min(available * 0.72, cap))
        }
    }

    @ViewBuilder
    private var trackListing: some View {
        let multipleDiscs = album.discNumbers.count > 1
        ForEach(album.discNumbers, id: \.self) { disc in
            VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                if multipleDiscs {
                    Text(DulcetStrings.discTitle(disc))
                        .font(.headline)
                        .dulcetForeground(.secondaryTextOnWindow)
                        .accessibilityAddTraits(.isHeader)
                }

                VStack(spacing: 0) {
                    let tracks = album.tracks.filter { ($0.discNumber ?? 1) == disc }
                    ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                        DulcetTrackRow(
                            track: track,
                            showAlbum: false,
                            index: index + 1,
                            surface: .window,
                            showsArtwork: false,
                            albumArtists: album.albumArtists,
                            onActivate: { onActivateTrack(track) },
                            onDownload: onDownloadTrack.map { handler in
                                { handler(track) }
                            }
                        )
                        .dulcetTrackContextMenu(
                            track: track,
                            onPlay: { onActivateTrack(track) },
                            offersAlbum: false
                        )
                        if track.id != tracks.last?.id {
                            Divider().padding(.leading, DulcetMetrics.denseRowSeparatorInset)
                        }
                    }
                }
            }
        }
    }

    private var trackListLoading: some View {
        HStack(spacing: DulcetSpacing.sm) {
            ProgressView()
                .controlSize(.small)
                .accessibilityHidden(true)
            Text(DulcetStrings.albumTracksLoading)
                .dulcetForeground(.secondaryTextOnWindow)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(DulcetStrings.albumTracksLoading)
    }

    private func trackListFailure(_ failure: DulcetLibraryFailure) -> some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
            Label(DulcetStrings.albumTracksErrorTitle, systemImage: "exclamationmark.triangle")
                .font(.headline)
            Text(DulcetLibraryErrorView.message(for: failure))
                .dulcetForeground(.secondaryTextOnWindow)
                .lineLimit(nil)
            Button(DulcetStrings.albumTracksRetry, systemImage: "arrow.clockwise", action: onRetryTracks)
                .buttonStyle(.bordered)
                .accessibilityLabel(DulcetStrings.albumTracksRetry)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// An album page's header: artwork, title, artist, a metadata line, and Play / Shuffle.
struct DulcetAlbumHeader: View {
    let album: DulcetAlbum
    let layout: DulcetAlbumDetailView.HeaderLayout
    let artworkSize: CGFloat
    let onPlay: () -> Void
    let onShuffle: () -> Void

    var body: some View {
        switch layout {
        case .sideBySide:
            HStack(alignment: .bottom, spacing: DulcetSpacing.lg) {
                artwork
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    identity(alignment: .leading)
                    DulcetEqualWidthActions {
                        playButton
                    } secondary: {
                        shuffleButton
                    }
                    .frame(maxWidth: 360)
                }
                // Identity takes what the artwork leaves; it wraps, it never collapses.
                .frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(1)
            }
        case .stacked:
            VStack(spacing: DulcetSpacing.md) {
                artwork
                identity(alignment: .center)
                DulcetEqualWidthActions {
                    playButton
                } secondary: {
                    shuffleButton
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var artwork: some View {
        DulcetArtworkView(artwork: album.artwork, size: artworkSize)
            .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
            .accessibilityIdentifier("dulcet.album.artwork")
    }

    private func identity(alignment: HorizontalAlignment) -> some View {
        let textAlignment: TextAlignment = alignment == .leading ? .leading : .center
        return VStack(alignment: alignment, spacing: DulcetSpacing.xxs) {
            if layout == .sideBySide {
                Text(DulcetStrings.album.uppercased())
                    .font(.caption.weight(.semibold))
                    .dulcetForeground(.secondaryTextOnWindow)
            }
            Text(album.title)
                .font(layout == .sideBySide ? .largeTitle.weight(.bold) : .title2.weight(.bold))
                .multilineTextAlignment(textAlignment)
                .lineLimit(nil)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("dulcet.album.title")
            DulcetArtistLink(
                credits: album.credits.filter { $0.role == .albumArtist },
                font: .title3
            )
            .multilineTextAlignment(textAlignment)
            .accessibilityIdentifier("dulcet.album.artist")
            Text(DulcetStrings.albumMetadata(
                year: album.year,
                tracks: DulcetStrings.trackCount(album.trackCount),
                duration: album.totalDuration.dulcetDuration
            ))
                .font(.subheadline)
                .dulcetForeground(.secondaryTextOnWindow)
                .multilineTextAlignment(textAlignment)
                .lineLimit(nil)
        }
        .frame(maxWidth: .infinity, alignment: Alignment(horizontal: alignment, vertical: .center))
    }

    private var playButton: some View {
        Button(action: onPlay) {
            Label(DulcetStrings.play, systemImage: "play.fill")
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                .dulcetForeground(.labelOnAccentFill)
        }
        .buttonStyle(.borderedProminent)
        .dulcetDefaultActionShortcut()
        .disabled(album.tracks.isEmpty)
        .accessibilityLabel(DulcetStrings.play)
        .accessibilityIdentifier("dulcet.album.play")
    }

    private var shuffleButton: some View {
        Button(action: onShuffle) {
            Label(DulcetStrings.shuffle, systemImage: "shuffle")
                .lineLimit(1)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .disabled(album.tracks.isEmpty)
        .accessibilityLabel(DulcetStrings.shuffle)
        .accessibilityIdentifier("dulcet.album.shuffle")
    }
}

/// Artist names that lead to the artist's page when the library has one.
///
/// One linkable artist is a button; several are a menu naming each; none is plain text. A name
/// is never styled as a link unless following it shows a page.
struct DulcetArtistLink: View {
    @Environment(DulcetPresentationStore.self) private var store
    let credits: [DulcetCredit]
    var font: Font = .body
    var onNavigate: () -> Void = {}

    var body: some View {
        let names = DulcetStrings.artistNames(credits.map(\.name))
        let targets = linkTargets
#if os(tvOS)
        plain(names)
#else
        if targets.count == 1, let target = targets.first {
            Button {
                onNavigate()
                store.showArtist(target.id)
            } label: {
                Text(names).font(font)
            }
            .buttonStyle(.plain)
            .dulcetForeground(.accentTextOnWindow)
            .accessibilityAddTraits(.isLink)
            .accessibilityHint(DulcetStrings.goToArtist)
        } else if targets.count > 1 {
            Menu {
                ForEach(targets, id: \.id) { target in
                    Button(target.name) {
                        onNavigate()
                        store.showArtist(target.id)
                    }
                }
            } label: {
                Text(names).font(font)
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .fixedSize()
            .dulcetForeground(.accentTextOnWindow)
            .accessibilityHint(DulcetStrings.goToArtist)
        } else {
            plain(names)
        }
#endif
    }

    private func plain(_ names: String) -> some View {
        Text(names)
            .font(font)
            .dulcetForeground(.secondaryTextOnWindow)
            .lineLimit(nil)
    }

    private var linkTargets: [(name: String, id: DulcetProviderItemID)] {
        var seen = Set<DulcetProviderItemID>()
        return credits.compactMap { credit in
            guard let id = store.libraryArtistID(for: credit), seen.insert(id).inserted else {
                return nil
            }
            return (credit.name, id)
        }
    }
}

/// An album title that leads to the album's page when the library can identify it.
struct DulcetAlbumLink: View {
    @Environment(DulcetPresentationStore.self) private var store
    let track: DulcetTrack
    let title: String
    var onNavigate: () -> Void = {}

    var body: some View {
#if os(tvOS)
        plain
#else
        if let id = store.libraryAlbumID(for: track) {
            Button {
                onNavigate()
                store.showAlbum(id)
            } label: {
                Text(title).font(.subheadline)
            }
            .buttonStyle(.plain)
            .dulcetForeground(.secondaryTextOnWindow)
            .accessibilityAddTraits(.isLink)
            .accessibilityHint(DulcetStrings.goToAlbum)
        } else {
            plain
        }
#endif
    }

    private var plain: some View {
        Text(title)
            .font(.subheadline)
            .dulcetForeground(.secondaryTextOnWindow)
            .lineLimit(nil)
    }
}

extension View {
    /// Play, Play Next, Add to Queue, Go to Album and Go to Artist for one track — each only
    /// when it can act. Nothing on tvOS, whose focus engine owns the long press.
    func dulcetTrackContextMenu(
        track: DulcetTrack,
        onPlay: (() -> Void)? = nil,
        offersAlbum: Bool = true,
        onNavigate: @escaping () -> Void = {}
    ) -> some View {
        modifier(DulcetTrackContextMenu(
            track: track,
            onPlay: onPlay,
            offersAlbum: offersAlbum,
            onNavigate: onNavigate
        ))
    }

    /// Play, Shuffle, Play Next, Add to Queue and Go to Artist for one album, each only when it
    /// can act: an album whose track list has not been read yet has nothing to play.
    func dulcetAlbumContextMenu(album: DulcetAlbum, isEnabled: Bool = true) -> some View {
        modifier(DulcetAlbumContextMenu(album: album, isEnabled: isEnabled))
    }
}

private struct DulcetTrackContextMenu: ViewModifier {
    @Environment(DulcetPresentationStore.self) private var store
    let track: DulcetTrack
    let onPlay: (() -> Void)?
    let offersAlbum: Bool
    let onNavigate: () -> Void

    func body(content: Content) -> some View {
#if os(tvOS)
        content
#else
        content.contextMenu {
            if let onPlay, track.availability == .playable {
                Button(DulcetStrings.play, systemImage: "play", action: onPlay)
            }
            DulcetQueueInsertionMenuItems(tracks: [track])
            if offersAlbum, let albumID = store.libraryAlbumID(for: track) {
                Button(DulcetStrings.goToAlbum, systemImage: "square.stack") {
                    onNavigate()
                    store.showAlbum(albumID)
                }
            }
            DulcetGoToArtistMenuItems(
                credits: track.credits.filter { $0.role == .artist },
                onNavigate: onNavigate
            )
        }
#endif
    }
}

private struct DulcetAlbumContextMenu: ViewModifier {
    @Environment(DulcetPresentationStore.self) private var store
    let album: DulcetAlbum
    let isEnabled: Bool

    func body(content: Content) -> some View {
#if os(tvOS)
        content
#else
        if isEnabled {
            content.contextMenu {
                if !album.tracks.isEmpty {
                    Button(DulcetStrings.play, systemImage: "play") {
                        store.playAlbum(album.id, shuffle: false)
                    }
                    Button(DulcetStrings.shuffle, systemImage: "shuffle") {
                        store.playAlbum(album.id, shuffle: true)
                    }
                    DulcetQueueInsertionMenuItems(tracks: album.tracks)
                }
                DulcetGoToArtistMenuItems(
                    credits: album.credits.filter { $0.role == .albumArtist }
                )
            }
        } else {
            content
        }
#endif
    }
}

#if !os(tvOS)
private struct DulcetQueueInsertionMenuItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    let tracks: [DulcetTrack]

    var body: some View {
        if store.queueInsertionEnabled, !tracks.isEmpty {
            Button(DulcetStrings.playNext, systemImage: "text.line.first.and.arrowtriangle.forward") {
                store.insertIntoQueue(tracks, placement: .next)
            }
            Button(DulcetStrings.addToQueue, systemImage: "text.line.last.and.arrowtriangle.forward") {
                store.insertIntoQueue(tracks, placement: .last)
            }
        }
    }
}

private struct DulcetGoToArtistMenuItems: View {
    @Environment(DulcetPresentationStore.self) private var store
    let credits: [DulcetCredit]
    var onNavigate: () -> Void = {}

    var body: some View {
        let targets = credits.compactMap { credit in
            store.libraryArtistID(for: credit).map { (name: credit.name, id: $0) }
        }
        if targets.count == 1, let target = targets.first {
            Button(DulcetStrings.goToArtist, systemImage: "music.mic") {
                onNavigate()
                store.showArtist(target.id)
            }
        } else if targets.count > 1 {
            Menu(DulcetStrings.goToArtist, systemImage: "music.mic") {
                ForEach(targets, id: \.id) { target in
                    Button(target.name) {
                        onNavigate()
                        store.showArtist(target.id)
                    }
                }
            }
        }
    }
}
#endif

struct DulcetArtistDetailView: View {
    let artist: DulcetArtist
    let albums: [DulcetAlbum]
    var onSelectAlbum: (DulcetAlbum) -> Void = { _ in }

    var body: some View {
        GeometryReader { geometry in
            let insets = DulcetLibraryMetrics.horizontalInset(forWidth: geometry.size.width)
            let minimumTile = DulcetLibraryMetrics.shelfItemMinimumWidth(forWidth: geometry.size.width)
            ScrollView {
                VStack(alignment: .leading, spacing: DulcetSpacing.lg) {
                    VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                        Text(DulcetStrings.artist.uppercased())
                            .font(.caption.weight(.semibold))
                            .dulcetForeground(.secondaryTextOnWindow)
                        Text(artist.name)
                            .font(.largeTitle.weight(.bold))
                            .lineLimit(nil)
                            .accessibilityAddTraits(.isHeader)
                        Text(DulcetStrings.albumCount(albums.count))
                            .font(.subheadline)
                            .dulcetForeground(.secondaryTextOnWindow)
                    }

                    if !albums.isEmpty {
                        Text(DulcetStrings.albums)
                            .font(.title2.weight(.semibold))
                            .accessibilityAddTraits(.isHeader)
                        LazyVGrid(
                            columns: DulcetResponsiveGridLayout.columns(
                                containerWidth: geometry.size.width,
                                horizontalInsets: insets * 2,
                                minimumItemWidth: minimumTile,
                                spacing: DulcetSpacing.sm,
                                alignment: .topLeading
                            ),
                            alignment: .leading,
                            spacing: DulcetSpacing.md
                        ) {
                            ForEach(albums) { album in
                                DulcetAlbumShelfItem(
                                    album: album,
                                    tileWidth: DulcetLibraryMetrics.tileWidth(
                                        containerWidth: geometry.size.width - insets * 2,
                                        minimumItemWidth: minimumTile,
                                        spacing: DulcetSpacing.sm
                                    )
                                ) {
                                    onSelectAlbum(album)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, insets)
                .padding(.vertical, DulcetSpacing.lg)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background(Color.dulcetWindow)
        .dulcetForeground(.primaryTextOnWindow)
        .navigationTitle(artist.name)
#if os(iOS)
        .toolbarTitleDisplayMode(.inline)
#endif
    }
}

extension DulcetAlbum {
    func belongs(to artist: DulcetArtist) -> Bool {
        let credits = credits + tracks.flatMap(\.credits)
        return credits.contains { credit in
            credit.id == artist.id || (credit.id == nil && credit.name == artist.name)
        }
    }
}

struct DulcetOfflineLibraryView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let snapshot: DulcetSnapshot
    var onActivateTrack: (DulcetTrack) -> Void = { _ in }

    var body: some View {
        // This reader consumes the outer proposal before the vertical ScrollView,
        // so only the stable container width participates in the column decision.
        GeometryReader { geometry in
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
                            .dulcetDefaultActionShortcut()
                            .accessibilityLabel(DulcetStrings.tryAgain)
                    }

                    if dynamicTypeSize.isAccessibilitySize {
                        LazyVGrid(
                            columns: DulcetResponsiveGridLayout.columns(
                                containerWidth: geometry.size.width,
                                horizontalInsets: DulcetSpacing.lg * 2,
                                minimumItemWidth: 190,
                                spacing: DulcetSpacing.md,
                                alignment: .top
                            ),
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
                                surface: .control,
                                onActivate: track.downloadState.isLocallyPlayable
                                    ? { onActivateTrack(track) }
                                    : nil
                            )
                            if track.id != tracks.last?.id {
                                Divider().padding(.leading, DulcetMetrics.denseRowSeparatorInset)
                            }
                        }
                    }
                    .background(
                        Color.dulcetControl.opacity(0.52),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                    )
                }
                .padding(DulcetSpacing.lg)
            }
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
