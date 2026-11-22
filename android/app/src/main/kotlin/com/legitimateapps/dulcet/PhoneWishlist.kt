package com.legitimateapps.dulcet

import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.DiscoverFeatureAccess
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverItem
import com.legitimateapps.dulcet.core.DiscoverLibraryRef
import com.legitimateapps.dulcet.core.DiscoverResult
import com.legitimateapps.dulcet.core.WishFilter
import com.legitimateapps.dulcet.core.WishRow
import com.legitimateapps.dulcet.core.WishSnapshot
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.library.ageWords
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity

/** Wishlist is a separate capability from Discover and from My Library membership. */
@Composable
internal fun PhoneWishlistEntry(session: DiscoverWishlistSession, open: () -> Unit) {
    val publication by session.publication.collectAsState()
    if (publication?.access?.wishlistAvailable != true) return
    ListItem(headlineContent = { Text(stringResource(R.string.wishlist_title)) },
        leadingContent = { Icon(DulcetIcons.Wish, null) },
        modifier = Modifier.fillMaxWidth().testTag("wishlist.open").clickable(onClick = open))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneWishlist(
    session: DiscoverWishlistSession,
    openLibrary: (DiscoverLibraryRef) -> Unit,
    back: () -> Unit,
    initialRowKey: String? = null,
) {
    val publication by session.publication.collectAsState()
    val current = publication ?: return
    if (!current.access.wishlistAvailable) return
    var kind by rememberSaveable(session) { mutableStateOf<String?>(null) }
    var state by rememberSaveable(session) { mutableStateOf<String?>(null) }
    var history by rememberSaveable(session) { mutableStateOf(false) }
    val filter = WishFilter(kind, state, history)
    LaunchedEffect(session, filter, current.access.canReadWishlist) {
        if (current.access.canReadWishlist) session.refreshWishlist(filter)
    }
    val groups = current.wishes.withIndex().groupBy { indexed ->
        if (indexed.value.pending) "pending" else indexed.value.wish?.state ?: "wanted"
    }.entries.sortedBy { listOf("pending", "unavailable", "requested", "searching", "wanted", "fulfilled").indexOf(it.key).let { rank -> if (rank < 0) 6 else rank } }
    val offline = current.connection != "online"
    val wishlistError = current.wishlistError?.takeUnless { offline && it.code == "offline" }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var selected by rememberSaveable(initialRowKey) { mutableStateOf(false) }
    LaunchedEffect(initialRowKey, current.wishes, current.wishlistSettled, offline, wishlistError) {
        var index = 0
        var target: Int? = null
        for (group in groups) {
            index++ // Native section heading.
            for (indexed in group.value) {
                if (indexed.value.key == initialRowKey) target = index
                index++
            }
        }
        if (!selected && target != null && current.wishlistSettled) {
            listState.scrollToItem(target + (if (offline) 1 else 0) +
                (if (wishlistError != null) 1 else 0))
            selected = true
        }
    }
    // The header the list has scrolled past and holds at its top, if any: a header merely sitting at the top is not pinned.
    val pinnedSection by remember(listState) { derivedStateOf {
        val info = listState.layoutInfo
        val first = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        info.visibleItemsInfo.firstOrNull { item ->
            (item.key as? String)?.startsWith("section:") == true && item.offset <= info.viewportStartOffset &&
                (item.index < first || (item.index == first && offset > 0))
        }?.key as? String
    } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection).testTag("wishlist.surface")) {
        TopAppBar(title = { Text(stringResource(R.string.wishlist_title)) }, scrollBehavior = scrollBehavior, navigationIcon = {
            IconButton(onClick = back, modifier = Modifier.testTag("wishlist.back")) {
                Icon(DulcetIcons.ArrowBack, contentDescription = stringResource(R.string.wishlist_back))
            }
        }, actions = {
            IconButton(onClick = { session.refreshWishlist(filter) }, enabled = current.access.canReadWishlist,
                modifier = Modifier.testTag("wishlist.refresh")) {
                Icon(DulcetIcons.Refresh, contentDescription = stringResource(R.string.wishlist_refresh))
            }
            Box {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }, modifier = Modifier.testTag("wishlist.filters")) {
                    Icon(DulcetIcons.FilterList, contentDescription = stringResource(R.string.wishlist_filters))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    WishlistFilterMenu(kind, { kind = it }, state, { state = it }, history, { history = it })
                }
            }
        })
        if (current.wishlistLoading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("wishlist.loading"))
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("wishlist.list"), state = listState, contentPadding = PaddingValues(bottom = DulcetSpacing.sectionGap)) {
            if (offline) item("offline") {
                DulcetNotice(
                    NoticeTone.Info,
                    stringResource(R.string.wishlist_offline),
                    Modifier.padding(horizontal = DulcetSpacing.pageGutter, vertical = DulcetSpacing.s),
                    titleTag = "wishlist.offline",
                    actionLabel = stringResource(R.string.discover_retry),
                    onAction = { session.refreshWishlist(filter) },
                    actionTag = "wishlist.offline.retry",
                )
            }
            wishlistError?.let { failure -> item("error") {
                DiscoverFailureNotice(
                    failure, stringResource(R.string.wishlist_refresh_failed),
                    modifier = Modifier.padding(horizontal = DulcetSpacing.pageGutter, vertical = DulcetSpacing.s),
                    titleTag = if (failure.code == "rate-limited") "discover.notice.busy" else "wishlist.refreshFailed",
                    retryTag = if (failure.code == "rate-limited") "discover.retry" else "wishlist.refreshFailed.retry",
                    retry = { session.refreshWishlist(filter) },
                    tone = if (current.wishes.isEmpty() || failure.code in setOf("unauthorized", "forbidden")) NoticeTone.Error else NoticeTone.Info,
                )
            } }
            if (current.wishlistSettled && !current.wishlistLoading && current.wishes.isEmpty() && current.wishlistError == null) item("empty") {
                PhoneSearchEmpty(stringResource(R.string.wishlist_empty), "wishlist.empty",
                    stringResource(R.string.restyle_wishlist_empty_hint))
            }
            for (group in groups) {
                // Pinned: the rows leave the state word to their header, so a scrolled row still has its header above it.
                stickyHeader("section:${group.key}") {
                    val pinned = pinnedSection == "section:${group.key}"
                    // Pinned over scrolled rows it takes the scrolled app bar's tint and a hairline, as Material's scrolled
                    // top bar does; in place it is a plain heading, as signed off.
                    Column(Modifier.fillMaxWidth().background(if (pinned) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface)) {
                        Text(if (group.key == "pending") stringResource(R.string.wishlist_row_not_synced) else wishlistStateTitle(group.key),
                            Modifier.fillMaxWidth()
                                .padding(start = DulcetSpacing.pageGutter, end = DulcetSpacing.pageGutter,
                                    top = DulcetSpacing.sectionGap, bottom = DulcetSpacing.headingToContent)
                                .semantics { heading() }.testTag("wishlist.section.${group.key}"),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (group.key == "unavailable") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (pinned) HorizontalDivider(Modifier.testTag("wishlist.section.${group.key}.pinned"), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                itemsIndexed(group.value, key = { _, indexed -> indexed.value.key }) { _, indexed ->
                    PhoneWishRow(session, indexed.value, indexed.index, current.access, openLibrary)
                }
            }
        }
    }
}

@Composable
private fun WishlistFilterMenu(
    kind: String?,
    chooseKind: (String?) -> Unit,
    state: String?,
    chooseState: (String?) -> Unit,
    history: Boolean,
    toggleHistory: (Boolean) -> Unit,
) {
    Text(stringResource(R.string.wishlist_filter_kind), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    WishlistFilterItem(R.string.wishlist_all_kinds, "wishlist.kind.all", kind == null) { chooseKind(null) }
    for (value in listOf("artist", "album", "song")) {
        WishlistFilterItem(discoverKindTitle(value), "wishlist.kind.$value", kind == value) { chooseKind(value) }
    }
    Text(stringResource(R.string.wishlist_filter_state), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    WishlistFilterItem(R.string.wishlist_all_states, "wishlist.state.all", state == null) { chooseState(null) }
    for (value in listOf("wanted", "requested", "searching", "unavailable", "fulfilled")) {
        WishlistFilterItem(wishlistStateTitle(value), "wishlist.state.$value", state == value) { chooseState(value) }
    }
    PhoneMenuItem(
        text = { Text(stringResource(R.string.wishlist_history)) },
        leadingIcon = { if (history) Icon(DulcetIcons.Check, null) },
        onClick = { toggleHistory(!history) },
        modifier = Modifier.testTag("wishlist.history").semantics { selected = history },
    )
    if (!history) Text(stringResource(R.string.wishlist_recent_arrivals), Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WishlistFilterItem(label: Int, tag: String, selected: Boolean, choose: () -> Unit) {
    WishlistFilterItem(stringResource(label), tag, selected, choose)
}

@Composable
private fun WishlistFilterItem(label: String, tag: String, selected: Boolean, choose: () -> Unit) {
    PhoneMenuItem(
        text = { Text(label) },
        leadingIcon = { if (selected) Icon(DulcetIcons.Check, null) },
        onClick = choose,
        modifier = Modifier.testTag(tag).semantics { this.selected = selected },
    )
}

/**
 * A refresh that failed while the saved list is still on screen: the tonal card, not the error one, since nothing the
 * person saved is lost, and Try again beside the sentence, so one failed read costs one line of the list.
 */
/** Row disposal revokes UI callbacks; accepted mutations remain owned by the account reader. */
private class WishRowLifetime {
    var active = true
    fun close() { active = false }
}

@Composable
private fun PhoneWishRow(
    session: DiscoverWishlistSession,
    row: WishRow,
    index: Int,
    access: DiscoverFeatureAccess,
    openLibrary: (DiscoverLibraryRef) -> Unit,
) {
    val wish = row.wish
    val pending = row.pendingAdd
    val snapshot = wish?.snapshot ?: pending?.snapshot ?: return
    val kind = wish?.kind ?: pending?.target?.kind?.name?.lowercase().orEmpty()
    val state = wish?.state ?: "wanted"
    val lifetime = remember(session, row.key) { WishRowLifetime() }
    var mutationError by remember(session, row.key) { mutableStateOf<DiscoverFailure?>(null) }
    DisposableEffect(lifetime) { onDispose(lifetime::close) }
    val failure = mutationError ?: row.error
    val removeLabel = stringResource(R.string.wishlist_remove_named, snapshot.title)
    val arrivalLabel = stringResource(R.string.wishlist_open_named, snapshot.title)
    val rejectLabel = stringResource(R.string.wishlist_reject_named, snapshot.title)
    val local = wish?.libraryItem
    val canCorrect = wish != null && state == "fulfilled" && local != null && access.canRejectMatch && wish.target != null
    var detailsOpen by rememberSaveable(row.key) { mutableStateOf(false) }
    val hasMenu = access.canRemoveWish || (wish != null && state == "fulfilled" && local != null)
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.testTag("wishlist.row.$index").centredSlots().clickable { detailsOpen = true },
            headlineContent = { WrappedTitleText(snapshot.title, maxLines = 2) },
            supportingContent = { WishRowSupporting(row, snapshot, kind, state, failure, index) },
            leadingContent = { CentredSlot { WishArtwork(session, snapshot, kind) } },
            trailingContent = if (!hasMenu) null else {
                { TrailingSlot {
                    var open by remember(session, row.key) { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }, modifier = Modifier.testTag("wishlist.row.menu.$index")) {
                            Icon(DulcetIcons.MoreVert, stringResource(R.string.wishlist_row_actions, snapshot.title))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            if (wish != null && state == "fulfilled" && local != null) {
                                PhoneMenuItem(
                                    text = { Text(stringResource(R.string.wishlist_open_arrival)) },
                                    leadingIcon = { Icon(DulcetIcons.LibraryMusic, null) },
                                    onClick = { open = false; openLibrary(local) },
                                    modifier = Modifier.testTag("wishlist.arrival.$index")
                                        .semantics { contentDescription = arrivalLabel },
                                )
                                if (canCorrect) PhoneMenuItem(
                                    text = { Text(stringResource(R.string.wishlist_reject)) },
                                    leadingIcon = { Icon(DulcetIcons.Edit, null) },
                                    onClick = {
                                        open = false
                                        val item = DiscoverItem(id = wish.id, kind = wish.kind, target = wish.target,
                                            title = snapshot.title, artist = snapshot.artist, year = snapshot.year,
                                            releaseDate = null, artworkId = snapshot.artworkId, disc = null, position = null,
                                            listenCount = null, primaryType = null, links = emptyList(), wishable = true,
                                            availability = wish.availability)
                                        mutationError = null
                                        session.reject(item, local, wish.id) { result ->
                                            if (lifetime.active) mutationError = (result as? DiscoverResult.Failed)?.error
                                        }
                                    },
                                    modifier = Modifier.testTag("wishlist.reject.$index")
                                        .semantics { contentDescription = rejectLabel },
                                )
                            }
                            if (access.canRemoveWish) PhoneMenuItem(
                                text = { Text(stringResource(R.string.wishlist_remove), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(DulcetIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    open = false
                                    mutationError = null
                                    session.remove(row.key) { result ->
                                        if (lifetime.active) mutationError = (result as? DiscoverResult.Failed)?.error
                                    }
                                },
                                modifier = Modifier.testTag("wishlist.remove.$index")
                                    .semantics { contentDescription = removeLabel },
                            )
                        }
                    }
                } }
            },
        )
    }
    if (detailsOpen) WishDetailsSheet(session, row, index, access, openLibrary) { detailsOpen = false }
}

/** A recent wish uses the same artwork and text lines as its Wishlist row. */
@Composable
internal fun RecentWishRow(session: DiscoverWishlistSession, row: WishRow, open: () -> Unit) {
    val wish = row.wish
    val snapshot = wish?.snapshot ?: row.pendingAdd?.snapshot ?: return
    val kind = wish?.kind ?: row.pendingAdd?.target?.kind?.name?.lowercase().orEmpty()
    ListItem(
        modifier = Modifier.testTag("discover.recentWishes.item.${row.key}").centredSlots().clickable(onClick = open),
        headlineContent = { WrappedTitleText(snapshot.title, maxLines = 2) },
        supportingContent = { WishRowSupporting(row, snapshot, kind, wish?.state ?: "wanted", row.error) },
        leadingContent = { CentredSlot { WishArtwork(session, snapshot, kind) } },
    )
}

@Composable
private fun WishRowSupporting(row: WishRow, snapshot: WishSnapshot, kind: String, state: String, failure: DiscoverFailure?, index: Int? = null) {
    val kindLabel = kind.takeIf { it.isNotBlank() }?.let { discoverSingularKind(it) }
    val timing = if (row.pending) stringResource(R.string.wishlist_row_not_synced)
        else row.wish?.let { stringResource(R.string.restyle_wishlist_added, wishlistRelativeTime(it.addedAtMillis)) }
    val parts = listOfNotNull(kindLabel, snapshot.artist?.takeIf { it.isNotBlank() }, timing)
    val stateLabel = wishStateName(row, state)
    val metadata = parts.metadataLine().ifBlank { stateLabel }
    Column {
        MetadataText(
            metadata,
            index?.let { Modifier.testTag("wishlist.row.state.$it") } ?: Modifier,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            spokenText = if (row.pending || parts.isEmpty()) metadata else (listOf(stateLabel) + parts).metadataLine(),
        )
        row.wish?.note?.takeIf { it.isNotBlank() }?.let { note ->
            Text(stringResource(R.string.restyle_wishlist_note_preview, note),
                index?.let { Modifier.testTag("wishlist.row.note.$it") } ?: Modifier,
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
        }
        when {
            failure != null && !failure.retryable -> Text(stringResource(R.string.wishlist_not_saved),
                index?.let { Modifier.testTag("wishlist.row.error.$it") } ?: Modifier,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            !row.pending && failure?.retryable == true -> MetadataText(stringResource(R.string.restyle_wishlist_pending_saved),
                index?.let { Modifier.testTag("wishlist.row.pending.$it") } ?: Modifier, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WishArtwork(session: DiscoverWishlistSession, snapshot: WishSnapshot, kind: String) {
    DiscoverArtwork(session, snapshot.artworkId, snapshot.title, DulcetSizes.rowArt, entity = wishArtworkEntity(kind))
}

private fun wishArtworkEntity(kind: String): ArtworkEntity = when (kind) {
    "artist" -> ArtworkEntity.Artist
    "song" -> ArtworkEntity.Song
    "playlist" -> ArtworkEntity.Playlist
    else -> ArtworkEntity.Album
}

/** Use the group header’s state label. Pending wishes show “Not synced yet” until they sync. */
@Composable
private fun wishStateName(row: WishRow, state: String): String =
    if (row.pending) stringResource(R.string.wishlist_row_not_synced) else wishlistStateTitle(state)

@Composable
private fun wishlistStateTitle(state: String): String = when (state) {
    "wanted" -> stringResource(R.string.wishlist_wanted)
    "requested" -> stringResource(R.string.wishlist_requested)
    "searching" -> stringResource(R.string.wishlist_searching)
    "unavailable" -> stringResource(R.string.wishlist_unavailable)
    "fulfilled" -> stringResource(R.string.wishlist_fulfilled)
    else -> state // Open enum: retain the server's plain-text value without inventing a state.
}

@Composable
private fun WishTime(label: Int, millis: Long) {
    val relative = wishlistRelativeTime(millis)
    Text(stringResource(label, relative), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun wishlistRelativeTime(millis: Long): String =
    libraryResources().ageWords(millis) ?: stringResource(R.string.discover_just_now)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WishDetailsSheet(session: DiscoverWishlistSession, row: WishRow, index: Int,
    access: DiscoverFeatureAccess, openLibrary: (DiscoverLibraryRef) -> Unit, dismiss: () -> Unit) {
    val snapshot = row.wish?.snapshot ?: row.pendingAdd?.snapshot ?: return
    val state = row.wish?.state ?: "wanted"
    val kind = row.wish?.kind ?: row.pendingAdd?.target?.kind?.name?.lowercase().orEmpty()
    var confirmRemove by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    PhoneModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, modifier = Modifier.testTag("wishlist.details")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = DulcetSpacing.pageGutter, vertical = DulcetSpacing.l)) {
            Column(verticalArrangement = Arrangement.spacedBy(DulcetSpacing.m)) {
                DiscoverArtwork(session, snapshot.artworkId, snapshot.title, 120.dp, entity = wishArtworkEntity(kind))
                Text(snapshot.title, style = MaterialTheme.typography.headlineSmall)
                snapshot.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(wishStateName(row, state), Modifier.testTag("wishlist.details.status"), style = MaterialTheme.typography.bodyMedium,
                    color = if (state == "unavailable") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                row.wish?.let { wish ->
                    WishTime(R.string.wishlist_added, wish.addedAtMillis)
                    wish.fulfilledAtMillis?.let { WishTime(R.string.wishlist_arrived_at, it) }
                    wish.note?.let { note ->
                        Text(stringResource(R.string.restyle_wishlist_note), style = MaterialTheme.typography.titleSmall)
                        Text(note, Modifier.testTag("wishlist.details.note"), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (row.pending) Text(stringResource(R.string.restyle_wishlist_pending_detail),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.padding(top = DulcetSpacing.l)) {
                if (state == "fulfilled") row.wish?.libraryItem?.let { ref ->
                    WishSheetAction(R.string.wishlist_open_arrival, DulcetIcons.LibraryMusic, "wishlist.details.open") {
                        dismiss()
                        openLibrary(ref)
                    }
                }
                if (access.canReadWishlist) WishSheetAction(
                    R.string.restyle_wishlist_refresh_status, DulcetIcons.Refresh, "wishlist.details.refresh",
                ) { session.refreshWishlist(WishFilter()) }
                if (access.canRemoveWish) WishSheetAction(
                    R.string.wishlist_remove, DulcetIcons.Delete, "wishlist.details.remove", destructive = true,
                ) { confirmRemove = true }
            }

        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.wishlist_remove_named, snapshot.title)) },
        text = { Text(stringResource(R.string.restyle_wishlist_remove_note)) },
        confirmButton = { TextButton({ confirmRemove = false; session.remove(row.key) {}; dismiss() },
            Modifier.testTag("wishlist.details.remove.confirm")) { Text(stringResource(R.string.wishlist_remove)) } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun WishSheetAction(label: Int, icon: ImageVector, tag: String, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(stringResource(label), color = color) },
        leadingContent = { Icon(icon, contentDescription = null, Modifier.testTag("$tag.icon"), tint = color) },
        modifier = Modifier.bleedKeyline().heightIn(min = DulcetSizes.rowArt)
            .testTag(tag).clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
