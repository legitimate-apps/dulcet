import AppKit
import XCTest
@testable import DulcetKit
@testable import DulcetMac

/// The Mac's Download, where a person meets it: the button beside a track's row on its album page
/// (spec §14.5), through the production root view hosted in the app, over the production reader,
/// against the disposable server. The control-label proof records download transitions; the
/// offline row proof uses the real download controller and AVPlayer. The same store action's
/// background transfer across a killed process is `DulcetMacDownloadHandoffAppTest`'s.
@MainActor
final class DulcetMacDownloadRowAppTest: XCTestCase {
    private let username = "dulcet-admin"
    private let password = "dulcet-ci-canary-password"
    private let track = "UI Playback Canary"
    private let album = "Threshold Boundary"

    /// 1. the track's row on its album page has one Download button, drawn inside the window;
    /// 2. a click on it asks the device's downloads for that track, by the server's id;
    /// 3. as the downloads report the track downloading, failed and downloaded, the row's control
    ///    and the row's own accessibility value follow: Downloading, Retry Download (whose click
    ///    asks again), Downloaded.
    func aRowsDownloadButtonDownloadsTheTrackAndTheRowFollowsItThroughTheHostedMacApp() async throws {
        let server = try disposableServer()
        let songID = try await server.songID(track, album: album)
        let downloads = RecordingDownloads()
        let app = try await HostedApp(serverURL: server.baseURL, username: username, password: password,
                                      downloadController: downloads)
        defer { app.close() }

        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.section.albums")
        let tile = try await app.element(timeout: .seconds(30), described: "the \(album) tile") {
            app.identifier($0) == "dulcet.library.album" && (app.label($0) ?? "").hasPrefix(album)
        }
        try app.press(tile, named: "the \(album) tile")
        let row = try await app.element(timeout: .seconds(15), described: "the \(track) row") {
            app.identifier($0) == "dulcet.reader.track" && (app.label($0) ?? "").hasPrefix(self.track)
        }
        XCTAssertEqual(rowValue(row, in: app), "", "A track with no download says nothing about one")

        // 1. One Download button beside the row, inside the window.
        let button = try await control(labelled: "Download", besides: row, in: app)
        let frame = try app.frame(button)
        XCTAssertTrue(app.contentScreenFrame.contains(frame), "The Download button \(frame) must lie inside the window \(app.contentScreenFrame)")
        XCTAssertTrue(downloads.requested.isEmpty, "The control: nothing is asked for before the click")

        // 2. The click asks for this track.
        try app.click(atScreenPoint: NSPoint(x: frame.midX, y: frame.midY))
        try await waitUntil(timeout: .seconds(5), "The click must ask for \(songID); asked=\(downloads.requested)") {
            downloads.requested == [songID]
        }

        // 3. The row follows the download, distinguishing waiting from an active transfer.
        downloads.report(songID, .queued)
        try await waitUntil(timeout: .seconds(5), "A queued row must say Queued") {
            self.rowValue(row, in: app) == "Queued"
        }
        downloads.report(songID, .downloading)
        _ = try await control(labelled: "Downloading", besides: row, in: app)
        try await waitUntil(timeout: .seconds(5), "The row must read Downloading; value=\(rowValue(row, in: app))") {
            self.rowValue(row, in: app) == "Downloading"
        }
        downloads.report(songID, .failed)
        let retry = try await control(labelled: "Retry Download", besides: row, in: app)
        try await waitUntil(timeout: .seconds(5), "The row must read Download Failed; value=\(rowValue(row, in: app))") {
            self.rowValue(row, in: app) == "Download Failed"
        }
        let retryFrame = try app.frame(retry)
        try app.click(atScreenPoint: NSPoint(x: retryFrame.midX, y: retryFrame.midY))
        try await waitUntil(timeout: .seconds(5), "Retry Download must ask again; asked=\(downloads.requested)") {
            downloads.requested == [songID, songID]
        }
        downloads.report(songID, .downloaded)
        _ = try await control(labelled: "Downloaded", besides: row, in: app)
        try await waitUntil(timeout: .seconds(5), "The row must read Downloaded; value=\(rowValue(row, in: app))") {
            self.rowValue(row, in: app) == "Downloaded"
        }
        print("DULCET MAC DOWNLOAD ROW PROOF PASS track=\(track.debugDescription) asked=\(downloads.requested.count)"
            + " shown=Download,Downloading,Retry Download,Downloaded button=\(frame)")
    }

    /// An open offline album must become playable when its held download promotes, without
    /// reopening the page. The row starts real AVPlayer playback from the promoted bytes.
    func aPromotedDownloadPlaysOfflineFromTheAlreadyOpenReaderRow() async throws {
        let server = try disposableServer()
        let songID = try await server.songID(track, album: album)
        let run = UUID().uuidString
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("dulcet-row-\(run)")
        let downloads = DulcetCoreDownloadController(
            databaseName: "dulcet-mac-hosted-reader-\(run).db", downloadRootURL: root,
            sessionConfiguration: .ephemeral
        )
        let app = try await HostedApp(serverURL: server.baseURL, username: username, password: password,
                                      run: run, downloadController: downloads)
        defer { app.close(); downloads.disconnect(); try? FileManager.default.removeItem(at: root) }
        try await app.selectSidebarRow(identifiedBy: "dulcet.sidebar.section.albums")
        let tile = try await app.element(timeout: .seconds(30), described: "the album") {
            app.identifier($0) == "dulcet.library.album" && (app.label($0) ?? "").hasPrefix(self.album)
        }
        try app.press(tile, named: "album")
        let row = try await app.element(timeout: .seconds(15), described: "online track row") {
            app.identifier($0) == "dulcet.reader.track" && (app.label($0) ?? "").hasPrefix(self.track)
        }
        try await proxy(server.baseURL, path: "/__dulcet/rule?action=hold&state=on&endpoint=stream", method: "POST")
        defer { Task { try? await self.proxy(server.baseURL, path: "/__dulcet/rule?action=hold&state=off", method: "POST") } }
        let baseline = try await requestCount(server.baseURL)
        let button = try await control(labelled: "Download", besides: row, in: app)
        let frame = try app.frame(button)
        try app.click(atScreenPoint: NSPoint(x: frame.midX, y: frame.midY))
        let id = DulcetProviderItemID(providerInstanceID: "macos-hosted-\(run)", rawID: songID)
        try await waitUntil(timeout: .seconds(15), "Download must reach the held transfer") {
            downloads.status(for: id) == .downloading
        }
        app.enterDeviceOnlyMode()
        XCTAssertEqual(app.store.librarySession?.isOnline, false)
        _ = try await app.element(timeout: .seconds(10), described: "offline unavailable row") {
            app.identifier($0) == "dulcet.reader.track.unavailable" && (app.label($0) ?? "").hasPrefix(self.track)
        }
        // Reads the online grid already issued may still reach the proxy while cancellation
        // settles. Keep the transfer held and mark the device-only boundary after those reads.
        try await Task.sleep(for: .seconds(3))
        let offlineBaseline = try await requestCount(server.baseURL)
        try await proxy(server.baseURL, path: "/__dulcet/rule?action=hold&state=off", method: "POST")
        try await waitUntil(timeout: .seconds(30), "Real transfer must promote") {
            downloads.status(for: id) == .downloaded
        }
        downloads.closeNetworkAccessForTesting()
        let playable = try await app.element(timeout: .seconds(10), described: "promoted offline reader row") {
            app.identifier($0) == "dulcet.reader.track" && (app.label($0) ?? "").hasPrefix(self.track)
        }
        try app.press(playable, named: "promoted offline track")
        try await waitUntil(timeout: .seconds(15), "Offline playback must advance from the reader row") {
            guard let playing = app.store.snapshot.nowPlaying else { return false }
            return playing.current.id == id && playing.progressBegan && playing.elapsed > .zero
        }
        XCTAssertNotNil(app.store.snapshot.nowPlaying?.current.artwork.remoteReference,
                        "Control: the offline track must have artwork that connected playback would fetch")
        try await Task.sleep(for: .seconds(3))
        let count = try await requestCount(server.baseURL)
        XCTAssertEqual(count - offlineBaseline, 0, "Promotion and offline playback send no additional requests")
        let streams = try await streamRequests(server.baseURL, since: baseline, songID: songID)
        XCTAssertEqual(streams.count, 1, "The download must fetch the track exactly once, including after promotion")
        XCTAssertEqual(streams.first?["answered"] as? Bool, true)
        XCTAssertEqual(streams.first?["status"] as? Int, 200)
    }

    private func streamRequests(_ base: String, since: Int, songID: String) async throws -> [[String: Any]] {
        let (data, response) = try await URLSession.shared.data(from: try XCTUnwrap(URL(string: base + "/__dulcet/requests?since=\(since)")))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let requests = try XCTUnwrap(body["requests"] as? [[String: Any]])
        return requests.filter { $0["endpoint"] as? String == "stream" && $0["id"] as? String == songID }
    }

    private func proxy(_ base: String, path: String, method: String = "GET") async throws {
        var request = URLRequest(url: try XCTUnwrap(URL(string: base + path)))
        request.httpMethod = method
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }

    private func requestCount(_ base: String) async throws -> Int {
        let (data, response) = try await URLSession.shared.data(from: try XCTUnwrap(URL(string: base + "/__dulcet/requests")))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try XCTUnwrap(body["total"] as? Int)
    }

    // MARK: - Helpers

    /// The one download control drawn beside `row` with this label, waiting for it to appear.
    private func control(labelled label: String, besides row: Any, in app: HostedApp) async throws -> Any {
        let rowFrame = try app.frame(row)
        var found: [Any] = []
        func beside() -> String {
            app.windowElements { $0 is any NSAccessibilityElementProtocol && (try? app.frame($0)).map { $0.midY > rowFrame.minY && $0.midY < rowFrame.maxY } ?? false }
                .map { "\(type(of: $0)):\(app.identifier($0) ?? "-")=\(app.label($0) ?? "-")" }
                .joined(separator: "; ")
        }
        try await waitUntil(timeout: .seconds(5), "One \(label) control must sit beside \(track)'s row; found \(found.count)"
            + " downloads=\(app.store.downloadsEnabled); beside: \(beside())") {
            // By what it says, not its identifier: the row's own identifier reaches its children.
            found = app.windowElements { $0 is any NSAccessibilityElementProtocol && app.label($0) == label }
                .filter { (try? app.frame($0)).map { $0.midY > rowFrame.minY && $0.midY < rowFrame.maxY } ?? false }
            return found.count == 1
        }
        return found[0]
    }

    private func rowValue(_ row: Any, in app: HostedApp) -> String {
        let value = app.value("accessibilityValue", of: row)
        return value as? String ?? (value as? NSAttributedString)?.string ?? ""
    }

    private func disposableServer() throws -> LiveServer {
        let environment = ProcessInfo.processInfo.environment
        let baseURL = try XCTUnwrap(environment["DULCET_CONFORMANCE_BASE_URL"], "The disposable server URL must be supplied")
        guard let components = URLComponents(string: baseURL), components.scheme == "http",
              components.host == "127.0.0.1" || components.host == "localhost",
              environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true" else {
            XCTFail("Refused: \(baseURL.debugDescription) is not a disposable loopback server")
            throw HostedAppError.refused
        }
        return LiveServer(baseURL: baseURL, username: username, password: password)
    }
}

/// The device's downloads, recorded: what was asked for, and the state each track reports.
@MainActor
private final class RecordingDownloads: DulcetDownloadControlling {
    private(set) var requested: [String] = []
    private var states: [String: DulcetDownloadState] = [:]
    private var statusHandler: (@MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void)?
    private var ids: [String: DulcetProviderItemID] = [:]

    var downloadsEnabled: Bool { true }

    func setStatusHandler(_ handler: @escaping @MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void) {
        statusHandler = handler
    }

    func configure(account: DulcetPlaybackAccount) {}

    func requestDownload(_ track: DulcetTrack) {
        requested.append(track.id.rawID)
        ids[track.id.rawID] = track.id
    }

    func status(for id: DulcetProviderItemID) -> DulcetDownloadState {
        states[id.rawID] ?? .notDownloaded
    }

    func offlinePlaybackAsset(for track: DulcetTrack) -> DulcetOfflinePlaybackAsset? { nil }

    func removeAccountData() async -> Bool { true }

    func disconnect() {}

    /// What the background session would report as the track's download moves.
    func report(_ rawID: String, _ state: DulcetDownloadState) {
        states[rawID] = state
        if let id = ids[rawID] { statusHandler?(id, state) }
    }
}
