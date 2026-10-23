package com.legitimateapps.dulcet

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.DiscoverFeatureAccess
import com.legitimateapps.dulcet.core.DiscoverFailure
import com.legitimateapps.dulcet.core.DiscoverItem
import com.legitimateapps.dulcet.core.DiscoverLibraryRef
import com.legitimateapps.dulcet.core.DiscoverOperation
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
    TextButton(onClick = open, modifier = Modifier.testTag("wishlist.open")) {
        Icon(DulcetIcons.QueueMusic, contentDescription = null)
        Text(stringResource(R.string.wishlist_title), Modifier.padding(start = 8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneWishlist(
    session: DiscoverWishlistSession,
    openLibrary: (DiscoverLibraryRef) -> Unit,
    back: () -> Unit,
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
    Column(Modifier.fillMaxSize().testTag("wishlist.surface")) {
        TopAppBar(title = { Text(stringResource(R.string.wishlist_title)) }, navigationIcon = {
            IconButton(onClick = back, modifier = Modifier.testTag("wishlist.back")) {
                Icon(DulcetIcons.ArrowBack, contentDescription = stringResource(R.string.wishlist_back))
            }
        }, actions = {
            IconButton(onClick = { session.refreshWishlist(filter) }, enabled = current.access.canReadWishlist,
                modifier = Modifier.testTag("wishlist.refresh")) {
                Icon(DulcetIcons.Refresh, contentDescription = stringResource(R.string.wishlist_refresh))
            }
        })
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("wishlist.list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("filters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wishlist_filter_kind), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(kind == null, { kind = null }, { Text(stringResource(R.string.wishlist_all_kinds)) },
                            Modifier.testTag("wishlist.kind.all"))
                        for (value in listOf("artist", "album", "song")) {
                            FilterChip(kind == value, { kind = value }, { Text(discoverKindTitle(value)) },
                                Modifier.testTag("wishlist.kind.$value"))
                        }
                    }
                    Text(stringResource(R.string.wishlist_filter_state), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(state == null, { state = null }, { Text(stringResource(R.string.wishlist_all_states)) },
                            Modifier.testTag("wishlist.state.all"))
                        for (value in listOf("wanted", "requested", "searching", "unavailable", "fulfilled")) {
                            FilterChip(state == value, { state = value }, { Text(wishlistStateTitle(value)) },
                                Modifier.testTag("wishlist.state.$value"))
                        }
                    }
                    FilterChip(history, { history = !history }, { Text(stringResource(R.string.wishlist_history)) },
                        Modifier.testTag("wishlist.history"))
                    if (!history) Text(stringResource(R.string.wishlist_recent_arrivals), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (current.connection != "online") item("offline") {
                Text(stringResource(R.string.wishlist_offline), Modifier.testTag("wishlist.offline"))
            }
            if (current.wishlistLoading) item("loading") {
                CircularProgressIndicator(Modifier.testTag("wishlist.loading"))
            }
            current.wishlistError?.let { failure -> item("error") {
                DiscoverPageNotices(null, failure, failureText = stringResource(R.string.wishlist_refresh_failed)) { session.refreshWishlist(filter) }
            } }
            if (current.wishlistSettled && !current.wishlistLoading && current.wishes.isEmpty() && current.wishlistError == null) item("empty") {
                Text(stringResource(R.string.wishlist_empty), Modifier.testTag("wishlist.empty"))
            }
            itemsIndexed(current.wishes, key = { _, row -> row.key }) { index, row ->
                PhoneWishRow(session, row, index, current.access, openLibrary)
            }
        }
    }
}

/** Revoking callbacks never removes an already committed outbox intention. */
private class WishRowLifetime {
    var active = true
    private val operations = mutableListOf<DiscoverOperation>()
    fun own(operation: DiscoverOperation) { if (active) operations += operation else operation.cancel() }
    fun close() { active = false; operations.forEach(DiscoverOperation::cancel); operations.clear() }
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
    Column(Modifier.fillMaxWidth().testTag("wishlist.row.$index"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DiscoverArtwork(session, snapshot.artworkId)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(snapshot.title, style = MaterialTheme.typography.titleMedium)
                snapshot.artist?.let { Text(it) }
                snapshot.year?.let { Text(it.toString()) }
                Text(if (kind in listOf("artist", "album", "song")) discoverKindTitle(kind) else kind,
                    style = MaterialTheme.typography.bodySmall)
                Text(wishlistStateTitle(state), Modifier.testTag("wishlist.row.state.$index"), style = MaterialTheme.typography.labelLarge)
            }
        }
        when {
            failure != null && !failure.retryable -> Text(stringResource(R.string.wishlist_not_saved),
                Modifier.testTag("wishlist.row.error.$index"), color = MaterialTheme.colorScheme.error)
            row.pending || failure?.retryable == true -> Text(stringResource(R.string.wishlist_waiting),
                Modifier.testTag("wishlist.row.pending.$index"))
        }
        wish?.let { value ->
            WishTime(R.string.wishlist_added, value.addedAtMillis)
            WishTime(R.string.wishlist_updated, value.updatedAtMillis)
            value.fulfilledAtMillis?.let { WishTime(R.string.wishlist_arrived_at, it) }
            value.note?.let { note ->
                Text(note, Modifier.testTag("wishlist.row.note.$index"))
                value.noteAtMillis?.let { WishTime(R.string.wishlist_note_at, it) }
            }
        }
        val local = wish?.libraryItem
        if (wish != null && state == "fulfilled" && local != null) {
            TextButton(onClick = { openLibrary(local) }, modifier = Modifier.testTag("wishlist.arrival.$index").semantics { contentDescription = arrivalLabel }) {
                Text(stringResource(R.string.wishlist_open_arrival))
            }
            if (access.canRejectMatch && wish.target != null) TextButton(onClick = {
                val item = DiscoverItem(id = wish.id, kind = wish.kind, target = wish.target,
                    title = snapshot.title, artist = snapshot.artist, year = snapshot.year,
                    releaseDate = null, artworkId = snapshot.artworkId, disc = null, position = null,
                    listenCount = null, primaryType = null, links = emptyList(), wishable = true,
                    availability = wish.availability)
                mutationError = null
                lifetime.own(session.reject(item, local, wish.id) { result ->
                    if (lifetime.active) mutationError = (result as? DiscoverResult.Failed)?.error
                })
            }, modifier = Modifier.testTag("wishlist.reject.$index").semantics { contentDescription = rejectLabel }) {
                Text(stringResource(R.string.wishlist_reject))
            }
        }
        if (access.canRemoveWish) TextButton(onClick = {
            mutationError = null
            lifetime.own(session.remove(row.key) { result ->
                if (lifetime.active) mutationError = (result as? DiscoverResult.Failed)?.error
            })
        }, modifier = Modifier.testTag("wishlist.remove.$index").semantics { contentDescription = removeLabel }) {
            Text(stringResource(R.string.wishlist_remove))
        }
        HorizontalDivider()
    }
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
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val formatter = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Text(stringResource(label, formatter.format(Date(millis))), style = MaterialTheme.typography.bodySmall)
}
