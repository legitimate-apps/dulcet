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

/**
 * The listening-session controls (spec §12.13): Sleep timer and Crossfade are rows in the player's
 * More sheet, each opening a radio sheet; the Autoplay switch is the Autoplay section header of the
 * Up Next sheet — the one place it lives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneListeningSessionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sleepChoicesAndCancelAndCrossfadeReachTheirActionsThroughTheirSheets() {
        var state by mutableStateOf(AndroidPlaybackState())
        val sleeps = mutableListOf<SleepTimerChoice?>()
        val fades = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                var sleepOpen by remember { mutableStateOf(false) }
                var fadeOpen by remember { mutableStateOf(false) }
                PhoneListeningSession(state, { sleepOpen = true }, { fadeOpen = true })
                if (sleepOpen) SleepTimerSheet(state,
                    { sleeps += it; state = state.copy(sleepTimer = it); sleepOpen = false }) { sleepOpen = false }
                if (fadeOpen) CrossfadeSheet(state,
                    { fades += it; state = state.copy(crossfadeSeconds = it); fadeOpen = false }) { fadeOpen = false }
            }
        }
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

    /** The Autoplay switch sits on the queue sheet's Autoplay section header — once, nowhere else. */
    @Test fun upNextCarriesTheOneAutoplaySwitchAndQueueEditsShowBriefConfirmationsOnce() {
        var notice by mutableStateOf<AndroidQueueConfirmation?>(null)
        val claimed = mutableListOf<Long>()
        var state by mutableStateOf(AndroidPlaybackState(queue = listOf(
            AndroidQueueEntry("user", AndroidTrack("provider", "one", "Your song")),
            AndroidQueueEntry("auto", AndroidTrack("provider", "two", "Similar song"), isAutoplay = true))))
        val actions = object : UpNextActions {
            override fun jumpTo(queueEntryId: String) {}
            override fun moveEntry(queueEntryId: String, toIndex: Int) = true
            override fun removeEntry(queueEntryId: String) = true
            override fun clearUpcoming() = true
        }
        ShadowToast.reset()
        compose.setContent { MaterialTheme {
            UpNextList(null, state, actions, autoplay = { state = state.copy(autoplayEnabled = it) })
            QueueConfirmationNotice(notice) { claimed += it; true }
        } }
        // One Autoplay: the section header, its text and its switch. "AutoPlay" twice was the defect.
        compose.onNodeWithText("Autoplay").assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText("Autoplay").fetchSemanticsNodes().size)
        compose.onNodeWithTag("listening.autoplay").performClick()
        assertFalse(state.autoplayEnabled)
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
