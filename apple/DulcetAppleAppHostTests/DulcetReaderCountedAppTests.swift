import UIKit
import XCTest

/// CONF-76, CONF-77 and CONF-86 through the iOS app host on an iPhone and on an iPad, and through
/// the Apple TV app host on a tvOS simulator, counted at the fault proxy
/// (DulcetReaderCountedProofs.swift holds the proofs, shared with macOS). Each method requires its
/// device class, so an iPhone run cannot stand as iPad evidence, nor either as Apple TV evidence.
@MainActor
final class DulcetReaderCountedAppTests: XCTestCase {
#if os(iOS)
    func testConf76ACachedOpenPublishesBeforeAnyAnswerOnIPhone() async throws {
        try require(.phone)
        try await DulcetReaderCountedProofs(self, platform: "ios").conf76()
    }

    func testConf77AReconnectFlushesReadsTheEpochRevalidatesTheVisibleScreenAndNothingElseOnIPhone() async throws {
        try require(.phone)
        try await DulcetReaderCountedProofs(self, platform: "ios").conf77()
    }

    func testConf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLiveOnIPhone() async throws {
        try require(.phone)
        try await DulcetReaderCountedProofs(self, platform: "ios").conf86()
    }

    func testConf76ACachedOpenPublishesBeforeAnyAnswerOnIPadOS() async throws {
        try require(.pad)
        try await DulcetReaderCountedProofs(self, platform: "ipados").conf76()
    }

    func testConf77AReconnectFlushesReadsTheEpochRevalidatesTheVisibleScreenAndNothingElseOnIPadOS() async throws {
        try require(.pad)
        try await DulcetReaderCountedProofs(self, platform: "ipados").conf77()
    }

    func testConf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLiveOnIPadOS() async throws {
        try require(.pad)
        try await DulcetReaderCountedProofs(self, platform: "ipados").conf86()
    }
#elseif os(tvOS)
    func testConf76ACachedOpenPublishesBeforeAnyAnswerOnAppleTV() async throws {
        try require(.tv)
        try await DulcetReaderCountedProofs(self, platform: "tvos").conf76()
    }

    func testConf77AReconnectFlushesReadsTheEpochRevalidatesTheVisibleScreenAndNothingElseOnAppleTV() async throws {
        try require(.tv)
        try await DulcetReaderCountedProofs(self, platform: "tvos").conf77()
    }

    func testConf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLiveOnAppleTV() async throws {
        try require(.tv)
        try await DulcetReaderCountedProofs(self, platform: "tvos").conf86()
    }
#endif

#if os(iOS) || os(tvOS)
    private func require(_ idiom: UIUserInterfaceIdiom) throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.dev")
        guard UIDevice.current.userInterfaceIdiom == idiom else {
            XCTFail("This proof needs \(idiom == .tv ? "an Apple TV" : idiom == .pad ? "an iPad" : "an iPhone"), got idiom \(UIDevice.current.userInterfaceIdiom.rawValue)")
            throw CountedProofError.invalidFixture
        }
    }
#endif
}
