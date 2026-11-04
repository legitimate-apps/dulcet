package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.offset
import com.legitimateapps.dulcet.library.trackDisplayTitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.library.hostInForeground
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.AndroidQueueInsertion
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.searchFailedBody
import com.legitimateapps.dulcet.library.searchScopeLabel
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchEmptyState
import com.legitimateapps.dulcet.search.SearchObservation
import com.legitimateapps.dulcet.search.emptyState
import androidx.compose.ui.semantics.semantics
import com.legitimateapps.dulcet.search.SearchPresenter

/**
 * The account's search presenter, kept by the app above its tabs and pages, so a result opened and
 * left with Back finds its query and results as they were. Keyed by the whole account: a changed
 * password or address replaces the process's reader, and a presenter still attached to the old one
 * would never hear from it again.
 */
@Composable
internal fun rememberSearchPresenter(account: SearchAccount, dependencies: SearchHostDependencies): SearchPresenter {
    val context = LocalContext.current
    val foreground = hostInForeground()
    val presenter = remember(account) { dependencies.createPresenter(account, context, foreground) }
    DisposableEffect(presenter) {
        onDispose(presenter::close)
    }
    return presenter
}

@Composable
internal fun MobileSearchScreen(
    presenter: SearchPresenter,
    /** An album or artist result opens its library page; a track plays ([SearchActivation]). */
    onActivate: (SearchResultItem) -> Unit,
    account: SearchAccount? = null,
    onPlay: ((SearchResultItem) -> Unit)? = null,
    /** Play Next and Add to Queue for a track result; null disables the items (spec §14.1). */
    onQueue: ((SearchResultItem, AndroidQueueInsertion) -> Unit)? = null,
    /** Scope host supplies the Search heading and status-bar inset when extensions are available. */
    embedded: Boolean = false,
) {
    val state by presenter.state.collectAsStateWithLifecycle()
    val recents by presenter.recents.collectAsStateWithLifecycle()
    val recentError by presenter.recentSearchError.collectAsStateWithLifecycle()
    var expanded by remember(state.query) { mutableStateOf(setOf<SearchResultType>()) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhoneSearchField(state.query, presenter::updateQuery, presenter::commitQuery, "search.query")
            LocalPhoneSearchScopes.current?.invoke()
            // Where these results come from (§16.15): the core's scope, in the shared words. A server
            // that failed or cannot be reached leaves the device's rows showing, labelled.
            val resources = libraryResources()
            val empty = state.emptyState
            // With nothing to show, a failure says it all below; the line would only repeat it.
            val scopeLine = resources.searchScopeLabel(
                state.scope.takeIf { empty !is SearchEmptyState.Failed })
            // A scope chip above already says "your server"; the line adds only what differs from it.
            val chipSays = LocalPhoneSearchScopes.current?.let { stringResource(R.string.discover_scope_server) }
            if (scopeLine != null && scopeLine != chipSays) Text(
                scopeLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("search.scope"),
            )
            if (state.isLoading && state.results.isEmpty()) LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag("search.loading"))
            // Only a search the server answered says "no matching music"; a failed or offline one
            // never heard from it (§16.15), so it says what it knows: this device has none.
            when (empty) {
                null -> Unit
                SearchEmptyState.NoMatches -> PhoneSearchEmpty(stringResource(R.string.search_empty), "search.empty")
                SearchEmptyState.NoMatchesOnDevice -> PhoneSearchEmpty(stringResource(SharedR.string.search_offline_empty_title), "search.empty.offline")
                is SearchEmptyState.Failed -> Surface(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp).testTag("search.failed"),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(SharedR.string.search_failed_title),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(resources.searchFailedBody(empty.error), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("search.failed.body"))
                        TextButton(onClick = presenter::refresh,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onErrorContainer),
                            modifier = Modifier.testTag("search.retry")) {
                            Text(stringResource(SharedR.string.library_try_again))
                        }
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search.results")
                    .semantics { this[SearchObservation] = state },
            ) {
                if (recentError != null) item { Text(stringResource(SharedR.string.library_depth_save_failed), Modifier.testTag("search.recents.error")) }
                if (state.query.isBlank() && recents.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.restyle_recent_searches), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = presenter::clearRecents, modifier = Modifier.testTag("search.recents.clear")) {
                                Text(stringResource(R.string.restyle_recent_clear))
                            }
                        }
                    }
                    itemsIndexed(recents) { index, query ->
                        ListItem(headlineContent = { Text(query) },
                            leadingContent = { Icon(PhoneSearchIcons.History, null) },
                            trailingContent = { IconButton({ presenter.updateQuery(query) }, Modifier.testTag("search.recent.insert.$index")) {
                                Icon(DulcetIcons.ArrowUp, stringResource(R.string.restyle_search_insert))
                            } },
                            modifier = Modifier.fillMaxWidth().testTag("search.recent.$index")
                                .clickable { presenter.updateQuery(query); presenter.commitQuery() })
                    }
                }
                for (type in listOf(SearchResultType.Artist, SearchResultType.Album, SearchResultType.Track)) {
                    val all = state.rows.withIndex().filter { it.value.item.type == type }
                    val groupRows = if (type in expanded) all else all.take(4)
                    if (groupRows.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(when (type) {
                                SearchResultType.Artist -> SharedR.string.search_group_artists
                                SearchResultType.Album -> SharedR.string.search_group_albums
                                SearchResultType.Track -> SharedR.string.search_group_songs
                            }), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            if (all.size > 4 && type !in expanded) TextButton(
                                onClick = { expanded = expanded + type },
                                modifier = Modifier.offset(x = 12.dp).testTag("search.seeAll.${type.name.lowercase()}"),
                            ) { Text(stringResource(SharedR.string.search_see_all)) }
                        }
                    }
                    itemsIndexed(groupRows, key = { _, indexed -> indexed.index }) { _, indexed ->
                    val index = indexed.index
                    val row = indexed.value
                    val result = row.item
                    val available = row.playability != AndroidLibraryPlayability.UnavailableOffline
                    val playable = available
                    MobileSearchResult(
                        result = result,
                        index = index,
                        account = account,
                        // Only beside the server's rows is "on this device" news; the scope line covers the rest.
                        deviceOnly = row.source == AndroidLibrarySearchRowSource.Device &&
                            state.scope == AndroidLibrarySearchScope.ServerAndDevice,
                        unavailableOffline = !available,
                        onActivate = { presenter.commitQuery(); onActivate(result) },
                        onPlay = onPlay?.takeIf { result.type == SearchResultType.Track && playable }?.let { play -> { presenter.commitQuery(); play(result) } },
                        onQueue = onQueue?.takeIf { result.type == SearchResultType.Track && playable }
                            ?.let { queue -> { insertion -> queue(result, insertion) } },
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun MobileSearchResult(
    result: SearchResultItem,
    index: Int,
    account: SearchAccount?,
    deviceOnly: Boolean,
    unavailableOffline: Boolean,
    onActivate: () -> Unit,
    onPlay: (() -> Unit)?,
    onQueue: ((AndroidQueueInsertion) -> Unit)? = null,
) {
    val resources = libraryResources()
    val kind = stringResource(when (result.type) {
        SearchResultType.Artist -> R.string.search_kind_artist
        SearchResultType.Album -> R.string.search_kind_album
        SearchResultType.Track -> R.string.search_kind_track
    })
    val credits = result.credits.joinToString { it.name }
    PhoneMediaRow(
        title = resources.trackDisplayTitle(result.title),
        supporting = {
            val notes = listOfNotNull(
                resources.getString(SharedR.string.search_row_device_only).takeIf { deviceOnly },
                resources.getString(SharedR.string.library_not_available_offline).takeIf { unavailableOffline },
            )
            MetadataText((listOfNotNull(kind.takeUnless { result.type == SearchResultType.Artist }, credits) + notes).filter { it.isNotBlank() }.metadataLine(), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        },
        leading = {
            if (account != null) Artwork(account, result.artworkKey, resources.trackDisplayTitle(result.title), 48.dp,
                entity = when (result.type) {
                    SearchResultType.Artist -> ArtworkEntity.Artist
                    SearchResultType.Album -> ArtworkEntity.Album
                    SearchResultType.Track -> ArtworkEntity.Song
                })
            else Icon(DulcetIcons.MusicNote, null)
        },
        trailing = if (onPlay == null && onQueue == null) null else {
            { SearchResultQueueMenu(index, onQueue, onPlay) }
        },
        titleMaxLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 2 else 1,
        modifier = Modifier.testTag("search.result.$index"),
        enabled = result.type != SearchResultType.Track || !unavailableOffline,
        onClick = onActivate,
    )
}

/**
 * Play Next and Add to Queue on a search result's menu (spec §14.1), tagged `search.queue.<index>` and
 * `search.queue.<index>.playNext` / `.addToQueue` — the same queue-edit path the album and track menus use.
 */
@Composable
private fun SearchResultQueueMenu(
    index: Int,
    onQueue: ((AndroidQueueInsertion) -> Unit)?,
    onPlay: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("search.queue.$index")) {
            Icon(DulcetIcons.MoreVert, stringResource(SharedR.string.queue_add_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            onPlay?.let { play -> androidx.compose.material3.DropdownMenuItem(
                text = { Text(stringResource(R.string.action_play)) },
                leadingIcon = { Icon(DulcetIcons.Play, null) },
                onClick = { open = false; play() }, modifier = Modifier.testTag("search.play.$index")) }
            onQueue?.let { queue -> QueueInsertionItems("search.queue.$index", { open = false },
                { queue(AndroidQueueInsertion.PlayNext) }, { queue(AndroidQueueInsertion.AddToQueue) }) }
        }
    }
}
