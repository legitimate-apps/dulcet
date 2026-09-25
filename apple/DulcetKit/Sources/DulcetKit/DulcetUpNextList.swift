import Accessibility
import SwiftUI

enum DulcetQueueStrings {
    static let upNext = text("queue.upNext", "Up Next")
    static let nothingUpNext = text("queue.nothingUpNext", "Nothing Up Next")
    static let clear = text("queue.clear", "Clear")
    static let remove = text("queue.remove", "Remove")
    static let playNow = text("queue.playNow", "Play Now")
    static let playNext = text("queue.playNext", "Play Next")
    static let playLater = text("queue.playLater", "Play Last")
    static let moveUp = text("queue.moveUp", "Move Up")
    static let moveDown = text("queue.moveDown", "Move Down")
    static let previouslyPlayed = text("queue.history", "Previously Played")
    static let playAgain = text("queue.playAgain", "Play Again")
    static let airPlay = text("queue.airPlay", "AirPlay")
    static let volume = text("queue.volume", "Volume")

    static func playingFrom(_ source: String) -> String {
        String(
            localized: "queue.playingFrom",
            defaultValue: "Playing from \(source)",
            bundle: .module
        )
    }

    private static func text(
        _ key: StaticString,
        _ fallback: String.LocalizationValue
    ) -> String {
        String(localized: key, defaultValue: fallback, bundle: .module)
    }
}

/// The Up Next section: the entries after the current one. Tap a row to play it now; drag to
/// reorder and swipe to remove where the platform has those gestures; a context menu carries the
/// same actions everywhere, including tvOS, which has neither.
///
/// It renders rows only. The owner of the Now Playing screen decides where it sits and wraps it
/// in its own container; it is a `List` section so it composes into an existing `List`.
public struct DulcetUpNextSection: View {
    private let model: DulcetUpNextModel
    private let onEdit: (DulcetQueueEditIntent) -> Void

    public init(nowPlaying: DulcetNowPlaying?, onEdit: @escaping (DulcetQueueEditIntent) -> Void) {
        model = DulcetUpNextModel(nowPlaying: nowPlaying)
        self.onEdit = onEdit
    }

    public var body: some View {
        Section {
            if model.upcoming.isEmpty {
                Text(DulcetQueueStrings.nothingUpNext)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .accessibilityIdentifier("dulcet.upNext.empty")
            } else {
                rows
            }
        } header: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(DulcetQueueStrings.upNext).font(.headline)
                    if let source = model.sourceDisplayName {
                        Text(DulcetQueueStrings.playingFrom(source))
                            .font(.caption)
                            .dulcetForeground(.secondaryTextOnWindow)
                    }
                }
                Spacer()
                if !model.upcoming.isEmpty {
                    Button(DulcetQueueStrings.clear) { onEdit(.clearUpcoming) }
                        .accessibilityIdentifier("dulcet.upNext.clear")
                }
            }
        }
    }

    @ViewBuilder
    private var rows: some View {
        #if os(tvOS)
        ForEach(Array(model.upcoming.enumerated()), id: \.element.id) { offset, entry in
            row(entry, offset: offset)
        }
        #else
        ForEach(Array(model.upcoming.enumerated()), id: \.element.id) { offset, entry in
            row(entry, offset: offset)
        }
        .onMove { source, destination in
            if let intent = model.moveIntent(fromOffsets: source, toOffset: destination) {
                onEdit(intent)
            }
        }
        .onDelete { offsets in
            model.removeIntents(atOffsets: offsets).forEach(onEdit)
        }
        #endif
    }

    private func row(_ entry: DulcetQueueEntry, offset: Int) -> some View {
        Button {
            onEdit(model.jumpIntent(to: entry))
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.track.title)
                    .lineLimit(1)
                Text(DulcetStrings.artistNames(entry.track.artistNames))
                    .font(.caption)
                    .dulcetForeground(.secondaryTextOnWindow)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .dulcetHoverEffect()
        .accessibilityIdentifier("dulcet.upNext.row.\(offset)")
        // Reordering is a drag, which VoiceOver and Switch Control cannot perform on a row, so
        // Move Up and Move Down are named actions. Removal needs one only where the platform does
        // not already offer it: on iOS the row's swipe comes with a Delete action derived from
        // `.onDelete` (OBSERVED on iOS 26.5, listed beside these), so a Remove there would be the
        // same action twice. tvOS has no `.onDelete`; that the Mac derives no such action is
        // ASSUMED, so it keeps Remove.
        .accessibilityActions {
            if offset > 0,
               let intent = model.moveIntent(
                   fromOffsets: IndexSet(integer: offset),
                   toOffset: offset - 1
               ) {
                Button(DulcetQueueStrings.moveUp) { onEdit(intent) }
            }
            if offset + 1 < model.upcoming.count,
               let intent = model.moveIntent(
                   fromOffsets: IndexSet(integer: offset),
                   toOffset: offset + 2
               ) {
                Button(DulcetQueueStrings.moveDown) { onEdit(intent) }
            }
            #if !os(iOS)
            Button(DulcetQueueStrings.remove) { onEdit(.remove(entry.id)) }
            #endif
        }
        .contextMenu {
            Button(DulcetQueueStrings.playNow) { onEdit(model.jumpIntent(to: entry)) }
            if offset > 0,
               let intent = model.moveIntent(
                   fromOffsets: IndexSet(integer: offset),
                   toOffset: offset - 1
               ) {
                Button(DulcetQueueStrings.moveUp) { onEdit(intent) }
            }
            if offset + 1 < model.upcoming.count,
               let intent = model.moveIntent(
                   fromOffsets: IndexSet(integer: offset),
                   toOffset: offset + 2
               ) {
                Button(DulcetQueueStrings.moveDown) { onEdit(intent) }
            }
            Button(DulcetQueueStrings.remove, role: .destructive) { onEdit(.remove(entry.id)) }
        }
    }
}

/// A standalone Up Next list, for a sheet or a column. Rows stay tappable: on iOS a long press
/// drags a row and a swipe removes it without entering edit mode, which would disable the tap.
public struct DulcetUpNextList: View {
    private let nowPlaying: DulcetNowPlaying?
    private let onEdit: (DulcetQueueEditIntent) -> Void

    public init(nowPlaying: DulcetNowPlaying?, onEdit: @escaping (DulcetQueueEditIntent) -> Void) {
        self.nowPlaying = nowPlaying
        self.onEdit = onEdit
    }

    public var body: some View {
        List {
            DulcetUpNextSection(nowPlaying: nowPlaying, onEdit: onEdit)
#if !os(tvOS)
            DulcetQueueHistorySection(nowPlaying: nowPlaying, onEdit: onEdit)
#endif
        }
        .accessibilityIdentifier("dulcet.upNext")
    }
}

#if !os(tvOS)
/// Previously Played, under Up Next: the queue's entries BEFORE the current one, nearest first.
/// It is positional, not a listening log -- an entry the queue passed without playing it (a jump
/// over it, or an automatic skip past a track that could not play, spec §12.12) is listed like
/// any other, unmarked, and repeat-all wrapping to the first entry empties it. Collapsed until
/// asked for, so a long album played to its middle does not push Up Next off the screen; a row
/// plays that entry again. Nothing when the current entry is the first.
struct DulcetQueueHistorySection: View {
    private let model: DulcetUpNextModel
    private let onEdit: (DulcetQueueEditIntent) -> Void
    @State private var expanded = false

    init(nowPlaying: DulcetNowPlaying?, onEdit: @escaping (DulcetQueueEditIntent) -> Void) {
        model = DulcetUpNextModel(nowPlaying: nowPlaying)
        self.onEdit = onEdit
    }

    var body: some View {
        if !model.recentHistory.isEmpty {
            Section {
                // A disclosure group, so VoiceOver reads it as expanded or collapsed.
                DisclosureGroup(isExpanded: $expanded) {
                    ForEach(Array(model.recentHistory.enumerated()), id: \.element.id) { offset, entry in
                        Button {
                            DulcetUIProofMarkers.record("history-jump:\(offset)")
                            onEdit(model.jumpIntent(to: entry))
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.track.title)
                                    .lineLimit(1)
                                Text(DulcetStrings.artistNames(entry.track.artistNames))
                                    .font(.caption)
                                    .dulcetForeground(.secondaryTextOnWindow)
                                    .lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .dulcetHoverEffect()
                        .accessibilityHint(DulcetQueueStrings.playAgain)
                        .accessibilityIdentifier("dulcet.history.row.\(offset)")
                    }
                } label: {
                    Text(DulcetQueueStrings.previouslyPlayed)
                        .font(.headline)
                        .accessibilityIdentifier("dulcet.history.toggle")
                }
            }
        }
    }
}
#endif

extension View {
    /// Says so, briefly and to VoiceOver, when the playback controller refuses a queue edit, and
    /// when the queue skips past a track it could not play (spec §12.12): a row that would not
    /// move, a track that would not queue, or music that jumped a track otherwise looks like
    /// something that silently happened -- or silently did not.
    ///
    /// `isActive` false leaves it to another surface on top -- the shell under a presented player
    /// -- so one notice is shown and announced once, where the person is looking.
    ///
    /// Where the notice is drawn is not decided here. It goes at the bottom of the page the
    /// person is looking at, centred, above the now-playing bar and the tab bar, never over a
    /// navigation control: so it is handed down to the page, whose now-playing bar placement
    /// draws it (``DulcetNowPlayingBarPlacement``). A surface with no such page under it -- the
    /// player, and tvOS -- passes `drawsNotices` and draws them along its own bottom edge.
    func dulcetPlaybackFeedback(
        store: DulcetPresentationStore,
        isActive: Bool = true,
        drawsNotices: Bool = false
    ) -> some View {
        modifier(DulcetPlaybackFeedback(store: store, isActive: isActive, drawsNotices: drawsNotices))
    }
}

/// The playback feedback's notices on screen now, handed down to the page that draws them.
struct DulcetPlaybackNotices: Equatable {
    var showingRefusal = false
    var skipMessage: String?
}

private struct DulcetPlaybackNoticesKey: EnvironmentKey {
    static let defaultValue = DulcetPlaybackNotices()
}

extension EnvironmentValues {
    var dulcetPlaybackNotices: DulcetPlaybackNotices {
        get { self[DulcetPlaybackNoticesKey.self] }
        set { self[DulcetPlaybackNoticesKey.self] = newValue }
    }
}

private struct DulcetPlaybackFeedback: ViewModifier {
    let store: DulcetPresentationStore
    let isActive: Bool
    let drawsNotices: Bool
    @State private var showingRefusal = false
    @State private var refusalHideTask: Task<Void, Never>?
    @State private var skipMessage: String?
    @State private var skipHideTask: Task<Void, Never>?

    func body(content: Content) -> some View {
        let notices = DulcetPlaybackNotices(showingRefusal: showingRefusal, skipMessage: skipMessage)
        content
            // Drawn here, nothing beneath draws them again.
            .environment(\.dulcetPlaybackNotices, drawsNotices ? DulcetPlaybackNotices() : notices)
            .overlay(alignment: .bottom) {
                if drawsNotices {
                    DulcetPlaybackNoticeRegion {
                        DulcetPlaybackNoticeStack(notices: notices)
                    }
                }
            }
            .onChange(of: store.snapshot.refusedQueueEdits) { previous, current in
                guard isActive, current > previous else { return }
                AccessibilityNotification.Announcement(DulcetStrings.queueEditRefused).post()
                withAnimation(.easeInOut(duration: 0.2)) { showingRefusal = true }
                refusalHideTask?.cancel()
                refusalHideTask = Task { @MainActor in
                    try? await Task.sleep(for: .seconds(3))
                    guard !Task.isCancelled else { return }
                    withAnimation(.easeInOut(duration: 0.2)) { showingRefusal = false }
                }
            }
            .onChange(of: store.snapshot.playbackSkipNotice) { previous, current in
                guard let current else {
                    // Withdrawn -- the account disconnected, or signed out: the track it named
                    // belongs to a queue that is gone.
                    skipHideTask?.cancel()
                    withAnimation(.easeInOut(duration: 0.2)) { skipMessage = nil }
                    return
                }
                guard isActive, current.sequence != previous?.sequence else { return }
                DulcetUIProofMarkers.record("skip-notice:\(current.sequence)")
                AccessibilityNotification.Announcement(current.message).post()
                withAnimation(.easeInOut(duration: 0.2)) { skipMessage = current.message }
                skipHideTask?.cancel()
                skipHideTask = Task { @MainActor in
                    try? await Task.sleep(for: .seconds(4))
                    guard !Task.isCancelled else { return }
                    withAnimation(.easeInOut(duration: 0.2)) { skipMessage = nil }
                }
            }
    }
}

/// Where the notices go: along the bottom of the space it is given -- the page's own frame,
/// between its navigation bar and the now-playing bar -- centred, offered at most
/// ``maximumShare`` of that height (spec §12.12 rule 5).
///
/// The offer is what lets a notice choose its shorter sentence rather than grow over the
/// navigation bar: a notice is transient status, and a card covering most of the page for four
/// seconds hides the page it is about. It takes the whole space it is given, draws nothing of its
/// own, and passes taps through.
struct DulcetPlaybackNoticeRegion: Layout {
    /// The most of the page's height a notice is offered.
    static let maximumShare: CGFloat = 1.0 / 3.0

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        proposal.replacingUnspecifiedDimensions()
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let offered = ProposedViewSize(width: bounds.width, height: bounds.height * Self.maximumShare)
        for subview in subviews {
            subview.place(at: CGPoint(x: bounds.midX, y: bounds.maxY), anchor: .bottom, proposal: offered)
        }
    }
}

/// The notices, stacked at the bottom of the surface drawing them, centred, clear of its edges.
/// Taps pass through them to whatever is under.
///
/// The skip notice names the track when that sentence fits the height it is offered, and
/// otherwise says the same without the title (``DulcetStrings/playbackSkippedAfterFailureUntitled``):
/// never truncated, and never grown over navigation. VoiceOver is told the whole sentence
/// either way, in the announcement the notice makes as it appears. The text is capped at
/// ``DynamicTypeSize/accessibility2``, as a transient status line, so the shorter sentence fits
/// on any phone.
struct DulcetPlaybackNoticeStack: View {
    static let largestTextSize = DynamicTypeSize.accessibility2
    static let skipIdentifier = "dulcet.playback.skipped-notice"
    let notices: DulcetPlaybackNotices

    var body: some View {
        VStack(spacing: DulcetSpacing.xs) {
            if notices.showingRefusal {
                DulcetPlaybackNotice(text: DulcetStrings.queueEditRefused, systemImage: "exclamationmark.circle")
                    .accessibilityIdentifier("dulcet.queue.edit-refused")
            }
            if let skipMessage = notices.skipMessage {
                ViewThatFits(in: .vertical) {
                    DulcetPlaybackNotice(text: skipMessage, systemImage: "forward.end")
                        .accessibilityIdentifier(Self.skipIdentifier)
                    DulcetPlaybackNotice(
                        text: DulcetStrings.playbackSkippedAfterFailureUntitled,
                        systemImage: "forward.end"
                    )
                    .accessibilityIdentifier(Self.skipIdentifier)
                }
            }
        }
        .dynamicTypeSize(...Self.largestTextSize)
        // Never edge to edge: a notice that wraps stays a card, clear of the screen's sides.
        .padding(.horizontal, DulcetSpacing.lg)
        .padding(.bottom, DulcetSpacing.sm)
        .frame(maxWidth: .infinity, alignment: .bottom)
        .allowsHitTesting(false)
    }
}

/// One notice: its sentence on the regular material, the pair its colour was measured against
/// (`primaryTextOnRegularMaterial`).
///
/// The material always contains the text, at every Dynamic Type size and however many lines a
/// long title wraps to. The background is a rounded rectangle, not a capsule, whose corner
/// radius would grow with a wrapped notice's height; and its continuous corners bend away from
/// the straight edge over ``cornerExtent`` × the radius along each side, so a horizontal padding
/// at least that wide keeps every line clear of every corner. The text is never truncated.
struct DulcetPlaybackNotice: View {
    static let cornerRadius: CGFloat = 10
    /// How far along each edge a continuous corner curves, per point of radius.
    static let cornerExtent: CGFloat = 1.528665
    static let horizontalPadding: CGFloat = DulcetSpacing.md
    let text: String
    let systemImage: String

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.callout.weight(.semibold))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, Self.horizontalPadding)
            .padding(.vertical, DulcetSpacing.xs)
            .background(
                .regularMaterial,
                in: RoundedRectangle(cornerRadius: Self.cornerRadius, style: .continuous)
            )
            .dulcetForeground(.primaryTextOnRegularMaterial)
            // One element that reads as the sentence. The symbol is decoration, and its own
            // label ("Go To End" for the skip) would otherwise be read first.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(text)
            .transition(.opacity)
    }
}
