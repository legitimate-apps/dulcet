package com.legitimateapps.dulcet

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueSource
import com.legitimateapps.dulcet.library.LibraryAlbum
import com.legitimateapps.dulcet.library.LibraryArtist
import com.legitimateapps.dulcet.library.LibraryIndex
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.playback.PlayRequest
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.rememberPlaybackController
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.ui.DulcetIcons
import kotlinx.coroutines.flow.MutableStateFlow

/** Play and show-player requests delivered to the activity by intent. Consumed once each. */
internal class PhonePlaybackRequests {
    val pending = MutableStateFlow<PlayRequest?>(null)
    val show = MutableStateFlow(0)

    fun accept(intent: Intent?) {
        // The launcher activity is exported; the playback alias is not. An explicit intent from
        // another application can name the activity but never the alias, so only the alias counts.
        if (intent?.component?.className != PLAYBACK_ENTRY_ALIAS) return
        val request = PlaybackIntents.playRequest(intent)
        if (request != null) pending.value = request
        if (request != null || intent?.action == PlaybackIntents.ACTION_SHOW_NOW_PLAYING) show.value += 1
    }
}

internal const val PLAYBACK_ENTRY_ALIAS = "com.legitimateapps.dulcet.PlaybackEntry"

private enum class PhoneTab { Search, Library }

private val routeSaver = listSaver<SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() })
private fun List<String>.toMutableStateList() = mutableStateListOf<String>().also { it.addAll(this) }

/**
 * The phone app once an account exists. Presentation state lives here and in the screens; library
 * data comes from the existing [LibrarySession] read and playback from the service's controller.
 */
@Composable
internal fun PhoneApp(account: SearchAccount, dependencies: SearchHostDependencies, requests: PhonePlaybackRequests) {
    val context = LocalContext.current
    val preferences = remember { runCatching { context.getSharedPreferences("dulcet.ui", 0) }.getOrNull() }
    var tab by rememberSaveable {
        mutableStateOf(runCatching { PhoneTab.valueOf(preferences?.getString("tab", null).orEmpty()) }
            .getOrDefault(PhoneTab.Search))
    }
    val routes = rememberSaveable(saver = routeSaver) { mutableStateListOf() }
    var playerOpen by rememberSaveable { mutableStateOf(false) }

    val playback = rememberPlaybackController()
    val playbackState by remember(playback) { playback?.state ?: MutableStateFlow(AndroidPlaybackState()) }
        .collectAsStateWithLifecycle()
    val library = rememberLibrarySession(account)
    val libraryState by library.state.collectAsState()
    val index = remember(libraryState.library) { LibraryIndex.from(libraryState.library?.rows.orEmpty()) }

    val pending by requests.pending.collectAsState()
    LaunchedEffect(playback, pending) {
        val request = pending ?: return@LaunchedEffect
        val controller = playback ?: return@LaunchedEffect
        requests.pending.value = null
        controller.playSong(request.provider, request.rawId, request.title)
    }
    val showRequests by requests.show.collectAsState()
    LaunchedEffect(showRequests) { if (showRequests > 0) playerOpen = true }

    BackHandler(enabled = playerOpen) { playerOpen = false }
    BackHandler(enabled = !playerOpen && routes.isNotEmpty()) { routes.removeAt(routes.lastIndex) }

    val actions = PhoneActions(
        openAlbum = { routes += "album:$it" },
        openArtist = { routes += "artist:$it" },
        back = { if (routes.isNotEmpty()) routes.removeAt(routes.lastIndex) },
        playAlbum = { album, start, shuffle -> playAlbum(playback, album, start, shuffle) },
        playArtist = { artist, shuffle -> playArtist(playback, index, artist, shuffle) },
    )
    val playingRawId = playbackState.queue.getOrNull(playbackState.currentIndex ?: -1)?.track?.rawId

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Column {
                    if (playbackState.hasSession) MiniPlayer(account, playbackState, playback) { playerOpen = true }
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == PhoneTab.Library && routes.isEmpty(),
                            onClick = { tab = PhoneTab.Library; routes.clear(); saveTab(preferences, PhoneTab.Library) },
                            icon = { Icon(DulcetIcons.LibraryMusic, null) },
                            label = { Text(stringResource(R.string.tab_library)) },
                            modifier = Modifier.testTag("library.open"),
                        )
                        NavigationBarItem(
                            selected = tab == PhoneTab.Search && routes.isEmpty(),
                            onClick = { tab = PhoneTab.Search; routes.clear(); saveTab(preferences, PhoneTab.Search) },
                            icon = { Icon(DulcetIcons.Search, null) },
                            label = { Text(stringResource(R.string.tab_search)) },
                            modifier = Modifier.testTag("search.open"),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val route = routes.lastOrNull()
                when {
                    route?.startsWith("album:") == true ->
                        AlbumScreen(account, index.album(route.removePrefix("album:")), playingRawId, actions)
                    route?.startsWith("artist:") == true ->
                        ArtistScreen(account, index, index.artist(route.removePrefix("artist:")), actions)
                    tab == PhoneTab.Library -> LibraryHome(account, library, libraryState, index, playingRawId, actions)
                    else -> MobileSearchRoute(account, dependencies) { result ->
                        playback?.playSong(result.id.providerInstanceId, result.id.rawId, result.title)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = playerOpen && playback != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            if (playback != null) NowPlayingScreen(account, playbackState, playback) { playerOpen = false }
        }
    }
}

private fun saveTab(preferences: android.content.SharedPreferences?, tab: PhoneTab) {
    runCatching { preferences?.edit()?.putString("tab", tab.name)?.apply() }
}

internal class PhoneActions(
    val openAlbum: (String) -> Unit,
    val openArtist: (String) -> Unit,
    val back: () -> Unit,
    val playAlbum: (LibraryAlbum, Int, Boolean) -> Unit,
    val playArtist: (LibraryArtist, Boolean) -> Unit,
)

private fun playAlbum(playback: AndroidPlaybackController?, album: LibraryAlbum, start: Int, shuffle: Boolean) {
    val tracks = album.playable()
    if (playback == null || tracks.isEmpty()) return
    playback.playQueue(tracks, start.coerceIn(tracks.indices), AndroidQueueSource.Album, album.item.title,
        album.item.id.rawId, shuffle)
}

private fun playArtist(playback: AndroidPlaybackController?, index: LibraryIndex, artist: LibraryArtist, shuffle: Boolean) {
    val tracks = index.albumsBy(artist).flatMap { it.playable() }
    if (playback == null || tracks.isEmpty()) return
    playback.playQueue(tracks, 0, AndroidQueueSource.Artist, artist.item.title, artist.item.id.rawId, shuffle)
}

/** The same session lifecycle the library screen always had, hoisted so detail pages share one read. */
@Composable
private fun rememberLibrarySession(account: SearchAccount): LibrarySession {
    val context = LocalContext.current
    val session = remember(account.providerInstanceId) { LibrarySession(context, account) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        session.openSaved()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.resume()
                Lifecycle.Event.ON_STOP -> session.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.close() }
    }
    return session
}
