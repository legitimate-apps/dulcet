package com.legitimateapps.dulcet.tv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.OutlinedButton
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
 * Add to playlist with a remote (spec §18.6), as the phone's sheet does it: the playlists this
 * account can edit, and a new one. Every write goes through the core's playlist editor; an append is
 * not positional, so it names no view. The tvOS shell offers the same from a track's or album's menu.
 * A create whose answer was lost is asked about here too ([TvPlaylistQuestion]): only the device that
 * made it can settle it, so the TV asks rather than leaving it to the phone.
 */

/** What Add to playlist adds. */
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
    // An album says how much it adds, as on the phone. A song's name says it already.
    val supporting = (addition as? TvPlaylistAddition.Album)?.let {
        "${it.title} · ${resources.getQuantityString(R.plurals.tv_track_count, it.trackRawIds.size, it.trackRawIds.size)}"
    } ?: addition.title
    val chooserInk = Modifier.tvInkColumn().padding(start = TV_ADD_ART + TV_ROW_LEADING_GAP)
    TvDialogShell(
        "playlists.add", onDismiss,
        width = if (naming) TvDialogWidth.Form else TvDialogWidth.List,
        topAnchored = naming,
        header = {
            TvDialogHeader(
                resources.getString(if (naming) R.string.tv_new_playlist else R.string.tv_playlist_add_title_sentence),
                supporting,
                modifier = if (naming) Modifier else chooserInk,
            )
        },
    ) {
        val first = remember { FocusRequester() }
        val firstFocus = rememberFirstFocus()
        val playlists = rememberLazyListState()
        LaunchedEffect(naming, submitting) {
            if (!submitting) {
                // The chooser can return from a refused edit on a row far below New playlist.
                if (!naming && (playlists.firstVisibleItemIndex != 0 || playlists.firstVisibleItemScrollOffset != 0)) {
                    playlists.scrollToItem(0)
                    withFrameNanos { }
                }
                firstFocus.give(first)
            }
        }
        if (naming) {
            val songs = when (addition) {
                is TvPlaylistAddition.Songs -> addition.rawIds
                is TvPlaylistAddition.Album -> addition.trackRawIds
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
                TvField(resources.getString(R.string.tv_playlist_name), name, { name = it }, enabled = !submitting,
                    tag = "playlists.add.name", keyboard = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.focusRequester(first))
                TvPlaylistFormActions(
                    tag = "playlists.add.name",
                    canCreate = !submitting && name.isNotBlank() && songs.isNotEmpty(),
                    submitting = submitting,
                    onCancel = { naming = false },
                    onCreate = {
                        val chosen = name.trim()
                        // An album screen that showed no songs has nothing to create from.
                        if (!submitting && chosen.isNotEmpty() && songs.isNotEmpty()) {
                            submitting = true
                            note = null
                            session.playlists.create(chosen, songs) { finished(chosen, it) }
                        }
                    },
                )
            }
        } else {
            val current = publication
            val editable = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Playlist>().filter { it.editable }
            val settled = current != null && current.itemsState == AndroidLibraryItemsState.Present &&
                current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading
            LazyColumn(
                Modifier.weight(1f, fill = false).fillMaxWidth(),
                state = playlists,
                verticalArrangement = Arrangement.spacedBy(TvSpacing.xs),
            ) {
                item(contentType = "new") {
                    TvDialogRow(
                        label = resources.getString(R.string.tv_new_playlist),
                        tag = "playlists.add.new",
                        modifier = Modifier.fillMaxWidth().focusRequester(first),
                        enabled = !submitting,
                        leading = {
                            Box(
                                Modifier.size(TV_ADD_ART)
                                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(TV_CARD_RADIUS)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    DulcetIcons.Add, null, Modifier.size(TV_ADD_ART_ICON),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        },
                    ) { if (!submitting) naming = true }
                }
                itemsIndexed(
                    editable, key = { _, playlist -> playlist.rawId }, contentType = { _, _ -> "playlist" },
                ) { position, playlist ->
                    TvDialogRow(
                        label = playlist.name,
                        tag = "playlists.add.item.$position",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitting,
                        leading = { TvArtwork(account, playlist.artworkKey, TV_ADD_ART_REQUEST, ArtworkEntity.Playlist) },
                        supporting = playlist.songCount?.let { resources.getString(R.string.tv_playlist_song_count, it) },
                    ) {
                        if (!submitting) {
                            submitting = true
                            note = null
                            when (addition) {
                                is TvPlaylistAddition.Songs ->
                                    session.playlists.append(playlist.rawId, addition.rawIds) { finished(playlist.name, it) }
                                is TvPlaylistAddition.Album ->
                                    session.playlists.appendAlbum(playlist.rawId, addition.rawId) { finished(playlist.name, it) }
                            }
                        }
                    }
                }
                if (editable.isEmpty()) item {
                    when {
                        settled -> TvChooserLine(
                            resources.getString(R.string.tv_playlist_no_editable), "playlists.add.empty",
                            modifier = chooserInk.padding(top = TvSpacing.s),
                        )
                        current?.itemsState == AndroidLibraryItemsState.Unavailable ->
                            TvChooserLine(resources.unavailableLine(current.itemsUnavailableReason
                                ?: AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List), "playlists.add.unavailable",
                            modifier = chooserInk.padding(top = TvSpacing.s))
                        else -> TvLoadingProgress("playlists.add.loading")
                    }
                }
            }
        }
        if (submitting) TvChooserLine(resources.getString(R.string.tv_playlist_adding), "playlists.add.submitting")
        note?.let { TvChooserLine(it, "playlists.add.note") }
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
    TvDialogShell(
        "library.playlists.create", onDismiss = { onDone(null) }, topAnchored = true,
        header = { TvDialogHeader(resources.getString(R.string.tv_new_playlist)) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
            val field = remember { FocusRequester() }
            val firstFocus = rememberFirstFocus()
            LaunchedEffect(submitting) { if (!submitting) firstFocus.give(field) }
            TvField(resources.getString(R.string.tv_playlist_name), name, { name = it }, enabled = !submitting,
                tag = "library.playlists.new.name", keyboard = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.focusRequester(field))
            TvPlaylistFormActions(
                tag = "library.playlists.new",
                canCreate = !submitting && name.isNotBlank(),
                submitting = submitting,
                onCancel = { onDone(null) },
                onCreate = {
                    val chosen = name.trim()
                    if (!submitting && chosen.isNotEmpty()) {
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
                    }
                },
            )
            note?.let { TvChooserLine(it, "library.playlists.new.note") }
        }
    }
}

/** Stacked with Create first, so a single DOWN from the field lands on it when the name is valid. */
@Composable
private fun TvPlaylistFormActions(
    tag: String,
    canCreate: Boolean,
    submitting: Boolean,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val resources = libraryResources()
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onCreate,
        enabled = canCreate,
        colors = ButtonDefaults.colors(containerColor = colors.primary, contentColor = colors.onPrimary),
        // tv-material keeps a disabled button focusable, so explicitly let the remote skip it.
        modifier = Modifier.fillMaxWidth().testTag("$tag.confirm").focusProperties { canFocus = canCreate },
    ) {
        Text(resources.getString(R.string.tv_playlist_create), Modifier.weight(1f), textAlign = TextAlign.Center)
    }
    OutlinedButton(
        onClick = onCancel,
        enabled = !submitting,
        modifier = Modifier.fillMaxWidth().testTag("$tag.cancel"),
    ) {
        Text(resources.getString(R.string.tv_cancel), Modifier.weight(1f), textAlign = TextAlign.Center)
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
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
            Text(resources.playlistQuestionLine(question, name), Modifier.testTag("playlists.question.line"),
                style = MaterialTheme.typography.titleLarge)
            val first = remember { FocusRequester() }
            // On presentation, and after an answer: a disabled tv-material button keeps focus
            // (observed), so this guards a control that does not.
            val firstFocus = rememberFirstFocus()
            LaunchedEffect(answering) { if (!answering) firstFocus.give(first) }
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
    TvConfirmDialog(
        tag = "playlist.delete.dialog",
        title = resources.getString(R.string.tv_playlist_delete_headline),
        body = resources.getString(R.string.tv_playlist_delete_body, name),
        safeLabel = resources.getString(R.string.tv_cancel),
        safeTag = "playlist.delete.cancel",
        destructiveLabel = resources.getString(R.string.tv_playlist_delete_action),
        destructiveTag = "playlist.delete.confirm",
        onSafe = onCancel,
        onDestructive = onDelete,
        bodyTag = "playlist.delete.line",
    )
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
    TvDialogButton(label, tag, onClick, modifier = modifier, enabled = enabled)
}

@Composable
private fun TvChooserLine(text: String, tag: String, modifier: Modifier = Modifier) {
    Text(text, modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
