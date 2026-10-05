#if os(macOS) || os(iOS) || os(tvOS)
import Foundation
import Testing
@testable import DulcetKit

// Now Playing's heart and lyrics controls (spec §16.20, §18.4). The heart is the rows' heart:
// it names the playing track, goes through the same session, and so pends, holds and settles
// exactly as a row's does. These run on every Apple platform the DulcetKit suite runs on, so the
// footer's platform branches are each pinned where they compile.

// MARK: - Fakes

@MainActor
private final class HeartReader: DulcetLibraryReading {
    private(set) var favourites: [(DulcetFavouriteTarget, Bool)] = []
    private(set) var ratings: [(DulcetFavouriteTarget, Int)] = []
    var outcomeHandler: (@MainActor (DulcetFavouriteOutcome) -> Void)?

    func subscribeWindow(
        _ query: DulcetLibraryQuery,
        onPublication: @escaping @MainActor (DulcetLibraryWindow) -> Void
    ) -> any DulcetLibraryWindowSubscribing { HeartWindow() }

    func subscribeSearch(
        onPublication: @escaping @MainActor (DulcetReaderSearchPublication) -> Void
    ) -> any DulcetLibrarySearchSubscribing { HeartSearch() }

    func setFavourite(_ target: DulcetFavouriteTarget, favourite: Bool) -> Bool {
        favourites.append((target, favourite))
        return true
    }

    func setRating(_ target: DulcetFavouriteTarget, rating: Int) -> Bool {
        ratings.append((target, rating))
        return true
    }

    func subscribeFavouriteOutcomes(
        _ handler: @escaping @MainActor (DulcetFavouriteOutcome) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        outcomeHandler = handler
        return HeartCancellable()
    }

    func pendingChangeCount(
        completion: @escaping @MainActor (DulcetPendingChanges) -> Void
    ) -> any DulcetLibraryReaderCancellable {
        completion(DulcetPendingChanges(count: 0))
        return HeartCancellable()
    }

    func connect(completion: @escaping @MainActor (DulcetReaderConnection) -> Void) -> any DulcetLibraryReaderCancellable {
        HeartCancellable()
    }

    func reconnect(completion: @escaping @MainActor (DulcetReaderConnection) -> Void) -> any DulcetLibraryReaderCancellable {
        HeartCancellable()
    }

    func setOnline(_ reachable: Bool) {}
    func setForeground(_ foreground: Bool) {}
    func setNetworkConstrained(_ constrained: Bool) {}
    func setupFailed(completion: @escaping @MainActor (Bool) -> Void) -> any DulcetLibraryReaderCancellable {
        HeartCancellable()
    }
    /// A reader whose thread has not stopped yet keeps its completion here.
    var holdsClose = false
    private(set) var pendingClose: (@MainActor () -> Void)?
    func close(completion: @escaping @MainActor () -> Void) {
        if holdsClose { pendingClose = completion } else { completion() }
    }
}

@MainActor
private final class HeartWindow: DulcetLibraryWindowSubscribing {
    func loadMore() {}
    func loadBefore() {}
    func setViewport(first: Int, last: Int) {}
    func close() {}
}

@MainActor
private final class HeartSearch: DulcetLibrarySearchSubscribing {
    func updateQuery(_ text: String) {}
    func refresh() {}
    func close() {}
}

private final class HeartCancellable: DulcetLibraryReaderCancellable {
    func cancel() {}
}

@MainActor
private final class HeartReaderFactory: DulcetLibraryReaderMaking {
    private(set) var made: [HeartReader] = []

    func makeReader(account: DulcetLibraryReaderAccount, foreground: Bool) -> any DulcetLibraryReading {
        let reader = HeartReader()
        made.append(reader)
        return reader
    }
}

/// The deterministic Now Playing, with a reader session beside it.
@MainActor
private final class NowPlayingWithSession: DulcetDataSource, DulcetLibrarySessionProviding {
    private let fixture = DulcetDeterministicDataSource(initialState: .nowPlaying)
    let librarySession: DulcetLibrarySession?

    init(session: DulcetLibrarySession?) {
        librarySession = session
    }

    var currentSnapshot: DulcetSnapshot { fixture.currentSnapshot }
    func setSnapshotHandler(_ handler: @escaping @MainActor (DulcetSnapshot) -> Void) {
        fixture.setSnapshotHandler(handler)
    }
    func send(_ action: DulcetPresentationAction) { fixture.send(action) }
}

private func account(_ providerInstanceID: String) -> DulcetLibraryReaderAccount {
    DulcetLibraryReaderAccount(
        providerInstanceID: providerInstanceID,
        normalizedServerURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
}

// MARK: - The footer

@Test @MainActor
func theNowPlayingFooterOffersTheHeartAndLyricsOnEveryApplePlatform() {
    typealias Player = DulcetNowPlayingView
    let offered = Player.footerControls(offersFavourite: true, showsQueueToggle: true)
    let withheld = Player.footerControls(offersFavourite: false, showsQueueToggle: true)
    #expect(offered.contains(.lyrics), "every Apple player offers lyrics")
    #expect(withheld.contains(.lyrics), "lyrics do not depend on the reader")
    #expect(!withheld.contains(.favourite), "no heart while the reader does not hold the track's account")
    #expect(!withheld.contains(.rating), "no stars while the reader does not hold the track's account")
#if os(macOS)
    // The Mac's heart and stars are in the window's toolbar, so the footer never draws a second set.
    #expect(offered == [.airPlay, .lyrics, .upNext])
    #expect(Player.footerControls(offersFavourite: true, showsQueueToggle: false) == [.airPlay, .lyrics])
#elseif os(tvOS)
    // Apple TV: the heart, its stars beside it, then lyrics, all reached by focus under the transport.
    #expect(offered == [.favourite, .rating, .lyrics])
    #expect(withheld == [.lyrics])
#else
    #expect(offered == [.favourite, .rating, .airPlay, .lyrics, .upNext])
    #expect(Player.footerControls(offersFavourite: true, showsQueueToggle: false) == [.favourite, .rating, .airPlay, .lyrics])
    #expect(withheld == [.airPlay, .lyrics, .upNext])
#endif
}

@Test @MainActor
func theNowPlayingStarsSitBesideTheHeartOnTheirOwnRowWhereAPhoneCannotFitThemAll() {
    typealias Player = DulcetNowPlayingView
    let offered = Player.footerControls(offersFavourite: true, showsQueueToggle: true)
    // The heart and the stars are adjacent in every footer that draws them, heart first.
    if let heart = offered.firstIndex(of: .favourite) {
        #expect(offered.indices.contains(heart + 1) && offered[heart + 1] == .rating)
    }
    let rows = Player.footerRows(offered)
#if os(iOS)
    // Five 44-point stars, the heart and three 44-point controls do not fit a phone's width in one
    // row, so the track's own marks take the first row and the rest keep the second.
    #expect(rows == [[.favourite, .rating], [.airPlay, .lyrics, .upNext]])
    #expect(Player.footerRows(Player.footerControls(offersFavourite: false, showsQueueToggle: true))
        == [[.airPlay, .lyrics, .upNext]], "no empty marks row without a reader")
#else
    #expect(rows == [offered], "one row where it fits")
#endif
}

@Test
func lyricsInOneColumnAreWideEnoughToReadAcrossARoomOnATelevision() {
#if os(tvOS)
    #expect(DulcetNowPlayingView.oneColumnLyricsMaxWidth >= 1_000)
#else
    #expect(DulcetNowPlayingView.oneColumnLyricsMaxWidth == 600)
#endif
}

// MARK: - The heart

@Test @MainActor
func nowPlayingOffersTheHeartOnlyWhileTheReaderHoldsThePlayingTracksAccount() throws {
    let noSession = DulcetPresentationStore(source: NowPlayingWithSession(session: nil))
    let track = try #require(noSession.snapshot.nowPlaying?.current)
    #expect(noSession.nowPlayingFavourite(for: track) == nil, "no reader session, no heart")

    let factory = HeartReaderFactory()
    let unopened = DulcetLibrarySession(factory: factory)
    let unopenedStore = DulcetPresentationStore(source: NowPlayingWithSession(session: unopened))
    #expect(unopenedStore.nowPlayingFavourite(for: track) == nil, "no reader yet, no heart")

    let foreign = DulcetLibrarySession(factory: HeartReaderFactory())
    foreign.open(account: account("another-server"), mode: .connected)
    let foreignStore = DulcetPresentationStore(source: NowPlayingWithSession(session: foreign))
    #expect(foreignStore.nowPlayingFavourite(for: track) == nil,
            "a track from another account cannot be starred through this reader")
    #expect(foreignStore.playingTrackFavourite == nil)

    // Switching accounts: the session already names the track's account while the previous
    // reader is still stopping, and until the new one opens nothing can be changed.
    let swapping = DulcetLibrarySession(factory: factory)
    swapping.open(account: account("another-server"), mode: .connected)
    try #require(factory.made.last).holdsClose = true
    swapping.open(account: account(track.id.providerInstanceID), mode: .connected)
    #expect(swapping.account?.providerInstanceID == track.id.providerInstanceID && swapping.reader == nil)
    let swappingStore = DulcetPresentationStore(source: NowPlayingWithSession(session: swapping))
    #expect(swappingStore.nowPlayingFavourite(for: track) == nil, "no reader to take the change, no heart")

    let session = DulcetLibrarySession(factory: factory)
    session.open(account: account(track.id.providerInstanceID), mode: .connected)
    let store = DulcetPresentationStore(source: NowPlayingWithSession(session: session))
    let favourite = try #require(store.nowPlayingFavourite(for: track))
    #expect(favourite.target == DulcetFavouriteTarget(kind: .track, id: track.id))
    #expect(favourite.published == track.isFavorite, "before any tap, the queue's own copy")

    // The stars beside the heart are offered by exactly the same rule, so the two never part.
    #expect(noSession.nowPlayingRating(for: track) == nil)
    #expect(unopenedStore.nowPlayingRating(for: track) == nil)
    #expect(foreignStore.nowPlayingRating(for: track) == nil)
    #expect(swappingStore.nowPlayingRating(for: track) == nil)
    let rating = try #require(store.nowPlayingRating(for: track))
    #expect(rating.target == favourite.target)
    // What a star press does: through the same reader as the heart.
    session.setRating(rating.target, rating: 4)
    let reader = try #require(factory.made.last)
    #expect(reader.ratings.count == 1)
    #expect(reader.ratings.last?.0 == favourite.target)
    #expect(reader.ratings.last?.1 == 4)
}

@Test @MainActor
func theNowPlayingHeartIsTheRowsHeartPendingHeldAndSaved() throws {
    let factory = HeartReaderFactory()
    let session = DulcetLibrarySession(factory: factory)
    let probe = DulcetPresentationStore(source: NowPlayingWithSession(session: nil))
    let track = try #require(probe.snapshot.nowPlaying?.current)
    session.open(account: account(track.id.providerInstanceID), mode: .connected)
    let reader = try #require(factory.made.first)
    let store = DulcetPresentationStore(source: NowPlayingWithSession(session: session))
    let before = try #require(store.nowPlayingFavourite(for: track))
    let wasFavourite = session.isFavourite(track.id, published: before.published)

    // What the Now Playing button does on a press.
    #expect(session.toggleFavourite(before.target, published: before.published))
    let after = try #require(store.nowPlayingFavourite(for: track))
    #expect(session.isFavourite(track.id, published: after.published) == !wasFavourite,
            "the heart changes with the press, before any request is answered")
    // A row drawing the same track from an older publication shows the tap too.
    #expect(session.isFavourite(track.id, published: wasFavourite) == !wasFavourite)
    #expect(session.favouriteState(after.target) == .pending)
    #expect(reader.favourites.count == 1)
    #expect(reader.favourites.last?.0 == DulcetFavouriteTarget(kind: .track, id: track.id))
    #expect(reader.favourites.last?.1 == !wasFavourite)
    #expect(store.playingTrackFavourite?.isFavourite == !wasFavourite,
            "the lock screen and the menu read the same heart")

    let rawID = track.id.rawID
    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "held", targetKind: "track", rawID: rawID, field: "favourite", errorKind: "forbidden"))
    #expect(session.favouriteState(after.target) == .held(.forbidden))
    #expect(session.isFavourite(track.id, published: after.published) == !wasFavourite)

    reader.outcomeHandler?(DulcetFavouriteOutcome(
        kind: "saved", targetKind: "track", rawID: rawID, field: "favourite", errorKind: nil))
    #expect(session.favouriteState(after.target) == .settled)
    let saved = try #require(store.nowPlayingFavourite(for: track))
    #expect(saved.published == !wasFavourite, "a saved change stays on the heart")
}
#endif
