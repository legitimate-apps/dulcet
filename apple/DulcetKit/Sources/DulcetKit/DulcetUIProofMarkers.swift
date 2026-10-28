import SwiftUI
import OSLog

/// Evidence a UI proof reads to learn that a HANDLER ran, not merely that the screen ended up
/// looking right. A title that changed after a swipe could have changed for another reason; the
/// marker is written by the swipe handler itself. Compiled into debug builds only, and recording
/// only when the app was launched with ``launchArgument``.
@MainActor
enum DulcetUIProofMarkers {
    static let launchArgument = "-dulcet-debug-ui-markers"
    static let navigationLaunchArgument = "-dulcet-debug-navigation-receipts"
    static let accessibilityIdentifier = "dulcet.debug.ui-markers"

#if DEBUG
    static var isEnabled = ProcessInfo.processInfo.arguments.contains(launchArgument)
    static let log = DulcetUIProofMarkerLog()
    private static let navigationReceiptsEnabled = ProcessInfo.processInfo.arguments.contains(navigationLaunchArgument)
    private static var navigationSequence = 0
    private static let logger = Logger(subsystem: "com.dulcet.navigation", category: "proof")
#endif

    static func navigation(writer: String, oldDestination: String, newDestination: String,
                           oldPath: String, newPath: String, detail: String) {
#if DEBUG
        // Navigation diagnostics are separate from exact handler-marker proofs.
        guard isEnabled, navigationReceiptsEnabled else { return }
        navigationSequence += 1
        let receipt = "NAV|\(navigationSequence)|\(writer)|dest=\(oldDestination)->\(newDestination)|path=\(oldPath)->\(newPath)|\(detail)"
        record(receipt)
        print(receipt)
        logger.notice("\(receipt, privacy: .public)")
#endif
    }

    static func detail(_ writer: String, context: @autoclosure () -> String) {
#if DEBUG
        guard isEnabled, navigationReceiptsEnabled else { return }
        navigationSequence += 1
        let receipt = "DETAIL|\(navigationSequence)|\(writer)|\(context())"
        record(receipt)
        print(receipt)
        logger.notice("\(receipt, privacy: .public)")
#endif
    }

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
        modifier(DulcetAccessibilityCaptureEvidence()).overlay(alignment: .bottomLeading) {
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

#if DEBUG
private struct DulcetAccessibilityCaptureEvidence: ViewModifier {
    @Environment(\.colorSchemeContrast) private var contrast
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    func body(content: Content) -> some View {
        content
            .onAppear(perform: record)
            .onChange(of: contrast) { _, _ in record() }
            .onChange(of: reduceTransparency) { _, _ in record() }
    }
    private func record() {
        DulcetUIProofMarkers.record("accessibility:contrast=\(contrast == .increased ? "increased" : "standard"):reduceTransparency=\(reduceTransparency)")
    }
}
#endif
