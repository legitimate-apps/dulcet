package com.legitimateapps.dulcet.search.conformance

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryQuery
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.toReaderAccount
import com.legitimateapps.dulcet.search.SearchAccount
import java.util.concurrent.TimeUnit
import org.robolectric.Shadows.shadowOf

/**
 * Reproduce an album already seen with absent titles as well as its untitled server response.
 * Seed ordinary album membership through the real reader, then remove only its display metadata.
 * Both cached and refreshed tracks must remain streamable.
 */
fun seedUntitledAlbum(context: Context, account: SearchAccount, server: LoopbackLibraryServer, albumId: String) {
    server.albumSongTitles = listOf("First", "Second", "Third")
    val reader = AndroidLibraryReader.forAccount(context, account.toReaderAccount(), true)
    var publication: AndroidLibraryPublication? = null
    val window = reader.openWindow(AndroidLibraryQuery.Album(albumId)) { publication = it }
    try {
        awaitFixture { publication?.let { it.itemsState == AndroidLibraryItemsState.Present && it.items.size == 3 } == true }
    } finally {
        window.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        awaitFixture { closed }
    }
    SQLiteDatabase.openDatabase(context.getDatabasePath("dulcet.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        listOf("", "   ", null).forEachIndexed { index, title ->
            db.execSQL("UPDATE cache_track SET title = ?, normalized_title = ?, metadata_missing = ? WHERE server_id = ? AND raw_id = ?",
                arrayOf<Any?>(title, title, if (title == null) 1 else 0, account.providerInstanceId, "$albumId-song-${index + 1}"))
        }
    }
    server.albumSongTitles = listOf("", "   ", null)
}

private fun awaitFixture(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out preparing the untitled album fixture" }
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }
}

/** Valid one-second mono PCM WAV, consumed by the production validating Media3 data source. */
fun untitledAlbumAudio(): ByteArray = java.nio.ByteBuffer.allocate(88_244).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray()); putInt(88_236); put("WAVEfmt ".toByteArray())
    putInt(16); putShort(1); putShort(1); putInt(44_100); putInt(88_200); putShort(2); putShort(16)
    put("data".toByteArray()); putInt(88_200)
}.array()
