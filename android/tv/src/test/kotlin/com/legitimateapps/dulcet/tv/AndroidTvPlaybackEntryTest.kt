package com.legitimateapps.dulcet.tv

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.search.SearchActivation
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
class AndroidTvPlaybackEntryTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun aTrackSearchResultOpensPlaybackWithOpaqueIdentity() {
        val app = RuntimeEnvironment.getApplication()
        val result = SearchResultItem(ProviderItemId("provider::opaque", "song::opaque"),
            SearchResultType.Track, "Playback track", credits = emptyList(), albumTitle = null, year = null,
            duration = null, discNumber = null, trackNumber = null, sourceContainer = null,
            mediaSourceId = null, artworkKey = null)
        val opened = mutableListOf<String>()
        SearchActivation(app, openAlbum = { opened += "album:$it" }, openArtist = { opened += "artist:$it" }).activate(result)
        assertEquals(emptyList(), opened, "A track opens no library page")
        val intent = assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(PlaybackIntents.ACTION_PLAY_TRACK, intent.action)
        assertEquals(app.packageName, intent.`package`, "Playback intents never leave this package")
        // The action must land on this app's own, non-exported player component.
        val resolved = assertNotNull(app.packageManager.resolveActivity(intent, 0)).activityInfo
        assertEquals("com.legitimateapps.dulcet.tv.TvPlaybackActivity", resolved.name)
        assertFalse(resolved.exported, "No other application may start playback")
        assertEquals("provider::opaque", intent.getStringExtra(PlaybackIntents.PROVIDER))
        assertEquals("song::opaque", intent.getStringExtra(PlaybackIntents.SONG))
        assertEquals("Playback track", intent.getStringExtra(PlaybackIntents.TITLE))
        assertEquals(setOf(PlaybackIntents.PROVIDER, PlaybackIntents.SONG, PlaybackIntents.TITLE), intent.extras!!.keySet())
    }

    @Test fun albumAndArtistSearchResultsOpenTheLibraryPagesAndStartNoActivity() {
        val app = RuntimeEnvironment.getApplication()
        fun item(rawId: String, type: SearchResultType) = SearchResultItem(ProviderItemId("provider::opaque", rawId),
            type, "Title", credits = emptyList(), albumTitle = null, year = null, duration = null, discNumber = null,
            trackNumber = null, sourceContainer = null, mediaSourceId = null, artworkKey = null)
        val opened = mutableListOf<String>()
        val activation = SearchActivation(app, openAlbum = { opened += "album:$it" }, openArtist = { opened += "artist:$it" })
        activation.activate(item("album::opaque/1", SearchResultType.Album))
        activation.activate(item("artist::opaque/2", SearchResultType.Artist))
        assertEquals(listOf("album:album::opaque/1", "artist:artist::opaque/2"), opened, "Opaque ids reach the pages unchanged")
        assertEquals(null, shadowOf(app).nextStartedActivity, "No placeholder activity is started")
    }
}
