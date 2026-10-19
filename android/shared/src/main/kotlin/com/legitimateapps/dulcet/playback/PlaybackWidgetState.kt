package com.legitimateapps.dulcet.playback

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Only display metadata, shared inside this application's sandbox. */
internal object PlaybackWidgetState {
    fun publish(context: Context, title: String, subtitle: String, playing: Boolean) {
        context.getSharedPreferences("dulcet.widget", Context.MODE_PRIVATE).edit()
            .putString("title", title.ifBlank { "Dulcet" }).putString("subtitle", subtitle)
            .putBoolean("playing", playing).apply()
        val component = ComponentName(context.packageName, "com.legitimateapps.dulcet.DulcetPlaybackWidget")
        val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(component)
        if (ids.isNotEmpty()) context.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .setComponent(component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
    }
}
