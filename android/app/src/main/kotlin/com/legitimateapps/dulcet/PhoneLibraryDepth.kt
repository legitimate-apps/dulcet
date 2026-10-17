package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR

@Composable
internal fun LibraryCategoryControls(state: LibraryCategoryState, showSort: Boolean = true,
                                     sorts: List<Pair<String, String>> = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)")) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (state.category.endsWith("playlists")) OutlinedTextField(state.filter.takeUnless { it == "all" }.orEmpty(), state::filter,
            label = { Text(stringResource(SharedR.string.library_filter)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("library.${state.category}.filter"))
        else Row(verticalAlignment = Alignment.CenterVertically) {
            for ((key, label) in listOf("all" to "All", "favorites" to "Favorites", "downloaded" to "Downloaded")) {
                FilterChip(selected = state.filter.ifBlank { "all" } == key, onClick = { state.filter(key) }, label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp).testTag("library.${state.category}.filter.$key"))
            }
        }
        if (showSort) Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.testTag("library.${state.category}.sort")) {
                Text(sorts.firstOrNull { it.first == state.sort }?.second ?: sorts.first().second)
            }
            DropdownMenu(menu, { menu = false }) {
                sorts.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) },
                    onClick = { state.choose(key); menu = false }, modifier = Modifier.testTag("library.${state.category}.sort.$key")) }
            }
        }
    }
}

@Composable
internal fun LibraryPinButton(session: LibrarySession, item: AndroidLibraryItem, tag: String) {
    if (item is AndroidLibraryItem.Playlist && item.local) return
    val depth by session.depth.collectAsState()
    val pinned = depth?.let { session.isPinned(item) } == true
    TextButton(onClick = { session.togglePin(item) }, modifier = Modifier.testTag(tag)) {
        Text(stringResource(if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin))
    }
}

/** Local reader views never imply that seen metadata is a full server catalog. */
@Composable
internal fun DeviceSongs(account: SearchAccount, session: LibrarySession, actions: PhoneActions,
                         downloaded: Boolean, playingRawId: String?) {
    val key = if (downloaded) "downloaded" else "songs"
    val controls = rememberLibraryCategory(session, key)
    val surface = rememberSurface(session, "$key:${controls.sort}:${controls.filter}") {
        if (downloaded) openDownloaded() else openSongs(controls.sort, controls.filter)
    }
    val publication by surface.state.collectAsState()
    val list = rememberLazyListState()
    ReportListViewport(surface, list, publication)
    val tracks = publication?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
    val shown = tracks
    val shuffle = rememberLibraryShuffle(session)
    val connection by session.connection.collectAsState()
    val offline = connection is LibraryConnectionState.Offline || connection is LibraryConnectionState.Saved
    val title = stringResource(if (downloaded) SharedR.string.library_downloaded else SharedR.string.search_group_songs)
    LazyColumn(Modifier.fillMaxSize().testTag("library.$key"), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column {
                StatementText(stringResource(if (downloaded) SharedR.string.library_downloaded_hint else SharedR.string.library_songs_scope), "library.$key.scope")
                LibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)",
                    "artist" to "Artist", "mostPlayed" to "Most Played"))
                if (!downloaded && !offline) StatementText(stringResource(SharedR.string.library_shuffle_server_hint), "library.$key.shuffle.scope")
                TextButton(onClick = {
                    if (downloaded || offline) actions.playTracksFrom(shown, null, title, true)
                    else shuffle.start { actions.playTracksFrom(it, null, title, true) }
                },
                    enabled = !shuffle.busy && (!downloaded && !offline || shown.any { it.playability != AndroidLibraryPlayability.UnavailableOffline }),
                    modifier = Modifier.testTag("library.$key.shuffleAll")) { Text(stringResource(if (offline && !downloaded) SharedR.string.library_shuffle_available else SharedR.string.library_shuffle_all)) }
                if (shuffle.failed) StatementText(stringResource(SharedR.string.library_shuffle_failed), "library.$key.shuffle.failed")
                publication?.let {
                    ListStatus(it, "library.$key", session::retry)
                    if (it.growsAtEnd()) TextButton(onClick = surface::loadMore, modifier = Modifier.testTag("library.$key.loadMore")) {
                        Text(stringResource(SharedR.string.library_load_more))
                    }
                }
            }
        }
        itemsIndexed(shown, key = { _, track -> track.rawId }) { position, track ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrackRow(track, position + 1, track.rawId == playingRawId, Modifier.weight(1f).testTag("library.$key.track.$position"),
                    onUnavailable = {}, onClick = { actions.playTracksFrom(shown, track.rawId, title, false) })
                LibraryPinButton(session, track, "library.$key.pin.$position")
            }
        }
    }
}

@Composable
internal fun LibraryPins(account: SearchAccount, session: LibrarySession, actions: PhoneActions) {
    val depth by session.depth.collectAsState()
    if (depth?.pinCount == 0 || depth == null) return
    val surface = rememberSurface(session, "pinned") { openPinned() }
    val publication by surface.state.collectAsState()
    val items = publication?.items.orEmpty()
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("library.pinned")) {
        Text(stringResource(SharedR.string.library_pinned), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(items, key = { _, item -> item.pinKind() + item.rawId }) { index, item ->
                Column(Modifier.width(220.dp)) {
                    ListItem(headlineContent = { Text(item.displayTitle()) }, supportingContent = { Text(item.subtitle().orEmpty()) },
                        modifier = Modifier.fillMaxWidth().testTag("library.pinned.item.$index").clickable {
                            when (item) {
                                is AndroidLibraryItem.Album -> actions.openAlbum(item.rawId)
                                is AndroidLibraryItem.Artist -> actions.openArtist(item.rawId)
                                is AndroidLibraryItem.Playlist -> actions.openPlaylist(item.rawId)
                                is AndroidLibraryItem.Track -> actions.playTracks(items, item.rawId, item.title.orEmpty())
                                else -> Unit
                            }
                        })
                    LibraryPinButton(session, item, "library.pinned.unpin.$index")
                }
            }
        }
    }
}

internal fun releaseGroupTitle(group: String): Int = when (group) {
    "epsSingles" -> SharedR.string.library_release_eps_singles
    "compilations" -> SharedR.string.library_release_compilations
    "appearsOn" -> SharedR.string.library_release_appears_on
    else -> SharedR.string.library_release_albums
}
