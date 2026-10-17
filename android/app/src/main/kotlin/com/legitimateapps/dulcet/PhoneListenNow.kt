package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.SearchAccount

@Composable
internal fun PhoneListenNow(account: SearchAccount, session: LibrarySession, actions: PhoneActions, playbackState: AndroidPlaybackState, playback: AndroidPlaybackController?) {
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("listenNow.surface"), contentPadding = PaddingValues(vertical=20.dp)) {
        item {
            Column(Modifier.padding(horizontal=16.dp).padding(bottom=24.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Listen Now", style=MaterialTheme.typography.headlineLarge, modifier=Modifier.semantics { heading() })
                Text("Find your next listen", style=MaterialTheme.typography.titleMedium)
                Text("Return to an album, revisit a favourite or explore your server.", style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick=session::refresh, modifier=Modifier.testTag("listenNow.refresh")) { Text("Refresh") }
            }
        }
        if (playbackState.hasSession && playback != null) item {
            Column(Modifier.padding(horizontal=16.dp).padding(bottom=24.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Continue Listening", style=MaterialTheme.typography.titleLarge, modifier=Modifier.semantics { heading() })
                Text(playbackState.album ?: playbackState.title)
                Text(playbackState.title, style=MaterialTheme.typography.bodyMedium)
                Button(onClick={ playback.play() }, modifier=Modifier.testTag("listenNow.resume")) { Text("Resume") }
            }
        }
        items(ListenNowShelf.entries, key={ it.wireName }) { shelf ->
            val surface = rememberSurface(session, shelf) { openListenNow(shelf) }
            val publication by surface.state.collectAsState()
            val current = publication
            if (current == null || current.items.isNotEmpty() || current.freshness != AndroidLibraryFreshness.Live) {
                Column(Modifier.fillMaxWidth().testTag("listenNow.${shelf.wireName}").padding(bottom=24.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.padding(horizontal=16.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(shelf.title, Modifier.weight(1f).semantics { heading() }, style=MaterialTheme.typography.titleLarge)
                        val first = current?.items?.filterIsInstance<AndroidLibraryItem.Track>()?.firstOrNull()
                        if (first != null) TextButton(onClick={ actions.playTracks(current.items,first.rawId,shelf.title) }) { Text("Play") }
                    }
                    if (current == null || current.freshness == AndroidLibraryFreshness.Loading) {
                        RowPlaceholder(Modifier.testTag("listenNow.${shelf.wireName}.loading"))
                    } else {
                        FreshnessLine(current.freshness,"listenNow.${shelf.wireName}",session::retry)
                        LazyRow(contentPadding=PaddingValues(horizontal=16.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(current.items, key={ _, item -> item::class.simpleName+item.rawId }) { position,item ->
                                AlbumCard(account,item,Modifier.width(160.dp).testTag("listenNow.${shelf.wireName}.item.$position")) {
                                    when(item) {
                                        is AndroidLibraryItem.Album -> actions.openAlbum(item.rawId)
                                        is AndroidLibraryItem.Track -> actions.playTracks(current.items,item.rawId,shelf.title)
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
