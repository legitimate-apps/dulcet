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

// Conditional presentation coverage: connector outcomes are injected, not production-derived.
// The connector controls completion timing/outcomes; credential stores control load/save results.
@Test @MainActor
func accountPresentationTransitionsGivenConnectorOutcomes() {
    let request = DulcetAccountConnectRequest(
        serverURL: "https://music.example.invalid", username: "listener",
        password: "fixture-password", allowLocalHTTP: false
    )
    let success = DulcetAccountConnectOutcome.connected(DulcetConnectedAccountSummary(
        serverName: "Music", normalizedServerURL: request.serverURL
    ))
    var observed = Set<DulcetPresentationState>()

    func makeStore(
        _ connector: ControlledAccountConnector,
        credentials: any DulcetCredentialStoring = MemoryCredentialStore(persisted: nil)
    ) -> DulcetPresentationStore {
        let store = DulcetPresentationStore(source: DulcetAccountDataSource(
            connector: connector, credentialStore: credentials
        ))
        store.accountServerURL = request.serverURL
        store.accountUsername = request.username
        store.accountPassword = request.password
        return store
    }

    let connector = ControlledAccountConnector()
    let credentials = MemoryCredentialStore(persisted: nil)
    let store = makeStore(connector, credentials: credentials)
    observed.insert(store.snapshot.state)
    store.submitAccountConnection()
    #expect(connector.requests == [request])
    observed.insert(store.snapshot.state)
    connector.complete(success)
    observed.insert(store.snapshot.state)
    #expect(credentials.saved == [request])

    // Reconstruct from credentials actually saved by the successful production submission.
    let restoredConnector = ControlledAccountConnector()
    let restored = makeStore(restoredConnector, credentials: credentials)
    observed.insert(restored.snapshot.state)
    #expect(restoredConnector.requests.isEmpty)

    // Reach every distinct domain-error family through the connector completion path.
    for kind in DulcetAccountFailureKind.allCases
        where kind != .transportCancelled && kind != .credentialPersistenceFailed {
        let failingConnector = ControlledAccountConnector()
        let failed = makeStore(failingConnector)
        failed.submitAccountConnection()
        #expect(failingConnector.requests == [request])
        failingConnector.complete(.failed(DulcetAccountErrorPresenter.presentation(
            for: DulcetAccountErrorContext(kind: kind, serverName: "Music")
        )))
        observed.insert(failed.snapshot.state)
    }

    let persistenceConnector = ControlledAccountConnector()
    let persistenceFailed = makeStore(
        persistenceConnector, credentials: Conf09bFailingSaveCredentialStore()
    )
    persistenceFailed.submitAccountConnection()
    #expect(persistenceConnector.requests == [request])
    persistenceConnector.complete(success)
    observed.insert(persistenceFailed.snapshot.state)

    // Explicit expected states keep a broken production mapping from changing the oracle too.
    // accountConnectEmpty and tlsUntrusted* are fixture variants, not distinct live states;
    // accountRemoving/accountRemovalError belong to account removal.
    #expect(observed == Set<DulcetPresentationState>([
        .accountConnectIdle, .accountConnecting, .accountSavedDisconnected, .accountConnected,
        .accountErrorInput, .accountErrorTransport, .accountErrorSecurity, .accountErrorProtocol,
        .accountErrorServer, .accountErrorAuthentication, .accountErrorCapability,
        .accountErrorPersistence,
    ]), "Injected outcomes did not produce the expected presentation transitions")
}

@MainActor
private final class Conf09bFailingSaveCredentialStore: DulcetCredentialStoring {
    func load() throws -> DulcetAccountConnectRequest? { nil }
    func save(_ request: DulcetAccountConnectRequest) throws {
        throw DulcetCredentialStoreError.missingDataProtectionKeychainEntitlement
    }
    func delete() throws {}
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

    // Re-entering a library that is already held redraws it without contacting the server, so
    // there is no second request and nothing in flight for the next departure to cancel. This
    // previously asserted the opposite — that re-entry starts a read and leaving cancels it —
    // which was the defect, not the contract. The cancellation control it carried now lives in
    // `leavingTheLibraryCancelsAReadThatHasNotFinished`, where a read really is in flight.
    store.selectDestination(.library)
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.snapshot.albums == [album])
    #expect(libraryBrowser.requests.count == 1)
    #expect(libraryBrowser.operations.last?.cancelCount == 0)
    store.selectDestination(.search)
    #expect(libraryBrowser.requests.count == 1)
    #expect(libraryBrowser.operations.last?.cancelCount == 0)
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

/// A person typing into search drives one store write per character, and every one of those
/// writes republishes a snapshot that carries a query string back to the text field. If a
/// publication ever carried a query composed before the character that triggered it, the field
/// would be reset to older text and the person would lose what they had just typed.
///
/// One apple-ci run read the field mid-typing and reported six of nine characters, which looks
/// exactly like that. It is not: traced against the app's own text binding on an iPad Pro 11-inch
/// (M5) simulator, with per-keystroke cost induced at 0, 20, 60, 150, 400 and 1000 ms, all 217
/// publications echoed the live query and no value regressed across 84 keystrokes. This pins that
/// property so a future change -- capturing the query when a request starts, say, instead of
/// reading it at publish time -- fails here in milliseconds rather than as a rare UI flake.
@Test @MainActor
func searchPublicationNeverCarriesAQueryOlderThanTheTypedText() async {
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

    // One write per character, as the text field produces them. Checking after every character is
    // the point: an overwrite would be repaired by the next character if only the end were
    // checked, so the final value alone cannot see it.
    var typed = ""
    for character in "atlas" {
        typed.append(character)
        store.searchQuery = typed
        #expect(store.searchQuery == typed)
        #expect(store.snapshot.searchQuery == typed)
    }

    // A publication that arrives after further typing must still carry the later text. The
    // in-flight request was composed for "atlas"; completing it once the person has typed more
    // must not put "atlas" back in the field.
    await settleSearchTask(until: { search.requests.count == 1 })
    #expect(search.requests.first?.query == "atlas")
    store.searchQuery = "atlas north"
    await settleSearchTask(until: { search.requests.count == 2 })
    search.complete(at: search.requests.count - 1, .loaded(searchPage(
        results: [searchResult(id: "track:one", title: "Atlas North")]
    )))
    #expect(store.snapshot.state == .searchResults)
    #expect(store.searchQuery == "atlas north")
    #expect(store.snapshot.searchQuery == "atlas north")

    // Clearing is a genuine source-initiated change and must still reach the field, so the
    // property above cannot be satisfied by refusing to apply snapshots at all.
    store.searchQuery = ""
    #expect(store.searchQuery.isEmpty)
    #expect(store.snapshot.searchQuery.isEmpty)
    #expect(store.snapshot.state == .searchIdle)
}

// Synthetic offset-respecting server rows, not an observed reference-server response.
@Test(arguments: [false, true]) @MainActor
func searchConsumedRowsReachLaterUniqueResultsForEveryKind(crossPageOverlap: Bool) async throws {
    // Internally unique pages with five cross-page overlaps exercise the caller's merge too.
    let firstPage: [String] = crossPageOverlap ? (0..<20).map { String($0) } : Array(repeating: "A", count: 20)
    let secondPage: [String] = crossPageOverlap ? (15..<35).map { String($0) } : Array(repeating: "B", count: 20)
    let rows = firstPage + secondPage + ["C"]
    let connector = ControlledAccountConnector()
    let search = ControlledServerSearch()
    let source = DulcetAccountDataSource(
        connector: connector, serverSearch: search, searchDebounce: .zero,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music", normalizedServerURL: "https://music.example.invalid"
    )))
    store.selectDestination(.search)
    store.searchQuery = "atlas"
    await settleSearchTask(until: { search.requests.count == 1 })

    func respond(_ index: Int) {
        let request = search.requests[index]
        let artists = Array(rows.dropFirst(request.artistOffset).prefix(request.artistCount))
        let albums = Array(rows.dropFirst(request.albumOffset).prefix(request.albumCount))
        let tracks = Array(rows.dropFirst(request.trackOffset).prefix(request.trackCount))
        search.complete(at: index, .loaded(rawSearchPage(artists: artists, albums: albums, tracks: tracks)))
    }
    respond(0)
    for kind in [DulcetSearchResultKind.artist, .album, .track] {
        var offsets: [Int] = [0]
        // Bounded driver: with intra-page duplicates, the old caller stalls at 2 before C.
        for _ in 0..<5 where store.snapshot.searchHasMoreKinds.contains(kind) {
            let index = search.requests.count
            store.loadMoreSearchResults(kind)
            let request = try #require(search.requests.last)
            offsets.append(kind == .artist ? request.artistOffset : kind == .album ? request.albumOffset : request.trackOffset)
            respond(index)
        }
        #expect(offsets == [0, 20, 40])
        var seen: Set<String> = []
        let expectedIDs = rows.filter { seen.insert($0).inserted }.map { "\(kind)-\($0)" }
        #expect(store.snapshot.searchResults.filter { $0.kind == kind }.map(\.id.rawID) == expectedIDs)
        #expect(!store.snapshot.searchHasMoreKinds.contains(kind))
    }
}

@Test @MainActor
func searchRepeatedFullPagesAreOneRequestPerActivationAndFailuresPreserveCursors() async throws {
    let connector = ControlledAccountConnector()
    let search = ControlledServerSearch()
    let source = DulcetAccountDataSource(
        connector: connector, serverSearch: search, searchDebounce: .zero,
        providerInstanceIDFactory: { "provider-instance-fixture" }
    )
    let store = DulcetPresentationStore(source: source)
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music", normalizedServerURL: "https://music.example.invalid"
    )))
    store.selectDestination(.search)
    store.searchQuery = "atlas"
    await settleSearchTask(until: { search.requests.count == 1 })
    let repeated = Array(repeating: "A", count: 20)
    search.complete(at: 0, .loaded(rawSearchPage(artists: repeated, albums: repeated, tracks: repeated)))
    for kind in [DulcetSearchResultKind.artist, .album, .track] {
        func offset(_ request: DulcetSearchPageRequest) -> Int {
            kind == .artist ? request.artistOffset : kind == .album ? request.albumOffset : request.trackOffset
        }
        for pageIndex in 1...5 {
            let index = search.requests.count
            store.loadMoreSearchResults(kind)
            #expect(search.requests.count == index + 1)
            #expect(offset(try #require(search.requests.last)) == pageIndex * 20)
            store.loadMoreSearchResults(kind)
            #expect(search.requests.count == index + 1, "in-flight activation must not start another request")
            search.complete(at: index, .loaded(rawSearchPage(
                artists: kind == .artist ? repeated : [],
                albums: kind == .album ? repeated : [],
                tracks: kind == .track ? repeated : []
            )))
            await settleSearchTask()
            #expect(search.requests.count == index + 1, "completion must not automatically page again")
            #expect(store.snapshot.searchHasMoreKinds.contains(kind))
        }
        let failedIndex = search.requests.count
        store.loadMoreSearchResults(kind)
        #expect(offset(try #require(search.requests.last)) == 120)
        search.complete(at: failedIndex, .failed(DulcetSearchFailure(kind: .timeout)))
        store.loadMoreSearchResults(kind)
        #expect(offset(try #require(search.requests.last)) == 120)
        search.complete(at: failedIndex + 1, .loaded(rawSearchPage(artists: [], albums: [], tracks: [])))
        #expect(!store.snapshot.searchHasMoreKinds.contains(kind))
    }
    // New query resets all cursors. A stale completion cannot advance the new generation.
    let initialIndex = search.requests.count
    store.searchQuery = "new query"
    await settleSearchTask(until: { search.requests.count == initialIndex + 1 })
    let request = try #require(search.requests.last)
    let resetOffsets: [Int] = [request.artistOffset, request.albumOffset, request.trackOffset]
    #expect(resetOffsets == [0, 0, 0])
    search.complete(at: 0, .loaded(rawSearchPage(artists: repeated, albums: repeated, tracks: repeated)))
    search.complete(at: initialIndex, .loaded(rawSearchPage(artists: ["Z"], albums: repeated, tracks: repeated)))
    for kind in [DulcetSearchResultKind.album, .track] {
        let index = search.requests.count
        store.loadMoreSearchResults(kind)
        let next = try #require(search.requests.last)
        #expect((kind == .album ? next.albumOffset : next.trackOffset) == 20)
        search.complete(at: index, .loaded(rawSearchPage(artists: [], albums: [], tracks: [])))
    }
}

@MainActor
private func rawSearchPage(artists: [String], albums: [String], tracks: [String]) -> DulcetSearchPage {
    func unique(_ rows: [String], _ kind: DulcetSearchResultKind) -> [DulcetSearchResult] {
        var seen: Set<String> = []
        return rows.filter { seen.insert($0).inserted }.map {
            searchResult(id: "\(kind)-\($0)", title: $0, kind: kind)
        }
    }
    let artistResults = unique(artists, .artist)
    let albumResults = unique(albums, .album)
    let trackResults = unique(tracks, .track)
    return DulcetSearchPage(
        results: artistResults + albumResults + trackResults,
        artistResultCount: artistResults.count,
        albumResultCount: albumResults.count,
        trackResultCount: trackResults.count,
        artistConsumedRowCount: artists.count,
        albumConsumedRowCount: albums.count,
        trackConsumedRowCount: tracks.count,
        artistHasMore: artists.count == 20,
        albumHasMore: albums.count == 20,
        trackHasMore: tracks.count == 20
    )
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

    // An index with no request behind it is recorded as a failure rather than subscripted: a trap
    // here ends the whole test process, so every other test in the run reports nothing, and the
    // one line that names the cause is an `Index out of range` with no test attached.
    func complete(
        at index: Int,
        _ outcome: DulcetSearchPageOutcome,
        sourceLocation: SourceLocation = #_sourceLocation
    ) {
        guard completions.indices.contains(index) else {
            Issue.record(
                "no search request at index \(index); \(completions.count) were issued",
                sourceLocation: sourceLocation
            )
            return
        }
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
    private(set) var restoredCoverages: [DulcetLibraryCatalogCoverage] = []
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

    func restorePersistedQueue(
        with tracks: [DulcetTrack],
        catalogCoverage: DulcetLibraryCatalogCoverage
    ) {
        restoredCatalogs.append(tracks)
        restoredCoverages.append(catalogCoverage)
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
private func settleSearchTask(
    until condition: () -> Bool,
    sourceLocation: SourceLocation = #_sourceLocation
) async {
    let deadline = ContinuousClock.now + .seconds(5)
    while ContinuousClock.now < deadline {
        if condition() { return }
        try? await Task.sleep(for: .milliseconds(5))
    }
    // Returning silently let the caller go on to act on a request that was never issued.
    if !condition() {
        Issue.record("search condition not met within 5 s", sourceLocation: sourceLocation)
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
        artistConsumedRowCount: results.count { $0.kind == .artist },
        albumConsumedRowCount: results.count { $0.kind == .album },
        trackConsumedRowCount: results.count { $0.kind == .track },
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
    libraryBrowser.complete(.preview(musicFolders: [], artists: [], albums: albums))

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
    libraryBrowser.complete(.preview(musicFolders: [], artists: [], albums: [album]))

    // The first paint knows no tracks, so the catalog handed to the controller is empty — and it
    // is declared PARTIAL, because "not in the catalog" here means "not read yet", not "gone".
    #expect(playback.restoredCatalogs.last?.isEmpty == true)
    #expect(playback.restoredCoverages.last == .partial)

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
    // The saved queue can now be restored, because these tracks are known — and with every album
    // read, the catalog is authoritative again.
    #expect(playback.restoredCatalogs.last?.map(\.id) == tracks.map(\.id))
    #expect(playback.restoredCoverages.last == .wholeLibrary)
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
    libraryBrowser.complete(.preview(musicFolders: [], artists: [], albums: [album]))

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
    libraryBrowser.complete(.preview(musicFolders: [], artists: [], albums: [album]))
    store.selectAlbum(album.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)

    let complete = fixtureLibraryAlbum()
    libraryBrowser.completeAgain(.loaded(musicFolders: [], artists: [], albums: [complete]))
    #expect(libraryBrowser.albumTrackRequests == ["album:opaque"])

    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedAlbum?.id == album.id)
    #expect(store.snapshot.selectedAlbum?.areTracksLoaded == true)
    #expect(store.snapshot.selectedAlbum?.tracks.map(\.id) == complete.tracks.map(\.id))
}

// MARK: - A preview is not the end of a library open

@MainActor
private final class CountingLibraryRefreshScheduler: DulcetLibraryRefreshScheduling {
    private(set) var scheduledCount = 0
    private(set) var scheduledDelays: [Duration] = []
    private var actions: [@MainActor () -> Void] = []

    func schedule(
        after delay: Duration,
        action: @escaping @MainActor () -> Void
    ) -> any DulcetLibraryRefreshOperation {
        scheduledCount += 1
        scheduledDelays.append(delay)
        actions.append(action)
        return CountingLibraryRefreshOperation()
    }

    func fireMostRecent() {
        guard let action = actions.popLast() else { return }
        action()
    }
}

@MainActor
private final class CountingLibraryRefreshOperation: DulcetLibraryRefreshOperation {
    func cancel() {}
}

@MainActor
private func connectedLibraryStore(
    libraryBrowser: ControlledLibraryBrowser,
    refreshScheduler: CountingLibraryRefreshScheduler
) -> DulcetPresentationStore {
    let connector = ControlledAccountConnector()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        libraryRefreshCadence: .seconds(60),
        libraryRefreshScheduler: refreshScheduler,
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

/// The refresh cadence means "re-read the library this long after it was last read in full". A
/// preview has not read it in full — the authoritative read is still running — so arming the
/// cadence there measures from first paint instead, and a read slower than the cadence gets a
/// refresh fired on top of it, which opens the library again while it is still opening.
@Test @MainActor
func aPreviewDoesNotStartTheLibraryRefreshCadence() {
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let store = connectedLibraryStore(
        libraryBrowser: libraryBrowser,
        refreshScheduler: refreshScheduler
    )
    let album = fixtureUnreadAlbum()

    libraryBrowser.complete(.preview(musicFolders: [], artists: [], albums: [album]))
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(refreshScheduler.scheduledCount == 0)

    libraryBrowser.completeAgain(.loaded(
        musicFolders: [],
        artists: [],
        albums: [fixtureLibraryAlbum()]
    ))
    #expect(refreshScheduler.scheduledCount == 1)
    #expect(refreshScheduler.scheduledDelays == [.seconds(60)])

    // One open arms the cadence exactly once, so a refresh cannot be double-booked.
    #expect(libraryBrowser.requests.count == 1)
}

/// The preview arriving must not release the operation: the authoritative read is still in
/// flight, and the operation is the only handle that can cancel it. Releasing it leaves a sync
/// that nothing can stop, still holding the server, whose eventual result the presentation then
/// discards because a newer open has superseded it.
@Test @MainActor
func aLibraryOpenIsStillCancellableAfterItsPreviewArrives() {
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let store = connectedLibraryStore(
        libraryBrowser: libraryBrowser,
        refreshScheduler: refreshScheduler
    )

    libraryBrowser.complete(.preview(
        musicFolders: [],
        artists: [],
        albums: [fixtureUnreadAlbum()]
    ))
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(libraryBrowser.operations.last?.cancelCount == 0)

    store.selectDestination(.search)
    #expect(libraryBrowser.operations.last?.cancelCount == 1)
}

/// The final publication does release it, or every completed open would leak its handle.
@Test @MainActor
func aFinishedLibraryOpenReleasesItsOperation() {
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let store = connectedLibraryStore(
        libraryBrowser: libraryBrowser,
        refreshScheduler: refreshScheduler
    )

    libraryBrowser.complete(.preview(
        musicFolders: [],
        artists: [],
        albums: [fixtureUnreadAlbum()]
    ))
    libraryBrowser.completeAgain(.loaded(
        musicFolders: [],
        artists: [],
        albums: [fixtureLibraryAlbum()]
    ))

    store.selectDestination(.search)
    #expect(libraryBrowser.operations.last?.cancelCount == 0)
}

// MARK: - Re-entering the library

/// The control for the operator's "when i tap out and tap in it has to refresh". Asserting that
/// the library is shown passes before and after the fix, so this asserts the REQUEST COUNT across
/// navigate-away-and-back cycles: it must not grow.
@Test @MainActor
func reEnteringTheLibraryRedrawsItWithoutAskingTheServerAgain() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let store = connectedLibraryStore(
        libraryBrowser: libraryBrowser,
        refreshScheduler: refreshScheduler
    )
    let album = fixtureLibraryAlbum()
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(libraryBrowser.requests.count == 1)

    for destination in [DulcetSidebarDestination.search, .nowPlaying, .settings] {
        store.selectDestination(destination)
        store.selectDestination(.library)
        #expect(store.snapshot.state == .libraryBrowse, "returning from \(destination)")
        #expect(store.snapshot.albums == [album], "returning from \(destination)")
        #expect(libraryBrowser.requests.count == 1, "returning from \(destination)")
    }

    // The cadence keeps running across navigation, because it measures time since the last full
    // read — not time since the person last looked at the screen.
    #expect(refreshScheduler.scheduledCount == 1)
}

/// The instrument is not blind: the counter does move when a read is genuinely required.
@Test @MainActor
func reconnectingReadsTheLibraryAgainEvenWhenOneIsHeld() throws {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        libraryRefreshCadence: .seconds(60),
        libraryRefreshScheduler: refreshScheduler,
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
    libraryBrowser.complete(.loaded(
        musicFolders: [],
        artists: [],
        albums: [fixtureLibraryAlbum()]
    ))
    #expect(libraryBrowser.requests.count == 1)

    store.selectDestination(.library)
    #expect(libraryBrowser.requests.count == 1)

    // Connecting again describes a different session, so the held library stops being an answer
    // and the connection itself reads — the person pressed Reconnect on the library screen.
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    #expect(libraryBrowser.requests.count == 2)
    libraryBrowser.complete(.loaded(
        musicFolders: [],
        artists: [],
        albums: [fixtureLibraryAlbum()]
    ))

    // And once that read has finished, re-entry is free again.
    store.selectDestination(.search)
    store.selectDestination(.library)
    #expect(libraryBrowser.requests.count == 2)
}

/// 🚨 The window this protects is everything between the preview painting and the sync
/// committing. Its DURATION is a property of the sync's transport, not of this behaviour, and it
/// has already changed once by about two orders of magnitude — so this control deliberately does
/// not depend on it, and neither does the guard it covers.
///
/// `selectDestination(.library)` is this surface's ONLY way back out of an album detail; there is
/// no separate back action, which `republishHeldLibrary` says in so many words. During that window
/// `libraryReadCompleted` is still false, so backing out fell straight through the held-data guard,
/// cancelled the whole in-flight pair, and started a second one — replacing the grid the person was
/// looking at with a loading spinner and re-reading the entire library.
///
/// Asserting that the grid is shown passes identically before and after the fix, so this asserts
/// the REQUEST COUNT, and also that the live operation was never cancelled: the outcome and the
/// process, because the outcome alone is satisfied by a cancel-and-restart that lands on the same
/// albums.
@Test @MainActor
func backingOutOfAnAlbumWhileTheFirstReadRunsDoesNotStartASecondWalk() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let store = connectedLibraryStore(
        libraryBrowser: libraryBrowser,
        refreshScheduler: refreshScheduler
    )
    let album = fixtureUnreadAlbum()

    // The preview paints. The sync behind it has not finished, so the operation is still live and
    // `libraryReadCompleted` is still false — this is the whole window under test.
    libraryBrowser.complete(.preview(musicFolders: [], artists: [], albums: [album]))
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(libraryBrowser.requests.count == 1)

    store.selectAlbum(album.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)

    store.selectDestination(.library)

    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.snapshot.albums == [album])
    #expect(libraryBrowser.requests.count == 1)
    #expect(libraryBrowser.operations.last?.cancelCount == 0)

    // The read that was left alone still finishes, and still replaces the preview with the
    // committed library. Guarding initiation must not cost the answer.
    libraryBrowser.completeAgain(.loaded(
        musicFolders: [],
        artists: [],
        albums: [fixtureLibraryAlbum()]
    ))
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.snapshot.albums == [fixtureLibraryAlbum()])
    #expect(libraryBrowser.requests.count == 1)
    #expect(refreshScheduler.scheduledCount == 1)
}

/// Nothing has been drawn yet, so there is no grid to redraw — but there is also nothing to
/// restart. Re-entering during the blank part of the window must keep showing the loading surface
/// rather than publishing an empty library over a read that is still running.
@Test @MainActor
func reEnteringBeforeThePreviewArrivesKeepsLoadingRatherThanRereading() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let store = connectedLibraryStore(
        libraryBrowser: libraryBrowser,
        refreshScheduler: refreshScheduler
    )
    #expect(store.snapshot.state == .libraryLoading)
    #expect(libraryBrowser.requests.count == 1)

    store.selectDestination(.library)

    #expect(store.snapshot.state == .libraryLoading)
    #expect(libraryBrowser.requests.count == 1)
    #expect(libraryBrowser.operations.last?.cancelCount == 0)
}

/// A read that failed is not a library, so the error surface's Try Again — which re-enters the
/// destination — must reach the server.
@Test @MainActor
func aFailedLibraryReadIsRetriedWhenTheLibraryIsEnteredAgain() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let store = connectedLibraryStore(
        libraryBrowser: libraryBrowser,
        refreshScheduler: refreshScheduler
    )
    libraryBrowser.complete(.failed(DulcetLibraryFailure(kind: .timeout)))
    #expect(store.snapshot.state == .libraryError)
    #expect(libraryBrowser.requests.count == 1)

    store.selectDestination(.library)
    #expect(libraryBrowser.requests.count == 2)
    #expect(store.snapshot.state == .libraryLoading)
}

/// A read interrupted part way through is not a library either: leaving cancels it, and coming
/// back must read rather than redraw a half-answered one.
@Test @MainActor
func leavingTheLibraryCancelsAReadThatHasNotFinished() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let store = connectedLibraryStore(
        libraryBrowser: libraryBrowser,
        refreshScheduler: refreshScheduler
    )
    #expect(store.snapshot.state == .libraryLoading)

    store.selectDestination(.search)
    #expect(libraryBrowser.operations.last?.cancelCount == 1)

    store.selectDestination(.library)
    #expect(libraryBrowser.requests.count == 2)
    #expect(store.snapshot.state == .libraryLoading)
}

/// Connecting from Settings does not itself open the library — only a Reconnect pressed ON the
/// library screen does that. So the held library has to be invalidated by the connection, or
/// walking to Library afterwards would redraw the previous session's albums without asking.
/// Found by mutation: removing that invalidation left every other test green.
@Test @MainActor
func connectingFromSettingsInvalidatesTheHeldLibrary() throws {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        libraryRefreshCadence: .seconds(60),
        libraryRefreshScheduler: refreshScheduler,
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
    libraryBrowser.complete(.loaded(
        musicFolders: [],
        artists: [],
        albums: [fixtureLibraryAlbum()]
    ))
    #expect(libraryBrowser.requests.count == 1)

    // Connect again from Settings, which does not open the library.
    store.selectDestination(.settings)
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Other",
        normalizedServerURL: "https://other.example.invalid"
    )))
    #expect(libraryBrowser.requests.count == 1)

    store.selectDestination(.library)
    #expect(libraryBrowser.requests.count == 2)
    #expect(store.snapshot.state == .libraryLoading)
}

/// A search can return an album the last library read did not include — the server has it and we
/// simply have not looked since. Held data answers navigation only when it can actually answer it,
/// so that case reads rather than dropping the person on a grid they did not ask for.
@Test @MainActor
func activatingASearchResultAbsentFromTheHeldLibraryReadsAgain() async throws {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let search = ControlledServerSearch()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
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
    let album = fixtureLibraryAlbum()
    store.selectDestination(.library)
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [], albums: [album]))
    #expect(libraryBrowser.requests.count == 1)

    store.selectDestination(.search)
    store.searchQuery = "opaque"
    await settleSearchTask(until: { search.requests.count == 1 })
    let held = searchResult(id: album.id.rawID, title: album.title, kind: .album)
    let absent = searchResult(id: "album:not-in-the-held-library", title: "Newer", kind: .album)
    search.complete(at: 0, .loaded(searchPage(results: [held, absent])))

    // An album the held library does contain is navigation, and costs nothing.
    store.activateSearchResult(held.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedAlbum?.id == album.id)
    #expect(libraryBrowser.requests.count == 1)

    // One it does not contain must reach the server rather than silently show the grid.
    store.selectDestination(.search)
    store.activateSearchResult(absent.id)
    #expect(libraryBrowser.requests.count == 2)
    #expect(store.snapshot.state == .libraryLoading)
}

/// `.preview` makes "a preview is not an ending" unrepresentable. This is the other half: an
/// ending is final. A preview arriving after the authoritative result would blank the library —
/// tracks back to zero, restoration coverage back to partial — and ordering across two HTTP
/// clients is not something the consumer can assume.
@Test @MainActor
func aPreviewArrivingAfterTheAuthoritativeResultIsIgnored() throws {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let refreshScheduler = CountingLibraryRefreshScheduler()
    let source = DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        playbackController: playback,
        libraryRefreshCadence: .seconds(60),
        libraryRefreshScheduler: refreshScheduler,
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

    let complete = fixtureLibraryAlbum()
    libraryBrowser.complete(.preview(musicFolders: [], artists: [], albums: [fixtureUnreadAlbum()]))
    libraryBrowser.completeAgain(.loaded(musicFolders: [], artists: [], albums: [complete]))
    #expect(store.snapshot.albums.first?.areTracksLoaded == true)
    #expect(store.snapshot.albums.first?.tracks.count == 1)
    #expect(playback.restoredCoverages.last == .wholeLibrary)

    // A preview for the same open, delivered late.
    libraryBrowser.completeAgain(.preview(
        musicFolders: [],
        artists: [],
        albums: [fixtureUnreadAlbum()]
    ))

    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.snapshot.albums.first?.areTracksLoaded == true)
    #expect(store.snapshot.albums.first?.tracks.count == 1)
    #expect(store.snapshot.albums.map(\.id) == [complete.id])
    #expect(playback.restoredCoverages.last == .wholeLibrary)
}

// MARK: - Library-wide playback before track lists exist

/// The predicate behind the Play All / Shuffle enablement. The review found that removing all
/// four `.disabled(...)` modifiers still passed the whole suite, because nothing asserted either
/// the decision or what it protects. This asserts the decision.
@Test @MainActor
func wholeLibraryPlaybackIsOnlyPossibleOnceSomeTrackListExists() {
    func snapshot(albums: [DulcetAlbum], looseTracks: [DulcetTrack] = []) -> DulcetSnapshot {
        DulcetSnapshot(
            state: .libraryBrowse,
            selectedDestination: .library,
            accountConnected: true,
            connectivity: .online(serverName: "Music"),
            albums: albums,
            looseTracks: looseTracks,
            recentlyAddedTracks: [],
            captureDate: Date(timeIntervalSince1970: 0)
        )
    }
    let unread = fixtureUnreadAlbum()
    let complete = fixtureLibraryAlbum()

    #expect(!snapshot(albums: []).canPlayWholeLibrary)
    #expect(!snapshot(albums: [unread]).canPlayWholeLibrary)
    #expect(!snapshot(albums: [unread, unread]).canPlayWholeLibrary)
    #expect(snapshot(albums: [complete]).canPlayWholeLibrary)
    #expect(snapshot(albums: [unread, complete]).canPlayWholeLibrary)
    #expect(snapshot(albums: [unread], looseTracks: complete.tracks).canPlayWholeLibrary)
}

/// And this asserts what it protects: with no track list anywhere, the action reaches the
/// playback boundary as nothing at all rather than as an empty queue.
@Test @MainActor
func playingTheWholeLibraryBeforeTrackListsExistQueuesNothing() throws {
    let libraryBrowser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let store = connectedLibraryStore(
        connector: ControlledAccountConnector(),
        libraryBrowser: libraryBrowser,
        playback: playback
    )
    let album = fixtureUnreadAlbum()
    libraryBrowser.complete(.preview(musicFolders: [], artists: [], albums: [album]))
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(!store.snapshot.canPlayWholeLibrary)

    store.playLibrary(shuffle: false)
    store.playLibrary(shuffle: true)
    store.playAlbum(album.id, shuffle: false)
    #expect(playback.queueIntents.isEmpty)

    // The instrument is not blind: once the tracks arrive the same actions do build a queue.
    libraryBrowser.completeAgain(.loaded(
        musicFolders: [],
        artists: [],
        albums: [fixtureLibraryAlbum()]
    ))
    #expect(store.snapshot.canPlayWholeLibrary)
    store.playLibrary(shuffle: false)
    #expect(playback.queueIntents.count == 1)
}

// MARK: - Shell navigation: playback leaves the person where they are

@MainActor
private func connectedShellStore(
    navigation: DulcetPlaybackStartNavigation
) -> (DulcetPresentationStore, ControlledPlaybackController, DulcetAlbum, DulcetArtist) {
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser()
    let playback = ControlledPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        libraryBrowser: libraryBrowser,
        playbackController: playback,
        providerInstanceIDFactory: { "provider-instance-fixture" },
        playbackStartNavigation: navigation
    ))
    store.accountServerURL = "https://music.example.invalid"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.submitAccountConnection()
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "https://music.example.invalid"
    )))
    let album = fixtureLibraryAlbum()
    let artist = DulcetArtist(
        id: try! #require(album.credits.first?.id),
        name: "Opaque Artist",
        mediaSourceID: nil
    )
    store.selectDestination(.library)
    libraryBrowser.complete(.loaded(musicFolders: [], artists: [artist], albums: [album]))
    return (store, playback, album, artist)
}

@MainActor
private func readyPresentation(for album: DulcetAlbum) -> DulcetPlaybackPresentation {
    DulcetPlaybackPresentation(status: .ready, nowPlaying: DulcetNowPlaying(
        sessionID: DulcetPlaybackSessionID("session-shell"),
        current: album.tracks[0],
        queue: album.tracks,
        elapsed: .seconds(3),
        isPlaying: true,
        outputName: "Fixture output",
        volume: 1,
        audioFormat: DulcetAudioFormat(codec: "FLAC", sampleRateKilohertz: 44.1)
    ))
}

@Test @MainActor
func playingATrackLeavesTheAlbumPageShowingAndCarriesNowPlayingEverywhere() {
    let (store, playback, album, _) = connectedShellStore(navigation: .stayOnCurrentSurface)
    store.selectAlbum(album.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)

    store.activateTrack(albumID: album.id, trackID: album.tracks[0].id)
    #expect(playback.queueIntents.count == 1)
    // Still on the album: the bar says what is playing, the page does not go away.
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedDestination == .library)
    #expect(store.snapshot.selectedAlbum?.id == album.id)
    #expect(store.snapshot.playbackStatus == .preparing)

    playback.publish(readyPresentation(for: album))
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.nowPlaying?.current.id == album.tracks[0].id)

    // Every later publication carries it -- navigation used to drop it until the next tick.
    store.selectDestination(.search)
    #expect(store.snapshot.selectedDestination == .search)
    #expect(store.snapshot.nowPlaying?.current.id == album.tracks[0].id)
    store.selectDestination(.library)
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.snapshot.nowPlaying?.current.id == album.tracks[0].id)
    store.selectDestination(.settings)
    #expect(store.snapshot.nowPlaying?.current.id == album.tracks[0].id)

    playback.publish(.unavailable)
    #expect(store.snapshot.nowPlaying == nil)
    #expect(store.snapshot.playbackStatus == .unavailable)
}

@Test @MainActor
func showNowPlayingNavigationStillMovesToNowPlayingForTV() {
    let (store, playback, album, _) = connectedShellStore(navigation: .showNowPlaying)
    store.selectAlbum(album.id)
    store.activateTrack(albumID: album.id, trackID: album.tracks[0].id)
    #expect(store.snapshot.selectedDestination == .nowPlaying)
    playback.publish(readyPresentation(for: album))
    #expect(store.snapshot.state == .nowPlaying)
}

@Test
func playbackStartNavigationDefaultsStayPutWhereANowPlayingBarExists() {
#if os(tvOS)
    #expect(DulcetPlaybackStartNavigation.platformDefault == .showNowPlaying)
#else
    #expect(DulcetPlaybackStartNavigation.platformDefault == .stayOnCurrentSurface)
#endif
}

@Test @MainActor
func aPlaybackTickDoesNotEmptyTheArtistPage() {
    let (store, playback, album, artist) = connectedShellStore(navigation: .stayOnCurrentSurface)
    store.showArtist(artist.id)
    #expect(store.snapshot.state == .artistDetail)
    #expect(store.snapshot.selectedArtist?.id == artist.id)
    playback.publish(readyPresentation(for: album))
    #expect(store.snapshot.state == .artistDetail)
    #expect(store.snapshot.selectedArtist?.id == artist.id)
}

@Test @MainActor
func artistAndAlbumLinksResolveOnlyToPagesTheLibraryHolds() {
    let (store, _, album, artist) = connectedShellStore(navigation: .stayOnCurrentSurface)
    let track = album.tracks[0]

    // By identity, then by an unambiguous name; never to an artist the library does not list.
    #expect(store.libraryArtistID(for: DulcetCredit(role: .artist, name: "x", id: artist.id)) == artist.id)
    #expect(store.libraryArtistID(for: DulcetCredit(role: .artist, name: artist.name, id: nil)) == artist.id)
    #expect(store.libraryArtistID(for: DulcetCredit(role: .artist, name: "Nobody", id: nil)) == nil)
    #expect(store.libraryArtistID(for: DulcetCredit(
        role: .artist,
        name: "Featured",
        id: DulcetProviderItemID(providerInstanceID: "provider-instance-fixture", rawID: "artist:unlisted")
    )) == nil)

    #expect(store.libraryAlbumID(for: track) == album.id)
    let unknown = DulcetTrack(
        id: DulcetProviderItemID(providerInstanceID: "p", rawID: "t:other"),
        title: "Other",
        credits: [],
        albumTitle: "Some Other Album",
        duration: .seconds(1),
        mediaSourceID: nil,
        artwork: DulcetArtwork(seed: "o", palette: .tealSun)
    )
    #expect(store.libraryAlbumID(for: unknown) == nil)

    store.selectDestination(.search)
    store.showArtist(artist.id)
    #expect(store.snapshot.selectedDestination == .library)
    #expect(store.snapshot.state == .artistDetail)
    store.showAlbum(album.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedAlbum?.id == album.id)
}

@Test @MainActor
func queueEditingIsOfferedOnlyWhenTheControllerCanEdit() {
    let (store, _, _, _) = connectedShellStore(navigation: .stayOnCurrentSurface)
    #expect(!store.queueEditingEnabled)

    let editing = EditingPlaybackController()
    let editingStore = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: editing
    ))
    #expect(editingStore.queueEditingEnabled)
    let album = fixtureLibraryAlbum()
    let addition = DulcetQueueAddition.album(album)
    editingStore.editQueue(.playNext(addition))
    #expect(editing.edits == [.playNext(addition)])
}

@Test @MainActor
func droppingDraggedItemsOntoTheQueueAddsEachResolvableOneToTheEnd() throws {
    DulcetQueueDragRegistry.removeAll()
    let editing = EditingPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: editing
    ))
    let album = fixtureLibraryAlbum()
    let albumAddition = DulcetQueueAddition.album(album)
    let trackAddition = DulcetQueueAddition.searchResult(try #require(album.tracks.first))

    let albumDrag = DulcetQueueDragRegistry.register(albumAddition)
    let trackDrag = DulcetQueueDragRegistry.register(trackAddition)
    let nothing = DulcetQueueDragRegistry.register(nil)
    let foreign = DulcetQueueDragItem(ticket: UUID())

    // Order is the drop's, and tickets the process never issued, or issued for nothing, add
    // nothing rather than failing the whole drop.
    #expect(DulcetQueueDragRegistry.dropOntoQueue([trackDrag, foreign, nothing, albumDrag], store: store))
    #expect(editing.edits == [.playLater(trackAddition), .playLater(albumAddition)])

    // A drop made only of unresolvable tickets reports that it did nothing.
    #expect(!DulcetQueueDragRegistry.dropOntoQueue([foreign, nothing], store: store))
    #expect(editing.edits.count == 2)
}

@Test @MainActor
func theDragRegistryForgetsAbandonedDragsBeyondItsCapacity() {
    DulcetQueueDragRegistry.removeAll()
    let addition = DulcetQueueAddition.album(fixtureLibraryAlbum())
    let first = DulcetQueueDragRegistry.register(addition)
    let rest = (0..<DulcetQueueDragRegistry.capacity).map { _ in DulcetQueueDragRegistry.register(addition) }
    #expect(DulcetQueueDragRegistry.addition(for: first) == nil)
    #expect(DulcetQueueDragRegistry.addition(for: rest[0]) == addition)
    #expect(DulcetQueueDragRegistry.addition(for: rest[rest.count - 1]) == addition)
}

@Test @MainActor
func aDropCannotEditAQueueTheControllerCannotEdit() {
    DulcetQueueDragRegistry.removeAll()
    let (store, _, _, _) = connectedShellStore(navigation: .stayOnCurrentSurface)
    let drag = DulcetQueueDragRegistry.register(.album(fixtureLibraryAlbum()))
    #expect(!DulcetQueueDragRegistry.dropOntoQueue([drag], store: store))
}

@Test @MainActor
func theSearchCommandAsksTheFieldForFocusUntilTheFieldTakesIt() {
    let (store, _, _, _) = connectedShellStore(navigation: .stayOnCurrentSurface)
    #expect(!store.searchFocusRequested)
    store.focusSearch()
    #expect(store.selectedDestination == .search)
    #expect(store.searchFocusRequested)
    // Already on Search: the command still asks, because the field may have lost focus.
    store.searchFocusRequestHandled()
    store.focusSearch()
    #expect(store.searchFocusRequested)
    store.searchFocusRequestHandled()
    #expect(!store.searchFocusRequested)
}

@MainActor
private final class EditingPlaybackController: DulcetPlaybackControlling, DulcetQueueEditing {
    private(set) var edits: [DulcetQueueEditIntent] = []
    var refusesEdits = false
    let currentPresentation: DulcetPlaybackPresentation = .unavailable
    func setPresentationHandler(_ handler: @escaping @MainActor (DulcetPlaybackPresentation) -> Void) {}
    func configure(account: DulcetPlaybackAccount) {}
    func restorePersistedQueue(with tracks: [DulcetTrack], catalogCoverage: DulcetLibraryCatalogCoverage) {}
    func replaceQueueAndPlay(_ intent: DulcetPlaybackQueueIntent) {}
    func send(_ intent: DulcetPlaybackControlIntent) {}
    func disconnect() {}
    func edit(_ intent: DulcetQueueEditIntent) -> Bool {
        edits.append(intent)
        return !refusesEdits
    }
}

// MARK: Local-network access

@MainActor
private final class ControlledLocalNetworkAccess: DulcetLocalNetworkAccessProbing {
    final class Watch: DulcetLocalNetworkAccessWatch {
        var cancelled = false
        func cancel() { cancelled = true }
    }

    private(set) var watchedURLs: [String] = []
    private(set) var watches: [Watch] = []
    private var onChange: (@MainActor (DulcetLocalNetworkAccess) -> Void)?

    func watch(
        serverURL: String,
        onChange: @escaping @MainActor (DulcetLocalNetworkAccess) -> Void
    ) -> any DulcetLocalNetworkAccessWatch {
        watchedURLs.append(serverURL)
        self.onChange = onChange
        let watch = Watch()
        watches.append(watch)
        return watch
    }

    func answer(_ access: DulcetLocalNetworkAccess) {
        onChange?(access)
    }
}

@MainActor
private func localNetworkStore() -> (DulcetPresentationStore, ControlledAccountConnector, ControlledLocalNetworkAccess) {
    let connector = ControlledAccountConnector()
    let probe = ControlledLocalNetworkAccess()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        localNetworkAccess: probe
    ))
    store.accountServerURL = "http://10.0.0.20:4533"
    store.accountUsername = "listener"
    store.accountPassword = "fixture-password"
    store.accountAllowLocalHTTP = true
    return (store, connector, probe)
}

private func accountFailure(_ kind: DulcetAccountFailureKind) -> DulcetAccountFailurePresentation {
    DulcetAccountErrorPresenter.presentation(for: DulcetAccountErrorContext(kind: kind, serverName: "10.0.0.20"))
}

@MainActor
private func shownFailureKind(_ store: DulcetPresentationStore) -> DulcetAccountFailureKind? {
    if case let .failed(failure) = store.snapshot.accountConnection { return failure.kind }
    return nil
}

@Test @MainActor
func aLocalServerBlockedByLocalNetworkPrivacySaysSoAndConnectsOnceAccessIsGranted() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))

    // The system is asked before the server is blamed, and the spinner stays up meanwhile.
    #expect(probe.watchedURLs == ["http://10.0.0.20:4533"])
    #expect(store.snapshot.state == .accountConnecting)

    probe.answer(.denied)
    #expect(shownFailureKind(store) == .localNetworkAccessDenied)
    #expect(connector.requests.count == 1)

    // Granting access -- answering the prompt, or turning the switch on -- retries by itself.
    probe.answer(.notDenied)
    #expect(connector.requests.count == 2)
    #expect(store.snapshot.state == .accountConnecting)

    // Once per request: a server still unreachable after the grant is reported, not retried
    // again every time the permission is toggled.
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    probe.answer(.notDenied)
    #expect(connector.requests.count == 2)
}

@Test @MainActor
func anUnreachableServerThatPrivacyDidNotBlockKeepsItsOwnFailure() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportTimeout)))
    probe.answer(.notDenied)
    #expect(shownFailureKind(store) == .transportTimeout)
    #expect(connector.requests.count == 1)
}

@Test @MainActor
func onlyTransportFailuresConsultLocalNetworkPrivacy() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.invalidCredentials)))
    #expect(probe.watchedURLs.isEmpty)
    #expect(shownFailureKind(store) == .invalidCredentials)
}

@Test @MainActor
func aNewSubmissionStopsWatchingForTheOldOnesAccess() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    store.submitAccountConnection()
    #expect(probe.watches.first?.cancelled == true)
    // A late grant for the superseded request starts nothing.
    probe.answer(.notDenied)
    #expect(connector.requests.count == 2)
}

@Test
func localNetworkDenialHasCatalogedCopy() {
    let presentation = DulcetAccountErrorPresenter.presentation(for: DulcetAccountErrorContext(
        kind: .localNetworkAccessDenied,
        serverName: "10.0.0.20"
    ))
    #expect(presentation.title == "Allow Dulcet to find devices on your local network")
    #expect(presentation.kind.family == .transport)
}

// MARK: - Per-destination navigation (a tab bar and a sidebar keep each place as it was left)

@Test @MainActor
func libraryComesBackAsItWasLeftAndChoosingItAgainReturnsToTheGrid() {
    let (store, _, album, artist) = connectedShellStore(navigation: .stayOnCurrentSurface)
    store.showArtist(artist.id)
    store.selectAlbum(album.id)
    #expect(store.libraryPath == [.artist(artist.id), .album(album.id)])
    #expect(store.snapshot.state == .albumDetailMultiDisc)

    // Away and back: the stack is kept while away, and the album is what comes back.
    store.navigate(to: .search)
    #expect(store.snapshot.selectedDestination == .search)
    #expect(store.libraryPath == [.artist(artist.id), .album(album.id)])
    store.navigate(to: .library)
    #expect(store.snapshot.selectedDestination == .library)
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.snapshot.selectedAlbum?.id == album.id)
    #expect(store.libraryPath == [.artist(artist.id), .album(album.id)])

    // Back pops one page -- to the artist, not the grid.
    store.popLibrary(to: [.artist(artist.id)])
    #expect(store.snapshot.state == .artistDetail)
    #expect(store.snapshot.selectedArtist?.id == artist.id)
    #expect(store.libraryPath == [.artist(artist.id)])

    // Choosing the destination already showing returns it to its root.
    store.navigate(to: .library)
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.libraryPath.isEmpty)
    // And a root stays a root when the person leaves and returns.
    store.navigate(to: .settings)
    store.navigate(to: .library)
    #expect(store.snapshot.state == .libraryBrowse)
}

@Test @MainActor
func aPageOpenedFromAnotherDestinationStartsTheLibraryStackOnTheGrid() {
    let (store, _, album, artist) = connectedShellStore(navigation: .stayOnCurrentSurface)
    store.showArtist(artist.id)
    store.navigate(to: .search)
    // "Go to Album" from the player or a search result: the album on the grid, not on top of
    // an artist page the person left somewhere else.
    store.showAlbum(album.id)
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.libraryPath == [.album(album.id)])
    store.popLibrary(to: [])
    #expect(store.snapshot.state == .libraryBrowse)
    #expect(store.libraryPath.isEmpty)
}

@Test @MainActor
func aBackGestureThatIsNotAPopChangesNothing() {
    let (store, _, album, _) = connectedShellStore(navigation: .stayOnCurrentSurface)
    store.selectAlbum(album.id)
    // The navigation stack reporting the path it already holds, or one it never held, is not a
    // request to go anywhere.
    store.popLibrary(to: [.album(album.id)])
    store.popLibrary(to: [.artist(album.id)])
    #expect(store.snapshot.state == .albumDetailMultiDisc)
    #expect(store.libraryPath == [.album(album.id)])
}

@Test @MainActor
func theFixtureKeepsLibraryPagesPerDestinationToo() throws {
    let store = DulcetPresentationStore(source: DulcetDeterministicDataSource())
    let album = try #require(store.snapshot.albums.first)
    store.selectAlbum(album.id)
    store.navigate(to: .search)
    store.navigate(to: .library)
    #expect(store.snapshot.selectedAlbum?.id == album.id)
    store.navigate(to: .library)
    #expect(store.snapshot.state == .libraryBrowse)
}

// MARK: - Playback start navigation reaches the fixture's shell proofs

@Test @MainActor
func theFixtureFollowsThePlaybackStartNavigationItIsGiven() throws {
    for navigation in [DulcetPlaybackStartNavigation.stayOnCurrentSurface, .showNowPlaying] {
        let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(
            playbackStartNavigation: navigation
        ))
        let album = try #require(store.snapshot.albums.first { !$0.tracks.isEmpty })
        store.selectAlbum(album.id)
        store.activateTrack(albumID: album.id, trackID: album.tracks[0].id)
        #expect(store.snapshot.nowPlaying?.current.id == album.tracks[0].id)
        #expect(store.snapshot.selectedDestination
            == (navigation == .showNowPlaying ? .nowPlaying : .library))
    }
}

// MARK: - A failed track is not a dead end

@Test @MainActor
func aFailedTrackNamesItselfAndSkipAndRetryReachPlayback() throws {
    let source = DulcetDeterministicDataSource(failingTrackTitles: ["Disc 1 Track 1"])
    let store = DulcetPresentationStore(source: source)
    let album = try #require(store.snapshot.albums.first { $0.tracks.first?.title == "Disc 1 Track 1" })
    store.activateTrack(albumID: album.id, trackID: album.tracks[0].id)
    #expect(store.snapshot.playbackFailed)
    #expect(store.snapshot.playbackFailure?.track?.title == "Disc 1 Track 1")
    #expect(store.snapshot.playbackFailure?.canSkip == true)
    #expect(store.snapshot.playbackFailure?.canRetry == true)
    #expect(store.showsNowPlayingBar)

    // Dismissed, the bar goes away -- until playback does anything else.
    store.dismissPlaybackFailure()
    #expect(!store.showsNowPlayingBar)
    store.sendPlaybackControl(.retry)
    #expect(!store.snapshot.playbackFailed)
    #expect(store.snapshot.nowPlaying?.current.title == "Disc 1 Track 1")
    #expect(store.showsNowPlayingBar)
}

@Test @MainActor
func aNewFailureAfterADismissalShowsTheBarAgain() throws {
    let source = DulcetDeterministicDataSource(failingTrackTitles: ["Disc 1 Track 1"])
    let store = DulcetPresentationStore(source: source)
    let album = try #require(store.snapshot.albums.first { $0.tracks.first?.title == "Disc 1 Track 1" })
    store.activateTrack(albumID: album.id, trackID: album.tracks[0].id)
    store.dismissPlaybackFailure()
    #expect(!store.showsNowPlayingBar)
    // The fixture goes from failed straight to failed again, with no playing or preparing
    // snapshot in between: the person starting it is what makes it a new failure to show.
    store.activateTrack(albumID: album.id, trackID: album.tracks[0].id)
    #expect(store.snapshot.playbackFailed)
    #expect(store.showsNowPlayingBar)
}

@Test @MainActor
func skippingAFailedTrackPlaysTheNextEntry() throws {
    let store = DulcetPresentationStore(source: DulcetDeterministicDataSource(
        failingTrackTitles: ["Disc 1 Track 1"]
    ))
    let album = try #require(store.snapshot.albums.first { $0.tracks.first?.title == "Disc 1 Track 1" })
    store.activateTrack(albumID: album.id, trackID: album.tracks[0].id)
    store.sendPlaybackControl(.next)
    #expect(!store.snapshot.playbackFailed)
    #expect(store.snapshot.nowPlaying?.current.id == album.tracks[1].id)
}

@Test(arguments: [DulcetPlaybackSurfaceStatus.ready, .failed]) @MainActor
func everySurfaceReadsOneFailurePredicate(status: DulcetPlaybackSurfaceStatus) {
    let playback = ControlledPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: playback
    ))
    // `.ready` with nothing to present is an item that went missing: the Mac and tvOS surface
    // already called it a failure, and the iPhone and iPad player and bar must agree.
    playback.publish(DulcetPlaybackPresentation(status: status, nowPlaying: nil))
    #expect(store.snapshot.playbackFailed)
    #expect(store.showsNowPlayingBar)
    store.selectDestination(.nowPlaying)
    #expect(store.snapshot.state == .nowPlayingFailed)
}

// MARK: - A refused queue edit says so

@Test @MainActor
func aRefusedQueueEditIsCountedOnTheSnapshot() {
    let editing = EditingPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: editing
    ))
    let entry = DulcetQueueEntryID("entry-gone")
    store.editQueue(.remove(entry))
    #expect(store.snapshot.refusedQueueEdits == 0)
    editing.refusesEdits = true
    store.editQueue(.remove(entry))
    store.editQueue(.clearUpcoming)
    #expect(store.snapshot.refusedQueueEdits == 2)
    #expect(editing.edits.count == 3)
}

// MARK: - A late local-network grant connects where the person is

@Test @MainActor
func accessGrantedAfterThePersonMovedOnConnectsWithoutTakingThemBack() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    #expect(store.snapshot.selectedDestination == .settings)

    store.navigate(to: .library)
    let libraryState = store.snapshot.state
    #expect(store.snapshot.selectedDestination == .library)

    probe.answer(.notDenied)
    #expect(connector.requests.count == 2)
    #expect(store.snapshot.selectedDestination == .library, "no spinner on Settings")
    #expect(store.snapshot.state == libraryState)

    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "http://10.0.0.20:4533"
    )))
    #expect(store.snapshot.selectedDestination == .library)
    #expect(store.snapshot.accountConnected)
    #expect(store.snapshot.state == .emptyLibraryConnected, "the library opens on the connection")
}

@Test @MainActor
func anInPlaceRetryThatFailsIsRecordedWhereSettingsShowsIt() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    store.navigate(to: .search)
    probe.answer(.notDenied)
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    #expect(store.snapshot.selectedDestination == .search)
    #expect(shownFailureKind(store) == .transportUnreachable)
    store.navigate(to: .settings)
    #expect(shownFailureKind(store) == .transportUnreachable)
}

// MARK: - The failure message offers only what is there

@MainActor
private func failure(canSkip: Bool, canRetry: Bool = true, stoppedPartway: Bool = false) -> DulcetFailedPlayback {
    DulcetFailedPlayback(
        track: fixtureLibraryAlbum().tracks[0],
        canSkip: canSkip,
        canRetry: canRetry,
        stoppedPartway: stoppedPartway
    )
}

@Test @MainActor
func theLastEntrysFailureDoesNotOfferASkipThatIsNotThere() {
    // The last entry with repeat off, and a one-track queue under repeat-all, both reach the
    // player as a failure that cannot skip: nothing else follows the failed track.
    let message = DulcetPlaybackFailedView.message(for: failure(canSkip: false))
    #expect(message == "Dulcet couldn\u{2019}t start this track. Try it again.")
    #expect(!message.localizedCaseInsensitiveContains("skip"))
}

@Test @MainActor
func aFailureWithSomewhereToGoOffersBoth() {
    #expect(DulcetPlaybackFailedView.message(for: failure(canSkip: true))
        == "Dulcet couldn\u{2019}t start this track. Try it again, or skip to the next one.")
}

@Test @MainActor
func aTrackThatStoppedPartwayIsNotSaidToHaveFailedToStart() {
    let withSkip = DulcetPlaybackFailedView.message(for: failure(canSkip: true, stoppedPartway: true))
    let withoutSkip = DulcetPlaybackFailedView.message(for: failure(canSkip: false, stoppedPartway: true))
    #expect(withSkip == "This track stopped partway through. Try it again, or skip to the next one.")
    #expect(withoutSkip == "This track stopped partway through. Try it again.")
    #expect(![withSkip, withoutSkip].contains { $0.contains("start") })
}

@Test @MainActor
func aFailureWithNothingOnOfferSendsThePersonBackToTheLibrary() {
    #expect(DulcetPlaybackFailedView.message(for: .undescribed)
        == "Return to your library and choose another track.")
    #expect(DulcetPlaybackFailedView.message(for: failure(canSkip: true, canRetry: false))
        == "Dulcet couldn\u{2019}t start this track. Skip to the next one.")
}

// MARK: - Only the failure dismissed stays away

@Test @MainActor
func aDismissedFailureComesBackWhenPlaybackStartsWithoutTheStore() {
    let playback = ControlledPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: playback
    ))
    let failed = DulcetPlaybackPresentation(status: .failed, nowPlaying: nil, failure: failure(canSkip: true))
    playback.publish(failed)
    store.dismissPlaybackFailure()
    #expect(!store.showsNowPlayingBar)
    // Started from the lock screen or a headset: nothing goes through the store, and the same
    // track fails again. Playback did something else in between, so this is a new failure.
    playback.publish(DulcetPlaybackPresentation(status: .preparing, nowPlaying: nil))
    playback.publish(failed)
    #expect(store.showsNowPlayingBar)
}

@Test @MainActor
func aDifferentFailureAfterADismissalShowsTheBarWithNothingInBetween() {
    let playback = ControlledPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: playback
    ))
    playback.publish(DulcetPlaybackPresentation(status: .failed, nowPlaying: nil, failure: failure(canSkip: true)))
    store.dismissPlaybackFailure()
    #expect(!store.showsNowPlayingBar)
    // The same failure republished stays dismissed...
    playback.publish(DulcetPlaybackPresentation(status: .failed, nowPlaying: nil, failure: failure(canSkip: true)))
    #expect(!store.showsNowPlayingBar)
    // ...but another track failing is not the failure the person put away.
    let otherTrack = DulcetTrack(
        id: DulcetProviderItemID(providerInstanceID: "provider-instance-fixture", rawID: "track:other"),
        title: "Other Track",
        credits: [],
        albumTitle: "Opaque Album",
        duration: .seconds(90),
        mediaSourceID: nil,
        artwork: DulcetArtwork(seed: "track:other", palette: .indigoCoral)
    )
    let other = DulcetFailedPlayback(track: otherTrack, canSkip: false, canRetry: true)
    playback.publish(DulcetPlaybackPresentation(status: .failed, nowPlaying: nil, failure: other))
    #expect(store.snapshot.playbackFailure == other)
    #expect(store.showsNowPlayingBar)
}

@MainActor
private func failure(
    entry: String,
    attempt: String,
    canSkip: Bool,
    stoppedPartway: Bool = false
) -> DulcetPlaybackPresentation {
    DulcetPlaybackPresentation(status: .failed, nowPlaying: nil, failure: DulcetFailedPlayback(
        track: fixtureLibraryAlbum().tracks[0],
        canSkip: canSkip,
        canRetry: true,
        stoppedPartway: stoppedPartway,
        queueEntryID: entry,
        attemptID: attempt
    ))
}

@Test @MainActor
func aQueueEditThatChangesWhatSkipCanDoLeavesADismissedFailureAway() {
    let playback = ControlledPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: playback
    ))
    playback.publish(failure(entry: "entry:1", attempt: "attempt:1", canSkip: false))
    store.dismissPlaybackFailure()
    #expect(!store.showsNowPlayingBar)
    // Adding a track after the failed one gives Skip somewhere to go. The failure is the same
    // one the person put away, so the bar stays away.
    playback.publish(failure(entry: "entry:1", attempt: "attempt:1", canSkip: true))
    #expect(store.snapshot.playbackFailure?.canSkip == true)
    #expect(!store.showsNowPlayingBar)
}

@Test @MainActor
func anotherAttemptOrAnotherKindOfFailureIsNotTheOneDismissed() {
    let playback = ControlledPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: playback
    ))
    playback.publish(failure(entry: "entry:1", attempt: "attempt:1", canSkip: true))
    store.dismissPlaybackFailure()
    #expect(!store.showsNowPlayingBar)
    // The same entry and track, a later attempt: a new failure.
    playback.publish(failure(entry: "entry:1", attempt: "attempt:2", canSkip: true))
    #expect(store.showsNowPlayingBar)

    store.dismissPlaybackFailure()
    #expect(!store.showsNowPlayingBar)
    // The same attempt, now reported as having stopped partway: a different failure.
    playback.publish(failure(entry: "entry:1", attempt: "attempt:2", canSkip: true, stoppedPartway: true))
    #expect(store.showsNowPlayingBar)

    store.dismissPlaybackFailure()
    // The same track at another place in the queue: a different entry, so a different failure.
    playback.publish(failure(entry: "entry:2", attempt: "attempt:2", canSkip: true, stoppedPartway: true))
    #expect(store.showsNowPlayingBar)
}

// MARK: - The bar and the player say the same thing

// tvOS has no now-playing bar, so there is nothing there for the player to agree with.
#if os(macOS) || os(iOS)
@Test @MainActor
func theBarAndThePlayerAgreeThatATrackStoppedPartway() {
    let partway = failure(canSkip: true, stoppedPartway: true)
    let title = fixtureLibraryAlbum().tracks[0].title
    #expect(DulcetNowPlayingBar.failureLine(for: partway) == "Stopped partway through")
    #expect(DulcetNowPlayingBar.failureAnnouncement(for: partway)
        == "\u{201C}\(title)\u{201D} stopped partway through")
    #expect(DulcetPlaybackFailedView.message(for: partway).contains("stopped partway"))
    // None of the three says it could not play, or could not start.
    for text in [
        DulcetNowPlayingBar.failureLine(for: partway),
        DulcetNowPlayingBar.failureAnnouncement(for: partway),
        DulcetPlaybackFailedView.message(for: partway),
    ] {
        #expect(!text.localizedCaseInsensitiveContains("couldn\u{2019}t"), "\(text)")
    }

    let beforeStart = failure(canSkip: true)
    #expect(DulcetNowPlayingBar.failureLine(for: beforeStart) == "Couldn\u{2019}t play this track")
    #expect(DulcetNowPlayingBar.failureAnnouncement(for: beforeStart)
        == "Couldn\u{2019}t play \u{201C}\(title)\u{201D}")
    for text in [
        DulcetNowPlayingBar.failureLine(for: beforeStart),
        DulcetNowPlayingBar.failureAnnouncement(for: beforeStart),
        DulcetPlaybackFailedView.message(for: beforeStart),
    ] {
        #expect(!text.contains("partway"), "\(text)")
    }
}
#endif

// MARK: - A drop that carries nothing is refused out loud

@Test @MainActor
func aDropThatCarriesNothingIsRefusedOutLoud() throws {
    DulcetQueueDragRegistry.removeAll()
    let editing = EditingPlaybackController()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: ControlledAccountConnector(),
        playbackController: editing
    ))
    // A disabled tile -- offline, or its track list not read yet -- still lifts, so the drag
    // interaction keeps one identity; what it carries is nothing, and the drag says so.
    let nothing = DulcetQueueDragRegistry.register(nil)
    #expect(!DulcetQueueDragRegistry.activeDragCarriesAddition)
    #expect(!DulcetQueueDragRegistry.dropOntoQueue([nothing], store: store))
    #expect(editing.edits.isEmpty)
    #expect(store.snapshot.refusedQueueEdits == 1, "a drop that adds nothing must not be silent")

    // A drag that carries something is not refused.
    let addition = DulcetQueueAddition.album(fixtureLibraryAlbum())
    let something = DulcetQueueDragRegistry.register(addition)
    #expect(DulcetQueueDragRegistry.activeDragCarriesAddition)
    #expect(DulcetQueueDragRegistry.dropOntoQueue([something], store: store))
    #expect(store.snapshot.refusedQueueEdits == 1)
}

// MARK: - An in-place local-network retry never moves the person

@Test @MainActor
func anInPlaceRetryNeverMovesThePersonAndConnectionShowsItConnecting() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    store.navigate(to: .library)
    probe.answer(.notDenied)
    #expect(connector.requests.count == 2, "the grant retried in place")
    #expect(store.snapshot.selectedDestination == .library)

    // The person opens Connection while the retry runs: it is connecting, not refused.
    store.navigate(to: .settings)
    #expect(shownFailureKind(store) == nil, "the refusal no longer applies")
    #expect(store.snapshot.accountConnection == .connecting)
    #expect(store.snapshot.state == .accountConnecting)

    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "http://10.0.0.20:4533"
    )))
    #expect(store.snapshot.selectedDestination == .settings, "an in-place retry never moves them")
    #expect(store.snapshot.accountConnected)
    #expect(store.snapshot.state == .accountConnected)
}

/// The grant is answered by the live watch, not by whatever the account status happens to show:
/// opening a saved account's library replaces the refusal on the status with "saved", and the
/// person who went there is the one most waiting for the connection.
@Test @MainActor
func accessGrantedAfterASavedAccountsLibraryReplacedTheRefusalStillConnects() {
    let connector = ControlledAccountConnector()
    let probe = ControlledLocalNetworkAccess()
    let saved = DulcetAccountConnectRequest(
        serverURL: "http://10.0.0.20:4533",
        username: "listener",
        password: "fixture-password",
        allowLocalHTTP: true
    )
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        credentialStore: MemoryCredentialStore(persisted: saved),
        localNetworkAccess: probe
    ))
    #expect(store.snapshot.state == .accountSavedDisconnected, "the experiment needs a saved account")
    store.navigate(to: .settings)
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    #expect(shownFailureKind(store) == .localNetworkAccessDenied)
    store.navigate(to: .library)
    // The condition under test: the refusal is no longer what the status shows.
    #expect(shownFailureKind(store) == nil)
    #expect(store.snapshot.accountConnection == .saved(serverName: "10.0.0.20:4533"))

    probe.answer(.notDenied)
    #expect(connector.requests.count == 2, "the grant retried the connection")
    #expect(store.snapshot.selectedDestination == .library)
    #expect(store.snapshot.accountConnection == .connecting)

    // Opening Library again while it runs does not make the account look saved and idle, so
    // Connection still shows the connection running, with Cancel.
    store.navigate(to: .library)
    #expect(store.snapshot.accountConnection == .connecting)
    store.navigate(to: .settings)
    #expect(store.snapshot.state == .accountConnecting)
    store.navigate(to: .library)

    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "http://10.0.0.20:4533"
    )))
    #expect(store.snapshot.accountConnected)
    #expect(store.snapshot.selectedDestination == .library)
    #expect(store.snapshot.state != .accountSavedDisconnected)
    #expect(connector.requests.count == 2, "retried once, not again")
}

@MainActor
private final class CommittedLibraryBrowser: DulcetLibraryBrowsing, DulcetCommittedLibraryBrowsing {
    private(set) var committedReads: [@MainActor (DulcetLibraryBrowseOutcome) -> Void] = []

    func browse(
        _ request: DulcetLibraryBrowseRequest,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        ControlledLibraryOperation()
    }

    func browseCommitted(
        providerInstanceID: String,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        committedReads.append(completion)
        return ControlledLibraryOperation()
    }
}

@MainActor
private final class InstanceCredentialStore: DulcetProviderInstanceCredentialStoring {
    private var persisted: DulcetAccountConnectRequest?
    private(set) var providerInstanceID: String?

    init(persisted: DulcetAccountConnectRequest, providerInstanceID: String) {
        self.persisted = persisted
        self.providerInstanceID = providerInstanceID
    }

    func load() throws -> DulcetAccountConnectRequest? { persisted }
    func save(_ request: DulcetAccountConnectRequest) throws { persisted = request }
    func save(_ request: DulcetAccountConnectRequest, providerInstanceID: String) throws {
        persisted = request
        self.providerInstanceID = providerInstanceID
    }
    func delete() throws { persisted = nil }
}

/// The same retry where the saved account's library is read from what is held on the device: a
/// read begun while the retry ran must not overwrite how the retry ended.
@Test @MainActor
func aHeldLibraryReadDoesNotOverwriteHowAnInPlaceRetryEnded() {
    let connector = ControlledAccountConnector()
    let probe = ControlledLocalNetworkAccess()
    let browser = CommittedLibraryBrowser()
    let store = DulcetPresentationStore(source: DulcetAccountDataSource(
        connector: connector,
        credentialStore: InstanceCredentialStore(
            persisted: DulcetAccountConnectRequest(
                serverURL: "http://10.0.0.20:4533",
                username: "listener",
                password: "fixture-password",
                allowLocalHTTP: true
            ),
            providerInstanceID: "provider-instance-held"
        ),
        libraryBrowser: browser,
        localNetworkAccess: probe
    ))
    store.navigate(to: .settings)
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    store.navigate(to: .library)
    probe.answer(.notDenied)
    #expect(connector.requests.count == 2, "the grant retried the connection")
    // Library is opened again while the retry runs: a held read begins, and the status still
    // says the connection is running.
    store.navigate(to: .library)
    #expect(browser.committedReads.count == 2, "the experiment needs a held read in flight")
    #expect(store.snapshot.accountConnection == .connecting)

    connector.complete(.failed(accountFailure(.transportUnreachable)))
    #expect(shownFailureKind(store) == .transportUnreachable)
    browser.committedReads[1](.loaded(musicFolders: [], artists: [], albums: []))
    #expect(store.snapshot.state != .libraryLoading, "the held read landed")
    #expect(shownFailureKind(store) == .transportUnreachable, "the retry's outcome stands")
    store.navigate(to: .settings)
    #expect(store.snapshot.state != .accountConnecting, "nothing is connecting any more")
}

/// The person corrects the address while the refusal shows, and has not pressed Connect yet.
@Test @MainActor
func editingTheAddressWhileTheRefusalShowsEndsItAndKeepsTheEdit() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    #expect(shownFailureKind(store) == .localNetworkAccessDenied, "the experiment needs the refusal showing")

    store.accountServerURL = "http://10.0.0.99:4533"
    #expect(probe.watches.last?.cancelled == true, "the edit ends the watch")
    #expect(shownFailureKind(store) == nil, "Connection no longer promises to connect on its own")
    #expect(store.snapshot.selectedDestination == .settings)

    // A grant from the ended watch retries nothing, and the edit stays in the field.
    probe.answer(.notDenied)
    #expect(connector.requests.count == 1)
    #expect(store.accountServerURL == "http://10.0.0.99:4533")
    // ...and is still there after the person goes somewhere and comes back.
    store.navigate(to: .library)
    store.navigate(to: .settings)
    #expect(store.accountServerURL == "http://10.0.0.99:4533")

    // Connect sends what was edited.
    store.submitAccountConnection()
    #expect(connector.requests.last?.serverURL == "http://10.0.0.99:4533")
}

/// An edit that changes nothing is not an edit: the retry still comes.
@Test @MainActor
func settingTheAddressToWhatItAlreadyIsKeepsTheRetry() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    store.accountServerURL = "http://10.0.0.20:4533"
    #expect(probe.watches.last?.cancelled == false)
    probe.answer(.notDenied)
    #expect(connector.requests.count == 2, "the grant retried the connection")
}

/// The privacy check can take as long as the person takes to answer the system's prompt; someone
/// who has left the spinner meanwhile is not pulled back to Connection by its answer.
@Test @MainActor
func thePrivacyChecksAnswerArrivingAfterThePersonLeftDoesNotMoveThem() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    #expect(store.snapshot.state == .accountConnecting, "the spinner waits for the privacy check")
    store.navigate(to: .library)
    probe.answer(.denied)
    #expect(store.snapshot.selectedDestination == .library, "a denial does not move them")
    #expect(shownFailureKind(store) == .localNetworkAccessDenied, "it is recorded on the status")
    store.navigate(to: .settings)
    #expect(shownFailureKind(store) == .localNetworkAccessDenied, "Connection shows it")
    probe.answer(.notDenied)
    #expect(connector.requests.count == 2, "the grant still retries")

    // The other answer -- privacy was never the obstacle -- is recorded the same way.
    let (other, otherConnector, otherProbe) = localNetworkStore()
    other.submitAccountConnection()
    otherConnector.complete(.failed(accountFailure(.transportUnreachable)))
    other.navigate(to: .search)
    otherProbe.answer(.notDenied)
    #expect(other.snapshot.selectedDestination == .search)
    #expect(shownFailureKind(other) == .transportUnreachable)
    other.navigate(to: .settings)
    #expect(shownFailureKind(other) == .transportUnreachable)
}

@Test @MainActor
func anInPlaceRetryCanBeCancelledFromConnection() {
    let (store, connector, probe) = localNetworkStore()
    store.submitAccountConnection()
    connector.complete(.failed(accountFailure(.transportUnreachable)))
    probe.answer(.denied)
    store.navigate(to: .search)
    probe.answer(.notDenied)
    store.navigate(to: .settings)
    store.cancelAccountConnection()
    #expect(store.snapshot.selectedDestination == .settings)
    #expect(store.snapshot.state != .accountConnecting)
    // A completion arriving after the cancel changes nothing.
    connector.complete(.connected(DulcetConnectedAccountSummary(
        serverName: "Music",
        normalizedServerURL: "http://10.0.0.20:4533"
    )))
    #expect(!store.snapshot.accountConnected)
    #expect(store.snapshot.selectedDestination == .settings)
}
