package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
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
        // The row's menu leads with its heart (the restyle moved a track's favourite into this menu);
        // Play Next is the next choice down.
        assertTrue(focused("album.track.0.favourite"), "long-Select opens the row's menu on its first choice, the heart")
        key("album.track.0.favourite", Key.DirectionDown)
        assertTrue(focused("queue.add.playNext"), "Play Next follows the heart, then Add to Queue")
        key("queue.add.playNext", Key.DirectionCenter)
        assertEquals(listOf("next"), calls)
        compose.onAllNodesWithTag("queue.add").assertCountEquals(0)
        assertTrue(focused("album.track.0"), "focus returns to the row")

        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        key("album.track.0.favourite", Key.DirectionDown)
        key("queue.add.playNext", Key.DirectionDown)
        key("queue.add.addToQueue", Key.DirectionCenter)
        assertEquals(listOf("next", "last"), calls)

        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        key("album.track.0.favourite", Key.DirectionDown)
        key("queue.add.playNext", Key.DirectionDown)
        // The row offers its download toggle after the queue choices; Cancel is last.
        key("queue.add.addToQueue", Key.DirectionDown)
        var downs = 0
        while (!focused("queue.add.cancel") && downs < 4) { compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.DirectionDown) }; compose.waitForIdle(); downs += 1 }
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
        // No download and no queue: a long Select opens the row's menu with only its heart (the
        // restyle moved a track's favourite into this menu), and no queue choice.
        compose.onNodeWithTag("album.track.0").performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertTrue(focused("album.track.0.favourite"), "the menu opens on the heart")
        compose.onAllNodesWithTag("queue.add.playNext").assertCountEquals(0)
        compose.onAllNodesWithTag("queue.add.addToQueue").assertCountEquals(0)
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
