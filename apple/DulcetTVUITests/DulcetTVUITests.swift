import XCTest

final class DulcetTVUITests: XCTestCase {
    /// Section order in the tvOS section bar, which is also the order the remote traverses.
    private static let sections = ["library", "search", "nowPlaying", "settings"]

    /// The account is DEBUG setup, because typing credentials raises a system save-password
    /// dialog no app-side query can reach. Everything else -- reaching Search, entering the
    /// query, ranked rendering, remote activation, playback, and the return to Library --
    /// runs through the production tvOS UI. Nothing selects a destination for this control:
    /// the section bar is the only route to Search, which is what makes the run evidence of
    /// reachability rather than evidence that the surface works once something else reaches it.
    @MainActor
    func testSimulatorSearchQueryRanksAndActivatesTrack() throws {
        continueAfterFailure = false
        XCTAssertNotNil(
            ProcessInfo.processInfo.environment["SIMULATOR_UDID"],
            "This control requires a tvOS simulator"
        )
        let environment = ProcessInfo.processInfo.environment
        let serverURL = try XCTUnwrap(environment["DULCET_UI_TEST_SERVER_URL"], "Missing disposable server URL")
        let username = try XCTUnwrap(environment["DULCET_UI_TEST_USERNAME"], "Missing disposable username")
        let password = try XCTUnwrap(environment["DULCET_UI_TEST_PASSWORD"], "Missing disposable password")
        XCTAssertEqual(serverURL, "http://127.0.0.1:4533", "Only the disposable loopback fixture is allowed")
        XCTAssertFalse(username.isEmpty)
        XCTAssertFalse(password.isEmpty)
        // A query matching exactly one row cannot separate rank from arity: with a single result,
        // "rank zero" and "the only row" are the same assertion, and so are "activate the row that
        // was focused" and "activate the first result". This query matches four rows and places
        // the canary at a non-zero rank, so both distinctions become observable.
        let query = "Threshold"
        let canaryTitle = "UI Playback Canary"
        let canaryRank = 2
        // The rendered order is a product contract, not a server one: results are ranked by match
        // quality first, then by kind with tracks ahead of albums, then by the order the server
        // returned them. The server lists the matching album ahead of every track; the app does
        // not. Asserting the whole order makes drift in either fail here, naming what it observed,
        // rather than silently relocating the canary to another rank.
        let rankedLabels = [
            "Thirty One Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "Twenty Nine Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track",
            "Threshold Boundary, Dulcet Fixtures, Album",
        ]
        let app = XCUIApplication()

        // The ordinary root, launched with no setup arguments at all: every section is already
        // offered here, so the bar is part of the shipped shell rather than something the seeded
        // launch below arranges. What this launch shows behind the bar depends on what an earlier
        // run left in the keychain, so nothing is asserted about it.
        app.launch()
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 10))
        for section in Self.sections {
            XCTAssertTrue(
                app.buttons["dulcet.tab.\(section)"].firstMatch.waitForExistence(timeout: 10),
                "The unseeded root must offer the \(section) section: " + app.debugDescription
            )
        }
        print("DULCET TV UNSEEDED ROOT " + app.debugDescription)
        app.terminate()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", serverURL,
            "-dulcet-debug-account-username", username,
            "-dulcet-debug-account-password", password,
        ]
        app.launch()

        // Connecting an account leaves the person on Connection, which is where a real first run
        // leaves them too. Search is somewhere else, and only the section bar goes there.
        XCTAssertTrue(
            app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 60),
            "The live account connection must succeed before navigation is attempted: " + app.debugDescription
        )
        XCTAssertEqual(app.navigationBars.firstMatch.identifier, "Connection")
        XCTAssertFalse(
            app.textFields["dulcet.search.field"].firstMatch.exists,
            "Search must not already be on screen, or reaching it proves nothing: " + app.debugDescription
        )
        // A defined initial focus: the remote starts on one of the section's own named controls,
        // inside the content rather than on the bar, so the first press does something the person
        // asked for. Which control is the section's business -- an idle form focuses its first
        // field, a saved account focuses Reconnect -- so this pins the section, not the row.
        XCTAssertNil(focusedSection(app), "Launch focus belongs in the section, not on the bar")
        let launchFocus = try XCTUnwrap(
            focusedControlIdentifier(app),
            "Launch must place remote focus on a named control: " + app.debugDescription
        )
        XCTAssertTrue(
            launchFocus.hasPrefix("dulcet.account-connect."),
            "Launch focus must be a Connection control, observed \(launchFocus): " + app.debugDescription
        )
        print("DULCET TV LAUNCH section=Connection focus=\(launchFocus) search-present=false")

        // Reach Search the way a person does.
        XCTAssertTrue(
            selectSection(app, "search"),
            "The section bar must reach Search from Connection: " + app.debugDescription
        )
        let field = app.textFields["dulcet.search.field"].firstMatch
        XCTAssertTrue(
            field.waitForExistence(timeout: 40),
            "Selecting Search in the section bar must present the search surface: " + app.debugDescription
        )
        // Down leaves the bar for the section's own content. Bounded, and re-checked after every
        // press, so a focus engine that never reaches the field fails here rather than typing
        // into whatever else happens to hold focus.
        for _ in 0..<4 {
            if field.hasFocus { break }
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(field.hasFocus, "Search field must have remote focus: " + app.debugDescription)
        print("DULCET TV REACHED-SEARCH via=section-bar control=dulcet.tab.search")
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        field.typeText(query)
        XCTAssertEqual(field.value as? String, query, "Typed text must reach the app's own field")
        print("DULCET TV QUERY value=\(field.value as? String ?? "missing") input=typeText")

        // Navigate the system keyboard by remote, including its Done control. Re-check focus
        // after every press rather than selecting a control at an assumed coordinate.
        let done = app.buttons["done"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        for _ in 0..<6 {
            if done.hasFocus { break }
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(done.hasFocus, "Keyboard Done must have remote focus: " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, query)

        // Every rank is addressed by its own identifier and checked against the row that belongs
        // there. A view that stamped one constant identifier on every row would satisfy rank zero
        // and then fail to produce rank one at all.
        var observedLabels: [String] = []
        for rank in rankedLabels.indices {
            let result = app.buttons["dulcet.search.result.\(rank)"].firstMatch
            if !result.waitForExistence(timeout: rank == 0 ? 30 : 5) {
                // The result list is lazy and the section bar takes screen height from it, so a
                // low rank is not built until the remote moves toward it. Bounded, and re-checked
                // after every press, so a rank that never renders still fails here.
                for _ in 0..<8 {
                    if result.exists { break }
                    XCUIRemote.shared.press(.down)
                }
            }
            XCTAssertTrue(
                result.exists,
                "Rank \(rank) must render its own identifier: " + app.debugDescription
            )
            // Read the label now. Reaching a low rank scrolls a high one out of the lazy list,
            // and re-reading an element that has left the hierarchy throws rather than returning
            // a stale value.
            let label = result.label
            XCTAssertEqual(label, rankedLabels[rank], "Rank \(rank) rendered accessibility text")
            observedLabels.append(label)
        }
        XCTAssertNotEqual(
            observedLabels[0],
            rankedLabels[canaryRank],
            "The canary must not render at rank zero, or this control cannot tell rank from arity"
        )
        XCTAssertFalse(
            app.buttons["dulcet.search.result.\(rankedLabels.count)"].firstMatch.exists,
            "The ranked list must end at rank \(rankedLabels.count - 1): " + app.debugDescription
        )
        print("DULCET TV RANKS labels=\(observedLabels)")

        // Reaching a low rank may have carried focus past the canary, so the walk chooses its
        // direction from the rank that actually holds focus. Pressing up blindly would leave the
        // list for the search field and then for the section bar.
        let canaryResult = app.buttons["dulcet.search.result.\(canaryRank)"].firstMatch
        for _ in 0..<16 {
            if canaryResult.exists, canaryResult.hasFocus { break }
            let focusedRank = rankedLabels.indices.first { rank in
                let row = app.buttons["dulcet.search.result.\(rank)"].firstMatch
                return row.exists && row.hasFocus
            }
            XCUIRemote.shared.press(focusedRank.map { $0 > canaryRank } == true ? .up : .down)
        }
        XCTAssertTrue(
            canaryResult.exists && canaryResult.hasFocus,
            "Rank \(canaryRank) must have remote focus: " + app.debugDescription
        )
        XCUIRemote.shared.press(.select)

        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 15), "Activating rank \(canaryRank) must present Now Playing")
        // Now Playing showing rank zero's track here would mean activation played the first
        // result rather than the row that was focused. That is why a non-zero rank is selected.
        XCTAssertEqual(title.label, canaryTitle, "Now Playing must show the focused row, not the first result")
        XCTAssertTrue(app.staticTexts["Playing from Search"].firstMatch.waitForExistence(timeout: 5))
        let progress = app.progressIndicators["Now Playing"].firstMatch
        XCTAssertTrue(progress.waitForExistence(timeout: 30), "Activated media must begin progressing")
        let initialValue = try XCTUnwrap(progress.value as? String)
        XCTAssertTrue(initialValue.hasSuffix(" of 0:31"), initialValue)
        let advances = NSPredicate { _, _ in
            guard progress.exists, let value = progress.value as? String else { return false }
            return value.hasSuffix(" of 0:31") && value != initialValue
        }
        XCTAssertEqual(
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: advances, object: nil)], timeout: 10),
            .completed,
            "Media time must advance after remote activation"
        )
        XCTAssertEqual(title.label, canaryTitle)
        // Read the playback receipts now: returning to Library replaces this surface, and
        // re-reading an element that has left the hierarchy throws instead of reporting it.
        let observedTitle = title.label
        let observedProgress = progress.value as? String ?? "missing"

        // Playback moved the app to Now Playing on its own. Leaving a section the app chose, and
        // deliberately returning to the library, is the other half of navigation: without it a
        // person who plays one track has no way back to what they were browsing.
        XCTAssertTrue(
            selectSection(app, "library"),
            "The section bar must return to Library from Now Playing: " + app.debugDescription
        )
        XCTAssertTrue(
            app.staticTexts["Albums"].firstMatch.waitForExistence(timeout: 30),
            "Returning to Library must present the library, not the section it came from: " + app.debugDescription
        )
        XCTAssertEqual(app.navigationBars.firstMatch.identifier, "Library")
        XCTAssertFalse(
            app.textFields["dulcet.search.field"].firstMatch.exists,
            "Library must replace the search surface: " + app.debugDescription
        )
        print("DULCET TV SEARCH PASS query=typed ranks=\(observedLabels)"
            + " activated-rank=\(canaryRank) activation=remote-select source=search"
            + " title=\(observedTitle) progress=\(initialValue)->\(observedProgress)"
            + " reached-search=section-bar returned-to=library setup=debug-account-only")
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

    /// The identifier of the control that currently holds remote focus inside a section.
    @MainActor
    private func focusedControlIdentifier(_ app: XCUIApplication) -> String? {
        for kind in [XCUIElement.ElementType.textField, .secureTextField, .button, .switch] {
            let query = app.descendants(matching: kind)
            for index in 0..<query.count {
                let element = query.element(boundBy: index)
                if element.hasFocus, !element.identifier.isEmpty { return element.identifier }
            }
        }
        return nil
    }

    /// Moves remote focus from a section's content back onto the section bar.
    ///
    /// Up first, because that is how a person leaves the top of a surface. Menu -- the platform's
    /// Back press -- is the fallback, and is pressed only while focus is still inside content:
    /// OBSERVED, Menu on the bar itself leaves the app, so pressing it blind would end the run
    /// somewhere no assertion could describe.
    @MainActor
    private func focusSectionBar(_ app: XCUIApplication) -> Bool {
        for _ in 0..<8 {
            if focusedSection(app) != nil { return true }
            XCUIRemote.shared.press(.up)
        }
        if focusedSection(app) != nil { return true }
        XCUIRemote.shared.press(.menu)
        return focusedSection(app) != nil
    }

    /// Reaches one section through the bar, by remote, the way a person does: focus the bar, walk
    /// to the section's own control, press it.
    @MainActor
    private func selectSection(_ app: XCUIApplication, _ section: String) -> Bool {
        guard let target = Self.sections.firstIndex(of: section), focusSectionBar(app) else { return false }
        for _ in 0...Self.sections.count {
            guard let current = focusedSection(app),
                  let index = Self.sections.firstIndex(of: current) else { return false }
            if index == target { break }
            XCUIRemote.shared.press(index > target ? .left : .right)
        }
        guard focusedSection(app) == section else { return false }
        XCUIRemote.shared.press(.select)
        return true
    }
}
