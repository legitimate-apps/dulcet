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
        XCTAssertEqual(failedSession.playbackSessionId, session.playbackSessionId)
        XCTAssertEqual(failedSession.attemptId, session.attemptId)
        XCTAssertEqual(failedSession.queueEntryId, session.queueEntryId)
        XCTAssertEqual(controller.currentPresentation.status, .failed)
        XCTAssertNil(controller.currentPresentation.nowPlaying)
        XCTAssertEqual(store.snapshot.state, .nowPlayingFailed)
        XCTAssertNil(store.snapshot.nowPlaying)
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
