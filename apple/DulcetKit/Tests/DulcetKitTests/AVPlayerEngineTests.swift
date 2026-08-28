import AVFoundation
import Foundation
import MediaPlayer
import Testing
@testable import DulcetKit

#if os(macOS)
import CoreAudio
#endif

@Suite(.serialized)
struct AVPlayerEngineTests {
    @Test
    func progressivePrepareUsesTheCustomLoaderAndReportsEngineSeekability() async throws {
        let resource = InMemoryPlaybackResource(data: makePCMWave(duration: 2))
        let engine = DulcetAVPlayerEngine()
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }

        let outcome = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan(resource: resource)))
        #expect(outcome == .accepted(commandID: .init("prepare")))

        try await waitUntil("AVPlayer did not become ready through the custom resource loader") {
            events.containsReady(seekability: .seekable)
        }
        // Reported rather than asserted bare, so a CI failure distinguishes "the engine never routed
        // through our loader" from "it did, but slower than the deadline allowed".
        #expect(
            resource.requests.count > 0,
            Comment(rawValue: "custom loader received \(resource.requests.count) requests")
        )
        #expect(resource.requests.first?.range.start == 0)
        #expect(resource.requests.first?.requiresAudioSignature == true)

        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func progressBeginsOnlyAfterMediaTimeAdvancesAndUsesMonotonicSamples() async throws {
        let engine = DulcetAVPlayerEngine()
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(resource: InMemoryPlaybackResource(data: makePCMWave(duration: 3)))

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        try await waitUntil("AVPlayer did not become ready") { events.containsReady(seekability: .seekable) }
        #expect(!events.containsProgressBegan)

        _ = await execute(engine, .play(commandID: .init("play")))
        try await waitUntil("the monotonic sampler did not observe advancing media time", timeout: 3) {
            events.positionSamples.count >= 2
        }

        let snapshot = events.snapshot
        let progressIndex = try #require(snapshot.firstIndex { event in
            if case .playbackProgressBegan = event { true } else { false }
        })
        let firstPositionIndex = try #require(snapshot.firstIndex { event in
            if case .positionChanged = event { true } else { false }
        })
        #expect(progressIndex < firstPositionIndex)
        let samples = events.positionSamples
        #expect(samples.allSatisfy { $0.position > 0 })
        #expect(zip(samples, samples.dropFirst()).allSatisfy {
            $0.position < $1.position && $0.monotonic < $1.monotonic
        })
        #expect(zip(samples, samples.dropFirst()).allSatisfy {
            Double($1.monotonic.uptimeNanoseconds - $0.monotonic.uptimeNanoseconds) / 1_000_000_000
                <= DulcetAVPlayerEngine.cadenceMaximum
        })

        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func hlsIsRejectedWithoutTouchingThePlaybackResource() async {
        let resource = InMemoryPlaybackResource(data: makePCMWave(duration: 1))
        let engine = DulcetAVPlayerEngine()
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let hlsPlan = plan(resource: resource, deliveryProtocol: .hls)

        let outcome = await execute(engine, .prepare(commandID: .init("hls"), plan: hlsPlan))

        #expect(outcome == .rejected(commandID: .init("hls"), reason: .unsupported))
        #expect(resource.requests.isEmpty)
        #expect(events.snapshot == [
            .failedBeforeStart(attemptID: hlsPlan.attemptID, error: .unsupportedPlan),
        ])
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func replacementAndPreloadedAdvanceUseDifferentBoundaryEventsInOrder() async throws {
        let player = AVQueuePlayer()
        let engine = DulcetAVPlayerEngine(player: player)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let original = plan(session: "session-a", attempt: "attempt-a")
        let replacement = plan(session: "session-a", attempt: "attempt-b")
        let next = plan(session: "session-b", attempt: "attempt-c")

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: original))
        _ = await execute(engine, .replaceCurrent(commandID: .init("replace"), plan: replacement))
        _ = await execute(engine, .preloadNext(commandID: .init("preload"), plan: next))
        player.advanceToNextItem()

        try await waitUntil("AVQueuePlayer did not publish the preloaded session boundary") {
            events.containsAdvance(old: replacement.attemptID, new: next.attemptID)
        }
        let snapshot = events.snapshot
        let replaced = try #require(snapshot.firstIndex(of: .attemptReplaced(
            oldAttemptID: original.attemptID,
            newAttemptID: replacement.attemptID
        )))
        let replacementPreparing = try #require(snapshot.firstIndex(of: .preparing(attemptID: replacement.attemptID)))
        let advanced = try #require(snapshot.firstIndex(of: .advancedToPreloaded(
            oldAttemptID: replacement.attemptID,
            newAttemptID: next.attemptID
        )))
        #expect(replaced < replacementPreparing)
        #expect(replacementPreparing < advanced)

        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func attachingALateListenerImmediatelyResynchronizesTheCurrentAttempt() async throws {
        let engine = DulcetAVPlayerEngine()
        let playbackPlan = plan()
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        let events = PlaybackEventRecorder()

        engine.setEventListener { events.append($0) }

        #expect(events.snapshot.count == 1)
        guard case let .observationResynced(attemptID, snapshot) = events.snapshot[0] else {
            Issue.record("late listener did not receive ObservationResynced first")
            return
        }
        #expect(attemptID == playbackPlan.attemptID)
        #expect(snapshot.status == .preparing || snapshot.status == .ready)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func audioSessionActivatesOncePerSessionAndStopDeactivatesImmediately() async {
        let audioSession = RecordingAudioSession()
        let engine = DulcetAVPlayerEngine(audioSession: audioSession)
        let original = plan(session: "same-session", attempt: "attempt-one")
        let replacement = plan(session: "same-session", attempt: "attempt-two")

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: original))
        _ = await execute(engine, .replaceCurrent(commandID: .init("replace"), plan: replacement))
        #expect(audioSession.activationCount == 1)
        #expect(audioSession.deactivationCount == 0)

        _ = await execute(engine, .stop(commandID: .init("stop")))
        #expect(audioSession.deactivationCount == 1)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func audioSessionActivationFailureIsClosedAndRejectsPreparation() async {
        let audioSession = RecordingAudioSession(failsActivation: true)
        let engine = DulcetAVPlayerEngine(audioSession: audioSession)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan()

        let outcome = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))

        #expect(outcome == .rejected(commandID: .init("prepare"), reason: .failed(.engine)))
        #expect(events.snapshot == [
            .failedBeforeStart(attemptID: playbackPlan.attemptID, error: .engine),
        ])
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func interruptionsAndNoisyRoutesPauseWithExplicitResumptionPolicy() async throws {
        let audioSession = RecordingAudioSession()
        let engine = DulcetAVPlayerEngine(audioSession: audioSession)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        try await waitUntil("AVPlayer did not become ready") { events.containsReady(seekability: .seekable) }
        _ = await execute(engine, .play(commandID: .init("play")))
        try await waitUntil("playback did not progress before interruption") { events.containsProgressBegan }

        audioSession.publish(.interruptionBegan)
        try await waitUntil("interruption begin was not emitted") {
            events.snapshot.contains { if case .interruptionBegan(_, false) = $0 { true } else { false } }
        }
        audioSession.publish(.interruptionEnded(systemAllowsResume: true))
        try await waitUntil("eligible interruption did not resume") {
            events.snapshot.contains { if case .interruptionEnded(_, true) = $0 { true } else { false } }
        }
        try await waitUntil("transport did not resume after the system-authorized interruption") {
            events.snapshot.contains { if case .resumed = $0 { true } else { false } }
        }
        audioSession.publish(.routeChanged(old: .bluetooth, new: .builtIn, becomingNoisy: true))
        try await waitUntil("becoming-noisy route did not pause") {
            events.snapshot.contains {
                if case .routeChanged(_, .bluetooth, .builtIn, true) = $0 { true } else { false }
            }
        }

        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func externalPrimaryPlaybackFinalizesTheCurrentSession() async throws {
        let audioSession = RecordingAudioSession()
        let engine = DulcetAVPlayerEngine(audioSession: audioSession)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan()
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))

        audioSession.publish(.externalPlaybackBegan)

        try await waitUntil("external playback did not finalize the engine session") {
            events.snapshot.contains(.engineTornDown(
                attemptID: playbackPlan.attemptID,
                reason: .systemReclaimed
            ))
        }
        #expect(audioSession.deactivationCount == 1)
        let playOutcome = await execute(engine, .play(commandID: .init("play-after-takeover")))
        #expect(playOutcome == .rejected(commandID: .init("play-after-takeover"), reason: .invalidState))
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func anEmptyQueueDeactivatesAfterTheConfiguredGracePeriod() async throws {
        let player = AVQueuePlayer()
        let audioSession = RecordingAudioSession()
        let engine = DulcetAVPlayerEngine(
            player: player,
            audioSession: audioSession,
            audioSessionGracePeriod: 0.05
        )
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        let item = try #require(player.currentItem)

        NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: item)
        player.removeAllItems()

        try await waitUntil("empty queue did not deactivate after its grace period") {
            audioSession.deactivationCount == 1
        }
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func nowPlayingIsNotPublishedAtPreparingAndBecomesSeekableOnlyAfterReady() async throws {
        let mediaControls = RecordingSystemMediaControls()
        let suspended = SuspendedPlaybackResource()
        let engine = DulcetAVPlayerEngine(systemMediaControls: mediaControls)
        let playbackPlan = plan(resource: suspended, title: "Ordered metadata")

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        #expect(mediaControls.publications.isEmpty)

        suspended.resume(with: makePCMWave(duration: 2))
        try await waitUntil("Ready did not publish Now Playing") {
            mediaControls.publications.count == 1
        }
        let state = try #require(mediaControls.publications.first)
        #expect(state.metadata.title == "Ordered metadata")
        #expect(state.seekability == .seekable)
        #expect(state.sessionID == playbackPlan.playbackSessionID)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func replacementPublishesMetadataAfterTheBoundaryAndBeforePreparingTheNewAttempt() async throws {
        let ordering = PlaybackSystemOrderingRecorder()
        let mediaControls = RecordingSystemMediaControls(ordering: ordering)
        let engine = DulcetAVPlayerEngine(systemMediaControls: mediaControls)
        engine.setEventListener { ordering.append(.event($0)) }
        let original = plan(session: "session", attempt: "original", title: "Original")
        let replacement = plan(session: "session", attempt: "replacement", title: "Replacement")
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: original))
        try await waitUntil("initial Ready did not publish metadata") {
            mediaControls.publications.count == 1
        }
        ordering.reset()

        _ = await execute(engine, .replaceCurrent(commandID: .init("replace"), plan: replacement))

        let snapshot = ordering.snapshot
        #expect(snapshot.count >= 3)
        #expect(snapshot[0] == .event(.attemptReplaced(
            oldAttemptID: original.attemptID,
            newAttemptID: replacement.attemptID
        )))
        #expect(snapshot[1] == .metadata("Replacement"))
        #expect(snapshot[2] == .event(.preparing(attemptID: replacement.attemptID)))
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func remoteCommandsRejectStaleSessionsAndRouteEveryCurrentCommand() async throws {
        let mediaControls = RecordingSystemMediaControls()
        let router = RecordingRemoteCommandRouter()
        let capabilities = DulcetRemoteCommandCapabilities(
            allowsNext: true,
            allowsPrevious: true,
            allowsRating: true,
            allowsFavourite: true
        )
        let engine = DulcetAVPlayerEngine(
            systemMediaControls: mediaControls,
            remoteCommandRouter: router,
            remoteCommandCapabilities: capabilities
        )
        let playbackPlan = plan(session: "current-session", attempt: "current-attempt")
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        try await waitUntil("Ready did not publish command availability") {
            mediaControls.publications.last?.seekability == .seekable
        }

        #expect(!mediaControls.send(.play(sessionID: .init("stale-session"))))
        #expect(mediaControls.send(.pause(sessionID: playbackPlan.playbackSessionID)))
        #expect(mediaControls.send(.toggle(sessionID: playbackPlan.playbackSessionID)))
        #expect(mediaControls.send(.seek(sessionID: playbackPlan.playbackSessionID, position: 0.5)))
        #expect(mediaControls.send(.next(sessionID: playbackPlan.playbackSessionID)))
        #expect(mediaControls.send(.previous(sessionID: playbackPlan.playbackSessionID)))
        #expect(mediaControls.send(.rating(sessionID: playbackPlan.playbackSessionID, value: 4)))
        #expect(mediaControls.send(.favourite(
            sessionID: playbackPlan.playbackSessionID,
            isFavourite: true
        )))
        #expect(router.commands == [
            .pause(sessionID: playbackPlan.playbackSessionID),
            .toggle(sessionID: playbackPlan.playbackSessionID),
            .seek(sessionID: playbackPlan.playbackSessionID, position: 0.5),
            .next(sessionID: playbackPlan.playbackSessionID),
            .previous(sessionID: playbackPlan.playbackSessionID),
            .rating(sessionID: playbackPlan.playbackSessionID, value: 4),
            .favourite(sessionID: playbackPlan.playbackSessionID, isFavourite: true),
        ])
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func mediaPlayerCommandCenterExposesOnlyTheV1CommandSet() {
        let controls = DulcetPlatformSystemMediaControls()
        let center = MPRemoteCommandCenter.shared()
        controls.publish(DulcetSystemNowPlayingState(
            sessionID: .init("session"),
            metadata: .init(title: "Fixture"),
            duration: 60,
            position: 10,
            rate: 1,
            isPlaying: true,
            seekability: .seekable,
            remoteCapabilities: .init(
                allowsNext: true,
                allowsPrevious: true,
                allowsRating: true,
                allowsFavourite: true
            )
        ))

        #expect(center.playCommand.isEnabled)
        #expect(center.pauseCommand.isEnabled)
        #expect(center.togglePlayPauseCommand.isEnabled)
        #expect(center.nextTrackCommand.isEnabled)
        #expect(center.previousTrackCommand.isEnabled)
        #expect(center.changePlaybackPositionCommand.isEnabled)
        #expect(center.ratingCommand.isEnabled)
        #expect(center.likeCommand.isEnabled)
        #expect(!center.stopCommand.isEnabled)
        #expect(!center.changePlaybackRateCommand.isEnabled)
        #expect(!center.changeRepeatModeCommand.isEnabled)
        #expect(!center.changeShuffleModeCommand.isEnabled)
        #expect(!center.skipForwardCommand.isEnabled)
        #expect(!center.skipBackwardCommand.isEnabled)
        #expect(!center.seekForwardCommand.isEnabled)
        #expect(!center.seekBackwardCommand.isEnabled)
        #expect(!center.enableLanguageOptionCommand.isEnabled)
        #expect(!center.disableLanguageOptionCommand.isEnabled)
        controls.clear()
    }

    @Test
    func allTenCommandsProduceExactlyOneCorrelatedOutcome() async throws {
        let engine = DulcetAVPlayerEngine(systemMediaControls: RecordingSystemMediaControls())
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let current = plan(session: "session", attempt: "first")
        let replacement = plan(session: "session", attempt: "second")
        let next = plan(session: "next-session", attempt: "next")

        #expect(await execute(engine, .prepare(commandID: .init("prepare"), plan: current))
            == .accepted(commandID: .init("prepare")))
        try await waitUntil("command fixture did not become ready") {
            events.containsReady(seekability: .seekable)
        }
        #expect(await execute(engine, .play(commandID: .init("play")))
            == .accepted(commandID: .init("play")))
        #expect(await execute(engine, .pause(commandID: .init("pause")))
            == .completed(commandID: .init("pause"), result: .withoutData))
        #expect(await execute(engine, .seek(commandID: .init("seek"), position: 0.25))
            == .completed(commandID: .init("seek"), result: .withoutData))
        #expect(await execute(engine, .setVolume(commandID: .init("volume"), volume: 0.5))
            == .completed(commandID: .init("volume"), result: .withoutData))
        #expect(await execute(engine, .setRate(commandID: .init("rate"), rate: 1.25))
            == .completed(commandID: .init("rate"), result: .withoutData))
        #expect(await execute(engine, .preloadNext(commandID: .init("preload"), plan: next))
            == .accepted(commandID: .init("preload")))
        #expect(await execute(engine, .replaceCurrent(commandID: .init("replace"), plan: replacement))
            == .accepted(commandID: .init("replace")))
        #expect(await execute(engine, .stop(commandID: .init("stop")))
            == .completed(commandID: .init("stop"), result: .withoutData))
        #expect(await execute(engine, .release(commandID: .init("release")))
            == .completed(commandID: .init("release"), result: .withoutData))
        #expect(await execute(engine, .play(commandID: .init("after-release")))
            == .rejected(commandID: .init("after-release"), reason: .engineReleased))
    }

    @Test
    func sourceExpiryRequestsCoreRefreshWithoutAnAdapterRetry() async throws {
        let resource = FailingPlaybackResource(
            error: .authentication,
            refreshReason: .unauthorized
        )
        let engine = DulcetAVPlayerEngine(systemMediaControls: RecordingSystemMediaControls())
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(resource: resource)

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))

        try await waitUntil("401 did not request a source refresh") {
            events.snapshot.contains(.sourceRefreshRequired(
                attemptID: playbackPlan.attemptID,
                reason: .unauthorized
            ))
        }
        #expect(resource.requestCount == 1)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func credentialBearingFoundationErrorsCollapseToClosedFailures() {
        let usernameCanary = "foundation-user-canary"
        let tokenCanary = "foundation-token-canary"
        let url = URL(
            string: "https://source.invalid/audio?u=\(usernameCanary)&t=\(tokenCanary)"
        )!
        let error = NSError(
            domain: NSURLErrorDomain,
            code: URLError.secureConnectionFailed.rawValue,
            userInfo: [
                NSURLErrorFailingURLErrorKey: url,
                NSURLErrorFailingURLStringErrorKey: url.absoluteString,
            ]
        )

        let avFailure = DulcetApplePlaybackErrorSanitizer.avFoundationFailure(error)
        let sessionFailure = DulcetApplePlaybackErrorSanitizer.urlSessionFailure(error)

        #expect(avFailure == .tlsUntrusted)
        #expect(sessionFailure == .tlsUntrusted)
        let surfaced = String(reflecting: [avFailure, sessionFailure])
        #expect(!surfaced.contains(usernameCanary))
        #expect(!surfaced.contains(tokenCanary))
        #expect(!surfaced.contains("?"))
    }

    #if os(macOS)
    @Test
    func macCoreAudioRoutesClassifyNoisyDisconnectsWithoutTreatingHDMIAsAnInterruption() {
        #expect(DulcetMacAudioRouteClassifier.kind(
            transportType: kAudioDeviceTransportTypeBuiltIn
        ) == .builtIn)
        #expect(DulcetMacAudioRouteClassifier.kind(
            transportType: kAudioDeviceTransportTypeUSB
        ) == .wired)
        #expect(DulcetMacAudioRouteClassifier.kind(
            transportType: kAudioDeviceTransportTypeBluetooth
        ) == .bluetooth)
        #expect(DulcetMacAudioRouteClassifier.kind(
            transportType: kAudioDeviceTransportTypeHDMI
        ) == .hdmi)
        #expect(DulcetMacAudioRouteClassifier.kind(
            transportType: kAudioDeviceTransportTypeAirPlay
        ) == .remote)
        #expect(DulcetMacAudioRouteClassifier.becomingNoisy(old: .wired, new: .builtIn))
        #expect(DulcetMacAudioRouteClassifier.becomingNoisy(old: .bluetooth, new: .builtIn))
        #expect(!DulcetMacAudioRouteClassifier.becomingNoisy(old: .hdmi, new: .builtIn))
    }
    #endif
}

private func execute(
    _ engine: DulcetAVPlayerEngine,
    _ command: DulcetPlaybackCommand
) async -> DulcetPlaybackCommandOutcome {
    await withCheckedContinuation { continuation in
        engine.execute(command) { continuation.resume(returning: $0) }
    }
}

// 20 seconds, not 2. AVFoundation has to spin up, resolve a custom-scheme asset through the
// resource-loader delegate and reach readyToPlay; two seconds is a local-machine figure. On the
// hosted runner these tests failed at 2.388s -- the deadline plus overhead -- while passing locally,
// which is the signature of a budget that is too tight rather than of behaviour that is absent.
//
// The deadline still has teeth: if the loader is never invoked at all, the condition never becomes
// true and the expectation fails at 20 seconds instead of 2. Waiting longer costs a slow test only
// when something is already broken.
private func waitUntil(
    _ failure: String,
    timeout: TimeInterval = 20,
    condition: @escaping @Sendable () -> Bool
) async throws {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: .seconds(timeout))
    while !condition(), clock.now < deadline {
        try await Task.sleep(for: .milliseconds(20))
    }
    #expect(condition(), Comment(rawValue: failure))
}

private func plan(
    resource: any DulcetPlaybackResourceLoading = InMemoryPlaybackResource(
        data: makePCMWave(duration: 2)
    ),
    deliveryProtocol: DulcetPlaybackDeliveryProtocol = .httpProgressive,
    session: String = "session",
    attempt: String = "attempt",
    title: String = "Playback fixture"
) -> DulcetPlaybackPlan {
    DulcetPlaybackPlan(
        playbackSessionID: .init(session),
        attemptID: .init(attempt),
        deliveryProtocol: deliveryProtocol,
        expectedContainer: .wav,
        resource: resource,
        metadata: .init(title: title, artist: "Dulcet Tests")
    )
}

private final class SuspendedPlaybackResource: DulcetPlaybackResourceLoading, @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [(
        DulcetPlaybackResourceLoadRequest,
        @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    )] = []
    private var data: Data?

    var description: String { "SuspendedPlaybackResource(<redacted>)" }

    func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        lock.lock()
        if let data {
            lock.unlock()
            complete(request, from: data, completion: completion)
        } else {
            pending.append((request, completion))
            lock.unlock()
        }
        return InMemoryPlaybackOperation()
    }

    func resume(with data: Data) {
        lock.lock()
        self.data = data
        let pending = pending
        self.pending.removeAll()
        lock.unlock()
        pending.forEach { request, completion in
            complete(request, from: data, completion: completion)
        }
    }

    private func complete(
        _ request: DulcetPlaybackResourceLoadRequest,
        from data: Data,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) {
        let start = min(Int(request.range.start), data.count)
        let end = min(Int(request.range.endInclusive) + 1, data.count)
        completion(.loaded(
            data: start < end ? data.subdata(in: start..<end) : Data(),
            contentInformation: .init(
                contentLength: Int64(data.count),
                supportsByteRanges: true
            )
        ))
    }
}

private final class FailingPlaybackResource: DulcetPlaybackResourceLoading, @unchecked Sendable {
    private let error: DulcetPlaybackFailure
    private let refreshReason: DulcetPlaybackSourceRefreshReason?
    private let lock = NSLock()
    private var requests = 0

    init(error: DulcetPlaybackFailure, refreshReason: DulcetPlaybackSourceRefreshReason?) {
        self.error = error
        self.refreshReason = refreshReason
    }

    var description: String { "FailingPlaybackResource(<redacted>)" }

    var requestCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }

    func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        lock.lock()
        requests += 1
        lock.unlock()
        completion(.failed(error: error, refreshReason: refreshReason))
        return InMemoryPlaybackOperation()
    }
}

private final class RecordingSystemMediaControls: DulcetSystemMediaControlling,
    @unchecked Sendable {
    private let lock = NSLock()
    private let ordering: PlaybackSystemOrderingRecorder?
    private var handler: (@Sendable (DulcetRemotePlaybackCommand) -> Bool)?
    private var publicationStorage: [DulcetSystemNowPlayingState] = []
    private var transportStorage: [(DulcetPlaybackSessionID, TimeInterval, Double, Bool)] = []

    init(ordering: PlaybackSystemOrderingRecorder? = nil) {
        self.ordering = ordering
    }

    var publications: [DulcetSystemNowPlayingState] {
        lock.lock()
        defer { lock.unlock() }
        return publicationStorage
    }

    func setCommandHandler(
        _ handler: (@Sendable (DulcetRemotePlaybackCommand) -> Bool)?
    ) {
        lock.lock()
        self.handler = handler
        lock.unlock()
    }

    func publish(_ state: DulcetSystemNowPlayingState) {
        lock.lock()
        publicationStorage.append(state)
        lock.unlock()
        ordering?.append(.metadata(state.metadata.title))
    }

    func updateTransport(
        sessionID: DulcetPlaybackSessionID,
        position: TimeInterval,
        rate: Double,
        isPlaying: Bool
    ) {
        lock.lock()
        transportStorage.append((sessionID, position, rate, isPlaying))
        lock.unlock()
    }

    func clear() {}

    func send(_ command: DulcetRemotePlaybackCommand) -> Bool {
        lock.lock()
        let handler = handler
        lock.unlock()
        return handler?(command) ?? false
    }
}

private final class RecordingRemoteCommandRouter: DulcetRemotePlaybackCommandRouting,
    @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [DulcetRemotePlaybackCommand] = []

    var commands: [DulcetRemotePlaybackCommand] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    func handleRemotePlaybackCommand(_ command: DulcetRemotePlaybackCommand) -> Bool {
        lock.lock()
        storage.append(command)
        lock.unlock()
        return true
    }
}

private enum PlaybackSystemOrderingEntry: Equatable, Sendable {
    case event(DulcetPlaybackEvent)
    case metadata(String)
}

private final class PlaybackSystemOrderingRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [PlaybackSystemOrderingEntry] = []

    var snapshot: [PlaybackSystemOrderingEntry] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    func append(_ entry: PlaybackSystemOrderingEntry) {
        lock.lock()
        storage.append(entry)
        lock.unlock()
    }

    func reset() {
        lock.lock()
        storage.removeAll()
        lock.unlock()
    }
}

private final class InMemoryPlaybackResource: DulcetPlaybackResourceLoading, @unchecked Sendable {
    private let data: Data
    private let lock = NSLock()
    private var storage: [DulcetPlaybackResourceLoadRequest] = []

    init(data: Data) {
        self.data = data
    }

    var description: String { "InMemoryPlaybackResource(<redacted>)" }

    var requests: [DulcetPlaybackResourceLoadRequest] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        lock.lock()
        storage.append(request)
        lock.unlock()
        let start = min(Int(request.range.start), data.count)
        let end = min(Int(request.range.endInclusive) + 1, data.count)
        let body = start < end ? data.subdata(in: start..<end) : Data()
        completion(.loaded(
            data: body,
            contentInformation: .init(
                contentLength: Int64(data.count),
                supportsByteRanges: true
            )
        ))
        return InMemoryPlaybackOperation()
    }
}

private final class InMemoryPlaybackOperation: DulcetPlaybackResourceLoadOperation,
    @unchecked Sendable {
    func cancel() {}
}

private final class RecordingAudioSession: DulcetAudioSessionManaging, @unchecked Sendable {
    private let lock = NSLock()
    private let failsActivation: Bool
    private var handler: (@Sendable (DulcetAudioSessionEvent) -> Void)?
    private var activations = 0
    private var deactivations = 0

    init(failsActivation: Bool = false) {
        self.failsActivation = failsActivation
    }

    var activationCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return activations
    }

    var deactivationCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return deactivations
    }

    func setEventHandler(_ handler: (@Sendable (DulcetAudioSessionEvent) -> Void)?) {
        lock.lock()
        self.handler = handler
        lock.unlock()
    }

    func activate() throws {
        lock.lock()
        activations += 1
        lock.unlock()
        if failsActivation { throw DulcetPlaybackFailure.engine }
    }

    func deactivate() {
        lock.lock()
        deactivations += 1
        lock.unlock()
    }

    func publish(_ event: DulcetAudioSessionEvent) {
        lock.lock()
        let handler = handler
        lock.unlock()
        handler?(event)
    }
}

private final class PlaybackEventRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [DulcetPlaybackEvent] = []

    func append(_ event: DulcetPlaybackEvent) {
        lock.lock()
        storage.append(event)
        lock.unlock()
    }

    var snapshot: [DulcetPlaybackEvent] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    var containsProgressBegan: Bool {
        snapshot.contains { if case .playbackProgressBegan = $0 { true } else { false } }
    }

    func containsReady(seekability: DulcetPlaybackSeekability) -> Bool {
        snapshot.contains {
            if case let .ready(_, _, actual) = $0 { actual == seekability } else { false }
        }
    }

    func containsAdvance(old: DulcetPlaybackAttemptID, new: DulcetPlaybackAttemptID) -> Bool {
        snapshot.contains { $0 == .advancedToPreloaded(oldAttemptID: old, newAttemptID: new) }
    }

    var positionSamples: [(position: TimeInterval, monotonic: DulcetMonotonicInstant)] {
        snapshot.compactMap {
            if case let .positionChanged(_, position, monotonic) = $0 {
                (position, monotonic)
            } else {
                nil
            }
        }
    }
}

private func makePCMWave(duration: TimeInterval) -> Data {
    let sampleRate = 8_000
    let sampleCount = Int(duration * Double(sampleRate))
    let payloadLength = sampleCount * MemoryLayout<Int16>.size
    var bytes: [UInt8] = []
    func ascii(_ value: String) { bytes.append(contentsOf: value.utf8) }
    func littleEndian<T: FixedWidthInteger>(_ value: T) {
        var encoded = value.littleEndian
        withUnsafeBytes(of: &encoded) { bytes.append(contentsOf: $0) }
    }
    ascii("RIFF")
    littleEndian(UInt32(36 + payloadLength))
    ascii("WAVEfmt ")
    littleEndian(UInt32(16))
    littleEndian(UInt16(1))
    littleEndian(UInt16(1))
    littleEndian(UInt32(sampleRate))
    littleEndian(UInt32(sampleRate * 2))
    littleEndian(UInt16(2))
    littleEndian(UInt16(16))
    ascii("data")
    littleEndian(UInt32(payloadLength))
    bytes.append(contentsOf: repeatElement(0, count: payloadLength))
    return Data(bytes)
}
