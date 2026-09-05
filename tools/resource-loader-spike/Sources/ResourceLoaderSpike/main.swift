import AVFoundation
import Foundation
import UniformTypeIdentifiers

private struct LoadingEvent {
    let path: String
    let requestedOffset: Int64?
    let requestedLength: Int?
    let requestsToEnd: Bool?
}

private final class EventRecorder {
    private let lock = NSLock()
    private var storage: [LoadingEvent] = []

    func append(_ event: LoadingEvent) {
        lock.lock()
        storage.append(event)
        lock.unlock()
    }

    var events: [LoadingEvent] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }
}

private final class ManifestRouter {
    private let lock = NSLock()
    private var routeToFixture: [String: String] = [:]
    private var originalURLs: [String] = []
    private let manifestURL: URL

    init(manifestURL: URL) {
        self.manifestURL = manifestURL
    }

    func rewrite(_ data: Data) throws -> Data {
        guard let source = String(data: data, encoding: .utf8) else {
            throw SpikeError.invalidManifest("manifest is not UTF-8")
        }
        let rewritten = try source.split(separator: "\n", omittingEmptySubsequences: false)
            .map { try rewriteLine(String($0)) }
            .joined(separator: "\n")
        return Data(rewritten.utf8)
    }

    func fixturePath(for routePath: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return routeToFixture[routePath]
    }

    var routedOriginalURLs: [String] {
        lock.lock()
        defer { lock.unlock() }
        return originalURLs
    }

    private func rewriteLine(_ line: String) throws -> String {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty {
            return line
        }
        if !trimmed.hasPrefix("#") {
            return try route(trimmed)
        }

        let expression = try NSRegularExpression(pattern: #"URI="([^"]+)""#)
        let mutable = NSMutableString(string: line)
        let matches = expression.matches(
            in: line,
            range: NSRange(location: 0, length: (line as NSString).length)
        )
        for match in matches.reversed() {
            let uri = (line as NSString).substring(with: match.range(at: 1))
            let routed = try route(uri)
            mutable.replaceCharacters(in: match.range(at: 1), with: routed)
        }
        return mutable as String
    }

    private func route(_ reference: String) throws -> String {
        guard let resolved = URL(string: reference, relativeTo: manifestURL)?.absoluteURL,
              let scheme = resolved.scheme?.lowercased(),
              ["http", "https"].contains(scheme)
        else {
            throw SpikeError.invalidManifest("could not resolve HTTP(S) manifest URI: \(reference)")
        }
        let fixture = resolved.lastPathComponent
        guard !fixture.isEmpty else {
            throw SpikeError.invalidManifest("manifest URI has no fixture name: \(reference)")
        }

        lock.lock()
        let routePath = "routed/route-\(routeToFixture.count)"
        routeToFixture[routePath] = fixture
        originalURLs.append(resolved.absoluteString)
        lock.unlock()
        // AVFoundation rejects literal custom-scheme child URLs in an HLS
        // manifest. A relative opaque route resolves against the manifest's
        // custom-scheme URL and therefore still returns to this delegate.
        return routePath
    }
}

private final class FixtureResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    let recorder = EventRecorder()
    private let fixtureRoot: URL
    private let router: ManifestRouter

    init(fixtureRoot: URL) {
        self.fixtureRoot = fixtureRoot
        self.router = ManifestRouter(
            manifestURL: URL(string: "https://playlist-origin.invalid/library/playlist.m3u8")!
        )
    }

    var routedOriginalURLs: [String] { router.routedOriginalURLs }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let url = loadingRequest.request.url else {
            loadingRequest.finishLoading(with: SpikeError.missingRequestURL)
            return true
        }

        let requestedPath = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !requestedPath.isEmpty, !requestedPath.contains("..") else {
            loadingRequest.finishLoading(with: SpikeError.invalidFixturePath(url.path))
            return true
        }

        let relativePath = router.fixturePath(for: requestedPath) ?? requestedPath
        let fileURL = fixtureRoot.appendingPathComponent(relativePath)
        guard var data = try? Data(contentsOf: fileURL) else {
            loadingRequest.finishLoading(with: SpikeError.missingFixture(relativePath))
            return true
        }
        if relativePath == "playlist.m3u8" {
            do {
                data = try router.rewrite(data)
            } catch {
                loadingRequest.finishLoading(with: error)
                return true
            }
        }

        if let information = loadingRequest.contentInformationRequest {
            information.contentType = contentType(for: fileURL)
            information.contentLength = Int64(data.count)
            information.isByteRangeAccessSupported = true
        }

        let dataRequest = loadingRequest.dataRequest
        recorder.append(
            LoadingEvent(
                path: relativePath,
                requestedOffset: dataRequest?.requestedOffset,
                requestedLength: dataRequest?.requestedLength,
                requestsToEnd: dataRequest?.requestsAllDataToEndOfResource
            )
        )

        if let dataRequest {
            let requestedOffset = dataRequest.currentOffset > 0
                ? dataRequest.currentOffset
                : dataRequest.requestedOffset
            let start = max(0, Int(requestedOffset))
            guard start <= data.count else {
                loadingRequest.finishLoading(with: SpikeError.invalidByteRange(start, data.count))
                return true
            }

            let available = data.count - start
            let count = dataRequest.requestsAllDataToEndOfResource
                ? available
                : min(available, dataRequest.requestedLength)
            if count > 0 {
                dataRequest.respond(with: data.subdata(in: start..<(start + count)))
            }
        }

        loadingRequest.finishLoading()
        return true
    }

    private func contentType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "m3u8":
            return UTType(filenameExtension: "m3u8")?.identifier ?? "public.m3u-playlist"
        case "mp3":
            return UTType.mp3.identifier
        case "aac":
            return UTType(filenameExtension: "aac")?.identifier ?? "public.aac-audio"
        default:
            return UTType.data.identifier
        }
    }
}

private enum SpikeError: LocalizedError {
    case commandFailed(String, Int32, String)
    case invalidByteRange(Int, Int)
    case invalidFixturePath(String)
    case invalidManifest(String)
    case missingFixture(String)
    case missingRequestURL
    case playbackFailed(String, String, Double)
    case playbackTimedOut(String, Double)
    case unexpectedArgument(String)
    case verificationFailed(String)

    var errorDescription: String? {
        switch self {
        case let .commandFailed(command, status, output):
            return "\(command) failed with exit \(status): \(output)"
        case let .invalidByteRange(offset, size):
            return "requested byte offset \(offset) exceeds fixture size \(size)"
        case let .invalidFixturePath(path):
            return "invalid fixture path: \(path)"
        case let .invalidManifest(detail):
            return "invalid HLS manifest: \(detail)"
        case let .missingFixture(path):
            return "missing fixture: \(path)"
        case .missingRequestURL:
            return "resource-loading request has no URL"
        case let .playbackFailed(label, detail, time):
            return "\(label) playback failed at \(ResourceLoaderSpike.format(time))s: \(detail)"
        case let .playbackTimedOut(label, time):
            return "\(label) playback timed out at \(ResourceLoaderSpike.format(time))s"
        case let .unexpectedArgument(argument):
            return "unexpected argument: \(argument)"
        case let .verificationFailed(message):
            return message
        }
    }
}

/// Every distinguishable way the localhost control server can fail to become
/// ready. One message per condition, each carrying the child's liveness and the
/// elapsed times, so a CI log alone identifies which one occurred.
private enum ServerReadinessFailure: LocalizedError {
    case childExitedBeforePortHandshake(
        child: String,
        elapsed: Double,
        budget: Double,
        portFileBytes: Int,
        parse: String
    )
    case portHandshakeTimedOut(
        child: String,
        elapsed: Double,
        budget: Double,
        portFileBytes: Int,
        parse: String
    )
    case httpProbeNeverSucceeded(
        port: Int,
        probes: Int,
        lastProbe: String,
        child: String,
        handshakeElapsed: Double,
        elapsed: Double,
        budget: Double
    )

    var errorDescription: String? {
        let prefix = "localhost HLS control server readiness failed condition="
        switch self {
        case let .childExitedBeforePortHandshake(child, elapsed, budget, portFileBytes, parse):
            return prefix + "child-exited-before-port-handshake "
                + "\(child) elapsed=\(ResourceLoaderSpike.format(elapsed))s "
                + "budget=\(ResourceLoaderSpike.format(budget))s "
                + "port_file_bytes=\(portFileBytes) last_parse=\(parse)"
        case let .portHandshakeTimedOut(child, elapsed, budget, portFileBytes, parse):
            return prefix + "port-handshake-timeout "
                + "\(child) elapsed=\(ResourceLoaderSpike.format(elapsed))s "
                + "budget=\(ResourceLoaderSpike.format(budget))s "
                + "port_file_bytes=\(portFileBytes) last_parse=\(parse)"
        case let .httpProbeNeverSucceeded(
            port, probes, lastProbe, child, handshakeElapsed, elapsed, budget
        ):
            return prefix + "http-probe-never-succeeded "
                + "port=\(port) probes=\(probes) last_probe=\(lastProbe) \(child) "
                + "handshake_elapsed=\(ResourceLoaderSpike.format(handshakeElapsed))s "
                + "elapsed=\(ResourceLoaderSpike.format(elapsed))s "
                + "budget=\(ResourceLoaderSpike.format(budget))s"
        }
    }
}

/// Readiness timing, kept in one value so a check can drive the same production
/// code path with a short budget instead of duplicating the logic.
private struct ServerReadinessBudget {
    let deadline: TimeInterval
    let probeTimeout: TimeInterval
    let pollInterval: TimeInterval

    /// Unchanged from the value this tool has always used.
    static let production = ServerReadinessBudget(
        deadline: 10, probeTimeout: 1, pollInterval: 0.1
    )
}

/// The child publishes its port by atomically replacing the handshake file with
/// a newline-terminated decimal. A reader therefore observes either no file or
/// the whole value, and a hypothetical short read is rejected by the missing
/// terminator rather than parsed as a truncated port number.
private enum PortHandshake {
    case absent
    case incomplete(bytes: Int, reason: String)
    case published(port: Int, bytes: Int)
}

private struct ProbeOutcome {
    let succeeded: Bool
    let detail: String
}

/// The probe's completion handler runs on a URLSession queue while the readiness
/// loop reads the result, so the result crosses threads under a lock.
private final class ProbeResultBox {
    private let lock = NSLock()
    private var value = ProbeOutcome(succeeded: false, detail: "probe-did-not-complete")

    func record(_ outcome: ProbeOutcome) {
        lock.lock()
        value = outcome
        lock.unlock()
    }

    var outcome: ProbeOutcome {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

private struct PlaybackObservation {
    let maximumTime: Double
    let status: AVPlayerItem.Status
}

private struct HTTPFixtureServer {
    let process: Process
    let controlManifestURL: URL
}

private enum SpikeMode {
    case fullSpike(expectedSegmentOutcome: String)
    case readinessReportingSelfCheck
}

@main
private enum ResourceLoaderSpike {
    static func main() {
        do {
            let mode = try parseArguments()
            try verifyReadinessReportingContract()
            guard case let .fullSpike(expectedSegmentOutcome) = mode else { return }
            let root = FileManager.default.temporaryDirectory
                .appendingPathComponent("dulcet-resource-loader-spike-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: root) }

            try createFixtures(at: root)
            let server = try startHTTPServer(root: root)
            defer {
                server.process.terminate()
                server.process.waitUntilExit()
            }

            let control = try play(
                label: "HTTP HLS negative control",
                url: server.controlManifestURL,
                loader: nil,
                stopWhen: { observation in observation.maximumTime >= 1.20 }
            )
            guard control.maximumTime >= 1.20 else {
                throw SpikeError.verificationFailed(
                    "HLS negative control did not play past the first segment boundary"
                )
            }
            print("CONTROL HLS_OVER_HTTP=PLAYED time=\(format(control.maximumTime))s")

            try verifyManifestRewriteContract()

            let progressiveLoader = FixtureResourceLoader(fixtureRoot: root)
            _ = try play(
                label: "custom-scheme progressive MP3",
                url: URL(string: "dulcet-stream://fixture/progressive.mp3")!,
                loader: progressiveLoader,
                stopWhen: { _ in
                    progressiveLoader.recorder.events.contains {
                        $0.path == "progressive.mp3" && $0.requestedOffset != nil
                    }
                }
            )
            let progressiveEvents = progressiveLoader.recorder.events.filter {
                $0.path == "progressive.mp3" && $0.requestedOffset != nil
            }
            guard !progressiveEvents.isEmpty else {
                throw SpikeError.verificationFailed(
                    "progressive MP3 produced no delegate byte-range request"
                )
            }
            print("OBSERVED PROGRESSIVE_MP3=DELEGATE_BYTE_RANGES count=\(progressiveEvents.count)")
            printEvents(progressiveEvents)

            let requiredSegments = Set(["segment0.aac", "segment1.aac"])
            let hlsLoader = FixtureResourceLoader(fixtureRoot: root)
            var hlsObservation: PlaybackObservation?
            var hlsPlaybackError: Error?
            do {
                hlsObservation = try play(
                    label: "custom-scheme HLS",
                    url: URL(string: "dulcet-stream://fixture/playlist.m3u8")!,
                    loader: hlsLoader,
                    stopWhen: { observation in
                        let seen = Set(
                            hlsLoader.recorder.events
                                .filter { $0.path.hasPrefix("segment") }
                                .map(\.path)
                        )
                        return observation.maximumTime >= 1.20 && requiredSegments.isSubset(of: seen)
                    }
                )
            } catch {
                hlsPlaybackError = error
                print("HLS_FAILURE_EVENTS_BEGIN")
                printEvents(hlsLoader.recorder.events)
                print("HLS_FAILURE_EVENTS_END")
                print("HLS_PLAYBACK_ERROR surfaced=\(error.localizedDescription)")
            }
            let hlsEvents = hlsLoader.recorder.events
            let manifestSeen = hlsEvents.contains { $0.path == "playlist.m3u8" }
            let segmentEvents = hlsEvents.filter { $0.path.hasPrefix("segment") }
            guard manifestSeen else {
                throw SpikeError.verificationFailed(
                    "custom-scheme HLS manifest never reached the resource-loader delegate"
                )
            }

            let observedSegments = Set(segmentEvents.map(\.path))
            let expectedOrigins = Set([
                "https://media-a.invalid/audio/segment0.aac",
                "https://media-b.invalid/audio/segment1.aac",
            ])
            guard expectedOrigins.isSubset(of: Set(hlsLoader.routedOriginalURLs)) else {
                throw SpikeError.verificationFailed("absolute cross-origin source URIs were not all rewritten")
            }

            let missingSegments = requiredSegments.subtracting(observedSegments).sorted()
            var measuredHLSPlaybackTime = hlsObservation?.maximumTime ?? 0
            if let spikeError = hlsPlaybackError as? SpikeError {
                switch spikeError {
                case let .playbackFailed(_, _, time): measuredHLSPlaybackTime = time
                case let .playbackTimedOut(_, time): measuredHLSPlaybackTime = time
                default: break
                }
            }
            let playedPastBoundary = measuredHLSPlaybackTime >= 1.20
            let observedOutcome: String
            if missingSegments.isEmpty, playedPastBoundary, hlsPlaybackError == nil {
                observedOutcome = "all-routed"
                print(
                    "OBSERVED HLS_MANIFEST=DELEGATE HLS_MEDIA_SEGMENTS=ALL_DELEGATE "
                        + "required=2 time=\(format(measuredHLSPlaybackTime))s playback_error=none"
                )
            } else if observedSegments == Set(["segment0.aac"]),
                      !playedPastBoundary,
                      hlsPlaybackError != nil
            {
                observedOutcome = "first-only-playback-failed"
                print(
                    "OBSERVED HLS_MANIFEST=DELEGATE HLS_MEDIA_SEGMENTS=FIRST_ONLY "
                        + "missing=segment1.aac time=\(format(measuredHLSPlaybackTime))s "
                        + "past_boundary=false playback_error=surfaced"
                )
            } else {
                observedOutcome = "indeterminate"
                print(
                    "OBSERVED HLS_MANIFEST=DELEGATE HLS_MEDIA_SEGMENTS=INDETERMINATE "
                        + "missing=\(missingSegments.joined(separator: ",")) "
                        + "past_boundary=\(playedPastBoundary) playback_error=\(hlsPlaybackError != nil)"
                )
            }
            printEvents(segmentEvents)
            guard expectedSegmentOutcome == observedOutcome else {
                throw SpikeError.verificationFailed(
                    "recorded HLS outcome expected \(expectedSegmentOutcome), observed \(observedOutcome)"
                )
            }
            print("VERDICT expected=\(expectedSegmentOutcome) observed=\(observedOutcome) PASS")
        } catch {
            FileHandle.standardError.write(Data("resource-loader spike ERROR: \(error.localizedDescription)\n".utf8))
            Foundation.exit(1)
        }
    }

    private static func parseArguments() throws -> SpikeMode {
        let arguments = Array(CommandLine.arguments.dropFirst())
        guard arguments.count == 2 else {
            throw SpikeError.unexpectedArgument(arguments.joined(separator: " "))
        }
        if arguments[0] == "--self-check" {
            guard arguments[1] == "readiness-reporting" else {
                throw SpikeError.unexpectedArgument(arguments[1])
            }
            return .readinessReportingSelfCheck
        }
        guard arguments[0] == "--expect-hls-segments" else {
            throw SpikeError.unexpectedArgument(arguments.joined(separator: " "))
        }
        guard ["all-routed", "first-only-playback-failed"].contains(arguments[1]) else {
            throw SpikeError.unexpectedArgument(arguments[1])
        }
        return .fullSpike(expectedSegmentOutcome: arguments[1])
    }

    private static func createFixtures(at root: URL) throws {
        let progressiveWAV = root.appendingPathComponent("progressive.wav")
        try makeWAV(duration: 2.0, frequency: 440.0).write(to: progressiveWAV)
        try run("/opt/homebrew/bin/lame", [
            "--silent", "--cbr", "-b", "128",
            progressiveWAV.path,
            root.appendingPathComponent("progressive.mp3").path,
        ])

        for index in 0..<2 {
            let wav = root.appendingPathComponent("segment\(index).wav")
            let rawAAC = root.appendingPathComponent("segment\(index)-raw.aac")
            let packedAAC = root.appendingPathComponent("segment\(index).aac")
            try makeWAV(duration: 1.0, frequency: index == 0 ? 523.25 : 659.25).write(to: wav)
            try run("/usr/bin/afconvert", [
                wav.path, rawAAC.path,
                "-f", "adts", "-d", "aac", "-b", "96000",
            ])
            var packed = makeHLSTimestampTag(timestamp: UInt64(index * 90_000))
            packed.append(try Data(contentsOf: rawAAC))
            try packed.write(to: packedAAC)
        }

        let playlist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:1
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:1.0,
        https://media-a.invalid/audio/segment0.aac
        #EXTINF:1.0,
        https://media-b.invalid/audio/segment1.aac
        #EXT-X-ENDLIST

        """
        try Data(playlist.utf8).write(to: root.appendingPathComponent("playlist.m3u8"))

        let controlPlaylist = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:1
        #EXT-X-MEDIA-SEQUENCE:0
        #EXTINF:1.0,
        segment0.aac
        #EXTINF:1.0,
        segment1.aac
        #EXT-X-ENDLIST

        """
        try Data(controlPlaylist.utf8).write(to: root.appendingPathComponent("control.m3u8"))
    }

    private static func verifyManifestRewriteContract() throws {
        let router = ManifestRouter(
            manifestURL: URL(string: "https://playlist-origin.invalid/master/index.m3u8")!
        )
        let source = """
        #EXTM3U
        #EXT-X-KEY:METHOD=AES-128,URI="https://keys.invalid/key.bin"
        #EXT-X-MAP:URI="../init/init.mp4"
        variants/low.m3u8
        https://media.invalid/audio/segment.aac

        """
        let rewritten = try router.rewrite(Data(source.utf8))
        guard let text = String(data: rewritten, encoding: .utf8),
              !text.contains("https://")
        else {
            throw SpikeError.verificationFailed(
                "manifest rewrite contract did not normalize segment, playlist, key, and map URIs"
            )
        }
        let routeExpression = try NSRegularExpression(pattern: #"routed/route-[0-9]+"#)
        let routeStrings = routeExpression.matches(
            in: text,
            range: NSRange(location: 0, length: (text as NSString).length)
        ).map { (text as NSString).substring(with: $0.range) }
        let deliveryManifestURL = URL(string: "dulcet-stream://fixture/playlist.m3u8")!
        let resolvedRoutes = routeStrings.compactMap {
            URL(string: $0, relativeTo: deliveryManifestURL)?.absoluteURL
        }
        guard resolvedRoutes.count == 4,
              resolvedRoutes.allSatisfy({ $0.scheme == "dulcet-stream" })
        else {
            throw SpikeError.verificationFailed(
                "rewritten manifest routes did not all resolve under the delegate's custom scheme"
            )
        }
        print("REWRITE_CONTRACT media,playlist,key,map=ALL_RESOLVE_TO_CUSTOM_SCHEME PASS")
    }

    /// Drives the production readiness wait into each of its three failure
    /// conditions and asserts that the reported message names that condition and
    /// no other. A readiness failure in CI is otherwise indistinguishable from
    /// the other two, which is the whole point of these checks.
    private static func verifyReadinessReportingContract() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("dulcet-readiness-reporting-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let budget = ServerReadinessBudget(deadline: 1.0, probeTimeout: 0.25, pollInterval: 0.05)

        // Condition 1: the child died before publishing a port.
        let exitedChild = shellChild("exit 7")
        try exitedChild.run()
        exitedChild.waitUntilExit()
        let exited = try readinessFailureMessage(
            process: exitedChild,
            portFile: root.appendingPathComponent("exited-port"),
            budget: budget
        )
        try requireCondition("child-exited-before-port-handshake", in: exited)
        try require(exited.contains("exit_status=7"), "child exit status not reported: \(exited)")
        try require(exited.contains("child_running=false"), "child liveness not reported: \(exited)")
        try require(
            exited.contains("last_parse=port-file-absent"),
            "handshake state not reported: \(exited)"
        )

        // Condition 2: a port handshake that never completes. The file holds "5",
        // which is exactly the prefix a torn read of a five-digit port would
        // yield, and which the old reader parsed as the valid port 5.
        let tornPortFile = root.appendingPathComponent("torn-port")
        try Data("5".utf8).write(to: tornPortFile)
        let tornChild = shellChild("sleep 30")
        try tornChild.run()
        defer { terminate(tornChild) }
        let torn = try readinessFailureMessage(
            process: tornChild,
            portFile: tornPortFile,
            budget: budget
        )
        try requireCondition("port-handshake-timeout", in: torn)
        try require(
            torn.contains("last_parse=unterminated-port-file"),
            "truncated handshake not named: \(torn)"
        )
        try require(torn.contains("port_file_bytes=1"), "handshake byte count missing: \(torn)")
        try require(torn.contains("child_running=true"), "child liveness not reported: \(torn)")
        try require(
            !torn.contains("port=5"),
            "a truncated handshake was parsed as a port: \(torn)"
        )

        // Condition 3: a published port that never answers HTTP. The port is
        // bound without ever being listened on, so no HTTP response can arrive
        // there and nothing else can occupy it for the duration of the check.
        let silent = try reserveSilentPort()
        defer { Darwin.close(silent.descriptor) }
        let silentPortFile = root.appendingPathComponent("silent-port")
        try Data("\(silent.port)\n".utf8).write(to: silentPortFile)
        let silentChild = shellChild("sleep 30")
        try silentChild.run()
        defer { terminate(silentChild) }
        let unanswered = try readinessFailureMessage(
            process: silentChild,
            portFile: silentPortFile,
            budget: budget
        )
        try requireCondition("http-probe-never-succeeded", in: unanswered)
        try require(
            unanswered.contains("port=\(silent.port)"),
            "published port not reported: \(unanswered)"
        )
        try require(
            unanswered.contains("last_probe="),
            "probe outcome not reported: \(unanswered)"
        )
        try require(
            unanswered.contains("child_running=true"),
            "child liveness not reported: \(unanswered)"
        )
        try require(
            !unanswered.contains("probes=0"),
            "no HTTP probe was attempted: \(unanswered)"
        )

        for message in [exited, torn, unanswered] {
            try require(message.contains("elapsed="), "elapsed time not reported: \(message)")
            try require(message.contains("budget="), "budget not reported: \(message)")
        }

        print(
            "READINESS_REPORTING child-exited-before-port-handshake,port-handshake-timeout,"
                + "http-probe-never-succeeded=EACH_NAMED_AND_MUTUALLY_EXCLUSIVE PASS"
        )
    }

    private static let readinessConditions = [
        "child-exited-before-port-handshake",
        "port-handshake-timeout",
        "http-probe-never-succeeded",
    ]

    private static func requireCondition(_ expected: String, in message: String) throws {
        for condition in readinessConditions {
            let present = message.contains("condition=\(condition)")
            guard present == (condition == expected) else {
                throw SpikeError.verificationFailed(
                    "readiness message should name only \(expected): \(message)"
                )
            }
        }
    }

    private static func require(_ condition: Bool, _ message: String) throws {
        guard condition else { throw SpikeError.verificationFailed(message) }
    }

    private static func readinessFailureMessage(
        process: Process,
        portFile: URL,
        budget: ServerReadinessBudget
    ) throws -> String {
        do {
            let url = try waitForControlServer(
                process: process,
                portFile: portFile,
                budget: budget
            )
            throw SpikeError.verificationFailed(
                "readiness wait reported success where it must fail: \(url.absoluteString)"
            )
        } catch let failure as ServerReadinessFailure {
            return failure.localizedDescription
        }
    }

    private static func shellChild(_ script: String) -> Process {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = ["-c", script]
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        return process
    }

    private static func terminate(_ process: Process) {
        if process.isRunning {
            process.terminate()
            process.waitUntilExit()
        }
    }

    /// Binds a loopback port without listening on it, so nothing ever accepts a
    /// connection there and the port stays reserved while the descriptor is open.
    /// Whether a connection attempt is refused or simply never completes is a
    /// platform detail the check deliberately does not depend on.
    private static func reserveSilentPort() throws -> (descriptor: Int32, port: Int) {
        let descriptor = socket(AF_INET, SOCK_STREAM, 0)
        guard descriptor >= 0 else {
            throw SpikeError.verificationFailed("could not create a silent probe socket")
        }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            Darwin.close(descriptor)
            throw SpikeError.verificationFailed("could not bind a silent probe socket")
        }
        var resolved = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &resolved) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(descriptor, $0, &length)
            }
        }
        guard named == 0, resolved.sin_port != 0 else {
            Darwin.close(descriptor)
            throw SpikeError.verificationFailed("could not resolve the silent probe port")
        }
        return (descriptor, Int(UInt16(bigEndian: resolved.sin_port)))
    }

    private static func makeWAV(duration: Double, frequency: Double) -> Data {
        let sampleRate = 44_100
        let sampleCount = Int(Double(sampleRate) * duration)
        var pcm = Data(capacity: sampleCount * 2)
        for sample in 0..<sampleCount {
            let phase = 2.0 * Double.pi * frequency * Double(sample) / Double(sampleRate)
            let value = Int16(sin(phase) * 8_000)
            pcm.appendLittleEndian(value)
        }

        var wav = Data()
        wav.append(Data("RIFF".utf8))
        wav.appendLittleEndian(UInt32(36 + pcm.count))
        wav.append(Data("WAVEfmt ".utf8))
        wav.appendLittleEndian(UInt32(16))
        wav.appendLittleEndian(UInt16(1))
        wav.appendLittleEndian(UInt16(1))
        wav.appendLittleEndian(UInt32(sampleRate))
        wav.appendLittleEndian(UInt32(sampleRate * 2))
        wav.appendLittleEndian(UInt16(2))
        wav.appendLittleEndian(UInt16(16))
        wav.append(Data("data".utf8))
        wav.appendLittleEndian(UInt32(pcm.count))
        wav.append(pcm)
        return wav
    }

    private static func makeHLSTimestampTag(timestamp: UInt64) -> Data {
        var payload = Data("com.apple.streaming.transportStreamTimestamp".utf8)
        payload.append(0)
        payload.appendBigEndian(timestamp & 0x1_FFFF_FFFF)

        var frame = Data("PRIV".utf8)
        frame.append(synchsafe(payload.count))
        frame.append(contentsOf: [0, 0])
        frame.append(payload)

        var tag = Data("ID3".utf8)
        tag.append(contentsOf: [4, 0, 0])
        tag.append(synchsafe(frame.count))
        tag.append(frame)
        return tag
    }

    private static func synchsafe(_ value: Int) -> Data {
        Data([
            UInt8((value >> 21) & 0x7F),
            UInt8((value >> 14) & 0x7F),
            UInt8((value >> 7) & 0x7F),
            UInt8(value & 0x7F),
        ])
    }

    private static func startHTTPServer(root: URL) throws -> HTTPFixtureServer {
        let portFile = root.appendingPathComponent("http-port")
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = [
            "-c",
            """
            import http.server, os, sys, tempfile
            os.chdir(sys.argv[1])
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), http.server.SimpleHTTPRequestHandler)
            port_file = sys.argv[2]
            handle, staging = tempfile.mkstemp(dir=os.path.dirname(port_file) or '.')
            with os.fdopen(handle, 'w') as sink:
                sink.write('%d\\n' % server.server_address[1])
                sink.flush()
                os.fsync(sink.fileno())
            os.replace(staging, port_file)
            server.serve_forever()
            """,
            root.path,
            portFile.path,
        ]
        process.standardOutput = FileHandle.standardError
        process.standardError = FileHandle.standardError
        try process.run()
        do {
            let controlURL = try waitForControlServer(
                process: process,
                portFile: portFile,
                budget: .production
            )
            return HTTPFixtureServer(process: process, controlManifestURL: controlURL)
        } catch {
            if process.isRunning {
                process.terminate()
                process.waitUntilExit()
            }
            throw error
        }
    }

    /// Blocks until the child's control server answers `/control.m3u8`, or throws
    /// the `ServerReadinessFailure` naming which readiness condition occurred.
    private static func waitForControlServer(
        process: Process,
        portFile: URL,
        budget: ServerReadinessBudget
    ) throws -> URL {
        let start = Date()
        let deadline = start.addingTimeInterval(budget.deadline)
        let session = URLSession(configuration: probeConfiguration(timeout: budget.probeTimeout))
        defer { session.invalidateAndCancel() }

        var publishedPort: Int?
        var handshakeElapsed = 0.0
        var portFileBytes = 0
        var lastParse = "port-file-absent"
        var probes = 0
        var lastProbe = "no-probe-attempted"

        while Date() < deadline {
            if publishedPort == nil {
                switch readPortHandshake(portFile) {
                case .absent:
                    portFileBytes = 0
                    lastParse = "port-file-absent"
                case let .incomplete(bytes, reason):
                    portFileBytes = bytes
                    lastParse = reason
                case let .published(port, bytes):
                    publishedPort = port
                    portFileBytes = bytes
                    lastParse = "published"
                    handshakeElapsed = Date().timeIntervalSince(start)
                }
            }

            guard let port = publishedPort else {
                let child = childState(process)
                if !child.running {
                    throw ServerReadinessFailure.childExitedBeforePortHandshake(
                        child: child.summary,
                        elapsed: Date().timeIntervalSince(start),
                        budget: budget.deadline,
                        portFileBytes: portFileBytes,
                        parse: lastParse
                    )
                }
                Thread.sleep(forTimeInterval: budget.pollInterval)
                continue
            }

            let controlURL = URL(string: "http://127.0.0.1:\(port)/control.m3u8")!
            probes += 1
            let outcome = probe(session: session, url: controlURL, timeout: budget.probeTimeout)
            lastProbe = outcome.detail
            if outcome.succeeded {
                let total = Date().timeIntervalSince(start)
                print(
                    "READINESS control_server=READY port=\(port) probes=\(probes) "
                        + "handshake_elapsed=\(format(handshakeElapsed))s "
                        + "total_elapsed=\(format(total))s "
                        + "budget=\(format(budget.deadline))s port_file_bytes=\(portFileBytes)"
                )
                return controlURL
            }

            let child = childState(process)
            if !child.running {
                throw ServerReadinessFailure.httpProbeNeverSucceeded(
                    port: port,
                    probes: probes,
                    lastProbe: lastProbe,
                    child: child.summary,
                    handshakeElapsed: handshakeElapsed,
                    elapsed: Date().timeIntervalSince(start),
                    budget: budget.deadline
                )
            }
            Thread.sleep(forTimeInterval: budget.pollInterval)
        }

        let child = childState(process)
        let elapsed = Date().timeIntervalSince(start)
        guard let port = publishedPort else {
            throw ServerReadinessFailure.portHandshakeTimedOut(
                child: child.summary,
                elapsed: elapsed,
                budget: budget.deadline,
                portFileBytes: portFileBytes,
                parse: lastParse
            )
        }
        throw ServerReadinessFailure.httpProbeNeverSucceeded(
            port: port,
            probes: probes,
            lastProbe: lastProbe,
            child: child.summary,
            handshakeElapsed: handshakeElapsed,
            elapsed: elapsed,
            budget: budget.deadline
        )
    }

    /// Rejects anything that is not a complete, newline-terminated port value, so
    /// a short read of "51234\n" can never be mistaken for the valid port 5.
    private static func readPortHandshake(_ portFile: URL) -> PortHandshake {
        guard let data = try? Data(contentsOf: portFile, options: [.uncached]) else {
            return .absent
        }
        guard data.last == UInt8(ascii: "\n") else {
            return .incomplete(
                bytes: data.count,
                reason: data.isEmpty ? "empty-port-file" : "unterminated-port-file"
            )
        }
        guard let text = String(data: data.dropLast(), encoding: .ascii),
              let port = Int(text),
              (1...65_535).contains(port)
        else {
            return .incomplete(bytes: data.count, reason: "unparsable-port-value")
        }
        return .published(port: port, bytes: data.count)
    }

    private static func childState(_ process: Process) -> (running: Bool, summary: String) {
        if process.isRunning {
            return (true, "child_running=true")
        }
        let reason = process.terminationReason == .uncaughtSignal ? "uncaught-signal" : "exit"
        return (
            false,
            "child_running=false exit_status=\(process.terminationStatus) "
                + "termination_reason=\(reason)"
        )
    }

    private static func probeConfiguration(timeout: TimeInterval) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.httpMaximumConnectionsPerHost = 1
        configuration.waitsForConnectivity = false
        return configuration
    }

    /// One bounded probe. A probe that outlives its own request timeout is
    /// cancelled rather than abandoned, and the session is invalidated when the
    /// readiness wait returns, so no probe survives it.
    private static func probe(
        session: URLSession,
        url: URL,
        timeout: TimeInterval
    ) -> ProbeOutcome {
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        let box = ProbeResultBox()
        let semaphore = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: request) { _, response, error in
            if let http = response as? HTTPURLResponse {
                box.record(
                    ProbeOutcome(
                        succeeded: http.statusCode == 200,
                        detail: "http_status=\(http.statusCode)"
                    )
                )
            } else if let urlError = error as? URLError {
                box.record(
                    ProbeOutcome(succeeded: false, detail: "url_error=\(urlError.code.rawValue)")
                )
            } else if let error {
                let failure = error as NSError
                box.record(
                    ProbeOutcome(
                        succeeded: false,
                        detail: "error=\(failure.domain)/\(failure.code)"
                    )
                )
            } else {
                box.record(ProbeOutcome(succeeded: false, detail: "no-http-response"))
            }
            semaphore.signal()
        }
        task.resume()
        // The request already bounds itself at `timeout`; the extra grace only
        // covers completion-handler delivery, so a probe that hits its own
        // timeout reports the URL error rather than "abandoned". The readiness
        // deadline still governs whether another probe starts.
        if semaphore.wait(timeout: .now() + timeout + 0.25) == .timedOut {
            task.cancel()
            return ProbeOutcome(succeeded: false, detail: "probe-cancelled-after-timeout")
        }
        return box.outcome
    }

    private static func play(
        label: String,
        url: URL,
        loader: FixtureResourceLoader?,
        stopWhen: (PlaybackObservation) -> Bool
    ) throws -> PlaybackObservation {
        let asset = AVURLAsset(url: url)
        if let loader {
            asset.resourceLoader.setDelegate(loader, queue: DispatchQueue(label: "dulcet.spike.\(label)"))
        }
        let item = AVPlayerItem(asset: asset)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        player.play()

        let deadline = Date().addingTimeInterval(12)
        var maximumTime = 0.0
        while Date() < deadline {
            _ = RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.05))
            let seconds = player.currentTime().seconds
            if seconds.isFinite {
                maximumTime = max(maximumTime, seconds)
            }
            let observation = PlaybackObservation(maximumTime: maximumTime, status: item.status)
            if stopWhen(observation) {
                player.pause()
                return observation
            }
            if item.status == .failed {
                let detail = item.error?.localizedDescription ?? "unknown AVPlayerItem failure"
                throw SpikeError.playbackFailed(label, detail, maximumTime)
            }
        }
        player.pause()
        throw SpikeError.playbackTimedOut(label, maximumTime)
    }

    private static func printEvents(_ events: [LoadingEvent]) {
        for event in events {
            let offset = event.requestedOffset.map(String.init) ?? "none"
            let length = event.requestedLength.map(String.init) ?? "none"
            let toEnd = event.requestsToEnd.map(String.init) ?? "none"
            print(
                "REQUEST path=\(event.path) offset=\(offset) "
                    + "length=\(length) to_end=\(toEnd)"
            )
        }
    }

    fileprivate static func format(_ value: Double) -> String {
        String(format: "%.3f", value)
    }

    private static func run(_ executable: String, _ arguments: [String]) throws {
        let process = Process()
        let output = Pipe()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardOutput = output
        process.standardError = output
        try process.run()
        process.waitUntilExit()
        let text = String(data: output.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        guard process.terminationStatus == 0 else {
            throw SpikeError.commandFailed(executable, process.terminationStatus, text)
        }
    }
}

private extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var encoded = value.littleEndian
        Swift.withUnsafeBytes(of: &encoded) { append(contentsOf: $0) }
    }

    mutating func appendBigEndian<T: FixedWidthInteger>(_ value: T) {
        var encoded = value.bigEndian
        Swift.withUnsafeBytes(of: &encoded) { append(contentsOf: $0) }
    }
}
