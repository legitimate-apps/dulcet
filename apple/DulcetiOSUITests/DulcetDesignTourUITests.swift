import CryptoKit
import UIKit
import XCTest

/// A screenshot tour of the app's key screens against a live disposable server, for design review.
///
/// This is an instrument, not a proof: it asserts only that each screen was reached, so a shot is
/// never of a spinner or an error in place of the screen it is named after. `tools/design-tour-apple`
/// runs it and exports the attachments; it returns at once unless `DULCET_DESIGN_TOUR=1`, so no ordinary
/// run of this target photographs anything.
///
/// Each shot is an attachment named `<nn>-<screen>`. A screen that could not be reached is still
/// photographed, as `<nn>-<screen>-UNREACHED`, and recorded as a failure, and the tour continues:
/// one broken screen must not cost the reviewer every later one.
final class DulcetDesignTourUITests: XCTestCase {
    private struct TourServer {
        let url: String
        let username: String
        let password: String
    }

    private static let playlistName = "Design Tour Mix"
    private var shotNumber = 0

    @MainActor
    func testDesignTour() {
        let environment = ProcessInfo.processInfo.environment
        guard environment["DULCET_DESIGN_TOUR"] == "1" else {
            print("DULCET DESIGN TOUR not requested; it runs only from tools/design-tour-apple")
            return
        }
        guard let url = environment["DULCET_UI_TEST_SERVER_URL"], !url.isEmpty,
              let username = environment["DULCET_UI_TEST_USERNAME"], !username.isEmpty,
              let password = environment["DULCET_UI_TEST_PASSWORD"], !password.isEmpty else {
            XCTFail("The tour needs DULCET_UI_TEST_SERVER_URL, _USERNAME and _PASSWORD")
            return
        }
        continueAfterFailure = true
        let server = TourServer(url: url, username: username, password: password)
        preparePlaylist(on: server)

        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", server.url,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 15), "The app window must exist")
        let compact = window.frame.width < 700
        print("DULCET DESIGN TOUR start compact=\(compact) window=\(window.frame)")
        guard awaitConnection(in: app, compact: compact) else {
            shoot("connection-UNREACHED", app)
            XCTFail("The disposable account must connect before the tour: " + app.debugDescription)
            return
        }

        // Library root: Home on a phone, Home beside the sidebar on a regular width.
        step("library", app) {
            guard self.openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact) else { return false }
            // A regular width opens Library on Home; there is no Home row in the sidebar.
            return app.descendants(matching: .any)["dulcet.reader.home.recentlyAdded"].firstMatch
                .waitForExistence(timeout: 30) && self.settle(app)
        }
        if !compact {
            step("sidebar-albums", app) {
                self.tapSidebarSection("albums", in: app)
                    && app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 30)
                    && self.settle(app)
            }
        }
        step("album", app) { self.openAlbum("Threshold Boundary", in: app, compact: compact) && self.settle(app) }
        step("artist", app) { self.openArtist("Dulcet Fixtures", in: app, compact: compact) && self.settle(app) }

        // Now Playing, playing: Threshold Boundary's first track carries synced lyrics.
        step("now-playing", app) {
            guard self.openAlbum("Threshold Boundary", in: app, compact: compact) else { return false }
            let play = app.buttons["dulcet.album.play"].firstMatch
            guard play.waitForExistence(timeout: 10) else { return false }
            play.tap()
            let bar = app.buttons["dulcet.mini-player.open"].firstMatch
            guard bar.waitForExistence(timeout: 20), self.waitFor(timeout: 20, { bar.label.contains("Twenty Nine Seconds") }) else {
                return false
            }
            bar.tap()
            let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
            return title.waitForExistence(timeout: 10) && self.settle(app, seconds: 2)
        }
        step("lyrics", app) {
            let toggle = app.buttons["dulcet.now-playing.lyrics"].firstMatch
            guard toggle.waitForExistence(timeout: 10) else { return false }
            toggle.tap()
            let lit = app.staticTexts["dulcet.lyrics.line.current"].firstMatch
            return app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch.waitForExistence(timeout: 10)
                && lit.waitForExistence(timeout: 25) && self.settle(app)
        }
        step("up-next", app) {
            let lyrics = app.buttons["dulcet.now-playing.lyrics"].firstMatch
            if app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch.exists, lyrics.exists { lyrics.tap() }
            let toggle = app.buttons["dulcet.now-playing.up-next"].firstMatch
            if toggle.waitForExistence(timeout: 3) { toggle.tap() }
            return app.descendants(matching: .any)["dulcet.upNext"].firstMatch.waitForExistence(timeout: 10)
                && self.settle(app)
        }
        closePlayer(in: app)
        // Paused, from the now-playing bar, for the rest of the tour: the simulator's audio is the host's.
        let pause = app.buttons["Pause"].firstMatch
        if pause.waitForExistence(timeout: 3), pause.isHittable { pause.tap() }

        step("playlists", app) {
            self.openPlaylists(in: app, compact: compact)
                && app.buttons.matching(identifier: "dulcet.library.playlist").firstMatch.waitForExistence(timeout: 30)
                && self.settle(app)
        }
        step("playlist", app) {
            let row = app.buttons.matching(identifier: "dulcet.library.playlist")
                .matching(NSPredicate(format: "label BEGINSWITH %@", Self.playlistName)).firstMatch
            guard row.waitForExistence(timeout: 20), self.scrollIntoView(row, in: app) else { return false }
            row.tap()
            let title = app.staticTexts["dulcet.playlist.title"].firstMatch
            return title.waitForExistence(timeout: 10)
                && app.buttons["dulcet.reader.track"].firstMatch.waitForExistence(timeout: 20)
                && self.settle(app)
        }
        step("search", app) {
            guard self.openDestination("Search", sidebar: "dulcet.sidebar.search", in: app, compact: compact) else { return false }
            let field = app.textFields["dulcet.search.field"].firstMatch
            guard field.waitForExistence(timeout: 10) else { return false }
            field.tap()
            field.typeText("Threshold")
            guard app.buttons["dulcet.search.result.0"].firstMatch.waitForExistence(timeout: 20) else { return false }
            self.dismissKeyboard(in: app)
            return self.settle(app)
        }
        print("DULCET DESIGN TOUR done shots=\(shotNumber)")
    }

    // MARK: - Steps and shots

    /// Runs one screen: reach it, then photograph it whether or not it was reached.
    @MainActor
    private func step(_ screen: String, _ app: XCUIApplication, _ reach: () -> Bool) {
        let reached = reach()
        shoot(reached ? screen : screen + "-UNREACHED", app)
        if !reached {
            XCTFail("The design tour could not reach \(screen): " + app.debugDescription)
        }
        print("DULCET DESIGN TOUR screen=\(screen) reached=\(reached)")
    }

    @MainActor
    private func shoot(_ screen: String, _ app: XCUIApplication) {
        shotNumber += 1
        let name = String(format: "%02d-%@", shotNumber, screen)
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        // Also straight to the tool's directory: a simulator's test runner writes the host's file
        // system, and the tool need not wait for the result bundle to be finalized.
        if let directory = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_OUT"], !directory.isEmpty {
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: directory).appendingPathComponent(name + ".png"))
        }
    }

    /// Lets artwork and list animations land before a shot, without asserting anything.
    @MainActor
    private func settle(_ app: XCUIApplication, seconds: TimeInterval = 1.2) -> Bool {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
        return true
    }

    @MainActor
    private func waitFor(timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    // MARK: - Navigation

    /// A first launch waits on Connection while it connects; a saved account opens into its library.
    @MainActor
    private func awaitConnection(in app: XCUIApplication, compact: Bool) -> Bool {
        let signOut = app.buttons["Sign Out"].firstMatch
        if signOut.waitForExistence(timeout: 10) { return true }
        guard openDestination("Connection", sidebar: "dulcet.sidebar.settings", in: app, compact: compact) else { return false }
        return signOut.waitForExistence(timeout: 30)
    }

    @MainActor
    private func openDestination(_ tab: String, sidebar: String, in app: XCUIApplication, compact: Bool) -> Bool {
        if compact {
            let button = app.tabBars.buttons[tab].firstMatch
            guard button.waitForExistence(timeout: 10) else { return false }
            button.tap()
            // A second tap on the selected tab pops its stack back to the root.
            if tab == "Library" { button.tap() }
            return true
        }
        let row = app.staticTexts[sidebar].firstMatch
        guard row.waitForExistence(timeout: 10), row.isHittable else { return false }
        row.tap()
        return true
    }

    @MainActor
    private func tapSidebarSection(_ section: String, in app: XCUIApplication) -> Bool {
        let row = app.staticTexts["dulcet.sidebar.section.\(section)"].firstMatch
        guard row.waitForExistence(timeout: 10), row.isHittable else { return false }
        row.tap()
        return true
    }

    @MainActor
    private func openAlbum(_ album: String, in app: XCUIApplication, compact: Bool) -> Bool {
        guard openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact) else { return false }
        if !compact, !tapSidebarSection("albums", in: app) { return false }
        let tile = app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album)).firstMatch
        guard tile.waitForExistence(timeout: 30), scrollIntoView(tile, in: app) else { return false }
        tile.tap()
        let title = app.staticTexts["dulcet.album.title"].firstMatch
        return title.waitForExistence(timeout: 10) && waitFor(timeout: 10) { title.label == album }
    }

    /// The artist a person reaches from Library > Artists.
    @MainActor
    private func openArtist(_ artist: String, in app: XCUIApplication, compact: Bool) -> Bool {
        guard openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact) else { return false }
        if compact {
            let link = app.buttons["dulcet.reader.section.artists"].firstMatch
            guard link.waitForExistence(timeout: 15), scrollIntoView(link, in: app) else { return false }
            link.tap()
        } else if !tapSidebarSection("artists", in: app) {
            return false
        }
        let row = app.buttons.matching(identifier: "dulcet.library.artist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", artist)).firstMatch
        guard row.waitForExistence(timeout: 30), scrollIntoView(row, in: app) else { return false }
        row.tap()
        // The page names the artist over its album tiles.
        return app.staticTexts.matching(NSPredicate(format: "label == %@", artist)).firstMatch.waitForExistence(timeout: 10)
            && app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 20)
    }

    @MainActor
    private func openPlaylists(in app: XCUIApplication, compact: Bool) -> Bool {
        guard openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact) else { return false }
        if !compact { return tapSidebarSection("playlists", in: app) }
        let link = app.buttons["dulcet.reader.section.playlists"].firstMatch
        guard link.waitForExistence(timeout: 15), scrollIntoView(link, in: app) else { return false }
        link.tap()
        return true
    }

    @MainActor
    private func closePlayer(in app: XCUIApplication) {
        let close = app.buttons["dulcet.now-playing.close"].firstMatch
        if close.waitForExistence(timeout: 5) { close.tap() } else { app.swipeDown() }
        _ = app.buttons["dulcet.now-playing.close"].firstMatch.waitForNonExistence(timeout: 5)
    }

    @MainActor
    private func dismissKeyboard(in app: XCUIApplication) {
        let keyboard = app.keyboards.firstMatch
        guard keyboard.exists else { return }
        let search = keyboard.buttons["Search"].firstMatch
        let hide = keyboard.buttons["Hide keyboard"].firstMatch
        if search.exists { search.tap() } else if hide.exists { hide.tap() }
        _ = keyboard.waitForNonExistence(timeout: 5)
    }

    @MainActor
    private func scrollIntoView(_ element: XCUIElement, in app: XCUIApplication) -> Bool {
        let window = app.windows.firstMatch
        for _ in 0..<8 {
            if element.exists, !element.frame.isEmpty,
               window.frame.contains(CGPoint(x: element.frame.midX, y: element.frame.midY)), element.isHittable {
                return true
            }
            app.swipeUp()
        }
        return element.exists && element.isHittable
    }

    // MARK: - Server fixture

    /// One playlist of the corpus's own tracks, named so a rerun finds and replaces it.
    private func preparePlaylist(on server: TourServer) {
        let existing = (rest("getPlaylists", [], server)?["playlists"] as? [String: Any])?["playlist"] as? [[String: Any]] ?? []
        for playlist in existing where playlist["name"] as? String == Self.playlistName {
            if let id = playlist["id"] as? String {
                _ = rest("deletePlaylist", [URLQueryItem(name: "id", value: id)], server)
            }
        }
        let found = (rest("search3", [
            URLQueryItem(name: "query", value: "Dulcet"), URLQueryItem(name: "songCount", value: "12"),
            URLQueryItem(name: "albumCount", value: "0"), URLQueryItem(name: "artistCount", value: "0"),
        ], server)?["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let threshold = (rest("search3", [
            URLQueryItem(name: "query", value: "Threshold"), URLQueryItem(name: "songCount", value: "5"),
            URLQueryItem(name: "albumCount", value: "0"), URLQueryItem(name: "artistCount", value: "0"),
        ], server)?["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let ids = (threshold + found).compactMap { $0["id"] as? String }.prefix(8)
        let created = rest("createPlaylist", [URLQueryItem(name: "name", value: Self.playlistName)]
            + ids.map { URLQueryItem(name: "songId", value: $0) }, server)
        XCTAssertNotNil(created, "The tour's playlist must be made on the disposable server")
        print("DULCET DESIGN TOUR playlist songs=\(ids.count)")
    }

    /// One `/rest` call with the disposable account; the body on `ok`, else nil. Never logs the URL.
    private func rest(_ endpoint: String, _ query: [URLQueryItem], _ server: TourServer) -> [String: Any]? {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((server.password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
        guard var components = URLComponents(string: server.url) else { return nil }
        let base = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = base + "/rest/" + endpoint
        components.queryItems = [
            URLQueryItem(name: "u", value: server.username), URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt), URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-design-tour"), URLQueryItem(name: "f", value: "json"),
        ] + query
        guard let url = components.url else { return nil }
        final class Outcome: @unchecked Sendable { var data: Data? }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            if error == nil, (response as? HTTPURLResponse)?.statusCode == 200 { outcome.data = data }
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 15) == .success else {
            task.cancel()
            return nil
        }
        guard let data = outcome.data,
              let document = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any],
              envelope["status"] as? String == "ok" else { return nil }
        return envelope
    }
}
