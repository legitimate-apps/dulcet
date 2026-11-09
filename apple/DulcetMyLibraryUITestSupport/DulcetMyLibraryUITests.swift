import Foundation
import XCTest
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Production UI and real loopback /rest calls. Each run requires disposable fixture accounts.
@MainActor
final class DulcetMyLibraryUITests: XCTestCase {
    private var app = XCUIApplication()
    // Keep each disposable proof's account separate on every shell. Relaunches inside
    // one proof retain this namespace; a later server must not inherit a prior account.
    private let accountFixtureNamespace = UUID().uuidString
#if os(macOS)
    private var installedFixtureNetworkMonitor = false
#endif
    private var user: String {
#if os(macOS)
        "apple-mac"
#elseif os(tvOS)
        "apple-tv"
#else
        UIDevice.current.userInterfaceIdiom == .pad ? "apple-ipad" : "apple-iphone"
#endif
    }
    private let password = "fixture-password"
    private var base: String {
#if os(macOS)
        "http://127.0.0.1:4784"
#elseif os(tvOS)
        "http://127.0.0.1:4788"
#else
        UIDevice.current.userInterfaceIdiom == .pad ? "http://127.0.0.1:4787" : "http://127.0.0.1:4786"
#endif
    }
    private let navidrome = "http://127.0.0.1:4785"

    /// MP-09 uses the shipping account actions and request selector. The control server only
    /// changes its owned listeners and reports receipts; it never supplies app presentation state.
    func testMP09ConnectionsMatchAuthenticatedReceiptsAndRemoveReaddWithoutReplacingTheAccount() throws {
        continueAfterFailure = false
        let fixture = try connectionsFixture()
        defer { app.terminate(); _ = try? connectionsControl(fixture, "reset", method: "POST") }
        _ = try connectionsControl(fixture, "reset", method: "POST")
        try launch(server: fixture.routeA, username: fixture.username, password: fixture.password)
        try connectionsSelectAccount()
        try connectionsAssertCurrent("home", fixture: fixture)
        let account = try connectionsSavedAccount()
        let signOut = app.buttons["Sign Out"].firstMatch
        try connectionsReveal(signOut, identifier: "Sign Out")
        activate(signOut)
        try connectionsCancelSignOut()
        XCTAssertEqual(try connectionsSavedAccount(), account, "Cancelling Sign Out preserves the saved account")
        let label = try XCTUnwrap(fixture.routeLabels?["home"] ?? nil, "The authenticated directory must supply its long-label positive control")
        XCTAssertGreaterThan(label.count, 80)
        let text = app.staticTexts["dulcet.connections.label.home"].firstMatch
        try connectionsReveal(text, identifier: "dulcet.connections.label.home")
        XCTAssertEqual(connectionsText(text), label, "The long administrator label must remain complete in the accessibility tree")
        try connectionsCapture("MP09-01-current-home")

        // The OS path remains satisfied. A fresh process's ordinary saved-account reconnect
        // encounters the silent listener outage and the production selector chooses another route.
        _ = try connectionsControl(fixture, "reset", method: "POST")
        _ = try connectionsControl(fixture, "outage", method: "POST", parameters: ["routeId": "home", "state": "on"])
        app.terminate()
        try launch(connect: false)
        try connectionsSelectAccount()
        try connectionsAssertCurrent("tunnel", fixture: fixture)
        try connectionsAssertStatus("home", "Unavailable")
        XCTAssertEqual(try connectionsSavedAccount(), account)
        let failed = try connectionsControl(fixture, "state")
        XCTAssertTrue((failed["requests"] as? [[String: Any]] ?? []).contains {
            $0["routeId"] as? String == "home" && $0["completed"] as? Bool == false
        }, "Unavailable must follow an actual failed fixture request")
        try connectionsCapture("MP09-02-home-unavailable-current-tunnel")

        // Remove the original bootstrap while the live alternate supplies the authenticated
        // directory. Verify cannot silently clear the original route's removal tombstone.
        try connectionsActivate("dulcet.connections.remove.home")
        let directoryReads = try connectionsDirectoryReceiptCount(fixture, route: "tunnel")
        try connectionsActivate("dulcet.connections.verify")
        let verificationDeadline = Date().addingTimeInterval(30)
        while try connectionsDirectoryReceiptCount(fixture, route: "tunnel") <= directoryReads,
              Date() < verificationDeadline {
            let pulse = XCTestExpectation(description: "Authenticated surviving-route directory receipt")
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { pulse.fulfill() }
            _ = XCTWaiter.wait(for: [pulse], timeout: 1)
        }
        XCTAssertGreaterThan(try connectionsDirectoryReceiptCount(fixture, route: "tunnel"), directoryReads,
            "Verify after bootstrap removal must authenticate through the surviving route")
        XCTAssertFalse(app.buttons["dulcet.connections.remove.home"].firstMatch.exists)
        XCTAssertEqual(try connectionsSavedAccount(), account)
        for route in ["different", "bad-name", "tunnel", "remote"] {
            try connectionsActivate("dulcet.connections.remove.\(route)")
        }
        let offline = app.staticTexts["dulcet.connections.offline"].firstMatch
        try connectionsReveal(offline, identifier: "dulcet.connections.offline")
        XCTAssertTrue(offline.waitForExistence(timeout: 20))
        try connectionsCapture("MP09-03-last-route-removed-offline")
        // The receipt window opens once the old process is gone: a request the previous launch
        // finished before it exited is not a dial on relaunch.
        app.terminate()
        _ = try connectionsControl(fixture, "reset", method: "POST", parameters: ["marker": "last-route-relaunch"])
        try launch(connect: false)
        try connectionsSelectAccount()
        try connectionsReveal(offline, identifier: "dulcet.connections.offline")
        XCTAssertTrue(offline.exists)
        let offlineReceipt = try connectionsControl(fixture, "state")
        let relaunchRequests = offlineReceipt["requests"] as? [[String: Any]] ?? []
        XCTAssertEqual(relaunchRequests.count, 0,
            "Removing the last route must not dial the saved anchor on relaunch: " +
            relaunchRequests.map(connectionsReceiptDescription).joined(separator: "; "))
        XCTAssertEqual(try connectionsSavedAccount(), account)

        // Explicit Add to a historical HTTPS address bootstraps the account after its last
        // route was removed. This is real keyboard input followed by the production Add action.
        try connectionsEnterAddress(fixture.routeA)
        try connectionsActivate("dulcet.connections.add")
        try connectionsAssertCurrent("home", fixture: fixture)
        XCTAssertEqual(try connectionsSavedAccount(), account)
        XCTAssertFalse(app.buttons["dulcet.connections.remove.tunnel"].firstMatch.exists,
            "Re-add clears only the chosen historical tombstone")
        try connectionsCapture("MP09-04-explicit-bootstrap-readd")
        print("DULCET MP-09 CONTROLS current-receipt=true silent-failure=true bootstrap-remove=true last-offline=true readd=true ownership=same-saved-row surface=\(user)")
    }

    func testMP09UpgradeAndDifferentServerRefusalKeepTheSavedOwnershipAnchor() throws {
        continueAfterFailure = false
        let fixture = try connectionsFixture()
        defer { app.terminate(); _ = try? connectionsControl(fixture, "reset", method: "POST") }
        _ = try connectionsControl(fixture, "reset", method: "POST")
        try launch(server: fixture.legacyHttp, username: fixture.username, password: fixture.password)
        try connectionsSelectAccount()
        let account = try connectionsSavedAccount()
        try connectionsEnterAddress(fixture.routeA)
        try connectionsActivate("dulcet.connections.upgrade")
        try connectionsAssertCurrent("home", fixture: fixture)
        XCTAssertEqual(try connectionsSavedAccount(), account)
        // A connected Account page shows no connect form; the saved server's row shows its
        // ownership address as host, port and path. The legacy HTTP fixture's port is its own,
        // distinct from every HTTPS route's, so the row tells the anchor apart from them.
        let anchorRow = app.buttons[account].firstMatch
        try connectionsReveal(anchorRow, identifier: account)
        let legacy = try XCTUnwrap(URLComponents(string: fixture.legacyHttp))
        let legacyPath = legacy.percentEncodedPath.hasSuffix("/") ? String(legacy.percentEncodedPath.dropLast()) : legacy.percentEncodedPath
        let legacyShown = "\(try XCTUnwrap(legacy.host)):\(try XCTUnwrap(legacy.port))\(legacyPath)"
        let anchorTexts = anchorRow.descendants(matching: .staticText)
            .matching(NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", legacyShown, legacyShown))
        XCTAssertTrue(anchorRow.label.contains(legacyShown) || anchorTexts.count > 0,
            "Preserving Upgrade retains the historical HTTP ownership address \(legacyShown): \(anchorRow.label)")
        try connectionsCapture("MP09-05-preserving-https-upgrade")

        // A TLS-valid different disposable server has the same username. Explicit Add must
        // refuse it rather than switch account ownership or offer destructive replacement.
        try connectionsEnterAddress(try XCTUnwrap(fixture.routes["different"]))
        try connectionsActivate("dulcet.connections.add")
        let error = app.staticTexts["dulcet.connections.error"].firstMatch
        try connectionsReveal(error, identifier: "dulcet.connections.error")
        XCTAssertTrue(error.waitForExistence(timeout: 30))
        XCTAssertEqual(try connectionsSavedAccount(), account)
        XCTAssertFalse(app.alerts.firstMatch.exists, "Refused enrollment cannot invoke destructive account replacement")
        try connectionsAssertStatus("home", "Current HTTPS connection")
        try connectionsCapture("MP09-06-different-server-refused")
        _ = try connectionsControl(fixture, "reset", method: "POST")
        app.terminate()
        try launch(connect: false)
        try connectionsSelectAccount()
        try connectionsAssertCurrent("home", fixture: fixture)
        let receipt = try connectionsControl(fixture, "state")
        XCTAssertEqual((receipt["counts"] as? [String: Int])?["legacy"], 0,
            "The upgraded account's real relaunch must never dial the HTTP anchor")
        XCTAssertEqual(try connectionsSavedAccount(), account)
        print("DULCET MP-09 CONTROLS upgrade=true different-server-refused=true historical-anchor=true http-after-upgrade=0 surface=\(user)")
    }

    private struct ConnectionsFixture: Decodable {
        let standard: String
        let disposable: Bool
        let nonce: String
        let phase: Int
        let routeA: String
        let legacyHttp: String
        let routes: [String: String]
        let routeLabels: [String: String?]?
        let controlBase: String
        let username: String
        let password: String
    }
    private func connectionsFixture() throws -> ConnectionsFixture {
        let raw = try XCTUnwrap(ProcessInfo.processInfo.environment["DULCET_MULTIPATH_MANIFEST_JSON"],
            "MP-09 requires the owned local disposable fixture; no existing account is a fallback")
        let fixture = try JSONDecoder().decode(ConnectionsFixture.self, from: Data(raw.utf8))
        XCTAssertEqual(fixture.standard, "dulcet-multipath-fixture")
        XCTAssertTrue(fixture.disposable)
        XCTAssertEqual(fixture.phase, 2)
        _ = try XCTUnwrap(UUID(uuidString: fixture.nonce))
        guard fixture.standard == "dulcet-multipath-fixture", fixture.disposable, fixture.phase == 2,
              UUID(uuidString: fixture.nonce) != nil else { throw URLError(.badURL) }
        for address in Array(fixture.routes.values) + [fixture.routeA, fixture.legacyHttp, fixture.controlBase] {
            let url = try XCTUnwrap(URLComponents(string: address))
            guard url.host == "localhost", url.port != nil, ["http", "https"].contains(url.scheme ?? ""),
                  url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else {
                throw URLError(.badURL)
            }
        }
        return fixture
    }
    private func connectionsControl(_ fixture: ConnectionsFixture, _ action: String, method: String = "GET",
                                    parameters: [String: String] = [:]) throws -> [String: Any] {
        var components = try XCTUnwrap(URLComponents(string: fixture.controlBase + "/" + action))
        components.queryItems = parameters.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: try XCTUnwrap(components.url), cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.httpMethod = method
        request.setValue(fixture.nonce, forHTTPHeaderField: "X-Dulcet-Fixture")
        let response = try XCTUnwrap(JSONSerialization.jsonObject(with: fetch(request)) as? [String: Any])
        XCTAssertEqual(response["nonce"] as? String, fixture.nonce)
        guard response["nonce"] as? String == fixture.nonce else { throw URLError(.badServerResponse) }
        return response
    }
    /// A fixture receipt that names itself: route, method, path, the query and header names (the
    /// fixture keeps no values), the requesting user agent and the test window it fell in.
    private func connectionsReceiptDescription(_ receipt: [String: Any]) -> String {
        let keys = ["routeId", "method", "path", "endpoint", "queryNames", "headerNames", "userAgent",
                    "observationMarker", "observationEpoch", "authorization", "completed"]
        return keys.map { "\($0)=\(receipt[$0].map { "\($0)" } ?? "nil")" }.joined(separator: " ")
    }
    private func connectionsDirectoryReceiptCount(_ fixture: ConnectionsFixture, route: String) throws -> Int {
        let state = try connectionsControl(fixture, "state")
        return (state["requests"] as? [[String: Any]] ?? []).filter {
            $0["routeId"] as? String == route && $0["endpoint"] as? String == "getDulcetConnections" &&
            $0["method"] as? String == "POST" && $0["bodyAuth"] as? Bool == true && $0["completed"] as? Bool == true
        }.count
    }
    private func connectionsSavedAccount() throws -> String {
        let rows = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "dulcet.account.server."))
        // A lazy Form only publishes rows in view; return to the saved-server selector first.
        let first = rows.firstMatch
        try connectionsReveal(first, identifier: "dulcet.account.server.*")
        XCTAssertEqual(rows.count, 1, "Connections actions must not create a separately saved account")
        return try XCTUnwrap(rows.allElementsBoundByIndex.first?.identifier)
    }
    private func connectionsAssertCurrent(_ route: String, fixture: ConnectionsFixture) throws {
        try connectionsAssertStatus(route, "Current HTTPS connection")
        let address = app.staticTexts["dulcet.connections.address.\(route)"].firstMatch
        try connectionsReveal(address, identifier: "dulcet.connections.address.\(route)")
        XCTAssertEqual(connectionsText(address), fixture.routes[route])
        let checked = app.staticTexts["dulcet.connections.checked.\(route)"].firstMatch
        try connectionsReveal(checked, identifier: "dulcet.connections.checked.\(route)")
        XCTAssertTrue(connectionsText(checked).hasPrefix("Last checked:"), "The wall-clock check is visible to accessibility")
        let automatic = app.staticTexts["dulcet.connections.automatic"].firstMatch
        try connectionsReveal(automatic, identifier: "dulcet.connections.automatic")
        XCTAssertTrue(connectionsText(automatic).contains("Automatic"))
        let state = try connectionsControl(fixture, "state")
        let requests = state["requests"] as? [[String: Any]] ?? []
        XCTAssertTrue(requests.contains {
            $0["routeId"] as? String == route && $0["completed"] as? Bool == true &&
            ($0["queryAuth"] as? Bool == true || $0["bodyAuth"] as? Bool == true || $0["authorization"] as? Bool == true)
        }, "Current connection must match a completed authenticated request on that exact listener")
    }
    private func connectionsAssertStatus(_ route: String, _ expected: String) throws {
        let status = app.staticTexts["dulcet.connections.status.\(route)"].firstMatch
        try connectionsReveal(status, identifier: "dulcet.connections.status.\(route)")
        let exactText = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            status.exists && self.connectionsText(status) == expected
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [exactText], timeout: 20), .completed,
            "Expected exact connection status: \(expected)")
    }
    private func connectionsText(_ element: XCUIElement) -> String {
#if os(macOS)
        return element.value as? String ?? element.label
#else
        return element.label
#endif
    }
    private func connectionsActivate(_ identifier: String) throws {
        let button = app.buttons[identifier].firstMatch
        try connectionsReveal(button, identifier: identifier)
        XCTAssertTrue(button.isEnabled, "The real Connections action must be enabled: \(identifier)")
        guard button.isEnabled else { throw URLError(.cannotLoadFromNetwork) }
        activate(button)
        if identifier == "dulcet.connections.verify" || identifier == "dulcet.connections.add" {
            // Settled means the production check has finished: its progress indicator is gone.
            // An accepted Add clears the address field, which leaves Add itself disabled, so its
            // enabled state cannot mark the end; Verify is disabled only while a check runs.
            let verifying = app.descendants(matching: .any)["dulcet.connections.verifying"].firstMatch
            let settled = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                !verifying.exists && button.exists && (identifier == "dulcet.connections.add" || button.isEnabled)
            }, object: nil)
            XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 30), .completed,
                "The production verification must settle before the next control action")
        }
    }
    /// Account holds the saved server, Sign Out and the connect form; on iPhone, iPad and
    /// Apple TV its Connections row pushes the route controls, while the Mac shows them inline.
    private var connectionsPageOpen = false
    private var connectionsInline = false
    private func connectionsSelectAccount() throws {
        try select("settings")
        connectionsPageOpen = false
        connectionsInline = false
    }
    private func connectionsShowPage(_ page: Bool) throws {
#if !os(macOS)
        guard page != connectionsPageOpen else { return }
        let row = app.buttons["dulcet.account.connections"].firstMatch
#if os(tvOS)
        // An Apple TV account that is not connected (offline after its last route was removed,
        // then relaunched) shows Connections inline among the reconnect rows, with no row to push.
        if page {
            let inline = app.textFields["dulcet.connections.https-address"].firstMatch
            let shown = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in row.exists || inline.exists }, object: nil)
            _ = XCTWaiter.wait(for: [shown], timeout: 20)
            connectionsInline = !row.exists && inline.exists
        }
        if connectionsInline {
            connectionsPageOpen = page
            return
        }
#endif
        if page {
            try connectionsScroll(to: row, identifier: "dulcet.account.connections")
            activate(row)
            XCTAssertTrue(row.waitForNonExistence(timeout: 10), "Connections must push its own page")
        } else {
#if os(tvOS)
            XCUIRemote.shared.press(.menu)
#else
            let back = app.navigationBars.firstMatch.buttons.element(boundBy: 0)
            XCTAssertTrue(back.waitForExistence(timeout: 10), "Connections must offer the navigation Back button")
            activate(back)
#endif
            XCTAssertTrue(row.waitForExistence(timeout: 10), "Back must return to Account")
        }
        connectionsPageOpen = page
#endif
    }
    /// The confirmation's reversible choice is whatever the system drew: a popover's dismissal
    /// region (iOS 27 on iPhone and iPad alike) or a Cancel button in the app's own windows,
    /// never the Mac Touch Bar's mirror of it.
    private func connectionsCancelSignOut() throws {
#if os(macOS)
        let cancel = app.windows.buttons["Cancel"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), "Sign Out must offer a reversible Cancel choice")
        activate(cancel)
        XCTAssertTrue(cancel.waitForNonExistence(timeout: 10))
#elseif os(tvOS)
        let cancel = app.buttons["Cancel"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), "Sign Out must offer a reversible Cancel choice")
        activate(cancel)
#else
        let popover = app.popovers.firstMatch
        let cancel = app.buttons["Cancel"].firstMatch
        let presented = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in popover.exists || cancel.exists }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [presented], timeout: 10), .completed, "Sign Out must present its confirmation")
        if popover.exists {
            let dismiss = app.otherElements["PopoverDismissRegion"].firstMatch
            XCTAssertTrue(dismiss.waitForExistence(timeout: 10), "Sign Out must offer reversible dismissal")
            activate(dismiss)
            XCTAssertTrue(popover.waitForNonExistence(timeout: 10))
        } else {
            activate(cancel)
            XCTAssertTrue(cancel.waitForNonExistence(timeout: 10))
        }
#endif
    }
    private func connectionsReveal(_ element: XCUIElement, identifier: String) throws {
        try connectionsShowPage(identifier.hasPrefix("dulcet.connections."))
        try connectionsScroll(to: element, identifier: identifier)
    }
    private func connectionsScroll(to element: XCUIElement, identifier: String) throws {
#if os(tvOS)
        let found = element.waitForExistence(timeout: 20)
#else
        for down in [false, true] {
            for _ in 0..<6 {
                if element.exists && element.isHittable { return }
#if os(macOS)
                let scroll = app.scrollViews.firstMatch
                if down { scroll.swipeDown() } else { scroll.swipeUp() }
#else
                if down { app.swipeDown() } else { app.swipeUp() }
#endif
            }
        }
        let found = element.waitForExistence(timeout: 10)
#endif
        if !found {
            // Keep the failed query and a bounded identity/geometry projection before the
            // fatal assertion: a parent identifier can hide a correctly rendered child.
            let hierarchy = app.debugDescription.split(separator: "\n").filter {
                $0.contains("identifier:") || $0.contains("CollectionView") || $0.contains("Window")
            }.map { String($0).trimmingCharacters(in: .whitespaces) }.joined(separator: "\n")
            let diagnostic = "requested=\(identifier)\nquery=\(element.debugDescription.prefix(4_096))\n" + String(hierarchy.prefix(24_000))
            let attachment = XCTAttachment(string: diagnostic)
            attachment.name = "MP09-missing-\(identifier)-bounded-accessibility"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertTrue(found, "Missing Connections control: \(identifier)")
        guard found else { throw URLError(.cannotLoadFromNetwork) }
    }
    private func connectionsEnterAddress(_ value: String) throws {
        let field = app.textFields["dulcet.connections.https-address"].firstMatch
        try connectionsReveal(field, identifier: "dulcet.connections.https-address")
        activate(field)
#if os(tvOS)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
#endif
        let existing = (field.value as? String).flatMap { $0 == field.placeholderValue ? nil : $0 } ?? ""
        if !existing.isEmpty {
#if os(macOS)
            field.typeKey("a", modifierFlags: .command)
            field.typeText(XCUIKeyboardKey.delete.rawValue)
#elseif os(tvOS)
            // The full-screen keyboard opens with its cursor after the existing text.
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
#else
            // Deleting from wherever the tap left the cursor kept part of the old address.
            // Put the cursor after the last character first, then delete exactly that many.
            field.coordinate(withNormalizedOffset: CGVector(dx: 0.98, dy: 0.5)).tap()
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
#endif
            let empty = (field.value as? String).map { $0.isEmpty || $0 == field.placeholderValue } ?? true
            XCTAssertTrue(empty, "The previous address must be cleared before typing the next one")
        }
        field.typeText(value)
        XCTAssertEqual(field.value as? String, value, "The keyboard must enter the exact chosen HTTPS bootstrap")
#if os(tvOS)
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10))
#elseif os(iOS)
        // Dismiss by dragging the shipping Form, whose scrollDismissesKeyboard is interactive.
        if app.keyboards.firstMatch.exists { app.swipeDown() }
#endif
    }
    private func connectionsCapture(_ name: String) throws {
        let screen = app.screenshot().image
#if os(macOS)
        let size = screen.size
        let ratio = min(1, 900 / max(size.width, size.height))
        let scaledSize = NSSize(width: size.width * ratio, height: size.height * ratio)
        let bitmap = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil,
            pixelsWide: max(1, Int(scaledSize.width)), pixelsHigh: max(1, Int(scaledSize.height)),
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
        screen.draw(in: NSRect(origin: .zero, size: scaledSize))
        NSGraphicsContext.restoreGraphicsState()
        let data = try XCTUnwrap(bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.55]))
#else
        let ratio = min(1, 900 / max(screen.size.width, screen.size.height))
        let size = CGSize(width: screen.size.width * ratio, height: screen.size.height * ratio)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        let scaled = UIGraphicsImageRenderer(size: size, format: format).image { _ in screen.draw(in: CGRect(origin: .zero, size: size)) }
        let data = try XCTUnwrap(scaled.jpegData(compressionQuality: 0.55))
#endif
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.jpeg")
        attachment.name = "\(user)-\(name)-900px-JPEG55"; attachment.lifetime = .keepAlways; add(attachment)
        let geometry = XCTAttachment(string: app.debugDescription)
        geometry.name = "\(user)-\(name)-accessibility-geometry"; geometry.lifetime = .keepAlways; add(geometry)
    }

    @MainActor
    func testNativeBaselineTour() throws {
        guard ProcessInfo.processInfo.environment["DULCET_NATIVE_BASELINE_OUT"] != nil else {
            throw XCTSkip("Opt-in disposable visual census")
        }
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        try launch()
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.section.albums"].waitForExistence(timeout: 30))
        try capture("tvos-81-library-empty")
        try select("browse")
        XCTAssertTrue(app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 30))
        try capture("tvos-82-browse")
        try openAlbum(album.title)
        revealAlbumActions()
        let add = albumMembershipAction(album.id)
        XCTAssertTrue(add.waitForExistence(timeout: 20)); activate(add)
        let added = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "Remove from Library"), object: add)
        XCTAssertEqual(XCTWaiter.wait(for: [added], timeout: 20), .completed)
        try select("library"); try capture("tvos-83-library-personal")
        let songs = app.buttons["dulcet.myLibrary.section.songs"].firstMatch
        XCTAssertTrue(songs.waitForExistence(timeout: 15)); activate(songs)
        try capture("tvos-84-songs")
        try setOffline(true)
        try select("browse"); try capture("tvos-85-offline-library")
        try setOffline(false)
    }

#if os(tvOS)
    @MainActor
    func testFullGridMoreOpensAlbumActionsAndMembershipReachesServer() throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        defer { try? removeAlbum(album.id) }
        try launch()
        try select("browse")
        try addFromFullGridMore(album)
    }

    private func addFromFullGridMore(_ album: (id: String, title: String)) throws {
        try openAlbumList()
        let tile = albumTile(album.title)
        XCTAssertTrue(tile.waitForExistence(timeout: 30), "The full album grid must render the fixture")
        focus(tile)
        XCTAssertTrue(tile.hasFocus, "The full-grid tile must hold remote focus")
        let add = albumMembershipAction(album.id)
        try capture("TV-Full-Grid-Context-Menu")
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        XCTAssertTrue(add.waitForExistence(timeout: 10), "Long Select must open the tile's native context actions")
        activate(add)
        try awaitServerMembership(album.id, added: true)
        // The same tile's primary Select action must still open its detail after dismissal.
        focus(tile)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.staticTexts["dulcet.album.title"].firstMatch.waitForExistence(timeout: 20))
        print("TV FULL GRID MENU PROOF remoteLongSelect=true membershipServer=true primarySelect=true")
    }
#endif

    @MainActor
    func testBrowseAddRemoveRelaunchAndOfflineReplay() throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        try launch()
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.section.albums"].waitForExistence(timeout: 30))
        try select("browse")
#if os(tvOS)
        try addFromFullGridMore(album)
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        try removeAlbum(album.id)
        app.terminate(); try launch()
        try select("browse")
#endif
        try openAlbum(album.title)
        revealAlbumActions()
        let add = albumMembershipAction(album.id)
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        XCTAssertEqual(actionText(add), "Add to Library")
        activate(add)
        awaitLabel(add, "Remove from Library", albumMenu: true)
        // The menu names no album: the server must record this album's membership.
        try awaitServerMembership(album.id, added: true)
#if !os(tvOS)
        dismissAlbumActions(add)
        // The production reader expands the newly added album, and the existing executor
        // downloads it without an explicit Download action. Cut only this account's HTTP
        // route before playing, so the native engine must consume the retained local file.
        let albumResponse = try rest("getAlbum", params: ["id": album.id])
        let detail = try XCTUnwrap(albumResponse["album"] as? [String: Any])
        let songs = try XCTUnwrap(detail["song"] as? [[String: Any]])
        let songTitle = try XCTUnwrap(songs.first?["title"] as? String)
        let song = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@",
                                                    songTitle, "Current track, " + songTitle)).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 20), app.debugDescription)
        let downloaded = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value CONTAINS %@", "Downloaded"), object: song)
        XCTAssertEqual(XCTWaiter.wait(for: [downloaded], timeout: 90), .completed,
                       "Adding an album must automatically download its songs through the production reader")
        try setOffline(true)
        activate(song)
        let playPause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        XCTAssertTrue(playPause.waitForExistence(timeout: 30), app.debugDescription)
        awaitLabel(playPause, "Pause")
        XCTAssertTrue(app.buttons["dulcet.mini-player.open"].firstMatch.label.contains(songTitle))
        try capture("Offline-Automatic-Album-Playback")
        print("OFFLINE-AUTO-UI account=\(user) trigger=album-add downloaded=true route=offline engine=playing")
        app.terminate()
        try setOffline(false)
        try launch()
#endif
        try select("library")
        // The compact root's destination rows precede its lazy Recently Added grid.
        // Relaunch resets its scroll position; reveal the actual tile before observing it.
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        app.terminate()
        try launch(connect: false)
        try select("library")
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Saved account paints its durable My Library without reconnecting")
        activate(albumTile(album.title))
        revealAlbumActions()
        let remove = albumMembershipAction(album.id)
        XCTAssertTrue(remove.waitForExistence(timeout: 20))
        activate(remove)
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.section.albums"].waitForExistence(timeout: 20))
        // Restore online to flush the durable remove, then cut only this fixture's HTTP route.
        app.terminate(); try launch()
        try awaitServerMembership(album.id, added: false)
        try select("browse"); try openAlbum(album.title)
        try setOffline(true)
        revealAlbumActions()
        let offlineAdd = albumMembershipAction(album.id)
        activate(offlineAdd)
        awaitLabel(offlineAdd, "Remove from Library", albumMenu: true)
        try select("library")
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        // A direct read bypasses the offline proxy: it proves the write has not reached the server.
        let pendingBeforeRelaunch = try serverContains(album.id)
        XCTAssertFalse(pendingBeforeRelaunch)
        app.terminate()
#if os(iOS)
        // Native compact rows now fit at default size. Largest text creates the real
        // offscreen/reveal condition without depending on removed introductory content.
        try launch(connect: false, variant: UIDevice.current.userInterfaceIdiom == .phone ? "largest-text" : "light")
#else
        try launch(connect: false)
#endif
        try select("library")
#if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .phone {
            // Require an actual scroll at the largest text size, then require the durable
            // album to publish and become actionable after that scroll.
            // The List may not publish the offscreen section header until it scrolls.
            // Prove the visible category root first; the scroll below must reveal the album.
            XCTAssertTrue(app.buttons["dulcet.myLibrary.section.playlists"].waitForExistence(timeout: 20),
                          "The offline Library category root must paint before revealing its lazy album: " + app.debugDescription)
            XCTAssertFalse(albumTile(album.title).isHittable,
                           "The largest-text offline root must begin with its album below the viewport")
            let scrolls = revealPhoneAlbumTile(albumTile(album.title))
            XCTAssertGreaterThan(scrolls, 0, "The offline relaunch proof must actually reveal the lazy grid")
            XCTAssertTrue(albumTile(album.title).isHittable)
            print("MY LIBRARY OFFLINE RELAUNCH lazy-grid-revealed scrolls=\(scrolls)")
        }
#endif
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Offline relaunch paints the durable pending add before reconnect")
        let pendingAfterRelaunch = try serverContains(album.id)
        XCTAssertFalse(pendingAfterRelaunch)
        app.terminate()
        try setOffline(false)
        try launch()
        try select("library")
        revealPhoneAlbumTile(albumTile(album.title))
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        try removeAlbum(album.id)
    }

    /// Drives the production library on each Apple shell using disposable server metadata.
    @MainActor
    func testLibraryDepthPinSurvivesRelaunchAndOfflineDestinationsRemainReachable() throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try launch()
        try select("browse")
        try openAlbum(album.title)
        revealAlbumActions()
        let pin = albumPinAction(album.id)
        XCTAssertTrue(pin.waitForExistence(timeout: 20))
        if !actionText(pin).hasPrefix("Unpin") { activate(pin) }
        awaitLabel(pin, "Unpin \(album.title)", albumMenu: true)
        app.terminate()
        try setOffline(true)
        try launch(connect: false)
        try select("library")
#if os(macOS)
        // On the hosted Mac, this exact query reports firstMatch=false but count=1 and
        // indexed.exists=true. Avoid the firstMatch shortcut without changing the target.
        let pinnedHeading = app.staticTexts
            .matching(identifier: "dulcet.library.pinned.title").element(boundBy: 0)
#else
        let pinnedHeading = app.staticTexts["dulcet.library.pinned.title"].firstMatch
#endif
        XCTAssertTrue(pinnedHeading.waitForExistence(timeout: 20))
        XCTAssertEqual(pinnedHeading.label, "Pinned", "The actual pinned section heading must be present")
        print("MY LIBRARY PIN heading=\(pinnedHeading.label)")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Pinned metadata must survive an offline relaunch")
        try capture("Library-Depth-Pinned-Offline")
        let downloaded = app.buttons["dulcet.library.downloaded"].firstMatch
#if os(tvOS)
        XCTAssertFalse(downloaded.exists, "Apple TV has no durable user-managed media downloads in v1")
#else
        XCTAssertTrue(downloaded.waitForExistence(timeout: 10))
        activate(downloaded)
        XCTAssertTrue(app.staticTexts["Music stored on this device, ready to play offline."].firstMatch.waitForExistence(timeout: 20))
        try capture("Library-Depth-Downloaded")
        try select("library")
#endif
        try select("browse")
        let songs = app.buttons["dulcet.library.songs"].firstMatch
        XCTAssertTrue(songs.waitForExistence(timeout: 10))
        activate(songs)
#if os(tvOS)
        // The TV Picker is a native segmented control. Restrict its query rather than
        // collecting every accessibility descendant in the cached Songs list.
        let sort = app.segmentedControls["dulcet.library.songs.sort"].firstMatch
        XCTAssertTrue(sort.waitForExistence(timeout: 20))
        XCTAssertTrue(sort.buttons["Title"].exists)
#else
        XCTAssertTrue(app.descendants(matching: .any)["dulcet.library.songs.sort"].firstMatch.waitForExistence(timeout: 20))
#endif
        try capture("Library-Depth-Songs")
        try setOffline(false)
        try select("browse")
        try openAlbum(album.title, waitForReconnect: true)
        revealAlbumActions()
        let unpin = albumPinAction(album.id)
        XCTAssertTrue(unpin.waitForExistence(timeout: 20))
        if actionText(unpin).hasPrefix("Unpin") { activate(unpin) }
        awaitLabel(unpin, "Pin \(album.title)", albumMenu: true)
    }

#if !os(tvOS)
    /// Catalog order, durable category selection, shuffle queue, and artist depth through UI.
    @MainActor
    func testIPhoneLibrarySortShuffleAndArtistDepth() throws {
#if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .phone else { throw XCTSkip("Compact library proof") }
        continueAfterFailure = false
        try setOffline(false)
        try launch()
        try select("browse")
        let albums = app.buttons["dulcet.reader.section.albums"].firstMatch
        try reveal(albums)
        activate(albums)
        let options = app.buttons["dulcet.library.albums.options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 20)); activate(options)
        let sort = app.buttons["dulcet.library.albums.sort"].firstMatch
        XCTAssertTrue(sort.waitForExistence(timeout: 20))
        activate(sort)
        let release = app.buttons["Release Date"].firstMatch
        XCTAssertTrue(release.waitForExistence(timeout: 10))
        activate(release)
        XCTAssertTrue(options.waitForExistence(timeout: 10)); activate(options)
        XCTAssertTrue(sort.waitForExistence(timeout: 10))
        dismissToolbarMenu(options, action: sort)
        XCTAssertTrue(app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 20))
        try capture("Library-Depth-Release-Sort")
        app.terminate()
        try launch(connect: false)
        try select("browse")
        try reveal(albums); activate(albums)
        XCTAssertTrue(options.waitForExistence(timeout: 10)); activate(options)
        XCTAssertTrue(sort.waitForExistence(timeout: 20))
        // UIKit's native submenu exports Sort By without its SwiftUI accessibilityValue.
        // Read the actual selected Picker action inside it after the saved launch.
        activate(sort)
        XCTAssertTrue(release.waitForExistence(timeout: 10))
        XCTAssertTrue(release.isSelected, "The selected Release Date action must survive relaunch")
        dismissToolbarMenu(options, action: release)
        dismissToolbarMenu(options, action: sort)
        // Return through a different tab and reselect Browse to reach its navigation root.
        try select("library"); try select("browse")
        for _ in 0..<8 {
            if app.buttons["dulcet.reader.section.artists"].exists { break }
            let back = app.navigationBars.buttons.firstMatch
            guard back.exists else { break }
            back.tap()
        }
        let artists = app.buttons["dulcet.reader.section.artists"].firstMatch
        try reveal(artists); activate(artists)
        let artist = app.buttons["dulcet.library.artist"].firstMatch
        XCTAssertTrue(artist.waitForExistence(timeout: 20)); activate(artist)
        let artistAlbum = app.buttons["dulcet.library.album"].firstMatch
        try reveal(artistAlbum)
        XCTAssertTrue(artistAlbum.exists, "Artist discography contains the fixture's albums")
        app.scrollViews.firstMatch.swipeUp()
        XCTAssertTrue(artistAlbum.isHittable, "The discography can be brought fully into the compact viewport")
        try capture("Library-Depth-Artist-Discography")
        // The first artist only shares an album credit, so it has no songs of its own, and an
        // artist page leaves out an empty Top Songs. Require it on the fixture's credited artist.
        app.navigationBars.buttons.firstMatch.tap()
        let creditedArtist = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label BEGINSWITH %@",
            "dulcet.library.artist", "Dulcet Fixtures,")).firstMatch
        try reveal(creditedArtist); activate(creditedArtist)
        XCTAssertTrue(app.staticTexts["Top Songs"].firstMatch.waitForExistence(timeout: 20))
        try capture("Library-Depth-Artist-Top-Songs")
        // This proof shuffles the complete server catalog. Its destination is Browse;
        // Library's Songs category contains only personal membership.
        try select("browse")
        let songs = app.buttons["dulcet.library.songs"].firstMatch
        try reveal(songs); activate(songs)
        let shuffle = app.buttons["dulcet.songs.shuffleAll"].firstMatch
        XCTAssertTrue(shuffle.waitForExistence(timeout: 20)); XCTAssertTrue(shuffle.isEnabled)
        activate(shuffle)
        XCTAssertTrue(app.buttons["dulcet.mini-player.open"].firstMatch.waitForExistence(timeout: 30), "Shuffle All creates a real playback queue")
        try capture("Library-Depth-Shuffle-All")
        print("LIBRARY DEPTH IPHONE sort=releaseDate persisted=true artistTopSongs=true discography=true shuffleQueue=true")
#else
        throw XCTSkip("iPhone library proof")
#endif
    }

#if os(iOS)
    @MainActor private func reveal(_ element: XCUIElement) throws {
        for _ in 0..<12 {
            if element.exists && element.isHittable { return }
            let list = app.collectionViews.firstMatch
            if list.exists { list.swipeUp() }
            else { app.scrollViews.firstMatch.swipeUp() }
        }
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        XCTAssertTrue(element.isHittable)
    }
#endif

    @MainActor
    func testLibrarySearchGroupsAndRecentQuerySurviveRelaunch() throws {
        continueAfterFailure = false
        try setOffline(false)
        try launch()
        try select("search")
#if os(iOS)
        let field = dulcetSearchField(in: app)
#else
        let field = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR elementType == %d", "dulcet.search.field", XCUIElement.ElementType.searchField.rawValue)).firstMatch
#endif
        XCTAssertTrue(field.waitForExistence(timeout: 20))
        activate(field)
        field.typeText("Dulcet")
        let artists = app.buttons["dulcet.search.group.artist"].firstMatch
        let albums = app.buttons["dulcet.search.group.album"].firstMatch
        let songs = app.buttons["dulcet.search.group.track"].firstMatch
        XCTAssertTrue(artists.waitForExistence(timeout: 30))
        XCTAssertTrue(albums.waitForExistence(timeout: 30))
        XCTAssertTrue(songs.waitForExistence(timeout: 30))
        activate(artists)
        XCTAssertTrue(app.buttons["dulcet.search.group.all"].firstMatch.waitForExistence(timeout: 10))
        let resultCount = app.staticTexts["dulcet.search.resultCount"].firstMatch
        XCTAssertTrue(resultCount.waitForExistence(timeout: 10))
#if os(macOS)
        let countText = NSPredicate(format: "value == %@", "1 result")
#else
        let countText = NSPredicate(format: "label == %@", "1 result")
#endif
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: countText, object: resultCount)], timeout: 20), .completed,
                       "The selected Artists group counts its own results")
#if os(macOS)
        XCTAssertEqual(resultCount.value as? String, "1 result")
        // SwiftUI's native Table is a SwiftUIOutlineTableView with AXOutline role.
        // Scope it by the rendered result so the sidebar's outline cannot satisfy this proof.
        let artistResults = app.outlines.containing(.staticText, identifier: "dulcet.search.result.0")
            .element(boundBy: 0)
        XCTAssertTrue(artistResults.waitForExistence(timeout: 20), "The selected group must render its real result cell")
        let oneArtistRow = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            artistResults.outlineRows.count == 1
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [oneArtistRow], timeout: 20), .completed,
                       "Artists See All must contain only the fixture artist")
        XCTAssertEqual(artistResults.outlineRows.count, 1)
        print("MY LIBRARY SEARCH selected=Artists count=\(resultCount.value ?? "nil") rows=\(artistResults.outlineRows.count) role=outline")
#else
        XCTAssertEqual(resultCount.label, "1 result")
#endif
        try capture("Library-Depth-Search-Groups")
        activate(field)
        field.typeText("\n")
        app.terminate()
        try launch(connect: false)
        try select("search")
        XCTAssertTrue(app.buttons["dulcet.search.recent.Dulcet"].firstMatch.waitForExistence(timeout: 20), "Submitted query survives relaunch for this account")
        activate(app.buttons["dulcet.search.recent.Dulcet"].firstMatch)
        XCTAssertTrue(artists.waitForExistence(timeout: 20), "Recent query reruns the real reader search")
    }
#endif

    @MainActor
    func testNonDulcetServerKeepsTheOriginalLibraryNavigation() throws {
        continueAfterFailure = false
        try launch(server: navidrome, username: "dulcet-admin", password: "dulcet-ci-canary-password")
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.reader.section.favourites"].waitForExistence(timeout: 30))
        XCTAssertTrue(destination("browse").exists, "Browse is stable on every server")
        try select("browse")
        try openAlbumList()
        let catalog = app.buttons["dulcet.library.album"].firstMatch
        XCTAssertTrue(catalog.waitForExistence(timeout: 30), "The server catalog must load before asserting membership controls are absent")
        XCTAssertFalse(app.descendants(matching: .any)["dulcet.myLibrary"].exists)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dulcet.membership.'")).firstMatch.exists)
    }

    @MainActor
    func testLibraryBrowseAndAlbumInLightDarkAndLargestText() throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        for variant in ["light", "dark", "largest-text"] {
            app.terminate()
            try removeAlbum(album.id)
            try launch(variant: variant)
            try select("library")
            XCTAssertTrue(app.buttons["dulcet.myLibrary.section.albums"].waitForExistence(timeout: 30))
            try capture("Library-\(variant)")
            try select("browse")
            let catalog = app.buttons["dulcet.reader.section.albums"].firstMatch
#if os(iOS)
            try reveal(catalog)
#else
            XCTAssertTrue(catalog.waitForExistence(timeout: 30))
#endif
            XCTAssertTrue(catalog.isHittable, "Browse must expose its Albums destination")
            try capture("Browse-\(variant)")
            try openAlbum(album.title)
            revealAlbumActions()
            let add = albumMembershipAction(album.id)
            XCTAssertTrue(add.waitForExistence(timeout: 30))
            XCTAssertEqual(actionText(add), "Add to Library")
            try captureAlbumAdd("Album-Add-\(variant)", button: add)
        }
    }

    @MainActor
    func testAlbumAddRemainsVisibleAtLargestTextOnIPhone() throws {
#if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .phone else { throw XCTSkip("Compact iPhone capture correction") }
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        try launch(variant: "system-largest-text")
        try select("browse")
        let albums = app.buttons["dulcet.reader.section.albums"].firstMatch
        try reveal(albums)
        try openAlbumList()
        let catalogAlbum = albumTile(album.title)
        let catalogScrolls = revealPhoneAlbumTile(catalogAlbum)
        XCTAssertTrue(catalogAlbum.exists && catalogAlbum.isHittable,
            "The catalog must be visible before searching its albums: " + app.debugDescription)
        print("MY LIBRARY LARGEST TEXT catalog-scrolls=\(catalogScrolls) catalog-visible=true")
        try openAlbum(album.title)
        revealAlbumActions()
        let add = albumMembershipAction(album.id)
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        XCTAssertEqual(actionText(add), "Add to Library")
        try captureAlbumAdd("Album-Add-largest-text", button: add)
#else
        throw XCTSkip("Compact iPhone capture correction")
#endif
    }

    @MainActor private func captureAlbumAdd(_ name: String, button: XCUIElement) throws {
#if os(iOS)
        // More is a native overlay, so its actions can overlap navigation chrome.
        // Require the complete action to be visible and tappable in the real window.
        let frame = button.frame
        XCTAssertTrue(button.isHittable, "The native Add action must be tappable at this text size")
        XCTAssertFalse(frame.isEmpty)
        XCTAssertTrue(app.windows.firstMatch.frame.contains(frame),
                      "The complete native Add action must remain inside the window: \(frame)")
        print("MY LIBRARY CAPTURE \(name) native-add=\(frame) fully-visible=true")
#endif
        try capture(name)
    }

    @MainActor
    private func launch(connect: Bool = true, server: String? = nil, username: String? = nil, password: String? = nil, variant: String = "light") throws {
        var launchArguments = ["-dulcet-debug-ui-markers", "-dulcet-debug-navigation-receipts", variant == "dark" ? "-dulcet-debug-dark-mode" : "-dulcet-debug-light-mode"]
        if variant == "largest-text" { launchArguments += ["-dulcet-debug-largest-text"] }
        // The debug flag sizes SwiftUI's hierarchy but not a toolbar menu's content, which reads
        // the system's size. This variant sets the size a person sets in Settings.
        if variant == "system-largest-text" { launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"] }
        if connect {
            launchArguments += ["-dulcet-debug-connect-account", "-dulcet-debug-account-server-url", server ?? base,
                "-dulcet-debug-account-username", username ?? user, "-dulcet-debug-account-password", password ?? self.password]
        }
        launchArguments += ["-dulcet-debug-account-fixture", accountFixtureNamespace]
        app.launchArguments = launchArguments
#if os(macOS)
        if !installedFixtureNetworkMonitor {
            installedFixtureNetworkMonitor = true
            _ = addUIInterruptionMonitor(withDescription: "Allow the disposable Python fixture's local network") { dialog in
                self.allowFixtureLocalNetwork(dialog)
            }
        }
#endif
        app.launch()
#if os(macOS)
        // The fixture's Python process can trigger macOS's local-network permission dialog.
        // Handle only that named fixture prompt, including when it predates this app's launch.
        let notifications = XCUIApplication(bundleIdentifier: "com.apple.UserNotificationCenter")
        if notifications.state != .notRunning {
            for dialog in notifications.dialogs.allElementsBoundByIndex {
                _ = allowFixtureLocalNetwork(dialog)
            }
        }
        app.activate()
        let window = app.windows.firstMatch
        if !window.waitForExistence(timeout: 10) {
            // The hosted app can expose its menu bar without a window. Start this proof
            // through the same File > New Window action available to a person.
            app.menuBars.menuBarItems["File"].click()
            let newWindow = app.menuItems["New Window"].firstMatch
            XCTAssertTrue(newWindow.waitForExistence(timeout: 5), app.debugDescription)
            XCTAssertTrue(newWindow.isEnabled, "File > New Window must be available")
            newWindow.click()
        }
        XCTAssertTrue(window.waitForExistence(timeout: 10), app.debugDescription)
        let screenWidth = try XCTUnwrap(NSScreen.main?.frame.width)
        let visible = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            window.frame.minX >= 0 && window.frame.maxX <= screenWidth
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [visible], timeout: 10), .completed,
                       "The Mac window must remain on screen after every launch: \(window.frame)")
#elseif os(tvOS)
        if connect {
            // The section bar exists while signed out. Require actual reader content
            // before a Connections control can be evidence of the fixture account.
            let connected = app.buttons["dulcet.library.album"].firstMatch
            let ready = connected.waitForExistence(timeout: 30)
            if !ready {
                let attachment = XCTAttachment(string: String(app.debugDescription.prefix(24_000)))
                attachment.name = "MP09-tv-fixture-connect-bounded-accessibility"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
            XCTAssertTrue(ready, "Disposable TV fixture account must connect before navigating to Connections")
            guard ready else { throw URLError(.cannotConnectToHost) }
        }
#elseif os(iOS)
        if connect {
            // Browse is inserted as soon as capability discovery publishes. On a cold
            // connection its tab can precede the Home landing and catalog publication;
            // tapping during that handoff can leave Home selected. Wait for actual reader
            // content, as the ordinary-server path does, before choosing another destination.
            // A measured cold negotiation remained Connecting beyond 30 seconds under CI
            // host contention, then published the actual album tiles; allow that cold path.
            let connected = app.buttons["dulcet.library.album"].firstMatch
            if !connected.waitForExistence(timeout: 90) {
                let welcome = app.buttons["Connect a Server"]
                if welcome.exists { welcome.tap() }
                let copy = app.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " | ")
                XCTFail("Disposable account fixture failed to connect: \(copy)")
            }
        }
#endif
    }
#if os(macOS)
    @MainActor private func allowFixtureLocalNetwork(_ dialog: XCUIElement) -> Bool {
        let text = dialog.staticTexts.allElementsBoundByIndex.map {
            $0.label + " " + ($0.value as? String ?? "")
        }.joined(separator: " ")
        guard text.contains("Python"), text.contains("local networks") else { return false }
        let allow = dialog.buttons["Allow"].firstMatch
        guard allow.exists else { return false }
        allow.click()
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: dialog)
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 5), .completed)
        print("MY LIBRARY MAC fixture-local-network-prompt handled=Allow")
        return true
    }
#endif
    @MainActor private func destination(_ section: String) -> XCUIElement {
#if os(macOS)
        // Labels also match section headings and toolbar titles. The actual navigation row
        // carries this identifier on its text in the macOS outline's accessibility tree.
        return app.descendants(matching: .any)["dulcet.sidebar.\(section)"].firstMatch
#elseif os(tvOS)
        return app.buttons["dulcet.tab.\(section)"].firstMatch
#else
        let nativeTab = app.tabBars.buttons[section == "library" ? "Library" : section == "browse" ? "Browse" : "Search"].firstMatch
        if nativeTab.exists { return nativeTab }
        if UIDevice.current.userInterfaceIdiom == .pad {
            // Adaptable TabView exposes its native sidebar destination as a Cell.
            let nativeSidebar = app.descendants(matching: .any)["dulcet.tab.\(section)"].firstMatch
            if nativeSidebar.exists { return nativeSidebar }
            return app.descendants(matching: .any)["dulcet.sidebar.\(section)"].firstMatch
        }
        let title = section == "library" ? "Library" : section == "browse" ? "Browse" : section == "settings" ? "Connection" : "Search"
        return app.tabBars.buttons[title].firstMatch
#endif
    }
    @MainActor private func select(_ section: String) throws {
#if os(iOS)
        // Scope native input to this launch's process, as the design tour does.
        // Destination readiness and selected-state checks remain explicit.
        withoutIdleWaits { [self] in selectDestination(section) }
#else
        selectDestination(section)
#endif
    }
    @MainActor private func selectDestination(_ section: String) {
#if os(macOS)
        if section == "settings" {
            // The Mac keeps Account, with its Connections inline, in Dulcet > Settings…, not
            // in the sidebar; the menu's own shortcut opens it, or brings it forward.
            let field = app.descendants(matching: .any)["dulcet.connections.https-address"].firstMatch
            app.typeKey(",", modifierFlags: .command)
            XCTAssertTrue(field.waitForExistence(timeout: 30), "Dulcet > Settings… must open Account with its Connections")
            return
        }
#endif
#if os(iOS)
        if section != "search" {
            let field = app.searchFields.firstMatch
            let closeSearch = app.buttons["Close"].firstMatch
            if field.exists, closeSearch.exists, closeSearch.isHittable,
               abs(closeSearch.frame.midY - field.frame.midY) < field.frame.height {
                closeSearch.tap()
                XCTAssertTrue(field.waitForNonExistence(timeout: 10), "Close native Search before changing destination")
                guard !field.exists else { return }
            } else if UIDevice.current.userInterfaceIdiom == .pad, field.exists, field.isHittable {
                field.tap()
                field.typeText("\n")
            }
        }
        // A saved/debug connection can leave Account presented above the landing page.
        // Its underlying tab frames remain queryable but cannot receive the tap.
        let accountClose = app.buttons["dulcet.account.close"].firstMatch
        if section == "settings" {
            // Account, which holds Connections, is a root toolbar sheet on iPhone and iPad.
            if accountClose.exists { return }
            let account = app.buttons["dulcet.account.open"].firstMatch
            if !account.exists { selectDestination("library") }
            XCTAssertTrue(account.waitForExistence(timeout: 30), "Missing Account toolbar action")
            activate(account)
            XCTAssertTrue(accountClose.waitForExistence(timeout: 10), "Account must present its sheet with Close")
            return
        }
        if accountClose.exists {
            activate(accountClose)
            let dismissed = accountClose.waitForNonExistence(timeout: 10)
            XCTAssertTrue(dismissed, "Dismiss Account before selecting a native destination")
            print("MY LIBRARY IOS account-dismissed=\(dismissed) target=\(section)")
            guard dismissed else { return }
        }
#endif
        let membership = albumMembershipAction()
        if membership.exists {
#if os(macOS)
            app.typeKey(.escape, modifierFlags: [])
#elseif os(tvOS)
            XCUIRemote.shared.press(.menu)
#else
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)).tap()
#endif
        }
#if os(iOS)
        if section == "search", app.searchFields.firstMatch.exists { return }
#endif
        let control = destination(section)
        XCTAssertTrue(control.waitForExistence(timeout: 30), "Missing destination \(section)")
#if os(tvOS)
        // Menu pops a pushed page, then returns focus to the section bar. That focus
        // transition is asynchronous: the retained Songs -> Browse failure sent another
        // Menu before observing it and eventually left the app for the TV Home screen.
        let remote = XCUIRemote.shared
        let focusedTab = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH 'dulcet.tab.' AND hasFocus == true"
        )).firstMatch
        var menuPresses = 0
        while !focusedTab.exists && menuPresses < 6 {
            guard app.state == .runningForeground else {
                XCTFail("Menu navigation left the app before reaching the section bar")
                return
            }
            remote.press(.menu)
            menuPresses += 1
            // Observe the result of this press before deciding whether a page still needs
            // popping. A successful return must prove actual bar focus, not exhaust retries.
            if focusedTab.waitForExistence(timeout: 3) { break }
        }
        guard focusedTab.exists else {
            XCTFail("Menu did not return focus to the section bar for \(section): \(app.debugDescription)")
            return
        }
        print("MY LIBRARY TV section-bar-focused target=\(section) menu-presses=\(menuPresses)")
        let order = ["listenNow", "library", "browse", "search", "nowPlaying", "settings"]
        for _ in 0..<order.count {
            if control.hasFocus {
                XCTAssertTrue(control.hasFocus, "The requested section must actually have remote focus")
                remote.press(.select)
                return
            }
            guard let current = order.firstIndex(where: { app.buttons["dulcet.tab.\($0)"].firstMatch.hasFocus }),
                  let target = order.firstIndex(of: section) else {
                XCTFail("Section-bar focus disappeared while reaching \(section)")
                return
            }
            remote.press(target > current ? .right : .left)
        }
        XCTFail("Remote could not reach \(section): \(app.debugDescription)")
#else
        activateDestination(control)
#if os(iOS)
        // Adaptable tabs can be top-bar Buttons or sidebar Cells on iPad. Observe
        // the destination returned by the native query instead of assuming the legacy
        // sidebar identifier exists. Immediate Search replaces its tab button with a field.
        // Other destinations still require the selected state of their existing control.
        func destinationReached() -> Bool {
            if section == "search", app.searchFields.firstMatch.exists { return true }
            return control.exists && control.isSelected
        }
        let deadline = Date().addingTimeInterval(10)
        var retries = 0
        repeat {
            let remaining = max(0, deadline.timeIntervalSince(Date()))
            let selected = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in destinationReached() }, object: nil)
            if XCTWaiter.wait(for: [selected], timeout: min(3, remaining)) == .completed { return }
            guard Date() < deadline else { break }
            if retries < 2, Date() < deadline, control.exists, control.isHittable {
                retries += 1
                print("MY LIBRARY IOS unacknowledged-destination-tap target=\(section) retry=\(retries)")
                activateDestination(control)
                if destinationReached() { return }
            }
        } while Date() < deadline
        XCTAssertTrue(destinationReached(), "The native destination tap must select \(section): \(app.debugDescription)")
#endif
#if os(macOS)
        // A macOS List does not write its selection binding when its selected row is clicked
        // again. Return through the visible navigation controls, just as Menu pops on tvOS,
        // so a retained album detail cannot hide the Library root's membership/empty state.
        if section == "library" || section == "browse" {
            let back = app.toolbars.buttons["chevron.backward"].firstMatch
            for _ in 0..<6 {
                if !back.exists { break }
                activate(back)
            }
            XCTAssertFalse(back.exists, "The selected destination must reach its root")
        }
#endif
#endif
    }
#if os(iOS)
    /// The same guarded interaction scope used by the account and player UI proofs.
    /// It applies to the current application process and retains ordinary native input.
    @MainActor private func withoutIdleWaits(_ body: @escaping () -> Void) {
        let selector = NSSelectorFromString("_performWithInteractionOptions:block:")
        guard let method = class_getInstanceMethod(type(of: app), selector),
              let encoding = method_getTypeEncoding(method).map({ String(cString: $0) }),
              encoding.filter({ !$0.isNumber }) == "v@:I@?" else {
            print("MY LIBRARY IOS destination interaction options unavailable; using ordinary waits")
            body()
            return
        }
        typealias Perform = @convention(c) (AnyObject, Selector, UInt32, @convention(block) () -> Void) -> Void
        let perform = unsafeBitCast(method_getImplementation(method), to: Perform.self)
        perform(app, selector, 3, body)
    }
#endif
    @MainActor private func albumTile(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == 'dulcet.library.album' AND label BEGINSWITH %@", title)).firstMatch
    }
    @discardableResult
    @MainActor private func revealPhoneAlbumTile(_ tile: XCUIElement) -> Int {
#if os(iOS)
        guard UIDevice.current.userInterfaceIdiom == .phone else { return 0 }
        // Swipe the screen before asserting a lazy tile's publication. Native List exposes a
        // CollectionView and artwork pages a ScrollView, and a push can remove the one just found
        // (run 42166026404: the outgoing list existed, then "No matches" for its swipe).
        for scrolls in 0..<8 {
            if tile.exists && tile.isHittable { return scrolls }
            app.swipeUp()
        }
        return 8
#else
        return 0
#endif
    }
    @MainActor private func openAlbumList(waitForReconnect: Bool = false) throws {
        let albumListLink = "dulcet.reader.section.albums"
        let all = app.buttons[albumListLink].firstMatch
        // Wait for the appropriate catalog link to publish before leaving Browse.
        XCTAssertTrue(waitForAlbumListLink(all, waitForReconnect: waitForReconnect),
                      "Browse must publish a ready album-list link: \(albumListLink). \(app.debugDescription)")
        activate(all)
    }
    @MainActor private func waitForAlbumListLink(_ all: XCUIElement, waitForReconnect: Bool) -> Bool {
#if os(iOS)
        if waitForReconnect && UIDevice.current.userInterfaceIdiom == .pad {
            // Restoring the disposable route does not synchronously reconnect the reader.
            // Its cached page already has the album link, but removing the device-only banner
            // moves that target before native input. Native iPad tabs have no sidebar Online
            // footer; the reader states device-only mode with its own banner. Observe that
            // banner and Reconnect gone, with the link present, within the link's original
            // readiness budget.
            let deviceOnly = app.descendants(matching: .any)["dulcet.reader.deviceOnly"].firstMatch
            let reconnect = app.buttons["dulcet.reader.reconnect"].firstMatch
            let ready = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                !deviceOnly.exists && !reconnect.exists && all.exists
            }, object: nil)
            let completed = XCTWaiter.wait(for: [ready], timeout: 30) == .completed
            if completed { print("MY LIBRARY IPAD reconnect-ready device-only=false reconnect=false album-link=true") }
            return completed
        }
#endif
        return all.waitForExistence(timeout: 30)
    }
    @MainActor private func openAlbum(_ title: String, waitForReconnect: Bool = false) throws {
        // Horizontal Browse shelves instantiate only visible cards. Enter the full list on
        // TV as well, so waiting for a named album does not depend on its shelf position.
        // A retained Browse stack can already show the list. A reconnecting proof always
        // requires the list link's own readiness, so its absence is a failure there.
        if waitForReconnect || app.buttons["dulcet.reader.section.albums"].firstMatch.exists {
            try openAlbumList(waitForReconnect: waitForReconnect)
        }
        let tile = albumTile(title)
#if !os(tvOS)
        // The sidebar is also a scroll view. Wait for catalog publication and scroll only the
        // album page; scrolling firstMatch can hide Library before the catalog finishes loading.
        let albumPage = app.scrollViews.containing(.button, identifier: "dulcet.library.album").firstMatch
        XCTAssertTrue(albumPage.waitForExistence(timeout: 30), "The album page must load before scrolling")
#elseif os(iOS)
        let albumPage = app.scrollViews.containing(.button, identifier: "dulcet.library.album").firstMatch
        XCTAssertTrue(albumPage.waitForExistence(timeout: 30), "The catalog must load before scrolling its tiles")
#endif
#if !os(tvOS)
        let viewport = app.windows.firstMatch.frame.insetBy(dx: 0, dy: 100)
        var swipes = 0
        for _ in 0..<6 {
            if tile.exists && tile.isHittable {
                let frame = tile.frame
                if !frame.isEmpty && viewport.contains(CGPoint(x: frame.midX, y: frame.midY)) { break }
            }
#if os(macOS)
            albumPage.scroll(byDeltaX: 0, deltaY: -450)
#else
            // On iPad the first scroll view may be the sidebar. Scroll the actual catalog,
            // and require the tile's center, not a sliver at the bottom, to enter the window.
            albumPage.swipeUp()
#endif
            swipes += 1
        }
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        XCTAssertTrue(tile.isHittable, "The album must be visible before activation")
        let tileFrame = tile.frame
        XCTAssertTrue(viewport.contains(CGPoint(x: tileFrame.midX, y: tileFrame.midY)),
                      "The album's center must be inside the visible catalog before activation: \(tileFrame)")
        print("MY LIBRARY ALBUM target=\(title) center-in-viewport=true swipes=\(swipes) frame=\(tileFrame)")
#else
        // At the largest text size the first native grid row fills the viewport. Its lazy
        // second row is absent from AX until remote navigation scrolls it into view (run
        // 40547199270). Reach it with the remote before resolving the named tile.
        if !tile.exists {
            let firstTile = app.buttons["dulcet.library.album"].firstMatch
            XCTAssertTrue(firstTile.waitForExistence(timeout: 30), "The album grid must publish before remote navigation")
            focus(firstTile)
            var presses = 0
            for _ in 0..<12 {
                if tile.exists { break }
                XCUIRemote.shared.press(.down)
                presses += 1
            }
            print("MY LIBRARY TV ALBUM target=\(title) reveal-down-presses=\(presses) published=\(tile.exists)")
        }
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
#endif
        activate(tile)
        // Resolve the indexed result, as for the iPad sidebar. On the Mac the firstMatch wait
        // reported absent for 15 s while the failure hierarchy held this exact titled StaticText.
        let header = app.staticTexts.matching(identifier: "dulcet.album.title").element(boundBy: 0)
        let opened = header.waitForExistence(timeout: 15)
        if !opened {
            print("MY LIBRARY NAVIGATION " + app.staticTexts["dulcet.debug.ui-markers"].firstMatch.label)
            print("MY LIBRARY FAILED ALBUM HIERARCHY " + app.debugDescription)
        }
        XCTAssertTrue(opened, "The activated tile must open its album page")
        XCTAssertEqual(header.label, title, "The album page must name the activated tile")
    }
    @MainActor private func activateDestination(_ element: XCUIElement) {
#if os(iOS)
        if element.identifier.hasPrefix("dulcet.tab.") {
            // A native tab can report an invalid synthesized hit point (-1, -1)
            // despite publishing its visible frame. Tap that frame, then require selection.
            let frame = element.frame
            guard frame.width > 0, frame.height > 0, app.frame.contains(frame) else {
                XCTFail("The native destination must have an on-screen frame: " + element.debugDescription)
                return
            }
            let origin = app.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: frame.midX - app.frame.minX,
                                       dy: frame.midY - app.frame.minY)).tap()
            return
        }
#endif
        activate(element)
    }
    @MainActor private func activate(_ element: XCUIElement) {
#if os(tvOS)
        focus(element)
        if isFocused(element) { XCUIRemote.shared.press(.select) }
#elseif os(macOS)
        element.click()
#else
        element.tap()
#endif
    }
#if os(tvOS)
    @MainActor private func focusBrowseShelfAlbum(_ title: String) throws -> XCUIElement {
        // The membership context-menu proof uses Browse's horizontal shelf, its original
        // production path. Real RIGHT movement composes lazy tiles outside the viewport.
        let shelf = app.otherElements["dulcet.reader.home.recentlyAdded"].firstMatch
        let tiles = shelf.buttons.matching(identifier: "dulcet.library.album")
        XCTAssertTrue(tiles.firstMatch.waitForExistence(timeout: 30), "Browse must publish its album shelf")
        focus(tiles.firstMatch)
        let tile = tiles.matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
        for _ in 0..<20 {
            if tile.exists {
                focus(tile)
                XCTAssertTrue(tile.hasFocus, "The named shelf album must hold focus before its menu")
                return tile
            }
            XCUIRemote.shared.press(.right)
        }
        XCTFail("The remote did not reveal shelf album \(title): \(app.debugDescription)")
        return tile
    }
    private func snapshotIfPresent(_ element: XCUIElement) -> XCUIElementSnapshot? {
        guard element.exists else { return nil }
        return try? element.snapshot()
    }
    private func focusedSnapshots() -> (button: XCUIElementSnapshot?, cell: XCUIElementSnapshot?) {
        let button = snapshotIfPresent(app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch)
        let cell = snapshotIfPresent(app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch)
        return (button, cell)
    }
    private func isFocused(
        _ target: XCUIElementSnapshot,
        among focused: (button: XCUIElementSnapshot?, cell: XCUIElementSnapshot?)
    ) -> Bool {
        // tvOS 27 focuses an anonymous child of a native menu button (run 42153962844: More's
        // Other child is the Focused node). Only that control's own subtree counts.
        func containsFocus(_ node: XCUIElementSnapshot) -> Bool {
            node.hasFocus || node.children.contains(where: containsFocus)
        }
        if containsFocus(target) { return true }
        // Native confirmation dialogs expose an identified action and a focused UIKit
        // alias at the same label/frame. The alias carries focus, the identified one does not.
        if let button = focused.button, button.label == target.label, button.frame == target.frame { return true }
        // tvOS context-menu actions sit inside a focused cell rather than holding focus themselves.
        return focused.cell?.frame.contains(CGPoint(x: target.frame.midX, y: target.frame.midY)) == true
    }
    private func isFocused(_ target: XCUIElement) -> Bool {
        guard let target = snapshotIfPresent(target) else { return false }
        return isFocused(target, among: focusedSnapshots())
    }
    /// The Connections page is one column: the routes, then the add group. Its rows publish
    /// frames the remote cannot steer by (zero for a row not yet laid out, a header position for
    /// the field), and a press up past the first row lands in the tab bar, where left and right
    /// switch tabs. Sweep the column instead: down to its end, then up to its top, so one pass
    /// visits every row. Leaving the page for the tab bar only turns the sweep back down.
    private func connectionsFocusSweep(_ target: XCUIElement) {
        // The inline (not connected) form's server, username and password fields hold focus
        // themselves; without them every field reads as "none" and the sweep turns in place.
        func identity(_ focused: (button: XCUIElementSnapshot?, cell: XCUIElementSnapshot?)) -> String {
            let hasFocus = NSPredicate(format: "hasFocus == true")
            let element = focused.cell ?? focused.button
                ?? snapshotIfPresent(app.textFields.matching(hasFocus).firstMatch)
                ?? snapshotIfPresent(app.secureTextFields.matching(hasFocus).firstMatch)
            guard let element else { return "none" }
            return "\(element.identifier)|\(element.label)|\(element.frame)"
        }
        func inTabBar(_ focused: (button: XCUIElementSnapshot?, cell: XCUIElementSnapshot?)) -> Bool {
            (focused.cell ?? focused.button)?.identifier.hasPrefix("dulcet.tab.") == true
        }
        let targetIdentifier = target.identifier
        var direction: XCUIRemote.Button = .down
        var stalled = 0
        for move in 0..<48 {
            if isFocused(target) { return }
            let before = focusedSnapshots()
            if inTabBar(before) { direction = .down }
            XCUIRemote.shared.press(direction)
            let after = focusedSnapshots()
            if inTabBar(after) {
                direction = .down
                stalled = 0
            } else if identity(after) == identity(before) {
                // A text field can take a press to settle (its focus ring animates, the form
                // scrolls), so only a second press that moves nothing marks the column's end.
                stalled += 1
                if stalled >= 2 { direction = direction == .down ? .up : .down; stalled = 0 }
            } else {
                stalled = 0
            }
            print("CONNECTIONS FOCUS move=\(move) target=\(targetIdentifier) focus=\(identity(after)) next=\(direction)")
        }
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "tv-focus-timeout-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "tv-focus-timeout-screen"; screen.lifetime = .keepAlways; add(screen)
        XCTFail("Remote could not focus \(targetIdentifier) on the Connections page")
    }
    private func focus(_ target: XCUIElement) {
        if target.identifier.hasPrefix("dulcet.connections.") {
            connectionsFocusSweep(target)
            return
        }
        func identity(_ element: XCUIElementSnapshot) -> String {
            "\(element.elementType.rawValue)|\(element.identifier)|\(element.label)"
        }
        func opposite(_ direction: XCUIRemote.Button) -> XCUIRemote.Button {
            switch direction {
            case .up: .down
            case .down: .up
            case .left: .right
            default: .left
            }
        }
        let targetIdentifier = target.identifier, targetLabel = target.label
        var lastTargetFrame = target.frame
        var tried: [String: Set<UInt>] = [:]
        var arrival: [String: XCUIRemote.Button] = [:]
        for move in 0..<40 {
            let started = ProcessInfo.processInfo.systemUptime
            // Each live attribute read resolves a fresh accessibility query. Reuse one snapshot
            // per element per move, including diagnostics, instead of resolving every field again.
            let targetSnapshot = snapshotIfPresent(target)
            if targetSnapshot?.hasFocus == true { return }
            let focused = focusedSnapshots()
            if let targetSnapshot {
                if isFocused(targetSnapshot, among: focused) { return }
                lastTargetFrame = targetSnapshot.frame
            }
            let published = targetSnapshot != nil
            guard let current = focused.cell ?? focused.button else {
                XCUIRemote.shared.press(.down)
                continue
            }
            let key = identity(current), a = current.frame, b = lastTargetFrame
            let horizontal: XCUIRemote.Button = b.midX > a.midX ? .right : .left
            let vertical: XCUIRemote.Button = b.midY > a.midY ? .down : .up
            let overlaps = min(a.maxY, b.maxY) > max(a.minY, b.minY)
            let horizontalNeighbor = overlaps && (b.minX >= a.maxX || b.maxX <= a.minX)
            let verticalDistance = abs(a.midY - b.midY) > max(20, min(a.height, b.height) / 2)
            var directions: [XCUIRemote.Button] = horizontalNeighbor || !verticalDistance
                ? [horizontal, vertical, opposite(vertical), opposite(horizontal)]
                : [vertical, horizontal, opposite(vertical), opposite(horizontal)]
            // Focus geometry is a preference, not a route. A tall, staggered grid can
            // require moving back onto the card above a More control before moving left.
            // Remember tried directions by focus identity. A press that scrolls the same
            // focused element still makes progress and remains eligible below. The target
            // itself can leave the lazy snapshot while a route moves back through a card.
            if let entered = arrival[key] {
                let back = opposite(entered)
                directions.removeAll { $0 == back }; directions.append(back)
            }
            let direction = directions.first { !(tried[key] ?? []).contains($0.rawValue) }
                ?? arrival[key].map(opposite) ?? .up
            tried[key, default: []].insert(direction.rawValue)
            print("FOCUS MOVE \(move) direction=\(direction) tried=\(tried[key] ?? []) currentID=\(current.identifier) currentLabel=\(current.label) currentFrame=\(a) targetID=\(targetIdentifier) targetLabel=\(targetLabel) targetFrame=\(b) published=\(published)")
            let queried = ProcessInfo.processInfo.systemUptime
            XCUIRemote.shared.press(direction)
            let pressed = ProcessInfo.processInfo.systemUptime
            let afterFocus = focusedSnapshots()
            if let after = afterFocus.cell ?? afterFocus.button {
                let next = identity(after)
                let afterFrame = after.frame
                if next != key, arrival[next] == nil { arrival[next] = direction }
                if next == key, afterFrame != a { tried[key]?.remove(direction.rawValue) }
                print("FOCUS AFTER \(move) changed=\(next != key) id=\(after.identifier) label=\(after.label) frame=\(afterFrame)")
            } else { print("FOCUS AFTER \(move) none=true") }
            let finished = ProcessInfo.processInfo.systemUptime
            print("FOCUS TIMING \(move) before=\(queried - started) press=\(pressed - queried) after=\(finished - pressed) total=\(finished - started)")
        }
        let hierarchy = XCTAttachment(string: app.debugDescription)
        hierarchy.name = "tv-focus-timeout-hierarchy"; hierarchy.lifetime = .keepAlways; add(hierarchy)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "tv-focus-timeout-screen"; screen.lifetime = .keepAlways; add(screen)
        XCTFail("Remote could not focus \(targetIdentifier), \(targetLabel), last frame \(lastTargetFrame)")
    }
#endif
    /// An action inside the album page's open More menu. On iOS and macOS More is a toolbar Menu,
    /// and its native items do not carry a SwiftUI accessibilityIdentifier, so the action is found
    /// by its visible title inside that menu: a UIKit menu cell (never a page control, which has an
    /// identifier) or an AppKit item under More itself. tvOS 27 shows the same native title on an
    /// unidentified element inside a focusable cell (run 42153962844); an identifier still counts.
    @MainActor private func albumMenuAction(titles: [String], identifier: NSPredicate) -> XCUIElement {
#if os(tvOS)
        let titled = NSPredicate(format: "identifier == '' AND label IN %@", titles)
        return app.descendants(matching: .any)
            .matching(NSCompoundPredicate(orPredicateWithSubpredicates: [identifier, titled])).firstMatch
#elseif os(macOS)
        return app.menuButtons["dulcet.album.more"].menus.firstMatch.menuItems
            .matching(NSPredicate(format: "title IN %@", titles)).firstMatch
#else
        return app.cells.buttons.matching(NSPredicate(format: "identifier == '' AND label IN %@", titles)).firstMatch
#endif
    }
    @MainActor private func albumMembershipAction(_ albumID: String? = nil) -> XCUIElement {
        albumMenuAction(titles: ["Add to Library", "Remove from Library"],
            identifier: albumID.map { NSPredicate(format: "identifier == %@", "dulcet.membership.album.\($0)") }
                ?? NSPredicate(format: "identifier BEGINSWITH %@", "dulcet.membership.album."))
    }
    @MainActor private func albumPinAction(_ albumID: String) -> XCUIElement {
        albumMenuAction(titles: ["Pin to Library", "Unpin from Library"],
            identifier: NSPredicate(format: "identifier == %@", "dulcet.pin.album.\(albumID)"))
    }
    @MainActor private func revealAlbumActions() {
        let action = albumMembershipAction()
#if os(macOS)
        // AppKit can retain a closed menu item's node. Existence alone is not visibility.
        if action.exists && action.isHittable { return }
#else
        if action.exists { return }
#endif
        let more = app.descendants(matching: .any)["dulcet.album.more"].firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 15), "The album must offer More")
        activate(more)
        XCTAssertTrue(action.waitForExistence(timeout: 10), "The native menu must offer the membership action")
    }

    @MainActor private func actionText(_ element: XCUIElement) -> String {
#if os(macOS)
        // Native AppKit menu items publish their visible text as title, with an empty label.
        return element.label.isEmpty ? element.title : element.label
#else
        return element.label
#endif
    }
    @MainActor private func awaitLabel(_ element: XCUIElement, _ label: String, albumMenu: Bool = false) {
        // Reopen before resolving a menu item's identifier: selecting an item closes its menu.
        // A native menu item shows the action's title, not the control's accessibility label.
        let menuTitle = label.hasPrefix("Unpin ") ? "Unpin from Library" : label.hasPrefix("Pin ") ? "Pin to Library" : label
#if os(macOS)
        let expected = NSPredicate(format: "label == %@ OR title == %@ OR title == %@", label, label, menuTitle)
#else
        let expected = NSPredicate(format: "label == %@ OR label == %@", label, menuTitle)
#endif
        // A native menu can show the state from when it opened: reopen it to read a change.
        if albumMenu {
            let reopenUntil = Date().addingTimeInterval(20)
            while Date() < reopenUntil {
                revealAlbumActions()
                if XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: expected, object: element)], timeout: 4) == .completed { return }
#if os(tvOS)
                // Menu on a closed action menu would pop the album page instead.
                if element.exists { XCUIRemote.shared.press(.menu) }
#else
                dismissAlbumActions(element)
#endif
            }
        }
        if albumMenu { revealAlbumActions() }
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: expected, object: element)], timeout: 20), .completed)
    }
#if !os(tvOS)
    @MainActor private func dismissAlbumActions(_ action: XCUIElement) {
        // Reading a changed menu action reopens More. Dismiss that transient surface
        // before a track tap, otherwise the first tap only dismisses the native menu.
        dismissToolbarMenu(app.buttons["dulcet.album.more"].firstMatch, action: action)
    }
    @MainActor private func dismissToolbarMenu(_ anchor: XCUIElement, action: XCUIElement) {
#if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
#else
        // A native menu temporarily hides its toolbar anchor from accessibility. Dismiss
        // only while the action is visible; the outside tap is consumed by the menu.
        guard action.exists && action.isHittable else { return }
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.12)).tap()
#endif
        let closed = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            !action.exists || !action.isHittable
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [closed], timeout: 5), .completed,
                       "The native action menu must close before page interaction")
    }
#endif
    @MainActor private func capture(_ name: String) throws {
#if os(macOS)
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10))
        let image = window.screenshot().image
        let bitmap = try XCTUnwrap(image.tiffRepresentation.flatMap(NSBitmapImageRep.init(data:)))
        let data = try XCTUnwrap(bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.65]))
#else
        let data = try XCTUnwrap(app.screenshot().image.jpegData(compressionQuality: 0.65))
#endif
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.jpeg")
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        if let directory = ProcessInfo.processInfo.environment["DULCET_NATIVE_BASELINE_OUT"] {
            try data.write(to: URL(fileURLWithPath: directory).appendingPathComponent(name + ".jpg"))
        }
    }
    private func fixtureAlbum() throws -> (id: String, title: String) {
        let response = try rest("getAlbumList2", params: ["type": "newest", "size": "500"])
        let list = try XCTUnwrap(response["albumList2"] as? [String: Any])
        let albums = try XCTUnwrap(list["album"] as? [[String: Any]])
        let album = try XCTUnwrap(albums.first(where: { ($0["name"] as? String) == "Threshold Boundary" }))
        return (try XCTUnwrap(album["id"] as? String), try XCTUnwrap(album["name"] as? String))
    }
    private func removeAlbum(_ id: String) throws { _ = try rest("removeFromDulcetLibrary", params: ["albumId": id]) }
    private func serverContains(_ id: String) throws -> Bool {
        let response = try rest("getDulcetLibrary")
        let library = try XCTUnwrap(response["dulcetLibrary"] as? [String: Any])
        return (library["album"] as? [[String: Any]] ?? []).contains { ($0["id"] as? String) == id }
    }
    private func awaitServerMembership(_ id: String, added: Bool) throws {
        let deadline = Date().addingTimeInterval(30)
        repeat {
            if try serverContains(id) == added { return }
            let pulse = XCTestExpectation(description: "Membership poll interval")
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { pulse.fulfill() }
            _ = XCTWaiter.wait(for: [pulse], timeout: 1)
        } while Date() < deadline
        XCTFail("Server did not settle membership \(id) added=\(added)")
    }
    private func rest(_ method: String, params: [String: String] = [:]) throws -> [String: Any] {
        var url = URLComponents(string: "http://127.0.0.1:4783/rest/\(method).view")!
        let query = params.merging(["u": user, "p": password, "v": "1.16.1", "c": "dulcet-ui-proof", "f": "json"]) { _, b in b }
        url.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        let data = try fetch(url.url!)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let response = try XCTUnwrap(body["subsonic-response"] as? [String: Any])
        XCTAssertEqual(response["status"] as? String, "ok")
        return response
    }
    private func fetch(_ url: URL) throws -> Data {
        try fetch(URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15))
    }
    private func fetch(_ request: URLRequest) throws -> Data {
        let completed = XCTestExpectation(description: "Loopback fixture response")
        let result = DulcetFixtureHTTPResult()
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                result.store(.failure(error))
            } else if let data, (response as? HTTPURLResponse)?.statusCode == 200 {
                result.store(.success(data))
            } else {
                result.store(.failure(URLError(.badServerResponse)))
            }
            completed.fulfill()
        }
        task.resume()
        defer { task.cancel() }
        XCTAssertEqual(XCTWaiter.wait(for: [completed], timeout: 16), .completed)
        return try XCTUnwrap(result.load()).get()
    }
    private func setOffline(_ offline: Bool) throws {
        let response = try fetch(URL(string: base + "/fixture/network?offline=\(offline ? "1" : "0")")!)
        XCTAssertEqual(String(data: response, encoding: .utf8), offline ? "offline" : "online",
                       "The disposable route must acknowledge the requested network condition")
    }
}

/// URLSession replies off the test actor; the waiter pumps the test run loop.
private final class DulcetFixtureHTTPResult: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Result<Data, Error>?
    func store(_ result: Result<Data, Error>) {
        lock.lock(); defer { lock.unlock() }
        value = result
    }
    func load() -> Result<Data, Error>? {
        lock.lock(); defer { lock.unlock() }
        return value
    }
}
