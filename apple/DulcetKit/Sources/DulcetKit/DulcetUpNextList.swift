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
        }
        .accessibilityIdentifier("dulcet.upNext")
    }
}
