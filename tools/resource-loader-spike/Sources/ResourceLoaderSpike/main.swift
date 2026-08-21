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

private final class FixtureResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    let recorder = EventRecorder()
    private let fixtureRoot: URL

    init(fixtureRoot: URL) {
        self.fixtureRoot = fixtureRoot
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let url = loadingRequest.request.url else {
            loadingRequest.finishLoading(with: SpikeError.missingRequestURL)
            return true
        }

        let relativePath = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !relativePath.isEmpty, !relativePath.contains("..") else {
            loadingRequest.finishLoading(with: SpikeError.invalidFixturePath(url.path))
            return true
        }

        let fileURL = fixtureRoot.appendingPathComponent(relativePath)
        guard let data = try? Data(contentsOf: fileURL) else {
            loadingRequest.finishLoading(with: SpikeError.missingFixture(relativePath))
            return true
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
    case missingFixture(String)
    case missingRequestURL
    case playbackFailed(String, String)
    case playbackTimedOut(String)
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
        case let .missingFixture(path):
            return "missing fixture: \(path)"
        case .missingRequestURL:
            return "resource-loading request has no URL"
        case let .playbackFailed(label, detail):
            return "\(label) playback failed: \(detail)"
        case let .playbackTimedOut(label):
            return "\(label) playback did not become ready or request media before the deadline"
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

@main
private enum ResourceLoaderSpike {
    static func main() {
        do {
            let expectedSegmentOutcome = try parseArguments()
            let root = FileManager.default.temporaryDirectory
                .appendingPathComponent("dulcet-resource-loader-spike-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: root) }

            try createFixtures(at: root)
            let server = try startHTTPServer(root: root)
            defer {
                server.terminate()
                server.waitUntilExit()
            }
            try waitForHTTPServer()

            let control = try play(
                label: "HTTP HLS negative control",
                url: URL(string: "http://127.0.0.1:18765/playlist.m3u8")!,
                loader: nil,
                stopWhen: { observation in observation.maximumTime >= 0.20 }
            )
            guard control.maximumTime >= 0.20 else {
                throw SpikeError.verificationFailed(
                    "HLS negative control did not advance; custom-scheme absence would be inconclusive"
                )
            }
            print("CONTROL HLS_OVER_HTTP=PLAYED time=\(format(control.maximumTime))s")

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

            let hlsLoader = FixtureResourceLoader(fixtureRoot: root)
            let hlsObservation = try? play(
                label: "custom-scheme HLS",
                url: URL(string: "dulcet-stream://fixture/playlist.m3u8")!,
                loader: hlsLoader,
                stopWhen: { _ in
                    hlsLoader.recorder.events.contains { $0.path.hasPrefix("segment") }
                }
            )
            let hlsEvents = hlsLoader.recorder.events
            let manifestSeen = hlsEvents.contains { $0.path == "playlist.m3u8" }
            let segmentEvents = hlsEvents.filter { $0.path.hasPrefix("segment") }
            guard manifestSeen else {
                throw SpikeError.verificationFailed(
                    "custom-scheme HLS manifest never reached the resource-loader delegate"
                )
            }

            let observedOutcome = segmentEvents.isEmpty ? "escaped" : "routed"
            if segmentEvents.isEmpty {
                print("OBSERVED HLS_MANIFEST=DELEGATE HLS_MEDIA_SEGMENTS=ESCAPED_DELEGATE")
                if let hlsObservation {
                    print("DETAIL custom HLS status=\(statusName(hlsObservation.status)) time=\(format(hlsObservation.maximumTime))s")
                } else {
                    print("DETAIL custom HLS could not play after its manifest was supplied")
                }
            } else {
                print("OBSERVED HLS_MANIFEST=DELEGATE HLS_MEDIA_SEGMENTS=DELEGATE count=\(segmentEvents.count)")
                printEvents(segmentEvents)
            }

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

    private static func parseArguments() throws -> String {
        let arguments = Array(CommandLine.arguments.dropFirst())
        guard arguments.count == 2, arguments[0] == "--expect-hls-segments" else {
            throw SpikeError.unexpectedArgument(arguments.joined(separator: " "))
        }
        guard ["routed", "escaped"].contains(arguments[1]) else {
            throw SpikeError.unexpectedArgument(arguments[1])
        }
        return arguments[1]
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
        segment0.aac
        #EXTINF:1.0,
        segment1.aac
        #EXT-X-ENDLIST

        """
        try Data(playlist.utf8).write(to: root.appendingPathComponent("playlist.m3u8"))
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

    private static func startHTTPServer(root: URL) throws -> Process {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = [
            "-m", "http.server", "18765", "--bind", "127.0.0.1", "--directory", root.path,
        ]
        process.standardOutput = FileHandle.standardError
        process.standardError = FileHandle.standardError
        try process.run()
        return process
    }

    private static func waitForHTTPServer() throws {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let semaphore = DispatchSemaphore(value: 0)
            var succeeded = false
            URLSession.shared.dataTask(with: URL(string: "http://127.0.0.1:18765/playlist.m3u8")!) {
                _, response, _ in
                succeeded = (response as? HTTPURLResponse)?.statusCode == 200
                semaphore.signal()
            }.resume()
            _ = semaphore.wait(timeout: .now() + 1)
            if succeeded { return }
            Thread.sleep(forTimeInterval: 0.1)
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
                throw SpikeError.playbackFailed(label, detail)
            }
        }
        player.pause()
        throw SpikeError.playbackTimedOut(label)
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

    private static func statusName(_ status: AVPlayerItem.Status) -> String {
        switch status {
        case .unknown: return "unknown"
        case .readyToPlay: return "readyToPlay"
        case .failed: return "failed"
        @unknown default: return "future(\(status.rawValue))"
        }
    }

    private static func format(_ value: Double) -> String {
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
