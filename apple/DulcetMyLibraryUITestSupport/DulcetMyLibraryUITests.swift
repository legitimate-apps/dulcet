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
#if os(macOS)
    private let accountFixtureNamespace = UUID().uuidString
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

    @MainActor
    func testBrowseAddRemoveRelaunchAndOfflineReplay() async throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        try removeAlbum(album.id)
        try await launch()
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.browse"].waitForExistence(timeout: 30))
        try select("browse")
#if os(tvOS)
        let tile = albumTile(album.title)
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        focus(tile)
        XCUIRemote.shared.press(.select, forDuration: 1.5)
        let contextAdd = app.buttons["Add to Library"].firstMatch
        XCTAssertTrue(contextAdd.waitForExistence(timeout: 10), "Remote press and hold offers membership")
        activate(contextAdd)
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        try removeAlbum(album.id)
        app.terminate(); try await launch()
        try select("browse")
#endif
        try openAlbum(album.title)
        let add = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(add.waitForExistence(timeout: 30))
        XCTAssertEqual(add.label, "Add to Library")
        activate(add)
        awaitLabel(add, "Remove from Library")
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        app.terminate()
        try await launch(connect: false)
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Saved account paints its durable My Library without reconnecting")
        activate(albumTile(album.title))
        let remove = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
        XCTAssertTrue(remove.waitForExistence(timeout: 20))
        activate(remove)
        try select("library")
        XCTAssertTrue(app.buttons["dulcet.myLibrary.browse"].waitForExistence(timeout: 20))
        // Restore online to flush the durable remove, then cut only this fixture's HTTP route.
        app.terminate(); try await launch()
        try awaitServerMembership(album.id, added: false)
        try select("browse"); try openAlbum(album.title)
        try setOffline(true)
        let offlineAdd = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
        activate(offlineAdd)
        awaitLabel(offlineAdd, "Remove from Library")
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        // A direct read bypasses the offline proxy: it proves the write has not reached the server.
        XCTAssertFalse(try serverContains(album.id))
        app.terminate()
        try await launch(connect: false)
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20), "Offline relaunch paints the durable pending add before reconnect")
        XCTAssertFalse(try serverContains(album.id))
        app.terminate()
        try setOffline(false)
        try await launch()
        try select("library")
        XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 20))
        try awaitServerMembership(album.id, added: true)
        try removeAlbum(album.id)
    }

    @MainActor
    func testNonDulcetServerKeepsTheOriginalLibraryNavigation() async throws {
        continueAfterFailure = false
        try await launch(server: navidrome, username: "dulcet-admin", password: "dulcet-ci-canary-password")
        try select("library")
        let catalog = app.descendants(matching: .any).matching(NSPredicate(format: "identifier IN %@", ["dulcet.reader.section.albums", "dulcet.sidebar.section.albums"])).firstMatch
        XCTAssertTrue(catalog.waitForExistence(timeout: 30), "Positive catalog control must load before asserting absence")
        XCTAssertFalse(destination("browse").exists)
        XCTAssertFalse(app.descendants(matching: .any)["dulcet.myLibrary"].exists)
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dulcet.membership.'")).firstMatch.exists)
    }

    @MainActor
    func testLibraryBrowseAndAlbumInLightDarkAndLargestText() async throws {
        continueAfterFailure = false
        try setOffline(false)
        let album = try fixtureAlbum()
        for variant in ["light", "dark", "largest-text"] {
            app.terminate()
            try removeAlbum(album.id)
            try await launch(variant: variant)
            try select("library")
            XCTAssertTrue(app.buttons["dulcet.myLibrary.browse"].waitForExistence(timeout: 30))
            try capture("Library-\(variant)")
            try select("browse")
            XCTAssertTrue(albumTile(album.title).waitForExistence(timeout: 30))
            try capture("Browse-\(variant)")
            try openAlbum(album.title)
            let add = app.buttons["dulcet.membership.album.\(album.id)"].firstMatch
            XCTAssertTrue(add.waitForExistence(timeout: 30))
            XCTAssertEqual(add.label, "Add to Library")
            try capture("Album-Add-\(variant)")
        }
    }

    @MainActor
    private func launch(connect: Bool = true, server: String? = nil, username: String? = nil, password: String? = nil, variant: String = "light") async throws {
        var launchArguments = ["-AppleInterfaceStyle", variant == "dark" ? "Dark" : "Light"]
        if variant == "largest-text" { launchArguments += ["-dulcet-debug-largest-text"] }
        if connect {
            launchArguments += ["-dulcet-debug-connect-account", "-dulcet-debug-account-server-url", server ?? base,
                "-dulcet-debug-account-username", username ?? user, "-dulcet-debug-account-password", password ?? self.password]
        }
#if os(macOS)
        launchArguments += ["-dulcet-debug-account-fixture", accountFixtureNamespace]
#endif
        app.launchArguments = launchArguments
        app.launch()
#if os(macOS)
        app.activate()
#endif
    }
    @MainActor private func destination(_ section: String) -> XCUIElement {
#if os(macOS)
        let title = section == "library" ? "Library" : section == "browse" ? "Browse" : "Search"
        return app.windows.firstMatch.descendants(matching: .any)
            .matching(NSPredicate(format: "label == %@", title)).firstMatch
#elseif os(tvOS)
        return app.buttons["dulcet.tab.\(section)"].firstMatch
#else
        let sidebar = app.descendants(matching: .any)["dulcet.sidebar.\(section)"].firstMatch
        return sidebar.exists ? sidebar : app.tabBars.buttons[section == "library" ? "Library" : section == "browse" ? "Browse" : "Search"].firstMatch
#endif
    }
    @MainActor private func select(_ section: String) throws {
        let control = destination(section)
        XCTAssertTrue(control.waitForExistence(timeout: 30), "Missing destination \(section)")
#if os(tvOS)
        // Menu returns to the section bar from roots; from a pushed page it pops first.
        let remote = XCUIRemote.shared
        for _ in 0..<6 {
            if app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dulcet.tab.' AND hasFocus == true")).firstMatch.exists { break }
            remote.press(.menu)
        }
        let order = ["library", "browse", "search", "nowPlaying", "settings"]
        for _ in 0..<12 {
            if control.hasFocus { remote.press(.select); return }
            if let current = order.firstIndex(where: { app.buttons["dulcet.tab.\($0)"].firstMatch.hasFocus }), let target = order.firstIndex(of: section) {
                remote.press(target > current ? .right : .left)
            } else { remote.press(.up) }
        }
        XCTFail("Remote could not reach \(section)")
#else
        activate(control)
#endif
    }
    @MainActor private func albumTile(_ title: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == 'dulcet.library.album' AND label BEGINSWITH %@", title)).firstMatch
    }
    @MainActor private func openAlbum(_ title: String) throws {
        let tile = albumTile(title)
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        activate(tile)
    }
    @MainActor private func activate(_ element: XCUIElement) {
#if os(tvOS)
        focus(element)
        if element.hasFocus { XCUIRemote.shared.press(.select) }
#elseif os(macOS)
        element.click()
#else
        element.tap()
#endif
    }
#if os(tvOS)
    private func focus(_ target: XCUIElement) {
        for _ in 0..<40 {
            if target.hasFocus { return }
            let current = app.buttons.matching(NSPredicate(format: "hasFocus == true")).firstMatch
            guard current.exists else { XCUIRemote.shared.press(.down); continue }
            let a = current.frame, b = target.frame
            if abs(a.midY - b.midY) > max(20, min(a.height, b.height) / 2) {
                XCUIRemote.shared.press(b.midY > a.midY ? .down : .up)
            } else {
                XCUIRemote.shared.press(b.midX > a.midX ? .right : .left)
            }
        }
        XCTFail("Remote could not focus " + target.debugDescription)
    }
#endif
    @MainActor private func awaitLabel(_ element: XCUIElement, _ label: String) {
        let expected = NSPredicate(format: "label == %@", label)
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: expected, object: element)], timeout: 20), .completed)
    }
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
        let library = try XCTUnwrap(try rest("getDulcetLibrary")["dulcetLibrary"] as? [String: Any])
        return (library["album"] as? [[String: Any]] ?? []).contains { ($0["id"] as? String) == id }
    }
    private func awaitServerMembership(_ id: String, added: Bool) throws {
        let deadline = Date().addingTimeInterval(30)
        repeat {
            if try serverContains(id) == added { return }
            Thread.sleep(forTimeInterval: 0.2)
        } while Date() < deadline
        XCTFail("Server did not settle membership \(id) added=\(added)")
    }
    private func rest(_ method: String, params: [String: String] = [:]) throws -> [String: Any] {
        var url = URLComponents(string: "http://127.0.0.1:4783/rest/\(method).view")!
        let query = params.merging(["u": user, "p": password, "v": "1.16.1", "c": "dulcet-ui-proof", "f": "json"]) { _, b in b }
        url.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        let data = try Data(contentsOf: url.url!)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let response = try XCTUnwrap(body["subsonic-response"] as? [String: Any])
        XCTAssertEqual(response["status"] as? String, "ok")
        return response
    }
    private func setOffline(_ offline: Bool) throws {
        _ = try Data(contentsOf: URL(string: base + "/fixture/network?offline=\(offline ? "1" : "0")")!)
    }
}
