import AppKit
import XCTest
@testable import DulcetKit
@testable import DulcetMac

/// The Mac's Download, where a person meets it: the button beside a track's row on its album page
/// (spec §14.5), through the production root view hosted in the app, over the production reader,
/// against the disposable server. The device's downloads are a recorder standing in for the
/// background session, so the proof observes what the row asks for and what it shows as the
/// download moves; that the same store action downloads through the real background session,
/// across a killed process, is `DulcetMacDownloadHandoffAppTest`'s.
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

        // 3. The row follows the download.
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
