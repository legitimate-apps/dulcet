import AppKit
import Security
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

@MainActor
final class DulcetSignedMacAccountConnectTests: XCTestCase {
    /// Live production composition -> Keychain item and marker -> visible connected root.
    /// A fresh isolated host namespace prevents a prior save from satisfying this proof.
    func testLiveConnectSavesDeviceOnlyCredentialAndRendersConnectedUI() async throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.signed-host")
        let environment = ProcessInfo.processInfo.environment
        let baseURL = try XCTUnwrap(environment["DULCET_CONFORMANCE_BASE_URL"])
        let url = try XCTUnwrap(URLComponents(string: baseURL))
        XCTAssertEqual(url.scheme, "http")
        XCTAssertEqual(url.host, "127.0.0.1")
        XCTAssertTrue((15781...15789).contains(url.port ?? 0))
        XCTAssertEqual(environment["DULCET_CONFORMANCE_DISPOSABLE"], "true")
        guard url.scheme == "http", url.host == "127.0.0.1",
              (15781...15789).contains(url.port ?? 0),
              environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true" else {
            throw ProofError.invalidFixture
        }
        let store = DulcetKeychainCredentialStore()
        // Refuse inherited state instead of deleting an unknown account.
        XCTAssertNil(store.activeAccountID)
        guard store.activeAccountID == nil else { throw ProofError.inheritedAccount }
        let controlService = "com.legitimateapps.dulcet.signed-host.control.\(UUID().uuidString)"
        let controlAccount = "attribute-readback-control"
        defer {
            try? store.delete()
            SecItemDelete(query(service: controlService, account: controlAccount) as CFDictionary)
        }
        var wrong = query(service: controlService, account: controlAccount)
        wrong[kSecAttrSynchronizable as String] = kCFBooleanFalse
        wrong[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        wrong[kSecValueData as String] = Data("wrong-accessibility-control".utf8)
        XCTAssertEqual(SecItemAdd(wrong as CFDictionary, nil), errSecSuccess)
        let control = try attributes(service: controlService, account: controlAccount)
        XCTAssertEqual(control[kSecAttrAccessible as String] as? String,
                       kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String)
        XCTAssertNotEqual(control[kSecAttrAccessible as String] as? String,
                          kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)

        let presentation = DulcetMacProduction.makePresentationStore()
        presentation.selectDestination(.settings)
        let enhancedUI = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
        let previous = NSApp.accessibilityAttributeValue(enhancedUI) ?? false
        NSApp.accessibilitySetValue(true, forAttribute: enhancedUI)
        defer { NSApp.accessibilitySetValue(previous, forAttribute: enhancedUI) }
        let host = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: presentation))
        host.frame = NSRect(x: 0, y: 0, width: 1180, height: 760)
        let window = NSWindow(contentRect: host.frame, styleMask: [.titled, .closable, .resizable],
                              backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = host
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }

        presentation.accountServerURL = baseURL
        presentation.accountUsername = "dulcet-admin"
        presentation.accountPassword = "dulcet-ci-canary-password"
        presentation.accountAllowLocalHTTP = true
        presentation.submitAccountConnection()
        XCTAssertEqual(presentation.snapshot.state, .accountConnecting)
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        while presentation.snapshot.state == .accountConnecting && ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(50))
        }
        // A successful production connection navigates to the library.
        // The account status remains connected when Settings is opened below.
        XCTAssertTrue(presentation.snapshot.accountConnected)
        guard case let .connected(account) = presentation.snapshot.accountConnection else {
            throw ProofError.notConnected
        }
        let accountID = try XCTUnwrap(store.activeAccountID, "Production save did not publish the active pointer")
        let request = try XCTUnwrap(store.load(), "Production credential could not be read back")
        XCTAssertEqual(request.serverURL, baseURL)
        XCTAssertEqual(request.username, "dulcet-admin")
        XCTAssertEqual(request.password, "dulcet-ci-canary-password")
        XCTAssertTrue(request.allowLocalHTTP)
        try assertStoredAttributes(accountID: accountID)

        // Observe the production view's accessible connected status and Sign Out action,
        // rather than using a snapshot as a proxy for rendering.
        presentation.selectDestination(.settings)
        let connectedLabel = DulcetStrings.connectedTo(account.serverName)
        let uiDeadline = ContinuousClock.now.advanced(by: .seconds(10))
        var labels: [String] = []
        repeat {
            host.layoutSubtreeIfNeeded()
            labels = descendants(host).compactMap { label($0) }
            if labels.contains(where: { $0.contains(connectedLabel) }) && labels.contains(DulcetStrings.signOut) { break }
            try await Task.sleep(for: .milliseconds(50))
        } while ContinuousClock.now < uiDeadline
        XCTAssertTrue(labels.contains(where: { $0.contains(connectedLabel) }), "Connected status was not rendered")
        XCTAssertTrue(labels.contains(DulcetStrings.signOut), "Connected Sign Out action was not rendered")
        try store.save(request, providerInstanceID: accountID)
        XCTAssertEqual(store.activeAccountID, accountID)
        XCTAssertEqual(try store.load(), request)
        try assertStoredAttributes(accountID: accountID)
        try store.delete()
        XCTAssertNil(store.activeAccountID)
        XCTAssertNil(try store.load())
        var deletedQuery = query(service: DulcetKeychainCredentialStore.productionService, account: accountID)
        deletedQuery[kSecReturnAttributes as String] = kCFBooleanTrue
        XCTAssertEqual(SecItemCopyMatching(deletedQuery as CFDictionary, nil), errSecItemNotFound)
    }

    private func query(service: String, account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: account, kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
         kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
    }
    private func attributes(service: String, account: String) throws -> [String: Any] {
        var q = query(service: service, account: account)
        q[kSecReturnAttributes as String] = kCFBooleanTrue
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        XCTAssertNil(q[kSecAttrAccessible as String])
        XCTAssertEqual(q[kSecAttrSynchronizable as String] as? String, kSecAttrSynchronizableAny as String)
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(q as CFDictionary, &result), errSecSuccess)
        return try XCTUnwrap(result as? [String: Any])
    }
    private func assertStoredAttributes(accountID: String) throws {
        let saved = try attributes(service: DulcetKeychainCredentialStore.productionService, account: accountID)
        XCTAssertEqual(saved[kSecAttrAccount as String] as? String, accountID)
        XCTAssertEqual(saved[kSecAttrAccessible as String] as? String,
                       kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        XCTAssertEqual((saved[kSecAttrSynchronizable as String] as? NSNumber)?.boolValue, false)
    }
    private func descendants(_ root: Any) -> [Any] {
        var result: [Any] = []
        var visited = Set<ObjectIdentifier>()
        var pending: [Any] = [root]
        while let current = pending.popLast() {
            let object = current as AnyObject
            guard visited.insert(ObjectIdentifier(object)).inserted else { continue }
            result.append(current)
            var children = objectValue("accessibilityChildren", of: current) as? [Any] ?? []
            if let view = current as? NSView { children += view.subviews }
            pending += children
        }
        return result
    }
    // SwiftUI exposes public Objective-C AX getters without the complete protocol.
    private func objectValue(_ name: String, of element: Any) -> Any? {
        guard let object = element as? NSObject else { return nil }
        let selector = NSSelectorFromString(name)
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue()
    }
    private func label(_ element: Any) -> String? {
        objectValue("accessibilityLabel", of: element) as? String
            ?? objectValue("accessibilityTitle", of: element) as? String
            ?? objectValue("accessibilityValue", of: element) as? String
    }
    private enum ProofError: Error { case invalidFixture, inheritedAccount, notConnected }
}
