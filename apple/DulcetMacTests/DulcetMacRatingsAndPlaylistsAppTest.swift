import AppKit
import DulcetCore
import SwiftUI
import XCTest
@testable import DulcetKit
@testable import DulcetMac

/// The Mac's rating control and playlist editing, driven through the production root view hosted
/// in the app, over the production controller and library reader, against the disposable server
/// (spec §16.20, §18.6; CONF-84, CONF-88..91). The account is connected through the presentation
/// store, as the other hosted proofs connect it; everything after that goes through the rendered
/// views. A macOS host test does not inherit xcodebuild's environment, so the server arrives
/// through the DulcetMac scheme, from `DULCET_TEST_*` build settings.
@MainActor
final class DulcetMacRatingsAndPlaylistsAppTest: XCTestCase {
    private let username = "dulcet-admin"
    private let password = "dulcet-ci-canary-password"
    /// The track the rating proof rates: the first of "Threshold Boundary".
    private let track = "Twenty Nine Seconds"

    /// The five stars the Mac draws beside the heart in the window's toolbar, clicked where each
    /// star is drawn:
    ///
    /// 1. a click on star N shows N at once, before the server answers, and the server's
    ///    `userRating` reads N back;
    /// 2. another client's rating is written to the server directly, the app is reopened over
    ///    the same reader database and account, and the stars show the server's value -- so what
    ///    they show is read from the server, not remembered from the click;
    /// 3. a click on the star shown removes the rating, and the server reads back 0.
    ///
    /// Each star is clicked at the centre of its own button by a mouse event the window
    /// hit-tests, and what the stars show is read from the group's label, checked against the
    /// star buttons' own labels.
    /// Each value is chosen against what the server holds first, so no earlier run's rating can
    /// make a write unobservable, and the server's rating is put back afterwards.
    func nowPlayingStarsRateTheTrackOnTheServerThroughTheHostedMacApp() async throws {
        let server = try disposableServer()
        let album = "Threshold Boundary"
        let songID = try await server.songID(track, album: album)
        let before = try await server.rating(songID)
        addTeardownBlock { try? await server.setRating(songID, before) }
        let rating = before == 3 ? 2 : 3
        let elsewhere = rating == 4 ? 5 : 4
        let run = UUID().uuidString

        // 1. A click rates the track: shown at once, then saved on the server.
        let app = try await HostedApp(serverURL: server.baseURL, username: username, password: password, run: run, toolbar: true)
        try await app.play(query: "Twenty Nine", track: track)
        _ = try await app.windowElement(identifiedBy: "dulcet.now-playing.rating", timeout: .seconds(10))
        try await waitForShown(before, in: app, timeout: .seconds(15))
        try await clickStar(rating, in: app)
        try await waitForShown(rating, in: app, timeout: .seconds(3), allowingPending: true)
        let saved = try await awaitRating(rating, of: songID, on: server)
        XCTAssertEqual(saved, rating, "The click on star \(rating) must rate \(track) \(rating) on the server (was \(before))")
        try await waitForShown(rating, in: app, timeout: .seconds(15))
        XCTAssertEqual(app.store.snapshot.nowPlaying?.current.title, track, "Rating must not change what is playing")
        app.close()

        // 2. Re-read: another client's rating is what the reopened app shows.
        try await server.setRating(songID, elsewhere)
        let written = try await server.rating(songID)
        XCTAssertEqual(written, elsewhere, "The other client's rating must be on the server before the reopen")
        let reopened = try await HostedApp(serverURL: server.baseURL, username: username, password: password, run: run, toolbar: true)
        defer { reopened.close() }
        try await reopened.play(query: "Twenty Nine", track: track)
        let reread = try await reopened.windowElement(identifiedBy: "dulcet.now-playing.rating", timeout: .seconds(10))
        try await waitForShown(elsewhere, in: reopened, timeout: .seconds(30))

        // 3. A click on the star shown removes the rating.
        try await clickStar(elsewhere, in: reopened)
        try await waitForShown(0, in: reopened, timeout: .seconds(3), allowingPending: true)
        let removed = try await awaitRating(0, of: songID, on: server)
        XCTAssertEqual(removed, 0, "The click on the star shown must remove the rating on the server")
        print("DULCET MAC STARS PROOF PASS track=\(track.debugDescription) server-before=\(before) clicked=\(rating)"
            + " other-client=\(elsewhere) shown-after-reopen=\(elsewhere) removed=0 frame=\(try reopened.frame(reread))")
    }

    // MARK: - Helpers

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

    /// What the stars show, as the group's label says it after the track's name -- "3 stars",
    /// "Not rated", "Rating unknown", with ", Waiting to send" while pending -- checked against
    /// the five star buttons, of which only the N-th offers to remove the rating. "unreadable"
    /// when the two disagree.
    private func shownRating(in app: HostedApp) -> String {
        let prefix = "Rating for \(track), "
        guard let group = app.windowFirstElement(where: { app.identifier($0) == "dulcet.now-playing.rating" }),
              let label = app.label(group), label.hasPrefix(prefix) else { return "absent" }
        let said = String(label.dropFirst(prefix.count))
        let labels = (1...5).map { star in
            app.windowFirstElement(where: { app.identifier($0) == "dulcet.now-playing.rating.star.\(star)" }).flatMap(app.label) ?? "absent"
        }
        let removable = labels.indices.filter { labels[$0] == "Remove Rating" }.map { $0 + 1 }
        let rated = Int(said.split(separator: " ").first ?? "") ?? 0
        let consistent = removable == (rated == 0 ? [] : [rated])
            && labels.enumerated().allSatisfy { index, label in
                label == "Remove Rating" || label == (index == 0 ? "Rate 1 star" : "Rate \(index + 1) stars")
            }
        return consistent ? said : "unreadable said=\(said.debugDescription) stars=\(labels)"
    }

    /// Waits until the stars show `rating`, settled -- or, with `allowingPending`, with a pending
    /// or held change after it.
    private func waitForShown(_ rating: Int, in app: HostedApp, timeout: Duration, allowingPending: Bool = false) async throws {
        let expected = Self.ratingValue(rating)
        try await waitUntil(timeout: timeout, "the stars must show \(expected.debugDescription); show \(shownRating(in: app))") {
            let shown = self.shownRating(in: app)
            return shown == expected || (allowingPending && shown.hasPrefix(expected + ", "))
        }
    }

    /// What the stars say their value is, settled: "Not rated", "1 star", "3 stars".
    private static func ratingValue(_ rating: Int) -> String {
        switch rating {
        case 0: "Not rated"
        case 1: "1 star"
        default: "\(rating) stars"
        }
    }

    /// Clicks star `star` where it is drawn: a mouse click at the centre of that star's own
    /// button, which the window hit-tests.
    private func clickStar(_ star: Int, in app: HostedApp) async throws {
        let button = try await app.windowElement(identifiedBy: "dulcet.now-playing.rating.star.\(star)", timeout: .seconds(5))
        let frame = try app.frame(button)
        XCTAssertGreaterThan(frame.width, 0, "Star \(star) must be drawn; frame=\(frame)")
        try app.click(atScreenPoint: NSPoint(x: frame.midX, y: frame.midY))
    }

    private func awaitRating(_ expected: Int, of songID: String, on server: LiveServer) async throws -> Int {
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        var observed = try await server.rating(songID)
        while observed != expected, ContinuousClock.now < deadline {
            try await Task.sleep(for: .seconds(1))
            observed = try await server.rating(songID)
        }
        return observed
    }
}
