import XCTest

final class DulcetiOSUITests: XCTestCase {
    /// A missing launch-screen declaration opts into the legacy 320-by-480 canvas.
    /// Compare the actual window with the display, independently of device resolution.
    @MainActor
    func testIPhoneWindowUsesFullDisplay() {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10))
        let display = XCUIScreen.main.screenshot().image.size
        let frame = window.frame
        print("DULCET DISPLAY GEOMETRY window=\(frame) displayImageSize=\(display)")
        print(app.debugDescription)
        XCTAssertGreaterThan(frame.width, 0)
        XCTAssertLessThan(frame.width, 700, "Run this full-display proof on an iPhone")
        XCTAssertEqual(frame.minX, 0, accuracy: 1)
        XCTAssertEqual(frame.minY, 0, accuracy: 1)
        XCTAssertEqual(
            frame.height / frame.width, display.height / display.width, accuracy: 0.01,
            "The app window must fill the portrait display without legacy letterboxing"
        )

        // A full-size empty window is not evidence that the account interface rendered.
        // Scope both semantic queries to this window, then require visible, contained frames.
        let content: [(String, XCUIElement)] = [
            ("dulcet.account-connect.title",
             window.staticTexts["dulcet.account-connect.title"].firstMatch),
            ("dulcet.account-connect.server-address",
             window.textFields["dulcet.account-connect.server-address"].firstMatch),
        ]
        for (identifier, element) in content {
            guard element.waitForExistence(timeout: 5) else {
                XCTFail("Full-display content missing from measured window: \(identifier)")
                continue
            }
            let contentFrame = element.frame
            print("DULCET DISPLAY CONTENT id=\(identifier) frame=\(contentFrame) window=\(frame) hittable=\(element.isHittable)")
            XCTAssertGreaterThan(contentFrame.width, 0, "\(identifier) must have visible width")
            XCTAssertGreaterThan(contentFrame.height, 0, "\(identifier) must have visible height")
            XCTAssertTrue(element.isHittable, "\(identifier) must be visible and reachable")
            XCTAssertTrue(
                frame.contains(contentFrame),
                "\(identifier) frame \(contentFrame) must lie inside measured window \(frame)"
            )
        }
    }

    /// A compact iPhone window shows one column at a time, so reaching a destination, using the
    /// navigation bar's back control, and choosing that same destination again is an ordinary
    /// path -- and it was a dead end. OBSERVED on an iPhone 17 Pro simulator before the fix, and
    /// repeatably: the second choice left the detail unpushed, the row highlighted, and the only
    /// way forward was choosing some other destination.
    ///
    /// MEASURED mechanism: the sidebar list's selection binding reports the store's destination,
    /// which the back control does not change, so the second choice reads back as an unchanged
    /// value and SwiftUI infers no push. The binding's setter does still run for that choice --
    /// established by the fix, which is one line inside that setter asking for the detail column.
    /// What SwiftUI writes into the selection on the way back was NOT measured, and a first
    /// attempt built on assuming a `nil` there was measured not to carry the fix.
    ///
    /// This uses the deterministic layout fixture rather than the disposable server: the contract
    /// under test is navigation, and a fixture makes the run independent of any server state.
    @MainActor
    func testCompactSidebarRestoresTheDetailForTheSameDestination() {
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertLessThan(
            window.frame.width,
            700,
            "This proof requires a compact-width iPhone window; a regular-width window shows both"
                + " columns at once and cannot express the defect"
        )

        let searchRow = app.staticTexts["dulcet.sidebar.search"].firstMatch
        let searchField = app.textFields["dulcet.search.field"].firstMatch

        // Reveals the sidebar the way a person does, and reports which control it used so a
        // failure names the control rather than only its effect. Returns whether the back control
        // was needed: on a compact window the detail is showing at both call sites, so a run that
        // found the sidebar already open did not exercise the path this proof is about, and the
        // caller asserts that rather than accepting a pass that skipped it.
        func revealSidebar(_ phase: String) -> (reached: Bool, usedBackControl: Bool) {
            var usedBackControl = false
            if !searchRow.isHittable {
                let backControl = app.navigationBars.buttons.firstMatch
                guard backControl.waitForExistence(timeout: 5) else {
                    XCTFail("\(phase): a compact window must expose the sidebar through a back"
                        + " control: " + app.debugDescription)
                    return (false, false)
                }
                print("DULCET COMPACT NAV \(phase) back-control=\(backControl.identifier)")
                backControl.tap()
                usedBackControl = true
            }
            guard searchRow.waitForExistence(timeout: 5), searchRow.isHittable else {
                XCTFail("\(phase): the Search row must be reachable in the sidebar: "
                    + app.debugDescription)
                return (false, usedBackControl)
            }
            return (true, usedBackControl)
        }

        let firstReveal = revealSidebar("first")
        guard firstReveal.reached else { return }
        XCTAssertTrue(
            firstReveal.usedBackControl,
            "A compact window opens on the detail column, so reaching the sidebar must have gone"
                + " through the back control; a run that skipped it did not set up this proof"
        )
        searchRow.tap()
        XCTAssertTrue(
            searchField.waitForExistence(timeout: 5),
            "Choosing Search must show the Search detail: " + app.debugDescription
        )

        let secondReveal = revealSidebar("second")
        guard secondReveal.reached else { return }
        XCTAssertTrue(
            secondReveal.usedBackControl,
            "The Search detail must have been showing before the back control was used; without"
                + " that, the re-selection below is not the case this proof is about"
        )
        // The store's selected destination is still Search here. That is the whole point: the
        // second choice must push the detail again even though the value does not change.
        XCTAssertFalse(
            searchField.exists,
            "The back control must leave the sidebar showing, not the Search detail: "
                + app.debugDescription
        )
        searchRow.tap()
        let reselectPushed = searchField.waitForExistence(timeout: 5)
        // Print the observed value, not a verdict: a bare "PASS" line printed after an assertion
        // that already failed is a claim nothing checked.
        print("DULCET COMPACT NAV OBSERVED"
            + " first-back=\(firstReveal.usedBackControl) second-back=\(secondReveal.usedBackControl)"
            + " reselect-push=\(reselectPushed)")
        XCTAssertTrue(
            reselectPushed,
            "Choosing the destination already selected must show its detail again, not strand the"
                + " person on the sidebar: " + app.debugDescription
        )
    }

    private enum BlockingSystemDialogProbeResult {
        case absent
        case handled
        case unsupported
    }

    private struct LivePlaybackConfiguration {
        let serverURL: String
        let username: String
        let password: String
    }

    private struct PlaybackProgressSample {
        let elapsed: TimeInterval
        let duration: TimeInterval
        let accessibilityValue: String
    }

    private enum SearchUIWindowExpectation {
        case compactWidth
        case regularWidth
    }

    /// Simulator evidence for the `platform-observed-search-activation` gate on the iOS cell: a
    /// query typed through the app's own UI reaches the search field, ranked results render with
    /// the disposable corpus's canary track at rank zero, and activating it starts the
    /// search-sourced queue -- observed through the Now Playing surface (the activated track's
    /// title, the "Playing from Search" source line) and the progress slider that only exists
    /// once media time is actually advancing.
    @MainActor
    func testSimulatorSearchQueryRanksAndActivatesTrackOnIPhone() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This search proof requires an iPhone simulator; a physical device is not valid evidence")
            return
        }

        proveSearchQueryRanksAndActivatesTrack(windowExpectation: .compactWidth)
    }

    /// The same typed-query-to-Now-Playing proof on the regular-width iPad split layout, where
    /// the sidebar and detail are visible at once. Recorded against the iPadOS cell: a compact
    /// window here is a wrong-destination failure, not a pass.
    @MainActor
    func testSimulatorSearchQueryRanksAndActivatesTrackOnIPadOS() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This search proof requires an iPad simulator; a physical device is not valid evidence")
            return
        }

        proveSearchQueryRanksAndActivatesTrack(windowExpectation: .regularWidth)
    }

    @MainActor
    private func proveSearchQueryRanksAndActivatesTrack(
        windowExpectation: SearchUIWindowExpectation
    ) {
        guard let configuration = livePlaybackConfiguration() else { return }
        // A query matching exactly one row cannot separate rank from arity: with a single result,
        // "rank zero" and "the only row" are the same assertion, and so are "activate the row that
        // was pressed" and "activate the first result". This query matches four rows and places
        // the canary at a non-zero rank, so both distinctions become observable.
        let query = "Threshold"
        let canaryTitle = "UI Playback Canary"
        let canaryRank = 2
        // The rendered order is a product contract, not a server one: results are ranked by match
        // quality first, then by kind with tracks ahead of albums, then by the order the server
        // returned them. The server lists the matching album ahead of every track; the app does
        // not. Asserting each rank's identity makes drift in either fail here, naming what it
        // observed, rather than silently relocating the canary to another rank.
        //
        // OBSERVED: this query matches four rows, the fourth being the matching album, and the
        // results list materializes its rows lazily -- so the album row is not addressable on a
        // compact window. These three ranks are asserted on every destination; the rendered
        // result count below pins the full arity without depending on an offscreen row, and the
        // macOS control, whose table renders every row at once, pins the album's rank.
        let rankedLabels = [
            "Thirty One Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "Twenty Nine Seconds, Dulcet Fixtures · Threshold Boundary, Track",
            "UI Playback Canary, Dulcet Fixtures · Threshold Boundary, Track",
        ]
        let renderedResultCount = "4 results"

        let app = XCUIApplication()
        app.launchArguments += [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url",
            configuration.serverURL,
            "-dulcet-debug-account-username",
            configuration.username,
            "-dulcet-debug-account-password",
            configuration.password,
        ]
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        switch windowExpectation {
        case .compactWidth:
            XCTAssertLessThanOrEqual(
                window.frame.width,
                700,
                "This proof requires a compact-width iPhone window; an iPad is invalid evidence"
            )
        case .regularWidth:
            XCTAssertGreaterThan(
                window.frame.width,
                700,
                "This proof requires a regular-width iPad window; an iPhone is invalid evidence"
            )
        }

        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live account connection must succeed before search is attempted")
            return
        }

        // staticTexts, not descendants(matching: .any): the sidebar row's identifier is carried
        // by both its SF Symbol image and its label, so an .any query resolves ambiguously.
        let searchRow = app.staticTexts["dulcet.sidebar.search"].firstMatch
        if !searchRow.isHittable {
            // Compact width opens on the detail column; the sidebar sits behind the detail's
            // back control, exactly as a person reaches it on an iPhone.
            let backControl = app.navigationBars.buttons.firstMatch
            guard backControl.waitForExistence(timeout: 5) else {
                XCTFail(
                    "A compact-width window must expose the sidebar through a back control: "
                        + app.debugDescription
                )
                return
            }
            backControl.tap()
        }
        // Existence and hittability are separate outcomes and were previously reported by one
        // message, so a run could not distinguish "the back control never revealed the sidebar"
        // from "the row is on screen but covered". MEASURED over 39 CI executions of this test:
        // every successful run satisfied this wait on its FIRST poll, about 1.0 s into a 5 s
        // budget, and the one failure consumed all five polls. The budget is not marginal, so a
        // failure here means the navigation did not happen -- never that the wait was too short.
        guard searchRow.waitForExistence(timeout: 5) else {
            XCTFail("The Search row must exist in the sidebar: " + app.debugDescription)
            return
        }
        guard searchRow.isHittable else {
            XCTFail(
                "The Search row must be visible in the sidebar; frame=\(searchRow.frame)"
                    + " window=\(window.frame): " + app.debugDescription
            )
            return
        }
        searchRow.tap()

        let searchField = app.textFields["dulcet.search.field"].firstMatch
        // Same measurement as above: 38 of 38 successful CI executions resolved this on the first
        // poll. Exhausting the budget means the Search destination never rendered.
        guard searchField.waitForExistence(timeout: 5) else {
            XCTFail("The search field must exist on the Search destination: " + app.debugDescription)
            return
        }
        searchField.tap()
        searchField.typeText(query)
        // The field's value settles asynchronously, and `typeText` returning does not mean every
        // synthesized keystroke has been delivered and rendered -- XCUITest's own post-typing idle
        // wait is not that guarantee. Reading `.value` once therefore conflated "the field lost
        // characters" with "the sample was early", and one apple-ci run failed on the second.
        //
        // MEASURED on an iPad Pro 11-inch (M5) simulator with per-keystroke app cost induced at
        // 0, 20, 60, 150, 400 and 1000 ms, tracing the app's own text binding: all 9 keystrokes
        // reach it in order in every case, and across 84 keystrokes no value ever regressed. So a
        // short read is the sample being early. The 400 ms run reproduced the CI failure exactly
        // -- a prefix, everything after it passing -- while the trace showed the field completing
        // 3.8 s later.
        //
        // This polls the same assertion instead of sampling it. A field that genuinely drops
        // characters still fails, because the poll requires the full query and has an end; what it
        // no longer does is fail for being late. The attempt count is printed rather than
        // asserted: zero is the normal outcome, and a run that needed many is a responsiveness
        // signal worth seeing in the log rather than a reason to fail.
        //
        // Monotonic, per this repository's clock rule: a wall-clock deadline can be moved by the
        // host while the poll is running.
        //
        // The samples carry their own process check, matching the tvOS control in
        // DulcetTVUITests: an incremental commit produces non-shrinking prefixes of the query, so
        // a sample that is not a prefix, or one that goes backwards, is a different defect and
        // says so rather than being counted as "still settling".
        var settleSamples: [String] = []
        var observedFieldValue = searchField.value as? String ?? ""
        settleSamples.append(observedFieldValue)
        let settleClock = ContinuousClock()
        let settleDeadline = settleClock.now.advanced(by: .seconds(10))
        while observedFieldValue != query, settleClock.now < settleDeadline {
            Thread.sleep(forTimeInterval: 0.1)
            observedFieldValue = searchField.value as? String ?? ""
            settleSamples.append(observedFieldValue)
        }
        print("DULCET SEARCH TYPING OBSERVED settle-attempts=\(settleSamples.count - 1)"
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
            "The typed text must reach the search field's own value;"
                + " polled \(settleSamples.count - 1) times over 10 s"
        )

        let firstResult = app.buttons["dulcet.search.result.0"].firstMatch
        // The check above reads the text field, and MEASURED: with the field's binding replaced
        // by a constant empty string -- so the app can never hold the query -- it still reported
        // "Threshold" and passed, and this wait is where the run failed instead. A text field
        // being edited reports what is on screen, not what the app's state received, so this is
        // the first assertion that depends on the query having actually reached the app. Its
        // message must therefore not blame the server for a query that never arrived.
        guard firstResult.waitForExistence(timeout: 30) else {
            XCTFail(
                "No ranked results for the typed query. Either the query never reached the app's"
                    + " state -- the field's own value is not proof that it did -- or the"
                    + " disposable server did not answer: " + app.debugDescription
            )
            return
        }
        // A person dismisses the software keyboard before activating a result; the test must do
        // the same, because the occluding keyboard window eats the hit test. Measured on iPhone
        // 17 Pro: the rank-zero row's midpoint sat at y=500 under a keyboard whose top edge was
        // y=472, so tapping without dismissing is not a real activation path.
        guard dismissKeyboardBeforeActivation(in: app) else { return }

        // Every rank is addressed by its own identifier and checked against the row that belongs
        // there. A view that stamped one constant identifier on every row would satisfy rank zero
        // and then fail to produce rank one at all.
        var rankedResults: [XCUIElement] = []
        for rank in rankedLabels.indices {
            let result = app.buttons["dulcet.search.result.\(rank)"].firstMatch
            guard result.waitForExistence(timeout: 10) else {
                XCTFail("Rank \(rank) must render its own identifier: " + app.debugDescription)
                return
            }
            XCTAssertEqual(
                result.label,
                rankedLabels[rank],
                "Rank \(rank) rendered accessibility text (title, credits, album, kind)"
            )
            rankedResults.append(result)
        }
        // Captured while the rows are still on screen: after activation the search surface is
        // replaced and re-reading these elements throws rather than returning a stale value.
        let observedLabels = rankedResults.map(\.label)
        XCTAssertNotEqual(
            rankedResults[0].label,
            rankedLabels[canaryRank],
            "The canary must not render at rank zero, or this proof cannot tell rank from arity"
        )
        // The app's own count of the ranked list. Asserting it keeps the arity pinned even
        // though the list renders lazily, so a query that starts matching a different number of
        // rows fails here instead of quietly changing which rank the canary occupies.
        XCTAssertTrue(
            app.staticTexts[renderedResultCount].firstMatch.exists,
            "The results header must report \(renderedResultCount): " + app.debugDescription
        )

        // Scroll the results container only when the canary is outside the visible window.
        // The legacy letterboxed iPhone canvas needed this; a full-display iPhone may expose
        // every row already. Keep the reachability check for smaller windows and keyboards,
        // and preserve the rank assertions above independently of any scrolling.
        let canaryResult = rankedResults[canaryRank]
        let resultsList = app.scrollViews.firstMatch
        guard resultsList.waitForExistence(timeout: 5) else {
            XCTFail("The ranked results must render in a scrollable list: " + app.debugDescription)
            return
        }
        var scrollAttempts = 0
        while scrollAttempts < 6 && !isReachableForTap(canaryResult, in: window) {
            resultsList.swipeUp()
            scrollAttempts += 1
        }

        guard canaryResult.isHittable else {
            // Frame diagnostics make an occlusion report actionable from the CI log alone:
            // a covered row and an offscreen row are different defects with the same symptom.
            let keyboard = app.keyboards.firstMatch
            print(
                "DULCET SEARCH UI DIAG result-frame=\(canaryResult.frame)"
                    + " window-frame=\(window.frame)"
                    + " keyboard-exists=\(keyboard.exists)"
                    + " keyboard-frame=\(keyboard.exists ? String(describing: keyboard.frame) : "none")"
            )
            XCTFail("The rank-\(canaryRank) result must be hittable so activation is a real tap")
            return
        }

        canaryResult.tap()

        let nowPlayingTitle = app.staticTexts["dulcet.now-playing.title"].firstMatch
        // Successful CI executions resolve this in one to four polls of the fifteen available,
        // so exhausting the budget means activation produced no navigation at all -- which the
        // tree below distinguishes from "Now Playing rendered a state without a title".
        guard nowPlayingTitle.waitForExistence(timeout: 15) else {
            XCTFail(
                "Activating rank \(canaryRank) must present the Now Playing surface: "
                    + app.debugDescription
            )
            return
        }
        // Now Playing showing rank zero's track here would mean activation played the first
        // result rather than the row that was pressed. That is why a non-zero rank is pressed.
        XCTAssertEqual(
            nowPlayingTitle.label,
            canaryTitle,
            "Now Playing must show the row that was activated, not the first result"
        )
        XCTAssertTrue(
            app.staticTexts["Playing from Search"].firstMatch.waitForExistence(timeout: 5),
            "The Now Playing source line must report the search-sourced queue"
        )
        XCTAssertTrue(
            app.sliders["Now Playing"].firstMatch.waitForExistence(timeout: 30),
            "Real playback of the activated track must begin and expose progressing media time"
        )
        print(
            "DULCET SEARCH UI PASS width=\(windowExpectation == .compactWidth ? "compact" : "regular")"
                + " query=typed ranks=\(observedLabels)"
                + " activated-rank=\(canaryRank) activation=tap source=search now-playing=\(canaryTitle)"
        )
    }

    /// Proves the iPad renders the regular-width split: sidebar and detail visible at once, in
    /// separate columns. An iPhone cannot satisfy this, which is the point -- a test that passed on
    /// both would let the iPadOS cell claim evidence it does not have.
    @MainActor
    func testAccountConnectUsesRegularWidthSplitLayout() {
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()
        print("DULCET IPADOS APP LAUNCH PASS layout-assertions-starting=true")

        // Guard on window width, not XCUIApplication.horizontalSizeClass: that attribute reports
        // .unspecified for the application element (OBSERVED on iPad Pro 13-inch, rawValue 0), so
        // asserting .regular against it fails on the very device it is meant to accept. A regular
        // split needs a window far wider than any iPhone.
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertGreaterThan(
            window.frame.width,
            700,
            "This proof requires a regular-width window; an iPhone destination is invalid evidence"
        )

        // staticTexts, not descendants(matching: .any): the row's identifier is carried by BOTH its
        // SF Symbol image and its label (OBSERVED in the captured hierarchy), so an .any query is
        // ambiguous and resolves against the decorative image, whose isHittable is false.
        let sidebar = app.staticTexts["dulcet.sidebar.library"].firstMatch
        let detail = app.staticTexts["dulcet.account-connect.title"].firstMatch

        XCTAssertTrue(sidebar.waitForExistence(timeout: 10), "The Library row must be in the sidebar")
        XCTAssertTrue(detail.waitForExistence(timeout: 10), "The account-connect heading must be in the detail column")
        XCTAssertTrue(sidebar.isHittable, "The sidebar must be visible, not retained offscreen")
        XCTAssertTrue(detail.isHittable, "The detail must be visible at the same time as the sidebar")
        XCTAssertLessThan(
            sidebar.frame.maxX,
            detail.frame.minX,
            "Regular-width layout must place the sidebar and detail in separate visible columns"
        )
    }

    /// Device-only evidence that the production account, library, stream, audio engine, and
    /// progressing-media-time scrobble path can be driven through the iPad UI. The server-side
    /// play-count assertion intentionally remains outside XCUITest so the app remains the only
    /// playback actor and the test cannot independently submit a scrobble.
    @MainActor
    func testRealDevicePlaybackAdvancesPastScrobbleThreshold() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] == nil else {
            XCTFail("This playback proof requires a physical iPad; a simulator is not valid evidence")
            return
        }

        proveLivePlaybackAdvancesPastScrobbleThreshold()
    }

    /// Simulator-only evidence for the same live account, library, stream, audio engine, and
    /// progressing-media-time scrobble path exercised by the physical-iPad proof.
    @MainActor
    func testSimulatorPlaybackAdvancesPastScrobbleThreshold() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("This playback proof requires an iPad simulator; a physical device is not valid evidence")
            return
        }

        proveLivePlaybackAdvancesPastScrobbleThreshold(usingInjectedAccount: true)
    }

    @MainActor
    private func proveLivePlaybackAdvancesPastScrobbleThreshold(
        usingInjectedAccount: Bool = false
    ) {
        guard let configuration = livePlaybackConfiguration() else { return }

        let app = XCUIApplication()
        // The app's DEBUG delivery marker. The Now Playing slider shows the threshold; nothing in
        // the product shows delivery, and delivery is the event the workflow's play-count read
        // depends on. Without it this proof twice returned on the displayed threshold while the
        // app's own accumulator (counted from its first sampled position) had not crossed, and
        // XCUITest's teardown killed the app before its next sample (apple-ci 2026-09-06 and
        // 2026-09-11: NowPlaying logged, no Scrobbled line, count stayed 0, test green).
        app.launchArguments += ["-dulcet-debug-scrobble-delivery-marker"]
        if usingInjectedAccount {
            app.launchArguments += [
                "-dulcet-debug-connect-account",
                "-dulcet-debug-account-server-url",
                configuration.serverURL,
                "-dulcet-debug-account-username",
                configuration.username,
                "-dulcet-debug-account-password",
                configuration.password,
            ]
        } else {
            installSystemAlertInterruptionMonitors()
        }
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertGreaterThan(
            window.frame.width,
            700,
            "This playback proof requires a regular-width iPad window; an iPhone is invalid evidence"
        )

        if !usingInjectedAccount {
            let serverField = app.textFields["dulcet.account-connect.server-address"].firstMatch
            let usernameField = app.textFields["dulcet.account-connect.username"].firstMatch
            let passwordField = app.secureTextFields["dulcet.account-connect.password"].firstMatch
            guard replaceText(in: serverField, with: configuration.serverURL, name: "server address"),
                  replaceText(in: usernameField, with: configuration.username, name: "username"),
                  replaceText(
                    in: passwordField,
                    with: configuration.password,
                    name: "password",
                    secure: true
                  ) else { return }
            guard dismissKeyboardIfPresent(in: app) else { return }

            if configuration.serverURL.lowercased().hasPrefix("http://") {
                // The physical-device accessibility service describes this as Button, Toggle,
                // while XCUITest exposes neither a Button nor a Switch. Match its semantic label
                // and binary value across element types, then guard uniqueness so a decorative
                // child cannot win.
                let localHTTPControls = app.descendants(matching: .any).matching(
                    NSPredicate(
                        format: "label == %@ AND (value == %@ OR value == %@)",
                        "Allow HTTP on this local network",
                        "0",
                        "1"
                    )
                )
                let allowLocalHTTP = localHTTPControls.firstMatch
                guard allowLocalHTTP.waitForExistence(timeout: 5) else {
                    XCTFail("The local-HTTP consent control must exist for a cleartext disposable server")
                    return
                }
                XCTAssertEqual(
                    localHTTPControls.count,
                    1,
                    "The local-HTTP consent query must resolve to exactly one semantic control"
                )
                guard scrollIntoView(allowLocalHTTP, in: app) else {
                    XCTFail("The local-HTTP consent control must be hittable before it is changed")
                    return
                }
                if (allowLocalHTTP.value as? String) != "1" {
                    allowLocalHTTP.coordinate(
                        withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)
                    ).tap()
                }
                guard waitForValue("1", of: allowLocalHTTP, timeout: 5) else {
                    XCTFail("The local-HTTP consent control must visibly change from off to on")
                    return
                }
            }

            let connectAction = app.buttons["dulcet.account-connect.primary-action"].firstMatch
            guard connectAction.waitForExistence(timeout: 5) else {
                XCTFail("The account primary action must exist")
                return
            }
            guard connectAction.isEnabled else {
                XCTFail("The account primary action must enable after all required values are entered")
                return
            }
            connectAction.tap()
            allowLocalNetworkAccessIfRequested()
        }

        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live account connection must succeed before playback is attempted")
            return
        }

        // Negative control, before any playback: the marker must be live and read three zeros,
        // each excluding a different way a later "delivered=1" could be credited to the wrong
        // event. The counts are per process; only `pending` reads the durable outbox.
        //   delivered=0  this launch has not yet delivered anything (a leftover row drained on
        //                configureDelivery would already show here);
        //   pending=0    no row survives from an earlier launch on a reused simulator, so a
        //                drain that has not happened yet cannot supply the 1 either;
        //   persisted=0  this launch has not itself persisted a play (a crossing before the
        //                account settled, or a restored session, would show here).
        let deliveryMarker = app.staticTexts["dulcet.debug.scrobble-delivery"].firstMatch
        guard deliveryMarker.waitForExistence(timeout: 10) else {
            XCTFail("The app's scrobble delivery marker must exist when its launch argument is passed")
            return
        }
        guard let baseline = waitForScrobbleDeliveryCounts(
            in: deliveryMarker,
            timeout: 10,
            until: { $0["delivered"] != nil }
        ) else {
            XCTFail("The scrobble delivery marker must report counts; last label: \(deliveryMarker.label)")
            return
        }
        XCTAssertEqual(baseline["delivered"], 0, "No play may be delivered before playback starts")
        XCTAssertEqual(baseline["pending"], 0, "No play may be waiting in the outbox from an earlier launch")
        XCTAssertEqual(baseline["persisted"], 0, "No play may be persisted by this launch before playback")
        guard baseline["delivered"] == 0, baseline["pending"] == 0, baseline["persisted"] == 0 else {
            return
        }

        // staticTexts avoids the duplicate Image/StaticText identifier carried by sidebar Labels.
        let library = app.staticTexts["dulcet.sidebar.library"].firstMatch
        guard library.waitForExistence(timeout: 5) else {
            XCTFail("The Library row must exist in the iPad sidebar")
            return
        }
        library.tap()

        let thresholdAlbum = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "Threshold Boundary")
        ).firstMatch
        guard thresholdAlbum.waitForExistence(timeout: 30) else {
            XCTFail("The disposable server must expose the Threshold Boundary album")
            return
        }
        guard scrollIntoView(
            thresholdAlbum,
            in: app,
            probingBlockingSystemAlerts: !usingInjectedAccount
        ) else {
            XCTFail("The threshold canary album must be reachable in the library")
            return
        }
        thresholdAlbum.tap()

        let thresholdTrack = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "UI Playback Canary")
        ).firstMatch
        guard thresholdTrack.waitForExistence(timeout: 10) else {
            XCTFail("The disposable server must expose the dedicated eligible UI playback canary")
            return
        }
        guard scrollIntoView(
            thresholdTrack,
            in: app,
            probingBlockingSystemAlerts: !usingInjectedAccount
        ) else {
            XCTFail("The scrobble canary track must be reachable")
            return
        }
        thresholdTrack.tap()

        let progress = app.sliders["Now Playing"].firstMatch
        guard progress.waitForExistence(timeout: 30) else {
            XCTFail("Real playback must begin and expose progressing media time in Now Playing")
            return
        }
        guard let finalSample = waitUntilPastScrobbleThreshold(progress) else { return }
        let threshold = min(finalSample.duration * 0.5, 4 * 60)
        XCTAssertGreaterThanOrEqual(
            finalSample.duration,
            30,
            "The server-reported or decoded duration must be eligible for scrobbling"
        )
        XCTAssertGreaterThan(
            finalSample.elapsed,
            threshold,
            "Observed progressing media time must move past the §15.2 scrobble threshold"
        )

        // The displayed position and the accumulator are different quantities with half a second
        // between them on this track, so the threshold is necessary, never sufficient. Return only
        // once the app reports the server acknowledged `submission=true`; XCUITest kills the app
        // when this method returns, and a play that has not left by then never leaves in CI.
        // The budget covers the next 500 ms position sample, the request, and a loaded host; it
        // is not a retry window, because the facade schedules no in-process retry.
        guard let delivered = waitForScrobbleDeliveryCounts(
            in: deliveryMarker,
            timeout: 30,
            until: { ($0["delivered"] ?? 0) >= 1 }
        ) else {
            XCTFail(
                "The app must report the scrobble delivered before this proof returns; last marker: \(deliveryMarker.label)"
            )
            return
        }
        XCTAssertEqual(delivered["delivered"], 1, "Exactly one play is expected for one crossing")
        XCTAssertEqual(delivered["failures"], 0, "No delivery attempt may have failed")
    }

    private func livePlaybackConfiguration() -> LivePlaybackConfiguration? {
        let serverURL = runtimeValue(
            environment: "DULCET_UI_TEST_SERVER_URL",
            argument: "-dulcet-ui-test-server-url"
        )
        let username = runtimeValue(
            environment: "DULCET_UI_TEST_USERNAME",
            argument: "-dulcet-ui-test-username"
        )
        let password = runtimeValue(
            environment: "DULCET_UI_TEST_PASSWORD",
            argument: "-dulcet-ui-test-password"
        )

        if serverURL == nil {
            XCTFail("Missing server URL; set DULCET_UI_TEST_SERVER_URL or -dulcet-ui-test-server-url")
        }
        if username == nil {
            XCTFail("Missing username; set DULCET_UI_TEST_USERNAME or -dulcet-ui-test-username")
        }
        if password == nil {
            XCTFail("Missing password; set DULCET_UI_TEST_PASSWORD or -dulcet-ui-test-password")
        }
        guard let serverURL, let username, let password else { return nil }
        return LivePlaybackConfiguration(serverURL: serverURL, username: username, password: password)
    }

    private func runtimeValue(environment key: String, argument flag: String) -> String? {
        let environmentValue = ProcessInfo.processInfo.environment[key]?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let environmentValue, !environmentValue.isEmpty {
            return environmentValue
        }

        let arguments = ProcessInfo.processInfo.arguments
        guard let flagIndex = arguments.firstIndex(of: flag),
              arguments.indices.contains(flagIndex + 1) else { return nil }
        let argumentValue = arguments[flagIndex + 1].trimmingCharacters(in: .whitespacesAndNewlines)
        return argumentValue.isEmpty ? nil : argumentValue
    }

    @MainActor
    private func replaceText(
        in field: XCUIElement,
        with value: String,
        name: String,
        secure: Bool = false
    ) -> Bool {
        guard field.waitForExistence(timeout: 5) else {
            XCTFail("The \(name) field must exist")
            return false
        }
        field.tap()
        field.typeKey("a", modifierFlags: .command)
        field.typeText(value)

        // URL fields can occasionally preserve a stale insertion point across app relaunches on
        // physical iPadOS. Verify the resulting value and make one fresh selection/replacement.
        if !secure, (field.value as? String) != value {
            field.tap()
            field.typeKey("a", modifierFlags: .command)
            field.typeKey(.delete, modifierFlags: [])
            field.typeText(value)
        }

        let enteredValue = field.value as? String
        let didEnterValue = secure ? enteredValue?.count == value.count : enteredValue == value
        guard didEnterValue else {
            XCTFail("The \(name) field must contain exactly the supplied runtime value")
            return false
        }
        return true
    }

    @MainActor
    private func dismissKeyboardIfPresent(in app: XCUIApplication) -> Bool {
        let keyboard = app.keyboards.firstMatch
        guard keyboard.exists else { return true }
        let hideKeyboard = keyboard.buttons["Hide keyboard"].firstMatch
        if hideKeyboard.waitForExistence(timeout: 2) {
            hideKeyboard.tap()
        } else {
            keyboard.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.95)).tap()
        }
        guard keyboard.waitForNonExistence(timeout: 5) else {
            XCTFail("The software keyboard must dismiss before the account controls are driven")
            return false
        }
        return true
    }

    @MainActor
    private func dismissKeyboardBeforeActivation(in app: XCUIApplication) -> Bool {
        let keyboard = app.keyboards.firstMatch
        guard keyboard.exists else { return true }
        // The search field carries the platform's search submit key, which resigns the field on
        // both iPhone and iPad. The iPad-only hide key and a results drag remain fallbacks.
        let submit = keyboard.buttons["Search"].firstMatch
        let hideKeyboard = keyboard.buttons["Hide keyboard"].firstMatch
        if submit.waitForExistence(timeout: 2) {
            submit.tap()
        } else if hideKeyboard.waitForExistence(timeout: 2) {
            hideKeyboard.tap()
        } else {
            app.swipeDown()
        }
        guard keyboard.waitForNonExistence(timeout: 5) else {
            XCTFail("The software keyboard must dismiss before a result is activated")
            return false
        }
        return true
    }

    /// A row is reachable when the hit point XCUITest would use for a tap -- its midpoint -- is
    /// inside the window, not merely when the element exists. A row that begins on screen and
    /// extends past the bottom edge satisfies `exists` while its midpoint does not.
    @MainActor
    private func isReachableForTap(_ element: XCUIElement, in window: XCUIElement) -> Bool {
        guard element.exists, window.exists else { return false }
        let frame = element.frame
        guard !frame.isEmpty, !frame.isInfinite else { return false }
        return window.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) && element.isHittable
    }

    @MainActor
    private func scrollIntoView(
        _ element: XCUIElement,
        in app: XCUIApplication,
        probingBlockingSystemAlerts: Bool = true
    ) -> Bool {
        let window = app.windows.firstMatch
        guard window.exists else {
            XCTFail("Dulcet's app window is unavailable because the app terminated or was backgrounded")
            return false
        }

        var swipeCount = 0
        while true {
            if probingBlockingSystemAlerts {
                let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
                // An application-level swipe always resolves a hit point, even while SpringBoard
                // is presenting an alert, so XCTest has no blocked action with which to invoke an
                // interruption monitor. Alert handling does not consume a scroll attempt.
                switch dismissBlockingSystemAlertIfPresent(in: springboard) {
                case .handled:
                    continue
                case .unsupported:
                    return false
                case .absent:
                    break
                }
            }

            if element.exists {
                let frame = element.frame
                let midpoint = CGPoint(x: frame.midX, y: frame.midY)
                if !frame.isEmpty && !frame.isInfinite &&
                    window.frame.contains(midpoint) && element.isHittable {
                    return true
                }
            }

            guard swipeCount < 6 else { return false }

            // This must remain a real event rather than an isHittable-gated preflight. Tap paths
            // can still invoke the interruption monitors installed as a backstop.
            app.swipeUp()
            swipeCount += 1
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
    }

    @MainActor
    private func dismissBlockingSystemAlertIfPresent(
        in springboard: XCUIApplication
    ) -> BlockingSystemDialogProbeResult {
        // The password-save surface is not exposed as a SpringBoard alert on every runtime. Its
        // dismissal buttons are exposed directly on the SpringBoard process, however. Query only
        // known decline labels: falling back to an arbitrary SpringBoard button can background the
        // app or drive unrelated system UI.
        let knownDeclineLabels = ["Not Now", "Never", "No Thanks", "Don't Save", "Don’t Save"]
        let knownDecline = springboard.buttons.matching(
            NSPredicate(format: "label IN %@", knownDeclineLabels)
        ).firstMatch
        if knownDecline.waitForExistence(timeout: 3) {
            let title = knownDecline.label
            knownDecline.tap()
            print("DULCET_UI_SYSTEM_ALERT handled=proactive button=\(title)")
            return .handled
        }

        // A changed button label must not collapse into the same result as no dialog. The dialog's
        // password copy is independent evidence that the blocking surface exists; if it is present,
        // preserve the surface, report every observed button label, and fail at the probe site.
        let passwordCopy = springboard.staticTexts.matching(
            NSPredicate(format: "label CONTAINS[c] %@", "password")
        )
        guard passwordCopy.count > 0 else { return .absent }

        let labels = observedButtonLabels(in: springboard)
        print("DULCET_UI_SYSTEM_ALERT handled=unsupported buttons=\(labels)")
        XCTFail(
            "Unsupported blocking password dialog; observed SpringBoard buttons: \(labels)"
        )
        return .unsupported
    }

    @MainActor
    private func waitForValue(
        _ expectedValue: String,
        of element: XCUIElement,
        timeout: TimeInterval
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if (element.value as? String) == expectedValue {
                return true
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        return (element.value as? String) == expectedValue
    }

    @MainActor
    private func allowLocalNetworkAccessIfRequested() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"].firstMatch
        if allow.waitForExistence(timeout: 3) {
            allow.tap()
        }
    }

    @MainActor
    private func installSystemAlertInterruptionMonitors() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

        // Monitors run in reverse installation order. Keep this general fallback first so the
        // expected-response monitor below gets the first opportunity to preserve test intent.
        _ = addUIInterruptionMonitor(withDescription: "Dismiss an unexpected system alert") { _ in
            for title in ["Cancel", "Close", "Dismiss", "Not Now", "OK"] {
                let button = springboard.buttons[title].firstMatch
                if button.exists {
                    button.tap()
                    print("DULCET_UI_INTERRUPTION handled=general button=\(title)")
                    return true
                }
            }

            let labels = self.observedButtonLabels(in: springboard)
            print("DULCET_UI_INTERRUPTION handled=unsupported buttons=\(labels)")
            XCTFail("Unsupported system interruption; observed SpringBoard buttons: \(labels)")
            return false
        }

        _ = addUIInterruptionMonitor(withDescription: "Handle expected system alerts") { _ in
            for title in ["Not Now", "Allow"] {
                let button = springboard.buttons[title].firstMatch
                if button.exists {
                    button.tap()
                    print("DULCET_UI_INTERRUPTION handled=expected button=\(title)")
                    return true
                }
            }
            return false
        }
    }

    @MainActor
    private func observedButtonLabels(in application: XCUIApplication) -> [String] {
        application.buttons.allElementsBoundByIndex.map { button in
            button.label.isEmpty ? "<empty>" : button.label
        }
    }

    /// Polls the delivery marker's label (`dulcet-scrobble persisted=N delivered=N ...`) until
    /// `until` accepts the parsed counts, returning nil at the deadline. A label without counts
    /// (the app has not yet installed its observer) never satisfies a predicate.
    @MainActor
    private func waitForScrobbleDeliveryCounts(
        in marker: XCUIElement,
        timeout: TimeInterval,
        until accepted: ([String: Int]) -> Bool
    ) -> [String: Int]? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let counts = scrobbleDeliveryCounts(from: marker.label), accepted(counts) {
                return counts
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        if let counts = scrobbleDeliveryCounts(from: marker.label), accepted(counts) {
            return counts
        }
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

    @MainActor
    private func waitUntilPastScrobbleThreshold(
        _ progress: XCUIElement,
        timeout: TimeInterval = 90
    ) -> PlaybackProgressSample? {
        let deadline = Date().addingTimeInterval(timeout)
        var lastSample: PlaybackProgressSample?
        var observedIncrease = false

        while Date() < deadline {
            if let value = progress.value as? String,
               let sample = playbackProgressSample(from: value) {
                if let previous = lastSample, sample.elapsed > previous.elapsed {
                    observedIncrease = true
                }
                lastSample = sample

                // §15.2: eligible at min(50% of media duration, four minutes), with duration >= 30s.
                // Waiting for the next whole displayed second proves media time moved beyond, not
                // merely onto, the fractional threshold and leaves delivery a progressing update.
                let threshold = min(sample.duration * 0.5, 4 * 60)
                let pastThreshold = floor(threshold) + 1
                if sample.duration >= 30,
                   observedIncrease,
                   sample.elapsed >= pastThreshold {
                    return sample
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }

        let lastValue = lastSample?.accessibilityValue ?? String(describing: progress.value)
        XCTFail(
            "Playback media time did not progress past the §15.2 scrobble threshold; last value: \(lastValue)"
        )
        return nil
    }

    private func playbackProgressSample(from value: String) -> PlaybackProgressSample? {
        let components = value.components(separatedBy: " of ")
        guard components.count == 2,
              let elapsed = clockSeconds(components[0]),
              let duration = clockSeconds(components[1]) else { return nil }
        return PlaybackProgressSample(
            elapsed: elapsed,
            duration: duration,
            accessibilityValue: value
        )
    }

    private func clockSeconds(_ value: String) -> TimeInterval? {
        let components = value.split(separator: ":").compactMap { TimeInterval($0) }
        switch components.count {
        case 2:
            return components[0] * 60 + components[1]
        case 3:
            return components[0] * 3_600 + components[1] * 60 + components[2]
        default:
            return nil
        }
    }
}
