import DulcetCore
import DulcetKit
import Foundation

extension DulcetCoreLibraryReader: DulcetMyLibraryReading {
    func subscribeMyLibrary(_ handler: @escaping @MainActor (DulcetMyLibrarySnapshot) -> Void) -> any DulcetLibraryReaderCancellable {
        let library = myLibraryClient ?? AppleMyLibraryClient(reader: client)
        myLibraryClient = library
        let listener = DulcetCoreMyLibraryListener { [weak self] publication in
            guard let self else { return }
            self.myLibraryPublication = publication.value
            handler(Self.copyMyLibrary(publication.value, serverID: self.myLibraryServerID))
        }
        let subscription = library.subscribe(sort: "recentlyAdded", listener: listener)
        myLibraryCoreSubscription = subscription
        return DulcetCoreMyLibraryCancellation(listener: listener) { subscription.close() }
    }
    func setMyLibrarySort(_ sort: DulcetMyLibrarySort) { myLibraryCoreSubscription?.setSort(sort: sort.rawValue) }
    func refreshMyLibrary() { myLibraryClient?.refresh { _ in } }
    func myLibraryMembership(_ target: DulcetFavouriteTarget) -> DulcetMyLibraryMembership {
        myLibraryPublication.map {
            DulcetMyLibraryMembership(rawValue: $0.membership(kind: target.kind.rawValue, rawId: target.id.rawID)) ?? .unknown
        } ?? .unknown
    }
    func setMyLibraryMembership(_ target: DulcetFavouriteTarget, added: Bool) -> Bool {
        guard let library = myLibraryClient else { return false }
        return added ? library.add(kind: target.kind.rawValue, rawId: target.id.rawID) : library.remove(kind: target.kind.rawValue, rawId: target.id.rawID)
    }

    private static func copyMyLibrary(_ publication: AppleMyLibraryPublication, serverID: String) -> DulcetMyLibrarySnapshot {
        func item(kind: String, id: String, title: String, artist: String? = nil, artistID: String? = nil,
                  album: String? = nil, albumID: String? = nil, year: Int? = nil, artwork: String?,
                  songCount: Int? = nil, albumCount: Int? = nil, duration: Int64? = nil) -> DulcetReaderItem {
            DulcetReaderItem(kind: kind, providerInstanceID: serverID, rawID: id, title: title,
                artistName: artist, artistRawID: artistID, albumTitle: album, albumRawID: albumID,
                year: year, genre: nil, durationMilliseconds: duration, songCount: songCount,
                albumCount: albumCount, discNumber: nil, trackNumber: nil, sourceContainer: nil,
                artworkKey: artwork, owner: nil, favourite: nil, rating: nil, playCount: nil,
                playability: nil, detailComplete: false, metadataMissing: false)!
        }
        return DulcetMyLibrarySnapshot(
            availability: DulcetMyLibraryAvailability(rawValue: publication.availability) ?? .unknown,
            syncState: publication.syncState,
            error: publication.errorKind.map { DulcetReaderErrorKind(coreName: $0) },
            lastReadAt: publication.lastReadAtEpochMillis > 0 ? Date(timeIntervalSince1970: Double(publication.lastReadAtEpochMillis) / 1000) : nil,
            albums: publication.albums.map {
                item(kind: "album", id: $0.rawId, title: $0.title, artist: $0.artistName, artistID: $0.artistRawId,
                     year: $0.year > 0 ? Int($0.year) : nil, artwork: $0.artworkKey,
                     songCount: $0.songCount >= 0 ? Int($0.songCount) : nil)
            },
            artists: publication.artists.map {
                item(kind: "artist", id: $0.rawId, title: $0.name, artwork: $0.artworkKey,
                     albumCount: $0.albumCount >= 0 ? Int($0.albumCount) : nil)
            },
            songs: publication.songs.map {
                item(kind: "track", id: $0.rawId, title: $0.title, artist: $0.artistName,
                     album: $0.albumTitle, albumID: $0.albumRawId, artwork: $0.artworkKey,
                     duration: $0.durationMilliseconds >= 0 ? $0.durationMilliseconds : nil)
            })
    }
}

/// The facade delivers immutable publications on the main thread; only the adapter retains
/// the Kotlin membership index. Swift values copied from it are what the UI receives.
private struct DulcetCoreMyLibraryDelivery: @unchecked Sendable {
    let value: AppleMyLibraryPublication
}

private final class DulcetCoreMyLibraryListener: NSObject, AppleMyLibraryListener, @unchecked Sendable {
    private let handler: @MainActor (DulcetCoreMyLibraryDelivery) -> Void
    init(handler: @escaping @MainActor (DulcetCoreMyLibraryDelivery) -> Void) { self.handler = handler }
    func onMyLibraryPublication(publication: AppleMyLibraryPublication) {
        let delivery = DulcetCoreMyLibraryDelivery(value: publication)
        MainActor.assumeIsolated { handler(delivery) }
    }
}
@MainActor
private final class DulcetCoreMyLibraryCancellation: DulcetLibraryReaderCancellable {
    private let listener: AnyObject
    private var action: (() -> Void)?
    init(listener: AnyObject, action: @escaping () -> Void) { self.listener = listener; self.action = action }
    func cancel() { action?(); action = nil }
}
