import SwiftUI

// Immersive player labels use primary white rather than the shared library accent. The same
// library navigation and favorite session operations preserve link and pending/held semantics.
struct DulcetPlayerArtistLink: View {
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
            .dulcetHoverEffect()
            .dulcetForeground(.primaryTextOnWindow)
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
            .dulcetHoverEffect()
            .fixedSize()
            .dulcetForeground(.primaryTextOnWindow)
            .accessibilityHint(DulcetStrings.goToArtist)
        } else {
            plain(names)
        }
#endif
    }

    private func plain(_ names: String) -> some View {
        Text(names)
            .font(font)
            .dulcetForeground(.primaryTextOnWindow)
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


struct DulcetPlayerFavouriteButton: View {
    @Environment(DulcetPresentationStore.self) private var store
    let target: DulcetFavouriteTarget
    let published: Bool?
    var title: String = ""
    var size: Font = .body
    var identifier = "dulcet.reader.favorite"
    /// The smallest side of the control's hit area.
    var minimumSide: CGFloat = 32

    var body: some View {
        if let session = store.librarySession {
            let on = session.isFavourite(target.id, published: published)
            let state = session.favouriteState(target)
            Button {
                let accepted = session.toggleFavourite(target, published: published)
                DulcetUIProofMarkers.record("favorite:\(identifier):accepted=\(accepted)")
            } label: {
                ZStack(alignment: .bottomTrailing) {
                    Image(systemName: on ? "heart.fill" : "heart")
                        .font(size)
                        .dulcetForeground(.primaryTextOnWindow)
                    // Always present, hidden while settled: inserting it into the label on a
                    // press took accessibility focus off the focused heart on Apple TV while the
                    // focus effect stayed drawn on it (OBSERVED on a tvOS 26.5 simulator), so an
                    // assistive reader lost the control the person had just pressed.
                    Image(systemName: state == .settled || state == .pending ? "clock" : "exclamationmark.circle.fill")
                        .font(.caption2.weight(.bold))
                        .dulcetForeground(.secondaryTextOnWindow)
                        .offset(x: 6, y: 4)
                        .opacity(state == .settled ? 0 : 1)
                        .accessibilityHidden(true)
                }
                .frame(minWidth: minimumSide, minHeight: minimumSide)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
#if os(macOS)
            .help(on ? DulcetStrings.unfavorite : DulcetStrings.favorite)
#endif
            .accessibilityLabel(on ? DulcetStrings.unfavorite : DulcetStrings.favorite)
            .accessibilityValue(stateDescription(state, title: title, on: on))
            .accessibilityIdentifier(identifier)
        }
    }

    private func stateDescription(_ state: DulcetFavouriteChangeState, title: String, on: Bool) -> String {
        switch state {
        case .settled: on && !title.isEmpty ? DulcetStrings.readerFavoriteAccessibility(title) : ""
        case .pending: DulcetStrings.readerFavoritePending
        case .held: DulcetStrings.readerFavoriteHeld
        }
    }
}

/// The immersive player's heart uses the same favorite session and account guard as library
/// rows. Pending, held and saved changes retain their existing labels and stable identifier.
struct DulcetPlayerNowPlayingFavouriteButton: View {
    @Environment(DulcetPresentationStore.self) private var store
    let track: DulcetTrack
    var size: Font = .body
    var minimumSide: CGFloat = 32

    static let identifier = "dulcet.now-playing.favorite"

    var body: some View {
        if let favourite = store.nowPlayingFavourite(for: track) {
            DulcetPlayerFavouriteButton(
                target: favourite.target,
                published: favourite.published,
                title: track.title,
                size: size,
                identifier: Self.identifier,
                minimumSide: minimumSide
            )
        }
    }
}
