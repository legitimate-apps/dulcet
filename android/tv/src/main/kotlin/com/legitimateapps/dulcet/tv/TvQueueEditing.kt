package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.shared.R as SharedR

/*
 * Queue editing with a remote (spec §14.1). A remote has no swipe, drag or long-press menu worth
 * relying on, so each edit is a named choice in a small dialog opened from a button beside what it
 * edits -- what the tvOS shell's context menu offers, reached by one RIGHT and a centre press. Every choice
 * closes the dialog, and focus returns to the button that opened it.
 */

/** One choice in a [TvQueueMenu]: its words, its test tag, and what it does. */
internal class TvQueueChoice(val label: String, val tag: String, val action: () -> Unit)

/**
 * A dialog of [choices] under [title], the first focused, then Cancel. [tag] tags the dialog, and
 * Cancel is `<tag>.cancel`. Back and Cancel close it having done nothing.
 */
@Composable
internal fun TvQueueMenu(tag: String, title: String, choices: List<TvQueueChoice>, cancel: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(480.dp).testTag(tag)) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                choices.forEachIndexed { index, choice ->
                    Button(
                        onClick = { onDismiss(); choice.action() },
                        modifier = Modifier.fillMaxWidth().testTag(choice.tag)
                            .let { if (index == 0) it.focusRequester(first) else it },
                    ) { Text(choice.label) }
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("$tag.cancel")) { Text(cancel) }
            }
        }
    }
}

/**
 * Something to add to the queue: [title] names it in the dialog, and [playNext] and [addToQueue] add
 * it, each saying so itself when the queue refuses. [toPlaylist], when given, is what Add to
 * Playlist adds (spec §18.6).
 */
internal class TvQueueAddition(
    val title: String,
    val playNext: () -> Unit,
    val addToQueue: () -> Unit,
    val toPlaylist: TvPlaylistAddition? = null,
)

/**
 * Play Next and Add to Queue for [addition], while there is one; tagged `queue.add`. With a
 * [session] and something to add to a playlist, Add to Playlist… follows, and opens the playlist
 * chooser once this dialog has closed.
 */
@Composable
internal fun TvAddToUpNext(addition: TvQueueAddition?, session: LibrarySession? = null, onDismiss: () -> Unit) {
    // Held here rather than in the addition: the menu closes before the chooser opens.
    var choosing by remember(session) { mutableStateOf<TvPlaylistAddition?>(null) }
    if (session != null) choosing?.let { TvAddToPlaylist(session, it) { choosing = null } }
    if (addition == null) return
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val toPlaylist = addition.toPlaylist?.takeIf { session != null }
    TvQueueMenu("queue.add", addition.title, listOfNotNull(
        TvQueueChoice(resources.getString(SharedR.string.queue_play_next), "queue.add.playNext", addition.playNext),
        TvQueueChoice(resources.getString(SharedR.string.queue_add_to_queue), "queue.add.addToQueue", addition.addToQueue),
        toPlaylist?.let { TvQueueChoice(resources.getString(R.string.tv_playlist_add), "queue.add.playlist") { choosing = it } },
    ), resources.getString(SharedR.string.queue_cancel), onDismiss)
}
