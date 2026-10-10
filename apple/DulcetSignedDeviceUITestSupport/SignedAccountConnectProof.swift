import Foundation
import XCTest

/// Drives the isolated production-composed host with touch credential entry and Reconnect.
/// XCTest typing with a visible software keyboard does not evidence manual key-by-key tapping.
@MainActor
enum SignedAccountConnectProof {
    static func run(lane: String, rotating: Bool) throws {
        precondition(["ipad", "iphone"].contains(lane))
        let prefix = "DULCET_SIGNED_" + lane.uppercased()
        if rotating { XCUIDevice.shared.orientation = .portrait }
        defer { if rotating { XCUIDevice.shared.orientation = .portrait } }
        let env = ProcessInfo.processInfo.environment
        let url = try XCTUnwrap(env["DULCET_CONFORMANCE_BASE_URL"])
        XCTAssertEqual(env["DULCET_CONFORMANCE_DISPOSABLE"], "true")
        let nonce = try XCTUnwrap(env[prefix + "_NONCE"])
        XCTAssertNotNil(UUID(uuidString: nonce))
#if targetEnvironment(simulator)
        XCTAssertEqual(env[prefix + "_RUNTIME"], "simulator", "Simulator rehearsal must be explicit")
#else
        XCTAssertNil(env[prefix + "_RUNTIME"], "Hardware proof must not request simulator mode")
#endif
        let app = XCUIApplication(bundleIdentifier: "com.legitimateapps.dulcet.signed-\(lane)")
        let marker = app.staticTexts["dulcet.signed-\(lane).proof"].firstMatch
        func launch(_ phase: String) {
            app.launchArguments = ["-dulcet-signed-\(lane)-phase", phase, "-dulcet-signed-\(lane)-nonce", nonce]
#if targetEnvironment(simulator)
            app.launchArguments += ["-dulcet-signed-\(lane)-runtime", "simulator"]
#endif
            if phase == "prime" { app.launchArguments += ["-dulcet-signed-\(lane)-prime-url", url] }
            if phase == "connect" { app.launchArguments += ["-dulcet-signed-\(lane)-fixture-url", url] }
            app.launch()
        }
        func expectMarker(_ token: String) {
            let predicate = NSPredicate(format: "exists == true AND label CONTAINS %@", token)
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: marker)],
                                         timeout: 65), .completed, "The signed host must verify \(token) through the real persistence path")
        }
        func expectHittable(_ element: XCUIElement, _ message: String = "Control must become hittable") {
            // Transitions on hardware leave controls briefly unhittable; one sample is not evidence.
            let hittable = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: element)
            XCTAssertEqual(XCTWaiter().wait(for: [hittable], timeout: 5), .completed, message)
        }
        func quit() {
            app.terminate()
            XCTAssertEqual(app.state, .notRunning, "Relaunch must end the prior app process")
        }
        func connection() {
            let row = app.staticTexts["dulcet.sidebar.settings"].firstMatch
            if row.waitForExistence(timeout: 3) {
                // Right after Connect the sidebar is still settling and the row is briefly not
                // hittable (signed iPad, 2026-10-10); wait for it rather than sample once.
                expectHittable(row, "Connection row must become hittable")
                row.tap()
            }
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
            for alert in [app.alerts.firstMatch, springboard.alerts.firstMatch] {
                if alert.exists, alert.label.contains("Dulcet Signed \(lane == "ipad" ? "iPad" : "iPhone") Host"),
                   alert.label.localizedCaseInsensitiveContains("local network") {
                    let allow = alert.buttons["Allow"].firstMatch
                    XCTAssertTrue(allow.exists, "The Local Network prompt must offer Allow"); allow.tap()
                    break
                }
            }
            if marker.exists, marker.label.contains("FAIL") { break }
            RunLoop.current.run(until: Date().addingTimeInterval(0.5))
        }
        XCTAssertFalse(marker.exists && marker.label.contains("FAIL"),
                       "Fixture prime failed: \(marker.exists ? marker.label : "marker absent")")
        expectMarker("primed=PASS")
        quit()
        launch("connect")
        connection()
        func enter(_ field: XCUIElement, _ text: String) {
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            expectHittable(field)
            // A tap during the navigation transition can be dropped (signed iPad, 2026-10-10:
            // no keyboard after the first field tap). Re-tap only while no keyboard is up.
            for attempt in 1...3 {
                field.tap()
                if app.keyboards.firstMatch.waitForExistence(timeout: 5) { break }
                print("DULCET SIGNED \(lane.uppercased()) field re-tap attempt=\(attempt + 1)")
            }
            XCTAssertTrue(app.keyboards.firstMatch.exists, "Credential entry requires the on-screen keyboard")
            field.typeText(text)
        }
        enter(app.textFields["dulcet.account-connect.server-address"].firstMatch, url)
        enter(app.textFields["dulcet.account-connect.username"].firstMatch, "dulcet-admin")
        enter(app.secureTextFields["dulcet.account-connect.password"].firstMatch, "dulcet-ci-canary-password")
        let http = app.switches["Allow HTTP on this local network"].firstMatch
        if !http.isHittable { app.swipeUp() }
        expectHittable(http)
        // A centre tap lands on the row's label, and a tap while the software keyboard is still
        // leaving can be dropped (signed iPad, 2026-10-10: "Local HTTP is not allowed" after the
        // tap). Tap the switch end and re-tap only while it provably still reads off.
        for attempt in 1...3 where (http.value as? String) != "1" {
            http.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            let on = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == '1'"), object: http)
            if XCTWaiter().wait(for: [on], timeout: 5) == .completed { break }
            print("DULCET SIGNED \(lane.uppercased()) consent re-tap attempt=\(attempt + 1)")
        }
        XCTAssertEqual(http.value as? String, "1", "Local HTTP consent must turn on before Connect")
        let connect = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        if !connect.isHittable { app.swipeUp() }
        expectHittable(connect); XCTAssertTrue(connect.isEnabled)
        connect.tap()
        expectMarker("connected=PASS")
        // A device with password AutoFill offers to save the typed password after sign-in and the
        // sheet covers the sidebar (signed iPad, 2026-10-10). Press only a known decline label so
        // nothing reaches the device's own password store.
        let saveTitle = app.staticTexts["Save Password?"].firstMatch
        if saveTitle.waitForExistence(timeout: 5) {
            let declines = ["Not Now", "Never", "No Thanks", "Don't Save", "Don\u{2019}t Save"]
            let inApp = app.buttons.matching(NSPredicate(format: "label IN %@", declines)).firstMatch
            let inSystem = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons
                .matching(NSPredicate(format: "label IN %@", declines)).firstMatch
            let decline = inApp.waitForExistence(timeout: 3) ? inApp : inSystem
            XCTAssertTrue(decline.waitForExistence(timeout: 3), "The save-password sheet must offer a decline")
            print("DULCET SIGNED \(lane.uppercased()) save-password declined=\(decline.label)")
            decline.tap()
            let gone = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: saveTitle)
            XCTAssertEqual(XCTWaiter().wait(for: [gone], timeout: 5), .completed, "The save-password sheet must close")
        }
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
        XCTAssertTrue(primary.label.contains("Reconnect")); expectHittable(primary)
        let libraryRow = app.staticTexts["dulcet.sidebar.library"].firstMatch
        if libraryRow.exists {
            expectHittable(libraryRow); libraryRow.tap()
        } else {
            let library = app.tabBars.buttons["Library"].firstMatch
            expectHittable(library); library.tap()
        }
        expectHittable(reconnect); reconnect.tap()
        expectMarker("connected=PASS")
        connection()
        XCTAssertTrue(connected.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["Sign Out"].exists)
        if rotating {
            XCTAssertGreaterThan(app.frame.width, 700, "The iPad proof requires a regular-width tablet window")
            XCTAssertLessThan(app.frame.width, app.frame.height, "Connected proof begins in portrait")
            XCUIDevice.shared.orientation = .landscapeLeft
            let rotated = NSPredicate { _, _ in app.frame.width > app.frame.height }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: rotated, object: app)],
                                         timeout: 15), .completed, "Connected UI must rotate to landscape")
            expectMarker("connected=PASS")
            XCTAssertTrue(connected.waitForExistence(timeout: 10))
            expectHittable(app.buttons["Sign Out"])
            let libraryRow = app.staticTexts["dulcet.sidebar.library"].firstMatch
            expectHittable(libraryRow, "Landscape keeps Library navigation reachable")
        }
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
