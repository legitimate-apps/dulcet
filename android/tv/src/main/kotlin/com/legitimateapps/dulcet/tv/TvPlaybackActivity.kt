package com.legitimateapps.dulcet.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.legitimateapps.dulcet.ui.rememberArtwork
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
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}

@Composable
internal fun TvNowPlaying(account: SearchAccount?, state: AndroidPlaybackState, playback: AndroidPlaybackController?) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(playback != null) { if (playback != null) runCatching { playFocus.requestFocus() } }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), MaterialTheme.colorScheme.surface)))) {
            Row(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    TvArtwork(account, state.artworkKey, 360)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(if (playback == null) "Connect an account before playing." else "Now Playing",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(state.title.ifBlank { if (state.hasSession) "Loading…" else "Nothing is playing" },
                        style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("tv.player.title"))
                    Text(listOfNotNull(state.artist, state.album).joinToString(" · "),
                        style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    TvProgress(state)
                    if (state.error != null) Text("Playback failed. Check your connection and choose the song again.",
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp).testTag("tv.player.error"))
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { playback?.skipToPrevious() }, enabled = playback != null && (state.canGoPrevious || state.canRestart),
                            modifier = Modifier.testTag("tv.player.previous")) { Icon(DulcetIcons.SkipPrevious, "Previous") }
                        IconButton(onClick = { playback?.togglePlayPause() }, enabled = playback != null,
                            modifier = Modifier.size(64.dp).focusRequester(playFocus).testTag("tv.player.playpause")) {
                            Icon(if (state.playWhenReady) DulcetIcons.Pause else DulcetIcons.Play,
                                if (state.playWhenReady) "Pause" else "Play", Modifier.size(36.dp))
                        }
                        IconButton(onClick = { playback?.next() }, enabled = playback != null && state.canGoNext,
                            modifier = Modifier.testTag("tv.player.next")) { Icon(DulcetIcons.SkipNext, "Next") }
                    }
                    Spacer(Modifier.height(28.dp))
                    if (state.queue.size > 1) {
                        Text("Up Next", style = MaterialTheme.typography.titleMedium)
                        LazyColumn(Modifier.fillMaxWidth().height(180.dp).testTag("tv.player.upnext")) {
                            itemsIndexed(state.queue, key = { _, entry -> entry.queueEntryId }) { position, entry ->
                                ListItem(
                                    selected = position == state.currentIndex,
                                    onClick = { playback?.jumpTo(entry.queueEntryId) },
                                    headlineContent = { Text(entry.track.title.ifBlank { "Loading…" }, maxLines = 1) },
                                    supportingContent = entry.track.artist?.let { { Text(it, maxLines = 1) } },
                                    modifier = Modifier.testTag("tv.player.upnext.$position"),
                                )
                            }
                        }
                    }
                }
            }
            // Along the player's own bottom edge, clear of the transport row above it.
            SkipNoticeRegion(state.skipNotice, visible = true, MaterialTheme.colorScheme.inverseSurface,
                MaterialTheme.colorScheme.inverseOnSurface, MaterialTheme.typography.titleMedium,
                Modifier.fillMaxSize().padding(bottom = 24.dp), maxCardWidth = 720.dp)
        }
    }
}

@Composable
private fun TvProgress(state: AndroidPlaybackState) {
    val duration = state.durationMilliseconds?.takeIf { it > 0 }
    val fraction = if (duration == null) 0f else (state.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(clock(state.positionMilliseconds), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("tv.player.position"))
        Spacer(Modifier.weight(1f))
        Text(duration?.let(::clock).orEmpty(), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TvArtwork(account: SearchAccount?, key: String?, size: Int) {
    val image = account?.let { rememberArtwork(it, key, size * 2) }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A2A2E)),
        contentAlignment = Alignment.Center) {
        if (image != null) Image(image, "Cover art", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(DulcetIcons.Album, null, Modifier.size((size / 3).dp), tint = Color(0xFF8E8E93))
    }
}

private fun clock(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
