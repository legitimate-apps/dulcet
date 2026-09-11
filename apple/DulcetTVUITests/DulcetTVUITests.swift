import XCTest
import UIKit

final class DulcetTVUITests: XCTestCase {
    /// Section order in the tvOS section bar, which is also the order the remote traverses.
    private static let sections = ["library", "search", "nowPlaying", "settings"]

    /// The account is DEBUG setup, because typing credentials raises a system save-password
    /// dialog no app-side query can reach. Everything else -- reaching Search, entering the
    /// query, ranked rendering, remote activation, playback, and the return to Library --
    /// runs through the production tvOS UI. Nothing selects a destination for this control:
    /// the section bar is the only route to Search, which is what makes the run evidence of
    /// reachability rather than evidence that the surface works once something else reaches it.
    ///
    /// Both section-bar reaches below go through `focusSectionBarViaUpNavigation` only, never
    /// the exit command: OBSERVED (docs/tvos-search-ui-evidence.md, "Focus behaviour"), the
    /// Connection and Now Playing surfaces are shallow single-panel surfaces that reach the bar
    /// in one Up press, unlike the Library album grid, which does not reach it in ten. This
    /// control therefore has nothing to say about whether the exit command moves focus to the
    /// bar -- that claim belongs to `testExitCommandAtIdleConnectionReturnsFocusToSectionBar`,
    /// asserted on its own, so a broken exit command reports as its own failure instead of
    /// hiding behind, or taking down, this one.
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

        // Reach Search the way a person does. Up-navigation, not the exit command: see
        // focusSectionBarViaUpNavigation.
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
        // The value settles asynchronously, and on tvOS the characters cross from the system
        // keyboard's own scene into the app, so `typeText` returning is not a guarantee that they
        // have arrived. One apple-ci run read `""` here -- run 34589816662, 1 of 39 executions in
        // the job-log corpus. That run had already asserted the field existed, had remote focus,
        // and that the keyboard was on screen, so "never reached" is excluded by its own upstream
        // assertions; and TRACED against the app's text binding on an Apple TV 4K simulator, the
        // app commits one character at a time and receives all of them in order, so a read landing
        // mid-sequence legitimately sees a prefix -- the empty one included.
        //
        // NOT OBSERVED: the failing population settling. 15 local runs, including per-keystroke
        // app cost induced at 200 and 500 ms and a 57-character query, all had the value complete
        // at the first sample, so the event is rarer than anything this could force. The poll is
        // safe under either reading -- text that never arrives still fails it -- and it records
        // which population the run saw, which nothing else can: `continueAfterFailure` is false
        // here, so no later assertion survives to answer the question.
        //
        // The samples carry their own process check. An incremental commit produces non-shrinking
        // prefixes of the query; a sample that is not a prefix, or one that goes backwards, is a
        // different defect and says so rather than being counted as "still settling".
        var settleSamples: [String] = []
        var observedFieldValue = field.value as? String ?? ""
        settleSamples.append(observedFieldValue)
        let settleClock = ContinuousClock()
        let settleDeadline = settleClock.now.advanced(by: .seconds(10))
        while observedFieldValue != query, settleClock.now < settleDeadline {
            Thread.sleep(forTimeInterval: 0.1)
            observedFieldValue = field.value as? String ?? ""
            settleSamples.append(observedFieldValue)
        }
        print("DULCET TV KEYBOARD-BUFFER OBSERVED settle-attempts=\(settleSamples.count - 1)"
            + " first=\(settleSamples[0].debugDescription) final=\(observedFieldValue.debugDescription)")
        for (index, sample) in settleSamples.enumerated() {
            XCTAssertTrue(
                query.hasPrefix(sample),
                "Sample \(index) was \(sample.debugDescription), which is not a prefix of the typed"
                    + " query: the field is not receiving this text one character at a time"
            )
            if index > 0 {
                XCTAssertGreaterThanOrEqual(
                    sample.count,
                    settleSamples[index - 1].count,
                    "The field's value went backwards between samples \(index - 1) and \(index)"
                )
            }
        }
        XCTAssertEqual(
            observedFieldValue,
            query,
            "The tvOS keyboard must accept the typed text; polled \(settleSamples.count - 1) times over 10 s"
        )
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
        // This is the assertion that proves the APP received the text, and the one above the
        // keyboard does not. MEASURED: with the search field's binding replaced by a constant
        // empty string -- so the app can never hold the query -- the in-keyboard check above
        // still reported "Threshold" and passed, and only this one failed with "". While the
        // tvOS keyboard is open, the field's reported value follows the keyboard, so a check
        // taken there certifies the keyboard, not the app.
        //
        // Polled for the same reason as the check above, and it is the same poll: the app's own
        // state settles after the keyboard hands the text over, so a single sample here would
        // conflate "the app never got it" with "the sample was early".
        var appSamples: [String] = []
        var appFieldValue = field.value as? String ?? ""
        appSamples.append(appFieldValue)
        let appClock = ContinuousClock()
        let appDeadline = appClock.now.advanced(by: .seconds(10))
        while appFieldValue != query, appClock.now < appDeadline {
            Thread.sleep(forTimeInterval: 0.1)
            appFieldValue = field.value as? String ?? ""
            appSamples.append(appFieldValue)
        }
        print("DULCET TV APP-FIELD OBSERVED settle-attempts=\(appSamples.count - 1)"
            + " first=\(appSamples[0].debugDescription) final=\(appFieldValue.debugDescription)")
        for (index, sample) in appSamples.enumerated() {
            XCTAssertTrue(
                query.hasPrefix(sample),
                "App-field sample \(index) was \(sample.debugDescription), which is not a prefix of"
                    + " the typed query"
            )
        }
        XCTAssertEqual(
            appFieldValue,
            query,
            "Typed text must reach the app's own field once the keyboard is dismissed;"
                + " polled \(appSamples.count - 1) times over 10 s"
        )

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
        // person who plays one track has no way back to what they were browsing. Up-navigation
        // again, not the exit command.
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

    /// Production remote activation and progressing media time, paired with the workflow's
    /// independent 0 -> 1 server assertion. This test never submits a scrobble itself.
    @MainActor
    func testTVSimulatorPlaybackAdvancesPastScrobbleThreshold() throws {
        continueAfterFailure = false
        let udid = try XCTUnwrap(ProcessInfo.processInfo.environment["SIMULATOR_UDID"])
        XCTAssertNotNil(UUID(uuidString: udid), "Playback evidence requires a simulator UDID")
        // UIKit's runtime idiom identifies the runner's device family directly. Window width
        // cannot distinguish an Apple TV from a large iPad; .tv rejects both iPhone and iPad.
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .tv, "Only Apple TV is valid evidence")
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
            "The live account connection must succeed before navigation is attempted: "
        )
        XCTAssertEqual(app.navigationBars.firstMatch.identifier, "Connection")
        XCTAssertFalse(
            app.textFields["dulcet.search.field"].firstMatch.exists,
            "Search must not already be on screen, or reaching it proves nothing: "
        )
        // A defined initial focus: the remote starts on one of the section's own named controls,
        // inside the content rather than on the bar, so the first press does something the person
        // asked for. Which control is the section's business -- an idle form focuses its first
        // field, a saved account focuses Reconnect -- so this pins the section, not the row.
        XCTAssertNil(focusedSection(app), "Launch focus belongs in the section, not on the bar")
        let launchFocus = try XCTUnwrap(
            focusedControlIdentifier(app),
            "Launch must place remote focus on a named control: "
        )
        XCTAssertTrue(
            launchFocus.hasPrefix("dulcet.account-connect."),
            "Launch focus must be a Connection control, observed \(launchFocus): "
        )
        print("DULCET TV LAUNCH section=Connection focus=\(launchFocus) search-present=false")

        // Reach Search the way a person does. Up-navigation, not the exit command: see
        // focusSectionBarViaUpNavigation.
        XCTAssertTrue(
            selectSection(app, "search"),
            "The section bar must reach Search from Connection: "
        )
        let field = app.textFields["dulcet.search.field"].firstMatch
        XCTAssertTrue(
            field.waitForExistence(timeout: 40),
            "Selecting Search in the section bar must present the search surface: "
        )
        // Down leaves the bar for the section's own content. Bounded, and re-checked after every
        // press, so a focus engine that never reaches the field fails here rather than typing
        // into whatever else happens to hold focus.
        for _ in 0..<4 {
            if field.hasFocus { break }
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(field.hasFocus, "Search field must have remote focus: ")
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
        XCTAssertTrue(done.hasFocus, "Keyboard Done must have remote focus: ")
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
                "Rank \(rank) must render its own identifier: "
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
            "The ranked list must end at rank \(rankedLabels.count - 1): "
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
            "Rank \(canaryRank) must have remote focus: "
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

        let pastThreshold = NSPredicate { _, _ in
            guard progress.exists, let value = progress.value as? String else { return false }
            let parts = value.components(separatedBy: " of ")
            guard parts.count == 2, parts[1] == "0:31" else { return false }
            let clock = parts[0].split(separator: ":").compactMap { Double($0) }
            guard clock.count == 2 else { return false }
            // §15.2: duration >= 30s; threshold min(50% of duration, four minutes).
            // The next displayed whole second is strictly beyond 15.5s. No seeking occurs.
            return clock[0] * 60 + clock[1] >= 16
        }
        XCTAssertEqual(
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: pastThreshold, object: nil)], timeout: 90),
            .completed,
            "Progressing media time must cross the eligible 31-second canary's 15.5-second threshold"
        )
        XCTAssertEqual(title.label, canaryTitle)
        print("DULCET TV PLAYBACK PASS simulator=\(udid) idiom=tv title=\(title.label)"
            + " initial=\(initialValue) final=\(progress.value as? String ?? "missing") threshold=15.5")
    }

    /// DulcetAccountConnectionView installs its own onExitCommand and supplies `nil` while idle:
    /// `.dulcetOnExitCommand(perform: isConnecting ? { ... } : nil)`. SwiftUI does not document
    /// whether a `nil` action still consumes the exit press at that view or lets it fall through
    /// to an ancestor's own `onExitCommand` -- here, `DulcetTVSectionNavigation`'s outer handler,
    /// which returns focus to the bar. This test settles that empirically, by pressing Menu
    /// exactly once with no preceding Up press, so the outcome is a claim about the exit command
    /// alone -- not about whether Up would also have worked. It is a separate test from the
    /// search/ranking control above so a broken exit command reports as its own failure instead
    /// of taking an unrelated, currently-working control down with it.
    @MainActor
    func testExitCommandAtIdleConnectionReturnsFocusToSectionBar() throws {
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

        let app = XCUIApplication()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", serverURL,
            "-dulcet-debug-account-username", username,
            "-dulcet-debug-account-password", password,
        ]
        app.launch()
        XCTAssertTrue(
            app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 60),
            "The live account connection must succeed before the exit command is exercised: " + app.debugDescription
        )
        XCTAssertEqual(app.navigationBars.firstMatch.identifier, "Connection")
        XCTAssertNil(focusedSection(app), "Must start with focus inside the Connection surface, not on the bar")
        let priorFocus = try XCTUnwrap(
            focusedControlIdentifier(app),
            "Launch must place remote focus on a named control: " + app.debugDescription
        )

        // The press under test. Deliberately no Up presses precede it: Up reaching the bar from
        // this surface (proven by the sibling control) says nothing about whether Menu also
        // does, and folding both into one press loop is exactly the defect this test exists not
        // to repeat.
        XCUIRemote.shared.press(.menu)

        guard app.state == .runningForeground else {
            print("DULCET TV EXITCOMMAND outcome=app-exited state=\(app.state) prior-focus=\(priorFocus)")
            XCTFail(
                "The exit press left the app instead of returning focus to the bar (state=\(app.state)): "
                    + "a nil inner onExitCommand did not fall through to the outer handler, and the press "
                    + "reached the platform's own default action instead of either handler: " + app.debugDescription
            )
            return
        }
        let landedSection = focusedSection(app)
        let outcome = landedSection == "settings" ? "fell-through-to-bar" : "swallowed"
        print(
            "DULCET TV EXITCOMMAND outcome=\(outcome) landed-section=\(landedSection ?? "none")"
                + " prior-focus=\(priorFocus)"
        )
        XCTAssertEqual(
            landedSection,
            "settings",
            "A nil inner onExitCommand on the idle Connection surface must fall through to "
                + "DulcetTVSectionNavigation's outer handler and return focus to the bar: " + app.debugDescription
        )
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

    /// Margin over the OBSERVED minimum for `focusSectionBarViaUpNavigation`. OBSERVED
    /// (docs/tvos-search-ui-evidence.md, "Focus behaviour"): one Up press reaches the bar from a
    /// shallow single-panel surface. 4 is a four-times margin over that minimum for the two
    /// shallow surfaces this file drives (Connection, Now Playing) -- not a search for an
    /// unbounded retry. A surface that needs more than this is a different, deeper shape
    /// (OBSERVED: the Library album grid does not reach the bar within ten), and reaching the
    /// bar from one of those is the exit command's job, proven separately in
    /// `testExitCommandAtIdleConnectionReturnsFocusToSectionBar`, not this loop's.
    private static let sectionBarUpNavigationBound = 4

    /// Moves remote focus from a section's content back onto the section bar by Up-navigation
    /// alone. Deliberately not a fallback chain to the exit command: a call site that needs the
    /// exit command should exercise it directly and say so, the way
    /// `testExitCommandAtIdleConnectionReturnsFocusToSectionBar` does, rather than reaching for a
    /// loop that can pass whether or not the exit command works.
    ///
    /// Returns the number of Up presses actually needed (0 if the bar already had focus), or nil
    /// if `sectionBarUpNavigationBound` was exceeded without reaching it. The count is the
    /// marker: a caller that only recorded true/false could not tell "reached on press one" from
    /// "reached on the last try before the bound", which is exactly the ambiguity a bounded
    /// retry loop must not leave behind.
    @MainActor
    private func focusSectionBarViaUpNavigation(_ app: XCUIApplication) -> Int? {
        if focusedSection(app) != nil { return 0 }
        for attempt in 1...Self.sectionBarUpNavigationBound {
            XCUIRemote.shared.press(.up)
            if focusedSection(app) != nil { return attempt }
        }
        return nil
    }

    /// Reaches one section through the bar, by remote, the way a person does: focus the bar by
    /// Up-navigation, walk to the section's own control, press it.
    @MainActor
    private func selectSection(_ app: XCUIApplication, _ section: String) -> Bool {
        guard let target = Self.sections.firstIndex(of: section) else { return false }
        guard let presses = focusSectionBarViaUpNavigation(app) else {
            print(
                "DULCET TV FOCUS-BAR mechanism=up-navigation outcome=not-reached"
                    + " bound=\(Self.sectionBarUpNavigationBound) target=\(section)"
            )
            return false
        }
        print("DULCET TV FOCUS-BAR mechanism=up-navigation outcome=reached presses=\(presses) target=\(section)")
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
