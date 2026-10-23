#if os(macOS) || os(iOS)
#if DEBUG
import CryptoKit
#endif
import DulcetCore
import DulcetKit
import Foundation

@MainActor
final class DulcetCoreDownloadController: NSObject, DulcetDownloadControlling, DulcetOfflineLibraryControlling {
    private var cacheWrites: [UUID: CacheWrite] = [:]
    private var protectedPlayback: [UUID: String] = [:]
    private struct CacheWrite {
        let identifier: String
        let totalLength: Int64
        let container: DulcetAudioContainer
    }
    var reconcileArtworkCache: (@MainActor (String, DulcetArtworkCacheIntent) async -> Void)?
    private let databaseName: String
    private let downloadRootURL: URL
    private let sessionConfiguration: URLSessionConfiguration
    private let diskBudgetBytes: Int64
    private let unknownLengthReservationBytes: Int64
    private var account: DulcetPlaybackAccount?
    private var client: AppleDownloadClient?
    private var statusHandler:
        (@MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void)?
    private var identityChangedHandler: (@MainActor () -> Void)?
    private var stateRefreshHandler: (@MainActor () -> Void)?
    private var mediaBindingWillChangeHandler: (@MainActor (DulcetPlaybackAccount) -> Void)?
    private var mediaBindingWillBeReplacedHandler: (@MainActor () -> Void)?
    private var prepareOperations: [DulcetProviderItemID: any AppleDownloadOperation] = [:]
    private var pendingTracks: [DulcetTrack] = []
    // Tests can retain a completed core prepare while a secured delivery wins the race.
    var preparedDownloadDelivery: (@MainActor (@escaping @MainActor () -> Void) -> Void)?
    var completedDownloadDelivery: (@MainActor (@escaping @MainActor () -> Void) -> Void)?
    var remoteTaskEnumerationDelivery: (@MainActor (@escaping @MainActor () -> Void) -> Void)?
    var securedDownloadValidationDelivery: (@MainActor (String, @escaping @MainActor () -> Void) -> Void)?
    // Like pending tracks, a preference belongs to the configured URL/user binding. Repeated
    // same-binding reconciliation must not discard the listener's latest choice.
    private var pendingAutomaticDownloadsEnabled: Bool?
    private var completedTaskIdentifiers: Set<Int> = []
    // Only tasks started here after accepted identity carry this process-local proof.
    private var authorizedTaskGenerations: [Int: Int] = [:]
    private var resumedTaskIdentifiers: Set<Int> = []
    private var removedTaskIdentifiers: Set<Int> = []
    // Includes restored tasks before any membership change can schedule cancellation.
    private var knownTaskIdentifiers: [Int: String] = [:]
    private var redirectsByTaskIdentifier: [Int: Int] = [:]
    private var queuePrepareOperation: (any AppleDownloadOperation)?
    private var retryTask: Task<Void, Never>?
    private var retryNotBeforeWallClock: Int64?
    private var backgroundEventsContinuation: CheckedContinuation<Void, Never>?
    private var backgroundEventsFinishedBeforeWait = false
    private var reconciliationGeneration = 0
    private var reconciled = false
    private var automaticDownloadContactPermitted = false
    private var remoteEligible = false
    private var identityRejected = false
    private var identityValidationOperation: (any AppleDownloadOperation)?
    private var validatingDeliveries: Set<String> = []
    private var deferredPreparations: [(outcome: AppleDownloadPreparationOutcomeDto,
        itemID: DulcetProviderItemID?, generation: Int)] = []
    private var cacheBudgetRevision = 0

    private var credentialGeneration: Int64 {
        account?.credentialGeneration ?? 0
    }

    private lazy var session: URLSession = {
        sessionConfiguration.requestCachePolicy = .reloadIgnoringLocalCacheData
        sessionConfiguration.urlCache = nil
        sessionConfiguration.httpCookieStorage = nil
        sessionConfiguration.urlCredentialStorage = nil
        return URLSession(
            configuration: sessionConfiguration,
            delegate: self,
            delegateQueue: .main
        )
    }()

    let downloadsEnabled: Bool

    static var productionBackgroundSessionIdentifier: String {
        let bundleIdentifier = Bundle.main.bundleIdentifier ?? "com.legitimateapps.dulcet"
        #if DEBUG
        if let namespace = DulcetDownloadHandoffProbe.namespace {
            return "\(bundleIdentifier).downloads.background.debug-handoff-\(namespace)"
        }
        #endif
        return "\(bundleIdentifier).downloads.background"
    }

    /// The database every production component of this app opens; one name, so the reader sees
    /// the rows the downloads write.
    static var productionDatabaseName: String {
        #if DEBUG
        if let namespace = DulcetDownloadHandoffProbe.namespace {
            return "dulcet-handoff-\(namespace).db"
        }
        #endif
        return "dulcet.db"
    }

    init(
        databaseName: String,
        downloadRootURL: URL,
        sessionConfiguration: URLSessionConfiguration,
        downloadsEnabled: Bool = true,
        diskBudgetBytes: Int64 = 10 * 1_024 * 1_024 * 1_024,
        unknownLengthReservationBytes: Int64 = 512 * 1_024 * 1_024
    ) {
        precondition(downloadRootURL.isFileURL)
        self.databaseName = databaseName
        self.downloadRootURL = downloadRootURL
        self.sessionConfiguration = sessionConfiguration
        self.downloadsEnabled = downloadsEnabled
        self.diskBudgetBytes = diskBudgetBytes
        self.unknownLengthReservationBytes = unknownLengthReservationBytes
        super.init()
    }

    static func production() -> DulcetCoreDownloadController? {
        guard let applicationSupport = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else { return nil }
        var downloadsDirectory = "Downloads"
        #if DEBUG
        if let namespace = DulcetDownloadHandoffProbe.namespace {
            downloadsDirectory = "Downloads-handoff-\(namespace)"
        }
        #endif
        let root = applicationSupport
            .appendingPathComponent("Dulcet", isDirectory: true)
            .appendingPathComponent(downloadsDirectory, isDirectory: true)
        let configuration = URLSessionConfiguration.background(
            withIdentifier: productionBackgroundSessionIdentifier
        )
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        return Self(
            databaseName: productionDatabaseName,
            downloadRootURL: root,
            sessionConfiguration: configuration
        )
    }

    func downloadCount(providerInstanceID: String) -> Int? {
        let count = Int(AppleMediaBindingClient.shared.downloadCount(databaseName: databaseName, providerInstanceId: providerInstanceID))
        return count >= 0 ? count : nil
    }

    var downloadCountToRemove: Int? { account.flatMap { downloadCount(providerInstanceID: $0.providerInstanceID) } }

    func removeSavedAccountData(providerInstanceID: String) async -> Bool {
        if account?.providerInstanceID == providerInstanceID { return await removeAccountData() }
        return AppleMediaBindingClient.shared.removeAccountData(databaseName: databaseName,
            downloadRootPath: downloadRootURL.path, providerInstanceId: providerInstanceID)
    }

    func setStatusHandler(
        _ handler: @escaping @MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void
    ) {
        statusHandler = handler
    }

    func setServerIdentityChangedHandler(_ handler: @escaping @MainActor () -> Void) {
        identityChangedHandler = handler
    }

    func setStateRefreshHandler(_ handler: @escaping @MainActor () -> Void) {
        stateRefreshHandler = handler
    }

    func setMediaBindingWillChangeHandler(_ handler: @escaping @MainActor (DulcetPlaybackAccount) -> Void) {
        mediaBindingWillChangeHandler = handler
    }

    func setMediaBindingWillBeReplacedHandler(_ handler: @escaping @MainActor () -> Void) {
        mediaBindingWillBeReplacedHandler = handler
    }

    private func endpointAccount(_ value: DulcetPlaybackAccount) -> PlaybackEndpointAccount {
        PlaybackEndpointAccount(providerInstanceId: value.providerInstanceID,
            normalizedBaseUrl: value.normalizedServerURL, username: value.username,
            password: value.password, allowLocalHttp: value.allowLocalHTTP)
    }

    func isSameMediaBinding(first: DulcetPlaybackAccount, second: DulcetPlaybackAccount) -> Bool {
        guard MediaBindingIdentity.shared.isValidBinding(providerInstanceId: first.providerInstanceID,
            normalizedBaseUrl: first.normalizedServerURL, username: first.username),
            MediaBindingIdentity.shared.isValidBinding(providerInstanceId: second.providerInstanceID,
            normalizedBaseUrl: second.normalizedServerURL, username: second.username)
        else { return false }
        let first = endpointAccount(first)
        let second = endpointAccount(second)
        if let client { return client.isSameMediaBinding(first: first, second: second) }
        return AppleMediaBindingClient.shared.isSameMediaBinding(databaseName: databaseName,
            downloadRootPath: downloadRootURL.path, first: first, second: second)
    }

    private enum AdoptionFailure: Error { case persistence }

    func hasSameMediaOrigin(first: DulcetPlaybackAccount, second: DulcetPlaybackAccount) -> Bool {
        let identity = MediaBindingIdentity.shared
        return identity.isValidBinding(providerInstanceId: first.providerInstanceID,
            normalizedBaseUrl: first.normalizedServerURL, username: first.username) &&
            identity.isValidBinding(providerInstanceId: second.providerInstanceID,
            normalizedBaseUrl: second.normalizedServerURL, username: second.username) &&
            first.providerInstanceID == second.providerInstanceID &&
            identity.canonicalServerUrl(normalizedBaseUrl: first.normalizedServerURL) ==
                identity.canonicalServerUrl(normalizedBaseUrl: second.normalizedServerURL)
    }

    func adoptAuthenticatedAccount(previous: DulcetPlaybackAccount, confirmed: DulcetPlaybackAccount) throws -> Bool {
        guard hasSameMediaOrigin(first: previous, second: confirmed) else { return false }
        let old = endpointAccount(previous)
        let new = endpointAccount(confirmed)
        let adopted: Bool
        if let client {
            adopted = client.adoptAuthenticatedAccount(previous: old, confirmed: new)
        } else {
            adopted = AppleMediaBindingClient.shared.adoptAuthenticatedAccount(databaseName: databaseName,
                downloadRootPath: downloadRootURL.path, previous: old, confirmed: new)
        }
        guard adopted else { throw AdoptionFailure.persistence }
        return true
    }

    func replaceUnconfirmedAccount(previous: DulcetPlaybackAccount, confirmed: DulcetPlaybackAccount) throws {
        guard MediaBindingIdentity.shared.isValidBinding(providerInstanceId: confirmed.providerInstanceID,
            normalizedBaseUrl: confirmed.normalizedServerURL, username: confirmed.username)
        else { throw AdoptionFailure.persistence }
        // This authenticated login did not prove continuity with saved credentials. Its name
        // may be an alias from an earlier login, so retirement cannot use alias equality.
        mediaBindingWillBeReplacedHandler?()
        automaticDownloadContactPermitted = false
        reconciliationGeneration += 1
        deferredPreparations = []
        authorizedTaskGenerations = [:]
        reconciled = false
        pendingAutomaticDownloadsEnabled = nil
        cacheWrites.values.forEach { client?.abortStreamingCache(writeId: $0.identifier) }
        cacheWrites = [:]
        protectedPlayback = [:]
        updatePlaybackProtection()
        pendingTracks = []
        prepareOperations.values.forEach { $0.cancel() }
        prepareOperations = [:]
        queuePrepareOperation?.cancel()
        queuePrepareOperation = nil
        cancelRetry()
        let taskIDs = Set(knownTaskIdentifiers.keys)
        let downloadIDs = Set(knownTaskIdentifiers.values)
        removedTaskIdentifiers.formUnion(taskIDs)
        // URLSession enumeration is asynchronous. Capture only the retired tasks, so a late
        // cancellation cannot affect a new binding; the closed client also rejects callbacks.
        session.getAllTasks { tasks in
            for task in tasks where taskIDs.contains(task.taskIdentifier) ||
                task.taskDescription.map(downloadIDs.contains) == true { task.cancel() }
        }
        client?.close()
        client = nil
        account = nil
        guard AppleMediaBindingClient.shared.replaceUnconfirmedAccount(databaseName: databaseName,
            downloadRootPath: downloadRootURL.path, confirmed: endpointAccount(confirmed))
        else { throw AdoptionFailure.persistence }
        account = confirmed
        stateRefreshHandler?()
    }

    func configure(account: DulcetPlaybackAccount) {
        guard MediaBindingIdentity.shared.isValidBinding(
            providerInstanceId: account.providerInstanceID,
            normalizedBaseUrl: account.normalizedServerURL,
            username: account.username
        ) else { return }
        automaticDownloadContactPermitted = false
        remoteEligible = false
        identityRejected = false
        identityValidationOperation?.cancel()
        identityValidationOperation = nil
        validatingDeliveries = []
        let sameAccount = self.account.map { previous in
            MediaBindingIdentity.shared.sameBinding(
                firstProviderInstanceId: previous.providerInstanceID,
                firstNormalizedBaseUrl: previous.normalizedServerURL,
                firstUsername: previous.username,
                secondProviderInstanceId: account.providerInstanceID,
                secondNormalizedBaseUrl: account.normalizedServerURL,
                secondUsername: account.username
            ) || isSameMediaBinding(first: previous, second: account)
        } ?? false
        if !sameAccount {
            // This also covers downloads-first configuration: the engine must relinquish its
            // current and prepared items before the new client's eager bind can purge files.
            mediaBindingWillChangeHandler?(account)
            cacheWrites.values.forEach { client?.abortStreamingCache(writeId: $0.identifier) }
            cacheWrites = [:]
            protectedPlayback = [:]
            updatePlaybackProtection()
        }
        reconciliationGeneration += 1
        deferredPreparations = []
        authorizedTaskGenerations = [:]
        let generation = reconciliationGeneration
        reconciled = false
        prepareOperations.values.forEach { $0.cancel() }
        prepareOperations = [:]
        queuePrepareOperation?.cancel()
        queuePrepareOperation = nil
        cancelRetry()
        client?.close()
        cacheWrites = [:]
        if !sameAccount {
            pendingAutomaticDownloadsEnabled = nil
            protectedPlayback = [:]
            pendingTracks = []
            removedTaskIdentifiers.formUnion(knownTaskIdentifiers.keys)
        }
        self.account = account
        client = AppleDownloadClient(
            account: PlaybackEndpointAccount(
                providerInstanceId: account.providerInstanceID,
                normalizedBaseUrl: account.normalizedServerURL,
                username: account.username,
                password: account.password,
                allowLocalHttp: account.allowLocalHTTP
            ),
            databaseName: databaseName,
            downloadRootPath: downloadRootURL.path
        )
        session.getAllTasks { [weak self] tasks in
            Task { @MainActor [weak self] in
                guard let self, generation == reconciliationGeneration else { return }
                tasks.filter { task in task.taskDescription.map { self.client?.ownsDownloadTask(downloadIdentifier: $0) == true } ?? false }
                    .forEach { if $0.state == .running { $0.suspend() } }
                finishReconciliation(tasks: tasks)
            }
        }
    }

    func requestDownload(_ track: DulcetTrack) {
        guard downloadsEnabled, track.availability == .playable else { return }
        guard let account, track.id.providerInstanceID == account.providerInstanceID else { return }
        // DEBUG proof witness shared by row actions and the Mac launch-argument driver.
        // Automatic membership reconciliation does not enter this explicit-request path.
        probe("download-requested item=\(track.id.rawID)")
        guard reconciled, remoteEligible, !identityRejected, validatingDeliveries.isEmpty else {
            if !pendingTracks.contains(where: { $0.id == track.id }) {
                pendingTracks.append(track)
            }
            publish(.queued, for: track.id)
            return
        }
        guard prepareOperations[track.id] == nil else { return }
        guard let sourceContainer = track.sourceContainer?.coreContainer,
              let client else {
            publish(.failed, for: track.id)
            return
        }
        publish(.queued, for: track.id)
        let generation = reconciliationGeneration
        let operation = client.startPrepareOriginalDownload(
            rawId: track.id.rawID,
            sourceContainer: sourceContainer,
            durationMilliseconds: track.duration.downloadMilliseconds,
            credentialGeneration: credentialGeneration,
            wallClockMilliseconds: Date().downloadWallClockMilliseconds,
            diskBudgetBytes: diskBudgetBytes,
            unknownLengthReservationBytes: unknownLengthReservationBytes
        ) { [weak self] outcome in
            Task { @MainActor [weak self] in
                guard let self, generation == reconciliationGeneration else { return }
                let deliver: @MainActor () -> Void = { [weak self] in
                    guard let self, generation == reconciliationGeneration else { return }
                    prepareOperations[track.id] = nil
                    handlePreparationOutcome(outcome, fallbackItemID: track.id)
                }
                if outcome.prepared != nil, let preparedDownloadDelivery { preparedDownloadDelivery(deliver) }
                else { deliver() }
            }
        }
        prepareOperations[track.id] = operation
    }

    func status(for id: DulcetProviderItemID) -> DulcetDownloadState {
        guard reconciled,
              let account,
              id.providerInstanceID == account.providerInstanceID,
              let outcome = client?.status(rawId: id.rawID),
              outcome.errorKind == nil else { return .notDownloaded }
        return DulcetDownloadState(rawValue: outcome.state) ?? .failed
    }

    func offlinePlaybackAsset(for track: DulcetTrack) -> DulcetOfflinePlaybackAsset? {
        guard reconciled,
              let account,
              track.id.providerInstanceID == account.providerInstanceID,
              let outcome = client?.localPlaybackPlan(rawId: track.id.rawID),
              outcome.errorKind == nil,
              let plan = outcome.plan,
              let container = DulcetAudioContainer(coreName: plan.expectedContainer),
              plan.exactByteLength >= 0 else { return nil }
        let resource = DulcetLocalFilePlaybackResource(
            fileURL: URL(fileURLWithPath: plan.filePath),
            exactByteLength: plan.exactByteLength
        )
        return DulcetOfflinePlaybackAsset(
            expectedContainer: container,
            exactByteLength: plan.exactByteLength,
            resource: cachingResource(resource, track: track, expectedContainer: container, cacheRemoteResponses: false)
        )
    }

    var offlineLibraryStorage: DulcetOfflineLibraryStorage {
        guard reconciled, let value = client?.offlineLibraryStorage(), value.errorKind == nil else { return .init() }
        return .init(automaticDownloadsEnabled: value.automaticDownloadsEnabled,
                     cacheBudgetBytes: value.cacheBudgetBytes, downloadBytes: value.downloadBytes,
                     cacheBytes: value.cacheBytes, artworkBytes: value.artworkBytes, queuedAutomaticCount: Int(value.queuedAutomaticCount),
                     completedAutomaticCount: Int(value.completedAutomaticCount))
    }

    func setAutomaticDownloadsEnabled(_ enabled: Bool) {
        guard account != nil else { return }
        pendingAutomaticDownloadsEnabled = enabled
        _ = flushPendingAutomaticDownloadsSetting(
            allowNewDownloads: automaticDownloadContactPermitted, scheduleDownloads: true)
    }

    @discardableResult
    private func flushPendingAutomaticDownloadsSetting(allowNewDownloads: Bool, scheduleDownloads: Bool) -> Bool {
        guard let enabled = pendingAutomaticDownloadsEnabled else { return true }
        guard reconciled, let client else { return false }
        let outcome = client.setAutomaticDownloadsEnabled(enabled: enabled, allowNewDownloads: allowNewDownloads,
            credentialGeneration: credentialGeneration,
            wallClockMilliseconds: Date().downloadWallClockMilliseconds)
        // Keep the latest intent after a persistence/binding failure. A later same-binding
        // reconciliation or setting action retries it without starting automatic work first.
        guard outcome.errorKind == nil else { return false }
        pendingAutomaticDownloadsEnabled = nil
        applyOfflineChange(outcome, scheduleDownloads: scheduleDownloads)
        return true
    }
    func setStreamingCacheBudgetBytes(_ bytes: Int64) {
        guard bytes >= 0, reconciled, let serverID = account?.providerInstanceID else { return }
        cacheBudgetRevision += 1
        let revision = cacheBudgetRevision
        let generation = reconciliationGeneration
        Task { @MainActor [weak self] in
            guard let self else { return }
            await reconcileArtworkCache?(serverID, .reconcile(reservationBytes: 0, budgetBytesOverride: bytes))
            guard generation == reconciliationGeneration, revision == cacheBudgetRevision,
                  serverID == account?.providerInstanceID,
                  client?.setCacheBudgetBytes(bytes: bytes) == true else { return }
            stateRefreshHandler?()
        }
    }
    func clearStreamingCache() {
        guard reconciled, let serverID = account?.providerInstanceID else { return }
        let generation = reconciliationGeneration
        Task { @MainActor [weak self] in
            guard let self else { return }
            await reconcileArtworkCache?(serverID, .clear)
            guard generation == reconciliationGeneration, serverID == account?.providerInstanceID,
                  client?.clearStreamingCache() == true else { return }
            stateRefreshHandler?()
        }
    }
    func missingAutomaticDownloadDetails() -> AppleOfflineLibraryHydrationDto? {
        guard reconciled, automaticDownloadContactPermitted else { return nil }
        return client?.missingAutomaticDownloadDetails()
    }
    func reconcileMyLibrary() {
        guard reconciled, let client,
              flushPendingAutomaticDownloadsSetting(
                allowNewDownloads: automaticDownloadContactPermitted, scheduleDownloads: false) else { return }
        applyOfflineChange(client.reconcileMyLibrary(allowNewDownloads: automaticDownloadContactPermitted,
            credentialGeneration: credentialGeneration,
            wallClockMilliseconds: Date().downloadWallClockMilliseconds))
    }
    func setAutomaticDownloadContactPermitted(_ permitted: Bool) {
        automaticDownloadContactPermitted = permitted
        if permitted { reconcileMyLibrary(); stateRefreshHandler?() }
    }

    func rejectServerIdentity() {
        guard !identityRejected else { return }
        identityRejected = true
        deferredPreparations = []
        authorizedTaskGenerations = [:]
        remoteEligible = false
        client?.rejectServerIdentity()
        // Retain the policy/local plans. Only remote work and its late completions are retired.
        prepareOperations.values.forEach { $0.cancel() }
        prepareOperations = [:]
        queuePrepareOperation?.cancel()
        queuePrepareOperation = nil
        identityValidationOperation?.cancel()
        identityValidationOperation = nil
        cancelRetry()
        markPendingDeliveriesRejected()
        let owned = Set(knownTaskIdentifiers.compactMap { taskID, id in
            client?.ownsDownloadTask(downloadIdentifier: id) == true ? taskID : nil
        })
        removedTaskIdentifiers.formUnion(owned)
        let generation = reconciliationGeneration
        session.getAllTasks { [weak self] tasks in
            Task { @MainActor [weak self] in
                guard let self, generation == reconciliationGeneration, identityRejected else { return }
                for task in tasks where task.taskDescription.map({ self.client?.ownsDownloadTask(downloadIdentifier: $0) == true }) ?? false {
                    removedTaskIdentifiers.insert(task.taskIdentifier)
                    task.cancel()
                }
            }
        }
        identityChangedHandler?()
    }

    private func validateRemoteAccess(_ completion: @escaping @MainActor (Bool) -> Void) {
        guard let client, !identityRejected else { completion(false); return }
        let generation = reconciliationGeneration
        identityValidationOperation = client.startValidateServerIdentity { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self, generation == reconciliationGeneration, !identityRejected else { return }
                if error == "serverIdentityChanged" { rejectServerIdentity(); completion(false); return }
                if let error {
                    remoteEligible = false
                    session.getAllTasks { [weak self] tasks in
                        Task { @MainActor [weak self] in
                            guard let self, generation == reconciliationGeneration, !remoteEligible else { return }
                            tasks.filter { $0.taskDescription.map { self.client?.ownsDownloadTask(downloadIdentifier: $0) == true } ?? false }
                                .forEach { if $0.state == .running { $0.suspend() } }
                        }
                    }
                    if ["transport", "timeout", "serverBusy", "server"].contains(error) {
                        scheduleRetry(at: Date().downloadWallClockMilliseconds + 30_000)
                    }
                }
                completion(error == nil)
            }
        }
    }
    private func applyOfflineChange(_ outcome: AppleOfflineLibraryChangeDto, scheduleDownloads: Bool = true) {
        guard outcome.errorKind == nil else { return }
        let cancelled = Set(outcome.taskIdentifiersToCancel)
        if !cancelled.isEmpty {
            // Mark known tasks before URLSession's asynchronous enumeration. Completion may
            // already be queued on the main actor when cancellation loses the race.
            removedTaskIdentifiers.formUnion(knownTaskIdentifiers.compactMap { taskID, identifier in
                cancelled.contains(identifier) ? taskID : nil
            })
            let generation = reconciliationGeneration
            session.getAllTasks { [weak self] tasks in
                Task { @MainActor [weak self] in
                    guard let self, generation == reconciliationGeneration else { return }
                    let removed = tasks.filter { $0.taskDescription.map(cancelled.contains) == true }
                    removedTaskIdentifiers.formUnion(removed.map(\.taskIdentifier))
                    removed.forEach { $0.cancel() }
                }
            }
        }
        if scheduleDownloads { scheduleNextDownload() }
        if outcome.hasChanges { refreshArtworkBudget(); stateRefreshHandler?() }
    }

    /// A cache tap belongs to this playback resource, including preload/crossfade reuse. The
    /// core protects every currently retained local profile for its item until the resource dies.
    func cachingResource(_ resource: any DulcetPlaybackResourceLoading, track: DulcetTrack,
                         expectedContainer: DulcetAudioContainer, cacheRemoteResponses: Bool = true) -> any DulcetPlaybackResourceLoading {
        let token = UUID()
        let generation = reconciliationGeneration
        protectedPlayback[token] = track.id.rawID
        updatePlaybackProtection()
        return DulcetCachingPlaybackResource(source: resource, observe: { [weak self] request, data, information in
            Task { @MainActor [weak self] in
                guard let self, cacheRemoteResponses, generation == reconciliationGeneration else { return }
                if cacheWrites[token] == nil {
                    await reconcileArtworkCache?(track.id.providerInstanceID, .reconcile(reservationBytes: information.contentLength, budgetBytesOverride: nil))
                }
                guard generation == reconciliationGeneration else { return }
                cacheValidatedBytes(token: token, track: track, expectedContainer: expectedContainer,
                                         request: request, data: data, information: information)
            }
        }, release: { [weak self] in
            Task { @MainActor [weak self] in
                // A same-binding credential refresh may keep this resource playing. A
                // rebind clears its unique token, so an old account cannot publish a release.
                guard let self, protectedPlayback[token] == track.id.rawID else { return }
                if let write = cacheWrites.removeValue(forKey: token) { client?.abortStreamingCache(writeId: write.identifier) }
                protectedPlayback[token] = nil
                updatePlaybackProtection()
                reconcileMyLibrary()
                if let budget = client?.offlineLibraryStorage().cacheBudgetBytes { _ = client?.setCacheBudgetBytes(bytes: budget) }
            }
        })
    }
    private func updatePlaybackProtection() {
        client?.protectPlaybackItems(rawIds: Array(Set(protectedPlayback.values)))
    }
    private func cacheValidatedBytes(token: UUID, track: DulcetTrack, expectedContainer: DulcetAudioContainer,
                                     request: DulcetPlaybackResourceLoadRequest, data: Data,
                                     information: DulcetPlaybackContentInformation) {
        guard reconciled, protectedPlayback[token] == track.id.rawID,
              track.id.providerInstanceID == account?.providerInstanceID, let client else { return }
        let container = information.container ?? expectedContainer
        if cacheWrites[token] == nil {
            guard let identifier = client.beginStreamingCache(rawId: track.id.rawID, container: container.coreContainer,
                      totalLength: information.contentLength, durationMilliseconds: track.duration.downloadMilliseconds,
                      rangeStart: request.range.start,
                      credentialGeneration: credentialGeneration, wallClockMilliseconds: Date().downloadWallClockMilliseconds) else { return }
            cacheWrites[token] = CacheWrite(identifier: identifier, totalLength: information.contentLength,
                                          container: container)
            updatePlaybackProtection()
        }
        guard let write = cacheWrites[token] else { return }
        guard information.contentLength == write.totalLength, container == write.container else {
            client.abortStreamingCache(writeId: write.identifier)
            cacheWrites[token] = nil
            return
        }
        // AVFoundation reads metadata tails and seeks out of order. The common disk writer
        // merges absolute interval coverage, including overlaps, without issuing refill requests.
        guard client.appendStreamingCache(writeId: write.identifier, bytes: data, offset: request.range.start) else {
            cacheWrites[token] = nil
            return
        }
        if client.isStreamingCacheWriteComplete(writeId: write.identifier) {
            let promoted = client.finishStreamingCache(writeId: write.identifier, totalLength: write.totalLength,
                                                       wallClockMilliseconds: Date().downloadWallClockMilliseconds)
            cacheWrites[token] = nil
            refreshArtworkBudget()
            if promoted { stateRefreshHandler?() }
        }
    }

    func artworkCachePlan(serverID: String, evictableBytes: Int64, pinnedBytes: Int64,
                          intent: DulcetArtworkCacheIntent) -> ArtworkCacheBudgetPlan? {
        guard reconciled, serverID == account?.providerInstanceID else { return nil }
        switch intent {
        case let .reconcile(reservationBytes, budgetBytesOverride):
            if let budgetBytesOverride {
                return client?.planArtworkCacheForBudget(evictableBytes: evictableBytes, pinnedBytes: pinnedBytes,
                    reservationBytes: reservationBytes, budgetBytes: budgetBytesOverride)
            }
            return client?.planArtworkCache(evictableBytes: evictableBytes, pinnedBytes: pinnedBytes,
                                            reservationBytes: reservationBytes)
        case .clear:
            return client?.planClearArtworkCache(evictableBytes: evictableBytes, pinnedBytes: pinnedBytes)
        }
    }
    private func refreshArtworkBudget() {
        guard let serverID = account?.providerInstanceID else { return }
        Task { @MainActor [weak self] in await self?.reconcileArtworkCache?(serverID, .reconcile(reservationBytes: 0, budgetBytesOverride: nil)) }
    }

    func disconnect() {
        pendingAutomaticDownloadsEnabled = nil
        reconciliationGeneration += 1
        deferredPreparations = []
        authorizedTaskGenerations = [:]
        reconciled = false
        cacheWrites.values.forEach { client?.abortStreamingCache(writeId: $0.identifier) }
        cacheWrites = [:]
        protectedPlayback = [:]
        pendingTracks = []
        prepareOperations.values.forEach { $0.cancel() }
        prepareOperations = [:]
        queuePrepareOperation?.cancel()
        queuePrepareOperation = nil
        cancelRetry()
        removedTaskIdentifiers.formUnion(knownTaskIdentifiers.keys)
        let generation = reconciliationGeneration
        session.getAllTasks { [weak self] tasks in
            Task { @MainActor [weak self] in
                guard let self, generation == reconciliationGeneration else { return }
                removedTaskIdentifiers.formUnion(tasks.map(\.taskIdentifier))
                tasks.forEach { $0.cancel() }
            }
        }
        client?.close()
        client = nil
        account = nil
        stateRefreshHandler?()
    }

    func removeAccountData() async -> Bool {
        guard !Task.isCancelled else { return false }
        pendingAutomaticDownloadsEnabled = nil
        reconciliationGeneration += 1
        deferredPreparations = []
        authorizedTaskGenerations = [:]
        let removalGeneration = reconciliationGeneration
        reconciled = false
        cacheWrites.values.forEach { client?.abortStreamingCache(writeId: $0.identifier) }
        cacheWrites = [:]
        protectedPlayback = [:]
        pendingTracks = []
        prepareOperations.values.forEach { $0.cancel() }
        prepareOperations = [:]
        queuePrepareOperation?.cancel()
        queuePrepareOperation = nil
        cancelRetry()
        let tasks = await allSessionTasks()
        // URLSession's continuation does not cooperate with task cancellation. Recovery,
        // reconnect, disconnect or a newer removal can supersede us while it is suspended.
        // On the main actor this guard and all mutations below run without another await.
        guard !Task.isCancelled,
              removalGeneration == reconciliationGeneration else { return false }
        removedTaskIdentifiers.formUnion(tasks.map(\.taskIdentifier))
        tasks.forEach { $0.cancel() }
        guard let client else {
            account = nil
            removeSecuredDownloadInbox()
            return true
        }
        let outcome = client.removeAccountData()
        guard outcome.errorKind == nil else { return false }
        removeSecuredDownloadInbox()
        client.close()
        self.client = nil
        account = nil
        stateRefreshHandler?()
        return true
    }

    func handleBackgroundSessionEvents() async {
        await withCheckedContinuation { continuation in
            if backgroundEventsFinishedBeforeWait {
                backgroundEventsFinishedBeforeWait = false
                continuation.resume()
                return
            }
            precondition(backgroundEventsContinuation == nil)
            backgroundEventsContinuation = continuation
            _ = session
        }
    }

    func closeNetworkAccessForTesting() {
        client?.closeNetworkAccess()
    }

    private func finishReconciliation(tasks: [URLSessionTask]) {
        guard let client else { return }
        for task in tasks { knownTaskIdentifiers[task.taskIdentifier] = task.taskDescription }
        let identifiers = tasks.compactMap(\.taskDescription)
        let outcome = client.reconcile(
            outstandingTaskIdentifiers: identifiers,
            credentialGeneration: credentialGeneration
        )
        guard outcome.errorKind == nil else {
            pendingTracks.forEach { publish(.failed, for: $0.id) }
            pendingTracks = []
            return
        }
        let cancel = Set(outcome.taskIdentifiersToCancel)
        let removed = tasks.filter { task in
            guard let description = task.taskDescription else { return true }
            return cancel.contains(description)
        }
        removedTaskIdentifiers.formUnion(removed.map(\.taskIdentifier))
        removed.forEach { $0.cancel() }
        reconciled = true
        updatePlaybackProtection()
        // Apply a queued preference before membership can enqueue or schedule automatic work.
        // Core still owns retention; suppress only new enqueueing until the inbox is reconciled.
        guard flushPendingAutomaticDownloadsSetting(allowNewDownloads: false, scheduleDownloads: false) else {
            pendingTracks.forEach { publish(.failed, for: $0.id) }
            pendingTracks = []
            stateRefreshHandler?()
            return
        }
        refreshArtworkBudget()
        // Remove orphaned secured deliveries before new membership can enqueue work.
        processSecuredDownloads()
        reconcileMyLibrary()
        probe("reconciled outstanding=\(identifiers.isEmpty ? "none" : identifiers.joined(separator: ",")) cancelled=\(cancel.count)")
        stateRefreshHandler?()
        let queued = pendingTracks
        pendingTracks = []
        queued.forEach(requestDownload)
        beginRemoteValidation()
    }

    private func beginRemoteValidation() {
        guard reconciled, !identityRejected else { return }
        validateRemoteAccess { [weak self] valid in
            guard let self, valid, !identityRejected else { return }
            remoteEligible = true
            // Register every already-secured receipt before opening any asynchronous window
            // in which membership changes or explicit requests could prepare a replacement.
            processSecuredDownloads()
            let generation = reconciliationGeneration
            session.getAllTasks { [weak self] tasks in
                Task { @MainActor [weak self] in
                    guard let self, generation == reconciliationGeneration, remoteEligible, !identityRejected else { return }
                    let deliver = { @MainActor [weak self] in
                        guard let self, generation == reconciliationGeneration, remoteEligible, !identityRejected else { return }
                        for task in tasks where !removedTaskIdentifiers.contains(task.taskIdentifier)
                            && (task.taskDescription.map { self.client?.ownsDownloadTask(downloadIdentifier: $0) == true } ?? false) {
                            if task.state == .suspended { task.resume() }
                        }
                        processSecuredDownloads()
                        resumePendingDownloads()
                    }
                    if let remoteTaskEnumerationDelivery { remoteTaskEnumerationDelivery(deliver) }
                    else { deliver() }
                }
            }
        }
    }

    private func resumePendingDownloads() {
        guard remoteEligible, !identityRejected, validatingDeliveries.isEmpty else { return }
        let prepared = deferredPreparations
        deferredPreparations = []
        for value in prepared where value.generation == reconciliationGeneration {
            handlePreparationOutcome(value.outcome, fallbackItemID: value.itemID)
        }
        let queued = pendingTracks
        pendingTracks = []
        queued.forEach(requestDownload)
        scheduleNextDownload()
    }

    private func handlePreparationOutcome(
        _ outcome: AppleDownloadPreparationOutcomeDto,
        fallbackItemID: DulcetProviderItemID?
    ) {
        guard remoteEligible, !identityRejected else { return }
        if outcome.errorKind == "serverIdentityChanged" { rejectServerIdentity(); return }
        // Preparation can have started before a restored receipt registered its validator.
        // Retain its result until the receipts commit, then reread the durable row in startTask.
        if outcome.prepared != nil, !validatingDeliveries.isEmpty {
            deferredPreparations.append((outcome, fallbackItemID, reconciliationGeneration))
            return
        }
        if let prepared = outcome.prepared,
           let url = URL(string: prepared.url),
           let account {
            var request = URLRequest(url: url)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            prepared.hostHeader.map { request.setValue($0, forHTTPHeaderField: "Host") }
            startTask(
                prepared: prepared,
                itemID: DulcetProviderItemID(
                    providerInstanceID: account.providerInstanceID,
                    rawID: prepared.rawId
                ),
                request: request
            )
            return
        }
        if let fallbackItemID {
            publish(
                outcome.terminalState.flatMap(DulcetDownloadState.init(rawValue:))
                    ?? (outcome.errorKind == nil ? .queued : .failed),
                for: fallbackItemID
            )
        }
        scheduleRetry(at: outcome.retryNotBeforeWallClock?.int64Value)
    }

    private func scheduleNextDownload() {
        guard reconciled, remoteEligible, !identityRejected, validatingDeliveries.isEmpty,
              pendingAutomaticDownloadsEnabled == nil, queuePrepareOperation == nil, let client else { return }
        let generation = reconciliationGeneration
        let operation = client.startPrepareNextDownload(
            wallClockMilliseconds: Date().downloadWallClockMilliseconds,
            diskBudgetBytes: diskBudgetBytes,
            unknownLengthReservationBytes: unknownLengthReservationBytes
        ) { [weak self] outcome in
            Task { @MainActor [weak self] in
                guard let self, generation == reconciliationGeneration else { return }
                queuePrepareOperation = nil
                handlePreparationOutcome(outcome, fallbackItemID: nil)
            }
        }
        queuePrepareOperation = operation
    }

    private func scheduleRetry(at boundary: Int64?) {
        guard let boundary, boundary < Int64.max else { return }
        if let current = retryNotBeforeWallClock, current <= boundary { return }
        cancelRetry()
        retryNotBeforeWallClock = boundary
        let delay = max(0, boundary - Date().downloadWallClockMilliseconds)
        retryTask = Task { @MainActor [weak self] in
            if delay > 0 {
                try? await Task.sleep(for: .milliseconds(delay))
            } else {
                await Task.yield()
            }
            guard !Task.isCancelled, let self else { return }
            retryTask = nil
            retryNotBeforeWallClock = nil
            if remoteEligible { scheduleNextDownload() } else { beginRemoteValidation() }
        }
    }

    private func cancelRetry() {
        retryTask?.cancel()
        retryTask = nil
        retryNotBeforeWallClock = nil
    }

    private func startTask(
        prepared: ApplePreparedDownloadDto,
        itemID: DulcetProviderItemID,
        request: URLRequest
    ) {
        guard remoteEligible, !identityRejected, let client else { return }
        // A secured OS delivery may have promoted while preparation was suspended in identity
        // validation or URL resolution. Its committed bytes win over the stale prepared URL.
        let current = client.status(rawId: itemID.rawID)
        if current.errorKind == nil, ["downloaded", "stale"].contains(current.state) {
            publish(DulcetDownloadState(rawValue: current.state) ?? .downloaded, for: itemID)
            scheduleNextDownload()
            return
        }
        let resume = client.resumeData(
            downloadIdentifier: prepared.downloadIdentifier,
            wallClockMilliseconds: Date().downloadWallClockMilliseconds
        )
        let task: URLSessionDownloadTask
        if resume.errorKind == nil, let data = resume.data as Data? {
            task = session.downloadTask(withResumeData: data)
            resumedTaskIdentifiers.insert(task.taskIdentifier)
        } else {
            task = session.downloadTask(with: request)
        }
        task.taskDescription = prepared.downloadIdentifier
        knownTaskIdentifiers[task.taskIdentifier] = prepared.downloadIdentifier
        publish(.downloading, for: itemID)
        authorizedTaskGenerations[task.taskIdentifier] = reconciliationGeneration
        task.resume()
        probe("task-started download=\(prepared.downloadIdentifier) item=\(itemID.rawID)")
    }

    private func handleCompletedDownload(
        task: URLSessionDownloadTask,
        location: URL
    ) {
        if removedTaskIdentifiers.contains(task.taskIdentifier) {
            try? FileManager.default.removeItem(at: location)
            return
        }
        let authorizedGeneration = authorizedTaskGenerations.removeValue(forKey: task.taskIdentifier)
        guard let secured = secureDownloadedFile(task: task, location: location) else { return }
        probe(
            "delivered download=\(task.taskDescription ?? "none") "
                + "status=\(secured.statusCode.map(String.init) ?? "none") bytes=\(secured.deliveredFileLength)"
        )
        completedTaskIdentifiers.insert(task.taskIdentifier)
        let deliver = { @MainActor [weak self] in
            _ = self?.processSecuredDownload(secured, authorizedGeneration: authorizedGeneration)
        }
        if let completedDownloadDelivery { completedDownloadDelivery(deliver) }
        else { deliver() }
    }

    @discardableResult
    private func processSecuredDownload(_ secured: DulcetSecuredDownload, authorizedGeneration: Int? = nil) -> Bool {
        guard UUID(uuidString: secured.identifier) != nil, let client else { return false }
        guard let identifier = secured.downloadIdentifier else { return removeSecuredDownload(secured) }
        // Orphan cleanup is local and cannot promote anything, so it remains available offline.
        if client.fileTarget(downloadIdentifier: identifier).errorKind == "unknownDownload" {
            return removeSecuredDownload(secured)
        }
        guard !identityRejected, secured.identityRejected != true else { return false }
        if let authorizedGeneration, authorizedGeneration != reconciliationGeneration { return false }
        // Finishing an already-authorized transfer is local work even when new contact is
        // closed. Restored tasks and inbox metadata never manufacture this in-memory proof.
        if authorizedGeneration == reconciliationGeneration,
           client.ownsDownloadTask(downloadIdentifier: identifier) {
            let promoted = promoteSecuredDownload(secured)
            if promoted { resumePendingDownloads() }
            return promoted
        }
        guard remoteEligible, !validatingDeliveries.contains(secured.identifier) else { return false }
        validatingDeliveries.insert(secured.identifier)
        validateRemoteAccess { [weak self] valid in
            guard let self else { return }
            let generation = reconciliationGeneration
            let deliver = { @MainActor [weak self] in
                guard let self, generation == reconciliationGeneration else { return }
                validatingDeliveries.remove(secured.identifier)
                guard valid, !identityRejected else { return }
                _ = promoteSecuredDownload(secured)
                resumePendingDownloads()
            }
            if let securedDownloadValidationDelivery { securedDownloadValidationDelivery(identifier, deliver) }
            else { deliver() }
        }
        return false
    }

    private func promoteSecuredDownload(_ secured: DulcetSecuredDownload) -> Bool {
        guard UUID(uuidString: secured.identifier) != nil, let client else { return false }
        guard let downloadIdentifier = secured.downloadIdentifier else {
            return removeSecuredDownload(secured)
        }
        let target = client.fileTarget(downloadIdentifier: downloadIdentifier)
        if target.errorKind == "unknownDownload" {
            // The durable row was removed (or belonged to a purged media binding). Only
            // this secured delivery is unowned; persistence failures remain retryable.
            return removeSecuredDownload(secured)
        }
        guard target.errorKind == nil,
              let temporaryFilePath = target.temporaryFilePath,
              let rawID = target.rawId,
              let account else { return false }
        let itemID = DulcetProviderItemID(
            providerInstanceID: account.providerInstanceID,
            rawID: rawID
        )
        let targetURL = URL(fileURLWithPath: temporaryFilePath)
        do {
            try FileManager.default.createDirectory(
                at: targetURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            if FileManager.default.fileExists(atPath: targetURL.path) {
                try FileManager.default.removeItem(at: targetURL)
            }
            try FileManager.default.moveItem(at: secured.payloadURL(in: downloadInboxURL), to: targetURL)
        } catch {
            return false
        }
        guard let statusCode = secured.statusCode else {
            let outcome = client.recordFailure(
                downloadIdentifier: downloadIdentifier,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
            try? FileManager.default.removeItem(at: targetURL)
            removeSecuredMetadata(secured)
            publish(.interrupted, for: itemID)
            scheduleRetry(at: outcome.retryNotBeforeWallClock?.int64Value)
            return true
        }
        guard (200 ... 299).contains(statusCode) else {
            let outcome = client.finishDownload(
                downloadIdentifier: downloadIdentifier,
                statusCode: Int32(statusCode),
                contentType: secured.contentType,
                contentLength: -1,
                retryAfterMilliseconds: secured.retryAfterMilliseconds,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
            try? FileManager.default.removeItem(at: targetURL)
            removeSecuredMetadata(secured)
            publish(DulcetDownloadState(rawValue: outcome.state) ?? .interrupted, for: itemID)
            scheduleNextDownload()
            return true
        }
        let contentLength = secured.exactContentLength
        if statusCode == 206, contentLength == nil {
            let outcome = client.recordFailure(
                downloadIdentifier: downloadIdentifier,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
            try? FileManager.default.removeItem(at: targetURL)
            removeSecuredMetadata(secured)
            publish(.interrupted, for: itemID)
            scheduleRetry(at: outcome.retryNotBeforeWallClock?.int64Value)
            return true
        }
        let outcome = client.finishDownload(
            downloadIdentifier: downloadIdentifier,
            statusCode: Int32(statusCode),
            contentType: secured.contentType,
            contentLength: contentLength ?? -1,
            retryAfterMilliseconds: secured.retryAfterMilliseconds,
            wallClockMilliseconds: Date().downloadWallClockMilliseconds
        )
        removeSecuredMetadata(secured)
        #if DEBUG
        probePromotion(downloadIdentifier: downloadIdentifier, rawID: rawID, state: outcome.state)
        #endif
        publish(DulcetDownloadState(rawValue: outcome.state) ?? .failed, for: itemID)
        scheduleNextDownload()
        return true
    }

    private func handleTaskCompletion(task: URLSessionTask, error: Error?) {
        authorizedTaskGenerations[task.taskIdentifier] = nil
        redirectsByTaskIdentifier[task.taskIdentifier] = nil
        knownTaskIdentifiers[task.taskIdentifier] = nil
        if removedTaskIdentifiers.remove(task.taskIdentifier) != nil {
            resumedTaskIdentifiers.remove(task.taskIdentifier)
            return
        }
        if completedTaskIdentifiers.remove(task.taskIdentifier) != nil {
            resumedTaskIdentifiers.remove(task.taskIdentifier)
            return
        }
        guard let downloadIdentifier = task.taskDescription,
              let client else { return }
        probe("task-failed download=\(downloadIdentifier) error=\(error.map { "\(($0 as NSError).domain):\(($0 as NSError).code)" } ?? "none")")
        let target = client.fileTarget(downloadIdentifier: downloadIdentifier)
        guard target.errorKind == nil,
              let rawID = target.rawId,
              let account else { return }
        let itemID = DulcetProviderItemID(
            providerInstanceID: account.providerInstanceID,
            rawID: rawID
        )
        let rejectedResumeData = resumedTaskIdentifiers.remove(task.taskIdentifier) != nil
        if rejectedResumeData {
            _ = client.rejectResumeData(downloadIdentifier: downloadIdentifier)
            publish(.queued, for: itemID)
            scheduleNextDownload()
        } else if let resumeData = (error as NSError?)?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data,
           !resumeData.isEmpty {
            let outcome = client.recordResumeData(
                downloadIdentifier: downloadIdentifier,
                data: resumeData,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
            publish(.interrupted, for: itemID)
            scheduleRetry(at: outcome.retryNotBeforeWallClock?.int64Value)
        } else {
            let outcome = client.recordFailure(
                downloadIdentifier: downloadIdentifier,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
            publish(.interrupted, for: itemID)
            scheduleRetry(at: outcome.retryNotBeforeWallClock?.int64Value)
        }
    }

    private var downloadInboxURL: URL {
        downloadRootURL.appendingPathComponent(".incoming", isDirectory: true)
    }

    private func secureDownloadedFile(
        task: URLSessionDownloadTask,
        location: URL
    ) -> DulcetSecuredDownload? {
        let identifier = UUID().uuidString
        let response = task.response as? HTTPURLResponse
        let secured = DulcetSecuredDownload(
            identifier: identifier,
            downloadIdentifier: task.taskDescription,
            statusCode: response?.statusCode,
            contentType: response?.value(forHTTPHeaderField: "Content-Type"),
            contentLength: response?.value(forHTTPHeaderField: "Content-Length"),
            contentRange: response?.value(forHTTPHeaderField: "Content-Range"),
            retryAfter: response?.value(forHTTPHeaderField: "Retry-After"),
            deliveredFileLength: (
                try? FileManager.default.attributesOfItem(atPath: location.path)[.size] as? NSNumber
            )?.int64Value ?? -1
        )
        do {
            try FileManager.default.createDirectory(
                at: downloadInboxURL,
                withIntermediateDirectories: true
            )
            let payload = secured.payloadURL(in: downloadInboxURL)
            try JSONEncoder().encode(secured).write(
                to: secured.metadataURL(in: downloadInboxURL),
                options: .atomic
            )
            do {
                try FileManager.default.moveItem(at: location, to: payload)
            } catch {
                removeSecuredMetadata(secured)
                throw error
            }
            return secured
        } catch {
            return nil
        }
    }

    func processSecuredDownloads() {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: downloadInboxURL,
            includingPropertiesForKeys: nil
        ) else { return }
        for metadataURL in files where metadataURL.pathExtension == "json" {
            guard let data = try? Data(contentsOf: metadataURL),
                  let secured = try? JSONDecoder().decode(DulcetSecuredDownload.self, from: data),
                  UUID(uuidString: secured.identifier) != nil,
                  secured.metadataURL(in: downloadInboxURL).lastPathComponent == metadataURL.lastPathComponent else {
                continue
            }
            guard FileManager.default.fileExists(
                atPath: secured.payloadURL(in: downloadInboxURL).path
            ) else {
                removeSecuredMetadata(secured)
                continue
            }
            _ = processSecuredDownload(secured)
        }
    }

    private func markPendingDeliveriesRejected() {
        guard let files = try? FileManager.default.contentsOfDirectory(at: downloadInboxURL, includingPropertiesForKeys: nil) else { return }
        for file in files where file.pathExtension == "json" {
            guard let data = try? Data(contentsOf: file), var secured = try? JSONDecoder().decode(DulcetSecuredDownload.self, from: data),
                  let id = secured.downloadIdentifier, client?.ownsDownloadTask(downloadIdentifier: id) == true else { continue }
            secured.identityRejected = true
            if let marked = try? JSONEncoder().encode(secured) { try? marked.write(to: file, options: .atomic) }
        }
    }

    private func removeSecuredDownload(_ secured: DulcetSecuredDownload) -> Bool {
        guard UUID(uuidString: secured.identifier) != nil else { return false }
        do {
            let payload = secured.payloadURL(in: downloadInboxURL)
            if FileManager.default.fileExists(atPath: payload.path) {
                try FileManager.default.removeItem(at: payload)
            }
            // Keep metadata if payload removal fails, so reconciliation can retry cleanup.
            try FileManager.default.removeItem(at: secured.metadataURL(in: downloadInboxURL))
            return true
        } catch {
            return false
        }
    }

    private func removeSecuredMetadata(_ secured: DulcetSecuredDownload) {
        try? FileManager.default.removeItem(at: secured.metadataURL(in: downloadInboxURL))
    }

    private func removeSecuredDownloadInbox() {
        try? FileManager.default.removeItem(at: downloadInboxURL)
    }

    private func allSessionTasks() async -> [URLSessionTask] {
        await withCheckedContinuation { continuation in
            session.getAllTasks { continuation.resume(returning: $0) }
        }
    }

    private func publish(_ state: DulcetDownloadState, for id: DulcetProviderItemID) {
        if state == .downloaded { refreshArtworkBudget() }
        statusHandler?(id, state)
        stateRefreshHandler?()
    }

    /// One line in the handoff proof's marker file, in a DEBUG build with the proof's namespace
    /// active; nothing otherwise.
    private func probe(_ event: @autoclosure () -> String) {
        #if DEBUG
        DulcetDownloadHandoffProbe.record(event())
        #endif
    }

    #if DEBUG
    /// The promoted file as the core will play it: its length and digest, so the proof can hold
    /// them to the bytes the server sends for the same track.
    private func probePromotion(downloadIdentifier: String, rawID: String, state: String) {
        guard DulcetDownloadHandoffProbe.namespace != nil else { return }
        var bytes = "none"
        var digest = "none"
        if let plan = client?.localPlaybackPlan(rawId: rawID).plan,
           let data = FileManager.default.contents(atPath: plan.filePath) {
            bytes = String(data.count)
            digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        }
        // The durable row as the core reads it back, not the promotion's own answer.
        let row = client?.status(rawId: rawID).state ?? "none"
        DulcetDownloadHandoffProbe.record(
            "promoted download=\(downloadIdentifier) item=\(rawID) state=\(state) row=\(row) bytes=\(bytes) sha256=\(digest)"
        )
    }

    /// The background session's own tasks, as the system reports them: each task's download
    /// identifier and state, for the proof's outstanding-download witness.
    func debugSessionTasks() async -> [(download: String, state: URLSessionTask.State)] {
        // Before an account is configured the session is not made here: making it would change
        // when the system's events for it are delivered, which is what the proof observes.
        guard client != nil else { return [] }
        return await allSessionTasks().map { ($0.taskDescription ?? "none", $0.state) }
    }
    #endif
}

extension DulcetCoreDownloadController: URLSessionDownloadDelegate, URLSessionTaskDelegate {
    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        MainActor.assumeIsolated { [weak self] in
            self?.handleCompletedDownload(task: downloadTask, location: location)
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        MainActor.assumeIsolated { [weak self] in
            self?.handleTaskCompletion(task: task, error: error)
        }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        MainActor.assumeIsolated { [weak self] in
            guard let self else { return }
            probe("finished-events")
            if let continuation = backgroundEventsContinuation {
                backgroundEventsContinuation = nil
                continuation.resume()
            } else {
                backgroundEventsFinishedBeforeWait = true
            }
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        let completion = DulcetUncheckedSendable(completionHandler)
        MainActor.assumeIsolated { [weak self] in
            guard let self,
                  let sourceURL = response.url,
                  let proposedURL = request.url,
                  let client else {
                completion.value(nil)
                return
            }
            let redirects = redirectsByTaskIdentifier[task.taskIdentifier, default: 0]
            let decision = client.evaluateRedirect(
                sourceUrl: sourceURL.absoluteString,
                proposedUrl: proposedURL.absoluteString,
                redirectsAlreadyFollowed: Int32(redirects)
            )
            switch decision.kind {
            case "preserve":
                redirectsByTaskIdentifier[task.taskIdentifier] = redirects + 1
                completion.value(request)
            case "strip":
                let names = Set(decision.queryItemNamesToStrip)
                guard let sanitized = request.removingDownloadQueryItems(named: names) else {
                    completion.value(nil)
                    return
                }
                redirectsByTaskIdentifier[task.taskIdentifier] = redirects + 1
                completion.value(sanitized)
            default:
                completion.value(nil)
            }
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
            completionHandler(.performDefaultHandling, nil)
        } else {
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }
}

private struct DulcetSecuredDownload: Codable {
    let identifier: String
    let downloadIdentifier: String?
    let statusCode: Int?
    let contentType: String?
    let contentLength: String?
    let contentRange: String?
    let retryAfter: String?
    let deliveredFileLength: Int64
    var identityRejected: Bool? = nil

    func payloadURL(in inbox: URL) -> URL {
        inbox.appendingPathComponent("\(identifier).download")
    }

    func metadataURL(in inbox: URL) -> URL {
        inbox.appendingPathComponent("\(identifier).json")
    }

    var exactContentLength: Int64? {
        parsedDownloadExactContentLength(
            statusCode: statusCode ?? -1,
            contentRange: contentRange,
            contentLength: contentLength,
            deliveredFileLength: deliveredFileLength
        )
    }

    var retryAfterMilliseconds: Int64 {
        guard let retryAfter,
              let seconds = Int64(retryAfter.trimmingCharacters(in: .whitespacesAndNewlines)),
              seconds >= 0 else { return -1 }
        let multiplied = seconds.multipliedReportingOverflow(by: 1_000)
        return multiplied.overflow ? -1 : multiplied.partialValue
    }
}

private struct DulcetUncheckedSendable<Value>: @unchecked Sendable {
    let value: Value

    init(_ value: Value) {
        self.value = value
    }
}

private extension URLRequest {
    func removingDownloadQueryItems(named names: Set<String>) -> URLRequest? {
        guard let url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        components.queryItems = components.queryItems?.filter { !names.contains($0.name) }
        guard let sanitizedURL = components.url else { return nil }
        var copy = self
        copy.url = sanitizedURL
        return copy
    }
}

extension HTTPURLResponse {
    func downloadExactContentLength(deliveredFileLength: Int64) -> Int64? {
        parsedDownloadExactContentLength(
            statusCode: statusCode,
            contentRange: value(forHTTPHeaderField: "Content-Range"),
            contentLength: value(forHTTPHeaderField: "Content-Length"),
            deliveredFileLength: deliveredFileLength
        )
    }
}

private func parsedDownloadExactContentLength(
    statusCode: Int,
    contentRange: String?,
    contentLength: String?,
    deliveredFileLength: Int64
) -> Int64? {
    if statusCode != 206 {
        guard let contentLength,
              let length = Int64(contentLength.trimmingCharacters(in: .whitespacesAndNewlines)),
              length >= 0 else { return nil }
        return length
    }

    guard let contentRange else { return nil }
    let tokens = contentRange
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .split(whereSeparator: \Character.isWhitespace)
    guard tokens.count == 2, tokens[0].lowercased() == "bytes" else { return nil }
    let rangeAndTotal = tokens[1].split(separator: "/", omittingEmptySubsequences: false)
    guard rangeAndTotal.count == 2,
          rangeAndTotal[1] != "*",
          let total = Int64(rangeAndTotal[1]),
          total == deliveredFileLength else { return nil }
    let bounds = rangeAndTotal[0].split(separator: "-", omittingEmptySubsequences: false)
    guard bounds.count == 2,
          let start = Int64(bounds[0]),
          let end = Int64(bounds[1]),
          start >= 0,
          end >= start,
          end < total else { return nil }
    if let contentLength {
        guard let deliveredRangeLength = Int64(
            contentLength.trimmingCharacters(in: .whitespacesAndNewlines)
        ), deliveredRangeLength == end - start + 1 else { return nil }
    }
    return total
}

private extension Date {
    var downloadWallClockMilliseconds: Int64 {
        Int64((timeIntervalSince1970 * 1_000).rounded())
    }
}

private extension Duration {
    var downloadMilliseconds: Int64 {
        let value = components
        let fractional = Double(value.attoseconds) / 1_000_000_000_000_000
        return max(0, Int64((Double(value.seconds) * 1_000 + fractional).rounded()))
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

    init?(coreName: String) {
        switch coreName {
        case "Mp3": self = .mp3
        case "Mp4": self = .mp4
        case "Wav": self = .wav
        case "Flac": self = .flac
        case "Ogg": self = .ogg
        case "AdtsAac": self = .adtsAAC
        default: return nil
        }
    }
}

#if DEBUG
/// DEBUG ONLY: the instrument of the killed-process download handoff proof (spec §14.5,
/// `docs/download-background-blockers.md`).
///
/// A launch argument names a namespace. The app keeps it in its defaults for a quarter of an hour, so a
/// launch nobody passes arguments to -- the system's own relaunch for the background session
/// included -- still has it. While it is active the app keeps its background session, database and
/// downloads apart under that namespace, and appends each step of the download handoff to a marker
/// file the proof reads: the step, the process, an opaque download identifier, an item identifier,
/// byte counts and digests. Never a URL, a query or a credential.
enum DulcetDownloadHandoffProbe {
    static let launchArgument = "-dulcet-debug-download-handoff"
    static let clearArgument = "-dulcet-debug-download-handoff-clear"
    static let dumpArgument = "-dulcet-debug-download-handoff-dump"
    private static let defaultsKey = "com.legitimateapps.dulcet.debug.download-handoff"
    private static let lifetime: TimeInterval = 15 * 60

    /// Resolved once per process, on first use, so the session identifier cannot change under a
    /// running app when the quarter hour runs out.
    static let namespace: String? = resolve(
        arguments: ProcessInfo.processInfo.arguments,
        defaults: .standard,
        now: Date(),
        bundlePath: bundleCopyPath
    )

    /// macOS copies share preferences, so bind the probe to the launched copy. On iOS the
    /// preferences are already isolated by the app's data container. A literal bundle path
    /// there rejected an OS relaunch after an Xcode launch (hosted run 37927554714), making
    /// the replacement select the ordinary session instead of the outstanding probe session.
    private static var bundleCopyPath: String? {
        #if os(macOS)
        Bundle.main.bundlePath
        #else
        nil
        #endif
    }

    /// `nil` means the app's data container supplies the preferences isolation (iOS).
    /// A path keeps separately launched macOS copies from taking one another's namespace.
    static func resolve(arguments: [String], defaults: UserDefaults, now: Date, bundlePath: String?) -> String? {
        var dumpedNamespace: String?
        if let index = arguments.firstIndex(of: dumpArgument),
           arguments.indices.contains(index + 1), isValid(arguments[index + 1]) {
            dumpedNamespace = arguments[index + 1]
            logMarkers(namespace: arguments[index + 1])
        }
        if arguments.contains(clearArgument) {
            // Cleanup is the last launch even after a failed proof. Preserve the previous
            // process's trail in the xcresult's app/system log before removing its namespace.
            if let previous = defaults.dictionary(forKey: defaultsKey)?["namespace"] as? String,
               isValid(previous), previous != dumpedNamespace {
                logMarkers(namespace: previous)
            }
            defaults.removeObject(forKey: defaultsKey)
        }
        if let index = arguments.firstIndex(of: launchArgument),
           arguments.indices.contains(index + 1),
           isValid(arguments[index + 1]) {
            defaults.set(
                [
                    "namespace": arguments[index + 1],
                    "expires": now.addingTimeInterval(lifetime).timeIntervalSince1970,
                    "bundle": bundlePath ?? "app-container",
                ],
                forKey: defaultsKey
            )
        }
        let stored = defaults.dictionary(forKey: defaultsKey)
        // This runs even when the probe is inactive: otherwise the replacement that selected
        // the ordinary session leaves no evidence of why it rejected the probe's namespace.
        // Booleans only; bundle paths, defaults values and launch arguments are never logged.
        let harness = arguments.contains(launchArgument)
        let cleared = arguments.contains(clearArgument)
        let bundleMatches = bundlePath.map { stored?["bundle"] as? String == $0 } ?? true
        func diagnostic(_ result: String) {
            NSLog("%@", "DULCET HANDOFF resolution harness=\(harness) cleared=\(cleared)"
                  + " stored=\(stored != nil) bundle-match=\(bundleMatches) result=\(result)")
        }
        guard let stored,
              let namespace = stored["namespace"] as? String,
              let expires = stored["expires"] as? Double,
              isValid(namespace) else {
            diagnostic(stored == nil ? "missing" : "invalid-record")
            return nil
        }
        guard bundleMatches else {
            diagnostic("bundle-mismatch")
            return nil
        }
        guard now.timeIntervalSince1970 < expires else {
            diagnostic("expired")
            defaults.removeObject(forKey: defaultsKey)
            return nil
        }
        diagnostic("active")
        return namespace
    }

    /// The marker writer admits only public-safe proof fields (never URLs or credentials).
    /// Log the trail independently of `namespace`, which cleanup is about to clear and which
    /// a failed replacement may never have resolved in the first place.
    private static func logMarkers(namespace: String) {
        guard let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { return }
        let file = support.appendingPathComponent("Dulcet/DebugDownloadHandoff/\(namespace)/markers.log")
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return }
        for line in text.split(separator: "\n") {
            NSLog("%@", "DULCET HANDOFF marker \(line)")
        }
    }

    static func isValid(_ namespace: String) -> Bool {
        (1 ... 48).contains(namespace.count)
            && namespace.unicodeScalars.allSatisfy {
                CharacterSet.alphanumerics.contains($0) && $0.isASCII || $0 == "-"
            }
    }

    /// `Application Support/Dulcet/DebugDownloadHandoff/<namespace>`, or nil when inactive.
    static var directoryURL: URL? {
        guard let namespace,
              let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        else { return nil }
        return support
            .appendingPathComponent("Dulcet", isDirectory: true)
            .appendingPathComponent("DebugDownloadHandoff", isDirectory: true)
            .appendingPathComponent(namespace, isDirectory: true)
    }

    static var markersURL: URL? {
        directoryURL?.appendingPathComponent("markers.log")
    }

    /// Appends `<wall-clock ms> pid=<pid> <event>`; does nothing when inactive.
    static func record(_ event: String) {
        guard let markersURL, let directoryURL else { return }
        let milliseconds = Int64((Date().timeIntervalSince1970 * 1_000).rounded())
        let line = Data("\(milliseconds) pid=\(getpid()) \(event)\n".utf8)
        try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        if let handle = try? FileHandle(forWritingTo: markersURL) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: line)
        } else {
            try? line.write(to: markersURL, options: .atomic)
        }
    }

    /// Every line recorded so far.
    static func markers() -> [String] {
        guard let markersURL, let text = try? String(contentsOf: markersURL, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").map(String.init)
    }
}
#endif
#endif
