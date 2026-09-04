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

        print(
            "CONF-51 \(platformLabel) DOWNLOAD trigger=platform-executor"
                + " response=validated promotion=atomic exact_bytes=\(storedByteCount)"
        )
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
        let baseURL = ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_BASE_URL"] ?? ""
        guard ProcessInfo.processInfo.environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true",
              let components = URLComponents(string: baseURL),
              components.scheme == "http",
              components.host == "127.0.0.1",
              components.port != nil,
              components.path.isEmpty,
              components.query == nil,
              components.fragment == nil else {
            XCTFail("downloads may run only against the disposable loopback conformance server")
            throw DownloadIntegrationPrecondition.invalidEnvironment
        }
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("dulcet-apple-download-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let controller = DulcetCoreDownloadController(
            databaseName: "dulcet-apple-download-\(UUID().uuidString).db",
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
        return DownloadIntegrationContext(
            controller: controller,
            downloadRoot: root,
            baseURL: baseURL
        )
    }

    private func loadLiveTrack(baseURL: String) async throws -> DulcetTrack {
        let client = AppleLibraryBrowseClient()
        let seed: LiveDownloadTrackSeed? = await withCheckedContinuation { continuation in
            _ = client.startBrowse(request: AppleLibraryBrowseRequest(
                providerInstanceId: providerInstanceID,
                normalizedBaseUrl: baseURL,
                username: fixtureUsername,
                password: fixturePassword,
                allowLocalHttp: true
            )) { outcome in
                guard let source = outcome.snapshot?.albums.lazy
                    .flatMap(\.tracks)
                    .first(where: { $0.sourceContainer != nil }) else {
                    continuation.resume(returning: nil)
                    return
                }
                continuation.resume(returning: LiveDownloadTrackSeed(
                    providerInstanceID: source.providerInstanceId,
                    rawID: source.rawId,
                    title: source.title,
                    albumTitle: source.albumTitle,
                    discNumber: source.discNumber?.intValue,
                    trackNumber: source.trackNumber?.intValue,
                    durationMilliseconds: source.durationMilliseconds,
                    sourceContainer: source.sourceContainer,
                    mediaSourceID: source.mediaSourceId
                ))
            }
        }
        let source = try XCTUnwrap(
            seed,
            "the disposable library must expose one downloadable track"
        )
        let container = try XCTUnwrap(source.sourceContainer.flatMap(downloadContainer))
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
        await withCheckedContinuation { continuation in
            _ = resource.load(DulcetPlaybackResourceLoadRequest(
                range: range,
                requiresAudioSignature: true
            )) { outcome in
                continuation.resume(returning: outcome)
            }
        }
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

    func tearDown() {
        controller.disconnect()
        try? FileManager.default.removeItem(at: downloadRoot)
    }
}
