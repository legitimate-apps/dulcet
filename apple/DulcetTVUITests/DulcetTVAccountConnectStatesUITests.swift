import Foundation
import UIKit
import XCTest

/// CONF-09b on Apple TV: every declared account-connect render state, each reached through the
/// transition the shipping app itself makes, in the app process, against the local disposable
/// server, with the Siri Remote alone -- never an injected outcome, and never a tap.
///
/// | state | how this proof reaches it |
/// |---|---|
/// | idle | a launch with no saved account (an earlier test's account is signed out first, by remote) |
/// | domain error, input | the form typed by remote with an `ftp://` address: refused before any request |
/// | domain error, security | the disposable server's plain-HTTP address with the local-HTTP consent off |
/// | domain error, transport | the consent on, by remote, and a loopback port nothing listens on |
/// | domain error, authentication | the disposable server's own answer to a wrong password typed by remote |
/// | domain error, protocol | the disposable server's web-player address, `/app`: its own 404 page, not OpenSubsonic |
/// | domain error, server | the fault proxy in front of the disposable server answers `ping` with OpenSubsonic error 0 |
/// | in progress | a loopback port this process listens on and never answers; Cancel, by remote, ends it |
/// | connected | the right account, submitted through Connect's own path by the DEBUG launch hook |
/// | saved, disconnected | a relaunch with the account saved: the library offers Reconnect, Connection names the server |
/// | credential persistence | a relaunch whose active-account pointer names no Keychain item |
///
/// The right password is never typed: the DEBUG launch hook hands the form the account and calls
/// the same `submitAccountConnection()` Connect calls -- the production connector, the server's
/// answer, the real Keychain save -- because typed credentials that are then accepted raise a
/// system save-password dialog no app-side query reaches (docs/tvos-search-ui-evidence.md).
/// The persistence error is the production launch-time read answering errSecItemNotFound for a
/// pointer, planted in the argument domain of the defaults the store reads, that names an account
/// this simulator's Keychain does not hold. A failing Keychain SAVE has no simulator trigger; the
/// unentitled Mac host reaches that half for real. The server error is the one answer the disposable
/// server does not write -- it never sends a server-family error to a valid request -- so the
/// fault proxy the other app proofs use (tools/conformance-env/lyrics-fault-proxy) answers the
/// connect sequence's `ping` with a well-formed `failed` envelope, and the app classifies that real
/// HTTP answer. `Capability.Unsupported` has no account-connect origin (spec §18.12).
///
/// Self-contained on purpose: it shares no helper with DulcetTVUITests.swift.
final class DulcetTVAccountConnectStatesUITests: XCTestCase {
    private static let sections = ["library", "search", "nowPlaying", "settings"]
    private var afterTest: [() -> Void] = []

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    override func tearDown() {
        let cleanups = afterTest
        afterTest = []
        cleanups.reversed().forEach { $0() }
        super.tearDown()
    }

    @MainActor
    func testEveryAccountConnectStateIsReachedByRemoteOnAppleTV() {
        guard ProcessInfo.processInfo.environment["SIMULATOR_UDID"] != nil else {
            XCTFail("The account-connect states proof requires a tvOS simulator")
            return
        }
        guard let server = disposableServer(), let proxy = faultProxy() else { return }
        guard let refused = TVLoopbackTestPort(listening: false),
              let silent = TVLoopbackTestPort(listening: true) else {
            XCTFail("Two loopback ports must be available to this process")
            return
        }
        afterTest.append { refused.release(); silent.release() }
        let refusedURL = "http://127.0.0.1:\(refused.port)"
        let silentURL = "http://127.0.0.1:\(silent.port)"
        print("DULCET TV ACCOUNT STATES ports refused=\(refused.port) silent=\(silent.port)")

        let app = XCUIApplication()
        let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        let tryAgain = app.buttons["Try Again"].firstMatch
        let signOutButton = app.buttons["Sign Out"].firstMatch

        // 0. A clean start: a saved account from an earlier test is connected and signed out.
        app.launchArguments = []
        app.launch()
        guard let firstSection = awaitSection(app, among: ["Connection", "Library"]) else { return }
        if firstSection == "Library" {
            print("DULCET TV ACCOUNT STATE cleanup=saved-account-signed-out")
            guard connectByLaunchHook(app, server: server), signOutByRemote(app) else { return }
            app.terminate()
            app.launchArguments = []
            app.launch()
            guard awaitSection(app, among: ["Connection"]) != nil else { return }
        }

        // 1. Idle: the empty form, focus in it, Connect not yet available.
        guard primary.waitForExistence(timeout: 20) else {
            XCTFail("Connection must offer its primary action: " + app.debugDescription)
            return
        }
        XCTAssertTrue(primary.label.contains("Connect") && !primary.label.contains("Reconnect"),
                      "Idle offers Connect, observed \(primary.label.debugDescription)")
        XCTAssertFalse(primary.isEnabled, "Connect waits for an address and an account")
        XCTAssertFalse(element(labelPrefix: "Reconnect to", in: app).exists, "Idle names no saved server")
        XCTAssertFalse(signOutButton.exists, "Idle is not connected")
        XCTAssertFalse(tryAgain.exists, "Idle shows no failure")
        XCTAssertEqual(focusedControlIdentifier(app)?.hasPrefix("dulcet.account-connect."), true,
                       "Launch focus belongs in the form: " + app.debugDescription)
        print("DULCET TV ACCOUNT STATE reached=idle primary=\(primary.label.debugDescription)")

        let addressField = app.textFields["dulcet.account-connect.server-address"].firstMatch
        let passwordField = app.secureTextFields["dulcet.account-connect.password"].firstMatch

        // 2. Input error: the form typed by remote, Connect pressed by remote, the address refused.
        guard typeByRemote("ftp://127.0.0.1:\(refused.port)", into: addressField, in: app),
              typeByRemote(server.username, into: app.textFields["dulcet.account-connect.username"].firstMatch, in: app),
              typeByRemote(server.password, into: passwordField, in: app, secure: true),
              pressByRemote(primary, in: app, name: "Connect") else { return }
        let invalidAddress = element(labelPrefix: "Check the server address", in: app)
        guard invalidAddress.waitForExistence(timeout: 15), tryAgain.waitForExistence(timeout: 5) else {
            XCTFail("An ftp:// address must show the input error: " + app.debugDescription)
            return
        }
        print("DULCET TV ACCOUNT STATE reached=error-input title=\(invalidAddress.label.debugDescription)")

        // 3. Security error: plain HTTP to the server with the local-HTTP consent off.
        guard setLocalHTTPByRemote(app, on: false),
              typeByRemote(server.url, into: addressField, in: app),
              pressByRemote(tryAgain, in: app, name: "Try Again") else { return }
        let plaintextRefused = element(labelPrefix: "Local HTTP is not allowed", in: app)
        guard plaintextRefused.waitForExistence(timeout: 15) else {
            XCTFail("Plain HTTP without the consent must show the security error: " + app.debugDescription)
            return
        }
        XCTAssertFalse(invalidAddress.exists, "The input error must be replaced, not joined")
        print("DULCET TV ACCOUNT STATE reached=error-security title=\(plaintextRefused.label.debugDescription)")

        // 4. Transport error: the consent on, by remote, and the system refuses the connection.
        guard setLocalHTTPByRemote(app, on: true),
              typeByRemote(refusedURL, into: addressField, in: app),
              pressByRemote(tryAgain, in: app, name: "Try Again") else { return }
        let unreachable = element(labelPrefix: "The server could not be reached", in: app)
        guard unreachable.waitForExistence(timeout: 45), tryAgain.waitForExistence(timeout: 5) else {
            XCTFail("A refused connection must show the transport error: " + app.debugDescription)
            return
        }
        print("DULCET TV ACCOUNT STATE reached=error-transport title=\(unreachable.label.debugDescription)")

        // 5. Authentication error: the server's own answer to a wrong password.
        guard typeByRemote(server.url, into: app.textFields["dulcet.account-connect.server-address"].firstMatch, in: app),
              typeByRemote(server.password + "-wrong", into: app.secureTextFields["dulcet.account-connect.password"].firstMatch,
                           in: app, secure: true),
              pressByRemote(tryAgain, in: app, name: "Try Again") else { return }
        let rejected = element(labelPrefix: "The username or password was not accepted", in: app)
        guard rejected.waitForExistence(timeout: 45) else {
            XCTFail("A wrong password must show the server's authentication answer: " + app.debugDescription)
            return
        }
        XCTAssertFalse(unreachable.exists, "The earlier transport error must be replaced")
        print("DULCET TV ACCOUNT STATE reached=error-authentication title=\(rejected.label.debugDescription)")

        // 6. Protocol error: the server's web-player address answers, but not as OpenSubsonic.
        guard typeByRemote(server.url + "/app", into: addressField, in: app),
              typeByRemote(server.password, into: passwordField, in: app, secure: true),
              pressByRemote(tryAgain, in: app, name: "Try Again") else { return }
        let notOpenSubsonic = element(labelPrefix: "This is not an OpenSubsonic endpoint", in: app)
        guard notOpenSubsonic.waitForExistence(timeout: 45) else {
            XCTFail("The web-player address must show the protocol error: " + app.debugDescription)
            return
        }
        print("DULCET TV ACCOUNT STATE reached=error-protocol title=\(notOpenSubsonic.label.debugDescription)")

        // 7. Server error: `ping` answered with an OpenSubsonic error the account is not at fault for.
        guard proxy.control("POST", "endpoint=ping&code=0&state=on") != nil else {
            XCTFail("The fault proxy must accept the server-error rule")
            return
        }
        afterTest.append { _ = proxy.control("POST", "state=off") }
        guard typeByRemote(proxy.url, into: addressField, in: app),
              pressByRemote(tryAgain, in: app, name: "Try Again") else { return }
        let serverRejected = element(labelPrefix: "The server rejected account setup", in: app)
        guard serverRejected.waitForExistence(timeout: 45) else {
            XCTFail("An OpenSubsonic server error must show the server error: " + app.debugDescription)
            return
        }
        let answered = proxy.control("POST", "state=off")?["answered"] as? Int ?? -1
        XCTAssertGreaterThan(answered, 0, "The server error shown must be the answer the proxy gave")
        print("DULCET TV ACCOUNT STATE reached=error-server title=\(serverRejected.label.debugDescription) proxy-answers=\(answered)")

        // 8. In progress, then Cancel by remote: a server that accepts and never answers.
        guard typeByRemote(silentURL, into: addressField, in: app),
              pressByRemote(tryAgain, in: app, name: "Try Again") else { return }
        let connecting = element(labelPrefix: "Connecting to the server", in: app)
        guard connecting.waitForExistence(timeout: 10), waitForLabel(containing: "Cancel", of: primary, timeout: 5) else {
            XCTFail("A connection awaiting its server must show progress and offer Cancel: " + app.debugDescription)
            return
        }
        // The primary action ignores a second press within 0.35 s of its first (a doubled Select).
        Thread.sleep(forTimeInterval: 1)
        XCTAssertTrue(connecting.exists, "The silent server must still be awaited")
        print("DULCET TV ACCOUNT STATE reached=in-progress primary=\(primary.label.debugDescription)")
        guard pressByRemote(primary, in: app, name: "Cancel") else { return }
        guard connecting.waitForNonExistence(timeout: 10), waitForLabel(containing: "Connect", of: primary, timeout: 5) else {
            XCTFail("Cancel must end the connection and offer Connect again: " + app.debugDescription)
            return
        }
        XCTAssertFalse(tryAgain.exists, "A cancelled connection is not a failure")
        XCTAssertFalse(serverRejected.exists, "Cancel returns to the form, not the earlier error")
        let accepted = silent.drainAcceptedConnections()
        XCTAssertGreaterThan(accepted, 0, "The progress shown must have been a real connection to the silent port")
        print("DULCET TV ACCOUNT STATE left=in-progress via=cancel silent-connections=\(accepted)")

        // 9. Connected: the account submitted through Connect's own path lands on the library,
        //    and Connection, reached through the section bar, says connected.
        app.terminate()
        guard connectByLaunchHook(app, server: server) else { return }
        let connected = element(labelPrefix: "Connected to", in: app)
        XCTAssertTrue(connected.waitForExistence(timeout: 5), "Connection must say it is connected: " + app.debugDescription)
        print("DULCET TV ACCOUNT STATE reached=connected label=\(connected.label.debugDescription)")

        // 10. Saved and disconnected: a relaunch opens the saved account's library, which offers
        //    Reconnect; Connection names the saved server.
        app.terminate()
        app.launchArguments = []
        app.launch()
        guard awaitSection(app, among: ["Library"]) != nil else { return }
        let reconnect = app.buttons["dulcet.reader.reconnect"].firstMatch
        XCTAssertTrue(reconnect.waitForExistence(timeout: 20), "A saved account's library offers Reconnect: " + app.debugDescription)
        guard selectSection(app, "settings") else {
            XCTFail("The section bar must reach Connection from Library: " + app.debugDescription)
            return
        }
        let savedServer = element(labelPrefix: "Reconnect to", in: app)
        guard savedServer.waitForExistence(timeout: 20), waitForLabel(containing: "Reconnect", of: primary, timeout: 5) else {
            XCTFail("Connection must name the saved server and offer Reconnect: " + app.debugDescription)
            return
        }
        XCTAssertFalse(signOutButton.exists, "A saved account is not connected until Reconnect")
        print("DULCET TV ACCOUNT STATE reached=saved-disconnected label=\(savedServer.label.debugDescription)")

        // 11. Credential persistence: a launch whose active-account pointer the Keychain cannot answer.
        app.terminate()
        app.launchArguments = ["-com.legitimateapps.dulcet.active-account-id", UUID().uuidString]
        app.launch()
        let unreadable = element(labelPrefix: "Your saved account could not be opened", in: app)
        if !unreadable.waitForExistence(timeout: 10) {
            _ = selectSection(app, "settings")
        }
        guard unreadable.waitForExistence(timeout: 20), tryAgain.waitForExistence(timeout: 5) else {
            XCTFail("An unreadable saved account must show the persistence error: " + app.debugDescription)
            return
        }
        XCTAssertFalse(element(labelPrefix: "The account could not be saved", in: app).exists,
                       "A launch-time read must not be described as a failed save")
        print("DULCET TV ACCOUNT STATE reached=error-persistence title=\(unreadable.label.debugDescription)")

        // The read changed nothing: the account is still saved for the next launch.
        app.terminate()
        app.launchArguments = []
        app.launch()
        guard awaitSection(app, among: ["Library"]) != nil else { return }
        XCTAssertTrue(app.buttons["dulcet.reader.reconnect"].firstMatch.waitForExistence(timeout: 20),
                      "The saved account must survive an unreadable pointer: " + app.debugDescription)
        print("DULCET TV ACCOUNT STATES PASS")
    }

    // MARK: - Steps

    /// Launches with the account handed to the form by the DEBUG hook, which submits it through
    /// Connect's own path, waits for the library it lands on, and opens Connection by remote.
    @MainActor
    private func connectByLaunchHook(_ app: XCUIApplication, server: Server) -> Bool {
        app.terminate()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", server.url,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        app.launch()
        let deadline = ContinuousClock.now.advanced(by: .seconds(60))
        while app.navigationBars.firstMatch.identifier != "Library", ContinuousClock.now < deadline {
            Thread.sleep(forTimeInterval: 0.25)
        }
        guard app.navigationBars.firstMatch.identifier == "Library" else {
            XCTFail("A connection made at launch must land on Library: " + app.debugDescription)
            return false
        }
        guard selectSection(app, "settings") else {
            XCTFail("The section bar must reach Connection from Library: " + app.debugDescription)
            return false
        }
        guard app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 60) else {
            XCTFail("The connection must succeed and Connection must offer Sign Out: " + app.debugDescription)
            return false
        }
        return true
    }

    /// Signs the connected account out by remote, through its confirmation, and waits for the
    /// empty form.
    @MainActor
    private func signOutByRemote(_ app: XCUIApplication) -> Bool {
        guard pressByRemote(app.buttons["Sign Out"].firstMatch, in: app, name: "Sign Out") else { return false }
        // The question is a `confirmationDialog`, which tvOS presents as a sheet, not an alert.
        let question = app.sheets["Sign out of this server?"]
        guard question.waitForExistence(timeout: 15) else {
            XCTFail("Sign Out must ask first: " + app.debugDescription)
            return false
        }
        // tvOS draws each action as a button inside a button of the same label, Cancel first.
        let confirm = question.buttons.matching(NSPredicate(format: "label == %@", "Sign Out")).firstMatch
        guard confirm.exists, pressUntilFocused(app, confirm, .right, limit: 4) else {
            XCTFail("The question's Sign Out must take remote focus: " + question.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        guard question.waitForNonExistence(timeout: 15), primary.waitForExistence(timeout: 45),
              waitForLabel(containing: "Connect", of: primary, timeout: 30), !primary.label.contains("Reconnect") else {
            XCTFail("Signing out must return Connection to its empty form: " + app.debugDescription)
            return false
        }
        return true
    }

    /// Cleartext to a loopback server needs the local-HTTP consent, which keeps its value across
    /// edits; set by remote only when it differs.
    @MainActor
    private func setLocalHTTPByRemote(_ app: XCUIApplication, on: Bool) -> Bool {
        let wanted = on ? "1" : "0"
        let consent = app.descendants(matching: .any).matching(NSPredicate(
            format: "label == %@ AND (value == %@ OR value == %@)", "Allow HTTP on this local network", "0", "1"
        )).firstMatch
        guard consent.waitForExistence(timeout: 10) else {
            XCTFail("The local-HTTP consent control must exist: " + app.debugDescription)
            return false
        }
        if (consent.value as? String) == wanted { return true }
        guard focusByRemote(consent, in: app) else {
            XCTFail("The local-HTTP consent must take remote focus: " + app.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        guard waitForValue(wanted, of: consent, timeout: 5) else {
            XCTFail("Select must turn the local-HTTP consent \(on ? "on" : "off"): " + app.debugDescription)
            return false
        }
        return true
    }

    /// Focuses `field` by remote, opens the system keyboard with Select, replaces what the field
    /// holds with `text`, and leaves the keyboard through the field's Next, by remote.
    @MainActor
    private func typeByRemote(_ text: String, into field: XCUIElement, in app: XCUIApplication, secure: Bool = false) -> Bool {
        // Synthesized typing into the system keyboard on a loaded host can drop keystrokes, so each
        // attempt is verified and a short field is retyped, never accepted.
        var observed = ""
        for attempt in 1...3 {
            guard typeByRemoteOnce(text, into: field, in: app) else { return false }
            let settle = ContinuousClock.now.advanced(by: .seconds(10))
            func matches() -> Bool {
                observed = field.value as? String ?? ""
                return secure ? observed.count == text.count : observed == text
            }
            while !matches(), ContinuousClock.now < settle {
                Thread.sleep(forTimeInterval: 0.1)
            }
            if matches() { return true }
            print("DULCET TV ACCOUNT RETYPE target=\(field.identifier) attempt=\(attempt) observed-length=\(observed.count) wanted-length=\(text.count)")
        }
        XCTFail("\(field.identifier) must hold the \(text.count) characters typed, observed \(observed.count)")
        return false
    }

    /// One pass: focus the field by remote, open its keyboard, clear what it holds, type, and
    /// close the keyboard through the field's Next.
    @MainActor
    private func typeByRemoteOnce(_ text: String, into field: XCUIElement, in app: XCUIApplication) -> Bool {
        guard field.waitForExistence(timeout: 10), focusByRemote(field, in: app) else {
            XCTFail("\(field.identifier) must take remote focus: " + app.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        guard app.keyboards.firstMatch.waitForExistence(timeout: 10) else {
            XCTFail("Select on \(field.identifier) must open the keyboard: " + app.debugDescription)
            return false
        }
        // A secure field reports bullets, one per character; clear by that count either way.
        let existing = (field.value as? String).flatMap { $0 == field.placeholderValue ? nil : $0 } ?? ""
        if !existing.isEmpty {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
        }
        field.typeText(text)
        // Every account field sets `.submitLabel(.next)`, so the keyboard's return button reads "next".
        // Selecting it submits the field; the address and username fields then move focus to the
        // following field, whose keyboard opens in turn, so Menu closes that one untouched.
        let submit = app.buttons.matching(NSPredicate(format: "label ==[c] %@", "next")).firstMatch
        guard submit.waitForExistence(timeout: 5) else {
            XCTFail("The keyboard must offer the field's Next: " + app.debugDescription)
            return false
        }
        for _ in 0..<6 where !submit.hasFocus {
            XCUIRemote.shared.press(.down)
        }
        guard submit.hasFocus else {
            XCTFail("Keyboard Next must take remote focus: " + app.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        let following = app.descendants(matching: .any).matching(NSPredicate(
            format: "hasKeyboardFocus == true AND identifier BEGINSWITH %@ AND identifier != %@",
            "dulcet.account-connect.", field.identifier
        )).firstMatch
        if !app.keyboards.firstMatch.waitForNonExistence(timeout: 3), following.exists, app.keyboards.firstMatch.exists {
            print("DULCET TV ACCOUNT KEYBOARD chained-to=\(following.identifier) action=menu")
            XCUIRemote.shared.press(.menu)
        }
        guard app.keyboards.firstMatch.waitForNonExistence(timeout: 10) else {
            XCTFail("Next, then Menu on the following field's keyboard, must close the keyboard: " + app.debugDescription)
            return false
        }
        return true
    }

    /// Focuses `control` by remote and presses Select on it.
    @MainActor
    private func pressByRemote(_ control: XCUIElement, in app: XCUIApplication, name: String) -> Bool {
        guard control.waitForExistence(timeout: 10) else {
            XCTFail("\(name) must be on screen: " + app.debugDescription)
            return false
        }
        guard control.isEnabled else {
            XCTFail("\(name) must be enabled: " + app.debugDescription)
            return false
        }
        guard focusByRemote(control, in: app) else {
            XCTFail("\(name) must take remote focus: " + app.debugDescription)
            return false
        }
        XCUIRemote.shared.press(.select)
        return true
    }

    // MARK: - Focus

    /// The navigation bar's identifier once it is one of `sections`, within 30 seconds.
    @MainActor
    private func awaitSection(_ app: XCUIApplication, among sections: [String]) -> String? {
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        repeat {
            let bar = app.navigationBars.firstMatch
            if bar.exists, sections.contains(bar.identifier) { return bar.identifier }
            Thread.sleep(forTimeInterval: 0.25)
        } while ContinuousClock.now < deadline
        XCTFail("Launch must open one of \(sections); observed "
                + "\(app.navigationBars.firstMatch.identifier.debugDescription): " + app.debugDescription)
        return nil
    }

    /// Walks remote focus to `target` by direction presses toward its frame, at most `bound`.
    @MainActor
    private func focusByRemote(_ target: XCUIElement, in app: XCUIApplication, bound: Int = 16) -> Bool {
        func focusedFrame() -> CGRect? {
            let focused = app.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch
            return focused.exists ? focused.frame : nil
        }
        var presses = 0
        var visited: [CGRect] = []
        var swapAxes = false
        while !(target.exists && target.hasFocus), presses < bound {
            guard let from = focusedFrame() else {
                XCUIRemote.shared.press(.down)
                presses += 1
                continue
            }
            visited.append(from)
            let to = target.frame
            let dy = to.midY - from.midY, dx = to.midX - from.midX
            let vertical: XCUIRemote.Button = dy > 0 ? .down : .up
            let horizontal: XCUIRemote.Button = dx > 0 ? .right : .left
            let verticalGap = to.maxY < from.minY || to.minY > from.maxY ? abs(dy) : 0
            let horizontalGap = to.maxX < from.minX || to.minX > from.maxX ? abs(dx) : 0
            let leadsVertically = verticalGap == horizontalGap ? abs(dy) >= abs(dx) : verticalGap > horizontalGap
            var order = leadsVertically ? [vertical, horizontal] : [horizontal, vertical]
            if swapAxes { order.reverse() }
            swapAxes = false
            for direction in order {
                XCUIRemote.shared.press(direction)
                presses += 1
                guard let now = focusedFrame() else { break }
                if now == from { continue }
                swapAxes = visited.contains(now)
                break
            }
        }
        print("DULCET TV ACCOUNT FOCUS target=\(target.identifier.isEmpty ? target.label : target.identifier)"
              + " presses=\(presses) reached=\(target.hasFocus)")
        return target.exists && target.hasFocus
    }

    /// Presses `button` until `element` holds focus, at most `limit` times.
    @MainActor
    private func pressUntilFocused(_ app: XCUIApplication, _ element: XCUIElement, _ button: XCUIRemote.Button, limit: Int) -> Bool {
        func settles() -> Bool {
            let deadline = ContinuousClock.now.advanced(by: .milliseconds(1500))
            repeat {
                if element.hasFocus { return true }
                Thread.sleep(forTimeInterval: 0.2)
            } while ContinuousClock.now < deadline
            return false
        }
        if settles() { return true }
        for _ in 0..<limit {
            XCUIRemote.shared.press(button)
            if settles() { return true }
        }
        return false
    }

    /// The section whose bar control holds remote focus, or nil while focus is inside a section.
    @MainActor
    private func focusedSection(_ app: XCUIApplication) -> String? {
        Self.sections.first { section in
            let control = app.buttons["dulcet.tab.\(section)"].firstMatch
            return control.exists && control.hasFocus
        }
    }

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

    /// Reaches a section through the bar, by remote: Up onto the bar, across to the section, Select.
    @MainActor
    private func selectSection(_ app: XCUIApplication, _ section: String) -> Bool {
        guard let target = Self.sections.firstIndex(of: section) else { return false }
        var reached = focusedSection(app) != nil
        for _ in 0..<4 where !reached {
            XCUIRemote.shared.press(.up)
            reached = focusedSection(app) != nil
        }
        if !reached {
            // A deep surface such as the album grid reaches the bar by the exit command.
            XCUIRemote.shared.press(.menu)
            reached = focusedSection(app) != nil
        }
        guard reached else { return false }
        for _ in 0...Self.sections.count {
            guard let current = focusedSection(app), let index = Self.sections.firstIndex(of: current) else { return false }
            if index == target { break }
            XCUIRemote.shared.press(index > target ? .left : .right)
        }
        guard focusedSection(app) == section else { return false }
        XCUIRemote.shared.press(.select)
        return true
    }

    // MARK: - Queries

    private typealias Server = (url: String, username: String, password: String)

    private func disposableServer() -> Server? {
        let environment = ProcessInfo.processInfo.environment
        guard let url = environment["DULCET_UI_TEST_SERVER_URL"],
              let username = environment["DULCET_UI_TEST_USERNAME"],
              let password = environment["DULCET_UI_TEST_PASSWORD"] else {
            XCTFail("The disposable server's URL, username and password must be supplied")
            return nil
        }
        guard let components = URLComponents(string: url), components.scheme == "http",
              components.host == "127.0.0.1", components.port != nil else {
            XCTFail("Only a disposable loopback server is allowed; refusing \(URLComponents(string: url)?.host ?? "<none>")")
            return nil
        }
        return (url, username, password)
    }

    private struct FaultProxy {
        let url: String

        /// One request to the proxy's server-error control, answered synchronously: its JSON, or nil.
        func control(_ method: String, _ query: String) -> [String: Any]? {
            guard let target = URL(string: url + "/__dulcet/subsonic-error" + (query.isEmpty ? "" : "?" + query)) else {
                return nil
            }
            var request = URLRequest(url: target)
            request.httpMethod = method
            final class Outcome: @unchecked Sendable { var status = -1; var data: Data?; var error: String? }
            let outcome = Outcome()
            let done = DispatchSemaphore(value: 0)
            let task = URLSession.shared.dataTask(with: request) { data, response, error in
                outcome.status = (response as? HTTPURLResponse)?.statusCode ?? -1
                outcome.data = data
                outcome.error = error.map { String(describing: $0) }
                done.signal()
            }
            task.resume()
            guard done.wait(timeout: .now() + 20) == .success else {
                task.cancel()
                print("DULCET TV ACCOUNT FAULT PROXY \(method) \(query) timed out")
                return nil
            }
            guard outcome.status == 200, let data = outcome.data,
                  let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                print("DULCET TV ACCOUNT FAULT PROXY \(method) \(query) status=\(outcome.status) error=\(outcome.error ?? "none")")
                return nil
            }
            return body
        }
    }

    /// The fault proxy in front of the disposable server; like the server, loopback only.
    private func faultProxy() -> FaultProxy? {
        let url = ProcessInfo.processInfo.environment["DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL"] ?? ""
        guard let components = URLComponents(string: url), components.scheme == "http",
              components.host == "127.0.0.1", components.port != nil else {
            XCTFail("DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL must name the loopback fault proxy; got \(url.debugDescription)")
            return nil
        }
        return FaultProxy(url: url)
    }

    private func element(labelPrefix prefix: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
    }

    private func waitForLabel(containing text: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if element.exists, element.label.contains(text) { return true }
            Thread.sleep(forTimeInterval: 0.1)
        } while Date() < deadline
        return false
    }

    private func waitForValue(_ value: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if element.exists, (element.value as? String) == value { return true }
            Thread.sleep(forTimeInterval: 0.1)
        } while Date() < deadline
        return false
    }
}

/// A loopback TCP port chosen by the system. Not listening, the port is given back at once, so a
/// connection to it is refused (a socket left bound but not listening is not refused here: OBSERVED,
/// the connection waited out the 30-second request limit instead). Listening, the port is held for
/// the length of the test, and a connection completes into the backlog and is never answered, so
/// the client waits until it gives up or is cancelled.
private final class TVLoopbackTestPort {
    let port: UInt16
    private var descriptor: Int32

    init?(listening: Bool) {
        let socketDescriptor = socket(AF_INET, SOCK_STREAM, 0)
        guard socketDescriptor >= 0 else { return nil }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socketDescriptor, $0, length) }
        }
        guard bound == 0, !listening || listen(socketDescriptor, 16) == 0 else {
            close(socketDescriptor)
            return nil
        }
        var assigned = sockaddr_in()
        var assignedLength = length
        let named = withUnsafeMutablePointer(to: &assigned) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socketDescriptor, $0, &assignedLength) }
        }
        guard named == 0 else {
            close(socketDescriptor)
            return nil
        }
        port = UInt16(bigEndian: assigned.sin_port)
        if listening {
            descriptor = socketDescriptor
        } else {
            close(socketDescriptor)
            descriptor = -1
        }
    }

    /// Accepts, and closes, every connection waiting in the backlog; answers how many there were.
    func drainAcceptedConnections() -> Int {
        guard descriptor >= 0 else { return 0 }
        _ = fcntl(descriptor, F_SETFL, fcntl(descriptor, F_GETFL) | O_NONBLOCK)
        var count = 0
        while true {
            let connection = accept(descriptor, nil, nil)
            guard connection >= 0 else { break }
            close(connection)
            count += 1
        }
        return count
    }

    func release() {
        guard descriptor >= 0 else { return }
        close(descriptor)
        descriptor = -1
    }

    deinit { release() }
}
