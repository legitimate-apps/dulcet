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
        // Coverage is the second dimension on purpose. `wholeLibrary` means the catalog can
        // speak about absence, so an absent selection is cleared and no launch retries it.
        // `partial` means track lists are still being read one album at a time, so absence is
        // "not read yet" and clearing would destroy a good position from no evidence. The
        // third catalog below is the discriminator: its selection IS present (just unplayable),
        // so partial coverage must still act on it. A blanket bypass would fail that row.
        for (available, hasSourceContainer, coverage) in [
            (["a", "c"], true, DulcetLibraryCatalogCoverage.wholeLibrary),
            ([], true, DulcetLibraryCatalogCoverage.wholeLibrary),
            (["a", "missing", "c"], false, DulcetLibraryCatalogCoverage.wholeLibrary),
            (["a", "c"], true, DulcetLibraryCatalogCoverage.partial),
            ([], true, DulcetLibraryCatalogCoverage.partial),
            (["a", "missing", "c"], false, DulcetLibraryCatalogCoverage.partial),
        ] as [([String], Bool, DulcetLibraryCatalogCoverage)] {
            // Absence is only unknown while the selection itself is unread. These are the rows
            // where partial coverage must defer rather than clear.
            let deferring = coverage == .partial && !available.contains("missing")
            let name = "\(CommandLine.arguments[1])-\(available.count)-\(coverage).db"
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
            let retainedIDs = started.snapshot?.entries.map(\.queueEntryId)
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
                        trackNumber: 1, duration: .seconds(30), sourceContainer: hasSourceContainer ? .mp3 : nil, mediaSourceID: nil,
                        artwork: DulcetArtwork(seed: id, palette: .indigoCoral))
                }
                check(!tracks.contains { $0.id.rawID == "missing" && $0.sourceContainer != nil },
                      "catalog cannot resolve selection")
                if !hasSourceContainer {
                    check(!tracks.isEmpty && tracks.allSatisfy { $0.sourceContainer == nil },
                          "nonempty catalog has no source-container metadata")
                }
                controller.restorePersistedQueue(with: tracks, catalogCoverage: coverage)
                store.selectDestination(.nowPlaying)
                let label =
                    "available=\(available) containers=\(hasSourceContainer) "
                    + "coverage=\(coverage) launch=\(launch)"
                print("\(label) controller=\(controller.currentPresentation.status) surface=\(store.snapshot.state)")
                check(controller.currentPresentation.status == .unavailable, "\(label) controller idle")
                check(store.snapshot.state == .nowPlayingUnavailable, "\(label) published idle")
                check(store.snapshot.nowPlaying == nil, "\(label) no playing item")
                let reader = ApplePlaybackQueueClient(databaseName: name)
                let saved = reader.snapshot()
                check(saved.errorKind == nil, "\(label) read durable queue")
                check(saved.snapshot?.entries.map(\.rawId) == ["a", "missing", "c"], "\(label) preserved ALL persisted entries")
                check(saved.snapshot?.entries.map(\.queueEntryId) == retainedIDs, "\(label) preserved queue identities")
                if deferring {
                    // Deferred, not disarmed: the saved position is exactly where it was, and
                    // the durable queue still points at the same entry.
                    check(saved.snapshot?.currentIndex == 1, "\(label) preserved selection")
                    check(
                        reader.restoreCurrentPaused().startDirective?.rawId == "missing",
                        "\(label) durable selection still points at the saved entry"
                    )
                } else {
                    check(saved.snapshot?.currentIndex == -1, "\(label) cleared selection")
                    check(
                        reader.restoreCurrentPaused().startDirective == nil,
                        "\(label) no stale restore directive"
                    )
                }
                reader.close()
                controller.disconnect()
            }
            // An explicit Play action still reports an unresolvable source as a failure.
            let player = DulcetCorePlaybackController(databaseName: name)
            let store = DulcetPresentationStore(source: DulcetAccountDataSource(
                connector: RestorationConnector(), playbackController: player
            ))
            player.configure(account: DulcetPlaybackAccount(
                providerInstanceID: "fixture", normalizedServerURL: "http://127.0.0.1:1",
                username: "fixture", password: "fixture", allowLocalHTTP: true
            ))
            let unsupported = DulcetTrack(
                id: DulcetProviderItemID(providerInstanceID: "fixture", rawID: "explicit-play"),
                title: "Explicit play", credits: [], albumTitle: "Fixture", discNumber: 1,
                trackNumber: 1, duration: .seconds(30), sourceContainer: nil, mediaSourceID: nil,
                artwork: DulcetArtwork(seed: "explicit", palette: .indigoCoral)
            )
            check(unsupported.sourceContainer == nil, "explicit source is unresolvable")
            player.replaceQueueAndPlay(DulcetPlaybackQueueIntent(
                tracks: [unsupported], sourceKind: .library, sourceID: nil,
                sourceDisplayName: "Fixture", startIndex: 0, shuffle: false
            ))
            store.selectDestination(.nowPlaying)
            check(player.currentPresentation.status == .failed, "explicit play still fails")
            check(store.snapshot.state == .nowPlayingFailed, "explicit play publishes failure")
            print("explicit play controller=\(player.currentPresentation.status) surface=\(store.snapshot.state)")
            let beforeBypass = player.currentPresentation
            player.restorePersistedQueue(with: [], catalogCoverage: .wholeLibrary)
            print("after bypass controller=\(player.currentPresentation.status) surface=\(store.snapshot.state)")
            check(player.currentPresentation == beforeBypass, "bypassed restoration preserves failed presentation")
            check(store.snapshot.state == .nowPlayingFailed, "bypassed restoration still publishes failed state")
            // After checking the real store pipeline, observe the boundary directly to
            // reject even an identical re-publication on repeated no-op restoration.
            var bypassPublications = 0
            player.setPresentationHandler { _ in bypassPublications += 1 }
            player.restorePersistedQueue(with: [], catalogCoverage: .wholeLibrary)
            player.restorePersistedQueue(with: [unsupported], catalogCoverage: .wholeLibrary)
            player.restorePersistedQueue(with: [], catalogCoverage: .partial)
            check(bypassPublications == 0, "bypassed restoration publishes nothing")
            player.disconnect()
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
