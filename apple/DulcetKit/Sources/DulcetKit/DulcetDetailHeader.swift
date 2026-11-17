#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

/// How a detail header arranges itself, decided once from the width the page was given.
enum DulcetDetailHeaderArrangement: Equatable {
    /// Artwork beside the text, the text aligned to the artwork's top. Regular widths.
    case sideBySide
    /// Artwork above the text, everything centred. Phones, narrow iPad columns and every
    /// accessibility text size, where a large title beside artwork has no room to wrap.
    case stacked

    /// One decision on the page's own width, so a narrow iPad split column is laid out like a
    /// phone and a phone in landscape like a narrow window.
    static func forWidth(_ width: CGFloat, accessibilitySize: Bool) -> Self {
        accessibilitySize || width < DulcetLibraryMetrics.compactWidthThreshold ? .stacked : .sideBySide
    }

    /// The artwork's edge for a page `width` points wide: the fixed regular size beside the text;
    /// stacked, as large as the column allows up to a cap, never edge to edge.
    func artworkSize(width: CGFloat, accessibilitySize: Bool) -> CGFloat {
        switch self {
        case .sideBySide:
            return DulcetMetrics.detailHeaderArtworkSize
        case .stacked:
            let cap = accessibilitySize
                ? DulcetMetrics.detailHeaderAccessibilityArtworkMaxSize
                : DulcetMetrics.detailHeaderStackedArtworkMaxSize
            let room = width - 2 * DulcetMetrics.detailHeaderStackedArtworkSideRoom
            return max(DulcetMetrics.detailHeaderStackedArtworkMinSize, min(cap, room))
        }
    }
}

/// The one header of a collection page: album, artist, playlist and genre, on iPhone, iPad and
/// Mac (Apple TV draws it only at accessibility sizes; otherwise `DulcetTVDetailHeader`).
///
/// Anatomy, in order: artwork, title, an optional credit (the album's artists), one metadata line,
/// an optional accessory (a playlist's sync status), then the actions.
/// - Side by side: artwork `DulcetMetrics.detailHeaderArtworkSize`, `DulcetSpacing.md`, then the
///   text column aligned to the artwork's top. The title is `.largeTitle` bold.
/// - Stacked: one centred column; the title is `.title2` bold.
///
/// The actions slot holds the paired Play and Shuffle and nothing else: pass them through
/// `DulcetEqualWidthActions`, each button with `fillsWidth: true`. Page-level secondary actions --
/// Edit, Rename, Delete, Pin, Favorite, Add to Library -- belong in the toolbar's More menu, never
/// in the header (Apple TV, whose pages hide the navigation bar, puts its More here instead).
struct DulcetDetailHeader<Artwork: View, Credit: View, Accessory: View, Actions: View>: View {
    /// Nil when the navigation bar's large title already names the page (a genre).
    let title: String?
    var titleIdentifier: String?
    var metadata: String?
    let arrangement: DulcetDetailHeaderArrangement
    let artworkSize: CGFloat
    @ViewBuilder let artwork: (CGFloat) -> Artwork
    @ViewBuilder let credit: () -> Credit
    @ViewBuilder let accessory: () -> Accessory
    @ViewBuilder let actions: () -> Actions

    init(
        title: String?,
        titleIdentifier: String? = nil,
        metadata: String? = nil,
        arrangement: DulcetDetailHeaderArrangement,
        artworkSize: CGFloat,
        @ViewBuilder artwork: @escaping (CGFloat) -> Artwork,
        @ViewBuilder credit: @escaping () -> Credit,
        @ViewBuilder accessory: @escaping () -> Accessory,
        @ViewBuilder actions: @escaping () -> Actions
    ) {
        self.title = title
        self.titleIdentifier = titleIdentifier
        self.metadata = metadata
        self.arrangement = arrangement
        self.artworkSize = artworkSize
        self.artwork = artwork
        self.credit = credit
        self.accessory = accessory
        self.actions = actions
    }

    var body: some View {
        switch arrangement {
        case .stacked:
            VStack(spacing: DulcetSpacing.md) {
                artwork(artworkSize)
                identity(alignment: .center)
                actions().frame(maxWidth: DulcetMetrics.detailHeaderActionsMaxWidth)
            }
            .frame(maxWidth: .infinity)
        case .sideBySide:
            HStack(alignment: .top, spacing: DulcetSpacing.md) {
                artwork(artworkSize)
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    identity(alignment: .leading)
                    actions().frame(maxWidth: DulcetMetrics.detailHeaderActionsMaxWidth)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(1)
            }
        }
    }

    private func identity(alignment: HorizontalAlignment) -> some View {
        let textAlignment: TextAlignment = alignment == .leading ? .leading : .center
        return VStack(alignment: alignment, spacing: DulcetSpacing.xxs) {
            if let title {
                Text(title)
                    .font(arrangement == .sideBySide ? .largeTitle.weight(.bold) : .title2.weight(.bold))
                    .multilineTextAlignment(textAlignment)
                    .lineLimit(nil)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .dulcetOptionalAccessibilityIdentifier(titleIdentifier)
                    .dulcetDetailTitleMarker()
            }
            credit()
                .multilineTextAlignment(textAlignment)
            if let metadata, !metadata.isEmpty {
                Text(metadata)
                    .font(.subheadline)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .multilineTextAlignment(textAlignment)
                    .lineLimit(nil)
            }
            accessory()
        }
        .frame(maxWidth: .infinity, alignment: Alignment(horizontal: alignment, vertical: .top))
    }
}

extension DulcetDetailHeader where Credit == EmptyView, Accessory == EmptyView {
    /// A header with no credit line and no accessory: an artist, a genre.
    init(
        title: String?,
        titleIdentifier: String? = nil,
        metadata: String? = nil,
        arrangement: DulcetDetailHeaderArrangement,
        artworkSize: CGFloat,
        @ViewBuilder artwork: @escaping (CGFloat) -> Artwork,
        @ViewBuilder actions: @escaping () -> Actions
    ) {
        self.init(
            title: title, titleIdentifier: titleIdentifier, metadata: metadata,
            arrangement: arrangement, artworkSize: artworkSize,
            artwork: artwork, credit: { EmptyView() }, accessory: { EmptyView() }, actions: actions
        )
    }
}
#endif
