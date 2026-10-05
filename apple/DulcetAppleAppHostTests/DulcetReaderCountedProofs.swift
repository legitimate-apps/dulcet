import CryptoKit
import DulcetCore
import Foundation
import XCTest
@testable import DulcetKit
#if os(macOS)
@testable import DulcetMac
#else
@testable import Dulcet_DEV
#endif

/// The production reader inside the app host (macOS, or iOS and iPadOS), through the production Kotlin facade and
/// database, with every request it sends counted by `tools/conformance-env/lyrics-fault-proxy` in
/// front of the disposable server (CONF-76, CONF-77, CONF-86; spec §16.9, §16.14). The account's
/// server is the proxy, so the proxy's log is everything the app sent; the test reads the server
/// itself directly. Requests are counted, never timed.
///
/// The credential store is in memory -- an ad-hoc signed host cannot reach the data-protection
/// Keychain -- so what these prove starts at the store boundary.
@MainActor
final class DulcetReaderCountedProofs {
    private unowned let testCase: XCTestCase
    /// The destination, as the proofs name it in their observation lines and database names.
    private let platform: String

    init(_ testCase: XCTestCase, platform: String) {
        self.testCase = testCase
        self.platform = platform
    }

    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"
    private let fixtureAlbums: Set<String> = ["Double Lines", "Threshold Boundary"]

    // MARK: CONF-76

    /// An open with cached content publishes it before any answer and never a loading state; a
    /// grid-only album paints its cached header first online; offline, a never-opened album is
    /// unavailable.
    ///
    /// 1. A first launch connects through the proxy and reads the albums and one album live.
    /// 2. A relaunch with the account saved, in device-only mode, paints the albums and that album
    ///    from this device -- the proxy logs NO request while it does --, an album seen only in the
    ///    grid shows its cached header with its tracks unavailable, and an album never seen is
    ///    unavailable offline.
    /// 3. Reconnected, with every `getAlbum` held at the proxy, the opened album publishes what this
    ///    device saw and the grid-only album its cached header with its tracks still loading, both
    ///    while their reads are held unanswered -- so before any answer --, and neither ever as a
    ///    whole-screen loading state; released, both go live.
    func conf76() async throws {
        let fixture = try await counted()
        let databaseName = "dulcet-counted-76-\(platform)-\(UUID().uuidString).db"
        let credentials = CountedCredentialStore()
        let providerInstanceID = "\(platform)-counted-\(UUID().uuidString)"

        // 1. First launch, live.
        let (first, firstSession) = makeStore(databaseName, providerInstanceID, credentials)
        try await connect(first, firstSession, through: fixture.proxy)
        let albums = Recorder()
        albums.open(try XCTUnwrap(firstSession.reader), .albums(.alphabeticalByName))
        try await waitUntil("live albums: \(albums.summary)") {
            albums.last?.freshness == .live && self.fixtureAlbums.isSubset(of: Set(albums.last?.items.map(\.displayTitle) ?? []))
        }
        let liveTitles = albums.last?.items.map(\.displayTitle) ?? []
        let opened = try XCTUnwrap(albums.last?.items.first { $0.displayTitle == "Double Lines" })
        let gridOnly = try XCTUnwrap(albums.last?.items.first { $0.displayTitle == "Threshold Boundary" })
        let page = Recorder()
        page.open(try XCTUnwrap(firstSession.reader), .album(rawID: opened.id.rawID))
        try await waitUntil("live album: \(page.summary)") { page.last?.freshness == .live && page.last?.items.isEmpty == false }
        let liveTracks = page.last?.items.map(\.displayTitle) ?? []
        albums.close()
        page.close()
        await close(firstSession)

        // 2. Relaunch, device-only: painted from this device, nothing sent.
        let (second, secondSession) = makeStore(databaseName, providerInstanceID, credentials)
        XCTAssertEqual(secondSession.mode, .deviceOnly, "Nothing is sent until Reconnect is chosen")
        let relaunchMark = try await fixture.proxy.total()
        let cachedAlbums = Recorder()
        cachedAlbums.open(try XCTUnwrap(secondSession.reader), .albums(.alphabeticalByName))
        let cachedPage = Recorder()
        cachedPage.open(try XCTUnwrap(secondSession.reader), .album(rawID: opened.id.rawID))
        let gridOnlyOffline = Recorder()
        gridOnlyOffline.open(try XCTUnwrap(secondSession.reader), .album(rawID: gridOnly.id.rawID))
        let neverSeen = Recorder()
        neverSeen.open(try XCTUnwrap(secondSession.reader), .album(rawID: "dulcet-never-seen-\(UUID().uuidString)"))
        try await waitUntil("cached: \(cachedAlbums.summary) \(cachedPage.summary) \(gridOnlyOffline.summary) \(neverSeen.summary)") {
            cachedAlbums.last != nil && cachedPage.last != nil && gridOnlyOffline.last != nil && neverSeen.last != nil
        }
        try await settle(fixture)
        let sentWhilePainting = try await fixture.proxy.requests(since: relaunchMark)
        XCTAssertEqual(sentWhilePainting.map(\.description), [],
            "The relaunch paints from this device and sends nothing at all")
        guard case .cached(.offline, _) = cachedAlbums.first?.freshness else {
            XCTFail("The albums' first publication must be what this device saw, offline: \(cachedAlbums.summary)")
            return
        }
        XCTAssertEqual(cachedAlbums.first?.items.map(\.displayTitle), liveTitles, "The albums this device saw, in its order")
        XCTAssertEqual(cachedPage.first?.items.map(\.displayTitle), liveTracks, "The album's tracks this device saw")
        guard case .cached(.offline, _) = gridOnlyOffline.first?.freshness else {
            XCTFail("A grid-only album offline shows its cached header: \(gridOnlyOffline.summary)")
            return
        }
        XCTAssertEqual(gridOnlyOffline.first?.header?.displayTitle, "Threshold Boundary")
        XCTAssertEqual(gridOnlyOffline.first?.itemsState, .unavailable, "Tracks never read are unavailable offline")
        XCTAssertEqual(neverSeen.first?.freshness, .unavailable(.notCachedOffline), "An album never seen is unavailable offline")
        XCTAssertFalse((cachedAlbums.all + cachedPage.all + gridOnlyOffline.all + neverSeen.all).contains {
            $0.freshness == .loading || $0.itemsState == .loading
        }, "Offline, nothing shows a loading state")
        for recorder in [cachedAlbums, cachedPage, gridOnlyOffline, neverSeen] { recorder.close() }

        // 3. Online: with every album read held, both pages publish before any answer.
        dulcetReaderRetry(second)
        try await waitUntil("reconnect: mode=\(secondSession.mode)") { secondSession.mode == .connected && secondSession.reader != nil }
        try await settle(fixture)
        try await fixture.proxy.rule("hold", endpoint: "getAlbum")
        let heldMark = try await fixture.proxy.total()
        let onlinePage = Recorder()
        onlinePage.open(try XCTUnwrap(secondSession.reader), .album(rawID: opened.id.rawID))
        let gridOnlyOnline = Recorder()
        gridOnlyOnline.open(try XCTUnwrap(secondSession.reader), .album(rawID: gridOnly.id.rawID))
        var heldReads: [Seen] = []
        try await waitUntil("both album reads held: \(heldReads)", condition: {
            heldReads.filter { !$0.answered }.count >= 2 && onlinePage.last != nil && gridOnlyOnline.last != nil
        }, poll: { heldReads = (try? await fixture.proxy.requests(since: heldMark).filter { $0.endpoint == "getAlbum" }) ?? [] })
        let beforeAnswer = (page: onlinePage.all, gridOnly: gridOnlyOnline.all)
        XCTAssertEqual(heldReads.filter(\.answered).count, 0, "No album read had been answered: \(heldReads)")
        try await fixture.proxy.rule("hold", endpoint: nil)
        try await waitUntil("released: \(onlinePage.summary) \(gridOnlyOnline.summary)") {
            onlinePage.last?.freshness == .live && gridOnlyOnline.last?.freshness == .live && gridOnlyOnline.last?.itemsState == .present
        }
        guard case .cached = beforeAnswer.page.first?.freshness else {
            XCTFail("The opened album publishes what this device saw before its read is answered: \(beforeAnswer.page.map(\.freshness))")
            return
        }
        XCTAssertEqual(beforeAnswer.page.first?.items.map(\.displayTitle), liveTracks)
        guard case .cached = beforeAnswer.gridOnly.first?.freshness else {
            XCTFail("The grid-only album paints its cached header before its read is answered: \(beforeAnswer.gridOnly.map(\.freshness))")
            return
        }
        XCTAssertEqual(beforeAnswer.gridOnly.first?.header?.displayTitle, "Threshold Boundary")
        XCTAssertEqual(beforeAnswer.gridOnly.first?.itemsState, .loading, "Its tracks say they are being read")
        XCTAssertFalse((onlinePage.all + gridOnlyOnline.all).contains { $0.freshness == .loading },
            "A cached open never gives way to a whole-screen loading state")
        onlinePage.close()
        gridOnlyOnline.close()
        await close(secondSession)
        print("CONF-76 OBSERVED \(platform) relaunch-requests=\(sentWhilePainting.count) first-cached=\(String(describing: cachedAlbums.first?.freshness))"
            + " held-album-reads=\(heldReads.count) page-before-answer=\(beforeAnswer.page.map(\.freshness))"
            + " grid-only-before-answer=\(beforeAnswer.gridOnly.map { "\($0.freshness)/\($0.itemsState)" })"
            + " never-seen=\(String(describing: neverSeen.first?.freshness))")
    }

    // MARK: CONF-77

    /// A reconnect performs the outbox flush, the epoch read and the visible screen's revalidation
    /// and nothing else, counted at the proxy (with no download, there is no downloaded album to
    /// recheck).
    ///
    /// The visible screen is home's four rows, whole lists that a reconnect always re-reads (a paged
    /// list read live within the last minute is spared, §16.11 rule 3). Connected through the proxy
    /// with home live, the platform reports the network lost; an album starred offline sends
    /// nothing. The favourites row's read is held and the network is reported back: while it is
    /// held every row says it is revalidating and none live; released, each row says live once, at
    /// the end. What the reconnect sent is the star first, then the epoch read (`getMusicFolders`
    /// and `getScanStatus`), then the four rows' reads, and nothing else.
    func conf77() async throws {
        let fixture = try await counted()
        let network = ManualReachability()
        let (store, session) = makeStore(
            "dulcet-counted-77-\(platform)-\(UUID().uuidString).db", "\(platform)-counted-\(UUID().uuidString)", CountedCredentialStore(),
            reachability: network
        )
        try await connect(store, session, through: fixture.proxy)
        let reader = try XCTUnwrap(session.reader)
        let rows = HomeRows(reader)
        try await waitUntil("home live: \(rows.summary)") { rows.all { $0 == .live } }
        let albums = Recorder()
        albums.open(reader, .albums(.alphabeticalByName))
        try await waitUntil("live albums: \(albums.summary)") {
            albums.last?.freshness == .live && self.fixtureAlbums.isSubset(of: Set(albums.last?.items.map(\.displayTitle) ?? []))
        }
        let album = try XCTUnwrap(albums.last?.items.first { $0.displayTitle == "Double Lines" })
        albums.close()
        // Control: the album starts unstarred on the server, so the star read afterwards is this run's.
        _ = try await fixture.server.call("unstar", [URLQueryItem(name: "albumId", value: album.id.rawID)])
        testCase.addTeardownBlock { [server = fixture.server, id = album.id.rawID] in
            _ = try? await server.call("unstar", [URLQueryItem(name: "albumId", value: id)])
        }
        var outcomes: [DulcetFavouriteOutcome] = []
        let outcomeSubscription = reader.subscribeFavouriteOutcomes { outcomes.append($0) }
        defer { outcomeSubscription.cancel() }
        try await settle(fixture)

        network.report(reachable: false)
        try await waitUntil("home offline: \(rows.summary)") {
            rows.all { if case .cached(.offline, _) = $0 { true } else { false } }
        }
        let offlineMark = try await fixture.proxy.total()
        XCTAssertTrue(reader.setFavourite(DulcetFavouriteTarget(kind: .album, id: album.id), favourite: true),
            "The reader takes the change offline")
        var pending = -1
        try await waitUntil("the change pending offline: \(pending)", condition: { pending == 1 }, poll: {
            reader.pendingChangeCount { pending = Int($0.count ?? -1) }
        })
        try await settle(fixture)
        let sentOffline = try await fixture.proxy.requests(since: offlineMark).map(\.description)
        XCTAssertEqual(sentOffline, [], "A change made offline sends nothing")

        // The last row's read is held, so the sequence has a step left when the rest is answered.
        try await fixture.proxy.rule("hold", endpoint: "getStarred2")
        let mark = try await fixture.proxy.total()
        let framesBefore = rows.frameCounts
        network.report(reachable: true)
        var sinceMark: [Seen] = []
        try await waitUntil("every row read but the held one answered: \(sinceMark)", condition: {
            sinceMark.contains { $0.endpoint == "getStarred2" && !$0.answered }
                && sinceMark.filter { $0.endpoint == "getAlbumList2" && $0.answered }.count == 3
        }, poll: { sinceMark = (try? await fixture.proxy.requests(since: mark)) ?? [] })
        try await settle(fixture, held: true)
        XCTAssertTrue(rows.all { if case .cached(.revalidating, _) = $0 { true } else { false } },
            "Every row says it is revalidating, and none live, while the reconnect has a read left: \(rows.summary)")
        try await fixture.proxy.rule("hold", endpoint: nil)
        try await waitUntil("the reconnect to finish: \(rows.summary) outcomes=\(outcomes)") {
            rows.all { $0 == .live } && outcomes.contains { $0.kind == .saved }
        }
        try await settle(fixture)
        let reconnectFrames = rows.frames(since: framesBefore)
        for (row, seen) in reconnectFrames {
            XCTAssertEqual(seen.filter { $0 == .live }.count, 1, "\(row) says live once: \(seen)")
            XCTAssertEqual(seen.last, .live, "\(row) says live at the end: \(seen)")
        }

        let sent = try await fixture.proxy.requests(since: mark).filter { $0.endpoint != "getCoverArt" }
        let names = sent.map(\.endpoint)
        XCTAssertEqual(names.first, "star", "The outbox flush goes first: \(sent)")
        XCTAssertEqual(names.filter { $0 == "star" }.count, 1, "One change, one send: \(sent)")
        XCTAssertEqual(Array(names.dropFirst().prefix(2)).sorted(), ["getMusicFolders", "getScanStatus"], "Then the epoch read: \(sent)")
        XCTAssertEqual(sent.dropFirst(3).map(\.description).sorted(),
            ["getAlbumList2[frequent]:200", "getAlbumList2[newest]:200", "getAlbumList2[recent]:200", "getStarred2:200"],
            "Then the visible screen, each row once, and nothing else: \(sent)")
        let starred = try await fixture.server.call("getAlbum", [URLQueryItem(name: "id", value: album.id.rawID)])
        XCTAssertNotNil((starred["album"] as? [String: Any])?["starred"], "The server holds the star sent at the reconnect")
        rows.close()
        await close(session)
        print("CONF-77 OBSERVED \(platform) offline-requests=0 reconnect=\(sent.map(\.description))"
            + " frames=\(reconnectFrames.map { "\($0.key.rawValue)=\($0.value.count)" }.sorted()) outcomes=\(outcomes.map(\.kind))")
    }

    // MARK: CONF-86

    /// Home's rows publish independently, each with its own freshness, and one failing row leaves
    /// the others live. With the favourites row's read failed at the proxy and the most-played
    /// row's held, a fresh connect's home has its first two rows live and the favourites row failed
    /// while the most-played row is still loading, its read asked and unanswered; released, it goes
    /// live, the others stay live, and the failed row stays failed on its own.
    func conf86() async throws {
        let fixture = try await counted()
        try await fixture.proxy.rule("fail", endpoint: "getStarred2")
        try await fixture.proxy.rule("hold", endpoint: "getAlbumList2", type: "frequent")
        let (store, session) = makeStore(
            "dulcet-counted-86-\(platform)-\(UUID().uuidString).db", "\(platform)-counted-\(UUID().uuidString)", CountedCredentialStore()
        )
        let mark = try await fixture.proxy.total()
        try await connect(store, session, through: fixture.proxy)
        let reader = try XCTUnwrap(session.reader)
        var rows: [DulcetHomeRow: Recorder] = [:]
        for row in DulcetHomeRow.allCases {
            let recorder = Recorder()
            recorder.open(reader, .homeRow(row))
            rows[row] = recorder
        }
        func freshness(_ row: DulcetHomeRow) -> DulcetReaderFreshness? { rows[row]?.last?.freshness }
        func failed(_ row: DulcetHomeRow) -> Bool {
            switch freshness(row) {
            case .unavailable(.failed)?, .cached(.failed, _)?: true
            default: false
            }
        }
        let summary = { DulcetHomeRow.allCases.map { "\($0.rawValue)=\(rows[$0]?.summary ?? "-")" }.joined(separator: " ") }
        try await waitUntil("rows: \(summary())") {
            freshness(.recentlyAdded) == .live && freshness(.recentlyPlayed) == .live && failed(.favourites)
        }
        let whileHeld = try await fixture.proxy.requests(since: mark)
        let frequent = whileHeld.filter { $0.endpoint == "getAlbumList2" && $0.type == "frequent" }
        XCTAssertFalse(frequent.isEmpty, "Control: the most-played row did ask: \(whileHeld)")
        XCTAssertEqual(frequent.filter(\.answered).count, 0, "The most-played row's answer had not arrived: \(frequent)")
        XCTAssertTrue(whileHeld.contains { $0.endpoint == "getStarred2" && $0.status == 500 },
            "Control: the favourites row's read was failed by the proxy: \(whileHeld)")
        XCTAssertEqual(freshness(.mostPlayed), .loading, "The held row still says it is loading: \(summary())")
        XCTAssertFalse(rows[.recentlyAdded]?.last?.items.isEmpty ?? true, "A live row shows its albums")
        try await fixture.proxy.rule("hold", endpoint: nil)
        try await waitUntil("most played live: \(summary())") { freshness(.mostPlayed) == .live }
        XCTAssertEqual(freshness(.recentlyAdded), .live)
        XCTAssertEqual(freshness(.recentlyPlayed), .live)
        XCTAssertTrue(failed(.favourites), "The failed row stays failed on its own: \(summary())")
        try await fixture.proxy.rule("fail", endpoint: nil)
        for recorder in rows.values { recorder.close() }
        await close(session)
        print("CONF-86 OBSERVED \(platform) live-before-held=recentlyAdded,recentlyPlayed failed=favourites:\(String(describing: freshness(.favourites)))"
            + " held=mostPlayed frames=\(DulcetHomeRow.allCases.map { rows[$0]?.all.count ?? 0 })")
    }

    // MARK: Harness

    private struct Fixture {
        let proxy: FaultProxy
        let server: CountedServer
        /// The proxy's log length when this test began; nothing before it is this test's.
        let start: Int
    }

    /// The proxy and the server behind it, both refused unless they are loopback and disposable,
    /// and the proxy shown to front that server.
    private func counted() async throws -> Fixture {
        let environment = ProcessInfo.processInfo.environment
        let serverURL = environment["DULCET_CONFORMANCE_BASE_URL"] ?? ""
        let proxyURL = environment["DULCET_LYRICS_FAULT_PROXY_URL"] ?? ""
        for url in [serverURL, proxyURL] {
            let components = URLComponents(string: url)
            guard components?.scheme == "http", components?.host == "127.0.0.1",
                  environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true" else {
                XCTFail("Counted reader fixture refused: \(url.debugDescription) is not a disposable loopback origin")
                throw CountedProofError.invalidFixture
            }
        }
        let proxy = FaultProxy(url: proxyURL)
        let health = try await proxy.call("GET", "/__dulcet/health")
        XCTAssertEqual(health["ok"] as? Bool, true, "The proxy answers")
        let server = CountedServer(baseURL: serverURL, username: fixtureUsername, password: fixturePassword)
        let through = CountedServer(baseURL: proxyURL, username: fixtureUsername, password: fixturePassword)
        let direct = try await server.songID("Thirty One Seconds", album: "Threshold Boundary")
        let proxied = try await through.songID("Thirty One Seconds", album: "Threshold Boundary")
        XCTAssertEqual(direct, proxied, "The proxy fronts the disposable server")
        // A rule left on by a failed test would hold or fail the next one's reads.
        try await proxy.rule("hold", endpoint: nil)
        try await proxy.rule("fail", endpoint: nil)
        testCase.addTeardownBlock {
            try? await proxy.rule("hold", endpoint: nil)
            try? await proxy.rule("fail", endpoint: nil)
        }
        return Fixture(proxy: proxy, server: server, start: try await proxy.total())
    }

    private func makeStore(
        _ databaseName: String,
        _ providerInstanceID: String,
        _ credentials: CountedCredentialStore,
        reachability: (any DulcetReachabilityMonitoring)? = nil
    ) -> (DulcetPresentationStore, DulcetLibrarySession) {
        let session = DulcetLibrarySession(
            factory: DulcetCoreLibraryReaderFactory(databaseName: databaseName),
            reachability: reachability,
            unreachableGrace: .milliseconds(200)
        )
        let source = DulcetAccountDataSource(
            connector: DulcetCoreAccountConnector(),
            credentialStore: credentials,
            providerInstanceIDFactory: { providerInstanceID },
            librarySession: session
        )
        return (DulcetPresentationStore(source: source), session)
    }

    private func connect(_ store: DulcetPresentationStore, _ session: DulcetLibrarySession, through proxy: FaultProxy) async throws {
        store.accountServerURL = proxy.url
        store.accountUsername = fixtureUsername
        store.accountPassword = fixturePassword
        store.accountAllowLocalHTTP = true
        store.submitAccountConnection()
        try await waitUntil("connect: state=\(store.snapshot.state) mode=\(session.mode)") {
            store.snapshot.accountConnected && session.mode == .connected && session.reader != nil
        }
    }

    private func close(_ session: DulcetLibrarySession) async {
        await withCheckedContinuation { continuation in
            session.close { continuation.resume() }
        }
    }

    /// Waits until every request the proxy logged is answered (or, with `held`, until only held
    /// ones are not) and nothing new arrived for half a second.
    private func settle(_ fixture: Fixture, held: Bool = false) async throws {
        var last = -1
        var quietSince = ContinuousClock.now
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        while ContinuousClock.now < deadline {
            let log = try await fixture.proxy.requests(since: fixture.start)
            let total = log.count
            let unanswered = log.filter { !$0.answered }.count
            if total != last {
                last = total
                quietSince = ContinuousClock.now
            } else if (held || unanswered == 0), ContinuousClock.now - quietSince >= .milliseconds(500) {
                return
            }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTFail("The app's requests never settled")
    }

    private func waitUntil(
        _ failureMessage: @autoclosure () -> String,
        timeout: Duration = .seconds(30),
        condition: @MainActor () -> Bool,
        poll: @MainActor () async -> Void = {}
    ) async throws {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while true {
            await poll()
            if condition() { return }
            if ContinuousClock.now >= deadline {
                XCTFail(failureMessage())
                throw CountedProofError.invalidFixture
            }
            try await Task.sleep(for: .milliseconds(50))
        }
    }
}

/// One query's publications, in order.
@MainActor
private final class Recorder {
    private(set) var all: [DulcetLibraryWindow] = []
    private var subscription: (any DulcetLibraryWindowSubscribing)?

    var first: DulcetLibraryWindow? { all.first }
    var last: DulcetLibraryWindow? { all.last }
    var summary: String { all.map { "\($0.freshness)/\($0.itemsState)/\($0.items.count)" }.joined(separator: ",") }

    func open(_ reader: any DulcetLibraryReading, _ query: DulcetLibraryQuery) {
        subscription = reader.subscribeWindow(query) { [weak self] in self?.all.append($0) }
    }

    func close() {
        subscription?.close()
        subscription = nil
    }
}

/// Home's four rows, each its own subscription.
@MainActor
private final class HomeRows {
    private var recorders: [DulcetHomeRow: Recorder] = [:]

    init(_ reader: any DulcetLibraryReading) {
        for row in DulcetHomeRow.allCases {
            let recorder = Recorder()
            recorder.open(reader, .homeRow(row))
            recorders[row] = recorder
        }
    }

    func all(_ test: (DulcetReaderFreshness) -> Bool) -> Bool {
        DulcetHomeRow.allCases.allSatisfy { recorders[$0]?.last.map { test($0.freshness) } ?? false }
    }

    var frameCounts: [DulcetHomeRow: Int] { recorders.mapValues(\.all.count) }

    func frames(since counts: [DulcetHomeRow: Int]) -> [DulcetHomeRow: [DulcetReaderFreshness]] {
        recorders.mapValues { _ in [] }.merging(recorders.map { row, recorder in
            (row, recorder.all.dropFirst(counts[row] ?? 0).map(\.freshness))
        }) { $1 }
    }

    var summary: String {
        DulcetHomeRow.allCases.map { "\($0.rawValue)=\(recorders[$0]?.last.map { "\($0.freshness)" } ?? "-")" }.joined(separator: " ")
    }

    func close() { recorders.values.forEach { $0.close() } }
}

/// The platform's network reports, made by hand.
@MainActor
private final class ManualReachability: DulcetReachabilityMonitoring {
    private var handler: (@MainActor (Bool, Bool) -> Void)?
    private var reachable = true

    func start(_ handler: @escaping @MainActor (Bool, Bool) -> Void) {
        self.handler = handler
        handler(reachable, false)
    }

    func stop() { handler = nil }

    func report(reachable: Bool) {
        self.reachable = reachable
        handler?(reachable, false)
    }
}

/// A request the proxy logged: its endpoint, `type` and `id`, and what the app got.
private struct Seen: CustomStringConvertible {
    let endpoint: String
    let type: String?
    let id: String?
    let answered: Bool
    let status: Int

    var description: String { endpoint + (type.map { "[\($0)]" } ?? "") + ":\(answered ? String(status) : "unanswered")" }
}

/// `tools/conformance-env/lyrics-fault-proxy`'s controls.
private struct FaultProxy {
    let url: String

    func call(_ method: String, _ path: String) async throws -> [String: Any] {
        var request = URLRequest(url: try XCTUnwrap(URL(string: url + path)))
        request.httpMethod = method
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CountedProofError.unexpected("The proxy refused \(method) \(path)")
        }
        return body
    }

    func total() async throws -> Int {
        try await call("GET", "/__dulcet/requests?since=0")["total"] as? Int ?? 0
    }

    func requests(since mark: Int) async throws -> [Seen] {
        let body = try await call("GET", "/__dulcet/requests?since=\(mark)")
        return (body["requests"] as? [[String: Any]] ?? []).map {
            Seen(endpoint: $0["endpoint"] as? String ?? "", type: $0["type"] as? String, id: $0["id"] as? String,
                 answered: $0["answered"] as? Bool ?? false, status: $0["status"] as? Int ?? 0)
        }
    }

    /// Switches a hold or fail rule on for `endpoint` (and `type`), or off when `endpoint` is nil.
    func rule(_ action: String, endpoint: String?, type: String? = nil) async throws {
        var path = "/__dulcet/rule?action=\(action)&state=\(endpoint == nil ? "off" : "on")"
        if let endpoint { path += "&endpoint=\(endpoint)" }
        if let type { path += "&type=\(type)" }
        _ = try await call("POST", path)
    }
}

enum CountedProofError: Error {
    case invalidFixture
    case unexpected(String)
}

/// The account's credentials, in memory: a hosted test cannot reach the data-protection Keychain
/// when signed ad hoc, so what the proofs show starts at the store boundary.
@MainActor
final class CountedCredentialStore: DulcetProviderInstanceCredentialStoring {
    private var persisted: DulcetAccountConnectRequest?
    private(set) var providerInstanceID: String?
    private(set) var credentialGeneration: Int64 = 0

    func load() throws -> DulcetAccountConnectRequest? { persisted }
    func save(_ request: DulcetAccountConnectRequest) throws {
        persisted = request
        credentialGeneration += 1
    }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        persisted = request
        self.providerInstanceID = providerInstanceID
        credentialGeneration += 1
    }
    func delete() throws {
        persisted = nil
        providerInstanceID = nil
        credentialGeneration += 1
    }
}

/// The server read directly, sharing nothing with the app but the server.
struct CountedServer {
    let baseURL: String
    let username: String
    let password: String

    func call(_ endpoint: String, _ query: [URLQueryItem]) async throws -> [String: Any] {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
        var components = try XCTUnwrap(URLComponents(string: baseURL))
        components.path = "/rest/" + endpoint
        components.queryItems = [
            URLQueryItem(name: "u", value: username), URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt), URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-counted-test"), URLQueryItem(name: "f", value: "json"),
        ] + query
        let (data, _) = try await URLSession.shared.data(from: try XCTUnwrap(components.url))
        // The URL carries a token, so a failure names the endpoint only.
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any],
              envelope["status"] as? String == "ok" else {
            throw CountedProofError.unexpected("/rest/\(endpoint) did not return an ok envelope")
        }
        return envelope
    }

    func songID(_ title: String, album: String) async throws -> String {
        let result = try await call("search3", [
            URLQueryItem(name: "query", value: title), URLQueryItem(name: "songCount", value: "20"),
            URLQueryItem(name: "albumCount", value: "0"), URLQueryItem(name: "artistCount", value: "0"),
        ])
        let songs = (result["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let matches = songs.filter { $0["title"] as? String == title && $0["album"] as? String == album }
        guard matches.count == 1, let id = matches[0]["id"] as? String else {
            throw CountedProofError.unexpected("\(matches.count) songs named \(title) on \(album); exactly one is required")
        }
        return id
    }
}
