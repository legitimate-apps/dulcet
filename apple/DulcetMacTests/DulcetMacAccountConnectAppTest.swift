import XCTest
@testable import DulcetKit
@testable import DulcetMac

@MainActor
final class DulcetMacAccountConnectAppTest: XCTestCase {
    private let activeAccountKey = "com.legitimateapps.dulcet.active-account-id"
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

    func connectSuccessCrossesLiveKotlinFacadeIntoPersistenceFailureState() async throws {
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
}
