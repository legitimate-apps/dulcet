#if os(macOS) || os(iOS)
#if os(macOS) && canImport(AppIntentsTesting)
import AppIntentsTesting
#endif
import Foundation
import ObjectiveC
import XCTest

/// Opt-in system execution proof against the published disposable playback canary.
/// No app module is imported: AppIntentsTesting invokes the installed app across processes.
@MainActor
final class DulcetSystemSurfacesUITests: XCTestCase {
    func testSystemIntentStartsPlaybackAndControlsTheExistingPlayer() async throws {
        let app = try connectedApp(proofFlag: "DULCET_SYSTEM_SURFACES_PROOF")

#if os(macOS)
        let bundle = "com.legitimateapps.dulcet.dev.my-library-proof"
#else
        let bundle = "com.legitimateapps.dulcet.dev"
#endif
#if os(macOS) && canImport(AppIntentsTesting)
        if #available(macOS 27.0, iOS 27.0, *) {
            let definitions = IntentDefinitions(bundleIdentifier: bundle)
            _ = try await definitions.intents["DulcetPlaySongIntent"]
                .makeIntent(name: "UI Playback Canary").run()
            app.activate()
            try assertCanaryAdvances(in: app)
            _ = try await definitions.intents["DulcetTogglePlaybackIntent"].makeIntent().run()
            app.activate()
            XCTAssertTrue(app.buttons["dulcet.mini-player.play-pause"].waitForExistence(timeout: 10))
            XCTAssertEqual(app.buttons["dulcet.mini-player.play-pause"].label, "Play")
            _ = try await definitions.intents["DulcetResumeIntent"].makeIntent().run()
            app.activate()
            try assertCanaryAdvances(in: app)
            print("DULCET SYSTEM SURFACES OBSERVED cross-process-intent=true song=UI-Playback-Canary advancing-media-time=true pause-resume=true bundle=\(bundle)")
            return
        }
#endif
#if os(iOS)
            // Older simulator runtimes cannot load AppIntentsTesting. Exercise Siri's real
            // recognition-to-app path instead, and retain its response if the runtime refuses it.
            XCUIDevice.shared.siriService.activate(voiceRecognitionText: "Play a song in Dulcet DEV")
            let siri = XCUIApplication(bundleIdentifier: "com.apple.siri")
            _ = siri.staticTexts.firstMatch.waitForExistence(timeout: 15)
            print("DULCET SYSTEM SURFACES SIRI PARAMETER PROMPT " + siri.debugDescription)
            XCUIDevice.shared.siriService.activate(voiceRecognitionText: "UI Playback Canary")
            _ = siri.staticTexts.firstMatch.waitForExistence(timeout: 15)
            print("DULCET SYSTEM SURFACES SIRI RESPONSE " + siri.debugDescription)
            app.activate()
            try assertCanaryAdvances(in: app)
            print("DULCET SYSTEM SURFACES OBSERVED siri-trigger=true advancing-media-time=true bundle=\(bundle)")
#else
            throw XCTSkip("AppIntentsTesting requires macOS 27")
#endif
    }

#if os(iOS)
    func testHomeWidgetRendersAndItsIntentResumesPlayback() throws {
        let app = try connectedApp(proofFlag: "DULCET_WIDGET_HOST_PROOF")
        let sidebar = app.descendants(matching: .any)["dulcet.sidebar.search"].firstMatch
        if sidebar.exists { sidebar.tap() } else { app.tabBars.buttons["Search"].tap() }
        let search = app.textFields["dulcet.search.field"].firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10), app.debugDescription)
        search.tap()
        search.typeText("UI Playback Canary")
        let song = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'dulcet.search.result.' AND label CONTAINS 'UI Playback Canary'")).firstMatch
        XCTAssertTrue(song.waitForExistence(timeout: 30), app.debugDescription)
        song.tap()
        try assertCanaryAdvances(in: app)
        let transport = app.buttons["dulcet.mini-player.play-pause"]
        XCTAssertEqual(transport.label, "Pause")
        transport.tap()
        XCTAssertEqual(transport.label, "Play")

        XCUIDevice.shared.press(.home)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        withoutIdleWaits(springboard) {
            let icon = springboard.icons["Dulcet DEV"].firstMatch
            XCTAssertTrue(icon.waitForExistence(timeout: 10), springboard.debugDescription)
            icon.press(forDuration: 1.5)
            let editHome = springboard.buttons["Edit Home Screen"].firstMatch
            XCTAssertTrue(editHome.waitForExistence(timeout: 5), springboard.debugDescription)
            editHome.tap()
            let edit = springboard.buttons["Edit"].firstMatch
            if edit.waitForExistence(timeout: 3) { edit.tap() }
            var addButton = springboard.buttons["Add Widget"].firstMatch
            if !addButton.waitForExistence(timeout: 3) { addButton = springboard.buttons["Add"].firstMatch }
            XCTAssertTrue(addButton.waitForExistence(timeout: 5), springboard.debugDescription)
            addButton.tap()
            var widgetSearch = springboard.searchFields.firstMatch
            if !widgetSearch.waitForExistence(timeout: 3) { widgetSearch = springboard.textFields.firstMatch }
            XCTAssertTrue(widgetSearch.waitForExistence(timeout: 5), springboard.debugDescription)
            widgetSearch.tap()
            widgetSearch.typeText("Dulcet")
            let result = springboard.staticTexts["Dulcet DEV"].firstMatch
            XCTAssertTrue(result.waitForExistence(timeout: 10), springboard.debugDescription)
            print("DULCET SYSTEM SURFACES WIDGET PICKER " + springboard.debugDescription)
            // The widget gallery's remote accessibility element can report no hit point
            // despite publishing its on-screen frame. Send the event to that observed frame.
            XCTAssertTrue(springboard.frame.contains(result.frame))
            result.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            let pickerAttachment = XCTAttachment(screenshot: springboard.screenshot())
            pickerAttachment.name = "WidgetHost-DulcetPicker"
            pickerAttachment.lifetime = .keepAlways
            self.add(pickerAttachment)
            let addWidget = springboard.buttons["Add Widget"].firstMatch
            if addWidget.waitForExistence(timeout: 10) {
                addWidget.tap()
            } else {
                // iOS 26.5's remote gallery loses AX after presenting the picker.
                // A retained simulator recording shows Add Widget at (201, 792)
                // on this 402×874 phone. Keep the fallback scoped to that observed
                // layout, then require the actual Home widget and playback effect.
                XCTAssertEqual(springboard.frame.size, CGSize(width: 402, height: 874))
                springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.905)).tap()
            }
            let done = springboard.buttons["Done"].firstMatch
            if done.waitForExistence(timeout: 5) { done.tap() }
            let resume = springboard.buttons["Resume Dulcet"].firstMatch
            XCTAssertTrue(resume.waitForExistence(timeout: 20), springboard.debugDescription)
            XCTAssertTrue(springboard.staticTexts["UI Playback Canary"].firstMatch.exists,
                          "The real WidgetKit host must render the published current title")
            let attachment = XCTAttachment(screenshot: springboard.screenshot())
            attachment.name = "WidgetHost-DulcetNowPlaying"
            attachment.lifetime = .keepAlways
            self.add(attachment)
            resume.tap()
        }
        app.activate()
        XCTAssertEqual(transport.label, "Pause")
        try assertCanaryAdvances(in: app)
        print("DULCET SYSTEM SURFACES OBSERVED widget-host=true current-title=true widget-intent-resume=true advancing-media-time=true")
    }
#endif

    // SpringBoard's icon lift and edit mode keep animations armed. This is the same
    // ABI-checked test-only interaction scope as the existing row-menu proofs (TRAPS 48).
    // Every control and resulting playback state is still explicitly awaited/asserted.
    private func withoutIdleWaits(_ app: XCUIApplication, _ body: @escaping () -> Void) {
        let selector = NSSelectorFromString("_performWithInteractionOptions:block:")
        guard let method = class_getInstanceMethod(type(of: app), selector),
              let encoding = method_getTypeEncoding(method).map({ String(cString: $0) }),
              encoding.filter({ !$0.isNumber }) == "v@:I@?" else { body(); return }
        typealias Perform = @convention(c) (AnyObject, Selector, UInt32, @convention(block) () -> Void) -> Void
        unsafeBitCast(method_getImplementation(method), to: Perform.self)(app, selector, 3, body)
    }

    private func connectedApp(proofFlag: String) throws -> XCUIApplication {
        let environment = ProcessInfo.processInfo.environment
        guard environment[proofFlag] == "1" else {
            throw XCTSkip("Requires an explicitly provisioned disposable system-surfaces fixture")
        }
        guard let base = environment["DULCET_UI_TEST_SERVER_URL"],
              let url = URLComponents(string: base),
              ["127.0.0.1", "localhost", "::1"].contains(url.host ?? ""),
              let username = environment["DULCET_UI_TEST_USERNAME"],
              let password = environment["DULCET_UI_TEST_PASSWORD"] else {
            XCTFail("Provide the loopback disposable server and its published fixture credentials")
            throw NSError(domain: "DulcetSystemSurfacesFixture", code: 1)
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = [
            "-dulcet-debug-connect-account", "-dulcet-debug-account-server-url", base,
            "-dulcet-debug-account-username", username, "-dulcet-debug-account-password", password,
            "-dulcet-debug-account-fixture", UUID().uuidString,
        ]
        app.launch()
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 20))
        let catalog = app.buttons["dulcet.library.album"].firstMatch
        XCTAssertTrue(catalog.waitForExistence(timeout: 40), "The disposable catalog must load before system execution: " + app.debugDescription)

        return app
    }

    private func assertCanaryAdvances(in app: XCUIApplication) throws {
        let mini = app.buttons["dulcet.mini-player.open"]
        XCTAssertTrue(mini.waitForExistence(timeout: 30), app.debugDescription)
        XCTAssertTrue(mini.label.contains("UI Playback Canary"), mini.label)
        mini.tap()
        let title = app.staticTexts["dulcet.now-playing.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        XCTAssertEqual(title.label, "UI Playback Canary")
        let elapsed = app.staticTexts.matching(NSPredicate(format: "label == 'Elapsed'")).firstMatch
        XCTAssertTrue(elapsed.waitForExistence(timeout: 15), app.debugDescription)
        let before = elapsed.value as? String
        XCTAssertNotNil(before)
        let advances = NSPredicate { _, _ in
            guard let after = elapsed.value as? String else { return false }
            return after != before && after != "0:00"
        }
        let advancing = expectation(for: advances, evaluatedWith: nil)
        wait(for: [advancing], timeout: 10)
        print("DULCET SYSTEM SURFACES MEDIA elapsed-before=\(before ?? "missing") elapsed-after=\(elapsed.value ?? "missing")")
        let close = app.buttons["dulcet.now-playing.close"]
        if close.exists { close.tap() }
    }
}
#endif
