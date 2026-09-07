package com.legitimateapps.dulcet.playback

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
        val account = try { AndroidAccountCredentialStore(this).load() }
        catch (_: CredentialStoreException) {
            unavailableReason = "Saved credentials are unavailable. Reconnect your account."
            return
        } ?: return
        val controller = AndroidPlaybackController(this, PlaybackEndpointAccount(
            account.id, account.serverUrl, account.username, account.password, account.allowLocalHttp))
        playback = controller
        session = MediaSession.Builder(this, controller.sessionPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo):
                    ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(
                    if (controller.packageName == packageName || controller.isTrusted)
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                            .setAvailablePlayerCommands(session.player.availableCommands).build()
                    else MediaSession.ConnectionResult.reject())
            }).build()
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == LOCAL_BIND) LocalBinder() else super.onBind(intent)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        playback?.close()
        session = null
        playback = null
        super.onDestroy()
    }

    companion object { const val LOCAL_BIND = "com.legitimateapps.dulcet.playback.BIND" }
}
