import DulcetCore
import DulcetKit
import Foundation

/// Shared production account composition for every touch-capable Apple shell.
@MainActor
enum DulcetAppleProduction {
    static func makePresentationStore() -> DulcetPresentationStore {
        DulcetPresentationStore(
            source: DulcetAccountDataSource(
                connector: DulcetCoreAccountConnector(),
                credentialStore: DulcetKeychainCredentialStore(),
                libraryBrowser: DulcetCoreLibraryBrowser()
            )
        )
    }
}

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
            artwork: placeholderArtwork(for: album.rawId),
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
                    mediaSourceID: track.mediaSourceId,
                    artwork: placeholderArtwork(for: album.rawId)
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

    private static func placeholderArtwork(for rawID: String) -> DulcetArtwork {
        let palettes = DulcetArtworkPalette.allCases
        let index = rawID.unicodeScalars.reduce(0) { ($0 + Int($1.value)) % palettes.count }
        return DulcetArtwork(seed: rawID, palette: palettes[index])
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
