import DulcetCore
@testable import DulcetKit
import AVFoundation
import Foundation
import SQLite3
import XCTest
#if os(iOS)
import UIKit
#endif

@MainActor
final class DulcetAppleDownloadIntegrationTest: XCTestCase {
    private let providerInstanceID = "apple-download-provider:opaque-id"
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

    func testAutomaticDownloadPreferenceIsNotLostDuringSameAccountReconciliation() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-pending-setting-\(UUID())")
        let databaseName = "dulcet-pending-setting-\(UUID()).db"
        let controller = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: root,
                                                      sessionConfiguration: .ephemeral)
        defer {
            controller.disconnect()
            try? FileManager.default.removeItem(at: root)
            removeAdapterTestDatabase(databaseName)
        }
        let account = DulcetPlaybackAccount(providerInstanceID: providerInstanceID,
            normalizedServerURL: "https://media.example.invalid", username: "fixture", password: "fixture",
            allowLocalHTTP: false, credentialGeneration: 1)
        let readyBudget: Int64 = 1_024 * 1_024 * 1_024
        controller.configure(account: account)
        try await waitUntil(timeout: .seconds(5), failureMessage: "initial account did not reconcile") {
            controller.offlineLibraryStorage.cacheBudgetBytes == readyBudget
        }
        XCTAssertTrue(controller.offlineLibraryStorage.automaticDownloadsEnabled)

        // Keep configure and the action in one MainActor turn: the real URLSession
        // enumeration callback cannot finish reconciliation before this user intent.
        controller.configure(account: account)
        controller.setAutomaticDownloadsEnabled(false)
        // A second same-binding configure must not erase an accepted preference either.
        controller.configure(account: account)
        try await waitUntil(timeout: .seconds(5), failureMessage: "replacement account did not reconcile") {
            controller.offlineLibraryStorage.cacheBudgetBytes == readyBudget
        }
        XCTAssertFalse(controller.offlineLibraryStorage.automaticDownloadsEnabled,
                       "Automatic Downloads off must survive asynchronous same-account reconciliation")
        let persisted = AppleDownloadClient(account: PlaybackEndpointAccount(providerInstanceId: providerInstanceID,
            normalizedBaseUrl: account.normalizedServerURL, username: account.username,
            password: account.password, allowLocalHttp: false), databaseName: databaseName, downloadRootPath: root.path)
        defer { persisted.close() }
        XCTAssertNil(persisted.reconcile(outstandingTaskIdentifiers: [], credentialGeneration: 1).errorKind)
        XCTAssertFalse(persisted.offlineLibraryStorage().automaticDownloadsEnabled,
                       "The preference must be durable, not only an optimistic switch value")
    }

    func testPendingAutomaticDownloadPreferenceUsesLatestIntentAndDoesNotCrossAccounts() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-setting-scope-\(UUID())")
        let databaseName = "dulcet-setting-scope-\(UUID()).db"
        let controller = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: root,
                                                      sessionConfiguration: .ephemeral)
        defer {
            controller.disconnect()
            try? FileManager.default.removeItem(at: root)
            removeAdapterTestDatabase(databaseName)
        }
        let account = DulcetPlaybackAccount(providerInstanceID: providerInstanceID,
            normalizedServerURL: "https://media.example.invalid", username: "fixture", password: "fixture",
            allowLocalHTTP: false, credentialGeneration: 1)
        let readyBudget: Int64 = 1_024 * 1_024 * 1_024
        controller.configure(account: account)
        try await waitUntil(timeout: .seconds(5), failureMessage: "initial account did not reconcile") {
            controller.offlineLibraryStorage.cacheBudgetBytes == readyBudget
        }
        controller.configure(account: account)
        controller.setAutomaticDownloadsEnabled(false)
        controller.setAutomaticDownloadsEnabled(true)
        controller.setAutomaticDownloadsEnabled(false)
        try await waitUntil(timeout: .seconds(5), failureMessage: "same account did not reconcile") {
            controller.offlineLibraryStorage.cacheBudgetBytes == readyBudget
        }
        XCTAssertFalse(controller.offlineLibraryStorage.automaticDownloadsEnabled,
                       "The most recent preference must win while reconciliation is pending")

        controller.configure(account: account)
        controller.setAutomaticDownloadsEnabled(false)
        controller.configure(account: DulcetPlaybackAccount(providerInstanceID: "different-setting-owner",
            normalizedServerURL: account.normalizedServerURL, username: "another", password: "fixture",
            allowLocalHTTP: false, credentialGeneration: 1))
        try await waitUntil(timeout: .seconds(5), failureMessage: "different account did not reconcile") {
            controller.offlineLibraryStorage.cacheBudgetBytes == readyBudget
        }
        XCTAssertTrue(controller.offlineLibraryStorage.automaticDownloadsEnabled,
                      "A pending action for the previous account must not turn off another account's downloads")
    }

    /// No network fixture: use the real facade to validate/promote bytes, then the real Swift
    /// adapter to resolve the same raw ID before and after a saved-account binding edit.
    func testRebindingTheSameProviderRejectsPriorLocalBytesAndLateCacheCallbacks() async throws {
        for editUsername in [false, true] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-rebind-\(UUID())")
            let databaseName = "dulcet-rebind-\(UUID()).db"
            defer {
                try? FileManager.default.removeItem(at: root)
                removeAdapterTestDatabase(databaseName)
            }
            let account = DulcetPlaybackAccount(providerInstanceID: providerInstanceID,
                normalizedServerURL: "https://media.example.invalid", username: "first", password: "fixture",
                allowLocalHTTP: false, credentialGeneration: 1)
            let bytes = try XCTUnwrap(Data(base64Encoded: "UklGRjQAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YRAAAAAAAAAAAAAAAAAAAAAAAAAA"))
            let seed = AppleDownloadClient(account: PlaybackEndpointAccount(providerInstanceId: providerInstanceID,
                normalizedBaseUrl: account.normalizedServerURL, username: account.username,
                password: account.password, allowLocalHttp: false), databaseName: databaseName, downloadRootPath: root.path)
            defer { seed.close() }
            XCTAssertNil(seed.reconcile(outstandingTaskIdentifiers: [], credentialGeneration: 1).errorKind)
            let write = try XCTUnwrap(seed.beginStreamingCache(rawId: "same-raw-id", container: .wav,
                totalLength: Int64(bytes.count), durationMilliseconds: 1, rangeStart: 0,
                credentialGeneration: 1, wallClockMilliseconds: 1))
            XCTAssertTrue(seed.appendStreamingCache(writeId: write, bytes: bytes, offset: 0))
            XCTAssertTrue(seed.finishStreamingCache(writeId: write, totalLength: Int64(bytes.count), wallClockMilliseconds: 2))
            let previousFilePath = try XCTUnwrap(seed.localPlaybackPlan(rawId: "same-raw-id").plan?.filePath)
            seed.close()
            let controller = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: root,
                                                          sessionConfiguration: .ephemeral)
            defer { controller.disconnect() }
            let track = DulcetTrack(id: .init(providerInstanceID: providerInstanceID, rawID: "same-raw-id"),
                title: "Binding control", credits: [], albumTitle: nil, duration: .milliseconds(1), sourceContainer: .wav, mediaSourceID: nil,
                artwork: DulcetArtwork(seed: "binding-control", palette: .indigoCoral))
            controller.configure(account: account)
            try await waitUntil(timeout: .seconds(5), failureMessage: "same binding did not retain validated cache") {
                controller.offlineLibraryStorage.cacheBytes == Int64(bytes.count)
            }
            XCTAssertNotNil(controller.offlinePlaybackAsset(for: track), "same URL and username must retain local bytes")
            let sourceURL = root.appendingPathComponent("late-playback.wav")
            try bytes.write(to: sourceURL)
            let late = controller.cachingResource(DulcetLocalFilePlaybackResource(fileURL: sourceURL,
                exactByteLength: Int64(bytes.count)), track: track, expectedContainer: .wav)
            controller.configure(account: DulcetPlaybackAccount(providerInstanceID: providerInstanceID,
                normalizedServerURL: editUsername ? account.normalizedServerURL : "https://other.example.invalid",
                username: editUsername ? "second" : account.username, password: "fixture",
                allowLocalHTTP: false, credentialGeneration: 2))
            try await waitUntil(timeout: .seconds(5), failureMessage: "new binding did not reconcile") {
                controller.offlineLibraryStorage.cacheBudgetBytes > 0
            }
            XCTAssertNil(controller.offlinePlaybackAsset(for: track), "same raw ID must not resolve prior binding bytes")
            XCTAssertFalse(FileManager.default.fileExists(atPath: previousFilePath), "old binding file must actually be purged")
            let lateResult = await load(resource: late, range: .init(start: 0, endInclusive: Int64(bytes.count - 1)))
            if case let .loaded(data, _) = lateResult { XCTAssertEqual(data, bytes) }
            else { XCTFail("old resource must actually deliver bytes to exercise stale-tap rejection") }
            // Drain the tap's main-actor callback rather than depending on a network request.
            await Task.yield()
            await Task.yield()
            XCTAssertEqual(controller.offlineLibraryStorage.cacheBytes, 0, "old playback tap must not publish into new binding")
            XCTAssertNil(controller.offlinePlaybackAsset(for: track))
        }
    }

    func testKnownServerReplacementQuarantinesLateMediaAndKeepsOfflinePlayback() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-rejected-identity-\(UUID())")
        let databaseName = "dulcet-rejected-identity-\(UUID()).db"
        let account = DulcetPlaybackAccount(providerInstanceID: providerInstanceID,
            normalizedServerURL: "https://media.example.invalid", username: "alice", password: "fixture",
            allowLocalHTTP: false, credentialGeneration: 1)
        let bytes = bindingPCMWave(seconds: 1)
        let seed = AppleDownloadClient(account: PlaybackEndpointAccount(providerInstanceId: providerInstanceID,
            normalizedBaseUrl: account.normalizedServerURL, username: account.username,
            password: account.password, allowLocalHttp: false), databaseName: databaseName, downloadRootPath: root.path)
        XCTAssertNil(seed.reconcile(outstandingTaskIdentifiers: [], credentialGeneration: 1).errorKind)
        let write = try XCTUnwrap(seed.beginStreamingCache(rawId: "retained", container: .wav,
            totalLength: Int64(bytes.count), durationMilliseconds: 1000, rangeStart: 0, credentialGeneration: 1, wallClockMilliseconds: 1))
        XCTAssertTrue(seed.appendStreamingCache(writeId: write, bytes: bytes, offset: 0))
        XCTAssertTrue(seed.finishStreamingCache(writeId: write, totalLength: Int64(bytes.count), wallClockMilliseconds: 2))
        let path = try XCTUnwrap(seed.localPlaybackPlan(rawId: "retained").plan?.filePath)
        seed.close()
        let controller = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: root, sessionConfiguration: .ephemeral)
        defer {
            controller.disconnect()
            try? FileManager.default.removeItem(at: root)
            removeAdapterTestDatabase(databaseName)
        }
        func track(_ raw: String) -> DulcetTrack {
            DulcetTrack(id: .init(providerInstanceID: providerInstanceID, rawID: raw), title: raw, credits: [], albumTitle: nil,
                duration: .seconds(1), sourceContainer: .wav, mediaSourceID: nil,
                artwork: DulcetArtwork(seed: raw, palette: .indigoCoral))
        }
        controller.configure(account: account)
        try await waitUntil(timeout: .seconds(5), failureMessage: "offline media did not reconcile") {
            controller.offlineLibraryStorage.cacheBytes == Int64(bytes.count)
        }
        let sourceURL = root.appendingPathComponent("late-source.wav")
        try bytes.write(to: sourceURL)
        let late = controller.cachingResource(DulcetLocalFilePlaybackResource(fileURL: sourceURL,
            exactByteLength: Int64(bytes.count)), track: track("late"), expectedContainer: .wav)
        controller.rejectServerIdentity()
        controller.requestDownload(track("queued-after-rejection"))
        let delivered = await load(resource: late, range: .init(start: 0, endInclusive: Int64(bytes.count - 1)))
        if case let .loaded(actual, _) = delivered { XCTAssertEqual(actual, bytes) }
        else { XCTFail("the real late resource must deliver bytes to exercise its callback") }
        await Task.yield()
        await Task.yield()
        XCTAssertNil(controller.offlinePlaybackAsset(for: track("late")))
        XCTAssertEqual(controller.offlineLibraryStorage.cacheBytes, Int64(bytes.count))
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), bytes)
        let retained = try XCTUnwrap(controller.offlinePlaybackAsset(for: track("retained")))
        let local = await load(resource: retained.resource, range: .init(start: 0, endInclusive: Int64(bytes.count - 1)))
        if case let .loaded(actual, _) = local { XCTAssertEqual(actual, bytes) }
        else { XCTFail("quarantine must preserve ordinary offline playback") }
        let tasks = await controller.debugSessionTasks()
        XCTAssertTrue(tasks.isEmpty, "known rejected namespaces cannot start a transfer")
        print("DULCET REJECTED SERVER MEDIA PASS late-promotion=blocked offline-bytes=retained remote-tasks=0")
    }

    func testRestoredCompletionWhoseRowWasRemovedCleansOnlyItsSecuredInboxPair() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-orphan-\(UUID())")
        let databaseName = "dulcet-orphan-\(UUID()).db"
        let controller = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: root,
                                                      sessionConfiguration: .ephemeral)
        let session = URLSession(configuration: .ephemeral)
        defer {
            session.invalidateAndCancel()
            controller.disconnect()
            try? FileManager.default.removeItem(at: root)
            removeAdapterTestDatabase(databaseName)
        }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let systemFile = root.appendingPathComponent("system-delivery")
        try Data("late removed automatic bytes".utf8).write(to: systemFile)
        let task = session.downloadTask(with: URL(string: "https://media.example.invalid/stream")!)
        task.taskDescription = "removed-download-row"
        controller.urlSession(session, downloadTask: task, didFinishDownloadingTo: systemFile)
        let inbox = root.appendingPathComponent(".incoming")
        let secured = try FileManager.default.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil)
        XCTAssertEqual(secured.count, 2, "completion before reconciliation must secure payload and metadata")
        let unrelated = inbox.appendingPathComponent("unrelated.keep")
        try Data("unrelated".utf8).write(to: unrelated)
        controller.configure(account: DulcetPlaybackAccount(providerInstanceID: providerInstanceID,
            normalizedServerURL: "https://media.example.invalid", username: "fixture", password: "fixture",
            allowLocalHTTP: false, credentialGeneration: 1))
        try await waitUntil(timeout: .seconds(5), failureMessage: "restored orphan remained in secured inbox") {
            secured.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) }
        }
        XCTAssertEqual(try Data(contentsOf: unrelated), Data("unrelated".utf8))
        XCTAssertEqual(controller.offlineLibraryStorage.downloadBytes, 0)
        XCTAssertEqual(controller.offlineLibraryStorage.cacheBytes, 0)
    }

    /// Drive the downloads-first entry with real promoted files and real current/prepared players.
    /// The forwarding protocol witness runs INSIDE the real adapter's binding-change callback:
    /// files must still exist there, while native items have already been invalidated.
    func testLocalPlaybackStopsBeforeARealBindingPurgeAndEquivalentRefreshKeepsIt() async throws {
        for change in ["endpoint", "username", "password", "equivalent", "invalid", "authenticated"] {
            let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-live-rebind-\(UUID())")
            let databaseName = "dulcet-live-rebind-\(UUID()).db"
            let account = DulcetPlaybackAccount(providerInstanceID: providerInstanceID,
                normalizedServerURL: "https://media.example.invalid/base", username: change == "authenticated" ? "Alice" : "first", password: "fixture",
                allowLocalHTTP: false, credentialGeneration: 1)
            let bytes = bindingPCMWave(seconds: 30)
            let seed = AppleDownloadClient(account: PlaybackEndpointAccount(providerInstanceId: providerInstanceID,
                normalizedBaseUrl: account.normalizedServerURL, username: account.username,
                password: account.password, allowLocalHttp: false), databaseName: databaseName, downloadRootPath: root.path)
            XCTAssertNil(seed.reconcile(outstandingTaskIdentifiers: [], credentialGeneration: 1).errorKind)
            var paths: [String] = []
            for raw in ["current", "incoming"] {
                let write = try XCTUnwrap(seed.beginStreamingCache(rawId: raw, container: .wav,
                    totalLength: Int64(bytes.count), durationMilliseconds: 30_000, rangeStart: 0,
                    credentialGeneration: 1, wallClockMilliseconds: 1))
                XCTAssertTrue(seed.appendStreamingCache(writeId: write, bytes: bytes, offset: 0))
                XCTAssertTrue(seed.finishStreamingCache(writeId: write, totalLength: Int64(bytes.count), wallClockMilliseconds: 2))
                paths.append(try XCTUnwrap(seed.localPlaybackPlan(rawId: raw).plan?.filePath))
            }
            seed.close()
            if change == "authenticated" {
                try makeCurrentBindingFixtureExplicit(databaseName)
            }
            let downloads = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: root,
                sessionConfiguration: .ephemeral)
            let proxy = BindingBoundaryDownloads(downloads)
            let nativePlayer = AVQueuePlayer()
            let engine = DulcetAVPlayerEngine(player: nativePlayer)
            let playback = DulcetCorePlaybackController(databaseName: databaseName, engine: engine, downloadController: proxy)
            defer {
                playback.disconnect()
                downloads.disconnect()
                try? FileManager.default.removeItem(at: root)
                removeAdapterTestDatabase(databaseName)
            }
            downloads.configure(account: account)
            playback.configureOffline(account: account)
            try await waitUntil(timeout: .seconds(5), failureMessage: "local binding did not reconcile") {
                downloads.offlineLibraryStorage.cacheBytes == Int64(bytes.count * (change == "authenticated" ? 1 : 2))
                    && downloads.offlineLibraryStorage.downloadBytes == Int64(change == "authenticated" ? bytes.count : 0)
            }
            for (index, raw) in ["current", "incoming"].enumerated() {
                let track = DulcetTrack(id: .init(providerInstanceID: providerInstanceID, rawID: raw),
                    title: raw, credits: [], albumTitle: nil, duration: .seconds(30), sourceContainer: .wav,
                    mediaSourceID: nil, artwork: DulcetArtwork(seed: raw, palette: .indigoCoral))
                let local = try XCTUnwrap(downloads.offlinePlaybackAsset(for: track))
                let plan = DulcetPlaybackPlan(playbackSessionID: .init(raw), attemptID: .init(raw),
                    deliveryProtocol: .httpProgressive, expectedContainer: .wav, resource: local.resource,
                    metadata: .init(title: raw))
                let command: DulcetPlaybackCommand = index == 0
                    ? .prepare(commandID: .init(raw), plan: plan)
                    : .preloadNext(commandID: .init(raw), plan: plan)
                let outcome = await bindingExecute(engine, command)
                XCTAssertEqual(outcome, .accepted(commandID: .init(raw)))
            }
            let fadeOutcome = await bindingExecute(engine, .setCrossfade(commandID: .init("fade"),
                attemptID: .init("current"), duration: 2))
            XCTAssertEqual(fadeOutcome, .completed(commandID: .init("fade"), result: .withoutData))
            try await waitUntil(timeout: .seconds(30), failureMessage: "both real local decoders must prepare") {
                nativePlayer.currentItem?.status == .readyToPlay && engine.preparedCrossfadeStateForTesting()?.isReady == true
            }
            let playOutcome = await bindingExecute(engine, .play(commandID: .init("play-local")))
            XCTAssertEqual(playOutcome, .accepted(commandID: .init("play-local")))
            try await waitUntil(timeout: .seconds(10), failureMessage: "current local file must actually play before rebind") {
                nativePlayer.rate > 0 && CMTimeGetSeconds(nativePlayer.currentTime()) > 0.1
            }
            let current = try XCTUnwrap(nativePlayer.currentItem)
            let incoming = try XCTUnwrap(engine.preparedCrossfadeStateForTesting())
            let actualChange = change == "endpoint" || change == "username"
            var witnessedBeforePurge = 0
            proxy.afterRetirement = {
                witnessedBeforePurge += 1
                XCTAssertTrue(paths.allSatisfy { FileManager.default.fileExists(atPath: $0) },
                    "witness must occur before the real unlink, not after it")
                XCTAssertNil(nativePlayer.currentItem, "current player must be empty BEFORE deletion")
                XCTAssertTrue(nativePlayer.items().isEmpty, "native queue must be empty BEFORE deletion")
                XCTAssertNil(engine.preparedCrossfadeStateForTesting(), "incoming decoder must be retired BEFORE deletion")
                XCTAssertEqual(nativePlayer.rate, 0)
            }
            let replacement = DulcetPlaybackAccount(providerInstanceID: providerInstanceID,
                normalizedServerURL: change == "endpoint" ? "https://other.example.invalid/base"
                    : change == "equivalent" ? "https://MEDIA.example.invalid:443/base/"
                    : change == "invalid" ? "not a server URL" : account.normalizedServerURL,
                username: change == "username" ? "second" : change == "authenticated" ? "alice" : account.username,
                password: change == "password" ? "replacement-password" : account.password,
                allowLocalHTTP: false, credentialGeneration: 2)
            var savedPresentation: DulcetPresentationStore?
            var savedConnector: BindingSavedConnector?
            var savedCredentials: BindingSavedCredentials?
            if change == "authenticated" {
                let request = DulcetAccountConnectRequest(serverURL: account.normalizedServerURL,
                    username: account.username, password: account.password, allowLocalHTTP: false)
                let credentials = BindingSavedCredentials(provider: providerInstanceID, request: request)
                let connector = BindingSavedConnector()
                savedConnector = connector
                savedCredentials = credentials
                let source = DulcetAccountDataSource(connector: connector, credentialStore: credentials,
                    playbackController: playback, downloadController: proxy)
                savedPresentation = DulcetPresentationStore(source: source)
                XCTAssertEqual(connector.requests, [request], "Automatic reconnect must authenticate the exact saved credentials")
                connector.complete(.connected(.init(serverName: "Binding proof", normalizedServerURL: account.normalizedServerURL,
                    canonicalUsername: "alice")))
                XCTAssertEqual(try credentials.load()?.username, "alice")
                XCTAssertEqual(credentials.providerInstanceID, providerInstanceID)
                XCTAssertEqual(try? Data(contentsOf: URL(fileURLWithPath: paths[0])), bytes,
                    "The legacy explicit payload must survive the real saved-account reconnect path")
            } else {
                // Downloads may be opened before any presentation/playback reconfiguration.
                downloads.configure(account: replacement)
            }
            if actualChange {
                XCTAssertEqual(witnessedBeforePurge, 1, "real binding purge must pass the retirement barrier")
                XCTAssertNil(nativePlayer.currentItem)
                XCTAssertNil(engine.preparedCrossfadeStateForTesting())
                XCTAssertTrue(paths.allSatisfy { !FileManager.default.fileExists(atPath: $0) })
            } else {
                XCTAssertEqual(witnessedBeforePurge, 0)
                XCTAssertTrue(nativePlayer.currentItem === current, "same binding must preserve native current")
                XCTAssertEqual(engine.preparedCrossfadeStateForTesting()?.itemIdentity, incoming.itemIdentity,
                    "password/equivalent refresh must preserve prepared incoming")
                XCTAssertTrue(paths.allSatisfy { FileManager.default.fileExists(atPath: $0) })
            }
            playback.configureOffline(account: replacement)
            if !actualChange {
                XCTAssertTrue(nativePlayer.currentItem === current)
                XCTAssertEqual(engine.preparedCrossfadeStateForTesting()?.itemIdentity, incoming.itemIdentity)
            }
            if change == "authenticated", let presentation = savedPresentation,
               let connector = savedConnector, let credentials = savedCredentials {
                // The original spelling is an authorized crash-recovery alias, but a later
                // manually typed login is not a reconnect using the current saved credentials.
                presentation.accountUsername = "Alice"
                presentation.submitAccountConnection()
                XCTAssertEqual(connector.requests.last?.username, "Alice")
                connector.complete(.connected(.init(serverName: "Binding proof", normalizedServerURL: account.normalizedServerURL,
                    canonicalUsername: "Alice")))
                XCTAssertEqual(presentation.pendingDownloadRemovalCount, 1)
                XCTAssertEqual(try credentials.load()?.username, "alice", "Consent precedes credential replacement")
                XCTAssertTrue(nativePlayer.currentItem === current, "The confirmation keeps playback intact")
                XCTAssertTrue(paths.allSatisfy { FileManager.default.fileExists(atPath: $0) })
                presentation.cancelServerReplacement()
                XCTAssertEqual(try credentials.load()?.username, "alice")
                presentation.accountUsername = "Alice"
                presentation.submitAccountConnection()
                connector.complete(.connected(.init(serverName: "Binding proof", normalizedServerURL: account.normalizedServerURL,
                    canonicalUsername: "Alice")))
                presentation.confirmServerReplacement()
                try await waitUntil(timeout: .seconds(10), failureMessage: "Confirmed account replacement did not complete") {
                    (try? credentials.load()?.username) == "Alice"
                }
                XCTAssertEqual(try credentials.load()?.username, "Alice")
                XCTAssertEqual(witnessedBeforePurge, 1, "Unproven typed alias must retire once before forced replacement")
                XCTAssertNil(nativePlayer.currentItem)
                XCTAssertNil(engine.preparedCrossfadeStateForTesting())
                XCTAssertTrue(paths.allSatisfy { !FileManager.default.fileExists(atPath: $0) },
                    "An old authenticated spelling cannot authorize retaining another typed login's files")
                print("BINDING-ROUND4 unproven-typed-alias retired-before-purge=true")
            }
            _ = await bindingExecute(engine, .release(commandID: .init("cleanup")))
            withExtendedLifetime(playback) {}
            withExtendedLifetime(savedPresentation) {}
            print("BINDING-ROUND4 case=\(change) preserved=\(!actualChange) explicit=\(change == "authenticated")")
        }
    }

    /// A legacy completed payload is explicit when retention says so. Seed that durable fixture
    /// directly, after the real facade has validated and promoted the WAV, without another fetch.
    private func makeCurrentBindingFixtureExplicit(_ name: String) throws {
        let file = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
            .appendingPathComponent("Library/Application Support/databases").appendingPathComponent(name)
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(file.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        XCTAssertEqual(sqlite3_exec(db, "UPDATE offline_media_retention SET explicit_download=1 WHERE raw_id='current'", nil, nil, nil), SQLITE_OK)
        XCTAssertEqual(sqlite3_changes(db), 1, "The fixture must mark an existing payload explicit")
    }

    /// The production account data source selects two durable namespaces; the real download,
    /// queue and reader facades must each return the first server's state when it is selected again.
    /// Credentials and connector results are controlled; media validation, files and SQLite are real.
    func testSavedServerRoundTripRestoresLocalMediaQueueAndSeenLibrary() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-saved-roundtrip-\(UUID())")
        let databaseName = "dulcet-saved-roundtrip-\(UUID()).db"
        let credentials = BindingMultiCredentials()
        let accounts: [(String, DulcetAccountConnectRequest)] = [
            ("saved-a", .init(serverURL: "https://one.example.invalid", username: "alice", password: "fixture-a", allowLocalHTTP: false)),
            ("saved-b", .init(serverURL: "https://two.example.invalid", username: "bob", password: "fixture-b", allowLocalHTTP: false))
        ]
        var payloads: [String: Data] = [:]
        let queue = ApplePlaybackQueueClient(databaseName: databaseName)
        defer { queue.close(); try? FileManager.default.removeItem(at: root); removeAdapterTestDatabase(databaseName) }
        for (id, request) in accounts {
            let seed = AppleDownloadClient(account: PlaybackEndpointAccount(providerInstanceId: id,
                normalizedBaseUrl: request.serverURL, username: request.username, password: request.password,
                allowLocalHttp: false), databaseName: databaseName, downloadRootPath: root.path)
            XCTAssertNil(seed.reconcile(outstandingTaskIdentifiers: [], credentialGeneration: 0).errorKind)
            var bytes = bindingPCMWave(seconds: 1)
            bytes[bytes.count - 1] = id == "saved-a" ? 1 : 2
            payloads[id] = bytes
            let write = try XCTUnwrap(seed.beginStreamingCache(rawId: "same-raw-id", container: .wav,
                totalLength: Int64(bytes.count), durationMilliseconds: 1000, rangeStart: 0,
                credentialGeneration: 0, wallClockMilliseconds: 1))
            XCTAssertTrue(seed.appendStreamingCache(writeId: write, bytes: bytes, offset: 0))
            XCTAssertTrue(seed.finishStreamingCache(writeId: write, totalLength: Int64(bytes.count), wallClockMilliseconds: 2))
            seed.close()
            try credentials.save(request, providerInstanceID: id, serverIdentity: "stable-\(id)")
            XCTAssertNil(queue.selectAccount(providerInstanceId: id).errorKind)
            XCTAssertNil(queue.replaceAndStart(request: ApplePlaybackQueueRequestDto(
                items: [ApplePlaybackQueueItemDto(providerInstanceId: id, rawId: "same-raw-id", durationMilliseconds: 1000)],
                sourceKind: "album", sourceRawId: "album-\(id)", sourceDisplayName: id,
                startIndex: 0, shuffle: false)).errorKind)
        }
        let databaseFile = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
            .appendingPathComponent("Library/Application Support/databases").appendingPathComponent(databaseName)
        var database: OpaquePointer?
        XCTAssertEqual(sqlite3_open(databaseFile.path, &database), SQLITE_OK)
        defer { sqlite3_close(database) }
        let seedSQL = """
        UPDATE offline_media_retention SET explicit_download=1;
        INSERT OR REPLACE INTO cache_track(server_id,raw_id,title,normalized_title,duration_milliseconds,source_container,issue_seq,last_access_wall)
        VALUES ('saved-a','same-raw-id','Library A','library a',1000,'wav',1,1),('saved-b','same-raw-id','Library B','library b',1000,'wav',1,1);
        """
        XCTAssertEqual(sqlite3_exec(database, seedSQL, nil, nil, nil), SQLITE_OK)
        try credentials.activate(providerInstanceID: "saved-a")
        let downloads = DulcetCoreDownloadController(databaseName: databaseName, downloadRootURL: root, sessionConfiguration: .ephemeral)
        defer { downloads.disconnect() }
        let playback = DulcetCorePlaybackController(queueClient: queue, engine: DulcetAVPlayerEngine(), catalog: [])
        let connector = BindingSavedConnector()
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(connector: connector,
            credentialStore: credentials, playbackController: playback, downloadController: downloads))
        for index in [0, 1, 0] {
            let (id, request) = accounts[index]
            if credentials.providerInstanceID != id || index == 1 { store.selectSavedServer(id) }
            connector.complete(.connected(.init(serverName: id, normalizedServerURL: request.serverURL,
                canonicalUsername: request.username, serverIdentity: "stable-\(id)")))
            try await waitUntil(timeout: .seconds(10), failureMessage: "Selected server media did not reconcile") {
                downloads.status(for: .init(providerInstanceID: id, rawID: "same-raw-id")).isLocallyPlayable
            }
            XCTAssertEqual(credentials.providerInstanceID, id)
            XCTAssertEqual(queue.snapshot().snapshot?.entries.map(\.providerInstanceId), [id])
            XCTAssertEqual(queue.snapshot().snapshot?.entries.map(\.rawId), ["same-raw-id"])
            XCTAssertTrue(downloads.status(for: .init(providerInstanceID: id, rawID: "same-raw-id")).isLocallyPlayable)
            XCTAssertEqual(downloads.downloadCountToRemove, 1)
            let local = AppleDownloadClient(account: PlaybackEndpointAccount(providerInstanceId: id,
                normalizedBaseUrl: request.serverURL, username: request.username, password: request.password, allowLocalHttp: false),
                databaseName: databaseName, downloadRootPath: root.path)
            XCTAssertNil(local.reconcile(outstandingTaskIdentifiers: [], credentialGeneration: 0).errorKind)
            let plan = try XCTUnwrap(local.localPlaybackPlan(rawId: "same-raw-id").plan)
            XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: plan.filePath)), payloads[id])
            local.close()
            let reader = AppleLibraryReaderClient(databaseName: databaseName, account: AppleLibraryReaderAccount(
                providerInstanceId: id, normalizedBaseUrl: request.serverURL, username: request.username,
                password: request.password, allowLocalHttp: false), foreground: false)
            reader.setOnline(reachable: false)
            let listener = BindingLibraryListener()
            let subscription = reader.subscribeLibraryWindow(request: AppleLibraryWindowRequest(kind: "songs", rawId: nil,
                listType: "title", genre: "", fromYear: 0, toYear: 0, musicFolderId: nil), listener: listener)
            try await waitUntil(timeout: .seconds(5), failureMessage: "Selected server's seen library was not restored") {
                listener.titles == [index == 0 ? "Library A" : "Library B"]
            }
            subscription.close()
            await withCheckedContinuation { done in reader.close { done.resume() } }
        }
        XCTAssertEqual(credentials.savedProviderInstanceIDs, ["saved-a", "saved-b"])
        print("DULCET SAVED SERVER STORAGE PASS selection=A->B->A media=distinct-bytes queue=restored library=restored deleted=0")
    }

    private func removeAdapterTestDatabase(_ name: String) {
        let directory = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
            .appendingPathComponent("Library/Application Support/databases")
        for suffix in ["", "-wal", "-shm"] {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name + suffix))
        }
    }

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
        XCTAssertEqual(observed.entries.filter { $0.endpoint != "ping" }.count, 0,
                       "Only the authenticated identity check may run; a new Download must wait for successful negotiation")
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
        let seedTransfers = seedRequests.entries.filter { $0.endpoint != "ping" }
        XCTAssertEqual(seedTransfers.count, 1, "the request counter must observe the earlier Download beside its identity checks")
        let seedStream = try XCTUnwrap(seedTransfers.first)
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
        XCTAssertTrue(observed.entries.contains { $0.endpoint == "ping" && $0.answered && $0.status == 200 },
                      "Saved recovery must authenticate server identity before resuming remote work")
        let recoveryTransfers = observed.entries.filter { $0.endpoint != "ping" }
        if recovery == .intact {
            XCTAssertEqual(recoveryTransfers.count, 0, "an intact download has no recovery read to make")
        } else {
            XCTAssertEqual(recoveryTransfers.count, 1, "only the permitted recovery stream may be sent beside identity checks")
            let stream = try XCTUnwrap(recoveryTransfers.first)
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
        // Existing Mac/iPhone/iPad conformance selectors also execute these isolated adapter
        // regressions, so the next full run exercises ownership, inbox cleanup and preference
        // delivery across asynchronous transfer reconciliation.
        try await testRebindingTheSameProviderRejectsPriorLocalBytesAndLateCacheCallbacks()
        try await testLocalPlaybackStopsBeforeARealBindingPurgeAndEquivalentRefreshKeepsIt()
        try await testSavedServerRoundTripRestoresLocalMediaQueueAndSeenLibrary()
        try await testKnownServerReplacementQuarantinesLateMediaAndKeepsOfflinePlayback()
        try await testRestoredCompletionWhoseRowWasRemovedCleansOnlyItsSecuredInboxPair()
        try await testAutomaticDownloadPreferenceIsNotLostDuringSameAccountReconciliation()
        try await testPendingAutomaticDownloadPreferenceUsesLatestIntentAndDoesNotCrossAccounts()

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

@MainActor
private final class BindingBoundaryDownloads: DulcetDownloadControlling {
    let base: DulcetCoreDownloadController
    var afterRetirement: (() -> Void)?
    init(_ base: DulcetCoreDownloadController) { self.base = base }
    var downloadsEnabled: Bool { base.downloadsEnabled }
    var downloadCountToRemove: Int? { base.downloadCountToRemove }
    func setMediaBindingWillChangeHandler(_ handler: @escaping @MainActor (DulcetPlaybackAccount) -> Void) {
        base.setMediaBindingWillChangeHandler { [weak self] upcoming in
            handler(upcoming)
            self?.afterRetirement?()
        }
    }
    func setMediaBindingWillBeReplacedHandler(_ handler: @escaping @MainActor () -> Void) {
        base.setMediaBindingWillBeReplacedHandler { [weak self] in
            handler()
            self?.afterRetirement?()
        }
    }
    func replaceUnconfirmedAccount(previous: DulcetPlaybackAccount, confirmed: DulcetPlaybackAccount) throws {
        try base.replaceUnconfirmedAccount(previous: previous, confirmed: confirmed)
    }
    func setStatusHandler(_ handler: @escaping @MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void) { base.setStatusHandler(handler) }
    func configure(account: DulcetPlaybackAccount) { base.configure(account: account) }
    func adoptAuthenticatedAccount(previous: DulcetPlaybackAccount, confirmed: DulcetPlaybackAccount) throws -> Bool {
        try base.adoptAuthenticatedAccount(previous: previous, confirmed: confirmed)
    }
    func isSameMediaBinding(first: DulcetPlaybackAccount, second: DulcetPlaybackAccount) -> Bool {
        base.isSameMediaBinding(first: first, second: second)
    }
    func hasSameMediaOrigin(first: DulcetPlaybackAccount, second: DulcetPlaybackAccount) -> Bool {
        base.hasSameMediaOrigin(first: first, second: second)
    }
    func setAutomaticDownloadContactPermitted(_ permitted: Bool) { base.setAutomaticDownloadContactPermitted(permitted) }
    func requestDownload(_ track: DulcetTrack) { base.requestDownload(track) }
    func status(for id: DulcetProviderItemID) -> DulcetDownloadState { base.status(for: id) }
    func offlinePlaybackAsset(for track: DulcetTrack) -> DulcetOfflinePlaybackAsset? { base.offlinePlaybackAsset(for: track) }
    func removeAccountData() async -> Bool {
        afterRetirement?()
        afterRetirement = nil
        return await base.removeAccountData()
    }
    func disconnect() { base.disconnect() }
    func setStateRefreshHandler(_ handler: @escaping @MainActor () -> Void) { base.setStateRefreshHandler(handler) }
}

@MainActor
private func bindingExecute(_ engine: DulcetAVPlayerEngine, _ command: DulcetPlaybackCommand) async -> DulcetPlaybackCommandOutcome {
    await withCheckedContinuation { continuation in engine.execute(command) { continuation.resume(returning: $0) } }
}

private func bindingPCMWave(seconds: Int) -> Data {
    let sampleRate = 8_000
    let size = seconds * sampleRate * 2
    var data = Data()
    func text(_ value: String) { data.append(contentsOf: value.utf8) }
    func le<T: FixedWidthInteger>(_ value: T) { var value = value.littleEndian; withUnsafeBytes(of: &value) { data.append(contentsOf: $0) } }
    text("RIFF"); le(UInt32(36 + size)); text("WAVEfmt "); le(UInt32(16))
    le(UInt16(1)); le(UInt16(1)); le(UInt32(sampleRate)); le(UInt32(sampleRate * 2))
    le(UInt16(2)); le(UInt16(16)); text("data"); le(UInt32(size))
    data.append(Data(repeating: 0, count: size))
    return data
}

@MainActor
private final class BindingSavedCredentials: DulcetProviderInstanceCredentialStoring {
    private(set) var providerInstanceID: String?
    private var request: DulcetAccountConnectRequest?
    init(provider: String, request: DulcetAccountConnectRequest) { providerInstanceID = provider; self.request = request }
    func load() throws -> DulcetAccountConnectRequest? { request }
    func save(_ request: DulcetAccountConnectRequest) throws { self.request = request }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        self.providerInstanceID = providerInstanceID
        self.request = request
    }
    func delete() throws { request = nil }
}

@MainActor
private final class BindingSavedConnector: DulcetAccountConnecting {
    var requests: [DulcetAccountConnectRequest] = []
    private var completion: (@MainActor (DulcetAccountConnectOutcome) -> Void)?
    func connect(_ request: DulcetAccountConnectRequest,
                 completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void) -> any DulcetAccountConnectOperation {
        requests.append(request); self.completion = completion
        return BindingSavedOperation()
    }
    func complete(_ outcome: DulcetAccountConnectOutcome) { completion?(outcome); completion = nil }
}

@MainActor
private final class BindingSavedOperation: DulcetAccountConnectOperation { func cancel() {} }


private final class BindingLibraryListener: NSObject, AppleLibraryWindowListener, @unchecked Sendable {
    @MainActor var titles: [String] = []
    func onWindowPublication(publication: AppleLibraryWindowPublication) {
        let publishedTitles = publication.items.compactMap(\.title)
        MainActor.assumeIsolated { titles = publishedTitles }
    }
}

@MainActor
private final class BindingMultiCredentials: DulcetSavedServerCredentialStoring {
    private var records: [String: (DulcetAccountConnectRequest, String)] = [:]
    var providerInstanceID: String?
    private(set) var savedProviderInstanceIDs: [String] = []
    var serverIdentity: String { providerInstanceID.flatMap { records[$0]?.1 } ?? "" }
    func load() throws -> DulcetAccountConnectRequest? { providerInstanceID.flatMap { records[$0]?.0 } }
    func load(providerInstanceID: String) throws -> DulcetAccountConnectRequest {
        try XCTUnwrap(records[providerInstanceID]?.0)
    }
    func serverIdentity(providerInstanceID: String) throws -> String { records[providerInstanceID]?.1 ?? "" }
    func activate(providerInstanceID: String) throws { self.providerInstanceID = providerInstanceID }
    func save(_ request: DulcetAccountConnectRequest) throws {
        try save(request, providerInstanceID: providerInstanceID ?? "new", serverIdentity: serverIdentity)
    }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        try save(request, providerInstanceID: providerInstanceID, serverIdentity: serverIdentity)
    }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String, serverIdentity: String) throws {
        records[providerInstanceID] = (request, serverIdentity)
        if !savedProviderInstanceIDs.contains(providerInstanceID) { savedProviderInstanceIDs.append(providerInstanceID) }
        self.providerInstanceID = providerInstanceID
    }
    func delete() throws { if let providerInstanceID { try delete(providerInstanceID: providerInstanceID) } }
    func delete(providerInstanceID: String) throws {
        records[providerInstanceID] = nil
        savedProviderInstanceIDs.removeAll { $0 == providerInstanceID }
        if self.providerInstanceID == providerInstanceID { self.providerInstanceID = nil }
    }
}
