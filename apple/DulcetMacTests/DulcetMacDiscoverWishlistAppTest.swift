import AppKit
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

/// The six Discover proofs use the CI Mac host, its production root/controller/reader and the
/// same disposable fixture as iOS. Only initial connection uses the store; feature actions go
/// through the rendered accessibility controls, native text events, sidebar or context menu.
@MainActor
final class DulcetMacDiscoverWishlistAppTest: XCTestCase {
    private var apps: [HostedApp] = []
    private var previousProofMarkers = false
    private var env: [String: String] { ProcessInfo.processInfo.environment }
    private func required(_ key: String) throws -> String { try XCTUnwrap(env["DULCET_DISCOVER_" + key], key) }
    private func request(_ url: String, body: [String: Any]? = nil) async throws -> [String: Any] {
        var req = URLRequest(url: try XCTUnwrap(URL(string: url)))
        if let body {
            req.httpMethod = "POST"; req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: req)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    private func control(_ path: String, _ body: [String: Any]? = nil) async throws -> [String: Any] {
        try await request(required("CONTROL_URL") + "/control/" + path, body: body)
    }
    private func server(other: Bool = false, nav: Bool = false) throws -> LiveServer {
        LiveServer(baseURL: try required(nav ? "NAVIDROME_URL" : "SERVER_URL"),
                   username: try required(other ? "OTHER_USERNAME" : "USERNAME"),
                   password: try required(other ? "OTHER_PASSWORD" : "PASSWORD"))
    }
    private func rest(_ endpoint: String, _ params: [String: String] = [:], other: Bool = false) async throws -> [String: Any] {
        try await server(other: other).call(endpoint, params.map { URLQueryItem(name: $0.key, value: $0.value) })
    }
    @discardableResult private func scenario(_ name: String) async throws -> Int {
        let ack = try await control("scenario", ["name": name])
        XCTAssertEqual(ack["armed"] as? Bool, true)
        return try XCTUnwrap(ack["revision"] as? Int)
    }
    private func effect(_ name: String, revision: Int, flag: String? = nil) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(15))
        var state: [String: Any] = [:]
        repeat {
            state = try await control("state")
            if (state["effects"] as? [[String: Any]] ?? []).contains(where: {
                $0["scenario"] as? String == name && $0["revision"] as? Int == revision && (flag == nil || $0[flag!] as? Bool == true)
            }) {
                _ = try await request(required("CAPTURE_UPLOAD_URL"), body: ["ack": state]); return
            }
            try await Task.sleep(for: .milliseconds(50))
        } while ContinuousClock.now < deadline
        XCTFail("Unacknowledged fixture effect \(name) \(revision) \(state)")
        throw HostedAppError.unexpected(name)
    }
    override func setUp() async throws {
        continueAfterFailure = true
#if DEBUG
        previousProofMarkers = DulcetUIProofMarkers.isEnabled
        DulcetUIProofMarkers.isEnabled = true
#endif
        XCTAssertEqual(try required("DISPOSABLE"), "true")
        let reset = try await control("reset", [:])
        XCTAssertEqual(reset["reset"] as? Bool, true)
    }
    override func tearDown() async throws {
        apps.forEach { $0.close() }; apps = []
#if DEBUG
        DulcetUIProofMarkers.isEnabled = previousProofMarkers
#endif
    }
    private func launch(run: String = UUID().uuidString, credentials: any DulcetCredentialStoring = HostedCredentialStore(),
                        connect: Bool = true, other: Bool = false, nav: Bool = false, variant: String? = nil) async throws -> HostedApp {
        let server = try server(other: other, nav: nav)
        let app = try await HostedApp(serverURL: server.baseURL, username: server.username, password: server.password,
                                     run: run, credentialStore: credentials, connect: connect,
                                     colorScheme: variant == "dark" ? .dark : variant == "light" ? .light : nil,
                                     textSize: variant == "largest" ? .accessibility5 : .large)
        apps.append(app)
        if connect {
            // Detection can replace legacy Library with My Library. Wait for that publication
            // before choosing Browse, so the positive control is on the settled server root.
            try await waitUntil(timeout: .seconds(20), "My Library detection settles") { app.session.myLibrary.availability != .unknown }
            if app.session.myLibrary.availability == .available {
                try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.browse")
            }
            // Positive app browse precedes every feature-absence assertion.
            try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.section.albums")
            _ = try await app.element(identifiedBy: "dulcet.library.album", timeout: .seconds(45))
        }
        return app
    }
    private func press(_ id: String, in app: HostedApp) async throws {
        let element = try await app.element(identifiedBy: id, timeout: .seconds(20))
        try app.press(element, named: id)
    }
    private func search(_ query: String = "Fixture", in app: HostedApp) async throws {
        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.search")
        // Switching scopes also returns a retained metadata page to the query root.
        if let local = app.firstElement(where: { app.label($0) == "Your server" }) { try app.press(local, named: "Your server") }
        let everywhere = try await app.element(timeout: .seconds(15), described: "Everywhere") { app.label($0) == "Everywhere" }
        try app.press(everywhere, named: "Everywhere")
        try await type(query, in: app)
    }
    private func type(_ text: String, in app: HostedApp) async throws {
        let element = try await app.element(identifiedBy: "discover.query", timeout: .seconds(10))
        let field = try XCTUnwrap((element as? NSTextField) ?? (element as? NSCell)?.controlView as? NSTextField)
        app.window.makeKeyAndOrderFront(nil)
        XCTAssertTrue(app.window.makeFirstResponder(field)); field.currentEditor()?.selectAll(nil)
        try app.sendText(text)
    }
    private func item(_ title: String, in app: HostedApp) async throws -> Any {
        try await reveal(title, in: app) {
            (app.identifier($0) ?? "").hasPrefix("discover.item.") && (app.label($0) ?? "").hasPrefix(title)
        }
    }
    private func reveal(_ title: String, in app: HostedApp, where matches: (Any) -> Bool) async throws -> Any {
        for delta in [Int32(-500), Int32(500)] {
            for _ in 0..<14 {
                if let element = app.firstElement(where: matches) {
                    let frame = try app.frame(element)
                    if app.contentScreenFrame.contains(NSPoint(x: frame.midX, y: frame.midY)) { return element }
                }
                try app.scrollDetail(by: delta)
                try await Task.sleep(for: .milliseconds(150))
            }
        }
        XCTFail("Could not reveal \(title) through the native detail scroll view")
        throw HostedAppError.unexpected(title)
    }
    private func open(_ title: String, in app: HostedApp) async throws {
        try app.press(try await item(title, in: app), named: title)
        _ = try await app.element(identifiedBy: "discover.wish", timeout: .seconds(20))
    }
    private func wishlist(_ app: HostedApp) async throws {
        try await app.selectSidebarRow(identifiedBy: "wishlist.destination")
        _ = try await app.element(identifiedBy: "wishlist.refresh", timeout: .seconds(15))
    }
    private func wishes(other: Bool = false) async throws -> [[String: Any]] {
        try await rest("getDulcetWishlist", ["size": "100"], other: other)["dulcetWishlist"]
            .flatMap { $0 as? [String: Any] }?["wish"] as? [[String: Any]] ?? []
    }
    private func awaitWish(_ title: String, count: Int, other: Bool = false) async throws -> [[String: Any]] {
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        repeat {
            let rows = try await wishes(other: other).filter { ($0["snapshot"] as? [String: Any])?["title"] as? String == title }
            if rows.count == count { return rows }
            try await Task.sleep(for: .milliseconds(100))
        } while ContinuousClock.now < deadline
        XCTFail("Server wish \(title) did not reach count \(count)"); throw HostedAppError.unexpected(title)
    }
    private func row(_ title: String, in app: HostedApp) async throws -> Any {
        try await reveal("Wishlist row \(title)", in: app) {
            (app.identifier($0) ?? "").hasPrefix("wishlist.row.") && app.containsLabel(title, in: $0)
        }
    }
    private func capture(_ name: String, _ app: HostedApp) async throws {
        let view = try XCTUnwrap(app.window.contentView)
        let bitmap = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        let data = try XCTUnwrap(bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.65]))
        _ = try await request(required("CAPTURE_UPLOAD_URL"), body: ["name": name, "jpeg": data.base64EncodedString()])
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.jpeg")
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }

    func testEverywhereQueryPagesAndHeldRouting() async throws {
        let app = try await launch()
        let revision = try await scenario("delayed-old-query")
        try await search("old", in: app)
        try await effect("delayed-old-query", revision: revision)
        try await type("Fixture", in: app)
        _ = try await item("Fixture Song 01", in: app)
        try await effect("delayed-old-query", revision: revision, flag: "response")
        var payloads: [String] = []
        for _ in 0..<100 {
            let effects = try await control("state")["effects"] as? [[String: Any]] ?? []
            payloads = effects.compactMap { $0["payload"] as? String }
            if payloads.contains("old") { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertLessThan(try XCTUnwrap(payloads.firstIndex(of: "new")), try XCTUnwrap(payloads.firstIndex(of: "old")))
        XCTAssertNil(app.firstElement { (app.label($0) ?? "").hasPrefix("Obsolete old query") })
        try await capture("Search-Everywhere", app)
        try await open("Fixture Artist 00", in: app)
        _ = try await app.element(identifiedBy: "discover.artist.title", timeout: .seconds(20))
        try await capture("Artist", app)
        try await open("Fixture Album 00", in: app)
        _ = try await app.element(identifiedBy: "discover.album.title", timeout: .seconds(20))
        try await app.chooseMenuItem("2026-02-01 · 12 tracks", described: "edition") { try await self.press("discover.edition", in: app) }
        try await capture("Album", app)
        try await press("discover.openLibrary", in: app)
        _ = try await app.element(identifiedBy: "dulcet.album.title", timeout: .seconds(20))
        let albums = try await rest("getAlbumList2", ["type": "alphabeticalByName", "size": "100"])["albumList2"] as? [String: Any]
        let album = try XCTUnwrap((albums?["album"] as? [[String: Any]])?.first { $0["name"] as? String == "Fixture Album 00" })
        XCTAssertEqual(app.store.readerPath.last, .album(.init(providerInstanceID: try XCTUnwrap(app.session.account?.providerInstanceID), rawID: try XCTUnwrap(album["id"] as? String))))
        try await search(in: app); try await open("Fixture Song 10", in: app); try await press("discover.openLibrary", in: app)
        try await waitUntil(timeout: .seconds(20), "Exact held track must play") { app.store.snapshot.nowPlaying?.current.title == "Fixture Song 10" }
    }

    func testOfflineWishRelaunchRemoveAndScanArrival() async throws {
        let run = UUID().uuidString, credentials = HostedCredentialStore(), title = "Fixture Song 01"
        var app = try await launch(run: run, credentials: credentials)
        try await search(in: app)
        let sourceItem = try await item(title, in: app)
        let sourceID = try XCTUnwrap(app.identifier(sourceItem)).replacingOccurrences(of: "discover.item.", with: "")
        try await open(title, in: app); try await scenario("offline"); try await press("discover.wish", in: app)
        _ = try await app.element(identifiedBy: "wishlist.pending", timeout: .seconds(10))
        try await capture("Pending", app); app.close()
        app = try await launch(run: run, credentials: credentials, connect: false)
        try await wishlist(app)
        _ = try await app.element(identifiedBy: "wishlist.pending", timeout: .seconds(10))
        let pending = try await app.element(timeout: .seconds(10), described: "durable add UUID") { (app.identifier($0) ?? "").hasPrefix("wishlist.row.add:") }
        let operation = try XCTUnwrap(app.identifier(pending)).replacingOccurrences(of: "wishlist.row.add:", with: "")
        XCTAssertNotNil(UUID(uuidString: operation)); app.close()
        let lost = try await scenario("lost-add-receipt")
        app = try await launch(run: run, credentials: credentials)
        try await wishlist(app); try await effect("lost-add-receipt", revision: lost, flag: "delivered")
        let deliveredRows = try await awaitWish(title, count: 1)
        let delivered = try XCTUnwrap(deliveredRows.first)
        try await scenario("offline")
        let deliveredRow = try await row(title, in: app)
        let remove = try XCTUnwrap(app.childElement(identifiedBy: "wishlist.remove", in: deliveredRow))
        try app.press(remove, named: "Remove \(title)")
        let scan = try await scenario("scan-arrival"); try await effect("scan-arrival", revision: scan, flag: "completed")
        app.close(); app = try await launch(run: run, credentials: credentials)
        try await wishlist(app); _ = try await awaitWish(title, count: 0)
        var replay = ["kind": "song", "mbid": "00000001-1111-4111-8111-111111111111", "title": title, "sourceType": "search", "sourceId": sourceID, "operationId": operation, "actionTime": try XCTUnwrap(delivered["addedAt"] as? String)]
        let snapshot = try XCTUnwrap(delivered["snapshot"] as? [String: Any])
        for key in ["artist", "artworkId"] { if let value = snapshot[key] as? String { replay[key] = value } }
        if let year = snapshot["year"] as? Int { replay["year"] = String(year) }
        let late = try await rest("addToDulcetWishlist", replay)["dulcetWishMutation"] as? [String: Any]
        XCTAssertEqual(late?["removed"] as? Bool, true)
        _ = try await request(required("CAPTURE_UPLOAD_URL"), body: ["ack": ["scenario": "lost-add-receipt-replay", "replay": late ?? [:]]])
        try await capture("Wishlist", app)
        try await search(in: app); try await open(title, in: app); try await press("discover.wish", in: app)
        let bRows = try await awaitWish(title, count: 1)
        let generationB = try XCTUnwrap(bRows.first?["id"] as? String)
        let secondScan = try await scenario("scan-arrival"); try await effect("scan-arrival", revision: secondScan, flag: "completed")
        try await wishlist(app); try await press("wishlist.refresh", in: app)
        let arrival = try await app.element(identifiedBy: "wishlist.arrival", timeout: .seconds(30))
        try await capture("Arrived", app); try app.press(arrival, named: "Arrived")
        let currentRows = try await awaitWish(title, count: 1)
        let current = try XCTUnwrap(currentRows.first)
        let localID = try XCTUnwrap((current["libraryItem"] as? [String: Any])?["id"] as? String)
        try await waitUntil(timeout: .seconds(20), "Arrived must play exact server item") { app.store.snapshot.nowPlaying?.current.id.rawID == localID }
        let secondLate = try await rest("addToDulcetWishlist", replay)["dulcetWishMutation"] as? [String: Any]
        XCTAssertEqual(secondLate?["removed"] as? Bool, true)
        let survivingRows = try await awaitWish(title, count: 1)
        XCTAssertEqual(survivingRows.first?["id"] as? String, generationB)
        let library = try await rest("getDulcetLibrary", ["type": "song", "size": "100"])["dulcetLibrary"] as? [String: Any]
        XCTAssertTrue((library?["song"] as? [[String: Any]] ?? []).contains { $0["id"] as? String == localID })
    }

    func testBusyStaleAndNavidromeControl() async throws {
        let app = try await launch()
        let stale = try await scenario("stale-answer"); try await search(in: app)
        _ = try await item("Fixture Album 00", in: app)
        let age = try await app.element(identifiedBy: "discover.age", timeout: .seconds(20))
        XCTAssertTrue((app.label(age) ?? "").contains("Cached answer")); try await effect("stale-answer", revision: stale)
        let busy = try await scenario("rate-limit-120")
        try await type("Fixture new", in: app)
        let retry = try await reveal("rate-limit Retry", in: app) { app.identifier($0) == "discover.retry" }
        XCTAssertFalse(app.isAccessibilityEnabled(retry)); try await effect("rate-limit-120", revision: busy)
        try await capture("Search-Busy", app); app.close(); try await scenario("normal")
        let baseline = try await control("baseline")
        XCTAssertEqual(baseline["login"] as? Bool, true); XCTAssertGreaterThan(baseline["albums"] as? Int ?? 0, 0)
        let nav = try await launch(nav: true)
        try await nav.selectSidebarRow(identifiedBy: "dulcet.sidebar.search")
        XCTAssertNil(nav.firstElement { nav.label($0) == "Everywhere" })
        XCTAssertNil(nav.firstElement { nav.identifier($0) == "wishlist.destination" })
        let requests = try await control("requests?since=0")["requests"] as? [[String: Any]] ?? []
        let observed = requests.filter { $0["channel"] as? String == "navidrome" }
        XCTAssertTrue(observed.contains { $0["endpoint"] as? String == "getAlbumList2" })
        XCTAssertFalse(observed.contains { ($0["endpoint"] as? String ?? "").contains("Dulcet") })
        _ = try await request(required("CAPTURE_UPLOAD_URL"), body: ["ack": ["scenario": "navidrome-baseline", "baseline": baseline, "featureCalls": 0]])
    }

    func testAccountSwitchAndIdentityReplacementFence() async throws {
        let credentials = DiscoverHostedCredentials(), run = UUID().uuidString
        let app = try await launch(run: run, credentials: credentials)
        try await search(in: app); try await open("Fixture Song 01", in: app)
        try await scenario("offline"); try await press("discover.wish", in: app)
        _ = try await app.element(identifiedBy: "wishlist.pending", timeout: .seconds(10))
        try await scenario("normal")
        app.store.addServer()
        app.store.accountServerURL = try required("SERVER_URL"); app.store.accountUsername = try required("OTHER_USERNAME")
        app.store.accountPassword = try required("OTHER_PASSWORD"); app.store.accountAllowLocalHTTP = true
        app.store.submitAccountConnection()
        try await waitUntil(timeout: .seconds(30), "B connects") { app.session.account?.username == self.env["DULCET_DISCOVER_OTHER_USERNAME"] && app.session.mode == .connected }
        try await wishlist(app)
        _ = try await app.element(timeout: .seconds(20), described: "other owner sentinel") { app.label($0) == "Other owner only" }
        XCTAssertNil(app.firstElement { app.label($0) == "Fixture Song 01" })
        let otherWishes = try await wishes(other: true)
        XCTAssertFalse(otherWishes.contains { ($0["snapshot"] as? [String: Any])?["title"] as? String == "Fixture Song 01" })
        try await savedServer(try required("USERNAME"), in: app); try await wishlist(app)
        _ = try await row("Fixture Song 01", in: app)
        try await savedServer(try required("OTHER_USERNAME"), in: app); try await wishlist(app)
        try await scenario("identity-replacement")
        try await savedServer(try required("USERNAME"), in: app, replacement: true)
        // AppKit presents this native alert in an attached sheet, outside the main window's
        // accessibility subtree. Use the same real NSAlert buttons as the CI host proofs.
        let consent = try await app.answerAlert(pressing: "Cancel", described: "server replacement consent")
        XCTAssertTrue(consent.contains("Replace this server’s local data?"))
        XCTAssertNil(app.store.pendingDownloadRemovalCount)
        try await wishlist(app)
        XCTAssertNil(app.firstElement { app.label($0) == "Fixture Song 01" })
    }
    private func savedServer(_ username: String, in app: HostedApp, replacement: Bool = false) async throws {
        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.settings")
        let button = try await app.element(timeout: .seconds(15), described: "saved \(username)") { (app.identifier($0) ?? "").hasPrefix("dulcet.account.server.") && (app.label($0) ?? "").contains(username) }
        try app.press(button, named: "saved server")
        if !replacement { try await waitUntil(timeout: .seconds(30), "saved server commits") { app.session.account?.username == username && app.session.mode == .connected } }
    }

    func testMacKeyboardSearchSidebarAndContextRemove() async throws {
        let app = try await launch()
        try await search(in: app)
        let song = try await item("Fixture Song 01", in: app)
        // Focus the real native result and use Space on the link and Add wish button.
        try await focusAndPress(song, key: " ", code: 49, in: app)
        let wish = try await app.element(identifiedBy: "discover.wish", timeout: .seconds(20))
        try await focusAndPress(wish, key: " ", code: 49, in: app)
        _ = try await awaitWish("Fixture Song 01", count: 1)
        try await wishlist(app)
        let row = try await row("Fixture Song 01", in: app)
        try await app.chooseMenuItem("Remove wish", described: "wish context menu") { try app.rightClick(row) }
        _ = try await awaitWish("Fixture Song 01", count: 0)
        app.window.setContentSize(NSSize(width: 900, height: 600))
        XCTAssertEqual(app.contentWidth, 900, accuracy: 1, "The production root's minimum Mac width")
        _ = try await app.element(identifiedBy: "wishlist.refresh", timeout: .seconds(10))
        try await capture("Mac-Minimum-Wishlist", app)
        app.close()
        for variant in ["light", "dark", "largest"] {
            _ = try await control("reset", [:])
            let variantApp = try await launch(variant: variant)
            try await search(in: variantApp)
            _ = try await item("Fixture Artist 00", in: variantApp)
            try await capture("Search-" + variant, variantApp)
            try await open("Fixture Artist 00", in: variantApp)
            try await capture("Artist-" + variant, variantApp)
            try await open("Fixture Album 00", in: variantApp)
            let wish = try await variantApp.element(identifiedBy: "discover.wish", timeout: .seconds(20))
            XCTAssertFalse((variantApp.label(wish) ?? "").isEmpty)
            XCTAssertTrue(variantApp.isAccessibilityEnabled(wish))
            try await capture("Album-" + variant, variantApp)
            try await scenario("offline"); try variantApp.press(wish, named: "Add wish")
            _ = try await variantApp.element(identifiedBy: "wishlist.pending", timeout: .seconds(10))
            try await capture("Pending-" + variant, variantApp)
            try await wishlist(variantApp)
            _ = try await variantApp.element(identifiedBy: "wishlist.pending", timeout: .seconds(10))
            try await capture("Wishlist-" + variant, variantApp)
            try await scenario("normal"); variantApp.close()
        }
    }

    private func focusAndPress(_ element: Any, key: String, code: UInt16, in app: HostedApp) async throws {
        let identifier = try XCTUnwrap(app.identifier(element))
        NSApp.activate(ignoringOtherApps: true)
        app.window.makeKeyAndOrderFront(nil)
        XCTAssertTrue(NSApp.isFullKeyboardAccessEnabled, "The CI keyboard proof requires Full Keyboard Access")
        func focused() -> Bool {
            guard let current = app.firstElement(where: { app.identifier($0) == identifier }) as? NSObject else { return false }
            let selector = NSSelectorFromString("isAccessibilityFocused")
            return current.responds(to: selector) && (current.value(forKey: "accessibilityFocused") as? Bool == true)
        }
        func send(_ characters: String, code: UInt16) throws {
            for type in [NSEvent.EventType.keyDown, .keyUp] {
                let event = try XCTUnwrap(NSEvent.keyEvent(with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: app.window.windowNumber, context: nil, characters: characters, charactersIgnoringModifiers: characters, isARepeat: false, keyCode: code))
                // The XCTest host may stay inactive. Deliver to this actual window's
                // responder chain, so another app's key window cannot consume the proof.
                app.window.sendEvent(event)
            }
        }
        // Like the hosted table proofs' makeFirstResponder, focus the rendered control
        // through AppKit accessibility, then require the actual key event to activate it.
        // Lazy search rows do not stay in one key-view loop while Tab scrolls other groups.
        let current = try XCTUnwrap(app.firstElement(where: { app.identifier($0) == identifier }) as? NSObject)
        let setter = NSSelectorFromString("setAccessibilityFocused:")
        XCTAssertTrue(current.responds(to: setter), "Native control must expose keyboard focus")
        guard current.responds(to: setter) else { throw HostedAppError.unexpected("keyboard focus unavailable") }
        current.setValue(true, forKey: "accessibilityFocused")
        try await waitUntil(timeout: .seconds(3), "Native keyboard focus must reach \(identifier)") { focused() }
        print("DISCOVER_MAC_KEYBOARD identifier=\(identifier) focused=\(focused()) keyWindow=\(NSApp.keyWindow === app.window) responder=\(String(describing: app.window.firstResponder))")
        try send(key, code: code)
    }

    func testMacWindowClosureDropsPendingNavigation() async throws {
        let app = try await launch()
        try await search(in: app)
        let revision = try await scenario("held-detail")
        let album = try await item("Fixture Album 00", in: app)
        try app.press(album, named: "album detail")
        try await effect("held-detail", revision: revision, flag: "held")
        XCTAssertTrue(app.window.isVisible)
        app.window.performClose(nil)
        XCTAssertFalse(app.window.isVisible)
        _ = try await control("release-detail", [:])
        try await effect("held-detail", revision: revision, flag: "released")
        try await Task.sleep(for: .milliseconds(500))
        XCTAssertFalse(app.window.isVisible, "The actual late detail must not reopen its closed window")
    }
}

/// Same saved-account protocol as production, isolated to this hosted run. Server identity evidence
/// and provider UUIDs are retained across native saved-server selection and a reader reopening.
@MainActor private final class DiscoverHostedCredentials: DulcetSavedServerCredentialStoring {
    struct Record { let request: DulcetAccountConnectRequest; let identity: String }
    var records: [String: Record] = [:]
    var providerInstanceID: String?
    var credentialGeneration: Int64 = 0
    var savedProviderInstanceIDs: [String] { Array(records.keys).sorted() }
    var serverIdentity: String { providerInstanceID.flatMap { records[$0]?.identity } ?? "" }
    func load() throws -> DulcetAccountConnectRequest? { providerInstanceID.flatMap { records[$0]?.request } }
    func save(_ request: DulcetAccountConnectRequest) throws { try save(request, providerInstanceID: providerInstanceID ?? UUID().uuidString) }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws { try save(request, providerInstanceID: providerInstanceID, serverIdentity: "") }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String, serverIdentity: String) throws {
        records[providerInstanceID] = Record(request: request, identity: serverIdentity); self.providerInstanceID = providerInstanceID; credentialGeneration += 1
    }
    func load(providerInstanceID: String) throws -> DulcetAccountConnectRequest { try XCTUnwrap(records[providerInstanceID]?.request) }
    func serverIdentity(providerInstanceID: String) throws -> String { records[providerInstanceID]?.identity ?? "" }
    func activate(providerInstanceID: String) throws { self.providerInstanceID = providerInstanceID; credentialGeneration += 1 }
    func delete() throws { if let id = providerInstanceID { try delete(providerInstanceID: id) } }
    func delete(providerInstanceID: String) throws { records.removeValue(forKey: providerInstanceID); if self.providerInstanceID == providerInstanceID { self.providerInstanceID = nil }; credentialGeneration += 1 }
}
