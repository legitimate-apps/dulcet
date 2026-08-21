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
    case httpServerDidNotStart
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
        case .httpServerDidNotStart:
            return "localhost HLS control server did not become ready"
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

private struct PlaybackObservation {
    let maximumTime: Double
    let status: AVPlayerItem.Status
}

private struct HTTPFixtureServer {
    let process: Process
    let controlManifestURL: URL
}

private struct SpikeArguments {
    let expectedSegmentOutcome: String
}

@main
private enum ResourceLoaderSpike {
    static func main() {
        do {
            let arguments = try parseArguments()
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
            guard arguments.expectedSegmentOutcome == observedOutcome else {
                throw SpikeError.verificationFailed(
                    "recorded HLS outcome expected \(arguments.expectedSegmentOutcome), observed \(observedOutcome)"
                )
            }
            print("VERDICT expected=\(arguments.expectedSegmentOutcome) observed=\(observedOutcome) PASS")
        } catch {
            FileHandle.standardError.write(Data("resource-loader spike ERROR: \(error.localizedDescription)\n".utf8))
            Foundation.exit(1)
        }
    }

    private static func parseArguments() throws -> SpikeArguments {
        let arguments = Array(CommandLine.arguments.dropFirst())
        guard arguments.count == 2,
              arguments[0] == "--expect-hls-segments"
        else {
            throw SpikeError.unexpectedArgument(arguments.joined(separator: " "))
        }
        guard ["all-routed", "first-only-playback-failed"].contains(arguments[1]) else {
            throw SpikeError.unexpectedArgument(arguments[1])
        }
        return SpikeArguments(expectedSegmentOutcome: arguments[1])
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
            import http.server, os, pathlib, sys
            os.chdir(sys.argv[1])
            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), http.server.SimpleHTTPRequestHandler)
            pathlib.Path(sys.argv[2]).write_text(str(server.server_address[1]), encoding='ascii')
            server.serve_forever()
            """,
            root.path,
            portFile.path,
        ]
        process.standardOutput = FileHandle.standardError
        process.standardError = FileHandle.standardError
        try process.run()
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            guard let portText = try? String(contentsOf: portFile, encoding: .ascii),
                  let port = Int(portText)
            else {
                if !process.isRunning { throw SpikeError.httpServerDidNotStart }
                Thread.sleep(forTimeInterval: 0.1)
                continue
            }
            let controlURL = URL(string: "http://127.0.0.1:\(port)/control.m3u8")!
            let semaphore = DispatchSemaphore(value: 0)
            var succeeded = false
            URLSession.shared.dataTask(with: controlURL) {
                _, response, _ in
                succeeded = (response as? HTTPURLResponse)?.statusCode == 200
                semaphore.signal()
            }.resume()
            _ = semaphore.wait(timeout: .now() + 1)
            if succeeded {
                return HTTPFixtureServer(process: process, controlManifestURL: controlURL)
            }
            Thread.sleep(forTimeInterval: 0.1)
        }
        if process.isRunning {
            process.terminate()
            process.waitUntilExit()
        }
        throw SpikeError.httpServerDidNotStart
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
