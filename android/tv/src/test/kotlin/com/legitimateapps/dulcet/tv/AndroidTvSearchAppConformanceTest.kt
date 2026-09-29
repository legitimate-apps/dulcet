package com.legitimateapps.dulcet.tv

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
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
@Config(application = AndroidTvSearchTestApplication::class, qualifiers = "w960dp-h540dp")
class AndroidTvSearchAppConformanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<TvSearchActivity>()

    @Test
    fun conf41TvQueryDpadFocusTraversalAndActivationRouteToDetail() {
        val application = RuntimeEnvironment.getApplication() as AndroidTvSearchTestApplication

        // At launch the remote starts on the navigation row, not in the field (no keyboard comes up).
        compose.waitUntil(timeoutMillis = 5_000) { focused("search.open") }
        assertFalse(focused("search.query"), "The query field must not take focus at launch")
        compose.onNodeWithTag("search.open").performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitUntil(timeoutMillis = 5_000) { focused("search.query") }
        // Passing over the field leaves it read-only (no input session, so no keyboard); the centre
        // key selects it for typing.
        compose.onNodeWithTag("search.query").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsEditable).not()
            or SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, false))
        compose.onNodeWithTag("search.query").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("search.query").assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))

        compose.onNodeWithTag("search.query").performTextInput("echo")
        compose.waitUntil(timeoutMillis = 5_000) { application.presenter.state.value.results.size == 3 }
        compose.onNodeWithTag("search.result.0").assertTextContains("Echo")
        compose.onNodeWithTag("search.result.1").assertTextContains("Echo Ensemble")
        compose.onNodeWithTag("search.result.2").assertTextContains("Echo Track")

        compose.onNodeWithTag("search.query").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.0").assertIsFocused()
        compose.onNodeWithTag("search.result.0").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.1").assertIsFocused()

        // The artist result opens the library's artist screen above search, in this activity.
        compose.onNodeWithTag("search.result.1").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(timeoutMillis = 5_000) { exists("artist.surface") }
        assertNull(shadowOf(application).nextStartedActivity, "An artist result starts no other activity")
        assertFalse(exists("search.results"))

        // Back returns to search as it was left: the query, its results, and focus on the artist row.
        // One Back is enough only if the centre key opened the artist once.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(timeoutMillis = 5_000) { focused("search.result.1") }
        compose.onNodeWithTag("search.query").assertTextContains("echo")
        compose.onNodeWithTag("search.result.2").assertTextContains("Echo Track")
        assertEquals(1, application.presenters, "Search keeps one presenter across a result opened and left")

        // The album result opens the album screen.
        compose.onNodeWithTag("search.result.1").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("search.result.0").assertIsFocused()
        compose.onNodeWithTag("search.result.0").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(timeoutMillis = 5_000) { exists("album.surface") }
        assertNull(shadowOf(application).nextStartedActivity, "An album result starts no other activity")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(timeoutMillis = 5_000) { focused("search.result.0") }

        // The track result plays, through this app's own player entry, with its opaque identity.
        compose.onNodeWithTag("search.result.0").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.1").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.2").assertIsFocused()
        compose.onNodeWithTag("search.result.2").performKeyInput { pressKey(Key.DirectionCenter) }
        val routed = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(PlaybackIntents.ACTION_PLAY_TRACK, routed.action)
        assertEquals(application.packageName, routed.`package`)
        assertEquals("track::a9-opaque", routed.getStringExtra(PlaybackIntents.SONG))
        assertEquals("provider::opaque", routed.getStringExtra(PlaybackIntents.PROVIDER))
        assertNull(shadowOf(application).nextStartedActivity, "One centre press plays once")
        val diagnosticShape = routed.toUri(Intent.URI_INTENT_SCHEME)
        assertFalse(diagnosticShape.contains(application.account.username))
        assertFalse(diagnosticShape.contains(application.account.password))
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String): Boolean = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }
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
    var presenters = 0

    override val searchHostDependencies: SearchHostDependencies = object : SearchHostDependencies {
        override fun loadAccount(context: Context): SearchAccount = account

        override fun createPresenter(account: SearchAccount, context: Context, foreground: Boolean): SearchPresenter =
            SearchPresenter(account, TvRankedMergedFixtureSearchSource()).also { presenter = it; presenters++ }
    }
}

/**
 * A fixture for the SCREEN, publishing as the core does: the device's rows at once, then the core's
 * own merge under `serverAndDevice`. The core's search runs in the production tests.
 */
private class TvRankedMergedFixtureSearchSource : SearchSource {
    private val device = listOf(
        tvSearchResult("album::7f-opaque", "Stale Echo", SearchResultType.Album),
        tvSearchResult("artist::f4-opaque", "Echo Ensemble", SearchResultType.Artist),
    )
    private val server = listOf(
        tvSearchResult("album::7f-opaque", "Echo", SearchResultType.Album),
        tvSearchResult("track::a9-opaque", "Echo Track", SearchResultType.Track),
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
