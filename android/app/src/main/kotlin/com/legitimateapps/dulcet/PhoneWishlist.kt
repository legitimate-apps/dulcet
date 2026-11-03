package com.legitimateapps.dulcet

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.DiscoverFeatureAccess
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverItem
import com.legitimateapps.dulcet.core.DiscoverLibraryRef
import com.legitimateapps.dulcet.core.DiscoverResult
import com.legitimateapps.dulcet.core.WishFilter
import com.legitimateapps.dulcet.core.WishRow
import com.legitimateapps.dulcet.library.DiscoverWishlistSession
import com.legitimateapps.dulcet.ui.DulcetIcons
import java.text.DateFormat
import java.util.Date

/** Wishlist is a separate capability from Discover and from My Library membership. */
@Composable
internal fun PhoneWishlistEntry(session: DiscoverWishlistSession, open: () -> Unit) {
    val publication by session.publication.collectAsState()
    if (publication?.access?.wishlistAvailable != true) return
    ListItem(headlineContent = { Text(stringResource(R.string.wishlist_title)) },
        leadingContent = { Icon(DulcetIcons.Wish, null) },
        trailingContent = { Icon(DulcetIcons.ChevronRight, null) },
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
    }.entries.sortedBy { listOf("pending", "unavailable", "wanted", "requested", "searching", "fulfilled").indexOf(it.key).let { rank -> if (rank < 0) 6 else rank } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var selected by rememberSaveable(initialRowKey) { mutableStateOf(false) }
    LaunchedEffect(initialRowKey, current.wishes, current.wishlistSettled) {
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
            listState.scrollToItem(target + (if (current.connection != "online") 1 else 0) +
                (if (current.wishlistError != null) 1 else 0))
            selected = true
        }
    }
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
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("wishlist.list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            if (current.connection != "online") item("offline") {
                WishlistNotice(stringResource(R.string.wishlist_offline), "wishlist.offline") { session.refreshWishlist(filter) }
            }
            current.wishlistError?.let { failure -> item("error") {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    DiscoverPageNotices(null, failure, failureText = stringResource(R.string.wishlist_refresh_failed)) { session.refreshWishlist(filter) }
                }
            } }
            if (current.wishlistSettled && !current.wishlistLoading && current.wishes.isEmpty() && current.wishlistError == null) item("empty") {
                PhoneSearchEmpty(stringResource(R.string.wishlist_empty), "wishlist.empty",
                    stringResource(R.string.restyle_wishlist_empty_hint))
            }
            for (group in groups) {
                item("section:${group.key}") {
                    Text(if (group.key == "pending") stringResource(R.string.restyle_wishlist_waiting_section) else wishlistStateTitle(group.key),
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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
    DropdownMenuItem(
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
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (selected) Icon(DulcetIcons.Check, null) },
        onClick = choose,
        modifier = Modifier.testTag(tag).semantics { this.selected = selected },
    )
}

@Composable
private fun WishlistNotice(text: String, tag: String, retry: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(text, Modifier.testTag(tag), color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = retry, modifier = Modifier.align(Alignment.End).testTag("$tag.retry")) {
                Text(stringResource(R.string.discover_retry))
            }
        }
    }
}

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
            modifier = Modifier.testTag("wishlist.row.$index").clickable { detailsOpen = true },
            headlineContent = { Text(snapshot.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOfNotNull(snapshot.artist, snapshot.year?.toString()).joinToString(" · ")
                        .takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    val kindLabel = if (kind in listOf("artist", "album", "song")) discoverSingularKind(kind) else kind.takeIf { it.isNotBlank() }
                    val relativeAdded = wish?.let { value -> stringResource(R.string.restyle_wishlist_added,
                        android.text.format.DateUtils.getRelativeTimeSpanString(value.addedAtMillis, System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()) }
                    Text(listOfNotNull(wishlistStateTitle(state), kindLabel, relativeAdded).joinToString(" · "),
                        Modifier.testTag("wishlist.row.state.$index"), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state == "unavailable") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        failure != null && !failure.retryable -> Text(stringResource(R.string.wishlist_not_saved),
                            Modifier.testTag("wishlist.row.error.$index"), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        row.pending || failure?.retryable == true -> Text(stringResource(R.string.restyle_wishlist_pending_saved),
                            Modifier.testTag("wishlist.row.pending.$index"), style = MaterialTheme.typography.bodySmall)
                    }
                    wish?.note?.let { Text(stringResource(R.string.restyle_wishlist_note_preview, it),
                        Modifier.testTag("wishlist.row.note.$index"), maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
            },
            leadingContent = { DiscoverArtwork(session, snapshot.artworkId, snapshot.title, 56.dp) },
            trailingContent = if (!hasMenu) null else {
                {
                    var open by remember(session, row.key) { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }, modifier = Modifier.testTag("wishlist.row.menu.$index")) {
                            Icon(DulcetIcons.MoreVert, stringResource(R.string.wishlist_row_actions, snapshot.title))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            if (wish != null && state == "fulfilled" && local != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.wishlist_open_arrival)) },
                                    leadingIcon = { Icon(DulcetIcons.LibraryMusic, null) },
                                    onClick = { open = false; openLibrary(local) },
                                    modifier = Modifier.testTag("wishlist.arrival.$index")
                                        .semantics { contentDescription = arrivalLabel },
                                )
                                if (canCorrect) DropdownMenuItem(
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
                            if (access.canRemoveWish) DropdownMenuItem(
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
                }
            },
        )
    }
    if (detailsOpen) WishDetailsSheet(session, row, index, access, openLibrary) { detailsOpen = false }
}

@Composable
private fun WishStateLabel(state: String, index: Int) {
    Text(wishlistStateTitle(state), Modifier.testTag("wishlist.row.state.$index"),
        style = MaterialTheme.typography.bodySmall,
        color = if (state == "unavailable") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)

}

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
    val relative = android.text.format.DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(),
        android.text.format.DateUtils.MINUTE_IN_MILLIS, android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    Text(stringResource(label, relative), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WishDetailsSheet(session: DiscoverWishlistSession, row: WishRow, index: Int,
    access: DiscoverFeatureAccess, openLibrary: (DiscoverLibraryRef) -> Unit, dismiss: () -> Unit) {
    val snapshot = row.wish?.snapshot ?: row.pendingAdd?.snapshot ?: return
    val state = row.wish?.state ?: "wanted"
    var confirmRemove by remember { mutableStateOf(false) }
    PhoneModalBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("wishlist.details")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DiscoverArtwork(session, snapshot.artworkId, snapshot.title, 120.dp)
            Text(snapshot.title, style = MaterialTheme.typography.headlineSmall)
            snapshot.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(wishlistStateTitle(state), style = MaterialTheme.typography.labelLarge)
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
            if (state == "fulfilled") row.wish?.libraryItem?.let { ref ->
                Button({ dismiss(); openLibrary(ref) }, Modifier.fillMaxWidth().testTag("wishlist.details.open")) {
                    Text(stringResource(R.string.wishlist_open_arrival))
                }
            }
            if (access.canReadWishlist) TextButton({ session.refreshWishlist(WishFilter()) },
                Modifier.testTag("wishlist.details.refresh")) { Text(stringResource(R.string.restyle_wishlist_refresh_status)) }
            if (access.canRemoveWish) TextButton({ confirmRemove = true }, Modifier.testTag("wishlist.details.remove")) {
                Text(stringResource(R.string.wishlist_remove), color = MaterialTheme.colorScheme.error)
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
