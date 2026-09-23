package com.legitimateapps.dulcet.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.legitimateapps.dulcet.core.AndroidPlaybackController

/**
 * The two playback intents every Dulcet shell answers. Each app declares its own Now Playing
 * activity for them, so the phone renders Material 3 and TV renders its lean-back surface without
 * the shared module depending on either design system. Extras carry opaque identity only; the
 * service resolves the song with its own saved account, never with credentials from an intent.
 */
public object PlaybackIntents {
    public const val ACTION_PLAY_TRACK: String = "com.legitimateapps.dulcet.action.PLAY_TRACK"
    public const val ACTION_SHOW_NOW_PLAYING: String = "com.legitimateapps.dulcet.action.SHOW_NOW_PLAYING"
    public const val PROVIDER: String = "playback.provider"
    public const val SONG: String = "playback.song"
    public const val TITLE: String = "playback.title"

    public fun playTrack(context: Context, provider: String, rawId: String, title: String): Intent =
        Intent(ACTION_PLAY_TRACK).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(PROVIDER, provider).putExtra(SONG, rawId).putExtra(TITLE, title)

    public fun showNowPlaying(context: Context): Intent =
        Intent(ACTION_SHOW_NOW_PLAYING).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** A play request read from an intent, or null when the intent is not one. */
    public fun playRequest(intent: Intent?): PlayRequest? {
        if (intent?.action != ACTION_PLAY_TRACK) return null
        val provider = intent.getStringExtra(PROVIDER).orEmpty()
        val song = intent.getStringExtra(SONG).orEmpty()
        if (provider.isBlank() || song.isBlank()) return null
        return PlayRequest(provider, song, intent.getStringExtra(TITLE).orEmpty())
    }
}

public data class PlayRequest(val provider: String, val rawId: String, val title: String)

/** Shared production entry; the Android/TV search detail surfaces supply only opaque identity. */
@Composable
fun PlaybackEntry(provider: String, rawId: String, title: String) {
    val context = LocalContext.current
    Box(Modifier.testTag("playback.open").clickable(role = Role.Button) {
        context.startActivity(PlaybackIntents.playTrack(context, provider, rawId, title))
    }.padding(16.dp)) { BasicText("Play") }
}

/** The service's controller while this composition is started; null before binding or without an account. */
@Composable
public fun rememberPlaybackController(): AndroidPlaybackController? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var controller by remember { mutableStateOf<AndroidPlaybackController?>(null) }
    DisposableEffect(context, lifecycle) {
        var bound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                controller = (binder as? PlaybackService.LocalBinder)?.service?.ensurePlayback()
            }
            override fun onServiceDisconnected(name: ComponentName?) { controller = null }
        }
        fun bind() {
            if (bound) return
            bound = context.bindService(Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.LOCAL_BIND), connection, Context.BIND_AUTO_CREATE)
        }
        fun unbind() {
            if (!bound) return
            context.unbindService(connection); bound = false; controller = null
        }
        // Bound only while started: a stopped screen must not be what keeps the service alive.
        // Media3's foreground ownership keeps playback running once the activity goes away.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> bind()
                Lifecycle.Event.ON_STOP -> unbind()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); unbind() }
    }
    return controller
}
