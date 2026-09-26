package com.legitimateapps.dulcet.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/**
 * Reaches [PlaybackService] through its local binding, releases its controller for an account
 * that is signing out (spec §14.7), and unbinds. True once the service answered; false when it
 * could not be reached, so the caller must not remove the account yet.
 *
 * The service is this app's own and runs in-process, so it answers at once; the bound only keeps
 * a sign-out from waiting forever on a service that failed to start. Call on the main thread.
 */
public suspend fun releasePlaybackForSignOut(context: Context): Boolean = withTimeoutOrNull(RELEASE_TIMEOUT) {
    suspendCancellableCoroutine { continuation ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = (binder as? PlaybackService.LocalBinder)?.service
                service?.releasePlayback()
                runCatching { context.unbindService(this) }
                if (continuation.isActive) continuation.resume(service != null)
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = context.bindService(Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.LOCAL_BIND), connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            runCatching { context.unbindService(connection) }
            continuation.resume(false)
        } else {
            continuation.invokeOnCancellation { runCatching { context.unbindService(connection) } }
        }
    }
} ?: false

private val RELEASE_TIMEOUT = 10.seconds
