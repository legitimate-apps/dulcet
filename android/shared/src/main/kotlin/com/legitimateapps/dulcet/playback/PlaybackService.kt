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

    /** The account [playback] was built for; its credentials are the controller's for its life. */
    private var playbackAccountId: String? = null

    /**
     * The controller for the account saved NOW, created once one exists. The service may have been
     * created before the first connection (a media button, a bind from the connect screen), so
     * absence is re-checked on every local bind instead of being fixed at creation.
     *
     * A controller built for another account is released first, whatever replaced that account: a
     * sign-out releases it itself (spec §14.7), but an account can also be saved without one — on
     * the connect form shown when the saved record cannot be read, which is the only way to change
     * account on TV — and the new account must never be handed the old one's controller, which
     * carries its credentials, queue and server. The saved account's id is read without decrypting
     * its record, so this costs nothing on the usual path.
     */
    fun ensurePlayback(): AndroidPlaybackController? {
        val store = AndroidAccountCredentialStore(this)
        val activeId = store.activeAccountId()
        playback?.let { existing ->
            if (activeId != null && activeId == playbackAccountId) return existing
            releasePlayback()
        }
        val account = try { store.load() }
        catch (_: CredentialStoreException) {
            unavailableReason = "Saved credentials are unavailable. Reconnect your account."
            return null
        } ?: return null
        val controller = AndroidPlaybackController(this, PlaybackEndpointAccount(
            account.id, account.serverUrl, account.username, account.password, account.allowLocalHttp))
        playback = controller
        playbackAccountId = account.id
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

    /**
     * Ends playback for an account that is signing out (spec §14.7): stops the session, removes
     * and releases the media session, and closes the controller, which held that account's
     * credentials. The next [ensurePlayback] builds a controller from whatever account is then
     * saved, so a later account never inherits this one's controller. A no-op without one.
     */
    fun releasePlayback() {
        val controller = playback ?: return
        controller.stop()
        session?.let { removeSession(it); it.release() }
        controller.close()
        session = null
        playback = null
        playbackAccountId = null
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
