package com.legitimateapps.dulcet

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchDetailActivity
import com.legitimateapps.dulcet.search.SearchIntentRouter
import com.legitimateapps.dulcet.search.SearchPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration

@RunWith(RobolectricTestRunner::class)
class MobileSearchPlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aTrackResultPlaysFromItsButtonWhileTheRowStillOpensDetail() {
        val app = RuntimeEnvironment.getApplication()
        val account = SearchAccount("provider::opaque", "https://music.example.invalid", "u", "p", false)
        val presenter = SearchPresenter(account, RankedMergedFixtureSearchDataSource(), Duration.ZERO,
            CoroutineScope(Dispatchers.Unconfined))
        val played = mutableListOf<SearchResultItem>()
        compose.setContent { MobileSearchScreen(presenter, SearchIntentRouter(app), account) { played += it } }
        compose.onNodeWithTag("search.query").performTextInput("echo")
        compose.waitUntil(5_000) { presenter.state.value.results.size == 3 }

        // Rank 2 is the track; the album and artist above it offer no Play button.
        compose.onNodeWithTag("search.play.0").assertDoesNotExist()
        compose.onNodeWithTag("search.play.1").assertDoesNotExist()
        compose.onNodeWithTag("search.play.2").performClick()
        assertEquals(listOf("track::a9-opaque"), played.map { it.id.rawId })
        assertNull(shadowOf(app).nextStartedActivity, "Play must not also navigate")

        // The row itself keeps CONF-41's route to the detail screen.
        compose.onNodeWithTag("search.result.2").performClick()
        val routed = shadowOf(app).nextStartedActivity
        assertEquals(SearchDetailActivity::class.java.name, routed.component?.className)
        assertEquals(1, played.size, "Activating the row must not play")
        presenter.close()
    }
}
