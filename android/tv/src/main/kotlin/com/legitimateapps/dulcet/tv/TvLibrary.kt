package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.library.playPlaylist
import com.legitimateapps.dulcet.library.playlistOwnerLine
import com.legitimateapps.dulcet.library.playlistPendingLine
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.LazyListState
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.library.AlbumSort
import com.legitimateapps.dulcet.library.rememberAlbumSort
import com.legitimateapps.dulcet.library.orderLine
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
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
import com.legitimateapps.dulcet.library.connectionLine
import com.legitimateapps.dulcet.library.coverageLine
import com.legitimateapps.dulcet.library.growsAtEnd
import com.legitimateapps.dulcet.library.discardedChangesLine
import com.legitimateapps.dulcet.library.displayTitle
import com.legitimateapps.dulcet.library.isFavourite
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.library.hostInForeground
import com.legitimateapps.dulcet.library.libraryResources
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

internal const val ROUTE_SEARCH = "search"
internal const val ROUTE_LIBRARY = "library"
internal const val ROUTE_ALBUMS = "albums"
internal const val ROUTE_ARTISTS = "artists"
internal const val ROUTE_GENRES = "genres"
internal const val ROUTE_FAVOURITES = "favourites"
internal const val ROUTE_PLAYLISTS = "playlists"
internal const val ROUTE_ACCOUNT = "account"
private const val ALBUM = "album:"
private const val PLAYLIST = "playlist:"
private const val ARTIST = "artist:"
private const val GENRE = "genre:"

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
internal fun TvLibraryEntry(
    account: SearchAccount,
    /** The playback service's controller, null until it binds. */
    playback: AndroidPlaybackController? = rememberPlaybackController(),
    search: @Composable (TvNavigator, AndroidPlaybackController?) -> Unit,
) {
    val context = LocalContext.current
    val foreground = hostInForeground()
    val session = remember(account) { LibrarySession(context, account, foreground) }
    val routes = rememberSaveable(saver = routeSaver) { mutableStateListOf(ROUTE_LIBRARY) }
    val memory = remember { TvFocusMemory() }
    val states = rememberSaveableStateHolder()
    val playbackState by remember(playback) { playback?.state ?: MutableStateFlow(AndroidPlaybackState()) }
        .collectAsStateWithLifecycle()

    fun back() {
        if (routes.size > 1) {
            val left = routes.removeAt(routes.lastIndex)
            if (left !in routes) { states.removeState(left); memory.forget(left) }
        } else if (routes.single() != ROUTE_LIBRARY) {
            show(routes, states, memory, ROUTE_LIBRARY)
        }
    }
    val navigator = remember(routes) {
        TvNavigator(open = { route -> routes += route }, back = ::back)
    }
    // Back walks down the routes, then from the search root to the library, the screen the app opens
    // on; from the library it leaves the app.
    BackHandler(enabled = routes.size > 1 || routes.single() != ROUTE_LIBRARY) { navigator.back() }

    val top = routes.last()
    val navigation = remember { TvNavigationFocus() }
    // The library screens are lists and grids, not Surfaces: paint the background and set the content
    // colour here, or every uncoloured heading falls back to black on the dark background.
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            TvTopBar(top, routes.first(), playbackState.hasSession, navigation,
                onSearch = { show(routes, states, memory, ROUTE_SEARCH) },
                onLibrary = { show(routes, states, memory, ROUTE_LIBRARY) },
                onNowPlaying = { context.startActivity(PlaybackIntents.showNowPlaying(context)) },
                onAccount = { if (routes.last() != ROUTE_ACCOUNT) routes += ROUTE_ACCOUNT })
            Box(Modifier.fillMaxWidth().weight(1f)) {
            states.SaveableStateProvider(top) {
                CompositionLocalProvider(
                    LocalTvRouteFocus provides memory.route(top),
                    // A text field keeps UP for its cursor; the navigation bar is above it.
                    LocalTvNavFocus provides navigation.current,
                    LocalTvNavigation provides navigation,
                ) {
                    val playingRawId = playbackState.queue.getOrNull(playbackState.currentIndex ?: -1)?.track?.rawId
                    when {
                        top == ROUTE_SEARCH -> search(navigator, playback)
                        top == ROUTE_LIBRARY -> TvLibraryHome(account, session, playback, navigator)
                        top == ROUTE_ALBUMS -> TvAlbumsGrid(account, session, navigator)
                        top == ROUTE_ARTISTS -> TvArtistsGrid(account, session, navigator)
                        top == ROUTE_GENRES -> TvGenresGrid(account, session, navigator)
                        top == ROUTE_FAVOURITES -> TvFavouritesScreen(account, session, playback, playingRawId, navigator)
                        top == ROUTE_PLAYLISTS -> TvPlaylistsGrid(account, session, navigator)
                        top == ROUTE_ACCOUNT -> TvAccountScreen(account, navigator)
                        top.startsWith(PLAYLIST) ->
                            TvPlaylistScreen(account, session, playback, playingRawId, top.removePrefix(PLAYLIST), navigator)
                        top.startsWith(ALBUM) ->
                            TvAlbumScreen(account, session, playback, playingRawId, top.removePrefix(ALBUM), navigator)
                        top.startsWith(ARTIST) ->
                            TvArtistScreen(account, session, playback, top.removePrefix(ARTIST), navigator)
                        top.startsWith(GENRE) ->
                            TvGenreScreen(account, session, playback, playingRawId, top.removePrefix(GENRE), navigator)
                    }
                }
            }
        }
        }
    }
    // On a return to the foreground the screens above are already open when start() reconnects. At
    // launch the TV shows the library, whose home rows open as it composes; each publishes its cache
    // before its own read, as the phone's do.
    DroppedAdditionsNotice(playback, playbackState.droppedAdditions)
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
    val search = FocusRequester()
    val library = FocusRequester()
    val account = FocusRequester()
    var current: FocusRequester = library

    /** The tag of the showing root's tab: the one place on the bar a screen's default may take focus from. */
    var currentTag: String = "library.open"

    /** The tag of the bar control holding focus; null while focus is off the bar. Not state: read once, at a claim. */
    var focusedTag: String? = null
        private set

    fun focusChanged(tag: String, focused: Boolean) {
        if (focused) focusedTag = tag else if (focusedTag == tag) focusedTag = null
    }

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
 * The 10-foot top bar, on every screen of the signed-in app: the brand, the roots (Search and
 * Library) as tabs, and at the far end Now Playing while something plays and the Account place.
 * Sign out is not here; it lives on the Account screen ([ROUTE_ACCOUNT]).
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
) {
    focus.current = when {
        top == ROUTE_ACCOUNT -> focus.account
        root == ROUTE_LIBRARY -> focus.library
        else -> focus.search
    }
    focus.currentTag = when {
        top == ROUTE_ACCOUNT -> "tv.account.open"
        root == ROUTE_LIBRARY -> "library.open"
        else -> "search.open"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 56.dp, top = 20.dp, end = 56.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DulcetIcons.LibraryMusic, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.app_name),
            Modifier.padding(start = 12.dp, end = 40.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        TvTab(stringResource(R.string.tv_nav_search), root == ROUTE_SEARCH, "search.open", focus.search, focus,
            onClick = onSearch)
        TvTab(stringResource(R.string.tv_nav_library), root == ROUTE_LIBRARY, "library.open", focus.library, focus,
            Modifier.padding(start = 8.dp), onLibrary)
        Spacer(Modifier.weight(1f))
        if (playing) {
            Button(onClick = onNowPlaying, modifier = Modifier
                .onFocusChanged { focus.focusChanged("tv.nav.nowplaying", it.isFocused) }
                .testTag("tv.nav.nowplaying")) {
                Icon(DulcetIcons.QueueMusic, null, Modifier.size(20.dp))
                Text(stringResource(R.string.tv_nav_now_playing), Modifier.padding(start = 8.dp))
            }
        }
        TvTab(stringResource(SharedR.string.account_signout_account_label), top == ROUTE_ACCOUNT, "tv.account.open", focus.account,
            focus, Modifier.padding(start = 8.dp), onAccount, icon = DulcetIcons.Person)
    }
}

@Composable
private fun TvTab(
    label: String,
    selected: Boolean,
    tag: String,
    focus: FocusRequester,
    bar: TvNavigationFocus,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.focusRequester(focus).onFocusChanged { bar.focusChanged(tag, it.isFocused) }
            .testTag(tag).semantics { this.selected = selected },
        colors = if (selected) androidx.tv.material3.ButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) else androidx.tv.material3.ButtonDefaults.colors(),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp))
        Text(label, Modifier.padding(start = if (icon != null) 8.dp else 0.dp))
    }
}

/**
 * The navigation bar's current tab's focus, for a screen whose topmost control is a text field:
 * a text field consumes UP for its cursor, so the D-pad could otherwise never leave it upward.
 */
internal val LocalTvNavFocus = staticCompositionLocalOf<FocusRequester?> { null }

// ---- Account (spec §14.7): the settings place, reached from the bar's Account tab ----------------

/**
 * Whose account, on which server, Sign out, and the streaming quality. Pushed above whatever was showing, so Back returns
 * to it; the bar's Account tab stays lit meanwhile. It opens with focus on Back, never on Sign out:
 * a destructive action is never where a remote's centre key lands unasked.
 */
@Composable
internal fun TvAccountScreen(account: SearchAccount, navigator: TvNavigator) {
    EnterRoute()
    val server = runCatching { java.net.URI(account.normalizedBaseUrl).host }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: account.normalizedBaseUrl
    LazyColumn(
        Modifier.fillMaxSize().testTag("account.surface"),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row { TvAction(stringResource(R.string.tv_back), "account.back", default = true, onClick = navigator.back) }
        }
        item {
            Text(stringResource(SharedR.string.account_signout_account_title), style = MaterialTheme.typography.displaySmall)
        }
        item {
            Text(
                stringResource(SharedR.string.account_signout_account_body, server, account.username),
                Modifier.testTag("account.summary"),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { TvSignOutEntry() }
        // Below Sign out, so the screen still opens on Back with Sign out one step down (§14.7).
        item { TvStreamingQuality() }
    }
}

/**
 * The streaming-quality choice (spec §12.5): a row of choices for Wi-Fi and one for cellular, the
 * chosen one marked. Saved at once, applied from the next song; nothing playing restarts for it.
 */
@Composable
private fun TvStreamingQuality() {
    val context = LocalContext.current
    val settings = remember(context) { StreamingQualitySettings.get(context) }
    val preference by settings.preference.collectAsStateWithLifecycle()
    Column(Modifier.testTag("quality.section"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(SharedR.string.streaming_quality_title), style = MaterialTheme.typography.headlineSmall)
        TvQualityRow(stringResource(SharedR.string.streaming_quality_unmetered), "quality.unmetered", preference.unmetered) {
            settings.set(preference.copy(unmetered = it))
        }
        TvQualityRow(stringResource(SharedR.string.streaming_quality_metered), "quality.metered", preference.metered) {
            settings.set(preference.copy(metered = it))
        }
        TvStatement(stringResource(SharedR.string.streaming_quality_body), "quality.body")
    }
}

@Composable
private fun TvQualityRow(title: String, tag: String, selected: StreamingQuality, choose: (StreamingQuality) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StreamingQuality.entries.forEach { quality ->
                val label = quality.label(context)
                TvAction(
                    label,
                    "$tag.${quality.wireName}",
                    description = if (quality == selected) {
                        context.getString(SharedR.string.streaming_quality_choice_selected, title, label)
                    } else {
                        context.getString(SharedR.string.streaming_quality_choice, title, label)
                    },
                    icon = if (quality == selected) DulcetIcons.Check else null,
                ) { choose(quality) }
            }
        }
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
}

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
internal fun Modifier.tvFocus(tag: String, default: Boolean = false): Modifier {
    val route = LocalTvRouteFocus.current
    val bar = LocalTvNavigation.current
    val requester = remember { FocusRequester() }
    if (route != null) {
        val restore = route.pending == tag
        // Decided once, when the element first composes: a default that arrives late — the home's
        // first card after a cold read — takes focus only if the person has not moved along the bar
        // meanwhile, and a later recomposition never turns a declined claim into a jump.
        val claim = remember(tag, default) { default && route.claimDefault() && bar?.allowsDefault() != false }
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
    // The screen the app opens on. Its first card takes focus when it arrives, if the remote is still
    // on the bar's Library tab (tvFocus); until then — an empty library, one still loading, or one that
    // cannot be read — the remote waits there (EnterRoute), so the first key press moves from a known place.
    val rows = rememberHomeRows(session)
    val observation by session.observation.collectAsState()
    val list = rememberLazyListState()
    // A focus returning to a row the list had scrolled past: bring that row back first. Two items
    // precede the rows: the sections with Refresh, then the connection notices.
    val returning = LocalTvRouteFocus.current?.pending?.let { HOME_ROW.find(it) }?.groupValues?.get(1)?.toIntOrNull()
    LaunchedEffect(returning) { if (returning != null) list.scrollToItem(returning + 2) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("library.surface").semantics { this[LibraryObservation] = observation },
        state = list,
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            // No "Library" heading: the bar's lit tab names the screen. The ways into the library
            // are one row of their own, with Refresh at its end.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvAction(stringResource(R.string.tv_library_albums), "library.view.albums") { navigator.open(ROUTE_ALBUMS) }
                TvAction(stringResource(R.string.tv_library_artists), "library.view.artists") { navigator.open(ROUTE_ARTISTS) }
                TvAction(stringResource(SharedR.string.library_genres), "library.view.genres") { navigator.open(ROUTE_GENRES) }
                TvAction(stringResource(R.string.tv_library_favourites), "library.view.favourites") { navigator.open(ROUTE_FAVOURITES) }
                TvAction(stringResource(R.string.tv_library_playlists), "library.view.playlists") { navigator.open(ROUTE_PLAYLISTS) }
                Spacer(Modifier.weight(1f))
                TvAction(stringResource(R.string.tv_refresh), "library.refresh", icon = DulcetIcons.Refresh, onClick = session::refresh)
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
    // A card's onClick may be one from an earlier composition: it queues the row as shown now, on the
    // controller bound now -- or, before the service binds, once it does.
    val shown by rememberUpdatedState(publication)
    val plays = rememberPlaybackBinding(playback)
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
                                is AndroidLibraryItem.Track -> playTracks(plays, account.providerInstanceId,
                                        shown?.items.orEmpty(), item.rawId, title) { showNowPlaying(context) }
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

/**
 * Every album, in the order chosen on this device ([AlbumSort], shared with the phone), windowed
 * (§16.12): the viewport is reported, pages extend it. The orders are a row of buttons above the grid,
 * UP from the first row of albums; choosing one opens that order's own window from its start and
 * leaves the remote on the button chosen.
 */
@Composable
private fun TvAlbumsGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val sort = rememberAlbumSort()
    val order = sort.value
    val surface = rememberSurface(session, ROUTE_ALBUMS + ":" + order) { openAlbums(order) }
    TvBrowseGrid(account, session, surface, "library.albums", R.string.tv_library_albums,
        controls = { TvAlbumSortRow(order, sort::choose) }) { item ->
        if (item is AndroidLibraryItem.Album) navigator.openAlbum(item.rawId)
    }
}

/** The Apple shells' sort picker as a row of buttons, the chosen one lit and marked selected. */
@Composable
private fun TvAlbumSortRow(order: AndroidAlbumListType, choose: (AndroidAlbumListType) -> Unit) {
    val returning = pendingPosition("library.albums.sort.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(SharedR.string.library_sort_by), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val state = rememberLazyListState()
        LaunchedEffect(returning) { if (returning != null) state.scrollToItem(returning) }
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(AlbumSort.CHOICES) { _, choice ->
                val chosen = choice == order
                Button(
                    onClick = { choose(choice) },
                    modifier = Modifier.tvFocus("library.albums.sort.${AlbumSort.tag(choice)}").semantics { selected = chosen },
                    colors = if (chosen) androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) else androidx.tv.material3.ButtonDefaults.colors(),
                ) {
                    if (chosen) Icon(DulcetIcons.Check, null, Modifier.size(20.dp).padding(end = 4.dp))
                    Text(stringResource(AlbumSort.titleResource(choice)))
                }
            }
        }
    }
}

/** Every genre, one response (`getGenres`, §16.9); a genre opens its songs, as on Apple. */
@Composable
private fun TvGenresGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_GENRES) { openGenres() }
    TvBrowseGrid(account, session, surface, "library.genres", SharedR.string.library_genres) { item ->
        if (item is AndroidLibraryItem.Genre) navigator.open(GENRE + item.rawId)
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
                         controls: (@Composable () -> Unit)? = null, open: (AndroidLibraryItem) -> Unit) {
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
    LazyVerticalGrid(GridCells.Adaptive(180.dp),
        Modifier.fillMaxSize().testTag(tag).semantics { this[LibraryObservation] = observation }, state = grid,
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.displaySmall)
                controls?.invoke()
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
        // Re-evaluated when the window changes shape as well as on every move, as the phone's grid is:
        // a window rebased or given back to what fits the screen can show its end with nowhere left to
        // move, and must still grow; a publication changing freshness or coverage alone asks for nothing.
        snapshotFlow {
            val visible = grid.layoutInfo.visibleItemsInfo.filter { it.key is String }
            (visible.firstOrNull()?.index to visible.lastOrNull()?.index) to latest?.let { it.items.size to it.leadingOffset }
        }.collect { (range, _) ->
            val (first, last) = range
            val shown = latest
            if (shown == null || first == null || last == null) return@collect
            val from = (first - 1).coerceAtLeast(0)
            val to = (last - 1).coerceAtLeast(from)
            surface.setViewport(from, to)
            if (shown.growsAtEnd() && to >= shown.items.size - PAGE_AHEAD) surface.loadMore()
            if (shown.leadingOffset > 0 && from == 0) surface.loadBefore()
        }
    }
}

private const val PAGE_AHEAD = 12

/** A list's viewport, as [ReportViewport] for a grid: rows keyed by a string, after [header] other items. */
@Composable
private fun ReportListViewport(surface: LibrarySurface, list: LazyListState, publication: AndroidLibraryPublication?, header: Int) {
    val latest by rememberUpdatedState(publication)
    LaunchedEffect(surface, list) {
        // Re-evaluated when the window changes shape as well as on every move ([ReportViewport]).
        snapshotFlow {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.key is String }
            (visible.firstOrNull()?.index to visible.lastOrNull()?.index) to latest?.let { it.items.size to it.leadingOffset }
        }.collect { (range, _) ->
            val (first, last) = range
            val shown = latest
            if (shown == null || first == null || last == null) return@collect
            val from = (first - header).coerceAtLeast(0)
            val to = (last - header).coerceAtLeast(from)
            surface.setViewport(from, to)
            if (shown.growsAtEnd() && to >= shown.items.size - PAGE_AHEAD) surface.loadMore()
            if (shown.leadingOffset > 0 && from == 0) surface.loadBefore()
        }
    }
}

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
        resources.orderLine(publication)?.let { TvStatement(it, "$tag.order") }
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
    downloadRequests: DownloadRequests? = null,
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
    TvAddToUpNext(adding) { adding = null }
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
                                    icon = if (favourite) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder,
                                ) { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId)) }
                                if (current.itemsState == AndroidLibraryItemsState.Present) {
                                    val tracks = current.items.filterIsInstance<AndroidLibraryItem.Track>()
                                    val state = AlbumDownloads.of(tracks, downloads)
                                    if (state.anyRequested) TvAction(resources.getString(SharedR.string.download_album_remove),
                                        "album.download.remove", icon = DulcetIcons.DownloadDone) {
                                        requests.remove(tracks.map { it.rawId }.filter { it in downloads })
                                    } else if (state.downloadable > 0) TvAction(resources.getString(SharedR.string.download_album),
                                        "album.download", icon = DulcetIcons.Download) { requests.download(tracks) }
                                }
                                // Play Next and Add to Queue for the album, beside its download (spec §14.1).
                                if (playback != null && playable) TvAction(resources.getString(SharedR.string.queue_add_options), "album.queue",
                                    icon = DulcetIcons.QueueMusic) {
                                    val shownNow = shown ?: return@TvAction
                                    fun add(insertion: AndroidQueueInsertion) {
                                        if (!queueAlbum(playback, provider, shownNow, insertion)) queueEditRefused(context)
                                    }
                                    adding = TvQueueAddition((shownNow.header as? AndroidLibraryItem.Album)?.title.orEmpty(),
                                        { add(AndroidQueueInsertion.PlayNext) }, { add(AndroidQueueInsertion.AddToQueue) })
                                }
                            }
                            if (current.itemsState == AndroidLibraryItemsState.Present) {
                                context.resources.albumDownloadLine(AlbumDownloads.of(
                                    current.items.filterIsInstance<AndroidLibraryItem.Track>(), downloads))
                                    ?.let { TvStatement(it, "album.download.progress") }
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
                    onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, item.rawId)) },
                    download = downloads[item.rawId],
                    onDownloadToggle = when {
                        item.rawId in downloads -> { { requests.remove(listOf(item.rawId)) } }
                        item.downloadItem() != null -> { { requests.download(listOf(item)) } }
                        else -> null
                    },
                    onQueue = tvTrackAddition(context, playback, provider, item, album)?.let { addition -> { adding = addition } },
                )
            }
        }
    }
}

/**
 * The account's favourites (§16.9 `getStarred2`): songs, then albums and artists as rows of cards.
 * A song keeps its heart beside it; a heart taken off here leaves the song in the list, hollow,
 * until the list is next read, so it can be put back where it was taken off.
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
    var adding by remember { mutableStateOf<TvQueueAddition?>(null) }
    TvAddToUpNext(adding) { adding = null }
    val shown by rememberUpdatedState(publication)
    val plays = rememberPlaybackBinding(playback)
    val title = resources.getString(SharedR.string.library_home_favourites)
    LazyColumn(
        Modifier.fillMaxSize().testTag("library.favourites").semantics { this[LibraryObservation] = observation },
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(stringResource(R.string.tv_back), "library.favourites.back", onClick = navigator.back)
                TvAction(stringResource(R.string.tv_refresh), "library.favourites.refresh", onClick = session::refresh)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tv_library_favourites), style = MaterialTheme.typography.displaySmall)
                TvConnectionNotices(session, accountNotices = false)
                if (current != null) {
                    if (current.items.isEmpty() && current.itemsState == AndroidLibraryItemsState.Present &&
                        current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading) {
                        resources.freshnessLine(current.freshness)?.let { TvStatement(it, "library.favourites.freshness") }
                        TvStatement(resources.getString(SharedR.string.library_favourites_empty), "library.favourites.empty")
                    } else {
                        TvListStatus(current, "library.favourites", session::retry)
                    }
                }
                TvOutcomeLines(outcomeLines, null, "library.favourites.outcome")
                note?.let { TvStatement(it, "library.favourites.note") }
            }
        }
        if (tracks.isNotEmpty()) {
            item { Text(stringResource(SharedR.string.library_favourites_songs), style = MaterialTheme.typography.headlineSmall) }
            itemsIndexed(tracks, key = { _, track -> "track:" + track.rawId }) { position, track ->
                TvTrackRow(track, position, playing = track.rawId == playingRawId, tagPrefix = "library.favourites.track",
                    default = position == 0,
                    onPlay = {
                        note = null
                        val list = shown?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
                        playTracks(plays, account.providerInstanceId, list, track.rawId, title) { showNowPlaying(context) }
                    },
                    onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                    onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)) },
                    onQueue = tvTrackAddition(context, playback, account.providerInstanceId, track, null)
                        ?.let { addition -> { adding = addition } },
                )
            }
        }
        if (albums.isNotEmpty()) {
            item { Text(stringResource(SharedR.string.library_favourites_albums), style = MaterialTheme.typography.headlineSmall) }
            item {
                LazyRow(contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(albums, key = { _, album -> album.rawId }) { position, album ->
                        TvCard(account, album, "library.favourites.album.$position", default = tracks.isEmpty() && position == 0) {
                            navigator.openAlbum(album.rawId)
                        }
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item { Text(stringResource(SharedR.string.library_favourites_artists), style = MaterialTheme.typography.headlineSmall) }
            item {
                LazyRow(contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(artists, key = { _, artist -> artist.rawId }) { position, artist ->
                        TvCard(account, artist, "library.favourites.artist.$position",
                            default = tracks.isEmpty() && albums.isEmpty() && position == 0) { navigator.openArtist(artist.rawId) }
                    }
                }
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
 * The heart beside a row or on a card's line: its own focus target, so the row keeps its one action
 * (play). Filled is a favourite, with any pending change in it (§16.20).
 */
/** A track's download: adds it, or — once requested — removes it. */
@Composable
private fun TvDownloadButton(requested: Boolean, tag: String, onClick: () -> Unit) {
    val resources = libraryResources()
    IconButton(onClick = onClick, modifier = Modifier.tvFocus(tag).semantics { selected = requested }) {
        Icon(if (requested) DulcetIcons.DownloadDone else DulcetIcons.Download,
            resources.getString(if (requested) SharedR.string.download_track_remove else SharedR.string.download_track))
    }
}

/** Opens Play Next and Add to Queue for what it sits beside. */
@Composable
private fun TvQueueButton(tag: String, onClick: () -> Unit) {
    val resources = libraryResources()
    IconButton(onClick = onClick, modifier = Modifier.tvFocus(tag)) {
        Icon(DulcetIcons.QueueMusic, resources.getString(SharedR.string.queue_add_options))
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
): TvQueueAddition? {
    // Hidden while no playback service is bound, rather than offered and then refused (spec §14.1).
    if (playback == null || !track.canBeQueued()) return null
    val library = context.getString(R.string.tv_library_title)
    fun add(insertion: AndroidQueueInsertion) {
        if (!queueTrack(playback, provider, track, insertion, library, album)) queueEditRefused(context)
    }
    return TvQueueAddition(track.title.orEmpty(), { add(AndroidQueueInsertion.PlayNext) }, { add(AndroidQueueInsertion.AddToQueue) })
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
    if (playability == AndroidLibraryPlayability.UnavailableOffline || result.title.isBlank()) return null
    val search = context.getString(R.string.tv_nav_search)
    fun add(insertion: AndroidQueueInsertion) {
        if (!queueSearchResult(playback, provider, result, insertion, search)) queueEditRefused(context)
    }
    return TvQueueAddition(result.title, { add(AndroidQueueInsertion.PlayNext) }, { add(AndroidQueueInsertion.AddToQueue) })
}

@Composable
private fun TvFavouriteButton(favourite: Boolean, tag: String, onClick: () -> Unit) {
    val resources = libraryResources()
    IconButton(onClick = onClick, modifier = Modifier.tvFocus(tag).semantics { selected = favourite }) {
        Icon(if (favourite) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder,
            resources.getString(if (favourite) SharedR.string.library_favourite_remove else SharedR.string.library_favourite_add))
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
internal fun TvTrackRow(track: AndroidLibraryItem.Track, position: Int, playing: Boolean, onPlay: () -> Unit,
                       onUnavailable: () -> Unit, onFavourite: (() -> Unit)? = null, tagPrefix: String = "album.track",
                       default: Boolean = false, byPosition: Boolean = false,
                       download: AndroidDownloadStatus? = null, onDownloadToggle: (() -> Unit)? = null,
                       onQueue: (() -> Unit)? = null) {
    if (onFavourite == null && onQueue == null) {
        TvTrackRowBody(track, position, playing, onPlay, onUnavailable, tagPrefix, default, Modifier.fillMaxWidth(), byPosition, download)
        return
    }
    // The heart is beside the row, a focus target of its own: RIGHT from the row reaches it, and the
    // row stays one target whose action is play.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvTrackRowBody(track, position, playing, onPlay, onUnavailable, tagPrefix, default, Modifier.weight(1f), byPosition, download)
        if (onFavourite != null) TvFavouriteButton(track.isFavourite(), "$tagPrefix.$position.favourite", onFavourite)
        // The track's own download, one more focus target to the right (spec §14.5).
        if (onDownloadToggle != null) TvDownloadButton(download != null, "$tagPrefix.$position.download", onDownloadToggle)
        // Play Next and Add to Queue, the last target to the right (spec §14.1).
        if (onQueue != null) TvQueueButton("$tagPrefix.$position.queue", onQueue)
    }
}

@Composable
private fun TvTrackRowBody(track: AndroidLibraryItem.Track, position: Int, playing: Boolean, onPlay: () -> Unit,
                           onUnavailable: () -> Unit, tagPrefix: String, default: Boolean, modifier: Modifier,
                           byPosition: Boolean = false, download: AndroidDownloadStatus? = null) {
    val unavailable = track.playability == AndroidLibraryPlayability.UnavailableOffline
    val select = if (unavailable) onUnavailable else onPlay
    val resources = libraryResources()
    val nowPlaying = stringResource(R.string.tv_now_playing_row)
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.tvFocus("$tagPrefix.$position", default)
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
            if (playing) Icon(DulcetIcons.Play, nowPlaying, Modifier.size(20.dp).testTag("$tagPrefix.$position.playing"), tint = content)
            else Text((if (byPosition) position + 1 else track.trackNumber ?: position + 1).toString(), color = content)
        }
        Text(track.title.orEmpty(), Modifier.weight(1f), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (unavailable) Text(resources.getString(SharedR.string.library_not_available_offline), color = content)
        else if (download != null && !download.playsOffline) LocalContext.current.resources.downloadLine(download)?.let {
            Text(it, Modifier.testTag("$tagPrefix.$position.download.status"), color = content)
        }
        if (download?.playsOffline == true) Icon(DulcetIcons.DownloadDone,
            resources.getString(SharedR.string.download_downloaded),
            Modifier.size(20.dp).testTag("$tagPrefix.$position.downloaded"), tint = content)
        track.durationMilliseconds?.let { Text(clock(it), color = content) }
    }
}

// ---- Playlists (spec §18.6): browse and play; editing is the phone's -------------------------------------

/** The account's playlists, one response (§16.9). */
@Composable
private fun TvPlaylistsGrid(account: SearchAccount, session: LibrarySession, navigator: TvNavigator) {
    EnterRoute()
    val surface = rememberSurface(session, ROUTE_PLAYLISTS) { openPlaylists() }
    TvBrowseGrid(account, session, surface, "library.playlists", R.string.tv_library_playlists) { item ->
        if (item is AndroidLibraryItem.Playlist) navigator.open(PLAYLIST + item.rawId)
    }
}

/**
 * One playlist, read-only here: its name, whose it is, and its entries in the playlist's order,
 * duplicates kept. Play and Shuffle queue the playable entries; an entry plays the playlist from it.
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
    val surface = rememberSurface(session, PLAYLIST + rawId) { openPlaylist(rawId) }
    val context = LocalContext.current
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    var adding by remember(rawId) { mutableStateOf<TvQueueAddition?>(null) }
    TvAddToUpNext(adding) { adding = null }
    val resources = libraryResources()
    val provider = account.providerInstanceId
    val plays = rememberPlaybackBinding(playback)
    fun play(current: AndroidLibraryPublication, position: Int, shuffle: Boolean) {
        note = null
        playPlaylist(plays, provider, current, position, shuffle) { showNowPlaying(context) }
    }
    // tv-material buttons can keep an onClick from an earlier composition: they play what is shown now.
    val shown by rememberUpdatedState(publication)
    val returning = pendingPosition("playlist.entry.")
    val list = rememberLazyListState()
    LaunchedEffect(returning, publication?.items?.size) {
        val size = publication?.items?.size ?: return@LaunchedEffect
        if (returning != null && returning < size) list.scrollToItem(returning + 2)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("playlist.surface").semantics { this[LibraryObservation] = observation },
        state = list,
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(stringResource(R.string.tv_back), "playlist.back", onClick = navigator.back)
                TvAction(stringResource(R.string.tv_refresh), "playlist.refresh", onClick = session::refresh)
            }
        }
        val current = publication
        val playlist = current?.header as? AndroidLibraryItem.Playlist
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TvConnectionNotices(session, accountNotices = false)
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvArtwork(account, playlist?.artworkKey, 200, DulcetIcons.PlaylistPlay)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(playlist?.name.orEmpty(), Modifier.testTag("playlist.title"),
                            style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        playlist?.let { resources.playlistOwnerLine(it) }?.let { TvStatement(it, "playlist.owner") }
                        playlist?.let { resources.playlistPendingLine(it) }?.let { TvStatement(it, "playlist.pending") }
                        if (current != null) {
                            resources.freshnessLine(current.freshness)?.let { TvStatement(it, "playlist.freshness") }
                            (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                                TvStatement(resources.unavailableLine(unavailable.reason, LibrarySubject.List), "playlist.unavailable")
                            }
                        }
                        if (current != null && playlist != null) {
                            val playable = current.itemsState == AndroidLibraryItemsState.Present && current.items.any {
                                it is AndroidLibraryItem.Track && !it.metadataMissing && it.playability != AndroidLibraryPlayability.UnavailableOffline
                            }
                            if (playable) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TvAction(stringResource(R.string.tv_play), "playlist.play", icon = DulcetIcons.Play, default = true) {
                                    shown?.let { play(it, 0, false) }
                                }
                                TvAction(stringResource(R.string.tv_shuffle), "playlist.shuffle", icon = DulcetIcons.Shuffle) {
                                    shown?.let { play(it, 0, true) }
                                }
                            }
                        }
                        note?.let { TvStatement(it, "playlist.note") }
                    }
                }
            }
        }
        if (current == null) return@LazyColumn
        when (current.itemsState) {
            AndroidLibraryItemsState.Loading -> item { TvStatement("…", "playlist.entries.loading") }
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
                if (current.items.isEmpty()) item { TvStatement(resources.getString(SharedR.string.library_empty_list), "playlist.empty") }
                itemsIndexed(current.items) { position, item ->
                    if (item is AndroidLibraryItem.Track) TvTrackRow(
                        item,
                        position,
                        playing = item.rawId == playingRawId,
                        onPlay = { play(current, position, false) },
                        onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                        tagPrefix = "playlist.entry",
                        byPosition = true,
                        onQueue = tvTrackAddition(context, playback, provider, item, null)?.let { addition -> { adding = addition } },
                    )
                }
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
) {
    EnterRoute()
    val surface = rememberSurface(session, GENRE + name) { openGenre(name) }
    val context = LocalContext.current
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    var note by remember(name) { mutableStateOf<String?>(null) }
    var adding by remember(name) { mutableStateOf<TvQueueAddition?>(null) }
    TvAddToUpNext(adding) { adding = null }
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
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(stringResource(R.string.tv_back), "genre.back", onClick = navigator.back)
                TvAction(stringResource(R.string.tv_refresh), "genre.refresh", onClick = session::refresh)
            }
        }
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
                    tracks.any { !it.metadataMissing && it.playability != AndroidLibraryPlayability.UnavailableOffline }
                if (playable) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvAction(stringResource(R.string.tv_play), "genre.play", icon = DulcetIcons.Play, default = true) { play(null, false) }
                    TvAction(stringResource(R.string.tv_shuffle), "genre.shuffle", icon = DulcetIcons.Shuffle) { play(null, true) }
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
                onQueue = tvTrackAddition(context, playback, provider, track, null)?.let { addition -> { adding = addition } },
            )
        }
    }
}

/** The genre screen's items before its songs: the Back row, then the header. */
private const val GENRE_HEADER_ITEMS = 2

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
    LaunchedEffect(returning, albums.size) {
        if (returning != null && returning < albums.size) grid.scrollToItem(returning)
    }
    // The header stands above the grid: focused actions in it must not scroll the cards, and the
    // grid cannot clip it.
    Column(Modifier.fillMaxSize().testTag("artist.surface").semantics { this[LibraryObservation] = observation }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 56.dp).padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvAction(stringResource(R.string.tv_back), "artist.back", onClick = navigator.back)
                TvAction(stringResource(R.string.tv_refresh), "artist.refresh", onClick = session::refresh)
            }
            TvConnectionNotices(session, accountNotices = false)
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                TvArtwork(account, artist?.artworkKey, 160, DulcetIcons.Person, circle = true)
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
                    if (artist != null) {
                        val favourite = artist.favourite == true
                        TvAction(
                            resources.getString(if (favourite) SharedR.string.library_favourite_remove else SharedR.string.library_favourite_add),
                            "artist.favourite",
                            description = resources.getString(
                                if (favourite) SharedR.string.library_favourite_on else SharedR.string.library_favourite_add),
                            icon = if (favourite) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder,
                        ) { session.toggleFavourite(target) }
                    }
                    note?.let { TvStatement(it, "artist.note") }
                    TvOutcomeLines(outcomeLines, target, "artist.outcome")
                }
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(180.dp),
            Modifier.fillMaxWidth().weight(1f),
            state = grid, contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            itemsIndexed(albums, key = { _, album -> album.rawId }) { position, album ->
                TvCard(account, album, "artist.album.$position", width = null) { navigator.openAlbum(album.rawId) }
            }
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
        is AndroidLibraryItem.Track, is AndroidLibraryItem.Genre -> DulcetIcons.MusicNote
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
        // From the couch a focused card must be obvious at a glance: it grows a touch and gains a ring.
        scale = CardDefaults.scale(focusedScale = 1.04f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary))),
    ) {
        Column {
            // An artist's art is a circle, as artists are drawn everywhere; albums and tracks are square.
            val artist = item is AndroidLibraryItem.Artist
            TvArtwork(account, artwork, width ?: 180, placeholder,
                Modifier.fillMaxWidth().aspectRatio(1f).padding(if (artist) 14.dp else 0.dp),
                rounded = false, circle = artist)
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
