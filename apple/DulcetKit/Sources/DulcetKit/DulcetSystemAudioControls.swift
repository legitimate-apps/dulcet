import SwiftUI
import AVKit

#if os(iOS)
import MediaPlayer
import UIKit
#endif

/// The system AirPlay / output picker: the same control Music shows. It lists AirPlay speakers,
/// Bluetooth and wired outputs, and the system handles the switch; the audio session's
/// long-form route-sharing policy is what lets a music app appear there correctly.
public struct DulcetAirPlayRoutePicker: View {
    private let tint: Color?

    public init(tint: Color? = nil) {
        self.tint = tint
    }

    public var body: some View {
        DulcetRoutePickerRepresentable(tint: tint)
            .frame(width: 44, height: 44)
            .accessibilityLabel(DulcetQueueStrings.airPlay)
            .accessibilityIdentifier("dulcet.player.airPlay")
    }
}

#if os(iOS) || os(tvOS)
private struct DulcetRoutePickerRepresentable: UIViewRepresentable {
    let tint: Color?

    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.prioritizesVideoDevices = false
        apply(to: view)
        return view
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {
        apply(to: view)
    }

    private func apply(to view: AVRoutePickerView) {
        #if os(iOS)
        if let tint {
            view.activeTintColor = UIColor(tint)
            view.tintColor = UIColor(tint)
        }
        #endif
    }
}
#elseif os(macOS)
private struct DulcetRoutePickerRepresentable: NSViewRepresentable {
    let tint: Color?

    func makeNSView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.isRoutePickerButtonBordered = false
        return view
    }

    func updateNSView(_ view: AVRoutePickerView, context: Context) {}
}
#endif

/// The system volume slider, which moves the device's output volume (and an AirPlay speaker's)
/// exactly as the hardware buttons do. Only iOS and iPadOS have an app-embeddable system volume
/// control: tvOS volume belongs to the remote and the TV, and macOS apps have no system-volume
/// view, so on those platforms this renders nothing rather than a slider that moves only this
/// app's own gain.
public struct DulcetSystemVolumeSlider: View {
    public init() {}

    public var body: some View {
        #if os(iOS)
        DulcetVolumeViewRepresentable()
            .frame(height: 34)
            .accessibilityLabel(DulcetQueueStrings.volume)
            .accessibilityIdentifier("dulcet.player.volume")
        #else
        EmptyView()
        #endif
    }

    /// Whether this platform renders a system volume control at all, so a layout can omit the
    /// row instead of reserving space for nothing.
    public static var isAvailable: Bool {
        #if os(iOS)
        true
        #else
        false
        #endif
    }
}

#if os(iOS)
private struct DulcetVolumeViewRepresentable: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        MPVolumeView(frame: .zero)
    }

    func updateUIView(_ view: MPVolumeView, context: Context) {}
}
#endif
