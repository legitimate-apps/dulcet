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
    static let connectionFailed = text("status.connectionFailed", "Connection failed")
    static let offline = text("status.offline", "Offline")
    static let albums = text("library.albums", "Albums")
    static let recentlyAdded = text("library.recentlyAdded", "Recently Added")
    static let songs = text("library.songs", "songs")
    static let tracks = text("library.tracks", "tracks")
    static let playAll = text("action.playAll", "Play All")
    static let shuffle = text("action.shuffle", "Shuffle")
    static let connectServer = text("action.connectServer", "Connect a Server")
    static let browseHelp = text("action.browseHelp", "Learn About Servers")
    static let tryAgain = text("action.tryAgain", "Try Again")
    static let connectionSettings = text("action.connectionSettings", "Review Connection Settings")
    static let openCertificateHelp = text("action.certificateHelp", "Open CA Installation Guide")
    static let more = text("action.more", "More")
    static let play = text("action.play", "Play")
    static let pause = text("action.pause", "Pause")
    static let previous = text("action.previous", "Previous Track")
    static let next = text("action.next", "Next Track")
    static let favorite = text("action.favorite", "Favorite")
    static let unfavorite = text("action.unfavorite", "Remove Favorite")
    static let volume = text("action.volume", "Volume")
    static let queue = text("player.queue", "Up Next")
    static let playingOn = text("player.playingOn", "Playing on")
    static let firstRunTitle = text("empty.title", "Your music, on this Mac")
    static let firstRunBody = text("empty.body", "Connect an OpenSubsonic server to browse your library and listen with native Mac controls.")
    static let firstRunFootnote = text("empty.footnote", "Dulcet keeps account credentials in the system Keychain and sends no analytics.")
    static let searchTitle = text("search.title", "Search")
    static let searchPrompt = text("search.prompt", "Artists, albums, and tracks")
    static let searchSummary = text("search.summary", "Local results appear immediately. Server matches refresh the same row instead of creating a duplicate.")
    static let bestMatches = text("search.bestMatches", "Best Matches")
    static let resultColumn = text("search.column.result", "Result")
    static let typeColumn = text("search.column.type", "Type")
    static let sourceColumn = text("search.column.source", "Source")
    static let local = text("search.source.local", "On this Mac")
    static let server = text("search.source.server", "Server")
    static let localAndServer = text("search.source.both", "Mac + Server")
    static let refreshed = text("search.refreshed", "Refreshed from server")
    static let album = text("search.kind.album", "Album")
    static let artist = text("search.kind.artist", "Artist")
    static let track = text("search.kind.track", "Track")
    static let tlsTitle = text("tls.title", "This server’s certificate isn’t trusted")
    static let tlsBody = text("tls.body", "Dulcet stopped before sending account credentials. There is no “continue anyway” option.")
    static let tlsWhy = text("tls.why", "Why macOS stopped the connection")
    static let tlsRemedyTitle = text("tls.remedyTitle", "How to reconnect safely")
    static let tlsRemedyBody = text("tls.remedyBody", "Fix or renew the server certificate. If your server uses a private certificate authority, install that CA in macOS at the operating-system level, then try again.")
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

    static func albumCount(_ count: Int) -> String {
        pluralized("library.albumCount", fallback: "%d albums", count: count)
    }

    static func trackCount(_ count: Int) -> String {
        pluralized("library.trackCount", fallback: "%d tracks", count: count)
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
        kind: String,
        source: String
    ) -> String {
        formatted(
            "search.result.accessibility",
            "%1$@, %2$@, %3$@, %4$@",
            title,
            subtitle,
            kind,
            source
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

enum DulcetLinks {
    static let certificateInstallationGuide = URL(
        string: "https://support.apple.com/guide/keychain-access/add-certificates-to-a-keychain-kyca2431/mac"
    )!
}
