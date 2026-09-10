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
        let trace = DownloadBrowseTrace()
        trace.mark("swift-start")
        let client = AppleLibraryBrowseClient(diagnosticObserver: { phase in
            trace.mark(phase)
        })
        defer { trace.mark("swift-load-track-exited") }
        let (seed, failure): (LiveDownloadTrackSeed?, String) = await withCheckedContinuation { continuation in
            _ = client.startBrowse(request: AppleLibraryBrowseRequest(
                providerInstanceId: providerInstanceID,
                normalizedBaseUrl: baseURL,
                username: fixtureUsername,
                password: fixturePassword,
                allowLocalHttp: true
            )) { outcome in
                trace.mark("swift-completion-entered")
                if let error = outcome.error {
                    continuation.resume(returning: (nil, "library browse failed: kind=\(error.kind)"))
                    return
                }
                guard let snapshot = outcome.snapshot else {
                    continuation.resume(returning: (nil, "library browse returned neither snapshot nor error"))
                    return
                }
                let tracks = snapshot.albums.flatMap(\.tracks)
                guard !tracks.isEmpty else {
                    continuation.resume(returning: (nil, "library empty: albums=\(snapshot.albums.count), tracks=0"))
                    return
                }
                guard let source = tracks.first(where: { $0.sourceContainer != nil }) else {
                    continuation.resume(returning: (nil, "no track carrying a source container: tracks=\(tracks.count)"))
                    return
                }
                continuation.resume(returning: (LiveDownloadTrackSeed(
                    providerInstanceID: source.providerInstanceId,
                    rawID: source.rawId,
                    title: source.title,
                    albumTitle: source.albumTitle,
                    discNumber: source.discNumber?.intValue,
                    trackNumber: source.trackNumber?.intValue,
                    durationMilliseconds: source.durationMilliseconds,
                    sourceContainer: source.sourceContainer,
                    mediaSourceID: source.mediaSourceId
                ), ""))
            }
        }
        trace.mark("swift-continuation-resumed")
        // Only on the failure path, and only ever adding to a run that is already failing: ask the
        // server two questions the browse itself cannot answer. The browse times out after 30s
        // reporting nothing but "timed out", which does not say whether the server was blocked or
        // whether that one connection stalled. A raw POSIX connect is a genuinely different
        // instrument from URLSession -- two probes sharing the same stack would be one probe twice.
        let diagnosis = seed == nil ? await probeDisposableServer(baseURL: baseURL) : ""
        let source = try XCTUnwrap(
            seed,
            "the disposable library must expose one downloadable track; \(failure); \(diagnosis); \(trace.summary)"
        )
        XCTAssertTrue(
            trace.observedHTTPCompletion,
            "the real browse must emit both header and body phase evidence; \(trace.summary)"
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

// Callbacks can originate in the HTTP engine. Capture time and enqueue only: formatting and
// bounded storage run on a separate serial queue. No live I/O can backpressure the request or
// the summary reader. A killed runner may lose this in-memory tail; timestamps still represent
// capture time, not the later processing time. This is lightweight, not zero-overhead tracing.
private final class DownloadBrowseTrace: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.legitimateapps.dulcet.browse-trace")
    private let started = ContinuousClock.now
    // Accessed only on queue.
    private var events: [String] = []
    private var sawHeaders = false
    private var sawBody = false

    func mark(_ phase: String) {
        let captured = ContinuousClock.now
        queue.async { [self] in
            let line = "LIBRARY BROWSE elapsed=\(started.duration(to: captured)) \(phase)"
            events.append(line)
            sawHeaders = sawHeaders || phase.hasSuffix("headers-received")
            sawBody = sawBody || phase.contains("body-completed hop=")
            if events.count > 128 { events.removeFirst() }
        }
    }

    var observedHTTPCompletion: Bool {
        queue.sync { sawHeaders && sawBody }
    }

    var summary: String {
        queue.sync { events.joined(separator: "; ") }
    }
}


/// Two bounded, independent probes of the disposable server, run only when a browse has already
/// failed. The probes run sequentially and can add roughly ten seconds after failure;
/// they do not change the timeout of the request under test.
///
/// These are later observations, not proof of server health during the original request.
/// Fast connect/ping supports current reachability; a slow ping or failed connect narrows
/// follow-up investigation without identifying the cause of the original stall.
private func probeDisposableServer(baseURL: String) async -> String {
    guard let components = URLComponents(string: baseURL),
          let host = components.host else {
        return "PROBE unavailable=malformed-base-url"
    }
    let port = UInt16(components.port ?? 80)
    let connect = probeTCPConnect(host: host, port: port, timeout: 5)

    var ping = "ping=skipped"
    if let url = URL(string: "\(baseURL)/rest/ping.view?v=1.16.1&c=dulcet-probe&f=json") {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 5
        let session = URLSession(configuration: configuration)
        let started = ContinuousClock.now
        do {
            let (_, response) = try await session.data(from: url)
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            ping = "ping=\(status) after=\(started.duration(to: .now))"
        } catch {
            ping = "ping=failed(\((error as NSError).code)) after=\(started.duration(to: .now))"
        }
        session.invalidateAndCancel()
    }
    return "PROBE \(connect) \(ping)"
}

/// A non-blocking POSIX connect with an explicit deadline. Deliberately not URLSession: the point is
/// to ask through a different stack than the one that just timed out.
private func probeTCPConnect(host: String, port: UInt16, timeout: Int32) -> String {
    let started = ContinuousClock.now
    let descriptor = socket(AF_INET, SOCK_STREAM, 0)
    guard descriptor >= 0 else { return "connect=socket-failed" }
    defer { close(descriptor) }
    _ = fcntl(descriptor, F_SETFL, fcntl(descriptor, F_GETFL, 0) | O_NONBLOCK)

    var address = sockaddr_in()
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    address.sin_family = sa_family_t(AF_INET)
    address.sin_port = port.bigEndian
    guard inet_pton(AF_INET, host, &address.sin_addr) == 1 else { return "connect=unresolvable" }

    let outcome = withUnsafePointer(to: &address) { pointer in
        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { rebound in
            Darwin.connect(descriptor, rebound, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    if outcome == 0 { return "connect=immediate after=\(started.duration(to: .now))" }
    guard errno == EINPROGRESS else {
        return "connect=refused(\(errno)) after=\(started.duration(to: .now))"
    }
    var descriptors = pollfd(fd: descriptor, events: Int16(POLLOUT), revents: 0)
    let ready = poll(&descriptors, 1, timeout * 1000)
    if ready == 0 { return "connect=timeout(\(timeout)s)" }
    if ready < 0 { return "connect=poll-failed(\(errno))" }
    var pending: Int32 = 0
    var size = socklen_t(MemoryLayout<Int32>.size)
    getsockopt(descriptor, SOL_SOCKET, SO_ERROR, &pending, &size)
    if pending != 0 { return "connect=error(\(pending)) after=\(started.duration(to: .now))" }
    return "connect=ok after=\(started.duration(to: .now))"
}
