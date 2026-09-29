import Foundation

/// The words for what the reader published (spec §16.14, §16.15). One place, used by every Apple
/// destination, so no two screens can say different things about the same state. Nothing here
/// decides anything: every input is a value the core computed.
enum DulcetReaderCopy {
    enum Subject {
        case album
        case list
        /// An album whose header is known and whose track list is not.
        case albumTracks
    }

    /// The one line shown with a publication's content, or nil for `live` content, for `loading`
    /// (which shows an indicator instead), and for `unavailable` (which says so in place of the
    /// content). Cached content always says how old it is.
    static func freshnessLine(_ freshness: DulcetReaderFreshness, now: Date = Date()) -> String? {
        guard case let .cached(reason, asOf) = freshness else { return nil }
        let seen = asOf.map { DulcetStrings.readerSeenAt(age($0, now: now)) } ?? DulcetStrings.readerSeenUnknownAge
        return DulcetStrings.readerSeenWithReason(seen, reasonPhrase(reason))
    }

    /// The statement shown instead of content when there is none -- never a spinner.
    static func unavailableLine(_ reason: DulcetReaderUnavailableReason, subject: Subject) -> String {
        switch reason {
        case .notCachedOffline:
            switch subject {
            case .album: DulcetStrings.readerUnavailableAlbum
            case .list: DulcetStrings.readerUnavailableList
            case .albumTracks: DulcetStrings.readerUnavailableAlbumTracks
            }
        case .gone:
            DulcetStrings.readerUnavailableGone
        case .failed(.closed):
            DulcetStrings.readerUnavailableClosed
        case let .failed(kind):
            DulcetStrings.readerUnavailableFailed(DulcetStrings.readerErrorPhrase(kind))
        case .internalFailure:
            DulcetStrings.readerUnavailableInternal
        }
    }

    /// A list's coverage, stated ABOVE the list (§16.14): an open window only while it cannot be
    /// extended -- offline, or sorted on this device -- and a scanning or changing server always.
    /// `unverifiedNoEpoch` is stated once per account, never here.
    static func coverageLine(_ window: DulcetLibraryWindow) -> String? {
        switch window.coverage {
        case .open:
            guard window.freshness.isOffline || window.order == .localView else { return nil }
            let shown = window.items.count
            let kind = countKind(window.items)
            if let total = window.total, total > shown {
                return DulcetStrings.readerCoverageOpen(
                    shown: DulcetStrings.groupedNumber(shown),
                    total: DulcetStrings.readerCount(kind, total)
                )
            }
            return DulcetStrings.readerCoverageOpen(shown: DulcetStrings.readerCount(kind, shown))
        case .unverifiedScanning:
            return DulcetStrings.readerCoverageScanning
        case .unverifiedChanging:
            return DulcetStrings.readerCoverageChanging
        case .unverifiedNoEpoch, .complete, nil:
            return nil
        }
    }

    /// "Available offline" above a list sorted on this device while offline (§16.14).
    static func orderLine(_ window: DulcetLibraryWindow) -> String? {
        window.order == .localView ? DulcetStrings.readerAvailableOffline : nil
    }

    /// The label of a search's scope (§16.15); nil when the server answered and nothing needs
    /// saying.
    static func searchScopeLabel(_ scope: DulcetReaderSearchScope?) -> String? {
        switch scope {
        case nil, .serverAndDevice:
            return nil
        case .deviceWhileServerPending:
            return DulcetStrings.readerSearchScopeDevice
        case let .deviceOffline(seen):
            guard let seen else { return DulcetStrings.readerSearchScopeDevice }
            return DulcetStrings.readerSearchScopeOffline(
                albums: Int(clamping: seen.albums),
                tracks: Int(clamping: seen.tracks)
            )
        case let .deviceServerFailed(kind, _):
            return DulcetStrings.readerSearchScopeFailed(DulcetStrings.readerErrorPhrase(kind))
        }
    }

    /// The connection's own failure, stated once above the screen while it keeps the reader
    /// offline (§16.14).
    static func connectionLine(_ failure: DulcetReaderErrorKind?) -> String? {
        failure.map { DulcetStrings.readerConnectionFailed(DulcetStrings.readerErrorPhrase($0)) }
    }

    static func countKind(_ items: [DulcetReaderItem]) -> DulcetReaderCountKind {
        guard let first = items.first?.kind, items.allSatisfy({ $0.kind == first }) else { return .items }
        return switch first {
        case .album: .albums
        case .artist: .artists
        case .track: .tracks
        case .playlist: .playlists
        case .genre: .items
        }
    }

    private static func reasonPhrase(_ reason: DulcetReaderCachedReason) -> String {
        switch reason {
        case .offline: DulcetStrings.readerReasonOffline
        case .revalidating: DulcetStrings.readerReasonRevalidating
        case .stale: DulcetStrings.readerReasonStale
        case .owed: DulcetStrings.readerReasonOwed
        case .internalFailure: DulcetStrings.readerReasonInternal
        case let .failed(kind): DulcetStrings.readerErrorPhrase(kind)
        }
    }

    private static func age(_ asOf: Date, now: Date) -> String {
        if now.timeIntervalSince(asOf) < 60 { return DulcetStrings.readerJustNow }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: asOf, relativeTo: now)
    }
}
