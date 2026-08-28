#if os(macOS)
import SwiftUI

public enum DulcetPlaybackMenuCommand: Sendable, Hashable {
    case playPause
    case next
    case previous
    case toggleShuffle
    case cycleRepeat
    case seekForward
    case seekBackward
}

public struct DulcetPlaybackMenuState: Sendable, Hashable {
    public static let seekInterval: Duration = .seconds(15)

    public let nowPlaying: DulcetNowPlaying?

    public init(nowPlaying: DulcetNowPlaying?) {
        self.nowPlaying = nowPlaying
    }

    public func isEnabled(_ command: DulcetPlaybackMenuCommand) -> Bool {
        guard let nowPlaying else { return false }
        switch command {
        case .next:
            return nowPlaying.canGoNext
        case .previous:
            return nowPlaying.canGoPrevious
        case .seekForward, .seekBackward:
            return nowPlaying.seekability == .seekable
        case .playPause, .toggleShuffle, .cycleRepeat:
            return true
        }
    }

    public func action(
        for command: DulcetPlaybackMenuCommand
    ) -> DulcetPlaybackControlIntent? {
        guard isEnabled(command), let nowPlaying else { return nil }
        switch command {
        case .playPause:
            return nowPlaying.isPlaying ? .pause : .play
        case .next:
            return .next
        case .previous:
            return .previous
        case .toggleShuffle:
            return .setShuffle(!nowPlaying.shuffleEnabled)
        case .cycleRepeat:
            return .cycleRepeat
        case .seekForward:
            return .seek(min(
                nowPlaying.current.duration,
                nowPlaying.elapsed + Self.seekInterval
            ))
        case .seekBackward:
            return .seek(max(.zero, nowPlaying.elapsed - Self.seekInterval))
        }
    }
}

public struct DulcetPlaybackCommands: Commands {
    private let store: DulcetPresentationStore

    public init(store: DulcetPresentationStore) {
        self.store = store
    }

    public var body: some Commands {
        CommandMenu(DulcetStrings.playbackMenu) {
            Button(playPauseTitle) {
                perform(.playPause)
            }
            .keyboardShortcut(.space, modifiers: [])
            .disabled(!state.isEnabled(.playPause))

            Divider()

            Button(DulcetStrings.next) {
                perform(.next)
            }
            .keyboardShortcut(.rightArrow, modifiers: [])
            .disabled(!state.isEnabled(.next))

            Button(DulcetStrings.previous) {
                perform(.previous)
            }
            .keyboardShortcut(.leftArrow, modifiers: [])
            .disabled(!state.isEnabled(.previous))

            Divider()

            Button(shuffleTitle) {
                perform(.toggleShuffle)
            }
            .disabled(!state.isEnabled(.toggleShuffle))

            Button(repeatTitle) {
                perform(.cycleRepeat)
            }
            .disabled(!state.isEnabled(.cycleRepeat))

            Divider()

            Button(DulcetStrings.seekForward15Seconds) {
                perform(.seekForward)
            }
            .keyboardShortcut(.rightArrow, modifiers: [.command, .option])
            .disabled(!state.isEnabled(.seekForward))

            Button(DulcetStrings.seekBackward15Seconds) {
                perform(.seekBackward)
            }
            .keyboardShortcut(.leftArrow, modifiers: [.command, .option])
            .disabled(!state.isEnabled(.seekBackward))
        }
    }

    private var state: DulcetPlaybackMenuState {
        DulcetPlaybackMenuState(nowPlaying: store.snapshot.nowPlaying)
    }

    private var playPauseTitle: String {
        store.snapshot.nowPlaying?.isPlaying == true ? DulcetStrings.pause : DulcetStrings.play
    }

    private var shuffleTitle: String {
        if store.snapshot.nowPlaying?.shuffleEnabled == true {
            return DulcetStrings.turnShuffleOff
        }
        return DulcetStrings.turnShuffleOn
    }

    private var repeatTitle: String {
        let mode = switch store.snapshot.nowPlaying?.repeatMode {
        case .all: DulcetStrings.repeatAll
        case .one: DulcetStrings.repeatOne
        case .off, nil: DulcetStrings.repeatOff
        }
        return DulcetStrings.repeatMenuValue(mode)
    }

    private func perform(_ command: DulcetPlaybackMenuCommand) {
        guard let intent = state.action(for: command) else { return }
        store.sendPlaybackControl(intent)
    }
}
#endif
