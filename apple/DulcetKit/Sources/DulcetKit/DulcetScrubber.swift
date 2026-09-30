#if os(macOS) || os(iOS)
import SwiftUI

/// The player's seek bar, drawn as the system player's is: a slim track whose played part takes
/// the primary colour, no knob at rest, swelling while it is dragged. The platform Slider's
/// large pill thumb read as a button parked on the track rather than as the playhead.
///
/// A touch or press anywhere in the row moves the playhead; letting go seeks. VoiceOver gets a
/// platform slider whose swipes step ten seconds and seek at once.
struct DulcetScrubber: View {
    @Binding var value: Double
    let range: ClosedRange<Double>
    var onEditingChanged: (Bool) -> Void = { _ in }

    init(
        value: Binding<Double>,
        in range: ClosedRange<Double>,
        onEditingChanged: @escaping (Bool) -> Void = { _ in }
    ) {
        _value = value
        self.range = range
        self.onEditingChanged = onEditingChanged
    }

    @State private var isDragging = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The track's height at rest and while dragged.
    static let restingHeight: CGFloat = 6
    static let draggingHeight: CGFloat = 12
    /// The row's height. The bar's growth stays inside it, so dragging never shifts the layout.
    static let rowHeight: CGFloat = 28
    /// One swipe of the adjustable element, in the bar's units (seconds).
    static let voiceOverStep: Double = 10

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(.primary.opacity(0.16))
                Capsule()
                    .fill(.primary)
                    .frame(width: geometry.size.width * fraction)
            }
            .frame(height: isDragging ? Self.draggingHeight : Self.restingHeight)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            .contentShape(Rectangle())
            .gesture(drag(width: geometry.size.width))
        }
        .frame(height: Self.rowHeight)
        .animation(reduceMotion ? nil : .spring(duration: 0.24), value: isDragging)
        // Accessibility sees a platform Slider, so VoiceOver announces a slider and UI tests find
        // one (`app.sliders["Now Playing"]`); the drawn bar above is presentation only.
        .accessibilityRepresentation {
            Slider(value: $value, in: range)
                .accessibilityAdjustableAction { direction in
                    switch direction {
                    case .increment: value = min(range.upperBound, value + Self.voiceOverStep)
                    case .decrement: value = max(range.lowerBound, value - Self.voiceOverStep)
                    @unknown default: return
                    }
                    // No finger to lift: a swipe seeks at once, and the bar keeps following the player.
                    onEditingChanged(false)
                }
        }
    }

    private var fraction: CGFloat {
        let span = range.upperBound - range.lowerBound
        guard span > 0 else { return 0 }
        return CGFloat(min(1, max(0, (value - range.lowerBound) / span)))
    }

    private func drag(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { drag in
                if !isDragging {
                    isDragging = true
                    onEditingChanged(true)
                }
                value = position(at: drag.location.x, width: width)
            }
            .onEnded { drag in
                value = position(at: drag.location.x, width: width)
                isDragging = false
                onEditingChanged(false)
            }
    }

    private func position(at x: CGFloat, width: CGFloat) -> Double {
        guard width > 0 else { return range.lowerBound }
        let fraction = min(1, max(0, x / width))
        return range.lowerBound + fraction * (range.upperBound - range.lowerBound)
    }
}
#endif
