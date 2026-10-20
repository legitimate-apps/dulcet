import AVFoundation
import Foundation

enum DulcetAVPlayerEngineQueueLiveness: Sendable {
    case responsive(turnaroundSeconds: TimeInterval)
    case notResponsive(waitedSeconds: TimeInterval)

    var diagnosticDescription: String {
        switch self {
        case let .responsive(turnaroundSeconds):
            "engine queue responsive; turnaround_seconds=\(Self.format(turnaroundSeconds))"
        case let .notResponsive(waitedSeconds):
            "engine queue not responsive; probe_wait_seconds=\(Self.format(waitedSeconds))"
        }
    }

    private static func format(_ seconds: TimeInterval) -> String {
        String(format: "%.3f", seconds)
    }
}

public final class DulcetAVPlayerEngine: DulcetApplePlaybackEngine, @unchecked Sendable {
    public static let sampleInterval: TimeInterval = 0.5
    public static let cadenceMaximum: TimeInterval = 2
    public static let emptyQueueAudioSessionGracePeriod: TimeInterval = 10
    static let startupRecoveryDelay: TimeInterval = 2
    static let startupRecoveryVerdictBudget: TimeInterval = 120

    private let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<UInt8>()
    private let mediaSamplingQueue: DispatchQueue
    private var player: AVQueuePlayer
    private let clock: any DulcetAVPlayerEngineClock
    private let usesAVFoundationMediaStack: Bool
    private let audioSession: any DulcetAudioSessionManaging
    private let audioSessionGracePeriod: TimeInterval
    private let systemMediaControls: any DulcetSystemMediaControlling
    private weak var remoteCommandRouter: (any DulcetRemotePlaybackCommandRouting)?
    private var remoteCommandCapabilities: DulcetRemoteCommandCapabilities
    private var listener: DulcetPlaybackEventHandler?
    private var current: PlayerItemContext?
    private var preloaded: PlayerItemContext?
    private var released = false
    private var desiredRate: Float = 1
    private var masterVolume: Float = 1
    private var gainVerificationGeneration: UInt64 = 0
    private let gainVerificationInterval = DispatchTimeInterval.milliseconds(20)
    private static let gainVerificationAttempts = 100
    private var crossfadeDuration: TimeInterval = 0
    private var overlap: CrossfadeTail?
    private var crossfadeTimeObserver: Any?
    private var crossfadeObservationGeneration: UInt64 = 0

    private struct CrossfadeTail {
        let player: AVQueuePlayer
        let context: PlayerItemContext
        let duration: TimeInterval
        var fraction: Float = 0
    }

    func currentPlayerVolumeForTesting() -> Float {
        var volume: Float = 0
        performOnQueueSynchronously { [self] in volume = player.volume }
        return volume
    }

    func currentPlayerRateForTesting() -> Float {
        var rate: Float = 0
        performOnQueueSynchronously { [self] in rate = player.rate }
        return rate
    }

    /// Transport/gain evidence from the actual players, read on the engine queue.
    func crossfadeStateForTesting() -> (incomingRate: Float, outgoingRate: Float,
                                      incomingVolume: Float, outgoingVolume: Float, outgoingPosition: Double)? {
        var state: (Float, Float, Float, Float, Double)?
        performOnQueueSynchronously { [self] in
            guard let overlap else { return }
            state = (player.rate, overlap.player.rate, player.volume, overlap.player.volume,
                     CMTimeGetSeconds(overlap.player.currentTime()))
        }
        return state
    }
    /// Runs `command` in the same engine-queue turn that finds an overlap active, so a test cannot
    /// mistake a tail that ended on its own for one the command disposed. Returns false, without
    /// running the command, when no overlap is active.
    func executeDuringOverlapForTesting(
        _ command: DulcetPlaybackCommand,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) -> Bool {
        var ran = false
        performOnQueueSynchronously { [self] in
            guard overlap != nil else { return }
            ran = true
            executeOnQueue(command, completion: completion)
        }
        return ran
    }
    private var playerObservers: [NSKeyValueObservation] = []
    private var notificationObservers: [NSObjectProtocol] = []
    private var cancelSampler: (@Sendable () -> Void)?
    private var cancelAudioSessionGrace: (@Sendable () -> Void)?
    private var activeAudioSessionID: DulcetPlaybackSessionID?
    private var interruptionWasPlaying = false
    /// Validated artwork bytes per session: pruned to the current one at each advance and emptied
    /// whenever the engine lets go of its items.
    private var artworkBySession: [DulcetPlaybackSessionID: Data] = [:]
    private var resourceLoaderTraceHandler:
        (@Sendable (DulcetPlaybackResourceLoaderTraceEvent) -> Void)?

    public convenience init(
        player: AVQueuePlayer = AVQueuePlayer(),
        audioSession: any DulcetAudioSessionManaging = DulcetPlatformAudioSession(),
        audioSessionGracePeriod: TimeInterval = DulcetAVPlayerEngine.emptyQueueAudioSessionGracePeriod,
        systemMediaControls: any DulcetSystemMediaControlling = DulcetPlatformSystemMediaControls(),
        remoteCommandRouter: (any DulcetRemotePlaybackCommandRouting)? = nil,
        remoteCommandCapabilities: DulcetRemoteCommandCapabilities = .init()
    ) {
        self.init(
            player: player,
            clock: DulcetAVPlayerEngineSystemClock(),
            usesAVFoundationMediaStack: true,
            audioSession: audioSession,
            audioSessionGracePeriod: audioSessionGracePeriod,
            systemMediaControls: systemMediaControls,
            remoteCommandRouter: remoteCommandRouter,
            remoteCommandCapabilities: remoteCommandCapabilities
        )
    }

    init(
        player: AVQueuePlayer = AVQueuePlayer(),
        clock: any DulcetAVPlayerEngineClock,
        usesAVFoundationMediaStack: Bool,
        audioSession: any DulcetAudioSessionManaging = DulcetPlatformAudioSession(),
        audioSessionGracePeriod: TimeInterval = DulcetAVPlayerEngine.emptyQueueAudioSessionGracePeriod,
        systemMediaControls: any DulcetSystemMediaControlling = DulcetPlatformSystemMediaControls(),
        remoteCommandRouter: (any DulcetRemotePlaybackCommandRouting)? = nil,
        remoteCommandCapabilities: DulcetRemoteCommandCapabilities = .init(),
        queue: DispatchQueue = DispatchQueue(label: "com.legitimateapps.dulcet.playback-engine"),
        mediaSamplingQueue: DispatchQueue = DispatchQueue(
            label: "com.legitimateapps.dulcet.playback-engine.media-sampling"
        )
    ) {
        self.player = player
        self.masterVolume = player.volume
        self.clock = clock
        self.usesAVFoundationMediaStack = usesAVFoundationMediaStack
        self.audioSession = audioSession
        self.audioSessionGracePeriod = max(0, audioSessionGracePeriod)
        self.systemMediaControls = systemMediaControls
        self.remoteCommandRouter = remoteCommandRouter
        self.remoteCommandCapabilities = remoteCommandCapabilities
        self.queue = queue
        self.mediaSamplingQueue = mediaSamplingQueue
        superInitQueue()
    }

    deinit {
        tearDownWithoutEvent()
    }

    public func execute(
        _ command: DulcetPlaybackCommand,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) {
        queue.async { [weak self] in
            guard let self else {
                completion(.rejected(commandID: command.commandID, reason: .engineReleased))
                return
            }
            self.executeOnQueue(command, completion: completion)
        }
    }

    public func setEventListener(_ listener: DulcetPlaybackEventHandler?) {
        performOnQueueSynchronously { [self] in
            self.listener = listener
            guard listener != nil, let current else { return }
            emit(
                .observationResynced(
                    attemptID: current.plan.attemptID,
                    snapshot: current.snapshot()
                )
            )
        }
    }

    public func setRemoteCommandRouter(
        _ router: (any DulcetRemotePlaybackCommandRouting)?
    ) {
        performOnQueueSynchronously { [self] in
            remoteCommandRouter = router
        }
    }

    func setResourceLoaderTraceHandlerForTesting(
        _ handler: (@Sendable (DulcetPlaybackResourceLoaderTraceEvent) -> Void)?
    ) {
        performOnQueueSynchronously { [self] in
            resourceLoaderTraceHandler = handler
        }
    }

    func reportCurrentItemReadyForTesting(
        duration: TimeInterval?,
        seekability: DulcetPlaybackSeekability
    ) {
        performOnQueueSynchronously { [self] in
            guard let current else { return }
            itemBecameReady(current, duration: duration, seekability: seekability)
        }
    }

    func reportCurrentItemDurationForTesting(
        _ duration: TimeInterval,
        seekability: DulcetPlaybackSeekability
    ) {
        performOnQueueSynchronously { [self] in
            guard let current else { return }
            updateDuration(current, duration: duration, seekability: seekability)
        }
    }

    /// Drive natural end-of-item on the non-AVFoundation test path.
    ///
    /// This calls exactly the `itemEnded` the `.AVPlayerItemDidPlayToEndTime` observer calls, so a
    /// test using it exercises the production path rather than a parallel one; only the trigger
    /// differs. The observer is not registered when the AVFoundation media stack is disabled, which
    /// is why the seam is needed at all. It mirrors `reportCurrentItemReadyForTesting`.
    func reportCurrentItemEndedForTesting() {
        performOnQueueSynchronously { [self] in
            guard let current else { return }
            itemEnded(current)
        }
    }

    /// Drives a failure of the current item on the non-AVFoundation test path through exactly the
    /// `itemFailed` the item-status and failed-to-play observers call; only the trigger differs.
    func reportCurrentItemFailedForTesting(_ failure: DulcetPlaybackFailure) {
        performOnQueueSynchronously { [self] in
            guard let current else { return }
            itemFailed(current, reported: failure)
        }
    }

    /// Drives a playback stall on the non-AVFoundation test path through exactly the
    /// `beginBuffering` the `.AVPlayerItemPlaybackStalled` observer calls.
    func reportCurrentItemStalledForTesting() {
        performOnQueueSynchronously { [self] in
            guard let current else { return }
            beginBuffering(current)
        }
    }

    func reportPlayerTransportChangedForTesting() {
        performOnQueueSynchronously { [self] in timeControlStatusChanged() }
    }

    func currentItemHasPendingRequestsForTesting() -> Bool {
        var pending = false
        performOnQueueSynchronously { [self] in pending = current?.loader.hasPendingRequests == true }
        return pending
    }

    /// Whether artwork is held for a session. Artwork for a session the engine does not hold is
    /// dropped, so the owner must deliver it after the engine accepted that session's plan.
    func holdsArtworkForTesting(_ sessionID: DulcetPlaybackSessionID) -> Bool {
        var held = false
        performOnQueueSynchronously { [self] in held = artworkBySession[sessionID] != nil }
        return held
    }

    /// Measures whether the serial engine queue can service newly submitted work.
    ///
    /// The timeout races on a separate queue, so this diagnostic still completes when the engine
    /// queue is blocked. The queued probe may run later, but the one-shot result cannot change after
    /// the timeout has reported the queue unresponsive.
    func probeQueueLivenessForTesting(
        timeout: TimeInterval = 1
    ) async -> DulcetAVPlayerEngineQueueLiveness {
        let clock = ContinuousClock()
        let started = clock.now
        let timeout = max(0.001, timeout)
        return await withCheckedContinuation { continuation in
            let result = QueueLivenessProbeResult(continuation: continuation)
            queue.async {
                result.resolve(.responsive(
                    turnaroundSeconds: Self.seconds(from: started.duration(to: clock.now))
                ))
            }
            DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + timeout) {
                result.resolve(.notResponsive(
                    waitedSeconds: Self.seconds(from: started.duration(to: clock.now))
                ))
            }
        }
    }

    /// Applies queue/role capability changes only to the named live session.
    @discardableResult
    public func updateRemoteCommandCapabilities(
        _ capabilities: DulcetRemoteCommandCapabilities,
        for sessionID: DulcetPlaybackSessionID
    ) -> Bool {
        var applied = false
        performOnQueueSynchronously { [self] in
            guard let current, current.plan.playbackSessionID == sessionID else { return }
            applied = true
            guard remoteCommandCapabilities != capabilities else { return }
            remoteCommandCapabilities = capabilities
            publishNowPlaying(current)
        }
        return applied
    }

    /// Supplies artwork for a session's system Now Playing entry. It may arrive before the session
    /// is current (a preloaded next item), in which case it is shown when the session takes over.
    public func setNowPlayingArtwork(_ imageData: Data?, for sessionID: DulcetPlaybackSessionID) {
        performOnQueueSynchronously { [self] in
            guard current?.plan.playbackSessionID == sessionID ||
                preloaded?.plan.playbackSessionID == sessionID else { return }
            artworkBySession[sessionID] = imageData
            if let current, current.plan.playbackSessionID == sessionID {
                publishNowPlaying(current)
            }
        }
    }

    private func superInitQueue() {
        queue.setSpecific(key: queueKey, value: 1)
        player.actionAtItemEnd = .advance
        systemMediaControls.setCommandHandler { [weak self] command in
            self?.handleRemoteCommandSynchronously(command) ?? false
        }
        audioSession.setEventHandler { [weak self] event in
            self?.enqueue { $0.handleAudioSessionEvent(event) }
        }
        if usesAVFoundationMediaStack {
            observePlayer()
        }
        startSampler()
    }

    private static func seconds(from duration: Duration) -> TimeInterval {
        let components = duration.components
        return TimeInterval(components.seconds) +
            TimeInterval(components.attoseconds) / 1_000_000_000_000_000_000
    }

    private func executeOnQueue(
        _ command: DulcetPlaybackCommand,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) {
        dispatchPrecondition(condition: .onQueue(queue))
        if released {
            completion(.rejected(commandID: command.commandID, reason: .engineReleased))
            return
        }
        switch command {
        case let .prepare(commandID, plan):
            prepare(plan, replacing: false, commandID: commandID, completion: completion)
        case let .play(commandID):
            guard let current else {
                completion(.rejected(commandID: commandID, reason: .invalidState))
                return
            }
            let wasRequested = current.playRequested
            requestStartupPlayback(current)
            player.playImmediately(atRate: desiredRate)
            updateStartupRecovery(current)
            updateSystemTransport(for: current, isPlaying: true)
            if current.readyEmitted && !wasRequested {
                emit(.resumed(
                    attemptID: current.plan.attemptID,
                    position: currentPosition(for: current)
                ))
            }
            completion(.accepted(commandID: commandID))
        case let .pause(commandID):
            guard let current else {
                completion(.rejected(commandID: commandID, reason: .invalidState))
                return
            }
            pause(context: current)
            completion(.completed(commandID: commandID, result: .withoutData))
        case let .stop(commandID):
            stop(reason: .user)
            completion(.completed(commandID: commandID, result: .withoutData))
        case let .seek(commandID, position):
            seek(commandID: commandID, position: position, completion: completion)
        case let .setVolume(commandID, volume):
            guard volume.isFinite, (0...1).contains(volume) else {
                completion(.rejected(commandID: commandID, reason: .invalidState))
                return
            }
            masterVolume = Float(volume)
            applyFadeVolumes()
            completion(.completed(commandID: commandID, result: .withoutData))
        case let .setCrossfade(commandID, attemptID, duration):
            guard current?.plan.attemptID == attemptID, duration.isFinite, duration >= 0 else {
                completion(.rejected(commandID: commandID, reason: .invalidState))
                return
            }
            crossfadeDuration = duration
            if usesAVFoundationMediaStack {
                if duration > 0 || overlap != nil { installCrossfadeTimeObserver() }
                else { removeCrossfadeTimeObserver() }
            }
            completion(.completed(commandID: commandID, result: .withoutData))
        case let .setRate(commandID, rate):
            guard rate.isFinite, rate > 0, rate <= 2 else {
                completion(.rejected(commandID: commandID, reason: .unsupported))
                return
            }
            desiredRate = Float(rate)
            if player.timeControlStatus == .playing {
                player.rate = desiredRate
            }
            if let overlap, overlap.player.rate > 0 { overlap.player.rate = desiredRate }
            if let current {
                current.rate = rate
                emit(.rateChanged(attemptID: current.plan.attemptID, rate: rate))
                updateSystemTransport(for: current, isPlaying: current.playRequested)
            }
            completion(.completed(commandID: commandID, result: .withoutData))
        case let .replaceCurrent(commandID, plan):
            guard let current, current.plan.playbackSessionID == plan.playbackSessionID else {
                completion(.rejected(commandID: commandID, reason: .invalidState))
                return
            }
            guard plan.deliveryProtocol == .httpProgressive,
                  plan.resource is any DulcetPlaybackResourceLoading else {
                emit(.failedBeforeStart(attemptID: plan.attemptID, error: .unsupportedPlan))
                completion(.rejected(commandID: commandID, reason: .unsupported))
                return
            }
            emit(
                .attemptReplaced(
                    oldAttemptID: current.plan.attemptID,
                    newAttemptID: plan.attemptID
                )
            )
            publishNowPlaying(
                plan: plan,
                duration: current.duration,
                position: currentPosition(for: current),
                seekability: current.seekability,
                isPlaying: current.playRequested
            )
            prepare(plan, replacing: true, commandID: commandID, completion: completion)
        case let .preloadNext(commandID, plan):
            preload(plan, commandID: commandID, completion: completion)
        case let .discardPreloaded(commandID, attemptID):
            guard let preloaded, preloaded.plan.attemptID == attemptID else {
                completion(.rejected(commandID: commandID, reason: .invalidState))
                return
            }
            discardPreloadedItem(preloaded)
            completion(.completed(commandID: commandID, result: .withoutData))
        case let .release(commandID):
            releaseEngine()
            completion(.completed(commandID: commandID, result: .withoutData))
        }
    }

    private func prepare(
        _ plan: DulcetPlaybackPlan,
        replacing: Bool,
        commandID: DulcetPlaybackCommandID,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) {
        guard plan.deliveryProtocol == .httpProgressive else {
            emit(.failedBeforeStart(attemptID: plan.attemptID, error: .unsupportedPlan))
            completion(.rejected(commandID: commandID, reason: .unsupported))
            return
        }
        guard let resource = plan.resource as? any DulcetPlaybackResourceLoading else {
            emit(.failedBeforeStart(attemptID: plan.attemptID, error: .unsupportedPlan))
            completion(.rejected(commandID: commandID, reason: .unsupported))
            return
        }
        if !replacing, current != nil {
            completion(.rejected(commandID: commandID, reason: .invalidState))
            return
        }
        guard activateAudioSessionIfNeeded(for: plan, commandID: commandID, completion: completion) else {
            return
        }
        endOverlap()
        removeCrossfadeTimeObserver()
        crossfadeDuration = 0
        let wasPlaying = current?.playRequested == true
        if !replacing { interruptionWasPlaying = false }
        let context = makeContext(plan: plan, resource: resource, isPreloaded: false)
        current?.invalidate()
        current = context
        if usesAVFoundationMediaStack {
            player.removeAllItems()
            player.insert(context.item, after: nil)
            if let preloaded {
                player.insert(preloaded.item, after: context.item)
            }
        }
        if usesAVFoundationMediaStack { installCrossfadeTimeObserver() }
        emit(.preparing(attemptID: plan.attemptID))
        // Publish after activation, without waiting for stream readiness or artwork. Cold
        // transcodes and restored queues can spend time preparing an otherwise valid track.
        publishNowPlaying(context)
        if wasPlaying {
            requestStartupPlayback(context)
            player.playImmediately(atRate: desiredRate)
            updateStartupRecovery(context)
        }
        completion(.accepted(commandID: commandID))
    }

    private func preload(
        _ plan: DulcetPlaybackPlan,
        commandID: DulcetPlaybackCommandID,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) {
        guard let current, plan.playbackSessionID != current.plan.playbackSessionID,
              !current.endEmitted else {
            // An item that already ended cannot be preloaded behind: AVQueuePlayer may have
            // removed it, and the boundary has passed. The owner falls back to a fresh start.
            completion(.rejected(commandID: commandID, reason: .invalidState))
            return
        }
        if usesAVFoundationMediaStack, !player.items().contains(where: { $0 === current.item }) {
            completion(.rejected(commandID: commandID, reason: .invalidState))
            return
        }
        guard plan.deliveryProtocol == .httpProgressive,
              let resource = plan.resource as? any DulcetPlaybackResourceLoading else {
            emit(.failedBeforeStart(attemptID: plan.attemptID, error: .unsupportedPlan))
            completion(.rejected(commandID: commandID, reason: .unsupported))
            return
        }
        if let existing = preloaded {
            discardPreloadedItem(existing)
        }
        let context = makeContext(plan: plan, resource: resource, isPreloaded: true)
        preloaded = context
        if usesAVFoundationMediaStack {
            player.insert(context.item, after: current.item)
        }
        emit(.preparing(attemptID: plan.attemptID))
        completion(.accepted(commandID: commandID))
    }

    private func discardPreloadedItem(_ context: PlayerItemContext) {
        context.invalidate()
        if usesAVFoundationMediaStack, player.items().contains(where: { $0 === context.item }) {
            player.remove(context.item)
        }
        artworkBySession[context.plan.playbackSessionID] = nil
        if preloaded === context { preloaded = nil }
    }

    private func seek(
        commandID: DulcetPlaybackCommandID,
        position: TimeInterval,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) {
        guard let context = current,
              context.seekability == .seekable,
              position.isFinite,
              position >= 0 else {
            completion(.rejected(commandID: commandID, reason: .invalidState))
            return
        }
        endOverlap()
        let from = currentPosition(for: context)
        context.seekGeneration &+= 1
        let seekGeneration = context.seekGeneration
        context.isSeeking = true
        removeCrossfadeTimeObserver()
        let requested = CMTime(seconds: position, preferredTimescale: 1_000)
        player.seek(to: requested, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] success in
            guard let self else {
                completion(.rejected(commandID: commandID, reason: .engineReleased))
                return
            }
            self.queue.async {
                guard self.current === context, context.seekGeneration == seekGeneration else {
                    completion(.rejected(commandID: commandID, reason: .invalidState))
                    return
                }
                context.isSeeking = false
                if self.usesAVFoundationMediaStack { self.installCrossfadeTimeObserver() }
                context.lastSampledPosition = position
                let event: DulcetPlaybackEvent = success
                    ? .seekCompleted(attemptID: context.plan.attemptID, from: from, to: position)
                    : .seekFailed(attemptID: context.plan.attemptID, from: from, to: position)
                self.emit(event)
                if success {
                    self.updateSystemTransport(
                        for: context,
                        isPlaying: context.playRequested
                    )
                }
                completion(
                    success
                        ? .completed(commandID: commandID, result: .withoutData)
                        : .rejected(commandID: commandID, reason: .failed(.engine))
                )
            }
        }
    }

    private func stop(reason: DulcetPlaybackSkipReason) {
        interruptionWasPlaying = false
        endOverlap()
        crossfadeDuration = 0
        removeCrossfadeTimeObserver()
        guard let current else {
            player.pause()
            player.removeAllItems()
            return
        }
        let position = currentPosition(for: current)
        player.pause()
        emit(.skipped(attemptID: current.plan.attemptID, position: position, reason: reason))
        current.invalidate()
        preloaded?.invalidate()
        self.current = nil
        preloaded = nil
        artworkBySession = [:]
        player.removeAllItems()
        deactivateAudioSessionImmediately()
        systemMediaControls.clear()
    }

    private func releaseEngine() {
        guard !released else { return }
        interruptionWasPlaying = false
        endOverlap()
        removeCrossfadeTimeObserver()
        player.pause()
        if let current {
            emit(
                .engineTornDown(
                    attemptID: current.plan.attemptID,
                    reason: .released
                )
            )
        }
        current?.invalidate()
        preloaded?.invalidate()
        current = nil
        preloaded = nil
        artworkBySession = [:]
        player.removeAllItems()
        playerObservers.forEach { $0.invalidate() }
        playerObservers.removeAll()
        notificationObservers.forEach(NotificationCenter.default.removeObserver)
        notificationObservers.removeAll()
        cancelSampler?()
        cancelSampler = nil
        cancelAudioSessionGrace?()
        cancelAudioSessionGrace = nil
        deactivateAudioSessionImmediately()
        audioSession.setEventHandler(nil)
        systemMediaControls.setCommandHandler(nil)
        systemMediaControls.clear()
        released = true
    }

    private func tearDownWithoutEvent() {
        performOnQueueSynchronously { [self] in
            endOverlap()
            removeCrossfadeTimeObserver()
            player.pause()
            current?.invalidate()
            preloaded?.invalidate()
            playerObservers.forEach { $0.invalidate() }
            notificationObservers.forEach(NotificationCenter.default.removeObserver)
            cancelSampler?()
            cancelAudioSessionGrace?()
            audioSession.deactivate()
            audioSession.setEventHandler(nil)
            systemMediaControls.setCommandHandler(nil)
            systemMediaControls.clear()
        }
    }

    private func makeContext(
        plan: DulcetPlaybackPlan,
        resource: any DulcetPlaybackResourceLoading,
        isPreloaded: Bool
    ) -> PlayerItemContext {
        let loader = DulcetAVAssetResourceLoaderDelegate(
            resource: resource,
            attemptID: plan.attemptID,
            expectedContainer: plan.expectedContainer,
            traceHandler: resourceLoaderTraceHandler
        ) { [weak self] attemptID, error, refreshReason in
            self?.enqueue {
                $0.handleResourceFailure(
                    attemptID: attemptID,
                    error: error,
                    refreshReason: refreshReason
                )
            }
        }
        let opaqueURL = URL(
            string: "\(DulcetAVAssetResourceLoaderDelegate.scheme)://playback/\(UUID().uuidString)"
        )!
        let asset = AVURLAsset(url: opaqueURL)
        asset.resourceLoader.setDelegate(loader, queue: queue)
        let item = AVPlayerItem(asset: asset)
        let context = PlayerItemContext(
            plan: plan,
            item: item,
            loader: loader,
            isPreloaded: isPreloaded,
            rate: Double(desiredRate)
        )
        if usesAVFoundationMediaStack {
            observe(context)
        }
        return context
    }

    private func observePlayer() {
        installCrossfadeTimeObserver()
        playerObservers.append(
            player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] _, _ in
                self?.enqueue { $0.timeControlStatusChanged() }
            }
        )
        playerObservers.append(
            player.observe(\.currentItem, options: [.new]) { [weak self] _, _ in
                self?.enqueue { $0.currentItemChanged() }
            }
        )
    }

    private func observe(_ context: PlayerItemContext) {
        // A crossfade reuses this context with a fresh item on the same asset. Fence on the
        // engine queue as well as at delivery: the replaced item's callbacks may already queue.
        let observedItem = context.item
        context.observers.append(
            observedItem.observe(\.status, options: [.initial, .new]) { [weak self, weak context] item, _ in
                guard let context else { return }
                self?.enqueue {
                    guard context.item === item else { return }
                    $0.itemStatusChanged(context)
                }
            }
        )
        context.observers.append(
            observedItem.observe(\.duration, options: [.new]) { [weak self, weak context] item, _ in
                guard let context else { return }
                self?.enqueue {
                    guard context.item === item else { return }
                    $0.durationChanged(context)
                }
            }
        )
        context.observers.append(
            observedItem.observe(\.seekableTimeRanges, options: [.new]) { [weak self, weak context] item, _ in
                guard let context else { return }
                self?.enqueue {
                    guard context.item === item else { return }
                    $0.durationChanged(context)
                }
            }
        )
        let center = NotificationCenter.default
        notificationObservers.append(
            center.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: observedItem,
                queue: nil
            ) { [weak self, weak context, weak observedItem] _ in
                guard let context, let observedItem else { return }
                self?.enqueue {
                    guard context.item === observedItem else { return }
                    $0.itemEnded(context)
                }
            }
        )
        notificationObservers.append(
            center.addObserver(
                forName: .AVPlayerItemFailedToPlayToEndTime,
                object: observedItem,
                queue: nil
            ) { [weak self, weak context, weak observedItem] notification in
                guard let context, let observedItem else { return }
                // A decode failure arrives HERE, with the item still `readyToPlay` and its
                // `error` nil: without this the engine reported it as its own `.engine` failure.
                let failure = (notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)
                    .map(DulcetApplePlaybackErrorSanitizer.avFoundationFailure)
                self?.enqueue {
                    guard context.item === observedItem else { return }
                    $0.itemFailed(context, reported: failure)
                }
            }
        )
        notificationObservers.append(
            center.addObserver(
                forName: .AVPlayerItemPlaybackStalled,
                object: observedItem,
                queue: nil
            ) { [weak self, weak context, weak observedItem] _ in
                guard let context, let observedItem else { return }
                self?.enqueue {
                    guard context.item === observedItem else { return }
                    $0.beginBuffering(context)
                }
            }
        )
    }

    private func itemStatusChanged(_ context: PlayerItemContext) {
        guard isActive(context) else { return }
        switch context.item.status {
        case .readyToPlay:
            let duration = finiteSeconds(context.item.duration)
            itemBecameReady(context, duration: duration, seekability: seekability(context))
        case .failed:
            itemFailed(context)
        case .unknown:
            break
        @unknown default:
            itemFailed(context)
        }
    }

    private func itemBecameReady(
        _ context: PlayerItemContext,
        duration: TimeInterval?,
        seekability: DulcetPlaybackSeekability
    ) {
        guard isActive(context), !context.readyEmitted else { return }
        context.readyEmitted = true
        context.duration = duration
        context.seekability = seekability
        emit(
            .ready(
                attemptID: context.plan.attemptID,
                duration: duration,
                seekability: seekability
            )
        )
        if current === context {
            publishNowPlaying(context)
            updateStartupRecovery(context)
        }
    }

    private func durationChanged(_ context: PlayerItemContext) {
        guard isActive(context), let duration = finiteSeconds(context.item.duration) else { return }
        updateDuration(context, duration: duration, seekability: seekability(context))
    }

    private func updateDuration(
        _ context: PlayerItemContext,
        duration: TimeInterval,
        seekability: DulcetPlaybackSeekability
    ) {
        guard isActive(context), duration.isFinite, duration > 0 else { return }
        let durationChanged = context.duration == nil || abs((context.duration ?? 0) - duration) > 0.001
        let seekabilityChanged = context.seekability != seekability
        guard durationChanged || seekabilityChanged else { return }
        context.duration = duration
        context.seekability = seekability
        if durationChanged { emit(.durationChanged(attemptID: context.plan.attemptID, duration: duration)) }
        if seekabilityChanged, context.readyEmitted {
            // The core routes remote seeks from its observation, not command-center flags.
            // Resync the existing attempt without sending another Ready or resetting progress.
            emit(.observationResynced(attemptID: context.plan.attemptID, snapshot: context.snapshot()))
        }
        if current === context, context.readyEmitted { publishNowPlaying(context) }
    }

    private func itemEnded(_ context: PlayerItemContext) {
        guard isActive(context), !context.endEmitted else { return }
        context.endEmitted = true
        context.cancelStartupRecovery()
        emit(
            .endedNaturally(
                attemptID: context.plan.attemptID,
                finalPosition: finiteSeconds(context.item.duration) ?? context.lastSampledPosition
            )
        )
        updateSystemTransport(for: context, isPlaying: false)
        if current === context, preloaded == nil {
            scheduleAudioSessionDeactivationAfterGrace()
        }
    }

    private func itemFailed(_ context: PlayerItemContext, reported: DulcetPlaybackFailure? = nil) {
        guard isActive(context), !context.failureEmitted, !context.waitingForRefresh else { return }
        context.failureEmitted = true
        context.cancelStartupRecovery()
        stopPresentingAsPlaying(afterFailureOf: context)
        let failure = context.item.error.map { closedFailure(for: $0) } ?? reported ?? closedFailure(for: nil)
        if context.progressBegan {
            emit(
                .failedAfterPartial(
                    attemptID: context.plan.attemptID,
                    position: context.lastSampledPosition,
                    error: failure
                )
            )
        } else {
            emit(.failedBeforeStart(attemptID: context.plan.attemptID, error: failure))
        }
    }

    private func handleResourceFailure(
        attemptID: DulcetPlaybackAttemptID,
        error: DulcetPlaybackFailure,
        refreshReason: DulcetPlaybackSourceRefreshReason?
    ) {
        guard let context = context(for: attemptID), !context.failureEmitted else { return }
        if refreshReason == .unauthorized || refreshReason == .expired {
            context.waitingForRefresh = true
            context.cancelStartupRecovery()
            // Only the CURRENT item may pause the shared player. A preloaded item that needs a
            // refresh is reported and dropped by its owner; pausing here would silence the song
            // that is playing, with no Paused event, for a failure that is not its own.
            if context === current {
                context.playRequested = false
                endOverlap()
                player.pause()
                updateSystemTransport(for: context, isPlaying: false)
            }
            emit(
                .sourceRefreshRequired(
                    attemptID: attemptID,
                    reason: refreshReason!
                )
            )
            return
        }
        context.failureEmitted = true
        context.cancelStartupRecovery()
        stopPresentingAsPlaying(afterFailureOf: context)
        if context.progressBegan {
            emit(
                .failedAfterPartial(
                    attemptID: attemptID,
                    position: context.lastSampledPosition,
                    error: error
                )
            )
        } else {
            emit(.failedBeforeStart(attemptID: attemptID, error: error))
        }
    }

    /// A failed current item is not playing, and the system must not say it is: left as it was,
    /// the lock screen, Control Center and a headset kept offering Pause for an item that had
    /// stopped, and its elapsed time went on counting up. Shown as paused, the system offers Play,
    /// which the owner turns into Try Again (spec §12.1, §15). The player is paused too, so audio
    /// already buffered does not go on sounding under a failure. A failed PRELOADED item leaves
    /// the current one alone: its failure is not the current item's.
    private func stopPresentingAsPlaying(afterFailureOf context: PlayerItemContext) {
        guard context === current else { return }
        interruptionWasPlaying = false
        endOverlap()
        context.playRequested = false
        player.pause()
        updateSystemTransport(for: context, isPlaying: false, rateOverride: 0)
    }

    private func currentItemChanged() {
        guard let outgoing = current, let incoming = preloaded else { return }
        guard player.currentItem === incoming.item else { return }
        promotePreloaded(outgoing: outgoing, incoming: incoming, retainingTail: false)
    }

    private func promotePreloaded(
        outgoing: PlayerItemContext, incoming: PlayerItemContext, retainingTail: Bool
    ) {
        if !outgoing.endEmitted {
            outgoing.endEmitted = true
            outgoing.cancelStartupRecovery()
            emit(.endedNaturally(
                attemptID: outgoing.plan.attemptID,
                finalPosition: retainingTail ? outgoing.lastSampledPosition :
                    (finiteSeconds(outgoing.item.duration) ?? outgoing.lastSampledPosition)
            ))
        }
        emit(.advancedToPreloaded(
            oldAttemptID: outgoing.plan.attemptID, newAttemptID: incoming.plan.attemptID
        ))
        outgoing.invalidate()
        crossfadeDuration = 0
        if !retainingTail { removeCrossfadeTimeObserver() }
        incoming.isPreloaded = false
        // The listener asked for music, not for one item: the preloaded item inherits the
        // outgoing item's play request. Without this the sampler -- which only samples a context
        // whose play was requested -- never sees the new item progress, so it never reports
        // PlaybackProgressBegan and the play is never scrobbled, while audio is plainly playing.
        incoming.playRequested = outgoing.playRequested
        if incoming.playRequested { requestStartupPlayback(incoming) }
        current = incoming
        preloaded = nil
        artworkBySession = artworkBySession.filter { $0.key == incoming.plan.playbackSessionID }
        activeAudioSessionID = incoming.plan.playbackSessionID
        cancelAudioSessionGrace?()
        cancelAudioSessionGrace = nil
        if incoming.readyEmitted || retainingTail {
            publishNowPlaying(incoming)
        }
        updateStartupRecovery(incoming)
    }

    /// Keep the existing decoder and resource loader running for the tail. Only the queued
    /// incoming asset gets a fresh item in a new queue, which becomes the main player. AVPlayerItem
    /// attachment outlives remove(_:), so reusing that item would throw during the handoff. The
    /// asset and its loader stay preloaded; this needs no second
    /// request or seek of the outgoing stream, so non-seekable transcodes use the same path.
    /// With duration zero no item/player transfer occurs: AVQueuePlayer owns the gapless boundary.
    private func updateCrossfade(at position: TimeInterval) {
        guard position.isFinite, let current, current.playRequested,
              !current.failureEmitted, !current.isSeeking else { return }
        if var tail = overlap {
            // Incoming media time, rather than wall time, stops the ramp while it buffers.
            tail.fraction = Float(min(1, max(0, position / tail.duration)))
            overlap = tail
            applyFadeVolumes()
            if tail.fraction >= 1 || tail.player.currentItem == nil { endOverlap() }
            return
        }
        guard crossfadeDuration > 0, let duration = current.duration,
              position >= duration - crossfadeDuration, position < duration,
              player.rate > 0, !current.endEmitted,
              let incoming = preloaded, !incoming.failureEmitted, !incoming.waitingForRefresh,
              incoming.item.status == .readyToPlay,
              player.currentItem === current.item else { return }
        let remaining = duration - position
        let outgoingPlayer = player
        removeCrossfadeTimeObserver()
        playerObservers.forEach { $0.invalidate() }
        playerObservers.removeAll()
        outgoingPlayer.remove(incoming.item)
        let incomingAsset = incoming.item.asset
        incoming.invalidate()
        incoming.item = AVPlayerItem(asset: incomingAsset)
        observe(incoming)
        let incomingPlayer = AVQueuePlayer(items: [incoming.item])
        incomingPlayer.volume = 0
        incomingPlayer.actionAtItemEnd = .advance
        player = incomingPlayer
        overlap = CrossfadeTail(player: outgoingPlayer, context: current, duration: remaining)
        current.lastSampledPosition = position
        promotePreloaded(outgoing: current, incoming: incoming, retainingTail: true)
        observePlayer()
        incomingPlayer.playImmediately(atRate: desiredRate)
        applyFadeVolumes()
    }

    /// AVPlayer can drop a volume write: under audio-device load a newer value has been seen
    /// overtaken by an older one, and the player went on reporting the older gain for over a
    /// second (a 0.4 read back as 0.02, a 0 as the faded 0.38). Each write is therefore checked
    /// on the engine queue and written again until both players report the intended gain; a
    /// newer write supersedes the check, which gives up after `gainVerificationAttempts`.
    private func applyFadeVolumes() {
        writeFadeVolumes()
        gainVerificationGeneration &+= 1
        scheduleGainVerification(generation: gainVerificationGeneration, attempt: 1)
    }

    private func writeFadeVolumes() {
        let gains = intendedGains
        player.volume = gains.current
        if let tail = overlap, let gain = gains.tail { tail.player.volume = gain }
    }

    private var intendedGains: (current: Float, tail: Float?) {
        let fraction = overlap?.fraction ?? 1
        return (masterVolume * fraction, overlap.map { _ in masterVolume * (1 - fraction) })
    }

    private var fadeVolumesApplied: Bool {
        let gains = intendedGains
        return player.volume == gains.current && overlap?.player.volume == gains.tail
    }

    private func scheduleGainVerification(generation: UInt64, attempt: Int) {
        queue.asyncAfter(deadline: .now() + gainVerificationInterval) { [weak self] in
            guard let self, !self.released, self.gainVerificationGeneration == generation,
                  !self.fadeVolumesApplied else { return }
            self.writeFadeVolumes()
            if attempt < Self.gainVerificationAttempts {
                self.scheduleGainVerification(generation: generation, attempt: attempt + 1)
            }
        }
    }

    private func endOverlap() {
        if let tail = overlap {
            tail.player.pause()
            tail.player.removeAllItems()
            tail.context.invalidate()
            overlap = nil
        }
        applyFadeVolumes()
        if crossfadeDuration == 0 { removeCrossfadeTimeObserver() }
    }

    private func installCrossfadeTimeObserver() {
        guard !released, crossfadeTimeObserver == nil, current?.isSeeking != true,
              crossfadeDuration > 0 || overlap != nil else { return }
        let observedPlayer = player
        let generation = crossfadeObservationGeneration
        crossfadeTimeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.05, preferredTimescale: 1_000), queue: queue
        ) { [weak self, weak observedPlayer] time in
            guard let self, self.player === observedPlayer,
                  self.crossfadeObservationGeneration == generation else { return }
            self.updateCrossfade(at: CMTimeGetSeconds(time))
        }
    }

    private func removeCrossfadeTimeObserver() {
        // AVFoundation seek completion and periodic callbacks have separate producers. Removing
        // and regenerating the observer also rejects an old timestamp delivered after the seek.
        crossfadeObservationGeneration &+= 1
        if let observer = crossfadeTimeObserver {
            player.removeTimeObserver(observer)
            crossfadeTimeObserver = nil
        }
    }

    private func timeControlStatusChanged() {
        // A failed item's player goes on changing status; none of it is news about the attempt.
        guard let current, !current.failureEmitted else { return }
        switch player.timeControlStatus {
        case .waitingToPlayAtSpecifiedRate:
            beginBuffering(current)
        case .playing:
            // Claim the entry again when the player actually starts, after any activation /
            // readiness timing window, rather than relying on its first metadata write.
            publishNowPlaying(current)
            if current.buffering {
                current.buffering = false
                emit(
                    .bufferingEnded(
                        attemptID: current.plan.attemptID,
                        position: currentPosition(for: current)
                    )
                )
                updateSystemTransport(for: current, isPlaying: current.playRequested)
            }
            if current.pausedAfterProgress {
                current.pausedAfterProgress = false
                emit(
                    .resumed(
                        attemptID: current.plan.attemptID,
                        position: currentPosition(for: current)
                    )
                )
            }
        case .paused:
            if current.progressBegan && !current.pausedAfterProgress && !current.buffering {
                current.pausedAfterProgress = true
                emit(
                    .paused(
                        attemptID: current.plan.attemptID,
                        position: currentPosition(for: current)
                    )
                )
            }
        @unknown default:
            break
        }
        updateStartupRecovery(current)
    }

    private func requestStartupPlayback(_ context: PlayerItemContext) {
        if !context.playRequested || context.startupPlayRequestedAt == nil {
            context.startupRecoveryAttempted = false
            context.startupPlayRequestedAt = clock.monotonicNow
        }
        context.playRequested = true
    }

    /// OBSERVED: requested playback can remain ready and paused at zero with no failure verdict.
    /// ASSUMED: a dropped/ignored Play caused run 38535897557; the controls inject that loss.
    /// Reassert transport once when ready/paused with no outstanding reads. If that does not
    /// help, fail as .engine by 120 s from Play (the former bound was approximately 122 s).
    /// Reads and transport flaps cannot reset the monotonic budget or invent a decoder error.
    private func updateStartupRecovery(_ context: PlayerItemContext) {
        guard needsStartupRecovery(context) else {
            context.cancelStartupRecovery()
            return
        }
        guard context.cancelStartupRecoveryTimer == nil else { return }
        let token = UUID()
        context.startupRecoveryToken = token
        let remaining = max(0, Self.startupRecoveryVerdictBudget -
            (clock.monotonicNow - (context.startupPlayRequestedAt ?? clock.monotonicNow)))
        let delay = context.startupRecoveryAttempted
            ? remaining : min(Self.startupRecoveryDelay, remaining)
        context.cancelStartupRecoveryTimer = clock.scheduleOnce(on: queue, after: delay) {
            [weak self, weak context] in
            guard let self, let context, context.startupRecoveryToken == token else { return }
            context.cancelStartupRecoveryTimer = nil
            context.startupRecoveryToken = nil
            guard self.needsStartupRecovery(context) else { return }
            let elapsed = self.clock.monotonicNow -
                (context.startupPlayRequestedAt ?? self.clock.monotonicNow)
            if elapsed >= Self.startupRecoveryVerdictBudget {
                // No progress within the Play budget is an engine failure, including when a
                // resource request never completes/cancels. It must stop, not skip, the queue.
                self.itemFailed(context, reported: .engine)
            } else {
                if !context.startupRecoveryAttempted && context.readyEmitted &&
                    self.player.timeControlStatus == .paused && !context.loader.hasPendingRequests &&
                    (!self.usesAVFoundationMediaStack || self.player.currentItem === context.item) {
                    context.startupRecoveryAttempted = true
                    self.player.playImmediately(atRate: self.desiredRate)
                }
                self.updateStartupRecovery(context)
            }
        }
    }

    private func needsStartupRecovery(_ context: PlayerItemContext) -> Bool {
        !released && current === context && context.playRequested &&
            !context.progressBegan && !context.endEmitted && !context.failureEmitted &&
            !context.waitingForRefresh && context.startupPlayRequestedAt != nil
    }

    private func beginBuffering(_ context: PlayerItemContext) {
        guard current === context, !context.buffering else { return }
        context.buffering = true
        emit(
            .buffering(
                attemptID: context.plan.attemptID,
                position: currentPosition(for: context)
            )
        )
        // Still "playing" to the listener (the pause button stays), but the system must stop
        // extrapolating elapsed time, or the lock-screen scrubber runs ahead of the audio.
        updateSystemTransport(for: context, isPlaying: context.playRequested, rateOverride: 0)
    }

    private func pause(context: PlayerItemContext) {
        interruptionWasPlaying = false
        endOverlap()
        context.playRequested = false
        context.cancelStartupRecovery()
        player.pause()
        updateSystemTransport(for: context, isPlaying: false)
        if context.progressBegan && !context.pausedAfterProgress {
            context.pausedAfterProgress = true
            emit(
                .paused(
                    attemptID: context.plan.attemptID,
                    position: currentPosition(for: context)
                )
            )
        }
    }

    private func startSampler() {
        cancelSampler = clock.startSampler(
            on: mediaSamplingQueue,
            interval: Self.sampleInterval
        ) { [weak self] in
            guard let self else { return }
            guard let (context, sampledPlayer, seekGeneration) = contextForPositionSampling() else { return }
            let sample = clock.sample(player: sampledPlayer)
            performOnQueueSynchronously { [self] in
                guard context.seekGeneration == seekGeneration else { return }
                applyPositionSample(sample, for: context)
            }
        }
    }

    /// AVFoundation media-time reads can block while the host is overloaded. Keep those reads off
    /// the serialized state queue, and pin each result to the exact attempt that requested it so a
    /// late sample can never mutate a replacement attempt or a new playback session.
    private func contextForPositionSampling() -> (PlayerItemContext, AVQueuePlayer, UInt64)? {
        var context: (PlayerItemContext, AVQueuePlayer, UInt64)?
        performOnQueueSynchronously { [self] in
            guard !released, let current, current.playRequested, !current.isSeeking else { return }
            context = (current, player, current.seekGeneration)
        }
        return context
    }

    private func activateAudioSessionIfNeeded(
        for plan: DulcetPlaybackPlan,
        commandID: DulcetPlaybackCommandID,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) -> Bool {
        cancelAudioSessionGrace?()
        cancelAudioSessionGrace = nil
        guard activeAudioSessionID != plan.playbackSessionID else { return true }
        do {
            try audioSession.activate()
            activeAudioSessionID = plan.playbackSessionID
            return true
        } catch {
            emit(.failedBeforeStart(attemptID: plan.attemptID, error: .engine))
            completion(.rejected(commandID: commandID, reason: .failed(.engine)))
            return false
        }
    }

    private func scheduleAudioSessionDeactivationAfterGrace() {
        cancelAudioSessionGrace?()
        cancelAudioSessionGrace = clock.scheduleOnce(
            on: queue,
            after: audioSessionGracePeriod
        ) { [weak self] in
            guard let self, self.preloaded == nil, self.player.items().isEmpty else { return }
            self.audioSession.deactivate()
            self.activeAudioSessionID = nil
            self.systemMediaControls.clear()
            self.cancelAudioSessionGrace?()
            self.cancelAudioSessionGrace = nil
        }
    }

    private func deactivateAudioSessionImmediately() {
        cancelAudioSessionGrace?()
        cancelAudioSessionGrace = nil
        guard activeAudioSessionID != nil else { return }
        audioSession.deactivate()
        activeAudioSessionID = nil
    }

    private func handleAudioSessionEvent(_ event: DulcetAudioSessionEvent) {
        guard let current else { return }
        switch event {
        case .interruptionBegan:
            endOverlap()
            // Native transport can lag a newer Pause. Only accepted Play intent may
            // grant automatic resumption; an old playing report cannot recreate it.
            interruptionWasPlaying = current.playRequested
            current.playRequested = false
            current.cancelStartupRecovery()
            current.pausedAfterProgress = current.progressBegan
            player.pause()
            updateSystemTransport(for: current, isPlaying: false)
            emit(.interruptionBegan(attemptID: current.plan.attemptID, shouldResume: false))
        case let .interruptionEnded(systemAllowsResume):
            let shouldResume = systemAllowsResume && interruptionWasPlaying
            interruptionWasPlaying = false
            emit(.interruptionEnded(attemptID: current.plan.attemptID, shouldResume: shouldResume))
            if shouldResume {
                requestStartupPlayback(current)
                if current.progressBegan {
                    emit(.resumed(
                        attemptID: current.plan.attemptID,
                        position: currentPosition(for: current)
                    ))
                }
                current.pausedAfterProgress = false
                player.playImmediately(atRate: desiredRate)
                updateStartupRecovery(current)
                updateSystemTransport(for: current, isPlaying: true)
            }
        case let .routeChanged(old, new, becomingNoisy):
            let didPause = becomingNoisy &&
                (current.playRequested || player.timeControlStatus == .playing)
            if becomingNoisy {
                interruptionWasPlaying = false
                current.playRequested = false
                current.cancelStartupRecovery()
                current.pausedAfterProgress = current.progressBegan
                endOverlap()
                player.pause()
                updateSystemTransport(for: current, isPlaying: false)
            }
            emit(.routeChanged(
                attemptID: current.plan.attemptID,
                old: old,
                new: new,
                didPause: didPause
            ))
        case .externalPlaybackBegan:
            interruptionWasPlaying = false
            endOverlap()
            current.playRequested = false
            player.pause()
            emit(.interruptionBegan(attemptID: current.plan.attemptID, shouldResume: false))
            emit(.engineTornDown(attemptID: current.plan.attemptID, reason: .systemReclaimed))
            current.invalidate()
            preloaded?.invalidate()
            self.current = nil
            preloaded = nil
            artworkBySession = [:]
            player.removeAllItems()
            deactivateAudioSessionImmediately()
            systemMediaControls.clear()
        }
    }

    private func publishNowPlaying(_ context: PlayerItemContext) {
        publishNowPlaying(
            plan: context.plan,
            duration: context.duration,
            position: currentPosition(for: context),
            seekability: context.seekability,
            isPlaying: context.playRequested
        )
    }

    private func publishNowPlaying(
        plan: DulcetPlaybackPlan,
        duration: TimeInterval?,
        position: TimeInterval,
        seekability: DulcetPlaybackSeekability,
        isPlaying: Bool
    ) {
        systemMediaControls.publish(DulcetSystemNowPlayingState(
            sessionID: plan.playbackSessionID,
            metadata: plan.metadata,
            duration: duration,
            position: position,
            rate: current?.plan.playbackSessionID == plan.playbackSessionID && current?.buffering == true
                ? 0 : Double(desiredRate),
            isPlaying: isPlaying,
            seekability: seekability,
            remoteCapabilities: remoteCommandCapabilities,
            artworkImageData: artworkBySession[plan.playbackSessionID]
        ))
    }

    private func updateSystemTransport(
        for context: PlayerItemContext? = nil,
        isPlaying: Bool,
        rateOverride: Double? = nil
    ) {
        guard let context = context ?? current, context.readyEmitted else { return }
        let rate = rateOverride ?? (context.buffering ? 0 : Double(desiredRate))
        systemMediaControls.updateTransport(
            sessionID: context.plan.playbackSessionID,
            position: currentPosition(for: context),
            rate: rate,
            isPlaying: isPlaying
        )
    }

    private func handleRemoteCommandSynchronously(_ command: DulcetRemotePlaybackCommand) -> Bool {
        var handled = false
        var router: (any DulcetRemotePlaybackCommandRouting)?
        performOnQueueSynchronously { [self] in
            guard !released,
                  let current,
                  current.plan.playbackSessionID == command.sessionID else { return }
            if let remoteCommandRouter {
                router = remoteCommandRouter
                return
            }
            switch command {
            case .play:
                requestStartupPlayback(current)
                player.playImmediately(atRate: desiredRate)
                updateStartupRecovery(current)
                updateSystemTransport(for: current, isPlaying: true)
                handled = true
            case .pause:
                pause(context: current)
                handled = true
            case .toggle:
                if current.playRequested {
                    pause(context: current)
                } else {
                    requestStartupPlayback(current)
                    player.playImmediately(atRate: desiredRate)
                    updateStartupRecovery(current)
                    updateSystemTransport(for: current, isPlaying: true)
                }
                handled = true
            case let .seek(_, position):
                guard current.seekability == .seekable, position.isFinite, position >= 0 else {
                    return
                }
                seek(
                    commandID: DulcetPlaybackCommandID("system-media-seek-\(UUID().uuidString)"),
                    position: position
                ) { _ in }
                handled = true
            case .next, .previous, .rating, .favourite:
                handled = false
            }
        }
        // The core router enters MainActor. Holding the engine queue while it waits on main
        // can deadlock a main-thread observation that synchronously updates this engine.
        // The core also checks the session identity, covering a transition after this snapshot.
        return router?.handleRemotePlaybackCommand(command) ?? handled
    }

    private func applyPositionSample(
        _ sample: DulcetAVPlayerEngineClockSample,
        for context: PlayerItemContext
    ) {
        guard current === context, context.playRequested, !context.isSeeking else { return }
        guard sample.isPlaying else { return }
        let position = sample.mediaPosition
        guard position > context.lastSampledPosition + 0.000_001 else { return }
        if !context.progressBegan {
            context.progressBegan = true
            context.cancelStartupRecovery()
            context.progressStartWallClock = clock.wallClockNow
            emit(
                .playbackProgressBegan(
                    attemptID: context.plan.attemptID,
                    wallClock: context.progressStartWallClock!,
                    mediaPosition: position
                )
            )
        }
        context.lastSampledPosition = position
        context.lastSampleMonotonic = sample.monotonicTime
        emit(
            .positionChanged(
                attemptID: context.plan.attemptID,
                mediaPosition: position,
                monotonicTime: sample.monotonicTime
            )
        )
        updateSystemTransport(for: context, isPlaying: true)
    }

    private func seekability(_ context: PlayerItemContext) -> DulcetPlaybackSeekability {
        guard finiteSeconds(context.item.duration) != nil else { return .unknown }
        if context.loader.latestContentInformation?.supportsByteRanges == true ||
            !context.item.seekableTimeRanges.isEmpty {
            return .seekable
        }
        return .notSeekable
    }

    private func currentPosition(for context: PlayerItemContext) -> TimeInterval {
        context.lastSampledPosition
    }

    private func finiteSeconds(_ time: CMTime) -> TimeInterval? {
        let seconds = CMTimeGetSeconds(time)
        return seconds.isFinite && seconds >= 0 ? seconds : nil
    }

    private func closedFailure(for error: Error?) -> DulcetPlaybackFailure {
        DulcetApplePlaybackErrorSanitizer.avFoundationFailure(error)
    }

    private func context(for attemptID: DulcetPlaybackAttemptID) -> PlayerItemContext? {
        if current?.plan.attemptID == attemptID { return current }
        if preloaded?.plan.attemptID == attemptID { return preloaded }
        return nil
    }

    private func isActive(_ context: PlayerItemContext) -> Bool {
        current === context || preloaded === context
    }

    private func emit(_ event: DulcetPlaybackEvent) {
        dispatchPrecondition(condition: .onQueue(queue))
        if Self.describesPlayback(event), context(for: event.attemptID)?.failureEmitted == true {
            // A failed attempt is over. The player's status goes on changing after the item
            // fails -- it pauses, its time-control observer fires later -- and a Paused, Resumed
            // or position report for that attempt would read to the core as the attempt still
            // being played, taking it out of Failed so Try Again had nothing to retry.
            return
        }
        listener?(event)
    }

    /// Events that describe an attempt being played, as opposed to ending, failing or being
    /// replaced. None of these is true of an attempt after its failure.
    private static func describesPlayback(_ event: DulcetPlaybackEvent) -> Bool {
        switch event {
        case .ready, .playbackProgressBegan, .buffering, .bufferingEnded, .paused, .resumed,
             .positionChanged, .durationChanged, .seekCompleted, .seekFailed, .endedNaturally,
             .routeChanged, .interruptionBegan, .interruptionEnded, .rateChanged,
             .sourceRefreshRequired:
            true
        case .preparing, .skipped, .failedBeforeStart, .failedAfterPartial, .attemptReplaced,
             .advancedToPreloaded, .engineTornDown, .observationResynced:
            false
        }
    }

    private func enqueue(_ operation: @escaping @Sendable (DulcetAVPlayerEngine) -> Void) {
        queue.async { [weak self] in
            guard let self else { return }
            operation(self)
        }
    }

    private func performOnQueueSynchronously(_ work: () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            work()
        } else {
            queue.sync(execute: work)
        }
    }
}

struct DulcetAVPlayerEngineClockSample: Sendable {
    let isPlaying: Bool
    let mediaPosition: TimeInterval
    let monotonicTime: DulcetMonotonicInstant
}

protocol DulcetAVPlayerEngineClock: Sendable {
    var wallClockNow: Date { get }
    var monotonicNow: TimeInterval { get }

    func sample(player: AVQueuePlayer) -> DulcetAVPlayerEngineClockSample

    func startSampler(
        on queue: DispatchQueue,
        interval: TimeInterval,
        handler: @escaping @Sendable () -> Void
    ) -> @Sendable () -> Void

    /// Schedule one deferred callback and return its cancel closure.
    ///
    /// The audio-session grace period used a `DispatchSourceTimer` built inline, which made
    /// `anEmptyQueueDeactivatesAfterTheConfiguredGracePeriod` depend on real elapsed time on a
    /// simulator whose media stack is the unreliable part. Routing it through the clock lets a test
    /// fire the deadline instead of waiting for one.
    func scheduleOnce(
        on queue: DispatchQueue,
        after interval: TimeInterval,
        handler: @escaping @Sendable () -> Void
    ) -> @Sendable () -> Void
}

extension DulcetAVPlayerEngineClock {
    var monotonicNow: TimeInterval {
        Double(DispatchTime.now().uptimeNanoseconds) / 1_000_000_000
    }
}

private struct DulcetAVPlayerEngineSystemClock: DulcetAVPlayerEngineClock {
    func scheduleOnce(
        on queue: DispatchQueue,
        after interval: TimeInterval,
        handler: @escaping @Sendable () -> Void
    ) -> @Sendable () -> Void {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + interval)
        // The handler captures the timer so the timer stays alive until it fires, and releases it
        // by cancelling afterwards. Without that, the returned cancel closure would be the only
        // strong reference and a caller who discarded it would get a timer that is deallocated
        // before its deadline and silently never fires. OBSERVED by mutation while adding this.
        timer.setEventHandler {
            handler()
            timer.cancel()
        }
        timer.resume()
        return { timer.cancel() }
    }

    var wallClockNow: Date { Date() }

    func sample(player: AVQueuePlayer) -> DulcetAVPlayerEngineClockSample {
        let seconds = CMTimeGetSeconds(player.currentTime())
        return DulcetAVPlayerEngineClockSample(
            isPlaying: player.timeControlStatus == .playing,
            mediaPosition: seconds.isFinite && seconds >= 0 ? seconds : 0,
            monotonicTime: DulcetMonotonicInstant(
                uptimeNanoseconds: DispatchTime.now().uptimeNanoseconds
            )
        )
    }

    func startSampler(
        on queue: DispatchQueue,
        interval: TimeInterval,
        handler: @escaping @Sendable () -> Void
    ) -> @Sendable () -> Void {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(
            deadline: .now() + interval,
            repeating: interval,
            leeway: .milliseconds(50)
        )
        timer.setEventHandler(handler: handler)
        timer.resume()
        return { timer.cancel() }
    }
}

private final class QueueLivenessProbeResult: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<DulcetAVPlayerEngineQueueLiveness, Never>?

    init(continuation: CheckedContinuation<DulcetAVPlayerEngineQueueLiveness, Never>) {
        self.continuation = continuation
    }

    func resolve(_ result: DulcetAVPlayerEngineQueueLiveness) {
        lock.lock()
        let continuation = continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(returning: result)
    }
}

private final class PlayerItemContext: @unchecked Sendable {
    let plan: DulcetPlaybackPlan
    var item: AVPlayerItem
    let loader: DulcetAVAssetResourceLoaderDelegate
    var isPreloaded: Bool
    var observers: [NSKeyValueObservation] = []
    var readyEmitted = false
    var playRequested = false
    var progressBegan = false
    var progressStartWallClock: Date?
    var buffering = false
    var pausedAfterProgress = false
    var endEmitted = false
    var failureEmitted = false
    var waitingForRefresh = false
    var startupRecoveryAttempted = false
    var startupPlayRequestedAt: TimeInterval?
    var startupRecoveryToken: UUID?
    var cancelStartupRecoveryTimer: (@Sendable () -> Void)?
    var isSeeking = false
    var seekGeneration: UInt64 = 0
    var lastSampledPosition: TimeInterval = 0
    var lastSampleMonotonic: DulcetMonotonicInstant?
    var duration: TimeInterval?
    var seekability: DulcetPlaybackSeekability = .unknown
    var rate: Double

    init(
        plan: DulcetPlaybackPlan,
        item: AVPlayerItem,
        loader: DulcetAVAssetResourceLoaderDelegate,
        isPreloaded: Bool,
        rate: Double
    ) {
        self.plan = plan
        self.item = item
        self.loader = loader
        self.isPreloaded = isPreloaded
        self.rate = rate
    }

    func invalidate() {
        cancelStartupRecovery()
        observers.forEach { $0.invalidate() }
        observers.removeAll()
    }

    func cancelStartupRecovery() {
        startupPlayRequestedAt = nil
        startupRecoveryToken = nil
        cancelStartupRecoveryTimer?()
        cancelStartupRecoveryTimer = nil
    }

    func snapshot() -> DulcetPlaybackObservationSnapshot {
        let status: DulcetPlaybackObservationStatus = if failureEmitted {
            .failed
        } else if buffering {
            .buffering
        } else if pausedAfterProgress {
            .paused
        } else if progressBegan {
            .progressing
        } else if readyEmitted {
            .ready
        } else {
            .preparing
        }
        return DulcetPlaybackObservationSnapshot(
            status: status,
            mediaPosition: lastSampledPosition,
            duration: duration,
            seekability: seekability,
            rate: rate,
            progressStartWallClock: progressStartWallClock
        )
    }
}
