package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons

/*
 * Queue editing with a remote (spec §14.1). A remote has no swipe or drag worth relying on, so each
 * edit is a named choice in a dialog — opened by a held centre key (long-Select) on the row it
 * edits, on an Up Next row or a library track row alike. The dialog is one Material surface (28 dp
 * corners, elevated colour, a strong scrim behind), its choices ListItems with icons, its
 * destructive choices in the error colour and never under the opening focus, and Cancel last. Back
 * and Cancel close it having done nothing; a choice closes it having acted, and focus returns to
 * what opened it. Add to Playlist… closes it and opens the playlist chooser ([TvAddToPlaylist]).
 */

/**
 * One choice in a [TvQueueMenu]: its words, its test tag, and what it does. [icon] leads the row;
 * a [destructive] choice is drawn in the error colour and never takes the dialog's initial focus.
 */
internal class TvQueueChoice(
    val label: String,
    val tag: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val action: () -> Unit,
) {
    /** The long-standing shape: words, tag, action. */
    constructor(label: String, tag: String, action: () -> Unit) : this(label, tag, null, false, action)
}

/**
 * A dialog of [choices] under a header of [title], an optional [supporting] line and [artwork]: the
 * first non-destructive choice focused (Cancel when every choice is destructive), then Cancel,
 * `<tag>.cancel`. [tag] tags the dialog.
 */
@Composable
internal fun TvQueueMenu(tag: String, title: String, choices: List<TvQueueChoice>, cancel: String,
                         supporting: String? = null, artwork: (@Composable () -> Unit)? = null, onDismiss: () -> Unit) {
    TvDialogShell(tag, onDismiss, width = 520) {
        // The sheet is capped to the screen, so a long menu scrolls. A scrolling list that looks like a
        // complete one hides its last rows, so an edge fades and a chevron shows while rows lie beyond it.
        val scroll = rememberScrollState()
        Box(Modifier.heightIn(max = 480.dp)) {
        Column(Modifier.verticalScroll(scroll).padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TvDialogHeader(title, supporting, artwork)
            val first = remember { FocusRequester() }
            val focusIndex = choices.indexOfFirst { !it.destructive }
            FocusWhenWindowFocused(first)
            choices.forEachIndexed { index, choice ->
                if (choice.destructive && choices.getOrNull(index - 1)?.destructive != true) {
                    Spacer(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(MaterialTheme.colorScheme.border))
                }
                ListItem(
                    scale = TvRowScale,
                    selected = false,
                    onClick = { onDismiss(); choice.action() },
                    colors = ListItemDefaults.colors(contentColor = if (choice.destructive)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
                    headlineContent = { Text(choice.label, style = MaterialTheme.typography.titleMedium) },
                    leadingContent = { Icon(choice.icon ?: queueChoiceIcon(choice.tag), null) },
                    modifier = Modifier.fillMaxWidth().testTag(choice.tag)
                        .let { if (index == focusIndex) it.focusRequester(first) else it },
                )
            }
            Spacer(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(MaterialTheme.colorScheme.border))
            ListItem(
                scale = TvRowScale,
                selected = false,
                onClick = onDismiss,
                headlineContent = { Text(cancel) },
                modifier = Modifier.fillMaxWidth().testTag("$tag.cancel")
                    .let { if (focusIndex < 0) it.focusRequester(first) else it },
            )
        }
        // The column's own 24 dp of padding is not a row: a list scrolled until its last row shows has said all it has.
        val padding = with(LocalDensity.current) { 24.dp.roundToPx() }
        if (scroll.value > padding) TvMenuScrollCue(Modifier.align(Alignment.TopCenter).testTag("$tag.moreAbove"), above = true)
        if (scroll.maxValue - scroll.value > padding) TvMenuScrollCue(Modifier.align(Alignment.BottomCenter).testTag("$tag.moreBelow"), above = false)
        }
    }
}

/** An edge of a scrolling menu that fades into the sheet, with a chevron saying more rows lie that way. */
@Composable
private fun TvMenuScrollCue(modifier: Modifier, above: Boolean) {
    val surface = MaterialTheme.colorScheme.surface
    val fade = listOf(surface, surface.copy(alpha = 0.9f), Color.Transparent)
    Box(modifier.fillMaxWidth().height(56.dp)
        .background(Brush.verticalGradient(if (above) fade else fade.reversed())),
        contentAlignment = if (above) Alignment.TopCenter else Alignment.BottomCenter) {
        Icon(DulcetIcons.ExpandMore, null, Modifier.size(32.dp).let { if (above) it.rotate(180f) else it },
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun TvAddToUpNext(addition: TvQueueAddition?, session: LibrarySession? = null, account: com.legitimateapps.dulcet.search.SearchAccount? = null, onDismiss: () -> Unit) {
    // Held here rather than in the addition: the menu closes before the chooser opens.
    var choosing by remember(session) { mutableStateOf<TvPlaylistAddition?>(null) }
    if (session != null) choosing?.let { TvAddToPlaylist(session, it, account) { choosing = null } }
    if (addition == null) return
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val toPlaylist = addition.toPlaylist?.takeIf { session != null }
    TvQueueMenu("queue.add", addition.title, listOfNotNull(
        TvQueueChoice(resources.getString(SharedR.string.queue_play_next), "queue.add.playNext",
            icon = DulcetIcons.PlaylistPlay) { addition.playNext() },
        TvQueueChoice(resources.getString(SharedR.string.queue_add_to_queue), "queue.add.addToQueue",
            icon = DulcetIcons.QueueMusic) { addition.addToQueue() },
        toPlaylist?.let { TvQueueChoice(resources.getString(R.string.tv_playlist_add), "queue.add.playlist",
            icon = DulcetIcons.PlaylistAdd) { choosing = it } },
    ), resources.getString(SharedR.string.queue_cancel), onDismiss = onDismiss)
}

private fun queueChoiceIcon(tag: String): ImageVector = when {
    tag.contains("playNext", ignoreCase = true) -> DulcetIcons.PlaylistPlay
    tag.contains("addToQueue", ignoreCase = true) || tag.endsWith(".queue") -> DulcetIcons.QueueMusic
    tag.contains("playlist", ignoreCase = true) && !tag.contains("delete", ignoreCase = true) -> DulcetIcons.PlaylistAdd
    tag.contains("favourite", ignoreCase = true) || tag.contains("favorite", ignoreCase = true) -> DulcetIcons.Favourite
    tag.contains("download", ignoreCase = true) -> DulcetIcons.Download
    tag.contains("pin", ignoreCase = true) -> DulcetIcons.WishBorder
    tag.contains("refresh", ignoreCase = true) -> DulcetIcons.Refresh
    tag.contains("remove", ignoreCase = true) || tag.contains("delete", ignoreCase = true) || tag.contains("clear", ignoreCase = true) -> DulcetIcons.Delete
    else -> DulcetIcons.Add
}
