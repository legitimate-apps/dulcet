package com.legitimateapps.dulcet

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.graphics.BitmapFactory
import java.io.File
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
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != TOGGLE) return
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        // onReceive receives a ReceiverRestrictedContext, which cannot bind services.
        // The async controller connection must outlive that callback on the app context.
        val appContext = context.applicationContext
        val future = MediaController.Builder(appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))).buildAsync()
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
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DulcetPlaybackWidget::class.java))
            val hasSession = state.getBoolean("hasSession", false)
            val reconnect = state.getBoolean("needsReconnect", false)
            val playing = state.getBoolean("playing", false)
            val title = if (hasSession) state.getString("title", null)?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name) else context.getString(R.string.app_name)
            val subtitle = when {
                !hasSession -> context.getString(R.string.widget_open_library)
                reconnect -> context.getString(R.string.widget_reconnect)
                else -> state.getString("subtitle", "").orEmpty()
            }
            val toggleLabel = context.getString(if (playing) R.string.widget_pause else R.string.widget_play)
            val art = if (hasSession) runCatching { BitmapFactory.decodeFile(File(context.filesDir, "dulcet-widget-art.jpg").path) }.getOrNull() else null
            val openIntent = if (hasSession) PlaybackIntents.showNowPlaying(context)
                else context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java)
            val open = PendingIntent.getActivity(context, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val toggle = PendingIntent.getBroadcast(context, 0,
                Intent(context, DulcetPlaybackWidget::class.java).setAction(TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.dulcet_widget)
                views.setTextViewText(R.id.widget_title, title)
                views.setTextViewText(R.id.widget_subtitle, subtitle)
                views.setImageViewResource(R.id.widget_toggle, if (playing) R.drawable.widget_pause else R.drawable.widget_play)
                views.setContentDescription(R.id.widget_toggle, toggleLabel)
                views.setViewVisibility(R.id.widget_toggle, if (hasSession) View.VISIBLE else View.GONE)
                views.setBoolean(R.id.widget_toggle, "setEnabled", !reconnect)
                if (art != null) views.setImageViewBitmap(R.id.widget_artwork, art)
                else views.setImageViewResource(R.id.widget_artwork, R.drawable.widget_artwork)
                // A two-cell host keeps the text and48dp control; larger hosts also show the cover.
                val narrow = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) < 240
                views.setViewVisibility(R.id.widget_artwork, if (narrow) View.GONE else View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_root, open)
                views.setOnClickPendingIntent(R.id.widget_title, open)
                views.setOnClickPendingIntent(R.id.widget_toggle, toggle)
                manager.updateAppWidget(id, views)
            }
            art?.recycle()
        }
    }
}
