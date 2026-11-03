package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.IconButtonDefaults
import com.legitimateapps.dulcet.core.AndroidRepeatMode
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.connectionLine
import com.legitimateapps.dulcet.library.savedAccountLine
import com.legitimateapps.dulcet.library.rememberOutcomeLines
import com.legitimateapps.dulcet.library.rememberWatchedFavourite
import com.legitimateapps.dulcet.library.rememberWatchedRating
import com.legitimateapps.dulcet.ui.sleepTimerLabel
import com.legitimateapps.dulcet.shared.R as SharedR
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.playback.PlayRequest
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.ui.DroppedAdditionsNotice
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
                DroppedAdditionsNotice(playback, state.droppedAdditions, state.queueConfirmation)
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

/**
 * The playing track's rating as this device knows it (0 unrated, null unknown), and [rate] to set
 * one, 0 removing it. The stars themselves live in the listening options panel ([TvRatingStars]);
 * a rating's outcome line is the heart's ([TvPlayerFavourite.line]).
 */
internal class TvPlayerRating(val rating: Int?, val rate: (Int) -> Unit)

/**
 * A saved account not yet connected in this process (spec §13.1): [line] says what waits for the
 * server, and [reconnect] connects it in place.
 */
internal class TvPlayerSaved(val line: String, val reconnect: () -> Unit)

@Composable
internal fun TvNowPlaying(account: SearchAccount?, state: AndroidPlaybackState, playback: AndroidPlaybackController?) {
    val context = LocalContext.current
    // A cold notification entry owns a foreground session too: no library activity is required.
    val foreground = com.legitimateapps.dulcet.library.hostInForeground()
    val library = remember(account) {
        account?.let { runCatching { LibrarySession(context, it, foreground, restoredAccount = true) }.getOrNull() }
    }
    library?.let { com.legitimateapps.dulcet.library.LibraryLifecycle(it) }
    val rawId = state.queue.getOrNull(state.currentIndex ?: -1)?.track?.rawId
    val target = remember(rawId) { rawId?.let { AndroidLibraryEntity(AndroidLibraryEntityKind.Track, it) } }
    val favourite = if (library != null && target != null) {
        val value = rememberWatchedFavourite(library, target)
        val line = rememberOutcomeLines(library, listOf(target)).firstOrNull()?.second
        TvPlayerFavourite(value == true, line) { library.setFavourite(target, value != true) }
    } else {
        null
    }
    val rating = if (library != null && target != null) {
        TvPlayerRating(rememberWatchedRating(library, target)) { library.setRating(target, it) }
    } else {
        null
    }
    val lyrics: (@Composable () -> Unit)? = library?.let { session -> { TvLyricsPanel(session, state) } }
    val saved = if (library != null && account != null) {
        val connection by library.connection.collectAsState()
        val line = if (state.needsReconnect) context.getString(SharedR.string.now_playing_needs_reconnect)
            else context.resources.savedAccountLine(connection, account) ?: context.resources.connectionLine(connection)
        line?.let { TvPlayerSaved(it, library::connectSavedAccount) }
    } else {
        null
    }
    TvNowPlayingScreen(account, state, remember(playback) { playback?.let(::ControllerActions) }, favourite, lyrics, saved,
        listening = playback?.let { controller -> { TvListeningSessionButton(state, controller::setAutoplay,
            controller::setSleepTimer, controller::setCrossfade, rating) } })
}

/** The upcoming entries: what follows the playing track; the queue panel lists exactly these. */
internal fun AndroidPlaybackState.upcoming(): List<AndroidQueueEntry> =
    queue.drop((currentIndex ?: -1) + 1)

/** Lean-back Now Playing for [state]; null [playback] before the service is bound or without an account. */
@Composable
internal fun TvNowPlayingScreen(
    account: SearchAccount?,
    state: AndroidPlaybackState,
    playback: TvPlayerActions?,
    favourite: TvPlayerFavourite? = null,
    lyrics: (@Composable () -> Unit)? = null,
    saved: TvPlayerSaved? = null,
    listening: (@Composable () -> Unit)? = null,
) {
    val playFocus = remember { FocusRequester() }
    val queueFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    // The lyrics take the metadata's place while on (§18.4); the setting outlives a track change.
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    // Up Next is a destination of its own, opened from the transport's Queue button.
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    var restoreQueueFocus by remember { mutableStateOf(false) }
    val hasUpcoming = state.upcoming().isNotEmpty()
    LaunchedEffect(playback != null, state.hasSession) {
        if (state.hasSession) {
            if (playback != null) runCatching { playFocus.requestFocus() }
        } else {
            runCatching { emptyFocus.requestFocus() }
        }
    }
    LaunchedEffect(queueOpen, hasUpcoming, restoreQueueFocus) {
        // Back from the queue lands on the button that opened it; if the queue emptied meanwhile
        // (its button is gone), on Play/Pause.
        if (!queueOpen && restoreQueueFocus) {
            restoreQueueFocus = false
            runCatching { (if (hasUpcoming && playback != null) queueFocus else playFocus).requestFocus() }
        }
    }
    if (!state.hasSession) {
        TvPlayerEmpty(account != null, emptyFocus, saved, needsReconnect = state.needsReconnect)
        return
    }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), MaterialTheme.colorScheme.surface)))) {
            // 48 dp of safe margin below: no control may reach the overscan edge.
            Row(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(if (state.sleepTimer == null) 300.dp else 260.dp).testTag("tv.player.artwork")) {
                        TvArtwork(account, state.artworkKey, if (state.sleepTimer == null) 300 else 260, DulcetIcons.Album, rounded = true)
                        // Along the bottom of the cover, which takes no focus: the player's own
                        // bottom edge is the transport, clear of the notice (spec §12.12 rule 8).
                        SkipNoticeRegion(state.skipNotice, visible = true, MaterialTheme.colorScheme.inverseSurface,
                            MaterialTheme.colorScheme.inverseOnSurface, MaterialTheme.typography.titleMedium,
                            Modifier.matchParentSize())
                    }
                    if (state.sleepTimer != null) {
                        // A running sleep timer is visible on the player, not only in the panel.
                        Row(Modifier.padding(top = 16.dp).testTag("tv.player.sleep.active"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(TvPlayerGlyphs.SleepTimer, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text(sleepTimerLabel(state.sleepTimer, state.sleepRemainingMilliseconds),
                                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (playback != null && hasUpcoming) {
                        Spacer(Modifier.height(16.dp))
                        TvQueuePreview(account, state, playback) { queueOpen = true }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    if (queueOpen && playback != null) {
                        TvQueuePanel(account, state, playback, onClose = {
                            queueOpen = false
                            restoreQueueFocus = true
                        })
                        return@Column
                    }
                    val track = state.queue.getOrNull(state.currentIndex ?: -1)
                    val lyricsOn = lyrics != null && track != null && showLyrics
                    Text(state.title.ifBlank { stringResource(R.string.tv_player_loading) },
                        style = if (lyricsOn) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("tv.player.title"))
                    Text(listOfNotNull(state.artist, state.album).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (lyricsOn) {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) { lyrics?.invoke() }
                    }
                    favourite?.line?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp).testTag("tv.player.favourite.outcome"))
                    }
                    Spacer(Modifier.height(20.dp))
                    TvScrubber(state, playback)
                    if (state.error != null) Text(stringResource(R.string.tv_player_failed),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp).testTag("tv.player.error"))
                    // Between the scrubber and the transport, so UP from Play reaches Reconnect.
                    saved?.let { notice ->
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvAction(stringResource(SharedR.string.library_reconnect), "tv.player.reconnect", onClick = notice.reconnect)
                            Text(notice.line, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(if (state.needsReconnect) "tv.player.needsReconnect" else "tv.player.saved"))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    TvTransport(state, playback, playFocus, queueFocus = null) { queueOpen = true }
                    Spacer(Modifier.height(16.dp))
                    TvPlayerSecondary(favourite,
                        lyricsOn = if (lyrics != null && track != null) showLyrics else null,
                        toggleLyrics = { showLyrics = !showLyrics }, listening = listening,
                        queueFocus = if (playback != null && hasUpcoming) queueFocus else null, openQueue = { queueOpen = true })
                }
            }
        }
    }
}

/**
 * Nothing playing: no session, so no scrubber or transport — a headline, one reason line, a saved
 * account's reconnect when there is one, and a focused way on: connect an account, or browse the
 * library of a connected one.
 */
@Composable
private fun TvPlayerEmpty(signedIn: Boolean, emptyFocus: FocusRequester, saved: TvPlayerSaved?, needsReconnect: Boolean) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.tv_player_nothing_title), style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.testTag("tv.player.empty"))
                Text(stringResource(if (signedIn) R.string.tv_player_nothing_body else R.string.tv_player_needs_account),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                saved?.let { notice ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvAction(stringResource(SharedR.string.library_reconnect), "tv.player.reconnect", onClick = notice.reconnect)
                        Text(notice.line, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(if (needsReconnect) "tv.player.needsReconnect" else "tv.player.saved"))
                    }
                }
                Button(
                    onClick = {
                        context.startActivity(Intent(context, TvSearchActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.focusRequester(emptyFocus)
                        .testTag(if (signedIn) "tv.player.browse" else "tv.player.connect"),
                ) {
                    Text(stringResource(if (signedIn) R.string.tv_player_browse else R.string.tv_player_connect))
                }
            }
        }
    }
}

/**
 * The transport, centred on the filled Play/Pause that takes the player's initial focus: Previous and
 * Next beside it, Shuffle and Repeat smaller and toggle-styled, and the Queue button — the way to Up
 * Next — at its end.
 */
@Composable
private fun TvTransport(state: AndroidPlaybackState, playback: TvPlayerActions?, playFocus: FocusRequester,
                        queueFocus: FocusRequester?, openQueue: () -> Unit) {
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
        Button(onClick = { playback?.togglePlayPause() }, enabled = live,
            colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(64.dp).focusRequester(playFocus).testTag("tv.player.playpause")) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(if (state.playWhenReady) DulcetIcons.Pause else DulcetIcons.Play,
                    stringResource(if (state.playWhenReady) R.string.tv_player_pause else R.string.tv_player_play), Modifier.size(36.dp))
            }
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
        if (queueFocus != null) {
            IconButton(onClick = openQueue, modifier = Modifier.focusRequester(queueFocus).testTag("tv.player.queue")) {
                Icon(DulcetIcons.QueueMusic, stringResource(R.string.tv_player_up_next))
            }
        }
    }
}

/**
 * The secondary group under the transport: the heart — its state is the filled or outlined glyph on
 * the same neutral container, never colour — then lyrics and More. The lyrics toggle's on state is
 * a primary container; focus stays scale and border.
 */
@Composable
private fun TvPlayerSecondary(favourite: TvPlayerFavourite?, lyricsOn: Boolean?, toggleLyrics: () -> Unit,
                              listening: (@Composable () -> Unit)?, queueFocus: FocusRequester? = null,
                              openQueue: () -> Unit = {}) {
    if (favourite == null && lyricsOn == null && listening == null && queueFocus == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (favourite != null) {
            val latest by rememberUpdatedState(favourite)
            IconButton(onClick = { latest.toggle() },
                modifier = Modifier.testTag("tv.player.favourite").semantics { selected = favourite.favourite }) {
                Icon(if (favourite.favourite) DulcetIcons.Favourite else DulcetIcons.FavouriteBorder,
                    stringResource(if (favourite.favourite) SharedR.string.library_favourite_remove else SharedR.string.library_favourite_add))
            }
        }
        if (lyricsOn != null) {
            IconButton(onClick = toggleLyrics,
                modifier = Modifier.testTag("tv.player.lyrics").semantics { selected = lyricsOn },
                colors = if (lyricsOn) IconButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) else IconButtonDefaults.colors()) {
                Icon(DulcetIcons.Lyrics, stringResource(SharedR.string.lyrics_title))
            }
        }
        if (queueFocus != null) IconButton(onClick = openQueue,
            modifier = Modifier.focusRequester(queueFocus).testTag("tv.player.queue")) {
            Icon(DulcetIcons.QueueMusic, stringResource(R.string.tv_player_up_next))
        }
        listening?.invoke()
    }
}

/** A small two-state control: its on state is a tonal container, its description says the state. */
@Composable
private fun TvToggle(icon: ImageVector, description: String, on: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp).testTag(tag).semantics { selected = on },
        colors = if (on) IconButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) else IconButtonDefaults.colors(),
    ) { Icon(icon, description, Modifier.size(22.dp)) }
}

/**
 * The position, and a scrubber a remote can move: focused, the track grows a thumb and the position
 * reads in the lit colour, and LEFT and RIGHT seek back and forward by [SEEK_STEP_MILLISECONDS];
 * accessibility services can set the position directly. Repeated presses add up from the last target,
 * not from a position the player has not published yet.
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
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (focused) 12.dp else 6.dp)) {
            val thumb = 12.dp
            Box(Modifier.fillMaxWidth().height(if (focused) 10.dp else 6.dp).align(Alignment.Center)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.35f else 0.2f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
                    .background(if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary))
            }
            if (focused) {
                Box(Modifier.align(Alignment.CenterStart)
                    .padding(start = ((maxWidth - thumb) * fraction).coerceAtLeast(0.dp))
                    .size(thumb).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(clock(shown), style = MaterialTheme.typography.labelMedium,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Unspecified,
                modifier = Modifier.testTag("tv.player.position"))
            Spacer(Modifier.weight(1f))
            Text(duration?.let(::clock).orEmpty(), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Glyphs drawn here on the Material 24-unit grid, as [DulcetIcons] are, kept to what this screen needs. */
internal object TvPlayerGlyphs {
    /** A crescent moon: a sleep timer running. */
    val SleepTimer: ImageVector = glyph("SleepTimer",
        "M12.34 2.02C6.59 1.82 2 6.42 2 12c0 5.52 4.48 10 10 10 3.71 0 6.93-2.02 8.66-5.02-7.51-.25-12.09-8.43-8.32-14.96z")

    /** Three bars: the track being played, in the queue panel's Now playing summary. */
    val NowPlaying: ImageVector = glyph("NowPlaying", "M10 20h4V4h-4v16zm-6 0h4v-8H4v8zM16 9v11h4V9h-4z")

    private fun glyph(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()
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
