package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.library.canBeQueued
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.AssistChip
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.library.*
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.rememberPlaybackBinding
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons

/**
 * A list page's one controls row: the filter chips (the active one checked), then one Sort button
 * naming the current order and opening a radio choice. Playlists filter by text; everything else
 * offers All / Favorites / Downloaded.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvLibraryCategoryControls(state: LibraryCategoryState, showSort: Boolean = true,
                                       sorts: List<Pair<String, String>> = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)")) {
    var choosingSort by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showSort) {
            val current = sorts.firstOrNull { it.first == state.sort } ?: sorts.first()
            // A chip like the filters beside it: the same height, outline and label strength.
            AssistChip(onClick = { choosingSort = true }, modifier = Modifier.tvFocus("library.${state.category}.sort"),
                trailingIcon = { Icon(DulcetIcons.ExpandMore, null, Modifier.size(20.dp)) }) {
                Text(stringResource(R.string.tv_sort_button, current.second), maxLines = 1)
            }
        }
        if (state.category.endsWith("playlists")) androidx.compose.foundation.text.BasicTextField(
            value = state.filter.takeUnless { it == "all" }.orEmpty(), onValueChange = state::filter,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.width(320.dp).tvFocus("library.${state.category}.filter")
                .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            decorationBox = { inner ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(DulcetIcons.Search, null, Modifier.size(20.dp))
                    Box { if (state.filter.isBlank() || state.filter == "all") Text("Filter playlists",
                        color = MaterialTheme.colorScheme.onSurfaceVariant); inner() }
                }
            }, singleLine = true)
        else LibraryCategories.filterKeys(state.category).forEach { key ->
            val label = when (key) {
                "favorites" -> stringResource(R.string.tv_favorites)
                "downloaded" -> stringResource(SharedR.string.library_downloaded)
                else -> stringResource(R.string.tv_filter_all)
            }
            TvFilterChip(label, "library.${state.category}.filter.$key", state.filter.ifBlank { "all" } == key) { state.filterChoice(key) }
        }
    }
    if (choosingSort) {
        TvChoiceDialog(
            tag = "library.${state.category}.sort", surfaceTag = "library.${state.category}.sort.menu",
            title = stringResource(R.string.tv_sort_dialog_title),
            choices = sorts,
            choiceTag = { it.first },
            choiceLabel = { it.second },
            selected = sorts.firstOrNull { it.first == state.sort },
            onChoose = { state.choose(it.first); choosingSort = false },
            onDismiss = { choosingSort = false },
        )
    }
}

/** One filter chip: a check leads it while selected; tv-material keeps that apart from focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvFilterChip(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.tvFocus(tag),
        leadingIcon = if (selected) ({ Icon(DulcetIcons.Check, null, Modifier.size(20.dp)) }) else null,
    ) {
        Text(label, maxLines = 1)
    }
}

/**
 * The device pins, a shelf of cards on the library's home; an empty one hides. Unpin lives in the
 * card's long-Select menu ([TvContextCard]), not on a pill under it.
 */
@Composable
internal fun TvLibraryPins(account: SearchAccount, session: LibrarySession, playback: AndroidPlaybackController?, navigator: TvNavigator) {
    val depth by session.depth.collectAsState()
    if (depth == null || depth?.pinCount == 0) return
    val surface = rememberSurface(session, "pinned") { openPinned() }
    val publication by surface.state.collectAsState()
    val items = publication?.items.orEmpty()
    if (items.isEmpty()) return
    val context = LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(SharedR.string.library_pinned), style = MaterialTheme.typography.headlineSmall)
        LazyRow(Modifier.tvBleed(24.dp, 56.dp), contentPadding = PaddingValues(start = 24.dp, end = 56.dp, top = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(items, key = { _, item -> item.pinKind() + item.rawId }) { index, item ->
                TvContextCard(account, session, playback, item, "library.pinned.item.$index") {
                    when (item) {
                        is AndroidLibraryItem.Album -> navigator.openAlbum(item.rawId)
                        is AndroidLibraryItem.Artist -> navigator.openArtist(item.rawId)
                        is AndroidLibraryItem.Playlist -> navigator.open("playlist:" + item.rawId)
                        is AndroidLibraryItem.Track -> playTracks(plays, account.providerInstanceId, items, item.rawId, item.title.orEmpty()) {
                            context.startActivity(PlaybackIntents.showNowPlaying(context))
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * The Songs or Downloaded page: title, one controls row (filters + Sort), a tonal Shuffle when there
 * is something to play, then the track rows. Empty says why, and offers the way out (the filter's
 * chips stay; a never-filtered Downloads empty points back to the library).
 */
@Composable
internal fun TvDeviceSongs(account: SearchAccount, session: LibrarySession, playback: AndroidPlaybackController?,
                           navigator: TvNavigator, downloaded: Boolean, playingRawId: String?) {
    EnterRoute()
    val key = if (downloaded) "downloaded" else "songs"
    val controls = rememberLibraryCategory(session, key)
    val surface = rememberSurface(session, "$key:${controls.sort}:${controls.filter}") {
        if (downloaded) openDownloaded() else openSongs(controls.sort, controls.filter)
    }
    val publication by surface.state.collectAsState()
    val context = LocalContext.current
    val plays = rememberPlaybackBinding(playback)
    val rawTracks = publication?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Track>()
    val tracks = if (downloaded) projectDownloadedTracks(rawTracks, controls.sort, controls.filter) else rawTracks
    val shuffle = rememberLibraryShuffle(session)
    val connection by session.connection.collectAsState()
    val offline = connection is LibraryConnectionState.Offline || connection is LibraryConnectionState.Saved
    val title = stringResource(if (downloaded) SharedR.string.library_downloads_heading else SharedR.string.search_group_songs)
    val shown = publication?.let { if (downloaded) it.copy(items = tracks) else it }
    val settledEmpty = shown != null && tracks.isEmpty() &&
        shown.itemsState == AndroidLibraryItemsState.Present &&
        shown.freshness !is AndroidLibraryFreshness.Unavailable && shown.freshness != AndroidLibraryFreshness.Loading
    LazyColumn(Modifier.fillMaxSize().testTag("library.$key"), contentPadding = PaddingValues(start = 56.dp, top = 24.dp, end = 56.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.displaySmall)
                if (downloaded && !settledEmpty && publication != null) {
                    TvStatement(
                        context.resources.getQuantityString(SharedR.plurals.library_count_tracks, tracks.size, tracks.size.toString()),
                        "library.$key.summary")
                }
                TvConnectionNotices(session, accountNotices = false, local = downloaded)
                if (settledEmpty) {
                    val filtered = controls.filter.isNotBlank() && controls.filter != "all"
                    if (filtered) {
                        TvLibraryCategoryControls(controls, showSort = false)
                        TvEmptyState(
                            stringResource(if (controls.filter == "favorites") R.string.tv_empty_favorite_songs else R.string.tv_empty_songs),
                            stringResource(R.string.tv_empty_filtered_hint), "library.$key.empty",
                            actionLabel = stringResource(R.string.tv_empty_show_all),
                            actionTag = "library.$key.showAll") { controls.filterChoice("all") }
                    } else if (downloaded) {
                        TvEmptyState(
                            stringResource(R.string.tv_downloads_empty_title),
                            stringResource(R.string.tv_downloads_empty_hint),
                            "library.$key.empty",
                            actionLabel = stringResource(R.string.tv_go_to_library),
                            actionTag = "library.$key.goLibrary", icon = DulcetIcons.Download) { navigator.showRoot(ROUTE_LIBRARY) }
                    } else {
                        TvEmptyState(stringResource(R.string.tv_empty_songs), null, "library.$key.empty")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { TvLibraryCategoryControls(controls, sorts = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)", "artist" to "Artist", "mostPlayed" to "Most played")) }
                        Button(
                            onClick = {
                                if (downloaded || offline) playTracks(plays, account.providerInstanceId, tracks, null, title, true) { context.startActivity(PlaybackIntents.showNowPlaying(context)) }
                                else shuffle.start { playTracks(plays, account.providerInstanceId, it, null, title, true) { context.startActivity(PlaybackIntents.showNowPlaying(context)) } }
                            },
                            enabled = !shuffle.busy && (!downloaded && !offline || tracks.any { it.canBeQueued() }),
                            modifier = Modifier.tvFocus("library.$key.shuffleAll"),
                        ) {
                            Icon(DulcetIcons.Shuffle, null, Modifier.size(20.dp))
                            Text(stringResource(if (offline && !downloaded) SharedR.string.library_shuffle_available else SharedR.string.library_shuffle_all),
                                Modifier.padding(start = 8.dp))
                        }
                    }
                    if (shuffle.failed) TvStatement(stringResource(SharedR.string.library_shuffle_failed), "library.$key.shuffle.failed")
                    publication?.let { TvListStatus(it, "library.$key", session::retry, local = downloaded) }
                }
            }
        }
        itemsIndexed(tracks, key = { _, track -> track.rawId }) { position, track ->
            val pinned = session.isPinned(track)
            TvTrackRow(track, position, track.rawId == playingRawId, onPlay = {
                playTracks(plays, account.providerInstanceId, tracks, track.rawId, title) { context.startActivity(PlaybackIntents.showNowPlaying(context)) }
            }, onUnavailable = {}, tagPrefix = "library.$key.track", byPosition = false, default = position == 0,
                account = account, session = session,
                queue = tvTrackAddition(context, playback, account.providerInstanceId, track, null),
                pin = TvLibraryToggle(pinned) { session.togglePin(track) })
        }
        if (!settledEmpty && publication?.growsAtEnd() == true) item {
            TvAction(stringResource(SharedR.string.library_load_more), "library.$key.loadMore", onClick = surface::loadMore)
        }
    }
}

internal fun tvReleaseGroupTitle(group: String): Int = when (group) {
    "epsSingles" -> SharedR.string.library_release_eps_singles
    "compilations" -> SharedR.string.library_release_compilations
    "appearsOn" -> SharedR.string.library_release_appears_on
    else -> SharedR.string.library_release_albums
}
