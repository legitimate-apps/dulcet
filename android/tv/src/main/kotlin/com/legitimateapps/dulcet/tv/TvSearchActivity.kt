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
import androidx.compose.runtime.setValue
import com.legitimateapps.dulcet.library.hostInForeground
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AccountConnector
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
import com.legitimateapps.dulcet.library.searchScopeLabel
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchActivation
import com.legitimateapps.dulcet.search.SearchObservation
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
                        TvLibraryEntry(current) { navigator ->
                            TvSearchScreen(presenter, rememberSearchActivation(navigator), account = current)
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
    onActivate: (SearchResultItem) -> Unit,
    /** Rows show cover art when the account is known; tests without one draw the placeholder. */
    account: SearchAccount? = null,
) {
    val state by presenter.state.collectAsStateWithLifecycle()
    val resources = libraryResources()
    val queryFocus = remember { FocusRequester() }
    val accountEntry = LocalTvAccountEntry.current
    // One read of the rows for this composition, used by the list and its focus requesters alike.
    // The list's items are read when it measures, not when this composes; reading the state again
    // there let a publication landing between the two (the results arriving) meet requesters sized
    // for the rows before it, and the first result threw.
    val rows = state.rows
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
        if (route == null || route.claimDefault()) runCatching { accountEntry?.requestFocus() }
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
                        .tvFocus("search.query"),
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
                itemsIndexed(rows) { index, row ->
                    val result = row.item
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
                        focusRequester = resultFocus[index],
                        queryFocusRequester = queryFocus.takeIf { index == 0 },
                        onActivate = { onActivate(result) },
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
    account: SearchAccount?,
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
                    Key.DirectionUp -> if (queryFocusRequester != null) {
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(result.title, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(stringResource(result.type.label()), note).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
