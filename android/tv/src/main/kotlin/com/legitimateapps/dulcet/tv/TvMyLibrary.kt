package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.library.canBeQueued
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
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
 * beside Browse, the whole catalog. The home is the phone's list order as large list rows — Playlists,
 * Favorites, Downloaded, Most Played, then the library's Artists, Albums and Songs — above one
 * Recently Added row. Add/Remove sits on the album and artist screens' More menus and on each row's
 * long-Select menu. Without the extension these screens are never reachable: the top bar's Library
 * root is the catalog's.
 */

/**
 * The My Library home: the ways into its sections on the left, Recently Added on the right. One
 * initial focus, as on the other roots: the left pane's first section, always composed, so it never
 * depends on whether the library's publication or its empty state arrives first. The right pane
 * claims nothing; RIGHT from the pane reaches it.
 */
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
    val depth by session.depth.collectAsState()
    val destinations = tvRootDestinations(browse = false, personal = true, pins = (depth?.pinCount ?: 0) > 0)
    val context = LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    Row(Modifier.fillMaxSize().testTag("mylibrary.surface")) {
        LazyColumn(
            Modifier.width(300.dp).fillMaxHeight(),
            contentPadding = PaddingValues(start = 56.dp, top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(stringResource(R.string.tv_nav_library), style = MaterialTheme.typography.headlineMedium) }
            itemsIndexed(destinations) { index, destination ->
                TvHomeEntry(stringResource(destination.label), "mylibrary.view.${destination.key}", destination.icon,
                    default = index == 0) { navigator.open(destination.route) }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 24.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TvLibraryPins(account, session, playback, navigator); TvConnectionNotices(session, accountNotices = true) }
            val current = publication
            when {
                current == null || ((current.syncState == MyLibrarySyncState.Never || current.syncState == MyLibrarySyncState.Syncing) &&
                    current.albums.isEmpty() && current.artists.isEmpty() && current.songs.isEmpty()) ->
                    item { TvShelfSkeleton("mylibrary.loading") }
                current.tvIsEmpty() -> item {
                    TvEmptyState(
                        stringResource(SharedR.string.library_my_library_empty),
                        stringResource(SharedR.string.library_my_library_empty_hint),
                        "mylibrary.empty",
                        actionLabel = stringResource(SharedR.string.library_browse),
                        actionTag = "mylibrary.browse",
                        actionDefault = false,
                    ) { navigator.showRoot(ROUTE_BROWSE) }
                }
                else -> {
                    if (current.albums.isNotEmpty()) {
                        item {
                            Text(stringResource(SharedR.string.library_home_newest), style = MaterialTheme.typography.headlineSmall)
                        }
                        item {
                            LazyRow(Modifier.tvBleed(24.dp, 56.dp).testTag("mylibrary.recent.albums"),
                                contentPadding = PaddingValues(start = 24.dp, end = 56.dp, top = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                itemsIndexed(current.albums.take(RECENT_COUNT), key = { _, album -> album.rawId }) { position, album ->
                                    TvContextCard(account, session, playback, album.toLibraryItem(), "mylibrary.recent.album.$position") {
                                        navigator.openAlbum(album.rawId)
                                    }
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
                                account = account,
                                queue = tvTrackAddition(context, playback, account.providerInstanceId, song.toTrack(), null),
                                session = session,
                                library = TvLibraryToggle(added = true) { myLibrary.remove(target) },
                            )
                        }
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
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tv_library_artists), style = MaterialTheme.typography.displaySmall)
                TvLibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "recentlyAdded" to "Recently Added"))
            }
        }
        itemsIndexed(publication?.artists.orEmpty(), key = { _, artist -> artist.rawId }) { position, artist ->
            TvContextCard(account, session, null, artist.toLibraryItem(), "mylibrary.artists.item.$position", default = position == 0, width = null) {
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
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tv_library_albums), style = MaterialTheme.typography.displaySmall)
                TvLibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "recentlyAdded" to "Recently Added", "artist" to "Artist", "year" to "Release Year (Newest First)"))
            }
        }
        itemsIndexed(publication?.albums.orEmpty(), key = { _, album -> album.rawId }) { position, album ->
            TvContextCard(account, session, null, album.toLibraryItem(), "mylibrary.albums.item.$position", default = position == 0, width = null) {
                navigator.openAlbum(album.rawId)
            }
        }
    }
}

/** Every song of the library, playing from the row pressed; its Remove is in the row's long-Select menu. */
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
    val context = LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    val songs = publication?.songs.orEmpty()
    val tracks = songs.map(MyLibrarySong::toTrack)
    val title = stringResource(SharedR.string.library_favourites_songs)
    LazyColumn(
        Modifier.fillMaxSize().testTag("mylibrary.songs"),
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.displaySmall)
                if (songs.isNotEmpty()) {
                    TvLibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "recentlyAdded" to "Recently Added", "artist" to "Artist"))
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
                byPosition = false,
                default = position == 0,
                account = account,
                queue = tvTrackAddition(context, playback, account.providerInstanceId, song.toTrack(), null),
                session = session,
                library = TvLibraryToggle(added = true) { myLibrary.remove(target) },
            )
        }
    }
}

/** A row or header's Add/Remove: the check is in the library, the + adds. */
internal class TvLibraryToggle(val added: Boolean, val toggle: () -> Unit)

/** A header's Add/Remove for an album or artist screen, or a row's (spec §29.4); null where the
 * control hides: no source on the screen, or a server without the extension. */
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
