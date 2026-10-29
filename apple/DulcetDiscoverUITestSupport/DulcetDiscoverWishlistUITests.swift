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
    private var controlDroppedInput = false
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
    func launch(server: String? = nil, other: Bool = false, connect: Bool = true, variant: String = "light", navigationReceipts: Bool = true) throws {
        if !prepared { try prepare(); prepared = true }
        var args = ["-dulcet-debug-ui-markers", "-dulcet-debug-account-fixture", namespace, variant == "dark" ? "-dulcet-debug-dark-mode" : "-dulcet-debug-light-mode"]
        if navigationReceipts { args.append("-dulcet-debug-navigation-receipts") }
#if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad {
            args += DulcetRemoveControl.arguments(for: name)
            print("CONTROL_CONFIGURATION method=\(name) observable=\(args.contains("-dulcet-control-publish-input")) swallowed=\(args.contains("-dulcet-control-remove-swallowed")) delivered=\(args.contains("-dulcet-control-remove-delivered"))")
        }
#endif
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
    @discardableResult func select(_ section: String) -> XCUIElement {
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
        return row
    }
    func selectSavedServer(_ button: XCUIElement) {
        XCTAssertTrue(button.waitForExistence(timeout: 30), "Saved account must appear before checking whether it is enabled: " + app.debugDescription)
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: button)
        XCTAssertEqual(XCTWaiter.wait(for: [enabled], timeout: 30), .completed)
        let identifier = button.identifier
        print("DISCOVER_SAVED_ACCOUNT identifier=\(identifier) exists=true enabled=true")
        activate(button)
        let started = ProcessInfo.processInfo.systemUptime
        repeat {
            // One snapshot keeps the current-server label, enabled state and frame together.
            if let root = try? app.snapshot() {
                var pending: [XCUIElementSnapshot] = [root]
                var displayed = false
                while let node = pending.popLast() {
                    if node.elementType == .button && node.identifier == identifier {
                        let frame = node.frame
                        let usable = !frame.isEmpty && frame.origin.x.isFinite && frame.origin.y.isFinite
                            && frame.width.isFinite && frame.height.isFinite
                        displayed = displayed || usable
                        if usable && node.isEnabled && node.label.contains("Current server") { return }
                    }
                    pending.append(contentsOf: node.children)
                }
                // A retained Connection tab can keep an off-screen row in the accessibility tree.
                if !displayed { select("settings") }
            }
            Thread.sleep(forTimeInterval: 0.25)
        } while ProcessInfo.processInfo.systemUptime - started < 180
        XCTFail("Saved account must commit before navigating; elapsed=\(ProcessInfo.processInfo.systemUptime - started)s: " + app.debugDescription)
    }
    func search(_ query: String = "Fixture") throws {
        var destination = select("search")
        let everywhere = app.buttons["Everywhere"].firstMatch
#if os(iOS)
        // The selected Search tab or sidebar row identifies the destination before using Back.
        var selected = false
        for attempt in 0..<3 {
            if attempt > 0 { destination = select("search") }
            let selection = XCTNSPredicateExpectation(predicate: NSPredicate(format: "selected == true"), object: destination)
            if XCTWaiter.wait(for: [selection], timeout: 15) == .completed {
                selected = true
                break
            }
        }
        guard selected else {
            XCTFail("Search must become the selected destination before returning to its root: " + app.debugDescription)
            return
        }
        // Search retains its metadata stack when Open in Library changes destinations.
        // Use the same native Back controls as a person before entering another query.
        let back = app.navigationBars.buttons["BackButton"].firstMatch
        var pops = 0
        while !everywhere.exists, back.exists, back.isHittable, pops < 4 {
            activate(back)
            pops += 1
        }
#endif
        XCTAssertTrue(everywhere.waitForExistence(timeout: 15), "Missing Search Everywhere scope: " + app.debugDescription)
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
    private func descendants(_ root: XCUIElementSnapshot) -> [XCUIElementSnapshot] {
        var nodes: [XCUIElementSnapshot] = []
        var pending = [root]
        while let node = pending.popLast() {
            nodes.append(node)
            pending.append(contentsOf: node.children)
        }
        return nodes
    }
    private func presented(_ node: XCUIElementSnapshot, in root: XCUIElementSnapshot) -> Bool {
        let frame = node.frame
        return !frame.isEmpty && frame.origin.x.isFinite && frame.origin.y.isFinite
            && frame.width.isFinite && frame.height.isFinite
            && root.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
    }
    func reveal(_ element: XCUIElement) {
        let started = ProcessInfo.processInfo.systemUptime
        var scrolls = 0
        var searchPage = false
        repeat {
            if searchPage {
                if let root = try? app.snapshot() {
                    scrollContent(up: scrolls % 20 < 10, nodes: descendants(root))
                    scrolls += 1
                }
                searchPage = false
            } else if let target = try? element.snapshot() {
                reveal(matching: { node, _ in
                    node.elementType == target.elementType && node.identifier == target.identifier
                        && (target.identifier.isEmpty ? node.label == target.label : true) && node.isEnabled
                }, description: "Control " + target.identifier, started: started)
                return
            } else {
                searchPage = ProcessInfo.processInfo.systemUptime - started >= 5
            }
            Thread.sleep(forTimeInterval: 0.25)
        } while ProcessInfo.processInfo.systemUptime - started < 180
        XCTFail("Control must become reachable; elapsed=\(ProcessInfo.processInfo.systemUptime - started)s")
    }
    private func scrollContent(up: Bool, nodes: [XCUIElementSnapshot]) {
#if os(macOS)
        app.scrollViews.firstMatch.scroll(byDeltaX: 0, deltaY: up ? -500 : 500)
#else
        let hasScroll = nodes.contains { $0.elementType == .scrollView }
        let hasList = nodes.contains { $0.elementType == .collectionView && $0.label != "Sidebar" }
        let body = hasScroll ? app.scrollViews.firstMatch : app.collectionViews.matching(NSPredicate(format: "label != %@", "Sidebar")).firstMatch
        if hasScroll || hasList {
            if up { body.swipeUp() } else { body.swipeDown() }
        }
#endif
    }
    private func reveal(matching match: (XCUIElementSnapshot, XCUIElementSnapshot) -> Bool, description: String,
                        ready: (XCUIElementSnapshot, XCUIElementSnapshot) -> Bool = { _, _ in true },
                        started: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        var scrolls = 0
        repeat {
            // Inspect one immutable tree so presence, row contents and geometry agree.
            if let root = try? app.snapshot() {
                let nodes = descendants(root)
                if nodes.contains(where: { match($0, root) && presented($0, in: root) && ready($0, root) }) { return }
                // Allow publication before searching beyond the currently materialized page.
                if ProcessInfo.processInfo.systemUptime - started >= 5 {
                    scrollContent(up: scrolls % 20 < 10, nodes: nodes)
                    scrolls += 1
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
        } while ProcessInfo.processInfo.systemUptime - started < 180
        XCTFail("\(description) must become reachable; elapsed=\(ProcessInfo.processInfo.systemUptime - started)s")
    }
    func item(_ kind: String, _ number: Int) -> XCUIElement {
        let title: String
        switch kind {
        case "artist": title = String(format: "Fixture Artist %02d", number - 100)
        case "album": title = String(format: "Fixture Album %02d", number - 200)
        default: title = String(format: "Fixture Song %02d", number)
        }
        let result = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ AND label BEGINSWITH %@", "discover.item.", title)).firstMatch
        reveal(matching: { node, _ in node.elementType == .button && node.identifier.hasPrefix("discover.item.")
            && node.label.hasPrefix(title) && node.isEnabled }, description: title)
        return result
    }
    func wishlist() {
        let started = ProcessInfo.processInfo.systemUptime
        var requestedLibrary = false
        repeat {
            if let root = try? app.snapshot() {
                let nodes = descendants(root)
                let sidebarSelected = nodes.contains { $0.identifier == "wishlist.destination" && $0.isSelected }
                let libraryTabSelected = nodes.filter { $0.elementType == .tabBar }.contains { tab in
                    descendants(tab).contains { $0.elementType == .button && $0.isSelected
                        && ($0.identifier == "dulcet.tab.library" || $0.label == "Library") }
                }
                if sidebarSelected || libraryTabSelected, nodes.contains(where: {
                    $0.identifier == "wishlist.refresh" && $0.isEnabled && presented($0, in: root)
                }) { return }
                // Activate Wishlist directly so a delayed Library reset cannot dismiss its content.
                if nodes.contains(where: { $0.identifier == "wishlist.destination" && presented($0, in: root) }) {
                    activate(app.descendants(matching: .any)["wishlist.destination"].firstMatch)
                } else if !requestedLibrary, nodes.contains(where: { $0.elementType == .tabBar && presented($0, in: root) }) {
                    select("library")
                    requestedLibrary = true
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
        } while ProcessInfo.processInfo.systemUptime - started < 180
        XCTFail("Wishlist activation must present content; elapsed=\(ProcessInfo.processInfo.systemUptime - started)s")
    }
    func capture(_ name: String) throws {
#if os(macOS)
        let bitmap = try XCTUnwrap(app.windows.firstMatch.screenshot().image.tiffRepresentation.flatMap(NSBitmapImageRep.init(data:)))
        let data = try XCTUnwrap(bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.65]))
#else
        let window = app.windows.firstMatch
        let image = window.screenshot().image
        let frame = window.frame
        print("DISCOVER_CAPTURE name=\(name) imageSize=\(image.size) windowFrame=\(frame) imageOrientation=\(image.imageOrientation.rawValue) deviceOrientation=\(XCUIDevice.shared.orientation.rawValue)")
        XCTAssertGreaterThan(frame.width, 0)
        XCTAssertGreaterThan(frame.height, 0)
        XCTAssertEqual(image.size.width / image.size.height, frame.width / frame.height, accuracy: 0.02,
                       "Capture must match the actual app window, including after rotation or resize")
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
        try launch(navigationReceipts: true)
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
            if edition.value as? String == "2026-02-01 · 12 tracks" { break }
            Thread.sleep(forTimeInterval: 0.2)
        }
        XCTAssertEqual(edition.value as? String, "2026-02-01 · 12 tracks")
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
        print("DISCOVER_PLAY_UI markers=\(app.staticTexts["dulcet.debug.ui-markers"].firstMatch.label)")
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
        reveal(matching: { node, _ in
            guard node.identifier.hasPrefix("wishlist.row.") else { return false }
            let children = self.descendants(node)
            return children.contains { $0.label == title }
                && children.contains { $0.identifier == "wishlist.remove" && $0.elementType == .button
                    && $0.isEnabled }
        }, description: "Wishlist row and remove button for " + title, ready: { node, root in
            self.descendants(node).contains { $0.identifier == "wishlist.remove" && self.presented($0, in: root) }
        })
        return row
    }
    private func removeWish(_ title: String) {
        let rowIdentifier = wishRow(title).identifier
        let key = String(rowIdentifier.dropFirst("wishlist.row.".count))
        let markers = app.staticTexts["dulcet.debug.ui-markers"].firstMatch
        XCTAssertTrue(markers.waitForExistence(timeout: 10))
#if os(iOS)
        let observesTouches = UIDevice.current.userInterfaceIdiom == .pad
#else
        let observesTouches = false
#endif
        let inputMarkers = app.staticTexts["dulcet.debug.input-markers"].firstMatch
        if observesTouches {
            XCTAssertTrue(inputMarkers.waitForExistence(timeout: 10))
            XCTAssertTrue(markers.label.contains("input-monitor-ready"))
            XCTAssertTrue(DulcetRemoveInputProof.observedCompletedContact(inputMarkers.label),
                          "The input ledger must witness a prior app interaction before a missing input can permit recovery")
        }
        let receipts = { markers.label + (observesTouches ? " " + inputMarkers.label : "") }
        let baseline = DulcetRemoveInputProof.sequence(receipts())
        let maximumInputs = observesTouches ? 2 : 1
        for attempt in 1...maximumInputs {
            let before = receipts()
            if DulcetRemoveInputProof.received(before, key: key, after: baseline) { return }
            let inputBaseline = DulcetRemoveInputProof.sequence(before)
            // Re-resolve the same durable identity; a removed row must never turn a
            // second input into a tap on the next wish in the list.
            let button = app.descendants(matching: .any)[rowIdentifier].firstMatch.buttons["wishlist.remove"].firstMatch
            XCTAssertTrue(button.exists && button.isEnabled)
            print("DISCOVER_REMOVE_INPUT row=\(rowIdentifier) attempt=\(attempt)")
            if observesTouches && !name.contains("CompletedTouchSwallowed") && !controlDroppedInput {
                controlDroppedInput = true
                print("CONTROL_REMOVE_OMITTED_FIRST_INPUT row=\(rowIdentifier)")
            } else { activate(button) }
            let received = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                DulcetRemoveInputProof.received(receipts(), key: key, after: baseline)
            }, object: nil)
            if XCTWaiter.wait(for: [received], timeout: 5) == .completed {
                print("DISCOVER_REMOVE_RECEIVED row=\(rowIdentifier) attempt=\(attempt)")
                return
            }
            let after = receipts()
            let classification = DulcetRemoveInputProof.recovery(after, after: inputBaseline)
            guard observesTouches, attempt < maximumInputs,
                  let reason = classification else {
                XCTFail("Remove was not received; attempt=\(attempt)/\(maximumInputs) input=\(classification ?? "normal-or-incomplete") receipts=\(after)")
                return
            }
            print("DISCOVER_REMOVE_RESYNTHESIZE row=\(rowIdentifier) reason=\(reason)")
        }
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
        removeWish(title)
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
        removeWish(title)
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
        removeWish(title)
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
            if variant == "largest" {
                let edition = app.descendants(matching: .any)["discover.edition"].firstMatch
                reveal(edition); XCTAssertTrue(edition.isHittable); activate(edition)
                let later = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "2026-02-01")).firstMatch
                XCTAssertTrue(later.waitForExistence(timeout: 10)); activate(later)
                let selectedEdition = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "2026-02-01 · 12 tracks"), object: edition)
                XCTAssertEqual(XCTWaiter.wait(for: [selectedEdition], timeout: 30), .completed,
                               "The accessible edition menu must describe the selected release with its date and track count")
                print("DISCOVER_LARGEST_EDITION selected=true frame=\(edition.frame)")
                reveal(wish)
            }
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
    func testOfflineRemoveCompletedTouchSwallowed() throws { try offlineWishRelaunchRemoveAndScanArrival() }
    func testOfflineRemoveDeliveredNoop() throws { try offlineWishRelaunchRemoveAndScanArrival() }
    func testOfflineRemoveRecoveryOnly() throws { try offlineWishRelaunchRemoveAndScanArrival() }
    func testSearchObservableInputReceipts() throws { try launch(); try search() }
    func testSearchReadOnlyInputReceipts() throws { try launch(); try search() }
}
#endif


// BEGIN_REMOVE_INPUT_PROOF
/// Parses the fixture's monotonic handler and passive window-touch receipts.
/// A normally ended touch without a handler remains a proof failure, never a retry.
enum DulcetRemoveInputProof {
    private static func records(_ text: String) -> [[Substring]] {
        text.split(separator: " ").map { $0.split(separator: "|", omittingEmptySubsequences: false) }
    }
    static func sequence(_ text: String) -> Int {
        records(text).compactMap { fields in
            fields.count > 1 && ["NAV", "DETAIL", "INPUT"].contains(String(fields[0])) ? Int(fields[1]) : nil
        }.max() ?? 0
    }
    static func observedCompletedContact(_ text: String) -> Bool {
        let events = records(text).filter { $0.count >= 4 && $0[0] == "INPUT" }
        let began = Set(events.filter { $0[3] == "phase=began" }.map { String($0[2]) })
        return events.contains { fields in
            fields[3] == "phase=ended" && began.contains(String(fields[2]))
                && fields[2].hasPrefix("touch=") && (Int(fields[2].dropFirst(6)) ?? -1) > 0
        }
    }
    static func received(_ text: String, key: String, after baseline: Int) -> Bool {
        records(text).contains { fields in
            fields.count >= 4 && fields[0] == "DETAIL" && (Int(fields[1]) ?? -1) > baseline
                && fields[2] == "wishlist.remove" && fields.dropFirst(3).joined(separator: "|") == "key=" + key
        }
    }
    static func recovery(_ text: String, after baseline: Int) -> String? {
        let events = records(text).filter { $0.count >= 4 && $0[0] == "INPUT" && (Int($0[1]) ?? -1) > baseline }
        if events.isEmpty { return "no-touch" }
        guard events.allSatisfy({ $0[3] == "phase=began" || $0[3] == "phase=cancelled" }) else { return nil }
        let began = Set(events.filter { $0[3] == "phase=began" }.map { String($0[2]) })
        let cancelled = Set(events.filter { $0[3] == "phase=cancelled" }.map { String($0[2]) })
        return !began.isEmpty && began == cancelled ? "cancelled-touch" : nil
    }
}
// END_REMOVE_INPUT_PROOF

// BEGIN_CONTROL_CONFIGURATION
enum DulcetRemoveControl {
    static func arguments(for name: String) -> [String] {
        var args = ["-dulcet-debug-input-receipts"]
        if name.contains("ObservableInputReceipts") { args.append("-dulcet-control-publish-input") }
        if !name.contains("RecoveryOnly") && !name.contains("Search") {
            args.append(name.contains("CompletedTouchSwallowed") ? "-dulcet-control-remove-swallowed" : "-dulcet-control-remove-delivered")
        }
        return args
    }
}
// END_CONTROL_CONFIGURATION
