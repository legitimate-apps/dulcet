package com.legitimateapps.dulcet.tv

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.library.LibrarySubject
import com.legitimateapps.dulcet.library.unavailableLine
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.PlaylistQuestion
import com.legitimateapps.dulcet.library.PlaylistQuestions
import com.legitimateapps.dulcet.library.playlistOutcomeLine
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.playlistEditLine
import com.legitimateapps.dulcet.library.playlistQuestionLine
import com.legitimateapps.dulcet.library.rememberSurface
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.ArtworkEntity

/*
 * Add to Playlist with a remote (spec §18.6), as the phone's sheet does it: the playlists this
 * account can edit, and a new one. Every write goes through the core's playlist editor; an append is
 * not positional, so it names no view. The tvOS shell offers the same from a track's or album's menu.
 * A create whose answer was lost is asked about here too ([TvPlaylistQuestion]): only the device that
 * made it can settle it, so the TV asks rather than leaving it to the phone.
 */

/** What Add to Playlist adds. */
internal sealed interface TvPlaylistAddition {
    val title: String

    /** Songs, by id, in order. */
    data class Songs(val rawIds: List<String>, override val title: String) : TvPlaylistAddition

    /**
     * An album. An existing playlist takes it through the core's album append, in album order; a new
     * playlist is created with [trackRawIds], the tracks the album screen showed.
     */
    data class Album(val rawId: String, override val title: String, val trackRawIds: List<String>) : TvPlaylistAddition
}

/**
 * The chooser, tagged `playlists.add`: New playlist first and focused, then each playlist the
 * account can edit (`playlists.add.item.N`) with its artwork and song count. New playlist turns the
 * dialog into the top-anchored name entry, titled for what it makes and filled with what is being
 * added. One choice per presentation: the first claims it before the core is called, and a refused
 * edit gives it back with the reason said and focus on the dialog's first control (the controls it
 * had were disabled while the edit was out). Back is Cancel.
 */
@Composable
internal fun TvAddToPlaylist(session: LibrarySession, addition: TvPlaylistAddition, account: com.legitimateapps.dulcet.search.SearchAccount? = null, onDismiss: () -> Unit) {
    val surface = rememberSurface(session, "playlists.add") { openPlaylists() }
    val publication by surface.state.collectAsState()
    val resources = libraryResources()
    val context = LocalContext.current
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(addition.title) }
    var note by remember { mutableStateOf<String?>(null) }
    // Claimed synchronously, before the asynchronous core: a second centre press can arrive before
    // Compose redraws the disabled buttons. Kept after success until the dialog leaves composition.
    var submitting by remember(session, addition) { mutableStateOf(false) }
    fun finished(playlist: String, result: AndroidPlaylistEditResult) {
        val line = resources.playlistEditLine(result)
        if (line == null) {
            Toast.makeText(context, resources.getString(R.string.tv_playlist_added, playlist), Toast.LENGTH_SHORT).show()
            onDismiss()
        } else {
            note = line
            submitting = false
        }
    }
    TvDialogShell("playlists.add", onDismiss, topAnchored = naming, width = 640) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The shared dialog header: what this does, then what it adds.
            TvDialogHeader(resources.getString(if (naming) R.string.tv_new_playlist else R.string.tv_playlist_add_title_sentence),
                addition.title)
            val first = remember { FocusRequester() }
            val window = LocalWindowInfo.current
            LaunchedEffect(naming, submitting) { if (!submitting) { runCatching { first.requestFocus() }; if (window.awaitWindowFocus()) runCatching { first.requestFocus() } } }
            val songs = when (addition) {
                is TvPlaylistAddition.Songs -> addition.rawIds
                is TvPlaylistAddition.Album -> addition.trackRawIds
            }
            if (naming) {
                TvField(resources.getString(R.string.tv_playlist_name), name, { name = it }, enabled = !submitting,
                    tag = "playlists.add.name", keyboard = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.focusRequester(first))
                // Stacked, Create first: one DOWN from the field lands on it.
                Button(
                    onClick = {
                        val chosen = name.trim()
                        // An album screen that showed no tracks has nothing to create from.
                        if (submitting || chosen.isEmpty() || songs.isEmpty()) return@Button
                        submitting = true
                        note = null
                        session.playlists.create(chosen, songs) { finished(chosen, it) }
                    },
                    enabled = !submitting && name.isNotBlank() && songs.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("playlists.add.name.confirm"),
                ) { Text(resources.getString(R.string.tv_playlist_create)) }
                Button(onClick = { naming = false }, enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().testTag("playlists.add.name.cancel")) {
                    Text(resources.getString(R.string.tv_cancel))
                }
            } else {
                ListItem(
                    scale = TvRowScale,
                    selected = false,
                    onClick = { if (!submitting) naming = true },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("playlists.add.new"),
                    leadingContent = {
                        Icon(DulcetIcons.Add, null, Modifier.size(64.dp))
                    },
                    headlineContent = { Text(resources.getString(R.string.tv_new_playlist)) },
                )
                val current = publication
                val editable = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Playlist>().filter { it.editable }
                val settled = current != null && current.itemsState == AndroidLibraryItemsState.Present &&
                    current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading
                when {
                    editable.isNotEmpty() -> LazyColumn(Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(editable, key = { _, playlist -> playlist.rawId }) { position, playlist ->
                            ListItem(
                                scale = TvRowScale,
                                selected = false,
                                onClick = {
                                    if (submitting) return@ListItem
                                    submitting = true
                                    note = null
                                    when (addition) {
                                        is TvPlaylistAddition.Songs ->
                                            session.playlists.append(playlist.rawId, addition.rawIds) { finished(playlist.name, it) }
                                        is TvPlaylistAddition.Album ->
                                            session.playlists.appendAlbum(playlist.rawId, addition.rawId) { finished(playlist.name, it) }
                                    }
                                },
                                enabled = !submitting,
                                modifier = Modifier.fillMaxWidth().testTag("playlists.add.item.$position"),
                                leadingContent = {
                                    TvArtwork(account, playlist.artworkKey, 64, ArtworkEntity.Playlist)
                                },
                                supportingContent = playlist.songCount?.let { count ->
                                    { Text(resources.getString(R.string.tv_playlist_song_count, count), style = MaterialTheme.typography.bodyMedium) }
                                },
                                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    settled -> TvChooserLine(resources.getString(R.string.tv_playlist_no_editable), "playlists.add.empty")
                    current?.itemsState == AndroidLibraryItemsState.Unavailable ->
                        TvChooserLine(resources.unavailableLine(current.itemsUnavailableReason
                            ?: AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List), "playlists.add.unavailable")
                    else -> TvLoadingProgress("playlists.add.loading")
                }
            }
            if (submitting) TvChooserLine(resources.getString(R.string.tv_playlist_adding), "playlists.add.submitting")
            note?.let { TvChooserLine(it, "playlists.add.note") }
        }
    }
}

/**
 * The standalone New playlist dialog (the playlists grid's first card opens it): top-anchored so the
 * TV IME never covers the field, Create disabled while the name is blank, Back or Cancel closes. A
 * created playlist opens its own page; a create the core refuses says why and stays.
 */
@Composable
internal fun TvPlaylistCreateDialog(session: LibrarySession, onDone: (localId: String?) -> Unit) {
    val resources = libraryResources()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    TvDialogShell("library.playlists.create", onDismiss = { onDone(null) }, topAnchored = true, width = 640) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvDialogHeader(resources.getString(R.string.tv_new_playlist))
            val field = remember { FocusRequester() }
            val window = LocalWindowInfo.current
            LaunchedEffect(submitting) { if (!submitting) { runCatching { field.requestFocus() }; if (window.awaitWindowFocus()) runCatching { field.requestFocus() } } }
            TvField(resources.getString(R.string.tv_playlist_name), name, { name = it }, enabled = !submitting,
                tag = "library.playlists.new.name", keyboard = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.focusRequester(field))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        val chosen = name.trim()
                        if (submitting || chosen.isEmpty()) return@Button
                        submitting = true
                        note = null
                        session.playlists.create(chosen, emptyList()) { result ->
                            val line = resources.playlistEditLine(result)
                            if (line == null) {
                                Toast.makeText(context, resources.getString(R.string.tv_playlist_added, chosen), Toast.LENGTH_SHORT).show()
                                onDone(result.localId)
                            } else {
                                note = line
                                submitting = false
                            }
                        }
                    },
                    enabled = !submitting && name.isNotBlank(),
                    modifier = Modifier.testTag("library.playlists.new.confirm"),
                ) { Text(resources.getString(R.string.tv_playlist_create)) }
                Button(onClick = { onDone(null) }, enabled = !submitting,
                    modifier = Modifier.testTag("library.playlists.new.cancel")) {
                    Text(resources.getString(R.string.tv_cancel))
                }
            }
            note?.let { TvChooserLine(it, "library.playlists.new.note") }
        }
    }
}

/**
 * A create in doubt, asked as the tvOS shell asks it: a dialog over whatever screen is showing, the
 * oldest waiting question first, tagged `playlists.question`. Its controls are the phone's — Yes, use
 * that one (`.keep`, only for a lone candidate), Not mine / None of these — create it (`.another`) and
 * Decide later (`.later`); a create deleted here that may have been made gets Dismiss (`.dismiss`).
 * Back is Decide later or Dismiss. An answer the core refuses is said in the dialog (`.note`) and the
 * question stays; while one is out its controls are disabled and Back does nothing.
 */
@Composable
internal fun TvPlaylistQuestion(session: LibrarySession) {
    TvPlaylistQuestion(session.playlistQuestions) { question ->
        // A question rebuilt after a relaunch has no name: the playlist shown under its local id names it.
        question.name ?: run {
            val surface = rememberSurface(session, "playlists.question") { openPlaylists() }
            val publication by surface.state.collectAsState()
            publication?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Playlist>()
                .firstOrNull { it.rawId == question.localId }?.name
        }
    }
}

/** [TvPlaylistQuestion] over [questions], naming each create by [nameOf]. */
@Composable
internal fun TvPlaylistQuestion(questions: PlaylistQuestions, nameOf: @Composable (PlaylistQuestion) -> String? = { it.name }) {
    val asking by questions.asking.collectAsState()
    val question = asking.firstOrNull() ?: return
    key(question.localId, question.kind) { TvPlaylistQuestionDialog(questions, question, nameOf(question)) }
}

@Composable
private fun TvPlaylistQuestionDialog(questions: PlaylistQuestions, question: PlaylistQuestion, name: String?) {
    val resources = libraryResources()
    val busy by questions.inFlight.collectAsState()
    val answering = question.localId in busy
    // A refusal is about this question: a replacement (other candidates) starts without it.
    var note by remember(question) { mutableStateOf<String?>(null) }
    // Read live, not from the collected state a frame behind: Back right after an answer must not defer it.
    TvDialogShell("playlists.question",
        onDismiss = { if (question.localId !in questions.inFlight.value) questions.defer(question) }) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(resources.playlistQuestionLine(question, name), Modifier.testTag("playlists.question.line"),
                style = MaterialTheme.typography.titleLarge)
            val first = remember { FocusRequester() }
            // On presentation, and after an answer: a disabled tv-material button keeps focus
            // (observed), so this guards a control that does not.
            val window = LocalWindowInfo.current
            LaunchedEffect(answering) { if (!answering) { runCatching { first.requestFocus() }; if (window.awaitWindowFocus()) runCatching { first.requestFocus() } } }
            when (question.kind) {
                PlaylistQuestion.Kind.WhichIsYours -> {
                    val lone = question.loneCandidate
                    if (lone != null) TvQuestionButton(resources.getString(SharedR.string.playlist_keep_existing),
                        "playlists.question.keep", !answering, Modifier.focusRequester(first)) {
                        questions.answer(question, lone) { note = resources.playlistEditLine(it) }
                    }
                    TvQuestionButton(resources.getString(if (lone != null) SharedR.string.playlist_create_lone
                        else SharedR.string.playlist_create_many), "playlists.question.another", !answering,
                        if (lone == null) Modifier.focusRequester(first) else Modifier) {
                        questions.answer(question, null) { note = resources.playlistEditLine(it) }
                    }
                    TvQuestionButton(resources.getString(SharedR.string.playlist_decide_later), "playlists.question.later",
                        !answering) { questions.defer(question) }
                }
                PlaylistQuestion.Kind.MaybeCreated ->
                    TvQuestionButton(resources.getString(SharedR.string.library_dismiss), "playlists.question.dismiss",
                        !answering, Modifier.focusRequester(first)) { questions.defer(question) }
            }
            note?.let { TvChooserLine(it, "playlists.question.note") }
        }
    }
}

/**
 * Asks before a playlist is deleted from the server: Cancel, where focus lands, beside Delete in the
 * error color — a single DOWN from Cancel cannot reach it. Back is Cancel. Nothing is recorded until
 * Delete is pressed.
 */
@Composable
internal fun TvPlaylistDeleteDialog(name: String, onCancel: () -> Unit, onDelete: () -> Unit) {
    val resources = libraryResources()
    TvDialogShell("playlist.delete.dialog", onCancel) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvDialogHeader(resources.getString(R.string.tv_playlist_delete_headline))
            Text(resources.getString(R.string.tv_playlist_delete_body, name), Modifier.testTag("playlist.delete.line"),
                style = MaterialTheme.typography.bodyLarge)
            val cancel = remember { FocusRequester() }
            FocusWhenWindowFocused(cancel)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvQuestionButton(resources.getString(R.string.tv_cancel), "playlist.delete.cancel", true,
                    Modifier.focusRequester(cancel), onCancel)
                Button(
                    onClick = onDelete,
                    modifier = Modifier.testTag("playlist.delete.confirm"),
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.error,
                        focusedContainerColor = MaterialTheme.colorScheme.error,
                        focusedContentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(resources.getString(R.string.tv_playlist_delete_action)) }
            }
        }
    }
}

/**
 * What a playlist's page says about its own edits: a create waiting for the person's answer, with
 * Choose… to ask again (`playlist.question.waiting`, `.choose`; not while its dialog is showing), and
 * a change that did not land, with Dismiss (`playlist.outcome`, `.dismiss`).
 */
@Composable
internal fun TvPlaylistNotices(questions: PlaylistQuestions, rawId: String, outcome: AndroidPlaylistOutcome?, dismissOutcome: () -> Unit) {
    val resources = libraryResources()
    val asking by questions.asking.collectAsState()
    val awaitingChoice by questions.awaitingChoice.collectAsState()
    if (rawId in awaitingChoice && asking.none { it.localId == rawId }) {
        TvStatement(resources.getString(SharedR.string.playlist_question_waiting), "playlist.question.waiting")
        TvAction(resources.getString(SharedR.string.playlist_question_choose), "playlist.question.choose") {
            questions.reask(rawId)
        }
    }
    resources.playlistOutcomeLine(outcome)?.let { line ->
        Text(line, Modifier.testTag("playlist.outcome"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        TvAction(resources.getString(SharedR.string.library_dismiss), "playlist.outcome.dismiss", onClick = dismissOutcome)
    }
}

@Composable
private fun TvQuestionButton(label: String, tag: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag)) { Text(label) }
}

@Composable
private fun TvChooserLine(text: String, tag: String) {
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
