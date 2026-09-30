package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.tv.material3.IconButtonDefaults
import com.legitimateapps.dulcet.core.AndroidRepeatMode
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.rememberOutcomeLines
import com.legitimateapps.dulcet.library.rememberWatchedFavourite
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.queueEditRefused
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.playback.PlayRequest
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.rememberPlaybackController
import com.legitimateapps.dulcet.search.ProductionSearchHostDependencies
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.SkipNoticeRegion
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Lean-back Now Playing. Every control is a focusable tv-material component reachable with the
 * D-pad; the remote's media keys reach the same controller through the media session, and through
 * this activity while it has focus.
 */
class TvPlaybackActivity : ComponentActivity() {
    private val pending = MutableStateFlow<PlayRequest?>(null)
    private var controller: AndroidPlaybackController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pending.value = PlaybackIntents.playRequest(intent)
        val account = runCatching { ProductionSearchHostDependencies.loadAccount(this) }.getOrNull()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val playback = rememberPlaybackController()
                controller = playback
                val request by pending.collectAsStateWithLifecycle()
                LaunchedEffect(playback, request) {
                    val next = request ?: return@LaunchedEffect
                    val owner = playback ?: return@LaunchedEffect
                    pending.value = null
                    owner.playSong(next.provider, next.rawId, next.title)
                }
                val state by remember(playback) { playback?.state ?: MutableStateFlow(AndroidPlaybackState()) }
                    .collectAsStateWithLifecycle()
                TvNowPlaying(account, state, playback)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PlaybackIntents.playRequest(intent)?.let { pending.value = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val playback = controller ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> playback.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> playback.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playback.togglePlayPause()
            KeyEvent.KEYCODE_MEDIA_STOP -> playback.stop()
            KeyEvent.KEYCODE_MEDIA_NEXT -> playback.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playback.skipToPrevious()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> playback.seekStep(SEEK_STEP_MILLISECONDS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> playback.seekStep(-SEEK_STEP_MILLISECONDS)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}

/** What the player's controls do: the service's controller in the app, a recording in a test. */
internal interface TvPlayerActions {
    fun togglePlayPause()
    fun previous()
    fun next()
    fun setShuffle(enabled: Boolean)
    fun cycleRepeatMode()
    fun seek(positionMilliseconds: Long)
    fun jumpTo(queueEntryId: String)
    /** Queue edits (spec §14.1); each answers whether the queue accepted it. */
    fun moveEntry(queueEntryId: String, toIndex: Int): Boolean
    fun removeEntry(queueEntryId: String): Boolean
    fun clearUpcoming(): Boolean
}

private class ControllerActions(private val controller: AndroidPlaybackController) : TvPlayerActions {
    override fun togglePlayPause() = controller.togglePlayPause()
    override fun previous() = controller.skipToPrevious()
    override fun next() = controller.next()
    override fun setShuffle(enabled: Boolean) = controller.setShuffle(enabled)
    override fun cycleRepeatMode() = controller.cycleRepeatMode()
    override fun seek(positionMilliseconds: Long) = controller.seek(positionMilliseconds)
    override fun jumpTo(queueEntryId: String) = controller.jumpTo(queueEntryId)
    override fun moveEntry(queueEntryId: String, toIndex: Int) = controller.moveEntry(queueEntryId, toIndex)
    override fun removeEntry(queueEntryId: String) = controller.removeEntry(queueEntryId)
    override fun clearUpcoming() = controller.clearUpcoming()
}

/**
 * The playing track's heart on the TV player: its state as this device knows it, [toggle] to change
 * it, and the words for a change that needs them ([line]), the library screens' own (§16.20).
 */
internal class TvPlayerFavourite(val favourite: Boolean, val line: String?, val toggle: () -> Unit)

@Composable
internal fun TvNowPlaying(account: SearchAccount?, state: AndroidPlaybackState, playback: AndroidPlaybackController?) {
    val context = LocalContext.current
    // The account's process reader, for the heart alone. This session is never started: the library
    // activity's session reports the foreground and reachability, and a second one here would tell
    // the reader the app went to the background whenever that activity stops behind this one.
    val library = remember(account) {
        account?.let { runCatching { LibrarySession(context, it, foreground = false) }.getOrNull() }
    }
    DisposableEffect(library) { onDispose { library?.close() } }
    val rawId = state.queue.getOrNull(state.currentIndex ?: -1)?.track?.rawId
    val target = remember(rawId) { rawId?.let { AndroidLibraryEntity(AndroidLibraryEntityKind.Track, it) } }
    val favourite = if (library != null && target != null) {
        val value = rememberWatchedFavourite(library, target)
        val line = rememberOutcomeLines(library, listOf(target)).firstOrNull()?.second
        TvPlayerFavourite(value == true, line) { library.setFavourite(target, value != true) }
    } else {
        null
    }
    val lyrics: (@Composable () -> Unit)? = library?.let { session -> { TvLyricsPanel(session, state) } }
    TvNowPlayingScreen(account, state, remember(playback) { playback?.let(::ControllerActions) }, favourite, lyrics)
}

/** Lean-back Now Playing for [state]; null [playback] before the service is bound or without an account. */
@Composable
internal fun TvNowPlayingScreen(
    account: SearchAccount?,
    state: AndroidPlaybackState,
    playback: TvPlayerActions?,
    favourite: TvPlayerFavourite? = null,
    lyrics: (@Composable () -> Unit)? = null,
) {
    val playFocus = remember { FocusRequester() }
    // The lyrics take Up Next's place while on (§18.4); the setting outlives a track change.
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playback != null) { if (playback != null) runCatching { playFocus.requestFocus() } }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), MaterialTheme.colorScheme.surface)))) {
            Row(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(360.dp).testTag("tv.player.artwork")) {
                        TvArtwork(account, state.artworkKey, 360, DulcetIcons.Album, rounded = true)
                        // Along the bottom of the cover, which takes no focus: the player's own
                        // bottom edge is the Up Next list, whose rows the D-pad moves through, and
                        // a card over a focused row hides where focus is (spec §12.12 rule 8).
                        SkipNoticeRegion(state.skipNotice, visible = true, MaterialTheme.colorScheme.inverseSurface,
                            MaterialTheme.colorScheme.inverseOnSurface, MaterialTheme.typography.titleMedium,
                            Modifier.matchParentSize())
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(stringResource(if (playback == null) R.string.tv_player_needs_account else R.string.tv_player_heading),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(state.title.ifBlank {
                        stringResource(if (state.hasSession) R.string.tv_player_loading else R.string.tv_player_nothing)
                    }, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("tv.player.title"))
                    Text(listOfNotNull(state.artist, state.album).joinToString(" · "),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    favourite?.line?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp).testTag("tv.player.favourite.outcome"))
                    }
                    Spacer(Modifier.height(20.dp))
                    TvScrubber(state, playback)
                    if (state.error != null) Text(stringResource(R.string.tv_player_failed),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp).testTag("tv.player.error"))
                    Spacer(Modifier.height(20.dp))
                    TvTransport(state, playback, playFocus, favourite,
                        lyrics = if (lyrics != null && state.queue.getOrNull(state.currentIndex ?: -1) != null) showLyrics else null) {
                        showLyrics = !showLyrics
                    }
                    Spacer(Modifier.height(24.dp))
                    if (showLyrics && lyrics != null && state.queue.getOrNull(state.currentIndex ?: -1) != null) lyrics()
                    else if (state.queue.size > 1) TvUpNext(state, playback)
                }
            }
        }
    }
}

/** Shuffle, Previous, Play/Pause, Next and Repeat, left to right, as on the phone. */
@Composable
private fun TvTransport(state: AndroidPlaybackState, playback: TvPlayerActions?, playFocus: FocusRequester, favourite: TvPlayerFavourite?,
                        lyrics: Boolean? = null, toggleLyrics: () -> Unit = {}) {
    val live = playback != null
    // tv-material buttons can keep an onClick from an earlier composition: read the modes live.
    val current by rememberUpdatedState(state)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        TvToggle(DulcetIcons.Shuffle,
            stringResource(if (state.shuffle) R.string.tv_player_shuffle_on else R.string.tv_player_shuffle_off),
            on = state.shuffle, enabled = live, tag = "tv.player.shuffle") { playback?.setShuffle(!current.shuffle) }
        IconButton(onClick = { playback?.previous() }, enabled = live && (state.canGoPrevious || state.canRestart),
            modifier = Modifier.testTag("tv.player.previous")) {
            Icon(DulcetIcons.SkipPrevious, stringResource(R.string.tv_player_previous))
        }
        IconButton(onClick = { playback?.togglePlayPause() }, enabled = live,
            modifier = Modifier.size(64.dp).focusRequester(playFocus).testTag("tv.player.playpause")) {
            Icon(if (state.playWhenReady) DulcetIcons.Pause else DulcetIcons.Play,
                stringResource(if (state.playWhenReady) R.string.tv_player_pause else R.string.tv_player_play), Modifier.size(36.dp))
        }
        IconButton(onClick = { playback?.next() }, enabled = live && state.canGoNext,
            modifier = Modifier.testTag("tv.player.next")) {
            Icon(DulcetIcons.SkipNext, stringResource(R.string.tv_player_next))
        }
        val repeat = when (state.repeatMode) {
            AndroidRepeatMode.Off -> R.string.tv_player_repeat_off
            AndroidRepeatMode.All -> R.string.tv_player_repeat_all
            AndroidRepeatMode.One -> R.string.tv_player_repeat_one
        }
        TvToggle(if (state.repeatMode == AndroidRepeatMode.One) DulcetIcons.RepeatOne else DulcetIcons.Repeat,
            stringResource(repeat), on = state.repeatMode != AndroidRepeatMode.Off, enabled = live,
            tag = "tv.player.repeat") { playback?.cycleRepeatMode() }
        // After Repeat, so the transport's order is the phone's; the heart changes the track, not playback.
        if (favourite != null) {
            val latest by rememberUpdatedState(favourite)
            TvToggle(if (favourite.favourite) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder,
                stringResource(if (favourite.favourite) SharedR.string.library_favourite_remove else SharedR.string.library_favourite_add),
                on = favourite.favourite, enabled = true, tag = "tv.player.favourite") { latest.toggle() }
        }
        if (lyrics != null) {
            TvToggle(DulcetIcons.Lyrics, stringResource(SharedR.string.lyrics_title), on = lyrics, enabled = true,
                tag = "tv.player.lyrics") { toggleLyrics() }
        }
    }
}

/** A two-state control whose state is its fill; its description says the state. */
@Composable
private fun TvToggle(icon: ImageVector, description: String, on: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag).semantics { selected = on },
        colors = if (on) IconButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) else IconButtonDefaults.colors(),
    ) { Icon(icon, description) }
}

/**
 * The position, and a scrubber a remote can move: focused, LEFT and RIGHT seek back and forward by
 * [SEEK_STEP_MILLISECONDS], and accessibility services can set the position directly. Repeated presses
 * add up from the last target, not from a position the player has not published yet.
 */
@Composable
private fun TvScrubber(state: AndroidPlaybackState, playback: TvPlayerActions?) {
    val duration = state.durationMilliseconds?.takeIf { it > 0 }
    val seekable = playback != null && state.seekable && duration != null
    var target by remember { mutableStateOf<Long?>(null) }
    // The target stands until the player publishes a position at it, or for a few seconds if it never does.
    LaunchedEffect(target, state.positionMilliseconds) {
        val pending = target ?: return@LaunchedEffect
        if (kotlin.math.abs(state.positionMilliseconds - pending) < SEEK_STEP_MILLISECONDS / 5) target = null
        else { delay(3_000); target = null }
    }
    val shown = target ?: state.positionMilliseconds
    val fraction = if (duration == null) 0f else (shown.toFloat() / duration).coerceIn(0f, 1f)
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(R.string.tv_player_position)
    fun seekTo(position: Long) {
        val end = duration ?: return
        val clamped = position.coerceIn(0, end)
        target = clamped
        playback?.seek(clamped)
    }
    Column(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (!seekable || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { seekTo(shown - SEEK_STEP_MILLISECONDS); true }
                    Key.DirectionRight -> { seekTo(shown + SEEK_STEP_MILLISECONDS); true }
                    else -> false
                }
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (seekable) setProgress { value -> seekTo((value * duration!!).toLong()); true }
            }
            .focusable(seekable)
            .testTag("tv.player.scrubber"),
    ) {
        val height = if (focused) 10.dp else 6.dp
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.35f else 0.2f))) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                .background(if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(clock(shown), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("tv.player.position"))
            Spacer(Modifier.weight(1f))
            Text(duration?.let(::clock).orEmpty(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The queue, the playing entry kept in view; selecting an entry plays it. Each entry after the playing
 * one has an options button, RIGHT of it, whose dialog moves it up or down within Up Next or removes
 * it, and Clear Up Next removes all of it. The playing entry and those before it offer
 * no edits: the queue refuses to remove the playing one (spec §14.1), and the Apple shells edit Up
 * Next only. A refused edit is said.
 */
@Composable
private fun TvUpNext(state: AndroidPlaybackState, playback: TvPlayerActions?) {
    val context = LocalContext.current
    val resources = context.resources
    fun answered(accepted: Boolean) { if (!accepted) queueEditRefused(context) }
    // With no playing entry, the whole queue is Up Next.
    val firstUpcoming = state.currentIndex?.plus(1) ?: 0
    var editing by remember { mutableStateOf<String?>(null) }
    // The entry the dialog is open for, by identity: a row may have moved, or gone, since.
    val editingAt = editing?.let { id -> state.queue.indexOfFirst { it.queueEntryId == id } }
        ?.takeIf { it >= firstUpcoming && playback != null }
    if (editing != null && editingAt == null) LaunchedEffect(editing) { editing = null }
    if (editingAt != null && playback != null) {
        val id = state.queue[editingAt].queueEntryId
        TvQueueMenu("tv.player.upnext.edit",
            state.queue[editingAt].track.title.ifBlank { resources.getString(R.string.tv_player_loading) },
            listOfNotNull(
                if (editingAt > firstUpcoming) TvQueueChoice(resources.getString(SharedR.string.queue_move_up),
                    "tv.player.upnext.edit.moveUp") { answered(playback.moveEntry(id, editingAt - 1)) } else null,
                if (editingAt < state.queue.lastIndex) TvQueueChoice(resources.getString(SharedR.string.queue_move_down),
                    "tv.player.upnext.edit.moveDown") { answered(playback.moveEntry(id, editingAt + 1)) } else null,
                TvQueueChoice(resources.getString(SharedR.string.queue_remove), "tv.player.upnext.edit.remove") {
                    answered(playback.removeEntry(id))
                },
                // Clear is here rather than beside the heading: a button there would sit between the
                // transport and the list, and DOWN from the transport must land on the list.
                TvQueueChoice(resources.getString(SharedR.string.queue_clear_up_next), "tv.player.upnext.edit.clear") {
                    answered(playback.clearUpcoming())
                },
            ),
            resources.getString(SharedR.string.queue_cancel)) { editing = null }
    }
    Text(stringResource(R.string.tv_player_up_next), style = MaterialTheme.typography.titleMedium)
    val list = rememberLazyListState()
    // The playing entry near the top, with the one before it still in view for context.
    LaunchedEffect(state.currentIndex) { state.currentIndex?.let { list.scrollToItem((it - 1).coerceAtLeast(0)) } }
    val loading = stringResource(R.string.tv_player_loading)
    val options = stringResource(SharedR.string.queue_entry_options)
    LazyColumn(Modifier.fillMaxWidth().height(180.dp).testTag("tv.player.upnext"), state = list) {
        itemsIndexed(state.queue, key = { _, entry -> entry.queueEntryId }) { position, entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListItem(
                    selected = position == state.currentIndex,
                    onClick = { playback?.jumpTo(entry.queueEntryId) },
                    headlineContent = { Text(entry.track.title.ifBlank { loading }, maxLines = 1) },
                    supportingContent = entry.track.artist?.let { { Text(it, maxLines = 1) } },
                    modifier = Modifier.weight(1f).testTag("tv.player.upnext.$position"),
                )
                if (playback != null && position >= firstUpcoming) {
                    IconButton(onClick = { editing = entry.queueEntryId },
                        modifier = Modifier.testTag("tv.player.upnext.$position.edit")) {
                        Icon(DulcetIcons.MoreVert, options)
                    }
                }
            }
        }
    }
}

/** Seeks [step] from the published position, within the track, when the track can seek. */
private fun AndroidPlaybackController.seekStep(step: Long) {
    val current = state.value
    val duration = current.durationMilliseconds?.takeIf { it > 0 } ?: return
    if (!current.seekable) return
    seek((current.positionMilliseconds + step).coerceIn(0, duration))
}

/** How far one press of LEFT or RIGHT on the scrubber, or a remote's rewind or fast-forward key, moves. */
internal const val SEEK_STEP_MILLISECONDS = 10_000L
