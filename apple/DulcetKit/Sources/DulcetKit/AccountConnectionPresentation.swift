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

@MainActor
public protocol DulcetCommittedLibraryBrowsing: AnyObject {
    func browseCommitted(
        providerInstanceID: String,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation
}

@MainActor
public protocol DulcetLibraryRefreshOperation: AnyObject {
    func cancel()
}

@MainActor
public protocol DulcetLibraryRefreshScheduling: AnyObject {
    func schedule(
        after delay: Duration,
        action: @escaping @MainActor () -> Void
    ) -> any DulcetLibraryRefreshOperation
}

@MainActor
public final class DulcetMonotonicLibraryRefreshScheduler: DulcetLibraryRefreshScheduling {
    public init() {}

    public func schedule(
        after delay: Duration,
        action: @escaping @MainActor () -> Void
    ) -> any DulcetLibraryRefreshOperation {
        DulcetTaskLibraryRefreshOperation(delay: delay, action: action)
    }
}

@MainActor
private final class DulcetTaskLibraryRefreshOperation: DulcetLibraryRefreshOperation {
    private var task: Task<Void, Never>?

    init(delay: Duration, action: @escaping @MainActor () -> Void) {
        task = Task {
            do {
                try await ContinuousClock().sleep(for: delay)
                guard !Task.isCancelled else { return }
                action()
            } catch {
                // Cancellation is the only expected failure for the monotonic cadence sleep.
            }
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
    }
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

private enum DulcetLibrarySelection {
    case album(DulcetProviderItemID)
    case artist(DulcetProviderItemID)
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

/// Immediate read of the active account's committed cache; never performs network I/O.
@MainActor
public protocol DulcetLocalSearching: AnyObject {
    func searchCommitted(providerInstanceID: String, query: String) -> DulcetSearchPageOutcome
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
    private let downloadController: (any DulcetDownloadControlling)?
    private let accountRemovalTimeout: Duration
    private let searchDebounce: Duration
    private let libraryRefreshCadence: Duration
    private let libraryRefreshScheduler: any DulcetLibraryRefreshScheduling
    private let providerInstanceIDFactory: @MainActor () -> String
    private var snapshotHandler: (@MainActor (DulcetSnapshot) -> Void)?
    private var activeOperation: (any DulcetAccountConnectOperation)?
    private var activeLibraryOperation: (any DulcetLibraryBrowseOperation)?
    private var activeSearchOperation: (any DulcetSearchOperation)?
    private var searchDebounceTask: Task<Void, Never>?
    private var accountRemovalTask: Task<Void, Never>?
    private var accountRemovalWatchdog: Task<Void, Never>?
    private var accountRemovalID: UUID?
    private var libraryRefreshOperation: (any DulcetLibraryRefreshOperation)?
    private var generation = 0
    private var libraryGeneration = 0
    private var searchGeneration = 0
    private var providerInstanceID: String?
    private var accountRemovalStatus: DulcetAccountRemovalStatus = .idle
    private var savedServerName: String?
    private var searchQuery = ""
    private var searchResults: [DulcetSearchResult] = []
    private var initialServerPageLoaded = false
    private var serverResultCounts: [DulcetSearchResultKind: Int] = [:]
    private var searchHasMoreKinds: Set<DulcetSearchResultKind> = []
    private var searchLoadingMoreKind: DulcetSearchResultKind?
    private var localSearchFailure: DulcetSearchFailure?
    private var serverSearchFailure: DulcetSearchFailure?
    private var searchFailure: DulcetSearchFailure? { serverSearchFailure ?? localSearchFailure }
    private var libraryMusicFolders: [DulcetMusicFolder] = []
    private var libraryArtists: [DulcetArtist] = []
    private var libraryAlbums: [DulcetAlbum] = []

    static let defaultSearchDebounce: Duration = .milliseconds(250)
    private static let searchPageSize = 20

    public private(set) var currentSnapshot: DulcetSnapshot
    public var downloadsEnabled: Bool {
        downloadController?.downloadsEnabled == true
    }

    public init(
        connector: any DulcetAccountConnecting,
        credentialStore: (any DulcetCredentialStoring)? = nil,
        libraryBrowser: (any DulcetLibraryBrowsing)? = nil,
        artworkFetcher: (any DulcetArtworkFetching)? = nil,
        serverSearch: (any DulcetServerSearching)? = nil,
        playbackController: (any DulcetPlaybackControlling)? = nil,
        downloadController: (any DulcetDownloadControlling)? = nil,
        initialRequest: DulcetAccountConnectRequest = .empty,
        searchDebounce: Duration = .milliseconds(250),
        accountRemovalTimeout: Duration = .seconds(30),
        libraryRefreshCadence: Duration = .seconds(86_400),
        libraryRefreshScheduler: any DulcetLibraryRefreshScheduling =
            DulcetMonotonicLibraryRefreshScheduler(),
        providerInstanceIDFactory: @escaping @MainActor () -> String = { UUID().uuidString }
    ) {
        self.connector = connector
        self.credentialStore = credentialStore
        self.libraryBrowser = libraryBrowser
        self.artworkFetcher = artworkFetcher
        self.serverSearch = serverSearch
        self.playbackController = playbackController
        self.downloadController = downloadController
        self.accountRemovalTimeout = accountRemovalTimeout
        self.searchDebounce = searchDebounce
        self.libraryRefreshCadence = libraryRefreshCadence
        self.libraryRefreshScheduler = libraryRefreshScheduler
        self.providerInstanceIDFactory = providerInstanceIDFactory
        providerInstanceID = (credentialStore as? any DulcetProviderInstanceCredentialStoring)?
            .providerInstanceID
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
        downloadController?.setStatusHandler { [weak self] id, state in
            self?.receiveDownloadState(state, for: id)
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
                receivePlaybackPresentation(
                    playbackController?.currentPresentation ?? .unavailable,
                    selectNowPlaying: true
                )
            }
        case let .updateSearchQuery(query):
            updateSearchQuery(query)
        case let .loadMoreSearchResults(kind):
            loadMoreSearchResults(kind)
        case .retrySearch:
            guard currentSnapshot.selectedDestination == .search else { return }
            if searchQuery.trimmedForSearch.count < 2 {
                openSearch()
            } else {
                startInitialSearch(debounce: false, destination: currentSnapshot.selectedDestination)
            }
        case let .activateSearchResult(id):
            activateSearchResult(id)
        case let .selectAlbum(id):
            guard currentSnapshot.selectedDestination == .library,
                  let album = currentSnapshot.albums.first(where: { $0.id == id }) else { return }
            publish(
                state: .albumDetailMultiDisc,
                destination: .library,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection,
                musicFolders: currentSnapshot.musicFolders,
                artists: currentSnapshot.artists,
                albums: currentSnapshot.albums,
                selectedAlbum: album
            )
        case let .playLibrary(shuffle):
            let tracks = currentSnapshot.albums.flatMap(\.tracks) + currentSnapshot.looseTracks
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
        case let .downloadTrack(id):
            guard let track = libraryAlbums.lazy.flatMap(\.tracks).first(where: { $0.id == id }),
                  downloadController?.downloadsEnabled == true else { return }
            downloadController?.requestDownload(track)
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
        cancelLibraryRefresh()
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
                    let instanceID = self.providerInstanceID ?? self.providerInstanceIDFactory()
                    if let credentialStore = self.credentialStore
                        as? any DulcetProviderInstanceCredentialStoring {
                        try credentialStore.save(request, providerInstanceID: instanceID)
                    } else {
                        try self.credentialStore?.save(request)
                    }
                    if self.credentialStore != nil {
                        self.savedServerName = account.serverName
                    }
                    self.providerInstanceID = instanceID
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
        selectedArtist: DulcetArtist? = nil,
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
            selectedArtist: selectedArtist,
            libraryFailure: libraryFailure,
            nowPlaying: nowPlaying,
            searchQuery: searchQuery,
            searchResults: searchResults,
            searchHasMoreKinds: searchHasMoreKinds,
            searchLoadingMoreKind: searchLoadingMoreKind,
            searchFailure: searchFailure,
            searchIncludesLocalCache: libraryBrowser is any DulcetLocalSearching,
            searchFailureIsLocal: serverSearchFailure == nil && localSearchFailure != nil,
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
        selectedArtist: DulcetArtist? = nil,
        libraryFailure: DulcetLibraryFailure? = nil,
        nowPlaying: DulcetNowPlaying? = nil,
        searchQuery: String = "",
        searchResults: [DulcetSearchResult] = [],
        searchHasMoreKinds: Set<DulcetSearchResultKind> = [],
        searchLoadingMoreKind: DulcetSearchResultKind? = nil,
        searchFailure: DulcetSearchFailure? = nil,
        searchIncludesLocalCache: Bool = false,
        searchFailureIsLocal: Bool = false,
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
            selectedArtist: selectedArtist,
            nowPlaying: nowPlaying,
            searchQuery: searchQuery,
            searchResults: searchResults,
            searchHasMoreKinds: searchHasMoreKinds,
            searchLoadingMoreKind: searchLoadingMoreKind,
            searchFailure: searchFailure,
            searchIncludesLocalCache: searchIncludesLocalCache,
            searchFailureIsLocal: searchFailureIsLocal,
            captureDate: Date(timeIntervalSince1970: 0),
            accountForm: form,
            accountConnection: status,
            accountRemoval: accountRemoval,
            libraryFailure: libraryFailure
        )
    }

    private func openLibrary(selecting selection: DulcetLibrarySelection? = nil) {
        cancelLibraryBrowse()
        cancelLibraryRefresh()
        guard case let .connected(account) = currentSnapshot.accountConnection else {
            if let savedServerName,
               let providerInstanceID,
               let committedBrowser = libraryBrowser as? any DulcetCommittedLibraryBrowsing {
                loadCommittedLibrary(
                    from: committedBrowser,
                    providerInstanceID: providerInstanceID,
                    savedServerName: savedServerName,
                    selection: selection
                )
                return
            }
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
        if let selection, presentLibrarySelection(selection) {
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
                self.publishLoadedLibrary(
                    musicFolders: musicFolders,
                    artists: artists,
                    albums: albums,
                    form: form,
                    status: self.currentSnapshot.accountConnection,
                    selection: selection
                )
                self.scheduleLibraryRefresh()
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

    private func loadCommittedLibrary(
        from browser: any DulcetCommittedLibraryBrowsing,
        providerInstanceID: String,
        savedServerName: String,
        selection: DulcetLibrarySelection?
    ) {
        libraryGeneration += 1
        let requestGeneration = libraryGeneration
        let form = currentSnapshot.accountForm
        let status = DulcetAccountConnectionStatus.saved(serverName: savedServerName)
        publish(
            state: .libraryLoading,
            destination: .library,
            form: form,
            status: status
        )
        let operation = browser.browseCommitted(providerInstanceID: providerInstanceID) {
                [weak self] outcome in
            guard let self,
                  self.libraryGeneration == requestGeneration,
                  self.currentSnapshot.selectedDestination == .library else { return }
            self.activeLibraryOperation = nil
            switch outcome {
            case let .loaded(musicFolders, artists, albums):
                self.publishLoadedLibrary(
                    musicFolders: musicFolders,
                    artists: artists,
                    albums: albums,
                    form: form,
                    status: status,
                    selection: selection
                )
            case .failed:
                self.publish(
                    state: .accountSavedDisconnected,
                    destination: .library,
                    form: form,
                    status: status
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

    private func publishLoadedLibrary(
        musicFolders: [DulcetMusicFolder],
        artists: [DulcetArtist],
        albums: [DulcetAlbum],
        form: DulcetAccountConnectRequest,
        status: DulcetAccountConnectionStatus,
        selection: DulcetLibrarySelection?
    ) {
        libraryMusicFolders = musicFolders
        libraryArtists = artists
        libraryAlbums = albums.map { applyingDownloadStates(to: $0) }
        playbackController?.restorePersistedQueue(with: libraryAlbums.flatMap(\.tracks))
        if let selection {
            if presentLibrarySelection(selection) {
                return
            }
            publish(
                state: .libraryError,
                destination: .library,
                form: form,
                status: status,
                libraryFailure: DulcetLibraryFailure(kind: .protocol)
            )
            return
        }
        publish(
            state: albums.isEmpty && artists.isEmpty
                ? .emptyLibraryConnected
                : .libraryBrowse,
            destination: .library,
            form: form,
            status: status,
            musicFolders: musicFolders,
            artists: artists,
            albums: libraryAlbums
        )
    }

    private func scheduleLibraryRefresh() {
        guard case .connected = currentSnapshot.accountConnection else { return }
        libraryRefreshOperation = libraryRefreshScheduler.schedule(
            after: libraryRefreshCadence
        ) { [weak self] in
            guard let self else { return }
            self.libraryRefreshOperation = nil
            if self.currentSnapshot.selectedDestination == .library,
               case .connected = self.currentSnapshot.accountConnection {
                self.openLibrary()
            } else if case .connected = self.currentSnapshot.accountConnection {
                self.scheduleLibraryRefresh()
            }
        }
    }

    private func presentLibrarySelection(_ selection: DulcetLibrarySelection) -> Bool {
        switch selection {
        case let .album(id):
            guard let album = libraryAlbums.first(where: { $0.id == id }) else { return false }
            publish(
                state: .albumDetailMultiDisc,
                destination: .library,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection,
                musicFolders: libraryMusicFolders,
                artists: libraryArtists,
                albums: libraryAlbums,
                selectedAlbum: album
            )
        case let .artist(id):
            guard let artist = libraryArtists.first(where: { $0.id == id }) else { return false }
            publish(
                state: .artistDetail,
                destination: .library,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection,
                musicFolders: libraryMusicFolders,
                artists: libraryArtists,
                albums: libraryAlbums,
                selectedArtist: artist
            )
        }
        return true
    }

    private func cancelLibraryBrowse() {
        libraryGeneration += 1
        let operation = activeLibraryOperation
        activeLibraryOperation = nil
        operation?.cancel()
    }

    private func cancelLibraryRefresh() {
        libraryRefreshOperation?.cancel()
        libraryRefreshOperation = nil
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
           (searchResults.isEmpty || !initialServerPageLoaded),
           serverSearchFailure == nil {
            // currentSnapshot.selectedDestination is still the OLD destination here: this method
            // runs before anything has published the move to .search, so pass the destination we
            // are switching to explicitly instead of letting startInitialSearch() read it back.
            startInitialSearch(debounce: false, destination: .search)
            return
        }
        if searchQuery.trimmedForSearch.count == 1 { loadLocalSearch() }
        let state: DulcetPresentationState = if searchQuery.trimmedForSearch.count < 2 {
            localSearchState
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
        initialServerPageLoaded = false
        serverResultCounts = [:]
        localSearchFailure = nil
        searchHasMoreKinds = []
        searchLoadingMoreKind = nil
        serverSearchFailure = nil
        cancelSearchRequest()
        guard currentSnapshot.selectedDestination == .search else { return }
        guard query.trimmedForSearch.count >= 2 else {
            loadLocalSearch()
            publish(
                state: localSearchState,
                destination: .search,
                form: currentSnapshot.accountForm,
                status: currentSnapshot.accountConnection
            )
            return
        }
        startInitialSearch(debounce: true, destination: currentSnapshot.selectedDestination)
    }

    private var localSearchState: DulcetPresentationState {
        if searchQuery.trimmedForSearch.isEmpty { return .searchIdle }
        if !searchResults.isEmpty { return .searchResults }
        if localSearchFailure != nil { return .searchError }
        return libraryBrowser is any DulcetLocalSearching ? .searchEmpty : .searchIdle
    }

    private func loadLocalSearch() {
        guard !searchQuery.trimmedForSearch.isEmpty,
              case .connected = currentSnapshot.accountConnection,
              let localSearch = libraryBrowser as? any DulcetLocalSearching else { return }
        let instanceID = providerInstanceID ?? providerInstanceIDFactory()
        providerInstanceID = instanceID
        switch localSearch.searchCommitted(providerInstanceID: instanceID, query: searchQuery.trimmedForSearch) {
        case let .loaded(page):
            localSearchFailure = nil
            // This is a complete ranked local snapshot. Callers either have a local-only
            // query or have cleared results before starting the initial server request.
            searchResults = page.results
        case let .failed(failure): localSearchFailure = failure
        case .cancelled: break
        }
    }

    private func startInitialSearch(debounce: Bool, destination: DulcetSidebarDestination) {
        cancelSearchRequest()
        // `destination` is the destination this search is for, supplied explicitly by every
        // caller rather than read back from currentSnapshot.selectedDestination. retrySearch and
        // search-query updates only ever run while already on the search screen, so they pass the
        // current snapshot's destination and this guard is exactly as protective as before.
        // openSearch()'s fast path is different: it can run before the switch to .search has
        // published anything at all, so it passes the destination it is switching TO instead of
        // reading a snapshot that has not caught up yet.
        guard destination == .search,
              case .connected = currentSnapshot.accountConnection,
              searchQuery.trimmedForSearch.count >= 2 else { return }
        searchResults = []
        initialServerPageLoaded = false
        serverResultCounts = [:]
        localSearchFailure = nil
        searchHasMoreKinds = []
        searchLoadingMoreKind = nil
        serverSearchFailure = nil
        loadLocalSearch()
        publish(
            state: searchResults.isEmpty ? .searchLoading : .searchResults,
            destination: destination,
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
        serverSearchFailure = nil
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
            serverSearchFailure = DulcetSearchFailure(kind: .capability)
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
                serverSearchFailure = nil
                for resultKind in DulcetSearchResultKind.allCases where kind == nil || kind == resultKind {
                    let count = switch resultKind {
                    case .artist: page.artistResultCount
                    case .album: page.albumResultCount
                    case .track: page.trackResultCount
                    }
                    serverResultCounts[resultKind, default: 0] += count
                }
                if let kind {
                    appendOrReplace(page.results)
                    setHasMore(page.hasMore(for: kind), for: kind)
                } else {
                    initialServerPageLoaded = true
                    appendOrReplace(page.results)
                    searchHasMoreKinds = Set(DulcetSearchResultKind.allCases.filter(page.hasMore))
                }
                publish(
                    state: searchResults.isEmpty ? .searchEmpty : .searchResults,
                    destination: .search,
                    form: form,
                    status: currentSnapshot.accountConnection
                )
            case let .failed(failure):
                serverSearchFailure = failure
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
        serverResultCounts[kind, default: 0]
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

    private func activateSearchResult(_ id: DulcetProviderItemID) {
        guard currentSnapshot.selectedDestination == .search,
              let result = searchResults.first(where: { $0.id == id }) else { return }
        switch result.kind {
        case .track:
            let tracks = searchResults.compactMap(\.playableTrack)
            guard let startIndex = tracks.firstIndex(where: { $0.id == id }) else { return }
            beginPlayback(DulcetPlaybackQueueIntent(
                tracks: tracks,
                sourceKind: .search,
                sourceID: nil,
                sourceDisplayName: DulcetStrings.search,
                startIndex: startIndex,
                shuffle: false
            ))
        case .album:
            cancelSearchRequest()
            openLibrary(selecting: .album(id))
        case .artist:
            cancelSearchRequest()
            openLibrary(selecting: .artist(id))
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

        generation += 1
        activeOperation?.cancel()
        activeOperation = nil
        cancelLibraryBrowse()
        cancelLibraryRefresh()
        cancelSearchRequest()
        let removedServerID = providerInstanceID
        let cacheRemover = artworkFetcher as? any DulcetArtworkCacheRemoving
        let removalID = UUID()
        accountRemovalID = removalID
        let downloadController = downloadController
        // Independent tasks are intentional: a task group would wait forever for a
        // cleanup adapter that ignores cancellation, even after its timeout won.
        accountRemovalTask = Task { [weak self] in
            await withTaskCancellationHandler {
                let downloadsRemoved = await downloadController?.removeAccountData() ?? true
                // On download timeout, retain artwork with the still-saved account.
                guard !Task.isCancelled else {
                    self?.failAccountRemoval(removalID)
                    return
                }
                guard self?.accountRemovalID == removalID else { return }
                guard downloadsRemoved else {
                    self?.failAccountRemoval(removalID)
                    return
                }
                if let removedServerID, let cacheRemover {
                    await cacheRemover.removeCachedArtwork(serverID: removedServerID)
                }
                guard !Task.isCancelled else {
                    self?.failAccountRemoval(removalID)
                    return
                }
                guard self?.accountRemovalID == removalID else { return }
                guard let self else { return }
                // Delete the credential only after downloads report success and the artwork
                // adapter returns. Artwork deletion is best effort: its Void adapter swallows
                // filesystem errors, so return does not verify that cached files were erased.
                // Before this point, failure/cancellation retains the saved credential; artwork
                // may already be gone if failure occurs after its cleanup attempt.
                do {
                    try self.credentialStore?.delete()
                } catch {
                    self.failAccountRemoval(removalID)
                    return
                }
                self.finishAccountRemoval()
            } onCancel: { [weak self] in
                Task { @MainActor [weak self] in
                    self?.failAccountRemoval(removalID)
                }
            }
        }
        accountRemovalWatchdog = Task { [weak self, accountRemovalTimeout] in
            do {
                try await Task.sleep(for: accountRemovalTimeout)
            } catch {
                return
            }
            self?.failAccountRemoval(removalID)
        }
    }

    /// Cancels presentation-owned cleanup without waiting for adapter cooperation.
    /// Internal cancellation boundary; no presentation action exposes it yet.
    func cancelAccountRemoval() {
        accountRemovalTask?.cancel()
    }

    private func failAccountRemoval(_ removalID: UUID) {
        guard accountRemovalID == removalID else { return }
        accountRemovalID = nil
        accountRemovalWatchdog?.cancel()
        accountRemovalWatchdog = nil
        accountRemovalTask?.cancel()
        accountRemovalTask = nil
        // Successful download cleanup clears its configuration before artwork/credential work.
        // Every recovery path must restore it, including watchdog and explicit cancellation.
        // configure also advances the download generation before a stale continuation resumes.
        if case let .connected(account) = self.currentSnapshot.accountConnection {
            self.configureDownloads(account: account, request: self.currentSnapshot.accountForm)
        }
        accountRemovalStatus = .failed
        publishAccountRemovalState(
            state: .accountRemovalError,
            status: currentSnapshot.accountConnection
        )
    }

    private func finishAccountRemoval() {
        accountRemovalID = nil
        accountRemovalWatchdog?.cancel()
        accountRemovalWatchdog = nil
        accountRemovalTask = nil
        providerInstanceID = nil
        savedServerName = nil
        playbackController?.disconnect()
        libraryMusicFolders = []
        libraryArtists = []
        libraryAlbums = []
        searchQuery = ""
        searchResults = []
        initialServerPageLoaded = false
        serverResultCounts = [:]
        localSearchFailure = nil
        searchHasMoreKinds = []
        searchLoadingMoreKind = nil
        serverSearchFailure = nil
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
            allowLocalHTTP: request.allowLocalHTTP,
            credentialGeneration: credentialStore?.credentialGeneration ?? 0
        ))
        configureDownloads(account: account, request: request)
    }

    private func configureDownloads(
        account: DulcetConnectedAccountSummary,
        request: DulcetAccountConnectRequest
    ) {
        guard let providerInstanceID else { return }
        downloadController?.configure(account: DulcetPlaybackAccount(
            providerInstanceID: providerInstanceID,
            normalizedServerURL: account.normalizedServerURL,
            username: request.username,
            password: request.password,
            allowLocalHTTP: request.allowLocalHTTP,
            credentialGeneration: credentialStore?.credentialGeneration ?? 0
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
            state = switch presentation.status {
            case .unavailable:
                .nowPlayingUnavailable
            case .preparing:
                .nowPlayingPreparing
            case .ready:
                presentation.nowPlaying == nil ? .nowPlayingFailed : .nowPlaying
            case .failed:
                .nowPlayingFailed
            }
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
            nowPlaying: presentation.status == .ready ? presentation.nowPlaying : nil
        )
    }

    private func applyingDownloadStates(to album: DulcetAlbum) -> DulcetAlbum {
        guard let downloadController else { return album }
        return album.replacingTracks(album.tracks.map { track in
            track.replacingDownloadState(downloadController.status(for: track.id))
        })
    }

    private func receiveDownloadState(
        _ state: DulcetDownloadState,
        for id: DulcetProviderItemID
    ) {
        libraryAlbums = libraryAlbums.map { album in
            album.replacingTracks(album.tracks.map { track in
                track.id == id ? track.replacingDownloadState(state) : track
            })
        }
        let selectedAlbum = currentSnapshot.selectedAlbum.flatMap { selected in
            libraryAlbums.first(where: { $0.id == selected.id })
        }
        publish(
            state: currentSnapshot.state,
            destination: currentSnapshot.selectedDestination,
            form: currentSnapshot.accountForm,
            status: currentSnapshot.accountConnection,
            musicFolders: currentSnapshot.musicFolders,
            artists: currentSnapshot.artists,
            albums: libraryAlbums,
            selectedAlbum: selectedAlbum,
            selectedArtist: currentSnapshot.selectedArtist,
            libraryFailure: currentSnapshot.libraryFailure,
            nowPlaying: currentSnapshot.nowPlaying
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
        case .accountConnectIdle, .accountConnectEmpty, .accountConnecting, .accountConnected,
             .accountRemoving, .accountRemovalError, .accountSavedDisconnected,
             .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            self
        case .emptyLibraryNoAccount, .emptyLibraryConnected, .libraryLoading, .libraryError,
             .libraryBrowse, .albumDetailMultiDisc, .artistDetail,
             .nowPlaying, .nowPlayingPreparing,
             .nowPlayingFailed, .nowPlayingUnavailable,
             .searchIdle, .searchLoading, .searchResults, .searchEmpty, .searchError,
             .tlsUntrusted, .tlsUntrustedPopulatedForm, .offlineMetadataOnly:
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
