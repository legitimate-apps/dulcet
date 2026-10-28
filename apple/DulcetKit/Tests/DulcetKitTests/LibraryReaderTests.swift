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
private class RecordingReader: DulcetLibraryReading, DulcetMyLibraryReading, DulcetLibraryDepthReading, DulcetLibrarySongLookingUp {
    var songLookups: [DulcetDiscoverLibraryRef] = []
    var songLookupCompletions: [@MainActor (DulcetReaderItem?) -> Void] = []
    var songLookupCancelled = false
    func lookupLibrarySong(_ reference: DulcetDiscoverLibraryRef, completion: @escaping @MainActor (DulcetReaderItem?) -> Void) -> any DulcetLibraryReaderCancellable {
        songLookups.append(reference); songLookupCompletions.append(completion)
        return RecordingCancellable { self.songLookupCancelled = true }
    }
    var depthHandlers: [@MainActor (DulcetLibraryDepthState) -> Void] = []
    var depthCategories: [(String, String, String)] = []
    var depthPins: [(DulcetReaderItem, Bool)] = []
    var depthSearches: [String] = []
    func readLibraryDepth(_ completion: @escaping @MainActor (DulcetLibraryDepthState) -> Void) { depthHandlers.append(completion) }
    func setLibraryCategory(_ category: String, sort: String, filter: String) -> Bool {
        depthCategories.append((category, sort, filter)); return true
    }
    func setLibraryPin(_ item: DulcetReaderItem, pinned: Bool) -> Bool { depthPins.append((item, pinned)); return true }
    func rememberSearch(_ text: String) -> Bool { depthSearches.append(text); return true }
    func clearRecentSearches() -> Bool { true }

    var libraryHandler: (@MainActor (DulcetMyLibrarySnapshot) -> Void)?
    var libraryMembership: DulcetMyLibraryMembership = .notAdded
    var libraryChanges: [(DulcetFavouriteTarget, Bool)] = []
    var libraryRefreshes = 0
    var libraryCancelled = false
    func subscribeMyLibrary(_ handler: @escaping @MainActor (DulcetMyLibrarySnapshot) -> Void) -> any DulcetLibraryReaderCancellable {
        libraryHandler = handler
        return RecordingCancellable { [weak self] in self?.libraryCancelled = true }
    }
    func refreshMyLibrary() { libraryRefreshes += 1 }
    func myLibraryMembership(_ target: DulcetFavouriteTarget) -> DulcetMyLibraryMembership { libraryMembership }
    func setMyLibraryMembership(_ target: DulcetFavouriteTarget, added: Bool) -> Bool {
        libraryChanges.append((target, added)); libraryMembership = added ? .added : .notAdded
        libraryHandler?(DulcetMyLibrarySnapshot(availability: .available))
        return true
    }
    let account: DulcetLibraryReaderAccount
    private(set) var events: [String] = []
    private(set) var windows: [RecordingWindow] = []
    var onWindowSubscription: (@MainActor (RecordingWindow) -> Void)?
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
        onWindowSubscription?(window)
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

    func rejectServerIdentity() { events.append("rejectIdentity"); setOnline(false) }
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
    var onLoadMore: (@MainActor () -> Void)?

    init(query: DulcetLibraryQuery, publish: @escaping @MainActor (DulcetLibraryWindow) -> Void) {
        self.query = query
        self.publish = publish
    }

    func loadMore() { events.append("loadMore"); onLoadMore?() }
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
    title: String? = "Title",
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
    let ageBelowAccountMessage = DulcetReaderCopy.freshnessLine(
        .cached(.offline, asOf: now.addingTimeInterval(-10)), now: now, includesReason: false
    )
    #expect(ageBelowAccountMessage?.contains(DulcetStrings.readerJustNow) == true)
    #expect(ageBelowAccountMessage?.contains(DulcetStrings.readerReasonOffline) == false)
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
    #expect(DulcetReaderCopy.orderLine(window(items, order: "localView")) == DulcetStrings.libraryLocalOrder)
    #expect(DulcetReaderCopy.orderLine(window(items, freshness: .cached(.offline, asOf: nil), order: "localView")) == DulcetStrings.readerAvailableOffline)
    #expect(DulcetReaderCopy.coverageLine(window(items, coverage: "open", order: "localView")) == DulcetStrings.libraryLoadedScope(items.count))
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
func readerTitlesUseTheSameLocalizedFallbackAsSearch() {
    for title: String? in [nil, "", "  ", "\t\n", "A real title"] {
        let track = item("track", "untitled", title: title)
        let expected = title == "A real title" ? "A real title" : DulcetStrings.readerUntitled
        #expect(track.displayTitle == expected)
        #expect(track.playableTrack?.title == expected)
        #expect(item("track", "unknown-duration", title: title, duration: nil).playableTrack == nil)
    }
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
    #expect(reachability.started == 1, "the saved account watches for network restoration")
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
func rejectedReaderIgnoresHeldSuccessAndRequiresNegotiationBeforeANewLifetime() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    var negotiations = 0
    session.onSavedReconnectRequested = { negotiations += 1 }
    session.open(account: readerAccount, mode: .connected)
    let old = try #require(factory.made.first)
    #expect(old.connectCompletions.count == 1)
    session.rejectServerIdentity()
    #expect(old.events.contains("rejectIdentity"))
    #expect(!session.isOnline)
    #expect(session.connectionFailure == .serverIdentityChanged)
    let refreshes = old.libraryRefreshes
    old.answerConnections(DulcetReaderConnection(
        epochKnown: true, serverReportsNoEpoch: true, discardedPendingChanges: 0, errorKind: nil))
    #expect(!session.isOnline, "A held successful callback cannot reopen rejected authority")
    #expect(session.connectionFailure == .serverIdentityChanged)
    #expect(old.libraryRefreshes == refreshes)
    let reconnects = old.events.filter { $0 == "reconnect" }.count
    session.reconnect()
    #expect(negotiations == 1)
    #expect(old.events.filter { $0 == "reconnect" }.count == reconnects)
    session.open(account: readerAccount, mode: .connected)
    #expect(factory.made.count == 1, "The accepted lifetime must wait for the old reader thread")
    #expect(old.events.last == "close")
    old.finishClose()
    #expect(factory.made.count == 2)
    #expect(factory.made.last !== old)
    #expect(session.connectionFailure == nil)
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
    credentials: ReaderTestCredentials? = nil,
    reachability: ManualReachability? = nil,
    delays: ManualDelays = ManualDelays(),
    identityPolicy: DulcetAccountIdentityPolicy = .exact
) -> (DulcetPresentationStore, DulcetLibrarySession, ReaderTestCredentials) {
    let session = DulcetLibrarySession(factory: factory, reachability: reachability, delays: delays)
    let store = credentials ?? ReaderTestCredentials(persisted: persisted, providerInstanceID: providerInstanceID)
    let presentation = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        credentialStore: store,
        libraryBrowser: browser,
        serverSearch: serverSearch,
        providerInstanceIDFactory: { "provider-new" },
        librarySession: session,
        reconnectDelays: delays,
        identityPolicy: identityPolicy
    ))
    return (presentation, session, store)
}

@Test @MainActor
func theAppOpensCachedListenNowWhileTheSavedAccountReconnects() throws {
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

    #expect(store.selectedDestination == .listenNow)
    #expect(store.readerOwnsLibrary)
    #expect(session.mode == .deviceOnly)
    let reader = try #require(factory.made.first)
    #expect(reader.account == readerAccount)
    #expect(reader.events == ["setOnline(false)"])
    store.navigate(to: .search)
    store.navigate(to: .library)
    #expect(store.snapshot.state == .libraryBrowse, "the reader's library, not the Reconnect wall")
    #expect(connector.requests == [saved], "CONF-10b: saved launch reconnects without a tap")
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
    store.navigate(to: .library)
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
        connector: connector, browser: browser,
        // Production injects SavedServerPolicy, whose common URL parser resolves this legacy
        // raw address. The Kit fixture supplies that known result without duplicating the parser.
        identityPolicy: .init(samePrincipal: { oldURL, oldUser, newURL, newUser in
            oldUser == newUser && oldURL == "music.example.invalid" && newURL == "https://music.example.invalid"
        }, isReplacement: { _, _ in false }))
    store.submitAccountConnection()
    store.navigate(to: .library)
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
func choosingACatalogSidebarSectionLeavesHomeAndKeepsItsOwnNavigationHistory() {
    let factory = RecordingReaderFactory()
    let (store, _, _) = readerModeStore(
        persisted: DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid", username: "listener",
            password: "fixture-password", allowLocalHTTP: false),
        providerInstanceID: "provider-reader", factory: factory
    )
    let album = DulcetReaderRoute.album(DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "album-home"))
    store.navigate(to: .listenNow)
    store.pushReaderPage(album)
    store.selectLibrarySection(.playlists)
    #expect(store.selectedDestination == .browse, "A catalog sidebar choice must leave Listen Now")
    #expect(store.librarySection == .playlists)
    #expect(store.readerPath.isEmpty, "The sidebar opens the chosen section's root")
    store.navigate(to: .listenNow)
    #expect(store.readerPath == [album], "Catalog navigation must preserve Home's own detail stack")
    store.selectLibrarySection(.albums)
    #expect(store.selectedDestination == .browse)
    #expect(store.librarySection == .albums)
    #expect(store.readerPath.isEmpty)
}

@Test @MainActor
func aPageOpenedIsOwedFocusAndBackNamesWhatThePageItLandedOnHadOpened() {
    let factory = RecordingReaderFactory()
    let (store, _, _) = readerModeStore(
        persisted: DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid", username: "listener",
            password: "fixture-password", allowLocalHTTP: false),
        providerInstanceID: "provider-reader",
        factory: factory
    )
    let artist = DulcetReaderRoute.artist(DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "artist-1"))
    let album = DulcetReaderRoute.album(DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "album-9"))
    store.navigate(to: .library)
    store.pushReaderPage(artist)
    #expect(store.readerArrival == artist, "a page just opened is owed focus")
    store.readerArrivalFocused()
    #expect(store.readerArrival == nil)
    store.pushReaderPage(album)
    #expect(store.readerArrival == album)
    #expect(store.readerReturn == nil, "nothing has been returned to while pages only open")

    // Back from the album lands on the artist, whose album item opened it.
    store.goBackInLibrary()
    #expect(store.readerReturn == DulcetReaderReturn(page: artist, opened: album))
    #expect(store.readerArrival == nil, "Back is a return, not an arrival")
    store.readerReturnFocused()
    #expect(store.readerReturn == nil)

    // Back from the artist lands on the section at the root, on the artist.
    store.goBackInLibrary()
    #expect(store.readerReturn == DulcetReaderReturn(page: nil, opened: artist))

    // Opening another page or choosing a section is not a return.
    store.pushReaderPage(album)
    #expect(store.readerReturn == nil)
    store.goBackInLibrary()
    #expect(store.readerReturn == DulcetReaderReturn(page: nil, opened: album))
    store.selectLibrarySection(.artists)
    #expect(store.readerReturn == nil)

    // A pop of several pages at once names the page it lands on and the one that page opened.
    store.pushReaderPage(artist)
    store.pushReaderPage(album)
    store.popReader(to: [])
    #expect(store.readerReturn == DulcetReaderReturn(page: nil, opened: artist))
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

/// SwiftUI can report the search screen gone while it is still showing: on Apple TV, choosing
/// Search with a Library page pushed ran the screen's onAppear and then its onDisappear a
/// millisecond later, with no second onAppear, and the field went on taking text. The screen must
/// still answer what is typed into it, not wait on a subscription its own disappearance closed.
@Test @MainActor
func aSearchScreenReportedGoneWhileItIsStillTypedIntoAnswersWhatIsTyped() throws {
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
    let reader = try #require(factory.made.first)
    let first = try #require(reader.searches.first)
    // The disappearance that arrives while the screen is still on show.
    model.close()
    #expect(first.closed, "control: the disappearance closed the subscription")

    store.searchQuery = "Thr"
    model.updateQuery(store.searchQuery)
    try #require(reader.searches.count == 2, "the screen subscribed again for what was typed into it")
    let answering = try #require(reader.searches.last)
    #expect(answering.queries.last == "Thr")
    answering.publish(DulcetReaderSearchPublication(
        query: "Thr", sequence: 1, scope: .deviceOffline(nil), rows: []))
    #expect(model.current?.query == "Thr")
    #expect(DulcetReaderSearchContent(query: model.query, publication: model.current, onActivate: { _ in })
        .presentation != .waiting, "the screen answers from this device instead of waiting on the server")

    // The screen's own disappearance still closes what it opened.
    model.close()
    #expect(answering.closed)
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


/// Launch negotiation is shared by automatic and manual triggers. Its transient backoff never
/// reads on a tight loop, and it stops in the background or on loss of the network.
@Test @MainActor
func savedAutomaticReconnectKeepsTheOfflinePresentationWithoutAReader() {
    let connector = ReaderTestConnector()
    let delays = ManualDelays()
    let saved = DulcetAccountConnectRequest(serverURL: readerAccount.normalizedServerURL,
        username: readerAccount.username, password: readerAccount.password, allowLocalHTTP: false)
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        credentialStore: ReaderTestCredentials(persisted: saved, providerInstanceID: "provider-reader"),
        reconnectDelays: delays
    ))
    #expect(connector.requests == [saved])
    connector.complete(.failed(DulcetAccountFailurePresentation(kind: .transportUnreachable,
        serverName: "Music", title: "Offline", message: "Offline", recovery: "Reconnect",
        automaticRetryFloor: .zero)))
    #expect(store.snapshot.state == .accountSavedDisconnected)
    #expect(store.snapshot.accountConnection == .saved(serverName: "music.example.invalid"))
    #expect(!store.snapshot.accountConnected)
    delays.advance(by: .milliseconds(1999))
    #expect(connector.requests.count == 1)
    delays.advance(by: .milliseconds(1))
    #expect(connector.requests.count == 2, "the offline presentation still schedules backoff")
    connector.complete(.failed(DulcetAccountFailurePresentation(kind: .invalidCredentials,
        serverName: "Music", title: "Sign in", message: "Sign in", recovery: "Reconnect")))
    #expect(store.snapshot.state == .accountErrorAuthentication)
    delays.advance(by: .seconds(3600))
    #expect(connector.requests.count == 2, "authentication still requires recovery")
}

@Test @MainActor
func savedAutomaticReconnectJoinsBacksOffAndRespondsToLifecycle() throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let delays = ManualDelays()
    let reachability = ManualReachability()
    let saved = DulcetAccountConnectRequest(serverURL: readerAccount.normalizedServerURL,
        username: readerAccount.username, password: readerAccount.password, allowLocalHTTP: false)
    let (store, session, _) = readerModeStore(persisted: saved,
        providerInstanceID: "provider-reader", factory: factory, connector: connector,
        reachability: reachability, delays: delays)
    #expect(store.selectedDestination == .listenNow)
    #expect(session.mode == .deviceOnly)
    #expect(connector.requests.count == 1)
    store.submitAccountConnection()
    session.setForeground(false)
    session.setForeground(true)
    #expect(connector.requests.count == 1, "manual and foreground join the launch attempt")
    func fail(_ kind: DulcetAccountFailureKind, floor: Duration? = .zero) {
        connector.complete(.failed(DulcetAccountFailurePresentation(kind: kind,
            serverName: "Music", title: "Offline", message: "Offline", recovery: "Reconnect",
            automaticRetryFloor: floor)))
    }
    fail(.transportUnreachable)
    #expect(store.selectedDestination == .listenNow, "failure never takes away the library")
    delays.advance(by: .milliseconds(1999))
    #expect(connector.requests.count == 1)
    delays.advance(by: .milliseconds(1))
    #expect(connector.requests.count == 2)
    fail(.transportTimeout)
    delays.advance(by: .seconds(3))
    #expect(connector.requests.count == 2)
    delays.advance(by: .seconds(1))
    #expect(connector.requests.count == 3, "the next backoff doubled to four seconds")
    fail(.knownServerError, floor: .seconds(400))
    delays.advance(by: .seconds(299))
    #expect(connector.requests.count == 3)
    delays.advance(by: .seconds(1))
    #expect(connector.requests.count == 4, "Retry-After is capped at five minutes")
    fail(.transportUnreachable)
    session.setForeground(false)
    delays.advance(by: .seconds(60))
    #expect(connector.requests.count == 4, "background cancels the timer")
    session.setForeground(true)
    #expect(connector.requests.count == 5)
    fail(.transportUnreachable)
    delays.advance(by: .milliseconds(1999))
    #expect(connector.requests.count == 5)
    delays.advance(by: .milliseconds(1))
    #expect(connector.requests.count == 6, "foreground return reset the backoff to two seconds")
    reachability.report(reachable: false)
    delays.advance(by: .seconds(5)) // the shared handoff grace expires while negotiation is running
    fail(.transportUnreachable)
    delays.advance(by: .seconds(60))
    #expect(connector.requests.count == 6, "unreachable suppresses the timer")
    reachability.report(reachable: true)
    #expect(connector.requests.count == 7)
    connector.complete(.connected(DulcetConnectedAccountSummary(serverName: "Music",
        normalizedServerURL: saved.serverURL)))
    #expect(store.snapshot.accountConnected)
    #expect(session.mode == .connected)
    #expect(factory.made.count == 1, "the same reader keeps its device rows")
}

@Test @MainActor
func savedAutomaticReconnectAuthAndTrustFailuresHaveNoTimer() throws {
    for kind in [DulcetAccountFailureKind.invalidCredentials, .tlsUntrusted,
                 .notASubsonicServer, .redirectRejected] {
        let connector = ReaderTestConnector()
        let delays = ManualDelays()
        let saved = DulcetAccountConnectRequest(serverURL: readerAccount.normalizedServerURL,
            username: readerAccount.username, password: readerAccount.password, allowLocalHTTP: false)
        let (store, _, _) = readerModeStore(persisted: saved,
            providerInstanceID: "provider-reader", factory: RecordingReaderFactory(),
            connector: connector, delays: delays)
        connector.complete(.failed(DulcetAccountFailurePresentation(kind: kind,
            serverName: "Music", title: "Cannot connect", message: "Fix the account", recovery: "Reconnect")))
        delays.advance(by: .seconds(3600))
        #expect(connector.requests.count == 1, "auth/trust/protocol failures never retry on a timer")
        store.selectDestination(.settings)
        guard case let .failed(failure) = store.snapshot.accountConnection else {
            Issue.record("Connection must retain the failure"); continue
        }
        #expect(failure.kind == kind)
        store.submitAccountConnection()
        #expect(connector.requests.count == 2, "manual recovery remains available")
    }
}


@Test @MainActor
func editingASavedAccountPausesAutomaticRetriesAndRejectsLateCompletion() {
    let connector = ReaderTestConnector()
    let delays = ManualDelays()
    let saved = DulcetAccountConnectRequest(serverURL: readerAccount.normalizedServerURL,
        username: readerAccount.username, password: readerAccount.password, allowLocalHTTP: false)
    let (store, session, _) = readerModeStore(persisted: saved,
        providerInstanceID: "provider-reader", factory: RecordingReaderFactory(),
        connector: connector, delays: delays)
    store.selectDestination(.settings)
    store.accountPassword = "new-draft-password"
    connector.complete(.connected(DulcetConnectedAccountSummary(serverName: "Music",
        normalizedServerURL: saved.serverURL)))
    #expect(!store.snapshot.accountConnected, "superseded negotiation cannot authorize the reader")
    #expect(store.accountPassword == "new-draft-password")
    session.setForeground(false); session.setForeground(true)
    delays.advance(by: .seconds(3600))
    #expect(connector.requests.count == 1, "a draft waits for manual submission")
    store.submitAccountConnection()
    #expect(connector.requests.last?.password == "new-draft-password")
    connector.complete(.failed(DulcetAccountFailurePresentation(kind: .transportUnreachable,
        serverName: "Music", title: "Offline", message: "Offline", recovery: "Reconnect",
        automaticRetryFloor: .zero)))
    delays.advance(by: .seconds(2))
    #expect(connector.requests.count == 3, "manual saved-account failure keeps transient retries")
    store.accountPassword = "another-draft"
    delays.advance(by: .seconds(60))
    #expect(connector.requests.count == 3, "editing cancels any pending or active attempt")
}

@Test @MainActor
func browseIsStableAcrossServerCapabilitiesAndKeepsEachNavigationStack() throws {
    let factory = RecordingReaderFactory()
    let saved = DulcetAccountConnectRequest(serverURL: "https://music.example.invalid", username: "listener", password: "fixture-password", allowLocalHTTP: false)
    let (store, session, _) = readerModeStore(persisted: saved, providerInstanceID: "provider-reader", factory: factory)
    let reader = try #require(factory.made.first)
    let baseline: [DulcetSidebarDestination] = [.library, .search, .settings]
    #expect(store.destinations(baseline) == [.library, .browse, .search, .settings])
    reader.libraryHandler?(DulcetMyLibrarySnapshot(availability: .unavailable))
    #expect(store.destinations(baseline) == [.library, .browse, .search, .settings])
    #expect(!session.toggleMyLibrary(DulcetFavouriteTarget(kind: .album, id: DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "album"))))
    #expect(reader.libraryChanges.isEmpty)
    reader.libraryHandler?(DulcetMyLibrarySnapshot(availability: .available))
    #expect(store.destinations(baseline) == [.library, .browse, .search, .settings])
    store.navigate(to: .browse)
    let album = DulcetReaderRoute.album(DulcetProviderItemID(providerInstanceID: "provider-reader", rawID: "album"))
    store.pushReaderPage(album)
    #expect(store.selectedDestination == .browse)
    #expect(store.readerPath == [album])
    store.navigate(to: .library)
    #expect(store.readerPath.isEmpty)
    store.navigate(to: .browse)
    #expect(store.readerPath == [album])
    store.navigate(to: .browse)
    #expect(store.readerPath.isEmpty)
    store.navigate(to: .search)
    store.showReaderPage(album)
    #expect(store.selectedDestination == .library)
    #expect(store.readerPath == [album])
    store.resetReaderNavigation()
    #expect(store.readerPath.isEmpty)
    store.navigate(to: .browse)
    #expect(store.readerPath.isEmpty)
}

@Test @MainActor
func myLibraryMembershipUsesOnlyTheCurrentAccountAndClosesItsPublication() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .deviceOnly)
    let reader = try #require(factory.made.first)
    reader.libraryHandler?(DulcetMyLibrarySnapshot(availability: .available))
    let target = DulcetFavouriteTarget(kind: .album, id: DulcetProviderItemID(providerInstanceID: readerAccount.providerInstanceID, rawID: "album"))
    #expect(session.toggleMyLibrary(target))
    #expect(reader.libraryChanges.last?.1 == true)
    #expect(session.myLibraryMembership(target) == .added)
    #expect(session.toggleMyLibrary(target))
    #expect(reader.libraryChanges.last?.1 == false)
    reader.libraryMembership = .partial
    #expect(session.toggleMyLibrary(target))
    #expect(reader.libraryChanges.last?.1 == true)
    let foreign = DulcetFavouriteTarget(kind: .album, id: DulcetProviderItemID(providerInstanceID: "another-account", rawID: "album"))
    #expect(!session.toggleMyLibrary(foreign))
    #expect(!session.setMyLibraryMembership(foreign, added: false))
    #expect(reader.libraryChanges.count == 3)
    session.close()
    #expect(reader.libraryCancelled)
    #expect(session.myLibrary.availability == .unknown)
    reader.libraryHandler?(DulcetMyLibrarySnapshot(availability: .available))
    #expect(session.myLibrary.availability == .unknown)
    #expect(DulcetFavouriteOutcome(kind: "notSaved", targetKind: "album", rawID: "album", field: "inLibrary", errorKind: "forbidden").field == .inLibrary)
}

// External surfaces hold transient subscriptions only for their request, and cannot cross accounts.
@MainActor
private final class SystemActionTestSource: DulcetDataSource, DulcetLibrarySessionProviding {
    let fixture = DulcetDeterministicDataSource(initialState: .nowPlaying)
    let librarySession: DulcetLibrarySession?
    var queues: [DulcetPlaybackQueueIntent] = []
    init(_ session: DulcetLibrarySession) { librarySession = session }
    var currentSnapshot: DulcetSnapshot { fixture.currentSnapshot }
    func setSnapshotHandler(_ handler: @escaping @MainActor (DulcetSnapshot) -> Void) { fixture.setSnapshotHandler(handler) }
    func send(_ action: DulcetPresentationAction) {
        if case let .playTracks(intent) = action { queues.append(intent) }
        fixture.send(action)
    }
}

@Test @MainActor
func systemSurfaceWindowClosesAfterContentAndOnCancellation() async throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let store = DulcetPresentationStore(source: SystemActionTestSource(session))
    let actions = DulcetSystemActions(store: store)
    let reader = try #require(factory.made.first)
    let request = Task { try await actions.window(.albums(.recent)) }
    await Task.yield()
    let subscription = try #require(reader.windows.last)
    subscription.publish(window([item("album", "album/opaque:?", title: "A")]))
    let publication = try await request.value
    #expect(publication.items.first?.id.rawID == "album/opaque:?")
    #expect(subscription.events.last == "close")
    let cancelled = Task { try await actions.window(.playlists) }
    await Task.yield()
    let pending = try #require(reader.windows.last)
    cancelled.cancel()
    do { _ = try await cancelled.value; Issue.record("cancelled request succeeded") } catch is CancellationError {} catch { Issue.record("wrong cancellation error") }
    #expect(pending.events.last == "close")
}

@Test @MainActor
func systemSurfaceRejectsAnAccountChangeWhileResolving() async throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let store = DulcetPresentationStore(source: SystemActionTestSource(session))
    let actions = DulcetSystemActions(store: store)
    let reader = try #require(factory.made.first)
    let request = Task { try await actions.window(.favourites) }
    await Task.yield()
    session.close()
    reader.finishClose()
    do { _ = try await request.value; Issue.record("old account was accepted") }
    catch DulcetSystemActions.Failure.changedAccount {} catch { Issue.record("wrong account error: \(error)") }
    #expect(reader.windows.last?.events.last == "close")
}

@Test @MainActor
func systemSurfaceSnapshotRoundTripsOnlyDisplayMetadata() throws {
    let name = "dulcet.surface.test." + UUID().uuidString
    let defaults = try #require(UserDefaults(suiteName: name))
    defer { defaults.removePersistentDomain(forName: name) }
    let snapshot = DulcetSurfaceSnapshot(title: "A Song", subtitle: "An Album", isPlaying: true,
        recent: [.init(id: "opaque/provider/song", title: "A Song")])
    snapshot.write(to: defaults)
    #expect(DulcetSurfaceSnapshot.read(from: defaults) == snapshot)
    #expect(DulcetSurfaceSnapshot.groupID(bundleID: "com.legitimateapps.dulcet.dev.widgets") == "group.com.legitimateapps.dulcet.dev")
    #expect(DulcetSurfaceSnapshot.groupID(bundleID: "com.legitimateapps.dulcet.widgets") == "group.com.legitimateapps.dulcet")
    #expect(DulcetSystemActions.sameName("Beyoncé", "  beyonce "))
    #expect(!DulcetSystemActions.sameName("Song", "Song (Live)"))
}

@Test @MainActor
func systemSurfaceReconnectsSavedAccountFromBackgroundWithoutUsingEditedFields() throws {
    let factory = RecordingReaderFactory()
    let connector = ReaderTestConnector()
    let saved = DulcetAccountConnectRequest(serverURL: readerAccount.normalizedServerURL,
        username: readerAccount.username, password: readerAccount.password, allowLocalHTTP: false)
    let session = DulcetLibrarySession(factory: factory, foreground: false)
    let source = DulcetAccountDataSource(connector: connector,
        credentialStore: ReaderTestCredentials(persisted: saved, providerInstanceID: "provider-reader"),
        librarySession: session)
    let store = DulcetPresentationStore(source: source)
    #expect(connector.requests.isEmpty, "background restoration does not negotiate automatically")
    store.prepareForExternalPlayback()
    #expect(connector.requests == [saved], "an explicit external command negotiates using saved credentials")
    store.prepareForExternalPlayback()
    #expect(connector.requests.count == 1, "concurrent commands join the connection")
    connector.complete(.connected(DulcetConnectedAccountSummary(serverName: "Music", normalizedServerURL: readerAccount.normalizedServerURL)))
    #expect(store.snapshot.accountConnection.isConnected)
    #expect(session.account?.providerInstanceID == "provider-reader")
    #expect(factory.foregrounds == [false])
}

@Test @MainActor
func systemSurfaceRecentReplayUsesOpaqueIdentityForAOneLetterCachedTitle() async throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let source = SystemActionTestSource(session)
    let store = DulcetPresentationStore(source: source)
    let actions = DulcetSystemActions(store: store)
    let reader = try #require(factory.made.first)
    let request = Task { try await actions.playRecent(provider: "provider-reader", rawID: "song/opaque:?", name: "A") }
    await Task.yield()
    let subscription = try #require(reader.searches.last)
    let row = try #require(DulcetReaderSearchRow(kind: "track", providerInstanceID: "provider-reader",
        rawID: "song/opaque:?", title: "A", credits: [], albumTitle: nil, year: nil,
        durationMilliseconds: 60_000, discNumber: nil, trackNumber: nil, sourceContainer: "Mp3",
        mediaSourceID: nil, artworkKey: nil, source: "device", favourite: false, rating: nil,
        playability: "streamable"))
    subscription.publish(.init(query: "A", sequence: 1, scope: .deviceWhileServerPending, rows: [row]))
    try await request.value
    #expect(source.queues.last?.tracks.map(\.id.rawID) == ["song/opaque:?"])
    #expect(subscription.closed)
}

@Test @MainActor
func systemSurfaceContinuationLoadsPastTheFirstPageAndClosesItsWindow() async throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let store = DulcetPresentationStore(source: SystemActionTestSource(session))
    let actions = DulcetSystemActions(store: store)
    let reader = try #require(factory.made.first)
    let query = DulcetLibraryQuery.albums(.alphabeticalByName)
    reader.onWindowSubscription = { subscription in
        guard subscription.query == query else { return }
        // The second page exists only after production asks for it. Waiting for the request
        // itself makes executor load irrelevant and cannot pass when continuation is missing.
        subscription.onLoadMore = { [weak subscription] in
            subscription?.publish(window([item("album", "1"), item("album", "2"), item("album", "3")],
                                         coverage: "open", sequence: 2))
        }
        subscription.publish(window([item("album", "1")], coverage: "open"))
    }
    let answer = try await actions.window(query, minimumCount: 3)
    let subscription = try #require(reader.windows.last(where: { $0.query == query }))
    #expect(answer.items.map(\.id.rawID) == ["1", "2", "3"])
    #expect(subscription.events == ["viewport(0,0)", "loadMore", "close"])
}

@MainActor @Test
func libraryDepthIgnoresOldAccountAnswersAndKeepsStateOnAStorageFailure() {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .deviceOnly)
    let first = factory.made[0]
    let oldAnswer = first.depthHandlers[0]
    oldAnswer(DulcetLibraryDepthState(sorts: ["albums": "artist"], pins: ["album\u{0}one"], recentSearches: ["First"]))
    #expect(session.libraryDepth.recentSearches == ["First"])
    session.refreshLibraryDepth()
    first.depthHandlers.last?(DulcetLibraryDepthState(errorKind: "internalFailure"))
    #expect(session.libraryDepth.recentSearches == ["First"])
    #expect(session.notice != nil)

    let other = DulcetLibraryReaderAccount(providerInstanceID: "another-provider", normalizedServerURL: "https://other.example.invalid",
                                          username: "another-listener", password: "fixture-password", allowLocalHTTP: false)
    session.open(account: other, mode: .deviceOnly)
    first.finishClose()
    #expect(factory.made.count == 2)
    #expect(session.libraryDepth.pins.isEmpty)
    #expect(session.libraryDepth.recentSearches.isEmpty)
    oldAnswer(DulcetLibraryDepthState(pins: ["album\u{0}one"], recentSearches: ["Late old account"]))
    #expect(session.libraryDepth.recentSearches.isEmpty)
    session.toggleLibraryPin(item("album", "foreign"))
    #expect(factory.made[1].depthPins.isEmpty)
}

@MainActor @Test
func libraryDepthCategoryMutationKeepsOtherPreferenceAndRefreshesFromCore() {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .deviceOnly)
    let reader = factory.made[0]
    reader.depthHandlers[0](DulcetLibraryDepthState(sorts: ["songs": "artist"], filters: ["songs": "downloaded"]))
    session.setLibraryCategory("songs", sort: "mostPlayed")
    #expect(reader.depthCategories.count == 1)
    #expect(reader.depthCategories[0].0 == "songs")
    #expect(reader.depthCategories[0].1 == "mostPlayed")
    #expect(reader.depthCategories[0].2 == "downloaded")
    #expect(reader.depthHandlers.count == 2)
    let pinned = item("album", "one")
    session.toggleLibraryPin(pinned)
    #expect(reader.depthPins.last?.1 == true)
    reader.depthHandlers.last?(DulcetLibraryDepthState(pins: ["album\u{0}one"]))
    session.toggleLibraryPin(pinned)
    #expect(reader.depthPins.last?.1 == false)
}

@MainActor @Test
func libraryDepthRapidChangesKeepAcceptedWritesAndIgnoreOlderReads() {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .deviceOnly)
    let reader = factory.made[0]
    reader.depthHandlers[0](DulcetLibraryDepthState(sorts: ["songs": "title"], filters: ["songs": "all"]))
    session.setLibraryCategory("songs", sort: "artist")
    let olderRead = reader.depthHandlers.last!
    session.setLibraryCategory("songs", filter: "downloaded")
    #expect(reader.depthCategories.last?.1 == "artist")
    #expect(reader.depthCategories.last?.2 == "downloaded")
    reader.depthHandlers.last?(DulcetLibraryDepthState(sorts: ["songs": "artist"], filters: ["songs": "downloaded"]))
    olderRead(DulcetLibraryDepthState(sorts: ["songs": "artist"], filters: ["songs": "all"]))
    #expect(session.libraryDepth.filters["songs"] == "downloaded")

    let pinned = item("album", "one")
    session.toggleLibraryPin(pinned)
    let pinRead = reader.depthHandlers.last!
    session.toggleLibraryPin(pinned)
    #expect(reader.depthPins.map { $0.1 } == [true, false])
    reader.depthHandlers.last?(DulcetLibraryDepthState())
    pinRead(DulcetLibraryDepthState(pins: ["album\u{0}one"]))
    #expect(session.libraryDepth.pins.isEmpty)
}

@MainActor @Test
func shuffleResolutionWaitsForANewServerSampleRatherThanPlayingTheCachedQueue() {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .deviceOnly)
    var delivered: [String] = []
    session.resolveTracks(of: .shuffleSongs, waitForFresh: true) { tracks, _ in delivered = tracks.map(\.id.rawID) }
    let subscription = factory.made[0].windows.last!
    subscription.publish(window([item("track", "old-random-sample")], freshness: .cached(.revalidating, asOf: nil)))
    #expect(delivered.isEmpty)
    #expect(!subscription.events.contains("close"))
    subscription.publish(window([item("track", "fresh-random-sample")], sequence: 2))
    #expect(delivered == ["fresh-random-sample"])
    #expect(subscription.events.contains("close"))
}

@Test @MainActor
func systemSurfacePinsProjectOnlyCurrentAccountMetadataAndRemoveUnpinnedItems() throws {
    let album = item("album", "opaque:/?album", title: "Pinned Album")
    let song = item("track", "opaque:/?song", title: "Pinned Song")
    let rows = window([album, song])
    let depth = DulcetLibraryDepthState(pins: ["album\u{0}opaque:/?album", "track\u{0}opaque:/?song"])
    let projected = DulcetSurfaceSnapshot.pinnedItems(window: rows, providerID: "provider-reader", depth: depth)
    #expect(projected.map(\.kind) == ["album", "track"])
    #expect(projected.map(\.rawID) == ["opaque:/?album", "opaque:/?song"])
    #expect(DulcetSurfaceSnapshot.pinnedItems(window: rows, providerID: "other", depth: depth).isEmpty)
    #expect(DulcetSurfaceSnapshot.pinnedItems(window: rows, providerID: nil, depth: depth).isEmpty)
    #expect(DulcetSurfaceSnapshot.pinnedItems(window: rows, providerID: "provider-reader", depth: .init()).isEmpty)
    let snapshot = DulcetSurfaceSnapshot(pinned: projected)
    #expect(try JSONDecoder().decode(DulcetSurfaceSnapshot.self, from: JSONEncoder().encode(snapshot)) == snapshot)
    var old = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(snapshot)) as? [String: Any])
    old.removeValue(forKey: "pinned")
    #expect(try JSONDecoder().decode(DulcetSurfaceSnapshot.self, from: JSONSerialization.data(withJSONObject: old)).pinned == nil)
}

@Test @MainActor
func systemSurfacePinnedReplayRevalidatesOpaqueIdentityAndRejectsAnUnpin() async throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let source = SystemActionTestSource(session)
    let store = DulcetPresentationStore(source: source)
    let actions = DulcetSystemActions(store: store)
    let reader = try #require(factory.made.first)
    reader.depthHandlers[0](.init(pins: ["track\u{0}opaque-song"]))
    let request = Task { try await actions.playPinned(provider: "provider-reader", rawID: "opaque-song", kind: "track") }
    await Task.yield()
    let subscription = try #require(reader.windows.last)
    subscription.publish(window([item("track", "opaque-song", title: "A")]))
    try await request.value
    #expect(source.queues.last?.tracks.map(\.id.rawID) == ["opaque-song"])
    #expect(subscription.events.last == "close")
    session.toggleLibraryPin(item("track", "opaque-song"))
    let unpinned = Task { try await actions.playPinned(provider: "provider-reader", rawID: "opaque-song", kind: "track") }
    await Task.yield()
    reader.windows.last?.publish(window([item("track", "opaque-song", title: "A")]))
    do { try await unpinned.value; Issue.record("stale widget played an unpinned item") }
    catch DulcetSystemActions.Failure.unavailable {} catch { Issue.record("wrong unpin error: \(error)") }
    #expect(source.queues.count == 1)
    do { try await actions.playPinned(provider: "another-account", rawID: "opaque-song", kind: "track"); Issue.record("foreign pin played") }
    catch DulcetSystemActions.Failure.changedAccount {} catch { Issue.record("wrong account error: \(error)") }
}

#if os(iOS) || os(macOS)
@Test @MainActor
func wishlistMetadataUsesTheReaderStackAndReturnsToItsPendingList() throws {
    let saved = DulcetAccountConnectRequest(serverURL: "https://music.example.invalid", username: "listener", password: "fixture-password", allowLocalHTTP: false)
    let (store, _, _) = readerModeStore(persisted: saved, providerInstanceID: "provider-reader", factory: RecordingReaderFactory())
    store.navigate(to: .library)
    store.pushReaderPage(.wishlist)
    let metadata = DulcetReaderRoute.discover(.album(mbid: "external-album", releaseId: "selected-edition"))
    store.pushReaderPage(metadata)
    #expect(store.readerPath == [.wishlist, metadata])
    store.navigate(to: .search)
    #expect(store.readerPath(for: .library) == [.wishlist, metadata], "The hidden tab must not be told to pop its retained Wishlist/detail")
    #expect(store.readerPath(for: .search).isEmpty)
    store.navigate(to: .library)
    #expect(store.readerPath == [.wishlist, metadata], "Changing the shell's column arrangement must retain the stored item and back route")
    store.goBackInLibrary()
    #expect(store.readerPath == [.wishlist])
    #expect(store.canGoBackInLibrary)
    store.goBackInLibrary()
    #expect(store.readerPath.isEmpty)
}
#endif


@Test @MainActor func aNewDetailStackCannotEraseAnUnpresentedReaderRoute() {
    let saved = DulcetAccountConnectRequest(serverURL: "https://music.example.invalid", username: "listener", password: "fixture-password", allowLocalHTTP: false)
    let (store, _, _) = readerModeStore(persisted: saved, providerInstanceID: "provider-reader", factory: RecordingReaderFactory())
    store.selectDestination(.search)
    let album = DulcetReaderRoute.album(.init(providerInstanceID: "fixture", rawID: "held-album"))
    // Native iPad NAV 12–17: Search -> Library -> [album], then a new stack writes [].
    store.showReaderPage(album)
    store.popReaderFromStack(to: [], presentedRoutes: [])
    #expect(store.selectedDestination == .library)
    #expect(store.readerPath == [album], "A new stack's initial write is not a person's Back")
    // Once the destination has appeared, the same empty prefix is a real Back.
    store.popReaderFromStack(to: [], presentedRoutes: [album])
    #expect(store.readerPath.isEmpty)
}

#if os(iOS) || os(macOS)
@Test @MainActor func wishlistAlwaysOpensInLibraryFromBrowseOrListenNow() throws {
    let factory = RecordingReaderFactory()
    let saved = DulcetAccountConnectRequest(serverURL: "https://music.example.invalid", username: "listener", password: "fixture-password", allowLocalHTTP: false)
    let (store, _, _) = readerModeStore(persisted: saved, providerInstanceID: "provider-reader", factory: factory)
    let reader = try #require(factory.made.first)
    reader.libraryHandler?(DulcetMyLibrarySnapshot(availability: .available))
    for origin in [DulcetSidebarDestination.browse, .listenNow] {
        store.navigate(to: origin)
        store.showReaderPage(.wishlist)
        #expect(store.selectedDestination == .library, "Design §6: Library › Wishlist, including a sidebar choice from Browse")
        #expect(store.readerPath.first == .wishlist)
    }
}


@Test @MainActor func discoverSongPlaybackUsesAnExactScopedLookupWithoutATitleSearch() throws {
    let factory = RecordingReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    session.open(account: readerAccount, mode: .connected)
    let source = SystemActionTestSource(session)
    let store = DulcetPresentationStore(source: source)
    let reader = try #require(factory.made.first)
    let ref = DulcetDiscoverLibraryRef(providerInstanceId: readerAccount.providerInstanceID, type: "song", id: "opaque-exact")
    store.openDiscoverLibrary(ref)
    #expect(reader.songLookups == [ref])
    #expect(reader.searches.isEmpty, "No title or minimum query length is involved")
    reader.songLookupCompletions[0](item("track", "wrong-id", title: "A"))
    #expect(source.queues.isEmpty, "A different local ID must never play")
    store.openDiscoverLibrary(ref)
    store.openDiscoverLibrary(ref)
    reader.songLookupCompletions[1](item("track", "opaque-exact", title: "Obsolete"))
    #expect(source.queues.isEmpty, "A cancelled previous lookup cannot activate the player")
    reader.songLookupCompletions[2](item("track", "opaque-exact", title: "A"))
    #expect(source.queues.first?.tracks.first?.title == "A")
    #expect(source.queues.first?.tracks.first?.id.rawID == "opaque-exact")
    store.openDiscoverLibrary(ref)
    let late = reader.songLookupCompletions[3]
    session.close()
    store.resetReaderNavigation()
    #expect(reader.songLookupCancelled)
    late(item("track", "opaque-exact", title: "A"))
    #expect(source.queues.count == 1, "An account close revokes a late exact lookup too")
}
#endif

@Test @MainActor func anAlbumPushNeverExposesAnEmptyPathWhileItsPresentationCatchesUp() {
    let saved = DulcetAccountConnectRequest(serverURL: "https://music.example.invalid", username: "listener", password: "fixture-password", allowLocalHTTP: false)
    let (store, _, _) = readerModeStore(persisted: saved, providerInstanceID: "provider-reader", factory: RecordingReaderFactory())
    store.selectDestination(.browse)
    typealias Presentation = DulcetReaderStackPresentation<DulcetReaderRoute, String>
    var presentation = Presentation()
    let section = DulcetReaderRoute.section(.albums)
    store.pushReaderPage(section)
    presentation.request(.init(context: "account-A/browse", path: store.readerPath))
    presentation.appeared(section)
    let album = DulcetReaderRoute.album(.init(providerInstanceID: "provider-reader", rawID: "held-album"))
    store.pushReaderPage(album)
    let request = Presentation.Request(context: "account-A/browse", path: store.readerPath)
    // SwiftUI reads the new body before onChange acknowledges the request. That read must
    // not turn [albums, album] into a root, which later writes back a stale shorter path.
    let displayed = presentation.displayedPath(for: request)
    #expect(displayed == [section, album])
    presentation.request(request)
    presentation.appeared(album)
    #expect(store.readerPath == [section, album])
    #expect(presentation.displayedPath(for: request) == [section, album])
    // A new account/destination still waits for its own request registration.
    #expect(presentation.displayedPath(for: .init(context: "account-B/library", path: [album])).isEmpty)
    // The ordinary Back transition remains accepted after actual presentation.
    let accepted = presentation.wrote([section])
    #expect(accepted)
    store.popReaderFromStack(to: [section], presentedRoutes: presentation.presentedRoutes)
    #expect(store.readerPath == [section])
}
