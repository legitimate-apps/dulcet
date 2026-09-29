package com.legitimateapps.dulcet.library

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.legitimateapps.dulcet.core.AndroidLyricsPublication
import com.legitimateapps.dulcet.core.AndroidLyricsTrack
import com.legitimateapps.dulcet.core.AndroidPlaybackState

/*
 * What a lyrics panel needs from Compose on either shell (spec §18.4): the current track's lyrics,
 * read when the track changes and again when the library comes back online, and media time between
 * the controller's samples.
 */

/** The lyrics of [state]'s current track, and the model to retry them with. */
public class NowPlayingLyrics(public val model: SessionLyrics, public val publication: AndroidLyricsPublication?)

@Composable
public fun rememberNowPlayingLyrics(session: LibrarySession, state: AndroidPlaybackState): NowPlayingLyrics {
    val lyrics = remember(session) { session.lyrics(deviceLanguages()) }
    val entry = state.queue.getOrNull(state.currentIndex ?: -1)?.track
    val track = entry?.let { AndroidLyricsTrack(it.rawId, it.artist, it.title.ifBlank { null }) }
    LaunchedEffect(lyrics, track) { lyrics.show(track) }
    DisposableEffect(lyrics) { onDispose { lyrics.show(null) } }
    val connection by session.connection.collectAsState()
    val online = connection is LibraryConnectionState.Online
    // Back online after the panel opened offline: what it shows is read again.
    LaunchedEffect(lyrics, online) { if (online) lyrics.refreshIfNotLive() }
    val publication by lyrics.state.collectAsState()
    return NowPlayingLyrics(lyrics, publication)
}

/**
 * Media time now: the controller's last sample, advanced on the monotonic clock while playback is
 * progressing, re-drawn every [TICK_MILLIS]. The ticker is a main-thread message, not a frame loop,
 * and stops whenever playback does.
 */
@Composable
public fun rememberMediaTime(state: AndroidPlaybackState): Long {
    val sampledAt = remember(state.positionMilliseconds, state.phase, state.queueEntryId) { SystemClock.elapsedRealtime() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val progressing = state.phase == PHASE_PROGRESSING
    DisposableEffect(progressing) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                now = SystemClock.elapsedRealtime()
                handler.postDelayed(this, TICK_MILLIS)
            }
        }
        if (progressing) handler.postDelayed(tick, TICK_MILLIS)
        onDispose { handler.removeCallbacks(tick) }
    }
    return interpolatedPosition(state.positionMilliseconds, sampledAt, maxOf(now, sampledAt), progressing, state.durationMilliseconds)
}

private const val TICK_MILLIS = 100L
