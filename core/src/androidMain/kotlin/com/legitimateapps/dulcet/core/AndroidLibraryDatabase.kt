package com.legitimateapps.dulcet.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android composition root; SQLDelight and its driver stay behind the core facade. */
public class AndroidLibraryDatabase(context: Context) {
    private val context = context.applicationContext

    public suspend fun search(providerInstanceId: String, query: String): List<SearchResultItem> =
        withContext(Dispatchers.IO) {
            val store = DulcetDriverFactory(context).openDulcetDatabase()
            try { LocalLibrarySearch(store).search(providerInstanceId, query) }
            finally { store.close() }
        }

    public suspend fun synchronize(request: LibrarySyncRequest): LibrarySyncResponse = withContext(Dispatchers.IO) {
        val store = DulcetDriverFactory(context).openDulcetDatabase()
        try {
            val repository = LibrarySyncRepository(store)
            LibrarySyncManager(LibrarySyncEngine(repository), repository).synchronize(request)
        } finally { store.close() }
    }
}
