import Foundation

@MainActor
public struct DulcetDeterministicFixture {
    public init() {}

    public func snapshot(for state: DulcetPresentationState) -> DulcetSnapshot {
        let library = Self.library
        let looseTracks = [Self.trackWithoutAlbum]
        let base = BaseSnapshot(
            state: state,
            destination: destination(for: state),
            accountConnected: state != .emptyLibraryNoAccount,
            connectivity: connectivity(for: state),
            albums: state == .emptyLibraryNoAccount ? [] : library,
            looseTracks: state == .emptyLibraryNoAccount ? [] : looseTracks,
            recentlyAddedTracks: state == .emptyLibraryNoAccount
                ? []
                : Self.recentlyAddedTracks(in: library, looseTracks: looseTracks)
        )

        switch state {
        case .accountConnectIdle, .accountConnecting, .accountConnected,
             .accountRemoving, .accountRemovalError,
             .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            return accountSnapshot(for: state)
        case .emptyLibraryNoAccount, .libraryBrowse:
            return base.snapshot()
        case .emptyLibraryConnected, .libraryLoading, .libraryError:
            return base.snapshot()
        case .albumDetailMultiDisc:
            return base.snapshot(selectedAlbum: Self.doubleLines)
        case .nowPlaying:
            return base.snapshot(nowPlaying: Self.nowPlaying)
        case .nowPlayingUnavailable:
            return base.snapshot()
        case .searchIdle:
            return base.snapshot()
        case .searchLoading:
            return base.snapshot(searchQuery: "atlas")
        case .searchResults:
            return base.snapshot(
                searchQuery: "atlas",
                searchResults: Self.searchResults,
                searchHasMoreKinds: [.track, .album]
            )
        case .searchEmpty:
            return base.snapshot(searchQuery: "zzzz")
        case .searchError:
            return base.snapshot(
                searchQuery: "atlas",
                searchFailure: DulcetSearchFailure(kind: .unreachable)
            )
        case .tlsUntrusted:
            return base.snapshot()
        case .offlineMetadataOnly:
            let offlineAlbums = library.map { album in
                DulcetAlbum(
                    id: album.id,
                    title: album.title,
                    credits: album.credits,
                    year: album.year,
                    duration: album.duration,
                    mediaSourceID: album.mediaSourceID,
                    artwork: album.artwork,
                    tracks: album.tracks.map { $0.withAvailability(.metadataOnly) }
                )
            }
            return BaseSnapshot(
                state: state,
                destination: .library,
                accountConnected: true,
                connectivity: .offline(lastSyncedDescription: "Today at 14:28 UTC"),
                albums: offlineAlbums,
                looseTracks: looseTracks.map { $0.withAvailability(.metadataOnly) },
                recentlyAddedTracks: Self.recentlyAddedTracks(
                    in: offlineAlbums,
                    looseTracks: looseTracks.map { $0.withAvailability(.metadataOnly) }
                )
            ).snapshot()
        }
    }

    private func destination(for state: DulcetPresentationState) -> DulcetSidebarDestination {
        switch state {
        case .emptyLibraryNoAccount, .emptyLibraryConnected, .libraryLoading, .libraryError,
             .libraryBrowse, .albumDetailMultiDisc, .offlineMetadataOnly:
            .library
        case .nowPlaying, .nowPlayingUnavailable:
            .nowPlaying
        case .searchIdle, .searchLoading, .searchResults, .searchEmpty, .searchError:
            .search
        case .accountConnectIdle, .accountConnecting, .accountConnected,
             .accountRemoving, .accountRemovalError,
             .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence, .tlsUntrusted:
            .settings
        }
    }

    private func connectivity(for state: DulcetPresentationState) -> DulcetConnectivity {
        switch state {
        case .accountConnected, .accountRemoving, .accountRemovalError:
            .online(serverName: "Listening Room")
        case .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            .connectionFailed(.account(Self.accountFailure(for: state)))
        case .accountConnectIdle, .accountConnecting:
            .unavailable
        case .emptyLibraryNoAccount:
            .unavailable
        case .tlsUntrusted:
            .connectionFailed(.tlsUntrusted(Self.tlsFailure))
        case .offlineMetadataOnly:
            .offline(lastSyncedDescription: "Today at 14:28 UTC")
        default:
            .online(serverName: "Listening Room")
        }
    }

    private func accountSnapshot(for state: DulcetPresentationState) -> DulcetSnapshot {
        let form = DulcetAccountConnectRequest(
            serverURL: "https://music.example.invalid",
            username: "listener",
            password: "fixture-password",
            allowLocalHTTP: false
        )
        let status: DulcetAccountConnectionStatus = switch state {
        case .accountConnectIdle:
            .idle
        case .accountConnecting:
            .connecting
        case .accountConnected, .accountRemoving, .accountRemovalError:
            .connected(DulcetConnectedAccountSummary(
                serverName: "Listening Room",
                normalizedServerURL: "https://music.example.invalid"
            ))
        case .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            .failed(Self.accountFailure(for: state))
        case .emptyLibraryNoAccount, .emptyLibraryConnected, .libraryLoading, .libraryError,
             .libraryBrowse, .albumDetailMultiDisc, .nowPlaying, .nowPlayingUnavailable,
             .searchIdle, .searchLoading, .searchResults, .searchEmpty, .searchError,
             .tlsUntrusted, .offlineMetadataOnly:
            .idle
        }
        let removal: DulcetAccountRemovalStatus = switch state {
        case .accountRemoving: .removing
        case .accountRemovalError: .failed
        default: .idle
        }
        return DulcetSnapshot(
            state: state,
            selectedDestination: .settings,
            accountConnected: state == .accountConnected
                || state == .accountRemoving
                || state == .accountRemovalError,
            connectivity: connectivity(for: state),
            albums: [],
            looseTracks: [],
            recentlyAddedTracks: [],
            captureDate: Self.captureDate,
            accountForm: form,
            accountConnection: status,
            accountRemoval: removal
        )
    }
}

/// Test and capture adapter that keeps scenario selection out of ``DulcetDataSource``.
@MainActor
public final class DulcetDeterministicDataSource: DulcetDataSource {
    private let fixture: DulcetDeterministicFixture
    private var snapshotHandler: (@MainActor (DulcetSnapshot) -> Void)?

    public private(set) var currentSnapshot: DulcetSnapshot

    public init(
        fixture: DulcetDeterministicFixture = DulcetDeterministicFixture(),
        initialState: DulcetPresentationState = .libraryBrowse
    ) {
        self.fixture = fixture
        currentSnapshot = fixture.snapshot(for: initialState)
    }

    public func setSnapshotHandler(
        _ handler: @escaping @MainActor (DulcetSnapshot) -> Void
    ) {
        snapshotHandler = handler
    }

    public func send(_ action: DulcetPresentationAction) {
        switch action {
        case let .selectDestination(destination):
            let state: DulcetPresentationState = switch destination {
            case .library: .libraryBrowse
            case .search: .searchResults
            case .nowPlaying: .nowPlaying
            case .settings: .accountConnectIdle
            }
            currentSnapshot = fixture.snapshot(for: state)
        case let .updateSearchQuery(query):
            currentSnapshot = currentSnapshot.replacingSearchQuery(query)
        case .loadMoreSearchResults, .retrySearch:
            break
        case let .selectAlbum(id):
            if let album = currentSnapshot.albums.first(where: { $0.id == id }) {
                currentSnapshot = fixture.snapshot(for: .albumDetailMultiDisc)
                    .replacingSelectedAlbum(album)
            }
        case let .submitAccountConnection(request):
            currentSnapshot = fixture.snapshot(for: .accountConnecting)
                .replacingAccountForm(request)
        case .cancelAccountConnection:
            currentSnapshot = fixture.snapshot(for: .accountConnectIdle)
        case .removeAccount:
            currentSnapshot = fixture.snapshot(for: .accountRemoving)
        case .dismissAccountRemovalFailure:
            currentSnapshot = fixture.snapshot(for: .accountConnected)
        }
        snapshotHandler?(currentSnapshot)
    }
}

private extension DulcetSnapshot {
    func replacingSelectedAlbum(_ album: DulcetAlbum) -> DulcetSnapshot {
        DulcetSnapshot(
            state: .albumDetailMultiDisc,
            selectedDestination: .library,
            accountConnected: accountConnected,
            connectivity: connectivity,
            albums: albums,
            musicFolders: musicFolders,
            artists: artists,
            looseTracks: looseTracks,
            recentlyAddedTracks: recentlyAddedTracks,
            selectedAlbum: album,
            captureDate: captureDate,
            accountForm: accountForm,
            accountConnection: accountConnection,
            accountRemoval: accountRemoval
        )
    }

    func replacingSearchQuery(_ query: String) -> DulcetSnapshot {
        DulcetSnapshot(
            state: state,
            selectedDestination: selectedDestination,
            accountConnected: accountConnected,
            connectivity: connectivity,
            albums: albums,
            looseTracks: looseTracks,
            recentlyAddedTracks: recentlyAddedTracks,
            selectedAlbum: selectedAlbum,
            nowPlaying: nowPlaying,
            searchQuery: query,
            searchResults: searchResults,
            searchHasMoreKinds: searchHasMoreKinds,
            searchLoadingMoreKind: searchLoadingMoreKind,
            searchFailure: searchFailure,
            captureDate: captureDate,
            accountForm: accountForm,
            accountConnection: accountConnection,
            accountRemoval: accountRemoval
        )
    }

    func replacingAccountForm(_ form: DulcetAccountConnectRequest) -> DulcetSnapshot {
        DulcetSnapshot(
            state: state,
            selectedDestination: selectedDestination,
            accountConnected: accountConnected,
            connectivity: connectivity,
            albums: albums,
            looseTracks: looseTracks,
            recentlyAddedTracks: recentlyAddedTracks,
            selectedAlbum: selectedAlbum,
            nowPlaying: nowPlaying,
            searchQuery: searchQuery,
            searchResults: searchResults,
            searchHasMoreKinds: searchHasMoreKinds,
            searchLoadingMoreKind: searchLoadingMoreKind,
            searchFailure: searchFailure,
            captureDate: captureDate,
            accountForm: form,
            accountConnection: accountConnection,
            accountRemoval: accountRemoval
        )
    }
}

private extension DulcetDeterministicFixture {
    struct BaseSnapshot {
        let state: DulcetPresentationState
        let destination: DulcetSidebarDestination
        let accountConnected: Bool
        let connectivity: DulcetConnectivity
        let albums: [DulcetAlbum]
        let looseTracks: [DulcetTrack]
        let recentlyAddedTracks: [DulcetTrack]

        @MainActor
        func snapshot(
            selectedAlbum: DulcetAlbum? = nil,
            nowPlaying: DulcetNowPlaying? = nil,
            searchQuery: String = "",
            searchResults: [DulcetSearchResult] = [],
            searchHasMoreKinds: Set<DulcetSearchResultKind> = [],
            searchFailure: DulcetSearchFailure? = nil
        ) -> DulcetSnapshot {
            DulcetSnapshot(
                state: state,
                selectedDestination: destination,
                accountConnected: accountConnected,
                connectivity: connectivity,
                albums: albums,
                looseTracks: looseTracks,
                recentlyAddedTracks: recentlyAddedTracks,
                selectedAlbum: selectedAlbum,
                nowPlaying: nowPlaying,
                searchQuery: searchQuery,
                searchResults: searchResults,
                searchHasMoreKinds: searchHasMoreKinds,
                searchFailure: searchFailure,
                captureDate: DulcetDeterministicFixture.captureDate
            )
        }
    }

    static let captureDate: Date = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar.date(from: DateComponents(
            timeZone: calendar.timeZone,
            year: 2026,
            month: 8,
            day: 21,
            hour: 14,
            minute: 32
        ))!
    }()

    static let doubleLinesArtwork = DulcetArtwork(seed: "double-lines", palette: .indigoCoral)
    static let pagingArtwork = DulcetArtwork(seed: "paging-atlas", palette: .mossGold)
    static let unicodeArtwork = DulcetArtwork(seed: "etude-tokyo", palette: .plumIce)
    static let severalArtistsArtwork = DulcetArtwork(seed: "several-artists", palette: .oceanMint)
    static let longTitleArtwork = DulcetArtwork(seed: "long-title", palette: .emberRose)

    static let doubleLines: DulcetAlbum = {
        let tracks = (1...2).flatMap { disc in
            (1...2).map { track in
                DulcetTrack(
                    id: "double-lines-d\(disc)-t\(track)",
                    title: "Disc \(disc) Track \(track)",
                    artistNames: ["Dulcet Fixtures"],
                    albumTitle: "Double Lines",
                    discNumber: disc,
                    trackNumber: track,
                    duration: .seconds(196 + (disc * 13) + (track * 7)),
                    artwork: doubleLinesArtwork,
                    isFavorite: disc == 1 && track == 2
                )
            }
        }
        return DulcetAlbum(
            id: "album-double-lines",
            title: "Double Lines",
            albumArtists: ["Dulcet Fixtures"],
            year: 2026,
            artwork: doubleLinesArtwork,
            tracks: tracks
        )
    }()

    static let pagingAtlas: DulcetAlbum = {
        let tracks = (1...300).map { track in
            DulcetTrack(
                id: String(format: "paging-%03d", track),
                title: String(format: "Paging Track %03d", track),
                artistNames: ["Dulcet Fixtures"],
                albumTitle: "Paging Atlas",
                discNumber: 1,
                trackNumber: track,
                duration: .seconds(15 + (track % 19)),
                artwork: pagingArtwork
            )
        }
        return DulcetAlbum(
            id: "album-paging-atlas",
            title: "Paging Atlas",
            albumArtists: ["Dulcet Fixtures"],
            year: 2026,
            artwork: pagingArtwork,
            tracks: tracks
        )
    }()

    static let unicodeAlbum = album(
        id: "album-etudes-between-stations",
        title: "Études Between Stations",
        artists: ["Aiko Laurent"],
        year: 2025,
        artwork: unicodeArtwork,
        titles: ["Étude 東京", "Après la pluie", "Lueur № 7", "Faint Signals"]
    )

    static let severalAlbumArtists = DulcetAlbum(
        id: "album-several-artists",
        title: "Several Album Artists",
        albumArtists: ["Alpha Ensemble", "Beta Ensemble", "Gamma Ensemble"],
        year: 2024,
        artwork: severalArtistsArtwork,
        tracks: [
            DulcetTrack(
                id: "shared-credit",
                title: "Shared Credit",
                artistNames: ["Alpha Ensemble", "Beta Ensemble", "Gamma Ensemble"],
                albumTitle: "Several Album Artists",
                trackNumber: 1,
                duration: .seconds(243),
                artwork: severalArtistsArtwork
            )
        ]
    )

    static let longTitleAlbum = DulcetAlbum(
        id: "album-long-titles",
        title: "Long Titles",
        albumArtists: ["Dulcet Fixtures"],
        year: 2026,
        artwork: longTitleArtwork,
        tracks: [
            DulcetTrack(
                id: "deliberately-long-title",
                title: "A deliberately long title " + String(repeating: "0123456789", count: 32),
                artistNames: ["Dulcet Fixtures"],
                albumTitle: "Long Titles",
                trackNumber: 1,
                duration: .seconds(287),
                artwork: longTitleArtwork
            )
        ]
    )

    static let thresholdBoundary = album(
        id: "album-threshold-boundary",
        title: "Threshold Boundary",
        artists: ["Dulcet Fixtures"],
        year: 2026,
        artwork: DulcetArtwork(seed: "threshold-boundary", palette: .slateApricot),
        titles: ["Twenty Nine Seconds", "Thirty One Seconds"],
        durations: [29, 31]
    )

    static let library: [DulcetAlbum] = [
        unicodeAlbum,
        doubleLines,
        pagingAtlas,
        severalAlbumArtists,
        longTitleAlbum,
        thresholdBoundary,
        album(id: "album-night-glass", title: "Night Glass", artists: ["Rhea Vale"], year: 2026, artwork: DulcetArtwork(seed: "night-glass", palette: .duskLavender), titles: ["Low Orbit", "Soft Relay", "Almost Awake"]),
        album(id: "album-mosslight", title: "Mosslight", artists: ["Orchard Static"], year: 2023, artwork: DulcetArtwork(seed: "mosslight", palette: .mossGold), titles: ["Verdant Signal", "Open Field"]),
        album(id: "album-paper-satellites", title: "Paper Satellites", artists: ["Mara North"], year: 2022, artwork: DulcetArtwork(seed: "paper-satellites", palette: .oceanMint), titles: ["Fold", "Transit", "Blue Thread"]),
        album(id: "album-arcs-embers", title: "Arcs & Embers", artists: ["Juniper Set"], year: 2025, artwork: DulcetArtwork(seed: "arcs-embers", palette: .emberRose), titles: ["Kindling", "Wide Arc"]),
        album(id: "album-field-notes", title: "Field Notes, Vol. 3", artists: ["Common Hours"], year: 2024, artwork: DulcetArtwork(seed: "field-notes", palette: .tealSun), titles: ["Margin", "Index", "Pressed Flower"]),
        album(id: "album-quiet-machines", title: "Quiet Machines", artists: ["Nia Form"], year: 2021, artwork: DulcetArtwork(seed: "quiet-machines", palette: .plumIce), titles: ["Idle State", "Copper Sleep"]),
    ]

    static let trackWithoutAlbum = DulcetTrack(
        id: "track-no-album",
        title: "No Album",
        artistNames: ["Dulcet Fixtures"],
        albumTitle: nil,
        duration: .seconds(174),
        artwork: DulcetArtwork(seed: "no-album", palette: .slateApricot)
    )

    static let nowPlaying = DulcetNowPlaying(
        current: unicodeAlbum.tracks[0],
        queue: [
            severalAlbumArtists.tracks[0],
            longTitleAlbum.tracks[0],
            doubleLines.tracks[0],
        ],
        elapsed: .seconds(142),
        isPlaying: true,
        outputName: "Studio Display",
        volume: 0.68,
        audioFormat: DulcetAudioFormat(codec: "FLAC", sampleRateKilohertz: 44.1)
    )

    static func recentlyAddedTracks(
        in albums: [DulcetAlbum],
        looseTracks: [DulcetTrack]
    ) -> [DulcetTrack] {
        let requestedIDs = [
            "album-etudes-between-stations-track-1",
            "double-lines-d1-t2",
            "shared-credit",
            "track-no-album",
            "deliberately-long-title",
            "paging-150",
        ]
        let all = albums.flatMap(\.tracks) + looseTracks
        return requestedIDs.compactMap { id in all.first { $0.id.rawID == id } }
    }

    static let searchResults: [DulcetSearchResult] = [
        searchResult(
            id: "paging-001", title: "Paging Track 001", kind: .track,
            artist: "Dulcet Fixtures", album: "Paging Atlas", duration: .seconds(188),
            artwork: pagingArtwork
        ),
        searchResult(
            id: "album-paging-atlas", title: "Paging Atlas", kind: .album,
            artist: "Dulcet Fixtures", album: nil, duration: .seconds(3_600),
            artwork: pagingArtwork
        ),
        searchResult(
            id: "artist-atlas-rooms", title: "Atlas Rooms", kind: .artist,
            artist: nil, album: nil, duration: nil,
            artwork: DulcetArtwork(seed: "atlas-rooms", palette: .tealSun)
        ),
        searchResult(
            id: "track-atlas-after-dark", title: "Atlas After Dark", kind: .track,
            artist: "Rhea Vale", album: "Night Glass", duration: .seconds(204),
            artwork: DulcetArtwork(seed: "atlas-after-dark", palette: .duskLavender)
        ),
        searchResult(
            id: "album-atlas-archive", title: "The Atlas Archive", kind: .album,
            artist: "North Window", album: nil, duration: .seconds(2_940),
            artwork: DulcetArtwork(seed: "atlas-archive", palette: .slateApricot)
        ),
        searchResult(
            id: "track-atlas-north", title: "Atlas North", kind: .track,
            artist: "Signal Coast", album: "Bearings", duration: .seconds(231),
            artwork: DulcetArtwork(seed: "atlas-north", palette: .mossGold)
        ),
    ]

    static let tlsFailure = DulcetTLSFailure(
        serverName: "Listening Room",
        reason: "The certificate expired on 14 August 2026.",
        technicalDetail: "macOS stopped the connection because it could not establish an OS-trusted certificate chain."
    )

    static func accountFailure(
        for state: DulcetPresentationState
    ) -> DulcetAccountFailurePresentation {
        let fixture: (DulcetAccountFailureKind, String, String, String, String?) = switch state {
        case .accountErrorInput:
            (
                .invalidServerURL,
                "This server address isn’t supported",
                "Enter a complete HTTP or HTTPS OpenSubsonic server address.",
                "Check the address and try again.",
                nil
            )
        case .accountErrorTransport:
            (
                .transportTimeout,
                "The server took too long to respond",
                "Dulcet stopped this connection attempt after the server did not respond in time.",
                "Check that the server is awake and reachable, then try again.",
                nil
            )
        case .accountErrorSecurity:
            (
                .tlsUntrusted,
                "This server’s certificate isn’t trusted",
                "macOS could not establish an operating-system-trusted certificate chain.",
                "Install the private certificate authority in macOS, then try again.",
                nil
            )
        case .accountErrorProtocol:
            (
                .malformedEnvelope,
                "The server returned invalid account information",
                "Dulcet reached the server, but its successful response was not a valid Subsonic envelope.",
                "Check for a proxy login page or update the server, then try again.",
                nil
            )
        case .accountErrorServer:
            (
                .knownServerError,
                "The server could not complete account setup",
                "The server returned a recognised Subsonic error.",
                "Review the server configuration and try again.",
                nil
            )
        case .accountErrorAuthentication:
            (
                .crossOriginRedirectRejected,
                "The server redirected account setup to another host",
                "Dulcet did not send credentials to login.example.invalid.",
                "Exclude /rest/ from the SSO sign-in layer, or enter an endpoint that is not behind it.",
                "login.example.invalid"
            )
        case .accountErrorCapability:
            (
                .capabilityUnsupported,
                "Account connection isn’t available",
                "This build cannot complete the requested account capability.",
                "Update Dulcet and try again.",
                nil
            )
        case .accountErrorPersistence:
            (
                .credentialPersistenceFailed,
                "Dulcet couldn’t save these credentials",
                "The server accepted the account, but the system Keychain did not save it.",
                "Unlock the Keychain and try connecting again.",
                nil
            )
        case .accountConnectIdle, .accountConnecting, .accountConnected,
             .accountRemoving, .accountRemovalError,
             .emptyLibraryNoAccount, .emptyLibraryConnected, .libraryLoading, .libraryError,
             .libraryBrowse, .albumDetailMultiDisc, .nowPlaying, .nowPlayingUnavailable,
             .searchIdle, .searchLoading, .searchResults, .searchEmpty, .searchError,
             .tlsUntrusted, .offlineMetadataOnly:
            (
                .transportUnreachable,
                "Can’t reach the server",
                "Dulcet could not establish a connection.",
                "Check the address and network, then try again.",
                nil
            )
        }
        return DulcetAccountFailurePresentation(
            kind: fixture.0,
            serverName: "music.example.invalid",
            title: fixture.1,
            message: fixture.2,
            recovery: fixture.3,
            targetHost: fixture.4
        )
    }

    static func album(
        id: String,
        title: String,
        artists: [String],
        year: Int,
        artwork: DulcetArtwork,
        titles: [String],
        durations: [Int]? = nil
    ) -> DulcetAlbum {
        let tracks = titles.enumerated().map { index, trackTitle in
            DulcetTrack(
                id: "\(id)-track-\(index + 1)",
                title: trackTitle,
                artistNames: artists,
                albumTitle: title,
                discNumber: 1,
                trackNumber: index + 1,
                duration: .seconds(durations?[index] ?? 188 + (index * 23)),
                artwork: artwork
            )
        }
        return DulcetAlbum(
            id: id,
            title: title,
            albumArtists: artists,
            year: year,
            artwork: artwork,
            tracks: tracks
        )
    }

    static func searchResult(
        id: String,
        title: String,
        kind: DulcetSearchResultKind,
        artist: String?,
        album: String?,
        duration: Duration?,
        artwork: DulcetArtwork
    ) -> DulcetSearchResult {
        DulcetSearchResult(
            id: DulcetProviderItemID(
                providerInstanceID: "deterministic-fixture",
                rawID: id
            ),
            title: title,
            kind: kind,
            credits: artist.map {
                [DulcetCredit(
                    role: kind == .album ? .albumArtist : .artist,
                    name: $0,
                    id: nil
                )]
            } ?? [],
            albumTitle: album,
            year: nil,
            duration: duration,
            mediaSourceID: nil,
            artwork: artwork
        )
    }
}

private extension DulcetTrack {
    func withAvailability(_ availability: DulcetMediaAvailability) -> DulcetTrack {
        DulcetTrack(
            id: id,
            title: title,
            credits: credits,
            albumTitle: albumTitle,
            discNumber: discNumber,
            trackNumber: trackNumber,
            duration: duration,
            mediaSourceID: mediaSourceID,
            artwork: artwork,
            availability: availability,
            isFavorite: isFavorite
        )
    }
}
