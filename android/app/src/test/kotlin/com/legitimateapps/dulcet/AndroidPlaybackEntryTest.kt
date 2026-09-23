package com.legitimateapps.dulcet

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.search.SearchDetailActivity
import com.legitimateapps.dulcet.search.SearchDetailIntent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                assertEquals(PlaybackIntents.ACTION_PLAY_TRACK, intent.action)
                assertEquals(app.packageName, intent.`package`, "Playback intents never leave this package")
                // The action must land on this app's own, non-exported player component.
                val resolved = assertNotNull(app.packageManager.resolveActivity(intent, 0)).activityInfo
                assertEquals("com.legitimateapps.dulcet.PlaybackEntry", resolved.name)
                assertFalse(resolved.exported, "No other application may start playback")
                assertEquals("provider::opaque", intent.getStringExtra(PlaybackIntents.PROVIDER))
                assertEquals("song::opaque", intent.getStringExtra(PlaybackIntents.SONG))
                assertEquals("Playback track", intent.getStringExtra(PlaybackIntents.TITLE))
                assertEquals(setOf(PlaybackIntents.PROVIDER, PlaybackIntents.SONG, PlaybackIntents.TITLE), intent.extras!!.keySet())
            }
        }
    }

    @Test fun onlyThePrivateAliasCanRequestPlayback() {
        val app = RuntimeEnvironment.getApplication()
        fun request(component: String) = PlaybackIntents.playTrack(app, "provider::opaque", "song::opaque", "Track")
            .setClassName(app, component)
        val external = PhonePlaybackRequests()
        external.accept(request("com.legitimateapps.dulcet.MainActivity"))
        assertEquals(null, external.pending.value, "An explicit intent to the exported activity must not start playback")
        assertEquals(0, external.show.value)
        val internal = PhonePlaybackRequests()
        internal.accept(request(PLAYBACK_ENTRY_ALIAS))
        assertEquals("song::opaque", internal.pending.value?.rawId, "The control requires the alias path to be accepted")
        assertEquals(1, internal.show.value)
    }
}
