import Foundation
import Observation

// Lyrics for Now Playing (spec §18.4).
//
// `DulcetLyricsReading` is what a reader offers when it can read lyrics; the app target adapts the
// core's `AppleLibraryLyricsClient`, tests adapt a fake. Which line is current is the core's rule
// (`cursorAtMilliseconds`), carried in each document as `cursor`, so no shell decides it.

public enum DulcetLyricsReadMode: Sendable, Hashable {
    /// The stored document, no request: the panel's first frame.
    case cached
    /// Live when online, else what is stored.
    case read
    /// The person's Retry.
    case retry
}

public struct DulcetLyricsRequest: Sendable, Hashable {
    public let trackRawID: String
    public let artist: String?
    public let title: String?

    public init(trackRawID: String, artist: String?, title: String?) {
        self.trackRawID = trackRawID
        self.artist = artist
        self.title = title
    }

    public init(track: DulcetTrack) {
        self.init(
            trackRawID: track.id.rawID,
            artist: track.credits.first { $0.role == .artist }?.name ?? track.credits.first?.name,
            title: track.title
        )
    }
}

/// The current line group for a playback position (§18.4): `index...lastIndex` of the lines, or
/// none. `isInterlude` is before the first line or on a group of blank lines -- nothing is lit.
public struct DulcetLyricsCursor: Sendable, Hashable {
    public let index: Int
    public let lastIndex: Int
    public let isInterlude: Bool

    public init(index: Int, lastIndex: Int, isInterlude: Bool) {
        self.index = index
        self.lastIndex = lastIndex
        self.isInterlude = isInterlude
    }

    public static let none = DulcetLyricsCursor(index: -1, lastIndex: -1, isInterlude: false)

    /// Whether `line` is lit.
    public func contains(_ line: Int) -> Bool {
        !isInterlude && index >= 0 && line >= index && line <= lastIndex
    }
}

public struct DulcetLyricsLine: Sendable, Hashable {
    public let text: String
    /// When it becomes current, the offset applied; nil in unsynced lyrics.
    public let start: Duration?

    public init(text: String, startMilliseconds: Int64?) {
        self.text = text
        start = startMilliseconds.map { .milliseconds($0) }
    }
}

public struct DulcetLyricsDocument: Sendable, Hashable {
    public let synced: Bool
    public let lines: [DulcetLyricsLine]
    public let language: String?
    /// Some of the server's layers were too large to keep: say so.
    public let trimmed: Bool
    private let cursorAt: @Sendable (Int64) -> DulcetLyricsCursor

    public init(
        synced: Bool,
        lines: [DulcetLyricsLine],
        language: String?,
        trimmed: Bool,
        cursor: @escaping @Sendable (Int64) -> DulcetLyricsCursor
    ) {
        self.synced = synced
        self.lines = lines
        self.language = language
        self.trimmed = trimmed
        cursorAt = cursor
    }

    /// The core's current line group at `position` of media time; `.none` when unsynced.
    public func cursor(at position: Duration) -> DulcetLyricsCursor {
        guard synced else { return .none }
        let milliseconds = position.components.seconds * 1_000 + position.components.attoseconds / 1_000_000_000_000_000
        return cursorAt(milliseconds)
    }

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.synced == rhs.synced && lhs.lines == rhs.lines && lhs.language == rhs.language && lhs.trimmed == rhs.trimmed
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(synced)
        hasher.combine(lines)
        hasher.combine(language)
        hasher.combine(trimmed)
    }
}

public enum DulcetLyricsContent: Sendable, Hashable {
    case lyrics(DulcetLyricsDocument)
    /// The server has no lyrics to show for this track: a fact, never a spinner.
    case none
    /// Nothing stored and nothing could be read; the freshness says why.
    case unavailable
}

public struct DulcetLyricsPublication: Sendable, Hashable {
    public let trackRawID: String
    public let content: DulcetLyricsContent
    public let freshness: DulcetReaderFreshness
    /// The endpoint's breaker is open and nothing was sent (§10.4).
    public let breakerOpen: Bool
    /// Why the read never ran, when it did not.
    public let error: DulcetReaderErrorKind?

    public init(
        trackRawID: String,
        state: String,
        document: DulcetLyricsDocument?,
        freshness: DulcetReaderFreshness,
        breakerOpen: Bool,
        errorKind: String?
    ) {
        self.trackRawID = trackRawID
        switch state {
        case "lyrics": content = document.map(DulcetLyricsContent.lyrics) ?? .unavailable
        case "none": content = .none
        default: content = .unavailable
        }
        self.freshness = freshness
        self.breakerOpen = breakerOpen
        error = errorKind.map { DulcetReaderErrorKind(coreName: $0) }
    }
}

@MainActor
public protocol DulcetLyricsReading: AnyObject {
    /// The completion is called exactly once.
    func readLyrics(
        _ request: DulcetLyricsRequest,
        mode: DulcetLyricsReadMode,
        completion: @escaping @MainActor (DulcetLyricsPublication) -> Void
    )
}

// MARK: - What the panel shows

public enum DulcetLyricsPanelState: Sendable, Hashable {
    case loading
    case synced(DulcetLyricsDocument)
    case plain(DulcetLyricsDocument)
    case none
    /// Nothing to show; `message` says why and `offersRetry` whether trying again can help.
    case unavailable(message: String, offersRetry: Bool)
}

public enum DulcetLyricsPresentation {
    public static func state(_ publication: DulcetLyricsPublication?, loading: Bool) -> DulcetLyricsPanelState {
        guard let publication else { return .loading }
        switch publication.content {
        case let .lyrics(document):
            return document.synced ? .synced(document) : .plain(document)
        case .none:
            return .none
        case .unavailable:
            // The stored document had nothing, and a live read is still on its way.
            if loading { return .loading }
            switch publication.freshness {
            case .unavailable(.notCachedOffline):
                return .unavailable(message: DulcetStrings.lyricsOffline, offersRetry: false)
            case let .unavailable(.failed(error)):
                return .unavailable(
                    message: DulcetStrings.lyricsFailed(DulcetStrings.readerErrorPhrase(error)),
                    offersRetry: true
                )
            default:
                return .unavailable(message: DulcetStrings.lyricsUnavailable, offersRetry: true)
            }
        }
    }

    /// The line under lyrics that are shown but may not be current; nil when they are live.
    public static func status(_ publication: DulcetLyricsPublication?) -> String? {
        guard let publication, case .lyrics = publication.content else { return nil }
        switch publication.freshness {
        case .cached(.offline, _): return DulcetStrings.lyricsShownOffline
        case let .cached(.failed(error), _): return DulcetStrings.lyricsShownFailed(DulcetStrings.readerErrorPhrase(error))
        default: return nil
        }
    }
}

/// One track's lyrics while a panel shows them: the stored document at once, then the live read.
@MainActor
@Observable
public final class DulcetLyricsModel {
    public private(set) var publication: DulcetLyricsPublication?
    public private(set) var loading = false
    public private(set) var request: DulcetLyricsRequest?
    @ObservationIgnored private var generation = 0

    public init() {}

    public var state: DulcetLyricsPanelState { DulcetLyricsPresentation.state(publication, loading: loading) }

    /// Reads the same track again from a new reader -- after a reconnect -- keeping what is shown
    /// until the answer arrives. Every earlier read's answer is dropped (§18.4's bridge rule).
    public func reload(from reader: (any DulcetLyricsReading)?) {
        guard let request, let reader else { return }
        generation += 1
        let current = generation
        loading = true
        reader.readLyrics(request, mode: .read) { [weak self] live in
            guard let self, self.generation == current else { return }
            self.publication = live
            self.loading = false
        }
    }

    /// Loads `request` from `reader`; a different track drops what the last one showed.
    public func load(_ request: DulcetLyricsRequest, from reader: (any DulcetLyricsReading)?) {
        guard request != self.request || publication == nil else { return }
        self.request = request
        publication = nil
        generation += 1
        let current = generation
        guard let reader else {
            publication = DulcetLyricsPublication(
                trackRawID: request.trackRawID, state: "unavailable", document: nil,
                freshness: .unavailable(.internalFailure), breakerOpen: false, errorKind: "internalFailure"
            )
            return
        }
        loading = true
        reader.readLyrics(request, mode: .cached) { [weak self] cached in
            guard let self, self.generation == current else { return }
            // A stored document paints at once; the live read replaces it.
            if self.publication == nil { self.publication = cached }
        }
        reader.readLyrics(request, mode: .read) { [weak self] live in
            guard let self, self.generation == current else { return }
            self.publication = live
            self.loading = false
        }
    }

    public func retry(from reader: (any DulcetLyricsReading)?) {
        guard let request, let reader else { return }
        generation += 1
        let current = generation
        loading = true
        reader.readLyrics(request, mode: .retry) { [weak self] live in
            guard let self, self.generation == current else { return }
            self.publication = live
            self.loading = false
        }
    }
}

extension DulcetStrings {
    static let lyrics = dynamicText("lyrics.title", fallback: "Lyrics")
    static let lyricsShow = dynamicText("lyrics.show", fallback: "Show Lyrics")
    static let lyricsHide = dynamicText("lyrics.hide", fallback: "Hide Lyrics")
    static let lyricsNone = dynamicText("lyrics.none", fallback: "No lyrics for this song.")
    static let lyricsLoading = dynamicText("lyrics.loading", fallback: "Loading lyrics")
    static let lyricsOffline = dynamicText("lyrics.offline", fallback: "Lyrics for this song haven\u{2019}t been loaded on this device, and your server can\u{2019}t be reached.")
    static let lyricsUnavailable = dynamicText("lyrics.unavailable", fallback: "Lyrics couldn\u{2019}t be loaded.")
    static let lyricsShownOffline = dynamicText("lyrics.shownOffline", fallback: "Offline \u{2014} showing lyrics saved on this device.")
    static let lyricsTrimmed = dynamicText("lyrics.trimmed", fallback: "Some of this song\u{2019}s lyrics were too large to show.")
    static let lyricsTryAgain = dynamicText("lyrics.tryAgain", fallback: "Try Again")

    static func lyricsFailed(_ phrase: String) -> String {
        dynamicFormatted("lyrics.failed", fallback: "Lyrics couldn\u{2019}t be loaded \u{2014} %@", phrase)
    }

    static func lyricsShownFailed(_ phrase: String) -> String {
        dynamicFormatted("lyrics.shownFailed", fallback: "Showing saved lyrics \u{2014} %@", phrase)
    }
}
