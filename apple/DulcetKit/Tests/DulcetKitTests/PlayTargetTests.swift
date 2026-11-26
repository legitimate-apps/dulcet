import Foundation
import Testing
@testable import DulcetKit

/// A page's Play button shows Pause only while its own music is sounding. Judged by eye a wrong
/// rule looks right on the one page it was tried on, so each page kind is pinned here.
private func playTargetNowPlaying(
    trackID: String = "t1",
    source: DulcetPlaybackSource?,
    isPlaying: Bool,
    shuffleEnabled: Bool = false
) -> DulcetNowPlaying {
    let track = DulcetTrack(
        id: DulcetProviderItemID(providerInstanceID: "fixture", rawID: trackID),
        title: "Fixture Track",
        credits: [],
        albumTitle: nil,
        duration: .seconds(60),
        mediaSourceID: nil,
        artwork: DulcetArtwork(seed: trackID, palette: .indigoCoral)
    )
    return DulcetNowPlaying(
        current: track,
        queue: [track],
        source: source,
        elapsed: .seconds(5),
        isPlaying: isPlaying,
        outputName: "Fixture Output",
        volume: 1,
        audioFormat: DulcetAudioFormat(codec: "FLAC", sampleRateKilohertz: 44.1),
        seekability: .seekable,
        shuffleEnabled: shuffleEnabled,
        canGoNext: false,
        canGoPrevious: false
    )
}

private func id(_ raw: String) -> DulcetProviderItemID {
    DulcetProviderItemID(providerInstanceID: "fixture", rawID: raw)
}

@Test
func aCollectionsPlayShowsPauseOnlyWhileItsOwnMusicIsPlaying() {
    let fromAlbum = DulcetPlaybackSource(kind: .album, id: id("album-1"))
    let playing = playTargetNowPlaying(source: fromAlbum, isPlaying: true)

    #expect(playing.isPlayingTarget(.collection(.album, id("album-1"))))
    // Another album, the same id under another kind, and another account's id are not it.
    #expect(!playing.isPlayingTarget(.collection(.album, id("album-2"))))
    #expect(!playing.isPlayingTarget(.collection(.playlist, id("album-1"))))
    #expect(!playing.isPlayingTarget(.collection(
        .album, DulcetProviderItemID(providerInstanceID: "other", rawID: "album-1")
    )))
    // Paused: the button offers Play again, which starts the collection over.
    let paused = playTargetNowPlaying(source: fromAlbum, isPlaying: false)
    #expect(!paused.isPlayingTarget(.collection(.album, id("album-1"))))
}

@Test
func shuffleDoesNotChangeWhichPageIsPlaying() {
    let fromPlaylist = DulcetPlaybackSource(kind: .playlist, id: id("pl"))
    for shuffled in [false, true] {
        let state = playTargetNowPlaying(source: fromPlaylist, isPlaying: true, shuffleEnabled: shuffled)
        #expect(state.isPlayingTarget(.collection(.playlist, id("pl"))))
    }
}

@Test
func anUnknownOrIdlessSourceMatchesNoPage() {
    let unknown = playTargetNowPlaying(source: nil, isPlaying: true)
    #expect(!unknown.isPlayingTarget(.collection(.album, id("album-1"))))
    // The whole library and a search carry no id, so no page's Play claims them.
    for kind in [DulcetPlaybackQueueSourceKind.library, .search] {
        let state = playTargetNowPlaying(source: DulcetPlaybackSource(kind: kind, id: nil), isPlaying: true)
        #expect(!state.isPlayingTarget(.collection(.album, id("album-1"))))
        #expect(!state.isPlayingTarget(.collection(kind, id("album-1"))))
    }
}

@Test
func aTopResultSongShowsPauseWhateverQueueItIsPlayingFrom() {
    // Played from search, an album or a playlist: it is the same song.
    for source in [
        DulcetPlaybackSource(kind: .search, id: nil),
        DulcetPlaybackSource(kind: .album, id: id("album-1")),
        nil,
    ] {
        let state = playTargetNowPlaying(trackID: "song-9", source: source, isPlaying: true)
        #expect(state.isPlayingTarget(.song(id("song-9"))))
        #expect(!state.isPlayingTarget(.song(id("song-10"))))
    }
    let paused = playTargetNowPlaying(trackID: "song-9", source: nil, isPlaying: false)
    #expect(!paused.isPlayingTarget(.song(id("song-9"))))
}
