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

    /// Spec §12.5: the quality for the network reaches each resolve. A change replaces a preload
    /// resolved at the old quality, so the next item plays at the new one, and never restarts or
    /// re-prepares what is playing. The choice is saved in the core's stored form.
    func testTheStreamingQualityReachesTheNextResolveAndAChangeReplacesTheStalePreload() async throws {
        let fixture = makeFixture(tracks: ["a", "b", "c"], sourceContainer: .flac)
        let suite = "dulcet-quality-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { UserDefaults().removePersistentDomain(forName: suite) }
        let quality = DulcetCoreStreamingQuality(defaults: defaults, monitorsNetwork: false)
        quality.setNetwork(.metered)
        quality.setPreference(DulcetStreamingQualityPreference(unmetered: .original, metered: .kbps128))
        fixture.controller.streamingQuality = quality

        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        XCTAssertEqual(fixture.engine.commands.last { $0.kind == "prepare" }?.container, .mp3,
                       "on cellular the FLAC source is asked for as a capped MP3 stream")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let staleB = try await fixture.waitForPreload(rawID: "b")
        XCTAssertEqual(fixture.engine.commands.last { $0.kind == "preload" }?.container, .mp3)
        let prepares = fixture.engine.count("prepare")
        let stops = fixture.engine.count("stop")

        quality.setNetwork(.unmetered)

        await fixture.waitFor {
            fixture.engine.commands.contains { $0.kind == "discard" && $0.attempt == staleB.attempt.rawValue }
        }
        await fixture.waitFor {
            fixture.engine.commands.filter { $0.kind == "preload" && $0.title == "Track b" }.count == 2
        }
        let freshB = try XCTUnwrap(fixture.engine.commands.last { $0.kind == "preload" && $0.title == "Track b" })
        XCTAssertNotEqual(freshB.attempt, staleB.attempt.rawValue)
        XCTAssertEqual(freshB.container, .flac, "on Wi-Fi the next item is the original FLAC file")
        XCTAssertEqual(fixture.engine.count("prepare"), prepares, "what is playing is not re-prepared")
        XCTAssertEqual(fixture.engine.count("stop"), stops, "nor stopped")
        XCTAssertTrue(fixture.controller.preloadLog.contains("discarded:quality"))

        XCTAssertEqual(defaults.string(forKey: DulcetCoreStreamingQuality.defaultsKey), "unmetered=original;metered=128")
        XCTAssertEqual(
            DulcetCoreStreamingQuality(defaults: defaults, monitorsNetwork: false).preference,
            DulcetStreamingQualityPreference(unmetered: .original, metered: .kbps128),
            "the choice survives a relaunch"
        )
    }

    /// Every choice the settings screen offers is a core choice with the same stored name.
    func testEveryStreamingQualityChoiceIsTheCoresOwn() {
        for choice in DulcetStreamingQuality.allCases {
            let core = DulcetCoreStreamingQuality.core(choice)
            XCTAssertEqual(core.wireName, choice.rawValue)
            XCTAssertEqual(core.maxBitRateKbps?.intValue, choice.maxBitRateKbps)
            XCTAssertEqual(DulcetCoreStreamingQuality.presentation(core), choice)
        }
        XCTAssertEqual(StreamingQuality.entries.count, DulcetStreamingQuality.allCases.count)
    }

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

    /// `Stopped` also follows a natural end. While a preload takes over, the ended session stays
    /// current, reading Stopped, until `AdvancedToPreloaded` -- and the lock screen already offers
    /// Play for it. A Play pressed in that gap (a remote Play, a media key, the app's own) is not
    /// Play after a stop: restarting there would discard the preload and replay the ended entry
    /// over the one about to play. It goes to the engine as a plain play, as it always did.
    func testAPlayWhileAnEndIsHeldForThePreloadDoesNotReplayTheEndedEntry() async throws {
        for press in ["remote", "app"] {
            let fixture = makeFixture(tracks: ["a", "b"])
            fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
            let first = try await fixture.waitForPrepare(rawID: "a")
            fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
            fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
            let preload = try await fixture.waitForPreload(rawID: "b")
            await fixture.waitFor { fixture.controller.preloadLog.contains("in-engine") }
            let endedSession = try XCTUnwrap(
                fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)

            fixture.emit(.ready(attemptID: preload.attempt, duration: 120, seekability: .seekable))
            fixture.emit(.endedNaturally(attemptID: first, finalPosition: 120))
            await fixture.waitFor { fixture.queue.snapshot().snapshot?.currentSession?.phase == "Stopped" }
            // The held end is reached, or nothing below means anything.
            XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.phase, "Stopped", press)
            XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId,
                           endedSession, "\(press): the ended session is still current")
            XCTAssertTrue(fixture.controller.preloadLog.contains("in-engine"), press)

            let preparesBefore = fixture.engine.count("prepare")
            let playsBefore = fixture.engine.count("play")
            let stopsBefore = fixture.engine.count("stop")
            if press == "remote" {
                let router = try XCTUnwrap(fixture.engine.remoteRouter)
                XCTAssertTrue(router.handleRemotePlaybackCommand(.play(sessionID: .init(endedSession))), press)
            } else {
                fixture.controller.send(.play)
            }
            // A replay would prepare a again; give it the whole window to happen.
            await fixture.waitFor { fixture.engine.count("prepare") > preparesBefore }
            XCTAssertEqual(fixture.engine.count("prepare"), preparesBefore,
                           "\(press): the ended entry must not be prepared again")
            XCTAssertEqual(fixture.engine.count("discard"), 0, "\(press): the preload must not be dropped")
            XCTAssertEqual(fixture.engine.count("stop"), stopsBefore, "\(press): nor the engine stopped")
            XCTAssertFalse(fixture.controller.preloadLog.contains { $0.hasPrefix("discarded") },
                           "\(press): \(fixture.controller.preloadLog)")
            XCTAssertEqual(fixture.engine.count("play"), playsBefore + 1,
                           "\(press): the Play reaches the engine as a plain play, as before")

            fixture.emit(.advancedToPreloaded(oldAttemptID: first, newAttemptID: preload.attempt))
            await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.current.id.rawID == "b" }
            XCTAssertEqual(fixture.controller.currentPresentation.nowPlaying?.current.id.rawID, "b",
                           "\(press): the preload takes over")
            XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 1, press)
        }
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
    /// Retry can act, Skip starts the next entry, and Retry after a failure before start is a new
    /// attempt inside the same session (spec §12.1), never a resumption of the failed attempt.
    /// The failure is the CONNECTION's, so nothing moves on its own (spec §12.12).
    func testAFailedTrackIsNamedAndSkipAndRetryStartTheRightEntries() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.failedBeforeStart(attemptID: first, error: .transport))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        XCTAssertFalse(fixture.engine.commands.contains { $0.kind == "prepare" && $0.title == "Track b" },
                       "a connection failure must not move the queue on its own")
        XCTAssertNil(fixture.controller.currentPresentation.skipNotice)

        let failure = try XCTUnwrap(fixture.controller.currentPresentation.failure)
        XCTAssertEqual(failure.track?.id.rawID, "a")
        XCTAssertTrue(failure.canSkip)
        XCTAssertTrue(failure.canRetry)
        XCTAssertEqual(try XCTUnwrap(fixture.store).snapshot.playbackFailure, failure)

        let failedSession = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)
        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.send(.retry)
        let retried = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
        XCTAssertNotEqual(retried, first, "Try Again is a new attempt")
        // ...of the SAME session (spec §12.1: a retry after FailedBeforeStart keeps the session and
        // its accumulator). Finalizing the failed session and starting another is the next-item
        // boundary, which a new attempt id alone cannot tell apart from this.
        XCTAssertEqual(fixture.session(ofPrepare: retried), failedSession,
                       "Try Again after a failure before start must keep the session")
        let retriedSession = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession)
        XCTAssertEqual(retriedSession.playbackSessionId, failedSession)
        XCTAssertEqual(retriedSession.attemptId, retried.rawValue)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 0)

        fixture.emit(.failedBeforeStart(attemptID: retried, error: .transport))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        let preparesBeforeSkip = fixture.engine.count("prepare")
        fixture.controller.send(.next)
        let lastAttempt = try await fixture.waitForPrepare(rawID: "b", after: preparesBeforeSkip)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 1)

        // The last entry failing has nowhere to skip to, and says so -- even as the track's own
        // failure, which would otherwise move the queue on (spec §12.12).
        fixture.emit(.failedBeforeStart(attemptID: lastAttempt, error: .sourceUnavailable))
        await fixture.waitFor { fixture.controller.currentPresentation.failure?.track?.id.rawID == "b" }
        XCTAssertEqual(fixture.controller.currentPresentation.failure?.canSkip, false)
    }

    /// A track that played and then stopped says it stopped partway, and Try Again keeps its
    /// session as any retry does (spec §12.1): a new attempt, resuming where the failure left off,
    /// so what was already heard still counts toward the one play this listen is.
    func testRetryAfterAPartialFailureKeepsTheSessionAndResumesWhereItStopped() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        let failedSession = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)
        XCTAssertEqual(fixture.engine.count("seek"), 0, "a fresh start does not seek")

        fixture.emit(.failedAfterPartial(attemptID: first, position: 40, error: .transport))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        let failure = try XCTUnwrap(fixture.controller.currentPresentation.failure)
        XCTAssertEqual(failure.track?.id.rawID, "a")
        XCTAssertTrue(failure.stoppedPartway, "the failure must be reported as stopped partway")
        XCTAssertTrue(failure.canRetry)
        XCTAssertTrue(failure.canSkip)
        // A dismissal is kept by these (§3.1), so they must name this failure and no other.
        XCTAssertEqual(failure.attemptID, first.rawValue, "the failure names the attempt that failed")
        XCTAssertEqual(failure.queueEntryID, fixture.queue.snapshot().snapshot?.entries.first?.queueEntryId)
        XCTAssertNotNil(failure.queueEntryID)

        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.send(.retry)
        let retried = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
        XCTAssertNotEqual(retried, first, "Try Again is a new attempt")
        XCTAssertEqual(fixture.session(ofPrepare: retried), failedSession,
                       "Try Again after a partial failure must keep the session")
        let retriedSession = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession)
        XCTAssertEqual(retriedSession.playbackSessionId, failedSession)
        XCTAssertEqual(retriedSession.attemptId, retried.rawValue)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 0)

        fixture.emit(.ready(attemptID: retried, duration: 120, seekability: .seekable))
        await fixture.waitFor { fixture.engine.count("seek") > 0 }
        XCTAssertEqual(fixture.engine.commands.last { $0.kind == "seek" }?.position, 40,
                       "the retried attempt resumes where the failure left off")
    }

    /// A Play from outside the app -- the lock screen, Control Center, a headset or a keyboard's
    /// media key -- after a failure is that failure's Try Again, as the app's own Play is and as
    /// Android's is (spec §12.1, §15): a new attempt of the same session, not a bare play command
    /// addressed to an attempt that is over. It used to reach the engine as `.play` for the failed
    /// item, which AVPlayer ignores, so the system control did nothing at all.
    func testARemotePlayAfterAFailureIsTryAgain() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        let failedSession = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)

        fixture.emit(.failedAfterPartial(attemptID: first, position: 40, error: .transport))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        // A real player pauses after its item fails, and its observer can report it late. That
        // report must not take the attempt out of Failed, or there is nothing left to retry.
        fixture.emit(.paused(attemptID: first, position: 40))
        await fixture.waitFor { fixture.queue.snapshot().snapshot?.currentSession?.phase != "Failed" }
        // The failure state is reached, or nothing below means anything.
        XCTAssertEqual(fixture.controller.currentPresentation.status, .failed)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.phase, "Failed")
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId, failedSession)
        XCTAssertTrue(try XCTUnwrap(fixture.controller.currentPresentation.failure).canRetry)

        let router = try XCTUnwrap(fixture.engine.remoteRouter, "the controller must install its router")
        let preparesBefore = fixture.engine.count("prepare")
        let playsBefore = fixture.engine.count("play")
        XCTAssertTrue(router.handleRemotePlaybackCommand(.play(sessionID: .init(failedSession))),
                      "a remote Play on the failed session must be accepted")
        let retried = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
        XCTAssertNotEqual(retried, first, "a remote Play after a failure is a new attempt")
        XCTAssertEqual(fixture.session(ofPrepare: retried), failedSession,
                       "...of the same session, exactly as Try Again")
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.attemptId, retried.rawValue)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 0, "the failed entry, not the next")
        XCTAssertEqual(fixture.engine.count("play"), playsBefore,
                       "no bare play may be addressed to the attempt that failed")

        fixture.emit(.ready(attemptID: retried, duration: 120, seekability: .seekable))
        await fixture.waitFor { fixture.engine.count("seek") > 0 }
        XCTAssertEqual(fixture.engine.commands.last { $0.kind == "seek" }?.position, 40,
                       "the remote retry resumes where the failure left off, as Try Again does")
    }

    /// A headset's single button sends toggle, not play. After a failure nothing is playing, so
    /// toggle is Play, and Play is Try Again. Pause, by contrast, has nothing to act on and must
    /// not start anything.
    func testARemoteToggleAfterAFailureIsTryAgainAndPauseStartsNothing() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.failedBeforeStart(attemptID: first, error: .transport))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        XCTAssertEqual(fixture.controller.currentPresentation.status, .failed)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.phase, "Failed")
        let failedSession = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)
        let router = try XCTUnwrap(fixture.engine.remoteRouter, "the controller must install its router")

        let preparesBeforePause = fixture.engine.count("prepare")
        _ = router.handleRemotePlaybackCommand(.pause(sessionID: .init(failedSession)))
        await fixture.waitFor { fixture.engine.count("prepare") > preparesBeforePause }
        XCTAssertEqual(fixture.engine.count("prepare"), preparesBeforePause, "Pause must not retry")
        XCTAssertEqual(fixture.controller.currentPresentation.status, .failed)

        XCTAssertTrue(router.handleRemotePlaybackCommand(.toggle(sessionID: .init(failedSession))))
        let retried = try await fixture.waitForPrepare(rawID: "a", after: preparesBeforePause)
        XCTAssertNotEqual(retried, first)
        XCTAssertEqual(fixture.session(ofPrepare: retried), failedSession,
                       "a toggle after a failure before start keeps the session, as Try Again does")
    }

    /// The app's own Play goes the same way: one path, whichever control pressed it.
    func testTheAppsOwnPlayAfterAFailureIsTryAgain() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.failedBeforeStart(attemptID: first, error: .transport))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.phase, "Failed")
        let failedSession = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)

        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.send(.play)
        let retried = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
        XCTAssertNotEqual(retried, first)
        XCTAssertEqual(fixture.session(ofPrepare: retried), failedSession)
    }

    /// Play after a stop restarts the entry, as Android's does (spec §12.1): the engine's stop
    /// removed its item, so a bare play addressed to the stopped attempt would be refused and
    /// nothing would sound. The stopped entry is shown at rest, so Play is offered at all; Play
    /// and a toggle (a headset's one button) both start it again as a new session, resuming where
    /// the stop saved its position.
    func testPlayAfterAStopRestartsTheEntryAsANewSession() async throws {
        for press in ["play", "toggle"] {
            let fixture = makeFixture(tracks: ["a", "b"])
            fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
            let first = try await fixture.waitForPrepare(rawID: "a")
            fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
            fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
            await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
            let stoppedSession = try XCTUnwrap(
                fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)

            // What the engine's stop reports for the attempt it held.
            fixture.emit(.skipped(attemptID: first, position: 40, reason: .user))
            await fixture.waitFor { fixture.queue.snapshot().snapshot?.currentSession?.phase == "Stopped" }
            // The stop is reached and keeps its session, or nothing below means anything.
            XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.phase, "Stopped", press)
            XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId,
                           stoppedSession, press)
            await fixture.waitFor { fixture.controller.currentPresentation.status == .ready }
            let atRest = try XCTUnwrap(fixture.controller.currentPresentation.nowPlaying,
                                       "\(press): the stopped entry is shown, so Play is offered")
            XCTAssertEqual(fixture.controller.currentPresentation.status, .ready, press)
            XCTAssertEqual(atRest.current.id.rawID, "a", press)
            XCTAssertFalse(atRest.isPlaying, press)
            XCTAssertEqual(atRest.elapsed, .seconds(40),
                           "\(press): shown where Play will resume, the position the stop saved")

            let preparesBefore = fixture.engine.count("prepare")
            let playsBefore = fixture.engine.count("play")
            fixture.controller.send(press == "play" ? .play : .toggle)
            let restarted = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
            XCTAssertNotEqual(restarted, first, "\(press): a new attempt")
            XCTAssertNotEqual(fixture.session(ofPrepare: restarted), stoppedSession,
                              "\(press): Play after Stop starts a new session, as on Android")
            XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 0,
                           "\(press): the stopped entry, not the next")
            XCTAssertEqual(fixture.engine.count("play"), playsBefore,
                           "\(press): no bare play may be addressed to the stopped attempt")

            fixture.emit(.ready(attemptID: restarted, duration: 120, seekability: .seekable))
            await fixture.waitFor { fixture.engine.count("seek") > 0 }
            XCTAssertEqual(fixture.engine.commands.last { $0.kind == "seek" }?.position, 40,
                           "\(press): the restart resumes where the stop saved its position")
        }
    }

    /// A failure at the end left nothing to resume: Try Again replays the track as a new session,
    /// from the start (spec §12.1), so the second listen is a play of its own.
    func testRetryAfterAFailureAtTheEndReplaysInANewSessionFromTheStart() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        let failedSession = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)

        fixture.emit(.failedAfterPartial(attemptID: first, position: 120, error: .transport))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        XCTAssertTrue(try XCTUnwrap(fixture.controller.currentPresentation.failure).canRetry)

        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.send(.retry)
        let replay = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
        let replaySession = try XCTUnwrap(fixture.session(ofPrepare: replay))
        XCTAssertNotEqual(replaySession, failedSession, "a replay after a failure at the end is a new session")
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId, replaySession)
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentIndex, 0, "the same entry, not the next")

        fixture.emit(.ready(attemptID: replay, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: replay, wallClock: Date(), mediaPosition: 0))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        XCTAssertEqual(fixture.engine.count("seek"), 0, "the replay starts from the beginning")
    }

    /// Every start stops the engine, and the engine's stop clears its Now Playing artwork, so a
    /// retry inside the same session must hand the artwork over again -- a session whose artwork
    /// was delivered once is not a session the engine still has artwork for.
    func testARetryInTheSameSessionDeliversItsArtworkAgain() async throws {
        let fixture = makeFixture(tracks: ["a", "b"], artwork: Data([0xFF, 0xD8, 0xFF, 0x02]))
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let session = try XCTUnwrap(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId)
        await fixture.waitFor { fixture.engine.artwork.contains { $0.session == session } }
        XCTAssertEqual(fixture.engine.artwork.filter { $0.session == session }.count, 1,
                       "the case needs the artwork delivered once before the failure")

        fixture.emit(.failedAfterPartial(attemptID: first, position: 40, error: .transport))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        let preparesBefore = fixture.engine.count("prepare")
        fixture.controller.send(.retry)
        let retried = try await fixture.waitForPrepare(rawID: "a", after: preparesBefore)
        XCTAssertEqual(fixture.session(ofPrepare: retried), session, "the retry keeps the session")
        fixture.emit(.ready(attemptID: retried, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: retried, wallClock: Date(), mediaPosition: 40))

        await fixture.waitFor { fixture.engine.artwork.filter { $0.session == session }.count >= 2 }
        XCTAssertEqual(fixture.engine.artwork.filter { $0.session == session }.count, 2,
                       "the retried attempt's engine item must get the artwork again")
        XCTAssertEqual(fixture.engine.artwork.last?.data, Data([0xFF, 0xD8, 0xFF, 0x02]))
    }

    /// The only track of a repeating queue has nowhere else to go: Next would start the same
    /// failed track, so its failure offers Try Again and no Skip.
    func testTheOnlyTrackOfARepeatingQueueFailingOffersNoSkip() async throws {
        let fixture = makeFixture(tracks: ["only"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "only")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        fixture.controller.send(.cycleRepeat)
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.repeatMode == .all }
        XCTAssertEqual(fixture.controller.currentPresentation.nowPlaying?.repeatMode, .all,
                       "the case needs repeat-all on")

        fixture.emit(.failedAfterPartial(attemptID: first, position: 40, error: .sourceUnavailable))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }
        let failure = try XCTUnwrap(fixture.controller.currentPresentation.failure)
        XCTAssertEqual(failure.track?.id.rawID, "only")
        XCTAssertFalse(failure.canSkip, "Skip would start the failed track again")
        XCTAssertTrue(failure.canRetry)
    }

    /// Spec §12.12: the engine advances on its own into an entry it cannot decode. That failure is
    /// the track's own, so the queue moves past it as a next-item advance: the next entry starts
    /// in a session of its own, the failure line never appears for a track that is not playing,
    /// and the person is told which track was skipped.
    func testAnAutomaticAdvanceIntoAnUndecodableEntrySkipsItAndStartsTheNext() async throws {
        let fixture = makeFixture(tracks: ["a", "b", "c"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let preload = try await fixture.waitForPreload(rawID: "b")
        fixture.emit(.endedNaturally(attemptID: first, finalPosition: 120))
        fixture.emit(.advancedToPreloaded(oldAttemptID: first, newAttemptID: preload.attempt))
        await fixture.waitFor { fixture.queue.snapshot().snapshot?.currentIndex == 1 }
        // The experiment is an AUTOMATIC advance: b became current through the engine's own
        // boundary, not through anything this test asked the controller for.
        XCTAssertEqual(fixture.queue.snapshot().snapshot?.currentSession?.playbackSessionId, preload.session,
                       "the case needs b current through the gapless boundary")
        XCTAssertNil(fixture.controller.currentPresentation.skipNotice, "nothing skipped yet")
        let preparesBefore = fixture.engine.count("prepare")

        fixture.emit(.failedBeforeStart(attemptID: preload.attempt, error: .undecodable))

        let third = try await fixture.waitForPrepare(rawID: "c", after: preparesBefore)
        let snapshot = try XCTUnwrap(fixture.queue.snapshot().snapshot)
        XCTAssertEqual(snapshot.currentIndex, 2, "the queue moved past b")
        let thirdSession = try XCTUnwrap(fixture.session(ofPrepare: third))
        XCTAssertNotEqual(thirdSession, preload.session, "a next-item advance: c has its own session")
        XCTAssertEqual(snapshot.currentSession?.playbackSessionId, thirdSession)
        XCTAssertFalse(fixture.queue.acceptsCommand(playbackSessionId: preload.session, requiresSeekable: false),
                       "b's session ended")
        XCTAssertNotEqual(fixture.controller.currentPresentation.status, .failed,
                          "the failure line is never left up for a track that is not playing")
        XCTAssertNil(fixture.controller.currentPresentation.failure)
        let notice = try XCTUnwrap(fixture.controller.currentPresentation.skipNotice, "the person is told")
        XCTAssertEqual(notice.title, "Track b")
        XCTAssertEqual(notice.message, "Couldn\u{2019}t play \u{201C}Track b\u{201D}. Skipped.")
        XCTAssertEqual(try XCTUnwrap(fixture.store).snapshot.playbackSkipNotice, notice)

        // c plays: the chain ended, and b is behind it in the queue like any entry passed.
        fixture.emit(.ready(attemptID: third, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: third, wallClock: Date(), mediaPosition: 1))
        await fixture.waitFor { fixture.controller.currentPresentation.nowPlaying?.isPlaying == true }
        let nowPlaying = try XCTUnwrap(fixture.controller.currentPresentation.nowPlaying)
        XCTAssertEqual(nowPlaying.current.id.rawID, "c")
        XCTAssertEqual(nowPlaying.queueEntries.prefix(2).map(\.track.id.rawID), ["a", "b"])
    }

    /// A notice belongs to the session that produced it: disconnecting -- which signing out does
    /// too, through account removal -- takes it away with everything else, so a later presentation
    /// never carries a skip from an account that is gone.
    func testDisconnectingClearsTheSkipNotice() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.failedBeforeStart(attemptID: first, error: .undecodable))
        _ = try await fixture.waitForPrepare(rawID: "b")
        XCTAssertNotNil(fixture.controller.currentPresentation.skipNotice, "the case needs a notice up")

        fixture.controller.disconnect()

        XCTAssertNil(fixture.controller.currentPresentation.skipNotice)
        await fixture.waitFor { fixture.store?.snapshot.playbackSkipNotice == nil }
        XCTAssertNil(try XCTUnwrap(fixture.store).snapshot.playbackSkipNotice)
    }

    /// Reaching another server withdraws the notice too: it names a track of the queue left
    /// behind. Configuring the same server again -- a reconnect -- keeps it.
    func testConfiguringAnotherAccountClearsTheSkipNoticeAndTheSameOneKeepsIt() async throws {
        let fixture = makeFixture(tracks: ["a", "b"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.failedBeforeStart(attemptID: first, error: .undecodable))
        _ = try await fixture.waitForPrepare(rawID: "b")
        let notice = try XCTUnwrap(fixture.controller.currentPresentation.skipNotice, "the case needs a notice up")

        fixture.controller.configure(account: account(provider))
        XCTAssertEqual(fixture.controller.currentPresentation.skipNotice, notice, "the same server keeps it")

        fixture.controller.configure(account: account("another-\(provider)"))

        XCTAssertNil(fixture.controller.currentPresentation.skipNotice)
        await fixture.waitFor { fixture.store?.snapshot.playbackSkipNotice == nil }
        XCTAssertNil(try XCTUnwrap(fixture.store).snapshot.playbackSkipNotice)
    }

    /// The person's Play reaches the core as they press it (spec §12.12 rule 4). A queue restored
    /// paused, Play pressed while its entry is still preparing -- the engine reports no `Resumed`
    /// before Ready -- then Ready and a decode failure: the next entry starts playing. The control
    /// is the same sequence with no Play, where the next entry is prepared and left paused.
    func testAPlayPressedBeforeARestoredEntryIsReadyLetsTheSkipPlayOn() async throws {
        for pressesPlay in [true, false] {
            let launched = makeFixture(tracks: ["a", "b", "c"])
            launched.controller.replaceQueueAndPlay(launched.intent(startIndex: 0))
            _ = try await launched.waitForPrepare(rawID: "a")

            // A relaunch: a new queue client and controller over the same database.
            let relaunched = makeFixture(tracks: ["a", "b", "c"])
            relaunched.controller.restorePersistedQueue(with: relaunched.tracks, catalogCoverage: .wholeLibrary)
            let restored = try await relaunched.waitForPrepare(rawID: "a")
            XCTAssertEqual(relaunched.engine.count("play"), 0, "the restore itself starts no sound")
            relaunched.emit(.preparing(attemptID: restored))
            if pressesPlay { relaunched.controller.send(.play) }
            relaunched.emit(.ready(attemptID: restored, duration: 120, seekability: .seekable))
            let playsBefore = relaunched.engine.count("play")

            relaunched.emit(.failedBeforeStart(attemptID: restored, error: .undecodable))
            let next = try await relaunched.waitForPrepare(rawID: "b")
            relaunched.emit(.ready(attemptID: next, duration: 120, seekability: .seekable))
            await relaunched.waitFor { relaunched.engine.count("play") > playsBefore }

            XCTAssertEqual(relaunched.controller.currentPresentation.skipNotice?.title, "Track a",
                           "pressesPlay=\(pressesPlay): the skip happened")
            XCTAssertEqual(relaunched.engine.count("play") - playsBefore, pressesPlay ? 1 : 0,
                           pressesPlay ? "the person pressed Play, so b starts playing"
                               : "nobody pressed Play, so b is prepared and left paused")
        }
    }

    /// A connection-class failure of the entry the engine advanced into stops there and is
    /// presented -- the same boundary as above, with the other side of the §12.12 rule.
    func testAnAutomaticAdvanceIntoAConnectionFailureStopsAndPresentsIt() async throws {
        let fixture = makeFixture(tracks: ["a", "b", "c"])
        fixture.controller.replaceQueueAndPlay(fixture.intent(startIndex: 0))
        let first = try await fixture.waitForPrepare(rawID: "a")
        fixture.emit(.ready(attemptID: first, duration: 120, seekability: .seekable))
        fixture.emit(.playbackProgressBegan(attemptID: first, wallClock: Date(), mediaPosition: 1))
        let preload = try await fixture.waitForPreload(rawID: "b")
        fixture.emit(.endedNaturally(attemptID: first, finalPosition: 120))
        fixture.emit(.advancedToPreloaded(oldAttemptID: first, newAttemptID: preload.attempt))
        await fixture.waitFor { fixture.queue.snapshot().snapshot?.currentIndex == 1 }

        fixture.emit(.failedBeforeStart(attemptID: preload.attempt, error: .engine))
        await fixture.waitFor { fixture.controller.currentPresentation.status == .failed }

        XCTAssertEqual(fixture.controller.currentPresentation.failure?.track?.id.rawID, "b")
        XCTAssertEqual(fixture.controller.currentPresentation.failure?.canSkip, true)
        XCTAssertFalse(fixture.engine.commands.contains { $0.kind == "prepare" && $0.title == "Track c" },
                       "the engine's own failure is not the track's: nothing skips")
        XCTAssertNil(fixture.controller.currentPresentation.skipNotice)
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

    private func account(_ providerInstanceID: String) -> DulcetPlaybackAccount {
        DulcetPlaybackAccount(
            providerInstanceID: providerInstanceID,
            normalizedServerURL: "http://127.0.0.1:9",
            username: "fixture",
            password: "fixture-password",
            allowLocalHTTP: true
        )
    }

    private func makeFixture(
        tracks rawIDs: [String],
        artwork: Data? = nil,
        recordStatuses: Bool = false,
        sourceContainer: DulcetAudioContainer = .mp3
    ) -> Fixture {
        let tracks = rawIDs.map { rawID in
            DulcetTrack(
                id: DulcetProviderItemID(providerInstanceID: provider, rawID: rawID),
                title: "Track \(rawID)",
                credits: [],
                albumTitle: "System album",
                duration: .seconds(120),
                sourceContainer: sourceContainer,
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
        controller.configure(account: account(provider))
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

    /// The session the engine was told an attempt belongs to, as the prepare command carried it.
    func session(ofPrepare attempt: DulcetPlaybackAttemptID) -> String? {
        engine.commands.last { $0.kind == "prepare" && $0.attempt == attempt.rawValue }?.session
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
    var position: TimeInterval? = nil
    var container: DulcetAudioContainer? = nil
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
                          session: plan.playbackSessionID.rawValue, title: plan.metadata.title,
                          container: plan.expectedContainer)
            outcome = .accepted(commandID: commandID)
        case let .preloadNext(commandID, plan):
            entry = .init(kind: "preload", attempt: plan.attemptID.rawValue,
                          session: plan.playbackSessionID.rawValue, title: plan.metadata.title,
                          container: plan.expectedContainer)
            outcome = .accepted(commandID: commandID)
        case let .discardPreloaded(commandID, attemptID):
            entry = .init(kind: "discard", attempt: attemptID.rawValue, session: nil, title: nil)
            outcome = .completed(commandID: commandID, result: .withoutData)
        case let .play(commandID):
            entry = .init(kind: "play", attempt: nil, session: nil, title: nil)
            outcome = .completed(commandID: commandID, result: .withoutData)
        case let .stop(commandID):
            entry = .init(kind: "stop", attempt: nil, session: nil, title: nil)
            outcome = .completed(commandID: commandID, result: .withoutData)
        case let .seek(commandID, position):
            entry = .init(kind: "seek", attempt: nil, session: nil, title: nil, position: position)
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

    private var router: (any DulcetRemotePlaybackCommandRouting)?

    /// The router the controller installed: what a system control reaches, and nothing else.
    var remoteRouter: (any DulcetRemotePlaybackCommandRouting)? { lock.withLock { router } }

    func setRemoteCommandRouter(_ router: (any DulcetRemotePlaybackCommandRouting)?) {
        lock.withLock { self.router = router }
    }

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
