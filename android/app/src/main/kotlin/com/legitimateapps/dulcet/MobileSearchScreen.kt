package com.legitimateapps.dulcet

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.legitimateapps.dulcet.library.searchScopeLabel
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchObservation
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
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().testTag("search.query"),
            )
            // Where these results come from (§16.15): the core's scope, in the shared words. A server
            // that failed or cannot be reached leaves the device's rows showing, labelled.
            val scopeLine = libraryResources().searchScopeLabel(state.scope.takeIf { state.query.isNotBlank() })
            if (scopeLine != null) Text(
                scopeLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("search.scope"),
            )
            when {
                state.isLoading && state.results.isEmpty() -> Text(
                    stringResource(R.string.search_loading),
                    modifier = Modifier.testTag("search.loading"),
                )
                state.query.isNotBlank() && !state.isLoading && state.results.isEmpty() -> Text(
                    stringResource(R.string.search_empty),
                    modifier = Modifier.testTag("search.empty"),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search.results")
                    .semantics { this[SearchObservation] = state },
            ) {
                itemsIndexed(state.rows) { index, row ->
                    val result = row.item
                    val playable = row.playability != AndroidLibraryPlayability.UnavailableOffline
                    MobileSearchResult(
                        result = result,
                        index = index,
                        account = account,
                        // Only beside the server's rows is "on this device" news; the scope line covers the rest.
                        deviceOnly = row.source == AndroidLibrarySearchRowSource.Device &&
                            state.scope == AndroidLibrarySearchScope.ServerAndDevice,
                        unavailableOffline = !playable,
                        onActivate = { onActivate(result) },
                        onPlay = onPlay?.takeIf { result.type == SearchResultType.Track && playable }?.let { play -> { play(result) } },
                        onQueue = onQueue?.takeIf { result.type == SearchResultType.Track && playable }
                            ?.let { queue -> { insertion -> queue(result, insertion) } },
                    )
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
        headlineContent = { Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val notes = listOfNotNull(
                resources.getString(SharedR.string.search_row_device_only).takeIf { deviceOnly },
                resources.getString(SharedR.string.library_not_available_offline).takeIf { unavailableOffline },
            )
            Text((listOf(kind, credits) + notes).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (account != null && result.type != SearchResultType.Artist) Artwork(account, result.artworkKey, result.title, 48.dp)
            else Icon(if (result.type == SearchResultType.Artist) DulcetIcons.Person else DulcetIcons.MusicNote, null)
        },
        trailingContent = if (onPlay == null && onQueue == null) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onPlay?.let { play ->
                        IconButton(onClick = play, modifier = Modifier.testTag("search.play.$index")) {
                            Icon(DulcetIcons.Play, stringResource(R.string.action_play_track, result.title))
                        }
                    }
                    onQueue?.let { queue ->
                        SearchResultQueueMenu(index, { insertion -> queue(insertion) })
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onActivate).testTag("search.result.$index"),
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
