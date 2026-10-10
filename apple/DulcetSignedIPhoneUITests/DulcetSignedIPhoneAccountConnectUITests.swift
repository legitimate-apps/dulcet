import XCTest

/// Same production/persistence flow on both lanes; iPad also verifies connected rotation.
/// Synthetic XCTest typing with a visible keyboard does not prove a hardware keyboard.
@MainActor
final class DulcetSignedIPhoneAccountConnectUITests: XCTestCase {
    func testSignedIPhoneConnectKeychainRelaunchAndTouchReconnect() throws {
        continueAfterFailure = false
        try SignedAccountConnectProof.run(lane: "iphone", rotating: false)
    }
}
