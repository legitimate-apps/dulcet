import SwiftUI

/// Up Next's reorder handle, as the system player's queue has it: every row shows a grabber, and a
/// drag that starts on it moves the row at once -- no Edit first, and no long press. The row's
/// tap, its swipe to remove and its context menu stay as they are, because the List never enters
/// edit mode (which would take the tap away).
///
/// The arithmetic lives here, apart from the view, so a test can hold it to the queue order a drop
/// produces: which upcoming position the dragged row reaches, how far each row it passes steps
/// aside to show the gap, and the `onMove`-style destination the model turns into a core move.
struct DulcetQueueHandleDrag: Equatable {
    /// The upcoming row being dragged, by its position when the drag began.
    let source: Int
    /// The rows' frames in the list's space when the drag began, in upcoming order.
    let frames: [CGRect]
    /// How far the finger has moved since the drag began.
    var translation: CGFloat = 0

    init?(source: Int, frames: [CGRect]) {
        guard frames.indices.contains(source) else { return nil }
        self.source = source
        self.frames = frames
    }

    /// The upcoming position the row lands on if dropped now: the number of other rows whose
    /// middle lies above the dragged row's middle. Rows of different heights (a long title wraps)
    /// count by where they are, not by an assumed height.
    var target: Int {
        let middle = frames[source].midY + translation
        return frames.indices.filter { $0 != source && frames[$0].midY < middle }.count
    }

    /// How far the row at `index` steps aside, the dragged row's own height, to open the gap where
    /// the dragged row would land. The dragged row itself follows the finger instead.
    func displacement(of index: Int) -> CGFloat {
        let height = frames[source].height
        let target = target
        if index == source { return 0 }
        if source < target, index > source, index <= target { return -height }
        if target < source, index >= target, index < source { return height }
        return 0
    }

    /// The `onMove` destination for this drop -- `DulcetUpNextModel.moveIntent` reads it -- or nil
    /// when the row would land where it started.
    var moveDestination: Int? {
        let target = target
        guard target != source else { return nil }
        return target > source ? target + 1 : target
    }
}

#if os(iOS)
/// The grabber at a row's trailing edge. It starts a drag on touch, so a vertical scroll that
/// begins on it moves the row instead -- the system player's handle does the same; the rest of
/// the row scrolls the list.
struct DulcetQueueReorderHandle: View {
    let onChanged: (CGFloat) -> Void
    let onEnded: () -> Void
    /// Down while the finger is: it resets on its own when the system cancels the drag, where
    /// `onEnded` never runs, so a cancelled drag still ends.
    @GestureState private var pressed = false

    var body: some View {
        Image(systemName: "line.3.horizontal")
            .font(.body.weight(.medium))
            .dulcetForeground(.primaryTextOnWindow)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .global)
                    .updating($pressed) { _, pressed, _ in pressed = true }
                    .onChanged { onChanged($0.translation.height) }
                    .onEnded { _ in onEnded() }
            )
            .onChange(of: pressed) { _, down in if !down { onEnded() } }
            // VoiceOver and Switch Control move a row with its Move Up and Move Down actions.
            .accessibilityHidden(true)
    }
}
#endif
