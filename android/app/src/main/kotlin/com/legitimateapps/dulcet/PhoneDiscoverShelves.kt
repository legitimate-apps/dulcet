package com.legitimateapps.dulcet

import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.library.ageWords
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity

/** Compass points distinguish Discover from Browse's catalog grid. */
internal val DiscoverCompass = androidx.compose.ui.graphics.vector.ImageVector.Builder("Discover", 24.dp, 24.dp, 24f, 24f)
    .addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(
        "M12 2L15 9L22 12L15 15L12 22L9 15L2 12L9 9Z").toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black))
    .build()

private val shelfIds = listOf("trendingArtists", "trendingAlbums", "trendingSongs", "newReleases", "similarArtists", "publicPlaylists")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneDiscoverHome(session: DiscoverWishlistSession, openShelf: (String) -> Unit,
    openPlaylists: () -> Unit, openPlaylist: (String) -> Unit, openWishlist: () -> Unit,
    openLibrary: (DiscoverLibraryRef) -> Unit, openArtist: (String) -> Unit, openAlbum: (String) -> Unit, openRecentWish: (String) -> Unit = { openWishlist() }, back: (() -> Unit)? = null) {
    val publication by session.publication.collectAsState()
    val current = publication ?: return
    if (!current.access.discoverAvailable) return
    LaunchedEffect(session) { session.refreshHome() }
    LaunchedEffect(session, current.access.canReadWishlist) {
        if (current.access.canReadWishlist) session.refreshWishlist(WishFilter())
    }
    val home = current.home
    val barList = rememberLazyListState()
    val scrollBehavior = rememberPositionTintedBar { barList.firstVisibleItemIndex > 0 || barList.firstVisibleItemScrollOffset > 0 }
    val stale = home?.shelves?.firstOrNull { it.page?.age?.isSaved() == true }
    val failedShelf = home?.shelves?.firstOrNull { it.error != null || it.page?.refreshError != null }
    val statusShown = stale != null || failedShelf != null || current.connection == "offline"
    // The list keeps its first item in place, which would leave a status line arriving above it out of view.
    LaunchedEffect(statusShown) { if (statusShown && barList.firstVisibleItemIndex <= 1) barList.scrollToItem(0) }
    Column(Modifier.fillMaxSize().testTag("discover.home")) {
        DiscoverShelfBar(stringResource(R.string.discover_home_title), back = back,
            refresh = if (current.connection == "offline") null else ({ session.refreshHome() }), scrollBehavior = scrollBehavior)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("discover.home.list"),
            state = barList,
            contentPadding = PaddingValues(top = DulcetSpacing.s, bottom = DulcetSpacing.l),
        ) {
            if (statusShown) item("status") {
                Box(Modifier.padding(horizontal = DulcetSpacing.pageGutter).padding(bottom = DulcetSpacing.sectionGap)) {
                    ShelfNotices("discover.home.status", stale?.page?.age ?: home?.shelves?.firstNotNullOfOrNull { it.page?.age },
                        failedShelf?.let { it.error ?: it.page?.refreshError }, false, false, "", current.connection,
                        retry = session::refreshHome)
                }
            }
            item("trending") {
                Text(stringResource(R.string.discover_trending), Modifier.padding(horizontal = DulcetSpacing.pageGutter),
                    style = MaterialTheme.typography.titleLarge)
            }
            for (id in shelfIds) {
                val state = home?.shelves?.firstOrNull { it.id == id }
                val page = state?.page
                if (state != null && page == null && !state.loading) continue
                val loading = (state?.loading ?: (home == null)) && page == null
                val empty = page != null && page.items.isEmpty() && page.playlists.isEmpty()
                item(id) {
                    val tag = "discover.shelf.$id"
                    val seeAll: (() -> Unit)? = if (page?.nextCursor != null) {
                        if (id == "publicPlaylists") openPlaylists else ({ openShelf(id) })
                    } else null
                    Column(Modifier.fillMaxWidth().testTag(tag)) {
                        DulcetSectionHeader(shelfTitle(id), onSeeAll = seeAll, seeAllTag = "$tag.all",
                            seeAllLabel = stringResource(R.string.discover_see_all))
                        if (loading || empty) {
                            Box(Modifier.padding(horizontal = DulcetSpacing.pageGutter)) {
                                ShelfNotices(tag, null, null, loading, empty, shelfEmpty(id), null, cards = true,
                                    retry = session::refreshHome)
                            }
                        }
                        if (!loading && !empty && page != null) {
                            val rail = rememberLazyListState()
                            LazyRow(
                                Modifier.fillMaxWidth().testTag("$tag.items"),
                                state = rail,
                                flingBehavior = rememberSnapFlingBehavior(rail, SnapPosition.Start),
                                contentPadding = PaddingValues(horizontal = DulcetSpacing.pageGutter),
                                horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.cardGap),
                            ) {
                                items(page.items, key = { "item:${it.id}" }) { item ->
                                    ShelfMusicItem(session, item, "$tag.item.${item.id}", WishSource("shelf", id),
                                        openLibrary, openArtist, openAlbum, Modifier.railTileWidth(), card = true)
                                }
                                items(page.playlists, key = { "playlist:${it.id}" }) { playlist ->
                                    ShelfPlaylistRow(session, playlist, "$tag.playlist.${playlist.id}", { openPlaylist(playlist.id) },
                                        Modifier.railTileWidth(), card = true)
                                }
                            }
                        }
                    }
                }
            }
            if (current.access.wishlistAvailable) item("recent-wishes") {
                val recent = home?.recentWishes.orEmpty()
                Column(Modifier.fillMaxWidth().testTag("discover.recentWishes")) {
                    DulcetSectionHeader(stringResource(R.string.discover_recent_wishes), onSeeAll = openWishlist,
                        seeAllTag = "discover.recentWishes.all", seeAllLabel = stringResource(R.string.discover_see_all))
                    val loading = home?.wishesLoading ?: true
                    val empty = home != null && home.wishesSettled && recent.isEmpty()
                    if (loading || empty || home?.wishesError != null) {
                        Box(Modifier.padding(horizontal = DulcetSpacing.pageGutter)) {
                            ShelfNotices("discover.recentWishes", null, home?.wishesError, loading, empty,
                                stringResource(R.string.discover_empty_wishes), current.connection, retry = session::refreshHome)
                        }
                    }
                    recent.forEach { row ->
                        key(row.key) { RecentWishRow(session, row) { openRecentWish(row.key) } }
                    }
                }
            }
            item("credit") {
                Column(Modifier.padding(horizontal = DulcetSpacing.pageGutter).padding(top = DulcetSpacing.sectionGap),
                    verticalArrangement = Arrangement.spacedBy(DulcetSpacing.xs)) {
                    Text(stringResource(R.string.discover_suggestions_credit), Modifier.testTag("discover.suggestionsCredit"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ShelfCredit()
                }
            }
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneDiscoverShelf(session: DiscoverWishlistSession, id: String, openLibrary: (DiscoverLibraryRef) -> Unit,
    openArtist: (String) -> Unit, openAlbum: (String) -> Unit, back: () -> Unit,
    memory: DiscoverBrowseMemory = remember(session) { DiscoverBrowseMemory() }) {
    var savedPages by rememberSaveable(session, id) { mutableIntStateOf(1) }
    val reader = remember(session, id, memory) { memory.shelf(id) }
    LaunchedEffect(reader.pagesLoaded) { if (reader.pagesLoaded > 0) savedPages = reader.pagesLoaded }
    DisposableEffect(reader, session) { reader.mount(savedPages) { cursor, done -> session.shelf(id, cursor, done) }; onDispose { reader.close() } }
    val page = reader.value
    val publication by session.publication.collectAsState()
    val tag = "discover.shelfList.$id"
    val barList = rememberLazyListState()
    val scrollBehavior = rememberPositionTintedBar { barList.firstVisibleItemIndex > 0 || barList.firstVisibleItemScrollOffset > 0 }
    Column(Modifier.fillMaxSize()) {
        DiscoverShelfBar(shelfTitle(id), back, scrollBehavior = scrollBehavior)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(tag), state = barList, contentPadding = PaddingValues(DulcetSpacing.pageGutter)) {
            // Keep a first list item across loading. Otherwise the initially visible credit can
            // become the scroll anchor, so inserting the first page opens the list at its tail.
            item("notice") {
                val failure = reader.error ?: page?.refreshError
                val loading = reader.loading || (page == null && failure == null)
                val empty = page?.items?.isEmpty() == true
                if (hasShelfNotice(page?.age, failure, loading, empty, publication?.connection)) {
                    ShelfNotices("discover.shelf.$id", page?.age, failure, loading, empty,
                        shelfEmpty(id), publication?.connection, retry = reader::refresh)
                }
            }
            items(page?.items.orEmpty(), key = { it.id }) { item ->
                ShelfMusicItem(session, item, "discover.shelf.$id.item.${item.id}", WishSource("shelf", id), openLibrary, openArtist, openAlbum)
            }
            if (page?.nextCursor != null) item("more") { ShelfMore("$tag.more", reader.loading, reader::more) }
            item("credit") { ShelfCredit() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneDiscoverPlaylists(session: DiscoverWishlistSession, openPlaylist: (String) -> Unit, back: () -> Unit,
    memory: DiscoverBrowseMemory = remember(session) { DiscoverBrowseMemory() }) {
    var field by rememberSaveable(session) { mutableStateOf("") }
    var query by rememberSaveable(session) { mutableStateOf("") }
    var savedPages by rememberSaveable(session, query) { mutableIntStateOf(1) }
    val reader = remember(session, query, memory) { memory.list(query) }
    LaunchedEffect(reader.pagesLoaded) { if (reader.pagesLoaded > 0) savedPages = reader.pagesLoaded }
    DisposableEffect(reader, session) { reader.mount(savedPages) { cursor, done -> session.playlists(query, cursor, done) }; onDispose { reader.close() } }
    val publication by session.publication.collectAsState()
    val page = reader.value
    val barList = rememberLazyListState()
    val scrollBehavior = rememberPositionTintedBar { barList.firstVisibleItemIndex > 0 || barList.firstVisibleItemScrollOffset > 0 }
    Column(Modifier.fillMaxSize()) {
        DiscoverShelfBar(stringResource(R.string.discover_public_playlists), back, scrollBehavior = scrollBehavior)
        PhoneSearchField(field, { field = it }, { if (query == field) reader.refresh() else query = field },
            "discover.playlists.query", stringResource(R.string.discover_playlists_query), avatar = false,
            submitTag = "discover.playlists.submit",
            submitLabel = stringResource(R.string.discover_playlists_submit), windowInsets = WindowInsets(0)) // The bar above has the status bar.
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discover.playlists"), state = barList, contentPadding = PaddingValues(DulcetSpacing.pageGutter)) {
            // Keep a first list item across loading. Otherwise the initially visible credit can
            // become the scroll anchor, so inserting the first page opens the list at its tail.
            item("notice") {
                val failure = reader.error ?: page?.refreshError
                val loading = reader.loading || (page == null && failure == null)
                val empty = page?.items?.isEmpty() == true
                if (hasShelfNotice(page?.age, failure, loading, empty, publication?.connection)) {
                    ShelfNotices("discover.playlists", page?.age, failure, loading, empty,
                        stringResource(R.string.discover_empty_playlists), publication?.connection, retry = reader::refresh)
                }
            }
            items(page?.items.orEmpty(), key = { it.id }) { playlist -> ShelfPlaylistRow(session, playlist, "discover.playlists.item.${playlist.id}", { openPlaylist(playlist.id) }, Modifier.bleedKeyline()) }
            if (page?.nextCursor != null) item("more") { ShelfMore("discover.playlists.more", reader.loading, reader::more) }
            item("credit") { ShelfCredit() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneDiscoverPlaylist(session: DiscoverWishlistSession, mbid: String, openLibrary: (DiscoverLibraryRef) -> Unit,
    openArtist: (String) -> Unit, openAlbum: (String) -> Unit, back: () -> Unit,
    memory: DiscoverBrowseMemory = remember(session) { DiscoverBrowseMemory() },
    playMatches: ((String, List<DiscoverItem>, Boolean) -> Unit)? = null,
    downloadedOnly: Boolean = false,
    isDownloaded: (DiscoverLibraryRef) -> Boolean = { false },
) {
    var savedPages by rememberSaveable(session, mbid) { mutableIntStateOf(1) }
    val reader = remember(session, mbid, memory) { memory.playlist(mbid) }
    LaunchedEffect(reader.pagesLoaded) { if (reader.pagesLoaded > 0) savedPages = reader.pagesLoaded }
    DisposableEffect(reader, session) { reader.mount(savedPages) { cursor, done -> session.playlist(mbid, cursor, done) }; onDispose { reader.close() } }
    val publication by session.publication.collectAsState()
    val page = reader.value
    val tag = "discover.playlist.$mbid"
    val matchedEntries = page?.entries.orEmpty().mapNotNull { entry ->
        entry.item.takeIf { shelfLibraryRoute(it, publication?.providerInstanceId)?.type == "song" }
    }
    val failure = reader.error ?: page?.refreshError
    val offline = downloadedOnly || publication?.connection == "offline" || failure?.code in setOf("unauthorized", "offline")
    val playableEntries = if (offline) matchedEntries.filter { item ->
        shelfLibraryRoute(item, publication?.providerInstanceId)?.let(isDownloaded) == true
    } else matchedEntries
    val barList = rememberLazyListState()
    val scrollBehavior = rememberPositionTintedBar { barList.firstVisibleItemIndex > 0 || barList.firstVisibleItemScrollOffset > 0 }
    Column(Modifier.fillMaxSize()) {
        // The hero names the playlist at rest; the bar takes the name over once the hero has left.
        DiscoverShelfBar(page?.summary?.title?.let { if (barList.firstVisibleItemIndex > 0) it else "" }
            ?: stringResource(R.string.discover_public_playlists), back,
            scrollBehavior = scrollBehavior, menuItems = { close ->
                PhoneMenuItem(text = { Text(stringResource(R.string.library_refresh)) },
                    leadingIcon = { Icon(DulcetIcons.Refresh, null) },
                    onClick = { close(); reader.refresh() }, modifier = Modifier.testTag("$tag.refresh"))
            })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(tag), state = barList, contentPadding = PaddingValues(DulcetSpacing.pageGutter)) {
            item("summary") {
                Column {
                    val empty = page?.entries?.isEmpty() == true
                    if (hasShelfNotice(page?.age, failure, reader.loading, empty, publication?.connection)) {
                        Box(Modifier.padding(bottom = DulcetSpacing.l)) {
                            ShelfNotices(tag, page?.age, failure, reader.loading, empty,
                                stringResource(R.string.discover_empty_playlist), publication?.connection, retry = reader::refresh)
                        }
                    }
                    page?.summary?.let { summary ->
                        LibraryDetailHeader(summary.title, modifier = Modifier.bleedKeyline(), artwork = { DiscoverArtwork(session, null, summary.title, null, Modifier.detailHeroArt(), entity = ArtworkEntity.Playlist) }, subtitle = summary.creator,
                            artworkSpacing = DulcetSpacing.l,
                            metadata = summary.trackCount?.let { pluralStringResource(R.plurals.discover_playlist_songs, it, it) },
                            status = {
                                if (matchedEntries.isNotEmpty()) Text(
                                    stringResource(R.string.discover_shelves_partial, matchedEntries.size, summary.trackCount ?: matchedEntries.size),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("$tag.availability"))
                                else Text(stringResource(R.string.discover_playlist_no_matches),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("$tag.availability"))
                            },
                            actions = if (matchedEntries.isNotEmpty() && playMatches != null) ({
                                PlayShuffleButtons(
                                    onPlay = { playMatches(summary.title, playableEntries, false) },
                                    onShuffle = { playMatches(summary.title, playableEntries, true) },
                                    enabled = playableEntries.isNotEmpty(), tagPrefix = tag)
                            }) else null)
                    }
                }
            }
            if (!page?.entries.isNullOrEmpty()) item("songs-title") {
                DulcetSectionHeader(stringResource(R.string.discover_pages_tracks), Modifier.bleedKeyline(),
                    role = SectionRole.Detail)
            }
            items(page?.entries.orEmpty(), key = { it.position }) { entry ->
                ShelfMusicItem(session, entry.item, "$tag.entry.${entry.position}", WishSource("playlist", mbid), openLibrary, openArtist, openAlbum, position = entry.position)
            }
            if (page?.nextCursor != null) item("more") { ShelfMore("$tag.more", reader.loading, reader::more) }
            item("credit") { ShelfCredit() }
        }
    }
}

/** Library IDs stay scoped. An external recording has no invented playback or detail route. */
internal fun shelfLibraryRoute(item: DiscoverItem, provider: String?): DiscoverLibraryRef? =
    item.availability.libraryItems.singleOrNull()?.takeIf {
        item.availability.status == "held" && (item.kind != "album" || item.availability.completeness == "known") && it.providerInstanceId == provider && it.type == item.kind
    }

@Composable
private fun ShelfMusicItem(session: DiscoverWishlistSession, item: DiscoverItem, tag: String, source: WishSource,
    openLibrary: (DiscoverLibraryRef) -> Unit, openArtist: (String) -> Unit, openAlbum: (String) -> Unit,
    modifier: Modifier = Modifier, card: Boolean = false, position: Int? = null) {
    val publication by session.publication.collectAsState()
    val state = publication
    val wish = (state?.wishes.orEmpty() + state?.home?.recentWishes.orEmpty()).firstOrNull {
        item.target != null && (it.wish?.target ?: it.pendingAdd?.target) == item.target
    }
    val recognized = item.kind in setOf("artist", "album", "song")
    val local = if (recognized) shelfLibraryRoute(item, state?.providerInstanceId) else null
    val open: (() -> Unit)? = when {
        local != null -> ({ openLibrary(local) })
        item.target != null && item.kind == "artist" -> ({ openArtist(item.target!!.mbid) })
        item.target != null && item.kind == "album" -> ({ openAlbum(item.target!!.mbid) })
        else -> null
    }
    var failure by remember(session, tag) { mutableStateOf<DiscoverFailure?>(null) }
    var menuOpen by remember(session, tag) { mutableStateOf(false) }
    val lifetime = remember(session, tag) { ShelfActionLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.close() } }
    val explicitLocal = item.availability.libraryItems.firstOrNull { recognized && it.providerInstanceId == state?.providerInstanceId }
    val canRemove = recognized && wish != null && state?.access?.canRemoveWish == true
    val canAdd = wish == null && recognized && item.wishable && item.target != null && state?.access?.canAddWish == true
    val canReject = recognized && state?.access?.canRejectMatch == true && item.availability.libraryItems.isNotEmpty()
    val hasActions = explicitLocal != null || canRemove || canAdd || canReject
    val wishLabel = stringResource(if (canRemove) R.string.discover_remove_wish_named else R.string.discover_shelves_wish, item.title)
    val actionsLabel = stringResource(R.string.discover_item_actions, item.title)
    val menuItems: @Composable (() -> Unit) -> Unit = { close ->
        explicitLocal?.let { ref -> PhoneMenuItem(text = { Text(stringResource(R.string.discover_open_library)) },
            leadingIcon = { Icon(DulcetIcons.LibraryMusic, null) }, onClick = { close(); openLibrary(ref) },
            modifier = Modifier.testTag("$tag.openLibrary")) }
        if (canRemove || canAdd) PhoneMenuItem(
            text = { Text(stringResource(if (canRemove) R.string.discover_remove_wish else R.string.discover_add_wish)) },
            leadingIcon = { Icon(if (canRemove) DulcetIcons.Wish else DulcetIcons.WishBorder, null) },
            onClick = {
                close()
                if (canRemove) session.remove(checkNotNull(wish).key) { if (lifetime.active) failure = (it as? DiscoverResult.Failed)?.error }
                else session.add(item, source) { if (lifetime.active) failure = (it as? DiscoverResult.Failed)?.error }
            }, modifier = Modifier.testTag("$tag.wish").semantics {
                contentDescription = wishLabel
            })
        if (canReject) item.availability.libraryItems.forEachIndexed { index, ref -> PhoneMenuItem(
            text = { Text(stringResource(R.string.discover_reject)) }, leadingIcon = { Icon(DulcetIcons.Edit, null) },
            onClick = { close(); session.reject(item, ref, wish?.wish?.id) { if (lifetime.active) failure = (it as? DiscoverResult.Failed)?.error } },
            modifier = Modifier.testTag("$tag.reject.$index")) }
    }
    val actions: @Composable () -> Unit = {
        if (hasActions) Box {
            IconButton({ menuOpen = true }, Modifier.testTag("$tag.menu")) {
                Icon(DulcetIcons.MoreVert, actionsLabel)
            }
            DropdownMenu(menuOpen, { menuOpen = false }) { menuItems { menuOpen = false } }
        }
    }
    val supporting: @Composable () -> Unit = {
        Column {
            item.artist?.let { Text(it, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            DiscoverAvailabilityText(item.availability, "$tag.availability", compact = card)
            if (position != null && item.target == null && item.availability.status !in setOf("held", "partlyHeld")) Text(
                stringResource(R.string.discover_playlist_no_match), Modifier.testTag("$tag.noMatch"),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (position != null && wish?.pending == true) DiscoverSyncLine("$tag.sync")
            (failure ?: wish?.error)?.let { Text(stringResource(if (it.retryable) R.string.discover_waiting else R.string.discover_not_saved), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (card) {
        AlbumCardVisual(
            title = item.title,
            subtitle = item.artist?.takeUnless { item.kind == "artist" },
            modifier = modifier.testTag(tag),
            artwork = {
                DiscoverArtwork(session, item.artworkId, item.title, null, Modifier.fillMaxWidth().aspectRatio(1f),
                    entity = item.artworkEntity(), loadTag = "$tag.artwork")
            },
            menuItems = menuItems.takeIf { hasActions },
            moreTag = "$tag.menu".takeIf { hasActions },
            moreLabel = actionsLabel,
            moreInset = if (item.kind == "artist") DulcetSpacing.m else 0.dp,
            onClick = open,
            captionStatus = {
                DiscoverAvailabilityText(item.availability, "$tag.availability", compact = true)
                if (wish?.pending == true) DiscoverSyncLine("$tag.sync")
                (failure ?: wish?.error)?.let {
                    Text(stringResource(if (it.retryable) R.string.discover_waiting else R.string.discover_not_saved),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    } else if (position != null) {
        CuratedEntryRow(position, item.title, supporting, open, modifier.bleedKeyline().testTag(tag),
            trailing = actions.takeIf { hasActions })
    } else {
        val trailing: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.xs)) {
                if (wish?.pending == true) DiscoverSyncStatus("$tag.sync")
                actions()
            }
        }
        PhoneMediaRow(title = item.title, supporting = supporting,
            leading = { Box(Modifier.clearAndSetSemantics {}) {
                DiscoverArtwork(session, item.artworkId, item.title, DulcetSizes.rowArt, entity = item.artworkEntity())
            } }, trailing = trailing.takeIf { hasActions || wish?.pending == true },
            modifier = modifier.bleedKeyline().testTag(tag), onClick = open, titleMaxLines = 2)
    }
}

/** Curated rows share Library's numbered song geometry even when a recording has no local match. */
@Composable
internal fun CuratedEntryRow(
    position: Int, title: String, supporting: @Composable () -> Unit, onClick: (() -> Unit)?,
    modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null,
) {
    TrackRowVisual(title, supporting, position, modifier, onClick = onClick, trailing = trailing,
        numberTag = "curated.number")
}

private fun DiscoverItem.artworkEntity(): ArtworkEntity = when (kind) {
    "artist" -> ArtworkEntity.Artist
    "song" -> ArtworkEntity.Song
    "playlist" -> ArtworkEntity.Playlist
    else -> ArtworkEntity.Album
}

private class ShelfActionLifetime {
    var active = true
    fun close() { active = false }
}

@Composable
private fun ShelfPlaylistRow(session: DiscoverWishlistSession, playlist: DiscoverPlaylistSummary, tag: String, open: () -> Unit, modifier: Modifier = Modifier, card: Boolean = false) {
    if (card) {
        AlbumCardVisual(
            title = playlist.title,
            subtitle = playlistMetadata(playlist),
            modifier = modifier.testTag(tag),
            artwork = { DiscoverArtwork(session, null, playlist.title, null, Modifier.fillMaxWidth().aspectRatio(1f),
                entity = ArtworkEntity.Playlist) },
            onClick = open,
        )
    } else {
        PhoneMediaRow(
            title = playlist.title,
            supporting = { MetadataText(playlistMetadata(playlist), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            leading = { DiscoverArtwork(session, null, playlist.title, DulcetSizes.rowArt, entity = ArtworkEntity.Playlist) },
            modifier = modifier.testTag(tag), onClick = open, titleMaxLines = 2,
        )
    }
}

@Composable
private fun playlistMetadata(value: DiscoverPlaylistSummary): String =
    listOfNotNull(value.creator, value.trackCount?.let { pluralStringResource(R.plurals.discover_playlist_songs, it, it) }).metadataLine()

private fun hasShelfNotice(age: DiscoverAge?, error: DiscoverFailure?, loading: Boolean, empty: Boolean, connection: String?): Boolean =
    age?.isSaved() == true || error != null || loading && age == null || empty && !loading || connection == "offline"

@Composable
private fun ShelfNotices(tag: String, age: DiscoverAge?, error: DiscoverFailure?, loading: Boolean,
    empty: Boolean, emptyText: String, connection: String?, cards: Boolean = false, retry: () -> Unit) {
    val offline = error?.code == "offline" || connection == "offline"
    Column(verticalArrangement = Arrangement.spacedBy(DulcetSpacing.s)) {
        if (offline || error == null && age?.isSaved() == true) DiscoverSavedStatus(age, offline, "$tag.age")
        if (error != null && !offline) {
            val resources = libraryResources()
            val ageLabel = age?.takeIf(DiscoverAge::isSaved)?.let { resources.ageWords(it.asOfMillis()) }
            DiscoverFailureNotice(
                error,
                stringResource(if (age == null) R.string.discover_shelves_failed else R.string.discover_refresh_failed),
                modifier = Modifier.testTag("$tag.error"),
                titleTag = "$tag.error.message",
                body = ageLabel?.let { stringResource(SharedR.string.library_seen_at, it) },
                retryTag = "$tag.retry",
                retry = retry,
                tone = if (age == null || error.code == "unauthorized" || error.code == "forbidden") NoticeTone.Error else NoticeTone.Info,
            )
        }
        if (loading && age == null) DiscoverLoadingRows("$tag.loading", cards, cardWidth = railTileWidthDp())
        if (error == null && empty && !loading) Text(emptyText, Modifier.testTag("$tag.empty"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverShelfBar(
    title: String,
    back: (() -> Unit)? = null,
    refresh: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    menuItems: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    TopAppBar(title = { DetailBarTitleText(title, LocalTextStyle.current, maxLines = 1) },
        expandedHeight = detailAppBarHeight(collapsed = false, title = title, alwaysShowTitle = true), navigationIcon = {
        if (back != null) IconButton(back, Modifier.testTag("discover.back")) { Icon(DulcetIcons.ArrowBack, stringResource(R.string.discover_pages_back)) }
    }, actions = {
        if (refresh != null) IconButton(refresh, Modifier.testTag("discover.home.refresh")) { Icon(DulcetIcons.Refresh, stringResource(R.string.discover_home_refresh)) }
        if (menuItems != null) {
            var expanded by remember(title) { mutableStateOf(false) }
            Box {
                IconButton({ expanded = true }, Modifier.testTag("discover.playlist.more")) {
                    Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
                }
                DropdownMenu(expanded, { expanded = false }) { menuItems { expanded = false } }
            }
        }
        if (back == null) AccountAvatarAction()
    },
        colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
        scrollBehavior = scrollBehavior)
}
@Composable private fun ShelfCredit() { Text(stringResource(R.string.discover_credit), Modifier.testTag("discover.credit"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun ShelfMore(tag: String, loading: Boolean, more: () -> Unit) { TextButton(more, Modifier.testTag(tag), enabled = !loading) { Text(stringResource(R.string.discover_more)) } }
@Composable private fun shelfTitle(id: String) = stringResource(when (id) {
    "trendingArtists" -> R.string.discover_artists; "trendingAlbums" -> R.string.discover_albums; "trendingSongs" -> R.string.discover_songs
    "newReleases" -> R.string.discover_new_releases; "similarArtists" -> R.string.discover_similar_artists; else -> R.string.discover_public_playlists
})
@Composable private fun shelfEmpty(id: String) = stringResource(when (id) {
    "trendingArtists" -> R.string.discover_empty_trending_artists; "trendingAlbums" -> R.string.discover_empty_trending_albums; "trendingSongs" -> R.string.discover_empty_trending_songs
    "newReleases" -> R.string.discover_empty_releases; "similarArtists" -> R.string.discover_empty_similar; else -> R.string.discover_empty_playlists
})
