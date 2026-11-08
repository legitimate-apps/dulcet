package com.legitimateapps.dulcet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.legitimateapps.dulcet.core.ListenNowShelf
import com.legitimateapps.dulcet.shared.R

/** Android section headings use sentence case independently of other native shells. */
@Composable
public fun ListenNowShelf.androidTitle(): String = stringResource(when (this) {
    ListenNowShelf.Recent -> R.string.listen_shelf_recent
    ListenNowShelf.RecentPlaylists -> R.string.listen_shelf_recent_playlists
    ListenNowShelf.Frequent -> R.string.listen_shelf_frequent
    ListenNowShelf.Newest -> R.string.listen_shelf_newest
    ListenNowShelf.Rediscover -> R.string.listen_shelf_rediscover
    ListenNowShelf.DailyMix -> R.string.listen_shelf_daily_mix
    ListenNowShelf.Favourites -> R.string.listen_shelf_favourites
    ListenNowShelf.ArtistStation -> R.string.listen_shelf_artists
    ListenNowShelf.GenreStation -> R.string.listen_shelf_genres
    ListenNowShelf.Random -> R.string.listen_shelf_random
})
