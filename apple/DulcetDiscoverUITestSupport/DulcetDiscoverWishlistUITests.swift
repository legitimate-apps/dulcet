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
    func launch(server: String? = nil, other: Bool = false, connect: Bool = true, variant: String = "light") throws {
        if !prepared { try prepare(); prepared = true }
        var args = ["-dulcet-debug-ui-markers", "-dulcet-debug-account-fixture", namespace, variant == "dark" ? "-dulcet-debug-dark-mode" : "-dulcet-debug-light-mode"]
        if variant == "largest" { args.append("-dulcet-debug-largest-text") }
        if other { args += ["-dulcet-debug-add-account"] }
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
            XCTAssertTrue(waitForCatalog(timeout: 90), "Positive app catalog control must expose an album tile")
        }
#if os(macOS)
        print("DISCOVER_PLATFORM macos productionHost=true")
#else
        let idiom = UIDevice.current.userInterfaceIdiom == .pad ? "pad" : "phone"
        XCTAssertEqual(idiom, try required("EXPECTED_IDIOM"))
        print("DISCOVER_PLATFORM \(idiom) productionHost=true")
#endif
    }
    private func waitForCatalog(timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            // During cold connection and initial Listen Now loading, a live descendant query
            // can fail its own snapshot request before the readiness deadline. A failed read
            // is not catalog evidence. Re-read one immutable tree within the same deadline.
            if let root = try? app.snapshot() {
                var pending: [XCUIElementSnapshot] = [root]
                while let node = pending.popLast() {
                    if node.elementType == .button && node.identifier == "dulcet.library.album" { return true }
                    pending.append(contentsOf: node.children)
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
        } while Date() < deadline
        return false
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
        let row = app.descendants(matching: .any)["dulcet.sidebar.\(section)"].firstMatch
#else
        var tab = app.tabBars.buttons["dulcet.tab." + section].firstMatch
        if !tab.waitForExistence(timeout: 2) {
            // On a cold offline launch UIKit retains the native tab labels but drops the
            // identifiers attached to SwiftUI's tabItem labels. Stay inside the real tab bar.
            let titles = ["listenNow": "Listen Now", "library": "Library", "browse": "Browse", "search": "Search", "settings": "Connection"]
            tab = app.tabBars.buttons[titles[section] ?? section].firstMatch
        }
        let row = UIDevice.current.userInterfaceIdiom == .pad && !tab.exists ? app.descendants(matching: .any)["dulcet.sidebar.\(section)"].firstMatch : tab
#endif
        XCTAssertTrue(row.waitForExistence(timeout: 30))
        let reachable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: row)
        XCTAssertEqual(XCTWaiter.wait(for: [reachable], timeout: 30), .completed, "Destination must be reachable before activation")
        activate(row)
    }
    func selectSavedServer(_ button: XCUIElement) {
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: button)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 30), .completed)
        activate(button)
        // Connecting a saved account returns the real app to Listen Now. Reopen Connection
        // before checking its current-account label; the departing button cannot stay visible.
        let settled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false OR (label CONTAINS %@ AND enabled == true)", "Current server"), object: button)
        XCTAssertEqual(XCTWaiter.wait(for: [settled], timeout: 45), .completed)
        if !button.exists { select("settings") }
        let committed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label CONTAINS %@ AND enabled == true", "Current server"), object: button)
        XCTAssertEqual(XCTWaiter.wait(for: [committed], timeout: 45), .completed, "Saved account must commit before navigating: " + app.debugDescription)
    }
    func search(_ query: String = "Fixture") throws {
        select("search")
        let everywhere = app.buttons["Everywhere"].firstMatch
        XCTAssertTrue(everywhere.waitForExistence(timeout: 15), "Missing Search Everywhere scope")
        activate(everywhere)
        let field = app.textFields["discover.query"].firstMatch
        if !field.exists {
            // Switching scopes is the public way to return from a retained metadata stack.
            activate(app.buttons["Your server"].firstMatch); activate(everywhere)
        }
        XCTAssertTrue(field.waitForExistence(timeout: 10)); activate(field)
        let current = (field.value as? String).flatMap { $0 == field.placeholderValue ? nil : $0 } ?? ""
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: current.count) + query)
#if os(iOS)
        field.typeText("\n")
#endif
    }
    func reveal(_ element: XCUIElement) {
        if element.waitForExistence(timeout: 2) && element.isHittable { return }
        for direction in [true, false] {
            for _ in 0..<10 {
                if element.exists && element.isHittable { return }
#if os(macOS)
                app.scrollViews.firstMatch.scroll(byDeltaX: 0, deltaY: direction ? -500 : 500)
#else
                // SwiftUI List is a CollectionView; metadata pages use a ScrollView.
                // Exclude the split-view sidebar so scrolling stays in the presented content.
                let scroll = app.scrollViews.firstMatch
                let body = scroll.exists ? scroll : app.collectionViews.matching(NSPredicate(format: "label != %@", "Sidebar")).firstMatch
                guard body.exists else { return }
                if direction { body.swipeUp() } else { body.swipeDown() }
#endif
            }
        }
    }
    func item(_ kind: String, _ number: Int) -> XCUIElement {
        let title: String
        switch kind {
        case "artist": title = String(format: "Fixture Artist %02d", number - 100)
        case "album": title = String(format: "Fixture Album %02d", number - 200)
        default: title = String(format: "Fixture Song %02d", number)
        }
        let result = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "discover.item.", title)).firstMatch
        reveal(result)
        return result
    }
    func wishlist() {
        select("library")
        if app.buttons["wishlist.refresh"].exists { return }
        let link = app.descendants(matching: .any)["wishlist.destination"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 15), "Missing Library Wishlist destination: " + app.debugDescription)
        activate(link)
        XCTAssertTrue(app.buttons["wishlist.refresh"].waitForExistence(timeout: 15), "Wishlist activation must present content: " + app.debugDescription)
    }
    func capture(_ name: String) throws {
#if os(macOS)
        let bitmap = try XCTUnwrap(app.windows.firstMatch.screenshot().image.tiffRepresentation.flatMap(NSBitmapImageRep.init(data:)))
        let data = try XCTUnwrap(bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.65]))
#else
        let image = app.screenshot().image
        print("DISCOVER_CAPTURE name=\(name) imageSize=\(image.size) windowFrame=\(app.windows.firstMatch.frame) deviceOrientation=\(XCUIDevice.shared.orientation.rawValue)")
        let data = try XCTUnwrap(image.jpegData(compressionQuality: 0.65))
#endif
        let ack = try json(required("CAPTURE_UPLOAD_URL"), body: ["name": name, "jpeg": data.base64EncodedString()])
        XCTAssertEqual(ack["stored"] as? Bool, true)
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.jpeg")
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }

    func assertPlaybackReference(_ localId: String) {
        let log = app.staticTexts["dulcet.debug.ui-markers"].firstMatch
        XCTAssertTrue(log.waitForExistence(timeout: 10))
        let entry = log.label.split(separator: " ").last { $0.hasPrefix("discover-play:") }
        let parts = entry?.split(separator: ":").map(String.init) ?? []
        XCTAssertEqual(parts.count, 3)
        XCTAssertNotNil(parts.count > 1 ? UUID(uuidString: parts[1]) : nil, "Actual local account namespace")
        XCTAssertEqual(parts.last, localId, "Actual track supplied to the existing player")
    }
    func everywherePagesAndHeldRouting() throws {
        try launch()
        let revision = try scenario("delayed-old-query")
        try search("old")
        try effect("delayed-old-query", revision: revision, flag: "held")
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
        for _ in 0..<50 {
            let effects = try control("state")["effects"] as? [[String: Any]] ?? []
            if effects.contains(where: { $0["revision"] as? Int == revision && $0["payload"] as? String == "old" }) { break }
            Thread.sleep(forTimeInterval: 0.1)
        }
        let queryEffects = try control("state")["effects"] as? [[String: Any]] ?? []
        let payloads = queryEffects.compactMap { $0["payload"] as? String }
        XCTAssertTrue(payloads.contains("new") && payloads.contains("old"))
        XCTAssertLessThan(try XCTUnwrap(payloads.firstIndex(of: "new")), try XCTUnwrap(payloads.firstIndex(of: "old")))
        Thread.sleep(forTimeInterval: 1.0)
        let obsolete = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Obsolete old query · ")).firstMatch
        XCTAssertFalse(obsolete.exists, "Late old-query payload must not replace the new answer")
        XCTAssertTrue(item("artist", 100).exists)
        try capture("Search-Everywhere")
        activate(item("artist", 100))
        XCTAssertTrue(app.staticTexts["discover.artist.title"].waitForExistence(timeout: 30))
        try capture("Artist")
        activate(item("album", 200))
        XCTAssertTrue(app.staticTexts["discover.album.title"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["8 of 12 here"].exists)
        let edition = app.descendants(matching: .any)["discover.edition"].firstMatch
        XCTAssertTrue(edition.exists); activate(edition)
#if os(macOS)
        let later = app.menuItems.matching(NSPredicate(format: "label BEGINSWITH %@", "2026-02-01")).firstMatch
#else
        let later = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "2026-02-01")).firstMatch
#endif
        XCTAssertTrue(later.waitForExistence(timeout: 10)); activate(later)
        for _ in 0..<30 {
            if edition.value as? String == "00000190-1111-4111-8111-111111111111" { break }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTAssertEqual(edition.value as? String, "00000190-1111-4111-8111-111111111111")
        try capture("Album")
        let open = app.buttons["discover.openLibrary"].firstMatch
        reveal(open); XCTAssertTrue(open.exists); activate(open)
        XCTAssertTrue(app.staticTexts["dulcet.album.title"].waitForExistence(timeout: 30))
        let albums = try XCTUnwrap(rest("getAlbumList2", params: ["type": "alphabeticalByName", "size": "100"])["albumList2"] as? [String: Any])
        let localAlbum = try XCTUnwrap((albums["album"] as? [[String: Any]])?.first { $0["name"] as? String == "Fixture Album 00" })
        let albumId = try XCTUnwrap(localAlbum["id"] as? String)
        XCTAssertTrue(app.staticTexts["dulcet.debug.ui-markers"].firstMatch.label.contains(":album:" + albumId))
        let album = try XCTUnwrap(rest("getAlbum", params: ["id": albumId])["album"] as? [String: Any])
        let track = try XCTUnwrap((album["song"] as? [[String: Any]])?.first { $0["title"] as? String == "Fixture Song 10" })
        let trackId = try XCTUnwrap(track["id"] as? String)
        try search(); activate(item("song", 10))
        let play = app.buttons["discover.openLibrary"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 15)); activate(play)
        XCTAssertTrue(app.buttons["dulcet.mini-player.play-pause"].waitForExistence(timeout: 30))
        assertPlaybackReference(trackId)
        print("DISCOVER_ROUTE scopedAlbumId=\(albumId) scopedPlaybackId=\(trackId)")
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
    func waitForWish(_ title: String, count: Int) throws {
        for _ in 0..<40 {
            if try wishes().filter({ ($0["snapshot"] as? [String: Any])?["title"] as? String == title }).count == count { return }
            Thread.sleep(forTimeInterval: 0.25)
        }
        XCTFail("Server wish count did not settle to \(count) for generated fixture title")
    }
    func wishRow(_ title: String) -> XCUIElement {
        // Artist and album titles can open metadata; a song title is plain text.
        // Both identify their own row without requiring that optional action.
        let row = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "wishlist.row.")).containing(NSPredicate(format: "label == %@", title)).firstMatch
        reveal(row)
        return row
    }
    func offlineWishRelaunchRemoveAndScanArrival() throws {
        let title = "Fixture Song 01"
        try launch(); try search()
        let song = item("song", 1)
        XCTAssertTrue(song.waitForExistence(timeout: 30)); activate(song)
        _ = try scenario("offline")
        activate(app.buttons["discover.wish"].firstMatch)
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        try capture("Pending")
        app.terminate(); try launch(connect: false); wishlist()
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        app.terminate(); _ = try scenario("normal"); try launch(); wishlist()
        try waitForWish(title, count: 1)
        activate(wishRow(title).buttons["wishlist.remove"].firstMatch)
        try waitForWish(title, count: 0)
        // Observe A's durable identity before sending it. Foundation may automatically retry
        // the GET after the fixture drops its first receipt; that recovery must not erase the
        // identity used by our independent late replay after removal and scan.
        try search(); let sourceId = item("song", 1).identifier.replacingOccurrences(of: "discover.item.", with: "")
        activate(item("song", 1)); _ = try scenario("offline")
        activate(app.buttons["discover.wish"].firstMatch)
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        wishlist()
        let operationId = wishRow(title).identifier.replacingOccurrences(of: "wishlist.row.add:", with: "")
        XCTAssertNotNil(UUID(uuidString: operationId), "Observed native pending add identity")
        app.terminate()
        let lostRevision = try scenario("lost-add-receipt")
        try launch(); wishlist()
        try effect("lost-add-receipt", revision: lostRevision, flag: "delivered")
        let deliveredWish = try XCTUnwrap(wishes().first { ($0["snapshot"] as? [String: Any])?["title"] as? String == title })
        _ = try scenario("offline")
        activate(wishRow(title).buttons["wishlist.remove"].firstMatch)
        XCTAssertFalse(app.staticTexts[title].exists, "Durable remove hides A before reconnect")
        let scanRevision = try scenario("scan-arrival")
        try effect("scan-arrival", revision: scanRevision, flag: "completed")
        app.terminate(); try launch(); wishlist()
        try waitForWish(title, count: 0)
        var replayParams = ["kind": "song", "mbid": "00000001-1111-4111-8111-111111111111", "title": title, "sourceType": "search", "sourceId": sourceId, "operationId": operationId, "actionTime": try XCTUnwrap(deliveredWish["addedAt"] as? String)]
        let snapshot = try XCTUnwrap(deliveredWish["snapshot"] as? [String: Any])
        for key in ["artist", "artworkId"] { if let value = snapshot[key] as? String { replayParams[key] = value } }
        if let year = snapshot["year"] as? Int { replayParams["year"] = String(year) }
        let replay = try XCTUnwrap(rest("addToDulcetWishlist", params: replayParams)["dulcetWishMutation"] as? [String: Any])
        XCTAssertEqual(replay["removed"] as? Bool, true)
        try waitForWish(title, count: 0)
        let library = try XCTUnwrap(rest("getDulcetLibrary", params: ["type": "song", "size": "100"])["dulcetLibrary"] as? [String: Any])
        let member = try XCTUnwrap((library["song"] as? [[String: Any]])?.first { $0["title"] as? String == title })
        let localId = try XCTUnwrap(member["id"] as? String)
        _ = try json(required("CAPTURE_UPLOAD_URL"), body: ["ack": ["scenario": "lost-add-receipt-replay", "replay": replay, "myLibraryItem": member]])
        print("DISCOVER_ACK lostReceiptReplayed=true removed=true myLibraryRetained=true localId=\(localId)")
        try capture("Wishlist")
        // Deliberate generation B remains independent of A's tombstone.
        try search(); activate(item("song", 1)); activate(app.buttons["discover.wish"].firstMatch)
        try waitForWish(title, count: 1)
        let secondScan = try scenario("scan-arrival"); try effect("scan-arrival", revision: secondScan, flag: "completed")
        wishlist(); activate(app.buttons["wishlist.refresh"])
        let arrivedRow = wishRow(title)
        XCTAssertTrue(arrivedRow.staticTexts["Arrived"].waitForExistence(timeout: 30))
        try capture("Arrived")
        activate(arrivedRow.buttons["wishlist.arrival"].firstMatch)
        XCTAssertTrue(app.buttons["dulcet.mini-player.play-pause"].waitForExistence(timeout: 30))
        assertPlaybackReference(localId)
        let current = try wishes().first { ($0["snapshot"] as? [String: Any])?["title"] as? String == title }
        XCTAssertEqual((current?["libraryItem"] as? [String: Any])?["id"] as? String, localId)
        let lateA = try XCTUnwrap(rest("addToDulcetWishlist", params: replayParams)["dulcetWishMutation"] as? [String: Any])
        XCTAssertEqual(lateA["removed"] as? Bool, true)
        let survivingB = try wishes().filter { ($0["snapshot"] as? [String: Any])?["title"] as? String == title }
        XCTAssertEqual(survivingB.count, 1)
        XCTAssertEqual(survivingB.first?["id"] as? String, current?["id"] as? String)
        _ = try json(required("CAPTURE_UPLOAD_URL"), body: ["ack": ["scenario": "old-A-after-B", "lateA": lateA, "survivingB": survivingB]])
        print("DISCOVER_ROUTE arrivalServerId=\(localId) playbackVisible=true lateAReplayed=true generationBRetained=true")
        wishlist()
        activate(wishRow(title).buttons["wishlist.remove"].firstMatch)
        try waitForWish(title, count: 0)
        let retained = try XCTUnwrap(rest("getDulcetLibrary", params: ["type": "song", "size": "100"])["dulcetLibrary"] as? [String: Any])
        XCTAssertTrue((retained["song"] as? [[String: Any]])?.contains { $0["id"] as? String == localId } == true, "Removing the arrived wish must retain its exact library member without another scan")
        _ = try json(required("CAPTURE_UPLOAD_URL"), body: ["ack": ["scenario": "fulfilled-wish-removed", "wishAbsent": true, "wishId": try XCTUnwrap(current?["id"] as? String), "retainedLibraryItem": localId]])
        try capture("After-Arrived-Remove")
    }

    func busyStaleAndNavidromeControl() throws {
        try launch()
        let stale = try scenario("stale-answer"); try search()
        XCTAssertTrue(item("album", 200).waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["discover.age"].firstMatch.label.contains("Cached answer"))
        try effect("stale-answer", revision: stale)
        let busy = try scenario("rate-limit-120")
        app.terminate(); try launch(); try search()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Server is busy")).firstMatch.waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["discover.retry"].firstMatch.isEnabled)
        XCTAssertTrue(item("album", 200).exists, "Cached last-good rows survive the 429")
        try capture("Search-Busy")
        try effect("rate-limit-120", revision: busy)
        _ = try scenario("normal")
        let baseline = try control("baseline")
        XCTAssertEqual(baseline["login"] as? Bool, true)
        XCTAssertGreaterThan(baseline["albums"] as? Int ?? 0, 0)
        _ = try json(required("CAPTURE_UPLOAD_URL"), body: ["ack": ["scenario": "navidrome-baseline", "baseline": baseline]])
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
        try launch(); try search(); activate(item("song", 1)); _ = try scenario("offline")
        activate(app.buttons["discover.wish"].firstMatch)
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        app.terminate(); _ = try scenario("normal")
        // The same durable account fixture retains A while B is added through real composition.
        try launch(other: true); wishlist()
        XCTAssertTrue(app.staticTexts["Other owner only"].waitForExistence(timeout: 30))
        XCTAssertFalse(app.staticTexts["Fixture Song 01"].exists)
        XCTAssertFalse(try wishes(other: true).contains { ($0["snapshot"] as? [String: Any])?["title"] as? String == "Fixture Song 01" })
        select("settings")
        let savedA = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "dulcet.account.server.", try required("USERNAME"))).firstMatch
        XCTAssertTrue(savedA.waitForExistence(timeout: 15))
        selectSavedServer(savedA)
        wishlist()
        XCTAssertTrue(app.staticTexts["Fixture Song 01"].waitForExistence(timeout: 30))
        XCTAssertFalse(app.staticTexts["Other owner only"].exists)
        select("settings")
        let savedB = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", "dulcet.account.server.", try required("OTHER_USERNAME"))).firstMatch
        selectSavedServer(savedB); wishlist()
        XCTAssertTrue(app.staticTexts["Other owner only"].waitForExistence(timeout: 30))
        select("settings")
        _ = try scenario("identity-replacement")
        activate(savedA)
        let consent = app.buttons["dulcet.account.confirm-replacement"].firstMatch
        XCTAssertTrue(consent.waitForExistence(timeout: 30), "Replacement requires consent")
        let cancel = app.buttons["dulcet.account.cancel-replacement"].firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), "Consent must offer VoiceOver an explicit Cancel button")
        XCTAssertEqual(cancel.label, "Cancel")
        XCTAssertTrue(cancel.isHittable)
        activate(cancel)
        let dismissed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: consent)
        XCTAssertEqual(XCTWaiter.wait(for: [dismissed], timeout: 10), .completed)
        wishlist(); XCTAssertFalse(app.staticTexts["Fixture Song 01"].exists)
        print("DISCOVER_ACCOUNT savedAccountSwitch=true otherOwnerSentinel=true replacementConsentCancelled=true oldWishAbsent=true")
    }

    func accessibilityVariants() throws {
        for variant in ["light", "dark", "largest"] {
            app.terminate(); _ = try control("reset", [:]); namespace = UUID().uuidString
            try launch(variant: variant); try search()
            XCTAssertTrue(item("artist", 100).waitForExistence(timeout: 30))
            try capture("Search-" + variant)
            activate(item("artist", 100))
            XCTAssertTrue(app.staticTexts["discover.artist.title"].waitForExistence(timeout: 30))
            try capture("Artist-" + variant)
            activate(item("album", 200))
            let wish = app.buttons["discover.wish"].firstMatch
            reveal(wish)
            XCTAssertTrue(wish.waitForExistence(timeout: 30)); XCTAssertTrue(wish.isHittable)
            XCTAssertFalse(wish.label.isEmpty)
            try capture("Album-" + variant)
            _ = try scenario("offline"); activate(wish)
            XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
            try capture("Pending-" + variant)
            wishlist()
            XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
            try capture("Wishlist-" + variant)
            _ = try scenario("normal")
        }
    }

}
private final class ResponseBox: @unchecked Sendable { var data: Data?; var ok = false }
#if os(iOS)
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
        XCUIDevice.shared.orientation = .landscapeLeft
        try launch(); try search(); activate(item("album", 200))
        _ = try scenario("offline")
        activate(app.buttons["discover.wish"].firstMatch)
        XCTAssertTrue(app.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        wishlist()
        let sidebar = app.descendants(matching: .any)["wishlist.destination"].firstMatch
        let detail = app.buttons["wishlist.refresh"].firstMatch
        XCTAssertTrue(sidebar.isSelected, "The sidebar must select Wishlist rather than Library")
        XCTAssertTrue(sidebar.frame.maxX <= detail.frame.minX)
        XCTAssertTrue(detail.isHittable, "The regular Wishlist must keep Refresh reachable")
        let row = wishRow("Fixture Album 00")
        XCTAssertTrue(row.staticTexts["Waiting to sync"].exists)
        try capture("iPad-Regular-Wishlist")
        let pendingKey = row.identifier
        activate(row.buttons["wishlist.metadata"].firstMatch)
        let album = app.staticTexts["discover.album.title"].firstMatch
        XCTAssertTrue(album.waitForExistence(timeout: 30))
        XCTAssertTrue(sidebar.isSelected, "A Wishlist metadata page keeps its sidebar destination selected")
        let window = app.windows.firstMatch
        let original = window.frame
        // Drag the native iPad window corner; a width assertion prevents rotation alone
        // from masquerading as a compact-window proof.
        let corner = window.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -4, dy: -4))
        corner.press(forDuration: 0.1, thenDragTo: window.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 590, dy: original.height - 20)))
        XCTAssertLessThanOrEqual(window.frame.width, 600, "Native compact-window resize must occur")
        XCTAssertTrue(album.exists)
        XCTAssertEqual(album.label, "Fixture Album 00")
        try capture("iPad-Compact-Album")
        let back = app.navigationBars.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "Wishlist", "Back")).firstMatch
        XCTAssertTrue(back.isHittable); activate(back)
        let retained = wishRow("Fixture Album 00")
        XCTAssertTrue(retained.staticTexts["Waiting to sync"].waitForExistence(timeout: 15))
        XCTAssertEqual(retained.identifier, pendingKey, "The same account's durable pending intent survives resize and Back")
        try capture("iPad-Compact-Wishlist")
        _ = try scenario("normal")
        XCUIDevice.shared.orientation = .portrait
        try accessibilityVariants()
    }
}
#endif
