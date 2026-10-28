import SwiftUI

#if os(macOS)
import AudioToolbox
import CoreAudio
#endif

/// Output volume belongs to the platform. This control never sends a playback or queue command.
struct DulcetPlayerVolumeControl: View {
#if os(macOS)
    @State private var output = DulcetMacOutputVolume()
#endif

    var body: some View {
#if os(iOS)
        DulcetSystemVolumeSlider()
#elseif os(macOS)
        HStack(spacing: 10) {
            Image(systemName: "speaker.fill").accessibilityHidden(true)
            Slider(value: Binding(
                get: { output.value ?? 0 },
                set: { output.set($0) }
            ), in: 0...1)
                .disabled(!output.writable)
                .accessibilityLabel(DulcetQueueStrings.volume)
                .accessibilityValue(DulcetStrings.volumeValue(output.value ?? 0))
                .accessibilityIdentifier("dulcet.player.volume")
            Image(systemName: "speaker.wave.3.fill").accessibilityHidden(true)
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .onAppear { output.start() }
        .onDisappear { output.stop() }
#endif
    }
}

#if os(macOS)
/// Observes the selected output and its native aggregate volume. Outputs that expose no software
/// volume (for example some digital receivers) keep a disabled slider, just as Sound settings do.
@MainActor @Observable
private final class DulcetMacOutputVolume {
    private(set) var value: Double?
    private(set) var writable = false
    private var device = AudioObjectID(kAudioObjectUnknown)
    private var registrations: [(AudioObjectID, AudioObjectPropertyAddress, AudioObjectPropertyListenerBlock)] = []

    func start() {
        stop()
        var route = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let system = AudioObjectID(kAudioObjectSystemObject)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(system, &route, 0, nil, &size, &device) == noErr else {
            value = nil
            writable = false
            return
        }
        observe(system, address: route, routeChanged: true)
        observe(device, address: volumeAddress, routeChanged: false)
        refresh()
    }

    func stop() {
        for (object, var address, listener) in registrations {
            AudioObjectRemovePropertyListenerBlock(object, &address, .main, listener)
        }
        registrations.removeAll()
    }

    func set(_ requested: Double) {
        guard writable else { return }
        var address = volumeAddress
        var scalar = Float32(min(1, max(0, requested)))
        _ = AudioObjectSetPropertyData(
            device, &address, 0, nil, UInt32(MemoryLayout<Float32>.size), &scalar
        )
        // Read back even after a refusal: the thumb always reports the output's actual value.
        refresh()
    }

    private var volumeAddress: AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(
            mSelector: kAudioHardwareServiceDeviceProperty_VirtualMainVolume,
            mScope: kAudioDevicePropertyScopeOutput,
            mElement: kAudioObjectPropertyElementMain
        )
    }

    private func refresh() {
        var address = volumeAddress
        var scalar: Float32 = 0
        var size = UInt32(MemoryLayout<Float32>.size)
        var settable = DarwinBoolean(false)
        let read = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &scalar)
        let write = AudioObjectIsPropertySettable(device, &address, &settable)
        value = read == noErr && scalar.isFinite ? Double(min(1, max(0, scalar))) : nil
        writable = value != nil && write == noErr && settable.boolValue
    }

    private func observe(_ object: AudioObjectID, address: AudioObjectPropertyAddress, routeChanged: Bool) {
        var address = address
        let listener: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            Task { @MainActor [weak self] in
                if routeChanged { self?.start() } else { self?.refresh() }
            }
        }
        if AudioObjectAddPropertyListenerBlock(object, &address, .main, listener) == noErr {
            registrations.append((object, address, listener))
        }
    }
}
#endif
