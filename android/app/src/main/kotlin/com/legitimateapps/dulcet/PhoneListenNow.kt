package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.ui.androidTitle
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.ArtworkEntity

/**
 * Listen Now: a collapsing top app bar with the Account avatar, one resume card, and the shelves.
 * A shelf shows skeletons only while loading, hides itself once loaded empty, and hides when most
 * of its items already appeared on a shelf above it. Freshness is a page concern, never per shelf:
 * [ListenNowFailureLine] says once when a shelf could not be read and the account notice has not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneListenNow(account: SearchAccount, session: LibrarySession, actions: PhoneActions, playbackState: AndroidPlaybackState, playback: AndroidPlaybackController?) {
    // Home owns its independent shelf windows for its whole lifetime. A lazy row may be
    // empty or offscreen when a refresh starts; it must still receive the live read.
    val shelves = ListenNowShelf.entries.associateWith { shelf ->
        rememberSurface(session, shelf) { openListenNow(shelf) }
    }
    val states = shelves.mapValues { (_, surface) -> surface.state.collectAsState() }
    val loading = states.values.any { it.value.let { p -> p == null || p.freshness == AndroidLibraryFreshness.Loading } }
    // Shelf hygiene: keep shelves with content, drop loaded-empty ones, and drop a shelf when
    // more than half of its items were already shown on an earlier visible shelf.
    val seen = mutableSetOf<String>()
    val visible = ListenNowShelf.entries.filter { shelf ->
        val current = states.getValue(shelf).value
        current == null || current.freshness == AndroidLibraryFreshness.Loading ||
            keepListenNowShelf(current.items.map { it::class.simpleName + it.rawId }, seen,
                minimum = if (shelf == ListenNowShelf.Favourites) 2 else 3)
    }
    var userRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(loading, userRefreshing) { if (!loading && userRefreshing) { kotlinx.coroutines.delay(250); userRefreshing = false } }
    val list = rememberLazyListState()
    val scrollBehavior = rememberPositionTintedBar { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 }
    val hasCard = (playbackState.hasSession && playback != null) ||
        (!playbackState.hasSession && !states.getValue(ListenNowShelf.Recent).value?.items.isNullOrEmpty())
    val resume = rememberHistoryResume(account, session, states.getValue(ListenNowShelf.Recent).value?.items?.firstOrNull()
        ?.takeIf { !playbackState.hasSession })
    val blank = !loading && !hasCard && visible.all { states.getValue(it).value?.items.isNullOrEmpty() }
    val connection by session.connection.collectAsState()
    val resources = libraryResources()
    val failure = if (connection.hasConnectionNotice()) null else states.values.firstNotNullOfOrNull { state ->
        state.value?.let { resources.listenNowFailure(it) }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            PhoneRootTopBar("Listen Now", scrollBehavior)
        },
    ) { padding ->
        // Pinned above the list: a LazyColumn holds its first item in place when a row is inserted above it.
        Column(Modifier.padding(padding)) {
        ConnectionNotices(session, accountNotices = false, savedAccount = account)
        if (!blank) failure?.let { ListenNowFailureLine(it.line, session) }
        PullToRefreshBox(isRefreshing = userRefreshing && loading, onRefresh = { userRefreshing = true; session.refresh() }) {
            LazyColumn(Modifier.fillMaxSize().testTag("listenNow.surface"), state = list, contentPadding = PaddingValues(vertical = 20.dp)) {
                // Always composed, empty until there is a card: the list keeps its first item in place, so a
                // card arriving above the shelves would otherwise open the page scrolled past it.
                item("top") {
                if (playbackState.hasSession && playback != null) {
                    Surface(
                        Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Continue listening", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Artwork(account, playbackState.artworkKey, playbackState.title, 72.dp, entity = ArtworkEntity.Song)
                                Column(Modifier.weight(1f)) {
                                    Text(playbackState.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    playbackState.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                                }
                            }
                            playbackState.durationMilliseconds?.takeIf { it > 0 }?.let { duration ->
                                LinearProgressIndicator(progress = { (playbackState.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("listenNow.resume.progress"))
                            }
                            Button(onClick = { playback.play() }, modifier = Modifier.fillMaxWidth().testTag("listenNow.resume")) { Text("Resume") }
                        }
                    }
                }
                resume?.let { HistoryResumeCard(account, actions, it) }
                }
                if (blank && failure != null) item("unreachable") { ListenNowUnreachable(failure.headline, session) }
                items(visible, key = { it.wireName }) { shelf ->
                    val current = states.getValue(shelf).value
                    val shelfTitle = shelf.androidTitle()
                    Column(Modifier.fillMaxWidth().testTag("listenNow.${shelf.wireName}").padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(shelfTitle, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                            if (shelf == ListenNowShelf.Recent && !current?.items.isNullOrEmpty()) TextButton({ actions.openRoute("recentlyPlayed") },
                                Modifier.offset(x = 12.dp).testTag("listenNow.recent.seeAll")) { Text(androidx.compose.ui.res.stringResource(com.legitimateapps.dulcet.shared.R.string.search_see_all)) }
                        }
                        val items = current?.items
                        if (items.isNullOrEmpty() && (current == null || current.freshness == AndroidLibraryFreshness.Loading)) {
                            RowPlaceholder(Modifier.testTag("listenNow.${shelf.wireName}.loading"), cardWidth = 160.dp)
                        } else {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // The card above plays this shelf's newest entry; a tile repeating it directly below says nothing new.
                                val shown = if (shelf == ListenNowShelf.Recent && resume?.item == items?.firstOrNull() && items.orEmpty().size > 1) items.orEmpty().drop(1) else items.orEmpty()
                                itemsIndexed(shown, key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                                    AlbumCard(account, item, Modifier.width(160.dp).testTag("listenNow.${shelf.wireName}.item.$position")) {
                                        when (item) {
                                            is AndroidLibraryItem.Album -> actions.openAlbum(item.rawId)
                                            is AndroidLibraryItem.Track -> actions.playTracks(items.orEmpty(), item.rawId, shelfTitle)
                                            is AndroidLibraryItem.Playlist -> actions.openPlaylist(item.rawId)
                                            else -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Why a shelf could not be read: a line to sit above shelves it left in place, and a headline for a page with none. */
private data class ListenNowFailure(val line: String, val headline: String)

/**
 * The page's one line when a shelf's read failed: the server went away mid-use while the platform
 * still reports a network, so every shelf keeps its items as Cached(Failed) and the connection stays
 * online. Nothing when the connection's own notice already says why.
 */
@Composable
private fun ListenNowFailureLine(line: String, session: LibrarySession) {
    ServerStateNotice(line, "listenNow.freshness", libraryResources().getString(SharedR.string.library_try_again), "listenNow.retry", session::retry)
}

/**
 * The same failure with no shelf to show for it. "Showing what you last saw" would be false over an
 * empty page, so the page says what went wrong and offers the one thing to do about it.
 */
@Composable
private fun LazyItemScope.ListenNowUnreachable(headline: String, session: LibrarySession) {
    Column(Modifier.testTag("listenNow.unreachable").fillParentMaxHeight(0.8f).fillMaxWidth().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(headline, Modifier.testTag("listenNow.freshness"), style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        FilledTonalButton(onClick = session::retry, modifier = Modifier.testTag("listenNow.retry")) {
            Text(libraryResources().getString(SharedR.string.library_try_again))
        }
    }
}

private fun android.content.res.Resources.listenNowFailure(publication: AndroidLibraryPublication): ListenNowFailure? {
    val freshness = publication.freshness
    fun statement(line: String) = ListenNowFailure(line, line)
    return when {
        freshness is AndroidLibraryFreshness.Cached -> when (val reason = freshness.reason) {
            is AndroidLibraryCachedReason.Failed -> freshnessLine(freshness)?.let { ListenNowFailure(it, sentence(errorPhrase(reason.error))) }
            AndroidLibraryCachedReason.InternalFailure -> freshnessLine(freshness)?.let { ListenNowFailure(it, sentence(getString(SharedR.string.library_reason_internal))) }
            else -> null
        }
        freshness is AndroidLibraryFreshness.Unavailable -> statement(unavailableLine(freshness.reason, LibrarySubject.List))
        publication.itemsState == AndroidLibraryItemsState.Unavailable -> statement(unavailableLine(
            publication.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List))
        else -> null
    }
}

private fun sentence(phrase: String) = phrase.replaceFirstChar { it.uppercase() }

/** Only shelves that are shown may reserve items against later shelves. */
internal fun keepListenNowShelf(items: List<String>, seen: MutableSet<String>, minimum: Int = 3): Boolean {
    if (items.size < minimum) return false
    if (items.count { it in seen } * 2 > items.size) return false
    seen.addAll(items)
    return true
}

/** The newest played entry and the album it opens, once it can actually play. */
private class HistoryResume(val item: AndroidLibraryItem, val publication: AndroidLibraryPublication?)

/**
 * A one-press way back to [item], or null while its album is still reading or has nothing that can play
 * here: with the server away the card would otherwise lead the page with an apology for what it cannot do.
 */
@Composable
private fun rememberHistoryResume(account: SearchAccount, session: LibrarySession, item: AndroidLibraryItem?): HistoryResume? {
    val album = item as? AndroidLibraryItem.Album ?: return item?.let { HistoryResume(it, null) }
    val publication = rememberSurface(session, "resume:${album.rawId}") { openAlbum(album.rawId) }.state.collectAsState().value
    return publication?.takeIf { it.playableTracks(account.providerInstanceId).isNotEmpty() }?.let { HistoryResume(album, it) }
}

@Composable
private fun HistoryResumeCard(account: SearchAccount, actions: PhoneActions, resume: HistoryResume) {
    val item = resume.item
    val album = item as? AndroidLibraryItem.Album
    val publication = resume.publication
    Surface(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Play again", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Artwork(account, album?.artworkKey ?: (item as? AndroidLibraryItem.Track)?.artworkKey, item.displayTitle(), 72.dp,
                    entity = if (item is AndroidLibraryItem.Track) ArtworkEntity.Song else ArtworkEntity.Album)
                Column(Modifier.weight(1f)) {
                    Text(item.displayTitle(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    item.subtitle()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                }
            }
            Button(onClick = {
                    when (item) {
                        is AndroidLibraryItem.Album -> publication?.let { actions.playAlbum(it, 0, false) }
                        is AndroidLibraryItem.Track -> actions.playTracks(listOf(item), item.rawId, "Recently played")
                        is AndroidLibraryItem.Playlist -> actions.openPlaylist(item.rawId)
                        else -> Unit
                    }
            }, modifier = Modifier.fillMaxWidth().testTag("listenNow.resume")) {
                Text(if (item is AndroidLibraryItem.Playlist) "Open playlist" else "Play")
            }
        }
    }
}
