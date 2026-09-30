package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.legitimateapps.dulcet.core.AudioContainer
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
 * Play Next and Add to Queue from a TV track row, with the remote alone (spec §14.1): the row's last
 * focus target to the RIGHT opens them, the first choice focused, and a choice closes them with focus
 * back where it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvQueueAdditionTest {
    @get:Rule val compose = createComposeRule()

    @OptIn(ExperimentalTestApi::class)
    @Test fun aTrackRowPlaysNextAndAddsToQueueWithTheRemote() {
        val calls = mutableListOf<String>()
        var adding by mutableStateOf<TvQueueAddition?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column {
                    TvAddToUpNext(adding) { adding = null }
                    TvTrackRow(track(), 0, false, onPlay = { calls += "play" }, onUnavailable = {}, onFavourite = {},
                        download = null, onDownloadToggle = {},
                        onQueue = { adding = TvQueueAddition("Title", { calls += "next" }, { calls += "last" }) })
                }
            }
        }
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.RequestFocus)
        key("album.track.0", Key.DirectionRight)
        key("album.track.0.favourite", Key.DirectionRight)
        key("album.track.0.download", Key.DirectionRight)
        assertTrue(focused("album.track.0.queue"), "the queue button is the row's last target to the RIGHT")
        key("album.track.0.queue", Key.DirectionCenter)
        assertTrue(focused("queue.add.playNext"), "the centre key opens Play Next and Add to Queue, the first focused")
        key("queue.add.playNext", Key.DirectionCenter)
        assertEquals(listOf("next"), calls)
        compose.onAllNodesWithTag("queue.add").assertCountEquals(0)
        assertTrue(focused("album.track.0.queue"), "focus returns to the button")

        key("album.track.0.queue", Key.DirectionCenter)
        key("queue.add.playNext", Key.DirectionDown)
        key("queue.add.addToQueue", Key.DirectionCenter)
        assertEquals(listOf("next", "last"), calls)

        key("album.track.0.queue", Key.DirectionCenter)
        key("queue.add.playNext", Key.DirectionDown)
        key("queue.add.addToQueue", Key.DirectionDown)
        key("queue.add.cancel", Key.DirectionCenter)
        assertEquals(listOf("next", "last"), calls, "Cancel adds nothing")
        compose.onAllNodesWithTag("queue.add").assertCountEquals(0)
    }

    /** What is added is what a play would queue: a track that cannot play now offers no button. */
    @Test fun aTrackThatCannotPlayNowOffersNoQueueButton() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(tvTrackAddition(context, null, "p", track(AndroidLibraryPlayability.UnavailableOffline), null))
        assertNotNull(tvTrackAddition(context, null, "p", track(), null))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvTrackRow(track(), 0, false, onPlay = {}, onUnavailable = {}, onFavourite = {}, onQueue = null)
            }
        }
        compose.onAllNodesWithTag("album.track.0.queue").assertCountEquals(0)
        assertFalse(focused("album.track.0.queue"))
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
