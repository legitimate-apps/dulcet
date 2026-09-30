import DulcetCore
import DulcetKit
import Foundation
import Network

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

/// The device's streaming-quality setting (spec §12.5): the person's choice, saved in the core's
/// stored form, and the network's cost as `NWPathMonitor` reports it. The core's policy decides
/// which quality the next resolve uses; this only feeds it. A path the system calls expensive
/// (cellular, a personal hotspot) or constrained (Low Data Mode) is metered. Until the first
/// report the policy treats the network as metered.
@MainActor
final class DulcetCoreStreamingQuality: DulcetStreamingQualitySetting {
    static let defaultsKey = "dulcet.streamingQuality"

    private let policy = StreamingQualityPolicy(
        preference: StreamingQualityPreference(unmetered: .original, metered: .original)
    )
    private let defaults: UserDefaults
    /// Sendable, so the nonisolated deinit may cancel it.
    private nonisolated let monitor: NWPathMonitor?
    /// Called when the quality the next resolve would use has changed.
    var onQualityChange: (@MainActor () -> Void)?

    init(defaults: UserDefaults = .standard, monitorsNetwork: Bool = true) {
        self.defaults = defaults
        monitor = monitorsNetwork ? NWPathMonitor() : nil
        _ = policy.restore(stored: defaults.string(forKey: Self.defaultsKey))
        startMonitoring()
    }

    deinit {
        monitor?.cancel()
    }

    /// The quality the next resolve applies.
    var currentQuality: StreamingQuality { policy.currentQuality }

    var preference: DulcetStreamingQualityPreference {
        let stored = policy.preference
        return DulcetStreamingQualityPreference(
            unmetered: Self.presentation(stored.unmetered),
            metered: Self.presentation(stored.metered)
        )
    }

    func setPreference(_ preference: DulcetStreamingQualityPreference) {
        let changed = policy.setPreference(preference: StreamingQualityPreference(
            unmetered: Self.core(preference.unmetered),
            metered: Self.core(preference.metered)
        ))
        defaults.set(policy.preference.encoded(), forKey: Self.defaultsKey)
        if changed { onQualityChange?() }
    }

    /// What the current network costs. A path with no usable route is not reported: the last
    /// classification stands until a usable path says otherwise.
    func setNetwork(_ network: NetworkCostClass) {
        if policy.setNetwork(network: network) { onQualityChange?() }
    }

    private func startMonitoring() {
        guard let monitor else { return }
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            let metered = path.isExpensive || path.isConstrained
            Task { @MainActor in
                self?.setNetwork(metered ? .metered : .unmetered)
            }
        }
        monitor.start(queue: .main)
    }

    static func presentation(_ quality: StreamingQuality) -> DulcetStreamingQuality {
        DulcetStreamingQuality(rawValue: quality.wireName) ?? .original
    }

    static func core(_ quality: DulcetStreamingQuality) -> StreamingQuality {
        switch quality {
        case .original: .original
        case .kbps320: .kbps320
        case .kbps256: .kbps256
        case .kbps192: .kbps192
        case .kbps128: .kbps128
        case .kbps96: .kbps96
        }
    }
}
