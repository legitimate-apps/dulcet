import Testing
@testable import DulcetKit

@Suite @MainActor struct DulcetUIProofMarkerTests {
    @Test func ordinaryHandlerMarkersExcludeNavigationReceipts() {
#if DEBUG
        let wasEnabled = DulcetUIProofMarkers.isEnabled
        DulcetUIProofMarkers.isEnabled = true
        defer { DulcetUIProofMarkers.isEnabled = wasEnabled }
        let before = DulcetUIProofMarkers.log.entries.count
        DulcetUIProofMarkers.record("skip-notice:1")
        DulcetUIProofMarkers.navigation(writer: "receive", oldDestination: "library", newDestination: "library",
                                        oldPath: "[]", newPath: "[]", detail: "unchanged")
        DulcetUIProofMarkers.record("mini-player-open")
        #expect(Array(DulcetUIProofMarkers.log.entries.dropFirst(before)) == ["skip-notice:1", "mini-player-open"],
                "General handler proofs must not opt into navigation diagnostics")
#endif
    }
}
