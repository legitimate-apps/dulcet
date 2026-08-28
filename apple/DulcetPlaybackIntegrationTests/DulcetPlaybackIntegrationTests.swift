import DulcetCore
import DulcetKit
import Foundation
import Network
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
        print("PRODUCTION LOADER LIFECYCLE TRACE \(loaderSummary)")
        print("PRODUCTION LOADER REQUEST TRACE \(loaderTrace.requestDiagnostics)")
        print("PRODUCTION LOADER EVENT TRACE \(loaderTrace.eventDiagnostics)")
        XCTAssertGreaterThan(loaderSummary.started, 0)
        XCTAssertEqual(loaderSummary.contentInformationPublished, loaderSummary.started)
        XCTAssertEqual(loaderSummary.failed, 0)
        XCTAssertEqual(loaderSummary.active, 0)
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

private final class ProductionPathLoopbackServer: @unchecked Sendable {
    private let audio: Data
    private let listener: NWListener
    private let queue = DispatchQueue(label: "com.legitimateapps.dulcet.tests.production-http")
    private let lock = NSLock()
    private var rangeStorage: [String?] = []

    var baseURL: URL {
        URL(string: "http://127.0.0.1:\(listener.port!.rawValue)")!
    }

    var rangeHeaders: [String?] {
        lock.lock()
        defer { lock.unlock() }
        return rangeStorage
    }

    init(audio: Data) throws {
        self.audio = audio
        listener = try NWListener(using: .tcp, on: .any)
        let startup = ProductionPathLoopbackStartup()
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                startup.complete()
            case let .failed(error):
                startup.complete(error: error)
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.start(queue: queue)
        guard startup.wait(timeout: 5) else {
            listener.cancel()
            throw ProductionPathLoopbackError.startupTimedOut
        }
        if let startupError = startup.error {
            listener.cancel()
            throw startupError
        }
        guard listener.port != nil else {
            listener.cancel()
            throw ProductionPathLoopbackError.missingPort
        }
    }

    func stop() {
        listener.cancel()
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(from: connection, accumulated: Data())
    }

    private func receive(from connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
            [weak self, weak connection] data, _, isComplete, error in
            guard let self, let connection else { return }
            var requestData = accumulated
            if let data { requestData.append(data) }
            if requestData.range(of: Data("\r\n\r\n".utf8)) != nil {
                respond(to: connection, requestData: requestData)
            } else if error != nil || isComplete {
                connection.cancel()
            } else {
                receive(from: connection, accumulated: requestData)
            }
        }
    }

    private func respond(to connection: NWConnection, requestData: Data) {
        let request = String(decoding: requestData, as: UTF8.self)
        let rangeHeader = request
            .split(separator: "\r\n")
            .first { $0.lowercased().hasPrefix("range:") }
            .map { String($0.dropFirst("range:".count)).trimmingCharacters(in: .whitespaces) }
        lock.lock()
        rangeStorage.append(rangeHeader)
        lock.unlock()

        let bounds = requestedBounds(from: rangeHeader)
        let body = audio.subdata(in: bounds.start..<(bounds.endInclusive + 1))
        let status = rangeHeader == nil ? "200 OK" : "206 Partial Content"
        var headers = [
            "HTTP/1.1 \(status)",
            "Content-Type: audio/mpeg",
            "Content-Length: \(body.count)",
            "Accept-Ranges: bytes",
            "Connection: close",
        ]
        if rangeHeader != nil {
            headers.append(
                "Content-Range: bytes \(bounds.start)-\(bounds.endInclusive)/\(audio.count)"
            )
        }
        var response = Data((headers.joined(separator: "\r\n") + "\r\n\r\n").utf8)
        response.append(body)
        connection.send(content: response, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }

    private func requestedBounds(from rangeHeader: String?) -> (start: Int, endInclusive: Int) {
        guard let rangeHeader,
              rangeHeader.lowercased().hasPrefix("bytes="),
              let separator = rangeHeader.firstIndex(of: "-") else {
            return (0, audio.count - 1)
        }
        let startText = rangeHeader[rangeHeader.index(rangeHeader.startIndex, offsetBy: 6)..<separator]
        let endText = rangeHeader[rangeHeader.index(after: separator)...]
        let start = min(Int(startText) ?? 0, audio.count - 1)
        let requestedEnd = Int(endText) ?? (audio.count - 1)
        return (start, min(max(start, requestedEnd), audio.count - 1))
    }
}

private final class ProductionPathLoopbackStartup: @unchecked Sendable {
    private let lock = NSLock()
    private let semaphore = DispatchSemaphore(value: 0)
    private var completed = false
    private var errorStorage: Error?

    var error: Error? {
        lock.lock()
        defer { lock.unlock() }
        return errorStorage
    }

    func complete(error: Error? = nil) {
        lock.lock()
        guard !completed else {
            lock.unlock()
            return
        }
        completed = true
        errorStorage = error
        lock.unlock()
        semaphore.signal()
    }

    func wait(timeout: TimeInterval) -> Bool {
        semaphore.wait(timeout: .now() + timeout) == .success
    }
}

private enum ProductionPathLoopbackError: Error {
    case lifecycleTimedOut
    case missingFixture
    case missingPort
    case resolveFailed
    case startupTimedOut
}
