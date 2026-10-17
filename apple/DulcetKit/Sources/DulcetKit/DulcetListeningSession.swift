import Foundation

/// What a sleep timer waits for (spec §12.13). The raw values are the core's `SleepTimerChoice`
/// wire names, so a choice reads the same on every platform.
public enum DulcetSleepTimerChoice: String, CaseIterable, Sendable, Hashable, Identifiable {
    case minutes15 = "15m"
    case minutes30 = "30m"
    case minutes45 = "45m"
    case minutes60 = "60m"
    case endOfTrack
    case endOfQueue

    public var id: String { rawValue }

    /// The countdown's length, or nil for a choice that waits for the music to end.
    public var minutes: Int? {
        switch self {
        case .minutes15: 15
        case .minutes30: 30
        case .minutes45: 45
        case .minutes60: 60
        case .endOfTrack, .endOfQueue: nil
        }
    }

    public var label: String {
        if let minutes { return DulcetStrings.sleepTimerMinutes(minutes) }
        return self == .endOfTrack ? DulcetStrings.sleepTimerEndOfTrack : DulcetStrings.sleepTimerEndOfQueue
    }
}

/// A sleep timer that is set. The countdown itself runs on the monotonic clock inside the
/// playback controller; `endsAt` is only the wall-clock moment a surface shows ("until 23:40").
public struct DulcetSleepTimerState: Sendable, Hashable {
    public let choice: DulcetSleepTimerChoice
    /// When a timed choice is expected to end, for display; nil for end of track or queue.
    public let endsAt: Date?

    public init(choice: DulcetSleepTimerChoice, endsAt: Date?) {
        self.choice = choice
        self.endsAt = endsAt
    }
}

/// The device's listening settings (spec §12.8 crossfade, §14.4 AutoPlay).
public struct DulcetListeningPreference: Sendable, Hashable {
    public static let maximumCrossfadeSeconds = 12

    /// Whether the queue keeps playing similar music from the person's server when it ends.
    public var autoplayEnabled: Bool
    /// The overlap between two tracks of different albums, in whole seconds; 0 is off.
    public var crossfadeSeconds: Int

    public init(autoplayEnabled: Bool, crossfadeSeconds: Int) {
        self.autoplayEnabled = autoplayEnabled
        self.crossfadeSeconds = min(max(crossfadeSeconds, 0), Self.maximumCrossfadeSeconds)
    }
}

/// The listening settings as the production composition supplies them: the settings screen and
/// the Up Next toggle write them, playback reads them.
@MainActor
public protocol DulcetListeningSetting: AnyObject {
    var listeningPreference: DulcetListeningPreference { get }
    func setListeningPreference(_ preference: DulcetListeningPreference)
}

/// A playback controller that runs a sleep timer. Nil cancels the one that is set.
@MainActor
public protocol DulcetSleepTimerControlling: AnyObject {
    func setSleepTimer(_ choice: DulcetSleepTimerChoice?)
}

/// Word that Play Next or Add to Queue did what it says (the brief confirmation surfaces show).
/// `sequence` grows with every accepted addition, so two identical additions are two notices.
public struct DulcetQueueEditConfirmation: Sendable, Hashable {
    public enum Placement: Sendable, Hashable {
        case playNext
        case addToQueue
    }

    public let sequence: Int
    public let placement: Placement
    public let trackCount: Int
    /// The single track's title, when one track was added.
    public let title: String?

    public init(sequence: Int, placement: Placement, trackCount: Int, title: String?) {
        self.sequence = sequence
        self.placement = placement
        self.trackCount = trackCount
        self.title = title
    }

    /// The whole sentence the notice shows and VoiceOver announces.
    public var message: String {
        switch placement {
        case .playNext:
            if trackCount == 1, let title { return DulcetStrings.queueConfirmPlayNext(title: title) }
            return DulcetStrings.queueConfirmPlayNextCount(trackCount)
        case .addToQueue:
            if trackCount == 1, let title { return DulcetStrings.queueConfirmAddToQueue(title: title) }
            return DulcetStrings.queueConfirmAddToQueueCount(trackCount)
        }
    }
}
