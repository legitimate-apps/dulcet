import Foundation

#if os(iOS) || os(tvOS)
import AVFAudio
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
#else
/// macOS has no `AVAudioSession`; AVPlayer participates in the desktop audio policy directly.
public final class DulcetPlatformAudioSession: DulcetAudioSessionManaging, @unchecked Sendable {
    private let lock = NSLock()
    private var eventHandler: (@Sendable (DulcetAudioSessionEvent) -> Void)?

    public init() {}

    public func setEventHandler(_ handler: (@Sendable (DulcetAudioSessionEvent) -> Void)?) {
        lock.lock()
        eventHandler = handler
        lock.unlock()
    }

    public func activate() throws {}
    public func deactivate() {}
}
#endif
