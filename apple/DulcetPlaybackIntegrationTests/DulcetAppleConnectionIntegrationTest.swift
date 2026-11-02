import AVFoundation
import CryptoKit
import DulcetCore
@testable import DulcetKit
import Foundation
import XCTest
import SQLite3

/// MP-10 executes the production Apple network paths. The fixture is a fresh local Dulcet Server,
/// and its CA is installed in the isolated runner trust store before this selector is launched.
/// No test-only TLS delegate or production certificate exception exists in this proof.
@MainActor
final class DulcetAppleConnectionIntegrationTest: XCTestCase {
    private var operations: [any ApplePlaybackWireOperation] = []
    private var artworkOperations: [any AppleArtworkFetchOperation] = []

    #if !os(tvOS)
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
        // Reconciliation is a per-client gate. The controller reconciles its own client;
        // this independent local-file observer must acquire readiness before any task starts.
        let unreconciled = bindings.localPlaybackPlan(rawId: oldTrack.id.rawID)
        XCTAssertNil(unreconciled.plan)
        XCTAssertEqual(unreconciled.errorKind, "persistence")
        XCTAssertEqual(unreconciled.unavailableKind, "notDownloaded")
        let reconciliation = bindings.reconcile(outstandingTaskIdentifiers: [], credentialGeneration: 1)
        XCTAssertNil(reconciliation.errorKind)
        XCTAssertTrue(bindings.isMediaBindingCurrent())
        downloads.requestDownload(oldTrack)
        try await eventually("The legacy HTTP positive download did not complete") {
            downloads.status(for: oldTrack.id) == .downloaded
        }
        let oldOutcome = bindings.localPlaybackPlan(rawId: oldTrack.id.rawID)
        XCTAssertNil(oldOutcome.errorKind)
        let oldFile = try XCTUnwrap(oldOutcome.plan,
            "Completed legacy file: error=\(oldOutcome.errorKind ?? "none") unavailable=\(oldOutcome.unavailableKind ?? "none") bindingCurrent=\(bindings.isMediaBindingCurrent())")
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
        let afterPlayer = try await fixture.state()
        let playerStreamCount = afterPlayer.endpointCounts["home"]?["stream"] ?? 0
        XCTAssertGreaterThan(playerStreamCount, 0, "The actual AVPlayer must issue HTTPS media requests")

        // The real URLSession downloader and Darwin artwork transport still receive the frozen
        // HTTP ownership account; the shared request factory is the only place that substitutes HTTPS.
        let newTrack = fixture.track(2, provider: provider)
        downloads.requestDownload(newTrack)
        try await eventually("The adopted account's URLSession download did not complete") {
            downloads.status(for: newTrack.id) == .downloaded
        }
        let newOutcome = bindings.localPlaybackPlan(rawId: newTrack.id.rawID)
        XCTAssertNil(newOutcome.errorKind)
        let newFile = try XCTUnwrap(newOutcome.plan,
            "Completed adopted file: error=\(newOutcome.errorKind ?? "none") unavailable=\(newOutcome.unavailableKind ?? "none") bindingCurrent=\(bindings.isMediaBindingCurrent())")
        XCTAssertEqual(newFile.exactByteLength, Int64(fixture.tracks[2].sourceBytes))
        XCTAssertEqual(try digest(URL(fileURLWithPath: newFile.filePath)), fixture.tracks[2].sourceSha256)
        let artwork = await fetchArtwork(fixture: fixture, provider: provider, artworkID: fixture.tracks[2].artworkId)
        XCTAssertNotNil(artwork)
        XCTAssertGreaterThan(artwork?.count ?? 0, 0)
        let successful = try await fixture.state()
        XCTAssertEqual(successful.counts["legacy", default: 0], 0)
        // Original-file URLSession downloads use stream?format=raw. Require a new HTTPS
        // request after the released player's boundary, independently of its positive count.
        XCTAssertGreaterThan(successful.endpointCounts["home"]?["stream"] ?? 0, playerStreamCount,
            "The completed URLSession download must add its own HTTPS media request")
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
        print("DULCET MP-10 APPLE COMPLETED paths=AVPlayer-resource-loader,URLSession-download,artwork assertions=xcresult")
    }

    #endif

    func testMP04AdvancingAVPlayerRecoversThroughTheSelectorWithTheSameListen() async throws {
        try await nativePlaybackHandoff(transcoded: false)
    }

    func testMP04LegacyTranscodeRecoversWithAnAdvertisedOffsetAndAbsoluteMediaTime() async throws {
        try await nativePlaybackHandoff(transcoded: true)
    }

    private func nativePlaybackHandoff(transcoded: Bool) async throws {
        let fixture = try ConnectionProofFixture.load()
        _ = try await fixture.control("reset", method: "POST")
        if transcoded { XCTAssertEqual(fixture.transcodeOffsetV1, true) }
        XCTAssertEqual(fixture.phase, 2)
        let provider = "apple-handoff-\(UUID())"
        let name = "dulcet-handoff-\(UUID()).db"
        let account = DulcetPlaybackAccount(providerInstanceID: provider, normalizedServerURL: fixture.legacyHttp,
            username: fixture.username, password: fixture.password, allowLocalHTTP: true, credentialGeneration: 1)
        XCTAssertTrue(AppleMediaBindingClient.shared.acceptServerIdentity(databaseName: name,
            providerInstanceId: provider, identity: fixture.serverIdentity))
        establishFreshMediaOwnership(name, fixture: fixture, provider: provider)
        let connections = AppleConnectionsClient(databaseName: name)
        let enrollment = await enroll(connections, fixture: fixture, provider: provider)
        XCTAssertNil(enrollment.error)
        let queue = ApplePlaybackQueueClient(databaseName: name)
        let engine = DulcetAVPlayerEngine()
        let controller = DulcetCorePlaybackController(queueClient: queue, engine: engine, catalog: [], identityDatabaseName: name)
        defer { controller.disconnect(); queue.close(); removeConnectionProofDatabase(name) }
        let qualitySuite = "dulcet-handoff-quality-\(UUID())"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: qualitySuite))
        defer { defaults.removePersistentDomain(forName: qualitySuite) }
        let quality = DulcetCoreStreamingQuality(defaults: defaults, monitorsNetwork: false)
        quality.setPreference(.init(unmetered: transcoded ? .kbps96 : .original, metered: transcoded ? .kbps96 : .original))
        controller.streamingQuality = quality
        controller.configure(account: account)
        _ = await execute(engine, .setVolume(commandID: .init("handoff-mute"), volume: 0))
        _ = try await fixture.control("reset", method: "POST")
        _ = try await fixture.control("throttle", method: "POST", query: ["routeId": "home", "endpoint": "stream", "bytesPerSecond": "32768"])
        let track = fixture.track(1, provider: provider)
        controller.replaceQueueAndPlay(.init(tracks: [track], sourceKind: .album,
            sourceID: .init(providerInstanceID: provider, rawID: fixture.tracks[1].albumId),
            sourceDisplayName: "Disposable handoff album", startIndex: 0, shuffle: false))
        try await eventually("The actual AVPlayer decoder did not advance before the cut") {
            (queue.snapshot().snapshot?.currentSession?.positionMilliseconds ?? 0) >= 1_000
        }
        let before = try XCTUnwrap(queue.snapshot().snapshot?.currentSession)
        _ = try await fixture.control("cut", method: "POST", query: ["routeId": "home", "endpoint": "stream", "bytes": "131072"])
        try await eventually("AVPlayer did not create a fresh alternate-route attempt and resume confirmed media time") {
            guard let session = queue.snapshot().snapshot?.currentSession else { return false }
            return session.attemptId != before.attemptId && session.positionMilliseconds > before.positionMilliseconds + 1_000
        }
        let after = try XCTUnwrap(queue.snapshot().snapshot?.currentSession)
        XCTAssertEqual(after.queueEntryId, before.queueEntryId)
        XCTAssertEqual(after.playbackSessionId, before.playbackSessionId)
        XCTAssertNotEqual(after.attemptId, before.attemptId)
        XCTAssertEqual(queue.snapshot().snapshot?.currentIndex, 0)
        let receipts = try await fixture.state()
        XCTAssertTrue(receipts.requests.contains { $0.routeId == "home" && $0.endpoint == "stream" && $0.cut && $0.responseBytes > 0 })
        XCTAssertTrue(receipts.requests.contains { $0.routeId == "tunnel" && $0.endpoint == "stream" && $0.responseBytes > 0 })
        print("DULCET MP04 origins=\(receipts.requests.filter { $0.endpoint == "stream" }.map { "\($0.routeId):\($0.requestOrigin ?? "none"):range=\($0.rangeStart ?? -1):offset=\($0.timeOffsetSeconds ?? -1)" })")
        if !transcoded {
            XCTAssertTrue(receipts.requests.contains { $0.routeId == "tunnel" && $0.endpoint == "stream" && ($0.rangeStart ?? 0) > 0 },
                "Original-file recovery must resume with Range on the alternate route")
        }
        if transcoded {
            XCTAssertFalse(queue.acceptsCommand(playbackSessionId: after.playbackSessionId, requiresSeekable: true),
                "A server-offset remainder cannot expose absolute interactive seek as a native byte seek")
            XCTAssertTrue(receipts.requests.contains { $0.routeId == "tunnel" && $0.endpoint == "stream" &&
                ($0.timeOffsetSeconds ?? -1) >= before.positionMilliseconds / 1_000 })
        }
        controller.send(.pause)
        try await eventually("The recovered player did not honor pause") { queue.snapshot().snapshot?.currentSession?.phase == "Paused" }
        let paused = try XCTUnwrap(queue.snapshot().snapshot?.currentSession)
        try await Task.sleep(for: .seconds(1))
        XCTAssertEqual(queue.snapshot().snapshot?.currentSession?.phase, "Paused")
        XCTAssertLessThanOrEqual(queue.snapshot().snapshot?.currentSession?.positionMilliseconds ?? .max, paused.positionMilliseconds + 250)
        _ = try await fixture.control("outage", method: "POST", query: ["routeId": "allHttps", "state": "off"])
        _ = try await fixture.control("throttle", method: "POST", query: ["routeId": "home", "endpoint": "stream", "bytesPerSecond": "0"])
        print("DULCET MP-04 APPLE decoder=AVPlayer advancing-cut-advancing=true queue-session=preserved attempt=new paused=preserved")
    }

    private func removeConnectionProofDatabase(_ name: String) {
        let url = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support/databases/\(name)")
        for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: url.path + suffix) }
    }

    #if !os(tvOS)
    func testMP05CutExplicitAndAutomaticURLSessionDownloadsRestartWithTheSameIDsAndExactHashes() async throws {
        let fixture = try ConnectionProofFixture.load()
        XCTAssertEqual(fixture.phase, 2)
        for automatic in [false, true] {
            _ = try await fixture.control("reset", method: "POST")
            let provider = "apple-download-handoff-\(UUID())"
            let name = "dulcet-download-handoff-\(UUID()).db"
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-handoff-\(UUID())")
            let account = DulcetPlaybackAccount(providerInstanceID: provider, normalizedServerURL: fixture.legacyHttp,
                username: fixture.username, password: fixture.password, allowLocalHTTP: true, credentialGeneration: 1)
            XCTAssertTrue(AppleMediaBindingClient.shared.acceptServerIdentity(databaseName: name,
                providerInstanceId: provider, identity: fixture.serverIdentity))
            establishFreshMediaOwnership(name, fixture: fixture, provider: provider)
            let enrollment = await enroll(AppleConnectionsClient(databaseName: name), fixture: fixture, provider: provider)
            XCTAssertNil(enrollment.error)
            var downloads = DulcetCoreDownloadController(databaseName: name, downloadRootURL: root, sessionConfiguration: .ephemeral)
            downloads.configure(account: account)
            downloads.setAutomaticDownloadContactPermitted(false)
            downloads.setAutomaticDownloadsEnabled(automatic)
            let track = fixture.track(automatic ? 3 : 2, provider: provider)
            let reader = AppleLibraryReaderClient(databaseName: name,
                account: AppleLibraryReaderAccount(providerInstanceId: provider, normalizedBaseUrl: fixture.legacyHttp,
                    username: fixture.username, password: fixture.password, allowLocalHttp: true), foreground: true, readsDownloads: true)
            defer { reader.close(); downloads.disconnect(); try? FileManager.default.removeItem(at: root); removeConnectionProofDatabase(name) }
            var subscription: AppleMyLibrarySubscription?
            defer { subscription?.close() }
            if automatic {
                let connectionError: String? = await withCheckedContinuation { continuation in
                    _ = reader.connect { continuation.resume(returning: $0.errorKind) }
                }
                XCTAssertNil(connectionError, "The automatic proof requires the real advertised My Library contract")
                let library = AppleMyLibraryClient(reader: reader)
                let listener = ConnectionProofMembership(rawID: track.id.rawID) { downloads.reconcileMyLibrary() }
                subscription = library.subscribe(sort: "recentlyAdded", listener: listener)
                let available: String = await withCheckedContinuation { continuation in
                    _ = library.refresh { continuation.resume(returning: $0.availability) }
                }
                XCTAssertEqual(available, "available")
                await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                    _ = library.warmAutomaticDownloadDetails(albumIds: [], trackIds: [track.id.rawID], playlistIds: [], loadPlaylistIndex: false) { _ in continuation.resume() }
                }
                XCTAssertTrue(library.add(kind: "track", rawId: track.id.rawID))
                try await eventually("Browse Add did not publish its retained membership") { listener.membership == "added" }
            }
            _ = try await fixture.control("throttle", method: "POST", query: ["routeId": "home", "endpoint": "stream", "bytesPerSecond": "32768"])
            _ = try await fixture.control("cut", method: "POST", query: ["routeId": "home", "endpoint": "stream", "bytes": "65536"])
            if automatic { downloads.setAutomaticDownloadContactPermitted(true); downloads.reconcileMyLibrary() }
            else { downloads.requestDownload(track) }
            try await eventually("The original native transfer never started") { downloads.status(for: track.id) == .downloading }
            let original = try connectionDownloadWitness(name, rawID: track.id.rawID)
            XCTAssertEqual(original.explicit, automatic ? 0 : 1)
            XCTAssertEqual(original.automatic, automatic ? 1 : 0)
            try await eventually("The positive-byte cut did not interrupt the actual URLSession task") { downloads.status(for: track.id) == .interrupted }
            XCTAssertNil(downloads.offlinePlaybackAsset(for: track), "An incomplete transfer cannot be played or promoted")
            downloads.disconnect()
            downloads = DulcetCoreDownloadController(databaseName: name, downloadRootURL: root, sessionConfiguration: .ephemeral)
            downloads.configure(account: account)
            downloads.setAutomaticDownloadContactPermitted(true)
            try await eventually("The relaunched native executor did not finish the same durable intent") { downloads.status(for: track.id) == .downloaded }
            let completed = try connectionDownloadWitness(name, rawID: track.id.rawID)
            XCTAssertEqual(completed, original, "DownloadId, explicit/automatic retention and rank survive route failure and executor relaunch")
            let asset = try XCTUnwrap(downloads.offlinePlaybackAsset(for: track))
            XCTAssertEqual(asset.exactByteLength, Int64(fixture.tracks[automatic ? 3 : 2].sourceBytes))
            let delivered = await connectionLoad(asset.resource, length: asset.exactByteLength)
            XCTAssertEqual(SHA256.hash(data: delivered).map { String(format: "%02x", $0) }.joined(), fixture.tracks[automatic ? 3 : 2].sourceSha256)
            let receipts = try await fixture.state()
            XCTAssertTrue(receipts.requests.contains { $0.routeId == "home" && $0.endpoint == "stream" && $0.cut && $0.responseBytes > 0 })
            XCTAssertTrue(receipts.requests.contains { $0.routeId == "tunnel" && $0.endpoint == "stream" && $0.completed && !$0.range })
            // The exact installed bytes are readable after all network routes disappear.
            _ = try await fixture.control("outage", method: "POST", query: ["routeId": "allHttps", "state": "on"])
            downloads.closeNetworkAccessForTesting()
            let engine = DulcetAVPlayerEngine()
            let progress = ConnectionProofPlayerProgress()
            engine.setEventListener { progress.record($0) }
            let local = DulcetPlaybackPlan(playbackSessionID: .init("handoff-offline-session"), attemptID: .init("handoff-offline-attempt"),
                deliveryProtocol: .httpProgressive, expectedContainer: asset.expectedContainer, resource: asset.resource,
                metadata: .init(title: track.title, artist: nil, albumTitle: track.albumTitle))
            _ = await execute(engine, .setVolume(commandID: .init("offline-mute"), volume: 0))
            _ = await execute(engine, .prepare(commandID: .init("offline-prepare"), plan: local))
            _ = await execute(engine, .play(commandID: .init("offline-play")))
            try await eventually("The exact installed handoff file did not advance offline AVPlayer media time") { progress.position > 0 }
            engine.stopForAccountChange()
            print("DULCET MP-05 APPLE intent=\(automatic ? "automatic" : "explicit") cut=positive relaunch=1 same-download-id=1 restart=zero hash=exact offline-player=advancing")
        }
        _ = try await fixture.control("reset", method: "POST")
    }

    func testMP07BrowseAlbumLostAcknowledgementExpandsRetentionAndPlaysOffline() async throws {
        let fixture = try ConnectionProofFixture.load()
        _ = try await fixture.control("reset", method: "POST")
        let provider = "apple-album-handoff-\(UUID())"
        let name = "dulcet-album-handoff-\(UUID()).db"
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-album-handoff-\(UUID())")
        XCTAssertTrue(AppleMediaBindingClient.shared.acceptServerIdentity(databaseName: name,
            providerInstanceId: provider, identity: fixture.serverIdentity))
        establishFreshMediaOwnership(name, fixture: fixture, provider: provider)
        let enrollment = await enroll(AppleConnectionsClient(databaseName: name), fixture: fixture, provider: provider)
        XCTAssertNil(enrollment.error)
        let account = DulcetPlaybackAccount(providerInstanceID: provider, normalizedServerURL: fixture.legacyHttp,
            username: fixture.username, password: fixture.password, allowLocalHTTP: true, credentialGeneration: 1)
        let downloads = DulcetCoreDownloadController(databaseName: name, downloadRootURL: root, sessionConfiguration: .ephemeral)
        downloads.configure(account: account)
        downloads.setAutomaticDownloadsEnabled(false)
        downloads.setAutomaticDownloadContactPermitted(false)
        let reader = AppleLibraryReaderClient(databaseName: name,
            account: AppleLibraryReaderAccount(providerInstanceId: provider, normalizedBaseUrl: fixture.legacyHttp,
                username: fixture.username, password: fixture.password, allowLocalHttp: true), foreground: false, readsDownloads: true)
        defer { reader.close(); downloads.disconnect(); try? FileManager.default.removeItem(at: root); removeConnectionProofDatabase(name) }
        let connectionError: String? = await withCheckedContinuation { continuation in
            _ = reader.connect { continuation.resume(returning: $0.errorKind) }
        }
        XCTAssertNil(connectionError)
        let library = AppleMyLibraryClient(reader: reader)
        let albumID = try XCTUnwrap(fixture.tracks.first?.albumId)
        XCTAssertTrue(fixture.tracks.allSatisfy { $0.albumId == albumID })
        let listener = ConnectionProofMembership(rawID: albumID, kind: "album") { downloads.reconcileMyLibrary() }
        let subscription = library.subscribe(sort: "recentlyAdded", listener: listener)
        defer { subscription.close() }
        let availability: String = await withCheckedContinuation { continuation in
            _ = library.refresh { continuation.resume(returning: $0.availability) }
        }
        XCTAssertEqual(availability, "available")
        XCTAssertTrue(library.remove(kind: "album", rawId: albumID))
        try await waitForConnectionReceipt(fixture) { $0.endpoint == "removeFromDulcetLibrary" && $0.completed }
        try await eventually("Initial album removal must reach the disposable server") {
            listener.membership == "notAdded" && (try? self.pendingConnectionMutations(name)).map { $0.isEmpty } == true
        }
        // This is the Browse album-detail read used to warm automatic children, before Add.
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            _ = library.warmAutomaticDownloadDetails(albumIds: [albumID], trackIds: [], playlistIds: [], loadPlaylistIndex: false) { _ in continuation.resume() }
        }
        reader.setOnline(reachable: false)
        XCTAssertTrue(library.add(kind: "album", rawId: albumID))
        try await eventually("Browse Add must be durable before any acknowledgement") {
            (try? self.pendingConnectionMutations(name)).map { !$0.isEmpty } == true
        }
        let pending = try pendingConnectionMutations(name)
        _ = try await fixture.control("lose-reply", method: "POST", query: ["routeId": "home", "endpoint": "addToDulcetLibrary", "state": "on"])
        reader.setOnline(reachable: true)
        var lost = false
        for _ in 0..<400 {
            lost = try await fixture.state().requests.contains { $0.routeId == "home" && $0.endpoint == "addToDulcetLibrary" && $0.lostReply }
            if lost { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertTrue(lost, "The real album write must commit before its reply is cut")
        reader.setOnline(reachable: false)
        XCTAssertEqual(try pendingConnectionMutations(name), pending, "Route recovery retains desired value, issue and action time")
        ConnectionNetworkEvents.shared.topologyChanged()
        reader.setOnline(reachable: true)
        let reconnectError: String? = await withCheckedContinuation { continuation in
            _ = reader.reconnect { continuation.resume(returning: $0.errorKind) }
        }
        XCTAssertNil(reconnectError)
        try await eventually("Alternate acknowledgement must publish the same retained album") {
            listener.membership == "added" && (try? self.pendingConnectionMutations(name)).map { $0.isEmpty } == true
        }
        let acknowledgements = try await fixture.state()
        XCTAssertTrue(acknowledgements.requests.contains { $0.routeId != "home" && $0.endpoint == "addToDulcetLibrary" && $0.completed })
        downloads.setAutomaticDownloadContactPermitted(true)
        downloads.setAutomaticDownloadsEnabled(true)
        downloads.reconcileMyLibrary()
        let tracks = fixture.tracks.indices.map { fixture.track($0, provider: provider) }
        try await eventually("Acknowledged album must expand and install every automatic child") {
            tracks.allSatisfy { downloads.status(for: $0.id) == .downloaded }
        }
        for (index, track) in tracks.enumerated() {
            let witness = try connectionDownloadWitness(name, rawID: track.id.rawID)
            XCTAssertEqual(witness.automatic, 1)
            let asset = try XCTUnwrap(downloads.offlinePlaybackAsset(for: track))
            let bytes = await connectionLoad(asset.resource, length: asset.exactByteLength)
            XCTAssertEqual(SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(), fixture.tracks[index].sourceSha256)
        }
        // A path outage and failed membership read cannot turn installed children into removals.
        _ = try await fixture.control("outage", method: "POST", query: ["routeId": "allHttps", "state": "on"])
        let failedRefresh: String = await withCheckedContinuation { continuation in
            _ = library.refresh { continuation.resume(returning: $0.outcome) }
        }
        XCTAssertEqual(failedRefresh, "failed")
        downloads.reconcileMyLibrary()
        for track in tracks { XCTAssertEqual(downloads.status(for: track.id), .downloaded) }
        _ = try await fixture.control("outage", method: "POST", query: ["routeId": "allHttps", "state": "off"])
        ConnectionNetworkEvents.shared.topologyChanged()
        let resumedError: String? = await withCheckedContinuation { continuation in
            _ = reader.reconnect { continuation.resume(returning: $0.errorKind) }
        }
        XCTAssertNil(resumedError)
        let explicit = try XCTUnwrap(tracks.first)
        downloads.requestDownload(explicit)
        try await eventually("Explicit ownership of an installed automatic file must persist") {
            (try? self.connectionDownloadWitness(name, rawID: explicit.id.rawID).explicit) == 1
        }
        let beforeRemoval = try await fixture.state().requests.filter { $0.endpoint == "removeFromDulcetLibrary" && $0.completed }.count
        XCTAssertTrue(library.remove(kind: "album", rawId: albumID))
        try await waitForConnectionReceipt(fixture, minimum: beforeRemoval + 1) { $0.endpoint == "removeFromDulcetLibrary" && $0.completed }
        try await eventually("Real album removal must acknowledge before retention is reconciled") {
            listener.membership == "notAdded" && (try? self.pendingConnectionMutations(name)).map { $0.isEmpty } == true
        }
        downloads.setAutomaticDownloadsEnabled(false)
        downloads.reconcileMyLibrary()
        XCTAssertEqual(downloads.status(for: explicit.id), .downloaded)
        _ = try await fixture.control("outage", method: "POST", query: ["routeId": "allHttps", "state": "on"])
        reader.setOnline(reachable: false)
        downloads.closeNetworkAccessForTesting()
        let asset = try XCTUnwrap(downloads.offlinePlaybackAsset(for: explicit))
        let engine = DulcetAVPlayerEngine()
        defer { engine.stopForAccountChange() }
        let progress = ConnectionProofPlayerProgress()
        engine.setEventListener { progress.record($0) }
        let plan = DulcetPlaybackPlan(playbackSessionID: .init("album-offline-listen"), attemptID: .init("album-offline-attempt"),
            deliveryProtocol: .httpProgressive, expectedContainer: asset.expectedContainer, resource: asset.resource,
            metadata: .init(title: explicit.title))
        _ = await execute(engine, .setVolume(commandID: .init("album-mute"), volume: 0))
        _ = await execute(engine, .prepare(commandID: .init("album-prepare"), plan: plan))
        _ = await execute(engine, .play(commandID: .init("album-play")))
        try await eventually("Retained album bytes must advance actual offline AVPlayer") { progress.position > 0 }
        _ = try await fixture.control("reset", method: "POST")
        print("DULCET MP-07 APPLE album-add=pending-lost-ack-alternate hashes=4 explicit-survives-remove=1 offline-decoder=AVPlayer")
    }

    private func waitForConnectionReceipt(_ fixture: ConnectionProofFixture, minimum: Int = 1,
        matching predicate: (ConnectionProofFixture.State.Receipt) -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        while try await fixture.state().requests.filter(predicate).count < minimum {
            guard ContinuousClock.now < deadline else { XCTFail("Missing actual fixture receipt"); throw ConnectionProofError.timeout }
            try await Task.sleep(for: .milliseconds(50))
        }
    }

    private func pendingConnectionMutations(_ name: String) throws -> [String] {
        let path = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support/databases/\(name)").path
        var database: OpaquePointer?
        guard sqlite3_open(path, &database) == SQLITE_OK else { throw ConnectionProofError.invalidFixture }
        defer { sqlite3_close(database) }
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(database, "SELECT target_id,field,value,local_sequence,wall_clock FROM mutation_outbox ORDER BY local_sequence", -1, &statement, nil) == SQLITE_OK else { throw ConnectionProofError.invalidFixture }
        defer { sqlite3_finalize(statement) }
        var rows: [String] = []
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW {
            rows.append((0..<5).map { index in sqlite3_column_text(statement, Int32(index)).map { String(cString: $0) } ?? "NULL" }.joined(separator: "|"))
            result = sqlite3_step(statement)
        }
        guard result == SQLITE_DONE else { throw ConnectionProofError.invalidFixture }
        return rows
    }

    func testMP05RemovedRouteAndLateOldNativeDeliveryCannotPromoteOverItsReplacement() async throws {
        let fixture = try ConnectionProofFixture.load()
        _ = try await fixture.control("reset", method: "POST")
        let provider = "apple-delivery-fence-\(UUID())"
        let name = "dulcet-delivery-fence-\(UUID()).db"
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-delivery-fence-\(UUID())")
        let account = DulcetPlaybackAccount(providerInstanceID: provider, normalizedServerURL: fixture.legacyHttp,
            username: fixture.username, password: fixture.password, allowLocalHTTP: true, credentialGeneration: 1)
        XCTAssertTrue(AppleMediaBindingClient.shared.acceptServerIdentity(databaseName: name,
            providerInstanceId: provider, identity: fixture.serverIdentity))
        establishFreshMediaOwnership(name, fixture: fixture, provider: provider)
        let connections = AppleConnectionsClient(databaseName: name)
        let enrollment = await enroll(connections, fixture: fixture, provider: provider)
        XCTAssertNil(enrollment.error)
        let downloads = DulcetCoreDownloadController(databaseName: name, downloadRootURL: root, sessionConfiguration: .ephemeral)
        defer { downloads.disconnect(); try? FileManager.default.removeItem(at: root); removeConnectionProofDatabase(name) }
        downloads.configure(account: account)
        downloads.setAutomaticDownloadsEnabled(false)
        downloads.setAutomaticDownloadContactPermitted(true)
        var held: (@MainActor () -> Void)?
        downloads.completedDownloadDelivery = { held = $0 }
        let track = fixture.track(2, provider: provider)
        downloads.requestDownload(track)
        try await eventually("The real native completed-file callback was not held") { held != nil }
        let original = try connectionDownloadWitness(name, rawID: track.id.rawID)
        let removal = connections.remove(accountId: provider, anchorUrl: fixture.legacyHttp,
            username: fixture.username, serverIdentity: fixture.serverIdentity, routeId: "home")
        XCTAssertNil(removal.errorKind)
        let oldDelivery = try XCTUnwrap(held)
        downloads.completedDownloadDelivery = nil
        oldDelivery()
        XCTAssertFalse(downloads.status(for: track.id).isLocallyPlayable,
            "Revocation after native completion and before promotion must still discard the old route's bytes")
        XCTAssertNil(downloads.offlinePlaybackAsset(for: track))
        try await eventually("The admitted non-removed route did not finish the same DownloadId") { downloads.status(for: track.id) == .downloaded }
        let replacement = try connectionDownloadWitness(name, rawID: track.id.rawID)
        XCTAssertEqual(replacement, original)
        oldDelivery()
        XCTAssertEqual(downloads.status(for: track.id), .downloaded, "A late old callback cannot demote or replace the newer installation")
        let asset = try XCTUnwrap(downloads.offlinePlaybackAsset(for: track))
        let bytes = await connectionLoad(asset.resource, length: asset.exactByteLength)
        XCTAssertEqual(SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(), fixture.tracks[2].sourceSha256)
        let receipts = try await fixture.state()
        XCTAssertGreaterThan(receipts.endpointCounts["tunnel"]?["stream"] ?? 0, 0)
    }

    private func connectionLoad(_ resource: any DulcetPlaybackResourceLoading, length: Int64) async -> Data {
        var operation: (any DulcetPlaybackResourceLoadOperation)?
        let bytes: Data = await withCheckedContinuation { continuation in
            operation = resource.load(.init(range: .init(start: 0, endInclusive: length - 1), requiresAudioSignature: true)) {
                if case let .loaded(bytes, _) = $0 { continuation.resume(returning: bytes) }
                else { continuation.resume(returning: Data()) }
            }
        }
        withExtendedLifetime(operation) { }
        return bytes
    }

    private struct ConnectionDownloadWitness: Equatable { let id: String; let explicit: Int64; let automatic: Int64; let rank: Int64 }
    private func connectionDownloadWitness(_ name: String, rawID: String) throws -> ConnectionDownloadWitness {
        let path = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Application Support/databases/\(name)").path
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(path, &database), SQLITE_OK)
        defer { sqlite3_close(database) }
        var statement: OpaquePointer?
        let sql = "SELECT d.download_id,r.explicit_download,r.automatic_download,r.added_rank FROM download d JOIN offline_media_retention r USING(server_id,raw_id,transcode_profile) WHERE d.raw_id=?"
        XCTAssertEqual(sqlite3_prepare_v2(database, sql, -1, &statement, nil), SQLITE_OK)
        defer { sqlite3_finalize(statement) }
        _ = rawID.withCString { sqlite3_bind_text(statement, 1, $0, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self)) }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW)
        return .init(id: String(cString: try XCTUnwrap(sqlite3_column_text(statement, 0))), explicit: sqlite3_column_int64(statement, 1),
            automatic: sqlite3_column_int64(statement, 2), rank: sqlite3_column_int64(statement, 3))
    }

    #endif

    private func establishFreshMediaOwnership(_ name: String, fixture: ConnectionProofFixture, provider: String) {
        // Fresh fixture namespace: use the production reconciliation boundary before enrollment.
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("connection-media-epoch-\(UUID())")
        let client = AppleDownloadClient(account: PlaybackEndpointAccount(providerInstanceId: provider,
            normalizedBaseUrl: fixture.legacyHttp, username: fixture.username, password: fixture.password,
            allowLocalHttp: true), databaseName: name, downloadRootPath: root.path)
        defer { client.close(); try? FileManager.default.removeItem(at: root) }
        XCTAssertNil(client.reconcile(outstandingTaskIdentifiers: [], credentialGeneration: 1).errorKind)
        XCTAssertTrue(client.isMediaBindingCurrent())
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
        let albumId: String
    }
    let standard: String
    let phase: Int?
    let transcodeOffsetV1: Bool?
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
        let requests: [Receipt]
        struct Receipt: Decodable, Sendable {
            let routeId: String
            let endpoint: String
            let responseBytes: Int
            let timeOffsetSeconds: Int64?
            let requestOrigin: String?
            let rangeStart: Int64?
            let completed: Bool
            let cut: Bool
            let range: Bool
            let lostReply: Bool
        }
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

@MainActor
private final class ConnectionProofMembership: NSObject, AppleMyLibraryListener {
    private nonisolated let rawID: String
    private nonisolated let kind: String
    private let changed: @MainActor () -> Void
    var membership = "notAdded"
    init(rawID: String, kind: String = "track", changed: @escaping @MainActor () -> Void) { self.rawID = rawID; self.kind = kind; self.changed = changed }
    nonisolated func onMyLibraryPublication(publication: AppleMyLibraryPublication) {
        let value = publication.membership(kind: kind, rawId: rawID)
        MainActor.assumeIsolated { membership = value; changed() }
    }
}
