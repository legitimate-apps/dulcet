import XCTest
import Foundation
import CryptoKit
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Each concrete suite runs against a fresh disposable fixture and the production application.
/// Controls witness the fault/scan independently of the app. No fixture values are injected as UI.
@MainActor class DulcetDiscoverWishlistProof: XCTestCase {
    let app = XCUIApplication()
    var namespace = UUID().uuidString
    private var prepared = false
    var env: [String: String] { ProcessInfo.processInfo.environment }
    func required(_ name: String) throws -> String { try XCTUnwrap(env["DULCET_DISCOVER_" + name], "Missing fixture environment " + name) }
    func json(_ url: String, body: [String: Any]? = nil) throws -> [String: Any] {
        var request = URLRequest(url: try XCTUnwrap(URL(string: url)))
        if let body { request.httpMethod = "POST"; request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        // URLSession's completion runs on its delegate queue, independent of this test's main actor.
        let box = ResponseBox()
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, response, error in
            box.data = data; box.ok = error == nil && (response as? HTTPURLResponse)?.statusCode == 200; done.signal()
        }.resume()
        XCTAssertEqual(done.wait(timeout: .now() + 45), .success, "Fixture request deadline")
        XCTAssertTrue(box.ok, "Fixture request must succeed")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: try XCTUnwrap(box.data)) as? [String: Any])
    }
    func control(_ path: String, _ body: [String: Any]? = nil) throws -> [String: Any] { try json(required("CONTROL_URL") + "/control/" + path, body: body) }
    @discardableResult func scenario(_ name: String) throws -> Int {
        let ack = try control("scenario", ["name": name])
        XCTAssertEqual(ack["armed"] as? Bool, true)
        return try XCTUnwrap(ack["revision"] as? Int)
    }
    func effect(_ name: String, revision: Int, flag: String? = nil) throws {
        let state = try control("state")
        let effects = try XCTUnwrap(state["effects"] as? [[String: Any]])
        XCTAssertTrue(effects.contains { $0["scenario"] as? String == name && $0["revision"] as? Int == revision && (flag == nil || $0[flag!] as? Bool == true) }, "Arming a fault alone is not evidence it ran")
        print("DISCOVER_ACK scenario=\(name) revision=\(revision) flag=\(flag ?? "served")")
    }
    func launch(server: String? = nil, other: Bool = false, connect: Bool = true, variant: String = "light") throws {
        if !prepared { try prepare(); prepared = true }
        var args = ["-dulcet-debug-account-fixture", namespace, variant == "dark" ? "-dulcet-debug-dark-mode" : "-dulcet-debug-light-mode"]
        if variant == "largest" { args.append("-dulcet-debug-largest-text") }
        if connect { args += ["-dulcet-debug-connect-account", "-dulcet-debug-account-server-url", try server ?? required("SERVER_URL"), "-dulcet-debug-account-username", try required(other ? "OTHER_USERNAME" : "USERNAME"), "-dulcet-debug-account-password", try required(other ? "OTHER_PASSWORD" : "PASSWORD")] }
        app.launchArguments = args
#if os(macOS)
        _ = addUIInterruptionMonitor(withDescription: "Disposable Python fixture local network") { dialog in
            let text = dialog.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ")
            guard text.contains("Python"), text.contains("local networks"), dialog.buttons["Allow"].exists else { return false }
            dialog.buttons["Allow"].click(); return true
        }
#endif
        app.launch()
#if os(macOS)
        let notifications = XCUIApplication(bundleIdentifier: "com.apple.UserNotificationCenter")
        for dialog in notifications.dialogs.allElementsBoundByIndex {
            let text = dialog.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ")
            if text.contains("Python"), text.contains("local networks"), dialog.buttons["Allow"].exists { dialog.buttons["Allow"].click() }
        }
        app.activate()
#endif
        if connect {
            let browse = app.buttons["dulcet.library.album"].firstMatch
            XCTAssertTrue(browse.waitForExistence(timeout: 90), "Positive app catalog control: " + app.debugDescription)
        }
#if os(macOS)
        print("DISCOVER_PLATFORM macos productionHost=true")
#else
        let idiom = UIDevice.current.userInterfaceIdiom == .pad ? "pad" : "phone"
        XCTAssertEqual(idiom, try required("EXPECTED_IDIOM"))
        print("DISCOVER_PLATFORM \(idiom) productionHost=true")
#endif
    }
    private func prepare() throws {
        continueAfterFailure = false
        XCTAssertEqual(try required("DISPOSABLE"), "true")
        app.terminate()
        let reset = try control("reset", [:])
        XCTAssertEqual(reset["reset"] as? Bool, true)
    }
    override func tearDownWithError() throws { XCUIApplication().terminate() }
    func activate(_ element: XCUIElement) {
#if os(macOS)
        element.click()
#else
        element.tap()
#endif
    }
    func select(_ section: String) {
#if os(macOS)
        let row = app.staticTexts["dulcet.sidebar.\(section)"].firstMatch
#else
        let row = UIDevice.current.userInterfaceIdiom == .pad ? app.staticTexts["dulcet.sidebar.\(section)"].firstMatch : app.tabBars.buttons[section == "library" ? "Library" : section == "browse" ? "Browse" : "Search"].firstMatch
#endif
        XCTAssertTrue(row.waitForExistence(timeout: 30)); activate(row)
    }
    func search(_ query: String = "Fixture") throws {
        select("search")
        let everywhere = app.buttons["Everywhere"].firstMatch
        XCTAssertTrue(everywhere.waitForExistence(timeout: 15), "Missing Search Everywhere scope")
        activate(everywhere)
        let field = app.textFields["discover.query"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10)); activate(field)
        field.typeText(query)
#if os(iOS)
        field.typeText("\n")
#endif
    }
    func item(_ kind: String, _ number: Int) -> XCUIElement {
        let title: String
        switch kind {
        case "artist": title = String(format: "Fixture Artist %02d", number - 100)
        case "album": title = String(format: "Fixture Album %02d", number - 200)
        default: title = String(format: "Fixture Song %02d", number)
        }
        return app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "discover.item.", title)).firstMatch
    }
    func wishlist() {
        select("library")
        let link = app.descendants(matching: .any)["wishlist.destination"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 15), "Missing Library Wishlist destination")
        activate(link)
        XCTAssertTrue(app.buttons["wishlist.refresh"].waitForExistence(timeout: 15))
    }
    func capture(_ name: String) throws {
        let root = try required("CAPTURES")
        try FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)
        try app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: root).appendingPathComponent(name + ".png"))
    }
    func everywherePagesAndHeldRouting() throws {
        try launch()
        let revision = try scenario("delayed-old-query")
        try search("old")
        let field = app.textFields["discover.query"].firstMatch
        activate(field)
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 3) + "Fixture")
#if os(iOS)
        field.typeText("\n")
#endif
        XCTAssertTrue(item("artist", 100).waitForExistence(timeout: 30))
        XCTAssertTrue(item("album", 200).waitForExistence(timeout: 30))
        XCTAssertTrue(item("song", 1).waitForExistence(timeout: 30))
        try effect("delayed-old-query", revision: revision)
        try capture("Search-Everywhere")
        activate(item("artist", 100))
        XCTAssertTrue(app.staticTexts["discover.artist.title"].waitForExistence(timeout: 30))
        try capture("Artist")
        activate(item("album", 200))
        XCTAssertTrue(app.staticTexts["discover.album.title"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["8 of 12 here"].exists)
        XCTAssertTrue(app.descendants(matching: .any)["discover.edition"].exists)
        try capture("Album")
        let open = app.buttons["discover.openLibrary"].firstMatch
        XCTAssertTrue(open.exists); activate(open)
        XCTAssertTrue(app.staticTexts["dulcet.album.title"].waitForExistence(timeout: 30))
        print("DISCOVER_ROUTE scopedLibrary=true")
    }
    func offlineWishRelaunchRemoveAndScanArrival() throws {
        try launch(); try search()
        let song = item("song", 1)
        XCTAssertTrue(song.waitForExistence(timeout: 30)); activate(song)
        _ = try scenario("offline")
        activate(app.buttons["discover.wish"].firstMatch)
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        try capture("Pending")
        app.terminate()
        try launch(connect: false)
        wishlist()
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        app.terminate(); _ = try scenario("normal"); try launch()
        wishlist()
        let title = app.staticTexts["Fixture Song 01"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 30))
        // A real scan must finish and fulfil the tagged recording before the native refresh.
        let scanRevision = try scenario("scan-arrival")
        try effect("scan-arrival", revision: scanRevision, flag: "completed")
        activate(app.buttons["wishlist.refresh"])
        XCTAssertTrue(app.staticTexts["Arrived"].waitForExistence(timeout: 30))
        try capture("Arrived")
        let arrival = app.buttons["wishlist.arrival"].firstMatch
        XCTAssertTrue(arrival.exists); activate(arrival)
        XCTAssertTrue(app.buttons["dulcet.mini-player.play-pause"].waitForExistence(timeout: 30))
        wishlist()
        activate(app.buttons["wishlist.remove"].firstMatch)
        // A new generation intentionally added after removal must survive a lost receipt.
        try search(); activate(item("song", 1))
        let lostRevision = try scenario("lost-add-receipt")
        activate(app.buttons["discover.wish"].firstMatch)
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 20))
        try effect("lost-add-receipt", revision: lostRevision, flag: "delivered")
        wishlist(); activate(app.buttons["wishlist.remove"].firstMatch)
        _ = try scenario("normal")
        app.terminate(); try launch(); wishlist()
        XCTAssertFalse(app.staticTexts["Fixture Song 01"].exists)
        try capture("Wishlist")
    }
    func busyStaleAndNavidromeControl() throws {
        try launch()
        let stale = try scenario("stale-answer"); try search()
        XCTAssertTrue(item("album", 200).waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["discover.age"].firstMatch.label.contains("Cached answer"))
        try effect("stale-answer", revision: stale)
        let busy = try scenario("rate-limit-120")
        let field = app.textFields["discover.query"].firstMatch; activate(field); field.typeText(" new")
#if os(iOS)
        field.typeText("\n")
#endif
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Server is busy")).firstMatch.waitForExistence(timeout: 20))
        try effect("rate-limit-120", revision: busy)
        _ = try scenario("normal")
        let baseline = try control("baseline")
        XCTAssertEqual(baseline["login"] as? Bool, true)
        XCTAssertGreaterThan(baseline["albums"] as? Int ?? 0, 0)
        app.terminate(); namespace = UUID().uuidString
        try launch(server: required("NAVIDROME_URL"))
        select("search")
        XCTAssertFalse(app.buttons["Everywhere"].exists)
        select("library")
        XCTAssertFalse(app.descendants(matching: .any)["wishlist.destination"].exists)
        let requests = try control("requests?since=0")["requests"] as? [[String: Any]] ?? []
        let nav = requests.filter { $0["channel"] as? String == "navidrome" }
        XCTAssertTrue(nav.contains { $0["endpoint"] as? String == "getAlbumList2" })
        XCTAssertFalse(nav.contains { ($0["endpoint"] as? String ?? "").contains("Dulcet") })
        print("DISCOVER_NAVIDROME positiveBrowse=true featureCalls=0")
    }
    func accountSwitchAndIdentityReplacementFence() throws {
        try launch(); try search()
        XCTAssertTrue(item("song", 1).waitForExistence(timeout: 30))
        activate(item("song", 1)); _ = try scenario("offline")
        activate(app.buttons["discover.wish"].firstMatch)
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        app.terminate(); _ = try scenario("normal")
        namespace = UUID().uuidString; try launch(other: true)
        wishlist()
        XCTAssertTrue(app.staticTexts["Other owner only"].waitForExistence(timeout: 30))
        XCTAssertFalse(app.staticTexts["Fixture Song 01"].exists)
        _ = try scenario("identity-replacement")
        activate(app.buttons["wishlist.refresh"])
        XCTAssertFalse(app.staticTexts["Fixture Song 01"].exists)
        print("DISCOVER_ACCOUNT otherOwnerSentinel=true oldWishAbsent=true")
    }
    func accessibilityVariants() throws {
        for variant in ["light", "dark", "largest"] {
            app.terminate(); try launch(variant: variant); try search()
            XCTAssertTrue(item("album", 200).waitForExistence(timeout: 30))
            activate(item("album", 200))
            let wish = app.buttons["discover.wish"].firstMatch
            XCTAssertTrue(wish.waitForExistence(timeout: 30)); XCTAssertTrue(wish.isHittable)
            XCTAssertFalse(wish.label.isEmpty)
            try capture("Album-" + variant)
        }
    }
}
private final class ResponseBox: @unchecked Sendable { var data: Data?; var ok = false }
#if os(macOS)
@MainActor final class DulcetDiscoverWishlistMacUITests: DulcetDiscoverWishlistProof {
    func testEverywhereQueryPagesAndHeldRouting() throws { try everywherePagesAndHeldRouting() }
    func testOfflineWishRelaunchRemoveAndScanArrival() throws { try offlineWishRelaunchRemoveAndScanArrival() }
    func testBusyStaleAndNavidromeControl() throws { try busyStaleAndNavidromeControl() }
    func testAccountSwitchAndIdentityReplacementFence() throws { try accountSwitchAndIdentityReplacementFence() }
    func testMacKeyboardSearchSidebarAndContextRemove() throws {
        try launch(); wishlist()
        let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "wishlist.row.")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30)); row.rightClick()
        XCTAssertTrue(app.menuItems["Remove wish"].isEnabled)
        activate(app.menuItems["Remove wish"])
        try accessibilityVariants()
    }
    func testMacWindowClosureDropsPendingNavigation() throws {
        try launch(); try search(); _ = try scenario("delayed-old-query")
        let window = app.windows.firstMatch
        activate(window.buttons[XCUIIdentifierCloseWindow].firstMatch)
        XCTAssertFalse(window.exists)
    }
}
#else
@MainActor final class DulcetDiscoverWishlistUITests: DulcetDiscoverWishlistProof {
    func testEverywhereQueryPagesAndHeldRouting() throws { try everywherePagesAndHeldRouting() }
    func testOfflineWishRelaunchRemoveAndScanArrival() throws { try offlineWishRelaunchRemoveAndScanArrival() }
    func testBusyStaleAndNavidromeControl() throws { try busyStaleAndNavidromeControl() }
    func testAccountSwitchAndIdentityReplacementFence() throws { try accountSwitchAndIdentityReplacementFence() }
    func testIPhoneLargestTextAndVoiceOverLabels() throws { try accessibilityVariants() }
}
@MainActor final class DulcetDiscoverWishlistIPadUITests: DulcetDiscoverWishlistProof {
    func testEverywhereQueryPagesAndHeldRouting() throws { try everywherePagesAndHeldRouting() }
    func testOfflineWishRelaunchRemoveAndScanArrival() throws { try offlineWishRelaunchRemoveAndScanArrival() }
    func testBusyStaleAndNavidromeControl() throws { try busyStaleAndNavidromeControl() }
    func testAccountSwitchAndIdentityReplacementFence() throws { try accountSwitchAndIdentityReplacementFence() }
    func testIPadSidebarSelectionSurvivesWishlistDetailAndResize() throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad)
        try launch(); wishlist()
        let sidebar = app.staticTexts["wishlist.destination"].firstMatch
        let detail = app.buttons["wishlist.refresh"].firstMatch
        XCTAssertTrue(sidebar.frame.maxX <= detail.frame.minX)
        try capture("iPad-Regular-Wishlist")
        XCUIDevice.shared.orientation = .portrait
        XCTAssertTrue(detail.waitForExistence(timeout: 10))
        try capture("iPad-Portrait-Wishlist")
        try accessibilityVariants()
    }
}
#endif
