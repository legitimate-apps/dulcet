package com.legitimateapps.dulcet.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.legitimateapps.dulcet.core.AndroidLibrarySearchPublication
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRow
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.playback.PlaybackIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public data class SearchAccount(
    val providerInstanceId: String,
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    init {
        require(providerInstanceId.isNotBlank())
        require(normalizedBaseUrl.isNotBlank())
    }

    override fun toString(): String = "SearchAccount(<redacted>)"
}

/**
 * What a search screen draws. [query] is the text as typed, updated on every keystroke; [rows] and
 * [scope] are the core's latest publication (§16.15), which may answer a slightly older keystroke
 * until the next one arrives. The scope is never inferred here: it is the core's.
 */
public data class SearchUiState(
    val query: String = "",
    val rows: List<AndroidLibrarySearchRow> = emptyList(),
    /** Null before the first publication. */
    val scope: AndroidLibrarySearchScope? = null,
    /** The query [rows] answer. */
    val answered: String = "",
    /** The core's word that the server's answer to [answered] is still coming. */
    val serverPending: Boolean = false,
) {
    val results: List<SearchResultItem> get() = rows.map { it.item }

    /** The server's answer is still coming; the core decides, including for a query too short to send. */
    val isLoading: Boolean get() = serverPending

    val error: DomainError? get() = (scope as? AndroidLibrarySearchScope.DeviceServerFailed)?.error

    override fun toString(): String =
        "SearchUiState(query=<redacted>, results=${rows.size}, scope=${scope?.let { it::class.simpleName }})"
}

/**
 * What a search screen says when it has no row to draw and is not waiting (§16.15), the same on the
 * phone and the TV, and the same as Apple says for the same scope.
 */
public sealed interface SearchEmptyState {
    /** The server answered and nothing matches. */
    public data object NoMatches : SearchEmptyState

    /** Offline, and nothing this device has seen matches: said of this device, never of the server. */
    public data object NoMatchesOnDevice : SearchEmptyState

    /**
     * The search failed and nothing on this device matches: Try Again. [error] is the server's
     * (a timeout included), or null when the reader itself failed — Try Again then retries its setup.
     */
    public data class Failed(val error: DomainError?) : SearchEmptyState
}

/**
 * The statement a screen makes in place of rows, or null while it has rows, a blank query, a
 * server answer still coming, or no publication yet. A failed or offline search is never "no
 * matches": the server was not heard from, so only the device is said to have none. A reader that
 * failed is a failure too, with Try Again ([SearchSourceHandle.refresh] retries its setup).
 */
public val SearchUiState.emptyState: SearchEmptyState?
    get() {
        if (query.isBlank() || rows.isNotEmpty() || isLoading) return null
        return when (val current = scope) {
            null -> null
            AndroidLibrarySearchScope.ReaderFailed -> SearchEmptyState.Failed(null)
            AndroidLibrarySearchScope.ServerAndDevice, AndroidLibrarySearchScope.DeviceWhileServerPending ->
                SearchEmptyState.NoMatches
            is AndroidLibrarySearchScope.DeviceOffline -> SearchEmptyState.NoMatchesOnDevice
            is AndroidLibrarySearchScope.DeviceServerFailed -> SearchEmptyState.Failed(current.error)
        }
    }

/** A search screen's state, for tests: rows carry catalog ids and titles, never account data. */
public val SearchObservation: androidx.compose.ui.semantics.SemanticsPropertyKey<SearchUiState> =
    androidx.compose.ui.semantics.SemanticsPropertyKey("SearchObservation")

/**
 * Where a search screen's publications come from: the account's reader in production
 * ([AndroidLibrarySearchSource]). The core owns the debounce, the two-character minimum, the merge,
 * the ranking and the scope; a source only delivers its publications, on the main thread.
 */
public fun interface SearchSource {
    public fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle
}

public interface SearchSourceHandle {
    public fun updateQuery(text: String)
    public fun refresh()
    public fun close()
}

/** Shared phone/TV search state over one [SearchSource]. */
public class SearchPresenter(
    private val account: SearchAccount,
    source: SearchSource,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(SearchUiState())
    public val state: StateFlow<SearchUiState> = mutableState.asStateFlow()
    private var closed = false

    private val handle: SearchSourceHandle = source.open { publication ->
        if (!closed) {
            mutableState.update {
                it.copy(rows = publication.rows, scope = publication.scope, answered = publication.query,
                    serverPending = publication.serverPending)
            }
        }
    }

    public fun updateQuery(value: String) {
        if (closed) return
        mutableState.update { it.copy(query = value) }
        handle.updateQuery(value)
    }

    /** Runs the current query again. */
    public fun refresh() {
        if (!closed) handle.refresh()
    }

    override fun close() {
        if (closed) return
        closed = true
        handle.close()
    }
}

/**
 * What activating a search result does, the same in both shells: an album or an artist opens the
 * library's own detail screen for it ([openAlbum], [openArtist], given the opaque raw id), and a track
 * plays through this app's own non-exported player entry ([PlaybackIntents.playTrack]), which carries
 * opaque identity only. No result opens a screen of its own.
 */
public class SearchActivation(
    private val context: Context,
    private val openAlbum: (String) -> Unit,
    private val openArtist: (String) -> Unit,
) {
    public fun activate(result: SearchResultItem) {
        when (result.type) {
            SearchResultType.Album -> openAlbum(result.id.rawId)
            SearchResultType.Artist -> openArtist(result.id.rawId)
            SearchResultType.Track -> context.startActivity(
                PlaybackIntents.playTrack(context, result.id.providerInstanceId, result.id.rawId, result.title)
                    .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
        }
    }
}

public interface SearchHostDependencies {
    public fun loadAccount(context: Context): SearchAccount?
    /** [foreground]: whether the host is in the foreground now, read from its lifecycle. */
    public fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter
}

public interface SearchHostDependencyOwner {
    public val searchHostDependencies: SearchHostDependencies
}

public object ProductionSearchHostDependencies : SearchHostDependencies {
    override fun loadAccount(context: Context): SearchAccount? =
        AndroidAccountCredentialStore(context).load()?.let { account ->
            SearchAccount(
                providerInstanceId = account.id,
                normalizedBaseUrl = account.serverUrl,
                username = account.username,
                password = account.password,
                allowLocalHttp = account.allowLocalHttp,
            )
        }

    override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter =
        SearchPresenter(account, AndroidLibrarySearchSource(context, account, foreground))
}
