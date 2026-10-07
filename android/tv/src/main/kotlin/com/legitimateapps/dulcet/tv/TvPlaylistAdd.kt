package com.legitimateapps.dulcet.tv

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.library.LibrarySubject
import com.legitimateapps.dulcet.library.unavailableLine
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.playlistEditLine
import com.legitimateapps.dulcet.library.rememberSurface

/*
 * Add to Playlist with a remote (spec §18.6), as the phone's sheet does it: the playlists this
 * account can edit, and a new one. Every write goes through the core's playlist editor; an append is
 * not positional, so it names no view. The tvOS shell offers the same from a track's or album's menu.
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
 * The chooser, tagged `playlists.add`: New Playlist… first and focused, then each playlist the
 * account can edit (`playlists.add.item.N`). New Playlist… turns the dialog into a name field, filled
 * with what is being added, and Create. One choice per presentation: the first claims it before the
 * core is called, and a refused edit gives it back with the reason said.
 */
@Composable
internal fun TvAddToPlaylist(session: LibrarySession, addition: TvPlaylistAddition, onDismiss: () -> Unit) {
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
    // A full-screen dialog window holding a centred surface, not the platform's wrap-content one: a
    // focused text field in a wrap-content dialog window re-requests its window's layout on every
    // pass (it reports its focused rectangle, which relays the window out), so the window never
    // settles. Observed under Robolectric, where a wait for idle then never returns.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(Modifier.width(560.dp).testTag("playlists.add")) {
                Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(resources.getString(R.string.tv_playlist_add_title), style = MaterialTheme.typography.headlineSmall)
                    val first = remember { FocusRequester() }
                    LaunchedEffect(naming) { runCatching { first.requestFocus() } }
                    if (naming) {
                        TvField(resources.getString(R.string.tv_playlist_name), name, { name = it }, enabled = !submitting,
                            tag = "playlists.add.name", keyboard = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.focusRequester(first))
                        Button(
                            onClick = {
                                val chosen = name.trim()
                                if (submitting || chosen.isEmpty()) return@Button
                                submitting = true
                                note = null
                                val songs = when (addition) {
                                    is TvPlaylistAddition.Songs -> addition.rawIds
                                    is TvPlaylistAddition.Album -> addition.trackRawIds
                                }
                                session.playlists.create(chosen, songs) { finished(chosen, it) }
                            },
                            enabled = !submitting && name.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().testTag("playlists.add.name.confirm"),
                        ) { Text(resources.getString(R.string.tv_playlist_create)) }
                        Button(onClick = { naming = false }, enabled = !submitting,
                            modifier = Modifier.fillMaxWidth().testTag("playlists.add.name.cancel")) {
                            Text(resources.getString(R.string.tv_cancel))
                        }
                    } else {
                        Button(onClick = { if (!submitting) naming = true }, enabled = !submitting,
                            modifier = Modifier.fillMaxWidth().focusRequester(first).testTag("playlists.add.new")) {
                            Text(resources.getString(R.string.tv_playlist_new))
                        }
                        val current = publication
                        val editable = current?.items.orEmpty().filterIsInstance<AndroidLibraryItem.Playlist>().filter { it.editable }
                        val settled = current != null && current.itemsState == AndroidLibraryItemsState.Present &&
                            current.freshness !is AndroidLibraryFreshness.Unavailable && current.freshness != AndroidLibraryFreshness.Loading
                        when {
                            editable.isNotEmpty() -> LazyColumn(Modifier.heightIn(max = 280.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(editable, key = { _, playlist -> playlist.rawId }) { position, playlist ->
                                    Button(
                                        onClick = {
                                            if (submitting) return@Button
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
                                    ) { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                            settled -> TvChooserLine(resources.getString(R.string.tv_playlist_no_editable), "playlists.add.empty")
                            current?.itemsState == AndroidLibraryItemsState.Unavailable ->
                                TvChooserLine(resources.unavailableLine(current.itemsUnavailableReason
                                    ?: AndroidLibraryUnavailableReason.InternalFailure, LibrarySubject.List), "playlists.add.unavailable")
                            else -> TvChooserLine("…", "playlists.add.loading")
                        }
                        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("playlists.add.cancel")) {
                            Text(resources.getString(R.string.tv_cancel))
                        }
                    }
                    if (submitting) TvChooserLine(resources.getString(R.string.tv_playlist_adding), "playlists.add.submitting")
                    note?.let { TvChooserLine(it, "playlists.add.note") }
                }
            }
        }
    }
}

@Composable
private fun TvChooserLine(text: String, tag: String) {
    Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
