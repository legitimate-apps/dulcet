package com.legitimateapps.dulcet.tv

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidLibraryCoverage
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.library.ArtistPlayResult
import com.legitimateapps.dulcet.library.LibraryHomeRowSurface
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibrarySubject
import com.legitimateapps.dulcet.library.LibrarySurface
import com.legitimateapps.dulcet.library.connectionLine
import com.legitimateapps.dulcet.library.coverageLine
import com.legitimateapps.dulcet.library.discardedChangesLine
import com.legitimateapps.dulcet.library.displayTitle
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.library.hostInForeground
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.noEpochLine
import com.legitimateapps.dulcet.library.offersRetry
import com.legitimateapps.dulcet.library.outcomeLine
import com.legitimateapps.dulcet.library.playAlbum
import com.legitimateapps.dulcet.library.playArtist
import com.legitimateapps.dulcet.library.playTracks
import com.legitimateapps.dulcet.library.rememberHomeRows
import com.legitimateapps.dulcet.library.rememberSurface
import com.legitimateapps.dulcet.library.subtitle
import com.legitimateapps.dulcet.library.titleResource
import com.legitimateapps.dulcet.library.unavailableLine
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.rememberPlaybackController
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.rememberArtwork
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * The TV app once an account exists (spec §3, Phase 5): search and the library, each a root, with the
 * library's album, artist, albums and artists screens pushed above it. Library data comes from the
 * account's one LibrarySession (§16.18), whose windows each screen opens while it is composed and
 * closes when it goes, as the phone's do; playing goes through the service's controller with the
 * phone's own queue builders (`LibraryPlay.kt`). Now Playing is its own activity, reachable from the
 * navigation row on every screen while something is playing, and from the playing track's row.
 *
 * Focus: every screen names where the D-pad lands when it opens, and Back returns to the element that
 * opened the screen it leaves (TvFocusMemory), scrolled back into view if a list had moved on.
 */

internal const val ROUTE_SEARCH = "search"
internal const val ROUTE_LIBRARY = "library"
internal const val ROUTE_ALBUMS = "albums"
internal const val ROUTE_ARTISTS = "artists"
private const val ALBUM = "album:"
private const val ARTIST = "artist:"

private val routeSaver = listSaver<SnapshotStateList<String>, String>(
    { it.toList() },
    { saved -> mutableStateListOf<String>().also { it.addAll(saved) } },
)

/** How a screen moves: to another route above it, and back to the one below. */
internal class TvNavigator(val open: (String) -> Unit, val back: () -> Unit) {
    fun openAlbum(rawId: String) = open(ALBUM + rawId)
    fun openArtist(rawId: String) = open(ARTIST + rawId)
}

/**
 * The TV app's navigation and library around [search]. The account's [LibrarySession] lives as long
 * as this entry — on the search screen too — so reachability and the foreground are reported whichever
 * screen is showing, and a search offline says so (§16.15).
 */
@Composable
internal fun TvLibraryEntry(account: SearchAccount, search: @Composable () -> Unit) {
    val context = LocalContext.current
    val foreground = hostInForeground()
    val session = remember(account) { LibrarySession(context, account, foreground) }
    val routes = rememberSaveable(saver = routeSaver) { mutableStateListOf(ROUTE_SEARCH) }
    val memory = remember { TvFocusMemory() }
    val states = rememberSaveableStateHolder()
    val playback = rememberPlaybackController()
    val playbackState by remember(playback) { playback?.state ?: MutableStateFlow(AndroidPlaybackState()) }
        .collectAsStateWithLifecycle()

    fun back() {
        if (routes.size > 1) {
            val left = routes.removeAt(routes.lastIndex)
            if (left !in routes) { states.removeState(left); memory.forget(left) }
        } else if (routes.single() != ROUTE_SEARCH) {
            show(routes, states, memory, ROUTE_SEARCH)
        }
    }
    val navigator = remember(routes) {
        TvNavigator(open = { route -> routes += route }, back = ::back)
    }
    // Back walks down the routes, then from the library root to search, the screen the app opens on;
    // from search it leaves the app.
    BackHandler(enabled = routes.size > 1 || routes.single() != ROUTE_SEARCH) { navigator.back() }

    val top = routes.last()
    val navigation = remember { TvNavigationFocus() }
    Column(Modifier.fillMaxSize()) {
        TvNavigationRow(routes.first(), playbackState.hasSession, navigation,
            onSearch = { show(routes, states, memory, ROUTE_SEARCH) },
            onLibrary = { show(routes, states, memory, ROUTE_LIBRARY) },
            onNowPlaying = { context.startActivity(PlaybackIntents.showNowPlaying(context)) })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            states.SaveableStateProvider(top) {
                CompositionLocalProvider(
                    LocalTvRouteFocus provides memory.route(top),
                    // A text field keeps UP for its cursor; the navigation row is above it.
                    LocalTvAccountEntry provides navigation.current,
                ) {
                    val playingRawId = playbackState.queue.getOrNull(playbackState.currentIndex ?: -1)?.track?.rawId
                    when {
                        top == ROUTE_SEARCH -> search()
                        top == ROUTE_LIBRARY -> TvLibraryHome(account, session, playback, navigator)
                        top == ROUTE_ALBUMS -> TvAlbumsGrid(account, session, navigator)
                        top == ROUTE_ARTISTS -> TvArtistsGrid(account, session, navigator)
                        top.startsWith(ALBUM) ->
                            TvAlbumScreen(account, session, playback, playingRawId, top.removePrefix(ALBUM), navigator)
                        top.startsWith(ARTIST) ->
                            TvArtistScreen(account, session, playback, top.removePrefix(ARTIST), navigator)
                    }
                }
            }
        }
    }
    // On a return to the foreground the screens above are already open when start() reconnects. At
    // launch the TV shows search, and the library's windows open when the person turns to it —
    // usually after the reconnect has read the epoch; each publishes its cache before its own read.
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

/** The navigation row's focus: the root that is showing, so UP from a screen's top lands on it. */
private class TvNavigationFocus {
    val search = FocusRequester()
    val library = FocusRequester()
    var current: FocusRequester = search
}

@Composable
private fun TvNavigationRow(
    root: String,
    playing: Boolean,
    focus: TvNavigationFocus,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onNowPlaying: () -> Unit,
) {
    focus.current = if (root == ROUTE_LIBRARY) focus.library else focus.search
    Row(Modifier.fillMaxWidth().padding(start = 56.dp, top = 12.dp, end = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTab(stringResource(R.string.tv_nav_search), root == ROUTE_SEARCH, "search.open", focus.search, onSearch)
        TvTab(stringResource(R.string.tv_nav_library), root == ROUTE_LIBRARY, "library.open", focus.library, onLibrary)
        if (playing) {
            Button(onClick = onNowPlaying, modifier = Modifier.testTag("tv.nav.nowplaying")) {
                Icon(DulcetIcons.QueueMusic, null, Modifier.size(20.dp))
                Text(stringResource(R.string.tv_nav_now_playing), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun TvTab(label: String, selected: Boolean, tag: String, focus: FocusRequester, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.focusRequester(focus).testTag(tag).semantics { this.selected = selected },
        colors = if (selected) androidx.tv.material3.ButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) else androidx.tv.material3.ButtonDefaults.colors(),
    ) { Text(label) }
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
}

internal val LocalTvRouteFocus = staticCompositionLocalOf<TvRouteFocus?> { null }

/** Marks the route's screen as entered, so its memory applies to this composition. */
@Composable
private fun EnterRoute() {
    val route = LocalTvRouteFocus.current ?: return
    remember(route) { route.also(TvRouteFocus::enter) }
}

/**
 * Tags an element and remembers it as the route's focus when focused; focuses it again when the route
 * comes back ([TvRouteFocus.pending]), or when it is the screen's [default] and nothing else is to be.
 */
@Composable
internal fun Modifier.tvFocus(tag: String, default: Boolean = false): Modifier {
    val route = LocalTvRouteFocus.current
    val requester = remember { FocusRequester() }
    if (route != null) {
        val restore = route.pending == tag
        val claim = default && route.claimDefault()
        if (restore || claim) LaunchedEffect(tag) {
            if (runCatching { requester.requestFocus() }.isSuccess) route.restored()
        }
    }
    return this.focusRequester(requester)
        .onFocusChanged { if (it.isFocused || it.hasFocus) route?.focused(tag) }
        .testTag(tag)
}

/** The position in a list whose tags start with [prefix] that the route is to return to, if any. */
@Composable
private fun pendingPosition(prefix: String): Int? =
    LocalTvRouteFocus.current?.pending?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()

// ---- Home -----------------------------------------------------------------------------------------------

@Composable
private fun TvLibraryHome(account: SearchAccount, session: LibrarySession, playback: AndroidPlaybackController?,
                          navigator: TvNavigator) {
    EnterRoute()
    val rows = rememberHomeRows(session)
    val observation by session.observation.collectAsState()
    val list = rememberLazyListState()
    // A focus returning to a row the list had scrolled past: bring that row back first. Two items
    // precede the rows: the heading with its actions, then the connection notices.
    val returning = LocalTvRouteFocus.current?.pending?.let { HOME_ROW.find(it) }?.groupValues?.get(1)?.toIntOrNull()
    LaunchedEffect(returning) { if (returning != null) list.scrollToItem(returning + 2) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("library.surface").semantics { this[LibraryObservation] = observation },
        state = list,
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.tv_library_title), style = MaterialTheme.typography.displaySmall)
                TvAction(stringResource(R.string.tv_library_albums), "library.view.albums") { navigator.open(ROUTE_ALBUMS) }
                TvAction(stringResource(R.string.tv_library_artists), "library.view.artists") { navigator.open(ROUTE_ARTISTS) }
                TvAction(stringResource(R.string.tv_refresh), "library.refresh", onClick = session::refresh)
            }
        }
        item { TvConnectionNotices(session, accountNotices = true) }
        itemsIndexed(rows) { index, row -> TvHomeRow(account, index, row, session, playback, navigator) }
    }
}

private val HOME_ROW = Regex("library\\.home\\.(\\d+)\\.item\\.\\d+")

@Composable
private fun TvHomeRow(account: SearchAccount, index: Int, row: LibraryHomeRowSurface, session: LibrarySession,
                      playback: AndroidPlaybackController?, navigator: TvNavigator) {
    val publication by row.surface.state.collectAsState()
    val resources = libraryResources()
    val context = LocalContext.current
    val title = resources.getString(row.row.titleResource())
    // A card's onClick may be one from an earlier composition: it queues the row as shown now.
    val shown by rememberUpdatedState(publication)
    Column(Modifier.fillMaxWidth().testTag("library.home.$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        val current = publication ?: return@Column
        resources.freshnessLine(current.freshness)?.let { line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvStatement(line, "library.home.$index.freshness")
                if (current.freshness.offersRetry()) {
                    TvAction(resources.getString(SharedR.string.library_try_again), "library.home.$index.retry") { session.retry() }
                }
            }
        }
        when (val freshness = current.freshness) {
            AndroidLibraryFreshness.Loading -> TvStatement("…", "library.home.$index.loading")
            is AndroidLibraryFreshness.Unavailable ->
                TvStatement(resources.unavailableLine(freshness.reason, LibrarySubject.List), "library.home.$index.unavailable")
            else -> if (current.items.isEmpty()) {
                TvStatement(resources.getString(SharedR.string.library_empty_list), "library.home.$index.empty")
            } else {
                val state = rememberLazyListState()
                val returning = pendingPosition("library.home.$index.item.")
                LaunchedEffect(returning, current.items.size) {
                    if (returning != null && returning < current.items.size) state.scrollToItem(returning)
                }
                LazyRow(Modifier.testTag("library.home.$index.items"), state = state,
                    contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(current.items, key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                        TvCard(account, item, "library.home.$index.item.$position", default = index == 0 && position == 0) {
                            when (item) {
                                is AndroidLibraryItem.Album -> navigator.openAlbum(item.rawId)
                                is AndroidLibraryItem.Artist -> navigator.openArtist(item.rawId)
                                is AndroidLibraryItem.Track -> if (playTracks(playback, account.providerInstanceId,
                                        shown?.items.orEmpty(), item.rawId, title)) showNowPlaying(context)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The phone's `ConnectionNotices`, in the TV's type: the same statements, from the same copy. */
@Composable
private fun TvConnectionNotices(session: LibrarySession, accountNotices: Boolean) {
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
            resources.noEpochLine(connection)?.let { TvStatement(it, "library.noEpoch") }
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

/** Every album, `alphabeticalByName`, windowed (§16.12): the viewport is reported, pages extend it. */
@Composable
private fun TvAlbumsGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_ALBUMS) { openAlbums() }
    TvBrowseGrid(account, session, surface, "library.albums", R.string.tv_library_albums) { item ->
        if (item is AndroidLibraryItem.Album) navigator.openAlbum(item.rawId)
    }
}

/** Every artist, one response (§16.9). */
@Composable
private fun TvArtistsGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_ARTISTS) { openArtists() }
    TvBrowseGrid(account, session, surface, "library.artists", R.string.tv_library_artists) { item ->
        if (item is AndroidLibraryItem.Artist) navigator.openArtist(item.rawId)
    }
}

@Composable
private fun TvBrowseGrid(account: SearchAccount, session: LibrarySession, surface: LibrarySurface, tag: String, title: Int,
                         open: (AndroidLibraryItem) -> Unit) {
    val publication by surface.state.collectAsState()
    val current = publication
    val grid = rememberLazyGridState()
    ReportViewport(surface, grid, current)
    val returning = pendingPosition("$tag.item.")
    LaunchedEffect(returning, current?.items?.size) {
        val size = current?.items?.size ?: return@LaunchedEffect
        // Grid index 0 is the header; items start at 1.
        if (returning != null && returning < size) grid.scrollToItem(returning + 1)
    }
    LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.fillMaxSize().testTag(tag), state = grid,
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.displaySmall)
                TvConnectionNotices(session, accountNotices = false)
                if (current != null) TvListStatus(current, tag, session::retry)
            }
        }
        itemsIndexed(current?.items.orEmpty(), key = { _, item -> item.rawId }) { position, item ->
            TvCard(account, item, "$tag.item.$position", default = position == 0, width = null) { open(item) }
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
        snapshotFlow {
            val visible = grid.layoutInfo.visibleItemsInfo.filter { it.key is String }
            visible.firstOrNull()?.index to visible.lastOrNull()?.index
        }.collect { (first, last) ->
            val shown = latest ?: return@collect
            if (first == null || last == null) return@collect
            val from = (first - 1).coerceAtLeast(0)
            val to = (last - 1).coerceAtLeast(from)
            surface.setViewport(from, to)
            if (shown.coverage == AndroidLibraryCoverage.Open && to >= shown.items.size - PAGE_AHEAD) surface.loadMore()
            if (shown.leadingOffset > 0 && from == 0) surface.loadBefore()
        }
    }
}

private const val PAGE_AHEAD = 12

/** A list's freshness and coverage, or the statement shown instead of it (the phone's `ListStatus`). */
@Composable
private fun TvListStatus(publication: AndroidLibraryPublication, tag: String, retry: () -> Unit) {
    val resources = libraryResources()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        resources.freshnessLine(publication.freshness)?.let { line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvStatement(line, "$tag.freshness")
                if (publication.freshness.offersRetry()) {
                    TvAction(resources.getString(SharedR.string.library_try_again), "$tag.retry", onClick = retry)
                }
            }
        }
        resources.coverageLine(publication)?.let { TvStatement(it, "$tag.coverage") }
        val freshness = publication.freshness
        when {
            freshness is AndroidLibraryFreshness.Unavailable ->
                TvStatement(resources.unavailableLine(freshness.reason, LibrarySubject.List), "$tag.unavailable")
            freshness == AndroidLibraryFreshness.Loading || publication.itemsState == AndroidLibraryItemsState.Loading ->
                TvStatement(stringResource(R.string.tv_library_loading), "$tag.loading")
            publication.itemsState == AndroidLibraryItemsState.Unavailable -> TvStatement(resources.unavailableLine(
                publication.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List,
            ), "$tag.unavailable")
            publication.items.isEmpty() -> TvStatement(resources.getString(SharedR.string.library_empty_list), "$tag.empty")
        }
    }
}

// ---- Album ----------------------------------------------------------------------------------------------

@Composable
private fun TvAlbumScreen(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    playingRawId: String?,
    rawId: String,
    navigator: TvNavigator,
) {
    EnterRoute()
    val surface = rememberSurface(session, ALBUM + rawId) { openAlbum(rawId) }
    val context = LocalContext.current
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val outcomes by session.outcomes.collectAsState()
    // Only an outcome about this album is said here, and it goes when the screen does.
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Album, rawId)
    val outcome = outcomes[target]
    DisposableEffect(session, target) { onDispose { session.dismissOutcome(target) } }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    val resources = libraryResources()
    val provider = account.providerInstanceId
    fun play(current: AndroidLibraryPublication, trackRawId: String?, shuffle: Boolean) {
        // A note that a track plays only on reconnect no longer applies.
        note = null
        if (playAlbum(playback, provider, current, trackRawId, shuffle)) showNowPlaying(context)
    }
    // tv-material buttons can keep an onClick from an earlier composition: they play what is shown now.
    val shown by rememberUpdatedState(publication)
    val returning = pendingPosition("album.track.")
    val list = rememberLazyListState()
    LaunchedEffect(returning, publication?.items?.size) {
        val size = publication?.items?.size ?: return@LaunchedEffect
        // Two items above the tracks: the actions, then the header.
        if (returning != null && returning < size) list.scrollToItem(returning + 2)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("album.surface").semantics { this[LibraryObservation] = observation },
        state = list,
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(stringResource(R.string.tv_back), "album.back", onClick = navigator.back)
                TvAction(stringResource(R.string.tv_refresh), "album.refresh", onClick = session::refresh)
            }
        }
        val current = publication
        val album = current?.header as? AndroidLibraryItem.Album
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvConnectionNotices(session, accountNotices = false)
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvArtwork(account, album?.artworkKey, 200, DulcetIcons.Album)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(album?.title.orEmpty(), Modifier.testTag("album.title"),
                            style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        album?.artistName?.let {
                            Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (current != null) {
                            resources.freshnessLine(current.freshness)?.let { TvStatement(it, "album.freshness") }
                            (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                                TvStatement(resources.unavailableLine(unavailable.reason, LibrarySubject.Album), "album.unavailable")
                            }
                        }
                        if (current != null && album != null) {
                            val playable = current.itemsState == AndroidLibraryItemsState.Present &&
                                current.items.any { (it as? AndroidLibraryItem.Track)?.playability != AndroidLibraryPlayability.UnavailableOffline }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (playable) {
                                    TvAction(stringResource(R.string.tv_play), "album.play", icon = DulcetIcons.Play, default = true) {
                                        shown?.let { play(it, null, false) }
                                    }
                                    TvAction(stringResource(R.string.tv_shuffle), "album.shuffle", icon = DulcetIcons.Shuffle) {
                                        shown?.let { play(it, null, true) }
                                    }
                                }
                                val favourite = album.favourite == true
                                TvAction(
                                    resources.getString(if (favourite) SharedR.string.library_favourite_remove else SharedR.string.library_favourite_add),
                                    "album.favourite",
                                    description = resources.getString(
                                        if (favourite) SharedR.string.library_favourite_on else SharedR.string.library_favourite_add),
                                    icon = if (favourite) DulcetIcons.Star else DulcetIcons.StarBorder,
                                ) { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId)) }
                            }
                        }
                        resources.outcomeLine(outcome)?.let { TvStatement(it, "album.outcome") }
                        note?.let { TvStatement(it, "album.note") }
                    }
                }
            }
        }
        if (current == null) return@LazyColumn
        when (current.itemsState) {
            AndroidLibraryItemsState.Loading -> item { TvStatement("…", "album.tracks.loading") }
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
                )
            }
        }
    }
}

/**
 * One track. Selected, a row that can play plays the album from it; a row this device cannot play
 * offline says why, as the phone's does (§16.14). Every row is ONE focus target of one kind, so the
 * D-pad stops on it once, and a row whose playability changes while focused (going offline, or
 * reconnecting) keeps its focus. Its action is the centre key or Enter, on release, and a click for
 * accessibility services.
 */
@Composable
private fun TvTrackRow(track: AndroidLibraryItem.Track, position: Int, playing: Boolean, onPlay: () -> Unit,
                       onUnavailable: () -> Unit) {
    val unavailable = track.playability == AndroidLibraryPlayability.UnavailableOffline
    val select = if (unavailable) onUnavailable else onPlay
    val resources = libraryResources()
    val nowPlaying = stringResource(R.string.tv_now_playing_row)
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().tvFocus("album.track.$position")
            // One accessibility node whatever the playability: focusable() does not merge the row's
            // texts, so this does. A row that cannot play is described as such.
            .semantics(mergeDescendants = true) {
                if (unavailable) {
                    contentDescription = "${track.title.orEmpty()}, ${resources.getString(SharedR.string.library_not_available_offline)}"
                }
                selected = playing
                onClick { select(); true }
            }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val centre = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (centre && event.type == KeyEventType.KeyUp) {
                    select()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) colors.inverseSurface else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = when {
            focused -> colors.inverseOnSurface
            unavailable -> colors.onSurface.copy(alpha = 0.45f)
            else -> colors.onSurface
        }
        Box(Modifier.width(32.dp)) {
            if (playing) Icon(DulcetIcons.Play, nowPlaying, Modifier.size(20.dp).testTag("album.track.$position.playing"), tint = content)
            else Text((track.trackNumber ?: position + 1).toString(), color = content)
        }
        Text(track.title.orEmpty(), Modifier.weight(1f), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (unavailable) Text(resources.getString(SharedR.string.library_not_available_offline), color = content)
        track.durationMilliseconds?.let { Text(clock(it), color = content) }
    }
}

// ---- Artist ---------------------------------------------------------------------------------------------

@Composable
private fun TvArtistScreen(
    account: SearchAccount,
    session: LibrarySession,
    playback: AndroidPlaybackController?,
    rawId: String,
    navigator: TvNavigator,
) {
    EnterRoute()
    val surface = rememberSurface(session, ARTIST + rawId) { openArtist(rawId) }
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val context = LocalContext.current
    val resources = libraryResources()
    val current = publication
    val artist = current?.header as? AndroidLibraryItem.Artist
    // Playing an artist opens each album first; leaving the screen abandons that, so nothing starts
    // playing after the person has gone, and a second press while it runs does nothing.
    var collecting by remember(rawId) { mutableStateOf<AutoCloseable?>(null) }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    DisposableEffect(rawId) { onDispose { collecting?.close() } }
    fun play(publication: AndroidLibraryPublication, shuffle: Boolean) {
        if (collecting != null) return
        note = null
        var finished = false
        val handle = playArtist(playback, session, account.providerInstanceId, publication, shuffle) { result ->
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
    LaunchedEffect(returning, albums.size) {
        if (returning != null && returning < albums.size) grid.scrollToItem(returning + 1)
    }
    LazyVerticalGrid(GridCells.Adaptive(180.dp),
        Modifier.fillMaxSize().testTag("artist.surface").semantics { this[LibraryObservation] = observation },
        state = grid, contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvAction(stringResource(R.string.tv_back), "artist.back", onClick = navigator.back)
                    TvAction(stringResource(R.string.tv_refresh), "artist.refresh", onClick = session::refresh)
                }
                TvConnectionNotices(session, accountNotices = false)
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvArtwork(account, artist?.artworkKey, 160, DulcetIcons.Person)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(artist?.name.orEmpty(), Modifier.testTag("artist.title"), style = MaterialTheme.typography.displaySmall)
                        if (listed) Text(pluralStringResource(R.plurals.tv_album_count, albums.size, albums.size),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (current != null) TvListStatus(current, "artist", session::retry)
                        if (current != null && artist != null && listed && albums.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TvAction(stringResource(R.string.tv_play), "artist.play", icon = DulcetIcons.Play, default = true,
                                    enabled = collecting == null) { shown?.let { play(it, false) } }
                                TvAction(stringResource(R.string.tv_shuffle), "artist.shuffle", icon = DulcetIcons.Shuffle,
                                    enabled = collecting == null) { shown?.let { play(it, true) } }
                            }
                        }
                        note?.let { TvStatement(it, "artist.note") }
                    }
                }
            }
        }
        itemsIndexed(albums, key = { _, album -> album.rawId }) { position, album ->
            TvCard(account, album, "artist.album.$position", width = null) { navigator.openAlbum(album.rawId) }
        }
    }
}

// ---- Shared pieces --------------------------------------------------------------------------------------

private fun showNowPlaying(context: Context) = context.startActivity(PlaybackIntents.showNowPlaying(context))

/** A card with its cover: an album, an artist, or a track in a home row. One focus target. */
@Composable
private fun TvCard(account: SearchAccount, item: AndroidLibraryItem, tag: String, default: Boolean = false,
                   width: Int? = 200, onClick: () -> Unit) {
    val placeholder = when (item) {
        is AndroidLibraryItem.Artist -> DulcetIcons.Person
        is AndroidLibraryItem.Track -> DulcetIcons.MusicNote
        else -> DulcetIcons.Album
    }
    val artwork = when (item) {
        is AndroidLibraryItem.Album -> item.artworkKey
        is AndroidLibraryItem.Artist -> item.artworkKey
        is AndroidLibraryItem.Track -> item.artworkKey
        is AndroidLibraryItem.Playlist -> item.artworkKey
        is AndroidLibraryItem.Genre -> null
    }
    Card(
        onClick = onClick,
        modifier = (if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth()).tvFocus(tag, default),
    ) {
        Column {
            TvArtwork(account, artwork, width ?: 180, placeholder, Modifier.fillMaxWidth().aspectRatio(1f), rounded = false)
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.displayTitle(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.subtitle()?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun TvArtwork(
    account: SearchAccount?,
    key: String?,
    size: Int,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier.size(size.dp),
    rounded: Boolean = true,
) {
    val image = account?.let { rememberArtwork(it, key, size * 2) }
    Box(modifier.then(if (rounded) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier).background(Color(0xFF2A2A2E)),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(image, stringResource(R.string.tv_player_cover_art), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(placeholder, null, Modifier.size((size / 3).dp), tint = Color(0xFF8E8E93))
    }
}

@Composable
private fun TvAction(
    label: String,
    tag: String,
    description: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    default: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.tvFocus(tag, default).semantics { description?.let { contentDescription = it } },
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp))
        Text(label, Modifier.padding(start = if (icon != null) 8.dp else 0.dp))
    }
}

@Composable
private fun TvStatement(text: String, tag: String) {
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun clock(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
