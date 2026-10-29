import XCTest

/// Probe a transient transport/bar copy without failing the test when XCTest has no tap point.
/// A snapshot can succeed just before a separate hittability resolution loses its activation
/// point. Geometry is only a prefilter: XCTest still decides whether the control can be tapped,
/// including occlusion. Only its specific no-hit-point diagnostic is treated as `false`.
@MainActor
func playerElementIsHittable(_ element: XCUIElement) -> Bool {
    guard let snapshot = try? element.snapshot(), snapshot.isEnabled else { return false }
    let frame = snapshot.frame
    guard frame.origin.x.isFinite, frame.origin.y.isFinite,
          frame.width.isFinite, frame.height.isFinite, frame.width > 0, frame.height > 0 else { return false }

    var hittable = false
    var missingHitPoint = false
    XCTExpectFailure(
        "A transient player copy without an activation point is not hittable",
        strict: false,
        failingBlock: { hittable = element.isHittable },
        issueMatcher: { issue in
            let message = issue.compactDescription
            guard message.hasPrefix("Failed to determine hittability of "),
                  message.hasSuffix(": Activation point invalid and no suggested hit points based on element frame") else {
                return false
            }
            missingHitPoint = true
            return true
        }
    )
    return hittable && !missingHitPoint
}
