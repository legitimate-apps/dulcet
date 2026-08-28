import Foundation

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
    static let more = text("action.more", "More")
    static let play = text("action.play", "Play")
    static let pause = text("action.pause", "Pause")
    static let previous = text("action.previous", "Previous Track")
    static let next = text("action.next", "Next Track")
    static let repeatMode = text("action.repeat", "Repeat Mode")
    static let repeatOff = text("player.repeat.off", "Off")
    static let repeatAll = text("player.repeat.all", "Repeat All")
    static let repeatOne = text("player.repeat.one", "Repeat One")
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
    static let libraryLoadingBody = text("library.loading.body", "Dulcet is fetching artists, albums, and track lists from the connected server. Large or remote libraries can take time.")
    static let libraryErrorTitle = text("library.error.title", "The library could not be loaded")
    static let libraryErrorTimeout = text("library.error.timeout", "The server took too long to return the library.")
    static let libraryErrorAuthentication = text("library.error.authentication", "The server no longer accepts this account. Review the connection settings and connect again.")
    static let libraryErrorSecurity = text("library.error.security", "A security or certificate check stopped the library request.")
    static let libraryErrorProtocol = text("library.error.protocol", "The server returned a library response Dulcet could not read.")
    static let libraryErrorGeneric = text("library.error.generic", "Check the server and network, then try again.")
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
    static let signOutErrorTitle = text("account.remove.error.title", "Dulcet couldn’t delete the saved credential")
    static let signOutErrorBody = text("account.remove.error.body", "The account is still connected and its loaded library has not been cleared.")
    static let keepAccount = text("account.remove.keep", "Keep Account")

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

enum DulcetLinks {
    static let certificateInstallationGuide = URL(
        string: "https://support.apple.com/guide/keychain-access/add-certificates-to-a-keychain-kyca2431/mac"
    )!
}
