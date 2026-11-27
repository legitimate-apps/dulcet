import CoreGraphics
import Testing
@testable import DulcetKit

@MainActor
struct ListenNowTests {
    @Test func homeIsTheFirstDestinationAndQueriesUseTheCoreShelfSeam() {
        #expect(DulcetSidebarDestination.allCases.first == .listenNow)
        #expect(DulcetListenNowShelf.allCases.count == 10)
        for shelf in DulcetListenNowShelf.allCases {
            let request = DulcetLibraryQuery.listenNow(shelf).request
            #expect(request.kind == "listenNow")
            #expect(request.listType == shelf.rawValue)
        }
    }
}

// MARK: - Top Picks

private func album(_ id: String, _ title: String, artist: String = "Pavement") -> DulcetReaderItem {
    DulcetReaderItem(kind: "album", providerInstanceID: "server", rawID: id, title: title, artistName: artist,
        artistRawID: "ar-\(artist)", albumTitle: nil, albumRawID: nil, year: 1997, genre: nil,
        durationMilliseconds: nil, songCount: 12, albumCount: nil, discNumber: nil, trackNumber: nil,
        sourceContainer: nil, artworkKey: "al-\(id)", owner: nil, favourite: nil, rating: nil, playCount: nil,
        playability: nil, detailComplete: false, metadataMissing: false)!
}

private func playlist(_ id: String, _ title: String) -> DulcetReaderItem {
    DulcetReaderItem(kind: "playlist", providerInstanceID: "server", rawID: id, title: title, artistName: nil,
        artistRawID: nil, albumTitle: nil, albumRawID: nil, year: nil, genre: nil, durationMilliseconds: nil,
        songCount: 14, albumCount: nil, discNumber: nil, trackNumber: nil, sourceContainer: nil,
        artworkKey: "pl-\(id)", owner: "listener", favourite: nil, rating: nil, playCount: nil, playability: nil,
        detailComplete: false, metadataMissing: false)!
}

private func track(_ id: String, artist: String = "Pavement", genre: String? = "Indie Rock", albumID: String? = nil,
                   playability: String? = "streamable") -> DulcetReaderItem {
    DulcetReaderItem(kind: "track", providerInstanceID: "server", rawID: id, title: "Song \(id)", artistName: artist,
        artistRawID: "ar-\(artist)", albumTitle: "Album", albumRawID: albumID ?? "al-\(id)", year: nil, genre: genre,
        durationMilliseconds: 180_000, songCount: nil, albumCount: nil, discNumber: 1, trackNumber: 1,
        sourceContainer: "flac", artworkKey: "al-\(albumID ?? id)", owner: nil, favourite: nil, rating: nil,
        playCount: nil, playability: playability, detailComplete: true, metadataMissing: false)!
}

@MainActor
struct ListenNowTopPicksTests {
    /// A Dulcet Server Home answers every shelf: the mix leads, the album last played follows,
    /// then the stations named after what the listener plays, then rediscovery and the rest.
    @Test func everyShelfOrdersThePicksAsTheListenerReadsThem() {
        let daily = (1...6).map { track("d\($0)", artist: $0.isMultiple(of: 2) ? "Soccer Mommy" : "Pavement") }
        let station = (1...5).map { track("s\($0)") }
        let genre = (1...4).map { track("g\($0)", artist: "Artist \($0)", genre: "Dream Pop") }
        let favourites = (1...3).map { track("f\($0)", artist: "Peach Pit") }
        let picks = DulcetListenNowPicks.plan([
            .recent: [album("a1", "Brighten the Corners"), album("a2", "Crooked Rain")],
            .recentPlaylists: [playlist("p1", "Agent mix")],
            .frequent: [album("a1", "Brighten the Corners"), album("a3", "Wowee Zowee")],
            .newest: [album("a4", "Terror Twilight")],
            .rediscover: [album("a5", "Slanted and Enchanted")],
            .dailyMix: daily,
            .favourites: favourites,
            .artistStation: station,
            .genreStation: genre,
        ])
        #expect(picks.map(\.reason) == [
            .madeForYou, .jumpBackIn, .becauseYouPlay(artist: "Pavement"), .rediscover,
            .becauseYouLove(genre: "Dream Pop"), .madeForYou,
        ])
        #expect(picks.count == DulcetListenNowPicks.maximum)
        #expect(picks[0].title == "Daily Mix")
        #expect(picks[0].route == .listenNowShelf(.dailyMix))
        #expect(picks[1].title == "Brighten the Corners")
        #expect(picks[1].route == .album(DulcetProviderItemID(providerInstanceID: "server", rawID: "a1")))
        #expect(picks[2].title == "Pavement Mix")
        #expect(picks[4].title == "Dream Pop Mix")
        #expect(picks[5].title == "Favorites Mix")
        // Six cards are all there is room for: On Repeat and Just Added fall off the end.
        #expect(!picks.contains { $0.reason == .onRepeat || $0.reason == .justAdded })
    }

    /// One album is one card, even when two shelves carry it: Heavy Rotation's first album is the
    /// one just played, so On Repeat shows the next one.
    @Test func anAlbumAlreadyPickedIsNotPickedTwice() {
        let picks = DulcetListenNowPicks.plan([
            .recent: [album("a1", "Brighten the Corners")],
            .frequent: [album("a1", "Brighten the Corners"), album("a3", "Wowee Zowee")],
            .newest: [album("a3", "Wowee Zowee"), album("a4", "Terror Twilight")],
        ])
        #expect(picks.map(\.title) == ["Brighten the Corners", "Wowee Zowee", "Terror Twilight"])
        #expect(picks.map(\.reason) == [.jumpBackIn, .onRepeat, .justAdded])
        #expect(Set(picks.map(\.id)).count == picks.count)
    }

    /// A plain OpenSubsonic server has no daily mix, rediscovery or genre profile, and its station
    /// is similar songs by many artists: the hero is still made from what it does answer, and the
    /// station is never credited to one artist it is not about.
    @Test func aPlainServerStillGetsAHeroFromWhatItAnswers() {
        let similar = [track("x1", artist: "Silver Jews"), track("x2", artist: "Built to Spill"),
                       track("x3", artist: "Silver Jews")]
        let picks = DulcetListenNowPicks.plan([
            .recent: [album("a1", "Brighten the Corners")],
            .frequent: [album("a2", "Crooked Rain")],
            .newest: [album("a4", "Terror Twilight")],
            .artistStation: similar,
        ])
        #expect(picks.map(\.reason) == [.jumpBackIn, .madeForYou, .onRepeat, .justAdded])
        #expect(picks[1].title == DulcetListenNowShelf.artistStation.title)
        #expect(picks[1].subtitle == "Silver Jews and Built to Spill")
    }

    /// No history, nothing personal: no hero at all rather than random music called a pick. A
    /// short mix is not a card either.
    @Test func nothingPersonalMeansNoHero() {
        #expect(DulcetListenNowPicks.plan([:]).isEmpty)
        #expect(DulcetListenNowPicks.plan([.dailyMix: [track("1"), track("2")]]).isEmpty)
        #expect(DulcetListenNowPicks.plan([.random: [album("r1", "Random")]]).isEmpty)
    }

    @Test func offlineMixKeepsItsCollageAndOnlyPlaysAvailableTracks() throws {
        let online = (1...4).map { track("\($0)", albumID: "album\($0)") }
        let offline = (1...4).map {
            track("\($0)", albumID: "album\($0)", playability: $0 == 3 ? "downloaded" : "unavailableOffline")
        }
        let onlinePick = try #require(DulcetListenNowPicks.plan([.dailyMix: online]).first)
        let offlinePick = try #require(DulcetListenNowPicks.plan([.dailyMix: offline]).first)
        #expect(onlinePick.artworks.count == 4)
        #expect(offlinePick.artworks == onlinePick.artworks)
        #expect(offlinePick.playableMixTracks.map(\.id.rawID) == ["3"])
        let noneAvailable = (1...4).map { track("\($0)", albumID: "album\($0)", playability: "unavailableOffline") }
        let coldPick = try #require(DulcetListenNowPicks.plan([.dailyMix: noneAvailable]).first)
        #expect(coldPick.artworks == onlinePick.artworks)
        #expect(coldPick.playableMixTracks.isEmpty)
    }

    /// A recently played playlist stands in for Jump Back In when no album has been played.
    @Test func aPlaylistCanBeJumpedBackInto() {
        let picks = DulcetListenNowPicks.plan([.recentPlaylists: [playlist("p1", "Agent mix")]])
        #expect(picks.map(\.title) == ["Agent mix"])
        #expect(picks.first?.route == .playlist(DulcetProviderItemID(providerInstanceID: "server", rawID: "p1")))
    }

    /// A mix's cover is four of its albums when it draws on four, and otherwise its first.
    @Test func aMixShowsFourOfItsAlbums() {
        let four = (1...5).map { track("t\($0)", albumID: "al\($0)") }
        let pick = DulcetListenNowPicks.plan([.dailyMix: four]).first
        #expect(pick?.artworks.count == 4)
        let sameAlbum = (1...5).map { track("t\($0)", albumID: "al1") }
        #expect(DulcetListenNowPicks.plan([.dailyMix: sameAlbum]).first?.artworks.count == 1)
    }

    @Test func artistsLineRanksByHowOftenEachPlays() {
        let tracks = ["A", "B", "B", "C", "D", "D", "D"].enumerated().map { track("\($0.offset)", artist: $0.element) }
        #expect(DulcetListenNowPicks.artistsLine(tracks) == "D, B, A and more")
        #expect(DulcetListenNowPicks.artistsLine([]) == "")
    }

    @Test func stationShelvesAreNamedAfterWhatTheyPlay() {
        #expect(DulcetListenNowPicks.shelfTitle(.artistStation, items: [track("1"), track("2")]) == "More from Pavement")
        #expect(DulcetListenNowPicks.shelfTitle(.genreStation, items: [track("1"), track("2")]) == "More Indie Rock")
        let mixed = [track("1", artist: "A", genre: "Rock"), track("2", artist: "B", genre: "Pop")]
        #expect(DulcetListenNowPicks.shelfTitle(.artistStation, items: mixed) == DulcetListenNowShelf.artistStation.title)
        #expect(DulcetListenNowPicks.shelfTitle(.genreStation, items: mixed) == DulcetListenNowShelf.genreStation.title)
        #expect(DulcetListenNowPicks.shelfTitle(.recent, items: [album("a", "A")]) == DulcetListenNowShelf.recent.title)
    }

    @Test func heroMotionIsBounded() {
        typealias M = DulcetListenNowHeroMetrics
        // One card and a peek of the next on a phone; never a wall-to-wall card on a wide page.
        let phone: CGFloat = M.cardWidth(pageWidth: 402, inset: 20)
        let expected: CGFloat = 402 - 40 - M.nextCardPeek
        #expect(abs(phone - expected) < 0.001)
        #expect(M.cardWidth(pageWidth: 1200, inset: 40) == M.maximumCardWidth)
        // Resting: drawn as laid out.
        #expect(M.stretchScale(offset: 0) == 1)
        #expect(M.scrolledOpacity(offset: 0) == 1)
        // Pulled: grows; scrolled: never shrinks below its size, fades but never vanishes.
        #expect(M.stretchScale(offset: 100) > 1)
        #expect(M.stretchScale(offset: -300) == 1)
        #expect(M.scrolledOpacity(offset: -200) < 1)
        #expect(M.scrolledOpacity(offset: -10_000) == 0.25)
        #expect(M.scrolledOpacity(offset: 80) == 1)
    }
}

struct DiscoverHomeOrderTests {
    /// Discover opens on artists like the listener's own once there are any; until then on
    /// trending, with the personal shelf where it was.
    @Test func personalShelfLeadsOnlyWhenItHasArtists() {
        #expect(DulcetDiscoverShelf.homeOrder(personalHasItems: false) == DulcetDiscoverShelf.allCases)
        let order = DulcetDiscoverShelf.homeOrder(personalHasItems: true)
        #expect(order.first == .similarArtists)
        #expect(Set(order) == Set(DulcetDiscoverShelf.allCases))
        #expect(order.count == DulcetDiscoverShelf.allCases.count)
        #expect(Array(order.dropFirst()) == DulcetDiscoverShelf.allCases.filter { $0 != .similarArtists })
    }
}
