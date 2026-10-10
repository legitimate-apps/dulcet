import Foundation
import ObjectiveC
import UIKit
import XCTest

/// CONF-09b on iPhone and iPad: every declared account-connect render state, each reached through
/// the transition the shipping app itself makes, in the app process, against the local disposable
/// server -- never an injected outcome.
///
/// | state | how this proof reaches it |
/// |---|---|
/// | idle | a launch with no saved account (an earlier test's account is signed out first) |
/// | domain error, input | an `ftp://` address: the connector refuses it before any request |
/// | domain error, security | the disposable server's plain-HTTP address with the local-HTTP consent off |
/// | domain error, transport | Connect to a loopback port nothing listens on: the system refuses the connection |
/// | domain error, authentication | the disposable server's own answer to a wrong password |
/// | domain error, protocol | the disposable server's web-player address, `/app`: its own 404 page, not OpenSubsonic |
/// | domain error, server | the fault proxy in front of the disposable server answers `ping` with OpenSubsonic error 0 |
/// | in progress | Connect to a loopback port this process listens on and never answers; Cancel ends it |
/// | connected | the right account, submitted through Connect's own path by the DEBUG launch hook: Connection says connected |
/// | saved, disconnected | a relaunch with the account saved: the library offers Reconnect, Connection names the server |
/// | credential persistence | a relaunch whose active-account pointer names no Keychain item |
///
/// The persistence error is reached through the production launch-time read: the pointer, a
/// launch argument in the argument domain of the defaults the store reads, names an account this
/// simulator's real Keychain does not hold, so the store's own `SecItemCopyMatching` answers
/// errSecItemNotFound. That is the state a backup restored to another device leaves (preferences
/// restored, this-device-only Keychain items not); the pointer is planted, the read and its answer
/// are not. A failing Keychain SAVE has no trigger on a simulator, whose Keychain saves succeed --
/// that half is reached for real on the unentitled Mac host instead.
///
/// The server error is the one state whose answer the disposable server does not write: it never
/// sends a server-family error to a valid request, so the fault proxy the other app proofs use
/// (tools/conformance-env/lyrics-fault-proxy) answers the connect sequence's `ping` with a
/// well-formed `failed` envelope, and the app classifies that real HTTP answer.
/// `Capability.Unsupported` has no account-connect origin (spec §18.12) and is not reached.
///
/// Requests go only to loopback: the disposable server, the fault proxy, and two ports this
/// process owns.
final class DulcetAccountConnectStatesUITests: XCTestCase {
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
    func testEveryAccountConnectStateIsReachedThroughTheRealPathOnIPhone() {
        guard requireSimulator(.phone, "The account-connect states proof on iPhone") else { return }
        XCUIDevice.shared.orientation = .portrait
        proveEveryAccountConnectState(compact: true)
    }

    @MainActor
    func testEveryAccountConnectStateIsReachedThroughTheRealPathOnIPadOS() {
        guard requireSimulator(.pad, "The account-connect states proof on iPad") else { return }
        proveEveryAccountConnectState(compact: false)
    }

    /// The event options belong to a process. A scope entered before launch must also reach
    /// the new process, including a replacement created by a relaunch within that scope.
    @MainActor
    func testAnimationWaitOptionsReachEachLaunchedProcessOnIPadOS() {
        guard requireSimulator(.pad, "The interaction-scope lifecycle proof on iPad") else { return }
        let app = XCUIApplication()
        app.launchArguments = ["-dulcet-account-connect-layout-fixture"]
        withoutIdleWaits(app) {
            for launch in 1...2 {
                self.launchInInteractionScope(app)
                let process = app.value(forKey: "currentProcess") as? NSObject
                let applicationOptions = app.value(forKey: "currentInteractionOptions") as? NSNumber
                let processOptions = process?.value(forKey: "interactionOptions") as? NSNumber
                print("DULCET INTERACTION SCOPE launch=\(launch) application=\(String(describing: applicationOptions))"
                    + " process=\(String(describing: processOptions))")
                XCTAssertEqual(applicationOptions?.uint32Value, 3)
                XCTAssertEqual(processOptions?.uint32Value, 3,
                               "The running process must receive both event-wait options")
                app.terminate()
            }
        }
        XCTAssertEqual((app.value(forKey: "currentInteractionOptions") as? NSNumber)?.uint32Value, 0,
                       "The scope must restore ordinary event waits")
    }

    @MainActor
    private func proveEveryAccountConnectState(compact expectedCompact: Bool) {
        let app = XCUIApplication()
        withoutIdleWaits(app) { [self] in
            driveEveryAccountConnectState(in: app, compact: expectedCompact)
        }
    }

    @MainActor
    private func driveEveryAccountConnectState(in app: XCUIApplication, compact expectedCompact: Bool) {
        guard let server = disposableServer(), let proxy = faultProxy() else { return }
        guard let refused = LoopbackTestPort(listening: false),
              let silent = LoopbackTestPort(listening: true) else {
            XCTFail("This process must be able to hold two loopback ports")
            return
        }
        afterTest.append { refused.release(); silent.release() }
        let refusedURL = "http://127.0.0.1:\(refused.port)"
        let silentURL = "http://127.0.0.1:\(silent.port)"
        print("DULCET ACCOUNT STATES ports refused=\(refused.port) silent=\(silent.port)")

        installSystemAlertInterruptionMonitors()
        app.launchArguments = []
        launchInInteractionScope(app)
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 15), "The app window must exist")
        let compact = window.frame.width < 700
        print("DULCET ACCOUNT STATES destination=\(compact ? "compact" : "regular") window=\(window.frame)")
        guard compact == expectedCompact else {
            XCTFail("This proof expected a \(expectedCompact ? "compact" : "regular") window, got \(window.frame)")
            return
        }

        // An earlier test on this simulator may have left its account saved. Sign it out the way a
        // person does, so idle below is the launch of an app with no account.
        let reconnectInLibrary = app.buttons["dulcet.reader.reconnect"].firstMatch
        if reconnectInLibrary.waitForExistence(timeout: 8) {
            print("DULCET ACCOUNT STATES precondition=saved-account action=sign-out")
            reconnectInLibrary.tap()
            guard awaitSignOut(in: app, compact: compact), signOut(in: app) else { return }
            app.terminate()
            launchInInteractionScope(app)
        }

        // 1. Idle.
        let title = app.staticTexts["dulcet.account-connect.title"].firstMatch
        let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        guard openConnection(in: app, compact: compact),
              title.waitForExistence(timeout: 15), primary.waitForExistence(timeout: 5) else {
            XCTFail("A launch with no saved account must show the account form: " + app.debugDescription)
            return
        }
        XCTAssertTrue(primary.label.contains("Connect") && !primary.label.contains("Reconnect"),
                      "Idle offers Connect; label=\(primary.label)")
        XCTAssertFalse(primary.isEnabled, "Idle with an empty form cannot submit")
        XCTAssertFalse(element(labelPrefix: "Reconnect to", in: app).exists, "Idle names no saved server")
        XCTAssertFalse(app.buttons["Sign Out"].exists, "Idle is not connected")
        XCTAssertFalse(app.buttons["Try Again"].exists, "Idle shows no failure")
        print("DULCET ACCOUNT STATE reached=idle primary=\(primary.label.debugDescription)")

        // 2. An input error: an address that is not an OpenSubsonic URL is refused before any request.
        guard fill(app, serverURL: "ftp://127.0.0.1:\(refused.port)", username: server.username, password: server.password),
              tap(primary, in: app, name: "Connect") else { return }
        let invalidAddress = app.staticTexts["Check the server address"].firstMatch
        let tryAgain = app.buttons["Try Again"].firstMatch
        guard invalidAddress.waitForExistence(timeout: 15), tryAgain.waitForExistence(timeout: 5) else {
            XCTFail("An ftp:// address must show the input error: " + app.debugDescription)
            return
        }
        print("DULCET ACCOUNT STATE reached=error-input title=\(invalidAddress.label.debugDescription)")

        // 3. A security error: plain HTTP to the server with the local-HTTP consent off.
        guard fill(app, serverURL: server.url, username: server.username, password: server.password, allowLocalHTTP: false),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let plaintextRefused = app.staticTexts["Local HTTP is not allowed"].firstMatch
        guard plaintextRefused.waitForExistence(timeout: 15) else {
            XCTFail("Plain HTTP without the consent must show the security error: " + app.debugDescription)
            return
        }
        XCTAssertFalse(invalidAddress.exists, "The input error must be replaced, not joined")
        print("DULCET ACCOUNT STATE reached=error-security title=\(plaintextRefused.label.debugDescription)")

        // 4. A transport error: the system refuses the connection.
        guard fill(app, serverURL: refusedURL, username: server.username, password: server.password),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let unreachable = app.staticTexts["The server could not be reached"].firstMatch
        guard unreachable.waitForExistence(timeout: 45), tryAgain.waitForExistence(timeout: 5) else {
            XCTFail("A refused connection must show the transport error: " + app.debugDescription)
            return
        }
        print("DULCET ACCOUNT STATE reached=error-transport title=\(unreachable.label.debugDescription)")

        // 5. An authentication error: the server's own answer to a wrong password.
        guard fill(app, serverURL: server.url, username: server.username, password: server.password + "-wrong"),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let rejected = app.staticTexts["The username or password was not accepted"].firstMatch
        guard rejected.waitForExistence(timeout: 45) else {
            XCTFail("A wrong password must show the server's authentication error: " + app.debugDescription)
            return
        }
        XCTAssertFalse(unreachable.exists, "The transport error must be replaced, not joined")
        print("DULCET ACCOUNT STATE reached=error-authentication title=\(rejected.label.debugDescription)")

        // 6. A protocol error: the server's web-player address answers, but not as OpenSubsonic.
        guard fill(app, serverURL: server.url + "/app", username: server.username, password: server.password),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let notOpenSubsonic = app.staticTexts["This is not an OpenSubsonic endpoint"].firstMatch
        guard notOpenSubsonic.waitForExistence(timeout: 45) else {
            XCTFail("The web-player address must show the protocol error: " + app.debugDescription)
            return
        }
        print("DULCET ACCOUNT STATE reached=error-protocol title=\(notOpenSubsonic.label.debugDescription)")

        // 7. A server error: `ping` answered with an OpenSubsonic error the account is not at fault for.
        guard proxy.control("POST", "endpoint=ping&code=0&state=on") != nil else {
            XCTFail("The fault proxy must accept the server-error rule")
            return
        }
        afterTest.append { _ = proxy.control("POST", "state=off") }
        guard fill(app, serverURL: proxy.url, username: server.username, password: server.password),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let serverRejected = app.staticTexts["The server rejected account setup"].firstMatch
        guard serverRejected.waitForExistence(timeout: 45) else {
            XCTFail("An OpenSubsonic server error must show the server error: " + app.debugDescription)
            return
        }
        let answered = proxy.control("POST", "state=off")?["answered"] as? Int ?? -1
        XCTAssertGreaterThan(answered, 0, "The server error shown must be the answer the proxy gave")
        print("DULCET ACCOUNT STATE reached=error-server title=\(serverRejected.label.debugDescription) proxy-answers=\(answered)")

        // 8. In progress, then Cancel: a server that accepts the connection and never answers.
        guard fill(app, serverURL: silentURL, username: server.username, password: server.password),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let connecting = app.staticTexts["Connecting to the server…"].firstMatch
        guard connecting.waitForExistence(timeout: 15),
              waitForLabel(containing: "Cancel", of: primary, timeout: 5) else {
            XCTFail("A connection waiting on the server must show progress and Cancel: " + app.debugDescription)
            return
        }
        print("DULCET ACCOUNT STATE reached=in-progress primary=\(primary.label.debugDescription)")
        guard tap(primary, in: app, name: "Cancel") else { return }
        guard connecting.waitForNonExistence(timeout: 10),
              waitForLabel(containing: "Connect", of: primary, timeout: 5) else {
            XCTFail("Cancel must end the connection and offer Connect again: " + app.debugDescription)
            return
        }
        XCTAssertFalse(tryAgain.exists, "A cancelled connection is not a failure")
        XCTAssertFalse(serverRejected.exists, "Cancel returns to the form, not the earlier error")
        let accepted = silent.drainAcceptedConnections()
        XCTAssertGreaterThan(accepted, 0, "The progress shown must have been a real connection to the silent port")
        print("DULCET ACCOUNT STATE left=in-progress via=cancel silent-connections=\(accepted)")

        // 9. Connected: the right account lands on the library, and Connection says connected.
        //    The account arrives through the DEBUG launch hook, which hands the form these values
        //    and calls the same `submitAccountConnection()` Connect calls -- the production
        //    connector, the server's answer and the real Keychain save -- because a password
        //    typed into a form that then goes away raises the system's save-password sheet, which
        //    no XCUITest query reaches (docs/TRAPS.md 33). It skips the typing, never the
        //    connecting.
        app.terminate()
        app.launchArguments = [
            "-dulcet-debug-connect-account",
            "-dulcet-debug-account-server-url", server.url,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        launchInInteractionScope(app)
        guard awaitSignOut(in: app, compact: compact) else { return }
        let connected = element(labelPrefix: "Connected to", in: app)
        XCTAssertTrue(connected.waitForExistence(timeout: 5), "Connection must say it is connected: " + app.debugDescription)
        print("DULCET ACCOUNT STATE reached=connected label=\(connected.label.debugDescription)")

        // 10. Saved and disconnected: a relaunch opens the saved account's library, sending nothing
        //    until Reconnect, and Connection names the saved server.
        app.terminate()
        app.launchArguments = []
        launchInInteractionScope(app)
        guard reconnectInLibrary.waitForExistence(timeout: 20) else {
            XCTFail("A relaunch with a saved account must open its library with Reconnect: " + app.debugDescription)
            return
        }
        guard openConnection(in: app, compact: compact) else { return }
        let savedServer = element(labelPrefix: "Reconnect to", in: app)
        guard savedServer.waitForExistence(timeout: 10),
              waitForLabel(containing: "Reconnect", of: primary, timeout: 5) else {
            XCTFail("Connection must name the saved server and offer Reconnect: " + app.debugDescription)
            return
        }
        XCTAssertFalse(app.buttons["Sign Out"].exists, "A saved account is not connected until Reconnect")
        print("DULCET ACCOUNT STATE reached=saved-disconnected label=\(savedServer.label.debugDescription)")

        // 11. Credential persistence: the active-account pointer names no Keychain item.
        app.terminate()
        let missingAccount = UUID().uuidString
        app.launchArguments = ["-com.legitimateapps.dulcet.active-account-id", missingAccount]
        launchInInteractionScope(app)
        guard openConnection(in: app, compact: compact) else { return }
        let unreadable = app.staticTexts["Your saved account could not be opened"].firstMatch
        guard unreadable.waitForExistence(timeout: 15), tryAgain.waitForExistence(timeout: 5) else {
            XCTFail("An unreadable saved account must show the persistence error: " + app.debugDescription)
            return
        }
        XCTAssertFalse(
            app.staticTexts["The server accepted the account, but the system Keychain did not save it."].exists,
            "A launch-time read must not be described as a save the server just accepted"
        )
        print("DULCET ACCOUNT STATE reached=error-persistence title=\(unreadable.label.debugDescription)"
            + " pointer=planted keychain=real")

        // The account saved in step 9 is untouched: the pointer above lived in this launch's
        // arguments only, so the next launch opens it again.
        app.terminate()
        app.launchArguments = []
        launchInInteractionScope(app)
        XCTAssertTrue(reconnectInLibrary.waitForExistence(timeout: 20),
                      "The saved account must still open after the planted launch: " + app.debugDescription)
        print("DULCET ACCOUNT STATES PASS destination=\(compact ? "compact" : "regular")")
    }

    // The proof waits for the actual state of each control below. Waiting for every animation
    // in the app as well is not a state predicate: iPad CI received main-run-loop idle replies
    // and drew the protocol error, but never replied to animation-idle requests after Try Again.
    // Use the same synchronous interaction scope as the row-menu proofs (TRAPS 48), retaining
    // every field/state/server assertion. Each launch reapplies it to the replacement process;
    // no option escapes the outer scope.
    // If Xcode changes the private call's ABI, fall back to its ordinary waits.
    @MainActor
    private func withoutIdleWaits(_ app: XCUIApplication, _ body: @escaping () -> Void) {
        let selector = NSSelectorFromString("_performWithInteractionOptions:block:")
        guard let method = class_getInstanceMethod(type(of: app), selector),
              let encoding = method_getTypeEncoding(method).map({ String(cString: $0) }),
              encoding.filter({ !$0.isNumber }) == "v@:I@?" else {
            print("DULCET ACCOUNT STATES interaction scope unavailable; using ordinary idle waits")
            body()
            return
        }
        typealias Perform = @convention(c) (AnyObject, Selector, UInt32, @convention(block) () -> Void) -> Void
        let perform = unsafeBitCast(method_getImplementation(method), to: Perform.self)
        perform(app, selector, 3, body)
    }

    /// XCTest stores these options on both XCUIApplication and its current process. A relaunch
    /// keeps the application's value but creates a process with ordinary waits. Re-entering the
    /// active scope after launch applies its options to that new process too; the nested scope
    /// restores the outer value, and the outer scope restores ordinary waits when the proof ends.
    @MainActor
    private func launchInInteractionScope(_ app: XCUIApplication) {
        app.launch()
        withoutIdleWaits(app) {}
    }

    // MARK: - Driving the form

    @MainActor
    private func fill(
        _ app: XCUIApplication, serverURL: String, username: String, password: String, allowLocalHTTP allow: Bool = true
    ) -> Bool {
        guard replaceText(in: app.textFields["dulcet.account-connect.server-address"].firstMatch,
                          with: serverURL, name: "server address"),
              replaceText(in: app.textFields["dulcet.account-connect.username"].firstMatch,
                          with: username, name: "username"),
              replaceText(in: app.secureTextFields["dulcet.account-connect.password"].firstMatch,
                          with: password, name: "password", secure: true),
              dismissKeyboardIfPresent(in: app) else { return false }
        // Cleartext to a loopback server needs the local-HTTP consent, which keeps its value
        // across edits; set it only when it differs.
        let allowLocalHTTP = app.switches["Allow HTTP on this local network"].firstMatch
        guard allowLocalHTTP.waitForExistence(timeout: 5) else {
            XCTFail("The local-HTTP consent control must exist: " + app.debugDescription)
            return false
        }
        let wanted = allow ? "1" : "0"
        if (allowLocalHTTP.value as? String) != wanted {
            guard scrollIntoView(allowLocalHTTP, matching: .localHTTPConsent, in: app) else {
                XCTFail("The local-HTTP consent control must be reachable")
                return false
            }
            // The scope suppresses animation-idle waits, so a tap can arrive while the keyboard
            // is still leaving and be dropped (hosted iPhone, run 37868446237). Tap again only
            // while the control still exists, is hittable and provably holds its old value.
            var turned = false
            for attempt in 1...3 {
                allowLocalHTTP.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
                if waitForValue(wanted, of: allowLocalHTTP, timeout: 5) { turned = true; break }
                guard attempt < 3, allowLocalHTTP.exists, allowLocalHTTP.isHittable,
                      (allowLocalHTTP.value as? String) != wanted else { break }
                print("DULCET ACCOUNT STATES consent re-tap attempt=\(attempt + 1)")
            }
            guard turned else {
                XCTFail("The local-HTTP consent control must turn \(allow ? "on" : "off")")
                return false
            }
        }
        return true
    }

    @MainActor
    private func tap(_ control: XCUIElement, in app: XCUIApplication, name: String) -> Bool {
        let target: HitTarget = (name == "Connect" || name == "Cancel")
            ? .identifier("dulcet.account-connect.primary-action") : .button(name)
        guard control.waitForExistence(timeout: 5), scrollIntoView(control, matching: target, in: app) else {
            XCTFail("\(name) must be reachable: " + app.debugDescription)
            return false
        }
        guard control.isEnabled else {
            XCTFail("\(name) must be enabled: " + app.debugDescription)
            return false
        }
        control.tap()
        return true
    }

    @MainActor
    private func openConnection(in app: XCUIApplication, compact: Bool) -> Bool {
        if compact {
            let tab = app.tabBars.buttons["Connection"].firstMatch
            guard tab.waitForExistence(timeout: 10) else {
                XCTFail("A compact window must present the Connection tab: " + app.debugDescription)
                return false
            }
            tab.tap()
            return true
        }
        let row = app.staticTexts["dulcet.sidebar.settings"].firstMatch
        guard row.waitForExistence(timeout: 10), row.isHittable else {
            XCTFail("The Connection row must be visible in the sidebar: " + app.debugDescription)
            return false
        }
        row.tap()
        return true
    }

    /// Waits for the connected account's Sign Out on Connection, opening Connection when the
    /// connection has moved the app to its library.
    @MainActor
    private func awaitSignOut(in app: XCUIApplication, compact: Bool) -> Bool {
        let signOut = app.buttons["Sign Out"].firstMatch
        let deadline = Date().addingTimeInterval(60)
        repeat {
            if signOut.waitForExistence(timeout: 5) { return true }
            guard openConnection(in: app, compact: compact) else { return false }
        } while Date() < deadline
        XCTFail("The connection must succeed and Connection must offer Sign Out: " + app.debugDescription)
        return false
    }

    /// Signs the connected account out through its confirmation, and waits for the empty form.
    @MainActor
    private func signOut(in app: XCUIApplication) -> Bool {
        let signOut = app.buttons["Sign Out"].firstMatch
        guard tap(signOut, in: app, name: "Sign Out") else { return false }
        let confirmations = [app.sheets, app.alerts, app.popovers].map { $0.buttons["Sign Out"].firstMatch }
        let deadline = Date().addingTimeInterval(10)
        while !confirmations.contains(where: \.exists), Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        guard let confirm = confirmations.first(where: \.exists) else {
            XCTFail("Sign Out must ask for confirmation: " + app.debugDescription)
            return false
        }
        let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        // The popover can be accessible before its opening animation accepts a tap. Wait for
        // the result and retry only while the confirmation remains, instead of relying on event
        // quiescence to finish that animation for us.
        let confirmationDeadline = Date().addingTimeInterval(10)
        repeat {
            if confirm.exists && confirm.isHittable { confirm.tap() }
            if primary.waitForExistence(timeout: 1) { break }
        } while confirm.exists && Date() < confirmationDeadline
        guard primary.waitForExistence(timeout: 45), waitForLabel(containing: "Connect", of: primary, timeout: 10),
              !primary.label.contains("Reconnect") else {
            XCTFail("Signing out must return Connection to its empty form: " + app.debugDescription)
            return false
        }
        return true
    }

    // MARK: - Helpers

    private struct DisposableServer {
        let url: String
        let username: String
        let password: String
    }

    /// The disposable server CI and local runs pass in. Its URL must name a loopback host: this
    /// proof signs in, and writes nothing, but it is never pointed anywhere else.
    private func disposableServer() -> DisposableServer? {
        let environment = ProcessInfo.processInfo.environment
        func value(_ key: String) -> String? {
            let found = environment[key]?.trimmingCharacters(in: .whitespacesAndNewlines)
            return found?.isEmpty == false ? found : nil
        }
        guard let url = value("DULCET_UI_TEST_SERVER_URL"),
              let username = value("DULCET_UI_TEST_USERNAME"),
              let password = value("DULCET_UI_TEST_PASSWORD") else {
            XCTFail("Set DULCET_UI_TEST_SERVER_URL, DULCET_UI_TEST_USERNAME and DULCET_UI_TEST_PASSWORD")
            return nil
        }
        let host = URLComponents(string: url)?.host?.lowercased() ?? ""
        guard host == "localhost" || host == "::1" || host.hasPrefix("127.") else {
            XCTFail("DULCET_UI_TEST_SERVER_URL must name a loopback host; refusing '\(host)'")
            return nil
        }
        return DisposableServer(url: url, username: username, password: password)
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
                print("DULCET ACCOUNT STATES FAULT PROXY \(method) \(query) timed out")
                return nil
            }
            guard outcome.status == 200, let data = outcome.data,
                  let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
                print("DULCET ACCOUNT STATES FAULT PROXY \(method) \(query) status=\(outcome.status) error=\(outcome.error ?? "none")")
                return nil
            }
            return body
        }
    }

    /// The fault proxy in front of the disposable server; like the server, loopback only.
    private func faultProxy() -> FaultProxy? {
        let url = ProcessInfo.processInfo.environment["DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL"]?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let host = URLComponents(string: url)?.host?.lowercased() ?? ""
        guard host == "localhost" || host.hasPrefix("127.") else {
            XCTFail("DULCET_UI_TEST_LYRICS_FAULT_PROXY_URL must name the loopback fault proxy; got \(url.debugDescription)")
            return nil
        }
        return FaultProxy(url: url)
    }

    @MainActor
    private func requireSimulator(_ idiom: UIUserInterfaceIdiom, _ proof: String) -> Bool {
        let environment = ProcessInfo.processInfo.environment
        let name = environment["SIMULATOR_DEVICE_NAME"] ?? "<none>"
        let observed = UIDevice.current.userInterfaceIdiom
        print("DULCET UI DESTINATION proof=\(proof.debugDescription) simulator=\(environment["SIMULATOR_UDID"] != nil)"
            + " name=\(name.debugDescription) idiom=\(observed.rawValue)")
        guard environment["SIMULATOR_UDID"] != nil else {
            XCTFail("\(proof) requires a simulator; a physical device is not valid evidence")
            return false
        }
        guard observed == idiom else {
            XCTFail("\(proof) requires idiom \(idiom.rawValue) but runs on \(name) (idiom \(observed.rawValue))")
            return false
        }
        return true
    }

    /// The first element whose label begins with `prefix`: a combined status line is exposed as
    /// one element whose type differs between idioms.
    @MainActor
    private func element(labelPrefix prefix: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
    }

    @MainActor
    private func waitForLabel(containing text: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !(element.exists && element.label.contains(text)), Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        return element.exists && element.label.contains(text)
    }

    @MainActor
    private func waitForValue(_ expected: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while (element.value as? String) != expected, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        return (element.value as? String) == expected
    }

    @MainActor
    private func replaceText(in field: XCUIElement, with value: String, name: String, secure: Bool = false) -> Bool {
        guard field.waitForExistence(timeout: 5) else {
            XCTFail("The \(name) field must exist")
            return false
        }
        // Synthesized typing on a loaded host can drop keystrokes or land before the field takes
        // focus, so each attempt is verified and a short field is retyped, never accepted.
        // Clear with ordinary Delete input: Command-A attaches a synthetic hardware keyboard.
        // This is an input protocol choice; it does not diagnose UIKit animation-idle failures.
        // A tap near the trailing edge lands at the end of text that fits the field, but text wider
        // than the field puts the insertion point under the tap instead (iPhone CI kept 3 of a
        // longer address's characters), so clearing repeats until the field reads empty.
        // Secure fields report one bullet per character and are cleared the same way.
        var observed = ""
        for attempt in 1...3 {
            var focused = false
            for _ in 0..<4 where !focused {
                field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
                let deadline = Date().addingTimeInterval(3)
                while !(field.value(forKey: "hasKeyboardFocus") as? Bool ?? false), Date() < deadline {
                    RunLoop.current.run(until: Date().addingTimeInterval(0.1))
                }
                focused = field.value(forKey: "hasKeyboardFocus") as? Bool ?? false
            }
            guard focused else {
                XCTFail("The \(name) field must take keyboard focus on a tap")
                return false
            }
            func remaining() -> String {
                (field.value as? String).flatMap { $0 == field.placeholderValue ? nil : $0 } ?? ""
            }
            for round in 0..<4 {
                let existing = remaining()
                if existing.isEmpty { break }
                if round > 0 { field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap() }
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
            }
            let left = remaining()
            guard left.isEmpty else {
                print("DULCET ACCOUNT STATES clear field=\(name) attempt=\(attempt) remaining-length=\(left.count)")
                continue
            }
            field.typeText(value)
            observed = field.value as? String ?? ""
            if secure ? observed.count == value.count : observed == value { return true }
            print("DULCET ACCOUNT STATES retype field=\(name) attempt=\(attempt) observed-length=\(observed.count) wanted-length=\(value.count)")
        }
        XCTFail("The \(name) field must contain exactly the value typed; observed \(observed.count) of \(value.count) characters")
        return false
    }

    @MainActor
    private func dismissKeyboardIfPresent(in app: XCUIApplication) -> Bool {
        let keyboard = app.keyboards.firstMatch
        // A Hide tap on a loaded host can land while the keyboard is still settling; try again
        // before calling the keyboard stuck.
        for attempt in 1...3 {
            guard keyboard.exists else { return true }
            let hide = keyboard.buttons["Hide keyboard"].firstMatch
            if hide.waitForExistence(timeout: 2) {
                hide.tap()
            } else {
                keyboard.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.95)).tap()
            }
            if keyboard.waitForNonExistence(timeout: 10) { return true }
            print("DULCET ACCOUNT STATES keyboard-still-shown attempt=\(attempt)")
        }
        XCTFail("The software keyboard must dismiss before the account controls are driven: " + app.debugDescription)
        return false
    }

    /// Match immutable snapshot attributes locally. In particular, consent's value is not a
    /// predicate evaluated remotely over every descendant on each poll.
    private enum HitTarget {
        case identifier(String)
        case button(String)
        case localHTTPConsent

        @MainActor
        func matches(_ snapshot: XCUIElementSnapshot) -> Bool {
            switch self {
            case .identifier(let identifier):
                return snapshot.elementType == .button && snapshot.identifier == identifier
            case .button(let label):
                return snapshot.elementType == .button && snapshot.label == label
            case .localHTTPConsent:
                return snapshot.elementType == .switch && snapshot.label == "Allow HTTP on this local network"
            }
        }
    }

    /// Scrolls until the element's midpoint is inside the window and hittable, at most six swipes.
    @MainActor
    private func scrollIntoView(_ element: XCUIElement, matching target: HitTarget, in app: XCUIApplication) -> Bool {
        let window = app.windows.firstMatch
        for swipe in 0...6 {
            dismissPasswordSavePromptIfPresent()
            if waitForStableHitTarget(element, matching: target, in: window, timeout: 5) { return true }
            if swipe < 6 {
                app.swipeUp()
                RunLoop.current.run(until: Date().addingTimeInterval(0.25))
            }
        }
        return false
    }

    @MainActor
    private func hitTarget(_ target: HitTarget, in snapshot: XCUIElementSnapshot) -> XCUIElementSnapshot? {
        if target.matches(snapshot) { return snapshot }
        for child in snapshot.children {
            if let found = hitTarget(target, in: child) { return found }
        }
        return nil
    }

    /// One window snapshot supplies both frames and target presence for a poll. Live element
    /// properties each re-resolve the query; reading frame before exists can fail if Cancel has
    /// meanwhile become a timeout error. Snapshot attributes do not issue additional queries.
    /// Hittability is not a public snapshot attribute: check it only after geometry settles,
    /// still inside the event-wait scope, so unrelated animation-idle cannot block the tap.
    @MainActor
    private func waitForStableHitTarget(
        _ element: XCUIElement, matching target: HitTarget, in window: XCUIElement, timeout: TimeInterval
    ) -> Bool {
        let started = ProcessInfo.processInfo.systemUptime
        let deadline = started + timeout
        var previous = CGRect.null
        var previousWindow = CGRect.null
        var stableSince = started
        var polls = 0
        var slowestSnapshot: TimeInterval = 0
        repeat {
            let beforeSnapshot = ProcessInfo.processInfo.systemUptime
            let snapshot: XCUIElementSnapshot
            do { snapshot = try window.snapshot() } catch {
                XCTFail("The account window snapshot must succeed: \(error)")
                return false
            }
            let now = ProcessInfo.processInfo.systemUptime
            polls += 1
            slowestSnapshot = max(slowestSnapshot, now - beforeSnapshot)
            guard now < deadline else { break }
            let frame = hitTarget(target, in: snapshot)?.frame ?? .null
            let visible = !frame.isNull && !frame.isEmpty
                && snapshot.frame.contains(CGPoint(x: frame.midX, y: frame.midY))
            // An offscreen frame needs a swipe, not five seconds of identical snapshots.
            if !frame.isNull && !visible { return false }
            if !visible || frame != previous || snapshot.frame != previousWindow {
                previous = frame
                previousWindow = snapshot.frame
                stableSince = now
            } else if now - stableSince >= 0.4 {
                if element.isHittable && ProcessInfo.processInfo.systemUptime < deadline {
                    print("DULCET ACCOUNT HIT TARGET settled polls=\(polls) snapshot-max=\(slowestSnapshot)")
                    return true
                }
                stableSince = ProcessInfo.processInfo.systemUptime
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        } while ProcessInfo.processInfo.systemUptime < deadline
        print("DULCET ACCOUNT HIT TARGET timeout polls=\(polls) snapshot-max=\(slowestSnapshot)")
        return false
    }

    /// The system's offer to save the password can appear after a successful sign-in. Only its
    /// known decline labels are pressed.
    @MainActor
    private func dismissPasswordSavePromptIfPresent() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let decline = springboard.buttons.matching(NSPredicate(
            format: "label IN %@", ["Not Now", "Never", "No Thanks", "Don't Save", "Don’t Save"]
        )).firstMatch
        if decline.exists {
            print("DULCET_UI_SYSTEM_ALERT handled=proactive button=\(decline.label)")
            decline.tap()
        }
    }

    @MainActor
    private func installSystemAlertInterruptionMonitors() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        _ = addUIInterruptionMonitor(withDescription: "Decline saving the password or allow local network") { _ in
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
}

/// A loopback TCP port chosen by the system. Not listening, the port is given back at once, so a
/// connection to it is refused (a socket left bound but not listening is not refused here: OBSERVED,
/// the connection waited out the 30-second request limit instead). Listening, the port is held for
/// the length of the test, and a connection completes into the backlog and is never answered, so
/// the client waits until it gives up or is cancelled.
private final class LoopbackTestPort {
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
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(socketDescriptor, $0, &assignedLength)
            }
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
        let flags = fcntl(descriptor, F_GETFL)
        _ = fcntl(descriptor, F_SETFL, flags | O_NONBLOCK)
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
