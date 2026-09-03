package com.legitimateapps.dulcet.tv

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchPage
import com.legitimateapps.dulcet.core.SearchPageResult
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchDataSource
import com.legitimateapps.dulcet.search.SearchDetailActivity
import com.legitimateapps.dulcet.search.SearchDetailIntent
import com.legitimateapps.dulcet.search.SearchIntentRouter
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencyOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AndroidTvSearchTestApplication::class, qualifiers = "w960dp-h540dp")
class AndroidTvSearchAppConformanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<TvSearchActivity>()

    @Test
    fun conf41TvQueryDpadFocusTraversalAndActivationRouteToDetail() {
        val application = RuntimeEnvironment.getApplication() as AndroidTvSearchTestApplication

        compose.onNodeWithTag("search.query").performTextInput("echo")
        compose.waitUntil(timeoutMillis = 5_000) { application.presenter.state.value.results.size == 3 }
        compose.onNodeWithTag("search.result.0").assertTextContains("Echo")
        compose.onNodeWithTag("search.result.1").assertTextContains("Echo Ensemble")
        compose.onNodeWithTag("search.result.2").assertTextContains("Echo Track")

        compose.onNodeWithTag("search.query").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.0").assertIsFocused()
        compose.onNodeWithTag("search.result.0").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.1").assertIsFocused()
        compose.onNodeWithTag("search.result.1").performKeyInput { pressKey(Key.DirectionCenter) }

        val routed = shadowOf(application).nextStartedActivity
        assertEquals(SearchDetailActivity::class.java.name, routed.component?.className)
        assertEquals(SearchDetailIntent.ACTION, routed.action)
        assertEquals(SearchDetailIntent.SOURCE_SEARCH, routed.getStringExtra(SearchDetailIntent.EXTRA_SOURCE))
        assertEquals("artist::f4-opaque", routed.getStringExtra(SearchDetailIntent.EXTRA_RAW_ID))
        assertEquals(SearchResultType.Artist.name, routed.getStringExtra(SearchDetailIntent.EXTRA_RESULT_TYPE))
        val diagnosticShape = routed.toUri(Intent.URI_INTENT_SCHEME)
        assertFalse(diagnosticShape.contains(application.account.username))
        assertFalse(diagnosticShape.contains(application.account.password))
    }
}

class AndroidTvSearchTestApplication : Application(), SearchHostDependencyOwner {
    val account = SearchAccount(
        providerInstanceId = "provider::opaque",
        normalizedBaseUrl = "https://music.example.invalid",
        username = "tv-credential-user-canary",
        password = "tv-credential-password-canary",
        allowLocalHttp = false,
    )
    lateinit var presenter: SearchPresenter

    override val searchHostDependencies: SearchHostDependencies = object : SearchHostDependencies {
        override fun loadAccount(context: Context): SearchAccount = account

        override fun createPresenter(account: SearchAccount): SearchPresenter = SearchPresenter(
            account = account,
            dataSource = TvRankedMergedFixtureSearchDataSource(),
            serverDebounce = Duration.ZERO,
            scope = CoroutineScope(Dispatchers.Unconfined),
        ).also { presenter = it }

        override fun createRouter(context: Context): SearchIntentRouter = SearchIntentRouter(context)
    }
}

private class TvRankedMergedFixtureSearchDataSource : SearchDataSource {
    override suspend fun localResults(query: String): List<SearchResultItem> = listOf(
        tvSearchResult("album::7f-opaque", "Stale Echo", SearchResultType.Album),
        tvSearchResult("artist::f4-opaque", "Echo Ensemble", SearchResultType.Artist),
    )

    override suspend fun serverResults(account: SearchAccount, query: String): SearchPageResult =
        SearchPageResult.Loaded(
            SearchPage(
                results = listOf(
                    tvSearchResult("album::7f-opaque", "Echo", SearchResultType.Album),
                    tvSearchResult("track::a9-opaque", "Echo Track", SearchResultType.Track),
                ),
                artistResultCount = 0,
                albumResultCount = 1,
                trackResultCount = 1,
                artistHasMore = false,
                albumHasMore = false,
                trackHasMore = false,
            ),
        )
}

private fun tvSearchResult(rawId: String, title: String, type: SearchResultType): SearchResultItem =
    SearchResultItem(
        id = ProviderItemId("provider::opaque", rawId),
        type = type,
        title = title,
        credits = emptyList(),
        albumTitle = null,
        year = null,
        duration = if (type == SearchResultType.Track) 180.seconds else null,
        discNumber = null,
        trackNumber = null,
        sourceContainer = if (type == SearchResultType.Track) AudioContainer.Mp3 else null,
        mediaSourceId = null,
        artworkKey = null,
    )
