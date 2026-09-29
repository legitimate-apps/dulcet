package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidRepeatMode
import com.legitimateapps.dulcet.core.AndroidTrack
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lean-back player's controls, driven with D-pad keys only, as a remote does: from where focus
 * lands (Play/Pause), LEFT and RIGHT reach Shuffle and Repeat, UP reaches the scrubber, whose LEFT and
 * RIGHT seek, and every label is the resource's, in the state it names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvNowPlayingControlsTest {
    @get:Rule val compose = createComposeRule()
    private val resources = RuntimeEnvironment.getApplication().resources
    private val actions = Recording()

    @Test fun shuffleAndRepeatAreReachedFromPlayAndSayTheirState() {
        var state by mutableStateOf(playing())
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, state, actions) } }
        compose.waitForIdle()
        assertTrue(focused("tv.player.playpause"), "setup: focus lands on Play/Pause")
        assertEquals(resources.getString(R.string.tv_player_shuffle_off), description("tv.player.shuffle"))
        assertEquals(resources.getString(R.string.tv_player_repeat_off), description("tv.player.repeat"))
        assertFalse(selected("tv.player.shuffle"))

        key("tv.player.playpause", Key.DirectionLeft)
        assertTrue(focused("tv.player.previous"))
        key("tv.player.previous", Key.DirectionLeft)
        assertTrue(focused("tv.player.shuffle"), "LEFT twice from Play/Pause reaches Shuffle")
        key("tv.player.shuffle", Key.DirectionCenter)
        assertEquals(listOf("shuffle=true"), actions.calls)

        key("tv.player.shuffle", Key.DirectionRight)
        key("tv.player.previous", Key.DirectionRight)
        key("tv.player.playpause", Key.DirectionRight)
        key("tv.player.next", Key.DirectionRight)
        assertTrue(focused("tv.player.repeat"), "RIGHT twice from Play/Pause reaches Repeat")
        key("tv.player.repeat", Key.DirectionCenter)
        assertEquals(listOf("shuffle=true", "repeat"), actions.calls)

        // The controller publishes the new modes; the controls say them.
        state = state.copy(shuffle = true, repeatMode = AndroidRepeatMode.One)
        compose.waitForIdle()
        assertEquals(resources.getString(R.string.tv_player_shuffle_on), description("tv.player.shuffle"))
        assertEquals(resources.getString(R.string.tv_player_repeat_one), description("tv.player.repeat"))
        assertTrue(selected("tv.player.shuffle"))
        assertTrue(selected("tv.player.repeat"))
    }

    /** Presses add up from the last target, not from a position the player has not published yet. */
    @Test fun theScrubberIsAboveTheTransportAndItsLeftAndRightSeek() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, playing(), actions) } }
        compose.waitForIdle()
        key("tv.player.playpause", Key.DirectionUp)
        assertTrue(focused("tv.player.scrubber"), "UP from Play/Pause reaches the scrubber")
        key("tv.player.scrubber", Key.DirectionRight)
        key("tv.player.scrubber", Key.DirectionRight)
        key("tv.player.scrubber", Key.DirectionLeft)
        assertEquals(listOf("seek=70000", "seek=80000", "seek=70000"), actions.calls)
        assertTrue(focused("tv.player.scrubber"), "seeking keeps focus on the scrubber")
        // Clamped to the track.
        repeat(20) { key("tv.player.scrubber", Key.DirectionRight) }
        assertEquals("seek=200000", actions.calls.last())
    }

    @Test fun aTrackThatCannotSeekHasNoScrubberStop() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, playing().copy(seekable = false), actions) }
        }
        compose.waitForIdle()
        key("tv.player.playpause", Key.DirectionUp)
        assertFalse(focused("tv.player.scrubber"), "a scrubber that cannot seek takes no focus")
        assertTrue(actions.calls.isEmpty())
    }

    @Test fun anUpNextEntrySelectedPlaysThatEntry() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, playing(), actions) } }
        compose.waitForIdle()
        key("tv.player.playpause", Key.DirectionDown)
        assertTrue(focused("tv.player.upnext.0") || focused("tv.player.upnext.1"), "DOWN from the transport reaches Up Next")
        val at = if (focused("tv.player.upnext.0")) 0 else 1
        key("tv.player.upnext.$at", Key.DirectionDown)
        key("tv.player.upnext.${at + 1}", Key.DirectionCenter)
        assertEquals(listOf("jump=e${at + 2}"), actions.calls)
    }

    private fun playing() = AndroidPlaybackState(
        title = "Track 2", phase = "Playing", playbackSessionId = "s", playWhenReady = true,
        positionMilliseconds = 60_000, durationMilliseconds = 200_000, seekable = true,
        queue = (1..3).map { AndroidQueueEntry("e$it", AndroidTrack("p", "t$it", "Track $it", "Artist")) },
        currentIndex = 1, canGoNext = true, canGoPrevious = true,
    )

    private fun key(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focused(tag: String): Boolean = runCatching {
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
    }.getOrDefault(false)

    private fun description(tag: String): String? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.firstOrNull()

    private fun selected(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private class Recording : TvPlayerActions {
        val calls = mutableListOf<String>()
        override fun togglePlayPause() { calls += "toggle" }
        override fun previous() { calls += "previous" }
        override fun next() { calls += "next" }
        override fun setShuffle(enabled: Boolean) { calls += "shuffle=$enabled" }
        override fun cycleRepeatMode() { calls += "repeat" }
        override fun seek(positionMilliseconds: Long) { calls += "seek=$positionMilliseconds" }
        override fun jumpTo(queueEntryId: String) { calls += "jump=$queueEntryId" }
    }
}
