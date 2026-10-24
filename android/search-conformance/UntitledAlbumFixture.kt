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
 * The seen-cache can retain absent titles, although a fresh REST read requires a nonblank title.
 * Seed ordinary album membership through the real reader, then remove only its display metadata.
 * The online server's untitled response cannot replace it, so the cached tracks stay streamable.
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
