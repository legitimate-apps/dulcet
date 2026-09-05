import XCTest

final class DulcetiOSUITests: XCTestCase {
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
        let query = "UI Playback Canary"

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
                XCTFail("A compact-width window must expose the sidebar through a back control")
                return
            }
            backControl.tap()
        }
        guard searchRow.waitForExistence(timeout: 5), searchRow.isHittable else {
            XCTFail("The Search row must be visible in the sidebar")
            return
        }
        searchRow.tap()

        let searchField = app.textFields["dulcet.search.field"].firstMatch
        guard searchField.waitForExistence(timeout: 5) else {
            XCTFail("The search field must exist on the Search destination")
            return
        }
        searchField.tap()
        searchField.typeText(query)
        XCTAssertEqual(
            searchField.value as? String,
            query,
            "The query typed through the platform keyboard must reach the search field itself"
        )

        let firstResult = app.buttons["dulcet.search.result.0"].firstMatch
        guard firstResult.waitForExistence(timeout: 30) else {
            XCTFail("The disposable server must rank the UI playback canary first for this query")
            return
        }
        XCTAssertTrue(
            firstResult.label.hasPrefix(query),
            "Rank zero must render the canary track, not a store value; label=\(firstResult.label)"
        )
        // The row's accessibility label is "<title>, <subtitle>, <kind>"; DulcetStrings.track
        // renders the track kind as "Track".
        XCTAssertTrue(
            firstResult.label.hasSuffix(", Track"),
            "Rank zero must be a track result; label=\(firstResult.label)"
        )
        // A person dismisses the software keyboard before activating a result; the test must do
        // the same, because the occluding keyboard window eats the hit test. Measured on iPhone
        // 17 Pro: the rank-zero row's midpoint sat at y=500 under a keyboard whose top edge was
        // y=472, so tapping without dismissing is not a real activation path.
        guard dismissKeyboardBeforeActivation(in: app) else { return }

        guard firstResult.isHittable else {
            // Frame diagnostics make an occlusion report actionable from the CI log alone:
            // a covered row and an offscreen row are different defects with the same symptom.
            let keyboard = app.keyboards.firstMatch
            print(
                "DULCET SEARCH UI DIAG result-frame=\(firstResult.frame)"
                    + " window-frame=\(window.frame)"
                    + " keyboard-exists=\(keyboard.exists)"
                    + " keyboard-frame=\(keyboard.exists ? String(describing: keyboard.frame) : "none")"
            )
            XCTFail("The rank-zero result must be hittable so activation is a real tap")
            return
        }

        firstResult.tap()

        let nowPlayingTitle = app.staticTexts["dulcet.now-playing.title"].firstMatch
        guard nowPlayingTitle.waitForExistence(timeout: 15) else {
            XCTFail("Activating rank zero must present the Now Playing surface")
            return
        }
        XCTAssertEqual(
            nowPlayingTitle.label,
            query,
            "Now Playing must show the activated search track"
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
                + " query=typed rank0=rendered activation=tap source=search now-playing=\(query)"
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
