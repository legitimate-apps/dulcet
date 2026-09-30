package com.legitimateapps.dulcet.ui

import android.content.Context
import android.widget.Toast
import com.legitimateapps.dulcet.shared.R

/**
 * Says so, briefly, when the playback controller refuses a queue edit: a row that would not move or
 * a track that would not queue otherwise looks like something that silently did not happen. The
 * Apple shells say the same words (spec §14.1). A toast is read out by TalkBack.
 */
public fun queueEditRefused(context: Context) {
    Toast.makeText(context, R.string.queue_edit_refused, Toast.LENGTH_SHORT).show()
}
