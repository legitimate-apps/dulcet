import DulcetCore
import DulcetKit
import Foundation

protocol DulcetCorePlaybackEngine: DulcetApplePlaybackEngine {
    func setRemoteCommandRouter(_ router: (any DulcetRemotePlaybackCommandRouting)?)
    @discardableResult
    func updateRemoteCommandCapabilities(
        _ capabilities: DulcetRemoteCommandCapabilities,
        for sessionID: DulcetPlaybackSessionID
    ) -> Bool
    func setNowPlayingArtwork(_ imageData: Data?, for sessionID: DulcetPlaybackSessionID)
}

extension DulcetCorePlaybackEngine {
    func setNowPlayingArtwork(_ imageData: Data?, for sessionID: DulcetPlaybackSessionID) {}
}

extension DulcetAVPlayerEngine: DulcetCorePlaybackEngine {}

/// What this process has done with scrobble effects, as counted by the core facade. Only
/// `submittedPlaysDelivered` says a play reached the server: it moves on an `ok` envelope for
/// `scrobble submission=true` and on nothing else. A persisted play is durable, not delivered.
struct DulcetScrobbleDeliveryReport: Equatable, Sendable {
    let submittedPlaysPersisted: Int
    let submittedPlaysDelivered: Int
    let submittedPlaysPending: Int
    let submittedPlayFailedAttempts: Int
    let nowPlayingSent: Int
    let nowPlayingDropped: Int

    init(_ dto: ApplePlaybackDeliveryReportDto) {
        submittedPlaysPersisted = Int(dto.submittedPlaysPersisted)
        submittedPlaysDelivered = Int(dto.submittedPlaysDelivered)
        submittedPlaysPending = Int(dto.submittedPlaysPending)
        submittedPlayFailedAttempts = Int(dto.submittedPlayFailedAttempts)
        nowPlayingSent = Int(dto.nowPlayingSent)
        nowPlayingDropped = Int(dto.nowPlayingDropped)
    }
}

/// A gapless preload in flight (spec §12.8): registered in the core, then resolved, then held by
/// the engine behind the current item until it reports `AdvancedToPreloaded`.
private struct DulcetPreloadInFlight {
    let attemptID: String
    let sessionID: String
    let track: DulcetTrack
    var resolve: (any ApplePlaybackWireOperation)?
    var corePlan: AppleRemotePlaybackPlanDto?
    var inEngine = false
}

@MainActor
final class DulcetCorePlaybackController: DulcetPlaybackControlling, DulcetQueueEditing {
    private let queueClient: ApplePlaybackQueueClient
    private let engine: any DulcetCorePlaybackEngine
    private let downloadController: (any DulcetDownloadControlling)?
    private let artworkFetcher: (any DulcetArtworkFetching)?
    private var artworkOperations: [String: any DulcetArtworkFetchOperation] = [:]
    private var artworkRequestedSessions: Set<String> = []
    private var preload: DulcetPreloadInFlight?
    /// Set after a preload met `Server.Busy`: no preload is attempted before this instant, so a
    /// busy server's own `Retry-After` is honoured rather than re-attacked (spec §12.2, §12.8).
    private var preloadSuppressedUntil: ContinuousClock.Instant?
    private var currentPlanIsTranscoded = false
    private let clock = ContinuousClock()
    /// Every preload lifecycle step, in order, for tests and diagnostics. Content-free.
    private(set) var preloadLog: [String] = []
    private var wireClient: ApplePlaybackWireClient?
    private var resolveOperation: (any ApplePlaybackWireOperation)?
    private var account: PlaybackEndpointAccount?
    private var presentationAccount: DulcetPlaybackAccount?
    private var catalog: [DulcetProviderItemID: DulcetTrack] = [:]
    private var progressingSessions: Set<String> = []
    private var restoredPausedSessions: Set<String> = []
    private var presentationHandler:
        (@MainActor (DulcetPlaybackPresentation) -> Void)?
    private var activeDirectiveIdentity: String?
    private var pendingStarts: [String: PendingStart] = [:]
    private lazy var remoteBridge = DulcetCoreRemoteCommandBridge { [weak self] command in
        self?.handleRemoteCommand(command) == true
    }

    private(set) var currentPresentation: DulcetPlaybackPresentation = .unavailable
    /// The latest track the core skipped past (spec §12.12). Every presentation carries it, so a
    /// surface sees a new one exactly once, by its sequence, whatever is published after it.
    private var skipNotice: DulcetSkippedTrackNotice?
    private var skipNoticeSequence = 0

    convenience init(
        databaseName: String = "dulcet.db",
        engine: DulcetAVPlayerEngine = DulcetAVPlayerEngine(),
        downloadController: (any DulcetDownloadControlling)? = nil,
        artworkFetcher: (any DulcetArtworkFetching)? = nil
    ) {
        self.init(
            queueClient: ApplePlaybackQueueClient(databaseName: databaseName),
            engine: engine,
            catalog: [],
            downloadController: downloadController,
            artworkFetcher: artworkFetcher
        )
    }

    init(
        queueClient: ApplePlaybackQueueClient,
        engine: any DulcetCorePlaybackEngine,
        catalog tracks: [DulcetTrack],
        downloadController: (any DulcetDownloadControlling)? = nil,
        artworkFetcher: (any DulcetArtworkFetching)? = nil
    ) {
        self.queueClient = queueClient
        self.engine = engine
        catalog = Dictionary(uniqueKeysWithValues: tracks.map { ($0.id, $0) })
        self.downloadController = downloadController
        self.artworkFetcher = artworkFetcher
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

    /// Receives the current report immediately and every later change, on the main actor. The
    /// presentation never carries this: a shell that wants to say "played" must wait for it here,
    /// because the ingestion path returns once the play is persisted, before any request leaves.
    func setScrobbleDeliveryHandler(
        _ handler: (@MainActor (DulcetScrobbleDeliveryReport) -> Void)?
    ) {
        guard let handler else {
            queueClient.setDeliveryReportObserver(observer: nil)
            return
        }
        queueClient.setDeliveryReportObserver { dto in
            let report = DulcetScrobbleDeliveryReport(dto)
            if Thread.isMainThread {
                MainActor.assumeIsolated { handler(report) }
            } else {
                DispatchQueue.main.async { handler(report) }
            }
        }
    }

    func configure(account presentationAccount: DulcetPlaybackAccount) {
        resolveOperation?.cancel()
        resolveOperation = nil
        if let preload { discardPreload(preload, reason: "configure", startsHeldEnd: false) }
        cancelArtwork()
        preloadSuppressedUntil = nil
        wireClient?.close()
        catalog = [:]
        progressingSessions = []
        restoredPausedSessions = []
        pendingStarts = [:]
        activeDirectiveIdentity = nil
        // A notice names a track of the account's queue, so reaching another server withdraws it
        // (§12.12 rule 5). Configuring the same server again keeps it.
        if self.presentationAccount?.providerInstanceID != presentationAccount.providerInstanceID {
            skipNotice = nil
        }
        let coreAccount = PlaybackEndpointAccount(
            providerInstanceId: presentationAccount.providerInstanceID,
            normalizedBaseUrl: presentationAccount.normalizedServerURL,
            username: presentationAccount.username,
            password: presentationAccount.password,
            allowLocalHttp: presentationAccount.allowLocalHTTP
        )
        account = coreAccount
        self.presentationAccount = presentationAccount
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

    /// Restores the saved queue from the tracks the library can currently speak for.
    ///
    /// The core reads the supplied catalog as "what can resolve now" and clears the saved
    /// selection when the current entry is missing from it, so no launch keeps trying to start
    /// something unresolvable. That is right only when the catalog is authoritative. Track lists
    /// are now read one album at a time, so right after a first paint the catalog is empty
    /// because nobody has read any album — not because the queue is gone. Under
    /// `.partial` coverage this therefore says nothing at all until the catalog can speak about
    /// the current entry, and the library calls it again as each album's tracks arrive. Under
    /// `.wholeLibrary` the behaviour is unchanged.
    func restorePersistedQueue(
        with tracks: [DulcetTrack],
        catalogCoverage: DulcetLibraryCatalogCoverage
    ) {
        guard let account else { return }
        catalog.merge(
            Dictionary(uniqueKeysWithValues: tracks.map { ($0.id, $0) }),
            uniquingKeysWith: { _, latest in latest }
        )
        if catalogCoverage == .partial, !catalogCoversPersistedSelection() { return }
        let transition = queueClient.restoreCurrentPausedWithCatalog(
            providerInstanceId: account.providerInstanceId,
            availableRawIds: tracks.filter {
                $0.id.providerInstanceID == account.providerInstanceId && $0.sourceContainer != nil
            }.map { $0.id.rawID }
        )
        guard transition.errorKind == nil else {
            publishFailure()
            return
        }
        // A bypass has no playback work and must not republish a live session's snapshot.
        guard let directive = transition.startDirective else { return }
        start(directive)
    }

    /// Whether the merged catalog holds the entry the saved queue is currently pointing at.
    /// A queue with no saved selection has nothing to restore either way.
    private func catalogCoversPersistedSelection() -> Bool {
        guard let persisted = queueClient.snapshot().snapshot else { return false }
        let currentIndex = Int(persisted.currentIndex)
        guard currentIndex >= 0, currentIndex < persisted.entries.count else { return false }
        let entry = persisted.entries[currentIndex]
        return catalog[DulcetProviderItemID(
            providerInstanceID: entry.providerInstanceId,
            rawID: entry.rawId
        )] != nil
    }

    func send(_ intent: DulcetPlaybackControlIntent) {
        guard let snapshot = queueClient.snapshot().snapshot else { return }
        guard let session = snapshot.currentSession else {
            // A finished queue keeps its last entry selected with no session (spec §14.3).
            // Play -- or Previous, which in Music replays -- starts it again as a new session.
            switch intent {
            case .play, .toggle, .previous:
                let transition = queueClient.startCurrent()
                guard transition.errorKind == nil else { return publishFailure() }
                start(transition.startDirective)
            case .next:
                // Next goes where the queue would have gone: round to the first entry under
                // repeat-all. The presentation offers Next exactly when this can act, so an
                // enabled button is never one that does nothing.
                guard Self.hasEntryAfterCurrent(snapshot) else { return }
                let transition = queueClient.next()
                guard transition.errorKind == nil else { return publishFailure() }
                start(transition.startDirective)
            case .retry:
                retryCurrentEntry()
            case let .setShuffle(enabled):
                applyEdit(queueClient.setShuffle(enabled: enabled))
            case .cycleRepeat:
                applyEdit(queueClient.cycleRepeatMode())
            case .pause, .seek:
                break
            }
            return
        }
        let sessionID = session.playbackSessionId
        switch intent {
        case .play:
            guard queueClient.acceptsCommand(
                playbackSessionId: sessionID,
                requiresSeekable: false
            ) else { return }
            // The core hears the person's Play as they press it (§12.12 rule 4): before the engine
            // is ready the engine reports nothing, and a failure can come before Ready.
            _ = queueClient.recordPlayRequested(playbackSessionId: sessionID)
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
            startOrStop(queueClient.nextForSession(playbackSessionId: sessionID))
        case .previous:
            startOrStop(queueClient.previousForSession(playbackSessionId: sessionID))
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
            applyEdit(queueClient.setShuffle(enabled: enabled))
        case .cycleRepeat:
            applyEdit(queueClient.cycleRepeatMode())
        case .retry:
            retryCurrentEntry()
        }
    }

    /// Whether Next has an entry to go to: one follows the current entry, or repeat-all wraps to
    /// the first. The same predicate decides the presentation's `canGoNext` without a session.
    private static func hasEntryAfterCurrent(_ snapshot: ApplePlaybackQueueSnapshotDto) -> Bool {
        let index = Int(snapshot.currentIndex)
        guard snapshot.entries.indices.contains(index) else { return false }
        return index + 1 < snapshot.entries.count
            || DulcetRepeatMode(rawValue: snapshot.repeatMode) == .all
    }

    /// Try Again. The core decides what that is (spec §12.1): after a failure before start, a new
    /// attempt inside the same session; after a track that stopped partway, a new play of it from
    /// where it stopped, because that failure already evaluated its session; with no session, the
    /// selected entry's start. A session that has not failed has nothing to try again.
    private func retryCurrentEntry() {
        guard account != nil else { return }
        let transition = queueClient.retryCurrent()
        guard transition.errorKind == nil else { return publishFailure() }
        guard transition.startDirective != nil else { return }
        publishPreparing()
        start(transition.startDirective)
    }

    /// Next past the last entry finalizes the session and starts nothing; the engine must then
    /// stop too, or the audio would keep playing under a stopped presentation.
    private func startOrStop(_ transition: ApplePlaybackQueueTransitionDto) {
        if transition.startDirective == nil, transition.snapshot?.currentSession == nil {
            abandonPreload(reason: "queue-finished")
            execute(.stop(commandID: commandID("queue-finished")))
        }
        start(transition.startDirective)
    }

    // MARK: Queue editing (spec §14.1, §14.2)

    @discardableResult
    func edit(_ intent: DulcetQueueEditIntent) -> Bool {
        switch intent {
        case let .playNext(addition):
            return enqueue(addition, mode: "playNext")
        case let .playLater(addition):
            return enqueue(addition, mode: "playLater")
        case let .move(entryID, toIndex):
            guard let coreIndex = coreIndex(forPresentedIndex: toIndex) else { return false }
            return applyEdit(queueClient.moveEntry(
                queueEntryId: entryID.rawValue,
                toIndex: Int32(coreIndex)
            ))
        case let .remove(entryID):
            return applyEdit(queueClient.removeEntry(queueEntryId: entryID.rawValue))
        case .clearUpcoming:
            return applyEdit(queueClient.clearUpcoming())
        case let .jump(entryID):
            let transition = queueClient.jumpTo(queueEntryId: entryID.rawValue)
            guard transition.errorKind == nil else { return false }
            publishPreparing()
            start(transition.startDirective)
            return true
        }
    }

    /// `queueEntries` omits entries the catalog cannot name yet, so a position in it is not a
    /// position in the core queue. The core position is the one the entry now shown at that
    /// presented position occupies: moving onto it lands after it when moving down and before it
    /// when moving up, which is what the presented list shows.
    private func coreIndex(forPresentedIndex presented: Int) -> Int? {
        guard let entries = currentPresentation.nowPlaying?.queueEntries,
              entries.indices.contains(presented),
              let core = queueClient.snapshot().snapshot?.entries.firstIndex(where: {
                  $0.queueEntryId == entries[presented].id.rawValue
              }) else { return nil }
        return core
    }

    private func enqueue(_ addition: DulcetQueueAddition, mode: String) -> Bool {
        guard account != nil, !addition.tracks.isEmpty else { return false }
        let playInstead = DulcetPlaybackQueueIntent(
            tracks: addition.tracks,
            sourceKind: addition.sourceKind,
            sourceID: addition.sourceID,
            sourceDisplayName: addition.sourceDisplayName,
            startIndex: 0,
            shuffle: false
        )
        // "Play Next" into an empty player means "play this": there is no queue to add to. The
        // core's queue decides emptiness, not the presentation -- a track that is still
        // preparing presents no now-playing item, and replacing its queue then would throw away
        // what the person just started.
        guard let queued = queueClient.snapshot().snapshot, !queued.entries.isEmpty,
              queued.entries.first?.providerInstanceId == account?.providerInstanceId else {
            replaceQueueAndPlay(playInstead)
            return true
        }
        catalog.merge(
            Dictionary(addition.tracks.map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest }),
            uniquingKeysWith: { _, latest in latest }
        )
        return applyEdit(queueClient.enqueue(insertion: ApplePlaybackQueueInsertionDto(
            items: addition.tracks.map { track in
                ApplePlaybackQueueItemDto(
                    providerInstanceId: track.id.providerInstanceID,
                    rawId: track.id.rawID,
                    durationMilliseconds: track.duration.playbackMilliseconds
                )
            },
            sourceKind: addition.sourceKind.rawValue,
            sourceRawId: addition.sourceID?.rawID,
            sourceDisplayName: addition.sourceDisplayName,
            mode: mode
        )))
    }

    /// An edit never starts or stops anything; it republishes the queue and re-checks the
    /// preload, because the entry that plays next may have changed.
    @discardableResult
    private func applyEdit(_ transition: ApplePlaybackQueueTransitionDto) -> Bool {
        guard transition.errorKind == nil else { return false }
        handleDiscardedPreload(transition.discardedPreloadAttemptId)
        if let directive = transition.startDirective {
            // The edit discarded a preload the core had held a natural end for: the next entry
            // starts now, or nothing ever would.
            start(directive)
            return true
        }
        publish(transition)
        requestPreloadIfNeeded()
        return true
    }

    func disconnect() {
        resolveOperation?.cancel()
        resolveOperation = nil
        if let preload { discardPreload(preload, reason: "disconnect", startsHeldEnd: false) }
        cancelArtwork()
        presentationAccount = nil
        wireClient?.close()
        wireClient = nil
        account = nil
        catalog = [:]
        progressingSessions = []
        restoredPausedSessions = []
        activeDirectiveIdentity = nil
        pendingStarts = [:]
        execute(.stop(commandID: commandID("disconnect")))
        // A notice names a track of the queue just left, so it goes with it: signing out must not
        // show the previous account's track. Reaching another server withdraws it in `configure`.
        skipNotice = nil
        currentPresentation = .unavailable
        emitPresentation()
    }

    private func start(_ directive: ApplePlaybackStartDirectiveDto?) {
        guard let directive else {
            publish(queueClient.snapshot())
            return
        }
        // Every start below issues a stop, and the engine's stop removes its preloaded item too;
        // the core discarded its registration when it began this session.
        abandonPreload(reason: "start")
        // The stop also clears the engine's artwork. A retry keeps its session (§12.1), so the
        // session's earlier delivery is no longer on the engine: forget it, and the next
        // publication fetches the artwork for the new attempt's item.
        cancelArtwork(sessionID: directive.playbackSessionId)
        guard let track = catalog[DulcetProviderItemID(
                providerInstanceID: directive.providerInstanceId,
                rawID: directive.rawId
              )] else {
            // The library cannot name this entry yet -- track lists are read one album at a
            // time, so an entry from an album nobody has opened since launch is simply unread.
            // That says nothing about the item, so it must not read as the item's own failure
            // and send the queue skipping past tracks that play (spec §12.12). It stops and is
            // presented, as every failure was before the rule, and Try Again starts it once the
            // library can name it.
            recordStartFailure(attemptId: directive.attemptId, errorKind: "transport")
            return
        }
        guard let sourceContainer = track.sourceContainer?.coreContainer else {
            // The server gave this item no container this client can play: nothing to resolve,
            // and nothing about the connection -- the item's own failure.
            recordStartFailure(attemptId: directive.attemptId, errorKind: "sourceUnavailable")
            return
        }
        if let offline = downloadController?.offlinePlaybackAsset(for: track) {
            resolveOperation?.cancel()
            resolveOperation = nil
            currentPlanIsTranscoded = false
            activeDirectiveIdentity = directive.attemptId
            pendingStarts[directive.attemptId] = PendingStart(
                shouldAutoPlay: directive.shouldAutoPlay,
                resumePositionMilliseconds: directive.resumePositionMilliseconds
            )
            if !directive.shouldAutoPlay {
                restoredPausedSessions.insert(directive.playbackSessionId)
            }
            publishPreparing()
            let plan = DulcetPlaybackPlan(
                playbackSessionID: DulcetPlaybackSessionID(directive.playbackSessionId),
                attemptID: DulcetPlaybackAttemptID(directive.attemptId),
                deliveryProtocol: .httpProgressive,
                expectedContainer: offline.expectedContainer,
                resource: offline.resource,
                metadata: DulcetNowPlayingMetadata(
                    title: track.title,
                    artist: track.artistNames.joined(separator: ", "),
                    albumTitle: track.albumTitle
                )
            )
            execute(.stop(commandID: commandID("replace"))) { [weak self] _ in
                self?.prepare(plan)
            }
            return
        }
        guard let wireClient else {
            // No connection to the server at all: that is never the item's own failure, so it
            // must not read as one and send the queue skipping (spec §12.12).
            recordStartFailure(attemptId: directive.attemptId, errorKind: "transport")
            return
        }
        resolveOperation?.cancel()
        activeDirectiveIdentity = directive.attemptId
        pendingStarts[directive.attemptId] = PendingStart(
            shouldAutoPlay: directive.shouldAutoPlay,
            resumePositionMilliseconds: directive.resumePositionMilliseconds
        )
        if !directive.shouldAutoPlay {
            restoredPausedSessions.insert(directive.playbackSessionId)
        }
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
                    // A withdrawn request is not a failure: it is neither presented nor skipped
                    // past (spec §12.12). Every withdrawal here also retires this directive, so
                    // the identity check above already drops it; this keeps that true should a
                    // withdrawal ever arrive for a directive that is still current.
                    guard outcome.errorKind != "cancelled" else { return }
                    self.recordStartFailure(
                        attemptId: directive.attemptId,
                        errorKind: Self.closedFailureKind(outcome.errorKind)
                    )
                    return
                }
                self.currentPlanIsTranscoded = corePlan.isTranscoded
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
                    self?.prepare(plan)
                }
            }
        }
    }

    private func prepare(_ plan: DulcetPlaybackPlan) {
        execute(.prepare(commandID: commandID("prepare"), plan: plan)) { [weak self] outcome in
            guard case .accepted = outcome else {
                self?.pendingStarts[plan.attemptID.rawValue] = nil
                return
            }
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
        if let preload, event.attemptID.rawValue == preload.attemptID,
           handlePreloadedAttemptEvent(event, preload: preload) {
            return
        }
        var holdingForPreload = false
        if case let .endedNaturally(attemptID, _) = event,
           let preload,
           queueClient.snapshot().snapshot?.currentSession?.attemptId == attemptID.rawValue {
            if preload.inEngine {
                // The engine takes over with `AdvancedToPreloaded`. Between the two events the
                // outgoing session reads Stopped, and presenting that would flash "Nothing is
                // playing" between two tracks of one album.
                holdingForPreload = true
            } else {
                // Registered but not yet in the engine: nothing will advance into it, so let the
                // core start the next entry normally rather than wait for a boundary that is
                // never coming.
                discardPreload(preload, reason: "ended-before-delivery")
            }
        }
        let transition = record(event)
        noteSkippedAfterFailure(transition)
        if case let .advancedToPreloaded(_, newAttemptID) = event,
           preload?.attemptID == newAttemptID.rawValue {
            preloadLog.append("advanced")
            currentPlanIsTranscoded = preload?.corePlan?.isTranscoded ?? false
            preload = nil
        }
        if case let .playbackProgressBegan(attemptID, _, _) = event,
           let session = transition.snapshot?.currentSession,
           session.attemptId == attemptID.rawValue {
            progressingSessions.insert(session.playbackSessionId)
            restoredPausedSessions.remove(session.playbackSessionId)
        }
        if !(holdingForPreload && transition.startDirective == nil) {
            publish(transition)
        }
        if case let .ready(attemptID, _, seekability) = event {
            completePendingStart(attemptID: attemptID, seekability: seekability)
        }
        if let directive = transition.startDirective {
            start(directive)
        } else if case let .playbackProgressBegan(attemptID, _, _) = event,
                  transition.snapshot?.currentSession?.attemptId == attemptID.rawValue {
            // Current playback is established before its successor may compete for the server
            // (spec §12.8: current playback > preload).
            requestPreloadIfNeeded()
        }
    }

    // MARK: Gapless preload (spec §12.8)

    /// Events for the preloaded attempt. A failure there must not reach the current session: the
    /// preload is dropped and the boundary falls back to a normal start. Returns whether the
    /// event was fully handled.
    private func handlePreloadedAttemptEvent(
        _ event: DulcetPlaybackEvent,
        preload: DulcetPreloadInFlight
    ) -> Bool {
        let failure: DulcetPlaybackFailure? = switch event {
        case let .failedBeforeStart(_, error), let .failedAfterPartial(_, _, error): error
        case .sourceRefreshRequired: .transport
        default: nil
        }
        guard let failure else { return false }
        if case let .serverBusy(retryAfter) = failure {
            if let corePlan = preload.corePlan {
                wireClient?.observePreloadFailure(plan: corePlan, errorKind: "serverBusy")
            }
            // At least the server's own recovery time; five seconds when it named none, which is
            // what the reference server sends (CLAUDE.md trap 24).
            let wait = max(retryAfter ?? Self.defaultBusyBackoff, Self.minimumBusyBackoff)
            preloadSuppressedUntil = clock.now.advanced(by: .milliseconds(Int64(wait * 1_000)))
            preloadLog.append("busy")
        }
        discardPreload(preload, reason: "failed")
        return true
    }

    private func requestPreloadIfNeeded() {
        guard preload == nil, let wireClient, account != nil else { return }
        if let until = preloadSuppressedUntil, clock.now < until { return }
        guard let snapshot = queueClient.snapshot().snapshot,
              let session = snapshot.currentSession,
              progressingSessions.contains(session.playbackSessionId) else { return }
        let transition = queueClient.preloadNextForSession(playbackSessionId: session.playbackSessionId)
        guard transition.errorKind == nil else { return }
        handleDiscardedPreload(transition.discardedPreloadAttemptId)
        guard let directive = transition.preloadDirective else { return }
        guard let track = catalog[DulcetProviderItemID(
                providerInstanceID: directive.providerInstanceId,
                rawID: directive.rawId
              )],
              let sourceContainer = track.sourceContainer?.coreContainer else {
            _ = queueClient.discardPreload(attemptId: directive.attemptId)
            preloadLog.append("declined-unresolvable")
            return
        }
        var inFlight = DulcetPreloadInFlight(
            attemptID: directive.attemptId,
            sessionID: directive.playbackSessionId,
            track: track
        )
        preloadLog.append("registered")
        let metadata = DulcetNowPlayingMetadata(
            title: track.title,
            artist: track.artistNames.joined(separator: ", "),
            albumTitle: track.albumTitle
        )
        if let offline = downloadController?.offlinePlaybackAsset(for: track) {
            preload = inFlight
            deliverPreload(DulcetPlaybackPlan(
                playbackSessionID: DulcetPlaybackSessionID(directive.playbackSessionId),
                attemptID: DulcetPlaybackAttemptID(directive.attemptId),
                deliveryProtocol: .httpProgressive,
                expectedContainer: offline.expectedContainer,
                resource: offline.resource,
                metadata: metadata
            ))
            return
        }
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
        inFlight.resolve = wireClient.startResolve(request: request) { [weak self] outcome in
            Task { @MainActor [weak self] in
                guard let self, let current = self.preload,
                      current.attemptID == directive.attemptId else { return }
                self.preload?.resolve = nil
                guard let corePlan = outcome.plan else {
                    self.discardPreload(current, reason: "resolve-failed")
                    return
                }
                // Direct play never consumes a transcode slot; a transcoded preload only while
                // the learned budget has one beside current playback.
                guard wireClient.mayPreload(
                    plan: corePlan,
                    currentPlaybackIsTranscoded: self.currentPlanIsTranscoded
                ) else {
                    self.discardPreload(current, reason: "budget")
                    return
                }
                self.preload?.corePlan = corePlan
                self.deliverPreload(DulcetCorePlaybackPlanFactory.makePlan(
                    client: wireClient,
                    corePlan: corePlan,
                    metadata: metadata
                ))
            }
        }
        preload = inFlight
    }

    private func deliverPreload(_ plan: DulcetPlaybackPlan) {
        let attemptID = plan.attemptID.rawValue
        execute(.preloadNext(commandID: commandID("preload"), plan: plan)) { [weak self] outcome in
            guard let self, let current = self.preload, current.attemptID == attemptID else { return }
            guard case .accepted = outcome else {
                self.discardPreload(current, reason: "engine-refused")
                return
            }
            self.preload?.inEngine = true
            self.preloadLog.append("in-engine")
            // Only now: the engine drops artwork for a session it does not hold, and a disk-cache
            // hit can complete before an in-flight preloadNext has reached the engine queue.
            self.fetchArtwork(for: current.track, sessionID: current.sessionID)
        }
    }

    /// Drops a preload from the core and the engine. The core then starts the next entry
    /// normally at the boundary.
    private func discardPreload(
        _ preload: DulcetPreloadInFlight,
        reason: String,
        startsHeldEnd: Bool = true
    ) {
        preload.resolve?.cancel()
        if self.preload?.attemptID == preload.attemptID { self.preload = nil }
        let transition = queueClient.discardPreload(attemptId: preload.attemptID)
        cancelArtwork(sessionID: preload.sessionID)
        execute(.discardPreloaded(
            commandID: commandID("discard-preload"),
            attemptID: DulcetPlaybackAttemptID(preload.attemptID)
        ))
        preloadLog.append("discarded:\(reason)")
        if startsHeldEnd, let directive = transition.startDirective {
            // The end was already held for this preload; nothing else will advance the queue.
            preloadLog.append("resumed-held-end")
            start(directive)
        }
    }

    /// The core already discarded it (a queue edit changed what plays next): remove it from the
    /// engine and forget it here.
    private func handleDiscardedPreload(_ attemptID: String?) {
        guard let attemptID, let preload, preload.attemptID == attemptID else { return }
        preload.resolve?.cancel()
        self.preload = nil
        cancelArtwork(sessionID: preload.sessionID)
        execute(.discardPreloaded(
            commandID: commandID("discard-preload"),
            attemptID: DulcetPlaybackAttemptID(attemptID)
        ))
        preloadLog.append("discarded:edit")
    }

    /// Forgets a preload whose engine item is being removed anyway (a stop, a new account).
    private func abandonPreload(reason: String) {
        guard let preload else { return }
        preload.resolve?.cancel()
        self.preload = nil
        preloadLog.append("abandoned:\(reason)")
    }

    // MARK: Now Playing artwork

    /// Loads artwork through the core's validated artwork path and hands the engine bytes, never
    /// a URL. Keyed by session so a late image for an earlier track cannot land on a later one.
    private func fetchArtwork(for track: DulcetTrack, sessionID: String) {
        guard !artworkRequestedSessions.contains(sessionID),
              let artworkFetcher,
              let presentationAccount,
              let reference = track.artwork.remoteReference,
              reference.serverID == presentationAccount.providerInstanceID else { return }
        let operation = artworkFetcher.fetch(DulcetArtworkFetchRequest(
            reference: reference,
            sizeBucket: .pixels512,
            normalizedServerURL: presentationAccount.normalizedServerURL,
            username: presentationAccount.username,
            password: presentationAccount.password,
            allowLocalHTTP: presentationAccount.allowLocalHTTP
        )) { [weak self] outcome in
            guard let self else { return }
            self.artworkOperations[sessionID] = nil
            guard case let .loaded(data) = outcome else { return }
            self.engine.setNowPlayingArtwork(data, for: DulcetPlaybackSessionID(sessionID))
        }
        if artworkRequestedSessions.count > 64 { artworkRequestedSessions = [] }
        artworkRequestedSessions.insert(sessionID)
        artworkOperations[sessionID] = operation
    }

    private func cancelArtwork(sessionID: String? = nil) {
        if let sessionID {
            artworkOperations.removeValue(forKey: sessionID)?.cancel()
            artworkRequestedSessions.remove(sessionID)
        } else {
            artworkOperations.values.forEach { $0.cancel() }
            artworkOperations = [:]
            artworkRequestedSessions = []
        }
    }

    private func completePendingStart(
        attemptID: DulcetPlaybackAttemptID,
        seekability: DulcetPlaybackSeekability
    ) {
        guard let pending = pendingStarts.removeValue(forKey: attemptID.rawValue) else { return }
        let finish: @MainActor () -> Void = { [weak self] in
            guard pending.shouldAutoPlay, let self else { return }
            self.execute(.play(commandID: self.commandID("autoplay")))
        }
        guard pending.resumePositionMilliseconds > 0, seekability == .seekable else {
            finish()
            return
        }
        execute(.seek(
            commandID: commandID("restore-position"),
            position: TimeInterval(pending.resumePositionMilliseconds) / 1_000
        )) { _ in finish() }
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

    func publish(_ transition: ApplePlaybackQueueTransitionDto) {
        guard transition.errorKind == nil, let snapshot = transition.snapshot else {
            publishFailure()
            return
        }
        guard let session = snapshot.currentSession else {
            publishWithoutSession(snapshot)
            return
        }
        // Every PlaybackAttemptPhase is named here, and tools/verify-playback-phase-parity fails
        // when the Kotlin enum carries a case this switch does not name. This was a four-name
        // allow-list with an `else` that published `.preparing`, which put FOUR phases behind one
        // presentation -- and three of them are not "preparing" in any sense a person would
        // recognise. `Stopped` and `TornDown` mean nothing is playing and nothing is coming, so
        // the spinner they produced could never resolve.
        switch session.phase {
        case "Failed":
            publishFailure()
            return
        case "Created", "Preparing":
            publishPreparing()
            return
        case "Stopped", "TornDown":
            // Reached on every stop, including the one `disconnect()` issues itself. The engine
            // emits `.skipped` from its stop implementation and the event listener delivers it
            // through `Task { @MainActor }`, so it lands AFTER `disconnect()` has published
            // `.unavailable` and overwrites it. Mapping the phase correctly removes the harm
            // rather than ordering around it: both paths now publish the same thing.
            publishUnavailable()
            return
        case "Ready", "Progressing", "Buffering", "Paused":
            break
        default:
            // A phase this shell does not know, which the parity gate exists to make impossible.
            // `.preparing` is the worst available guess: it is the one presentation that never
            // resolves on its own, so an unknown phase would strand the person on a spinner.
            publishUnavailable()
            return
        }
        guard let current = catalog[DulcetProviderItemID(
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
        let (queueEntries, currentEntryIndex) = presentedEntries(snapshot)
        fetchArtwork(for: current, sessionID: session.playbackSessionId)
        let phase: DulcetPlaybackPresentationPhase = switch session.phase {
        case "Progressing": .progressing
        case "Buffering": .buffering
        case "Paused": .paused
        default: restoredPausedSessions.contains(session.playbackSessionId) ? .paused : .ready
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
                sourceDisplayName: snapshot.entries.indices.contains(index)
                    ? snapshot.entries[index].sourceDisplayName
                    : nil,
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
                canGoPrevious: index > 0 || repeatMode == .all,
                queueEntries: queueEntries,
                currentEntryIndex: currentEntryIndex
            )
        )
        emitPresentation()
        engine.updateRemoteCommandCapabilities(
            DulcetRemoteCommandCapabilities(
                allowsNext: currentPresentation.nowPlaying?.canGoNext == true,
                allowsPrevious: currentPresentation.nowPlaying?.canGoPrevious == true
            ),
            for: DulcetPlaybackSessionID(session.playbackSessionId)
        )
    }

    /// Entry identities aligned with the tracks the catalog can speak for. An entry the catalog
    /// cannot resolve is left out of BOTH lists, so an index into one is never an index into a
    /// different entry of the other.
    private func presentedEntries(
        _ snapshot: ApplePlaybackQueueSnapshotDto
    ) -> ([DulcetQueueEntry], Int?) {
        var entries: [DulcetQueueEntry] = []
        var currentEntryIndex: Int?
        for (offset, entry) in snapshot.entries.enumerated() {
            guard let track = catalog[DulcetProviderItemID(
                providerInstanceID: entry.providerInstanceId,
                rawID: entry.rawId
            )] else { continue }
            if offset == Int(snapshot.currentIndex) { currentEntryIndex = entries.count }
            entries.append(DulcetQueueEntry(id: DulcetQueueEntryID(entry.queueEntryId), track: track))
        }
        return (entries, currentEntryIndex)
    }

    /// No session. After a queue finishes the core keeps the last entry selected (spec §14.3), so
    /// this presents that track stopped at its start -- as Music does -- instead of "Nothing is
    /// playing". With no selection, or a selection the catalog cannot name, nothing is playing.
    private func publishWithoutSession(_ snapshot: ApplePlaybackQueueSnapshotDto) {
        let index = Int(snapshot.currentIndex)
        guard snapshot.entries.indices.contains(index),
              let current = catalog[DulcetProviderItemID(
                  providerInstanceID: snapshot.entries[index].providerInstanceId,
                  rawID: snapshot.entries[index].rawId
              )] else {
            publishUnavailable()
            return
        }
        let repeatMode = DulcetRepeatMode(rawValue: snapshot.repeatMode) ?? .off
        let (queueEntries, currentEntryIndex) = presentedEntries(snapshot)
        currentPresentation = DulcetPlaybackPresentation(
            status: .ready,
            nowPlaying: DulcetNowPlaying(
                sessionID: nil,
                current: current,
                queue: queueEntries.map(\.track),
                currentIndex: currentEntryIndex ?? index,
                sourceDisplayName: snapshot.entries[index].sourceDisplayName,
                elapsed: .zero,
                isPlaying: false,
                outputName: DulcetPlaybackStrings.thisDevice,
                volume: 1,
                audioFormat: DulcetAudioFormat(
                    codec: current.sourceContainer?.displayName
                        ?? DulcetPlaybackStrings.unknownAudioFormat,
                    sampleRateKilohertz: 0
                ),
                phase: .paused,
                seekability: .unknown,
                progressBegan: false,
                repeatMode: repeatMode,
                shuffleEnabled: snapshot.shuffleEnabled,
                canGoNext: Self.hasEntryAfterCurrent(snapshot),
                canGoPrevious: true,
                queueEntries: queueEntries,
                currentEntryIndex: currentEntryIndex
            )
        )
        emitPresentation()
    }

    private func emitPresentation() {
        currentPresentation = currentPresentation.carrying(skipNotice: skipNotice)
        presentationHandler?(currentPresentation)
    }

    /// The core moved past a track whose failure was its own (spec §12.12): say which, once.
    private func noteSkippedAfterFailure(_ transition: ApplePlaybackQueueTransitionDto) {
        guard let entryID = transition.skippedAfterFailureQueueEntryId else { return }
        let entry = transition.snapshot?.entries.first { $0.queueEntryId == entryID }
        let title = entry.flatMap {
            catalog[DulcetProviderItemID(providerInstanceID: $0.providerInstanceId, rawID: $0.rawId)]?.title
        }
        skipNoticeSequence += 1
        skipNotice = DulcetSkippedTrackNotice(sequence: skipNoticeSequence, title: title)
    }

    /// A start that failed before the engine had it. Its transition can already be a skip past
    /// that entry (spec §12.12), which starts the next one; otherwise the failure is presented.
    /// The recursion through `start` is bounded by the core's chain guard.
    private func recordStartFailure(attemptId: String, errorKind: String) {
        let transition = queueClient.recordFailedBeforeStart(attemptId: attemptId, errorKind: errorKind)
        noteSkippedAfterFailure(transition)
        if transition.errorKind == nil, let directive = transition.startDirective {
            start(directive)
        } else {
            publishFailure()
        }
    }

    private func publishUnavailable() {
        currentPresentation = .unavailable
        emitPresentation()
    }

    private func publishPreparing() {
        currentPresentation = DulcetPlaybackPresentation(status: .preparing, nowPlaying: nil)
        emitPresentation()
    }

    private func publishFailure() {
        currentPresentation = DulcetPlaybackPresentation(
            status: .failed,
            nowPlaying: nil,
            failure: failedEntry()
        )
        emitPresentation()
    }

    /// The entry a failure belongs to: the queue's current session, when that session is the
    /// one that failed. Anything else -- a queue operation refused, no account -- is a failure
    /// with no entry to name, skip past or retry, and says so rather than guessing.
    private func failedEntry() -> DulcetFailedPlayback {
        guard account != nil,
              let snapshot = queueClient.snapshot().snapshot,
              snapshot.currentSession?.phase == "Failed" else { return .undescribed }
        let index = Int(snapshot.currentIndex)
        guard snapshot.entries.indices.contains(index) else { return .undescribed }
        let entry = snapshot.entries[index]
        return DulcetFailedPlayback(
            track: catalog[DulcetProviderItemID(
                providerInstanceID: entry.providerInstanceId,
                rawID: entry.rawId
            )],
            // The core's answer (spec §12.12), published with the queue, so the shell never
            // computes a second one. Skip asks it forward; an automatic skip asks the same core
            // function in its direction of travel.
            canSkip: snapshot.canSkipPastCurrent,
            canRetry: true,
            stoppedPartway: snapshot.currentSession?.failure == "afterPartial",
            queueEntryID: entry.queueEntryId,
            attemptID: snapshot.currentSession?.attemptId
        )
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

    private static let defaultBusyBackoff: TimeInterval = 5
    private static let minimumBusyBackoff: TimeInterval = 1

    private static func closedFailureKind(_ value: String?) -> String {
        DulcetPlaybackFailure(coreKind: value).coreName
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

private struct PendingStart {
    let shouldAutoPlay: Bool
    let resumePositionMilliseconds: Int64
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
