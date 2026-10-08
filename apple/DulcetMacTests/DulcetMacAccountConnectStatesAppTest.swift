import AppKit
import DulcetCore
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

/// CONF-09b on the Mac: every declared account-connect render state, reached through the
/// transitions the app makes, rendered by the production root view in a window of the hosted app,
/// and read back from that window's accessibility tree. Nothing is injected: every outcome is the
/// production connector's answer, or the credential store's.
///
/// Part 1 is the shipping composition, `DulcetMacProduction.makePresentationStore()`, with the
/// data-protection Keychain. This host is signed ad hoc without entitlements (as apple-ci builds
/// it, as `connectSuccessCrossesLiveKotlinFacadeIntoPersistenceFailureState` relies on too), so
/// the Keychain refuses its save for real, and both persistence errors are the real store's
/// answers. The server error is the one state whose answer the disposable server does not write: it
/// never sends a server-family error to a valid request, so the fault proxy the other app proofs
/// use (tools/conformance-env/lyrics-fault-proxy) answers the connect sequence's `ping` with a
/// well-formed `failed` envelope, and the connector, the Apple adapter and the view classify that
/// real HTTP answer. `Capability.Unsupported` has no account-connect origin (spec §18.12) and is not
/// reached.
///
/// | state | how this proof reaches it |
/// |---|---|
/// | idle | a launch with no active-account pointer |
/// | domain error, input | an `ftp://` address: the connector refuses it before any request |
/// | domain error, security | the disposable server's plain-HTTP address without the local-HTTP consent |
/// | domain error, transport | Connect to a loopback port nothing listens on: the system refuses the connection |
/// | domain error, authentication | the disposable server's own answer to a wrong password |
/// | domain error, protocol | the disposable server's web-player address, `/app`: its own 404 page, not OpenSubsonic |
/// | domain error, server | the fault proxy in front of the disposable server answers `ping` with OpenSubsonic error 0 |
/// | in progress | Connect to a loopback port this process listens on and never answers; Cancel ends it |
/// | credential persistence, save | the right password: the server accepts, the Keychain save is refused |
/// | credential persistence, load | a launch whose active-account pointer the Keychain cannot answer |
///
/// Part 2 keeps everything production -- connector, reader with a database of its own, root view
/// -- except the credential store, an in-memory stand-in, because this host's Keychain cannot hold
/// an account: connected (the connection lands on the library, and Connection says connected), and
/// saved and disconnected (a second data source over the same store and reader database, as a
/// relaunch builds it). The signed Keychain's half of those two states needs an entitled host and
/// is not claimed here.
@MainActor
final class DulcetMacAccountConnectStatesAppTest: XCTestCase {
    private let activeAccountKey = "com.legitimateapps.dulcet.active-account-id"
    private let username = "dulcet-admin"
    private let password = "dulcet-ci-canary-password"

    func everyAccountConnectStateIsReachedThroughTheHostedMacApp() async throws {
        let server = try disposableServer()
        let proxy = try faultProxy()
        let refused = try XCTUnwrap(MacLoopbackTestPort(listening: false), "a bound loopback port")
        let silent = try XCTUnwrap(MacLoopbackTestPort(listening: true), "a listening loopback port")
        defer { refused.release(); silent.release() }
        let refusedURL = "http://127.0.0.1:\(refused.port)"
        let silentURL = "http://127.0.0.1:\(silent.port)"
        print("DULCET MAC ACCOUNT STATES ports refused=\(refused.port) silent=\(silent.port)")

        let enhancedUI = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
        let previousEnhancedUI = NSApp.accessibilityAttributeValue(enhancedUI) ?? false
        NSApp.accessibilitySetValue(true, forAttribute: enhancedUI)
        defer { NSApp.accessibilitySetValue(previousEnhancedUI, forAttribute: enhancedUI) }

        let defaults = UserDefaults.standard
        let previousPointer = defaults.string(forKey: activeAccountKey)
        defaults.removeObject(forKey: activeAccountKey)
        defer {
            if let previousPointer {
                defaults.set(previousPointer, forKey: activeAccountKey)
            } else {
                defaults.removeObject(forKey: activeAccountKey)
            }
        }

        // MARK: Part 1 -- the shipping composition.
        let shipping = AccountStatesWindow(store: DulcetMacProduction.makePresentationStore())
        defer { shipping.close() }
        let store = shipping.store

        // 1. Idle.
        XCTAssertEqual(store.snapshot.state, .accountConnectIdle)
        _ = try await shipping.element(identifiedBy: "dulcet.account-connect.title")
        let primary = try await shipping.element(identifiedBy: "dulcet.account-connect.primary-action")
        XCTAssertEqual(shipping.label(primary), "Connect", "Idle offers Connect")
        XCTAssertNil(shipping.firstElement(labelled: "Sign Out"), "Idle is not connected")
        XCTAssertNil(shipping.firstElement(labelled: "Try Again"), "Idle shows no failure")
        print("DULCET MAC ACCOUNT STATE reached=idle state=\(store.snapshot.state.rawValue)")

        // 2. Input error: an address that is not an OpenSubsonic URL is refused before any request.
        enter("ftp://127.0.0.1:\(refused.port)", password: server.password, into: store)
        try shipping.press(try await shipping.element(identifiedBy: "dulcet.account-connect.primary-action"), named: "Connect")
        try await waitUntil(timeout: .seconds(15), "input error: state=\(store.snapshot.state)") {
            store.snapshot.state == .accountErrorInput
        }
        _ = try await shipping.element(labelled: "Check the server address")
        print("DULCET MAC ACCOUNT STATE reached=error-input state=\(store.snapshot.state.rawValue)")

        // 3. Security error: plain HTTP to the server without the local-HTTP consent.
        enter(server.baseURL, password: server.password, allowLocalHTTP: false, into: store)
        try shipping.press(try await shipping.element(labelled: "Try Again"), named: "Try Again")
        try await waitUntil(timeout: .seconds(15), "security error: state=\(store.snapshot.state)") {
            store.snapshot.state == .accountErrorSecurity
        }
        _ = try await shipping.element(labelled: "Local HTTP is not allowed")
        XCTAssertNil(shipping.firstElement(labelled: "Check the server address"))
        print("DULCET MAC ACCOUNT STATE reached=error-security state=\(store.snapshot.state.rawValue)")

        // 4. Transport error: the system refuses the connection.
        enter(refusedURL, password: server.password, into: store)
        try shipping.press(try await shipping.element(labelled: "Try Again"), named: "Try Again")
        try await waitUntil(timeout: .seconds(45), "transport error: state=\(store.snapshot.state)") {
            store.snapshot.state == .accountErrorTransport
        }
        _ = try await shipping.element(labelled: "The server could not be reached")
        print("DULCET MAC ACCOUNT STATE reached=error-transport state=\(store.snapshot.state.rawValue)")

        // 5. Authentication error: the server's own answer to a wrong password.
        enter(server.baseURL, password: server.password + "-wrong", into: store)
        try shipping.press(try await shipping.element(labelled: "Try Again"), named: "Try Again")
        try await waitUntil(timeout: .seconds(45), "authentication error: state=\(store.snapshot.state)") {
            store.snapshot.state == .accountErrorAuthentication
        }
        _ = try await shipping.element(labelled: "The username or password was not accepted")
        XCTAssertNil(shipping.firstElement(labelled: "The server could not be reached"))
        print("DULCET MAC ACCOUNT STATE reached=error-authentication state=\(store.snapshot.state.rawValue)")

        // 6. Protocol error: the server's web-player address answers, but not as OpenSubsonic.
        enter(server.baseURL + "/app", password: server.password, into: store)
        try shipping.press(try await shipping.element(labelled: "Try Again"), named: "Try Again")
        try await waitUntil(timeout: .seconds(45), "protocol error: state=\(store.snapshot.state)") {
            store.snapshot.state == .accountErrorProtocol
        }
        _ = try await shipping.element(labelled: "This is not an OpenSubsonic endpoint")
        print("DULCET MAC ACCOUNT STATE reached=error-protocol state=\(store.snapshot.state.rawValue)")

        // 7. Server error: `ping` answered with an OpenSubsonic error the account is not at fault for.
        try await proxy.armServerError()
        defer { proxy.disarmServerError() }
        enter(proxy.url, password: server.password, into: store)
        try shipping.press(try await shipping.element(labelled: "Try Again"), named: "Try Again")
        try await waitUntil(timeout: .seconds(45), "server error: state=\(store.snapshot.state)") {
            store.snapshot.state == .accountErrorServer
        }
        _ = try await shipping.element(labelled: "The server rejected account setup")
        let answered = try await proxy.serverErrorsAnswered()
        XCTAssertGreaterThan(answered, 0, "The server error shown must be the answer the proxy gave")
        proxy.disarmServerError()
        print("DULCET MAC ACCOUNT STATE reached=error-server state=\(store.snapshot.state.rawValue) proxy-answers=\(answered)")

        // 8. In progress, then Cancel: a server that accepts the connection and never answers.
        enter(silentURL, password: server.password, into: store)
        try shipping.press(try await shipping.element(labelled: "Try Again"), named: "Try Again")
        try await waitUntil(timeout: .seconds(10), "in progress: state=\(store.snapshot.state)") {
            store.snapshot.state == .accountConnecting
        }
        _ = try await shipping.element(labelled: "Connecting to the server…")
        let cancel = try await shipping.element(identifiedBy: "dulcet.account-connect.primary-action")
        XCTAssertEqual(shipping.label(cancel), "Cancel", "In progress offers Cancel")
        // The primary action ignores a second activation inside the system double-click interval
        // after it submitted (a doubled Return), so Cancel is pressed after it.
        try await Task.sleep(for: .seconds(NSEvent.doubleClickInterval + 0.5))
        XCTAssertEqual(store.snapshot.state, .accountConnecting, "The silent server must still be awaited")
        print("DULCET MAC ACCOUNT STATE reached=in-progress state=\(store.snapshot.state.rawValue)")
        try shipping.press(cancel, named: "Cancel")
        try await waitUntil(timeout: .seconds(10), "cancel: state=\(store.snapshot.state)") {
            store.snapshot.state == .accountConnectIdle
        }
        try await waitUntil(timeout: .seconds(5), "cancel: Connecting still shown") {
            shipping.firstElement(labelled: "Connecting to the server…") == nil
        }
        XCTAssertNil(shipping.firstElement(labelled: "Try Again"), "A cancelled connection is not a failure")
        let accepted = silent.drainAcceptedConnections()
        XCTAssertGreaterThan(accepted, 0, "The progress shown must have been a real connection to the silent port")
        print("DULCET MAC ACCOUNT STATE left=in-progress via=cancel silent-connections=\(accepted)")

        // 9. Persistence error on save: the server accepts, this host's Keychain refuses the save.
        enter(server.baseURL, password: server.password, into: store)
        try shipping.press(try await shipping.element(identifiedBy: "dulcet.account-connect.primary-action"), named: "Connect")
        try await waitUntil(timeout: .seconds(45), "save: state=\(store.snapshot.state)") {
            store.snapshot.state != .accountConnecting && store.snapshot.state != .accountConnectIdle
        }
        XCTAssertEqual(store.snapshot.state, .accountErrorPersistence,
                       "An unentitled host's Keychain refuses the save; this proof needs the unentitled build")
        _ = try await shipping.element(labelled: "The account could not be saved")
        XCTAssertNil(defaults.string(forKey: activeAccountKey), "A refused save leaves no active-account pointer")
        print("DULCET MAC ACCOUNT STATE reached=error-persistence-save state=\(store.snapshot.state.rawValue)")
        shipping.close()

        // 10. Persistence error on load: a launch whose pointer the Keychain cannot answer.
        defaults.set(UUID().uuidString, forKey: activeAccountKey)
        var keychainAnswer = "none"
        do { _ = try DulcetKeychainCredentialStore().load() } catch { keychainAnswer = String(describing: error) }
        XCTAssertNotEqual(keychainAnswer, "none", "The Keychain must refuse to return the pointed-to account")
        let relaunched = AccountStatesWindow(store: DulcetMacProduction.makePresentationStore())
        defer { relaunched.close() }
        XCTAssertEqual(relaunched.store.snapshot.state, .accountErrorPersistence)
        _ = try await relaunched.element(labelled: "Your saved account could not be opened")
        XCTAssertNil(relaunched.firstElement(labelled: "The server accepted the account, but the system Keychain did not save it."),
                     "A launch-time read must not be described as a save the server just accepted")
        print("DULCET MAC ACCOUNT STATE reached=error-persistence-load keychain=\(keychainAnswer)")
        relaunched.close()
        defaults.removeObject(forKey: activeAccountKey)

        // MARK: Part 2 -- production connector, reader and view over an in-memory credential store.
        let credentials = HostedCredentialStore()
        let databaseName = "dulcet-account-states-\(UUID().uuidString).db"
        let providerInstanceID = "macos-account-states-\(UUID().uuidString)"
        func makeStore() -> (DulcetPresentationStore, DulcetLibrarySession) {
            let session = DulcetLibrarySession(factory: DulcetCoreLibraryReaderFactory(databaseName: databaseName))
            return (DulcetPresentationStore(source: DulcetAccountDataSource(
                connector: DulcetCoreAccountConnector(),
                credentialStore: credentials,
                providerInstanceIDFactory: { providerInstanceID },
                librarySession: session
            )), session)
        }

        // 11. Connected: the connection lands on the library, and Connection says connected.
        let (first, firstSession) = makeStore()
        let connectedWindow = AccountStatesWindow(store: first)
        defer { connectedWindow.close() }
        XCTAssertEqual(first.snapshot.state, .accountConnectIdle)
        enter(server.baseURL, password: server.password, into: first)
        try connectedWindow.press(
            try await connectedWindow.element(identifiedBy: "dulcet.account-connect.primary-action"), named: "Connect"
        )
        try await waitUntil(timeout: .seconds(45), "connected: state=\(first.snapshot.state) mode=\(firstSession.mode)") {
            first.snapshot.accountConnected && first.selectedDestination == .library
        }
        first.selectDestination(.settings)
        XCTAssertEqual(first.snapshot.state, .accountConnected, "Connection opened from the library is connected")
        let connectedLine = try await connectedWindow.element(described: "Connected to") {
            (connectedWindow.label($0) ?? "").hasPrefix("Connected to")
        }
        _ = try await connectedWindow.element(labelled: "Sign Out")
        print("DULCET MAC ACCOUNT STATE reached=connected label=\(connectedWindow.label(connectedLine)?.debugDescription ?? "nil")")
        connectedWindow.close()
        await withCheckedContinuation { continuation in
            firstSession.close { continuation.resume() }
        }

        // 12. Saved and disconnected: what a relaunch builds over the saved account.
        XCTAssertNotNil(try credentials.load(), "The connected account must be saved")
        let (second, secondSession) = makeStore()
        let savedWindow = AccountStatesWindow(store: second)
        defer { savedWindow.close() }
        XCTAssertEqual(second.selectedDestination, .library, "A saved account opens into its library")
        _ = try await savedWindow.element(identifiedBy: "dulcet.reader.reconnect")
        second.selectDestination(.settings)
        XCTAssertEqual(second.snapshot.state, .accountSavedDisconnected)
        let savedLine = try await savedWindow.element(described: "Reconnect to") {
            (savedWindow.label($0) ?? "").hasPrefix("Reconnect to")
        }
        let reconnect = try await savedWindow.element(identifiedBy: "dulcet.account-connect.primary-action")
        XCTAssertEqual(savedWindow.label(reconnect), "Reconnect")
        XCTAssertNil(savedWindow.firstElement(labelled: "Sign Out"), "A saved account is not connected until Reconnect")
        XCTAssertFalse(second.snapshot.accountConnected)
        print("DULCET MAC ACCOUNT STATE reached=saved-disconnected label=\(savedWindow.label(savedLine)?.debugDescription ?? "nil")")
        savedWindow.close()
        await withCheckedContinuation { continuation in
            secondSession.close { continuation.resume() }
        }
        print("DULCET MAC ACCOUNT STATES PASS")
    }

    /// What the form's fields write: the same bindings the text fields and the toggle edit.
    private func enter(
        _ serverURL: String, password: String, allowLocalHTTP: Bool = true, into store: DulcetPresentationStore
    ) {
        store.accountServerURL = serverURL
        store.accountUsername = username
        store.accountPassword = password
        store.accountAllowLocalHTTP = allowLocalHTTP
    }

    private func faultProxy() throws -> AccountStatesFaultProxy {
        let url = ProcessInfo.processInfo.environment["DULCET_LYRICS_FAULT_PROXY_URL"] ?? ""
        guard let components = URLComponents(string: url), components.scheme == "http",
              components.host == "127.0.0.1" || components.host == "localhost" else {
            XCTFail("DULCET_LYRICS_FAULT_PROXY_URL must name the loopback fault proxy; got \(url.debugDescription)")
            throw HostedAppError.refused
        }
        return AccountStatesFaultProxy(url: url)
    }

    private func disposableServer() throws -> LiveServer {
        let environment = ProcessInfo.processInfo.environment
        let baseURL = try XCTUnwrap(environment["DULCET_CONFORMANCE_BASE_URL"], "The disposable server URL must be supplied")
        guard let components = URLComponents(string: baseURL), components.scheme == "http",
              components.host == "127.0.0.1" || components.host == "localhost",
              environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true" else {
            XCTFail("Refused: \(baseURL.debugDescription) is not a disposable loopback server")
            throw HostedAppError.refused
        }
        return LiveServer(baseURL: baseURL, username: username, password: password)
    }
}

/// The fault proxy's control for answering `ping` with an OpenSubsonic error, code 0.
private struct AccountStatesFaultProxy {
    let url: String

    func armServerError() async throws {
        let (_, response) = try await control("POST", "endpoint=ping&code=0&state=on")
        XCTAssertEqual(response, 200, "The fault proxy must accept the server-error rule")
    }

    func serverErrorsAnswered() async throws -> Int {
        let (data, _) = try await control("GET", "")
        let observed = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return observed?["answered"] as? Int ?? -1
    }

    /// Best effort and synchronous, so a failing proof still leaves the shared proxy disarmed.
    func disarmServerError() {
        var request = URLRequest(url: URL(string: url + "/__dulcet/subsonic-error?state=off")!)
        request.httpMethod = "POST"
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, _, _ in done.signal() }.resume()
        _ = done.wait(timeout: .now() + 5)
    }

    private func control(_ method: String, _ query: String) async throws -> (Data, Int) {
        var request = URLRequest(url: URL(string: url + "/__dulcet/subsonic-error" + (query.isEmpty ? "" : "?" + query))!)
        request.httpMethod = method
        let (data, response) = try await URLSession.shared.data(for: request)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }
}

/// The production root view in a window, read and pressed through its accessibility tree.
@MainActor
private final class AccountStatesWindow {
    let store: DulcetPresentationStore
    private let hostingView: NSView
    private let window: NSWindow

    init(store: DulcetPresentationStore) {
        self.store = store
        let hosting = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: store))
        hosting.frame = NSRect(x: 0, y: 0, width: 1180, height: 760)
        hostingView = hosting
        window = NSWindow(contentRect: hosting.frame, styleMask: [.titled, .closable, .resizable],
                          backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = hosting
        window.makeKeyAndOrderFront(nil)
    }

    func close() { window.orderOut(nil) }

    func element(identifiedBy identifier: String) async throws -> Any {
        try await element(described: identifier) { self.identifier($0) == identifier }
    }

    func element(labelled text: String) async throws -> Any {
        try await element(described: text) { self.label($0) == text }
    }

    func element(described description: String, timeout: Duration = .seconds(20), where matches: (Any) -> Bool) async throws -> Any {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        repeat {
            if let match = firstElement(where: matches) { return match }
            try await Task.sleep(for: .milliseconds(100))
        } while ContinuousClock.now < deadline
        let shown = descendants(of: hostingView).compactMap { label($0) }.filter { !$0.isEmpty }
        XCTFail("\(description) never appeared; labels shown: \(shown.joined(separator: " | "))")
        throw HostedAppError.unexpected(description)
    }

    func firstElement(labelled text: String) -> Any? {
        firstElement { self.label($0) == text }
    }

    func firstElement(where matches: (Any) -> Bool) -> Any? {
        hostingView.layoutSubtreeIfNeeded()
        return descendants(of: hostingView).first(where: matches)
    }

    func press(_ element: Any, named name: String) throws {
        let press = #selector(NSAccessibilityProtocol.accessibilityPerformPress)
        let object = try XCTUnwrap(element as? NSObject, "\(name) is not an accessibility object")
        XCTAssertTrue(object.responds(to: press), "\(name) does not answer AXPress")
        _ = object.perform(press)
    }

    func identifier(_ element: Any) -> String? {
        value("accessibilityIdentifier", of: element) as? String
    }

    func label(_ element: Any) -> String? {
        ["accessibilityLabel", "accessibilityTitle", "accessibilityValue"].lazy.compactMap { name -> String? in
            let found = self.value(name, of: element)
            return found as? String ?? (found as? NSAttributedString)?.string
        }.first
    }

    private func value(_ name: String, of element: Any) -> Any? {
        guard let object = element as? NSObject else { return nil }
        let selector = NSSelectorFromString(name)
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue()
    }

    private func descendants(of root: Any) -> [Any] {
        var result: [Any] = []
        var visited = Set<ObjectIdentifier>()
        var pending: [Any] = [root]
        while let current = pending.popLast() {
            guard let object = current as AnyObject? else { continue }
            guard visited.insert(ObjectIdentifier(object)).inserted else { continue }
            var children = value("accessibilityChildren", of: current) as? [Any] ?? []
            if let view = current as? NSView { children.append(contentsOf: view.subviews) }
            result.append(current)
            pending.append(contentsOf: children)
        }
        return result
    }
}

/// A loopback TCP port chosen by the system. Not listening, the port is given back at once, so a
/// connection to it is refused (a socket left bound but not listening is not refused here: OBSERVED,
/// the connection waited out the 30-second request limit instead). Listening, the port is held for
/// the length of the test, and a connection completes into the backlog and is never answered, so
/// the client waits until it gives up or is cancelled.
private final class MacLoopbackTestPort {
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
