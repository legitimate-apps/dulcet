import SwiftUI

#if os(macOS)
import AVKit

/// Keep the system route picker and its menu, with the player's semantic control color.
/// The shared picker accepts a tint on iOS; AppKit exposes a separate per-state color API.
struct DulcetPlayerRoutePicker: NSViewRepresentable {
    func makeNSView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.isRoutePickerButtonBordered = false
        apply(to: view)
        return view
    }

    func updateNSView(_ view: AVRoutePickerView, context: Context) { apply(to: view) }

    private func apply(to view: AVRoutePickerView) {
        view.setRoutePickerButtonColor(.labelColor, for: .normal)
        view.setRoutePickerButtonColor(.labelColor, for: .normalHighlighted)
        view.setRoutePickerButtonColor(.controlAccentColor, for: .active)
        view.setRoutePickerButtonColor(.controlAccentColor, for: .activeHighlighted)
        view.setAccessibilityLabel(DulcetQueueStrings.airPlay)
        view.setAccessibilityIdentifier("dulcet.player.airPlay")
    }
}
#endif
