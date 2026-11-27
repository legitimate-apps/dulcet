#if os(macOS) || os(iOS) || os(tvOS)
import Foundation

/// Why a Top Pick is on Listen Now: the line over its card, said the way the listener would.
enum DulcetListenNowPickReason: Hashable, Sendable {
    /// A mix built from the listener's own plays, stars and ratings.
    case madeForYou
    /// The album or playlist played most recently.
    case jumpBackIn
    /// A station of one artist the listener plays most.
    case becauseYouPlay(artist: String)
    /// A station of one genre the listener plays most.
    case becauseYouLove(genre: String)
    /// Loved or played often, and not heard for a while.
    case rediscover
    /// Played more than anything else.
    case onRepeat
    /// The newest album on the server.
    case justAdded

    var text: String {
        switch self {
        case .madeForYou: DulcetStrings.homePickMadeForYou
        case .jumpBackIn: DulcetStrings.homePickJumpBackIn
        case let .becauseYouPlay(artist): DulcetStrings.homePickBecauseYouPlay(artist)
        case let .becauseYouLove(genre): DulcetStrings.homePickBecauseYouLove(genre)
        case .rediscover: DulcetStrings.homePickRediscover
        case .onRepeat: DulcetStrings.homePickOnRepeat
        case .justAdded: DulcetStrings.homePickJustAdded
        }
    }
}

/// One large card at the top of Listen Now. A mix plays its tracks and opens its shelf; a
/// collection plays and opens the album or playlist it is.
struct DulcetListenNowPick: Identifiable, Hashable, Sendable {
    enum Content: Hashable, Sendable {
        case mix(DulcetListenNowShelf, [DulcetReaderItem])
        case collection(DulcetReaderItem)
    }

    let reason: DulcetListenNowPickReason
    let title: String
    let subtitle: String
    let content: Content

    var id: String {
        switch content {
        case let .mix(shelf, _): "mix.\(shelf.rawValue)"
        case let .collection(item): "\(item.kind.rawValue).\(item.id.rawID)"
        }
    }

    /// Up to four distinct covers: a mix shows the albums it draws from, a collection its own.
    var artworks: [DulcetArtwork] {
        switch content {
        case let .collection(item): return [item.artwork]
        case let .mix(_, tracks):
            var seen = Set<String>()
            let covered = tracks.filter { $0.artworkKey != nil && seen.insert($0.albumID?.rawID ?? $0.artworkKey ?? "").inserted }
            let artworks = covered.prefix(4).map(\.artwork)
            if artworks.isEmpty, let first = tracks.first { return [first.artwork] }
            return artworks.count == 4 ? artworks : Array(artworks.prefix(1))
        }
    }

    var playableMixTracks: [DulcetTrack] {
        guard case let .mix(_, tracks) = content else { return [] }
        return tracks.filter { !$0.isUnavailableOffline }.compactMap(\.playableTrack)
    }

    /// The page the card opens.
    var route: DulcetReaderRoute? {
        switch content {
        case let .mix(shelf, _): .listenNowShelf(shelf)
        case let .collection(item): item.route
        }
    }
}

/// Which shelves become Top Picks, and in what order. Pure, so the choice is pinned by tests.
///
/// The cards come only from shelves that drew something: Dulcet Server's own Home answers every
/// shelf; a plain OpenSubsonic server answers recent, heavy rotation, new, favourites and a
/// similar-songs station, and the hero is built from those. A listener with no history and a
/// server with nothing personal gets no hero at all, never a card dressed up from random music.
enum DulcetListenNowPicks {
    /// Enough to page through, few enough that each still means something.
    static let maximum = 6
    /// A mix shorter than this is not worth a card of its own.
    static let minimumMixTracks = 3

    static func plan(_ shelves: [DulcetListenNowShelf: [DulcetReaderItem]]) -> [DulcetListenNowPick] {
        var picks: [DulcetListenNowPick] = []
        var usedCollections = Set<DulcetProviderItemID>()

        func mix(_ shelf: DulcetListenNowShelf, reason: (([DulcetReaderItem]) -> DulcetListenNowPickReason?)? = nil) {
            let tracks = (shelves[shelf] ?? []).filter { $0.kind == .track }
            guard tracks.count >= minimumMixTracks else { return }
            let chosen = reason?(tracks) ?? .madeForYou
            picks.append(DulcetListenNowPick(
                reason: chosen,
                title: mixTitle(shelf, tracks: tracks),
                subtitle: artistsLine(tracks),
                content: .mix(shelf, tracks)
            ))
        }
        func collection(_ shelf: DulcetListenNowShelf, reason: DulcetListenNowPickReason) {
            let candidates = (shelves[shelf] ?? []).filter { ($0.kind == .album || $0.kind == .playlist) }
            guard let item = candidates.first(where: { !usedCollections.contains($0.id) }) else { return }
            usedCollections.insert(item.id)
            picks.append(DulcetListenNowPick(
                reason: reason,
                title: item.displayTitle,
                subtitle: item.kind == .playlist ? (item.owner ?? String()) : (item.artistName ?? String()),
                content: .collection(item)
            ))
        }

        // A mix made from the listener's own taste leads, as Music's Top Picks do; then the music
        // they were last in the middle of; then the stations named after what they play.
        mix(.dailyMix)
        if shelves[.recent]?.isEmpty == false {
            collection(.recent, reason: .jumpBackIn)
        } else {
            collection(.recentPlaylists, reason: .jumpBackIn)
        }
        mix(.artistStation) { tracks in stationArtist(tracks).map { .becauseYouPlay(artist: $0) } }
        collection(.rediscover, reason: .rediscover)
        mix(.genreStation) { tracks in stationGenre(tracks).map { .becauseYouLove(genre: $0) } }
        mix(.favourites)
        collection(.frequent, reason: .onRepeat)
        collection(.newest, reason: .justAdded)
        return Array(picks.prefix(maximum))
    }

    /// The one artist every track of a station shares, or nil when it mixes artists (a plain
    /// server's similar-songs station does).
    static func stationArtist(_ tracks: [DulcetReaderItem]) -> String? {
        shared(tracks.map(\.artistName))
    }

    /// The one genre every track of a station shares, or nil.
    static func stationGenre(_ tracks: [DulcetReaderItem]) -> String? {
        shared(tracks.map(\.genre))
    }

    /// A shelf's heading, named after what it is drawn from when that is one artist or genre:
    /// "More from Pavement" says more than "From Your Artists".
    static func shelfTitle(_ shelf: DulcetListenNowShelf, items: [DulcetReaderItem]) -> String {
        switch shelf {
        case .artistStation:
            stationArtist(items).map(DulcetStrings.homeMoreFrom) ?? shelf.title
        case .genreStation:
            stationGenre(items).map(DulcetStrings.homeMoreGenre) ?? shelf.title
        default:
            shelf.title
        }
    }

    private static func mixTitle(_ shelf: DulcetListenNowShelf, tracks: [DulcetReaderItem]) -> String {
        switch shelf {
        case .artistStation: stationArtist(tracks).map(DulcetStrings.homeArtistMix) ?? shelf.title
        case .genreStation: stationGenre(tracks).map(DulcetStrings.homeGenreMix) ?? shelf.title
        case .favourites: DulcetStrings.homeFavouritesMix
        default: shelf.title
        }
    }

    /// The first few artists a mix plays, most frequent first, as a listener reads them.
    static func artistsLine(_ tracks: [DulcetReaderItem]) -> String {
        var counts: [String: Int] = [:]
        var order: [String] = []
        for name in tracks.compactMap(\.artistName) where !name.isEmpty {
            if counts[name] == nil { order.append(name) }
            counts[name, default: 0] += 1
        }
        let ranked = order.enumerated()
            .sorted { lhs, rhs in
                let (l, r) = (counts[lhs.element]!, counts[rhs.element]!)
                return l != r ? l > r : lhs.offset < rhs.offset
            }
            .map(\.element)
        switch ranked.count {
        case 0: return String()
        case 1...3: return ListFormatter.localizedString(byJoining: ranked)
        default: return DulcetStrings.homeArtistsAndMore(ranked.prefix(3).joined(separator: ", "))
        }
    }

    private static func shared(_ values: [String?]) -> String? {
        guard let first = values.first.flatMap({ $0 }), !first.isEmpty,
              values.allSatisfy({ $0 == first }) else { return nil }
        return first
    }
}
#endif
