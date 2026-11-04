package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.library.trackDisplayTitle
import com.legitimateapps.dulcet.library.canBeQueued
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import com.legitimateapps.dulcet.library.LibraryCategories
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.library.rememberLibraryCategory
import com.legitimateapps.dulcet.library.AlbumSort
import com.legitimateapps.dulcet.library.rememberAlbumSort
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.legitimateapps.dulcet.core.AndroidQueueInsertion
import com.legitimateapps.dulcet.library.ArtistPlayResult
import com.legitimateapps.dulcet.library.LibraryHomeRowSurface
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibrarySubject
import com.legitimateapps.dulcet.library.LibrarySurface
import com.legitimateapps.dulcet.library.hasConnectionNotice
import com.legitimateapps.dulcet.library.MyLibraryMembership
import com.legitimateapps.dulcet.library.MyLibrarySession
import com.legitimateapps.dulcet.library.membershipOf
import com.legitimateapps.dulcet.library.toggleMembership
import com.legitimateapps.dulcet.library.connectionLine
import com.legitimateapps.dulcet.library.coverageLine
import com.legitimateapps.dulcet.library.growsAtEnd
import com.legitimateapps.dulcet.library.discardedChangesLine
import com.legitimateapps.dulcet.library.displayTitle
import com.legitimateapps.dulcet.library.favouriteTarget
import com.legitimateapps.dulcet.library.isFavourite
import com.legitimateapps.dulcet.library.rememberOutcomeLines
import com.legitimateapps.dulcet.library.savedAccountLine
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.noEpochLine
import com.legitimateapps.dulcet.library.offersRetry
import com.legitimateapps.dulcet.library.orderLine
import com.legitimateapps.dulcet.library.playableTracks
import com.legitimateapps.dulcet.library.rememberHomeRows
import com.legitimateapps.dulcet.library.rememberSurface
import com.legitimateapps.dulcet.library.titleResource
import com.legitimateapps.dulcet.library.unavailableLine
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.RatingStars
import com.legitimateapps.dulcet.ui.rememberArtwork
import com.legitimateapps.dulcet.ui.ArtworkEntity

/*
 * The phone's library over the reader (spec §16.14). Every screen opens the windows it shows while it
 * is composed and closes them when it goes, so the reader's "visible screen" is what is on screen.
 * Freshness, coverage, playability and a pending favourite arrive in the core's publications; this
 * file draws them with the shared words in `LibraryCopy.kt`, the same words the TV app uses.
 */

/** The library's views, in the Apple shells' section order where both have one. */
private enum class LibraryView { Home, Albums, Artists, Songs, Downloaded, Genres, Favourites, Playlists }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryHome(
    account: SearchAccount,
    session: LibrarySession,
    actions: PhoneActions,
    playingRawId: String? = null,
    title: Int = R.string.tab_library,
    myLibrary: MyLibrarySession? = null,
    wishlist: (@Composable () -> Unit)? = null,
) {
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val navigation = if (actions.openRoute !== NoPhoneRoute) actions else actions.copy(openRoute = { route = it }, back = { route = null })
    androidx.activity.compose.BackHandler(route != null) { route = null }
    route?.let {
        LibraryRoutePage(it, account, session, navigation, playingRawId, myLibrary)
        return
    }
    val observation by session.observation.collectAsState()
    val browse = title != R.string.tab_library
    val rows = if (browse) rememberHomeRows(session) else emptyList()
    val discovery by session.discoverWishlist.publication.collectAsState()
    LibraryRootFrame(stringResource(title), "library.refresh", session::refresh, Modifier.semantics { this[LibraryObservation] = observation }) {
        // Above the list, not its first item: see MyLibraryHome.
        Column(Modifier.fillMaxSize()) {
        ConnectionNotices(session, accountNotices = !browse)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library.home").semantics { this[LibraryObservation] = observation },
            contentPadding = PaddingValues(bottom = 24.dp)) {
            for (id in LibraryCategories.rootIds(browse, personalLibrary = false)) when (id) {
                "playlists" -> item { LibraryRootRow(stringResource(R.string.library_view_playlists), DulcetIcons.QueueMusic, "library.view.playlists") { navigation.openRoute(id) } }
                "favourites" -> item { LibraryRootRow(stringResource(R.string.library_favorites_title), DulcetIcons.Favourite, "library.view.favourites") { navigation.openRoute(id) } }
                "downloaded" -> item { LibraryRootRow(stringResource(R.string.library_downloads_title), DulcetIcons.Download, "library.view.downloaded") { navigation.openRoute(id) } }
                "wishlist" -> if (wishlist != null) item { wishlist() }
                "pinned" -> item { LibraryRootRow(stringResource(SharedR.string.library_pinned), LibraryRowIcons.PushPin, "library.view.pinned") { navigation.openRoute(id) } }
                "mostPlayed" -> item { LibraryRootRow(stringResource(SharedR.string.library_home_frequent), LibraryRowIcons.TrendingUp, "library.view.mostPlayed") { navigation.openRoute(id) } }
                "discover" -> if (discovery?.access?.discoverAvailable == true) item { LibraryRootRow(stringResource(R.string.library_discover_entry), LibraryRowIcons.Explore, "discover.destination") { navigation.openRoute(id) } }
                "artists" -> item { LibraryRootRow(stringResource(R.string.library_view_artists), DulcetIcons.Person, "library.view.artists") { navigation.openRoute(id) } }
                "albums" -> item { LibraryRootRow(stringResource(R.string.library_view_albums), DulcetIcons.Album, "library.view.albums") { navigation.openRoute(id) } }
                "songs" -> item { LibraryRootRow(stringResource(SharedR.string.search_group_songs), DulcetIcons.MusicNote, "library.view.songs") { navigation.openRoute("songsBrowse") } }
                "genres" -> item { LibraryRootRow(stringResource(SharedR.string.library_genres), LibraryRowIcons.Label, "library.view.genres") { navigation.openRoute(id) } }
            }
            // As the Apple app's phone Library: the two rankings are rows to their full lists, then
            // what was added recently is the one shelf. The history itself lives in Listen Now.
            if (browse) {
                item { LibraryRootRow(stringResource(SharedR.string.library_home_recent), LibraryRowIcons.History, "library.view.recentlyPlayed") { navigation.openRoute("recentlyPlayed") } }
                item { LibraryRootRow(stringResource(SharedR.string.library_home_frequent), LibraryRowIcons.TrendingUp, "library.view.mostPlayed") { navigation.openRoute("mostPlayed") } }
                rows.firstOrNull()?.let { row ->
                    item(key = "browse-shelf:0") { RecentlyAddedStrip(account, row, navigation, myLibrary, "library.home.0") }
                }
            }
        }
        }
    }
}

// ---- Home -----------------------------------------------------------------------------------------------

/** N independent rows (§16.9): each paints, fails and recovers on its own, with its own freshness. */
@Composable
private fun HomeRows(account: SearchAccount, session: LibrarySession, actions: PhoneActions, myLibrary: MyLibrarySession? = null) {
    val rows = rememberHomeRows(session)
    val pins = rememberPinnedItems(session)
    val list = rememberLazyListState()
    val hasPins = pins.isNotEmpty()
    // A header that arrives after the first layout belongs at the top. Metadata refreshes and
    // changes within an existing pins row must retain the user's scroll position.
    LaunchedEffect(hasPins) { if (hasPins) list.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("library.home"), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (hasPins) item(key = "pins") { LibraryPins(account, session, actions, pins) }
        itemsIndexed(rows, key = { _, row -> row.row.titleResource() }) { index, row -> HomeRow(account, index, row, session, actions, myLibrary) }
    }
}

@Composable
private fun HomeRow(account: SearchAccount, index: Int, row: LibraryHomeRowSurface, session: LibrarySession, actions: PhoneActions,
                    myLibrary: MyLibrarySession? = null) {
    val publication by row.surface.state.collectAsState()
    val connection by session.connection.collectAsState()
    val connectionNotice = connection.hasConnectionNotice()
    val resources = libraryResources()
    Column(Modifier.fillMaxWidth().testTag("library.home.$index").padding(top = 20.dp)) {
        Text(resources.getString(row.row.titleResource()), Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val current = publication ?: run { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        FreshnessLine(current.freshness, "library.home.$index", session::retry, includeReason = !connectionNotice)
        when (val freshness = current.freshness) {
            AndroidLibraryFreshness.Loading -> RowPlaceholder(Modifier.testTag("library.home.$index.loading"))
            is AndroidLibraryFreshness.Unavailable -> if (!connectionNotice) StatementText(
                resources.unavailableLine(freshness.reason, LibrarySubject.List), "library.home.$index.unavailable")
            else -> if (current.items.isEmpty()) {
                StatementText(resources.getString(SharedR.string.library_empty_list), "library.home.$index.empty")
            } else {
                LazyRow(Modifier.testTag("library.home.$index.items"),
                    contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(current.items, key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                        val tag = "library.home.$index.item.$position"
                        AlbumCard(account, item, Modifier.width(140.dp).testTag(tag),
                            library = libraryAction(myLibrary, item, tag)) {
                            when (item) {
                                is AndroidLibraryItem.Album -> actions.openAlbum(item.rawId)
                                is AndroidLibraryItem.Artist -> actions.openArtist(item.rawId)
                                is AndroidLibraryItem.Track ->
                                    actions.playTracks(current.items, item.rawId, resources.getString(row.row.titleResource()))
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RowPlaceholder(modifier: Modifier, cardWidth: Dp = 140.dp) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(cardWidth).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest))
                Box(Modifier.fillMaxWidth(0.45f).height(10.dp).clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh))
            }
        }
    }
}

// ---- Albums and artists ---------------------------------------------------------------------------------

/**
 * Every album, in the order the person chose ([AlbumSort], remembered on this device), windowed
 * (§16.12): the viewport is reported, pages extend it. A different order is a different window, read
 * from its start, and the grid returns to its top.
 */
@Composable
internal fun AlbumsGrid(account: SearchAccount, session: LibrarySession, actions: PhoneActions, myLibrary: MyLibrarySession? = null) {
    val depth by session.depth.collectAsState()
    if (depth == null) { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("library.albums.loading")); return }
    val filters = rememberLibraryCategory(session, "albums")
    val sort = rememberAlbumSort(session)
    val order = sort.value
    val surface = rememberSurface(session, "albums:$order") { openAlbums(order) }
    val publication by surface.state.collectAsState()
    val grid = rememberLazyGridState()
    // A different order is a different list: it starts at its top.
    var shownOrder by rememberSaveable { mutableStateOf(order) }
    LaunchedEffect(order) {
        if (shownOrder != order) {
            shownOrder = order
            grid.scrollToItem(0)
        }
    }
    val current = publication
    ReportViewport(surface, grid, current)
    LazyVerticalGrid(if (LocalDensity.current.fontScale >= 1.5f) GridCells.Fixed(1) else GridCells.Adaptive(156.dp), Modifier.fillMaxSize().testTag("library.albums"), state = grid,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.bleedKeyline()) {
                LibraryCategoryControls(filters, showSort = false, sortControl = { AlbumSortControl(order, sort::choose) })
                if (current != null) {
                    LibraryListStatus(current, "library.albums", session::retry, filters)
                    if (current.growsAtEnd()) TextButton(onClick = surface::loadMore, modifier = Modifier.padding(start = 4.dp).testTag("library.albums.loadMore")) {
                        Text(stringResource(SharedR.string.library_load_more))
                    }
                }
            }
        }
        itemsIndexed(current?.items.orEmpty(), key = { _, item -> item.rawId }) { position, item ->
            val tag = "library.albums.item.$position"
            if (LocalDensity.current.fontScale >= 1.5f && item is AndroidLibraryItem.Album) {
                RankedAlbumRow(account, item, null, Modifier.testTag(tag)) { actions.openAlbum(item.rawId) }
            } else AlbumCard(account, item, Modifier.testTag(tag), library = libraryAction(myLibrary, item, tag)) { actions.openAlbum(item.rawId) }
        }
    }
}

/** "Sort by", with the order showing and a menu of the others: the Apple shells' picker. */
@Composable
private fun AlbumSortControl(order: AndroidAlbumListType, choose: (AndroidAlbumListType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
            AssistChip(onClick = { open = true }, modifier = Modifier.testTag("library.albums.sort"),
                label = { Text(stringResource(AlbumSort.titleResource(order)), maxLines = 1, softWrap = false) },
                trailingIcon = { Icon(DulcetIcons.ExpandMore, null, Modifier.size(20.dp)) })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (choice in AlbumSort.CHOICES) DropdownMenuItem(
                    text = { Text(stringResource(AlbumSort.titleResource(choice))) },
                    leadingIcon = { if (choice == order) Icon(DulcetIcons.Check, null) else Spacer(Modifier.size(24.dp)) },
                    onClick = { open = false; choose(choice) },
                    modifier = Modifier.testTag("library.albums.sort.${AlbumSort.tag(choice)}").semantics { selected = choice == order })
            }
    }
}

/**
 * Tells the window what is on screen — which the reader rebases and looks ahead around — and asks
 * for the next page near the end. Indexes are into the publication drawn; the facade carries them
 * to newer ones by identity. The reader decides whether a page is read at all (never offline).
 */
@Composable
private fun ReportViewport(surface: LibrarySurface, grid: LazyGridState, publication: AndroidLibraryPublication?) {
    ReportViewport(surface, publication) {
        // Album cells are keyed by their id; the status line above them is not.
        val visible = grid.layoutInfo.visibleItemsInfo.filter { it.key is String }
        visible.firstOrNull()?.index to visible.lastOrNull()?.index
    }
}

/** A list's viewport, as [ReportViewport] for a grid: rows keyed by a string, after one header item. */
@Composable
internal fun ReportListViewport(surface: LibrarySurface, list: LazyListState, publication: AndroidLibraryPublication?) {
    ReportViewport(surface, publication) {
        val visible = list.layoutInfo.visibleItemsInfo.filter { it.key is String }
        visible.firstOrNull()?.index to visible.lastOrNull()?.index
    }
}

@Composable
private fun ReportViewport(surface: LibrarySurface, publication: AndroidLibraryPublication?, visible: () -> Pair<Int?, Int?>) {
    val latest by rememberUpdatedState(publication)
    LaunchedEffect(surface) {
        // Re-evaluated when the window changes shape as well as on every scroll: a window rebased or
        // given back to what fits the screen can show its end with nothing left to scroll, and must
        // still grow. Only the shape: a publication that changes freshness or coverage alone (a
        // failed or empty page) asks for nothing, or a failing page would be asked for in a loop.
        snapshotFlow { visible() to latest?.let { it.items.size to it.leadingOffset } }.collect { (range, _) ->
            val (first, last) = range
            val shown = latest
            if (shown == null || first == null || last == null) return@collect
            // Index 0 is the header; items start at 1.
            val from = (first - 1).coerceAtLeast(0)
            val to = (last - 1).coerceAtLeast(from)
            surface.setViewport(from, to)
            if (shown.growsAtEnd() && to >= shown.items.size - PAGE_AHEAD) surface.loadMore()
            if (shown.leadingOffset > 0 && from == 0) surface.loadBefore()
        }
    }
}

private const val PAGE_AHEAD = 12

@Composable
internal fun ArtistsList(session: LibrarySession, actions: PhoneActions, myLibrary: MyLibrarySession? = null) {
    val controls = rememberLibraryCategory(session, "artists")
    val surface = rememberSurface(session, "artists") { openArtists() }
    val publication by surface.state.collectAsState()
    val current = publication
    LazyColumn(Modifier.fillMaxSize().testTag("library.artists")) {
        item { LibraryCategoryControls(controls, sorts = listOf(
            "title" to stringResource(R.string.library_sort_artist_az),
            "titleDescending" to stringResource(R.string.library_sort_artist_za),
        )) }
        if (current != null) item { LibraryListStatus(current, "library.artists", session::retry, controls) }
        itemsIndexed(current?.items.orEmpty(), key = { _, item -> item.rawId }) { position, item ->
            if (item is AndroidLibraryItem.Artist) {
                val tag = "library.artists.item.$position"
                ArtistRow(item, Modifier.testTag(tag), library = libraryAction(myLibrary, item, tag)) {
                    actions.openArtist(item.rawId)
                }
            }
        }
    }
}

/** Every genre (`getGenres`, one response, §16.9). A genre opens its songs, as on Apple. */
@Composable
internal fun GenresList(session: LibrarySession, actions: PhoneActions) {
    val surface = rememberSurface(session, "genres") { openGenres() }
    val publication by surface.state.collectAsState()
    val current = publication
    Column(Modifier.fillMaxSize()) {
        // The status stands above the list: as a lazy first item it starts empty, which the list treats
        // as scrolled past, so a failure arriving later stayed above the viewport.
        if (current != null) ListStatus(current, "library.genres", session::retry)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("library.genres")) {
            itemsIndexed(current?.items.orEmpty(), key = { _, item -> item.rawId }) { position, item ->
                if (item is AndroidLibraryItem.Genre) ListItem(
                    headlineContent = { Text(item.displayTitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Icon(DulcetIcons.ChevronRight, null) },
                    modifier = Modifier.testTag("library.genres.item.$position").clickable { actions.openGenre(item.rawId) },
                )
            }
        }
    }
}

/**
 * The account's favourites (§16.9 `getStarred2`): songs, albums and artists, each with its heart.
 * A heart taken off here stays in the list, hollow, until the list is next read — so a slip is
 * undone where it happened; a favourite added elsewhere appears when this list is read again.
 */
@Composable
internal fun FavouritesList(account: SearchAccount, session: LibrarySession, actions: PhoneActions, playingRawId: String?,
                           myLibrary: MyLibrarySession? = null) {
    val surface = rememberSurface(session, "favourites") { openFavourites() }
    val publication by surface.state.collectAsState()
    val current = publication
    val resources = libraryResources()
    val items = current?.items.orEmpty()
    val tracks = items.filterIsInstance<AndroidLibraryItem.Track>()
    val albums = items.filterIsInstance<AndroidLibraryItem.Album>()
    val artists = items.filterIsInstance<AndroidLibraryItem.Artist>()
    val outcomeLines = rememberOutcomeLines(session, items.mapNotNull { it.favouriteTarget() })
    var note by remember { mutableStateOf<String?>(null) }
    val title = resources.getString(SharedR.string.library_home_favourites)
    LazyColumn(Modifier.fillMaxSize().testTag("library.favourites")) {
        if (current != null) item {
            // An empty list says how to fill it, not the generic "nothing here".
            if (current.items.isEmpty() && current.itemsState == AndroidLibraryItemsState.Present &&
                current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading) {
                FreshnessLine(current.freshness, "library.favourites", session::retry)
                EmptyState(DulcetIcons.FavouriteBorder, "No favorites yet", body = "Music you favorite appears here.",
                    actionLabel = stringResource(SharedR.string.library_browse), onAction = { actions.openRoute("browse") }, actionTag = "library.favourites.browse", modifier = Modifier.testTag("library.favourites.empty"))
            } else {
                ListStatus(current, "library.favourites", session::retry)
            }
            OutcomeLines(outcomeLines, null, "library.favourites.outcome")
            note?.let { StatementText(it, "library.favourites.note") }
        }
        if (tracks.isNotEmpty()) {
            item { SectionHeading(SharedR.string.library_favourites_songs, "library.favourites.songs") }
            itemsIndexed(tracks, key = { _, track -> "track:" + track.rawId }) { position, track ->
                TrackRow(track, position + 1, track.rawId == playingRawId, Modifier.testTag("library.favourites.track.$position"),
                    artwork = { Artwork(account, track.artworkKey, track.title.orEmpty(), 56.dp, entity = ArtworkEntity.Song) },
                    onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                    onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)) },
                    favouriteTag = "library.favourites.track.$position.favourite",
                    onAddToPlaylist = { actions.addToPlaylist(PlaylistAddition.Songs(listOf(track.rawId), track.title.orEmpty())) },
                    onOpenAlbum = track.albumRawId?.let { id -> { actions.openAlbum(id) } },
                    onOpenArtist = track.artistRawId?.let { id -> { actions.openArtist(id) } },
                    queue = actions.trackQueue(track, null),
                    onRate = { session.setRating(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId), it) },
                    library = trackLibraryAction(myLibrary, track)) {
                    note = null
                    actions.playTracks(tracks, track.rawId, title)
                }
            }
        }
        if (albums.isNotEmpty()) {
            item { SectionHeading(SharedR.string.library_favourites_albums, "library.favourites.albums") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(albums, key = { _, album -> album.rawId }) { position, album ->
                        Box(Modifier.width(140.dp)) {
                            AlbumCard(account, album, Modifier.fillMaxWidth().testTag("library.favourites.album.$position")) {
                                actions.openAlbum(album.rawId)
                            }
                            // A badge, so the heart reads on the artwork rather than hanging off its corner.
                            FavouriteButton(album.isFavourite(), "library.favourites.album.$position.favourite",
                                Modifier.align(Alignment.TopEnd).padding(6.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape)) {
                                session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId))
                            }
                        }
                    }
                }
            }
        }
        if (artists.isNotEmpty()) {
            item { SectionHeading(SharedR.string.library_favourites_artists, "library.favourites.artists") }
            itemsIndexed(artists, key = { _, artist -> "artist:" + artist.rawId }) { position, artist ->
                ArtistRow(artist, Modifier.testTag("library.favourites.artist.$position"),
                    onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Artist, artist.rawId)) },
                    favouriteTag = "library.favourites.artist.$position.favourite") { actions.openArtist(artist.rawId) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun SectionHeading(text: Int, tag: String) {
    Text(stringResource(text), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).testTag(tag),
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

/**
 * A list's freshness, coverage and order lines, or the statement shown instead of it. A detail's
 * list (an artist's albums) can be loading or unavailable under a cached header: that is said, never
 * "Nothing here yet".
 */
@Composable
internal fun ListStatus(publication: AndroidLibraryPublication, tag: String, retry: () -> Unit, local: Boolean = false) {
    val resources = libraryResources()
    val freshness = publication.freshness
    val cachedFailure = cachedListFailure(freshness)
    when {
        cachedFailure != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(resources.unavailableLine(cachedFailure, LibrarySubject.List), Modifier.weight(1f).testTag("$tag.freshness"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = retry, modifier = Modifier.testTag("$tag.retry")) { Text(stringResource(SharedR.string.library_try_again)) }
        }
        freshness is AndroidLibraryFreshness.Unavailable && publication.items.isEmpty() ->
            StatementText(resources.unavailableLine(freshness.reason, LibrarySubject.List), "$tag.unavailable")
        publication.itemsState == AndroidLibraryItemsState.Loading || freshness == AndroidLibraryFreshness.Loading ->
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("$tag.loading"))
        publication.itemsState == AndroidLibraryItemsState.Unavailable -> StatementText(resources.unavailableLine(
            publication.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List), "$tag.unavailable")
        publication.items.isEmpty() -> EmptyState(DulcetIcons.MusicNote, "Nothing here yet", modifier = Modifier.testTag("$tag.empty"))
    }
}

@Composable
internal fun LibraryListStatus(publication: AndroidLibraryPublication, tag: String, retry: () -> Unit,
    controls: com.legitimateapps.dulcet.library.LibraryCategoryState) {
    if (cachedListFailure(publication.freshness) == null && publication.items.isEmpty() && publication.itemsState == AndroidLibraryItemsState.Present &&
        publication.freshness !is AndroidLibraryFreshness.Unavailable && publication.freshness != AndroidLibraryFreshness.Loading) {
        val filter = controls.filter.ifBlank { "all" }
        val category = controls.category.substringAfterLast('.').let { if (it == "downloaded") "downloads" else it }
        val icon = when (category) {
            "albums" -> DulcetIcons.Album
            "artists" -> DulcetIcons.Person
            "downloads" -> DulcetIcons.Download
            "playlists" -> DulcetIcons.QueueMusic
            else -> DulcetIcons.MusicNote
        }
        EmptyState(icon,
            if (filter == "favorites") "No favorite ${category.lowercase()}" else if (filter == "downloaded") "No downloaded ${category.lowercase()}" else "No ${category.lowercase()} yet",
            body = if (filter == "all") null else "Try another filter to see your music.",
            actionLabel = if (filter == "all") null else "Show all", onAction = { controls.filterChoice("all") }, actionTag = "$tag.showAll",
            modifier = Modifier.testTag("$tag.empty"))
    } else ListStatus(publication, tag, retry)
}

private fun cachedListFailure(freshness: AndroidLibraryFreshness): AndroidLibraryUnavailableReason? =
    when (val reason = (freshness as? AndroidLibraryFreshness.Cached)?.reason) {
        is com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.Failed -> AndroidLibraryUnavailableReason.Failed(reason.error)
        com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.InternalFailure -> AndroidLibraryUnavailableReason.InternalFailure
        else -> null
    }

/**
 * One connection statement above the home, with manual Reconnect as a fallback.
 */
@Composable
internal fun SavedAccountNotice(session: LibrarySession, account: SearchAccount) {
    val connection by session.connection.collectAsState()
    val resources = libraryResources()
    val line = resources.savedAccountLine(connection, account) ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(line, Modifier.weight(1f).testTag("library.saved"), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = session::connectSavedAccount, modifier = Modifier.testTag("library.reconnect")) {
            Text(resources.getString(SharedR.string.library_reconnect))
        }
    }
}

/**
 * What is true of the connection rather than of one screen: why the last reconnect failed, with
 * "Try again"; and, on the library's first screen only ([accountNotices]), the two account-level
 * statements that are made once rather than on every list (§16.12, §16.10).
 */
@Composable
internal fun ConnectionNotices(session: LibrarySession, accountNotices: Boolean, local: Boolean = false) {
    val depth by session.depth.collectAsState()
    if (depth?.errorKind != null) StatementText(stringResource(SharedR.string.library_depth_save_failed), "library.depth.error")
    if (local) return
    val connection by session.connection.collectAsState()
    val discarded by session.discardedChanges.collectAsState()
    val resources = libraryResources()
    resources.connectionLine(connection)?.let { line ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, Modifier.weight(1f).testTag("library.connection"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = session::retry, modifier = Modifier.testTag("library.connection.retry")) {
                Text(resources.getString(SharedR.string.library_try_again))
            }
        }
    }
    if (!accountNotices) return
    resources.discardedChangesLine(discarded)?.let { line ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, Modifier.weight(1f).testTag("library.discarded"), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = session::dismissDiscardedChanges) {
                Text(resources.getString(SharedR.string.library_dismiss))
            }
        }
    }
}

// ---- Album --------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumScreen(
    account: SearchAccount,
    session: LibrarySession,
    rawId: String,
    playingRawId: String?,
    actions: PhoneActions,
    downloadRequests: DownloadRequests? = null,
    myLibrary: MyLibrarySession? = null,
) {
    val surface = rememberSurface(session, "album:$rawId") { openAlbum(rawId) }
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
    var rateOpen by remember(rawId) { mutableStateOf(false) }
    val resources = libraryResources()
    LaunchedEffect(publication) { publication?.let(actions.rememberAlbum) }
    val list = rememberLazyListState()
    val collapsed = detailHeaderCollapsed(list)
    val headerAlbum = publication?.header as? AndroidLibraryItem.Album
    val songCount = headerAlbum?.songCount ?: publication?.takeIf { it.itemsState == AndroidLibraryItemsState.Present }
        ?.items?.count { it is AndroidLibraryItem.Track }
    Column(Modifier.fillMaxSize().testTag("album.surface").semantics { this[LibraryObservation] = observation }) {
        TopAppBar(title = { DetailAppBarTitle(headerAlbum?.title,
            songCount?.let { pluralStringResource(R.plurals.album_song_count, it, it) }, collapsed, "album") },
            expandedHeight = detailAppBarHeight(collapsed), navigationIcon = {
            IconButton(onClick = actions.back, modifier = Modifier.testTag("album.back")) {
                Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back))
            }
        }, actions = {
            val album = publication?.header as? AndroidLibraryItem.Album
            val shown = publication
            if (album != null) FavouriteButton(album.favourite == true, "album.favourite") {
                session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId))
            }
            if (album != null && shown != null) {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }, modifier = Modifier.testTag("album.menu")) {
                    Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
                }
                if (open) PhoneModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("album.actions.sheet").semantics { dismiss { open = false; true } }) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    ListItem(headlineContent = { Text(album.title) }, supportingContent = { Text(album.artistName.orEmpty()) },
                        leadingContent = { Artwork(account, album.artworkKey, album.title, 56.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    Spacer(Modifier.height(8.dp))
                    if (actions.queueEditing && shown.playableTracks(account.providerInstanceId).isNotEmpty()) {
                        QueueInsertionItems("album", { open = false },
                            { actions.queueAlbum(shown, AndroidQueueInsertion.PlayNext) },
                            { actions.queueAlbum(shown, AndroidQueueInsertion.AddToQueue) })
                    }
                    if (shown.itemsState == AndroidLibraryItemsState.Present) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_add_to)) },
                            leadingIcon = { Icon(DulcetIcons.PlaylistAdd, null) },
                            onClick = { open = false; actions.addToPlaylist(PlaylistAddition.Album(album.rawId, album.title,
                                shown.items.filterIsInstance<AndroidLibraryItem.Track>().map { it.rawId })) }, modifier = Modifier.testTag("album.addToPlaylist"))
                    }
                    HorizontalDivider()
                    val albumTracks = shown.items.filterIsInstance<AndroidLibraryItem.Track>()
                    val state = AlbumDownloads.of(albumTracks, downloads)
                    if (state.anyRequested || state.downloadable > 0) DropdownMenuItem(
                        text = { Text(stringResource(if (state.anyRequested) SharedR.string.download_album_remove else SharedR.string.download_album)) },
                        leadingIcon = { Icon(if (state.anyRequested) DulcetIcons.DownloadDone else DulcetIcons.Download, null) },
                        onClick = { open = false; if (state.anyRequested) requests.remove(albumTracks.map { it.rawId }.filter { it in downloads }) else requests.download(albumTracks) },
                        modifier = Modifier.testTag(if (state.anyRequested) "album.download.remove" else "album.download"))
                    DropdownMenuItem(
                        text = { Text(stringResource(SharedR.string.library_rating_menu)) },
                        leadingIcon = { Icon(if ((album.rating ?: 0) > 0) DulcetIcons.Star else DulcetIcons.StarBorder, null) },
                        onClick = { open = false; rateOpen = true },
                        modifier = Modifier.testTag("album.rate"),
                    )
                    val depth by session.depth.collectAsState()
                    val pinned = depth?.let { session.isPinned(album) } == true
                    DropdownMenuItem(text = { Text(stringResource(if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin)) },
                        leadingIcon = { Icon(LibraryRowIcons.PushPin, null) }, onClick = { open = false; session.togglePin(album) }, modifier = Modifier.testTag("album.pin"))
                    val membership = membershipOf(myLibrary, AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId))
                    if (membership != null && myLibrary != null) DropdownMenuItem(
                        text = { Text(stringResource(if (membership == MyLibraryMembership.Added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library)) },
                        leadingIcon = { Icon(if (membership == MyLibraryMembership.Added) DulcetIcons.Check else DulcetIcons.Add, null) },
                        onClick = { myLibrary.toggleMembership(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId), membership) },
                        modifier = Modifier.testTag("album.library").semantics { selected = membership == MyLibraryMembership.Added })
                    album.artistRawId?.let { id -> DropdownMenuItem(text = { Text(stringResource(R.string.library_go_to_artist)) },
                        leadingIcon = { Icon(DulcetIcons.Person, null) }, onClick = { open = false; actions.openArtist(id) }, modifier = Modifier.testTag("album.artist")) }
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_refresh)) }, leadingIcon = { Icon(DulcetIcons.Refresh, null) },
                        onClick = { open = false; session.refresh() }, modifier = Modifier.testTag("album.refresh"))
                    Spacer(Modifier.height(24.dp))
                    }
                }
            }
            if (rateOpen && album != null) RatingDialog("album", TrackRating(album.rating) {
                session.setRating(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId), it)
            }) { rateOpen = false }
        })
        ConnectionNotices(session, accountNotices = false)
        val current = publication ?: run { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        val album = current.header as? AndroidLibraryItem.Album
        val tracks = current.items.filterIsInstance<AndroidLibraryItem.Track>()
        val anyPlayable = current.playableTracks(account.providerInstanceId).isNotEmpty()
        val largeText = LocalDensity.current.fontScale >= 1.5f
        PullToRefreshBox(isRefreshing = current.freshness == AndroidLibraryFreshness.Loading, onRefresh = session::refresh, modifier = Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize().testTag("album.tracks"), state = list) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {

                    OutcomeLines(outcomeLines, target, "album.outcome")
                    note?.let { StatementText(it, "album.note") }
                    (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                        StatementText(resources.unavailableLine(unavailable.reason, LibrarySubject.Album), "album.unavailable")
                    }
                    if (album != null) {
                        val songs = album.songCount ?: tracks.size.takeIf { current.itemsState == AndroidLibraryItemsState.Present }
                        LibraryDetailHeader(
                            title = album.title,
                            artwork = {
                                Artwork(account, album.artworkKey, album.title, null,
                                    Modifier.fillMaxWidth(if (largeText) 0.6f else 0.72f).aspectRatio(1f))
                            },
                            titleTag = "album.title",
                            subtitle = album.artistName,
                            onSubtitle = album.artistRawId?.let { id -> { actions.openArtist(id) } },
                            metadata = listOfNotNull(album.year?.toString(),
                                songs?.let { pluralStringResource(R.plurals.album_song_count, it, it) },
                                album.durationMilliseconds?.takeIf { it > 0 }?.let { longDuration(it) }).metadataLine(),
                            status = {
                                LocalContext.current.resources.albumDownloadLine(AlbumDownloads.of(tracks, downloads))?.let { downloadStatus ->
                                    Text(downloadStatus, Modifier.testTag("album.download.status"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center)
                                }
                            },
                        ) {
                            PlayShuffleButtons(
                                onPlay = { actions.playAlbum(current, 0, false) },
                                onShuffle = { actions.playAlbum(current, 0, true) },
                                enabled = anyPlayable, tagPrefix = "album")
                        }
                    }
                }
            }
            when (current.itemsState) {
                AndroidLibraryItemsState.Loading -> item { RowPlaceholder(Modifier.testTag("album.tracks.loading")) }
                // The header is known and the track list cannot be shown: the core says why (§16.14).
                AndroidLibraryItemsState.Unavailable -> if (album != null) item {
                    StatementText(resources.unavailableLine(
                        current.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure,
                        LibrarySubject.Album,
                    ), "album.tracks.unavailable")
                }
                AndroidLibraryItemsState.Present -> itemsIndexed(current.items, key = { position, item -> item.rawId + "#" + position }) { position, item ->
                    if (item is AndroidLibraryItem.Track) {
                        TrackRow(item, position + 1, item.rawId == playingRawId, Modifier.testTag("album.track.$position"),

                            albumArtist = album?.artistName,
                            compactActions = true,
                            onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                            onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, item.rawId)) },
                            favouriteTag = "album.track.$position.favourite",
                            onAddToPlaylist = { actions.addToPlaylist(PlaylistAddition.Songs(listOf(item.rawId), item.title.orEmpty())) },
                            onOpenAlbum = album?.rawId?.let { id -> { actions.openAlbum(id) } },
                            onOpenArtist = item.artistRawId?.let { id -> { actions.openArtist(id) } },
                            download = TrackDownload(downloads[item.rawId],
                                onDownload = { requests.download(listOf(item)) }.takeIf { item.downloadItem() != null },
                                onRemove = { requests.remove(listOf(item.rawId)) }),
                            queue = actions.trackQueue(item, album),
                            onRate = { session.setRating(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, item.rawId), it) },
                            library = trackLibraryAction(myLibrary, item)) {
                            note = null
                            actions.playAlbum(current, position, false)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        }
    }
}

/**
 * The heart. Filled is a favourite — with any change made here already in it, sent or not: offline, a
 * favourite is a favourite and is labelled nowhere (§16.20); only an outcome that needs words is said,
 * by [OutcomeLines]. Its description is the action, and its state is `selected`.
 */
@Composable
internal fun FavouriteButton(favourite: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val resources = libraryResources()
    IconButton(onClick = onClick, modifier = modifier.testTag(tag).semantics { selected = favourite }) {
        Icon(if (favourite) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder,
            resources.getString(if (favourite) SharedR.string.library_favourite_remove else SharedR.string.library_favourite_add),
            tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The outcome lines of a screen's hearts: [primary]'s under [tag], any other's under `tag.<kind>.<id>`,
 * so a test can tell which entity a line is about.
 */
@Composable
internal fun OutcomeLines(lines: List<Pair<AndroidLibraryEntity, String>>, primary: AndroidLibraryEntity?, tag: String) {
    for ((target, line) in lines) {
        StatementText(line, if (target == primary) tag else "$tag.${target.kind.name.lowercase()}.${target.rawId}")
    }
}

// ---- Artist -------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistScreen(account: SearchAccount, session: LibrarySession, rawId: String, actions: PhoneActions,
                          myLibrary: MyLibrarySession? = null) {
    val surface = rememberSurface(session, "artist:$rawId") { openArtist(rawId) }
    val publication by surface.state.collectAsState()
    val topSurface = rememberSurface(session, "artistTopSongs:$rawId") { openArtistTopSongs(rawId) }
    val topSongs by topSurface.state.collectAsState()
    val current = publication
    val artist = current?.header as? AndroidLibraryItem.Artist
    val albums = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Album>()
    val listed = current?.itemsState == AndroidLibraryItemsState.Present
    val grid = rememberLazyGridState()
    val collapsed = detailHeaderCollapsed(grid)
    val resources = libraryResources()
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Artist, rawId)
    val outcomeLines = rememberOutcomeLines(session, listOf(target))
    // Playing an artist opens each album first; leaving the screen abandons that, so nothing starts
    // playing after the person has gone, and a second tap while it runs does nothing.
    var collecting by remember(rawId) { mutableStateOf<AutoCloseable?>(null) }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    DisposableEffect(rawId) { onDispose { collecting?.close() } }
    fun play(publication: AndroidLibraryPublication, shuffle: Boolean) {
        if (collecting != null) return
        note = null
        var finished = false
        val handle = actions.playArtist(publication, shuffle) { result ->
            finished = true
            collecting = null
            note = when (result) {
                ArtistPlayResult.Played -> null
                // Some album holds tracks this device could play once it reconnects.
                ArtistPlayResult.NeedsConnection -> resources.getString(SharedR.string.library_plays_on_reconnect)
                ArtistPlayResult.NothingPlayable -> resources.getString(SharedR.string.library_artist_nothing_playable)
            }
        }
        if (!finished) collecting = handle
    }
    Column(Modifier.fillMaxSize().testTag("artist.surface")) {
        TopAppBar(title = { DetailAppBarTitle(artist?.name,
            if (listed) pluralStringResource(R.plurals.library_album_count, albums.size, albums.size) else null,
            collapsed, "artist") }, expandedHeight = detailAppBarHeight(collapsed),
            navigationIcon = {
                IconButton(onClick = actions.back) { Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back)) }
            }, actions = {
                if (artist != null) {
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { more = true }, modifier = Modifier.testTag("artist.menu")) { Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu)) }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(text = { Text(stringResource(if (session.isPinned(artist)) SharedR.string.library_unpin else SharedR.string.library_pin)) },
                                leadingIcon = { Icon(LibraryRowIcons.PushPin, null) }, onClick = { more = false; session.togglePin(artist) }, modifier = Modifier.testTag("artist.pin"))
                        }
                    }
                }
                if (artist != null) {
                    val membership = membershipOf(myLibrary, target)
                    // The + / checkmark of the artist's membership, beside its heart as Apple Music has it.
                    if (membership != null && myLibrary != null) MembershipButton(membership, "artist.library") {
                        myLibrary.toggleMembership(target, membership)
                    }
                }
                if (artist != null) FavouriteButton(artist.favourite == true, "artist.favourite") {
                    session.toggleFavourite(target)
                }
            })
        ConnectionNotices(session, accountNotices = false)
        if (current == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        LazyVerticalGrid(GridCells.Adaptive(156.dp), Modifier.fillMaxSize().testTag("artist.albums"),
            state = grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.bleedKeyline().fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ListStatus(current, "artist", session::retry)
                    if (artist != null) {
                        LibraryDetailHeader(
                            title = artist.name,
                            titleTag = "artist.title",
                            artwork = { Monogram(artist.name, 120.dp) },
                            metadata = if (listed) pluralStringResource(R.plurals.library_album_count, albums.size, albums.size) else null,
                            artworkSpacing = 12.dp,
                        ) {
                            // Whether anything can play is the tracks' playability, known once each album is
                            // open; if nothing can, the tap says so.
                            PlayShuffleButtons(onPlay = { if (albums.isNotEmpty()) play(current, false) else actions.playTracksFrom(topSongs?.items.orEmpty(), null, artist.name, false) }, onShuffle = { if (albums.isNotEmpty()) play(current, true) else actions.playTracksFrom(topSongs?.items.orEmpty(), null, artist.name, true) },
                                enabled = collecting == null && (albums.isNotEmpty() || topSongs?.items.orEmpty().any { it is AndroidLibraryItem.Track && it.canBeQueued() }), tagPrefix = "artist")
                        }
                        note?.let { StatementText(it, "artist.note") }
                        OutcomeLines(outcomeLines, target, "artist.outcome")
                    }
                }
            }
            val top = topSongs?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
            if (top.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.bleedKeyline()) {
                    Text(stringResource(SharedR.string.library_top_songs), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                    top.take(5).forEachIndexed { index, track ->
                        TrackRow(track, index + 1, false, Modifier.fillMaxWidth().testTag("artist.topSongs.$index"), artwork = { Artwork(account, track.artworkKey, track.title.orEmpty(), 56.dp, entity = ArtworkEntity.Song) }, onUnavailable = {}, rank = index + 1,
                            onOpenAlbum = track.albumRawId?.let { id -> { actions.openAlbum(id) } },
                            onOpenArtist = track.artistRawId?.let { id -> { actions.openArtist(id) } },
                            onClick = { actions.playTracks(top, track.rawId, artist?.name.orEmpty()) })
                    }
                }
            }
            for (group in listOf("albums", "epsSingles", "compilations", "appearsOn")) {
                val grouped = albums.withIndex().filter { it.value.discographyGroup == group }
                if (grouped.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(releaseGroupTitle(group)), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("artist.group.$group"))
                }
                itemsIndexed(grouped, key = { _, album -> album.value.rawId }) { _, indexed ->
                    val album = indexed.value
                    AlbumCard(account, album, Modifier.testTag("artist.album.${indexed.index}")) { actions.openAlbum(album.rawId) }
                }
            }
        }
    }
}

// ---- Genre --------------------------------------------------------------------------------------------

/**
 * One genre's songs (`getSongsByGenre`, windowed, §16.9), as the Apple shells show a genre: its name,
 * how many songs, Play and Shuffle, and the songs, each playing the genre's playable songs from itself.
 * A genre carries no artwork and no heart of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenreScreen(account: SearchAccount, session: LibrarySession, name: String, playingRawId: String?, actions: PhoneActions,
                         myLibrary: MyLibrarySession? = null) {
    val surface = rememberSurface(session, "genre:$name") { openGenre(name) }
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val current = publication
    val tracks = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
    val outcomeLines = rememberOutcomeLines(session, tracks.mapNotNull { it.favouriteTarget() })
    var note by remember(name) { mutableStateOf<String?>(null) }
    val resources = libraryResources()
    val list = rememberLazyListState()
    val collapsed = detailHeaderCollapsed(list)
    val count = current?.total ?: tracks.size.takeIf { current?.itemsState == AndroidLibraryItemsState.Present }
    ReportListViewport(surface, list, current)
    Column(Modifier.fillMaxSize().testTag("genre.surface").semantics { this[LibraryObservation] = observation }) {
        TopAppBar(title = { DetailAppBarTitle(name,
            count?.let { pluralStringResource(R.plurals.album_song_count, it, it) }, collapsed, "genre",
            alwaysShowTitle = true, titleTag = "genre.title") }, expandedHeight = detailAppBarHeight(collapsed), navigationIcon = {
            IconButton(onClick = actions.back, modifier = Modifier.testTag("genre.back")) {
                Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back))
            }
        }, actions = {
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.testTag("genre.menu")) { Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu)) }
                DropdownMenu(more, { more = false }) { DropdownMenuItem(text = { Text(stringResource(R.string.library_refresh)) }, leadingIcon = { Icon(DulcetIcons.Refresh, null) },
                    onClick = { more = false; session.refresh() }, modifier = Modifier.testTag("genre.refresh")) }
            }
        })
        ConnectionNotices(session, accountNotices = false)
        LazyColumn(Modifier.fillMaxSize().testTag("genre.tracks"), state = list) {
            // One header item, so a track's list index is its position plus one (ReportListViewport).
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (count != null && count > 0) Text(pluralStringResource(R.plurals.album_song_count, count, count),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("genre.count"))
                    Spacer(Modifier.height(12.dp))
                    val playable = current?.itemsState == AndroidLibraryItemsState.Present &&
                        tracks.any { it.canBeQueued() }
                    if (current != null && current.itemsState != AndroidLibraryItemsState.Loading) {
                        PlayShuffleButtons(
                            onPlay = { note = null; actions.playTracksFrom(tracks, null, name, false) },
                            onShuffle = { note = null; actions.playTracksFrom(tracks, null, name, true) },
                            enabled = playable, tagPrefix = "genre")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (current != null) ListStatus(current, "genre", session::retry)
                    OutcomeLines(outcomeLines, null, "genre.outcome")
                    note?.let { StatementText(it, "genre.note") }
                }
            }
            itemsIndexed(tracks, key = { _, track -> track.rawId }) { position, track ->
                TrackRow(track, position + 1, track.rawId == playingRawId, Modifier.testTag("genre.track.$position"),
                    artwork = { Artwork(account, track.artworkKey, track.title.orEmpty(), 56.dp, entity = ArtworkEntity.Song) },
                    onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                    onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)) },
                    favouriteTag = "genre.track.$position.favourite",
                    onAddToPlaylist = { actions.addToPlaylist(PlaylistAddition.Songs(listOf(track.rawId), track.title.orEmpty())) },
                    onOpenAlbum = track.albumRawId?.let { id -> { actions.openAlbum(id) } },
                    onOpenArtist = track.artistRawId?.let { id -> { actions.openArtist(id) } },
                    queue = actions.trackQueue(track, null),
                    library = trackLibraryAction(myLibrary, track)) {
                    note = null
                    actions.playTracksFrom(tracks, track.rawId, name, false)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---- Shared pieces --------------------------------------------------------------------------------------

/** The one freshness line (§16.14), with "Try again" where a reconnect can help. Null for live content. */
@Composable
internal fun FreshnessLine(freshness: AndroidLibraryFreshness, tag: String, retry: () -> Unit, includeReason: Boolean = true) {
    if (freshness == AndroidLibraryFreshness.Loading) LinearProgressIndicator(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("$tag.loading"))
}

@Composable
internal fun StatementText(text: String, tag: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * An Add to Library / Remove from Library offered on a card or row (My Library, spec §29.4): [added]
 * draws the check and offers Remove, anything else the + and Add; [tag] is the element's own tag, so
 * the menu item is `$tag.library`. Only ever non-null where the server advertises `dulcetLibrary`.
 */
internal class MembershipAction(val added: Boolean, val tag: String, val toggle: () -> Unit)

/** A track row's Add to Library / Remove from Library: the membership it shows, and the toggle. */
internal class TrackLibrary(val membership: com.legitimateapps.dulcet.library.MyLibraryMembership, val toggle: () -> Unit)

@Composable
internal fun AlbumCard(
    account: SearchAccount,
    item: AndroidLibraryItem,
    modifier: Modifier,
    library: MembershipAction? = null,
    onClick: () -> Unit,
) {
    val title = item.displayTitle()
    val (artwork, artworkEntity) = when (item) {
        is AndroidLibraryItem.Album -> item.artworkKey to ArtworkEntity.Album
        is AndroidLibraryItem.Artist -> item.artworkKey to ArtworkEntity.Artist
        is AndroidLibraryItem.Track -> item.artworkKey to ArtworkEntity.Song
        is AndroidLibraryItem.Playlist -> item.artworkKey to ArtworkEntity.Playlist
        else -> null to ArtworkEntity.Album
    }
    val subtitle = (item as? AndroidLibraryItem.Album)?.let { album ->
        listOfNotNull(album.artistName, album.originalReleaseDate?.take(4) ?: album.year?.toString()).metadataLine()
    }
    AlbumCardVisual(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        artwork = { Artwork(account, artwork, title, null, Modifier.fillMaxWidth().aspectRatio(1f), artworkEntity) },
        menuItems = library?.let { membership ->
            { closeMenu: () -> Unit ->
                DropdownMenuItem(
                    text = { Text(stringResource(if (membership.added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library)) },
                    leadingIcon = { Icon(if (membership.added) DulcetIcons.Check else DulcetIcons.Add, null) },
                    onClick = { closeMenu(); membership.toggle() },
                    modifier = Modifier.testTag(membership.tag + ".library"),
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
internal fun ArtistRow(
    item: AndroidLibraryItem.Artist,
    modifier: Modifier,
    onFavourite: (() -> Unit)? = null,
    favouriteTag: String? = null,
    library: MembershipAction? = null,
    onClick: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onFavourite != null || library != null
    Box {
        ListItem(
            headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val count = item.albumCount
                Text(if (count != null && count > 0) pluralStringResource(R.plurals.library_album_count, count, count)
                    else stringResource(R.string.library_artist_kind))
            },
            leadingContent = { Monogram(item.name, 48.dp) },
            trailingContent = if (hasMenu) ({ IconButton({ menuOpen = true }, Modifier.testTag((library?.tag ?: favouriteTag?.removeSuffix(".favourite") ?: "artist") + ".menu")) {
                Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
            } }) else null,
            modifier = modifier.let { base ->
                if (hasMenu) base.combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                else base.clickable(onClick = onClick)
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onFavourite != null) DropdownMenuItem(
                text = { Text(stringResource(if (item.favourite == true) R.string.library_favorite_remove else R.string.library_favorite_add)) },
                leadingIcon = { Icon(if (item.favourite == true) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder, null) },
                onClick = { menuOpen = false; onFavourite() }, modifier = Modifier.testTag(favouriteTag ?: "artist.favourite"),
            )
            if (library != null) DropdownMenuItem(
                text = { Text(stringResource(if (library.added) SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library)) },
                leadingIcon = { Icon(if (library.added) DulcetIcons.Check else DulcetIcons.Add, null) },
                onClick = { menuOpen = false; library.toggle() }, modifier = Modifier.testTag(library.tag + ".library"),
            )
        }
    }
}

/**
 * One track. A track this device cannot play offline says so in the row, and a tap on it says why it
 * will not play ([onUnavailable]) instead of doing nothing (§16.14) — the statement comes from the
 * core's playability, never from a guess here.
 */
@Composable
internal fun TrackRow(
    track: AndroidLibraryItem.Track,
    number: Int,
    playing: Boolean,
    modifier: Modifier,
    onUnavailable: () -> Unit,
    onFavourite: (() -> Unit)? = null,
    favouriteTag: String? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    download: TrackDownload? = null,
    queue: TrackQueue? = null,
    onRate: ((Int) -> Unit)? = null,
    library: TrackLibrary? = null,
    /** The row's tag when no favouriteTag names it (a row with no heart, as My Library's). */
    rowTagOverride: String? = null,
    artwork: (@Composable () -> Unit)? = null,
    albumArtist: String? = null,
    compactActions: Boolean = false,
    pin: TrackPin? = null,
    rank: Int? = null,
    onOpenAlbum: (() -> Unit)? = null,
    onOpenArtist: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val unavailable = track.playability == AndroidLibraryPlayability.UnavailableOffline
    val statusIconSize = with(LocalDensity.current) {
        MaterialTheme.typography.bodySmall.fontSize.toDp().coerceAtLeast(16.dp)
    }
    val resources = libraryResources()
    val rowTag = rowTagOverride ?: favouriteTag?.removeSuffix(".favourite")
    val status = download?.status
    val detail = if (unavailable) resources.getString(SharedR.string.library_not_available_offline)
        else status?.takeIf { !it.playsOffline }?.let { LocalContext.current.resources.downloadLine(it) }
            ?: track.artistName?.takeUnless { it == albumArtist }
    val metadata = listOfNotNull(detail, track.durationMilliseconds?.let(::formatDuration)).metadataLine()
    TrackRowVisual(
        title = resources.trackDisplayTitle(track.title), titleMaxLines = 2,
        supporting = if (metadata.isNotEmpty() || status?.playsOffline == true || (playing && artwork != null)) ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (playing && artwork != null) Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing), Modifier.size(statusIconSize))
                else if (!playing && status?.playsOffline == true) Icon(DulcetIcons.DownloadDone, stringResource(SharedR.string.download_downloaded),
                    Modifier.size(statusIconSize).testTag((rowTag ?: "track") + ".downloaded"))
                if (metadata.isNotEmpty()) Text(metadata, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }) else null,
        leading = artwork, number = track.trackNumber ?: number, rank = rank, modifier = modifier, playing = playing, unavailable = unavailable,
        trailing = if (onFavourite != null || onAddToPlaylist != null || download != null || queue != null || onRate != null || library != null || pin != null) ({
            TrackMenu(rowTag, onAddToPlaylist, download, queue.takeIf { !unavailable }, onRate?.let { TrackRating(track.rating, it) }, library,
                onFavourite = onFavourite, favorite = track.favourite == true, favoriteTag = favouriteTag, pin = pin,
                onOpenAlbum = onOpenAlbum, onOpenArtist = onOpenArtist)
        }) else null,
        onClick = { if (unavailable) onUnavailable() else onClick() },
    )
}

@Composable
internal fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean, tagPrefix: String) {
    DetailActionButtons(
        DetailAction(R.string.action_play, DulcetIcons.Play, "$tagPrefix.play", enabled, onPlay),
        DetailAction(R.string.action_shuffle, DulcetIcons.Shuffle, "$tagPrefix.shuffle", enabled, onShuffle),
    )
}

/** Cover art from the core repository, with a tonal placeholder while loading or when there is none. */
@Composable
internal fun Artwork(account: SearchAccount, key: String?, title: String, size: Dp?, modifier: Modifier = Modifier,
                     entity: ArtworkEntity = ArtworkEntity.Album) {
    val pixels = size?.let { (it.value * 3).toInt() } ?: 512
    ArtworkFrame(rememberArtwork(account, key, pixels), stringResource(R.string.artwork_description, title), size, modifier, entity)
}

@Composable
internal fun Monogram(name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        val initial = name.trim().firstOrNull()?.uppercase()
        if (initial != null) Text(initial, color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = if (size > 64.dp) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleMedium)
        else Icon(DulcetIcons.Person, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

internal fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * The card or row's Add/Remove for My Library, or null where the control hides: no source on this
 * screen, a server without the extension, no publication yet, or an item kind (genre, playlist) that
 * membership does not cover.
 */
@Composable
internal fun libraryAction(myLibrary: MyLibrarySession?, item: AndroidLibraryItem, tag: String): MembershipAction? {
    if (myLibrary == null) return null
    val target = item.favouriteTarget() ?: return null
    val membership = membershipOf(myLibrary, target) ?: return null
    return MembershipAction(membership == MyLibraryMembership.Added, tag) { myLibrary.toggleMembership(target, membership) }
}

/** A track row's Add/Remove in its menu, or null where the control hides (see [libraryAction]). */
@Composable
private fun trackLibraryAction(myLibrary: MyLibrarySession?, track: AndroidLibraryItem.Track): TrackLibrary? {
    if (myLibrary == null) return null
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)
    val membership = membershipOf(myLibrary, target) ?: return null
    return TrackLibrary(membership) { myLibrary.toggleMembership(target, membership) }
}

@Composable
internal fun longDuration(milliseconds: Long): String {
    val minutes = (milliseconds / 60_000).coerceAtLeast(1).toInt()
    return if (minutes < 60) stringResource(R.string.duration_minutes, minutes)
    else stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
}

/** One track's download on its row: its status, if any, and what the row's menu may ask. */
internal class TrackDownload(
    val status: AndroidDownloadStatus?,
    /** Null when the track has no download (its original file would not play here). */
    val onDownload: (() -> Unit)?,
    val onRemove: () -> Unit,
)

/**
 * Play Next and Add to Queue on a row's menu (spec §14.1). Absent for a track that cannot play now:
 * what is added is what a play would queue.
 */
internal class TrackQueue(val onPlayNext: () -> Unit, val onAddToQueue: () -> Unit)

/**
 * A row's rating (§16.20): [value] is the publication's, with any change made here already in it —
 * null when the row does not carry one, which is unknown, never 0 —
 * and [onRate] sets one, 0 removing it. A rating is sent on reconnect, as a heart is, so a track that
 * cannot play offline can still be rated.
 */
internal class TrackRating(val value: Int?, val onRate: (Int) -> Unit)

internal class TrackPin(val pinned: Boolean, val tag: String, val toggle: () -> Unit)

/** The row's context menu: what can be done to one song besides playing it. */
@Composable
private fun TrackMenu(tag: String?, onAddToPlaylist: (() -> Unit)?, download: TrackDownload? = null, queue: TrackQueue? = null,
                      rating: TrackRating? = null, library: TrackLibrary? = null,
                      onFavourite: (() -> Unit)? = null, favorite: Boolean = false, favoriteTag: String? = null, pin: TrackPin? = null,
                      onOpenAlbum: (() -> Unit)? = null, onOpenArtist: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    var rateOpen by remember { mutableStateOf(false) }
    if (rateOpen && rating != null) RatingDialog(tag ?: "track", rating) { rateOpen = false }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag((tag ?: "track") + ".menu")) {
            Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (queue != null) QueueInsertionItems(tag ?: "track", { open = false }, queue.onPlayNext, queue.onAddToQueue)
            val hasLibraryOrNavigationActions = onFavourite != null || pin != null || library != null ||
                onAddToPlaylist != null || rating != null || onOpenAlbum != null || onOpenArtist != null
            if (queue != null && hasLibraryOrNavigationActions) HorizontalDivider()
            if (onFavourite != null) DropdownMenuItem(
                text = { Text(stringResource(if (favorite) R.string.library_favorite_remove else R.string.library_favorite_add)) },
                leadingIcon = { Icon(if (favorite) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder, null) },
                onClick = { open = false; onFavourite() }, modifier = Modifier.testTag(favoriteTag ?: "track.favourite"))
            if (pin != null) DropdownMenuItem(
                text = { Text(stringResource(if (pin.pinned) SharedR.string.library_unpin else SharedR.string.library_pin)) },
                leadingIcon = { Icon(LibraryRowIcons.PushPin, null) }, onClick = { open = false; pin.toggle() },
                modifier = Modifier.testTag(pin.tag))
            if (library != null) DropdownMenuItem(
                text = {
                    Text(stringResource(if (library.membership == com.legitimateapps.dulcet.library.MyLibraryMembership.Added)
                        SharedR.string.library_remove_from_library else SharedR.string.library_add_to_library))
                },
                leadingIcon = {
                    Icon(if (library.membership == com.legitimateapps.dulcet.library.MyLibraryMembership.Added) DulcetIcons.Check else DulcetIcons.Add, null)
                },
                onClick = { open = false; library.toggle() },
                modifier = Modifier.testTag((tag ?: "track") + ".menu.library"),
            )
            if (onAddToPlaylist != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_add_to)) },
                leadingIcon = { Icon(DulcetIcons.PlaylistAdd, null) },
                onClick = { open = false; onAddToPlaylist() },
                modifier = Modifier.testTag((tag ?: "track") + ".menu.addToPlaylist"),
            )
            if (rating != null) DropdownMenuItem(
                text = { Text(stringResource(SharedR.string.library_rating_menu)) },
                leadingIcon = { Icon(if ((rating.value ?: 0) > 0) DulcetIcons.Star else DulcetIcons.StarBorder, null) },
                onClick = { open = false; rateOpen = true },
                modifier = Modifier.testTag((tag ?: "track") + ".menu.rate"),
            )
            if (onOpenAlbum != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.library_go_to_album)) },
                leadingIcon = { Icon(DulcetIcons.LibraryMusic, null) },
                onClick = { open = false; onOpenAlbum() },
                modifier = Modifier.testTag((tag ?: "track") + ".menu.album"),
            )
            if (onOpenArtist != null) DropdownMenuItem(
                text = { Text(stringResource(R.string.library_go_to_artist)) },
                leadingIcon = { Icon(DulcetIcons.Person, null) },
                onClick = { open = false; onOpenArtist() },
                modifier = Modifier.testTag((tag ?: "track") + ".menu.artist"),
            )
            if (download?.status != null) {
                if (queue != null || hasLibraryOrNavigationActions) HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(SharedR.string.download_track_remove), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(DulcetIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { open = false; download.onRemove() },
                    modifier = Modifier.testTag((tag ?: "track") + ".menu.removeDownload"),
                )
            } else if (download?.onDownload != null) DropdownMenuItem(
                text = { Text(stringResource(SharedR.string.download_track)) },
                leadingIcon = { Icon(DulcetIcons.Download, null) },
                onClick = { open = false; download.onDownload.invoke() },
                modifier = Modifier.testTag((tag ?: "track") + ".menu.download"),
            )
        }
    }
}

/**
 * The song's stars, from its menu: each tap is a change of its own, shown in the stars and on the row
 * at once and sent like a heart's; Done only closes. Tagged `<tag>.rating` and `<tag>.rating.done`.
 */
@Composable
private fun RatingDialog(tag: String, rating: TrackRating, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(SharedR.string.library_rating)) },
        text = {
            RatingStars(rating.value, rating.onRate, "$tag.rating",
                onColor = MaterialTheme.colorScheme.primary, offColor = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        confirmButton = {
            TextButton(onClick = close, modifier = Modifier.testTag("$tag.rating.done")) {
                Text(stringResource(SharedR.string.library_rating_done))
            }
        },
    )
}

/** Play Next and Add to Queue as menu items, tagged `<tag>.menu.playNext` and `<tag>.menu.addToQueue`. */
@Composable
internal fun QueueInsertionItems(tag: String, close: () -> Unit, onPlayNext: () -> Unit, onAddToQueue: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(SharedR.string.queue_play_next)) },
        leadingIcon = { Icon(DulcetIcons.PlaylistPlay, null) },
        onClick = { close(); onPlayNext() },
        modifier = Modifier.testTag("$tag.menu.playNext"),
    )
    DropdownMenuItem(
        text = { Text(stringResource(SharedR.string.queue_add_to_queue)) },
        leadingIcon = { Icon(DulcetIcons.QueueMusic, null) },
        onClick = { close(); onAddToQueue() },
        modifier = Modifier.testTag("$tag.menu.addToQueue"),
    )
}
