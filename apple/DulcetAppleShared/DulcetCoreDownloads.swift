#if os(macOS) || os(iOS)
#if DEBUG
import CryptoKit
#endif
import DulcetCore
import DulcetKit
import Foundation

@MainActor
final class DulcetCoreDownloadController: NSObject, DulcetDownloadControlling {
    private let databaseName: String
    private let downloadRootURL: URL
    private let sessionConfiguration: URLSessionConfiguration
    private let diskBudgetBytes: Int64
    private let unknownLengthReservationBytes: Int64
    private var account: DulcetPlaybackAccount?
    private var client: AppleDownloadClient?
    private var statusHandler:
        (@MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void)?
    private var stateRefreshHandler: (@MainActor () -> Void)?
    private var prepareOperations: [DulcetProviderItemID: any AppleDownloadOperation] = [:]
    private var pendingTracks: [DulcetTrack] = []
    private var completedTaskIdentifiers: Set<Int> = []
    private var resumedTaskIdentifiers: Set<Int> = []
    private var removedTaskIdentifiers: Set<Int> = []
    private var redirectsByTaskIdentifier: [Int: Int] = [:]
    private var queuePrepareOperation: (any AppleDownloadOperation)?
    private var retryTask: Task<Void, Never>?
    private var retryNotBeforeWallClock: Int64?
    private var backgroundEventsContinuation: CheckedContinuation<Void, Never>?
    private var backgroundEventsFinishedBeforeWait = false
    private var reconciliationGeneration = 0
    private var reconciled = false

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

    func setStatusHandler(
        _ handler: @escaping @MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void
    ) {
        statusHandler = handler
    }

    func setStateRefreshHandler(_ handler: @escaping @MainActor () -> Void) {
        stateRefreshHandler = handler
    }

    func configure(account: DulcetPlaybackAccount) {
        reconciliationGeneration += 1
        let generation = reconciliationGeneration
        reconciled = false
        prepareOperations.values.forEach { $0.cancel() }
        prepareOperations = [:]
        queuePrepareOperation?.cancel()
        queuePrepareOperation = nil
        cancelRetry()
        client?.close()
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
                finishReconciliation(tasks: tasks)
            }
        }
    }

    func requestDownload(_ track: DulcetTrack) {
        guard downloadsEnabled, track.availability == .playable else { return }
        guard let account, track.id.providerInstanceID == account.providerInstanceID else { return }
        guard reconciled else {
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
                guard let self else { return }
                prepareOperations[track.id] = nil
                handlePreparationOutcome(outcome, fallbackItemID: track.id)
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
            resource: resource
        )
    }

    func disconnect() {
        reconciliationGeneration += 1
        reconciled = false
        pendingTracks = []
        prepareOperations.values.forEach { $0.cancel() }
        prepareOperations = [:]
        queuePrepareOperation?.cancel()
        queuePrepareOperation = nil
        cancelRetry()
        session.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
        client?.close()
        client = nil
        account = nil
        stateRefreshHandler?()
    }

    func removeAccountData() async -> Bool {
        guard !Task.isCancelled else { return false }
        reconciliationGeneration += 1
        let removalGeneration = reconciliationGeneration
        reconciled = false
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
        tasks.filter { task in
            guard let description = task.taskDescription else { return true }
            return cancel.contains(description)
        }.forEach { $0.cancel() }
        reconciled = true
        probe("reconciled outstanding=\(identifiers.isEmpty ? "none" : identifiers.joined(separator: ",")) cancelled=\(cancel.count)")
        stateRefreshHandler?()
        processSecuredDownloads()
        let queued = pendingTracks
        pendingTracks = []
        queued.forEach(requestDownload)
        scheduleNextDownload()
    }

    private func handlePreparationOutcome(
        _ outcome: AppleDownloadPreparationOutcomeDto,
        fallbackItemID: DulcetProviderItemID?
    ) {
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
        guard reconciled, queuePrepareOperation == nil, let client else { return }
        let operation = client.startPrepareNextDownload(
            wallClockMilliseconds: Date().downloadWallClockMilliseconds,
            diskBudgetBytes: diskBudgetBytes,
            unknownLengthReservationBytes: unknownLengthReservationBytes
        ) { [weak self] outcome in
            Task { @MainActor [weak self] in
                guard let self else { return }
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
            scheduleNextDownload()
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
        guard let client else { return }
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
        publish(.downloading, for: itemID)
        task.resume()
        probe("task-started download=\(prepared.downloadIdentifier) item=\(itemID.rawID)")
    }

    private func handleCompletedDownload(
        task: URLSessionDownloadTask,
        location: URL
    ) {
        if removedTaskIdentifiers.remove(task.taskIdentifier) != nil {
            try? FileManager.default.removeItem(at: location)
            return
        }
        guard let secured = secureDownloadedFile(task: task, location: location) else { return }
        probe(
            "delivered download=\(task.taskDescription ?? "none") "
                + "status=\(secured.statusCode.map(String.init) ?? "none") bytes=\(secured.deliveredFileLength)"
        )
        completedTaskIdentifiers.insert(task.taskIdentifier)
        _ = processSecuredDownload(secured)
    }

    @discardableResult
    private func processSecuredDownload(_ secured: DulcetSecuredDownload) -> Bool {
        guard let downloadIdentifier = secured.downloadIdentifier,
              let client else { return false }
        let target = client.fileTarget(downloadIdentifier: downloadIdentifier)
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
        redirectsByTaskIdentifier[task.taskIdentifier] = nil
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

    private func processSecuredDownloads() {
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: downloadInboxURL,
            includingPropertiesForKeys: nil
        ) else { return }
        for metadataURL in files where metadataURL.pathExtension == "json" {
            guard let data = try? Data(contentsOf: metadataURL),
                  let secured = try? JSONDecoder().decode(DulcetSecuredDownload.self, from: data) else {
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
        statusHandler?(id, state)
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
    private static let defaultsKey = "com.legitimateapps.dulcet.debug.download-handoff"
    private static let lifetime: TimeInterval = 15 * 60

    /// Resolved once per process, on first use, so the session identifier cannot change under a
    /// running app when the quarter hour runs out.
    static let namespace: String? = resolve(
        arguments: ProcessInfo.processInfo.arguments,
        defaults: .standard,
        now: Date(),
        bundlePath: Bundle.main.bundlePath
    )

    /// The stored namespace names the app bundle that stored it. On a Mac every copy of an app
    /// shares its defaults, and only the copy the proof launched may take the namespace up.
    static func resolve(arguments: [String], defaults: UserDefaults, now: Date, bundlePath: String) -> String? {
        if arguments.contains(clearArgument) {
            defaults.removeObject(forKey: defaultsKey)
        }
        if let index = arguments.firstIndex(of: launchArgument),
           arguments.indices.contains(index + 1),
           isValid(arguments[index + 1]) {
            defaults.set(
                [
                    "namespace": arguments[index + 1],
                    "expires": now.addingTimeInterval(lifetime).timeIntervalSince1970,
                    "bundle": bundlePath,
                ],
                forKey: defaultsKey
            )
        }
        guard let stored = defaults.dictionary(forKey: defaultsKey),
              let namespace = stored["namespace"] as? String,
              let expires = stored["expires"] as? Double,
              stored["bundle"] as? String == bundlePath,
              isValid(namespace) else { return nil }
        guard now.timeIntervalSince1970 < expires else {
            defaults.removeObject(forKey: defaultsKey)
            return nil
        }
        return namespace
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
