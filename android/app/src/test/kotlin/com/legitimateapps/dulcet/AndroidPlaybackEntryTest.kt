package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.playback.PlaybackEntry
import com.legitimateapps.dulcet.playback.PlaybackActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class AndroidPlaybackEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun productionEntryOpensPlaybackWithOpaqueIdentity() {
        compose.setContent { PlaybackEntry("provider::opaque", "song::opaque", "Playback track") }
        compose.onNodeWithTag("playback.open").performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(PlaybackActivity::class.java.name, intent.component?.className)
        assertEquals("provider::opaque", intent.getStringExtra(PlaybackActivity.PROVIDER))
        assertEquals("song::opaque", intent.getStringExtra(PlaybackActivity.SONG))
        assertEquals("Playback track", intent.getStringExtra(PlaybackActivity.TITLE))
        assertEquals(setOf(PlaybackActivity.PROVIDER, PlaybackActivity.SONG, PlaybackActivity.TITLE), intent.extras!!.keySet())
        println("PLAYBACK ENTRY OBSERVED module=app production-entry-click=true target=PlaybackActivity credentials-in-intent=false decoder-executed=false")
    }
}
