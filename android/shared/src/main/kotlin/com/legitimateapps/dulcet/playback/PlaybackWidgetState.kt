package com.legitimateapps.dulcet.playback

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException

/** Only display metadata, shared inside this application's sandbox. */
internal object PlaybackWidgetState {
    fun publish(context: Context, title: String, subtitle: String, playing: Boolean, hasSession: Boolean = false, needsReconnect: Boolean = false) {
        context.getSharedPreferences("dulcet.widget", Context.MODE_PRIVATE).edit()
            .putString("title", title.ifBlank { "Dulcet" }).putString("subtitle", subtitle)
            .putBoolean("playing", playing).putBoolean("hasSession", hasSession).putBoolean("needsReconnect", needsReconnect).apply()
        if (!hasSession) File(context.filesDir, "dulcet-widget-art.jpg").delete()
        update(context)
    }

    /** Metadata already validated by the media session; keep only a small private widget cover. */
    fun artwork(context: Context, bytes: ByteArray?) {
        val file = File(context.filesDir, "dulcet-widget-art.jpg")
        val bitmap = bytes?.let {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 320) options.inSampleSize *= 2
            BitmapFactory.decodeByteArray(it, 0, it.size, options)
        }
        if (bitmap == null) file.delete() else {
            val scale = minOf(1f, 160f / maxOf(bitmap.width, bitmap.height))
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            try {
                file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            } catch (_: IOException) {
                // Cache storage failure must not interrupt the authenticated playback session.
                file.delete()
            } finally {
                if (small !== bitmap) small.recycle()
                bitmap.recycle()
            }
        }
        update(context)
    }

    private fun update(context: Context) {
        val component = ComponentName(context.packageName, "com.legitimateapps.dulcet.DulcetPlaybackWidget")
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(component)
        if (ids.isNotEmpty()) context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .setComponent(component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
    }
}
