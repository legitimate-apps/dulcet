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
    /// The connection form as the person has edited it, before they ask to connect.
    case editAccountForm(DulcetAccountConnectRequest)
    case submitAccountConnection(DulcetAccountConnectRequest)
    case cancelAccountConnection
    case removeAccount
    case dismissAccountRemovalFailure
    /// Plays tracks a reader screen resolved: an album, a playlist, an artist, a search.
    case playTracks(DulcetPlaybackQueueIntent)
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

/// The places inside the library the reader draws (spec §16.18).
public enum DulcetLibrarySection: String, CaseIterable, Identifiable, Sendable {
    case home
    case albums
    case artists
    case genres
    case playlists
    case favourites

    public var id: String { rawValue }
}

/// A reader page pushed onto the Library stack. The store owns the stack, so a tab switched away
/// from and back shows the page it was left on.
/// Where a Back press in the Library landed: the page now on top (nil for the section at the
/// root) and the route that page had opened, whose item takes focus again on a remote.
public struct DulcetReaderReturn: Equatable, Sendable {
    public let page: DulcetReaderRoute?
    public let opened: DulcetReaderRoute
}

public enum DulcetReaderRoute: Hashable, Sendable {
    case section(DulcetLibrarySection)
    case album(DulcetProviderItemID)
    case artist(DulcetProviderItemID)
    case playlist(DulcetProviderItemID)
    case genre(String)
    /// One album list in one order: a home row's "See All".
    case albumList(DulcetAlbumListType)
}

/// The sign-out offer for changes that have not reached the server (§14.7 step 2).
public struct DulcetSignOutOffer: Sendable, Hashable, Identifiable {
    public enum Phase: Sendable, Hashable {
        /// Asking what to do. `online` offers to send them first.
        case asking(online: Bool)
        /// Sending them; the offer stays up until the server answers.
        case sending
        /// The send could not reach the server; they are still unsent.
        case sendFailed
    }

    public let id: Int
    /// Nil when the count could not be read: the offer is still made, and says so.
    public let pendingCount: Int64?
    public let phase: Phase
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
    /// The reader's Library stack, outermost first, while the reader draws the library.
    public private(set) var readerPath: [DulcetReaderRoute] = []
    /// The library section at the root of the reader's stack: the sidebar's and the TV bar's
    /// selection. The phone's root is always Home, with the other sections pushed from it.
    public private(set) var librarySection: DulcetLibrarySection = .home
    /// The sign-out offer on screen, if one is.
    public private(set) var signOutOffer: DulcetSignOutOffer?
    @ObservationIgnored private var signOutOfferSequence = 0
    /// The lock screen's heart, kept for the store's lifetime by the app that installs it.
    @ObservationIgnored public var likeCommandDriver: DulcetLikeCommandDriver?
    /// The device's streaming-quality setting (spec §12.5), installed by the production
    /// composition. Without one the settings screen offers no choice.
    @ObservationIgnored public var streamingQualitySetting: (any DulcetStreamingQualitySetting)? {
        didSet { streamingQuality = streamingQualitySetting?.preference }
    }
    /// The streaming-quality choice as saved; nil where the setting is not installed.
    public private(set) var streamingQuality: DulcetStreamingQualityPreference?
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
    public var accountServerURL: String {
        didSet { formEdited(accountServerURL != oldValue) }
    }
    public var accountUsername: String {
        didSet { formEdited(accountUsername != oldValue) }
    }
    public var accountPassword: String {
        didSet { formEdited(accountPassword != oldValue) }
    }
    public var accountAllowLocalHTTP: Bool {
        didSet { formEdited(accountAllowLocalHTTP != oldValue) }
    }

    /// Tells the source what the person has typed, so what it does next -- a retry it would
    /// make on its own -- can answer to the form as it now is.
    private func formEdited(_ changed: Bool) {
        guard changed, !isApplyingSourceSnapshot else { return }
        source.send(.editAccountForm(accountFormRequest))
    }

    private var accountFormRequest: DulcetAccountConnectRequest {
        DulcetAccountConnectRequest(
            serverURL: accountServerURL,
            username: accountUsername,
            password: accountPassword,
            allowLocalHTTP: accountAllowLocalHTTP
        )
    }

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
        if destination == .library, readerOwnsLibrary {
            // The reader's pages are the store's own; the source is told only the destination.
            if selectedDestination == .library {
                readerPath = []
                readerReturn = nil
                readerArrival = nil
            }
            selectDestination(.library)
            return
        }
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
        source.send(.submitAccountConnection(accountFormRequest))
    }

    public func cancelAccountConnection() {
        source.send(.cancelAccountConnection)
    }

    /// Saves a streaming-quality choice. It applies from the next item played; what is playing
    /// is never restarted for it.
    public func setStreamingQuality(_ preference: DulcetStreamingQualityPreference) {
        guard let streamingQualitySetting else { return }
        streamingQualitySetting.setPreference(preference)
        streamingQuality = streamingQualitySetting.preference
    }

    public func removeAccount() {
        guard let session = librarySession, session.reader != nil else {
            source.send(.removeAccount)
            return
        }
        // Changes that have not reached the server are offered first: signing out discards them.
        session.pendingChangeCount { [weak self] pending in
            guard let self else { return }
            if pending.count == 0 {
                self.source.send(.removeAccount)
            } else {
                self.offerSignOut(pending: pending.count, phase: .asking(online: session.isOnline))
            }
        }
    }

    /// Keeps the account: the offer's default.
    public func dismissSignOutOffer() {
        signOutOffer = nil
    }

    /// Signs out, discarding the unsent changes the offer counted.
    public func signOutDiscardingChanges() {
        signOutOffer = nil
        source.send(.removeAccount)
    }

    /// Sends the unsent changes, then signs out once none are left. If the server cannot be
    /// reached the offer comes back saying so, and the account stays.
    public func sendChangesThenSignOut() {
        guard let session = librarySession, let offer = signOutOffer else { return }
        offerSignOut(pending: offer.pendingCount, phase: .sending)
        session.submitPendingChanges { [weak self] reached in
            guard let self, self.signOutOffer?.phase == .sending else { return }
            session.pendingChangeCount { [weak self] pending in
                guard let self, self.signOutOffer?.phase == .sending else { return }
                if reached, pending.count == 0 {
                    self.signOutOffer = nil
                    self.source.send(.removeAccount)
                } else {
                    self.offerSignOut(pending: pending.count, phase: reached ? .asking(online: true) : .sendFailed)
                }
            }
        }
    }

    private func offerSignOut(pending: Int64?, phase: DulcetSignOutOffer.Phase) {
        signOutOfferSequence += 1
        signOutOffer = DulcetSignOutOffer(id: signOutOfferSequence, pendingCount: pending, phase: phase)
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
        if readerOwnsLibrary {
            showReaderPage(.album(id))
            return
        }
        source.send(.showAlbum(id))
    }

    public func showArtist(_ id: DulcetProviderItemID) {
        if readerOwnsLibrary {
            showReaderPage(.artist(id))
            return
        }
        source.send(.showArtist(id))
    }

    // MARK: The reader's library

    /// The reader session the Library and Search surfaces read from, when there is one.
    public var librarySession: DulcetLibrarySession? {
        (source as? any DulcetLibrarySessionProviding)?.librarySession
    }

    /// Whether the reader draws Library and Search: an account is saved or connected.
    public var readerOwnsLibrary: Bool {
        guard let session = librarySession else { return false }
        return session.mode != .none
    }

    /// Opens a page from anywhere: from Library it is pushed; from elsewhere -- a search result,
    /// the player -- Library comes forward showing it on top of the section it was left on.
    public func showReaderPage(_ route: DulcetReaderRoute) {
        readerReturn = nil
        readerArrival = route
        if selectedDestination == .library {
            if readerPath.last != route { readerPath.append(route) }
        } else {
            readerPath = [route]
            selectDestination(.library)
        }
    }

    /// Pushes a page on top of the Library stack.
    public func pushReaderPage(_ route: DulcetReaderRoute) {
        guard selectedDestination == .library else {
            showReaderPage(route)
            return
        }
        readerReturn = nil
        readerArrival = route
        readerPath.append(route)
    }

    /// The back button or the edge swipe left the stack at `path`, a prefix of the one it held.
    public func popReader(to path: [DulcetReaderRoute]) {
        guard path.count < readerPath.count,
              Array(readerPath.prefix(path.count)) == path else { return }
        readerReturn = DulcetReaderReturn(page: path.last, opened: readerPath[path.count])
        readerArrival = nil
        readerPath = path
    }

    /// Where Back last landed and what it came back from: the page now on top (nil for the
    /// section at the root) and the route that page had opened. On a remote, focus goes back to
    /// the item that opened the page left, as in the platform's own Music app; the focus engine
    /// alone puts it on the first control of the page, the Library's section bar. Cleared when
    /// that item takes focus, and by any push or section change.
    public private(set) var readerReturn: DulcetReaderReturn?

    /// The item Back returned to holds focus again.
    public func readerReturnFocused() {
        readerReturn = nil
    }

    /// The page just opened, until one of its controls takes focus. On a remote nothing else
    /// puts focus on a page whose content arrives after it is pushed: the focus engine finds no
    /// control on it yet and falls back to the app's section bar, where Back leaves the app.
    /// Cleared when the page's first control takes focus, and by Back or a section change.
    public private(set) var readerArrival: DulcetReaderRoute?

    /// A control of the page just opened holds focus.
    public func readerArrivalFocused() {
        readerArrival = nil
    }

    /// Whether Back has a library page to leave: the Library is showing and a page is pushed.
    public var canGoBackInLibrary: Bool {
        selectedDestination == .library && readerOwnsLibrary && !readerPath.isEmpty
    }

    /// Back from the keyboard or the menu bar: the same one step the back button takes.
    public func goBackInLibrary() {
        guard canGoBackInLibrary else { return }
        popReader(to: Array(readerPath.dropLast()))
    }

    /// The playing track as a favourite: offered only while the reader holds the account the
    /// track came from, with what the person currently sees on its heart.
    public var playingTrackFavourite: (target: DulcetFavouriteTarget, isFavourite: Bool)? {
        guard let session = librarySession,
              let track = snapshot.nowPlaying?.current,
              let favourite = nowPlayingFavourite(for: track) else { return nil }
        return (favourite.target, session.isFavourite(track.id, published: favourite.published))
    }

    /// The heart Now Playing draws for `track`, on every Apple platform: offered only while the
    /// reader holds the account the track came from (§16.20). `published` is what the session last
    /// saw published for it, else the queue's own copy -- which is as old as the queue. The button
    /// goes through the session like every other heart, so pending, held and saved are the same.
    public func nowPlayingFavourite(
        for track: DulcetTrack
    ) -> (target: DulcetFavouriteTarget, published: Bool?)? {
        guard let session = librarySession, session.reader != nil,
              track.id.providerInstanceID == session.account?.providerInstanceID else { return nil }
        return (
            DulcetFavouriteTarget(kind: .track, id: track.id),
            session.knownFavourites[track.id] ?? track.isFavorite
        )
    }

    /// Flips the playing track's heart, from the lock screen, the menu bar or a shortcut.
    @discardableResult
    public func togglePlayingTrackFavourite() -> Bool {
        guard let session = librarySession, let (target, isFavourite) = playingTrackFavourite else {
            return false
        }
        return session.setFavourite(target, favourite: !isFavourite)
    }

    /// A sidebar or TV-bar section: Library, at that section's root.
    public func selectLibrarySection(_ section: DulcetLibrarySection) {
        librarySection = section
        readerPath = []
        readerReturn = nil
        readerArrival = nil
        if selectedDestination != .library {
            selectDestination(.library)
        }
    }

    /// Plays tracks a reader screen resolved.
    public func playTracks(_ intent: DulcetPlaybackQueueIntent) {
        dismissedPlaybackFailure = nil
        source.send(.playTracks(intent))
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
