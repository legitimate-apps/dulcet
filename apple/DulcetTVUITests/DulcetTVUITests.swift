import CryptoKit
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

        // The heart: Down from the transport to the row under it, then Left if Down landed on
        // the stars or Lyrics to its right. Bounded and re-checked after every press, so a heart
        // the remote cannot reach fails here, with the presses it took in the pass line when it
        // does not.
        let heart = app.buttons["dulcet.now-playing.favorite"].firstMatch
        let lyrics = app.buttons["dulcet.now-playing.lyrics"].firstMatch
        let stars = (1...5).map { app.buttons["dulcet.now-playing.rating.star.\($0)"].firstMatch }
        XCTAssertTrue(heart.waitForExistence(timeout: 10), "Now Playing must offer the heart: " + app.debugDescription)
        XCTAssertTrue(stars.allSatisfy(\.exists), "Now Playing must offer five stars beside the heart: " + app.debugDescription)
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

        // Lyrics: after the stars, beside the heart's group.
        XCTAssertTrue(lyrics.exists, "Now Playing must offer lyrics: " + app.debugDescription)
        for _ in 0..<3 where !lyrics.hasFocus {
            XCUIRemote.shared.press(.right)
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
        print("DULCET TV NOW PLAYING PASS track=\(track.debugDescription) heart-presses=\(heartPresses.joined(separator: ","))"
            + " heart=remote-select starred=true->false lyrics=remote-select lit=\(lit.debugDescription)"
            + " setup=debug-account-only")
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
    /// it: Sign Out on Connection. A first launch stays on Connection while the account connects.
    /// A launch with an account already saved -- an earlier test on this simulator -- opens
    /// straight into that account's library (CONF-10b) and connects there; Connection is then
    /// reached through the section bar, as a person reaches it. Either way the launch places
    /// remote focus on a named control inside the section, never on the bar, so the first press
    /// does something the person asked for.
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
