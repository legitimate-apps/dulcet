import Foundation
import MediaPlayer
import Testing
@testable import DulcetKit

// The Swift half of the library reader (spec §16.18): the value copies of the facade's
// publications, the words for them, the session that owns the account's reader, and the data
// source and store in reader mode. The reader itself is a recording fake; the core behind the
// real one is covered by the core's own suites.

// MARK: - Fakes

@MainActor
private final class RecordingReader: DulcetLibraryReading {
    let account: DulcetLibraryReaderAccount
    private(set) var events: [String] = []
    private(set) var windows: [RecordingWindow] = []
    private(set) var searches: [RecordingSearch] = []
    private(set) var favourites: [(DulcetFavouriteTarget, Bool)] = []
    var acceptsFavourites = true
    var pending: Int64? = 0
    var outcomeHandler: (@MainActor (DulcetFavouriteOutcome) -> Void)?
    var connectCompletions: [@MainActor (DulcetReaderConnection) -> Void] = []
    var closeCompletion: (@MainActor () -> Void)?

    init(account: DulcetLibraryReaderAccount) {
        self.account = account
    }

    func subscribeWindow(
        _ query: DulcetLibraryQuery,
        onPublication: @escaping @MainActor (DulcetLibraryWindow) -> Void
    ) -> any DulcetLibraryWindowSubscribing {
        events.append("subscribeWindow")
        let window = RecordingWindow(query: query, publish: onPublication)
        windows.append(window)
        return window
    }

    func subscribeSearch(
        onPublication: @escaping @MainActor (DulcetReaderSearchPublication) -> Void
    ) -> any DulcetLibrarySearchSubscribing {
        events.append("subscribeSearch")
        let search = RecordingSearch(publish: onPublication)
        searches.append(search)
        return search
    }

    func setFavourite(_ target: DulcetFavouriteTarget, favourite: Bool) -> Bool {
        events.append("setFavourite")
        favourites.append((target, favourite))
        return acceptsFavourites
    }

    func subscribeFavouriteOutcomes(
        _ handler: @escaping @MainActor (DulcetFavouriteOutcome) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        outcomeHandler = handler
        return RecordingCancellable()
    }

    func pendingChangeCount(
        completion: @escaping @MainActor (DulcetPendingChanges) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        events.append("pendingChangeCount")
        completion(DulcetPendingChanges(count: pending))
        return RecordingCancellable()
    }

    func connect(completion: @escaping @MainActor (DulcetReaderConnection) -> Void) -> any DulcetLibraryReaderCancellable {
        events.append("connect")
        connectCompletions.append(completion)
        return RecordingCancellable()
    }

    func reconnect(completion: @escaping @MainActor (DulcetReaderConnection) -> Void) -> any DulcetLibraryReaderCancellable {
        events.append("reconnect")
        connectCompletions.append(completion)
        return RecordingCancellable()
    }

    func setOnline(_ reachable: Bool) { events.append("setOnline(\(reachable))") }
    func setForeground(_ foreground: Bool) { events.append("setForeground(\(foreground))") }
    func setNetworkConstrained(_ constrained: Bool) { events.append("setNetworkConstrained(\(constrained))") }

    func close(completion: @escaping @MainActor () -> Void) {
        events.append("close")
        closeCompletion = completion
    }

    /// The reader's thread stopped.
    func finishClose() {
        closeCompletion?()
        closeCompletion = nil
    }

    func answerConnections(_ connection: DulcetReaderConnection) {
        let waiting = connectCompletions
        connectCompletions = []
        waiting.forEach { $0(connection) }
    }
}

@MainActor
private final class RecordingWindow: DulcetLibraryWindowSubscribing {
    let query: DulcetLibraryQuery
    let publish: @MainActor (DulcetLibraryWindow) -> Void
    private(set) var events: [String] = []

    init(query: DulcetLibraryQuery, publish: @escaping @MainActor (DulcetLibraryWindow) -> Void) {
        self.query = query
        self.publish = publish
    }

    func loadMore() { events.append("loadMore") }
    func loadBefore() { events.append("loadBefore") }
    func setViewport(first: Int, last: Int) { events.append("viewport(\(first),\(last))") }
    func close() { events.append("close") }
}

@MainActor
private final class RecordingSearch: DulcetLibrarySearchSubscribing {
    let publish: @MainActor (DulcetReaderSearchPublication) -> Void
    private(set) var queries: [String] = []
    private(set) var closed = false

    init(publish: @escaping @MainActor (DulcetReaderSearchPublication) -> Void) {
        self.publish = publish
    }

    func updateQuery(_ text: String) { queries.append(text) }
    func close() { closed = true }
}

@MainActor
private final class RecordingCancellable: DulcetLibraryReaderCancellable {
    func cancel() {}
}

@MainActor
private final class RecordingReaderFactory: DulcetLibraryReaderMaking {
    private(set) var made: [RecordingReader] = []

    func makeReader(account: DulcetLibraryReaderAccount, foreground: Bool) -> any DulcetLibraryReading {
        let reader = RecordingReader(account: account)
        made.append(reader)
        return reader
    }
}

@MainActor
private final class ManualReachability: DulcetReachabilityMonitoring {
    private(set) var started = 0
    private var handler: (@MainActor (Bool, Bool) -> Void)?

    func start(_ handler: @escaping @MainActor (Bool, Bool) -> Void) {
        started += 1
        self.handler = handler
    }

    func stop() { handler = nil }

    func report(reachable: Bool, constrained: Bool = false) {
        handler?(reachable, constrained)
    }
}

@MainActor
private final class ReaderTestConnector: DulcetAccountConnecting {
    private(set) var requests: [DulcetAccountConnectRequest] = []
    private var completion: (@MainActor (DulcetAccountConnectOutcome) -> Void)?

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        requests.append(request)
        self.completion = completion
        return ReaderTestOperation()
    }

    func complete(_ outcome: DulcetAccountConnectOutcome) {
        completion?(outcome)
        completion = nil
    }
}

@MainActor
private final class ReaderTestOperation: DulcetAccountConnectOperation {
    func cancel() {}
}

private final class ReaderTestCredentials: DulcetProviderInstanceCredentialStoring {
    private(set) var persisted: DulcetAccountConnectRequest?
    private(set) var providerInstanceID: String?

    init(persisted: DulcetAccountConnectRequest?, providerInstanceID: String?) {
        self.persisted = persisted
        self.providerInstanceID = providerInstanceID
    }

    func load() throws -> DulcetAccountConnectRequest? { persisted }
    func save(_ request: DulcetAccountConnectRequest) throws { persisted = request }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        persisted = request
        self.providerInstanceID = providerInstanceID
    }
    func delete() throws { persisted = nil }
}

/// Counts every library read the legacy path would make: in reader mode there must be none.
@MainActor
private final class ForbiddenLibraryBrowser: DulcetLibraryBrowsing {
    private(set) var browses = 0

    func browse(
        _ request: DulcetLibraryBrowseRequest,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        browses += 1
        return ForbiddenBrowseOperation()
    }
}

@MainActor
private final class ForbiddenBrowseOperation: DulcetLibraryBrowseOperation {
    func cancel() {}
}

private let readerAccount = DulcetLibraryReaderAccount(
    providerInstanceID: "provider-reader",
    normalizedServerURL: "https://music.example.invalid",
    username: "listener",
    password: "fixture-password",
    allowLocalHTTP: false
)

private func item(
    _ kind: String = "track",
    _ rawID: String,
    title: String = "Title",
    favourite: Bool? = nil,
    playability: String? = "streamable",
    duration: Int64? = 180_000
) -> DulcetReaderItem {
    DulcetReaderItem(
        kind: kind,
        providerInstanceID: "provider-reader",
        rawID: rawID,
        title: title,
        artistName: "Artist",
        artistRawID: "artist-1",
        albumTitle: "Album",
        albumRawID: "album-1",
        year: 2001,
        genre: nil,
        durationMilliseconds: duration,
        songCount: nil,
        albumCount: nil,
        discNumber: 1,
        trackNumber: 1,
        sourceContainer: "Mp3",
        artworkKey: nil,
        owner: nil,
        favourite: favourite,
        rating: nil,
        playCount: nil,
        playability: playability,
        detailComplete: true,
        metadataMissing: false
    )!
}

private func window(
    _ items: [DulcetReaderItem],
    freshness: DulcetReaderFreshness = .live,
    coverage: String? = "complete",
    sequence: Int = 1,
    order: String = "server",
    total: Int? = nil
) -> DulcetLibraryWindow {
    DulcetLibraryWindow(
        sequence: sequence,
        freshness: freshness,
        coverage: coverage,
        total: total,
        leadingOffset: 0,
        header: nil,
        items: items,
        itemsState: "present",
        order: order,
        anchorRawID: nil,
        anchorIndex: nil
    )
}

// MARK: - Values

@Test
func freshnessCopiesEveryCachedReasonIncludingOwed() {
    let owed = DulcetReaderFreshness(kind: "cached", reason: "owed", errorKind: nil, asOfEpochMillis: 1_000)
    #expect(owed == .cached(.owed, asOf: Date(timeIntervalSince1970: 1)))
    // A screen owing a load more offers the reconnect that makes it (round 10).
    #expect(owed.offersRetry)
    #expect(!owed.isLive)
    #expect(DulcetReaderFreshness(kind: "cached", reason: "revalidating", errorKind: nil, asOfEpochMillis: nil)
        == .cached(.revalidating, asOf: nil))
    #expect(DulcetReaderFreshness(kind: "cached", reason: "failed", errorKind: "serverBusy", asOfEpochMillis: nil)
        == .cached(.failed(.serverBusy), asOf: nil))
    #expect(DulcetReaderFreshness(kind: "unavailable", reason: "notCachedOffline", errorKind: nil, asOfEpochMillis: nil)
        == .unavailable(.notCachedOffline))
    // A word the core has not taught this build is an internal failure, never "live".
    #expect(DulcetReaderErrorKind(coreName: "somethingNew") == .internalFailure)
    #expect(!DulcetReaderFreshness(kind: "cached", reason: "stale", errorKind: nil, asOfEpochMillis: nil).offersRetry)
}

@Test @MainActor
func owedAndOfflineContentSaysHowOldItIsAndWhy() {
    let now = Date(timeIntervalSince1970: 10_000)
    let owedLine = DulcetReaderCopy.freshnessLine(.cached(.owed, asOf: now.addingTimeInterval(-10)), now: now)
    #expect(owedLine?.contains(DulcetStrings.readerReasonOwed) == true)
    #expect(owedLine?.contains(DulcetStrings.readerJustNow) == true)
    #expect(DulcetReaderCopy.freshnessLine(.live) == nil)
    #expect(DulcetReaderCopy.freshnessLine(.loading) == nil)
    let unknownAge = DulcetReaderCopy.freshnessLine(.cached(.offline, asOf: nil), now: now)
    #expect(unknownAge?.contains(DulcetStrings.readerSeenUnknownAge) == true)
    #expect(DulcetReaderCopy.unavailableLine(.notCachedOffline, subject: .album) == DulcetStrings.readerUnavailableAlbum)
}

@Test @MainActor
func anOpenListIsStatedOnlyWhileItCannotBeExtended() {
    let items = [item("album", "a1"), item("album", "a2")]
    #expect(DulcetReaderCopy.coverageLine(window(items, coverage: "open")) == nil,
            "an open list that can extend says nothing")
    let offline = DulcetReaderCopy.coverageLine(window(
        items, freshness: .cached(.offline, asOf: nil), coverage: "open", total: 40))
    #expect(offline?.contains("40") == true)
    #expect(DulcetReaderCopy.coverageLine(window(items, coverage: "unverifiedScanning"))
        == DulcetStrings.readerCoverageScanning)
    #expect(DulcetReaderCopy.coverageLine(window(items, coverage: "unverifiedNoEpoch")) == nil,
            "no-epoch is stated once per account, never per list")
    #expect(DulcetReaderCopy.orderLine(window(items, order: "localView")) == DulcetStrings.readerAvailableOffline)
}

@Test
func queriesNameTheFacadeRequestKinds() {
    #expect(DulcetLibraryQuery.albums(.newest).request.kind == "albumList")
    #expect(DulcetLibraryQuery.albums(.newest).request.listType == "newest")
    #expect(DulcetLibraryQuery.favourites.request.kind == "starred")
    #expect(DulcetLibraryQuery.homeRow(.favourites).request.listType == "favourites")
    #expect(DulcetLibraryQuery.homeRow(.recentlyPlayed).request.listType == "recent")
    #expect(DulcetLibraryQuery.songsByGenre("Jazz").request.genre == "Jazz")
    #expect(DulcetLibraryQuery.album(rawID: "al-1").request.rawID == "al-1")
}

@Test
func anUnavailableOfflineTrackIsNeverHandedToThePlayer() {
    let offline = item("track", "t1", playability: "unavailableOffline")
    #expect(offline.isUnavailableOffline)
    #expect(offline.playableTrack?.availability == .metadataOnly)
    #expect(window([offline, item("track", "t2")]).playableTracks.map(\.id.rawID) == ["t2"])
    // No length, no playback: the server never said how long it is.
    #expect(item("track", "t3", duration: nil).playableTrack == nil)
    #expect(item("track", "t4").playableTrack?.albumID?.rawID == "album-1")
}

// MARK: - The session

@Test @MainActor
func aSavedAccountIsReadFromTheDeviceBeforeAnyScreenSubscribes() throws {
    let factory = RecordingReaderFactory()
    let reachability = ManualReachability()
    let session = DulcetLibrarySession(factory: factory, reachability: reachability)
    session.open(account: readerAccount, mode: .deviceOnly)
    let model = DulcetLibraryWindowModel(query: .albums(.alphabeticalByName))
    model.open(in: session)
    session.setForeground(false)
    session.setForeground(true)
    session.reconnect()

    let reader = try #require(factory.made.first)
    // CONF-10b: offline first, then the subscription, and nothing that reads the server.
    #expect(reader.events.first == "setOnline(false)")
    #expect(reader.events.contains("subscribeWindow"))
    #expect((reader.events.firstIndex(of: "setOnline(false)") ?? .max) < (reader.events.firstIndex(of: "subscribeWindow") ?? -1))
    #expect(!reader.events.contains("connect"))
    #expect(!reader.events.contains("reconnect"), "Try Again on a saved account is Reconnect, not a read")
    #expect(!reader.events.contains("setOnline(true)"))
    #expect(reachability.started == 0, "reachability is not forwarded before the person reconnects")
}

@Test @MainActor
func reconnectingTheSameAccountKeepsItsReaderAndItsScreens() throws {
    let factory = RecordingReaderFactory()
    let reachability = ManualReachability()
    let session = DulcetLibrarySession(factory: factory, reachability: reachability)
    session.open(account: readerAccount, mode: .deviceOnly)
    let model = DulcetLibraryWindowModel(query: .artists)
    model.open(in: session)
    let generation = session.readerGeneration
    session.open(account: readerAccount, mode: .connected)

    #expect(factory.made.count == 1)
    #expect(session.readerGeneration == generation, "the screens keep their rows through the reconnect")
    let reader = try #require(factory.made.first)
    #expect(Array(reader.events.suffix(2)) == ["setOnline(true)", "reconnect"])
    #expect(reachability.started == 1)
    reachability.report(reachable: false)
    #expect(reader.events.last == "setOnline(false)")
}

@Test @MainActor
func tryAgainIsTheReconnectNeverAScreensOwnRead() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let model = DulcetLibraryWindowModel(query: .playlists)
    model.open(in: session)
    session.reconnect()
    #expect(reader.events.filter { $0 == "reconnect" }.count == 1)
    #expect(reader.windows.allSatisfy { !$0.events.contains("refresh") })

    reader.answerConnections(DulcetReaderConnection(
        epochKnown: false, serverReportsNoEpoch: false, discardedPendingChanges: 0, errorKind: "unreachable"))
    #expect(session.connectionFailure == .unreachable)
    session.reconnect()
    reader.answerConnections(DulcetReaderConnection(
        epochKnown: true, serverReportsNoEpoch: true, discardedPendingChanges: 0, errorKind: nil))
    #expect(session.connectionFailure == nil)
    #expect(session.serverReportsNoEpoch)
}

@Test @MainActor
func anotherAccountsReaderIsMadeOnlyOnceTheOldOneHasStopped() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let first = try #require(factory.made.first)
    let other = DulcetLibraryReaderAccount(
        providerInstanceID: "provider-reader",
        normalizedServerURL: "https://music.example.invalid",
        username: "listener",
        password: "changed-password",
        allowLocalHTTP: false
    )
    session.open(account: other, mode: .connected)
    #expect(first.events.last == "close")
    #expect(factory.made.count == 1, "two readers over one namespace would write one cache from two threads")
    first.finishClose()
    #expect(factory.made.count == 2)
    #expect(factory.made.last?.account == other)
}

@Test @MainActor
func closeCompletesOnlyAfterTheReadersThreadStopped() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    var closed = false
    session.close { closed = true }
    #expect(!closed, "the account's rows may go only once the reader has stopped")
    reader.finishClose()
    #expect(closed)
    #expect(session.mode == .none)
}

@Test @MainActor
func aFavouriteShowsAtOnceIsHeldWhenRefusedAndClearsWhenSaved() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t1")
    let target = DulcetFavouriteTarget(kind: .track, id: id)

    #expect(!session.isFavourite(id, published: false))
    #expect(session.toggleFavourite(target, published: false))
    #expect(session.isFavourite(id, published: false), "the tap shows before any request")
    #expect(session.favouriteState(target) == .pending)
    #expect(reader.favourites.last?.1 == true)

    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "held", targetKind: "track", rawID: "t1", field: "favourite", errorKind: "forbidden"))
    #expect(session.favouriteState(target) == .held(.forbidden))
    #expect(session.notice != nil)

    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: "t1", field: "favourite", errorKind: nil))
    #expect(session.favouriteState(target) == .settled)

    reader.acceptsFavourites = false
    #expect(!session.setFavourite(target, favourite: false))
    #expect(session.notice?.message == DulcetStrings.readerChangeNotRecorded)
}

@Test @MainActor
func aWindowReportsItsViewportAndExtendsOncePerLength() async throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let model = DulcetLibraryWindowModel(query: .albums(.newest))
    model.open(in: session)
    let subscription = try #require(reader.windows.first)
    let items = (0 ..< 10).map { item("album", "a\($0)") }
    subscription.publish(window(items, coverage: "open"))
    for index in 0 ..< 10 { model.rowAppeared(index) }
    await Task.yield()
    await Task.yield()
    #expect(subscription.events.contains("viewport(0,9)"))
    #expect(subscription.events.filter { $0 == "loadMore" }.count == 1)
    model.rowDisappeared(0)
    await Task.yield()
    await Task.yield()
    #expect(subscription.events.filter { $0 == "loadMore" }.count == 1, "the same end is asked for once")
    subscription.publish(window(items + [item("album", "a10")], coverage: "complete", sequence: 2))
    model.rowAppeared(10)
    await Task.yield()
    await Task.yield()
    #expect(subscription.events.filter { $0 == "loadMore" }.count == 1, "a complete list does not extend")
}

// MARK: - Reader mode in the data source and the store

@MainActor
private func readerModeStore(
    persisted: DulcetAccountConnectRequest?,
    providerInstanceID: String?,
    factory: RecordingReaderFactory,
    connector: ReaderTestConnector = ReaderTestConnector(),
    browser: ForbiddenLibraryBrowser = ForbiddenLibraryBrowser(),
    credentials: ReaderTestCredentials? = nil
) -> (DulcetPresentationStore, DulcetLibrarySession, ReaderTestCredentials) {
    let session = DulcetLibrarySession(factory: factory)
    let store = credentials ?? ReaderTestCredentials(persisted: persisted, providerInstanceID: providerInstanceID)
    let presentation = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        credentialStore: store,
        libraryBrowser: browser,
        providerInstanceIDFactory: { "provider-new" },
        librarySession: session
    ))
    return (presentation, session, store)
}

@Test @MainActor
func theAppOpensStraightIntoTheLibraryItHasSeenAndSendsNothing() throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let browser = ForbiddenLibraryBrowser()
    let saved = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let (store, session, _) = readerModeStore(
        persisted: saved, providerInstanceID: "provider-reader", factory: factory,
        connector: connector, browser: browser)

    #expect(store.selectedDestination == .library)
    #expect(store.readerOwnsLibrary)
    #expect(session.mode == .deviceOnly)
    let reader = try #require(factory.made.first)
    #expect(reader.account == readerAccount)
    #expect(reader.events == ["setOnline(false)"])
    store.navigate(to: .search)
    store.navigate(to: .library)
    #expect(store.snapshot.state == .libraryBrowse, "the reader's library, not the Reconnect wall")
    #expect(connector.requests.isEmpty, "CONF-10b: nothing is sent until Reconnect")
    #expect(browser.browses == 0, "the library sync is not a UI path in reader mode")
}

@Test @MainActor
func reconnectFromTheLibraryConnectsInPlaceAndSavesTheReachedAddress() throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let browser = ForbiddenLibraryBrowser()
    let saved = DulcetAccountConnectRequest(
        serverURL: "music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let (store, session, credentials) = readerModeStore(
        persisted: saved, providerInstanceID: "provider-reader", factory: factory,
        connector: connector, browser: browser)
    store.submitAccountConnection()
    #expect(store.selectedDestination == .library, "Reconnect on the library connects where the person is")
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Navidrome", normalizedServerURL: "https://music.example.invalid")))

    #expect(session.mode == .connected)
    #expect(credentials.persisted?.serverURL == "https://music.example.invalid",
            "the next launch must open the namespace the reader bound, without reaching the server")
    // The saved account's reader bound the typed address; the connected one binds the reached
    // one, so it is a different account and a second reader follows the first one's close.
    let first = try #require(factory.made.first)
    #expect(first.events.last == "close")
    first.finishClose()
    #expect(factory.made.count == 2)
    #expect(factory.made.last?.account.normalizedServerURL == "https://music.example.invalid")
    #expect(factory.made.last?.events.contains("connect") == true)
    #expect(store.selectedDestination == .library)
    store.navigate(to: .search)
    store.navigate(to: .library)
    #expect(browser.browses == 0, "no library sync runs from any screen while the reader holds the account")
    #expect(store.snapshot.state == .libraryBrowse)
}

@Test @MainActor
func aReaderPageFromAnotherDestinationOpensLibraryOnIt() {
    let factory = RecordingReaderFactory()
    let (store, _, _) = readerModeStore(
        persisted: DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid", username: "listener",
            password: "fixture-password", allowLocalHTTP: false),
        providerInstanceID: "provider-reader",
        factory: factory
    )
    let album = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "album-9")
    store.navigate(to: .search)
    store.showAlbum(album)
    #expect(store.selectedDestination == .library)
    #expect(store.readerPath == [.album(album)])
    store.pushReaderPage(.artist(DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "artist-1")))
    #expect(store.readerPath.count == 2)
    store.popReader(to: [.album(album)])
    #expect(store.readerPath == [.album(album)])
    store.navigate(to: .library)
    #expect(store.readerPath.isEmpty, "choosing the tab showing returns it to its root")
    store.selectLibrarySection(.genres)
    #expect(store.librarySection == .genres)
}

@Test @MainActor
func signingOutOffersTheChangesThatHaveNotReachedTheServer() throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let (store, session, _) = readerModeStore(
        persisted: nil, providerInstanceID: nil, factory: factory, connector: connector)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Navidrome", normalizedServerURL: "https://music.example.invalid")))
    let reader = try #require(factory.made.first)
    #expect(session.mode == .connected)

    reader.pending = 3
    store.removeAccount()
    let offer = try #require(store.signOutOffer)
    #expect(offer.pendingCount == 3)
    #expect(offer.phase == .asking(online: true))
    #expect(store.snapshot.state != .accountRemoving, "nothing is discarded before the person decides")
    store.dismissSignOutOffer()
    #expect(store.signOutOffer == nil)

    // The count could not be read: the offer is still made, saying so.
    reader.pending = nil
    store.removeAccount()
    #expect(store.signOutOffer?.pendingCount == nil)
    #expect(store.signOutOffer != nil)

    store.signOutDiscardingChanges()
    #expect(store.signOutOffer == nil)
    #expect(store.snapshot.state == .accountRemoving)
}

@Test @MainActor
func sendingChangesBeforeSigningOutReconnectsAndSignsOutOnlyWhenNoneAreLeft() throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let (store, _, _) = readerModeStore(
        persisted: nil, providerInstanceID: nil, factory: factory, connector: connector)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Navidrome", normalizedServerURL: "https://music.example.invalid")))
    let reader = try #require(factory.made.first)
    reader.pending = 2
    store.removeAccount()
    store.sendChangesThenSignOut()
    #expect(store.signOutOffer?.phase == .sending)
    #expect(reader.events.last == "reconnect")

    // The server could not be reached: the offer comes back, and the account stays.
    reader.answerConnections(DulcetReaderConnection(
        epochKnown: false, serverReportsNoEpoch: false, discardedPendingChanges: 0, errorKind: "unreachable"))
    #expect(store.signOutOffer?.phase == .sendFailed)
    #expect(store.snapshot.state != .accountRemoving)

    store.dismissSignOutOffer()
    store.removeAccount()
    store.sendChangesThenSignOut()
    reader.pending = 0
    reader.answerConnections(DulcetReaderConnection(
        epochKnown: true, serverReportsNoEpoch: false, discardedPendingChanges: 0, errorKind: nil))
    #expect(store.signOutOffer == nil)
    #expect(store.snapshot.state == .accountRemoving)
}

@Test @MainActor
func searchIsAnsweredByTheReaderFromTheFirstCharacter() throws {
    let factory = RecordingReaderFactory()
    let (store, session, _) = readerModeStore(
        persisted: DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid", username: "listener",
            password: "fixture-password", allowLocalHTTP: false),
        providerInstanceID: "provider-reader",
        factory: factory
    )
    store.navigate(to: .search)
    let model = DulcetReaderSearchModel()
    model.open(in: session, query: store.searchQuery)
    store.searchQuery = "a"
    model.updateQuery(store.searchQuery)
    let reader = try #require(factory.made.first)
    let search = try #require(reader.searches.first)
    #expect(search.queries.last == "a")
    search.publish(DulcetReaderSearchPublication(
        query: "a", sequence: 1, scope: .deviceOffline(nil), rows: []))
    #expect(model.current?.query == "a")
    #expect(DulcetReaderCopy.searchScopeLabel(model.current?.scope) == DulcetStrings.readerSearchScopeDevice)
    // A publication for an older query never shows under a newer one.
    model.updateQuery("ab")
    #expect(model.current == nil)
}

@Test @MainActor
func signOutStopsTheReaderBeforeTheCredentialIsDeleted() async throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let (store, session, credentials) = readerModeStore(
        persisted: nil, providerInstanceID: nil, factory: factory, connector: connector)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Navidrome", normalizedServerURL: "https://music.example.invalid")))
    let reader = try #require(factory.made.first)
    store.removeAccount()
    #expect(store.signOutOffer == nil, "nothing unsent, so nothing to offer")
    for _ in 0 ..< 200 where reader.closeCompletion == nil { await Task.yield() }
    #expect(reader.events.last == "close", "the removal reached the reader")
    #expect(credentials.persisted != nil, "the credential outlives a reader that is still stopping")
    reader.finishClose()
    for _ in 0 ..< 200 where store.snapshot.state != .accountConnectIdle { await Task.yield() }
    #expect(credentials.persisted == nil)
    #expect(store.snapshot.state == .accountConnectIdle)
    #expect(session.mode == .none)
    #expect(!store.readerOwnsLibrary)
}

@Test
func theLockScreenHeartIsOfferedOnlyWithAHandlerAndACurrentTrack() {
    let center = MPRemoteCommandCenter.shared()
    let command = DulcetSystemLikeCommand(commandCenter: center)
    command.update(available: true, isFavourite: true)
    command.apply(to: center, hasItem: true)
    #expect(!center.likeCommand.isEnabled, "no handler: a heart that does nothing is not offered")
    command.setToggleHandler { true }
    #expect(center.likeCommand.isEnabled)
    #expect(center.likeCommand.isActive)
    command.update(available: true, isFavourite: false)
    #expect(!center.likeCommand.isActive)
    command.apply(to: center, hasItem: false)
    #expect(!center.likeCommand.isEnabled, "nothing playing, nothing to like")
    command.setToggleHandler(nil)
    #expect(!center.likeCommand.isEnabled)
}
