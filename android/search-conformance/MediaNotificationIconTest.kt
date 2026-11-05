package com.legitimateapps.dulcet.search.conformance

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Reads the actual default provider's icon after app/shared/Media3 resource merging. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaNotificationIconTest {
    @Test fun defaultProviderResolvesDulcetsMonochromeMark() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
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
        try {
            val notification = DefaultMediaNotificationProvider(context).createNotification(session, ImmutableList.of(), actions) {}.notification
            val resolved = TypedValue()
            context.resources.getValue(notification.smallIcon.resId, resolved, true)
            assertEquals("ic_dulcet_notification", context.resources.getResourceEntryName(resolved.resourceId))
        } finally { session.release(); player.release() }
    }
}
