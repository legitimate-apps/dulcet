import CryptoKit
import Foundation
import UIKit
import XCTest

/// A screenshot tour of the Apple TV app's key screens against a live disposable server, for
/// design review, driven by the remote alone.
///
/// This is an instrument, not a proof: it asserts only that each screen was reached -- and, where
/// the shot is about focus, that focus is where the name says -- so a shot is never of a spinner,
/// an error or the wrong control in place of what it is named after. `tools/design-tour-apple
/// --device tvos` runs it and compresses the shots; it returns at once unless
/// `DULCET_DESIGN_TOUR=1`, so no ordinary run of this target photographs anything, and apple-ci
/// selects its tvOS controls by name and never this one.
///
/// Each shot is an attachment, and a PNG in `DULCET_DESIGN_TOUR_OUT`, named `<nn>-<screen>`. A
/// screen that could not be reached is still photographed, as `<nn>-<screen>-UNREACHED`, and
/// recorded as a failure, and the tour continues: one broken screen must not cost the reviewer
/// every later one. Playlist creation and favouriting write only to the disposable server; the player heart and stars
/// are focused for their shots, never pressed.
final class DulcetTVDesignTourUITests: XCTestCase {
    /// Section order in the tvOS section bar, which is also the order the remote traverses.
    private static let sections = ["library", "search", "nowPlaying", "settings"]
    private static let album = "Threshold Boundary"
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
        guard let serverParts = URLComponents(string: url), serverParts.scheme == "http",
              serverParts.host == "127.0.0.1", serverParts.port != nil else {
            XCTFail("The tour requires a disposable loopback server")
            return
        }
        continueAfterFailure = true
        let remote = XCUIRemote.shared
        let app = XCUIApplication()
        tourAccountStates(app, server: (url, username, password))
        app.terminate()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", url,
            "-dulcet-debug-account-username", username,
            "-dulcet-debug-account-password", password,
        ]

        // Library root as a person meets it at launch: a saved account opens straight into its
        // library with focus inside it. A first launch on this simulator stops on Connection
        // while the account connects, so the tour waits for that and launches again.
        step("library", app) {
            app.launch()
            guard let section = self.launchSection(app) else { return false }
            if section == "Connection" {
                guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 60) else { return false }
                app.terminate()
                app.launch()
                guard self.launchSection(app) == "Library" else { return false }
            }
            let tile = app.buttons.matching(identifier: "dulcet.library.album").firstMatch
            // Artwork arrives after the tiles. Three seconds is a pause, not a wait for covers:
            // a relaunch has been seen to still show the placeholder covers here.
            return tile.waitForExistence(timeout: 30) && self.focusedSection(app) == nil && self.settle(3)
        }

        // An album: Down from the launch focus to the album shelf on Home, Right along it to
        // Threshold Boundary, whose first track carries the synced lyrics the lyrics shot needs,
        // then Select.
        step("album", app) {
            let tiles = app.buttons.matching(identifier: "dulcet.library.album")
            guard self.press(.down, until: { self.focused(in: tiles) != nil }, bound: 8),
                  self.press(.right, until: { self.focused(in: tiles)?.label.hasPrefix(Self.album) == true }, bound: 12)
            else { return false }
            remote.press(.select)
            let title = app.staticTexts["dulcet.album.title"].firstMatch
            return title.waitForExistence(timeout: 15) && self.waitFor(10) { title.label == Self.album }
                && self.settle(1.5)
        }

        // Now Playing, playing, focus on Play/Pause: the album's own Play, by remote. The first
        // track of the corpus's albums carries synced lyrics, which the lyrics shot needs.
        let pause = app.buttons["Pause"].firstMatch
        step("now-playing", app) {
            let play = app.buttons["dulcet.album.play"].firstMatch
            // Play is disabled until the album's tracks have loaded; focus lands beside it.
            guard play.waitForExistence(timeout: 10), self.waitFor(20, { play.isEnabled }),
                  self.press(.left, until: { play.hasFocus }, bound: 4) else { return false }
            remote.press(.select)
            let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
            if !title.waitForExistence(timeout: 10) {
                // Playback did not bring Now Playing forward on its own; reach it as a person would.
                guard self.selectSection(app, "nowPlaying"), title.waitForExistence(timeout: 15) else { return false }
            }
            guard pause.waitForExistence(timeout: 20) else { return false }
            if !pause.hasFocus, !self.press(.down, until: { pause.hasFocus }, bound: 4) { return false }
            return self.waitFor(10) { self.progressAdvanced(app) } && self.settle(1.5)
        }

        // The heart's row: Down from the transport reaches it (Left if Down landed beside it).
        // The star shot is taken first, on the third star, so the focused-star lift is visible;
        // then back to the heart for its own shot. Nothing is pressed.
        let heart = app.buttons["dulcet.now-playing.favorite"].firstMatch
        let lyrics = app.buttons["dulcet.now-playing.lyrics"].firstMatch
        let stars = (1...5).map { app.buttons["dulcet.now-playing.rating.star.\($0)"].firstMatch }
        let reachedHeart = heart.waitForExistence(timeout: 10) && press(bound: 12, until: { heart.hasFocus }) {
            let besideHeart = (lyrics.exists && lyrics.hasFocus) || stars.contains { $0.exists && $0.hasFocus }
            return besideHeart ? .left : .down
        }
        step("now-playing-star", app) {
            guard reachedHeart else { return false }
            for _ in 0..<3 { remote.press(.right) }
            return stars[2].hasFocus && self.settle(1)
        }
        step("now-playing-heart", app) {
            guard reachedHeart, self.press(.left, until: { heart.hasFocus }, bound: 6) else { return false }
            return self.settle(1)
        }

        // Lyrics, while playing: Right past the fifth star, Select, and a line lit by media time.
        step("lyrics", app) {
            guard lyrics.exists, self.press(.right, until: { lyrics.hasFocus }, bound: 8) else { return false }
            remote.press(.select)
            let panel = app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch
            return panel.waitForExistence(timeout: 10)
                && app.staticTexts["dulcet.lyrics.line.current"].firstMatch.waitForExistence(timeout: 25)
                && self.settle(1.5)
        }
        // Lyrics off, and paused for the rest of the tour: the simulator's audio is the host's.
        if lyrics.exists, lyrics.hasFocus, lyrics.label == "Hide Lyrics" { remote.press(.select) }
        remote.press(.playPause)

        // Search with results, by remote: the section bar, the field, the query typed, Done.
        step("search", app) {
            guard self.selectSection(app, "search") else { return false }
            let field = app.textFields["dulcet.search.field"].firstMatch
            guard field.waitForExistence(timeout: 30),
                  self.press(.down, until: { field.hasFocus }, bound: 4) else { return false }
            remote.press(.select)
            guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { return false }
            field.typeText("Threshold")
            let done = app.buttons["done"].firstMatch
            guard done.waitForExistence(timeout: 5),
                  self.press(.down, until: { done.hasFocus }, bound: 6) else { return false }
            remote.press(.select)
            guard app.keyboards.firstMatch.waitForNonExistence(timeout: 5),
                  app.buttons["dulcet.search.result.0"].firstMatch.waitForExistence(timeout: 30) else { return false }
            // Onto the first result, so the shot shows a focused row as well as the list.
            let results = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dulcet.search.result.'"))
            _ = self.press(.down, until: { self.focused(in: results) != nil }, bound: 3)
            return self.settle(1.5)
        }

        // Settings, with focus on the streaming-quality choice.
        step("settings-quality", app) {
            guard self.selectSection(app, "settings") else { return false }
            let quality = app.descendants(matching: .any)["dulcet.streaming-quality"].firstMatch
            guard quality.waitForExistence(timeout: 30) else { return false }
            // Every choice's identifier starts with the section's (`.unmetered.<quality>`,
            // `.metered.<quality>`), so the prefix finds any of them.
            let choices = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dulcet.streaming-quality'"))
            return self.press(.down, until: { self.focused(in: choices) != nil }, bound: 12) && self.settle(1.5)
        }
        tourLibrarySections(app, server: (url, username, password))
        print("DULCET DESIGN TOUR done shots=\(shotNumber)")
    }

    // MARK: - Steps and shots

    /// Runs one screen: reach it, then photograph it whether or not it was reached.
    @MainActor
    private func step(_ screen: String, _ app: XCUIApplication, _ reach: () -> Bool) {
        let reached = reach()
        _ = settle(0.6)
        shoot(reached ? screen : screen + "-UNREACHED")
        if !reached {
            XCTFail("The design tour could not reach \(screen): " + app.debugDescription)
        }
        // What held remote focus in the shot, so a reviewer can tell which control is lifted.
        let focus = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
        let held = focus.exists ? "\(focus.identifier.isEmpty ? "-" : focus.identifier) \(focus.label.debugDescription)" : "none"
        print("DULCET DESIGN TOUR screen=\(screen) reached=\(reached) focus=\(held)")
    }

    @MainActor
    private func shoot(_ screen: String) {
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

    /// Lets focus animations and artwork land before a shot, without asserting anything.
    private func settle(_ seconds: TimeInterval) -> Bool {
        RunLoop.current.run(until: Date().addingTimeInterval(seconds))
        return true
    }

    private func waitFor(_ timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return condition()
    }

    // MARK: - Remote

    /// Presses `button` until `condition` holds, re-checking before every press; false if
    /// `bound` presses did not get there.
    @MainActor
    private func press(_ button: XCUIRemote.Button, until condition: () -> Bool, bound: Int) -> Bool {
        press(bound: bound, until: condition) { button }
    }

    @MainActor
    private func press(bound: Int, until condition: () -> Bool, choosing next: () -> XCUIRemote.Button) -> Bool {
        for _ in 0..<bound where !condition() {
            XCUIRemote.shared.press(next())
        }
        return condition()
    }

    /// The element of `query` holding remote focus, if one does.
    @MainActor
    private func focused(in query: XCUIElementQuery) -> XCUIElement? {
        let element = query.matching(NSPredicate(format: "hasFocus == true")).firstMatch
        return element.exists ? element : nil
    }

    /// The navigation bar a launch settles on, Connection or Library, or nil after 30 s.
    @MainActor
    private func launchSection(_ app: XCUIApplication) -> String? {
        var section: String?
        _ = waitFor(30) {
            let bar = app.navigationBars.firstMatch
            section = bar.exists && ["Connection", "Library"].contains(bar.identifier) ? bar.identifier : nil
            return section != nil
        }
        return section
    }

    /// Whether the Now Playing scrubber reports a position past zero.
    @MainActor
    private func progressAdvanced(_ app: XCUIApplication) -> Bool {
        let progress = app.progressIndicators["Now Playing"].firstMatch
        // One snapshot, so a redraw between reads means "not yet" rather than a failed test.
        guard let value = (try? progress.snapshot())?.value as? String else { return false }
        return !value.hasPrefix("0:00 ")
    }

    /// The section whose bar control currently holds remote focus, or nil while focus is inside
    /// a section's own content.
    @MainActor
    private func focusedSection(_ app: XCUIApplication) -> String? {
        Self.sections.first { section in
            let control = app.buttons["dulcet.tab.\(section)"].firstMatch
            return control.exists && control.hasFocus
        }
    }

    /// Reaches one section through the bar, the way a person does: Up to the bar (the exit
    /// command from a deep surface, where Up alone does not get there), across to the section's
    /// own control, Select.
    @MainActor
    private func selectSection(_ app: XCUIApplication, _ section: String) -> Bool {
        guard let target = Self.sections.firstIndex(of: section) else { return false }
        if focusedSection(app) == nil, !press(.up, until: { self.focusedSection(app) != nil }, bound: 4) {
            XCUIRemote.shared.press(.menu)
            guard waitFor(3, { self.focusedSection(app) != nil }) else { return false }
        }
        for _ in 0...Self.sections.count {
            guard let current = focusedSection(app), let index = Self.sections.firstIndex(of: current) else { return false }
            if index == target { break }
            XCUIRemote.shared.press(index > target ? .left : .right)
        }
        guard focusedSection(app) == section else { return false }
        XCUIRemote.shared.press(.select)
        return true
    }

    // MARK: - Connected Library places and playlist creation

    @MainActor
    private func tourLibrarySections(_ app: XCUIApplication, server: Server) {
        let remote = XCUIRemote.shared
        for (row, screen) in [("recentlyAdded", "recently-added"), ("recentlyPlayed", "recently-played"), ("mostPlayed", "most-played")] {
            step(screen, app) {
                // Keep the first two Home photographs' original play history. The lower
                // frequent shelf needs enough real fixture albums to traverse horizontally:
                // a singleton shelf's full-width focus section traps the remote on its card.
                if row == "mostPlayed", !self.seedMostPlayedFixture(server: server) { return false }
                // Each independent full-list photograph starts from the same real account launch
                // as Library's proven first shot, so a previous list's scroll/focus is not reused.
                app.terminate()
                app.launch()
                guard self.launchSection(app) == "Library", self.selectLibrarySection(app, "home") else { return false }
                let all = app.buttons["dulcet.reader.home.\(row).all"].firstMatch
                guard all.waitForExistence(timeout: 30), self.focusHomeAllByRemote(all, in: app) else { return false }
                remote.press(.select)
                return app.buttons.matching(identifier: "dulcet.library.album").firstMatch.waitForExistence(timeout: 30)
                    && self.settle(2)
            }
        }
        step("all-albums", app) {
            self.selectLibrarySection(app, "albums")
                && app.buttons.matching(identifier: "dulcet.library.album").firstMatch.waitForExistence(timeout: 30)
                && self.settle(2)
        }
        step("album-sort", app) {
            let sort = app.segmentedControls["dulcet.reader.albums.sort"].firstMatch
            guard sort.waitForExistence(timeout: 10),
                  self.press(.down, until: { self.focused(in: sort.buttons) != nil }, bound: 4) else { return false }
            // TV renders all seven options inline as segments, not a menu. The container
            // cannot take focus; a real child segment does. Focus itself changes selection.
            let title = sort.buttons["Title"].firstMatch
            guard self.press(.left, until: { title.hasFocus }, bound: 7) else { return false }
            return sort.buttons["Top Rated"].firstMatch.exists && self.settle(2)
        }
        var albumMenuOpened = false
        step("album-menu", app) {
            let tile = app.buttons.matching(identifier: "dulcet.library.album")
                .matching(NSPredicate(format: "label BEGINSWITH %@", Self.album)).firstMatch
            guard self.focusAlbumTileByRemote(tile, in: app) else { return false }
            _ = self.settle(1)
            // Album grid tiles have been observed to retain focus after the first hold without
            // opening their native menu. Retry a longer real hold only while this same tile
            // still holds focus. A native menu action proves the popup, including when the
            // optional playlist action is unavailable; inline controls are outside its cells.
            let action = app.cells.descendants(matching: .any).matching(NSPredicate(
                format: "label IN %@", ["Play", "Shuffle", "Favorite", "Remove Favorite", "Add to Playlist…"]
            )).firstMatch
            for duration in [1.5, 3.0] {
                guard tile.exists, tile.hasFocus else { break }
                remote.press(.select, forDuration: duration)
                if action.waitForExistence(timeout: 4) {
                    albumMenuOpened = true
                    break
                }
            }
            return albumMenuOpened && self.settle(1)
        }
        if app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists { remote.press(.menu) }
        step("all-artists", app) {
            self.selectLibrarySection(app, "artists")
                && app.buttons.matching(identifier: "dulcet.library.artist").firstMatch.waitForExistence(timeout: 30)
                && self.settle(1)
        }
        var artistMenuOpened = false
        step("artist-menu", app) {
            let row = app.buttons.matching(identifier: "dulcet.library.artist").firstMatch
            guard row.waitForExistence(timeout: 15), self.focusByRemote(row, in: app) else { return false }
            remote.press(.select, forDuration: 1.5)
            // The observed TV context menu exposes its action as Other inside a focused
            // CollectionView Cell; the list's inline hearts are Buttons outside these cells.
            let action = app.cells.descendants(matching: .any).matching(NSPredicate(
                format: "label IN %@", ["Favorite", "Remove Favorite"]
            )).firstMatch
            artistMenuOpened = action.waitForExistence(timeout: 10)
            return artistMenuOpened && self.settle(1)
        }
        if app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists { remote.press(.menu) }
        step("artist", app) {
            let row = app.buttons.matching(identifier: "dulcet.library.artist").firstMatch
            return self.pressByRemote(row, in: app, name: "Artist")
                && app.staticTexts["dulcet.artist.title"].firstMatch.waitForExistence(timeout: 20)
                && app.buttons.matching(identifier: "dulcet.library.album").firstMatch.waitForExistence(timeout: 30)
                && self.settle(2)
        }
        if app.staticTexts["dulcet.artist.title"].firstMatch.exists { remote.press(.menu) }
        step("genres", app) {
            self.selectLibrarySection(app, "genres")
                && app.buttons.matching(identifier: "dulcet.library.genre").firstMatch.waitForExistence(timeout: 30)
        }
        step("genre", app) {
            let row = app.buttons.matching(identifier: "dulcet.library.genre").firstMatch
            return self.pressByRemote(row, in: app, name: "Genre")
                && app.staticTexts["dulcet.genre.title"].firstMatch.waitForExistence(timeout: 20)
                && app.buttons.matching(identifier: "dulcet.reader.track").firstMatch.waitForExistence(timeout: 30)
                && self.settle(1)
        }
        if app.staticTexts["dulcet.genre.title"].firstMatch.exists { remote.press(.menu) }
        step("favourites", app) {
            self.selectLibrarySection(app, "favourites")
                && app.navigationBars["Favorites"].firstMatch.waitForExistence(timeout: 20)
                && self.settle(2)
        }
        step("playlists", app) {
            self.selectLibrarySection(app, "playlists")
                && app.navigationBars["Playlists"].firstMatch.waitForExistence(timeout: 20)
                && self.settle(2)
        }
        // Creation is the only playlist editor shipped on TV; its page has no rename/edit action.
        // This makes one playlist on the disposable account and leaves it available for the next
        // variant's review. A unique name keeps a repeated tour's rows identifiable.
        let name = "TV Design Tour " + UUID().uuidString.prefix(8)
        step("track-menu", app) {
            guard self.selectLibrarySection(app, "albums") else { return false }
            let tile = app.buttons.matching(identifier: "dulcet.library.album")
                .matching(NSPredicate(format: "label BEGINSWITH %@", Self.album)).firstMatch
            guard self.focusAlbumTileByRemote(tile, in: app) else { return false }
            remote.press(.select)
            guard app.staticTexts["dulcet.album.title"].firstMatch.waitForExistence(timeout: 20) else { return false }
            let row = app.buttons.matching(identifier: "dulcet.reader.track").firstMatch
            guard row.waitForExistence(timeout: 30), self.focusByRemote(row, in: app) else { return false }
            remote.press(.select, forDuration: 1.5)
            return app.descendants(matching: .any)["dulcet.track.addToPlaylist"].firstMatch.waitForExistence(timeout: 10)
        }
        step("playlist-add", app) {
            let item = app.descendants(matching: .any)["dulcet.track.addToPlaylist"].firstMatch
            guard item.exists, self.pressUntilFocused(app, item, .down, limit: 10) else { return false }
            remote.press(.select)
            return app.descendants(matching: .any)["dulcet.addToPlaylist.new"].firstMatch.waitForExistence(timeout: 15)
                && self.settle(1)
        }
        step("playlist-create", app) {
            let new = app.descendants(matching: .any)["dulcet.addToPlaylist.new"].firstMatch
            guard self.pressUntilFocused(app, new, .up, limit: 6) else { return false }
            remote.press(.select)
            return app.textFields["dulcet.playlist.name"].firstMatch.waitForExistence(timeout: 10)
        }
        step("playlist-create-filled", app) {
            let field = app.textFields["dulcet.playlist.name"].firstMatch
            guard field.exists, self.focusByRemote(field, in: app) else { return false }
            remote.press(.select)
            guard app.keyboards.firstMatch.waitForExistence(timeout: 5) else { return false }
            field.typeText(String(name))
            let done = app.buttons["done"].firstMatch
            guard done.waitForExistence(timeout: 5), self.press(.down, until: { done.hasFocus }, bound: 6) else { return false }
            remote.press(.select)
            return app.keyboards.firstMatch.waitForNonExistence(timeout: 5)
                && app.buttons["dulcet.playlist.name.confirm"].firstMatch.isEnabled
        }
        let confirm = app.descendants(matching: .any)["dulcet.playlist.name.confirm"].firstMatch
        if confirm.exists, confirm.isEnabled, pressUntilFocused(app, confirm, .down, limit: 4) {
            remote.press(.select)
            _ = app.descendants(matching: .any)["dulcet.addToPlaylist.new"].firstMatch.waitForNonExistence(timeout: 15)
        }
        // Dismiss a failed chooser before going back to Library's section bar.
        if app.textFields["dulcet.playlist.name"].firstMatch.exists { remote.press(.menu) }
        if app.descendants(matching: .any)["dulcet.addToPlaylist.new"].firstMatch.exists { remote.press(.menu) }
        if app.staticTexts["dulcet.album.title"].firstMatch.exists { remote.press(.menu) }
        step("playlists-populated", app) {
            self.selectLibrarySection(app, "playlists")
                && app.buttons.matching(identifier: "dulcet.library.playlist")
                    .matching(NSPredicate(format: "label BEGINSWITH %@", String(name))).firstMatch.waitForExistence(timeout: 30)
                && self.settle(1)
        }
        let deleteMenuItem = app.descendants(matching: .any)["dulcet.playlist.delete"].firstMatch
        step("playlist-menu", app) {
            let row = app.buttons.matching(identifier: "dulcet.library.playlist")
                .matching(NSPredicate(format: "label BEGINSWITH %@", String(name))).firstMatch
            guard row.waitForExistence(timeout: 15), self.focusByRemote(row, in: app) else { return false }
            remote.press(.select, forDuration: 1.5)
            return deleteMenuItem.waitForExistence(timeout: 10) && self.settle(1)
        }
        let deleteQuestion = app.alerts["Delete this playlist? It is deleted from your server too."]
        step("playlist-delete-confirmation", app) {
            guard deleteMenuItem.exists,
                  self.pressUntilFocused(app, deleteMenuItem, .down, limit: 12) else { return false }
            remote.press(.select)
            return deleteQuestion.waitForExistence(timeout: 15)
                && deleteQuestion.buttons.matching(NSPredicate(format: "label == %@", "Cancel")).firstMatch.exists
                && self.settle(1)
        }
        // The photographed destructive confirmation is always cancelled, never confirmed.
        if deleteQuestion.exists {
            let cancel = deleteQuestion.buttons.matching(NSPredicate(format: "label == %@", "Cancel")).firstMatch
            if pressUntilFocused(app, cancel, .left, limit: 4) {
                remote.press(.select)
                if !deleteQuestion.waitForNonExistence(timeout: 15) {
                    XCTFail("Cancel must dismiss the playlist deletion confirmation")
                }
            } else {
                // Menu is the system dismissal if Cancel could not take remote focus.
                remote.press(.menu)
                XCTFail("The playlist deletion confirmation's Cancel could not take remote focus")
            }
        } else if deleteMenuItem.exists {
            remote.press(.menu)
        }
        step("playlist", app) {
            let row = app.buttons.matching(identifier: "dulcet.library.playlist")
                .matching(NSPredicate(format: "label BEGINSWITH %@", String(name))).firstMatch
            return self.pressByRemote(row, in: app, name: "Playlist")
                && app.staticTexts["dulcet.playlist.title"].firstMatch.waitForExistence(timeout: 20)
                && app.buttons.matching(identifier: "dulcet.reader.track").firstMatch.waitForExistence(timeout: 30)
                && self.settle(1)
        }
        if app.staticTexts["dulcet.playlist.title"].firstMatch.exists { remote.press(.menu) }
        // The user's heart on an album populates the Favourites place. This is a real app write
        // to this tour's disposable server, rather than an injected render state.
        step("favourites-populated", app) {
            guard self.selectLibrarySection(app, "albums") else { return false }
            let tile = app.buttons.matching(identifier: "dulcet.library.album").firstMatch
            guard self.focusAlbumTileByRemote(tile, in: app) else { return false }
            remote.press(.select)
            guard app.staticTexts["dulcet.album.title"].firstMatch.waitForExistence(timeout: 20) else { return false }
            let heart = app.buttons["dulcet.album.favorite"].firstMatch
            guard heart.waitForExistence(timeout: 10) else { return false }
            if heart.label != "Remove Favorite" {
                guard self.pressByRemote(heart, in: app, name: "Favourite album") else { return false }
            }
            _ = self.settle(2)
            remote.press(.menu)
            return self.selectLibrarySection(app, "favourites")
                && app.buttons.matching(identifier: "dulcet.library.album").firstMatch.waitForExistence(timeout: 30)
                && self.settle(2)
        }
    }

    /// Get into the grid before walking horizontally: horizontal presses on a focused sort
    /// segment change sorting, and horizontal presses on the section bar move to another place.
    @MainActor
    private func focusAlbumTileByRemote(_ tile: XCUIElement, in app: XCUIApplication) -> Bool {
        let sort = app.segmentedControls["dulcet.reader.albums.sort"].firstMatch
        let tiles = app.buttons.matching(identifier: "dulcet.library.album")
        guard tiles.firstMatch.waitForExistence(timeout: 30),
              press(.down, until: { self.focused(in: tiles) != nil || self.focused(in: sort.buttons) != nil }, bound: 4) else { return false }
        if focused(in: sort.buttons) != nil {
            let title = sort.buttons["Title"].firstMatch
            guard press(.left, until: { title.hasFocus }, bound: 7) else { return false }
            _ = settle(1)
            guard press(.down, until: { self.focused(in: tiles) != nil }, bound: 4) else { return false }
        }
        _ = settle(0.5)
        return tile.waitForExistence(timeout: 15) && focusByRemote(tile, in: app, bound: 24)
    }

    /// Walk the real remote focus graph to a Home header. Header buttons sit at the far right;
    /// a lone card cannot move Right, and repeatedly walking only Up/Down skips those buttons.
    @MainActor
    private func focusHomeAllByRemote(_ target: XCUIElement, in app: XCUIApplication) -> Bool {
        var traversed: [String: [String: Int]] = [:]
        let shelves = ["recentlyAdded", "recentlyPlayed", "mostPlayed", "favourites"]
        for index in 0..<160 {
            if target.hasFocus { return true }
            let held = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            guard held.exists else {
                XCUIRemote.shared.press(.down)
                continue
            }
            // Frame coordinates change as Home scrolls. Use the owning shelf plus the named
            // control/card as the graph node, so scrolling cannot hide a repeated dead end.
            let owner = shelves.first { row in
                let shelf = app.descendants(matching: .any)["dulcet.reader.home.\(row)"].firstMatch
                return shelf.exists && shelf.descendants(matching: .any)
                    .matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists
            } ?? "section-bar"
            let key = owner + "|" + held.identifier + "|" + held.label
            let from = held.frame, to = target.frame
            let dx = to.midX - from.midX, dy = to.midY - from.midY
            let horizontal: (String, XCUIRemote.Button) = dx >= 0 ? ("right", .right) : ("left", .left)
            let vertical: (String, XCUIRemote.Button) = dy >= 0 ? ("down", .down) : ("up", .up)
            let oppositeHorizontal: (String, XCUIRemote.Button) = dx >= 0 ? ("left", .left) : ("right", .right)
            let oppositeVertical: (String, XCUIRemote.Button) = dy >= 0 ? ("up", .up) : ("down", .down)
            let horizontalGap = to.maxX < from.minX || to.minX > from.maxX ? abs(dx) : 0
            let verticalGap = to.maxY < from.minY || to.minY > from.maxY ? abs(dy) : 0
            let toward = horizontalGap > verticalGap
                ? [horizontal, vertical, oppositeVertical, oppositeHorizontal]
                : [vertical, horizontal, oppositeHorizontal, oppositeVertical]
            let counts = traversed[key] ?? [:]
            // Prefer an untried edge from this actual node; geometric greed alone cycled on the
            // one-card shelves. If all four were explored, take the least traversed edge.
            let direction = toward.enumerated().min { lhs, rhs in
                let a = counts[lhs.element.0] ?? 0, b = counts[rhs.element.0] ?? 0
                return a == b ? lhs.offset < rhs.offset : a < b
            }!.element
            traversed[key, default: [:]][direction.0, default: 0] += 1
            print("DULCET TV HOME GRAPH step=\(index) target=\(target.identifier) node=\(key) direction=\(direction.0)")
            XCUIRemote.shared.press(direction.1)
            _ = settle(0.15)
        }
        return target.hasFocus
    }

    /// Restore Library's section bar before traversing it. The app's Library tab restores its
    /// pushed page; choosing that tab alone cannot expose the section controls underneath it.
    @MainActor
    private func selectLibrarySection(_ app: XCUIApplication, _ section: String) -> Bool {
        let order = ["home", "albums", "artists", "genres", "playlists", "favourites"]
        if !app.alerts.firstMatch.exists, !app.keyboards.firstMatch.exists,
           app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists {
            XCUIRemote.shared.press(.menu)
            _ = settle(0.5)
        }
        guard let wanted = order.firstIndex(of: section) else { return false }
        if !app.buttons["dulcet.tab.library"].firstMatch.isSelected {
            guard selectSection(app, "library") else { return false }
        }
        let home = app.buttons["dulcet.reader.section.home"].firstMatch
        // An album, artist, genre or See All hides the section bar. Menu pops the stack only
        // from content focus; from the app bar Menu has the system's leave-app meaning.
        for _ in 0..<6 where !home.exists {
            if focusedSection(app) != nil {
                guard press(.down, until: { self.focusedSection(app) == nil }, bound: 6) else { return false }
            }
            XCUIRemote.shared.press(.menu)
            _ = waitFor(3) { home.exists }
            guard app.state == .runningForeground else { return false }
        }
        guard home.waitForExistence(timeout: 10) else { return false }
        func libraryFocus() -> Int? {
            order.firstIndex { name in
                let item = app.buttons["dulcet.reader.section.\(name)"].firstMatch
                return item.exists && item.hasFocus
            }
        }
        // Up from a root list reaches its own bar; Down from the app bar reaches it. Once on
        // it, the ordered Left/Right walk is the same one the TV library control proof uses.
        for _ in 0..<8 where libraryFocus() == nil {
            XCUIRemote.shared.press(focusedSection(app) != nil ? .down : .up)
            _ = settle(0.2)
        }
        guard libraryFocus() != nil else { return false }
        for _ in 0...order.count {
            guard let current = libraryFocus() else { return false }
            if current == wanted { break }
            XCUIRemote.shared.press(current > wanted ? .left : .right)
            _ = settle(0.2)
        }
        let control = app.buttons["dulcet.reader.section.\(section)"].firstMatch
        guard control.hasFocus else { return false }
        XCUIRemote.shared.press(.select)
        let title: String
        switch section {
        case "albums": title = "Albums"
        case "artists": title = "Artists"
        case "genres": title = "Genres"
        case "playlists": title = "Playlists"
        case "favourites": title = "Favorites"
        default: title = "Library"
        }
        return app.navigationBars[title].firstMatch.waitForExistence(timeout: 20)
    }

    // MARK: - Account states, reached through the shipping form

    @MainActor
    private func tourAccountStates(_ app: XCUIApplication, server: Server) {
        guard let refused = TVTourLoopbackPort(listening: false),
              let silent = TVTourLoopbackPort(listening: true) else {
            XCTFail("The tour needs two disposable loopback ports")
            return
        }
        defer { refused.release(); silent.release() }
        app.launchArguments = []
        app.launch()
        if launchSection(app) == "Library" {
            guard connectByLaunchHook(app, server: server), signOutByRemote(app) else { return }
            app.terminate()
            app.launchArguments = []
            app.launch()
        }
        let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        let retry = app.buttons["Try Again"].firstMatch
        let address = app.textFields["dulcet.account-connect.server-address"].firstMatch
        let username = app.textFields["dulcet.account-connect.username"].firstMatch
        let password = app.secureTextFields["dulcet.account-connect.password"].firstMatch
        step("connection-empty", app) {
            primary.waitForExistence(timeout: 20) && !primary.isEnabled && !app.buttons["Sign Out"].exists
        }
        step("first-run-library", app) {
            self.selectSection(app, "library")
                && app.staticTexts["Your music, wherever you listen"].firstMatch.waitForExistence(timeout: 15)
        }
        step("first-run-search", app) {
            self.selectSection(app, "search")
                && app.textFields["dulcet.search.field"].firstMatch.waitForExistence(timeout: 15)
                && self.settle(1)
        }
        step("now-playing-empty", app) {
            self.selectSection(app, "nowPlaying") && self.settle(1)
                && app.staticTexts["Nothing is playing"].firstMatch.waitForExistence(timeout: 15)
        }
        _ = selectSection(app, "settings")
        step("connection-filled", app) {
            self.typeByRemote(server.url, into: address, in: app)
                && self.typeByRemote(server.username, into: username, in: app)
                && self.typeByRemote(server.password, into: password, in: app, secure: true)
                && primary.isEnabled && self.settle(1)
        }
        step("connection-error-input", app) {
            self.typeByRemote("ftp://127.0.0.1:\(refused.port)", into: address, in: app)
                && self.pressByRemote(primary, in: app, name: "Connect")
                && self.element(labelPrefix: "Check the server address", in: app).waitForExistence(timeout: 15)
        }
        step("connection-error-security", app) {
            self.setLocalHTTPByRemote(app, on: false)
                && self.typeByRemote(server.url, into: address, in: app)
                && self.pressByRemote(retry, in: app, name: "Try Again")
                && self.element(labelPrefix: "Local HTTP is not allowed", in: app).waitForExistence(timeout: 15)
        }
        step("connection-error-transport", app) {
            self.setLocalHTTPByRemote(app, on: true)
                && self.typeByRemote("http://127.0.0.1:\(refused.port)", into: address, in: app)
                && self.pressByRemote(retry, in: app, name: "Try Again")
                && self.element(labelPrefix: "The server could not be reached", in: app).waitForExistence(timeout: 45)
        }
        step("connection-error-authentication", app) {
            self.typeByRemote(server.url, into: address, in: app)
                && self.typeByRemote(server.password + "-wrong", into: password, in: app, secure: true)
                && self.pressByRemote(retry, in: app, name: "Try Again")
                && self.element(labelPrefix: "The username or password was not accepted", in: app).waitForExistence(timeout: 45)
        }
        step("connection-error-protocol", app) {
            self.typeByRemote(server.url + "/app", into: address, in: app)
                && self.typeByRemote(server.password, into: password, in: app, secure: true)
                && self.pressByRemote(retry, in: app, name: "Try Again")
                && self.element(labelPrefix: "This is not an OpenSubsonic endpoint", in: app).waitForExistence(timeout: 45)
        }
        step("connection-error-server", app) {
            guard let proxy = self.faultProxy(),
                  proxy.control("POST", "endpoint=ping&code=0&state=on") != nil else { return false }
            defer { _ = proxy.control("POST", "state=off") }
            return self.typeByRemote(proxy.url, into: address, in: app)
                && self.pressByRemote(retry, in: app, name: "Try Again")
                && self.element(labelPrefix: "The server rejected account setup", in: app).waitForExistence(timeout: 45)
        }
        step("connection-error-tls", app) {
            guard let tlsURL = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_BAD_TLS_URL"],
                  let parts = URLComponents(string: tlsURL), parts.scheme == "https",
                  parts.host == "127.0.0.1", parts.port != nil else { return false }
            return self.typeByRemote(tlsURL, into: address, in: app)
                && self.pressByRemote(retry, in: app, name: "Try Again")
                && self.element(labelPrefix: "This server’s certificate isn’t trusted", in: app).waitForExistence(timeout: 45)
        }
        step("connection-connecting", app) {
            self.typeByRemote("http://127.0.0.1:\(silent.port)", into: address, in: app)
                && self.pressByRemote(retry, in: app, name: "Try Again")
                && self.element(labelPrefix: "Connecting to the server", in: app).waitForExistence(timeout: 10)
                && self.waitForLabel(containing: "Cancel", of: primary, timeout: 5)
        }
        _ = settle(1)
        _ = pressByRemote(primary, in: app, name: "Cancel")
        step("connection-connected", app) {
            self.connectByLaunchHook(app, server: server)
                && self.element(labelPrefix: "Connected to", in: app).waitForExistence(timeout: 15)
                && self.settle(1)
        }
        step("connection-saved-disconnected", app) {
            app.terminate()
            app.launchArguments = []
            app.launch()
            return self.launchSection(app) == "Library" && self.selectSection(app, "settings")
                && self.element(labelPrefix: "Reconnect to", in: app).waitForExistence(timeout: 15)
        }
        step("connection-error-persistence", app) {
            app.terminate()
            app.launchArguments = ["-com.legitimateapps.dulcet.active-account-id", UUID().uuidString]
            app.launch()
            if !self.element(labelPrefix: "Your saved account could not be opened", in: app).waitForExistence(timeout: 10) {
                _ = self.selectSection(app, "settings")
            }
            return self.element(labelPrefix: "Your saved account could not be opened", in: app).waitForExistence(timeout: 15)
        }
    }

    /// Launches with the account handed to the form by the DEBUG hook, which submits it through
    /// Connect's own path, waits for the library it lands on, and opens Connection by remote.
    @MainActor
    private func connectByLaunchHook(_ app: XCUIApplication, server: Server) -> Bool {
        app.terminate()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", server.url,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        app.launch()
        let deadline = ContinuousClock.now.advanced(by: .seconds(60))
        while app.navigationBars.firstMatch.identifier != "Library", ContinuousClock.now < deadline {
            Thread.sleep(forTimeInterval: 0.25)
        }
        guard app.navigationBars.firstMatch.identifier == "Library" else {
            XCTFail("A connection made at launch must land on Library: " + app.debugDescription)
            return false
        }
        guard selectSection(app, "settings") else {
            XCTFail("The section bar must reach Connection from Library: " + app.debugDescription)
            return false
        }
        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 60) else {
            XCTFail("The connection must succeed and Connection must offer Sign Out: " + app.debugDescription)
            return false
        }
        return true
    }

    /// Signs the connected account out by remote, through its confirmation, and waits for the
    /// empty form.
    @MainActor
    private func signOutByRemote(_ app: XCUIApplication) -> Bool {
        guard pressByRemote(app.buttons["Sign Out"].firstMatch, in: app, name: "Sign Out") else { return false }
        // The question is a `confirmationDialog`, which tvOS presents as a sheet, not an alert.
        let question = app.sheets["Sign out of this server?"]
        guard question.waitForExistence(timeout: 15) else {
            XCTFail("Sign Out must ask first: " + app.debugDescription)
            return false
        }
        // tvOS draws each action as a button inside a button of the same label, Cancel first.
        let confirm = question.buttons.matching(NSPredicate(format: "label == %@", "Sign Out")).firstMatch
        guard confirm.exists, pressUntilFocused(app, confirm, .right, limit: 4) else {
            XCTFail("The question's Sign Out must take remote focus: " + question.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        guard question.waitForNonExistence(timeout: 15), primary.waitForExistence(timeout: 45),
              waitForLabel(containing: "Connect", of: primary, timeout: 30), !primary.label.contains("Reconnect") else {
            XCTFail("Signing out must return Connection to its empty form: " + app.debugDescription)
            return false
        }
        return true
    }

    /// Cleartext to a loopback server needs the local-HTTP consent, which keeps its value across
    /// edits; set by remote only when it differs.
    @MainActor
    private func setLocalHTTPByRemote(_ app: XCUIApplication, on: Bool) -> Bool {
        let wanted = on ? "1" : "0"
        let consent = app.descendants(matching: .any).matching(NSPredicate(
            format: "label == %@ AND (value == %@ OR value == %@)", "Allow HTTP on this local network", "0", "1"
        )).firstMatch
        guard consent.waitForExistence(timeout: 10) else {
            XCTFail("The local-HTTP consent control must exist: " + app.debugDescription)
            return false
        }
        if (consent.value as? String) == wanted { return true }
        guard focusByRemote(consent, in: app) else {
            XCTFail("The local-HTTP consent must take remote focus: " + app.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        guard waitForValue(wanted, of: consent, timeout: 5) else {
            XCTFail("Select must turn the local-HTTP consent \(on ? "on" : "off"): " + app.debugDescription)
            return false
        }
        return true
    }

    /// Focuses `field` by remote, opens the system keyboard with Select, replaces what the field
    /// holds with `text`, and leaves the keyboard through the field's Next, by remote.
    @MainActor
    private func typeByRemote(_ text: String, into field: XCUIElement, in app: XCUIApplication, secure: Bool = false) -> Bool {
        // Synthesized typing into the system keyboard on a loaded host can drop keystrokes, so each
        // attempt is verified and a short field is retyped, never accepted.
        var observed = ""
        for attempt in 1...3 {
            guard typeByRemoteOnce(text, into: field, in: app) else { return false }
            let settle = ContinuousClock.now.advanced(by: .seconds(10))
            func matches() -> Bool {
                observed = field.value as? String ?? ""
                return secure ? observed.count == text.count : observed == text
            }
            while !matches(), ContinuousClock.now < settle {
                Thread.sleep(forTimeInterval: 0.1)
            }
            if matches() { return true }
            print("DULCET TV TOUR ACCOUNT RETYPE target=\(field.identifier) attempt=\(attempt) observed-length=\(observed.count) wanted-length=\(text.count)")
        }
        XCTFail("\(field.identifier) must hold the \(text.count) characters typed, observed \(observed.count)")
        return false
    }

    /// One pass: focus the field by remote, open its keyboard, clear what it holds, type, and
    /// close the keyboard through the field's Next.
    @MainActor
    private func typeByRemoteOnce(_ text: String, into field: XCUIElement, in app: XCUIApplication) -> Bool {
        guard field.waitForExistence(timeout: 10), focusByRemote(field, in: app) else {
            XCTFail("\(field.identifier) must take remote focus: " + app.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        guard app.keyboards.firstMatch.waitForExistence(timeout: 10) else {
            XCTFail("Select on \(field.identifier) must open the keyboard: " + app.debugDescription)
            return false
        }
        // A secure field reports bullets, one per character; clear by that count either way.
        let existing = (field.value as? String).flatMap { $0 == field.placeholderValue ? nil : $0 } ?? ""
        if !existing.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
        }
        field.typeText(text)
        // Every account field sets `.submitLabel(.next)`, so the keyboard's return button reads "next".
        // Selecting it submits the field; the address and username fields then move focus to the
        // following field, whose keyboard opens in turn, so Menu closes that one untouched.
        let submit = app.buttons.matching(NSPredicate(format: "label ==[c] %@", "next")).firstMatch
        guard submit.waitForExistence(timeout: 5) else {
            XCTFail("The keyboard must offer the field's Next: " + app.debugDescription)
            return false
        }
        for _ in 0..<6 where !submit.hasFocus {
            XCUIRemote.shared.press(.down)
        }
        guard submit.hasFocus else {
            XCTFail("Keyboard Next must take remote focus: " + app.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        let following = app.descendants(matching: .any).matching(NSPredicate(
            format: "hasKeyboardFocus == true AND identifier BEGINSWITH %@ AND identifier != %@",
            "dulcet.account-connect.", field.identifier
        )).firstMatch
        if !app.keyboards.firstMatch.waitForNonExistence(timeout: 3), following.exists, app.keyboards.firstMatch.exists {
            print("DULCET TV TOUR ACCOUNT KEYBOARD chained-to=\(following.identifier) action=menu")
            XCUIRemote.shared.press(.menu)
        }
        guard app.keyboards.firstMatch.waitForNonExistence(timeout: 10) else {
            XCTFail("Next, then Menu on the following field's keyboard, must close the keyboard: " + app.debugDescription)
            return false
        }
        return true
    }

    /// Focuses `control` by remote and presses Select on it.
    @MainActor
    private func pressByRemote(_ control: XCUIElement, in app: XCUIApplication, name: String) -> Bool {
        guard control.waitForExistence(timeout: 10) else {
            XCTFail("\(name) must be on screen: " + app.debugDescription)
            return false
        }
        guard control.isEnabled else {
            XCTFail("\(name) must be enabled: " + app.debugDescription)
            return false
        }
        guard focusByRemote(control, in: app) else {
            XCTFail("\(name) must take remote focus: " + app.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        return true
    }

    /// Walks remote focus to `target` by direction presses toward its frame, at most `bound`.
    @MainActor
    private func focusByRemote(_ target: XCUIElement, in app: XCUIApplication, bound: Int = 16) -> Bool {
        func focusedFrame() -> CGRect? {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            return focused.exists ? focused.frame : nil
        }
        var presses = 0
        var visited: [CGRect] = []
        var swapAxes = false
        while !(target.exists && target.hasFocus), presses < bound {
            guard let from = focusedFrame() else {
                XCUIRemote.shared.press(.down)
                presses += 1
                continue
            }
            visited.append(from)
            let to = target.frame
            let dy = to.midY - from.midY, dx = to.midX - from.midX
            let vertical: XCUIRemote.Button = dy > 0 ? .down : .up
            let horizontal: XCUIRemote.Button = dx > 0 ? .right : .left
            let verticalGap = to.maxY < from.minY || to.minY > from.maxY ? abs(dy) : 0
            let horizontalGap = to.maxX < from.minX || to.minX > from.maxX ? abs(dx) : 0
            let leadsVertically = verticalGap == horizontalGap ? abs(dy) >= abs(dx) : verticalGap > horizontalGap
            var order = leadsVertically ? [vertical, horizontal] : [horizontal, vertical]
            if swapAxes { order.reverse() }
            swapAxes = false
            for direction in order {
                XCUIRemote.shared.press(direction)
                presses += 1
                guard let now = focusedFrame() else { break }
                if now == from { continue }
                swapAxes = visited.contains(now)
                break
            }
        }
        print("DULCET TV TOUR ACCOUNT FOCUS target=\(target.identifier.isEmpty ? target.label : target.identifier)"
              + " presses=\(presses) reached=\(target.hasFocus)")
        return target.exists && target.hasFocus
    }

    /// System menu rows take focus on their enclosing cell rather than the named child.
    @MainActor
    private func isFocused(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        guard let target = try? element.snapshot() else { return false }
        if target.hasFocus { return true }
        guard let cell = try? app.cells.matching(NSPredicate(format: "hasFocus == true")).firstMatch.snapshot() else { return false }
        return cell.frame.contains(CGPoint(x: target.frame.midX, y: target.frame.midY))
    }

    /// Presses `button` until `element` holds focus, at most `limit` times.
    @MainActor
    private func pressUntilFocused(_ app: XCUIApplication, _ element: XCUIElement, _ button: XCUIRemote.Button, limit: Int) -> Bool {
        func settles() -> Bool {
            let deadline = ContinuousClock.now.advanced(by: .milliseconds(1500))
            repeat {
                if self.isFocused(app, element) { return true }
                Thread.sleep(forTimeInterval: 0.2)
            } while ContinuousClock.now < deadline
            return false
        }
        if settles() { return true }
        for _ in 0..<limit {
            XCUIRemote.shared.press(button)
            if settles() { return true }
        }
        return false
    }

    // MARK: - Queries

    private typealias Server = (url: String, username: String, password: String)

    /// Populate only the disposable server's play history, never a destination in the app.
    /// The singleton Home failure remains evidence; this photographs the populated state too.
    private func seedMostPlayedFixture(server: Server) -> Bool {
        guard let list = fixtureREST("getAlbumList2", [
            URLQueryItem(name: "type", value: "newest"), URLQueryItem(name: "size", value: "100")
        ], server: server)?["albumList2"] as? [String: Any],
              let albums = list["album"] as? [[String: Any]], albums.count > 1 else { return false }
        for album in albums {
            guard let id = album["id"] as? String,
                  let detail = fixtureREST("getAlbum", [URLQueryItem(name: "id", value: id)], server: server)?["album"] as? [String: Any],
                  let songs = detail["song"] as? [[String: Any]], let songID = songs.first?["id"] as? String,
                  fixtureREST("scrobble", [
                    URLQueryItem(name: "id", value: songID),
                    URLQueryItem(name: "submission", value: "true"),
                    URLQueryItem(name: "time", value: String(Int64(Date().timeIntervalSince1970 * 1000)))
                  ], server: server) != nil else { return false }
        }
        let frequent = fixtureREST("getAlbumList2", [
            URLQueryItem(name: "type", value: "frequent"), URLQueryItem(name: "size", value: "100")
        ], server: server)?["albumList2"] as? [String: Any]
        let count = (frequent?["album"] as? [[String: Any]])?.count ?? 0
        print("DULCET TV TOUR FIXTURE most-played albums=\(count) seeded=\(albums.count)")
        return count >= albums.count
    }

    /// Authenticated REST setup for the opt-in tour's explicitly disposable loopback fixture.
    private func fixtureREST(_ endpoint: String, _ query: [URLQueryItem], server: Server) -> [String: Any]? {
        guard ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1",
              var components = URLComponents(string: server.url), components.scheme == "http",
              components.host == "127.0.0.1", components.port != nil else { return nil }
        let salt = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let token = Insecure.MD5.hash(data: Data((server.password + salt).utf8))
            .map { String(format: "%02x", $0) }.joined()
        let base = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = base + "/rest/" + endpoint
        components.queryItems = [
            URLQueryItem(name: "u", value: server.username), URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt), URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-design-tour"), URLQueryItem(name: "f", value: "json")
        ] + query
        guard let target = components.url else { return nil }
        final class Outcome: @unchecked Sendable { var data: Data? }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: target) { data, response, error in
            if error == nil, (response as? HTTPURLResponse)?.statusCode == 200 { outcome.data = data }
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 15) == .success else {
            task.cancel()
            print("DULCET TV TOUR FIXTURE \(endpoint) timed out")
            return nil
        }
        guard let data = outcome.data,
              let document = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any],
              envelope["status"] as? String == "ok" else {
            print("DULCET TV TOUR FIXTURE \(endpoint) did not return an ok envelope")
            return nil
        }
        return envelope
    }

    private struct FaultProxy {
        let url: String

        /// One request to the proxy's server-error control, answered synchronously: its JSON, or nil.
        func control(_ method: String, _ query: String) -> [String: Any]? {
            guard let target = URL(string: url + "/__dulcet/subsonic-error" + (query.isEmpty ? "" : "?" + query)) else {
                return nil
            }
            var request = URLRequest(url: target)
            request.httpMethod = method
            final class Outcome: @unchecked Sendable { var status = -1; var data: Data?; var error: String? }
            let outcome = Outcome()
            let done = DispatchSemaphore(value: 0)
            let task = URLSession.shared.dataTask(with: request) { data, response, error in
                outcome.status = (response as? HTTPURLResponse)?.statusCode ?? -1
                outcome.data = data
                outcome.error = error.map { String(describing: $0) }
                done.signal()
            }
            task.resume()
            guard done.wait(timeout: .now() + 20) == .success else {
                task.cancel()
                print("DULCET TV TOUR ACCOUNT FAULT PROXY \(method) \(query) timed out")
                return nil
            }
            guard outcome.status == 200, let data = outcome.data,
                  let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                print("DULCET TV TOUR ACCOUNT FAULT PROXY \(method) \(query) status=\(outcome.status) error=\(outcome.error ?? "none")")
                return nil
            }
            return body
        }
    }

    /// The fault proxy in front of the disposable server; like the server, loopback only.
    private func faultProxy() -> FaultProxy? {
        let url = ProcessInfo.processInfo.environment["DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL"] ?? ""
        guard let components = URLComponents(string: url), components.scheme == "http",
              components.host == "127.0.0.1", components.port != nil else {
            XCTFail("DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL must name the loopback fault proxy; got \(url.debugDescription)")
            return nil
        }
        return FaultProxy(url: url)
    }

    @MainActor
    private func element(labelPrefix prefix: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
    }

    @MainActor
    private func waitForLabel(containing text: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if element.exists, element.label.contains(text) { return true }
            Thread.sleep(forTimeInterval: 0.1)
        } while Date() < deadline
        return false
    }

    @MainActor
    private func waitForValue(_ value: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if element.exists, (element.value as? String) == value { return true }
            Thread.sleep(forTimeInterval: 0.1)
        } while Date() < deadline
        return false
    }
}

/// A loopback TCP port chosen by the system. Not listening, the port is given back at once, so a
/// connection to it is refused (a socket left bound but not listening is not refused here: OBSERVED,
/// the connection waited out the 30-second request limit instead). Listening, the port is held for
/// the length of the test, and a connection completes into the backlog and is never answered, so
/// the client waits until it gives up or is cancelled.
private final class TVTourLoopbackPort {
    let port: UInt16
    private var descriptor: Int32

    init?(listening: Bool) {
        let socketDescriptor = socket(AF_INET, SOCK_STREAM, 0)
        guard socketDescriptor >= 0 else { return nil }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socketDescriptor, $0, length) }
        }
        guard bound == 0, !listening || listen(socketDescriptor, 16) == 0 else {
            close(socketDescriptor)
            return nil
        }
        var assigned = sockaddr_in()
        var assignedLength = length
        let named = withUnsafeMutablePointer(to: &assigned) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socketDescriptor, $0, &assignedLength) }
        }
        guard named == 0 else {
            close(socketDescriptor)
            return nil
        }
        port = UInt16(bigEndian: assigned.sin_port)
        if listening {
            descriptor = socketDescriptor
        } else {
            close(socketDescriptor)
            descriptor = -1
        }
    }

    /// Accepts, and closes, every connection waiting in the backlog; answers how many there were.
    func drainAcceptedConnections() -> Int {
        guard descriptor >= 0 else { return 0 }
        _ = fcntl(descriptor, F_SETFL, fcntl(descriptor, F_GETFL) | O_NONBLOCK)
        var count = 0
        while true {
            let connection = accept(descriptor, nil, nil)
            guard connection >= 0 else { break }
            close(connection)
            count += 1
        }
        return count
    }

    func release() {
        guard descriptor >= 0 else { return }
        close(descriptor)
        descriptor = -1
    }

    deinit { release() }
}
