import AVFoundation
import Foundation
import MediaPlayer
import Testing
@testable import DulcetKit

#if os(macOS)
import CoreAudio
import Network
#endif

@Suite(.serialized)
struct AVPlayerEngineTests {
    @Test
    func progressivePrepareUsesTheCustomLoaderAndReportsEngineSeekability() async throws {
        let resource = InMemoryPlaybackResource(data: makePCMWave(duration: 2))
        #if os(macOS)
        let engine = DulcetAVPlayerEngine()
        #else
        let clock = ManualAVPlayerEngineClock()
        let engine = DulcetAVPlayerEngine(clock: clock, usesAVFoundationMediaStack: false)
        #endif
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }

        let outcome = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan(resource: resource)))
        #expect(outcome == .accepted(commandID: .init("prepare")))

        #if os(macOS)
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
        #else
        #expect(!events.containsReady)
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        #expect(events.containsReady(seekability: .seekable))
        #endif

        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func progressBeginsOnlyAfterMediaTimeAdvancesAndUsesMonotonicSamples() async throws {
        #if os(macOS)
        let engine = DulcetAVPlayerEngine()
        #else
        let startWallClock = Date(timeIntervalSince1970: 1_788_000_000)
        let clock = ManualAVPlayerEngineClock(wallClockNow: startWallClock)
        let engine = DulcetAVPlayerEngine(clock: clock, usesAVFoundationMediaStack: false)
        #endif
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(resource: InMemoryPlaybackResource(data: makePCMWave(duration: 3)))

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        #if os(macOS)
        try await waitUntil("AVPlayer did not become ready") { events.containsReady(seekability: .seekable) }
        #else
        engine.reportCurrentItemReadyForTesting(duration: 3, seekability: .seekable)
        #expect(events.containsReady(seekability: .seekable))
        #endif
        #expect(!events.containsProgressBegan)

        _ = await execute(engine, .play(commandID: .init("play")))
        #if os(macOS)
        try await waitUntil("the monotonic sampler did not observe advancing media time", timeout: 3) {
            events.positionSamples.count >= 2
        }
        #else
        clock.tick(
            isPlaying: true,
            mediaPosition: 0,
            monotonicUptimeNanoseconds: 1_000_000_000
        )
        #expect(!events.containsProgressBegan)
        clock.tick(
            isPlaying: true,
            mediaPosition: 0.5,
            monotonicUptimeNanoseconds: 1_500_000_000
        )
        clock.tick(
            isPlaying: true,
            mediaPosition: 1,
            monotonicUptimeNanoseconds: 2_000_000_000
        )
        #expect(events.positionSamples.map(\.monotonic.uptimeNanoseconds) == [
            1_500_000_000,
            2_000_000_000,
        ])
        #expect(events.progressStartWallClock == startWallClock)
        #endif

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

    #if os(macOS)
    @Test
    func loopbackHTTPServerMatchesNavidromeRangeSemantics() async throws {
        let audio = try navidromeReferenceMP3Fixture()
        #expect(audio.count > 7_000_000)
        let server = try LoopbackRangeHTTPServer(audio: audio)
        defer { server.stop() }

        let twoByteProbe = try await loopbackResponse(from: server.url, range: "bytes=0-1")
        #expect(twoByteProbe.response.statusCode == 206)
        #expect(twoByteProbe.data.count == 2)
        #expect(twoByteProbe.response.value(forHTTPHeaderField: "Content-Length") == "2")
        #expect(
            twoByteProbe.response.value(forHTTPHeaderField: "Content-Range") ==
                "bytes 0-1/\(audio.count)"
        )
        #expect(twoByteProbe.response.value(forHTTPHeaderField: "Accept-Ranges") == "bytes")
        #expect(twoByteProbe.response.value(forHTTPHeaderField: "Content-Type") == "audio/mpeg")

        let firstKilobyte = try await loopbackResponse(from: server.url, range: "bytes=0-1023")
        #expect(firstKilobyte.response.statusCode == 206)
        #expect(firstKilobyte.data.count == 1_024)
        #expect(firstKilobyte.response.value(forHTTPHeaderField: "Content-Length") == "1024")
        #expect(
            firstKilobyte.response.value(forHTTPHeaderField: "Content-Range") ==
                "bytes 0-1023/\(audio.count)"
        )
        #expect(firstKilobyte.response.value(forHTTPHeaderField: "Accept-Ranges") == "bytes")

        let complete = try await loopbackResponse(from: server.url, range: nil)
        #expect(complete.response.statusCode == 200)
        #expect(complete.data.count == audio.count)
        #expect(
            complete.response.value(forHTTPHeaderField: "Content-Length") == String(audio.count)
        )
        #expect(complete.response.value(forHTTPHeaderField: "Content-Range") == nil)
        #expect(complete.response.value(forHTTPHeaderField: "Accept-Ranges") == "bytes")
    }

    @Test
    func loopbackHTTPRangeStreamBecomesReadyAndProgresses() async throws {
        let server = try LoopbackRangeHTTPServer(audio: navidromeReferenceMP3Fixture())
        defer { server.stop() }
        let resource = LoopbackHTTPPlaybackResource(url: server.url)
        let engine = DulcetAVPlayerEngine()
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }

        let playbackPlan = plan(resource: resource, expectedContainer: .mp3)
        let prepare = await execute(
            engine,
            .prepare(commandID: .init("loopback-prepare"), plan: playbackPlan)
        )
        #expect(prepare == .accepted(commandID: .init("loopback-prepare")))
        let play = await execute(engine, .play(commandID: .init("loopback-play")))
        #expect(play == .accepted(commandID: .init("loopback-play")))

        try await waitUntil("AVPlayer did not become ready through loopback HTTP range loading") {
            events.containsReady(seekability: .seekable)
        }
        try await waitUntil("loopback HTTP playback never advanced media time", timeout: 5) {
            events.containsProgressBegan
        }
        #expect(server.requests.count > 0)
        #expect(server.requests.allSatisfy { $0.rangeHeader != nil })
        #expect(server.requests.first?.rangeHeader == "bytes=0-262143")

        _ = await execute(engine, .release(commandID: .init("loopback-release")))
    }
    #endif

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
        // Deactivation is a SIBLING of the teardown event, not its cause, so observing
        // engineTornDown does not guarantee the session has been deactivated yet. Asserting it
        // synchronously passed locally and failed on a loaded CI runner with deactivationCount 0.
        // Waiting on the asserted property keeps the assertion exactly as strong -- the count must
        // still reach 1 -- while not depending on an ordering nothing specifies.
        //
        // The three sibling `#expect(...count...)` assertions after a waitUntil in this file were
        // checked and left alone: each awaits an event that is CAUSED BY the thing it counts, so
        // the count is already guaranteed when the wait returns.
        try await waitUntil("external playback did not deactivate the audio session") {
            audioSession.deactivationCount == 1
        }
        let playOutcome = await execute(engine, .play(commandID: .init("play-after-takeover")))
        #expect(playOutcome == .rejected(commandID: .init("play-after-takeover"), reason: .invalidState))
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func anEmptyQueueDeactivatesAfterTheConfiguredGracePeriod() async throws {
        let audioSession = RecordingAudioSession()
        #if os(macOS)
        // macOS keeps the real path: a real AVQueuePlayer, the real end-of-item notification, and a
        // real 0.05s grace period elapsing. That is the coverage this test exists for.
        let player = AVQueuePlayer()
        let engine = DulcetAVPlayerEngine(
            player: player,
            audioSession: audioSession,
            audioSessionGracePeriod: 0.05
        )
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        let item = try #require(player.currentItem)

        NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: item)
        player.removeAllItems()
        #else
        // The simulator's media stack is the unreliable part, and it is not what this test asserts:
        // the claim is that an empty queue deactivates the session after its grace period. Drive the
        // same itemEnded the notification observer drives, then fire the deadline instead of waiting
        // for wall time. This test timed out at 21.4s on a hosted iOS runner while macOS passed.
        let clock = ManualAVPlayerEngineClock()
        let engine = DulcetAVPlayerEngine(
            clock: clock,
            usesAVFoundationMediaStack: false,
            audioSession: audioSession,
            audioSessionGracePeriod: 0.05
        )
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        engine.reportCurrentItemEndedForTesting()
        #expect(clock.fireScheduledDeadline(), "no grace deadline was scheduled to fire")
        #endif

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
    expectedContainer: DulcetAudioContainer = .wav,
    session: String = "session",
    attempt: String = "attempt",
    title: String = "Playback fixture"
) -> DulcetPlaybackPlan {
    DulcetPlaybackPlan(
        playbackSessionID: .init(session),
        attemptID: .init(attempt),
        deliveryProtocol: deliveryProtocol,
        expectedContainer: expectedContainer,
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

#if os(macOS)
private struct LoopbackHTTPRequest: Sendable {
    let rangeHeader: String?
}

private final class LoopbackRangeHTTPServer: @unchecked Sendable {
    private let audio: Data
    private let listener: NWListener
    private let queue = DispatchQueue(label: "com.legitimateapps.dulcet.tests.http-range-server")
    private let lock = NSLock()
    private var requestStorage: [LoopbackHTTPRequest] = []

    var url: URL {
        URL(string: "http://127.0.0.1:\(listener.port!.rawValue)/tone.mp3")!
    }

    init(audio: Data) throws {
        self.audio = audio
        listener = try NWListener(using: .tcp, on: .any)
        let startup = LoopbackHTTPServerStartup()
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                startup.complete()
            case let .failed(error):
                startup.complete(error: error)
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.start(queue: queue)
        guard startup.wait(timeout: 5) else {
            listener.cancel()
            throw LoopbackHTTPServerError.startupTimedOut
        }
        if let error = startup.error {
            listener.cancel()
            throw error
        }
        guard listener.port != nil else {
            listener.cancel()
            throw LoopbackHTTPServerError.missingPort
        }
    }

    var requests: [LoopbackHTTPRequest] {
        lock.lock()
        defer { lock.unlock() }
        return requestStorage
    }

    func stop() {
        listener.cancel()
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(from: connection, accumulated: Data())
    }

    private func receive(from connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [weak self, weak connection] data, _, isComplete, error in
            guard let self, let connection else { return }
            var requestData = accumulated
            if let data { requestData.append(data) }
            if requestData.range(of: Data("\r\n\r\n".utf8)) != nil {
                respond(to: connection, requestData: requestData)
            } else if error != nil || isComplete {
                connection.cancel()
            } else {
                receive(from: connection, accumulated: requestData)
            }
        }
    }

    private func respond(to connection: NWConnection, requestData: Data) {
        let request = String(decoding: requestData, as: UTF8.self)
        let rangeHeader = request
            .split(separator: "\r\n")
            .first { $0.lowercased().hasPrefix("range:") }
            .map { String($0.dropFirst("range:".count)).trimmingCharacters(in: .whitespaces) }
        lock.lock()
        requestStorage.append(LoopbackHTTPRequest(rangeHeader: rangeHeader))
        lock.unlock()

        let bounds = requestedBounds(from: rangeHeader)
        let body = audio.subdata(in: bounds.start..<(bounds.endInclusive + 1))
        let status = rangeHeader == nil ? "200 OK" : "206 Partial Content"
        var headers = [
            "HTTP/1.1 \(status)",
            "Content-Type: audio/mpeg",
            "Content-Length: \(body.count)",
            "Accept-Ranges: bytes",
            "Connection: close",
        ]
        if rangeHeader != nil {
            headers.append(
                "Content-Range: bytes \(bounds.start)-\(bounds.endInclusive)/\(audio.count)"
            )
        }
        var response = Data((headers.joined(separator: "\r\n") + "\r\n\r\n").utf8)
        response.append(body)
        connection.send(content: response, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func requestedBounds(from rangeHeader: String?) -> (start: Int, endInclusive: Int) {
        guard let rangeHeader,
              rangeHeader.lowercased().hasPrefix("bytes="),
              let separator = rangeHeader.firstIndex(of: "-") else {
            return (0, audio.count - 1)
        }
        let startText = rangeHeader[rangeHeader.index(rangeHeader.startIndex, offsetBy: 6)..<separator]
        let endText = rangeHeader[rangeHeader.index(after: separator)...]
        let start = min(Int(startText) ?? 0, audio.count - 1)
        let requestedEnd = Int(endText) ?? (audio.count - 1)
        return (start, min(max(start, requestedEnd), audio.count - 1))
    }
}

private final class LoopbackHTTPServerStartup: @unchecked Sendable {
    private let lock = NSLock()
    private let semaphore = DispatchSemaphore(value: 0)
    private var completed = false
    private var errorStorage: Error?

    var error: Error? {
        lock.lock()
        defer { lock.unlock() }
        return errorStorage
    }

    func complete(error: Error? = nil) {
        lock.lock()
        guard !completed else {
            lock.unlock()
            return
        }
        completed = true
        errorStorage = error
        lock.unlock()
        semaphore.signal()
    }

    func wait(timeout: TimeInterval) -> Bool {
        semaphore.wait(timeout: .now() + timeout) == .success
    }
}

private enum LoopbackHTTPServerError: Error {
    case startupTimedOut
    case missingPort
    case missingFixture
}

private func loopbackResponse(
    from url: URL,
    range: String?
) async throws -> (data: Data, response: HTTPURLResponse) {
    var request = URLRequest(url: url)
    if let range {
        request.setValue(range, forHTTPHeaderField: "Range")
    }
    let configuration = URLSessionConfiguration.ephemeral
    configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
    configuration.urlCache = nil
    let (data, response) = try await URLSession(configuration: configuration).data(for: request)
    guard let response = response as? HTTPURLResponse else {
        throw URLError(.badServerResponse)
    }
    return (data, response)
}

private func navidromeReferenceMP3Fixture() throws -> Data {
    guard let url = Bundle.module.url(
        forResource: "navidrome-reference",
        withExtension: "mp3",
        subdirectory: "Fixtures"
    ) else {
        throw LoopbackHTTPServerError.missingFixture
    }
    return try Data(contentsOf: url, options: .mappedIfSafe)
}

private final class LoopbackHTTPPlaybackResource: DulcetPlaybackResourceLoading,
    DulcetPlaybackRequestAuthorizing, DulcetPlaybackResponseValidating,
    DulcetPlaybackRedirectEvaluating, @unchecked Sendable {
    private let url: URL
    private lazy var resource = DulcetURLSessionPlaybackResource(
        expectedContainer: .mp3,
        authorizer: self,
        validator: self,
        redirectEvaluator: self
    )

    init(url: URL) {
        self.url = url
    }

    var description: String { "LoopbackHTTPPlaybackResource(<redacted>)" }

    func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        resource.load(request, completion: completion)
    }

    func authorize(
        range: DulcetPlaybackByteRange,
        completion: @escaping @Sendable (
            Result<DulcetPlaybackAuthorizedRequest, DulcetPlaybackFailure>
        ) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        var request = URLRequest(url: url)
        request.setValue("bytes=\(range.start)-\(range.endInclusive)", forHTTPHeaderField: "Range")
        completion(.success(DulcetPlaybackAuthorizedRequest(request: request)))
        return InMemoryPlaybackOperation()
    }

    func validate(
        response: DulcetPlaybackHTTPResponse,
        expectedContainer: DulcetAudioContainer,
        requestedRange: DulcetPlaybackByteRange,
        requiresAudioSignature: Bool
    ) -> DulcetPlaybackResponseValidation {
        guard response.statusCode == 206,
              response.contentType?.lowercased() == "audio/mpeg",
              !response.body.isEmpty,
              response.body.count <= requestedRange.length,
              let totalText = response.contentRange?.split(separator: "/").last,
              let totalLength = Int64(totalText),
              !requiresAudioSignature || response.body.starts(with: Data("ID3".utf8)) else {
            return .rejected(error: .protocolViolation, refreshReason: .validationFailed)
        }
        return .accepted(
            contentInformation: .init(contentLength: totalLength, supportsByteRanges: true)
        )
    }

    func evaluate(
        sourceURL: URL,
        proposedURL: URL,
        redirectsAlreadyFollowed: Int
    ) -> DulcetPlaybackRedirectDecision {
        .reject(.transport)
    }
}

private func makeMP3Tone() -> Data {
    Data(
        base64Encoded: """
        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
        """,
        options: .ignoreUnknownCharacters
    )!
}
#endif

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

    var containsReady: Bool {
        snapshot.contains { if case .ready = $0 { true } else { false } }
    }

    var progressStartWallClock: Date? {
        snapshot.compactMap {
            if case let .playbackProgressBegan(_, wallClock, _) = $0 { wallClock } else { nil }
        }.first
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

#if !os(macOS)
private final class ManualAVPlayerEngineClock: DulcetAVPlayerEngineClock, @unchecked Sendable {
    let wallClockNow: Date

    private let lock = NSLock()
    private var currentSample = DulcetAVPlayerEngineClockSample(
        isPlaying: false,
        mediaPosition: 0,
        monotonicTime: DulcetMonotonicInstant(uptimeNanoseconds: 0)
    )
    private var samplerQueue: DispatchQueue?
    private var samplerHandler: (@Sendable () -> Void)?
    private var deferredQueue: DispatchQueue?
    private var deferredHandler: (@Sendable () -> Void)?

    /// Fire the pending one-shot deadline (the audio-session grace period) without waiting for it.
    /// Returns false when nothing is scheduled, so a test cannot silently pass by firing nothing.
    @discardableResult
    func fireScheduledDeadline() -> Bool {
        lock.lock()
        let queue = deferredQueue
        let handler = deferredHandler
        deferredQueue = nil
        deferredHandler = nil
        lock.unlock()
        guard let queue, let handler else { return false }
        queue.sync(execute: handler)
        return true
    }

    func scheduleOnce(
        on queue: DispatchQueue,
        after _: TimeInterval,
        handler: @escaping @Sendable () -> Void
    ) -> @Sendable () -> Void {
        lock.lock()
        deferredQueue = queue
        deferredHandler = handler
        lock.unlock()
        return { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.deferredQueue = nil
            self.deferredHandler = nil
            self.lock.unlock()
        }
    }

    init(wallClockNow: Date = Date(timeIntervalSince1970: 1_788_000_000)) {
        self.wallClockNow = wallClockNow
    }

    func sample(player _: AVQueuePlayer) -> DulcetAVPlayerEngineClockSample {
        lock.lock()
        defer { lock.unlock() }
        return currentSample
    }

    func startSampler(
        on queue: DispatchQueue,
        interval _: TimeInterval,
        handler: @escaping @Sendable () -> Void
    ) -> @Sendable () -> Void {
        lock.lock()
        samplerQueue = queue
        samplerHandler = handler
        lock.unlock()
        return { [weak self] in
            guard let self else { return }
            lock.lock()
            samplerQueue = nil
            samplerHandler = nil
            lock.unlock()
        }
    }

    func tick(
        isPlaying: Bool,
        mediaPosition: TimeInterval,
        monotonicUptimeNanoseconds: UInt64
    ) {
        lock.lock()
        currentSample = DulcetAVPlayerEngineClockSample(
            isPlaying: isPlaying,
            mediaPosition: mediaPosition,
            monotonicTime: DulcetMonotonicInstant(
                uptimeNanoseconds: monotonicUptimeNanoseconds
            )
        )
        let queue = samplerQueue
        let handler = samplerHandler
        lock.unlock()
        queue?.sync { handler?() }
    }
}
#endif

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
