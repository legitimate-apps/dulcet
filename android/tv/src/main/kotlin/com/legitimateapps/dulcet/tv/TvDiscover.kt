package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.legitimateapps.dulcet.DiscoverBrowseMemory
import com.legitimateapps.dulcet.DiscoverPagedReader
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchPresenter
import java.text.DateFormat
import java.util.Date

internal const val ROUTE_DISCOVER = "discover"
internal const val ROUTE_WISHLIST = "wishlist"
internal val LocalTvDiscover = staticCompositionLocalOf<DiscoverWishlistSession?> { null }
private val shelfNames = linkedMapOf("trendingArtists" to "Artists", "trendingAlbums" to "Albums", "trendingSongs" to "Songs",
    "newReleases" to "New releases", "similarArtists" to "Artists like yours", "publicPlaylists" to "Public playlists")

@Composable
private fun DiscoverButton(label: String, tag: String, enabled: Boolean = true, selected: Boolean = false, focusTag: String = tag, spokenLabel: String = label, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick = action, enabled = enabled,
        colors = if (selected) ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.colors(),
        modifier = Modifier.tvFocus(focusTag, default = tag.endsWith(".back"), semanticsTag = tag).onFocusChanged { focused = it.hasFocus }
            .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(50)) else Modifier)
            .semantics { contentDescription = spokenLabel; this.selected = selected }) { Text(label) }
}

@Composable
private fun Heading(text: String) { Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }

/** The route owns reads; wish intentions remain owned by the account's durable outbox. */
private class TvDiscoverLifetime {
    var active = true
    private val operations = mutableListOf<DiscoverOperation>()
    fun own(operation: DiscoverOperation) { if (active) operations += operation else operation.cancel() }
    fun close() { active = false; operations.forEach { it.cancel() }; operations.clear() }
}

@Composable
private fun Notices(age: DiscoverAge?, error: DiscoverFailure?, loading: Boolean, empty: Boolean, retry: () -> Unit) {
    var retryReady by remember(error?.retryAtMillis) { mutableStateOf(error?.retryAtMillis?.let { it <= System.currentTimeMillis() } != false) }
    LaunchedEffect(error?.retryAtMillis) {
        error?.retryAtMillis?.let { deadline ->
            kotlinx.coroutines.delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
            retryReady = true
        }
    }
    if (loading) Text("Loading…", Modifier.testTag("discover.loading"))
    if (age?.stale == true || age?.fromDeviceCache == true) Text(age.sourceAgeSeconds?.let { "Saved data · $it seconds old" } ?: "Saved data", Modifier.testTag("discover.stale"))
    if (error != null) {
        val message = when(error.code) {
            "offline" -> "You're offline. Saved data is shown."
            "rate-limited" -> "The source is busy. Try again later."
            "forbidden" -> "This account does not have permission."
            else -> "Couldn't refresh. Saved data is still shown."
        }
        Text(message, Modifier.testTag("discover.error"))
        error.retryAtMillis?.let { Text("Retry after ${DateFormat.getTimeInstance().format(Date(it))}") }
        DiscoverButton("Retry", "discover.retry", retryReady, action = retry)
    }
    if (empty && !loading && error == null) Text("Nothing here yet.", Modifier.testTag("discover.empty"))
}

@Composable
private fun DiscoverList(title: String, tag: String, navigator: TvNavigator, initialPosition: Int? = null, manageRoute: Boolean = true, content: LazyListScope.() -> Unit) {
    if (manageRoute) EnterRoute()
    val list = rememberLazyListState()
    LaunchedEffect(initialPosition) { if (initialPosition != null) list.scrollToItem(initialPosition) }
    // Stable lazy keys preserve scrolling; restore the action before asking route memory for focus.
    LazyColumn(Modifier.fillMaxSize().testTag(tag), state = list,
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(if (tag == "discover.home") 12.dp else 20.dp)) {
        if (tag != "discover.home") {
        item("heading") { Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            DiscoverButton("Back", "$tag.back", action = navigator.back); Heading(title)
        } }
        item("credit") { Text("Data from MusicBrainz and ListenBrainz", Modifier.testTag("discover.credit"), style = MaterialTheme.typography.bodyMedium) }
        }
        content()
    }
}

internal fun tvDiscoverLibraryRoute(item: DiscoverItem, provider: String?): DiscoverLibraryRef? =
    item.availability.libraryItems.singleOrNull()?.takeIf { it.providerInstanceId == provider && it.type == item.kind &&
        item.availability.status == "held" && (item.kind != "album" || item.availability.completeness == "known") }

private fun openLocal(ref: DiscoverLibraryRef, account: SearchAccount, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    if (ref.providerInstanceId != account.providerInstanceId) return
    when (ref.type) {
        "artist" -> navigator.openArtist(ref.id)
        "album" -> navigator.openAlbum(ref.id)
        "song", "track" -> playback?.playSong(ref.providerInstanceId, ref.id, "Library song")
    }
}

@Composable
private fun TvDiscoverArtwork(session: DiscoverWishlistSession, item: DiscoverItem, compact: Boolean = false, modifier: Modifier = Modifier) {
    var image by remember(session, item.artworkId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    DisposableEffect(session, item.artworkId) {
        val lifetime = TvDiscoverLifetime()
        item.artworkId?.let { id -> lifetime.own(session.artwork(id) { result ->
            if (lifetime.active && result is DiscoverResult.Value) image = runCatching {
                android.graphics.BitmapFactory.decodeByteArray(result.value, 0, result.value.size)?.asImageBitmap()
            }.getOrNull()
        }) }
        onDispose(lifetime::close)
    }
    Box(modifier.size(if (compact) 96.dp else 140.dp).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(item.title.take(1).uppercase(), style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun TvDiscoverItem(session: DiscoverWishlistSession, item: DiscoverItem, tag: String, source: WishSource,
    account: SearchAccount, playback: AndroidPlaybackController?, navigator: TvNavigator, modifier: Modifier = Modifier, openMetadata: Boolean = true, compact: Boolean = false) {
    val publication by session.publication.collectAsState()
    val lifetime = remember(session, item.id) { TvDiscoverLifetime() }
    DisposableEffect(lifetime) { onDispose(lifetime::close) }
    var error by remember(session, item.id) { mutableStateOf<DiscoverFailure?>(null) }
    val wish = publication?.wishes?.firstOrNull { item.target != null && (it.wish?.target ?: it.pendingAdd?.target) == item.target }
    val local = tvDiscoverLibraryRoute(item, publication?.providerInstanceId)
    Column(modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (item.kind != "song") TvDiscoverArtwork(session, item, compact, Modifier.align(Alignment.CenterHorizontally))
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        Text(listOfNotNull(item.artist, item.releaseDate ?: item.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        Text(when(item.availability.status) {
            "held" -> "In your library"
            "partlyHeld" -> "Partly in your library · ${item.availability.matchedTrackCount ?: 0} of ${item.availability.totalTrackCount ?: "?"} tracks"
            else -> "Not in your library"
        }, Modifier.testTag("$tag.availability"), style = MaterialTheme.typography.bodyMedium)
        val primaryActions: @Composable () -> Unit = {
        if (local != null) DiscoverButton(if (compact) "Open Library" else "Open ${item.title} in Library", "$tag.open", spokenLabel = "Open ${item.title} in Library") { openLocal(local, account, playback, navigator) }
        else if (openMetadata && item.target != null && item.kind in listOf("artist", "album")) DiscoverButton(if (compact) "Open" else "Open ${item.title}", "$tag.open", spokenLabel = "Open ${item.title}") {
            navigator.open("discover.${item.kind}:" + checkNotNull(item.target).mbid)
        }
        val remove = wish != null && publication?.access?.canRemoveWish == true
        if (remove || (publication?.access?.canAddWish == true && item.wishable && item.target != null && item.availability.status != "held")) {
            DiscoverButton(if (compact) { if (remove) "Remove wish" else "Wish" } else { if (remove) "Remove wish for ${item.title}" else "Wish for ${item.title}" }, "discover.wish.${item.id}", focusTag = "$tag.wish", spokenLabel = if (remove) "Remove wish for ${item.title}" else "Wish for ${item.title}") {
                error = null
                val done: (DiscoverResult<WishEnqueued>) -> Unit = { if (lifetime.active) error = (it as? DiscoverResult.Failed)?.error }
                lifetime.own(if (remove) session.remove(checkNotNull(wish).key, done) else session.add(item, source, done))
            }
        }
        }
        if (compact && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { primaryActions() }
        else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { primaryActions() }
        // Partial and ambiguous matches stay metadata pages; each scoped library match is explicit.
        if (local == null) item.availability.libraryItems.filter { it.providerInstanceId == account.providerInstanceId }.forEachIndexed { i, ref ->
            DiscoverButton("Open library match ${i + 1}", "$tag.library.$i") { openLocal(ref, account, playback, navigator) }
        }
        if (publication?.access?.canRejectMatch == true) item.availability.libraryItems.filter { it.providerInstanceId == account.providerInstanceId }.forEachIndexed { i, ref ->
            DiscoverButton("Wrong match for ${item.title}", "$tag.reject.$i") {
                lifetime.own(session.reject(item, ref, wish?.wish?.id) { if (lifetime.active) error = (it as? DiscoverResult.Failed)?.error })
            }
        }
        if (wish?.pending == true || (error ?: wish?.error)?.retryable == true) Text("Waiting to sync", Modifier.testTag("$tag.pending"))
        if ((error ?: wish?.error)?.retryable == false) Text("Wish not saved. Try again.", Modifier.testTag("$tag.error"))
    }
}

@Composable
internal fun TvWishlistEntry(session: DiscoverWishlistSession, navigator: TvNavigator) {
    val publication by session.publication.collectAsState()
    if (publication?.access?.wishlistAvailable == true) DiscoverButton("Wishlist", "wishlist.open") { navigator.open(ROUTE_WISHLIST) }
}

@Composable
internal fun TvDiscoverRoute(session: DiscoverWishlistSession, memory: DiscoverBrowseMemory, route: String,
    account: SearchAccount, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    val publication by session.publication.collectAsState()
    if (route == ROUTE_WISHLIST || route.startsWith("wishlist:")) { TvWishlist(session, account, playback, navigator, route.substringAfter(':', "").takeIf { it.isNotEmpty() }); return }
    if (publication?.access?.discoverAvailable != true) return
    when {
        route == ROUTE_DISCOVER -> TvDiscoverHome(session, account, playback, navigator)
        route.startsWith("discover.shelf:") -> {
            val id = route.substringAfter(':'); val reader = remember(memory, id) { memory.shelf(id) }
            MountReader(reader) { cursor, done -> session.shelf(id, cursor, done) }
            val page = reader.value
            DiscoverList(shelfNames[id] ?: id, "discover.shelf.surface", navigator) {
                item { Notices(page?.age, reader.error ?: page?.refreshError, reader.loading, page?.items?.isEmpty() == true, reader::refresh) }
                itemsIndexed(page?.items.orEmpty(), key = { _, item -> item.id }) { index, item -> TvDiscoverItem(session, item, "discover.item.$index", WishSource("shelf", id), account, playback, navigator) }
                if (page?.nextCursor != null) item { DiscoverButton("Load more", "discover.shelf.more", !reader.loading, action = reader::more) }
            }
        }
        route == "discover.playlists" -> TvDiscoverPlaylists(session, memory, navigator)
        route.startsWith("discover.playlist:") -> {
            val id = route.substringAfter(':'); val reader = remember(memory, id) { memory.playlist(id) }
            MountReader(reader) { cursor, done -> session.playlist(id, cursor, done) }
            val page = reader.value
            DiscoverList(page?.summary?.title ?: "Public playlist", "discover.playlist.surface", navigator) {
                item { Notices(page?.age, reader.error ?: page?.refreshError, reader.loading, page?.entries?.isEmpty() == true, reader::refresh) }
                items(page?.entries.orEmpty(), key = { it.position }) { entry -> Column {
                    Text("${entry.position}", Modifier.testTag("discover.playlist.position.${entry.position}"))
                    TvDiscoverItem(session, entry.item, "discover.playlist.item.${entry.position}", WishSource("playlist", id), account, playback, navigator)
                } }
                if (page?.nextCursor != null) item { DiscoverButton("Load more", "discover.playlist.more", !reader.loading, action = reader::more) }
            }
        }
        route.startsWith("discover.artist:") || route.startsWith("discover.album:") -> TvDiscoverMetadata(session, route, account, playback, navigator)
    }
}

@Composable
private fun <T> MountReader(reader: DiscoverPagedReader<T>, read: (String?, (DiscoverResult<T>) -> Unit) -> DiscoverOperation) {
    var pages by rememberSaveable(reader) { mutableIntStateOf(1) }
    LaunchedEffect(reader.pagesLoaded) { if (reader.pagesLoaded > 0) pages = reader.pagesLoaded }
    DisposableEffect(reader) { reader.mount(pages, read); onDispose(reader::close) }
}

@Composable
private fun TvDiscoverHome(session: DiscoverWishlistSession, account: SearchAccount, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    val publication by session.publication.collectAsState()
    LaunchedEffect(session) { session.refreshHome(); session.refreshWishlist(WishFilter()) }
    DiscoverList("Discover", "discover.home", navigator) {
        item("credit") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Data from MusicBrainz and ListenBrainz", Modifier.weight(1f).testTag("discover.credit"), style = MaterialTheme.typography.bodyMedium)
            DiscoverButton("Refresh", "discover.refresh", spokenLabel = "Refresh Discover", action = session::refreshHome)
        } }
        for ((id, title) in shelfNames) item(id) {
            val shelf = publication?.home?.shelves?.firstOrNull { it.id == id }; val page = shelf?.page
            Column(Modifier.testTag("discover.shelf.$id"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (id.startsWith("trending")) "Trending on ListenBrainz · $title" else title,
                        Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    DiscoverButton("See all", "discover.shelf.$id.all", spokenLabel = "See all $title") { navigator.open(if (id == "publicPlaylists") "discover.playlists" else "discover.shelf:$id") }
                }
                if (id == "similarArtists") Text("Suggestions from ListenBrainz, based on artists in your library")
                Notices(page?.age, shelf?.error ?: page?.refreshError, shelf?.loading ?: true,
                    page != null && page.items.isEmpty() && page.playlists.isEmpty(), session::refreshHome)
                LazyRow(Modifier.testTag("discover.shelf.$id.items"), horizontalArrangement = Arrangement.spacedBy(32.dp), contentPadding = PaddingValues(8.dp)) {
                    items(page?.items.orEmpty(), key = { it.id }) { item ->
                        TvDiscoverItem(session, item, "discover.shelf.$id.item.${item.id}", WishSource("shelf", id), account, playback, navigator,
                            Modifier.width(280.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), compact = true)
                    }
                    items(page?.playlists.orEmpty(), key = { it.id }) { playlist -> Column(Modifier.width(280.dp).padding(12.dp)) {
                        Text(playlist.title); playlist.creator?.let { Text(it) }
                        DiscoverButton("Open ${playlist.title}", "discover.playlist.${playlist.id}") { navigator.open("discover.playlist:" + playlist.id) }
                    } }
                }
            }
        }
        item { Heading("Recent wishes"); TvWishlistEntry(session, navigator) }
        items(publication?.home?.recentWishes.orEmpty(), key = { it.key }) { row ->
            DiscoverButton(row.wish?.snapshot?.title ?: row.pendingAdd?.snapshot?.title ?: "Wish", "discover.recent.${row.key}") { navigator.open("wishlist:" + row.key) }
        }
    }
}

@Composable
private fun TvDiscoverPlaylists(session: DiscoverWishlistSession, memory: DiscoverBrowseMemory, navigator: TvNavigator) {
    var field by rememberSaveable { mutableStateOf("") }; var query by rememberSaveable { mutableStateOf("") }
    val reader = remember(memory, query) { memory.list(query) }; MountReader(reader) { cursor, done -> session.playlists(query, cursor, done) }
    val page = reader.value
    DiscoverList("Public playlists", "discover.playlists.surface", navigator) {
        item { TvDiscoverQuery(field, { field = it }, "discover.playlists.query") { if (query == field) reader.refresh() else query = field } }
        item { DiscoverButton("Search playlists", "discover.playlists.submit") { if (query == field) reader.refresh() else query = field } }
        item { Notices(page?.age, reader.error ?: page?.refreshError, reader.loading, page?.items?.isEmpty() == true, reader::refresh) }
        items(page?.items.orEmpty(), key = { it.id }) { playlist ->
            DiscoverButton("Open ${playlist.title}", "discover.playlist.${playlist.id}") { navigator.open("discover.playlist:" + playlist.id) }
        }
        if (page?.nextCursor != null) item { DiscoverButton("Load more", "discover.playlists.more", !reader.loading, action = reader::more) }
    }
}

@Composable
private fun TvDiscoverMetadata(session: DiscoverWishlistSession, route: String, account: SearchAccount, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    LaunchedEffect(session) { if (session.publication.value?.access?.canReadWishlist == true) session.refreshWishlist(WishFilter()) }
    val id = route.substringAfter(':'); val artist = route.startsWith("discover.artist:")
    var release by rememberSaveable(route) { mutableStateOf<String?>(null) }
    var header by remember(route) { mutableStateOf<DiscoverItem?>(null) }
    var editions by remember(route) { mutableStateOf(emptyList<DiscoverEdition>()) }
    val reader = remember(session, route, release) { DiscoverPagedReader<DiscoverPage>({ it.nextCursor }, { a, b -> b.copy(items = (a.items + b.items).distinctBy { it.id }) }) }
    val lifetime = remember(reader) { TvDiscoverLifetime() }
    DisposableEffect(lifetime) { onDispose(lifetime::close) }
    var selectedRelease by remember(route) { mutableStateOf<String?>(null) }
    MountReader(reader) { cursor, done ->
        if (cursor != null) {
            if (artist) session.artistAlbums(id, cursor, done) else session.albumTracks(id, selectedRelease.orEmpty(), cursor, done)
        } else if (artist) session.artist(id) { result -> if (lifetime.active) when(result) {
            is DiscoverResult.Value -> { header = result.value.item; done(DiscoverResult.Value(result.value.albums)) }
            is DiscoverResult.Failed -> done(result)
        } } else session.album(id, release) { result -> if (lifetime.active) when(result) {
            is DiscoverResult.Value -> { header = result.value.item; editions = result.value.editions; selectedRelease = result.value.selectedReleaseId; done(DiscoverResult.Value(result.value.tracks)) }
            is DiscoverResult.Failed -> done(result)
        } }
    }
    DiscoverList(header?.title ?: if (artist) "Artist" else "Album", if (artist) "discover.artist.surface" else "discover.album.surface", navigator) {
        header?.let { item { TvDiscoverItem(session, it, "discover.header", WishSource(if (artist) "artist" else "album", id), account, playback, navigator, openMetadata = false) } }
        if (!artist) items(editions, key = { it.releaseId }) { edition ->
            DiscoverButton("Edition ${edition.releaseDate ?: edition.releaseId} · ${edition.trackCount ?: "?"} tracks", "discover.edition.${edition.releaseId}") { release = edition.releaseId }
        }
        item { Notices(reader.value?.age, reader.error, reader.loading, reader.value?.items?.isEmpty() == true, reader::refresh) }
        itemsIndexed(reader.value?.items.orEmpty(), key = { i, item -> "$i:${item.id}" }) { index, item ->
            TvDiscoverItem(session, item, "discover.item.$index", WishSource(if (artist) "artist" else "album", id), account, playback, navigator)
        }
        if (reader.value?.nextCursor != null) item { DiscoverButton("Load more", "discover.metadata.more", !reader.loading, action = reader::more) }
    }
}

@Composable
internal fun TvDiscoverQuery(value: String, change: (String) -> Unit, tag: String, submit: () -> Unit) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val navFocus = LocalTvNavFocus.current
    val manager = androidx.compose.ui.platform.LocalFocusManager.current
    val editorFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var editorWasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            editorFocus.requestFocus()
            keyboard?.show()
        }
    }
    if (!editing) {
        // A TV button is a reliable remote focus target even when the input service is closed.
        Button(onClick = { editorWasFocused = false; editing = true }, modifier = Modifier.fillMaxWidth().tvFocus(tag)
            .semantics { contentDescription = "Search query. Select to edit." }) {
            Text(value.ifBlank { "Search query" }, style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        BasicTextField(value, change, modifier = Modifier.fillMaxWidth()
            .tvFocus(tag)
            .focusRequester(editorFocus)
            .onFocusChanged {
                if (it.isFocused) editorWasFocused = true
                else if (editorWasFocused) { editing = false; keyboard?.hide() }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionCenter, Key.Enter -> { keyboard?.show(); true }
                    Key.DirectionUp -> { runCatching { navFocus?.requestFocus() }; true }
                    Key.DirectionDown -> { manager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
                    else -> false
                }
            }
            .border(3.dp, MaterialTheme.colorScheme.primary).padding(16.dp)
            .semantics { contentDescription = "Search query" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                submit(); keyboard?.hide(); manager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
            }))
    }
}

@Composable
internal fun TvSearchScreen(presenter: SearchPresenter, account: SearchAccount? = null, playback: AndroidPlaybackController? = null, onActivate: (SearchResultItem) -> Unit) {
    // The search route persists while scopes change. Its children do not re-enter focus memory.
    EnterRoute()
    val session = LocalTvDiscover.current
    val publication by (session?.publication ?: remember { kotlinx.coroutines.flow.MutableStateFlow<DiscoverPublication?>(null) }).collectAsState()
    var everywhere by rememberSaveable(session) { mutableStateOf(false) }
    val navigator = LocalTvDiscoverNavigator.current
    if (publication?.access?.discoverAvailable != true || session == null || account == null || navigator == null) {
        TvCatalogSearchScreen(presenter, account, playback, manageRoute = false, onActivate = onActivate); return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 56.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            DiscoverButton("My server", "discover.scope.server", selected = !everywhere) { everywhere = false }
            DiscoverButton("Everywhere", "discover.scope.everywhere", selected = everywhere) { everywhere = true }
        }
        Box(Modifier.weight(1f)) {
            if (!everywhere) TvCatalogSearchScreen(presenter, account, playback, manageRoute = false, onActivate = onActivate)
            else TvDiscoverSearch(session, account, playback, navigator)
        }
    }
}
internal val LocalTvDiscoverNavigator = staticCompositionLocalOf<TvNavigator?> { null }

@Composable
internal fun TvDiscoverSearch(session: DiscoverWishlistSession, account: SearchAccount, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    LaunchedEffect(session) { if (session.publication.value?.access?.canReadWishlist == true) session.refreshWishlist(WishFilter()) }
    val publication by session.publication.collectAsState()
    var field by rememberSaveable { mutableStateOf(publication?.query.orEmpty()) }
    DiscoverList("Search Everywhere", "discover.search", navigator, manageRoute = false) {
        item { TvDiscoverQuery(field, { field = it }, "discover.query") { session.setQuery(field) } }
        item { DiscoverButton("Search Everywhere", "discover.submit", publication?.access?.canSearch == true) { session.setQuery(field) } }
        for (group in publication?.groups.orEmpty()) {
            item("heading:${group.kind}") { Heading(group.kind.replaceFirstChar { it.uppercase() }); Notices(group.page?.age, group.error, group.loading, group.page?.items?.isEmpty() == true) { session.setQuery(field) } }
            itemsIndexed(group.page?.items.orEmpty(), key = { i, item -> "${group.kind}:$i:${item.id}" }) { index, item ->
                TvDiscoverItem(session, item, "discover.search.${group.kind}.$index", WishSource("search", null), account, playback, navigator)
            }
            if (group.page?.nextCursor != null) item { DiscoverButton("More ${group.kind}", "discover.more.${group.kind}") { session.loadMore(group.kind) } }
        }
    }
}

@Composable
internal fun TvWishlist(session: DiscoverWishlistSession, account: SearchAccount, playback: AndroidPlaybackController?, navigator: TvNavigator, initialRowKey: String? = null) {
    val publication by session.publication.collectAsState()
    val current = publication ?: return
    if (!current.access.wishlistAvailable) return
    var kind by rememberSaveable { mutableStateOf<String?>(null) }; var state by rememberSaveable { mutableStateOf<String?>(null) }
    var history by rememberSaveable { mutableStateOf(false) }
    val filter = WishFilter(kind, state, history)
    LaunchedEffect(session, filter, current.access.canReadWishlist) { if (current.access.canReadWishlist) session.refreshWishlist(filter) }
    val lifetime = remember(session) { TvDiscoverLifetime() }; DisposableEffect(lifetime) { onDispose(lifetime::close) }
    var error by remember { mutableStateOf<DiscoverFailure?>(null) }
    DiscoverList("Wishlist", "wishlist.surface", navigator,
        initialPosition = initialRowKey?.let { key -> current.wishes.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.plus(if (current.connection == "online") 7 else 8) }) {
        item { DiscoverButton("Refresh Wishlist", "wishlist.refresh", current.access.canReadWishlist) { session.refreshWishlist(filter) } }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(listOf("all", "artist", "album", "song")) { value -> DiscoverButton(if (value == "all") "All kinds" else value, "wishlist.kind.$value", selected = (kind ?: "all") == value) { kind = value.takeUnless { it == "all" } } }
        } }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(listOf("all", "wanted", "requested", "searching", "unavailable", "fulfilled")) { value -> DiscoverButton(if (value == "all") "All states" else if (value == "fulfilled") "Arrived" else value.replaceFirstChar { it.uppercase() }, "wishlist.state.$value", selected = (state ?: "all") == value) { state = value.takeUnless { it == "all" } } }
        } }
        item { DiscoverButton(if (history) "Hide older arrivals" else "Show older arrivals", "wishlist.history") { history = !history } }
        if (current.connection != "online") item { Text("You're offline. Wishes will sync when connected.", Modifier.testTag("wishlist.offline")) }
        error?.let { failure -> item { Text(if (failure.retryable) "Waiting to sync" else "Wish not saved. Try again.", Modifier.testTag("wishlist.mutation.error")) } }
        item { Notices(null, current.wishlistError, current.wishlistLoading, current.wishlistSettled && current.wishes.isEmpty()) { session.refreshWishlist(filter) } }
        itemsIndexed(current.wishes, key = { _, row -> row.key }) { index, row ->
            val wish = row.wish; val snapshot = wish?.snapshot ?: row.pendingAdd?.snapshot
            Column(Modifier.testTag("wishlist.row.$index"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(snapshot?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                snapshot?.artist?.let { Text(it) }
                Text(if (wish?.state == "fulfilled") "Arrived" else (wish?.state ?: "wanted").replaceFirstChar { it.uppercase() }, Modifier.testTag("wishlist.row.state.$index"))
                if (row.pending || row.error?.retryable == true) Text("Waiting to sync")
                if (row.error?.retryable == false) Text("Wish not saved. Try again.")
                wish?.let { Text("Added ${DateFormat.getDateTimeInstance().format(Date(it.addedAtMillis))}") }
                wish?.fulfilledAtMillis?.let { Text("Arrived ${DateFormat.getDateTimeInstance().format(Date(it))}") }
                wish?.note?.let { Text(it) }
                if (wish?.state == "fulfilled") wish.libraryItem?.let { ref ->
                    DiscoverButton("Open ${snapshot?.title} in Library", "wishlist.arrival.$index") { openLocal(ref, account, playback, navigator) }
                }
                if (wish?.state == "fulfilled" && wish.target != null && current.access.canRejectMatch) wish.libraryItem?.let { ref ->
                    DiscoverButton("Wrong match for ${snapshot?.title}", "wishlist.reject.$index") {
                        val item = DiscoverItem(wish.id, wish.kind, wish.target, wish.snapshot.title, wish.snapshot.artist,
                            wish.snapshot.year, null, wish.snapshot.artworkId, null, null, null, null, emptyList(), true, wish.availability)
                        lifetime.own(session.reject(item, ref, wish.id) { if (lifetime.active) error = (it as? DiscoverResult.Failed)?.error })
                    }
                }
                if (current.access.canRemoveWish) DiscoverButton("Remove wish for ${snapshot?.title}", "wishlist.remove.$index") {
                    lifetime.own(session.remove(row.key) { if (lifetime.active) error = (it as? DiscoverResult.Failed)?.error })
                }
            }
        }
    }
}
