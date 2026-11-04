package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.ArtworkEntity

/**
 * Listen Now: a collapsing top app bar with the Account avatar, one resume card, and the shelves.
 * A shelf shows skeletons only while loading, hides itself once loaded empty, and hides when most
 * of its items already appeared on a shelf above it. Freshness is a page concern, never per shelf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneListenNow(account: SearchAccount, session: LibrarySession, actions: PhoneActions, playbackState: AndroidPlaybackState, playback: AndroidPlaybackController?) {
    // Home owns its independent shelf windows for its whole lifetime. A lazy row may be
    // empty or offscreen when a refresh starts; it must still receive the live read.
    val shelves = ListenNowShelf.entries.associateWith { shelf ->
        rememberSurface(session, shelf) { openListenNow(shelf) }
    }
    val states = shelves.mapValues { (_, surface) -> surface.state.collectAsState() }
    val loading = states.values.any { it.value.let { p -> p == null || p.freshness == AndroidLibraryFreshness.Loading } }
    // Shelf hygiene: keep shelves with content, drop loaded-empty ones, and drop a shelf when
    // more than half of its items were already shown on an earlier visible shelf.
    val seen = mutableSetOf<String>()
    val visible = ListenNowShelf.entries.filter { shelf ->
        val current = states.getValue(shelf).value
        current == null || current.freshness == AndroidLibraryFreshness.Loading ||
            keepListenNowShelf(current.items.map { it::class.simpleName + it.rawId }, seen,
                minimum = if (shelf == ListenNowShelf.Favourites) 2 else 3)
    }
    var userRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(loading, userRefreshing) { if (!loading && userRefreshing) { kotlinx.coroutines.delay(250); userRefreshing = false } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            PhoneRootTopBar("Listen Now", scrollBehavior)
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = userRefreshing && loading, onRefresh = { userRefreshing = true; session.refresh() }, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().testTag("listenNow.surface"), contentPadding = PaddingValues(vertical = 20.dp)) {
                if (playbackState.hasSession && playback != null) item {
                    Surface(
                        Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Continue listening", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Artwork(account, playbackState.artworkKey, playbackState.title, 72.dp, entity = ArtworkEntity.Song)
                                Column(Modifier.weight(1f)) {
                                    Text(playbackState.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    playbackState.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                }
                            }
                            playbackState.durationMilliseconds?.takeIf { it > 0 }?.let { duration ->
                                LinearProgressIndicator(progress = { (playbackState.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("listenNow.resume.progress"))
                            }
                            Button(onClick = { playback.play() }, modifier = Modifier.fillMaxWidth().testTag("listenNow.resume")) { Text("Resume") }
                        }
                    }
                }
                if (!playbackState.hasSession) {
                    states.getValue(ListenNowShelf.Recent).value?.items?.firstOrNull()?.let { history ->
                        item { HistoryResumeCard(account, session, actions, history) }
                    }
                }
                items(visible, key = { it.wireName }) { shelf ->
                    val current = states.getValue(shelf).value
                    Column(Modifier.fillMaxWidth().testTag("listenNow.${shelf.wireName}").padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(shelf.title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                            if (shelf == ListenNowShelf.Recent && !current?.items.isNullOrEmpty()) TextButton({ actions.openRoute("recentlyPlayed") },
                                Modifier.testTag("listenNow.recent.seeAll")) { Text(androidx.compose.ui.res.stringResource(com.legitimateapps.dulcet.shared.R.string.search_see_all)) }
                        }
                        val items = current?.items
                        if (items.isNullOrEmpty() && (current == null || current.freshness == AndroidLibraryFreshness.Loading)) {
                            RowPlaceholder(Modifier.testTag("listenNow.${shelf.wireName}.loading"), cardWidth = 160.dp)
                        } else {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                itemsIndexed(items.orEmpty(), key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                                    AlbumCard(account, item, Modifier.width(160.dp).testTag("listenNow.${shelf.wireName}.item.$position")) {
                                        when (item) {
                                            is AndroidLibraryItem.Album -> actions.openAlbum(item.rawId)
                                            is AndroidLibraryItem.Track -> actions.playTracks(items.orEmpty(), item.rawId, shelf.title)
                                            is AndroidLibraryItem.Playlist -> actions.openPlaylist(item.rawId)
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Only shelves that are shown may reserve items against later shelves. */
internal fun keepListenNowShelf(items: List<String>, seen: MutableSet<String>, minimum: Int = 3): Boolean {
    if (items.size < minimum) return false
    if (items.count { it in seen } * 2 > items.size) return false
    seen.addAll(items)
    return true
}

@Composable
private fun HistoryResumeCard(account: SearchAccount, session: LibrarySession, actions: PhoneActions, item: AndroidLibraryItem) {
    val album = item as? AndroidLibraryItem.Album
    val surface = if (album != null) rememberSurface(session, "resume:${album.rawId}") { openAlbum(album.rawId) } else null
    val publication = surface?.state?.collectAsState()?.value
    Surface(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recently played", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Artwork(account, album?.artworkKey ?: (item as? AndroidLibraryItem.Track)?.artworkKey, item.displayTitle(), 72.dp,
                    entity = if (item is AndroidLibraryItem.Track) ArtworkEntity.Song else ArtworkEntity.Album)
                Column(Modifier.weight(1f)) {
                    Text(item.displayTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    item.subtitle()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
            val playable = album == null || publication?.playableTracks(account.providerInstanceId)?.isNotEmpty() == true
            when {
                album != null && publication == null -> Unit
                !playable -> Text(androidx.compose.ui.res.stringResource(SharedR.string.library_not_available_offline),
                    Modifier.testTag("listenNow.resume.unavailable"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Button(onClick = {
                    when (item) {
                        is AndroidLibraryItem.Album -> publication?.let { actions.playAlbum(it, 0, false) }
                        is AndroidLibraryItem.Track -> actions.playTracks(listOf(item), item.rawId, "Recently Played")
                        is AndroidLibraryItem.Playlist -> actions.openPlaylist(item.rawId)
                        else -> Unit
                    }
                }, modifier = Modifier.fillMaxWidth().testTag("listenNow.resume")) {
                    Text(if (item is AndroidLibraryItem.Playlist) "Open playlist" else "Play")
                }
            }
        }
    }
}
