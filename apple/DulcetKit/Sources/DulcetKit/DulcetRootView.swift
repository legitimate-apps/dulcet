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
                    DulcetTVSectionNavigation(store: store)
                        .dulcetForeground(.primaryTextOnWindow)
                }
            }
        }
        .environment(store)
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
                        DulcetStateSurface(
                            store: store,
                            allowsProgrammaticFocus: false
                        )
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

#if os(tvOS)
/// Top-level section navigation for the remote.
///
/// tvOS puts top-level sections in a focusable bar across the top of the screen, above the
/// content, which the remote reaches by moving focus up out of a surface or by pressing the
/// platform's exit button. Following that convention -- rather than porting the sidebar column
/// -- is what makes every section reachable from every other one, including the return to
/// Library after playback has moved the app to Now Playing on its own.
///
/// The store owns which section is showing, and this bar only ever asks it to change: each
/// control calls the same reducer path the sidebar uses, and that reducer does real
/// per-destination work -- cancelling a library browse, cancelling an in-flight search request,
/// re-deriving the playback presentation.
///
/// A `TabView` was the obvious way to express this and is not used, because on tvOS its
/// selection does not durably accept a change the app makes for itself. Measured during
/// development on a tvOS 26.5 simulator: activating a search result moved the app to Now Playing,
/// which rendered for about a second and was then replaced by the section the person had been on,
/// because the tab view restored its own selection. Two binding formulations behaved identically,
/// so it is not a missing observation dependency.
///
/// That measurement is deliberately NOT marked OBSERVED: the TabView implementation it was taken
/// from was a scratch experiment and was never committed, so nothing in this repository lets a
/// reader reproduce it. Treat it as the recorded reason for the design, not as a verified claim
/// about SwiftUI -- and re-measure before relying on it for a different surface.
///
/// Owning the selection here keeps the one destination the reducer publishes as the only one, so
/// nothing can write a stale section back over it.
private struct DulcetTVSectionNavigation: View {
    @Bindable var store: DulcetPresentationStore
    @FocusState private var focusedSection: DulcetSidebarDestination?

    var body: some View {
        let selected = store.selectedDestination
        VStack(spacing: 0) {
            sectionBar(selected: selected)
            NavigationStack {
                DulcetStateSurface(store: store)
            }
        }
        // The exit button is how a person leaves a surface on this platform, so it returns focus
        // to the bar -- deterministically, rather than relying on the focus engine to find a
        // control several scroll views away. From the bar itself it stays unhandled, because
        // there the platform's own meaning is to leave the app, and consuming it would strand
        // the person inside.
        .dulcetOnExitCommand(perform: focusedSection == nil ? { focusedSection = selected } : nil)
    }

    private func sectionBar(selected: DulcetSidebarDestination) -> some View {
        HStack(spacing: DulcetSpacing.xs) {
            ForEach(DulcetSidebarDestination.allCases) { destination in
                Button {
                    store.selectDestination(destination)
                } label: {
                    Label(destination.windowTitle, systemImage: Self.symbolName(for: destination))
                        .font(.callout.weight(destination == selected ? .semibold : .regular))
                        .padding(.horizontal, DulcetSpacing.sm)
                        .padding(.vertical, DulcetSpacing.xxs)
                        .background(
                            destination == selected ? Color.dulcetControl : Color.clear,
                            in: Capsule()
                        )
                }
                .buttonStyle(.borderless)
                .focused($focusedSection, equals: destination)
                .accessibilityIdentifier("dulcet.tab.\(destination.rawValue)")
                .accessibilityLabel(destination.windowTitle)
                .accessibilityAddTraits(destination == selected ? .isSelected : [])
            }
        }
        .padding(.top, DulcetSpacing.sm)
        .padding(.bottom, DulcetSpacing.xs)
        .frame(maxWidth: .infinity)
        .background(Color.dulcetWindow)
        // Its own focus region, so moving up out of a surface reaches the bar as a whole rather
        // than searching for a control that happens to line up with what focus was on.
        .focusSection()
    }

    private static func symbolName(for destination: DulcetSidebarDestination) -> String {
        switch destination {
        case .library: "rectangle.grid.2x2"
        case .search: "magnifyingglass"
        case .nowPlaying: "waveform"
        case .settings: "server.rack"
        }
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
            .accessibilityIdentifier("dulcet.sidebar.\(destination.rawValue)")
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
        case let .disconnected(serverName):
            HStack(spacing: DulcetSpacing.xs) {
                DulcetStatusDot(color: .dulcetOffline)
                VStack(alignment: .leading, spacing: 2) {
                    Text(serverName)
                        .font(.subheadline.weight(.medium))
                    Text(DulcetStrings.disconnected)
                        .font(.caption)
                        .dulcetForeground(.secondaryTextOnThinMaterial)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(DulcetStrings.serverDisconnected(serverName))
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
    var allowsProgrammaticFocus = true

    private var snapshot: DulcetSnapshot { store.snapshot }

    var body: some View {
        switch snapshot.selectedDestination {
        case .settings:
            DulcetAccountConnectionView(
                store: store,
                allowsProgrammaticFocus: allowsProgrammaticFocus
            )
        case .library:
            librarySurface
        case .search:
            DulcetSearchView(
                snapshot: snapshot,
                searchQuery: $store.searchQuery,
                onLoadMore: store.loadMoreSearchResults,
                onRetry: store.retrySearch,
                onActivateResult: store.activateSearchResult
            )
        case .nowPlaying:
            if snapshot.state == .nowPlaying, let player = snapshot.nowPlaying {
                DulcetNowPlayingView(player: player) { intent in
                    store.sendPlaybackControl(intent)
                }
            } else if snapshot.state == .nowPlayingPreparing {
                DulcetPlaybackPreparingView()
            } else if snapshot.state == .nowPlayingFailed {
                DulcetUnavailableDestinationView(
                    symbol: "exclamationmark.triangle",
                    title: DulcetStrings.nowPlayingFailedTitle,
                    message: DulcetStrings.nowPlayingFailedBody
                )
                .navigationTitle(DulcetSidebarDestination.nowPlaying.windowTitle)
            } else {
                DulcetUnavailableDestinationView(
                    symbol: "waveform",
                    title: DulcetStrings.nowPlayingUnavailableTitle,
                    message: DulcetStrings.nowPlayingUnavailableBody
                )
                .navigationTitle(DulcetSidebarDestination.nowPlaying.windowTitle)
            }
        }
    }

    @ViewBuilder
    private var librarySurface: some View {
        switch snapshot.state {
        case .accountSavedDisconnected:
            if case let .saved(serverName) = snapshot.accountConnection {
                DulcetSavedAccountLibraryView(serverName: serverName) {
                    store.submitAccountConnection()
                }
                .navigationTitle(DulcetSidebarDestination.library.windowTitle)
            } else {
                DulcetEmptyLibraryView(onConnect: { store.selectDestination(.settings) })
                    .navigationTitle(DulcetSidebarDestination.library.windowTitle)
            }
        case .emptyLibraryNoAccount:
            DulcetEmptyLibraryView(onConnect: { store.selectDestination(.settings) })
                .navigationTitle(DulcetSidebarDestination.library.windowTitle)
        case .emptyLibraryConnected:
            DulcetEmptyLibraryView(connected: true)
                .navigationTitle(DulcetSidebarDestination.library.windowTitle)
        case .libraryLoading:
            DulcetLibraryLoadingView()
                .navigationTitle(DulcetSidebarDestination.library.windowTitle)
        case .libraryError:
            DulcetLibraryErrorView(failure: snapshot.libraryFailure) {
                store.selectDestination(.library)
            }
            .navigationTitle(DulcetSidebarDestination.library.windowTitle)
        case .libraryBrowse:
            DulcetLibraryBrowseView(
                snapshot: snapshot,
                onSelectAlbum: { album in store.selectAlbum(album.id) },
                onPlayAll: { store.playLibrary(shuffle: false) },
                onShuffle: { store.playLibrary(shuffle: true) }
            )
            .navigationTitle(DulcetSidebarDestination.library.windowTitle)
        case .albumDetailMultiDisc:
            if let album = snapshot.selectedAlbum {
                DulcetAlbumDetailView(
                    album: album,
                    tracksFailure: snapshot.selectedAlbumTracksFailure,
                    onPlay: { store.playAlbum(album.id, shuffle: false) },
                    onShuffle: { store.playAlbum(album.id, shuffle: true) },
                    onActivateTrack: { track in
                        store.activateTrack(albumID: album.id, trackID: track.id)
                    },
                    onDownloadTrack: store.downloadsEnabled
                        ? { track in store.downloadTrack(track.id) }
                        : nil,
                    onRetryTracks: { store.retryAlbumTracks() }
                )
            } else {
                DulcetEmptyLibraryView(connected: true)
                    .navigationTitle(DulcetSidebarDestination.library.windowTitle)
            }
        case .artistDetail:
            if let artist = snapshot.selectedArtist {
                DulcetArtistDetailView(
                    artist: artist,
                    albums: snapshot.albums.filter { $0.belongs(to: artist) },
                    onSelectAlbum: { album in store.selectAlbum(album.id) }
                )
            } else {
                DulcetEmptyLibraryView(connected: true)
                    .navigationTitle(DulcetSidebarDestination.library.windowTitle)
            }
        case .offlineMetadataOnly:
            DulcetOfflineLibraryView(
                snapshot: snapshot,
                onActivateTrack: { track in
                    guard let album = snapshot.albums.first(where: {
                        $0.tracks.contains(where: { $0.id == track.id })
                    }) else { return }
                    store.activateTrack(albumID: album.id, trackID: track.id)
                }
            )
                .navigationTitle(DulcetSidebarDestination.library.windowTitle)
        default:
            DulcetEmptyLibraryView(connected: snapshot.accountConnected)
                .navigationTitle(DulcetSidebarDestination.library.windowTitle)
        }
    }
}

extension DulcetSidebarDestination {
    var windowTitle: String {
        switch self {
        case .library: DulcetStrings.library
        case .search: DulcetStrings.search
        case .nowPlaying: DulcetStrings.nowPlaying
        case .settings: DulcetStrings.settings
        }
    }
}
#endif
