import Foundation
import XCTest

/// Drives the isolated production-composed host with touch credential entry and Reconnect.
/// XCTest typing with a visible software keyboard does not evidence manual key-by-key tapping.
@MainActor
final class DulcetSignedIPhoneAccountConnectUITests: XCTestCase {
    func testSignedIPhoneConnectKeychainRelaunchAndTouchReconnect() throws {
        continueAfterFailure = false
        let env = ProcessInfo.processInfo.environment
        let url = try XCTUnwrap(env["DULCET_CONFORMANCE_BASE_URL"])
        XCTAssertEqual(env["DULCET_CONFORMANCE_DISPOSABLE"], "true")
        let nonce = try XCTUnwrap(env["DULCET_SIGNED_IPHONE_NONCE"])
        XCTAssertNotNil(UUID(uuidString: nonce))
        let app = XCUIApplication(bundleIdentifier: "com.legitimateapps.dulcet.signed-iphone")
        let marker = app.staticTexts["dulcet.signed-iphone.proof"].firstMatch
        func launch(_ phase: String) {
            app.launchArguments = ["-dulcet-signed-iphone-phase", phase, "-dulcet-signed-iphone-nonce", nonce]
            if phase == "prime" { app.launchArguments += ["-dulcet-signed-iphone-prime-url", url] }
            if phase == "connect" { app.launchArguments += ["-dulcet-signed-iphone-fixture-url", url] }
            app.launch()
        }
        func expectMarker(_ token: String) {
            let predicate = NSPredicate(format: "exists == true AND label CONTAINS %@", token)
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: marker)],
                                         timeout: 65), .completed, "The signed host must verify \(token) through the real persistence path")
        }
        func quit() {
            app.terminate()
            XCTAssertEqual(app.state, .notRunning, "Relaunch must end the prior app process")
        }
        func connection() {
            let row = app.staticTexts["dulcet.sidebar.settings"].firstMatch
            if row.waitForExistence(timeout: 3) { XCTAssertTrue(row.isHittable); row.tap() }
            else {
                let tab = app.tabBars.buttons["Connection"].firstMatch
                XCTAssertTrue(tab.waitForExistence(timeout: 10)); tab.tap()
            }
        }
        let connected = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Connected to'")).firstMatch
        let saved = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Reconnect to'")).firstMatch
        // A fresh install asks for Local Network consent on its first fixture request.
        // Accept only that prompt, only for this host, before any state is written.
        launch("prime")
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let primeDeadline = Date().addingTimeInterval(65)
        while Date() < primeDeadline, !(marker.exists && marker.label.contains("primed=PASS")) {
            let alert = springboard.alerts.firstMatch
            if alert.exists, alert.label.contains("Dulcet Signed iPhone Host"),
               alert.label.localizedCaseInsensitiveContains("local network") {
                let allow = alert.buttons["Allow"].firstMatch
                XCTAssertTrue(allow.exists, "The Local Network prompt must offer Allow"); allow.tap()
            }
            if marker.exists, marker.label.contains("FAIL") { break }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        expectMarker("primed=PASS")
        quit()
        launch("connect")
        connection()
        func enter(_ field: XCUIElement, _ text: String) {
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            XCTAssertTrue(field.isHittable)
            field.tap()
            XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5), "Credential entry requires the on-screen keyboard")
            field.typeText(text)
        }
        enter(app.textFields["dulcet.account-connect.server-address"].firstMatch, url)
        enter(app.textFields["dulcet.account-connect.username"].firstMatch, "dulcet-admin")
        enter(app.secureTextFields["dulcet.account-connect.password"].firstMatch, "dulcet-ci-canary-password")
        let http = app.switches["Allow HTTP on this local network"].firstMatch
        if !http.isHittable { app.swipeUp() }
        XCTAssertTrue(http.isHittable)
        if (http.value as? String) != "1" { http.tap() }
        let connect = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        if !connect.isHittable { app.swipeUp() }
        XCTAssertTrue(connect.isHittable); XCTAssertTrue(connect.isEnabled)
        connect.tap()
        expectMarker("connected=PASS")
        connection()
        XCTAssertTrue(connected.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Sign Out"].exists)
        quit()
        launch("read") // observation flags only: no account hook, pointer override or credential arguments
        expectMarker("saved=PASS")
        let reconnect = app.buttons["dulcet.reader.reconnect"].firstMatch
        XCTAssertTrue(reconnect.waitForExistence(timeout: 15), "The saved library must offer Reconnect")
        connection()
        XCTAssertTrue(saved.waitForExistence(timeout: 10), "Connection must name the saved server")
        XCTAssertFalse(app.buttons["Sign Out"].exists)
        let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        XCTAssertTrue(primary.label.contains("Reconnect")); XCTAssertTrue(primary.isHittable)
        let library = app.tabBars.buttons["Library"].firstMatch
        XCTAssertTrue(library.isHittable); library.tap()
        XCTAssertTrue(reconnect.isHittable); reconnect.tap()
        expectMarker("connected=PASS")
        connection()
        XCTAssertTrue(connected.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Sign Out"].exists)
        quit()
        launch("missing") // deletes only the nonce-owned item; retains its production pointer
        expectMarker("missing-item=PASS")
        connection()
        XCTAssertTrue(app.staticTexts["Your saved account could not be opened"].waitForExistence(timeout: 10))
        XCTAssertFalse(saved.exists)
        XCTAssertFalse(reconnect.exists)
        quit()
        launch("cleanup")
        expectMarker("cleanup=PASS")
        connection()
        XCTAssertTrue(primary.waitForExistence(timeout: 10))
        XCTAssertFalse(primary.label.contains("Reconnect"))
        XCTAssertFalse(primary.isEnabled)
        quit()
    }
}
