package com.legitimateapps.dulcet

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidQueueInsertion
import com.legitimateapps.dulcet.library.canBeQueued
import com.legitimateapps.dulcet.library.queueAlbum
import com.legitimateapps.dulcet.library.queueTrack
import com.legitimateapps.dulcet.ui.queueEditRefused
import com.legitimateapps.dulcet.library.ArtistPlayResult
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.playAlbum
import com.legitimateapps.dulcet.library.playArtist
import com.legitimateapps.dulcet.library.playTracks
import com.legitimateapps.dulcet.library.playPlaylist
import com.legitimateapps.dulcet.library.hostInForeground
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.titledTracks
import com.legitimateapps.dulcet.playback.PlayRequest
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.rememberPlaybackController
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchActivation
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
    var adding by remember { mutableStateOf<PlaylistAddition?>(null) }

    val playback = rememberPlaybackController()
    val playbackState by remember(playback) { playback?.state ?: MutableStateFlow(AndroidPlaybackState()) }
        .collectAsStateWithLifecycle()
    val foreground = hostInForeground()
    val library = remember(account) { LibrarySession(context, account, foreground) }
    val pending by requests.pending.collectAsState()
    LaunchedEffect(playback, pending) {
        val request = pending ?: return@LaunchedEffect
        val controller = playback ?: return@LaunchedEffect
        requests.pending.value = null
        controller.playSong(request.provider, request.rawId, request.title)
    }
    val showRequests by requests.show.collectAsState()
    LaunchedEffect(showRequests) { if (showRequests > 0) playerOpen = true }

    val provider = account.providerInstanceId
    val libraryName = stringResource(R.string.tab_library)
    val actions = PhoneActions(
        openAlbum = { routes += "album:$it" },
        openArtist = { routes += "artist:$it" },
        back = { if (routes.isNotEmpty()) routes.removeAt(routes.lastIndex) },
        playAlbum = { album, start, shuffle -> playAlbumFrom(playback, provider, album, start, shuffle) },
        playArtist = { artist, shuffle, done -> playArtist(playback, library, provider, artist, shuffle, done) },
        // Up Next rows take their titles from albums already on screen, every titled track whether or
        // not it can play right now; no request is made for them.
        rememberAlbum = { album -> playback?.rememberTracks(album.titledTracks(provider)) },
        playTracks = { items, rawId, source ->
            if (playTracks(playback, provider, items, rawId, source)) playerOpen = true
        },
        openPlaylist = { routes += "playlist:$it" },
        followPlaylist = { local, server ->
            val index = routes.lastIndexOf("playlist:$local")
            if (index >= 0) routes[index] = "playlist:$server"
        },
        playPlaylist = { playlist, position, shuffle ->
            if (playPlaylist(playback, provider, playlist, position, shuffle)) playerOpen = true
        },
        addToPlaylist = { adding = it },
        // A refused addition is said, as the Apple shells say it, rather than silently not happening.
        queueAlbum = { album, insertion ->
            if (!queueAlbum(playback, provider, album, insertion)) queueEditRefused(context)
        },
        queueTrack = { track, insertion, album ->
            if (!queueTrack(playback, provider, track, insertion, libraryName, album)) queueEditRefused(context)
        },
    )
    // A restored Up Next row with no title takes it from what this device has seen, as it did from the
    // whole-library mirror: a seen-cache read, never a request.
    val untitled = remember(playbackState.queue) {
        playbackState.queue.map { it.track }.filter { it.title.isBlank() }.map { it.rawId }.distinct()
    }
    LaunchedEffect(playback, library, untitled) {
        val controller = playback ?: return@LaunchedEffect
        if (untitled.isNotEmpty()) library.seenTracks(untitled) { tracks -> controller.rememberTracks(tracks) }
    }
    val playingRawId = playbackState.queue.getOrNull(playbackState.currentIndex ?: -1)?.track?.rawId
    // Search lives above the pages, so Back from a result's page returns to the query and results.
    val searchPresenter = rememberSearchPresenter(account, dependencies)
    val searchActivation = remember(context) {
        SearchActivation(context, openAlbum = { routes += "album:$it" }, openArtist = { routes += "artist:$it" })
    }

    PhoneFrame(account, playbackState, playback, playerOpen, { playerOpen = it },
        back = if (routes.isNotEmpty()) { { routes.removeAt(routes.lastIndex) } } else null, library = library, tabs = {
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
            // The account and its Sign out (spec §14.7); provided by AccountConnectScreen.
            AccountNavigationItem()
        }
    }) {
        val route = routes.lastOrNull()
        // The library's own state — which view, how far scrolled — is kept while a page is pushed
        // over it, so Back from a playlist returns to the playlists, not to the home rows.
        val saved = rememberSaveableStateHolder()
        when {
            route?.startsWith("album:") == true ->
                AlbumScreen(account, library, route.removePrefix("album:"), playingRawId, actions)
            route?.startsWith("artist:") == true ->
                ArtistScreen(account, library, route.removePrefix("artist:"), actions)
            route?.startsWith("playlist:") == true ->
                PlaylistScreen(account, library, route.removePrefix("playlist:"), playingRawId, actions)
            tab == PhoneTab.Library -> saved.SaveableStateProvider("library") { LibraryHome(account, library, actions, playingRawId) }
            else -> MobileSearchScreen(searchPresenter, searchActivation::activate, account) { result ->
                playback?.playSong(result.id.providerInstanceId, result.id.rawId, result.title)
            }
        }
    }
    // One session for every tab and detail page, started with the activity. The search tab's
    // presenter uses the same process reader, so it follows the same reachability. The scaffold
    // composes its content during layout, after this effect has run, so on the phone start() — and
    // its reconnect's first request — comes before the screens open their windows; each window still
    // paints from the cache before its own read is issued.
    adding?.let { addition -> AddToPlaylistSheet(account, library, addition) { adding = null } }
    LibraryLifecycle(library)
}

/**
 * The phone's frame: the page above the now-playing bar and the tabs, the full player over all of
 * them while [playerOpen] and there is playback to show, and the skip notice (spec §12.12 rule 5)
 * on whichever is in front. Back closes the player while it covers the page, and otherwise goes
 * [back] on the page, when the page has somewhere to go back to.
 */
@Composable
internal fun PhoneFrame(
    account: SearchAccount,
    playbackState: AndroidPlaybackState,
    playback: AndroidPlaybackController?,
    playerOpen: Boolean,
    setPlayerOpen: (Boolean) -> Unit,
    back: (() -> Unit)? = null,
    library: LibrarySession? = null,
    tabs: @Composable () -> Unit,
    page: @Composable () -> Unit,
) {
    // The player covers the page only when it is asked open and there is playback to show; a flag
    // left open with none covers nothing, so Back must not be spent closing it.
    val covered = playerOpen && playback != null
    // At most one of these is enabled at a time, so which one Back reaches does not depend on the
    // order they are registered in (the later registration wins). The `!covered` guard is that
    // independence: with it, swapping the two lines changes nothing.
    BackHandler(enabled = !covered && back != null) { back?.invoke() }
    BackHandler(enabled = covered) { setPlayerOpen(false) }
    // The player is in front from the moment it is asked open until its exit slide has finished.
    val player = remember { MutableTransitionState(covered) }.apply { targetState = covered }
    val inFront = player.currentState || player.targetState
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // While the full player covers them -- entering, open, or sliding away -- a screen
            // reader must reach nothing of the page, the now-playing bar or the tabs, so there is
            // never a second layer to reach: an accessibility action bypasses touch hit testing,
            // and a hidden row it reached would start a queue the person cannot see.
            modifier = if (inFront) Modifier.clearAndSetSemantics {} else Modifier,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                Column {
                    if (playbackState.hasSession) MiniPlayer(account, playbackState, playback) { setPlayerOpen(true) }
                    tabs()
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                page()
                // Inside the content region, so it ends above the now-playing bar and the tabs and
                // never covers them; the full player shows it itself while it is open.
                PhoneSkipNotice(playbackState, visible = !covered)
            }
        }
        AnimatedVisibility(
            visibleState = player,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            if (playback != null) NowPlayingScreen(account, playbackState, playback, library) { setPlayerOpen(false) }
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
    /** An album publication and the index of a row in its items. */
    val playAlbum: (AndroidLibraryPublication, Int, Boolean) -> Unit,
    /**
     * An artist publication: every album's playable tracks, in the artist's album order. The last
     * argument hears how it ended; the returned handle abandons the albums still opening.
     */
    val playArtist: (AndroidLibraryPublication, Boolean, (ArtistPlayResult) -> Unit) -> AutoCloseable?,
    val rememberAlbum: (AndroidLibraryPublication) -> Unit = {},
    /** A list's playable tracks queued from the one with the id, as a library queue with the name. */
    val playTracks: (List<AndroidLibraryItem>, String, String) -> Unit = { _, _, _ -> },
    val openPlaylist: (String) -> Unit = {},
    /** A playlist page open on a local id follows the playlist to the server's id once it is made. */
    val followPlaylist: (String, String) -> Unit = { _, _ -> },
    /** A playlist publication, the index of an entry in its items, and shuffle. */
    val playPlaylist: (AndroidLibraryPublication, Int, Boolean) -> Unit = { _, _, _ -> },
    /** Opens "Add to playlist" for songs or an album. */
    val addToPlaylist: (PlaylistAddition) -> Unit = {},
    /** Play Next or Add to Queue for an album publication's playable tracks (spec §14.1). */
    val queueAlbum: (AndroidLibraryPublication, AndroidQueueInsertion) -> Unit = { _, _ -> },
    /** Play Next or Add to Queue for one track, shown on the album given, if any. */
    val queueTrack: (AndroidLibraryItem.Track, AndroidQueueInsertion, AndroidLibraryItem.Album?) -> Unit = { _, _, _ -> },
) {
    /** A row's Play Next and Add to Queue, or null for a track that cannot be queued now. */
    fun trackQueue(track: AndroidLibraryItem.Track, album: AndroidLibraryItem.Album?): TrackQueue? =
        if (!track.canBeQueued()) null
        else TrackQueue({ queueTrack(track, AndroidQueueInsertion.PlayNext, album) },
            { queueTrack(track, AndroidQueueInsertion.AddToQueue, album) })
}

/** [position] indexes the album's rows; the queue skips rows with no metadata to play. */
private fun playAlbumFrom(
    playback: AndroidPlaybackController?,
    provider: String,
    publication: AndroidLibraryPublication,
    position: Int,
    shuffle: Boolean,
) {
    val rawId = (publication.items.getOrNull(position) as? AndroidLibraryItem.Track)?.rawId
    playAlbum(playback, provider, publication, rawId, shuffle)
}
