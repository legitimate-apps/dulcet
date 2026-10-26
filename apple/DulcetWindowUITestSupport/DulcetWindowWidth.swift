import Foundation
import XCTest

struct WindowWidthSettlementTimeout: Error, CustomStringConvertible {
    let previousWidth: CGFloat?
    let latestWidth: CGFloat?
    let elapsed: TimeInterval

    var description: String {
        "The app window width must settle; previous-width=\(String(describing: previousWidth))"
            + " latest-width=\(String(describing: latestWidth)) elapsed=\(elapsed) seconds"
    }
}

func waitForSettledWindowWidth(
    timeout: TimeInterval,
    readFrame: () -> CGRect?,
    now: () -> TimeInterval,
    pause: (TimeInterval) -> Void
) throws -> CGFloat {
    let started = now()
    var previousFrame: CGRect?
    var latestFrame: CGRect?
    func usable(_ frame: CGRect) -> Bool {
        frame.origin.x.isFinite && frame.origin.y.isFinite
            && frame.width.isFinite && frame.height.isFinite
            && frame.width > 0 && frame.height > 0
    }
    while now() - started < timeout {
        previousFrame = latestFrame
        latestFrame = readFrame()
        let elapsed = now() - started
        guard elapsed < timeout else { break }
        // Treat width as settled when two finite, nonempty snapshots at least 250 ms apart report the same width.
        if let previousFrame, let latestFrame, usable(previousFrame), usable(latestFrame),
           previousFrame.width == latestFrame.width {
            return latestFrame.width
        }
        pause(min(0.25, timeout - elapsed))
    }
    throw WindowWidthSettlementTimeout(
        previousWidth: previousFrame?.width, latestWidth: latestFrame?.width,
        elapsed: now() - started
    )
}

@MainActor
func settledWindowWidth(
    _ window: XCUIElement, timeout: TimeInterval = 60,
    file: StaticString = #filePath, line: UInt = #line
) -> CGFloat? {
    do {
        return try waitForSettledWindowWidth(
            timeout: timeout,
            readFrame: { (try? window.snapshot())?.frame },
            now: { ProcessInfo.processInfo.systemUptime },
            pause: { Thread.sleep(forTimeInterval: $0) }
        )
    } catch {
        XCTFail(String(describing: error), file: file, line: line)
        return nil
    }
}

