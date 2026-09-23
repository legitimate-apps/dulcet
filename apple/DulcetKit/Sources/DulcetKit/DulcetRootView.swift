#if os(macOS) || os(iOS) || os(tvOS)
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
                        DulcetDestinationStack(store: store)
                    }
                    .navigationSplitViewStyle(.balanced)
                    .dulcetForeground(.primaryTextOnWindow)
                    // The Mac's now-playing bar docks along the whole window's bottom edge, under
                    // both columns, as a music player's transport does.
                    .modifier(DulcetNowPlayingBarPlacement(
                        store: store,
                        placement: .docked,
                        isSuppressed: store.selectedDestination == .nowPlaying,
                        onOpen: { store.selectDestination(.nowPlaying) }
                    ))
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
                DulcetIOSShell(store: store)
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

#if os(iOS)
/// The iPhone and iPad shell.
///
/// A compact width -- every iPhone in portrait, and an iPad in a narrow multitasking window --
/// gets the platform's bottom tab bar with the now-playing bar above it and the full player as a
/// sheet. A regular width keeps the sidebar, with the now-playing bar floating over the detail.
/// The size class decides, not the device, so an iPad dragged into Slide Over behaves like a
/// phone rather than squeezing a sidebar into it.
private struct DulcetIOSShell: View {
    @Bindable var store: DulcetPresentationStore
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        if horizontalSizeClass == .compact {
            DulcetCompactShell(store: store)
        } else {
            ZStack {
                Color.dulcetWindow.ignoresSafeArea()
                NavigationSplitView {
                    DulcetSidebar(store: store)
                } detail: {
                    DulcetDestinationStack(
                        store: store,
                        barPlacement: .floating,
                        onOpenPlayer: { store.selectDestination(.nowPlaying) }
                    )
                }
                .navigationSplitViewStyle(.balanced)
                .dulcetForeground(.primaryTextOnWindow)
            }
        }
    }
}

/// Tabs for the destinations that exist (Library, Search, Connection), the now-playing bar above
/// the tab bar, and Now Playing as a sheet dragged down to dismiss -- never a tab, because it is
/// not a place, it is what is playing.
///
/// The bar is a material card in each tab rather than the tab bar's own bottom accessory. The
/// accessory was tried on iOS 26.5: its glass adapts to the content scrolling beneath it, and
/// OBSERVED in the simulator it rendered the track title white on a light backdrop, illegible,
/// with the system's default foreground as well as with ours. A regular-material card has a
/// fixed contrast relationship and behaves the same on every supported iOS version.
private struct DulcetCompactShell: View {
    @Bindable var store: DulcetPresentationStore
    @State private var playerPresented = false
    /// The last tab the person was on, so a Now Playing destination arriving from elsewhere
    /// (a size-class change from an iPad sidebar) is shown as the sheet over that tab.
    @State private var lastTab: DulcetSidebarDestination = .library

    static let tabs: [DulcetSidebarDestination] = [.library, .search, .settings]

    var body: some View {
        TabView(selection: tabSelection) {
            ForEach(Self.tabs) { destination in
                DulcetDestinationStack(
                    store: store,
                    tab: destination,
                    barPlacement: .floating,
                    onOpenPlayer: { playerPresented = true }
                )
                    .tabItem {
                        Label(destination.windowTitle, systemImage: destination.symbolName)
                            .accessibilityIdentifier("dulcet.tab.\(destination.rawValue)")
                    }
                    .tag(destination)
            }
        }
        .sheet(isPresented: $playerPresented) {
            DulcetNowPlayingSheet(store: store, onClose: { playerPresented = false })
        }
        .onAppear(perform: absorbNowPlayingDestination)
        .onChange(of: store.selectedDestination) { _, _ in absorbNowPlayingDestination() }
    }

    private var tabSelection: Binding<DulcetSidebarDestination> {
        Binding(
            get: {
                let destination = store.selectedDestination
                return Self.tabs.contains(destination) ? destination : lastTab
            },
            set: { destination in
                lastTab = destination
                // Choosing the tab already showing returns it to its root, as tab bars do: for
                // Library that is the grid, out of whichever album or artist was open.
                store.selectDestination(destination)
            }
        )
    }

    private func absorbNowPlayingDestination() {
        let destination = store.selectedDestination
        if Self.tabs.contains(destination) {
            lastTab = destination
        } else if destination == .nowPlaying {
            store.selectDestination(lastTab)
            playerPresented = true
        }
    }
}

#endif

#if !os(tvOS)
/// Where a library page can be pushed. Derived from the snapshot, never stored beside it: the
/// store's state names the page, and this only lets the navigation stack show it as a push, with
/// a back button and the edge swipe.
enum DulcetLibraryRoute: Hashable {
    case album(DulcetProviderItemID)
    case artist(DulcetProviderItemID)

    static func path(for snapshot: DulcetSnapshot) -> [DulcetLibraryRoute] {
        guard snapshot.selectedDestination == .library else { return [] }
        switch snapshot.state {
        case .albumDetailMultiDisc:
            return snapshot.selectedAlbum.map { [.album($0.id)] } ?? []
        case .artistDetail:
            return snapshot.selectedArtist.map { [.artist($0.id)] } ?? []
        default:
            return []
        }
    }
}

/// One navigation stack showing a destination, with library pages pushed onto it.
///
/// In a tab, `tab` names the destination this stack belongs to, and the stack draws nothing while
/// another destination is selected -- the snapshot describes one destination at a time.
struct DulcetDestinationStack: View {
    @Bindable var store: DulcetPresentationStore
    var tab: DulcetSidebarDestination?
    /// Where this stack shows the now-playing bar, if it shows one. It is applied to the root and
    /// to every pushed page, inside the stack: OBSERVED on an iPad, a stack that is a split
    /// view's detail is adopted into the column's own navigation, and a bar applied around the
    /// stack drew on the root but vanished once an album was pushed.
    var barPlacement: DulcetNowPlayingBarPlacement.Placement?
    var onOpenPlayer: () -> Void = {}

    var body: some View {
        let showing = tab.map { $0 == store.selectedDestination } ?? true
        NavigationStack(path: libraryPath) {
            Group {
                if showing {
                    DulcetStateSurface(store: store, libraryAsStackRoot: true)
                } else {
                    Color.dulcetWindow.ignoresSafeArea()
                }
            }
            .modifier(bar)
            .navigationDestination(for: DulcetLibraryRoute.self) { route in
                DulcetLibraryRouteView(store: store, route: route)
                    .modifier(bar)
            }
        }
        // A different destination is a different stack: switching from an open album to Search
        // must not animate as a pop.
        .id(tab ?? store.selectedDestination)
    }

    private var bar: DulcetOptionalNowPlayingBar {
        DulcetOptionalNowPlayingBar(
            store: store,
            placement: barPlacement,
            isSuppressed: store.selectedDestination == .nowPlaying,
            onOpen: onOpenPlayer
        )
    }

    private var libraryPath: Binding<[DulcetLibraryRoute]> {
        Binding(
            get: { DulcetLibraryRoute.path(for: store.snapshot) },
            set: { path in
                // The back button and the edge swipe pop to the grid. Nothing pushes through
                // this setter: pages are pushed by the store selecting them.
                if path.isEmpty, !DulcetLibraryRoute.path(for: store.snapshot).isEmpty {
                    store.selectDestination(.library)
                }
            }
        )
    }
}

private struct DulcetOptionalNowPlayingBar: ViewModifier {
    @Bindable var store: DulcetPresentationStore
    let placement: DulcetNowPlayingBarPlacement.Placement?
    let isSuppressed: Bool
    let onOpen: () -> Void

    func body(content: Content) -> some View {
#if os(tvOS)
        content
#else
        if let placement {
            content.modifier(DulcetNowPlayingBarPlacement(
                store: store,
                placement: placement,
                isSuppressed: isSuppressed,
                onOpen: onOpen
            ))
        } else {
            content
        }
#endif
    }
}

/// A pushed library page. It keeps the last value it showed, so the page stays drawn while it
/// animates away after the store has already moved back to the grid.
private struct DulcetLibraryRouteView: View {
    @Bindable var store: DulcetPresentationStore
    let route: DulcetLibraryRoute
    @State private var retainedAlbum: DulcetAlbum?
    @State private var retainedArtist: DulcetArtist?

    var body: some View {
        Group {
            switch route {
            case let .album(id):
                if let album = currentAlbum(id) ?? retainedAlbum {
                    DulcetAlbumDetailView(
                        album: album,
                        tracksFailure: currentAlbum(id) == nil
                            ? nil
                            : store.snapshot.selectedAlbumTracksFailure,
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
                }
            case let .artist(id):
                if let artist = currentArtist(id) ?? retainedArtist {
                    DulcetArtistDetailView(
                        artist: artist,
                        albums: store.snapshot.albums.filter { $0.belongs(to: artist) },
                        onSelectAlbum: { album in store.selectAlbum(album.id) }
                    )
                }
            }
        }
        .onAppear(perform: retain)
        .onChange(of: store.snapshot) { _, _ in retain() }
    }

    private func currentAlbum(_ id: DulcetProviderItemID) -> DulcetAlbum? {
        store.snapshot.state == .albumDetailMultiDisc && store.snapshot.selectedAlbum?.id == id
            ? store.snapshot.selectedAlbum
            : nil
    }

    private func currentArtist(_ id: DulcetProviderItemID) -> DulcetArtist? {
        store.snapshot.state == .artistDetail && store.snapshot.selectedArtist?.id == id
            ? store.snapshot.selectedArtist
            : nil
    }

    private func retain() {
        switch route {
        case let .album(id):
            if let album = currentAlbum(id) { retainedAlbum = album }
        case let .artist(id):
            if let artist = currentArtist(id) { retainedArtist = artist }
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
                    sidebarRow(DulcetStrings.library, symbol: DulcetSidebarDestination.library.symbolName, destination: .library)
                    sidebarRow(DulcetStrings.search, symbol: DulcetSidebarDestination.search.symbolName, destination: .search)
                    sidebarRow(DulcetStrings.nowPlaying, symbol: DulcetSidebarDestination.nowPlaying.symbolName, destination: .nowPlaying)
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
                guard let destination else { return }
                store.selectDestination(destination)
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
    /// Inside a navigation stack, album and artist pages are pushed on top of the grid rather
    /// than replacing it, so the grid stays the stack's root in those states.
    var libraryAsStackRoot = false

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
            libraryBrowse
        case .albumDetailMultiDisc where libraryAsStackRoot,
             .artistDetail where libraryAsStackRoot:
            libraryBrowse
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

private extension DulcetStateSurface {
    var libraryBrowse: some View {
        DulcetLibraryBrowseView(
            snapshot: snapshot,
            onSelectAlbum: { album in store.selectAlbum(album.id) },
            onSelectArtist: { artist in store.showArtist(artist.id) },
            onPlayAll: { store.playLibrary(shuffle: false) },
            onShuffle: { store.playLibrary(shuffle: true) }
        )
        .navigationTitle(DulcetSidebarDestination.library.windowTitle)
    }
}

extension DulcetSidebarDestination {
    var symbolName: String {
        switch self {
        case .library: "rectangle.grid.2x2"
        case .search: "magnifyingglass"
        case .nowPlaying: "waveform"
        case .settings: "server.rack"
        }
    }

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
