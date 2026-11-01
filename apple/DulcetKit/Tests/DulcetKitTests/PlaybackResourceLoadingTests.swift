import AVFoundation
import Foundation
import Network
import Testing
@testable import DulcetKit

@Suite(.serialized)
struct PlaybackResourceLoadingTests {
@Test
func rangePolicyKeepsSignatureProbeSmallAndBoundsLaterReads() {
    func range(_ offset: Int64, _ end: Int64, signature: Bool = false,
               total: Int64? = nil, toEnd: Bool = false) -> DulcetPlaybackByteRange? {
        DulcetAVAssetResourceLoaderDelegate.nextRange(
            offset: offset, requestedEndExclusive: end, requestsToEnd: toEnd,
            requiresAudioSignature: signature, contentLength: total)
    }

    // AVFoundation can ask for just two header bytes; the validator still needs its probe.
    #expect(range(0, 2, signature: true) == .init(start: 0, endInclusive: 262_143))
    #expect(range(0, 10_485_760, signature: true) == .init(start: 0, endInclusive: 262_143))
    #expect(range(0, 2, signature: true, total: 100) == .init(start: 0, endInclusive: 99))
    // A later contiguous read and a seek both use at most 1 MiB.
    #expect(range(262_144, 10_485_760, total: 10_485_760) ==
        .init(start: 262_144, endInclusive: 1_310_719))
    #expect(range(7_340_032, 10_485_760, total: 10_485_760) ==
        .init(start: 7_340_032, endInclusive: 8_388_607))
    // The requested end wins even when the representation has more bytes.
    #expect(range(7_340_032, 7_340_132, total: 10_485_760) ==
        .init(start: 7_340_032, endInclusive: 7_340_131))
    // The known resource end wins even when the request extends past it.
    #expect(range(9_699_328, 20_000_000, total: 10_485_760) ==
        .init(start: 9_699_328, endInclusive: 10_485_759))
    // All-data-to-end requests use the discovered total after the initial probe.
    #expect(range(262_144, 2, total: 10_485_760, toEnd: true) ==
        .init(start: 262_144, endInclusive: 1_310_719))
    #expect(range(100, 100, total: 1_000) == nil)
    #expect(range(1_000, 2_000, total: 1_000) == nil)
}

@Test
func unusedResourcesReleaseWithoutConstructingADelegateDuringDeinitialization() async {
    for _ in 0..<20 {
        var resource: DulcetURLSessionPlaybackResource? = makeURLSessionResource(
            validator: AcceptingPlaybackValidator(), configuration: .ephemeral)
        weak var released = resource
        resource = nil
        #expect(released == nil)
    }
    // Invalidation is asynchronous; let an illegally resurrected delegate receive its callback.
    try? await Task.sleep(for: .milliseconds(100))
}

@Test
func completedResourceReleasesItsSessionAndDelegate() async {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
    ScriptedPlaybackURLProtocol.install { _ in
        .response(status: 206, headers: ["Content-Type": "audio/mpeg", "Content-Length": "3",
            "Content-Range": "bytes 0-2/3"], body: Data("ID3".utf8))
    }
    var resource: DulcetURLSessionPlaybackResource? = makeURLSessionResource(
        validator: AcceptingPlaybackValidator(), configuration: configuration)
    weak var released = resource
    let outcome = await load(resource!)
    guard case .loaded = outcome else { Issue.record("Positive HTTP fixture did not load"); return }
    resource = nil
    for _ in 0..<100 {
        if released == nil { break }
        try? await Task.sleep(for: .milliseconds(10))
    }
    #expect(released == nil, "A finished session's delegate must not retain its resource forever")
}

@Test
func releasingAnActiveResourceCancelsItsOutstandingLoadExactlyOnce() async {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
    let capture = ResourceRetirementCapture()
    let holder = PlaybackOperationHolder()
    var resource: DulcetURLSessionPlaybackResource? = makeURLSessionResource(
        validator: AcceptingPlaybackValidator(), configuration: configuration)
    weak var released = resource
    await withCheckedContinuation { (started: CheckedContinuation<Void, Never>) in
        ScriptedPlaybackURLProtocol.install { _ in .held { started.resume() } }
        holder.operation = resource!.load(.init(range: .init(start: 0, endInclusive: 2),
            requiresAudioSignature: true)) { capture.record($0) }
    }
    resource = nil
    for _ in 0..<100 {
        if released == nil && capture.count > 0 { break }
        try? await Task.sleep(for: .milliseconds(10))
    }
    #expect(released == nil)
    #expect(capture.count == 1)
    #expect(capture.cancelled)
    holder.operation?.cancel()
    try? await Task.sleep(for: .milliseconds(20))
    #expect(capture.count == 1, "Late native cancellation must not complete a retired transfer twice")
}

@Test @MainActor
func releasingLoaderCancelsNativeRequestsAndIgnoresLateCompletion() async throws {
    let resource = SuspendedLoaderResource()
    let trace = LoaderRetirementTrace()
    let proxy = RetiringLoaderProxy(delegate: DulcetAVAssetResourceLoaderDelegate(
        resource: resource, attemptID: .init("loader-retirement"), expectedContainer: .wav,
        traceHandler: { trace.record($0) }, failureHandler: { _, _, _ in trace.recordFailure() }))
    weak var released = proxy.delegate
    let queue = DispatchQueue(label: "test.loader-retirement")
    let asset = AVURLAsset(url: URL(string: "dulcet-stream://retirement/track.wav")!)
    asset.resourceLoader.setDelegate(proxy, queue: queue)
    let player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
    defer {
        player.pause()
        player.replaceCurrentItem(with: nil)
        asset.cancelLoading()
        asset.resourceLoader.setDelegate(nil, queue: nil)
    }
    player.play()
    for _ in 0..<500 {
        if resource.loadCount > 0 { break }
        try await Task.sleep(for: .milliseconds(10))
    }
    try #require(resource.loadCount > 0, "A real native resource request must reach the loader")
    // Keep the native asset, proxy and player installed. Only the real delegate loses its owner,
    // so an AVFoundation didCancel from item removal cannot rescue missing deinit cleanup.
    queue.sync { proxy.delegate = nil }
    #expect(released == nil)
    let started = trace.startedCount
    #expect(started > 0)
    #expect(resource.cancelCount == resource.loadCount)
    #expect(trace.cancelledCount == started)
    #expect(trace.terminalCount == started)
    resource.completeLate()
    resource.cancelAgain()
    try await Task.sleep(for: .milliseconds(30))
    #expect(resource.cancelCount == resource.loadCount)
    #expect(trace.cancelledCount == started)
    #expect(trace.terminalCount == started)
    #expect(trace.failureCount == 0)
}

@Test
func partialResponsePublishesContentRangeTotalRatherThanRangeLength() async throws {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
    ScriptedPlaybackURLProtocol.install { _ in
        .response(
            status: 206,
            headers: [
                "Content-Type": "audio/mpeg",
                "Content-Length": "3",
                "Content-Range": "bytes 0-2/7550103",
            ],
            body: Data("ID3".utf8)
        )
    }
    let validator = TotalLengthValidator(expected: 7_550_103)
    let resource = makeURLSessionResource(
        validator: validator,
        configuration: configuration
    )

    let outcome = await load(resource)

    guard case let .loaded(data, information) = outcome else {
        Issue.record("valid partial response did not load")
        return
    }
    #expect(data == Data("ID3".utf8))
    #expect(information == .init(contentLength: 7_550_103, supportsByteRanges: true))
    #expect(validator.observationCount == 1)
}

@Test
func partialResponseWithoutAResourceTotalFailsBeforeValidation() async throws {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
    ScriptedPlaybackURLProtocol.install { _ in
        .response(
            status: 206,
            headers: [
                "Content-Type": "audio/mpeg",
                "Content-Length": "3",
            ],
            body: Data("ID3".utf8)
        )
    }
    let validator = TotalLengthValidator(expected: 3)
    let resource = makeURLSessionResource(
        validator: validator,
        configuration: configuration
    )

    let outcome = await load(resource)

    guard case let .failed(error, refreshReason) = outcome else {
        Issue.record("partial response without a resource total did not fail closed")
        return
    }
    #expect(error == .protocolViolation)
    #expect(refreshReason == .validationFailed)
    #expect(validator.observationCount == 0)
}

@Test
func urlSessionPlaybackResourcePassesEveryNegativeHTTPShapeToTheCoreValidator() async throws {
    let cases: [(String, ScriptedPlaybackURLProtocol.Response, DulcetPlaybackFailure)] = [
        (
            "envelope delivered at HTTP 200",
            .response(
                status: 200,
                headers: ["Content-Type": "application/json"],
                body: Data(#"{"subsonic-response":{"status":"failed","error":{"code":40}}}"#.utf8)
            ),
            .authentication
        ),
        (
            "bare HTTP error with no envelope",
            .response(
                status: 503,
                headers: ["Content-Type": "text/plain"],
                body: Data("temporarily unavailable".utf8)
            ),
            .sourceUnavailable
        ),
        (
            "truncated successful audio body",
            .response(
                status: 200,
                headers: ["Content-Type": "audio/mpeg", "Content-Length": "2"],
                body: Data([0x49, 0x44])
            ),
            .protocolViolation
        ),
    ]

    for (label, response, expectedFailure) in cases {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
        ScriptedPlaybackURLProtocol.install { _ in response }
        let validator = NegativeShapeValidator(expectedLabel: label)
        let resource = makeURLSessionResource(
            validator: validator,
            configuration: configuration
        )

        let outcome = await load(resource)
        guard case let .failed(error, refreshReason) = outcome else {
            Issue.record("\(label) did not fail closed")
            continue
        }
        #expect(error == expectedFailure)
        #expect(refreshReason == (expectedFailure == .authentication ? .unauthorized : .validationFailed))
        #expect(validator.observationCount == 1)
    }
}

@Test
func aBodyEndedByALostConnectionReachesTheValidatorWithEveryByteReceived() async throws {
    // A body short of its declared length ends in -1005 after URLSession delivers what arrived
    // (OBSERVED on macOS against Navidrome 0.63.2: 24,576 declared, 24,012 delivered -- a prefix of
    // a 24,639-byte body, the estimate having undershot). The core validator decides what that is;
    // since no request asks for an estimate it treats every declared length as exact and refuses it
    // (spec §12.5). This pins only that the loader hands it every byte.
    let body = Data("ID3".utf8) + Data(repeating: 0xFF, count: 61)
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
    ScriptedPlaybackURLProtocol.install { _ in
        .endedByLostConnection(
            status: 200,
            headers: ["Content-Type": "audio/mpeg", "Content-Length": "96"],
            body: body
        )
    }
    let validator = ShortBodyValidator(verdict: .accepted(
        contentInformation: .init(contentLength: Int64(body.count), supportsByteRanges: false)
    ))
    let resource = makeURLSessionResource(validator: validator, configuration: configuration)

    let outcome = await load(resource, range: .init(start: 0, endInclusive: 262_143))

    guard case let .loaded(data, information) = outcome else {
        Issue.record("a body ended by a lost connection did not reach the validator: \(outcome)")
        return
    }
    #expect(data == body)
    #expect(information == .init(contentLength: Int64(body.count), supportsByteRanges: false))
    #expect(validator.observed.map(\.body) == [body])
    #expect(validator.observed.map(\.contentLength) == [96])
}

@Test
func aLostConnectionStaysATransportFailureWhenTheValidatorRefusesTheShortBody() async throws {
    // An exact length cut short is truncation; the validator refuses it, and the load reports the
    // lost connection it was, not the validator's verdict on a body that never completed.
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
    ScriptedPlaybackURLProtocol.install { _ in
        .endedByLostConnection(
            status: 200,
            headers: ["Content-Type": "audio/mpeg", "Content-Length": "96"],
            body: Data("ID3".utf8)
        )
    }
    let validator = ShortBodyValidator(verdict: .rejected(error: .protocolViolation, refreshReason: .validationFailed))
    let resource = makeURLSessionResource(validator: validator, configuration: configuration)

    let outcome = await load(resource, range: .init(start: 0, endInclusive: 262_143))

    guard case let .failed(error, refreshReason) = outcome else {
        Issue.record("a refused short body did not fail")
        return
    }
    #expect(error == .transport)
    #expect(refreshReason == nil)
    #expect(validator.observed.count == 1)
}

@Test
func aLostConnectionWithNoBodyNoDeclaredLengthOrAPartialAnswerFailsWithoutValidation() async throws {
    let cases: [(String, Int, [String: String], Data)] = [
        ("no body byte delivered", 200, ["Content-Type": "audio/mpeg", "Content-Length": "96"], Data()),
        // A chunked 200 declares no length, so a real drop could not be told from the end.
        ("a 200 that declared no length", 200, ["Content-Type": "audio/mpeg"], Data("ID3".utf8)),
        (
            "a partial answer cut short",
            206,
            ["Content-Type": "audio/mpeg", "Content-Length": "96", "Content-Range": "bytes 0-95/960"],
            Data("ID3".utf8)
        ),
    ]
    for (label, status, headers, body) in cases {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
        ScriptedPlaybackURLProtocol.install { _ in
            .endedByLostConnection(status: status, headers: headers, body: body)
        }
        let validator = ShortBodyValidator(verdict: .accepted(
            contentInformation: .init(contentLength: 3, supportsByteRanges: false)
        ))
        let resource = makeURLSessionResource(validator: validator, configuration: configuration)

        let outcome = await load(resource, range: .init(start: 0, endInclusive: 262_143))

        guard case let .failed(error, refreshReason) = outcome else {
            Issue.record("\(label) did not fail")
            continue
        }
        #expect(error == .transport, "\(label)")
        #expect(refreshReason == nil, "\(label)")
        #expect(validator.observed.isEmpty, "\(label)")
    }
}

@Test
func aCompleteChunkedAnswerToARangedReadReachesTheValidatorWithNoDeclaredLength() async throws {
    // What a cold Navidrome transcode answers once the request asks for no estimate (spec §12.5,
    // §28 2026-10-04): HTTP 200, no Content-Length, the Range ignored, every byte, and a clean end.
    // OBSERVED against Navidrome 0.63.2: 24,639 bytes for "Dulcet Health Probe" at 96 kbps.
    let body = Data("ID3".utf8) + Data(repeating: 0xFF, count: 24_639 - 3)
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
    ScriptedPlaybackURLProtocol.install { _ in
        .response(
            status: 200,
            headers: ["Content-Type": "audio/mpeg", "Accept-Ranges": "none"],
            body: body
        )
    }
    let validator = ShortBodyValidator(verdict: .accepted(
        contentInformation: .init(contentLength: Int64(body.count), supportsByteRanges: false)
    ))
    let resource = makeURLSessionResource(validator: validator, configuration: configuration)

    let outcome = await load(resource, range: .init(start: 0, endInclusive: 262_143))

    guard case let .loaded(data, information) = outcome else {
        Issue.record("a complete chunked answer did not load: \(outcome)")
        return
    }
    #expect(data == body)
    #expect(information == .init(contentLength: Int64(body.count), supportsByteRanges: false))
    #expect(validator.observed.map(\.body) == [body])
    #expect(validator.observed.map(\.contentLength) == [nil])
    #expect(validator.observed.map(\.statusCode) == [200])
}

@Test
func credentialBearingCrossOriginRedirectStripsCanariesBeforeTheTargetRequest() async throws {
    let usernameCanary = "redirect-user-canary"
    let tokenCanary = "redirect-token-canary"
    let saltCanary = "redirect-salt-canary"
    let source = URL(
        string: "https://source.invalid/stream?u=\(usernameCanary)&t=\(tokenCanary)&s=\(saltCanary)&v=1.16.1"
    )!
    let target = URL(
        string: "https://object-store.invalid/audio.mp3?u=\(usernameCanary)&t=\(tokenCanary)&s=\(saltCanary)&object-signature=keep-me"
    )!
    let observations = RedirectRequestObservations()
    ScriptedPlaybackURLProtocol.install { request in
        observations.append(request.url!)
        if request.url?.host == source.host {
            return .redirect(target)
        }
        return .response(
            status: 206,
            headers: [
                "Content-Type": "audio/mpeg",
                "Content-Length": "3",
                "Content-Range": "bytes 0-2/3",
                "Accept-Ranges": "bytes",
            ],
            body: Data("ID3".utf8)
        )
    }
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [ScriptedPlaybackURLProtocol.self]
    let resource = DulcetURLSessionPlaybackResource(
        expectedContainer: .mp3,
        authorizer: FixedPlaybackAuthorizer(url: source),
        validator: AcceptingPlaybackValidator(),
        redirectEvaluator: CredentialStrippingRedirectEvaluator(),
        sessionConfiguration: configuration
    )

    let outcome = await load(resource)
    guard case let .loaded(data, information) = outcome else {
        Issue.record("credential-stripped redirect did not reach the validated target response")
        return
    }
    #expect(data == Data("ID3".utf8))
    #expect(information == .init(contentLength: 3, supportsByteRanges: true))
    let targetURLs = observations.urls.filter { $0.host == target.host }
    #expect(targetURLs.count == 1)
    let renderedTargets = targetURLs.map(\.absoluteString)
    for canary in [usernameCanary, tokenCanary, saltCanary] {
        #expect(renderedTargets.allSatisfy { !$0.contains(canary) })
    }
    #expect(renderedTargets.allSatisfy { $0.contains("object-signature=keep-me") })
}
}

private func makeURLSessionResource(
    validator: any DulcetPlaybackResponseValidating,
    configuration: URLSessionConfiguration
) -> DulcetURLSessionPlaybackResource {
    DulcetURLSessionPlaybackResource(
        expectedContainer: .mp3,
        authorizer: FixedPlaybackAuthorizer(
            url: URL(string: "https://source.invalid/stream?u=canary-user&t=canary-token&s=canary-salt")!
        ),
        validator: validator,
        redirectEvaluator: CredentialStrippingRedirectEvaluator(),
        sessionConfiguration: configuration
    )
}

private func load(
    _ resource: DulcetURLSessionPlaybackResource,
    range: DulcetPlaybackByteRange = .init(start: 0, endInclusive: 2)
) async -> DulcetPlaybackResourceLoadOutcome {
    let holder = PlaybackOperationHolder()
    return await withCheckedContinuation { continuation in
        holder.operation = resource.load(
            DulcetPlaybackResourceLoadRequest(
                range: range,
                requiresAudioSignature: true
            )
        ) { outcome in
            withExtendedLifetime(holder) {
                continuation.resume(returning: outcome)
            }
        }
    }
}

private final class PlaybackOperationHolder: @unchecked Sendable {
    var operation: (any DulcetPlaybackResourceLoadOperation)?
}

private final class ImmediatePlaybackOperation: DulcetPlaybackResourceLoadOperation,
    @unchecked Sendable {
    func cancel() {}
}

private final class FixedPlaybackAuthorizer: DulcetPlaybackRequestAuthorizing, @unchecked Sendable {
    let url: URL

    init(url: URL) {
        self.url = url
    }

    func authorize(
        range: DulcetPlaybackByteRange,
        completion: @escaping @Sendable (Result<DulcetPlaybackAuthorizedRequest, DulcetPlaybackFailure>) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        var request = URLRequest(url: url)
        request.setValue("bytes=\(range.start)-\(range.endInclusive)", forHTTPHeaderField: "Range")
        completion(.success(DulcetPlaybackAuthorizedRequest(request: request)))
        return ImmediatePlaybackOperation()
    }
}

private final class NegativeShapeValidator: DulcetPlaybackResponseValidating, @unchecked Sendable {
    private let lock = NSLock()
    private let expectedLabel: String
    private var observations = 0

    init(expectedLabel: String) {
        self.expectedLabel = expectedLabel
    }

    var observationCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return observations
    }

    func validate(
        response: DulcetPlaybackHTTPResponse,
        expectedContainer: DulcetAudioContainer,
        requestedRange: DulcetPlaybackByteRange,
        requiresAudioSignature: Bool
    ) -> DulcetPlaybackResponseValidation {
        lock.lock()
        observations += 1
        lock.unlock()
        switch expectedLabel {
        case "envelope delivered at HTTP 200":
            #expect(response.statusCode == 200)
            #expect(String(decoding: response.body, as: UTF8.self).contains("subsonic-response"))
            return .rejected(error: .authentication, refreshReason: .unauthorized)
        case "bare HTTP error with no envelope":
            #expect(response.statusCode == 503)
            #expect(!String(decoding: response.body, as: UTF8.self).contains("subsonic-response"))
            return .rejected(error: .sourceUnavailable, refreshReason: .validationFailed)
        case "truncated successful audio body":
            #expect(response.statusCode == 200)
            #expect(response.body.count == 2)
            #expect(requiresAudioSignature)
            return .rejected(error: .protocolViolation, refreshReason: .validationFailed)
        default:
            Issue.record("unhandled negative-shape fixture")
            return .rejected(error: .protocolViolation, refreshReason: .validationFailed)
        }
    }
}

private final class AcceptingPlaybackValidator: DulcetPlaybackResponseValidating,
    @unchecked Sendable {
    func validate(
        response: DulcetPlaybackHTTPResponse,
        expectedContainer: DulcetAudioContainer,
        requestedRange: DulcetPlaybackByteRange,
        requiresAudioSignature: Bool
    ) -> DulcetPlaybackResponseValidation {
        #expect(response.statusCode == 206)
        #expect(response.body == Data("ID3".utf8))
        return .accepted(
            contentInformation: .init(contentLength: 3, supportsByteRanges: true)
        )
    }
}

private final class ShortBodyValidator: DulcetPlaybackResponseValidating, @unchecked Sendable {
    private let lock = NSLock()
    private let verdict: DulcetPlaybackResponseValidation
    private var responses: [DulcetPlaybackHTTPResponse] = []

    init(verdict: DulcetPlaybackResponseValidation) {
        self.verdict = verdict
    }

    var observed: [DulcetPlaybackHTTPResponse] {
        lock.lock()
        defer { lock.unlock() }
        return responses
    }

    func validate(
        response: DulcetPlaybackHTTPResponse,
        expectedContainer: DulcetAudioContainer,
        requestedRange: DulcetPlaybackByteRange,
        requiresAudioSignature: Bool
    ) -> DulcetPlaybackResponseValidation {
        lock.lock()
        responses.append(response)
        lock.unlock()
        return verdict
    }
}

private final class TotalLengthValidator: DulcetPlaybackResponseValidating, @unchecked Sendable {
    private let lock = NSLock()
    private let expected: Int64
    private var observations = 0

    init(expected: Int64) {
        self.expected = expected
    }

    var observationCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return observations
    }

    func validate(
        response: DulcetPlaybackHTTPResponse,
        expectedContainer: DulcetAudioContainer,
        requestedRange: DulcetPlaybackByteRange,
        requiresAudioSignature: Bool
    ) -> DulcetPlaybackResponseValidation {
        lock.lock()
        observations += 1
        lock.unlock()
        #expect(response.contentLength == expected)
        return .accepted(
            contentInformation: .init(contentLength: expected, supportsByteRanges: true)
        )
    }
}

private final class CredentialStrippingRedirectEvaluator: DulcetPlaybackRedirectEvaluating,
    @unchecked Sendable {
    func evaluate(
        sourceURL: URL,
        proposedURL: URL,
        redirectsAlreadyFollowed: Int
    ) -> DulcetPlaybackRedirectDecision {
        guard sourceURL.scheme == "https" || proposedURL.scheme != "http" else {
            return .reject(.transport)
        }
        if sourceURL.host == proposedURL.host {
            return .followPreservingRequest
        }
        return .followStrippingQueryItems(["u", "t", "s"])
    }
}

private final class RedirectRequestObservations: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [URL] = []

    func append(_ url: URL) {
        lock.lock()
        storage.append(url)
        lock.unlock()
    }

    var urls: [URL] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }
}

private final class ScriptedPlaybackURLProtocol: URLProtocol, @unchecked Sendable {
    enum Response: Sendable {
        case response(status: Int, headers: [String: String], body: Data)
        /// The head and `body`, then the connection lost before the declared length arrived.
        case endedByLostConnection(status: Int, headers: [String: String], body: Data)
        case redirect(URL)
        case held(@Sendable () -> Void)
    }

    private static let lock = NSLock()
    private nonisolated(unsafe) static var handler: (@Sendable (URLRequest) -> Response)?

    static func install(_ handler: @escaping @Sendable (URLRequest) -> Response) {
        lock.lock()
        self.handler = handler
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        let handler = Self.handler
        Self.lock.unlock()
        guard let handler, let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        switch handler(request) {
        case let .held(started):
            started()
        case let .redirect(target):
            let response = HTTPURLResponse(
                url: url,
                statusCode: 302,
                httpVersion: "HTTP/1.1",
                headerFields: ["Location": target.absoluteString]
            )!
            client?.urlProtocol(
                self,
                wasRedirectedTo: URLRequest(url: target),
                redirectResponse: response
            )
        case let .response(status, headers, body):
            let response = HTTPURLResponse(
                url: url,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: headers
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        case let .endedByLostConnection(status, headers, body):
            let response = HTTPURLResponse(
                url: url,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: headers
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            guard !body.isEmpty else {
                client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
                return
            }
            client?.urlProtocol(self, didLoad: body)
            // The loss is held until the session has handed the body over: an end close behind
            // bytes not yet handed over makes the session discard them (spec §28, 2026-10-01),
            // which no delegate can recover and which is not what these tests are about.
            failWhenDelivered(body.count, deadline: Date().addingTimeInterval(5))
        }
    }

    private func failWhenDelivered(_ count: Int, deadline: Date) {
        if (task?.countOfBytesReceived ?? 0) >= Int64(count) || Date() >= deadline {
            client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
            return
        }
        RunLoop.current.perform { [self] in
            Thread.sleep(forTimeInterval: 0.005)
            failWhenDelivered(count, deadline: deadline)
        }
    }

    override func stopLoading() {}
}

private final class ResourceRetirementCapture: @unchecked Sendable {
    private let lock = NSLock()
    private var outcomes: [DulcetPlaybackResourceLoadOutcome] = []
    func record(_ outcome: DulcetPlaybackResourceLoadOutcome) { lock.withLock { outcomes.append(outcome) } }
    var count: Int { lock.withLock { outcomes.count } }
    var cancelled: Bool { lock.withLock { outcomes.count == 1 && { if case .cancelled = outcomes[0] { true } else { false } }() } }
}

private final class RetiringLoaderProxy: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    // Accessed only before installation or on the resource-loader serial queue.
    var delegate: DulcetAVAssetResourceLoaderDelegate?
    init(delegate: DulcetAVAssetResourceLoaderDelegate) { self.delegate = delegate }
    func resourceLoader(_ loader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        delegate?.resourceLoader(loader, shouldWaitForLoadingOfRequestedResource: request) ?? false
    }
    func resourceLoader(_ loader: AVAssetResourceLoader, didCancel request: AVAssetResourceLoadingRequest) {
        delegate?.resourceLoader(loader, didCancel: request)
    }
}

private final class SuspendedLoaderResource: DulcetPlaybackResourceLoading, @unchecked Sendable {
    private let lock = NSLock()
    private var completions: [@Sendable (DulcetPlaybackResourceLoadOutcome) -> Void] = []
    private var operations: [SuspendedLoaderOperation] = []
    var description: String { "SuspendedLoaderResource" }
    var loadCount: Int { lock.withLock { operations.count } }
    var cancelCount: Int { lock.withLock { operations.reduce(0) { $0 + $1.cancelCount } } }
    func load(_ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        let operation = SuspendedLoaderOperation()
        lock.withLock { completions.append(completion); operations.append(operation) }
        return operation
    }
    func completeLate() {
        for completion in lock.withLock({ completions }) {
            completion(.cancelled)
            completion(.loaded(data: Data("late".utf8),
                contentInformation: .init(contentLength: 4, supportsByteRanges: true)))
        }
    }
    func cancelAgain() { for operation in lock.withLock({ operations }) { operation.cancel() } }
}

private final class SuspendedLoaderOperation: DulcetPlaybackResourceLoadOperation, @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    var cancelCount: Int { lock.withLock { cancelled ? 1 : 0 } }
    func cancel() { lock.withLock { cancelled = true } }
}

private final class LoaderRetirementTrace: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [DulcetPlaybackResourceLoaderTraceEvent] = []
    private var failures = 0
    func record(_ event: DulcetPlaybackResourceLoaderTraceEvent) { lock.withLock { events.append(event) } }
    func recordFailure() { lock.withLock { failures += 1 } }
    var failureCount: Int { lock.withLock { failures } }
    var startedCount: Int { lock.withLock { events.filter { if case .started = $0 { true } else { false } }.count } }
    var cancelledCount: Int { lock.withLock { events.filter { if case .cancelled = $0 { true } else { false } }.count } }
    var terminalCount: Int {
        lock.withLock { events.filter {
            switch $0 { case .finished, .cancelled, .failed: true; default: false }
        }.count }
    }
}


/// These fixtures close the actual TCP connection, rather than synthesizing a URL loading error.
/// The accepting validator makes HTTP framing the only possible reason for rejecting the body.
@Suite(.serialized)
struct ChunkedMediaTransferTests {
    @Test(arguments: ChunkedMediaEnding.allCases, ["/rest/stream.view", "/api/media/song"])
    func playbackAndCacheRequireTheFinalChunk(ending: ChunkedMediaEnding, path: String) async throws {
        let server = try ChunkedMediaServer(ending: ending)
        defer { server.stop() }
        let observations = ChunkedMediaObservations()
        let resource = DulcetURLSessionPlaybackResource(expectedContainer: .mp3,
            authorizer: ChunkedMediaAuthorizer(url: server.url(path)), validator: ChunkedMediaValidator(),
            redirectEvaluator: ChunkedMediaRedirectEvaluator())
        let cached = DulcetCachingPlaybackResource(source: resource) { _, data, _ in observations.record(data) }
        let holder = ChunkedMediaOperationHolder()
        let outcome = await withCheckedContinuation { continuation in
            holder.operation = cached.load(.init(range: .init(start: 0, endInclusive: 262_143),
                requiresAudioSignature: true)) { continuation.resume(returning: $0) }
        }
        withExtendedLifetime(holder) {}
        if ending.isComplete {
            guard case let .loaded(data, information) = outcome else {
                Issue.record("complete chunked playback failed")
                return
            }
            #expect(data == ChunkedMediaServer.body)
            #expect(information.contentLength == Int64(data.count))
            #expect(observations.bytes == ChunkedMediaServer.body)
        } else {
            guard case .failed(error: .transport, refreshReason: nil) = outcome else {
                Issue.record("incomplete chunked playback was accepted")
                return
            }
            #expect(observations.bytes.isEmpty, "incomplete media must never reach the cache writer")
        }
        #expect(server.requestCount > 0, "the real socket fixture must receive the media request")
    }

    @Test
    func strictTransportPreservesTheAuthorizedVirtualHost() async throws {
        let server = try ChunkedMediaServer(ending: .complete)
        defer { server.stop() }
        let resource = DulcetURLSessionPlaybackResource(expectedContainer: .mp3,
            authorizer: ChunkedMediaAuthorizer(url: server.url("/rest/stream.view"), host: "media.example.invalid"),
            validator: ChunkedMediaValidator(), redirectEvaluator: ChunkedMediaRedirectEvaluator())
        guard case .loaded = await load(resource) else { Issue.record("virtual host request failed"); return }
        #expect(server.requestHeaders.contains { $0.contains("Host: media.example.invalid\r\n") })
    }

    @Test
    func explicitProxyPolicyDoesNotBecomeADirectMediaConnection() async throws {
        let server = try ChunkedMediaServer(ending: .complete)
        defer { server.stop() }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.connectionProxyDictionary = ["HTTPEnable": 1, "HTTPProxy": "proxy.example.invalid", "HTTPPort": 8080]
        let resource = DulcetURLSessionPlaybackResource(expectedContainer: .mp3,
            authorizer: ChunkedMediaAuthorizer(url: server.url("/rest/stream.view")),
            validator: ChunkedMediaValidator(), redirectEvaluator: ChunkedMediaRedirectEvaluator(),
            sessionConfiguration: configuration)
        guard case .failed(error: .transport, refreshReason: nil) = await load(resource) else {
            Issue.record("an unsupported proxy did not fail closed"); return
        }
        #expect(server.requestCount == 0)
    }

    @Test(arguments: [false, true])
    func strictTransportRoutesRedirectsThroughTheExistingCredentialPolicy(crossOrigin: Bool) async throws {
        let source = try ChunkedMediaServer(ending: .complete)
        let other = try ChunkedMediaServer(ending: .complete)
        defer { source.stop(); other.stop() }
        let destination = crossOrigin ? other : source
        let target = destination.url("/rest/stream.view?u=redirect-user-canary&t=redirect-token-canary&s=redirect-salt-canary&object-signature=keep")
        source.redirect(to: target)
        let resource = DulcetURLSessionPlaybackResource(expectedContainer: .mp3,
            authorizer: FixedPlaybackAuthorizer(url: source.url("/redirect")),
            validator: ChunkedMediaValidator(), redirectEvaluator: LoopbackCredentialRedirectEvaluator())
        let outcome = await load(resource)
        guard case let .loaded(data, _) = outcome else { Issue.record("strict redirect did not load: \(outcome); sourceRequests=\(source.requestCount) destinationRequests=\(destination.requestCount)"); return }
        #expect(data == ChunkedMediaServer.body)
        let requests = destination.requestTargets.filter { !$0.hasPrefix("/redirect") }
        #expect(requests.count == 1)
        #expect(requests[0].contains("object-signature=keep"))
        for canary in ["redirect-user-canary", "redirect-token-canary", "redirect-salt-canary"] {
            #expect(requests[0].contains(canary) == !crossOrigin)
        }
    }

    /// A source protocol hands its client a redirect, then retires without response or
    /// terminal callbacks that could accidentally complete the client's redirected task.
    @Test
    func strictRedirectRetiresTheSourceWithoutCompletingTheTask() throws {
        let source = try ChunkedMediaServer(ending: .complete)
        let target = try ChunkedMediaServer(ending: .complete)
        defer { source.stop(); target.stop() }
        source.redirect(to: target.url("/rest/stream.view"))
        let client = StrictRedirectClient()
        let proto = DulcetStrictMediaURLProtocol(request: URLRequest(url: source.url("/redirect")),
                                                cachedResponse: nil, client: client)
        proto.startLoading()
        #expect(client.redirected.wait(timeout: .now() + 5) == .success)
        proto.queue.sync {} // the entire redirect callback batch has returned
        #expect(client.events == ["redirect"], "source callbacks must not finish the redirected task")
        proto.stopLoading()
        proto.queue.sync {}
        #expect(client.events == ["redirect"], "retiring the source must remain silent")
        #expect(source.requestCount == 1)
        #expect(target.requestCount == 0, "the URL loading client owns the destination request")
    }

    /// A redirected task must load the media body even when the target response is delayed.
    @Test(arguments: [false, true])
    func strictRedirectWaitsForTheDestinationResponse(crossOrigin: Bool) async throws {
        let source = try ChunkedMediaServer(ending: .complete)
        let other = try ChunkedMediaServer(ending: .complete)
        defer { source.stop(); other.stop() }
        let destination = crossOrigin ? other : source
        destination.mediaDelay = 0.2
        source.redirect(to: destination.url("/rest/stream.view?u=user-canary&t=token-canary&s=salt-canary&object-signature=keep"))
        let evaluator = LoopbackCredentialRedirectEvaluator()
        let resource = DulcetURLSessionPlaybackResource(expectedContainer: .mp3,
            authorizer: FixedPlaybackAuthorizer(url: source.url("/redirect")),
            validator: ChunkedMediaValidator(), redirectEvaluator: evaluator)
        let outcome = await load(resource)
        guard case let .loaded(data, _) = outcome else {
            Issue.record("held redirect failed: \(outcome); decisions=\(evaluator.decisionCount) source=\(source.requestCount) destination=\(destination.requestCount)")
            return
        }
        #expect(data == ChunkedMediaServer.body)
        #expect(evaluator.decisionCount == 1)
        let targets = destination.requestTargets.filter { !$0.hasPrefix("/redirect") }
        #expect(targets.count == 1)
        #expect(targets.first?.contains("object-signature=keep") == true)
        for canary in ["user-canary", "token-canary", "salt-canary"] {
            #expect(targets.first?.contains(canary) == !crossOrigin)
        }
    }

    @Test
    func activeTransfersCanLastLongerThanTheirIdleTimeout() async throws {
        let server = try ChunkedMediaServer(ending: .complete, trickleInterval: 0.12)
        defer { server.stop() }
        let resource = DulcetURLSessionPlaybackResource(expectedContainer: .mp3,
            authorizer: ChunkedMediaAuthorizer(url: server.url("/rest/stream.view"), timeout: 0.3),
            validator: ChunkedMediaValidator(), redirectEvaluator: ChunkedMediaRedirectEvaluator())
        let start = ContinuousClock.now
        guard case let .loaded(data, _) = await load(resource) else { Issue.record("an active transfer hit a total-duration deadline"); return }
        #expect(data == ChunkedMediaServer.body)
        #expect(start.duration(to: .now) > .seconds(1))
    }

    @Test
    func rejectedRedirectFailsWithoutFollowingTheTarget() async throws {
        let source = try ChunkedMediaServer(ending: .complete)
        let target = try ChunkedMediaServer(ending: .complete)
        defer { source.stop(); target.stop() }
        source.redirect(to: target.url("/rest/stream.view"))
        let resource = DulcetURLSessionPlaybackResource(expectedContainer: .mp3,
            authorizer: ChunkedMediaAuthorizer(url: source.url("/redirect")),
            validator: ChunkedMediaValidator(), redirectEvaluator: ChunkedMediaRedirectEvaluator())
        guard case .failed(error: .transport, refreshReason: nil) = await load(resource) else {
            Issue.record("a rejected media redirect must fail without waiting for its body"); return
        }
        #expect(target.requestCount == 0)
    }

    @Test(arguments: ChunkedMediaEnding.allCases, ["/rest/download.view", "/api/media/song"])
    func downloadDelegateRequiresTheFinalChunk(ending: ChunkedMediaEnding, path: String) async throws {
        let server = try ChunkedMediaServer(ending: ending)
        defer { server.stop() }
        let delegate = ChunkedDownloadDelegate()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        DulcetMediaTransport.requireCompleteHTTPBodies(in: configuration)
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let result = await withCheckedContinuation { continuation in
            delegate.completion = { continuation.resume(returning: $0) }
            session.downloadTask(with: server.url(path)).resume()
        }
        if ending.isComplete {
            #expect(result.body == ChunkedMediaServer.body)
            #expect(result.errorCode == nil)
        } else {
            #expect(result.body == nil, "the delegate must not deliver a promotable download file")
            #expect(result.errorCode == NSURLErrorNetworkConnectionLost)
        }
        #expect(server.requestCount > 0, "the real socket fixture must receive the media request")
    }
}

enum ChunkedMediaEnding: CaseIterable, Sendable {
    case complete, chunkBoundary, midChunk, completeWithExtensionsAndTrailers, incompleteTrailers
    var isComplete: Bool { self == .complete || self == .completeWithExtensionsAndTrailers }
}

private final class ChunkedMediaOperationHolder: @unchecked Sendable {
    var operation: (any DulcetPlaybackResourceLoadOperation)?
}

private final class ChunkedMediaObservations: @unchecked Sendable {
    private let lock = NSLock()
    private var storage = Data()
    var bytes: Data { lock.withLock { storage } }
    func record(_ data: Data) { lock.withLock { storage.append(data) } }
}

private final class ChunkedMediaAuthorizer: DulcetPlaybackRequestAuthorizing, @unchecked Sendable {
    let url: URL
    let host: String?
    let timeout: TimeInterval
    init(url: URL, host: String? = nil, timeout: TimeInterval = 5) { self.url = url; self.host = host; self.timeout = timeout }
    func authorize(range: DulcetPlaybackByteRange,
        completion: @escaping @Sendable (Result<DulcetPlaybackAuthorizedRequest, DulcetPlaybackFailure>) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        if let host { request.setValue(host, forHTTPHeaderField: "Host") }
        request.setValue("bytes=\(range.start)-\(range.endInclusive)", forHTTPHeaderField: "Range")
        completion(.success(.init(request: request)))
        return ChunkedMediaOperation()
    }
}
private final class ChunkedMediaOperation: DulcetPlaybackResourceLoadOperation, @unchecked Sendable {
    func cancel() {}
}
private final class ChunkedMediaValidator: DulcetPlaybackResponseValidating, @unchecked Sendable {
    func validate(response: DulcetPlaybackHTTPResponse, expectedContainer: DulcetAudioContainer,
        requestedRange: DulcetPlaybackByteRange, requiresAudioSignature: Bool) -> DulcetPlaybackResponseValidation {
        .accepted(contentInformation: .init(contentLength: Int64(response.body.count), supportsByteRanges: false))
    }
}
private final class ChunkedMediaRedirectEvaluator: DulcetPlaybackRedirectEvaluating, @unchecked Sendable {
    func evaluate(sourceURL: URL, proposedURL: URL, redirectsAlreadyFollowed: Int) -> DulcetPlaybackRedirectDecision {
        .reject(.transport)
    }
}

private struct ChunkedDownloadResult: Sendable {
    let body: Data?
    let errorCode: Int?
}
private final class ChunkedDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    var completion: (@Sendable (ChunkedDownloadResult) -> Void)?
    private var body: Data?
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        body = try? Data(contentsOf: location)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        completion?(.init(body: body, errorCode: (error as NSError?)?.code))
    }
}

private final class ChunkedMediaServer: @unchecked Sendable {
    static let body = Data("ID3chunked-media-body".utf8)
    private let ending: ChunkedMediaEnding
    private let trickleInterval: TimeInterval?
    private let listener: NWListener
    private let queue = DispatchQueue(label: "com.legitimateapps.dulcet.tests.chunked-media")
    private let lock = NSLock()
    private var requests = 0
    private var targets: [String] = []
    private var receivedHeaders: [String] = []
    var requestHeaders: [String] { lock.withLock { receivedHeaders } }
    var mediaDelay: TimeInterval = 0
    private var redirectTarget: URL?
    var requestTargets: [String] { lock.withLock { targets } }
    func redirect(to target: URL) { lock.withLock { redirectTarget = target } }
    var requestCount: Int { lock.withLock { requests } }
    func url(_ path: String) -> URL { URL(string: "http://127.0.0.1:\(listener.port!.rawValue)\(path)")! }
    init(ending: ChunkedMediaEnding, trickleInterval: TimeInterval? = nil) throws {
        self.trickleInterval = trickleInterval
        self.ending = ending
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
        listener.newConnectionHandler = { [weak self] connection in
            connection.start(queue: self?.queue ?? .global())
            self?.receive(connection, accumulated: Data())
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success else {
            listener.cancel()
            throw URLError(.cannotConnectToHost)
        }
    }
    func stop() { listener.cancel() }
    private func receive(_ connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, done, error in
            guard let self else { connection.cancel(); return }
            var request = accumulated
            if let data { request.append(data) }
            guard request.range(of: Data("\r\n\r\n".utf8)) != nil else {
                if done || error != nil { connection.cancel() } else { receive(connection, accumulated: request) }
                return
            }
            let target = String(decoding: request, as: UTF8.self).split(separator: " ").dropFirst().first.map(String.init) ?? ""
            let redirect = lock.withLock {
                requests += 1; targets.append(target)
                receivedHeaders.append(String(decoding: request, as: UTF8.self))
                return target.hasPrefix("/redirect") ? redirectTarget : nil
            }
            if let redirect {
                let wire = Data("HTTP/1.1 302 Found\r\nLocation: \(redirect.absoluteString)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
                connection.send(content: wire, contentContext: .finalMessage, isComplete: true,
                    completion: .contentProcessed { _ in connection.cancel() })
                return
            }
            var wire = Data("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".utf8)
            if ending == .completeWithExtensionsAndTrailers || ending == .incompleteTrailers {
                wire.append(Data("3;first=chunk\r\nID3\r\n\(String(Self.body.count - 3, radix: 16));last=chunk\r\n".utf8))
                wire.append(Self.body.dropFirst(3))
                wire.append(Data("\r\n0\r\nX-Media-Test: complete\r\n".utf8))
                if ending.isComplete { wire.append(Data("\r\n".utf8)) }
                connection.send(content: wire, contentContext: .finalMessage, isComplete: true,
                    completion: .contentProcessed { _ in connection.cancel() })
                return
            }
            wire.append(Data("\(String(Self.body.count, radix: 16))\r\n".utf8))
            wire.append(ending == .midChunk ? Self.body.prefix(5) : Self.body)
            if ending != .midChunk { wire.append(Data("\r\n".utf8)) }
            if ending.isComplete { wire.append(Data("0\r\n\r\n".utf8)) }
            if let trickleInterval {
                let header = Data(wire.prefix(wire.count - Self.body.count - 7))
                connection.send(content: header, completion: .contentProcessed { [weak self] error in
                    guard error == nil, let self else { connection.cancel(); return }
                    sendTrickle(Self.body, connection: connection, interval: trickleInterval)
                })
            }
            else {
                let delayedWire = wire
                queue.asyncAfter(deadline: .now() + mediaDelay) {
                    connection.send(content: delayedWire, contentContext: .finalMessage, isComplete: true,
                        completion: .contentProcessed { _ in connection.cancel() })
                }
            }
        }
    }
    private func sendTrickle(_ data: Data, connection: NWConnection, interval: TimeInterval) {
        let count = min(2, data.count)
        let rest = Data(data.dropFirst(count))
        var piece = Data(data.prefix(count))
        if rest.isEmpty { piece.append(Data("\r\n0\r\n\r\n".utf8)) }
        connection.send(content: piece, contentContext: rest.isEmpty ? .finalMessage : .defaultMessage,
            isComplete: true, completion: .contentProcessed { [weak self] error in
                guard error == nil, !rest.isEmpty, let self else { connection.cancel(); return }
                queue.asyncAfter(deadline: .now() + interval) { self.sendTrickle(rest, connection: connection, interval: interval) }
            })
    }
}

private final class LoopbackCredentialRedirectEvaluator: DulcetPlaybackRedirectEvaluating, @unchecked Sendable {
    private let lock = NSLock()
    private var decisions = 0
    var decisionCount: Int { lock.withLock { decisions } }
    func evaluate(sourceURL: URL, proposedURL: URL, redirectsAlreadyFollowed: Int) -> DulcetPlaybackRedirectDecision {
        lock.withLock { decisions += 1 }
        guard redirectsAlreadyFollowed < 8, !(sourceURL.scheme == "https" && proposedURL.scheme == "http") else { return .reject(.transport) }
        if sourceURL.host == proposedURL.host && sourceURL.port == proposedURL.port && sourceURL.scheme == proposedURL.scheme { return .followPreservingRequest }
        return .followStrippingQueryItems(["u", "t", "s"])
    }
}

/// Records the real strict protocol's client callbacks without URLSession's timing-dependent
/// filtering. The protocol's serial queue supplies a deterministic boundary after the redirect.
private final class StrictRedirectClient: NSObject, URLProtocolClient, @unchecked Sendable {
    let redirected = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var storage: [String] = []
    var events: [String] { lock.withLock { storage } }
    private func record(_ event: String) { lock.withLock { storage.append(event) } }
    func urlProtocol(_ proto: URLProtocol, wasRedirectedTo request: URLRequest, redirectResponse: URLResponse) {
        record("redirect"); redirected.signal()
    }
    func urlProtocol(_ proto: URLProtocol, didReceive response: URLResponse, cacheStoragePolicy policy: URLCache.StoragePolicy) { record("response") }
    func urlProtocol(_ proto: URLProtocol, didLoad data: Data) { record("data") }
    func urlProtocolDidFinishLoading(_ proto: URLProtocol) { record("finish") }
    func urlProtocol(_ proto: URLProtocol, didFailWithError error: Error) { record("failure") }
    func urlProtocol(_ proto: URLProtocol, cachedResponseIsValid cachedResponse: CachedURLResponse) { record("cache") }
    func urlProtocol(_ proto: URLProtocol, didReceive challenge: URLAuthenticationChallenge) { record("challenge") }
    func urlProtocol(_ proto: URLProtocol, didCancel challenge: URLAuthenticationChallenge) { record("challenge-cancel") }
}
