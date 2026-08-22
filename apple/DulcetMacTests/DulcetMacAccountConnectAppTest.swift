import AppKit
import Security
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

@MainActor
final class DulcetMacAccountConnectAppTest: XCTestCase {
    private let activeAccountKey = "com.legitimateapps.dulcet.active-account-id"
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

    func connectReachesConnectedUIThroughLiveKotlinFacade() async throws {
        let baseURL = try XCTUnwrap(
            ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_BASE_URL"],
            "apple-ci must supply the live conformance fixture URL"
        )
        let request = DulcetAccountConnectRequest(
            serverURL: baseURL,
            username: fixtureUsername,
            password: fixturePassword,
            allowLocalHTTP: true
        )
        let credentialStore = DulcetKeychainCredentialStore()
        try? credentialStore.delete()
        defer { try? credentialStore.delete() }

        let store = DulcetMacProduction.makePresentationStore()
        store.accountServerURL = request.serverURL
        store.accountUsername = request.username
        store.accountPassword = request.password
        store.accountAllowLocalHTTP = request.allowLocalHTTP

        store.submitAccountConnection()
        XCTAssertEqual(store.snapshot.state, .accountConnecting)

        try await waitUntil(timeout: .seconds(20)) {
            store.snapshot.state != .accountConnecting
        }

        XCTAssertEqual(store.snapshot.state, .accountConnected)
        XCTAssertTrue(store.snapshot.accountConnected)
        guard case let .connected(account) = store.snapshot.accountConnection else {
            return XCTFail("the live connector did not publish a connected account")
        }
        XCTAssertEqual(account.normalizedServerURL, baseURL)
        guard case let .online(serverName) = store.snapshot.connectivity else {
            return XCTFail("the connected state did not publish online connectivity")
        }
        XCTAssertFalse(serverName.isEmpty)

        let persisted = try XCTUnwrap(try credentialStore.load())
        XCTAssertEqual(persisted, request)
        try assertProductionKeychainAttributes()

        let rendering = try renderProductionView(store: store)
        XCTAssertTrue(rendering.didRenderPixels)
        XCTAssertTrue(
            rendering.accessibilityStrings.contains {
                $0.localizedCaseInsensitiveContains("Connected to")
            },
            "the connected confirmation was not present in the rendered accessibility tree"
        )
        XCTAssertTrue(
            rendering.accessibilityStrings.contains {
                $0.localizedCaseInsensitiveContains("Online")
            },
            "online status was not present in the rendered accessibility tree"
        )
        XCTAssertFalse(
            rendering.accessibilityStrings.contains { $0.contains(fixturePassword) },
            "the rendered accessibility tree exposed the fixture password"
        )

        var snapshotDump = ""
        dump(store.snapshot, to: &snapshotDump)
        let diagnosticStrings = [
            String(describing: store.snapshot),
            String(reflecting: store.snapshot),
            snapshotDump,
        ]
        for value in [request.serverURL, request.username, request.password] {
            XCTAssertTrue(
                diagnosticStrings.allSatisfy { !$0.contains(value) },
                "a credential-bearing value escaped snapshot redaction"
            )
        }
    }

    private func waitUntil(
        timeout: Duration,
        condition: @MainActor () -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while !condition() {
            if clock.now >= deadline {
                XCTFail("the live account connection did not complete before the test deadline")
                return
            }
            try await Task.sleep(for: .milliseconds(50))
        }
    }

    private func assertProductionKeychainAttributes() throws {
        let accountID = try XCTUnwrap(UserDefaults.standard.string(forKey: activeAccountKey))
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: DulcetKeychainCredentialStore.productionService,
            kSecAttrAccount as String: accountID,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
            kSecReturnAttributes as String: kCFBooleanTrue as Any,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        XCTAssertEqual(status, errSecSuccess)
        let attributes = try XCTUnwrap(result as? [String: Any])
        XCTAssertEqual(
            attributes[kSecAttrAccessible as String] as? String,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String
        )
        XCTAssertEqual(attributes[kSecAttrSynchronizable as String] as? Bool, false)
    }

    private func renderProductionView(
        store: DulcetPresentationStore
    ) throws -> (didRenderPixels: Bool, accessibilityStrings: [String]) {
        let hostingView = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: store))
        hostingView.frame = NSRect(x: 0, y: 0, width: 1180, height: 760)
        let window = NSWindow(
            contentRect: hostingView.frame,
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.contentView = hostingView
        window.makeKeyAndOrderFront(nil)
        defer { window.close() }

        hostingView.layoutSubtreeIfNeeded()
        guard let bitmap = hostingView.bitmapImageRepForCachingDisplay(in: hostingView.bounds) else {
            throw RenderingFailure.couldNotAllocateBitmap
        }
        hostingView.cacheDisplay(in: hostingView.bounds, to: bitmap)
        let strings = accessibilityStrings(from: window)
        return (bitmap.pixelsWide > 0 && bitmap.pixelsHigh > 0, strings)
    }

    private func accessibilityStrings(from root: NSObject) -> [String] {
        let stringSelectors = [
            NSSelectorFromString("accessibilityLabel"),
            NSSelectorFromString("accessibilityTitle"),
            NSSelectorFromString("accessibilityValue"),
            NSSelectorFromString("accessibilityValueDescription"),
            NSSelectorFromString("accessibilityHelp"),
        ]
        let childrenSelector = NSSelectorFromString("accessibilityChildren")
        var visited: Set<ObjectIdentifier> = []
        var values: [String] = []

        func visit(_ object: NSObject) {
            guard visited.insert(ObjectIdentifier(object)).inserted else { return }
            for selector in stringSelectors where object.responds(to: selector) {
                if let value = object.perform(selector)?.takeUnretainedValue() as? String,
                   !value.isEmpty {
                    values.append(value)
                }
            }
            guard object.responds(to: childrenSelector),
                  let children = object.perform(childrenSelector)?.takeUnretainedValue()
                    as? [NSObject] else { return }
            children.forEach(visit)
        }

        visit(root)
        return values
    }
}

private enum RenderingFailure: Error {
    case couldNotAllocateBitmap
}
