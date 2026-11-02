package com.legitimateapps.dulcet

import android.os.SystemClock
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.ui.DulcetIcons
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Compass points distinguish Discover from Browse's catalog grid. */
internal val DiscoverCompass = androidx.compose.ui.graphics.vector.ImageVector.Builder("Discover", 24.dp, 24.dp, 24f, 24f)
    .addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(
        "M12 2L15 9L22 12L15 15L12 22L9 15L2 12L9 9Z").toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black))
    .build()

private val shelfIds = listOf("trendingArtists", "trendingAlbums", "trendingSongs", "newReleases", "similarArtists", "publicPlaylists")

@Composable
internal fun PhoneDiscoverHome(session: DiscoverWishlistSession, openShelf: (String) -> Unit,
    openPlaylists: () -> Unit, openPlaylist: (String) -> Unit, openWishlist: () -> Unit,
    openLibrary: (DiscoverLibraryRef) -> Unit, openArtist: (String) -> Unit, openAlbum: (String) -> Unit, openRecentWish: (String) -> Unit = { openWishlist() }) {
    val publication by session.publication.collectAsState()
    val current = publication ?: return
    if (!current.access.discoverAvailable) return
    LaunchedEffect(session) { session.refreshHome() }
    LaunchedEffect(session, current.access.canReadWishlist) {
        if (current.access.canReadWishlist) session.refreshWishlist(WishFilter())
    }
    val home = current.home
    Column(Modifier.fillMaxSize().testTag("discover.home")) {
        DiscoverShelfBar(stringResource(R.string.discover_home_title), refresh = { session.refreshHome() })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discover.home.list"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("credit") { ShelfCredit() }
            item("trending") { ShelfHeading(stringResource(R.string.discover_trending)) }
            for (id in shelfIds) item(id) {
                val state = home?.shelves?.firstOrNull { it.id == id }
                val page = state?.page
                val tag = "discover.shelf.$id"
                Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfSectionTitle(shelfTitle(id), if (page?.nextCursor != null) ({ if (id == "publicPlaylists") openPlaylists() else openShelf(id) }) else null, "$tag.all")
                    if (id == "similarArtists") Text(stringResource(R.string.discover_suggestions_credit), Modifier.testTag("discover.suggestionsCredit"), style = MaterialTheme.typography.bodySmall)
                    ShelfNotices(tag, page?.age, state?.error ?: page?.refreshError, state?.loading ?: (home == null),
                        page != null && page.items.isEmpty() && page.playlists.isEmpty(), shelfEmpty(id), current.connection) { session.refreshHome() }
                    LazyRow(Modifier.fillMaxWidth().testTag("$tag.items"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(page?.items.orEmpty(), key = { it.id }) { item ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                ShelfMusicItem(session, item, "$tag.item.${item.id}", WishSource("shelf", id), openLibrary, openArtist, openAlbum, Modifier.width(280.dp).padding(8.dp))
                            }
                        }
                        items(page?.playlists.orEmpty(), key = { it.id }) { playlist ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                ShelfPlaylistRow(playlist, "$tag.playlist.${playlist.id}", { openPlaylist(playlist.id) }, Modifier.width(280.dp))
                            }
                        }
                    }
                }
            }
            if (current.access.wishlistAvailable) {
                item("recent-title") { Column(Modifier.testTag("discover.recentWishes")) {
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
                        } }, modifier = Modifier.fillMaxWidth().testTag("discover.recentWishes.item.${row.key}").clickable { openRecentWish(row.key) })
                }
            }
        }
    }
}

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
    Column(Modifier.fillMaxSize()) {
        DiscoverShelfBar(shelfTitle(id), back)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(tag), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("credit") { ShelfCredit() }
            item("notices") { ShelfNotices("discover.shelf.$id", page?.age, reader.error ?: page?.refreshError,
                reader.loading, page != null && page.items.isEmpty(), shelfEmpty(id), publication?.connection, reader::refresh) }
            items(page?.items.orEmpty(), key = { it.id }) { item ->
                ShelfMusicItem(session, item, "discover.shelf.$id.item.${item.id}", WishSource("shelf", id), openLibrary, openArtist, openAlbum)
            }
            if (page?.nextCursor != null) item("more") { ShelfMore("$tag.more", reader.loading, reader::more) }
        }
    }
}

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
    Column(Modifier.fillMaxSize()) {
        DiscoverShelfBar(stringResource(R.string.discover_public_playlists), back)
        OutlinedTextField(field, { field = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("discover.playlists.query"),
            label = { Text(stringResource(R.string.discover_playlists_query)) }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (query == field) reader.refresh() else query = field }))
        TextButton({ if (query == field) reader.refresh() else query = field }, Modifier.align(Alignment.End).testTag("discover.playlists.submit")) { Text(stringResource(R.string.discover_playlists_submit)) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discover.playlists"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("credit") { ShelfCredit() }
            item("notices") { ShelfNotices("discover.playlists", page?.age, reader.error ?: page?.refreshError, reader.loading,
                page != null && page.items.isEmpty(), stringResource(R.string.discover_empty_playlists), publication?.connection, reader::refresh) }
            items(page?.items.orEmpty(), key = { it.id }) { playlist -> ShelfPlaylistRow(playlist, "discover.playlists.item.${playlist.id}", { openPlaylist(playlist.id) }) }
            if (page?.nextCursor != null) item("more") { ShelfMore("discover.playlists.more", reader.loading, reader::more) }
        }
    }
}

@Composable
internal fun PhoneDiscoverPlaylist(session: DiscoverWishlistSession, mbid: String, openLibrary: (DiscoverLibraryRef) -> Unit,
    openArtist: (String) -> Unit, openAlbum: (String) -> Unit, back: () -> Unit,
    memory: DiscoverBrowseMemory = remember(session) { DiscoverBrowseMemory() }) {
    var savedPages by rememberSaveable(session, mbid) { mutableIntStateOf(1) }
    val reader = remember(session, mbid, memory) { memory.playlist(mbid) }
    LaunchedEffect(reader.pagesLoaded) { if (reader.pagesLoaded > 0) savedPages = reader.pagesLoaded }
    DisposableEffect(reader, session) { reader.mount(savedPages) { cursor, done -> session.playlist(mbid, cursor, done) }; onDispose { reader.close() } }
    val publication by session.publication.collectAsState()
    val page = reader.value
    val tag = "discover.playlist.$mbid"
    Column(Modifier.fillMaxSize()) {
        DiscoverShelfBar(page?.summary?.title ?: stringResource(R.string.discover_public_playlists), back)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(tag), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("credit") { ShelfCredit() }
            page?.summary?.let { summary -> item("summary") { PlaylistMetadata(summary) } }
            item("notices") { ShelfNotices(tag, page?.age, reader.error ?: page?.refreshError, reader.loading,
                page != null && page.entries.isEmpty(), stringResource(R.string.discover_empty_playlist), publication?.connection, reader::refresh) }
            items(page?.entries.orEmpty(), key = { it.position }) { entry ->
                Column {
                    Text(entry.position.toString(), style = MaterialTheme.typography.labelMedium)
                    ShelfMusicItem(session, entry.item, "$tag.entry.${entry.position}", WishSource("playlist", mbid), openLibrary, openArtist, openAlbum)
                }
            }
            if (page?.nextCursor != null) item("more") { ShelfMore("$tag.more", reader.loading, reader::more) }
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
    modifier: Modifier = Modifier) {
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
    val lifetime = remember(session, tag) { ShelfActionLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.close() } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PhoneMediaRow(title = item.title, supporting = { item.artist?.let { Text(it) } },
            leading = { Box(Modifier.clearAndSetSemantics { }) {
                if (item.kind == "artist") Monogram(item.title, 48.dp) else DiscoverArtwork(session, item.artworkId, item.title, 48.dp)
            } }, modifier = Modifier.testTag(tag), onClick = open, titleMaxLines = Int.MAX_VALUE)
        DiscoverAvailabilityText(item.availability, "$tag.availability")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val explicitLocal = item.availability.libraryItems.firstOrNull { recognized && it.providerInstanceId == state?.providerInstanceId }
            if (explicitLocal != null) IconButton({ openLibrary(explicitLocal) }, Modifier.testTag("$tag.openLibrary")) {
                Icon(DulcetIcons.LibraryMusic, stringResource(R.string.discover_open_library_named, item.title))
            }
            if (recognized && wish != null && state?.access?.canRemoveWish == true) IconButton({
                lifetime.own(session.remove(wish.key) { if (lifetime.active) failure = (it as? DiscoverResult.Failed)?.error })
            }, Modifier.testTag("$tag.wish")) { Icon(DulcetIcons.Wish, stringResource(R.string.discover_remove_wish_named, item.title)) }
            else if (recognized && item.wishable && item.target != null && state?.access?.canAddWish == true) IconButton({
                lifetime.own(session.add(item, source) { if (lifetime.active) failure = (it as? DiscoverResult.Failed)?.error })
            }, Modifier.testTag("$tag.wish")) { Icon(DulcetIcons.WishBorder, stringResource(R.string.discover_shelves_wish, item.title)) }
            if (recognized && state?.access?.canRejectMatch == true && item.availability.libraryItems.isNotEmpty()) DiscoverRejectMenu(session, item) { failure = it }
        }
        if (wish?.pending == true) Text(stringResource(R.string.discover_waiting))
        (failure ?: wish?.error)?.let { Text(stringResource(if (it.retryable) R.string.discover_waiting else R.string.discover_not_saved), color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
    }
}

private class ShelfActionLifetime {
    var active = true; private val operations = mutableListOf<DiscoverOperation>()
    fun own(operation: DiscoverOperation) { if (active) operations += operation else operation.cancel() }
    fun close() { active = false; operations.forEach { it.cancel() }; operations.clear() }
}

@Composable
private fun ShelfPlaylistRow(playlist: DiscoverPlaylistSummary, tag: String, open: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(headlineContent = { Text(playlist.title) }, supportingContent = { PlaylistMetadata(playlist) },
        leadingContent = { Box(Modifier.clearAndSetSemantics { }) { Icon(DulcetIcons.LibraryMusic, null) } },
        modifier = modifier.testTag(tag).clickable(onClick = open))
}

@Composable private fun PlaylistMetadata(value: DiscoverPlaylistSummary) {
    Column {
        value.creator?.let { Text(it) }
        value.trackCount?.let { Text(pluralStringResource(R.plurals.discover_playlist_songs, it, it)) }
    }
}

@Composable
private fun ShelfNotices(tag: String, age: DiscoverAge?, error: DiscoverFailure?, loading: Boolean,
    empty: Boolean, emptyText: String, connection: String?, retry: () -> Unit) {
    if (loading) CircularProgressIndicator(Modifier.testTag("$tag.loading"))
    age?.let {
        // Elapsed time advances age within this presentation. Persisted wall time is provenance only.
        val initial = remember(it) { SystemClock.elapsedRealtime() }
        val sourceAge = it.sourceAgeSeconds
        Column(Modifier.testTag("$tag.age")) {
            if (it.stale || it.fromDeviceCache) Text(stringResource(R.string.discover_cached))
            Text(sourceAge?.let { seconds -> stringResource(R.string.discover_age, seconds + (SystemClock.elapsedRealtime() - initial).coerceAtLeast(0) / 1000) }
                ?: stringResource(R.string.discover_shelves_age_unknown), style = MaterialTheme.typography.bodySmall)
            if (connection != "online") Text(stringResource(R.string.wishlist_offline))
        }
    }
    if (error != null) {
        val deadline = remember(error) { SystemClock.elapsedRealtime() + ((error.retryAtMillis ?: 0) - System.currentTimeMillis()).coerceAtLeast(0) }
        var now by remember(error) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(error) { while (now < deadline) { delay(1000); now = SystemClock.elapsedRealtime() } }
        Column(Modifier.testTag("$tag.error")) {
            Text(when {
                error.code == "rate-limited" -> stringResource(R.string.discover_busy, ceil((deadline - now).coerceAtLeast(0) / 60000.0).toInt())
                error.code == "offline" || connection == "offline" -> stringResource(R.string.discover_shelves_offline)
                else -> stringResource(R.string.discover_shelves_failed)
            }, color = MaterialTheme.colorScheme.error)
            TextButton(retry, Modifier.testTag("$tag.retry"), enabled = error.retryable && now >= deadline) { Text(stringResource(R.string.discover_retry)) }
        }
    } else if (empty && !loading) Text(emptyText, Modifier.testTag("$tag.empty"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverShelfBar(title: String, back: (() -> Unit)? = null, refresh: (() -> Unit)? = null) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        if (back != null) IconButton(back, Modifier.testTag("discover.back")) { Icon(DulcetIcons.ArrowBack, stringResource(R.string.discover_pages_back)) }
    }, actions = { if (refresh != null) IconButton(refresh, Modifier.testTag("discover.home.refresh")) { Icon(DulcetIcons.Refresh, stringResource(R.string.discover_home_refresh)) } })
}
@Composable private fun ShelfHeading(text: String) { Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
@Composable private fun ShelfSectionTitle(title: String, all: (() -> Unit)?, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { ShelfHeading(title) }
        if (all != null) TextButton(all, Modifier.testTag(tag)) { Text(stringResource(R.string.discover_see_all)) }
    }
}
@Composable private fun ShelfCredit() { Text(stringResource(R.string.discover_credit), Modifier.testTag("discover.credit"), style = MaterialTheme.typography.bodySmall) }
@Composable private fun ShelfMore(tag: String, loading: Boolean, more: () -> Unit) { TextButton(more, Modifier.testTag(tag), enabled = !loading) { Text(stringResource(R.string.discover_more)) } }
@Composable private fun shelfTitle(id: String) = stringResource(when (id) {
    "trendingArtists" -> R.string.discover_artists; "trendingAlbums" -> R.string.discover_albums; "trendingSongs" -> R.string.discover_songs
    "newReleases" -> R.string.discover_new_releases; "similarArtists" -> R.string.discover_similar_artists; else -> R.string.discover_public_playlists
})
@Composable private fun shelfEmpty(id: String) = stringResource(when (id) {
    "trendingArtists" -> R.string.discover_empty_trending_artists; "trendingAlbums" -> R.string.discover_empty_trending_albums; "trendingSongs" -> R.string.discover_empty_trending_songs
    "newReleases" -> R.string.discover_empty_releases; "similarArtists" -> R.string.discover_empty_similar; else -> R.string.discover_empty_playlists
})
