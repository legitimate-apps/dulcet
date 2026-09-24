import Observation

public enum DulcetPresentationAction: Sendable, Hashable {
    case selectDestination(DulcetSidebarDestination)
    case updateSearchQuery(String)
    case loadMoreSearchResults(DulcetSearchResultKind)
    case retrySearch
    case activateSearchResult(DulcetProviderItemID)
    case selectAlbum(DulcetProviderItemID)
    case retryAlbumTracks
    case playLibrary(shuffle: Bool)
    case playAlbum(DulcetProviderItemID, shuffle: Bool)
    case activateTrack(albumID: DulcetProviderItemID, trackID: DulcetProviderItemID)
    case downloadTrack(DulcetProviderItemID)
    /// Shows one library album from anywhere in the app.
    case showAlbum(DulcetProviderItemID)
    /// Shows one library artist from anywhere in the app.
    case showArtist(DulcetProviderItemID)
    case playbackControl(DulcetPlaybackControlIntent)
    case editQueue(DulcetQueueEditIntent)
    /// An edit the person tried that could not be made before it reached the queue -- a drop
    /// carrying nothing -- said out loud as a refused edit is.
    case reportRefusedQueueEdit
    case submitAccountConnection(DulcetAccountConnectRequest)
    case cancelAccountConnection
    case removeAccount
    case dismissAccountRemovalFailure
}

/// The presentation boundary implemented by the deterministic fixture today and live data later.
///
/// Sources accept semantic user actions and can push replacement snapshots at any time. The
/// protocol deliberately has no fixture-state API, so a network-backed source does not need to
/// emulate capture scenarios.
@MainActor
public protocol DulcetDataSource: AnyObject {
    var currentSnapshot: DulcetSnapshot { get }
    var downloadsEnabled: Bool { get }
    func setSnapshotHandler(_ handler: @escaping @MainActor (DulcetSnapshot) -> Void)
    func send(_ action: DulcetPresentationAction)
}

public extension DulcetDataSource {
    var downloadsEnabled: Bool { false }
}

/// Optional capability of a data source: answering, synchronously, whether an item the person
/// can see has a library page to go to. A view offers a "Go to" link only when this says yes, so
/// no link leads nowhere.
@MainActor
public protocol DulcetLibraryNavigating: AnyObject {
    func libraryArtistID(for credit: DulcetCredit) -> DulcetProviderItemID?
    func libraryAlbumID(for track: DulcetTrack) -> DulcetProviderItemID?
    var queueEditingEnabled: Bool { get }
}

/// A library page shown on top of the grid, as a navigation stack pushes it.
public enum DulcetLibraryRoute: Hashable, Sendable {
    case album(DulcetProviderItemID)
    case artist(DulcetProviderItemID)

    /// The page a snapshot shows on top of the library grid, if it shows one.
    public static func page(in snapshot: DulcetSnapshot) -> DulcetLibraryRoute? {
        guard snapshot.selectedDestination == .library else { return nil }
        switch snapshot.state {
        case .albumDetailMultiDisc:
            return snapshot.selectedAlbum.map { .album($0.id) }
        case .artistDetail:
            return snapshot.selectedArtist.map { .artist($0.id) }
        default:
            return nil
        }
    }
}

@MainActor
@Observable
public final class DulcetPresentationStore {
    @ObservationIgnored private let source: any DulcetDataSource
    @ObservationIgnored private var isApplyingSourceSnapshot = false

    public private(set) var snapshot: DulcetSnapshot
    public private(set) var selectedDestination: DulcetSidebarDestination
    /// Set by the search command and cleared by the search field once it has taken focus. A
    /// request, not a focus state: the field may not exist yet when the command arrives.
    public private(set) var searchFocusRequested = false
    /// The library pages open on top of the grid, outermost first: the Library destination's own
    /// navigation stack.
    ///
    /// Kept here rather than derived from the snapshot, because a snapshot describes one
    /// destination at a time. A person who opens an album, looks something up in Search and comes
    /// back expects to find the album, as every tab bar and sidebar keeps each place as it was
    /// left -- deriving the stack from the snapshot reset it to the grid on every return.
    public private(set) var libraryPath: [DulcetLibraryRoute] = []
    /// The failure the person dismissed from the now-playing bar. Only that failure stays away:
    /// it is forgotten when playback does anything else -- however that was started, the lock
    /// screen and a headset included -- and whenever the person starts something through the
    /// store, even when that fails too with no moment in between; and a different failure is not
    /// it, so it shows. It is kept by identity, so a queue edit that changes what Skip can do
    /// does not bring back a failure the person put away.
    private var dismissedPlaybackFailure: DulcetFailedPlayback.Identity?
    /// Whether the failure showing is the one the person dismissed.
    public var playbackFailureDismissed: Bool {
        guard snapshot.playbackFailed, let dismissedPlaybackFailure else { return false }
        return dismissedPlaybackFailure == (snapshot.playbackFailure ?? .undescribed).identity
    }
    public var downloadsEnabled: Bool { source.downloadsEnabled }
    public var searchQuery: String {
        didSet {
            guard !isApplyingSourceSnapshot, searchQuery != oldValue else { return }
            source.send(.updateSearchQuery(searchQuery))
        }
    }
    public var accountServerURL: String
    public var accountUsername: String
    public var accountPassword: String
    public var accountAllowLocalHTTP: Bool

    public init(source: any DulcetDataSource) {
        self.source = source
        let initialSnapshot = source.currentSnapshot
        snapshot = initialSnapshot
        selectedDestination = initialSnapshot.selectedDestination
        libraryPath = DulcetLibraryRoute.page(in: initialSnapshot).map { [$0] } ?? []
        searchQuery = initialSnapshot.searchQuery
        accountServerURL = initialSnapshot.accountForm.serverURL
        accountUsername = initialSnapshot.accountForm.username
        accountPassword = initialSnapshot.accountForm.password
        accountAllowLocalHTTP = initialSnapshot.accountForm.allowLocalHTTP

        source.setSnapshotHandler { [weak self] snapshot in
            self?.receive(snapshot)
        }
    }

    /// Shows a destination's root: Library's grid, whatever was open on top of it.
    public func selectDestination(_ destination: DulcetSidebarDestination) {
        guard !isApplyingSourceSnapshot else { return }
        selectedDestination = destination
        source.send(.selectDestination(destination))
    }

    /// Chooses a top-level destination the way a tab bar or a sidebar does. Coming back to
    /// Library shows it as it was left -- the album or artist that was open is open again -- and
    /// choosing the destination already showing returns it to its root, as tapping the current
    /// tab does.
    public func navigate(to destination: DulcetSidebarDestination) {
        guard !isApplyingSourceSnapshot else { return }
        if destination == .library, selectedDestination != .library, let page = libraryPath.last {
            selectedDestination = .library
            show(page)
            return
        }
        if destination == .library, selectedDestination == .library {
            libraryPath = []
        }
        selectDestination(destination)
    }

    /// The back button or the edge swipe left the library stack at `path`, a prefix of the one
    /// it held. The store is asked for the page now on top, or for the grid.
    public func popLibrary(to path: [DulcetLibraryRoute]) {
        guard !isApplyingSourceSnapshot,
              path.count < libraryPath.count,
              Array(libraryPath.prefix(path.count)) == path else { return }
        libraryPath = path
        if let page = path.last {
            show(page)
        } else {
            selectDestination(.library)
        }
    }

    private func show(_ page: DulcetLibraryRoute) {
        switch page {
        case let .album(id): source.send(.showAlbum(id))
        case let .artist(id): source.send(.showArtist(id))
        }
    }

    public func submitAccountConnection() {
        source.send(.submitAccountConnection(DulcetAccountConnectRequest(
            serverURL: accountServerURL,
            username: accountUsername,
            password: accountPassword,
            allowLocalHTTP: accountAllowLocalHTTP
        )))
    }

    public func cancelAccountConnection() {
        source.send(.cancelAccountConnection)
    }

    public func removeAccount() {
        source.send(.removeAccount)
    }

    public func dismissAccountRemovalFailure() {
        source.send(.dismissAccountRemovalFailure)
    }

    /// Re-reads the selected album's track list after a failure.
    public func retryAlbumTracks() {
        source.send(.retryAlbumTracks)
    }

    public func selectAlbum(_ id: DulcetProviderItemID) {
        source.send(.selectAlbum(id))
    }

    public func playLibrary(shuffle: Bool) {
        dismissedPlaybackFailure = nil
        source.send(.playLibrary(shuffle: shuffle))
    }

    public func playAlbum(_ id: DulcetProviderItemID, shuffle: Bool) {
        dismissedPlaybackFailure = nil
        source.send(.playAlbum(id, shuffle: shuffle))
    }

    public func activateTrack(albumID: DulcetProviderItemID, trackID: DulcetProviderItemID) {
        dismissedPlaybackFailure = nil
        source.send(.activateTrack(albumID: albumID, trackID: trackID))
    }

    public func downloadTrack(_ id: DulcetProviderItemID) {
        source.send(.downloadTrack(id))
    }

    public func sendPlaybackControl(_ intent: DulcetPlaybackControlIntent) {
        dismissedPlaybackFailure = nil
        source.send(.playbackControl(intent))
    }

    /// Whether the now-playing bar shows: something is queued, opening or failed -- and a
    /// failure has not been dismissed.
    public var showsNowPlayingBar: Bool {
        if snapshot.playbackFailed { return !playbackFailureDismissed }
        return snapshot.nowPlaying != nil || snapshot.playbackStatus == .preparing
    }

    /// Puts the failed track's bar away. Nothing plays until the person starts something.
    public func dismissPlaybackFailure() {
        guard snapshot.playbackFailed else { return }
        dismissedPlaybackFailure = (snapshot.playbackFailure ?? .undescribed).identity
    }

    /// The library artist a credit leads to, or nil when there is no page to show.
    public func libraryArtistID(for credit: DulcetCredit) -> DulcetProviderItemID? {
        (source as? any DulcetLibraryNavigating)?.libraryArtistID(for: credit)
    }

    /// The library album a track belongs to, or nil when it cannot be identified.
    public func libraryAlbumID(for track: DulcetTrack) -> DulcetProviderItemID? {
        (source as? any DulcetLibraryNavigating)?.libraryAlbumID(for: track)
    }

    public func showAlbum(_ id: DulcetProviderItemID) {
        source.send(.showAlbum(id))
    }

    public func showArtist(_ id: DulcetProviderItemID) {
        source.send(.showArtist(id))
    }

    /// Whether the playback controller can edit the live queue. Play Next and Add to Queue are
    /// offered only then -- hidden, never shown disabled.
    public var queueEditingEnabled: Bool {
        (source as? any DulcetLibraryNavigating)?.queueEditingEnabled == true
    }

    /// ⌘F: go to Search, and ask its field for focus where the platform lets the shell move
    /// focus into it (iOS; the Mac field deliberately carries no focus binding).
    public func focusSearch() {
        if selectedDestination != .search {
            selectDestination(.search)
        }
        searchFocusRequested = true
    }

    /// The search field took the focus a search command asked for.
    public func searchFocusRequestHandled() {
        searchFocusRequested = false
    }

    /// Play Next, Play Later, reorder, remove, clear upcoming, and jump to a queue row.
    public func editQueue(_ intent: DulcetQueueEditIntent) {
        source.send(.editQueue(intent))
    }

    /// A queue edit that failed before it could be asked for, such as a drop that carries
    /// nothing. It gets the same feedback as an edit the queue refused.
    public func reportRefusedQueueEdit() {
        source.send(.reportRefusedQueueEdit)
    }

    public func loadMoreSearchResults(_ kind: DulcetSearchResultKind) {
        source.send(.loadMoreSearchResults(kind))
    }

    public func retrySearch() {
        source.send(.retrySearch)
    }

    public func activateSearchResult(_ id: DulcetProviderItemID) {
        dismissedPlaybackFailure = nil
        source.send(.activateSearchResult(id))
    }

    @discardableResult
    public func loadArtwork(
        _ reference: DulcetArtworkReference,
        sizeBucket: DulcetArtworkSizeBucket,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> (any DulcetArtworkFetchOperation)? {
        guard let loader = source as? any DulcetArtworkLoading else {
            completion(.unavailable)
            return nil
        }
        return loader.loadArtwork(reference, sizeBucket: sizeBucket, completion: completion)
    }

    private func receive(_ snapshot: DulcetSnapshot) {
        isApplyingSourceSnapshot = true
        followLibraryPage(in: snapshot, arrivingFrom: self.snapshot.selectedDestination)
        if !snapshot.playbackFailed { dismissedPlaybackFailure = nil }
        self.snapshot = snapshot
        selectedDestination = snapshot.selectedDestination
        searchQuery = snapshot.searchQuery
        accountServerURL = snapshot.accountForm.serverURL
        accountUsername = snapshot.accountForm.username
        accountPassword = snapshot.accountForm.password
        accountAllowLocalHTTP = snapshot.accountForm.allowLocalHTTP
        isApplyingSourceSnapshot = false
    }

    /// Keeps the library stack in step with the page the source is showing. A page already in the
    /// stack is the one being returned to, so everything above it is popped; a page arriving from
    /// another destination -- an album opened from a search result or from the player -- starts
    /// the stack afresh on the grid; any other page is pushed. A library surface with no page on
    /// it is the grid, and empties the stack, except while a read is still loading: that read is
    /// the answer to the page that was asked for.
    private func followLibraryPage(
        in snapshot: DulcetSnapshot,
        arrivingFrom previousDestination: DulcetSidebarDestination
    ) {
        guard snapshot.selectedDestination == .library,
              snapshot.state != .libraryLoading else { return }
        guard let page = DulcetLibraryRoute.page(in: snapshot) else {
            libraryPath = []
            return
        }
        if let index = libraryPath.lastIndex(of: page) {
            libraryPath = Array(libraryPath[...index])
        } else if previousDestination != .library {
            libraryPath = [page]
        } else {
            libraryPath.append(page)
        }
    }
}
