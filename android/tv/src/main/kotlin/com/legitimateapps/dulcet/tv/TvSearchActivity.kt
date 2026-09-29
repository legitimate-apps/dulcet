package com.legitimateapps.dulcet.tv

import android.content.Context
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.legitimateapps.dulcet.library.hostInForeground
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AccountConnector
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.searchScopeLabel
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchIntentRouter
import com.legitimateapps.dulcet.search.SearchObservation
import androidx.compose.ui.semantics.semantics
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
        // Before the account is read below: a sign-out the previous process began deletes its
        // credential when this model is created (spec §14.7).
        val accountModel = ViewModelProvider(this)[TvAccountModel::class.java]
        setContent {
            MaterialTheme {
                var account by remember { mutableStateOf(loadAccount()) }
                val signedOut by accountModel.signedOut.collectAsStateWithLifecycle()
                LaunchedEffect(signedOut) { if (signedOut > 0) account = loadAccount() }
                TvAccountHost(accountModel.signOut, account) {
                    val current = account
                    if (current == null) {
                        val context = LocalContext.current
                        val connector = remember { AccountConnector() }
                        TvConnectScreen(connector::connect, remember { AndroidAccountCredentialStore(context) }) {
                            account = loadAccount()
                        }
                    } else {
                        TvLibraryEntry(current) { TvSearchRoute(current, searchDependencies) }
                    }
                }
            }
        }
    }

    private fun loadAccount(): SearchAccount? = runCatching { searchDependencies.loadAccount(this) }.getOrNull()
}

@Composable
private fun TvSearchRoute(account: SearchAccount, dependencies: SearchHostDependencies) {
    val context = LocalContext.current
    // Keyed by the whole account: a changed password or address replaces the process's reader, and a
    // presenter still attached to the old one would never hear from it again.
    val foreground = hostInForeground()
    val presenter = remember(account) { dependencies.createPresenter(account, context, foreground) }
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
    val resources = libraryResources()
    val queryFocus = remember { FocusRequester() }
    val accountEntry = LocalTvAccountEntry.current
    val resultFocus = remember(state.results.map { it.id }) {
        List(state.results.size) { FocusRequester() }
    }

    LaunchedEffect(Unit) { queryFocus.requestFocus() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            // No heading: the navigation row above names the screen, and the room goes to results.
            modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
                                stringResource(R.string.tv_search_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(queryFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                event.key == Key.DirectionDown && resultFocus.isNotEmpty() -> {
                                    resultFocus.first().requestFocus()
                                    true
                                }
                                // The field keeps UP for its cursor; the account entry sits above it.
                                event.key == Key.DirectionUp && accountEntry != null -> {
                                    accountEntry.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        }
                        .testTag("search.query"),
                )
            }
            // Where these results come from (§16.15), in the words the phone uses for the same scope.
            libraryResources().searchScopeLabel(state.scope.takeIf { state.query.isNotBlank() })?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("search.scope"))
            }
            if (state.isLoading && state.results.isEmpty()) {
                Text(stringResource(R.string.tv_search_searching), modifier = Modifier.testTag("search.loading"))
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search.results")
                    .semantics { this[SearchObservation] = state },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(state.rows) { index, row ->
                    val result = row.item
                    TvSearchResult(
                        result = result,
                        note = listOfNotNull(
                            resources.getString(SharedR.string.search_row_device_only).takeIf {
                                row.source == AndroidLibrarySearchRowSource.Device &&
                                    state.scope == AndroidLibrarySearchScope.ServerAndDevice
                            },
                            resources.getString(SharedR.string.library_not_available_offline)
                                .takeIf { row.playability == AndroidLibraryPlayability.UnavailableOffline },
                        ).joinToString(" · ").ifEmpty { null },
                        index = index,
                        focusRequester = resultFocus[index],
                        queryFocusRequester = queryFocus.takeIf { index == 0 },
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
    note: String?,
    index: Int,
    focusRequester: FocusRequester,
    /** The query field, above the first result only: UP from there goes to it. */
    queryFocusRequester: FocusRequester?,
    onActivate: () -> Unit,
) {
    Card(
        onClick = onActivate,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Between results the list's own focus search moves UP and DOWN: it composes a
                // result not yet on screen, where a requester of one never composed takes nothing,
                // which left the D-pad stuck at the last result the screen had room for.
                when (event.key) {
                    Key.DirectionUp -> if (queryFocusRequester != null) {
                        queryFocusRequester.requestFocus()
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
            Text(listOfNotNull(stringResource(result.type.label()), note).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A result's kind, in the TV's words. */
private fun SearchResultType.label(): Int = when (this) {
    SearchResultType.Album -> R.string.tv_search_type_album
    SearchResultType.Artist -> R.string.tv_search_type_artist
    SearchResultType.Track -> R.string.tv_search_type_track
}
