package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.queueEditRefused
import com.legitimateapps.dulcet.ui.ArtworkEntity

/**
 * Up Next as a destination of its own (A30), opened from the transport's Queue button and filling
 * the player's control column: the playing track as a summary at the top — an equalizer glyph, not
 * a fill — then the entries that follow it, one focusable ListItem each with artwork, title, artist
 * and duration. A row's centre key plays it; a held centre key (long-Select) opens its move/remove
 * dialog, so rows carry no buttons. Back closes the panel and the player returns focus to the Queue
 * button. When nothing is up next the panel closes itself, the button having nothing to show.
 */
@Composable
internal fun TvQueuePanel(
    account: SearchAccount?,
    state: AndroidPlaybackState,
    playback: TvPlayerActions,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val resources = context.resources
    fun answered(accepted: Boolean) { if (!accepted) queueEditRefused(context) }
    val firstUpcoming = (state.currentIndex ?: -1) + 1
    val upcoming = state.upcoming()
    LaunchedEffect(upcoming.size) { if (upcoming.isEmpty()) onClose() }
    // The entry the dialog is open for, by identity: a row may have moved, or gone, since.
    var editing by remember { mutableStateOf<String?>(null) }
    val editingAt = editing?.let { id -> state.queue.indexOfFirst { it.queueEntryId == id } }
        ?.takeIf { it >= firstUpcoming && it < state.queue.size }
    if (editing != null && editingAt == null) LaunchedEffect(editing) { editing = null }
    if (editingAt != null) {
        val id = state.queue[editingAt].queueEntryId
        TvQueueMenu("tv.player.upnext.edit",
            state.queue[editingAt].track.title.ifBlank { resources.getString(R.string.tv_player_loading) },
            listOfNotNull(
                // The first entry of Up Next cannot move above the playing one; the last cannot move down.
                if (editingAt > firstUpcoming) TvQueueChoice(resources.getString(SharedR.string.queue_move_up),
                    "tv.player.upnext.edit.moveUp", icon = DulcetIcons.ArrowUp) { answered(playback.moveEntry(id, editingAt - 1)) } else null,
                if (editingAt < state.queue.lastIndex) TvQueueChoice(resources.getString(SharedR.string.queue_move_down),
                    "tv.player.upnext.edit.moveDown", icon = DulcetIcons.ArrowDown) { answered(playback.moveEntry(id, editingAt + 1)) } else null,
                TvQueueChoice(resources.getString(SharedR.string.queue_remove), "tv.player.upnext.edit.remove",
                    icon = DulcetIcons.Delete, destructive = true) { answered(playback.removeEntry(id)) },
                // Clear is here rather than beside the heading: a button there would sit between the
                // summary and the list, and DOWN from the summary must land on the list.
                TvQueueChoice(resources.getString(SharedR.string.queue_clear_up_next), "tv.player.upnext.edit.clear",
                    icon = DulcetIcons.Delete, destructive = true) { answered(playback.clearUpcoming()) },
            ),
            resources.getString(SharedR.string.queue_cancel)) { editing = null }
    }
    val firstRow = remember { FocusRequester() }
    // Initial focus is the first upcoming row, claimed once when that row first exists: requesting
    // it from the panel races the lazy list's first composition, and re-claiming on a track change
    // would steal focus while the person browses.
    var firstRowClaimed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().fillMaxHeight()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back && event.type == KeyEventType.KeyDown) { onClose(); true } else false
            },
    ) {
        val current = state.queue.getOrNull(state.currentIndex ?: -1)
        if (current != null) {
            Row(Modifier.fillMaxWidth().testTag("tv.player.upnext.nowPlaying"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvArtwork(account, current.track.artworkKey, 56, ArtworkEntity.Song, Modifier.size(56.dp))
                Icon(TvPlayerGlyphs.NowPlaying, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(stringResource(R.string.tv_now_playing_row), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current.track.title.ifBlank { stringResource(R.string.tv_player_loading) },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current.track.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(stringResource(R.string.tv_player_up_next), style = MaterialTheme.typography.headlineSmall)
        // Clear of the first row: the title and the list never touch.
        Spacer(Modifier.height(12.dp))
        val loading = stringResource(R.string.tv_player_loading)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("tv.player.upnext"),
            // Room for the focused row's scale, clear of the next row's text.
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(upcoming, key = { _, entry -> entry.queueEntryId }) { index, entry ->
                if (entry.isAutoplay && (index == 0 || !upcoming[index - 1].isAutoplay)) {
                    Text(stringResource(R.string.tv_autoplay), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                if (index == 0 && !firstRowClaimed) {
                    LaunchedEffect(Unit) {
                        firstRowClaimed = true
                        runCatching { firstRow.requestFocus() }
                    }
                }
                ListItem(
                    selected = false,
                    onClick = { playback.jumpTo(entry.queueEntryId) },
                    onLongClick = { editing = entry.queueEntryId },
                    headlineContent = { Text(entry.track.title.ifBlank { loading }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = entry.track.artist?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = { TvArtwork(account, entry.track.artworkKey, QUEUE_ROW_ARTWORK_DP, ArtworkEntity.Song, Modifier.size(QUEUE_ROW_ARTWORK_DP.dp)) },
                    trailingContent = entry.track.durationMilliseconds?.let { duration -> { Text(clock(duration)) } },
                    modifier = Modifier.fillMaxWidth().testTag("tv.player.upnext.$index")
                        .let { if (index == 0) it.focusRequester(firstRow) else it },
                )
            }
        }
    }
}

/**
 * Up Next on the player face, in its own region under the controls: a heading and as many whole rows
 * as the region holds, at most [QUEUE_PREVIEW_MAX_ROWS] and none cut part-way — the full queue keeps
 * its own editing destination. Each row is the shared track-row shape: artwork, title in body-large
 * and artist in body-medium.
 */
@Composable
internal fun TvQueuePreview(account: SearchAccount?, state: AndroidPlaybackState, playback: TvPlayerActions,
                            modifier: Modifier = Modifier, openQueue: () -> Unit) {
    val upcoming = state.upcoming()
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val heading = typography.titleMedium.lineDp(density)
    // The row's text and its list-item padding, never less than the artwork and that padding.
    val row = maxOf((QUEUE_ROW_ARTWORK_DP + 24).dp,
        typography.bodyLarge.lineDp(density) + typography.bodyMedium.lineDp(density) + 24.dp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fit = if (!constraints.hasBoundedHeight) QUEUE_PREVIEW_MAX_ROWS
            else ((maxHeight - QUEUE_PREVIEW_TOP - heading) / (row + QUEUE_PREVIEW_GAP)).toInt()
        val shown = upcoming.take(fit.coerceIn(0, QUEUE_PREVIEW_MAX_ROWS))
        if (shown.isEmpty()) return@BoxWithConstraints
        Column(Modifier.fillMaxWidth().padding(top = QUEUE_PREVIEW_TOP).testTag("tv.player.upnext.preview"),
            verticalArrangement = Arrangement.spacedBy(QUEUE_PREVIEW_GAP)) {
            Text(stringResource(R.string.tv_player_up_next), style = typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            val loading = stringResource(R.string.tv_player_loading)
            shown.forEachIndexed { index, entry ->
                ListItem(selected = false, onClick = { playback.jumpTo(entry.queueEntryId) }, onLongClick = openQueue,
                    modifier = Modifier.fillMaxWidth().height(row).testTag("tv.player.upnext.preview.$index"),
                    leadingContent = { TvArtwork(account, entry.track.artworkKey, QUEUE_ROW_ARTWORK_DP, ArtworkEntity.Song) },
                    headlineContent = { Text(entry.track.title.ifBlank { loading }, style = typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = entry.track.artist?.let { artist -> {
                        Text(artist, style = typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } })
            }
        }
    }
}

/** One line of [this] style in dp at the current font scale. */
private fun TextStyle.lineDp(density: Density): Dp = with(density) {
    (if (lineHeight.isSpecified) lineHeight else fontSize * 1.4f).toDp()
}

/** The artwork of a queue row, here and in the panel; the player's cover paints this size first. */
internal const val QUEUE_ROW_ARTWORK_DP = 48
private const val QUEUE_PREVIEW_MAX_ROWS = 3
private val QUEUE_PREVIEW_TOP = 12.dp
private val QUEUE_PREVIEW_GAP = 4.dp
