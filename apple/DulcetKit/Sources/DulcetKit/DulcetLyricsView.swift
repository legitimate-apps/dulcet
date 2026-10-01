#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

// The lyrics panel of Now Playing (spec §18.4). Synced lyrics light the current line group as
// media time moves and keep it in view; plain lyrics scroll; a track the server has no lyrics for
// says so; a failure says why and offers Try Again when trying again can help.

/// Lyrics for the current track. `elapsed` is the player's media time; between its updates the
/// panel moves on by the clock while playing, so the lit line does not lag a whole tick behind.
struct DulcetLyricsPanel: View {
    @Environment(DulcetPresentationStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var model = DulcetLyricsModel()
    @State private var anchor = DulcetLyricsClockAnchor(elapsed: .zero, at: .now)
    let track: DulcetTrack
    let elapsed: Duration
    let isPlaying: Bool

    private var reader: (any DulcetLyricsReading)? {
        guard let session = store.librarySession,
              session.account?.providerInstanceID == track.id.providerInstanceID else { return nil }
        return session.reader as? any DulcetLyricsReading
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .accessibilityIdentifier("dulcet.lyrics.panel")
            .onAppear {
                anchor = DulcetLyricsClockAnchor(elapsed: elapsed, at: .now)
                model.load(DulcetLyricsRequest(track: track), from: reader)
            }
            .onChange(of: track.id) { _, _ in model.load(DulcetLyricsRequest(track: track), from: reader) }
            .onChange(of: elapsed) { _, new in anchor = DulcetLyricsClockAnchor(elapsed: new, at: .now) }
            .onChange(of: store.librarySession?.readerGeneration) { _, _ in
                model.reload(from: reader)
            }
            // Reconnect in place keeps the reader; what was read while offline is read again.
            .onChange(of: store.librarySession?.isOnline == true) { wasOnline, online in
                guard online, !wasOnline, model.publication?.freshness != .live else { return }
                model.reload(from: reader)
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            ProgressView()
                .accessibilityLabel(DulcetStrings.lyricsLoading)
                .frame(maxWidth: .infinity, minHeight: 160)
        case let .synced(document):
            TimelineView(.periodic(from: .now, by: 0.2)) { context in
                DulcetSyncedLyrics(
                    document: document,
                    cursor: document.cursor(at: anchor.position(at: context.date, playing: isPlaying)),
                    status: DulcetLyricsPresentation.status(model.publication),
                    reduceMotion: reduceMotion
                )
            }
        case let .plain(document):
            ScrollView {
                VStack(alignment: .leading, spacing: DulcetSpacing.xs) {
                    notes(document)
                    ForEach(Array(document.lines.enumerated()), id: \.offset) { _, line in
                        Text(line.text.isEmpty ? DulcetStrings.lyricsPlainBlankLine : line.text)
                            .font(Self.plainLineFont)
                            .dulcetForeground(.primaryTextOnWindow)
                            .frame(maxWidth: .infinity, alignment: .leading)
#if os(tvOS)
                            // A television scrolls only by moving focus, and plain lyrics have
                            // no current line to follow: each line takes focus, so Up and Down
                            // read through them.
                            .focusable()
#endif
                            .accessibilityIdentifier("dulcet.lyrics.line")
                    }
                }
                .padding(.vertical, DulcetSpacing.md)
            }
        case .none:
            Text(DulcetStrings.lyricsNone)
                .font(.headline)
                .dulcetForeground(.secondaryTextOnWindow)
                .frame(maxWidth: .infinity, minHeight: 160)
                .accessibilityIdentifier("dulcet.lyrics.none")
        case let .unavailable(message, offersRetry):
            VStack(spacing: DulcetSpacing.sm) {
                Text(message)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .dulcetForeground(.secondaryTextOnWindow)
                if offersRetry {
                    Button(DulcetStrings.lyricsTryAgain) { model.retry(from: reader) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("dulcet.lyrics.retry")
                }
            }
            .frame(maxWidth: .infinity, minHeight: 160)
            .accessibilityIdentifier("dulcet.lyrics.unavailable")
        }
    }

    @ViewBuilder
    private func notes(_ document: DulcetLyricsDocument) -> some View {
        if let status = DulcetLyricsPresentation.status(model.publication) {
            Text(status).font(.caption).dulcetForeground(.secondaryTextOnWindow)
        }
        if document.trimmed {
            Text(DulcetStrings.lyricsTrimmed).font(.caption).dulcetForeground(.secondaryTextOnWindow)
        }
    }

    /// Plain lyrics' lines, sized for the room: a television reads from a sofa.
    private static var plainLineFont: Font {
#if os(tvOS)
        .title2.weight(.semibold)
#else
        .title3.weight(.semibold)
#endif
    }
}

/// Media time at a moment, and how far it has moved since while playing.
struct DulcetLyricsClockAnchor: Equatable {
    let elapsed: Duration
    let at: Date

    /// The position at `date`: the player's last report, plus the clock since it while playing --
    /// at most two seconds of it, so a stalled player never runs the lyrics far ahead.
    func position(at date: Date, playing: Bool) -> Duration {
        guard playing else { return elapsed }
        let since = min(max(0, date.timeIntervalSince(at)), 2)
        return elapsed + .milliseconds(Int64(since * 1_000))
    }
}

private struct DulcetSyncedLyrics: View {
    let document: DulcetLyricsDocument
    let cursor: DulcetLyricsCursor
    let status: String?
    let reduceMotion: Bool

    /// The synced lines, sized for the room: on a television they are the screen, read from a
    /// sofa. One size for lit and unlit lines alike, so the follow of the lit line never jumps
    /// the layout; the lit line reads by its full-strength colour.
    private static var lineFont: Font {
#if os(tvOS)
        .title.weight(.bold)
#else
        .title2.weight(.bold)
#endif
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: DulcetSpacing.sm) {
                    if let status {
                        Text(status).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                    }
                    if document.trimmed {
                        Text(DulcetStrings.lyricsTrimmed).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                    }
                    ForEach(Array(document.lines.enumerated()), id: \.offset) { index, line in
                        let lit = cursor.contains(index)
                        Text(line.text.isEmpty ? DulcetStrings.lyricsSyncedBlankLine : line.text)
                            .font(Self.lineFont)
                            .dulcetForeground(lit ? .primaryTextOnWindow : .secondaryTextOnWindow)
                            .opacity(lit ? 1 : 0.55)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .id(index)
                            .accessibilityIdentifier(lit ? "dulcet.lyrics.line.current" : "dulcet.lyrics.line")
                            .accessibilityAddTraits(lit ? .isSelected : [])
                    }
                }
                .padding(.vertical, DulcetSpacing.xl)
            }
            .onChange(of: cursor.index) { _, index in
                guard index >= 0 else { return }
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.35)) {
                    proxy.scrollTo(index, anchor: .center)
                }
            }
        }
    }
}
#endif
