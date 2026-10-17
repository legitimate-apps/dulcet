package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneListeningSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sleepChoicesCancelAutoplayAndCrossfadeReachTheirActions() {
        var state by mutableStateOf(AndroidPlaybackState(autoplayActive = true))
        val sleeps = mutableListOf<SleepTimerChoice?>()
        val fades = mutableListOf<Int>()
        compose.setContent { MaterialTheme { PhoneListeningSession(state,
            { state = state.copy(autoplayEnabled = it) },
            { sleeps += it; state = state.copy(sleepTimer = it) },
            { fades += it; state = state.copy(crossfadeSeconds = it) }) } }
        compose.onNodeWithTag("listening.autoplay").performClick()
        assertFalse(state.autoplayEnabled)
        SleepTimerChoice.entries.forEach { choice ->
            compose.onNodeWithTag("listening.sleep").performClick()
            compose.onNodeWithTag("listening.sleep.${choice.wireName}").performClick()
        }
        compose.onNodeWithTag("listening.sleep").performClick()
        compose.onNodeWithTag("listening.sleep.cancel").performClick()
        assertEquals(SleepTimerChoice.entries.toList() + null, sleeps)
        for (seconds in listOf(12, 0)) {
            compose.onNodeWithTag("listening.crossfade").performClick()
            compose.onNodeWithTag("listening.crossfade.$seconds").performScrollTo().performClick()
        }
        assertEquals(listOf(12, 0), fades)
    }

    @Test fun upNextNamesTheAutoplaySectionAndQueueEditsShowBriefConfirmationsOnce() {
        var notice by mutableStateOf<AndroidQueueConfirmation?>(null)
        val claimed = mutableListOf<Long>()
        val state = AndroidPlaybackState(queue = listOf(
            AndroidQueueEntry("user", AndroidTrack("provider", "one", "Your song")),
            AndroidQueueEntry("auto", AndroidTrack("provider", "two", "Similar song"), isAutoplay = true)))
        val actions = object : UpNextActions {
            override fun jumpTo(queueEntryId: String) {}
            override fun moveEntry(queueEntryId: String, toIndex: Int) = true
            override fun removeEntry(queueEntryId: String) = true
            override fun clearUpcoming() = true
        }
        ShadowToast.reset()
        compose.setContent { MaterialTheme {
            UpNextList(null, state, actions)
            QueueConfirmationNotice(notice) { claimed += it; true }
        } }
        compose.onNodeWithText("AutoPlay").assertIsDisplayed()
        assertNull(ShadowToast.getTextOfLatestToast(), "No confirmation when the empty player starts playback")
        AndroidQueueInsertion.entries.forEachIndexed { index, insertion ->
            compose.runOnIdle { notice = AndroidQueueConfirmation(index.toLong() + 1, insertion, 1) }
            compose.waitForIdle()
            assertEquals(RuntimeEnvironment.getApplication().getString(if (insertion == AndroidQueueInsertion.PlayNext)
                SharedR.string.queue_confirm_next else SharedR.string.queue_confirm_added), ShadowToast.getTextOfLatestToast())
        }
        assertEquals(listOf(1L, 2L), claimed)
    }
}
