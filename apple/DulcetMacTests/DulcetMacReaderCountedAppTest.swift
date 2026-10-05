import AppKit
import XCTest

/// CONF-76, CONF-77 and CONF-86 through the macOS app host, counted at the fault proxy
/// (DulcetAppleAppHostTests/DulcetReaderCountedProofs.swift holds the proofs, shared with iOS and
/// iPadOS). Named, not `test`-prefixed: each runs only when selected, beside its fixtures.
@MainActor
final class DulcetMacReaderCountedAppTest: XCTestCase {
    func conf76ACachedOpenPublishesBeforeAnyAnswerThroughTheHostedMacApp() async throws {
        try await DulcetReaderCountedProofs(self, platform: "macos").conf76()
    }

    func conf77AReconnectFlushesReadsTheEpochRevalidatesTheVisibleScreenAndNothingElseThroughTheHostedMacApp() async throws {
        try await DulcetReaderCountedProofs(self, platform: "macos").conf77()
    }

    func conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLiveThroughTheHostedMacApp() async throws {
        try await DulcetReaderCountedProofs(self, platform: "macos").conf86()
    }
}
