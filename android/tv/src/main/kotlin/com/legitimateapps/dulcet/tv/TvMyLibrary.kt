package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.library.canBeQueued
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.library.rememberLibraryCategory
import com.legitimateapps.dulcet.library.MyLibrarySort
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.MyLibraryMembership
import com.legitimateapps.dulcet.library.MyLibraryPublication
import com.legitimateapps.dulcet.library.MyLibrarySession
import com.legitimateapps.dulcet.library.MyLibrarySong
import com.legitimateapps.dulcet.library.MyLibrarySyncState
import com.legitimateapps.dulcet.library.membershipOf
import com.legitimateapps.dulcet.library.playTracks
import com.legitimateapps.dulcet.library.toLibraryItem
import com.legitimateapps.dulcet.library.toTrack
import com.legitimateapps.dulcet.library.toggleMembership
import com.legitimateapps.dulcet.playback.rememberPlaybackBinding
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons

/*
 * My Library on the TV (spec §29.4): what this account added on a server advertising `dulcetLibrary`,
 * beside Browse, the whole catalog. The home's section buttons open the Artists, Albums, Songs and
 * Playlists lists; its Recently Added rows play and open as the catalog home's do. Every control is a
 * focus target the D-pad reaches (tvFocus), and Add/Remove sits on the album and artist screens and on
 * each song's row. Without the extension these screens are never reachable: the top bar does not split.
 */

/** The My Library home: the ways into its sections, then Recently Added. */
@Composable
internal fun TvMyLibraryHome(
    account: SearchAccount,
    session: LibrarySession,
    myLibrary: MyLibrarySession,
    playback: AndroidPlaybackController?,
    navigator: TvNavigator,
) {
    EnterRoute()
    val publication by myLibrary.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    LazyColumn(
        Modifier.fillMaxSize().testTag("mylibrary.surface"),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            // The ways into the library's sections, one row of their own, with Refresh at its end.
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction(stringResource(R.string.tv_library_artists), "mylibrary.view.artists") { navigator.open(ROUTE_MY_ARTISTS) }
                TvAction(stringResource(R.string.tv_library_albums), "mylibrary.view.albums") { navigator.open(ROUTE_MY_ALBUMS) }
                TvAction(stringResource(SharedR.string.library_favourites_songs), "mylibrary.view.songs") { navigator.open(ROUTE_MY_SONGS) }
                TvAction(stringResource(R.string.tv_library_playlists), "mylibrary.view.playlists") { navigator.open(ROUTE_PLAYLISTS) }
                Spacer(Modifier.weight(1f))
                TvAction(stringResource(R.string.tv_refresh), "mylibrary.refresh", icon = DulcetIcons.Refresh) { myLibrary.refresh() }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvWishlistEntry(session.discoverWishlist, navigator)
                TvAction(stringResource(SharedR.string.library_downloaded), "mylibrary.view.downloaded") { navigator.open(ROUTE_DOWNLOADED) }
                TvAction(stringResource(SharedR.string.library_shuffle_all), "mylibrary.shuffleAll", enabled = publication?.songs?.any { it.toTrack().canBeQueued() } == true) {
                    playTracks(plays, account.providerInstanceId, publication?.songs.orEmpty().map(MyLibrarySong::toTrack), null, "My Library", true) { showNowPlaying(context) }
                }
            }
        }
        item { TvLibraryPins(account, session, playback, navigator); TvConnectionNotices(session, accountNotices = true) }
        val current = publication
        when {
            current == null || current.syncState == MyLibrarySyncState.Never || current.syncState == MyLibrarySyncState.Syncing ->
                item { TvStatement("…", "mylibrary.loading") }
            current.tvIsEmpty() -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvStatement(stringResource(SharedR.string.library_my_library_empty) + " " +
                        stringResource(SharedR.string.library_my_library_empty_hint), "mylibrary.empty")
                    TvAction(stringResource(SharedR.string.library_browse), "mylibrary.browse", default = true) {
                        navigator.showRoot(ROUTE_BROWSE)
                    }
                }
            }
            else -> {
                if (current.albums.isNotEmpty()) {
                    item {
                        Text(stringResource(SharedR.string.library_home_newest), style = MaterialTheme.typography.headlineSmall)
                    }
                    item {
                        LazyRow(Modifier.testTag("mylibrary.recent.albums"),
                            contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            itemsIndexed(current.albums.take(RECENT_COUNT), key = { _, album -> album.rawId }) { position, album ->
                                TvCard(account, album.toLibraryItem(), "mylibrary.recent.album.$position",
                                    default = position == 0) { navigator.openAlbum(album.rawId) }
                            }
                        }
                    }
                }
                if (current.songs.isNotEmpty()) {
                    item {
                        Text(stringResource(SharedR.string.library_favourites_songs), style = MaterialTheme.typography.headlineSmall)
                    }
                    val songs = current.songs.take(RECENT_COUNT)
                    val tracks = songs.map(MyLibrarySong::toTrack)
                    itemsIndexed(songs, key = { _, song -> song.rawId }) { position, song ->
                        val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Track, song.rawId)
                        TvTrackRow(
                            song.toTrack(),
                            position,
                            playing = false,
                            onPlay = {
                                playTracks(plays, account.providerInstanceId, tracks, song.rawId,
                                    context.getString(SharedR.string.library_home_newest)) { showNowPlaying(context) }
                            },
                            onUnavailable = {},
                            tagPrefix = "mylibrary.recent.track",
                            // With no albums above, the first song is where the remote lands.
                            default = position == 0 && current.albums.isEmpty(),
                            library = TvLibraryToggle(added = true) { myLibrary.remove(target) },
                        )
                    }
                }
            }
        }
    }
}

private const val RECENT_COUNT = 20

private fun MyLibraryPublication.tvIsEmpty(): Boolean =
    albums.isEmpty() && artists.isEmpty() && songs.isEmpty() &&
        (syncState == MyLibrarySyncState.Current || syncState == MyLibrarySyncState.Cached)

/** Every artist of the library, one grid of cards; an artist opens its screen. */
@Composable
internal fun TvMyArtistsGrid(account: SearchAccount, session: LibrarySession, myLibrary: MyLibrarySession, navigator: TvNavigator) {
    val controls = rememberLibraryCategory(session, "mylibrary.artists")
    LaunchedEffect(controls.sort) { myLibrary.setSort(if (controls.sort == "recentlyAdded") MyLibrarySort.RecentlyAdded else MyLibrarySort.Alphabetical) }
    EnterRoute()
    val publication by myLibrary.state.collectAsState()
    LazyVerticalGrid(GridCells.Adaptive(180.dp),
        Modifier.fillMaxSize().testTag("mylibrary.artists"),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvAction(stringResource(R.string.tv_back), "mylibrary.artists.back", onClick = navigator.back)
                }
                Text(stringResource(R.string.tv_library_artists), style = MaterialTheme.typography.displaySmall)
                TvLibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "recentlyAdded" to "Recently Added"))
            }
        }
        itemsIndexed(publication?.artists.orEmpty(), key = { _, artist -> artist.rawId }) { position, artist ->
            TvCard(account, artist.toLibraryItem(), "mylibrary.artists.item.$position", default = position == 0, width = null) {
                navigator.openArtist(artist.rawId)
            }
        }
    }
}

/** Every album of the library, one grid of cards; an album opens its screen. */
@Composable
internal fun TvMyAlbumsGrid(account: SearchAccount, session: LibrarySession, myLibrary: MyLibrarySession, navigator: TvNavigator) {
    val controls = rememberLibraryCategory(session, "mylibrary.albums")
    LaunchedEffect(controls.sort) { myLibrary.setSort(if (controls.sort == "recentlyAdded") MyLibrarySort.RecentlyAdded else MyLibrarySort.Alphabetical) }
    EnterRoute()
    val publication by myLibrary.state.collectAsState()
    LazyVerticalGrid(GridCells.Adaptive(180.dp),
        Modifier.fillMaxSize().testTag("mylibrary.albums"),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvAction(stringResource(R.string.tv_back), "mylibrary.albums.back", onClick = navigator.back)
                }
                Text(stringResource(R.string.tv_library_albums), style = MaterialTheme.typography.displaySmall)
                TvLibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "recentlyAdded" to "Recently Added") + listOf("artist" to "Artist", "year" to "Release Year (Newest First)"))
            }
        }
        itemsIndexed(publication?.albums.orEmpty(), key = { _, album -> album.rawId }) { position, album ->
            TvCard(account, album.toLibraryItem(), "mylibrary.albums.item.$position", default = position == 0, width = null) {
                navigator.openAlbum(album.rawId)
            }
        }
    }
}

/** Every song of the library, playing from the row pressed; its Remove is the row's last target. */
@Composable
internal fun TvMySongsList(
    account: SearchAccount,
    session: LibrarySession,
    myLibrary: MyLibrarySession,
    playback: AndroidPlaybackController?,
    playingRawId: String?,
    navigator: TvNavigator,
) {
    val controls = rememberLibraryCategory(session, "mylibrary.songs")
    LaunchedEffect(controls.sort) { myLibrary.setSort(if (controls.sort == "recentlyAdded") MyLibrarySort.RecentlyAdded else MyLibrarySort.Alphabetical) }
    EnterRoute()
    val publication by myLibrary.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    val songs = publication?.songs.orEmpty()
    val tracks = songs.map(MyLibrarySong::toTrack)
    val title = stringResource(SharedR.string.library_favourites_songs)
    LazyColumn(
        Modifier.fillMaxSize().testTag("mylibrary.songs"),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvAction(stringResource(R.string.tv_back), "mylibrary.songs.back", onClick = navigator.back)
                }
                Text(title, style = MaterialTheme.typography.displaySmall)
                TvLibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "recentlyAdded" to "Recently Added", "artist" to "Artist"))
                TvAction(stringResource(SharedR.string.library_shuffle_all), "mylibrary.songs.shuffleAll", enabled = tracks.any { it.canBeQueued() }) {
                    playTracks(plays, account.providerInstanceId, tracks, null, title, true) { showNowPlaying(context) }
                }
            }
        }
        itemsIndexed(songs, key = { _, song -> song.rawId }) { position, song ->
            val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Track, song.rawId)
            TvTrackRow(
                song.toTrack(),
                position,
                playing = song.rawId == playingRawId,
                onPlay = {
                    playTracks(plays, account.providerInstanceId, tracks, song.rawId, title) { showNowPlaying(context) }
                },
                onUnavailable = {},
                tagPrefix = "mylibrary.songs",
                byPosition = true,
                default = position == 0,
                library = TvLibraryToggle(added = true) { myLibrary.remove(target) },
            )
        }
    }
}

/** A row or header's Add/Remove, its own focus target; the check is in the library, the + adds. */
internal class TvLibraryToggle(val added: Boolean, val toggle: () -> Unit)

@Composable
internal fun TvLibraryButton(added: Boolean, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.tvFocus(tag).semantics { selected = added }) {
        Icon(if (added) DulcetIcons.Check else DulcetIcons.Add,
            stringResource(if (added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library))
    }
}

/**
 * A header's Add/Remove for an album or artist screen, or a row's (spec §29.4); null where the
 * control hides: no source on the screen, or a server without the extension.
 */
@Composable
internal fun TvMembershipAction(
    myLibrary: MyLibrarySession?,
    target: AndroidLibraryEntity,
): TvLibraryToggle? {
    val membership = membershipOf(myLibrary, target) ?: return null
    val session = myLibrary ?: return null
    return TvLibraryToggle(membership == MyLibraryMembership.Added) { session.toggleMembership(target, membership) }
}

private fun showNowPlaying(context: android.content.Context) =
    context.startActivity(com.legitimateapps.dulcet.playback.PlaybackIntents.showNowPlaying(context))
