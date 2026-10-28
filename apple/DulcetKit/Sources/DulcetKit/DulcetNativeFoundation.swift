#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

enum DulcetStackRoute: Hashable {
    case reader(DulcetReaderRoute)
    case library(DulcetLibraryRoute)
}

struct DulcetReaderNavigationLink: View {
    let title: String
    let symbol: String
    let route: DulcetReaderRoute
    let identifier: String
    var body: some View {
#if os(tvOS)
        NavigationLink(value: route) { Label(title, systemImage: symbol) }
            .accessibilityIdentifier(identifier)
#else
        NavigationLink(value: DulcetStackRoute.reader(route)) {
            Label { Text(title) } icon: {
                Image(systemName: symbol).dulcetForeground(.accentIconOnWindow)
            }
        }
        .accessibilityIdentifier(identifier)
#endif
    }
}

private struct DulcetOpenAccountKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

extension EnvironmentValues {
    var dulcetOpenAccount: @MainActor () -> Void {
        get { self[DulcetOpenAccountKey.self] }
        set { self[DulcetOpenAccountKey.self] = newValue }
    }
}

extension View {
    @ViewBuilder func dulcetReaderFreshness(_ freshness: DulcetReaderFreshness?) -> some View {
        if let freshness, let detail = DulcetReaderCopy.freshnessLine(freshness) {
            self.accessibilityCustomContent(Text(DulcetStrings.readerFreshness), Text(detail))
        } else { self }
    }
}

/// Destination status is ordinary content, so native bars retain their scroll-edge effects.
struct DulcetDestinationStatus: View {
    let title: String
    var symbol = "wifi.slash"
    var retry: (() -> Void)?
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Label(title, systemImage: symbol)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            if let retry {
                Button(DulcetStrings.tryAgain, action: retry).buttonStyle(.borderless)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("dulcet.destination.status")
    }
}

struct DulcetShelfHeader: View {
    let title: String
    var identifier: String?
    let onSeeAll: () -> Void
    var body: some View {
        Button(action: onSeeAll) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.title2.bold()).foregroundStyle(.primary)
                Spacer(minLength: 8)
                Image(systemName: "chevron.forward").font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(DulcetStrings.readerRowAccessibility(title, DulcetStrings.readerSeeAll))
        .accessibilityIdentifier(identifier ?? "dulcet.shelf.seeAll")
        .accessibilityAddTraits(.isHeader)
    }
}

struct DulcetPlaybackSettingsPage: View {
    @Bindable var store: DulcetPresentationStore
    var body: some View {
        Form {
            DulcetStreamingQualitySection(store: store, inForm: true)
            DulcetCrossfadeSection(store: store, inForm: true)
        }
#if os(macOS)
        .formStyle(.grouped)
#endif
        .navigationTitle(DulcetStrings.playbackSettings)
        .accessibilityIdentifier("dulcet.settings.playback")
    }
}

struct DulcetDownloadsStoragePage: View {
    @Bindable var store: DulcetPresentationStore
    var body: some View {
        Form { DulcetOfflineLibrarySection(store: store, inForm: true) }
#if os(macOS)
            .formStyle(.grouped)
#endif
            .navigationTitle(DulcetStrings.downloadsStorage)
            .accessibilityIdentifier("dulcet.settings.downloadsStorage")
    }
}
/// Shared collection geometry. Accessibility sizes use rows so titles retain their width.
struct DulcetArtworkGrid<Content: View>: View {
    @Environment(\.dynamicTypeSize) private var textSize
    let items: [DulcetReaderItem]
    let width: CGFloat
    @ViewBuilder let tile: (DulcetReaderItem, Int, CGFloat) -> Content
    var body: some View {
        let available = max(100, width - 2 * DulcetLibraryMetrics.horizontalInset(forWidth: width))
#if os(tvOS)
        let count = max(1, Int(available / 280))
#else
        let count = width < 600 ? 2 : max(2, Int(available / 180))
#endif
        let size = max(40, (available - CGFloat(count - 1) * DulcetSpacing.sm) / CGFloat(count))
        if textSize.isAccessibilitySize {
            LazyVStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    DulcetReaderListRow(item: item)
                }
            }
        } else {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), alignment: .topLeading), count: count),
                alignment: .leading, spacing: DulcetSpacing.lg) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    tile(item, index, size)
                }
            }
        }
    }
}

/// Library and metadata album headers use the same hierarchy and responsive arrangement.
struct DulcetAlbumHeaderLayout<Artwork: View, Identity: View, Actions: View>: View {
    let compact: Bool
    @ViewBuilder let artwork: () -> Artwork
    @ViewBuilder let identity: () -> Identity
    @ViewBuilder let actions: () -> Actions
    var body: some View {
        if compact {
            VStack(spacing: DulcetSpacing.md) {
                artwork()
                identity()
                actions().frame(maxWidth: 440)
            }
            .frame(maxWidth: .infinity)
        } else {
            HStack(alignment: .top, spacing: DulcetSpacing.lg) {
                artwork()
                VStack(alignment: .leading, spacing: DulcetSpacing.md) {
                    identity()
                    actions().frame(maxWidth: 400)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(1)
            }
        }
    }
}
#endif
