package com.legitimateapps.dulcet.emulator

import android.os.SystemClock

/**
 * The server's own record of a rating (CONF-84), read by the probe and never through the app: the
 * `userRating` `getSong` answers, 0 when the song has none.
 */
fun DisposableServerProbe.userRating(rawId: String): Int =
    call("getSong", mapOf("id" to rawId)).getJSONObject("song").optInt("userRating", 0)

/**
 * Removes any rating the song holds, so a proof starts and ends with the fixture unrated whatever an
 * earlier run on the same server left. `setRating` with 0 is how Subsonic removes one.
 */
fun DisposableServerProbe.clearRating(rawId: String) {
    call("setRating", mapOf("id" to rawId, "rating" to "0"))
    check(userRating(rawId) == 0) { "The probe could not clear the rating of $rawId" }
}

/** A fixture song about thirty seconds long, rated by the rating proofs and cleared after them. */
const val RATED_TITLE = "Thirty One Seconds"

/**
 * Waits until [rawId] is the current queue entry. A timeout names what the controller held instead
 * — its phase, error, title and queue — because "timed out" alone cannot tell a play that never
 * arrived from one the controller refused or failed.
 */
fun PlaybackObserver.awaitCurrent(rawId: String, timeoutMillis: Long = 60_000) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (true) {
        val state = state()
        if (state.queue.getOrNull(state.currentIndex ?: -1)?.track?.rawId == rawId) return
        check(SystemClock.elapsedRealtime() < deadline) {
            "Timed out waiting for $rawId to be current: phase=${state.phase} error=${state.error} " +
                "title=${state.title} currentIndex=${state.currentIndex} queue=${state.queue.map { it.track.rawId }}"
        }
        Thread.sleep(100)
    }
}
