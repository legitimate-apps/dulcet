import Foundation

/// All external playback surfaces enter the existing store; the queue and reader keep ownership.
@MainActor
public final class DulcetSystemActions {
    public static let shared = DulcetSystemActions()
    public weak var store: DulcetPresentationStore?
    public init(store: DulcetPresentationStore? = nil) { self.store = store }

    public enum Failure: LocalizedError {
        case connect, nothingPlaying, notFound, ambiguous, unavailable, changedAccount
        public var errorDescription: String? {
            switch self {
            case .connect: "Open Dulcet and connect your music server first."
            case .nothingPlaying: "There is no song in the current queue."
            case .notFound: "No matching music was found. Try a more specific name."
            case .ambiguous: "Several items have that name. Choose one in Dulcet."
            case .unavailable: "That music or action is unavailable right now. Open Dulcet to try again."
            case .changedAccount: "The active account changed. Try again."
            }
        }
    }

    public func transport(_ command: DulcetPlaybackControlIntent) async throws {
        _ = try await readySession()
        guard let store else { throw Failure.connect }
        guard store.snapshot.nowPlaying != nil else { throw Failure.nothingPlaying }
        store.sendPlaybackControl(command)
    }

    public func favouriteCurrent() async throws {
        _ = try await readySession()
        guard let store, let session = store.librarySession else { throw Failure.connect }
        guard let (target, favourite) = store.playingTrackFavourite else { throw Failure.nothingPlaying }
        // "Add" is idempotent, unlike the lock screen's toggle.
        if !favourite, !session.setFavourite(target, favourite: true) { throw Failure.unavailable }
    }

    public func window(_ query: DulcetLibraryQuery, minimumCount: Int = 0) async throws -> DulcetLibraryWindow {
        let session = try await readySession()
        let generation = session.readerGeneration
        var latest: DulcetLibraryWindow?
        guard let reader = session.reader else { throw Failure.connect }
        let subscription = reader.subscribeWindow(query) { latest = $0 }
        defer { subscription.close() }
        var extendedCount: Int?
        for _ in 0..<300 {
            try Task.checkCancellation()
            guard session.readerGeneration == generation else { throw Failure.changedAccount }
            if let latest {
                switch latest.freshness {
                case .live, .cached:
                    if latest.itemsState != .loading, query != .shuffleSongs || latest.freshness == .live {
                        if latest.items.count >= minimumCount || !latest.growsAtEnd { return latest }
                        if extendedCount != latest.items.count {
                            extendedCount = latest.items.count
                            subscription.setViewport(first: 0, last: max(0, latest.items.count - 1))
                            subscription.loadMore()
                        }
                    }
                case .unavailable: throw Failure.unavailable
                case .loading: break
                }
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw Failure.unavailable
    }

    public func playRecent(provider: String, rawID: String, name: String) async throws {
        let session = try await readySession()
        guard provider == session.account?.providerInstanceID else { throw Failure.changedAccount }
        let generation = session.readerGeneration
        var latest: DulcetReaderSearchPublication?
        guard let subscription = session.subscribeSearch(onPublication: { latest = $0 }) else { throw Failure.connect }
        defer { subscription.close() }
        subscription.updateQuery(name)
        for _ in 0..<300 {
            try Task.checkCancellation()
            guard generation == session.readerGeneration else { throw Failure.changedAccount }
            if let latest, latest.query == name {
                // Identity replay may use a cached ID even when a one-letter title cannot query the server.
                if let row = latest.rows.first(where: { $0.id.providerInstanceID == provider && $0.id.rawID == rawID && $0.result.kind == .track }) {
                    guard !row.isUnavailableOffline, let track = row.result.playableTrack else { throw Failure.unavailable }
                    try enqueue([track], kind: .library, name: name, id: nil)
                    return
                }
                if latest.scope != .deviceWhileServerPending { throw Failure.unavailable }
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw Failure.unavailable
    }

    public func play(kind: DulcetReaderItemKind, name: String) async throws {
        let session = try await readySession()
        let generation = session.readerGeneration
        if kind == .playlist {
            let list = try await window(.playlists)
            let matches = list.items.filter { Self.sameName($0.title ?? "", name) }
            guard matches.count == 1, let item = matches.first else {
                throw matches.isEmpty ? Failure.notFound : Failure.ambiguous
            }
            try await play(item)
            return
        }
        var latest: DulcetReaderSearchPublication?
        guard let subscription = session.subscribeSearch(onPublication: { latest = $0 }) else { throw Failure.connect }
        defer { subscription.close() }
        subscription.updateQuery(name)
        for _ in 0..<300 {
            try Task.checkCancellation()
            guard generation == session.readerGeneration else { throw Failure.changedAccount }
            if let latest, latest.query == name, latest.scope != .deviceWhileServerPending {
                let matches = latest.rows.filter {
                    $0.result.kind.rawValue == kind.rawValue && Self.sameName($0.result.title, name)
                }
                guard matches.count == 1, let row = matches.first else {
                    throw matches.isEmpty ? Failure.notFound : Failure.ambiguous
                }
                switch kind {
                case .album: try await playContainer(.album(rawID: row.id.rawID), name: row.result.title, kind: .album, id: row.id)
                case .artist:
                    let artist = try await window(.artist(rawID: row.id.rawID))
                    try await playArtist(artist, name: row.result.title, id: row.id)
                case .track:
                    guard !row.isUnavailableOffline, let track = row.result.playableTrack else { throw Failure.unavailable }
                    try enqueue([track], kind: .search, name: row.result.title, id: nil)
                default: throw Failure.unavailable
                }
                return
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw Failure.unavailable
    }

    public func play(_ item: DulcetReaderItem) async throws {
        guard item.id.providerInstanceID == store?.librarySession?.account?.providerInstanceID else { throw Failure.changedAccount }
        switch item.kind {
        case .album: try await playContainer(.album(rawID: item.id.rawID), name: item.displayTitle, kind: .album, id: item.id)
        case .playlist: try await playContainer(.playlist(rawID: item.id.rawID), name: item.displayTitle, kind: .playlist, id: item.id)
        case .artist: try await playArtist(try await window(.artist(rawID: item.id.rawID)), name: item.displayTitle, id: item.id)
        case .track:
            guard let track = item.playableTrack, !item.isUnavailableOffline else { throw Failure.unavailable }
            try enqueue([track], kind: .library, name: item.displayTitle, id: nil)
        case .genre: throw Failure.unavailable
        }
    }

    public func shuffleLibrary() async throws {
        let session = try await readySession()
        let generation = session.readerGeneration
        for _ in 0..<100 where session.myLibrary.availability == .unknown {
            try Task.checkCancellation()
            guard session.readerGeneration == generation else { throw Failure.changedAccount }
            try await Task.sleep(for: .milliseconds(50))
        }
        if session.myLibrary.availability == .available {
            try enqueue(session.myLibrary.songs.filter { !$0.isUnavailableOffline }.compactMap(\.playableTrack), kind: .library, name: "My Library", id: nil, shuffle: true)
            return
        }
        guard session.myLibrary.availability != .unknown else { throw Failure.unavailable }
        // The shared reader obtains a fresh server-wide song sample, independent of seen albums.
        let songs = try await window(.shuffleSongs)
        try enqueue(songs.playableTracks, kind: .library, name: "Library Shuffle", id: nil, shuffle: true)
    }

    public func playFavourites() async throws {
        let publication = try await window(.favourites)
        try enqueue(publication.playableTracks, kind: .library, name: "Favourites", id: nil)
    }

    public func addCurrent(toPlaylist name: String) async throws {
        guard let track = store?.snapshot.nowPlaying?.current else { throw Failure.nothingPlaying }
        let list = try await window(.playlists)
        let matches = list.items.filter { $0.editable && Self.sameName($0.title ?? "", name) }
        guard matches.count == 1, let playlist = matches.first else {
            throw matches.isEmpty ? Failure.notFound : Failure.ambiguous
        }
        guard track.id.providerInstanceID == store?.librarySession?.account?.providerInstanceID,
              let editor = store?.librarySession?.playlists else { throw Failure.changedAccount }
        let result: DulcetPlaylistEditResult = await withCheckedContinuation { continuation in
            editor.perform(.append(playlistID: playlist.id.rawID, songs: [track.id.rawID])) { continuation.resume(returning: $0) }
        }
        guard result.record == .pending || result.record == .unchanged else { throw Failure.unavailable }
    }

    private func readySession() async throws -> DulcetLibrarySession {
        store?.prepareForExternalPlayback()
        for _ in 0..<200 {
            try Task.checkCancellation()
            if let session = store?.librarySession, session.reader != nil,
               store?.snapshot.accountConnection != .connecting { return session }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw Failure.connect
    }

    private func playContainer(_ query: DulcetLibraryQuery, name: String, kind: DulcetPlaybackQueueSourceKind, id: DulcetProviderItemID) async throws {
        let publication = try await window(query)
        try enqueue(publication.playableTracks, kind: kind, name: name, id: id)
    }

    private func playArtist(_ artist: DulcetLibraryWindow, name: String, id: DulcetProviderItemID) async throws {
        var tracks: [DulcetTrack] = []
        for album in artist.items where album.kind == .album {
            tracks += try await window(.album(rawID: album.id.rawID)).playableTracks
        }
        try enqueue(tracks, kind: .artist, name: name, id: id)
    }

    private func enqueue(_ tracks: [DulcetTrack], kind: DulcetPlaybackQueueSourceKind, name: String, id: DulcetProviderItemID?, shuffle: Bool = false) throws {
        guard let store, !tracks.isEmpty else { throw Failure.unavailable }
        guard tracks.allSatisfy({ $0.id.providerInstanceID == store.librarySession?.account?.providerInstanceID }) else { throw Failure.changedAccount }
        store.playTracks(.init(tracks: tracks, sourceKind: kind, sourceID: id, sourceDisplayName: name, startIndex: shuffle ? nil : 0, shuffle: shuffle))
    }

    nonisolated static func sameName(_ lhs: String, _ rhs: String) -> Bool {
        lhs.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            == rhs.trimmingCharacters(in: .whitespacesAndNewlines).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
    }
}
