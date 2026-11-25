import Foundation
import Testing
@testable import DulcetKit

// Library's home under its sections: Recently Added once the person has added something; before
// that their playlists and downloads; the Browse invitation only when they have none of these.

private func homeItem(_ kind: String, _ rawID: String) -> DulcetReaderItem {
    DulcetReaderItem(
        kind: kind, providerInstanceID: "provider-home", rawID: rawID, title: rawID,
        artistName: "Artist", artistRawID: "artist-1", albumTitle: "Album", albumRawID: "album-1",
        year: 2001, genre: nil, durationMilliseconds: 180_000, songCount: nil, albumCount: nil,
        discNumber: 1, trackNumber: 1, sourceContainer: "Mp3", artworkKey: nil, owner: nil,
        favourite: nil, rating: nil, playCount: nil, playability: "streamable",
        detailComplete: true, metadataMissing: false, discographyGroup: nil
    )!
}

private func homeWindow(_ items: [DulcetReaderItem], freshness: DulcetReaderFreshness = .live,
                        itemsState: String = "present") -> DulcetLibraryWindow {
    DulcetLibraryWindow(sequence: 1, freshness: freshness, coverage: "complete", total: items.count,
        leadingOffset: 0, header: nil, items: items, itemsState: itemsState, order: "server",
        anchorRawID: nil, anchorIndex: nil)
}

private let emptyLibrary = DulcetMyLibrarySnapshot(availability: .available, syncState: "cached")
private let playlist = homeWindow([homeItem("playlist", "mix")])
private let downloadedAlbum = homeWindow([homeItem("album", "kept")])
private let none = homeWindow([])

@Test
func shelfStateReadsAnsweredEmptyApartFromStillLoading() {
    #expect(DulcetLibraryHomeContent.Shelf(nil) == .pending)
    #expect(DulcetLibraryHomeContent.Shelf(homeWindow([], freshness: .loading)) == .pending)
    #expect(DulcetLibraryHomeContent.Shelf(homeWindow([], itemsState: "loading")) == .pending)
    #expect(DulcetLibraryHomeContent.Shelf(none) == .empty)
    #expect(DulcetLibraryHomeContent.Shelf(homeWindow([], freshness: .unavailable(.notCachedOffline))) == .empty)
    // Items cached while a live read is in flight are still the person's music.
    #expect(DulcetLibraryHomeContent.Shelf(homeWindow([homeItem("playlist", "p")], freshness: .loading)) == .filled)
}

@Test
func addedMusicShowsRecentlyAddedWhateverElseThePersonHas() {
    var library = emptyLibrary
    library.albums = [homeItem("album", "added")]
    #expect(DulcetLibraryHomeContent.resolve(library: library, playlists: .filled, downloads: .filled) == .recentlyAdded)
    #expect(DulcetLibraryHomeContent.resolve(library: library, playlists: .pending, downloads: .empty) == .recentlyAdded)
    var songsOnly = emptyLibrary
    songsOnly.songs = [homeItem("track", "s")]
    #expect(DulcetLibraryHomeContent.resolve(library: songsOnly, playlists: .empty, downloads: .empty) == .recentlyAdded)
}

@Test
func anEmptyLibraryShowsThePersonsPlaylistsAndDownloadsInsteadOfTheInvitation() {
    let p = DulcetLibraryHomeContent.Shelf(playlist)
    let d = DulcetLibraryHomeContent.Shelf(downloadedAlbum)
    let e = DulcetLibraryHomeContent.Shelf(none)
    #expect(DulcetLibraryHomeContent.resolve(library: emptyLibrary, playlists: p, downloads: d) == .yourMusic(playlists: true, downloads: true))
    #expect(DulcetLibraryHomeContent.resolve(library: emptyLibrary, playlists: p, downloads: e) == .yourMusic(playlists: true, downloads: false))
    #expect(DulcetLibraryHomeContent.resolve(library: emptyLibrary, playlists: e, downloads: d) == .yourMusic(playlists: false, downloads: true))
    // What is known already shows while the other read is still out.
    #expect(DulcetLibraryHomeContent.resolve(library: emptyLibrary, playlists: .pending, downloads: d) == .yourMusic(playlists: false, downloads: true))
    // Even during My Library's own sync, music the person already has is shown.
    var syncing = emptyLibrary
    syncing.syncState = "syncing"
    #expect(DulcetLibraryHomeContent.resolve(library: syncing, playlists: p, downloads: e) == .yourMusic(playlists: true, downloads: false))
}

@Test
func theInvitationAppearsOnlyWhenEveryReadAnsweredEmpty() {
    #expect(DulcetLibraryHomeContent.resolve(library: emptyLibrary, playlists: .empty, downloads: .empty) == .invitation)
    #expect(DulcetLibraryHomeContent.resolve(library: emptyLibrary, playlists: .pending, downloads: .empty) == .waiting)
    #expect(DulcetLibraryHomeContent.resolve(library: emptyLibrary, playlists: .empty, downloads: .pending) == .waiting)
    var syncing = emptyLibrary
    syncing.syncState = "syncing"
    #expect(DulcetLibraryHomeContent.resolve(library: syncing, playlists: .empty, downloads: .empty) == .waiting)
}
