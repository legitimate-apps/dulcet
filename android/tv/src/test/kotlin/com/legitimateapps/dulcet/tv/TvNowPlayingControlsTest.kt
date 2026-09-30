package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

    /**
     * An Up Next entry is edited with the remote alone: RIGHT from its row reaches its options, the
     * centre key opens them with the first choice focused, and a choice closes them with focus back
     * on the button. The first entry of Up Next cannot move up and the last cannot move down.
     */
    @Test fun anUpNextEntryIsMovedAndRemovedWithTheRemote() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, fourEntries(), actions) } }
        compose.waitForIdle()
        reachEntry(2)
        key("tv.player.upnext.2", Key.DirectionRight)
        assertTrue(focused("tv.player.upnext.2.edit"), "RIGHT from an Up Next entry reaches its options")
        key("tv.player.upnext.2.edit", Key.DirectionCenter)
        assertTrue(exists("tv.player.upnext.edit"), "the centre key opens the entry's options")
        assertFalse(exists("tv.player.upnext.edit.moveUp"), "the first Up Next entry cannot move above the playing one")
        assertTrue(focused("tv.player.upnext.edit.moveDown"), "the first choice takes focus")
        key("tv.player.upnext.edit.moveDown", Key.DirectionCenter)
        assertEquals(listOf("move=e3>3"), actions.calls, "Move Down names the entry and its new place in the whole queue")
        assertFalse(exists("tv.player.upnext.edit"), "a choice closes the options")
        assertTrue(focused("tv.player.upnext.2.edit"), "focus returns to the button that opened them")

        key("tv.player.upnext.2.edit", Key.DirectionDown)
        assertTrue(focused("tv.player.upnext.3.edit"), "DOWN moves between the options buttons")
        key("tv.player.upnext.3.edit", Key.DirectionCenter)
        assertTrue(exists("tv.player.upnext.edit.moveUp"))
        assertFalse(exists("tv.player.upnext.edit.moveDown"), "the last entry cannot move down")
        key("tv.player.upnext.edit.moveUp", Key.DirectionDown)
        key("tv.player.upnext.edit.remove", Key.DirectionCenter)
        assertEquals(listOf("move=e3>3", "remove=e4"), actions.calls)
    }

    /** Only Up Next is edited: the playing entry, which the queue will not remove, and those before it have no options. */
    @Test fun thePlayingEntryAndThosePlayedHaveNoOptions() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, fourEntries(), actions) } }
        compose.waitForIdle()
        assertTrue(exists("tv.player.upnext.0") && exists("tv.player.upnext.1"), "setup: the rows are composed")
        assertFalse(exists("tv.player.upnext.0.edit"))
        assertFalse(exists("tv.player.upnext.1.edit"), "the playing entry cannot be removed")
        reachEntry(2)
        assertTrue(exists("tv.player.upnext.2.edit"), "an Up Next entry has options")
    }

    /** Clear Up Next is among an entry's options; a refusal from the queue is said. */
    @Test fun clearRemovesUpNextAndARefusedEditIsSaid() {
        actions.accepts = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, fourEntries(), actions) } }
        compose.waitForIdle()
        reachEntry(2)
        key("tv.player.upnext.2", Key.DirectionRight)
        key("tv.player.upnext.2.edit", Key.DirectionCenter)
        key("tv.player.upnext.edit.moveDown", Key.DirectionDown)
        key("tv.player.upnext.edit.remove", Key.DirectionDown)
        assertTrue(focused("tv.player.upnext.edit.clear"), "DOWN through the options reaches Clear Up Next")
        key("tv.player.upnext.edit.clear", Key.DirectionCenter)
        assertEquals(listOf("clear"), actions.calls)
        assertEquals(sharedString(com.legitimateapps.dulcet.shared.R.string.queue_edit_refused),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast(), "a refused edit is said")
    }

    /** DOWN from the transport, then DOWN through the list to the entry at [position]. */
    private fun reachEntry(position: Int) {
        key("tv.player.playpause", Key.DirectionDown)
        var at = (0..3).firstOrNull { focused("tv.player.upnext.$it") }
        assertTrue(at != null, "setup: DOWN from the transport reaches Up Next")
        while (at!! < position) { key("tv.player.upnext.$at", Key.DirectionDown); at++ }
        assertTrue(focused("tv.player.upnext.$position"), "setup: D-pad DOWN reaches entry $position")
    }

    /**
     * The heart is the transport's last control, RIGHT of Repeat: its state is its fill and its
     * description the action; centre toggles it; a change that needs words says them beneath the title.
     */
    @Test fun theHeartIsRightOfRepeatAndSaysItsStateAndAnyOutcome() {
        var favourite by mutableStateOf(false)
        var line by mutableStateOf<String?>(null)
        val toggles = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvNowPlayingScreen(null, playing(), actions, TvPlayerFavourite(favourite, line) { toggles += !favourite; favourite = !favourite })
            }
        }
        compose.waitForIdle()
        key("tv.player.playpause", Key.DirectionRight)
        key("tv.player.next", Key.DirectionRight)
        key("tv.player.repeat", Key.DirectionRight)
        assertTrue(focused("tv.player.favourite"), "RIGHT three times from Play/Pause reaches the heart")
        assertFalse(selected("tv.player.favourite"))
        assertEquals(sharedString(com.legitimateapps.dulcet.shared.R.string.library_favourite_add), description("tv.player.favourite"))
        key("tv.player.favourite", Key.DirectionCenter)
        assertEquals(listOf(true), toggles)
        assertTrue(selected("tv.player.favourite"), "the heart fills with the press")
        assertEquals(sharedString(com.legitimateapps.dulcet.shared.R.string.library_favourite_remove), description("tv.player.favourite"))
        assertTrue(focused("tv.player.favourite"), "focus stays on the heart")
        assertEquals(emptyList(), actions.calls, "the heart changes the track, not playback")
        assertFalse(exists("tv.player.favourite.outcome"))
        line = "Couldn't save that change — the server"
        compose.waitForIdle()
        assertEquals(line, compose.onNodeWithTag("tv.player.favourite.outcome").fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString())
    }

    private fun sharedString(id: Int): String = resources.getString(id)

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun playing() = AndroidPlaybackState(
        title = "Track 2", phase = "Playing", playbackSessionId = "s", playWhenReady = true,
        positionMilliseconds = 60_000, durationMilliseconds = 200_000, seekable = true,
        queue = (1..3).map { AndroidQueueEntry("e$it", AndroidTrack("p", "t$it", "Track $it", "Artist")) },
        currentIndex = 1, canGoNext = true, canGoPrevious = true,
    )

    private fun fourEntries() = playing().copy(
        queue = (1..4).map { AndroidQueueEntry("e$it", AndroidTrack("p", "t$it", "Track $it", "Artist")) })

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
        /** What the queue answers to an edit. */
        var accepts = true
        override fun moveEntry(queueEntryId: String, toIndex: Int): Boolean { calls += "move=$queueEntryId>$toIndex"; return accepts }
        override fun removeEntry(queueEntryId: String): Boolean { calls += "remove=$queueEntryId"; return accepts }
        override fun clearUpcoming(): Boolean { calls += "clear"; return accepts }
    }
}
