import DulcetCore
import DulcetKit
import Foundation

// Playlist editing and lyrics over the core's facades (spec §18.6, §18.4): each call is copied
// into DulcetKit's values, whose initializers own every decision about a word (§7.1). The facades
// complete on the main thread; the adapters assert it rather than hop.

extension DulcetCoreLibraryReader: DulcetPlaylistEditing {
    private var playlists: AppleLibraryPlaylistClient {
        if let playlistClient { return playlistClient }
        let made = AppleLibraryPlaylistClient(reader: client)
        playlistClient = made
        return made
    }

    func editPlaylist(_ edit: DulcetPlaylistEdit, completion: @escaping @MainActor (DulcetPlaylistEditResult) -> Void) {
        let done: (AppleLibraryPlaylistEditResult) -> Void = { result in
            let copy = DulcetPlaylistEditResult(record: result.record, localID: result.localId, errorKind: result.errorKind)
            MainActor.assumeIsolated { completion(copy) }
        }
        switch edit {
        case let .create(name, songs):
            _ = playlists.create(name: name, songRawIds: songs, completion: done)
        case let .rename(playlistID, name):
            _ = playlists.rename(playlistId: playlistID, name: name, completion: done)
        case let .append(playlistID, songs):
            _ = playlists.append(playlistId: playlistID, songRawIds: songs, completion: done)
        case let .appendAlbum(playlistID, albumID):
            _ = playlists.appendAlbum(playlistId: playlistID, albumRawId: albumID, completion: done)
        case let .remove(playlistID, indices, expected):
            _ = playlists.remove(
                playlistId: playlistID,
                indices: indices.map { KotlinInt(value: Int32(clamping: $0)) },
                expectedEntries: expected,
                completion: done
            )
        case let .move(playlistID, from, to, expected):
            _ = playlists.move(
                playlistId: playlistID,
                from: Int32(clamping: from),
                to: Int32(clamping: to),
                expectedEntries: expected,
                completion: done
            )
        case let .delete(playlistID):
            _ = playlists.delete(playlistId: playlistID, completion: done)
        case let .withdraw(playlistID, change):
            _ = playlists.withdraw(playlistId: playlistID, change: change.rawValue, completion: done)
        case let .chooseCreated(localID, playlistID):
            _ = playlists.chooseCreated(localId: localID, playlistId: playlistID, completion: done)
        }
    }

    func pendingPlaylistChanges(completion: @escaping @MainActor ([DulcetPlaylistPendingChange]?) -> Void) {
        _ = playlists.pendingChanges { result in
            // A read that failed is not "nothing pending": the editor keeps what it asks.
            let copy: [DulcetPlaylistPendingChange]? = result.errorKind != nil ? nil : result.changes.map {
                DulcetPlaylistPendingChange(playlistID: $0.playlistId, change: $0.change, name: $0.name, candidates: $0.candidates)
            }
            MainActor.assumeIsolated { completion(copy) }
        }
    }

    func subscribePlaylistOutcomes(
        _ handler: @escaping @MainActor (DulcetPlaylistOutcome) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        let listener = DulcetCorePlaylistOutcomeListener(handler: handler)
        let subscription = playlists.subscribeOutcomes(listener: listener)
        return DulcetCorePlaylistCancellable(listener: listener) { subscription.close() }
    }
}

extension DulcetCoreLibraryReader: DulcetLyricsReading {
    private var lyrics: AppleLibraryLyricsClient {
        if let lyricsClient { return lyricsClient }
        let made = AppleLibraryLyricsClient(reader: client, preferredLanguages: Locale.preferredLanguages)
        lyricsClient = made
        return made
    }

    func readLyrics(
        _ request: DulcetLyricsRequest,
        mode: DulcetLyricsReadMode,
        completion: @escaping @MainActor (DulcetLyricsPublication) -> Void
    ) {
        let track = AppleLibraryLyricsTrack(rawId: request.trackRawID, artist: request.artist, title: request.title)
        let done: (AppleLibraryLyricsPublication) -> Void = { publication in
            let copy = Self.copy(publication)
            MainActor.assumeIsolated { completion(copy) }
        }
        switch mode {
        case .cached: _ = lyrics.cached(track: track, completion: done)
        case .read: _ = lyrics.read(track: track, completion: done)
        case .retry: _ = lyrics.retry(track: track, completion: done)
        }
    }

    nonisolated static func copy(_ publication: AppleLibraryLyricsPublication) -> DulcetLyricsPublication {
        let document = publication.layer.map { layer in
            let cursor = DulcetCoreLyricsCursor(layer: layer)
            return DulcetLyricsDocument(
                synced: publication.synced,
                lines: publication.lines.map {
                    DulcetLyricsLine(text: $0.text, startMilliseconds: $0.effectiveStartMilliseconds?.int64Value)
                },
                language: publication.language,
                trimmed: publication.trimmed,
                cursor: { cursor.at($0) }
            )
        }
        return DulcetLyricsPublication(
            trackRawID: publication.trackRawId,
            state: publication.state,
            document: document,
            freshness: copy(publication.freshness),
            breakerOpen: publication.breakerOpen,
            errorKind: publication.errorKind
        )
    }
}

/// The core's cursor over one immutable layer. Unchecked because the layer is an immutable Kotlin
/// value whose one lazily built index is synchronized in the core.
private final class DulcetCoreLyricsCursor: @unchecked Sendable {
    private let layer: LyricsLayer

    init(layer: LyricsLayer) {
        self.layer = layer
    }

    func at(_ milliseconds: Int64) -> DulcetLyricsCursor {
        let cursor = layer.cursorAtMilliseconds(positionMilliseconds: milliseconds)
        return DulcetLyricsCursor(index: Int(cursor.index), lastIndex: Int(cursor.lastIndex), isInterlude: cursor.isInterlude)
    }
}

private final class DulcetCorePlaylistOutcomeListener: NSObject, AppleLibraryPlaylistOutcomeListener, @unchecked Sendable {
    private let handler: @MainActor (DulcetPlaylistOutcome) -> Void

    init(handler: @escaping @MainActor (DulcetPlaylistOutcome) -> Void) {
        self.handler = handler
    }

    func onPlaylistOutcome(outcome: AppleLibraryPlaylistOutcome) {
        guard let copy = DulcetPlaylistOutcome(
            kind: outcome.kind,
            playlistID: outcome.playlistId,
            change: outcome.change,
            localID: outcome.localId,
            name: outcome.name,
            candidates: outcome.candidates,
            fields: outcome.fields,
            errorKind: outcome.errorKind
        ) else { return }
        MainActor.assumeIsolated { handler(copy) }
    }
}

@MainActor
private final class DulcetCorePlaylistCancellable: DulcetLibraryReaderCancellable {
    private let listener: AnyObject
    private var action: (() -> Void)?

    init(listener: AnyObject, action: @escaping () -> Void) {
        self.listener = listener
        self.action = action
    }

    func cancel() {
        action?()
        action = nil
    }
}
