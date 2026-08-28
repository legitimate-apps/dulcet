import AVFoundation
import Foundation

public final class DulcetAVPlayerEngine: DulcetApplePlaybackEngine, @unchecked Sendable {
    public static let sampleInterval: TimeInterval = 0.5
    public static let cadenceMaximum: TimeInterval = 2
    public static let emptyQueueAudioSessionGracePeriod: TimeInterval = 10

    private let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<UInt8>()
    private let player: AVQueuePlayer
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
    private var playerObservers: [NSKeyValueObservation] = []
    private var notificationObservers: [NSObjectProtocol] = []
    private var sampler: DispatchSourceTimer?
    private var audioSessionGraceTimer: DispatchSourceTimer?
    private var activeAudioSessionID: DulcetPlaybackSessionID?
    private var interruptionWasPlaying = false

    public init(
        player: AVQueuePlayer = AVQueuePlayer(),
        audioSession: any DulcetAudioSessionManaging = DulcetPlatformAudioSession(),
        audioSessionGracePeriod: TimeInterval = DulcetAVPlayerEngine.emptyQueueAudioSessionGracePeriod,
        systemMediaControls: any DulcetSystemMediaControlling = DulcetPlatformSystemMediaControls(),
        remoteCommandRouter: (any DulcetRemotePlaybackCommandRouting)? = nil,
        remoteCommandCapabilities: DulcetRemoteCommandCapabilities = .init()
    ) {
        self.player = player
        self.audioSession = audioSession
        self.audioSessionGracePeriod = max(0, audioSessionGracePeriod)
        self.systemMediaControls = systemMediaControls
        self.remoteCommandRouter = remoteCommandRouter
        self.remoteCommandCapabilities = remoteCommandCapabilities
        self.queue = DispatchQueue(label: "com.legitimateapps.dulcet.playback-engine")
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
                    snapshot: current.snapshot(player: player)
                )
            )
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
            remoteCommandCapabilities = capabilities
            if current.readyEmitted { publishNowPlaying(current) }
            applied = true
        }
        return applied
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
        observePlayer()
        startSampler()
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
            current.playRequested = true
            player.playImmediately(atRate: desiredRate)
            updateSystemTransport(for: current, isPlaying: true)
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
            player.volume = Float(volume)
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
                position: currentPosition(),
                seekability: current.seekability,
                isPlaying: current.playRequested
            )
            prepare(plan, replacing: true, commandID: commandID, completion: completion)
        case let .preloadNext(commandID, plan):
            preload(plan, commandID: commandID, completion: completion)
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
        let wasPlaying = player.timeControlStatus == .playing
        let context = makeContext(plan: plan, resource: resource, isPreloaded: false)
        current?.invalidate()
        current = context
        player.removeAllItems()
        player.insert(context.item, after: nil)
        if let preloaded {
            player.insert(preloaded.item, after: context.item)
        }
        emit(.preparing(attemptID: plan.attemptID))
        if wasPlaying {
            context.playRequested = true
            player.playImmediately(atRate: desiredRate)
        }
        completion(.accepted(commandID: commandID))
    }

    private func preload(
        _ plan: DulcetPlaybackPlan,
        commandID: DulcetPlaybackCommandID,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) {
        guard let current, plan.playbackSessionID != current.plan.playbackSessionID else {
            completion(.rejected(commandID: commandID, reason: .invalidState))
            return
        }
        guard plan.deliveryProtocol == .httpProgressive,
              let resource = plan.resource as? any DulcetPlaybackResourceLoading else {
            emit(.failedBeforeStart(attemptID: plan.attemptID, error: .unsupportedPlan))
            completion(.rejected(commandID: commandID, reason: .unsupported))
            return
        }
        preloaded?.invalidate()
        if let existing = preloaded?.item {
            player.remove(existing)
        }
        let context = makeContext(plan: plan, resource: resource, isPreloaded: true)
        preloaded = context
        player.insert(context.item, after: current.item)
        emit(.preparing(attemptID: plan.attemptID))
        completion(.accepted(commandID: commandID))
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
        let from = currentPosition()
        let requested = CMTime(seconds: position, preferredTimescale: 1_000)
        player.seek(to: requested, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] success in
            guard let self else {
                completion(.rejected(commandID: commandID, reason: .engineReleased))
                return
            }
            self.queue.async {
                guard self.current === context else {
                    completion(.rejected(commandID: commandID, reason: .invalidState))
                    return
                }
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
        guard let current else {
            player.pause()
            player.removeAllItems()
            return
        }
        let position = currentPosition()
        player.pause()
        emit(.skipped(attemptID: current.plan.attemptID, position: position, reason: reason))
        current.invalidate()
        preloaded?.invalidate()
        self.current = nil
        preloaded = nil
        player.removeAllItems()
        deactivateAudioSessionImmediately()
        systemMediaControls.clear()
    }

    private func releaseEngine() {
        guard !released else { return }
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
        player.removeAllItems()
        playerObservers.forEach { $0.invalidate() }
        playerObservers.removeAll()
        notificationObservers.forEach(NotificationCenter.default.removeObserver)
        notificationObservers.removeAll()
        sampler?.cancel()
        sampler = nil
        audioSessionGraceTimer?.cancel()
        audioSessionGraceTimer = nil
        deactivateAudioSessionImmediately()
        audioSession.setEventHandler(nil)
        systemMediaControls.setCommandHandler(nil)
        systemMediaControls.clear()
        released = true
    }

    private func tearDownWithoutEvent() {
        performOnQueueSynchronously { [self] in
            player.pause()
            current?.invalidate()
            preloaded?.invalidate()
            playerObservers.forEach { $0.invalidate() }
            notificationObservers.forEach(NotificationCenter.default.removeObserver)
            sampler?.cancel()
            audioSessionGraceTimer?.cancel()
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
            expectedContainer: plan.expectedContainer
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
        observe(context)
        return context
    }

    private func observePlayer() {
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
        context.observers.append(
            context.item.observe(\.status, options: [.initial, .new]) { [weak self, weak context] _, _ in
                guard let context else { return }
                self?.enqueue { $0.itemStatusChanged(context) }
            }
        )
        context.observers.append(
            context.item.observe(\.duration, options: [.new]) { [weak self, weak context] _, _ in
                guard let context else { return }
                self?.enqueue { $0.durationChanged(context) }
            }
        )
        let center = NotificationCenter.default
        notificationObservers.append(
            center.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: context.item,
                queue: nil
            ) { [weak self, weak context] _ in
                guard let context else { return }
                self?.enqueue { $0.itemEnded(context) }
            }
        )
        notificationObservers.append(
            center.addObserver(
                forName: .AVPlayerItemFailedToPlayToEndTime,
                object: context.item,
                queue: nil
            ) { [weak self, weak context] _ in
                guard let context else { return }
                self?.enqueue { $0.itemFailed(context) }
            }
        )
        notificationObservers.append(
            center.addObserver(
                forName: .AVPlayerItemPlaybackStalled,
                object: context.item,
                queue: nil
            ) { [weak self, weak context] _ in
                guard let context else { return }
                self?.enqueue { $0.beginBuffering(context) }
            }
        )
    }

    private func itemStatusChanged(_ context: PlayerItemContext) {
        guard isActive(context) else { return }
        switch context.item.status {
        case .readyToPlay:
            guard !context.readyEmitted else { return }
            context.readyEmitted = true
            let duration = finiteSeconds(context.item.duration)
            context.duration = duration
            context.seekability = seekability(context)
            emit(
                .ready(
                    attemptID: context.plan.attemptID,
                    duration: duration,
                    seekability: context.seekability
                )
            )
            if current === context {
                publishNowPlaying(context)
            }
        case .failed:
            itemFailed(context)
        case .unknown:
            break
        @unknown default:
            itemFailed(context)
        }
    }

    private func durationChanged(_ context: PlayerItemContext) {
        guard isActive(context), let duration = finiteSeconds(context.item.duration) else { return }
        guard context.duration == nil || abs((context.duration ?? 0) - duration) > 0.001 else { return }
        context.duration = duration
        emit(.durationChanged(attemptID: context.plan.attemptID, duration: duration))
    }

    private func itemEnded(_ context: PlayerItemContext) {
        guard isActive(context), !context.endEmitted else { return }
        context.endEmitted = true
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

    private func itemFailed(_ context: PlayerItemContext) {
        guard isActive(context), !context.failureEmitted, !context.waitingForRefresh else { return }
        context.failureEmitted = true
        let failure = closedFailure(for: context.item.error)
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
            context.playRequested = false
            player.pause()
            updateSystemTransport(for: context, isPlaying: false)
            emit(
                .sourceRefreshRequired(
                    attemptID: attemptID,
                    reason: refreshReason!
                )
            )
            return
        }
        context.failureEmitted = true
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

    private func currentItemChanged() {
        guard let outgoing = current, let incoming = preloaded else { return }
        guard player.currentItem === incoming.item else { return }
        if !outgoing.endEmitted {
            outgoing.endEmitted = true
            emit(
                .endedNaturally(
                    attemptID: outgoing.plan.attemptID,
                    finalPosition: finiteSeconds(outgoing.item.duration) ?? outgoing.lastSampledPosition
                )
            )
        }
        emit(
            .advancedToPreloaded(
                oldAttemptID: outgoing.plan.attemptID,
                newAttemptID: incoming.plan.attemptID
            )
        )
        outgoing.invalidate()
        incoming.isPreloaded = false
        current = incoming
        preloaded = nil
        activeAudioSessionID = incoming.plan.playbackSessionID
        if incoming.readyEmitted {
            publishNowPlaying(incoming)
        }
    }

    private func timeControlStatusChanged() {
        guard let current else { return }
        switch player.timeControlStatus {
        case .waitingToPlayAtSpecifiedRate:
            beginBuffering(current)
        case .playing:
            if current.buffering {
                current.buffering = false
                emit(
                    .bufferingEnded(
                        attemptID: current.plan.attemptID,
                        position: currentPosition()
                    )
                )
            }
            if current.pausedAfterProgress {
                current.pausedAfterProgress = false
                emit(
                    .resumed(
                        attemptID: current.plan.attemptID,
                        position: currentPosition()
                    )
                )
            }
        case .paused:
            if current.progressBegan && !current.pausedAfterProgress && !current.buffering {
                current.pausedAfterProgress = true
                emit(
                    .paused(
                        attemptID: current.plan.attemptID,
                        position: currentPosition()
                    )
                )
            }
        @unknown default:
            break
        }
    }

    private func beginBuffering(_ context: PlayerItemContext) {
        guard current === context, !context.buffering else { return }
        context.buffering = true
        emit(
            .buffering(
                attemptID: context.plan.attemptID,
                position: currentPosition()
            )
        )
    }

    private func pause(context: PlayerItemContext) {
        context.playRequested = false
        player.pause()
        updateSystemTransport(for: context, isPlaying: false)
        if context.progressBegan && !context.pausedAfterProgress {
            context.pausedAfterProgress = true
            emit(
                .paused(
                    attemptID: context.plan.attemptID,
                    position: currentPosition()
                )
            )
        }
    }

    private func startSampler() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(
            deadline: .now() + Self.sampleInterval,
            repeating: Self.sampleInterval,
            leeway: .milliseconds(50)
        )
        timer.setEventHandler { [weak self] in self?.samplePosition() }
        sampler = timer
        timer.resume()
    }

    private func activateAudioSessionIfNeeded(
        for plan: DulcetPlaybackPlan,
        commandID: DulcetPlaybackCommandID,
        completion: @escaping DulcetPlaybackCommandCompletion
    ) -> Bool {
        audioSessionGraceTimer?.cancel()
        audioSessionGraceTimer = nil
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
        audioSessionGraceTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + audioSessionGracePeriod)
        timer.setEventHandler { [weak self, weak timer] in
            guard let self, self.preloaded == nil, self.player.items().isEmpty else { return }
            self.audioSession.deactivate()
            self.activeAudioSessionID = nil
            self.systemMediaControls.clear()
            timer?.cancel()
            self.audioSessionGraceTimer = nil
        }
        audioSessionGraceTimer = timer
        timer.resume()
    }

    private func deactivateAudioSessionImmediately() {
        audioSessionGraceTimer?.cancel()
        audioSessionGraceTimer = nil
        guard activeAudioSessionID != nil else { return }
        audioSession.deactivate()
        activeAudioSessionID = nil
    }

    private func handleAudioSessionEvent(_ event: DulcetAudioSessionEvent) {
        guard let current else { return }
        switch event {
        case .interruptionBegan:
            interruptionWasPlaying = current.playRequested || player.timeControlStatus == .playing
            current.playRequested = false
            current.pausedAfterProgress = current.progressBegan
            player.pause()
            updateSystemTransport(for: current, isPlaying: false)
            emit(.interruptionBegan(attemptID: current.plan.attemptID, shouldResume: false))
        case let .interruptionEnded(systemAllowsResume):
            let shouldResume = systemAllowsResume && interruptionWasPlaying
            interruptionWasPlaying = false
            emit(.interruptionEnded(attemptID: current.plan.attemptID, shouldResume: shouldResume))
            if shouldResume {
                current.playRequested = true
                if current.progressBegan {
                    emit(.resumed(
                        attemptID: current.plan.attemptID,
                        position: currentPosition()
                    ))
                }
                current.pausedAfterProgress = false
                player.playImmediately(atRate: desiredRate)
                updateSystemTransport(for: current, isPlaying: true)
            }
        case let .routeChanged(old, new, becomingNoisy):
            let didPause = becomingNoisy &&
                (current.playRequested || player.timeControlStatus == .playing)
            if becomingNoisy {
                current.playRequested = false
                current.pausedAfterProgress = current.progressBegan
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
            current.playRequested = false
            player.pause()
            emit(.interruptionBegan(attemptID: current.plan.attemptID, shouldResume: false))
            emit(.engineTornDown(attemptID: current.plan.attemptID, reason: .systemReclaimed))
            current.invalidate()
            preloaded?.invalidate()
            self.current = nil
            preloaded = nil
            player.removeAllItems()
            deactivateAudioSessionImmediately()
            systemMediaControls.clear()
        }
    }

    private func publishNowPlaying(_ context: PlayerItemContext) {
        publishNowPlaying(
            plan: context.plan,
            duration: context.duration,
            position: currentPosition(),
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
            rate: Double(desiredRate),
            isPlaying: isPlaying,
            seekability: seekability,
            remoteCapabilities: remoteCommandCapabilities
        ))
    }

    private func updateSystemTransport(
        for context: PlayerItemContext? = nil,
        isPlaying: Bool
    ) {
        guard let context = context ?? current, context.readyEmitted else { return }
        systemMediaControls.updateTransport(
            sessionID: context.plan.playbackSessionID,
            position: currentPosition(),
            rate: Double(desiredRate),
            isPlaying: isPlaying
        )
    }

    private func handleRemoteCommandSynchronously(_ command: DulcetRemotePlaybackCommand) -> Bool {
        var handled = false
        performOnQueueSynchronously { [self] in
            guard !released,
                  let current,
                  current.plan.playbackSessionID == command.sessionID else { return }
            switch command {
            case .play:
                current.playRequested = true
                player.playImmediately(atRate: desiredRate)
                updateSystemTransport(for: current, isPlaying: true)
                handled = true
            case .pause:
                pause(context: current)
                handled = true
            case .toggle:
                if current.playRequested {
                    pause(context: current)
                } else {
                    current.playRequested = true
                    player.playImmediately(atRate: desiredRate)
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
                handled = remoteCommandRouter?.handleRemotePlaybackCommand(command) == true
            }
        }
        return handled
    }

    private func samplePosition() {
        guard let current, player.timeControlStatus == .playing else { return }
        let position = currentPosition()
        guard position > current.lastSampledPosition + 0.000_001 else { return }
        let monotonic = DulcetMonotonicInstant(
            uptimeNanoseconds: DispatchTime.now().uptimeNanoseconds
        )
        if !current.progressBegan {
            current.progressBegan = true
            current.progressStartWallClock = Date()
            emit(
                .playbackProgressBegan(
                    attemptID: current.plan.attemptID,
                    wallClock: current.progressStartWallClock!,
                    mediaPosition: position
                )
            )
        }
        current.lastSampledPosition = position
        current.lastSampleMonotonic = monotonic
        emit(
            .positionChanged(
                attemptID: current.plan.attemptID,
                mediaPosition: position,
                monotonicTime: monotonic
            )
        )
        updateSystemTransport(for: current, isPlaying: true)
    }

    private func seekability(_ context: PlayerItemContext) -> DulcetPlaybackSeekability {
        guard finiteSeconds(context.item.duration) != nil else { return .unknown }
        if context.loader.latestContentInformation?.supportsByteRanges == true ||
            !context.item.seekableTimeRanges.isEmpty {
            return .seekable
        }
        return .notSeekable
    }

    private func currentPosition() -> TimeInterval {
        finiteSeconds(player.currentTime()) ?? 0
    }

    private func finiteSeconds(_ time: CMTime) -> TimeInterval? {
        let seconds = CMTimeGetSeconds(time)
        return seconds.isFinite && seconds >= 0 ? seconds : nil
    }

    private func closedFailure(for error: Error?) -> DulcetPlaybackFailure {
        guard let nsError = error as NSError? else { return .engine }
        if nsError.domain == NSURLErrorDomain {
            switch URLError.Code(rawValue: nsError.code) {
            case .serverCertificateHasBadDate, .serverCertificateUntrusted,
                 .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
                 .secureConnectionFailed:
                return .tlsUntrusted
            default:
                return .transport
            }
        }
        return .engine
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
        listener?(event)
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

private final class PlayerItemContext: @unchecked Sendable {
    let plan: DulcetPlaybackPlan
    let item: AVPlayerItem
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
        observers.forEach { $0.invalidate() }
        observers.removeAll()
    }

    func snapshot(player: AVQueuePlayer) -> DulcetPlaybackObservationSnapshot {
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
        let seconds = CMTimeGetSeconds(player.currentTime())
        return DulcetPlaybackObservationSnapshot(
            status: status,
            mediaPosition: seconds.isFinite && seconds >= 0 ? seconds : nil,
            duration: duration,
            seekability: seekability,
            rate: rate,
            progressStartWallClock: progressStartWallClock
        )
    }
}
