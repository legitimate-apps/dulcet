package com.legitimateapps.dulcet.search.conformance

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real queue, Media3 session and default notification provider must share the localized title. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun assertUntitledPlayback(context: Context, controller: AndroidPlaybackController, albumId: String, shuffle: Boolean = false, expectedTitle: String? = null) {
    val title = expectedTitle ?: context.getString(com.legitimateapps.dulcet.core.R.string.playback_untitled)
    val state = controller.state.value
    assertEquals((1..3).map { "$albumId-song-$it" }, state.queue.map { it.track.rawId }.let { if (shuffle) it.sorted() else it })
    assertEquals(shuffle, state.shuffle)
    assertEquals(List(3) { title }, state.queue.map { it.track.title })
    assertEquals(title, state.title)
    assertTrue(state.playWhenReady)
    assertTrue(controller.sessionPlayer.playWhenReady)
    assertTrue(state.validatedBytesConsumed > 0, "The production player must consume validated audio bytes")
    val session = MediaSession.Builder(context, controller.sessionPlayer).build()
    try {
        assertEquals(title, session.player.mediaMetadata.title.toString())
        // Actions are not activated here; only the OS PendingIntent boundary is a stand-in.
        val pending = PendingIntent.getBroadcast(context, 0, Intent("fixture.media"), PendingIntent.FLAG_IMMUTABLE)
        val actions = object : MediaNotification.ActionFactory {
            override fun createMediaAction(s: MediaSession, icon: IconCompat, text: CharSequence, command: Int) =
                NotificationCompat.Action.Builder(icon, text, pending).build()
            override fun createCustomAction(s: MediaSession, icon: IconCompat, text: CharSequence, command: String, extras: Bundle) =
                NotificationCompat.Action.Builder(icon, text, pending).build()
            override fun createCustomActionFromCustomCommandButton(s: MediaSession, button: CommandButton) =
                NotificationCompat.Action.Builder(0, button.displayName, pending).build()
            override fun createMediaActionPendingIntent(s: MediaSession, command: Int) = pending
        }
        val notification = DefaultMediaNotificationProvider(context).createNotification(session, ImmutableList.of(), actions) {}
        assertEquals(title, notification.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    } finally { session.release() }
}
