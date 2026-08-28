import Foundation

#if os(iOS) || os(tvOS)
import AVFAudio
#elseif os(macOS)
import CoreAudio
#endif

public enum DulcetAudioSessionEvent: Equatable, Sendable {
    case interruptionBegan
    case interruptionEnded(systemAllowsResume: Bool)
    case routeChanged(
        old: DulcetPlaybackRouteKind,
        new: DulcetPlaybackRouteKind,
        becomingNoisy: Bool
    )
    case externalPlaybackBegan
}

public protocol DulcetAudioSessionManaging: AnyObject, Sendable {
    func setEventHandler(_ handler: (@Sendable (DulcetAudioSessionEvent) -> Void)?)
    func activate() throws
    func deactivate()
}

#if os(iOS) || os(tvOS)
public final class DulcetPlatformAudioSession: DulcetAudioSessionManaging, @unchecked Sendable {
    private let session: AVAudioSession
    private let notificationCenter: NotificationCenter
    private let lock = NSLock()
    private var eventHandler: (@Sendable (DulcetAudioSessionEvent) -> Void)?
    private var observers: [NSObjectProtocol] = []

    public init(
        session: AVAudioSession = .sharedInstance(),
        notificationCenter: NotificationCenter = .default
    ) {
        self.session = session
        self.notificationCenter = notificationCenter
        observeSystemPolicy()
    }

    deinit {
        observers.forEach(notificationCenter.removeObserver)
    }

    public func setEventHandler(_ handler: (@Sendable (DulcetAudioSessionEvent) -> Void)?) {
        lock.lock()
        eventHandler = handler
        lock.unlock()
    }

    public func activate() throws {
        // `.playback` permits AirPlay by default. No mixing option is supplied deliberately.
        try session.setCategory(.playback, mode: .default, options: [])
        try session.setActive(true)
    }

    public func deactivate() {
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func observeSystemPolicy() {
        observers.append(notificationCenter.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: session,
            queue: nil
        ) { [weak self] notification in
            self?.handleInterruption(notification)
        })
        observers.append(notificationCenter.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: session,
            queue: nil
        ) { [weak self] notification in
            self?.handleRouteChange(notification)
        })
        observers.append(notificationCenter.addObserver(
            forName: AVAudioSession.silenceSecondaryAudioHintNotification,
            object: session,
            queue: nil
        ) { [weak self] notification in
            guard let rawValue = notification.userInfo?[AVAudioSessionSilenceSecondaryAudioHintTypeKey]
                as? UInt,
                  AVAudioSession.SilenceSecondaryAudioHintType(rawValue: rawValue) == .begin else {
                return
            }
            self?.publish(.externalPlaybackBegan)
        })
    }

    private func handleInterruption(_ notification: Notification) {
        guard let rawType = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: rawType) else { return }
        switch type {
        case .began:
            publish(.interruptionBegan)
        case .ended:
            let rawOptions = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            publish(.interruptionEnded(
                systemAllowsResume: AVAudioSession.InterruptionOptions(rawValue: rawOptions)
                    .contains(.shouldResume)
            ))
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ notification: Notification) {
        let oldRoute = notification.userInfo?[AVAudioSessionRouteChangePreviousRouteKey]
            as? AVAudioSessionRouteDescription
        let rawReason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
        let reason = rawReason.flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
        publish(.routeChanged(
            old: routeKind(oldRoute),
            new: routeKind(session.currentRoute),
            becomingNoisy: reason == .oldDeviceUnavailable
        ))
    }

    private func routeKind(_ route: AVAudioSessionRouteDescription?) -> DulcetPlaybackRouteKind {
        guard let port = route?.outputs.first?.portType else { return .unknown }
        switch port {
        case .builtInReceiver, .builtInSpeaker:
            return .builtIn
        case .headphones, .headsetMic, .lineOut, .usbAudio:
            return .wired
        case .bluetoothA2DP, .bluetoothHFP, .bluetoothLE:
            return .bluetooth
        case .HDMI:
            return .hdmi
        case .airPlay:
            return .remote
        default:
            return .unknown
        }
    }

    private func publish(_ event: DulcetAudioSessionEvent) {
        lock.lock()
        let handler = eventHandler
        lock.unlock()
        handler?(event)
    }
}
#elseif os(macOS)
/// macOS has no AVAudioSession. Core Audio still reports output-route replacement.
public final class DulcetPlatformAudioSession: DulcetAudioSessionManaging, @unchecked Sendable {
    private let lock = NSLock()
    private var eventHandler: (@Sendable (DulcetAudioSessionEvent) -> Void)?
    private let routeQueue = DispatchQueue(label: "com.legitimateapps.dulcet.audio-route")
    private var currentRoute: DulcetPlaybackRouteKind
    private var routeListener: AudioObjectPropertyListenerBlock?
    private var routeListenerInstalled = false

    public init() {
        currentRoute = Self.readCurrentRoute()
        let listener: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            self?.routeDidChange()
        }
        routeListener = listener
        var address = Self.defaultOutputDeviceAddress
        routeListenerInstalled = AudioObjectAddPropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            routeQueue,
            listener
        ) == noErr
    }

    deinit {
        guard routeListenerInstalled, let routeListener else { return }
        var address = Self.defaultOutputDeviceAddress
        AudioObjectRemovePropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            routeQueue,
            routeListener
        )
    }

    public func setEventHandler(_ handler: (@Sendable (DulcetAudioSessionEvent) -> Void)?) {
        lock.lock()
        eventHandler = handler
        lock.unlock()
    }

    public func activate() throws {}
    public func deactivate() {}

    private func routeDidChange() {
        let newRoute = Self.readCurrentRoute()
        lock.lock()
        let oldRoute = currentRoute
        currentRoute = newRoute
        let handler = eventHandler
        lock.unlock()
        guard oldRoute != newRoute else { return }
        handler?(.routeChanged(
            old: oldRoute,
            new: newRoute,
            becomingNoisy: DulcetMacAudioRouteClassifier.becomingNoisy(
                old: oldRoute,
                new: newRoute
            )
        ))
    }

    private static var defaultOutputDeviceAddress: AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
    }

    private static func readCurrentRoute() -> DulcetPlaybackRouteKind {
        var device = AudioDeviceID(kAudioObjectUnknown)
        var deviceSize = UInt32(MemoryLayout<AudioDeviceID>.size)
        var defaultAddress = defaultOutputDeviceAddress
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &defaultAddress,
            0,
            nil,
            &deviceSize,
            &device
        ) == noErr, device != kAudioObjectUnknown else { return .unknown }

        var transport = UInt32(0)
        var transportSize = UInt32(MemoryLayout<UInt32>.size)
        var transportAddress = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyTransportType,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        guard AudioObjectGetPropertyData(
            device,
            &transportAddress,
            0,
            nil,
            &transportSize,
            &transport
        ) == noErr else { return .unknown }
        return DulcetMacAudioRouteClassifier.kind(transportType: transport)
    }
}

enum DulcetMacAudioRouteClassifier {
    static func kind(transportType: UInt32) -> DulcetPlaybackRouteKind {
        switch transportType {
        case kAudioDeviceTransportTypeBuiltIn:
            return .builtIn
        case kAudioDeviceTransportTypeUSB, kAudioDeviceTransportTypePCI,
             kAudioDeviceTransportTypeFireWire, kAudioDeviceTransportTypeThunderbolt:
            return .wired
        case kAudioDeviceTransportTypeBluetooth, kAudioDeviceTransportTypeBluetoothLE:
            return .bluetooth
        case kAudioDeviceTransportTypeHDMI, kAudioDeviceTransportTypeDisplayPort:
            return .hdmi
        case kAudioDeviceTransportTypeAirPlay:
            return .remote
        default:
            return .unknown
        }
    }

    static func becomingNoisy(
        old: DulcetPlaybackRouteKind,
        new: DulcetPlaybackRouteKind
    ) -> Bool {
        (old == .wired || old == .bluetooth || old == .remote) && new == .builtIn
    }
}
#endif
