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
                    .dulcetPlaybackFeedback(store: store)
                }
            }
        }
        .environment(store)
        .frame(minWidth: 900, minHeight: 600)
        .tint(.dulcetAccent)
        .dulcetUIProofMarkers()
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
        .dulcetUIProofMarkers()
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
                // Up Next on tvOS edits the queue too; a refusal there is said, as on the others.
                // There is no now-playing bar on tvOS, so the root draws the notices itself,
                // along the bottom edge, clear of the section bar at the top.
                .dulcetPlaybackFeedback(store: store, drawsNotices: true)
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
/// gets the platform's bottom tab bar. A regular width keeps the sidebar. The size class decides,
/// not the device, so an iPad window resized in Stage Manager or Split View behaves like a phone
/// rather than squeezing a sidebar into it.
///
/// Now Playing is never a place on either: the now-playing bar sits above the content and opens
/// the player over it, a sheet on a compact width and a cover over the whole window on a regular
/// one. The presentation lives here, above both shells, so a window resized across the boundary
/// keeps the player open and only changes how it is presented.
private struct DulcetIOSShell: View {
    @Bindable var store: DulcetPresentationStore
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var playerPresented = false
    /// Whether a player presentation is actually on screen, as opposed to asked for.
    @State private var playerOnScreen = false
    /// Set when a size-class change took the player down, so it comes back in the other style
    /// once the old presentation has gone.
    @State private var presentPlayerAfterDismissal = false
    /// The last place the person was, so a Now Playing destination arriving from elsewhere (the
    /// Show Now Playing command, a restored selection) opens the player over that place.
    @State private var lastPlace: DulcetSidebarDestination = .library

    static let places: [DulcetSidebarDestination] = [.library, .search, .settings]

    private var compact: Bool { horizontalSizeClass == .compact }

    var body: some View {
        Group {
            if compact {
                DulcetCompactShell(store: store, lastTab: $lastPlace, onOpenPlayer: openPlayer)
            } else {
                ZStack {
                    Color.dulcetWindow.ignoresSafeArea()
                    NavigationSplitView {
                        DulcetSidebar(store: store, destinations: Self.places)
                    } detail: {
                        DulcetDestinationStack(
                            store: store,
                            barPlacement: .floating,
                            onOpenPlayer: openPlayer
                        )
                    }
                    .navigationSplitViewStyle(.balanced)
                    .dulcetForeground(.primaryTextOnWindow)
                }
            }
        }
        .sheet(isPresented: presented(whenCompact: true), onDismiss: playerDismissed) {
            DulcetNowPlayingSheet(store: store, presentation: .sheet, onClose: closePlayer)
                .onAppear { playerOnScreen = true }
        }
        .fullScreenCover(isPresented: presented(whenCompact: false), onDismiss: playerDismissed) {
            DulcetNowPlayingSheet(store: store, presentation: .fullScreen, onClose: closePlayer)
                .onAppear { playerOnScreen = true }
        }
        // The player carries its own while it is up; this one speaks for the shell under it.
        .dulcetPlaybackFeedback(store: store, isActive: !playerPresented)
        .onChange(of: horizontalSizeClass) { _, _ in sizeClassChanged() }
        .onAppear(perform: absorbNowPlayingDestination)
        .onChange(of: store.selectedDestination) { _, _ in absorbNowPlayingDestination() }
        .background {
            DulcetKeyboardShortcuts(store: store, onShowNowPlaying: openPlayer)
        }
    }

    private func openPlayer() { playerPresented = true }
    private func closePlayer() {
        presentPlayerAfterDismissal = false
        playerPresented = false
    }

    private func presented(whenCompact: Bool) -> Binding<Bool> {
        Binding(
            get: { playerPresented && compact == whenCompact },
            set: { isPresented in
                // Only a dismissal from the style currently in use closes the player; the other
                // style reporting false while it hands over must not.
                if !isPresented, compact == whenCompact { playerPresented = false }
            }
        )
    }

    /// A window crossing the compact/regular boundary with the player open: iPad Split View or
    /// Stage Manager, or a large iPhone turned sideways. Both presentation styles flip in the same
    /// update, so the new one is asked to present while the old one is still being dismissed.
    /// The player is taken down instead, and presented again in the new style once the old
    /// presentation has gone: from its dismissal when one was on screen, on the next pass of the
    /// run loop when none was.
    ///
    /// This is a precaution, not a measured repair. A large iPhone turned sideways on iOS 26.5
    /// hands the player over correctly without it -- the rotation UI test passes with this
    /// handler removed -- and the Split View and Stage Manager crossings, which a simulator
    /// cannot drive, are unobserved. The failure it guards against (the player asked for with
    /// nothing on screen, and the bar unable to open it) is reasoned from the two presentations
    /// sharing one flag, and has not been reproduced.
    private func sizeClassChanged() {
        guard playerPresented else { return }
        playerPresented = false
        if playerOnScreen {
            presentPlayerAfterDismissal = true
        } else {
            DispatchQueue.main.async { playerPresented = true }
        }
    }

    private func playerDismissed() {
        playerOnScreen = false
        guard presentPlayerAfterDismissal else { return }
        presentPlayerAfterDismissal = false
        DispatchQueue.main.async { playerPresented = true }
    }

    private func absorbNowPlayingDestination() {
        let destination = store.selectedDestination
        if Self.places.contains(destination) {
            lastPlace = destination
        } else if destination == .nowPlaying {
            // Back to the place the person was, as they left it: an album that was open stays
            // open under the player.
            store.navigate(to: lastPlace)
            playerPresented = true
        }
    }
}

/// The hardware-keyboard shortcuts of an iPad: Space plays and pauses (away from Search and
/// Connection, whose text fields own it), Command-arrows skip,
/// Command-F puts the cursor in Search, Command-L opens the player. Holding Command lists them.
///
/// They are shortcut buttons in the shell's own view hierarchy rather than the scene's
/// `commands`. MEASURED on an iPadOS 26.5 simulator: the same buttons declared as scene commands
/// never fired from an XCUITest hardware key press -- not Space, not Command-F, not even with
/// every disabled state removed -- while these are part of the responder chain the key press
/// reaches. Each is drawn at zero size and hidden from accessibility: it exists for its shortcut.
private struct DulcetKeyboardShortcuts: View {
    @Bindable var store: DulcetPresentationStore
    let onShowNowPlaying: () -> Void
    /// Space belongs to text entry on a surface that has a text field. A key shortcut is matched
    /// alongside text input, so Space typed into Search also paused or resumed playback --
    /// OBSERVED in the iPad keyboard proof: the field read "Thirty One" and playback had resumed.
    /// Decided by the surface rather than by tracking which field is being edited, so there is no
    /// editing state that can be left stale by a field leaving the screen.
    private var spaceBelongsToText: Bool {
        store.selectedDestination == .search || store.selectedDestination == .settings
    }

    var body: some View {
        let state = DulcetPlaybackMenuState(nowPlaying: store.snapshot.nowPlaying)
        ZStack {
            shortcut(state.nowPlaying?.isPlaying == true ? DulcetStrings.pause : DulcetStrings.play,
                     key: .space, modifiers: [], enabled: !spaceBelongsToText && state.isEnabled(.playPause)) {
                perform(.playPause, in: state)
            }
            shortcut(DulcetStrings.next, key: .rightArrow, modifiers: .command,
                     enabled: state.isEnabled(.next)) {
                perform(.next, in: state)
            }
            shortcut(DulcetStrings.previous, key: .leftArrow, modifiers: .command,
                     enabled: state.isEnabled(.previous)) {
                perform(.previous, in: state)
            }
            shortcut(DulcetStrings.menuSearch, key: "f", modifiers: .command, enabled: true) {
                store.focusSearch()
            }
            shortcut(DulcetStrings.menuShowNowPlaying, key: "l", modifiers: .command,
                     enabled: store.showsNowPlayingBar) {
                onShowNowPlaying()
            }
        }
        .frame(width: 0, height: 0)
        .opacity(0)
        .accessibilityHidden(true)
    }

    private func shortcut(
        _ title: String,
        key: KeyEquivalent,
        modifiers: EventModifiers,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(title, action: action)
            .keyboardShortcut(key, modifiers: modifiers)
            .disabled(!enabled)
    }

    private func perform(_ command: DulcetPlaybackMenuCommand, in state: DulcetPlaybackMenuState) {
        guard let intent = state.action(for: command) else { return }
        store.sendPlaybackControl(intent)
    }
}

/// Tabs for the destinations that exist (Library, Search, Connection), with the now-playing bar
/// above the tab bar.
///
/// The bar is a material card in each tab rather than the tab bar's own bottom accessory. The
/// accessory was tried on iOS 26.5: its glass adapts to the content scrolling beneath it, and
/// OBSERVED in the simulator it rendered the track title white on a light backdrop, illegible,
/// with the system's default foreground as well as with ours. A regular-material card has a
/// fixed contrast relationship and behaves the same on every supported iOS version.
private struct DulcetCompactShell: View {
    @Bindable var store: DulcetPresentationStore
    @Binding var lastTab: DulcetSidebarDestination
    let onOpenPlayer: () -> Void

    var body: some View {
        TabView(selection: tabSelection) {
            ForEach(DulcetIOSShell.places) { destination in
                DulcetDestinationStack(
                    store: store,
                    tab: destination,
                    barPlacement: .floating,
                    onOpenPlayer: onOpenPlayer
                )
                    .tabItem {
                        Label(destination.windowTitle, systemImage: destination.symbolName)
                            .accessibilityIdentifier("dulcet.tab.\(destination.rawValue)")
                    }
                    .tag(destination)
            }
        }
    }

    private var tabSelection: Binding<DulcetSidebarDestination> {
        Binding(
            get: {
                let destination = store.selectedDestination
                return DulcetIOSShell.places.contains(destination) ? destination : lastTab
            },
            set: { destination in
                lastTab = destination
                // Each tab comes back as it was left, and choosing the tab already showing
                // returns it to its root, as tab bars do: for Library that is the grid, out of
                // whichever album or artist was open.
                store.navigate(to: destination)
            }
        )
    }
}

#endif

#if !os(tvOS)
/// One navigation stack showing a destination, with library pages pushed onto it.
///
/// In a tab, `tab` names the destination this stack belongs to. Only the Library destination
/// pushes pages, and its stack is the store's `libraryPath`, which outlives the stack: a tab
/// switched away from and back, or a split view's detail rebuilt for another destination, shows
/// the pages it was left on.
struct DulcetDestinationStack: View {
    @Bindable var store: DulcetPresentationStore
    var tab: DulcetSidebarDestination?
    /// Where this stack shows the now-playing bar, if it shows one. It is applied to the root and
    /// to every pushed page, inside the stack: OBSERVED on an iPad, a stack that is a split
    /// view's detail is adopted into the column's own navigation, and a bar applied around the
    /// stack drew on the root but vanished once an album was pushed.
    var barPlacement: DulcetNowPlayingBarPlacement.Placement?
    var onOpenPlayer: () -> Void = {}
    /// The last library surface this tab drew, kept while another tab is showing. The snapshot
    /// describes one destination at a time, so without it the Library tab would draw nothing
    /// while hidden and rebuild its grid -- scrolled back to the top -- on every return.
    @State private var retainedLibrary: DulcetSnapshot?

    private var destination: DulcetSidebarDestination { tab ?? store.selectedDestination }
    private var showing: Bool { tab.map { $0 == store.selectedDestination } ?? true }

    var body: some View {
        NavigationStack(path: libraryPath) {
            Group {
                if let displayed = displayedSnapshot {
                    // One branch whether showing or retained, so the grid keeps its identity --
                    // and its scroll position -- across a tab switch.
                    DulcetStateSurface(
                        store: store,
                        libraryAsStackRoot: true,
                        snapshotOverride: showing ? nil : displayed
                    )
                    .allowsHitTesting(showing)
                } else {
                    Color.dulcetWindow.ignoresSafeArea()
                }
            }
            .modifier(bar(drawsNotices: showing && libraryPath.wrappedValue.isEmpty))
            .navigationDestination(for: DulcetLibraryRoute.self) { route in
                DulcetLibraryRouteView(store: store, route: route)
                    .modifier(bar(drawsNotices: showing && libraryPath.wrappedValue.last == route))
            }
        }
        // A different destination is a different stack: switching from an open album to Search
        // must not animate as a pop.
        .id(destination)
        .onAppear(perform: retainLibrary)
        .onChange(of: store.snapshot) { _, _ in retainLibrary() }
    }

    private var displayedSnapshot: DulcetSnapshot? {
        if showing { return store.snapshot }
        return destination == .library ? retainedLibrary : nil
    }

    private func retainLibrary() {
        guard showing, destination == .library,
              store.snapshot.selectedDestination == .library else { return }
        retainedLibrary = store.snapshot
    }

    /// `drawsNotices`: only the page on top of the stack that is showing draws the playback
    /// notices, so a hidden tab or a page underneath does not hold a second copy. Pages are
    /// matched by route, so a path holding the same page twice draws on both, the lower one
    /// covered by the top.
    private func bar(drawsNotices: Bool) -> DulcetOptionalNowPlayingBar {
        DulcetOptionalNowPlayingBar(
            store: store,
            placement: barPlacement,
            isSuppressed: store.selectedDestination == .nowPlaying,
            drawsNotices: drawsNotices,
            onOpen: onOpenPlayer
        )
    }

    private var libraryPath: Binding<[DulcetLibraryRoute]> {
        // The destination this stack was drawn for, fixed now rather than read when the binding
        // is used. In a split view's detail the stack follows the selection, and the stack being
        // replaced writes its path back as it goes: Search's empty one as Library comes back,
        // which -- read live -- named Library and popped the album just restored to the grid;
        // and Library's, emptied, as Search is chosen, which pulled the person back to Library.
        // Only the stack that is showing Library can pop it.
        let stackDestination = destination
        return Binding(
            get: { stackDestination == .library ? store.libraryPath : [] },
            set: { path in
                // The back button and the edge swipe pop. Nothing pushes through this setter:
                // pages are pushed by the store selecting them.
                guard stackDestination == .library,
                      store.selectedDestination == .library else { return }
                store.popLibrary(to: path)
            }
        )
    }
}

private struct DulcetOptionalNowPlayingBar: ViewModifier {
    @Bindable var store: DulcetPresentationStore
    let placement: DulcetNowPlayingBarPlacement.Placement?
    let isSuppressed: Bool
    let drawsNotices: Bool
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
                drawsNotices: drawsNotices,
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
                if let album = currentAlbum(id) ?? retainedAlbum ?? heldAlbum(id) {
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
                } else {
                    // A page restored before the store has anything to draw for it -- a library
                    // read still answering -- says so rather than drawing an empty page.
                    DulcetLibraryLoadingView()
                }
            case let .artist(id):
                if let artist = currentArtist(id) ?? retainedArtist ?? heldArtist(id) {
                    DulcetArtistDetailView(
                        artist: artist,
                        albums: store.snapshot.albums.filter { $0.belongs(to: artist) },
                        onSelectAlbum: { album in store.selectAlbum(album.id) }
                    )
                } else {
                    DulcetLibraryLoadingView()
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

    /// A page lower in the stack than the one showing: drawn from the library the store holds,
    /// so going back reveals the page rather than a blank one while the store catches up.
    private func heldAlbum(_ id: DulcetProviderItemID) -> DulcetAlbum? {
        store.snapshot.albums.first { $0.id == id }
    }

    private func heldArtist(_ id: DulcetProviderItemID) -> DulcetArtist? {
        store.snapshot.artists.first { $0.id == id }
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
    /// The rows to show. The Mac lists Now Playing as a place; iOS presents it over the content
    /// from the now-playing bar instead, and leaves it out.
    var destinations: [DulcetSidebarDestination] = [.library, .search, .nowPlaying, .settings]

    var body: some View {
        VStack(spacing: 0) {
            List(selection: selection) {
                Section {
                    if destinations.contains(.library) {
                        sidebarRow(DulcetStrings.library, symbol: DulcetSidebarDestination.library.symbolName, destination: .library)
                    }
                    if destinations.contains(.search) {
                        sidebarRow(DulcetStrings.search, symbol: DulcetSidebarDestination.search.symbolName, destination: .search)
                    }
                    if destinations.contains(.nowPlaying) {
                        sidebarRow(DulcetStrings.nowPlaying, symbol: DulcetSidebarDestination.nowPlaying.symbolName, destination: .nowPlaying)
                    }
                } header: {
                    Text(DulcetStrings.browseSection)
                        .textCase(.uppercase)
                }

                if destinations.contains(.settings) {
                    Section {
                        sidebarRow(DulcetStrings.settings, symbol: "server.rack", destination: .settings)
                    } header: {
                        Text(DulcetStrings.accountSection)
                            .textCase(.uppercase)
                    }
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
                store.navigate(to: destination)
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
    /// Draws this snapshot instead of the store's: a hidden tab keeping what it last showed.
    var snapshotOverride: DulcetSnapshot?

    private var snapshot: DulcetSnapshot { snapshotOverride ?? store.snapshot }

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
                onActivateResult: store.activateSearchResult,
                focusRequested: store.searchFocusRequested,
                onFocusRequestHandled: store.searchFocusRequestHandled
            )
        case .nowPlaying:
            if snapshot.state == .nowPlaying, let player = snapshot.nowPlaying {
                DulcetNowPlayingView(
                    player: player,
                    onControl: store.sendPlaybackControl,
                    onEdit: store.editQueue
                )
            } else if snapshot.state == .nowPlayingPreparing {
                DulcetPlaybackPreparingView()
            } else if snapshot.state == .nowPlayingFailed {
                DulcetPlaybackFailedView(
                    failure: snapshot.playbackFailure,
                    onControl: store.sendPlaybackControl
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
        // One case, so the grid under a pushed page is the same view as the grid itself: split
        // across two cases it was rebuilt on every push, and back returned to its top.
        case .libraryBrowse,
             .albumDetailMultiDisc where libraryAsStackRoot,
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
