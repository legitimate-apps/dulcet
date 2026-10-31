import CryptoKit
import UIKit
import ObjectiveC
import XCTest

/// A screenshot tour of the app's key screens against a live disposable server, for design review.
///
/// This instrument asserts that each screen was reached and checks the playlist's action-row
/// geometry, single title at rest and action margins. A shot is never of a spinner or an error
/// in place of the screen it is named after. `tools/design-tour-apple`
/// runs it and exports the attachments; it returns at once unless `DULCET_DESIGN_TOUR=1`, so no ordinary
/// run of this target photographs anything.
///
/// Each shot is an attachment named `<nn>-<screen>`. A screen that could not be reached is still
/// photographed, as `<nn>-<screen>-UNREACHED`, and recorded as a failure, and the tour continues:
/// one broken screen must not cost the reviewer every later one.
final class DulcetDesignTourUITests: PlayerHittabilityTestCase {
    private struct TourServer {
        let url: String
        let username: String
        let password: String
    }

    private static let playlistName = "Design Tour Mix"
    private var shotNumber = 0

    @MainActor
    func testOfflineMusicSettingsPersistAcrossRelaunch() throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["DULCET_OFFLINE_PROOF"] == "1" else { throw XCTSkip("Requires the disposable offline-library fixture") }
        let url = try XCTUnwrap(environment["DULCET_UI_TEST_SERVER_URL"])
        let host = URLComponents(string: url)?.host ?? ""
        XCTAssertTrue(host == "localhost" || host.hasPrefix("127."))
        let app = XCUIApplication()
        app.launchArguments += ["-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", url,
            "-dulcet-debug-account-username", try XCTUnwrap(environment["DULCET_UI_TEST_USERNAME"]),
            "-dulcet-debug-account-password", try XCTUnwrap(environment["DULCET_UI_TEST_PASSWORD"])]
        launchInInteractionScope(app)
        if let expected = environment["DULCET_OFFLINE_IDIOM"] {
            XCTAssertEqual(UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "iphone", expected)
        }
        guard let width = settledWindowWidth(app.windows.firstMatch) else { return }
        let compact = width < 700
        XCTAssertTrue(awaitConnection(in: app, compact: compact), app.debugDescription)
        XCTAssertTrue(openDestination("Connection", sidebar: "dulcet.sidebar.settings", in: app, compact: compact))
        let automatic = app.switches["dulcet.offline.automaticDownloads"].firstMatch
        let settingsForm = app.descendants(matching: .any)["dulcet.account.settingsForm"].firstMatch
        XCTAssertTrue(settingsForm.waitForExistence(timeout: 20), app.debugDescription)
        // Form rows are lazy: on iPhone this setting does not exist in accessibility
        // until its owning Form has scrolled it into the viewport.
        XCTAssertTrue(scrollIntoView(automatic, in: app, verticalMargin: 16, scrollContainer: settingsForm))
        XCTAssertTrue(automatic.waitForExistence(timeout: 20), app.debugDescription)
        // SwiftUI exposes a row-sized switch wrapper and its native thumb. The wrapper's
        // centre lies in the label's empty space on iPad; actuate the actual UISwitch.
        let automaticControl = automatic.switches.firstMatch
        XCTAssertTrue(automaticControl.exists, automatic.debugDescription)
        XCTAssertTrue(automaticControl.isHittable, automatic.debugDescription)
        if automatic.value as? String == "1" { automaticControl.tap() }
        _ = waitFor(timeout: 5) { automatic.value as? String == "0" }
        XCTAssertEqual(automatic.value as? String, "0")
        shoot("offline-settings-automatic-off", app)
        app.terminate()
        app.launchArguments = []
        launchInInteractionScope(app)
        XCTAssertTrue(openDestination("Connection", sidebar: "dulcet.sidebar.settings", in: app, compact: compact))
        XCTAssertTrue(settingsForm.waitForExistence(timeout: 20))
        XCTAssertTrue(scrollIntoView(automatic, in: app, verticalMargin: 16, scrollContainer: settingsForm))
        XCTAssertTrue(automatic.waitForExistence(timeout: 20))
        XCTAssertEqual(automatic.value as? String, "0", "The account setting must survive relaunch")
        XCTAssertTrue(automaticControl.isHittable, automatic.debugDescription)
        automaticControl.tap()
        _ = waitFor(timeout: 5) { automatic.value as? String == "1" }
        XCTAssertEqual(automatic.value as? String, "1")
        let clear = app.buttons["dulcet.offline.clearCache"].firstMatch
        XCTAssertTrue(scrollIntoView(clear, in: app, verticalMargin: 16, scrollContainer: settingsForm))
        XCTAssertTrue(clear.waitForExistence(timeout: 10))
        clear.tap()
        shoot("offline-settings-controls", app)
        print("OFFLINE-SETTINGS idiom=\(UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "iphone") automatic=off-relaunch-on clear=invoked")
    }

    @MainActor
    func testListenNowLaunchAlbumAndRelaunch() throws {
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        guard environment["DULCET_HOME_PROOF"] == "1" else { throw XCTSkip("Requires a disposable home fixture") }
        let url = try XCTUnwrap(environment["DULCET_UI_TEST_SERVER_URL"])
        let host = URLComponents(string: url)?.host ?? ""
        XCTAssertTrue(host == "localhost" || host.hasPrefix("127."))
        let app = XCUIApplication()
        app.launchArguments += ["-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", url,
            "-dulcet-debug-account-username", try XCTUnwrap(environment["DULCET_UI_TEST_USERNAME"]),
            "-dulcet-debug-account-password", try XCTUnwrap(environment["DULCET_UI_TEST_PASSWORD"])]
        launchInInteractionScope(app)
        if let expected = environment["DULCET_HOME_IDIOM"] {
            XCTAssertEqual(UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "iphone", expected)
        }
        guard let width = settledWindowWidth(app.windows.firstMatch) else { return }
        let compact = width < 700
        // Saved Home can appear before reconnect completes. Wait for the real connection
        // before choosing a tile, so a freshly published shelf cannot move it under the tap.
        XCTAssertTrue(awaitConnection(in: app, compact: compact), app.debugDescription)
        XCTAssertTrue(openDestination("Listen Now", sidebar: "dulcet.sidebar.listenNow", in: app, compact: compact))
        let home = app.descendants(matching: .any)["dulcet.listenNow"].firstMatch
        XCTAssertTrue(home.waitForExistence(timeout: 40), app.debugDescription)
        let album = app.buttons["dulcet.library.album"].firstMatch
        XCTAssertTrue(album.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(scrollIntoView(album, in: app), app.debugDescription)
        shoot("listen-now", app)
        album.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
        XCTAssertTrue(app.staticTexts["dulcet.album.title"].firstMatch.waitForExistence(timeout: 20))
        shoot("listen-now-album", app)
        app.terminate()
        app.launchArguments = []
        launchInInteractionScope(app)
        XCTAssertTrue(home.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(album.waitForExistence(timeout: 20), "Cached home must survive relaunch")
        shoot("listen-now-relaunch", app)
        XCTAssertTrue(openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact))
        XCTAssertTrue(openDestination("Listen Now", sidebar: "dulcet.sidebar.listenNow", in: app, compact: compact))
        XCTAssertTrue(home.waitForExistence(timeout: 10))
        try provePlaylistContextIfAdvertised(in: app, compact: compact, environment: environment)
    }

    /// The ordinary-server Home proof remains unchanged. On the advertised disposable server,
    /// a brand-new playlist cannot appear in history until this client actually submits its play.
    @MainActor
    private func provePlaylistContextIfAdvertised(in app: XCUIApplication, compact: Bool,
                                                   environment: [String: String]) throws {
        let server = TourServer(url: try (environment["DULCET_PLAYLIST_CONTEXT_UPSTREAM"] ?? XCTUnwrap(environment["DULCET_UI_TEST_SERVER_URL"])),
                                username: try XCTUnwrap(environment["DULCET_UI_TEST_USERNAME"]),
                                password: try XCTUnwrap(environment["DULCET_UI_TEST_PASSWORD"]))
        let extensions = rest("getOpenSubsonicExtensions", [], server)?["openSubsonicExtensions"] as? [[String: Any]] ?? []
        guard extensions.contains(where: { $0["name"] as? String == "dulcetHome" && ($0["versions"] as? [Int] ?? []).contains(1) }) else { return }
        let songs = (rest("search3", [URLQueryItem(name: "query", value: "UI Playback Canary"),
                                    URLQueryItem(name: "songCount", value: "20")], server)?["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let song = try XCTUnwrap(songs.first { $0["title"] as? String == "UI Playback Canary" })
        let songID = try XCTUnwrap(song["id"] as? String)
        let idiom = UIDevice.current.userInterfaceIdiom == .pad ? "ipados" : "iphone"
        let name = "Context " + idiom + " " + UUID().uuidString.prefix(8)
        let made = rest("createPlaylist", [URLQueryItem(name: "name", value: name),
                                           URLQueryItem(name: "songId", value: songID)], server)?["playlist"] as? [String: Any]
        let playlistID = try XCTUnwrap(made?["id"] as? String)
        defer { _ = rest("deletePlaylist", [URLQueryItem(name: "id", value: playlistID)], server) }
        func historyContainsPlaylist() -> Bool {
            let home = rest("getDulcetHome", [URLQueryItem(name: "shelf", value: "recentPlaylists")], server)?["dulcetHome"] as? [String: Any]
            return (home?["playlist"] as? [[String: Any]] ?? []).contains { $0["id"] as? String == playlistID }
        }
        XCTAssertFalse(historyContainsPlaylist(), "Creating or reading the playlist must not supply a play")
        if !compact {
            // The wide shell can already own its playlist window before this external fixture
            // create. Reconnect the actual app so that window revalidates its first publication.
            app.terminate()
            app.launchArguments = ["-dulcet-debug-connect-account", "-dulcet-debug-account-server-url",
                                   try XCTUnwrap(environment["DULCET_UI_TEST_SERVER_URL"]),
                                   "-dulcet-debug-account-username", server.username,
                                   "-dulcet-debug-account-password", server.password]
            launchInInteractionScope(app)
            XCTAssertTrue(awaitConnection(in: app, compact: compact), app.debugDescription)
        }
        XCTAssertTrue(openPlaylists(in: app, compact: compact), app.debugDescription)
        let row = app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(scrollIntoView(row, in: app))
        row.tap()
        let play = app.buttons["dulcet.playlist.play"].firstMatch
        XCTAssertTrue(play.waitForExistence(timeout: 20))
        XCTAssertTrue(waitFor(timeout: 20) { play.isEnabled })
        XCTAssertFalse(historyContainsPlaylist(), "Playlist reads must still leave history empty")
        play.tap()
        let miniPlayer = app.buttons["dulcet.mini-player.open"].firstMatch
        XCTAssertTrue(miniPlayer.waitForExistence(timeout: 15), "Playlist Play must open a real player: " + app.debugDescription)
        miniPlayer.tap()
        let elapsed = app.staticTexts["Elapsed"].firstMatch
        XCTAssertTrue(elapsed.waitForExistence(timeout: 15), "A player must report progressing media time: " + app.debugDescription)
        let initialElapsed = elapsed.value as? String
        XCTAssertTrue(waitFor(timeout: 8) { (elapsed.value as? String) != initialElapsed }, "Elapsed media time must advance before the delivery proof")
        var offlineRelaunch = false
        if let proxy = environment["DULCET_PLAYLIST_CONTEXT_OFFLINE_PROXY"] {
            func setOffline(_ offline: Bool) throws {
                let url = try XCTUnwrap(URL(string: proxy + "/fixture/network?offline=" + (offline ? "1" : "0")))
                XCTAssertEqual(String(data: try Data(contentsOf: url), encoding: .utf8), offline ? "offline" : "online")
            }
            // The fixture proxy buffers this 31-second local file before serving it. Block
            // new requests only after the real player's elapsed media time has advanced.
            try setOffline(true)
            defer { try? setOffline(false) }
            Thread.sleep(forTimeInterval: 23)
            XCTAssertFalse(historyContainsPlaylist(), "The failed submission must remain local while offline")
            app.terminate(); app.launchArguments = []; launchInInteractionScope(app)
            XCTAssertTrue(app.descendants(matching: .any)["dulcet.listenNow"].firstMatch.waitForExistence(timeout: 20), "Saved Home must reopen offline")
            XCTAssertFalse(historyContainsPlaylist(), "Offline relaunch must not fabricate delivery")
            try setOffline(false)
            app.terminate(); launchInInteractionScope(app)
            offlineRelaunch = true
        }
        XCTAssertTrue(waitFor(timeout: 50) { historyContainsPlaylist() }, "A real playlist play must reach the server with its raw playlist ID")
        if app.buttons["dulcet.now-playing.close"].firstMatch.exists { closePlayer(in: app) }
        dismissOverlay(in: app)
        XCTAssertTrue(openDestination("Listen Now", sidebar: "dulcet.sidebar.listenNow", in: app, compact: compact))
        let refresh = app.buttons["dulcet.listenNow.refresh"].firstMatch
        XCTAssertTrue(scrollIntoView(refresh, in: app))
        refresh.tap()
        let recent = app.buttons.matching(identifier: "dulcet.library.playlist")
            .matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch
        XCTAssertTrue(recent.waitForExistence(timeout: 30), "Home must render the playlist this client just played: " + app.debugDescription)
        XCTAssertTrue(scrollIntoView(recent, in: app))
        shoot("playlist-context-" + idiom, app)
        print("PLAYLIST CONTEXT PROOF idiom=\(idiom) rawPlaylist=\(playlistID) serverHistory=true homeTile=true offlineRelaunch=\(offlineRelaunch) source=actual-playlist-play")
    }

    @MainActor
    func testDesignTour() {
        guard ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1" else { return }
        let app = XCUIApplication()
        withoutIdleWaits(app) { self.driveDesignTour(app) }
    }

    @MainActor
    private func driveDesignTour(_ app: XCUIApplication) {
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
        let host = URLComponents(string: url)?.host?.lowercased() ?? ""
        guard host == "localhost" || host == "::1" || host.hasPrefix("127.") else {
            XCTFail("The tour requires a loopback disposable server")
            return
        }
        continueAfterFailure = true
        guard let proxyURL = environment["DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL"],
              let proxy = URLComponents(string: proxyURL), proxy.scheme == "http",
              proxy.host == "127.0.0.1", proxy.port != nil else {
            XCTFail("The tour requires the disposable loopback fault proxy for its offline relaunch")
            return
        }
        let server = TourServer(url: proxyURL, username: username, password: password)
        preparePlaylist(on: server)

        XCUIDevice.shared.orientation = environment["DULCET_DESIGN_TOUR_ORIENTATION"] == "landscape" ? .landscapeLeft : .portrait
        if let size = environment["DULCET_DESIGN_TOUR_TEXT_SIZE"] {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", size]
        }
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", server.url,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        launchInInteractionScope(app)
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 15), "The app window must exist")
        guard let width = settledWindowWidth(window) else { return }
        let compact = width < 700
        print("DULCET DESIGN TOUR start compact=\(compact) width=\(width)")
        guard awaitConnection(in: app, compact: compact) else {
            shoot("connection-UNREACHED", app)
            XCTFail("The disposable account must connect before the tour: " + app.debugDescription)
            return
        }

        if environment["DULCET_DESIGN_TOUR_PHASE"] == "offline" {
            offlineScreens(app, server: server, compact: compact, textSize: environment["DULCET_DESIGN_TOUR_TEXT_SIZE"])
            return
        }

        step("connection-connected", app) {
            self.openDestination("Connection", sidebar: "dulcet.sidebar.settings", in: app, compact: compact)
                && app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 20) && self.settle(app)
        }
        step("settings-quality", app) {
            let quality = app.descendants(matching: .any)["dulcet.streaming-quality"].firstMatch
            return quality.waitForExistence(timeout: 10) && self.scrollIntoView(quality, in: app) && self.settle(app)
        }
        step("quality-options", app) {
            let choice = app.buttons["dulcet.streaming-quality.unmetered"].firstMatch
            guard choice.waitForExistence(timeout: 10), self.scrollIntoView(choice, in: app) else { return false }
            choice.tap()
            return app.buttons["96 kbps"].firstMatch.waitForExistence(timeout: 10) && self.settle(app)
        }
        dismissOverlay(in: app)

        // Library root: Home on a phone, Home beside the sidebar on a regular width.
        step("library", app) {
            guard self.openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact) else { return false }
            // A regular width opens Library on Home; there is no Home row in the sidebar.
            return app.descendants(matching: .any)["dulcet.reader.home.recentlyAdded"].firstMatch
                .waitForExistence(timeout: 30) && self.settle(app)
        }
        // Phone Home exposes two full-list links even when their shelves are empty. Regular
        // widths also expose Recently Added's See All button. Photograph the real list page,
        // including its empty state, rather than requiring this synthetic corpus to fill it.
        let homeLists = compact
            ? [("recentlyPlayed", "Recently Played", "recently-played"), ("mostPlayed", "Most Played", "most-played")]
            : [("recentlyAdded", "Recently Added", "recently-added"), ("recentlyPlayed", "Recently Played", "recently-played"), ("mostPlayed", "Most Played", "most-played")]
        for (row, title, screen) in homeLists {
            step(screen, app) {
                guard self.openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact) else { return false }
                let link = app.buttons["dulcet.reader.home.\(row).all"].firstMatch
                guard link.waitForExistence(timeout: 15), self.scrollIntoView(link, in: app) else { return false }
                link.tap()
                return app.navigationBars[title].firstMatch.waitForExistence(timeout: 15)
                    && self.settle(app, seconds: 2)
            }
        }
        if !compact {
            step("sidebar-albums", app) {
                self.openSection("albums", in: app, compact: false)
                    && app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 30)
                    && self.settle(app)
            }
        }
        for section in ["albums", "artists", "genres", "favourites"] {
            step(section, app) {
                let row = section == "favourites" ? "dulcet.reader.track" : "dulcet.library.\(section == "albums" ? "album" : section == "artists" ? "artist" : "genre")"
                return self.openSection(section, in: app, compact: compact)
                    && app.buttons[row].firstMatch.waitForExistence(timeout: 30) && self.settle(app, seconds: 2)
            }
        }
        step("album-sort", app) {
            guard self.openSection("albums", in: app, compact: compact) else { return false }
            let sort = app.buttons["dulcet.reader.albums.sort"].firstMatch
            guard sort.waitForExistence(timeout: 10), self.scrollIntoView(sort, in: app) else { return false }
            sort.tap()
            return app.buttons["Random"].firstMatch.waitForExistence(timeout: 10) && self.settle(app)
        }
        if app.buttons["Done"].firstMatch.exists { app.buttons["Done"].firstMatch.tap() }
        dismissOverlay(in: app)
        step("album-menu", app) {
            guard self.openSection("albums", in: app, compact: compact) else { return false }
            let album = app.buttons.matching(identifier: "dulcet.library.album")
                .matching(NSPredicate(format: "label BEGINSWITH 'Threshold Boundary'")).firstMatch
            guard app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 20),
                  self.scrollIntoView(album, in: app) else { return false }
            let offered = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Add to Playlist'"))
                .firstMatch
            for _ in 0..<2 {
                _ = self.settle(app, seconds: 0.8)
                // Press the artwork rather than XCTest's inferred activation point, which can
                // land on a clipped caption near the bottom of the large-text grid.
                album.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).press(forDuration: 1.2)
                if offered.waitForExistence(timeout: 5) { return self.settle(app) }
            }
            return false
        }
        dismissOverlay(in: app)
        step("artist-menu", app) {
            guard self.openSection("artists", in: app, compact: compact) else { return false }
            let artist = app.buttons["dulcet.library.artist"].firstMatch
            guard artist.waitForExistence(timeout: 20), self.scrollIntoView(artist, in: app) else { return false }
            _ = self.settle(app, seconds: 0.8)
            artist.press(forDuration: 1.2)
            return app.buttons.matching(NSPredicate(format: "label == 'Favorite' OR label == 'Remove Favorite'"))
                .firstMatch.waitForExistence(timeout: 10) && self.settle(app)
        }
        dismissOverlay(in: app)
        step("genre", app) {
            guard self.openSection("genres", in: app, compact: compact) else { return false }
            let genre = app.buttons["dulcet.library.genre"].firstMatch
            guard genre.waitForExistence(timeout: 20), self.scrollIntoView(genre, in: app) else { return false }
            genre.tap()
            return app.staticTexts["dulcet.genre.title"].firstMatch.waitForExistence(timeout: 15) && self.settle(app)
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
        let pause = app.buttons["dulcet.mini-player.play-pause"].firstMatch
        if pause.waitForExistence(timeout: 3), pause.label == "Pause", self.playerElementIsHittable(pause) { pause.tap() }

        step("playlists", app) {
            self.openPlaylists(in: app, compact: compact)
                && app.buttons.matching(identifier: "dulcet.library.playlist").firstMatch.waitForExistence(timeout: 30)
                && self.settle(app)
        }
        step("playlist-menu", app) {
            let row = app.buttons.matching(identifier: "dulcet.library.playlist")
                .matching(NSPredicate(format: "label BEGINSWITH %@", Self.playlistName)).firstMatch
            guard row.waitForExistence(timeout: 20), self.scrollIntoView(row, in: app) else { return false }
            _ = self.settle(app, seconds: 0.8)
            row.press(forDuration: 1.2)
            return app.buttons["dulcet.playlist.delete"].firstMatch.waitForExistence(timeout: 10) && self.settle(app)
        }
        dismissOverlay(in: app)
        step("playlist", app) {
            let row = app.buttons.matching(identifier: "dulcet.library.playlist")
                .matching(NSPredicate(format: "label BEGINSWITH %@", Self.playlistName)).firstMatch
            guard row.waitForExistence(timeout: 20), self.scrollIntoView(row, in: app) else { return false }
            row.tap()
            let title = app.staticTexts["dulcet.playlist.title"].firstMatch
            let reached = title.waitForExistence(timeout: 10)
                && app.buttons["dulcet.reader.track"].firstMatch.waitForExistence(timeout: 20)
                && self.settle(app)
            if reached {
                XCTAssertEqual(app.navigationBars.staticTexts.matching(identifier: Self.playlistName).count, 0,
                               "The playlist's header title must appear only once at rest")
                let shuffle = app.buttons["dulcet.playlist.shuffle"].firstMatch
                print("DULCET PLAYLIST LAYOUT shuffle=\(shuffle.frame)")
                XCTAssertLessThan(shuffle.frame.height, 110,
                                  "Shuffle must remain a single readable row, never a stack of letters")
                XCTAssertGreaterThan(shuffle.frame.width, 100,
                                     "Shuffle must have room for its whole label")
            }
            return reached
        }
        step("playlist-edit", app) {
            let edit = app.buttons["dulcet.playlist.edit"].firstMatch
            guard edit.waitForExistence(timeout: 10), self.scrollIntoView(edit, in: app) else { return false }
            edit.tap()
            return app.buttons["dulcet.playlist.done"].firstMatch.waitForExistence(timeout: 10) && self.settle(app)
        }
        if app.buttons["dulcet.playlist.done"].firstMatch.exists { app.buttons["dulcet.playlist.done"].firstMatch.tap() }
        step("playlist-more", app) {
            let more = app.buttons["dulcet.playlist.more"].firstMatch
            guard more.waitForExistence(timeout: 10), self.scrollIntoView(more, in: app) else { return false }
            more.tap()
            let delete = app.buttons["Delete Playlist"].firstMatch
            let reached = delete.waitForExistence(timeout: 10) && self.settle(app)
            if reached {
                let bounds = app.windows.firstMatch.frame
                print("DULCET PLAYLIST ACTION MARGINS delete=\(delete.frame) window=\(bounds)")
                XCTAssertGreaterThanOrEqual(delete.frame.minX, bounds.minX + 8,
                                            "Playlist actions must stay inside the screen margins")
                XCTAssertLessThanOrEqual(delete.frame.maxX, bounds.maxX - 8,
                                         "Playlist actions must stay inside the screen margins")
            }
            return reached
        }
        step("playlist-delete-confirmation", app) {
            let delete = app.buttons["Delete Playlist"].firstMatch
            guard delete.waitForExistence(timeout: 5) else { return false }
            delete.tap()
            return app.alerts.firstMatch.waitForExistence(timeout: 10)
                && app.alerts.buttons["Cancel"].firstMatch.exists && self.settle(app)
        }
        if app.alerts.buttons["Cancel"].firstMatch.exists { app.alerts.buttons["Cancel"].firstMatch.tap() }
        step("playlist-rename", app) {
            let more = app.buttons["dulcet.playlist.more"].firstMatch
            guard more.waitForExistence(timeout: 10) else { return false }
            more.tap()
            let rename = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Rename'")).firstMatch
            guard rename.waitForExistence(timeout: 5) else { return false }
            rename.tap()
            return app.alerts.textFields.firstMatch.waitForExistence(timeout: 10) && self.settle(app)
        }
        if app.buttons["Cancel"].firstMatch.exists { app.buttons["Cancel"].firstMatch.tap() }
        step("playlist-create", app) {
            guard self.openPlaylists(in: app, compact: compact) else { return false }
            let new = app.buttons["dulcet.playlists.new"].firstMatch
            guard new.waitForExistence(timeout: 10), self.scrollIntoView(new, in: app) else { return false }
            new.tap()
            return app.alerts.textFields.firstMatch.waitForExistence(timeout: 10) && self.settle(app)
        }
        step("playlist-create-filled", app) {
            let field = app.alerts.textFields.firstMatch
            guard field.waitForExistence(timeout: 5) else { return false }
            field.tap()
            field.typeText("Evening Mix")
            return (field.value as? String) == "Evening Mix" && self.settle(app)
        }
        if app.buttons["Cancel"].firstMatch.exists { app.buttons["Cancel"].firstMatch.tap() }
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
        step("track-menu", app) {
            guard self.openAlbum("Threshold Boundary", in: app, compact: compact) else { return false }
            let track = app.buttons["dulcet.reader.track"].firstMatch
            guard track.waitForExistence(timeout: 20), self.scrollIntoView(track, in: app) else { return false }
            track.press(forDuration: 1)
            return app.buttons["dulcet.track.addToPlaylist"].firstMatch.waitForExistence(timeout: 5) && self.settle(app)
        }
        step("playlist-add", app) {
            let add = app.buttons["dulcet.track.addToPlaylist"].firstMatch
            guard add.waitForExistence(timeout: 5) else { return false }
            add.tap()
            return app.buttons["dulcet.addToPlaylist.playlist"].firstMatch.waitForExistence(timeout: 15) && self.settle(app)
        }
        if app.buttons["Cancel"].firstMatch.exists { app.buttons["Cancel"].firstMatch.tap() }
        offlineScreens(app, server: server, compact: compact, textSize: environment["DULCET_DESIGN_TOUR_TEXT_SIZE"])
        XCTAssertEqual(shotNumber, compact ? 34 : 36, "The connected tour must photograph every requested destination and overlay")
        print("DULCET DESIGN TOUR done shots=\(shotNumber)")
    }

    @MainActor
    private func withoutIdleWaits(_ app: XCUIApplication, _ body: @escaping () -> Void) {
        let selector = NSSelectorFromString("_performWithInteractionOptions:block:")
        guard let method = class_getInstanceMethod(type(of: app), selector),
              let encoding = method_getTypeEncoding(method).map({ String(cString: $0) }),
              encoding.filter({ !$0.isNumber }) == "v@:I@?" else {
            print("DULCET ACCOUNT STATES interaction scope unavailable; using ordinary idle waits")
            body()
            return
        }
        typealias Perform = @convention(c) (AnyObject, Selector, UInt32, @convention(block) () -> Void) -> Void
        let perform = unsafeBitCast(method_getImplementation(method), to: Perform.self)
        perform(app, selector, 3, body)
    }

    /// XCTest stores these options on both XCUIApplication and its current process. A relaunch
    /// keeps the application's value but creates a process with ordinary waits. Re-entering the
    /// active scope after launch applies its options to that new process too; the nested scope
    /// restores the outer value, and the outer scope restores ordinary waits when the proof ends.
    @MainActor
    private func launchInInteractionScope(_ app: XCUIApplication) {
        app.launch()
        withoutIdleWaits(app) {}
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
        // Element screenshots use a stale crop rectangle after landscape rotation on Simulator.
        // The screen request preserves the whole iPad display and its orientation metadata.
        // Portrait uses the element request: whole-display capture has stalled after grid navigation.
        let landscape = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_ORIENTATION"] == "landscape"
        let screenshot = landscape ? XCUIScreen.main.screenshot() : app.screenshot()
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

    @MainActor
    private func dismissOverlay(in app: XCUIApplication) {
        // Outside the menu and its source, in the navigation/header margin. Do not select a
        // quality/sort option or a destructive menu action merely to dismiss its photograph.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.12)).tap()
        _ = settle(app, seconds: 0.4)
    }

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
        let row = app.buttons[sidebar].firstMatch
        guard row.waitForExistence(timeout: 10), row.isHittable else { return false }
        row.tap()
        return true
    }

    @MainActor
    private func tapSidebarSection(_ section: String, in app: XCUIApplication) -> Bool {
        let row = app.descendants(matching: .any)["dulcet.sidebar.section.\(section)"].firstMatch
        guard row.waitForExistence(timeout: 10), row.isHittable else { return false }
        row.tap()
        return true
    }

    @MainActor
    private func openAlbum(_ album: String, in app: XCUIApplication, compact: Bool) -> Bool {
        guard openSection("albums", in: app, compact: compact) else { return false }
        let tile = app.buttons.matching(identifier: "dulcet.library.album")
            .matching(NSPredicate(format: "label BEGINSWITH %@", album)).firstMatch
        // Accessibility sizes use a taller grid. LazyVGrid need not instantiate this last
        // fixture until scrolling reaches it; wait for the grid, then scroll its target in.
        guard app.buttons["dulcet.library.album"].firstMatch.waitForExistence(timeout: 30),
              scrollIntoView(tile, in: app) else { return false }
        let title = app.staticTexts["dulcet.album.title"].firstMatch
        for _ in 0..<3 {
            guard tile.exists, scrollIntoView(tile, in: app) else { return false }
            _ = settle(app, seconds: 0.5)
            tile.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap()
            if title.waitForExistence(timeout: 5), waitFor(timeout: 5, { title.label == album }) { return true }
        }
        return false
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
    private func openSection(_ section: String, in app: XCUIApplication, compact: Bool) -> Bool {
        guard openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact) else { return false }
        if !compact { return tapSidebarSection(section, in: app) }
        let link = app.buttons["dulcet.reader.section.\(section)"].firstMatch
        guard link.waitForExistence(timeout: 15), scrollIntoView(link, in: app) else { return false }
        link.tap()
        return true
    }

    @MainActor
    private func openPlaylists(in app: XCUIApplication, compact: Bool) -> Bool {
        let hasBrowse = compact ? app.tabBars.buttons["Browse"].firstMatch.exists
            : app.descendants(matching: .any)["dulcet.sidebar.browse"].firstMatch.exists
        guard openDestination(hasBrowse ? "Browse" : "Library", sidebar: hasBrowse ? "dulcet.sidebar.browse" : "dulcet.sidebar.library", in: app, compact: compact) else { return false }
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
    private func scrollIntoView(_ element: XCUIElement, in app: XCUIApplication,
                                verticalMargin: CGFloat = 100, scrollContainer: XCUIElement? = nil) -> Bool {
        let window = app.windows.firstMatch
        let scrolling = scrollContainer ?? app
        // Lazy grids can recycle a tile between `exists` and a separate frame query while a
        // swipe decelerates. A throwing snapshot gives one frame or lets the next swipe retry.
        func frame() -> CGRect? { (try? element.snapshot())?.frame }
        func visible(_ frame: CGRect?) -> Bool {
            guard let frame, !frame.isEmpty, element.isHittable else { return false }
            let bounds = window.frame
            return frame.midY >= bounds.minY + verticalMargin && frame.midY <= bounds.maxY - verticalMargin
                && bounds.contains(CGPoint(x: frame.midX, y: frame.midY))
        }
        for _ in 0..<8 {
            let current = frame()
            if visible(current) { return true }
            if let current, current.midY < window.frame.minY + verticalMargin { scrolling.swipeDown() }
            else { scrolling.swipeUp() }
            _ = settle(app, seconds: 0.6)
        }
        return visible(frame())
    }

    @MainActor
    private func offlineScreens(_ app: XCUIApplication, server: TourServer, compact: Bool, textSize: String?) {
        step("downloads-populated", app) {
            guard self.openAlbum("Threshold Boundary", in: app, compact: compact) else { return false }
            let track = app.buttons["dulcet.reader.track"].firstMatch
            guard track.waitForExistence(timeout: 20), self.scrollIntoView(track, in: app) else { return false }
            // A retake can reuse the real file downloaded by its preceding pass. Its badge is
            // the required state; the menu correctly offers removal instead of Download then.
            if (track.value as? String)?.contains("Downloaded") == true { return self.settle(app) }
            _ = self.settle(app, seconds: 0.8)
            track.press(forDuration: 1.2)
            let download = app.buttons["Download"].firstMatch
            if !download.waitForExistence(timeout: 2) {
                let more = app.buttons["More"].firstMatch
                if more.exists { more.tap() }
            }
            if download.waitForExistence(timeout: 5) { download.tap() }
            else { app.tap(); return false }
            return self.waitFor(timeout: 60) { (track.value as? String)?.contains("Downloaded") == true }
                && self.settle(app)
        }
        // Hold a real outage before saved launch; reachable saved accounts reconnect themselves.
        guard setTourOutage(server.url, enabled: true) else { return }
        defer { _ = setTourOutage(server.url, enabled: false) }
        app.terminate()
        app.launchArguments = []
        if let size = textSize { app.launchArguments += ["-UIPreferredContentSizeCategoryName", size] }
        self.launchInInteractionScope(app)
        step("device-only-library", app) {
            self.openDestination("Library", sidebar: "dulcet.sidebar.library", in: app, compact: compact)
                && app.buttons["dulcet.reader.reconnect"].firstMatch.waitForExistence(timeout: 20) && self.settle(app)
        }
        step("downloads-offline", app) {
            self.openAlbum("Threshold Boundary", in: app, compact: compact)
                && self.scrollIntoView(app.buttons["dulcet.reader.track"].firstMatch, in: app)
                && app.buttons["dulcet.reader.track"].firstMatch.waitForExistence(timeout: 15) && self.settle(app)
        }
    }

    /// This is the tour's own disposable proxy; changing it never stops a shared server process.
    private func setTourOutage(_ proxyURL: String, enabled: Bool) -> Bool {
        guard let url = URL(string: proxyURL + "/__dulcet/outage?state=" + (enabled ? "on" : "off")) else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        final class Outcome: @unchecked Sendable { var status = -1 }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: request) { _, response, _ in
            outcome.status = (response as? HTTPURLResponse)?.statusCode ?? -1
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 20) == .success else { task.cancel(); return false }
        XCTAssertEqual(outcome.status, 200, "The tour's disposable outage control must answer")
        return outcome.status == 200
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
        if let id = ids.first {
            XCTAssertNotNil(rest("star", [URLQueryItem(name: "id", value: id)], server))
            XCTAssertNotNil(rest("scrobble", [URLQueryItem(name: "id", value: id), URLQueryItem(name: "submission", value: "true")], server),
                            "A disposable play populates the reachable Recently Played and Most Played shelves")
        }
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
