import SwiftUI

/// A queue row has different space constraints from a library row: its transport stays visible.
/// At accessibility sizes metadata gets the whole column and controls get a separate line.
struct DulcetPlayerQueueRow<MenuActions: View, EditingActions: View>: View {
    let track: DulcetTrack
    let accessibilityLayout: Bool
    let isEditing: Bool
    let activationIdentifier: String
    let moreIdentifier: String
    let onActivate: () -> Void
    @ViewBuilder let menuActions: () -> MenuActions
    @ViewBuilder let editingActions: () -> EditingActions
    @State private var hovered = false
    @FocusState private var menuFocused: Bool

    var body: some View {
        Group {
            if accessibilityLayout {
                VStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    activation
                    if isEditing {
                        editingActions()
                    } else {
                        HStack {
                            DulcetPlayerQueueArtwork(artwork: track.artwork, size: 36)
                            Text(track.duration.dulcetDuration).font(.caption).dulcetForeground(.primaryTextOnWindow)
                            Spacer(minLength: 0)
                            more
                        }
                    }
                }
            } else {
                HStack(spacing: DulcetSpacing.sm) {
                    DulcetPlayerQueueArtwork(artwork: track.artwork, size: 48)
                    activation
                    if !isEditing {
                        Text(track.duration.dulcetDuration).font(.caption.monospacedDigit()).dulcetForeground(.primaryTextOnWindow)
                        more
                    }
                }
            }
        }
        .padding(.vertical, DulcetSpacing.xs)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
#if os(macOS)
        .onHover { hovered = $0 }
#endif
        .contextMenu { actions }
    }

    private var activation: some View {
        Button(action: onActivate) {
            VStack(alignment: .leading, spacing: 4) {
                Text(track.title)
                    .font(.headline)
                    .lineLimit(accessibilityLayout ? nil : 2)
                    .fixedSize(horizontal: false, vertical: true)
                Text(DulcetStrings.artistNames(track.artistNames))
                    .font(.subheadline)
                    .dulcetForeground(.primaryTextOnWindow)
                    .lineLimit(accessibilityLayout ? nil : 1)
                if let album = track.albumTitle {
                    Text(album)
                        .font(.caption)
                        .dulcetForeground(.primaryTextOnWindow)
                        .lineLimit(accessibilityLayout ? nil : 1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(activationIdentifier)
    }

    private var more: some View {
        Menu { actions } label: {
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
#if os(macOS)
                .opacity(hovered || menuFocused ? 1 : 0)
#endif
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .menuIndicator(.hidden)
        .buttonStyle(.plain)
        .focused($menuFocused)
        .accessibilityLabel(DulcetStrings.readerRowAccessibility(DulcetStrings.more, track.title))
        .accessibilityIdentifier(moreIdentifier)
    }

    @ViewBuilder private var actions: some View {
        DulcetTrackMenuItems(track: track, onPlay: onActivate, queueAddition: nil,
            offersAlbum: true, publishedRating: nil, onDownload: nil, onNavigate: {})
        menuActions()
    }
}

/// Missing queue covers stay neutral over either artwork palette.
struct DulcetPlayerQueueArtwork: View {
    let artwork: DulcetArtwork
    let size: CGFloat

    var body: some View {
        if artwork.remoteReference == nil {
            Image(systemName: "music.note")
                .font(.system(size: size * 0.45))
                .dulcetForeground(.primaryTextOnWindow)
                .frame(width: size, height: size)
                .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
                .accessibilityHidden(true)
        } else {
            DulcetArtworkView(artwork: artwork, size: size)
        }
    }
}
