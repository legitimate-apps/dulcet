package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.downloads.*
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.library.canBeQueued
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.legitimateapps.dulcet.ui.ArtworkEntity

@Composable
internal fun LibraryCategoryControls(state: LibraryCategoryState, showSort: Boolean = true, showFilters: Boolean = true,
                                     sortControl: (@Composable () -> Unit)? = null,
                                     sorts: List<Pair<String, String>> = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)")) {
    var menu by remember { mutableStateOf(false) }
    val allFilters = if (showFilters) LibraryCategories.filterKeys(state.category).map { key ->
        key to when (key) { "all" -> "All"; "favorites" -> "Favorites"; else -> "Downloaded" }
    } else emptyList()
    val filters = if (state.category == "songs") allFilters.filterNot { it.first == "all" } else allFilters
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sortControl != null) item(key = "sort-control") { sortControl() }
        if (showSort) item(key = "sort-menu") { Box {
            AssistChip(onClick = { menu = true },
                label = { Text(sorts.firstOrNull { it.first == state.sort }?.second ?: sorts.first().second, maxLines = 1, softWrap = false) },
                trailingIcon = { Icon(com.legitimateapps.dulcet.ui.DulcetIcons.ExpandMore, null) },
                modifier = Modifier.testTag("library.${state.category}.sort"))
            DropdownMenu(menu, { menu = false }) {
                sorts.forEach { (key, title) -> PhoneMenuItem(text = { Text(title) },
                    leadingIcon = { if (key == state.sort) Icon(com.legitimateapps.dulcet.ui.DulcetIcons.Check, null) },
                    onClick = { state.choose(key); menu = false }, modifier = Modifier.testTag("library.${state.category}.sort.$key")) }
            }
        } }
        items(filters, key = { it.first }) { (key, label) ->
            val active = state.filter.ifBlank { "all" } == key
            FilterChip(selected = active, onClick = { state.filterChoice(if (state.category == "songs" && active) "all" else key) },
                label = { Text(label, maxLines = 1, softWrap = false) },
                leadingIcon = { if (active) Icon(com.legitimateapps.dulcet.ui.DulcetIcons.Check, null, Modifier.size(18.dp)) },
                modifier = Modifier.testTag("library.${state.category}.filter.$key"))
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
    val context = LocalContext.current
    val requests = remember(context) { AccountDownloadRequests.of(context) }
    val downloads = rememberDownloadStatuses(requests)
    val depth by session.depth.collectAsState()
    val shown = if (downloaded) projectDownloadedTracks(tracks, controls.sort, controls.filter) else tracks
    val shuffle = rememberLibraryShuffle(session)
    val connection by session.connection.collectAsState()
    val offline = connection is LibraryConnectionState.Offline || connection is LibraryConnectionState.Saved
    val title = stringResource(if (downloaded) SharedR.string.library_downloads_heading else SharedR.string.search_group_songs)
    if (downloaded && controls.filter in setOf("", "all") && publication?.freshness != com.legitimateapps.dulcet.core.AndroidLibraryFreshness.Loading && publication?.freshness !is com.legitimateapps.dulcet.core.AndroidLibraryFreshness.Unavailable && publication?.itemsState == com.legitimateapps.dulcet.core.AndroidLibraryItemsState.Present && tracks.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                EmptyState(com.legitimateapps.dulcet.ui.DulcetIcons.Download, stringResource(R.string.library_empty_downloads),
                    body = stringResource(R.string.library_empty_downloads_body), actionLabel = stringResource(R.string.library_empty_downloads_action),
                    onAction = { actions.openRoute("browse") }, actionTag = "library.downloaded.browse", modifier = Modifier.testTag("library.downloaded.empty"), fillsPage = true)
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("library.$key"), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column {
                LibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)",
                    "artist" to "Artist", "mostPlayed" to "Most played"))
                if (shown.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    FilledTonalButton(onClick = {
                        if (downloaded || offline) actions.playTracksFrom(shown, null, title, true)
                        else shuffle.start { actions.playTracksFrom(it, null, title, true) }
                    },
                        enabled = !shuffle.busy && (!downloaded && !offline || shown.any { it.canBeQueued() }),
                        modifier = Modifier.testTag("library.$key.shuffleAll")) {
                        Icon(com.legitimateapps.dulcet.ui.DulcetIcons.Shuffle, contentDescription = null, Modifier.size(com.legitimateapps.dulcet.ui.textScaledIconSize(24.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (offline && !downloaded) SharedR.string.library_shuffle_available else SharedR.string.library_shuffle_all))
                    }
                }
                if (shuffle.failed) StatementText(stringResource(SharedR.string.library_shuffle_failed), "library.$key.shuffle.failed")
                publication?.let { LibraryListStatus(if (downloaded) it.copy(items = shown) else it, "library.$key", session::retry, controls) }
            }
        }
        itemsIndexed(shown, key = { _, track -> track.rawId }) { position, track ->
            TrackRow(track, position + 1, track.rawId == playingRawId, Modifier.fillMaxWidth().testTag("library.$key.track.$position"),
                artwork = { Artwork(account, track.artworkKey, track.title.orEmpty(), 56.dp, entity = ArtworkEntity.Song) },
                onUnavailable = {},
                onFavourite = { session.toggleFavourite(com.legitimateapps.dulcet.core.AndroidLibraryEntity(com.legitimateapps.dulcet.core.AndroidLibraryEntityKind.Track, track.rawId)) },
                favouriteTag = "library.$key.track.$position.favourite", onAddToPlaylist = { actions.addToPlaylist(PlaylistAddition.Songs(listOf(track.rawId), track.title.orEmpty(), track.artworkKey)) },
                download = TrackDownload(downloads[track.rawId], onDownload = { requests.download(listOf(track)) }.takeIf { track.downloadItem() != null }, onRemove = { requests.remove(listOf(track.rawId)) }),
                pin = TrackPin(depth?.let { session.isPinned(track) } == true, "library.$key.pin.$position") { session.togglePin(track) },
                queue = actions.trackQueue(track, null),
                onClick = { actions.playTracksFrom(shown, track.rawId, title, false) })
        }
        if (tracks.isNotEmpty() && publication?.growsAtEnd() == true) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.library_filter_loaded_songs),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = surface::loadMore, modifier = Modifier.testTag("library.$key.loadMore")) {
                    Text(stringResource(SharedR.string.library_load_more))
                }
            }
        }
    }
}

/** Keep the publication alive outside lazy content that can measure zero before it arrives. */
@Composable
internal fun rememberPinnedItems(session: LibrarySession): List<AndroidLibraryItem> {
    val depth by session.depth.collectAsState()
    if (depth?.pinCount == 0 || depth == null) return emptyList()
    val surface = rememberSurface(session, "pinned") { openPinned() }
    val publication by surface.state.collectAsState()
    return publication?.items.orEmpty()
}

@Composable
internal fun LibraryPins(account: SearchAccount, session: LibrarySession, actions: PhoneActions) {
    LibraryPins(account, session, actions, rememberPinnedItems(session))
}

@Composable
internal fun LibraryPins(account: SearchAccount, session: LibrarySession, actions: PhoneActions, items: List<AndroidLibraryItem>) {
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
