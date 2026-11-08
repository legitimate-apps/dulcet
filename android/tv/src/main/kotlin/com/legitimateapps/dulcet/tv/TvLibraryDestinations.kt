package com.legitimateapps.dulcet.tv

import androidx.compose.ui.graphics.vector.ImageVector
import com.legitimateapps.dulcet.library.LibraryCategories
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.shared.R as SharedR

internal data class TvRootDestination(val key: String, val route: String, val label: Int, val icon: ImageVector)

/** Both TV roots consume the same ownership model as the phone. Unsupported routes stay absent. */
internal fun tvRootDestinations(browse: Boolean, personal: Boolean, pins: Boolean): List<TvRootDestination> =
    LibraryCategories.rootIds(browse, personal).mapNotNull { key ->
        when (key) {
            "playlists" -> TvRootDestination(key, ROUTE_PLAYLISTS, R.string.tv_library_playlists, DulcetIcons.PlaylistPlay)
            "favourites" -> TvRootDestination(key, ROUTE_FAVOURITES, R.string.tv_favorites, DulcetIcons.Favourite)
            "downloaded" -> TvRootDestination(key, ROUTE_DOWNLOADED, SharedR.string.library_downloads_heading, DulcetIcons.Download)
            "mostPlayed" -> TvRootDestination(key, ROUTE_MOST_PLAYED, R.string.tv_most_played, TvRowIcons.TrendingUp)
            "pinned" -> TvRootDestination(key, ROUTE_PINNED, SharedR.string.library_pinned, DulcetIcons.PushPin)
            "artists" -> TvRootDestination(key, if (personal && !browse) ROUTE_MY_ARTISTS else ROUTE_ARTISTS, R.string.tv_library_artists, DulcetIcons.Person)
            "albums" -> TvRootDestination(key, if (personal && !browse) ROUTE_MY_ALBUMS else ROUTE_ALBUMS, R.string.tv_library_albums, DulcetIcons.Album)
            "songs" -> TvRootDestination(key, if (personal && !browse) ROUTE_MY_SONGS else ROUTE_SONGS, SharedR.string.search_group_songs, DulcetIcons.MusicNote)
            "genres" -> TvRootDestination(key, ROUTE_GENRES, SharedR.string.library_genres, DulcetIcons.Genre)
            else -> null
        }
    }
