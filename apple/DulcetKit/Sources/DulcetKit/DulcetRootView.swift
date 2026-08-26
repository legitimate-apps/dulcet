#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

public enum DulcetRenderVariant: Sendable {
    case standard
    case deliberatelyBadControl
}

public struct DulcetRootView: View {
    @Bindable private var store: DulcetPresentationStore
    private let variant: DulcetRenderVariant
#if os(iOS)
    @State private var preferredCompactColumn: NavigationSplitViewColumn = .detail
#endif

    public init(
        store: DulcetPresentationStore,
        variant: DulcetRenderVariant = .standard
    ) {
        self.store = store
        self.variant = variant
    }

    public var body: some View {
#if os(macOS)
        Group {
            if variant == .deliberatelyBadControl {
                DulcetDeliberatelyBadControlView(snapshot: store.snapshot)
            } else {
                ZStack {
                    Color.dulcetWindow.ignoresSafeArea()
                    NavigationSplitView {
                        DulcetSidebar(store: store)
                    } detail: {
                        DulcetStateSurface(store: store)
                    }
                    .navigationSplitViewStyle(.balanced)
                    .dulcetForeground(.primaryTextOnWindow)
                }
            }
        }
        .environment(store)
        .frame(minWidth: 900, minHeight: 600)
        .tint(.dulcetAccent)
#elseif os(iOS)
        Group {
            if variant == .deliberatelyBadControl {
                DulcetDeliberatelyBadControlView(snapshot: store.snapshot)
            } else {
                ZStack {
                    Color.dulcetWindow.ignoresSafeArea()
                    NavigationSplitView(preferredCompactColumn: $preferredCompactColumn) {
                        DulcetSidebar(store: store)
                    } detail: {
                        DulcetStateSurface(store: store)
                    }
                    .navigationSplitViewStyle(.balanced)
                    .dulcetForeground(.primaryTextOnWindow)
                }
            }
        }
        .environment(store)
        .tint(.dulcetAccent)
#elseif os(tvOS)
        Group {
            if variant == .deliberatelyBadControl {
                DulcetDeliberatelyBadControlView(snapshot: store.snapshot)
            } else {
                ZStack {
                    Color.dulcetWindow.ignoresSafeArea()
                    NavigationStack {
                        DulcetStateSurface(store: store)
                    }
                    .dulcetForeground(.primaryTextOnWindow)
                }
            }
        }
        .tint(.dulcetAccent)
#endif
    }
}

#if os(macOS)
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
        Group {
            if variant == .deliberatelyBadControl {
                DulcetDeliberatelyBadControlView(snapshot: store.snapshot)
            } else {
                GeometryReader { geometry in
                    HStack(spacing: 0) {
                        DulcetSidebar(store: store)
                            .frame(width: 232)
                            .fixedSize(horizontal: true, vertical: false)
                            .zIndex(1)
                        Divider()
                        DulcetStateSurface(store: store)
                            .id(store.snapshot.state.rawValue)
                            .frame(
                                width: max(0, geometry.size.width - 233),
                                height: geometry.size.height
                            )
                            .clipped()
                            .zIndex(0)
                    }
                    .frame(
                        width: geometry.size.width,
                        height: geometry.size.height,
                        alignment: .leading
                    )
                }
                .background(Color.dulcetWindow)
                .dulcetForeground(.primaryTextOnWindow)
            }
        }
        .environment(store)
    }
}
#endif

#if !os(tvOS)
private struct DulcetSidebar: View {
    @Bindable var store: DulcetPresentationStore

    var body: some View {
        VStack(spacing: 0) {
            List(selection: selection) {
                Section {
                    sidebarRow(DulcetStrings.library, symbol: "rectangle.grid.2x2", destination: .library)
                    sidebarRow(DulcetStrings.search, symbol: "magnifyingglass", destination: .search)
                    sidebarRow(DulcetStrings.nowPlaying, symbol: "waveform", destination: .nowPlaying)
                } header: {
                    Text(DulcetStrings.browseSection)
                        .textCase(.uppercase)
                }

                Section {
                    sidebarRow(DulcetStrings.settings, symbol: "server.rack", destination: .settings)
                } header: {
                    Text(DulcetStrings.accountSection)
                        .textCase(.uppercase)
                }
            }
            .listStyle(.sidebar)
            .scrollContentBackground(.hidden)

            Divider()
            serverStatus
                .padding(DulcetSpacing.sm)
        }
        .background(.thinMaterial)
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
        Label(title, systemImage: symbol)
            .tag(destination)
            .accessibilityLabel(title)
    }

    private var selection: Binding<DulcetSidebarDestination?> {
        Binding(
            get: { store.selectedDestination },
            set: { destination in
                if let destination {
                    store.selectDestination(destination)
                }
            }
        )
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
                        .dulcetForeground(.secondaryTextOnThinMaterial)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(DulcetStrings.serverStatus(serverName))
        case let .connectionFailed(failure):
            HStack(spacing: DulcetSpacing.xs) {
                DulcetStatusDot(color: .dulcetDanger)
                VStack(alignment: .leading, spacing: 2) {
                    Text(failure.serverName)
                        .font(.subheadline.weight(.medium))
                    Text(DulcetStrings.connectionFailed)
                        .font(.caption)
                        .dulcetForeground(.primaryTextOnThinMaterial)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(DulcetStrings.serverConnectionFailed(failure.serverName))
        case let .offline(lastSyncedDescription):
            HStack(spacing: DulcetSpacing.xs) {
                DulcetStatusDot(color: .dulcetOffline)
                VStack(alignment: .leading, spacing: 2) {
                    Text(DulcetStrings.offline)
                        .font(.subheadline.weight(.medium))
                    Text(DulcetStrings.lastSynced(lastSyncedDescription))
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnThinMaterial)
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
                    .dulcetForeground(.secondaryTextOnThinMaterial)
                    .lineLimit(nil)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }
}
#endif

private struct DulcetStateSurface: View {
    @Bindable var store: DulcetPresentationStore

    private var snapshot: DulcetSnapshot { store.snapshot }

    var body: some View {
        switch snapshot.selectedDestination {
        case .settings:
            DulcetAccountConnectionView(store: store)
        case .library:
            librarySurface
        case .search:
            if snapshot.state == .searchMixedSources {
                DulcetSearchView(snapshot: snapshot, searchQuery: $store.searchQuery)
            } else {
                DulcetUnavailableDestinationView(
                    symbol: "magnifyingglass",
                    title: DulcetStrings.searchUnavailableTitle,
                    message: DulcetStrings.searchUnavailableBody
                )
            }
        case .nowPlaying:
            if snapshot.state == .nowPlaying, let player = snapshot.nowPlaying {
                DulcetNowPlayingView(player: player)
            } else {
                DulcetUnavailableDestinationView(
                    symbol: "waveform",
                    title: DulcetStrings.nowPlayingUnavailableTitle,
                    message: DulcetStrings.nowPlayingUnavailableBody
                )
            }
        }
    }

    @ViewBuilder
    private var librarySurface: some View {
        switch snapshot.state {
        case .emptyLibraryNoAccount:
            DulcetEmptyLibraryView(onConnect: { store.selectDestination(.settings) })
        case .emptyLibraryConnected:
            DulcetEmptyLibraryView(connected: true)
        case .libraryLoading:
            DulcetLibraryLoadingView()
        case .libraryError:
            DulcetLibraryErrorView(failure: snapshot.libraryFailure) {
                store.selectDestination(.library)
            }
        case .libraryBrowse:
            DulcetLibraryBrowseView(snapshot: snapshot) { album in
                store.selectAlbum(album.id)
            }
        case .albumDetailMultiDisc:
            if let album = snapshot.selectedAlbum {
                DulcetAlbumDetailView(album: album)
            } else {
                DulcetEmptyLibraryView(connected: true)
            }
        case .offlineMetadataOnly:
            DulcetOfflineLibraryView(snapshot: snapshot)
        default:
            DulcetEmptyLibraryView(connected: snapshot.accountConnected)
        }
    }
}
#endif
