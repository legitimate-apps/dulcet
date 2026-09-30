import Foundation
import Observation

// The Swift face of the library reader (spec §16.18, the Apple paragraph).
//
// `DulcetLibraryReading` is what the shells need from one account's reader. The app target adapts
// the core's `AppleLibraryReaderClient` to it; tests adapt a fake. Everything here runs on the main
// actor, which is where the facade delivers every publication and completion.
//
// `DulcetLibrarySession` owns the reader for the account the app has, whether or not that account
// is connected in this launch, and is the one place that tells it about reachability and the
// foreground. Screens open `DulcetLibraryWindowModel`s against it while they are visible.

@MainActor
public protocol DulcetLibraryWindowSubscribing: AnyObject {
    func loadMore()
    func loadBefore()
    /// Indexes into the latest publication this subscription delivered.
    func setViewport(first: Int, last: Int)
    func close()
}

@MainActor
public protocol DulcetLibrarySearchSubscribing: AnyObject {
    func updateQuery(_ text: String)
    func close()
}

@MainActor
public protocol DulcetLibraryReaderCancellable: AnyObject {
    func cancel()
}

/// The account a reader reads as. Credential-bearing: never rendered, logged or compared by text.
public struct DulcetLibraryReaderAccount: Sendable, Hashable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let providerInstanceID: String
    public let normalizedServerURL: String
    public let username: String
    public let password: String
    public let allowLocalHTTP: Bool

    public init(
        providerInstanceID: String,
        normalizedServerURL: String,
        username: String,
        password: String,
        allowLocalHTTP: Bool
    ) {
        self.providerInstanceID = providerInstanceID
        self.normalizedServerURL = normalizedServerURL
        self.username = username
        self.password = password
        self.allowLocalHTTP = allowLocalHTTP
    }

    public var description: String { "DulcetLibraryReaderAccount(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [("account", "<redacted>" as Any)], displayStyle: .struct)
    }
}

/// One account's reader.
@MainActor
public protocol DulcetLibraryReading: AnyObject {
    /// The first publication is the cached content if there is any, before any request (CONF-76).
    func subscribeWindow(
        _ query: DulcetLibraryQuery,
        onPublication: @escaping @MainActor (DulcetLibraryWindow) -> Void
    ) -> any DulcetLibraryWindowSubscribing
    func subscribeSearch(
        onPublication: @escaping @MainActor (DulcetReaderSearchPublication) -> Void
    ) -> any DulcetLibrarySearchSubscribing
    /// The change is in the next publication of every open screen showing it, before any request.
    /// False when the reader could not take it.
    func setFavourite(_ target: DulcetFavouriteTarget, favourite: Bool) -> Bool
    /// A rating of 1 to 5, or 0 to remove it; the same outbox as a favourite (§18.3), and its
    /// outcomes arrive on the same subscription. False when the reader could not take it.
    func setRating(_ target: DulcetFavouriteTarget, rating: Int) -> Bool
    func subscribeFavouriteOutcomes(
        _ handler: @escaping @MainActor (DulcetFavouriteOutcome) -> Void
    ) -> any DulcetLibraryReaderCancellable
    @discardableResult
    func pendingChangeCount(
        completion: @escaping @MainActor (DulcetPendingChanges) -> Void
    ) -> any DulcetLibraryReaderCancellable
    @discardableResult
    func connect(completion: @escaping @MainActor (DulcetReaderConnection) -> Void) -> any DulcetLibraryReaderCancellable
    @discardableResult
    func reconnect(completion: @escaping @MainActor (DulcetReaderConnection) -> Void) -> any DulcetLibraryReaderCancellable
    func setOnline(_ reachable: Bool)
    func setForeground(_ foreground: Bool)
    func setNetworkConstrained(_ constrained: Bool)
    /// The completion runs once the reader's own thread has stopped (§14.7 step 6).
    func close(completion: @escaping @MainActor () -> Void)
}

@MainActor
public protocol DulcetLibraryReaderMaking: AnyObject {
    func makeReader(account: DulcetLibraryReaderAccount, foreground: Bool) -> any DulcetLibraryReading
}

/// What the platform reports about the network, forwarded to the reader (§16.14). The app target
/// adapts the system's path monitor; tests drive it by hand.
@MainActor
public protocol DulcetReachabilityMonitoring: AnyObject {
    /// Starts reporting; the handler receives the current state at once and every change after.
    func start(_ handler: @escaping @MainActor (_ reachable: Bool, _ constrained: Bool) -> Void)
    func stop()
}

/// Optional capability of a data source: the library reader session its Library and Search
/// surfaces read from.
@MainActor
public protocol DulcetLibrarySessionProviding: AnyObject {
    var librarySession: DulcetLibrarySession? { get }
}

// MARK: - The session

/// Whether the reader may read the server.
public enum DulcetLibraryReaderMode: Sendable, Hashable {
    /// No account: nothing to read.
    case none
    /// An account is saved but not connected in this launch. The reader paints what this device
    /// has seen and sends nothing: the person has not chosen Reconnect yet (CONF-10b).
    case deviceOnly
    /// Connected: the reader follows reachability and the foreground.
    case connected
}

/// A short statement about a favourite change the person should hear about, and the entity it
/// concerns. `id` increases with each one, so two identical notices are two events.
public struct DulcetLibraryNotice: Sendable, Hashable, Identifiable {
    public let id: Int
    public let message: String
}

/// The reader for the app's account, and everything about it that outlives one screen.
@MainActor
@Observable
public final class DulcetLibrarySession {
    @ObservationIgnored private let factory: (any DulcetLibraryReaderMaking)?
    @ObservationIgnored private let reachability: (any DulcetReachabilityMonitoring)?
    @ObservationIgnored private var outcomeSubscription: (any DulcetLibraryReaderCancellable)?
    @ObservationIgnored private var windows = NSHashTable<DulcetLibraryWindowModel>.weakObjects()
    @ObservationIgnored private var foreground: Bool
    @ObservationIgnored private var lastReachable: Bool?
    @ObservationIgnored private var noticeSequence = 0
    /// The reader to make once the previous one has closed.
    @ObservationIgnored private var awaitingOpen: OpenRequest?
    /// Completions waiting for every reader this session closed to stop.
    @ObservationIgnored private var closesInFlight: [@MainActor () -> Void] = []
    @ObservationIgnored private var closesPending = 0
    /// Playlist questions the last reader's editor left unanswered, for the next one of that
    /// account: a create in doubt deleted here is not in the core's outbox to be asked again.
    @ObservationIgnored private var carriedPlaylistQuestions: (account: DulcetLibraryReaderAccount, questions: [DulcetPlaylistQuestion])?

    public private(set) var reader: (any DulcetLibraryReading)?
    public private(set) var account: DulcetLibraryReaderAccount?
    public private(set) var mode: DulcetLibraryReaderMode = .none
    /// Changes whenever a different reader takes over, so screens drop another account's rows.
    public private(set) var readerGeneration = 0
    /// Why the last reconnect could not read the server, while that keeps the reader offline.
    public private(set) var connectionFailure: DulcetReaderErrorKind?
    /// The server reports no scan stamp: stated once for the account, never per list.
    public private(set) var serverReportsNoEpoch = false
    /// Unsent changes queued as another user, discarded when this account bound the cache.
    public private(set) var discardedChanges: Int64 = 0
    /// Favourites tapped whose send has not ended, keyed by entity: the value tapped.
    public private(set) var pendingFavourites: [DulcetFavouriteKey: Bool] = [:]
    /// Favourites kept unsent (§18.3 `held`), and why.
    public private(set) var heldFavourites: [DulcetFavouriteKey: DulcetReaderErrorKind] = [:]
    /// The favourite state this session last saw published for each entity, the tap overlay
    /// included. Now Playing and the lock screen read it: a queued track's own copy is as old as
    /// the queue.
    public private(set) var knownFavourites: [DulcetProviderItemID: Bool] = [:]
    /// Ratings set whose send has not ended, keyed by entity: the value set (0 removes it).
    public private(set) var pendingRatings: [DulcetFavouriteKey: Int] = [:]
    /// Ratings kept unsent (§18.3 `held`), and why.
    public private(set) var heldRatings: [DulcetFavouriteKey: DulcetReaderErrorKind] = [:]
    /// The rating this session last saw published for each entity, as ``knownFavourites`` is for
    /// hearts: Now Playing reads it, because a queued track carries no rating of its own.
    public private(set) var knownRatings: [DulcetProviderItemID: Int] = [:]
    /// The latest statement about a change or an unplayable row, for the shell to show briefly.
    public private(set) var notice: DulcetLibraryNotice?
    /// Playlist editing for the current reader, when it can edit playlists (§18.6).
    public private(set) var playlists: DulcetPlaylistEditor?
    /// Receives the playable tracks of every screen publication, for queue restoration.
    @ObservationIgnored public var onTracksSeen: (@MainActor ([DulcetTrack]) -> Void)?

    public init(
        factory: (any DulcetLibraryReaderMaking)?,
        reachability: (any DulcetReachabilityMonitoring)? = nil,
        foreground: Bool = true
    ) {
        self.factory = factory
        self.reachability = reachability
        self.foreground = foreground
    }

    /// Whether this app reads its library through a reader at all.
    public var isAvailable: Bool { factory != nil }

    /// Whether the reader may read the server now, as far as this session knows: connected, and
    /// not kept offline by a failed reconnect or by the network.
    public var isOnline: Bool {
        mode == .connected && connectionFailure == nil && lastReachable != false
    }

    // MARK: Lifecycle

    /// Opens (or keeps) the reader for `account`. `deviceOnly` paints what the device has seen and
    /// sends nothing; `connected` lets it read. A different account closes the old reader first.
    public func open(account: DulcetLibraryReaderAccount, mode requested: DulcetLibraryReaderMode) {
        guard let factory, requested != .none else { return }
        if let reader, self.account == account {
            if requested == .connected, mode != .connected {
                mode = .connected
                connectionFailure = nil
                startReachability()
                // Reachable while offline requests the reconnect: flush, epoch, then the screens.
                reader.setOnline(true)
                reconnect()
            }
            return
        }
        if let reader {
            // Two readers over one namespace would write the same cache from two threads, so
            // the new one is made only once the old one's thread has stopped. Screens keep their
            // last rows meanwhile; nothing can be read or changed until it opens.
            let target = OpenRequest(account: account, mode: requested)
            awaitingOpen = target
            detachReader(reader) { [weak self] in
                guard let self, self.awaitingOpen == target else { return }
                self.awaitingOpen = nil
                self.makeAndOpen(account: target.account, mode: target.mode, factory: factory)
            }
            // The session already speaks for the new account and mode, so the shells keep the
            // reader's surfaces through the swap rather than falling back to another path.
            self.reader = nil
            self.account = account
            mode = requested
            return
        }
        if awaitingOpen != nil {
            // A close is still running: the newest request is the one made when it ends.
            awaitingOpen = OpenRequest(account: account, mode: requested)
            self.account = account
            mode = requested
            return
        }
        makeAndOpen(account: account, mode: requested, factory: factory)
    }

    private struct OpenRequest: Equatable {
        let account: DulcetLibraryReaderAccount
        let mode: DulcetLibraryReaderMode
    }

    private func makeAndOpen(
        account: DulcetLibraryReaderAccount,
        mode requested: DulcetLibraryReaderMode,
        factory: any DulcetLibraryReaderMaking
    ) {
        let made = factory.makeReader(account: account, foreground: foreground)
        if requested == .deviceOnly {
            // Before any screen subscribes: the reader starts connected, and a window opened
            // before this call could read the server the person has not chosen to contact.
            made.setOnline(false)
        }
        reader = made
        self.account = account
        mode = requested
        connectionFailure = nil
        serverReportsNoEpoch = false
        pendingFavourites = [:]
        heldFavourites = [:]
        knownFavourites = [:]
        pendingRatings = [:]
        heldRatings = [:]
        knownRatings = [:]
        readerGeneration += 1
        outcomeSubscription = made.subscribeFavouriteOutcomes { [weak self] outcome in
            self?.receive(outcome)
        }
        let carried = carriedPlaylistQuestions?.account == account ? carriedPlaylistQuestions?.questions ?? [] : []
        carriedPlaylistQuestions = nil
        playlists = (made as? any DulcetPlaylistEditing).map { DulcetPlaylistEditor(editing: $0, session: self, carried: carried) }
        if requested == .connected {
            startReachability()
            made.connect { [weak self] connection in
                self?.receive(connection)
            }
        }
        for window in windows.allObjects {
            window.readerChanged()
        }
    }

    /// Stops reading the server without closing the reader: the account went back to saved.
    public func disconnect() {
        guard mode == .connected, let reader else { return }
        stopReachability()
        mode = .deviceOnly
        reader.setOnline(false)
    }

    /// Closes the reader. `completion` runs once its thread has stopped, which is when the
    /// account's rows may be deleted (§14.7 step 6).
    public func close(completion: @escaping @MainActor () -> Void = {}) {
        // A reader waiting for the previous one to close is never made.
        awaitingOpen = nil
        guard let reader else {
            account = nil
            mode = .none
            if closesInFlight.isEmpty {
                completion()
            } else {
                // The previous reader is still stopping; the rows may go only after it has.
                closesInFlight.append(completion)
            }
            return
        }
        detachReader(reader, completion: completion)
        self.reader = nil
        account = nil
        mode = .none
        connectionFailure = nil
        serverReportsNoEpoch = false
        pendingFavourites = [:]
        heldFavourites = [:]
        knownFavourites = [:]
        pendingRatings = [:]
        heldRatings = [:]
        knownRatings = [:]
        readerGeneration += 1
        for window in windows.allObjects {
            window.readerChanged()
        }
    }

    private func detachReader(_ reader: any DulcetLibraryReading, completion: @escaping @MainActor () -> Void) {
        stopReachability()
        outcomeSubscription?.cancel()
        outcomeSubscription = nil
        if let account, let playlists, !playlists.unansweredDeletionQuestions.isEmpty {
            carriedPlaylistQuestions = (account, playlists.unansweredDeletionQuestions)
        }
        playlists?.close()
        playlists = nil
        for window in windows.allObjects {
            window.dropSubscription()
        }
        closesInFlight.append(completion)
        closesPending += 1
        reader.close { [weak self] in
            guard let self else {
                completion()
                return
            }
            self.closesPending -= 1
            guard self.closesPending == 0 else { return }
            let waiting = self.closesInFlight
            self.closesInFlight = []
            for waiter in waiting { waiter() }
        }
    }

    // MARK: Reachability and foreground

    /// The app's foreground state; call it on every change (§16.14).
    public func setForeground(_ foreground: Bool) {
        guard foreground != self.foreground else { return }
        self.foreground = foreground
        reader?.setForeground(foreground)
        // A return to the foreground reconnects -- only for an account the person connected.
        if foreground, mode == .connected {
            reconnect()
        }
    }

    /// "Try again" on any screen: the reconnect, which re-reads every visible screen and makes any
    /// extend they owe -- never a screen's own refresh (§16.14). For an account not connected in
    /// this launch it does nothing: that "Try again" is the account's Reconnect.
    public func reconnect() {
        guard mode == .connected, let reader else { return }
        reader.reconnect { [weak self] connection in
            self?.receive(connection)
        }
    }

    private func startReachability() {
        guard let reachability else { return }
        reachability.start { [weak self] reachable, constrained in
            guard let self, self.mode == .connected, let reader = self.reader else { return }
            reader.setNetworkConstrained(constrained)
            guard reachable != self.lastReachable else { return }
            self.lastReachable = reachable
            reader.setOnline(reachable)
        }
    }

    private func stopReachability() {
        reachability?.stop()
        lastReachable = nil
    }

    private func receive(_ connection: DulcetReaderConnection) {
        if connection.epochKnown {
            connectionFailure = nil
            serverReportsNoEpoch = connection.serverReportsNoEpoch
        } else if let error = connection.error, error != .cancelled, error != .closed {
            connectionFailure = error
        }
        if connection.discardedPendingChanges > 0 {
            discardedChanges = connection.discardedPendingChanges
            post(DulcetStrings.readerDiscardedChanges(Int(clamping: connection.discardedPendingChanges)))
        }
    }

    // MARK: Windows

    func register(_ window: DulcetLibraryWindowModel) {
        windows.add(window)
    }

    func unregister(_ window: DulcetLibraryWindowModel) {
        windows.remove(window)
    }

    /// Records the favourite state of every entity a screen was shown, so surfaces that hold an
    /// older copy of a track -- Now Playing, the lock screen -- say what the screens say.
    func observe(_ items: [DulcetReaderItem]) {
        var tracks: [DulcetTrack] = []
        for item in items where item.kind == .track || item.kind == .album || item.kind == .artist {
            if item.kind == .track, let track = item.playableTrack { tracks.append(track) }
            if let rating = item.rating, knownRatings[item.id] != rating { knownRatings[item.id] = rating }
            guard let favourite = item.isFavourite, knownFavourites[item.id] != favourite else { continue }
            knownFavourites[item.id] = favourite
        }
        if !tracks.isEmpty { onTracksSeen?(tracks) }
    }

    func observe(_ rows: [DulcetReaderSearchRow]) {
        for row in rows {
            if let rating = row.rating, knownRatings[row.id] != rating { knownRatings[row.id] = rating }
            guard let favourite = row.isFavourite, knownFavourites[row.id] != favourite else { continue }
            knownFavourites[row.id] = favourite
        }
    }

    // MARK: Search

    public func subscribeSearch(
        onPublication: @escaping @MainActor (DulcetReaderSearchPublication) -> Void
    ) -> (any DulcetLibrarySearchSubscribing)? {
        reader?.subscribeSearch { [weak self] publication in
            self?.observe(publication.rows)
            onPublication(publication)
        }
    }

    // MARK: Favourites

    /// The favourite state to draw for an entity: the tap, while it is pending, then what the
    /// screens last published. `published` is the value in the row being drawn.
    public func isFavourite(_ id: DulcetProviderItemID, published: Bool?) -> Bool {
        if let kind = pendingKind(for: id), let pending = pendingFavourites[kind] { return pending }
        return published ?? knownFavourites[id] ?? false
    }

    private func pendingKind(for id: DulcetProviderItemID) -> DulcetFavouriteKey? {
        pendingFavourites.keys.first { $0.rawID == id.rawID && $0.providerInstanceID == id.providerInstanceID }
    }

    /// Whether a change for this entity is waiting to be sent, or held.
    public func favouriteState(_ target: DulcetFavouriteTarget) -> DulcetFavouriteChangeState {
        let key = DulcetFavouriteKey(target)
        if let error = heldFavourites[key] { return .held(error) }
        if pendingFavourites[key] != nil { return .pending }
        return .settled
    }

    /// Makes the entity a favourite, or not. Shown at once; the outcome arrives later.
    @discardableResult
    public func setFavourite(_ target: DulcetFavouriteTarget, favourite: Bool) -> Bool {
        guard let reader, account?.providerInstanceID == target.id.providerInstanceID else {
            post(DulcetStrings.readerChangeNotRecorded)
            return false
        }
        let key = DulcetFavouriteKey(target)
        guard reader.setFavourite(target, favourite: favourite) else {
            post(DulcetStrings.readerChangeNotRecorded)
            return false
        }
        pendingFavourites[key] = favourite
        heldFavourites[key] = nil
        knownFavourites[target.id] = favourite
        return true
    }

    /// Flips what the person sees.
    @discardableResult
    public func toggleFavourite(_ target: DulcetFavouriteTarget, published: Bool?) -> Bool {
        setFavourite(target, favourite: !isFavourite(target.id, published: published))
    }

    // MARK: Ratings

    /// The rating to draw for an entity, 0 for none: the value set, while it is pending, then what
    /// the screens last published. `published` is the value in the row being drawn.
    public func rating(_ id: DulcetProviderItemID, published: Int?) -> Int {
        if let key = pendingRatings.keys.first(where: {
            $0.rawID == id.rawID && $0.providerInstanceID == id.providerInstanceID
        }), let pending = pendingRatings[key] {
            return pending
        }
        return published ?? knownRatings[id] ?? 0
    }

    /// Whether a rating for this entity is waiting to be sent, or held.
    public func ratingState(_ target: DulcetFavouriteTarget) -> DulcetFavouriteChangeState {
        let key = DulcetFavouriteKey(target)
        if let error = heldRatings[key] { return .held(error) }
        if pendingRatings[key] != nil { return .pending }
        return .settled
    }

    /// Rates the entity 1 to 5, or removes its rating with 0. Shown at once; the outcome arrives
    /// later. Any other value is refused here and nothing is recorded.
    @discardableResult
    public func setRating(_ target: DulcetFavouriteTarget, rating: Int) -> Bool {
        guard DulcetRating.range.contains(rating) else { return false }
        guard let reader, account?.providerInstanceID == target.id.providerInstanceID else {
            post(DulcetStrings.readerChangeNotRecorded)
            return false
        }
        let key = DulcetFavouriteKey(target)
        guard reader.setRating(target, rating: rating) else {
            post(DulcetStrings.readerChangeNotRecorded)
            return false
        }
        pendingRatings[key] = rating
        heldRatings[key] = nil
        knownRatings[target.id] = rating
        return true
    }

    private func receiveRating(_ outcome: DulcetFavouriteOutcome, key: DulcetFavouriteKey, id: DulcetProviderItemID) {
        switch outcome.kind {
        case .saved:
            pendingRatings[key] = nil
            heldRatings[key] = nil
        case let .notSaved(error):
            pendingRatings[key] = nil
            heldRatings[key] = nil
            // The overlay is gone and the server's value shows; the screens republish it.
            knownRatings[id] = nil
            post(DulcetStrings.readerChangeNotSaved(DulcetStrings.readerErrorPhrase(error)))
        case let .held(error):
            heldRatings[key] = error
            post(error == .serverBusy
                ? DulcetStrings.readerChangeHeldBusy
                : DulcetStrings.readerChangeHeldRefused(DulcetStrings.readerHeldPhrase(error)))
        case .superseded:
            pendingRatings[key] = nil
            heldRatings[key] = nil
            knownRatings[id] = nil
            post(DulcetStrings.readerChangeSuperseded)
        case .notRecorded:
            pendingRatings[key] = nil
            heldRatings[key] = nil
            knownRatings[id] = nil
            post(DulcetStrings.readerChangeNotRecorded)
        }
    }

    private func receive(_ outcome: DulcetFavouriteOutcome) {
        if outcome.field == .rating, let targetKind = outcome.targetKind,
           let providerInstanceID = account?.providerInstanceID {
            receiveRating(
                outcome,
                key: DulcetFavouriteKey(kind: targetKind, providerInstanceID: providerInstanceID, rawID: outcome.targetRawID),
                id: DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: outcome.targetRawID)
            )
            return
        }
        guard outcome.field == .favourite, let targetKind = outcome.targetKind,
              let providerInstanceID = account?.providerInstanceID else { return }
        let key = DulcetFavouriteKey(
            kind: targetKind,
            providerInstanceID: providerInstanceID,
            rawID: outcome.targetRawID
        )
        let id = DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: outcome.targetRawID)
        switch outcome.kind {
        case .saved:
            pendingFavourites[key] = nil
            heldFavourites[key] = nil
        case let .notSaved(error):
            pendingFavourites[key] = nil
            heldFavourites[key] = nil
            // The overlay is gone and the server's value shows; the screens republish it.
            knownFavourites[id] = nil
            post(DulcetStrings.readerChangeNotSaved(DulcetStrings.readerErrorPhrase(error)))
        case let .held(error):
            heldFavourites[key] = error
            post(error == .serverBusy
                ? DulcetStrings.readerChangeHeldBusy
                : DulcetStrings.readerChangeHeldRefused(DulcetStrings.readerHeldPhrase(error)))
        case .superseded:
            pendingFavourites[key] = nil
            heldFavourites[key] = nil
            knownFavourites[id] = nil
            post(DulcetStrings.readerChangeSuperseded)
        case .notRecorded:
            pendingFavourites[key] = nil
            heldFavourites[key] = nil
            knownFavourites[id] = nil
            post(DulcetStrings.readerChangeNotRecorded)
        }
    }

    // MARK: Sign-out

    /// The changes that have not reached the server, for the sign-out offer (§14.7 step 2).
    public func pendingChangeCount(completion: @escaping @MainActor (DulcetPendingChanges) -> Void) {
        guard let reader else {
            completion(DulcetPendingChanges(count: 0))
            return
        }
        reader.pendingChangeCount(completion: completion)
    }

    /// Sends what can be sent before signing out: a reconnect flushes the outboxes first. The
    /// completion says whether the server was reached.
    public func submitPendingChanges(completion: @escaping @MainActor (Bool) -> Void) {
        guard mode == .connected, let reader else {
            completion(false)
            return
        }
        reader.reconnect { [weak self] connection in
            self?.receive(connection)
            completion(connection.epochKnown)
        }
    }

    // MARK: Notices

    /// Says something about a change or a row, briefly (§16.14: tapping an unavailable row says
    /// something rather than doing nothing).
    public func post(_ message: String) {
        noticeSequence += 1
        notice = DulcetLibraryNotice(id: noticeSequence, message: message)
    }

    public func dismissNotice(_ id: Int) {
        guard notice?.id == id else { return }
        notice = nil
    }

    /// Opens a transient window to read the tracks of an album or playlist -- a tile's Play, a
    /// context menu's Play Next -- and hands them over once its track list is known. Cached
    /// tracks answer at once; nothing is played from a list still loading.
    public func resolveTracks(
        of query: DulcetLibraryQuery,
        completion: @escaping @MainActor ([DulcetTrack], DulcetLibraryWindow) -> Void
    ) {
        guard let reader else { return }
        var subscription: (any DulcetLibraryWindowSubscribing)?
        var finished = false
        subscription = reader.subscribeWindow(query) { [weak self] window in
            guard !finished else { return }
            self?.observe(window.items)
            let settled: Bool = switch window.freshness {
            case .loading: false
            case .unavailable: true
            case .live, .cached: window.itemsState != .loading
            }
            guard settled else { return }
            finished = true
            subscription?.close()
            subscription = nil
            completion(window.playableTracks, window)
        }
        if finished {
            subscription?.close()
        }
    }
}

public enum DulcetFavouriteChangeState: Sendable, Hashable {
    case settled
    /// Tapped, not yet acknowledged.
    case pending
    /// Kept unsent, and why (§18.3).
    case held(DulcetReaderErrorKind)
}

/// One favourite target, keyed by kind and id: an outcome names exactly these (§28 R3 item 34).
public struct DulcetFavouriteKey: Sendable, Hashable {
    public let kind: DulcetFavouriteTargetKind
    public let providerInstanceID: String
    public let rawID: String

    public init(kind: DulcetFavouriteTargetKind, providerInstanceID: String, rawID: String) {
        self.kind = kind
        self.providerInstanceID = providerInstanceID
        self.rawID = rawID
    }

    public init(_ target: DulcetFavouriteTarget) {
        self.init(kind: target.kind, providerInstanceID: target.id.providerInstanceID, rawID: target.id.rawID)
    }
}

// MARK: - One screen

/// One visible screen's window. A view owns it, opens it on appear and closes it on disappear; it
/// keeps its last publication across a close, so a screen shown again paints at once and is then
/// brought up to date by the cached-first open (CONF-76).
@MainActor
@Observable
public final class DulcetLibraryWindowModel {
    public let query: DulcetLibraryQuery
    public private(set) var window: DulcetLibraryWindow?
    /// Increases with each publication that asks the list to move so its first visible row stays
    /// first (§16.12's rebase).
    public private(set) var anchorRequest: (id: DulcetProviderItemID, sequence: Int)?

    @ObservationIgnored private weak var session: DulcetLibrarySession?
    @ObservationIgnored private var subscription: (any DulcetLibraryWindowSubscribing)?
    @ObservationIgnored private var subscribedGeneration: Int?
    @ObservationIgnored private var visible = Set<Int>()
    @ObservationIgnored private var viewportScheduled = false
    @ObservationIgnored private var lastViewport: (Int, Int)?
    @ObservationIgnored private var requestedMoreAtCount: Int?
    @ObservationIgnored private var requestedBeforeAtOffset: Int?

    public init(query: DulcetLibraryQuery) {
        self.query = query
    }

    /// Opens the window on `session`'s reader, if it is not already open there.
    public func open(in session: DulcetLibrarySession) {
        if self.session !== session {
            self.session?.unregister(self)
            self.session = session
            session.register(self)
        }
        subscribe()
    }

    /// Closes the subscription and keeps the last publication.
    public func close() {
        dropSubscription()
        session?.unregister(self)
    }

    func readerChanged() {
        dropSubscription()
        window = nil
        anchorRequest = nil
        subscribe()
    }

    func dropSubscription() {
        subscription?.close()
        subscription = nil
        subscribedGeneration = nil
        visible = []
        lastViewport = nil
        requestedMoreAtCount = nil
        requestedBeforeAtOffset = nil
    }

    private func subscribe() {
        guard let session, let reader = session.reader else { return }
        guard subscription == nil || subscribedGeneration != session.readerGeneration else { return }
        subscription?.close()
        subscribedGeneration = session.readerGeneration
        subscription = reader.subscribeWindow(query) { [weak self] window in
            self?.receive(window)
        }
    }

    private func receive(_ window: DulcetLibraryWindow) {
        session?.observe(window.items)
        if let header = window.header { session?.observe([header]) }
        if let anchorID = window.anchorID,
           let anchored = window.items.first(where: { $0.id.rawID == anchorID }) {
            anchorRequest = (anchored.id, window.sequence)
        } else if let index = window.anchorIndex, window.items.indices.contains(index) {
            anchorRequest = (window.items[index].id, window.sequence)
        }
        // A publication that changes the list's length or start re-arms the extend triggers.
        if window.items.count != self.window?.items.count { requestedMoreAtCount = nil }
        if window.leadingOffset != self.window?.leadingOffset { requestedBeforeAtOffset = nil }
        self.window = window
        scheduleViewport()
    }

    // MARK: Scrolling

    /// A row came on screen.
    public func rowAppeared(_ index: Int) {
        visible.insert(index)
        scheduleViewport()
    }

    public func rowDisappeared(_ index: Int) {
        visible.remove(index)
        scheduleViewport()
    }

    private func scheduleViewport() {
        guard !viewportScheduled else { return }
        viewportScheduled = true
        Task { @MainActor [weak self] in
            self?.viewportScheduled = false
            self?.reportViewport()
        }
    }

    private func reportViewport() {
        guard let subscription, let window, let first = visible.min(), let last = visible.max() else { return }
        if lastViewport.map({ $0 != (first, last) }) ?? true {
            lastViewport = (first, last)
            subscription.setViewport(first: first, last: last)
        }
        // The window never loads more than a page beyond the viewport; the reader enforces it, and
        // asking again for the same end before the list grows would only be refused.
        let nearEnd = last >= window.items.count - 8
        if nearEnd, window.coverage == .open, requestedMoreAtCount != window.items.count {
            requestedMoreAtCount = window.items.count
            subscription.loadMore()
        }
        if first <= 4, window.leadingOffset > 0, requestedBeforeAtOffset != window.leadingOffset {
            requestedBeforeAtOffset = window.leadingOffset
            subscription.loadBefore()
        }
    }
}

// MARK: - Search

/// The search screen's subscription (§16.15). It answers from this device from the first
/// character, and from the server alongside when the reader can reach it.
@MainActor
@Observable
public final class DulcetReaderSearchModel {
    public private(set) var publication: DulcetReaderSearchPublication?
    public private(set) var query = ""

    @ObservationIgnored private weak var session: DulcetLibrarySession?
    @ObservationIgnored private var subscription: (any DulcetLibrarySearchSubscribing)?
    @ObservationIgnored private var subscribedGeneration: Int?

    public init() {}

    public func open(in session: DulcetLibrarySession, query: String) {
        self.session = session
        self.query = query
        if subscription == nil || subscribedGeneration != session.readerGeneration {
            subscription?.close()
            publication = nil
            subscribedGeneration = session.readerGeneration
            subscription = session.subscribeSearch { [weak self] publication in
                self?.publication = publication
            }
        }
        subscription?.updateQuery(query)
    }

    public func updateQuery(_ query: String) {
        guard query != self.query else { return }
        self.query = query
        subscription?.updateQuery(query)
    }

    public func close() {
        subscription?.close()
        subscription = nil
        subscribedGeneration = nil
    }

    /// The publication for the text in the field: an older query's rows are not shown under a
    /// newer one.
    public var current: DulcetReaderSearchPublication? {
        guard let publication, publication.query == query else { return nil }
        return publication
    }
}
