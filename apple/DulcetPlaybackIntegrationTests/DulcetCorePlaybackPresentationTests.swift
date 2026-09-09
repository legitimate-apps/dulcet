import DulcetCore
import DulcetKit
import XCTest

@MainActor
final class DulcetCorePlaybackPresentationTests: XCTestCase {
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
