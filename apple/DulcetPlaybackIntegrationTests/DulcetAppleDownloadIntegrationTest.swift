import DulcetCore
import DulcetKit
import Foundation
import XCTest
#if os(iOS)
import UIKit
#endif

@MainActor
final class DulcetAppleDownloadIntegrationTest: XCTestCase {
    private let providerInstanceID = "apple-download-provider:opaque-id"
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

    func http206ExactLengthFailsClosedWithoutAValidConsistentContentRange() throws {
        let cases: [(label: String, headers: [String: String], expected: Int64?)] = [
            (
                "valid-range",
                ["Content-Range": "bytes 90-99/100", "Content-Length": "10"],
                100
            ),
            ("missing-range", ["Content-Length": "10"], nil),
            (
                "malformed-range",
                ["Content-Range": "definitely-not-a-range", "Content-Length": "10"],
                nil
            ),
            (
                "unknown-total",
                ["Content-Range": "bytes 90-99/*", "Content-Length": "10"],
                nil
            ),
            (
                "total-does-not-match-file",
                ["Content-Range": "bytes 90-99/101", "Content-Length": "10"],
                nil
            ),
            (
                "range-length-does-not-match-content-length",
                ["Content-Range": "bytes 90-99/100", "Content-Length": "9"],
                nil
            ),
        ]

        for testCase in cases {
            let response = try XCTUnwrap(HTTPURLResponse(
                url: URL(string: "https://music.example.invalid/stream")!,
                statusCode: 206,
                httpVersion: "HTTP/1.1",
                headerFields: testCase.headers
            ))
            XCTAssertEqual(
                response.downloadExactContentLength(deliveredFileLength: 100),
                testCase.expected,
                "206-\(testCase.label)"
            )
        }
    }

    func downloadDelegateSecuresTemporaryFileBeforeFacadeReconciliation() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("dulcet-download-inbox-\(UUID().uuidString)", isDirectory: true)
        let source = FileManager.default.temporaryDirectory
            .appendingPathComponent("dulcet-system-download-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data("only-copy".utf8).write(to: source)
        defer {
            try? FileManager.default.removeItem(at: source)
            try? FileManager.default.removeItem(at: root)
        }
        let controller = DulcetCoreDownloadController(
            databaseName: "dulcet-inbox-\(UUID().uuidString).db",
            downloadRootURL: root,
            sessionConfiguration: .ephemeral
        )
        let session = URLSession(configuration: .ephemeral)
        let task = session.downloadTask(with: URL(string: "https://music.example.invalid/stream")!)
        task.taskDescription = "download:completion-before-reconciliation"

        controller.urlSession(session, downloadTask: task, didFinishDownloadingTo: source)

        XCTAssertFalse(
            FileManager.default.fileExists(atPath: source.path),
            "the Foundation-owned temporary URL must be moved before the delegate returns"
        )
        let securedFiles = try FileManager.default.contentsOfDirectory(
            at: root.appendingPathComponent(".incoming", isDirectory: true),
            includingPropertiesForKeys: nil
        )
        XCTAssertEqual(securedFiles.filter { $0.pathExtension == "download" }.count, 1)
        XCTAssertEqual(securedFiles.filter { $0.pathExtension == "json" }.count, 1)
        session.invalidateAndCancel()
        controller.disconnect()
    }

    func backgroundSessionFinishDelegateIsImplemented() {
        let controller = DulcetCoreDownloadController(
            databaseName: "dulcet-background-\(UUID().uuidString).db",
            downloadRootURL: FileManager.default.temporaryDirectory,
            sessionConfiguration: .ephemeral
        )

        XCTAssertTrue(
            controller.responds(
                to: #selector(URLSessionDelegate.urlSessionDidFinishEvents(forBackgroundURLSession:))
            )
        )
        controller.disconnect()
    }

    func backgroundSessionHandoffWaitsUntilDelegateFinishesEvents() async throws {
        let controller = DulcetCoreDownloadController(
            databaseName: "dulcet-background-wait-\(UUID().uuidString).db",
            downloadRootURL: FileManager.default.temporaryDirectory,
            sessionConfiguration: .ephemeral
        )
        var handoffFinished = false
        Task { @MainActor in
            await controller.handleBackgroundSessionEvents()
            handoffFinished = true
        }
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertFalse(handoffFinished)

        controller.urlSessionDidFinishEvents(forBackgroundURLSession: .shared)

        try await waitUntil(
            timeout: .seconds(1),
            failureMessage: "the app-level background handoff did not finish"
        ) {
            handoffFinished
        }
        controller.disconnect()
    }

    #if os(macOS)
    func downloadTriggerPromotesValidatedResponseAtomically() async throws {
        try await proveDownloadTriggerPromotesValidatedResponseAtomically()
    }

    func offlinePlaybackLoadsIdenticalBytesAfterNetworkClientsClose() async throws {
        try await proveOfflinePlaybackLoadsIdenticalBytesAfterNetworkClientsClose()
    }
    #elseif os(iOS)
    func downloadTriggerPromotesValidatedResponseAtomicallyOnIOS() async throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .phone)
        try await proveDownloadTriggerPromotesValidatedResponseAtomically()
    }

    func offlinePlaybackLoadsIdenticalBytesAfterNetworkClientsCloseOnIOS() async throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .phone)
        try await proveOfflinePlaybackLoadsIdenticalBytesAfterNetworkClientsClose()
    }

    func downloadTriggerPromotesValidatedResponseAtomicallyOnIPadOS() async throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad)
        try await proveDownloadTriggerPromotesValidatedResponseAtomically()
    }

    func offlinePlaybackLoadsIdenticalBytesAfterNetworkClientsCloseOnIPadOS() async throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad)
        try await proveOfflinePlaybackLoadsIdenticalBytesAfterNetworkClientsClose()
    }
    #endif

    /// §13.1's exception covers recovery of an earlier Download; it does not require Reconnect.
    func savedLaunchRecoversAMissingPreviouslyDownloadedFileBeforeReconnect() async throws {
        try await proveSavedLaunchRecovery(.missingFile)
    }

    func savedLaunchRecoversAPreviouslyDownloadedFileAfterCredentialGenerationChanges() async throws {
        try await proveSavedLaunchRecovery(.changedCredentialGeneration)
    }

    /// An intact download requires no recovery request while launch negotiation is running.
    func savedLaunchWithAnIntactDownloadMakesNoDownloadRecoveryRequests() async throws {
        try await proveSavedLaunchRecovery(.intact)
    }

    /// A new Download is outside §13.1's recovery exception, even after the saved controller
    /// has reconciled and could issue a stream immediately if presentation allowed the action.
    func savedLaunchDownloadTapWaitsForNegotiation() async throws {
        let context = try makeContext()
        defer { context.tearDown() }
        let browseBaseline = try await downloadProxyRequests(baseURL: context.baseURL, since: 0).total
        let track = try await loadLiveTrack(baseURL: context.baseURL)
        XCTAssertEqual(track.availability, .playable)
        let browseRequests = try await downloadProxyRequests(baseURL: context.baseURL, since: browseBaseline)
        XCTAssertGreaterThan(browseRequests.entries.count, 0, "the request counter must observe the live seed read")
        XCTAssertTrue(browseRequests.entries.contains { $0.answered && $0.status == 200 })
        context.controller.disconnect()

        let replacement = DulcetCoreDownloadController(
            databaseName: context.databaseName,
            downloadRootURL: context.downloadRoot,
            sessionConfiguration: .ephemeral
        )
        defer { replacement.disconnect() }
        let connector = DownloadRecoveryConnector()
        let source = DulcetAccountDataSource(
            connector: connector,
            credentialStore: DownloadRecoveryCredentialStore(
                request: DulcetAccountConnectRequest(
                    serverURL: context.baseURL,
                    username: fixtureUsername,
                    password: fixturePassword,
                    allowLocalHTTP: true
                ),
                providerInstanceID: providerInstanceID,
                credentialGeneration: 7
            ),
            downloadController: replacement
        )
        defer { _ = source.currentSnapshot }
        var reconciled = false
        // configure's getAllTasks answer is delivered asynchronously to this main actor. Install
        // the witness before yielding, so the action is driven after actual local reconciliation.
        replacement.setStateRefreshHandler { reconciled = true }
        try await waitUntil(timeout: .seconds(10), failureMessage: "saved downloads did not reconcile") {
            reconciled
        }
        XCTAssertEqual(source.currentSnapshot.accountConnection, .connecting)
        XCTAssertTrue(source.downloadsEnabled)
        XCTAssertEqual(replacement.status(for: track.id), .notDownloaded)

        source.send(.requestDownload(track))

        try await Task.sleep(for: .seconds(3))
        let observed = try await downloadProxyRequests(baseURL: context.baseURL, since: browseRequests.total)
        XCTAssertEqual(observed.entries.count, 0, "a new Download must wait for successful negotiation")
        XCTAssertEqual(replacement.status(for: track.id), .notDownloaded, "the tap must not enqueue a transfer")
        XCTAssertEqual(connector.requestCount, 1, "saved launch reconnects without permitting a new stream yet")
        XCTAssertEqual(source.currentSnapshot.accountConnection, .connecting)
        XCTAssertTrue(try regularFiles(in: context.downloadRoot).isEmpty)
    }

    private enum SavedDownloadRecovery: Equatable {
        case missingFile, changedCredentialGeneration, intact
    }

    private func proveSavedLaunchRecovery(_ recovery: SavedDownloadRecovery) async throws {
        let context = try makeContext()
        defer { context.tearDown() }
        let track = try await loadLiveTrack(baseURL: context.baseURL)
        let seedBaseline = try await downloadProxyRequests(baseURL: context.baseURL, since: 0).total
        context.controller.requestDownload(track)
        try await waitUntil(timeout: .seconds(60), failureMessage: "the earlier Download did not finish") {
            context.controller.status(for: track.id) == .downloaded
        }
        let originalFile = try XCTUnwrap(try regularFiles(in: context.downloadRoot).first)
        let originalBytes = try Data(contentsOf: originalFile)
        XCTAssertFalse(originalBytes.isEmpty)
        context.controller.disconnect()
        if recovery == .missingFile {
            try FileManager.default.removeItem(at: originalFile)
            XCTAssertFalse(FileManager.default.fileExists(atPath: originalFile.path))
        } else {
            XCTAssertTrue(FileManager.default.fileExists(atPath: originalFile.path))
        }

        // Nothing after this baseline browses, connects, or asks for another Download. The real
        // saved-account presentation configures the replacement controller as production does.
        let seedRequests = try await downloadProxyRequests(baseURL: context.baseURL, since: seedBaseline)
        XCTAssertEqual(seedRequests.entries.count, 1, "the request counter must observe the earlier Download")
        let seedStream = try XCTUnwrap(seedRequests.entries.first)
        XCTAssertEqual(seedStream.endpoint, "stream")
        XCTAssertEqual(seedStream.rawID, track.id.rawID)
        XCTAssertTrue(seedStream.answered)
        XCTAssertEqual(seedStream.status, 200)
        let baseline = seedRequests.total
        let replacement = DulcetCoreDownloadController(
            databaseName: context.databaseName,
            downloadRootURL: context.downloadRoot,
            sessionConfiguration: .ephemeral
        )
        defer { replacement.disconnect() }
        let connector = DownloadRecoveryConnector()
        let source = DulcetAccountDataSource(
            connector: connector,
            credentialStore: DownloadRecoveryCredentialStore(
                request: DulcetAccountConnectRequest(
                    serverURL: context.baseURL,
                    username: fixtureUsername,
                    password: fixturePassword,
                    allowLocalHTTP: true
                ),
                providerInstanceID: providerInstanceID,
                credentialGeneration: recovery == .changedCredentialGeneration ? 8 : 7
            ),
            downloadController: replacement
        )
        defer { _ = source.currentSnapshot }
        XCTAssertEqual(source.currentSnapshot.accountConnection, .connecting)
        try await waitUntil(timeout: .seconds(60), failureMessage: "saved launch did not recover the download") {
            replacement.status(for: track.id) == .downloaded
        }
        let asset = try XCTUnwrap(replacement.offlinePlaybackAsset(for: track))
        XCTAssertEqual(asset.exactByteLength, Int64(originalBytes.count))
        XCTAssertEqual(try Data(contentsOf: originalFile), originalBytes)

        // A duplicate recovery can arrive after promotion; count only after that work settles.
        try await Task.sleep(for: .seconds(3))
        let observed = try await downloadProxyRequests(baseURL: context.baseURL, since: baseline)
        XCTAssertEqual(connector.requestCount, 1, "saved launch reconnects independently of download recovery")
        XCTAssertEqual(source.currentSnapshot.accountConnection, .connecting)
        if recovery == .intact {
            XCTAssertEqual(observed.entries.count, 0, "an intact download has no recovery read to make")
        } else {
            XCTAssertEqual(observed.entries.count, 1, "only the permitted recovery stream may be sent")
            let stream = try XCTUnwrap(observed.entries.first)
            XCTAssertEqual(stream.endpoint, "stream")
            XCTAssertEqual(stream.rawID, track.id.rawID)
            XCTAssertTrue(stream.answered, "the replacement must promote the actual server response")
            XCTAssertEqual(stream.status, 200)
        }
    }

    private func downloadProxyRequests(
        baseURL: String, since: Int
    ) async throws -> (total: Int, entries: [DownloadRecoveryWireEntry]) {
        // makeContext already restricts this URL to the disposable loopback server. The proof
        // routes that server through its counting proxy; this control path never forwards to /rest.
        var components = try XCTUnwrap(URLComponents(string: baseURL))
        components.path = "/__dulcet/requests"
        components.queryItems = [URLQueryItem(name: "since", value: String(since))]
        let url = try XCTUnwrap(components.url)
        let (bytes, response) = try await URLSession.shared.data(from: url)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        let total = try XCTUnwrap(object["total"] as? Int)
        let entries = try XCTUnwrap(object["requests"] as? [[String: Any]])
        return (total, try entries.map { entry in
            DownloadRecoveryWireEntry(
                endpoint: try XCTUnwrap(entry["endpoint"] as? String),
                rawID: entry["id"] as? String,
                answered: try XCTUnwrap(entry["answered"] as? Bool),
                status: try XCTUnwrap(entry["status"] as? Int)
            )
        })
    }

    private func proveDownloadTriggerPromotesValidatedResponseAtomically() async throws {
        let context = try makeContext()
        defer { context.tearDown() }
        let track = try await loadLiveTrack(baseURL: context.baseURL)

        XCTAssertTrue(try regularFiles(in: context.downloadRoot).isEmpty)
        context.controller.requestDownload(track)

        try await waitUntil(
            timeout: .seconds(60),
            failureMessage: "the \(platformLabel) executor did not reach the downloaded state"
        ) {
            context.controller.status(for: track.id) == .downloaded
        }

        let asset = try XCTUnwrap(context.controller.offlinePlaybackAsset(for: track))
        let files = try regularFiles(in: context.downloadRoot)
        XCTAssertEqual(files.count, 1, "a successful transfer must leave exactly one promoted file")
        XCTAssertFalse(
            files.contains { $0.lastPathComponent.hasSuffix(".partial") },
            "the temporary transfer file must not remain visible after promotion"
        )
        let file = try XCTUnwrap(files.first)
        let attributes = try FileManager.default.attributesOfItem(atPath: file.path)
        let storedByteCount = try XCTUnwrap(attributes[.size] as? NSNumber).int64Value
        XCTAssertGreaterThan(storedByteCount, 0)
        XCTAssertEqual(storedByteCount, asset.exactByteLength)

        try await proveStreamCacheThroughPlaybackEngine()
        try await proveStreamCacheAndAutomaticRetention()

        print(
            "CONF-51 \(platformLabel) DOWNLOAD trigger=platform-executor"
                + " response=validated promotion=atomic exact_bytes=\(storedByteCount)"
        )
    }

    /// A real AVPlayer consumes the streamed response; cache promotion cannot be caused by
    /// the manual sparse-range proof below. A fresh engine then consumes only the local file.
    private func proveStreamCacheThroughPlaybackEngine() async throws {
        let context = try makeContext()
        defer { context.tearDown() }
        let track = try await loadLiveTrack(baseURL: context.baseURL,
            minimumDurationMilliseconds: 2_000, maximumDurationMilliseconds: 5_000)
        try await waitUntil(timeout: .seconds(10), failureMessage: "engine cache policy never reconciled") {
            context.controller.offlineLibraryStorage.cacheBudgetBytes > 0
        }
        let account = PlaybackEndpointAccount(providerInstanceId: providerInstanceID,
            normalizedBaseUrl: context.baseURL, username: fixtureUsername, password: fixturePassword, allowLocalHttp: true)
        let wire = ApplePlaybackWireClient(account: account)
        defer { wire.close() }
        let plan = try await resolveStreamingPlan(wire: wire, context: context, track: track)
        let onlineMediaTime = try await withPlaybackEngine { online in
            let onlineProgress = OfflineEngineProgress()
            online.setEventListener { event in
                onlineProgress.record(event)
            }
            let onlinePrepared = await execute(online, .prepare(commandID: .init("cache-engine-online-prepare"), plan: plan))
            XCTAssertEqual(onlinePrepared, .accepted(commandID: .init("cache-engine-online-prepare")))
            _ = await execute(online, .setVolume(commandID: .init("cache-engine-online-mute"), volume: 0))
            _ = await execute(online, .play(commandID: .init("cache-engine-online-play")))
            try await waitUntil(timeout: .seconds(20), failureMessage: "AVPlayer did not stream, advance and promote cache: \(onlineProgress.failure ?? "no engine failure")") {
                onlineProgress.position > 0 && context.controller.status(for: track.id) == .cached
            }
            XCTAssertGreaterThan(onlineProgress.position, 0, "the production player clock must advance while streaming")
            XCTAssertNil(onlineProgress.failure)
            XCTAssertGreaterThan(context.controller.offlineLibraryStorage.cacheBytes, 0)
            XCTAssertEqual(context.controller.offlineLibraryStorage.downloadBytes, 0)
            return onlineProgress.position
        }
        context.controller.closeNetworkAccessForTesting()
        wire.close()
        let local = try XCTUnwrap(context.controller.offlinePlaybackAsset(for: track))
        let localPlan = DulcetPlaybackPlan(playbackSessionID: .init("cache-engine-local-session"),
            attemptID: .init("cache-engine-local-attempt"), deliveryProtocol: .httpProgressive,
            expectedContainer: local.expectedContainer, resource: local.resource,
            metadata: .init(title: track.title, artist: nil, albumTitle: track.albumTitle))
        let offlineMediaTime = try await withPlaybackEngine { offline in
            let offlineProgress = OfflineEngineProgress()
            offline.setEventListener { offlineProgress.record($0) }
            let offlinePrepared = await execute(offline, .prepare(commandID: .init("cache-engine-offline-prepare"), plan: localPlan))
            XCTAssertEqual(offlinePrepared, .accepted(commandID: .init("cache-engine-offline-prepare")))
            _ = await execute(offline, .setVolume(commandID: .init("cache-engine-offline-mute"), volume: 0))
            _ = await execute(offline, .play(commandID: .init("cache-engine-offline-play")))
            try await waitUntil(timeout: .seconds(15), failureMessage: "cached AVPlayer did not advance with network closed: \(offlineProgress.failure ?? "no engine failure")") {
                offlineProgress.position > 0
            }
            XCTAssertGreaterThan(offlineProgress.position, 0, "cached playback must advance the real player clock")
            XCTAssertNil(offlineProgress.failure)
            return offlineProgress.position
        }
        print("OFFLINE-CACHE-ENGINE \(platformLabel) online-media-time=\(onlineMediaTime) cache=promoted network=closed offline-media-time=\(offlineMediaTime)")
    }

    /// Await release on success, cancellation and assertion-wait errors before the caller's
    /// context teardown can remove the media files AVFoundation was reading.
    private func withPlaybackEngine<T>(_ body: (DulcetAVPlayerEngine) async throws -> T) async throws -> T {
        let engine = DulcetAVPlayerEngine()
        do {
            let result = try await body(engine)
            _ = await execute(engine, .release(commandID: .init("cache-engine-scope-release")))
            return result
        } catch {
            _ = await execute(engine, .release(commandID: .init("cache-engine-scope-release-error")))
            throw error
        }
    }

    private func execute(_ engine: DulcetAVPlayerEngine, _ command: DulcetPlaybackCommand) async -> DulcetPlaybackCommandOutcome {
        await withCheckedContinuation { continuation in
            engine.execute(command) { continuation.resume(returning: $0) }
        }
    }

    private func resolveStreamingPlan(wire: ApplePlaybackWireClient, context: DownloadIntegrationContext,
                                      track: DulcetTrack) async throws -> DulcetPlaybackPlan {
        let container = try XCTUnwrap(track.sourceContainer)
        let coreContainer: AudioContainer = switch container {
        case .mp3: .mp3; case .mp4: .mp4; case .wav: .wav
        case .flac: .flac; case .ogg: .ogg; case .adtsAAC: .adtsaac
        }
        let resolved: DulcetPlaybackPlan? = await withCheckedContinuation { continuation in
            _ = wire.startResolve(request: PlaybackResolveRequest(
                playbackSessionId: PlaybackSessionId(value: "offline-cache-session"),
                attemptId: AttemptId(value: "offline-cache-attempt"),
                itemId: ProviderItemId(providerInstanceId: providerInstanceID, rawId: track.id.rawID),
                sourceContainer: coreContainer, supportsTranscodingExtension: false,
                deviceProfile: PlaybackDeviceProfile(name: "Dulcet offline proof", platform: "Apple",
                    maxAudioBitrate: 1_536_000, maxTranscodingAudioBitrate: 320_000,
                    directPlayProfiles: [DirectPlayAudioProfile(containers: [coreContainer], audioCodecs: ["mp3", "aac", "flac", "pcm", "vorbis"], protocols: ["http"], maxAudioChannels: 8)],
                    transcodingProfiles: [TranscodingAudioProfile(container: .mp3, audioCodec: "mp3", protocol: "http", maxAudioChannels: 2)]),
                legacyPreference: LegacyPlaybackPreference(format: nil, maxBitRateKbps: nil), legacyTimeOffset: nil)) {
                    // Keep the Kotlin DTO in this callback's actor region. Only the Swift
                    // Sendable plan crosses the continuation boundary.
                    let plan = $0.plan.map { corePlan in
                        DulcetCorePlaybackPlanFactory.makePlan(client: wire, corePlan: corePlan,
                            metadata: .init(title: track.title, artist: nil, albumTitle: track.albumTitle),
                            wrapResource: { resource, expected in
                                context.controller.cachingResource(resource, track: track, expectedContainer: expected)
                            })
                    }
                    continuation.resume(returning: plan)
                }
        }
        return try XCTUnwrap(resolved)
    }

    /// Runs under each existing Mac/iPhone/iPad download selector, so the regular full Apple
    /// conformance run exercises the same streamed bytes and ownership transitions as the apps.
    private func proveStreamCacheAndAutomaticRetention() async throws {
        let context = try makeContext()
        defer { context.tearDown() }
        let track = try await loadLiveTrack(baseURL: context.baseURL)
        try await waitUntil(timeout: .seconds(10), failureMessage: "offline policy never reconciled") {
            context.controller.offlineLibraryStorage.cacheBudgetBytes > 0
        }
        let endpointAccount = PlaybackEndpointAccount(providerInstanceId: providerInstanceID,
            normalizedBaseUrl: context.baseURL, username: fixtureUsername, password: fixturePassword, allowLocalHttp: true)
        let wire = ApplePlaybackWireClient(account: endpointAccount)
        defer { wire.close() }
        let plan = try await resolveStreamingPlan(wire: wire, context: context, track: track)
        let resource = try XCTUnwrap(plan.resource as? any DulcetPlaybackResourceLoading)
        let head = await load(resource: resource, range: .init(start: 0, endInclusive: 4_095))
        guard case let .loaded(_, headerInfo) = head else { XCTFail("stream header did not validate: \(head), track=\(track.title), container=\(String(describing: track.sourceContainer))"); return }
        // AVFoundation commonly reads the metadata tail before returning to the audio body.
        // Exercise that real request ordering, followed by overlapping sequential reads.
        let tail = await load(resource: resource, range: .init(start: max(0, headerInfo.contentLength - 4_096),
                                                             endInclusive: headerInfo.contentLength - 1))
        guard case .loaded = tail else { XCTFail("out-of-order metadata tail did not validate"); return }
        var streamed = Data()
        var total: Int64 = .max
        while Int64(streamed.count) < total {
            let start = Int64(streamed.count)
            let outcome = await load(resource: resource, range: .init(start: start, endInclusive: min(total - 1, start + 256 * 1024 - 1)))
            guard case let .loaded(bytes, info) = outcome else { XCTFail("streamed bytes did not pass inline validation"); return }
            XCTAssertFalse(bytes.isEmpty)
            guard !bytes.isEmpty else { return }
            total = info.contentLength
            streamed.append(bytes)
        }
        try await waitUntil(timeout: .seconds(10), failureMessage: "streaming response did not promote to cache") {
            context.controller.offlineLibraryStorage.cacheBytes == Int64(streamed.count)
        }
        XCTAssertEqual(context.controller.status(for: track.id), .cached)
        context.controller.closeNetworkAccessForTesting()
        wire.close()
        let cached = try XCTUnwrap(context.controller.offlinePlaybackAsset(for: track))
        let replay = await load(resource: cached.resource, range: .init(start: 0, endInclusive: cached.exactByteLength - 1))
        if case let .loaded(bytes, _) = replay { XCTAssertEqual(bytes, streamed) }
        else { XCTFail("cached song failed with network clients closed") }
        XCTAssertEqual(context.controller.offlineLibraryStorage.downloadBytes, 0)

        // The real Settings executor must trim artwork using the prospective limit before
        // mutating the stream quota. A clear uses a separate target-zero shared plan.
        let originalBudget = context.controller.offlineLibraryStorage.cacheBudgetBytes
        let smallerBudget = max(Int64(streamed.count), originalBudget / 2)
        var artworkBudgetApplied = false
        var artworkClearApplied = false
        context.controller.reconcileArtworkCache = { serverID, intent in
            XCTAssertEqual(serverID, self.providerInstanceID)
            switch intent {
            case let .reconcile(reservationBytes, budgetBytesOverride):
                guard let budgetBytesOverride else { return }
                XCTAssertEqual(reservationBytes, 0)
                XCTAssertEqual(budgetBytesOverride, smallerBudget)
                XCTAssertEqual(context.controller.offlineLibraryStorage.cacheBudgetBytes, originalBudget)
                let plan = context.controller.artworkCachePlan(serverID: serverID, evictableBytes: 20,
                    pinnedBytes: 0, intent: intent)
                XCTAssertNotNil(plan)
                // The executor reports the completed trim before media eviction is considered.
                _ = context.controller.artworkCachePlan(serverID: serverID, evictableBytes: 0,
                    pinnedBytes: 0, intent: intent)
                artworkBudgetApplied = true
            case .clear:
                XCTAssertGreaterThan(context.controller.offlineLibraryStorage.cacheBytes, 0)
                let plan = context.controller.artworkCachePlan(serverID: serverID, evictableBytes: 20,
                    pinnedBytes: 0, intent: intent)
                XCTAssertEqual(plan?.targetEvictableBytes, 0)
                _ = context.controller.artworkCachePlan(serverID: serverID, evictableBytes: 0,
                    pinnedBytes: 0, intent: intent)
                artworkClearApplied = true
            }
        }
        context.controller.setStreamingCacheBudgetBytes(smallerBudget)
        try await waitUntil(timeout: .seconds(10), failureMessage: "cache budget did not follow artwork trim") {
            context.controller.offlineLibraryStorage.cacheBudgetBytes == smallerBudget
        }
        XCTAssertTrue(artworkBudgetApplied)
        XCTAssertEqual(context.controller.offlineLibraryStorage.cacheBytes, Int64(streamed.count))
        context.controller.clearStreamingCache()
        try await waitUntil(timeout: .seconds(10), failureMessage: "clear did not follow artwork trim") { artworkClearApplied }
        // The active playback resource keeps its validated local file protected from Clear.
        XCTAssertNotNil(context.controller.offlinePlaybackAsset(for: track))
        context.controller.reconcileArtworkCache = nil
        print("OFFLINE-CACHE \(platformLabel) validated-stream=tee sparse-ranges=true offline=identical artwork-first=true active-clear=protected bytes=\(streamed.count)")

        // Automatic downloads require the advertised My Library contract. Plain OpenSubsonic
        // retains the cache proof above; the Dulcet Server fixture also runs the real add/remove.
        let automatic = try makeContext()
        defer { automatic.tearDown() }
        let reader = AppleLibraryReaderClient(databaseName: automatic.databaseName,
            account: AppleLibraryReaderAccount(providerInstanceId: providerInstanceID, normalizedBaseUrl: automatic.baseURL,
                username: fixtureUsername, password: fixturePassword, allowLocalHttp: true), foreground: true, readsDownloads: true)
        defer { reader.close() }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            _ = reader.connect { _ in continuation.resume() }
        }
        let library = AppleMyLibraryClient(reader: reader)
        let listener = OfflineLibraryIntegrationListener(rawID: track.id.rawID) {
            automatic.controller.reconcileMyLibrary()
        }
        let subscription = library.subscribe(sort: "recentlyAdded", listener: listener)
        defer { subscription.close() }
        let availability: String = await withCheckedContinuation { continuation in
            _ = library.refresh { continuation.resume(returning: $0.availability) }
        }
        guard availability == "available" else { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            _ = library.warmAutomaticDownloadDetails(albumIds: [], trackIds: [track.id.rawID], playlistIds: [], loadPlaylistIndex: false) {
                _ in continuation.resume()
            }
        }
        automatic.controller.setAutomaticDownloadContactPermitted(false)
        automatic.controller.setAutomaticDownloadsEnabled(true)
        XCTAssertTrue(library.add(kind: "track", rawId: track.id.rawID))
        try await waitUntil(timeout: .seconds(10), failureMessage: "cached My Library member never published") {
            listener.membership == "added"
        }
        let heldConnector = DownloadRecoveryConnector()
        let savedSource = DulcetAccountDataSource(connector: heldConnector,
            credentialStore: DownloadRecoveryCredentialStore(request: .init(serverURL: automatic.baseURL,
                username: fixtureUsername, password: fixturePassword, allowLocalHTTP: true),
                providerInstanceID: providerInstanceID, credentialGeneration: 7, allowsNegotiatedSave: true),
            downloadController: automatic.controller)
        defer { _ = savedSource.currentSnapshot }
        try await waitUntil(timeout: .seconds(10), failureMessage: "saved automatic policy never reconciled") {
            automatic.controller.offlineLibraryStorage.cacheBudgetBytes > 0
        }
        automatic.controller.reconcileMyLibrary()
        XCTAssertEqual(automatic.controller.status(for: track.id), .notDownloaded,
                       "Cached membership cannot create a new download while saved negotiation is held")
        XCTAssertEqual(automatic.controller.offlineLibraryStorage.queuedAutomaticCount, 0)
        XCTAssertTrue(try regularFiles(in: automatic.downloadRoot).isEmpty)
        XCTAssertEqual(savedSource.currentSnapshot.accountConnection, .connecting)
        heldConnector.complete(.connected(.init(serverName: "Disposable fixture", normalizedServerURL: automatic.baseURL)))
        try await waitUntil(timeout: .seconds(60), failureMessage: "My Library add did not automatically download") {
            automatic.controller.status(for: track.id) == .downloaded
        }
        XCTAssertGreaterThan(automatic.controller.offlineLibraryStorage.downloadBytes, 0)
        let explicitUpgrade = expectation(description: "explicit Keep Download ownership recorded")
        automatic.controller.setStatusHandler { id, state in
            if id == track.id, state == .downloaded { explicitUpgrade.fulfill() }
        }
        automatic.controller.requestDownload(track) // Explicit Keep Download upgrades the same row.
        await fulfillment(of: [explicitUpgrade], timeout: 10)
        XCTAssertTrue(library.remove(kind: "track", rawId: track.id.rawID))
        try await waitUntil(timeout: .seconds(10), failureMessage: "My Library removal did not publish") {
            listener.membership == "notAdded"
        }
        XCTAssertNotNil(automatic.controller.offlinePlaybackAsset(for: track), "explicit download must survive My Library removal")
        automatic.controller.setAutomaticDownloadsEnabled(false)
        XCTAssertNotNil(automatic.controller.offlinePlaybackAsset(for: track), "explicit download must survive disabling Automatic Downloads")
        XCTAssertEqual(automatic.controller.status(for: track.id), .downloaded)
        print("OFFLINE-AUTO \(platformLabel) trigger=my-library-add held-contact=no-new-row negotiated=downloaded explicit-survives-remove-and-off=true")
    }

    private func proveOfflinePlaybackLoadsIdenticalBytesAfterNetworkClientsClose() async throws {
        let context = try makeContext()
        defer { context.tearDown() }
        let track = try await loadLiveTrack(baseURL: context.baseURL)
        context.controller.requestDownload(track)

        try await waitUntil(
            timeout: .seconds(60),
            failureMessage: "the independent offline-playback setup download did not complete"
        ) {
            context.controller.status(for: track.id) == .downloaded
        }

        let file = try XCTUnwrap(try regularFiles(in: context.downloadRoot).first)
        let expectedBytes = try Data(contentsOf: file)
        XCTAssertFalse(expectedBytes.isEmpty)

        context.controller.closeNetworkAccessForTesting()
        let asset = try XCTUnwrap(
            context.controller.offlinePlaybackAsset(for: track),
            "the durable policy must still produce a local plan after its network clients close"
        )
        XCTAssertEqual(asset.exactByteLength, Int64(expectedBytes.count))
        let outcome = await load(
            resource: asset.resource,
            range: DulcetPlaybackByteRange(
                start: 0,
                endInclusive: asset.exactByteLength - 1
            )
        )
        switch outcome {
        case let .loaded(actualBytes, information):
            XCTAssertEqual(actualBytes, expectedBytes)
            XCTAssertEqual(information.contentLength, Int64(expectedBytes.count))
            XCTAssertTrue(information.supportsByteRanges)
        case let .failed(error, refreshReason):
            XCTFail(
                "local playback failed after network close: error=\(error)"
                    + " refresh=\(String(describing: refreshReason))"
            )
        case .cancelled:
            XCTFail("local playback was unexpectedly cancelled after network close")
        }

        print(
            "CONF-52 \(platformLabel) OFFLINE PLAYBACK network=closed"
                + " source=local-file identical_bytes=\(expectedBytes.count)"
        )
    }

    private var platformLabel: String {
        #if os(macOS)
        "MACOS"
        #elseif os(iOS)
        UIDevice.current.userInterfaceIdiom == .pad ? "IPADOS" : "IOS"
        #endif
    }

    private func makeContext() throws -> DownloadIntegrationContext {
        let environment = ProcessInfo.processInfo.environment
        let baseURL = environment["DULCET_CONFORMANCE_BASE_URL"] ?? ""
        let disposable = environment["DULCET_CONFORMANCE_DISPOSABLE"]
        var violations: [String] = []

        if disposable == nil {
            violations.append("DULCET_CONFORMANCE_DISPOSABLE is missing from the test process")
        } else if disposable != "true" {
            violations.append("DULCET_CONFORMANCE_DISPOSABLE must equal true")
        }

        if baseURL.isEmpty {
            violations.append("DULCET_CONFORMANCE_BASE_URL is missing or empty in the test process")
        } else if let components = URLComponents(string: baseURL) {
            if components.scheme != "http" {
                violations.append("DULCET_CONFORMANCE_BASE_URL scheme must be http")
            }
            if components.host != "127.0.0.1" {
                violations.append("DULCET_CONFORMANCE_BASE_URL host must be literal 127.0.0.1")
            }
            if components.port == nil {
                violations.append("DULCET_CONFORMANCE_BASE_URL must include a port")
            }
            if !components.path.isEmpty {
                violations.append("DULCET_CONFORMANCE_BASE_URL path must be empty")
            }
            if components.query != nil {
                violations.append("DULCET_CONFORMANCE_BASE_URL query must be absent")
            }
            if components.fragment != nil {
                violations.append("DULCET_CONFORMANCE_BASE_URL fragment must be absent")
            }
        } else {
            violations.append("DULCET_CONFORMANCE_BASE_URL must be a valid URL")
        }

        guard violations.isEmpty else {
            XCTFail(
                "downloads may run only against the disposable loopback conformance server; "
                    + "environment violations: \(violations.joined(separator: "; "))"
            )
            throw DownloadIntegrationPrecondition.invalidEnvironment
        }
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("dulcet-apple-download-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let databaseName = "dulcet-apple-download-\(UUID().uuidString).db"
        let controller = DulcetCoreDownloadController(
            databaseName: databaseName,
            downloadRootURL: root,
            sessionConfiguration: .ephemeral
        )
        controller.configure(account: DulcetPlaybackAccount(
            providerInstanceID: providerInstanceID,
            normalizedServerURL: baseURL,
            username: fixtureUsername,
            password: fixturePassword,
            allowLocalHTTP: true,
            credentialGeneration: 7
        ))
        controller.setAutomaticDownloadContactPermitted(true)
        return DownloadIntegrationContext(
            controller: controller,
            downloadRoot: root,
            baseURL: baseURL,
            databaseName: databaseName
        )
    }

    private func loadLiveTrack(baseURL: String, minimumDurationMilliseconds: Int64 = 0,
                               maximumDurationMilliseconds: Int64 = .max) async throws -> DulcetTrack {
        let client = AppleLibraryBrowseClient()
        let request = AppleLibraryBrowseRequest(
            providerInstanceId: providerInstanceID,
            normalizedBaseUrl: baseURL,
            username: fixtureUsername,
            password: fixturePassword,
            allowLocalHttp: true
        )
        // The album grid is read without a request per album, so a browse answers albums with
        // empty track lists and a declared count. A download seed is a TRACK, so this reads one
        // album's tracks through the same lazy path the album screen uses, taking the first album
        // that declares any. Every failure below still names what was actually seen: a bare
        // unwrap here would turn "the corpus has no downloadable format" and "the server stopped
        // answering" into the same nil.
        let albums = try await browseAlbumSeeds(client: client, request: request)
        var albumsRead = 0
        var tracksSeen = 0
        var albumsDeclaringTracks = 0
        var seed: LiveDownloadTrackSeed?
        for album in albums where album.trackCount > 0 {
            albumsDeclaringTracks += 1
            let read = try await readAlbumSeed(
                client: client,
                request: request,
                albumRawID: album.rawID,
                minimumDurationMilliseconds: minimumDurationMilliseconds,
                maximumDurationMilliseconds: maximumDurationMilliseconds
            )
            albumsRead += 1
            tracksSeen += read.trackCount
            if let source = read.seed {
                seed = source
                break
            }
        }
        let source = try XCTUnwrap(
            seed,
            "the disposable library must expose one downloadable track; "
                + "albums=\(albums.count), albums declaring tracks=\(albumsDeclaringTracks), "
                + "albums read=\(albumsRead), tracks seen=\(tracksSeen), "
                + "tracks carrying a source container=0"
        )
        let container = try XCTUnwrap(
            source.sourceContainer.flatMap(downloadContainer),
            "download seed has an unsupported source container: \(source.sourceContainer ?? "missing")"
        )
        return DulcetTrack(
            id: DulcetProviderItemID(
                providerInstanceID: source.providerInstanceID,
                rawID: source.rawID
            ),
            title: source.title,
            credits: [],
            albumTitle: source.albumTitle,
            discNumber: source.discNumber,
            trackNumber: source.trackNumber,
            duration: .milliseconds(source.durationMilliseconds),
            sourceContainer: container,
            mediaSourceID: source.mediaSourceID,
            artwork: DulcetArtwork(seed: "download-integration", palette: .indigoCoral)
        )
    }

    /// Kotlin DTOs are not `Sendable`, so every continuation below extracts plain values inside
    /// the callback rather than carrying a DTO across the boundary.
    private func browseAlbumSeeds(
        client: AppleLibraryBrowseClient,
        request: AppleLibraryBrowseRequest
    ) async throws -> [LiveDownloadAlbumSeed] {
        let (albums, failure): ([LiveDownloadAlbumSeed]?, String) =
            await withCheckedContinuation { continuation in
                _ = client.startBrowse(request: request) { outcome in
                    if let error = outcome.error {
                        continuation.resume(returning: (nil, "library browse failed: kind=\(error.kind)"))
                        return
                    }
                    guard let snapshot = outcome.snapshot else {
                        continuation.resume(
                            returning: (nil, "library browse returned neither snapshot nor error")
                        )
                        return
                    }
                    guard !snapshot.albums.isEmpty else {
                        continuation.resume(returning: (nil, "library empty: albums=0"))
                        return
                    }
                    continuation.resume(returning: (
                        snapshot.albums.map {
                            LiveDownloadAlbumSeed(rawID: $0.rawId, trackCount: Int($0.trackCount))
                        },
                        ""
                    ))
                }
            }
        return try XCTUnwrap(
            albums,
            "the disposable library must expose at least one album; \(failure)"
        )
    }

    private func readAlbumSeed(
        client: AppleLibraryBrowseClient,
        request: AppleLibraryBrowseRequest,
        albumRawID: String,
        minimumDurationMilliseconds: Int64 = 0,
        maximumDurationMilliseconds: Int64 = .max
    ) async throws -> (seed: LiveDownloadTrackSeed?, trackCount: Int) {
        let (result, failure): ((LiveDownloadTrackSeed?, Int)?, String) =
            await withCheckedContinuation { continuation in
                _ = client.startAlbumTracks(request: request, albumRawId: albumRawID) { outcome in
                    if let error = outcome.error {
                        continuation.resume(
                            returning: (nil, "album track read failed: kind=\(error.kind)")
                        )
                        return
                    }
                    guard let album = outcome.album else {
                        continuation.resume(
                            returning: (nil, "album track read returned neither album nor error")
                        )
                        return
                    }
                    let source = album.tracks.first {
                        $0.sourceContainer != nil && $0.durationMilliseconds >= minimumDurationMilliseconds
                            && $0.durationMilliseconds <= maximumDurationMilliseconds
                    }
                    continuation.resume(returning: ((
                        source.map {
                            LiveDownloadTrackSeed(
                                providerInstanceID: $0.providerInstanceId,
                                rawID: $0.rawId,
                                title: $0.title,
                                albumTitle: $0.albumTitle,
                                discNumber: $0.discNumber?.intValue,
                                trackNumber: $0.trackNumber?.intValue,
                                durationMilliseconds: $0.durationMilliseconds,
                                sourceContainer: $0.sourceContainer,
                                mediaSourceID: $0.mediaSourceId
                            )
                        },
                        album.tracks.count
                    ), ""))
                }
            }
        let unwrapped = try XCTUnwrap(
            result,
            "reading album \(albumRawID) must answer a track list; \(failure)"
        )
        return (unwrapped.0, unwrapped.1)
    }

    private func downloadContainer(coreName: String) -> DulcetAudioContainer? {
        switch coreName {
        case "Mp3": .mp3
        case "Mp4": .mp4
        case "Wav": .wav
        case "Flac": .flac
        case "Ogg": .ogg
        case "AdtsAac": .adtsAAC
        default: nil
        }
    }

    private func regularFiles(in root: URL) throws -> [URL] {
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: []
        ) else { return [] }
        return try enumerator.compactMap { element in
            guard let url = element as? URL,
                  try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else {
                return nil
            }
            return url
        }
    }

    private func load(
        resource: any DulcetPlaybackResourceLoading,
        range: DulcetPlaybackByteRange
    ) async -> DulcetPlaybackResourceLoadOutcome {
        // The caller owns an asynchronous load, just as the production AV resource loader does.
        // Its callbacks weakly reference the operation so dropping it means cancellation.
        var operation: (any DulcetPlaybackResourceLoadOperation)?
        let outcome: DulcetPlaybackResourceLoadOutcome = await withCheckedContinuation { continuation in
            operation = resource.load(DulcetPlaybackResourceLoadRequest(
                range: range,
                requiresAudioSignature: range.start == 0
            )) { outcome in
                continuation.resume(returning: outcome)
            }
        }
        withExtendedLifetime(operation) { }
        return outcome
    }

    private func waitUntil(
        timeout: Duration,
        failureMessage: @autoclosure () -> String,
        condition: @MainActor () -> Bool
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout)
        while !condition() {
            if clock.now >= deadline {
                XCTFail(failureMessage())
                return
            }
            try await Task.sleep(for: .milliseconds(50))
        }
    }
}

private struct LiveDownloadAlbumSeed: Sendable {
    let rawID: String
    /// The count the server declares on the album list, which is what says an album is worth
    /// reading without reading it first.
    let trackCount: Int
}

private struct LiveDownloadTrackSeed: Sendable {
    let providerInstanceID: String
    let rawID: String
    let title: String
    let albumTitle: String?
    let discNumber: Int?
    let trackNumber: Int?
    let durationMilliseconds: Int64
    let sourceContainer: String?
    let mediaSourceID: String?
}

private enum DownloadIntegrationPrecondition: Error {
    case invalidEnvironment
}

@MainActor
private struct DownloadIntegrationContext {
    let controller: DulcetCoreDownloadController
    let downloadRoot: URL
    let baseURL: String
    let databaseName: String

    func tearDown() {
        controller.disconnect()
        try? FileManager.default.removeItem(at: downloadRoot)
    }
}

private struct DownloadRecoveryWireEntry {
    let endpoint: String
    let rawID: String?
    let answered: Bool
    let status: Int
}

@MainActor
private final class DownloadRecoveryCredentialStore: DulcetProviderInstanceCredentialStoring {
    let request: DulcetAccountConnectRequest
    let providerInstanceID: String?
    let credentialGeneration: Int64
    private let allowsNegotiatedSave: Bool

    init(request: DulcetAccountConnectRequest, providerInstanceID: String, credentialGeneration: Int64,
         allowsNegotiatedSave: Bool = false) {
        self.request = request
        self.providerInstanceID = providerInstanceID
        self.credentialGeneration = credentialGeneration
        self.allowsNegotiatedSave = allowsNegotiatedSave
    }

    func load() throws -> DulcetAccountConnectRequest? { request }
    func save(_ request: DulcetAccountConnectRequest) throws {
        if !allowsNegotiatedSave { XCTFail("saved launch unexpectedly saved credentials") }
    }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        if !allowsNegotiatedSave { XCTFail("saved launch unexpectedly saved an account") }
    }
    func delete() throws { XCTFail("saved launch unexpectedly deleted credentials") }
}

@MainActor
private final class DownloadRecoveryConnector: DulcetAccountConnecting {
    private(set) var requestCount = 0
    private var completion: (@MainActor (DulcetAccountConnectOutcome) -> Void)?

    func complete(_ outcome: DulcetAccountConnectOutcome) {
        let callback = completion
        completion = nil
        callback?(outcome)
    }

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        requestCount += 1
        self.completion = completion
        return DownloadRecoveryConnectOperation()
    }
}

@MainActor
private final class DownloadRecoveryConnectOperation: DulcetAccountConnectOperation {
    func cancel() {}
}


@MainActor
private final class OfflineLibraryIntegrationListener: NSObject, AppleMyLibraryListener {
    private nonisolated let rawID: String
    private let handler: @MainActor () -> Void
    var membership = "notAdded"
    init(rawID: String, handler: @escaping @MainActor () -> Void) {
        self.rawID = rawID
        self.handler = handler
    }
    nonisolated func onMyLibraryPublication(publication: AppleMyLibraryPublication) {
        // The facade documents main-thread delivery. Extract a Sendable value before the
        // actor check, so the Kotlin publication never enters another Swift isolation region.
        let value = publication.membership(kind: "track", rawId: rawID)
        MainActor.assumeIsolated { membership = value; handler() }
    }
}

private final class OfflineEngineProgress: @unchecked Sendable {
    private let lock = NSLock()
    private var storedPosition: TimeInterval = 0
    private var storedFailure: String?
    var position: TimeInterval { lock.lock(); defer { lock.unlock() }; return storedPosition }
    var failure: String? { lock.lock(); defer { lock.unlock() }; return storedFailure }
    func record(_ event: DulcetPlaybackEvent) {
        lock.lock()
        defer { lock.unlock() }
        switch event {
        case let .playbackProgressBegan(_, _, mediaPosition), let .positionChanged(_, mediaPosition, _):
            storedPosition = max(storedPosition, mediaPosition)
        case let .failedBeforeStart(_, error), let .failedAfterPartial(_, _, error):
            storedFailure = String(describing: error)
        default: break
        }
    }
}
