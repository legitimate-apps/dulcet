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
                NavigationSplitView {
                    DulcetSidebar(store: store)
                } detail: {
                    DulcetStateSurface(snapshot: store.snapshot, searchQuery: $store.searchQuery)
                }
                .navigationSplitViewStyle(.balanced)
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

private struct DulcetSidebar: View {
    @Bindable var store: DulcetPresentationStore

    var body: some View {
        List(selection: $store.selectedDestination) {
            Section(DulcetStrings.browseSection) {
                sidebarRow(DulcetStrings.library, symbol: "rectangle.grid.2x2", destination: .library)
                sidebarRow(DulcetStrings.search, symbol: "magnifyingglass", destination: .search)
                sidebarRow(DulcetStrings.nowPlaying, symbol: "waveform", destination: .nowPlaying)
            }

            Section(DulcetStrings.accountSection) {
                sidebarRow(DulcetStrings.settings, symbol: "server.rack", destination: .settings)
            }
        }
        .listStyle(.sidebar)
        .navigationTitle(DulcetStrings.appName)
        .navigationSplitViewColumnWidth(
            min: DulcetMetrics.sidebarMinWidth,
            ideal: 232,
            max: 300
        )
        .safeAreaInset(edge: .bottom) {
            serverStatus
                .padding(DulcetSpacing.md)
                .background(.bar)
        }
    }

    private func sidebarRow(
        _ title: String,
        symbol: String,
        destination: DulcetSidebarDestination
    ) -> some View {
        Label(title, systemImage: symbol)
            .tag(destination)
            .accessibilityLabel(title)
            .accessibilityAddTraits(.isButton)
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
