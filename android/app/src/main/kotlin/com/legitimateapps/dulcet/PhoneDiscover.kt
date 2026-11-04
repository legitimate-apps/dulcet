package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.offset
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity
import java.net.URI
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** The existing search presenter stays mounted only while Your Server is selected. Its query lives
 * above this screen in PhoneApp; external search always uses the account's core facade. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneSearchScopes(
    session: DiscoverWishlistSession,
    server: @Composable () -> Unit,
    openLibrary: (DiscoverLibraryRef) -> Unit,
    openArtist: (String) -> Unit,
    openAlbum: (String) -> Unit,
) {
    val publication by session.publication.collectAsState()
    var everywhere by rememberSaveable(session) { mutableStateOf(false) }
    val available = publication?.access?.discoverAvailable == true
    Column(Modifier.fillMaxSize()) {
        val scopeControls: @Composable () -> Unit = {
            if (available) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!everywhere, { everywhere = false }, { Text(stringResource(R.string.discover_scope_server)) }, Modifier.testTag("discover.scope.server"),
                        leadingIcon = { if (!everywhere) Icon(DulcetIcons.Check, null) })
                    FilterChip(everywhere, { everywhere = true }, { Text(stringResource(R.string.discover_scope_everywhere)) }, Modifier.testTag("discover.scope.everywhere"),
                        leadingIcon = { if (everywhere) Icon(DulcetIcons.Check, null) })
                }
            } else {
                // The one scope this server has: the same chip on the same keyline, shown selected.
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    FilterChip(true, {}, { Text(stringResource(R.string.discover_scope_server)) },
                        Modifier.testTag("discover.scope.server.label"),
                        leadingIcon = { Icon(DulcetIcons.Check, null) })
                }
            }
        }
        CompositionLocalProvider(LocalPhoneSearchScopes provides scopeControls) {
            Box(Modifier.weight(1f)) {
                if (everywhere && available) PhoneDiscoverSearch(session, openLibrary, openArtist, openAlbum, embedded = true)
                else server()
            }
        }
    }
}

@Composable
internal fun PhoneDiscoverSearch(session: DiscoverWishlistSession, openLibrary: (DiscoverLibraryRef) -> Unit,
    openArtist: (String) -> Unit, openAlbum: (String) -> Unit, embedded: Boolean = false) {
    val publication by session.publication.collectAsState()
    val state = publication ?: return
    var query by rememberSaveable(session) { mutableStateOf(state.query) }
    LaunchedEffect(session) {
        // Saved UI state can outlive its reader after activity recreation. Restore the
        // request too, so a populated field cannot sit above an empty replacement session.
        // collectAsState can retain the previous flow's frame during a session handoff.
        // Compare the current reader directly before deciding its request is already set.
        if (query != session.publication.value?.query) session.setQuery(query)
        session.refreshWishlist(WishFilter())
    }
    Column(Modifier.fillMaxSize().testTag("discover.search.surface")) {
        PhoneSearchField(query, { query = it; session.setQuery(it) }, { session.setQuery(query) }, "discover.search.field")
        LocalPhoneSearchScopes.current?.invoke()
        if (state.connection != "online") Text(stringResource(R.string.discover_offline).metadataBreak(),
            Modifier.padding(horizontal = 16.dp).testTag("discover.notice.offline"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val pages = state.groups.mapNotNull { it.page }
        Column(Modifier.padding(horizontal = 16.dp)) { DiscoverPageNotices(pages.firstOrNull { it.age.stale || it.age.fromDeviceCache } ?: pages.firstOrNull(),
            state.groups.firstOrNull { it.error?.code == "rate-limited" }?.error ?: state.groups.firstNotNullOfOrNull { it.error }) { session.setQuery(query) } }
        var expanded by remember(query) { mutableStateOf(emptySet<String>()) }
        val visibleGroups = state.groups.filter { group ->
            group.page?.items?.isNotEmpty() == true || (group.loading && group.page == null)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("discover.list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (group in visibleGroups) {
                item(key = "header:${group.kind}") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(discoverKindTitle(group.kind), Modifier.weight(1f).testTag("discover.group.${group.kind}"), style = MaterialTheme.typography.titleMedium)
                        if ((group.page?.items?.size ?: 0) > 4 && group.kind !in expanded) TextButton({ expanded = expanded + group.kind },
                            Modifier.offset(x = 12.dp).testTag("discover.seeAll.${group.kind}")) { Text(stringResource(R.string.discover_see_all)) }
                    }
                    if (group.loading && group.page == null) DiscoverLoadingRows("discover.group.${group.kind}.loading")
                }
                itemsIndexed(group.page?.items.orEmpty().let { if (group.kind in expanded) it else it.take(4) }, key = { index, item -> "${group.kind}:$index:${item.id}" }) { index, item ->
                    DiscoverItemRow(session, item, index, WishSource("search", null), openLibrary, openArtist, openAlbum)
                }
                if (group.page?.nextCursor != null && ((group.page?.items?.size ?: 0) <= 4 || group.kind in expanded)) item(key = "more:${group.kind}") {
                    TextButton({ session.loadMore(group.kind) }, enabled = !group.loading,
                        modifier = Modifier.testTag("discover.more.${group.kind}")) { Text(stringResource(R.string.discover_more)) }
                }
            }
            if (state.query.isNotBlank() && state.groups.none { it.error != null } &&
                visibleGroups.none { it.page?.items?.isNotEmpty() == true } && visibleGroups.none { it.loading }) {
                item("no-matches") { Text(stringResource(R.string.discover_no_matches), Modifier.padding(horizontal = 16.dp)) }
            }
            // The credit is for what the page shows; a search that failed shows none of its data.
            if (visibleGroups.any { it.page?.items?.isNotEmpty() == true } || state.groups.none { it.error != null }) {
                item("credit") { Box(Modifier.padding(horizontal = 16.dp)) { DiscoverCredit() } }
            }
        }
    }
}

@Composable
internal fun DiscoverItemRow(session: DiscoverWishlistSession, item: DiscoverItem, index: Int, source: WishSource,
    openLibrary: (DiscoverLibraryRef) -> Unit, openArtist: (String) -> Unit, openAlbum: (String) -> Unit) {
    val publication by session.publication.collectAsState()
    val access = publication?.access
    val knownTarget = item.target
    val open: (() -> Unit)? = when {
        knownTarget == null -> null
        item.kind == "artist" -> ({ openArtist(knownTarget.mbid) })
        item.kind == "album" -> ({ openAlbum(knownTarget.mbid) })
        else -> null
    }
    val wish = publication?.wishes?.firstOrNull { (it.wish?.target ?: it.pendingAdd?.target) == knownTarget && knownTarget != null }
    var saveError by remember(session, item.id) { mutableStateOf<DiscoverFailure?>(null) }
    val largeText = LocalDensity.current.fontScale >= 1.5f
    PhoneMediaRow(
        title = item.title,
        supporting = { Column {
            Text(listOfNotNull(discoverSingularKind(item.kind), item.artist,
                item.releaseDate ?: item.year?.toString()).metadataLine(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            DiscoverAvailabilityText(item.availability, tag = "discover.availability.${item.kind}.$index")
            (saveError ?: wish?.error)?.let { Text(stringResource(if (it.retryable) R.string.discover_waiting else R.string.discover_not_saved), color = MaterialTheme.colorScheme.error) }
        } },
        leading = { DiscoverArtwork(session, item.artworkId, item.title, 56.dp,
            entity = when (item.kind) {
                "artist" -> ArtworkEntity.Artist
                "song" -> ArtworkEntity.Song
                "playlist" -> ArtworkEntity.Playlist
                else -> ArtworkEntity.Album
            }) },
        trailing = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (wish?.pending == true) DiscoverSyncStatus("discover.sync.${item.kind}.$index")
            DiscoverItemActions(session, item, index, source, wish, access, openLibrary) { saveError = it }
        } },
        modifier = Modifier.testTag("discover.item.${item.kind}.$index"), onClick = open,
        titleMaxLines = if (largeText) 2 else 1,
    )
}

@Composable
internal fun DiscoverAvailabilityText(
    value: DiscoverAvailability,
    tag: String? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showHeld: Boolean = true,
) {
    val matched = value.matchedTrackCount
    val total = value.totalTrackCount
    val label = when {
        value.status == "held" -> stringResource(if (compact) R.string.discover_shelves_in_library else R.string.discover_held)
        value.status == "partlyHeld" && matched != null && total != null ->
            stringResource(if (compact) R.string.discover_shelves_partial else R.string.restyle_discover_partial, matched, total)
        value.status == "notHeld" && value.completeness != "unknown" -> stringResource(R.string.discover_not_held)
        else -> null
    }
    // Availability is metadata, never a second button-like surface.
    if (label != null && (showHeld || value.status != "held")) {
        Text(label, modifier.then(tag?.let { Modifier.testTag(it) } ?: Modifier),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }

}

@Composable
internal fun DiscoverSyncStatus(tag: String) {
    Icon(DulcetIcons.Sync, stringResource(R.string.discover_waiting),
        Modifier.size(18.dp).testTag(tag), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun DiscoverArtwork(session: DiscoverWishlistSession, id: String?, title: String, size: Dp? = 48.dp,
    modifier: Modifier = Modifier, entity: ArtworkEntity = ArtworkEntity.Album, loadTag: String? = null) {
    var bitmap by remember(session, id) { mutableStateOf<ImageBitmap?>(null) }
    var settled by remember(session, id) { mutableStateOf(id == null) }
    DisposableEffect(session, id) {
        var active = true
        val request = id?.let { session.artwork(it) { result ->
            if (active) {
                if (result is DiscoverResult.Value) bitmap = BitmapFactory.decodeByteArray(result.value, 0, result.value.size)?.asImageBitmap()
                settled = true
            }
        } }
        onDispose { active = false; request?.cancel() }
    }
    val taggedModifier = loadTag?.let { modifier.testTag("$it.${if (settled) "ready" else "loading"}") } ?: modifier
    ArtworkFrame(bitmap, stringResource(R.string.artwork_description, title), size, taggedModifier, entity)
}

@Composable
internal fun DiscoverItemActions(session: DiscoverWishlistSession, item: DiscoverItem, index: Int, source: WishSource,
    wish: WishRow?, access: DiscoverFeatureAccess?, openLibrary: (DiscoverLibraryRef) -> Unit,
    failed: (DiscoverFailure?) -> Unit) {
    var open by remember(session, item.id) { mutableStateOf(false) }
    val canRemove = wish != null && access?.canRemoveWish == true
    val canAdd = wish == null && access?.canAddWish == true && item.wishable && item.target != null
    val canWish = canRemove || canAdd
    val wishLabel = stringResource(if (canRemove) R.string.discover_remove_wish_named else R.string.discover_add_wish_named, item.title)
    val canReject = access?.canRejectMatch == true && item.availability.libraryItems.isNotEmpty()
    if (!canWish && !canReject && item.availability.libraryItems.isEmpty()) return
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("discover.item.menu.${item.kind}.$index")) {
            Icon(DulcetIcons.MoreVert, stringResource(R.string.discover_item_actions, item.title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            item.availability.libraryItems.firstOrNull()?.let { ref ->
                DropdownMenuItem(text = { Text(stringResource(R.string.discover_open_library)) },
                    leadingIcon = { Icon(DulcetIcons.LibraryMusic, null) },
                    onClick = { open = false; openLibrary(ref) }, modifier = Modifier.testTag("discover.openLibrary.${item.kind}.$index"))
            }
            if (canWish) DropdownMenuItem(
                text = { Text(stringResource(if (canRemove) R.string.discover_remove_wish else R.string.discover_add_wish)) },
                leadingIcon = { Icon(if (canRemove) DulcetIcons.Wish else DulcetIcons.WishBorder, null) },
                onClick = {
                    open = false
                    if (canRemove) session.remove(checkNotNull(wish).key) { failed((it as? DiscoverResult.Failed)?.error) }
                    else session.add(item, source) { failed((it as? DiscoverResult.Failed)?.error) }
                }, modifier = Modifier.testTag("discover.wish.${item.kind}.$index").semantics { contentDescription = wishLabel })
            if (canReject) item.availability.libraryItems.forEachIndexed { refIndex, ref ->
                DropdownMenuItem(text = { Text(stringResource(R.string.discover_reject)) }, leadingIcon = { Icon(DulcetIcons.Edit, null) },
                    onClick = { open = false; session.reject(item, ref, wish?.wish?.id) { failed((it as? DiscoverResult.Failed)?.error) } },
                    modifier = Modifier.testTag("discover.reject.${item.kind}.$index.$refIndex"))
            }
        }
    }
}

@Composable
internal fun DiscoverWishIcon(session: DiscoverWishlistSession, item: DiscoverItem, index: Int, source: WishSource,
    wish: WishRow?, access: DiscoverFeatureAccess?, modifier: Modifier = Modifier, failed: (DiscoverFailure?) -> Unit) {
    if (wish != null && access?.canRemoveWish == true) {
        IconButton(onClick = { session.remove(wish.key) { failed((it as? DiscoverResult.Failed)?.error) } },
            modifier = modifier.testTag("discover.wish.${item.kind}.$index")) {
            Icon(DulcetIcons.Wish, stringResource(R.string.discover_remove_wish_named, item.title), tint = MaterialTheme.colorScheme.primary)
        }
    } else if (wish == null && access?.canAddWish == true && item.wishable && item.target != null) {
        IconButton(onClick = { session.add(item, source) { failed((it as? DiscoverResult.Failed)?.error) } },
            modifier = modifier.testTag("discover.wish.${item.kind}.$index")) {
            Icon(DulcetIcons.WishBorder, stringResource(R.string.discover_add_wish_named, item.title), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun DiscoverCredit() = Text(stringResource(R.string.discover_credit), Modifier.testTag("discover.credit"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun DiscoverLinks(links: List<DiscoverLink>) {
    val handler = LocalUriHandler.current
    var failed by remember(links) { mutableStateOf(false) }
    links.filter { runCatching { val uri = URI(it.url); uri.scheme.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null }.getOrDefault(false) }
        .forEach { link -> TextButton({ failed = runCatching { handler.openUri(link.url) }.isFailure }) {
            Text(stringResource(R.string.discover_external_link, link.relation))
        } }
    if (failed) Text(stringResource(R.string.discover_link_failed))
}

@Composable
internal fun DiscoverNoticeCard(error: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
internal fun DiscoverPageNotices(page: DiscoverPage?, error: DiscoverFailure?, failureText: String? = null, retry: () -> Unit) {
    page?.age?.takeIf { it.stale || it.fromDeviceCache }?.let { age ->
        Text(listOf(stringResource(R.string.discover_cached), stringResource(R.string.restyle_discover_updated,
            discoverRelativeAge(age.sourceAgeSeconds ?: ((System.currentTimeMillis() - age.receivedAtMillis).coerceAtLeast(0) / 1000)))).metadataLine(),
            Modifier.testTag("discover.notice.stale"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (error != null) {
        val deadline = remember(error.retryAtMillis) {
            SystemClock.elapsedRealtime() + ((error.retryAtMillis ?: 0) - System.currentTimeMillis()).coerceAtLeast(0)
        }
        var now by remember(deadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(deadline) { while (now < deadline) { delay(1000); now = SystemClock.elapsedRealtime() } }
        DiscoverNoticeCard(error = true) {
            if (error.code == "rate-limited") Text(stringResource(R.string.discover_busy, ceil((deadline - now).coerceAtLeast(0) / 60000.0).toInt()), Modifier.testTag("discover.notice.busy"))
            else Text(failureText ?: stringResource(R.string.discover_failed))
            TextButton(retry, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer), enabled = error.retryable && now >= deadline, modifier = Modifier.align(Alignment.End).testTag("discover.retry")) {
                Text(stringResource(R.string.discover_retry))
            }
        }
    }
}

@Composable internal fun discoverKindTitle(kind: String): String = stringResource(when(kind) {
    "artist" -> R.string.discover_artists
    "album" -> R.string.discover_albums
    "song" -> R.string.discover_songs
    else -> R.string.discover_music
})

@Composable internal fun discoverSingularKind(kind: String): String = when(kind) {
    "artist" -> stringResource(R.string.restyle_discover_artist)
    "album" -> stringResource(R.string.restyle_discover_album)
    "song" -> stringResource(R.string.restyle_discover_song)
    else -> kind
}
