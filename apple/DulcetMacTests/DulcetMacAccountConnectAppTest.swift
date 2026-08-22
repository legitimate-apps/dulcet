import AppKit
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

@MainActor
final class DulcetMacAccountConnectAppTest: XCTestCase {
    private let activeAccountKey = "com.legitimateapps.dulcet.active-account-id"
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

    func connectSuccessCrossesLiveKotlinFacadeIntoPersistenceFailureUI() async throws {
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
        UserDefaults.standard.removeObject(forKey: activeAccountKey)
        defer { UserDefaults.standard.removeObject(forKey: activeAccountKey) }
        let credentialStore = DulcetKeychainCredentialStore()

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

        XCTAssertEqual(store.snapshot.state, .accountErrorPersistence)
        XCTAssertFalse(store.snapshot.accountConnected)
        guard case let .failed(failure) = store.snapshot.accountConnection else {
            return XCTFail("the live success did not reach the production persistence boundary")
        }
        XCTAssertEqual(failure.kind, .credentialPersistenceFailed)
        guard case let .connectionFailed(.account(connectivityFailure)) =
            store.snapshot.connectivity else {
            return XCTFail("the persistence failure did not reach production connectivity state")
        }
        XCTAssertEqual(connectivityFailure.kind, .credentialPersistenceFailed)
        XCTAssertNil(UserDefaults.standard.string(forKey: activeAccountKey))
        XCTAssertNil(try credentialStore.load())

        let rendering = try renderProductionView(store: store)
        XCTAssertTrue(rendering.didRenderPixels)
        XCTAssertTrue(
            rendering.accessibilityStrings.contains {
                $0.localizedCaseInsensitiveContains("account could not be saved")
            },
            "the typed persistence failure was not present in the rendered accessibility tree"
        )
        XCTAssertTrue(
            rendering.accessibilityStrings.contains {
                $0.localizedCaseInsensitiveContains("Keychain")
            },
            "the rendered persistence failure did not name the Keychain remedy"
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
