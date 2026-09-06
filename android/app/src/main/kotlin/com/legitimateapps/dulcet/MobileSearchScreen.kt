package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
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
) {
    val context = LocalContext.current
    val presenter = remember(account.providerInstanceId) { dependencies.createPresenter(account) }
    val router = remember(context) { dependencies.createRouter(context) }
    DisposableEffect(presenter) {
        onDispose(presenter::close)
    }
    MobileSearchScreen(presenter, router)
}

@Composable
internal fun MobileSearchScreen(
    presenter: SearchPresenter,
    router: SearchIntentRouter,
) {
    val state by presenter.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                value = state.query,
                onValueChange = presenter::updateQuery,
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.results) { index, result ->
                    MobileSearchResult(
                        result = result,
                        index = index,
                        onActivate = { router.activate(result) },
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
    onActivate: () -> Unit,
) {
    Card(
        onClick = onActivate,
        modifier = Modifier.fillMaxWidth().testTag("search.result.$index"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(result.title, style = MaterialTheme.typography.titleMedium)
            Text(result.type.name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
