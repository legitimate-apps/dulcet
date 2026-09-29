package com.legitimateapps.dulcet

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchSource
import com.legitimateapps.dulcet.search.SearchSourceHandle
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.SearchHostDependencies
import com.legitimateapps.dulcet.search.SearchHostDependencyOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // An album result opens the library's album page in this activity; nothing else is started.
        compose.onNodeWithTag("search.result.0").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { exists("album.surface") }
        assertNull(shadowOf(application).nextStartedActivity, "An album result starts no other activity")
        assertFalse(exists("search.results"))

        // Back returns to search as it was left, with the same presenter.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(timeoutMillis = 5_000) { exists("search.results") }
        compose.onNodeWithTag("search.query").assertTextContains("echo")
        compose.onNodeWithTag("search.result.1").assertTextContains("Echo Ensemble")
        assertEquals(1, application.presenters, "Search keeps one presenter across a result opened and left")

        // An artist result opens the artist page.
        compose.onNodeWithTag("search.result.1").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { exists("artist.surface") }
        assertNull(shadowOf(application).nextStartedActivity, "An artist result starts no other activity")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(timeoutMillis = 5_000) { exists("search.results") }

        // A track result plays through this app's own player entry, with its opaque identity.
        compose.onNodeWithTag("search.result.2").performClick()
        val routed = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(PlaybackIntents.ACTION_PLAY_TRACK, routed.action)
        assertEquals(application.packageName, routed.`package`)
        assertEquals("track::a9-opaque", routed.getStringExtra(PlaybackIntents.SONG))
        assertEquals("provider::opaque", routed.getStringExtra(PlaybackIntents.PROVIDER))
        val diagnosticShape = routed.toUri(Intent.URI_INTENT_SCHEME)
        assertFalse(diagnosticShape.contains(application.account.username))
        assertFalse(diagnosticShape.contains(application.account.password))
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
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
    var presenters = 0

    override val searchHostDependencies: SearchHostDependencies = object : SearchHostDependencies {
        override fun loadAccount(context: Context): SearchAccount = account

        override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter =
            SearchPresenter(account, RankedMergedFixtureSearchSource()).also { presenter = it; presenters++ }
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
