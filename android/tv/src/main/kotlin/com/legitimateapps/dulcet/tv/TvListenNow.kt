package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.tv.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.playback.*
import com.legitimateapps.dulcet.search.SearchAccount

@Composable
internal fun TvListenNow(account: SearchAccount, session: LibrarySession, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    EnterRoute()
    val plays = rememberPlaybackBinding(playback)
    val state by remember(playback) { playback?.state ?: kotlinx.coroutines.flow.MutableStateFlow(AndroidPlaybackState()) }.collectAsState()
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().testTag("listenNow.surface"),
        contentPadding=PaddingValues(horizontal=56.dp, vertical=24.dp), verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item {
            Column {
                Text("Listen Now", style=MaterialTheme.typography.headlineLarge)
                Text("Find your next listen", style=MaterialTheme.typography.titleMedium)
                Button(onClick=session::refresh, modifier=Modifier.tvFocus("listenNow.refresh")) { Text("Refresh") }
            }
        }
        if (state.hasSession && playback != null) item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Continue Listening", style=MaterialTheme.typography.headlineSmall)
                Text(state.album ?: state.title)
                Button(onClick={ playback.play() }, modifier=Modifier.tvFocus("listenNow.resume")) { Text("Resume ${state.title}") }
            }
        }
        items(ListenNowShelf.entries,key={ it.wireName }) { shelf ->
            val surface = rememberSurface(session,shelf) { openListenNow(shelf) }
            val current by surface.state.collectAsState()
            val shown by rememberUpdatedState(current)
            if (current == null || current!!.items.isNotEmpty() || current!!.freshness != AndroidLibraryFreshness.Live) {
                Column(Modifier.fillMaxWidth().testTag("listenNow.${shelf.wireName}"), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(shelf.title,style=MaterialTheme.typography.headlineSmall)
                    val resources = libraryResources()
                    current?.let { publication ->
                        resources.freshnessLine(publication.freshness)?.let { Text(it) }
                        if (publication.freshness is AndroidLibraryFreshness.Unavailable) {
                            Text(resources.unavailableLine((publication.freshness as AndroidLibraryFreshness.Unavailable).reason,LibrarySubject.List))
                        }
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(20.dp),contentPadding=PaddingValues(vertical=8.dp)) {
                            itemsIndexed(publication.items,key={ _, item -> item::class.simpleName+item.rawId }) { position,item ->
                                // Keep the remote on Home until the person moves down. Several
                                // shelves can arrive together; none may steal focus from the bar.
                                TvCard(account,item,"listenNow.${shelf.wireName}.item.$position") {
                                    when(item) {
                                        is AndroidLibraryItem.Album -> navigator.openAlbum(item.rawId)
                                        is AndroidLibraryItem.Track -> playTracks(plays,account.providerInstanceId,shown?.items.orEmpty(),item.rawId,shelf.title) {
                                            context.startActivity(PlaybackIntents.showNowPlaying(context))
                                        }
                                        is AndroidLibraryItem.Playlist -> navigator.open("playlist:${item.rawId}")
                                        else -> Unit
                                    }
                                }
                            }
                        }
                        if(publication.freshness==AndroidLibraryFreshness.Loading) Text("Loading your music…")
                    }
                }
            }
        }
    }
}
