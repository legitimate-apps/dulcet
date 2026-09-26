package com.legitimateapps.dulcet.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidLibrarySearchPublication
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRow
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
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

public object SearchDetailIntent {
    public const val ACTION: String = "com.legitimateapps.dulcet.action.OPEN_SEARCH_DETAIL"
    public const val EXTRA_PROVIDER_INSTANCE_ID: String = "providerInstanceId"
    public const val EXTRA_RAW_ID: String = "rawId"
    public const val EXTRA_RESULT_TYPE: String = "resultType"
    public const val EXTRA_TITLE: String = "title"
    public const val EXTRA_SOURCE: String = "source"
    public const val SOURCE_SEARCH: String = "search"

    public fun create(context: Context, result: SearchResultItem): Intent =
        Intent(context, SearchDetailActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_PROVIDER_INSTANCE_ID, result.id.providerInstanceId)
            .putExtra(EXTRA_RAW_ID, result.id.rawId)
            .putExtra(EXTRA_RESULT_TYPE, result.type.name)
            .putExtra(EXTRA_TITLE, result.title)
            .putExtra(EXTRA_SOURCE, SOURCE_SEARCH)
            .apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
}

public class SearchIntentRouter(private val context: Context) {
    public fun activate(result: SearchResultItem) {
        context.startActivity(SearchDetailIntent.create(context, result))
    }
}

public interface SearchHostDependencies {
    public fun loadAccount(context: Context): SearchAccount?
    /** [foreground]: whether the host is in the foreground now, read from its lifecycle. */
    public fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter
    public fun createRouter(context: Context): SearchIntentRouter
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

    override fun createRouter(context: Context): SearchIntentRouter = SearchIntentRouter(context)
}

public class SearchDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rawId = intent.getStringExtra(SearchDetailIntent.EXTRA_RAW_ID).orEmpty()
        val title = intent.getStringExtra(SearchDetailIntent.EXTRA_TITLE).orEmpty()
        val type = intent.getStringExtra(SearchDetailIntent.EXTRA_RESULT_TYPE)
            ?.let { runCatching { SearchResultType.valueOf(it) }.getOrNull() }
        if (
            intent.action != SearchDetailIntent.ACTION ||
            intent.getStringExtra(SearchDetailIntent.EXTRA_SOURCE) != SearchDetailIntent.SOURCE_SEARCH ||
            rawId.isBlank() || title.isBlank() || type == null
        ) {
            finish()
            return
        }
        val provider = intent.getStringExtra(SearchDetailIntent.EXTRA_PROVIDER_INSTANCE_ID).orEmpty()
        setContent { SearchDetailContent(type, title, rawId, provider) }
    }
}

@Composable
private fun SearchDetailContent(type: SearchResultType, title: String, rawId: String, provider: String) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        BasicText(type.name)
        BasicText(title)
        BasicText(rawId)
        if (type == SearchResultType.Track) {
            com.legitimateapps.dulcet.playback.PlaybackEntry(provider, rawId, title)
        }
    }
}
