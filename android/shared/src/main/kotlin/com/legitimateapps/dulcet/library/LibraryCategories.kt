package com.legitimateapps.dulcet.library

/** Destination ownership shared by the phone and television shells. */
public object LibraryCategories {
    public fun rootIds(browse: Boolean, personalLibrary: Boolean): List<String> = if (browse) {
        listOf("discover", "artists", "albums", "songs", "genres")
    } else {
        listOf("playlists", "favourites", "downloaded", "wishlist", "pinned", "mostPlayed") +
            if (personalLibrary) listOf("artists", "albums", "songs") else emptyList()
    }

    /** Keep filter keys and ordering consistent; their labels remain platform resources. */
    public fun filterKeys(category: String): List<String> =
        listOf("all", "favorites") + if (category == "downloaded") emptyList() else listOf("downloaded")
}
