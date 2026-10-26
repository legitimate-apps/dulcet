package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.library.trackDisplayTitle
import android.content.Context
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
) {
    val state by presenter.state.collectAsStateWithLifecycle()
    val recents by presenter.recents.collectAsStateWithLifecycle()
    val recentError by presenter.recentSearchError.collectAsStateWithLifecycle()
    var expanded by remember(state.query) { mutableStateOf(setOf<SearchResultType>()) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = state.query,
                onValueChange = presenter::updateQuery,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(DulcetIcons.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { presenter.commitQuery() }),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().testTag("search.query"),
            )
            // Where these results come from (§16.15): the core's scope, in the shared words. A server
            // that failed or cannot be reached leaves the device's rows showing, labelled.
            val resources = libraryResources()
            val empty = state.emptyState
            // With nothing to show, a failure says it all below; the line would only repeat it.
            val scopeLine = resources.searchScopeLabel(
                state.scope.takeIf { state.query.isNotBlank() && empty !is SearchEmptyState.Failed })
            if (scopeLine != null) Text(
                scopeLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("search.scope"),
            )
            if (state.isLoading && state.results.isEmpty()) Text(
                stringResource(R.string.search_loading),
                modifier = Modifier.testTag("search.loading"),
            )
            // Only a search the server answered says "no matching music"; a failed or offline one
            // never heard from it (§16.15), so it says what it knows: this device has none.
            when (empty) {
                null -> Unit
                SearchEmptyState.NoMatches -> Text(
                    stringResource(R.string.search_empty),
                    modifier = Modifier.testTag("search.empty"),
                )
                SearchEmptyState.NoMatchesOnDevice -> Text(
                    stringResource(SharedR.string.search_offline_empty_title),
                    modifier = Modifier.testTag("search.empty.offline"),
                )
                is SearchEmptyState.Failed -> Column(
                    modifier = Modifier.fillMaxWidth().testTag("search.failed"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(SharedR.string.search_failed_title),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(resources.searchFailedBody(empty.error), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("search.failed.body"))
                    Button(onClick = presenter::refresh, modifier = Modifier.testTag("search.retry")) {
                        Text(stringResource(SharedR.string.library_try_again))
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
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(SharedR.string.search_recent), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = presenter::clearRecents, modifier = Modifier.testTag("search.recents.clear")) {
                                Text(stringResource(SharedR.string.search_clear))
                            }
                        }
                    }
                    itemsIndexed(recents) { index, query ->
                        TextButton(onClick = { presenter.updateQuery(query); presenter.commitQuery() },
                            modifier = Modifier.fillMaxWidth().testTag("search.recent.$index")) { Text(query) }
                    }
                }
                for (type in listOf(SearchResultType.Artist, SearchResultType.Album, SearchResultType.Track)) {
                    val all = state.rows.withIndex().filter { it.value.item.type == type }
                    val groupRows = if (type in expanded) all else all.take(4)
                    if (all.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(when (type) {
                                SearchResultType.Artist -> SharedR.string.search_group_artists
                                SearchResultType.Album -> SharedR.string.search_group_albums
                                SearchResultType.Track -> SharedR.string.search_group_songs
                            }), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            if (all.size > 4 && type !in expanded) TextButton(
                                onClick = { expanded = expanded + type },
                                modifier = Modifier.testTag("search.seeAll.${type.name.lowercase()}"),
                            ) { Text(stringResource(SharedR.string.search_see_all)) }
                        }
                    }
                    itemsIndexed(groupRows, key = { _, indexed -> indexed.index }) { _, indexed ->
                    val index = indexed.index
                    val row = indexed.value
                    val result = row.item
                    val available = row.playability != AndroidLibraryPlayability.UnavailableOffline
                    val playable = available && result.duration != null
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
                        onQueue = onQueue?.takeIf { result.type == SearchResultType.Track && playable && result.duration != null }
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
    ListItem(
        headlineContent = { Text(resources.trackDisplayTitle(result.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val notes = listOfNotNull(
                resources.getString(SharedR.string.search_row_device_only).takeIf { deviceOnly },
                resources.getString(SharedR.string.library_not_available_offline).takeIf { unavailableOffline },
                resources.getString(SharedR.string.library_duration_required).takeIf { result.type == SearchResultType.Track && result.duration == null },
            )
            Text((listOf(kind, credits) + notes).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (account != null && result.type != SearchResultType.Artist) Artwork(account, result.artworkKey, resources.trackDisplayTitle(result.title), 48.dp)
            else Icon(if (result.type == SearchResultType.Artist) DulcetIcons.Person else DulcetIcons.MusicNote, null)
        },
        trailingContent = if (onPlay == null && onQueue == null) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onPlay?.let { play ->
                        IconButton(onClick = play, modifier = Modifier.testTag("search.play.$index")) {
                            Icon(DulcetIcons.Play, stringResource(R.string.action_play_track, resources.trackDisplayTitle(result.title)))
                        }
                    }
                    onQueue?.let { queue ->
                        SearchResultQueueMenu(index, { insertion -> queue(insertion) })
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(enabled = result.type != SearchResultType.Track || (!unavailableOffline && result.duration != null), onClick = onActivate).testTag("search.result.$index"),
    )
}

/**
 * Play Next and Add to Queue on a search result's menu (spec §14.1), tagged `search.queue.<index>` and
 * `search.queue.<index>.playNext` / `.addToQueue` — the same queue-edit path the album and track menus use.
 */
@Composable
private fun SearchResultQueueMenu(
    index: Int,
    onQueue: (AndroidQueueInsertion) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("search.queue.$index")) {
            Icon(DulcetIcons.MoreVert, stringResource(SharedR.string.queue_add_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            QueueInsertionItems("search.queue.$index", { open = false },
                { onQueue(AndroidQueueInsertion.PlayNext) },
                { onQueue(AndroidQueueInsertion.AddToQueue) })
        }
    }
}
