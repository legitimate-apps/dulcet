import Foundation

public struct DulcetAccountErrorContext: Sendable, Hashable {
    public let kind: DulcetAccountFailureKind
    public let serverName: String
    public let targetHost: String?
    public let invalidServerURLIsInternationalized: Bool

    public init(
        kind: DulcetAccountFailureKind,
        serverName: String,
        targetHost: String? = nil,
        invalidServerURLIsInternationalized: Bool = false
    ) {
        self.kind = kind
        self.serverName = serverName
        self.targetHost = targetHost
        self.invalidServerURLIsInternationalized = invalidServerURLIsInternationalized
    }
}

/// The exhaustive switch is intentional: a new presentation kind cannot compile without copy.
public enum DulcetAccountErrorPresenter {
    public static func presentation(
        for context: DulcetAccountErrorContext
    ) -> DulcetAccountFailurePresentation {
        let copy: (title: String, message: String, recovery: String) = switch context.kind {
        case .invalidServerURL where context.invalidServerURLIsInternationalized:
            (
                "Use the punycode server address",
                "Dulcet cannot safely compare internationalized host names yet.",
                "Enter the punycode spelling instead. For example, müsik.example.invalid is xn--msik-0ra.example.invalid."
            )
        case .invalidServerURL:
            (
                "Check the server address",
                "That address is not a valid OpenSubsonic server URL.",
                "Enter the full http:// or https:// address. Plain HTTP is limited to explicitly allowed local servers."
            )
        case .transportUnreachable:
            (
                "The server could not be reached",
                "Dulcet could not establish a connection to \(context.serverName).",
                "Check that the server is running and reachable from this device, then try again."
            )
        case .transportTimeout:
            (
                "The server took too long to respond",
                "Account setup reached its 30-second request limit.",
                "Check the server and network, then try again. You can cancel while Dulcet is waiting."
            )
        case .transportCancelled:
            (
                "Connection cancelled",
                "Dulcet stopped the account connection.",
                "Choose Connect whenever you are ready to try again."
            )
        case .tlsUntrusted:
            (
                "This server’s certificate isn’t trusted",
                "Dulcet stopped before sending account credentials.",
                "Fix or renew the certificate. For a private certificate authority, install the CA at the operating-system level, then try again."
            )
        case .localNetworkPolicyRejected:
            (
                "Local HTTP is not allowed",
                "Dulcet will not send credentials over this plaintext connection.",
                "Use HTTPS, or enable local HTTP only for a server you control on a private local network."
            )
        case .redirectRejected:
            (
                "The server redirect was refused",
                "The redirect could not be followed safely.",
                "Enter the server’s final HTTPS address directly and try again."
            )
        case .malformedEnvelope:
            (
                "The server response was malformed",
                "The server replied successfully but its OpenSubsonic envelope was not valid.",
                "Check the server version or its reverse-proxy response, then try again."
            )
        case .incompatibleProtocol:
            (
                "The server protocol is not compatible",
                "This server does not support the OpenSubsonic version Dulcet needs.",
                "Update the server, or use a compatible OpenSubsonic endpoint."
            )
        case .notASubsonicServer:
            (
                "This is not an OpenSubsonic endpoint",
                "The address responded, but not as an OpenSubsonic server.",
                "Enter the server base address rather than a web player or sign-in page."
            )
        case .knownServerError:
            (
                "The server rejected account setup",
                "The server returned a recognized OpenSubsonic error.",
                "Review the account and server settings, then try again."
            )
        case .unknownServerError:
            (
                "The server could not complete account setup",
                "The server returned an OpenSubsonic error Dulcet does not recognize.",
                "Check the server logs or version, then try again."
            )
        case .invalidCredentials:
            (
                "The username or password was not accepted",
                "The server rejected these account credentials.",
                "Check the username and password, then try again."
            )
        case .tokenAuthenticationUnsupported:
            (
                "This account authentication is not supported",
                "The server rejected salted-token authentication.",
                "Enable token authentication on the server or use a compatible endpoint."
            )
        case .forbidden:
            (
                "This account is not allowed to connect",
                "The server accepted the credentials but denied access.",
                "Ask the server administrator to allow Subsonic API access for this account."
            )
        case .unsupportedAuthenticationChallenge:
            (
                "The intermediary’s sign-in method is not supported",
                "A reverse proxy requested HTTP, proxy, or client-certificate authentication that account setup does not support.",
                "Expose the OpenSubsonic endpoint without that extra authentication challenge, then try again."
            )
        case .crossOriginRedirectRejected:
            (
                "The server redirected to another sign-in host",
                context.targetHost.map {
                    "The server bounced account setup to \($0), so Dulcet refused to carry credentials there."
                } ?? "The server bounced account setup to another host, so Dulcet refused to carry credentials there.",
                "Exempt /rest/ from the SSO or identity-provider layer, or point Dulcet at an OpenSubsonic endpoint that is not behind it."
            )
        case .capabilityUnsupported:
            (
                "A required server capability is unavailable",
                "This server cannot provide a capability required for account setup.",
                "Update the server or use an endpoint with the required OpenSubsonic capability."
            )
        case .credentialPersistenceFailed:
            (
                "The account could not be saved",
                "The server accepted the account, but the system Keychain did not save it.",
                "Review Keychain access for Dulcet, then connect again."
            )
        }

        let localizationPrefix = if context.kind == .invalidServerURL &&
            context.invalidServerURLIsInternationalized {
            "account.error.unsupportedInternationalizedHost"
        } else {
            "account.error.\(context.kind.rawValue)"
        }
        let localizedMessage: String
        if context.kind == .crossOriginRedirectRejected {
            localizedMessage = DulcetStrings.dynamicFormatted(
                "\(localizationPrefix).message",
                fallback: "The server bounced account setup to %@, so Dulcet refused to carry credentials there.",
                context.targetHost ?? "another host"
            )
        } else {
            localizedMessage = DulcetStrings.dynamicText(
                "\(localizationPrefix).message",
                fallback: copy.message
            )
        }
        return DulcetAccountFailurePresentation(
            kind: context.kind,
            serverName: context.serverName,
            title: DulcetStrings.dynamicText(
                "\(localizationPrefix).title",
                fallback: copy.title
            ),
            message: localizedMessage,
            recovery: DulcetStrings.dynamicText(
                "\(localizationPrefix).recovery",
                fallback: copy.recovery
            ),
            targetHost: context.targetHost
        )
    }
}

@MainActor
public protocol DulcetAccountConnectOperation: AnyObject {
    func cancel()
}

@MainActor
public protocol DulcetAccountConnecting: AnyObject {
    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation
}

public struct DulcetLibraryBrowseRequest: Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let providerInstanceID: String
    public let normalizedServerURL: String
    public let username: String
    public let password: String
    public let allowLocalHTTP: Bool

    public init(
        providerInstanceID: String,
        normalizedServerURL: String,
        username: String,
        password: String,
        allowLocalHTTP: Bool
    ) {
        self.providerInstanceID = providerInstanceID
        self.normalizedServerURL = normalizedServerURL
        self.username = username
        self.password = password
        self.allowLocalHTTP = allowLocalHTTP
    }

    public var description: String { "DulcetLibraryBrowseRequest(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [("libraryBrowseRequest", "<redacted>" as Any)], displayStyle: .struct)
    }
}

public enum DulcetLibraryBrowseOutcome: Sendable {
    case loaded(
        musicFolders: [DulcetMusicFolder],
        artists: [DulcetArtist],
        albums: [DulcetAlbum]
    )
    case failed(DulcetLibraryFailure)
    case cancelled
}

@MainActor
public protocol DulcetLibraryBrowseOperation: AnyObject {
    func cancel()
}

@MainActor
public protocol DulcetLibraryBrowsing: AnyObject {
    func browse(
        _ request: DulcetLibraryBrowseRequest,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation
}

public struct DulcetSearchPageRequest: Sendable,
    CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public let providerInstanceID: String
    public let normalizedServerURL: String
    public let username: String
    public let password: String
    public let allowLocalHTTP: Bool
    public let query: String
    public let artistCount: Int
    public let artistOffset: Int
    public let albumCount: Int
    public let albumOffset: Int
    public let trackCount: Int
    public let trackOffset: Int

    public init(
        providerInstanceID: String,
        normalizedServerURL: String,
        username: String,
        password: String,
        allowLocalHTTP: Bool,
        query: String,
        artistCount: Int,
        artistOffset: Int,
        albumCount: Int,
        albumOffset: Int,
        trackCount: Int,
        trackOffset: Int
    ) {
        self.providerInstanceID = providerInstanceID
        self.normalizedServerURL = normalizedServerURL
        self.username = username
        self.password = password
        self.allowLocalHTTP = allowLocalHTTP
        self.query = query
        self.artistCount = artistCount
        self.artistOffset = artistOffset
        self.albumCount = albumCount
        self.albumOffset = albumOffset
        self.trackCount = trackCount
        self.trackOffset = trackOffset
    }

    public var description: String { "DulcetSearchPageRequest(<redacted>)" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: [("searchPageRequest", "<redacted>" as Any)], displayStyle: .struct)
    }
}

public struct DulcetSearchPage: Sendable, Hashable {
    public let results: [DulcetSearchResult]
    public let artistResultCount: Int
    public let albumResultCount: Int
    public let trackResultCount: Int
    public let artistHasMore: Bool
    public let albumHasMore: Bool
    public let trackHasMore: Bool

    public init(
        results: [DulcetSearchResult],
        artistResultCount: Int,
        albumResultCount: Int,
        trackResultCount: Int,
        artistHasMore: Bool,
        albumHasMore: Bool,
        trackHasMore: Bool
    ) {
        self.results = results
        self.artistResultCount = artistResultCount
        self.albumResultCount = albumResultCount
        self.trackResultCount = trackResultCount
        self.artistHasMore = artistHasMore
        self.albumHasMore = albumHasMore
        self.trackHasMore = trackHasMore
    }
}

public enum DulcetSearchPageOutcome: Sendable {
    case loaded(DulcetSearchPage)
    case failed(DulcetSearchFailure)
    case cancelled
}

@MainActor
public protocol DulcetSearchOperation: AnyObject {
    func cancel()
}

@MainActor
public protocol DulcetServerSearching: AnyObject {
    func search(
        _ request: DulcetSearchPageRequest,
        completion: @escaping @MainActor (DulcetSearchPageOutcome) -> Void
    ) -> any DulcetSearchOperation
}

/// Live presentation source for account setup. Network and persistence adapters stay replaceable.
@MainActor
public final class DulcetAccountDataSource: DulcetDataSource {
    private let connector: any DulcetAccountConnecting
    private let credentialStore: (any DulcetCredentialStoring)?
    private let libraryBrowser: (any DulcetLibraryBrowsing)?
    private let artworkFetcher: (any DulcetArtworkFetching)?
    private let serverSearch: (any DulcetServerSearching)?
    private let playbackController: (any DulcetPlaybackControlling)?
    private let searchDebounce: Duration
    private let providerInstanceIDFactory: @MainActor () -> String
    private var snapshotHandler: (@MainActor (DulcetSnapshot) -> Void)?
    private var activeOperation: (any DulcetAccountConnectOperation)?
    private var activeLibraryOperation: (any DulcetLibraryBrowseOperation)?
    private var activeSearchOperation: (any DulcetSearchOperation)?
    private var searchDebounceTask: Task<Void, Never>?
    private var accountRemovalTask: Task<Void, Never>?
    private var generation = 0
    private var libraryGeneration = 0
    private var searchGeneration = 0
    private var providerInstanceID: String?
    private var accountRemovalStatus: DulcetAccountRemovalStatus = .idle
    private var savedServerName: String?
    private var searchQuery = ""
    private var searchResults: [DulcetSearchResult] = []
    private var searchHasMoreKinds: Set<DulcetSearchResultKind> = []
    private var searchLoadingMoreKind: DulcetSearchResultKind?
    private var searchFailure: DulcetSearchFailure?

    static let defaultSearchDebounce: Duration = .milliseconds(250)
    private static let searchPageSize = 20

    public private(set) var currentSnapshot: DulcetSnapshot

    public init(
        connector: any DulcetAccountConnecting,
        credentialStore: (any DulcetCredentialStoring)? = nil,
        libraryBrowser: (any DulcetLibraryBrowsing)? = nil,
        artworkFetcher: (any DulcetArtworkFetching)? = nil,
        serverSearch: (any DulcetServerSearching)? = nil,
        playbackController: (any DulcetPlaybackControlling)? = nil,
        initialRequest: DulcetAccountConnectRequest = .empty,
        searchDebounce: Duration = .milliseconds(250),
        providerInstanceIDFactory: @escaping @MainActor () -> String = { UUID().uuidString }
    ) {
        self.connector = connector
        self.credentialStore = credentialStore
        self.libraryBrowser = libraryBrowser
        self.artworkFetcher = artworkFetcher
        self.serverSearch = serverSearch
        self.playbackController = playbackController
        self.searchDebounce = searchDebounce
        self.providerInstanceIDFactory = providerInstanceIDFactory
        do {
            if let restoredRequest = try credentialStore?.load() {
                let serverName = Self.savedServerName(for: restoredRequest.serverURL)
                savedServerName = serverName
                currentSnapshot = Self.snapshot(
                    state: .accountSavedDisconnected,
                    form: restoredRequest,
                    status: .saved(serverName: serverName)
                )
            } else {
                savedServerName = nil
                currentSnapshot = Self.snapshot(
                    state: .accountConnectIdle,
                    form: initialRequest,
                    status: .idle
                )
            }
        } catch {
            savedServerName = nil
            let failure = DulcetAccountErrorPresenter.presentation(for: DulcetAccountErrorContext(
                kind: .credentialPersistenceFailed,
                serverName: "Music server"
            ))
            currentSnapshot = Self.snapshot(
                state: .accountErrorPersistence,
                form: initialRequest,
                status: .failed(failure)
            )
        }
        playbackController?.setPresentationHandler { [weak self] presentation in
            self?.receivePlaybackPresentation(presentation)
        }
    }

    public func setSnapshotHandler(
        _ handler: @escaping @MainActor (DulcetSnapshot) -> Void
    ) {
        snapshotHandler = handler
    }

    public func send(_ action: DulcetPresentationAction) {
        if accountRemovalStatus == .removing {
            snapshotHandler?(currentSnapshot)
            return
        }
        switch action {
        case let .selectDestination(destination):
            switch destination {
            case .settings:
                cancelLibraryBrowse()
                cancelSearchRequest()
                publish(
                    state: currentSnapshot.state.accountStateOrIdle,
                    destination: .settings,
                    form: currentSnapshot.accountForm,
                    status: currentSnapshot.accountConnection
                )
            case .library:
                cancelSearchRequest()
                openLibrary()
            case .search:
                cancelLibraryBrowse()
                openSearch()
            case .nowPlaying:
                cancelLibraryBrowse()
                cancelSearchRequest()
                publish(
                    state: .nowPlayingUnavailable,
                    destination: .nowPlaying,
                    form: currentSnapshot.accountForm,
                    status: currentSnapshot.accountConnection
                )
            }
        case let .updateSearchQuery(query):
            updateSearchQuery(query)
        case let .loadMoreSearchResults(kind):
            loadMoreSearchResults(kind)
        case .retrySearch:
            startInitialSearch(debounce: false)
        case let .selectAlbum(id):
            guard currentSnapshot.selectedDestination == .library,
                  let album = currentSnapshot.albums.first(where: { $0.id == id }) else { return }
            publish(
                state: .albumDetailMultiDisc,
                destination: .library,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection,
                selectedAlbum: album
            )
        case let .playLibrary(shuffle):
            let tracks = currentSnapshot.albums.flatMap(\.tracks)
            guard !tracks.isEmpty else { return }
            beginPlayback(DulcetPlaybackQueueIntent(
                tracks: tracks,
                sourceKind: .library,
                sourceID: nil,
                sourceDisplayName: DulcetStrings.library,
                startIndex: shuffle ? nil : 0,
                shuffle: shuffle
            ))
        case let .playAlbum(id, shuffle):
            guard let album = currentSnapshot.albums.first(where: { $0.id == id }),
                  !album.tracks.isEmpty else { return }
            beginPlayback(DulcetPlaybackQueueIntent(
                tracks: album.tracks,
                sourceKind: .album,
                sourceID: album.id,
                sourceDisplayName: album.title,
                startIndex: shuffle ? nil : 0,
                shuffle: shuffle
            ))
        case let .activateTrack(albumID, trackID):
            guard let album = currentSnapshot.albums.first(where: { $0.id == albumID }),
                  let index = album.tracks.firstIndex(where: { $0.id == trackID }) else { return }
            beginPlayback(DulcetPlaybackQueueIntent(
                tracks: album.tracks,
                sourceKind: .album,
                sourceID: album.id,
                sourceDisplayName: album.title,
                startIndex: index,
                shuffle: false
            ))
        case let .playbackControl(intent):
            playbackController?.send(intent)
        case let .submitAccountConnection(request):
            submit(request)
        case .cancelAccountConnection:
            cancelActiveSubmission()
        case .removeAccount:
            removeAccount()
        case .dismissAccountRemovalFailure:
            dismissAccountRemovalFailure()
        }
    }

    private func cancelActiveSubmission() {
        guard currentSnapshot.state == .accountConnecting else { return }
        generation += 1
        let operation = activeOperation
        activeOperation = nil
        operation?.cancel()
        publishSavedAccountOrIdle(form: currentSnapshot.accountForm)
    }

    private func submit(_ request: DulcetAccountConnectRequest) {
        cancelLibraryBrowse()
        // Where the person was when they asked to connect. Reconnect is reachable from the library
        // surface now, and sending them to settings on success answers a request they did not make:
        // they pressed Reconnect on the library screen to see their library.
        let origin = currentSnapshot.selectedDestination
        generation += 1
        let submissionGeneration = generation
        let supersededOperation = activeOperation
        activeOperation = nil
        supersededOperation?.cancel()
        publish(state: .accountConnecting, form: request, status: .connecting)

        let operation = connector.connect(request) { [weak self] outcome in
            guard let self, self.generation == submissionGeneration else { return }
            self.activeOperation = nil
            switch outcome {
            case let .connected(account):
                do {
                    try self.credentialStore?.save(request)
                    if self.credentialStore != nil {
                        self.savedServerName = account.serverName
                    }
                    self.providerInstanceID = self.providerInstanceID ?? self.providerInstanceIDFactory()
                    self.configurePlayback(account: account, request: request)
                    self.publish(
                        state: .accountConnected,
                        destination: .settings,
                        form: request,
                        status: .connected(account)
                    )
                    if origin == .library {
                        self.openLibrary()
                    }
                } catch {
                    let failure = DulcetAccountErrorPresenter.presentation(
                        for: DulcetAccountErrorContext(
                            kind: .credentialPersistenceFailed,
                            serverName: account.serverName
                        )
                    )
                    self.publish(
                        state: .accountErrorPersistence,
                        destination: .settings,
                        form: request,
                        status: .failed(failure)
                    )
                }
            case let .failed(failure) where failure.kind == .transportCancelled:
                self.publishSavedAccountOrIdle(form: request)
            case let .failed(failure):
                self.publish(
                    state: failure.kind.family.presentationState,
                    destination: .settings,
                    form: request,
                    status: .failed(failure)
                )
            }
        }
        if generation == submissionGeneration,
           currentSnapshot.state == .accountConnecting {
            activeOperation = operation
        }
    }

    private func publish(
        state: DulcetPresentationState,
        destination: DulcetSidebarDestination = .settings,
        form: DulcetAccountConnectRequest,
        status: DulcetAccountConnectionStatus,
        musicFolders: [DulcetMusicFolder] = [],
        artists: [DulcetArtist] = [],
        albums: [DulcetAlbum] = [],
        selectedAlbum: DulcetAlbum? = nil,
        libraryFailure: DulcetLibraryFailure? = nil,
        nowPlaying: DulcetNowPlaying? = nil
    ) {
        currentSnapshot = Self.snapshot(
            state: state,
            destination: destination,
            form: form,
            status: status,
            musicFolders: musicFolders,
            artists: artists,
            albums: albums,
            selectedAlbum: selectedAlbum,
            libraryFailure: libraryFailure,
            nowPlaying: nowPlaying,
            searchQuery: searchQuery,
            searchResults: searchResults,
            searchHasMoreKinds: searchHasMoreKinds,
            searchLoadingMoreKind: searchLoadingMoreKind,
            searchFailure: searchFailure,
            accountRemoval: accountRemovalStatus
        )
        snapshotHandler?(currentSnapshot)
    }

    private static func snapshot(
        state: DulcetPresentationState,
        destination: DulcetSidebarDestination = .settings,
        form: DulcetAccountConnectRequest,
        status: DulcetAccountConnectionStatus,
        musicFolders: [DulcetMusicFolder] = [],
        artists: [DulcetArtist] = [],
        albums: [DulcetAlbum] = [],
        selectedAlbum: DulcetAlbum? = nil,
        libraryFailure: DulcetLibraryFailure? = nil,
        nowPlaying: DulcetNowPlaying? = nil,
        searchQuery: String = "",
        searchResults: [DulcetSearchResult] = [],
        searchHasMoreKinds: Set<DulcetSearchResultKind> = [],
        searchLoadingMoreKind: DulcetSearchResultKind? = nil,
        searchFailure: DulcetSearchFailure? = nil,
        accountRemoval: DulcetAccountRemovalStatus = .idle
    ) -> DulcetSnapshot {
        let connectivity: DulcetConnectivity = switch status {
        case .idle, .connecting:
            .unavailable
        case let .saved(serverName):
            .disconnected(serverName: serverName)
        case let .connected(account):
            .online(serverName: account.serverName)
        case let .failed(failure):
            .connectionFailed(.account(failure))
        }
        return DulcetSnapshot(
            state: state,
            selectedDestination: destination,
            accountConnected: {
                if case .connected = status { return true }
                return false
            }(),
            connectivity: connectivity,
            albums: albums,
            musicFolders: musicFolders,
            artists: artists,
            looseTracks: [],
            recentlyAddedTracks: [],
            selectedAlbum: selectedAlbum,
            nowPlaying: nowPlaying,
            searchQuery: searchQuery,
            searchResults: searchResults,
            searchHasMoreKinds: searchHasMoreKinds,
            searchLoadingMoreKind: searchLoadingMoreKind,
            searchFailure: searchFailure,
            captureDate: Date(timeIntervalSince1970: 0),
            accountForm: form,
            accountConnection: status,
            accountRemoval: accountRemoval,
            libraryFailure: libraryFailure
        )
    }

    private func openLibrary() {
        cancelLibraryBrowse()
        guard case let .connected(account) = currentSnapshot.accountConnection else {
            if let savedServerName {
                publish(
                    state: .accountSavedDisconnected,
                    destination: .library,
                    form: currentSnapshot.accountForm,
                    status: .saved(serverName: savedServerName)
                )
                return
            }
            publish(
                state: .emptyLibraryNoAccount,
                destination: .library,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection
            )
            return
        }
        guard let libraryBrowser else {
            publish(
                state: .emptyLibraryConnected,
                destination: .library,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection
            )
            return
        }
        let instanceID = providerInstanceID ?? providerInstanceIDFactory()
        providerInstanceID = instanceID
        libraryGeneration += 1
        let requestGeneration = libraryGeneration
        let form = currentSnapshot.accountForm
        publish(
            state: .libraryLoading,
            destination: .library,
            form: form,
            status: currentSnapshot.accountConnection
        )
        let operation = libraryBrowser.browse(DulcetLibraryBrowseRequest(
            providerInstanceID: instanceID,
            normalizedServerURL: account.normalizedServerURL,
            username: form.username,
            password: form.password,
            allowLocalHTTP: form.allowLocalHTTP
        )) { [weak self] outcome in
            guard let self,
                  self.libraryGeneration == requestGeneration,
                  self.currentSnapshot.selectedDestination == .library else { return }
            self.activeLibraryOperation = nil
            switch outcome {
            case let .loaded(musicFolders, artists, albums):
                self.publish(
                    state: albums.isEmpty && artists.isEmpty
                        ? .emptyLibraryConnected
                        : .libraryBrowse,
                    destination: .library,
                    form: form,
                    status: self.currentSnapshot.accountConnection,
                    musicFolders: musicFolders,
                    artists: artists,
                    albums: albums
                )
            case let .failed(failure):
                self.publish(
                    state: .libraryError,
                    destination: .library,
                    form: form,
                    status: self.currentSnapshot.accountConnection,
                    libraryFailure: failure
                )
            case .cancelled:
                break
            }
        }
        if libraryGeneration == requestGeneration,
           currentSnapshot.state == .libraryLoading {
            activeLibraryOperation = operation
        }
    }

    private func cancelLibraryBrowse() {
        libraryGeneration += 1
        let operation = activeLibraryOperation
        activeLibraryOperation = nil
        operation?.cancel()
    }

    private func openSearch() {
        guard case .connected = currentSnapshot.accountConnection else {
            publish(
                state: .searchIdle,
                destination: .search,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection
            )
            return
        }
        if searchQuery.trimmedForSearch.count >= 2,
           searchResults.isEmpty,
           searchFailure == nil {
            startInitialSearch(debounce: false)
            return
        }
        let state: DulcetPresentationState = if searchQuery.trimmedForSearch.count < 2 {
            .searchIdle
        } else if searchResults.isEmpty {
            searchFailure == nil ? .searchEmpty : .searchError
        } else {
            .searchResults
        }
        publish(
            state: state,
            destination: .search,
            form: currentSnapshot.accountForm,
            status: currentSnapshot.accountConnection
        )
    }

    private func updateSearchQuery(_ query: String) {
        searchQuery = query
        searchResults = []
        searchHasMoreKinds = []
        searchLoadingMoreKind = nil
        searchFailure = nil
        cancelSearchRequest()
        guard currentSnapshot.selectedDestination == .search else { return }
        guard query.trimmedForSearch.count >= 2 else {
            publish(
                state: .searchIdle,
                destination: .search,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection
            )
            return
        }
        startInitialSearch(debounce: true)
    }

    private func startInitialSearch(debounce: Bool) {
        cancelSearchRequest()
        guard currentSnapshot.selectedDestination == .search,
              case .connected = currentSnapshot.accountConnection,
              searchQuery.trimmedForSearch.count >= 2 else { return }
        searchResults = []
        searchHasMoreKinds = []
        searchLoadingMoreKind = nil
        searchFailure = nil
        publish(
            state: .searchLoading,
            destination: .search,
            form: currentSnapshot.accountForm,
            status: currentSnapshot.accountConnection
        )
        let requestGeneration = searchGeneration
        searchDebounceTask = Task { [weak self] in
            guard let self else { return }
            if debounce {
                try? await Task.sleep(for: searchDebounce)
            }
            guard !Task.isCancelled, searchGeneration == requestGeneration else { return }
            beginSearchPage(kind: nil, requestGeneration: requestGeneration)
        }
    }

    private func loadMoreSearchResults(_ kind: DulcetSearchResultKind) {
        guard currentSnapshot.selectedDestination == .search,
              searchHasMoreKinds.contains(kind),
              activeSearchOperation == nil,
              searchDebounceTask == nil else { return }
        searchLoadingMoreKind = kind
        searchFailure = nil
        publish(
            state: .searchResults,
            destination: .search,
            form: currentSnapshot.accountForm,
            status: currentSnapshot.accountConnection
        )
        beginSearchPage(kind: kind, requestGeneration: searchGeneration)
    }

    private func beginSearchPage(
        kind: DulcetSearchResultKind?,
        requestGeneration: Int
    ) {
        searchDebounceTask = nil
        guard let serverSearch,
              case let .connected(account) = currentSnapshot.accountConnection else {
            searchFailure = DulcetSearchFailure(kind: .capability)
            publish(
                state: searchResults.isEmpty ? .searchError : .searchResults,
                destination: .search,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection
            )
            return
        }
        let instanceID = providerInstanceID ?? providerInstanceIDFactory()
        providerInstanceID = instanceID
        let form = currentSnapshot.accountForm
        let request = DulcetSearchPageRequest(
            providerInstanceID: instanceID,
            normalizedServerURL: account.normalizedServerURL,
            username: form.username,
            password: form.password,
            allowLocalHTTP: form.allowLocalHTTP,
            query: searchQuery.trimmedForSearch,
            artistCount: kind == nil || kind == .artist ? Self.searchPageSize : 0,
            artistOffset: kind == .artist ? resultCount(for: .artist) : 0,
            albumCount: kind == nil || kind == .album ? Self.searchPageSize : 0,
            albumOffset: kind == .album ? resultCount(for: .album) : 0,
            trackCount: kind == nil || kind == .track ? Self.searchPageSize : 0,
            trackOffset: kind == .track ? resultCount(for: .track) : 0
        )
        let operation = serverSearch.search(request) { [weak self] outcome in
            guard let self,
                  searchGeneration == requestGeneration,
                  currentSnapshot.selectedDestination == .search else { return }
            activeSearchOperation = nil
            searchLoadingMoreKind = nil
            switch outcome {
            case let .loaded(page):
                searchFailure = nil
                if let kind {
                    appendOrReplace(page.results)
                    setHasMore(page.hasMore(for: kind), for: kind)
                } else {
                    searchResults = page.results
                    searchHasMoreKinds = Set(DulcetSearchResultKind.allCases.filter(page.hasMore))
                }
                publish(
                    state: searchResults.isEmpty ? .searchEmpty : .searchResults,
                    destination: .search,
                    form: form,
                    status: currentSnapshot.accountConnection
                )
            case let .failed(failure):
                searchFailure = failure
                publish(
                    state: searchResults.isEmpty ? .searchError : .searchResults,
                    destination: .search,
                    form: form,
                    status: currentSnapshot.accountConnection
                )
            case .cancelled:
                break
            }
        }
        if searchGeneration == requestGeneration,
           currentSnapshot.selectedDestination == .search {
            activeSearchOperation = operation
        }
    }

    private func cancelSearchRequest() {
        searchGeneration += 1
        searchDebounceTask?.cancel()
        searchDebounceTask = nil
        activeSearchOperation?.cancel()
        activeSearchOperation = nil
        searchLoadingMoreKind = nil
    }

    private func resultCount(for kind: DulcetSearchResultKind) -> Int {
        searchResults.lazy.filter { $0.kind == kind }.count
    }

    private func appendOrReplace(_ incoming: [DulcetSearchResult]) {
        for result in incoming {
            if let index = searchResults.firstIndex(where: { $0.id == result.id }) {
                searchResults[index] = result
            } else {
                searchResults.append(result)
            }
        }
    }

    private func setHasMore(_ hasMore: Bool, for kind: DulcetSearchResultKind) {
        if hasMore {
            searchHasMoreKinds.insert(kind)
        } else {
            searchHasMoreKinds.remove(kind)
        }
    }

    private func removeAccount() {
        guard case .connected = currentSnapshot.accountConnection else { return }
        let connectedStatus = currentSnapshot.accountConnection
        accountRemovalStatus = .removing
        publishAccountRemovalState(
            state: .accountRemoving,
            status: connectedStatus
        )

        do {
            try credentialStore?.delete()
        } catch {
            accountRemovalStatus = .failed
            publishAccountRemovalState(
                state: .accountRemovalError,
                status: connectedStatus
            )
            return
        }

        generation += 1
        activeOperation?.cancel()
        activeOperation = nil
        cancelLibraryBrowse()
        cancelSearchRequest()
        let removedServerID = providerInstanceID
        let cacheRemover = artworkFetcher as? any DulcetArtworkCacheRemoving
        accountRemovalTask = Task { [weak self] in
            if let removedServerID, let cacheRemover {
                await cacheRemover.removeCachedArtwork(serverID: removedServerID)
            }
            guard let self, !Task.isCancelled else { return }
            finishAccountRemoval()
        }
    }

    private func finishAccountRemoval() {
        accountRemovalTask = nil
        providerInstanceID = nil
        playbackController?.disconnect()
        searchQuery = ""
        searchResults = []
        searchHasMoreKinds = []
        searchLoadingMoreKind = nil
        searchFailure = nil
        accountRemovalStatus = .idle
        publish(
            state: .accountConnectIdle,
            destination: .settings,
            form: .empty,
            status: .idle
        )
    }

    private func configurePlayback(
        account: DulcetConnectedAccountSummary,
        request: DulcetAccountConnectRequest
    ) {
        guard let providerInstanceID else { return }
        playbackController?.configure(account: DulcetPlaybackAccount(
            providerInstanceID: providerInstanceID,
            normalizedServerURL: account.normalizedServerURL,
            username: request.username,
            password: request.password,
            allowLocalHTTP: request.allowLocalHTTP
        ))
    }

    private func beginPlayback(_ intent: DulcetPlaybackQueueIntent) {
        guard let playbackController else { return }
        playbackController.replaceQueueAndPlay(intent)
        receivePlaybackPresentation(playbackController.currentPresentation, selectNowPlaying: true)
    }

    private func receivePlaybackPresentation(
        _ presentation: DulcetPlaybackPresentation,
        selectNowPlaying: Bool = false
    ) {
        let destination = selectNowPlaying ? .nowPlaying : currentSnapshot.selectedDestination
        let state: DulcetPresentationState
        if destination == .nowPlaying {
            state = presentation.status == .ready && presentation.nowPlaying != nil
                ? .nowPlaying
                : .nowPlayingUnavailable
        } else {
            state = currentSnapshot.state
        }
        publish(
            state: state,
            destination: destination,
            form: currentSnapshot.accountForm,
            status: currentSnapshot.accountConnection,
            musicFolders: currentSnapshot.musicFolders,
            artists: currentSnapshot.artists,
            albums: currentSnapshot.albums,
            selectedAlbum: currentSnapshot.selectedAlbum,
            libraryFailure: currentSnapshot.libraryFailure,
            nowPlaying: presentation.nowPlaying
        )
    }

    private func dismissAccountRemovalFailure() {
        guard accountRemovalStatus == .failed,
              case .connected = currentSnapshot.accountConnection else { return }
        accountRemovalStatus = .idle
        publishAccountRemovalState(
            state: .accountConnected,
            status: currentSnapshot.accountConnection
        )
    }

    private func publishAccountRemovalState(
        state: DulcetPresentationState,
        status: DulcetAccountConnectionStatus
    ) {
        publish(
            state: state,
            destination: .settings,
            form: currentSnapshot.accountForm,
            status: status,
            musicFolders: currentSnapshot.musicFolders,
            artists: currentSnapshot.artists,
            albums: currentSnapshot.albums,
            selectedAlbum: currentSnapshot.selectedAlbum,
            libraryFailure: currentSnapshot.libraryFailure
        )
    }
}

extension DulcetAccountDataSource: DulcetArtworkLoading {
    public func loadArtwork(
        _ reference: DulcetArtworkReference,
        sizeBucket: DulcetArtworkSizeBucket,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> (any DulcetArtworkFetchOperation)? {
        guard let artworkFetcher,
              reference.serverID == providerInstanceID,
              case let .connected(account) = currentSnapshot.accountConnection else {
            completion(.unavailable)
            return nil
        }
        let form = currentSnapshot.accountForm
        return artworkFetcher.fetch(DulcetArtworkFetchRequest(
            reference: reference,
            sizeBucket: sizeBucket,
            normalizedServerURL: account.normalizedServerURL,
            username: form.username,
            password: form.password,
            allowLocalHTTP: form.allowLocalHTTP
        ), completion: completion)
    }

    private func publishSavedAccountOrIdle(form: DulcetAccountConnectRequest) {
        if let savedServerName {
            publish(
                state: .accountSavedDisconnected,
                destination: .settings,
                form: form,
                status: .saved(serverName: savedServerName)
            )
        } else {
            publish(
                state: .accountConnectIdle,
                destination: .settings,
                form: form,
                status: .idle
            )
        }
    }

    private static func savedServerName(for serverURL: String) -> String {
        let trimmed = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let host = components.host,
              !host.isEmpty else {
            return trimmed
        }
        if let port = components.port {
            return "\(host):\(port)"
        }
        return host
    }
}

private extension DulcetPresentationState {
    var accountStateOrIdle: DulcetPresentationState {
        switch self {
        case .accountConnectIdle, .accountConnecting, .accountConnected,
             .accountRemoving, .accountRemovalError, .accountSavedDisconnected,
             .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            self
        case .emptyLibraryNoAccount, .emptyLibraryConnected, .libraryLoading, .libraryError,
             .libraryBrowse, .albumDetailMultiDisc, .nowPlaying, .nowPlayingUnavailable,
             .searchIdle, .searchLoading, .searchResults, .searchEmpty, .searchError,
             .tlsUntrusted, .offlineMetadataOnly:
            .accountConnectIdle
        }
    }
}

private extension String {
    var trimmedForSearch: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private extension DulcetSearchResultKind {
    static let allCases: [Self] = [.track, .album, .artist]
}

private extension DulcetSearchPage {
    func hasMore(for kind: DulcetSearchResultKind) -> Bool {
        switch kind {
        case .track: trackHasMore
        case .album: albumHasMore
        case .artist: artistHasMore
        }
    }
}

private extension DulcetAccountErrorFamily {
    var presentationState: DulcetPresentationState {
        switch self {
        case .input: .accountErrorInput
        case .transport: .accountErrorTransport
        case .security: .accountErrorSecurity
        case .protocol: .accountErrorProtocol
        case .server: .accountErrorServer
        case .authentication: .accountErrorAuthentication
        case .capability: .accountErrorCapability
        case .persistence: .accountErrorPersistence
        }
    }
}
