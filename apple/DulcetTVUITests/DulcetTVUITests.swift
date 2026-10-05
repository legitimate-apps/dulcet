import CryptoKit
import UIKit
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
        let canaryLabel = "UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track"
        // The rows this query must render, each exactly once. Their ORDER is not asserted here:
        // search reads the device first and the server's answer replaces rows in place and
        // appends the rest (spec §16.15), so which rank a row takes depends on what this device
        // already held when the last keystroke landed -- typing speed against the debounce, not
        // correctness. The ranking is the core's, and its tests pin it. What this control keeps
        // is what only the app can show: every rank carries its own identifier and its own row,
        // and selecting one rank plays that row, not rank zero.
        let expectedLabels: Set<String> = [
            "Thirty One Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "Twenty Nine Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            canaryLabel,
            "Threshold Boundary, Dulcet Fixtures, Album",
        ]
        let rankCount = expectedLabels.count
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

        // Search is somewhere else, and only the section bar goes there.
        let launch = try awaitLaunchAndLiveConnection(app)
        XCTAssertFalse(
            app.textFields["dulcet.search.field"].firstMatch.exists,
            "Search must not already be on screen, or reaching it proves nothing: " + app.debugDescription
        )
        print("DULCET TV LAUNCH section=\(launch.section) focus=\(launch.focus) search-present=false")

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
        // Polled for the same reason as the check above, and it is the same poll -- including the
        // same two process checks: the app's own state settles after the keyboard hands the text
        // over, so a single sample here would conflate "the app never got it" with "the sample was
        // early". The non-shrinking check matters MORE here than it does above. A keyboard buffer
        // that reports a shorter value is a transcription artefact of a widget mid-edit; the app's
        // committed state going backwards is the app dropping text it already held, and that is a
        // product defect, not settling. Without this check the poll absorbs it: "Thresho" followed
        // by "Thresh" are both prefixes of the query, so the prefix check alone passes both.
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
            if index > 0 {
                XCTAssertGreaterThanOrEqual(
                    sample.count,
                    appSamples[index - 1].count,
                    "The app's own field value went backwards between samples \(index - 1) and"
                        + " \(index): the app is losing text it already held"
                )
            }
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
        for rank in 0..<rankCount {
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
            XCTAssertTrue(
                expectedLabels.contains(label),
                "Rank \(rank) rendered \(label.debugDescription), which this query does not match"
            )
            XCTAssertFalse(observedLabels.contains(label), "Rank \(rank) repeats an earlier rank's row")
            observedLabels.append(label)
        }
        XCTAssertEqual(Set(observedLabels), expectedLabels, "Every matching row must render once")
        let canaryRank = try XCTUnwrap(
            observedLabels.firstIndex(of: canaryLabel),
            "The canary must render: observed \(observedLabels)"
        )
        XCTAssertNotEqual(
            canaryRank,
            0,
            "The canary must not render at rank zero, or this control cannot tell rank from arity"
        )
        XCTAssertFalse(
            app.buttons["dulcet.search.result.\(rankCount)"].firstMatch.exists,
            "The ranked list must end at rank \(rankCount - 1): " + app.debugDescription
        )
        print("DULCET TV RANKS labels=\(observedLabels)")

        // Reaching a low rank may have carried focus past the canary, so the walk chooses its
        // direction from the rank that actually holds focus. Pressing up blindly would leave the
        // list for the search field and then for the section bar.
        let canaryResult = app.buttons["dulcet.search.result.\(canaryRank)"].firstMatch
        for _ in 0..<16 {
            if canaryResult.exists, canaryResult.hasFocus { break }
            let focusedRank = (0..<rankCount).first { rank in
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
            // One snapshot: an element redrawn between `exists` and `value` fails the test with
            // "Failed to get matching snapshot" instead of reading again.
            guard let value = (try? progress.snapshot())?.value as? String else { return false }
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
            app.buttons["dulcet.reader.section.albums"].firstMatch.waitForExistence(timeout: 30),
            "Returning to Library must present the library, not the section it came from: " + app.debugDescription
        )
        XCTAssertEqual(app.navigationBars.firstMatch.identifier, "Library")
        // The reader's section bar is chrome; an album tile on Home is the library itself, read
        // through the reader (the legacy library views are not reachable while it holds the account).
        let shelfTile = app.descendants(matching: .any).matching(identifier: "dulcet.library.album").firstMatch
        XCTAssertTrue(
            shelfTile.waitForExistence(timeout: 30),
            "Library must paint the reader's albums, not only its section bar: " + app.debugDescription
        )
        let observedTile = shelfTile.exists ? shelfTile.label : "missing"
        XCTAssertFalse(
            app.textFields["dulcet.search.field"].firstMatch.exists,
            "Library must replace the search surface: " + app.debugDescription
        )
        print("DULCET TV SEARCH PASS query=typed ranks=\(observedLabels)"
            + " activated-rank=\(canaryRank) activation=remote-select source=search"
            + " title=\(observedTitle) progress=\(initialValue)->\(observedProgress)"
            + " reached-search=section-bar returned-to=library library-tile=\(observedTile)"
            + " setup=debug-account-only")
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
        let launch = try awaitLaunchAndLiveConnection(app)
        if launch.section != "Connection" {
            // Reaching Connection through the bar leaves focus on the bar; the press under test
            // needs it inside the surface, where a launch on Connection would have put it.
            for _ in 0..<4 where focusedSection(app) != nil {
                XCUIRemote.shared.press(.down)
            }
        }
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

    /// Now Playing on Apple TV offers the playing track's heart and its lyrics, both reached and
    /// pressed with the remote alone (spec §16.20, §18.4). The account is DEBUG setup; the track
    /// is reached through the section bar and Search, typed and activated by remote. Down from
    /// the transport reaches the heart; Select fills it at once and the star reaches the server
    /// for that track, and Select again takes it off, so the server is left as it was found.
    /// Right moves across the five stars beside it; Select on one rates the track that many stars
    /// on the server and Select again on it removes the rating. Right past the fifth reaches Lyrics; Select shows the track's synced English layer and a line lights as
    /// media time moves, with focus kept on the toggle so the same press hides them again.
    @MainActor
    func testNowPlayingHeartAndLyricsAreReachedAndPressedByRemote() throws {
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
        let server = (url: serverURL, username: username, password: password)
        let album = "Threshold Boundary"
        let track = "Twenty Nine Seconds"
        let query = "Twenty Nine"

        let app = XCUIApplication()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", serverURL,
            "-dulcet-debug-account-username", username,
            "-dulcet-debug-account-password", password,
        ]
        app.launch()
        _ = try awaitLaunchAndLiveConnection(app)

        // Reach the track by remote: Search through the section bar, the query typed, Done.
        XCTAssertTrue(selectSection(app, "search"), "The section bar must reach Search: " + app.debugDescription)
        let field = app.textFields["dulcet.search.field"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 40), "Search must present its field: " + app.debugDescription)
        for _ in 0..<4 where !field.hasFocus {
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(field.hasFocus, "Search field must have remote focus: " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        field.typeText(query)
        let done = app.buttons["done"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        for _ in 0..<6 where !done.hasFocus {
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(done.hasFocus, "Keyboard Done must have remote focus: " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        // The app's own field, once the keyboard has handed the text over (see the search control
        // above for why the in-keyboard value certifies nothing).
        let settle = ContinuousClock.now.advanced(by: .seconds(10))
        while field.value as? String != query, ContinuousClock.now < settle {
            Thread.sleep(forTimeInterval: 0.1)
        }
        XCTAssertEqual(field.value as? String, query, "The typed query must reach the app's field")

        let rowPrefix = "\(track), "
        var trackRow: XCUIElement?
        let rowsDeadline = ContinuousClock.now.advanced(by: .seconds(30))
        repeat {
            trackRow = (0..<8).lazy
                .map { app.buttons["dulcet.search.result.\($0)"].firstMatch }
                .first { $0.exists && $0.label.hasPrefix(rowPrefix) && $0.label.hasSuffix(", Track") }
            if trackRow == nil { Thread.sleep(forTimeInterval: 0.25) }
        } while trackRow == nil && ContinuousClock.now < rowsDeadline
        let row = try XCTUnwrap(trackRow, "Search must list \(track) as a track: " + app.debugDescription)
        for _ in 0..<10 where !(row.exists && row.hasFocus) {
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(row.exists && row.hasFocus, "The track's row must take remote focus: " + app.debugDescription)
        XCUIRemote.shared.press(.select)

        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 15), "Activating the row must present Now Playing")
        XCTAssertEqual(title.label, track, "Now Playing must show the track this proof stars")

        let heart = app.buttons["dulcet.now-playing.favorite"].firstMatch
        let lyrics = app.buttons["dulcet.now-playing.lyrics"].firstMatch
        let stars = (1...5).map { app.buttons["dulcet.now-playing.rating.star.\($0)"].firstMatch }
        XCTAssertTrue(heart.waitForExistence(timeout: 10), "Now Playing must offer the heart: " + app.debugDescription)
        XCTAssertTrue(stars.allSatisfy(\.exists), "Now Playing must offer five stars beside the heart: " + app.debugDescription)
        // Lyrics first, while the 29-second track is still early: the heart's and stars' server
        // round trips can outlast it, and an ended track lights no line. Lyrics sit after the
        // stars, beside the heart's group: Down from the transport to that row, then Right across
        // it, bounded and re-checked after every press.
        XCTAssertTrue(lyrics.exists, "Now Playing must offer lyrics: " + app.debugDescription)
        var lyricsPresses = 0
        while !lyrics.hasFocus, lyricsPresses < 12 {
            let inRow = (heart.exists && heart.hasFocus) || stars.contains { $0.exists && $0.hasFocus }
            XCUIRemote.shared.press(inRow ? .right : .down)
            lyricsPresses += 1
        }
        XCTAssertTrue(lyrics.hasFocus, "Lyrics must take remote focus after the stars: " + app.debugDescription)
        XCTAssertEqual(lyrics.label, "Show Lyrics")
        XCUIRemote.shared.press(.select)
        let panel = app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch
        XCTAssertTrue(panel.waitForExistence(timeout: 10), "The lyrics panel must open: " + app.debugDescription)
        let shown = app.staticTexts.matching(NSPredicate(format: "label == %@", "Dulcet English line one")).firstMatch
        XCTAssertTrue(shown.waitForExistence(timeout: 20), "The panel must show the English layer: " + app.debugDescription)
        // The control: a lit line proves the cursor follows media time, not only that text was drawn.
        let current = app.staticTexts["dulcet.lyrics.line.current"].firstMatch
        XCTAssertTrue(current.waitForExistence(timeout: 25), "A line must light as the track plays: " + app.debugDescription)
        let lit = current.exists ? current.label : "<none>"
        XCTAssertTrue(lit.hasPrefix("Dulcet English line"), "The lit line must be the English layer's; lit=\(lit)")
        let toggleKeptFocus = ContinuousClock.now.advanced(by: .seconds(3))
        while !(lyrics.exists && lyrics.hasFocus), ContinuousClock.now < toggleKeptFocus {
            Thread.sleep(forTimeInterval: 0.1)
        }
        XCTAssertTrue(lyrics.hasFocus, "Showing lyrics must leave focus on the toggle: " + app.debugDescription)
        XCTAssertEqual(lyrics.label, "Hide Lyrics")
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(panel.waitForNonExistence(timeout: 5), "The same press must hide the lyrics")

        // The heart: Left from Lyrics across the stars, or Down first if focus left the row. Bounded and re-checked after every press, so a heart
        // the remote cannot reach fails here, with the presses it took in the pass line when it
        // does not.
        var heartPresses: [String] = []
        while !heart.hasFocus, heartPresses.count < 12 {
            let besideHeart = (lyrics.exists && lyrics.hasFocus) || stars.contains { $0.exists && $0.hasFocus }
            let direction: XCUIRemote.Button = besideHeart ? .left : .down
            XCUIRemote.shared.press(direction)
            heartPresses.append(direction == .left ? "left" : "down")
        }
        XCTAssertTrue(heart.hasFocus, "The heart must take remote focus: " + app.debugDescription)
        if heart.label == "Remove Favorite" {
            // An earlier run's favourite, cleared first so the change below is observable.
            XCUIRemote.shared.press(.select)
            XCTAssertTrue(waitForLabel("Favorite", of: heart, timeout: 5))
            XCTAssertEqual(awaitServerSongStarred(track, album: album, server: server, expected: false, timeout: 30), false)
        }
        XCTAssertEqual(readServerSongStarred(track, album: album, server: server), false,
            "The control: the server must not already hold the favourite this proof makes")
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(waitForLabel("Remove Favorite", of: heart, timeout: 3),
            "The heart must fill at once, before the server answers; label=\(heart.label)")
        XCTAssertEqual(awaitServerSongStarred(track, album: album, server: server, expected: true, timeout: 30), true,
            "The star must reach the server for the playing track")
        XCTAssertEqual(title.label, track, "Starring must not change what is playing")
        XCTAssertTrue(heart.hasFocus, "The press must leave focus on the heart: " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(waitForLabel("Favorite", of: heart, timeout: 3), "The heart must empty at once")
        XCTAssertEqual(awaitServerSongStarred(track, album: album, server: server, expected: false, timeout: 30), false,
            "Removing the favourite must reach the server")

        // The stars: Right from the heart reaches them in order, and Select on one rates the
        // playing track that many stars on the server; Select on the star already shown removes
        // the rating. The value is chosen against what the server holds before the press, so an
        // earlier run's rating never makes the write unobservable.
        let ratingBefore = try XCTUnwrap(readServerSongRating(track, album: album, server: server),
            "The control: the server's rating must be readable before the press")
        let rating = ratingBefore == 3 ? 2 : 3
        var starPresses = 0
        for star in stars.prefix(rating) {
            XCUIRemote.shared.press(.right)
            starPresses += 1
            XCTAssertTrue(star.hasFocus, "The remote must move across the stars in order: " + app.debugDescription)
        }
        XCUIRemote.shared.press(.select)
        XCTAssertEqual(awaitServerSongRating(track, album: album, server: server, expected: rating, timeout: 30), rating,
            "Select on star \(rating) must rate the playing track \(rating) on the server (was \(ratingBefore))")
        XCTAssertTrue(stars[rating - 1].hasFocus, "The press must leave focus on the star: " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        XCTAssertEqual(awaitServerSongRating(track, album: album, server: server, expected: 0, timeout: 30), 0,
            "Select on the star already shown must remove the rating on the server")
        XCTAssertEqual(title.label, track, "Rating must not change what is playing")
        for star in stars.dropFirst(rating) {
            XCUIRemote.shared.press(.right)
            starPresses += 1
            XCTAssertTrue(star.hasFocus, "The remote must move across the stars in order: " + app.debugDescription)
        }
        print("DULCET TV rating presses=\(starPresses) rated=\(rating) before=\(ratingBefore)")

        print("DULCET TV NOW PLAYING PASS track=\(track.debugDescription) heart-presses=\(heartPresses.joined(separator: ","))"
            + " heart=remote-select starred=true->false lyrics=remote-select lyrics-presses=\(lyricsPresses) lit=\(lit.debugDescription)"
            + " setup=debug-account-only")
    }

    /// The lyrics panel's other states on Apple TV, every step by remote (spec §18.4): plain
    /// lyrics whose lines each take focus, so Down reads from one to the next; a track with none
    /// says so; and a failed read says so and offers Try Again, which the remote reaches and
    /// presses. The synced state is `testNowPlayingHeartAndLyricsAreReachedAndPressedByRemote`'s.
    ///
    /// Each track's shape is read back from the disposable server first, so a drifted corpus fails
    /// as a precondition. The failed read goes through `tools/conformance-env/lyrics-fault-proxy`,
    /// which answers "Thirty One Seconds"' lyrics read with HTTP 500 until disarmed; its count shows
    /// the failure was met and that Try Again sent a new read. That needs a track this device has
    /// never stored lyrics for, so no earlier phase on the simulator may open its lyrics.
    @MainActor
    func testLyricsPlainNoneAndAFailedReadAreDrivenByRemote() throws {
        continueAfterFailure = false
        XCTAssertNotNil(ProcessInfo.processInfo.environment["SIMULATOR_UDID"], "This proof requires a tvOS simulator")
        let server = try disposableServer()
        let proxyURL = try XCTUnwrap(ProcessInfo.processInfo.environment["DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL"],
            "DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL must name the lyrics fault proxy")
        XCTAssertTrue(Self.isLoopbackURL(proxyURL), "The lyrics fault proxy must be a loopback host")
        let album = "Threshold Boundary"
        let plainID = try XCTUnwrap(readServerSong("Ogg Probe", album: "Dulcet Conformance", server: server)?["id"] as? String)
        let noneID = try XCTUnwrap(readServerSong("UI Playback Canary", album: album, server: server)?["id"] as? String)
        let failingID = try XCTUnwrap(readServerSong("Thirty One Seconds", album: album, server: server)?["id"] as? String)
        let plainLayers = try XCTUnwrap(readServerLyrics(songID: plainID, server: server))
        XCTAssertEqual(plainLayers.map(\.synced), [false], "Ogg Probe must carry one unsynced layer")
        let plainLines = try XCTUnwrap(plainLayers.first?.lines)
        XCTAssertEqual(plainLines, ["Dulcet plain sidecar line one", "Dulcet plain sidecar line two"])
        XCTAssertEqual(try XCTUnwrap(readServerLyrics(songID: noneID, server: server)).count, 0,
            "UI Playback Canary must carry no lyrics")
        XCTAssertTrue(try XCTUnwrap(readServerLyrics(songID: failingID, server: server)).contains { $0.synced },
            "Thirty One Seconds must carry synced lyrics")
        // Server identity: the proxy's library is the server's.
        let proxied = (url: proxyURL, username: server.username, password: server.password)
        XCTAssertEqual(readServerSong("Thirty One Seconds", album: album, server: proxied)?["id"] as? String, failingID,
            "The lyrics fault proxy must front the disposable server")

        // Plain: the lines take focus one after another.
        let plainApp = try launchAndConnect(serverURL: server.url, server: server)
        try playFromSearch(plainApp, query: "Ogg Probe", track: "Ogg Probe")
        let plainPanel = try showLyricsByRemote(plainApp)
        let lines = plainApp.descendants(matching: .any).matching(identifier: "dulcet.lyrics.line")
        let first = lines.matching(NSPredicate(format: "label == %@", plainLines[0])).firstMatch
        let second = lines.matching(NSPredicate(format: "label == %@", plainLines[1])).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 20) && second.exists,
            "Plain lyrics must show both lines: " + plainApp.debugDescription)
        XCTAssertFalse(plainApp.descendants(matching: .any)["dulcet.lyrics.line.current"].firstMatch.exists,
            "Plain lyrics have no current line, so none may be lit")
        // Right from the footer enters the panel; the focus engine picks the line nearest the
        // move (OBSERVED: the second line), so the walk then goes Up to the first and Down again.
        var intoLines: [String] = []
        while !(first.hasFocus || second.hasFocus), intoLines.count < 4 {
            XCUIRemote.shared.press(.right)
            intoLines.append("right")
        }
        XCTAssertTrue(first.hasFocus || second.hasFocus,
            "The remote must reach the plain lines from the footer: " + plainApp.debugDescription)
        func awaitFocus(on line: XCUIElement) -> Bool {
            let moved = ContinuousClock.now.advanced(by: .seconds(3))
            while !line.hasFocus, ContinuousClock.now < moved { Thread.sleep(forTimeInterval: 0.1) }
            return line.hasFocus
        }
        if second.hasFocus {
            XCUIRemote.shared.press(.up)
            intoLines.append("up")
        }
        XCTAssertTrue(awaitFocus(on: first), "Up must move focus to the first plain line: " + plainApp.debugDescription)
        XCUIRemote.shared.press(.down)
        XCTAssertTrue(awaitFocus(on: second), "Down must move focus to the next plain line: " + plainApp.debugDescription)
        XCTAssertTrue(plainPanel.exists)

        // None: said, and no spinner.
        let noneApp = try launchAndConnect(serverURL: server.url, server: server)
        try playFromSearch(noneApp, query: "UI Playback Canary", track: "UI Playback Canary")
        _ = try showLyricsByRemote(noneApp)
        let noLyrics = noneApp.staticTexts["dulcet.lyrics.none"].firstMatch
        XCTAssertTrue(noLyrics.waitForExistence(timeout: 20), "A track with no lyrics must say so: " + noneApp.debugDescription)
        // Read now: the next launch replaces this app, and its elements with it.
        let noneShown = noLyrics.exists ? noLyrics.label : "<none>"
        XCTAssertEqual(noneShown, "No lyrics for this song.")
        XCTAssertFalse(noneApp.activityIndicators["Loading lyrics"].firstMatch.exists,
            "A track the server has no lyrics for must never show a spinner")

        // A failed read, then Try Again by remote.
        XCTAssertNotNil(proxyControl("POST", "/__dulcet/lyrics-failure?song=\(failingID)&state=on", proxyURL: proxyURL),
            "The proxy must arm the failure")
        defer { _ = proxyControl("POST", "/__dulcet/lyrics-failure?song=\(failingID)&state=off", proxyURL: proxyURL) }
        let failedApp = try launchAndConnect(serverURL: proxyURL, server: server)
        try playFromSearch(failedApp, query: "Thirty One", track: "Thirty One Seconds")
        _ = try showLyricsByRemote(failedApp)
        let unavailable = failedApp.staticTexts["dulcet.lyrics.unavailable"].firstMatch
        let retry = failedApp.buttons["dulcet.lyrics.retry"].firstMatch
        if !(unavailable.waitForExistence(timeout: 30) && retry.waitForExistence(timeout: 5)) {
            let saved = failedApp.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Showing saved lyrics")).firstMatch
            XCTFail(saved.exists
                ? "This device already holds Thirty One Seconds' lyrics, so the failure shows as a note over them; the proof needs a simulator that never opened them"
                : "A failed read must say so and offer Try Again: " + failedApp.debugDescription)
            return
        }
        XCTAssertTrue(unavailable.label.hasPrefix("Lyrics couldn\u{2019}t be loaded"),
            "The panel must say the lyrics could not be loaded; message=\(unavailable.label)")
        let disarmed = try XCTUnwrap(proxyControl("POST", "/__dulcet/lyrics-failure?song=\(failingID)&state=off", proxyURL: proxyURL))
        let failedReads = disarmed["failed"] as? Int ?? 0
        XCTAssertGreaterThanOrEqual(failedReads, 1,
            "The proxy must have failed this track's lyrics read; otherwise the state above came from somewhere else")
        var toRetry: [String] = []
        while !retry.hasFocus, toRetry.count < 4 {
            XCUIRemote.shared.press(.right)
            toRetry.append("right")
        }
        XCTAssertTrue(retry.hasFocus, "The remote must reach Try Again: " + failedApp.debugDescription)
        XCUIRemote.shared.press(.select)
        let recovered = failedApp.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Dulcet synced line")).firstMatch
        XCTAssertTrue(recovered.waitForExistence(timeout: 30), "Try Again must read the lyrics again and show them: " + failedApp.debugDescription)
        let afterRetry = try XCTUnwrap(proxyControl("GET", "/__dulcet/observations?song=\(failingID)", proxyURL: proxyURL))
        let retryReads = afterRetry["forwardedAfterDisarm"] as? Int ?? 0
        XCTAssertGreaterThanOrEqual(retryReads, 1, "Try Again must send a new lyrics read")
        print("DULCET TV LYRICS STATES PASS plain-into-lines=\(intoLines.joined(separator: ",")) plain-down=focus-moved"
            + " none=\(noneShown.debugDescription) failed-reads=\(failedReads)"
            + " to-retry=\(toRetry.joined(separator: ",")) retry-reads=\(retryReads) setup=debug-account-only")
    }

    /// The Wi-Fi choice of the Connection screen's Streaming Quality rows (spec §12.5), made by
    /// remote, caps the next song the app streams: the disposable server's own log records the
    /// stream of "Dulcet Health Probe" -- a 123 kbps FLAC, read back first -- as transcoded to
    /// 96 kbps. The log is read from the offset it had before the play, so a line an earlier run
    /// left cannot answer. The cellular row is set to 128 kbps first, so a cap from the wrong row
    /// would show as 128. Both rows are put back to Original by remote at the end.
    @MainActor
    func testAStreamingQualityChosenByRemoteCapsTheStream() throws {
        continueAfterFailure = false
        XCTAssertNotNil(ProcessInfo.processInfo.environment["SIMULATOR_UDID"], "This proof requires a tvOS simulator")
        let server = try disposableServer()
        let logPath = try XCTUnwrap(ProcessInfo.processInfo.environment["DULCET_UI_TEST_SERVER_LOG"],
            "DULCET_UI_TEST_SERVER_LOG must name the disposable server's log")
        let track = "Dulcet Health Probe"
        let songID = try XCTUnwrap(readServerSong(track, album: "Dulcet Conformance", server: server)?["id"] as? String)
        let song = try XCTUnwrap(restCall("getSong", [URLQueryItem(name: "id", value: songID)], server: server)?["song"] as? [String: Any])
        let sourceKbps = try XCTUnwrap(song["bitRate"] as? Int, "The server must report the source bitrate")
        XCTAssertGreaterThan(sourceKbps, 96, "The control: the source must be above the cap, or no cap would be sent")
        let logBefore = try XCTUnwrap(Self.fileSize(logPath), "The disposable server's log must be readable: \(logPath)")

        let app = try launchAndConnect(serverURL: server.url, server: server)
        let metered = try chooseStreamingQualityByRemote(app, row: "metered", quality: "128")
        let unmetered = try chooseStreamingQualityByRemote(app, row: "unmetered", quality: "96")
        // The quality rows sit at the foot of the Connection page, deeper than the bar's
        // up-navigation bound reaches (OBSERVED: four Up presses did not); leaving a deep surface
        // is the exit command's job, as it is for a person.
        XCUIRemote.shared.press(.menu)
        let toBar = ContinuousClock.now.advanced(by: .seconds(3))
        while focusedSection(app) == nil, ContinuousClock.now < toBar { Thread.sleep(forTimeInterval: 0.1) }
        XCTAssertEqual(focusedSection(app), "settings",
            "The exit command must return focus to Connection's control on the bar: " + app.debugDescription)
        try playFromSearch(app, query: track, track: track)

        var lines: [[String: String]] = []
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        repeat {
            lines = Self.streamLines(logPath, from: logBefore).filter { $0["title"] == track }
            if !lines.isEmpty { break }
            Thread.sleep(forTimeInterval: 0.5)
        } while ContinuousClock.now < deadline
        XCTAssertFalse(lines.isEmpty, "The server must log a stream of \(track) after the play")
        for line in lines {
            XCTAssertEqual(line["transcoding"], "true", "The server must transcode the capped stream; line=\(line)")
            XCTAssertEqual(line["bitRate"], "96", "The stream must carry the Wi-Fi choice, 96 kbps; line=\(line)")
            XCTAssertEqual(line["originalBitRate"], String(sourceKbps), "The line must be this source's; line=\(line)")
        }

        XCTAssertTrue(selectSection(app, "settings"), "The section bar must reach Connection again: " + app.debugDescription)
        _ = try chooseStreamingQualityByRemote(app, row: "unmetered", quality: "original")
        _ = try chooseStreamingQualityByRemote(app, row: "metered", quality: "original")
        let summary = lines.map { "\($0["format"] ?? "?")@\($0["bitRate"] ?? "?")" }.joined(separator: ",")
        print("DULCET TV STREAMING QUALITY PASS track=\(track.debugDescription) source-kbps=\(sourceKbps)"
            + " metered-presses=\(metered) unmetered-presses=\(unmetered) server-streams=\(summary) setup=debug-account-only")
    }

    // MARK: - Lyrics and streaming-quality helpers

    private typealias Server = (url: String, username: String, password: String)

    /// Production playback on Apple TV, driven by the remote, crossing the scrobble threshold
    /// (§15.2) until the app reports the play delivered. The workflow reads the canary's server
    /// play count either side of this test; the test never reads or writes one itself, so a count
    /// that moves can only have come from the app. The app's debug delivery marker is why this
    /// returns on delivery rather than on the displayed threshold: returning on the threshold let
    /// the runner kill the app before its play left (the iPad proof, 2026-09-06 and 2026-09-11).
    @MainActor
    func testTVSimulatorPlaybackAdvancesPastScrobbleThreshold() throws {
        let udid = try XCTUnwrap(ProcessInfo.processInfo.environment["SIMULATOR_UDID"])
        XCTAssertNotNil(UUID(uuidString: udid), "Playback evidence requires a simulator, never a device")
        // The runtime idiom names the device family; it rejects iPhone and iPad, which window
        // size alone cannot.
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .tv, "Only Apple TV is valid evidence")
        let server = try disposableServer()
        let canary = "UI Playback Canary"
        let app = XCUIApplication()
        app.launchArguments = [
            "-dulcet-debug-scrobble-delivery-marker",
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", server.url,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        app.launch()
        _ = try awaitLaunchAndLiveConnection(app)

        // Before any playback the marker must read three zeros, each excluding a different way a
        // later delivered=1 could be credited to the wrong play: nothing delivered by this launch,
        // nothing left in the durable outbox by an earlier one, nothing persisted yet.
        let marker = app.staticTexts["dulcet.debug.scrobble-delivery"].firstMatch
        XCTAssertTrue(marker.waitForExistence(timeout: 10), "The delivery marker must exist when its launch argument is passed")
        let baseline = try XCTUnwrap(
            waitForScrobbleDeliveryCounts(in: marker, timeout: 10) { $0["delivered"] != nil },
            "The delivery marker must report counts; last label: \(marker.label)"
        )
        XCTAssertEqual(baseline["delivered"], 0, "No play may be delivered before playback starts")
        XCTAssertEqual(baseline["pending"], 0, "No play may be waiting from an earlier launch")
        XCTAssertEqual(baseline["persisted"], 0, "No play may be persisted before playback")
        guard baseline["delivered"] == 0, baseline["pending"] == 0, baseline["persisted"] == 0 else { return }

        try playFromSearch(app, query: canary, track: canary)
        let progress = app.progressIndicators["Now Playing"].firstMatch
        XCTAssertTrue(progress.waitForExistence(timeout: 30), "Real playback must expose progressing media time")
        var first: String?
        var last: String?
        let pastThreshold = NSPredicate { _, _ in
            // One snapshot per read: an element redrawn between `exists` and `value` throws.
            guard let value = (try? progress.snapshot())?.value as? String else { return false }
            if first == nil { first = value }
            last = value
            let parts = value.components(separatedBy: " of ")
            guard parts.count == 2, parts[1] == "0:31", value != first else { return false }
            let clock = parts[0].split(separator: ":").compactMap { Double($0) }
            guard clock.count == 2 else { return false }
            // §15.2: the 31-second canary is eligible at 15.5 s; the next whole second is beyond it.
            return clock[0] * 60 + clock[1] >= 16
        }
        XCTAssertEqual(
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: pastThreshold, object: nil)], timeout: 90),
            .completed,
            "Media time must progress past the canary's 15.5 s threshold; first=\(first ?? "nil") last=\(last ?? "nil")"
        )
        let delivered = try XCTUnwrap(
            waitForScrobbleDeliveryCounts(in: marker, timeout: 60) { ($0["delivered"] ?? 0) >= 1 },
            "The app must report the play delivered before this proof returns; last marker: \(marker.label)"
        )
        XCTAssertEqual(delivered["delivered"], 1, "Exactly one play is expected for one crossing")
        XCTAssertEqual(delivered["pending"], 0, "No play may be left unsent once it is delivered")
        print("DULCET TV PLAYBACK PASS simulator=\(udid) idiom=tv first=\(first ?? "nil") last=\(last ?? "nil")"
            + " marker=\(marker.label)")
    }

    private func waitForScrobbleDeliveryCounts(
        in marker: XCUIElement,
        timeout: TimeInterval,
        until accepted: ([String: Int]) -> Bool
    ) -> [String: Int]? {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if let counts = scrobbleDeliveryCounts(from: marker.label), accepted(counts) { return counts }
            Thread.sleep(forTimeInterval: 0.25)
        } while Date() < deadline
        return nil
    }

    private func scrobbleDeliveryCounts(from label: String) -> [String: Int]? {
        let words = label.split(separator: " ")
        guard words.first == "dulcet-scrobble" else { return nil }
        var counts: [String: Int] = [:]
        for word in words.dropFirst() {
            let pair = word.split(separator: "=", maxSplits: 1)
            guard pair.count == 2, let value = Int(pair[1]) else { return nil }
            counts[String(pair[0])] = value
        }
        return counts.isEmpty ? nil : counts
    }

    private struct DisposableServerRefused: Error {}

    /// The disposable server named by the run, refused unless it is on loopback.
    private func disposableServer() throws -> Server {
        let environment = ProcessInfo.processInfo.environment
        let url = try XCTUnwrap(environment["DULCET_UI_TEST_SERVER_URL"], "Missing disposable server URL")
        let username = try XCTUnwrap(environment["DULCET_UI_TEST_USERNAME"], "Missing disposable username")
        let password = try XCTUnwrap(environment["DULCET_UI_TEST_PASSWORD"], "Missing disposable password")
        guard Self.isLoopbackURL(url) else {
            XCTFail("Only a disposable loopback server is allowed; refusing \(URLComponents(string: url)?.host ?? "<none>")")
            throw DisposableServerRefused()
        }
        return (url, username, password)
    }

    private static func isLoopbackURL(_ url: String) -> Bool {
        guard let components = URLComponents(string: url), components.scheme == "http",
              let host = components.host?.lowercased() else { return false }
        return host == "localhost" || host == "127.0.0.1"
    }

    /// Launches with the account injected for `serverURL` and waits for its live connection on
    /// Connection.
    @MainActor
    private func launchAndConnect(serverURL: String, server: Server) throws -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", serverURL,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        app.launch()
        _ = try awaitLaunchAndLiveConnection(app)
        return app
    }

    /// Reaches a track by remote -- Search through the section bar, the query typed, the
    /// track's row focused and pressed -- and waits for Now Playing to show it.
    @MainActor
    private func playFromSearch(_ app: XCUIApplication, query: String, track: String) throws {
        XCTAssertTrue(selectSection(app, "search"), "The section bar must reach Search: " + app.debugDescription)
        let field = app.textFields["dulcet.search.field"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 40), "Search must present its field: " + app.debugDescription)
        for _ in 0..<4 where !field.hasFocus {
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(field.hasFocus, "Search field must have remote focus: " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        // A field an earlier search left filled is cleared first, so the query is exactly this.
        if let existing = field.value as? String, !existing.isEmpty, existing != field.placeholderValue {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
        }
        field.typeText(query)
        let done = app.buttons["done"].firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        for _ in 0..<6 where !done.hasFocus {
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(done.hasFocus, "Keyboard Done must have remote focus: " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        let settle = ContinuousClock.now.advanced(by: .seconds(10))
        while field.value as? String != query, ContinuousClock.now < settle {
            Thread.sleep(forTimeInterval: 0.1)
        }
        XCTAssertEqual(field.value as? String, query, "The typed query must reach the app's field")
        let rowPrefix = "\(track), "
        var trackRow: XCUIElement?
        let rowsDeadline = ContinuousClock.now.advanced(by: .seconds(30))
        repeat {
            trackRow = (0..<8).lazy
                .map { app.buttons["dulcet.search.result.\($0)"].firstMatch }
                .first { $0.exists && $0.label.hasPrefix(rowPrefix) && $0.label.hasSuffix(", Track") }
            if trackRow == nil { Thread.sleep(forTimeInterval: 0.25) }
        } while trackRow == nil && ContinuousClock.now < rowsDeadline
        let row = try XCTUnwrap(trackRow, "Search must list \(track) as a track: " + app.debugDescription)
        for _ in 0..<10 where !(row.exists && row.hasFocus) {
            XCUIRemote.shared.press(.down)
        }
        XCTAssertTrue(row.exists && row.hasFocus, "The track's row must take remote focus: " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        let title = app.staticTexts["dulcet.now-playing.title"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 15), "Activating the row must present Now Playing")
        XCTAssertTrue(waitForLabel(track, of: title, timeout: 15), "Now Playing must show \(track); title=\(title.label)")
    }

    /// Down from the transport to the footer row, Right to Lyrics, Select; returns the panel.
    @MainActor
    private func showLyricsByRemote(_ app: XCUIApplication) throws -> XCUIElement {
        let lyrics = app.buttons["dulcet.now-playing.lyrics"].firstMatch
        XCTAssertTrue(lyrics.waitForExistence(timeout: 10), "Now Playing must offer lyrics: " + app.debugDescription)
        var presses = 0
        while !lyrics.hasFocus, presses < 12 {
            let inFooter = app.buttons["dulcet.now-playing.favorite"].firstMatch.hasFocus
                || (1...5).contains { app.buttons["dulcet.now-playing.rating.star.\($0)"].firstMatch.hasFocus }
            XCUIRemote.shared.press(inFooter ? .right : .down)
            presses += 1
        }
        XCTAssertTrue(lyrics.hasFocus, "Lyrics must take remote focus: " + app.debugDescription)
        XCTAssertEqual(lyrics.label, "Show Lyrics")
        XCUIRemote.shared.press(.select)
        let panel = app.descendants(matching: .any)["dulcet.lyrics.panel"].firstMatch
        XCTAssertTrue(panel.waitForExistence(timeout: 10), "The lyrics panel must open: " + app.debugDescription)
        // Showing lyrics keeps focus on the toggle, so the next press starts from it.
        let kept = ContinuousClock.now.advanced(by: .seconds(3))
        while !lyrics.hasFocus, ContinuousClock.now < kept { Thread.sleep(forTimeInterval: 0.1) }
        XCTAssertTrue(lyrics.hasFocus, "Showing lyrics must leave focus on the toggle: " + app.debugDescription)
        return panel
    }

    /// On Connection, moves remote focus to one Streaming Quality row and along it to `quality`,
    /// presses it, and requires that button to read as chosen. Returns the presses it took.
    @MainActor
    private func chooseStreamingQualityByRemote(_ app: XCUIApplication, row: String, quality: String) throws -> String {
        let prefix = "dulcet.streaming-quality.\(row)."
        let target = app.buttons[prefix + quality].firstMatch
        XCTAssertTrue(target.waitForExistence(timeout: 10), "Connection must offer \(prefix + quality): " + app.debugDescription)
        var presses: [String] = []
        func focused() -> String? { focusedControlIdentifier(app) }
        // Down, then Up if Down passed the row, until focus is in it.
        while !(focused()?.hasPrefix(prefix) ?? false), presses.count < 16 {
            let below = focused().map { current in
                current.hasPrefix("dulcet.streaming-quality.metered.") && row == "unmetered"
            } ?? false
            XCUIRemote.shared.press(below ? .up : .down)
            presses.append(below ? "up" : "down")
        }
        let order = ["original", "320", "256", "192", "128", "96"]
        let wanted = try XCTUnwrap(order.firstIndex(of: quality))
        while let current = focused(), current.hasPrefix(prefix), current != prefix + quality, presses.count < 32 {
            let index = order.firstIndex(of: String(current.dropFirst(prefix.count))) ?? wanted
            let direction: XCUIRemote.Button = index < wanted ? .right : .left
            XCUIRemote.shared.press(direction)
            presses.append(direction == .right ? "right" : "left")
        }
        XCTAssertEqual(focused(), prefix + quality, "The remote must reach \(prefix + quality): " + app.debugDescription)
        XCUIRemote.shared.press(.select)
        let chosen = ContinuousClock.now.advanced(by: .seconds(5))
        while !target.isSelected, ContinuousClock.now < chosen { Thread.sleep(forTimeInterval: 0.1) }
        XCTAssertTrue(target.isSelected, "\(prefix + quality) must read as chosen once pressed: " + app.debugDescription)
        return presses.joined(separator: ",")
    }

    private struct LyricsLayer {
        let synced: Bool
        let lines: [String]
    }

    /// The server's own lyrics layers for a song, from `getLyricsBySongId`.
    private func readServerLyrics(songID: String, server: Server) -> [LyricsLayer]? {
        guard let list = restCall("getLyricsBySongId", [URLQueryItem(name: "id", value: songID)], server: server)?[
            "lyricsList"] as? [String: Any] else { return nil }
        return (list["structuredLyrics"] as? [[String: Any]] ?? []).map { layer in
            LyricsLayer(
                synced: layer["synced"] as? Bool ?? false,
                lines: (layer["line"] as? [[String: Any]] ?? []).compactMap { $0["value"] as? String }
            )
        }
    }

    /// One `/rest` call with the disposable account; the envelope's body on `ok`, else nil with
    /// the endpoint named -- never the URL, which carries a token.
    private func restCall(_ endpoint: String, _ query: [URLQueryItem], server: Server) -> [String: Any]? {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((server.password + salt).utf8))
            .map { String(format: "%02x", $0) }.joined()
        guard var components = URLComponents(string: server.url) else { return nil }
        let basePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = basePath + "/rest/" + endpoint
        components.queryItems = [
            URLQueryItem(name: "u", value: server.username),
            URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt),
            URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-ui-test"),
            URLQueryItem(name: "f", value: "json"),
        ] + query
        guard let url = components.url else { return nil }
        return Self.fetchJSON(URLRequest(url: url)).flatMap { document in
            guard let envelope = document["subsonic-response"] as? [String: Any],
                  envelope["status"] as? String == "ok" else {
                print("DULCET REST \(endpoint) did not return an ok envelope")
                return nil
            }
            return envelope
        }
    }

    /// One credential-free control request to the lyrics fault proxy.
    private func proxyControl(_ method: String, _ path: String, proxyURL: String) -> [String: Any]? {
        guard let url = URL(string: proxyURL + path) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = method
        return Self.fetchJSON(request)
    }

    private static func fetchJSON(_ request: URLRequest) -> [String: Any]? {
        final class Outcome: @unchecked Sendable { var data: Data? }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if error == nil, (response as? HTTPURLResponse)?.statusCode == 200 { outcome.data = data }
            done.signal()
        }
        task.resume()
        guard done.wait(timeout: .now() + 15) == .success else {
            task.cancel()
            return nil
        }
        return outcome.data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
    }

    private static func fileSize(_ path: String) -> UInt64? {
        guard let handle = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? handle.close() }
        return try? handle.seekToEnd()
    }

    /// The server's "Streaming file" lines written after `offset`, as their key=value fields.
    private static func streamLines(_ path: String, from offset: UInt64) -> [[String: String]] {
        guard let handle = FileHandle(forReadingAtPath: path) else { return [] }
        defer { try? handle.close() }
        guard (try? handle.seek(toOffset: offset)) != nil, let data = try? handle.readToEnd(),
              let text = String(data: data, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").filter { $0.contains("msg=\"Streaming file\"") }.map { line in
            var fields: [String: String] = [:]
            var rest = Substring(line)
            while let equals = rest.firstIndex(of: "=") {
                let key = rest[..<equals].split(separator: " ").last.map(String.init) ?? ""
                rest = rest[rest.index(after: equals)...]
                let value: Substring
                if rest.first == "\"" {
                    let body = rest.dropFirst()
                    let end = body.firstIndex(of: "\"") ?? body.endIndex
                    value = body[..<end]
                    rest = end < body.endIndex ? body[body.index(after: end)...] : ""
                } else {
                    let end = rest.firstIndex(of: " ") ?? rest.endIndex
                    value = rest[..<end]
                    rest = rest[end...]
                }
                if !key.isEmpty { fields[key] = String(value) }
            }
            return fields
        }
    }

    @MainActor
    private func waitForLabel(_ label: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while element.label != label, Date() < deadline {
            Thread.sleep(forTimeInterval: 0.1)
        }
        return element.label == label
    }

    /// Whether the server holds the named track of `album` as a favourite, read over
    /// `/rest/search3` with the disposable account. Nil, with the reason printed -- never the
    /// URL, which carries a token -- unless exactly one song matches.
    private func readServerSongStarred(
        _ track: String,
        album: String,
        server: (url: String, username: String, password: String)
    ) -> Bool? {
        // Subsonic carries `starred` only on a favourite.
        readServerSong(track, album: album, server: server).map { $0["starred"] != nil }
    }

    /// The server's rating of the named track of `album`, 0 when it carries none; nil as
    /// `readServerSongStarred`.
    private func readServerSongRating(
        _ track: String,
        album: String,
        server: (url: String, username: String, password: String)
    ) -> Int? {
        // Subsonic omits `userRating` on an unrated song.
        readServerSong(track, album: album, server: server).map { ($0["userRating"] as? Int) ?? 0 }
    }

    /// Polls until the server's rating for the track is `expected` or the timeout passes.
    private func awaitServerSongRating(
        _ track: String,
        album: String,
        server: (url: String, username: String, password: String),
        expected: Int,
        timeout: TimeInterval
    ) -> Int? {
        let deadline = Date().addingTimeInterval(timeout)
        var observed = readServerSongRating(track, album: album, server: server)
        while observed != nil, observed != expected, Date() < deadline {
            Thread.sleep(forTimeInterval: 1)
            observed = readServerSongRating(track, album: album, server: server)
        }
        return observed
    }

    /// The one song of `album` named `track`, as `/rest/search3` returns it.
    private func readServerSong(
        _ track: String,
        album: String,
        server: (url: String, username: String, password: String)
    ) -> [String: Any]? {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((server.password + salt).utf8))
            .map { String(format: "%02x", $0) }.joined()
        guard var components = URLComponents(string: server.url) else { return nil }
        let basePath = components.path.hasSuffix("/") ? String(components.path.dropLast()) : components.path
        components.path = basePath + "/rest/search3"
        components.queryItems = [
            URLQueryItem(name: "u", value: server.username),
            URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt),
            URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-ui-test"),
            URLQueryItem(name: "f", value: "json"),
            URLQueryItem(name: "query", value: track),
            URLQueryItem(name: "songCount", value: "20"),
            URLQueryItem(name: "albumCount", value: "0"),
            URLQueryItem(name: "artistCount", value: "0"),
        ]
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
            print("DULCET REST search3 timed out")
            return nil
        }
        guard let data = outcome.data,
              let document = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any],
              envelope["status"] as? String == "ok" else {
            print("DULCET REST search3 did not return an ok envelope")
            return nil
        }
        let songs = (envelope["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let matches = songs.filter { $0["title"] as? String == track && $0["album"] as? String == album }
        guard matches.count == 1, let match = matches.first else {
            print("DULCET REST search3 matched \(matches.count) songs named \(track) on \(album); exactly one is required")
            return nil
        }
        return match
    }

    /// Polls until the server's favourite state for the track is `expected` or the timeout passes.
    private func awaitServerSongStarred(
        _ track: String,
        album: String,
        server: (url: String, username: String, password: String),
        expected: Bool,
        timeout: TimeInterval
    ) -> Bool? {
        let deadline = Date().addingTimeInterval(timeout)
        var observed = readServerSongStarred(track, album: album, server: server)
        while observed != nil, observed != expected, Date() < deadline {
            Thread.sleep(forTimeInterval: 1)
            observed = readServerSongStarred(track, album: album, server: server)
        }
        return observed
    }

    /// The launch, then the injected account's live connection, confirmed where a person confirms
    /// it: Sign Out on Connection. A first launch stays on Connection while the account connects,
    /// and the connection lands on Library. A launch with an account already saved -- an earlier
    /// test on this simulator -- opens straight into that account's library (CONF-10b) and
    /// connects there. Either way Connection is then reached through the section bar, as a person
    /// reaches it, and the launch places remote focus on a named control inside the section, never
    /// on the bar, so the first press does something the person asked for.
    @MainActor
    private func awaitLaunchAndLiveConnection(
        _ app: XCUIApplication
    ) throws -> (section: String, focus: String) {
        let launchSections = ["Connection", "Library"]
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        var section = ""
        repeat {
            let bar = app.navigationBars.firstMatch
            section = bar.exists ? bar.identifier : ""
            if launchSections.contains(section) { break }
            Thread.sleep(forTimeInterval: 0.25)
        } while ContinuousClock.now < deadline
        XCTAssertTrue(
            launchSections.contains(section),
            "Launch must open Connection, or a saved account's Library; observed \(section.debugDescription): "
                + app.debugDescription
        )
        XCTAssertNil(focusedSection(app), "Launch focus belongs in the section, not on the bar")
        let focus = try XCTUnwrap(
            focusedControlIdentifier(app),
            "Launch must place remote focus on a named control: " + app.debugDescription
        )
        if section == "Connection" {
            // Which control is the section's business -- an idle form focuses its first field --
            // so this pins the section, not the row.
            XCTAssertTrue(
                focus.hasPrefix("dulcet.account-connect."),
                "Launch focus must be a Connection control, observed \(focus): " + app.debugDescription
            )
            // A connection asked for on Connection lands on the library it connected to.
            let landing = ContinuousClock.now.advanced(by: .seconds(60))
            while app.navigationBars.firstMatch.identifier != "Library", ContinuousClock.now < landing {
                Thread.sleep(forTimeInterval: 0.25)
            }
            XCTAssertEqual(
                app.navigationBars.firstMatch.identifier, "Library",
                "A connection made on Connection must land on Library: " + app.debugDescription
            )
            XCTAssertTrue(
                selectSection(app, "settings"),
                "The section bar must reach Connection from Library: " + app.debugDescription
            )
        } else {
            XCTAssertTrue(
                focus.hasPrefix("dulcet."),
                "Launch focus must be one of the library's own controls, observed \(focus): " + app.debugDescription
            )
            XCTAssertTrue(
                selectSection(app, "settings"),
                "The section bar must reach Connection from Library: " + app.debugDescription
            )
        }
        XCTAssertTrue(
            app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 60),
            "The live account connection must succeed before navigation is attempted: " + app.debugDescription
        )
        XCTAssertEqual(app.navigationBars.firstMatch.identifier, "Connection")
        return (section, focus)
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
