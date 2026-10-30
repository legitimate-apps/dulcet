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
/// | saved launch | reconnects automatically; an unreachable server leaves the device library and manual Reconnect |
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
    private var tourShot = 0
    private var tourCapturedScreens: Set<String> = []
    private var tourFilledValues: [String: String] = [:]

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

    /// Opt-in photographs of the same real account transitions used by the behavioural proof.
    @MainActor
    func testDesignTourAccountStates() {
        guard ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1" else { return }
        XCUIDevice.shared.orientation = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_ORIENTATION"] == "landscape"
            ? .landscapeLeft : .portrait
        proveEveryAccountConnectState(compact: UIDevice.current.userInterfaceIdiom == .phone)
        XCTAssertEqual(tourShot, 15, "The account tour must photograph every requested state")
        let requested = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_SCREENS"]?
            .split(separator: " ").map(String.init) ?? []
        if !requested.isEmpty {
            XCTAssertEqual(tourCapturedScreens, Set(requested), "Every selected screen must be photographed")
        }
    }

    @MainActor
    private func tourPhotograph(_ screen: String) {
        guard ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1",
              let directory = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_OUT"] else { return }
        let requested = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_SCREENS"]?
            .split(separator: " ").map(String.init) ?? []
        if !requested.isEmpty && !requested.contains(screen) {
            // Retain the canonical state index without photographing unselected states.
            tourShot += 1
            return
        }
        // Photograph the reached state above the floating tab bar, including at large text.
        let app = XCUIApplication()
        if screen == "connection-empty" {
            for _ in 0..<6 {
                if app.staticTexts["dulcet.account-connect.title"].firstMatch.exists { break }
                app.swipeDown()
            }
        } else if screen == "connection-saved-disconnected" {
            let reconnect = app.buttons["dulcet.reader.reconnect"].firstMatch
            guard scrollIntoView(reconnect, matching: .identifier("dulcet.reader.reconnect"), in: app) else { return }
        } else if screen == "connection-filled" {
            let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
            for _ in 0..<6 {
                if primary.exists { break }
                app.swipeUp()
            }
            guard scrollIntoView(primary, matching: .identifier("dulcet.account-connect.primary-action"), in: app) else { return }
        } else if screen == "connection-connected" || screen == "connection-error-persistence" || screen == "connection-error-input" {
            let label = screen == "connection-connected" ? "Sign Out" : "Sign In"
            let action = app.buttons[label].firstMatch
            for _ in 0..<6 {
                if action.exists { break }
                app.swipeUp()
            }
            guard scrollIntoView(action, matching: .button(label), in: app) else { return }
            if screen == "connection-error-persistence" {
                let heading = app.staticTexts["Your saved account could not be opened"].firstMatch
                for _ in 0..<6 {
                    if heading.exists, heading.frame.minY >= app.windows.firstMatch.frame.minY + 116 { break }
                    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35))
                        .press(forDuration: 0.05, thenDragTo:
                            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55)))
                }
                Thread.sleep(forTimeInterval: 0.6)
            }
        }
        tourShot += 1
        let name = String(format: "%02d-%@", tourShot, screen)
        let shot = XCUIScreen.main.screenshot()
        do { try shot.pngRepresentation.write(to: URL(fileURLWithPath: directory).appendingPathComponent(name + ".png")) }
        catch { XCTFail("Could not save tour photograph: \(error)") }
        tourCapturedScreens.insert(screen)
        print("DULCET DESIGN TOUR screen=\(screen) reached=true")
        let accessibilityText = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_TEXT_SIZE"]?
            .contains("Accessibility") == true
        if screen == "connection-error-tls" && accessibilityText {
            // The longest recovery includes a second action below the initial viewport.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.75))
                .press(forDuration: 0.05, thenDragTo:
                    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
            Thread.sleep(forTimeInterval: 0.6)
            let companionName = String(format: "%02d-connection-certificate-help-scrolled", tourShot)
            do {
                try XCUIScreen.main.screenshot().pngRepresentation.write(
                    to: URL(fileURLWithPath: directory).appendingPathComponent(companionName + ".png"))
            } catch { XCTFail("Could not save certificate-help photograph: \(error)") }
        }
        if requested.isEmpty && (screen == "connection-empty" || (screen == "first-run-library" && accessibilityText)) {
            // Companion evidence shows the bottom of a legitimately scrolling intro page.
            for _ in 0..<(accessibilityText ? 6 : 3) { app.swipeUp() }
            Thread.sleep(forTimeInterval: 0.6)
            let companion = screen == "connection-empty" ? "connection-quality-scrolled" : "first-run-library-scrolled"
            let companionName = String(format: "%02d-%@", tourShot, companion)
            do {
                try XCUIScreen.main.screenshot().pngRepresentation.write(
                    to: URL(fileURLWithPath: directory).appendingPathComponent(companionName + ".png"))
            } catch { XCTFail("Could not save scrolled intro photograph: \(error)") }
            if screen == "connection-empty" {
                // Return from the companion settings photograph before typing credentials.
                for _ in 0..<(accessibilityText ? 14 : 6) { app.swipeDown() }
                Thread.sleep(forTimeInterval: 0.6)
            }
        }
    }


    @MainActor
    func testCachedArtworkSurvivesOfflineRelaunchOnIPhone() throws {
        guard requireSimulator(.phone, "The device artwork relaunch proof on iPhone") else { return }
        try proveCachedArtworkSurvivesOfflineRelaunch(compact: true)
    }

    @MainActor
    func testCachedArtworkSurvivesOfflineRelaunchOnIPadOS() throws {
        guard requireSimulator(.pad, "The device artwork relaunch proof on iPad") else { return }
        try proveCachedArtworkSurvivesOfflineRelaunch(compact: false)
    }

    /// Local evidence uses an owned Navidrome process with real fixture covers. The proxy counts
    /// requests; it does not provide images or account outcomes. Unit controls separately prove
    /// synchronous decoded-memory hits and cross-size first paint.
    @MainActor
    private func proveCachedArtworkSurvivesOfflineRelaunch(compact: Bool) throws {
        guard ProcessInfo.processInfo.environment["DULCET_AUTORC_SERVER_CONTROL_URL"] != nil else {
            throw XCTSkip("This proof needs the owned disposable-server process controller")
        }
        guard let server = disposableServer(), let proxy = faultProxy() else { return }
        let app = XCUIApplication()
        withoutIdleWaits(app) { [self] in
            installSystemAlertInterruptionMonitors()
            setLocalFixtureServer(running: true)
            XCTAssertNotNil(proxy.control("POST", "state=off", path: "/__dulcet/outage"))
            defer {
                app.terminate()
                setLocalFixtureAppearance("light")
                setLocalFixtureServer(running: true)
                _ = proxy.control("POST", "state=off", path: "/__dulcet/outage")
            }
            app.launchArguments = [
                "-dulcet-debug-connect-account",
                "-dulcet-debug-account-server-url", proxy.url,
                "-dulcet-debug-account-username", server.username,
                "-dulcet-debug-account-password", server.password,
            ]
            launchInInteractionScope(app)
            // A preceding reconnect proof can leave this account and its disk covers saved.
            // Clear it through production Sign Out before witnessing fresh fixture bytes.
            guard awaitSignOut(in: app, compact: compact), signOut(in: app) else { return }
            app.terminate()
            let before = proxy.control("GET", "", path: "/__dulcet/requests")?["total"] as? Int ?? 0
            launchInInteractionScope(app)
            guard awaitSignOut(in: app, compact: compact), openArtworkGrid(in: app, compact: compact) else { return }
            let deadline = Date().addingTimeInterval(30)
            var fetchedCover = false
            repeat {
                let requests = proxy.control("GET", "since=\(before)", path: "/__dulcet/requests")?["requests"] as? [[String: Any]] ?? []
                fetchedCover = requests.contains { ($0["endpoint"] as? String) == "getCoverArt"
                    && ($0["answered"] as? Bool) == true && ($0["status"] as? Int) == 200 }
                if !fetchedCover { RunLoop.current.run(until: Date().addingTimeInterval(0.5)) }
            } while !fetchedCover && Date() < deadline
            XCTAssertTrue(fetchedCover, "Warm the device from a real successful cover response")
            guard fetchedCover else { return }
            // Let the completed production cache write reach disk before terminating its process.
            RunLoop.current.run(until: Date().addingTimeInterval(1))
            captureArtwork("cached-artwork-online", in: app)
            setLocalFixtureServer(running: false)
            XCTAssertNotNil(proxy.control("POST", "state=on", path: "/__dulcet/outage"))
            let variants: [(String, [String])] = compact ? [
                ("default", []),
                ("dark", []),
                ("largest-text", ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]),
            ] : [("default", [])]
            for (name, arguments) in variants {
                app.terminate()
                setLocalFixtureAppearance(name == "dark" ? "dark" : "light")
                app.launchArguments = arguments
                launchInInteractionScope(app)
                XCTAssertTrue(app.otherElements["dulcet.reader.deviceOnly"].firstMatch.waitForExistence(timeout: 20),
                    "The saved launch must show its device library while negotiation fails")
                guard openArtworkGrid(in: app, compact: compact) else { return }
                captureArtwork("cached-artwork-offline-\(name)", in: app)
                let dropped = proxy.control("GET", "", path: "/__dulcet/outage")?["dropped"] as? [String: Int] ?? [:]
                XCTAssertEqual(dropped["getCoverArt"] ?? 0, 0, "An offline cover must come from the device")
            }
            print("DULCET ARTWORK saved-relaunch=offline variants=\(variants.count) network-cover-requests=0")
        }
    }

    @MainActor
    private func openArtworkGrid(in app: XCUIApplication, compact: Bool) -> Bool {
        guard openRootDestination("Browse", identifier: "browse", in: app) else { return false }
        let albums = app.buttons["dulcet.reader.section.albums"].firstMatch
        let back = app.navigationBars.buttons["BackButton"].firstMatch
        var pops = 0
        while !albums.exists, back.exists, back.isHittable, pops < 4 {
            back.tap()
            pops += 1
        }
        guard tap(albums, in: app, name: "Albums") else { return false }
        let tile = app.buttons["dulcet.library.album"].firstMatch
        for _ in 0..<8 {
            if tile.waitForExistence(timeout: 2), tile.isHittable { return true }
            app.swipeUp()
        }
        XCTFail("The cached library must contain a visible album tile: " + app.debugDescription)
        return false
    }

    @MainActor
    private func openRootDestination(_ title: String, identifier: String, in app: XCUIApplication) -> Bool {
        let close = app.buttons["dulcet.account.close"].firstMatch
        if close.exists {
            close.tap()
            guard close.waitForNonExistence(timeout: 10) else { return false }
        }
        let choices = [app.tabBars.buttons[title].firstMatch,
                       app.descendants(matching: .any)["dulcet.tab.\(identifier)"].firstMatch,
                       app.buttons["dulcet.sidebar.\(identifier)"].firstMatch]
        guard let destination = choices.first(where: { $0.exists && $0.isHittable }) else {
            XCTFail("\(title) must be visible: " + app.debugDescription)
            return false
        }
        destination.tap()
        return true
    }

    @MainActor
    private func captureArtwork(_ name: String, in app: XCUIApplication) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name; shot.lifetime = .keepAlways; add(shot)
    }

    private func setLocalFixtureAppearance(_ appearance: String) {
        guard let url = ProcessInfo.processInfo.environment["DULCET_AUTORC_SERVER_CONTROL_URL"],
              let device = ProcessInfo.processInfo.environment["SIMULATOR_UDID"] else {
            XCTFail("The local artwork capture needs its owned simulator identifier"); return
        }
        let result = FaultProxy(url: url).control("POST", "",
            path: "/appearance?device=\(device)&style=\(appearance)")
        XCTAssertEqual(result?["appearanceConfigured"] as? Bool, true)
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
        let window = app.windows.element(boundBy: 0)
        guard let width = settledWindowWidth(
            window, expecting: expectedCompact ? "compact" : "regular",
            matching: { ($0 < 700) == expectedCompact }
        ) else { return }
        let compact = width < 700
        print("DULCET ACCOUNT STATES destination=\(compact ? "compact" : "regular") width=\(width)")
        guard compact == expectedCompact else {
            XCTFail("This proof expected a \(expectedCompact ? "compact" : "regular") window, got width=\(width)")
            return
        }

        // An earlier test on this simulator may have left its account saved. Sign it out the way a
        // person does, so idle below is the launch of an app with no account.
        let reconnectInLibrary = app.buttons["dulcet.reader.reconnect"].firstMatch
        guard openConnection(in: app, compact: compact) else { return }
        if app.buttons["Sign Out"].firstMatch.waitForExistence(timeout: 1)
            || element(labelPrefix: "Reconnect to", in: app).exists {
            print("DULCET ACCOUNT STATES precondition=saved-account action=sign-out")
            guard awaitSignOut(in: app, compact: compact), signOut(in: app) else { return }
            app.terminate()
            launchInInteractionScope(app)
        }

        if ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1" {
            guard openRootDestination("Library", identifier: "library", in: app) else { return }
            if app.navigationBars["Library"].firstMatch.waitForExistence(timeout: 10),
               !app.buttons["Sign Out"].firstMatch.exists {
                tourPhotograph("first-run-library")
            } else { XCTFail("First-run Library must have no saved account") }
            guard openRootDestination("Search", identifier: "search", in: app) else { return }
            // The interaction scope skips animation-idle waits. Wait for Search's own content,
            // then let the tab transition finish before photographing its resting surface.
            XCTAssertTrue(app.staticTexts["Search your server"].firstMatch.waitForExistence(timeout: 10))
            Thread.sleep(forTimeInterval: 0.6)
            tourPhotograph("first-run-search")
        }

        // 1. Idle.
        let title = app.staticTexts["dulcet.account-connect.title"].firstMatch
        let primary = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        guard openConnection(in: app, compact: compact),
              title.waitForExistence(timeout: 15), reveal(primary, in: app), primary.waitForExistence(timeout: 5) else {
            XCTFail("A launch with no saved account must show the account form: " + app.debugDescription)
            return
        }
        XCTAssertTrue(primary.label == "Sign In",
                      "Idle offers Connect; label=\(primary.label)")
        XCTAssertFalse(primary.isEnabled, "Idle with an empty form cannot submit")
        XCTAssertFalse(element(labelPrefix: "Reconnect to", in: app).exists, "Idle names no saved server")
        XCTAssertFalse(app.buttons["Sign Out"].exists, "Idle is not connected")
        XCTAssertFalse(app.buttons["Try Again"].exists, "Idle shows no failure")
        print("DULCET ACCOUNT STATE reached=idle primary=\(primary.label.debugDescription)")
        tourPhotograph("connection-empty")
        if ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1" {
            guard fill(app, serverURL: server.url, username: server.username, password: server.password) else { return }
            tourPhotograph("connection-filled")
        }

        // 2. An input error: an address that is not an OpenSubsonic URL is refused before any request.
        guard fill(app, serverURL: "ftp://127.0.0.1:\(refused.port)", username: server.username, password: server.password),
              tap(primary, in: app, name: "Connect") else { return }
        let invalidAddress = app.staticTexts["Check the server address"].firstMatch
        let tryAgain = app.buttons["dulcet.account-connect.primary-action"].firstMatch
        guard waitForAccountError("Check the server address", in: app) else {
            XCTFail("An ftp:// address must show the input error: " + app.debugDescription)
            return
        }
        print("DULCET ACCOUNT STATE reached=error-input title=\(invalidAddress.label.debugDescription)")
        tourPhotograph("connection-error-input")

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
        tourPhotograph("connection-error-security")

        // 4. A transport error: the system refuses the connection.
        guard fill(app, serverURL: refusedURL, username: server.username, password: server.password),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let unreachable = app.staticTexts["The server could not be reached"].firstMatch
        guard unreachable.waitForExistence(timeout: 45), tryAgain.waitForExistence(timeout: 5) else {
            XCTFail("A refused connection must show the transport error: " + app.debugDescription)
            return
        }
        print("DULCET ACCOUNT STATE reached=error-transport title=\(unreachable.label.debugDescription)")
        tourPhotograph("connection-error-transport")

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
        tourPhotograph("connection-error-authentication")

        // 6. A protocol error: the server's web-player address answers, but not as OpenSubsonic.
        guard fill(app, serverURL: server.url + "/app", username: server.username, password: server.password),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let notOpenSubsonic = app.staticTexts["This is not an OpenSubsonic endpoint"].firstMatch
        guard notOpenSubsonic.waitForExistence(timeout: 45) else {
            XCTFail("The web-player address must show the protocol error: " + app.debugDescription)
            return
        }
        print("DULCET ACCOUNT STATE reached=error-protocol title=\(notOpenSubsonic.label.debugDescription)")
        tourPhotograph("connection-error-protocol")

        if ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1",
           let tlsURL = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_BAD_TLS_URL"] {
            guard fill(app, serverURL: tlsURL, username: server.username, password: server.password),
                  tap(tryAgain, in: app, name: "Try Again") else { return }
            let tls = app.staticTexts["This server’s certificate isn’t trusted"].firstMatch
            if tls.waitForExistence(timeout: 45) { tourPhotograph("connection-error-tls") }
            else { tourPhotograph("connection-error-tls-UNREACHED"); XCTFail("Untrusted TLS must show its certificate error: " + app.debugDescription) }
        }

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
        tourPhotograph("connection-error-server")

        // 8. In progress, then Cancel: a server that accepts the connection and never answers.
        guard fill(app, serverURL: silentURL, username: server.username, password: server.password),
              tap(tryAgain, in: app, name: "Try Again") else { return }
        let connecting = app.staticTexts["Connecting to the server…"].firstMatch
        guard connecting.waitForExistence(timeout: 15),
              waitForStableHitTarget(primary, matching: .cancel,
                                    in: app.windows.firstMatch, timeout: 5) else {
            XCTFail("Cancel must be visible and tappable without scrolling: " + app.debugDescription)
            return
        }
        // The same snapshots confirmed Cancel's label, enabled state and viewport geometry.
        // Re-reading its live label for a wait and then for logging took 34 s in hosted run
        // 38488956473: the unchanged 30 s connection expired before visibility was inspected.
        print("DULCET ACCOUNT STATE reached=in-progress primary=\"Cancel\" observation=window-snapshot")
        tourPhotograph("connection-connecting")
        let cancelShot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        cancelShot.name = "Cancel visible without scrolling"
        cancelShot.lifetime = .keepAlways
        add(cancelShot)
        primary.tap()
        guard connecting.waitForNonExistence(timeout: 10),
              waitForLabel(containing: "Sign In", of: primary, timeout: 5) else {
            XCTFail("Cancel must end the connection and offer Connect again: " + app.debugDescription)
            return
        }
        let accountErrors = [invalidAddress, plaintextRefused, unreachable, rejected, notOpenSubsonic, serverRejected,
                             app.staticTexts["The server took too long to respond"].firstMatch]
        XCTAssertTrue(accountErrors.allSatisfy { !$0.exists }, "Cancel must clear previous errors without timing out")
        XCTAssertFalse(serverRejected.exists, "Cancel returns to the form, not the earlier error")
        let accepted = silent.drainAcceptedConnections()
        XCTAssertGreaterThan(accepted, 0, "The progress shown must have been a real connection to the silent port")
        print("DULCET ACCOUNT STATE left=in-progress via=cancel silent-connections=\(accepted)")

        // Repeat through Connect at the largest accessibility text size. Prefill skips text
        // entry only, leaving the real connector idle until the visible button starts it.
        // The request's unchanged 30-second deadline therefore cannot begin during launch.
        app.terminate()
        app.launchArguments = [
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            "-dulcet-debug-prefill-account",
            "-dulcet-debug-account-server-url", silentURL,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        launchInInteractionScope(app)
        guard openConnection(in: app, compact: compact),
              tap(primary, in: app, name: "Connect"),
              waitForStableHitTarget(primary, matching: .cancel,
                                    in: app.windows.firstMatch, timeout: 5) else {
            XCTFail("Cancel must be visible and tappable without scrolling at the largest text size: " + app.debugDescription)
            return
        }
        let largestTextShot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        largestTextShot.name = "Cancel visible at largest accessibility text size"
        largestTextShot.lifetime = .keepAlways
        add(largestTextShot)
        primary.tap()
        guard waitForLabel(containing: "Sign In", of: primary, timeout: 5), !connecting.exists,
              accountErrors.allSatisfy({ !$0.exists }) else {
            XCTFail("Cancel at the largest text size must end the request without a failure: " + app.debugDescription)
            return
        }
        let largestTextAccepted = silent.drainAcceptedConnections()
        XCTAssertGreaterThan(largestTextAccepted, 0, "Largest-text Cancel must stop a real silent-server connection")
        print("DULCET ACCOUNT STATE left=in-progress via=cancel text=accessibility-XXXL silent-connections=\(largestTextAccepted)")

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
            "-dulcet-debug-account-server-url", proxy.url,
            "-dulcet-debug-account-username", server.username,
            "-dulcet-debug-account-password", server.password,
        ]
        launchInInteractionScope(app)
        guard awaitSignOut(in: app, compact: compact) else { return }
        let connected = element(labelPrefix: "Connected to", in: app)
        XCTAssertTrue(connected.waitForExistence(timeout: 5), "Connection must say it is connected: " + app.debugDescription)
        print("DULCET ACCOUNT STATE reached=connected label=\(connected.label.debugDescription)")
        tourPhotograph("connection-connected")

        // 10. A fresh process reconnects from its saved credentials without a tap.
        app.terminate()
        app.launchArguments = []
        launchInInteractionScope(app)
        guard awaitSignOut(in: app, compact: compact) else { return }
        XCTAssertTrue(connected.waitForExistence(timeout: 20), "Saved launch must connect without a tap")
        print("DULCET ACCOUNT STATE reached=saved-auto-connected")
        // An unreachable saved launch remains on its library and says what happened once.
        let connectedShot = XCTAttachment(screenshot: app.screenshot())
        connectedShot.name = "saved-launch-auto-connected"; connectedShot.lifetime = .keepAlways; add(connectedShot)
        XCTAssertNotNil(proxy.control("POST", "state=on", path: "/__dulcet/outage"))
        setLocalFixtureServer(running: false)
        defer { setLocalFixtureServer(running: true) }
        defer { _ = proxy.control("POST", "state=off", path: "/__dulcet/outage") }
        app.terminate()
        launchInInteractionScope(app)
        XCTAssertTrue(reconnectInLibrary.waitForExistence(timeout: 20), "Offline library keeps manual Reconnect")
        XCTAssertTrue(app.otherElements["dulcet.reader.deviceOnly"].firstMatch.exists,
                      "The offline status container must retain its stable identity: " + app.debugDescription)
        tourPhotograph("connection-saved-disconnected")
        let observations = proxy.control("GET", "", path: "/__dulcet/outage")
        let dropped = observations?["dropped"] as? [String: Int] ?? [:]
        XCTAssertGreaterThan((dropped["ping"] ?? 0) + (dropped["getOpenSubsonicExtensions"] ?? 0), 0,
            "The saved launch actually tried the unreachable server")
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "saved-launch-offline-library"; shot.lifetime = .keepAlways; add(shot)
        setLocalFixtureServer(running: true)
        XCTAssertNotNil(proxy.control("POST", "state=off", path: "/__dulcet/outage"))
        guard awaitSignOut(in: app, compact: compact) else { return }
        // awaitSignOut can scroll the form past its upper status label. Reveal that same
        // connected status before asserting it; reconnection still occurs without a UI action.
        XCTAssertTrue(reveal(connected, in: app), "Recovered account status must be visible")
        XCTAssertTrue(connected.waitForExistence(timeout: 60), "Transient backoff must recover without a tap")
        print("DULCET ACCOUNT STATE reached=saved-offline-recovered")

        // 11. Credential persistence: the active-account pointer names no Keychain item.
        app.terminate()
        let missingAccount = UUID().uuidString
        app.launchArguments = ["-com.legitimateapps.dulcet.active-account-id", missingAccount]
        launchInInteractionScope(app)
        guard openConnection(in: app, compact: compact) else { return }
        let unreadable = app.staticTexts["Your saved account could not be opened"].firstMatch
        guard reveal(unreadable, in: app), unreadable.waitForExistence(timeout: 15),
              reveal(tryAgain, in: app), tryAgain.waitForExistence(timeout: 5) else {
            XCTFail("An unreadable saved account must show the persistence error: " + app.debugDescription)
            return
        }
        XCTAssertFalse(
            app.staticTexts["The server accepted the account, but the system Keychain did not save it."].exists,
            "A launch-time read must not be described as a save the server just accepted"
        )
        print("DULCET ACCOUNT STATE reached=error-persistence title=\(unreadable.label.debugDescription)"
            + " pointer=planted keychain=real")
        tourPhotograph("connection-error-persistence")

        // The account saved in step 9 is untouched: the pointer above lived in this launch's
        // arguments only, so the next launch opens it again.
        app.terminate()
        app.launchArguments = []
        launchInInteractionScope(app)
        guard awaitSignOut(in: app, compact: compact) else { return }
        XCTAssertTrue(connected.waitForExistence(timeout: 20),
                      "The saved account must reconnect after the planted launch: " + app.debugDescription)
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
        if ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1",
           let size = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR_TEXT_SIZE"],
           !app.launchArguments.contains("-UIPreferredContentSizeCategoryName") {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", size]
        }
        tourFilledValues.removeAll()
        app.launch()
        withoutIdleWaits(app) {}
    }

    // MARK: - Driving the form

    @MainActor
    private func reveal(_ control: XCUIElement, in app: XCUIApplication) -> Bool {
        for direction in [true, false] {
            for _ in 0..<6 {
                if control.waitForExistence(timeout: 1) { return true }
                if direction { app.swipeUp() } else { app.swipeDown() }
            }
        }
        return control.waitForExistence(timeout: 1)
    }

    @MainActor
    private func fill(
        _ app: XCUIApplication, serverURL: String, username: String, password: String, allowLocalHTTP allow: Bool = true
    ) -> Bool {
        guard enterCredential(in: app.textFields["dulcet.account-connect.server-address"].firstMatch,
                          with: serverURL, name: "server address", app: app),
              enterCredential(in: app.textFields["dulcet.account-connect.username"].firstMatch,
                          with: username, name: "username", app: app),
              enterCredential(in: app.secureTextFields["dulcet.account-connect.password"].firstMatch,
                          with: password, name: "password", secure: true, app: app),
              dismissKeyboardIfPresent(in: app) else { return false }
        guard serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased().hasPrefix("http://") else {
            XCTAssertFalse(app.switches["Allow HTTP on this local network"].firstMatch.exists,
                           "Consent must be hidden when the address is not HTTP")
            return true
        }
        // Cleartext to a loopback server needs the local-HTTP consent, which keeps its value
        // across edits; set it only when it differs.
        let allowLocalHTTP = app.switches["Allow HTTP on this local network"].firstMatch
        for _ in 0..<6 {
            if allowLocalHTTP.exists { break }
            app.swipeUp()
        }
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
        guard reveal(control, in: app), control.waitForExistence(timeout: 5) else {
            XCTFail("\(name) must exist on the presented Account page: " + app.debugDescription)
            return false
        }
        // The toolbar keeps Sign In after an error. Match the actual primary control,
        // including retries, rather than the historical action name used in diagnostics.
        let target: HitTarget = control.identifier == "dulcet.account-connect.primary-action"
            ? .identifier("dulcet.account-connect.primary-action") : .button(name)
        guard scrollIntoView(control, matching: target, in: app) else {
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
        let close = app.buttons["dulcet.account.close"].firstMatch
        if close.exists { return true }
        let account = app.buttons["dulcet.account.open"].firstMatch
        guard account.waitForExistence(timeout: 10), account.isHittable else {
            XCTFail("The account toolbar button must open the account sheet: " + app.debugDescription)
            return false
        }
        account.tap()
        return close.waitForExistence(timeout: 10)
    }

    /// Waits for the connected account's Sign Out on Connection, opening Connection when the
    /// connection has moved the app to its library.
    @MainActor
    private func awaitSignOut(in app: XCUIApplication, compact: Bool) -> Bool {
        let signOut = app.buttons["Sign Out"].firstMatch
        let deadline = Date().addingTimeInterval(60)
        // Restoring an account opens Library. A global Sign Out lookup there walks the
        // catalog before navigation can run, and can exhaust the accessibility query budget.
        // openConnection preserves the form when its destination is already selected.
        guard openConnection(in: app, compact: compact) else { return false }
        repeat {
            // Navigation and its accessibility reads can consume the deadline on a loaded
            // host. Accept the connected state just observed before checking elapsed time.
            if signOut.waitForExistence(timeout: 5) { return true }
            app.swipeUp()
            if signOut.exists { return true }
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
            if !confirm.exists {
                for _ in 0..<6 {
                    if primary.exists { break }
                    app.swipeUp()
                }
            }
            if primary.waitForExistence(timeout: 1) { break }
        } while confirm.exists && Date() < confirmationDeadline
        guard primary.waitForExistence(timeout: 45), waitForLabel(containing: "Sign In", of: primary, timeout: 10),
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

    /// Optional local-run process control: CI uses the proxy outage; a local proof can additionally
    /// stop its owned disposable Navidrome and assert that its listener really refused connections.
    private func setLocalFixtureServer(running: Bool) {
        guard let url = ProcessInfo.processInfo.environment["DULCET_AUTORC_SERVER_CONTROL_URL"] else { return }
        let components = URLComponents(string: url)
        guard components?.host == "127.0.0.1", components?.scheme == "http" else {
            XCTFail("The fixture process control must be loopback"); return
        }
        let result = FaultProxy(url: url).control("POST", "", path: running ? "/start" : "/stop")
        XCTAssertEqual(result?[running ? "serverRunning" : "serverStopped"] as? Bool, true)
        print("DULCET SAVED LAUNCH fixture-process=\(running ? "running" : "stopped-and-refused")")
    }

    private struct FaultProxy {
        let url: String

        /// One request to the proxy's server-error control, answered synchronously: its JSON, or nil.
        func control(_ method: String, _ query: String, path: String = "/__dulcet/subsonic-error") -> [String: Any]? {
            guard let target = URL(string: url + path + (query.isEmpty ? "" : "?" + query)) else {
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

    /// Witness both controls in one current window snapshot. A label query timed out on Xcode
    /// 27 even though its failure snapshot contained the expected error and retry button.
    @MainActor
    private func waitForAccountError(_ title: String, in app: XCUIApplication) -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + 30
        let window = app.windows.firstMatch
        repeat {
            if let snapshot = try? window.snapshot(),
               hitTarget(.staticText(title), in: snapshot) != nil,
               hitTarget(.identifier("dulcet.account-connect.primary-action"), in: snapshot) != nil { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        } while ProcessInfo.processInfo.systemUptime < deadline
        return false
    }

    @MainActor
    private func waitForValue(_ expected: String, of element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while (element.value as? String) != expected, Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.1))
        }
        return (element.value as? String) == expected
    }

    /// Reveals a credential row and replaces its text. Form geometry and tour photography stay
    /// here so `replaceText` keeps the self-contained input protocol that
    /// `tools/test-account-connect-text-entry` compiles and replays on its own.
    @MainActor
    private func enterCredential(in field: XCUIElement, with value: String, name: String, secure: Bool = false, app: XCUIApplication) -> Bool {
        // Form virtualizes rows outside its viewport. Reveal the requested credential row
        // before the existing lookup, just as a person scrolls back from an error to edit it.
        let photographing = ProcessInfo.processInfo.environment["DULCET_DESIGN_TOUR"] == "1"
        for _ in 0..<(photographing ? 14 : 6) {
            if field.exists { break }
            if name == "server address" { app.swipeDown() }
            else { app.swipeUp() }
        }
        if photographing {
            guard field.waitForExistence(timeout: 5) else {
                XCTFail("The \(name) field must exist")
                return false
            }
            if tourFilledValues[name] == value {
                let observed = field.value as? String ?? ""
                if secure ? observed.count == value.count : observed == value { return true }
            }
        }
        let entered = replaceText(in: field, with: value, name: name, secure: secure, photographing: photographing,
                                 beforeFocusTap: {
            // Revealing a lower error action can leave this existing field above the window.
            // Recheck the whole field before every cursor tap, including after focus is lost.
            guard scrollIntoView(field, matching: .credential(field.identifier), in: app) else {
                XCTFail("The \(name) field must be reachable before taking keyboard focus")
                return false
            }
            return true
        }) {
            guard photographing else { return }
            print("DULCET DESIGN TOUR focus-failure field=\(name) frame=\(field.frame) keyboard=\(app.keyboards.firstMatch.frame) " + app.debugDescription)
            tourPhotograph("connection-focus-UNREACHED")
        }
        if entered, photographing { tourFilledValues[name] = value }
        return entered
    }

    @MainActor
    private func replaceText(in field: XCUIElement, with value: String, name: String, secure: Bool = false,
                             photographing: Bool = false, beforeFocusTap: () -> Bool = { true },
                             onFocusFailure: () -> Void = {}) -> Bool {
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
        // Each live value read and cursor tap is a separate interaction. Require keyboard
        // focus again for the typing event that follows, rather than retain an earlier check.
        func focusForTyping(retap: Bool = false) -> Bool {
            var focused = !retap && (field.value(forKey: "hasKeyboardFocus") as? Bool ?? false)
            for _ in 0..<4 where !focused {
                guard beforeFocusTap() else { return false }
                if photographing { field.tap() }
                else { field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap() }
                let deadline = Date().addingTimeInterval(3)
                while !(field.value(forKey: "hasKeyboardFocus") as? Bool ?? false), Date() < deadline {
                    RunLoop.current.run(until: Date().addingTimeInterval(0.1))
                }
                focused = field.value(forKey: "hasKeyboardFocus") as? Bool ?? false
            }
            guard focused else {
                onFocusFailure()
                XCTFail("The \(name) field must take keyboard focus on a tap")
                return false
            }
            return true
        }
        var observed = ""
        attempts: for attempt in 1...3 {
            guard focusForTyping(retap: true) else { return false }
            func remaining() -> String? {
                // A live value read records a fatal XCTest failure when the host cannot
                // resolve its query. Read value and placeholder from one throwing snapshot;
                // an unavailable snapshot is unknown, never evidence that the field is empty.
                for _ in 0..<3 {
                    if let snapshot = try? field.snapshot() {
                        return (snapshot.value as? String)
                            .flatMap { $0 == snapshot.placeholderValue ? nil : $0 } ?? ""
                    }
                    RunLoop.current.run(until: Date().addingTimeInterval(0.1))
                }
                return nil
            }
            for round in 0..<4 {
                guard let existing = remaining() else { continue attempts }
                if existing.isEmpty { break }
                guard focusForTyping(retap: round > 0) else { return false }
                field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count))
            }
            guard let left = remaining() else { continue }
            guard left.isEmpty else {
                print("DULCET ACCOUNT STATES clear field=\(name) attempt=\(attempt) remaining-length=\(left.count)")
                continue
            }
            guard focusForTyping() else { return false }
            field.typeText(value)
            guard let replacement = remaining() else { continue }
            observed = replacement
            if secure ? observed.count == value.count : observed == value {
                return true
            }
            print("DULCET ACCOUNT STATES retype field=\(name) attempt=\(attempt) observed-length=\(observed.count) wanted-length=\(value.count)")
        }
        XCTFail("The \(name) field must contain exactly the value typed; observed \(observed.count) of \(value.count) characters")
        return false
    }

    @MainActor
    private func dismissKeyboardIfPresent(in app: XCUIApplication) -> Bool {
        let keyboard = app.keyboards.firstMatch
        func accountStillPresented() -> Bool {
            guard app.buttons["dulcet.account.close"].firstMatch.exists else {
                XCTFail("Keyboard dismissal must preserve the Account sheet: " + app.debugDescription)
                return false
            }
            return true
        }
        // iPad can retain an AX keyboard and its offscreen Hide button after dismissal.
        // Its container then has zero height at the window's bottom edge. Hardware focus
        // and stale descendants do not mean that a software keyboard covers the app.
        func isShown() -> Bool? {
            // Dismissal can remove the keyboard between an exists check and a live frame
            // read. One application snapshot keeps presence and geometry from the same poll.
            // A failed snapshot is unknown, not evidence that the keyboard disappeared.
            guard let root = try? app.snapshot(),
                  let window = root.children.first(where: { $0.elementType == .window && !$0.frame.isEmpty })
            else { return nil }
            var pending: [XCUIElementSnapshot] = [root]
            while let node = pending.popLast() {
                if node.elementType == .keyboard && !node.frame.isEmpty
                    && !node.frame.intersection(window.frame).isEmpty { return true }
                pending.append(contentsOf: node.children)
            }
            return false
        }
        // A Hide tap can land while the keyboard is settling; retain the bounded retries
        // and fail if a visible keyboard still covers the window after all three attempts.
        for attempt in 1...3 {
            let shown = isShown()
            if shown == false { return accountStillPresented() }
            if shown == true {
                let hide = keyboard.buttons["Hide keyboard"].firstMatch
                if hide.waitForExistence(timeout: 2) {
                    hide.tap()
                } else {
                    // Return submits the native form. Scroll upward to dismiss its keyboard;
                    // a downward drag can dismiss the whole Account sheet on iPhone.
                    let form = app.collectionViews["dulcet.account.settingsForm"].firstMatch
                    guard form.exists else {
                        XCTFail("The account form must support keyboard dismissal")
                        return false
                    }
                    form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                        .press(forDuration: 0.05, thenDragTo:
                            form.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.15)),
                               withVelocity: .slow, thenHoldForDuration: 0.3)
                }
            }
            let deadline = Date().addingTimeInterval(10)
            repeat {
                if isShown() == false { return accountStillPresented() }
                Thread.sleep(forTimeInterval: 0.1)
            } while Date() < deadline
            print("DULCET ACCOUNT STATES keyboard-still-shown attempt=\(attempt)")
        }
        XCTFail("The software keyboard must dismiss before the account controls are driven: " + app.debugDescription)
        return false
    }

    /// Match immutable snapshot attributes locally. In particular, consent's value is not a
    /// predicate evaluated remotely over every descendant on each poll.
    private enum HitTarget {
        case identifier(String)
        case credential(String)
        case button(String)
        case staticText(String)
        case localHTTPConsent
        case cancel

        @MainActor
        func matches(_ snapshot: XCUIElementSnapshot) -> Bool {
            switch self {
            case .identifier(let identifier):
                return snapshot.elementType == .button && snapshot.identifier == identifier
            case .credential(let identifier):
                return (snapshot.elementType == .textField || snapshot.elementType == .secureTextField)
                    && snapshot.identifier == identifier
            case .button(let label):
                return snapshot.elementType == .button && snapshot.label == label
            case .staticText(let label):
                return snapshot.elementType == .staticText && snapshot.label == label
            case .localHTTPConsent:
                return snapshot.elementType == .switch && snapshot.label == "Allow HTTP on this local network"
            case .cancel:
                return snapshot.elementType == .button
                    && snapshot.identifier == "dulcet.account-connect.primary-action"
                    && snapshot.label.contains("Cancel") && snapshot.isEnabled
            }
        }
    }

    /// Keeps the whole control clear of the native navigation and tab bars before a real tap.
    @MainActor
    private func scrollIntoView(_ element: XCUIElement, matching target: HitTarget, in app: XCUIApplication) -> Bool {
        let window = app.windows.firstMatch
        for swipe in 0...6 {
            dismissPasswordSavePromptIfPresent()
            if waitForStableHitTarget(element, matching: target, in: window, timeout: 5) { return true }
            if swipe < 6 {
                // A full-window fling can move this error action from behind the tab bar to
                // behind the navigation bar and back. Move a bounded distance toward the
                // content's centre, then hold the finger still to avoid that momentum.
                guard let snapshot = try? window.snapshot() else { return false }
                let viewport = hitViewport(for: target, in: snapshot)
                guard !viewport.isNull, !viewport.isInfinite, !viewport.isEmpty else { return false }
                let targetY = hitTarget(target, in: snapshot)?.frame.midY ?? viewport.maxY
                guard targetY.isFinite else { return false }
                let limit = viewport.height * 0.35
                let delta = min(limit, max(-limit, viewport.midY - targetY))
                // If geometry is already centred but hittability has not arrived, keep
                // waiting. A zero-length press could activate the action instead of scroll.
                guard abs(delta) >= 12 else { continue }
                let origin = window.coordinate(withNormalizedOffset: .zero)
                let start = origin.withOffset(CGVector(dx: viewport.midX - snapshot.frame.minX,
                                                       dy: viewport.midY - snapshot.frame.minY))
                let end = start.withOffset(CGVector(dx: 0, dy: delta))
                start.press(forDuration: 0.05, thenDragTo: end,
                            withVelocity: .slow, thenHoldForDuration: 0.3)
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

    /// XCTest can report a Form row hittable while its native tap point is behind chrome.
    /// In run 39422860211, Try Again's centre was (201, 60.7), above the navigation bar's
    /// bottom at 116. Use the bars from this same snapshot for ordinary proofs and tours.
    @MainActor
    private func hitViewport(for target: HitTarget, in snapshot: XCUIElementSnapshot) -> CGRect {
        var top = snapshot.frame.minY + 16
        var bottom = snapshot.frame.maxY - 24
        let targetFrame = hitTarget(target, in: snapshot)?.frame
        var nativeTargetViewport: CGRect?
        var pending = snapshot.children
        while let node = pending.popLast() {
            let frame = node.frame
            if !frame.isNull && !frame.isInfinite && !frame.isEmpty
                && frame.intersects(snapshot.frame) {
                if (node.elementType == .navigationBar || node.elementType == .tabBar),
                   let nativeTarget = hitTarget(target, in: node), nativeTarget.frame == targetFrame {
                    nativeTargetViewport = frame.intersection(snapshot.frame)
                }
                switch node.elementType {
                case .navigationBar:
                    top = max(top, frame.maxY + 12)
                case .tabBar, .keyboard:
                    bottom = min(bottom, frame.minY - 8)
                default: break
                }
            }
            pending.append(contentsOf: node.children)
        }
        // Library itself is a native tab target in the artwork proof, not a covered Form row.
        return nativeTargetViewport ?? CGRect(x: snapshot.frame.minX, y: top, width: snapshot.frame.width,
                                              height: max(0, bottom - top))
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
        // Settling needs several snapshots, and a loaded host has taken 3.3 s for one (apple-ci
        // run 38667301477 got two polls in five seconds). Wall time alone would then judge the host,
        // not the layout, so the budget also runs until a minimum number of polls, within a cap.
        // An offscreen target still fails on its first snapshot below.
        let minimumPolls = 6
        let hardDeadline = started + timeout * 3
        var previous = CGRect.null
        var previousWindow = CGRect.null
        var stableSince = started
        var polls = 0
        var slowestSnapshot: TimeInterval = 0
        func inBudget(_ time: TimeInterval) -> Bool {
            time < deadline || (polls < minimumPolls && time < hardDeadline)
        }
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
            guard inBudget(now) else { break }
            let frame = hitTarget(target, in: snapshot)?.frame ?? .null
            let visibleFrame = hitViewport(for: target, in: snapshot)
            let visible = !frame.isNull && !frame.isInfinite && !frame.isEmpty
                && visibleFrame.contains(frame)
            // An offscreen frame needs a swipe, not five seconds of identical snapshots.
            if !frame.isNull && !visible { return false }
            if !visible || frame != previous || snapshot.frame != previousWindow {
                previous = frame
                previousWindow = snapshot.frame
                stableSince = now
            } else if now - stableSince >= 0.4 {
                if element.isHittable && inBudget(ProcessInfo.processInfo.systemUptime) {
                    print("DULCET ACCOUNT HIT TARGET settled polls=\(polls) snapshot-max=\(slowestSnapshot)")
                    return true
                }
                stableSince = ProcessInfo.processInfo.systemUptime
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        } while inBudget(ProcessInfo.processInfo.systemUptime)
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
