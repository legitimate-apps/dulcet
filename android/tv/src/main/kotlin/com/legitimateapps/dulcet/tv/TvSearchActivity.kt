package com.legitimateapps.dulcet.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchIntentRouter
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencyOwner

class TvSearchActivity : ComponentActivity() {
    private val searchDependencies: SearchHostDependencies by lazy {
        (application as? SearchHostDependencyOwner)?.searchHostDependencies
            ?: ProductionSearchHostDependencies
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val account = runCatching { searchDependencies.loadAccount(this) }.getOrNull()
        setContent {
            MaterialTheme {
                if (account == null) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Text(
                            "Connect an account before searching.",
                            modifier = Modifier.padding(48.dp).testTag("search.account-required"),
                        )
                    }
                } else {
                    TvSearchRoute(account, searchDependencies)
                }
            }
        }
    }
}

@Composable
private fun TvSearchRoute(account: SearchAccount, dependencies: SearchHostDependencies) {
    val context = LocalContext.current
    val presenter = remember(account.providerInstanceId) { dependencies.createPresenter(account) }
    val router = remember(context) { dependencies.createRouter(context) }
    DisposableEffect(presenter) {
        onDispose(presenter::close)
    }
    TvSearchScreen(presenter, router)
}

@Composable
internal fun TvSearchScreen(
    presenter: SearchPresenter,
    router: SearchIntentRouter,
) {
    val state by presenter.state.collectAsStateWithLifecycle()
    val queryFocus = remember { FocusRequester() }
    val resultFocus = remember(state.results.map { it.id }) {
        List(state.results.size) { FocusRequester() }
    }

    LaunchedEffect(Unit) { queryFocus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Search", style = MaterialTheme.typography.displaySmall)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                BasicTextField(
                    value = state.query,
                    onValueChange = presenter::updateQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
                    decorationBox = { inner ->
                        if (state.query.isEmpty()) {
                            Text(
                                "Artists, albums, and tracks",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(queryFocus)
                        .onPreviewKeyEvent { event ->
                            if (
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.DirectionDown &&
                                resultFocus.isNotEmpty()
                            ) {
                                resultFocus.first().requestFocus()
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("search.query"),
                )
            }
            if (state.isLoading && state.results.isEmpty()) {
                Text("Searching…", modifier = Modifier.testTag("search.loading"))
            } else if (state.error != null) {
                Text("Search could not be completed.", modifier = Modifier.testTag("search.error"))
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search.results"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.results) { index, result ->
                    TvSearchResult(
                        result = result,
                        index = index,
                        focusRequester = resultFocus[index],
                        previousFocusRequester = resultFocus.getOrNull(index - 1) ?: queryFocus,
                        nextFocusRequester = resultFocus.getOrNull(index + 1),
                        onActivate = { router.activate(result) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSearchResult(
    result: SearchResultItem,
    index: Int,
    focusRequester: FocusRequester,
    previousFocusRequester: FocusRequester,
    nextFocusRequester: FocusRequester?,
    onActivate: () -> Unit,
) {
    Card(
        onClick = onActivate,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        previousFocusRequester.requestFocus()
                        true
                    }
                    Key.DirectionDown -> if (nextFocusRequester != null) {
                        nextFocusRequester.requestFocus()
                        true
                    } else {
                        false
                    }
                    Key.Enter, Key.DirectionCenter -> {
                        onActivate()
                        true
                    }
                    else -> false
                }
            }
            .testTag("search.result.$index"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(result.title, style = MaterialTheme.typography.titleLarge)
            Text(result.type.name, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
