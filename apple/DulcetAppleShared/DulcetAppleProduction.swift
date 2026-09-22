import DulcetCore
import DulcetKit
import Foundation
import ImageIO

/// Shared production account composition for every native Apple shell.
@MainActor
enum DulcetAppleProduction {
    static func makePresentationStore() -> DulcetPresentationStore {
        #if os(macOS)
        return makeMacComposition().store
        #elseif os(iOS)
        return makeIOSComposition().store
        #else
        let credentialStore = DulcetKeychainCredentialStore()
        let downloads: (any DulcetDownloadControlling)? = nil
        return makeStore(credentialStore: credentialStore, downloads: downloads)
        #endif
    }

    #if os(macOS)
    static func makeMacComposition() -> DulcetMacProductionComposition {
        let credentialStore = DulcetKeychainCredentialStore()
        let downloads = DulcetCoreDownloadController.production()
        return DulcetMacProductionComposition(
            store: makeStore(credentialStore: credentialStore, downloads: downloads),
            downloads: downloads
        )
    }
    #endif

    #if os(iOS)
    static func makeIOSComposition() -> DulcetiOSProductionComposition {
        let credentialStore = DulcetKeychainCredentialStore()
        let downloads = DulcetCoreDownloadController.production()
        let playbackController = DulcetCorePlaybackController(downloadController: downloads)
        return DulcetiOSProductionComposition(
            store: makeStore(
                credentialStore: credentialStore,
                downloads: downloads,
                playbackController: playbackController
            ),
            downloads: downloads,
            playbackController: playbackController
        )
    }
    #endif

    private static func makeStore(
        credentialStore: DulcetKeychainCredentialStore,
        downloads: (any DulcetDownloadControlling)?,
        playbackController: DulcetCorePlaybackController? = nil
    ) -> DulcetPresentationStore {
        DulcetPresentationStore(
            source: DulcetAccountDataSource(
                connector: DulcetCoreAccountConnector(),
                credentialStore: credentialStore,
                libraryBrowser: DulcetCoreLibraryBrowser(),
                artworkFetcher: DulcetCoreArtworkFetcher(),
                serverSearch: DulcetCoreServerSearch(),
                playbackController: playbackController ?? DulcetCorePlaybackController(
                    downloadController: downloads
                ),
                downloadController: downloads,
                providerInstanceIDFactory: {
                    credentialStore.activeAccountID ?? UUID().uuidString
                }
            )
        )
    }
}

#if os(macOS)
@MainActor
struct DulcetMacProductionComposition {
    let store: DulcetPresentationStore
    let downloads: DulcetCoreDownloadController?
}
#endif

#if os(iOS)
@MainActor
struct DulcetiOSProductionComposition {
    let store: DulcetPresentationStore
    let downloads: DulcetCoreDownloadController?
    /// The same controller the store drives, exposed so the shell's debug hooks can observe
    /// scrobble delivery without a presentation field the product never needs.
    let playbackController: DulcetCorePlaybackController
}
#endif

/**
 The library the person sees, and the sync that keeps it durable — deliberately not the same read.

 Opening the library used to wait for a whole `LibrarySync` pass: folders, artists, album pages, a
 `getAlbum` per album, playlists, starred, genres, and a stability re-walk of each stage. At the
 design's target scale that is thousands of requests, and nothing was drawn until the last one
 answered. So `browse` now runs two things at once and publishes whichever is ready:

 - a **preview** from `AppleLibraryBrowseClient`, which is a constant handful of requests and
   carries every field the album grid draws. Track lists arrive per album, on open.
 - the **sync**, unchanged, which commits a generation and then publishes the committed snapshot
   with every track.

 The completion is therefore called **more than once** — the preview first when it wins, then the
 committed snapshot. A preview *failure* is never delivered: only the sync's outcome can put the
 library into an error state, so a preview that cannot reach the server does not flash an error
 over a sync that is still working.
 */
@MainActor
final class DulcetCoreLibraryBrowser: DulcetLibraryBrowsing, DulcetCommittedLibraryBrowsing,
    DulcetAlbumTracksLoading {
    private let client: AppleLibrarySyncClient
    private let previewClient = AppleLibraryBrowseClient()

    private(set) var startedSyncCount = 0
    private(set) var completedSyncGenerations: [Int64] = []
    private(set) var displayedCommittedGenerations: [Int64] = []
    private(set) var deliveredPreviewCount = 0
    /// `"preview"` / `"committed"`, in delivery order. A test that only checks the committed
    /// result passes whether or not the preview ever ran, which is the whole point of it.
    private(set) var publicationOrder: [String] = []
    /// The album order the preview published, so a proof can require it to match the committed
    /// order rather than leaving the two free to disagree unobserved.
    private(set) var previewAlbumOrder: [String] = []

    init(databaseName: String = "dulcet.db") {
        client = AppleLibrarySyncClient(
            databaseName: databaseName,
            maximumInFlightPerServer: 4
        )
    }

    func loadAlbumTracks(
        _ request: DulcetLibraryBrowseRequest,
        albumRawID: String,
        completion: @escaping @MainActor (DulcetAlbumTracksOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        let operation = previewClient.startAlbumTracks(
            request: request.coreBrowseRequest,
            albumRawId: albumRawID
        ) { outcome in
            if let album = outcome.album {
                completion(.loaded(Self.copyAlbum(album).tracks))
                return
            }
            guard let kind = outcome.error?.kind else {
                preconditionFailure("An album track outcome must carry an album or an error")
            }
            if kind == "cancelled" {
                completion(.cancelled)
                return
            }
            guard let failure = Self.failureKind(kind) else {
                preconditionFailure("The core exported an unmapped library error kind")
            }
            completion(.failed(DulcetLibraryFailure(kind: failure)))
        }
        return DulcetCoreBrowseOperation(operation: operation)
    }

    func browse(
        _ request: DulcetLibraryBrowseRequest,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        startedSyncCount += 1
        var syncFinished = false
        var previewDelivered = false
        // A preview FAILURE is deliberately dropped: only the sync can put the library into an
        // error state, so a preview that cannot reach the server never flashes an error over a
        // sync that is still working.
        let preview = previewClient.startBrowse(request: request.coreBrowseRequest) {
            [weak self] outcome in
            guard let self, !syncFinished, let snapshot = outcome.snapshot else { return }
            previewDelivered = true
            self.deliveredPreviewCount += 1
            self.publicationOrder.append("preview")
            let outcome = Self.previewOutcome(snapshot)
            if case let .preview(_, _, albums) = outcome {
                self.previewAlbumOrder = albums.map(\.id.rawID)
            }
            completion(outcome)
        }
        let coreRequest = AppleLibrarySyncRequest(
            providerInstanceId: request.providerInstanceID,
            normalizedBaseUrl: request.normalizedServerURL,
            username: request.username,
            password: request.password,
            allowLocalHttp: request.allowLocalHTTP
        )
        let operation = client.startSync(
            request: coreRequest,
            restart: false,
            progress: { _ in }
        ) { [weak self] outcome in
            guard let self else { return }
            syncFinished = true
            preview.cancel()
            if let success = outcome.success {
                self.completedSyncGenerations.append(success.generation)
                self.completeFromCommitted(
                    providerInstanceID: request.providerInstanceID,
                    requiredGeneration: success.generation,
                    fallbackErrorKind: nil,
                    completion: completion
                )
                return
            }
            guard let error = outcome.error else {
                preconditionFailure("A library sync outcome must carry success or a closed error")
            }
            if error.kind == "cancelled" {
                completion(.cancelled)
                return
            }
            // A preview already put a real, live read of this server on screen. Replacing it with
            // "the library could not be loaded" would discard a good result and describe the
            // screen wrongly, so a sync failure only speaks when nothing else can. When the
            // preview failed too, nothing was delivered and the failure is delivered here.
            self.completeFromCommitted(
                providerInstanceID: request.providerInstanceID,
                requiredGeneration: nil,
                fallbackErrorKind: previewDelivered ? nil : error.kind,
                completion: completion
            )
        }
        return DulcetCoreLibraryPairOperation(sync: operation, preview: preview)
    }

    func browseCommitted(
        providerInstanceID: String,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        completeFromCommitted(
            providerInstanceID: providerInstanceID,
            requiredGeneration: nil,
            fallbackErrorKind: "unreachable",
            completion: completion
        )
        return DulcetCompletedLibraryOperation()
    }

    private func completeFromCommitted(
        providerInstanceID: String,
        requiredGeneration: Int64?,
        fallbackErrorKind: String?,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) {
        let outcome = client.readCommitted(providerInstanceId: providerInstanceID)
        if let snapshot = outcome.snapshot,
           snapshot.generation > 0,
           requiredGeneration == nil || snapshot.generation == requiredGeneration {
            displayedCommittedGenerations.append(snapshot.generation)
            publicationOrder.append("committed")
            completion(Self.copyCommitted(snapshot.library))
            return
        }
        guard let errorKind = outcome.error?.kind ?? fallbackErrorKind else {
            // The caller asked for no failure to be reported, because something better is
            // already on screen.
            return
        }
        guard let kind = Self.failureKind(errorKind) else {
            preconditionFailure("The core exported an unmapped library sync error kind")
        }
        completion(.failed(DulcetLibraryFailure(kind: kind)))
    }

    /// A preview is delivered as `.preview`, never as `.loaded`. The distinction is what tells
    /// the caller that this operation will deliver again — so it keeps the handle that cancels
    /// the still-running sync, and does not start the refresh cadence from a first paint.
    /// The albums keep `areTracksLoaded` as the core reported it, so the UI can tell an album
    /// nobody has read from an album with no tracks.
    private static func previewOutcome(
        _ snapshot: AppleLibraryBrowseSnapshotDto
    ) -> DulcetLibraryBrowseOutcome {
        guard case let .loaded(musicFolders, artists, albums) = copyCommitted(snapshot) else {
            preconditionFailure("copyCommitted always produces a loaded outcome")
        }
        // The grid's order is the CLIENT's, not whichever source answered. The server returns
        // `alphabeticalByName` using its own collation; the committed read returns
        // `ORDER BY title COLLATE NOCASE, raw_id` (Library.sq). Left alone the two disagree, and
        // the grid visibly reshuffles under the reader seconds-to-minutes after it paints —
        // OBSERVED, and invisible until something compared them. The preview adopts the
        // committed order so the later publication cannot move anything.
        return .preview(
            musicFolders: musicFolders,
            artists: artists,
            albums: albums.sorted(by: Self.precedesInLibraryOrder)
        )
    }

    /// `title COLLATE NOCASE, raw_id`, which is what `Library.sq` orders the committed read by.
    /// SQLite's NOCASE folds ASCII only, so the fold here is ASCII-only too — a Unicode-aware
    /// fold would silently disagree on exactly the titles that make ordering visible.
    private static func precedesInLibraryOrder(_ left: DulcetAlbum, _ right: DulcetAlbum) -> Bool {
        let leftTitle = asciiCaseFolded(left.title)
        let rightTitle = asciiCaseFolded(right.title)
        if leftTitle != rightTitle { return leftTitle < rightTitle }
        return left.id.rawID < right.id.rawID
    }

    private static func asciiCaseFolded(_ value: String) -> String {
        String(value.unicodeScalars.map { scalar in
            scalar.value >= 65 && scalar.value <= 90
                ? Character(Unicode.Scalar(scalar.value + 32)!)
                : Character(scalar)
        })
    }

    private static func copyCommitted(
        _ snapshot: AppleLibraryBrowseSnapshotDto
    ) -> DulcetLibraryBrowseOutcome {
        .loaded(
            musicFolders: snapshot.musicFolders.map { folder in
                DulcetMusicFolder(
                    id: DulcetProviderItemID(
                        providerInstanceID: folder.providerInstanceId,
                        rawID: folder.rawId
                    ),
                    name: folder.name
                )
            },
            artists: snapshot.artists.map { artist in
                DulcetArtist(
                    id: DulcetProviderItemID(
                        providerInstanceID: artist.providerInstanceId,
                        rawID: artist.rawId
                    ),
                    name: artist.name,
                    mediaSourceID: artist.mediaSourceId
                )
            },
            albums: snapshot.albums.map(copyAlbum)
        )
    }

    private static func failureKind(_ coreKind: String) -> DulcetLibraryFailureKind? {
        if coreKind == "unsupported" {
            return .capability
        }
        return DulcetLibraryFailureKind(rawValue: coreKind)
    }

    private static func copyAlbum(_ album: AppleLibraryAlbumDto) -> DulcetAlbum {
        DulcetAlbum(
            id: DulcetProviderItemID(
                providerInstanceID: album.providerInstanceId,
                rawID: album.rawId
            ),
            title: album.title,
            credits: album.credits.map(copyCredit),
            year: album.year?.intValue ?? 0,
            duration: .milliseconds(album.durationMilliseconds),
            mediaSourceID: album.mediaSourceId,
            artwork: artwork(
                fallbackSeed: album.rawId,
                providerInstanceID: album.providerInstanceId,
                artworkKey: album.artworkKey
            ),
            tracks: album.tracks.map { track in
                DulcetTrack(
                    id: DulcetProviderItemID(
                        providerInstanceID: track.providerInstanceId,
                        rawID: track.rawId
                    ),
                    title: track.title,
                    credits: track.credits.map(copyCredit),
                    albumTitle: track.albumTitle,
                    discNumber: track.discNumber?.intValue,
                    trackNumber: track.trackNumber?.intValue,
                    duration: .milliseconds(track.durationMilliseconds),
                    sourceContainer: track.sourceContainer.flatMap(DulcetAudioContainer.init(coreName:)),
                    mediaSourceID: track.mediaSourceId,
                    artwork: artwork(
                        fallbackSeed: track.rawId,
                        providerInstanceID: track.providerInstanceId,
                        artworkKey: track.artworkKey ?? album.artworkKey
                    )
                )
            },
            trackCount: Int(album.trackCount),
            areTracksLoaded: album.tracksLoaded
        )
    }

    private static func copyCredit(_ credit: AppleLibraryCreditDto) -> DulcetCredit {
        let role: DulcetCreditRole = credit.role == "AlbumArtist" ? .albumArtist : .artist
        let id: DulcetProviderItemID? = if let providerInstanceID = credit.providerInstanceId,
                    let rawID = credit.rawId {
            DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: rawID)
        } else {
            nil
        }
        return DulcetCredit(role: role, name: credit.name, id: id)
    }

    private static func artwork(
        fallbackSeed: String,
        providerInstanceID: String,
        artworkKey: String?
    ) -> DulcetArtwork {
        let palettes = DulcetArtworkPalette.allCases
        let index = fallbackSeed.unicodeScalars.reduce(0) {
            ($0 + Int($1.value)) % palettes.count
        }
        return DulcetArtwork(
            seed: fallbackSeed,
            palette: palettes[index],
            remoteReference: artworkKey.map {
                DulcetArtworkReference(serverID: providerInstanceID, artworkKey: $0)
            }
        )
    }
}

private extension DulcetAudioContainer {
    init?(coreName: String) {
        switch coreName {
        case "Mp3": self = .mp3
        case "Mp4": self = .mp4
        case "Wav": self = .wav
        case "Flac": self = .flac
        case "Ogg": self = .ogg
        case "AdtsAac": self = .adtsAAC
        default: return nil
        }
    }
}

@MainActor
final class DulcetCoreServerSearch: DulcetServerSearching {
    private let client = AppleSearchClient()

    func search(
        _ request: DulcetSearchPageRequest,
        completion: @escaping @MainActor (DulcetSearchPageOutcome) -> Void
    ) -> any DulcetSearchOperation {
        let coreRequest = AppleSearchPageRequest(
            providerInstanceId: request.providerInstanceID,
            normalizedBaseUrl: request.normalizedServerURL,
            username: request.username,
            password: request.password,
            allowLocalHttp: request.allowLocalHTTP,
            query: request.query,
            artistCount: Int32(request.artistCount),
            artistOffset: Int32(request.artistOffset),
            albumCount: Int32(request.albumCount),
            albumOffset: Int32(request.albumOffset),
            trackCount: Int32(request.trackCount),
            trackOffset: Int32(request.trackOffset)
        )
        let operation = client.startSearch(request: coreRequest) { outcome in
            if let page = outcome.page {
                completion(.loaded(DulcetSearchPage(
                    results: page.results.map(Self.copyResult),
                    artistResultCount: Int(page.artistResultCount),
                    albumResultCount: Int(page.albumResultCount),
                    trackResultCount: Int(page.trackResultCount),
                    artistHasMore: page.artistHasMore,
                    albumHasMore: page.albumHasMore,
                    trackHasMore: page.trackHasMore
                )))
                return
            }
            guard let error = outcome.error else {
                preconditionFailure("A search outcome must carry a page or a closed error")
            }
            if error.kind == "cancelled" {
                completion(.cancelled)
                return
            }
            guard let kind = DulcetSearchFailureKind(rawValue: error.kind) else {
                preconditionFailure("The core exported an unmapped search error kind")
            }
            completion(.failed(DulcetSearchFailure(kind: kind)))
        }
        return DulcetCoreSearchOperation(operation: operation)
    }

    private static func copyResult(_ result: AppleSearchResultItemDto) -> DulcetSearchResult {
        let kind: DulcetSearchResultKind = switch result.type {
        case "Track": .track
        case "Album": .album
        case "Artist": .artist
        default: preconditionFailure("The core exported an unmapped search result type")
        }
        let duration = result.durationMilliseconds.map { value in
            Duration.milliseconds(value.int64Value)
        }
        return DulcetSearchResult(
            id: DulcetProviderItemID(
                providerInstanceID: result.providerInstanceId,
                rawID: result.rawId
            ),
            title: result.title,
            kind: kind,
            credits: result.credits.map(copyCredit),
            albumTitle: result.albumTitle,
            year: result.year?.intValue,
            duration: duration,
            mediaSourceID: result.mediaSourceId,
            artwork: artwork(
                fallbackSeed: result.rawId,
                providerInstanceID: result.providerInstanceId,
                artworkKey: result.artworkKey
            ),
            discNumber: result.discNumber?.intValue,
            trackNumber: result.trackNumber?.intValue,
            sourceContainer: result.sourceContainer.flatMap(DulcetAudioContainer.init(coreName:))
        )
    }

    private static func copyCredit(_ credit: AppleSearchCreditDto) -> DulcetCredit {
        let role: DulcetCreditRole = credit.role == "AlbumArtist" ? .albumArtist : .artist
        let id: DulcetProviderItemID? = if let providerInstanceID = credit.providerInstanceId,
                    let rawID = credit.rawId {
            DulcetProviderItemID(providerInstanceID: providerInstanceID, rawID: rawID)
        } else {
            nil
        }
        return DulcetCredit(role: role, name: credit.name, id: id)
    }

    private static func artwork(
        fallbackSeed: String,
        providerInstanceID: String,
        artworkKey: String?
    ) -> DulcetArtwork {
        let palettes = DulcetArtworkPalette.allCases
        let index = fallbackSeed.unicodeScalars.reduce(0) {
            ($0 + Int($1.value)) % palettes.count
        }
        return DulcetArtwork(
            seed: fallbackSeed,
            palette: palettes[index],
            remoteReference: artworkKey.map {
                DulcetArtworkReference(serverID: providerInstanceID, artworkKey: $0)
            }
        )
    }
}

@MainActor
private final class DulcetCoreSearchOperation: DulcetSearchOperation {
    private let operation: any AppleSearchOperation

    init(operation: any AppleSearchOperation) {
        self.operation = operation
    }

    func cancel() {
        operation.cancel()
    }
}

@MainActor
final class DulcetCoreArtworkFetcher: DulcetArtworkFetching {
    private let client: AppleArtworkFetchClient
    private let cache: DulcetArtworkDiskCache

    init(
        client: AppleArtworkFetchClient = AppleArtworkFetchClient(),
        cache: DulcetArtworkDiskCache = DulcetArtworkDiskCache()
    ) {
        self.client = client
        self.cache = cache
    }

    func fetch(
        _ request: DulcetArtworkFetchRequest,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) -> any DulcetArtworkFetchOperation {
        let operation = DulcetCoreArtworkFetchOperation(
            client: client,
            cache: cache,
            request: request,
            completion: completion
        )
        operation.start()
        return operation
    }
}

extension DulcetCoreArtworkFetcher: DulcetArtworkCacheRemoving {
    func removeCachedArtwork(serverID: String) async {
        await cache.remove(serverID: serverID)
    }
}

@MainActor
private final class DulcetCoreArtworkFetchOperation: DulcetArtworkFetchOperation {
    private let client: AppleArtworkFetchClient
    private let cache: DulcetArtworkDiskCache
    private let request: DulcetArtworkFetchRequest
    private var completion: (@MainActor (DulcetArtworkFetchOutcome) -> Void)?
    private var task: Task<Void, Never>?
    private var coreOperation: (any AppleArtworkFetchOperation)?
    private var cancelled = false

    init(
        client: AppleArtworkFetchClient,
        cache: DulcetArtworkDiskCache,
        request: DulcetArtworkFetchRequest,
        completion: @escaping @MainActor (DulcetArtworkFetchOutcome) -> Void
    ) {
        self.client = client
        self.cache = cache
        self.request = request
        self.completion = completion
    }

    func start() {
        let key = cacheKey
        task = Task { [weak self] in
            guard let self else { return }
            if let cached = await cache.data(for: key) {
                guard !Task.isCancelled, !cancelled else { return }
                if Self.isDecodableImage(cached) {
                    deliver(.loaded(cached))
                    return
                }
                await cache.remove(key)
            }
            guard !Task.isCancelled, !cancelled else { return }
            startNetworkRequest(key: key)
        }
    }

    func cancel() {
        guard !cancelled else { return }
        cancelled = true
        task?.cancel()
        task = nil
        coreOperation?.cancel()
        coreOperation = nil
        completion = nil
    }

    private func startNetworkRequest(key: DulcetArtworkCacheKey) {
        let coreRequest = AppleArtworkFetchRequest(
            providerInstanceId: request.reference.serverID,
            artworkKey: request.reference.artworkKey,
            sizeBucketPixels: Int32(request.sizeBucket.rawValue),
            normalizedBaseUrl: request.normalizedServerURL,
            username: request.username,
            password: request.password,
            allowLocalHttp: request.allowLocalHTTP
        )
        coreOperation = client.startFetch(request: coreRequest) { [weak self] outcome in
            guard let self, !cancelled else { return }
            coreOperation = nil
            if let foundationData = outcome.data {
                let data = foundationData as Data
                guard Self.isDecodableImage(data) else {
                    deliver(.unavailable)
                    return
                }
                task = Task { [weak self] in
                    guard let self else { return }
                    await cache.insert(data, for: key)
                    guard !Task.isCancelled, !cancelled else { return }
                    deliver(.loaded(data))
                }
                return
            }
            if outcome.error?.kind == "cancelled" {
                deliver(.cancelled)
            } else if outcome.error != nil {
                deliver(.failed)
            } else {
                deliver(.unavailable)
            }
        }
    }

    private var cacheKey: DulcetArtworkCacheKey {
        DulcetArtworkCacheKey(
            serverID: request.reference.serverID,
            artworkKey: request.reference.artworkKey,
            sizeBucket: request.sizeBucket
        )
    }

    private static func isDecodableImage(_ data: Data) -> Bool {
        CGImageSourceCreateWithData(data as CFData, nil) != nil
    }

    private func deliver(_ outcome: DulcetArtworkFetchOutcome) {
        guard !cancelled, let completion else { return }
        self.completion = nil
        task = nil
        coreOperation = nil
        completion(outcome)
    }
}

@MainActor
final class DulcetCoreLibraryOperation: DulcetLibraryBrowseOperation {
    private let operation: any AppleLibrarySyncOperation

    init(operation: any AppleLibrarySyncOperation) {
        self.operation = operation
    }

    func cancel() {
        operation.cancel()
    }
}

@MainActor
private final class DulcetCompletedLibraryOperation: DulcetLibraryBrowseOperation {
    func cancel() {}
}

@MainActor
private final class DulcetCoreBrowseOperation: DulcetLibraryBrowseOperation {
    private let operation: any AppleLibraryBrowseOperation

    init(operation: any AppleLibraryBrowseOperation) {
        self.operation = operation
    }

    func cancel() {
        operation.cancel()
    }
}

/// One library open is a preview read and a sync. Cancelling it has to reach both, or a cancelled
/// library open keeps a sync running against the server.
@MainActor
private final class DulcetCoreLibraryPairOperation: DulcetLibraryBrowseOperation {
    private let sync: any AppleLibrarySyncOperation
    private let preview: any AppleLibraryBrowseOperation

    init(sync: any AppleLibrarySyncOperation, preview: any AppleLibraryBrowseOperation) {
        self.sync = sync
        self.preview = preview
    }

    func cancel() {
        preview.cancel()
        sync.cancel()
    }
}

private extension DulcetLibraryBrowseRequest {
    var coreBrowseRequest: AppleLibraryBrowseRequest {
        AppleLibraryBrowseRequest(
            providerInstanceId: providerInstanceID,
            normalizedBaseUrl: normalizedServerURL,
            username: username,
            password: password,
            allowLocalHttp: allowLocalHTTP
        )
    }
}

@MainActor
final class DulcetCoreAccountConnector: DulcetAccountConnecting {
    private let client = AppleAccountConnectionClient()

    func connect(
        _ request: DulcetAccountConnectRequest,
        completion: @escaping @MainActor (DulcetAccountConnectOutcome) -> Void
    ) -> any DulcetAccountConnectOperation {
        let coreRequest = AccountConnectionRequest(
            serverUrl: request.serverURL,
            username: request.username,
            password: request.password,
            allowLocalHttp: request.allowLocalHTTP
        )
        let operation = client.startConnect(request: coreRequest) { outcome in
            if let account = outcome.account {
                let fallbackName = URL(string: account.normalizedBaseUrl)?.host
                    ?? account.normalizedBaseUrl
                completion(.connected(DulcetConnectedAccountSummary(
                    serverName: account.serverType.isEmpty ? fallbackName : account.serverType,
                    normalizedServerURL: account.normalizedBaseUrl
                )))
                return
            }

            guard let error = outcome.errorPresentation else {
                preconditionFailure("A failed account connection must carry its presentation key")
            }
            guard let kind = DulcetAccountFailureKind(rawValue: error.kind) else {
                preconditionFailure("The core exported an unmapped account error kind")
            }
            let context = DulcetAccountErrorContext(
                kind: kind,
                serverName: URL(string: request.serverURL)?.host ?? request.serverURL,
                targetHost: error.targetHost,
                invalidServerURLIsInternationalized:
                    error.invalidServerURLIsInternationalized
            )
            completion(.failed(DulcetAccountErrorPresenter.presentation(for: context)))
        }
        return DulcetCoreAccountOperation(operation: operation)
    }
}

@MainActor
final class DulcetCoreAccountOperation: DulcetAccountConnectOperation {
    private let operation: any AppleAccountConnectOperation

    init(operation: any AppleAccountConnectOperation) {
        self.operation = operation
    }

    func cancel() {
        operation.cancel()
    }
}
