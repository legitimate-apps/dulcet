package com.legitimateapps.dulcet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.legitimateapps.dulcet.core.*

/** A mounted page owns its callbacks. Data survives Back in the account's phone navigation state. */
internal class DiscoverPagedReader<T>(
    private val cursor: (T) -> String?,
    private val append: (T, T) -> T,
) {
    var value: T? by mutableStateOf(null); private set
    var error: DiscoverFailure? by mutableStateOf(null); private set
    var loading by mutableStateOf(false); private set
    var pagesLoaded by mutableStateOf(0); private set
    private var restoreTarget = 1
    private var generation = 0L
    private var mounted = false
    private val operations = mutableListOf<DiscoverOperation>()
    private var read: ((String?, (DiscoverResult<T>) -> Unit) -> DiscoverOperation)? = null
    fun mount(restorePages: Int = 1, read: (String?, (DiscoverResult<T>) -> Unit) -> DiscoverOperation) {
        mounted = true; this.read = read
        if (value == null) { restoreTarget = restorePages.coerceAtLeast(1); generation++; request(null, generation) }
    }
    fun close() {
        mounted = false; generation++; operations.forEach { it.cancel() }; operations.clear()
        read = null; loading = false
    }
    fun refresh() {
        if (!mounted) return
        generation++; operations.forEach { it.cancel() }; operations.clear()
        restoreTarget = 1
        request(null, generation)
    }
    fun more() {
        if (!mounted || loading) return
        val next = value?.let(cursor) ?: return
        request(next, generation)
    }
    private fun request(next: String?, token: Long) {
        val fetch = read ?: return
        loading = true; error = null
        val operation = fetch(next) { result ->
            if (mounted && token == generation) {
                loading = false
                when (result) {
                    is DiscoverResult.Value -> {
                        value = if (next == null) result.value else value?.let { append(it, result.value) } ?: result.value
                        pagesLoaded = if (next == null) 1 else pagesLoaded + 1
                        if (pagesLoaded < restoreTarget) more()
                    }
                    is DiscoverResult.Failed -> {
                        if (result.error.code == "cursor-expired" && next != null) refresh()
                        else error = result.error
                    }
                }
            }
        }
        if (mounted && token == generation) operations += operation else operation.cancel()
    }
}

internal class DiscoverBrowseMemory {
    val shelves = mutableMapOf<String, DiscoverPagedReader<DiscoverShelfPage>>()
    val lists = mutableMapOf<String, DiscoverPagedReader<DiscoverPlaylistList>>()
    val playlists = mutableMapOf<String, DiscoverPagedReader<DiscoverPlaylist>>()
    fun shelf(id: String) = shelves.getOrPut(id) { DiscoverPagedReader({ it.nextCursor }, { a, b ->
        b.copy(items = (a.items + b.items).distinctBy { it.id }, playlists = (a.playlists + b.playlists).distinctBy { it.id })
    }) }
    fun list(query: String) = lists.getOrPut(query) { DiscoverPagedReader({ it.nextCursor }, { a, b -> b.copy(items = (a.items + b.items).distinctBy { it.id }) }) }
    fun playlist(id: String) = playlists.getOrPut(id) { DiscoverPagedReader({ it.nextCursor }, { a, b ->
        b.copy(entries = (a.entries + b.entries).distinctBy { it.position })
    }) }
}
