package com.legitimateapps.dulcet

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
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
    LaunchedEffect(session) { session.refreshWishlist(WishFilter()) }
    var query by rememberSaveable(session) { mutableStateOf(state.query) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("discover.search.surface")) {
        if (!embedded) Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it; session.setQuery(it) }, label = { Text(stringResource(R.string.discover_search_hint)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("discover.search.field"))
        DiscoverCredit()
        if (state.connection != "online") Text(stringResource(R.string.discover_offline), Modifier.testTag("discover.notice.offline"))
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
        Row(Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(onClick = open) else Modifier)
            .testTag("discover.item.${item.kind}.$index"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiscoverArtwork(session, item.artworkId)
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                item.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                item.releaseDate?.let { Text(it) } ?: item.year?.let { Text(it.toString()) }
                DiscoverAvailabilityText(item.availability)
            }
        }
        // Only server-issued, account-scoped references can enter existing library/player routes.
        item.availability.libraryItems.forEachIndexed { refIndex, ref ->
            TextButton({ openLibrary(ref) }, modifier = Modifier.testTag("discover.openLibrary.${item.kind}.${index}" + if (refIndex == 0) "" else ".$refIndex")) {
                Text(stringResource(R.string.discover_open_library))
            }
            if (access?.canRejectMatch == true) TextButton({ session.reject(item, ref, wish?.wish?.id) { result ->
                saveError = (result as? DiscoverResult.Failed)?.error
            } }, modifier = Modifier.testTag("discover.reject.${item.kind}.$index.$refIndex")) { Text(stringResource(R.string.discover_reject)) }
        }
        if (wish != null && access?.canRemoveWish == true) {
            TextButton({ session.remove(wish.key) { saveError = (it as? DiscoverResult.Failed)?.error } },
                modifier = Modifier.testTag("discover.wish.${item.kind}.$index")) { Text(stringResource(R.string.discover_remove_wish)) }
        } else if (access?.canAddWish == true && item.wishable && knownTarget != null && item.availability.status != "held") {
            TextButton({ session.add(item, source) { saveError = (it as? DiscoverResult.Failed)?.error } },
                modifier = Modifier.testTag("discover.wish.${item.kind}.$index")) { Text(stringResource(R.string.discover_add_wish)) }
        }
        if (wish?.pending == true) Text(stringResource(R.string.discover_waiting))
        val failure = saveError ?: wish?.error
        if (failure != null) Text(stringResource(if (failure.retryable) R.string.discover_waiting else R.string.discover_not_saved), color = MaterialTheme.colorScheme.error)
        HorizontalDivider()
    }
}

@Composable
internal fun DiscoverAvailabilityText(value: DiscoverAvailability) {
    when {
        value.status == "held" -> Text(stringResource(R.string.discover_held))
        value.status == "partlyHeld" && value.matchedTrackCount != null && value.totalTrackCount != null ->
            Text(stringResource(R.string.discover_partial, value.matchedTrackCount!!, value.totalTrackCount!!))
        value.completeness == "unknown" -> Text(stringResource(R.string.discover_unknown))
        value.status == "notHeld" -> Text(stringResource(R.string.discover_not_held))
        else -> Text(stringResource(R.string.discover_unknown))
    }
}

@Composable
internal fun DiscoverArtwork(session: DiscoverWishlistSession, id: String?) {
    var bitmap by remember(session, id) { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(session, id) {
        var active = true
        val request = id?.let { session.artwork(it) { result ->
            if (active && result is DiscoverResult.Value) bitmap = BitmapFactory.decodeByteArray(result.value, 0, result.value.size)?.asImageBitmap()
        } }
        onDispose { active = false; request?.cancel() }
    }
    val image = bitmap
    if (image != null) Image(image, null, Modifier.size(56.dp))
    else Surface(Modifier.size(56.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {}
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
internal fun DiscoverPageNotices(page: DiscoverPage?, error: DiscoverFailure?, failureText: String? = null, retry: () -> Unit) {
    if (page?.age?.stale == true || page?.age?.fromDeviceCache == true) Text(stringResource(R.string.discover_cached), Modifier.testTag("discover.notice.stale"))
    page?.age?.let { age ->
        Text(age.sourceAgeSeconds?.let { stringResource(R.string.discover_age, it) } ?: stringResource(R.string.discover_age_unknown), Modifier.testTag("discover.age"), style = MaterialTheme.typography.bodySmall)
        if (age.stale || age.fromDeviceCache) Text(stringResource(R.string.discover_device_age,
            ((System.currentTimeMillis() - age.receivedAtMillis).coerceAtLeast(0) / 1000)), style = MaterialTheme.typography.bodySmall)
    }
    if (error != null) {
        val deadline = remember(error.retryAtMillis) {
            SystemClock.elapsedRealtime() + ((error.retryAtMillis ?: 0) - System.currentTimeMillis()).coerceAtLeast(0)
        }
        var now by remember(deadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(deadline) { while (now < deadline) { delay(1000); now = SystemClock.elapsedRealtime() } }
        if (error.code == "rate-limited") Text(stringResource(R.string.discover_busy, ceil((deadline - now).coerceAtLeast(0) / 60000.0).toInt()), Modifier.testTag("discover.notice.busy"))
        else Text(failureText ?: stringResource(R.string.discover_failed))
        TextButton(retry, enabled = error.retryable && now >= deadline, modifier = Modifier.testTag("discover.retry")) { Text(stringResource(R.string.discover_retry)) }
    }
}

@Composable internal fun discoverKindTitle(kind: String): String = stringResource(when(kind) {
    "artist" -> R.string.discover_artists
    "album" -> R.string.discover_albums
    "song" -> R.string.discover_songs
    else -> R.string.discover_music
})
