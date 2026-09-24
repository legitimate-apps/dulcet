package com.legitimateapps.dulcet

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.ui.DulcetIcons
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchIntentRouter
import com.legitimateapps.dulcet.search.SearchPresenter

@Composable
internal fun MobileSearchRoute(
    account: SearchAccount,
    dependencies: SearchHostDependencies,
    onPlay: ((SearchResultItem) -> Unit)? = null,
) {
    val context = LocalContext.current
    val presenter = remember(account.providerInstanceId) { dependencies.createPresenter(account, context) }
    val router = remember(context) { dependencies.createRouter(context) }
    DisposableEffect(presenter) {
        onDispose(presenter::close)
    }
    MobileSearchScreen(presenter, router, account, onPlay)
}

@Composable
internal fun MobileSearchScreen(
    presenter: SearchPresenter,
    router: SearchIntentRouter,
    account: SearchAccount? = null,
    onPlay: ((SearchResultItem) -> Unit)? = null,
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
            when {
                state.error != null -> Text(
                    stringResource(R.string.search_failed),
                    modifier = Modifier.testTag("search.error"),
                )
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
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search.results"),
            ) {
                itemsIndexed(state.results) { index, result ->
                    MobileSearchResult(
                        result = result,
                        index = index,
                        account = account,
                        onActivate = { router.activate(result) },
                        onPlay = onPlay?.takeIf { result.type == SearchResultType.Track }?.let { play -> { play(result) } },
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
    onActivate: () -> Unit,
    onPlay: (() -> Unit)?,
) {
    val kind = stringResource(when (result.type) {
        SearchResultType.Artist -> R.string.search_kind_artist
        SearchResultType.Album -> R.string.search_kind_album
        SearchResultType.Track -> R.string.search_kind_track
    })
    val credits = result.credits.joinToString { it.name }
    ListItem(
        headlineContent = { Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOf(kind, credits).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            if (account != null && result.type != SearchResultType.Artist) Artwork(account, result.artworkKey, result.title, 48.dp)
            else Icon(if (result.type == SearchResultType.Artist) DulcetIcons.Person else DulcetIcons.MusicNote, null)
        },
        trailingContent = onPlay?.let { play ->
            {
                IconButton(onClick = play, modifier = Modifier.testTag("search.play.$index")) {
                    Icon(DulcetIcons.Play, stringResource(R.string.action_play_track, result.title))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onActivate).testTag("search.result.$index"),
    )
}
