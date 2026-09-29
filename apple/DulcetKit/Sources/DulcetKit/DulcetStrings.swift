import Foundation
#if os(iOS)
import UIKit
#endif

enum DulcetStrings {
    static let appName = text("app.name", "Dulcet")
    static let library = text("sidebar.library", "Library")
    static let search = text("sidebar.search", "Search")
    static let nowPlaying = text("sidebar.nowPlaying", "Now Playing")
    static let settings = text("sidebar.settings", "Connection")
    static let browseSection = text("sidebar.browseSection", "Browse")
    static let accountSection = text("sidebar.accountSection", "Account")
    static let noServer = text("sidebar.noServer", "No server connected")
    static let online = text("status.online", "Online")
    static let disconnected = text("status.disconnected", "Disconnected")
    static let connectionFailed = text("status.connectionFailed", "Connection failed")
    static let offline = text("status.offline", "Offline")
    static let albums = text("library.albums", "Albums")
    static let artists = text("library.artists", "Artists")
    static let recentlyAdded = text("library.recentlyAdded", "Recently Added")
    static let songs = text("library.songs", "songs")
    static let tracks = text("library.tracks", "tracks")
    static let playAll = text("action.playAll", "Play All")
    static let shuffle = text("action.shuffle", "Shuffle")
    static let connectServer = text("action.connectServer", "Connect a Server")
    static let reconnect = text("action.reconnect", "Reconnect")
    static let browseHelp = text("action.browseHelp", "Learn About Servers")
    static let tryAgain = text("action.tryAgain", "Try Again")
    static let connectionSettings = text("action.connectionSettings", "Review Connection Settings")
    static let openCertificateHelp = text("action.certificateHelp", "Open CA Installation Guide")
    static let openLocalNetworkSettings = text("action.localNetworkSettings", "Open Settings")
    static let more = text("action.more", "More")
    static let play = text("action.play", "Play")
    static let download = text("action.download", "Download")
    static let downloading = text("download.state.downloading", "Downloading")
    static let downloaded = text("download.state.downloaded", "Downloaded")
    static let retryDownload = text("action.download.retry", "Retry Download")
    static let downloadUpdateAvailable = text(
        "download.state.updateAvailable",
        "Downloaded — Update Available"
    )
    static let pause = text("action.pause", "Pause")
    static let previous = text("action.previous", "Previous Track")
    static let next = text("action.next", "Next Track")
    static let repeatMode = text("action.repeat", "Repeat Mode")
    static let repeatOff = text("player.repeat.off", "Off")
    static let repeatAll = text("player.repeat.all", "Repeat All")
    static let repeatOne = text("player.repeat.one", "Repeat One")
    static let playbackMenu = text("menu.playback", "Playback")
    static let turnShuffleOn = text("menu.playback.shuffleOn", "Turn Shuffle On")
    static let turnShuffleOff = text("menu.playback.shuffleOff", "Turn Shuffle Off")
    static let seekForward15Seconds = text(
        "menu.playback.seekForward15",
        "Seek Forward 15 Seconds"
    )
    static let seekBackward15Seconds = text(
        "menu.playback.seekBackward15",
        "Seek Backward 15 Seconds"
    )

    static func repeatMenuValue(_ value: String) -> String {
        formatted("menu.playback.repeatValue", "Repeat: %@", value)
    }
    static let controlOn = text("control.on", "On")
    static let controlOff = text("control.off", "Off")
    static let buffering = text("player.buffering", "Buffering…")
    static let paused = text("player.paused", "Paused")
    static let readyToPlay = text("player.ready", "Ready to play")
    static let favorite = text("action.favorite", "Favorite")
    static let unfavorite = text("action.unfavorite", "Remove Favorite")
    static let volume = text("action.volume", "Volume")
    static let queue = text("player.queue", "Queue")
    static let playingOn = text("player.playingOn", "Playing on")
    static let firstRunTitle = text("empty.title", "Your music, wherever you listen")
    static let firstRunBody = text("empty.body", "Connect an OpenSubsonic server to browse your library and listen with native controls.")
    static let firstRunFootnote = text("empty.footnote", "Dulcet keeps account credentials in the system Keychain and sends no analytics.")
    static let connectedEmptyTitle = text("library.empty.connected.title", "This library is empty")
    static let connectedEmptyBody = text("library.empty.connected.body", "The connected server returned no artists or albums.")
    static let connectedEmptyFootnote = text("library.empty.connected.footnote", "Dulcet reads the server again whenever you open Library.")
    static let savedAccountDisconnectedBody = text("library.savedAccount.disconnected.body", "This server account is saved, but Dulcet has not connected during this launch.")
    static let savedAccountDisconnectedFootnote = text("library.savedAccount.disconnected.footnote", "Dulcet will contact the server only after you choose Reconnect.")
    static let libraryLoadingTitle = text("library.loading.title", "Reading your library…")
    static let libraryLoadingBody = text("library.loading.body", "Dulcet is fetching artists and albums from the connected server. Track lists are read when you open an album.")
    static let libraryErrorTitle = text("library.error.title", "The library could not be loaded")
    static let libraryErrorTimeout = text("library.error.timeout", "The server took too long to return the library.")
    static let libraryErrorAuthentication = text("library.error.authentication", "The server no longer accepts this account. Review the connection settings and connect again.")
    static let libraryErrorSecurity = text("library.error.security", "A security or certificate check stopped the library request.")
    static let libraryErrorProtocol = text("library.error.protocol", "The server returned a library response Dulcet could not read.")
    static let libraryErrorGeneric = text("library.error.generic", "Check the server and network, then try again.")
    static let albumTracksLoading = text("album.tracks.loading", "Reading this album\u{2026}")
    static let albumTracksErrorTitle = text("album.tracks.error.title", "This album\u{2019}s tracks could not be read")
    static let albumTracksRetry = text("album.tracks.retry", "Try Again")
    static let libraryTracksLoading = text("library.tracks.loading", "Track lists are still loading.")
    static let nowPlayingPreparingTitle = text("player.preparing.title", "Preparing playback…")
    static let nowPlayingPreparingBody = text("player.preparing.body", "Dulcet is opening the selected track.")
    static let nowPlayingFailedTitle = text("player.failed.title", "This track could not be played")
    static let nowPlayingFailedBody = text("player.failed.body", "Return to your library and choose another track.")
    static let nowPlayingUnavailableTitle = text("player.unavailable.title", "Nothing is playing")
    static let nowPlayingUnavailableBody = text("player.unavailable.body", "Choose a track, album, or library playback action to begin.")
    static let searchTitle = text("search.title", "Search")
    static let searchPrompt = text("search.prompt", "Artists, albums, and tracks")
    static let searchSummary = text("search.summary", "Results come from the connected server. Search begins after two characters.")
    static let searchIdleTitle = text("search.idle.title", "Search your server")
    static let searchIdleBody = text("search.idle.body", "Enter at least two characters to find artists, albums, and tracks.")
    static let searchLoading = text("search.loading", "Searching the server…")
    static let searchEmptyTitle = text("search.empty.title", "No server matches")
    static let searchEmptyBody = text("search.empty.body", "Try a different artist, album, or track name.")
    static let searchErrorTitle = text("search.error.title", "Search could not be completed")
    static let searchErrorBody = text("search.error.body", "Check the server and network, then try again.")
    static let searchRetry = text("search.retry", "Try Again")
    static let loadMoreTracks = text("search.more.tracks", "More tracks")
    static let loadMoreAlbums = text("search.more.albums", "More albums")
    static let loadMoreArtists = text("search.more.artists", "More artists")
    static let loadingMore = text("search.more.loading", "Loading more…")
    static let bestMatches = text("search.bestMatches", "Best Matches")
    static let resultColumn = text("search.column.result", "Result")
    static let typeColumn = text("search.column.type", "Type")
    static let album = text("search.kind.album", "Album")
    static let artist = text("search.kind.artist", "Artist")
    static let track = text("search.kind.track", "Track")
    static let tlsTitle = text("tls.title", "This server’s certificate isn’t trusted")
    static let tlsBody = text("tls.body", "Dulcet stopped before sending account credentials. There is no “continue anyway” option.")
    static let tlsWhy = text("tls.why", "Why Dulcet stopped the connection")
    static let tlsRemedyTitle = text("tls.remedyTitle", "How to reconnect safely")
    static let tlsRemedyBody = text("tls.remedyBody", "Fix or renew the server certificate. If your server uses a private certificate authority, install that CA at the operating-system level, then try again.")
    static let offlineTitle = text("offline.title", "Browsing saved library metadata")
    static let offlineBody = text("offline.body", "Album and track details are available. Music remains on your server, so playback returns when the connection does.")
    static let offlineUnavailable = text("offline.unavailable", "Unavailable offline")
    static let lastSynced = text("offline.lastSynced", "Last synced")
    static let disc = text("album.disc", "Disc")
    static let duration = text("track.duration", "Duration")
    static let withoutAlbum = text("track.withoutAlbum", "Single · no album")
    static let controlBad = text("control.bad", "DELIBERATELY BAD CONTROL")
    static let accountConnectTitle = text("account.connect.title", "Connect your music server")
    static let accountConnectBody = text("account.connect.body", "Enter the OpenSubsonic address and the account you use with that server.")
    static let accountDetails = text("account.connect.details", "Server account")
    static let serverAddress = text("account.connect.server", "Server address")
    static let serverAddressPlaceholder = text("account.connect.server.placeholder", "https://music.example.com")
    static let username = text("account.connect.username", "Username")
    static let password = text("account.connect.password", "Password")
    static let allowLocalHTTP = text("account.connect.localHTTP", "Allow HTTP on this local network")
    static let allowLocalHTTPHint = text("account.connect.localHTTP.hint", "Use only for a server you control on a private local network.")
    static let connect = text("account.connect.submit", "Connect")
    static let connecting = text("account.connect.progress", "Connecting to the server…")
    static let connectingBody = text("account.connect.progress.body", "Dulcet is checking the server, signing in, and reading account capabilities. You can cancel at any time.")
    static let cancel = text("action.cancel", "Cancel")
    static let accountCredentialFootnote = text("account.connect.keychain", "After a successful connection, Dulcet stores these credentials in the system Keychain.")
    static let savedAccountReconnectBody = text("account.connect.saved.body", "This account is saved. Dulcet remains disconnected until you choose Reconnect.")
    static let tvTextEntryHint = text("account.connect.tv.textEntryHint", "Select a field to enter text with the Apple TV keyboard or a nearby Apple device.")
    static let signOut = text("account.remove.action", "Sign Out")
    static let signOutConfirmationTitle = text("account.remove.confirm.title", "Sign out of this server?")
    static let signOutConfirmationBody = text("account.remove.confirm.body", "Dulcet will delete this account’s Keychain credential and clear its loaded library from this app.")
    static let signingOut = text("account.remove.progress.title", "Signing out…")
    static let signingOutBody = text("account.remove.progress.body", "Dulcet is deleting the saved credential before clearing account data.")
    static let signOutErrorTitle = text("account.remove.error.title", "Dulcet couldn’t finish signing out")
    static let signOutErrorBody = text("account.remove.error.body", "The account is still connected and its loaded library has not been cleared.")
    static let keepAccount = text("account.remove.keep", "Keep Account")
    static let playNext = text("action.playNext", "Play Next")
    static let addToQueue = text("action.addToQueue", "Add to Queue")
    static let goToAlbum = text("action.goToAlbum", "Go to Album")
    static let goToArtist = text("action.goToArtist", "Go to Artist")
    static let upNext = text("player.upNext", "Up Next")
    static let showUpNext = text("player.upNext.show", "Show Up Next")
    static let hideUpNext = text("player.upNext.hide", "Hide Up Next")
    static let closeNowPlaying = text("player.close", "Close Now Playing")
    static let openNowPlayingHint = text("player.open.hint", "Opens the full player")
    static let playbackLoading = text("player.loading", "Loading…")
    static let playbackFailedShort = text("player.failed.short", "Couldn\u{2019}t play this track")
    static let playbackStoppedPartwayShort = text("player.failed.short.partway", "Stopped partway through")
    // What the player says under a failed track's name: one whole sentence per case, so no
    // translation is ever assembled from pieces.
    static let playbackFailedToStartRetryOrSkip = text(
        "player.failed.message.start.both",
        "Dulcet couldn\u{2019}t start this track. Try it again, or skip to the next one."
    )
    static let playbackFailedToStartRetry = text(
        "player.failed.message.start.retry",
        "Dulcet couldn\u{2019}t start this track. Try it again."
    )
    static let playbackFailedToStartSkip = text(
        "player.failed.message.start.skip",
        "Dulcet couldn\u{2019}t start this track. Skip to the next one."
    )
    static let playbackStoppedPartwayRetryOrSkip = text(
        "player.failed.message.partway.both",
        "This track stopped partway through. Try it again, or skip to the next one."
    )
    static let playbackStoppedPartwayRetry = text(
        "player.failed.message.partway.retry",
        "This track stopped partway through. Try it again."
    )
    static let playbackStoppedPartwaySkip = text(
        "player.failed.message.partway.skip",
        "This track stopped partway through. Skip to the next one."
    )
    static let playbackRetry = text("player.failed.retry", "Try Again")
    static let playbackSkip = text("player.failed.skip", "Skip to Next Track")
    static let playbackSkipShort = text("player.failed.skip.short", "Skip")
    static let playbackFailureDismiss = text("player.failed.dismiss", "Dismiss")
    static let queueEditRefused = text("queue.edit.refused", "Couldn\u{2019}t change Up Next")
    static let playbackSkippedAfterFailureUntitled = text(
        "player.skipped",
        "Couldn\u{2019}t play a track. Skipped."
    )
    static let queueDragRefused = text("queue.drag.refused", "Can\u{2019}t be added to Up Next")
    static let openAlbumHint = text("library.album.hint", "Opens the album")
    static let remainingTime = text("player.remaining", "Remaining")
    static let elapsedTime = text("player.elapsed", "Elapsed")
    static let menuSearch = text("menu.search", "Search Library")
    static let menuShowNowPlaying = text("menu.showNowPlaying", "Show Now Playing")
    static let menuGo = text("menu.go", "Go")
    static let menuBack = text("menu.back", "Back")
    static let menuFavoritePlaying = text("menu.favoritePlaying", "Add Playing Song to Favorites")
    static let menuUnfavoritePlaying = text("menu.unfavoritePlaying", "Remove Playing Song from Favorites")

    static func remaining(_ value: String) -> String {
        formatted("player.remainingValue", "\u{2212}%@", value)
    }

    static func miniPlayerAccessibility(title: String, artists: String) -> String {
        formatted("player.mini.accessibility", "Now Playing, %1$@, %2$@", title, artists)
    }

    static func albumCount(_ count: Int) -> String {
        pluralized("library.albumCount", fallback: "%d albums", count: count)
    }

    static func trackCount(_ count: Int) -> String {
        pluralized("library.trackCount", fallback: "%d tracks", count: count)
    }

    static func searchResultCount(_ count: Int) -> String {
        pluralized("search.resultCount", fallback: "%d results", count: count)
    }

    static func discTitle(_ number: Int) -> String {
        formatted("album.discNumber", "Disc %@", identifierNumber(number))
    }

    static func serverStatus(_ name: String) -> String {
        formatted("status.server", "%@ · Online", name)
    }

    static func playbackFailed(title: String) -> String {
        formatted("player.failed.track", "Couldn\u{2019}t play \u{201C}%@\u{201D}", title)
    }

    static func playbackSkippedAfterFailure(title: String) -> String {
        formatted("player.skipped.track", "Couldn\u{2019}t play \u{201C}%@\u{201D}. Skipped.", title)
    }

    static func playbackStoppedPartway(title: String) -> String {
        formatted("player.failed.track.partway", "\u{201C}%@\u{201D} stopped partway through", title)
    }

    static func serverConnectionFailed(_ name: String) -> String {
        formatted("status.serverConnectionFailed", "%@ · Connection failed", name)
    }

    static func serverDisconnected(_ name: String) -> String {
        formatted("status.serverDisconnected", "%@ · Disconnected", name)
    }

    static func reconnectToServer(_ name: String) -> String {
        formatted("account.connect.reconnectToServer", "Reconnect to %@", name)
    }

    static func artistNames(_ names: [String]) -> String {
        ListFormatter.localizedString(byJoining: names)
    }

    static func librarySummary(albumCount: Int, trackCount: Int) -> String {
        formatted(
            "library.summary",
            "%1$@ · %2$@",
            self.albumCount(albumCount),
            self.trackCount(trackCount)
        )
    }

    static func musicFolderSummary(_ names: [String]) -> String {
        formatted(
            "library.musicFolders",
            "Music folders: %@",
            ListFormatter.localizedString(byJoining: names)
        )
    }

    static func albumAccessibility(_ title: String, artists: String, tracks: String) -> String {
        formatted("album.accessibility", "%1$@, %2$@, %3$@", title, artists, tracks)
    }

    static func albumMetadata(year: Int, tracks: String, duration: String) -> String {
        formatted(
            "album.metadata",
            "%1$@ · %2$@ · %3$@",
            identifierNumber(year),
            tracks,
            duration
        )
    }

    static func trackSubtitle(artists: String, album: String) -> String {
        formatted("track.subtitle", "%1$@ · %2$@", artists, album)
    }

    static func trackAccessibility(title: String, subtitle: String, duration: String) -> String {
        formatted("track.accessibility", "%1$@, %2$@, %3$@", title, subtitle, duration)
    }

    static func currentTrackAccessibility(_ track: String) -> String {
        formatted("track.accessibility.current", "Current track, %@", track)
    }

    static func unavailableTrackAccessibility(
        title: String,
        subtitle: String,
        duration: String
    ) -> String {
        formatted(
            "track.accessibility.unavailable",
            "%1$@, %2$@, %3$@, %4$@",
            title,
            subtitle,
            duration,
            offlineUnavailable
        )
    }

    static func playbackProgress(elapsed: String, duration: String) -> String {
        formatted("player.progress", "%1$@ of %2$@", elapsed, duration)
    }

    static func volumeValue(_ volume: Double) -> String {
        volume.formatted(.percent.precision(.fractionLength(0)))
    }

    static func audioFormat(codec: String, sampleRateKilohertz: Double) -> String {
        let rate = sampleRateKilohertz.formatted(
            .number.precision(.fractionLength(0...1))
        )
        return formatted("player.audioFormat", "%1$@ · %2$@ kHz", codec, rate)
    }

    static func playingOn(_ outputName: String) -> String {
        formatted("player.playingOnOutput", "Playing on %@", outputName)
    }

    static func playingFrom(_ sourceName: String) -> String {
        formatted("player.playingFrom", "Playing from %@", sourceName)
    }

    static func searchResultAccessibility(
        title: String,
        subtitle: String,
        kind: String
    ) -> String {
        formatted(
            "search.result.accessibility",
            "%1$@, %2$@, %3$@",
            title,
            subtitle,
            kind
        )
    }

    static func lastSynced(_ description: String) -> String {
        formatted("offline.lastSyncedValue", "Last synced %@", description)
    }

    static func connectedTo(_ serverName: String) -> String {
        formatted("account.connect.connected", "Connected to %@", serverName)
    }

    // MARK: The library reader (§16.14, §16.15). Words the person sees are "Available offline",
    // "Downloaded" and "Not available offline"; never "cached" or "sync".

    static let readerUntitled = text("reader.untitled", "Unknown")
    /// Where a track's length would read had the server stated it: it never did (§16.11).
    static let readerTrackDurationUnknown = text("reader.track.durationUnknown", "\u{2013}")
    static let readerHome = text("reader.section.home", "Home")
    static let readerAlbums = text("reader.section.albums", "Albums")
    static let readerArtists = text("reader.section.artists", "Artists")
    static let readerGenres = text("reader.section.genres", "Genres")
    static let readerPlaylists = text("reader.section.playlists", "Playlists")
    static let readerFavorites = text("reader.section.favorites", "Favorites")
    static let readerSongs = text("reader.section.songs", "Songs")
    static let readerRecentlyAdded = text("reader.home.recentlyAdded", "Recently Added")
    static let readerRecentlyPlayed = text("reader.home.recentlyPlayed", "Recently Played")
    static let readerMostPlayed = text("reader.home.mostPlayed", "Most Played")
    static let readerSortBy = text("reader.sort", "Sort By")
    static let readerSortTitle = text("reader.sort.title", "Title")
    static let readerSortArtist = text("reader.sort.artist", "Artist")
    static let readerSortRecentlyAdded = text("reader.sort.recentlyAdded", "Recently Added")
    static let readerSortRecentlyPlayed = text("reader.sort.recentlyPlayed", "Recently Played")
    static let readerSortMostPlayed = text("reader.sort.mostPlayed", "Most Played")
    static let readerSortTopRated = text("reader.sort.topRated", "Top Rated")
    static let readerSortRandom = text("reader.sort.random", "Random")
    static let readerSortFavorites = text("reader.sort.favorites", "Favorites")
    static let readerJustNow = text("reader.age.justNow", "just now")
    static let readerSeenUnknownAge = text("reader.seen.unknownAge", "Showing what this device had saved, age unknown")
    static let readerReasonOffline = text("reader.reason.offline", "you\u{2019}re offline")
    static let readerReasonRevalidating = text("reader.reason.revalidating", "checking your server")
    static let readerReasonStale = text("reader.reason.stale", "your library has changed since")
    static let readerReasonOwed = text("reader.reason.owed", "more is still to load")
    static let readerReasonInternal = text("reader.reason.internal", "something went wrong on this device")
    static let readerErrorUnreachable = text("reader.error.unreachable", "couldn\u{2019}t reach your server")
    static let readerErrorTimeout = text("reader.error.timeout", "your server didn\u{2019}t answer in time")
    static let readerErrorCredentials = text("reader.error.credentials", "your server didn\u{2019}t accept your sign-in")
    static let readerErrorForbidden = text("reader.error.forbidden", "your account isn\u{2019}t allowed to see this")
    static let readerErrorBusy = text("reader.error.busy", "your server is busy")
    static let readerErrorNotFound = text("reader.error.notFound", "your server no longer has this")
    static let readerErrorTLS = text("reader.error.tls", "your server\u{2019}s certificate isn\u{2019}t trusted")
    static let readerErrorAccessRefused = text("reader.error.accessRefused", "your server refused access")
    static let readerErrorServer = text("reader.error.server", "your server couldn\u{2019}t answer")
    static let readerUnavailableAlbum = text("reader.unavailable.album", "You haven\u{2019}t opened this album on this device. Connect to your server to see it.")
    static let readerUnavailableList = text("reader.unavailable.list", "You haven\u{2019}t opened this on this device. Connect to your server to see it.")
    static let readerUnavailableAlbumTracks = text("reader.unavailable.albumTracks", "This album\u{2019}s tracks aren\u{2019}t on this device. Connect to your server to see them.")
    static let readerUnavailableGone = text("reader.unavailable.gone", "This is no longer on your server.")
    static let readerUnavailableInternal = text("reader.unavailable.internal", "Couldn\u{2019}t load this \u{2014} something went wrong on this device.")
    static let readerUnavailableClosed = text("reader.unavailable.closed", "This screen has closed.")
    static let readerEmptyList = text("reader.empty", "Nothing here yet.")
    static let readerCoverageScanning = text("reader.coverage.scanning", "Your server is updating its library \u{2014} this list may change")
    static let readerCoverageChanging = text("reader.coverage.changing", "This list kept changing while it was read \u{2014} it may be incomplete")
    static let readerAvailableOffline = text("reader.availableOffline", "Available offline")
    static let readerNotAvailableOffline = text("reader.notAvailableOffline", "Not available offline")
    static let readerDownloaded = text("reader.downloaded", "Downloaded")
    static let readerPlaysOnReconnect = text("reader.playsOnReconnect", "Not downloaded. It\u{2019}ll play when you reconnect.")
    static let readerNothingPlayable = text("reader.nothingPlayable", "Nothing here can play right now.")
    static let readerNoEpoch = text("reader.noEpoch", "Your server doesn\u{2019}t say when its library changes, so a long list can occasionally miss an item while the library is changing.")
    static let readerChangeHeldBusy = text("reader.change.heldBusy", "That change isn\u{2019}t sent yet \u{2014} your server is busy. It will be sent later.")
    static let readerChangeSuperseded = text("reader.change.superseded", "Changed on another device \u{2014} showing your server\u{2019}s value")
    static let readerChangeNotRecorded = text("reader.change.notRecorded", "Couldn\u{2019}t save that change on this device")
    static let readerFavoritePending = text("reader.favorite.pending", "Waiting to send")
    static let readerFavoriteHeld = text("reader.favorite.held", "Not sent yet")
    static let readerFavoriteOn = text("reader.favorite.on", "Favorite")
    static let readerDeviceOnlyTitle = text("reader.deviceOnly.title", "Showing what\u{2019}s on this device")
    static let readerSearchScopeDevice = text("reader.search.scope.device", "On this device")
    static let readerSearchScopeServer = text("reader.search.scope.server", "Your server")
    static let readerSearchRowDevice = text("reader.search.row.device", "On this device")
    static let readerSearchSummary = text("reader.search.summary", "Results come from your server and from what this device has seen, from the first character.")
    static let readerSearchIdleBody = text("reader.search.idle.body", "Find artists, albums, and tracks on your server and on this device.")
    static let readerSearchEmptyTitle = text("reader.search.empty.title", "No matches")
    static let readerSignOutPendingStay = text("reader.signOut.stay", "Stay Signed In")
    static let readerSignOutDiscard = text("reader.signOut.discard", "Sign Out and Discard")
    static let readerSignOutSend = text("reader.signOut.send", "Send Changes, Then Sign Out")
    static let readerSignOutPendingUnknown = text("reader.signOut.pendingUnknown", "Dulcet couldn\u{2019}t check for changes that haven\u{2019}t reached your server. Signing out now discards any there are.")
    static let readerSignOutSending = text("reader.signOut.sending", "Sending your changes\u{2026}")
    static let readerSignOutSendFailed = text("reader.signOut.sendFailed", "Your server couldn\u{2019}t be reached, so your changes weren\u{2019}t sent.")
    static let readerTryAgain = text("reader.tryAgain", "Try Again")
    static let readerPlayAll = text("reader.playAll", "Play")
    static let readerSeeAll = text("reader.seeAll", "See All")
    static let readerSignOutTitle = text("reader.signOut.title", "Changes Not Sent")

    static func readerSeenAt(_ age: String) -> String {
        formatted("reader.seen.at", "Showing what you last saw %@", age)
    }

    /// A reader row read aloud: its title, then its subtitle.
    static func readerRowAccessibility(_ title: String, _ subtitle: String) -> String {
        formatted("reader.row.accessibility", "%1$@, %2$@", title, subtitle)
    }

    static func readerSeenWithReason(_ seen: String, _ reason: String) -> String {
        formatted("reader.seen.withReason", "%1$@ \u{2014} %2$@", seen, reason)
    }

    static func readerUnavailableFailed(_ phrase: String) -> String {
        formatted("reader.unavailable.failed", "Couldn\u{2019}t load this \u{2014} %@.", phrase)
    }

    static func readerCoverageOpen(shown: String, total: String) -> String {
        formatted("reader.coverage.openTotal", "Showing %1$@ of %2$@ \u{2014} the rest need a connection", shown, total)
    }

    static func readerCoverageOpen(shown: String) -> String {
        formatted("reader.coverage.open", "Showing %@ \u{2014} the rest need a connection", shown)
    }

    static func readerCount(_ kind: DulcetReaderCountKind, _ count: Int) -> String {
        switch kind {
        case .albums: pluralized("reader.count.albums", fallback: "%d albums", count: count)
        case .artists: pluralized("reader.count.artists", fallback: "%d artists", count: count)
        case .tracks: pluralized("reader.count.tracks", fallback: "%d tracks", count: count)
        case .playlists: pluralized("reader.count.playlists", fallback: "%d playlists", count: count)
        case .items: pluralized("reader.count.items", fallback: "%d items", count: count)
        }
    }

    static func readerConnectionFailed(_ phrase: String) -> String {
        formatted("reader.connectionFailed", "Couldn\u{2019}t connect to your server \u{2014} %@", phrase)
    }

    static func readerDeviceOnlyBody(_ serverName: String) -> String {
        formatted(
            "reader.deviceOnly.body",
            "Dulcet will contact %@ only after you choose Reconnect.",
            serverName
        )
    }

    static func readerDiscardedChanges(_ count: Int) -> String {
        pluralized(
            "reader.discardedChanges",
            fallback: "%d favorites or ratings weren\u{2019}t sent and have been discarded: this device is now signed in as someone else.",
            count: count
        )
    }

    static func readerChangeNotSaved(_ phrase: String) -> String {
        formatted("reader.change.notSaved", "Couldn\u{2019}t save that change \u{2014} %@", phrase)
    }

    static func readerChangeHeldRefused(_ phrase: String) -> String {
        formatted(
            "reader.change.heldRefused",
            "That change isn\u{2019}t sent yet \u{2014} %@. It\u{2019}s kept, and will be sent once your server accepts it.",
            phrase
        )
    }

    static func readerSearchScopeOffline(albums: Int, tracks: Int) -> String {
        formatted(
            "reader.search.scope.offline",
            "Searching what\u{2019}s available offline \u{2014} %1$@ and %2$@ on this device",
            readerCount(.albums, albums),
            readerCount(.tracks, tracks)
        )
    }

    static func readerSearchScopeFailed(_ phrase: String) -> String {
        formatted("reader.search.scope.failed", "On this device \u{2014} %@", phrase)
    }

    static func readerSignOutPending(_ count: Int) -> String {
        pluralized(
            "reader.signOut.pending",
            fallback: "%d changes haven\u{2019}t reached your server. Signing out now discards them.",
            count: count
        )
    }

    static func readerSignOutPendingOnline(_ count: Int) -> String {
        pluralized(
            "reader.signOut.pendingOnline",
            fallback: "%d changes haven\u{2019}t reached your server yet. Send them before signing out, or sign out and discard them.",
            count: count
        )
    }

    static func readerFavoriteAccessibility(_ title: String) -> String {
        formatted("reader.favorite.accessibility", "%@, Favorite", title)
    }

    static func readerGenreSummary(albums: Int?, songs: Int?) -> String {
        let parts = [albums.map { readerCount(.albums, $0) }, songs.map { readerCount(.tracks, $0) }].compactMap { $0 }
        return ListFormatter.localizedString(byJoining: parts)
    }

    /// Why the reader could not read, in the words the screens use.
    static func readerErrorPhrase(_ kind: DulcetReaderErrorKind) -> String {
        switch kind {
        case .unreachable, .cancelled: readerErrorUnreachable
        case .timeout: readerErrorTimeout
        case .invalidCredentials, .authentication: readerErrorCredentials
        case .forbidden: readerErrorForbidden
        case .serverBusy: readerErrorBusy
        case .tlsUntrusted, .security: readerErrorTLS
        case .notFound: readerErrorNotFound
        case .internalFailure, .closed: readerReasonInternal
        case .protocol, .server, .playback, .input, .capability: readerErrorServer
        }
    }

    /// Why a held change waits: what the ping that checked the account said -- never the item.
    static func readerHeldPhrase(_ kind: DulcetReaderErrorKind) -> String {
        switch kind {
        case .invalidCredentials: readerErrorCredentials
        case .authentication, .forbidden: readerErrorAccessRefused
        default: readerErrorServer
        }
    }

    static func dynamicText(_ key: String, fallback: String) -> String {
        Bundle.module.localizedString(forKey: key, value: fallback, table: nil)
    }

    static func dynamicFormatted(
        _ key: String,
        fallback: String,
        _ arguments: CVarArg...
    ) -> String {
        String(
            format: dynamicText(key, fallback: fallback),
            locale: Locale.current,
            arguments: arguments
        )
    }

    /// A quantity, grouped as the locale groups quantities ("2,950").
    static func groupedNumber(_ value: Int, locale: Locale = .current) -> String {
        value.formatted(.number.locale(locale))
    }

    static func identifierNumber(_ value: Int, locale: Locale = .current) -> String {
        value.formatted(.number.grouping(.never).locale(locale))
    }

    private static func formatted(
        _ key: StaticString,
        _ fallback: String.LocalizationValue,
        _ arguments: CVarArg...
    ) -> String {
        String(
            format: text(key, fallback),
            locale: Locale.current,
            arguments: arguments
        )
    }

    private static func pluralized(
        _ key: String,
        fallback: String,
        count: Int
    ) -> String {
        let format = Bundle.module.localizedString(forKey: key, value: fallback, table: nil)
        return String.localizedStringWithFormat(format, count)
    }

    private static func text(
        _ key: StaticString,
        _ fallback: String.LocalizationValue
    ) -> String {
        String(localized: key, defaultValue: fallback, bundle: .module)
    }
}

public enum DulcetPlaybackStrings {
    public static let thisDevice = String(
        localized: "player.thisDevice",
        defaultValue: "This Device",
        bundle: .module
    )
    public static let unknownAudioFormat = String(
        localized: "player.unknownAudioFormat",
        defaultValue: "Audio",
        bundle: .module
    )
}

enum DulcetReaderCountKind {
    case albums
    case artists
    case tracks
    case playlists
    case items
}

enum DulcetLinks {
    static let certificateInstallationGuide = URL(
        string: "https://support.apple.com/guide/keychain-access/add-certificates-to-a-keychain-kyca2431/mac"
    )!

    /// Where Dulcet's Local Network switch lives: the app's own page in Settings on iOS, the
    /// Local Network privacy pane on the Mac. ASSUMED for the Mac: the pane URL is not documented
    /// API, and a wrong one opens System Settings at its top level rather than failing.
    ///
    /// What is and is not known (checked against macOS 26.7): Apple's own help for this setting
    /// links only to Privacy & Security, and TN3179 names no URL. The installed Privacy & Security
    /// extension declares `com.apple.preference.security` as its legacy identifier, which is what
    /// the URL's host names. The `Privacy_LocalNetwork` anchor does not appear among the anchors
    /// that extension carries for its other rows. Opening this URL was not observed at all, so
    /// both where it lands and whether it reaches the pane are ASSUMED.
    static let localNetworkSettings: URL? = {
#if os(iOS)
        URL(string: UIApplication.openSettingsURLString)
#elseif os(macOS)
        URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork")
#else
        nil
#endif
    }()
}
