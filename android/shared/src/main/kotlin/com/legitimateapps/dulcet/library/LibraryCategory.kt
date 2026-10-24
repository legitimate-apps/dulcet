package com.legitimateapps.dulcet.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** A shell's editable controls; durable choices remain in the account-bound core store. */
public class LibraryCategoryState internal constructor(
    private val session: LibrarySession,
    public val category: String,
) {
    public var sort: String by mutableStateOf("title")
        private set
    public var filter: String by mutableStateOf("")
        private set
    internal var loaded = false
    internal fun restore(sort: String, filter: String) {
        if (loaded) return
        loaded = true
        this.sort = sort.ifBlank { "title" }
        this.filter = if (category == "downloaded" && filter == "downloaded") "all" else filter
    }
    public fun choose(sort: String) {
        this.sort = sort
        session.setCategory(category, sort, filter)
    }
    public fun filter(text: String) { filter = text }
    internal fun persist() { if (loaded) { if (category == "albums") session.setCategoryFilter(category, filter) else session.setCategory(category, sort, filter) } }
}

@Composable
public fun rememberLibraryCategory(session: LibrarySession, category: String): LibraryCategoryState {
    val depth by session.depth.collectAsState()
    val state = remember(session, category) { LibraryCategoryState(session, category) }
    LaunchedEffect(depth, state) { depth?.let { state.restore(it.sort(category), it.filter(category)) } }
    LaunchedEffect(state, state.filter) { delay(300); state.persist() }
    return state
}

/** A bounded server-wide random queue. Its window is visible only while the person waits here. */
public class LibraryShuffleState internal constructor(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val session: LibrarySession,
) {
    public var busy: Boolean by mutableStateOf(false)
        private set
    public var failed: Boolean by mutableStateOf(false)
        private set

    public fun start(play: (List<com.legitimateapps.dulcet.core.AndroidLibraryItem>) -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            val surface = session.openShuffleSongs()
            try {
                val answer = surface.state.filterNotNull().first { publication ->
                    when (val freshness = publication.freshness) {
                        com.legitimateapps.dulcet.core.AndroidLibraryFreshness.Live -> true
                        is com.legitimateapps.dulcet.core.AndroidLibraryFreshness.Unavailable -> true
                        is com.legitimateapps.dulcet.core.AndroidLibraryFreshness.Cached ->
                            freshness.reason != com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.Revalidating &&
                            freshness.reason != com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.Stale &&
                            freshness.reason != com.legitimateapps.dulcet.core.AndroidLibraryCachedReason.Owed
                        else -> false
                    }
                }
                if (answer.items.any { it is com.legitimateapps.dulcet.core.AndroidLibraryItem.Track && it.canBeQueued() })
                    play(answer.items) else failed = true
            } finally {
                surface.close()
                busy = false
            }
        }
    }
}

@Composable
public fun rememberLibraryShuffle(session: LibrarySession): LibraryShuffleState {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return remember(session, scope) { LibraryShuffleState(scope, session) }
}
