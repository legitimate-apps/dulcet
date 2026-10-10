import XCTest

/// Same production/persistence flow on both lanes; iPad also verifies connected rotation.
/// Synthetic XCTest typing with a visible keyboard does not prove a hardware keyboard.
@MainActor
final class DulcetSignedIPadAccountConnectUITests: XCTestCase {
    func testSignedIPadConnectKeychainRelaunchAndTouchReconnect() throws {
        continueAfterFailure = false
        try SignedAccountConnectProof.run(lane: "ipad", rotating: true)
    }
}
