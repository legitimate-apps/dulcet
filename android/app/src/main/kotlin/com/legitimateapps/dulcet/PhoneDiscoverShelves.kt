package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity
import kotlinx.coroutines.delay
import kotlin.math.ceil

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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection).testTag("discover.home")) {
        DiscoverShelfBar(stringResource(R.string.discover_home_title), back = back,
            refresh = if (current.connection == "offline") null else ({ session.refreshHome() }), scrollBehavior = scrollBehavior)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discover.home.list"),
            contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val stale = home?.shelves?.firstOrNull { it.page?.age?.let { age -> age.stale || age.fromDeviceCache } == true }
            val failedShelf = home?.shelves?.firstOrNull { it.error != null || it.page?.refreshError != null }
            if (stale != null || failedShelf != null || current.connection == "offline") item("status") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    ShelfNotices("discover.home.status", stale?.page?.age ?: home?.shelves?.firstNotNullOfOrNull { it.page?.age },
                        failedShelf?.let { it.error ?: it.page?.refreshError }, false, false, "", current.connection,
                        retry = session::refreshHome)
                }
            }
            item("trending") { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { ShelfHeading(stringResource(R.string.discover_trending)) } }
            for (id in shelfIds) item(id) {
                val state = home?.shelves?.firstOrNull { it.id == id }
                val page = state?.page
                val tag = "discover.shelf.$id"
                Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        ShelfSectionTitle(shelfTitle(id), if (page?.nextCursor != null) ({ if (id == "publicPlaylists") openPlaylists() else openShelf(id) }) else null, "$tag.all")
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        ShelfNotices(tag, null, null,
                            (state?.loading ?: (home == null)) && page == null,
                            page != null && page.items.isEmpty() && page.playlists.isEmpty(), shelfEmpty(id), null, cards = true) { session.refreshHome() }
                    }
                    LazyRow(Modifier.fillMaxWidth().testTag("$tag.items"), contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(page?.items.orEmpty(), key = { it.id }) { item ->
                            ShelfMusicItem(session, item, "$tag.item.${item.id}", WishSource("shelf", id), openLibrary, openArtist, openAlbum, Modifier.width(160.dp), card = true)
                        }
                        items(page?.playlists.orEmpty(), key = { it.id }) { playlist ->
                            ShelfPlaylistRow(session, playlist, "$tag.playlist.${playlist.id}", { openPlaylist(playlist.id) }, Modifier.width(280.dp), card = true)
                        }
                    }
                }
            }
            if (current.access.wishlistAvailable) {
                item("recent-title") { Column(Modifier.padding(horizontal = 16.dp).testTag("discover.recentWishes")) {
                    ShelfSectionTitle(stringResource(R.string.discover_recent_wishes), openWishlist, "discover.recentWishes.all")
                    ShelfNotices("discover.recentWishes", null, home?.wishesError, home?.wishesLoading ?: true,
                        home?.wishesSettled == true && home.recentWishes.isEmpty(), stringResource(R.string.discover_empty_wishes), current.connection) { session.refreshHome() }
                } }
                items(home?.recentWishes.orEmpty(), key = { "wish:${it.key}" }) { row ->
                    val snapshot = row.wish?.snapshot ?: row.pendingAdd?.snapshot
                    if (snapshot != null) ListItem(headlineContent = { Text(snapshot.title) },
                        supportingContent = { Column {
                            snapshot.artist?.let { Text(it) }
                            if (row.pending) Text(stringResource(R.string.discover_waiting))
                            row.error?.let { Text(stringResource(R.string.discover_not_saved)) }
                        } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("discover.recentWishes.item.${row.key}").clickable { openRecentWish(row.key) })
                }
            }
            item("credit") { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.discover_suggestions_credit), Modifier.testTag("discover.suggestionsCredit"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ShelfCredit()
            } }
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        DiscoverShelfBar(shelfTitle(id), back, scrollBehavior = scrollBehavior)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(tag), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("notices") { ShelfNotices("discover.shelf.$id", page?.age, reader.error ?: page?.refreshError,
                reader.loading, page != null && page.items.isEmpty(), shelfEmpty(id), publication?.connection, retry = reader::refresh) }
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        DiscoverShelfBar(stringResource(R.string.discover_public_playlists), back, scrollBehavior = scrollBehavior)
        PhoneSearchField(field, { field = it }, { if (query == field) reader.refresh() else query = field },
            "discover.playlists.query", stringResource(R.string.discover_playlists_query), avatar = false,
            submitTag = "discover.playlists.submit")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discover.playlists"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("notices") { ShelfNotices("discover.playlists", page?.age, reader.error ?: page?.refreshError, reader.loading,
                page != null && page.items.isEmpty(), stringResource(R.string.discover_empty_playlists), publication?.connection, retry = reader::refresh) }
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
    playMatches: ((String, List<DiscoverItem>, Boolean) -> Unit)? = null) {
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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        DiscoverShelfBar(page?.summary?.title ?: stringResource(R.string.discover_public_playlists), back,
            scrollBehavior = scrollBehavior, menuItems = { close ->
                DropdownMenuItem(text = { Text(stringResource(R.string.library_refresh)) },
                    leadingIcon = { Icon(DulcetIcons.Refresh, null) },
                    onClick = { close(); reader.refresh() }, modifier = Modifier.testTag("$tag.refresh"))
            })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(tag), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            page?.summary?.let { summary -> item("summary") {
                LibraryDetailHeader(summary.title, modifier = Modifier.bleedKeyline(), artwork = { DiscoverArtwork(session, null, summary.title, 120.dp, entity = ArtworkEntity.Playlist) }, subtitle = summary.creator,
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
                            onPlay = { playMatches(summary.title, matchedEntries, false) },
                            onShuffle = { playMatches(summary.title, matchedEntries, true) },
                            enabled = true, tagPrefix = tag)
                    }) else null)
            } }
            item("notices") { ShelfNotices(tag, page?.age, reader.error ?: page?.refreshError, reader.loading,
                page != null && page.entries.isEmpty(), stringResource(R.string.discover_empty_playlist), publication?.connection, retry = reader::refresh) }
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
    val actions: @Composable () -> Unit = {
        if (explicitLocal != null || canRemove || canAdd || canReject) Box {
            IconButton({ menuOpen = true }, Modifier.testTag("$tag.menu")) {
                Icon(DulcetIcons.MoreVert, stringResource(R.string.discover_item_actions, item.title))
            }
            DropdownMenu(menuOpen, { menuOpen = false }) {
                explicitLocal?.let { ref -> DropdownMenuItem(text = { Text(stringResource(R.string.discover_open_library)) },
                    leadingIcon = { Icon(DulcetIcons.LibraryMusic, null) }, onClick = { menuOpen = false; openLibrary(ref) },
                    modifier = Modifier.testTag("$tag.openLibrary")) }
                if (canRemove || canAdd) DropdownMenuItem(
                    text = { Text(stringResource(if (canRemove) R.string.discover_remove_wish else R.string.discover_add_wish)) },
                    leadingIcon = { Icon(if (canRemove) DulcetIcons.Wish else DulcetIcons.WishBorder, null) },
                    onClick = {
                        menuOpen = false
                        if (canRemove) session.remove(checkNotNull(wish).key) { if (lifetime.active) failure = (it as? DiscoverResult.Failed)?.error }
                        else session.add(item, source) { if (lifetime.active) failure = (it as? DiscoverResult.Failed)?.error }
                    }, modifier = Modifier.testTag("$tag.wish").semantics {
                        contentDescription = wishLabel
                    })
                if (canReject) item.availability.libraryItems.forEachIndexed { index, ref -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.discover_reject)) }, leadingIcon = { Icon(DulcetIcons.Edit, null) },
                    onClick = { menuOpen = false; session.reject(item, ref, wish?.wish?.id) { if (lifetime.active) failure = (it as? DiscoverResult.Failed)?.error } },
                    modifier = Modifier.testTag("$tag.reject.$index")) }
            }
        }
    }
    val supporting: @Composable () -> Unit = {
        Column {
            item.artist?.let { Text(it, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            DiscoverAvailabilityText(item.availability, "$tag.availability", compact = card, showHeld = position == null)
            (failure ?: wish?.error)?.let { Text(stringResource(if (it.retryable) R.string.discover_waiting else R.string.discover_not_saved), color = MaterialTheme.colorScheme.error) }
        }
    }
    val cardGesture = when {
        !card -> Modifier
        open != null -> Modifier.combinedClickable(onClick = open, onLongClick = if (hasActions) ({ menuOpen = true }) else null)
        hasActions -> Modifier.pointerInput(item.id) { detectTapGestures(onLongPress = { menuOpen = true }) }
            .semantics { onLongClick(label = actionsLabel) { menuOpen = true; true } }
        else -> Modifier
    }
    Column(modifier.fillMaxWidth().testTag(tag).then(cardGesture)) {
        if (card) {
            Box(Modifier.fillMaxWidth()) {
                DiscoverArtwork(session, item.artworkId, item.title, null, Modifier.fillMaxWidth().aspectRatio(1f),
                    entity = item.artworkEntity(), loadTag = "$tag.artwork")
            }
            Spacer(Modifier.height(8.dp))
            // The same order and type as a Library album card: title, then a smaller artist line.
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    CompositionLocalProvider(
                        LocalTextStyle provides MaterialTheme.typography.bodySmall,
                        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { supporting() }
                    if (wish?.pending == true) DiscoverSyncStatus("$tag.sync")
                }
                actions()
            }
        } else {
            PhoneMediaRow(title = item.title, supporting = supporting,
                leading = { Box(Modifier.clearAndSetSemantics {}) {
                    if (position != null) Text(position.toString(), Modifier.width(32.dp), style = MaterialTheme.typography.bodyMedium)
                    else DiscoverArtwork(session, item.artworkId, item.title, 56.dp, entity = item.artworkEntity())
                } }, trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (wish?.pending == true) DiscoverSyncStatus("$tag.sync")
                        actions()
                    }
                }, modifier = Modifier.bleedKeyline(), onClick = open, titleMaxLines = 2)
        }
    }
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
    // On a shelf the row itself starts on the shelf's keyline, with no list padding of its own.
    if (card) Row(modifier.testTag(tag).clickable(onClick = open), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DiscoverArtwork(session, null, playlist.title, 56.dp, entity = ArtworkEntity.Playlist)
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
            ) { PlaylistMetadata(playlist) }
        }
    } else ListItem(headlineContent = { Text(playlist.title) }, supportingContent = { PlaylistMetadata(playlist) },
        leadingContent = { DiscoverArtwork(session, null, playlist.title, 56.dp, entity = ArtworkEntity.Playlist) },
        modifier = modifier.testTag(tag).clickable(onClick = open))
}

@Composable private fun PlaylistMetadata(value: DiscoverPlaylistSummary) {
    Text(listOfNotNull(value.creator, value.trackCount?.let { pluralStringResource(R.plurals.discover_playlist_songs, it, it) })
        .metadataLine(), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

@Composable
private fun ShelfNotices(tag: String, age: DiscoverAge?, error: DiscoverFailure?, loading: Boolean,
    empty: Boolean, emptyText: String, connection: String?, cards: Boolean = false, retry: () -> Unit) {
    if (loading && age == null) DiscoverLoadingRows("$tag.loading", cards,
        cardWidth = if (tag.endsWith(".publicPlaylists")) 280.dp else 160.dp)
    val ageLabel = age?.takeIf { it.stale || it.fromDeviceCache }?.let {
        discoverRelativeAge(it.sourceAgeSeconds ?: ((System.currentTimeMillis() - it.receivedAtMillis).coerceAtLeast(0) / 1000))
    }
    val offline = error?.code == "offline" || connection == "offline"
    when {
        offline -> Text((ageLabel?.let { stringResource(R.string.discover_shelves_offline_age, it) }
            ?: stringResource(R.string.discover_shelves_offline)).metadataBreak(), Modifier.testTag("$tag.age"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error == null && ageLabel != null -> Text(stringResource(R.string.discover_shelves_updated, ageLabel),
            Modifier.testTag("$tag.age"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (error != null) {
        val deadline = remember(error) { SystemClock.elapsedRealtime() + ((error.retryAtMillis ?: 0) - System.currentTimeMillis()).coerceAtLeast(0) }
        var now by remember(error) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(error) { while (now < deadline) { delay(1000); now = SystemClock.elapsedRealtime() } }
        // One line, the same quiet line as the offline state: nothing on the page is blocked. The
        // action joins it once a retry can run; until then the line itself says when.
        if (!offline) Row(Modifier.fillMaxWidth().testTag("$tag.error"), verticalAlignment = Alignment.CenterVertically) {
            val waiting = now < deadline
            Text(when {
                error.code == "rate-limited" || (error.retryable && waiting) -> stringResource(R.string.discover_busy, ceil((deadline - now).coerceAtLeast(0) / 60000.0).toInt())
                else -> (ageLabel?.let { stringResource(R.string.discover_shelves_failed_age, it) }
                    ?: stringResource(R.string.discover_shelves_failed)).metadataBreak()
            }, Modifier.weight(1f).testTag("$tag.error.message"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error.retryable && !waiting) TextButton(retry, Modifier.testTag("$tag.retry")) {
                Text(stringResource(R.string.discover_retry))
            }
        }
    } else if (empty && !loading) Text(emptyText, Modifier.testTag("$tag.empty"))
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
    TopAppBar(title = { Text(title) }, navigationIcon = {
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
@Composable private fun ShelfHeading(text: String) { Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
@Composable private fun ShelfSectionTitle(title: String, all: (() -> Unit)?, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (all != null) TextButton(all, Modifier.testTag(tag)) { Text(stringResource(R.string.discover_see_all)) }
    }
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
