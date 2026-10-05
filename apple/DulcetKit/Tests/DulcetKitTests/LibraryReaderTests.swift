import Foundation
import MediaPlayer
import Observation
import Testing
@testable import DulcetKit

// The Swift half of the library reader (spec §16.18): the value copies of the facade's
// publications, the words for them, the session that owns the account's reader, and the data
// source and store in reader mode. The reader itself is a recording fake; the core behind the
// real one is covered by the core's own suites.

// MARK: - Fakes

@MainActor
private class RecordingReader: DulcetLibraryReading {
    let account: DulcetLibraryReaderAccount
    private(set) var events: [String] = []
    private(set) var windows: [RecordingWindow] = []
    private(set) var searches: [RecordingSearch] = []
    private(set) var favourites: [(DulcetFavouriteTarget, Bool)] = []
    private(set) var ratings: [(DulcetFavouriteTarget, Int)] = []
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

    func setRating(_ target: DulcetFavouriteTarget, rating: Int) -> Bool {
        events.append("setRating")
        ratings.append((target, rating))
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

    /// Whether the setup fails, said when the test calls `finishSetup`; nil until then.
    var setupCompletion: (@MainActor (Bool) -> Void)?
    var setupCancelled = false

    func setupFailed(completion: @escaping @MainActor (Bool) -> Void) -> any DulcetLibraryReaderCancellable {
        setupCompletion = completion
        return RecordingCancellable { [weak self] in self?.setupCancelled = true }
    }

    func finishSetup(failed: Bool) {
        let completion = setupCompletion
        setupCompletion = nil
        if !setupCancelled { completion?(failed) }
    }

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
    private(set) var refreshes = 0
    private(set) var closed = false

    init(publish: @escaping @MainActor (DulcetReaderSearchPublication) -> Void) {
        self.publish = publish
    }

    func updateQuery(_ text: String) { queries.append(text) }
    func refresh() { refreshes += 1 }
    func close() { closed = true }
}

@MainActor
private final class RecordingCancellable: DulcetLibraryReaderCancellable {
    private let onCancel: @MainActor () -> Void
    init(onCancel: @escaping @MainActor () -> Void = {}) { self.onCancel = onCancel }
    func cancel() { onCancel() }
}

@MainActor
private final class RecordingReaderFactory: DulcetLibraryReaderMaking {
    private(set) var made: [RecordingReader] = []
    /// The foreground each reader was made with, in order.
    private(set) var foregrounds: [Bool] = []
    /// Makes readers that also edit playlists.
    var editsPlaylists = false

    func makeReader(account: DulcetLibraryReaderAccount, foreground: Bool) -> any DulcetLibraryReading {
        let reader = editsPlaylists ? PlaylistEditingReader(account: account) : RecordingReader(account: account)
        made.append(reader)
        foregrounds.append(foreground)
        return reader
    }
}

/// A reader that edits playlists, recording who listens for its playlist outcomes.
@MainActor
private final class PlaylistEditingReader: RecordingReader, DulcetPlaylistEditing {
    private(set) var playlistListeners = 0
    private(set) var playlistListenersCancelled = 0

    func editPlaylist(_ edit: DulcetPlaylistEdit, completion: @escaping @MainActor (DulcetPlaylistEditResult) -> Void) {}

    func pendingPlaylistChanges(completion: @escaping @MainActor ([DulcetPlaylistPendingChange]?) -> Void) {
        completion([])
    }

    func subscribePlaylistOutcomes(
        _ handler: @escaping @MainActor (DulcetPlaylistOutcome) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        playlistListeners += 1
        return RecordingCancellable { [weak self] in self?.playlistListenersCancelled += 1 }
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

/// Virtual time for the session's delays: nothing runs until the test advances the clock.
@MainActor
private final class ManualDelays: DulcetDelayScheduling {
    private final class Entry: DulcetLibraryReaderCancellable {
        let due: Duration
        var action: (@MainActor () -> Void)?
        init(due: Duration, action: @escaping @MainActor () -> Void) {
            self.due = due
            self.action = action
        }
        func cancel() { action = nil }
    }

    private(set) var now: Duration = .zero
    private var entries: [Entry] = []

    var pending: Int { entries.filter { $0.action != nil }.count }

    func schedule(after delay: Duration, _ action: @escaping @MainActor () -> Void) -> any DulcetLibraryReaderCancellable {
        let entry = Entry(due: now + delay, action: action)
        entries.append(entry)
        return entry
    }

    func advance(by step: Duration) {
        now += step
        while let next = entries.filter({ $0.action != nil && $0.due <= now }).min(by: { $0.due < $1.due }) {
            let action = next.action
            next.action = nil
            action?()
        }
        entries.removeAll { $0.action == nil }
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

/// Counts every server search the legacy search screen would make.
@MainActor
private final class ForbiddenServerSearch: DulcetServerSearching {
    private(set) var searches = 0

    func search(
        _ request: DulcetSearchPageRequest,
        completion: @escaping @MainActor (DulcetSearchPageOutcome) -> Void
    ) -> any DulcetSearchOperation {
        searches += 1
        return ForbiddenSearchOperation()
    }
}

@MainActor
private final class ForbiddenSearchOperation: DulcetSearchOperation {
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
    rating: Int? = nil,
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
        rating: rating,
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
    total: Int? = nil,
    leadingOffset: Int = 0
) -> DulcetLibraryWindow {
    DulcetLibraryWindow(
        sequence: sequence,
        freshness: freshness,
        coverage: coverage,
        total: total,
        leadingOffset: leadingOffset,
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
    let delays = ManualDelays()
    let session = DulcetLibrarySession(factory: factory, reachability: reachability, delays: delays)
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
    #expect(reader.events.last(where: { $0.hasPrefix("setOnline") }) == "setOnline(true)", "an unreachable report waits out the grace")
    delays.advance(by: DulcetLibrarySession.defaultUnreachableGrace)
    #expect(reader.events.last == "setOnline(false)")
}

/// A connected session over a reader whose connect succeeded, with reachability and virtual time
/// in the test's hands. `events` is what the reader is told from then on, constraint reports aside.
@MainActor
private func connectedSessionWithManualTime() throws -> (DulcetLibrarySession, ManualReachability, ManualDelays, events: () -> [String], log: () -> [String]) {
    let factory = RecordingReaderFactory()
    let reachability = ManualReachability()
    let delays = ManualDelays()
    var lines: [String] = []
    let session = DulcetLibrarySession(
        factory: factory, reachability: reachability, delays: delays, reachabilityLog: { lines.append($0) })
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    reader.answerConnections(DulcetReaderConnection(
        epochKnown: true, serverReportsNoEpoch: false, discardedPendingChanges: 0, errorKind: nil))
    reachability.report(reachable: true)
    let mark = reader.events.count
    return (session, reachability, delays, { reader.events.dropFirst(mark).filter { !$0.hasPrefix("setNetworkConstrained") } }, { lines })
}

// §16.14 "A reachability report is a hint": the CI album page that went offline for 4.5 s while
// its server answered (conformance run 37229956675).
@Test @MainActor
func aReachabilityBlipShorterThanTheGraceNeverTakesTheReaderOffline() throws {
    let (session, reachability, delays, events, log) = try connectedSessionWithManualTime()
    #expect(session.isOnline, "control: connected and reachable")

    reachability.report(reachable: false)
    delays.advance(by: .milliseconds(4_900))
    #expect(session.isOnline, "4.9 s into the blip the library still reads as online")
    #expect(!events().contains("setOnline(false)"), "the reader is not told during the grace: \(events())")
    reachability.report(reachable: true)
    delays.advance(by: .seconds(60))

    #expect(events().isEmpty, "a withdrawn report reaches the reader as nothing at all: \(events())")
    #expect(session.isOnline)
    #expect(delays.pending == 0, "the held report's timer is gone")
    #expect(log().contains { $0.hasPrefix("unreachable reported; held for 5000 ms") }, "\(log())")
    #expect(log().contains { $0.hasPrefix("reachable again after") && $0.hasSuffix("the reader stayed online") }, "\(log())")
}

@Test @MainActor
func anUnreachableReportThatOutlastsTheGraceTakesTheReaderOfflineAndRecoveryReconnects() throws {
    let (session, reachability, delays, events, log) = try connectedSessionWithManualTime()

    reachability.report(reachable: false)
    reachability.report(reachable: false) // a repeated report starts no second grace
    delays.advance(by: .milliseconds(4_999))
    #expect(events().isEmpty)
    delays.advance(by: .milliseconds(1))
    #expect(events() == ["setOnline(false)"], "a real loss is told when the grace ends: \(events())")
    #expect(!session.isOnline, "and the session says offline")
    #expect(log().contains { $0.contains("the reader is told and goes offline") }, "\(log())")

    reachability.report(reachable: false)
    delays.advance(by: .seconds(60))
    #expect(events() == ["setOnline(false)"], "already offline: nothing more")

    reachability.report(reachable: true)
    #expect(events() == ["setOnline(false)", "setOnline(true)"], "reachable again is told at once")
    #expect(log().last == "reachable; the reader is told and reconnects")
    for line in log() {
        #expect(!line.contains("http") && !line.contains(readerAccount.username) && !line.contains(readerAccount.password),
                "a reachability line names no server or credential: \(line)")
    }
}

@Test @MainActor
func aHeldUnreachableReportDiesWithTheConnection() throws {
    let (session, reachability, delays, events, _) = try connectedSessionWithManualTime()
    reachability.report(reachable: false)
    session.disconnect()
    #expect(events() == ["setOnline(false)"], "the disconnect's own offline")
    delays.advance(by: .seconds(60))
    #expect(events() == ["setOnline(false)"], "a report held for a connection that ended is never told: \(events())")
    #expect(delays.pending == 0)
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
func aRatingShowsAtOnceIsHeldWhenRefusedAndClearsWhenSaved() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t1")
    let target = DulcetFavouriteTarget(kind: .track, id: id)

    #expect(session.rating(id, published: 2) == 2)
    #expect(session.setRating(target, rating: 4))
    #expect(session.rating(id, published: 2) == 4, "the rating shows before any request")
    #expect(session.ratingState(target) == .pending)
    #expect(reader.ratings.map(\.1) == [4])
    #expect(reader.ratings.first?.0 == target, "the send names the rated track")
    #expect(session.favouriteState(target) == .settled, "a rating is not a pending favourite")

    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "held", targetKind: "track", rawID: "t1", field: "rating", errorKind: "forbidden"))
    #expect(session.ratingState(target) == .held(.forbidden))
    #expect(session.rating(id, published: 2) == 4, "a held rating is still the person's")
    #expect(session.notice != nil)

    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: "t1", field: "rating", errorKind: nil))
    #expect(session.ratingState(target) == .settled)
    #expect(session.rating(id, published: nil) == 4, "the saved rating stays where no row shows it")

    // 0 removes the rating, through the same outbox.
    #expect(session.setRating(target, rating: 0))
    #expect(reader.ratings.map(\.1) == [4, 0])
    #expect(session.rating(id, published: 4) == 0, "the removal shows before any request")

    // Anything else is refused here and nothing is sent.
    #expect(!session.setRating(target, rating: 6))
    #expect(!session.setRating(target, rating: -1))
    #expect(reader.ratings.count == 2)

    reader.acceptsFavourites = false
    #expect(!session.setRating(target, rating: 3))
    #expect(session.notice?.message == DulcetStrings.readerChangeNotRecorded)
}

@Test @MainActor
func aRatingOutcomeNeverSettlesAFavouriteOfTheSameTrack() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t1")
    let target = DulcetFavouriteTarget(kind: .track, id: id)
    #expect(session.setFavourite(target, favourite: true))
    #expect(session.setRating(target, rating: 5))

    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: "t1", field: "rating", errorKind: nil))
    #expect(session.ratingState(target) == .settled)
    #expect(session.favouriteState(target) == .pending, "the heart's own send has not ended")

    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: "t1", field: "favourite", errorKind: nil))
    #expect(session.favouriteState(target) == .settled)
}

@Test @MainActor
func aRatingThatIsNotSavedOrSupersededShowsTheServersValueAgain() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t1")
    let target = DulcetFavouriteTarget(kind: .track, id: id)

    for (kind, error, server) in [("notSaved", "forbidden", nil), ("superseded", nil, 2), ("notRecorded", nil, nil)] as [(String, String?, Int?)] {
        #expect(session.setRating(target, rating: 5))
        #expect(session.rating(id, published: 1) == 5)
        reader.outcomeHandler?(DulcetFavouriteOutcome(
            kind: kind, targetKind: "track", rawID: "t1", field: "rating", errorKind: error, serverValue: server))
        #expect(session.ratingState(target) == .settled, "\(kind)")
        #expect(session.rating(id, published: 1) == 1, "\(kind): the row's server value shows again")
        #expect(session.notice != nil, "\(kind): the person is told")
    }
    // No screen of this session ever published t1: the tapped value is never taken for the
    // server's, and a failed change leaves it unknown, not 0. A superseding value is the server's.
    #expect(session.rating(id, published: nil) == 2, "superseded: the server's value wins")
}

@Test @MainActor
func aTrackNoScreenHasPublishedHasAnUnknownRatingNeverZero() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "queued")
    let target = DulcetFavouriteTarget(kind: .track, id: id)
    #expect(session.rating(id, published: nil) == nil, "a restored queue's track is unknown")
    #expect(DulcetRating.adjusted(session.rating(id, published: nil), increment: true) == nil,
            "no relative step from an unknown rating")
    #expect(session.setRating(target, rating: 3), "an absolute rating is still allowed")
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "notSaved", targetKind: "track", rawID: "queued", field: "rating", errorKind: "forbidden"))
    #expect(session.rating(id, published: nil) == nil, "a refused change leaves it unknown, not the tap and not 0")
}

/// The order that exposed the defect: the core's flush republishes every open screen BEFORE it
/// tells the outcome, so the screen has already recorded the server's value when the outcome
/// arrives, and the outcome must not erase it.
@Test @MainActor
func aServerValueRepublishedBeforeTheOutcomeIsKeptAndIsWhatAnAdjustStepsFrom() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let model = DulcetLibraryWindowModel(query: .albums(.newest))
    model.open(in: session)
    let subscription = try #require(reader.windows.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t1")
    let target = DulcetFavouriteTarget(kind: .track, id: id)

    // Superseded: the person steps 3 to 4 while another device set 5.
    subscription.publish(window([item("track", "t1", rating: 3)], sequence: 1))
    #expect(session.setRating(target, rating: 4))
    subscription.publish(window([item("track", "t1", rating: 4)], sequence: 2)) // the overlay
    #expect(session.knownRatings[id] == 3, "the overlay is not the server's value")
    subscription.publish(window([item("track", "t1", rating: 5)], sequence: 3)) // republished first
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "superseded", targetKind: "track", rawID: "t1", field: "rating", errorKind: nil, serverValue: 5))
    #expect(session.rating(id, published: nil) == 5, "Now Playing shows the server's 5, not empty stars")
    #expect(DulcetRating.adjusted(session.rating(id, published: nil), increment: false) == 4,
            "the next step goes from 5, never from a fabricated 0")

    // Not saved: the republish shows the server's 3 again before the outcome.
    subscription.publish(window([item("track", "t1", rating: 3)], sequence: 4))
    #expect(session.setRating(target, rating: 1))
    subscription.publish(window([item("track", "t1", rating: 1)], sequence: 5)) // the overlay
    subscription.publish(window([item("track", "t1", rating: 3)], sequence: 6)) // republished first
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "notSaved", targetKind: "track", rawID: "t1", field: "rating", errorKind: "forbidden"))
    #expect(session.rating(id, published: nil) == 3)

    // Saved: the value set is what the server holds.
    #expect(session.setRating(target, rating: 2))
    subscription.publish(window([item("track", "t1", rating: 2)], sequence: 7))
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: "t1", field: "rating", errorKind: nil, value: 2))
    #expect(session.rating(id, published: nil) == 2)
}

/// Two taps before the first is answered: Saved(4) says what the server holds, and says nothing
/// about the 5 still on its way. The 5 stays shown -- and held, when it is held -- until its own
/// outcome; the 4 becomes what the server is known to hold.
@Test @MainActor
func aSavedEarlierRatingNeitherDropsNorUnholdsTheLaterOneStillOnItsWay() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t1")
    let target = DulcetFavouriteTarget(kind: .track, id: id)

    #expect(session.setRating(target, rating: 4))
    #expect(session.setRating(target, rating: 5))
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "held", targetKind: "track", rawID: "t1", field: "rating", errorKind: "forbidden"))
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: "t1", field: "rating", errorKind: nil, value: 4))
    #expect(session.rating(id, published: nil) == 5, "the later tap still shows")
    #expect(session.ratingState(target) == .held(.forbidden), "and is still held, and said to be")
    #expect(session.knownRatings[id] == 4, "the earlier value is what the server holds now")

    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: "t1", field: "rating", errorKind: nil, value: 5))
    #expect(session.rating(id, published: nil) == 5)
    #expect(session.ratingState(target) == .settled, "its own outcome settles it")
    #expect(session.knownRatings[id] == 5)
}

/// Tap 4, tap 5, then the overlay publication of the 4 arrives late: it is this session's own
/// value, not the server's, so it is not recorded as what the server holds -- the refusal that
/// follows returns the stars to the server's 2.
@Test @MainActor
func aLateOverlayOfAnEarlierTapIsNeverTakenAsTheServersRating() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let model = DulcetLibraryWindowModel(query: .albums(.newest))
    model.open(in: session)
    let subscription = try #require(reader.windows.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t1")
    let target = DulcetFavouriteTarget(kind: .track, id: id)

    subscription.publish(window([item("track", "t1", rating: 2)], sequence: 1))
    #expect(session.setRating(target, rating: 4))
    #expect(session.setRating(target, rating: 5))
    subscription.publish(window([item("track", "t1", rating: 4)], sequence: 2)) // the first tap's overlay, late
    #expect(session.knownRatings[id] == 2, "an earlier tap's overlay is not the server's value")
    subscription.publish(window([item("track", "t1", rating: 5)], sequence: 3)) // the latest overlay
    #expect(session.knownRatings[id] == 2)
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "notSaved", targetKind: "track", rawID: "t1", field: "rating", errorKind: "forbidden"))
    #expect(session.rating(id, published: nil) == 2, "refused, the stars show the server's 2, never a tap")

    // Settled, a publication is the server's again, whatever it shows.
    subscription.publish(window([item("track", "t1", rating: 4)], sequence: 4))
    #expect(session.knownRatings[id] == 4, "once nothing is pending, the tapped values are forgotten")
}

@Test @MainActor
func theScreensTellTheSessionEachTracksRatingForNowPlaying() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let model = DulcetLibraryWindowModel(query: .albums(.newest))
    model.open(in: session)
    let subscription = try #require(reader.windows.first)
    subscription.publish(window([item("track", "t1", rating: 3), item("track", "t2")]))
    let t1 = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t1")
    let t2 = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t2")
    #expect(session.knownRatings[t1] == 3)
    #expect(session.knownRatings[t2] == nil, "a rating the server never gave is not a zero")
    #expect(session.rating(t1, published: nil) == 3)
}

@Test @MainActor
func nowPlayingOffersStarsOnlyWhileTheReaderHoldsThePlayingTracksAccount() throws {
    let saved = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let factory = RecordingReaderFactory()
    let (store, session, _) = readerModeStore(
        persisted: saved, providerInstanceID: "provider-reader", factory: factory)
    let ours = try #require(item("track", "t1", rating: 2).playableTrack)
    let theirs = try #require(DulcetReaderItem(
        kind: "track", providerInstanceID: "another-server", rawID: "t9", title: "Elsewhere",
        artistName: nil, artistRawID: nil, albumTitle: nil, albumRawID: nil, year: nil, genre: nil,
        durationMilliseconds: 60_000, songCount: nil, albumCount: nil, discNumber: nil, trackNumber: nil,
        sourceContainer: "Mp3", artworkKey: nil, owner: nil, favourite: nil, rating: 5, playCount: nil,
        playability: "streamable", detailComplete: true, metadataMissing: false
    )?.playableTrack)

    #expect(store.nowPlayingRating(for: theirs) == nil, "another account's track cannot be rated here")
    let offered = try #require(store.nowPlayingRating(for: ours))
    #expect(offered.target == DulcetFavouriteTarget(kind: .track, id: ours.id))
    #expect(offered.published == nil, "no screen has shown its rating yet")

    #expect(offered.published == nil && session.rating(ours.id, published: offered.published) == nil,
            "unknown, not unrated")
    #expect(session.setRating(offered.target, rating: 4))
    #expect(session.rating(ours.id, published: store.nowPlayingRating(for: ours)?.published) == 4,
            "the stars read what the person set")
    #expect(try #require(factory.made.first).ratings.map(\.1) == [4])

    let signedOut = DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .nowPlaying))
    #expect(signedOut.nowPlayingRating(for: ours) == nil, "no reader session, no stars")
}

@Test
func theStarsRateRemoveAndAdjustWithinZeroToFive() {
    #expect(DulcetRating.value(pressing: 3, current: 0) == 3)
    #expect(DulcetRating.value(pressing: 3, current: 5) == 3)
    #expect(DulcetRating.value(pressing: 3, current: 3) == 0, "pressing the rating again removes it")
    #expect(DulcetRating.adjusted(0, increment: true) == 1)
    #expect(DulcetRating.adjusted(5, increment: true) == 5, "never past five")
    #expect(DulcetRating.adjusted(0, increment: false) == 0, "never below none")
    #expect(DulcetRating.adjusted(3, increment: false) == 2)
    #expect(DulcetRating.stars.map { DulcetRating.isFilled(star: $0, rating: 2) } == [true, true, false, false, false])
    #expect(DulcetRating.accessibilityValue(rating: 0, state: .settled) == "Not rated")
    #expect(DulcetRating.accessibilityValue(rating: nil, state: .settled) == "Rating unknown")
    #expect(DulcetRating.stars.allSatisfy { !DulcetRating.isFilled(star: $0, rating: nil) }, "unknown fills nothing")
    #expect(DulcetRating.adjusted(nil, increment: true) == nil && DulcetRating.adjusted(nil, increment: false) == nil)
    #expect(DulcetRating.value(pressing: 3, current: nil) == 3, "a press is absolute")
    #expect(DulcetRating.accessibilityValue(rating: 1, state: .settled) == "1 star")
    #expect(DulcetRating.accessibilityValue(rating: 4, state: .pending) == "4 stars, Waiting to send")
    #expect(DulcetRating.accessibilityValue(rating: 4, state: .held(.forbidden)) == "4 stars, Not sent yet")
    #expect(DulcetRating.starLabel(star: 2, current: 4) == "Rate 2 stars")
    #expect(DulcetRating.starLabel(star: 1, current: 0) == "Rate 1 star")
    #expect(DulcetRating.starLabel(star: 4, current: 4) == "Remove Rating")
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

/// Lets the model's scheduled viewport report run.
@MainActor
private func settle() async {
    await Task.yield()
    await Task.yield()
}

@Test @MainActor
func aListReadWhileTheServerScansOrReportsNoEpochStillGrowsAtItsEnd() async throws {
    // §16.12: pages read while the server scans append unguarded, and a server with no stamp is
    // read the same way; refusing to ask for them would freeze the list at its first pages.
    for coverage in ["unverifiedScanning", "unverifiedNoEpoch"] {
        let factory = RecordingReaderFactory()
        let session = DulcetLibrarySession(factory: factory)
        session.open(account: readerAccount, mode: .connected)
        let reader = try #require(factory.made.first)
        let model = DulcetLibraryWindowModel(query: .albums(.newest))
        model.open(in: session)
        let subscription = try #require(reader.windows.first)
        let items = (0 ..< 10).map { item("album", "a\($0)") }
        subscription.publish(window(items, coverage: coverage))
        for index in 0 ..< 10 { model.rowAppeared(index) }
        await settle()
        #expect(subscription.events.filter { $0 == "loadMore" }.count == 1, "\(coverage) grows at its end")
    }
}

@Test @MainActor
func onlyAListThatCanStillGrowAsksForMore() async throws {
    // The control: a complete list has nothing more, and one whose stamp kept moving waits for the
    // next epoch reading to rebase it.
    for coverage in ["complete", "unverifiedChanging"] {
        let factory = RecordingReaderFactory()
        let session = DulcetLibrarySession(factory: factory)
        session.open(account: readerAccount, mode: .connected)
        let reader = try #require(factory.made.first)
        let model = DulcetLibraryWindowModel(query: .albums(.newest))
        model.open(in: session)
        let subscription = try #require(reader.windows.first)
        let items = (0 ..< 10).map { item("album", "a\($0)") }
        subscription.publish(window(items, coverage: coverage))
        for index in 0 ..< 10 { model.rowAppeared(index) }
        await settle()
        #expect(!subscription.events.contains("loadMore"), "\(coverage) does not grow")
    }
}

@Test @MainActor
func aRebasedWindowOfTheSameLengthWhoseEndIsOnScreenAsksForMoreAgain() async throws {
    // A rebase re-reads the viewport's pages: the new window can hold as many rows as the old one,
    // starting deeper (§16.12). Its end is on screen and nothing is left to scroll, so it must ask
    // for the next page even though the length did not change.
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let model = DulcetLibraryWindowModel(query: .albums(.newest))
    model.open(in: session)
    let subscription = try #require(reader.windows.first)
    let first = (0 ..< 4).map { item("album", "a\($0)") }
    subscription.publish(window(first, coverage: "open"))
    for index in 0 ..< 4 { model.rowAppeared(index) }
    await settle()
    #expect(subscription.events.filter { $0 == "loadMore" }.count == 1)
    // The check fired on that page: the window is rebased to the four rows on screen, deeper.
    for index in 0 ..< 4 { model.rowDisappeared(index) }
    let rebased = (4 ..< 8).map { item("album", "a\($0)") }
    subscription.publish(window(rebased, coverage: "open", sequence: 2, leadingOffset: 4))
    for index in 0 ..< 4 { model.rowAppeared(index) }
    await settle()
    #expect(subscription.events.filter { $0 == "loadMore" }.count == 2, "the rebased window grows")
    // A publication that changes only freshness (a failed page) asks for nothing, or a failing page
    // would be asked for in a loop.
    subscription.publish(window(
        rebased, freshness: .cached(.failed(.serverBusy), asOf: nil), coverage: "open",
        sequence: 3, leadingOffset: 4))
    await settle()
    #expect(subscription.events.filter { $0 == "loadMore" }.count == 2, "freshness alone asks for nothing")
}

@Test @MainActor
func aWindowGivenBackToWhatFitsTheScreenAsksForMore() async throws {
    // The window shrinks to the rows on screen and its end is visible with nothing left to scroll
    // (the Android TV grid stalled at 4 albums so). The length changed, which re-arms the ask.
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let reader = try #require(factory.made.first)
    let model = DulcetLibraryWindowModel(query: .albums(.newest))
    model.open(in: session)
    let subscription = try #require(reader.windows.first)
    let items = (0 ..< 30).map { item("album", "a\($0)") }
    subscription.publish(window(items, coverage: "open"))
    for index in 0 ..< 4 { model.rowAppeared(index) }
    await settle()
    #expect(!subscription.events.contains("loadMore"), "the end is not near")
    // Rows 4... leave the screen as they leave the window.
    subscription.publish(window(Array(items.prefix(4)), coverage: "open", sequence: 2))
    await settle()
    #expect(subscription.events.filter { $0 == "loadMore" }.count == 1, "the shrunken window grows")
}

@Test
func aListGrowsAtItsEndWhileOpenScanningOrWithoutAnEpoch() {
    let items = [item("album", "a1")]
    #expect(window(items, coverage: "open").growsAtEnd)
    #expect(window(items, coverage: "unverifiedScanning").growsAtEnd)
    #expect(window(items, coverage: "unverifiedNoEpoch").growsAtEnd)
    #expect(!window(items, coverage: "complete").growsAtEnd)
    #expect(!window(items, coverage: "unverifiedChanging").growsAtEnd)
    #expect(!window(items, coverage: nil).growsAtEnd)
}

// MARK: - Reader mode in the data source and the store

@MainActor
private func readerModeStore(
    persisted: DulcetAccountConnectRequest?,
    providerInstanceID: String?,
    factory: RecordingReaderFactory,
    connector: ReaderTestConnector = ReaderTestConnector(),
    browser: ForbiddenLibraryBrowser = ForbiddenLibraryBrowser(),
    serverSearch: ForbiddenServerSearch? = nil,
    credentials: ReaderTestCredentials? = nil
) -> (DulcetPresentationStore, DulcetLibrarySession, ReaderTestCredentials) {
    let session = DulcetLibrarySession(factory: factory)
    let store = credentials ?? ReaderTestCredentials(persisted: persisted, providerInstanceID: providerInstanceID)
    let presentation = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        credentialStore: store,
        libraryBrowser: browser,
        serverSearch: serverSearch,
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
func backFromTheMenuBarLeavesOnePageAndOnlyOnTheLibrary() throws {
    let saved = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let (store, _, _) = readerModeStore(
        persisted: saved, providerInstanceID: "provider-reader", factory: RecordingReaderFactory())
    #expect(!store.canGoBackInLibrary, "nothing is pushed yet")
    let album = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "album-1")
    let artist = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "artist-1")
    store.showReaderPage(.artist(artist))
    store.showReaderPage(.album(album))
    #expect(store.readerPath == [.artist(artist), .album(album)])

    store.navigate(to: .search)
    #expect(!store.canGoBackInLibrary, "Back belongs to the Library while it is showing")
    store.goBackInLibrary()
    store.navigate(to: .library)
    #expect(store.readerPath == [.artist(artist), .album(album)], "a hidden stack is not popped")

    store.goBackInLibrary()
    #expect(store.readerPath == [.artist(artist)], "one step, as the back button takes")
    store.goBackInLibrary()
    #expect(store.readerPath.isEmpty)
    #expect(!store.canGoBackInLibrary)
}

@Test @MainActor
func theLegacySearchAndAlbumReadsStaySilentWhileTheReaderHoldsTheAccount() async throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let browser = ForbiddenLibraryBrowser()
    let search = ForbiddenServerSearch()
    let saved = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let (store, session, _) = readerModeStore(
        persisted: saved, providerInstanceID: "provider-reader", factory: factory,
        connector: connector, browser: browser, serverSearch: search)
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Navidrome", normalizedServerURL: "https://music.example.invalid")))
    #expect(session.mode == .connected, "connected: the legacy paths would now be able to send")

    store.navigate(to: .search)
    store.searchQuery = "Threshold"
    // Actions only the legacy search and album screens send. A stale control, a restored scene
    // or a keyboard command can still deliver one; none may start a second read of the server.
    store.retrySearch()
    store.loadMoreSearchResults(.album)
    #expect(store.snapshot.state == .searchIdle, "a legacy search would publish searchLoading here")
    // The legacy search is sent from a task, so give it every chance to run before looking:
    // leaving the screen straight away would cancel it and the count would prove nothing.
    for _ in 0..<50 { await Task.yield() }
    #expect(search.searches == 0, "the reader's search surface is the only search in reader mode")
    store.navigate(to: .library)
    store.selectAlbum(DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "album-1"))
    store.retryAlbumTracks()

    #expect(browser.browses == 0)
    #expect(store.snapshot.state == .libraryBrowse)
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

/// Try Again on a search the reader itself failed re-runs the search, which answers again for the
/// query in the field; the reconnect would leave it as it is (§16.15). A server failure is still
/// the reconnect's.
@Test @MainActor
func tryAgainOnASearchTheReaderFailedRerunsTheSearchAndAServerFailureReconnects() throws {
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
    model.open(in: session, query: "echo")
    let reader = try #require(factory.made.first)
    let search = try #require(reader.searches.first)
    var reconnects = 0

    search.publish(DulcetReaderSearchPublication(
        query: "echo", sequence: 1, scope: .deviceServerFailed(.internalFailure, nil), rows: []))
    #expect(DulcetReaderSearchContent(query: model.query, publication: model.current, onActivate: { _ in })
        .presentation == .failed(.internalFailure), "control: the failure names the query in the field")
    model.retry { reconnects += 1 }
    #expect(search.refreshes == 1, "Try Again re-ran the search")
    #expect(reconnects == 0)

    search.publish(DulcetReaderSearchPublication(
        query: "echo", sequence: 2, scope: .deviceServerFailed(.timeout, nil), rows: []))
    model.retry { reconnects += 1 }
    #expect(reconnects == 1, "a server failure is retried by the reconnect, which re-runs the search")
    #expect(search.refreshes == 1)
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

// MARK: - The playing track's heart through a save

/// Publishes whatever the test hands it; the heart's source is the library session, not this.
@MainActor
private final class HeartTestPlayback: DulcetPlaybackControlling {
    private var handler: (@MainActor (DulcetPlaybackPresentation) -> Void)?
    private(set) var currentPresentation: DulcetPlaybackPresentation = .unavailable

    func setPresentationHandler(_ handler: @escaping @MainActor (DulcetPlaybackPresentation) -> Void) {
        self.handler = handler
    }
    func configure(account: DulcetPlaybackAccount) {}
    func restorePersistedQueue(with tracks: [DulcetTrack], catalogCoverage: DulcetLibraryCatalogCoverage) {}
    func replaceQueueAndPlay(_ intent: DulcetPlaybackQueueIntent) {}
    func send(_ intent: DulcetPlaybackControlIntent) {}
    func disconnect() {}

    func publish(_ presentation: DulcetPlaybackPresentation) {
        currentPresentation = presentation
        handler?(presentation)
    }
}

/// A save told in the order the core's outbox tells it (spec §18.3): the republication of every
/// open window mentioning the target, then the outcome, as two separate main-thread deliveries.
/// Android's watch read the core's state between the two and drew a saved heart hollow (#172).
///
/// What this guards is the SWIFT side: the library session's overlay and recorded value, read at
/// each delivery. The reader here is a synchronous fake, so the core's own order -- adopting the
/// acknowledgement into the cache before republishing, so the republication carries the saved
/// value -- is ASSUMED from `MutationOutbox.flush`, not measured by this test; the republication
/// below is scripted to carry that value. Nothing on Apple reads the core's favourite state
/// between the two deliveries, which is the read Android's defect needed.
enum HeartScreen: Sendable {
    /// A track with no cache row -- only ever seen in a queue -- so no screen shows it and the
    /// flush republishes nothing for it. Android's failing case.
    case noScreenShowsTheTrack
    /// An album screen is open on the track: its republication carries the adopted value.
    case anOpenScreenShowsTheTrack
}

@MainActor
func heartThroughASave(on screen: HeartScreen) async throws -> [String] {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    let playback = HeartTestPlayback()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ReaderTestConnector(),
        credentialStore: ReaderTestCredentials(
            persisted: DulcetAccountConnectRequest(
                serverURL: "https://music.example.invalid",
                username: "listener",
                password: "fixture-password",
                allowLocalHTTP: false
            ),
            providerInstanceID: "provider-reader"
        ),
        playbackController: playback,
        librarySession: session
    ))
    let reader = try #require(factory.made.first)
    let id = DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "t-playing")
    // The queue's copy of the track says what the server said when it was queued: not a favourite.
    let queued = DulcetTrack(
        id: id, title: "Playing", credits: [], albumTitle: "Album", duration: .seconds(180),
        mediaSourceID: nil, artwork: DulcetArtwork(seed: "t-playing", palette: .indigoCoral),
        isFavorite: false
    )
    playback.publish(DulcetPlaybackPresentation(status: .ready, nowPlaying: DulcetNowPlaying(
        sessionID: DulcetPlaybackSessionID("session-heart"), current: queued, queue: [queued],
        elapsed: .seconds(3), isPlaying: true, outputName: "Fixture output", volume: 1,
        audioFormat: DulcetAudioFormat(codec: "MP3", sampleRateKilohertz: 44.1)
    )))
    // Held for the whole flush: the screen hears its subscription only while it is alive.
    let screenModel = DulcetLibraryWindowModel(query: .albums(.newest))
    var albumScreen: RecordingWindow?
    if screen == .anOpenScreenShowsTheTrack {
        screenModel.open(in: session)
        albumScreen = try #require(reader.windows.last)
        albumScreen?.publish(window([item("track", "t-playing", favourite: false)]))
        // The control: the screen's publications reach the session, or this case tests nothing.
        #expect(session.knownFavourites[id] == false, "the open screen's publication was not heard")
    }
    var seen: [String] = []
    func look(_ step: String) {
        let heart = store.playingTrackFavourite?.isFavourite
        seen.append("\(step): \(heart.map { $0 ? "filled" : "hollow" } ?? "absent")")
    }
    look("before")
    #expect(store.togglePlayingTrackFavourite())
    look("tapped")
    // A publication while the change waits carries the overlay (rule 2), as the core's does.
    albumScreen?.publish(window([item("track", "t-playing", favourite: true)], sequence: 2))
    look("pending")
    // The flush: republished with the adopted value (or nothing, with no screen) ...
    albumScreen?.publish(window([item("track", "t-playing", favourite: true)], sequence: 3))
    look("republished")
    // ... and only then, as a separate delivery, the outcome.
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: "t-playing", field: "favourite", errorKind: nil))
    look("saved")
    withExtendedLifetime(screenModel) {}
    return seen
}

@Test(arguments: [HeartScreen.noScreenShowsTheTrack, .anOpenScreenShowsTheTrack]) @MainActor
func theSavedHeartOfThePlayingTrackStaysFilledBetweenTheRepublicationAndTheOutcome(
    screen: HeartScreen
) async throws {
    let seen = try await heartThroughASave(on: screen)
    #expect(seen == [
        "before: hollow",
        "tapped: filled",
        "pending: filled",
        "republished: filled",
        "saved: filled",
    ], "the heart of a saved track never shows hollow, at any step of the flush (\(screen))")
}

// MARK: - A reader whose setup failed (§16.18, the Apple paragraph)

/// A connected session whose first reader's setup failed, with one screen and one search open.
@MainActor
private func sessionWhoseSetupFailed() throws -> (
    DulcetLibrarySession, RecordingReaderFactory, ManualReachability, ManualDelays,
    DulcetLibraryWindowModel, DulcetReaderSearchModel
) {
    let factory = RecordingReaderFactory()
    factory.editsPlaylists = true
    let reachability = ManualReachability()
    let delays = ManualDelays()
    let session = DulcetLibrarySession(
        factory: factory, reachability: reachability, foreground: true, delays: delays, reachabilityLog: { _ in })
    session.open(account: readerAccount, mode: .connected)
    let screen = DulcetLibraryWindowModel(query: .artists)
    screen.open(in: session)
    let search = DulcetReaderSearchModel()
    search.open(in: session, query: "abc")
    reachability.report(reachable: true)
    let failed = try #require(factory.made.first)
    failed.finishSetup(failed: true)
    return (session, factory, reachability, delays, screen, search)
}

@Test @MainActor
func tryAgainOnAReaderWhoseSetupFailedMakesANewOneAndEverythingIsWiredToIt() throws {
    let (session, factory, reachability, delays, screen, search) = try sessionWhoseSetupFailed()
    let failed = try #require(factory.made.first as? PlaylistEditingReader)
    #expect(session.readerSetupFailed, "control: the session heard the setup fail")
    let generation = session.readerGeneration

    session.reconnect() // Try Again on any screen
    #expect(!failed.events.contains("reconnect"), "a reader with no session has nothing to reconnect")
    #expect(failed.events.last == "close")
    #expect(factory.made.count == 1, "the new reader waits for the failed one's thread to stop")
    #expect(failed.playlistListenersCancelled == 1, "the failed reader's playlist outcomes are let go")
    failed.finishClose()
    let failedEventsAtClose = failed.events.count

    #expect(factory.made.count == 2)
    let fresh = try #require(factory.made.last as? PlaylistEditingReader)
    #expect(fresh.account == readerAccount, "the same account, retried")
    #expect(factory.foregrounds == [true, true], "made with the foreground as it is now")
    #expect(!session.readerSetupFailed)
    #expect(session.readerGeneration > generation, "screens that follow the generation read again")
    #expect(fresh.events.contains("connect"), "connected exactly as after a first setup")
    #expect(fresh.windows.map(\.query) == [.artists], "the open screen reads from the new reader")
    #expect(screen.window == nil, "and no longer holds what the failed reader published")
    search.open(in: session, query: "abc") // what the search screen does on a new generation
    #expect(fresh.searches.first?.queries == ["abc"], "the open search asks the new reader for the text typed")
    #expect(fresh.outcomeHandler != nil, "favourite outcomes come from the new reader")
    #expect(fresh.playlistListeners == 1 && session.playlists != nil, "playlist outcomes come from the new reader")

    #expect(reachability.started == 2, "reachability is reported to the new reader")
    reachability.report(reachable: true, constrained: true)
    #expect(Array(fresh.events.suffix(2)) == ["setNetworkConstrained(true)", "setOnline(true)"])
    reachability.report(reachable: false)
    delays.advance(by: DulcetLibrarySession.defaultUnreachableGrace)
    #expect(fresh.events.last == "setOnline(false)", "and its unreachable grace is the new reader's")
    #expect(failed.events.count == failedEventsAtClose, "nothing reaches the closed reader: \(failed.events)")

    session.setForeground(false)
    #expect(fresh.events.last == "setForeground(false)", "the foreground is reported to the new reader")
    fresh.finishSetup(failed: false)
    reachability.report(reachable: true)
    session.reconnect()
    #expect(fresh.events.last == "reconnect", "a reader that was built reconnects as before")
    #expect(factory.made.count == 2)
}

@Test @MainActor
func aSetupThatKeepsFailingIsRetriedOnlyWhenSomethingAsksNeverInALoop() throws {
    let (session, factory, reachability, delays, _, _) = try sessionWhoseSetupFailed()
    session.reconnect()
    factory.made[0].finishClose()
    let second = try #require(factory.made.last)
    second.finishSetup(failed: true)
    reachability.report(reachable: true) // the first report to the new reader is not a change
    #expect(session.readerSetupFailed)
    #expect(factory.made.count == 2, "the first reachability report after a setup retries nothing")
    #expect(second.events.last != "close")

    // The network coming back asks for a reconnect, which here is a new reader.
    reachability.report(reachable: false)
    delays.advance(by: DulcetLibrarySession.defaultUnreachableGrace)
    reachability.report(reachable: true)
    #expect(second.events.last == "close", "a regained network retries the setup")
    second.finishClose()
    #expect(factory.made.count == 3)
    let third = try #require(factory.made.last)
    third.finishSetup(failed: true)

    // A return to the foreground reconnects, so it retries too.
    session.setForeground(false)
    session.setForeground(true)
    #expect(third.events.last == "close", "a return to the foreground retries the setup")
    third.finishClose()
    #expect(factory.made.count == 4)
}

@Test @MainActor
func onlyTheCurrentReadersOwnFailureIsASetupToRetry() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let first = try #require(factory.made.first)
    let other = DulcetLibraryReaderAccount(
        providerInstanceID: "provider-other", normalizedServerURL: "https://other.example.invalid",
        username: "listener", password: "other-password", allowLocalHTTP: false)
    // An answer already on its way to the main thread when the swap cancelled the question.
    let lateAnswer = try #require(first.setupCompletion)
    session.open(account: other, mode: .connected)
    lateAnswer(true)
    #expect(!session.readerSetupFailed, "a reader being replaced is not the session's failure")
    first.finishClose()
    lateAnswer(true)
    #expect(!session.readerSetupFailed, "nor is one that has closed")
    let second = try #require(factory.made.last)
    #expect(second.account == other)
    second.finishSetup(failed: false)
    #expect(!session.readerSetupFailed)
    session.reconnect()
    #expect(second.events.last == "reconnect")
    #expect(factory.made.count == 2)
}

@Test @MainActor
func aSearchTryAgainOnASavedAccountWhoseSetupFailedReconnectsTheAccountAndMakesANewReader() throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let (store, session, _) = readerModeStore(
        persisted: DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid", username: "listener",
            password: "fixture-password", allowLocalHTTP: false),
        providerInstanceID: "provider-reader",
        factory: factory,
        connector: connector
    )
    store.navigate(to: .search)
    let model = DulcetReaderSearchModel()
    model.open(in: session, query: "echo")
    let failed = try #require(factory.made.first)
    #expect(session.mode == .deviceOnly, "control: a saved account, not connected in this launch")
    failed.finishSetup(failed: true)
    let search = try #require(failed.searches.first)
    search.publish(DulcetReaderSearchPublication(
        query: "echo", sequence: 1, scope: .deviceServerFailed(.internalFailure, nil), rows: []))

    model.retry { dulcetReaderRetry(store) }
    #expect(search.refreshes == 0, "a search on a reader with no session cannot answer anything new")
    #expect(connector.requests.count == 1, "Try Again on a saved account is its Reconnect")
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Navidrome", normalizedServerURL: "https://music.example.invalid")))
    #expect(failed.events.last == "close", "the reconnect replaces the reader whose setup failed")
    failed.finishClose()
    #expect(factory.made.count == 2)
    #expect(factory.made.last?.account == failed.account, "the same account, retried")
    #expect(session.mode == .connected)
    #expect(factory.made.last?.events.contains("connect") == true)
}

@Test @MainActor
func anAccountChosenWhileTheOldReaderIsStillClosingIsTheOneMade() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let first = try #require(factory.made.first)
    func account(_ id: String) -> DulcetLibraryReaderAccount {
        DulcetLibraryReaderAccount(
            providerInstanceID: id, normalizedServerURL: "https://\(id).example.invalid",
            username: "listener", password: "\(id)-password", allowLocalHTTP: false)
    }
    session.open(account: account("second"), mode: .connected)
    session.open(account: account("third"), mode: .deviceOnly)
    first.finishClose()
    #expect(factory.made.count == 2, "the newest request is made once the old reader has stopped")
    #expect(factory.made.last?.account == account("third"))
    #expect(session.mode == .deviceOnly)
    #expect(factory.made.last?.events.first == "setOnline(false)")
}

/// Set by an observation's onChange, which runs synchronously on the thread making the change.
private final class ObservedChange: @unchecked Sendable {
    var heard = false
}

@Test @MainActor
func aViewThatReadsIsOnlineRedrawsWhenOnlyTheNetworkChanges() throws {
    let (session, reachability, delays, _, _) = try connectedSessionWithManualTime()
    #expect(session.isOnline, "control")
    let lost = ObservedChange()
    withObservationTracking { _ = session.isOnline } onChange: { lost.heard = true }
    reachability.report(reachable: false)
    delays.advance(by: DulcetLibrarySession.defaultUnreachableGrace)
    #expect(!session.isOnline)
    #expect(lost.heard, "isOnline changed with nothing else, and an observer of it heard")

    let regained = ObservedChange()
    withObservationTracking { _ = session.isOnline } onChange: { regained.heard = true }
    reachability.report(reachable: true)
    #expect(session.isOnline)
    #expect(regained.heard, "and again when the network came back")
}
