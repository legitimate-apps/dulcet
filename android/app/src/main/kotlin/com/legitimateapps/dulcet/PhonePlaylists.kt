package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.library.canBeQueued
import com.legitimateapps.dulcet.ui.sortControlSemantics
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidPlaylistEditRecord
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.PlaylistQuestion
import com.legitimateapps.dulcet.library.PlaylistQuestions
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibrarySubject
import com.legitimateapps.dulcet.library.coverageLine
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.offersRetry
import com.legitimateapps.dulcet.library.playlistEditLine
import com.legitimateapps.dulcet.library.playlistOutcomeLine
import com.legitimateapps.dulcet.library.playlistOwnerLine
import com.legitimateapps.dulcet.library.playlistPendingLine
import com.legitimateapps.dulcet.library.playlistQuestionLine
import com.legitimateapps.dulcet.library.playlistView
import com.legitimateapps.dulcet.library.rememberLibraryCategory
import com.legitimateapps.dulcet.library.rememberSurface
import com.legitimateapps.dulcet.library.trackDisplayTitle
import com.legitimateapps.dulcet.library.unavailableLine
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity
import com.legitimateapps.dulcet.ui.RatingStars
import kotlin.math.roundToInt

/*
 * The phone's playlists (spec §18.6): the list, a playlist's page that plays and edits it, and "Add to
 * playlist". Every edit goes through the core's editor ([LibrarySession.playlists]), which shows it in
 * the next publication before any request, queues it, sends it at least once and reads it back. A
 * positional edit names the view it was made on — the entry ids of the publication drawn — and the
 * core refuses it when that is no longer what it publishes. Nothing here decides whether an edit is
 * allowed: a playlist the core does not mark editable offers no edit at all.
 */

/** What "Add to playlist" adds. */
internal sealed interface PlaylistAddition {
    val title: String

    /** The art the sheet shows for it, when the caller has it. */
    val artworkKey: String? get() = null

    /** How many songs the addition carries. */
    val songCount: Int

    /** Songs, by id, in order. */
    data class Songs(val rawIds: List<String>, override val title: String, override val artworkKey: String? = null) : PlaylistAddition {
        override val songCount get() = rawIds.size
    }

    /**
     * An album. An existing playlist takes it through the core's album append, in album order; a new
     * playlist is created with [trackRawIds], the tracks the album screen showed.
     */
    data class Album(val rawId: String, override val title: String, val trackRawIds: List<String>, override val artworkKey: String? = null) : PlaylistAddition {
        override val songCount get() = trackRawIds.size
    }
}

/** The playlists list's sort choices, as the account's stored category names them. */
private val playlistSorts = listOf("title" to "Title (A–Z)", "titleDescending" to "Title (Z–A)")

/** The account's playlists, with "New playlist". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistsList(
    account: SearchAccount,
    session: LibrarySession,
    actions: PhoneActions,
    createOpen: Boolean,
    onCreateOpenChange: (Boolean) -> Unit,
) {
    val controls = rememberLibraryCategory(session, "playlists")
    val surface = rememberSurface(session, "playlists") { openPlaylists() }
    val publication by surface.state.collectAsState()
    val current = publication
    val resources = libraryResources()
    var sorting by rememberSaveable { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val questions by session.playlistQuestions.asking.collectAsState()
    val playlists = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Playlist>()
    val settledEmpty = current != null && current.items.isEmpty() && current.itemsState == AndroidLibraryItemsState.Present &&
        current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading
    PullToRefreshBox(isRefreshing = current?.freshness == AndroidLibraryFreshness.Loading && current.items.isNotEmpty(),
        onRefresh = session::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("library.playlists")) {
            if (settledEmpty) {
                item {
                    EmptyState(
                        icon = DulcetIcons.QueueMusic,
                        title = stringResource(R.string.playlists_empty_title),
                        body = stringResource(R.string.playlists_empty_body),
                        actionLabel = stringResource(R.string.playlist_new),
                        onAction = { onCreateOpenChange(true) },
                        actionTag = "library.playlists.empty.new",
                        modifier = Modifier.testTag("library.playlists.empty"), fillsPage = true,
                    )
                }
            } else {
                item {
                    Column {
                        // Filter and sort stay hidden while there is nothing to act on.
                        if (playlists.isNotEmpty() || controls.filter.isNotBlank()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = controls.filter.takeUnless { it == "all" }.orEmpty(),
                                    onValueChange = controls::filter,
                                    placeholder = { Text(stringResource(SharedR.string.library_filter)) },
                                    leadingIcon = { Icon(DulcetIcons.Search, null) },
                                    singleLine = true,
                                    shape = CircleShape,
                                    modifier = Modifier.weight(1f).testTag("library.playlists.filter"),
                                )
                                Spacer(Modifier.size(8.dp))
                                val order = playlistSorts.firstOrNull { it.first == controls.sort }?.second ?: playlistSorts.first().second
                                AssistChip(
                                    onClick = { sorting = true },
                                    label = { Text(sortChipLabel(order), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingIcon = { Icon(DulcetIcons.ExpandMore, null, Modifier.size(18.dp)) },
                                    // Beside the filter field it is one control row: the same height and shape.
                                    shape = CircleShape,
                                    modifier = Modifier.heightIn(min = 56.dp).sortControlSemantics(order, sorting).testTag("library.playlists.sort"),
                                )
                            }
                        }
                        if (current != null) {
                            val freshness = current.freshness
                            when {
                                freshness is AndroidLibraryFreshness.Unavailable ->
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(resources.unavailableLine(freshness.reason, LibrarySubject.List),
                                            Modifier.weight(1f).padding(vertical = 8.dp).testTag("library.playlists.unavailable"),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (freshness.offersRetry()) {
                                            TextButton(onClick = session::retry, modifier = Modifier.testTag("library.playlists.retry")) {
                                                Text(resources.getString(SharedR.string.library_try_again))
                                            }
                                        }
                                    }
                                freshness == AndroidLibraryFreshness.Loading || current.itemsState == AndroidLibraryItemsState.Loading ->
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("library.playlists.loading"))
                                else -> resources.coverageLine(current)?.let { StatementText(it, "library.playlists.coverage") }
                            }
                        }
                        note?.let { StatementText(it, "library.playlists.note") }
                        // A create in doubt is about a playlist that may not be in this list: every one waiting is asked here.
                        questions.forEach { question ->
                            CreateInDoubt(session.playlistQuestions, question,
                                question.name ?: playlists.firstOrNull { it.rawId == question.localId }?.name,
                                "library.playlists.question.${question.localId}")
                        }
                    }
                }
                itemsIndexed(playlists, key = { _, playlist -> playlist.rawId }) { position, playlist ->
                    PlaylistRow(account, playlist, Modifier.testTag("library.playlists.item.$position")) { actions.openPlaylist(playlist.rawId) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (createOpen) {
        PlaylistNameDialog(R.string.playlist_new, R.string.playlist_create, "", "library.playlists.name",
            requireChange = false, dismiss = { onCreateOpenChange(false) }) { name ->
            onCreateOpenChange(false)
            session.playlists.create(name, emptyList()) { result ->
                note = resources.playlistEditLine(result)
                // Shown at once under its local id; the page follows it to the server's id when it is made.
                if (result.record == AndroidPlaylistEditRecord.Pending) result.localId?.let(actions.openPlaylist)
            }
        }
    }
    if (sorting) {
        PlaylistSortSheet(controls.sort, playlistSorts, "library.playlists", { controls.choose(it) }) { sorting = false }
    }
}

@Composable
private fun PlaylistRow(account: SearchAccount, playlist: AndroidLibraryItem.Playlist, modifier: Modifier, onClick: () -> Unit) {
    val resources = libraryResources()
    val details = listOfNotNull(
        playlist.songCount?.let { pluralStringResource(R.plurals.album_song_count, it, it) },
        // Ownership and pending never repeat each other: a local playlist's line already says it is not on the server.
        resources.playlistOwnerLine(playlist) ?: resources.playlistPendingLine(playlist),
    ).metadataLine()
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = details.takeIf { it.isNotEmpty() }?.let { { MetadataText(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Artwork(account, playlist.artworkKey, playlist.name, 56.dp, entity = ArtworkEntity.Playlist) },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** One playlist: plays and shuffles; editable ones rename, delete, reorder and remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistScreen(
    account: SearchAccount,
    session: LibrarySession,
    rawId: String,
    playingRawId: String?,
    actions: PhoneActions,
) {
    val created by session.createdPlaylists.collectAsState()
    LaunchedEffect(created[rawId]) { created[rawId]?.let { actions.followPlaylist(rawId, it) } }
    val surface = rememberSurface(session, "playlist:$rawId") { openPlaylist(rawId) }
    val publication by surface.state.collectAsState()
    val observation by session.observation.collectAsState()
    val outcomes by session.playlistOutcomes.collectAsState()
    val questions by session.playlistQuestions.asking.collectAsState()
    val awaitingChoice by session.playlistQuestions.awaitingChoice.collectAsState()
    val depth by session.depth.collectAsState()
    val resources = libraryResources()
    var editing by rememberSaveable(rawId) { mutableStateOf(false) }
    val reportEditing = LocalPlaylistEditing.current
    androidx.compose.runtime.DisposableEffect(editing) {
        reportEditing(editing)
        onDispose { reportEditing(false) }
    }
    var renaming by rememberSaveable(rawId) { mutableStateOf(false) }
    var deleting by rememberSaveable(rawId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    val current = publication
    val playlist = current?.header as? AndroidLibraryItem.Playlist
    val editable = playlist?.editable == true
    // The view the person acts on: every entry present, in order. Null while it is not the whole playlist.
    val view = current?.playlistView()
    val list = rememberLazyListState()
    // The status item has no height with nothing to say and a lazy list then rests on index 1, so the page's rest is index 1.
    val scrollBehavior = rememberPositionTintedBar { list.firstVisibleItemIndex > 1 || list.firstVisibleItemScrollOffset > 0 }
    // Read mode has a status item before the hero; editing keeps its own explicit title.
    val heroTitle = remember { DetailHeroTitle() }
    val collapsed = detailHeaderCollapsed(list, headerIndex = 1, hero = heroTitle)
    val songCount = playlist?.songCount ?: current?.takeIf { it.itemsState == AndroidLibraryItemsState.Present }
        ?.items?.size
    val drag = remember(rawId) { PlaylistDragState() }
    fun answered(result: AndroidPlaylistEditResult) {
        note = resources.playlistEditLine(result)
    }
    Column(Modifier.fillMaxSize().testTag("playlist.surface").semantics { this[LibraryObservation] = observation }) {
        if (editing) {
            // Edit mode's own bar: close or Done leaves it; every entry edit is already applied as made.
            TopAppBar(
                title = { Text(stringResource(R.string.playlist_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = { editing = false }, modifier = Modifier.testTag("playlist.edit.close")) {
                        Icon(DulcetClose, stringResource(R.string.playlist_edit_close))
                    }
                },
                actions = {
                    TextButton(onClick = { editing = false }, modifier = Modifier.testTag("playlist.edit")) {
                        Text(stringResource(R.string.playlist_done))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        } else {
            TopAppBar(title = { DetailAppBarTitle(playlist?.name,
                songCount?.let { pluralStringResource(R.plurals.album_song_count, it, it) }, collapsed, "playlist") },
                modifier = Modifier.testTag("playlist.bar").topBarTint(scrollBehavior),
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                expandedHeight = detailAppBarHeight(collapsed, playlist?.name, songCount?.let { pluralStringResource(R.plurals.album_song_count, it, it) }), navigationIcon = {
                IconButton(onClick = actions.back, modifier = Modifier.testTag("playlist.back")) {
                    Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back))
                }
            }, actions = {
                if (editable) {
                    IconButton(onClick = { editing = true }, enabled = view != null,
                        modifier = Modifier.testTag("playlist.edit")) {
                        Icon(DulcetIcons.Edit, stringResource(R.string.playlist_edit))
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("playlist.menu")) {
                            Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            PhoneMenuItem(text = { Text(stringResource(R.string.playlist_rename)) },
                                leadingIcon = { Icon(DulcetIcons.Edit, null) },
                                onClick = { menu = false; renaming = true }, modifier = Modifier.testTag("playlist.rename"))
                            if (playlist != null && !playlist.local) {
                                val pinned = depth?.let { session.isPinned(playlist) } == true
                                PhoneMenuItem(
                                    text = { Text(stringResource(if (pinned) SharedR.string.library_unpin else SharedR.string.library_pin)) },
                                    leadingIcon = { Icon(LibraryRowIcons.PushPin, null) },
                                    onClick = { menu = false; session.togglePin(playlist) },
                                    modifier = Modifier.testTag("playlist.pin"))
                            }
                            HorizontalDivider()
                            PhoneMenuItem(text = { Text(stringResource(R.string.playlist_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(DulcetIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; deleting = true }, modifier = Modifier.testTag("playlist.delete"))
                        }
                    }
                }
            }, scrollBehavior = scrollBehavior)
        }
        ConnectionNotices(session, accountNotices = false)
        if (current == null) return@Column
        val entries = current.items
        val anyPlayable = current.itemsState == AndroidLibraryItemsState.Present && entries.any {
            it is AndroidLibraryItem.Track && it.canBeQueued()
        }
        PullToRefreshBox(isRefreshing = current.freshness == AndroidLibraryFreshness.Loading && entries.isNotEmpty(),
            onRefresh = { if (!editing) session.refresh() },
            modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().testTag("playlist.entries"), state = list) {
                item {
                    Column {
                        (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(resources.unavailableLine(unavailable.reason, LibrarySubject.List),
                                    Modifier.weight(1f).padding(vertical = 8.dp).testTag("playlist.unavailable"),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (unavailable.offersRetry()) {
                                    TextButton(onClick = session::retry, modifier = Modifier.testTag("playlist.retry")) {
                                        Text(resources.getString(SharedR.string.library_try_again))
                                    }
                                }
                            }
                        }
                        val question = questions.firstOrNull { it.localId == rawId && it.kind == PlaylistQuestion.Kind.WhichIsYours }
                        if (question != null) {
                            CreateInDoubt(session.playlistQuestions, question, question.name ?: playlist?.name, "playlist.question")
                        } else if (rawId in awaitingChoice) {
                            // Deferred: the page keeps saying so, and asks again on request.
                            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(resources.getString(SharedR.string.playlist_question_waiting),
                                    Modifier.weight(1f).padding(vertical = 8.dp).testTag("playlist.question.waiting"),
                                    style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { session.playlistQuestions.reask(rawId) },
                                    modifier = Modifier.testTag("playlist.question.choose")) {
                                    Text(resources.getString(SharedR.string.playlist_question_choose))
                                }
                            }
                        }
                        outcomes[rawId]?.let { outcome ->
                            resources.playlistOutcomeLine(outcome)?.let { line ->
                                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(line, Modifier.weight(1f).padding(vertical = 8.dp).testTag("playlist.outcome"),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                    TextButton(onClick = { session.dismissPlaylistOutcome(rawId) }) {
                                        Text(resources.getString(SharedR.string.library_dismiss))
                                    }
                                }
                            }
                        }
                        note?.let { StatementText(it, "playlist.note") }
                    }
                }
                // The hero and its transport are the page's read mode; editing is rows and their order only.
                if (playlist != null && !editing) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val ownerLine = resources.playlistOwnerLine(playlist)
                        val pendingLine = resources.playlistPendingLine(playlist)
                        LibraryDetailHeader(
                            title = playlist.name,
                            titleTag = "playlist.title",
                            heroTitle = heroTitle,
                            modifier = Modifier.bleedKeyline(24.dp),
                            artwork = {
                                Artwork(account, playlist.artworkKey, playlist.name, null,
                                    Modifier.fillMaxWidth(0.53f).aspectRatio(1f), ArtworkEntity.Playlist)
                            },
                            metadata = listOfNotNull(
                                (playlist.songCount ?: entries.size.takeIf { current.itemsState == AndroidLibraryItemsState.Present })
                                    ?.let { pluralStringResource(R.plurals.album_song_count, it, it) },
                                playlist.durationMilliseconds?.takeIf { it > 0 }?.let { longDuration(it) },
                            ).metadataLine(),
                            status = if (ownerLine == null && pendingLine == null) null else {
                                {
                                    // One ownership/pending line, never both: a local playlist's owner line
                                    // already says it is not on the server.
                                    val line = ownerLine ?: pendingLine!!
                                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (pendingLine != null || playlist.local) {
                                            Icon(DulcetCloudOff, null, Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.size(6.dp))
                                        }
                                        Text(line, style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.weight(1f, fill = false).testTag(if (ownerLine != null) "playlist.owner" else "playlist.pending"))
                                    }
                                }
                            },
                        ) {
                            PlayShuffleButtons(
                                onPlay = { actions.playPlaylist(current, 0, false) },
                                onShuffle = { actions.playPlaylist(current, 0, true) },
                                enabled = anyPlayable, tagPrefix = "playlist")
                        }
                    }
                }
                when (current.itemsState) {
                    AndroidLibraryItemsState.Loading -> item { RowPlaceholder(Modifier.testTag("playlist.entries.loading")) }
                    AndroidLibraryItemsState.Unavailable -> if (playlist != null) item {
                        StatementText(resources.unavailableLine(
                            current.itemsUnavailableReason ?: com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List,
                        ), "playlist.entries.unavailable")
                    }
                    AndroidLibraryItemsState.Present -> {
                        if (entries.isEmpty()) item {
                            EmptyState(
                                icon = DulcetIcons.MusicNote,
                                title = stringResource(R.string.playlist_empty_title),
                                body = stringResource(R.string.playlist_empty_body),
                                modifier = Modifier.testTag("playlist.empty"),
                            )
                        }
                        // Keyed by position: a playlist may hold the same song twice, and each is its own entry.
                        itemsIndexed(entries, key = { position, item -> "$position:${item.rawId}" }) { position, item ->
                            val track = item as? AndroidLibraryItem.Track ?: return@itemsIndexed
                            if (editing && view != null) {
                                drag.count = entries.size
                                EditableEntry(account, track, position, entries.size, drag,
                                    onMove = { to -> session.playlists.move(rawId, position, to, view, ::answered) },
                                    onRemove = { session.playlists.remove(rawId, setOf(position), view, ::answered) })
                            } else {
                                PlaylistEntryRow(account, track, track.rawId == playingRawId, Modifier.testTag("playlist.entry.$position"),
                                    onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                                    onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)) },
                                    favouriteTag = "playlist.entry.$position.favourite",
                                    onAddToPlaylist = { actions.addToPlaylist(PlaylistAddition.Songs(listOf(track.rawId), track.title.orEmpty(), track.artworkKey)) },
                                    queue = actions.playlistTrackQueue(track, playlist),
                                    onRate = { session.setRating(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId), it) }) {
                                    note = null
                                    actions.playPlaylist(current, position, false)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    if (renaming && playlist != null) {
        PlaylistNameDialog(R.string.playlist_rename_title, R.string.playlist_save, playlist.name, "playlist.name",
            requireChange = true, dismiss = { renaming = false }) { name ->
            renaming = false
            session.playlists.rename(rawId, name, ::answered)
        }
    }
    if (deleting && playlist != null) {
        PlaylistDeleteDialog(playlist.name, dismiss = { deleting = false }) {
            deleting = false
            session.playlists.delete(rawId) { result ->
                if (result.record == AndroidPlaylistEditRecord.Pending || result.record == AndroidPlaylistEditRecord.CompactedAway) {
                    actions.back()
                } else {
                    answered(result)
                }
            }
        }
    }
}

/**
 * A playlist entry in read mode: artwork-led like every library row, never the album's track number.
 * Its menu is the track menu's playlist shape: queue, Add to playlist, rating (§14.1, §16.20).
 */
@Composable
private fun PlaylistEntryRow(
    account: SearchAccount,
    track: AndroidLibraryItem.Track,
    playing: Boolean,
    modifier: Modifier,
    onUnavailable: () -> Unit,
    onFavourite: (() -> Unit)?,
    favouriteTag: String?,
    onAddToPlaylist: (() -> Unit)?,
    queue: TrackQueue?,
    onRate: ((Int) -> Unit)?,
    onClick: () -> Unit,
) {
    TrackRow(track, 0, playing, modifier, onUnavailable,
        onFavourite = onFavourite, favouriteTag = favouriteTag,
        onAddToPlaylist = onAddToPlaylist, queue = queue, onRate = onRate,
        artwork = { Artwork(account, track.artworkKey, track.title.orEmpty(), 56.dp, entity = ArtworkEntity.Song) }, onClick = onClick)
}

/** The entry's context menu: Play Next / Add to Queue, Add to playlist, and its rating. */
@Composable
private fun PlaylistEntryMenu(tag: String?, onAddToPlaylist: (() -> Unit)?, queue: TrackQueue?, rating: TrackRating?) {
    var open by remember { mutableStateOf(false) }
    var rateOpen by remember { mutableStateOf(false) }
    if (rateOpen && rating != null) PlaylistEntryRatingDialog(tag ?: "track", rating) { rateOpen = false }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag((tag ?: "track") + ".menu")) {
            Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (queue != null) QueueInsertionItems(tag ?: "track", { open = false }, queue.onPlayNext, queue.onAddToQueue)
            if (onAddToPlaylist != null) PhoneMenuItem(
                text = { Text(stringResource(R.string.playlist_add_to)) },
                leadingIcon = { Icon(DulcetIcons.PlaylistAdd, null) },
                onClick = { open = false; onAddToPlaylist() },
                modifier = Modifier.testTag((tag ?: "track") + ".menu.addToPlaylist"),
            )
            if (rating != null) PhoneMenuItem(
                text = { Text(stringResource(SharedR.string.library_rating_menu)) },
                leadingIcon = { Icon(if ((rating.value ?: 0) > 0) DulcetIcons.Star else DulcetIcons.StarBorder, null) },
                onClick = { open = false; rateOpen = true },
                modifier = Modifier.testTag((tag ?: "track") + ".menu.rate"),
            )
        }
    }
}

/** The entry's stars, from its menu; Done only closes. Tagged `<tag>.rating` and `<tag>.rating.done`. */
@Composable
private fun PlaylistEntryRatingDialog(tag: String, rating: TrackRating, close: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(SharedR.string.library_rating)) },
        text = {
            RatingStars(rating.value, rating.onRate, "$tag.rating",
                onColor = MaterialTheme.colorScheme.primary, offColor = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        confirmButton = {
            TextButton(onClick = close, modifier = Modifier.testTag("$tag.rating.done")) {
                Text(stringResource(SharedR.string.library_rating_done))
            }
        },
    )
}

/**
 * The edit mode's drag: the row lifts and the others part as it passes them; one positional move
 * commits on drop, naming the view the drag began on — an edit the core refuses settles back with
 * its answer said on the page.
 */
private class PlaylistDragState {
    var active by mutableIntStateOf(-1)
    var offset by mutableFloatStateOf(0f)
    var rowHeightPx by mutableFloatStateOf(0f)

    /** The rows drawn; a plain var, read only by the gesture itself, never during composition. */
    var count = 0
    val target: Int
        get() = if (active < 0 || rowHeightPx <= 0f || count <= 0) -1
            else (active + (offset / rowHeightPx).roundToInt()).coerceIn(0, count - 1)

    fun begin(position: Int) { active = position; offset = 0f }
    fun clear() { active = -1; offset = 0f }
    fun drop(commit: (Int) -> Unit) {
        val from = active
        val to = target
        clear()
        if (from >= 0 && to >= 0 && to != from) commit(to)
    }
}

/** An entry in edit mode: remove leading, artwork as drawn in read mode, menu and drag handle trailing. */
@Composable
private fun EditableEntry(
    account: SearchAccount,
    track: AndroidLibraryItem.Track,
    position: Int,
    count: Int,
    drag: PlaylistDragState,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val from = drag.active
    val to = drag.target
    val shift = when {
        from < 0 -> 0f
        from == position -> drag.offset
        from < to && position in (from + 1)..to -> -drag.rowHeightPx
        to < from && position in to until from -> drag.rowHeightPx
        else -> 0f
    }
    val moveUp = stringResource(R.string.playlist_move_up)
    val moveDown = stringResource(R.string.playlist_move_down)
    val remove = stringResource(R.string.playlist_remove_entry)
    ListItem(
        headlineContent = {
            WrappedTitleText(track.title ?: stringResource(R.string.playlist_unknown_entry),
                maxLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 2 else 1)
        },
        supportingContent = track.artistName?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { CentredSlot {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp)
                        .testTag("playlist.entry.$position.drag")
                        .pointerInput(position) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { drag.begin(position) },
                                onDragEnd = { drag.drop(onMove) },
                                onDragCancel = { drag.clear() },
                            ) { change, dragAmount ->
                                change.consume()
                                val row = drag.rowHeightPx
                                if (row <= 0f || drag.active < 0) return@detectDragGesturesAfterLongPress
                                drag.offset = (drag.offset + dragAmount.y)
                                    .coerceIn(-(drag.active * row) - row / 2, ((drag.count - 1 - drag.active) * row) + row / 2)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(DulcetDragHandle, stringResource(R.string.playlist_drag_entry),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Artwork(account, track.artworkKey, track.title.orEmpty(), if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 40.dp else 56.dp, entity = ArtworkEntity.Song)
            }
        } },
        trailingContent = { CentredSlot {
            IconButton(onClick = onRemove, modifier = Modifier.testTag("playlist.entry.$position.remove")) {
                Icon(DulcetClose, remove, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .centredSlots()
            .testTag("playlist.entry.$position")
            .onGloballyPositioned { if (it.size.height > 0) drag.rowHeightPx = it.size.height.toFloat() }
            .zIndex(if (from == position) 1f else 0f)
            .graphicsLayer { translationY = shift }
            .semantics {
                customActions = listOfNotNull(
                    if (position > 0) CustomAccessibilityAction(moveUp) { onMove(position - 1); true } else null,
                    if (position < count - 1) CustomAccessibilityAction(moveDown) { onMove(position + 1); true } else null,
                    CustomAccessibilityAction(remove) { onRemove(); true },
                )
            },
    )
}

/** The edit row's menu: the drag handle's accessible steps, and the same remove as the leading icon. */
@Composable
private fun EditableEntryMenu(position: Int, canUp: Boolean, canDown: Boolean, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("playlist.entry.$position.menu")) {
            Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canUp) PhoneMenuItem(
                text = { Text(stringResource(R.string.playlist_move_up)) },
                leadingIcon = { Icon(DulcetIcons.ArrowUp, null) },
                onClick = { open = false; onMove(position - 1) },
                modifier = Modifier.testTag("playlist.entry.$position.up"))
            if (canDown) PhoneMenuItem(
                text = { Text(stringResource(R.string.playlist_move_down)) },
                leadingIcon = { Icon(DulcetIcons.ArrowDown, null) },
                onClick = { open = false; onMove(position + 1) },
                modifier = Modifier.testTag("playlist.entry.$position.down"))
            PhoneMenuItem(
                text = { Text(stringResource(R.string.playlist_remove_entry), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(DulcetRemoveCircleOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onRemove() },
                modifier = Modifier.testTag("playlist.entry.$position.menu.remove"))
        }
    }
}

/**
 * A create whose answer was lost (§18.6): the person decides, never an inference. A lone candidate
 * can be adopted ("Yes, use that one"); several cannot be told apart here, so none is offered and the
 * person creates it anyway or decides later. A create deleted here that may have been made says so.
 * The question stays until the core has recorded the answer; an answer it refuses is said under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateInDoubt(questions: PlaylistQuestions, question: PlaylistQuestion, name: String?, tag: String) {
    val resources = libraryResources()
    val busy by questions.inFlight.collectAsState()
    var note by remember(question) { mutableStateOf<String?>(null) }
    val enabled = question.localId !in busy
    fun answered(result: AndroidPlaylistEditResult) {
        note = resources.playlistEditLine(result)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(resources.playlistQuestionLine(question, name), Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium)
        note?.let { Text(it, Modifier.testTag("$tag.note"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (question.kind) {
                PlaylistQuestion.Kind.WhichIsYours -> {
                    question.loneCandidate?.let { candidate ->
                        TextButton(onClick = { questions.answer(question, candidate, ::answered) }, enabled = enabled,
                            modifier = Modifier.testTag("$tag.keep")) {
                            Text(resources.getString(SharedR.string.playlist_keep_existing))
                        }
                    }
                    TextButton(onClick = { questions.answer(question, null, ::answered) }, enabled = enabled,
                        modifier = Modifier.testTag("$tag.another")) {
                        Text(resources.getString(if (question.loneCandidate != null) SharedR.string.playlist_create_lone
                            else SharedR.string.playlist_create_many))
                    }
                    TextButton(onClick = { questions.defer(question) }, enabled = enabled, modifier = Modifier.testTag("$tag.later")) {
                        Text(resources.getString(SharedR.string.playlist_decide_later))
                    }
                }
                PlaylistQuestion.Kind.MaybeCreated ->
                    TextButton(onClick = { questions.defer(question) }, modifier = Modifier.testTag("$tag.dismiss")) {
                        Text(resources.getString(SharedR.string.library_dismiss))
                    }
            }
        }
    }
}

/**
 * "Add to playlist": the playlists this account can edit, and a new one. An append is not
 * positional, so it needs no view (§18.6); it shows in the playlist at once and is sent in order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(account: SearchAccount, session: LibrarySession, addition: PlaylistAddition, dismiss: () -> Unit) {
    val surface = rememberSurface(session, "playlists.add") { openPlaylists() }
    val publication by surface.state.collectAsState()
    val resources = libraryResources()
    val context = LocalContext.current
    var naming by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val addedTemplate = stringResource(R.string.playlist_added)
    // One choice per presentation. Claim it synchronously, before invoking the asynchronous core:
    // another tap can arrive before Compose redraws the disabled rows. Keep the claim after success
    // until the sheet leaves composition; dismissing it need not remove it in the same frame.
    var submitting by remember(session, addition) { mutableStateOf(false) }
    fun finished(name: String, result: AndroidPlaylistEditResult) {
        val line = resources.playlistEditLine(result)
        if (line == null) {
            Toast.makeText(context, addedTemplate.format(name), Toast.LENGTH_SHORT).show()
            dismiss()
        } else {
            note = line
            submitting = false
        }
    }
    // One modal at a time: the name dialog replaces the sheet, and its Cancel brings the choices back.
    if (!naming) PhoneModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, modifier = Modifier.testTag("playlists.add")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.playlist_add_to), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            MetadataText(listOf(addition.title, pluralStringResource(R.plurals.album_song_count, addition.songCount, addition.songCount)).metadataLine(),
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val current = publication
        val editable = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Playlist>().filter { it.editable }
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                if (current != null && editable.isEmpty() && current.itemsState == AndroidLibraryItemsState.Present &&
                    current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading) {
                    Text(stringResource(R.string.playlist_no_editable),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("playlists.add.empty"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.playlist_add_to_new)) },
                    leadingContent = {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center) {
                            Icon(DulcetIcons.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !submitting) { if (!submitting) naming = true }.testTag("playlists.add.new"),
                )
                if (submitting) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("playlists.add.submitting"), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.playlist_adding))
                    }
                }
                note?.let { StatementText(it, "playlists.add.note") }
                if (current != null) {
                    val freshness = current.freshness
                    when {
                        freshness is AndroidLibraryFreshness.Unavailable ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(resources.unavailableLine(freshness.reason, LibrarySubject.List),
                                    Modifier.weight(1f).padding(vertical = 8.dp).testTag("playlists.add.unavailable"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (freshness.offersRetry()) {
                                    TextButton(onClick = session::retry, modifier = Modifier.testTag("playlists.add.retry")) {
                                        Text(resources.getString(SharedR.string.library_try_again))
                                    }
                                }
                            }
                        current.itemsState == AndroidLibraryItemsState.Loading && editable.isEmpty() ->
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("playlists.add.loading"))
                    }
                }
            }
            itemsIndexed(editable, key = { _, playlist -> playlist.rawId }) { position, playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = playlist.songCount?.let { count ->
                        { Text(pluralStringResource(R.plurals.album_song_count, count, count), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    },
                    leadingContent = { Artwork(account, playlist.artworkKey, playlist.name, 56.dp, entity = ArtworkEntity.Playlist) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !submitting) {
                        if (submitting) return@clickable
                        submitting = true
                        note = null
                        naming = false
                        when (addition) {
                            is PlaylistAddition.Songs -> session.playlists.append(playlist.rawId, addition.rawIds) { finished(playlist.name, it) }
                            is PlaylistAddition.Album -> session.playlists.appendAlbum(playlist.rawId, addition.rawId) { finished(playlist.name, it) }
                        }
                    }.testTag("playlists.add.item.$position"),
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (naming) {
        PlaylistNameDialog(R.string.playlist_new, R.string.playlist_create, addition.title, "playlists.add.name",
            requireChange = false, dismiss = { naming = false }) { name ->
            if (submitting) return@PlaylistNameDialog
            submitting = true
            note = null
            naming = false
            val songs = when (addition) {
                is PlaylistAddition.Songs -> addition.rawIds
                is PlaylistAddition.Album -> addition.trackRawIds
            }
            session.playlists.create(name, songs) { finished(name, it) }
        }
    }
}
