import AVFoundation
import CryptoKit
import DulcetCore
@testable import DulcetKit
import Foundation
import XCTest

/// MP-10 executes the production Apple network paths. The fixture is a fresh local Dulcet Server,
/// and its CA is installed in the isolated runner trust store before this selector is launched.
/// No test-only TLS delegate or production certificate exception exists in this proof.
@MainActor
final class DulcetAppleConnectionIntegrationTest: XCTestCase {
    private var operations: [any ApplePlaybackWireOperation] = []
    private var artworkOperations: [any AppleArtworkFetchOperation] = []

    func testMP10PreservingHTTPSAdoptionNeverReturnsNativeEnginesToHTTP() async throws {
        let fixture = try ConnectionProofFixture.load()
        let provider = "apple-connections-\(UUID().uuidString)"
        let databaseName = "dulcet-connection-proof-\(UUID().uuidString).db"
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-connection-proof-\(UUID())")
        let account = PlaybackEndpointAccount(providerInstanceId: provider,
            normalizedBaseUrl: fixture.legacyHttp, username: fixture.username,
            password: fixture.password, allowLocalHttp: true)
        XCTAssertTrue(AppleMediaBindingClient.shared.acceptServerIdentity(databaseName: databaseName,
            providerInstanceId: provider, identity: fixture.serverIdentity))
        let downloadConfiguration = URLSessionConfiguration.ephemeral
        downloadConfiguration.timeoutIntervalForRequest = 5
        downloadConfiguration.timeoutIntervalForResource = 10
        let downloads = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: root,
            sessionConfiguration: downloadConfiguration)
        downloads.configure(account: DulcetPlaybackAccount(providerInstanceID: provider,
            normalizedServerURL: fixture.legacyHttp, username: fixture.username, password: fixture.password,
            allowLocalHTTP: true, credentialGeneration: 1))
        downloads.setAutomaticDownloadsEnabled(false)
        downloads.setAutomaticDownloadContactPermitted(true)
        let bindings = AppleDownloadClient(account: account, databaseName: databaseName, downloadRootPath: root.path)
        let connections = AppleConnectionsClient(databaseName: databaseName)
        let wire = ApplePlaybackWireClient(account: account)
        let engine = DulcetAVPlayerEngine()
        defer {
            operations.forEach { $0.cancel() }; artworkOperations.forEach { $0.cancel() }
            downloads.disconnect(); bindings.close(); wire.close()
            try? FileManager.default.removeItem(at: root)
            let databases = URL(fileURLWithPath: NSHomeDirectory())
                .appendingPathComponent("Library/Application Support/databases")
            for suffix in ["", "-wal", "-shm"] {
                try? FileManager.default.removeItem(at: databases.appendingPathComponent(databaseName + suffix))
            }
        }
        _ = try await fixture.control("state") // Fail before credentialed work if the disposable fixture is absent.

        // Positive legacy control: the ordinary URLSession executor downloads a real file on the
        // explicitly opted-in HTTP account before its preserving adoption.
        let oldTrack = fixture.track(0, provider: provider)
        downloads.requestDownload(oldTrack)
        try await eventually("The legacy HTTP positive download did not complete") {
            downloads.status(for: oldTrack.id) == .downloaded
        }
        let oldFile = try XCTUnwrap(bindings.localPlaybackPlan(rawId: oldTrack.id.rawID).plan)
        let oldHash = try digest(URL(fileURLWithPath: oldFile.filePath))
        XCTAssertEqual(oldHash, fixture.tracks[0].sourceSha256)
        XCTAssertEqual(oldFile.exactByteLength, Int64(fixture.tracks[0].sourceBytes))
        let legacyState = try await fixture.state()
        XCTAssertGreaterThan(legacyState.counts["legacy", default: 0], 0,
            "A zero HTTP counter is meaningful only after the fixture observed the legacy positive control")
        XCTAssertTrue(bindings.isMediaBindingCurrent())

        let enrolled = await enroll(connections, fixture: fixture, provider: provider)
        XCTAssertNil(enrolled.error)
        XCTAssertTrue(enrolled.enrolled)
        XCTAssertEqual(enrolled.selected, [fixture.routeA])
        XCTAssertTrue(bindings.isMediaBindingCurrent(), "Enrollment must retain the existing media ownership epoch")
        XCTAssertEqual(account.normalizedBaseUrl, fixture.legacyHttp)
        XCTAssertEqual(try digest(URL(fileURLWithPath: oldFile.filePath)), oldHash)
        XCTAssertFalse(bindings.allowsRestoredRequest(url: fixture.legacyHttp + "/rest/download.view"),
            "An old URLSession task/resume URL cannot restore HTTP access after adoption")
        XCTAssertNil(bindings.restoredRequestRouteBase(url: fixture.legacyHttp + "/rest/download.view"))
        let restored = connections.restore(accountId: provider, anchorUrl: fixture.legacyHttp,
            username: fixture.username, serverIdentity: fixture.serverIdentity)
        XCTAssertTrue(restored.enrolled)
        XCTAssertNil(restored.errorKind)
        _ = try await fixture.control("reset", method: "POST")

        // Actual AVPlayer consumes the production resource loader; an accepted plan is insufficient.
        let playing = fixture.track(1, provider: provider)
        let plan = try await resolve(wire, track: playing, provider: provider)
        let progress = ConnectionProofPlayerProgress()
        engine.setEventListener { progress.record($0) }
        _ = await execute(engine, .setVolume(commandID: .init("connections-mute"), volume: 0))
        do {
            let prepared = await execute(engine, .prepare(commandID: .init("connections-prepare"), plan: plan))
            XCTAssertEqual(prepared, .accepted(commandID: .init("connections-prepare")))
            _ = await execute(engine, .play(commandID: .init("connections-play")))
            try await eventually("The HTTPS fixture did not advance actual AVPlayer media time") { progress.position > 0 }
            XCTAssertGreaterThan(progress.position, 0)
            XCTAssertNil(progress.failure)
        } catch {
            _ = await execute(engine, .release(commandID: .init("connections-error-release")))
            throw error
        }
        _ = await execute(engine, .release(commandID: .init("connections-release")))

        // The real URLSession downloader and Darwin artwork transport still receive the frozen
        // HTTP ownership account; the shared request factory is the only place that substitutes HTTPS.
        let newTrack = fixture.track(2, provider: provider)
        downloads.requestDownload(newTrack)
        try await eventually("The adopted account's URLSession download did not complete") {
            downloads.status(for: newTrack.id) == .downloaded
        }
        let newFile = try XCTUnwrap(bindings.localPlaybackPlan(rawId: newTrack.id.rawID).plan)
        XCTAssertEqual(newFile.exactByteLength, Int64(fixture.tracks[2].sourceBytes))
        XCTAssertEqual(try digest(URL(fileURLWithPath: newFile.filePath)), fixture.tracks[2].sourceSha256)
        let artwork = await fetchArtwork(fixture: fixture, provider: provider, artworkID: fixture.tracks[2].artworkId)
        XCTAssertNotNil(artwork)
        XCTAssertGreaterThan(artwork?.count ?? 0, 0)
        let successful = try await fixture.state()
        XCTAssertEqual(successful.counts["legacy", default: 0], 0)
        XCTAssertGreaterThan(successful.endpointCounts["home"]?["stream"] ?? 0, 0)
        XCTAssertGreaterThan(successful.endpointCounts["home"]?["download"] ?? 0, 0)
        XCTAssertGreaterThan(successful.endpointCounts["home"]?["getCoverArt"] ?? 0, 0)

        // All admitted HTTPS routes can fail while HTTP remains reachable. Neither a fresh media
        // request, a new download intent, artwork nor restored opaque state may fall back to HTTP.
        _ = try await fixture.control("outage", method: "POST", query: ["routeId": "allHttps", "state": "on"])
        let waiting = fixture.track(3, provider: provider)
        downloads.requestDownload(waiting)
        let failedPlan = try await resolve(wire, track: waiting, provider: provider)
        let resource = try XCTUnwrap(failedPlan.resource as? any DulcetPlaybackResourceLoading)
        let failed = await failedLoad(resource)
        let absentArtwork = await fetchArtwork(fixture: fixture, provider: provider,
            artworkID: fixture.tracks[3].artworkId)
        XCTAssertTrue(failed)
        XCTAssertNil(absentArtwork)
        try await eventually("The HTTPS outage must interrupt the actual download executor") {
            let state = downloads.status(for: waiting.id)
            return state == .interrupted || state == .failed
        }
        XCTAssertFalse(bindings.allowsRestoredRequest(url: fixture.legacyHttp + "/rest/stream.view"))
        let outage = try await fixture.state()
        XCTAssertEqual(outage.counts["legacy", default: 0], 0,
            "The still reachable historical HTTP anchor is provenance, never fallback")
        XCTAssertEqual(account.normalizedBaseUrl, fixture.legacyHttp)
        XCTAssertTrue(bindings.isMediaBindingCurrent())
        XCTAssertEqual(try digest(URL(fileURLWithPath: oldFile.filePath)), oldHash)
        XCTAssertEqual(try digest(URL(fileURLWithPath: newFile.filePath)), fixture.tracks[2].sourceSha256)
        _ = try await fixture.control("outage", method: "POST", query: ["routeId": "allHttps", "state": "off"])
        print("DULCET MP-10 APPLE PASS paths=AVPlayer-resource-loader,URLSession-download,artwork ownership=http-anchor transport=https http-after-adoption=0 completed-files=preserved")
    }

    private func enroll(_ client: AppleConnectionsClient, fixture: ConnectionProofFixture,
                        provider: String) async -> ConnectionProofEnrollment {
        await withCheckedContinuation { continuation in
            let operation = client.startEnroll(accountId: provider, anchorUrl: fixture.legacyHttp,
                username: fixture.username, serverIdentity: fixture.serverIdentity,
                bootstrapUrl: fixture.routeA, password: fixture.password) { outcome in
                continuation.resume(returning: ConnectionProofEnrollment(enrolled: outcome.enrolled,
                    error: outcome.errorKind, selected: outcome.routes.filter(\.selected).map(\.baseUrl)))
            }
            operations.append(operation)
        }
    }

    private func resolve(_ client: ApplePlaybackWireClient, track: DulcetTrack,
                         provider: String) async throws -> DulcetPlaybackPlan {
        let plan: DulcetPlaybackPlan? = await withCheckedContinuation { continuation in
            let operation = client.startResolve(request: PlaybackResolveRequest(
                playbackSessionId: PlaybackSessionId(value: "connections-session-\(UUID())"),
                attemptId: AttemptId(value: "connections-attempt-\(UUID())"),
                itemId: ProviderItemId(providerInstanceId: provider, rawId: track.id.rawID),
                sourceContainer: .mp3, supportsTranscodingExtension: false,
                deviceProfile: PlaybackDeviceProfile(name: "Dulcet HTTPS fixture", platform: "Apple",
                    maxAudioBitrate: 1_536_000, maxTranscodingAudioBitrate: 320_000,
                    directPlayProfiles: [DirectPlayAudioProfile(containers: [.mp3], audioCodecs: ["mp3"], protocols: ["http"], maxAudioChannels: 2)],
                    transcodingProfiles: [TranscodingAudioProfile(container: .mp3, audioCodec: "mp3", protocol: "http", maxAudioChannels: 2)]),
                legacyPreference: LegacyPlaybackPreference(format: nil, maxBitRateKbps: nil), legacyTimeOffset: nil)) { outcome in
                let value = outcome.plan.map { corePlan in
                    DulcetCorePlaybackPlanFactory.makePlan(client: client, corePlan: corePlan,
                        metadata: .init(title: track.title))
                }
                continuation.resume(returning: value)
            }
            operations.append(operation)
        }
        return try XCTUnwrap(plan)
    }

    private func fetchArtwork(fixture: ConnectionProofFixture, provider: String, artworkID: String) async -> Data? {
        let client = AppleArtworkFetchClient()
        return await withCheckedContinuation { continuation in
            let operation = client.startFetch(request: AppleArtworkFetchRequest(providerInstanceId: provider,
                artworkKey: artworkID, sizeBucketPixels: 96, normalizedBaseUrl: fixture.legacyHttp,
                username: fixture.username, password: fixture.password, allowLocalHttp: true)) { outcome in
                continuation.resume(returning: outcome.data.map { $0 as Data })
            }
            artworkOperations.append(operation)
        }
    }

    private func failedLoad(_ resource: any DulcetPlaybackResourceLoading) async -> Bool {
        var operation: (any DulcetPlaybackResourceLoadOperation)?
        let result: Bool = await withCheckedContinuation { continuation in
            operation = resource.load(.init(range: .init(start: 0, endInclusive: 4095), requiresAudioSignature: true)) { outcome in
                if case .failed = outcome { continuation.resume(returning: true) }
                else { continuation.resume(returning: false) }
            }
        }
        withExtendedLifetime(operation) { }
        return result
    }

    private func execute(_ engine: DulcetAVPlayerEngine, _ command: DulcetPlaybackCommand) async -> DulcetPlaybackCommandOutcome {
        await withCheckedContinuation { continuation in engine.execute(command) { continuation.resume(returning: $0) } }
    }

    private func eventually(_ failure: String, _ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(45))
        while !condition() {
            guard ContinuousClock.now < deadline else { XCTFail(failure); throw ConnectionProofError.timeout }
            try await Task.sleep(for: .milliseconds(50))
        }
    }

    private func digest(_ file: URL) throws -> String {
        SHA256.hash(data: try Data(contentsOf: file)).map { String(format: "%02x", $0) }.joined()
    }
}

private struct ConnectionProofEnrollment: Sendable {
    let enrolled: Bool
    let error: String?
    let selected: [String]
}
private enum ConnectionProofError: Error { case missingFixture, invalidFixture, timeout, controlFailed }

private struct ConnectionProofFixture: Decodable, Sendable {
    struct Track: Decodable, Sendable {
        let id: String
        let sourceSha256: String
        let sourceBytes: Int
        let durationSeconds: Double
        let artworkId: String
    }
    let standard: String
    let disposable: Bool
    let nonce: String
    let serverIdentity: String
    let routeA: String
    let routeB: String
    let legacyHttp: String
    let controlBase: String
    let username: String
    let password: String
    let tracks: [Track]

    struct State: Decodable, Sendable {
        let counts: [String: Int]
        let endpointCounts: [String: [String: Int]]
    }
    static func load() throws -> Self {
        let environment = ProcessInfo.processInfo.environment
        let bytes: Data
        if let raw = environment["DULCET_MULTIPATH_MANIFEST_JSON"] { bytes = Data(raw.utf8) }
        else if let path = environment["DULCET_MULTIPATH_MANIFEST"] { bytes = try Data(contentsOf: URL(fileURLWithPath: path)) }
        else { XCTFail("The disposable multipath fixture manifest is required"); throw ConnectionProofError.missingFixture }
        let value = try JSONDecoder().decode(Self.self, from: bytes)
        guard value.standard == "dulcet-multipath-fixture", value.disposable, !value.nonce.isEmpty,
              value.tracks.count >= 4, !value.serverIdentity.isEmpty,
              value.tracks.allSatisfy({ $0.sourceBytes > 0 && $0.durationSeconds >= 10 && $0.sourceSha256.count == 64 && !$0.artworkId.isEmpty }) else {
            throw ConnectionProofError.invalidFixture
        }
        for (base, scheme) in [(value.routeA, "https"), (value.routeB, "https"), (value.legacyHttp, "http"), (value.controlBase, "http")] {
            guard let url = URLComponents(string: base), url.scheme == scheme,
                  ["localhost", "127.0.0.1", "::1", "[::1]"].contains(url.host ?? ""), url.port != nil,
                  url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else {
                throw ConnectionProofError.invalidFixture
            }
        }
        guard value.routeA != value.routeB else { throw ConnectionProofError.invalidFixture }
        return value
    }
    func track(_ index: Int, provider: String) -> DulcetTrack {
        let value = tracks[index]
        return DulcetTrack(id: .init(providerInstanceID: provider, rawID: value.id), title: "Disposable HTTPS fixture",
            credits: [], albumTitle: nil, duration: .milliseconds(Int64(value.durationSeconds * 1000)),
            sourceContainer: .mp3, mediaSourceID: nil, artwork: .init(seed: value.id, palette: .indigoCoral))
    }
    func state() async throws -> State {
        let bytes = try await control("state")
        return try JSONDecoder().decode(State.self, from: bytes)
    }
    func control(_ command: String, method: String = "GET", query: [String: String] = [:]) async throws -> Data {
        var components = URLComponents(string: controlBase + "/" + command)!
        if !query.isEmpty { components.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) } }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method; request.setValue(nonce, forHTTPHeaderField: "X-Dulcet-Fixture")
        request.timeoutInterval = 10
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil; configuration.urlCredentialStorage = nil; configuration.urlCache = nil
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw ConnectionProofError.controlFailed }
        return bytes
    }
}

/// AVPlayer's event handler is Sendable; its observation crosses the engine's callback queue.
private final class ConnectionProofPlayerProgress: @unchecked Sendable {
    private let lock = NSLock()
    private var storedPosition: TimeInterval = 0
    private var storedFailure: String?
    var position: TimeInterval { lock.lock(); defer { lock.unlock() }; return storedPosition }
    var failure: String? { lock.lock(); defer { lock.unlock() }; return storedFailure }
    func record(_ event: DulcetPlaybackEvent) {
        lock.lock()
        defer { lock.unlock() }
        switch event {
        case let .playbackProgressBegan(_, _, position), let .positionChanged(_, position, _):
            storedPosition = max(storedPosition, position)
        case let .failedBeforeStart(_, error), let .failedAfterPartial(_, _, error):
            storedFailure = String(describing: error)
        default: break
        }
    }
}
