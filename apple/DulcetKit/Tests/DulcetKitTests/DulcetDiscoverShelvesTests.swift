import Foundation
import Testing
@testable import DulcetKit

@Suite @MainActor struct DulcetDiscoverShelvesTests {
    let age = DulcetDiscoverAge(receivedAt: Date(timeIntervalSince1970: 1000))
    func item(_ id: String = "opaque/ID") -> DulcetDiscoverItem {
        .init(id: id, kind: "song", target: .init(kind: "song", mbid: id), title: "Song", wishable: true,
              availability: .init(status: "notHeld", completeness: "unknown"))
    }
    @Test func independentEmptyLoadingFailureAndCachedStates() {
        let empty = DulcetDiscoverShelfState(id: "similarArtists", page: .init(id: "similarArtists", age: age))
        #expect(DulcetDiscoverShelfPresentation(empty).isEmpty)
        #expect(!DulcetDiscoverShelfPresentation(.init(id: empty.id, error: .init(code: "upstream-unavailable"))).isEmpty)
        #expect(!DulcetDiscoverShelfPresentation(.init(id: empty.id, page: empty.page, loading: true)).isEmpty)
        let failure = DulcetDiscoverFailure(code: "offline", retryable: true)
        let stale = DulcetDiscoverShelfState(id: "trendingSongs", page: .init(id: "trendingSongs", items: [item()], nextCursor: "cursor/+", age: age, refreshError: failure), error: failure)
        #expect(!DulcetDiscoverShelfPresentation(stale).isEmpty)
        #expect(DulcetDiscoverShelfPresentation(stale).showsAll)
        #expect(DulcetDiscoverShelfPresentation(stale).source == .init(type: "shelf", id: "trendingSongs"))
        let home = DulcetDiscoverHome(shelves: [empty, stale])
        #expect(home.shelves[0].error == nil && home.shelves[1].page?.items.count == 1)
    }
    @Test func nullablePlaylistMetadataAndDuplicatePositionsSurvivePaging() {
        let reader = DiscoverProofReader(), song = item()
        let model = DulcetDiscoverListModel(reader: reader, route: .playlist("opaque/Playlist"))
        model.open()
        let summary = DulcetDiscoverPlaylistSummary(id: "opaque/Playlist", title: "Playlist")
        reader.playlistCompletions[0](.success(.init(summary: summary, entries: [.init(position: 1, item: song), .init(position: 2, item: song)], nextCursor: "opaque+=/cursor", age: age)))
        #expect(model.playlist?.summary.creator == nil && model.playlist?.summary.trackCount == nil)
        model.more()
        #expect(reader.playlistRequests[1].1 == "opaque+=/cursor")
        reader.playlistCompletions[1](.success(.init(summary: summary, entries: [.init(position: 3, item: song)], age: age)))
        #expect(model.playlist?.entries.map(\.position) == [1, 2, 3])
        #expect(model.playlist?.entries.map(\.item.id) == [song.id, song.id, song.id])
        #expect(model.source == .init(type: "playlist", id: "opaque/Playlist"))
    }
    @Test func expiredCursorReplacesEntirePageAndReplacementFailureKeepsRows() {
        let reader = DiscoverProofReader()
        let model = DulcetDiscoverListModel(reader: reader, route: .shelf("trendingArtists"))
        model.open()
        reader.shelfCompletions[0](.success(.init(id: "trendingArtists", items: [item("old")], nextCursor: "expired", age: age)))
        model.more()
        reader.shelfCompletions[1](.failure(.init(code: "cursor-expired", retryable: true)))
        #expect(reader.shelfRequests.count == 3 && reader.shelfRequests[2].1 == nil)
        reader.shelfCompletions[2](.failure(.init(code: "offline", retryable: true)))
        #expect(model.shelf?.items.map(\.id) == ["old"] && model.error?.code == "offline")
        model.load()
        reader.shelfCompletions[3](.success(.init(id: "trendingArtists", items: [item("replacement")], age: age)))
        #expect(model.shelf?.items.map(\.id) == ["replacement"])
    }
    @Test func queryReplacementAndDepartureFenceLateResults() {
        let reader = DiscoverProofReader()
        let model = DulcetDiscoverListModel(reader: reader, route: .playlists)
        model.open(); model.query = "new"; model.submitQuery()
        #expect(reader.pageCancellationCount == 1)
        let summary = DulcetDiscoverPlaylistSummary(id: "new", title: "New", creator: nil, trackCount: nil)
        reader.playlistListCompletions[1](.success(.init(items: [summary], age: age)))
        reader.playlistListCompletions[0](.success(.init(items: [], age: age)))
        #expect(model.playlists?.items == [summary])
        model.load(); model.close()
        reader.playlistListCompletions[2](.success(.init(items: [], age: age)))
        #expect(model.playlists?.items == [summary] && !model.loading)
    }
    @Test func sourceAwareWishesUseExactItemAndRecentPendingOverlay() {
        let reader = DiscoverProofReader(), song = item()
        let model = DulcetDiscoverModel(reader: reader); model.open()
        model.add(song, source: .init(type: "shelf", id: "trendingSongs"))
        model.add(song, source: .init(type: "playlist", id: "opaque/playlist"))
        #expect(reader.added.map { $0.1.type } == ["shelf", "playlist"])
        #expect(reader.added.allSatisfy { $0.0 == song })
        var publication = reader.value
        publication.home = .init(recentWishes: [.init(key: "pending-key", pendingAdd: .init(operationId: "op", actionTime: .now, target: song.target!, snapshot: .init(title: song.title), source: reader.added[1].1), pending: true)])
        reader.emit(publication)
        #expect(model.row(for: song)?.pending == true)
    }
    @Test func missingRecordingAndUnknownKindsHaveNeutralActionsPartialAlbumStaysDiscover() {
        var song = item(); song.target = nil
        #expect(!DulcetDiscoverItemPresentation(item: song).canWish)
        var unknown = item(); unknown.kind = "future"
        #expect(!DulcetDiscoverItemPresentation(item: unknown).canWish)
        let album = DulcetDiscoverItem(id: "a", kind: "album", target: .init(kind: "album", mbid: "external"), title: "Partial", availability: .init(status: "partlyHeld", completeness: "unknown"))
        #expect(DulcetDiscoverDestination(item: album) == .album(mbid: "external", releaseId: nil))
        #expect(DulcetDiscoverStatus(age: nil, error: .init(code: "offline", retryable: true), connection: "offline").canRetry == false)
    }
    @Test func navigationRetainsLoadedPageAndScrollUntilAccountReset() {
        let navigation = DulcetDiscoverNavigation(), reader = DiscoverProofReader()
        let route = DulcetDiscoverHomeRoute.playlist("opaque")
        let page = navigation.page(route, reader: reader); page.scrollID = "21"
        navigation.path = [route]; navigation.homeScroll = "publicPlaylists"
        #expect(navigation.page(route, reader: reader) === page)
        #expect(navigation.page(route, reader: reader).scrollID == "21")
        navigation.close()
        #expect(navigation.path.isEmpty && navigation.homeScroll == nil)
        #expect(navigation.page(route, reader: reader) !== page)
    }
}
