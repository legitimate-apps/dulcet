package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Play Next and Add to Queue from a TV track row's long-Select menu, with the remote alone
 * (spec §14.1): holding the centre key on the row opens them, the first choice focused, and a
 * choice closes them with focus back where it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvQueueAdditionTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun aTrackRowPlaysNextAndAddsToQueueWithTheRemote() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column {
                    TvTrackRow(track(), 0, false, onPlay = { calls += "play" }, onUnavailable = {}, onFavourite = {},
                        download = null, onDownloadToggle = {},
                        queue = TvQueueAddition("Title", { calls += "next" }, { calls += "last" }))
                }
            }
        }
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertTrue(focused("queue.add.playNext"), "long-Select opens Play Next and Add to Queue, the first focused")
        key("queue.add.playNext", Key.DirectionCenter)
        assertEquals(listOf("next"), calls)
        compose.onAllNodesWithTag("queue.add").assertCountEquals(0)
        assertTrue(focused("album.track.0"), "focus returns to the row")

        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        key("queue.add.playNext", Key.DirectionDown)
        key("queue.add.addToQueue", Key.DirectionCenter)
        assertEquals(listOf("next", "last"), calls)

        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        key("queue.add.playNext", Key.DirectionDown)
        key("queue.add.addToQueue", Key.DirectionDown)
        key("queue.add.cancel", Key.DirectionDown)
        key("queue.add.cancel", Key.DirectionCenter)
        assertEquals(listOf("next", "last"), calls, "Cancel adds nothing")
        compose.onAllNodesWithTag("queue.add").assertCountEquals(0)
    }

    /** What is added is what a play would queue: a track that cannot play now offers no menu. */
    @Test fun aTrackThatCannotPlayNowOffersNoQueueMenu() {
        val context = RuntimeEnvironment.getApplication()
        val controller = AndroidPlaybackController(context, PlaybackEndpointAccount("p", "http://127.0.0.1:9", "u", "p", true))
        try {
            assertNull(tvTrackAddition(context, controller, "p", track(AndroidLibraryPlayability.UnavailableOffline), null))
            assertNotNull(tvTrackAddition(context, controller, "p", track(), null))
            // While no playback service is bound the row offers nothing, rather than a choice then refused.
            assertNull(tvTrackAddition(context, null, "p", track(), null), "no playback service bound")
        } finally { controller.close() }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvTrackRow(track(), 0, false, onPlay = {}, onUnavailable = {}, onFavourite = {}, queue = null)
            }
        }
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        // No download and no queue: a long Select opens nothing.
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        compose.onAllNodesWithTag("queue.add").assertCountEquals(0)
        assertTrue(focused("album.track.0"), "nothing opened, so the row keeps focus")
    }

    private fun key(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focused(tag: String): Boolean = runCatching {
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
    }.getOrDefault(false)

    private fun track(playability: AndroidLibraryPlayability = AndroidLibraryPlayability.Streamable) = AndroidLibraryItem.Track(
        rawId = "t1", title = "Title", albumRawId = "album", albumTitle = "Album", artistName = "Artist",
        artistRawId = null, discNumber = 1, trackNumber = 1, durationMilliseconds = 30_000, sourceContainer = AudioContainer.Flac,
        artworkKey = null, favourite = false, rating = null, playCount = null,
        playability = playability, metadataMissing = false,
    )
}
