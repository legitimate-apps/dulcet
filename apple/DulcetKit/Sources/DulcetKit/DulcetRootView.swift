#if os(macOS)
import SwiftUI

public enum DulcetRenderVariant: Sendable {
    case standard
    case deliberatelyBadControl
}

public struct DulcetRootView: View {
    @Bindable private var store: DulcetPresentationStore
    private let variant: DulcetRenderVariant

    public init(
        store: DulcetPresentationStore,
        variant: DulcetRenderVariant = .standard
    ) {
        self.store = store
        self.variant = variant
    }

    public var body: some View {
        Group {
            if variant == .deliberatelyBadControl {
                DulcetDeliberatelyBadControlView(snapshot: store.snapshot)
            } else {
                ZStack {
                    Color.dulcetWindow.ignoresSafeArea()
                    NavigationSplitView {
                        DulcetSidebar(store: store)
                    } detail: {
                        DulcetStateSurface(snapshot: store.snapshot, searchQuery: $store.searchQuery)
                    }
                    .navigationSplitViewStyle(.balanced)
                }
            }
        }
        .frame(minWidth: 900, minHeight: 600)
        .tint(.dulcetAccent)
        .onChange(of: store.selectedDestination) { _, destination in
            switch destination {
            case .library:
                store.show(.libraryBrowse)
            case .search:
                store.show(.searchMixedSources)
            case .nowPlaying:
                store.show(.nowPlaying)
            case .settings:
                store.show(.tlsUntrusted)
            }
        }
    }
}

/// Fixed-composition sibling of ``DulcetRootView`` for offscreen evidence rendering.
///
/// `NavigationSplitView` places its sidebar in a separate AppKit compositor subtree, which
/// `NSHostingView.cacheDisplay` does not include. This view keeps the exact sidebar and state
/// surfaces while making their fixed capture boundary explicit.
public struct DulcetCaptureView: View {
    @Bindable private var store: DulcetPresentationStore
    private let variant: DulcetRenderVariant

    public init(
        store: DulcetPresentationStore,
        variant: DulcetRenderVariant = .standard
    ) {
        self.store = store
        self.variant = variant
    }

    public var body: some View {
        if variant == .deliberatelyBadControl {
            DulcetDeliberatelyBadControlView(snapshot: store.snapshot)
        } else {
            HStack(spacing: 0) {
                DulcetSidebar(store: store)
                    .frame(width: 232)
                    .fixedSize(horizontal: true, vertical: false)
                    .layoutPriority(10)
                    .zIndex(1)
                Divider()
                DulcetStateSurface(snapshot: store.snapshot, searchQuery: $store.searchQuery)
                    .id(store.snapshot.state.rawValue)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
                    .zIndex(0)
            }
            .background(Color.dulcetWindow)
        }
    }
}

private struct DulcetSidebar: View {
    @Bindable var store: DulcetPresentationStore

    var body: some View {
        VStack(alignment: .leading, spacing: DulcetSpacing.md) {
            Text(DulcetStrings.appName)
                .font(.title2.weight(.bold))
                .padding(.horizontal, DulcetSpacing.xs)

            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                sidebarSectionTitle(DulcetStrings.browseSection)
                sidebarRow(DulcetStrings.library, symbol: "rectangle.grid.2x2", destination: .library)
                sidebarRow(DulcetStrings.search, symbol: "magnifyingglass", destination: .search)
                sidebarRow(DulcetStrings.nowPlaying, symbol: "waveform", destination: .nowPlaying)
            }

            VStack(alignment: .leading, spacing: DulcetSpacing.xxs) {
                sidebarSectionTitle(DulcetStrings.accountSection)
                sidebarRow(DulcetStrings.settings, symbol: "server.rack", destination: .settings)
            }

            Spacer(minLength: DulcetSpacing.md)
            Divider()
            serverStatus
                .padding(.horizontal, DulcetSpacing.xs)
        }
        .padding(DulcetSpacing.sm)
        .background(Color.dulcetControl.opacity(0.78))
        .navigationSplitViewColumnWidth(
            min: DulcetMetrics.sidebarMinWidth,
            ideal: 232,
            max: 300
        )
    }

    private func sidebarRow(
        _ title: String,
        symbol: String,
        destination: DulcetSidebarDestination
    ) -> some View {
        Button {
            store.selectedDestination = destination
        } label: {
            Label(title, systemImage: symbol)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, DulcetSpacing.xs)
                .padding(.vertical, 6)
                .background {
                    if store.selectedDestination == destination {
                        RoundedRectangle(cornerRadius: 7, style: .continuous)
                            .fill(Color.dulcetAccent.opacity(0.16))
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(store.selectedDestination == destination ? Color.dulcetAccent : Color.primary)
            .accessibilityLabel(title)
            .accessibilityAddTraits(store.selectedDestination == destination ? [.isSelected] : [])
    }

    private func sidebarSectionTitle(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, DulcetSpacing.xs)
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private var serverStatus: some View {
        switch store.snapshot.connectivity {
        case let .online(serverName):
            HStack(spacing: DulcetSpacing.xs) {
                DulcetStatusDot(color: .green)
                VStack(alignment: .leading, spacing: 2) {
                    Text(serverName)
                        .font(.subheadline.weight(.medium))
                    Text(DulcetStrings.online)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(DulcetStrings.serverStatus(serverName))
        case let .offline(lastSyncedDescription):
            HStack(spacing: DulcetSpacing.xs) {
                DulcetStatusDot(color: .dulcetOffline)
                VStack(alignment: .leading, spacing: 2) {
                    Text(DulcetStrings.offline)
                        .font(.subheadline.weight(.medium))
                    Text("\(DulcetStrings.lastSynced) \(lastSyncedDescription)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(nil)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        case .unavailable:
            HStack(spacing: DulcetSpacing.xs) {
                DulcetStatusDot(color: .secondary)
                Text(DulcetStrings.noServer)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(nil)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}

private struct DulcetStateSurface: View {
    let snapshot: DulcetSnapshot
    @Binding var searchQuery: String

    var body: some View {
        switch snapshot.state {
        case .emptyLibraryNoAccount:
            DulcetEmptyLibraryView()
        case .libraryBrowse:
            DulcetLibraryBrowseView(snapshot: snapshot)
        case .albumDetailMultiDisc:
            if let album = snapshot.selectedAlbum {
                DulcetAlbumDetailView(album: album)
            }
        case .nowPlaying:
            if let player = snapshot.nowPlaying {
                DulcetNowPlayingView(player: player)
            }
        case .searchMixedSources:
            DulcetSearchView(snapshot: snapshot, searchQuery: $searchQuery)
        case .tlsUntrusted:
            if let failure = snapshot.tlsFailure {
                DulcetTLSUntrustedView(failure: failure)
            }
        case .offlineMetadataOnly:
            DulcetOfflineLibraryView(snapshot: snapshot)
        }
    }
}
#endif
