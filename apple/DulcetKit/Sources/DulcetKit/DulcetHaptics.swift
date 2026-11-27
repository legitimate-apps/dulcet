import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The few taps a listener should feel, and no others: the system player answers play/pause, a
/// skip, a swipe that lands, a reordered or removed queue row and a heart with a light touch,
/// and leaves every other control silent. A haptic on every button would make none of them mean
/// anything.
///
/// Fired by the handler that performs the action, not by a value changing, so a track that
/// arrives already favourited, or a queue the server reorders, is not felt as the person's own
/// gesture. iPhone only: iPad, Mac and Apple TV have no Taptic Engine to drive.
enum DulcetHaptic: String {
    /// Play or pause.
    case playPause
    /// Next or previous from a button.
    case skip
    /// A swipe on the mini player or the cover that skipped.
    case swipeSkip
    /// A song queued from a row swipe: Play Next or Add to Queue.
    case queue
    /// A queue row picked up by its handle.
    case reorderLift
    /// A queue row dropped in a new place.
    case reorder
    /// A queue row removed.
    case remove
    /// The heart, on or off.
    case favorite

    @MainActor
    func play() {
        DulcetUIProofMarkers.record("haptic:\(rawValue)")
#if os(iOS)
        switch self {
        case .playPause, .skip, .queue:
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        case .swipeSkip:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred(intensity: 0.7)
        case .reorderLift:
            UIImpactFeedbackGenerator(style: .light).impactOccurred(intensity: 0.8)
        case .reorder:
            UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.6)
        case .remove:
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        case .favorite:
            UISelectionFeedbackGenerator().selectionChanged()
        }
#endif
    }
}
