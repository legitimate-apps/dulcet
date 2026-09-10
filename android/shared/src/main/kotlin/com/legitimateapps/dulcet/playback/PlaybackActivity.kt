package com.legitimateapps.dulcet.playback

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import kotlinx.coroutines.*

/** Native focusable controls work with touch, keyboard and TV D-pad without a second player. */
class PlaybackActivity : Activity() {
    private var playback: AndroidPlaybackController? = null
    private var bound = false
    private var submitted = false
    private var observation: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var title: TextView
    private lateinit var progress: TextView
    private lateinit var status: TextView
    private lateinit var play: Button
    private lateinit var pause: Button
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as PlaybackService.LocalBinder).service
            val controller = service.playback
            playback = controller
            if (controller == null) { status.text = service.unavailableReason; return }
            play.isEnabled = true; pause.isEnabled = true
            pause.requestFocus()
            observation = scope.launch { controller.state.collect(::render) }
            if (!submitted) {
                submitted = true
                controller.playSong(intent.getStringExtra(PROVIDER).orEmpty(),
                    intent.getStringExtra(SONG).orEmpty(), intent.getStringExtra(TITLE).orEmpty())
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            playback = null; observation?.cancel()
            play.isEnabled = false; pause.isEnabled = false
            status.text = "Playback service disconnected."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        submitted = savedInstanceState?.getBoolean("submitted") ?: false
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (32 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        title = TextView(this).apply { textSize = 24f; text = intent.getStringExtra(TITLE) }
        progress = TextView(this)
        status = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        play = Button(this).apply { text = "Play"; isEnabled = false; setOnClickListener { playback?.play() } }
        pause = Button(this).apply { text = "Pause"; isEnabled = false; setOnClickListener { playback?.pause() } }
        column.addView(title); column.addView(progress); column.addView(status)
        column.addView(play); column.addView(pause)
        column.addView(Button(this).apply { text = "Stop"; setOnClickListener { playback?.stop() } })
        setContentView(column)
        play.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND),
            connection, Context.BIND_AUTO_CREATE)
        if (!bound) status.text = "Playback service unavailable."
    }

    override fun onStop() {
        observation?.cancel(); observation = null
        if (bound) unbindService(connection)
        bound = false; playback = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("submitted", submitted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> playback?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> playback?.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> playback?.let {
                if (it.sessionPlayer.playWhenReady) it.pause() else it.play()
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> playback?.stop()
            KeyEvent.KEYCODE_MEDIA_NEXT -> playback?.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> playback?.previous()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun render(state: AndroidPlaybackState) {
        title.text = state.title
        progress.text = "${state.positionMilliseconds / 1000} / ${state.durationMilliseconds?.div(1000) ?: "—"} seconds"
        status.text = if (state.error != null) "Playback failed. Check your connection and select the song again." else when (state.phase) {
            "Created", "Preparing", "Buffering" -> "Loading…"
            "Progressing" -> "Playing"
            "Ready", "Paused" -> "Paused"
            else -> "Stopped"
        }
    }

    companion object {
        const val PROVIDER = "playback.provider"
        const val SONG = "playback.song"
        const val TITLE = "playback.title"
        fun intent(context: Context, provider: String, rawId: String, title: String): Intent =
            Intent(context, PlaybackActivity::class.java).putExtra(PROVIDER, provider)
                .putExtra(SONG, rawId).putExtra(TITLE, title)
    }
}
