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
        // As the system player draws it: a quiet speaker at each end of a slim track whose filled
        // part carries the weight, and a small thumb. MPVolumeView's stock thumb is a large white
        // disc that outweighed the track beside it (OBSERVED on the test iPhone, 2026-11-27).
        HStack(spacing: DulcetSpacing.xs) {
            Image(systemName: "speaker.fill").accessibilityHidden(true)
            DulcetVolumeViewRepresentable()
                .frame(height: 34)
                .accessibilityLabel(DulcetQueueStrings.volume)
                .accessibilityIdentifier("dulcet.player.volume")
            Image(systemName: "speaker.wave.3.fill").accessibilityHidden(true)
        }
        .font(.caption)
        .dulcetForeground(.secondaryTextOnWindow)
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
        let view = MPVolumeView(frame: .zero)
        view.setMinimumVolumeSliderImage(Self.track(alpha: 0.92), for: .normal)
        view.setMaximumVolumeSliderImage(Self.track(alpha: 0.28), for: .normal)
        view.setVolumeThumbImage(Self.thumb(diameter: 14), for: .normal)
        // A finger on the thumb grows it, so the drag still shows what it holds.
        view.setVolumeThumbImage(Self.thumb(diameter: 22), for: .highlighted)
        return view
    }

    /// A 4 pt capsule the slider stretches between its rounded ends.
    private static func track(alpha: CGFloat) -> UIImage {
        let size = CGSize(width: 9, height: 4)
        let image = UIGraphicsImageRenderer(size: size).image { _ in
            UIColor.white.withAlphaComponent(alpha).setFill()
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: 2).fill() // design-token: allow a bitmap drawn for UIKit, half its 4 pt height
        }
        return image.resizableImage(withCapInsets: UIEdgeInsets(top: 0, left: 4, bottom: 0, right: 4))
    }

    /// The thumb, drawn with room for its shadow, which also widens where a finger can take it.
    private static func thumb(diameter: CGFloat) -> UIImage {
        let inset: CGFloat = 4 // design-token: allow bitmap room for the thumb's shadow, not layout
        let size = CGSize(width: diameter + inset * 2, height: diameter + inset * 2)
        return UIGraphicsImageRenderer(size: size).image { context in
            context.cgContext.setShadow(offset: CGSize(width: 0, height: 1), blur: 3,
                color: UIColor.black.withAlphaComponent(0.25).cgColor)
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(x: inset, y: inset, width: diameter, height: diameter)).fill()
        }
    }

    func updateUIView(_ view: MPVolumeView, context: Context) {}
}
#endif
