package com.legitimateapps.dulcet.search

import android.content.Context
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.core.AndroidLibrarySearchPublication
import com.legitimateapps.dulcet.library.toReaderAccount

/**
 * Production search: a `LibrarySearchSession` of the account's one reader (spec §16.15), so the
 * device half searches what this device has seen, the server half is `search3`, and every
 * publication carries the core's scope — `serverAndDevice`, `deviceWhileServerPending`,
 * `deviceOffline` with the seen-cache's counts, or `deviceServerFailed` with its kind. The reader is
 * shared with the library screens, so search follows the same reachability and favourites.
 */
public class AndroidLibrarySearchSource(context: Context, account: SearchAccount) : SearchSource {
    private val reader = AndroidLibraryReader.forAccount(context, account.toReaderAccount())

    override fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle {
        val search = reader.openSearch(listener)
        return object : SearchSourceHandle {
            override fun updateQuery(text: String) = search.updateQuery(text)
            override fun refresh() = search.refresh()
            override fun close() = search.close()
        }
    }
}
