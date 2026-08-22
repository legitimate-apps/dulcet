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
             .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            return accountSnapshot(for: state)
        case .emptyLibraryNoAccount, .libraryBrowse:
            return base.snapshot()
        case .albumDetailMultiDisc:
            return base.snapshot(selectedAlbum: Self.doubleLines)
        case .nowPlaying:
            return base.snapshot(nowPlaying: Self.nowPlaying)
        case .searchMixedSources:
            return base.snapshot(searchQuery: "atlas", searchResults: Self.searchResults)
        case .tlsUntrusted:
            return base.snapshot()
        case .offlineMetadataOnly:
            let offlineAlbums = library.map { album in
                DulcetAlbum(
                    id: album.id,
                    title: album.title,
                    albumArtists: album.albumArtists,
                    year: album.year,
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
        case .emptyLibraryNoAccount, .libraryBrowse, .albumDetailMultiDisc, .offlineMetadataOnly:
            .library
        case .nowPlaying:
            .nowPlaying
        case .searchMixedSources:
            .search
        case .accountConnectIdle, .accountConnecting, .accountConnected,
             .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence, .tlsUntrusted:
            .settings
        }
    }

    private func connectivity(for state: DulcetPresentationState) -> DulcetConnectivity {
        switch state {
        case .accountConnected:
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
        case .accountConnected:
            .connected(DulcetConnectedAccountSummary(
                serverName: "Listening Room",
                normalizedServerURL: "https://music.example.invalid"
            ))
        case .accountErrorInput, .accountErrorTransport, .accountErrorSecurity,
             .accountErrorProtocol, .accountErrorServer, .accountErrorAuthentication,
             .accountErrorCapability, .accountErrorPersistence:
            .failed(Self.accountFailure(for: state))
        case .emptyLibraryNoAccount, .libraryBrowse, .albumDetailMultiDisc, .nowPlaying,
             .searchMixedSources, .tlsUntrusted, .offlineMetadataOnly:
            .idle
        }
        return DulcetSnapshot(
            state: state,
            selectedDestination: .settings,
            accountConnected: state == .accountConnected,
            connectivity: connectivity(for: state),
            albums: [],
            looseTracks: [],
            recentlyAddedTracks: [],
            captureDate: Self.captureDate,
            accountForm: form,
            accountConnection: status
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
            case .search: .searchMixedSources
            case .nowPlaying: .nowPlaying
            case .settings: .accountConnectIdle
            }
            currentSnapshot = fixture.snapshot(for: state)
        case let .updateSearchQuery(query):
            currentSnapshot = currentSnapshot.replacingSearchQuery(query)
        case let .submitAccountConnection(request):
            currentSnapshot = fixture.snapshot(for: .accountConnecting)
                .replacingAccountForm(request)
        case .cancelAccountConnection:
            currentSnapshot = fixture.snapshot(for: .accountConnectIdle)
        }
        snapshotHandler?(currentSnapshot)
    }
}

private extension DulcetSnapshot {
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
            captureDate: captureDate,
            accountForm: accountForm,
            accountConnection: accountConnection
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
            captureDate: captureDate,
            accountForm: form,
            accountConnection: accountConnection
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
            searchResults: [DulcetSearchResult] = []
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
                    durationSeconds: 196 + (disc * 13) + (track * 7),
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
                durationSeconds: 15 + (track % 19),
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
                durationSeconds: 243,
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
                durationSeconds: 287,
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
        durationSeconds: 174,
        artwork: DulcetArtwork(seed: "no-album", palette: .slateApricot)
    )

    static let nowPlaying = DulcetNowPlaying(
        current: unicodeAlbum.tracks[0],
        queue: [
            severalAlbumArtists.tracks[0],
            longTitleAlbum.tracks[0],
            doubleLines.tracks[0],
        ],
        elapsedSeconds: 142,
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
        return requestedIDs.compactMap { id in all.first { $0.id == id } }
    }

    static let searchResults: [DulcetSearchResult] = [
        DulcetSearchResult(
            id: "paging-001",
            title: "Paging Track 001",
            subtitle: "Dulcet Fixtures · Paging Atlas",
            kind: .track,
            source: .localAndServer,
            artwork: pagingArtwork,
            refreshedFromServer: true
        ),
        DulcetSearchResult(
            id: "album-paging-atlas",
            title: "Paging Atlas",
            subtitle: "Dulcet Fixtures · 300 tracks",
            kind: .album,
            source: .local,
            artwork: pagingArtwork
        ),
        DulcetSearchResult(
            id: "artist-atlas-rooms",
            title: "Atlas Rooms",
            subtitle: "Artist · 4 albums",
            kind: .artist,
            source: .server,
            artwork: DulcetArtwork(seed: "atlas-rooms", palette: .tealSun)
        ),
        DulcetSearchResult(
            id: "track-atlas-after-dark",
            title: "Atlas After Dark",
            subtitle: "Rhea Vale · Night Glass",
            kind: .track,
            source: .server,
            artwork: DulcetArtwork(seed: "atlas-after-dark", palette: .duskLavender)
        ),
        DulcetSearchResult(
            id: "track-atlas-in-blue",
            title: "Atlas in Blue",
            subtitle: "Mara Venn · Cartography",
            kind: .track,
            source: .local,
            artwork: DulcetArtwork(seed: "atlas-in-blue", palette: .oceanMint)
        ),
        DulcetSearchResult(
            id: "album-atlas-archive",
            title: "The Atlas Archive",
            subtitle: "North Window · 18 tracks",
            kind: .album,
            source: .server,
            artwork: DulcetArtwork(seed: "atlas-archive", palette: .slateApricot)
        ),
        DulcetSearchResult(
            id: "artist-atlas-quartet",
            title: "Atlas Quartet",
            subtitle: "Artist · 7 albums",
            kind: .artist,
            source: .localAndServer,
            artwork: DulcetArtwork(seed: "atlas-quartet", palette: .indigoCoral),
            refreshedFromServer: true
        ),
        DulcetSearchResult(
            id: "track-atlas-north",
            title: "Atlas North",
            subtitle: "Signal Coast · Bearings",
            kind: .track,
            source: .server,
            artwork: DulcetArtwork(seed: "atlas-north", palette: .mossGold)
        ),
        DulcetSearchResult(
            id: "track-atlas-signal",
            title: "Atlas Signal",
            subtitle: "Rhea Vale · Night Glass",
            kind: .track,
            source: .local,
            artwork: DulcetArtwork(seed: "atlas-signal", palette: .plumIce)
        ),
        DulcetSearchResult(
            id: "album-atlas-field",
            title: "Atlas Field Recordings",
            subtitle: "Dulcet Fixtures · 24 tracks",
            kind: .album,
            source: .localAndServer,
            artwork: DulcetArtwork(seed: "atlas-field", palette: .emberRose)
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
             .emptyLibraryNoAccount, .libraryBrowse, .albumDetailMultiDisc, .nowPlaying,
             .searchMixedSources, .tlsUntrusted, .offlineMetadataOnly:
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
                durationSeconds: durations?[index] ?? 188 + (index * 23),
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
}

private extension DulcetTrack {
    func withAvailability(_ availability: DulcetMediaAvailability) -> DulcetTrack {
        DulcetTrack(
            id: id,
            title: title,
            artistNames: artistNames,
            albumTitle: albumTitle,
            discNumber: discNumber,
            trackNumber: trackNumber,
            durationSeconds: durationSeconds,
            artwork: artwork,
            availability: availability,
            isFavorite: isFavorite
        )
    }
}
