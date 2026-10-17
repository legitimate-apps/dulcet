package com.legitimateapps.dulcet.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidDroppedAdditions
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.shared.R

/**
 * Says so, briefly, when the playback controller refuses a queue edit: a row that would not move or
 * a track that would not queue otherwise looks like something that silently did not happen. The
 * Apple shells say the same words (spec §14.1). A toast is read out by TalkBack.
 */
public fun queueEditRefused(context: Context) {
    Toast.makeText(context, R.string.queue_edit_refused, Toast.LENGTH_SHORT).show()
}

/**
 * Says once that Play Next and Add to Queue made while a queue loaded were dropped because that queue
 * never started (spec §14.1), then tells [playback] it has been said, so no other surface -- and no
 * return to this one -- says it again. Apple has no such moment: its queue is in place at once.
 */
@Composable
public fun DroppedAdditionsNotice(playback: AndroidPlaybackController?, dropped: AndroidDroppedAdditions?,
    confirmation: com.legitimateapps.dulcet.core.AndroidQueueConfirmation? = null) {
    val context = LocalContext.current
    QueueConfirmationNotice(confirmation) { sequence -> playback?.dismissQueueConfirmation(sequence) == true }
    LaunchedEffect(playback, dropped?.sequence) {
        val notice = dropped ?: return@LaunchedEffect
        val controller = playback ?: return@LaunchedEffect
        // Claim the notice first: two started surfaces (the TV library under its Now Playing) each
        // run this effect for the same publication, and only the one that clears it speaks.
        if (!controller.dismissDroppedAdditions(notice.sequence)) return@LaunchedEffect
        Toast.makeText(context, context.resources.getQuantityString(R.plurals.queue_additions_dropped,
            notice.trackCount, notice.trackCount), Toast.LENGTH_LONG).show()
    }
}

/** Render only an applied, unclaimed edit; playback itself is the feedback for an empty queue. */
@Composable
public fun QueueConfirmationNotice(confirmation: com.legitimateapps.dulcet.core.AndroidQueueConfirmation?,
    claim: (Long) -> Boolean) {
    val context = LocalContext.current
    LaunchedEffect(confirmation?.sequence) {
        val notice = confirmation ?: return@LaunchedEffect
        if (!claim(notice.sequence)) return@LaunchedEffect
        Toast.makeText(context, if (notice.insertion == com.legitimateapps.dulcet.core.AndroidQueueInsertion.PlayNext)
            R.string.queue_confirm_next else R.string.queue_confirm_added, Toast.LENGTH_SHORT).show()
    }
}
