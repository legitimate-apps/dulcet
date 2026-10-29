#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

public enum DulcetRenderVariant: Sendable {
    case standard
    case deliberatelyBadControl
}

public struct DulcetRootView: View {
    @Bindable private var store: DulcetPresentationStore
    @Environment(\.scenePhase) private var scenePhase
    private let variant: DulcetRenderVariant

    public init(
        store: DulcetPresentationStore,
        variant: DulcetRenderVariant = .standard
    ) {
        self.store = store
        self.variant = variant
    }

    @State private var accountPresented = false
    @State private var accountReturnDestination: DulcetSidebarDestination = .listenNow

    @Environment(\.dynamicTypeSize) private var preferredTextSize
    private var proofTextSize: DynamicTypeSize {
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-dulcet-debug-largest-text") { return .accessibility5 }
#endif
        return preferredTextSize
    }

    private var proofColorScheme: ColorScheme? {
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("-dulcet-debug-dark-mode") { return .dark }
        if arguments.contains("-dulcet-debug-light-mode") { return .light }
#endif
        return nil
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
        .modifier(DulcetLibrarySessionChrome(store: store, scenePhase: scenePhase))
        .preferredColorScheme(proofColorScheme)
        .environment(\.dynamicTypeSize, proofTextSize)
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
        .environment(\.dulcetOpenAccount, { accountPresented = true })
        .sheet(isPresented: $accountPresented) {
            NavigationStack {
                DulcetAccountConnectionView(store: store)
            }
            .environment(\.dulcetCloseAccount, { accountPresented = false })
            .environment(store)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .onChange(of: store.selectedDestination, initial: true) { previous, current in
            if current == .settings {
                accountReturnDestination = previous == .settings ? .listenNow : previous
                store.navigate(to: accountReturnDestination)
                accountPresented = true
            }
        }
        .modifier(DulcetLibrarySessionChrome(store: store, scenePhase: scenePhase))
        .preferredColorScheme(proofColorScheme)
        .environment(\.dynamicTypeSize, proofTextSize)
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
        .modifier(DulcetLibrarySessionChrome(store: store, scenePhase: scenePhase))
        .preferredColorScheme(proofColorScheme)
        .environment(\.dynamicTypeSize, proofTextSize)
        .environment(store)
        .tint(.dulcetAccent)
#endif
    }
}

/// What the library session needs from the app's root: the foreground state, its notices, and
/// the sign-out offer.
private struct DulcetLibrarySessionChrome: ViewModifier {
    @Bindable var store: DulcetPresentationStore
    let scenePhase: ScenePhase

    func body(content: Content) -> some View {
        content
            .dulcetLibraryNotices(store: store)
            .dulcetSignOutOffer(store: store)
            .dulcetPlaylistSheets(store: store)
            .alert(DulcetStrings.serverReplacementTitle, isPresented: Binding(
                get: { store.pendingDownloadRemovalCount != nil },
                set: { if !$0 { store.cancelServerReplacement() } }
            )) {
                Button(DulcetStrings.serverReplacementAction, role: .destructive) { store.confirmServerReplacement() }
                    .accessibilityIdentifier("dulcet.account.confirm-replacement")
                Button(DulcetStrings.cancel, role: .cancel) { store.cancelServerReplacement() }
                    .accessibilityIdentifier("dulcet.account.cancel-replacement")
            } message: {
                Text(DulcetStrings.serverReplacementBody(downloadCount: store.pendingDownloadRemovalCount ?? 0))
            }
            .onChange(of: store.librarySession?.readerGeneration) { _, _ in store.resetReaderNavigation() }
            // Foreground is "not in the background": an inactive app -- another window in
            // front, a system sheet -- still shows its library and keeps it current (§16.14).
            .onAppear { store.librarySession?.setForeground(scenePhase != .background) }
            .onChange(of: scenePhase) { _, phase in
                store.librarySession?.setForeground(phase != .background)
            }
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
        // The app root's tint, so the capture draws accent-driven controls as the app does.
        .tint(.dulcetAccent)
    }
}
#endif

/// Where remote focus goes on Apple TV when a section arrives without the person choosing it,
/// until the person moves focus themselves.
///
/// Two arrivals leave focus with nowhere to be but the section bar. At launch the bar is the
/// first focusable row on screen, so the focus engine's own first pass puts focus on the control
/// for the section already showing, where Select does nothing. And when the app changes section
/// for itself -- activating a search result opens Now Playing -- the control that held focus goes
/// away, and the engine again falls back to the bar. In both, the arriving section's own control
/// claims focus instead. The content can arrive after the engine's pass, so the claim stays open
/// while focus is only where the engine put it, and closes as soon as the person moves focus:
/// along the bar, down out of it, with the exit button, or by choosing a section on the bar,
/// which keeps focus on the bar where they put it.
///
/// Platform-neutral so its decisions are testable off the device; only Apple TV installs one.
@MainActor
@Observable
final class DulcetArrivalFocus {
    /// True while an arriving section's control should take focus: from launch, and again after
    /// the app changed section for itself, until a claim lands or the person moves focus.
    private(set) var pending = true
    /// Whether the section bar holds focus, as the shell last reported it.
    private(set) var barHoldsFocus = false
    /// The section the person last chose on the bar, until the change it asks for arrives.
    private var chosenOnBar: DulcetSidebarDestination?

    /// True while a section's own control should take focus from the bar.
    var wantsSectionFocus: Bool { pending && barHoldsFocus }

    /// The section bar's focused control changed. Focus arriving on the bar from nowhere is the
    /// focus engine placing it; focus leaving a bar control -- to another bar control or into a
    /// section -- is a move, by the person or by a claim that already landed.
    func sectionBarFocusChanged(wasOnBar: Bool, isOnBar: Bool) {
        if wasOnBar { pending = false }
        barHoldsFocus = isOnBar
    }

    /// A section's own control reports that it now holds focus.
    func sectionControlFocused() {
        pending = false
    }

    /// The person chose a section on the bar; its arrival leaves focus on the bar.
    func sectionChosenOnBar(_ destination: DulcetSidebarDestination) {
        pending = false
        chosenOnBar = destination
    }

    /// The section showing changed. One the person chose on the bar settles focus there; any
    /// other change is the app's own, and the arriving section is asked to take focus.
    func sectionChanged(to destination: DulcetSidebarDestination) {
        pending = destination != chosenOnBar
        chosenOnBar = nil
    }

    /// The person pressed the exit button.
    func settle() {
        pending = false
    }
}

#if os(tvOS)
/// The platform tab bar owns section appearance and remote navigation.
private struct DulcetTVSectionNavigation: View {
    @Bindable var store: DulcetPresentationStore
    @State private var arrivalFocus = DulcetArrivalFocus()

    var body: some View {
        TabView(selection: Binding(get: { store.selectedDestination }, set: { store.navigate(to: $0) })) {
            ForEach(DulcetSidebarDestination.allCases) { destination in
                NavigationStack(path: Binding(
                    get: { store.readerPath(for: destination) },
                    set: { path in
                        if store.selectedDestination == destination, [.listenNow, .library, .browse].contains(destination) {
                            store.popReader(to: path)
                        }
                    }
                )) {
                    Group {
                        if store.selectedDestination == destination { DulcetStateSurface(store: store) }
                        else { Color.clear }
                    }
                    .navigationDestination(for: DulcetReaderRoute.self) { route in
                        DulcetReaderRouteView(route: route).environment(\.dulcetReaderPage, route)
                    }
                }
                .tabItem {
                    Label(destination.windowTitle, systemImage: destination.symbolName)
                        .accessibilityIdentifier("dulcet.tab.\(destination.rawValue)")
                }
                .tag(destination)
            }
        }
        .environment(arrivalFocus)
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
/// the player over the whole window. The presentation lives here, above both shells, so a window
/// resized across the boundary keeps the same player and its interaction state.
private struct DulcetIOSShell: View {
    @Bindable var store: DulcetPresentationStore
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var playerPresented = false
    @State private var playerTransition = DulcetPlayerTransition()
    /// The last place the person was, so a Now Playing destination arriving from elsewhere (the
    /// Show Now Playing command, a restored selection) opens the player over that place.
    @State private var lastPlace: DulcetSidebarDestination = .listenNow

    static let places: [DulcetSidebarDestination] = [.listenNow, .library, .browse, .search]

    private var compact: Bool { horizontalSizeClass == .compact }
    private var usesSystemAccessory: Bool { if #available(iOS 26.1, *) { true } else { false } }

    var body: some View {
        // A concrete container keeps the UIKit presenter alive when its shell child changes.
        // Group distributes the background to that child and tears it down at the boundary.
        ZStack {
            if #available(iOS 18.0, *) {
                DulcetCompactShell(store: store, lastTab: $lastPlace, onOpenPlayer: openPlayer)
            } else if compact {
                DulcetCompactShell(store: store, lastTab: $lastPlace, onOpenPlayer: openPlayer)
            } else {
                ZStack {
                    Color.dulcetWindow.ignoresSafeArea()
                    NavigationSplitView {
                        DulcetSidebar(store: store, destinations: store.destinations(Self.places))
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
#if DEBUG
        .background {
            GeometryReader { geometry in
                Color.clear
                    .onAppear { recordShellGeometry(geometry.size) }
                    .onChange(of: geometry.size) { _, size in recordShellGeometry(size) }
                    .onChange(of: horizontalSizeClass) { _, _ in recordShellGeometry(geometry.size) }
            }
        }
#endif
        // Split-view destinations inherit from the split presentation owner, which is above
        // its detail stack. Keep the existing player action and chrome available there too.
        .environment(\.dulcetPageNowPlayingBar, DulcetOptionalNowPlayingBar(
            store: store,
            placement: usesSystemAccessory ? nil : .floating,
            isSuppressed: store.selectedDestination == .nowPlaying,
            drawsNotices: true,
            onOpen: openPlayer
        ))
        .environment(\.dulcetPlayerTransition, playerTransition)
        .background {
            DulcetPlayerPresenter(
                transition: playerTransition,
                presented: playerPresented,
                content: {
                    AnyView(DulcetNowPlayingSheet(store: store, presentation: compact ? .sheet : .fullScreen, onClose: closePlayer)
                        .environment(store)
                        .tint(.dulcetAccent)
                        .environment(\.dulcetPlayerTransition, playerTransition))
                },
                onPresentedChanged: { if playerPresented != $0 { playerPresented = $0 } }
            )
        }
        // The player carries its own while it is up; this one speaks for the shell under it.
        .dulcetPlaybackFeedback(store: store, isActive: !playerPresented)
        .onAppear { DulcetPlayerFrameProbe.startIfRequested(); absorbNowPlayingDestination() }
        .onChange(of: store.selectedDestination) { _, _ in absorbNowPlayingDestination() }
        .background {
            DulcetKeyboardShortcuts(store: store, onShowNowPlaying: openPlayer)
        }
    }

    private func recordShellGeometry(_ size: CGSize) {
        DulcetUIProofMarkers.record("SHELL|width=\(size.width)|height=\(size.height)|compact=\(compact)")
    }

    private func openPlayer() { playerPresented = true }
    private func closePlayer() { playerPresented = false }

    private func absorbNowPlayingDestination() {
        let destination = store.selectedDestination
        if store.destinations(Self.places).contains(destination) {
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

/// Native tab chrome owns the accessory and adapts to a sidebar on iPad.
private struct DulcetCompactShell: View {
    @Bindable var store: DulcetPresentationStore
    @Binding var lastTab: DulcetSidebarDestination
    let onOpenPlayer: () -> Void
    @State private var allowsTabMinimization = false

    var body: some View {
        if #available(iOS 26.1, *) {
            modernTabs
                .tabViewStyle(.sidebarAdaptable)
                .tabViewBottomAccessory(isEnabled: store.showsNowPlayingBar) {
                    DulcetNowPlayingBar(store: store, onOpen: onOpenPlayer)
                        .dulcetQueueDropTarget(store: store, cornerRadius: 12)
                }
                .tabBarMinimizeBehavior(allowsTabMinimization ? .onScrollDown : .never)
                .environment(\.dulcetUserScrollAwayFromTop, { allowsTabMinimization = $0 })
                .onChange(of: store.readerPath) { _, _ in allowsTabMinimization = false }
                .onChange(of: store.selectedDestination) { _, _ in allowsTabMinimization = false }
        } else if #available(iOS 18.0, *) {
            modernTabs
                .tabViewStyle(.sidebarAdaptable)
        } else {
            TabView(selection: tabSelection) {
                ForEach(DulcetIOSShell.places) { destination in
                    tabPage(destination, systemAccessory: false)
                        .tabItem { tabLabel(destination) }
                        .tag(destination)
                }
            }
        }
    }

    @available(iOS 18.0, *)
    private var modernTabs: some View {
        TabView(selection: tabSelection) {
            ForEach(DulcetIOSShell.places.filter { $0 != .search }) { destination in
                Tab(value: destination) {
                    tabPage(destination, systemAccessory: hasSystemAccessory)
                } label: { tabLabel(destination) }
            }
            Tab(value: DulcetSidebarDestination.search, role: .search) {
                tabPage(.search, systemAccessory: hasSystemAccessory)
            } label: { tabLabel(.search) }
        }
    }

    private var hasSystemAccessory: Bool { if #available(iOS 26.1, *) { true } else { false } }

    private func tabPage(_ destination: DulcetSidebarDestination, systemAccessory: Bool) -> some View {
        DulcetDestinationStack(store: store, tab: destination,
            barPlacement: systemAccessory ? nil : .floating, onOpenPlayer: onOpenPlayer)
    }

    private func tabLabel(_ destination: DulcetSidebarDestination) -> some View {
        Label(destination.windowTitle, systemImage: destination.symbolName)
            .accessibilityIdentifier("dulcet.tab.\(destination.rawValue)")
    }

    private var tabSelection: Binding<DulcetSidebarDestination> {
        Binding(
            get: {
                let destination = store.selectedDestination
                return store.destinations(DulcetIOSShell.places).contains(destination) ? destination : lastTab
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
    // A split-view column keeps navigation state while its destination changes. Both the reader
    // and snapshot stacks use one element type so SwiftUI can compare their paths safely.
    private typealias StackRoute = DulcetStackRoute
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
    private struct ReaderContext: Hashable {
        var destination: DulcetSidebarDestination
        var generation: Int
        var enabled: Bool
    }
    private typealias ReaderPresentation = DulcetReaderStackPresentation<DulcetReaderRoute, ReaderContext>
    @State private var readerPresentation = ReaderPresentation()

    private var readerRequest: ReaderPresentation.Request {
        .init(context: .init(destination: destination, generation: store.librarySession?.readerGeneration ?? 0,
                             enabled: usesReader),
              path: usesReader ? store.readerPath(for: destination) : [])
    }

    private var destination: DulcetSidebarDestination { tab ?? store.selectedDestination }
    private var showing: Bool { tab.map { $0 == store.selectedDestination } ?? true }

    private var usesReader: Bool {
        (destination == .listenNow || destination == .library || destination == .browse) && store.readerOwnsLibrary
    }

    var body: some View {
        // One concrete root and one destination registration survive reader/snapshot changes.
        // Group distributes a registration into its changing children; a split column or a
        // retained tab must keep the navigation owner even while those children change.
        NavigationStack(path: usesReader ? readerPath : snapshotPath) {
            ZStack {
                if usesReader {
                    if destination == .listenNow { DulcetListenNowView() }
                    else { DulcetReaderCollectionRoot(destination: destination) }
                } else if let displayed = displayedSnapshot {
                    DulcetStateSurface(store: store, libraryAsStackRoot: true,
                                       snapshotOverride: showing ? nil : displayed)
                } else {
                    Color.dulcetWindow.ignoresSafeArea()
                }
            }
            .modifier(DulcetAccountToolbar())
            .allowsHitTesting(showing)
            .modifier(bar(drawsNotices: showing && (usesReader ? store.readerPath.isEmpty : libraryPath.wrappedValue.isEmpty)))
            .navigationDestination(for: StackRoute.self) { path in
                stackPage(path).modifier(DulcetAccountToolbar())
            }
        }
        // Concrete NavigationLink destinations need the same page chrome as store routes.
        .environment(\.dulcetPageNowPlayingBar, bar(drawsNotices: showing))
        // The split detail keeps its navigation owner when a destination changes. Recreating
        // it with an already-pushed path causes SwiftUI to write its initial [] over that push.
        .onAppear(perform: retainLibrary)
        .onChange(of: store.snapshot) { _, _ in retainLibrary() }
        .onChange(of: readerRequest, initial: true) { _, request in
            store.navigationBindingReceipt("DulcetDestinationStack.request[\(destination)]",
                oldPath: String(describing: readerPresentation.path), newPath: String(describing: request.path),
                outcome: "request;context=\(request.context)")
            readerPresentation.request(request)
        }
        .task(id: readerPresentation.pendingReplay) {
            guard let request = readerPresentation.pendingReplay else { return }
            // The initial reset must first become an actual empty displayed path. Reapply
            // after this UI update, with no timer and no replay across an account change.
            await Task.yield()
            guard !Task.isCancelled, readerRequest == request else { return }
            store.navigationBindingReceipt("DulcetDestinationStack.replay", oldPath: String(describing: readerPresentation.path),
                                           newPath: String(describing: request.path), outcome: "reapply-unpresented-route")
            readerPresentation.replay(request)
        }
    }

    private var readerPath: Binding<[StackRoute]> {
        let stackDestination = destination
        return Binding(
            get: { readerPresentation.displayedPath(for: readerRequest).map(StackRoute.reader) },
            set: { path in
                // A departing Library stack must not pop the page after Search takes its place.
                store.navigationBindingReceipt("DulcetDestinationStack.readerPath[\(stackDestination)]", oldPath: String(describing: store.readerPath(for: stackDestination)), newPath: String(describing: path), outcome: store.selectedDestination == stackDestination ? "received" : "guard-dropped:destination")
                guard store.selectedDestination == stackDestination else { return }
                let routes: [DulcetReaderRoute] = path.compactMap { if case let .reader(route) = $0 { route } else { nil } }
                guard routes.count == path.count else {
                    store.navigationBindingReceipt("DulcetDestinationStack.readerPath", oldPath: String(describing: store.readerPath), newPath: String(describing: path), outcome: "guard-dropped:route-type")
                    return
                }
                let accepted = readerPresentation.wrote(routes)
                store.navigationBindingReceipt("DulcetDestinationStack.wrote[\(stackDestination)]",
                    oldPath: String(describing: readerPresentation.request?.path), newPath: String(describing: routes),
                    outcome: "accepted=\(accepted);presented=\(readerPresentation.presentedRoutes);replay=\(String(describing: readerPresentation.pendingReplay))")
                store.receiveReaderPathFromStack(to: routes, presentedRoutes: readerPresentation.presentedRoutes)
            }
        )
    }

    /// The common route type is rendered completely by the stack's sole registration.
    @ViewBuilder private func stackPage(_ path: StackRoute) -> some View {
        switch path {
        case let .reader(route):
            DulcetReaderRouteView(route: route)
                .onAppear {
                    readerPresentation.appeared(route)
                    store.navigationBindingReceipt("DulcetDestinationStack.appeared", oldPath: String(describing: readerPresentation.path),
                                                   newPath: String(describing: route), outcome: "presented")
                }
                .environment(\.dulcetReaderPage, route)
                .modifier(bar(drawsNotices: showing && store.readerPath.last == route))
        case let .library(route):
            DulcetLibraryRouteView(store: store, route: route)
                .modifier(bar(drawsNotices: showing && libraryPath.wrappedValue.last == route))
        }
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

    private var snapshotPath: Binding<[StackRoute]> {
        let stackDestination = destination
        let path = libraryPath
        return Binding(
            get: { path.wrappedValue.map(StackRoute.library) },
            set: { updated in
                store.navigationBindingReceipt("DulcetDestinationStack.snapshotPath[\(stackDestination)]", oldPath: String(describing: path.wrappedValue), newPath: String(describing: updated), outcome: "received")
                let routes: [DulcetLibraryRoute] = updated.compactMap { if case let .library(route) = $0 { route } else { nil } }
                guard routes.count == updated.count else {
                    store.navigationBindingReceipt("DulcetDestinationStack.snapshotPath", oldPath: String(describing: path.wrappedValue), newPath: String(describing: updated), outcome: "guard-dropped:route-type")
                    return
                }
                path.wrappedValue = routes
            }
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
                      store.selectedDestination == .library else {
                    store.navigationBindingReceipt("DulcetDestinationStack.snapshotPath.libraryPath[\(stackDestination)]", oldPath: String(describing: store.libraryPath), newPath: String(describing: path), outcome: "guard-dropped:destination")
                    return
                }
                store.popLibrary(to: path)
            }
        )
    }
}

private struct DulcetPageNowPlayingBarKey: EnvironmentKey {
    static let defaultValue: DulcetOptionalNowPlayingBar? = nil
}

private extension EnvironmentValues {
    var dulcetPageNowPlayingBar: DulcetOptionalNowPlayingBar? {
        get { self[DulcetPageNowPlayingBarKey.self] }
        set { self[DulcetPageNowPlayingBarKey.self] = newValue }
    }
}

extension View {
    /// Gives a concrete destination the shell's existing transport and player action.
    func dulcetPageNowPlayingBar() -> some View {
        modifier(DulcetInheritedNowPlayingBar())
    }
}

private struct DulcetInheritedNowPlayingBar: ViewModifier {
    @Environment(\.dulcetPageNowPlayingBar) private var bar

    func body(content: Content) -> some View {
        Group {
            if let bar { content.modifier(bar) }
            else { content }
        }
        .onChange(of: bar?.store.showsNowPlayingBar, initial: true) { _, required in
            DulcetUIProofMarkers.detail("pageBar", context: "present=\(bar != nil) required=\(required == true) suppressed=\(bar?.isSuppressed == true) placement=\(String(describing: bar?.placement))")
        }
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
/// A sidebar row: one of the app's destinations, or one of the library's sections.
private enum DulcetSidebarSelection: Hashable {
    case destination(DulcetSidebarDestination)
    case section(DulcetLibrarySection)
    case myLibrarySection(DulcetMyLibrarySection)
    case wishlist
}

private struct DulcetSidebar: View {
    @Bindable var store: DulcetPresentationStore
    /// The rows to show. The Mac lists Now Playing as a place; iOS presents it over the content
    /// from the now-playing bar instead, and leaves it out.
    var destinations: [DulcetSidebarDestination] = [.listenNow, .library, .search, .nowPlaying, .settings]
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
#if os(macOS)
    @Environment(\.openSettings) private var openSettings
#endif

    /// The column takes more room at the accessibility text sizes: at the default width the rows'
    /// labels wrapped inside words ("Li-brary").
    private var columnWidth: (min: CGFloat, ideal: CGFloat, max: CGFloat) {
        dynamicTypeSize.isAccessibilitySize ? (360, 440, 560) : (DulcetMetrics.sidebarMinWidth, 232, 300)
    }

    var body: some View {
        VStack(spacing: 0) {
            List(selection: listSelection) {
                Section {
                    if destinations.contains(.listenNow) {
                        sidebarRow(DulcetStrings.listenNow, symbol: "play.circle", destination: .listenNow)
                    }
                    if destinations.contains(.library) {
                        sidebarRow(DulcetStrings.library, symbol: DulcetSidebarDestination.library.symbolName, destination: .library)
                    }
                    if destinations.contains(.library), store.librarySession?.discoverPublication?.access.showsWishlistDestination == true {
                        sidebarChoice(.wishlist) { Label(DulcetStrings.wishlist, systemImage: "bookmark") }
                            .accessibilityLabel(DulcetStrings.wishlist)
                            .accessibilityIdentifier("wishlist.destination")
                    }
                    if destinations.contains(.library) {
                        sidebarRow(DulcetStrings.browseSection, symbol: "globe", destination: .browse)
                    }
                    if destinations.contains(.search) {
                        sidebarRow(DulcetStrings.search, symbol: DulcetSidebarDestination.search.symbolName, destination: .search)
                    }
                    if destinations.contains(.nowPlaying) {
                        sidebarRow(DulcetStrings.nowPlaying, symbol: DulcetSidebarDestination.nowPlaying.symbolName, destination: .nowPlaying)
                    }
                }

            }
            .listStyle(.sidebar)

            Divider()
            serverStatus
                .padding(DulcetSpacing.sm)
        }
        .navigationSplitViewColumnWidth(
            min: columnWidth.min,
            ideal: columnWidth.ideal,
            max: columnWidth.max
        )
    }

    private func sidebarRow(
        _ title: String,
        symbol: String,
        destination: DulcetSidebarDestination
    ) -> some View {
        sidebarChoice(.destination(destination)) { Label(title, systemImage: symbol) }
            .accessibilityLabel(title)
            .accessibilityIdentifier("dulcet.sidebar.\(destination.rawValue)")
    }

    // On iPad, List(selection:) also tells NavigationSplitView to reset its detail's
    // navigation path after the selection setter returns. That erased a route the setter
    // had just opened (the opt-in NAV receipt records the subsequent [] binding write).
    // The store owns navigation; these rows are explicit actions with the same selected
    // appearance and accessibility state. AppKit's native source-list selection remains.
    private var listSelection: Binding<DulcetSidebarSelection?>? {
#if os(iOS)
        nil
#else
        selection
#endif
    }

    @ViewBuilder private func sidebarChoice<Content: View>(
        _ choice: DulcetSidebarSelection, @ViewBuilder label: () -> Content
    ) -> some View {
#if os(iOS)
        Button { selection.wrappedValue = choice } label: {
            label().frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(selection.wrappedValue == choice ? Color.dulcetControl : Color.clear)
        .accessibilityAddTraits(selection.wrappedValue == choice ? .isSelected : [])
#else
        label().tag(choice)
#endif
    }

    private var selection: Binding<DulcetSidebarSelection?> {
        Binding(
            get: {
                if store.selectedDestination == .library, store.readerPath.first == .wishlist { return .wishlist }
                if store.selectedDestination == .library,
                   case let .myLibrarySection(section)? = store.readerPath.first { return .myLibrarySection(section) }
                if store.readerOwnsLibrary, store.selectedDestination == .browse,
                   case let .section(section)? = store.readerPath.first {
                    return .section(section)
                }
                return .destination(store.selectedDestination)
            },
            set: { selection in
                store.navigationBindingReceipt("sidebar.selection", oldPath: String(describing: store.readerPath),
                    newPath: String(describing: store.readerPath), outcome: "requested=\(String(describing: selection))")
                switch selection {
                case nil:
                    return
                case let .destination(destination):
                    store.navigate(to: destination)
                case let .section(section):
                    store.selectLibrarySection(section)
                case .wishlist:
                    DulcetUIProofMarkers.record("discover-wishlist-sidebar")
                    store.showReaderPage(.wishlist)
                case let .myLibrarySection(section):
                    store.selectMyLibrarySection(section)
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
        case .disconnected:
            Label(DulcetStrings.offline, systemImage: "wifi.slash")
                .font(.caption)
                .dulcetForeground(.secondaryTextOnThinMaterial)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .connectionFailed:
            Label(DulcetStrings.connectionFailed, systemImage: "exclamationmark.circle")
                .font(.caption)
                .dulcetForeground(.primaryTextOnThinMaterial)
                .frame(maxWidth: .infinity, alignment: .leading)
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
#if os(macOS)
    @Environment(\.openSettings) private var openSettings
#endif
    var allowsProgrammaticFocus = true
    /// Inside a navigation stack, album and artist pages are pushed on top of the grid rather
    /// than replacing it, so the grid stays the stack's root in those states.
    var libraryAsStackRoot = false
    /// Draws this snapshot instead of the store's: a hidden tab keeping what it last showed.
    var snapshotOverride: DulcetSnapshot?

    private var snapshot: DulcetSnapshot { snapshotOverride ?? store.snapshot }

    var body: some View {
        switch snapshotOverride == nil ? store.selectedDestination : snapshot.selectedDestination {
        case .settings:
            DulcetAccountConnectionView(
                store: store,
                allowsProgrammaticFocus: allowsProgrammaticFocus
            )
        case .listenNow:
            if store.readerOwnsLibrary { DulcetListenNowView() } else { librarySurface }
        case .library, .browse:
            if store.readerOwnsLibrary {
                DulcetReaderCollectionRoot(destination: store.selectedDestination)
            } else {
                librarySurface
            }
        case .search where store.readerOwnsLibrary:
            DulcetReaderSearchScreen(store: store)
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
#if os(macOS)
                // This player exposes Favorite and ratings through the window toolbar.
                .toolbar {
                    if store.nowPlayingFavourite(for: player.current) != nil {
                        ToolbarItem(placement: .primaryAction) {
                            DulcetNowPlayingFavouriteButton(track: player.current)
                        }
                        ToolbarItem(placement: .primaryAction) {
                            Menu {
                                DulcetRatingMenu(target: .init(kind: .track, id: player.current.id), published: nil)
                            } label: { Label(DulcetStrings.more, systemImage: "ellipsis.circle") }
                            .accessibilityIdentifier("dulcet.now-playing.toolbar.more")
                        }
                    }
                }
#endif
            } else if snapshot.state == .nowPlayingPreparing {
                DulcetPlaybackPreparingView()
            } else if snapshot.state == .nowPlayingFailed {
                DulcetPlaybackFailedView(
                    failure: snapshot.playbackFailure,
                    onControl: store.sendPlaybackControl
                )
                .dulcetNavigationTitle(DulcetSidebarDestination.nowPlaying.windowTitle)
            } else {
                ContentUnavailableView {
                    Label(DulcetStrings.nowPlayingUnavailableTitle, systemImage: "music.note")
                } description: {
                    Text(DulcetStrings.nowPlayingUnavailableBody)
                } actions: {
                    Button(DulcetStrings.goToLibrary) { store.navigate(to: .library) }
                        .accessibilityIdentifier("dulcet.now-playing.empty.library")
                }
                .dulcetNavigationTitle(DulcetSidebarDestination.nowPlaying.windowTitle)
            }
        }
    }

    private func openAccount() {
#if os(macOS)
        openSettings()
#else
        store.selectDestination(.settings)
#endif
    }

    @ViewBuilder
    private var librarySurface: some View {
        switch snapshot.state {
        case .accountSavedDisconnected:
            if case let .saved(serverName) = snapshot.accountConnection {
                DulcetSavedAccountLibraryView(serverName: serverName) {
                    store.submitAccountConnection()
                }
                .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
            } else {
                DulcetEmptyLibraryView(onConnect: openAccount)
                    .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
            }
        case .emptyLibraryNoAccount:
            DulcetEmptyLibraryView(onConnect: openAccount)
                .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
        case .emptyLibraryConnected:
            DulcetEmptyLibraryView(connected: true)
                .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
        case .libraryLoading:
            DulcetLibraryLoadingView()
                .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
        case .libraryError:
            DulcetLibraryErrorView(failure: snapshot.libraryFailure) {
                store.selectDestination(.library)
            }
            .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
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
                    .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
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
                    .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
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
                .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
        default:
            DulcetEmptyLibraryView(connected: snapshot.accountConnected)
                .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
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
        .dulcetNavigationTitle(DulcetSidebarDestination.library.windowTitle, keepsTVNavigationBar: true)
    }
}

extension DulcetSidebarDestination {
    var symbolName: String {
        switch self {
        case .listenNow: "play.circle"
        case .library: "rectangle.grid.2x2"
        case .browse: "globe"
        case .search: "magnifyingglass"
        case .nowPlaying: "waveform"
        case .settings: "server.rack"
        }
    }

    var windowTitle: String {
        switch self {
        case .listenNow: DulcetStrings.listenNow
        case .library: DulcetStrings.library
        case .browse: DulcetStrings.browseSection
        case .search: DulcetStrings.search
        case .nowPlaying: DulcetStrings.nowPlaying
        case .settings: DulcetStrings.accountSection
        }
    }
}
#endif
