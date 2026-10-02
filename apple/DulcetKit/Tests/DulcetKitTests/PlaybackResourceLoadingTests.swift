import Foundation
import Testing
@testable import DulcetKit

@Suite(.serialized)
struct PlaybackResourceLoadingTests {
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
    // Navidrome's estimated Content-Length overshoots a cold transcode, the server stops at the end
    // of the body, and URLSession reports the short body as -1005 after delivering all of it
    // (OBSERVED on macOS against Navidrome 0.63.2: 24,576 declared, 24,012 delivered). The core
    // validator, which knows whether the plan's length is an estimate, decides what that is.
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
func aLostConnectionWithNoBodyOrAPartialAnswerFailsWithoutValidation() async throws {
    let cases: [(String, Int, [String: String], Data)] = [
        ("no body byte delivered", 200, ["Content-Type": "audio/mpeg", "Content-Length": "96"], Data()),
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
