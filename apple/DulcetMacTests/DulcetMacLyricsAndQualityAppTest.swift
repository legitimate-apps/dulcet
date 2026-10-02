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
        let title = try await app.element(identifiedBy: "dulcet.now-playing.title", timeout: .seconds(5))
        let panelFrame = try app.frame(panel)
        let titleFrame = try app.frame(title)
        let placement = "panel=\(panelFrame) title=\(titleFrame)"
        XCTAssertGreaterThanOrEqual(panelFrame.minX, titleFrame.maxX,
            "The lyrics must sit beside the player in the Mac window; \(placement)")

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

private enum HostedAppError: Error {
    case refused
    case unexpected(String)
}

// MARK: - The hosted app

/// The production root view in a window, over the production controller, reader and setting,
/// each with storage of this run's own.
@MainActor
private final class HostedApp {
    let store: DulcetPresentationStore
    let streamingQuality: DulcetCoreStreamingQuality
    private let session: DulcetLibrarySession
    private let controller: DulcetCorePlaybackController
    private let window: NSWindow
    private let hostingView: NSView
    private let defaultsSuite: String
    private let previousEnhancedUI: Any

    init(serverURL: String, username: String, password: String) async throws {
        let run = UUID().uuidString
        defaultsSuite = "dulcet-mac-hosted-\(run)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsSuite))
        streamingQuality = DulcetCoreStreamingQuality(defaults: defaults)
        controller = DulcetCorePlaybackController(databaseName: "dulcet-mac-hosted-playback-\(run).db")
        controller.streamingQuality = streamingQuality
        session = DulcetLibrarySession(factory: DulcetCoreLibraryReaderFactory(databaseName: "dulcet-mac-hosted-reader-\(run).db"))
        let providerInstanceID = "macos-hosted-\(run)"
        store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: DulcetCoreAccountConnector(),
            credentialStore: HostedCredentialStore(),
            playbackController: controller,
            providerInstanceIDFactory: { providerInstanceID },
            librarySession: session
        ))
        store.streamingQualitySetting = streamingQuality
        store.accountServerURL = serverURL
        store.accountUsername = username
        store.accountPassword = password
        store.accountAllowLocalHTTP = true
        store.submitAccountConnection()

        let enhancedUI = NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface")
        previousEnhancedUI = NSApp.accessibilityAttributeValue(enhancedUI) ?? false
        NSApp.accessibilitySetValue(true, forAttribute: enhancedUI)
        let hosting = NSHostingView(rootView: DulcetMacProduction.makeRootView(store: store))
        hosting.frame = NSRect(x: 0, y: 0, width: 1180, height: 760)
        hostingView = hosting
        window = NSWindow(contentRect: hosting.frame, styleMask: [.titled, .closable, .resizable],
                          backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentView = hosting
        window.makeKeyAndOrderFront(nil)

        let session = session
        let store = store
        try await waitUntil(timeout: .seconds(20),
                            "connect: state=\(store.snapshot.state) mode=\(session.mode)") {
            store.snapshot.accountConnected && session.mode == .connected && session.reader != nil
        }
    }

    func close() {
        controller.disconnect()
        window.orderOut(nil)
        NSApp.accessibilitySetValue(previousEnhancedUI, forAttribute: NSAccessibility.Attribute(rawValue: "AXEnhancedUserInterface"))
        UserDefaults.standard.removePersistentDomain(forName: defaultsSuite)
    }

    /// Search from the sidebar, the query typed into the field, the track's row selected and
    /// Return pressed; then the now-playing bar pressed, opening Now Playing on that track.
    func play(query: String, track: String) async throws {
        let destination = try await element(identifiedBy: "dulcet.sidebar.search", timeout: .seconds(10))
        _ = try selectTableRow(destination)
        try await waitUntil(timeout: .seconds(5), "search destination: \(store.selectedDestination)") {
            self.store.selectedDestination == .search
        }
        let fieldElement = try await element(identifiedBy: "dulcet.search.field", timeout: .seconds(10))
        let field = try XCTUnwrap(
            (fieldElement as? NSTextField) ?? (fieldElement as? NSCell)?.controlView as? NSTextField,
            "dulcet.search.field must resolve to NSTextField; observed \(type(of: fieldElement))"
        )
        XCTAssertTrue(window.makeFirstResponder(field), "The search field must take focus")
        // A query an earlier step typed is selected, so the typing replaces it.
        field.currentEditor()?.selectAll(nil)
        try sendText(query)
        try await waitUntil(timeout: .seconds(5), "typed=\(query.debugDescription) bound=\(store.searchQuery.debugDescription)") {
            self.store.searchQuery == query
        }
        let row = try await element(timeout: .seconds(30), described: "the \(track) search row") {
            (self.identifier($0) ?? "").hasPrefix("dulcet.search.result.")
                && (self.label($0) ?? "").hasPrefix("\(track), ") && (self.label($0) ?? "").hasSuffix(", Track")
        }
        let table = try selectTableRow(row)
        XCTAssertTrue(window.makeFirstResponder(table), "The results must take focus")
        try sendReturn()
        try await waitUntil(timeout: .seconds(15), "now playing=\(String(describing: store.snapshot.nowPlaying?.current.title))") {
            self.store.snapshot.nowPlaying?.current.title == track
        }
        let bar = try await element(identifiedBy: "dulcet.mini-player.open", timeout: .seconds(10))
        try press(bar, named: "dulcet.mini-player.open")
        try await waitUntil(timeout: .seconds(5), "state=\(store.snapshot.state)") {
            self.store.snapshot.state == .nowPlaying
        }
        let title = try await element(identifiedBy: "dulcet.now-playing.title", timeout: .seconds(10))
        XCTAssertEqual(label(title), track, "Now Playing must show \(track)")
    }

    /// Presses Now Playing's lyrics toggle when lyrics are not already shown.
    func showLyrics() async throws {
        let toggle = try await element(identifiedBy: "dulcet.now-playing.lyrics", timeout: .seconds(10))
        if label(toggle) == "Show Lyrics" {
            try press(toggle, named: "dulcet.now-playing.lyrics")
        }
        _ = try await element(identifiedBy: "dulcet.lyrics.panel", timeout: .seconds(10))
    }

    /// The Settings scene's content in a window of its own, as Dulcet > Settings… shows it.
    func openSettingsWindow() -> NSWindow {
        let hosting = NSHostingView(rootView: DulcetSettingsView(store: store))
        hosting.frame = NSRect(x: 0, y: 0, width: 480, height: 320)
        let settings = NSWindow(contentRect: hosting.frame, styleMask: [.titled, .closable], backing: .buffered, defer: false)
        settings.isReleasedWhenClosed = false
        settings.contentView = hosting
        settings.orderFront(nil)
        hosting.layoutSubtreeIfNeeded()
        return settings
    }

    /// Chooses `option` in the identified menu picker the way a click does: AXPress opens its
    /// menu, and the item titled `option` is performed from inside the menu's tracking, which then
    /// ends. The picker must read back the option afterwards.
    ///
    /// SwiftUI draws the picker without an AppKit pop-up button (OBSERVED on macOS 26: the
    /// identifier is on an `AccessibilityNode` and no `NSPopUpButton` exists in the window), so the
    /// menu is the only AppKit object a choice goes through.
    func choose(_ option: String, picker identifier: String, in settings: NSWindow) async throws {
        let root = try XCTUnwrap(settings.contentView)
        let picker = try await element(timeout: .seconds(10), described: identifier, in: root) {
            self.identifier($0) == identifier
        }
        // Main-thread only: the notification and the run-loop block both arrive on the main thread.
        final class Tracking: @unchecked Sendable {
            var offered: [String] = []
            var performed = false
            var opened = false
            var menu: NSMenu?
        }
        let tracking = Tracking()
        let observer = NotificationCenter.default.addObserver(
            forName: NSMenu.didBeginTrackingNotification, object: nil, queue: nil
        ) { notification in
            guard let menu = notification.object as? NSMenu else { return }
            tracking.opened = true
            tracking.offered = menu.items.map(\.title)
            tracking.menu = menu
            // Performed once tracking runs, in the run-loop mode tracking uses.
            RunLoop.main.perform(inModes: [.eventTracking, .common]) {
                MainActor.assumeIsolated {
                    guard let menu = tracking.menu else { return }
                    if let index = menu.items.firstIndex(where: { $0.title == option }) {
                        menu.performActionForItem(at: index)
                        tracking.performed = true
                    }
                    menu.cancelTracking()
                }
            }
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        // AXPress returns before the menu opens: SwiftUI opens it on a later turn of the main run
        // loop (OBSERVED on macOS 26), which the wait below spins.
        try press(picker, named: identifier)
        try await waitUntil(timeout: .seconds(5), "\(identifier) opens its menu on AXPress") { tracking.opened }
        XCTAssertTrue(tracking.offered.contains(option), "\(identifier) must offer \(option); offers \(tracking.offered)")
        try await waitUntil(timeout: .seconds(5), "\(identifier) shows \(option); reads \(self.label(picker) ?? "nil")") {
            root.layoutSubtreeIfNeeded()
            return tracking.performed && ((self.label(picker) ?? "").contains(option)
                || (self.value("accessibilityValue", of: picker) as? String)?.contains(option) == true)
        }
    }

    // MARK: Accessibility

    func element(identifiedBy identifier: String, timeout: Duration) async throws -> Any {
        try await element(timeout: timeout, described: identifier) { self.identifier($0) == identifier }
    }

    func element(
        timeout: Duration,
        described description: String,
        in root: NSView? = nil,
        where matches: (Any) -> Bool
    ) async throws -> Any {
        let root = root ?? hostingView
        let deadline = ContinuousClock.now.advanced(by: timeout)
        repeat {
            root.layoutSubtreeIfNeeded()
            if let match = descendants(of: root).first(where: matches) { return match }
            try await Task.sleep(for: .milliseconds(100))
        } while ContinuousClock.now < deadline
        let tree = descendants(of: root).compactMap { element -> String? in
            guard let id = identifier(element), id.hasPrefix("dulcet.") else { return nil }
            return "\(id)=\(label(element) ?? "nil")"
        }
        XCTFail("\(description) never appeared; dulcet elements: \(tree.joined(separator: "; "))")
        throw HostedAppError.unexpected(description)
    }

    func firstElement(where matches: (Any) -> Bool) -> Any? {
        hostingView.layoutSubtreeIfNeeded()
        return descendants(of: hostingView).first(where: matches)
    }

    func press(_ element: Any, named name: String) throws {
        let press = #selector(NSAccessibilityProtocol.accessibilityPerformPress)
        let object = try XCTUnwrap(element as? NSObject, "\(name) is not an accessibility object")
        XCTAssertTrue(object.responds(to: press), "\(name) does not answer AXPress")
        _ = object.perform(press)
    }

    func frame(_ element: Any) throws -> NSRect {
        let accessible = try XCTUnwrap(element as? any NSAccessibilityElementProtocol, "no frame on \(type(of: element))")
        return accessible.accessibilityFrame()
    }

    func identifier(_ element: Any) -> String? {
        value("accessibilityIdentifier", of: element) as? String
    }

    func label(_ element: Any) -> String? {
        value("accessibilityLabel", of: element) as? String
            ?? value("accessibilityTitle", of: element) as? String
            ?? value("accessibilityValue", of: element) as? String
    }

    func value(_ name: String, of element: Any) -> Any? {
        guard let object = element as? NSObject else { return nil }
        let selector = NSSelectorFromString(name)
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue()
    }

    private func descendants(of root: Any) -> [Any] {
        var result: [Any] = []
        var visited = Set<ObjectIdentifier>()
        var pending: [Any] = [root]
        while let current = pending.popLast() {
            guard let object = current as AnyObject? else { continue }
            guard visited.insert(ObjectIdentifier(object)).inserted else { continue }
            var children = value("accessibilityChildren", of: current) as? [Any] ?? []
            if let view = current as? NSView { children.append(contentsOf: view.subviews) }
            result.append(current)
            pending.append(contentsOf: children)
        }
        return result
    }

    private func selectTableRow(_ element: Any) throws -> NSTableView {
        let screenFrame = try frame(element)
        let point = window.convertPoint(fromScreen: NSPoint(x: screenFrame.midX, y: screenFrame.midY))
        let content = try XCTUnwrap(window.contentView)
        let table = try XCTUnwrap(content.hitTest(content.convert(point, from: nil)) as? NSTableView,
            "Expected a table at \(point) for \(identifier(element) ?? "nil")")
        let index = table.row(at: table.convert(point, from: nil))
        let rows = try XCTUnwrap(value("accessibilityRows", of: table) as? [Any])
        guard rows.indices.contains(index) else {
            throw HostedAppError.unexpected("\(identifier(element) ?? "nil"): row \(index) of \(rows.count)")
        }
        table.perform(NSSelectorFromString("setAccessibilitySelectedRows:"), with: [rows[index]])
        XCTAssertEqual(table.selectedRow, index, "Accessibility selection for \(identifier(element) ?? "nil")")
        return table
    }

    private func sendText(_ text: String) throws {
        for character in text {
            let value = String(character)
            for type in [NSEvent.EventType.keyDown, .keyUp] {
                let event = try XCTUnwrap(NSEvent.keyEvent(
                    with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                    windowNumber: window.windowNumber, context: nil, characters: value,
                    charactersIgnoringModifiers: value, isARepeat: false, keyCode: 0
                ))
                NSApp.sendEvent(event)
            }
        }
    }

    private func sendReturn() throws {
        for type in [NSEvent.EventType.keyDown, .keyUp] {
            let event = try XCTUnwrap(NSEvent.keyEvent(
                with: type, location: .zero, modifierFlags: [], timestamp: ProcessInfo.processInfo.systemUptime,
                windowNumber: window.windowNumber, context: nil, characters: "\r",
                charactersIgnoringModifiers: "\r", isARepeat: false, keyCode: 36
            ))
            NSApp.sendEvent(event)
        }
    }
}

@MainActor
private func waitUntil(
    timeout: Duration,
    _ failureMessage: @autoclosure () -> String,
    condition: @MainActor () -> Bool
) async throws {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while !condition() {
        if ContinuousClock.now >= deadline {
            XCTFail(failureMessage())
            throw HostedAppError.unexpected(failureMessage())
        }
        try await Task.sleep(for: .milliseconds(50))
    }
}

/// An in-memory credential store that remembers the provider instance, as the Keychain store does.
private final class HostedCredentialStore: DulcetProviderInstanceCredentialStoring {
    private var persisted: DulcetAccountConnectRequest?
    private(set) var providerInstanceID: String?
    private(set) var credentialGeneration: Int64 = 0

    func load() throws -> DulcetAccountConnectRequest? { persisted }
    func save(_ request: DulcetAccountConnectRequest) throws {
        persisted = request
        credentialGeneration += 1
    }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        persisted = request
        self.providerInstanceID = providerInstanceID
        credentialGeneration += 1
    }
    func delete() throws {
        persisted = nil
        providerInstanceID = nil
        credentialGeneration += 1
    }
}

/// The disposable server, read directly over `/rest` with its published fixture account.
private struct LiveServer {
    let baseURL: String
    let username: String
    let password: String

    struct LyricsLayer {
        let synced: Bool
        let lines: [String]
    }

    func call(_ endpoint: String, _ query: [URLQueryItem]) async throws -> [String: Any] {
        let salt = (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
        let token = Insecure.MD5.hash(data: Data((password + salt).utf8)).map { String(format: "%02x", $0) }.joined()
        var components = try XCTUnwrap(URLComponents(string: baseURL))
        components.path = "/rest/" + endpoint
        components.queryItems = [
            URLQueryItem(name: "u", value: username), URLQueryItem(name: "t", value: token),
            URLQueryItem(name: "s", value: salt), URLQueryItem(name: "v", value: "1.16.1"),
            URLQueryItem(name: "c", value: "dulcet-mac-hosted-test"), URLQueryItem(name: "f", value: "json"),
        ] + query
        let (data, _) = try await URLSession.shared.data(from: try XCTUnwrap(components.url))
        // The URL carries a token, so a failure names the endpoint only.
        guard let document = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let envelope = document["subsonic-response"] as? [String: Any],
              envelope["status"] as? String == "ok" else {
            throw HostedAppError.unexpected("/rest/\(endpoint) did not return an ok envelope")
        }
        return envelope
    }

    func songID(_ title: String, album: String) async throws -> String {
        let result = try await call("search3", [
            URLQueryItem(name: "query", value: title), URLQueryItem(name: "songCount", value: "20"),
            URLQueryItem(name: "albumCount", value: "0"), URLQueryItem(name: "artistCount", value: "0"),
        ])
        let songs = (result["searchResult3"] as? [String: Any])?["song"] as? [[String: Any]] ?? []
        let matches = songs.filter { $0["title"] as? String == title && $0["album"] as? String == album }
        guard matches.count == 1, let id = matches[0]["id"] as? String else {
            throw HostedAppError.unexpected("\(matches.count) songs named \(title) on \(album); exactly one is required")
        }
        return id
    }

    func bitRate(_ songID: String) async throws -> Int {
        let song = try await call("getSong", [URLQueryItem(name: "id", value: songID)])["song"] as? [String: Any]
        return try XCTUnwrap(song?["bitRate"] as? Int, "The server must report the source bitrate")
    }

    func lyricsLayers(_ songID: String) async throws -> [LyricsLayer] {
        let list = try await call("getLyricsBySongId", [URLQueryItem(name: "id", value: songID)])["lyricsList"] as? [String: Any]
        return (list?["structuredLyrics"] as? [[String: Any]] ?? []).map { layer in
            LyricsLayer(
                synced: layer["synced"] as? Bool ?? false,
                lines: (layer["line"] as? [[String: Any]] ?? []).compactMap { $0["value"] as? String }
            )
        }
    }
}
