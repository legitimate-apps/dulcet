import Foundation
import XCTest

/// A transient player probe may encounter XCTest's no-activation-point issue instead of `false`.
/// Consume only that diagnostic during the probe, on its calling thread. Recording it as an
/// expected failure would change the entire test's result from Passed to Expected Failure.
class PlayerHittabilityTestCase: XCTestCase {
    private final class Probe {
        let thread = Thread.current
        var missingHitPoint = false
    }

    private let probeLock = NSLock()
    private var activeProbe: Probe?

    override func record(_ issue: XCTIssue) {
        let handled = probeLock.withLock {
            guard let probe = activeProbe, probe.thread == Thread.current,
                  issue.compactDescription.hasPrefix("Failed to determine hittability of "),
                  issue.compactDescription.hasSuffix(": Activation point invalid and no suggested hit points based on element frame") else {
                return false
            }
            probe.missingHitPoint = true
            return true
        }
        if handled { print("DULCET PLAYER PROBE no-hit-point copy-skipped") }
        else { super.record(issue) }
    }

    /// Keep the scope synchronous and restore it even when a probe nests inside another probe.
    @MainActor
    func withPlayerHittabilityProbe(_ read: () -> Bool) -> Bool {
        let probe = Probe()
        let previous = probeLock.withLock {
            let previous = activeProbe
            activeProbe = probe
            return previous
        }
        defer { probeLock.withLock { activeProbe = previous } }
        let hittable = read()
        return probeLock.withLock { hittable && !probe.missingHitPoint }
    }

    /// Geometry is only a prefilter. XCTest still decides whether the control can be tapped,
    /// including occlusion; a successful snapshot cannot guarantee the later read has a hit point.
    @MainActor
    func playerElementIsHittable(_ element: XCUIElement) -> Bool {
        guard let snapshot = try? element.snapshot(), snapshot.isEnabled else { return false }
        let frame = snapshot.frame
        guard !frame.isInfinite, !frame.isNull, frame.origin.x.isFinite, frame.origin.y.isFinite,
              frame.width.isFinite, frame.height.isFinite, frame.width > 0, frame.height > 0 else { return false }
        return withPlayerHittabilityProbe { element.isHittable }
    }
}

final class PlayerHittabilityTests: PlayerHittabilityTestCase {
    @MainActor
    func testMissingHitPointMakesTheProbeFalseAndLeavesTheNextProbeUsable() {
        continueAfterFailure = false
        let hittable = withPlayerHittabilityProbe {
            record(XCTIssue(type: .system, compactDescription:
                "Failed to determine hittability of \"pause.fill\" Button: Activation point invalid and no suggested hit points based on element frame"))
            return true
        }
        XCTAssertFalse(hittable)
        XCTAssertTrue(withPlayerHittabilityProbe { true })
        XCTAssertFalse(withPlayerHittabilityProbe { false })
    }

    @MainActor
    func testNestedProbeRestoresTheOuterProbe() {
        XCTAssertFalse(withPlayerHittabilityProbe {
            XCTAssertTrue(withPlayerHittabilityProbe { true })
            record(XCTIssue(type: .system, compactDescription:
                "Failed to determine hittability of \"play.fill\" Button: Activation point invalid and no suggested hit points based on element frame"))
            return true
        })
    }
}
