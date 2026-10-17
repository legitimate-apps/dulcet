import Foundation

extension DulcetStrings {
    static let libraryLocalOrder = dynamicText("libraryDepth.localOrder", fallback: "Sorted and filtered on this device")
    static let libraryLoadMore = dynamicText("libraryDepth.loadMore", fallback: "Load More")
    static func libraryLoadedScope(_ count: Int) -> String {
        dynamicFormatted("libraryDepth.loadedScope", fallback: "Showing %ld loaded items. Scroll or choose Load More to continue.", count)
    }

    static let libraryUndatedAlbums = dynamicText("libraryDepth.undatedAlbums", fallback: "Release Date uses the server’s year ordering. Albums without a year are available in Title order.")
    static let libraryPinned = dynamicText("libraryDepth.libraryPinned", fallback: "Pinned")
    static let libraryPin = dynamicText("libraryDepth.libraryPin", fallback: "Pin to Library")
    static let libraryUnpin = dynamicText("libraryDepth.libraryUnpin", fallback: "Unpin from Library")
    static let libraryPinsDevice = dynamicText("libraryDepth.libraryPinsDevice", fallback: "Pins are saved on this device for this account.")
    static let librarySongs = dynamicText("libraryDepth.librarySongs", fallback: "Songs")
    static let libraryServerSongs = dynamicText("libraryDepth.serverSongs", fallback: "Server Songs")
    static let libraryShuffleServer = dynamicText("libraryDepth.shuffleServer", fallback: "Shuffle Server Songs")
    static let libraryDownloaded = dynamicText("libraryDepth.libraryDownloaded", fallback: "Downloaded")
    static let libraryShuffleAll = dynamicText("libraryDepth.libraryShuffleAll", fallback: "Shuffle All")
    static let libraryReleaseDate = dynamicText("libraryDepth.libraryReleaseDate", fallback: "Release Date")
    static let librarySortDescending = dynamicText("libraryDepth.librarySortDescending", fallback: "Title, Z–A")
    static let libraryFilter = dynamicText("libraryDepth.libraryFilter", fallback: "Filter")
    static let libraryAll = dynamicText("libraryDepth.libraryAll", fallback: "All")
    static let libraryFilterFavorites = dynamicText("libraryDepth.libraryFilterFavorites", fallback: "Favorites")
    static let libraryDownloadedHint = dynamicText("libraryDepth.libraryDownloadedHint", fallback: "Music stored on this device, ready to play offline.")
    static let librarySongsHint = dynamicText("libraryDepth.librarySongsHint", fallback: "Songs in this account. Sorting and filtering apply to the loaded songs; scroll to load more.")
    static let libraryTopSongs = dynamicText("libraryDepth.libraryTopSongs", fallback: "Top Songs")
    static let libraryTopSongsHint = dynamicText("libraryDepth.libraryTopSongsHint", fallback: "Server top songs when available; otherwise your played and favorited songs on this device.")
    static let libraryDiscography = dynamicText("libraryDepth.libraryDiscography", fallback: "Discography · Newest First")
    static let libraryNoReleaseTypes = dynamicText("libraryDepth.libraryNoReleaseTypes", fallback: "This server does not provide release types. Albums are listed together using available release dates or years.")
    static let libraryServerCatalog = dynamicText("libraryDepth.libraryServerCatalog", fallback: "Server Catalog")
    static let libraryBrowseHint = dynamicText("libraryDepth.libraryBrowseHint", fallback: "Browse all music on your server. Use Add to Library to keep favorites in your personal collection.")
    static let libraryPersonalTitle = dynamicText("libraryDepth.libraryPersonalTitle", fallback: "My Library")
    static let libraryPersonalHint = dynamicText("libraryDepth.libraryPersonalHint", fallback: "Your personal collection from the server catalog.")
    static let libraryPersonalEmpty = dynamicText("libraryDepth.libraryPersonalEmpty", fallback: "My Library is your personal collection. Browse the server catalog, open an album, artist or song, then choose Add to Library. Adding an album also adds its songs. Downloads and pins are separate and stay on this device.")
    static let libraryShufflePersonal = dynamicText("libraryDepth.libraryShufflePersonal", fallback: "Shuffle My Library")
    static let searchRecentTitle = dynamicText("libraryDepth.searchRecentTitle", fallback: "Recent Searches")
    static let searchRecentClear = dynamicText("libraryDepth.searchRecentClear", fallback: "Clear")
    static let searchAllResults = dynamicText("libraryDepth.searchAllResults", fallback: "All Results")
    static let libraryEpsSingles = dynamicText("libraryDepth.libraryEpsSingles", fallback: "EPs & Singles")
    static let libraryCompilations = dynamicText("libraryDepth.libraryCompilations", fallback: "Compilations")
    static let libraryAppearsOn = dynamicText("libraryDepth.libraryAppearsOn", fallback: "Appears On")
    static let libraryDateUnknown = dynamicText("libraryDepth.libraryDateUnknown", fallback: "Release date unavailable")
    static let librarySortScope = dynamicText("libraryDepth.librarySortScope", fallback: "This order applies to the loaded items. Your server does not offer a global song sort or creation dates for this category.")
    static let libraryRandomQueueHint = dynamicText("libraryDepth.libraryRandomQueueHint", fallback: "Starts a queue of up to 500 random server songs. Offline, shuffles the songs available on this device.")
    static func libraryPinAccessibility(pinned: Bool, title: String) -> String {
        dynamicFormatted(pinned ? "libraryDepth.unpin.accessibility" : "libraryDepth.pin.accessibility",
                         fallback: pinned ? "Unpin %@" : "Pin %@", title)
    }
    static func librarySearchSeeAll(_ kind: String, count: Int) -> String {
        dynamicFormatted("libraryDepth.searchSeeAll", fallback: "%@ (%ld) · See All", kind, count)
    }
}
