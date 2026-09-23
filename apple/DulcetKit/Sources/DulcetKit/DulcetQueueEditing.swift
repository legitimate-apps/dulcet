import Foundation

/// A queue entry's identity. Opaque: it is the core's `QueueEntryId`, and it is what an edit
/// names, because the same track can be queued more than once.
public struct DulcetQueueEntryID: Hashable, Sendable, CustomStringConvertible {
    public let rawValue: String

    public init(_ rawValue: String) {
        precondition(!rawValue.isEmpty)
        self.rawValue = rawValue
    }

    public var description: String { "DulcetQueueEntryID(<opaque>)" }
}

public struct DulcetQueueEntry: Identifiable, Hashable, Sendable {
    public let id: DulcetQueueEntryID
    public let track: DulcetTrack

    public init(id: DulcetQueueEntryID, track: DulcetTrack) {
        self.id = id
        self.track = track
    }
}

/// Tracks to add to the existing queue, with where they came from (spec §14.1 `sourceContext`).
public struct DulcetQueueAddition: Sendable, Hashable {
    public let tracks: [DulcetTrack]
    public let sourceKind: DulcetPlaybackQueueSourceKind
    public let sourceID: DulcetProviderItemID?
    public let sourceDisplayName: String

    public init(
        tracks: [DulcetTrack],
        sourceKind: DulcetPlaybackQueueSourceKind,
        sourceID: DulcetProviderItemID?,
        sourceDisplayName: String
    ) {
        self.tracks = tracks
        self.sourceKind = sourceKind
        self.sourceID = sourceID
        self.sourceDisplayName = sourceDisplayName
    }
}

public enum DulcetQueueEditIntent: Sendable, Hashable {
    /// Immediately after the current entry, in the given order. Starts playback when nothing
    /// is queued, because "Play Next" with an empty player means "play this".
    case playNext(DulcetQueueAddition)
    /// At the end of the queue. Also starts playback when nothing is queued.
    case playLater(DulcetQueueAddition)
    /// Moves an entry to `toIndex` in the whole queue's order (`DulcetNowPlaying.queueEntries`).
    case move(DulcetQueueEntryID, toIndex: Int)
    /// Removes an entry that is not the current one.
    case remove(DulcetQueueEntryID)
    /// Removes everything after the current entry.
    case clearUpcoming
    /// Plays the named entry now, as a new playback session.
    case jump(DulcetQueueEntryID)
}

@MainActor
public protocol DulcetQueueEditing: AnyObject {
    func edit(_ intent: DulcetQueueEditIntent)
}

/// What an Up Next list shows and how its gestures become queue edits. A value derived from the
/// presentation, so a list can be tested without a view, and so every index translation between
/// "row in the Up Next section" and "position in the whole queue" lives in exactly one place.
public struct DulcetUpNextModel: Sendable, Hashable {
    public let history: [DulcetQueueEntry]
    public let current: DulcetQueueEntry?
    public let upcoming: [DulcetQueueEntry]
    public let sourceDisplayName: String?

    public init(nowPlaying: DulcetNowPlaying?) {
        guard let nowPlaying else {
            history = []
            current = nil
            upcoming = []
            sourceDisplayName = nil
            return
        }
        let entries = nowPlaying.queueEntries
        sourceDisplayName = nowPlaying.sourceDisplayName
        if let index = nowPlaying.currentEntryIndex, entries.indices.contains(index) {
            history = Array(entries[..<index])
            current = entries[index]
            upcoming = Array(entries[(index + 1)...])
        } else {
            history = []
            current = nil
            upcoming = entries
        }
    }

    private var upcomingBase: Int { history.count + (current == nil ? 0 : 1) }

    /// Translates a SwiftUI `onMove` inside the upcoming rows into a whole-queue move. SwiftUI's
    /// destination is an insertion offset in the list BEFORE removal, so moving a row down by one
    /// arrives as `destination == source + 2`.
    public func moveIntent(fromOffsets source: IndexSet, toOffset destination: Int)
        -> DulcetQueueEditIntent? {
        guard source.count == 1, let from = source.first, upcoming.indices.contains(from),
              (0...upcoming.count).contains(destination) else { return nil }
        let target = destination > from ? destination - 1 : destination
        guard target != from else { return nil }
        return .move(upcoming[from].id, toIndex: upcomingBase + target)
    }

    public func removeIntents(atOffsets offsets: IndexSet) -> [DulcetQueueEditIntent] {
        offsets.compactMap { upcoming.indices.contains($0) ? .remove(upcoming[$0].id) : nil }
    }

    public func jumpIntent(to entry: DulcetQueueEntry) -> DulcetQueueEditIntent {
        .jump(entry.id)
    }
}
