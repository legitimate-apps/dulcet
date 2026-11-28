import SwiftUI

enum DulcetQueueCarryCompletion: Equatable {
    case dropped
    case cancelled
}

/// Up Next's reorder handle, as the system player's queue has it: every row shows a grabber, and a
/// drag that starts on it moves the row at once -- no Edit first, and no long press. The row's
/// tap, its swipe to remove and its context menu stay as they are, because the List never enters
/// edit mode (which would take the tap away).
///
/// The arithmetic lives here, apart from the view, so a test can hold it to the queue order a drop
/// produces: which upcoming position the dragged row reaches, how far each row it passes steps
/// aside to show the gap, and the `onMove`-style destination the model turns into a core move.
struct DulcetQueueHandleDrag: Equatable {
    /// The upcoming order behind the geometry, captured at the start of the carry.
    let entryIDs: [DulcetQueueEntryID]
    let source: Int
    var sourceID: DulcetQueueEntryID { entryIDs[source] }
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
    init?(source: Int, entryIDs: [DulcetQueueEntryID], frames: [CGRect?]) {
        guard frames.count == entryIDs.count, frames.indices.contains(source),
              let anchor = frames[source], anchor.height > 0 else { return nil }
        self.entryIDs = entryIDs
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

    /// The gap is meaningful while the upcoming order matches the layout the finger moved through.
    /// History may still have changed, so translate the gap using the live model's queue base.
    func moveIntent(
        completion: DulcetQueueCarryCompletion, in model: DulcetUpNextModel
    ) -> DulcetQueueEditIntent? {
        guard completion == .dropped, let moveDestination,
              model.upcoming.map(\.id) == entryIDs,
              let from = model.upcoming.firstIndex(where: { $0.id == sourceID }) else { return nil }
        return model.moveIntent(fromOffsets: IndexSet(integer: from), toOffset: moveDestination)
    }
}

#if os(iOS)
import UIKit

/// What a row's grabber does with a drag that starts on it. `began` answers whether a drag began;
/// `changed` takes the finger's travel since then, in points, positive downward.
struct DulcetQueueHandleCallbacks {
    let began: () -> Bool
    let changed: (CGFloat) -> Void
    let isActive: () -> Bool
    let finished: (DulcetQueueCarryCompletion) -> Void
}

/// The grabber at a row's trailing edge. A drag that starts on it moves the row at once, as the
/// system player's handle does; the rest of the row scrolls the list.
///
/// The grabber does not hold the drag itself. A gesture on a row lives in that row's cell, and a
/// lazy list takes the cell away once the list scrolls it off screen, ending the gesture without a
/// word (hosted CI run 46102176054), so a row could never be carried past the screen's edge. The
/// grabber only marks where a drag may start; the list's own scroll view owns the drag
/// (``DulcetQueueCarryRecognizer``), which outlives every cell and can scroll the list beneath it.
struct DulcetQueueReorderHandle: View {
    let callbacks: DulcetQueueHandleCallbacks

    var body: some View {
        Image(systemName: "line.3.horizontal")
            .font(.body.weight(.medium))
            .dulcetForeground(.primaryTextOnWindow)
            .frame(minWidth: DulcetMetrics.minimumTouchTarget, minHeight: DulcetMetrics.minimumTouchTarget)
            .overlay { DulcetQueueHandleMarker(callbacks: callbacks) }
            // VoiceOver and Switch Control move a row with its Move Up and Move Down actions.
            .accessibilityHidden(true)
    }
}

/// The grabber's touch area as a UIKit view, so the list's recognizer can tell from the touched
/// view that a touch began on a grabber, and which one.
private struct DulcetQueueHandleMarker: UIViewRepresentable {
    let callbacks: DulcetQueueHandleCallbacks

    func makeUIView(context: Context) -> DulcetQueueHandleMarkerView {
        let view = DulcetQueueHandleMarkerView()
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ view: DulcetQueueHandleMarkerView, context: Context) {
        view.callbacks = callbacks
    }
}

final class DulcetQueueHandleMarkerView: UIView {
    var callbacks: DulcetQueueHandleCallbacks?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window != nil else { return }
        // The nearest scroll view above a row is the list's own.
        var ancestor = superview
        while let view = ancestor, !(view is UIScrollView) { ancestor = view.superview }
        if let scrollView = ancestor as? UIScrollView { DulcetQueueCarryRecognizer.install(on: scrollView) }
    }
}

/// The Up Next reorder drag, owned by the list's scroll view rather than by a row: it begins only
/// on a touch that lands on a grabber, takes the touch from the list's own pan (so the list does
/// not scroll under the finger as well), and while the finger holds the row near the list's top or
/// bottom edge it scrolls the list itself, so a row can be carried past the screen in one drag.
final class DulcetQueueCarryRecognizer: UIGestureRecognizer {
    /// How near the list's top or bottom edge the finger carries the list on, in points.
    static let edgeZone: CGFloat = 56 // design-token: allow gesture zone, not layout
    /// How long the finger rests in an edge zone before the list starts to move, so a drop on the
    /// last row in view does not scroll it away.
    static let edgeDwell: CFTimeInterval = 0.45
    /// The fastest the list scrolls, in points a second, with the finger at or past the edge.
    static let maximumSpeed: CGFloat = 900 // design-token: allow scroll speed, not layout

    static func install(on scrollView: UIScrollView) {
        guard !(scrollView.gestureRecognizers ?? []).contains(where: { $0 is DulcetQueueCarryRecognizer }) else { return }
        let recognizer = DulcetQueueCarryRecognizer(target: nil, action: nil)
        recognizer.delaysTouchesBegan = false
        scrollView.addGestureRecognizer(recognizer)
    }

    private var touch: UITouch?
    private var callbacks: DulcetQueueHandleCallbacks?
    private var startY: CGFloat = 0
    private var displayLink: CADisplayLink?
    private var lastTick: CFTimeInterval?
    private var zoneEntered: CFTimeInterval?

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if touch != nil {
            touches.forEach { ignore($0, for: event) }
            return
        }
        guard state == .possible, touches.count == 1, let first = touches.first,
              let marker = Self.marker(above: first.view), let callbacks = marker.callbacks,
              callbacks.began()
        else {
            state = .failed
            return
        }
        touch = first
        self.callbacks = callbacks
        startY = first.location(in: nil).y
        state = .began
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        callbacks?.changed(touch.location(in: nil).y - startY)
        state = .changed
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        finish(.dropped)
        state = .ended
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch, touches.contains(touch) else { return }
        finish(.cancelled)
        state = .cancelled
    }

    override func reset() {
        super.reset()
        finish(.cancelled)
        touch = nil
        lastTick = nil
        zoneEntered = nil
    }

    private func finish(_ completion: DulcetQueueCarryCompletion) {
        displayLink?.invalidate()
        displayLink = nil
        let callbacks = callbacks
        self.callbacks = nil
        callbacks?.finished(completion)
    }

    /// Scrolls the list while the finger rests near its top or bottom edge, faster the deeper
    /// the finger is in the edge zone. The rows report their new frames as the list scrolls, and
    /// the drag reads its scroll from them, so the gap follows the finger through the list.
    @objc private func tick(_ link: CADisplayLink) {
        guard let touch else { return }
        guard callbacks?.isActive() == true, let scrollView = view as? UIScrollView,
              scrollView.window != nil else {
            finish(.cancelled)
            state = .cancelled
            return
        }
        let now = link.timestamp
        defer { lastTick = now }
        let point = touch.location(in: scrollView)
        let bounds = scrollView.bounds
        let zone = min(Self.edgeZone, bounds.height / 4)
        var depth: CGFloat = 0
        if point.y > bounds.maxY - zone {
            depth = min(1, (point.y - (bounds.maxY - zone)) / zone)
        } else if point.y < bounds.minY + zone {
            depth = -min(1, ((bounds.minY + zone) - point.y) / zone)
        }
        guard depth != 0 else { zoneEntered = nil; return }
        let entered = zoneEntered ?? now
        zoneEntered = entered
        guard now - entered >= Self.edgeDwell, let last = lastTick else { return }
        let inset = scrollView.adjustedContentInset
        let lowest = -inset.top
        let highest = max(lowest, scrollView.contentSize.height + inset.bottom - bounds.height)
        let step = depth * abs(depth) * Self.maximumSpeed * CGFloat(min(now - last, 1.0 / 30))
        let next = min(highest, max(lowest, scrollView.contentOffset.y + step))
        guard next != scrollView.contentOffset.y else { return }
        scrollView.contentOffset.y = next
    }

    private static func marker(above view: UIView?) -> DulcetQueueHandleMarkerView? {
        var candidate = view
        while let current = candidate, !(current is UIScrollView) {
            if let marker = current as? DulcetQueueHandleMarkerView { return marker }
            candidate = current.superview
        }
        return nil
    }
}
#endif
