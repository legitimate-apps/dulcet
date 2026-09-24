import CryptoKit
import DulcetCore
import Foundation
import MediaPlayer
import XCTest
@testable import DulcetKit
#if os(iOS)
@testable import Dulcet_DEV
import UIKit

/// The production playback system inside the real app process, against the local disposable
/// server: the production controller and AVFoundation engine, the production artwork fetcher, and
/// the process-wide MPNowPlayingInfoCenter the lock screen and Control Center read.
///
/// The server is read back over REST (read-only calls) for play counts; the app is the only
/// actor that scrobbles.
@MainActor
final class DulcetPlaybackSystemAppTests: XCTestCase {
    private let username = "dulcet-admin"
    private let password = "dulcet-ci-canary-password"

    /// The built bundle declares background audio. Without it iOS suspends playback when the
    /// screen locks; the simulator does not enforce the mode, so this is the part CI can check.
    func testTheAppDeclaresTheAudioBackgroundMode() throws {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.legitimateapps.dulcet.ios.dev",
                       "this must read the APP's Info.plist, not the test bundle's")
        XCTAssertNotNil(Bundle.main.object(forInfoDictionaryKey: "UILaunchScreen"),
                        "positive control: a key the generated plist is known to carry")
        let modes = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String])
        XCTAssertEqual(modes, ["audio"])
    }

    /// Two eligible tracks of one album, played through a gapless boundary:
    ///  - the second track is PRELOADED and the engine advances into it (process marker),
    ///  - each track is scrobbled exactly once on the server,
    ///  - the system Now Playing entry carries each title and decodable artwork.
    func testGaplessAdvanceScrobblesEachTrackOnceAndPublishesNowPlayingWithArtwork() async throws {
        let server = try disposableServer()
        let album = try await server.album(named: "Threshold Boundary")
        // Track 1 is 29 s (ineligible); 2 and 3 are 31 s each and adjacent.
        let ordered = album.songs.sorted { $0.track < $1.track }
        XCTAssertEqual(ordered.map(\.title), ["Twenty Nine Seconds", "Thirty One Seconds", "UI Playback Canary"],
                       "fixture identity: the experiment needs this exact album")
        let first = ordered[1]
        let second = ordered[2]
        let before = try await (server.playCount(first.id), server.playCount(second.id))

        let provider = "playback-system-\(UUID().uuidString)"
        let tracks = ordered.map { $0.dulcetTrack(provider: provider) }
        let controller = DulcetCorePlaybackController(
            databaseName: "playback-system-\(UUID().uuidString).db",
            artworkFetcher: DulcetCoreArtworkFetcher()
        )
        var reports: [DulcetScrobbleDeliveryReport] = []
        controller.setScrobbleDeliveryHandler { reports.append($0) }
        controller.configure(account: DulcetPlaybackAccount(
            providerInstanceID: provider,
            normalizedServerURL: server.baseURL,
            username: username,
            password: password,
            allowLocalHTTP: true
        ))
        defer { controller.disconnect() }

        controller.replaceQueueAndPlay(DulcetPlaybackQueueIntent(
            tracks: tracks,
            sourceKind: .album,
            sourceID: DulcetProviderItemID(providerInstanceID: provider, rawID: album.id),
            sourceDisplayName: album.name,
            startIndex: 1,
            shuffle: false
        ))

        let center = MPNowPlayingInfoCenter.default()
        try await waitUntil("the first track never reached the system Now Playing entry", seconds: 45) {
            center.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == first.title
        }
        try await waitUntil("the first track's artwork never reached the system entry", seconds: 20) {
            (center.nowPlayingInfo?[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork) != nil
        }
        try assertDecodableArtwork(center, "first")
        XCTAssertEqual(center.nowPlayingInfo?[MPMediaItemPropertyAlbumTitle] as? String, album.name)
        XCTAssertEqual(center.playbackState, .playing)

        try await waitUntil("the second track was never handed to the engine as a preload", seconds: 30) {
            controller.preloadLog.contains("in-engine")
        }
        try await waitUntil("the engine never advanced into the preloaded track", seconds: 60) {
            controller.preloadLog.contains("advanced")
        }
        try await waitUntil("the second track never reached the system Now Playing entry", seconds: 10) {
            center.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String == second.title
        }
        try await waitUntil("the second track's artwork never reached the system entry", seconds: 20) {
            (center.nowPlayingInfo?[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork) != nil
        }
        try assertDecodableArtwork(center, "second")
        XCTAssertEqual(controller.currentPresentation.nowPlaying?.current.title, second.title)

        // Delivery, not the displayed position, is what proves a play reached the server.
        try await waitUntil("both plays were not delivered", seconds: 60) {
            (reports.last?.submittedPlaysDelivered ?? 0) >= 2
        }
        let delivered = try XCTUnwrap(reports.last)
        XCTAssertEqual(delivered.submittedPlaysDelivered, 2)
        XCTAssertEqual(delivered.submittedPlayFailedAttempts, 0)

        let preloadEvents = controller.preloadLog
        XCTAssertEqual(preloadEvents.filter { $0 == "advanced" }.count, 1)
        XCTAssertFalse(preloadEvents.contains { $0.hasPrefix("discarded") },
                       "the boundary must be the engine's advance, not a fallback: \(preloadEvents)")

        // Exactly once each, and still once a little later (a duplicate would land by then).
        try await Task.sleep(for: .seconds(3))
        let after = try await (server.playCount(first.id), server.playCount(second.id))
        XCTAssertEqual(after.0, before.0 + 1, "\(first.title) must be scrobbled exactly once")
        XCTAssertEqual(after.1, before.1 + 1, "\(second.title) must be scrobbled exactly once")
        print("PLAYBACK-SYSTEM-EVIDENCE preload=\(preloadEvents) playCounts=\(before)->\(after) delivered=\(delivered.submittedPlaysDelivered)")
    }

    /// Queue editing through the production store route, persisted in the production queue
    /// database, and read back by a second client on the same database -- what a relaunch reads.
    func testQueueEditsThroughTheStorePersistForTheNextLaunch() async throws {
        let server = try disposableServer()
        let album = try await server.album(named: "Threshold Boundary")
        let provider = "playback-edit-\(UUID().uuidString)"
        let tracks = album.songs.sorted { $0.track < $1.track }.map { $0.dulcetTrack(provider: provider) }
        let databaseName = "playback-edit-\(UUID().uuidString).db"
        let controller = DulcetCorePlaybackController(databaseName: databaseName)
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: PlaybackSystemUnusedConnector(), playbackController: controller
        ))
        controller.configure(account: DulcetPlaybackAccount(
            providerInstanceID: provider,
            normalizedServerURL: server.baseURL,
            username: username,
            password: password,
            allowLocalHTTP: true
        ))
        defer { controller.disconnect() }
        controller.replaceQueueAndPlay(DulcetPlaybackQueueIntent(
            tracks: tracks, sourceKind: .album,
            sourceID: DulcetProviderItemID(providerInstanceID: provider, rawID: album.id),
            sourceDisplayName: album.name, startIndex: 0, shuffle: false
        ))
        try await waitUntil("playback never became presentable", seconds: 45) {
            controller.currentPresentation.nowPlaying?.queueEntries.count == 3
        }
        let entries = try XCTUnwrap(controller.currentPresentation.nowPlaying?.queueEntries)

        store.editQueue(.move(entries[2].id, toIndex: 1))
        store.editQueue(.playLater(DulcetQueueAddition(
            tracks: [tracks[1]], sourceKind: .search, sourceID: nil, sourceDisplayName: "Search"
        )))
        store.editQueue(.remove(entries[1].id))
        try await waitUntil("the edits never reached the presentation", seconds: 5) {
            controller.currentPresentation.nowPlaying?.queueEntries.map(\.track.title)
                == ["Twenty Nine Seconds", "UI Playback Canary", "Thirty One Seconds"]
        }

        let reader = ApplePlaybackQueueClient(databaseName: databaseName)
        defer { reader.close() }
        let persisted = try XCTUnwrap(reader.snapshot().snapshot)
        XCTAssertEqual(persisted.entries.map(\.rawId), [tracks[0], tracks[2], tracks[1]].map(\.id.rawID))
        XCTAssertEqual(persisted.entries[0].queueEntryId, entries[0].id.rawValue, "identities survive")
        XCTAssertEqual(persisted.entries[1].queueEntryId, entries[2].id.rawValue)
        XCTAssertNotEqual(persisted.entries[2].queueEntryId, entries[1].id.rawValue,
                          "the re-added track is a new entry, not the removed one")
        XCTAssertEqual(persisted.currentIndex, 0)
    }

    // MARK: Helpers

    private func assertDecodableArtwork(_ center: MPNowPlayingInfoCenter, _ label: String) throws {
        let artwork = try XCTUnwrap(center.nowPlayingInfo?[MPMediaItemPropertyArtwork] as? MPMediaItemArtwork)
        XCTAssertGreaterThan(artwork.bounds.width, 0)
        let image = try XCTUnwrap(artwork.image(at: CGSize(width: 256, height: 256)),
                                  "\(label) artwork must render an image")
        XCTAssertGreaterThan(image.size.width, 0)
        XCTAssertFalse((center.nowPlayingInfo ?? [:]).values.contains { $0 is URL || $0 is NSURL },
                       "no URL may reach the system entry")
    }

    private func disposableServer() throws -> DisposableServer {
        let environment = ProcessInfo.processInfo.environment
        let baseURL = try XCTUnwrap(environment["DULCET_CONFORMANCE_BASE_URL"],
                                    "supply the local disposable server as TEST_RUNNER_DULCET_CONFORMANCE_BASE_URL")
        guard environment["DULCET_CONFORMANCE_DISPOSABLE"] == "true",
              let host = URL(string: baseURL)?.host, host == "127.0.0.1" else {
            XCTFail("playback-system evidence writes play counts: local disposable server only")
            throw PlaybackSystemTestError.notDisposable
        }
        return DisposableServer(baseURL: baseURL, username: username, password: password)
    }

    private func waitUntil(
        _ failure: String,
        seconds: Int,
        _ condition: @MainActor () -> Bool
    ) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(seconds))
        var polls = 0
        while !condition() {
            polls += 1
            guard ContinuousClock.now < deadline else {
                XCTFail("\(failure) (polls=\(polls), budget=\(seconds)s)")
                throw PlaybackSystemTestError.timedOut
            }
            try await Task.sleep(for: .milliseconds(100))
        }
    }
}

private enum PlaybackSystemTestError: Error {
    case notDisposable
    case timedOut
    case server(String)
}

private struct DisposableSong {
    let id: String
    let title: String
    let track: Int
    let duration: Int
    let suffix: String
    let coverArt: String?
    let album: String

    func dulcetTrack(provider: String) -> DulcetTrack {
        DulcetTrack(
            id: DulcetProviderItemID(providerInstanceID: provider, rawID: id),
            title: title,
            credits: [DulcetCredit(role: .artist, name: "Dulcet Fixture", id: nil)],
            albumTitle: album,
            trackNumber: track,
            duration: .seconds(duration),
            sourceContainer: suffix == "mp3" ? .mp3 : .flac,
            mediaSourceID: nil,
            artwork: DulcetArtwork(
                seed: id,
                palette: .indigoCoral,
                remoteReference: coverArt.map { DulcetArtworkReference(serverID: provider, artworkKey: $0) }
            )
        )
    }
}

private struct DisposableAlbum {
    let id: String
    let name: String
    let songs: [DisposableSong]
}

private struct DisposableServer {
    let baseURL: String
    let username: String
    let password: String

    func album(named name: String) async throws -> DisposableAlbum {
        let list = try await call("getAlbumList2", ["type": "alphabeticalByName", "size": "100"])
        let albums = ((list["albumList2"] as? [String: Any])?["album"] as? [[String: Any]]) ?? []
        guard let id = albums.first(where: { $0["name"] as? String == name })?["id"] as? String else {
            throw PlaybackSystemTestError.server("album \(name) missing")
        }
        let album = try await call("getAlbum", ["id": id])
        let songs = ((album["album"] as? [String: Any])?["song"] as? [[String: Any]]) ?? []
        return DisposableAlbum(id: id, name: name, songs: songs.compactMap { song in
            guard let songID = song["id"] as? String, let title = song["title"] as? String,
                  let duration = song["duration"] as? Int else { return nil }
            return DisposableSong(
                id: songID, title: title, track: song["track"] as? Int ?? 0, duration: duration,
                suffix: song["suffix"] as? String ?? "mp3", coverArt: song["coverArt"] as? String,
                album: name
            )
        })
    }

    func playCount(_ songID: String) async throws -> Int {
        let response = try await call("getSong", ["id": songID])
        return ((response["song"] as? [String: Any])?["playCount"] as? Int) ?? 0
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
        guard let response = object?["subsonic-response"] as? [String: Any],
              response["status"] as? String == "ok" else {
            throw PlaybackSystemTestError.server("\(endpoint) failed")
        }
        return response
    }
}

@MainActor
private final class PlaybackSystemUnusedConnector: DulcetAccountConnecting {
    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        fatalError("Playback system app tests must not connect through the account flow")
    }
}
#endif
