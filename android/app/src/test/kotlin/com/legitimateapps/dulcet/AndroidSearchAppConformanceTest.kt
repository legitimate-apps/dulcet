package com.legitimateapps.dulcet

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.legitimateapps.dulcet.core.AndroidLibrarySearchPublication
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRow
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.mergeSearchResults
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchSource
import com.legitimateapps.dulcet.search.SearchSourceHandle
import com.legitimateapps.dulcet.search.SearchDetailActivity
import com.legitimateapps.dulcet.search.SearchDetailIntent
import com.legitimateapps.dulcet.search.SearchIntentRouter
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencyOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AndroidSearchTestApplication::class)
class AndroidSearchAppConformanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun conf41QueryRendersMergedRankingAndActivationRoutesToDetail() {
        val application = RuntimeEnvironment.getApplication() as AndroidSearchTestApplication

        compose.onNodeWithTag("search.query").performTextInput("echo")
        compose.waitUntil(timeoutMillis = 5_000) { application.presenter.state.value.results.size == 3 }

        compose.onNodeWithTag("search.result.0").assertTextContains("Echo")
        compose.onNodeWithTag("search.result.1").assertTextContains("Echo Ensemble")
        compose.onNodeWithTag("search.result.2").assertTextContains("Echo Track")
        compose.onNodeWithTag("search.result.0").performClick()

        val routed = shadowOf(application).nextStartedActivity
        assertEquals(SearchDetailActivity::class.java.name, routed.component?.className)
        assertEquals(SearchDetailIntent.ACTION, routed.action)
        assertEquals(SearchDetailIntent.SOURCE_SEARCH, routed.getStringExtra(SearchDetailIntent.EXTRA_SOURCE))
        assertEquals("album::7f-opaque", routed.getStringExtra(SearchDetailIntent.EXTRA_RAW_ID))
        assertEquals(SearchResultType.Album.name, routed.getStringExtra(SearchDetailIntent.EXTRA_RESULT_TYPE))
        val diagnosticShape = routed.toUri(Intent.URI_INTENT_SCHEME)
        assertFalse(diagnosticShape.contains(application.account.username))
        assertFalse(diagnosticShape.contains(application.account.password))
    }
}

class AndroidSearchTestApplication : Application(), SearchHostDependencyOwner {
    val account = SearchAccount(
        providerInstanceId = "provider::opaque",
        normalizedBaseUrl = "https://music.example.invalid",
        username = "credential-user-canary",
        password = "credential-password-canary",
        allowLocalHttp = false,
    )
    lateinit var presenter: SearchPresenter

    override val searchHostDependencies: SearchHostDependencies = object : SearchHostDependencies {
        override fun loadAccount(context: Context): SearchAccount = account

        override fun createPresenter(account: SearchAccount, context: Context): SearchPresenter =
            SearchPresenter(account, RankedMergedFixtureSearchSource()).also { presenter = it }

        override fun createRouter(context: Context): SearchIntentRouter = SearchIntentRouter(context)
    }
}

/**
 * A fixture for the SCREEN, not for search: it publishes as the core does — the device's rows at once
 * under `deviceWhileServerPending`, then the core's own merge of device and server rows under
 * `serverAndDevice` — so a screen test sees the two publications a real keystroke produces. The
 * core's search itself is exercised by the production tests against the disposable server.
 */
internal class RankedMergedFixtureSearchSource : SearchSource {
    private val device = listOf(
        searchResult("album::7f-opaque", "Stale Echo", SearchResultType.Album),
        searchResult("artist::f4-opaque", "Echo Ensemble", SearchResultType.Artist),
    )
    private val server = listOf(
        searchResult("album::7f-opaque", "Echo", SearchResultType.Album),
        searchResult("track::a9-opaque", "Echo Track", SearchResultType.Track),
    )

    override fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle = object : SearchSourceHandle {
        private var sequence = 0

        override fun updateQuery(text: String) {
            listener(AndroidLibrarySearchPublication(text, ++sequence, AndroidLibrarySearchScope.DeviceWhileServerPending,
                device.map { AndroidLibrarySearchRow(it, AndroidLibrarySearchRowSource.Device, null, null, null) },
                serverPending = true))
            val serverIds = server.map { it.id }.toSet()
            val merged = mergeSearchResults(device, server).map { item ->
                val source = if (item.id in serverIds) AndroidLibrarySearchRowSource.Server else AndroidLibrarySearchRowSource.Device
                AndroidLibrarySearchRow(item, source, null, null, null)
            }
            listener(AndroidLibrarySearchPublication(text, ++sequence, AndroidLibrarySearchScope.ServerAndDevice, merged))
        }

        override fun refresh() = Unit

        override fun close() = Unit
    }
}

internal fun searchResult(rawId: String, title: String, type: SearchResultType): SearchResultItem =
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
