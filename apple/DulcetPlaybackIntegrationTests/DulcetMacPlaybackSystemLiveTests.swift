import CryptoKit
import DulcetCore
import DulcetKit
import MediaPlayer
import XCTest

/// macOS, the real AVFoundation engine, the production controller, the local disposable server.
///
/// This is the macOS answer to "Preparing playback... while tracks advance": it records EVERY
/// presentation the controller publishes, with the time, across a whole album tail -- start,
/// gapless boundary, and the end of the queue -- and fails on any spinner or empty screen after
/// the first track is ready.
@MainActor
final class DulcetMacPlaybackSystemLiveTests: XCTestCase {
    private let username = "dulcet-admin"
    private let password = "dulcet-ci-canary-password"

    func testAnAlbumTailAdvancesWithoutPreparingAndEndsOnTheLastTrackStopped() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let baseURL = environment["DULCET_CONFORMANCE_BASE_URL"],
              environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true",
              URL(string: baseURL)?.host == "127.0.0.1" else {
            XCTFail("supply a LOCAL disposable server as TEST_RUNNER_DULCET_CONFORMANCE_BASE_URL")
            return
        }
        let server = LiveServer(baseURL: baseURL, username: username, password: password)
        let songs = try await server.albumSongs(named: "Threshold Boundary")
        XCTAssertEqual(songs.map(\.title), ["Twenty Nine Seconds", "Thirty One Seconds", "UI Playback Canary"],
                       "fixture identity")
        let before = try await (server.playCount(songs[1].id), server.playCount(songs[2].id))

        let provider = "mac-live-\(UUID().uuidString)"
        let tracks = songs.map { song in
            DulcetTrack(
                id: DulcetProviderItemID(providerInstanceID: provider, rawID: song.id),
                title: song.title,
                credits: [],
                albumTitle: "Threshold Boundary",
                duration: .seconds(song.duration),
                sourceContainer: .flac,
                mediaSourceID: nil,
                artwork: DulcetArtwork(seed: song.id, palette: .indigoCoral)
            )
        }
        let databaseName = "mac-live-\(UUID().uuidString).db"
        removeDatabaseAfterTest(databaseName)
        let controller = DulcetCorePlaybackController(databaseName: databaseName)
        let clock = ContinuousClock()
        let started = clock.now
        var timeline: [(seconds: Double, status: DulcetPlaybackSurfaceStatus, title: String?, session: String?)] = []
        controller.setPresentationHandler { presentation in
            let elapsed = started.duration(to: clock.now)
            timeline.append((
                Double(elapsed.components.seconds) + Double(elapsed.components.attoseconds) / 1e18,
                presentation.status,
                presentation.nowPlaying?.current.title,
                presentation.nowPlaying?.sessionID?.rawValue
            ))
        }
        var reports: [DulcetScrobbleDeliveryReport] = []
        controller.setScrobbleDeliveryHandler { reports.append($0) }
        controller.configure(account: DulcetPlaybackAccount(
            providerInstanceID: provider, normalizedServerURL: baseURL,
            username: username, password: password, allowLocalHTTP: true
        ))
        defer { controller.disconnect() }

        controller.replaceQueueAndPlay(DulcetPlaybackQueueIntent(
            tracks: tracks, sourceKind: .album,
            sourceID: DulcetProviderItemID(providerInstanceID: provider, rawID: "album"),
            sourceDisplayName: "Threshold Boundary", startIndex: 1, shuffle: false
        ))

        // The whole tail: two 31 s tracks and the end of the queue.
        let deadline = clock.now.advanced(by: .seconds(120))
        while clock.now < deadline {
            let presented = controller.currentPresentation.nowPlaying
            if presented?.current.title == "UI Playback Canary", presented?.sessionID == nil { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let summary = timeline.map { String(format: "%.1fs %@ %@", $0.seconds, "\($0.status)", $0.title ?? "-") }
        print("MAC-PLAYBACK-TIMELINE \(summary.joined(separator: " | "))")
        print("MAC-PRELOAD-LOG \(controller.preloadLog)")

        let firstReady = try XCTUnwrap(timeline.firstIndex { $0.status == .ready },
                                       "playback never became presentable")
        print(String(format: "MAC-TIME-TO-FIRST-READY %.2fs", timeline[firstReady].seconds))
        let afterReady = timeline[firstReady...]
        XCTAssertFalse(afterReady.contains { $0.status == .preparing },
                       "no 'Preparing playback' once the first track is ready: \(summary)")
        XCTAssertFalse(afterReady.contains { $0.status == .unavailable },
                       "no 'Nothing is playing' between or after the tracks: \(summary)")
        XCTAssertFalse(afterReady.contains { $0.status == .failed }, "\(summary)")
        XCTAssertEqual(controller.preloadLog.filter { $0 == "advanced" }.count, 1,
                       "the boundary must be a gapless advance: \(controller.preloadLog)")

        let final = try XCTUnwrap(controller.currentPresentation.nowPlaying, "the queue end must keep a track")
        XCTAssertEqual(final.current.title, "UI Playback Canary")
        XCTAssertNil(final.sessionID)
        XCTAssertFalse(final.isPlaying)
        XCTAssertEqual(final.elapsed, .zero)

        try await waitForDelivered(2, in: { reports.last })
        try await Task.sleep(for: .seconds(2))
        let after = try await (server.playCount(songs[1].id), server.playCount(songs[2].id))
        XCTAssertEqual(after.0, before.0 + 1, "exactly one play of the first track")
        XCTAssertEqual(after.1, before.1 + 1, "exactly one play of the second track")
        print("MAC-PLAY-COUNTS \(before) -> \(after)")
    }

    /// The audit's macOS observation: a queue of near-empty items ("Paging Atlas", ~0.3 s each)
    /// sat on "Preparing playback..." for 40+ s while the server showed tracks advancing. Items
    /// that end before their successor can be preloaded fall back to fresh starts, so the
    /// in-between is honestly "preparing" item after item; what must NOT happen is a spinner
    /// that outlives the queue. This drives twelve such items and requires the queue to end on
    /// the last item, stopped, with nothing preparing.
    func testAQueueOfNearEmptyItemsEndsOnItsLastItemNotOnASpinner() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let baseURL = environment["DULCET_CONFORMANCE_BASE_URL"],
              environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true",
              URL(string: baseURL)?.host == "127.0.0.1" else {
            XCTFail("supply a LOCAL disposable server as TEST_RUNNER_DULCET_CONFORMANCE_BASE_URL")
            return
        }
        let server = LiveServer(baseURL: baseURL, username: username, password: password)
        let songs = Array(try await server.albumSongs(named: "Paging Atlas").prefix(12))
        XCTAssertEqual(songs.count, 12, "fixture identity: Paging Atlas carries 300 near-empty items")
        let provider = "mac-paging-\(UUID().uuidString)"
        let tracks = songs.map { song in
            DulcetTrack(
                id: DulcetProviderItemID(providerInstanceID: provider, rawID: song.id),
                title: song.title, credits: [], albumTitle: "Paging Atlas",
                duration: .seconds(max(song.duration, 1)), sourceContainer: .mp3,
                mediaSourceID: nil, artwork: DulcetArtwork(seed: song.id, palette: .indigoCoral)
            )
        }
        let databaseName = "mac-paging-\(UUID().uuidString).db"
        removeDatabaseAfterTest(databaseName)
        let controller = DulcetCorePlaybackController(databaseName: databaseName)
        var statuses: [DulcetPlaybackSurfaceStatus] = []
        controller.setPresentationHandler { statuses.append($0.status) }
        controller.configure(account: DulcetPlaybackAccount(
            providerInstanceID: provider, normalizedServerURL: baseURL,
            username: username, password: password, allowLocalHTTP: true
        ))
        defer { controller.disconnect() }
        let clock = ContinuousClock()
        let started = clock.now
        controller.replaceQueueAndPlay(DulcetPlaybackQueueIntent(
            tracks: tracks, sourceKind: .album,
            sourceID: DulcetProviderItemID(providerInstanceID: provider, rawID: "paging"),
            sourceDisplayName: "Paging Atlas", startIndex: 0, shuffle: false
        ))
        let deadline = clock.now.advanced(by: .seconds(60))
        while clock.now < deadline {
            let presented = controller.currentPresentation
            if presented.nowPlaying?.current.title == songs.last?.title, presented.nowPlaying?.sessionID == nil {
                break
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        let elapsed = started.duration(to: clock.now)
        let log = controller.preloadLog
        print("MAC-PAGING elapsed=\(elapsed) publications=\(statuses.count) preparing=\(statuses.filter { $0 == .preparing }.count) ready=\(statuses.filter { $0 == .ready }.count) unavailable=\(statuses.filter { $0 == .unavailable }.count) failed=\(statuses.filter { $0 == .failed }.count) advanced=\(log.filter { $0 == "advanced" }.count) discarded=\(log.filter { $0.hasPrefix("discarded") }.count) log=\(log)")
        let final = controller.currentPresentation
        XCTAssertEqual(final.status, .ready, "the queue must end on a defined state, not a spinner")
        XCTAssertEqual(final.nowPlaying?.current.title, songs.last?.title)
        XCTAssertNil(final.nowPlaying?.sessionID)
        // Settle: nothing may republish a spinner after the end.
        try await Task.sleep(for: .seconds(2))
        XCTAssertEqual(controller.currentPresentation.status, .ready)
        XCTAssertNotEqual(statuses.last, .preparing)
    }

    /// The native SQLite driver writes a named database under Application Support; even
    /// `":memory:"` becomes a persistent file there. Remove this test's own files, by its unique
    /// name, so a run leaves nothing behind for the next.
    private func removeDatabaseAfterTest(_ name: String) {
        addTeardownBlock {
            let directory = FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Library/Application Support/databases")
            for suffix in ["", "-wal", "-shm"] {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(name + suffix))
            }
        }
    }

    private func waitForDelivered(
        _ count: Int,
        in report: @MainActor () -> DulcetScrobbleDeliveryReport?
    ) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        while (report()?.submittedPlaysDelivered ?? 0) < count, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(report()?.submittedPlaysDelivered, count)
        XCTAssertEqual(report()?.submittedPlayFailedAttempts, 0)
    }
}

private struct LiveSong {
    let id: String
    let title: String
    let duration: Int
}

private struct LiveServer {
    let baseURL: String
    let username: String
    let password: String

    func albumSongs(named name: String) async throws -> [LiveSong] {
        let list = try await call("getAlbumList2", ["type": "alphabeticalByName", "size": "100"])
        let albums = ((list["albumList2"] as? [String: Any])?["album"] as? [[String: Any]]) ?? []
        let id = try XCTUnwrap(albums.first { $0["name"] as? String == name }?["id"] as? String)
        let album = try await call("getAlbum", ["id": id])
        let songs = ((album["album"] as? [String: Any])?["song"] as? [[String: Any]]) ?? []
        return songs.sorted { ($0["track"] as? Int ?? 0) < ($1["track"] as? Int ?? 0) }.compactMap {
            // Near-empty fixture files carry no duration at all; absent reads as zero.
            guard let id = $0["id"] as? String, let title = $0["title"] as? String else { return nil }
            return LiveSong(id: id, title: title, duration: $0["duration"] as? Int ?? 0)
        }
    }

    func playCount(_ id: String) async throws -> Int {
        ((try await call("getSong", ["id": id])["song"] as? [String: Any])?["playCount"] as? Int) ?? 0
    }

    private func call(_ endpoint: String, _ parameters: [String: String]) async throws -> [String: Any] {
        let salt = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let token = Insecure.MD5.hash(data: Data((password + salt).utf8))
            .map { String(format: "%02x", $0) }.joined()
        var components = try XCTUnwrap(URLComponents(string: "\(baseURL)/rest/\(endpoint)"))
        components.queryItems = [
            .init(name: "u", value: username), .init(name: "t", value: token),
            .init(name: "s", value: salt), .init(name: "v", value: "1.16.1"),
            .init(name: "c", value: "dulcet-evidence-probe"), .init(name: "f", value: "json"),
        ] + parameters.map { .init(name: $0.key, value: $0.value) }
        let (data, _) = try await URLSession.shared.data(from: try XCTUnwrap(components.url))
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return try XCTUnwrap(object?["subsonic-response"] as? [String: Any])
    }
}
