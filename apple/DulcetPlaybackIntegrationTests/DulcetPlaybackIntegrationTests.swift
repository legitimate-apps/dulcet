import DulcetCore
import DulcetKit
import Foundation
import CryptoKit
import XCTest
@testable import DulcetKit

@MainActor
final class DulcetPlaybackIntegrationTests: XCTestCase {
    private var resolveOperation: (any ApplePlaybackWireOperation)?

    func testProductionCoreResourceBecomesReadyAndProgressesAgainstNavidromeRanges() async throws {
        let audio = try navidromeReferenceMP3Fixture()
        XCTAssertGreaterThan(audio.count, 7_000_000)
        let server = try ProductionPathLoopbackServer(audio: audio)
        defer { server.stop() }

        let account = PlaybackEndpointAccount(
            providerInstanceId: "loopback-provider",
            normalizedBaseUrl: server.baseURL.absoluteString,
            username: "loopback-user",
            password: "loopback-password",
            allowLocalHttp: true
        )
        let client = ApplePlaybackWireClient(account: account)
        defer { client.close() }
        let corePlan = try await resolvePlan(client: client)
        let plan = DulcetCorePlaybackPlanFactory.makePlan(
            client: client,
            corePlan: corePlan,
            metadata: DulcetNowPlayingMetadata(
                title: "Loopback fixture",
                artist: "Dulcet Tests"
            )
        )
        let engine = DulcetAVPlayerEngine()
        let loaderTrace = PlaybackLoaderTraceRecorder()
        // Keep request diagnostics even when the lifecycle wait throws.
        defer {
            print("PRODUCTION LOADER LIFECYCLE TRACE \(loaderTrace.summary)")
            print("PRODUCTION LOADER REQUEST TRACE \(loaderTrace.requestDiagnostics)")
            print("PRODUCTION LOADER EVENT TRACE \(loaderTrace.eventDiagnostics)")
        }
        engine.setResourceLoaderTraceHandlerForTesting { loaderTrace.append($0) }
        let ready = expectation(description: "production resource reached Ready")
        let progressing = expectation(description: "production resource advanced media time")
        engine.setEventListener { event in
            switch event {
            case .ready:
                ready.fulfill()
            case .playbackProgressBegan:
                progressing.fulfill()
            default:
                break
            }
        }

        let prepare = await execute(
            engine,
            .prepare(commandID: .init("production-loopback-prepare"), plan: plan)
        )
        XCTAssertEqual(prepare, .accepted(commandID: .init("production-loopback-prepare")))
        let play = await execute(
            engine,
            .play(commandID: .init("production-loopback-play"))
        )
        XCTAssertEqual(play, .accepted(commandID: .init("production-loopback-play")))

        await fulfillment(of: [ready, progressing], timeout: 8, enforceOrder: true)
        let ranges = server.rangeHeaders
        print("PRODUCTION PLAYBACK RANGE TRACE count=\(ranges.count) ranges=\(ranges)")
        XCTAssertFalse(ranges.isEmpty)
        XCTAssertTrue(ranges.allSatisfy { $0 != nil })

        _ = await execute(
            engine,
            .release(commandID: .init("production-loopback-release"))
        )
        try await waitUntil(timeout: 2) {
            loaderTrace.summary.active == 0
        }
        let loaderSummary = loaderTrace.summary
        XCTAssertGreaterThan(loaderSummary.started, 0)
        XCTAssertEqual(loaderSummary.contentInformationPublished, loaderSummary.started)
        XCTAssertEqual(loaderSummary.failed, 0)
        XCTAssertEqual(loaderSummary.active, 0)
    }

    func testInstalledResourceUsesRefreshedCredentialsForItsNextRangeAndRejectsARebind() async throws {
        let audio = try navidromeReferenceMP3Fixture()
        let server = try ProductionPathLoopbackServer(audio: audio)
        defer { server.stop() }
        func account(_ password: String, username: String = "loopback-user") -> PlaybackEndpointAccount {
            PlaybackEndpointAccount(providerInstanceId: "loopback-provider",
                normalizedBaseUrl: server.baseURL.absoluteString, username: username,
                password: password, allowLocalHttp: true)
        }
        let source = DulcetCorePlaybackWireSource(account: account("old-password"))
        defer { source.revoke() }
        let client = try XCTUnwrap(source.select()).client
        let corePlan = try await resolvePlan(client: client)
        let plan = DulcetCorePlaybackPlanFactory.makePlan(client: client, corePlan: corePlan,
            metadata: .init(title: "Credential refresh"), retaining: source)
        let resource = try XCTUnwrap(plan.resource as? any DulcetPlaybackResourceLoading)
        func load(_ start: Int64) async -> DulcetPlaybackResourceLoadOutcome {
            var operation: (any DulcetPlaybackResourceLoadOperation)?
            let result: DulcetPlaybackResourceLoadOutcome = await withCheckedContinuation { continuation in
                operation = resource.load(.init(range: .init(start: start, endInclusive: start + 1023),
                    requiresAudioSignature: start == 0)) { continuation.resume(returning: $0) }
            }
            withExtendedLifetime(operation) {}
            return result
        }
        let first = await load(0)
        guard case .loaded = first else { XCTFail("First real HTTP range must load: \(first)"); return }
        XCTAssertTrue(source.replace(account: account("new-password")))
        let second = await load(1024)
        guard case .loaded = second else { XCTFail("Same resource must load a second range: \(second)"); return }
        let requests = server.requestQueries
        XCTAssertEqual(requests.count, 2)
        guard requests.count == 2 else { return }
        for (query, password) in zip(requests, ["old-password", "new-password"]) {
            let parameters = URLComponents(string: "http://fixture" + query)?.queryItems ?? []
            let values = Dictionary(parameters.compactMap { item in item.value.map { (item.name, $0) } },
                                    uniquingKeysWith: { _, last in last })
            let salt = try XCTUnwrap(values["s"])
            let token = Insecure.MD5.hash(data: Data((password + salt).utf8))
                .map { String(format: "%02x", $0) }.joined()
            XCTAssertEqual(values["u"], "loopback-user")
            XCTAssertEqual(values["t"], token, "The actual HTTP request must use its current password")
        }
        XCTAssertFalse(source.replace(account: account("other-password", username: "different-user")))
        let rejected = await load(2048)
        if case .loaded = rejected { XCTFail("An old binding resource must be revoked") }
        XCTAssertEqual(server.requestQueries.count, 2, "Rebinding must issue no old-resource HTTP request")
    }

    private func resolvePlan(client: ApplePlaybackWireClient) async throws -> AppleRemotePlaybackPlanDto {
        let resolved = expectation(description: "production core resolved a legacy playback plan")
        let capture = PlaybackPlanCapture()
        let request = PlaybackResolveRequest(
            playbackSessionId: PlaybackSessionId(value: "loopback-session"),
            attemptId: AttemptId(value: "loopback-attempt"),
            itemId: ProviderItemId(
                providerInstanceId: "loopback-provider",
                rawId: "loopback-track"
            ),
            sourceContainer: .mp3,
            supportsTranscodingExtension: false,
            deviceProfile: PlaybackDeviceProfile(
                name: "Dulcet macOS test",
                platform: "macOS",
                maxAudioBitrate: 1_536_000,
                maxTranscodingAudioBitrate: 320_000,
                directPlayProfiles: [
                    DirectPlayAudioProfile(
                        containers: [.mp3],
                        audioCodecs: ["mp3"],
                        protocols: ["http"],
                        maxAudioChannels: 2
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
            ),
            legacyPreference: LegacyPlaybackPreference(format: nil, maxBitRateKbps: nil),
            legacyTimeOffset: nil
        )
        resolveOperation = client.startResolve(request: request) { [weak self] outcome in
            self?.resolveOperation = nil
            if let plan = outcome.plan {
                capture.store(plan)
            }
            resolved.fulfill()
        }
        await fulfillment(of: [resolved], timeout: 3)
        resolveOperation?.cancel()
        resolveOperation = nil
        guard let plan = capture.plan else {
            throw ProductionPathLoopbackError.resolveFailed
        }
        return plan
    }

    private func execute(
        _ engine: DulcetAVPlayerEngine,
        _ command: DulcetPlaybackCommand
    ) async -> DulcetPlaybackCommandOutcome {
        await withCheckedContinuation { continuation in
            engine.execute(command) { continuation.resume(returning: $0) }
        }
    }

    private func navidromeReferenceMP3Fixture() throws -> Data {
        guard let url = Bundle(for: Self.self).url(
            forResource: "navidrome-reference",
            withExtension: "mp3"
        ) else {
            throw ProductionPathLoopbackError.missingFixture
        }
        return try Data(contentsOf: url, options: .mappedIfSafe)
    }

    private func waitUntil(
        timeout: TimeInterval,
        condition: @escaping @MainActor () -> Bool
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else {
                throw ProductionPathLoopbackError.lifecycleTimedOut
            }
            try await Task.sleep(for: .milliseconds(20))
        }
    }
}

private final class PlaybackLoaderTraceRecorder: @unchecked Sendable {
    struct Summary: CustomStringConvertible {
        let started: Int
        let contentInformationPublished: Int
        let finished: Int
        let cancelled: Int
        let failed: Int

        var active: Int { started - finished - cancelled - failed }

        var description: String {
            "started=\(started) contentInformation=\(contentInformationPublished) "
                + "finished=\(finished) cancelled=\(cancelled) "
                + "failed=\(failed) active=\(active)"
        }
    }

    private let lock = NSLock()
    private var events: [DulcetPlaybackResourceLoaderTraceEvent] = []

    func append(_ event: DulcetPlaybackResourceLoaderTraceEvent) {
        lock.lock()
        events.append(event)
        lock.unlock()
    }

    var summary: Summary {
        lock.lock()
        defer { lock.unlock() }
        var started = 0
        var contentInformationPublished = 0
        var finished = 0
        var cancelled = 0
        var failed = 0
        for event in events {
            switch event {
            case .started: started += 1
            case .contentInformationPublished: contentInformationPublished += 1
            case .finished: finished += 1
            case .cancelled: cancelled += 1
            case .failed: failed += 1
            case .rangeRequested, .responded: break
            }
        }
        return Summary(
            started: started,
            contentInformationPublished: contentInformationPublished,
            finished: finished,
            cancelled: cancelled,
            failed: failed
        )
    }

    var requestDiagnostics: [String] {
        lock.lock()
        defer { lock.unlock() }
        return events.compactMap { event in
            guard case let .started(
                _, requestedOffset, requestedLength, requestsToEnd, requiresAudioSignature
            ) = event else { return nil }
            return "offset=\(requestedOffset) length=\(requestedLength) "
                + "toEnd=\(requestsToEnd) signature=\(requiresAudioSignature)"
        }
    }

    var eventDiagnostics: [String] {
        lock.lock()
        defer { lock.unlock() }
        return events.map { event in
            switch event {
            case let .started(requestID, offset, length, toEnd, signature):
                "\(requestID):started \(offset)+\(length) toEnd=\(toEnd) signature=\(signature)"
            case let .rangeRequested(requestID, range):
                "\(requestID):range \(range.start)-\(range.endInclusive)"
            case let .contentInformationPublished(requestID):
                "\(requestID):contentInformation"
            case let .responded(requestID, byteCount):
                "\(requestID):responded \(byteCount)"
            case let .finished(requestID):
                "\(requestID):finished"
            case let .cancelled(requestID):
                "\(requestID):cancelled"
            case let .failed(requestID, failure):
                "\(requestID):failed \(failure)"
            }
        }
    }
}

private final class PlaybackPlanCapture: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: AppleRemotePlaybackPlanDto?

    var plan: AppleRemotePlaybackPlanDto? {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    func store(_ plan: AppleRemotePlaybackPlanDto) {
        lock.lock()
        storage = plan
        lock.unlock()
    }
}
