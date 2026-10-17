package com.legitimateapps.dulcet

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.PlaybackService
import java.util.concurrent.Executor

/** User-initiated controls connect to the existing authenticated media session. */
class DulcetPlaybackWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != TOGGLE) return
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) { MediaController.releaseFuture(future); pending.finish() } }
        main.postDelayed({ finish() }, 8000)
        future.addListener({
            runCatching {
                val controller = future.get()
                if (controller.playWhenReady) controller.pause() else controller.play()
            }
            // Media3 takes foreground ownership asynchronously as playback starts.
            main.postDelayed({ finish() }, 2000)
        }, Executor { main.post(it) })
    }
    companion object {
        const val TOGGLE = "com.legitimateapps.dulcet.widget.TOGGLE"
        fun update(context: Context) {
            val state = context.getSharedPreferences("dulcet.widget", Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.dulcet_widget)
            views.setTextViewText(R.id.widget_title, state.getString("title", "Dulcet"))
            views.setTextViewText(R.id.widget_subtitle, state.getString("subtitle", "Open your music library"))
            views.setTextViewText(R.id.widget_toggle, if (state.getBoolean("playing", false)) "Pause" else "Play")
            views.setOnClickPendingIntent(R.id.widget_toggle, PendingIntent.getBroadcast(context, 0,
                Intent(context, DulcetPlaybackWidget::class.java).setAction(TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context, 1,
                PlaybackIntents.showNowPlaying(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, DulcetPlaybackWidget::class.java), views)
        }
    }
}
