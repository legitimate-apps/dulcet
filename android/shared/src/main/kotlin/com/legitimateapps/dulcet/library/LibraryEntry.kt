package com.legitimateapps.dulcet.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryHomeRow
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidAlbumListType
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidQueueSource
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.rememberPlaybackController
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R

/** State observations carry no account data: catalog ids and freshness, from the real session. */
public val LibraryObservation: SemanticsPropertyKey<LibraryObservationState> = SemanticsPropertyKey("LibraryObservation")

/**
 * The TV app's library, beside its search. The account's [LibrarySession] lives as long as this entry
 * — on the search screen too — so reachability and the foreground are reported whichever screen is
 * showing, and a search offline says so (§16.15).
 */
@Composable
public fun LibraryEntry(account: SearchAccount, search: @Composable () -> Unit) {
    val context = LocalContext.current
    val session = remember(account) { LibrarySession(context, account) }
    var showingLibrary by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        when {
            !showingLibrary -> search()
            album != null -> TvAlbumScreen(session, account.providerInstanceId, album!!) { album = null }
            else -> TvLibraryHome(session) { album = it }
        }
        BasicText(if (showingLibrary) "Search" else "Library",
            Modifier.align(Alignment.TopEnd).testTag("library.open")
                .background(Color.White).clickable { showingLibrary = !showingLibrary; album = null }.padding(16.dp))
    }
    // On a return to the foreground the screens above are already open when start() reconnects. At
    // launch the TV shows search, and the library's windows open when the person turns to it —
    // usually after the reconnect has read the epoch; each publishes its cache before its own read.
    LibraryLifecycle(session)
}

/** Starts and stops [session] with the host's lifecycle and closes it when it leaves composition. */
@Composable
public fun LibraryLifecycle(session: LibrarySession) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.start()
                Lifecycle.Event.ON_STOP -> session.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.close()
        }
    }
}

/**
 * The home screen's rows, opened WHILE COMPOSING — so each paints from the cache in its first frame,
 * before its own read is issued — and closed when the screen leaves composition, so the reader's
 * visible screen is exactly what is shown.
 */
@Composable
public fun rememberHomeRows(session: LibrarySession): List<LibraryHomeRowSurface> =
    remember(session) { Opened(session.openHome()) { rows -> rows.forEach { it.surface.close() } } }.value

/** One surface, opened while composing and closed when it leaves composition (see [rememberHomeRows]). */
@Composable
public fun rememberSurface(session: LibrarySession, key: Any?, open: LibrarySession.() -> LibrarySurface): LibrarySurface =
    remember(session, key) { Opened(session.open()) { it.close() } }.value

private class Opened<T>(val value: T, private val close: (T) -> Unit) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = close(value)
    override fun onAbandoned() = close(value)
}

@Composable
private fun TvLibraryHome(session: LibrarySession, openAlbum: (String) -> Unit) {
    val rows = rememberHomeRows(session)
    val observation by session.observation.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize().testTag("library.surface").semantics { this[LibraryObservation] = observation }
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { BasicText("Library", style = TextStyle(fontSize = 32.sp)) }
        item { TvConnectionNotices(session, accountNotices = true) }
        itemsIndexed(rows) { index, row -> TvHomeRow(index, row, session, openAlbum) }
    }
}

@Composable
private fun TvHomeRow(index: Int, row: LibraryHomeRowSurface, session: LibrarySession, openAlbum: (String) -> Unit) {
    val publication by row.surface.state.collectAsState()
    val resources = libraryResources()
    Column(Modifier.fillMaxWidth().testTag("library.home.$index"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(resources.getString(row.row.titleResource()), style = TextStyle(fontSize = 22.sp))
        val current = publication ?: return@Column
        resources.freshnessLine(current.freshness)?.let { line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(line, Modifier.testTag("library.home.$index.freshness"), style = TextStyle(color = Color.DarkGray))
                if (current.freshness.offersRetry()) TvButton(resources.getString(R.string.library_try_again), "library.home.$index.retry") { session.retry() }
            }
        }
        when (val freshness = current.freshness) {
            AndroidLibraryFreshness.Loading -> BasicText("…", Modifier.testTag("library.home.$index.loading"))
            is AndroidLibraryFreshness.Unavailable -> BasicText(
                resources.unavailableLine(freshness.reason, LibrarySubject.List),
                Modifier.testTag("library.home.$index.unavailable"),
            )
            else -> if (current.items.isEmpty()) {
                BasicText(resources.getString(R.string.library_empty_list), Modifier.testTag("library.home.$index.empty"))
            } else {
                LazyRow(Modifier.testTag("library.home.$index.items"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(current.items, key = { _, item -> item::class.simpleName + item.rawId }) { position, item ->
                        TvCard(item, Modifier.testTag("library.home.$index.item.$position")) {
                            if (item is AndroidLibraryItem.Album) openAlbum(item.rawId)
                        }
                    }
                }
            }
        }
    }
}

/** The phone's `ConnectionNotices`, in the TV's type: the same statements, from the same copy. */
@Composable
private fun TvConnectionNotices(session: LibrarySession, accountNotices: Boolean) {
    val connection by session.connection.collectAsState()
    val discarded by session.discardedChanges.collectAsState()
    val resources = libraryResources()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        resources.connectionLine(connection)?.let { line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(line, Modifier.testTag("library.connection"), style = TextStyle(color = Color(0xFFB00020)))
                TvButton(resources.getString(R.string.library_try_again), "library.connection.retry", onClick = session::retry)
            }
        }
        if (accountNotices) {
            resources.noEpochLine(connection)?.let { BasicText(it, Modifier.testTag("library.noEpoch")) }
            resources.discardedChangesLine(discarded)?.let { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BasicText(line, Modifier.testTag("library.discarded"))
                    TvButton(resources.getString(R.string.library_dismiss), "library.discarded.dismiss",
                        onClick = session::dismissDiscardedChanges)
                }
            }
        }
    }
}

@Composable
private fun TvAlbumScreen(session: LibrarySession, provider: String, rawId: String, back: () -> Unit) {
    val surface = rememberSurface(session, rawId) { openAlbum(rawId) }
    val context = LocalContext.current
    val playback = rememberPlaybackController()
    BackHandler(onBack = back)
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val outcomes by session.outcomes.collectAsState()
    // Only an outcome about this album is said here, and it goes when the screen does.
    val target = AndroidLibraryEntity(AndroidLibraryEntityKind.Album, rawId)
    val outcome = outcomes[target]
    DisposableEffect(session, target) { onDispose { session.dismissOutcome(target) } }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    val resources = libraryResources()
    LazyColumn(
        Modifier.fillMaxSize().testTag("album.surface").semantics { this[LibraryObservation] = observation }
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("Back", "album.back", onClick = back)
                TvButton("Refresh", "album.refresh", onClick = session::refresh)
            }
        }
        item { TvConnectionNotices(session, accountNotices = false) }
        val current = publication ?: return@LazyColumn
        val album = current.header as? AndroidLibraryItem.Album
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(album?.title.orEmpty(), Modifier.testTag("album.title"), style = TextStyle(fontSize = 32.sp))
                album?.artistName?.let { BasicText(it) }
                resources.freshnessLine(current.freshness)?.let { line ->
                    BasicText(line, Modifier.testTag("album.freshness"), style = TextStyle(color = Color.DarkGray))
                }
                (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                    BasicText(resources.unavailableLine(unavailable.reason, LibrarySubject.Album), Modifier.testTag("album.unavailable"))
                }
                if (album != null) {
                    val favourite = album.favourite == true
                    TvButton(
                        resources.getString(if (favourite) R.string.library_favourite_remove else R.string.library_favourite_add),
                        "album.favourite",
                        description = resources.getString(if (favourite) R.string.library_favourite_on else R.string.library_favourite_add),
                    ) { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, album.rawId)) }
                }
                resources.outcomeLine(outcome)?.let { BasicText(it, Modifier.testTag("album.outcome")) }
                note?.let { BasicText(it, Modifier.testTag("album.note")) }
            }
        }
        when (current.itemsState) {
            AndroidLibraryItemsState.Loading -> item { BasicText("…", Modifier.testTag("album.tracks.loading")) }
            AndroidLibraryItemsState.Unavailable -> if (current.header != null) item {
                // The header is cached and the track list is not: the core says why (§16.14).
                BasicText(
                    resources.unavailableLine(
                        current.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure,
                        LibrarySubject.Album,
                    ),
                    Modifier.testTag("album.tracks.unavailable"),
                )
            }
            AndroidLibraryItemsState.Present -> itemsIndexed(current.items) { position, item ->
                if (item is AndroidLibraryItem.Track) TvTrackRow(
                    item,
                    position,
                    onPlay = {
                        // The album from this track, as the phone plays it; then the TV's Now Playing.
                        // A note that a track plays only on reconnect no longer applies.
                        note = null
                        if (playAlbum(playback, provider, current, item.rawId)) {
                            context.startActivity(PlaybackIntents.showNowPlaying(context))
                        }
                    },
                    onUnavailable = { note = resources.getString(R.string.library_plays_on_reconnect) },
                )
            }
        }
    }
}

/**
 * The album's playable tracks queued from [trackRawId] (§14.1): the phone's album play. False, and
 * nothing played, before the playback service is bound or when nothing in the album can play.
 */
private fun playAlbum(playback: AndroidPlaybackController?, provider: String, publication: AndroidLibraryPublication, trackRawId: String): Boolean {
    val album = publication.header as? AndroidLibraryItem.Album ?: return false
    val tracks = publication.playableTracks(provider)
    if (playback == null || tracks.isEmpty()) return false
    val start = tracks.indexOfFirst { it.rawId == trackRawId }.takeIf { it >= 0 } ?: 0
    playback.playQueue(tracks, start, AndroidQueueSource.Album, album.title, album.rawId, false)
    return true
}

/**
 * One track. Selected, a row that can play plays the album from it; a row this device cannot play
 * offline says why, as the phone's does (§16.14). Every row is ONE focus target of one kind, so the
 * D-pad stops on it once, and a row whose playability changes while focused (going offline, or
 * reconnecting) keeps its focus. Its action is the centre key or Enter, and a click for
 * accessibility services.
 */
@Composable
private fun TvTrackRow(track: AndroidLibraryItem.Track, position: Int, onPlay: () -> Unit, onUnavailable: () -> Unit) {
    val unavailable = track.playability == AndroidLibraryPlayability.UnavailableOffline
    val select = if (unavailable) onUnavailable else onPlay
    val resources = libraryResources()
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag("album.track.$position")
            // One accessibility node whatever the playability: focusable() does not merge the row's
            // texts, so this does. A row that cannot play is described as such.
            .semantics(mergeDescendants = true) {
                if (unavailable) {
                    contentDescription = "${track.title.orEmpty()}, ${resources.getString(R.string.library_not_available_offline)}"
                }
                onClick { select(); true }
            }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                val centre = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (centre && event.type == KeyEventType.KeyUp) {
                    select()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .background(if (focused) Color.LightGray else Color.White).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText((track.trackNumber ?: position + 1).toString(), Modifier.width(32.dp))
        BasicText(track.title.orEmpty(), style = TextStyle(color = if (unavailable) Color.Gray else Color.Black))
        if (unavailable) BasicText(resources.getString(R.string.library_not_available_offline), style = TextStyle(color = Color.Gray))
    }
}

@Composable
private fun TvCard(item: AndroidLibraryItem, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        // One focus target: `clickable` is focusable itself, and `onFocusChanged` observes it.
        modifier.width(200.dp).onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .background(if (focused) Color.LightGray else Color(0xFFF2F2F2)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(item.displayTitle(), maxLines = 2)
        item.subtitle()?.let { BasicText(it, maxLines = 1, style = TextStyle(color = Color.DarkGray)) }
    }
}

@Composable
private fun TvButton(label: String, tag: String, description: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BasicText(
        label,
        Modifier.testTag(tag).semantics { description?.let { contentDescription = it } }
            .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick)
            .background(if (focused) Color.LightGray else Color(0xFFE6E6E6))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** The title of a home row, in the shared words. */
public fun AndroidLibraryHomeRow.titleResource(): Int = when (this) {
    AndroidLibraryHomeRow.Favourites -> R.string.library_home_favourites
    is AndroidLibraryHomeRow.Albums -> when (type) {
        AndroidAlbumListType.Recent -> R.string.library_home_recent
        AndroidAlbumListType.Frequent -> R.string.library_home_frequent
        else -> R.string.library_home_newest
    }
}

/** A card's title for any kind of row. */
public fun AndroidLibraryItem.displayTitle(): String = when (this) {
    is AndroidLibraryItem.Album -> title
    is AndroidLibraryItem.Artist -> name
    is AndroidLibraryItem.Track -> title.orEmpty()
    is AndroidLibraryItem.Playlist -> name
    is AndroidLibraryItem.Genre -> rawId
}

private fun AndroidLibraryItem.subtitle(): String? = when (this) {
    is AndroidLibraryItem.Album -> artistName
    is AndroidLibraryItem.Track -> artistName ?: albumTitle
    else -> null
}
