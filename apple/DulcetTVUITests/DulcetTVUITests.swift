import XCTest

final class DulcetTVUITests: XCTestCase {
    /// Account and initial destination are DEBUG setup. Query entry, ranked rendering,
    /// remote activation, and playback observations all use the production tvOS UI.
    /// This does not prove ordinary navigation to Search: the tvOS root has no such control.
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
            "Thirty One Seconds, Dulcet Fixtures \u{00B7} Threshold Boundary, Track",
            "Twenty Nine Seconds, Dulcet Fixtures \u{00B7} Threshold Boundary, Track",
            "UI Playback Canary, Dulcet Fixtures \u{00B7} Threshold Boundary, Track",
            "Threshold Boundary, Dulcet Fixtures, Album",
        ]
        let app = XCUIApplication()

        // Record the ordinary root independently of the destination hook. This is diagnostic
        // evidence, not an assertion that freezes the current lack of Search navigation.
        app.launch()
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 10))
        print("DULCET TV UNSEEDED ROOT " + app.debugDescription)
        app.terminate()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", serverURL,
            "-dulcet-debug-account-username", username,
            "-dulcet-debug-account-password", password,
            "-dulcet-debug-open-search",
        ]
        app.launch()
        let field = app.textFields["dulcet.search.field"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 40), "Live account setup must reach Search: " + app.debugDescription)
        XCTAssertTrue(field.hasFocus, "Search field must have remote focus")
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
        var rankedResults: [XCUIElement] = []
        for rank in rankedLabels.indices {
            let result = app.buttons["dulcet.search.result.\(rank)"].firstMatch
            XCTAssertTrue(
                result.waitForExistence(timeout: 30),
                "Rank \(rank) must render its own identifier: " + app.debugDescription
            )
            XCTAssertEqual(result.label, rankedLabels[rank], "Rank \(rank) rendered accessibility text")
            rankedResults.append(result)
        }
        XCTAssertNotEqual(
            rankedResults[0].label,
            rankedLabels[canaryRank],
            "The canary must not render at rank zero, or this control cannot tell rank from arity"
        )
        XCTAssertFalse(
            app.buttons["dulcet.search.result.\(rankedLabels.count)"].firstMatch.exists,
            "The ranked list must end at rank \(rankedLabels.count - 1): " + app.debugDescription
        )
        print("DULCET TV RANKS labels=\(rankedResults.map(\.label))")

        // Focus walks down through the ranks the canary is not at, so reaching it is itself
        // evidence that the rows are distinct and ordered as asserted.
        let canaryResult = rankedResults[canaryRank]
        for _ in 0..<16 {
            if canaryResult.hasFocus { break }
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(
            canaryResult.hasFocus,
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
        print("DULCET TV SEARCH PASS query=typed ranks=\(rankedResults.map(\.label))"
            + " activated-rank=\(canaryRank) activation=remote-select source=search"
            + " title=\(title.label) progress=\(initialValue)->\(progress.value as? String ?? "missing")"
            + " setup=debug-account-and-destination")
    }
}
