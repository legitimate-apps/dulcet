#if os(macOS)
import Foundation
import Testing
@testable import DulcetKit

@Test
func playbackMenuDisablesCommandsWithoutAuthoritativeCurrentItem() {
    let state = DulcetPlaybackMenuState(nowPlaying: nil)

    for command in [
        DulcetPlaybackMenuCommand.playPause,
        .next,
        .previous,
        .toggleShuffle,
        .cycleRepeat,
        .seekForward,
        .seekBackward,
    ] {
        #expect(!state.isEnabled(command))
        #expect(state.action(for: command) == nil)
    }
}

@Test
func playbackMenuEnablementAndActionsComeFromNowPlayingState() throws {
    let blocked = DulcetPlaybackMenuState(nowPlaying: playbackMenuNowPlaying(
        isPlaying: true,
        seekability: .notSeekable,
        canGoNext: false,
        canGoPrevious: true
    ))
    #expect(blocked.action(for: .playPause) == .pause)
    #expect(blocked.action(for: .next) == nil)
    #expect(blocked.action(for: .previous) == .previous)
    #expect(blocked.action(for: .seekForward) == nil)
    #expect(blocked.action(for: .seekBackward) == nil)

    let enabled = DulcetPlaybackMenuState(nowPlaying: playbackMenuNowPlaying(
        elapsed: .seconds(20),
        isPlaying: false,
        seekability: .seekable,
        shuffleEnabled: true,
        canGoNext: true,
        canGoPrevious: true
    ))
    #expect(enabled.action(for: .playPause) == .play)
    #expect(enabled.action(for: .next) == .next)
    #expect(enabled.action(for: .previous) == .previous)
    #expect(enabled.action(for: .toggleShuffle) == .setShuffle(false))
    #expect(enabled.action(for: .cycleRepeat) == .cycleRepeat)
    #expect(enabled.action(for: .seekForward) == .seek(.seconds(35)))
    #expect(enabled.action(for: .seekBackward) == .seek(.seconds(5)))
}

private func playbackMenuNowPlaying(
    elapsed: Duration = .seconds(20),
    isPlaying: Bool,
    seekability: DulcetPlaybackSeekability,
    shuffleEnabled: Bool = false,
    canGoNext: Bool,
    canGoPrevious: Bool
) -> DulcetNowPlaying {
    let track = DulcetTrack(
        id: DulcetProviderItemID(providerInstanceID: "fixture", rawID: "track"),
        title: "Fixture Track",
        credits: [],
        albumTitle: nil,
        duration: .seconds(60),
        mediaSourceID: nil,
        artwork: DulcetArtwork(seed: "track", palette: .indigoCoral)
    )
    return DulcetNowPlaying(
        current: track,
        queue: [track],
        elapsed: elapsed,
        isPlaying: isPlaying,
        outputName: "Fixture Output",
        volume: 1,
        audioFormat: DulcetAudioFormat(codec: "FLAC", sampleRateKilohertz: 44.1),
        seekability: seekability,
        shuffleEnabled: shuffleEnabled,
        canGoNext: canGoNext,
        canGoPrevious: canGoPrevious
    )
}
#endif
