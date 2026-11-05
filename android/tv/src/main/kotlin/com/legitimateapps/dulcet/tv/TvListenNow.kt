package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.tv.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.playback.*
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.androidTitle
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity

@Composable
internal fun TvListenNow(account: SearchAccount, session: LibrarySession, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    EnterRoute()
    val plays = rememberPlaybackBinding(playback)
    val state by remember(playback) { playback?.state ?: kotlinx.coroutines.flow.MutableStateFlow(AndroidPlaybackState()) }.collectAsState()
    val context = LocalContext.current
    val shelves = ListenNowShelf.entries.associateWith { shelf -> rememberSurface(session, shelf) { openListenNow(shelf) } }
    val publications = shelves.mapValues { it.value.state.collectAsState().value }
    val connection by session.connection.collectAsState()
    // A refresh that failed leaves the shelf's old items on screen with Cached(Failed): the server went away
    // mid-use while the platform still reports a network. That is a failure too, said once for the page.
    val firstFailure = ListenNowShelf.entries.firstOrNull { shelf ->
        val freshness = publications[shelf]?.freshness
        freshness is AndroidLibraryFreshness.Unavailable || publications[shelf]?.itemsState == AndroidLibraryItemsState.Unavailable ||
            (freshness as? AndroidLibraryFreshness.Cached)?.reason.let {
                it is com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.Failed ||
                    it == com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.InternalFailure
            }
    }
    // A shelf with nothing in it is a bare heading over blank space: only its failure line is worth the room,
    // and only while no account notice already says why.
    fun shelfShown(shelf: ListenNowShelf): Boolean {
        val current = publications[shelf]
        return current == null || current.items.isNotEmpty() || current.freshness == AndroidLibraryFreshness.Loading ||
            (shelf == firstFailure && !connection.hasConnectionNotice())
    }
    val nothingToShow = connection.hasConnectionNotice() && ListenNowShelf.entries.none(::shelfShown)
    var menu by remember { mutableStateOf(false) }
    if (menu) TvQueueMenu("listenNow.menu", "Listen Now",
        listOf(TvQueueChoice("Refresh", "listenNow.refresh", session::refresh)), "Cancel") { menu = false }
    LazyColumn(Modifier.fillMaxSize().testTag("listenNow.surface"),
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = TV_PAGE_BOTTOM)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Listen Now", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { menu = true }, modifier = Modifier.tvFocus("listenNow.more")) {
                    Icon(DulcetIcons.MoreVert, "More options")
                }
            }
        }
        item { TvConnectionNotices(session, accountNotices = false) }
        if (state.hasSession && playback != null) item {
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TvArtwork(account, state.artworkKey, 120, ArtworkEntity.Song)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Continue listening", style = MaterialTheme.typography.headlineSmall)
                    Text(state.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    state.artist?.let { Text(it, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val duration = state.durationMilliseconds
                    if (duration != null && duration > 0) {
                        Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth((state.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f))
                                .fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                        }
                    }
                    TvAction("Resume", "listenNow.resume", icon = DulcetIcons.Play) { playback.play() }
                }
            }
        }
        if (nothingToShow) item { TvStatement(stringResource(R.string.tv_listen_now_nothing_saved), "listenNow.nothingSaved") }
        items(ListenNowShelf.entries, key = { it.wireName }) { shelf ->
            val current = publications[shelf]
            val shelfTitle = shelf.androidTitle()
            val shown by rememberUpdatedState(current)
            if (shelfShown(shelf)) {
                Column(Modifier.fillMaxWidth().testTag("listenNow.${shelf.wireName}").padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(shelfTitle, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        if (shelf == ListenNowShelf.Recent || shelf == ListenNowShelf.Newest) {
                            TvAction("See all", "listenNow.${shelf.wireName}.seeAll", outlined = true) {
                                navigator.open(if (shelf == ListenNowShelf.Recent) ROUTE_RECENTLY_PLAYED else ROUTE_RECENTLY_ADDED)
                            }
                        }
                    }
                    if (current == null || current.items.isEmpty() && current.freshness == AndroidLibraryFreshness.Loading) {
                        TvShelfSkeleton("listenNow.${shelf.wireName}.loading")
                    } else {
                        if (current.freshness == AndroidLibraryFreshness.Loading ||
                            !connection.hasConnectionNotice() && shelf == firstFailure) {
                            TvListStatus(current, "listenNow.${shelf.wireName}", session::retry)
                        }
                        LazyRow(Modifier.tvBleed(56.dp, 56.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
                            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 20.dp)) {
                            itemsIndexed(current.items, key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                                // Keep the remote on Home's tab until the person moves down. Several
                                // shelves can arrive together; none may steal focus from the bar.
                                TvContextCard(account, session, playback, item, "listenNow.${shelf.wireName}.item.$position") {
                                    when (item) {
                                        is AndroidLibraryItem.Album -> navigator.openAlbum(item.rawId)
                                        is AndroidLibraryItem.Track -> playTracks(plays, account.providerInstanceId, shown?.items.orEmpty(), item.rawId, shelfTitle) {
                                            context.startActivity(PlaybackIntents.showNowPlaying(context))
                                        }
                                        is AndroidLibraryItem.Playlist -> navigator.open("playlist:${item.rawId}")
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
