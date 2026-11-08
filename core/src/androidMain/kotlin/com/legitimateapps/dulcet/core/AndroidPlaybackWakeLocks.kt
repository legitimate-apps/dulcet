package com.legitimateapps.dulcet.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.media3.common.C

/**
 * The last API level on which the Wi-Fi lock Media3 takes stays effective with the screen off.
 * From 34, the platform turns the same request into a low-latency lock that applies only with the
 * screen on and the app in front, trading throughput and roaming for latency a music stream has
 * no use for.
 */
private const val LAST_SCREEN_OFF_WIFI_LOCK_SDK = Build.VERSION_CODES.TIRAMISU

/**
 * Media3 defaults to [C.WAKE_MODE_LOCAL], which holds the CPU while a player plays. A stream adds
 * the Wi-Fi lock where it can do anything for playback with the screen off; a downloaded file reads
 * no network, and a player reused for it gives the lock up.
 */
internal fun androidPlaybackWakeMode(plan: PlaybackPlan, sdk: Int): Int =
    if (plan is RemotePlaybackWirePlan && sdk <= LAST_SCREEN_OFF_WIFI_LOCK_SDK) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL

/**
 * Holds the CPU while the next track starts. A foreground service does not, and Media3 releases
 * its locks when a player ends, so without this nothing keeps the device awake from the end of one
 * track until the next one's song read and resolution reach a playing player.
 *
 * The timeout outlasts both reads at their thirty-second limit, and bounds any wait that has no
 * reply coming. Each hold replaces the last, so a replaced start ending does not release
 * the newer one's lock.
 */
internal class AndroidPlaybackStartWakeLock(context: Context) {
    private val lock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dulcet:PlaybackStart")
        .apply { setReferenceCounted(false) }
    private var current = 0L

    @Synchronized fun hold(): Long {
        lock.acquire(TIMEOUT_MILLISECONDS)
        return ++current
    }

    @Synchronized fun release(hold: Long) {
        if (hold == current && lock.isHeld) lock.release()
    }

    private companion object {
        const val TIMEOUT_MILLISECONDS = 90_000L
    }
}
