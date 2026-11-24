import SwiftUI

/// When a player surface says it is waiting for audio. A skip names the next track at once and
/// is buffering for the moment its media opens; saying "Buffering…" for that moment made every
/// skip look stalled (OBSERVED on an iPhone, 2026-11-24, as "Loading…" then "Buffering…" before
/// the title). The cue appears only once the same entry has waited ``delay``, as the system
/// player stays quiet through an ordinary skip and speaks up when the network is slow.
enum DulcetBufferingCue {
    static let delay: Duration = .milliseconds(900)

    /// What a cue is keyed on: the entry waiting, while it waits. A new entry starts its own wait.
    struct Key: Hashable {
        let sessionID: DulcetPlaybackSessionID?
        let trackID: DulcetProviderItemID?
        let waiting: Bool

        init(_ player: DulcetNowPlaying?) {
            sessionID = player?.sessionID
            trackID = player?.current.id
            waiting = player?.phase == .buffering
        }
    }
}

private struct DulcetBufferingCueModifier: ViewModifier {
    let key: DulcetBufferingCue.Key
    @Binding var shown: Bool

    func body(content: Content) -> some View {
        content.task(id: key) {
            shown = false
            guard key.waiting else { return }
            try? await Task.sleep(for: DulcetBufferingCue.delay)
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.25)) { shown = true }
        }
    }
}

extension View {
    /// Sets `shown` once `player` has been buffering the same entry for ``DulcetBufferingCue/delay``,
    /// and clears it as soon as it is not.
    func dulcetBufferingCue(for player: DulcetNowPlaying?, shown: Binding<Bool>) -> some View {
        modifier(DulcetBufferingCueModifier(key: .init(player), shown: shown))
    }
}
