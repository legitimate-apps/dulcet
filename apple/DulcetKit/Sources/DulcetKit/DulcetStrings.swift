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
    static let openCertificateHelp = text("action.certificateHelp", "Certificate Help")
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

    static func albumCount(_ count: Int) -> String {
        String(format: text("library.albumCount", "%d albums"), count)
    }

    static func trackCount(_ count: Int) -> String {
        String(format: text("library.trackCount", "%d tracks"), count)
    }

    static func discTitle(_ number: Int) -> String {
        String(format: text("album.discNumber", "Disc %d"), number)
    }

    static func serverStatus(_ name: String) -> String {
        String(format: text("status.server", "%@ · Online"), name)
    }

    static func serverConnectionFailed(_ name: String) -> String {
        String(format: text("status.serverConnectionFailed", "%@ · Connection failed"), name)
    }

    private static func text(
        _ key: StaticString,
        _ fallback: String.LocalizationValue
    ) -> String {
        String(localized: key, defaultValue: fallback, bundle: .module)
    }
}
