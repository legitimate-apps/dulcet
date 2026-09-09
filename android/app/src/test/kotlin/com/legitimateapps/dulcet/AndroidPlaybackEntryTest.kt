package com.legitimateapps.dulcet

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.playback.PlaybackActivity
import com.legitimateapps.dulcet.search.SearchDetailActivity
import com.legitimateapps.dulcet.search.SearchDetailIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class AndroidPlaybackEntryTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun productionSearchDetailOpensPlaybackWithOpaqueIdentity() {
        val app = RuntimeEnvironment.getApplication()
        val result = SearchResultItem(ProviderItemId("provider::opaque", "song::opaque"),
            SearchResultType.Track, "Playback track", credits = emptyList(), albumTitle = null, year = null,
            duration = null, discNumber = null, trackNumber = null, sourceContainer = null,
            mediaSourceId = null, artworkKey = null)
        ActivityScenario.launch<SearchDetailActivity>(SearchDetailIntent.create(app, result)).use { scenario ->
            compose.onNodeWithTag("playback.open").performClick()
            scenario.onActivity { activity ->
                val intent = assertNotNull(shadowOf(activity).nextStartedActivity)
                assertEquals(PlaybackActivity::class.java.name, intent.component?.className)
                assertEquals("provider::opaque", intent.getStringExtra(PlaybackActivity.PROVIDER))
                assertEquals("song::opaque", intent.getStringExtra(PlaybackActivity.SONG))
                assertEquals("Playback track", intent.getStringExtra(PlaybackActivity.TITLE))
                assertEquals(setOf(PlaybackActivity.PROVIDER, PlaybackActivity.SONG, PlaybackActivity.TITLE), intent.extras!!.keySet())
            }
        }
    }
}
