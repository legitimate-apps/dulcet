package com.legitimateapps.dulcet.tv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import com.legitimateapps.dulcet.library.hostInForeground
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AccountConnector
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.searchFailedBody
import com.legitimateapps.dulcet.library.searchScopeLabel
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchActivation
import com.legitimateapps.dulcet.search.SearchEmptyState
import com.legitimateapps.dulcet.search.SearchObservation
import com.legitimateapps.dulcet.search.emptyState
import androidx.compose.ui.semantics.semantics
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencyOwner
import com.legitimateapps.dulcet.ui.DulcetIcons

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
            // A 10-foot app is dark: the room's display is the couch's, day or night.
            MaterialTheme(colorScheme = darkColorScheme()) {
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
                        val presenter = rememberSearchPresenter(current, searchDependencies)
                        TvLibraryEntry(current, restoredAccount = true) { navigator, playback ->
                            TvSearchScreen(presenter, account = current, onActivate = rememberSearchActivation(navigator),
                                playback = playback)
                        }
                    }
                }
            }
        }
    }

    private fun loadAccount(): SearchAccount? = runCatching { searchDependencies.loadAccount(this) }.getOrNull()
}

/**
 * The account's search presenter, kept for as long as the account's library entry is: above the
 * routes, so a result opened and left with Back finds its query, results and focus as they were.
 * Keyed by the whole account: a changed password or address replaces the process's reader, and a
 * presenter still attached to the old one would never hear from it again.
 */
@Composable
private fun rememberSearchPresenter(account: SearchAccount, dependencies: SearchHostDependencies): SearchPresenter {
    val context = LocalContext.current
    val foreground = hostInForeground()
    val presenter = remember(account) { dependencies.createPresenter(account, context, foreground) }
    DisposableEffect(presenter) {
        onDispose(presenter::close)
    }
    return presenter
}

/** An album or artist result opens the library's detail screen above search; a track plays. */
@Composable
private fun rememberSearchActivation(navigator: TvNavigator): (SearchResultItem) -> Unit {
    val context = LocalContext.current
    val activation = remember(context, navigator) {
        SearchActivation(context, openAlbum = navigator::openAlbum, openArtist = navigator::openArtist)
    }
    return activation::activate
}

@Composable
internal fun TvSearchScreen(
    presenter: SearchPresenter,
    /** Rows show cover art when the account is known; tests without one draw the placeholder. */
    account: SearchAccount? = null,
    /** The playback controller, for Play Next and Add to Queue on a track result (spec §14.1). */
    playback: AndroidPlaybackController? = null,
    onActivate: (SearchResultItem) -> Unit,
) {
    val state by presenter.state.collectAsStateWithLifecycle()
    val resultsList = rememberLazyListState()
    // A new query starts at its first group. Keeping the old scroll anchor could leave that
    // group's focus requester uncomposed when the remote moves DOWN from the query field.
    LaunchedEffect(state.query) { resultsList.scrollToItem(0) }
    val recents by presenter.recents.collectAsStateWithLifecycle()
    val recentError by presenter.recentSearchError.collectAsStateWithLifecycle()
    var expanded by remember(state.query) { mutableStateOf(setOf<SearchResultType>()) }
    val resources = libraryResources()
    val queryFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val failed = state.emptyState is SearchEmptyState.Failed
    val navFocus = LocalTvNavFocus.current
    // One read of the rows for this composition, used by the list and its focus requesters alike.
    // The list's items are read when it measures, not when this composes; reading the state again
    // there let a publication landing between the two (the results arriving) meet requesters sized
    // for the rows before it, and the first result threw.
    val rows = state.rows
    val groupFocus = remember(state.query) { SearchResultType.entries.associateWith { FocusRequester() } }
    val focusScope = rememberCoroutineScope()
    val currentQuery by rememberUpdatedState(state.query)
    val rowIds = rows.map { it.item.id }
    val currentRowIds by rememberUpdatedState(rowIds)
    val currentExpanded by rememberUpdatedState(expanded)
    val headerPrefix = (if (recentError != null) 1 else 0) +
        (if (state.query.isBlank() && recents.isNotEmpty()) recents.size + 1 else 0)
    val currentHeaderPrefix by rememberUpdatedState(headerPrefix)
    val firstResult = rows.indexOfFirst { it.item.type == SearchResultType.Artist }.takeIf { it >= 0 }
        ?: rows.indexOfFirst { it.item.type == SearchResultType.Album }.takeIf { it >= 0 } ?: 0
    val rowsScope = state.scope
    val resultFocus = remember(rows.map { it.item.id }) {
        List(rows.size) { FocusRequester() }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    // At launch the D-pad starts on the navigation row above, never in the field: an editable field
    // with focus brings up the on-screen keyboard, which takes the remote until Back closes it. Coming
    // back to search, focus returns instead to what last held it (TvRouteFocus).
    //
    // The field is read-only until it is selected — the centre key, or a tap — so the D-pad can pass
    // over it without the keyboard coming up. A read-only field opens no input session, which is what
    // keeps the keyboard down; `showKeyboardOnFocus = false` alone did not on an Android TV emulator.
    // Selecting it makes it editable, which brings the keyboard up; leaving it makes it read-only again.
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) { withFrameNanos { }; keyboard?.show() }
    }
    var fieldFocused by remember { mutableStateOf(false) }
    EnterRoute()
    val route = LocalTvRouteFocus.current
    LaunchedEffect(Unit) {
        if (route == null || route.claimDefault()) runCatching { navFocus?.requestFocus() }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            // No heading: the navigation row above names the screen, and the room goes to results.
            modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .border(2.dp,
                        if (fieldFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                BasicTextField(
                    value = state.query,
                    onValueChange = presenter::updateQuery,
                    singleLine = true,
                    readOnly = !editing,
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
                        .onFocusChanged { fieldFocused = it.isFocused; if (!it.isFocused) editing = false }
                        // A tap selects it too (the pointer's down, before the field's own gestures).
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                editing = true
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                // Selecting the field is what asks for the keyboard, whatever device
                                // the key came from; again, once Back has closed it.
                                event.key == Key.DirectionCenter || event.key == Key.Enter -> {
                                    if (editing) keyboard?.show() else editing = true
                                    true
                                }
                                event.key == Key.DirectionDown && resultFocus.isNotEmpty() -> {
                                    resultFocus[rows.indexOfFirst { it.item.type == SearchResultType.Artist }.takeIf { it >= 0 }
                                        ?: rows.indexOfFirst { it.item.type == SearchResultType.Album }.takeIf { it >= 0 } ?: 0].requestFocus()
                                    true
                                }
                                // The field keeps DOWN too, so the way to Try Again is explicit.
                                event.key == Key.DirectionDown && failed -> {
                                    runCatching { retryFocus.requestFocus() }.isSuccess
                                }
                                // The field keeps UP for its cursor; the navigation bar is above it.
                                event.key == Key.DirectionUp && navFocus != null -> {
                                    navFocus.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        }
                        .tvFocus("search.query"),
                )
            }
            val empty = state.emptyState
            // Where these results come from (§16.15), in the words the phone uses for the same scope.
            // With nothing to show, a failure says it all below; the line would only repeat it.
            resources.searchScopeLabel(
                state.scope.takeIf { state.query.isNotBlank() && empty !is SearchEmptyState.Failed },
            )?.let { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("search.scope"))
            }
            if (state.isLoading && state.results.isEmpty()) {
                Text(stringResource(R.string.tv_search_searching), modifier = Modifier.testTag("search.loading"))
            }
            // Only a search the server answered says "no matching music"; a failed or offline one
            // never heard from it (§16.15), so it says what it knows: this device has none.
            when (empty) {
                null -> Unit
                SearchEmptyState.NoMatches -> Text(
                    stringResource(R.string.tv_search_empty),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("search.empty"),
                )
                SearchEmptyState.NoMatchesOnDevice -> Text(
                    stringResource(SharedR.string.search_offline_empty_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("search.empty.offline"),
                )
                is SearchEmptyState.Failed -> Column(
                    modifier = Modifier.fillMaxWidth().testTag("search.failed"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(SharedR.string.search_failed_title), style = MaterialTheme.typography.titleLarge)
                    Text(resources.searchFailedBody(empty.error), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("search.failed.body"))
                    // Reached from the field by DOWN, as the list is. The button leaves as the search
                    // starts again, so focus goes back to the field rather than to nothing.
                    Button(
                        onClick = { presenter.refresh(); runCatching { queryFocus.requestFocus() } },
                        modifier = Modifier.focusRequester(retryFocus).tvFocus("search.retry"),
                    ) {
                        Text(stringResource(SharedR.string.library_try_again))
                    }
                }
            }
            LazyColumn(
                state = resultsList,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search.results")
                    .semantics { this[SearchObservation] = state },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (recentError != null) item { Text(stringResource(SharedR.string.library_depth_save_failed), Modifier.testTag("search.recents.error")) }
                if (state.query.isBlank() && recents.isNotEmpty()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(SharedR.string.search_recent), style = MaterialTheme.typography.titleLarge)
                            TvAction(stringResource(SharedR.string.search_clear), "search.recents.clear", onClick = presenter::clearRecents)
                        }
                    }
                    itemsIndexed(recents) { index, query ->
                        TvAction(query, "search.recent.$index") { presenter.updateQuery(query); presenter.commitQuery() }
                    }
                }
                var nextHeader = headerPrefix
                for (type in listOf(SearchResultType.Artist, SearchResultType.Album, SearchResultType.Track)) {
                    val all = rows.withIndex().filter { it.value.item.type == type }
                    val collapsed = all.size > 4 && type !in expanded
                    val groupRows = if (type in expanded) all else all.take(4)
                    val headerIndex = nextHeader
                    if (all.isNotEmpty()) nextHeader += 1 + groupRows.size
                    if (all.isNotEmpty()) item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(when (type) {
                                SearchResultType.Artist -> SharedR.string.search_group_artists
                                SearchResultType.Album -> SharedR.string.search_group_albums
                                SearchResultType.Track -> SharedR.string.search_group_songs
                            }), style = MaterialTheme.typography.titleLarge)
                            if (collapsed) TvAction(stringResource(SharedR.string.search_see_all),
                                "search.seeAll.${type.name.lowercase()}",
                                modifier = Modifier.focusRequester(groupFocus.getValue(type)).onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) false
                                    else when (event.key) {
                                        Key.DirectionUp -> if (all.first().index == firstResult) {
                                            queryFocus.requestFocus(); true
                                        } else false
                                        else -> false
                                    }
                                }) {
                                expanded = expanded + type
                                // The expanding control leaves composition; keep the remote on its group.
                                resultFocus[all.first().index].requestFocus()
                            }
                        }
                    }
                    itemsIndexed(groupRows, key = { _, indexed -> indexed.index }) { _, indexed ->
                    val index = indexed.index
                    val row = indexed.value
                    val result = row.item
                    val context = LocalContext.current
                    val addition = tvSearchAddition(context, playback, account?.providerInstanceId, result, row.playability)
                    TvSearchResult(
                        result = result,
                        note = listOfNotNull(
                            result.credits.joinToString { it.name }.takeIf { it.isNotBlank() },
                            resources.getString(SharedR.string.search_row_device_only).takeIf {
                                row.source == AndroidLibrarySearchRowSource.Device &&
                                    rowsScope == AndroidLibrarySearchScope.ServerAndDevice
                            },
                            resources.getString(SharedR.string.library_not_available_offline)
                                .takeIf { row.playability == AndroidLibraryPlayability.UnavailableOffline },
                        ).joinToString(" · ").ifEmpty { null },
                        index = index,
                        account = account,
                        addition = addition,
                        focusRequester = resultFocus[index],
                        queryFocusRequester = queryFocus.takeIf { index == firstResult },
                        onMoveUp = if (collapsed && index == all.first().index) ({
                            val requestedQuery = state.query
                            val requestedRows = rowIds
                            val requestedExpanded = expanded
                            val requestedPrefix = headerPrefix
                            focusScope.launch {
                                // The group header can be outside the lazy viewport after walking back.
                                resultsList.scrollToItem(headerIndex)
                                withFrameNanos { }
                                // A server merge or a new query may replace this header while scrolling.
                                if (currentQuery == requestedQuery && currentRowIds == requestedRows &&
                                    currentExpanded == requestedExpanded && currentHeaderPrefix == requestedPrefix) {
                                    groupFocus.getValue(type).requestFocus()
                                }
                            }
                            Unit
                        }) else null,
                        onActivate = { presenter.commitQuery(); onActivate(result) },
                    )
                }
                }
            }
        }
    }
}

@Composable
internal fun TvSearchResult(
    result: SearchResultItem,
    note: String?,
    index: Int,
    account: SearchAccount?,
    addition: TvQueueAddition?,
    focusRequester: FocusRequester,
    /** The query field, above the first result only: UP from there goes to it. */
    queryFocusRequester: FocusRequester?,
    /** A collapsed group's first row exposes its See all control above it. */
    onMoveUp: (() -> Unit)? = null,
    onActivate: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    if (addition != null) TvAddToUpNext(if (adding) addition else null) { adding = false }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            onClick = onActivate,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    // The centre key activates once, on release, as the Card's own click would. The Card
                    // is kept from seeing it: it clicked on the release of a press this handler had also
                    // acted on, and every result opened twice (Back then left a duplicate on top).
                    if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (event.type == KeyEventType.KeyUp) onActivate()
                        return@onPreviewKeyEvent true
                    }
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // Between results the list's own focus search moves UP and DOWN: it composes a
                    // result not yet on screen, where a requester of one never composed takes nothing,
                    // which left the D-pad stuck at the last result the screen had room for.
                    when (event.key) {
                        Key.DirectionUp -> if (onMoveUp != null) {
                            onMoveUp()
                            true
                        } else if (queryFocusRequester != null) {
                            queryFocusRequester.requestFocus()
                            true
                        } else {
                            false
                        }
                        else -> false
                    }
                }
                .tvFocus("search.result.$index"),
            // The same obvious focus as the library's cards: a touch of growth and a ring.
            scale = CardDefaults.scale(focusedScale = 1.02f),
            border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary))),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvArtwork(account, result.artworkKey, 72, when (result.type) {
                    SearchResultType.Artist -> DulcetIcons.Person
                    SearchResultType.Album -> DulcetIcons.Album
                    SearchResultType.Track -> DulcetIcons.MusicNote
                })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(stringResource(result.type.label()), note).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Play Next and Add to Queue for a track result, as a library row offers them (spec §14.1).
        // Beside the card, not in it: the card's centre key opens the result, so the button is its own
        // focus target, reached by one RIGHT, as the library rows' queue button is.
        if (addition != null) {
            Button(onClick = { adding = true }, modifier = Modifier.tvFocus("search.result.$index.queue")) {
                Text(stringResource(SharedR.string.queue_add_options))
            }
        }
    }
}

/** A result's kind, in the TV's words. */
private fun SearchResultType.label(): Int = when (this) {
    SearchResultType.Album -> R.string.tv_search_type_album
    SearchResultType.Artist -> R.string.tv_search_type_artist
    SearchResultType.Track -> R.string.tv_search_type_track
}
