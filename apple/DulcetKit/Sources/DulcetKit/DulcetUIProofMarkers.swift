import SwiftUI
import OSLog
#if DEBUG && os(iOS)
import UIKit
import ObjectiveC.runtime
#endif

/// Evidence a UI proof reads to learn that a HANDLER ran, not merely that the screen ended up
/// looking right. A title that changed after a swipe could have changed for another reason; the
/// marker is written by the action handler itself. Compiled into debug builds only, and recording
/// only when the app was launched with ``launchArgument``.
@MainActor
enum DulcetUIProofMarkers {
    static let launchArgument = "-dulcet-debug-ui-markers"
    static let navigationLaunchArgument = "-dulcet-debug-navigation-receipts"
    static let accessibilityIdentifier = "dulcet.debug.ui-markers"

#if DEBUG
    static var isEnabled = ProcessInfo.processInfo.arguments.contains(launchArgument)
    static let log = DulcetUIProofMarkerLog()
    static let inputReceiptsEnabled = ProcessInfo.processInfo.arguments.contains("-dulcet-debug-input-receipts")
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

#if DEBUG && os(iOS)
    private static var inputEntries: [String] = []
    static var inputText: String { (["dulcet-input"] + inputEntries).joined(separator: " ") }

    static func input(_ phase: String, touch: Int, point: CGPoint) {
        guard isEnabled, inputReceiptsEnabled else { return }
        navigationSequence += 1
        let receipt = "INPUT|\(navigationSequence)|touch=\(touch)|phase=\(phase)|x=\(String(format: "%.1f", point.x))|y=\(String(format: "%.1f", point.y))"
        // Do not invalidate SwiftUI during an active touch. AX reads this ledger on demand.
        inputEntries.append(receipt)
        if ProcessInfo.processInfo.arguments.contains("-dulcet-control-publish-input") { record(receipt) }
        logger.notice("\(receipt, privacy: .public)")
    }
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
#if os(iOS)
        .overlay(alignment: .bottomTrailing) {
            if DulcetUIProofMarkers.isEnabled && DulcetUIProofMarkers.inputReceiptsEnabled {
                DulcetUIProofInputMonitor().frame(width: 1, height: 1).allowsHitTesting(false)
            }
        }
#endif
#else
        self
#endif
    }
}

#if DEBUG && os(iOS)
/// Observe dispatch without joining UIKit's gesture arena. Even a recognizer that cannot
/// prevent another recognizer still participates in control/SwiftUI gesture dependencies.
/// This DEBUG-only hook calls the original implementation exactly once, without changing
/// the event, its delivery, or any recognizer state.
@MainActor private enum DulcetUIProofInputWindows {
    private static var installed = false
    private static var nextTouch = 0
    private static var touches: [ObjectIdentifier: Int] = [:]
    static func start(in window: UIWindow) {
        guard !installed else { return }
        guard let original = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.sendEvent(_:))),
              let observed = class_getInstanceMethod(UIWindow.self, #selector(UIWindow.dulcetProofSendEvent(_:))) else {
            return // The proof's input-monitor-ready assertion fails if installation is unavailable.
        }
        method_exchangeImplementations(original, observed)
        installed = true
        DulcetUIProofMarkers.record("input-monitor-ready")
    }
    static func receive(_ event: UIEvent, in window: UIWindow) {
        guard event.type == .touches else { return }
        for touch in event.allTouches ?? [] where touch.window === window {
            let key = ObjectIdentifier(touch)
            switch touch.phase {
            case .began:
                nextTouch += 1
                touches[key] = nextTouch
                DulcetUIProofMarkers.input("began", touch: nextTouch, point: touch.location(in: window))
            case .ended, .cancelled:
                let id = touches.removeValue(forKey: key) ?? -1
                DulcetUIProofMarkers.input(touch.phase == .ended ? "ended" : "cancelled", touch: id, point: touch.location(in: window))
            default: break
            }
        }
    }
}

extension UIWindow {
    @objc fileprivate func dulcetProofSendEvent(_ event: UIEvent) {
        // After exchange this invokes UIKit's original sendEvent implementation.
        dulcetProofSendEvent(event)
        DulcetUIProofInputWindows.receive(event, in: self)
    }
}
private struct DulcetUIProofInputMonitor: UIViewRepresentable {
    final class MonitorView: UIView {
        // A computed AX label witnesses input without publishing touch-down into SwiftUI.
        override var accessibilityLabel: String? {
            get { DulcetUIProofMarkers.inputText }
            set { }
        }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { DulcetUIProofInputWindows.start(in: window) }
        }
    }
    func makeUIView(context: Context) -> MonitorView {
        let view = MonitorView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = true
        view.accessibilityTraits = .staticText
        view.accessibilityIdentifier = "dulcet.debug.input-markers"
        return view
    }
    func updateUIView(_ uiView: MonitorView, context: Context) {
        if let window = uiView.window { DulcetUIProofInputWindows.start(in: window) }
    }
}
#endif
