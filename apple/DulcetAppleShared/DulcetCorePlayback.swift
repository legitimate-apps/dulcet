import DulcetCore
import DulcetKit
import Foundation

/// Copies the exported Kotlin plan classes into DulcetKit value types at the Objective-C boundary.
enum DulcetCorePlaybackPlanFactory {
    static func makePlan(
        client: ApplePlaybackWireClient,
        corePlan: AppleRemotePlaybackPlanDto,
        metadata: DulcetNowPlayingMetadata
    ) -> DulcetPlaybackPlan {
        let deliveryProtocol: DulcetPlaybackDeliveryProtocol = switch corePlan.deliveryProtocol {
        case "HttpProgressive": .httpProgressive
        case "Hls": .hls
        default: .hls
        }
        let expectedContainer: DulcetAudioContainer = switch corePlan.expectedContainer {
        case "Mp3": .mp3
        case "Mp4": .mp4
        case "Wav": .wav
        case "Flac": .flac
        case "Ogg": .ogg
        case "AdtsAac": .adtsAAC
        default: .mp3
        }
        let resource = DulcetCorePlaybackResource(
            client: client,
            plan: corePlan,
            expectedContainer: expectedContainer
        )
        return DulcetPlaybackPlan(
            playbackSessionID: DulcetPlaybackSessionID(corePlan.playbackSessionId),
            attemptID: DulcetPlaybackAttemptID(corePlan.attemptId),
            deliveryProtocol: deliveryProtocol,
            expectedContainer: expectedContainer,
            resource: resource,
            metadata: metadata
        )
    }
}

private final class DulcetCorePlaybackResource: DulcetPlaybackResourceLoading,
    DulcetPlaybackRequestAuthorizing, DulcetPlaybackResponseValidating,
    DulcetPlaybackRedirectEvaluating, @unchecked Sendable {
    private let client: ApplePlaybackWireClient
    private let plan: AppleRemotePlaybackPlanDto
    private let expectedContainer: DulcetAudioContainer
    private lazy var sessionResource = DulcetURLSessionPlaybackResource(
        expectedContainer: expectedContainer,
        authorizer: self,
        validator: self,
        redirectEvaluator: self
    )

    init(
        client: ApplePlaybackWireClient,
        plan: AppleRemotePlaybackPlanDto,
        expectedContainer: DulcetAudioContainer
    ) {
        self.client = client
        self.plan = plan
        self.expectedContainer = expectedContainer
    }

    var description: String { "DulcetCorePlaybackResource(<redacted>)" }

    func load(
        _ request: DulcetPlaybackResourceLoadRequest,
        completion: @escaping @Sendable (DulcetPlaybackResourceLoadOutcome) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        sessionResource.load(request, completion: completion)
    }

    func authorize(
        range: DulcetPlaybackByteRange,
        completion: @escaping @Sendable (
            Result<DulcetPlaybackAuthorizedRequest, DulcetPlaybackFailure>
        ) -> Void
    ) -> any DulcetPlaybackResourceLoadOperation {
        let operation = client.startPrepareRequest(
            plan: plan,
            rangeStart: range.start,
            rangeEndInclusive: range.endInclusive
        ) { outcome in
            guard let prepared = outcome.request, let url = URL(string: prepared.url) else {
                completion(.failure(Self.failure(for: outcome.errorKind)))
                return
            }
            var request = URLRequest(url: url)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            prepared.hostHeader.map {
                request.setValue($0, forHTTPHeaderField: "Host")
            }
            prepared.rangeHeader.map {
                request.setValue($0, forHTTPHeaderField: "Range")
            }
            completion(.success(DulcetPlaybackAuthorizedRequest(request: request)))
        }
        return DulcetCorePlaybackOperation(operation: operation)
    }

    func validate(
        response: DulcetPlaybackHTTPResponse,
        expectedContainer: DulcetAudioContainer,
        requestedRange: DulcetPlaybackByteRange,
        requiresAudioSignature: Bool
    ) -> DulcetPlaybackResponseValidation {
        let outcome = client.validateResponse(
            plan: plan,
            statusCode: Int32(response.statusCode),
            contentType: response.contentType,
            contentLength: response.contentLength ?? -1,
            retryAfter: response.retryAfter,
            acceptRanges: response.acceptRanges,
            contentRange: response.contentRange,
            body: response.body,
            requestedRangeStart: requestedRange.start,
            requestedRangeEndInclusive: requestedRange.endInclusive,
            requiresAudioSignature: requiresAudioSignature
        )
        guard outcome.accepted, outcome.contentLength >= 0 else {
            return .rejected(
                error: Self.failure(
                    for: outcome.errorKind,
                    retryAfterMilliseconds: outcome.retryAfterMilliseconds
                ),
                refreshReason: Self.refreshReason(for: outcome.refreshReason)
            )
        }
        return .accepted(
            contentInformation: DulcetPlaybackContentInformation(
                contentLength: outcome.contentLength,
                supportsByteRanges: outcome.supportsByteRanges
            )
        )
    }

    func evaluate(
        sourceURL: URL,
        proposedURL: URL,
        redirectsAlreadyFollowed: Int
    ) -> DulcetPlaybackRedirectDecision {
        let outcome = client.evaluateRedirect(
            sourceUrl: sourceURL.absoluteString,
            proposedUrl: proposedURL.absoluteString,
            redirectsAlreadyFollowed: Int32(redirectsAlreadyFollowed)
        )
        switch outcome.kind {
        case "preserve":
            return .followPreservingRequest
        case "strip":
            return .followStrippingQueryItems(Set(outcome.queryItemNamesToStrip))
        default:
            return .reject(.transport)
        }
    }

    private static func failure(
        for kind: String?,
        retryAfterMilliseconds: Int64 = -1
    ) -> DulcetPlaybackFailure {
        DulcetPlaybackFailure(coreKind: kind, retryAfterMilliseconds: retryAfterMilliseconds)
    }

    private static func refreshReason(for value: String?) -> DulcetPlaybackSourceRefreshReason? {
        switch value {
        case "Unauthorized": .unauthorized
        case "Expired": .expired
        case "ValidationFailed": .validationFailed
        default: nil
        }
    }
}

private final class DulcetCorePlaybackOperation: DulcetPlaybackResourceLoadOperation,
    @unchecked Sendable {
    private let operation: any ApplePlaybackWireOperation

    init(operation: any ApplePlaybackWireOperation) {
        self.operation = operation
    }

    func cancel() {
        operation.cancel()
    }
}

/// The one translation between the core's failure spellings and ``DulcetPlaybackFailure``, both
/// ways. Spec §12.12 classifies a failure from the error alone, so every distinction it uses must
/// come back to the core exactly as the core sent it: `coreName` of a value built from a core kind
/// is that kind again for a Server code, the two item-content failures and an unsupported
/// capability. The connection-class kinds still share names, which is safe: they all stop.
extension DulcetPlaybackFailure {
    init(coreKind kind: String?, retryAfterMilliseconds: Int64 = -1) {
        let parts = kind?.split(separator: ":", omittingEmptySubsequences: false).map(String.init) ?? []
        switch (parts.first, parts.count) {
        case ("authentication", 1): self = .authentication
        case ("forbidden", 1): self = .forbidden
        case ("serverBusy", 1):
            self = .serverBusy(
                retryAfter: retryAfterMilliseconds >= 0
                    ? TimeInterval(retryAfterMilliseconds) / 1_000
                    : nil
            )
        case ("protocol", 1), ("security", 1), ("protocolViolation", 1): self = .protocolViolation
        case ("tlsUntrusted", 1): self = .tlsUntrusted
        case ("sourceUnavailable", 1): self = .sourceUnavailable
        case ("unsupportedPlan", 1): self = .unsupportedPlan
        case ("undecodable", 1): self = .undecodable
        case ("engine", 1): self = .engine
        case ("unexpectedBinary", 1): self = .unexpectedBinary
        case ("unexpectedContentType", 3): self = .unexpectedContentType(observed: parts[1], expected: parts[2])
        case ("serverKnown", 2) where Int(parts[1]) != nil: self = .server(code: Int(parts[1])!)
        case ("serverUnknown", 2) where Int(parts[1]) != nil: self = .unrecognizedServerError(code: Int(parts[1])!)
        case ("capabilityUnsupported", 2): self = .capabilityUnsupported(feature: parts[1])
        default: self = .transport
        }
    }

    /// The name `ApplePlaybackQueueClient.recordFailed*` accepts for this failure.
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
        case .undecodable: "undecodable"
        case let .unexpectedContentType(observed, expected): "unexpectedContentType:\(observed):\(expected)"
        case .unexpectedBinary: "unexpectedBinary"
        case let .server(code): "serverKnown:\(code)"
        case let .unrecognizedServerError(code): "serverUnknown:\(code)"
        case let .capabilityUnsupported(feature): "capabilityUnsupported:\(feature)"
        }
    }
}
