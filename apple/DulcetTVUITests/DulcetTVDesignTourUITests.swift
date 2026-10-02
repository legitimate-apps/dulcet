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
/// every later one. Nothing here writes to the server: the heart and the stars are focused for
/// their shots, never pressed.
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
        continueAfterFailure = true
        let remote = XCUIRemote.shared
        let app = XCUIApplication()
        app.launchArguments += [
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
        print("DULCET DESIGN TOUR done shots=\(shotNumber)")
    }

    // MARK: - Steps and shots

    /// Runs one screen: reach it, then photograph it whether or not it was reached.
    @MainActor
    private func step(_ screen: String, _ app: XCUIApplication, _ reach: () -> Bool) {
        let reached = reach()
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
        guard progress.exists, let value = progress.value as? String else { return false }
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
}
