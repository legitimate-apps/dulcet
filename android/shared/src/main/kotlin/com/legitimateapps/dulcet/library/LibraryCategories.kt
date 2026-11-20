package com.legitimateapps.dulcet.library

/** Destination ownership shared by the phone and television shells. */
public object LibraryCategories {
    public fun rootIds(browse: Boolean, personalLibrary: Boolean): List<String> = if (browse) {
        listOf("discover", "artists", "albums", "songs", "genres")
    } else {
        listOf("playlists", "favourites", "downloaded", "wishlist", "pinned", "mostPlayed") +
            if (personalLibrary) listOf("artists", "albums", "songs") else emptyList()
    }

    /** The iPhone Library order, with the phone's genre, pin and history entry points. */
    public fun phoneRootIds(browse: Boolean, personalLibrary: Boolean): List<String> =
        phoneDestinations.filter { id ->
            when (id) {
                "discover" -> browse
                "artists", "albums", "songs", "genres" -> browse || personalLibrary
                else -> !browse
            }
        }

    private val phoneDestinations = listOf(
        "discover", "playlists", "artists", "albums", "songs", "genres", "downloaded",
        "favourites", "wishlist", "pinned", "recentlyPlayed", "mostPlayed",
    )

    /** Keep filter keys and ordering consistent; their labels remain platform resources. */
    public fun filterKeys(category: String): List<String> =
        listOf("all", "favorites") + if (category == "downloaded") emptyList() else listOf("downloaded")
}
