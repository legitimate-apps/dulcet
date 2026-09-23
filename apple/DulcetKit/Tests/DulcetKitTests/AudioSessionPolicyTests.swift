#if os(iOS)
import AVFAudio
import Foundation
import Testing
@testable import DulcetKit

/// The iOS audio-session policy (spec §12.9), driven through the production observer with
/// notifications posted exactly as the system posts them, to an injected notification centre.
@Suite(.serialized)
struct AudioSessionPolicyTests {
    @Test
    func aSuspendedAppInterruptionIsIgnoredButACallOrSiriPauses() {
        let fixture = Fixture()

        fixture.post(AVAudioSession.interruptionNotification, [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue,
            AVAudioSessionInterruptionReasonKey: AVAudioSession.InterruptionReason.appWasSuspended.rawValue,
        ])
        #expect(fixture.events.isEmpty, "a suspension is not a call; pausing on it stops nothing real")

        // Positive control through the same observer: an ordinary interruption does arrive.
        fixture.post(AVAudioSession.interruptionNotification, [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue,
        ])
        fixture.post(AVAudioSession.interruptionNotification, [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue,
            AVAudioSessionInterruptionOptionKey: AVAudioSession.InterruptionOptions.shouldResume.rawValue,
        ])
        fixture.post(AVAudioSession.interruptionNotification, [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue,
        ])
        #expect(fixture.events == [
            .interruptionBegan,
            .interruptionEnded(systemAllowsResume: true),
            .interruptionEnded(systemAllowsResume: false),
        ])
    }

    @Test
    func anUnpluggedOutputIsNoisyAndANewOutputIsNot() {
        let fixture = Fixture()

        fixture.post(AVAudioSession.routeChangeNotification, [
            AVAudioSessionRouteChangeReasonKey: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue,
        ])
        fixture.post(AVAudioSession.routeChangeNotification, [
            AVAudioSessionRouteChangeReasonKey: AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue,
        ])

        let noisy = fixture.events.map { event -> Bool? in
            if case let .routeChanged(_, _, becomingNoisy) = event { becomingNoisy } else { nil }
        }
        #expect(noisy == [true, false], "Music pauses on unplug and does not resume on replug")
    }

    private final class Fixture: @unchecked Sendable {
        let center = NotificationCenter()
        let session = AVAudioSession.sharedInstance()
        let policy: DulcetPlatformAudioSession
        private let lock = NSLock()
        private var recorded: [DulcetAudioSessionEvent] = []

        init() {
            policy = DulcetPlatformAudioSession(session: session, notificationCenter: center)
            policy.setEventHandler { [weak self] event in
                guard let self else { return }
                lock.lock()
                recorded.append(event)
                lock.unlock()
            }
        }

        var events: [DulcetAudioSessionEvent] {
            lock.lock()
            defer { lock.unlock() }
            return recorded
        }

        func post(_ name: Notification.Name, _ userInfo: [AnyHashable: Any]) {
            center.post(name: name, object: session, userInfo: userInfo)
        }
    }
}
#endif
