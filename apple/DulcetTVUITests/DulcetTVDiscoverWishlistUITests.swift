import XCTest
import Foundation
import CryptoKit
import UIKit

/// Production tvOS screens driven only by the remote against the disposable fixture.
@MainActor final class DulcetTVDiscoverWishlistUITests: XCTestCase {
    private let app = XCUIApplication()
    private let namespace = UUID().uuidString
    private var env: [String: String] {
        Dictionary(ProcessInfo.processInfo.environment.map { key, value in
            (key.hasPrefix("TEST_RUNNER_") ? String(key.dropFirst(12)) : key, value)
        }, uniquingKeysWith: { _, value in value })
    }
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
        let deadline = Date().addingTimeInterval(15)
        var state: [String: Any] = [:]
        var witnessed = false
        repeat {
            state = try control("state")
            let effects = try XCTUnwrap(state["effects"] as? [[String: Any]])
            witnessed = effects.contains { $0["scenario"] as? String == name && $0["revision"] as? Int == revision && (flag == nil || $0[flag!] as? Bool == true) }
            if witnessed { break }
            Thread.sleep(forTimeInterval: 0.05)
        } while Date() < deadline
        XCTAssertTrue(witnessed, "Arming a fault alone is not evidence it ran")
        _ = try json(required("CAPTURE_UPLOAD_URL"), body: ["ack": state])
        print("DISCOVER_ACK scenario=\(name) revision=\(revision) flag=\(flag ?? "served")")
    }
    func rest(_ endpoint: String, params: [String: String] = [:], other: Bool = false) throws -> [String: Any] {
        let salt = "discover-native"
        let password = try required(other ? "OTHER_PASSWORD" : "PASSWORD")
        let token = Insecure.MD5.hash(data: Data((password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
        var url = try XCTUnwrap(URLComponents(string: required("SERVER_URL") + "/rest/" + endpoint))
        var args = params; args.merge(["u": try required(other ? "OTHER_USERNAME" : "USERNAME"), "s": salt, "t": token, "c": "dulcet-native-proof", "v": "1.16.1", "f": "json"]) { _, new in new }
        url.queryItems = args.map { URLQueryItem(name: $0.key, value: $0.value) }
        let response = try XCTUnwrap(json(try XCTUnwrap(url.url).absoluteString)["subsonic-response"] as? [String: Any])
        XCTAssertEqual(response["status"] as? String, "ok", "Positive fixture REST control")
        return response
    }
    func wishes(other: Bool = false) throws -> [[String: Any]] {
        let list = try XCTUnwrap(rest("getDulcetWishlist", params: ["size": "100"], other: other)["dulcetWishlist"] as? [String: Any])
        return list["wish"] as? [[String: Any]] ?? []
    }

    private var prepared = false
    private func prepare() throws {
        continueAfterFailure = false
        XCTAssertEqual(try required("DISPOSABLE"), "true")
        app.terminate()
        XCTAssertEqual(try control("reset", [:])["reset"] as? Bool, true)
    }
    override func tearDownWithError() throws { XCUIApplication().terminate() }
    private func launch(navidrome: Bool = false) throws {
        if !prepared { try prepare(); prepared = true }
        app.launchArguments = ["-dulcet-debug-account-fixture", namespace + (navidrome ? "-navidrome" : ""),
            "-dulcet-debug-connect-account", "-dulcet-debug-account-server-url", try required(navidrome ? "NAVIDROME_URL" : "SERVER_URL"),
            "-dulcet-debug-account-username", try required("USERNAME"), "-dulcet-debug-account-password", try required("PASSWORD")]
        app.launch()
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .tv)
        XCTAssertTrue(app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 90), "Positive catalog control")
    }
    private func focused(_ target: XCUIElement) -> Bool {
        guard target.exists else { return false }
        if target.hasFocus { return true }
        let cell = app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch
        return cell.exists && cell.frame.contains(CGPoint(x: target.frame.midX, y: target.frame.midY))
    }
    /// Scroll with focus; consult all element types because native menus expose Other in Cell.
    private func focus(_ target: XCUIElement, limit: Int = 100) {
        var previous: CGRect = .zero
        var alternate = false
        for _ in 0..<limit {
            if focused(target) { return }
            let current = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            guard target.exists else { XCUIRemote.shared.press(.down); continue }
            let from = current.exists ? current.frame : .zero
            let to = target.frame
            let dx = to.midX - from.midX, dy = to.midY - from.midY
            let vertical = abs(dy) >= abs(dx)
            if from == previous { alternate.toggle() }
            let direction: XCUIRemote.Button = (vertical != alternate) ? (dy >= 0 ? .down : .up) : (dx >= 0 ? .right : .left)
            previous = from
            XCUIRemote.shared.press(direction)
        }
        XCTFail("Remote focus failed: " + target.identifier + " " + app.debugDescription)
    }
    private func assertRestoredFocus(_ target: XCUIElement) {
        let end = Date().addingTimeInterval(5)
        while !focused(target), Date() < end { Thread.sleep(forTimeInterval: 0.1) }
        XCTAssertTrue(focused(target), "Back must restore the originating control's remote focus")
    }
    private func activate(_ target: XCUIElement) {
        _ = target.waitForExistence(timeout: 5)
        focus(target)
        XCTAssertTrue(target.exists)
        XCTAssertTrue(focused(target), "Select must activate the intended focused control")
        XCUIRemote.shared.press(.select)
    }
    private func button(_ id: String) -> XCUIElement { app.buttons[id].firstMatch }
    private func section(_ id: String) {
        let target = button(id == "discover" ? "discover.destination" : "dulcet.tab." + id)
        for _ in 0..<8 {
            if app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND hasFocus == true", "dulcet.tab.")).firstMatch.exists || focused(button("discover.destination")) { break }
            XCUIRemote.shared.press(.menu)
        }
        activate(target)
    }
    private func firstButton(prefix: String, suffix: String = "") -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND identifier ENDSWITH %@", prefix, suffix)).firstMatch
    }
    private func wishCount(_ title: String, _ count: Int) throws {
        let end = Date().addingTimeInterval(30)
        repeat {
            if try wishes().filter({ ($0["snapshot"] as? [String: Any])?["title"] as? String == title }).count == count { return }
            Thread.sleep(forTimeInterval: 0.25)
        } while Date() < end
        XCTFail("Authoritative wish count did not settle")
    }
    private func capture(_ name: String) throws {
        let image = app.screenshot().image
        let size = CGSize(width: 960, height: image.size.height * 960 / image.size.width)
        let reduced = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        let data = try XCTUnwrap(reduced.jpegData(compressionQuality: 0.5))
        XCTAssertEqual(try json(required("CAPTURE_UPLOAD_URL"), body: ["name": name, "jpeg": data.base64EncodedString()])["stored"] as? Bool, true)
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.jpeg")
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testTVDiscoverShelvesSearchWishScanAndRemoveByRemote() throws {
        try launch()
        let mixed = try scenario("shelves-mixed")
        section("discover")
        XCTAssertTrue(button("discover.home.refresh").waitForExistence(timeout: 30))
        try effect("shelves-mixed", revision: mixed)
        try capture("TV-Discover-Focused")
        activate(button("discover.shelf.trendingArtists.all"))
        activate(firstButton(prefix: "discover.shelfList.trendingArtists.item.", suffix: ".wish"))
        try wishCount("Fixture Artist 00", 1)
        let artist = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@ AND NOT (identifier ENDSWITH %@)", "discover.shelfList.trendingArtists.item.", "Fixture Artist 00", ".wish")).firstMatch
        activate(artist)
        XCTAssertTrue(firstButton(prefix: "discover.item.").waitForExistence(timeout: 30), "Discover artist must show its discography")
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(artist.waitForExistence(timeout: 15))
        assertRestoredFocus(artist)
        activate(button("discover.shelfList.trendingArtists.more"))
        let tail = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "discover.shelfList.trendingArtists.item.", "Fixture Artist 23")).firstMatch
        focus(tail); XCTAssertTrue(focused(tail))
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(button("discover.home.refresh").waitForExistence(timeout: 15))
        activate(button("discover.shelf.trendingAlbums.all"))
        let album = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND NOT (identifier ENDSWITH %@)", "discover.shelfList.trendingAlbums.item.", ".wish")).firstMatch
        activate(album)
        XCTAssertTrue(app.descendants(matching: .any)["discover.edition"].firstMatch.waitForExistence(timeout: 30))
        XCUIRemote.shared.press(.menu); XCUIRemote.shared.press(.menu)
        activate(button("discover.shelf.publicPlaylists.all"))
        let playlistID = "00000258-1111-4111-8111-111111111111"
        activate(button("discover.playlists.item." + playlistID))
        let prefix = "discover.playlist." + playlistID
        XCTAssertTrue(button(prefix + ".entry.1").waitForExistence(timeout: 30))
        focus(button(prefix + ".entry.2")); XCTAssertTrue(focused(button(prefix + ".entry.2")))
        XCTAssertEqual(button(prefix + ".entry.1").label, button(prefix + ".entry.2").label)
        focus(button(prefix + ".entry.3")); XCTAssertTrue(focused(button(prefix + ".entry.3")))
        XCTAssertFalse(button(prefix + ".entry.3.wish").exists)
        activate(button(prefix + ".entry.4.wish"))
        try wishCount("Fixture Song 03", 1)
        activate(button(prefix + ".more"))
        focus(button(prefix + ".entry.24")); XCTAssertTrue(focused(button(prefix + ".entry.24")))
        try capture("TV-Public-Playlist")
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(button("discover.playlists.item." + playlistID).waitForExistence(timeout: 15), "Back returns to the parent list")
        assertRestoredFocus(button("discover.playlists.item." + playlistID))
        XCUIRemote.shared.press(.menu)
        XCTAssertTrue(button("discover.home.refresh").waitForExistence(timeout: 15))
        let rows = try wishes()
        XCTAssertTrue(rows.contains { ($0["source"] as? [String: Any])?["type"] as? String == "shelf" && ($0["source"] as? [String: Any])?["id"] as? String == "trendingArtists" })
        XCTAssertTrue(rows.contains { ($0["source"] as? [String: Any])?["type"] as? String == "playlist" && ($0["source"] as? [String: Any])?["id"] as? String == playlistID })
        section("search")
        activate(app.buttons["Everywhere"].firstMatch)
        let query = app.textFields["discover.query"].firstMatch
        activate(query)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        query.typeText("Fixture")
        let committed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "Fixture"), object: query)
        XCTAssertEqual(XCTWaiter.wait(for: [committed], timeout: 10), .completed)
        let done = app.buttons["done"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        for _ in 0..<6 where !done.hasFocus { XCUIRemote.shared.press(.down) }
        XCTAssertTrue(done.hasFocus)
        XCUIRemote.shared.press(.select)
        let song = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "discover.item.", "Fixture Song 01")).firstMatch
        activate(song); activate(button("discover.wish"))
        try wishCount("Fixture Song 01", 1)
        section("library"); activate(button("wishlist.destination"))
        XCTAssertTrue(app.staticTexts["Fixture Song 01"].firstMatch.waitForExistence(timeout: 30))
        let scan = try scenario("scan-arrival")
        try effect("scan-arrival", revision: scan, flag: "completed")
        activate(button("wishlist.refresh"))
        let arrived = button("wishlist.arrival")
        XCTAssertTrue(arrived.waitForExistence(timeout: 30))
        try capture("TV-Wishlist-Arrived")
        let arrivedRow = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "wishlist.row.")).containing(.button, identifier: "wishlist.arrival").firstMatch
        activate(arrivedRow.buttons["wishlist.remove"].firstMatch)
        try wishCount("Fixture Song 01", 0)
        _ = try json(required("CAPTURE_UPLOAD_URL"), body: ["ack": ["scenario": "tv-flow", "shelfPaging": 24, "playlistPositions": 24, "sources": true, "searched": true, "arrived": true, "removed": true]])
        print("DISCOVER_TV shelfPaging=24 album=true playlistPositions=24 sources=shelf,playlist searchEverywhere=true scanArrival=true removed=true remote=true")
    }
    func testTVDiscoverStatesAndNavidromeByRemote() throws {
        try launch()
        let empty = try scenario("shelves-empty")
        section("discover")
        XCTAssertTrue(app.staticTexts["discover.shelf.trendingArtists.empty"].waitForExistence(timeout: 30))
        try effect("shelves-empty", revision: empty)
        let stale = try scenario("shelves-stale")
        activate(button("discover.home.refresh"))
        XCTAssertTrue(app.staticTexts["discover.shelf.trendingArtists.age"].waitForExistence(timeout: 30))
        try effect("shelves-stale", revision: stale)
        let unavailable = try scenario("shelves-unavailable")
        activate(button("discover.home.refresh"))
        XCTAssertTrue(app.staticTexts["discover.shelf.trendingArtists.error"].waitForExistence(timeout: 30))
        try effect("shelves-unavailable", revision: unavailable)
        XCTAssertTrue(app.staticTexts["discover.shelf.trendingArtists.age"].exists)
        XCTAssertFalse(app.staticTexts["discover.shelf.trendingArtists.empty"].exists)
        try capture("TV-Cached-Failure")
        app.terminate(); try launch(navidrome: true)
        XCTAssertFalse(button("discover.destination").exists)
        section("search")
        XCTAssertFalse(app.descendants(matching: .any)["discover.scope"].exists)
        section("library")
        XCTAssertFalse(button("wishlist.destination").exists)
        let requests = try control("requests?since=0")["requests"] as? [[String: Any]] ?? []
        let baseline = requests.filter { $0["channel"] as? String == "navidrome" }
        XCTAssertTrue(baseline.contains { $0["endpoint"] as? String == "getAlbumList2" })
        let calls = baseline.filter { ($0["endpoint"] as? String ?? "").contains("Dulcet") }.count
        XCTAssertEqual(calls, 0)
        _ = try json(required("CAPTURE_UPLOAD_URL"), body: ["ack": ["scenario": "navidrome-baseline", "baseline": ["login": true, "albums": 1, "featureCalls": calls]]])
        print("DISCOVER_TV empty=true cachedFailure=true navidromeHidden=true featureCalls=0 remote=true")
    }
}

private final class ResponseBox: @unchecked Sendable { var data: Data?; var ok = false }
