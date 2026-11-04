package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.library.LibraryHomeRowSurface
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.MyLibrarySession
import com.legitimateapps.dulcet.library.displayTitle
import com.legitimateapps.dulcet.library.pinKind
import com.legitimateapps.dulcet.library.rememberSurface
import com.legitimateapps.dulcet.library.subtitle
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity

/*
 * The Library root and the destinations its rows push. The root is an M3 list (Playlists, Favorites,
 * Downloads, Wishlist when advertised, Pinned when any, Most Played, then Artists/Albums/Songs[/Genres])
 * with one personal "Recently added" strip below it — the old home's first row, the only shelf the
 * root keeps (the rest duplicated Listen Now). The strip keeps the `library.home.0.*` tags the tours
 * and proofs drive; the rows carry the old chip strip's `library.view.*` tags.
 */

/** The routes the Library rows push; PhoneApp's router hands them to [LibraryRoutePage]. */
internal val LibraryRouteIds = setOf(
    "albums", "artists", "songs", "songsBrowse", "genres", "downloaded",
    "favourites", "playlists", "pinned",
    "mostPlayed", "recentlyAdded", "recentlyPlayed",
    "myLibraryAlbums", "myLibraryArtists", "myLibrarySongs",
)

/** One pushed Library destination. [route] is one of [LibraryRouteIds]. */
@Composable
internal fun LibraryRoutePage(
    route: String,
    account: SearchAccount,
    session: LibrarySession,
    actions: PhoneActions,
    playingRawId: String?,
    myLibrary: MyLibrarySession?,
) {
    var createPlaylistOpen by rememberSaveable(route) { mutableStateOf(false) }
    val title = when (route) {
        "albums", "myLibraryAlbums" -> R.string.library_view_albums
        "artists", "myLibraryArtists" -> R.string.library_view_artists
        "songs", "songsBrowse", "myLibrarySongs" -> SharedR.string.search_group_songs
        "genres" -> SharedR.string.library_genres
        "downloaded" -> R.string.library_downloads_title
        "favourites" -> R.string.library_favorites_title
        "playlists" -> R.string.library_view_playlists
        else -> null
    }
    when (route) {
        "mostPlayed" -> MostPlayedPage(account, session, actions)
        "recentlyAdded" -> RecentAlbumsPage(account, session, actions, AndroidAlbumListType.Newest)
        "recentlyPlayed" -> RecentAlbumsPage(account, session, actions, AndroidAlbumListType.Recent)
        "pinned" -> PinnedPage(account, session, actions)
        else -> if (title != null) LibraryPage(session, stringResource(title), "library.$route.back", actions.back,
            refreshing = false, onRefresh = session::refresh, local = route == "downloaded",
            toolbarActions = {
                if (route == "playlists") TextButton(
                    onClick = { createPlaylistOpen = true },
                    modifier = Modifier.testTag("library.playlists.new"),
                ) { Text(stringResource(R.string.playlist_new)) }
            }) {
            when (route) {
                "albums" -> AlbumsGrid(account, session, actions, myLibrary)
                "artists" -> ArtistsList(session, actions, myLibrary)
                "songs", "songsBrowse" -> DeviceSongs(account, session, actions, false, playingRawId)
                "genres" -> GenresList(session, actions)
                "downloaded" -> DeviceSongs(account, session, actions, true, playingRawId)
                "favourites" -> FavouritesList(account, session, actions, playingRawId, myLibrary)
                "playlists" -> PlaylistsList(account, session, actions, createPlaylistOpen) { createPlaylistOpen = it }
                else -> MyLibraryCollection(account, session, myLibrary, actions, route, playingRawId)
            }
        }
    }
}

/** One root row: a leading icon and a label, the whole row the tap target; no chevron. */
@Composable
internal fun LibraryRootRow(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

/** The icons the root rows need that [DulcetIcons] does not carry. */
internal object LibraryRowIcons {
    val PushPin: ImageVector = icon("PushPin",
        "M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z")
    val TrendingUp: ImageVector = icon("TrendingUp",
        "M16 6l2.29 2.29-4.88 4.88-4-4L2 16.59 3.41 18l6-6 4 4 6.3-6.29L22 12V6z")
    val History: ImageVector = icon("History",
        "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z")
    val Explore: ImageVector = icon("Explore",
        "M12 10.9c-.61 0-1.1.49-1.1 1.1s.49 1.1 1.1 1.1c.61 0 1.1-.49 1.1-1.1s-.49-1.1-1.1-1.1zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19z")
    val Label: ImageVector = icon("Label",
        "M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z")
    val Schedule: ImageVector = icon("Schedule",
        "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(androidx.compose.ui.graphics.Color.Black))
            .build()
}

/**
 * The root's "Recently added": the old home's first row as the one strip the root keeps, with a
 * See all that pushes the full destination. Skeletons while loading, hidden once loaded empty or
 * unavailable — the destination's own status says why. The whole page of the row is shown: the
 * conformance scenarios scroll it to a named album.
 */
@Composable
internal fun RecentlyAddedStrip(
    account: SearchAccount,
    row: LibraryHomeRowSurface,
    actions: PhoneActions,
    myLibrary: MyLibrarySession?,
    tagPrefix: String,
) {
    val shelfTitle = stringResource(SharedR.string.library_home_newest)
    val publication by row.surface.state.collectAsState()
    val current = publication
    if (current != null && current.items.isEmpty() && current.freshness != AndroidLibraryFreshness.Loading) return
    Column(Modifier.fillMaxWidth().testTag(tagPrefix).padding(top = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(SharedR.string.library_home_newest),
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                )
                if (current?.items?.isNotEmpty() == true && current.itemsState == AndroidLibraryItemsState.Present) {
                    TextButton(onClick = { actions.openRoute("recentlyAdded") }, modifier = Modifier.offset(x = 12.dp).testTag("$tagPrefix.seeAll")) {
                        Text(stringResource(R.string.library_see_all))
                    }
                }
            }
            if (current == null || current.items.isEmpty() && current.freshness == AndroidLibraryFreshness.Loading) {
                RowPlaceholder(Modifier.testTag("$tagPrefix.loading"), cardWidth = 160.dp)
                return@Column
            }
            LazyRow(
                Modifier.testTag("$tagPrefix.items"),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(current.items, key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                    val tag = "$tagPrefix.item.$position"
                    AlbumCard(account, item, Modifier.width(160.dp).testTag(tag),
                        library = libraryAction(myLibrary, item, tag)) {
                        when (item) {
                            is AndroidLibraryItem.Album -> actions.openAlbum(item.rawId)
                            is AndroidLibraryItem.Artist -> actions.openArtist(item.rawId)
                            is AndroidLibraryItem.Track ->
                                actions.playTracks(current.items, item.rawId, shelfTitle)
                            else -> Unit
                        }
                    }
                }
            }
        }
}

// ---- Ranked and recent destinations -------------------------------------------------------------------

/**
 * Most Played (A06): the account's frequent albums as a ranked list — the rank in tabular figures
 * before the artwork, never a sort control on a ranking. Empty says the server reports no plays.
 */
@Composable
internal fun MostPlayedPage(account: SearchAccount, session: LibrarySession, actions: PhoneActions) {
    val surface = rememberSurface(session, "albums:Frequent") { openAlbums(AndroidAlbumListType.Frequent) }
    val publication by surface.state.collectAsState()
    val current = publication
    val albums = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Album>()
    LibraryPage(
        session, stringResource(SharedR.string.library_home_frequent), "mostPlayed.back", actions.back,
        refreshing = current == null || current.freshness == AndroidLibraryFreshness.Loading, onRefresh = session::refresh,
    ) {
        Column(Modifier.fillMaxSize()) {
            // The status stands above the list: as a lazy first item it starts empty, which the list treats
            // as scrolled past, so a failure arriving later stayed above the viewport.
            if (current != null) ListStatus(current, "mostPlayed", session::retry)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mostPlayed.list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                when {
                    current != null && current.freshness != AndroidLibraryFreshness.Loading &&
                        current.itemsState == AndroidLibraryItemsState.Present && albums.isEmpty() ->
                        item {
                            EmptyState(
                                LibraryRowIcons.TrendingUp, stringResource(R.string.library_empty_most_played),
                                body = stringResource(R.string.library_empty_most_played_body),
                                modifier = Modifier.testTag( "mostPlayed.empty"),
                            )
                        }
                    else -> itemsIndexed(albums, key = { _, album -> album.rawId }) { position, album ->
                        RankedAlbumRow(account, album, position + 1, Modifier.testTag("mostPlayed.item.$position")) {
                            actions.openAlbum(album.rawId)
                        }
                    }
                }
            }
        }
    }
}

/** The reader's recent window, with no competing sort or filters. */
@Composable
internal fun RecentAlbumsPage(account: SearchAccount, session: LibrarySession, actions: PhoneActions, type: AndroidAlbumListType) {
    val surface = rememberSurface(session, "albums:$type") { openAlbums(type) }
    val publication by surface.state.collectAsState()
    val tag = if (type == AndroidAlbumListType.Newest) "recentlyAdded" else "recentlyPlayed"
    LibraryPage(session, stringResource(if (type == AndroidAlbumListType.Newest) SharedR.string.library_home_newest else SharedR.string.library_home_recent),
        "$tag.back", actions.back, publication == null || publication?.freshness == AndroidLibraryFreshness.Loading, session::refresh) {
        val current = publication
        val albums = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Album>()
        if (LocalDensity.current.fontScale >= 1.5f) {
            Column(Modifier.fillMaxSize()) {
                // The status stands above the list: as a lazy first item it starts empty, which the list treats
                // as scrolled past, so a failure arriving later stayed above the viewport.
                if (current != null) ListStatus(current, tag, session::retry)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("$tag.list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (tag == "recentlyAdded") item { Text(stringResource(R.string.library_newest_first),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (current != null && current.itemsState == AndroidLibraryItemsState.Present && albums.isEmpty()) item {
                        EmptyState(LibraryRowIcons.History, stringResource(R.string.library_empty_recently_played))
                    }
                    itemsIndexed(albums, key = { _, item -> item.rawId }) { index, album ->
                        RankedAlbumRow(account, album, null, Modifier.testTag("$tag.item.$index")) { actions.openAlbum(album.rawId) }
                    }
                }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize().testTag("$tag.list"),
                contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // One item: an empty status in its own item would still take a row of grid spacing.
                if (current != null || tag == "recentlyAdded") item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.bleedKeyline()) {
                        if (current != null) ListStatus(current, tag, session::retry)
                        if (tag == "recentlyAdded") Text(stringResource(R.string.library_newest_first),
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (current != null && current.itemsState == AndroidLibraryItemsState.Present && albums.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(LibraryRowIcons.History, stringResource(R.string.library_empty_recently_played)) }
                }
                gridItemsIndexed(albums, key = { _, item -> item.rawId }) { index, album ->
                    AlbumCard(account, album, Modifier.testTag("$tag.item.$index")) { actions.openAlbum(album.rawId) }
                }
            }
        }
    }
}

/** One ranked row: the rank in tabular figures before the 56 dp artwork. */
@Composable
internal fun RankedAlbumRow(account: SearchAccount, album: AndroidLibraryItem.Album, rank: Int?, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(album.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(album.artistName,
                    if (rank != null) album.playCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.library_play_count, it.toInt(), it) }
                    else album.originalReleaseDate?.take(4) ?: album.year?.toString()).metadataLine(),
                maxLines = if (LocalDensity.current.fontScale >= 1.5f) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rank != null) Text(
                    rank.toString(), Modifier.widthIn(min = 28.dp, max = 48.dp), maxLines = 1,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Artwork(account, album.artworkKey, album.title, 56.dp)
            }
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

// ---- Pinned ---------------------------------------------------------------------------------------------

/**
 * The pinned collection (A41): the device pins as shared rows, Unpin in the row's menu — the loose
 * text button is gone. The root shows a Pinned row only while a pin exists.
 */
@Composable
internal fun PinnedPage(account: SearchAccount, session: LibrarySession, actions: PhoneActions) {
    val depth by session.depth.collectAsState()
    val surface = rememberSurface(session, "pinned") { openPinned() }
    val publication by surface.state.collectAsState()
    val failed = publication?.freshness is com.legitimateapps.dulcet.core.AndroidLibraryFreshness.Unavailable
    val loading = depth == null || (!failed && depth?.pinCount != 0 && (publication == null || publication?.itemsState != com.legitimateapps.dulcet.core.AndroidLibraryItemsState.Present || (publication?.items.isNullOrEmpty() && publication?.freshness == com.legitimateapps.dulcet.core.AndroidLibraryFreshness.Loading)))
    val items = if (depth?.pinCount == 0) emptyList() else publication?.items.orEmpty()
    LibraryPage(
        session, stringResource(SharedR.string.library_pinned), "library.pinned.back", actions.back,
        refreshing = false, onRefresh = session::refresh,
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("library.pinned"), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp).testTag("library.pinned.loading")) }
            else if (failed && items.isEmpty()) item { publication?.let { ListStatus(it, "library.pinned", session::retry) } }
            else if (items.isEmpty()) {
                item {
                    EmptyState(
                        LibraryRowIcons.PushPin, stringResource(R.string.library_empty_pinned),
                        body = stringResource(R.string.library_empty_pinned_body),
                        modifier = Modifier.testTag( "library.pinned.empty"),
                    )
                }
            }
            itemsIndexed(items, key = { _, item -> item.pinKind() + ":" + item.rawId }) { index, item ->
                PinnedRow(account, session, actions, item, index)
            }
        }
    }
}

@Composable
private fun PinnedRow(account: SearchAccount, session: LibrarySession, actions: PhoneActions, item: AndroidLibraryItem, index: Int) {
    var menuOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(item.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val subtitle = when (item) {
                    is AndroidLibraryItem.Artist -> item.albumCount?.takeIf { it > 0 }
                        ?.let { pluralStringResource(R.plurals.library_album_count, it, it) }
                    else -> item.subtitle()
                }
                val kind = when (item) {
                    is AndroidLibraryItem.Album -> stringResource(R.string.search_kind_album)
                    is AndroidLibraryItem.Artist -> stringResource(R.string.search_kind_artist)
                    is AndroidLibraryItem.Track -> stringResource(R.string.search_kind_track)
                    is AndroidLibraryItem.Playlist -> stringResource(R.string.restyle_pinned_playlist_kind)
                    else -> null
                }
                Text(listOfNotNull(kind, subtitle).metadataLine(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = {
                when (item) {
                    is AndroidLibraryItem.Artist -> Monogram(item.name, 56.dp)
                    else -> Artwork(account, (item as? AndroidLibraryItem.Album)?.artworkKey
                        ?: (item as? AndroidLibraryItem.Track)?.artworkKey
                        ?: (item as? AndroidLibraryItem.Playlist)?.artworkKey, item.displayTitle(), 56.dp,
                        entity = when (item) {
                            is AndroidLibraryItem.Album -> ArtworkEntity.Album
                            is AndroidLibraryItem.Track -> ArtworkEntity.Song
                            is AndroidLibraryItem.Playlist -> ArtworkEntity.Playlist
                            else -> ArtworkEntity.Album
                        })
                }
            },
            trailingContent = {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("library.pinned.item.$index.menu")) {
                    Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
                }
            },
            modifier = Modifier.testTag("library.pinned.item.$index").combinedClickable(onLongClick = { menuOpen = true }, onClick = {
                when (item) {
                    is AndroidLibraryItem.Album -> actions.openAlbum(item.rawId)
                    is AndroidLibraryItem.Artist -> actions.openArtist(item.rawId)
                    is AndroidLibraryItem.Playlist -> actions.openPlaylist(item.rawId)
                    is AndroidLibraryItem.Track -> actions.playTracks(listOf(item), item.rawId, item.title.orEmpty())
                    else -> Unit
                }
            }),
        )
        androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(SharedR.string.library_unpin)) },
                leadingIcon = { Icon(LibraryRowIcons.PushPin, null) },
                onClick = { menuOpen = false; session.togglePin(item) },
                modifier = Modifier.testTag("library.pinned.unpin.$index"),
            )
        }
    }
}

/** Root app bar and refresh semantics shared by both library modes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryRootFrame(title: String, refreshTag: String, onRefresh: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false, content: @Composable () -> Unit) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Column(modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection).testTag("library.surface")) {
        PhoneRootTopBar(title, scroll)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("mylibrary.loading"))
        PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh, modifier = Modifier.weight(1f).testTag(refreshTag)
            .semantics { customActions = listOf(CustomAccessibilityAction("Refresh") { onRefresh(); true }) }) { content() }
    }
}

/**
 * The tab pages' one top bar: a single row, title and avatar together, tinted once the page is
 * under it. A root page has no back control, so a large bar's first row would stand empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneRootTopBar(title: String, scrollBehavior: TopAppBarScrollBehavior) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = { AccountAvatarAction() },
        colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        scrollBehavior = scrollBehavior,
    )
}

/** Pushed library pages share their title, back control and pull-to-refresh behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryPage(session: LibrarySession, title: String, backTag: String, back: () -> Unit,
    refreshing: Boolean, onRefresh: () -> Unit, local: Boolean = false,
    toolbarActions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val observation by session.observation.collectAsState()
    Column(Modifier.fillMaxSize().testTag("library.surface").semantics { this[com.legitimateapps.dulcet.library.LibraryObservation] = observation }) {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = back, modifier = Modifier.testTag(backTag)) { Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back)) }
        }, actions = { toolbarActions() })
        ConnectionNotices(session, accountNotices = false, local = local)
        PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh, modifier = Modifier.weight(1f)) { content() }
    }
}
