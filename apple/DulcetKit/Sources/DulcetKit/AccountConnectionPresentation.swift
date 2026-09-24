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
        case .localNetworkAccessDenied:
            (
                "Allow Dulcet to find devices on your local network",
                "Your server is on your local network, and Dulcet does not have permission to reach it yet.",
                "Turn on Local Network for Dulcet in Settings. Dulcet connects as soon as access is allowed."
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
    /// A fast, incomplete first paint. Albums carry no track lists, and the SAME operation will
    /// deliver again. A preview is deliberately not a `.loaded`: everything a caller does once a
    /// library open has finished — releasing the operation, starting the refresh cadence — is
    /// wrong to do while the authoritative read is still in flight.
    case preview(
        musicFolders: [DulcetMusicFolder],
        artists: [DulcetArtist],
        albums: [DulcetAlbum]
    )
    /// The authoritative result of this open. Nothing further is delivered for it.
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

public enum DulcetAlbumTracksOutcome: Sendable {
    case loaded([DulcetTrack])
    case failed(DulcetLibraryFailure)
    case cancelled
}

/// Reading one album's track list. Separate from `DulcetLibraryBrowsing` because a browser that
/// serves a committed local snapshot already has every track and never needs it.
@MainActor
public protocol DulcetAlbumTracksLoading: AnyObject {
    func loadAlbumTracks(
        _ request: DulcetLibraryBrowseRequest,
        albumRawID: String,
        completion: @escaping @MainActor (DulcetAlbumTracksOutcome) -> Void
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
    /// Raw server rows, before deduplication. Advance each cursor in the same units as hasMore.
    public let artistConsumedRowCount: Int
    public let albumConsumedRowCount: Int
    public let trackConsumedRowCount: Int
    public let artistHasMore: Bool
    public let albumHasMore: Bool
    public let trackHasMore: Bool

    public init(
        results: [DulcetSearchResult],
        artistResultCount: Int,
        albumResultCount: Int,
        trackResultCount: Int,
        artistConsumedRowCount: Int,
        albumConsumedRowCount: Int,
        trackConsumedRowCount: Int,
        artistHasMore: Bool,
        albumHasMore: Bool,
        trackHasMore: Bool
    ) {
        self.results = results
        self.artistResultCount = artistResultCount
        self.albumResultCount = albumResultCount
        self.trackResultCount = trackResultCount
        self.artistConsumedRowCount = artistConsumedRowCount
        self.albumConsumedRowCount = albumConsumedRowCount
        self.trackConsumedRowCount = trackConsumedRowCount
        self.artistHasMore = artistHasMore
        self.albumHasMore = albumHasMore
        self.trackHasMore = trackHasMore
    }
}

/// One core search page's per-kind counts, named and typed exactly as the exported core page DTO
/// declares them.
///
/// The app target conforms that DTO with an empty extension, so no hand-written line there can
/// cross one kind's count into another's; the only mapping is `DulcetSearchPage.init(results:counts:)`,
/// which the package tests drive directly. The DTO itself has no initializer visible to Swift, so a
/// mapping written against it could not be tested at all.
public protocol DulcetSearchPageCounts {
    var artistResultCount: Int32 { get }
    var albumResultCount: Int32 { get }
    var trackResultCount: Int32 { get }
    var artistConsumedRowCount: Int32 { get }
    var albumConsumedRowCount: Int32 { get }
    var trackConsumedRowCount: Int32 { get }
    var artistHasMore: Bool { get }
    var albumHasMore: Bool { get }
    var trackHasMore: Bool { get }
}

extension DulcetSearchPage {
    public init(results: [DulcetSearchResult], counts: some DulcetSearchPageCounts) {
        self.init(
            results: results,
            artistResultCount: Int(counts.artistResultCount),
            albumResultCount: Int(counts.albumResultCount),
            trackResultCount: Int(counts.trackResultCount),
            artistConsumedRowCount: Int(counts.artistConsumedRowCount),
            albumConsumedRowCount: Int(counts.albumConsumedRowCount),
            trackConsumedRowCount: Int(counts.trackConsumedRowCount),
            artistHasMore: counts.artistHasMore,
            albumHasMore: counts.albumHasMore,
            trackHasMore: counts.trackHasMore
        )
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
    private let localNetworkAccess: (any DulcetLocalNetworkAccessProbing)?
    /// Watching for local-network access to be granted after a connection it blocked.
    private var localNetworkWatch: (any DulcetLocalNetworkAccessWatch)?
    /// Identifies the live watch, so an answer from one that was cancelled is never acted on.
    private var localNetworkWatchID: UUID?
    /// The connection the privacy check refused, while that refusal stands and the watch waits
    /// for a grant to retry it.
    private var localNetworkRefusedRequest: DulcetAccountConnectRequest?
    /// Set when a grant has already retried the connection the person asked for, so a server
    /// that stays unreachable is reported rather than retried again and again.
    private var localNetworkRetryUsed = false
    private var libraryGeneration = 0
    private var searchGeneration = 0
    private var providerInstanceID: String?
    private var accountRemovalStatus: DulcetAccountRemovalStatus = .idle
    private var savedServerName: String?
    private var searchQuery = ""
    private var searchResults: [DulcetSearchResult] = []
    private var searchConsumedRows: [DulcetSearchResultKind: Int] = [:]
    private var searchHasMoreKinds: Set<DulcetSearchResultKind> = []
    private var searchLoadingMoreKind: DulcetSearchResultKind?
    private var searchFailure: DulcetSearchFailure?
    private var libraryMusicFolders: [DulcetMusicFolder] = []
    private var libraryArtists: [DulcetArtist] = []
    private var libraryAlbums: [DulcetAlbum] = []
    private var activeAlbumTracksOperation: (any DulcetLibraryBrowseOperation)?
    private var albumTracksGeneration = 0
    private var selectedAlbumTracksFailure: DulcetLibraryFailure?
    /// Whether the library currently held was read to completion for the current connection.
    private var libraryReadCompleted = false
    /// Queue edits the playback controller refused, carried on every snapshot.
    private var refusedQueueEdits = 0
    /// What starting playback does to the surface the person is on.
    private let playbackStartNavigation: DulcetPlaybackStartNavigation
    /// The playback controller's latest presentation. Every publication carries it, so the
    /// now-playing state survives navigation instead of being dropped by whichever publication
    /// happened not to pass it along — the persistent mini-player and the macOS Playback menu
    /// both read it from every snapshot, whatever the destination.
    private var latestPlaybackPresentation: DulcetPlaybackPresentation = .unavailable

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
        providerInstanceIDFactory: @escaping @MainActor () -> String = { UUID().uuidString },
        playbackStartNavigation: DulcetPlaybackStartNavigation = .platformDefault,
        localNetworkAccess: (any DulcetLocalNetworkAccessProbing)? = nil
    ) {
        self.connector = connector
        self.localNetworkAccess = localNetworkAccess
        self.playbackStartNavigation = playbackStartNavigation
        latestPlaybackPresentation = playbackController?.currentPresentation ?? .unavailable
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
            // A keystroke is not answered with a snapshot: republishing would put the field's
            // previous value back under the person's cursor.
            if case .editAccountForm = action { return }
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
                    state: currentSnapshot.accountConnection == .connecting
                        ? .accountConnecting
                        : currentSnapshot.state.accountStateOrIdle,
                    destination: .settings,
                    form: currentSnapshot.accountForm,
                    status: currentSnapshot.accountConnection
                )
            case .library:
                cancelSearchRequest()
                openLibrary(reason: .entered)
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
            startInitialSearch(debounce: false, destination: currentSnapshot.selectedDestination)
        case let .activateSearchResult(id):
            activateSearchResult(id)
        case let .selectAlbum(id):
            guard currentSnapshot.selectedDestination == .library,
                  libraryAlbums.contains(where: { $0.id == id }) else { return }
            presentAlbum(id, loadingTracks: true)
        case .retryAlbumTracks:
            guard let album = currentSnapshot.selectedAlbum else { return }
            presentAlbum(album.id, loadingTracks: true)
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
        case let .showAlbum(id):
            // Navigation, not a reason to re-read: the same path an album search result takes.
            cancelSearchRequest()
            openLibrary(reason: .entered, selecting: .album(id))
        case let .showArtist(id):
            cancelSearchRequest()
            openLibrary(reason: .entered, selecting: .artist(id))
        case let .editQueue(intent):
            guard let editor = playbackController as? any DulcetQueueEditing else { return }
            if !editor.edit(intent) {
                // Said out loud on the next publication: a swipe or a drag that changed nothing
                // must not look as if it worked.
                refusedQueueEdits += 1
                receivePlaybackPresentation(latestPlaybackPresentation)
            }
        case .reportRefusedQueueEdit:
            refusedQueueEdits += 1
            receivePlaybackPresentation(latestPlaybackPresentation)
        case let .editAccountForm(form):
            accountFormEdited(form)
        case let .submitAccountConnection(request):
            localNetworkRetryUsed = false
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
        cancelLocalNetworkWatch()
        generation += 1
        let operation = activeOperation
        activeOperation = nil
        operation?.cancel()
        publishSavedAccountOrIdle(form: currentSnapshot.accountForm)
    }

    /// Connects with `request`. `inPlace` is a connection nobody asked to watch -- the automatic
    /// retry after local-network access was granted while the person was somewhere else -- so it
    /// never moves them, wherever they go before it lands: the account status says it is
    /// connecting (Connection shows that, with Cancel, if they go there), and the outcome is
    /// recorded on whatever surface they are on. That is decided here, when the retry is made,
    /// and not re-decided from where the person happens to be when it completes.
    private func submit(_ request: DulcetAccountConnectRequest, inPlace: Bool = false) {
        cancelLibraryBrowse()
        cancelLibraryRefresh()
        // Whatever is held describes the connection being replaced, so it stops being an answer
        // even if this submission is for the same server.
        libraryReadCompleted = false
        // Where the person was when they asked to connect. Reconnect is reachable from the library
        // surface now, and sending them to settings on success answers a request they did not make:
        // they pressed Reconnect on the library screen to see their library.
        let origin = currentSnapshot.selectedDestination
        cancelLocalNetworkWatch()
        generation += 1
        let submissionGeneration = generation
        let supersededOperation = activeOperation
        activeOperation = nil
        supersededOperation?.cancel()
        if inPlace {
            // The refusal it answers no longer applies; the status says what is happening now.
            publishInPlace(status: .connecting, form: request)
        } else {
            publish(state: .accountConnecting, form: request, status: .connecting)
        }

        let operation = connector.connect(request) { [weak self] outcome in
            guard let self, self.generation == submissionGeneration else { return }
            self.activeOperation = nil
            if inPlace {
                self.completeInPlace(outcome, request: request)
                return
            }
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
                        // A connection was just established, so anything held is another session's.
                        self.openLibrary(reason: .connected)
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
            case let .failed(failure) where self.localNetworkAccess != nil
                && (failure.kind == .transportUnreachable || failure.kind == .transportTimeout):
                // An unreachable local server may only be unreachable because the system has not
                // been given -- or has not yet been asked for -- local-network access. Ask the
                // system before blaming the server; the spinner stays up while it answers.
                self.resolveLocalNetworkAccess(
                    after: failure,
                    request: request,
                    generation: submissionGeneration
                )
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
           inPlace || currentSnapshot.state == .accountConnecting {
            activeOperation = operation
        }
    }

    /// The outcome of a connection made in place, recorded where the person is. Success opens
    /// the library or search they are looking at with the new connection; a failure is kept on
    /// the account status, which Settings shows the next time they go there -- or at once, when
    /// that is where they are.
    private func completeInPlace(
        _ outcome: DulcetAccountConnectOutcome,
        request: DulcetAccountConnectRequest
    ) {
        switch outcome {
        case let .connected(account):
            do {
                let instanceID = providerInstanceID ?? providerInstanceIDFactory()
                if let credentialStore = credentialStore as? any DulcetProviderInstanceCredentialStoring {
                    try credentialStore.save(request, providerInstanceID: instanceID)
                } else {
                    try credentialStore?.save(request)
                }
                if credentialStore != nil {
                    savedServerName = account.serverName
                }
                providerInstanceID = instanceID
                configurePlayback(account: account, request: request)
                publishInPlace(status: .connected(account), form: request)
                switch currentSnapshot.selectedDestination {
                case .library:
                    openLibrary(reason: .connected)
                case .search:
                    openSearch()
                case .nowPlaying, .settings:
                    break
                }
            } catch {
                publishInPlace(
                    status: .failed(DulcetAccountErrorPresenter.presentation(
                        for: DulcetAccountErrorContext(
                            kind: .credentialPersistenceFailed,
                            serverName: account.serverName
                        )
                    )),
                    form: request
                )
            }
        case let .failed(failure) where failure.kind == .transportCancelled:
            break
        case let .failed(failure):
            publishInPlace(status: .failed(failure), form: request)
        }
    }

    /// Republishes the surface that is showing with a new account status, moving nothing. On
    /// Settings the state is the status's own, so Connection shows what the status says.
    private func publishInPlace(
        status: DulcetAccountConnectionStatus,
        form: DulcetAccountConnectRequest
    ) {
        publish(
            state: currentSnapshot.selectedDestination == .settings
                ? status.accountPresentationState
                : currentSnapshot.state,
            destination: currentSnapshot.selectedDestination,
            form: form,
            status: status,
            musicFolders: currentSnapshot.musicFolders,
            artists: currentSnapshot.artists,
            albums: currentSnapshot.albums,
            selectedAlbum: currentSnapshot.selectedAlbum,
            selectedArtist: currentSnapshot.selectedArtist,
            libraryFailure: currentSnapshot.libraryFailure,
            selectedAlbumTracksFailure: currentSnapshot.selectedAlbumTracksFailure
        )
    }

    private func resolveLocalNetworkAccess(
        after failure: DulcetAccountFailurePresentation,
        request: DulcetAccountConnectRequest,
        generation submissionGeneration: Int
    ) {
        guard let localNetworkAccess else { return }
        var answered = false
        let watchID = UUID()
        localNetworkWatchID = watchID
        localNetworkWatch = localNetworkAccess.watch(serverURL: request.serverURL) { [weak self] access in
            guard let self,
                  self.generation == submissionGeneration,
                  self.localNetworkWatchID == watchID else { return }
            defer { answered = true }
            switch access {
            case .denied:
                self.localNetworkRefusedRequest = request
                self.publishLocalNetworkAnswer(
                    DulcetAccountErrorPresenter.presentation(for: DulcetAccountErrorContext(
                        kind: .localNetworkAccessDenied,
                        serverName: failure.serverName
                    )),
                    request: request
                )
            case .notDenied where !answered:
                // Privacy was never the obstacle: the original failure stands.
                self.endLocalNetworkWatch()
                self.publishLocalNetworkAnswer(failure, request: request)
            case .notDenied:
                // Access was granted after it blocked the connection, so retry it once, as the
                // person would have to. This watch is still live, so the refusal it reported
                // still stands: anything that ends it -- another connection, Cancel, removing
                // the account, editing what the refused connection would send -- cancels the
                // watch. What the status shows is not asked, because opening a saved account's
                // library replaces the refusal there with "saved", and that person is waiting
                // for this connection.
                // Visibly only while they are still on the explanation: someone who has gone to
                // Library or Search in the meantime is connected where they are, not taken back
                // to the Connection screen to watch a spinner.
                self.endLocalNetworkWatch()
                guard !self.localNetworkRetryUsed else { return }
                self.localNetworkRetryUsed = true
                self.submit(
                    request,
                    inPlace: self.currentSnapshot.selectedDestination != .settings
                )
            }
        }
    }

    /// An answer from the privacy check, recorded where the person is. The spinner was waiting
    /// on it, and it can take as long as the person takes to answer the system's prompt, so
    /// someone who has gone to Library or Search meanwhile is not taken back to Connection:
    /// Connection shows it when they come to it.
    private func publishLocalNetworkAnswer(
        _ answer: DulcetAccountFailurePresentation,
        request: DulcetAccountConnectRequest
    ) {
        guard currentSnapshot.selectedDestination == .settings else {
            publishInPlace(status: .failed(answer), form: request)
            return
        }
        publish(
            state: answer.kind.family.presentationState,
            destination: .settings,
            form: request,
            status: .failed(answer)
        )
    }

    /// Editing what the refused connection would send -- the address above all, but any field
    /// the retry would send and then write back into the form -- ends the refusal. The
    /// automatic retry would otherwise connect with the old values and replace the edit with
    /// them. The account status stops promising a connection that will no longer be made, and
    /// the edit is kept.
    private func accountFormEdited(_ form: DulcetAccountConnectRequest) {
        guard let refused = localNetworkRefusedRequest, form != refused else { return }
        cancelLocalNetworkWatch()
        publishInPlace(status: savedServerName.map { .saved(serverName: $0) } ?? .idle, form: form)
    }

    private func endLocalNetworkWatch() {
        localNetworkWatch = nil
        localNetworkWatchID = nil
        localNetworkRefusedRequest = nil
    }

    private func cancelLocalNetworkWatch() {
        localNetworkWatch?.cancel()
        endLocalNetworkWatch()
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
        selectedAlbumTracksFailure: DulcetLibraryFailure? = nil
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
            playback: latestPlaybackPresentation,
            selectedAlbumTracksFailure: selectedAlbumTracksFailure,
            searchQuery: searchQuery,
            searchResults: searchResults,
            searchHasMoreKinds: searchHasMoreKinds,
            searchLoadingMoreKind: searchLoadingMoreKind,
            searchFailure: searchFailure,
            accountRemoval: accountRemovalStatus,
            refusedQueueEdits: refusedQueueEdits
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
        playback: DulcetPlaybackPresentation = .unavailable,
        selectedAlbumTracksFailure: DulcetLibraryFailure? = nil,
        searchQuery: String = "",
        searchResults: [DulcetSearchResult] = [],
        searchHasMoreKinds: Set<DulcetSearchResultKind> = [],
        searchLoadingMoreKind: DulcetSearchResultKind? = nil,
        searchFailure: DulcetSearchFailure? = nil,
        accountRemoval: DulcetAccountRemovalStatus = .idle,
        refusedQueueEdits: Int = 0
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
            // Only a ready presentation carries a now-playing value into the snapshot: the menu
            // commands and the Now Playing surface both key off its presence.
            nowPlaying: playback.status == .ready ? playback.nowPlaying : nil,
            playbackStatus: playback.status,
            playbackFailure: playback.failure,
            refusedQueueEdits: refusedQueueEdits,
            playbackSkipNotice: playback.skipNotice,
            searchQuery: searchQuery,
            searchResults: searchResults,
            searchHasMoreKinds: searchHasMoreKinds,
            searchLoadingMoreKind: searchLoadingMoreKind,
            searchFailure: searchFailure,
            captureDate: Date(timeIntervalSince1970: 0),
            accountForm: form,
            accountConnection: status,
            accountRemoval: accountRemoval,
            libraryFailure: libraryFailure,
            selectedAlbumTracksFailure: selectedAlbumTracksFailure
        )
    }

    /// Why the library surface is being opened. Only one of these is a reason to contact the
    /// server again, which is the whole point of naming them: re-entering a screen is not an
    /// event about the library, it is an event about navigation.
    private enum DulcetLibraryOpenReason {
        /// The person navigated to the library, or activated a search result that lives in it.
        /// Whatever is already held is still the answer.
        case entered
        /// A connection was just established. Anything held describes a different session.
        case connected
        /// The refresh cadence elapsed. Reading is what the cadence is for.
        case refresh
    }

    /// The read policy, in one place because the previous behaviour was not a policy — it was the
    /// absence of one, and it re-read the whole library on every tap of the Library button.
    ///
    /// A read happens when there is nothing held (first entry, or the held data was invalidated),
    /// when the previous attempt failed (the error surface's Try Again re-enters), when a
    /// connection is established, and when the cadence elapses. It does NOT happen because
    /// somebody came back to the screen, and it does not happen because somebody re-entered a
    /// screen that is *already reading*. Staleness is already the cadence's job; adding a second,
    /// implicit staleness rule keyed on navigation would mean the library is re-read as often as
    /// the person happens to tab around, which is not a property anyone chose.
    ///
    /// 🚨 **Initiation is guarded here, not just publication.** `libraryGeneration` stops a
    /// superseded read from *publishing*, which is a different question from whether a second read
    /// should have *started*. One open costs a preview walk plus a sync that walks every stage
    /// twice — 57 requests against a 2,498-album / 4,996-track server, measured by execution in
    /// `LibraryFirstPaintCostTest`.
    ///
    /// ⚠️ **The request count is no longer the argument, and the correctness is.** Before the
    /// whole-library enumeration transport landed, the same duplicate open cost about 5,023
    /// requests, and saving those was reason enough on its own. It is now 57, so what this guard
    /// is actually worth is what it was always worth underneath the arithmetic: a read that has
    /// already painted is not thrown away, and the grid the person is looking at is not replaced
    /// by a spinner. Do not re-derive the value of this guard from the request count.
    private func openLibrary(
        reason: DulcetLibraryOpenReason,
        selecting selection: DulcetLibrarySelection? = nil
    ) {
        if reason == .entered, currentSnapshot.accountConnection.isConnected {
            // Deliberately before the cancels below: the refresh cadence measures time since the
            // last full read and must keep running across navigation, and a read that is still
            // running must not be cancelled by the act of arriving at the screen it is reading for.
            //
            // Held data answers navigation only when it can actually answer it. A search can
            // return an album the last read did not include — the server has it and we simply
            // have not looked since — so an unsatisfiable selection falls through and reads
            // rather than dropping the person on the grid they did not ask for.
            if libraryReadCompleted {
                if let selection {
                    if presentLibrarySelection(selection) { return }
                } else {
                    republishHeldLibrary()
                    return
                }
            } else if activeLibraryOperation != nil,
                      currentSnapshot.selectedDestination == .library {
                // A read is already running, on the screen the person is already on — so this is
                // not a navigation event at all, and the read it would start is the one already in
                // flight. The window is the gap between the preview painting and the sync
                // committing, and `selectDestination(.library)` is this surface's ONLY way back out
                // of an album (see `republishHeldLibrary`), so the person walks into it by using
                // the app normally. Falling through here cancelled that read and began another,
                // which replaced the grid the person was looking at with a spinner.
                //
                // How LONG that window is, is a property of the sync's transport and not of this
                // file: the enumeration walks shortened it by roughly two orders of magnitude in
                // request count. It is narrower than it was, and it is not zero — nothing here may
                // assume a duration, which is why the guard is on the operation being live rather
                // than on any elapsed time.
                //
                // A *selection* is still a real request: it names something the in-flight read was
                // not asked to select and may not carry, so an unsatisfiable one reads, exactly as
                // it does once a read has completed.
                if let selection {
                    if presentLibrarySelection(selection) { return }
                } else {
                    // Leaving an album detail ends interest in its track list, which is what the
                    // cancel below would otherwise have done.
                    cancelAlbumTracks()
                    republishHeldLibrary(whenEmpty: .libraryLoading)
                    return
                }
            }
        }
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
                    status: savedAccountStatus(serverName: savedServerName)
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
        // A read that has started has not finished. If it is cancelled — by leaving the screen,
        // reconnecting, or removing the account — re-entering must read again rather than redraw
        // a half-answered library.
        libraryReadCompleted = false
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
            switch outcome {
            case let .preview(musicFolders, artists, albums):
                // An ending is final. `.preview` makes "a preview is not an ending"
                // unrepresentable; this is the other half. A preview delivered after this open's
                // authoritative result would replace a complete library with a track-less one —
                // measured against the real data source as `areTracksLoaded` true->false,
                // `tracks` 1->0 and restoration coverage `.wholeLibrary`->`.partial`. The
                // producer happens not to do that today, but ordering across two HTTP clients is
                // not something a consumer can assume, so it is enforced here.
                guard !self.libraryReadCompleted else { return }
                // The operation is deliberately NOT released and the refresh cadence is
                // deliberately NOT started: the authoritative read is still running. Releasing it
                // would leave a sync nothing can cancel, and starting the cadence here would
                // measure from first paint rather than from the last completed read — so a sync
                // slower than the cadence would have a refresh fired on top of it.
                self.publishLoadedLibrary(
                    musicFolders: musicFolders,
                    artists: artists,
                    albums: albums,
                    form: form,
                    status: self.currentSnapshot.accountConnection,
                    selection: selection
                )
            case let .loaded(musicFolders, artists, albums):
                self.activeLibraryOperation = nil
                self.libraryReadCompleted = true
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
                self.activeLibraryOperation = nil
                self.publish(
                    state: .libraryError,
                    destination: .library,
                    form: form,
                    status: self.currentSnapshot.accountConnection,
                    libraryFailure: failure
                )
            case .cancelled:
                self.activeLibraryOperation = nil
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
        let status = savedAccountStatus(serverName: savedServerName)
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
            // Reading what is held changes nothing about the account, so the status is left as
            // it now stands: a connection running in place may have landed -- or failed -- while
            // the read ran, and what it said then is not to be overwritten by what was true
            // when the read began. Connecting lands by opening the library again, which moves
            // the generation on, so a read that reaches here never overwrites a connection.
            let status = self.currentSnapshot.accountConnection
            switch outcome {
            case let .preview(musicFolders, artists, albums):
                // A committed read is answered from the local database in one step, so this
                // cannot happen. It is handled as what `.preview` MEANS rather than folded in
                // with `.loaded`: the operation is not released and the read is not recorded as
                // complete. Conflating the two here was how the handle came to be released
                // before the case that decides whether releasing it is correct.
                self.publishLoadedLibrary(
                    musicFolders: musicFolders,
                    artists: artists,
                    albums: albums,
                    form: form,
                    status: status,
                    selection: selection
                )
            case let .loaded(musicFolders, artists, albums):
                self.activeLibraryOperation = nil
                self.libraryReadCompleted = true
                self.publishLoadedLibrary(
                    musicFolders: musicFolders,
                    artists: artists,
                    albums: albums,
                    form: form,
                    status: status,
                    selection: selection
                )
            case .failed:
                self.activeLibraryOperation = nil
                self.publish(
                    state: .accountSavedDisconnected,
                    destination: .library,
                    form: form,
                    status: status
                )
            case .cancelled:
                self.activeLibraryOperation = nil
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
        cancelAlbumTracks()
        selectedAlbumTracksFailure = nil
        // The catalog is whatever tracks are known right now, which after a first paint is
        // nothing. The controller decides whether that covers the saved queue; handing it a
        // catalog that does not is how a saved playback position gets thrown away.
        playbackController?.restorePersistedQueue(
            with: libraryAlbums.flatMap(\.tracks),
            catalogCoverage: libraryCatalogCoverage
        )
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
        // A library open publishes more than once, and the later publication can land while the
        // person is reading an album or an artist. Put them back where they were rather than
        // throwing them out to the grid.
        if selection == nil, currentSnapshot.selectedDestination == .library {
            if currentSnapshot.state == .albumDetailMultiDisc,
               let id = currentSnapshot.selectedAlbum?.id,
               libraryAlbums.contains(where: { $0.id == id }) {
                presentAlbum(id, loadingTracks: true)
                return
            }
            if currentSnapshot.state == .artistDetail,
               let id = currentSnapshot.selectedArtist?.id,
               libraryArtists.contains(where: { $0.id == id }) {
                _ = presentLibrarySelection(.artist(id))
                return
            }
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

    /// Called only from a FINAL publication, so the cadence measures time since the library was
    /// last read in full — never time since a preview painted the grid.
    private func scheduleLibraryRefresh() {
        guard case .connected = currentSnapshot.accountConnection else { return }
        libraryRefreshOperation = libraryRefreshScheduler.schedule(
            after: libraryRefreshCadence
        ) { [weak self] in
            guard let self else { return }
            self.libraryRefreshOperation = nil
            if self.currentSnapshot.selectedDestination == .library,
               case .connected = self.currentSnapshot.accountConnection {
                self.openLibrary(reason: .refresh)
            } else if case .connected = self.currentSnapshot.accountConnection {
                self.scheduleLibraryRefresh()
            }
        }
    }

    private func presentLibrarySelection(_ selection: DulcetLibrarySelection) -> Bool {
        switch selection {
        case let .album(id):
            guard libraryAlbums.contains(where: { $0.id == id }) else { return false }
            presentAlbum(id, loadingTracks: true)
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

    /// Shows one album, and reads its track list if nobody has yet. The grid draws from the
    /// album list alone, so an album arrives here with `areTracksLoaded == false` and an empty
    /// `tracks` — the detail view shows its own loading row until this completes.
    private func presentAlbum(_ id: DulcetProviderItemID, loadingTracks: Bool) {
        cancelAlbumTracks()
        selectedAlbumTracksFailure = nil
        guard let album = libraryAlbums.first(where: { $0.id == id }) else { return }
        publishAlbumDetail(album)
        guard loadingTracks, !album.areTracksLoaded else { return }
        guard let loader = libraryBrowser as? any DulcetAlbumTracksLoading,
              case let .connected(account) = currentSnapshot.accountConnection else { return }
        albumTracksGeneration += 1
        let requestGeneration = albumTracksGeneration
        let form = currentSnapshot.accountForm
        let operation = loader.loadAlbumTracks(
            DulcetLibraryBrowseRequest(
                providerInstanceID: providerInstanceID ?? providerInstanceIDFactory(),
                normalizedServerURL: account.normalizedServerURL,
                username: form.username,
                password: form.password,
                allowLocalHTTP: form.allowLocalHTTP
            ),
            albumRawID: id.rawID
        ) { [weak self] outcome in
            guard let self, self.albumTracksGeneration == requestGeneration else { return }
            self.activeAlbumTracksOperation = nil
            switch outcome {
            case let .loaded(tracks):
                self.adoptAlbumTracks(tracks, for: id)
            case let .failed(failure):
                self.selectedAlbumTracksFailure = failure
                guard let album = self.libraryAlbums.first(where: { $0.id == id }) else { return }
                self.publishAlbumDetail(album)
            case .cancelled:
                break
            }
        }
        if albumTracksGeneration == requestGeneration {
            activeAlbumTracksOperation = operation
        }
    }

    private func adoptAlbumTracks(_ tracks: [DulcetTrack], for id: DulcetProviderItemID) {
        libraryAlbums = libraryAlbums.map { album in
            album.id == id
                ? applyingDownloadStates(to: album.adoptingLoadedTracks(tracks))
                : album
        }
        guard let album = libraryAlbums.first(where: { $0.id == id }) else { return }
        // A queue saved from this album can now be restored, because its tracks are known.
        playbackController?.restorePersistedQueue(
            with: libraryAlbums.flatMap(\.tracks),
            catalogCoverage: libraryCatalogCoverage
        )
        guard currentSnapshot.state == .albumDetailMultiDisc,
              currentSnapshot.selectedAlbum?.id == id else { return }
        publishAlbumDetail(album)
    }

    private func publishAlbumDetail(_ album: DulcetAlbum) {
        publish(
            state: .albumDetailMultiDisc,
            destination: .library,
            form: currentSnapshot.accountForm,
            status: currentSnapshot.accountConnection,
            musicFolders: libraryMusicFolders,
            artists: libraryArtists,
            albums: libraryAlbums,
            selectedAlbum: album,
            selectedAlbumTracksFailure: selectedAlbumTracksFailure
        )
    }

    /// A catalog assembled from albums that have not all been read cannot say an entry is gone.
    private var libraryCatalogCoverage: DulcetLibraryCatalogCoverage {
        libraryAlbums.allSatisfy(\.areTracksLoaded) ? .wholeLibrary : .partial
    }

    /// Redraws the library already held, without contacting the server.
    ///
    /// This deliberately publishes the GRID rather than restoring whichever album or artist was
    /// last open. `selectDestination(.library)` is also the only way back out of an album on this
    /// surface — there is no separate back action — so restoring the detail here would leave the
    /// grid unreachable.
    ///
    /// [emptyState] is what "nothing held" means to the caller. After a completed read it means
    /// the server has no library; while a read is still running it means nothing has arrived yet,
    /// and publishing an empty library over a read in progress would be a false answer.
    private func republishHeldLibrary(
        whenEmpty emptyState: DulcetPresentationState = .emptyLibraryConnected
    ) {
        publish(
            state: libraryAlbums.isEmpty && libraryArtists.isEmpty
                ? emptyState
                : .libraryBrowse,
            destination: .library,
            form: currentSnapshot.accountForm,
            status: currentSnapshot.accountConnection,
            musicFolders: libraryMusicFolders,
            artists: libraryArtists,
            albums: libraryAlbums
        )
    }

    private func cancelAlbumTracks() {
        albumTracksGeneration += 1
        let operation = activeAlbumTracksOperation
        activeAlbumTracksOperation = nil
        operation?.cancel()
    }

    private func cancelLibraryBrowse() {
        libraryGeneration += 1
        let operation = activeLibraryOperation
        activeLibraryOperation = nil
        operation?.cancel()
        cancelAlbumTracks()
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
           searchResults.isEmpty,
           searchFailure == nil {
            // currentSnapshot.selectedDestination is still the OLD destination here: this method
            // runs before anything has published the move to .search, so pass the destination we
            // are switching to explicitly instead of letting startInitialSearch() read it back.
            startInitialSearch(debounce: false, destination: .search)
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
        searchConsumedRows = [:]
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
        startInitialSearch(debounce: true, destination: currentSnapshot.selectedDestination)
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
        searchConsumedRows = [:]
        searchHasMoreKinds = []
        searchLoadingMoreKind = nil
        searchFailure = nil
        publish(
            state: .searchLoading,
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
            artistOffset: kind == .artist ? searchConsumedRows[.artist, default: 0] : 0,
            albumCount: kind == nil || kind == .album ? Self.searchPageSize : 0,
            albumOffset: kind == .album ? searchConsumedRows[.album, default: 0] : 0,
            trackCount: kind == nil || kind == .track ? Self.searchPageSize : 0,
            trackOffset: kind == .track ? searchConsumedRows[.track, default: 0] : 0
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
                    searchConsumedRows[kind, default: 0] += page.consumedRowCount(for: kind)
                    appendOrReplace(page.results)
                    setHasMore(page.hasMore(for: kind), for: kind)
                } else {
                    for resultKind in DulcetSearchResultKind.allCases {
                        searchConsumedRows[resultKind] = page.consumedRowCount(for: resultKind)
                    }
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
            // Showing one album from a search result is navigation, not a reason to re-read.
            openLibrary(reason: .entered, selecting: .album(id))
        case .artist:
            cancelSearchRequest()
            openLibrary(reason: .entered, selecting: .artist(id))
        }
    }

    private func removeAccount() {
        cancelLocalNetworkWatch()
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
        libraryReadCompleted = false
        searchQuery = ""
        searchResults = []
        searchConsumedRows = [:]
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
        receivePlaybackPresentation(
            playbackController.currentPresentation,
            selectNowPlaying: playbackStartNavigation == .showNowPlaying
        )
    }

    private func receivePlaybackPresentation(
        _ presentation: DulcetPlaybackPresentation,
        selectNowPlaying: Bool = false
    ) {
        latestPlaybackPresentation = presentation
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
            // A playback update lands on whatever surface is showing. Leaving these out
            // emptied an artist page and dropped an album's track-list failure on every tick.
            selectedArtist: currentSnapshot.selectedArtist,
            libraryFailure: currentSnapshot.libraryFailure,
            selectedAlbumTracksFailure: currentSnapshot.selectedAlbumTracksFailure
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
            selectedAlbumTracksFailure: currentSnapshot.selectedAlbumTracksFailure
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

extension DulcetAccountDataSource: DulcetLibraryNavigating {
    /// Resolves against the library read that is held, never against the network: a link is
    /// offered only for an artist the library already lists, so following it cannot start a read
    /// that ends on an error page.
    public func libraryArtistID(for credit: DulcetCredit) -> DulcetProviderItemID? {
        if let id = credit.id, libraryArtists.contains(where: { $0.id == id }) {
            return id
        }
        let named = libraryArtists.filter { $0.name == credit.name }
        return named.count == 1 ? named[0].id : nil
    }

    /// A track carries its album's title, not its album's identity. It resolves only when the
    /// answer is unambiguous: an album already holding the track, or exactly one album with that
    /// title — narrowed by shared artist names when titles repeat. "Greatest Hits" by two artists
    /// resolves to neither rather than to the wrong one.
    public func libraryAlbumID(for track: DulcetTrack) -> DulcetProviderItemID? {
        if let holding = libraryAlbums.first(where: { album in
            album.tracks.contains(where: { $0.id == track.id })
        }) {
            return holding.id
        }
        guard let title = track.albumTitle, !title.isEmpty else { return nil }
        let titled = libraryAlbums.filter { $0.title == title }
        if titled.count == 1 { return titled[0].id }
        let artists = Set(track.credits.map(\.name))
        let credited = titled.filter { album in
            !artists.isDisjoint(with: album.credits.map(\.name))
        }
        return credited.count == 1 ? credited[0].id : nil
    }

    public var queueEditingEnabled: Bool {
        playbackController is any DulcetQueueEditing
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

    /// A saved account's status on a surface that reads it without connecting: saved -- unless a
    /// connection to it is running where the person is, the retry after a local-network grant,
    /// which the status goes on saying until it lands, so Connection still shows it with Cancel.
    private func savedAccountStatus(serverName: String) -> DulcetAccountConnectionStatus {
        activeOperation != nil && currentSnapshot.accountConnection == .connecting
            ? .connecting
            : .saved(serverName: serverName)
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

private extension DulcetAccountConnectionStatus {
    /// The Connection surface's state for this status, as a submission publishes it.
    var accountPresentationState: DulcetPresentationState {
        switch self {
        case .idle: .accountConnectIdle
        case .saved: .accountSavedDisconnected
        case .connecting: .accountConnecting
        case .connected: .accountConnected
        case let .failed(failure): failure.kind.family.presentationState
        }
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
    func consumedRowCount(for kind: DulcetSearchResultKind) -> Int {
        switch kind {
        case .track: trackConsumedRowCount
        case .album: albumConsumedRowCount
        case .artist: artistConsumedRowCount
        }
    }

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
