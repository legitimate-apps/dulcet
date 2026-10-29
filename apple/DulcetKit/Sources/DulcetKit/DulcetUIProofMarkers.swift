import SwiftUI
import OSLog
#if DEBUG && os(iOS)
import UIKit
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
    static func input(_ phase: String, touch: Int, point: CGPoint) {
        guard isEnabled, inputReceiptsEnabled else { return }
        navigationSequence += 1
        let receipt = "INPUT|\(navigationSequence)|touch=\(touch)|phase=\(phase)|x=\(String(format: "%.1f", point.x))|y=\(String(format: "%.1f", point.y))"
        record(receipt)
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
#if os(iOS)
                    .background {
                        if DulcetUIProofMarkers.inputReceiptsEnabled {
                            DulcetUIProofInputMonitor().frame(width: 0, height: 0)
                        }
                    }
#endif
            }
        }
#else
        self
#endif
    }
}

#if DEBUG && os(iOS)
/// A fixture-only listener on each app window. It never recognizes or prevents a gesture,
/// and never delays or cancels delivery to views. App-swallowed touches therefore still end
/// here; a cancelled HID contact does not become a normal touch-up in this ledger.
private final class DulcetUIProofInputObserver: UIGestureRecognizer {
    private var nextTouch = 0
    private var touches: [ObjectIdentifier: Int] = [:]
    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func shouldRequireFailure(of otherGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func shouldBeRequiredToFail(by otherGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        for touch in touches {
            nextTouch += 1
            self.touches[ObjectIdentifier(touch)] = nextTouch
            DulcetUIProofMarkers.input("began", touch: nextTouch, point: touch.location(in: view))
        }
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {}
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) { finish(touches, phase: "ended") }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) { finish(touches, phase: "cancelled") }
    private func finish(_ touches: Set<UITouch>, phase: String) {
        for touch in touches {
            let id = self.touches.removeValue(forKey: ObjectIdentifier(touch)) ?? -1
            DulcetUIProofMarkers.input(phase, touch: id, point: touch.location(in: view))
        }
        if self.touches.isEmpty { state = .failed }
    }
    override func reset() { super.reset(); touches.removeAll() }
}

@MainActor private enum DulcetUIProofInputWindows {
    private static var visibilityObserver: NSObjectProtocol?
    static func start(in window: UIWindow) {
        install(in: window)
        installVisibleWindows()
        guard visibilityObserver == nil else { return }
        visibilityObserver = NotificationCenter.default.addObserver(forName: UIWindow.didBecomeVisibleNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { installVisibleWindows() }
        }
    }
    private static func installVisibleWindows() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows { install(in: window) }
        }
    }
    private static func install(in window: UIWindow) {
        guard window.gestureRecognizers?.contains(where: { $0 is DulcetUIProofInputObserver }) != true else { return }
        let observer = DulcetUIProofInputObserver(target: nil, action: nil)
        observer.cancelsTouchesInView = false
        observer.delaysTouchesBegan = false
        observer.delaysTouchesEnded = false
        window.addGestureRecognizer(observer)
        DulcetUIProofMarkers.record("input-monitor-ready")
    }
}
private struct DulcetUIProofInputMonitor: UIViewRepresentable {
    final class MonitorView: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { DulcetUIProofInputWindows.start(in: window) }
        }
    }
    func makeUIView(context: Context) -> MonitorView {
        let view = MonitorView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        return view
    }
    func updateUIView(_ uiView: MonitorView, context: Context) {
        if let window = uiView.window { DulcetUIProofInputWindows.start(in: window) }
    }
}
#endif
