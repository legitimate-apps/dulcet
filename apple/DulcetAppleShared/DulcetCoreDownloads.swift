#if os(macOS)
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
    private var prepareOperations: [DulcetProviderItemID: any AppleDownloadOperation] = [:]
    private var pendingTracks: [DulcetTrack] = []
    private var completedTaskIdentifiers: Set<Int> = []
    private var resumedTaskIdentifiers: Set<Int> = []
    private var redirectsByTaskIdentifier: [Int: Int] = [:]
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
        let root = applicationSupport
            .appendingPathComponent("Dulcet", isDirectory: true)
            .appendingPathComponent("Downloads", isDirectory: true)
        let bundleIdentifier = Bundle.main.bundleIdentifier ?? "com.legitimateapps.dulcet"
        let configuration = URLSessionConfiguration.background(
            withIdentifier: "\(bundleIdentifier).downloads.background"
        )
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        return Self(
            databaseName: "dulcet.db",
            downloadRootURL: root,
            sessionConfiguration: configuration
        )
    }

    func setStatusHandler(
        _ handler: @escaping @MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void
    ) {
        statusHandler = handler
    }

    func configure(account: DulcetPlaybackAccount) {
        reconciliationGeneration += 1
        let generation = reconciliationGeneration
        reconciled = false
        prepareOperations.values.forEach { $0.cancel() }
        prepareOperations = [:]
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
                guard let prepared = outcome.prepared,
                      let url = URL(string: prepared.url) else {
                    publish(
                        outcome.terminalState.flatMap(DulcetDownloadState.init(rawValue:))
                            ?? (outcome.errorKind == nil ? .queued : .failed),
                        for: track.id
                    )
                    return
                }
                var request = URLRequest(url: url)
                request.cachePolicy = .reloadIgnoringLocalCacheData
                prepared.hostHeader.map { request.setValue($0, forHTTPHeaderField: "Host") }
                startTask(
                    prepared: prepared,
                    itemID: track.id,
                    request: request
                )
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
        session.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
        client?.close()
        client = nil
        account = nil
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
        let queued = pendingTracks
        pendingTracks = []
        queued.forEach(requestDownload)
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
    }

    private func handleCompletedDownload(
        task: URLSessionDownloadTask,
        location: URL
    ) {
        guard let downloadIdentifier = task.taskDescription,
              let client else { return }
        let target = client.fileTarget(downloadIdentifier: downloadIdentifier)
        guard target.errorKind == nil,
              let temporaryFilePath = target.temporaryFilePath,
              let rawID = target.rawId,
              let account else { return }
        let itemID = DulcetProviderItemID(
            providerInstanceID: account.providerInstanceID,
            rawID: rawID
        )
        guard let response = task.response as? HTTPURLResponse else {
            _ = client.recordFailure(
                downloadIdentifier: downloadIdentifier,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
            publish(.interrupted, for: itemID)
            return
        }
        guard (200 ... 299).contains(response.statusCode) else {
            let outcome = client.finishDownload(
                downloadIdentifier: downloadIdentifier,
                statusCode: Int32(response.statusCode),
                contentType: response.value(forHTTPHeaderField: "Content-Type"),
                contentLength: -1,
                retryAfterMilliseconds: response.downloadRetryAfterMilliseconds,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
            publish(DulcetDownloadState(rawValue: outcome.state) ?? .interrupted, for: itemID)
            return
        }
        let targetURL = URL(fileURLWithPath: temporaryFilePath)
        do {
            if FileManager.default.fileExists(atPath: targetURL.path) {
                try FileManager.default.removeItem(at: targetURL)
            }
            try FileManager.default.moveItem(at: location, to: targetURL)
        } catch {
            _ = client.recordFailure(
                downloadIdentifier: downloadIdentifier,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
            publish(.interrupted, for: itemID)
            return
        }
        let contentLength = response.downloadExactContentLength ?? -1
        let outcome = client.finishDownload(
            downloadIdentifier: downloadIdentifier,
            statusCode: Int32(response.statusCode),
            contentType: response.value(forHTTPHeaderField: "Content-Type"),
            contentLength: contentLength,
            retryAfterMilliseconds: response.downloadRetryAfterMilliseconds,
            wallClockMilliseconds: Date().downloadWallClockMilliseconds
        )
        completedTaskIdentifiers.insert(task.taskIdentifier)
        publish(DulcetDownloadState(rawValue: outcome.state) ?? .failed, for: itemID)
    }

    private func handleTaskCompletion(task: URLSessionTask, error: Error?) {
        redirectsByTaskIdentifier[task.taskIdentifier] = nil
        if completedTaskIdentifiers.remove(task.taskIdentifier) != nil {
            resumedTaskIdentifiers.remove(task.taskIdentifier)
            return
        }
        guard let downloadIdentifier = task.taskDescription,
              let client else { return }
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
        }
        if rejectedResumeData {
            _ = client.recordFailure(
                downloadIdentifier: downloadIdentifier,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
        } else if let resumeData = (error as NSError?)?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data,
           !resumeData.isEmpty {
            _ = client.recordResumeData(
                downloadIdentifier: downloadIdentifier,
                data: resumeData,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
        } else {
            _ = client.recordFailure(
                downloadIdentifier: downloadIdentifier,
                wallClockMilliseconds: Date().downloadWallClockMilliseconds
            )
        }
        publish(.interrupted, for: itemID)
    }

    private func publish(_ state: DulcetDownloadState, for id: DulcetProviderItemID) {
        statusHandler?(id, state)
    }
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

private extension HTTPURLResponse {
    var downloadExactContentLength: Int64? {
        if statusCode == 206,
           let contentRange = value(forHTTPHeaderField: "Content-Range"),
           let totalToken = contentRange.split(separator: "/", omittingEmptySubsequences: false).last,
           totalToken != "*",
           let total = Int64(totalToken),
           total >= 0 {
            return total
        }
        guard let raw = value(forHTTPHeaderField: "Content-Length"),
              let length = Int64(raw),
              length >= 0 else { return nil }
        return length
    }

    var downloadRetryAfterMilliseconds: Int64 {
        guard let raw = value(forHTTPHeaderField: "Retry-After"),
              let seconds = Int64(raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              seconds >= 0 else { return -1 }
        return seconds.multipliedReportingOverflow(by: 1_000).partialValue
    }
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
#endif
