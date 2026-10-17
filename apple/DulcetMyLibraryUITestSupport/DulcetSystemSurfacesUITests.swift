#if os(macOS) || os(iOS)
#if canImport(AppIntentsTesting)
import AppIntentsTesting
#endif
import Foundation
import XCTest

/// Opt-in system execution proof against the published disposable playback canary.
/// No app module is imported: AppIntentsTesting invokes the installed app across processes.
@MainActor
final class DulcetSystemSurfacesUITests: XCTestCase {
    func testSystemIntentStartsPlaybackAndControlsTheExistingPlayer() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard environment["DULCET_SYSTEM_SURFACES_PROOF"] == "1" else {
            throw XCTSkip("Requires an explicitly provisioned disposable system-surfaces fixture")
        }
        guard let base = environment["DULCET_UI_TEST_SERVER_URL"],
              let url = URLComponents(string: base),
              ["127.0.0.1", "localhost", "::1"].contains(url.host ?? ""),
              let username = environment["DULCET_UI_TEST_USERNAME"],
              let password = environment["DULCET_UI_TEST_PASSWORD"] else {
            XCTFail("Provide the loopback disposable server and its published fixture credentials")
            return
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

#if os(macOS)
        let bundle = "com.legitimateapps.dulcet.dev.my-library-proof"
#else
        let bundle = "com.legitimateapps.dulcet.dev"
#endif
#if canImport(AppIntentsTesting)
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
            XCUIDevice.shared.siriService.activate(voiceRecognitionText: "Play UI Playback Canary in Dulcet DEV")
            let siri = XCUIApplication(bundleIdentifier: "com.apple.SiriViewService")
            print("DULCET SYSTEM SURFACES SIRI RESPONSE " + siri.debugDescription)
            app.activate()
            try assertCanaryAdvances(in: app)
            print("DULCET SYSTEM SURFACES OBSERVED siri-trigger=true advancing-media-time=true bundle=\(bundle)")
#else
            throw XCTSkip("AppIntentsTesting requires macOS 27")
#endif
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
