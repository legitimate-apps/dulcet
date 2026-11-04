package com.legitimateapps.dulcet.tv

import androidx.compose.ui.semantics.disabled
import com.legitimateapps.dulcet.library.trackDisplayTitle
import com.legitimateapps.dulcet.library.playPlaylist
import com.legitimateapps.dulcet.library.queuePlaylistTrack
import com.legitimateapps.dulcet.library.playlistEditLine
import com.legitimateapps.dulcet.library.playlistOwnerLine
import com.legitimateapps.dulcet.library.playlistPendingLine
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryCoverage
import com.legitimateapps.dulcet.library.AlbumSort
import com.legitimateapps.dulcet.library.rememberAlbumSort
import com.legitimateapps.dulcet.library.orderLine
import com.legitimateapps.dulcet.library.isOffline
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.StandardCardContainer
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRowDefaults
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidDownloadStatus
import com.legitimateapps.dulcet.downloads.AccountDownloadRequests
import com.legitimateapps.dulcet.downloads.AlbumDownloads
import com.legitimateapps.dulcet.downloads.DownloadRequests
import com.legitimateapps.dulcet.downloads.albumDownloadLine
import com.legitimateapps.dulcet.downloads.downloadItem
import com.legitimateapps.dulcet.downloads.downloadLine
import com.legitimateapps.dulcet.downloads.rememberDownloadStatuses
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidPlaylistEditRecord
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.library.ArtistPlayResult
import com.legitimateapps.dulcet.library.LibraryHomeRowSurface
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibrarySubject
import com.legitimateapps.dulcet.library.LibrarySurface
import com.legitimateapps.dulcet.library.hasConnectionNotice
import com.legitimateapps.dulcet.library.MyLibraryAvailability
import com.legitimateapps.dulcet.library.MyLibrarySession
import com.legitimateapps.dulcet.library.connectionLine
import com.legitimateapps.dulcet.library.coverageLine
import com.legitimateapps.dulcet.library.growsAtEnd
import com.legitimateapps.dulcet.library.discardedChangesLine
import com.legitimateapps.dulcet.library.displayTitle
import com.legitimateapps.dulcet.library.isFavourite
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.library.hostInForeground
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.savedAccountLine
import com.legitimateapps.dulcet.library.noEpochLine
import com.legitimateapps.dulcet.library.offersRetry
import com.legitimateapps.dulcet.library.favouriteTarget
import com.legitimateapps.dulcet.library.rememberOutcomeLines
import com.legitimateapps.dulcet.library.playAlbum
import com.legitimateapps.dulcet.library.playArtist
import com.legitimateapps.dulcet.library.playTracks
import com.legitimateapps.dulcet.library.canBeQueued
import com.legitimateapps.dulcet.library.playableTracks
import com.legitimateapps.dulcet.library.queueAlbum
import com.legitimateapps.dulcet.library.queueSearchResult
import com.legitimateapps.dulcet.library.queueTrack
import com.legitimateapps.dulcet.core.AndroidQueueInsertion
import com.legitimateapps.dulcet.ui.DroppedAdditionsNotice
import com.legitimateapps.dulcet.ui.queueEditRefused
import com.legitimateapps.dulcet.library.rememberHomeRows
import com.legitimateapps.dulcet.library.rememberSurface
import com.legitimateapps.dulcet.library.subtitle
import com.legitimateapps.dulcet.library.titleResource
import com.legitimateapps.dulcet.library.unavailableLine
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.rememberPlaybackBinding
import com.legitimateapps.dulcet.playback.rememberPlaybackController
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.core.StreamingQuality
import com.legitimateapps.dulcet.playback.StreamingQualitySettings
import com.legitimateapps.dulcet.playback.label
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.rememberArtwork
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * The TV app once an account exists (spec §3, Phase 5): the library and search, each a root, with the
 * library's album, artist, albums, artists, genres and genre screens pushed above it. The app opens on the library,
 * as the phone does: a person turning on the TV expects their music, not an empty search field. Library data comes from the
 * account's one LibrarySession (§16.18), whose windows each screen opens while it is composed and
 * closes when it goes, as the phone's do; playing goes through the service's controller with the
 * phone's own queue builders (`LibraryPlay.kt`). Now Playing is its own activity, reachable from the
 * navigation row on every screen while something is playing, and from the playing track's row.
 *
 * Focus: every screen names where the D-pad lands when it opens, and Back returns to the element that
 * opened the screen it leaves (TvFocusMemory), scrolled back into view if a list had moved on.
 */

internal const val ROUTE_LISTEN_NOW = "listenNow"
internal const val ROUTE_SEARCH = "search"
internal const val ROUTE_LIBRARY = "library"
internal const val ROUTE_BROWSE = "browse"
internal const val ROUTE_MY_ARTISTS = "mylibrary.artists"
internal const val ROUTE_MY_ALBUMS = "mylibrary.albums"
internal const val ROUTE_MY_SONGS = "mylibrary.songs"
internal const val ROUTE_ALBUMS = "albums"
internal const val ROUTE_ARTISTS = "artists"
internal const val ROUTE_SONGS = "songs"
internal const val ROUTE_DOWNLOADED = "downloaded"
internal const val ROUTE_GENRES = "genres"
internal const val ROUTE_FAVOURITES = "favourites"
internal const val ROUTE_PLAYLISTS = "playlists"
internal const val ROUTE_MOST_PLAYED = "mostPlayed"
internal const val ROUTE_ACCOUNT = "account"
internal const val ROUTE_CONNECTIONS = "account.connections"
internal const val ROUTE_STORAGE = "account.storage"
internal const val ROUTE_PINNED = "pinned"
internal const val ROUTE_RECENTLY_ADDED = "recentlyAdded"
internal const val ROUTE_RECENTLY_PLAYED = "recentlyPlayed"
private const val ALBUM = "album:"
private const val PLAYLIST = "playlist:"
private const val ARTIST = "artist:"
private const val GENRE = "genre:"

private val routeSaver = listSaver<SnapshotStateList<String>, String>(
    { it.toList() },
    { saved -> mutableStateListOf<String>().also { it.addAll(saved) } },
)

/** How a screen moves: to another route above it, and back to the one below. */
internal class TvNavigator(
    val open: (String) -> Unit,
    val back: () -> Unit,
    /** Puts the route [to] where [from] is, for a playlist made under a local id that now has the server's. */
    val replace: (from: String, to: String) -> Unit = { _, _ -> },
    /** Back from [route] only while it is the one showing: an answer that comes after Back was pressed moves nothing. */
    val leave: (route: String) -> Unit = { back() },
    /** Switches the root, as a tab press does: My Library's empty state points to Browse. */
    val showRoot: (String) -> Unit = {},
) {
    fun openAlbum(rawId: String) = open(ALBUM + rawId)
    fun openArtist(rawId: String) = open(ARTIST + rawId)
}

/**
 * The TV app's navigation and library around [search]. The account's [LibrarySession] lives as long
 * as this entry — on the search screen too — so reachability and the foreground are reported whichever
 * screen is showing, and a search offline says so (§16.15).
 */
@Composable
internal fun TvLibraryEntry(
    account: SearchAccount,
    /** The playback service's controller, null until it binds. */
    playback: AndroidPlaybackController? = rememberPlaybackController(),
    /**
     * The app opened on the saved account, so device content paints before the automatic reconnect in this
     * process (spec §13.1, CONF-10b); the app's entry passes true.
     */
    restoredAccount: Boolean = false,
    /** The account's My Library; the session's own unless a test seats one. */
    myLibrary: MyLibrarySession? = null,
    search: @Composable (TvNavigator, AndroidPlaybackController?) -> Unit,
) {
    val context = LocalContext.current
    val foreground = hostInForeground()
    val session = remember(account) { LibrarySession(context, account, foreground, restoredAccount) }
    // My Library (spec §29.4): with `dulcetLibrary` advertised, Library is the account's own library
    // and the catalog moves to Browse; without it, everything is exactly as it always was.
    val myLibrarySession = myLibrary ?: session.myLibrary
    val myLibraryPublication by myLibrarySession.state.collectAsState()
    val split = myLibraryPublication?.availability == MyLibraryAvailability.Available
    // The split says the server advertises the extension; bring the device's library up to its.
    LaunchedEffect(split) { if (split) myLibrarySession.refresh() }
    val routes = rememberSaveable(saver = routeSaver) { mutableStateListOf(ROUTE_LISTEN_NOW) }
    val memory = remember { TvFocusMemory() }
    val states = rememberSaveableStateHolder()
    val playbackState by remember(playback) { playback?.state ?: MutableStateFlow(AndroidPlaybackState()) }
        .collectAsStateWithLifecycle()

    fun back() {
        if (routes.size > 1) {
            val left = routes.removeAt(routes.lastIndex)
            if (left !in routes) { states.removeState(left); memory.forget(left) }
        } else if (routes.single() != ROUTE_LISTEN_NOW) {
            show(routes, states, memory, ROUTE_LISTEN_NOW)
        }
    }
    val navigator = remember(routes) {
        TvNavigator(open = { route -> routes += route }, back = ::back, replace = { from, to ->
            val index = routes.lastIndexOf(from)
            if (index >= 0) {
                routes[index] = to
                if (from !in routes) { states.removeState(from); memory.forget(from) }
            }
        }, leave = { route -> if (routes.last() == route) back() },
            showRoot = { route -> show(routes, states, memory, route) })
    }
    // Back walks down the routes, then from the search root to the library, the screen the app opens
    // on; from the library it leaves the app.
    BackHandler(enabled = routes.size > 1 || routes.single() != ROUTE_LISTEN_NOW) { navigator.back() }

    val top = routes.last()
    val navigation = remember { TvNavigationFocus() }
    // The library screens are lists and grids, not Surfaces: paint the background and set the content
    // colour here, or every uncoloured heading falls back to black on the dark background.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            TvTopBar(top, routes.first(), playbackState.hasSession, navigation,
                onListenNow = { show(routes, states, memory, ROUTE_LISTEN_NOW) },
                onSearch = { show(routes, states, memory, ROUTE_SEARCH) },
                onLibrary = { show(routes, states, memory, ROUTE_LIBRARY) },
                onNowPlaying = { context.startActivity(PlaybackIntents.showNowPlaying(context)) },
                onAccount = { if (routes.last() != ROUTE_ACCOUNT) routes += ROUTE_ACCOUNT },
                onBrowse = { show(routes, states, memory, ROUTE_BROWSE) })
            // Under the bar on the library's home, the screen that offers to reconnect the saved account:
            // DOWN from the bar and UP out of the screen below both reach it, wherever the remote is
            // along them, so its place does not depend on how wide the bar's tabs happen to be.
            if (top == ROUTE_LISTEN_NOW || top == ROUTE_LIBRARY || top == ROUTE_BROWSE) TvSavedAccountNotice(session, account, navigation)
            val view = LocalView.current
            // Where the remote rests when its element left: the route's default element, unless the
            // route is waiting to restore one, else the showing root's own tab (as before content).
            navigation.onFocusedElementLeft = {
                view.post {
                    val route = memory.route(routeKey(routes.last(), split))
                    if (!navigation.contentFocused && navigation.focusedTag != navigation.currentTag) {
                        runCatching { (route.fallback.takeIf { route.pending == null } ?: navigation.current).requestFocus() }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onFocusChanged { navigation.contentFocused = it.hasFocus }.focusProperties {
                onExit = {
                    if (navigation.reconnectShown && requestedFocusDirection == FocusDirection.Up) {
                        navigation.reconnect.requestFocus()
                    }
                }
            }.focusGroup()) {
            states.SaveableStateProvider(top) {
                CompositionLocalProvider(
                    LocalTvRouteFocus provides memory.route(routeKey(top, split)),
                    // A text field keeps UP for its cursor; the navigation bar is above it.
                    LocalTvNavFocus provides navigation.current,
                    LocalTvNavigation provides navigation,
                ) {
                    val playingRawId = playbackState.queue.getOrNull(playbackState.currentIndex ?: -1)?.track?.rawId
                    when {
                        top == ROUTE_LISTEN_NOW -> TvListenNow(account, session, playback, navigator)
                        top == ROUTE_SEARCH -> search(navigator, playback)
                        top == ROUTE_LIBRARY && split ->
                            TvMyLibraryHome(account, session, myLibrarySession, playback, navigator)
                        top == ROUTE_LIBRARY -> TvLibraryHome(account, session, playback, navigator)
                        top == ROUTE_BROWSE -> TvLibraryHome(account, session, playback, navigator, browse = true)
                        top == ROUTE_ALBUMS -> TvAlbumsGrid(account, session, navigator, playback)
                        top == ROUTE_ARTISTS -> TvArtistsGrid(account, session, navigator)
                        top == ROUTE_SONGS -> TvDeviceSongs(account, session, playback, navigator, false, playingRawId)
                        top == ROUTE_DOWNLOADED -> TvDeviceSongs(account, session, playback, navigator, true, playingRawId)
                        top == ROUTE_GENRES -> TvGenresGrid(account, session, navigator)
                        top == ROUTE_FAVOURITES -> TvFavouritesScreen(account, session, playback, playingRawId, navigator)
                        top == ROUTE_PLAYLISTS -> TvPlaylistsGrid(account, session, navigator)
                        top == ROUTE_MOST_PLAYED -> TvMostPlayedGrid(account, session, navigator)
                        top == ROUTE_PINNED -> TvPinnedGrid(account, session, navigator, playback)
                        top == ROUTE_RECENTLY_ADDED -> TvRecentAlbumsGrid(account, session, navigator, recent = false)
                        top == ROUTE_RECENTLY_PLAYED -> TvRecentAlbumsGrid(account, session, navigator, recent = true)
                        top == ROUTE_MY_ARTISTS -> TvMyArtistsGrid(account, session, myLibrarySession, navigator)
                        top == ROUTE_MY_ALBUMS -> TvMyAlbumsGrid(account, session, myLibrarySession, navigator)
                        top == ROUTE_MY_SONGS -> TvMySongsList(account, session, myLibrarySession, playback, playingRawId, navigator)
                        top == ROUTE_ACCOUNT -> TvAccountScreen(account, navigator)
                        top == ROUTE_CONNECTIONS -> TvConnectionsScreen()
                        top == ROUTE_STORAGE -> TvStorageScreen()
                        top.startsWith(PLAYLIST) ->
                            TvPlaylistScreen(account, session, playback, playingRawId, top.removePrefix(PLAYLIST), navigator)
                        top.startsWith(ALBUM) ->
                            TvAlbumScreen(account, session, playback, playingRawId, top.removePrefix(ALBUM), navigator,
                                myLibrary = myLibrarySession.takeIf { split })
                        top.startsWith(ARTIST) ->
                            TvArtistScreen(account, session, playback, top.removePrefix(ARTIST), navigator,
                                myLibrary = myLibrarySession.takeIf { split })
                        top.startsWith(GENRE) ->
                            TvGenreScreen(account, session, playback, playingRawId, top.removePrefix(GENRE), navigator,
                                myLibrary = myLibrarySession.takeIf { split })
                    }
                }
            }
        }
        }
    }
    // On a return to the foreground the screens above are already open when start() reconnects. At
    // launch the TV shows the library, whose home rows open as it composes; each publishes its cache
    // before its own read, as the phone's do.
    DroppedAdditionsNotice(playback, playbackState.droppedAdditions, playbackState.queueConfirmation)
    // A create in doubt is asked over whichever screen is showing, as the tvOS shell asks it.
    TvPlaylistQuestion(session)
    LibraryLifecycle(session)
}

/**
 * Replaces the routes with the root [route], forgetting the screens that were above the old root; a
 * root keeps its own state, as a tab does.
 */
private fun show(routes: SnapshotStateList<String>, states: androidx.compose.runtime.saveable.SaveableStateHolder,
                 memory: TvFocusMemory, route: String) {
    if (routes.size == 1 && routes.single() == route) return
    routes.drop(1).forEach { states.removeState(it); memory.forget(it) }
    routes.clear()
    routes += route
}

/**
 * The navigation bar's focus: the root that is showing, so UP from a screen's top lands on it; and
 * which of the bar's controls holds focus, if any. The bar sits outside every route's focus memory,
 * so a screen's default focus asks here whether the person has moved along the bar ([allowsDefault]).
 */
internal class TvNavigationFocus {
    val listenNow = FocusRequester()
    val search = FocusRequester()
    val library = FocusRequester()
    val browse = FocusRequester()
    val account = FocusRequester()
    var current: FocusRequester = library

    /** The saved account's Reconnect, under the bar while [reconnectShown] (spec §13.1). */
    val reconnect = FocusRequester()
    var reconnectShown: Boolean = false

    /** The tag of the showing root's tab: the one place on the bar a screen's default may take focus from. */
    var currentTag: String = "library.open"

    /** The tag of the bar control holding focus; null while focus is off the bar. Not state: read once, at a claim. */
    var focusedTag: String? = null
        private set

    fun focusChanged(tag: String, focused: Boolean) {
        if (focused) focusedTag = tag else if (focusedTag == tag) focusedTag = null
    }

    /** Whether focus is inside the showing route's screen (below the bar). */
    var contentFocused: Boolean = false

    /**
     * The focused element of the showing screen left composition under the remote — a filter emptied
     * the grid, the last pinned item was unpinned, Library became My Library. Set by [tvFocus]; the
     * shell rests the remote again before the platform drops it on whatever control comes first.
     */
    var onFocusedElementLeft: () -> Unit = {}

    /**
     * Whether a screen's default element may take focus now: focus is off the bar, or on the showing
     * root's own tab — where the app put it while the screen had nothing to focus. Never once the
     * person has moved to another tab, Now Playing or Account.
     */
    fun allowsDefault(): Boolean = focusedTag == null || focusedTag == currentTag
}

/** Provided around the routes, so [tvFocus] can ask whether the person has moved along the bar. */
internal val LocalTvNavigation = staticCompositionLocalOf<TvNavigationFocus?> { null }

/**
 * While a screen's default element does not exist yet — the library home before its rows arrive,
 * or with none; a pushed screen before its read — the remote rests on the bar's tab for the showing
 * root, so the first key press moves from a known place, and the default may take focus from there
 * when it arrives ([TvNavigationFocus.allowsDefault]). Without this, focus that fell off a screen
 * that just left lands wherever the platform puts it, often another tab, which reads as a move.
 * Acts only when the route has nothing to restore or claim.
 */
@Composable
internal fun RestOnTheBarUntilContent() {
    val route = LocalTvRouteFocus.current
    val navFocus = LocalTvNavFocus.current
    LaunchedEffect(Unit) {
        if (route == null || route.claimDefault()) runCatching { navFocus?.requestFocus() }
    }
}

/**
 * The 10-foot top bar, on every screen of the signed-in app: the brand, the four roots as a
 * tv-material TabRow — Listen Now, Library, Browse and Search, the same set and order on every
 * server — and at the far end Now Playing while something plays and the Account action. Sign out
 * is not here; it lives on the Account screen ([ROUTE_ACCOUNT]). Selection (the row's pill
 * indicator and selected content color) never uses the focused style, and on the Account page no
 * tab is selected: one destination at most reads as current.
 */
@Composable
internal fun TvTopBar(
    top: String,
    root: String,
    playing: Boolean,
    focus: TvNavigationFocus,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onNowPlaying: () -> Unit,
    onAccount: () -> Unit,
    onBrowse: () -> Unit = {},
    onListenNow: () -> Unit = {},
) {
    focus.current = when {
        top.startsWith(ROUTE_ACCOUNT) -> focus.account
        root == ROUTE_LISTEN_NOW -> focus.listenNow
        root == ROUTE_LIBRARY -> focus.library
        root == ROUTE_BROWSE -> focus.browse
        else -> focus.search
    }
    focus.currentTag = when {
        top.startsWith(ROUTE_ACCOUNT) -> "tv.account.open"
        root == ROUTE_LISTEN_NOW -> "listenNow.open"
        root == ROUTE_LIBRARY -> "library.open"
        root == ROUTE_BROWSE -> "browse.open"
        else -> "search.open"
    }
    // The lit tab is the showing root's; on Account none is, so Library does not stay lit there.
    val selectedTab = when {
        top.startsWith(ROUTE_ACCOUNT) -> -1
        root == ROUTE_LISTEN_NOW -> 0
        root == ROUTE_LIBRARY -> 1
        root == ROUTE_BROWSE -> 2
        else -> 3
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 56.dp, top = 20.dp, end = 56.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DulcetIcons.LibraryMusic, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.app_name),
            Modifier.padding(start = 12.dp, end = 40.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        var focusedTab by remember { mutableStateOf<Int?>(null) }
        val tabColors = TabDefaults.pillIndicatorTabColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContentColor = MaterialTheme.colorScheme.onSurface,
            focusedContentColor = MaterialTheme.colorScheme.surface,
            focusedSelectedContentColor = MaterialTheme.colorScheme.surface,
        )
        // The showing root's pill stays lit whether or not the row has focus; a focused other tab gets
        // its own, focused, pill. One pill that followed focus left no destination lit whenever the
        // remote rested on another tab (an empty page's fall-back, a person browsing the bar).
        TabRow(selectedTabIndex = selectedTab, indicator = { positions, hasFocus ->
            val focused = focusedTab.takeIf { hasFocus }
            positions.getOrNull(selectedTab)?.let {
                TabRowDefaults.PillIndicator(currentTabPosition = it, doesTabRowHaveFocus = focused == selectedTab)
            }
            if (focused != null && focused != selectedTab) positions.getOrNull(focused)?.let {
                TabRowDefaults.PillIndicator(currentTabPosition = it, doesTabRowHaveFocus = true)
            }
        }) {
            Tab(selected = selectedTab == 0, onFocus = { focusedTab = 0 }, colors = tabColors, onClick = onListenNow,
                modifier = Modifier.tvBarTab("listenNow.open", focus.listenNow, focus)) {
                Text("Listen Now", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            Tab(selected = selectedTab == 1, onFocus = { focusedTab = 1 }, colors = tabColors, onClick = onLibrary,
                modifier = Modifier.tvBarTab("library.open", focus.library, focus)) {
                Text(stringResource(R.string.tv_nav_library), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            Tab(selected = selectedTab == 2, onFocus = { focusedTab = 2 }, colors = tabColors, onClick = onBrowse,
                modifier = Modifier.tvBarTab("browse.open", focus.browse, focus)) {
                Text(stringResource(SharedR.string.library_browse), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
            Tab(selected = selectedTab == 3, onFocus = { focusedTab = 3 }, colors = tabColors, onClick = onSearch,
                modifier = Modifier.tvBarTab("search.open", focus.search, focus)) {
                Text(stringResource(R.string.tv_nav_search), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        if (playing) {
            Button(onClick = onNowPlaying, modifier = Modifier
                .focusProperties { if (focus.reconnectShown) down = focus.reconnect }
                .onFocusChanged { focus.focusChanged("tv.nav.nowplaying", it.isFocused) }
                .testTag("tv.nav.nowplaying")) {
                Icon(DulcetIcons.QueueMusic, null, Modifier.size(20.dp))
                Text(stringResource(R.string.tv_nav_now_playing), Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        IconButton(
            onClick = onAccount,
            colors = IconButtonDefaults.colors(containerColor = if (top.startsWith(ROUTE_ACCOUNT)) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (top.startsWith(ROUTE_ACCOUNT)) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.tvBarTab("tv.account.open", focus.account, focus)
                .semantics { selected = top.startsWith(ROUTE_ACCOUNT) },
        ) {
            Icon(DulcetIcons.Person, stringResource(SharedR.string.account_signout_account_label))
        }
    }
}

/** A top-bar control's tag, focus requester and bookkeeping for [TvNavigationFocus]. */
private fun Modifier.tvBarTab(tag: String, requester: FocusRequester, bar: TvNavigationFocus): Modifier =
    focusProperties { if (bar.reconnectShown) down = bar.reconnect }
        .focusRequester(requester)
        .onFocusChanged { bar.focusChanged(tag, it.isFocused) }
        .testTag(tag)

/**
 * The navigation bar's current tab's focus, for a screen whose topmost control is a text field:
 * a text field consumes UP for its cursor, so the D-pad could otherwise never leave it upward.
 */
internal val LocalTvNavFocus = staticCompositionLocalOf<FocusRequester?> { null }

// ---- Account (spec §14.7): the settings place, reached from the bar's Account action ------------

/**
 * Whose account, on which server, the saved servers, streaming quality and downloads & storage as
 * settings rows, with Sign out last and in error. Pushed above whatever was showing, so the remote's
 * Back returns to it. It opens with focus on the first server row — or the first settings row when
 * there are none — never on Sign out: a destructive action is never where a remote's centre key
 * lands unasked.
 */
@Composable
internal fun TvAccountScreen(account: SearchAccount, navigator: TvNavigator) {
    EnterRoute()
    val server = runCatching { java.net.URI(account.normalizedBaseUrl).host }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: account.normalizedBaseUrl
    val actions = LocalTvAccountActions.current
    // The page opens on its first server row when there is one, else its first settings row.
    val hasServerRows = actions?.saved == true && (actions.savedServers.isNotEmpty() || actions.addServer != null)
    LazyColumn(
        Modifier.fillMaxSize().testTag("account.surface"),
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(stringResource(SharedR.string.account_signout_account_title), style = MaterialTheme.typography.displaySmall)
        }
        item {
            Text(
                stringResource(SharedR.string.account_signout_account_body, server, account.username),
                Modifier.testTag("account.summary"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The phone's sections: Servers, Playback, then Downloads & storage as its own page; Sign out
        // last, in a group of its own, the page's only destructive row.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasServerRows) TvSettingsHeader(stringResource(R.string.tv_settings_servers))
                TvSavedServerList(defaultFocus = true)
                TvSettingRow(stringResource(R.string.tv_connections), "account.connections", chevron = true) { navigator.open(ROUTE_CONNECTIONS) }
            }
        }
        item { TvStreamingQuality(defaultFocus = !hasServerRows) }
        item {
            TvSettingRow(stringResource(R.string.tv_downloads_storage), "account.storage", chevron = true) { navigator.open(ROUTE_STORAGE) }
        }
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.border))
                Spacer(Modifier.height(16.dp))
                TvSignOutAction()
            }
        }
    }
}

/** Downloads & storage: its own Account child destination with the page's title, as on the phone. */
@Composable
private fun TvStorageScreen() {
    EnterRoute()
    LazyColumn(Modifier.fillMaxSize().testTag("account.storage.surface"),
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.tv_downloads_storage), style = MaterialTheme.typography.displaySmall) }
        item { TvOfflineStorage() }
    }
}

/** The connection editor keeps its own body and opens as an Account child destination. */
@Composable
private fun TvConnectionsScreen() {
    EnterRoute()
    LazyColumn(Modifier.fillMaxSize().testTag("account.connections.surface"),
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.tv_connections), style = MaterialTheme.typography.displaySmall) }
        item { TvConnectionSettingsSection() }
    }
}

/**
 * The streaming-quality choice (spec §12.5): one settings row per network class, its value as
 * supporting text, opening a radio choice dialog whose selected mark is independent of focus.
 * Saved at once, applied from the next song; nothing playing restarts for it.
 */
@Composable
private fun TvStreamingQuality(defaultFocus: Boolean = false) {
    val context = LocalContext.current
    val settings = remember(context) { StreamingQualitySettings.get(context) }
    val preference by settings.preference.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.testTag("quality.section"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TvSettingsHeader(stringResource(R.string.tv_settings_playback))
        TvSettingRow(
            stringResource(SharedR.string.streaming_quality_unmetered), "quality.unmetered",
            // The account page's default focus when there is no saved-server row above it.
            default = defaultFocus,
            supporting = preference.unmetered.label(context),
        ) { choosing = "unmetered" }
        TvSettingRow(
            stringResource(R.string.tv_metered_networks), "quality.metered",
            supporting = preference.metered.label(context),
        ) { choosing = "metered" }
    }
    val which = choosing
    if (which != null) {
        val unmetered = which == "unmetered"
        TvChoiceDialog(
            tag = "quality.$which", surfaceTag = "quality.$which.menu",
            title = stringResource(
                if (unmetered) SharedR.string.streaming_quality_unmetered else R.string.tv_metered_networks),
            choices = StreamingQuality.entries,
            choiceTag = { it.wireName },
            choiceLabel = { it.label(context) },
            selected = if (unmetered) preference.unmetered else preference.metered,
            onChoose = { quality ->
                settings.set(if (unmetered) preference.copy(unmetered = quality) else preference.copy(metered = quality))
                choosing = null
            },
            onDismiss = { choosing = null },
            supporting = stringResource(R.string.tv_quality_applies_next_song),
        )
    }
}

// ---- Focus memory ---------------------------------------------------------------------------------------

/** For each route, the element last focused on it, so Back lands where the person left. */
internal class TvFocusMemory {
    private val routes = mutableMapOf<String, TvRouteFocus>()
    fun route(route: String): TvRouteFocus = routes.getOrPut(route) { TvRouteFocus() }
    fun forget(route: String) { routes.remove(route) }
}

/**
 * One route's focus. [pending] is the element to focus when the route is shown again: the one last
 * focused on it. A screen's [claimDefault] element takes focus only when nothing is pending.
 */
internal class TvRouteFocus {
    var last: String? = null
        private set
    var pending: String? = null
        private set
    private var entered = false

    /** Called as the route's screen enters composition: what was last focused is to be focused again. */
    fun enter() { pending = last; entered = true }
    fun focused(tag: String) { last = tag; if (pending == tag) pending = null }
    fun restored() { pending = null }
    fun claimDefault(): Boolean = entered && pending == null && last == null

    /** The screen's default element while it is composed: where the remote rests if its element leaves. */
    var fallback: FocusRequester? = null
}

/**
 * The focus memory a route uses. Library is two screens with no tag in common: the catalog home, and
 * My Library once the server advertises it. Each keeps its own memory, so the split arriving while
 * Library shows (a cold launch's handshake) does not leave the new screen waiting to restore an
 * element only the old one had.
 */
internal fun routeKey(route: String, split: Boolean): String =
    if (route == ROUTE_LIBRARY && split) "$ROUTE_LIBRARY/mine" else route

internal val LocalTvRouteFocus = staticCompositionLocalOf<TvRouteFocus?> { null }

/** Marks the route's screen as entered, so its memory applies to this composition. */
@Composable
internal fun EnterRoute() {
    val route = LocalTvRouteFocus.current ?: return
    remember(route) { route.also(TvRouteFocus::enter) }
    RestOnTheBarUntilContent()
}

/**
 * Tags an element and remembers it as the route's focus when focused; focuses it again when the route
 * comes back ([TvRouteFocus.pending]), or when it is the screen's [default] and nothing else is to be.
 */
@Composable
internal fun Modifier.tvFocus(tag: String, default: Boolean = false, withRoute: Boolean = false): Modifier {
    val route = LocalTvRouteFocus.current
    val bar = LocalTvNavigation.current
    val requester = remember { FocusRequester() }
    if (route != null) {
        val restore = route.pending == tag
        // Decided once, when the element first composes: a default that arrives late — the home's
        // first card after a cold read — takes focus only if the person has not moved along the bar
        // meanwhile, and a later recomposition never turns a declined claim into a jump.
        // A [withRoute] default composes in the same frame as its route, so it cannot arrive late:
        // it claims wherever focus fell as the route opened, a content action that opened another
        // root included.
        val claim = remember(tag, default) { default && route.claimDefault() && (withRoute || bar?.allowsDefault() != false) }
        if (restore || claim) LaunchedEffect(tag) {
            if (runCatching { requester.requestFocus() }.isSuccess) route.restored()
        }
    }
    val holding = remember { booleanArrayOf(false) }
    DisposableEffect(route, tag) {
        if (default && route != null) route.fallback = requester
        onDispose {
            if (route != null && route.fallback === requester) route.fallback = null
            if (holding[0]) bar?.run { onFocusedElementLeft() }
        }
    }
    return this.focusRequester(requester)
        .onFocusChanged { holding[0] = it.isFocused || it.hasFocus; if (holding[0]) route?.focused(tag) }
        .testTag(tag)
}

/** The position in a list whose tags start with [prefix] that the route is to return to, if any. */
@Composable
private fun pendingPosition(prefix: String): Int? =
    LocalTvRouteFocus.current?.pending?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()

// ---- Home -----------------------------------------------------------------------------------------------

@Composable
private fun TvLibraryHome(account: SearchAccount, session: LibrarySession, playback: AndroidPlaybackController?,
                          navigator: TvNavigator, browse: Boolean = false) {
    EnterRoute()
    // One initial focus: the left pane's first section, always composed, so it never depends on which
    // shelf arrives first. The shelves claim nothing; RIGHT from the pane reaches them.
    val rows = if (browse) rememberHomeRows(session).filter { it.row is com.legitimateapps.dulcet.core.AndroidLibraryHomeRow.Albums } else emptyList()
    val depth by session.depth.collectAsState()
    val destinations = tvRootDestinations(browse, personal = false, pins = (depth?.pinCount ?: 0) > 0)
    val observation by session.observation.collectAsState()
    val list = rememberLazyListState()
    // A focus returning to a row the list had scrolled past: bring that row back first. One item
    // precedes the rows in the shelves pane: the pins and connection notices.
    val returning = LocalTvRouteFocus.current?.pending?.let { HOME_ROW.find(it) }?.groupValues?.get(1)?.toIntOrNull()
    LaunchedEffect(returning) { if (returning != null) list.scrollToItem(returning + 1) }
    // The Google TV two-pane root: the ways into the library are a large list on the left (the
    // phone's order), always composed; the shelves are the content on the right.
    Row(
        Modifier.fillMaxSize().testTag("library.surface").semantics { this[LibraryObservation] = observation },
    ) {
        LazyColumn(
            Modifier.width(300.dp).fillMaxHeight().testTag("library.nav"),
            contentPadding = PaddingValues(start = 56.dp, top = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(stringResource(if (browse) SharedR.string.library_browse else R.string.tv_nav_library),
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp)) }
            itemsIndexed(destinations) { index, destination ->
                TvHomeEntry(stringResource(destination.label), "library.view.${destination.key}", destination.icon,
                    default = index == 0) { navigator.open(destination.route) }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxHeight().testTag("library.shelves"),
            state = list,
            contentPadding = PaddingValues(start = 24.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                if (!browse) TvLibraryPins(account, session, playback, navigator)
                TvConnectionNotices(session, accountNotices = true)
            }
            itemsIndexed(rows) { index, row -> TvHomeRow(account, index, row, session, playback, navigator) }
        }
    }
}

/** A library root's entry: a large list row with an icon, the phone's Library order. */
@Composable
internal fun TvHomeEntry(label: String, tag: String, icon: androidx.compose.ui.graphics.vector.ImageVector, default: Boolean = false, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        // The pane is composed with its root, so its first row is the root's initial focus however
        // the root was opened: from its tab, or from a content action such as an empty state's Browse.
        modifier = Modifier.fillMaxWidth().tvFocus(tag, default, withRoute = true),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

private val HOME_ROW = Regex("library\\.home\\.(\\d+)\\.item\\.\\d+")

@Composable
private fun TvHomeRow(account: SearchAccount, index: Int, row: LibraryHomeRowSurface, session: LibrarySession,
                      playback: AndroidPlaybackController?, navigator: TvNavigator) {
    val publication by row.surface.state.collectAsState()
    val connection by session.connection.collectAsState()
    val connectionNotice = connection.hasConnectionNotice()
    val resources = libraryResources()
    val context = LocalContext.current
    // Empty shelves hide rather than announce nothing; loading and failed shelves stay, saying so.
    val current = publication
    if (current != null && current.items.isEmpty() && current.freshness != AndroidLibraryFreshness.Loading &&
        current.freshness !is AndroidLibraryFreshness.Unavailable) return
    val title = resources.getString(row.row.titleResource())
    // A card's onClick may be one from an earlier composition: it queues the row as shown now, on the
    // controller bound now -- or, before the service binds, once it does.
    val shown by rememberUpdatedState(publication)
    val plays = rememberPlaybackBinding(playback)
    Column(Modifier.fillMaxWidth().testTag("library.home.$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            val type = (row.row as? com.legitimateapps.dulcet.core.AndroidLibraryHomeRow.Albums)?.type
            if (type == AndroidAlbumListType.Newest || type == AndroidAlbumListType.Recent) {
                TvAction("See all", "library.home.$index.seeAll", outlined = true) {
                    navigator.open(if (type == AndroidAlbumListType.Newest) ROUTE_RECENTLY_ADDED else ROUTE_RECENTLY_PLAYED)
                }
            }
        }
        if (current == null || current.items.isEmpty() && current.freshness == AndroidLibraryFreshness.Loading) {
            TvShelfSkeleton("library.home.$index.loading")
            return@Column
        }
        if (!connectionNotice) TvListStatus(current, "library.home.$index", session::retry)
        run {
                val state = rememberLazyListState()
                val returning = pendingPosition("library.home.$index.item.")
                LaunchedEffect(returning, current.items.size) {
                    if (returning != null && returning < current.items.size) state.scrollToItem(returning)
                }
                LazyRow(Modifier.testTag("library.home.$index.items"), state = state,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(current.items, key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                        TvContextCard(account, session, playback, item, "library.home.$index.item.$position") {
                            when (item) {
                                is AndroidLibraryItem.Album -> navigator.openAlbum(item.rawId)
                                is AndroidLibraryItem.Artist -> navigator.openArtist(item.rawId)
                                is AndroidLibraryItem.Track -> playTracks(plays, account.providerInstanceId,
                                        shown?.items.orEmpty(), item.rawId, title) { showNowPlaying(context) }
                                is AndroidLibraryItem.Playlist -> navigator.open(PLAYLIST + item.rawId)
                                else -> Unit
                            }
                        }
                    }
                }
        }
    }
}

/**
 * One home connection notice with Reconnect reachable from the remote's navigation bar.
 */
@Composable
private fun TvSavedAccountNotice(session: LibrarySession, account: SearchAccount, navigation: TvNavigationFocus) {
    val connection by session.connection.collectAsState()
    val resources = libraryResources()
    val line = resources.savedAccountLine(connection, account) ?: return
    DisposableEffect(navigation) {
        navigation.reconnectShown = true
        onDispose { navigation.reconnectShown = false }
    }
    Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 56.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TvAction(resources.getString(SharedR.string.library_reconnect), "library.reconnect",
            modifier = Modifier.focusRequester(navigation.reconnect).focusProperties { up = navigation.current },
            onClick = session::connectSavedAccount)
        TvStatement(if (connection == com.legitimateapps.dulcet.library.LibraryConnectionState.Connecting) "Reconnecting…" else "Showing saved music", "library.saved")
    }
}

/** The phone's `ConnectionNotices`, in the TV's type: the same statements, from the same copy. */
@Composable
internal fun TvConnectionNotices(session: LibrarySession, accountNotices: Boolean, local: Boolean = false) {
    val depth by session.depth.collectAsState()
    if (depth?.errorKind != null) TvStatement(stringResource(SharedR.string.library_depth_save_failed), "library.depth.error")
    if (local) return
    val connection by session.connection.collectAsState()
    val discarded by session.discardedChanges.collectAsState()
    val resources = libraryResources()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        resources.connectionLine(connection)?.let { line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(line, Modifier.testTag("library.connection"), color = MaterialTheme.colorScheme.error)
                TvAction(resources.getString(SharedR.string.library_try_again), "library.connection.retry", onClick = session::retry)
            }
        }
        if (accountNotices) {
            resources.discardedChangesLine(discarded)?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvStatement(line, "library.discarded")
                    TvAction(resources.getString(SharedR.string.library_dismiss), "library.discarded.dismiss",
                        onClick = session::dismissDiscardedChanges)
                }
            }
        }
    }
}

// ---- Albums and artists ---------------------------------------------------------------------------------

/**
 * Every album, in the order chosen on this device ([AlbumSort], shared with the phone), windowed
 * (§16.12): the viewport is reported, pages extend it. The order is one Sort button above the grid
 * opening a radio choice; choosing one opens that order's own window from its start and the remote
 * returns to the button.
 */
@Composable
private fun TvAlbumsGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator, playback: AndroidPlaybackController?) {
    val depth by session.depth.collectAsState()
    if (depth == null) { TvShelfSkeleton("library.albums.loading"); return }
    val filters = com.legitimateapps.dulcet.library.rememberLibraryCategory(session, "albums")
    EnterRoute()
    val sort = rememberAlbumSort(session)
    val order = sort.value
    val surface = rememberSurface(session, ROUTE_ALBUMS + ":" + order) { openAlbums(order) }
    TvBrowseGrid(account, session, surface, "library.albums", R.string.tv_library_albums, playback = playback,
        empty = TvEmptyCopy(
            plain = stringResource(R.string.tv_empty_albums),
            filtered = stringResource(R.string.tv_empty_favorite_albums),
            downloaded = stringResource(R.string.tv_empty_downloaded_albums),
        ),
        filters = filters,
        controls = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TvAlbumSort(order, sort::choose)
                Box(Modifier.weight(1f)) { TvLibraryCategoryControls(filters, showSort = false) }
            }
        }) { item ->
        if (item is AndroidLibraryItem.Album) navigator.openAlbum(item.rawId)
    }
}

/** The Albums order as one button naming it, opening a radio choice; the chosen one is marked. */
@Composable
private fun TvAlbumSort(order: AndroidAlbumListType, choose: (AndroidAlbumListType) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Button(onClick = { choosing = true }, modifier = Modifier.tvFocus("library.albums.sort")) {
        Icon(DulcetIcons.ExpandMore, null, Modifier.size(20.dp))
        Text(stringResource(R.string.tv_sort_button, stringResource(AlbumSort.titleResource(order))),
            Modifier.padding(start = 8.dp))
    }
    if (choosing) {
        TvChoiceDialog(
            tag = "library.albums.sort", surfaceTag = "library.albums.sort.menu",
            title = stringResource(R.string.tv_sort_dialog_title),
            choices = AlbumSort.CHOICES,
            choiceTag = { AlbumSort.tag(it) },
            choiceLabel = { stringResource(AlbumSort.titleResource(it)) },
            selected = order,
            onChoose = { choose(it); choosing = false },
            onDismiss = { choosing = false },
        )
    }
}

/** Every genre, one response (`getGenres`, §16.9), as wide text cards — genres have no art. */
@Composable
private fun TvGenresGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_GENRES) { openGenres() }
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val current = publication
    val grid = rememberLazyGridState()
    LazyVerticalGrid(GridCells.Adaptive(320.dp),
        Modifier.fillMaxSize().testTag("library.genres").semantics { this[LibraryObservation] = observation }, state = grid,
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(SharedR.string.library_genres), style = MaterialTheme.typography.displaySmall)
                TvConnectionNotices(session, accountNotices = false)
                if (current != null && current.items.isEmpty() && current.itemsState == AndroidLibraryItemsState.Present &&
                    current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading) {
                    TvEmptyState(stringResource(R.string.tv_empty_genres), null, "library.genres.empty")
                } else if (current != null) {
                    TvListStatus(current, "library.genres", session::retry)
                }
            }
        }
        itemsIndexed(current?.items.orEmpty(), key = { _, item -> item.rawId }) { position, item ->
            if (item is AndroidLibraryItem.Genre) {
                Card(
                    onClick = { navigator.open(GENRE + item.rawId) },
                    modifier = Modifier.fillMaxWidth().tvFocus("library.genres.item.$position", default = position == 0),
                ) {
                    Text(item.rawId, Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Every artist, one response (§16.9). */
@Composable
private fun TvArtistsGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_ARTISTS) { openArtists() }
    val filters = com.legitimateapps.dulcet.library.rememberLibraryCategory(session, "artists")
    TvBrowseGrid(account, session, surface, "library.artists", R.string.tv_library_artists,
        empty = TvEmptyCopy(
            plain = stringResource(R.string.tv_empty_artists),
            filtered = stringResource(R.string.tv_empty_favorite_artists),
            downloaded = stringResource(R.string.tv_empty_artists),
        ),
        filters = filters,
        controls = { TvLibraryCategoryControls(filters) }) { item ->
        if (item is AndroidLibraryItem.Artist) navigator.openArtist(item.rawId)
    }
}

/**
 * The ranked destination for [AndroidAlbumListType.Frequent]: large rank numerals on the cards and no
 * sort or filter — the server owns this order.
 */
@Composable
private fun TvMostPlayedGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_MOST_PLAYED) { openAlbums(AndroidAlbumListType.Frequent) }
    TvBrowseGrid(account, session, surface, "library.mostPlayed", R.string.tv_most_played, ranked = true,
        empty = TvEmptyCopy(stringResource(R.string.tv_most_played_empty), null, null)) { item ->
        if (item is AndroidLibraryItem.Album) navigator.openAlbum(item.rawId)
    }
}

@Composable
private fun TvPinnedGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator, playback: AndroidPlaybackController?) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_PINNED) { openPinned() }
    val context = LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    // Empty, the page keeps one thing to focus, so the remote never falls onto another tab.
    TvBrowseGrid(account, session, surface, "library.pinned", SharedR.string.library_pinned, playback = playback,
        empty = TvEmptyCopy(stringResource(R.string.tv_empty_pinned), null, null, stringResource(R.string.tv_empty_pinned_hint), DulcetIcons.WishBorder),
        emptyActionLabel = stringResource(R.string.tv_browse_music), onEmptyAction = { navigator.showRoot(ROUTE_BROWSE) }) { item ->
        when (item) {
            is AndroidLibraryItem.Album -> navigator.openAlbum(item.rawId)
            is AndroidLibraryItem.Artist -> navigator.openArtist(item.rawId)
            is AndroidLibraryItem.Playlist -> navigator.open(PLAYLIST + item.rawId)
            is AndroidLibraryItem.Track -> playTracks(plays, account.providerInstanceId, listOf(item), item.rawId, item.displayTitle()) { showNowPlaying(context) }
            else -> Unit
        }
    }
}

@Composable
private fun TvRecentAlbumsGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator, recent: Boolean) {
    EnterRoute()
    val route = if (recent) ROUTE_RECENTLY_PLAYED else ROUTE_RECENTLY_ADDED
    val surface = rememberSurface(session, route) { openAlbums(if (recent) AndroidAlbumListType.Recent else AndroidAlbumListType.Newest) }
    TvBrowseGrid(account, session, surface, "library.$route", if (recent) R.string.tv_recently_played else R.string.tv_recently_added,
        empty = TvEmptyCopy(if (recent) "No recently played albums" else "No recently added albums", null, null)) { item ->
        if (item is AndroidLibraryItem.Album) navigator.openAlbum(item.rawId)
    }
}

/** The empty copy of a filtered grid: the filter's own message, and the unfiltered one. */
internal class TvEmptyCopy(val plain: String, val filtered: String?, val downloaded: String?, val hint: String? = null,
                           val icon: androidx.compose.ui.graphics.vector.ImageVector = DulcetIcons.MusicNote)

@Composable
private fun TvBrowseGrid(account: SearchAccount, session: LibrarySession, surface: LibrarySurface, tag: String, title: Int,
                         controls: (@Composable () -> Unit)? = null, playback: AndroidPlaybackController? = null,
                         ranked: Boolean = false, empty: TvEmptyCopy? = null,
                         filters: com.legitimateapps.dulcet.library.LibraryCategoryState? = null,
                         emptyActionLabel: String? = null, onEmptyAction: (() -> Unit)? = null,
                         leadingCard: (@Composable () -> Unit)? = null,
                         open: (AndroidLibraryItem) -> Unit) {
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val current = publication
    val grid = rememberLazyGridState()
    // A different window (another order) is a different list: it starts at its top. The grid itself
    // stays, so the control that chose it keeps the remote.
    var opened by remember { mutableStateOf(surface) }
    LaunchedEffect(surface) {
        if (opened !== surface) {
            opened = surface
            grid.scrollToItem(0)
        }
    }
    ReportViewport(surface, grid, current)
    val returning = pendingPosition("$tag.item.")
    LaunchedEffect(returning, current?.items?.size) {
        val size = current?.items?.size ?: return@LaunchedEffect
        // Grid index 0 is the header; items start at 1.
        if (returning != null && returning < size) grid.scrollToItem(returning + 1)
    }
    val settledEmpty = current != null && current.items.isEmpty() &&
        current.itemsState == AndroidLibraryItemsState.Present &&
        current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading
    LazyVerticalGrid(GridCells.Adaptive(180.dp),
        Modifier.fillMaxSize().testTag(tag).semantics { this[LibraryObservation] = observation }, state = grid,
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.displaySmall)
                TvConnectionNotices(session, accountNotices = false)
                if (settledEmpty && empty != null) {
                    // An empty list shows no sort; a filtered-empty list keeps its chips so the filter
                    // can be cleared, and its own action clears it too.
                    val filtered = filters?.filter?.takeIf { it.isNotBlank() && it != "all" && !filters.category.endsWith("playlists") }
                    if (filtered != null) TvLibraryCategoryControls(filters, showSort = false)
                    val message = when (filtered) {
                        "favorites" -> empty.filtered ?: empty.plain
                        "downloaded" -> empty.downloaded ?: empty.plain
                        else -> empty.plain
                    }
                    when {
                        filtered != null && filters != null ->
                            TvEmptyState(message, stringResource(R.string.tv_empty_filtered_hint), "$tag.empty",
                                actionLabel = stringResource(R.string.tv_empty_show_all),
                                actionTag = "$tag.showAll") { filters.filterChoice("all") }
                        emptyActionLabel != null && onEmptyAction != null ->
                            TvEmptyState(message, empty.hint, "$tag.empty", actionLabel = emptyActionLabel,
                                actionTag = "$tag.empty.action", icon = empty.icon, onAction = onEmptyAction)
                        else -> TvEmptyState(message, empty.hint, "$tag.empty", icon = empty.icon)
                    }
                } else {
                    controls?.invoke()
                    if (current != null) {
                        TvListStatus(current, tag, session::retry)
                        if (current.growsAtEnd()) TvAction(stringResource(SharedR.string.library_load_more), "$tag.loadMore", onClick = surface::loadMore)
                    }
                }
            }
        }
        if (current == null || current.items.isEmpty() && current.itemsState == AndroidLibraryItemsState.Loading) {
            item(span = { GridItemSpan(maxLineSpan) }) { TvShelfSkeleton("$tag.loading.cards") }
        }
        if (leadingCard != null && !settledEmpty) item { leadingCard() }
        itemsIndexed(current?.items.orEmpty(), key = { _, item -> item.rawId }) { position, item ->
            TvContextCard(account, session, playback, item, "$tag.item.$position", default = leadingCard == null && position == 0,
                width = null, rank = if (ranked) position + 1 else null) { open(item) }
        }
    }
}

/**
 * Tells the window what is on screen — which the reader rebases and looks ahead around — and asks for
 * the next page near the end, as the phone's grid does. The reader decides whether a page is read.
 */
@Composable
private fun ReportViewport(surface: LibrarySurface, grid: LazyGridState, publication: AndroidLibraryPublication?) {
    val latest by rememberUpdatedState(publication)
    LaunchedEffect(surface, grid) {
        var lastScroll: Pair<Int, Int>? = null
        var lastShape: Pair<Int, Int>? = null
        var lastFreshness: AndroidLibraryFreshness? = null
        // Re-evaluated when the window changes shape as well as on every move, as the phone's grid is:
        // a window rebased or given back to what fits the screen can show its end with nowhere left to
        // move, and must still grow; a publication changing freshness or coverage alone asks for nothing.
        snapshotFlow {
            val visible = grid.layoutInfo.visibleItemsInfo.filter { it.key is String }
            Triple(visible.firstOrNull()?.index to visible.lastOrNull()?.index,
                latest?.let { it.items.size to it.leadingOffset }, latest?.freshness)
        }.collect { (range, shape, freshness) ->
            val (first, last) = range
            val shown = latest
            if (shown == null || first == null || last == null) return@collect
            val from = (first - 1).coerceAtLeast(0)
            val to = (last - 1).coerceAtLeast(from)
            val scroll = grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset
            // A status line appearing/disappearing can expose another card and nudge the scroll
            // anchor. Record that viewport, without treating the freshness reflow as another ask.
            val requestPages = shape != lastShape || (scroll != lastScroll && freshness == lastFreshness)
            lastScroll = scroll
            lastShape = shape
            lastFreshness = freshness
            surface.setViewport(from, to)
            if (requestPages) {
                if (shown.growsAtEnd() && to >= shown.items.size - PAGE_AHEAD) surface.loadMore()
                if (shown.leadingOffset > 0 && from == 0) surface.loadBefore()
            }
        }
    }
}

private const val PAGE_AHEAD = 12

/** A list's viewport, as [ReportViewport] for a grid: rows keyed by a string, after [header] other items. */
@Composable
private fun ReportListViewport(surface: LibrarySurface, list: LazyListState, publication: AndroidLibraryPublication?, header: Int) {
    val latest by rememberUpdatedState(publication)
    LaunchedEffect(surface, list) {
        var lastScroll: Pair<Int, Int>? = null
        var lastShape: Pair<Int, Int>? = null
        var lastFreshness: AndroidLibraryFreshness? = null
        // Re-evaluated when the window changes shape as well as on every move ([ReportViewport]).
        snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.key is String }
            Triple(visible.firstOrNull()?.index to visible.lastOrNull()?.index,
                latest?.let { it.items.size to it.leadingOffset }, latest?.freshness)
        }.collect { (range, shape, freshness) ->
            val (first, last) = range
            val shown = latest
            if (shown == null || first == null || last == null) return@collect
            val from = (first - header).coerceAtLeast(0)
            val to = (last - header).coerceAtLeast(from)
            val scroll = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
            // A status line appearing/disappearing can expose another card and nudge the scroll
            // anchor. Record that viewport, without treating the freshness reflow as another ask.
            val requestPages = shape != lastShape || (scroll != lastScroll && freshness == lastFreshness)
            lastScroll = scroll
            lastShape = shape
            lastFreshness = freshness
            surface.setViewport(from, to)
            if (requestPages) {
                if (shown.growsAtEnd() && to >= shown.items.size - PAGE_AHEAD) surface.loadMore()
                if (shown.leadingOffset > 0 && from == 0) surface.loadBefore()
            }
        }
    }
}

/** A list's actionable failure or loading progress; cached content stays visible below it. */
@Composable
internal fun TvListStatus(publication: AndroidLibraryPublication, tag: String, retry: () -> Unit, local: Boolean = false) {
    val resources = libraryResources()
    val freshness = publication.freshness
    val cachedFailure = (freshness as? AndroidLibraryFreshness.Cached)?.reason?.let { reason ->
        when (reason) {
            is com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.Failed -> AndroidLibraryUnavailableReason.Failed(reason.error)
            com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.InternalFailure -> AndroidLibraryUnavailableReason.InternalFailure
            else -> null
        }
    }
    when {
        cachedFailure != null -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvStatement(resources.unavailableLine(cachedFailure, LibrarySubject.List), "$tag.freshness")
                TvAction(resources.getString(SharedR.string.library_try_again), "$tag.retry", onClick = retry)
            }
        }
        freshness is AndroidLibraryFreshness.Unavailable || publication.itemsState == AndroidLibraryItemsState.Unavailable -> {
            val reason = (freshness as? AndroidLibraryFreshness.Unavailable)?.reason
                ?: publication.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvStatement(resources.unavailableLine(reason, LibrarySubject.List), "$tag.unavailable")
                TvAction(resources.getString(SharedR.string.library_try_again), "$tag.retry", onClick = retry)
            }
        }
        freshness == AndroidLibraryFreshness.Loading || publication.itemsState == AndroidLibraryItemsState.Loading ->
            TvLoadingProgress("$tag.loading")
    }
}

// ---- Album ----------------------------------------------------------------------------------------------

/** The album header's metadata line: the year, how many songs, how long they run. */
@Composable
private fun TvAlbumMetadata(album: AndroidLibraryItem.Album, publication: AndroidLibraryPublication,
                            tracks: List<AndroidLibraryItem.Track>) {
    val parts = mutableListOf<String>()
    (album.originalReleaseDate?.take(4) ?: album.year?.toString())?.let { parts += it }
    val count = publication.total ?: tracks.size
    if (count > 0) parts += pluralStringResource(R.plurals.tv_track_count, count, count)
    val minutes = (tracks.mapNotNull { it.durationMilliseconds }.sum() / 60_000L).takeIf { it > 0 }
    if (minutes != null) parts += pluralStringResource(R.plurals.tv_minutes, minutes.toInt(), minutes)
    if (parts.isNotEmpty()) {
        Text(parts.joinToString(" · "), Modifier.testTag("album.meta"),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TvAlbumScreen(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    playingRawId: String?,
    rawId: String,
    navigator: TvNavigator,
    downloadRequests: DownloadRequests? = null,
    myLibrary: MyLibrarySession? = null,
) {
    EnterRoute()
    val surface = rememberSurface(session, ALBUM + rawId) { openAlbum(rawId) }
    val context = LocalContext.current
    val requests = downloadRequests ?: remember(context) { AccountDownloadRequests.of(context) }
    val downloads = rememberDownloadStatuses(requests)
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    // Only outcomes about this album and its tracks are said here, and they go when the screen does.
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Album, rawId)
    val outcomeLines = rememberOutcomeLines(session,
        listOf(target) + publication?.items.orEmpty().mapNotNull { it.favouriteTarget() })
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    var adding by remember(rawId) { mutableStateOf<TvQueueAddition?>(null) }
    TvAddToUpNext(adding, session, account) { adding = null }
    val resources = libraryResources()
    val provider = account.providerInstanceId
    // tv-material buttons can keep an onClick from an earlier composition, and with it this screen's
    // `playback` as it was then -- null, if that was before the service bound. The binding is the
    // same object in every composition and plays on the controller bound when the press lands, or
    // holds the play until one binds; Now Playing opens once it has been made.
    val plays = rememberPlaybackBinding(playback)
    fun play(current: AndroidLibraryPublication, trackRawId: String?, shuffle: Boolean) {
        // A note that a track plays only on reconnect no longer applies.
        note = null
        playAlbum(plays, provider, current, trackRawId, shuffle) { showNowPlaying(context) }
    }
    // tv-material buttons can keep an onClick from an earlier composition: they play what is shown now.
    val shown by rememberUpdatedState(publication)
    var menu by remember(rawId) { mutableStateOf(false) }
    val current = publication
    val album = current?.header as? AndroidLibraryItem.Album
    val returning = pendingPosition("album.track.")
    val list = rememberLazyListState()
    LaunchedEffect(returning, publication?.items?.size) {
        val size = publication?.items?.size ?: return@LaunchedEffect
        // One item above the tracks: the header.
        if (returning != null && returning < size) list.scrollToItem(returning + 1)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("album.surface").semantics { this[LibraryObservation] = observation },
        state = list,
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvConnectionNotices(session, accountNotices = false)
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    TvArtwork(account, album?.artworkKey, 220, DulcetIcons.Album)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(album?.title ?: stringResource(R.string.tv_library_albums), Modifier.testTag("album.title"),
                            style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (album == null) TvLoadingProgress("album.header.loading")
                        album?.artistName?.let {
                            Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (current != null && album != null) {
                            val albumTracks = current.items.filterIsInstance<AndroidLibraryItem.Track>()
                            TvAlbumMetadata(album, current, albumTracks)

                            (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                                TvStatement(resources.unavailableLine(unavailable.reason, LibrarySubject.Album), "album.unavailable")
                            }
                            if (current.itemsState == AndroidLibraryItemsState.Present) {
                                context.resources.albumDownloadLine(AlbumDownloads.of(albumTracks, downloads))
                                    ?.let { TvStatement(it, "album.download.progress") }
                            }
                            val playable = current.playableTracks(provider).isNotEmpty()
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (playable) {
                                    TvAction(stringResource(R.string.tv_play), "album.play", icon = DulcetIcons.Play, default = true) {
                                        shown?.let { play(it, null, false) }
                                    }
                                    TvAction(stringResource(R.string.tv_shuffle), "album.shuffle", icon = DulcetIcons.Shuffle, outlined = true) {
                                        shown?.let { play(it, null, true) }
                                    }
                                }
                                TvFavouriteButton(album.favourite == true, "album.favourite") {
                                    session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId))
                                }
                                IconButton(onClick = { menu = true }, modifier = Modifier.tvFocus("album.more")) {
                                    Icon(DulcetIcons.MoreVert, resources.getString(R.string.tv_more_options))
                                }
                            }

                        }
                        TvOutcomeLines(outcomeLines, target, "album.outcome")
                        note?.let { TvStatement(it, "album.note") }
                    }
                }
            }
        }
        if (current == null) return@LazyColumn
        when (current.itemsState) {
            AndroidLibraryItemsState.Loading -> item { TvLoadingProgress("album.tracks.loading") }
            AndroidLibraryItemsState.Unavailable -> if (current.header != null) item {
                // The header is cached and the track list is not: the core says why (§16.14).
                TvStatement(
                    resources.unavailableLine(
                        current.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure,
                        LibrarySubject.Album,
                    ),
                    "album.tracks.unavailable",
                )
            }
            AndroidLibraryItemsState.Present -> itemsIndexed(current.items) { position, item ->
                if (item is AndroidLibraryItem.Track) TvTrackRow(
                    item,
                    position,
                    playing = item.rawId == playingRawId,
                    onPlay = {
                        // The playing track opens Now Playing rather than starting it over.
                        if (item.rawId == playingRawId && playback != null) showNowPlaying(context)
                        else play(current, item.rawId, false)
                    },
                    onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                    onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, item.rawId)) },
                    byPosition = true,
                    download = downloads[item.rawId],
                    onDownloadToggle = when {
                        item.rawId in downloads -> { { requests.remove(listOf(item.rawId)) } }
                        item.downloadItem() != null -> { { requests.download(listOf(item)) } }
                        else -> null
                    },
                    account = account,
                    queue = tvTrackAddition(context, playback, provider, item, album),
                    session = session,
                    library = TvMembershipAction(myLibrary, AndroidLibraryEntity(AndroidLibraryEntityKind.Track, item.rawId)),
                )
            }
        }
    }
    if (menu && album != null) {
        val albumTracks = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
        val downloadState = AlbumDownloads.of(albumTracks, downloads)
        val depth by session.depth.collectAsState()
        TvQueueMenu(
            "album.menu", album.title,
            buildList {
                if (playback != null && current?.playableTracks(provider)?.isNotEmpty() == true) {
                    add(TvQueueChoice(resources.getString(SharedR.string.queue_add_options), "album.queue") {
                        val shownNow = shown ?: return@TvQueueChoice
                        fun add(insertion: AndroidQueueInsertion) {
                            if (!queueAlbum(playback, provider, shownNow, insertion)) queueEditRefused(context)
                        }
                        adding = TvQueueAddition(album.title,
                            { add(AndroidQueueInsertion.PlayNext) }, { add(AndroidQueueInsertion.AddToQueue) },
                            TvPlaylistAddition.Album(rawId, album.title, albumTracks.map { it.rawId }))
                    })
                }
                if (current?.itemsState == AndroidLibraryItemsState.Present) {
                    if (downloadState.anyRequested) {
                        add(TvQueueChoice(resources.getString(SharedR.string.download_album_remove), "album.download.remove") {
                            requests.remove(albumTracks.map { it.rawId }.filter { it in downloads })
                        })
                    } else if (downloadState.downloadable > 0) {
                        add(TvQueueChoice(resources.getString(SharedR.string.download_album), "album.download") { requests.download(albumTracks) })
                    }
                }
                TvMembershipAction(myLibrary, AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId))?.let { toggle ->
                    add(TvQueueChoice(resources.getString(
                        if (toggle.added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library),
                        "album.library", toggle.toggle))
                }
                if (depth != null) {
                    val pinned = session.isPinned(album)
                    add(TvQueueChoice(resources.getString(if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin),
                        "album.pin") { session.togglePin(album) })
                }
                // Playback first, upkeep last, as on the phone.
                add(TvQueueChoice(resources.getString(R.string.tv_refresh), "album.refresh", session::refresh))
            },
            resources.getString(SharedR.string.queue_cancel),
            supporting = tvItemSubtitle(album),
            artwork = { TvArtwork(account, album.artworkKey, 64, DulcetIcons.Album) },
        ) { menu = false }
    }
}

/**
 * The account's favorites (§16.9 `getStarred2`): albums and artists as rows of cards, then songs.
 * Removing a favorite leaves it in the visible publication until the next read, so it can be
 * restored from the same contextual action.
 */
@Composable
private fun TvFavouritesScreen(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    playingRawId: String?,
    navigator: TvNavigator,
) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_FAVOURITES) { openFavourites() }
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val context = LocalContext.current
    val resources = libraryResources()
    val current = publication
    val items = current?.items.orEmpty()
    val tracks = items.filterIsInstance<AndroidLibraryItem.Track>()
    val albums = items.filterIsInstance<AndroidLibraryItem.Album>()
    val artists = items.filterIsInstance<AndroidLibraryItem.Artist>()
    val outcomeLines = rememberOutcomeLines(session, items.mapNotNull { it.favouriteTarget() })
    var note by remember { mutableStateOf<String?>(null) }
    val shown by rememberUpdatedState(publication)
    val plays = rememberPlaybackBinding(playback)
    val title = resources.getString(R.string.tv_favorites)
    val empty = current != null && current.items.isEmpty() && current.itemsState == AndroidLibraryItemsState.Present &&
        current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading
    LazyColumn(
        Modifier.fillMaxSize().testTag("library.favourites").semantics { this[LibraryObservation] = observation },
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tv_favorites), style = MaterialTheme.typography.displaySmall)
                TvConnectionNotices(session, accountNotices = false)
                if (empty) {

                    TvEmptyState(
                        stringResource(R.string.tv_favorites_empty_title),
                        stringResource(R.string.tv_favorites_empty_hint),
                        "library.favourites.empty",
                        actionLabel = stringResource(R.string.tv_browse_catalog),
                        actionTag = "library.favourites.browse",
                    ) { navigator.showRoot(ROUTE_BROWSE) }
                } else if (current != null) {
                    TvListStatus(current, "library.favourites", session::retry)
                }
                TvOutcomeLines(outcomeLines, null, "library.favourites.outcome")
                note?.let { TvStatement(it, "library.favourites.note") }
            }
        }
        if (albums.isNotEmpty()) {
            item { Text(stringResource(SharedR.string.library_favourites_albums), style = MaterialTheme.typography.headlineSmall) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(albums, key = { _, album -> album.rawId }) { position, album ->
                        TvContextCard(account, session, playback, album, "library.favourites.album.$position", default = position == 0) {
                            navigator.openAlbum(album.rawId)
                        }
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item { Text(stringResource(SharedR.string.library_favourites_artists), style = MaterialTheme.typography.headlineSmall) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(artists, key = { _, artist -> artist.rawId }) { position, artist ->
                        TvContextCard(account, session, playback, artist, "library.favourites.artist.$position",
                            default = albums.isEmpty() && position == 0) { navigator.openArtist(artist.rawId) }
                    }
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item { Text(stringResource(SharedR.string.library_favourites_songs), style = MaterialTheme.typography.headlineSmall) }
            itemsIndexed(tracks, key = { _, track -> "track:" + track.rawId }) { position, track ->
                TvTrackRow(track, position, playing = track.rawId == playingRawId, tagPrefix = "library.favourites.track",
                    default = albums.isEmpty() && artists.isEmpty() && position == 0,
                    onPlay = {
                        note = null
                        val list = shown?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
                        playTracks(plays, account.providerInstanceId, list, track.rawId, title) { showNowPlaying(context) }
                    },
                    onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                    onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)) },
                    account = account,
                    queue = tvTrackAddition(context, playback, account.providerInstanceId, track, null),
                    session = session,
                )
            }
        }
    }
}

/** The outcome lines of a screen's hearts: [primary]'s under [tag], any other's under `tag.<kind>.<id>`. */
@Composable
private fun TvOutcomeLines(lines: List<Pair<AndroidLibraryEntity, String>>, primary: AndroidLibraryEntity?, tag: String) {
    for ((target, line) in lines) {
        TvStatement(line, if (target == primary) tag else "$tag.${target.kind.name.lowercase()}.${target.rawId}")
    }
}

/**
 * Play Next and Add to Queue for one track, as the phone's row menu offers them, or null for a track
 * that cannot be queued now. A refusal is said.
 */
internal fun tvTrackAddition(
    context: android.content.Context,
    playback: AndroidPlaybackController?,
    provider: String,
    track: AndroidLibraryItem.Track,
    album: AndroidLibraryItem.Album?,
    playlist: AndroidLibraryItem.Playlist? = null,
): TvQueueAddition? {
    // Hidden while no playback service is bound, rather than offered and then refused (spec §14.1).
    if (playback == null || !track.canBeQueued()) return null
    val library = context.getString(R.string.tv_library_title)
    fun add(insertion: AndroidQueueInsertion) {
        val accepted = if (playlist == null) queueTrack(playback, provider, track, insertion, library, album)
            else queuePlaylistTrack(playback, provider, track, playlist, insertion)
        if (!accepted) queueEditRefused(context)
    }
    return TvQueueAddition(context.resources.trackDisplayTitle(track.title), { add(AndroidQueueInsertion.PlayNext) }, { add(AndroidQueueInsertion.AddToQueue) },
        TvPlaylistAddition.Songs(listOf(track.rawId), context.resources.trackDisplayTitle(track.title)))
}

/**
 * Play Next and Add to Queue for a search track (spec §14.1), or null for anything else, for a track
 * that cannot play now, or while no playback service is bound. A refusal is said.
 */
internal fun tvSearchAddition(
    context: android.content.Context,
    playback: AndroidPlaybackController?,
    provider: String?,
    result: SearchResultItem,
    playability: AndroidLibraryPlayability?,
): TvQueueAddition? {
    if (playback == null || provider == null || result.type != SearchResultType.Track) return null
    if (playability == AndroidLibraryPlayability.UnavailableOffline) return null
    val search = context.getString(R.string.tv_nav_search)
    fun add(insertion: AndroidQueueInsertion) {
        if (!queueSearchResult(playback, provider, result, insertion, search)) queueEditRefused(context)
    }
    return TvQueueAddition(context.resources.trackDisplayTitle(result.title), { add(AndroidQueueInsertion.PlayNext) }, { add(AndroidQueueInsertion.AddToQueue) })
}

@Composable
private fun TvFavouriteButton(favourite: Boolean, tag: String, onClick: () -> Unit) {
    val resources = libraryResources()
    IconButton(onClick = onClick, modifier = Modifier.tvFocus(tag).semantics { selected = favourite }) {
        Icon(if (favourite) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder,
            resources.getString(if (favourite) R.string.tv_favorite_remove else R.string.tv_favorite_add))
    }
}

/**
 * One track: a tv-material ListItem — artwork (or the album track number), title,
 * artist and duration — with one More action; Favorite is a contextual action. Selected (tonal) means the row
 * is the one playing. A long centre press opens the row's menu (`queue.add`): Play Next, Add to
 * Queue, Add to Playlist…, then Download, library membership and Pin as offered. A row this device
 * cannot play offline says why, as the phone's does (§16.14), and a row whose playability changes
 * while focused keeps its focus. Its action is the centre key or Enter, on release, and a click for
 * accessibility services.
 */
@Composable
internal fun TvTrackRow(track: AndroidLibraryItem.Track, position: Int, playing: Boolean, onPlay: () -> Unit,
                       onUnavailable: () -> Unit, onFavourite: (() -> Unit)? = null, tagPrefix: String = "album.track",
                       default: Boolean = false, byPosition: Boolean = false,
                       download: AndroidDownloadStatus? = null, onDownloadToggle: (() -> Unit)? = null,
                       account: SearchAccount? = null, queue: TvQueueAddition? = null,
                       session: LibrarySession? = null, library: TvLibraryToggle? = null,
                       pin: TvLibraryToggle? = null) {
    val resources = libraryResources()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<TvPlaylistAddition?>(null) }
    if (choosing != null && session != null) TvAddToPlaylist(session, choosing!!, account) { choosing = null }
    val choices = buildList {
        if (onFavourite != null) add(TvQueueChoice(
            resources.getString(if (track.isFavourite()) R.string.tv_favorite_remove else R.string.tv_favorite_add),
            "$tagPrefix.$position.favourite", onFavourite))
        if (queue != null) {
            add(TvQueueChoice(resources.getString(SharedR.string.queue_play_next), "queue.add.playNext", queue.playNext))
            add(TvQueueChoice(resources.getString(SharedR.string.queue_add_to_queue), "queue.add.addToQueue", queue.addToQueue))
            queue.toPlaylist?.let { playlist ->
                if (session != null) {
                    add(TvQueueChoice(resources.getString(R.string.tv_playlist_add), "queue.add.playlist") { choosing = playlist })
                }
            }
        }
        if (onDownloadToggle != null) {
            add(TvQueueChoice(
                resources.getString(if (download != null) SharedR.string.download_track_remove else SharedR.string.download_track),
                "$tagPrefix.$position.actions.download", onDownloadToggle))
        }
        if (library != null) {
            add(TvQueueChoice(
                resources.getString(if (library.added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library),
                "$tagPrefix.$position.actions.library", library.toggle))
        }
        if (pin != null) {
            add(TvQueueChoice(
                resources.getString(if (pin.added) SharedR.string.library_unpin else SharedR.string.library_pin),
                "$tagPrefix.$position.actions.pin", pin.toggle))
        }
    }
    if (menu && choices.isNotEmpty()) {
        TvQueueMenu("queue.add", resources.trackDisplayTitle(track.title), choices,
            resources.getString(SharedR.string.queue_cancel)) { menu = false }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTrackRowBody(track, position, playing, onPlay, onUnavailable, tagPrefix, default, Modifier.weight(1f), byPosition,
            download, account, onLongClick = if (choices.isEmpty()) null else ({ menu = true }))
        if (choices.isNotEmpty()) IconButton(onClick = { menu = true },
            modifier = Modifier.tvFocus("$tagPrefix.$position.more")) {
            Icon(DulcetIcons.MoreVert, resources.getString(R.string.tv_more_options))
        }
    }
}

@Composable
private fun TvTrackRowBody(track: AndroidLibraryItem.Track, position: Int, playing: Boolean, onPlay: () -> Unit,
                           onUnavailable: () -> Unit, tagPrefix: String, default: Boolean, modifier: Modifier,
                           byPosition: Boolean = false, download: AndroidDownloadStatus? = null,
                           account: SearchAccount? = null, onLongClick: (() -> Unit)? = null) {
    val unavailable = track.playability == AndroidLibraryPlayability.UnavailableOffline
    val select = if (unavailable) onUnavailable else onPlay
    val resources = libraryResources()
    val nowPlaying = stringResource(R.string.tv_now_playing_row)
    ListItem(
        selected = playing,
        onClick = select,
        onLongClick = onLongClick,
        modifier = modifier.tvFocus("$tagPrefix.$position", default)
            .semantics {
                if (unavailable) {
                    contentDescription = "${resources.trackDisplayTitle(track.title)}, ${resources.getString(SharedR.string.library_not_available_offline)}"
                }
            },
        leadingContent = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (byPosition) {
                    Text((track.trackNumber ?: position + 1).toString(), style = MaterialTheme.typography.titleMedium)
                } else {
                    TvArtwork(account, track.artworkKey, 48, DulcetIcons.MusicNote, Modifier.size(48.dp))
                }

            }
        },
        supportingContent = {
            val artist = track.artistName ?: track.albumTitle
            // The offline reason is its own line, as it was when rows were stacked: tests and
            // screen readers meet it whole.
            val line = if (unavailable) resources.getString(SharedR.string.library_not_available_offline)
                else {
                    val downloadLine = if (download != null && !download.playsOffline)
                        LocalContext.current.resources.downloadLine(download) else null
                    listOfNotNull(artist, track.durationMilliseconds?.let(::clock), downloadLine).joinToString(" · ")
                }
            if (line.isNotEmpty()) {
                Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = if (!unavailable && download != null && !download.playsOffline)
                        Modifier.testTag("$tagPrefix.$position.download.status") else Modifier)
            }
        },
        trailingContent = {
            when {
                playing -> Icon(DulcetIcons.Play, nowPlaying,
                    Modifier.size(24.dp).testTag("$tagPrefix.$position.playing"))
                download?.playsOffline == true -> Icon(DulcetIcons.DownloadDone,
                    resources.getString(SharedR.string.download_downloaded),
                    Modifier.size(24.dp).testTag("$tagPrefix.$position.downloaded"))
                track.isFavourite() -> Icon(DulcetIcons.Favourite,
                    resources.getString(R.string.tv_favorite_remove), Modifier.size(24.dp))
            }
        },
        headlineContent = {
            Text(resources.trackDisplayTitle(track.title), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
    )
}

// ---- Playlists (spec §18.6): browse, play, delete, and Add to Playlist… (TvPlaylistAdd.kt) -----------

/** The account's playlists, one response (§16.9); a "New playlist" card leads the grid. */
@Composable
private fun TvPlaylistsGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_PLAYLISTS) { openPlaylists() }
    val filters = com.legitimateapps.dulcet.library.rememberLibraryCategory(session, "playlists")
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TvPlaylistCreateDialog(session) { localId ->
            creating = false
            if (localId != null) navigator.open(PLAYLIST + localId)
        }
    }
    TvBrowseGrid(account, session, surface, "library.playlists", R.string.tv_library_playlists,
        empty = TvEmptyCopy(stringResource(R.string.tv_empty_playlists), null, null),
        emptyActionLabel = stringResource(R.string.tv_new_playlist), onEmptyAction = { creating = true },
        filters = filters,
        controls = { TvLibraryCategoryControls(filters) },
        leadingCard = {
            StandardCardContainer(modifier = Modifier.fillMaxWidth(),
                imageCard = { interaction ->
                    Card(onClick = { creating = true }, interactionSource = interaction,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).tvFocus("library.playlists.new", default = true)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(DulcetIcons.Add, null, Modifier.size(48.dp)) }
                    }
                },
                title = { Text(stringResource(R.string.tv_new_playlist), Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2) },
                subtitle = { Text(" ", style = MaterialTheme.typography.bodyMedium) })
        }) { item ->
        if (item is AndroidLibraryItem.Playlist) navigator.open(PLAYLIST + item.rawId)
    }
}

/**
 * One playlist: its name, whose it is, and its entries in the playlist's order, duplicates kept.
 * Play and Shuffle queue the playable entries; an entry plays the playlist from it. Delete Playlist…,
 * on one the person may edit, asks first and goes Back once the delete is queued. A create waiting
 * for the person's answer says so, with Choose… to ask again; a change that did not land is said,
 * with Dismiss. A playlist made under a local id is followed to the server's id once it has one.
 */
@Composable
private fun TvPlaylistScreen(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    playingRawId: String?,
    rawId: String,
    navigator: TvNavigator,
) {
    EnterRoute()
    val created by session.createdPlaylists.collectAsState()
    LaunchedEffect(created[rawId]) { created[rawId]?.let { navigator.replace(PLAYLIST + rawId, PLAYLIST + it) } }
    val surface = rememberSurface(session, PLAYLIST + rawId) { openPlaylist(rawId) }
    val context = LocalContext.current
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val outcomes by session.playlistOutcomes.collectAsState()
    var deleting by remember(rawId) { mutableStateOf(false) }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    var adding by remember(rawId) { mutableStateOf<TvQueueAddition?>(null) }
    TvAddToUpNext(adding, session, account) { adding = null }
    val resources = libraryResources()
    val provider = account.providerInstanceId
    val plays = rememberPlaybackBinding(playback)
    fun play(current: AndroidLibraryPublication, position: Int, shuffle: Boolean) {
        note = null
        playPlaylist(plays, provider, current, position, shuffle) { showNowPlaying(context) }
    }
    // tv-material buttons can keep an onClick from an earlier composition: they play what is shown now.
    val shown by rememberUpdatedState(publication)
    var menu by remember(rawId) { mutableStateOf(false) }
    val current = publication
    val playlist = current?.header as? AndroidLibraryItem.Playlist
    val returning = pendingPosition("playlist.entry.")
    val list = rememberLazyListState()
    LaunchedEffect(returning, publication?.items?.size) {
        val size = publication?.items?.size ?: return@LaunchedEffect
        if (returning != null && returning < size) list.scrollToItem(returning + 1)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("playlist.surface").semantics { this[LibraryObservation] = observation },
        state = list,
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvConnectionNotices(session, accountNotices = false)
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    TvArtwork(account, playlist?.artworkKey, 220, DulcetIcons.PlaylistPlay)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(playlist?.name ?: stringResource(R.string.tv_library_playlists), Modifier.testTag("playlist.title"),
                            style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (playlist == null) TvLoadingProgress("playlist.header.loading")
                        playlist?.let { resources.playlistOwnerLine(it) }?.let { TvStatement(it, "playlist.owner") }
                        playlist?.let { resources.playlistPendingLine(it) }?.let { TvStatement(it, "playlist.pending") }
                        if (current != null && playlist != null) {
                            val parts = mutableListOf<String>()
                            playlist.songCount?.let { parts += pluralStringResource(R.plurals.tv_track_count, it, it) }
                            playlist.durationMilliseconds?.let {
                                val minutes = (it / 60_000L).toInt()
                                if (minutes > 0) parts += pluralStringResource(R.plurals.tv_minutes, minutes, minutes)
                            }
                            if (parts.isNotEmpty()) {
                                Text(parts.joinToString(" · "), Modifier.testTag("playlist.meta"),
                                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                                TvStatement(resources.unavailableLine(unavailable.reason, LibrarySubject.List), "playlist.unavailable")
                            }
                            val playable = current.itemsState == AndroidLibraryItemsState.Present && current.items.any {
                                it is AndroidLibraryItem.Track && it.canBeQueued()
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (playable) {
                                    TvAction(stringResource(R.string.tv_play), "playlist.play", icon = DulcetIcons.Play, default = true) {
                                        shown?.let { play(it, 0, false) }
                                    }
                                    TvAction(stringResource(R.string.tv_shuffle), "playlist.shuffle", icon = DulcetIcons.Shuffle, outlined = true) {
                                        shown?.let { play(it, 0, true) }
                                    }
                                }
                                TvAction(resources.getString(R.string.tv_more_options), "playlist.more",
                                    icon = DulcetIcons.MoreVert, default = !playable, outlined = true) { menu = true }
                            }
                        }
                        TvPlaylistNotices(session.playlistQuestions, rawId, outcomes[rawId]) { session.dismissPlaylistOutcome(rawId) }
                        note?.let { TvStatement(it, "playlist.note") }
                    }
                }
            }
        }
        if (current == null) return@LazyColumn
        when (current.itemsState) {
            AndroidLibraryItemsState.Loading -> item { TvLoadingProgress("playlist.entries.loading") }
            AndroidLibraryItemsState.Unavailable -> if (current.header != null) item {
                TvStatement(
                    resources.unavailableLine(
                        current.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure,
                        LibrarySubject.List,
                    ),
                    "playlist.entries.unavailable",
                )
            }
            AndroidLibraryItemsState.Present -> {
                if (current.items.isEmpty()) item { TvEmptyState("This playlist is empty",
                    "Add songs from an album or a song’s More options.", "playlist.empty") }
                itemsIndexed(current.items) { position, item ->
                    if (item is AndroidLibraryItem.Track) TvTrackRow(
                        item,
                        position,
                        playing = item.rawId == playingRawId,
                        onPlay = { play(current, position, false) },
                        onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                        tagPrefix = "playlist.entry",
                        byPosition = false,
                        account = account,
                        queue = tvTrackAddition(context, playback, provider, item, null, playlist),
                        session = session,
                    )
                }
            }
        }
    }
    if (menu && playlist != null) {
        val depth by session.depth.collectAsState()
        TvQueueMenu(
            "playlist.menu", playlist.name,
            buildList {
                if (depth != null && !playlist.local) {
                    val pinned = session.isPinned(playlist)
                    add(TvQueueChoice(resources.getString(if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin),
                        "playlist.pin") { session.togglePin(playlist) })
                }
                if (playlist.editable) {
                    add(TvQueueChoice(resources.getString(R.string.tv_playlist_delete), "playlist.delete",
                        icon = null, destructive = true) { note = null; deleting = true })
                }
            },
            resources.getString(SharedR.string.queue_cancel),
            supporting = tvItemSubtitle(playlist),
            artwork = { TvArtwork(account, playlist.artworkKey, 64, DulcetIcons.PlaylistPlay) },
        ) { menu = false }
    }
    val named = playlist?.name
    if (deleting && named != null) TvPlaylistDeleteDialog(named, onCancel = { deleting = false }) {
        deleting = false
        session.playlists.delete(rawId) { result ->
            // Queued, or a local one never sent simply gone: the page has nothing left to show.
            if (result.record == AndroidPlaylistEditRecord.Pending || result.record == AndroidPlaylistEditRecord.CompactedAway) {
                navigator.leave(PLAYLIST + rawId)
            } else {
                note = resources.playlistEditLine(result)
            }
        }
    }
}

// ---- Genre ----------------------------------------------------------------------------------------------

/**
 * One genre's songs (`getSongsByGenre`, windowed, §16.9), as the Apple shells show a genre: its name,
 * how many songs, Play and Shuffle — where the remote lands — and the songs, each playing the genre's
 * playable songs from itself, with its heart and Play Next and Add to Queue to its right.
 */
@Composable
private fun TvGenreScreen(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    playingRawId: String?,
    name: String,
    navigator: TvNavigator,
    myLibrary: MyLibrarySession? = null,
) {
    EnterRoute()
    val surface = rememberSurface(session, GENRE + name) { openGenre(name) }
    val context = LocalContext.current
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    var note by remember(name) { mutableStateOf<String?>(null) }
    var adding by remember(name) { mutableStateOf<TvQueueAddition?>(null) }
    TvAddToUpNext(adding, session, account) { adding = null }
    val resources = libraryResources()
    val provider = account.providerInstanceId
    // tv-material buttons can keep an onClick from an earlier composition: they play what is shown now.
    val shown by rememberUpdatedState(publication)
    val plays = rememberPlaybackBinding(playback)
    fun play(trackRawId: String?, shuffle: Boolean) {
        note = null
        val items = shown?.items.orEmpty()
        playTracks(plays, provider, items, trackRawId, name, shuffle) { showNowPlaying(context) }
    }
    val tracks = publication?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
    val outcomeLines = rememberOutcomeLines(session, tracks.mapNotNull { it.favouriteTarget() })
    val returning = pendingPosition("genre.track.")
    val list = rememberLazyListState()
    LaunchedEffect(returning, tracks.size) {
        if (returning != null && returning < tracks.size) list.scrollToItem(returning + GENRE_HEADER_ITEMS)
    }
    ReportListViewport(surface, list, publication, GENRE_HEADER_ITEMS)
    LazyColumn(
        Modifier.fillMaxSize().testTag("genre.surface").semantics { this[LibraryObservation] = observation },
        state = list,
        contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val current = publication
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvConnectionNotices(session, accountNotices = false)
                Text(name, Modifier.testTag("genre.title"), style = MaterialTheme.typography.displaySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val count = current?.total ?: tracks.size.takeIf { current?.itemsState == AndroidLibraryItemsState.Present }
                if (count != null && count > 0) TvStatement(resources.getQuantityString(SharedR.plurals.library_count_tracks,
                    count, count.toString()), "genre.count")
                val playable = current?.itemsState == AndroidLibraryItemsState.Present &&
                    tracks.any { it.canBeQueued() }
                if (playable) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvAction(stringResource(R.string.tv_play), "genre.play", icon = DulcetIcons.Play, default = true) { play(null, false) }
                    TvAction(stringResource(R.string.tv_shuffle), "genre.shuffle", icon = DulcetIcons.Shuffle, outlined = true) { play(null, true) }
                }
                if (current != null) TvListStatus(current, "genre", session::retry)
                TvOutcomeLines(outcomeLines, null, "genre.outcome")
                note?.let { TvStatement(it, "genre.note") }
            }
        }
        itemsIndexed(tracks, key = { _, track -> track.rawId }) { position, track ->
            TvTrackRow(
                track,
                position,
                playing = track.rawId == playingRawId,
                onPlay = { play(track.rawId, false) },
                onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)) },
                tagPrefix = "genre.track",
                byPosition = true,
                account = account,
                queue = tvTrackAddition(context, playback, provider, track, null),
                session = session,
                library = TvMembershipAction(myLibrary, AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)),
            )
        }
    }
}

/** The genre screen's items before its songs: the header. */
private const val GENRE_HEADER_ITEMS = 1

// ---- Artist ---------------------------------------------------------------------------------------------

@Composable
private fun TvArtistScreen(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    rawId: String,
    navigator: TvNavigator,
    myLibrary: MyLibrarySession? = null,
) {
    EnterRoute()
    val surface = rememberSurface(session, ARTIST + rawId) { openArtist(rawId) }
    val topSurface = rememberSurface(session, "artistTopSongs:$rawId") { openArtistTopSongs(rawId) }
    val topSongs by topSurface.state.collectAsState()
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val context = LocalContext.current
    val resources = libraryResources()
    val current = publication
    val artist = current?.header as? AndroidLibraryItem.Artist
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Artist, rawId)
    val outcomeLines = rememberOutcomeLines(session, listOf(target))
    // Playing an artist opens each album first; leaving the screen abandons that, so nothing starts
    // playing after the person has gone, and a second press while it runs does nothing.
    var collecting by remember(rawId) { mutableStateOf<AutoCloseable?>(null) }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    DisposableEffect(rawId) { onDispose { collecting?.close() } }
    val plays = rememberPlaybackBinding(playback)
    fun play(publication: AndroidLibraryPublication, shuffle: Boolean) {
        if (collecting != null) return
        note = null
        var finished = false
        val handle = playArtist(plays, session, account.providerInstanceId, publication, shuffle) { result ->
            finished = true
            collecting = null
            note = when (result) {
                ArtistPlayResult.Played -> { showNowPlaying(context); null }
                // Some album holds tracks this device could play once it reconnects.
                ArtistPlayResult.NeedsConnection -> resources.getString(SharedR.string.library_plays_on_reconnect)
                ArtistPlayResult.NothingPlayable -> resources.getString(SharedR.string.library_artist_nothing_playable)
            }
        }
        if (!finished) collecting = handle
    }
    val shown by rememberUpdatedState(publication)
    val albums = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Album>()
    val listed = current?.itemsState == AndroidLibraryItemsState.Present
    val grid = rememberLazyGridState()
    val returning = pendingPosition("artist.album.")
    LaunchedEffect(returning, albums.size, topSongs?.items?.size) {
        if (returning != null && returning < albums.size) {
            var position = if (topSongs?.items?.isNotEmpty() == true) 1 else 0
            for (group in listOf("albums", "epsSingles", "compilations", "appearsOn")) {
                val entries = albums.withIndex().filter { it.value.discographyGroup == group }
                if (entries.isNotEmpty()) {
                    position += 1
                    val inside = entries.indexOfFirst { it.index == returning }
                    if (inside >= 0) { grid.scrollToItem(position + inside); break }
                    position += entries.size
                }
            }
        }
    }
    // The header stands above the grid: focused actions in it must not scroll the cards, and the
    // grid cannot clip it.
    var menu by remember(rawId) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("artist.surface").semantics { this[LibraryObservation] = observation }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 56.dp).padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvConnectionNotices(session, accountNotices = false)
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                TvArtwork(account, artist?.artworkKey, 200, DulcetIcons.Person, circle = true)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(artist?.name.orEmpty(), Modifier.testTag("artist.title"), style = MaterialTheme.typography.displaySmall)
                    if (listed) Text(pluralStringResource(R.plurals.tv_album_count, albums.size, albums.size),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (current != null) TvListStatus(current, "artist", session::retry)
                    if (current != null && artist != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (listed && albums.isNotEmpty()) {
                                TvAction(stringResource(R.string.tv_play), "artist.play", icon = DulcetIcons.Play, default = true,
                                    enabled = collecting == null) { shown?.let { play(it, false) } }
                                TvAction(stringResource(R.string.tv_shuffle), "artist.shuffle", icon = DulcetIcons.Shuffle, outlined = true,
                                    enabled = collecting == null) { shown?.let { play(it, true) } }
                            }
                            TvFavouriteButton(artist.favourite == true, "artist.favourite") { session.toggleFavourite(target) }
                            IconButton(onClick = { menu = true }, modifier = Modifier.tvFocus("artist.more")) {
                                Icon(DulcetIcons.MoreVert, resources.getString(R.string.tv_more_options))
                            }
                        }
                    }
                    note?.let { TvStatement(it, "artist.note") }
                    TvOutcomeLines(outcomeLines, target, "artist.outcome")
                }
            }
        }
        if (menu && artist != null) {
            val depth = session.depth.collectAsState().value
            TvQueueMenu(
                "artist.menu", artist.name,
                buildList {
                    TvMembershipAction(myLibrary, target)?.let { toggle ->
                        add(TvQueueChoice(resources.getString(
                            if (toggle.added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library),
                            "artist.library", toggle.toggle))
                    }
                    if (depth != null) {
                        val pinned = session.isPinned(artist)
                        add(TvQueueChoice(resources.getString(if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin),
                            "artist.pin") { session.togglePin(artist) })
                    }
                },
                resources.getString(SharedR.string.queue_cancel),
                artwork = { TvArtwork(account, artist.artworkKey, 64, DulcetIcons.Person, circle = true) },
            ) { menu = false }
        }
        LazyVerticalGrid(GridCells.Adaptive(180.dp),
            Modifier.fillMaxWidth().weight(1f),
            state = grid, contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            val top = topSongs?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
            if (top.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(SharedR.string.library_top_songs), style = MaterialTheme.typography.headlineSmall)
                    topSongs?.let { TvListStatus(it, "artist.topSongs", session::retry) }
                    top.take(5).forEachIndexed { index, track ->
                        TvTrackRow(track, index, false, onPlay = {
                            playTracks(plays, account.providerInstanceId, top, track.rawId, artist?.name.orEmpty()) { showNowPlaying(context) }
                        }, onUnavailable = {}, tagPrefix = "artist.topSongs", byPosition = true,
                            account = account,
                            queue = tvTrackAddition(context, playback, account.providerInstanceId, track, null),
                            session = session)
                    }
                }
            }
            for (group in listOf("albums", "epsSingles", "compilations", "appearsOn")) {
                val grouped = albums.withIndex().filter { it.value.discographyGroup == group }
                if (grouped.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(tvReleaseGroupTitle(group)), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.testTag("artist.group.$group"))
                }
                itemsIndexed(grouped, key = { _, album -> album.value.rawId }) { _, indexed ->
                    TvContextCard(account, session, playback, indexed.value, "artist.album.${indexed.index}", width = null) { navigator.openAlbum(indexed.value.rawId) }
                }
            }
        }
    }
}

// ---- Shared pieces --------------------------------------------------------------------------------------

private fun showNowPlaying(context: Context) = context.startActivity(PlaybackIntents.showNowPlaying(context))

/**
 * A card with its cover: an album, an artist, or a track in a home row. One focus target; its title
 * and subtitle sit below the art on the background, so the art is the whole card. [rank], when given,
 * is a large numeral over the art's lower left (Most Played). A long centre press opens [onLongClick]'s
 * context menu — there is no separate options pill.
 */
@Composable
internal fun TvCard(account: SearchAccount, item: AndroidLibraryItem, tag: String, default: Boolean = false,
                   width: Int? = 200, rank: Int? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val placeholder = tvItemPlaceholder(item)
    val artwork = tvItemArtworkKey(item)
    val artist = item is AndroidLibraryItem.Artist
    val shape = if (artist) CircleShape else RoundedCornerShape(12.dp)
    StandardCardContainer(
        modifier = if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth(),
        imageCard = { interaction ->
            Card(onClick = onClick, onLongClick = onLongClick, interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).tvFocus(tag, default)
                    .semantics { text = AnnotatedString(item.displayTitle()) },
                shape = CardDefaults.shape(shape),
                border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface), shape = shape)),
                colors = CardDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxSize()) {
                    TvArtwork(account, artwork, width ?: 180, placeholder, Modifier.fillMaxSize(), rounded = false, circle = artist)
                    if (rank != null) Text(rank.toString(),
                        Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.displaySmall, color = Color.White)
                }
            }
        },
        title = { Text(item.displayTitle(), Modifier.fillMaxWidth().padding(top = 12.dp, start = 12.dp, end = 12.dp),
            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (artist) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start) },
        subtitle = {
            val subtitle = tvItemSubtitle(item)
            subtitle?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (artist) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start) }
        },
    )
}

internal fun tvItemPlaceholder(item: AndroidLibraryItem) = when (item) {
    is AndroidLibraryItem.Artist -> DulcetIcons.Person
    is AndroidLibraryItem.Track, is AndroidLibraryItem.Genre -> DulcetIcons.MusicNote
    else -> DulcetIcons.Album
}

internal fun tvItemArtworkKey(item: AndroidLibraryItem): String? = when (item) {
    is AndroidLibraryItem.Album -> item.artworkKey
    is AndroidLibraryItem.Artist -> item.artworkKey
    is AndroidLibraryItem.Track -> item.artworkKey
    is AndroidLibraryItem.Playlist -> item.artworkKey
    is AndroidLibraryItem.Genre -> null
}

/** A card's second line, also its options menu's supporting line. */
internal fun tvItemSubtitle(item: AndroidLibraryItem): String? = if (item is AndroidLibraryItem.Album)
    listOfNotNull(item.artistName, item.originalReleaseDate?.take(4) ?: item.year?.toString()).joinToString(" · ").ifEmpty { null }
    else if (item is AndroidLibraryItem.Playlist) item.songCount?.let { "$it songs" }
    else item.subtitle()

@Composable
internal fun TvArtwork(
    account: SearchAccount?,
    key: String?,
    size: Int,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier.size(size.dp),
    rounded: Boolean = true,
    circle: Boolean = false,
) {
    val image = account?.let { rememberArtwork(it, key, size * 2) }
    Box(modifier.then(if (circle) Modifier.clip(CircleShape) else if (rounded) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(image, stringResource(R.string.tv_player_cover_art), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(placeholder, null, Modifier.size((size / 3).dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TvAction(
    label: String,
    tag: String,
    description: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    default: Boolean = false,
    enabled: Boolean = true,
    outlined: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp))
        Text(label, Modifier.padding(start = if (icon != null) 8.dp else 0.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val m = modifier.tvFocus(tag, default).semantics { description?.let { contentDescription = it } }
    // tv-material's own indication: a quiet surface at rest, the inverse fill when focused, so the
    // focused control is the only bright one on the screen.
    if (outlined) OutlinedButton(onClick = onClick, enabled = enabled, modifier = m, content = content)
    else Button(onClick = onClick, enabled = enabled, modifier = m, content = content)
}

@Composable
internal fun TvStatement(text: String, tag: String) {
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun clock(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
