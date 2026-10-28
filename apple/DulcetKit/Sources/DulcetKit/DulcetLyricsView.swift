#if os(macOS) || os(iOS) || os(tvOS)
import SwiftUI

// The lyrics panel of Now Playing (spec §18.4), styled after the system player's: synced lyrics
// set large and bold over the playing cover's colours, the current line at full strength with
// past and upcoming lines dimmed and softly blurred by distance, a spring keeping the lit line
// in the reading position, a tap on a line seeking to it, an animated three-dot mark on long
// instrumental gaps, and hand scrolling that pauses the follow until the person stops. Plain
// lyrics scroll calmly; a track the server has no lyrics for says so; a failure says why and
// offers Try Again when trying again can help.

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
    /// Whether the playing track can be sought; a line tap seeks only then.
    var canSeek: Bool = false
    /// A tapped line's seek request; the caller routes it into the player's control path.
    var onSeek: (Duration) -> Void = { _ in }

    private var reader: (any DulcetLyricsReading)? {
        guard let session = store.librarySession,
              session.account?.providerInstanceID == track.id.providerInstanceID else { return nil }
        return session.reader as? any DulcetLyricsReading
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            // A container of its own, so each state keeps its identifier: without it the panel's
            // identifier replaced the one on a single-element state (OBSERVED on iOS 26.5: the
            // no-lyrics text reported `dulcet.lyrics.panel`, not `dulcet.lyrics.none`).
            .accessibilityElement(children: .contain)
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
                let position = anchor.position(at: context.date, playing: isPlaying)
                DulcetSyncedLyrics(
                    document: document,
                    position: position,
                    cursor: document.cursor(at: position),
                    status: DulcetLyricsPresentation.status(model.publication),
                    now: context.date,
                    canSeek: canSeek,
                    onSeek: onSeek,
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
                    // The message is the state's own witness. An identifier on the stack around
                    // it was applied to the message and Try Again alike, and as a container of
                    // its own the stack was folded into the panel and its identifier lost
                    // (both OBSERVED on iOS 26.5).
                    .accessibilityIdentifier("dulcet.lyrics.unavailable")
                if offersRetry {
                    Button(DulcetStrings.lyricsTryAgain) { model.retry(from: reader) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("dulcet.lyrics.retry")
                }
            }
            .frame(maxWidth: .infinity, minHeight: 160)
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

    /// Plain lyrics use the same readable hierarchy as timed lyrics.
    private static var plainLineFont: Font {
#if os(tvOS)
        .title.weight(.semibold)
#else
        .title.weight(.semibold)
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

extension DulcetLyricsPresentation {
    /// A silence long enough to mark with the instrumental indicator: the gap before the next
    /// sung line runs at least this long (the system player's mark appears on the same order of
    /// gap; its exact threshold is ASSUMED, not measured).
    static let instrumentalGapThreshold: Duration = .seconds(4)

    /// The index of the line the instrumental indicator sits above at `position`, or nil: the
    /// system player parks its animated dots where the next line will appear during a long
    /// instrumental. A blank line is itself the mark of a gap (it is drawn as an ellipsis), so a
    /// gap already holding one asks for no dots, and neither does a gap about to close on a blank
    /// line. Unsynced lyrics never have one. A song ending in silence gets none either: with no
    /// next line there is nothing for the dots to stand in for.
    static func instrumentalGap(in document: DulcetLyricsDocument, at position: Duration) -> Int? {
        guard document.synced else { return nil }
        let milliseconds = position.components.seconds * 1_000 + position.components.attoseconds / 1_000_000_000_000_000
        let timed = document.lines.enumerated().compactMap { index, line -> (index: Int, start: Int64)? in
            line.start.map { (index, $0.components.seconds * 1_000 + $0.components.attoseconds / 1_000_000_000_000_000) }
        }
        guard let next = timed.first(where: { $0.start > milliseconds }),
              !document.lines[next.index].text.isEmpty else { return nil }
        let previous = timed.last { $0.start <= milliseconds }
        if let previous, document.lines[previous.index].text.isEmpty { return nil }
        let gapStart = previous?.start ?? 0
        return next.start - gapStart >= Self.instrumentalGapThreshold.wholeMilliseconds
            ? next.index
            : nil
    }
}

private extension Duration {
    /// Whole milliseconds, truncated, as the core's cursor counts them.
    var wholeMilliseconds: Int64 {
        components.seconds * 1_000 + components.attoseconds / 1_000_000_000_000_000
    }
}

/// Whether the synced list is following the current line or the person has taken over the
/// scrolling. A hand scroll (touch drag, wheel, remote move) pauses the follow and draws every
/// line plainly so the sheet can be scanned; the follow resumes ``resumeDelay`` after the last
/// interaction, or at once when a line is tapped -- the system player's rule.
struct DulcetLyricsFollow: Equatable, Sendable {
    /// How long after the last hand scroll the follow resumes.
    static let resumeDelay: TimeInterval = 3

    /// The last hand scroll's moment; nil while following.
    private var manualScrollAt: Date?

    func following(at now: Date) -> Bool {
        guard let manualScrollAt else { return true }
        return now.timeIntervalSince(manualScrollAt) >= Self.resumeDelay
    }

    mutating func noteManualScroll(at now: Date = .now) {
        manualScrollAt = now
    }

    /// A tap on a synced line seeks to the line's start -- when it has one and the track can be
    /// sought -- and resumes the follow at once, so the lit line returns to the reading position.
    mutating func lineTapped(
        _ line: DulcetLyricsLine,
        canSeek: Bool,
        at now: Date = .now,
        seek: (Duration) -> Void
    ) {
        guard canSeek, let start = line.start else { return }
        seek(start)
        manualScrollAt = nil
    }
}

/// How strongly a synced line reads `distance` rows from the current one: the current line at
/// full strength, its neighbours dimmed and softly blurred, further lines quieter still -- the
/// system player's reading falloff. While the person scrolls by hand every line is drawn plainly.
/// Reduce Motion swaps the blur for opacity alone.
enum DulcetLyricsDimming {
    static func opacity(distance: Int, following: Bool) -> Double {
        guard following else { return distance == 0 ? 1 : 0.75 }
        switch distance {
        case 0: return 1
        case 1: return 0.65
        case 2: return 0.5
        default: return 0.38
        }
    }

    static func blur(distance: Int, following: Bool, reduceMotion: Bool) -> CGFloat {
        guard following, !reduceMotion else { return 0 }
        switch distance {
        case 0: return 0
        case 1: return 0.9
        case 2: return 1.8
        default: return 2.8
        }
    }

    /// Rows between a line and the current one: lit lines are at zero, the rows of an interlude's
    /// blank group count as neighbours of the gap, and before the first line every row is upcoming.
    static func distance(of line: Int, cursor: DulcetLyricsCursor) -> Int {
        if cursor.contains(line) { return 0 }
        guard cursor.index >= 0 else { return line + 1 }
        if line < cursor.index { return cursor.index - line }
        if line > cursor.lastIndex { return line - cursor.lastIndex }
        return 1
    }
}

private struct DulcetSyncedLyrics: View {
    let document: DulcetLyricsDocument
    /// Media time at this tick; the gap indicator is computed from it.
    let position: Duration
    let cursor: DulcetLyricsCursor
    let status: String?
    /// The tick's moment; the follow's resume is measured against it, so no timer is needed.
    let now: Date
    let canSeek: Bool
    let onSeek: (Duration) -> Void
    let reduceMotion: Bool
    @State private var follow = DulcetLyricsFollow()
#if os(tvOS)
    /// The line the remote sits on; moving into the lines is a hand scroll, selecting one seeks.
    @FocusState private var focusedLine: Int?
#endif

    /// The synced lines, set as the system player sets them: the lines are the screen. One size
    /// for lit and unlit lines alike, so the follow of the lit line never jumps the layout; the
    /// lit line reads by its full-strength colour, and Dynamic Type still scales the style.
    private static var lineFont: Font {
#if os(tvOS)
        .largeTitle.weight(.bold)
#else
        .largeTitle.weight(.bold)
#endif
    }

    private static var lineSpacing: CGFloat { DulcetSpacing.lg }

    /// The top and bottom inset as a share of the panel's height, so the first and last lines can
    /// both reach the reading position.
    private static let edgeFraction: CGFloat = 0.33

    /// The lit line parks in the upper third, where the next lines are already in view -- the
    /// system player's reading position, not the centre.
    private static let followAnchor = UnitPoint(x: 0.5, y: 0.33)

    private static let followAnimation: Animation = .spring(duration: 0.55, bounce: 0.12)

    private var following: Bool { follow.following(at: now) }

    private var gapIndex: Int? {
        DulcetLyricsPresentation.instrumentalGap(in: document, at: position)
    }

    var body: some View {
        GeometryReader { geometry in
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: Self.lineSpacing) {
                        if let status {
                            Text(status).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                        }
                        if document.trimmed {
                            Text(DulcetStrings.lyricsTrimmed).font(.caption).dulcetForeground(.secondaryTextOnWindow)
                        }
                        ForEach(Array(document.lines.enumerated()), id: \.offset) { index, line in
                            if gapIndex == index {
                                DulcetLyricsGapDots(reduceMotion: reduceMotion)
                                    .id(Self.gapDotsID)
                            }
                            lineRow(index: index, line: line)
                                .id(index)
                        }
                    }
                    .padding(.horizontal, DulcetSpacing.md)
                    .padding(.vertical, geometry.size.height * Self.edgeFraction)
                }
                // A drag on the sheet is a hand scroll, on the platforms with touch or a pointer.
                // A television scrolls by moving the remote's focus, which pauses the follow
                // through `focusedLine` below.
#if !os(tvOS)
                .simultaneousGesture(
                    DragGesture(minimumDistance: 1)
                        .onChanged { _ in follow.noteManualScroll() }
                )
#endif
                // Wheel and trackpad scrolls raise no drag; scroll phases (where available)
                // catch them. `.animating` is excluded: the follow's own spring is programmatic.
                .modifier(DulcetLyricsScrollPhasePause {
                    follow.noteManualScroll()
                })
                .onAppear {
                    if cursor.index >= 0 { proxy.scrollTo(cursor.index, anchor: Self.followAnchor) }
                }
                .onChange(of: cursor.index) { _, index in
                    guard following, index >= 0 else { return }
                    scrollToCurrentLine(proxy: proxy)
                }
                // Back to the lit line when the pause runs out.
                .onChange(of: following) { _, nowFollowing in
                    guard nowFollowing, cursor.index >= 0 else { return }
                    scrollToCurrentLine(proxy: proxy)
                }
                // Lines feather out at the sheet's edges instead of clipping, the way the system
                // player fades its lyrics under the header and over the transport. A fixed mask:
                // visual only, never a hit or a layout, and the reading position is far inside.
                .mask {
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0),
                            .init(color: .black, location: 0.05),
                            .init(color: .black, location: 0.95),
                            .init(color: .clear, location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: following)
#if os(tvOS)
        // Moving the remote into the lines is a hand scroll: it pauses the follow, and the pause
        // runs out three seconds after the last move unless a line is selected first.
        .onChange(of: focusedLine) { _, line in
            if line != nil { follow.noteManualScroll() }
        }
#endif
    }

    private func scrollToCurrentLine(proxy: ScrollViewProxy) {
        withAnimation(reduceMotion ? nil : Self.followAnimation) {
            proxy.scrollTo(cursor.index, anchor: Self.followAnchor)
        }
    }

    private static let gapDotsID = "dulcet.lyrics.gap-dots"

    /// The line's strength: the follow's distance dimming, overridden to full strength on the
    /// line the remote sits on (always false where there is no remote).
    private static func lineOpacity(distance: Int, following: Bool, focused: Bool) -> Double {
        if focused { return 1 }
        return DulcetLyricsDimming.opacity(distance: distance, following: following)
    }

    private func lineRow(index: Int, line: DulcetLyricsLine) -> some View {
        let lit = cursor.contains(index)
        let distance = DulcetLyricsDimming.distance(of: index, cursor: cursor)
#if os(tvOS)
        let focused = focusedLine == index
#else
        let focused = false
#endif
        let text = Text(line.text.isEmpty ? DulcetStrings.lyricsSyncedBlankLine : line.text)
            .font(Self.lineFont)
            .multilineTextAlignment(.leading)
            .dulcetForeground(lit ? .primaryTextOnWindow : .secondaryTextOnWindow)
            .opacity(Self.lineOpacity(distance: distance, following: following, focused: focused))
            .blur(radius: DulcetLyricsDimming.blur(distance: distance, following: following, reduceMotion: reduceMotion))
            .frame(maxWidth: .infinity, alignment: .leading)
#if os(tvOS)
        // A television has no tap: each line is a plain-styled button, so the remote's focus
        // scrolls the sheet line to line and Select seeks to the line's start. The button carries
        // the line's identifier -- a button's label text merges into its accessibility element,
        // so the identifier on the row reads as the line itself.
        return Button {
            follow.lineTapped(line, canSeek: canSeek, seek: onSeek)
        } label: {
            text
        }
        .buttonStyle(.plain)
        .focused($focusedLine, equals: index)
        .scaleEffect(focused ? 1.02 : 1)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: distance)
        .accessibilityIdentifier(lit ? "dulcet.lyrics.line.current" : "dulcet.lyrics.line")
        .accessibilityAddTraits(lit ? .isSelected : [])
#else
        return text
            .contentShape(Rectangle())
            .onTapGesture {
                follow.lineTapped(line, canSeek: canSeek, seek: onSeek)
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: distance)
            .accessibilityIdentifier(lit ? "dulcet.lyrics.line.current" : "dulcet.lyrics.line")
            .accessibilityAddTraits(lit ? .isSelected : [])
#endif
    }
}

/// A hand scroll reported by the scroll view's phases where the platform has them (iOS 18, macOS
/// 15, tvOS 18): wheel and trackpad scrolls raise no drag gesture. The follow's own animated
/// scroll reports `.animating`, which is not a hand scroll and never pauses the follow.
private struct DulcetLyricsScrollPhasePause: ViewModifier {
    let noteManualScroll: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18.0, macOS 15.0, tvOS 18.0, *) {
            content.onScrollPhaseChange { _, phase in
                if phase == .tracking || phase == .interacting { noteManualScroll() }
            }
        } else {
            content
        }
    }
}

/// The three-dot mark of a long instrumental, pulsing in turn where the next line will appear,
/// as the system player draws it. Reduce Motion holds the dots still.
private struct DulcetLyricsGapDots: View {
    let reduceMotion: Bool

#if os(tvOS)
    private static let dotSide: CGFloat = 14
#else
    private static let dotSide: CGFloat = 9
#endif

    var body: some View {
        if reduceMotion {
            dots(active: nil)
        } else {
            Color.clear
                .frame(width: 0, height: 0)
                .phaseAnimator([0, 1, 2]) { _, phase in
                    dots(active: phase)
                } animation: { _ in
                    .easeInOut(duration: 0.35)
                }
        }
    }

    private func dots(active: Int?) -> some View {
        HStack(spacing: DulcetSpacing.xs) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill()
                    .frame(width: Self.dotSide, height: Self.dotSide)
                    .opacity(active == nil || active == index ? 1 : 0.3)
                    .scaleEffect(active == index ? 1.3 : 1)
            }
        }
        .dulcetForeground(.secondaryTextOnWindow)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(DulcetStrings.lyricsInstrumental)
        .accessibilityIdentifier("dulcet.lyrics.instrumental")
    }
}

/// The playing cover's colours washed across the lyrics screen: the artwork, greatly enlarged and
/// blurred past recognition, dimmed by the window colour so the registered text pairs keep the
/// contrast they were measured at. The player's glow (``DulcetArtworkGlow``) is the small-scale
/// version of the same idea. Reduce Transparency removes it, leaving the plain window.
struct DulcetLyricsBackdrop: View {
    let artwork: DulcetArtwork
    @Environment(\.accessibilityReduceTransparency) private var systemReduceTransparency
    @Environment(\.dulcetReduceTransparencyOverride) private var reduceTransparencyOverride

    /// The cover is drawn square at this size and stretched past the screen; after a blur this
    /// wide only colour remains, so the size buys nothing more.
    static let artworkSize: CGFloat = 512
    /// Drawn this much larger than the screen, so the blur's falloff never reaches an edge.
    static let overdraw: CGFloat = 1.3
    static let blurRadius: CGFloat = 70
    /// How much of the window colour sits over the blurred cover.
    static let scrimOpacity: Double = 0.55

    var body: some View {
        DulcetPlayerBackdrop(artwork: artwork)
    }

    static func scale(for size: CGSize) -> CGFloat {
        max(size.width, size.height, 1) / artworkSize * overdraw
    }
}
#endif
