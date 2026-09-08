import DulcetCore
import DulcetKit
import Foundation

// Compile with the production Swift controller and Kotlin framework; see
// tools/check-playback-restoration.py. Each case owns a new synthetic database.
@main struct PlaybackRestorationCheck {
    @MainActor static func main() {
        var failures = 0
        func check(_ condition: Bool, _ message: String) {
            if !condition { failures += 1; print("FAIL: \(message)") }
        }
        for available in [["a", "c"], []] as [[String]] {
            let name = "dulcet-restoration-check-\(UUID().uuidString).db"
            let seed = ApplePlaybackQueueClient(databaseName: name)
            let started = seed.replaceAndStart(request: ApplePlaybackQueueRequestDto(
                items: ["a", "missing", "c"].map {
                    ApplePlaybackQueueItemDto(providerInstanceId: "fixture", rawId: $0,
                                              durationMilliseconds: 30_000)
                },
                sourceKind: "library", sourceRawId: nil, sourceDisplayName: "Fixture",
                startIndex: 1, shuffle: false
            ))
            check(started.errorKind == nil, "seed succeeded")
            check(started.startDirective?.rawId == "missing", "missing item was selected")
            let retainedIDs = started.snapshot?.entries.filter { available.contains($0.rawId) }
                .map(\.queueEntryId)
            seed.close()
            // A new controller, then a second launch, must both recover to idle.
            for launch in 1...2 {
                let controller = DulcetCorePlaybackController(databaseName: name)
                let store = DulcetPresentationStore(source: DulcetAccountDataSource(
                    connector: RestorationConnector(), playbackController: controller
                ))
                controller.configure(account: DulcetPlaybackAccount(
                    providerInstanceID: "fixture", normalizedServerURL: "http://127.0.0.1:1",
                    username: "fixture", password: "fixture", allowLocalHTTP: true
                ))
                let tracks = available.map { id in
                    DulcetTrack(id: DulcetProviderItemID(providerInstanceID: "fixture", rawID: id),
                        title: id, credits: [], albumTitle: "Fixture", discNumber: 1,
                        trackNumber: 1, duration: .seconds(30), sourceContainer: .mp3, mediaSourceID: nil,
                        artwork: DulcetArtwork(seed: id, palette: .indigoCoral))
                }
                check(!tracks.contains { $0.id.rawID == "missing" }, "catalog lacks selection")
                controller.restorePersistedQueue(with: tracks)
                store.selectDestination(.nowPlaying)
                let label = "available=\(available) launch=\(launch)"
                print("\(label) controller=\(controller.currentPresentation.status) surface=\(store.snapshot.state)")
                check(controller.currentPresentation.status == .unavailable, "\(label) controller idle")
                check(store.snapshot.state == .nowPlayingUnavailable, "\(label) published idle")
                check(store.snapshot.nowPlaying == nil, "\(label) no playing item")
                let reader = ApplePlaybackQueueClient(databaseName: name)
                let saved = reader.snapshot()
                check(saved.errorKind == nil, "\(label) read durable queue")
                check(saved.snapshot?.entries.map(\.rawId) == available, "\(label) preserved resolvable entries")
                check(saved.snapshot?.entries.map(\.queueEntryId) == retainedIDs, "\(label) preserved queue identities")
                check(saved.snapshot?.currentIndex == -1, "\(label) cleared selection")
                check(reader.restoreCurrentPaused().startDirective == nil, "\(label) no stale restore directive")
                reader.close()
                controller.disconnect()
            }
        }
        print("Restoration checks: \(failures) failures")
        exit(failures == 0 ? 0 : 1)
    }
}

@MainActor private final class RestorationConnector: DulcetAccountConnecting, DulcetAccountConnectOperation {
    func connect(_ request: DulcetAccountConnectRequest,
                 completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void)
        -> any DulcetAccountConnectOperation { self }
    func cancel() {}
}
