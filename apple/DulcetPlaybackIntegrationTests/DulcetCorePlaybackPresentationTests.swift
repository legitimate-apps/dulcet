import DulcetCore
import DulcetKit
import XCTest

@MainActor
final class DulcetCorePlaybackPresentationTests: XCTestCase {
    func testEngineFailureAfterPlayingReachesPresentationStore() async throws {
        let track = DulcetTrack(
            id: DulcetProviderItemID(providerInstanceID: "failure-provider", rawID: "failure-track"),
            title: "Failure fixture",
            credits: [],
            albumTitle: "Failure album",
            duration: .seconds(120),
            mediaSourceID: nil,
            artwork: DulcetArtwork(seed: "failure-track", palette: .indigoCoral)
        )
        let queue = ApplePlaybackQueueClient(databaseName: ":memory:")
        defer { queue.close() }
        let started = queue.replaceAndStart(request: ApplePlaybackQueueRequestDto(
            items: [ApplePlaybackQueueItemDto(
                providerInstanceId: track.id.providerInstanceID,
                rawId: track.id.rawID,
                durationMilliseconds: 120_000
            )],
            sourceKind: "album",
            sourceRawId: "failure-album",
            sourceDisplayName: "Failure album",
            startIndex: 0,
            shuffle: false
        ))
        XCTAssertNil(started.errorKind)
        let session = try XCTUnwrap(started.snapshot?.currentSession)
        let attemptID = DulcetPlaybackAttemptID(session.attemptId)
        let engine = ControlledEventEngine()
        // The controller records events into this same queue, with its track in the catalog.
        let controller = DulcetCorePlaybackController(
            queueClient: queue, engine: engine, catalog: [track]
        )
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: UnusedAccountConnector(), playbackController: controller
        ))
        store.selectDestination(.nowPlaying)

        // Deliver each event through the listener registered by the production initializer.
        XCTAssertTrue(engine.emit(.ready(attemptID: attemptID, duration: 120, seekability: .seekable)))
        await waitForPresentation { controller.currentPresentation.nowPlaying?.phase == .ready }
        XCTAssertEqual(controller.currentPresentation.nowPlaying?.phase, .ready)
        XCTAssertTrue(engine.emit(.playbackProgressBegan(
            attemptID: attemptID,
            wallClock: Date(timeIntervalSince1970: 1),
            mediaPosition: 1
        )))
        await waitForPresentation { store.snapshot.nowPlaying?.isPlaying == true }
        let playing = try XCTUnwrap(controller.currentPresentation.nowPlaying)
        XCTAssertEqual(controller.currentPresentation.status, .ready)
        XCTAssertEqual(playing.current, track)
        XCTAssertEqual(playing.queue, [track])
        XCTAssertEqual(playing.sessionID?.rawValue, session.playbackSessionId)
        XCTAssertEqual(playing.phase, .progressing)
        XCTAssertTrue(playing.isPlaying)
        XCTAssertTrue(playing.progressBegan)
        XCTAssertEqual(store.snapshot.state, .nowPlaying)
        XCTAssertEqual(store.snapshot.nowPlaying, playing)

        XCTAssertTrue(engine.emit(.failedAfterPartial(
            attemptID: attemptID, position: 2, error: .transport
        )))
        await waitForPresentation { store.snapshot.state == .nowPlayingFailed }
        let recorded = queue.snapshot()
        XCTAssertNil(recorded.errorKind)
        let failedSession = try XCTUnwrap(recorded.snapshot?.currentSession)
        XCTAssertEqual(failedSession.phase, "Failed")
        XCTAssertEqual(failedSession.positionMilliseconds, 2_000)
        XCTAssertEqual(failedSession.playbackSessionId, session.playbackSessionId)
        XCTAssertEqual(failedSession.attemptId, session.attemptId)
        XCTAssertEqual(failedSession.queueEntryId, session.queueEntryId)
        XCTAssertEqual(controller.currentPresentation.status, .failed)
        XCTAssertNil(controller.currentPresentation.nowPlaying)
        XCTAssertEqual(store.snapshot.state, .nowPlayingFailed)
        XCTAssertNil(store.snapshot.nowPlaying)
    }

    /// Disconnect must survive the stop it issued itself.
    ///
    /// `disconnect()` calls `execute(.stop(...))` and then publishes `.unavailable`. The engine's
    /// stop implementation emits `.skipped` (DulcetAVPlayerEngine.swift, `stop(reason:)`), the
    /// event listener installed by the production initializer wraps every event in
    /// `Task { @MainActor }`, and the core maps `Skipped` to phase `Stopped` by COPYING the
    /// current session rather than retiring it -- so the queued event arrives after
    /// `.unavailable`, carrying a live `currentSession`, and used to republish `.preparing`.
    ///
    /// The result was a spinner with no server behind it: the account is gone, the wire client is
    /// closed, and nothing will ever arrive to resolve it.
    ///
    /// The fixture engine emits on `.stop` exactly as the production engine does, so the ordering
    /// under test is the production ordering and not one the test arranged.
    func testDisconnectIsNotOverwrittenByTheStopItIssued() async throws {
        let track = DulcetTrack(
            id: DulcetProviderItemID(providerInstanceID: "stop-provider", rawID: "stop-track"),
            title: "Stop fixture",
            credits: [],
            albumTitle: "Stop album",
            duration: .seconds(120),
            mediaSourceID: nil,
            artwork: DulcetArtwork(seed: "stop-track", palette: .indigoCoral)
        )
        let queue = ApplePlaybackQueueClient(databaseName: ":memory:")
        defer { queue.close() }
        let started = queue.replaceAndStart(request: ApplePlaybackQueueRequestDto(
            items: [ApplePlaybackQueueItemDto(
                providerInstanceId: track.id.providerInstanceID,
                rawId: track.id.rawID,
                durationMilliseconds: 120_000
            )],
            sourceKind: "album",
            sourceRawId: "stop-album",
            sourceDisplayName: "Stop album",
            startIndex: 0,
            shuffle: false
        ))
        XCTAssertNil(started.errorKind)
        let session = try XCTUnwrap(started.snapshot?.currentSession)
        let attemptID = DulcetPlaybackAttemptID(session.attemptId)
        let engine = StoppingEngine(attemptID: attemptID)
        let controller = DulcetCorePlaybackController(
            queueClient: queue, engine: engine, catalog: [track]
        )
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: UnusedAccountConnector(), playbackController: controller
        ))
        store.selectDestination(.nowPlaying)

        XCTAssertTrue(engine.emit(.ready(attemptID: attemptID, duration: 120, seekability: .seekable)))
        XCTAssertTrue(engine.emit(.playbackProgressBegan(
            attemptID: attemptID,
            wallClock: Date(timeIntervalSince1970: 1),
            mediaPosition: 1
        )))
        await waitForPresentation { store.snapshot.nowPlaying?.isPlaying == true }
        XCTAssertEqual(controller.currentPresentation.status, .ready)

        controller.disconnect()
        XCTAssertEqual(engine.stopCount, 1, "disconnect must issue exactly one stop")
        // The stop's own event is queued on the main actor behind this point. Drain it, then
        // require that what it published did not contradict the disconnect.
        await waitForPresentation { queue.snapshot().snapshot?.currentSession?.phase == "Stopped" }
        XCTAssertEqual(
            queue.snapshot().snapshot?.currentSession?.phase,
            "Stopped",
            "the stop must actually reach the core as Stopped, or this test proves nothing:"
                + " receiveEngineEvent calls publish synchronously right after record, so this"
                + " reading Stopped is what establishes that the racing publication has landed"
        )

        XCTAssertEqual(
            controller.currentPresentation.status,
            .unavailable,
            "a disconnected account must not present a spinner"
        )
        XCTAssertNil(controller.currentPresentation.nowPlaying)
        XCTAssertEqual(store.snapshot.state, .nowPlayingUnavailable)
    }

    /// Stopped is not preparing, and neither is TornDown.
    ///
    /// Both used to fall into the same `else` as `Created` and `Preparing`. A spinner is right for
    /// two of those four and is unresolvable for the other two, because nothing further is coming.
    func testStoppedAndTornDownAreNotPresentedAsPreparing() throws {
        let queue = ApplePlaybackQueueClient(databaseName: ":memory:")
        defer { queue.close() }
        let started = queue.replaceAndStart(request: ApplePlaybackQueueRequestDto(
            items: [ApplePlaybackQueueItemDto(
                providerInstanceId: "phase-provider",
                rawId: "phase-track",
                durationMilliseconds: 120_000
            )],
            sourceKind: "album",
            sourceRawId: "phase-album",
            sourceDisplayName: "Phase album",
            startIndex: 0,
            shuffle: false
        ))
        XCTAssertNil(started.errorKind)
        let attemptID = try XCTUnwrap(started.startDirective?.attemptId)
        XCTAssertNil(queue.recordReady(
            attemptId: attemptID, durationMilliseconds: 120_000, seekability: "seekable"
        ).errorKind)
        let stopped = queue.recordSkipped(
            attemptId: attemptID, positionMilliseconds: 3_000, reason: "user"
        )
        XCTAssertNil(stopped.errorKind)
        XCTAssertEqual(
            try XCTUnwrap(stopped.snapshot?.currentSession).phase,
            "Stopped",
            "a skip must leave the session current and Stopped, or this proves nothing"
        )

        let controller = DulcetCorePlaybackController(databaseName: ":memory:")
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: UnusedAccountConnector(), playbackController: controller
        ))
        store.selectDestination(.nowPlaying)
        // A preparing phase still prepares -- the fix narrows the bucket, it does not empty it.
        controller.publish(started)
        XCTAssertEqual(controller.currentPresentation.status, .preparing)
        XCTAssertEqual(store.snapshot.state, .nowPlayingPreparing)

        controller.publish(stopped)
        XCTAssertEqual(controller.currentPresentation.status, .unavailable)
        XCTAssertNil(controller.currentPresentation.nowPlaying)
        XCTAssertEqual(store.snapshot.state, .nowPlayingUnavailable)
    }

    private func waitForPresentation(_ predicate: @MainActor () -> Bool) async {
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        while !predicate(), ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    func testFailedAfterPartialPublishesFailureThroughPresentationStore() throws {
        let queue = ApplePlaybackQueueClient(databaseName: ":memory:")
        defer { queue.close() }
        let started = queue.replaceAndStart(request: ApplePlaybackQueueRequestDto(
            items: [ApplePlaybackQueueItemDto(
                providerInstanceId: "failure-provider",
                rawId: "failure-track",
                durationMilliseconds: 120_000
            )],
            sourceKind: "album",
            sourceRawId: "failure-album",
            sourceDisplayName: "Failure fixture",
            startIndex: 0,
            shuffle: false
        ))
        XCTAssertNil(started.errorKind)
        let attemptID = try XCTUnwrap(started.startDirective?.attemptId)
        let ready = queue.recordReady(
            attemptId: attemptID,
            durationMilliseconds: 120_000,
            seekability: "seekable"
        )
        XCTAssertNil(ready.errorKind)
        let progressing = queue.recordPlaybackProgressBegan(
            attemptId: attemptID,
            wallClockEpochMilliseconds: 1_000,
            mediaPositionMilliseconds: 1_000
        )
        XCTAssertNil(progressing.errorKind)
        XCTAssertEqual(progressing.snapshot?.currentSession?.phase, "Progressing")
        let failed = queue.recordFailedAfterPartial(
            attemptId: attemptID,
            positionMilliseconds: 2_000,
            errorKind: "transport"
        )
        // Recording an engine failure succeeds; the session, not the transition, failed.
        XCTAssertNil(failed.errorKind)
        XCTAssertEqual(try XCTUnwrap(failed.snapshot?.currentSession).phase, "Failed")

        let controller = DulcetCorePlaybackController(databaseName: ":memory:")
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: UnusedAccountConnector(),
            playbackController: controller
        ))
        store.selectDestination(.nowPlaying)
        controller.publish(started)
        XCTAssertEqual(controller.currentPresentation.status, .preparing)
        XCTAssertEqual(store.snapshot.state, .nowPlayingPreparing)

        // A known phase with a missing catalog item retains its preparing fallback.
        controller.publish(progressing)
        XCTAssertEqual(controller.currentPresentation.status, .preparing)
        controller.publish(failed)
        XCTAssertEqual(controller.currentPresentation.status, .failed)
        XCTAssertNil(controller.currentPresentation.nowPlaying)
        XCTAssertEqual(store.snapshot.state, .nowPlayingFailed)
        XCTAssertNil(store.snapshot.nowPlaying)
    }
}

@MainActor
private final class UnusedAccountConnector: DulcetAccountConnecting {
    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        fatalError("Playback presentation tests must not connect to a server")
    }
}

// Only the engine event source is controlled; recording and publication remain production code.
private final class ControlledEventEngine: DulcetCorePlaybackEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var listener: DulcetPlaybackEventHandler?

    func setEventListener(_ listener: DulcetPlaybackEventHandler?) {
        lock.withLock { self.listener = listener }
    }

    func emit(_ event: DulcetPlaybackEvent) -> Bool {
        guard let callback = lock.withLock({ listener }) else { return false }
        callback(event)
        return true
    }

    func execute(
        _ command: DulcetPlaybackCommand,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) {
        XCTFail("Event-delivery fixture unexpectedly executed an engine command")
    }

    func setRemoteCommandRouter(_ router: (any DulcetRemotePlaybackCommandRouting)?) {}

    func updateRemoteCommandCapabilities(
        _ capabilities: DulcetRemoteCommandCapabilities,
        for sessionID: DulcetPlaybackSessionID
    ) -> Bool { true }
}

/// Mirrors DulcetAVPlayerEngine.stop(reason:): a stop emits `.skipped` for the active attempt,
/// synchronously, before the command completes. The ordering under test comes from production.
private final class StoppingEngine: DulcetCorePlaybackEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var listener: DulcetPlaybackEventHandler?
    private let attemptID: DulcetPlaybackAttemptID
    private var stops = 0

    init(attemptID: DulcetPlaybackAttemptID) { self.attemptID = attemptID }

    var stopCount: Int { lock.withLock { stops } }

    func setEventListener(_ listener: DulcetPlaybackEventHandler?) {
        lock.withLock { self.listener = listener }
    }

    @discardableResult
    func emit(_ event: DulcetPlaybackEvent) -> Bool {
        guard let callback = lock.withLock({ listener }) else { return false }
        callback(event)
        return true
    }

    func execute(
        _ command: DulcetPlaybackCommand,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) {
        guard case let .stop(commandID) = command else {
            XCTFail("The disconnect fixture received a command other than stop")
            return
        }
        lock.withLock { stops += 1 }
        // DulcetAVPlayerEngine emits before it completes, and with reason `.user`.
        emit(.skipped(attemptID: attemptID, position: 3, reason: .user))
        completion(.completed(commandID: commandID, result: .withoutData))
    }

    func setRemoteCommandRouter(_ router: (any DulcetRemotePlaybackCommandRouting)?) {}

    func updateRemoteCommandCapabilities(
        _ capabilities: DulcetRemoteCommandCapabilities,
        for sessionID: DulcetPlaybackSessionID
    ) -> Bool { true }
}
