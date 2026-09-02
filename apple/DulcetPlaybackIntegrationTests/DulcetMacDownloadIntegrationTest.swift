import DulcetCore
import DulcetKit
import Foundation
import XCTest

@MainActor
final class DulcetMacDownloadIntegrationTest: XCTestCase {
    private let providerInstanceID = "macos-download-provider:opaque-id"
    private let fixtureUsername = "dulcet-admin"
    private let fixturePassword = "dulcet-ci-canary-password"

    func downloadTriggerPromotesValidatedResponseAtomically() async throws {
        let context = try makeContext()
        defer { context.tearDown() }
        let track = try await loadLiveTrack(baseURL: context.baseURL)

        XCTAssertTrue(try regularFiles(in: context.downloadRoot).isEmpty)
        context.controller.requestDownload(track)

        try await waitUntil(
            timeout: .seconds(60),
            failureMessage: "the macOS executor did not reach the downloaded state"
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
            "CONF-51 MACOS DOWNLOAD trigger=platform-executor"
                + " response=validated promotion=atomic exact_bytes=\(storedByteCount)"
        )
    }

    func offlinePlaybackLoadsIdenticalBytesAfterNetworkClientsClose() async throws {
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
            "CONF-52 MACOS OFFLINE PLAYBACK network=closed"
                + " source=local-file identical_bytes=\(expectedBytes.count)"
        )
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
            .appendingPathComponent("dulcet-macos-download-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let controller = DulcetCoreDownloadController(
            databaseName: "dulcet-macos-download-\(UUID().uuidString).db",
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
