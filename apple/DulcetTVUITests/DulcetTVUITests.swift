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
        let query = "UI Playback Canary"
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

        let firstResult = app.buttons["dulcet.search.result.0"].firstMatch
        XCTAssertTrue(firstResult.waitForExistence(timeout: 30), "Rank zero must render: " + app.debugDescription)
        XCTAssertTrue(firstResult.label.hasPrefix(query + ","), firstResult.label)
        XCTAssertTrue(firstResult.label.hasSuffix(", Track"), firstResult.label)
        print("DULCET TV RANK0 label=\(firstResult.label)")
        for _ in 0..<8 {
            if firstResult.hasFocus { break }
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(firstResult.hasFocus, "Rank zero must have remote focus: " + app.debugDescription)
        XCUIRemote.shared.press(.select)

        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 15), "Activating rank zero must present Now Playing")
        XCTAssertEqual(title.label, query)
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
        XCTAssertEqual(title.label, query)
        print("DULCET TV SEARCH PASS query=typed rank0=rendered activation=remote-select source=search"
            + " title=\(title.label) progress=\(initialValue)->\(progress.value as? String ?? "missing")"
            + " setup=debug-account-and-destination")
    }
}
