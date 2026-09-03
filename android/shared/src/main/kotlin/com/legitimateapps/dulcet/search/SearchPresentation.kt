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
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.SearchPageRequest
import com.legitimateapps.dulcet.core.SearchPageResult
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.core.ServerSearch
import com.legitimateapps.dulcet.core.mergeSearchResults
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

public data class SearchUiState(
    val query: String = "",
    val results: List<SearchResultItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: DomainError? = null,
) {
    override fun toString(): String =
        "SearchUiState(query=<redacted>, results=${results.size}, " +
            "isLoading=$isLoading, error=$error)"
}

/** A local implementation must return its already-ranked instant result set. */
public fun interface LocalSearchSource {
    public suspend fun search(query: String): List<SearchResultItem>
}

public interface SearchDataSource {
    public suspend fun localResults(query: String): List<SearchResultItem>
    public suspend fun serverResults(account: SearchAccount, query: String): SearchPageResult
}

public class CoreSearchDataSource(
    private val localSource: LocalSearchSource = LocalSearchSource { emptyList() },
    private val serverSearch: ServerSearch = ServerSearch(),
) : SearchDataSource {
    override suspend fun localResults(query: String): List<SearchResultItem> =
        localSource.search(query)

    override suspend fun serverResults(account: SearchAccount, query: String): SearchPageResult =
        serverSearch.search(
            SearchPageRequest(
                providerInstanceId = account.providerInstanceId,
                normalizedBaseUrl = account.normalizedBaseUrl,
                username = account.username,
                password = account.password,
                allowLocalHttp = account.allowLocalHttp,
                query = query,
                artistCount = PAGE_SIZE,
                artistOffset = 0,
                albumCount = PAGE_SIZE,
                albumOffset = 0,
                trackCount = PAGE_SIZE,
                trackOffset = 0,
            ),
        )

    private companion object {
        const val PAGE_SIZE = 30
    }
}

/**
 * Shared mobile/TV search state. Local rows publish immediately; the ranked server page arrives
 * after the contract debounce and replaces matching opaque IDs through the core merge function.
 */
public class SearchPresenter(
    private val account: SearchAccount,
    private val dataSource: SearchDataSource = CoreSearchDataSource(),
    private val serverDebounce: Duration = 250.milliseconds,
    private val scope: CoroutineScope = MainScope(),
) : AutoCloseable {
    private val mutableState = MutableStateFlow(SearchUiState())
    public val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    private var generation: Long = 0
    private var searchJob: Job? = null

    public fun updateQuery(value: String) {
        generation += 1
        val submittedGeneration = generation
        searchJob?.cancel()
        mutableState.value = SearchUiState(query = value)
        val query = value.trim()
        if (query.isEmpty()) return

        searchJob = scope.launch {
            val local = dataSource.localResults(query)
            if (submittedGeneration != generation) return@launch
            mutableState.value = SearchUiState(query = value, results = local)

            if (query.length < MINIMUM_SERVER_QUERY_LENGTH) return@launch
            delay(serverDebounce)
            if (submittedGeneration != generation) return@launch
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)

            when (val result = dataSource.serverResults(account, query)) {
                is SearchPageResult.Loaded -> if (submittedGeneration == generation) {
                    mutableState.value = SearchUiState(
                        query = value,
                        results = mergeSearchResults(local, result.page.results),
                    )
                }
                is SearchPageResult.Failed -> if (
                    submittedGeneration == generation &&
                    result.error != DomainError.Transport.Cancelled
                ) {
                    mutableState.value = mutableState.value.copy(
                        isLoading = false,
                        error = result.error,
                    )
                }
            }
        }
    }

    override fun close() {
        searchJob?.cancel()
        scope.cancel()
    }

    private companion object {
        const val MINIMUM_SERVER_QUERY_LENGTH = 2
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
    public fun createPresenter(account: SearchAccount): SearchPresenter
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

    override fun createPresenter(account: SearchAccount): SearchPresenter = SearchPresenter(account)

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
        setContent { SearchDetailContent(type, title, rawId) }
    }
}

@Composable
private fun SearchDetailContent(type: SearchResultType, title: String, rawId: String) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        BasicText(type.name)
        BasicText(title)
        BasicText(rawId)
    }
}
