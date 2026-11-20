package com.legitimateapps.dulcet

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import com.legitimateapps.dulcet.library.ageWords
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.reasonThenAge
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.textScaledIconSize
import com.legitimateapps.dulcet.ui.ArtworkEntity
import com.legitimateapps.dulcet.ui.ArtworkImages
import com.legitimateapps.dulcet.ui.artworkRetryDelayMillis
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val scopeControls: (@Composable () -> Unit)? = if (available) {
            {
                Row(Modifier.fillMaxWidth().padding(horizontal = DulcetSpacing.pageGutter).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.s)) {
                    FilterChip(!everywhere, { everywhere = false }, { Text(stringResource(R.string.discover_scope_server)) }, Modifier.testTag("discover.scope.server"),
                        leadingIcon = { if (!everywhere) Icon(DulcetIcons.Check, null) })
                    FilterChip(everywhere, { everywhere = true }, { Text(stringResource(R.string.discover_scope_everywhere)) }, Modifier.testTag("discover.scope.everywhere"),
                        leadingIcon = { if (everywhere) Icon(DulcetIcons.Check, null) })
                }
            }
        } else null
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
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize().testTag("discover.search.surface")) {
        Box(Modifier.fillMaxWidth()) {
            val scrolled = list.canScrollBackward
            Column(Modifier.fillMaxWidth()
                .background(if (scrolled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface)
                .padding(top = DulcetSpacing.s)) {
                // Switching scope moves neither the search field nor the chips.
                PhoneSearchField(query, { value ->
                    if (value != query) list.requestScrollToItem(0)
                    query = value
                    session.setQuery(value)
                }, { session.setQuery(query) }, "discover.search.field")
                LocalPhoneSearchScopes.current?.let { scopes -> Spacer(Modifier.height(DulcetSpacing.m)); scopes() }
            }
            if (scrolled) HorizontalDivider(Modifier.align(Alignment.BottomCenter).testTag("discover.search.scrollEdge"))
        }
        val pages = state.groups.mapNotNull { it.page }
        val savedPage = pages.firstOrNull { it.age.stale || it.age.fromDeviceCache }
        val offline = state.connection == "offline"
        val error = state.groups.firstOrNull { it.error?.code == "rate-limited" }?.error
            ?: state.groups.firstNotNullOfOrNull { it.error }
        var expanded by remember(query) { mutableStateOf(emptySet<String>()) }
        val visibleGroups = state.groups.filter { group ->
            group.page?.items?.isNotEmpty() == true || (group.loading && group.page == null)
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).testTag("discover.list"),
            state = list,
            contentPadding = PaddingValues(bottom = DulcetSpacing.l),
        ) {
            item("notice") {
                // Keep the top padding in the notice item so its key anchors the list even before a notice arrives.
                Spacer(Modifier.height(DulcetSpacing.s))
                if (savedPage != null || offline || error != null) {
                    Box(Modifier.padding(horizontal = DulcetSpacing.pageGutter)) {
                        DiscoverPageNotices(savedPage ?: pages.firstOrNull(), error, offline = offline) { session.setQuery(query) }
                    }
                }
            }
            for (group in visibleGroups) {
                item(key = "header:${group.kind}") {
                    val seeAll: (() -> Unit)? = if ((group.page?.items?.size ?: 0) > 4 && group.kind !in expanded) {
                        { expanded = expanded + group.kind }
                    } else null
                    DulcetSectionHeader(
                        discoverKindTitle(group.kind),
                        Modifier.testTag("discover.group.${group.kind}"),
                        onSeeAll = seeAll,
                        seeAllTag = "discover.seeAll.${group.kind}",
                        seeAllLabel = stringResource(R.string.discover_see_all),
                    )
                    if (group.loading && group.page == null) {
                        Box(Modifier.padding(horizontal = DulcetSpacing.pageGutter)) {
                            DiscoverLoadingRows("discover.group.${group.kind}.loading")
                        }
                    }
                }
                itemsIndexed(group.page?.items.orEmpty().let { if (group.kind in expanded) it else it.take(4) }, key = { index, item -> "${group.kind}:$index:${item.id}" }) { index, item ->
                    DiscoverItemRow(session, item, index, WishSource("search", null), openLibrary, openArtist, openAlbum)
                }
                if (group.page?.nextCursor != null && ((group.page?.items?.size ?: 0) <= 4 || group.kind in expanded)) item(key = "more:${group.kind}") {
                    TextButton({ session.loadMore(group.kind) }, enabled = !group.loading,
                        modifier = Modifier.padding(horizontal = DulcetSpacing.pageGutter).testTag("discover.more.${group.kind}")) { Text(stringResource(R.string.discover_more)) }
                }
            }
            if (state.query.isNotBlank() && state.groups.none { it.error != null } &&
                visibleGroups.none { it.page?.items?.isNotEmpty() == true } && visibleGroups.none { it.loading }) {
                item("no-matches") { Text(stringResource(R.string.discover_no_matches), Modifier.padding(horizontal = DulcetSpacing.pageGutter)) }
            }
            // The credit is for what the page shows; a search that failed shows none of its data.
            if (visibleGroups.any { it.page?.items?.isNotEmpty() == true } || state.groups.none { it.error != null }) {
                item("credit") { Box(Modifier.padding(horizontal = DulcetSpacing.pageGutter, vertical = DulcetSpacing.s)) { DiscoverCredit() } }
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
    val largeText = isLargeText()
    val hasActions = item.canChangeWish(wish, access) || item.availability.libraryItems.isNotEmpty()
    val syncing = wish?.pending == true
    PhoneMediaRow(
        title = item.title,
        supporting = { Column {
            MetadataText(listOfNotNull(discoverSingularKind(item.kind), item.artist,
                item.releaseDate?.take(4) ?: item.year?.toString()).metadataLine(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            DiscoverAvailabilityText(item.availability, tag = "discover.availability.${item.kind}.$index")
            (saveError ?: wish?.error)?.let { Text(stringResource(if (it.retryable) R.string.discover_waiting else R.string.discover_not_saved), color = MaterialTheme.colorScheme.error) }
        } },
        leading = { DiscoverArtwork(session, item.artworkId, item.title, DulcetSizes.rowArt,
            entity = when (item.kind) {
                "artist" -> ArtworkEntity.Artist
                "song" -> ArtworkEntity.Song
                "playlist" -> ArtworkEntity.Playlist
                else -> ArtworkEntity.Album
            }) },
        trailing = if (hasActions || syncing) ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.xs)) {
                if (syncing) DiscoverSyncStatus("discover.sync.${item.kind}.$index")
                DiscoverItemActions(session, item, index, source, wish, access, openLibrary) { saveError = it }
            }
        }) else null,
        trailingAction = hasActions,
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
        WrappedTitleText(label, modifier.then(tag?.let { Modifier.testTag(it) } ?: Modifier), maxLines = 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }

}

@Composable
internal fun DiscoverSyncStatus(tag: String) {
    Icon(DulcetIcons.Sync, stringResource(R.string.discover_waiting),
        Modifier.size(textScaledIconSize(18.dp)).testTag(tag), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The same status in words, for a row whose trailing edge has no room to explain an icon. */
@Composable
internal fun DiscoverSyncLine(tag: String) {
    Row(Modifier.testTag(tag), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DulcetSpacing.xs)) {
        Icon(DulcetIcons.Sync, null, Modifier.size(textScaledIconSize(14.dp)).testTag("$tag.icon"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.discover_waiting), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun DiscoverArtwork(session: DiscoverWishlistSession, id: String?, title: String, size: Dp? = DulcetSizes.compactArt,
    modifier: Modifier = Modifier, entity: ArtworkEntity = ArtworkEntity.Album, loadTag: String? = null) {
    val density = LocalDensity.current
    // A tile with no fixed size fills its page's width at most, so the window's width bounds it from the first read.
    val width = LocalConfiguration.current.screenWidthDp.dp
    val cover = rememberDiscoverCover(session, id, with(density) { (size ?: width).roundToPx() })
    val taggedModifier = loadTag?.let { modifier.testTag("$it.${if (cover.settled) "ready" else "loading"}") } ?: modifier
    ArtworkFrame(cover.bitmap, stringResource(R.string.artwork_description, title), size, taggedModifier, entity)
}

internal class DiscoverCover {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var settled by mutableStateOf(false)
}

/**
 * Reads a Discover cover and decodes it off the main thread at a size bounded by [pixels], with the rest of
 * the app's covers: the picture is the server's choice. Bytes the bounded decode refuses are a missing cover.
 * A failed read stays asked for on the shared slowing schedule, so a tile on screen does not hold its
 * placeholder after the server is answering again. An answer that there is no cover ends it.
 */
@Composable
internal fun rememberDiscoverCover(session: DiscoverWishlistSession, id: String?, pixels: Int,
    decode: (ByteArray, Int) -> ImageBitmap? = { bytes, wanted -> ArtworkImages.decodeForDisplay(bytes, wanted)?.asImageBitmap() }): DiscoverCover {
    val cover = remember(session, id) { DiscoverCover().apply { settled = id == null } }
    var attempt by remember(session, id) { mutableIntStateOf(0) }
    var retryDelay by remember(session, id) { mutableStateOf<Long?>(null) }
    val load = remember(session, id) { id?.let { ArtworkImages.beginLoad() } }
    val scope = rememberCoroutineScope()
    val wanted by rememberUpdatedState(pixels)
    DisposableEffect(load) { onDispose { load?.close() } }
    DisposableEffect(session, id, attempt) {
        var active = true
        val request = id?.let { session.artwork(it) { result ->
            scope.launch {
                val bytes = (result as? DiscoverResult.Value)?.value
                val decoded = bytes?.let { withContext(Dispatchers.Default) { decode(it, wanted) } }
                if (active) {
                    when {
                        decoded != null -> { cover.bitmap = decoded; load?.painted() }
                        result is DiscoverResult.Failed && result.error.retryable -> { load?.failed(); retryDelay = artworkRetryDelayMillis(attempt) }
                        else -> load?.missing()
                    }
                    cover.settled = true
                }
            }
        } }
        onDispose { active = false; request?.cancel() }
    }
    retryDelay?.let { wait ->
        LaunchedEffect(session, id, attempt) {
            delay(wait)
            retryDelay = null
            attempt += 1
        }
    }
    return cover
}

private fun DiscoverItem.canChangeWish(wish: WishRow?, access: DiscoverFeatureAccess?): Boolean =
    if (wish != null) access?.canRemoveWish == true else access?.canAddWish == true && wishable && target != null

@Composable
internal fun DiscoverItemActions(session: DiscoverWishlistSession, item: DiscoverItem, index: Int, source: WishSource,
    wish: WishRow?, access: DiscoverFeatureAccess?, openLibrary: (DiscoverLibraryRef) -> Unit,
    failed: (DiscoverFailure?) -> Unit) {
    var open by remember(session, item.id) { mutableStateOf(false) }
    val canRemove = wish != null && access?.canRemoveWish == true
    val canWish = item.canChangeWish(wish, access)
    val wishLabel = stringResource(if (canRemove) R.string.discover_remove_wish_named else R.string.discover_add_wish_named, item.title)
    val canReject = access?.canRejectMatch == true && item.availability.libraryItems.isNotEmpty()
    if (!canWish && item.availability.libraryItems.isEmpty()) return
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("discover.item.menu.${item.kind}.$index")) {
            Icon(DulcetIcons.MoreVert, stringResource(R.string.discover_item_actions, item.title))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            item.availability.libraryItems.firstOrNull()?.let { ref ->
                PhoneMenuItem(text = { Text(stringResource(R.string.discover_open_library)) },
                    leadingIcon = { Icon(DulcetIcons.LibraryMusic, null) },
                    onClick = { open = false; openLibrary(ref) }, modifier = Modifier.testTag("discover.openLibrary.${item.kind}.$index"))
            }
            if (canWish) PhoneMenuItem(
                text = { Text(stringResource(if (canRemove) R.string.discover_remove_wish else R.string.discover_add_wish)) },
                leadingIcon = { Icon(if (canRemove) DulcetIcons.Wish else DulcetIcons.WishBorder, null) },
                onClick = {
                    open = false
                    if (canRemove) session.remove(checkNotNull(wish).key) { failed((it as? DiscoverResult.Failed)?.error) }
                    else session.add(item, source) { failed((it as? DiscoverResult.Failed)?.error) }
                }, modifier = Modifier.testTag("discover.wish.${item.kind}.$index").semantics { contentDescription = wishLabel })
            if (canReject) item.availability.libraryItems.forEachIndexed { refIndex, ref ->
                PhoneMenuItem(text = { Text(stringResource(R.string.discover_reject)) }, leadingIcon = { Icon(DulcetIcons.Edit, null) },
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

internal fun DiscoverAge.isSaved(): Boolean = stale || fromDeviceCache

@Composable
internal fun DiscoverPageNotices(
    page: DiscoverPage?,
    error: DiscoverFailure?,
    offline: Boolean = false,
    retry: () -> Unit,
) {
    val saved = page?.age?.takeIf(DiscoverAge::isSaved)
    val offlineStatus = offline || error?.code == "offline"
    Column(verticalArrangement = Arrangement.spacedBy(DulcetSpacing.s)) {
        if (saved != null || offlineStatus) {
            DiscoverSavedStatus(saved, offlineStatus, if (saved != null) "discover.notice.stale" else "discover.notice.offline")
        }
        // The server can still be in its retry window while the device shows offline results.
        if (error != null && error.code != "offline" && (!offlineStatus || error.code == "rate-limited")) {
            DiscoverFailureNotice(
                error,
                stringResource(R.string.discover_failed),
                titleTag = "discover.notice.busy".takeIf { error.code == "rate-limited" },
                retryTag = "discover.retry",
                retry = retry.takeUnless { offlineStatus },
            )
        }
    }
}

@Composable
internal fun DiscoverSavedStatus(age: DiscoverAge?, offline: Boolean, tag: String) {
    val resources = libraryResources()
    val ageLabel = age?.takeIf(DiscoverAge::isSaved)?.let { resources.ageWords(it.asOfMillis()) }
    val reason = stringResource(if (offline) SharedR.string.library_reason_offline else R.string.discover_showing_saved)
    val label = resources.reasonThenAge(reason, ageLabel)
    if (offline) {
        DulcetNotice(
            NoticeTone.Info, reason,
            body = ageLabel?.let { stringResource(SharedR.string.library_seen_at, it) },
            modifier = Modifier.testTag(tag).semantics(mergeDescendants = true) {},
        )
    } else {
        MetadataText(label, Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, wholePhrases = true)
    }
}

@Composable
internal fun DiscoverFailureNotice(
    failure: DiscoverFailure,
    title: String,
    modifier: Modifier = Modifier,
    titleTag: String? = null,
    body: String? = null,
    retryTag: String? = null,
    retry: (() -> Unit)? = null,
    tone: NoticeTone = NoticeTone.Error,
) {
    val deadline = remember(failure.retryAtMillis) {
        SystemClock.elapsedRealtime() + ((failure.retryAtMillis ?: 0) - System.currentTimeMillis()).coerceAtLeast(0)
    }
    var now by remember(deadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (now < deadline) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    val waiting = now < deadline
    val busy = failure.code == "rate-limited" || (failure.retryable && waiting)
    val noticeTitle = when {
        busy && waiting -> {
            val minutes = ceil((deadline - now) / 60000.0).toInt()
            pluralStringResource(R.plurals.discover_busy, minutes, minutes)
        }
        busy -> stringResource(R.string.discover_busy_over)
        else -> title
    }
    val explanation = when (failure.code) {
        "unauthorized" -> stringResource(SharedR.string.library_error_credentials)
        "forbidden" -> stringResource(SharedR.string.library_error_forbidden)
        else -> stringResource(R.string.discover_request_failed_explanation)
    }
    DulcetNotice(
        if (busy) NoticeTone.Info else tone,
        noticeTitle,
        modifier = modifier,
        icon = when {
            busy && waiting -> LibraryRowIcons.Schedule
            busy -> DulcetIcons.Refresh
            else -> null
        },
        titleTag = titleTag,
        body = body ?: explanation.takeUnless { busy },
        actionLabel = stringResource(R.string.discover_retry),
        onAction = retry.takeIf { failure.retryable && !waiting },
        actionTag = retryTag,
    )
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
