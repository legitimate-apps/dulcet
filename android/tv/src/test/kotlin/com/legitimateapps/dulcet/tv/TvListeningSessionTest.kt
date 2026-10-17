package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television")
class TvListeningSessionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteReachesAutoplaySleepChoicesCancelAndScrollableCrossfade() {
        var state by mutableStateOf(AndroidPlaybackState(autoplayActive = true))
        val sleeps = mutableListOf<SleepTimerChoice?>()
        val fades = mutableListOf<Int>()
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvListeningSession(state,
            { state = state.copy(autoplayEnabled = it) },
            { sleeps += it; state = state.copy(sleepTimer = it) },
            { fades += it; state = state.copy(crossfadeSeconds = it) }) } }
        focus("tv.listening.autoplay")
        key("tv.listening.autoplay", Key.DirectionCenter)
        assertFalse(state.autoplayEnabled)
        key("tv.listening.autoplay", Key.DirectionDown)
        key("tv.listening.sleep", Key.DirectionCenter)
        compose.onNodeWithTag("tv.listening.sleep.15m").assertIsFocused()
        key("tv.listening.sleep.15m", Key.DirectionCenter)
        assertEquals(listOf(SleepTimerChoice.Minutes15), sleeps)
        // Reopen, move down to End of queue using only the remote, then cancel it.
        key("tv.listening.sleep", Key.DirectionCenter)
        repeat(5) { compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) } }
        compose.onNodeWithTag("tv.listening.sleep.endOfQueue").assertIsFocused()
        key("tv.listening.sleep.endOfQueue", Key.DirectionCenter)
        key("tv.listening.sleep", Key.DirectionCenter)
        repeat(6) { compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) } }
        compose.onNodeWithTag("tv.listening.sleep.cancel").assertIsFocused()
        key("tv.listening.sleep.cancel", Key.DirectionCenter)
        assertEquals(listOf(SleepTimerChoice.Minutes15, SleepTimerChoice.EndOfQueue, null), sleeps)
        focus("tv.listening.crossfade")
        key("tv.listening.crossfade", Key.DirectionCenter)
        repeat(12) { compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) } }
        compose.onNodeWithTag("tv.listening.crossfade.12").assertIsFocused()
        key("tv.listening.crossfade.12", Key.DirectionCenter)
        assertEquals(listOf(12), fades)
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
}
