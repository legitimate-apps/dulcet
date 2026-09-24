import DulcetCore
import DulcetKit
import XCTest

/// The production controller driving a recording engine: gapless preload wiring (spec §12.8),
/// the end-of-queue presentation (§14.3), queue editing (§14.1-14.2) and Now Playing artwork.
/// Recording, the core queue, resolution and publication are all production code; only the engine
/// is a fixture, and it emits nothing the test does not name.
@MainActor
final class DulcetCorePlaybackSystemTests: XCTestCase {
    /// Unique per test: `":memory:"` does not isolate queue databases between the tests in this
    /// process, so a fixed provider would let one test's queue decide what another test sees.
    private let provider = "system-provider-\(UUID().uuidString)"

    func testPreloadIsRegisteredAfterProgressAndTheBoundaryIsNotAStopOrAnEmptyScreen() async throws {
        let fixture = makeFixture(tracks: ["a", "b", "c"], recordStatuses: true)
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        XCTAssertTrue(fixture.engine.commands.contains { $0.kind == "preload" } == false,
                      "nothing is preloaded before current playback is established")

        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let preload = try await fixture.waitForPreload(rawID: "b")
        let stopsBeforeBoundary = fixture.engine.count("stop")
        let preparesBeforeBoundary = fixture.engine.count("prepare")
        fixture.statuses.removeAll()

        // The engine reports the natural end, then takes over with the preloaded item.
        fixture.emit(.ready(attemptID: preload.attempt, duration: 120, seekability: .seekable))
        fixture.emit(.endedNaturally(attemptID: first, finalPosition: 120))
        fixture.emit(.advancedToPreloaded(oldAttemptID: first, newAttemptID: preload.attempt))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.current.id.rawID == "b" }

        XCTAssertEqual(fixture.engine.count("stop"), stopsBeforeBoundary, "a gapless boundary issues no stop")
        XCTAssertEqual(fixture.engine.count("prepare"), preparesBeforeBoundary, "and no fresh prepare")
        XCTAssertFalse(fixture.statuses.contains(.unavailable),
                       "the boundary must not flash 'Nothing is playing' between two tracks")
        XCTAssertFalse(fixture.statuses.contains(.preparing), "nor a spinner")
        let nowPlaying = try XCTUnwrap(fixture.controller.currentPresentation.nowPlaying)
        XCTAssertEqual(nowPlaying.sessionID?.rawValue, preload.session)
        XCTAssertEqual(nowPlaying.currentEntryIndex, 1)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 1)

        // The incoming session progresses on its own attempt and then preloads ITS successor.
        fixture.emit(.playbackProgressBegan(attemptID: preload.attempt, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        _ = try await fixture.waitForPreload(rawID: "c")
        XCTAssertEqual(fixture.controller.preloadLog.filter { $0 == "advanced" }.count, 1)
    }

    func testAnEditThatChangesWhatPlaysNextRemovesTheEnginePreloadAndPreloadsTheNewNext() async throws {
        let fixture = makeFixture(tracks: ["a", "b", "c"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let preloadB = try await fixture.waitForPreload(rawID: "b")

        // Through the presentation store, the route the UI uses.
        let entries = try XCTUnwrap(fixture.controller.currentPresentation.nowPlaying?.queueEntries)
        XCTAssertEqual(entries.map(\.track.id.rawID), ["a", "b", "c"])
        try XCTUnwrap(fixture.store).editQueue(.move(entries[2].id, toIndex: 1))

        await fixture.waitFor {
            fixture.engine.commands.contains { $0.kind == "discard" && $0.attempt == preloadB.attempt.rawValue }
        }
        XCTAssertEqual(
            fixture.controller.currentPresentation.nowPlaying?.queueEntries.map(\.track.id.rawID),
            ["a", "c", "b"]
        )
        let preloadC = try await fixture.waitForPreload(rawID: "c")
        XCTAssertNotEqual(preloadC.attempt, preloadB.attempt)

        // An edit that does not change what plays next leaves the preload alone.
        let discardsBefore = fixture.engine.count("discard")
        fixture.controller.edit(.playLater(fixture.addition(["b"])))
        await fixture.waitFor {
            fixture.controller.currentPresentation.nowPlaying?.queueEntries.count == 4
        }
        XCTAssertEqual(fixture.engine.count("discard"), discardsBefore)
    }

    func testAPreloadServerBusyHonoursRetryAfterAndTheBoundaryStartsFresh() async throws {
        let fixture = makeFixture(tracks: ["a", "b", "c"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let preload = try await fixture.waitForPreload(rawID: "b")

        fixture.emit(.failedBeforeStart(attemptID: preload.attempt, error: .serverBusy(retryAfter: 30)))
        await fixture.waitFor {
            fixture.engine.commands.contains { $0.kind == "discard" && $0.attempt == preload.attempt.rawValue }
        }
        XCTAssertTrue(fixture.controller.preloadLog.contains("busy"))
        XCTAssertEqual(
            fixture.controller.currentPresentation.nowPlaying?.current.id.rawID,
            "a",
            "a failed preload must not reach the current session"
        )
        XCTAssertEqual(fixture.controller.currentPresentation.status, .ready)

        fixture.emit(.endedNaturally(attemptID: first, finalPosition: 120))
        let fresh = try await fixture.waitForPrepare(rawID: "b")
        XCTAssertNotEqual(fresh, preload.attempt, "the boundary starts b fresh, as a new attempt")

        // Retry-After is honoured: b's progress does not trigger a preload inside the 30 s window.
        let preloadsBefore = fixture.engine.count("preload")
        fixture.emit(.ready(attemptID: fresh, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: fresh, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(fixture.engine.count("preload"), preloadsBefore)
    }

    /// The engine held the preload, the current item ended, and then the preload FAILED -- so
    /// no AdvancedToPreloaded is coming. The next entry must start fresh, not leave silence under
    /// a "playing" presentation.
    func testAPreloadFailingAtTheBoundaryStartsTheNextEntryFresh() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let preload = try await fixture.waitForPreload(rawID: "b")
        await fixture.waitFor { fixture.controller.preloadLog.contains("in-engine") }

        let preparesBefore = fixture.engine.count("prepare")
        fixture.emit(.endedNaturally(attemptID: first, finalPosition: 120))
        fixture.emit(.failedBeforeStart(attemptID: preload.attempt, error: .transport))

        let fresh = try await fixture.waitForPrepare(rawID: "b", after: preparesBefore)
        XCTAssertNotEqual(fresh, preload.attempt)
        XCTAssertTrue(fixture.controller.preloadLog.contains("resumed-held-end"),
                      "the discard must be what restarted playback: \(fixture.controller.preloadLog)")
    }

    /// Moves are expressed against what the app can SHOW; entries the catalog cannot name are
    /// absent there but present in the core, so the controller must map the position.
    func testAMoveAgainstAPresentationThatHidesAnEntryLandsWhereTheListShowsIt() async throws {
        let fixture = makeFixture(tracks: ["a", "b", "c", "d"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying != nil }
        // "hidden" is queued in the core but absent from the catalog, so it is not presented.
        let inserted = fixture.queue.enqueue(insertion: ApplePlaybackQueueInsertionDto(
            items: [ApplePlaybackQueueItemDto(
                providerInstanceId: fixture.tracks[0].id.providerInstanceID,
                rawId: "hidden",
                durationMilliseconds: 120_000
            )],
            sourceKind: "search", sourceRawId: nil, sourceDisplayName: "Search", mode: "playNext"
        ))
        fixture.controller.publish(inserted)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.entries.map(\.rawId), ["a", "hidden", "b", "c", "d"])
        let presented = try XCTUnwrap(fixture.controller.currentPresentation.nowPlaying?.queueEntries)
        XCTAssertEqual(presented.map(\.track.id.rawID), ["a", "b", "c", "d"], "the setup hides one entry")

        // Move d to presented index 2, where the list shows c. Unmapped, core index 2 would put
        // d before b (the hidden entry shifts it by one); mapped, it lands where the list shows.
        fixture.controller.edit(.move(presented[3].id, toIndex: 2))

        XCTAssertEqual(
            fixture.controller.currentPresentation.nowPlaying?.queueEntries.map(\.track.id.rawID),
            ["a", "b", "d", "c"],
            "core after: \(fixture.queue.snapshot().snapshot?.entries.map(\.rawId) ?? [])"
        )
    }

    /// The preload is registered in the core but the engine has not accepted it yet (resolution
    /// or the engine queue is slow) when the current item ends. Nothing will ever advance into
    /// it, so the core must start the next entry normally rather than wait for a boundary.
    func testAnEndBeforeThePreloadReachesTheEngineStartsTheNextEntryNormally() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.engine.holdPreloadCompletions = true
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let pending = try await fixture.waitForPreload(rawID: "b")
        XCTAssertFalse(fixture.controller.preloadLog.contains("in-engine"), "the engine has not accepted it")

        let preparesBefore = fixture.engine.count("prepare")
        fixture.emit(.endedNaturally(attemptID: first, finalPosition: 120))
        let fresh = try await fixture.waitForPrepare(rawID: "b", after: preparesBefore)
        XCTAssertNotEqual(fresh, pending.attempt)
        XCTAssertTrue(fixture.controller.preloadLog.contains("discarded:ended-before-delivery"))
        // The late acceptance of the abandoned preload must change nothing.
        fixture.engine.completeHeldPreloads()
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertFalse(fixture.controller.preloadLog.contains("in-engine"))
    }

    func testTheEndOfTheQueueKeepsTheLastTrackStoppedAndPlayReplaysIt() async throws {
        let fixture = makeFixture(tracks: ["only"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "only")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }

        fixture.emit(.endedNaturally(attemptID: first, finalPosition: 120))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.sessionID == nil }

        let ended = fixture.controller.currentPresentation
        XCTAssertEqual(ended.status, .ready, "not 'Nothing is playing'")
        let stopped = try XCTUnwrap(ended.nowPlaying)
        XCTAssertEqual(stopped.current.id.rawID, "only")
        XCTAssertFalse(stopped.isPlaying)
        XCTAssertEqual(stopped.phase, .paused)
        XCTAssertEqual(stopped.elapsed, .zero)
        XCTAssertEqual(try XCTUnwrap(fixture.store).snapshot.state, .nowPlaying)

        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.send(.play)
        let replay = try await fixture.waitForPrepare(rawID: "only", after: preparesBefore)
        XCTAssertNotEqual(replay, first, "Play after the end is a new session and attempt")
    }

    /// Next is offered on a finished queue exactly when it can act: under repeat-all it starts
    /// the first entry as a new session, and without repeat there is nothing after the last
    /// entry, so it is disabled rather than enabled and inert.
    func testNextOnAFinishedQueueStartsTheFirstEntryUnderRepeatAllAndIsOtherwiseDisabled() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 1))
        let last = try await fixture.waitForPrepare(rawID: "b")
        fixture.emit(.ready(attemptID: last, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: last, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        fixture.emit(.endedNaturally(attemptID: last, finalPosition: 120))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.sessionID == nil }

        let finished = try XCTUnwrap(fixture.controller.currentPresentation.nowPlaying)
        XCTAssertEqual(finished.current.id.rawID, "b")
        XCTAssertFalse(finished.canGoNext, "nothing follows the last entry without repeat")
        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.send(.next)
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(fixture.engine.count("prepare"), preparesBefore, "a disabled Next starts nothing")

        fixture.controller.send(.cycleRepeat)
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.repeatMode == .all }
        XCTAssertEqual(fixture.controller.currentPresentation.nowPlaying?.canGoNext, true)
        fixture.controller.send(.next)
        let restarted = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
        XCTAssertNotEqual(restarted, last)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 0)
        XCTAssertNotNil(fixture.queue.snapshot().snapshot?.currentSession, "a new session")
    }

    /// A track that fails is not a dead end: the failure names it and says whether Skip and
    /// Retry can act, Skip starts the next entry, and Retry starts the failed one again as a new
    /// session rather than resuming the failed attempt.
    func testAFailedTrackIsNamedAndSkipAndRetryStartTheRightEntries() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.failedBeforeStart(attemptID: first, error: .sourceUnavailable))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }

        let failure = try XCTUnwrap(fixture.controller.currentPresentation.failure)
        XCTAssertEqual(failure.track?.id.rawID, "a")
        XCTAssertTrue(failure.canSkip)
        XCTAssertTrue(failure.canRetry)
        XCTAssertEqual(try XCTUnwrap(fixture.store).snapshot.playbackFailure, failure)

        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.send(.retry)
        let retried = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
        XCTAssertNotEqual(retried, first, "Retry is a new attempt")
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 0)

        fixture.emit(.failedBeforeStart(attemptID: retried, error: .sourceUnavailable))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        let preparesBeforeSkip = fixture.engine.count("prepare")
        fixture.controller.send(.next)
        let lastAttempt = try await fixture.waitForPrepare(rawID: "b", after: preparesBeforeSkip)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 1)

        // The last entry failing has nowhere to skip to, and says so.
        fixture.emit(.failedBeforeStart(attemptID: lastAttempt, error: .sourceUnavailable))
        await fixture.waitFor { fixture.controller.currentPresentation.failure?.track?.id.rawID == "b" }
        XCTAssertEqual(fixture.controller.currentPresentation.failure?.canSkip, false)
    }

    /// A queue edit the core refuses reports that it changed nothing, so the surface can say so.
    func testARefusedQueueEditReportsThatItChangedNothing() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        _ = try await fixture.waitForPrepare(rawID: "a")
        let current = try XCTUnwrap(fixture.queue.snapshot().snapshot?.entries.first?.queueEntryId)
        XCTAssertFalse(fixture.controller.edit(.remove(DulcetQueueEntryID(current))),
                       "the current entry cannot be removed")
        XCTAssertFalse(fixture.controller.edit(.jump(DulcetQueueEntryID("entry-never-queued"))))
        let upcoming = try XCTUnwrap(fixture.queue.snapshot().snapshot?.entries.last?.queueEntryId)
        XCTAssertTrue(fixture.controller.edit(.remove(DulcetQueueEntryID(upcoming))))
    }

    func testArtworkReachesTheEngineAsValidatedBytesForItsOwnSession() async throws {
        let fixture = makeFixture(tracks: ["a", "b"], artwork: Data([0xFF, 0xD8, 0xFF, 0x01]))
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        let session = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)

        await fixture.waitFor { !fixture.engine.artwork.isEmpty }
        XCTAssertEqual(fixture.engine.artwork.first?.session, session)
        XCTAssertEqual(fixture.engine.artwork.first?.data, Data([0xFF, 0xD8, 0xFF, 0x01]))
        XCTAssertEqual(fixture.artworkFetcher.requests.first?.reference.artworkKey, "cover-a")

        // Republishing the same session does not refetch.
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        _ = try await fixture.waitForPreload(rawID: "b")
        await fixture.waitFor { fixture.artworkFetcher.requests.count >= 2 }
        XCTAssertEqual(fixture.artworkFetcher.requests.map(\.reference.artworkKey), ["cover-a", "cover-b"],
                       "one fetch per session: the current one, then the preloaded one")
    }

    func testPlayNextIntoAnEmptyPlayerStartsPlaybackAndIntoAQueueStartsNothing() async throws {
        let fixture = makeFixture(tracks: ["a", "b", "x"])
        fixture.controller.edit(.playNext(fixture.addition(["x"])))
        let started = try await fixture.waitForPrepare(rawID: "x")

        // Still preparing -- nothing is presented as playing yet -- and Play Next must ADD to what
        // was just started, not replace it.
        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.edit(.playNext(fixture.addition(["a", "b"])))
        fixture.emit(.ready(attemptID: started, duration: 120, seekability: .seekable))
        await fixture.waitFor {
            fixture.controller.currentPresentation.nowPlaying?.queueEntries.count == 3
        }
        XCTAssertEqual(
            fixture.controller.currentPresentation.nowPlaying?.queueEntries.map(\.track.id.rawID),
            ["x", "a", "b"]
        )
        XCTAssertEqual(fixture.engine.count("prepare"), preparesBefore, "an edit starts nothing")
    }

    // MARK: Fixture

    private func makeFixture(
        tracks rawIDs: [String],
        artwork: Data? = nil,
        recordStatuses: Bool = false
    ) -> Fixture {
        let tracks = rawIDs.map { rawID in
            DulcetTrack(
                id: DulcetProviderItemID(providerInstanceID: provider, rawID: rawID),
                title: "Track \(rawID)",
                credits: [],
                albumTitle: "System album",
                duration: .seconds(120),
                mediaSourceID: nil,
                artwork: DulcetArtwork(
                    seed: rawID,
                    palette: .indigoCoral,
                    remoteReference: DulcetArtworkReference(serverID: provider, artworkKey: "cover-\(rawID)")
                )
            )
        }
        let queue = ApplePlaybackQueueClient(databaseName: ":memory:")
        let engine = RecordingCommandEngine()
        let fetcher = RecordingArtworkFetcher(data: artwork)
        let controller = DulcetCorePlaybackController(
            queueClient: queue,
            engine: engine,
            catalog: tracks,
            artworkFetcher: artwork == nil ? nil : fetcher
        )
        controller.configure(account: DulcetPlaybackAccount(
            providerInstanceID: provider,
            normalizedServerURL: "http://127.0.0.1:9",
            username: "fixture",
            password: "fixture-password",
            allowLocalHTTP: true
        ))
        // The store's data source installs the controller's single presentation handler. Tests
        // that read the published status SEQUENCE install a recorder instead and do without the
        // store; the two are never mixed, so neither observes a handler the other replaced.
        let store: DulcetPresentationStore? = recordStatuses ? nil : DulcetPresentationStore(
            source: DulcetAccountDataSource(
                connector: SystemTestsUnusedConnector(), playbackController: controller
            )
        )
        store?.selectDestination(.nowPlaying)
        let fixture = Fixture(
            controller: controller,
            queue: queue,
            engine: engine,
            store: store,
            tracks: tracks,
            artworkFetcher: fetcher
        )
        addTeardownBlock { queue.close() }
        if recordStatuses {
            controller.setPresentationHandler { [weak fixture] presentation in
                fixture?.statuses.append(presentation.status)
            }
        }
        return fixture
    }
}

@MainActor
private final class Fixture {
    let controller: DulcetCorePlaybackController
    let queue: ApplePlaybackQueueClient
    let engine: RecordingCommandEngine
    let store: DulcetPresentationStore?
    let tracks: [DulcetTrack]
    let artworkFetcher: RecordingArtworkFetcher
    var statuses: [DulcetPlaybackSurfaceStatus] = []

    init(
        controller: DulcetCorePlaybackController,
        queue: ApplePlaybackQueueClient,
        engine: RecordingCommandEngine,
        store: DulcetPresentationStore?,
        tracks: [DulcetTrack],
        artworkFetcher: RecordingArtworkFetcher
    ) {
        self.controller = controller
        self.queue = queue
        self.engine = engine
        self.store = store
        self.tracks = tracks
        self.artworkFetcher = artworkFetcher
    }

    func intent(startIndex: Int) -> DulcetPlaybackQueueIntent {
        DulcetPlaybackQueueIntent(
            tracks: tracks.filter { $0.id.rawID != "x" },
            sourceKind: .album,
            sourceID: DulcetProviderItemID(providerInstanceID: tracks[0].id.providerInstanceID, rawID: "album"),
            sourceDisplayName: "System album",
            startIndex: startIndex,
            shuffle: false
        )
    }

    func addition(_ rawIDs: [String]) -> DulcetQueueAddition {
        DulcetQueueAddition(
            tracks: rawIDs.compactMap { rawID in tracks.first { $0.id.rawID == rawID } },
            sourceKind: .search,
            sourceID: nil,
            sourceDisplayName: "Search"
        )
    }

    func emit(_ event: DulcetPlaybackEvent) {
        XCTAssertTrue(engine.emit(event), "the controller must have installed its listener")
    }

    func waitFor(_ predicate: @MainActor () -> Bool) async {
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while !predicate(), ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    /// The attempt of the `after`-th-or-later prepare for `rawID`, read back from the core so the
    /// test never invents an identity the controller did not create.
    func waitForPrepare(rawID: String, after: Int = 0) async throws -> DulcetPlaybackAttemptID {
        await waitFor {
            self.engine.commands.enumerated().contains { offset, command in
                offset >= self.indexOfPrepare(after) && command.kind == "prepare" && command.title == "Track \(rawID)"
            }
        }
        let start = indexOfPrepare(after)
        let command = try XCTUnwrap(
            Array(engine.commands.dropFirst(start)).last { command in
                command.kind == "prepare" && command.title == "Track \(rawID)"
            },
            "no prepare for \(rawID)"
        )
        return DulcetPlaybackAttemptID(try XCTUnwrap(command.attempt))
    }

    func waitForPreload(rawID: String) async throws -> (attempt: DulcetPlaybackAttemptID, session: String) {
        await waitFor {
            self.engine.commands.contains { $0.kind == "preload" && $0.title == "Track \(rawID)" }
        }
        let command = try XCTUnwrap(
            engine.commands.last { $0.kind == "preload" && $0.title == "Track \(rawID)" },
            "no preload for \(rawID); log=\(controller.preloadLog)"
        )
        return (DulcetPlaybackAttemptID(try XCTUnwrap(command.attempt)), try XCTUnwrap(command.session))
    }

    private func indexOfPrepare(_ ordinal: Int) -> Int {
        guard ordinal > 0 else { return 0 }
        var seen = 0
        for (offset, command) in engine.commands.enumerated() where command.kind == "prepare" {
            seen += 1
            if seen == ordinal { return offset + 1 }
        }
        return engine.commands.count
    }
}

private struct RecordedCommand: Sendable {
    let kind: String
    let attempt: String?
    let session: String?
    let title: String?
}

/// Accepts every command; emits nothing on its own.
private final class RecordingCommandEngine: DulcetCorePlaybackEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var listener: DulcetPlaybackEventHandler?
    private var recorded: [RecordedCommand] = []
    private var artworkStorage: [(session: String, data: Data?)] = []
    private var heldPreloads: [(DulcetPlaybackCommandOutcome, DulcetPlaybackCommandCompletion)] = []
    /// When set, `preloadNext` is recorded but not answered until `completeHeldPreloads()`.
    var holdPreloadCompletions = false

    func completeHeldPreloads() {
        let held = lock.withLock { () -> [(DulcetPlaybackCommandOutcome, DulcetPlaybackCommandCompletion)] in
            defer { heldPreloads = [] }
            return heldPreloads
        }
        held.forEach { outcome, completion in completion(outcome) }
    }

    var commands: [RecordedCommand] { lock.withLock { recorded } }
    var artwork: [(session: String, data: Data?)] { lock.withLock { artworkStorage } }

    func count(_ kind: String) -> Int { commands.filter { $0.kind == kind }.count }

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
        let entry: RecordedCommand
        let outcome: DulcetPlaybackCommandOutcome
        switch command {
        case let .prepare(commandID, plan):
            entry = .init(kind: "prepare", attempt: plan.attemptID.rawValue,
                          session: plan.playbackSessionID.rawValue, title: plan.metadata.title)
            outcome = .accepted(commandID: commandID)
        case let .preloadNext(commandID, plan):
            entry = .init(kind: "preload", attempt: plan.attemptID.rawValue,
                          session: plan.playbackSessionID.rawValue, title: plan.metadata.title)
            outcome = .accepted(commandID: commandID)
        case let .discardPreloaded(commandID, attemptID):
            entry = .init(kind: "discard", attempt: attemptID.rawValue, session: nil, title: nil)
            outcome = .completed(commandID: commandID, result: .withoutData)
        case let .stop(commandID):
            entry = .init(kind: "stop", attempt: nil, session: nil, title: nil)
            outcome = .completed(commandID: commandID, result: .withoutData)
        default:
            entry = .init(kind: "other", attempt: nil, session: nil, title: nil)
            outcome = .completed(commandID: command.commandID, result: .withoutData)
        }
        lock.withLock { recorded.append(entry) }
        if entry.kind == "preload", holdPreloadCompletions {
            lock.withLock { heldPreloads.append((outcome, completion)) }
            return
        }
        completion(outcome)
    }

    func setRemoteCommandRouter(_ router: (any DulcetRemotePlaybackCommandRouting)?) {}

    func updateRemoteCommandCapabilities(
        _ capabilities: DulcetRemoteCommandCapabilities,
        for sessionID: DulcetPlaybackSessionID
    ) -> Bool { true }

    func setNowPlayingArtwork(_ imageData: Data?, for sessionID: DulcetPlaybackSessionID) {
        lock.withLock { artworkStorage.append((sessionID.rawValue, imageData)) }
    }
}

@MainActor
private final class RecordingArtworkFetcher: DulcetArtworkFetching {
    private let data: Data?
    private(set) var requests: [DulcetArtworkFetchRequest] = []

    init(data: Data?) { self.data = data }

    func fetch(
        _ request: DulcetArtworkFetchRequest,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> any DulcetArtworkFetchOperation {
        requests.append(request)
        let outcome: DulcetArtworkFetchOutcome = data.map { .loaded($0) } ?? .unavailable
        Task { @MainActor in completion(outcome) }
        return NoopArtworkOperation()
    }
}

@MainActor
private final class NoopArtworkOperation: DulcetArtworkFetchOperation {
    func cancel() {}
}

@MainActor
private final class SystemTestsUnusedConnector: DulcetAccountConnecting {
    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        fatalError("Playback system tests must not connect to a server")
    }
}
