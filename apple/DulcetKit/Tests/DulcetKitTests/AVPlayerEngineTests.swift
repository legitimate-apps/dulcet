import AVFoundation
import Foundation
import Testing
@testable import DulcetKit

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
        #expect(resource.requests.count > 0)
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
}

private func execute(
    _ engine: DulcetAVPlayerEngine,
    _ command: DulcetPlaybackCommand
) async -> DulcetPlaybackCommandOutcome {
    await withCheckedContinuation { continuation in
        engine.execute(command) { continuation.resume(returning: $0) }
    }
}

private func waitUntil(
    _ failure: String,
    timeout: TimeInterval = 2,
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
    resource: InMemoryPlaybackResource = InMemoryPlaybackResource(data: makePCMWave(duration: 2)),
    deliveryProtocol: DulcetPlaybackDeliveryProtocol = .httpProgressive,
    session: String = "session",
    attempt: String = "attempt"
) -> DulcetPlaybackPlan {
    DulcetPlaybackPlan(
        playbackSessionID: .init(session),
        attemptID: .init(attempt),
        deliveryProtocol: deliveryProtocol,
        expectedContainer: .wav,
        resource: resource,
        metadata: .init(title: "Playback fixture", artist: "Dulcet Tests")
    )
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
