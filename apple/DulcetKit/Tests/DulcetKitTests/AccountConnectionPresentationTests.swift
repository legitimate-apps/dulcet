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
func accountRemovalDeletesCredentialBeforeCancellingWorkAndClearingAccountState() async {
    var events: [String] = []
    let connector = ControlledAccountConnector()
    let libraryBrowser = ControlledLibraryBrowser(onCancel: { events.append("library-cancel") })
    let artworkFetcher = ControlledArtworkFetcher(onRemove: { _ in events.append("artwork-remove") })
    let credentials = MemoryCredentialStore(
        persisted: nil,
        deleteAction: { events.append("credential-delete") }
    )
    let source = DulcetAccountDataSource(
        connector: connector,
        credentialStore: credentials,
        libraryBrowser: libraryBrowser,
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
    store.selectDestination(.library)
    #expect(libraryBrowser.operations.single?.cancelCount == 0)

    store.removeAccount()

    #expect(events == ["credential-delete", "library-cancel"])
    #expect(credentials.deleteCount == 1)
    #expect(store.snapshot.state == .accountRemoving)
    #expect(store.snapshot.accountRemoval == .removing)
    #expect(store.snapshot.accountConnected)
    store.selectDestination(.library)
    #expect(store.selectedDestination == .settings)
    #expect(libraryBrowser.requests.count == 1)

    await settleSearchTask(until: { events.count == 3 })

    #expect(events == ["credential-delete", "library-cancel", "artwork-remove"])
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
}

@Test @MainActor
func failedCredentialDeletionKeepsConnectedLibraryIntactAndCanBeRetried() async {
    enum DeleteFailure: Error { case denied }
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

    #expect(events == ["credential-delete"])
    #expect(credentials.deleteCount == 1)
    #expect(store.snapshot.state == .accountRemovalError)
    #expect(store.snapshot.accountRemoval == .failed)
    #expect(store.snapshot.accountConnected)
    #expect(store.snapshot.albums == [album])
    #expect(store.accountPassword == "fixture-password")
    #expect(artworkFetcher.removedServerIDs.isEmpty)
    #expect(libraryBrowser.operations.single?.cancelCount == 0)

    store.dismissAccountRemovalFailure()
    #expect(store.snapshot.state == .accountConnected)
    #expect(store.snapshot.accountRemoval == .idle)
    #expect(store.snapshot.albums == [album])

    deleteDecision.shouldFail = false
    store.removeAccount()
    await settleSearchTask(until: { events.count == 3 })

    #expect(credentials.deleteCount == 2)
    #expect(events == ["credential-delete", "credential-delete", "artwork-remove"])
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
    #expect(store.snapshot.state == .accountConnected)
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
private final class ControlledLibraryBrowser: DulcetLibraryBrowsing {
    private let onCancel: @MainActor () -> Void
    private(set) var requests: [DulcetLibraryBrowseRequest] = []
    private(set) var operations: [ControlledLibraryOperation] = []
    private var completions: [(@MainActor (DulcetLibraryBrowseOutcome) -> Void)] = []

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

    func complete(_ outcome: DulcetLibraryBrowseOutcome) {
        guard !completions.isEmpty else { return }
        completions.removeFirst()(outcome)
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
private func searchResult(id: String, title: String) -> DulcetSearchResult {
    DulcetSearchResult(
        id: DulcetProviderItemID(
            providerInstanceID: "provider-instance-fixture",
            rawID: id
        ),
        title: title,
        kind: .track,
        credits: [DulcetCredit(role: .artist, name: "Fixture Artist", id: nil)],
        albumTitle: "Fixture Album",
        year: 2026,
        duration: .seconds(61),
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
        artistResultCount: 0,
        albumResultCount: 0,
        trackResultCount: results.count,
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
    private let persisted: DulcetAccountConnectRequest?
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
    }

    func delete() throws {
        deleteCount += 1
        try deleteAction()
    }
}

@MainActor
private final class ControlledDeleteDecision {
    var shouldFail = true
}
