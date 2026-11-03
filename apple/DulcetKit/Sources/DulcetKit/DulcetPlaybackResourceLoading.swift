import AVFoundation
import Foundation
import UniformTypeIdentifiers

public struct DulcetPlaybackByteRange: Equatable, Sendable {
    public let start: Int64
    public let endInclusive: Int64

    public init(start: Int64, endInclusive: Int64) {
        precondition(start >= 0 && endInclusive >= start)
        self.start = start
        self.endInclusive = endInclusive
    }

    public var length: Int64 { endInclusive - start + 1 }
}

public struct DulcetPlaybackResourceLoadRequest: Equatable, Sendable {
    public let range: DulcetPlaybackByteRange
    public let requiresAudioSignature: Bool

    public init(range: DulcetPlaybackByteRange, requiresAudioSignature: Bool) {
        self.range = range
        self.requiresAudioSignature = requiresAudioSignature
    }
}

public struct DulcetPlaybackContentInformation: Equatable, Sendable {
    public let contentLength: Int64
    public let supportsByteRanges: Bool
    /// The container the validated response actually is, when the validator names one. A server
    /// may ignore a legacy format hint and send the original file (spec §12.5); the engine is then
    /// told what arrived rather than what was asked for. Nil means the plan's expected container.
    public let container: DulcetAudioContainer?

    public init(contentLength: Int64, supportsByteRanges: Bool, container: DulcetAudioContainer? = nil) {
        precondition(contentLength >= 0)
        self.contentLength = contentLength
        self.supportsByteRanges = supportsByteRanges
        self.container = container
    }
}

public enum DulcetPlaybackResourceLoadOutcome: Sendable {
    case loaded(data: Data, contentInformation: DulcetPlaybackContentInformation)
    case failed(error: DulcetPlaybackFailure, refreshReason: DulcetPlaybackSourceRefreshReason?)
    case cancelled
}

public protocol DulcetPlaybackResourceLoadOperation: AnyObject, Sendable {
    func cancel()
}

public protocol DulcetPlaybackResourceLoading: DulcetPlaybackResource {
    func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation
}

/// A single captured stream may publish validated bytes before its terminal framing check.
/// The completion is terminal exactly once; a failure cannot be followed by another chunk.
public protocol DulcetProgressivePlaybackResourceLoading: DulcetPlaybackResourceLoading {
    var loadsWholeRepresentationProgressively: Bool { get }
    func loadProgressively(
        _ request: DulcetPlaybackResourceLoadRequest,
        receive: @escaping @Sendable (Data, DulcetPlaybackContentInformation) -> Void,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation
}

public extension DulcetProgressivePlaybackResourceLoading {
    var loadsWholeRepresentationProgressively: Bool { false }
    func loadProgressively(
        _ request: DulcetPlaybackResourceLoadRequest,
        receive: @escaping @Sendable (Data, DulcetPlaybackContentInformation) -> Void,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        load(request) { outcome in
            if case let .loaded(data, information) = outcome { receive(data, information) }
            completion(outcome)
        }
    }
}

public struct DulcetPlaybackAuthorizedRequest: @unchecked Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let request: URLRequest
    public let routeBaseURL: String?
    public let routeGeneration: Int64?
    public let routeAttemptGeneration: Int64?

    public init(request: URLRequest, routeBaseURL: String? = nil, routeGeneration: Int64? = nil, routeAttemptGeneration: Int64? = nil) {
        self.request = request
        self.routeBaseURL = routeBaseURL
        self.routeGeneration = routeGeneration
        self.routeAttemptGeneration = routeAttemptGeneration
    }

    public var description: String { "DulcetPlaybackAuthorizedRequest(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [("authorizedRequest", "<redacted>" as Any)], displayStyle: .struct)
    }
}

/// Transport feedback is scoped to one preparation; completion precedes queue recovery.
public protocol DulcetPlaybackRouteFailureReporting: AnyObject, Sendable {
    func reportTransportFailure(routeBaseURL: String, routeGeneration: Int64, routeAttemptGeneration: Int64,
        completion: @escaping @Sendable () -> Void)
}

public protocol DulcetPlaybackRequestAuthorizing: AnyObject, Sendable {
    func authorize(
        range: DulcetPlaybackByteRange,
        completion: @escaping @Sendable (Result<DulcetPlaybackAuthorizedRequest, DulcetPlaybackFailure>) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation
}

public struct DulcetPlaybackHTTPResponse: Sendable {
    public let statusCode: Int
    public let contentType: String?
    public let contentLength: Int64?
    public let retryAfter: String?
    public let acceptRanges: String?
    public let contentRange: String?
    public let body: Data

    public init(
        statusCode: Int,
        contentType: String?,
        contentLength: Int64?,
        retryAfter: String?,
        acceptRanges: String?,
        contentRange: String?,
        body: Data
    ) {
        self.statusCode = statusCode
        self.contentType = contentType
        self.contentLength = contentLength
        self.retryAfter = retryAfter
        self.acceptRanges = acceptRanges
        self.contentRange = contentRange
        self.body = body
    }
}

public enum DulcetPlaybackResponseValidation: Sendable {
    case accepted(contentInformation: DulcetPlaybackContentInformation)
    case rejected(error: DulcetPlaybackFailure, refreshReason: DulcetPlaybackSourceRefreshReason?)
}

public protocol DulcetPlaybackResponseValidating: AnyObject, Sendable {
    func validate(
        response: DulcetPlaybackHTTPResponse,
        expectedContainer: DulcetAudioContainer,
        requestedRange: DulcetPlaybackByteRange,
        requiresAudioSignature: Bool
    ) -> DulcetPlaybackResponseValidation
}

/// Prefix acceptance is separate from terminal body/framing validation.
public protocol DulcetPlaybackResponsePrefixValidating: DulcetPlaybackResponseValidating {
    func validatePrefix(response: DulcetPlaybackHTTPResponse) -> DulcetPlaybackResponseValidation
}

public enum DulcetPlaybackRedirectDecision: Sendable {
    case followPreservingRequest
    case followStrippingQueryItems(Set<String>)
    case reject(DulcetPlaybackFailure)
}

public protocol DulcetPlaybackRedirectEvaluating: AnyObject, Sendable {
    func evaluate(
        sourceURL: URL,
        proposedURL: URL,
        redirectsAlreadyFollowed: Int
    ) -> DulcetPlaybackRedirectDecision
}

/// A production authorizer captures its route before dispatch; redirects cannot
/// borrow a newer selected route while this byte request is still in flight.
public protocol DulcetPlaybackScopedRedirectEvaluating: DulcetPlaybackRedirectEvaluating {
    func isCurrent(routeBaseURL: String, routeGeneration: Int64) -> Bool
    func evaluate(
        routeBaseURL: String,
        routeGeneration: Int64,
        sourceURL: URL,
        proposedURL: URL,
        redirectsAlreadyFollowed: Int
    ) -> DulcetPlaybackRedirectDecision
}

/// Performs the bytes request which the resource loader is currently satisfying.
/// Request construction and response validation remain delegated to the core-owned collaborators.
public final class DulcetURLSessionPlaybackResource: NSObject, DulcetProgressivePlaybackResourceLoading,
    @unchecked Sendable {
    private let expectedContainer: DulcetAudioContainer
    private let authorizer: any DulcetPlaybackRequestAuthorizing
    private let validator: any DulcetPlaybackResponseValidating
    private let redirectEvaluator: any DulcetPlaybackRedirectEvaluating
    private let sessionConfiguration: URLSessionConfiguration
    private let streamsWholeResponses: Bool
    private var progressiveBuffer: ProgressivePlaybackBuffer?
    private var terminalFailure: DulcetPlaybackResourceLoadOutcome?
    private let lock = NSLock()
    private var redirectsByTask: [Int: Int] = [:]
    private var routeBasesByTask: [Int: String] = [:]
    private var routeGenerationsByTask: [Int: Int64] = [:]
    /// Per task: the body received so far and what to do when the task completes. A delegate
    /// task rather than a completion-handler one, because a completion handler is given no data
    /// when the task ends in an error, so a body cut short could not reach the validator.
    private var transfersByTask: [Int: PlaybackTransfer] = [:]
    private var initializedSession: URLSession?

    private func sessionForLoading() -> URLSession {
        lock.lock()
        defer { lock.unlock() }
        if let initializedSession { return initializedSession }
        let configuration = sessionConfiguration
        DulcetMediaTransport.requireCompleteHTTPBodies(in: configuration)
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        let session = URLSession(
            configuration: configuration,
            delegate: PlaybackSessionDelegate(owner: self),
            delegateQueue: nil
        )
        initializedSession = session
        return session
    }

    public init(
        expectedContainer: DulcetAudioContainer,
        authorizer: any DulcetPlaybackRequestAuthorizing,
        validator: any DulcetPlaybackResponseValidating,
        redirectEvaluator: any DulcetPlaybackRedirectEvaluating,
        sessionConfiguration: URLSessionConfiguration = .ephemeral,
        streamsWholeResponses: Bool = false
    ) {
        self.expectedContainer = expectedContainer
        self.authorizer = authorizer
        self.validator = validator
        self.redirectEvaluator = redirectEvaluator
        self.sessionConfiguration = sessionConfiguration
        self.streamsWholeResponses = streamsWholeResponses
        super.init()
    }

    public override var description: String { "DulcetURLSessionPlaybackResource(<redacted>)" }

    public func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        load(request, progressive: nil, completion: completion)
    }

    public var loadsWholeRepresentationProgressively: Bool { streamsWholeResponses }

    public func loadProgressively(
        _ request: DulcetPlaybackResourceLoadRequest,
        receive: @escaping @Sendable (Data, DulcetPlaybackContentInformation) -> Void,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        guard streamsWholeResponses, let prefixValidator = validator as? any DulcetPlaybackResponsePrefixValidating else {
            return load(request) { outcome in
                if case let .loaded(data, information) = outcome { receive(data, information) }
                completion(outcome)
            }
        }
        lock.lock()
        let startsTransfer = progressiveBuffer == nil
        let buffer = progressiveBuffer ?? ProgressivePlaybackBuffer(start: request.range.start,
            validatePrefix: { prefixValidator.validatePrefix(response: $0) })
        progressiveBuffer = buffer
        lock.unlock()
        let subscription = buffer.subscribe(request, receive: receive, completion: completion)
        if startsTransfer {
            let probe = request.requiresAudioSignature && request.range.start == 0
                ? DulcetPlaybackResourceLoadRequest(range: .init(start: 0,
                    endInclusive: max(request.range.endInclusive, DulcetAVAssetResourceLoaderDelegate.initialChunkLength - 1)),
                    requiresAudioSignature: true) : request
            let primary = load(probe, progressive: buffer) { outcome in buffer.finish(outcome) }
            buffer.install(primary)
        }
        return subscription
    }

    private func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        progressive: ProgressivePlaybackBuffer?,
        completion resultHandler: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        let completion: @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void = { [weak self] outcome in
            if case .failed = outcome, let self {
                self.lock.lock(); self.terminalFailure = outcome; self.lock.unlock()
            }
            resultHandler(outcome)
        }
        lock.lock(); let refused = terminalFailure; lock.unlock()
        if let refused { completion(refused); return CompositePlaybackLoadOperation() }
        let operation = CompositePlaybackLoadOperation()
        let authorization = authorizer.authorize(range: request.range) { [weak self, weak operation] result in
            guard let self, let operation, !operation.isCancelled else {
                completion(.cancelled)
                return
            }
            switch result {
            case let .failure(error):
                completion(.failed(error: error, refreshReason: nil))
            case let .success(authorized):
                let failTransport: @Sendable (DulcetPlaybackFailure) -> Void = { [weak self, weak operation] failure in
                    guard let operation, !operation.isCancelled else { completion(.cancelled); return }
                    progressive?.retire()
                    // Retire authorization before route failure feedback can select another base.
                    // The engine owns the next attempt, including its Range or time offset.
                    if let self {
                        self.lock.lock()
                        self.terminalFailure = .failed(error: failure, refreshReason: nil)
                        self.lock.unlock()
                    }
                    let finish: @Sendable () -> Void = { [weak operation] in
                        completion(operation?.isCancelled == false ? .failed(error: failure, refreshReason: nil) : .cancelled)
                    }
                    if (failure == .transport || failure == .tlsUntrusted),
                       let reporter = self?.authorizer as? any DulcetPlaybackRouteFailureReporting,
                       let base = authorized.routeBaseURL, let generation = authorized.routeGeneration,
                       let attempt = authorized.routeAttemptGeneration, attempt >= 0 {
                        reporter.reportTransportFailure(routeBaseURL: base, routeGeneration: generation,
                            routeAttemptGeneration: attempt, completion: finish)
                    } else { finish() }
                }
                if let base = authorized.routeBaseURL, let routeGeneration = authorized.routeGeneration,
                    let scoped = self.redirectEvaluator as? any DulcetPlaybackScopedRedirectEvaluating,
                    !scoped.isCurrent(routeBaseURL: base, routeGeneration: routeGeneration) {
                    completion(.failed(error: .transport, refreshReason: nil))
                    return
                }
                progressive?.setAuthority { [weak self] in
                    guard let self else { return false }
                    if let base = authorized.routeBaseURL, let generation = authorized.routeGeneration,
                       let scoped = self.redirectEvaluator as? any DulcetPlaybackScopedRedirectEvaluating {
                        return scoped.isCurrent(routeBaseURL: base, routeGeneration: generation)
                    }
                    return true
                }
                var mediaRequest = authorized.request
                mediaRequest.allowsCellularAccess = mediaRequest.allowsCellularAccess && self.sessionConfiguration.allowsCellularAccess
                mediaRequest.allowsExpensiveNetworkAccess = mediaRequest.allowsExpensiveNetworkAccess && self.sessionConfiguration.allowsExpensiveNetworkAccess
                mediaRequest.allowsConstrainedNetworkAccess = mediaRequest.allowsConstrainedNetworkAccess && self.sessionConfiguration.allowsConstrainedNetworkAccess
                let task = self.sessionForLoading().dataTask(with: mediaRequest)
                self.setTaskCompletion(taskIdentifier: task.taskIdentifier, receive: progressive.map { buffer in
                    { @Sendable data, response in buffer.append(data, response: response) }
                }, cancellation: {
                    completion(.cancelled)
                }) { [weak self, weak operation] data, response, error in
                    guard let self, let operation else {
                        completion(.cancelled)
                        return
                    }
                    self.clearRedirectCount(taskIdentifier: operation.taskIdentifier)
                    guard !operation.isCancelled else {
                        completion(.cancelled)
                        return
                    }
                    // A 200 body cut off by a lost connection after some of it arrived is handed to
                    // the validator as received, and URLSession reports it as -1005. No request asks
                    // for an estimated length (spec §12.5): Navidrome's estimate can undershoot a
                    // cold transcode, and the body then stops at an arbitrary earlier write. So the
                    // core validator treats every declared length as exact and refuses the short
                    // body, and the load fails as the lost connection it was. A 200 that declared no
                    // length is never ended this way. If it was chunked, the session's strict
                    // transport has already reported a body whose zero chunk never came as that
                    // -1005, so the load stays the lost connection. Only a close-delimited body has
                    // no framing to check, and a real drop there still passes as the whole
                    // representation.
                    let lostConnectionResponse = error.map { Self.isLostConnection($0) } == true
                        ? response as? HTTPURLResponse : nil
                    let endedShort = lostConnectionResponse.map {
                        $0.statusCode == 200 && Self.resourceTotalLength(in: $0) != nil
                    } == true && !data.isEmpty
                    // Preserve the account-level refusal, including its Retry-After, for the
                    // validator even when the refused body is cut. It cannot fail over aliases.
                    let semanticRefusal = (response as? HTTPURLResponse).map { [401, 403, 429].contains($0.statusCode) } == true
                    guard error == nil || endedShort || semanticRefusal else {
                        failTransport(Self.closedFailure(for: error))
                        return
                    }
                    guard let http = response as? HTTPURLResponse else {
                        completion(.failed(error: .transport, refreshReason: nil))
                        return
                    }
                    guard http.statusCode != 206 || Self.resourceTotalLength(in: http) != nil else {
                        completion(.failed(
                            error: .protocolViolation,
                            refreshReason: .validationFailed
                        ))
                        return
                    }
                    if http.statusCode != 200, let progressive {
                        self.lock.lock()
                        if self.progressiveBuffer === progressive { self.progressiveBuffer = nil }
                        self.lock.unlock()
                    }
                    let body = data
                    let response = DulcetPlaybackHTTPResponse(
                        statusCode: http.statusCode,
                        contentType: http.value(forHTTPHeaderField: "Content-Type"),
                        contentLength: Self.resourceTotalLength(in: http),
                        retryAfter: http.value(forHTTPHeaderField: "Retry-After"),
                        acceptRanges: http.value(forHTTPHeaderField: "Accept-Ranges"),
                        contentRange: http.value(forHTTPHeaderField: "Content-Range"),
                        body: body
                    )
                    switch self.validator.validate(
                        response: response,
                        expectedContainer: self.expectedContainer,
                        requestedRange: request.range,
                        requiresAudioSignature: request.requiresAudioSignature
                    ) {
                    case let .accepted(contentInformation):
                        completion(.loaded(data: body, contentInformation: contentInformation))
                    case let .rejected(rejection, refreshReason):
                        if endedShort {
                            failTransport(Self.closedFailure(for: error))
                        } else {
                            completion(.failed(error: rejection, refreshReason: refreshReason))
                        }
                    }
                }
                operation.install(task: task)
                self.setRedirectCount(0, taskIdentifier: task.taskIdentifier)
                self.lock.lock()
                self.routeBasesByTask[task.taskIdentifier] = authorized.routeBaseURL
                self.routeGenerationsByTask[task.taskIdentifier] = authorized.routeGeneration
                self.lock.unlock()
                task.resume()
            }
        }
        operation.install(authorization: authorization)
        return operation
    }

    deinit {
        lock.lock()
        let session = initializedSession
        initializedSession = nil
        let transfers = Array(transfersByTask.values)
        transfersByTask.removeAll()
        redirectsByTask.removeAll()
        routeBasesByTask.removeAll()
        routeGenerationsByTask.removeAll()
        lock.unlock()
        // Never construct a session from deinit: its delegate must be a living object. The
        // proxy also lets an already used resource release without a session/delegate cycle.
        session?.invalidateAndCancel()
        for transfer in transfers { transfer.cancellation() }
    }

    private static func integerHeader(_ name: String, in response: HTTPURLResponse) -> Int64? {
        response.value(forHTTPHeaderField: name).flatMap(Int64.init)
    }

    private static func resourceTotalLength(in response: HTTPURLResponse) -> Int64? {
        switch response.statusCode {
        case 206:
            guard let contentRange = response.value(forHTTPHeaderField: "Content-Range"),
                  let separator = contentRange.lastIndex(of: "/"),
                  let total = Int64(contentRange[contentRange.index(after: separator)...]),
                  total > 0 else {
                return nil
            }
            return total
        case 200:
            return integerHeader("Content-Length", in: response)
        default:
            return nil
        }
    }

    private static func isLostConnection(_ error: Error) -> Bool {
        let nsError = error as NSError
        return nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorNetworkConnectionLost
    }

    private func setTaskCompletion(
        taskIdentifier: Int,
        receive: (@Sendable (Data, URLResponse?) -> Void)? = nil,
        cancellation: @escaping @Sendable () -> Void,
        _ completion: @escaping @Sendable (Data, URLResponse?, Error?) -> Void
    ) {
        lock.lock()
        transfersByTask[taskIdentifier] = PlaybackTransfer(completion: completion, cancellation: cancellation, receive: receive)
        lock.unlock()
    }

    fileprivate func receive(_ response: URLResponse, taskIdentifier: Int) {
        lock.lock()
        transfersByTask[taskIdentifier]?.response = response
        lock.unlock()
    }

    fileprivate func append(_ data: Data, taskIdentifier: Int) {
        lock.lock()
        transfersByTask[taskIdentifier]?.body.append(data)
        let receive = transfersByTask[taskIdentifier]?.receive
        let response = transfersByTask[taskIdentifier]?.response
        lock.unlock()
        receive?(data, response)
    }

    fileprivate func completeTransfer(_ task: URLSessionTask, error: Error?) {
        lock.lock()
        let transfer = transfersByTask.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        transfer?.completion(transfer?.body ?? Data(), transfer?.response ?? task.response, error)
    }

    private static func closedFailure(for error: Error?) -> DulcetPlaybackFailure {
        DulcetApplePlaybackErrorSanitizer.urlSessionFailure(error)
    }

    private func redirectCount(taskIdentifier: Int) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return redirectsByTask[taskIdentifier, default: 0]
    }

    private func setRedirectCount(_ count: Int, taskIdentifier: Int) {
        lock.lock()
        redirectsByTask[taskIdentifier] = count
        lock.unlock()
    }

    private func clearRedirectCount(taskIdentifier: Int?) {
        guard let taskIdentifier else { return }
        lock.lock()
        redirectsByTask.removeValue(forKey: taskIdentifier)
        routeBasesByTask.removeValue(forKey: taskIdentifier)
        routeGenerationsByTask.removeValue(forKey: taskIdentifier)
        lock.unlock()
    }
}

private struct PlaybackTransfer {
    var body = Data()
    var response: URLResponse?
    let completion: @Sendable (Data, URLResponse?, Error?) -> Void
    let cancellation: @Sendable () -> Void
    let receive: (@Sendable (Data, URLResponse?) -> Void)?
}

/// URLSession retains its delegate until invalidation completes. The delegate must not retain
/// the resource which owns that session, including while a cancelled task is being torn down.
private final class PlaybackSessionDelegate: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private weak var owner: DulcetURLSessionPlaybackResource?

    init(owner: DulcetURLSessionPlaybackResource) { self.owner = owner }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
        guard let owner else { completionHandler(.cancel); return }
        owner.receive(response, taskIdentifier: dataTask.taskIdentifier)
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        owner?.urlSession(session, dataTask: dataTask, didReceive: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        owner?.urlSession(session, task: task, didCompleteWithError: error)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let owner else { completionHandler(nil); return }
        owner.urlSession(session, task: task, willPerformHTTPRedirection: response,
                         newRequest: request, completionHandler: completionHandler)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard let owner else { completionHandler(.cancelAuthenticationChallenge, nil); return }
        owner.urlSession(session, task: task, didReceive: challenge, completionHandler: completionHandler)
    }
}

extension DulcetURLSessionPlaybackResource: URLSessionDataDelegate {
    public func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        append(data, taskIdentifier: dataTask.taskIdentifier)
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        completeTransfer(task, error: error)
    }

    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let sourceURL = response.url, let proposedURL = request.url else {
            task.cancel()
            completionHandler(nil)
            return
        }
        let redirects = redirectCount(taskIdentifier: task.taskIdentifier)
        lock.lock()
        let capturedBase = routeBasesByTask[task.taskIdentifier]
        let capturedGeneration = routeGenerationsByTask[task.taskIdentifier]
        lock.unlock()
        let decision: DulcetPlaybackRedirectDecision
        if let capturedBase, let capturedGeneration, let scoped = redirectEvaluator as? any DulcetPlaybackScopedRedirectEvaluating {
            decision = scoped.evaluate(routeBaseURL: capturedBase, routeGeneration: capturedGeneration, sourceURL: sourceURL,
                proposedURL: proposedURL, redirectsAlreadyFollowed: redirects)
        } else {
            decision = redirectEvaluator.evaluate(sourceURL: sourceURL,
                proposedURL: proposedURL, redirectsAlreadyFollowed: redirects)
        }
        switch decision {
        case .followPreservingRequest:
            setRedirectCount(redirects + 1, taskIdentifier: task.taskIdentifier)
            completionHandler(request)
        case let .followStrippingQueryItems(names):
            guard let sanitized = request.removingQueryItems(named: names) else {
                task.cancel()
                completionHandler(nil)
                return
            }
            setRedirectCount(redirects + 1, taskIdentifier: task.taskIdentifier)
            completionHandler(sanitized)
        case .reject:
            // nil alone asks URLSession for the redirect body. A refused media URL is
            // terminal; the source strict protocol has already handed off and retired.
            task.cancel()
            completionHandler(nil)
        }
    }

    public func urlSession(
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

private extension URLRequest {
    func removingQueryItems(named names: Set<String>) -> URLRequest? {
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

private final class CompositePlaybackLoadOperation: DulcetPlaybackResourceLoadOperation,
    @unchecked Sendable {
    private let lock = NSLock()
    private var authorization: (any DulcetPlaybackResourceLoadOperation)?
    private var task: URLSessionTask?
    private var cancelled = false

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    var taskIdentifier: Int? {
        lock.lock()
        defer { lock.unlock() }
        return task?.taskIdentifier
    }

    func install(authorization: any DulcetPlaybackResourceLoadOperation) {
        lock.lock()
        self.authorization = authorization
        let shouldCancel = cancelled
        lock.unlock()
        if shouldCancel { authorization.cancel() }
    }

    func install(task: URLSessionTask) {
        lock.lock()
        self.task = task
        let shouldCancel = cancelled
        lock.unlock()
        if shouldCancel { task.cancel() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let authorization = authorization
        let task = task
        lock.unlock()
        authorization?.cancel()
        task?.cancel()
    }
}

/// One cold stream belongs to one prepared attempt. AVFoundation's small header reads and later
/// reads subscribe to the same received representation instead of opening the song again.
private final class ProgressivePlaybackBuffer: @unchecked Sendable {
    private struct Subscriber {
        let request: DulcetPlaybackResourceLoadRequest
        var offset: Int64
        let receive: @Sendable (Data, DulcetPlaybackContentInformation) -> Void
        let completion: @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    }
    private let queue = DispatchQueue(label: "com.legitimateapps.dulcet.playback-progressive")
    private var start: Int64
    private let validatePrefix: @Sendable (DulcetPlaybackHTTPResponse) -> DulcetPlaybackResponseValidation
    private var authority: @Sendable () -> Bool = { true }
    private var primary: (any DulcetPlaybackResourceLoadOperation)?
    private var subscribers: [UUID: Subscriber] = [:]
    private var body = Data()
    private var information: DulcetPlaybackContentInformation?
    private var terminal: DulcetPlaybackResourceLoadOutcome?
    private var retired = false

    init(start: Int64, validatePrefix: @escaping @Sendable (DulcetPlaybackHTTPResponse) -> DulcetPlaybackResponseValidation) {
        self.start = start; self.validatePrefix = validatePrefix
    }
    func retire() { queue.async { self.retired = true } }
    func setAuthority(_ authority: @escaping @Sendable () -> Bool) { queue.async { self.authority = authority } }
    func install(_ primary: any DulcetPlaybackResourceLoadOperation) {
        queue.async { self.primary = primary; if self.terminal != nil { primary.cancel() } }
    }
    func subscribe(_ request: DulcetPlaybackResourceLoadRequest,
        receive: @escaping @Sendable (Data, DulcetPlaybackContentInformation) -> Void,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        let id = UUID()
        queue.async {
            self.subscribers[id] = Subscriber(request: request, offset: request.range.start,
                receive: receive, completion: completion)
            self.drain()
        }
        return ProgressivePlaybackSubscription { [weak self] in
            self?.queue.async { [weak self] in
                guard let self, let subscriber = self.subscribers.removeValue(forKey: id) else { return }
                subscriber.completion(.cancelled)
                // AVFoundation cancels speculative reads before asking for another window
                // (including tail metadata). The resource, not a read subscription, owns the
                // captured transfer; retiring the asset releases it and cancels its session.

            }
        }
    }
    func append(_ data: Data, response: URLResponse?) {
        queue.async {
            guard self.terminal == nil, !self.retired else { return }
            guard self.authority() else { self.reject(.transport); return }
            if self.body.isEmpty, let http = response as? HTTPURLResponse, http.statusCode == 200 {
                self.start = 0 // A server that ignored Range answered the representation from zero.
            }
            self.body.append(data)
            // A real, exact-framed200 can publish before EOF. Unknown lengths and ranges retain
            // the existing complete-body validator; they cannot borrow a fabricated total.
            if self.information == nil, let http = response as? HTTPURLResponse,
               http.statusCode == 200, self.start == 0,
               let length = http.value(forHTTPHeaderField: "Content-Length").flatMap(Int64.init), length > 0,
               self.body.count >= min(4096, length) {
                guard self.body.count <= length else { self.reject(.protocolViolation); return }
                let prefix = DulcetPlaybackHTTPResponse(statusCode: http.statusCode,
                    contentType: http.value(forHTTPHeaderField: "Content-Type"), contentLength: length,
                    retryAfter: http.value(forHTTPHeaderField: "Retry-After"),
                    acceptRanges: http.value(forHTTPHeaderField: "Accept-Ranges"),
                    contentRange: http.value(forHTTPHeaderField: "Content-Range"), body: Data(self.body.prefix(4096)))
                switch self.validatePrefix(prefix) {
                case let .accepted(information):
                    guard information.contentLength == length else { self.reject(.protocolViolation); return }
                    self.information = information
                case let .rejected(error, _): self.reject(error); return
                }
            }
            self.drain()
        }
    }
    func finish(_ outcome: DulcetPlaybackResourceLoadOutcome) {
        queue.async {
            guard self.terminal == nil else { return }
            if case let .loaded(_, information) = outcome {
                guard self.information == nil || self.information == information else {
                    self.reject(.protocolViolation); return
                }
                self.information = information
            }
            self.terminal = outcome
            self.drain()
        }
    }
    private func reject(_ error: DulcetPlaybackFailure) {
        terminal = .failed(error: error, refreshReason: nil)
        primary?.cancel()
        drain()
    }
    private func drain() {
        guard !retired || terminal != nil else { return }
        if let terminal {
            switch terminal {
            case .failed, .cancelled:
                let pending = subscribers.values; subscribers.removeAll()
                for subscriber in pending { subscriber.completion(terminal) }
                return
            case .loaded: break
            }
        }
        guard let information else { return }
        guard authority() else { reject(.transport); return }
        for id in Array(subscribers.keys) {
            guard var subscriber = subscribers[id] else { continue }
            let end = min(subscriber.request.range.endInclusive + 1, information.contentLength,
                start + Int64(body.count))
            if subscriber.offset >= start, subscriber.offset < end {
                let lower = Int(subscriber.offset - start), upper = Int(end - start)
                subscriber.receive(Data(body[lower..<upper]), information)
                subscriber.offset = end
            }
            let windowDone = subscriber.offset >= min(subscriber.request.range.endInclusive + 1, information.contentLength)
            if terminal != nil || (windowDone && subscriber.offset < information.contentLength) {
                subscribers.removeValue(forKey: id)
                subscriber.completion(.loaded(data: Data(), contentInformation: information))
            } else { subscribers[id] = subscriber }
        }
    }
}

private final class ProgressivePlaybackSubscription: DulcetPlaybackResourceLoadOperation, @unchecked Sendable {
    private let lock = NSLock()
    private var action: (@Sendable () -> Void)?
    init(_ action: @escaping @Sendable () -> Void) { self.action = action }
    func cancel() { lock.lock(); let action = action; self.action = nil; lock.unlock(); action?() }
}

/// Translates AVFoundation range requests into bounded, validated resource loads.
enum DulcetPlaybackResourceLoaderTraceEvent: Sendable {
    case started(
        requestID: Int,
        requestedOffset: Int64,
        requestedLength: Int64,
        requestsToEnd: Bool,
        requiresAudioSignature: Bool
    )
    case rangeRequested(requestID: Int, range: DulcetPlaybackByteRange)
    case contentInformationPublished(requestID: Int)
    case responded(requestID: Int, byteCount: Int)
    case finished(requestID: Int)
    case cancelled(requestID: Int)
    case failed(requestID: Int, failure: DulcetPlaybackFailure)
}

public final class DulcetAVAssetResourceLoaderDelegate: NSObject, AVAssetResourceLoaderDelegate,
    @unchecked Sendable {
    public static let scheme = "dulcet-stream"
    static let initialChunkLength: Int64 = 256 * 1024
    public static let maximumChunkLength: Int64 = 1024 * 1024

    private let resource: any DulcetPlaybackResourceLoading
    private let attemptID: DulcetPlaybackAttemptID
    private let expectedContainer: DulcetAudioContainer
    private let traceHandler: (@Sendable (DulcetPlaybackResourceLoaderTraceEvent) -> Void)?
    private let failureHandler: @Sendable (
        DulcetPlaybackAttemptID,
        DulcetPlaybackFailure,
        DulcetPlaybackSourceRefreshReason?
    ) -> Void
    private let lock = NSLock()
    private var contexts: [ObjectIdentifier: LoadingContext] = [:]
    private var contentInformation: DulcetPlaybackContentInformation?

    public var latestContentInformation: DulcetPlaybackContentInformation? {
        lock.lock()
        defer { lock.unlock() }
        return contentInformation
    }

    var hasPendingRequests: Bool {
        lock.lock()
        defer { lock.unlock() }
        return !contexts.isEmpty
    }

    public init(
        resource: any DulcetPlaybackResourceLoading,
        attemptID: DulcetPlaybackAttemptID,
        expectedContainer: DulcetAudioContainer,
        failureHandler: @escaping @Sendable (
            DulcetPlaybackAttemptID,
            DulcetPlaybackFailure,
            DulcetPlaybackSourceRefreshReason?
        ) -> Void
    ) {
        self.resource = resource
        self.attemptID = attemptID
        self.expectedContainer = expectedContainer
        self.traceHandler = nil
        self.failureHandler = failureHandler
        super.init()
    }

    init(
        resource: any DulcetPlaybackResourceLoading,
        attemptID: DulcetPlaybackAttemptID,
        expectedContainer: DulcetAudioContainer,
        traceHandler: (@Sendable (DulcetPlaybackResourceLoaderTraceEvent) -> Void)?,
        failureHandler: @escaping @Sendable (
            DulcetPlaybackAttemptID,
            DulcetPlaybackFailure,
            DulcetPlaybackSourceRefreshReason?
        ) -> Void
    ) {
        self.resource = resource
        self.attemptID = attemptID
        self.expectedContainer = expectedContainer
        self.traceHandler = traceHandler
        self.failureHandler = failureHandler
        super.init()
    }

    deinit {
        // AVFoundation need not send didCancel after retiring its asset. Remove ownership before
        // cancelling operations because cancellation may synchronously invoke their completions.
        lock.lock()
        let pending = Array(contexts.values)
        contexts.removeAll()
        lock.unlock()
        for context in pending {
            context.cancel()
            traceHandler?(.cancelled(requestID: context.requestID))
        }
    }

    public func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard loadingRequest.request.url?.scheme == Self.scheme else { return false }
        guard let dataRequest = loadingRequest.dataRequest else {
            finish(
                loadingRequest,
                failure: .protocolViolation,
                refreshReason: nil
            )
            return true
        }
        let requestedOffset = max(
            0,
            dataRequest.currentOffset > 0 ? dataRequest.currentOffset : dataRequest.requestedOffset
        )
        let requestedLength = max(1, Int64(dataRequest.requestedLength))
        let context = LoadingContext(
            loadingRequest: loadingRequest,
            nextOffset: requestedOffset,
            requestedEndExclusive: requestedOffset + requestedLength,
            requestsToEnd: dataRequest.requestsAllDataToEndOfResource,
            requiresAudioSignature: requestedOffset == 0
        )
        setContext(context, for: loadingRequest)
        traceHandler?(.started(
            requestID: context.requestID,
            requestedOffset: requestedOffset,
            requestedLength: requestedLength,
            requestsToEnd: dataRequest.requestsAllDataToEndOfResource,
            requiresAudioSignature: requestedOffset == 0
        ))
        loadNextChunk(context)
        return true
    }

    public func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        guard let context = removeContext(for: loadingRequest) else { return }
        context.cancel()
        traceHandler?(.cancelled(requestID: context.requestID))
    }

    /// The signature probe can outgrow AVFoundation's tiny initial header request. Once the
    /// signature is checked, larger bounded reads amortize the strict transport's connections.
    static func nextRange(
        offset: Int64,
        requestedEndExclusive: Int64,
        requestsToEnd: Bool,
        requiresAudioSignature: Bool,
        contentLength: Int64?
    ) -> DulcetPlaybackByteRange? {
        let desiredEndExclusive: Int64
        if let contentLength {
            desiredEndExclusive = requestsToEnd
                ? contentLength
                : min(requestedEndExclusive, contentLength)
        } else {
            desiredEndExclusive = requestedEndExclusive
        }
        guard offset < desiredEndExclusive else { return nil }
        let maximumLength = requiresAudioSignature ? initialChunkLength : maximumChunkLength
        let boundedEndExclusive = requiresAudioSignature
            ? contentLength ?? Int64.max
            : desiredEndExclusive
        let length = min(maximumLength, boundedEndExclusive - offset)
        return DulcetPlaybackByteRange(start: offset, endInclusive: offset + length - 1)
    }

    private func loadNextChunk(_ context: LoadingContext) {
        guard !context.isCancelled else { return }
        guard let range = Self.nextRange(
            offset: context.nextOffset,
            requestedEndExclusive: context.requestedEndExclusive,
            requestsToEnd: context.requestsToEnd,
            requiresAudioSignature: context.requiresAudioSignature,
            contentLength: context.contentInformation?.contentLength
        ) else {
            complete(context)
            return
        }
        traceHandler?(.rangeRequested(requestID: context.requestID, range: range))
        if let progressive = resource as? any DulcetProgressivePlaybackResourceLoading {
            loadProgressively(context, range: range, resource: progressive)
            return
        }
        let operation = resource.load(
            DulcetPlaybackResourceLoadRequest(
                range: range,
                requiresAudioSignature: context.requiresAudioSignature
            )
        ) { [weak self, weak context] outcome in
            guard let self, let context, !context.isCancelled else { return }
            switch outcome {
            case .cancelled:
                let removed = self.removeContext(for: context.loadingRequest)
                context.cancel()
                if removed != nil {
                    self.traceHandler?(.cancelled(requestID: context.requestID))
                }
            case let .failed(error, refreshReason):
                self.finish(context.loadingRequest, failure: error, refreshReason: refreshReason)
            case let .loaded(data, information):
                // A body longer than the chunk is accepted only as the whole representation from
                // its first byte: a transcode the server has not cached honours no range and
                // answers the first read with HTTP 200 and every byte (spec §12.5), which the
                // validator has already measured as the resource's length.
                let isWholeRepresentation = range.start == 0
                    && Int64(data.count) == information.contentLength
                guard !data.isEmpty, Int64(data.count) <= range.length || isWholeRepresentation else {
                    self.finish(
                        context.loadingRequest,
                        failure: .protocolViolation,
                        refreshReason: .validationFailed
                    )
                    return
                }
                if let existingInformation = context.contentInformation {
                    guard existingInformation == information else {
                        self.finish(
                            context.loadingRequest,
                            failure: .protocolViolation,
                            refreshReason: .validationFailed
                        )
                        return
                    }
                } else {
                    context.contentInformation = information
                    self.fillContentInformation(context.loadingRequest, with: information)
                    self.traceHandler?(.contentInformationPublished(requestID: context.requestID))
                }
                let responseLength = context.requestsToEnd
                    ? data.count
                    : min(
                        data.count,
                        Int(context.requestedEndExclusive - context.nextOffset)
                    )
                let responseData = data.prefix(responseLength)
                context.loadingRequest.dataRequest?.respond(with: responseData)
                self.traceHandler?(.responded(
                    requestID: context.requestID,
                    byteCount: responseData.count
                ))
                context.nextOffset += Int64(responseData.count)
                context.requiresAudioSignature = false
                if context.nextOffset >= information.contentLength ||
                    (!context.requestsToEnd && context.nextOffset >= context.requestedEndExclusive) {
                    self.complete(context)
                } else {
                    self.loadNextChunk(context)
                }
            }
        }
        context.install(operation)
    }

    private func loadProgressively(_ context: LoadingContext, range: DulcetPlaybackByteRange,
        resource: any DulcetProgressivePlaybackResourceLoading) {
        let deliveryRange = resource.loadsWholeRepresentationProgressively && !context.requestsToEnd
            ? DulcetPlaybackByteRange(start: range.start,
                endInclusive: min(range.endInclusive, context.requestedEndExclusive - 1)) : range
        let operation = resource.loadProgressively(.init(range: deliveryRange,
            requiresAudioSignature: context.requiresAudioSignature), receive: { [weak self, weak context] data, information in
                guard let self, let context, !context.isCancelled else { return }
                if let previous = context.contentInformation, previous != information {
                    self.finish(context.loadingRequest, failure: .protocolViolation, refreshReason: .validationFailed)
                    return
                }
                if context.contentInformation == nil {
                    context.contentInformation = information
                    self.fillContentInformation(context.loadingRequest, with: information)
                    self.traceHandler?(.contentInformationPublished(requestID: context.requestID))
                }
                let count = context.requestsToEnd ? data.count : min(data.count,
                    max(0, Int(context.requestedEndExclusive - context.nextOffset)))
                if count > 0 {
                    context.loadingRequest.dataRequest?.respond(with: data.prefix(count))
                    context.nextOffset += Int64(count)
                    context.requiresAudioSignature = false
                    self.traceHandler?(.responded(requestID: context.requestID, byteCount: count))
                }
                if !context.requestsToEnd && context.nextOffset >= context.requestedEndExclusive {
                    self.complete(context)
                }
            }, completion: { [weak self, weak context] outcome in
                guard let self, let context, !context.isCancelled else { return }
                switch outcome {
                case .cancelled:
                    if self.removeContext(for: context.loadingRequest) != nil {
                        context.cancel(); self.traceHandler?(.cancelled(requestID: context.requestID))
                    }
                case let .failed(error, refreshReason):
                    self.finish(context.loadingRequest, failure: error, refreshReason: refreshReason)
                case let .loaded(_, information):
                    if context.nextOffset >= information.contentLength ||
                       (!context.requestsToEnd && context.nextOffset >= context.requestedEndExclusive) {
                        self.complete(context)
                    } else { self.loadNextChunk(context) }
                }
            })
        context.install(operation)
    }

    private func fillContentInformation(
        _ loadingRequest: AVAssetResourceLoadingRequest,
        with information: DulcetPlaybackContentInformation
    ) {
        lock.lock()
        contentInformation = information
        lock.unlock()
        guard let target = loadingRequest.contentInformationRequest else { return }
        target.contentType = (information.container ?? expectedContainer).uniformTypeIdentifier
        target.contentLength = information.contentLength
        target.isByteRangeAccessSupported = information.supportsByteRanges
    }

    private func complete(_ context: LoadingContext) {
        guard removeContext(for: context.loadingRequest) != nil else { return }
        context.loadingRequest.finishLoading()
        traceHandler?(.finished(requestID: context.requestID))
    }

    private func finish(
        _ loadingRequest: AVAssetResourceLoadingRequest,
        failure: DulcetPlaybackFailure,
        refreshReason: DulcetPlaybackSourceRefreshReason?
    ) {
        let context = removeContext(for: loadingRequest)
        context?.cancel()
        failureHandler(attemptID, failure, refreshReason)
        loadingRequest.finishLoading(with: failure.resourceLoaderError)
        if let context {
            traceHandler?(.failed(requestID: context.requestID, failure: failure))
        }
    }

    private func setContext(
        _ context: LoadingContext,
        for request: AVAssetResourceLoadingRequest
    ) {
        lock.lock()
        contexts[ObjectIdentifier(request)] = context
        lock.unlock()
    }

    private func removeContext(
        for request: AVAssetResourceLoadingRequest
    ) -> LoadingContext? {
        lock.lock()
        defer { lock.unlock() }
        return contexts.removeValue(forKey: ObjectIdentifier(request))
    }
}

private final class LoadingContext: @unchecked Sendable {
    let loadingRequest: AVAssetResourceLoadingRequest
    let requestID: Int
    let requestedEndExclusive: Int64
    let requestsToEnd: Bool
    var nextOffset: Int64
    var requiresAudioSignature: Bool
    var contentInformation: DulcetPlaybackContentInformation?

    private let lock = NSLock()
    private var operation: (any DulcetPlaybackResourceLoadOperation)?
    private var cancelled = false

    init(
        loadingRequest: AVAssetResourceLoadingRequest,
        nextOffset: Int64,
        requestedEndExclusive: Int64,
        requestsToEnd: Bool,
        requiresAudioSignature: Bool
    ) {
        self.loadingRequest = loadingRequest
        self.requestID = Int(bitPattern: ObjectIdentifier(loadingRequest))
        self.nextOffset = nextOffset
        self.requestedEndExclusive = requestedEndExclusive
        self.requestsToEnd = requestsToEnd
        self.requiresAudioSignature = requiresAudioSignature
    }

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func install(_ operation: any DulcetPlaybackResourceLoadOperation) {
        lock.lock()
        self.operation = operation
        let shouldCancel = cancelled
        lock.unlock()
        if shouldCancel { operation.cancel() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let operation = operation
        lock.unlock()
        operation?.cancel()
    }
}

private extension DulcetAudioContainer {
    var uniformTypeIdentifier: String {
        let extensionName = switch self {
        case .mp3: "mp3"
        case .mp4: "m4a"
        case .wav: "wav"
        case .flac: "flac"
        case .ogg: "ogg"
        case .adtsAAC: "aac"
        }
        return UTType(filenameExtension: extensionName)?.identifier ?? UTType.audio.identifier
    }
}

private extension DulcetPlaybackFailure {
    var resourceLoaderError: NSError {
        let code = switch self {
        case .authentication: 1
        case .forbidden: 2
        case .serverBusy: 3
        case .protocolViolation: 4
        case .transport: 5
        case .tlsUntrusted: 6
        case .sourceUnavailable: 7
        case .unsupportedPlan: 8
        case .engine: 9
        case .undecodable: 10
        case .unexpectedContentType: 11
        case .unexpectedBinary: 12
        case .server: 13
        case .unrecognizedServerError: 14
        case .capabilityUnsupported: 15
        }
        return NSError(domain: "com.legitimateapps.dulcet.playback", code: code)
    }
}

/// The only conversion point for URL-bearing Foundation errors. Raw errors are never returned.
enum DulcetApplePlaybackErrorSanitizer {
    /// Reads the error and then the errors it wraps (`NSUnderlyingErrorKey`), outermost first, and
    /// classifies by the first one it recognises: one of an item's own media failures, or a URL
    /// failure. AVFoundation reports a transport failure wrapped in its own generic error, so
    /// reading only the outermost would call a lost connection the engine's. At most
    /// ``underlyingErrorDepth`` errors are read, the outermost included, so a cyclic or
    /// pathological chain still ends; past the bound, or with nothing recognised, it is `.engine`.
    /// Only the domain and code of each error are read -- never its user info's strings, which
    /// can carry the credential-bearing URL.
    static func avFoundationFailure(_ error: Error?) -> DulcetPlaybackFailure {
        var next = error as NSError?
        var read = 0
        while let nsError = next, read < underlyingErrorDepth {
            read += 1
            if nsError.domain == AVFoundationErrorDomain,
               let code = AVError.Code(rawValue: nsError.code),
               itemMediaFailures.contains(code) {
                return .undecodable
            }
            if nsError.domain == NSURLErrorDomain {
                return urlFailureCode(nsError.code)
            }
            next = nsError.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return .engine
    }

    /// How many errors ``avFoundationFailure(_:)`` reads, the outermost included.
    static let underlyingErrorDepth = 8

    /// AVFoundation's failures of an item's OWN media: it could not be parsed, recognised or
    /// decoded (spec §12.12). A decoder merely unavailable for now, a media-services reset or an
    /// interrupted operation is the system's, not the item's, and stays `.engine`.
    /// OBSERVED: an MP3 whose frames are corrupt after a valid ID3 tag becomes ready to play, then
    /// posts `AVPlayerItemFailedToPlayToEndTime` with `decodeFailed` (-11821).
    static let itemMediaFailures: Set<AVError.Code> = [
        .decodeFailed,
        .decoderNotFound,
        .fileFormatNotRecognized,
        .fileFailedToParse,
        .failedToParse,
        .undecodableMediaData,
    ]

    static func urlSessionFailure(_ error: Error?) -> DulcetPlaybackFailure {
        guard let nsError = error as NSError?, nsError.domain == NSURLErrorDomain else {
            return .transport
        }
        return urlFailureCode(nsError.code)
    }

    private static func urlFailureCode(_ rawCode: Int) -> DulcetPlaybackFailure {
        switch URLError.Code(rawValue: rawCode) {
        case .serverCertificateHasBadDate, .serverCertificateUntrusted,
             .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid,
             .clientCertificateRejected, .clientCertificateRequired,
             .secureConnectionFailed:
            return .tlsUntrusted
        default:
            return .transport
        }
    }
}
