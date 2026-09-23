package com.legitimateapps.dulcet.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.CredentialStoreException
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount

/** One owner for playback across both native shells, activity recreation and backgrounding. */
class PlaybackService : MediaSessionService() {
    var playback: AndroidPlaybackController? = null
        private set
    private var session: MediaSession? = null
    var unavailableReason: String = "Connect an account before playing."
        private set
    inner class LocalBinder : Binder() { val service: PlaybackService get() = this@PlaybackService }

    override fun onCreate() {
        super.onCreate()
        ensurePlayback()
    }

    /**
     * Creates the controller once an account exists. The service may have been created before the
     * first connection (a media button, a bind from the connect screen), so absence is re-checked
     * on every local bind instead of being fixed at creation.
     */
    fun ensurePlayback(): AndroidPlaybackController? {
        playback?.let { return it }
        val account = try { AndroidAccountCredentialStore(this).load() }
        catch (_: CredentialStoreException) {
            unavailableReason = "Saved credentials are unavailable. Reconnect your account."
            return null
        } ?: return null
        val controller = AndroidPlaybackController(this, PlaybackEndpointAccount(
            account.id, account.serverUrl, account.username, account.password, account.allowLocalHttp))
        playback = controller
        val builder = MediaSession.Builder(this, controller.sessionPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo):
                    ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(
                    if (controller.packageName == packageName || controller.isTrusted)
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                            .setAvailablePlayerCommands(session.player.availableCommands).build()
                    else MediaSession.ConnectionResult.reject())
            })
        // Tapping the notification or lock-screen card opens this app's own Now Playing.
        val show = PlaybackIntents.showNowPlaying(this)
        if (packageManager.resolveActivity(show, 0) != null)
            builder.setSessionActivity(PendingIntent.getActivity(this, 0, show,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        session = builder.build().also { addSession(it) }
        return controller
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == LOCAL_BIND) LocalBinder() else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        ensurePlayback()
        return session
    }

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        playback?.close()
        session = null
        playback = null
        super.onDestroy()
    }

    companion object { const val LOCAL_BIND = "com.legitimateapps.dulcet.playback.BIND" }
}
