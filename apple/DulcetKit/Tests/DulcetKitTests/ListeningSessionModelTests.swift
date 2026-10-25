import Foundation
import Testing
@testable import DulcetKit

/// The presentation half of the listening session: the AutoPlay section of Up Next, the queue
/// confirmations' sentences, the sleep-timer choices and the crossfade setting's bounds.
struct ListeningSessionModelTests {
    @Test func searchPlaybackAdapterKeepsAlbumIdentityAndDuration() {
        let result = DulcetSearchResult(id: .init(providerInstanceID: "server", rawID: "song:opaque"),
            title: "Song", kind: .track, credits: [], albumTitle: "Album", year: nil,
            duration: .seconds(40), mediaSourceID: nil, artwork: DulcetArtwork(seed: "song", palette: .indigoCoral),
            albumID: .init(providerInstanceID: "server", rawID: "album:opaque"))
        #expect(result.playableTrack?.albumID?.rawID == "album:opaque")
        #expect(result.playableTrack?.duration == .seconds(40))
    }

    @Test
    func autoplayEntriesFormTheirOwnSectionAfterWhatThePersonQueued() {
        // queue: h0 cur1 | u2 u3 | auto4 auto5
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(
            ["h", "cur", "u0", "u1", "auto0", "auto1"], current: 1, autoplayFrom: 4
        ))
        #expect(model.upcoming.map(\.id.rawValue) == ["entry-2", "entry-3"])
        #expect(model.autoplay.map(\.id.rawValue) == ["entry-4", "entry-5"])
        // Moves stay inside the person's rows and still name whole-queue indices.
        #expect(model.moveIntent(fromOffsets: [0], toOffset: 2) == .move(.init("entry-2"), toIndex: 3))
        #expect(model.moveIntent(fromOffsets: [0], toOffset: 3) == nil, "never into the AutoPlay rows")
        #expect(model.removeAutoplayIntents(atOffsets: [1]) == [.remove(.init("entry-5"))])
        #expect(model.removeAutoplayIntents(atOffsets: [2]).isEmpty)
        #expect(model.removeIntents(atOffsets: [0]) == [.remove(.init("entry-2"))])
    }

    @Test
    func withoutAutoplayEntriesTheSectionIsEmpty() {
        let model = DulcetUpNextModel(nowPlaying: nowPlaying(["cur", "u0"], current: 0, autoplayFrom: nil))
        #expect(model.autoplay.isEmpty)
        #expect(model.upcoming.count == 1)
    }

    @Test
    func confirmationsNameOneTrackAndCountSeveral() {
        let one = DulcetQueueEditConfirmation(sequence: 1, placement: .playNext, trackCount: 1, title: "Blue")
        let later = DulcetQueueEditConfirmation(sequence: 2, placement: .addToQueue, trackCount: 1, title: "Blue")
        let many = DulcetQueueEditConfirmation(sequence: 3, placement: .addToQueue, trackCount: 12, title: nil)
        let next = DulcetQueueEditConfirmation(sequence: 4, placement: .playNext, trackCount: 3, title: nil)
        #expect(one.message == "\u{201C}Blue\u{201D} will play next")
        #expect(later.message == "Added \u{201C}Blue\u{201D} to Up Next")
        #expect(many.message == "Added 12 songs to Up Next")
        #expect(next.message == "3 songs will play next")
        // Two identical additions are two notices.
        #expect(one != DulcetQueueEditConfirmation(sequence: 5, placement: .playNext, trackCount: 1, title: "Blue"))
    }

    @Test
    func sleepTimerChoicesUseTheCoresWireNames() {
        #expect(DulcetSleepTimerChoice.allCases.map(\.rawValue) == ["15m", "30m", "45m", "60m", "endOfTrack", "endOfQueue"])
        #expect(DulcetSleepTimerChoice.allCases.compactMap(\.minutes) == [15, 30, 45, 60])
        #expect(DulcetSleepTimerChoice.minutes15.label == "15 minutes")
        #expect(DulcetSleepTimerChoice.endOfTrack.label == "End of Track")
    }

    @Test
    func crossfadeIsClampedToItsRange() {
        #expect(DulcetListeningPreference(autoplayEnabled: true, crossfadeSeconds: -3).crossfadeSeconds == 0)
        #expect(DulcetListeningPreference(autoplayEnabled: true, crossfadeSeconds: 7).crossfadeSeconds == 7)
        #expect(DulcetListeningPreference(autoplayEnabled: true, crossfadeSeconds: 40).crossfadeSeconds == 12)
    }

    private func nowPlaying(_ titles: [String], current: Int, autoplayFrom: Int?) -> DulcetNowPlaying {
        let entries = titles.enumerated().map { offset, title in
            DulcetQueueEntry(
                id: .init("entry-\(offset)"),
                track: track(title),
                isAutoplay: autoplayFrom.map { offset >= $0 } ?? false
            )
        }
        return DulcetNowPlaying(
            current: entries[current].track,
            queue: entries.map(\.track),
            currentIndex: current,
            sourceDisplayName: "Album",
            elapsed: .zero,
            isPlaying: true,
            outputName: "This Device",
            volume: 1,
            audioFormat: .init(codec: "MP3", sampleRateKilohertz: 0),
            queueEntries: entries,
            currentEntryIndex: current,
            autoplayActive: autoplayFrom != nil
        )
    }

    private func track(_ title: String) -> DulcetTrack {
        DulcetTrack(
            id: .init(providerInstanceID: "server", rawID: title),
            title: title,
            credits: [],
            albumTitle: "Album",
            duration: .seconds(180),
            mediaSourceID: nil,
            artwork: DulcetArtwork(seed: title, palette: .indigoCoral)
        )
    }
}
