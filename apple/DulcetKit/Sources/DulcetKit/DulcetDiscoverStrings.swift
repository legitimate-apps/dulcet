import Foundation

extension DulcetStrings {
    static let discoverYourServer = dynamicText("discover.discoverYourServer", fallback: "Your server")
    static let discoverEverywhere = dynamicText("discover.discoverEverywhere", fallback: "Everywhere")
    static let discoverScope = dynamicText("discover.discoverScope", fallback: "Search scope")
    static let discoverSearchPrompt = dynamicText("discover.discoverSearchPrompt", fallback: "Search everywhere")
    static let discoverCredit = dynamicText("discover.discoverCredit", fallback: "Data from MusicBrainz and ListenBrainz")
    static let discoverNoResults = dynamicText("discover.discoverNoResults", fallback: "No results")
    static let discoverHeld = dynamicText("discover.discoverHeld", fallback: "In your library")
    static let discoverNotHeld = dynamicText("discover.discoverNotHeld", fallback: "Not in your library")
    static let discoverUnknown = dynamicText("discover.discoverUnknown", fallback: "Library match unknown")
    static let discoverOffline = dynamicText("discover.discoverOffline", fallback: "Offline")
    static let discoverCachedAnswer = dynamicText("discover.discoverCachedAnswer", fallback: "Cached answer")
    static let discoverCached = dynamicText("discover.discoverCached", fallback: "Cached")
    static let discoverReadFailed = dynamicText("discover.discoverReadFailed", fallback: "Could not refresh. Your last results are still here.")
    static let discoverWaiting = dynamicText("discover.discoverWaiting", fallback: "Waiting to sync")
    static let discoverNotSaved = dynamicText("discover.discoverNotSaved", fallback: "Not saved")
    static let discoverAddWish = dynamicText("discover.discoverAddWish", fallback: "Add wish")
    static let discoverRemoveWish = dynamicText("discover.discoverRemoveWish", fallback: "Remove wish")
    static let discoverOpenLibrary = dynamicText("discover.discoverOpenLibrary", fallback: "Open in Library")
    static let discoverReject = dynamicText("discover.discoverReject", fallback: "This isn’t it")
    static let discoverEdition = dynamicText("discover.discoverEdition", fallback: "Edition")
    static let discoverUnknownDate = dynamicText("discover.discoverUnknownDate", fallback: "Date unavailable")
    static let wishlist = dynamicText("discover.wishlist", fallback: "Wishlist")
    static let wishlistAdded = dynamicText("discover.wishlistAdded", fallback: "Added")
    static let wishlistUpdated = dynamicText("discover.wishlistUpdated", fallback: "Updated")
    static let wishlistNoteUpdated = dynamicText("discover.wishlistNoteUpdated", fallback: "Note updated")
    static let wishlistEmpty = dynamicText("discover.wishlistEmpty", fallback: "No wishes yet")
    static let wishlistLoading = dynamicText("discover.wishlistLoading", fallback: "Loading Wishlist")
    static let wishlistHistory = dynamicText("discover.wishlistHistory", fallback: "Include older arrivals")
    static let wishlistKind = dynamicText("discover.wishlistKind", fallback: "Kind")
    static let wishlistState = dynamicText("discover.wishlistState", fallback: "State")
    static let wishlistWanted = dynamicText("discover.wishlistWanted", fallback: "Wanted")
    static let wishlistRequested = dynamicText("discover.wishlistRequested", fallback: "Requested")
    static let wishlistSearching = dynamicText("discover.wishlistSearching", fallback: "Searching")
    static let wishlistUnavailable = dynamicText("discover.wishlistUnavailable", fallback: "Not found")
    static let wishlistArrived = dynamicText("discover.wishlistArrived", fallback: "Arrived")
    static let wishlistAll = dynamicText("discover.wishlistAll", fallback: "All")
    static let discoverLookupUnavailable = dynamicText("discover.discoverLookupUnavailable", fallback: "This item is not available in the current Library view. Open its album in Library to play it.")
    static func discoverPartial(_ count: Int, _ total: Int) -> String {
        dynamicFormatted("discover.discoverPartial", fallback: "%ld of %ld here", count, total)
    }
    static func discoverBusy(_ seconds: Int) -> String {
        dynamicFormatted("discover.discoverBusy", fallback: "Server is busy. Try again in %ld seconds.", seconds)
    }
    static func discoverAge(_ prefix: String, _ seconds: Int64) -> String {
        dynamicFormatted("discover.discoverAge", fallback: "%@ · %@ seconds old", prefix, String(seconds))
    }
    static func discoverAgeUnknown(_ prefix: String) -> String {
        dynamicFormatted("discover.discoverAgeUnknown", fallback: "%@ · age unavailable", prefix)
    }
    static func discoverOpenLink(_ relation: String) -> String {
        dynamicFormatted("discover.discoverOpenLink", fallback: "Open %@", relation)
    }
    static func discoverEditionTitle(_ date: String, _ count: Int?) -> String {
        dynamicFormatted("discover.discoverEditionTitle", fallback: "%@ · %@ tracks", date, count.map(String.init) ?? "?")
    }
    static let discoverTitle = dynamicText("discover.discoverTitle", fallback: "Discover")
    static let discoverSeeAll = dynamicText("discover.discoverSeeAll", fallback: "See all")
    static let discoverTrending = dynamicText("discover.discoverTrending", fallback: "Trending on ListenBrainz")
    static let discoverSuggestionsCredit = dynamicText("discover.discoverSuggestionsCredit", fallback: "Suggestions use ListenBrainz")
    static let discoverRecentWishes = dynamicText("discover.discoverRecentWishes", fallback: "Recent wishes")
    static let discoverSeeAllRecentWishes = dynamicText("discover.discoverSeeAllRecentWishes", fallback: "See all recent wishes")
    static let discoverRecentWishesEmpty = dynamicText("discover.discoverRecentWishesEmpty", fallback: "Your recent wishes will appear here.")
    static let discoverAgeUnavailable = dynamicText("discover.discoverAgeUnavailable", fallback: "Age unavailable")
    static let discoverUnavailable = dynamicText("discover.discoverUnavailable", fallback: "Discovery is temporarily unavailable.")
    static let discoverConnect = dynamicText("discover.discoverConnect", fallback: "Connect to your server to explore music.")
    static let discoverRetry = dynamicText("discover.discoverRetry", fallback: "Retry")
    static let discoverLoading = dynamicText("discover.discoverLoading", fallback: "Loading")
    static let discoverRecordingUnavailable = dynamicText("discover.discoverRecordingUnavailable", fallback: "Recording information unavailable")
    static let discoverPlaylistSearchPrompt = dynamicText("discover.discoverPlaylistSearchPrompt", fallback: "Search public playlists")
    static let discoverShowingAllPlaylists = dynamicText("discover.discoverShowingAllPlaylists", fallback: "Showing all public playlists")
    static let discoverLoadMore = dynamicText("discover.discoverLoadMore", fallback: "Load more")
    static let discoverDone = dynamicText("discover.discoverDone", fallback: "Done")
    static func discoverSeeAllNamed(_ title: String) -> String {
        dynamicFormatted("discover.discoverSeeAllNamed", fallback: "See all %@", title)
    }
    static func discoverCachedAnswerWithAge(_ age: String) -> String {
        dynamicFormatted("discover.discoverCachedAnswerWithAge", fallback: "Cached answer. %@", age)
    }
    static func discoverFetchedAt(_ date: String) -> String {
        dynamicFormatted("discover.discoverFetchedAt", fallback: "Fetched %@", date)
    }
    static func discoverRetryAt(_ date: String) -> String {
        dynamicFormatted("discover.discoverRetryAt", fallback: "Retry available at %@", date)
    }
    static func discoverWishedFor(_ title: String) -> String {
        dynamicFormatted("discover.discoverWishedFor", fallback: "Wished for %@", title)
    }
    static func discoverWishFor(_ title: String) -> String {
        dynamicFormatted("discover.discoverWishFor", fallback: "Wish for %@", title)
    }
    static func discoverShowingResults(_ query: String) -> String {
        dynamicFormatted("discover.discoverShowingResults", fallback: "Showing results for %@", query)
    }
    static func discoverSongPosition(_ position: Int) -> String {
        dynamicFormatted("discover.discoverSongPosition", fallback: "Song %ld", position)
    }
    static func discoverSongCount(_ count: Int) -> String {
        dynamicFormatted("discover.discoverSongCount", fallback: "%ld songs", count)
    }
}
