package com.legitimateapps.dulcet

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidPlaylistEditRecord
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.PlaylistQuestion
import com.legitimateapps.dulcet.library.PlaylistQuestions
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibrarySubject
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.playlistEditLine
import com.legitimateapps.dulcet.library.playlistOutcomeLine
import com.legitimateapps.dulcet.library.playlistOwnerLine
import com.legitimateapps.dulcet.library.playlistPendingLine
import com.legitimateapps.dulcet.library.playlistQuestionLine
import com.legitimateapps.dulcet.library.playlistView
import com.legitimateapps.dulcet.library.rememberSurface
import com.legitimateapps.dulcet.library.unavailableLine
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons

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

    /** Songs, by id, in order. */
    data class Songs(val rawIds: List<String>, override val title: String) : PlaylistAddition

    /**
     * An album. An existing playlist takes it through the core's album append, in album order; a new
     * playlist is created with [trackRawIds], the tracks the album screen showed.
     */
    data class Album(val rawId: String, override val title: String, val trackRawIds: List<String>) : PlaylistAddition
}

/** The account's playlists, with "New playlist". */
@Composable
internal fun PlaylistsList(account: SearchAccount, session: LibrarySession, actions: PhoneActions) {
    val surface = rememberSurface(session, "playlists") { openPlaylists() }
    val publication by surface.state.collectAsState()
    val current = publication
    val resources = libraryResources()
    var naming by rememberSaveable { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val questions by session.playlistQuestions.asking.collectAsState()
    val playlists = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Playlist>()
    LazyColumn(Modifier.fillMaxSize().testTag("library.playlists")) {
        item {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilledTonalButton(onClick = { naming = true }, modifier = Modifier.testTag("library.playlists.new")) {
                        Icon(DulcetIcons.Add, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.playlist_new))
                    }
                }
                if (naming) NameEditor(R.string.playlist_new, R.string.playlist_create, "", "library.playlists.name", { naming = false }) { name ->
                    naming = false
                    session.playlists.create(name, emptyList()) { result ->
                        note = resources.playlistEditLine(result)
                        // Shown at once under its local id; the page follows it to the server's id when it is made.
                        if (result.record == AndroidPlaylistEditRecord.Pending) result.localId?.let(actions.openPlaylist)
                    }
                }
                if (current != null) {
                    if (current.items.isEmpty() && current.itemsState == AndroidLibraryItemsState.Present &&
                        current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading) {
                        FreshnessLine(current.freshness, "library.playlists", session::retry)
                        StatementText(resources.getString(SharedR.string.playlists_empty), "library.playlists.empty")
                    } else {
                        ListStatus(current, "library.playlists", session::retry)
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
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PlaylistRow(account: SearchAccount, playlist: AndroidLibraryItem.Playlist, modifier: Modifier, onClick: () -> Unit) {
    val resources = libraryResources()
    val details = listOfNotNull(
        playlist.songCount?.let { pluralStringResource(R.plurals.album_song_count, it, it) },
        resources.playlistOwnerLine(playlist),
        resources.playlistPendingLine(playlist),
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = details.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Artwork(account, playlist.artworkKey, playlist.name, 48.dp) },
        trailingContent = { Icon(DulcetIcons.ChevronRight, null) },
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
    val resources = libraryResources()
    var editing by rememberSaveable(rawId) { mutableStateOf(false) }
    var renaming by rememberSaveable(rawId) { mutableStateOf(false) }
    var deleting by rememberSaveable(rawId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var note by remember(rawId) { mutableStateOf<String?>(null) }
    val current = publication
    val playlist = current?.header as? AndroidLibraryItem.Playlist
    val editable = playlist?.editable == true
    // The view the person acts on: every entry present, in order. Null while it is not the whole playlist.
    val view = current?.playlistView()
    fun answered(result: AndroidPlaylistEditResult) {
        note = resources.playlistEditLine(result)
    }
    Column(Modifier.fillMaxSize().testTag("playlist.surface").semantics { this[LibraryObservation] = observation }) {
        TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = actions.back, modifier = Modifier.testTag("playlist.back")) {
                Icon(DulcetIcons.ArrowBack, stringResource(R.string.action_back))
            }
        }, actions = {
            IconButton(onClick = session::refresh, modifier = Modifier.testTag("playlist.refresh")) {
                Icon(DulcetIcons.Refresh, stringResource(R.string.library_refresh))
            }
            if (editable) {
                IconButton(onClick = { editing = !editing }, enabled = view != null || editing,
                    modifier = Modifier.testTag("playlist.edit")) {
                    Icon(if (editing) DulcetIcons.Check else DulcetIcons.Edit,
                        stringResource(if (editing) R.string.playlist_done else R.string.playlist_edit))
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("playlist.menu")) {
                        Icon(DulcetIcons.MoreVert, stringResource(R.string.playlist_track_menu))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_rename)) },
                            leadingIcon = { Icon(DulcetIcons.Edit, null) },
                            onClick = { menu = false; renaming = true }, modifier = Modifier.testTag("playlist.rename"))
                        DropdownMenuItem(text = { Text(stringResource(R.string.playlist_delete)) },
                            leadingIcon = { Icon(DulcetIcons.Delete, null) },
                            onClick = { menu = false; deleting = true }, modifier = Modifier.testTag("playlist.delete"))
                    }
                }
            }
        })
        ConnectionNotices(session, accountNotices = false)
        if (current == null) return@Column
        val entries = current.items
        val anyPlayable = current.itemsState == AndroidLibraryItemsState.Present && entries.any {
            it is AndroidLibraryItem.Track && !it.metadataMissing && it.playability != AndroidLibraryPlayability.UnavailableOffline
        }
        LazyColumn(Modifier.fillMaxSize().testTag("playlist.entries")) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FreshnessLine(current.freshness, "playlist", session::retry)
                    (current.freshness as? AndroidLibraryFreshness.Unavailable)?.let { unavailable ->
                        StatementText(resources.unavailableLine(unavailable.reason, LibrarySubject.List), "playlist.unavailable")
                    }
                    val question = questions.firstOrNull { it.localId == rawId && it.kind == PlaylistQuestion.Kind.WhichIsYours }
                    if (question != null) {
                        CreateInDoubt(session.playlistQuestions, question, question.name ?: playlist?.name, "playlist.question")
                    } else if (rawId in awaitingChoice) {
                        // Deferred: the page keeps saying so, and asks again on request.
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line, Modifier.weight(1f).padding(vertical = 8.dp).testTag("playlist.outcome"),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { session.dismissPlaylistOutcome(rawId) }) {
                                    Text(resources.getString(SharedR.string.library_dismiss))
                                }
                            }
                        }
                    }
                    note?.let { StatementText(it, "playlist.note") }
                    if (playlist != null) {
                        Artwork(account, playlist.artworkKey, playlist.name, null,
                            Modifier.fillMaxWidth(0.6f).aspectRatio(1f).shadow(16.dp, RoundedCornerShape(16.dp)))
                        Spacer(Modifier.height(20.dp))
                        if (renaming) {
                            NameEditor(R.string.playlist_rename_title, R.string.playlist_save, playlist.name, "playlist.name",
                                { renaming = false }) { name ->
                                renaming = false
                                session.playlists.rename(rawId, name, ::answered)
                            }
                        } else {
                            Text(playlist.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center, modifier = Modifier.testTag("playlist.title"))
                        }
                        resources.playlistOwnerLine(playlist)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("playlist.owner"))
                        }
                        resources.playlistPendingLine(playlist)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("playlist.pending"))
                        }
                        val songs = playlist.songCount ?: entries.size.takeIf { current.itemsState == AndroidLibraryItemsState.Present }
                        Text(listOfNotNull(songs?.let { pluralStringResource(R.plurals.album_song_count, it, it) },
                            playlist.durationMilliseconds?.takeIf { it > 0 }?.let { longDuration(it) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        PlayShuffleButtons(
                            onPlay = { actions.playPlaylist(current, 0, false) },
                            onShuffle = { actions.playPlaylist(current, 0, true) },
                            enabled = anyPlayable, tagPrefix = "playlist")
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            when (current.itemsState) {
                AndroidLibraryItemsState.Loading -> item { RowPlaceholder(Modifier.testTag("playlist.entries.loading")) }
                AndroidLibraryItemsState.Unavailable -> if (playlist != null) item {
                    StatementText(resources.unavailableLine(
                        current.itemsUnavailableReason ?: AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List,
                    ), "playlist.entries.unavailable")
                }
                AndroidLibraryItemsState.Present -> {
                    if (entries.isEmpty()) item {
                        StatementText(resources.getString(SharedR.string.library_empty_list), "playlist.empty")
                    }
                    // Keyed by position: a playlist may hold the same song twice, and each is its own entry.
                    itemsIndexed(entries, key = { position, item -> "$position:${item.rawId}" }) { position, item ->
                        val track = item as? AndroidLibraryItem.Track ?: return@itemsIndexed
                        if (editing && view != null) {
                            EditableEntry(track, position, entries.size,
                                onMove = { to -> session.playlists.move(rawId, position, to, view, ::answered) },
                                onRemove = { session.playlists.remove(rawId, setOf(position), view, ::answered) })
                        } else {
                            TrackRow(track, position + 1, track.rawId == playingRawId, Modifier.testTag("playlist.entry.$position"),
                                onUnavailable = { note = resources.getString(SharedR.string.library_plays_on_reconnect) },
                                onFavourite = { session.toggleFavourite(AndroidLibraryEntity(AndroidLibraryEntityKind.Track, track.rawId)) },
                                favouriteTag = "playlist.entry.$position.favourite",
                                onAddToPlaylist = { actions.addToPlaylist(PlaylistAddition.Songs(listOf(track.rawId), track.title.orEmpty())) }) {
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
    if (deleting && playlist != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            text = { Text(stringResource(R.string.playlist_delete_confirm, playlist.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    session.playlists.delete(rawId) { result ->
                        if (result.record == AndroidPlaylistEditRecord.Pending || result.record == AndroidPlaylistEditRecord.CompactedAway) {
                            actions.back()
                        } else {
                            answered(result)
                        }
                    }
                }, modifier = Modifier.testTag("playlist.delete.confirm")) { Text(stringResource(R.string.playlist_delete_action)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.playlist_cancel)) } },
        )
    }
}

/** An entry in edit mode: move up, move down, remove. Positions are into the view drawn. */
@Composable
private fun EditableEntry(track: AndroidLibraryItem.Track, position: Int, count: Int, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(track.title ?: stringResource(R.string.playlist_unknown_entry), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = track.artistName?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row {
                IconButton(onClick = { onMove(position - 1) }, enabled = position > 0,
                    modifier = Modifier.testTag("playlist.entry.$position.up")) {
                    Icon(DulcetIcons.ArrowUp, stringResource(R.string.playlist_move_up))
                }
                IconButton(onClick = { onMove(position + 1) }, enabled = position < count - 1,
                    modifier = Modifier.testTag("playlist.entry.$position.down")) {
                    Icon(DulcetIcons.ArrowDown, stringResource(R.string.playlist_move_down))
                }
                IconButton(onClick = onRemove, modifier = Modifier.testTag("playlist.entry.$position.remove")) {
                    Icon(DulcetIcons.Remove, stringResource(R.string.playlist_remove_entry))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("playlist.entry.$position"),
    )
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

@Composable
private fun NameEditor(title: Int, confirm: Int, initial: String, tag: String, dismiss: () -> Unit, done: (String) -> Unit) {
    // In place rather than in a dialog: the name is asked for where it will appear.
    var name by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, placeholder = { Text(stringResource(R.string.playlist_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = dismiss, modifier = Modifier.testTag("$tag.cancel")) { Text(stringResource(R.string.playlist_cancel)) }
            TextButton(onClick = { done(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("$tag.confirm")) {
                Text(stringResource(confirm))
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
    fun finished(name: String, result: AndroidPlaylistEditResult) {
        val line = resources.playlistEditLine(result)
        if (line == null) {
            Toast.makeText(context, addedTemplate.format(name), Toast.LENGTH_SHORT).show()
            dismiss()
        } else {
            note = line
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, modifier = Modifier.testTag("playlists.add")) {
        Text(stringResource(R.string.playlist_add_to), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val current = publication
        val editable = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Playlist>().filter { it.editable }
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.playlist_add_to_new)) },
                    leadingContent = { Icon(DulcetIcons.Add, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { naming = true }.testTag("playlists.add.new"),
                )
                if (naming) NameEditor(R.string.playlist_new, R.string.playlist_create, addition.title, "playlists.add.name",
                    { naming = false }) { name ->
                    naming = false
                    val songs = when (addition) {
                        is PlaylistAddition.Songs -> addition.rawIds
                        is PlaylistAddition.Album -> addition.trackRawIds
                    }
                    session.playlists.create(name, songs) { finished(name, it) }
                }
                note?.let { StatementText(it, "playlists.add.note") }
                if (current != null) {
                    if (editable.isEmpty() && current.itemsState == AndroidLibraryItemsState.Present &&
                        current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading) {
                        StatementText(stringResource(R.string.playlist_no_editable), "playlists.add.empty")
                    } else {
                        ListStatus(current, "playlists.add", session::retry)
                    }
                }
            }
            itemsIndexed(editable, key = { _, playlist -> playlist.rawId }) { position, playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Artwork(account, playlist.artworkKey, playlist.name, 40.dp) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
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
}
