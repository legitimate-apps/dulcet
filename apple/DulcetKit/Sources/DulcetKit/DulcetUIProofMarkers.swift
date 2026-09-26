import SwiftUI

/// Evidence a UI proof reads to learn that a HANDLER ran, not merely that the screen ended up
/// looking right. A title that changed after a swipe could have changed for another reason; the
/// marker is written by the swipe handler itself. Compiled into debug builds only, and recording
/// only when the app was launched with ``launchArgument``.
@MainActor
enum DulcetUIProofMarkers {
    static let launchArgument = "-dulcet-debug-ui-markers"
    static let accessibilityIdentifier = "dulcet.debug.ui-markers"

#if DEBUG
    static let isEnabled = ProcessInfo.processInfo.arguments.contains(launchArgument)
    static let log = DulcetUIProofMarkerLog()
#endif

    /// Records that a handler ran, when proofs are enabled; otherwise nothing.
    static func record(_ marker: @autoclosure () -> String) {
#if DEBUG
        guard isEnabled else { return }
        log.entries.append(marker())
#endif
    }
}

#if DEBUG
@MainActor
@Observable
final class DulcetUIProofMarkerLog {
    fileprivate(set) var entries: [String] = []

    var text: String { (["dulcet-ui"] + entries).joined(separator: " ") }
}
#endif

extension View {
    /// Shows the proof markers, read-only, where a UI proof can read them. Nothing in a release
    /// build or without the launch argument.
    func dulcetUIProofMarkers() -> some View {
#if DEBUG
        overlay(alignment: .bottomLeading) {
            if DulcetUIProofMarkers.isEnabled {
                Text(DulcetUIProofMarkers.log.text)
                    .font(.system(size: 6).monospaced())
                    .lineLimit(1)
                    .opacity(0.02)
                    // Read-only evidence: it must never take a tap aimed at what is under it.
                    .allowsHitTesting(false)
                    .accessibilityIdentifier(DulcetUIProofMarkers.accessibilityIdentifier)
            }
        }
#else
        self
#endif
    }
}
