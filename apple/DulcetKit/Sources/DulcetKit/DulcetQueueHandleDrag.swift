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
    /// Where every upcoming row sat when the drag began, in the list's space and upcoming order.
    /// A lazy list lays out only the rows near the screen, so a row it has not laid out has no
    /// frame; its slot is estimated from its neighbours until the list lays it out (`observe`).
    private(set) var slots: [CGRect]
    /// The rows whose slot was measured rather than estimated, with the measured slot.
    private var measuredSlots: [Int: CGRect]
    /// The list's spacing between one row's bottom and the next row's top. The rows the dragged
    /// one passes step aside by its height and this.
    let spacing: CGFloat
    private let typicalHeight: CGFloat
    /// How far the finger has moved since the drag began.
    var translation: CGFloat = 0
    /// How far the list has scrolled since the drag began, positive when its content moved up.
    private(set) var scroll: CGFloat = 0

    /// `frames` holds each upcoming row's frame in the list's space, nil for a row the list has not
    /// laid out. The dragged row is always laid out: the finger is on it.
    init?(source: Int, frames: [CGRect?]) {
        guard frames.indices.contains(source), let anchor = frames[source], anchor.height > 0 else { return nil }
        // Before this, a row without a frame counted as one at the top of the list, above any
        // drop point, so every unlaid row below the screen pushed the drop one place further:
        // in a 13-song queue a three-row drag landed near the end (OBSERVED on iPhone, 2026-11-27).
        var known: [Int: CGRect] = [:]
        for (index, frame) in frames.enumerated() { if let frame, frame.height > 0 { known[index] = frame } }
        let heights = known.values.map(\.height).sorted()
        let typicalHeight = heights[heights.count / 2]
        let gaps = known.keys.compactMap { above -> CGFloat? in
            guard let top = known[above], let bottom = known[above + 1] else { return nil }
            let gap = bottom.minY - top.maxY
            return gap >= 0 && gap < typicalHeight ? gap : nil
        }.sorted()
        let spacing = gaps.isEmpty ? 0 : gaps[gaps.count / 2]
        // A frame counts only where it continues the run of rows from the dragged one: a row the
        // list laid out once and scrolled away keeps its old frame, which is not where it is now.
        var measured: [Int: CGRect] = [source: anchor]
        var edge = anchor
        for index in frames.indices where index > source {
            guard let frame = known[index], abs(frame.minY - edge.maxY - spacing) < 1 else { break }
            measured[index] = frame
            edge = frame
        }
        edge = anchor
        for index in frames.indices.reversed() where index < source {
            guard let frame = known[index], abs(edge.minY - frame.maxY - spacing) < 1 else { break }
            measured[index] = frame
            edge = frame
        }
        self.source = source
        self.spacing = spacing
        self.typicalHeight = typicalHeight
        measuredSlots = measured
        slots = Self.layout(count: frames.count, anchor: source, measured: measured,
                            spacing: spacing, typicalHeight: typicalHeight)
    }

    /// Every slot: measured ones as measured, the rest a typical row one spacing from the slot
    /// next to it, working outward from the dragged row.
    private static func layout(
        count: Int, anchor: Int, measured: [Int: CGRect], spacing: CGFloat, typicalHeight: CGFloat
    ) -> [CGRect] {
        var slots = Array(repeating: CGRect.zero, count: count)
        slots[anchor] = measured[anchor]!
        let width = slots[anchor].width, x = slots[anchor].minX
        for index in (anchor + 1)..<max(anchor + 1, count) {
            slots[index] = measured[index]
                ?? CGRect(x: x, y: slots[index - 1].maxY + spacing, width: width, height: typicalHeight)
        }
        for index in stride(from: anchor - 1, through: 0, by: -1) {
            slots[index] = measured[index]
                ?? CGRect(x: x, y: slots[index + 1].minY - spacing - typicalHeight, width: width, height: typicalHeight)
        }
        return slots
    }

    /// Takes in the rows the list has laid out now, upcoming position to frame. A row measured at
    /// the start has moved by exactly the list's scroll since then, which places the finger in the
    /// list's content; a row laid out since (the list scrolled it in) replaces its estimate.
    mutating func observe(currentFrames frames: [Int: CGRect]) {
        let current = frames.filter { slots.indices.contains($0.key) && $0.value.height > 0 }
        guard let reference = current.keys.filter({ measuredSlots[$0] != nil }).min() else { return }
        scroll = measuredSlots[reference]!.minY - current[reference]!.minY
        var changed = false
        for (index, frame) in current where measuredSlots[index] == nil {
            measuredSlots[index] = frame.offsetBy(dx: 0, dy: scroll)
            changed = true
        }
        if changed {
            slots = Self.layout(count: slots.count, anchor: source, measured: measuredSlots,
                                spacing: spacing, typicalHeight: typicalHeight)
        }
    }

    /// The dragged row's middle in the list's content, measured in the slots' space: where the
    /// finger holds it, plus however far the list scrolled beneath it.
    private var middle: CGFloat { slots[source].midY + translation + scroll }

    /// The upcoming position the row lands on if dropped now: the number of other rows whose
    /// middle lies above the dragged row's middle. Rows of different heights (a long title wraps)
    /// count by where they are, not by an assumed height.
    var target: Int {
        let middle = middle
        return slots.indices.filter { $0 != source && slots[$0].midY < middle }.count
    }

    /// How far the row at `index` steps aside to open the gap where the dragged row would land:
    /// the dragged row's height and the list's spacing. The dragged row itself follows the finger.
    func displacement(of index: Int) -> CGFloat {
        let pitch = slots[source].height + spacing
        let target = target
        if index == source { return 0 }
        if source < target, index > source, index <= target { return -pitch }
        if target < source, index >= target, index < source { return pitch }
        return 0
    }

    /// The dragged row's offset from its own place in the list: the finger's travel, plus the
    /// scroll that carried its place away beneath the finger.
    var liftedOffset: CGFloat { translation + scroll }

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
            .frame(minWidth: DulcetMetrics.minimumTouchTarget, minHeight: DulcetMetrics.minimumTouchTarget)
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
