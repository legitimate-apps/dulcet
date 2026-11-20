package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
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
 * what opened it. Add to playlist… closes it and opens the playlist chooser ([TvAddToPlaylist]).
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvQueueMenu(tag: String, title: String, choices: List<TvQueueChoice>, cancel: String,
                         supporting: String? = null, artwork: (@Composable () -> Unit)? = null, onDismiss: () -> Unit) {
    // An artwork header has a larger leading slot. Reserve the same space on the icon rows so
    // the title, supporting line and action labels share an ink column.
    val leadingWidth = if (artwork == null) TV_MENU_ICON else TV_ROW_ART + TvSpacing.l - TV_ROW_LEADING_GAP
    val headerInset = if (artwork == null) leadingWidth + TV_ROW_LEADING_GAP else 0.dp
    TvDialogShell(
        tag, onDismiss, width = TvDialogWidth.List,
        header = { TvDialogHeader(title, supporting, artwork, Modifier.tvInkColumn().padding(start = headerInset)) },
    ) {
        val first = remember { FocusRequester() }
        val focusIndex = choices.indexOfFirst { !it.destructive }
        FocusWhenWindowFocused(first)
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        val line = MaterialTheme.typography.titleMedium.lineHeight
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val geometry = with(density) {
                val row = maxOf(TV_MENU_ROW_MIN, line.toDp() + TvSpacing.xl).roundToPx()
                val gap = TvSpacing.xs.roundToPx()
                val available = maxHeight.roundToPx()
                val cue = TvSpacing.xl.roundToPx()
                val total = (choices.size + 1) * (row + gap) - gap
                val overflow = total > available
                val visible = ((available - cue + gap) / (row + gap)).coerceAtLeast(1)
                val viewport = if (overflow) {
                    (visible * (row + gap) - gap).coerceAtMost((available - cue).coerceAtLeast(0))
                } else total
                MenuGeometry(row.toDp(), viewport.toDp(), (row + gap).toFloat(), overflow)
            }
            val bringIntoView = remember(geometry.pitchPx) {
                object : BringIntoViewSpec {
                    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                        when {
                            offset < 0f -> offset
                            // Keep the next row wholly visible too, whenever the viewport holds two.
                            else -> {
                                val lookahead = geometry.pitchPx.takeIf { size + it <= containerSize } ?: 0f
                                (offset + size + lookahead - containerSize).coerceAtLeast(0f)
                            }
                        }
                }
            }
            Column {
                CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
                    Column(Modifier.height(geometry.viewport).testTag("$tag.rows").verticalScroll(scroll)) {
                        choices.forEachIndexed { index, choice ->
                            key(choice.tag) {
                                if (index > 0) TvMenuGap(choice.destructive && !choices[index - 1].destructive)
                                TvMenuRow(choice, geometry.row, leadingWidth,
                                    if (index == focusIndex) Modifier.focusRequester(first) else Modifier) {
                                    onDismiss()
                                    choice.action()
                                }
                            }
                        }
                        if (choices.isNotEmpty()) TvMenuGap(divider = true)
                        TvMenuRow(
                            TvQueueChoice(cancel, "$tag.cancel", DulcetIcons.Close, action = onDismiss),
                            geometry.row, leadingWidth,
                            if (focusIndex < 0) Modifier.focusRequester(first) else Modifier,
                            onClick = onDismiss,
                        )
                    }
                }
                if (geometry.overflow) {
                    Box(Modifier.fillMaxWidth().height(TvSpacing.xl), contentAlignment = Alignment.Center) {
                        if (scroll.canScrollForward) {
                            val surface = MaterialTheme.colorScheme.surface
                            Box(
                                Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, surface))),
                            )
                            Icon(DulcetIcons.ExpandMore, null, Modifier.size(TvSpacing.xl).testTag("$tag.moreBelow"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (scroll.canScrollBackward) {
                // The shell already leaves space between the pinned header and the list. Put the up cue there.
                Box(
                    Modifier.align(Alignment.TopCenter).offset(y = -TvSpacing.l).size(TvSpacing.l)
                        .testTag("$tag.moreAbove"),
                ) {
                    Icon(DulcetIcons.ExpandMore, null, Modifier.matchParentSize().rotate(180f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class MenuGeometry(val row: Dp, val viewport: Dp, val pitchPx: Float, val overflow: Boolean)

@Composable
private fun TvMenuGap(divider: Boolean) {
    Box(Modifier.fillMaxWidth().height(TvSpacing.xs), contentAlignment = Alignment.Center) {
        if (divider) {
            Box(Modifier.fillMaxWidth().height(TV_MENU_DIVIDER).background(MaterialTheme.colorScheme.border))
        }
    }
}

@Composable
private fun TvMenuRow(choice: TvQueueChoice, height: Dp, leadingWidth: Dp, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        scale = TvRowScale,
        selected = false,
        onClick = onClick,
        colors = if (choice.destructive) tvDestructiveRowColors()
            else ListItemDefaults.colors(contentColor = MaterialTheme.colorScheme.onSurface),
        headlineContent = {
            Text(choice.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Box(Modifier.width(leadingWidth), contentAlignment = Alignment.CenterStart) {
                Icon(choice.icon ?: queueChoiceIcon(choice.tag), null, Modifier.size(TV_MENU_ICON))
            }
        },
        modifier = modifier.fillMaxWidth().height(height).testTag(choice.tag),
    )
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
 * [session] and something to add to a playlist, Add to playlist… follows, and opens the playlist
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
    tag.contains("pin", ignoreCase = true) -> DulcetIcons.PushPin
    tag.contains("refresh", ignoreCase = true) -> DulcetIcons.Refresh
    tag.contains("remove", ignoreCase = true) || tag.contains("delete", ignoreCase = true) || tag.contains("clear", ignoreCase = true) -> DulcetIcons.Delete
    else -> DulcetIcons.Add
}
