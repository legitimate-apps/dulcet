#if os(iOS)
import UIKit

/// Opt-in simulator evidence only. It samples main-thread display-link delivery, not rendering
/// or GPU work, and never changes the preferred frame rate or installs a gesture recognizer.
@MainActor
enum DulcetPlayerFrameProbe {
    #if DEBUG
    private static var probe: Probe?
    static func startIfRequested() {
        guard probe == nil, ProcessInfo.processInfo.arguments.contains("-dulcet-debug-player-frame-metrics") else { return }
        probe = Probe()
        DulcetUIProofMarkers.record("reduce-motion:\(UIAccessibility.isReduceMotionEnabled)")
    }

    static func event(_ text: String) { probe?.event(text) }

    private final class Probe: NSObject {
        private var link: CADisplayLink?
        private var pending = ""
        private var lastFlush = 0.0
        private let writer = DispatchQueue(label: "dulcet.player-frame-evidence")
        private let file: URL
        override init() {
            file = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("dulcet-player-frame-metrics-\(UUID().uuidString).jsonl")
            super.init()
            let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
            link.add(to: .main, forMode: .common)
            self.link = link
            event("probe reduceMotion=\(UIAccessibility.isReduceMotionEnabled)")
        }
        func event(_ text: String) {
            guard let data = try? JSONSerialization.data(withJSONObject: ["event": text, "time": ProcessInfo.processInfo.systemUptime]),
                  let line = String(data: data, encoding: .utf8) else { return }
            pending += line + "\n"
        }
        @objc private func tick(_ link: CADisplayLink) {
            let now = ProcessInfo.processInfo.systemUptime
            let budget = max(link.duration, link.targetTimestamp - link.timestamp)
            pending += "{\"time\":\(now),\"budget\":\(budget)}\n"
            guard now - lastFlush >= 1 else { return }
            lastFlush = now
            let data = Data(pending.utf8)
            let file = file
            pending = ""
            writer.async {
                if !FileManager.default.fileExists(atPath: file.path) {
                    FileManager.default.createFile(atPath: file.path, contents: nil)
                }
                guard let handle = try? FileHandle(forWritingTo: file) else { return }
                defer { try? handle.close() }
                do { try handle.seekToEnd(); try handle.write(contentsOf: data) } catch { }
            }
        }
    }
    #else
    static func startIfRequested() { }
    static func event(_ text: String) { }
    #endif
}
#endif
