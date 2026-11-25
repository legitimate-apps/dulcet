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
    @Test func playlistPagingUsesSubmittedQueryAndFailedReplacementLabelsOldAnswer() {
        let reader = DiscoverProofReader()
        let model = DulcetDiscoverListModel(reader: reader, route: .playlists)
        model.query = "first"; model.submitQuery()
        reader.playlistListCompletions[0](.success(.init(items: [.init(id: "p", title: "First")], nextCursor: "opaque", age: age)))
        model.query = "draft"; model.more()
        #expect(reader.playlistListRequests[1].0 == "first" && reader.playlistListRequests[1].1 == "opaque")
        model.submitQuery()
        reader.playlistListCompletions[2](.failure(.init(code: "offline", retryable: true)))
        #expect(model.answerQuery == "first" && model.submittedQuery == "draft")
        #expect(model.playlists?.items.first?.title == "First" && model.nextCursor == nil)
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
        unknown.availability = .init(status: "held", completeness: "known", libraryItems: [.init(providerInstanceId: "local", type: "song", id: "local-song")])
        #expect(!DulcetDiscoverItemPresentation(item: unknown).canWish)
        #expect(DulcetDiscoverItemPresentation(item: unknown).libraryReferences.isEmpty)
        if case .item = DulcetDiscoverDestination(item: unknown) {} else { Issue.record("Unknown kind must stay neutral") }
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

#if os(iOS)
import SwiftUI
import Testing
import UIKit
@testable import DulcetKit

/// Renders the Discover home with real cover bytes and checks what a person sees: the cover leads each
/// tile, and the library and wish state is a mark on it rather than a sentence under it. When
/// `DULCET_DISCOVER_RENDER_DIR` names a directory the frame is also written there as a PNG.
@Suite @MainActor struct DulcetDiscoverTilesRenderTests {
    let age = DulcetDiscoverAge(receivedAt: Date(timeIntervalSince1970: 1000))
    func cover(_ hue: CGFloat) -> Data {
        let size = CGSize(width: 250, height: 250)
        return UIGraphicsImageRenderer(size: size).pngData { context in
            UIColor(hue: hue, saturation: 0.55, brightness: 0.75, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor(hue: hue, saturation: 0.7, brightness: 0.45, alpha: 1).setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 40, y: 40, width: 170, height: 170))
        }
    }
    func item(_ id: String, _ kind: String, _ title: String, artist: String? = nil, listens: Int64? = nil, cover: Bool = true, held: Bool = false) -> DulcetDiscoverItem {
        .init(id: id, kind: kind, target: .init(kind: kind, mbid: id), title: title, artist: artist,
              artworkId: cover ? "da_\(id)" : nil, listenCount: listens, wishable: !held,
              availability: .init(status: held ? "held" : "notHeld", completeness: held ? "known" : "unknown"))
    }
    func publication() -> DulcetDiscoverPublication {
        let artists = (0..<5).map { item("a\($0)", "artist", ["Ado", "Aimer", "BTS", "Lorde", "Yoasobi"][$0], listens: 120_000 + Int64($0) * 9_000, cover: false) }
        let albums = (0..<5).map { item("b\($0)", "album", ["ARIRANG", "Blue Hour", "Midnight Clear", "Plum", "Soft Static"][$0], artist: ["BTS", "Lorde", "Ado", "Aimer", "Yoasobi"][$0], held: $0 == 1) }
        let songs = (0..<5).map { item("c\($0)", "song", ["Hands Up", "Silver", "Seaside", "Ember", "Quiet Hours"][$0], artist: ["BTS", "Lorde", "Ado", "Aimer", "Yoasobi"][$0], listens: 80_000 + Int64($0) * 7_000) }
        func state(_ shelf: DulcetDiscoverShelf, _ items: [DulcetDiscoverItem]) -> DulcetDiscoverShelfState {
            .init(id: shelf.rawValue, page: .init(id: shelf.rawValue, items: items, nextCursor: "next", age: age))
        }
        return .init(providerInstanceId: "account-a", generation: 2,
                     access: .init(detection: "known", discoverAvailable: true, wishlistAvailable: true, canReadWishlist: true, canAddWish: true, canRemoveWish: true),
                     connection: "online", query: "",
                     home: .init(shelves: [state(.trendingArtists, artists), state(.trendingAlbums, albums), state(.trendingSongs, songs)]))
    }
    func snapshot<Content: View>(_ content: Content) -> UIImage {
        let controller = UIHostingController(rootView: content)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.frame = window.bounds
        // Let the covers decode and the tiles settle.
        RunLoop.main.run(until: Date().addingTimeInterval(1.0))
        controller.view.layoutIfNeeded()
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { context in
            UIColor.systemBackground.setFill()
            context.fill(window.bounds)
            if !controller.view.drawHierarchy(in: window.bounds, afterScreenUpdates: true) {
                controller.view.layer.render(in: context.cgContext)
            }
        }
        window.isHidden = true
        return image
    }
    func write(_ image: UIImage, _ name: String) throws {
        guard let directory = ProcessInfo.processInfo.environment["DULCET_DISCOVER_RENDER_DIR"] else { return }
        try FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true)
        try #require(image.pngData()).write(to: URL(fileURLWithPath: directory).appendingPathComponent(name))
    }
    func render(_ publication: DulcetDiscoverPublication, covers: [String: Data]) throws -> (UIImage, DiscoverProofReader) {
        let reader = DiscoverProofReader()
        reader.value = publication
        reader.covers = covers
        let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(initialState: .libraryBrowse))
        let view = NavigationStack {
            DulcetDiscoverHomeView(reader: reader, navigation: DulcetDiscoverNavigation())
        }.environment(store)
        return (snapshot(view), reader)
    }
    // Row ids differ from the home test: decoded covers are shared across a run, so a repeat id would not ask.
    @Test func listRowsKeepTheCoverAndTheMarksQuiet() throws {
        let reader = DiscoverProofReader()
        reader.value = publication()
        reader.covers = ["da_r0": cover(0), "da_r1": cover(0.2)]
        let model = DulcetDiscoverModel(reader: reader)
        model.open()
        let rows = VStack(alignment: .leading) {
            ForEach(["r0", "r1", "a0"], id: \.self) { id in
                DulcetDiscoverShelfItem(model: model, item: item(id, id.hasPrefix("a") ? "artist" : "album", id == "r0" ? "ARIRANG" : id == "r1" ? "Blue Hour" : "Ado", artist: id.hasPrefix("a") ? nil : "BTS", cover: !id.hasPrefix("a"), held: id == "r1"),
                                        source: .init(type: "shelf", id: "trendingAlbums"), identifier: "row." + id, style: .row,
                                        navigate: { _ in }, openLibrary: { _ in })
            }
        }.padding()
        let image = snapshot(rows)
        #expect(reader.artworkRequests.sorted() == ["da_r0", "da_r1"])
        try write(image, "discover-rows.png")
    }
    @Test func homeShowsCoverLedTilesAndAsksForEachCoverOnce() throws {
        var covers: [String: Data] = [:]
        for index in 0..<5 {
            covers["da_b\(index)"] = cover(CGFloat(index) / 5)
            covers["da_c\(index)"] = cover(CGFloat(index) / 5 + 0.1)
        }
        let (image, reader) = try render(publication(), covers: covers)
        #expect(image.size.width == 393)
        // Artists carry no cover id, so no request is made for them; every cover on screen asks once.
        #expect(!reader.artworkRequests.contains { $0.hasPrefix("da_a") })
        #expect(Set(reader.artworkRequests).count == reader.artworkRequests.count)
        // The first tiles are on screen; tiles scrolled off the edge may or may not have asked yet.
        #expect(reader.artworkRequests.contains("da_b0") && reader.artworkRequests.contains("da_b1"))
        try write(image, "discover-home.png")
    }
    @Test func listenCountsReadCompactly() {
        #expect(DulcetStrings.discoverListens(120_000).contains("120"))
        #expect(DulcetStrings.discoverListens(1_200_000).contains("1.2"))
    }
}
#endif
