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
        return DulcetiOSProductionComposition(
            store: makeStore(credentialStore: credentialStore, downloads: downloads),
            downloads: downloads
        )
    }
    #endif

    private static func makeStore(
        credentialStore: DulcetKeychainCredentialStore,
        downloads: (any DulcetDownloadControlling)?
    ) -> DulcetPresentationStore {
        DulcetPresentationStore(
            source: DulcetAccountDataSource(
                connector: DulcetCoreAccountConnector(),
                credentialStore: credentialStore,
                libraryBrowser: DulcetCoreLibraryBrowser(),
                artworkFetcher: DulcetCoreArtworkFetcher(),
                serverSearch: DulcetCoreServerSearch(),
                playbackController: DulcetCorePlaybackController(
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
}
#endif

@MainActor
final class DulcetCoreLibraryBrowser: DulcetLibraryBrowsing {
    private let client = AppleLibraryBrowseClient()

    func browse(
        _ request: DulcetLibraryBrowseRequest,
        completion: @escaping @MainActor (DulcetLibraryBrowseOutcome) -> Void
    ) -> any DulcetLibraryBrowseOperation {
        let coreRequest = AppleLibraryBrowseRequest(
            providerInstanceId: request.providerInstanceID,
            normalizedBaseUrl: request.normalizedServerURL,
            username: request.username,
            password: request.password,
            allowLocalHttp: request.allowLocalHTTP
        )
        let operation = client.startBrowse(request: coreRequest) { outcome in
            if let snapshot = outcome.snapshot {
                completion(.loaded(
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
                    albums: snapshot.albums.map(Self.copyAlbum)
                ))
                return
            }
            guard let error = outcome.error else {
                preconditionFailure("A library outcome must carry a snapshot or a closed error")
            }
            if error.kind == "cancelled" {
                completion(.cancelled)
                return
            }
            guard let kind = DulcetLibraryFailureKind(rawValue: error.kind) else {
                preconditionFailure("The core exported an unmapped library error kind")
            }
            completion(.failed(DulcetLibraryFailure(kind: kind)))
        }
        return DulcetCoreLibraryOperation(operation: operation)
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
            }
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
    private let operation: any AppleLibraryBrowseOperation

    init(operation: any AppleLibraryBrowseOperation) {
        self.operation = operation
    }

    func cancel() {
        operation.cancel()
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
