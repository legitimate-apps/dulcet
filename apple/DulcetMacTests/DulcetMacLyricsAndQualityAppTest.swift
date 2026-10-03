import AppKit
import CryptoKit
import DulcetCore
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

/// The Mac's lyrics panel and streaming-quality choice, driven through the production root view
/// hosted in the app, over the production playback controller, library reader and streaming
/// setting, against the disposable server (spec §18.4, §12.5).
///
/// The account is connected through the presentation store, as the other hosted proofs connect
/// it; everything after that goes through the rendered views by accessibility: the sidebar,
/// the search field typed into, a result row and Return, the now-playing bar, the lyrics toggle,
/// Try Again, and the Settings window's picker. A macOS host test does not inherit
/// xcodebuild's environment, so the run's values arrive through the DulcetMac scheme, from
/// `DULCET_TEST_*` build settings.
@MainActor
final class DulcetMacLyricsAndQualityAppTest: XCTestCase {
    private let username = "dulcet-admin"
    private let password = "dulcet-ci-canary-password"

    /// Every state of the Mac lyrics panel, rendered beside the player:
    ///
    /// 1. synced: "Twenty Nine Seconds" lights an English line as media time moves;
    /// 2. plain: "Ogg Probe"'s two unsynced sidecar lines, and nothing lit;
    /// 3. none: "UI Playback Canary" says it has no lyrics, with no spinner;
    /// 4. a failed read: "Thirty One Seconds" through `tools/conformance-env/lyrics-fault-proxy`,
    ///    which fails its lyrics read with HTTP 500 until disarmed. The panel says so and offers
    ///    Try Again; the proxy's count shows the failure was met, and after the disarm Try Again
    ///    sends a new read and the synced lines appear.
    ///
    /// The whole run is connected through the proxy, which forwards everything else unchanged,
    /// and each track's shape is first read back from the server itself. The reader's database
    /// is the run's own, so no stored document from an earlier run can answer for the failure.
    func lyricsPanelShowsEveryStateThroughTheHostedMacApp() async throws {
        let fixture = try Fixture()
        let server = fixture.server
        let album = "Threshold Boundary"
        let synced = try await server.songID("Twenty Nine Seconds", album: album)
        let plain = try await server.songID("Ogg Probe", album: "Dulcet Conformance")
        let none = try await server.songID("UI Playback Canary", album: album)
        let failing = try await server.songID("Thirty One Seconds", album: album)
        let syncedLayers = try await server.lyricsLayers(synced)
        XCTAssertTrue(syncedLayers.contains { $0.synced && $0.lines.contains("Dulcet English line one") }, "synced fixture")
        let plainLayers = try await server.lyricsLayers(plain)
        XCTAssertEqual(plainLayers.map(\.synced), [false], "plain fixture")
        let plainLines = try XCTUnwrap(plainLayers.first?.lines)
        XCTAssertEqual(plainLines, ["Dulcet plain sidecar line one", "Dulcet plain sidecar line two"])
        let noLayers = try await server.lyricsLayers(none)
        XCTAssertTrue(noLayers.isEmpty, "UI Playback Canary must carry no lyrics")
        let failingLayers = try await server.lyricsLayers(failing)
        XCTAssertTrue(failingLayers.contains { $0.synced }, "Thirty One Seconds must carry synced lyrics")
        // Server identity: the proxy's library is the server's.
        let proxied = LiveServer(baseURL: fixture.proxyURL, username: username, password: password)
        let proxiedID = try await proxied.songID("Thirty One Seconds", album: album)
        XCTAssertEqual(proxiedID, failing, "The lyrics fault proxy must front the disposable server")

        let app = try await HostedApp(serverURL: fixture.proxyURL, username: username, password: password)
        defer { app.close() }

        // 1. Synced, beside the player.
        try await app.play(query: "Twenty Nine", track: "Twenty Nine Seconds")
        try await app.showLyrics()
        let lit = try await app.element(identifiedBy: "dulcet.lyrics.line.current", timeout: .seconds(25))
        let litLabel = app.label(lit) ?? ""
        XCTAssertTrue(litLabel.hasPrefix("Dulcet English line"), "The lit line must be the English layer's; lit=\(litLabel)")
        let panel = try await app.element(identifiedBy: "dulcet.lyrics.panel", timeout: .seconds(5))
        // Beside the player only where the window is wide enough for two columns (820 points of
        // Now Playing, DulcetNowPlayingView.usesSideBySideLayout). The window asks for 1180 points,
        // but AppKit fits a titled window to the screen, and a CI host's screen can be narrower, so
        // the placement is asserted for the width the window actually got.
        let panelFrame = try app.frame(panel)
        let width = app.contentWidth
        let placement: String
        if let title = await app.element(identifiedBy: "dulcet.now-playing.title", within: .seconds(5)) {
            let titleFrame = try app.frame(title)
            placement = "side-by-side panel=\(panelFrame) title=\(titleFrame) window=\(width)"
            print("OBSERVED Mac lyrics placement: \(placement)")
            XCTAssertGreaterThanOrEqual(panelFrame.minX, titleFrame.maxX,
                "The lyrics must sit beside the player in the Mac window; \(placement)")
        } else {
            placement = "one-column panel=\(panelFrame) window=\(width)"
            print("OBSERVED Mac lyrics placement: \(placement)")
            XCTAssertLessThan(width, 1180,
                "Only a window narrowed to its screen may put the lyrics in place of the player; window=\(width)")
        }

        // 2. Plain: both lines, nothing lit.
        try await app.play(query: "Ogg Probe", track: "Ogg Probe")
        try await app.showLyrics()
        for line in plainLines {
            _ = try await app.element(timeout: .seconds(20), described: "plain line \(line)") {
                app.identifier($0) == "dulcet.lyrics.line" && app.label($0) == line
            }
        }
        XCTAssertNil(app.firstElement { app.identifier($0) == "dulcet.lyrics.line.current" },
            "Plain lyrics have no current line, so none may be lit")

        // 3. None: said, and no spinner.
        try await app.play(query: "UI Playback Canary", track: "UI Playback Canary")
        try await app.showLyrics()
        let noLyrics = try await app.element(identifiedBy: "dulcet.lyrics.none", timeout: .seconds(20))
        XCTAssertEqual(app.label(noLyrics), "No lyrics for this song.")
        XCTAssertNil(app.firstElement { app.label($0) == "Loading lyrics" },
            "A track the server has no lyrics for must never show a spinner")

        // 4. A failed read, then Try Again.
        _ = try await fixture.proxy("POST", "/__dulcet/lyrics-failure?song=\(failing)&state=on")
        var disarmed = false
        defer {
            if !disarmed {
                Task { _ = try? await fixture.proxy("POST", "/__dulcet/lyrics-failure?song=\(failing)&state=off") }
            }
        }
        try await app.play(query: "Thirty One", track: "Thirty One Seconds")
        try await app.showLyrics()
        _ = try await app.element(identifiedBy: "dulcet.lyrics.unavailable", timeout: .seconds(30))
        let retry = try await app.element(identifiedBy: "dulcet.lyrics.retry", timeout: .seconds(5))
        XCTAssertNotNil(app.firstElement { (app.label($0) ?? "").hasPrefix("Lyrics couldn\u{2019}t be loaded") },
            "The panel must say the lyrics could not be loaded")
        let afterFailure = try await fixture.proxy("POST", "/__dulcet/lyrics-failure?song=\(failing)&state=off")
        disarmed = true
        let failedReads = afterFailure["failed"] as? Int ?? 0
        XCTAssertGreaterThanOrEqual(failedReads, 1,
            "The proxy must have failed this track's lyrics read; otherwise the state above came from somewhere else")
        try app.press(retry, named: "dulcet.lyrics.retry")
        _ = try await app.element(timeout: .seconds(30), described: "the synced lines after Try Again") {
            (app.label($0) ?? "").hasPrefix("Dulcet synced line")
        }
        let afterRetry = try await fixture.proxy("GET", "/__dulcet/observations?song=\(failing)")
        let retryReads = afterRetry["forwardedAfterDisarm"] as? Int ?? 0
        XCTAssertGreaterThanOrEqual(retryReads, 1, "Try Again must send a new lyrics read")
        print("DULCET MAC LYRICS STATES PASS synced-lit=\(litLabel.debugDescription) placement=\(placement)"
            + " plain-lines=\(plainLines.count) none=\((app.label(noLyrics) ?? "").debugDescription)"
            + " failed-reads=\(failedReads) retry-reads=\(retryReads)")
    }

    /// The Wi-Fi choice in the Mac's Settings window (Dulcet > Settings…), made with its picker,
    /// caps the next song the app streams: the disposable server's own log records the stream of
    /// "Dulcet Health Probe" -- a 123 kbps FLAC, read back first -- as transcoded to 96 kbps. The
    /// log is read from the offset it had before the play. The setting is the production one
    /// over a defaults suite of this run's own, and the cellular choice is set to 128 kbps first,
    /// so a cap from the wrong row would show as 128. A Mac on a wired or Wi-Fi network is not
    /// metered; the run asserts that before reading anything into the cap.
    func streamingQualityChosenInSettingsCapsTheStreamThroughTheHostedMacApp() async throws {
        let fixture = try Fixture()
        let server = fixture.server
        let track = "Dulcet Health Probe"
        let songID = try await server.songID(track, album: "Dulcet Conformance")
        let sourceKbps = try await server.bitRate(songID)
        XCTAssertGreaterThan(sourceKbps, 96, "The control: the source must be above the cap, or no cap would be sent")
        let logBefore = try XCTUnwrap(Self.fileSize(fixture.serverLog), "The server log must be readable: \(fixture.serverLog)")

        let app = try await HostedApp(serverURL: server.baseURL, username: username, password: password)
        defer { app.close() }
        let settings = app.openSettingsWindow()
        defer { settings.close() }
        try await app.choose("128 kbps", picker: "dulcet.streaming-quality.metered", in: settings)
        try await app.choose("96 kbps", picker: "dulcet.streaming-quality.unmetered", in: settings)
        XCTAssertEqual(app.store.streamingQuality, DulcetStreamingQualityPreference(unmetered: .kbps96, metered: .kbps128),
            "The choices must be saved through the installed setting")
        XCTAssertEqual(app.streamingQuality.currentQuality, StreamingQuality.kbps96,
            "This Mac's network must be unmetered, so the Wi-Fi choice is the one the next resolve applies")
        settings.orderOut(nil)

        try await app.play(query: track, track: track)
        var lines: [[String: String]] = []
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        repeat {
            lines = Self.streamLines(fixture.serverLog, from: logBefore).filter { $0["title"] == track }
            if !lines.isEmpty { break }
            try await Task.sleep(for: .milliseconds(500))
        } while ContinuousClock.now < deadline
        XCTAssertFalse(lines.isEmpty, "The server must log a stream of \(track) after the play")
        for line in lines {
            XCTAssertEqual(line["transcoding"], "true", "The server must transcode the capped stream; line=\(line)")
            XCTAssertEqual(line["bitRate"], "96", "The stream must carry the Wi-Fi choice, 96 kbps; line=\(line)")
            XCTAssertEqual(line["originalBitRate"], String(sourceKbps), "The line must be this source's; line=\(line)")
        }
        let summary = lines.map { "\($0["format"] ?? "?")@\($0["bitRate"] ?? "?")" }.joined(separator: ",")
        print("DULCET MAC STREAMING QUALITY PASS track=\(track.debugDescription) source-kbps=\(sourceKbps)"
            + " server-streams=\(summary) chosen=settings-window-picker")
    }

    // MARK: - The run's fixture

    private struct Fixture {
        let server: LiveServer
        let proxyURL: String
        let serverLog: String

        init() throws {
            let environment = ProcessInfo.processInfo.environment
            let baseURL = try XCTUnwrap(environment["DULCET_CONFORMANCE_BASE_URL"], "The disposable server URL must be supplied")
            guard Self.isLoopback(baseURL), environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true" else {
                XCTFail("Refused: \(baseURL.debugDescription) is not a disposable loopback server")
                throw HostedAppError.refused
            }
            server = LiveServer(baseURL: baseURL, username: "dulcet-admin", password: "dulcet-ci-canary-password")
            proxyURL = environment["DULCET_LYRICS_FAULT_PROXY_URL"] ?? ""
            serverLog = environment["DULCET_SERVER_LOG"] ?? ""
        }

        static func isLoopback(_ url: String) -> Bool {
            guard let components = URLComponents(string: url), components.scheme == "http" else { return false }
            return components.host == "127.0.0.1" || components.host == "localhost"
        }

        /// One credential-free control request to the lyrics fault proxy.
        func proxy(_ method: String, _ path: String) async throws -> [String: Any] {
            guard Self.isLoopback(proxyURL), let url = URL(string: proxyURL + path) else {
                XCTFail("DULCET_LYRICS_FAULT_PROXY_URL must name a loopback lyrics fault proxy")
                throw HostedAppError.refused
            }
            var request = URLRequest(url: url)
            request.httpMethod = method
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let body = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw HostedAppError.unexpected("lyrics fault proxy \(method) \(path)")
            }
            return body
        }
    }

    private static func fileSize(_ path: String) -> UInt64? {
        guard !path.isEmpty, let handle = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? handle.close() }
        return try? handle.seekToEnd()
    }

    /// The server's "Streaming file" lines written after `offset`, as their key=value fields.
    private static func streamLines(_ path: String, from offset: UInt64) -> [[String: String]] {
        guard let handle = FileHandle(forReadingAtPath: path) else { return [] }
        defer { try? handle.close() }
        guard (try? handle.seek(toOffset: offset)) != nil, let data = try? handle.readToEnd(),
              let text = String(data: data, encoding: .utf8) else { return [] }
        return text.split(separator: "\n").filter { $0.contains("msg=\"Streaming file\"") }.map { line in
            var fields: [String: String] = [:]
            var rest = Substring(line)
            while let equals = rest.firstIndex(of: "=") {
                let key = rest[..<equals].split(separator: " ").last.map(String.init) ?? ""
                rest = rest[rest.index(after: equals)...]
                let value: Substring
                if rest.first == "\"" {
                    let body = rest.dropFirst()
                    let end = body.firstIndex(of: "\"") ?? body.endIndex
                    value = body[..<end]
                    rest = end < body.endIndex ? body[body.index(after: end)...] : ""
                } else {
                    let end = rest.firstIndex(of: " ") ?? rest.endIndex
                    value = rest[..<end]
                    rest = rest[end...]
                }
                if !key.isEmpty { fields[key] = String(value) }
            }
            return fields
        }
    }
}
