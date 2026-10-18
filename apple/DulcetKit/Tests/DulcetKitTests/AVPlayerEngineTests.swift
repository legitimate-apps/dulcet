import AVFoundation
import Dispatch
import Foundation
import MediaPlayer
import Testing
@testable import DulcetKit

#if os(macOS)
import CoreAudio
import Network
#endif

/// Real decoded PCM drives the boundary; these fail if overlap is merely reported without
/// running both players, if the gain ignores master volume, or if a tail leaks playback events.
@Suite(.serialized)
struct AVPlayerGainTests {
    /// AVPlayer has been seen to drop a write of `volume` under audio-device load: a newer value
    /// overtaken by an older one, still reported a second later (4 of 200 writes in two loaded
    /// runs before the engine verified its writes). Every gain the engine sets must take effect.
    @Test
    func everyVolumeWriteTakesEffectEvenWhenWritesFollowEachOtherClosely() async throws {
        let engine = DulcetAVPlayerEngine()
        defer { engine.execute(.release(commandID: .init("cleanup"))) { _ in } }
        _ = await execute(engine, .prepare(commandID: .init("prepare"),
            plan: plan(resource: InMemoryPlaybackResource(data: makePCMWave(duration: 30)))))
        _ = await execute(engine, .play(commandID: .init("play")))
        let targets: [Double] = [0, 0.4, 0, 0.02]
        for index in 0..<200 {
            let target = targets[index % targets.count]
            #expect(await execute(engine, .setVolume(commandID: .init("v\(index)"), volume: target)) ==
                .completed(commandID: .init("v\(index)"), result: .withoutData))
            try await waitUntil("write \(index) of \(target) never took effect", engine: engine, timeout: 1,
                                mediaState: { "player_volume=\(engine.currentPlayerVolumeForTesting())" }) {
                engine.currentPlayerVolumeForTesting() == Float(target)
            }
        }
    }
}

@Suite(.serialized)
struct AVPlayerCrossfadeTests {
    @Test
    func crossfadeOverlapsRealPlayersAndEmitsOneBoundaryBeforeTheTailEnds() async throws {
        let fixture = try await CrossfadeFixture.start()
        let engine = fixture.engine
        let queuedIncoming = try #require(fixture.originalPlayer.items().last)
        defer {
            engine.execute(.release(commandID: .init("cleanup"))) { _ in }
            try? FileManager.default.removeItem(at: fixture.localURL)
        }
        // The state the wait observed, not a second read: a loaded host can end the two-second
        // overlap between two reads.
        let state = try await waitForCrossfadeState("crossfade did not start", engine: engine, events: fixture.events) {
            $0.incomingRate > 0 && $0.outgoingRate > 0 && $0.incomingVolume > 0.05 && $0.outgoingVolume > 0.05
        }
        #expect(state.outgoingPosition < 6, "the old decoder must still be before its physical end")
        #expect(abs(state.incomingVolume + state.outgoingVolume - 1) < 0.02)
        #expect(fixture.controls.publications.last?.sessionID == fixture.next.playbackSessionID)
        let beforeDiscardedCallbacks = fixture.events.snapshot.count
        for name in [Notification.Name.AVPlayerItemDidPlayToEndTime,
                     .AVPlayerItemFailedToPlayToEndTime, .AVPlayerItemPlaybackStalled] {
            NotificationCenter.default.post(name: name, object: queuedIncoming)
        }
        // This read drains the engine queue after the notifications above. The discarded
        // queued item must not end, fail or buffer the context now backed by a fresh item.
        _ = engine.crossfadeStateForTesting()
        #expect(!fixture.events.snapshot.dropFirst(beforeDiscardedCallbacks).contains {
            switch $0 {
            case let .endedNaturally(id, _), let .failedBeforeStart(id, _),
                 let .failedAfterPartial(id, _, _), let .buffering(id, _):
                id == fixture.next.attemptID
            default: false
            }
        })
        // AVPlayer can drop a volume write (see the engine's applyFadeVolumes), so these wait for
        // the gains the players report rather than reading them once at the command's completion.
        #expect(await execute(engine, .setVolume(commandID: .init("fade-master"), volume: 0.4)) ==
            .completed(commandID: .init("fade-master"), result: .withoutData))
        try await waitUntil("the overlap's gains never summed to the master volume", engine: engine, timeout: 2,
                            mediaState: { "\(String(describing: engine.crossfadeStateForTesting()))" }) {
            guard let faded = engine.crossfadeStateForTesting() else { return false }
            return abs(faded.incomingVolume + faded.outgoingVolume - 0.4) < 0.02 &&
                faded.incomingVolume > 0 && faded.outgoingVolume > 0
        }
        #expect(await execute(engine, .setVolume(commandID: .init("sleep-silence"), volume: 0)) ==
            .completed(commandID: .init("sleep-silence"), result: .withoutData))
        try await waitUntil("a player stayed audible at master volume zero", engine: engine, timeout: 2,
                            mediaState: { "\(String(describing: engine.crossfadeStateForTesting()))" }) {
            guard let silent = engine.crossfadeStateForTesting() else { return false }
            return silent.incomingVolume == 0 && silent.outgoingVolume == 0
        }
        let boundary = fixture.events.snapshot
        let endIndex = try #require(boundary.firstIndex {
            if case let .endedNaturally(id, _) = $0 { id == fixture.first.attemptID } else { false }
        })
        if case let .endedNaturally(_, position) = boundary[endIndex] {
            // The 50 ms media observer allows 250 ms for decoder/queue scheduling on loaded hosts.
            #expect(abs(position - 4) < 0.25)
        }
        #expect(boundary[endIndex + 1] == .advancedToPreloaded(
            oldAttemptID: fixture.first.attemptID, newAttemptID: fixture.next.attemptID))
        try await waitUntil("tail was not disposed after its fade", engine: engine, timeout: 10) {
            engine.crossfadeStateForTesting() == nil
        }
        let after = fixture.events.snapshot
        #expect(after.filter { if case let .endedNaturally(id, _) = $0 {
            id == fixture.first.attemptID
        } else { false } }.count == 1)
        #expect(after.filter { if case .advancedToPreloaded = $0 { true } else { false } }.count == 1)
        #expect(!after.dropFirst(endIndex + 2).contains { $0.attemptID == fixture.first.attemptID })
        #expect(fixture.originalPlayer.rate == 0)
    }

    @Test
    func aStreamWithoutByteRangesOverlapsWithoutReopeningTheOutgoingResource() async throws {
        let resource = WholeRepresentationPlaybackResource(data: makePCMWave(duration: 6))
        let fixture = try await CrossfadeFixture.start(outgoingResource: resource)
        let engine = fixture.engine
        defer {
            engine.execute(.release(commandID: .init("cleanup"))) { _ in }
            try? FileManager.default.removeItem(at: fixture.localURL)
        }
        try await waitUntil("no-range stream did not start", engine: engine, timeout: 30) {
            fixture.events.containsProgressBegan
        }
        let requestsBeforeBoundary = resource.requestCount
        #expect(requestsBeforeBoundary > 0)
        _ = try await waitForCrossfadeState("no-range stream did not overlap", engine: engine) {
            $0.incomingRate > 0 && $0.outgoingRate > 0 && $0.incomingVolume > 0 && $0.outgoingVolume > 0
        }
        #expect(resource.requestCount == requestsBeforeBoundary,
                "the outgoing stream must keep its decoder, with no reopen or seek request")
    }

    @Test(arguments: ["pause", "stop", "seek", "replace", "release"])
    func transportChangesDisposeTheOutgoingTail(action: String) async throws {
        // The command runs in the engine-queue turn that finds the overlap active. Otherwise a
        // loaded host can let the two-second tail end on its own first, and every expectation
        // below would pass without the command having disposed anything. When the overlap ends
        // before the command can be issued, the scenario did not happen; run it again.
        for attempt in 1...3 {
            let fixture = try await CrossfadeFixture.start(warmTransport: true)
            let engine = fixture.engine
            defer {
                engine.execute(.release(commandID: .init("cleanup"))) { _ in }
                try? FileManager.default.removeItem(at: fixture.localURL)
            }
            let overlap = try await waitForCrossfadeState("no overlap sample reached incoming gain above 0.05", engine: engine, events: fixture.events) {
                $0.incomingVolume > 0.05
            }
            let command: DulcetPlaybackCommand
            switch action {
            case "pause": command = .pause(commandID: .init(action))
            case "stop": command = .stop(commandID: .init(action))
            case "seek": command = .seek(commandID: .init(action), position: 0.5)
            case "replace": command = .replaceCurrent(commandID: .init(action), plan: plan(
                session: "crossfade-b", attempt: "replacement"))
            default: command = .release(commandID: .init(action))
            }
            guard let outcome = await executeDuringOverlap(engine, command) else {
                #expect(attempt < 3, "the overlap ended before the command could be issued, 3 times")
                continue
            }
            print("DULCET CROSSFADE TRANSPORT action=\(action) command-during-overlap=true incoming-gain=\(overlap.incomingVolume) outgoing-gain=\(overlap.outgoingVolume)")
            #expect(!outcome.isRejectedForCrossfadeTest)
            #expect(engine.crossfadeStateForTesting() == nil)
            #expect(fixture.originalPlayer.rate == 0)
            #expect(fixture.originalPlayer.items().isEmpty)
            if ["pause", "stop", "release"].contains(action) {
                #expect(engine.currentPlayerRateForTesting() == 0)
            }
            return
        }
    }

    @Test
    func seekingBackBeforeTheBoundaryKeepsTheCrossfadeArmed() async throws {
        let fixture = try await CrossfadeFixture.start()
        let engine = fixture.engine
        defer {
            engine.execute(.release(commandID: .init("cleanup"))) { _ in }
            try? FileManager.default.removeItem(at: fixture.localURL)
        }
        try await waitUntil("current item was not ready for seeking", engine: engine, timeout: 30) {
            fixture.events.snapshot.contains {
                if case let .ready(id, _, seekability) = $0 {
                    id == fixture.first.attemptID && seekability == .seekable
                } else { false }
            }
        }
        // Seek while paused. Playing, a seek from 3 had one second of media before the boundary at
        // 4; a loaded host kept the backward seek pending for three, the item played out to 6 and
        // AVFoundation failed the seek on the item change. That raced the host, not the engine.
        #expect(await execute(engine, .pause(commandID: .init("hold"))) ==
            .completed(commandID: .init("hold"), result: .withoutData))
        let forward = await execute(engine, .seek(commandID: .init("forward"), position: 3))
        #expect(forward == .completed(commandID: .init("forward"), result: .withoutData), "forward=\(forward)")
        let back = await execute(engine, .seek(commandID: .init("back"), position: 0))
        #expect(back == .completed(commandID: .init("back"), result: .withoutData),
                "back=\(back); events=\(fixture.events.snapshot)")
        #expect(!fixture.events.containsAdvance(old: fixture.first.attemptID, new: fixture.next.attemptID))
        #expect(await execute(engine, .play(commandID: .init("resume"))) == .accepted(commandID: .init("resume")))
        try await waitUntil("backward seek lost the armed boundary", engine: engine, timeout: 30) {
            engine.crossfadeStateForTesting()?.incomingRate ?? 0 > 0
        }
        let ended = try #require(fixture.events.snapshot.first {
            if case let .endedNaturally(id, _) = $0 { id == fixture.first.attemptID } else { false }
        })
        if case let .endedNaturally(_, position) = ended { #expect(abs(position - 4) < 0.25) }
    }

    @Test(arguments: [false, true])
    func aBoundaryCallbackCannotOvertakeAPendingSeekOrReplayAfterIt(replaceDuringSeek: Bool) async throws {
        let player = DeferredCrossfadeSeekPlayer()
        let fixture = try await CrossfadeFixture.start(player: player)
        let engine = fixture.engine
        defer {
            engine.execute(.release(commandID: .init("cleanup"))) { _ in }
            try? FileManager.default.removeItem(at: fixture.localURL)
        }
        try await waitUntil("both real items were not ready", engine: engine, timeout: 30) {
            player.items().count == 2 && player.items().allSatisfy { $0.status == .readyToPlay }
        }
        player.holdSeek()
        let seeking = Task { await execute(engine, .seek(commandID: .init("held-seek"), position: 0)) }
        try await waitUntil("seek was not held", engine: engine) { player.hasHeldSeek }
        // Supply the same boundary timestamp AVFoundation can enqueue while seek completion
        // is outstanding. The stored callback remains the pre-seek observer after rearming.
        player.deliverOriginalTimestamp(4.1)
        #expect(engine.crossfadeStateForTesting() == nil)
        #expect(!fixture.events.containsAdvance(old: fixture.first.attemptID, new: fixture.next.attemptID))
        _ = await execute(engine, .setCrossfade(commandID: .init("policy-during-seek"),
            attemptID: fixture.first.attemptID, duration: 2))
        player.captureLatestCallbackForReplay()
        if replaceDuringSeek {
            let replacement = plan(resource: DulcetLocalFilePlaybackResource(
                fileURL: fixture.localURL, exactByteLength: Int64(makePCMWave(duration: 6).count)),
                session: "crossfade-a", attempt: "replacement-during-seek")
            #expect(await execute(engine, .replaceCurrent(commandID: .init("replace-seeking"), plan: replacement)) ==
                .accepted(commandID: .init("replace-seeking")))
            _ = await execute(engine, .setCrossfade(commandID: .init("rearm-replacement"),
                attemptID: replacement.attemptID, duration: 2))
        }
        player.finishSeek()
        let expected: DulcetPlaybackCommandOutcome = replaceDuringSeek
            ? .rejected(commandID: .init("held-seek"), reason: .invalidState)
            : .completed(commandID: .init("held-seek"), result: .withoutData)
        #expect(await seeking.value == expected)
        player.deliverOriginalTimestamp(4.1)
        #expect(engine.crossfadeStateForTesting() == nil)
        #expect(!fixture.events.containsAdvance(old: fixture.first.attemptID, new: fixture.next.attemptID))
        try await waitUntil("seek did not rearm a fresh observer", engine: engine, timeout: 30) {
            engine.crossfadeStateForTesting()?.incomingRate ?? 0 > 0
        }
    }

    @Test
    func staleAndInvalidCrossfadeCommandsAreRejectedAndZeroKeepsGapless() async throws {
        let player = AVQueuePlayer()
        player.volume = 0.7
        let engine = DulcetAVPlayerEngine(player: player, systemMediaControls: RecordingSystemMediaControls())
        defer { engine.execute(.release(commandID: .init("cleanup"))) { _ in } }
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let first = plan(resource: InMemoryPlaybackResource(data: makePCMWave(duration: 6)))
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: first))
        #expect(await execute(engine, .setCrossfade(commandID: .init("stale"),
            attemptID: .init("stale"), duration: 1)) ==
            .rejected(commandID: .init("stale"), reason: .invalidState))
        #expect(await execute(engine, .setCrossfade(commandID: .init("invalid"),
            attemptID: first.attemptID, duration: .nan)) ==
            .rejected(commandID: .init("invalid"), reason: .invalidState))
        _ = await execute(engine, .setCrossfade(commandID: .init("arm"), attemptID: first.attemptID, duration: 1))
        #expect(await execute(engine, .setCrossfade(commandID: .init("zero"),
            attemptID: first.attemptID, duration: 0)) ==
            .completed(commandID: .init("zero"), result: .withoutData))
        let next = plan(session: "next", attempt: "next")
        _ = await execute(engine, .preloadNext(commandID: .init("preload"), plan: next))
        _ = await execute(engine, .play(commandID: .init("play")))
        try await waitUntil("gapless item never advanced", engine: engine, timeout: 30) {
            events.containsAdvance(old: first.attemptID, new: next.attemptID)
        }
        #expect(engine.crossfadeStateForTesting() == nil)
        #expect(player.currentItem != nil, "gapless continues on the original queue")
        #expect(abs(player.volume - 0.7) < 0.001, "zero keeps the existing player's gain")
        let ended = try #require(events.snapshot.first {
            if case let .endedNaturally(id, _) = $0 { id == first.attemptID } else { false }
        })
        if case let .endedNaturally(_, position) = ended { #expect(abs(position - 6) < 0.1) }
    }
}

/// Retains real AVFoundation decoding while controlling only the interleaving of a seek
/// completion and an already-created media observer. Callbacks always run on the engine queue.
private final class DeferredCrossfadeSeekPlayer: AVQueuePlayer, @unchecked Sendable {
    private nonisolated let seekLock = NSLock()
    private nonisolated(unsafe) var shouldHold = false
    private nonisolated(unsafe) var heldSeek: (CMTime, CMTime, CMTime, @Sendable (Bool) -> Void)?
    private nonisolated(unsafe) var latestCallback: (@Sendable (CMTime) -> Void)?
    private nonisolated(unsafe) var originalCallback: (@Sendable (CMTime) -> Void)?
    private nonisolated(unsafe) var callbackQueue: DispatchQueue?

    nonisolated func holdSeek() {
        seekLock.withLock { shouldHold = true; originalCallback = latestCallback }
    }
    nonisolated var hasHeldSeek: Bool { seekLock.withLock { heldSeek != nil } }
    nonisolated func captureLatestCallbackForReplay() {
        seekLock.withLock { originalCallback = latestCallback }
    }

    nonisolated override func seek(
        to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime,
        completionHandler: @escaping @Sendable (Bool) -> Void
    ) {
        let hold = seekLock.withLock {
            if shouldHold {
                heldSeek = (time, toleranceBefore, toleranceAfter, completionHandler)
                shouldHold = false
                return true
            }
            return false
        }
        if !hold {
            super.seek(to: time, toleranceBefore: toleranceBefore, toleranceAfter: toleranceAfter,
                       completionHandler: completionHandler)
        }
    }

    nonisolated func finishSeek() {
        let held = seekLock.withLock { let value = heldSeek; heldSeek = nil; return value }
        guard let (time, before, after, completion) = held else { return }
        super.seek(to: time, toleranceBefore: before, toleranceAfter: after, completionHandler: completion)
    }

    nonisolated override func addPeriodicTimeObserver(
        forInterval interval: CMTime, queue: DispatchQueue?, using block: @escaping @Sendable (CMTime) -> Void
    ) -> Any {
        seekLock.withLock {
            latestCallback = block
            callbackQueue = queue
        }
        return super.addPeriodicTimeObserver(forInterval: interval, queue: queue, using: block)
    }

    nonisolated func deliverOriginalTimestamp(_ seconds: Double) {
        let (queue, callback) = seekLock.withLock { (callbackQueue, originalCallback) }
        queue?.sync { callback?(CMTime(seconds: seconds, preferredTimescale: 1_000)) }
    }
}

private extension DulcetPlaybackCommandOutcome {
    var isRejectedForCrossfadeTest: Bool {
        if case .rejected = self { return true }
        return false
    }
}

private struct CrossfadeFixture {
    let localURL: URL
    let engine: DulcetAVPlayerEngine
    let originalPlayer: AVQueuePlayer
    let events: PlaybackEventRecorder
    let controls: RecordingSystemMediaControls
    let first: DulcetPlaybackPlan
    let next: DulcetPlaybackPlan

    static func start(
        player: AVQueuePlayer = AVQueuePlayer(),
        outgoingResource: (any DulcetPlaybackResourceLoading)? = nil,
        warmTransport: Bool = false
    ) async throws -> CrossfadeFixture {
        let controls = RecordingSystemMediaControls()
        let engine = DulcetAVPlayerEngine(player: player, systemMediaControls: controls)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let outgoingDuration: TimeInterval = warmTransport ? 12 : 6
        let bytes = makePCMWave(duration: outgoingDuration)
        let localURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".wav")
        try bytes.write(to: localURL)
        var handedOff = false
        defer {
            if !handedOff {
                engine.execute(.release(commandID: .init("failed-fixture-cleanup"))) { _ in }
                try? FileManager.default.removeItem(at: localURL)
            }
        }
        let first = plan(resource: outgoingResource ?? DulcetLocalFilePlaybackResource(
            fileURL: localURL, exactByteLength: Int64(bytes.count)),
            session: "crossfade-a", attempt: "crossfade-a")
        let next = plan(resource: InMemoryPlaybackResource(data: makePCMWave(duration: 6)),
                        session: "crossfade-b", attempt: "crossfade-b")
        try #require(await execute(engine, .prepare(commandID: .init("prepare"), plan: first)) ==
            .accepted(commandID: .init("prepare")))
        try #require(await execute(engine, .preloadNext(commandID: .init("preload"), plan: next)) ==
            .accepted(commandID: .init("preload")))
        if warmTransport {
            // A paused AVQueuePlayer leaves its second item unknown, even after successful
            // preroll of the current item. Warm both actual items with real playback and
            // crossfade disarmed, then hold this seekable local fixture. The longer file gives
            // preparation its own clock; the staged playback window begins six seconds before the end.
            let items = player.items()
            try #require(items.count == 2, "The real player must hold both fixture items")
            try #require(await execute(engine, .play(commandID: .init("warm"))) ==
                .accepted(commandID: .init("warm")))
            let ready = { @Sendable in
                items.allSatisfy { $0.status == .readyToPlay } && player.currentItem === items[0]
            }
            try await waitUntil("playing transport fixture did not prepare both real items", engine: engine,
                                timeout: 30,
                                mediaState: { "statuses=\(items.map { $0.status.rawValue }) rate=\(player.rate) position=\(CMTimeGetSeconds(player.currentTime()))" },
                                condition: ready)
            try #require(ready(), "Both actual items must be ready with the original still current")
            try #require(await execute(engine, .pause(commandID: .init("hold-warm"))) ==
                .completed(commandID: .init("hold-warm"), result: .withoutData))
            try #require(player.rate == 0 && player.currentItem === items[0],
                         "Preparation must hold the original before it advances")
            let warmedPosition = CMTimeGetSeconds(player.currentTime())
            let start = outgoingDuration - 6
            try #require(await execute(engine, .seek(commandID: .init("stage-boundary"), position: start)) ==
                .completed(commandID: .init("stage-boundary"), result: .withoutData))
            try #require(ready() && player.rate == 0 && abs(CMTimeGetSeconds(player.currentTime()) - start) < 0.05,
                         "The real paused seek must leave six seconds before the original ends")
            print("DULCET CROSSFADE PREPARATION ready-items=2 paused=true warmed-position=\(warmedPosition) outgoing-position=\(CMTimeGetSeconds(player.currentTime())) remaining=6")
        }
        try #require(await execute(engine, .setCrossfade(commandID: .init("crossfade"),
            attemptID: first.attemptID, duration: 2)) ==
            .completed(commandID: .init("crossfade"), result: .withoutData))
        try #require(await execute(engine, .play(commandID: .init("play"))) ==
            .accepted(commandID: .init("play")))
        handedOff = true
        return Self(localURL: localURL, engine: engine, originalPlayer: player, events: events, controls: controls,
                    first: first, next: next)
    }
}

@Suite(.serialized)
struct AVPlayerResourceLoaderIntegrationTests {
    @Test
    func progressivePrepareRoutesAVFoundationRequestsThroughTheCustomLoader() async throws {
        let resource = InMemoryPlaybackResource(data: makePCMWave(duration: 2))
        let engine = DulcetAVPlayerEngine()

        let outcome = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan(resource: resource)))
        #expect(outcome == .accepted(commandID: .init("prepare")))
        let playOutcome = await execute(engine, .play(commandID: .init("play")))
        #expect(playOutcome == .accepted(commandID: .init("play")))

        // This is intentionally an AVFoundation integration assertion on every Apple target. It
        // requests playback and stops at the first resource request rather than waiting for
        // simulator media readiness, which is a separate engine-state concern pinned below with
        // the manual clock.
        try await waitUntil(
            "AVFoundation did not route through the custom resource loader",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
            !resource.requests.isEmpty
        }
        #expect(
            resource.requests.count > 0,
            Comment(rawValue: "custom loader received \(resource.requests.count) requests")
        )
        #expect(resource.requests.first?.range.start == 0)
        #expect(resource.requests.first?.requiresAudioSignature == true)

        _ = await execute(engine, .release(commandID: .init("release")))
    }
}

#if os(macOS)
/// Spec §12.5: a cold transcode honours no range. Navidrome answers the loader's first ranged read
/// with HTTP 200 and the whole representation (OBSERVED against Navidrome 0.63.2: a 2-minute
/// FLAC capped at 96 kbps answered `bytes=0-262143` with all 1,440,509 bytes). That body is longer
/// than the chunk the loader asked for and is still the resource from its first byte, so the item
/// must become ready from it rather than fail.
@Suite(.serialized)
struct AVPlayerWholeRepresentationTests {
    @Test
    func aWholeRepresentationLongerThanTheChunkMakesTheItemReady() async throws {
        let wave = makePCMWave(duration: 20)
        #expect(Int64(wave.count) > DulcetAVAssetResourceLoaderDelegate.maximumChunkLength,
                "The control: the body must outgrow one chunk")
        let resource = WholeRepresentationPlaybackResource(data: wave)
        let engine = DulcetAVPlayerEngine()
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let failures: @Sendable () -> [DulcetPlaybackFailure] = {
            events.snapshot.compactMap { event in
                switch event {
                case let .failedBeforeStart(_, error), let .failedAfterPartial(_, _, error): error
                default: nil
                }
            }
        }

        let ready: @Sendable () -> Bool = {
            events.snapshot.contains { if case .ready = $0 { true } else { false } }
        }

        _ = await execute(engine, .prepare(commandID: .init("whole-prepare"), plan: plan(resource: resource)))
        try await waitUntil(
            "AVPlayer neither became ready nor failed on a whole-representation answer",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
            ready() || !failures().isEmpty
        }
        #expect(failures().isEmpty, "reported \(failures())")
        #expect(ready())
        #expect(resource.requestCount > 0)
        _ = await execute(engine, .release(commandID: .init("whole-release")))
    }
}

#endif

/// Answers every read, whatever its range, with the whole representation and no range support,
/// as a server does for a transcode it has not cached.
private final class WholeRepresentationPlaybackResource: DulcetPlaybackResourceLoading, @unchecked Sendable {
    private let data: Data
    private let lock = NSLock()
    private var requests = 0

    init(data: Data) {
        self.data = data
    }

    var description: String { "WholeRepresentationPlaybackResource(<redacted>)" }

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
        guard request.range.start == 0 else {
            completion(.failed(error: .protocolViolation, refreshReason: .validationFailed))
            return InMemoryPlaybackOperation()
        }
        completion(.loaded(
            data: data,
            contentInformation: .init(contentLength: Int64(data.count), supportsByteRanges: false)
        ))
        return InMemoryPlaybackOperation()
    }
}

/// Spec §12.12: a failure that belongs to the item moves the queue past it. AVFoundation failing
/// to decode an item's own media is that failure, so the engine must name it `.undecodable`
/// (which the core reads as `Playback.NoPlayableSource`), never `.engine`, which the queue stops on.
@Suite(.serialized)
struct AVPlayerDecodeFailureTests {
    /// Force the public state from the hosted failure: Play accepted, ready item, transport
    /// paused, no progress and no verdict. The recovery must ask the real decoder to render.
    @Test
    func lostStartupPlaySurfacesTheRealDecoderFailure() async throws {
        let player = StartupPlayDroppingPlayer(dropCount: 1)
        let engine = DulcetAVPlayerEngine(player: player)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let resource = SuspendedPlaybackResource()
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan(
            resource: resource, expectedContainer: .mp3
        )))
        _ = await execute(engine, .play(commandID: .init("play")))
        try await waitUntil("the decoder never requested the bytes", engine: engine,
                            timeout: realAVFoundationProgressTimeout) { resource.requestCount > 0 }
        resource.resume(with: mp3FramesWithUndecodablePayloads())
        try await waitUntil("the recovery never reached the real decoder", engine: engine,
                            timeout: undecodableVerdictTimeout) {
            events.snapshot.contains { if case .failedBeforeStart(_, .undecodable) = $0 { true } else { false } }
        }
        #expect(player.playRequestCount == 2, "one requested Play and one transport recovery")
        #expect(!events.containsProgressBegan)
        #expect(events.snapshot.filter { if case .failedBeforeStart = $0 { true } else { false } }.count == 1)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func lostStartupPlayStartsValidMediaAfterDelayedBytes() async throws {
        let player = StartupPlayDroppingPlayer(dropCount: 1)
        let engine = DulcetAVPlayerEngine(player: player)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let resource = SuspendedPlaybackResource()
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan(resource: resource)))
        _ = await execute(engine, .play(commandID: .init("play")))
        try await waitUntil("the loader never requested delayed bytes", engine: engine,
                            timeout: realAVFoundationProgressTimeout) { resource.requestCount > 0 }
        #expect(!events.containsReady)
        #expect(player.playRequestCount == 1)
        resource.resume(with: makePCMWave(duration: 4))
        try await waitUntil("the recovered valid item never progressed", engine: engine,
                            timeout: realAVFoundationProgressTimeout) { events.containsProgressBegan }
        #expect(player.playRequestCount == 2)
        #expect(!events.snapshot.contains { if case .failedBeforeStart = $0 { true } else { false } })
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test(arguments: DecodePlayTiming.allCases)
    func mediaAVFoundationCannotDecodeFailsAsUndecodable(playTiming: DecodePlayTiming) async throws {
        let bytes = mp3FramesWithUndecodablePayloads()
        let immediate = InMemoryPlaybackResource(data: bytes)
        let suspended = SuspendedPlaybackResource()
        let resource: any DulcetPlaybackResourceLoading = playTiming == .beforeBytes ? suspended : immediate
        let requestCount: @Sendable () -> Int = {
            playTiming == .beforeBytes ? suspended.requestCount : immediate.requests.count
        }
        let player = AVQueuePlayer()
        let engine = DulcetAVPlayerEngine(player: player)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(resource: resource, expectedContainer: .mp3)

        let prepareOutcome = await execute(engine, .prepare(commandID: .init("undecodable-prepare"), plan: playbackPlan))
        #expect(prepareOutcome == .accepted(commandID: .init("undecodable-prepare")))
        let item = try #require(player.items().first)
        let diagnostics = DecodeVerdictDiagnostics(player: player, item: item)
        defer { diagnostics.invalidate() }
        if playTiming == .afterReady {
            try await waitUntil(
                "AVFoundation never made the item ready for the play-after-ready control",
                engine: engine,
                timeout: realAVFoundationProgressTimeout,
                mediaState: { diagnostics.describe() }
            ) { events.containsReady }
        }
        let playOutcome = await execute(engine, .play(commandID: .init("undecodable-play")))
        #expect(playOutcome == .accepted(commandID: .init("undecodable-play")))
        if playTiming == .beforeBytes {
            // Force play to arrive while the loader has not delivered even the header. This
            // controls the ordering without relying on a host being slow enough to hit it.
            try await waitUntil(
                "AVFoundation never requested the suspended undecodable bytes",
                engine: engine,
                timeout: realAVFoundationProgressTimeout,
                mediaState: { diagnostics.describe() }
            ) { suspended.requestCount > 0 }
            #expect(item.status == .unknown)
            suspended.resume(with: bytes)
        }
        let mediaState = PlayerMediaState(player: player, first: item, next: nil)
        let failures: @Sendable () -> [DulcetPlaybackFailure] = {
            events.snapshot.compactMap { event in
                switch event {
                case let .failedBeforeStart(_, error), let .failedAfterPartial(_, _, error): error
                default: nil
                }
            }
        }
        let describeState: @Sendable () -> String = {
            "\(mediaState.describe()) item_status=\(item.status.rawValue) " +
                "item_error=\(item.error.map { ($0 as NSError).code.description } ?? "none") " +
                "requests=\(requestCount()) events=\(events.snapshot.map(eventName)) " +
                "play_timing=\(playTiming.rawValue) \(diagnostics.describe())"
        }
        let verdictArrived: @Sendable () -> Bool = {
            mediaState.observe()
            return !failures().isEmpty
        }
        // AVFoundation reaches this verdict by rendering, and on a hosted simulator that can take
        // well over a minute while the engine queue answers in milliseconds. OBSERVED in CI: about
        // 65-70 s in #154 and #159, and about 88 s on #164's run 36660636272, where an independent
        // AudioQueue primed silence in 0.016 s once the old 60 s budget ran out -- so a working host audio output
        // does not bound the verdict's latency, and a probe of it cannot decide pass or fail.
        // Run 37944337563 subsequently emitted no verdict at all on tvOS Simulator within 270 s.
        // That remains undiagnosed: the independent signals below distinguish an engine missing
        // a notification from AVFoundation publishing none during observation (after prepare,
        // before play, through the verdict wait). Slow prior runs do not explain a paused player
        // at time zero. The deadline still has teeth: a verdict that never arrives, or arrives
        // as anything but `.undecodable`, fails.
        try await waitUntil(
            "AVFoundation never reported the undecodable item as failed",
            engine: engine,
            timeout: undecodableVerdictTimeout,
            mediaState: describeState,
            condition: verdictArrived
        )
        // The experiment is the one intended: the item was read through the loader, so the
        // failure is AVFoundation's verdict on these bytes, not a refusal before it saw them.
        #expect(requestCount() > 0)
        #expect(failures() == [.undecodable], "reported \(failures())")
        #expect(!events.containsProgressBegan)
        print("DULCET UNDECODABLE ENGINE OBSERVED failures=\(failures()) \(describeState())")
        _ = await execute(engine, .release(commandID: .init("undecodable-release")))
    }

    @Test
    func decodeDiagnosticsCaptureFailureSignalsWithoutCredentialStrings() {
        let item = AVPlayerItem(url: URL(string: "dulcet-test://diagnostics")!)
        let diagnostics = DecodeVerdictDiagnostics(player: AVQueuePlayer(), item: item)
        defer { diagnostics.invalidate() }
        let canary = "diagnostic-credential-canary"
        let error = NSError(
            domain: AVFoundationErrorDomain,
            code: AVError.Code.decodeFailed.rawValue,
            userInfo: [NSLocalizedDescriptionKey: canary,
                       NSUnderlyingErrorKey: NSError(domain: canary, code: -7)]
        )
        NotificationCenter.default.post(
            name: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            userInfo: [AVPlayerItemFailedToPlayToEndTimeErrorKey: error]
        )
        let description = diagnostics.describe()
        #expect(description.contains("AVPlayerItemFailedToPlayToEndTimeNotification error=AVFoundation:-11821/other:-7"))
        #expect(!description.contains(canary))
        // Transport noise may fill the bounded history before the wait expires. Keep the
        // failure evidence independently, or a missing engine event could look like a missing
        // AVFoundation notification once the history rolls over.
        for _ in 0..<40 {
            NotificationCenter.default.post(name: .AVPlayerItemPlaybackStalled, object: item)
        }
        let filled = diagnostics.describe()
        #expect(filled.contains("AVPlayerItemFailedToPlayToEndTimeNotification=1"))
        #expect(filled.contains("AVPlayerItemPlaybackStalledNotification=40"))
        #expect(filled.contains("latest_failure=AVFoundation:-11821/other:-7"))
        #expect(!filled.contains(canary))
        diagnostics.invalidate()
        NotificationCenter.default.post(name: .AVPlayerItemPlaybackStalled, object: item)
        #expect(diagnostics.describe() == filled, "invalidated diagnostics kept receiving notifications")
    }
}

@Suite(.serialized)
struct AVPlayerStartupRecoveryTests {
    @Test
    func remoteToggleGetsAFreshRecoveryAfterADeliberatePause() async throws {
        let clock = ManualAVPlayerEngineClock()
        let player = StartupPlayDroppingPlayer(dropCount: .max)
        let controls = RecordingSystemMediaControls()
        let engine = DulcetAVPlayerEngine(player: player, clock: clock, usesAVFoundationMediaStack: false,
                                         systemMediaControls: controls)
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        #expect(controls.send(.toggle(sessionID: .init("session"))))
        #expect(clock.fireScheduledDeadline())
        #expect(player.playRequestCount == 2)
        #expect(clock.scheduledDelay == DulcetAVPlayerEngine.startupRecoveryVerdictBudget - 2)
        #expect(controls.send(.toggle(sessionID: .init("session"))))
        #expect(!clock.fireScheduledDeadline())
        #expect(controls.send(.toggle(sessionID: .init("session"))))
        #expect(clock.scheduledDelay == DulcetAVPlayerEngine.startupRecoveryDelay)
        #expect(clock.fireScheduledDeadline())
        #expect(player.playRequestCount == 4)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func waitingForMediaDoesNotReassertPlayButHasAnAbsoluteFailureDeadline() async throws {
        let clock = ManualAVPlayerEngineClock()
        let player = StartupPlayDroppingPlayer(dropCount: .max, transportOverride: .waitingToPlayAtSpecifiedRate)
        let engine = DulcetAVPlayerEngine(player: player, clock: clock, usesAVFoundationMediaStack: false)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        _ = await execute(engine, .play(commandID: .init("play")))
        engine.reportPlayerTransportChangedForTesting()
        #expect(clock.fireScheduledDeadline())
        #expect(player.playRequestCount == 1)
        clock.advanceMonotonic(by: 118)
        #expect(clock.fireScheduledDeadline())
        #expect(clock.monotonicNow == 120)
        #expect(events.snapshot.last == .failedBeforeStart(attemptID: .init("attempt"), error: .engine))
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func aMissingVerdictIsBoundedAndIsNotInventedAsADecodeFailure() async throws {
        let clock = ManualAVPlayerEngineClock()
        let player = StartupPlayDroppingPlayer(dropCount: .max)
        let engine = DulcetAVPlayerEngine(player: player, clock: clock, usesAVFoundationMediaStack: false)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan(
            resource: InMemoryPlaybackResource(data: makePCMWave(duration: 2))
        )))
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        _ = await execute(engine, .play(commandID: .init("play")))
        #expect(clock.scheduledDelay == DulcetAVPlayerEngine.startupRecoveryDelay)
        #expect(clock.fireScheduledDeadline())
        #expect(player.playRequestCount == 2)
        #expect(clock.scheduledDelay == DulcetAVPlayerEngine.startupRecoveryVerdictBudget - 2)
        #expect(clock.fireScheduledDeadline())
        #expect(events.snapshot.last == .failedBeforeStart(attemptID: .init("attempt"), error: .engine))
        engine.reportPlayerTransportChangedForTesting()
        #expect(!clock.fireScheduledDeadline(), "a failed attempt must not recover again")
        #expect(events.snapshot.filter { if case .failedBeforeStart = $0 { true } else { false } }.count == 1)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test(arguments: [false, true])
    func anOpenReadDefersRecoveryButCannotExtendThePlayBudget(completeRead: Bool) async throws {
        let clock = ManualAVPlayerEngineClock()
        let player = StartupPlayDroppingPlayer(dropCount: .max)
        let resource = SuspendedPlaybackResource()
        let engine = DulcetAVPlayerEngine(player: player, clock: clock, usesAVFoundationMediaStack: true)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan(resource: resource)))
        _ = await execute(engine, .play(commandID: .init("play")))
        // This is a real AVAssetResourceLoadingRequest whose resource callback is held open.
        try await waitUntil("AVFoundation never opened the suspended read", engine: engine,
                            timeout: realAVFoundationProgressTimeout) { resource.requestCount > 0 }
        #expect(engine.currentItemHasPendingRequestsForTesting())
        // Inject Ready while keeping the real read open, so the pending-read guard itself
        // (rather than readiness gating) must prevent the otherwise eligible reassertion.
        engine.reportCurrentItemReadyForTesting(duration: 4, seekability: .seekable)
        #expect(clock.fireScheduledDeadline())
        #expect(clock.monotonicNow == 2)
        #expect(player.playRequestCount == 1, "an outstanding read must defer transport recovery")
        #expect(!events.snapshot.contains { if case .failedBeforeStart = $0 { true } else { false } })
        if completeRead {
            resource.resume(with: makePCMWave(duration: 4))
            try await waitUntil("the suspended read never finished", engine: engine,
                                timeout: realAVFoundationProgressTimeout) {
                events.containsReady && !engine.currentItemHasPendingRequestsForTesting()
            }
            #expect(clock.fireScheduledDeadline())
            #expect(clock.monotonicNow == 4)
            #expect(player.playRequestCount == 2, "delivery must allow the one recovery")
        }
        clock.advanceMonotonic(by: 119 - clock.monotonicNow)
        #expect(clock.fireScheduledDeadline())
        // If still polling an open read, the final callback is the remaining one second.
        if !completeRead { #expect(clock.fireScheduledDeadline()) }
        #expect(clock.monotonicNow == 120)
        #expect(events.snapshot.last == .failedBeforeStart(attemptID: .init("attempt"), error: .engine))
        if !completeRead {
            #expect(engine.currentItemHasPendingRequestsForTesting(), "no completion or didCancel rescued this read")
            #expect(player.playRequestCount == 1)
        }
        #expect(!clock.fireScheduledDeadline())
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func transportFlapsAndRepeatedPlayCannotResetTheDeadline() async throws {
        let clock = ManualAVPlayerEngineClock()
        let player = StartupPlayDroppingPlayer(dropCount: .max)
        let engine = DulcetAVPlayerEngine(player: player, clock: clock, usesAVFoundationMediaStack: false)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        _ = await execute(engine, .play(commandID: .init("play")))
        #expect(clock.fireScheduledDeadline())
        clock.advanceMonotonic(by: 58)
        player.setTransportOverride(.waitingToPlayAtSpecifiedRate)
        engine.reportPlayerTransportChangedForTesting()
        clock.advanceMonotonic(by: 59)
        player.setTransportOverride(.paused)
        engine.reportPlayerTransportChangedForTesting()
        _ = await execute(engine, .play(commandID: .init("play-again")))
        #expect(clock.fireScheduledDeadline())
        #expect(clock.monotonicNow == 120)
        #expect(events.snapshot.last == .failedBeforeStart(attemptID: .init("attempt"), error: .engine))
        #expect(!clock.fireScheduledDeadline())
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test(arguments: StartupRecoveryCancellation.allCases)
    func deliberateOrTerminalStateCancelsThePendingRecovery(reason: StartupRecoveryCancellation) async throws {
        let clock = ManualAVPlayerEngineClock()
        let player = StartupPlayDroppingPlayer(dropCount: .max)
        let audioSession = RecordingAudioSession()
        let engine = DulcetAVPlayerEngine(player: player, clock: clock, usesAVFoundationMediaStack: false,
                                         audioSession: audioSession)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(resource: InMemoryPlaybackResource(data: makePCMWave(duration: 2)))
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        _ = await execute(engine, .play(commandID: .init("play")))
        #expect(clock.scheduledDelay == DulcetAVPlayerEngine.startupRecoveryDelay)
        switch reason {
        case .pause: _ = await execute(engine, .pause(commandID: .init("pause")))
        case .interruption:
            audioSession.publish(.interruptionBegan)
            _ = await engine.probeQueueLivenessForTesting()
        case .route:
            audioSession.publish(.routeChanged(old: .bluetooth, new: .builtIn, becomingNoisy: true))
            _ = await engine.probeQueueLivenessForTesting()
        case .progress:
            clock.tick(isPlaying: true, mediaPosition: 0.5, monotonicUptimeNanoseconds: 500_000_000)
        case .failure: engine.reportCurrentItemFailedForTesting(.undecodable)
        case .replacement:
            _ = await execute(engine, .replaceCurrent(commandID: .init("replace"), plan: plan(
                resource: InMemoryPlaybackResource(data: makePCMWave(duration: 2)), attempt: "replacement"
            )))
        case .release: _ = await execute(engine, .release(commandID: .init("release")))
        }
        engine.reportPlayerTransportChangedForTesting()
        #expect(!clock.fireScheduledDeadline())
        #expect(player.playRequestCount == 1)
        #expect(!events.snapshot.contains { if case .failedBeforeStart(_, .engine) = $0 { true } else { false } })
        _ = await execute(engine, .release(commandID: .init("cleanup")))
    }
}

enum StartupRecoveryCancellation: CaseIterable, Sendable {
    case pause, interruption, route, progress, failure, replacement, release
}

/// Injects a dropped initial Play; that cause of the hosted tvOS state remains ASSUMED.
/// Subsequent calls use AVFoundation unchanged, including the decoder's real error notification.
private final class StartupPlayDroppingPlayer: AVQueuePlayer, @unchecked Sendable {
    private nonisolated let lock = NSLock()
    private nonisolated let dropCount: Int
    private nonisolated(unsafe) var transportOverride: AVPlayer.TimeControlStatus? // protected by lock
    private nonisolated(unsafe) var count = 0 // protected by lock

    nonisolated init(dropCount: Int, transportOverride: AVPlayer.TimeControlStatus? = nil) {
        self.dropCount = dropCount
        self.transportOverride = transportOverride
        super.init()
    }

    nonisolated override var timeControlStatus: AVPlayer.TimeControlStatus {
        lock.lock()
        let value = transportOverride
        lock.unlock()
        return value ?? super.timeControlStatus
    }

    nonisolated func setTransportOverride(_ value: AVPlayer.TimeControlStatus) {
        lock.lock()
        transportOverride = value
        lock.unlock()
    }

    nonisolated var playRequestCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    nonisolated override func playImmediately(atRate rate: Float) {
        lock.lock()
        count += 1
        let drop = count <= dropCount
        lock.unlock()
        if !drop { super.playImmediately(atRate: rate) }
    }
}

enum DecodePlayTiming: String, CaseIterable, Sendable {
    case immediatelyAfterPrepare, afterReady, beforeBytes
}

/// Records AVFoundation's signals independently of the engine's observers. Only closed domain
/// names and numeric codes leave the errors: URLs, error comments and user-info strings may
/// contain stream credentials. Keep a bounded transition history, not a poll on every wait turn.
/// The observation window starts after prepare, before the test requests play.
private final class DecodeVerdictDiagnostics: @unchecked Sendable {
    private let player: AVQueuePlayer
    private let item: AVPlayerItem
    private let lock = NSLock()
    private let started = ContinuousClock().now
    private var signals: [String] = []
    private var signalCount = 0
    private var notificationCounts: [String: Int] = [:]
    private var latestFailure: String?
    private var observers: [NSKeyValueObservation] = []
    private var notifications: [NSObjectProtocol] = []

    init(player: AVQueuePlayer, item: AVPlayerItem) {
        self.player = player
        self.item = item
        observers.append(player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            self?.append("transport=\(player.timeControlStatus.rawValue) rate=\(player.rate)")
        })
        observers.append(item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            self?.append("item_status=\(item.status.rawValue) error=\(Self.errorCodes(item.error))")
        })
        for name in [Notification.Name.AVPlayerItemFailedToPlayToEndTime,
                     .AVPlayerItemNewErrorLogEntry, .AVPlayerItemPlaybackStalled,
                     .AVPlayerItemDidPlayToEndTime] {
            notifications.append(NotificationCenter.default.addObserver(forName: name, object: item, queue: nil) {
                [weak self] notification in
                let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
                let codes = Self.errorCodes(error)
                self?.append(
                    "\(notification.name.rawValue) error=\(codes)",
                    notificationName: notification.name.rawValue,
                    failureCodes: notification.name == .AVPlayerItemFailedToPlayToEndTime ? codes : nil
                )
            })
        }
    }

    func invalidate() {
        observers.forEach { $0.invalidate() }
        notifications.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        notifications.removeAll()
    }

    func describe() -> String {
        lock.lock()
        let history = signals
        let dropped = signalCount - history.count
        let counts = notificationCounts.keys.sorted().map { "\($0)=\(notificationCounts[$0]!)" }
        let failure = latestFailure ?? "none"
        lock.unlock()
        let log = item.errorLog()?.events.map {
            "\(Self.domainName($0.errorDomain)):\($0.errorStatusCode)"
        } ?? []
        return "player_status=\(player.status.rawValue) player_error=\(Self.errorCodes(player.error)) " +
            "signals=\(history) dropped_signals=\(dropped) notifications=\(counts) " +
            "latest_failure=\(failure) error_log=\(log)"
    }

    private func append(_ signal: String, notificationName: String? = nil, failureCodes: String? = nil) {
        lock.lock()
        defer { lock.unlock() }
        if let notificationName { notificationCounts[notificationName, default: 0] += 1 }
        if let failureCodes { latestFailure = failureCodes }
        signalCount += 1
        signals.append("t=\(formatSeconds(durationSeconds(started.duration(to: ContinuousClock().now)))) \(signal)")
        if signals.count > 32 { signals.removeFirst() }
    }

    private static func domainName(_ domain: String) -> String {
        switch domain {
        case AVFoundationErrorDomain: "AVFoundation"
        case NSURLErrorDomain: "URL"
        case NSOSStatusErrorDomain: "OSStatus"
        case "CoreMediaErrorDomain": "CoreMedia"
        default: "other"
        }
    }

    private static func errorCodes(_ error: Error?) -> String {
        var error = error as NSError?
        var codes: [String] = []
        while let current = error, codes.count < DulcetApplePlaybackErrorSanitizer.underlyingErrorDepth {
            codes.append("\(domainName(current.domain)):\(current.code)")
            error = current.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return codes.isEmpty ? "none" : codes.joined(separator: "/")
    }
}

/// Three times the longest verdict CI has shown (about 88 s on #164's run 36660636272).
private let undecodableVerdictTimeout: TimeInterval = 270

/// The case name alone: the associated attempt ids would bury the sequence a failure needs to show.
private func eventName(_ event: DulcetPlaybackEvent) -> String {
    String(String(describing: event).prefix { $0 != "(" })
}

/// An ID3 tag, then MP3 frame headers (MPEG-1 Layer III, 128 kbit/s, 44.1 kHz) each followed by a
/// payload that is not audio. OBSERVED on macOS: AVFoundation finds the frames, reports the item
/// ready, then posts `AVPlayerItemFailedToPlayToEndTime` with `decodeFailed` (-11821). Bytes that
/// hold no frame header at all are not this case -- AVFoundation plays them as a fraction of a
/// second of nothing and stops without an error.
private func mp3FramesWithUndecodablePayloads() -> Data {
    let title = Array("Undecodable".utf8)
    let body: [UInt8] = [0] + title
    var frame: [UInt8] = Array("TIT2".utf8)
    frame += withUnsafeBytes(of: UInt32(body.count).bigEndian, Array.init)
    frame += [0, 0] + body
    let size = frame.count
    var bytes: [UInt8] = Array("ID3".utf8) + [3, 0, 0]
    bytes += [UInt8((size >> 21) & 0x7F), UInt8((size >> 14) & 0x7F), UInt8((size >> 7) & 0x7F), UInt8(size & 0x7F)]
    bytes += frame
    var state: UInt32 = 12_345
    for _ in 0..<380 {
        bytes += [0xFF, 0xFB, 0x90, 0x64]
        for _ in 0..<413 {
            state = state &* 1_103_515_245 &+ 12_345
            bytes.append(UInt8(truncatingIfNeeded: (state & 0x7FFF_FFFF) >> 16))
        }
    }
    return Data(bytes)
}

@Suite(.serialized)
struct AVPlayerEngineTests {
    @Test @MainActor
    func aMainThreadRemoteCommandCallsTheCoreRouterOutsideTheEngineQueue() async {
        let controls = RecordingSystemMediaControls()
        let queue = DispatchQueue(label: "dulcet.tests.remote-reentry")
        let router = ReentrantRemoteCommandRouter(queue: queue)
        let engine = DulcetAVPlayerEngine(
            clock: ManualAVPlayerEngineClock(), usesAVFoundationMediaStack: false,
            systemMediaControls: controls, remoteCommandRouter: router, queue: queue
        )
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        engine.reportCurrentItemReadyForTesting(duration: 60, seekability: .seekable)
        #expect(controls.send(.pause(sessionID: .init("session"))),
                "a main-thread command must reach the main-thread core router without a queue inversion")
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test @MainActor
    func lateDurationEnablesTheSystemScrubberWithoutAnArtworkRefresh() async {
        let engine = DulcetAVPlayerEngine(
            clock: ManualAVPlayerEngineClock(), usesAVFoundationMediaStack: false
        )
        let center = MPNowPlayingInfoCenter.default()
        let commands = MPRemoteCommandCenter.shared()
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        engine.reportCurrentItemReadyForTesting(duration: nil, seekability: .unknown)
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyPlaybackDuration] == nil)
        #expect(!commands.changePlaybackPositionCommand.isEnabled)
        engine.reportCurrentItemDurationForTesting(60, seekability: .seekable)
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyPlaybackDuration] as? Double == 60)
        #expect(commands.changePlaybackPositionCommand.isEnabled)
        #expect(events.snapshot.contains {
            if case let .observationResynced(_, snapshot) = $0 {
                return snapshot.seekability == .seekable && snapshot.duration == 60
            }
            return false
        }, "the core's command router must receive updated seekability too")
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyArtwork] == nil,
                "duration and scrubber do not depend on an artwork callback")
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test @MainActor
    func aColdPreparePublishesBeforeReadinessAndProgressRestoresAnEvictedEntry() async {
        let clock = ManualAVPlayerEngineClock()
        let engine = DulcetAVPlayerEngine(clock: clock, usesAVFoundationMediaStack: false)
        let center = MPNowPlayingInfoCenter.default()
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == "Playback fixture",
                "slow media and missing artwork must not delay publication")
        engine.reportCurrentItemReadyForTesting(duration: 60, seekability: .seekable)
        _ = await execute(engine, .play(commandID: .init("play")))
        center.nowPlayingInfo = nil
        clock.tick(isPlaying: true, mediaPosition: 0.5, monotonicUptimeNanoseconds: 500_000_000)
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == "Playback fixture")
        #expect(center.nowPlayingInfo?[MPMediaItemPropertyPlaybackDuration] as? Double == 60)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    #if !os(macOS)
    @Test
    func manuallyReportedReadinessPublishesSeekabilityWithoutAVFoundationTiming() async {
        let clock = ManualAVPlayerEngineClock()
        let engine = DulcetAVPlayerEngine(clock: clock, usesAVFoundationMediaStack: false)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }

        let outcome = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        #expect(outcome == .accepted(commandID: .init("prepare")))
        #expect(!events.containsReady)
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        #expect(events.containsReady(seekability: .seekable))

        _ = await execute(engine, .release(commandID: .init("release")))
    }
    #endif

    @Test
    func slowWaitDiagnosticReportsResponsiveEngineQueue() async {
        let queue = DispatchQueue(label: "com.legitimateapps.dulcet.tests.queue-responsive")
        let engine = DulcetAVPlayerEngine(
            clock: ManualAVPlayerEngineClock(),
            usesAVFoundationMediaStack: false,
            queue: queue
        )
        let clock = ContinuousClock()
        let started = clock.now
        try? await Task.sleep(for: .milliseconds(50))
        let waited = durationSeconds(started.duration(to: clock.now))
        let waitBudget: TimeInterval = 0.025

        let diagnostic = await waitFailureDiagnostic(
            "condition remained false",
            engine: engine,
            waitBudget: waitBudget,
            waited: waited,
            queueProbeTimeout: 5
        )

        #expect(waited >= waitBudget)
        #expect(diagnostic.contains("waited_seconds="))
        #expect(diagnostic.contains("engine queue responsive; turnaround_seconds="))
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func blockedWaitDiagnosticReportsUnresponsiveEngineQueue() async {
        let queue = DispatchQueue(label: "com.legitimateapps.dulcet.tests.queue-blocked")
        let engine = DulcetAVPlayerEngine(
            clock: ManualAVPlayerEngineClock(),
            usesAVFoundationMediaStack: false,
            queue: queue
        )
        let unblockQueue = DispatchSemaphore(value: 0)
        await withCheckedContinuation { blockerStarted in
            queue.async {
                blockerStarted.resume()
                unblockQueue.wait()
            }
        }

        let diagnostic = await waitFailureDiagnostic(
            "condition remained false",
            engine: engine,
            waitBudget: 0.05,
            waited: 0.05,
            queueProbeTimeout: 0.05
        )

        #expect(diagnostic.contains("engine queue not responsive; probe_wait_seconds="))
        unblockQueue.signal()
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func waitFailureDiagnosticsDistinguishProductFailuresFromCoreAudioHostOverload() {
        let responsive = classifiedWaitFailureDiagnostic(
            "interruption begin was not emitted",
            waitBudget: 20,
            waited: 20.025,
            queueProbeTimeout: 1,
            liveness: .responsive(turnaroundSeconds: 0.012)
        )
        let overloaded = classifiedWaitFailureDiagnostic(
            "interruption begin was not emitted",
            waitBudget: 20,
            waited: 20.025,
            queueProbeTimeout: 1,
            liveness: .notResponsive(waitedSeconds: 1.056)
        )

        #expect(responsive != overloaded)
        #expect(responsive.contains("failure_classification=DULCET_EXPECTATION_NOT_EMITTED"))
        #expect(responsive.contains("expected condition was not emitted despite a responsive engine queue"))
        #expect(!responsive.contains("DULCET_COREAUDIO_HOST_OVERLOAD"))
        #expect(overloaded.contains("failure_classification=DULCET_COREAUDIO_HOST_OVERLOAD"))
        #expect(overloaded.contains("cause=CoreAudio/host overload"))
        #expect(overloaded.contains("probe_timeout_seconds=1.000"))
        #expect(overloaded.contains("engine queue not responsive; probe_wait_seconds=1.056"))
        #expect(overloaded.contains("HALC_ProxyIOContext::IOWorkLoop: skipping cycle due to overload"))
    }

    @Test
    func blockedMediaSamplingDoesNotBlockReadyOrSeekableNowPlaying() async throws {
        let clock = BlockingSampleAVPlayerEngineClock()
        let mediaControls = RecordingSystemMediaControls()
        let engine = DulcetAVPlayerEngine(
            clock: clock,
            usesAVFoundationMediaStack: false,
            systemMediaControls: mediaControls
        )
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: plan()))
        _ = await execute(engine, .play(commandID: .init("play")))
        let sampling = Task.detached { clock.fireSampler() }
        let samplingStarted = await Task.detached { clock.waitUntilSampleStarts() }.value
        #expect(samplingStarted, "the negative-control media sample did not start")

        let readiness = Task.detached {
            engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        }
        try await waitUntil(
            "readiness was never published while a media sample was blocked",
            engine: engine
        ) {
            events.containsReady(seekability: .seekable)
        }
        // Readiness above already proves the engine queue serviced work while the sample was
        // blocked, so this probe only has to distinguish "queue is blocked" from "queue is slow".
        // The blocked sample is held until `clock.unblockSample()` BELOW this assertion, so a queue
        // genuinely blocked by it stays blocked for the whole probe no matter how long the probe
        // waits -- a longer timeout therefore cannot turn a real block into a pass, and only stops
        // a loaded runner from reporting a responsive queue as a blocked one.
        let liveness = await engine.probeQueueLivenessForTesting(timeout: blockedQueueProbeTimeout)

        #expect(events.containsReady(seekability: .seekable))
        #expect(mediaControls.publications.last?.seekability == .seekable)
        if case .responsive = liveness {
            // Expected: media sampling is isolated from the serialized engine state machine.
        } else {
            #expect(Bool(false), "a blocked media sample also blocked the engine queue")
        }

        clock.unblockSample()
        _ = await sampling.value
        await readiness.value
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func delayedMediaSampleCannotCrossAnAttemptReplacement() async {
        let clock = BlockingSampleAVPlayerEngineClock(mediaPosition: 0.5)
        let engine = DulcetAVPlayerEngine(clock: clock, usesAVFoundationMediaStack: false)
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let original = plan(session: "session", attempt: "original")
        let replacement = plan(session: "session", attempt: "replacement")

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: original))
        _ = await execute(engine, .play(commandID: .init("play")))
        let delayedSample = Task.detached { clock.fireSampler() }
        let samplingStarted = await Task.detached { clock.waitUntilSampleStarts() }.value
        #expect(samplingStarted, "the delayed original-attempt sample did not start")

        _ = await execute(
            engine,
            .replaceCurrent(commandID: .init("replace"), plan: replacement)
        )
        _ = await execute(engine, .play(commandID: .init("replacement-play")))
        clock.unblockSample()
        _ = await delayedSample.value
        #expect(events.progressBeganAttemptIDs.isEmpty)

        #expect(clock.fireSampler(), "the replacement-attempt positive-control sample did not fire")
        #expect(events.progressBeganAttemptIDs == [replacement.attemptID])
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
        try await waitUntil(
            "AVPlayer did not become ready",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) { events.containsReady(seekability: .seekable) }
        #else
        engine.reportCurrentItemReadyForTesting(duration: 3, seekability: .seekable)
        #expect(events.containsReady(seekability: .seekable))
        #endif
        #expect(!events.containsProgressBegan)

        _ = await execute(engine, .play(commandID: .init("play")))
        #if os(macOS)
        try await waitUntil(
            "the monotonic sampler did not observe advancing media time",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
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

        try await waitUntil(
            "AVPlayer did not become ready through loopback HTTP range loading",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
            events.containsReady(seekability: .seekable)
        }
        try await waitUntil(
            "loopback HTTP playback never advanced media time",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
            events.containsProgressBegan
        }
        #expect(server.requests.count > 0)
        #expect(server.requests.allSatisfy { $0.rangeHeader != nil })
        #expect(server.requests.first?.rangeHeader == "bytes=0-262143")

        _ = await execute(engine, .release(commandID: .init("loopback-release")))
    }

    /// Nothing may be said about an attempt after its failure. The core reads a `Paused` that
    /// follows `FailedAfterPartial` as the failed attempt pausing, which takes it out of Failed;
    /// Try Again and Play then have nothing to retry. On the real stack the player's
    /// time-control status goes on changing after the item fails, and its observer fires later,
    /// on its own schedule -- so this waits for KVO to deliver rather than reading synchronously.
    @Test
    func noEventFollowsTheFailureOfThePlayingItemOnTheRealStack() async throws {
        let engine = DulcetAVPlayerEngine(
            player: AVQueuePlayer(),
            systemMediaControls: RecordingSystemMediaControls()
        )
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(resource: InMemoryPlaybackResource(data: makePCMWave(duration: 20)))
        _ = await execute(engine, .prepare(commandID: .init("fail-prepare"), plan: playbackPlan))
        _ = await execute(engine, .play(commandID: .init("fail-play")))
        try await waitUntil(
            "the real player never advanced media time",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
            events.containsProgressBegan
        }

        engine.reportCurrentItemFailedForTesting(.transport)
        let afterFailure = try #require(events.snapshot.lastIndex { event in
            if case .failedAfterPartial = event { true } else { false }
        }, "the failure state must be reached, or the rest proves nothing")
        // Long enough for the player's rate and time-control changes to be observed and for the
        // engine queue to run what they enqueued.
        try await Task.sleep(for: .seconds(2))
        _ = await engine.probeQueueLivenessForTesting(timeout: 5)
        let trailing = Array(events.snapshot.dropFirst(afterFailure + 1))
        #expect(trailing.isEmpty, "events after the failure: \(trailing)")
        _ = await execute(engine, .release(commandID: .init("fail-release")))
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

        try await waitUntil(
            "AVQueuePlayer did not publish the preloaded session boundary",
            engine: engine
        ) {
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

    /// The gapless boundary must keep the music "requested". The sampler samples only a context
    /// whose play was requested, so an incoming item that did not inherit the request would play
    /// audibly and never report PlaybackProgressBegan -- and so never be scrobbled.
    ///
    /// The boundary is real: a real `AVQueuePlayer` holds both items and changes its current item,
    /// and the engine's own observation of that change makes the advance. What this test does not
    /// wait for is media time. The items' bytes never arrive, so the player can neither reach a
    /// natural end nor advance a clock by itself; every media-time sample is one supplied here, and
    /// the audio session is a recording one, so no system interruption can withdraw the request.
    /// Waiting for real media time instead made this test measure the simulator's audio clock: on
    /// hosted iPhone simulators a 1 s item did not end within 60 s, and a 2 s preloaded item went
    /// 60 s without PlaybackProgressBegan, in each case with the engine queue still responsive.
    /// Progress is defined as media time advancing, so where media time does not advance the engine
    /// must report none. The real-media form runs on the macOS host below.
    @Test
    func aPreloadedAdvanceInheritsThePlayRequestAndReportsItsOwnProgress() async throws {
        let boundary = try await PreloadBoundary.start()
        let (engine, clock, events, first, next) =
            (boundary.engine, boundary.clock, boundary.events, boundary.first, boundary.next)

        try await boundary.advance()

        // Below the outgoing item's last position, as an incoming item's first sample is: only the
        // incoming context can report it, because credited to the outgoing one it does not advance.
        // The supplied sample is the whole transport state the sampler reads; the real player is
        // meanwhile waiting for bytes, which the sampler does not consult.
        clock.tick(isPlaying: true, mediaPosition: 0.25, monotonicUptimeNanoseconds: 1_500_000_000)
        #expect(events.progressBeganAttemptIDs == [first.attemptID, next.attemptID])

        let snapshot = events.snapshot
        let ended = try #require(snapshot.firstIndex {
            if case let .endedNaturally(attemptID, _) = $0 { attemptID == first.attemptID } else { false }
        })
        let advanced = try #require(snapshot.firstIndex(of: .advancedToPreloaded(
            oldAttemptID: first.attemptID,
            newAttemptID: next.attemptID
        )))
        let incomingProgress = try #require(snapshot.firstIndex {
            if case let .playbackProgressBegan(attemptID, _, _) = $0 { attemptID == next.attemptID } else { false }
        })
        #expect(ended < advanced, "the outgoing session ends before the boundary")
        #expect(advanced < incomingProgress)
        #expect(!snapshot.contains { if case .skipped = $0 { true } else { false } },
                "a gapless advance is not a stop")
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    /// The incoming item inherits the request; it is not granted one. An engine that marked every
    /// advanced-into item as requested would pass the test above, and would start reporting
    /// progress for music the listener had paused.
    @Test
    func aPreloadedAdvanceWhilePausedInheritsNoPlayRequest() async throws {
        let boundary = try await PreloadBoundary.start()
        let (engine, clock, events, first, next) =
            (boundary.engine, boundary.clock, boundary.events, boundary.first, boundary.next)
        #expect(await execute(engine, .pause(commandID: .init("pause")))
            == .completed(commandID: .init("pause"), result: .withoutData))

        try await boundary.advance()

        clock.tick(isPlaying: true, mediaPosition: 0.25, monotonicUptimeNanoseconds: 1_500_000_000)
        #expect(events.progressBeganAttemptIDs == [first.attemptID],
                "an item advanced into while paused was sampled as if play had been requested")

        // Positive control: the same sample path reaches the incoming item once play IS requested,
        // so the silence above was the missing request and not an instrument that cannot reach it.
        #expect(await execute(engine, .play(commandID: .init("resume"))) == .accepted(commandID: .init("resume")))
        clock.tick(isPlaying: true, mediaPosition: 0.5, monotonicUptimeNanoseconds: 2_000_000_000)
        #expect(events.progressBeganAttemptIDs == [first.attemptID, next.attemptID])
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    #if os(macOS)
    /// The same boundary end to end on real media time: a natural end, the player's own advance,
    /// and the system clock's sampler reading the incoming item. It runs on the macOS host only.
    /// On hosted iPhone simulators these same waits ran out at 60 s with the engine queue
    /// responsive, twice alongside logged audio IO overload -- consistent with a stalled audio
    /// clock, though no run there reported the player's own media time. The tvOS simulator is left
    /// out too: its clean record (0 of 24) is what the iPhone rate (3 of 57) produces about a
    /// quarter of the time, so it does not show tvOS is different. The engine source itself has no
    /// platform conditionals.
    /// On failure the player's own media state is reported, so a clock that never moved and an
    /// engine that ignored one that did cannot be mistaken for each other.
    @Test
    func aNaturalEndIntoThePreloadReportsTheIncomingProgressFromRealMediaTime() async throws {
        let player = AVQueuePlayer()
        let engine = DulcetAVPlayerEngine(player: player, systemMediaControls: RecordingSystemMediaControls())
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let first = plan(
            resource: InMemoryPlaybackResource(data: makePCMWave(duration: 1)),
            session: "gapless-a",
            attempt: "gapless-attempt-a"
        )
        let next = plan(
            resource: InMemoryPlaybackResource(data: makePCMWave(duration: 2)),
            session: "gapless-b",
            attempt: "gapless-attempt-b"
        )

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: first))
        _ = await execute(engine, .play(commandID: .init("play")))
        #expect(await execute(engine, .preloadNext(commandID: .init("preload"), plan: next))
            == .accepted(commandID: .init("preload")))
        let queued = player.items()
        try #require(queued.count == 2, "the real player must hold the current and the preloaded item")
        let mediaState = PlayerMediaState(player: player, first: queued[0], next: queued[1])

        try await waitUntil(
            "the first item never reached its natural end and advanced",
            engine: engine,
            timeout: realAVFoundationProgressTimeout,
            mediaState: { mediaState.describe() }
        ) {
            mediaState.observe()
            return events.containsAdvance(old: first.attemptID, new: next.attemptID)
        }
        try await waitUntil(
            "the preloaded item played but never reported progress",
            engine: engine,
            timeout: realAVFoundationProgressTimeout,
            mediaState: { mediaState.describe() }
        ) {
            mediaState.observe()
            return events.progressBeganAttemptIDs.contains(next.attemptID)
        }
        let snapshot = events.snapshot
        let ended = try #require(snapshot.firstIndex {
            if case let .endedNaturally(attemptID, _) = $0 { attemptID == first.attemptID } else { false }
        })
        let advanced = try #require(snapshot.firstIndex(of: .advancedToPreloaded(
            oldAttemptID: first.attemptID,
            newAttemptID: next.attemptID
        )))
        #expect(ended < advanced, "the outgoing session ends before the boundary")
        #expect(!snapshot.contains { if case .skipped = $0 { true } else { false } },
                "a gapless advance is not a stop")
        _ = await execute(engine, .release(commandID: .init("release")))
    }
    #endif

    @Test
    func aDiscardedPreloadIsNotAdvancedIntoAndCannotBeDiscardedTwice() async throws {
        let player = AVQueuePlayer()
        let engine = DulcetAVPlayerEngine(player: player, systemMediaControls: RecordingSystemMediaControls())
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let first = plan(
            resource: InMemoryPlaybackResource(data: makePCMWave(duration: 1)),
            session: "discard-a",
            attempt: "discard-attempt-a"
        )
        let next = plan(session: "discard-b", attempt: "discard-attempt-b")

        #expect(await execute(engine, .discardPreloaded(
            commandID: .init("discard-none"),
            attemptID: next.attemptID
        )) == .rejected(commandID: .init("discard-none"), reason: .invalidState))
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: first))
        _ = await execute(engine, .play(commandID: .init("play")))
        _ = await execute(engine, .preloadNext(commandID: .init("preload"), plan: next))
        #expect(player.items().count == 2)
        let mediaState = PlayerMediaState(player: player, first: try #require(player.items().first), next: nil)

        #expect(await execute(engine, .discardPreloaded(
            commandID: .init("discard"),
            attemptID: next.attemptID
        )) == .completed(commandID: .init("discard"), result: .withoutData))
        #expect(player.items().count == 1)
        #expect(await execute(engine, .discardPreloaded(
            commandID: .init("discard-again"),
            attemptID: next.attemptID
        )) == .rejected(commandID: .init("discard-again"), reason: .invalidState))

        // This wait still depends on real media time on every destination, so if it runs out the
        // report says whether the player's own clock moved.
        try await waitUntil(
            "the first item never ended",
            engine: engine,
            timeout: realAVFoundationProgressTimeout,
            mediaState: { mediaState.describe() }
        ) {
            mediaState.observe()
            return events.snapshot.contains {
                if case let .endedNaturally(attemptID, _) = $0 { attemptID == first.attemptID } else { false }
            }
        }
        #expect(!events.snapshot.contains {
            if case .advancedToPreloaded = $0 { true } else { false }
        })
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func nowPlayingArtworkIsPublishedForTheCurrentSessionOnlyAndCarriedAcrossTransportUpdates()
        async throws {
        let mediaControls = RecordingSystemMediaControls()
        let engine = DulcetAVPlayerEngine(systemMediaControls: mediaControls)
        let current = plan(session: "artwork-session", attempt: "artwork-attempt")
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: current))
        try await waitUntil(
            "Ready did not publish now playing",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
            mediaControls.publications.last?.seekability == .seekable
        }
        #expect(mediaControls.publications.last?.artworkImageData == nil)

        let image = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01])
        let published = mediaControls.publications.count
        engine.setNowPlayingArtwork(Data([0x00]), for: .init("some-other-session"))
        #expect(mediaControls.publications.count == published)
        #expect(!engine.holdsArtworkForTesting(.init("some-other-session")),
                "artwork for a session that is neither current nor preloaded is dropped")

        engine.setNowPlayingArtwork(image, for: current.playbackSessionID)
        #expect(mediaControls.publications.count == published + 1)
        #expect(mediaControls.publications.last?.artworkImageData == image)
        #expect(mediaControls.publications.last?.metadata.title == current.metadata.title)
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    /// A stall keeps the entry "playing" -- the pause button stays -- but the system must stop
    /// extrapolating elapsed time, or the lock-screen scrubber runs ahead of the audio.
    @Test
    func aStallKeepsTheSystemEntryPlayingAtRateZeroUntilPlaybackResumes() async throws {
        let mediaControls = RecordingSystemMediaControls()
        let engine = DulcetAVPlayerEngine(
            clock: ManualAVPlayerEngineClock(),
            usesAVFoundationMediaStack: false,
            audioSession: RecordingAudioSession(),
            systemMediaControls: mediaControls
        )
        let current = plan(session: "stall-session", attempt: "stall-attempt")
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: current))
        engine.reportCurrentItemReadyForTesting(duration: 60, seekability: .seekable)
        _ = await execute(engine, .play(commandID: .init("play")))
        let before = mediaControls.transports.count

        engine.reportCurrentItemStalledForTesting()

        let stalled = try #require(mediaControls.transports.dropFirst(before).last)
        #expect(stalled.0 == current.playbackSessionID)
        #expect(stalled.3, "still playing to the listener")
        #expect(stalled.2 == 0, "but the system must not extrapolate while stalled")
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
        let clock = ManualAVPlayerEngineClock()
        let mediaControls = RecordingSystemMediaControls()
        let player = AVQueuePlayer()
        let resource = InMemoryPlaybackResource(data: makePCMWave(duration: 2))
        // Dedicated integration tests cover real AVFoundation loading and media progression. This
        // policy test keeps the production engine queue and event handler, but inserts no player
        // item; the empty-item and zero-request assertions below guard that boundary.
        let engine = DulcetAVPlayerEngine(
            player: player,
            clock: clock,
            usesAVFoundationMediaStack: false,
            audioSession: audioSession,
            systemMediaControls: mediaControls
        )
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(resource: resource)

        let prepare = await execute(
            engine,
            .prepare(commandID: .init("prepare"), plan: playbackPlan)
        )
        #expect(prepare == .accepted(commandID: .init("prepare")))
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        #expect(events.containsReady(seekability: .seekable))

        let play = await execute(engine, .play(commandID: .init("play")))
        #expect(play == .accepted(commandID: .init("play")))
        clock.tick(
            isPlaying: true,
            mediaPosition: 0.5,
            monotonicUptimeNanoseconds: 500_000_000
        )
        #expect(events.containsProgressBegan)
        #expect(player.items().isEmpty)
        #expect(resource.requests.isEmpty)

        let interruptionBeganIndex = events.snapshot.count
        audioSession.publish(.interruptionBegan)
        try await waitUntil("interruption begin was not emitted", engine: engine) {
            events.snapshot.count > interruptionBeganIndex
        }
        #expect(Array(events.snapshot.dropFirst(interruptionBeganIndex)) == [
            .interruptionBegan(attemptID: playbackPlan.attemptID, shouldResume: false),
        ])

        let interruptionEndedIndex = events.snapshot.count
        audioSession.publish(.interruptionEnded(systemAllowsResume: true))
        try await waitUntil("eligible interruption did not resume", engine: engine) {
            events.snapshot.count >= interruptionEndedIndex + 2
        }
        #expect(Array(events.snapshot.dropFirst(interruptionEndedIndex)) == [
            .interruptionEnded(attemptID: playbackPlan.attemptID, shouldResume: true),
            .resumed(attemptID: playbackPlan.attemptID, position: 0.5),
        ])

        let routeChangedIndex = events.snapshot.count
        audioSession.publish(.routeChanged(old: .bluetooth, new: .builtIn, becomingNoisy: true))
        try await waitUntil("becoming-noisy route did not pause", engine: engine) {
            events.snapshot.count > routeChangedIndex
        }
        #expect(Array(events.snapshot.dropFirst(routeChangedIndex)) == [
            .routeChanged(
                attemptID: playbackPlan.attemptID,
                old: .bluetooth,
                new: .builtIn,
                didPause: true
            ),
        ])
        #expect(mediaControls.transportPlayingStates == [true, true, false, true, false])

        let replay = await execute(engine, .play(commandID: .init("replay")))
        #expect(replay == .accepted(commandID: .init("replay")))
        let disallowedInterruptionIndex = events.snapshot.count
        audioSession.publish(.interruptionBegan)
        try await waitUntil("second interruption begin was not emitted", engine: engine) {
            events.snapshot.count > disallowedInterruptionIndex
        }
        #expect(Array(events.snapshot.dropFirst(disallowedInterruptionIndex)) == [
            .interruptionBegan(attemptID: playbackPlan.attemptID, shouldResume: false),
        ])

        let disallowedEndIndex = events.snapshot.count
        audioSession.publish(.interruptionEnded(systemAllowsResume: false))
        try await waitUntil("system-disallowed interruption end was not emitted", engine: engine) {
            events.snapshot.count > disallowedEndIndex
        }
        #expect(Array(events.snapshot.dropFirst(disallowedEndIndex)) == [
            .interruptionEnded(attemptID: playbackPlan.attemptID, shouldResume: false),
        ])
        #expect(mediaControls.transportPlayingStates == [
            true, true, false, true, false, true, false,
        ])

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

        try await waitUntil(
            "external playback did not finalize the engine session",
            engine: engine
        ) {
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
        try await waitUntil(
            "external playback did not deactivate the audio session",
            engine: engine
        ) {
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

        try await waitUntil(
            "empty queue did not deactivate after its grace period",
            engine: engine
        ) {
            audioSession.deactivationCount == 1
        }
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    @Test
    func nowPlayingPublishesAtPreparingAndBecomesSeekableOnlyAfterReady() async throws {
        let mediaControls = RecordingSystemMediaControls()
        let suspended = SuspendedPlaybackResource()
        let engine = DulcetAVPlayerEngine(systemMediaControls: mediaControls)
        let playbackPlan = plan(resource: suspended, title: "Ordered metadata")

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        #expect(mediaControls.publications.last?.metadata.title == "Ordered metadata")
        #expect(mediaControls.publications.last?.seekability == .unknown)

        suspended.resume(with: makePCMWave(duration: 2))
        try await waitUntil(
            "Ready did not publish Now Playing",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
            mediaControls.publications.last?.seekability == .seekable
        }
        let state = try #require(mediaControls.publications.last)
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
        try await waitUntil(
            "initial Ready did not publish metadata",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
            mediaControls.publications.last?.seekability == .seekable
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
        try await waitUntil(
            "Ready did not publish command availability",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
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

    /// After the current item fails, the system must be told it is no longer playing -- so the
    /// lock screen, Control Center and a headset offer Play -- and that Play must still reach the
    /// owner's router, which turns it into Try Again (spec §12.1, §15). The engine does not retry
    /// by itself: only the core knows what a retry is. Left showing "playing", the system's one
    /// button was Pause, and the failed item's Now Playing entry went on counting up.
    @Test
    func aFailedCurrentItemTellsTheSystemItStoppedAndRoutesItsPlay() async throws {
        let clock = ManualAVPlayerEngineClock()
        let mediaControls = RecordingSystemMediaControls()
        let router = RecordingRemoteCommandRouter()
        let engine = DulcetAVPlayerEngine(
            clock: clock,
            usesAVFoundationMediaStack: false,
            audioSession: RecordingAudioSession(),
            systemMediaControls: mediaControls,
            remoteCommandRouter: router
        )
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(
            resource: InMemoryPlaybackResource(data: makePCMWave(duration: 2)),
            session: "failing-session",
            attempt: "failing-attempt"
        )
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        _ = await execute(engine, .play(commandID: .init("play")))
        clock.tick(isPlaying: true, mediaPosition: 0.5, monotonicUptimeNanoseconds: 500_000_000)
        #expect(events.containsProgressBegan)
        #expect(mediaControls.publications.last?.sessionID == playbackPlan.playbackSessionID)
        #expect(mediaControls.transportPlayingStates.last == true, "the case needs a playing entry")

        engine.reportCurrentItemFailedForTesting(.undecodable)
        // The failure state is reached, or the rest proves nothing.
        #expect(events.snapshot.last == .failedAfterPartial(
            attemptID: playbackPlan.attemptID,
            position: 0.5,
            error: .undecodable
        ))
        #expect(mediaControls.transportPlayingStates.last == false,
                "a failed item must not be shown to the system as playing")
        #expect(mediaControls.transports.last?.2 == 0, "nor as advancing")

        #expect(mediaControls.send(.play(sessionID: playbackPlan.playbackSessionID)))
        #expect(router.commands == [.play(sessionID: playbackPlan.playbackSessionID)],
                "a Play after the failure must reach the owner, which retries")
        _ = await execute(engine, .release(commandID: .init("release")))
    }

    /// What a Stop leaves behind, which is why the owner answers Play after it by restarting
    /// the entry (spec §12.1, as Android does) and never by a bare play: the engine holds no item,
    /// so its own Play is refused, and the system's Now Playing entry is withdrawn, so a remote
    /// Play addressed to the stopped session is not the engine's to take and never reaches the
    /// owner through it. The stop is reported as the attempt's `.skipped`, which the core records
    /// as `Stopped` while keeping the session.
    @Test
    func aStopLeavesNoItemForAPlayToSoundAndWithdrawsTheSystemEntry() async throws {
        let clock = ManualAVPlayerEngineClock()
        let mediaControls = RecordingSystemMediaControls()
        let router = RecordingRemoteCommandRouter()
        let engine = DulcetAVPlayerEngine(
            clock: clock,
            usesAVFoundationMediaStack: false,
            audioSession: RecordingAudioSession(),
            systemMediaControls: mediaControls,
            remoteCommandRouter: router
        )
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let playbackPlan = plan(session: "stopped-session", attempt: "stopped-attempt")
        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: playbackPlan))
        engine.reportCurrentItemReadyForTesting(duration: 2, seekability: .seekable)
        _ = await execute(engine, .play(commandID: .init("play")))
        clock.tick(isPlaying: true, mediaPosition: 0.5, monotonicUptimeNanoseconds: 500_000_000)
        // A playing entry the system shows, whose remote Play reaches the owner, or the rest
        // proves nothing.
        #expect(events.containsProgressBegan)
        #expect(mediaControls.send(.play(sessionID: playbackPlan.playbackSessionID)))
        #expect(router.commands.count == 1, "the control: before the stop a remote Play is routed")
        let clearsBefore = mediaControls.clearCount

        #expect(await execute(engine, .stop(commandID: .init("stop")))
            == .completed(commandID: .init("stop"), result: .withoutData))
        #expect(events.snapshot.last == .skipped(
            attemptID: playbackPlan.attemptID,
            position: 0.5,
            reason: .user
        ))
        #expect(mediaControls.clearCount == clearsBefore + 1, "the system entry is withdrawn")

        #expect(await execute(engine, .play(commandID: .init("play-after-stop")))
            == .rejected(commandID: .init("play-after-stop"), reason: .invalidState),
            "a bare Play after Stop has no item to play")
        #expect(!mediaControls.send(.play(sessionID: playbackPlan.playbackSessionID)),
                "a remote Play for the stopped session is not the engine's")
        #expect(router.commands.count == 1, "and does not reach the owner through the engine")
        #expect(events.snapshot.last == .skipped(
            attemptID: playbackPlan.attemptID,
            position: 0.5,
            reason: .user
        ), "nothing after the stop reports the stopped attempt as playing again")
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
        // Favourites and ratings are unbuilt, so the command centre must not offer them even
        // when a caller claims the capability: a lock-screen heart that does nothing is worse
        // than none.
        #expect(!center.ratingCommand.isEnabled)
        #expect(!center.likeCommand.isEnabled)
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
        try await waitUntil(
            "command fixture did not become ready",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
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

        try await waitUntil(
            "401 did not request a source refresh",
            engine: engine,
            timeout: realAVFoundationProgressTimeout
        ) {
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

    /// Each of AVFoundation's failures of an item's own media is the item's (spec §12.12). They
    /// are named here one by one, not read from the set under test, so a code dropped from that
    /// set fails its own case.
    @Test(arguments: [
        AVError.Code.decodeFailed.rawValue,
        AVError.Code.decoderNotFound.rawValue,
        AVError.Code.fileFormatNotRecognized.rawValue,
        AVError.Code.fileFailedToParse.rawValue,
        AVError.Code.failedToParse.rawValue,
        AVError.Code.undecodableMediaData.rawValue,
    ])
    func anItemsOwnMediaFailureCrossesAsUndecodable(code: Int) {
        let error = NSError(domain: AVFoundationErrorDomain, code: code)
        #expect(DulcetApplePlaybackErrorSanitizer.avFoundationFailure(error) == .undecodable)
    }

    /// The control for the cases above: the system's failures are not the item's.
    @Test(arguments: [
        AVError.Code.unknown.rawValue,
        // Media services were reset, and an operation interrupted: declared only for iOS-family
        // SDKs, so named here by their raw values, which every platform's error can carry.
        -11_819,
        AVError.Code.decoderTemporarilyUnavailable.rawValue,
        -11_847,
    ])
    func theSystemsOwnFailureStaysTheEngines(code: Int) {
        let error = NSError(domain: AVFoundationErrorDomain, code: code)
        #expect(DulcetApplePlaybackErrorSanitizer.avFoundationFailure(error) == .engine)
    }

    /// A wrapped error is read through `NSUnderlyingErrorKey`, outermost first and a bounded
    /// number of levels deep: an AVFoundation error wrapping a certificate failure is the
    /// connection's, one wrapping a decode failure is the item's, and a chain deeper than the
    /// bound is the engine's. Nothing of any of them crosses but the closed name. Unreachable
    /// today -- the item's bytes arrive only through the resource loader -- and pinned so it
    /// stays right if that changes.
    @Test
    func wrappedFailuresAreReadThroughToABound() {
        let canary = "wrapped-token-canary"
        func wrapping(_ inner: NSError) -> NSError {
            NSError(
                domain: AVFoundationErrorDomain,
                code: AVError.Code.unknown.rawValue,
                userInfo: [NSUnderlyingErrorKey: inner]
            )
        }
        func wrapped(_ inner: NSError, levels: Int) -> NSError {
            (0..<levels).reduce(inner) { error, _ in wrapping(error) }
        }
        let untrusted = NSError(
            domain: NSURLErrorDomain,
            code: URLError.serverCertificateUntrusted.rawValue,
            userInfo: [NSURLErrorFailingURLStringErrorKey: "https://source.invalid/audio?t=\(canary)"]
        )
        let timedOut = NSError(domain: NSURLErrorDomain, code: URLError.timedOut.rawValue)
        let undecodable = NSError(domain: AVFoundationErrorDomain, code: AVError.Code.decodeFailed.rawValue)
        let sanitize = DulcetApplePlaybackErrorSanitizer.avFoundationFailure

        #expect(sanitize(wrapping(untrusted)) == .tlsUntrusted)
        #expect(sanitize(wrapping(timedOut)) == .transport)
        #expect(sanitize(wrapping(undecodable)) == .undecodable)
        // Eight errors are read, the outermost included: the eighth is, the ninth is not.
        #expect(sanitize(wrapped(undecodable, levels: 7)) == .undecodable, "the deepest error read")
        #expect(sanitize(wrapped(undecodable, levels: 8)) == .engine, "one level past the bound")
        #expect(!String(reflecting: sanitize(wrapping(untrusted))).contains(canary))
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

/// The core router enters MainActor, whose observation callbacks can reenter the engine. Prove
/// that the engine queue can service work while routing, without hanging the test on an actual
/// main/engine queue inversion. Thread.isMainThread alone cannot detect an inline dispatch sync.
private final class ReentrantRemoteCommandRouter: DulcetRemotePlaybackCommandRouting, Sendable {
    let queue: DispatchQueue
    init(queue: DispatchQueue) { self.queue = queue }
    func handleRemotePlaybackCommand(_ command: DulcetRemotePlaybackCommand) -> Bool {
        let serviced = DispatchSemaphore(value: 0)
        queue.async { serviced.signal() }
        return serviced.wait(timeout: .now() + 1) == .success
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

/// nil when no overlap was active, in which case the command did not run.
private func executeDuringOverlap(
    _ engine: DulcetAVPlayerEngine,
    _ command: DulcetPlaybackCommand
) async -> DulcetPlaybackCommandOutcome? {
    await withCheckedContinuation { continuation in
        let ran = engine.executeDuringOverlapForTesting(command) { continuation.resume(returning: $0) }
        if !ran { continuation.resume(returning: nil) }
    }
}

private typealias CrossfadeState = (incomingRate: Float, outgoingRate: Float,
                                    incomingVolume: Float, outgoingVolume: Float, outgoingPosition: Double)

/// The first overlap state that satisfies `predicate`, as that one read observed it. Waiting for a
/// condition and then reading the state again races the end of the overlap.
private func waitForCrossfadeState(
    _ failure: String,
    engine: DulcetAVPlayerEngine,
    timeout: TimeInterval = 30,
    events: PlaybackEventRecorder? = nil,
    where predicate: @escaping @Sendable (CrossfadeState) -> Bool
) async throws -> CrossfadeState {
    let observed = ObservedCrossfadeState()
    let mediaState: @Sendable () -> String = {
        "crossfade_samples=\(observed.diagnostic) events=\(events?.snapshot ?? [])"
    }
    try await waitUntil(failure, engine: engine, timeout: timeout, mediaState: mediaState) {
        if observed.value != nil { return true }
        let state = engine.crossfadeStateForTesting()
        observed.record(state)
        guard let state, predicate(state) else { return false }
        observed.set(state)
        return true
    }
    return try #require(observed.value, Comment(rawValue: failure))
}

private final class ObservedCrossfadeState: @unchecked Sendable {
    private let lock = NSLock()
    private var state: CrossfadeState?
    private var polls = 0
    private var overlapSamples = 0
    private var firstSample: String?
    private var recentSamples: [String] = []
    var value: CrossfadeState? { lock.withLock { state } }
    func set(_ newValue: CrossfadeState) { lock.withLock { if state == nil { state = newValue } } }
    func record(_ sample: CrossfadeState?) {
        lock.withLock {
            polls += 1
            guard let sample else { return }
            overlapSamples += 1
            let description = "incomingRate=\(sample.incomingRate),outgoingRate=\(sample.outgoingRate),incomingGain=\(sample.incomingVolume),outgoingGain=\(sample.outgoingVolume),outgoingPosition=\(sample.outgoingPosition)"
            if firstSample == nil { firstSample = description }
            recentSamples.append(description)
            if recentSamples.count > 8 { recentSamples.removeFirst() }
        }
    }
    var diagnostic: String {
        lock.withLock { "polls=\(polls) overlap=\(overlapSamples) first=\(firstSample ?? "none") recent=\(recentSamples)" }
    }
}

// This budget is deliberately opt-in. Only waits driven by real AVFoundation loading, readyToPlay,
// or advancing media time use it; ordinary engine-event waits retain the 20-second default below.
// Run 33301231544 measured unrelated passing siblings at 28.139s and 10.789s, and the real custom-
// loader test at 8.859s, while this suite's first readiness wait failed after 29.662s on its former
// 20-second budget. Sixty seconds gives that measured contention headroom without changing what
// passes.
//
// The deadline keeps its teeth: if AVFoundation never requests the resource, never becomes ready,
// or media time never advances, the condition never becomes true and the test still fails, just
// later. On failure the queue probe below says whether the engine queue itself could service work.
private let realAVFoundationProgressTimeout: TimeInterval = 60

// Used as a pass/fail gate, not as a diagnostic, so it is deliberately far longer than the one
// second `waitFailureDiagnostic` spends classifying an already-failed wait. See the call site.
private let blockedQueueProbeTimeout: TimeInterval = 5

/// `mediaState`, when given, is appended to a failure. A responsive engine queue says only that
/// the engine could have acted; for a wait on real media time it cannot say whether media time
/// moved, and without that a frozen player clock reads exactly like an engine that ignored one.
private func waitUntil(
    _ failure: String,
    engine: DulcetAVPlayerEngine,
    timeout: TimeInterval = 20,
    mediaState: (@Sendable () -> String)? = nil,
    condition: @escaping @Sendable () -> Bool
) async throws {
    let clock = ContinuousClock()
    let started = clock.now
    let deadline = clock.now.advanced(by: .seconds(timeout))
    // Return on the first true evaluation. Evaluating again after the loop would fail a
    // condition that held only briefly, such as an overlap a loaded host let end in between.
    while clock.now < deadline {
        if condition() { return }
        try await Task.sleep(for: .milliseconds(20))
    }
    guard !condition() else { return }

    var diagnostic = await waitFailureDiagnostic(
        failure,
        engine: engine,
        waitBudget: timeout,
        waited: durationSeconds(started.duration(to: clock.now))
    )
    if let mediaState {
        diagnostic += "; media_state=\(mediaState())"
    }
    #expect(Bool(false), Comment(rawValue: diagnostic))
}

private func waitFailureDiagnostic(
    _ failure: String,
    engine: DulcetAVPlayerEngine,
    waitBudget: TimeInterval,
    waited: TimeInterval,
    queueProbeTimeout: TimeInterval = 1
) async -> String {
    let liveness = await engine.probeQueueLivenessForTesting(timeout: queueProbeTimeout)
    return classifiedWaitFailureDiagnostic(
        failure,
        waitBudget: waitBudget,
        waited: waited,
        queueProbeTimeout: queueProbeTimeout,
        liveness: liveness
    )
}

private func classifiedWaitFailureDiagnostic(
    _ failure: String,
    waitBudget: TimeInterval,
    waited: TimeInterval,
    queueProbeTimeout: TimeInterval,
    liveness: DulcetAVPlayerEngineQueueLiveness
) -> String {
    let classification: [String]
    switch liveness {
    case .responsive:
        classification = [
            "failure_classification=DULCET_EXPECTATION_NOT_EMITTED",
            "cause=expected condition was not emitted despite a responsive engine queue",
        ]
    case .notResponsive:
        classification = [
            "failure_classification=DULCET_COREAUDIO_HOST_OVERLOAD",
            "cause=CoreAudio/host overload prevented the engine queue from servicing its liveness probe",
            "probe_timeout_seconds=\(formatSeconds(queueProbeTimeout))",
            "overload_evidence=engine queue not responsive; CoreAudio signature=HALC_ProxyIOContext::IOWorkLoop: skipping cycle due to overload",
        ]
    }

    return ([
        failure,
        "wait_budget_seconds=\(formatSeconds(waitBudget))",
        "waited_seconds=\(formatSeconds(waited))",
    ] + classification + [liveness.diagnosticDescription]).joined(separator: "; ")
}

private func durationSeconds(_ duration: Duration) -> TimeInterval {
    let components = duration.components
    return TimeInterval(components.seconds) +
        TimeInterval(components.attoseconds) / 1_000_000_000_000_000_000
}

private func formatSeconds(_ seconds: TimeInterval) -> String {
    String(format: "%.3f", seconds)
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
    private var requests = 0

    var description: String { "SuspendedPlaybackResource(<redacted>)" }

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

    var transportPlayingStates: [Bool] {
        lock.lock()
        defer { lock.unlock() }
        return transportStorage.map { $0.3 }
    }

    var transports: [(DulcetPlaybackSessionID, TimeInterval, Double, Bool)] {
        lock.lock()
        defer { lock.unlock() }
        return transportStorage
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

    private var clearStorage = 0

    var clearCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return clearStorage
    }

    func clear() {
        lock.lock()
        clearStorage += 1
        lock.unlock()
    }

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
        SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjYyLjEyLjEwMgAAAAAAAAAAAAAA/+M4wAAAAAAAAAAAAEluZm8AAAAPAAAAHgAACUgAHx8fJiYmLi4uNjY2Nj4+PkVFRU1NTU1VVVVdXV1kZGRkbGxsdHR0fHx8fIODg4uLi5OTk5Obm5uioqKqqqqqsrKyurq6wcHBwcnJydHR0dnZ2dng4ODo6Ojw8PDw+Pj4////AAAAAExhdmM2Mi4yOAAAAAAAAAAAAAAAACQCwAAAAAAAAAlI/Z1OtAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/+MYxAAMSJKUeU8AAAQJKAL3ve973vSlKUpSlL3u/f337xKp8t4t4m4uZc1WGxACAYrB9//+U9/R/gQ5z/QqJoc4WcQ3/GVE/+MYxAkO8PKQAZRQACo5Qhb/Bt4P1A9yADKiJOAfEwHMjrA71MPfAlAKgKisIoRX/5CPR6RD4fAr/hIGhKEgauAgACHD/76v/+MYxAgMkJaOedUAAv//UpJ0hyhZQW6ANVQAwMEHEWN0wV/2BMp+7//+1d/0sXZ9WUq1///e2mr///fVSoGATChjLmDULzq6/+MYxBAK0KYwAAa8RAw3hfTfVtzNFwScwtwTjA+APKAFk2y6yQqw1ioAC2iX4SJwBH3sQ3MEAKMURnPYubN3RNMQgKAwRlrF/+MYxB8MYKJMeAC6QABncMRP////o/1f/Z//2//+nuoYAAADAW7/L//c7FndVtGQDnR0JowGlEDABB0KgC6SjA37eyT0T////+MYxCgMiKJWWA48YP/R/////d7///TVFAAGGAog/1//wsSdFrSgxcU3kMRhLPB7XNqA1Aw3DwIr3dOAojKu7//o////9v///+MYxDAMKKJSWBY6YHL///hhlagDjpyGAaAyYHQKxhaivm0xVGZ1oiphOAqGB6BOYDgCxmMBhpgMPxr///izTUFgMCmEC5hZ/+MYxDoLIKY0AAewSLGLQZgljtGYpw0Y14uRgZA3gUCgRgDpnjwAKZzNb9X///glbSDJboxoQ0gU6pIw5gZTghOJNIgEUFDD/+MYxEgKyKY0AAb8RAsEgYC4AIVAFQ8WywrX//////////SqAsHwS+HJE9SCMAANGBOEuZBygJiOg6mA8AsYAIAKARdjjvxL/+MYxFcMiKY0AAa8RPn/////////o9EYYCgD6RFRmhQIYBASIDB8wIwnDHmUoMQcHYwFgFy/0Azz8xaW90//9jv///+tB4IA/+MYxF8LeKZQGAB8QBeHf/769GT/WqOODW8OaCJnj3zmdcIs0/aArtP/dV7PYqy36/60Sno//9fQEAAAEEkcy//1bopdATGx/+MYxGwLcKZQeAT8RIAgAiyZiZ6YxiCBAEUzaxDkXnLf//dav//6tf/X/22b///R3QM///6Z9VzJigQExTDaxMF0NAzsmmjI/+MYxHkLuJaTGAF0DiQnjBIAjAQDTNYCZy5T+9//////////6KIL8PHHjjzfIiGCoBmKgmHxNvG+AcGI4Cg4IC16mbKH3iH//+MYxIUMuKJiWAB6Cv///+v///3f//9CgoAEjZ1/X+1f4I+a/UrgaGFEZJgMAMXEw+rJzCDEhMAYFQCgPgAAZDRINbjn3u76/+MYxI0LwKZEEAZ8RH1f/v///r/9Po//+6qAAAMKi0///wJLRLzAACZAeaM8cyWYZ4jhupRcGgqGwYVQGpgdgADQCiOiNqmT/+MYxJkLaKJMGAC6QAHP/aj////Z/7/9P///7tFAX+3/+G2XkBoAqVAOAHMDIC4wgwjzFeIWPAf+I2vBqjExCPMIQEwwRwOT/+MYxKYPEKY+UC68YHYTQQMEEvZTf/6rv/f////6a/KK2wAAotFg090+/+JizirqL3BY8K8GAMHeYe7vJg/BcgUC4kAJUoaY/+MYxKQPeKY2MD68YNXe2Rc/6P/92//b///y3roUAAAYYCbX//DR14msqCl8gEEJiSGR5FExtyEAYOpQCysDoPjEpn//u//6/+MYxKEPmKYkwG+yZP/////Z//7KOj5NjKCUxoB/wACQGfjmDoJIaS8fxlFByGDKCGYEgDBgHgFlk0FF1uRl///////8b/6f/+MYxJ0NoKZKWBZ8YF3DAADYCgDXT9f/Ey5yWJIJgoSYNRgQBhmOWxiYewSpgKgQBYAFskHNtAsWd/3+7///+pUP//++Cso6/+MYxKEMoKJSWAC6CAgoBMcDA1YFyTENAQOVwSIBU7CQ5ACCzMCIAoAAFCoAqfiju///+v1//////rX///h9giXaNIiANBAG/+MYxKkM6KY8EAU8RMYEYRphCEAGpD5gZbAyhg7hAmBgBwYDYD5rODoIVq4tf/////////9SG4FAg///8Y07LOkhgYEy6MCw/+MYxLAMkKJOWBZ8YCTMjVNUxMAbTAhASLiv9GXeh2Xc0/o////2f///6///+CGFpOJ6hwAosB+YKACBhxhDnAckeaP4NZhh/+MYxLgNIKYwIAa8RgDRglgGmA8AcYEBcKqi+8bUIADACiWyvq//3WT500EIBRQmAAM79YDCg+Aw4BAbODYKAzBFD3/ar////+MYxL4NaKYwAAewSP//r//6///vbTUAFgMYkIMD//995qMx6Nxr//+Z18nRcByV5e3E1+t+YorYerQoUvMucWuYX+pk03YA/+MYxMMMAKZQeAY8RNQBshJBgQuwm/+gzp2Qmw9SYxeK3Lv/unZt85CpVgV//KigVJCg///JBQCkgoaJKoB/UpSl4oUMrjGP/+MYxM4LMKY0AAewSJIkUaWRItKgiCJMqhQ54oSXUIpQgIGjwNB0qCqw1///0///////29XneWpMQU1FNC4wqqqqqqqqqqqq/+MYxNwNGKZyWVUAAqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq/+MYxOIYCZKdkZpoAKqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq/+MYxLwNYKJgGckAAKqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq
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

/// A real `AVQueuePlayer` holding a current and a preloaded item whose bytes never arrive, under an
/// engine whose media-time samples all come from a manual clock. Play has been requested and the
/// outgoing item has reported progress through that clock -- the positive control that the
/// supplied-sample path reaches the engine -- before `advance()` makes the real player move on.
private struct PreloadBoundary {
    let player: AVQueuePlayer
    let engine: DulcetAVPlayerEngine
    let clock: ManualAVPlayerEngineClock
    let events: PlaybackEventRecorder
    let first: DulcetPlaybackPlan
    let next: DulcetPlaybackPlan
    let incomingItem: AVPlayerItem

    static func start() async throws -> PreloadBoundary {
        let player = AVQueuePlayer()
        let clock = ManualAVPlayerEngineClock()
        let engine = DulcetAVPlayerEngine(
            player: player,
            clock: clock,
            usesAVFoundationMediaStack: true,
            audioSession: RecordingAudioSession(),
            systemMediaControls: RecordingSystemMediaControls()
        )
        let events = PlaybackEventRecorder()
        engine.setEventListener { events.append($0) }
        let first = plan(resource: SuspendedPlaybackResource(), session: "gapless-a", attempt: "gapless-attempt-a")
        let next = plan(resource: SuspendedPlaybackResource(), session: "gapless-b", attempt: "gapless-attempt-b")

        _ = await execute(engine, .prepare(commandID: .init("prepare"), plan: first))
        #expect(await execute(engine, .play(commandID: .init("play"))) == .accepted(commandID: .init("play")))
        #expect(await execute(engine, .preloadNext(commandID: .init("preload"), plan: next))
            == .accepted(commandID: .init("preload")))
        let queued = player.items()
        try #require(queued.count == 2, "the real player must hold the current and the preloaded item")

        clock.tick(isPlaying: true, mediaPosition: 0.5, monotonicUptimeNanoseconds: 1_000_000_000)
        try #require(events.progressBeganAttemptIDs == [first.attemptID],
                     "the supplied sample did not reach the engine for the outgoing item")
        return PreloadBoundary(
            player: player,
            engine: engine,
            clock: clock,
            events: events,
            first: first,
            next: next,
            incomingItem: queued[1]
        )
    }

    func advance() async throws {
        player.advanceToNextItem()
        let (events, first, next) = (events, first, next)
        try await waitUntil(
            "the real AVQueuePlayer changed its current item but the engine made no boundary",
            engine: engine
        ) {
            events.containsAdvance(old: first.attemptID, new: next.attemptID)
        }
        try #require(player.currentItem === incomingItem, "the boundary under test is the preloaded item's")
    }
}

/// What the player itself says about media time, read independently of the engine's sampler.
///
/// `observe()` runs on each 20 ms poll of a wait and keeps, per item, the furthest media time seen
/// while that item was the player's current item; `describe()` reports those marks and the state
/// at the deadline. A clock that never moved leaves its item current at about zero. An item that
/// played to its end and one removed or failed without playing both leave `current_item=none`, and
/// only the marks tell those apart.
private final class PlayerMediaState: @unchecked Sendable {
    private let player: AVQueuePlayer
    private let first: AVPlayerItem
    private let next: AVPlayerItem?
    private let lock = NSLock()
    private var firstHighWater: TimeInterval?
    private var nextHighWater: TimeInterval?

    init(player: AVQueuePlayer, first: AVPlayerItem, next: AVPlayerItem?) {
        self.player = player
        self.first = first
        self.next = next
    }

    func observe() {
        guard let item = player.currentItem, item === first || item === next else { return }
        let seconds = CMTimeGetSeconds(item.currentTime())
        guard seconds.isFinite else { return }
        lock.lock()
        if item === first {
            firstHighWater = max(firstHighWater ?? 0, seconds)
        } else {
            nextHighWater = max(nextHighWater ?? 0, seconds)
        }
        lock.unlock()
    }

    func describe() -> String {
        let item = player.currentItem
        let which = item == nil ? "none" : item === first ? "first" : item === next ? "preloaded" : "other"
        let transport = switch player.timeControlStatus {
        case .paused: "paused"
        case .playing: "playing"
        case .waitingToPlayAtSpecifiedRate:
            "waiting(\(player.reasonForWaitingToPlay?.rawValue ?? "unknown"))"
        @unknown default: "unknown"
        }
        let timebaseRate = item?.timebase.map { formatSeconds(CMTimebaseGetRate($0)) } ?? "none"
        lock.lock()
        let firstMark = firstHighWater.map(formatSeconds) ?? "never_seen_current"
        let nextMark = next == nil ? "no_preload" : nextHighWater.map(formatSeconds) ?? "never_seen_current"
        lock.unlock()
        return "current_item=\(which) media_time=\(formatSeconds(CMTimeGetSeconds(player.currentTime()))) " +
            "transport=\(transport) timebase_rate=\(timebaseRate) " +
            "first_media_time_high_water=\(firstMark) preloaded_media_time_high_water=\(nextMark)"
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

    var progressBeganAttemptIDs: [DulcetPlaybackAttemptID] {
        snapshot.compactMap {
            if case let .playbackProgressBegan(attemptID, _, _) = $0 { attemptID } else { nil }
        }
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
    private var deferredDelay: TimeInterval?
    private var deferredFireAt: TimeInterval?
    private var monotonicTime: TimeInterval = 0

    var monotonicNow: TimeInterval {
        lock.lock()
        defer { lock.unlock() }
        return monotonicTime
    }

    func advanceMonotonic(by interval: TimeInterval) {
        lock.lock()
        monotonicTime += interval
        lock.unlock()
    }

    var scheduledDelay: TimeInterval? {
        lock.lock()
        defer { lock.unlock() }
        return deferredDelay
    }

    /// Fire the pending one-shot deadline (the audio-session grace period) without waiting for it.
    /// Returns false when nothing is scheduled, so a test cannot silently pass by firing nothing.
    @discardableResult
    func fireScheduledDeadline() -> Bool {
        lock.lock()
        let queue = deferredQueue
        let handler = deferredHandler
        if let deadline = deferredFireAt { monotonicTime = max(monotonicTime, deadline) }
        deferredQueue = nil
        deferredHandler = nil
        deferredDelay = nil
        deferredFireAt = nil
        lock.unlock()
        guard let queue, let handler else { return false }
        queue.sync(execute: handler)
        return true
    }

    func scheduleOnce(
        on queue: DispatchQueue,
        after interval: TimeInterval,
        handler: @escaping @Sendable () -> Void
    ) -> @Sendable () -> Void {
        lock.lock()
        deferredQueue = queue
        deferredHandler = handler
        deferredDelay = interval
        deferredFireAt = monotonicTime + interval
        lock.unlock()
        return { [weak self] in
            guard let self else { return }
            self.lock.lock()
            self.deferredQueue = nil
            self.deferredHandler = nil
            self.deferredDelay = nil
            self.deferredFireAt = nil
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

private final class BlockingSampleAVPlayerEngineClock: DulcetAVPlayerEngineClock, @unchecked Sendable {
    let wallClockNow = Date(timeIntervalSince1970: 1_788_000_000)

    private let lock = NSLock()
    private let sampleStarted = DispatchSemaphore(value: 0)
    private let sampleMayFinish = DispatchSemaphore(value: 0)
    private let mediaPosition: TimeInterval
    private var samplerQueue: DispatchQueue?
    private var samplerHandler: (@Sendable () -> Void)?
    private var shouldBlockNextSample = true

    init(mediaPosition: TimeInterval = 0) {
        self.mediaPosition = mediaPosition
    }

    func sample(player _: AVQueuePlayer) -> DulcetAVPlayerEngineClockSample {
        lock.lock()
        let shouldBlock = shouldBlockNextSample
        shouldBlockNextSample = false
        lock.unlock()
        if shouldBlock {
            sampleStarted.signal()
            sampleMayFinish.wait()
        }
        return DulcetAVPlayerEngineClockSample(
            isPlaying: true,
            mediaPosition: mediaPosition,
            monotonicTime: DulcetMonotonicInstant(uptimeNanoseconds: 0)
        )
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

    func scheduleOnce(
        on _: DispatchQueue,
        after _: TimeInterval,
        handler _: @escaping @Sendable () -> Void
    ) -> @Sendable () -> Void {
        {}
    }

    func fireSampler() -> Bool {
        lock.lock()
        let queue = samplerQueue
        let handler = samplerHandler
        lock.unlock()
        guard let queue, let handler else { return false }
        queue.sync(execute: handler)
        return true
    }

    func waitUntilSampleStarts() -> Bool {
        sampleStarted.wait(timeout: .now() + 1) == .success
    }

    func unblockSample() {
        sampleMayFinish.signal()
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
