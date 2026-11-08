package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.ui.QueueConfirmationNotice
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.*
import com.legitimateapps.dulcet.shared.R as SharedR

/**
 * The listening options panel, driven with the remote alone: rows a D-pad walks, a switch row for
 * Autoplay, values as supporting text, and choice lists whose radio marks the selection apart from
 * focus and which open on the current value, Off first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television")
class TvListeningSessionTest {
    @get:Rule val compose = createComposeRule()
    private val resources = RuntimeEnvironment.getApplication().resources

    @Test fun thePanelListsAutoplaySleepAndCrossfadeWithRadioChoicesOnTheCurrentValue() {
        var state by mutableStateOf(AndroidPlaybackState(autoplayActive = true))
        val sleeps = mutableListOf<SleepTimerChoice?>()
        val fades = mutableListOf<Int>()
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvListeningSession(state,
            { state = state.copy(autoplayEnabled = it) },
            { sleeps += it; state = state.copy(sleepTimer = it) },
            { fades += it; state = state.copy(crossfadeSeconds = it) }) } }
        // Initial focus is the Autoplay row; the centre key works its switch.
        compose.onNodeWithTag("tv.listening.autoplay").assertIsFocused()
        key("tv.listening.autoplay", Key.DirectionCenter)
        assertFalse(state.autoplayEnabled)
        // Inactive values read "Off".
        compose.onNode(hasText(resources.getString(SharedR.string.listening_off))
            and hasAnyAncestor(hasTestTag("tv.listening.sleep")), useUnmergedTree = true).assertExists()
        key("tv.listening.autoplay", Key.DirectionDown)
        key("tv.listening.sleep", Key.DirectionCenter)
        // Off is the first row and the current value, so it takes the opening focus.
        compose.onNodeWithTag("tv.listening.sleep.cancel").assertIsFocused()
        key("tv.listening.sleep.cancel", Key.DirectionDown)
        compose.onNodeWithTag("tv.listening.sleep.15m").assertIsFocused()
        key("tv.listening.sleep.15m", Key.DirectionCenter)
        assertEquals(listOf<SleepTimerChoice?>(SleepTimerChoice.Minutes15), sleeps)
        // A choice closes the list, and focus is back on the row that opened it.
        compose.onNodeWithTag("tv.listening.sleep").assertIsFocused()
        // Reopened, the list focuses the current value.
        key("tv.listening.sleep", Key.DirectionCenter)
        compose.onNodeWithTag("tv.listening.sleep.15m").assertIsFocused()
        SleepTimerChoice.entries.take(5).forEach { key("tv.listening.sleep.${it.wireName}", Key.DirectionDown) }
        compose.onNodeWithTag("tv.listening.sleep.endOfQueue").assertIsFocused()
        key("tv.listening.sleep.endOfQueue", Key.DirectionCenter)
        assertEquals(listOf<SleepTimerChoice?>(SleepTimerChoice.Minutes15, SleepTimerChoice.EndOfQueue), sleeps)
        // With a timer running, the first row is the explicit "Turn off timer".
        key("tv.listening.sleep", Key.DirectionCenter)
        compose.onNode(hasText(resources.getString(R.string.tv_sleep_turn_off))
            and hasAnyAncestor(hasTestTag("tv.listening.sleep.cancel")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("tv.listening.sleep.endOfQueue").assertIsFocused()
        SleepTimerChoice.entries.forEach { key("tv.listening.sleep.${it.wireName}", Key.DirectionUp) }
        compose.onNodeWithTag("tv.listening.sleep.cancel").assertIsFocused()
        key("tv.listening.sleep.cancel", Key.DirectionCenter)
        assertEquals(listOf(SleepTimerChoice.Minutes15, SleepTimerChoice.EndOfQueue, null), sleeps)

        // Crossfade: Off and the phone's presets (the same set on both), never thirteen one-second steps.
        key("tv.listening.sleep", Key.DirectionDown)
        key("tv.listening.crossfade", Key.DirectionCenter)
        compose.onNodeWithTag("tv.listening.crossfade.0").assertIsFocused()
        compose.onNodeWithTag("tv.listening.crossfade.1").assertDoesNotExist()
        compose.onNodeWithTag("tv.listening.crossfade.7").assertDoesNotExist()
        val presets = listOf(0, 2, 3, 4, 5, 6, 8, 12)
        repeat(presets.size - 1) { key("tv.listening.crossfade.${presets[it]}", Key.DirectionDown) }
        compose.onNodeWithTag("tv.listening.crossfade.12").assertIsFocused()
        key("tv.listening.crossfade.12", Key.DirectionCenter)
        assertEquals(listOf(12), fades)
        key("tv.listening.crossfade", Key.DirectionCenter)
        compose.onNodeWithTag("tv.listening.crossfade.12").assertIsFocused()
    }

    @Test fun fullPlayerKeepsListeningOptionsReachableWithLongTitleRatingAndQueue() {
        val state = AndroidPlaybackState(title = "A long album performance title that takes two lines on a television",
            artist = "Artist", album = "Album", playbackSessionId = "session", currentIndex = 0,
            queue = listOf(AndroidQueueEntry("one", AndroidTrack("provider", "one", "Your song")),
                AndroidQueueEntry("two", AndroidTrack("provider", "two", "Similar song"), true)))
        val actions = object : TvPlayerActions {
            override fun togglePlayPause() {}
            override fun previous() {}
            override fun next() {}
            override fun setShuffle(enabled: Boolean) {}
            override fun cycleRepeatMode() {}
            override fun seek(positionMilliseconds: Long) {}
            override fun jumpTo(queueEntryId: String) {}
            override fun moveEntry(queueEntryId: String, toIndex: Int) = true
            override fun removeEntry(queueEntryId: String) = true
            override fun clearUpcoming() = true
        }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            TvNowPlayingScreen(null, state, actions,
                listening = { TvListeningSessionButton(state, {}, {}, {}, TvPlayerRating(4) {}) })
        } }
        compose.onNodeWithTag("tv.listening.open").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("tv.player.rating").fetchSemanticsNodes().isEmpty(),
            "no rating strip on the player itself")
        focus("tv.listening.open")
        key("tv.listening.open", Key.DirectionCenter)
        compose.onNodeWithTag("tv.listening.autoplay").assertIsFocused()
        key("tv.listening.autoplay", Key.DirectionDown)
        compose.onNodeWithTag("tv.listening.sleep").assertIsFocused()
        key("tv.listening.sleep", Key.DirectionDown)
        compose.onNodeWithTag("tv.listening.crossfade").assertIsFocused()
        key("tv.listening.crossfade", Key.DirectionUp)
        key("tv.listening.sleep", Key.DirectionUp)
        key("tv.listening.autoplay", Key.DirectionUp)
        assertTrue((1..5).any { focused("tv.player.rating.$it") }, "the playing track's rating follows in the panel")
    }

    @Test fun remoteQueueEditsSayPlayingNextAndAddedToQueueOnce() {
        var notice by mutableStateOf<AndroidQueueConfirmation?>(null)
        val claims = mutableListOf<Long>()
        ShadowToast.reset()
        compose.setContent { MaterialTheme { QueueConfirmationNotice(notice) { claims += it; true } } }
        assertNull(ShadowToast.getTextOfLatestToast())
        AndroidQueueInsertion.entries.forEachIndexed { index, insertion ->
            compose.runOnIdle { notice = AndroidQueueConfirmation(index.toLong() + 1, insertion, 1) }
            compose.waitForIdle()
            assertEquals(RuntimeEnvironment.getApplication().getString(if (insertion == AndroidQueueInsertion.PlayNext)
                SharedR.string.queue_confirm_next else SharedR.string.queue_confirm_added), ShadowToast.getTextOfLatestToast())
        }
        assertEquals(listOf(1L, 2L), claims)
    }

    private fun focus(tag: String) { compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() } }
    private fun key(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }
    private fun focused(tag: String): Boolean = runCatching {
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
    }.getOrDefault(false)
}
