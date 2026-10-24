package com.legitimateapps.dulcet

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import java.net.URI
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** The existing search presenter stays mounted only while Your Server is selected. Its query lives
 * above this screen in PhoneApp; external search always uses the account's core facade. */
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
        if (available) {
            Text(stringResource(R.string.search_title), Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!everywhere, { everywhere = false }, { Text(stringResource(R.string.discover_scope_server)) }, Modifier.testTag("discover.scope.server"))
                FilterChip(everywhere, { everywhere = true }, { Text(stringResource(R.string.discover_scope_everywhere)) }, Modifier.testTag("discover.scope.everywhere"))
            }
        }
        Box(Modifier.weight(1f)) {
            if (everywhere && available) PhoneDiscoverSearch(session, openLibrary, openArtist, openAlbum, embedded = true)
            else server()
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
        if (query != state.query) session.setQuery(query)
        session.refreshWishlist(WishFilter())
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("discover.search.surface")) {
        if (!embedded) Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it; session.setQuery(it) }, label = { Text(stringResource(R.string.discover_search_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("discover.search.field"))
        DiscoverCredit()
        if (state.connection != "online") DiscoverNoticeCard {
            Text(stringResource(R.string.discover_offline), Modifier.testTag("discover.notice.offline"))
        }
        val pages = state.groups.mapNotNull { it.page }
        DiscoverPageNotices(pages.firstOrNull { it.age.stale || it.age.fromDeviceCache } ?: pages.firstOrNull(),
            state.groups.firstOrNull { it.error?.code == "rate-limited" }?.error ?: state.groups.firstNotNullOfOrNull { it.error }) { session.setQuery(query) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("discover.list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (group in state.groups) {
                item(key = "header:${group.kind}") {
                    Text(discoverKindTitle(group.kind), Modifier.testTag("discover.group.${group.kind}"), style = MaterialTheme.typography.titleLarge)
                    if (group.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                itemsIndexed(group.page?.items.orEmpty(), key = { index, item -> "${group.kind}:$index:${item.id}" }) { index, item ->
                    DiscoverItemRow(session, item, index, WishSource("search", null), openLibrary, openArtist, openAlbum)
                }
                if (group.page?.nextCursor != null) item(key = "more:${group.kind}") {
                    TextButton({ session.loadMore(group.kind) }, enabled = !group.loading,
                        modifier = Modifier.testTag("discover.more.${group.kind}")) { Text(stringResource(R.string.discover_more)) }
                }
                if (group.page?.items?.isEmpty() == true && !group.loading && group.error == null) item {
                    Text(stringResource(R.string.discover_no_matches))
                }
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
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        PhoneMediaRow(
            title = item.title,
            supporting = {
                listOfNotNull(discoverKindTitle(item.kind), item.artist,
                    item.releaseDate ?: item.year?.toString()).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            leading = {
                if (item.kind == "artist") Monogram(item.title, 48.dp)
                else DiscoverArtwork(session, item.artworkId, item.title, 48.dp)
            },
            trailing = {
                DiscoverItemActions(session, item, index, source, wish, access, openLibrary) { saveError = it }
            },
            modifier = Modifier.testTag("discover.item.${item.kind}.$index"),
            onClick = open,
        )
        DiscoverAvailabilityText(item.availability, tag = "discover.availability.${item.kind}.$index",
            modifier = Modifier.padding(start = 72.dp, bottom = 4.dp))
        if (wish?.pending == true) Text(stringResource(R.string.discover_waiting))
        val failure = saveError ?: wish?.error
        if (failure != null) Text(stringResource(if (failure.retryable) R.string.discover_waiting else R.string.discover_not_saved), color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
    }
}

@Composable
internal fun DiscoverAvailabilityText(value: DiscoverAvailability, tag: String? = null, modifier: Modifier = Modifier) {
    val matched = value.matchedTrackCount
    val total = value.totalTrackCount
    val label = when {
        value.status == "held" -> stringResource(R.string.discover_held)
        value.status == "partlyHeld" && matched != null && total != null ->
            stringResource(R.string.discover_partial, matched, total)
        value.completeness == "unknown" -> stringResource(R.string.discover_unknown)
        value.status == "notHeld" -> stringResource(R.string.discover_not_held)
        else -> stringResource(R.string.discover_unknown)
    }
    val container = when {
        value.status == "held" -> MaterialTheme.colorScheme.primaryContainer
        value.status == "partlyHeld" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        value.status == "held" -> MaterialTheme.colorScheme.onPrimaryContainer
        value.status == "partlyHeld" -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(modifier = modifier.then(tag?.let { Modifier.testTag(it) } ?: Modifier).semantics { text = AnnotatedString(label) },
        shape = RoundedCornerShape(50), color = container) {
        Text(label, modifier = Modifier.clearAndSetSemantics { }.padding(horizontal = 10.dp, vertical = 3.dp),
            color = content, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun DiscoverArtwork(session: DiscoverWishlistSession, id: String?, title: String, size: Dp? = 48.dp,
    modifier: Modifier = Modifier) {
    var bitmap by remember(session, id) { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(session, id) {
        var active = true
        val request = id?.let { session.artwork(it) { result ->
            if (active && result is DiscoverResult.Value) bitmap = BitmapFactory.decodeByteArray(result.value, 0, result.value.size)?.asImageBitmap()
        } }
        onDispose { active = false; request?.cancel() }
    }
    ArtworkFrame(bitmap, stringResource(R.string.artwork_description, title), size, modifier)
}

@Composable
internal fun DiscoverItemActions(session: DiscoverWishlistSession, item: DiscoverItem, index: Int, source: WishSource,
    wish: WishRow?, access: DiscoverFeatureAccess?, openLibrary: (DiscoverLibraryRef) -> Unit,
    failed: (DiscoverFailure?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        item.availability.libraryItems.firstOrNull()?.let { first ->
            IconButton(onClick = { openLibrary(first) },
                modifier = Modifier.testTag("discover.openLibrary.${item.kind}.$index")) {
                Icon(DulcetIcons.LibraryMusic, stringResource(R.string.discover_open_library_named, item.title),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        DiscoverWishIcon(session, item, index, source, wish, access) { failed(it) }
        if (access?.canRejectMatch == true && item.availability.libraryItems.isNotEmpty()) {
            var menuOpen by remember(session, item.id) { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true },
                    modifier = Modifier.testTag("discover.item.menu.${item.kind}.$index")) {
                    Icon(DulcetIcons.MoreVert, stringResource(R.string.discover_item_actions, item.title))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    item.availability.libraryItems.forEachIndexed { refIndex, ref ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.discover_reject)) },
                            leadingIcon = { Icon(DulcetIcons.Edit, null) },
                            onClick = {
                                menuOpen = false
                                session.reject(item, ref, wish?.wish?.id) { result ->
                                    failed((result as? DiscoverResult.Failed)?.error)
                                }
                            },
                            modifier = Modifier.testTag("discover.reject.${item.kind}.$index.$refIndex"),
                        )
                    }
                }
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
    } else if (access?.canAddWish == true && item.wishable && item.target != null) {
        IconButton(onClick = { session.add(item, source) { failed((it as? DiscoverResult.Failed)?.error) } },
            modifier = modifier.testTag("discover.wish.${item.kind}.$index")) {
            Icon(DulcetIcons.WishBorder, stringResource(R.string.discover_add_wish_named, item.title), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun DiscoverCredit() = Text(stringResource(R.string.discover_credit), style = MaterialTheme.typography.bodySmall)

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
    if (page?.age?.stale == true || page?.age?.fromDeviceCache == true) DiscoverNoticeCard {
        Text(stringResource(R.string.discover_cached), Modifier.testTag("discover.notice.stale"))
    }
    // Provenance metadata reads as a caption beside the credit line, never as a notice card.
    page?.age?.let { age ->
        Text(age.sourceAgeSeconds?.let { stringResource(R.string.discover_age, it) } ?: stringResource(R.string.discover_age_unknown),
            Modifier.testTag("discover.age"), style = MaterialTheme.typography.bodySmall)
        if (age.stale || age.fromDeviceCache) Text(stringResource(R.string.discover_device_age,
            ((System.currentTimeMillis() - age.receivedAtMillis).coerceAtLeast(0) / 1000)), style = MaterialTheme.typography.bodySmall)
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
            TextButton(retry, enabled = error.retryable && now >= deadline, modifier = Modifier.align(Alignment.End).testTag("discover.retry")) {
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
