import XCTest

final class DulcetiOSUITests: XCTestCase {
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

    /// Proves the iPad renders the regular-width split: sidebar and detail visible at once, in
    /// separate columns. An iPhone cannot satisfy this, which is the point -- a test that passed on
    /// both would let the iPadOS cell claim evidence it does not have.
    @MainActor
    func testAccountConnectUsesRegularWidthSplitLayout() {
        let app = XCUIApplication()
        app.launchArguments.append("-dulcet-account-connect-layout-fixture")
        app.launch()

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
        guard let configuration = livePlaybackConfiguration() else { return }

        let app = XCUIApplication()
        app.launch()

        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 10), "The app window must exist")
        XCTAssertGreaterThan(
            window.frame.width,
            700,
            "This playback proof requires a regular-width iPad window; an iPhone is invalid evidence"
        )

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
            // The physical-device accessibility service describes this as Button, Toggle, while
            // XCUITest exposes neither a Button nor a Switch. Match its semantic label and binary
            // value across element types, then guard uniqueness so a decorative child cannot win.
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
            guard makeHittable(allowLocalHTTP, byScrolling: app) else {
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

        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 30) else {
            XCTFail("The live account connection must succeed before playback is attempted")
            return
        }
        dismissPasswordSavePromptIfRequested()

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
        guard makeHittable(thresholdAlbum, byScrolling: app) else {
            XCTFail("The threshold canary album must be reachable in the library")
            return
        }
        thresholdAlbum.tap()

        let thresholdTrack = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "Thirty One Seconds")
        ).firstMatch
        guard thresholdTrack.waitForExistence(timeout: 10) else {
            XCTFail("The disposable server must expose the eligible 31-second scrobble canary")
            return
        }
        guard makeHittable(thresholdTrack, byScrolling: app) else {
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
    private func makeHittable(
        _ element: XCUIElement,
        byScrolling app: XCUIApplication
    ) -> Bool {
        for _ in 0..<6 {
            if element.exists && element.isHittable {
                return true
            }
            app.swipeUp()
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
        return element.exists && element.isHittable
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
    private func dismissPasswordSavePromptIfRequested() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let notNow = springboard.buttons["Not Now"].firstMatch
        if notNow.waitForExistence(timeout: 3) {
            notNow.tap()
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
