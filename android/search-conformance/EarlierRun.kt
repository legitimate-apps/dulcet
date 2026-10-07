package com.legitimateapps.dulcet.search.conformance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidServerContact
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount

/**
 * What an earlier run of the app leaves on this device for [accountId], written as that run's process
 * would have left the rows: its queue of [queue], the first song current and nothing playing, and
 * [unsentPlays] finished plays it could not deliver, waiting in the outbox. A relaunch then starts
 * from them. The app's database is created first by the production controller, with the server shut
 * off, so the schema is the one the app ships; call this on the main thread, before the relaunch.
 */
fun leaveEarlierRun(context: Context, accountId: String, serverUrl: String, queue: List<String>, unsentPlays: List<String>) {
    AndroidPlaybackController(context, PlaybackEndpointAccount(accountId, serverUrl, "earlier-run", "earlier-run", true),
        null, NeverContact).close()
    val now = System.currentTimeMillis()
    SQLiteDatabase.openDatabase(context.getDatabasePath(DATABASE).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.beginTransaction()
        try {
            db.insertOrThrow("queue_state", null, ContentValues().apply {
                put("server_id", accountId); put("current_position", 0); put("repeat_mode", "off"); put("shuffle_enabled", 0)
            })
            queue.forEachIndexed { index, rawId ->
                db.insertOrThrow("queue_entry", null, ContentValues().apply {
                    put("server_id", accountId); put("queue_entry_id", "earlier-entry-$index"); put("raw_id", rawId)
                    put("source_context_kind", "search"); putNull("source_context_raw_id")
                    put("source_context_display_name", "Search"); put("added_by", "play_now")
                    put("original_position", index); put("playback_position", index)
                })
            }
            db.insertOrThrow("active_queue", null, ContentValues().apply { put("singleton_id", 1); put("server_id", accountId) })
            unsentPlays.forEachIndexed { index, rawId ->
                db.insertOrThrow("scrobble_outbox", null, ContentValues().apply {
                    put("server_id", accountId); put("raw_id", rawId)
                    put("session_start_wall_clock", now - 600_000 + index); put("created_at_wall_clock", now - 300_000 + index)
                    put("attempt_count", 0)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

/** The app's one database, as the production driver names it. */
private const val DATABASE = "dulcet.db"

private object NeverContact : AndroidServerContact {
    override fun isOpen(): Boolean = false
    override suspend fun awaitOpen() = kotlinx.coroutines.awaitCancellation()
}
