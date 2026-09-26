package com.legitimateapps.dulcet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidRepeatMode
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.ArtworkImages
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.SkipNoticeRegion

/** Persistent transport above the tab bar whenever a session exists. */
@Composable
internal fun MiniPlayer(
    account: SearchAccount,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController?,
    open: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().testTag("player.mini").clickable(onClick = open)) {
        Column {
            val duration = state.durationMilliseconds?.takeIf { it > 0 }
            LinearProgressIndicator(
                progress = { if (duration == null) 0f else (state.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(account, state.artworkKey, state.title, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(state.title.ifBlank { stringResource(R.string.now_playing_loading) },
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("player.mini.title"))
                    state.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PlayPauseButton(state, playback, filled = false, size = 48)
                IconButton(onClick = { playback?.next() }, enabled = state.canGoNext, modifier = Modifier.testTag("player.mini.next")) {
                    Icon(DulcetIcons.SkipNext, stringResource(R.string.action_next))
                }
            }
        }
    }
}

@Composable
private fun PlayPauseButton(state: AndroidPlaybackState, playback: AndroidPlaybackController?, filled: Boolean, size: Int) {
    val playing = state.playWhenReady
    val label = stringResource(if (playing) R.string.action_pause else R.string.action_play)
    val icon = if (playing) DulcetIcons.Pause else DulcetIcons.Play
    val modifier = Modifier.size(size.dp).testTag(if (filled) "player.playpause" else "player.mini.playpause")
    if (filled) FilledIconButton(onClick = { playback?.togglePlayPause() }, modifier = modifier) {
        Icon(icon, label, Modifier.size((size * 0.45f).dp))
    } else IconButton(onClick = { playback?.togglePlayPause() }, modifier = modifier) { Icon(icon, label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingScreen(
    account: SearchAccount,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController,
    close: () -> Unit,
) {
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val surface = MaterialTheme.colorScheme.surface
    val accent = state.artworkKey?.let { ArtworkImages.accent(account, it) }
    val top by animateColorAsState(accent?.copy(alpha = 0.55f)?.compositeOver(surface) ?: MaterialTheme.colorScheme.primaryContainer,
        label = "now-playing-background")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, surface))).testTag("player.full")
        // The player covers the pages beneath it; touches must not fall through to them. A pointer
        // handler that consumes nothing, not `clickable`: a clickable merges everything inside it
        // into one node, so a screen reader read the whole player, the skip notice included, as
        // one label, and could not reach the notice on its own.
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close, modifier = Modifier.testTag("player.close")) {
                    Icon(DulcetIcons.ExpandMore, stringResource(R.string.action_close_player))
                }
                Text(state.album ?: stringResource(R.string.now_playing), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = { showQueue = true }, modifier = Modifier.testTag("player.queue")) {
                    Icon(DulcetIcons.QueueMusic, stringResource(R.string.action_up_next))
                }
            }
            Spacer(Modifier.weight(0.5f))
            // The cover settles back when paused, as a physical sleeve would.
            val scale by animateFloatAsState(if (state.playWhenReady) 1f else 0.86f, spring(dampingRatio = 0.6f), label = "cover")
            Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("player.artwork")) {
                Artwork(account, state.artworkKey, state.title, null,
                    Modifier.fillMaxSize().scale(scale).shadow(24.dp, RoundedCornerShape(12.dp)))
                // The notice lies along the bottom of the cover, which takes no input, so it covers
                // no control on any phone. The player's own bottom edge is its transport row on a
                // small phone (spec §12.12 rule 8).
                PhoneSkipNotice(state, visible = true, Modifier.matchParentSize())
            }
            Spacer(Modifier.weight(0.5f))
            Column(Modifier.fillMaxWidth()) {
                Text(state.title.ifBlank {
                        stringResource(if (state.hasSession) R.string.now_playing_loading else R.string.now_playing_nothing)
                    },
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.basicMarquee().testTag("player.title"))
                Text(state.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(20.dp))
            Scrubber(state, playback)
            if (state.error != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("player.error")) {
                    Text(stringResource(R.string.now_playing_failed), Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                ToggleIcon(DulcetIcons.Shuffle, stringResource(R.string.action_shuffle), state.shuffle, "player.shuffle") {
                    playback.setShuffle(!state.shuffle)
                }
                IconButton(onClick = playback::skipToPrevious, enabled = state.canGoPrevious || state.canRestart,
                    modifier = Modifier.size(64.dp).testTag("player.previous")) {
                    Icon(DulcetIcons.SkipPrevious, stringResource(R.string.action_previous), Modifier.size(40.dp))
                }
                PlayPauseButton(state, playback, filled = true, size = 80)
                IconButton(onClick = playback::next, enabled = state.canGoNext, modifier = Modifier.size(64.dp).testTag("player.next")) {
                    Icon(DulcetIcons.SkipNext, stringResource(R.string.action_next), Modifier.size(40.dp))
                }
                val repeatLabel = stringResource(when (state.repeatMode) {
                    AndroidRepeatMode.Off -> R.string.action_repeat_off
                    AndroidRepeatMode.All -> R.string.action_repeat_all
                    AndroidRepeatMode.One -> R.string.action_repeat_one
                })
                ToggleIcon(if (state.repeatMode == AndroidRepeatMode.One) DulcetIcons.RepeatOne else DulcetIcons.Repeat,
                    repeatLabel, state.repeatMode != AndroidRepeatMode.Off, "player.repeat", playback::cycleRepeatMode)
            }
            Spacer(Modifier.weight(0.4f))
        }
    }
    if (showQueue) UpNextSheet(account, state, playback) { showQueue = false }
}

/** The skip notice (spec §12.12 rule 5) in the phone's colours, at the bottom of the region it fills. */
@Composable
internal fun PhoneSkipNotice(state: AndroidPlaybackState, visible: Boolean, modifier: Modifier = Modifier) {
    SkipNoticeRegion(state.skipNotice, visible, MaterialTheme.colorScheme.inverseSurface,
        MaterialTheme.colorScheme.inverseOnSurface, MaterialTheme.typography.bodyMedium, modifier.fillMaxSize())
}

@Composable
private fun Scrubber(state: AndroidPlaybackState, playback: AndroidPlaybackController) {
    val duration = state.durationMilliseconds?.takeIf { it > 0 }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration == null) 0f else (state.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            val target = dragging
            dragging = null
            if (target != null && duration != null) playback.seek((target * duration).toLong())
        },
        enabled = state.seekable && duration != null,
        modifier = Modifier.fillMaxWidth().testTag("player.scrubber"),
    )
    Row(Modifier.fillMaxWidth()) {
        val shown = dragging?.let { (it * (duration ?: 0)).toLong() } ?: state.positionMilliseconds
        Text(formatDuration(shown), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("player.position"))
        Spacer(Modifier.weight(1f))
        Text(duration?.let { "-" + formatDuration(it - shown) } ?: "", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val onOff = stringResource(if (active) R.string.state_on else R.string.state_off)
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag).semantics {
        stateDescription = onOff
    }, colors = IconButtonDefaults.iconButtonColors(
        contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(icon, label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpNextSheet(account: SearchAccount, state: AndroidPlaybackState, playback: AndroidPlaybackController, dismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, modifier = Modifier.testTag("player.upnext")) {
        Text(stringResource(R.string.action_up_next), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (state.queue.isEmpty()) {
            Text(stringResource(R.string.up_next_empty), Modifier.padding(24.dp))
            return@ModalBottomSheet
        }
        val list = rememberLazyListState()
        LaunchedEffect(Unit) { state.currentIndex?.let { list.scrollToItem(it) } }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(state.queue, key = { _, entry -> entry.queueEntryId }) { position, entry ->
                val current = position == state.currentIndex
                val past = state.currentIndex?.let { position < it } ?: false
                ListItem(
                    headlineContent = {
                        Text(entry.track.title.ifBlank { stringResource(R.string.now_playing_loading) }, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            fontWeight = if (current) FontWeight.SemiBold else null)
                    },
                    supportingContent = entry.track.artist?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = { Artwork(account, entry.track.artworkKey, entry.track.title, 44.dp) },
                    trailingContent = when {
                        current -> { { Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing),
                            tint = MaterialTheme.colorScheme.primary) } }
                        entry.track.durationMilliseconds != null -> { { Text(formatDuration(entry.track.durationMilliseconds!!),
                            style = MaterialTheme.typography.bodySmall) } }
                        else -> null
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !current) { playback.jumpTo(entry.queueEntryId) }
                        .alpha(if (past) 0.55f else 1f)
                        .testTag("player.upnext.$position"),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
