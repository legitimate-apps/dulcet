import Foundation
import Security
import Testing
@testable import DulcetKit

@Test
func credentialBearingPresentationValuesCannotPrintCredentials() {
    let credentialValues = [
        "https://listener:request-secret@music.example.invalid",
        "print-canary-username",
        "print-canary-password",
    ]
    let request = DulcetAccountConnectRequest(
        serverURL: credentialValues[0],
        username: credentialValues[1],
        password: credentialValues[2],
        allowLocalHTTP: false
    )
    let snapshot = DulcetSnapshot(
        state: .accountConnectIdle,
        selectedDestination: .settings,
        accountConnected: false,
        connectivity: .unavailable,
        albums: [],
        looseTracks: [],
        recentlyAddedTracks: [],
        captureDate: Date(timeIntervalSince1970: 0),
        accountForm: request
    )
    var requestDump = ""
    var snapshotDump = ""
    dump(request, to: &requestDump)
    dump(snapshot, to: &snapshotDump)
    let rendered = [
        String(describing: request),
        String(reflecting: request),
        requestDump,
        String(describing: snapshot),
        String(reflecting: snapshot),
        snapshotDump,
    ]

    for value in credentialValues {
        #expect(rendered.allSatisfy { !$0.contains(value) })
    }
    #expect(rendered.allSatisfy { $0.contains("<redacted>") })
}

@Test @MainActor
func accountConnectSurfacePublishesProgressAndCancelsTheActiveOperation() {
    let connector = ControlledAccountConnector()
    let source = DulcetAccountDataSource(connector: connector)
    let store = DulcetPresentationStore(source: source)

    #expect(store.snapshot.state == .accountConnectIdle)

    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "correct horse battery staple"
    store.submitAccountConnection()

    #expect(store.snapshot.state == .accountConnecting)
    #expect(connector.requests == [DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "correct horse battery staple",
        allowLocalHTTP: false
    )])
    #expect(connector.operation.cancelCount == 0)

    store.cancelAccountConnection()

    #expect(connector.operation.cancelCount == 1)
}

@Test @MainActor
func productionDataSourceKeepsDestinationAndRenderedStateInAgreement() {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)

    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))

    let expectations: [(DulcetSidebarDestination, DulcetPresentationState)] = [
        (.library, .libraryLoading),
        (.search, .searchIdle),
        (.nowPlaying, .nowPlayingUnavailable),
    ]
    for (destination, expectedState) in expectations {
        store.selectDestination(destination)

        #expect(store.snapshot.selectedDestination == destination)
        #expect(store.snapshot.state == expectedState)
    }
}

@Test @MainActor
func connectedLibraryPublishesReadThroughContentAndCancelsWhenLeaving() {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))

    store.selectDestination(.library)
    #expect(store.snapshot.state == .libraryLoading)
    #expect(libraryBrowser.requests.single?.providerInstanceID == "provider-instance-fixture")
    #expect(libraryBrowser.requests.single?.normalizedServerURL == "https://music.example.invalid")

    let album = fixtureLibraryAlbum()
    libraryBrowser.complete(.loaded(
        musicFolders: [DulcetMusicFolder(
            id: DulcetProviderItemID(
                providerInstanceID: "provider-instance-fixture",
                rawID: "folder:opaque"
            ),
            name: "Primary"
        )],
        artists: [DulcetArtist(
            id: DulcetProviderItemID(
                providerInstanceID: "provider-instance-fixture",
                rawID: "artist:opaque"
            ),
            name: "Opaque Artist",
            mediaSourceID: nil
        )],
        albums: [album]
    ))

    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.snapshot.albums == [album])
    #expect(store.snapshot.artists.map(\.name) == ["Opaque Artist"])

    store.selectDestination(.library)
    #expect(libraryBrowser.operations.last?.cancelCount == 0)
    store.selectDestination(.search)
    #expect(libraryBrowser.operations.last?.cancelCount == 1)
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    #expect(store.snapshot.state == .searchIdle)
    #expect(store.snapshot.selectedDestination == .search)
}

@Test @MainActor
func playbackSurfaceIntentsReachTheSinglePlaybackControllerBoundary() throws {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        playbackController: playback,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    #expect(playback.configuredProviderInstanceID == "provider-instance-fixture")

    store.selectDestination(.library)
    let album = fixtureLibraryAlbum()
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    #expect(playback.restoredCatalogs.last?.map(\.id) == album.tracks.map(\.id))

    store.playLibrary(shuffle: true)
    let libraryIntent = try #require(playback.queueIntents.last)
    #expect(libraryIntent.sourceKind == .library)
    #expect(libraryIntent.sourceID == nil)
    #expect(libraryIntent.tracks.map(\.id) == album.tracks.map(\.id))
    #expect(libraryIntent.startIndex == nil)
    #expect(libraryIntent.shuffle)

    store.selectDestination(.library)
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    store.selectAlbum(album.id)
    #expect(store.snapshot.albums.map(\.id) == [album.id])
    store.playAlbum(album.id, shuffle: false)
    let albumIntent = try #require(playback.queueIntents.last)
    #expect(albumIntent.sourceKind == .album)
    #expect(albumIntent.sourceID == album.id)
    #expect(albumIntent.tracks.map(\.id) == album.tracks.map(\.id))
    #expect(albumIntent.startIndex == 0)
    #expect(!albumIntent.shuffle)

    store.selectDestination(.library)
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    store.selectAlbum(album.id)
    store.playAlbum(album.id, shuffle: true)
    let shuffledAlbumIntent = try #require(playback.queueIntents.last)
    #expect(shuffledAlbumIntent.sourceKind == .album)
    #expect(shuffledAlbumIntent.sourceID == album.id)
    #expect(shuffledAlbumIntent.startIndex == nil)
    #expect(shuffledAlbumIntent.shuffle)

    store.selectDestination(.library)
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    store.selectAlbum(album.id)
    store.activateTrack(albumID: album.id, trackID: album.tracks[0].id)
    let rowIntent = try #require(playback.queueIntents.last)
    #expect(rowIntent.sourceKind == .album)
    #expect(rowIntent.sourceID == album.id)
    #expect(rowIntent.startIndex == 0)
    #expect(!rowIntent.shuffle)

    let controls: [DulcetPlaybackControlIntent] = [
        .play,
        .pause,
        .next,
        .previous,
        .seek(.seconds(42)),
        .setShuffle(true),
        .cycleRepeat,
    ]
    for control in controls {
        store.sendPlaybackControl(control)
    }
    #expect(playback.controlIntents == controls)
}

@Test @MainActor
func downloadSurfaceIntentAndDurableStateReachTheDownloadControllerBoundary() throws {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let downloads = ControlledDownloadController()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        downloadController: downloads,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    #expect(store.downloadsEnabled)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    #expect(downloads.configuredAccount?.providerInstanceID == "provider-instance-fixture")

    store.selectDestination(.library)
    let album = fixtureLibraryAlbum()
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    store.downloadTrack(album.tracks[0].id)
    #expect(downloads.requestedTracks.map(\.id) == [album.tracks[0].id])

    downloads.publish(.downloaded, for: album.tracks[0].id)
    let renderedTrack = try #require(store.snapshot.albums.first?.tracks.first)
    #expect(renderedTrack.downloadState == .downloaded)

    downloads.downloadsEnabled = false
    #expect(!store.downloadsEnabled)
}

@Test @MainActor
func neverPlayedControllerRemainsUnavailableAfterConnectionAndLibraryLoad() {
    let connector = ControlledAccountConnector()
    let browser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: browser,
        playbackController: playback,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    ))
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    store.selectDestination(.library)
    let album = fixtureLibraryAlbum()
    browser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))

    #expect(playback.configuredProviderInstanceID == "provider-instance-fixture")
    #expect(playback.restoredCatalogs.count == 1)
    #expect(playback.restoredCatalogs.first == album.tracks)
    #expect(playback.queueIntents.isEmpty)
    #expect(playback.controlIntents.isEmpty)
    #expect(playback.currentPresentation == .unavailable)
    store.selectDestination(.nowPlaying)
    #expect(store.snapshot.selectedDestination == .nowPlaying)
    #expect(store.snapshot.state == .nowPlayingUnavailable)
    #expect(store.snapshot.nowPlaying == nil)
}

@Test(arguments: [DulcetPlaybackSurfaceStatus.ready, .failed]) @MainActor
func missingPreviouslyPresentedItemRemainsAPlaybackFailure(status: DulcetPlaybackSurfaceStatus) {
    let playback = ControlledPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: playback
    ))
    let album = fixtureLibraryAlbum()
    let item = DulcetNowPlaying(
        sessionID: DulcetPlaybackSessionID("session-before-item-loss"),
        current: album.tracks[0],
        queue: album.tracks,
        elapsed: .seconds(1),
        isPlaying: true,
        outputName: "Fixture output",
        volume: 1,
        audioFormat: DulcetAudioFormat(codec: "FLAC", sampleRateKilohertz: 44.1),
        phase: .progressing,
        seekability: .seekable,
        progressBegan: true
    )
    store.selectDestination(.nowPlaying)
    playback.publish(DulcetPlaybackPresentation(status: .ready, nowPlaying: item))
    #expect(store.snapshot.state == .nowPlaying)
    #expect(store.snapshot.nowPlaying?.sessionID == item.sessionID)
    #expect(store.snapshot.nowPlaying?.progressBegan == true)

    playback.publish(DulcetPlaybackPresentation(status: status, nowPlaying: nil))
    #expect(playback.currentPresentation.status == status)
    #expect(playback.currentPresentation.nowPlaying == nil)
    #expect(store.snapshot.state == .nowPlayingFailed)
    #expect(store.snapshot.nowPlaying == nil)
    store.selectDestination(.library)
    store.selectDestination(.nowPlaying)
    #expect(store.snapshot.state == .nowPlayingFailed)
}

@Test @MainActor
func nowPlayingMetadataIsWithheldUntilThePlaybackControllerPublishesReady() {
    let playback = ControlledPlaybackController()
    let source = DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: playback
    )
    let store = DulcetPresentationStore(source: source)
    let album = fixtureLibraryAlbum()
    let nowPlaying = DulcetNowPlaying(
        sessionID: DulcetPlaybackSessionID("session-ready"),
        current: album.tracks[0],
        queue: album.tracks,
        elapsed: .zero,
        isPlaying: true,
        outputName: "Fixture output",
        volume: 1,
        audioFormat: DulcetAudioFormat(codec: "FLAC", sampleRateKilohertz: 44.1),
        phase: .ready,
        seekability: .seekable,
        progressBegan: false
    )

    store.selectDestination(.nowPlaying)
    playback.publish(DulcetPlaybackPresentation(status: .preparing, nowPlaying: nowPlaying))
    #expect(store.snapshot.state == .nowPlayingPreparing)
    #expect(store.snapshot.nowPlaying == nil)

    playback.publish(DulcetPlaybackPresentation(status: .failed, nowPlaying: nowPlaying))
    #expect(store.snapshot.state == .nowPlayingFailed)
    #expect(store.snapshot.nowPlaying == nil)

    playback.publish(DulcetPlaybackPresentation(status: .ready, nowPlaying: nowPlaying))
    #expect(store.snapshot.state == .nowPlaying)
    #expect(store.snapshot.nowPlaying?.sessionID == DulcetPlaybackSessionID("session-ready"))

    store.sendPlaybackControl(.pause)
    #expect(playback.controlIntents == [.pause])
    #expect(store.snapshot.nowPlaying?.isPlaying == true)
}

@Test @MainActor
func serverSearchDebouncesCancelsAndPagesEachResultTypeIndependently() async {
    #expect(DulcetAccountDataSource.defaultSearchDebounce == .milliseconds(250))
    let connector = ControlledAccountConnector()
    let search = ControlledServerSearch()
    let source = DulcetAccountDataSource(
        connector: connector,
        serverSearch: search,
        searchDebounce: .zero,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    store.selectDestination(.search)

    store.searchQuery = "a"
    await settleSearchTask()
    #expect(search.requests.isEmpty)
    #expect(store.snapshot.state == .searchIdle)

    store.searchQuery = "at"
    await settleSearchTask(until: { search.requests.count == 1 })
    #expect(search.requests.count == 1)
    #expect(search.requests[0].query == "at")
    #expect(search.requests[0].artistCount == 20)
    #expect(search.requests[0].albumCount == 20)
    #expect(search.requests[0].trackCount == 20)

    store.searchQuery = "atlas"
    await settleSearchTask(until: { search.requests.count == 2 })
    #expect(search.operations[0].cancelCount == 1)
    #expect(search.requests.count == 2)
    #expect(search.requests[1].query == "atlas")
    #expect(search.requests[1].providerInstanceID == "provider-instance-fixture")
    #expect(search.requests[1].username == "listener")
    #expect(search.requests[1].password == "fixture-password")

    search.complete(at: 0, .loaded(searchPage(
        results: [searchResult(id: "stale", title: "Stale")]
    )))
    #expect(store.snapshot.state == .searchLoading)
    #expect(store.snapshot.searchResults.isEmpty)

    search.complete(at: 1, .loaded(searchPage(
        results: [searchResult(id: "track:one", title: "Atlas")],
        trackHasMore: true
    )))
    #expect(store.snapshot.state == .searchResults)
    #expect(store.snapshot.searchResults.map(\.id.rawID) == ["track:one"])
    #expect(store.snapshot.searchHasMoreKinds == [.track])

    store.loadMoreSearchResults(.track)
    #expect(search.requests.count == 3)
    #expect(search.requests[2].trackCount == 20)
    #expect(search.requests[2].trackOffset == 1)
    #expect(search.requests[2].artistCount == 0)
    #expect(search.requests[2].albumCount == 0)
    search.complete(at: 2, .loaded(searchPage(results: [
        searchResult(id: "track:one", title: "Atlas Updated"),
        searchResult(id: "track:two", title: "Atlas North"),
    ])))
    #expect(store.snapshot.searchResults.map(\.title) == ["Atlas Updated", "Atlas North"])
    #expect(store.snapshot.searchHasMoreKinds.isEmpty)

    store.searchQuery = "another"
    await settleSearchTask(until: { search.requests.count == 4 })
    #expect(search.requests.count == 4)
    store.selectDestination(.nowPlaying)
    #expect(search.operations[3].cancelCount == 1)
    #expect(store.snapshot.state == .nowPlayingUnavailable)
}

@Test @MainActor
func searchResultActivationRoutesTracksAlbumsAndArtistsThroughPresentationIntent() async throws {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let search = ControlledServerSearch()
    let playback = ControlledPlaybackController()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        serverSearch: search,
        playbackController: playback,
        searchDebounce: .zero,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))

    let album = fixtureLibraryAlbum()
    let artistID = try #require(album.credits.first?.id)
    let artist = DulcetArtist(id: artistID, name: "Opaque Artist", mediaSourceID: nil)
    store.selectDestination(.library)
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [artist], albums: [album]))

    store.selectDestination(.search)
    store.searchQuery = "opaque"
    await settleSearchTask(until: { search.requests.count == 1 })
    let firstTrack = searchResult(id: "track:search-one", title: "Opaque One")
    let secondTrack = searchResult(id: "track:search-two", title: "Opaque Two")
    let missingDurationTrack = searchResult(
        id: "track:missing-duration",
        title: "Missing Duration",
        duration: nil
    )
    let albumResult = searchResult(
        id: album.id.rawID,
        title: album.title,
        kind: .album,
        duration: album.duration
    )
    let artistResult = searchResult(
        id: artist.id.rawID,
        title: artist.name,
        kind: .artist,
        duration: nil
    )
    search.complete(at: 0, .loaded(searchPage(results: [
        firstTrack,
        secondTrack,
        missingDurationTrack,
        albumResult,
        artistResult,
    ])))

    store.activateSearchResult(missingDurationTrack.id)
    #expect(playback.queueIntents.isEmpty)

    store.activateSearchResult(secondTrack.id)
    let queueIntent = try #require(playback.queueIntents.last)
    #expect(queueIntent.sourceKind == .search)
    #expect(queueIntent.sourceID == nil)
    #expect(queueIntent.tracks.map(\.id) == [firstTrack.id, secondTrack.id])
    #expect(queueIntent.startIndex == 1)

    store.selectDestination(.search)
    store.activateSearchResult(albumResult.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedAlbum?.id == album.id)

    store.selectDestination(.search)
    store.activateSearchResult(artistResult.id)
    #expect(store.snapshot.state == .artistDetail)
    #expect(store.snapshot.selectedArtist == artist)
}

// Regression coverage for a navigation deadlock: PresentationStore.selectDestination moves its own
// `selectedDestination` before the data source has published anything, so any destination handler
// that silently returns without publishing leaves the sidebar highlight and the published snapshot
// disagreeing forever (only a relaunch clears it). openSearch()'s fast path used to be reachable
// with a stale `currentSnapshot.selectedDestination`, because it delegates to startInitialSearch(),
// which guards on that same snapshot before anything has republished it.
@Test @MainActor
func navigatingBackToSearchAfterCancellingAPendingQueryStillPublishesSearch() {
    let connector = ControlledAccountConnector()
    let source = DulcetAccountDataSource(
        connector: connector,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))

    store.selectDestination(.search)
    store.searchQuery = "so"
    #expect(store.snapshot.state == .searchLoading)

    // Navigate away before the debounced request ever fires. cancelSearchRequest() cancels the
    // pending work but does not clear searchResults, so entry condition (a) is now armed: a
    // trimmed query of at least two characters, an empty result set, and no failure.
    store.selectDestination(.settings)
    #expect(store.snapshot.selectedDestination == .settings)

    store.selectDestination(.search)

    #expect(store.selectedDestination == .search)
    #expect(store.snapshot.selectedDestination == .search)
}

@Test @MainActor
func navigatingBackToSearchAfterAZeroResultQueryStillPublishesSearch() async {
    let connector = ControlledAccountConnector()
    let search = ControlledServerSearch()
    let source = DulcetAccountDataSource(
        connector: connector,
        serverSearch: search,
        searchDebounce: .zero,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))

    store.selectDestination(.search)
    store.searchQuery = "zz"
    await settleSearchTask(until: { search.requests.count == 1 })
    search.complete(at: 0, .loaded(searchPage(results: [])))

    #expect(store.snapshot.state == .searchEmpty)
    #expect(store.snapshot.searchResults.isEmpty)

    // Entry condition (b): a query that legitimately returned zero results leaves searchResults
    // empty and searchFailure nil, the same shape openSearch() reads to decide it can start a
    // fresh search inline instead of publishing the destination move itself.
    store.selectDestination(.settings)
    store.selectDestination(.search)

    #expect(store.selectedDestination == .search)
    #expect(store.snapshot.selectedDestination == .search)
}

@Test
func credentialBearingSearchRequestCannotPrintCredentials() {
    let request = DulcetSearchPageRequest(
        providerInstanceID: "provider-fixture",
        normalizedServerURL: "https://music.example.invalid",
        username: "search-user-canary",
        password: "search-password-canary",
        allowLocalHTTP: false,
        query: "atlas",
        artistCount: 20,
        artistOffset: 0,
        albumCount: 20,
        albumOffset: 0,
        trackCount: 20,
        trackOffset: 0
    )
    var renderedDump = ""
    dump(request, to: &renderedDump)
    let rendered = [String(describing: request), String(reflecting: request), renderedDump]

    #expect(rendered.allSatisfy { !$0.contains("search-user-canary") })
    #expect(rendered.allSatisfy { !$0.contains("search-password-canary") })
    #expect(rendered.allSatisfy { $0.contains("<redacted>") })
}

@Test @MainActor
func accountRemovalTimeoutRecoversFromUncooperativeDownloadCleanup() async throws {
    let connector = ControlledAccountConnector()
    let downloads = ControlledDownloadController()
    downloads.suspendRemoval = true
    let source = DulcetAccountDataSource(
        connector: connector,
        downloadController: downloads,
        accountRemovalTimeout: .milliseconds(50)
    )
    let store = DulcetPresentationStore(source: source)
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music", normalizedServerURL: "https://music.example.invalid"
    )))
    store.removeAccount()
    // This continuation ignores cancellation and does not return during the deadline.
    defer { downloads.removalContinuation?.resume(returning: true) }
    let deadline = ContinuousClock.now + .seconds(5)
    while store.snapshot.accountRemoval == .removing && ContinuousClock.now < deadline {
        try await Task.sleep(for: .milliseconds(10))
    }
    #expect(downloads.removeAccountDataCount == 1)
    #expect(store.snapshot.accountRemoval == .failed)
    #expect(store.snapshot.state == .accountRemovalError)
    store.dismissAccountRemovalFailure()
    store.selectDestination(.library)
    #expect(store.selectedDestination == .library)
    #expect(store.snapshot.accountRemoval == .idle)
}

@Test @MainActor
func accountRemovalCancellationRecoversBeforeUncooperativeCleanupReturns() async throws {
    let connector = ControlledAccountConnector()
    let credentials = MemoryCredentialStore(persisted: nil)
    let downloads = ControlledDownloadController()
    downloads.suspendRemoval = true
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials,
        downloadController: downloads,
        accountRemovalTimeout: .seconds(60)
    )
    let store = DulcetPresentationStore(source: source)
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music", normalizedServerURL: "https://music.example.invalid"
    )))
    store.removeAccount()
    let startDeadline = ContinuousClock.now + .seconds(5)
    while downloads.removalContinuation == nil && ContinuousClock.now < startDeadline {
        try await Task.sleep(for: .milliseconds(10))
    }
    #expect(downloads.removalContinuation != nil)
    source.cancelAccountRemoval()
    let deadline = ContinuousClock.now + .seconds(5)
    while store.snapshot.accountRemoval == .removing && ContinuousClock.now < deadline {
        try await Task.sleep(for: .milliseconds(10))
    }
    #expect(store.snapshot.accountRemoval == .failed)
    #expect(store.snapshot.state == .accountRemovalError)
    store.dismissAccountRemovalFailure()
    store.selectDestination(.library)
    #expect(store.selectedDestination == .library)
    // A late success from the cancelled adapter must not sign out the recovered account.
    downloads.removalContinuation?.resume(returning: true)
    downloads.removalContinuation = nil
    try await Task.sleep(for: .milliseconds(50))
    #expect(store.snapshot.accountConnected)
    #expect(try credentials.load() != nil)
    #expect(credentials.deleteCount == 0)
    #expect(store.snapshot.accountRemoval == .idle)
    #expect(store.selectedDestination == .library)
}

@Test(arguments: [false, true]) @MainActor
func recoveryAfterDownloadCleanupRestoresConfigurationBeforeKeepAccount(cancelExplicitly: Bool) async throws {
    let connector = ControlledAccountConnector()
    let credentials = MemoryCredentialStore(persisted: nil)
    let downloads = ControlledDownloadController()
    let artwork = ControlledArtworkFetcher()
    artwork.suspendRemoval = true
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials,
        artworkFetcher: artwork,
        downloadController: downloads,
        accountRemovalTimeout: cancelExplicitly ? .seconds(60) : .milliseconds(50)
    )
    let store = DulcetPresentationStore(source: source)
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music", normalizedServerURL: "https://music.example.invalid"
    )))
    let originalAccount = try #require(downloads.configuredAccount)
    store.removeAccount()
    await settleSearchTask(until: { artwork.removalContinuation != nil })
    #expect(artwork.removalContinuation != nil)
    #expect(downloads.configuredAccount == nil)
    if cancelExplicitly { source.cancelAccountRemoval() }
    await settleSearchTask(until: { store.snapshot.accountRemoval == .failed })
    #expect(store.snapshot.accountRemoval == .failed)
    #expect(downloads.configuredAccount?.providerInstanceID == originalAccount.providerInstanceID)
    store.dismissAccountRemovalFailure()
    #expect(store.snapshot.accountConnected)
    #expect(downloads.configuredAccount != nil)
    artwork.removalContinuation?.resume()
    artwork.removalContinuation = nil
    await Task.yield()
    #expect(credentials.deleteCount == 0)
    #expect(try credentials.load() != nil)
}

@Test(arguments: [false, true]) @MainActor
func keepingAccountAfterCleanupFailurePreservesPersistedCredential(cleanupTimesOut: Bool) async throws {
    let connector = ControlledAccountConnector()
    let credentials = MemoryCredentialStore(persisted: nil)
    let downloads = ControlledDownloadController()
    downloads.removalResult = false
    downloads.suspendRemoval = cleanupTimesOut
    let artwork = ControlledArtworkFetcher()
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials,
        artworkFetcher: artwork,
        downloadController: downloads,
        accountRemovalTimeout: .milliseconds(50)
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music", normalizedServerURL: "https://music.example.invalid"
    )))
    let savedCredential = try #require(try credentials.load())
    store.removeAccount()
    defer { downloads.removalContinuation?.resume(returning: true) }
    let deadline = ContinuousClock.now + .seconds(5)
    while store.snapshot.accountRemoval == .removing && ContinuousClock.now < deadline {
        try await Task.sleep(for: .milliseconds(10))
    }
    #expect(store.snapshot.accountRemoval == .failed)
    store.dismissAccountRemovalFailure()
    #expect(store.snapshot.state == .accountConnected)
    #expect(store.snapshot.accountConnected)
    #expect(try credentials.load() == savedCredential)
    #expect(credentials.deleteCount == 0)
    // This download-stage failure occurs before artwork cleanup, so artwork is retained here.
    // A later-stage failure can follow artwork removal; retry invokes cleanup again.
    #expect(artwork.removedServerIDs.isEmpty)
    let relaunched = DulcetAccountDataSource(
        connector: ControlledAccountConnector(), credentialStore: credentials
    )
    #expect(relaunched.currentSnapshot.state == .accountSavedDisconnected)
}

@Test @MainActor
func accountRemovalDeletesCredentialOnlyAfterCleanupAndBeforeClearingAccountState() async throws {
    var events: [String] = []
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser(onCancel: { events.append("library-cancel") })
    let artworkFetcher = ControlledArtworkFetcher(onRemove: { _ in events.append("artwork-remove") })
    let downloads = ControlledDownloadController(onRemove: { events.append("download-remove") })
    let credentials = MemoryCredentialStore(
        persisted: nil,
        deleteAction: { events.append("credential-delete") }
    )
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials,
        libraryBrowser: libraryBrowser,
        artworkFetcher: artworkFetcher,
        downloadController: downloads,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    store.selectDestination(.library)
    #expect(libraryBrowser.operations.single?.cancelCount == 0)

    store.removeAccount()

    #expect(events == ["library-cancel"])
    #expect(credentials.deleteCount == 0)
    #expect(try credentials.load() != nil)
    #expect(store.snapshot.state == .accountRemoving)
    #expect(store.snapshot.accountRemoval == .removing)
    #expect(store.snapshot.accountConnected)
    store.selectDestination(.library)
    #expect(store.selectedDestination == .settings)
    #expect(libraryBrowser.requests.count == 1)

    await settleSearchTask(until: { events.count == 4 })

    #expect(events == [
        "library-cancel",
        "download-remove",
        "artwork-remove",
        "credential-delete",
    ])
    #expect(credentials.deleteCount == 1)
    #expect(try credentials.load() == nil)
    #expect(downloads.removeAccountDataCount == 1)
    #expect(artworkFetcher.removedServerIDs == ["provider-instance-fixture"])
    #expect(store.snapshot.state == .accountConnectIdle)
    #expect(store.snapshot.accountRemoval == .idle)
    #expect(!store.snapshot.accountConnected)
    #expect(store.snapshot.albums.isEmpty)
    #expect(store.snapshot.searchResults.isEmpty)
    #expect(store.searchQuery.isEmpty)
    #expect(store.accountServerURL.isEmpty)
    #expect(store.accountUsername.isEmpty)
    #expect(store.accountPassword.isEmpty)
    store.selectDestination(.library)
    #expect(store.snapshot.state == .emptyLibraryNoAccount)
}

@Test @MainActor
func failedCredentialDeletionKeepsConnectedLibraryIntactAndCanBeRetried() async throws {
    enum DeleteFailure: Error { case denied }
    let downloads = ControlledDownloadController()
    let deleteDecision = ControlledDeleteDecision()
    var events: [String] = []
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser(onCancel: { events.append("library-cancel") })
    let artworkFetcher = ControlledArtworkFetcher(onRemove: { _ in events.append("artwork-remove") })
    let credentials = MemoryCredentialStore(
        persisted: nil,
        deleteAction: {
            events.append("credential-delete")
            if deleteDecision.shouldFail { throw DeleteFailure.denied }
        }
    )
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials,
        libraryBrowser: libraryBrowser,
        artworkFetcher: artworkFetcher,
        downloadController: downloads,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    store.selectDestination(.library)
    let album = fixtureLibraryAlbum()
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))

    store.removeAccount()

    await settleSearchTask(until: { store.snapshot.accountRemoval == .failed })
    #expect(events == ["artwork-remove", "credential-delete"])
    #expect(credentials.deleteCount == 1)
    #expect(store.snapshot.state == .accountRemovalError)
    #expect(store.snapshot.accountRemoval == .failed)
    #expect(store.snapshot.accountConnected)
    #expect(store.snapshot.albums == [album])
    #expect(store.accountPassword == "fixture-password")
    #expect(artworkFetcher.removedServerIDs == ["provider-instance-fixture"])
    #expect(try credentials.load() != nil)
    #expect(downloads.configuredAccount != nil)
    #expect(libraryBrowser.operations.single?.cancelCount == 0)

    store.dismissAccountRemovalFailure()
    #expect(store.snapshot.state == .accountConnected)
    #expect(store.snapshot.accountRemoval == .idle)
    #expect(store.snapshot.albums == [album])

    deleteDecision.shouldFail = false
    store.removeAccount()
    await settleSearchTask(until: { store.snapshot.accountRemoval == .idle })

    #expect(credentials.deleteCount == 2)
    #expect(events == ["artwork-remove", "credential-delete", "artwork-remove", "credential-delete"])
    #expect(try credentials.load() == nil)
    #expect(store.snapshot.state == .accountConnectIdle)
    #expect(!store.snapshot.accountConnected)
    #expect(store.snapshot.albums.isEmpty)
}

@Test @MainActor
func connectedAccountLoadsOnlyServerSuppliedArtworkKeysWithCurrentCredentials() {
    let connector = ControlledAccountConnector()
    let artworkFetcher = ControlledArtworkFetcher()
    let source = DulcetAccountDataSource(
        connector: connector,
        artworkFetcher: artworkFetcher,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    let reference = DulcetArtworkReference(
        serverID: "provider-instance-fixture",
        artworkKey: "cover:opaque/song"
    )
    var outcome: DulcetArtworkFetchOutcome?

    let operation = store.loadArtwork(reference, sizeBucket: .pixels256) {
        outcome = $0
    }

    #expect(operation != nil)
    #expect(artworkFetcher.requests.count == 1)
    #expect(artworkFetcher.requests[0].reference == reference)
    #expect(artworkFetcher.requests[0].sizeBucket == .pixels256)
    #expect(artworkFetcher.requests[0].normalizedServerURL == "https://music.example.invalid")
    #expect(artworkFetcher.requests[0].username == "listener")
    #expect(artworkFetcher.requests[0].password == "fixture-password")
    artworkFetcher.complete(.loaded(Data([1, 2, 3])))
    guard case let .loaded(data)? = outcome else {
        Issue.record("Artwork completion did not preserve the loaded bytes")
        return
    }
    #expect(data == Data([1, 2, 3]))

    var wrongServerOutcome: DulcetArtworkFetchOutcome?
    let wrongServerOperation = store.loadArtwork(DulcetArtworkReference(
        serverID: "another-provider-instance",
        artworkKey: reference.artworkKey
    ), sizeBucket: .pixels256) {
        wrongServerOutcome = $0
    }
    #expect(wrongServerOperation == nil)
    if case .unavailable = wrongServerOutcome {
        // Expected: a provider-scoped reference cannot cross into another account.
    } else {
        Issue.record("Cross-account artwork reference was not rejected")
    }
    #expect(artworkFetcher.requests.count == 1)
}

@Test @MainActor
func lateCancelSuppressesQueuedSuccessAndCredentialPersistence() {
    let connector = ControlledAccountConnector()
    let credentials = MemoryCredentialStore(persisted: nil)
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials
    )
    let store = DulcetPresentationStore(source: source)

    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()

    store.cancelAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))

    #expect(connector.operation.cancelCount == 1)
    #expect(store.snapshot.state == .accountConnectIdle)
    #expect(!store.snapshot.accountConnected)
    #expect(credentials.saved.isEmpty)
}

@Test @MainActor
func replacementSubmissionCancelsThePreviousOperationAndOwnsTheOutcome() {
    let connector = SequencedAccountConnector()
    let credentials = MemoryCredentialStore(persisted: nil)
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials
    )
    let first = DulcetAccountConnectRequest(
        serverURL: "https://first.example.invalid",
        username: "first-listener",
        password: "first-password",
        allowLocalHTTP: false
    )
    let second = DulcetAccountConnectRequest(
        serverURL: "https://second.example.invalid",
        username: "second-listener",
        password: "second-password",
        allowLocalHTTP: false
    )

    source.send(.submitAccountConnection(first))
    source.send(.submitAccountConnection(second))

    #expect(connector.requests == [first, second])
    #expect(connector.operations[0].cancelCount == 1)
    #expect(connector.operations[1].cancelCount == 0)

    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "First",
        normalizedServerURL: first.serverURL
    )), at: 0)
    #expect(credentials.saved.isEmpty)
    #expect(source.currentSnapshot.state == .accountConnecting)

    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Second",
        normalizedServerURL: second.serverURL
    )), at: 1)
    #expect(credentials.saved == [second])
    #expect(source.currentSnapshot.state == .accountConnected)
    #expect(source.currentSnapshot.accountForm == second)
}

@Test
func accountDomainErrorsHaveATotalActionablePresentation() {
    let presentations = DulcetAccountFailureKind.allCases.map { kind in
        DulcetAccountErrorPresenter.presentation(for: DulcetAccountErrorContext(
            kind: kind,
            serverName: "Music server",
            targetHost: kind == .crossOriginRedirectRejected
                ? "login.example.invalid"
                : nil
        ))
    }

    #expect(Set(presentations.map(\.kind)) == Set(DulcetAccountFailureKind.allCases))
    #expect(presentations.allSatisfy {
        !$0.title.isEmpty && !$0.message.isEmpty && !$0.recovery.isEmpty
    })

    let tls = presentations.first { $0.kind == .tlsUntrusted }
    #expect(tls?.recovery.localizedCaseInsensitiveContains("install") == true)
    #expect(tls?.recovery.localizedCaseInsensitiveContains("CA") == true)
    #expect(tls?.recovery.localizedCaseInsensitiveContains("operating-system") == true)

    let internationalizedHost = DulcetAccountErrorPresenter.presentation(
        for: DulcetAccountErrorContext(
            kind: .invalidServerURL,
            serverName: "müsik.example.invalid",
            invalidServerURLIsInternationalized: true
        )
    )
    #expect(internationalizedHost.recovery.localizedCaseInsensitiveContains("punycode"))
    #expect(internationalizedHost.recovery.contains("xn--msik-0ra.example.invalid"))

    let crossOrigin = presentations.first { $0.kind == .crossOriginRedirectRejected }
    #expect(crossOrigin?.message.contains("login.example.invalid") == true)
    #expect(crossOrigin?.recovery.contains("/rest/") == true)
    #expect(crossOrigin?.recovery.localizedCaseInsensitiveContains("SSO") == true)
    #expect(crossOrigin?.message.contains("/") == false)
    #expect(crossOrigin?.message.contains("?") == false)
}

@Test @MainActor
func unentitledKeychainWriteFailsClosedWithoutLegacyFallback() throws {
    let identifier = UUID().uuidString
    let suiteName = "com.legitimateapps.dulcet.tests.\(identifier)"
    let service = "com.legitimateapps.dulcet.tests.\(identifier)"
    let activeAccountKey = "active-account"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    let store = DulcetKeychainCredentialStore(
        service: service,
        defaults: defaults,
        activeAccountKey: activeAccountKey
    )
    let request = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let legacyQuery: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: service,
        kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    defer {
        try? store.delete()
        SecItemDelete(legacyQuery as CFDictionary)
        defaults.removePersistentDomain(forName: suiteName)
    }

    var observedError: DulcetCredentialStoreError?
    do {
        try store.save(request)
    } catch let error as DulcetCredentialStoreError {
        observedError = error
    } catch {
        throw error
    }

    #expect(observedError == .missingDataProtectionKeychainEntitlement)
    #expect(defaults.string(forKey: activeAccountKey) == nil)
    let legacyLookupStatus = SecItemCopyMatching(legacyQuery as CFDictionary, nil)
#if os(macOS)
    #expect(legacyLookupStatus == errSecItemNotFound)
#else
    #expect(legacyLookupStatus == errSecMissingEntitlement)
#endif
}

@Test @MainActor
func relaunchPrefillsKeychainCredentialsButWaitsForExplicitReconnect() {
    let persisted = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let credentials = MemoryCredentialStore(persisted: persisted)
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials,
        libraryBrowser: libraryBrowser
    )
    let store = DulcetPresentationStore(source: source)

    #expect(store.snapshot.state == .accountSavedDisconnected)
    #expect(store.snapshot.accountForm == persisted)
    #expect(!store.snapshot.accountConnected)
    #expect(store.snapshot.accountConnection == .saved(serverName: "music.example.invalid"))
    #expect(store.snapshot.connectivity == .disconnected(serverName: "music.example.invalid"))
    #expect(connector.requests.isEmpty)
    #expect(libraryBrowser.requests.isEmpty)

    store.selectDestination(.library)

    #expect(store.snapshot.state == .accountSavedDisconnected)
    #expect(store.snapshot.selectedDestination == .library)
    #expect(store.snapshot.accountConnection == .saved(serverName: "music.example.invalid"))
    #expect(connector.requests.isEmpty)
    #expect(libraryBrowser.requests.isEmpty)
    #expect(DulcetStrings.reconnectToServer("music.example.invalid") == "Reconnect to music.example.invalid")
    #expect(DulcetStrings.savedAccountDisconnectedBody.localizedCaseInsensitiveContains("saved"))
    #expect(!DulcetStrings.savedAccountDisconnectedBody.contains(DulcetStrings.firstRunTitle))

    store.submitAccountConnection()
    #expect(connector.requests == [persisted])
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: persisted.serverURL
    )))

    #expect(credentials.saved == [persisted])
    // Reconnect was pressed on the library surface, so it lands in the library rather than on the
    // account pane. The CONF-10b guarantee is the one asserted above -- that neither the connector
    // nor the browser was touched before the person chose to reconnect -- and it is unchanged.
    #expect(store.snapshot.selectedDestination == .library)
    #expect(store.snapshot.state == .libraryLoading)
    #expect(libraryBrowser.requests.count == 1)
}

@Test @MainActor
func cancellingARestoredReconnectReturnsToSavedDisconnectedState() {
    let persisted = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: false
    )
    let connector = ControlledAccountConnector()
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: MemoryCredentialStore(persisted: persisted)
    )
    let store = DulcetPresentationStore(source: source)

    store.submitAccountConnection()
    store.cancelAccountConnection()

    #expect(connector.operation.cancelCount == 1)
    #expect(store.snapshot.state == .accountSavedDisconnected)
    #expect(store.snapshot.accountConnection == .saved(serverName: "music.example.invalid"))
    #expect(store.snapshot.connectivity == .disconnected(serverName: "music.example.invalid"))
}

@MainActor
private final class ControlledAccountConnector: DulcetAccountConnecting {
    let operation = ControlledAccountOperation()
    private(set) var requests: [DulcetAccountConnectRequest] = []
    private var completion: (@MainActor (DulcetAccountConnectOutcome) -> Void)?

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        requests.append(request)
        self.completion = completion
        return operation
    }

    func complete(_ outcome: DulcetAccountConnectOutcome) {
        completion?(outcome)
        completion = nil
    }
}

@MainActor
private final class ControlledAccountOperation: DulcetAccountConnectOperation {
    private(set) var cancelCount = 0

    func cancel() {
        cancelCount += 1
    }
}

@MainActor
private final class ControlledLibraryBrowser: DulcetLibraryBrowsing, DulcetAlbumTracksLoading {
    private let onCancel: @MainActor () -> Void
    private(set) var requests: [DulcetLibraryBrowseRequest] = []
    private(set) var operations: [ControlledLibraryOperation] = []
    private(set) var albumTrackRequests: [String] = []
    private var completions: [(@MainActor (DulcetLibraryBrowseOutcome) -> Void)] = []
    private var delivered: [(@MainActor (DulcetLibraryBrowseOutcome) -> Void)] = []
    private var albumTrackCompletions: [(@MainActor (DulcetAlbumTracksOutcome) -> Void)] = []

    init(onCancel: @escaping @MainActor () -> Void = {}) {
        self.onCancel = onCancel
    }

    func browse(
        _ request: DulcetLibraryBrowseRequest,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        let operation = ControlledLibraryOperation(onCancel: onCancel)
        requests.append(request)
        operations.append(operation)
        completions.append(completion)
        return operation
    }

    func loadAlbumTracks(
        _ request: DulcetLibraryBrowseRequest,
        albumRawID: String,
        completion: @escaping @MainActor (DulcetAlbumTracksOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        albumTrackRequests.append(albumRawID)
        albumTrackCompletions.append(completion)
        return ControlledLibraryOperation(onCancel: onCancel)
    }

    func complete(_ outcome: DulcetLibraryBrowseOutcome) {
        guard !completions.isEmpty else { return }
        let completion = completions.removeFirst()
        delivered.append(completion)
        completion(outcome)
    }

    /// The production browser answers one library open more than once — a fast preview, then the
    /// committed library — so a double is only faithful if it can do the same.
    func completeAgain(_ outcome: DulcetLibraryBrowseOutcome) {
        delivered.last?(outcome)
    }

    func completeAlbumTracks(_ outcome: DulcetAlbumTracksOutcome) {
        guard !albumTrackCompletions.isEmpty else { return }
        albumTrackCompletions.removeFirst()(outcome)
    }
}

@MainActor
private final class ControlledLibraryOperation: DulcetLibraryBrowseOperation {
    private let onCancel: @MainActor () -> Void
    private(set) var cancelCount = 0

    init(onCancel: @escaping @MainActor () -> Void = {}) {
        self.onCancel = onCancel
    }

    func cancel() {
        cancelCount += 1
        onCancel()
    }
}

@MainActor
private final class ControlledServerSearch: DulcetServerSearching {
    private(set) var requests: [DulcetSearchPageRequest] = []
    private(set) var operations: [ControlledSearchOperation] = []
    private var completions: [(@MainActor (DulcetSearchPageOutcome) -> Void)] = []

    func search(
        _ request: DulcetSearchPageRequest,
        completion: @escaping @MainActor (DulcetSearchPageOutcome) -> Void
    ) -> any DulcetSearchOperation {
        let operation = ControlledSearchOperation()
        requests.append(request)
        operations.append(operation)
        completions.append(completion)
        return operation
    }

    func complete(at index: Int, _ outcome: DulcetSearchPageOutcome) {
        completions[index](outcome)
    }
}

@MainActor
private final class ControlledSearchOperation: DulcetSearchOperation {
    private(set) var cancelCount = 0

    func cancel() {
        cancelCount += 1
    }
}

@MainActor
private final class ControlledArtworkFetcher: DulcetArtworkFetching, DulcetArtworkCacheRemoving {
    private let onRemove: @MainActor (String) -> Void
    private(set) var requests: [DulcetArtworkFetchRequest] = []
    private(set) var removedServerIDs: [String] = []
    var suspendRemoval = false
    var removalContinuation: CheckedContinuation<Void, Never>?
    private let operation = ControlledArtworkOperation()
    private var completion: (@MainActor (DulcetArtworkFetchOutcome) -> Void)?

    init(onRemove: @escaping @MainActor (String) -> Void = { _ in }) {
        self.onRemove = onRemove
    }

    func fetch(
        _ request: DulcetArtworkFetchRequest,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> any DulcetArtworkFetchOperation {
        requests.append(request)
        self.completion = completion
        return operation
    }

    func complete(_ outcome: DulcetArtworkFetchOutcome) {
        completion?(outcome)
        completion = nil
    }

    func removeCachedArtwork(serverID: String) async {
        removedServerIDs.append(serverID)
        onRemove(serverID)
        if suspendRemoval {
            await withCheckedContinuation { removalContinuation = $0 }
        }
    }
}

@MainActor
private final class ControlledArtworkOperation: DulcetArtworkFetchOperation {
    private(set) var cancelCount = 0

    func cancel() {
        cancelCount += 1
    }
}

@MainActor
private final class ControlledPlaybackController: DulcetPlaybackControlling {
    private var handler: (@MainActor (DulcetPlaybackPresentation) -> Void)?
    private(set) var configuredProviderInstanceID: String?
    private(set) var queueIntents: [DulcetPlaybackQueueIntent] = []
    private(set) var controlIntents: [DulcetPlaybackControlIntent] = []
    private(set) var restoredCatalogs: [[DulcetTrack]] = []
    private(set) var disconnectCount = 0
    private(set) var currentPresentation: DulcetPlaybackPresentation = .unavailable

    func setPresentationHandler(
        _ handler: @escaping @MainActor (DulcetPlaybackPresentation) -> Void
    ) {
        self.handler = handler
    }

    func configure(account: DulcetPlaybackAccount) {
        configuredProviderInstanceID = account.providerInstanceID
    }

    func replaceQueueAndPlay(_ intent: DulcetPlaybackQueueIntent) {
        queueIntents.append(intent)
        publish(DulcetPlaybackPresentation(status: .preparing, nowPlaying: nil))
    }

    func restorePersistedQueue(with tracks: [DulcetTrack]) {
        restoredCatalogs.append(tracks)
    }

    func send(_ intent: DulcetPlaybackControlIntent) {
        controlIntents.append(intent)
    }

    func disconnect() {
        disconnectCount += 1
        publish(.unavailable)
    }

    func publish(_ presentation: DulcetPlaybackPresentation) {
        currentPresentation = presentation
        handler?(presentation)
    }
}

@MainActor
private final class ControlledDownloadController: DulcetDownloadControlling {
    var downloadsEnabled = true
    private var handler: (@MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void)?
    private(set) var configuredAccount: DulcetPlaybackAccount?
    private(set) var requestedTracks: [DulcetTrack] = []
    var removalResult = true
    var suspendRemoval = false
    var removalContinuation: CheckedContinuation<Bool, Never>?
    private(set) var removeAccountDataCount = 0
    private let onRemove: @MainActor () -> Void

    init(onRemove: @escaping @MainActor () -> Void = {}) {
        self.onRemove = onRemove
    }

    func setStatusHandler(
        _ handler: @escaping @MainActor (DulcetProviderItemID, DulcetDownloadState) -> Void
    ) {
        self.handler = handler
    }

    func configure(account: DulcetPlaybackAccount) {
        configuredAccount = account
    }

    func requestDownload(_ track: DulcetTrack) {
        requestedTracks.append(track)
    }

    func status(for id: DulcetProviderItemID) -> DulcetDownloadState {
        .notDownloaded
    }

    func offlinePlaybackAsset(for track: DulcetTrack) -> DulcetOfflinePlaybackAsset? {
        nil
    }

    func removeAccountData() async -> Bool {
        removeAccountDataCount += 1
        onRemove()
        if suspendRemoval {
            return await withCheckedContinuation { removalContinuation = $0 }
        }
        if removalResult { configuredAccount = nil }
        return removalResult
    }

    func disconnect() {}

    func publish(_ state: DulcetDownloadState, for id: DulcetProviderItemID) {
        handler?(id, state)
    }
}

@MainActor
/// The album as the grid receives it after a first paint: the server's declared track count and
/// no track list at all.
private func fixtureUnreadAlbum(trackCount: Int = 12) -> DulcetAlbum {
    let album = fixtureLibraryAlbum()
    return DulcetAlbum(
        id: album.id,
        title: album.title,
        credits: album.credits,
        year: album.year,
        duration: album.duration,
        mediaSourceID: album.mediaSourceID,
        artwork: album.artwork,
        tracks: [],
        trackCount: trackCount,
        areTracksLoaded: false
    )
}

private func fixtureLibraryAlbum() -> DulcetAlbum {
    let providerID = "provider-instance-fixture"
    let artistID = DulcetProviderItemID(providerInstanceID: providerID, rawID: "artist:opaque")
    let credit = DulcetCredit(role: .albumArtist, name: "Opaque Artist", id: artistID)
    let track = DulcetTrack(
        id: DulcetProviderItemID(providerInstanceID: providerID, rawID: "track:opaque"),
        title: "Opaque Track",
        credits: [DulcetCredit(role: .artist, name: "Opaque Artist", id: artistID)],
        albumTitle: "Opaque Album",
        discNumber: 1,
        trackNumber: 1,
        duration: .seconds(61),
        mediaSourceID: nil,
        artwork: DulcetArtwork(seed: "track:opaque", palette: .indigoCoral)
    )
    return DulcetAlbum(
        id: DulcetProviderItemID(providerInstanceID: providerID, rawID: "album:opaque"),
        title: "Opaque Album",
        credits: [credit],
        year: 2026,
        duration: .seconds(61),
        mediaSourceID: nil,
        artwork: DulcetArtwork(seed: "album:opaque", palette: .indigoCoral),
        tracks: [track]
    )
}

@MainActor
// A fixed sleep is only long enough while nothing else is competing for the main actor, and that
// is not a property a test can rely on: Swift Testing runs the whole suite concurrently in one
// process, so any @MainActor test added later silently steals the window this one is counting on.
// That is not hypothetical — adding a test that builds a window per presentation state made the
// debounced-search assertions below fail, and the failure read as "search issued no request"
// rather than "the settle expired". Wait for the effect, with a deadline, so a genuine regression
// still fails and mere contention does not.
private func settleSearchTask(until condition: () -> Bool) async {
    let deadline = ContinuousClock.now + .seconds(5)
    while ContinuousClock.now < deadline {
        if condition() { return }
        try? await Task.sleep(for: .milliseconds(5))
    }
}

// Absence cannot be waited for, so this one still spends a fixed interval. It is only ever used to
// assert that nothing happened, where expiring early is the safe direction.
private func settleSearchTask() async {
    try? await Task.sleep(for: .milliseconds(20))
}

@MainActor
private func searchResult(
    id: String,
    title: String,
    kind: DulcetSearchResultKind = .track,
    duration: Duration? = .seconds(61)
) -> DulcetSearchResult {
    DulcetSearchResult(
        id: DulcetProviderItemID(
            providerInstanceID: "provider-instance-fixture",
            rawID: id
        ),
        title: title,
        kind: kind,
        credits: kind == .artist ? [] : [DulcetCredit(
            role: kind == .album ? .albumArtist : .artist,
            name: "Fixture Artist",
            id: nil
        )],
        albumTitle: "Fixture Album",
        year: 2026,
        duration: duration,
        mediaSourceID: nil,
        artwork: DulcetArtwork(seed: id, palette: .indigoCoral)
    )
}

@MainActor
private func searchPage(
    results: [DulcetSearchResult],
    trackHasMore: Bool = false
) -> DulcetSearchPage {
    DulcetSearchPage(
        results: results,
        artistResultCount: results.count { $0.kind == .artist },
        albumResultCount: results.count { $0.kind == .album },
        trackResultCount: results.count { $0.kind == .track },
        artistHasMore: false,
        albumHasMore: false,
        trackHasMore: trackHasMore
    )
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}

@MainActor
private final class SequencedAccountConnector: DulcetAccountConnecting {
    private(set) var requests: [DulcetAccountConnectRequest] = []
    private(set) var operations: [ControlledAccountOperation] = []
    private var completions: [Int: (@MainActor (DulcetAccountConnectOutcome) -> Void)] = [:]

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        let index = requests.count
        let operation = ControlledAccountOperation()
        requests.append(request)
        operations.append(operation)
        completions[index] = completion
        return operation
    }

    func complete(_ outcome: DulcetAccountConnectOutcome, at index: Int) {
        completions.removeValue(forKey: index)?(outcome)
    }
}

@MainActor
private final class MemoryCredentialStore: DulcetCredentialStoring {
    private var persisted: DulcetAccountConnectRequest?
    private(set) var saved: [DulcetAccountConnectRequest] = []
    private(set) var deleteCount = 0
    private let deleteAction: @MainActor () throws -> Void

    init(
        persisted: DulcetAccountConnectRequest?,
        deleteAction: @escaping @MainActor () throws -> Void = {}
    ) {
        self.persisted = persisted
        self.deleteAction = deleteAction
    }

    func load() throws -> DulcetAccountConnectRequest? {
        persisted
    }

    func save(_ request: DulcetAccountConnectRequest) throws {
        saved.append(request)
        persisted = request
    }

    func delete() throws {
        deleteCount += 1
        try deleteAction()
        persisted = nil
    }
}

@MainActor
private final class ControlledDeleteDecision {
    var shouldFail = true
}

// MARK: - Lazy album track lists

@MainActor
private func connectedLibraryStore(
    connector: ControlledAccountConnector,
    libraryBrowser: ControlledLibraryBrowser,
    playback: ControlledPlaybackController
) -> DulcetPresentationStore {
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        playbackController: playback,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    store.selectDestination(.library)
    return store
}

/// The control for the whole change on this side of the boundary: drawing the library must not
/// read a single album. Asserting "the library appeared" passed before this change too.
@Test @MainActor
func theAlbumGridIsDrawnWithoutReadingAnyAlbumsTrackList() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let store = connectedLibraryStore(
        connector: ControlledAccountConnector(),
        libraryBrowser: libraryBrowser,
        playback: playback
    )

    let albums = (0 ..< 25).map { index in
        let base = fixtureUnreadAlbum(trackCount: index + 1)
        return DulcetAlbum(
            id: DulcetProviderItemID(
                providerInstanceID: "provider-instance-fixture",
                rawID: "album:opaque-\(index)"
            ),
            title: base.title,
            credits: base.credits,
            year: base.year,
            duration: base.duration,
            mediaSourceID: base.mediaSourceID,
            artwork: base.artwork,
            tracks: [],
            trackCount: index + 1,
            areTracksLoaded: false
        )
    }
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: albums))

    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.snapshot.albums.count == 25)
    #expect(store.snapshot.albums.map(\.trackCount) == Array(1 ... 25))
    #expect(store.snapshot.albums.allSatisfy { $0.tracks.isEmpty })
    #expect(store.snapshot.albums.allSatisfy { !$0.areTracksLoaded })
    #expect(libraryBrowser.albumTrackRequests.isEmpty)

    // The instrument is not blind: opening one album moves the counter off zero, and by exactly
    // one, so the zero above is a measurement.
    store.selectAlbum(try #require(albums.first).id)
    #expect(libraryBrowser.albumTrackRequests == ["album:opaque-0"])
}

@Test @MainActor
func openingAnAlbumReadsItsTracksAndReRunsQueueRestoration() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let store = connectedLibraryStore(
        connector: ControlledAccountConnector(),
        libraryBrowser: libraryBrowser,
        playback: playback
    )
    let album = fixtureUnreadAlbum()
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))

    // The first paint knows no tracks, so the catalog handed to the controller is empty. The
    // controller decides what to do with that; the library must not withhold the call.
    #expect(playback.restoredCatalogs.last?.isEmpty == true)

    store.selectAlbum(album.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedAlbum?.areTracksLoaded == false)
    #expect(store.snapshot.selectedAlbumTracksFailure == nil)
    #expect(libraryBrowser.albumTrackRequests == ["album:opaque"])

    let tracks = fixtureLibraryAlbum().tracks
    libraryBrowser.completeAlbumTracks(.loaded(tracks))

    let selected = try #require(store.snapshot.selectedAlbum)
    #expect(selected.areTracksLoaded)
    #expect(selected.tracks.map(\.id) == tracks.map(\.id))
    #expect(selected.trackCount == tracks.count)
    #expect(store.snapshot.albums.first?.tracks.map(\.id) == tracks.map(\.id))
    // The saved queue can now be restored, because these tracks are known.
    #expect(playback.restoredCatalogs.last?.map(\.id) == tracks.map(\.id))
}

@Test @MainActor
func aFailedTrackReadIsShownOnTheAlbumAndTheRetryReadsItAgain() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let store = connectedLibraryStore(
        connector: ControlledAccountConnector(),
        libraryBrowser: libraryBrowser,
        playback: playback
    )
    let album = fixtureUnreadAlbum()
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))

    store.selectAlbum(album.id)
    libraryBrowser.completeAlbumTracks(.failed(DulcetLibraryFailure(kind: .timeout)))

    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedAlbumTracksFailure?.kind == .timeout)
    #expect(store.snapshot.selectedAlbum?.areTracksLoaded == false)

    store.retryAlbumTracks()
    #expect(store.snapshot.selectedAlbumTracksFailure == nil)
    #expect(libraryBrowser.albumTrackRequests == ["album:opaque", "album:opaque"])

    let tracks = fixtureLibraryAlbum().tracks
    libraryBrowser.completeAlbumTracks(.loaded(tracks))
    #expect(store.snapshot.selectedAlbum?.tracks.map(\.id) == tracks.map(\.id))
    #expect(store.snapshot.selectedAlbumTracksFailure == nil)
}

/// A library open publishes more than once now — a fast preview, then the committed library. The
/// later one must not throw someone out of the album they are reading.
@Test @MainActor
func aSecondLibraryPublicationKeepsThePersonOnTheAlbumTheyAreReading() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let store = connectedLibraryStore(
        connector: ControlledAccountConnector(),
        libraryBrowser: libraryBrowser,
        playback: playback
    )
    let album = fixtureUnreadAlbum()
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    store.selectAlbum(album.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)

    let complete = fixtureLibraryAlbum()
    libraryBrowser.completeAgain(.loaded(musicFolders: [], artists: [], albums: [complete]))

    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedAlbum?.id == album.id)
    #expect(store.snapshot.selectedAlbum?.areTracksLoaded == true)
    #expect(store.snapshot.selectedAlbum?.tracks.map(\.id) == complete.tracks.map(\.id))
}
