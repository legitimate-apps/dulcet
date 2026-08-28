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

    public init(contentLength: Int64, supportsByteRanges: Bool) {
        precondition(contentLength >= 0)
        self.contentLength = contentLength
        self.supportsByteRanges = supportsByteRanges
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

public struct DulcetPlaybackAuthorizedRequest: @unchecked Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let request: URLRequest

    public init(request: URLRequest) {
        self.request = request
    }

    public var description: String { "DulcetPlaybackAuthorizedRequest(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [("authorizedRequest", "<redacted>" as Any)], displayStyle: .struct)
    }
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

/// Performs the bytes request which the resource loader is currently satisfying.
/// Request construction and response validation remain delegated to the core-owned collaborators.
public final class DulcetURLSessionPlaybackResource: NSObject, DulcetPlaybackResourceLoading,
    @unchecked Sendable {
    private let expectedContainer: DulcetAudioContainer
    private let authorizer: any DulcetPlaybackRequestAuthorizing
    private let validator: any DulcetPlaybackResponseValidating
    private let redirectEvaluator: any DulcetPlaybackRedirectEvaluating
    private let sessionConfiguration: URLSessionConfiguration
    private let lock = NSLock()
    private var redirectsByTask: [Int: Int] = [:]
    private lazy var session: URLSession = {
        let configuration = sessionConfiguration
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    public init(
        expectedContainer: DulcetAudioContainer,
        authorizer: any DulcetPlaybackRequestAuthorizing,
        validator: any DulcetPlaybackResponseValidating,
        redirectEvaluator: any DulcetPlaybackRedirectEvaluating,
        sessionConfiguration: URLSessionConfiguration = .ephemeral
    ) {
        self.expectedContainer = expectedContainer
        self.authorizer = authorizer
        self.validator = validator
        self.redirectEvaluator = redirectEvaluator
        self.sessionConfiguration = sessionConfiguration
        super.init()
    }

    public override var description: String { "DulcetURLSessionPlaybackResource(<redacted>)" }

    public func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
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
                let task = self.session.dataTask(with: authorized.request) { [weak self, weak operation] data, response, error in
                    guard let self, let operation else {
                        completion(.cancelled)
                        return
                    }
                    self.clearRedirectCount(taskIdentifier: operation.taskIdentifier)
                    guard !operation.isCancelled else {
                        completion(.cancelled)
                        return
                    }
                    guard error == nil else {
                        completion(.failed(error: Self.closedFailure(for: error), refreshReason: nil))
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
                    let body = data ?? Data()
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
                    case let .rejected(error, refreshReason):
                        completion(.failed(error: error, refreshReason: refreshReason))
                    }
                }
                operation.install(task: task)
                self.setRedirectCount(0, taskIdentifier: task.taskIdentifier)
                task.resume()
            }
        }
        operation.install(authorization: authorization)
        return operation
    }

    deinit {
        session.invalidateAndCancel()
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
        lock.unlock()
    }
}

extension DulcetURLSessionPlaybackResource: URLSessionTaskDelegate {
    public func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let sourceURL = response.url, let proposedURL = request.url else {
            completionHandler(nil)
            return
        }
        let redirects = redirectCount(taskIdentifier: task.taskIdentifier)
        switch redirectEvaluator.evaluate(
            sourceURL: sourceURL,
            proposedURL: proposedURL,
            redirectsAlreadyFollowed: redirects
        ) {
        case .followPreservingRequest:
            setRedirectCount(redirects + 1, taskIdentifier: task.taskIdentifier)
            completionHandler(request)
        case let .followStrippingQueryItems(names):
            guard let sanitized = request.removingQueryItems(named: names) else {
                completionHandler(nil)
                return
            }
            setRedirectCount(redirects + 1, taskIdentifier: task.taskIdentifier)
            completionHandler(sanitized)
        case .reject:
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

/// Translates AVFoundation range requests into bounded, validated resource loads.
public final class DulcetAVAssetResourceLoaderDelegate: NSObject, AVAssetResourceLoaderDelegate,
    @unchecked Sendable {
    public static let scheme = "dulcet-stream"
    public static let maximumChunkLength: Int64 = 256 * 1024

    private let resource: any DulcetPlaybackResourceLoading
    private let attemptID: DulcetPlaybackAttemptID
    private let expectedContainer: DulcetAudioContainer
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
        self.failureHandler = failureHandler
        super.init()
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
        loadNextChunk(context)
        return true
    }

    public func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        removeContext(for: loadingRequest)?.cancel()
    }

    private func loadNextChunk(_ context: LoadingContext) {
        guard !context.isCancelled else { return }
        let desiredEndExclusive: Int64
        if let contentLength = context.contentInformation?.contentLength {
            desiredEndExclusive = context.requestsToEnd
                ? contentLength
                : min(context.requestedEndExclusive, contentLength)
        } else {
            desiredEndExclusive = context.requestedEndExclusive
        }
        if context.nextOffset >= desiredEndExclusive {
            complete(context)
            return
        }
        let chunkEndExclusive = context.nextOffset + Self.maximumChunkLength
        let fetchEndExclusive = context.requiresAudioSignature
            ? chunkEndExclusive
            : min(desiredEndExclusive, chunkEndExclusive)
        let endInclusive = fetchEndExclusive - 1
        let range = DulcetPlaybackByteRange(
            start: context.nextOffset,
            endInclusive: endInclusive
        )
        let operation = resource.load(
            DulcetPlaybackResourceLoadRequest(
                range: range,
                requiresAudioSignature: context.requiresAudioSignature
            )
        ) { [weak self, weak context] outcome in
            guard let self, let context, !context.isCancelled else { return }
            switch outcome {
            case .cancelled:
                _ = self.removeContext(for: context.loadingRequest)
                context.cancel()
            case let .failed(error, refreshReason):
                self.finish(context.loadingRequest, failure: error, refreshReason: refreshReason)
            case let .loaded(data, information):
                guard !data.isEmpty, Int64(data.count) <= range.length else {
                    self.finish(
                        context.loadingRequest,
                        failure: .protocolViolation,
                        refreshReason: .validationFailed
                    )
                    return
                }
                context.contentInformation = information
                self.fillContentInformation(context.loadingRequest, with: information)
                let responseLength = context.requestsToEnd
                    ? data.count
                    : min(
                        data.count,
                        Int(context.requestedEndExclusive - context.nextOffset)
                    )
                let responseData = data.prefix(responseLength)
                context.loadingRequest.dataRequest?.respond(with: responseData)
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

    private func fillContentInformation(
        _ loadingRequest: AVAssetResourceLoadingRequest,
        with information: DulcetPlaybackContentInformation
    ) {
        lock.lock()
        contentInformation = information
        lock.unlock()
        guard let target = loadingRequest.contentInformationRequest else { return }
        target.contentType = expectedContainer.uniformTypeIdentifier
        target.contentLength = information.contentLength
        target.isByteRangeAccessSupported = information.supportsByteRanges
    }

    private func complete(_ context: LoadingContext) {
        _ = removeContext(for: context.loadingRequest)
        context.loadingRequest.finishLoading()
    }

    private func finish(
        _ loadingRequest: AVAssetResourceLoadingRequest,
        failure: DulcetPlaybackFailure,
        refreshReason: DulcetPlaybackSourceRefreshReason?
    ) {
        removeContext(for: loadingRequest)?.cancel()
        failureHandler(attemptID, failure, refreshReason)
        loadingRequest.finishLoading(with: failure.resourceLoaderError)
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
        }
        return NSError(domain: "com.legitimateapps.dulcet.playback", code: code)
    }
}

/// The only conversion point for URL-bearing Foundation errors. Raw errors are never returned.
enum DulcetApplePlaybackErrorSanitizer {
    static func avFoundationFailure(_ error: Error?) -> DulcetPlaybackFailure {
        guard let nsError = error as NSError? else { return .engine }
        guard nsError.domain == NSURLErrorDomain else { return .engine }
        return urlFailureCode(nsError.code)
    }

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
