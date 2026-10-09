import AppKit
import CryptoKit
import XCTest
@testable import DulcetMac

/// The killed-process download handoff on the Mac (spec §14.5, `docs/download-background-blockers.md`),
/// with real processes: the hosted test launches a copy of the app it runs in, kills it while its
/// background download is outstanding at the server, and launches a replacement.
///
/// The copy is the same build under a bundle identifier of the run's own, re-signed ad hoc. The
/// background-transfer daemon keys a session's files by bundle identifier: OBSERVED locally, an
/// unsandboxed build sharing its identifier with an installed, sandboxed copy of the app had every
/// background download fail with NSURLErrorCannotCreateFile (-3000), and the same build under its
/// own identifier downloaded at once. Its own identifier also keeps the probe's defaults out of
/// the domain the host app and any installed copy read.
///
/// The app's own DEBUG instrument (`DulcetDownloadHandoffProbe`) keeps the run's background
/// session, database, downloads and account apart under a namespace of its own and appends each
/// step to a marker file this test reads. The download goes through the same store action as a
/// row's Download; a launch argument names the track, since this process cannot reach another
/// process's menus. A macOS host test does not inherit xcodebuild's environment, so the server
/// and the proxy arrive through the DulcetMac scheme, from `DULCET_TEST_*` build settings.
@MainActor
final class DulcetMacDownloadHandoffAppTest: XCTestCase {
    private let username = "dulcet-admin"
    private let password = "dulcet-ci-canary-password"
    private let track = "UI Playback Canary"
    private let album = "Threshold Boundary"

    /// 1. `tools/conformance-env/lyrics-fault-proxy` holds the track's `stream` read, so the
    ///    download is outstanding at the server while the child's session reports it running;
    /// 2. the child is killed (SIGKILL), and the held read is still unanswered after it is gone;
    /// 3. the read is released and answered once, with no app process of the run alive;
    /// 4. a replacement process (another pid) reconciles the secured artifact into the durable
    ///    row: the same download, `downloaded` read back from the database, the server's bytes,
    ///    and no second fetch.
    /// Whether macOS itself launched the app for the session's events in the minute after the
    /// read was answered is printed (`DULCET MAC HANDOFF os-launches=`), not asserted: what the
    /// proof stands on is the replacement the person opens.
    func aKilledAppsDownloadIsReconciledByTheReplacementProcessThroughTheHostedMacApp() async throws {
        let environment = ProcessInfo.processInfo.environment
        let baseURL = try XCTUnwrap(environment["DULCET_CONFORMANCE_BASE_URL"], "The disposable server URL must be supplied")
        let proxyURL = environment["DULCET_LYRICS_FAULT_PROXY_URL"] ?? ""
        guard Self.isLoopback(baseURL), environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true", Self.isLoopback(proxyURL) else {
            XCTFail("Refused: the server and the proxy must be disposable loopback services")
            throw HostedAppError.refused
        }
        let server = LiveServer(baseURL: baseURL, username: username, password: password)
        let proxied = LiveServer(baseURL: proxyURL, username: username, password: password)
        let songID = try await server.songID(track, album: album)
        let proxiedID = try await proxied.songID(track, album: album)
        XCTAssertEqual(proxiedID, songID, "The proxy must front the disposable server")
        let original = try await Self.download(songID, from: server)
        let originalDigest = SHA256.hash(data: original).map { String(format: "%02x", $0) }.joined()

        let namespace = "macos-" + UUID().uuidString.prefix(8).lowercased()
        let probe = Probe(namespace: namespace)
        var children: [NSRunningApplication] = []
        let copyDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-\(namespace)", isDirectory: true)
        defer {
            for child in children where !child.isTerminated { kill(child.processIdentifier, SIGKILL) }
            // Failure messages passed to waitUntil capture the trail BEFORE the wait. Keep
            // its final contents as an attachment on every exit, including a startup failure.
            let trail = probe.markers().joined(separator: "\n")
            let attachment = XCTAttachment(string: trail)
            attachment.name = "download-handoff-markers"
            attachment.lifetime = .keepAlways
            add(attachment)
            for line in probe.markers() { print("DULCET MAC HANDOFF final-marker \(line)") }
            probe.remove()
            UserDefaults.standard.removePersistentDomain(forName: Self.copyBundleIdentifier)
            try? FileManager.default.removeItem(at: copyDirectory)
        }
        let copy = try Self.makeCopy(in: copyDirectory)
        let proxy = Proxy(url: proxyURL)
        let since = try await proxy.requestTotal()
        _ = try await proxy.control("POST", "/__dulcet/rule?action=hold&state=on&endpoint=stream")
        do {
            print("DULCET MAC HANDOFF namespace=\(namespace) song=\(songID) bytes=\(original.count) sha256=\(originalDigest)")

            // 1. Outstanding: held at the server, and reported running by the child's session.
            let first = try await Self.launch(copy, [
                "-dulcet-debug-download-handoff", namespace,
                "-dulcet-debug-connect-account",
                "-dulcet-debug-account-server-url", proxyURL,
                "-dulcet-debug-account-username", username,
                "-dulcet-debug-account-password", password,
                "-dulcet-debug-download-handoff-track", track,
            ])
            children.append(first)
            let firstPID = first.processIdentifier
            let held = try await proxy.awaitStreams(of: songID, since: since, timeout: .seconds(90)) { $0.count == 1 && !$0[0].answered }
            XCTAssertNotNil(held, "The download's stream read must reach the proxy and be held; markers=\(probe.markers())")
            try await waitUntil(timeout: .seconds(30), "The child's session must report the download running; markers=\(probe.markers())") {
                probe.markers().contains { $0.contains(" pid=\(firstPID) outstanding download=") }
            }

            // 2. Death while outstanding, and the read still unanswered afterwards.
            XCTAssertEqual(kill(firstPID, SIGKILL), 0, "The child must be killed")
            try await waitUntil(timeout: .seconds(20), "The child must have ended") { kill(firstPID, 0) != 0 }
            let afterDeath = try await proxy.streams(of: songID, since: since)
            print("DULCET MAC HANDOFF died pid=\(firstPID) stream-reads=\(afterDeath.count) answered=\(afterDeath.map(\.answered))")
            XCTAssertEqual(afterDeath.map(\.answered), [false], "The download must still be outstanding after the process died")

            // 3. Release; answered once, by the system on the dead app's behalf.
            _ = try await proxy.control("POST", "/__dulcet/rule?action=hold&state=off")
            let answered = try await proxy.awaitStreams(of: songID, since: since, timeout: .seconds(60)) { $0.allSatisfy(\.answered) }
            XCTAssertEqual(answered?.map(\.status), [200], "The held read must be answered 200, once")

            // Whether macOS launches the app for the session's events by itself.
            let watchEnd = ContinuousClock.now.advanced(by: .seconds(60))
            while ContinuousClock.now < watchEnd, !probe.markers().contains(where: { !$0.contains(" pid=\(firstPID) ") }) {
                try await Task.sleep(for: .milliseconds(500))
            }
            let unattended = DulcetDebugDownloadHandoff.summarize(probe.markers(), currentPID: 0)
            print("DULCET MAC HANDOFF os-launches=\(Self.fields(unattended)["unattended-launches"] ?? "?") watch-seconds=60")

            // 4. The replacement the person opens.
            let replacement = try await Self.launch(copy, ["-dulcet-debug-download-handoff", namespace])
            children.append(replacement)
            try await waitUntil(timeout: .seconds(60), "A replacement must promote the download; markers=\(probe.markers())") {
                probe.markers().contains { $0.contains(" promoted ") }
            }
            let summary = DulcetDebugDownloadHandoff.summarize(probe.markers(), currentPID: replacement.processIdentifier)
            print("DULCET MAC HANDOFF summary \(summary)")
            let fields = Self.fields(summary)
            let outstanding = (fields["outstanding"] ?? "").split(separator: "@").map(String.init)
            let promoted = (fields["promoted"] ?? "").split(separator: "@").map(String.init)
            XCTAssertEqual(outstanding.last, String(firstPID), "The running download must be the killed process's")
            XCTAssertEqual(promoted.first, outstanding.first, "The replacement must promote the download that was outstanding")
            XCTAssertFalse(promoted.last == nil || promoted.last == String(firstPID), "The promotion must come from another process")
            XCTAssertEqual(fields["item"], songID)
            XCTAssertEqual(fields["state"], "downloaded")
            XCTAssertEqual(fields["row"], "downloaded", "The durable row must read back downloaded")
            XCTAssertEqual(fields["bytes"], String(original.count), "The artifact must hold the server's bytes")
            XCTAssertEqual(fields["sha256"], originalDigest, "The artifact must hold the server's bytes")
            // Let anything the replacement would still send arrive before it is counted.
            try await Task.sleep(for: .seconds(3))
            let streams = try await proxy.streams(of: songID, since: since)
            for line in probe.markers() { print("DULCET MAC HANDOFF marker \(line)") }
            print("DULCET MAC HANDOFF stream-reads=\(streams.map { "\($0.answered ? "answered" : "open"):\($0.status)" })")
            XCTAssertEqual(streams.count, 1, "The handoff must not fetch the track again")
        } catch {
            _ = try? await proxy.control("POST", "/__dulcet/rule?action=hold&state=off")
            throw error
        }
        _ = try? await proxy.control("POST", "/__dulcet/rule?action=hold&state=off")
    }

    // MARK: - Helpers

    private static func isLoopback(_ url: String) -> Bool {
        guard let components = URLComponents(string: url), components.scheme == "http" else { return false }
        return components.host == "127.0.0.1" || components.host == "localhost"
    }

    private static func fields(_ summary: String) -> [String: String] {
        Dictionary(summary.split(separator: " ").compactMap { field -> (String, String)? in
            guard let equals = field.firstIndex(of: "=") else { return nil }
            return (String(field[..<equals]), String(field[field.index(after: equals)...]))
        }, uniquingKeysWith: { first, _ in first })
    }

    private static let copyBundleIdentifier = "com.legitimateapps.dulcet.dev.handoff-proof"

    /// The app this test runs in, copied under the run's own bundle identifier and re-signed ad hoc.
    private static func makeCopy(in directory: URL) throws -> URL {
        let files = FileManager.default
        try? files.removeItem(at: directory)
        try files.createDirectory(at: directory, withIntermediateDirectories: true)
        let copy = directory.appendingPathComponent(Bundle.main.bundleURL.lastPathComponent, isDirectory: true)
        try files.copyItem(at: Bundle.main.bundleURL, to: copy)
        // The host's test bundle stays behind: the copy is the app alone.
        try? files.removeItem(at: copy.appendingPathComponent("Contents/PlugIns/DulcetMacTests.xctest"))
        let plist = copy.appendingPathComponent("Contents/Info.plist")
        var info = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: Data(contentsOf: plist), format: nil) as? [String: Any]
        )
        info["CFBundleIdentifier"] = copyBundleIdentifier
        try PropertyListSerialization.data(fromPropertyList: info, format: .binary, options: 0).write(to: plist)
        let sign = Process()
        sign.executableURL = URL(fileURLWithPath: "/usr/bin/codesign")
        sign.arguments = ["--force", "--deep", "--sign", "-", copy.path]
        try sign.run()
        sign.waitUntilExit()
        guard sign.terminationStatus == 0 else { throw HostedAppError.unexpected("ad hoc signing of the copy failed") }
        return copy
    }

    /// An instance of the copy, in the background.
    private static func launch(_ copy: URL, _ arguments: [String]) async throws -> NSRunningApplication {
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        configuration.activates = false
        configuration.addsToRecentItems = false
        configuration.arguments = arguments
        return try await NSWorkspace.shared.openApplication(at: copy, configuration: configuration)
    }

    /// The track's original file, refused if the server answered with an error envelope. The URL
    /// carries a token, so a failure names the endpoint only.
    private static func download(_ songID: String, from server: LiveServer) async throws -> Data {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((server.password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
        var components = try XCTUnwrap(URLComponents(string: server.baseURL))
        components.path = "/rest/download"
        components.queryItems = [
            URLQueryItem(name: "u", value: server.username), URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt), URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-mac-hosted-test"), URLQueryItem(name: "id", value: songID),
        ]
        let (data, response) = try await URLSession.shared.data(from: try XCTUnwrap(components.url))
        let type = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type") ?? ""
        guard (response as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty, !type.contains("xml"), !type.contains("json") else {
            throw HostedAppError.unexpected("/rest/download did not return media")
        }
        return data
    }

    /// The probe's files for one namespace, read and removed from this process.
    private struct Probe {
        let namespace: String
        private let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]

        init(namespace: String) {
            self.namespace = namespace
        }

        private var directory: URL {
            support.appendingPathComponent("Dulcet/DebugDownloadHandoff/\(namespace)", isDirectory: true)
        }

        func markers() -> [String] {
            guard let text = try? String(contentsOf: directory.appendingPathComponent("markers.log"), encoding: .utf8) else {
                return []
            }
            return text.split(separator: "\n").map(String.init)
        }

        /// Everything the run's namespace made.
        func remove() {
            let files = FileManager.default
            let made = [
                directory,
                support.appendingPathComponent("Dulcet/Downloads-handoff-\(namespace)", isDirectory: true),
            ] + ["", "-wal", "-shm", "-journal"].map {
                support.appendingPathComponent("databases/dulcet-handoff-\(namespace).db\($0)")
            }
            for url in made where files.fileExists(atPath: url.path) {
                try? files.removeItem(at: url)
            }
        }
    }

    private struct Stream: Sendable {
        let answered: Bool
        let status: Int
    }

    /// The fault proxy's control surface: credential-free requests only.
    @MainActor
    private struct Proxy {
        let url: String

        func control(_ method: String, _ path: String) async throws -> [String: Any] {
            var request = URLRequest(url: try XCTUnwrap(URL(string: url + path)))
            request.httpMethod = method
            request.timeoutInterval = 10
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw HostedAppError.unexpected("fault proxy \(method) \(path)")
            }
            return body
        }

        func requestTotal() async throws -> Int {
            let total = try await control("GET", "/__dulcet/requests?since=0")["total"] as? Int
            return try XCTUnwrap(total, "The fault proxy must report its request log")
        }

        func streams(of songID: String, since: Int) async throws -> [Stream] {
            let requests = try await control("GET", "/__dulcet/requests?since=\(since)")["requests"] as? [[String: Any]] ?? []
            return requests
                .filter { $0["endpoint"] as? String == "stream" && $0["id"] as? String == songID }
                .map { Stream(answered: $0["answered"] as? Bool ?? false, status: $0["status"] as? Int ?? 0) }
        }

        func awaitStreams(
            of songID: String,
            since: Int,
            timeout: Duration,
            until condition: ([Stream]) -> Bool
        ) async throws -> [Stream]? {
            let deadline = ContinuousClock.now.advanced(by: timeout)
            repeat {
                let seen = try await streams(of: songID, since: since)
                if !seen.isEmpty, condition(seen) { return seen }
                try await Task.sleep(for: .milliseconds(500))
            } while ContinuousClock.now < deadline
            return nil
        }
    }
}
