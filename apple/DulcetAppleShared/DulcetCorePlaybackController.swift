import DulcetCore
import DulcetKit
import Foundation

@MainActor
final class DulcetCorePlaybackController: DulcetPlaybackControlling {
    private let queueClient: ApplePlaybackQueueClient
    private let engine: DulcetAVPlayerEngine
    private var wireClient: ApplePlaybackWireClient?
    private var resolveOperation: (any ApplePlaybackWireOperation)?
    private var account: PlaybackEndpointAccount?
    private var catalog: [DulcetProviderItemID: DulcetTrack] = [:]
    private var progressingSessions: Set<String> = []
    private var presentationHandler:
        (@MainActor (DulcetPlaybackPresentation) -> Void)?
    private var activeDirectiveIdentity: String?
    private lazy var remoteBridge = DulcetCoreRemoteCommandBridge { [weak self] command in
        self?.handleRemoteCommand(command) == true
    }

    private(set) var currentPresentation: DulcetPlaybackPresentation = .unavailable

    init(
        databaseName: String = "dulcet.db",
        engine: DulcetAVPlayerEngine = DulcetAVPlayerEngine()
    ) {
        queueClient = ApplePlaybackQueueClient(databaseName: databaseName)
        self.engine = engine
        engine.setEventListener { [weak self] event in
            Task { @MainActor [weak self] in
                self?.receiveEngineEvent(event)
            }
        }
        engine.setRemoteCommandRouter(remoteBridge)
    }

    func setPresentationHandler(
        _ handler: @escaping @MainActor (DulcetPlaybackPresentation) -> Void
    ) {
        presentationHandler = handler
    }

    func configure(account presentationAccount: DulcetPlaybackAccount) {
        resolveOperation?.cancel()
        resolveOperation = nil
        wireClient?.close()
        let coreAccount = PlaybackEndpointAccount(
            providerInstanceId: presentationAccount.providerInstanceID,
            normalizedBaseUrl: presentationAccount.normalizedServerURL,
            username: presentationAccount.username,
            password: presentationAccount.password,
            allowLocalHttp: presentationAccount.allowLocalHTTP
        )
        account = coreAccount
        wireClient = ApplePlaybackWireClient(account: coreAccount)
        _ = queueClient.configureDelivery(account: coreAccount)
        publish(queueClient.snapshot())
    }

    func replaceQueueAndPlay(_ intent: DulcetPlaybackQueueIntent) {
        guard account != nil, !intent.tracks.isEmpty else {
            publishFailure()
            return
        }
        catalog.merge(
            Dictionary(uniqueKeysWithValues: intent.tracks.map { ($0.id, $0) }),
            uniquingKeysWith: { _, latest in latest }
        )
        let transition = queueClient.replaceAndStart(request: ApplePlaybackQueueRequestDto(
            items: intent.tracks.map { track in
                ApplePlaybackQueueItemDto(
                    providerInstanceId: track.id.providerInstanceID,
                    rawId: track.id.rawID,
                    durationMilliseconds: track.duration.playbackMilliseconds
                )
            },
            sourceKind: intent.sourceKind.rawValue,
            sourceRawId: intent.sourceID?.rawID,
            sourceDisplayName: intent.sourceDisplayName,
            startIndex: Int32(intent.startIndex ?? -1),
            shuffle: intent.shuffle
        ))
        guard transition.errorKind == nil else {
            publishFailure()
            return
        }
        publishPreparing()
        start(transition.startDirective)
    }

    func send(_ intent: DulcetPlaybackControlIntent) {
        guard let snapshot = queueClient.snapshot().snapshot,
              let session = snapshot.currentSession else { return }
        let sessionID = session.playbackSessionId
        switch intent {
        case .play:
            guard queueClient.acceptsCommand(
                playbackSessionId: sessionID,
                requiresSeekable: false
            ) else { return }
            execute(.play(commandID: commandID("play")))
        case .pause:
            guard queueClient.acceptsCommand(
                playbackSessionId: sessionID,
                requiresSeekable: false
            ) else { return }
            execute(.pause(commandID: commandID("pause")))
        case .toggle:
            send(currentPresentation.nowPlaying?.isPlaying == true ? .pause : .play)
        case .next:
            start(queueClient.nextForSession(playbackSessionId: sessionID).startDirective)
        case .previous:
            start(queueClient.previousForSession(playbackSessionId: sessionID).startDirective)
        case let .seek(position):
            guard queueClient.acceptsCommand(
                playbackSessionId: sessionID,
                requiresSeekable: true
            ) else { return }
            execute(.seek(
                commandID: commandID("seek"),
                position: position.playbackTimeInterval
            ))
        case let .setShuffle(enabled):
            publish(queueClient.setShuffle(enabled: enabled))
        case .cycleRepeat:
            publish(queueClient.cycleRepeatMode())
        }
    }

    func disconnect() {
        resolveOperation?.cancel()
        resolveOperation = nil
        wireClient?.close()
        wireClient = nil
        account = nil
        catalog = [:]
        progressingSessions = []
        activeDirectiveIdentity = nil
        execute(.stop(commandID: commandID("disconnect")))
        currentPresentation = .unavailable
        presentationHandler?(currentPresentation)
    }

    private func start(_ directive: ApplePlaybackStartDirectiveDto?) {
        guard let directive else {
            publish(queueClient.snapshot())
            return
        }
        guard let wireClient,
              let track = catalog[DulcetProviderItemID(
                providerInstanceID: directive.providerInstanceId,
                rawID: directive.rawId
              )],
              let sourceContainer = track.sourceContainer?.coreContainer else {
            _ = queueClient.recordFailedBeforeStart(
                attemptId: directive.attemptId,
                errorKind: "sourceUnavailable"
            )
            publishFailure()
            return
        }
        resolveOperation?.cancel()
        activeDirectiveIdentity = directive.attemptId
        publishPreparing()
        let request = PlaybackResolveRequest(
            playbackSessionId: PlaybackSessionId(value: directive.playbackSessionId),
            attemptId: AttemptId(value: directive.attemptId),
            itemId: ProviderItemId(
                providerInstanceId: directive.providerInstanceId,
                rawId: directive.rawId
            ),
            sourceContainer: sourceContainer,
            supportsTranscodingExtension: false,
            deviceProfile: Self.deviceProfile,
            legacyPreference: LegacyPlaybackPreference(format: nil, maxBitRateKbps: nil),
            legacyTimeOffset: nil
        )
        resolveOperation = wireClient.startResolve(request: request) { [weak self] outcome in
            Task { @MainActor [weak self] in
                guard let self,
                      self.activeDirectiveIdentity == directive.attemptId else { return }
                self.resolveOperation = nil
                guard let corePlan = outcome.plan else {
                    _ = self.queueClient.recordFailedBeforeStart(
                        attemptId: directive.attemptId,
                        errorKind: Self.closedFailureKind(outcome.errorKind)
                    )
                    self.publishFailure()
                    return
                }
                let plan = DulcetCorePlaybackPlanFactory.makePlan(
                    client: wireClient,
                    corePlan: corePlan,
                    metadata: DulcetNowPlayingMetadata(
                        title: track.title,
                        artist: track.artistNames.joined(separator: ", "),
                        albumTitle: track.albumTitle
                    )
                )
                self.execute(.stop(commandID: self.commandID("replace"))) { [weak self] _ in
                    self?.prepareAndPlay(plan)
                }
            }
        }
    }

    private func prepareAndPlay(_ plan: DulcetPlaybackPlan) {
        execute(.prepare(commandID: commandID("prepare"), plan: plan)) { [weak self] outcome in
            guard case .accepted = outcome else { return }
            self?.execute(.play(commandID: self?.commandID("autoplay")
                ?? DulcetPlaybackCommandID("autoplay-fallback")))
        }
    }

    private func execute(
        _ command: DulcetPlaybackCommand,
        completion: (@MainActor (DulcetPlaybackCommandOutcome) -> Void)? = nil
    ) {
        engine.execute(command) { outcome in
            guard let completion else { return }
            Task { @MainActor in completion(outcome) }
        }
    }

    private func receiveEngineEvent(_ event: DulcetPlaybackEvent) {
        let transition = record(event)
        if case let .playbackProgressBegan(attemptID, _, _) = event,
           let session = transition.snapshot?.currentSession,
           session.attemptId == attemptID.rawValue {
            progressingSessions.insert(session.playbackSessionId)
        }
        publish(transition)
        if let directive = transition.startDirective {
            start(directive)
        }
    }

    private func record(_ event: DulcetPlaybackEvent) -> ApplePlaybackQueueTransitionDto {
        switch event {
        case let .preparing(attemptID):
            queueClient.recordPreparing(attemptId: attemptID.rawValue)
        case let .ready(attemptID, duration, seekability):
            queueClient.recordReady(
                attemptId: attemptID.rawValue,
                durationMilliseconds: duration?.playbackMilliseconds ?? -1,
                seekability: seekability.coreName
            )
        case let .playbackProgressBegan(attemptID, wallClock, mediaPosition):
            queueClient.recordPlaybackProgressBegan(
                attemptId: attemptID.rawValue,
                wallClockEpochMilliseconds: Int64(wallClock.timeIntervalSince1970 * 1_000),
                mediaPositionMilliseconds: mediaPosition.playbackMilliseconds
            )
        case let .buffering(attemptID, position):
            queueClient.recordBuffering(
                attemptId: attemptID.rawValue,
                positionMilliseconds: position.playbackMilliseconds
            )
        case let .bufferingEnded(attemptID, position):
            queueClient.recordBufferingEnded(
                attemptId: attemptID.rawValue,
                positionMilliseconds: position.playbackMilliseconds
            )
        case let .paused(attemptID, position):
            queueClient.recordPaused(
                attemptId: attemptID.rawValue,
                positionMilliseconds: position.playbackMilliseconds
            )
        case let .resumed(attemptID, position):
            queueClient.recordResumed(
                attemptId: attemptID.rawValue,
                positionMilliseconds: position.playbackMilliseconds
            )
        case let .positionChanged(attemptID, mediaPosition, monotonicTime):
            queueClient.recordPositionChanged(
                attemptId: attemptID.rawValue,
                mediaPositionMilliseconds: mediaPosition.playbackMilliseconds,
                monotonicUptimeNanoseconds: Int64(
                    min(monotonicTime.uptimeNanoseconds, UInt64(Int64.max))
                )
            )
        case let .durationChanged(attemptID, duration):
            queueClient.recordDurationChanged(
                attemptId: attemptID.rawValue,
                durationMilliseconds: duration.playbackMilliseconds
            )
        case let .seekCompleted(attemptID, from, to):
            queueClient.recordSeekCompleted(
                attemptId: attemptID.rawValue,
                fromMilliseconds: from.playbackMilliseconds,
                toMilliseconds: to.playbackMilliseconds
            )
        case let .seekFailed(attemptID, from, to):
            queueClient.recordSeekFailed(
                attemptId: attemptID.rawValue,
                fromMilliseconds: from.playbackMilliseconds,
                toMilliseconds: to.playbackMilliseconds
            )
        case let .endedNaturally(attemptID, finalPosition):
            queueClient.recordEndedNaturally(
                attemptId: attemptID.rawValue,
                finalPositionMilliseconds: finalPosition.playbackMilliseconds
            )
        case let .skipped(attemptID, position, reason):
            queueClient.recordSkipped(
                attemptId: attemptID.rawValue,
                positionMilliseconds: position.playbackMilliseconds,
                reason: reason.coreName
            )
        case let .failedBeforeStart(attemptID, error):
            queueClient.recordFailedBeforeStart(
                attemptId: attemptID.rawValue,
                errorKind: error.coreName
            )
        case let .failedAfterPartial(attemptID, position, error):
            queueClient.recordFailedAfterPartial(
                attemptId: attemptID.rawValue,
                positionMilliseconds: position.playbackMilliseconds,
                errorKind: error.coreName
            )
        case let .routeChanged(attemptID, old, new, didPause):
            queueClient.recordRouteChanged(
                attemptId: attemptID.rawValue,
                oldRoute: old.coreName,
                newRoute: new.coreName,
                didPause: didPause
            )
        case let .interruptionBegan(attemptID, shouldResume):
            queueClient.recordInterruptionBegan(
                attemptId: attemptID.rawValue,
                shouldResume: shouldResume
            )
        case let .interruptionEnded(attemptID, shouldResume):
            queueClient.recordInterruptionEnded(
                attemptId: attemptID.rawValue,
                shouldResume: shouldResume
            )
        case let .attemptReplaced(oldAttemptID, newAttemptID):
            queueClient.recordAttemptReplaced(
                oldAttemptId: oldAttemptID.rawValue,
                newAttemptId: newAttemptID.rawValue
            )
        case let .advancedToPreloaded(oldAttemptID, newAttemptID):
            queueClient.recordAdvancedToPreloaded(
                oldAttemptId: oldAttemptID.rawValue,
                newAttemptId: newAttemptID.rawValue
            )
        case let .rateChanged(attemptID, rate):
            queueClient.recordRateChanged(attemptId: attemptID.rawValue, rate: rate)
        case let .engineTornDown(attemptID, reason):
            queueClient.recordEngineTornDown(
                attemptId: attemptID.rawValue,
                reason: reason.coreName
            )
        case let .sourceRefreshRequired(attemptID, reason):
            queueClient.recordSourceRefreshRequired(
                attemptId: attemptID.rawValue,
                reason: reason.coreName
            )
        case let .observationResynced(attemptID, snapshot):
            queueClient.recordObservationResynced(
                attemptId: attemptID.rawValue,
                status: snapshot.status.coreName,
                mediaPositionMilliseconds: snapshot.mediaPosition?.playbackMilliseconds ?? -1,
                durationMilliseconds: snapshot.duration?.playbackMilliseconds ?? -1,
                seekability: snapshot.seekability.coreName,
                rate: snapshot.rate,
                progressStartWallClockEpochMilliseconds: snapshot.progressStartWallClock.map {
                    Int64($0.timeIntervalSince1970 * 1_000)
                } ?? -1
            )
        }
    }

    private func publish(_ transition: ApplePlaybackQueueTransitionDto) {
        guard transition.errorKind == nil, let snapshot = transition.snapshot else {
            publishFailure()
            return
        }
        guard let session = snapshot.currentSession else {
            currentPresentation = .unavailable
            presentationHandler?(currentPresentation)
            return
        }
        guard ["Ready", "Progressing", "Buffering", "Paused"].contains(session.phase),
              let current = catalog[DulcetProviderItemID(
                providerInstanceID: session.providerInstanceId,
                rawID: session.rawId
              )] else {
            publishPreparing()
            return
        }
        let queue = snapshot.entries.compactMap { entry in
            catalog[DulcetProviderItemID(
                providerInstanceID: entry.providerInstanceId,
                rawID: entry.rawId
            )]
        }
        let phase: DulcetPlaybackPresentationPhase = switch session.phase {
        case "Progressing": .progressing
        case "Buffering": .buffering
        case "Paused": .paused
        default: .ready
        }
        let repeatMode = DulcetRepeatMode(rawValue: snapshot.repeatMode) ?? .off
        let index = Int(snapshot.currentIndex)
        let didProgress = progressingSessions.contains(session.playbackSessionId)
        currentPresentation = DulcetPlaybackPresentation(
            status: .ready,
            nowPlaying: DulcetNowPlaying(
                sessionID: DulcetPlaybackSessionID(session.playbackSessionId),
                current: current,
                queue: queue,
                currentIndex: index,
                elapsed: .milliseconds(max(0, session.positionMilliseconds)),
                isPlaying: didProgress && phase != .paused,
                outputName: DulcetPlaybackStrings.thisDevice,
                volume: 1,
                audioFormat: DulcetAudioFormat(
                    codec: current.sourceContainer?.displayName
                        ?? DulcetPlaybackStrings.unknownAudioFormat,
                    sampleRateKilohertz: 0
                ),
                phase: phase,
                seekability: session.seekability.presentationSeekability,
                progressBegan: didProgress,
                repeatMode: repeatMode,
                shuffleEnabled: snapshot.shuffleEnabled,
                canGoNext: index >= 0 && (
                    index + 1 < snapshot.entries.count || repeatMode == .all
                ),
                canGoPrevious: index > 0 || repeatMode == .all
            )
        )
        presentationHandler?(currentPresentation)
        engine.updateRemoteCommandCapabilities(
            DulcetRemoteCommandCapabilities(
                allowsNext: currentPresentation.nowPlaying?.canGoNext == true,
                allowsPrevious: currentPresentation.nowPlaying?.canGoPrevious == true
            ),
            for: DulcetPlaybackSessionID(session.playbackSessionId)
        )
    }

    private func publishPreparing() {
        currentPresentation = DulcetPlaybackPresentation(status: .preparing, nowPlaying: nil)
        presentationHandler?(currentPresentation)
    }

    private func publishFailure() {
        currentPresentation = DulcetPlaybackPresentation(status: .failed, nowPlaying: nil)
        presentationHandler?(currentPresentation)
    }

    private func handleRemoteCommand(_ command: DulcetRemotePlaybackCommand) -> Bool {
        guard queueClient.acceptsCommand(
            playbackSessionId: command.sessionID.rawValue,
            requiresSeekable: {
                if case .seek = command { return true }
                return false
            }()
        ) else { return false }
        switch command {
        case .play:
            send(.play)
        case .pause:
            send(.pause)
        case .toggle:
            send(.toggle)
        case .next:
            send(.next)
        case .previous:
            send(.previous)
        case let .seek(_, position):
            send(.seek(.milliseconds(position.playbackMilliseconds)))
        case .rating, .favourite:
            return false
        }
        return true
    }

    private func commandID(_ purpose: String) -> DulcetPlaybackCommandID {
        DulcetPlaybackCommandID("\(purpose)-\(UUID().uuidString)")
    }

    private static func closedFailureKind(_ value: String?) -> String {
        switch value {
        case "authentication": "authentication"
        case "forbidden": "forbidden"
        case "serverBusy": "serverBusy"
        case "sourceUnavailable": "sourceUnavailable"
        case "unsupportedPlan": "unsupportedPlan"
        case "protocol", "security": "protocolViolation"
        default: "transport"
        }
    }

    private static let deviceProfile = PlaybackDeviceProfile(
        name: "Dulcet Apple",
        platform: {
#if os(macOS)
            "macOS"
#elseif os(iOS)
            "iOS"
#else
            "tvOS"
#endif
        }(),
        maxAudioBitrate: 1_536_000,
        maxTranscodingAudioBitrate: 320_000,
        directPlayProfiles: [
            DirectPlayAudioProfile(
                containers: [.mp3, .mp4, .wav, .flac, .ogg, .adtsaac],
                audioCodecs: ["mp3", "aac", "alac", "flac", "opus", "vorbis", "pcm"],
                protocols: ["http"],
                maxAudioChannels: 8
            ),
        ],
        transcodingProfiles: [
            TranscodingAudioProfile(
                container: .mp3,
                audioCodec: "mp3",
                protocol: "http",
                maxAudioChannels: 2
            ),
        ]
    )
}

private final class DulcetCoreRemoteCommandBridge: DulcetRemotePlaybackCommandRouting,
    @unchecked Sendable {
    private let handler: @MainActor (DulcetRemotePlaybackCommand) -> Bool

    init(handler: @escaping @MainActor (DulcetRemotePlaybackCommand) -> Bool) {
        self.handler = handler
    }

    func handleRemotePlaybackCommand(_ command: DulcetRemotePlaybackCommand) -> Bool {
        if Thread.isMainThread {
            return MainActor.assumeIsolated { handler(command) }
        }
        return DispatchQueue.main.sync {
            MainActor.assumeIsolated { handler(command) }
        }
    }
}

private extension Duration {
    var playbackMilliseconds: Int64 {
        let parts = components
        let fractional = Double(parts.attoseconds) / 1_000_000_000_000_000
        return max(0, Int64((Double(parts.seconds) * 1_000 + fractional).rounded()))
    }

    var playbackTimeInterval: TimeInterval {
        TimeInterval(playbackMilliseconds) / 1_000
    }
}

private extension TimeInterval {
    var playbackMilliseconds: Int64 {
        guard isFinite else { return 0 }
        return max(0, Int64((self * 1_000).rounded()))
    }
}

private extension DulcetAudioContainer {
    var coreContainer: AudioContainer {
        switch self {
        case .mp3: .mp3
        case .mp4: .mp4
        case .wav: .wav
        case .flac: .flac
        case .ogg: .ogg
        case .adtsAAC: .adtsaac
        }
    }

    var displayName: String {
        switch self {
        case .mp3: "MP3"
        case .mp4: "M4A"
        case .wav: "WAV"
        case .flac: "FLAC"
        case .ogg: "Ogg"
        case .adtsAAC: "AAC"
        }
    }
}

private extension DulcetPlaybackSeekability {
    var coreName: String {
        switch self {
        case .seekable: "seekable"
        case .notSeekable: "notSeekable"
        case .unknown: "unknown"
        }
    }
}

private extension String {
    var presentationSeekability: DulcetPlaybackSeekability {
        switch self {
        case "Seekable": .seekable
        case "NotSeekable": .notSeekable
        default: .unknown
        }
    }
}

private extension DulcetPlaybackSkipReason {
    var coreName: String {
        switch self {
        case .user: "user"
        case .autoAdvance: "autoAdvance"
        case .queueReplacement: "queueReplacement"
        }
    }
}

private extension DulcetPlaybackFailure {
    var coreName: String {
        switch self {
        case .authentication: "authentication"
        case .forbidden: "forbidden"
        case .serverBusy: "serverBusy"
        case .protocolViolation: "protocolViolation"
        case .sourceUnavailable: "sourceUnavailable"
        case .unsupportedPlan: "unsupportedPlan"
        case .transport, .tlsUntrusted: "transport"
        case .engine: "engine"
        }
    }
}

private extension DulcetPlaybackRouteKind {
    var coreName: String {
        switch self {
        case .builtIn: "builtIn"
        case .wired: "wired"
        case .bluetooth: "bluetooth"
        case .hdmi: "hdmi"
        case .remote: "remote"
        case .unknown: "unknown"
        }
    }
}

private extension DulcetPlaybackEngineTeardownReason {
    var coreName: String {
        switch self {
        case .backgroundLimit: "backgroundLimit"
        case .lifecycle: "lifecycle"
        case .systemReclaimed: "systemReclaimed"
        case .released: "released"
        case .unknown: "unknown"
        }
    }
}

private extension DulcetPlaybackSourceRefreshReason {
    var coreName: String {
        switch self {
        case .unauthorized: "unauthorized"
        case .expired: "expired"
        case .validationFailed: "validationFailed"
        }
    }
}

private extension DulcetPlaybackObservationStatus {
    var coreName: String {
        switch self {
        case .preparing: "preparing"
        case .ready: "ready"
        case .progressing: "progressing"
        case .buffering: "buffering"
        case .paused: "paused"
        case .stopped: "stopped"
        case .failed: "failed"
        }
    }
}
