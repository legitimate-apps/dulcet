#if os(iOS)
import SwiftUI

/// The best match for what was typed, drawn above the grouped results as Music draws its Top
/// Result: large artwork, the title and what it is, and a Play button. The core chooses the row
/// (`DulcetReaderSearchPublication.topResult`); the groups below keep every row, this one included.
struct DulcetSearchTopResultCard: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let row: DulcetReaderSearchRow
    let accessibilityLabel: String
    let onOpen: () -> Void
    let onPlay: () -> Void

    private var result: DulcetSearchResult { row.result }
    private var unavailableOffline: Bool { row.isUnavailableOffline }
    /// An artist opens its page; a song or an album plays.
    private var hasPlay: Bool { result.kind != .artist && !unavailableOffline }

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
            Text(DulcetStrings.searchTopResult)
                .font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("dulcet.search.topResult.header")
            HStack(spacing: DulcetSpacing.sm) {
                Button(action: onOpen) {
                    HStack(spacing: DulcetSpacing.md) {
                        DulcetArtworkView(
                            artwork: result.artwork, size: DulcetMetrics.topResultArtworkSize,
                            muted: unavailableOffline, role: result.kind == .artist ? .artist : .cover
                        )
                        .clipShape(result.kind == .artist
                            ? AnyShape(Circle())
                            : AnyShape(RoundedRectangle(
                                cornerRadius: DulcetMetrics.artworkCornerRadius(forSize: DulcetMetrics.topResultArtworkSize),
                                style: .continuous)))
                        VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                            Text(result.displayTitle)
                                .font(.headline)
                                .dulcetForeground(.primaryTextOnWindow)
                                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                            Text(detail)
                                .font(.subheadline)
                                .dulcetForeground(.secondaryTextOnWindow)
                                .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .contentShape(Rectangle())
                }
                .dulcetRowButtonStyle()
                .accessibilityLabel(accessibilityLabel)
                .accessibilityIdentifier("dulcet.search.topResult")
                if hasPlay {
                    Button(action: onPlay) {
                        Image(systemName: "play.fill")
                            .font(.title2.weight(.semibold))
                            .frame(width: DulcetMetrics.heroPlayGlyphFrame, height: DulcetMetrics.heroPlayGlyphFrame)
                    }
                    .modifier(DulcetTopResultPlayStyle())
                    .accessibilityLabel(DulcetStrings.play)
                    .accessibilityIdentifier("dulcet.search.topResult.play")
                }
            }
            .padding(DulcetSpacing.sm)
            .background(Color.dulcetControl, in: RoundedRectangle(cornerRadius: DulcetMetrics.artworkCornerRadius, style: .continuous))
        }
        .padding(.bottom, DulcetSpacing.md)
    }

    /// What it is, then whose: "Song · Pavement", "Album · Soccer Mommy", "Artist".
    private var detail: String {
        [result.kind.displayTitle, result.subtitle].filter { !$0.isEmpty }.joined(separator: " \u{00B7} ")
    }
}

private struct DulcetTopResultPlayStyle: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.buttonStyle(.glassProminent).buttonBorderShape(.circle)
        } else {
            content.buttonStyle(.borderedProminent).buttonBorderShape(.circle)
        }
    }
}
#endif
