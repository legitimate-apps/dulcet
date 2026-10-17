package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.rememberPlaybackBinding
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR

@Composable
internal fun TvLibraryCategoryControls(state: LibraryCategoryState, showSort: Boolean = true,
                                       sorts: List<Pair<String, String>> = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)")) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.category.endsWith("playlists")) androidx.compose.foundation.text.BasicTextField(
            value = state.filter.takeUnless { it == "all" }.orEmpty(), onValueChange = state::filter,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(12.dp).tvFocus("library.${state.category}.filter"),
            decorationBox = { inner -> Column { Text(stringResource(SharedR.string.library_filter)); inner() } }, singleLine = true)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(listOf("all" to "All", "favorites" to "Favorites", "downloaded" to "Downloaded")) { _, (key, title) ->
                TvAction(title, "library.${state.category}.filter.$key", icon = if (state.filter.ifBlank { "all" } == key) com.legitimateapps.dulcet.ui.DulcetIcons.Check else null) { state.filter(key) }
            }
        }
        if (showSort) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(sorts) { _, (key, title) ->
                TvAction(title, "library.${state.category}.sort.$key", icon = if (state.sort == key) com.legitimateapps.dulcet.ui.DulcetIcons.Check else null) { state.choose(key) }
            }
        }
    }
}

@Composable
internal fun TvPinButton(session: LibrarySession, item: AndroidLibraryItem, tag: String) {
    if (item is AndroidLibraryItem.Playlist && item.local) return
    val depth by session.depth.collectAsState()
    val pinned = depth?.let { session.isPinned(item) } == true
    TvAction(stringResource(if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin), tag) { session.togglePin(item) }
}

@Composable
internal fun TvLibraryPins(account: SearchAccount, session: LibrarySession, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    val depth by session.depth.collectAsState()
    if (depth == null || depth?.pinCount == 0) return
    val surface = rememberSurface(session, "pinned") { openPinned() }
    val publication by surface.state.collectAsState()
    val items = publication?.items.orEmpty()
    if (items.isEmpty()) return
    val context = LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(SharedR.string.library_pinned), style = MaterialTheme.typography.headlineSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(items, key = { _, item -> item.pinKind() + item.rawId }) { index, item ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvCard(account, item, "library.pinned.item.$index") {
                        when (item) {
                            is AndroidLibraryItem.Album -> navigator.openAlbum(item.rawId)
                            is AndroidLibraryItem.Artist -> navigator.openArtist(item.rawId)
                            is AndroidLibraryItem.Playlist -> navigator.open("playlist:" + item.rawId)
                            is AndroidLibraryItem.Track -> playTracks(plays, account.providerInstanceId, items, item.rawId, item.title.orEmpty()) {
                                context.startActivity(PlaybackIntents.showNowPlaying(context))
                            }
                            else -> Unit
                        }
                    }
                    TvPinButton(session, item, "library.pinned.unpin.$index")
                }
            }
        }
    }
}

@Composable
internal fun TvDeviceSongs(account: SearchAccount, session: LibrarySession, playback: AndroidPlaybackController?,
                           navigator: TvNavigator, downloaded: Boolean, playingRawId: String?) {
    EnterRoute()
    val key = if (downloaded) "downloaded" else "songs"
    val controls = rememberLibraryCategory(session, key)
    val surface = rememberSurface(session, "$key:${controls.sort}:${controls.filter}") {
        if (downloaded) openDownloaded() else openSongs(controls.sort, controls.filter)
    }
    val publication by surface.state.collectAsState()
    val context = LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    val tracks = publication?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
    val shuffle = rememberLibraryShuffle(session)
    val connection by session.connection.collectAsState()
    val offline = connection is LibraryConnectionState.Offline || connection is LibraryConnectionState.Saved
    val title = stringResource(if (downloaded) SharedR.string.library_downloaded else SharedR.string.search_group_songs)
    LazyColumn(Modifier.fillMaxSize().testTag("library.$key"), contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction(stringResource(R.string.tv_back), "library.$key.back", onClick = navigator.back)
                Text(title, style = MaterialTheme.typography.displaySmall)
                TvStatement(stringResource(if (downloaded) SharedR.string.library_downloaded_hint else SharedR.string.library_songs_scope), "library.$key.scope")
                TvConnectionNotices(session, accountNotices = false)
                TvLibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)", "artist" to "Artist", "mostPlayed" to "Most Played"))
                if (!downloaded && !offline) TvStatement(stringResource(SharedR.string.library_shuffle_server_hint), "library.$key.shuffle.scope")
                TvAction(stringResource(if (offline && !downloaded) SharedR.string.library_shuffle_available else SharedR.string.library_shuffle_all), "library.$key.shuffleAll",
                    enabled = !shuffle.busy && (!downloaded && !offline || tracks.any { it.playability != AndroidLibraryPlayability.UnavailableOffline })) {
                    if (downloaded || offline) playTracks(plays, account.providerInstanceId, tracks, null, title, true) { context.startActivity(PlaybackIntents.showNowPlaying(context)) }
                    else shuffle.start { playTracks(plays, account.providerInstanceId, it, null, title, true) { context.startActivity(PlaybackIntents.showNowPlaying(context)) } }
                }
                if (shuffle.failed) TvStatement(stringResource(SharedR.string.library_shuffle_failed), "library.$key.shuffle.failed")
                publication?.let { TvListStatus(it, "library.$key", session::retry) }
            }
        }
        itemsIndexed(tracks, key = { _, track -> track.rawId }) { position, track ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TvTrackRow(track, position, track.rawId == playingRawId, onPlay = {
                        playTracks(plays, account.providerInstanceId, tracks, track.rawId, title) { context.startActivity(PlaybackIntents.showNowPlaying(context)) }
                    }, onUnavailable = {}, tagPrefix = "library.$key.track", byPosition = true, default = position == 0)
                }
                TvPinButton(session, track, "library.$key.pin.$position")
            }
        }
        if (publication?.growsAtEnd() == true) item {
            TvAction(stringResource(SharedR.string.library_load_more), "library.$key.loadMore", onClick = surface::loadMore)
        }
    }
}

internal fun tvReleaseGroupTitle(group: String): Int = when (group) {
    "epsSingles" -> SharedR.string.library_release_eps_singles
    "compilations" -> SharedR.string.library_release_compilations
    "appearsOn" -> SharedR.string.library_release_appears_on
    else -> SharedR.string.library_release_albums
}
