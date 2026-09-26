package com.legitimateapps.dulcet

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidSkipNotice
import com.legitimateapps.dulcet.ui.SKIP_NOTICE_TAG
import com.legitimateapps.dulcet.ui.SkipNoticeDrawnSentence
import com.legitimateapps.dulcet.ui.SkipNoticeRegion
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The phone's skip notice (spec §12.12 rule 5): what it says, how long it stays, and what it leaves alone. */
@RunWith(RobolectricTestRunner::class)
class PhoneSkipNoticeTest {
    @get:Rule val compose = createComposeRule()

    private var now = 50_000L

    @Composable
    private fun region(notice: AndroidSkipNotice?, visible: Boolean = true, modifier: Modifier = Modifier.fillMaxSize()) {
        SkipNoticeRegion(notice, visible, MaterialTheme.colorScheme.inverseSurface,
            MaterialTheme.colorScheme.inverseOnSurface, MaterialTheme.typography.bodyMedium, modifier) { now }
    }

    private fun announced() = compose.onNodeWithTag(SKIP_NOTICE_TAG).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.ContentDescription)?.single()

    private fun drawn() = compose.onNodeWithTag(SKIP_NOTICE_TAG).fetchSemanticsNode().config[SkipNoticeDrawnSentence]

    @Test fun theNoticeNamesTheTrackInOneAnnouncedSentenceAndGoesAfterItsTime() {
        compose.setContent { MaterialTheme { region(AndroidSkipNotice(1, "Unplayable Probe", now)) } }
        val node = compose.onNodeWithTag(SKIP_NOTICE_TAG).fetchSemanticsNode()
        assertEquals("Couldn’t play “Unplayable Probe”. Skipped.", announced())
        assertEquals("Couldn’t play “Unplayable Probe”. Skipped.", drawn())
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion], "TalkBack must announce it")
        assertEquals(0, node.children.size, "One node, one sentence: nothing inside it is read separately")
        compose.onAllNodesWithTag(SKIP_NOTICE_TAG, useUnmergedTree = true).fetchSemanticsNodes().let {
            assertEquals(1, it.size, "Exactly one notice is on screen")
        }

        now += 3_900
        compose.mainClock.advanceTimeBy(3_900)
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertExists("It stays for four seconds")
        now += 200
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertDoesNotExist()
    }

    /** A person who asked Android for more time to read content that disappears gets it (spec §12.12 rule 8). */
    @Test fun theNoticeStaysAsLongAsThePersonAskedAndroidToGiveIt() {
        val asked = mutableListOf<Long>()
        val slower = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean,
                containsText: Boolean, containsControls: Boolean): Long {
                asked += originalTimeoutMillis
                return 10_000
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides slower) {
                MaterialTheme { region(AndroidSkipNotice(1, "Unplayable Probe", now)) }
            }
        }
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertExists()
        assertEquals(listOf(4_000L), asked.distinct(), "The control requires the system to be asked about four seconds")
        now += 9_000
        compose.mainClock.advanceTimeBy(9_000)
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertExists("Nine seconds in, the ten the person asked for are not over")
        now += 1_100
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertDoesNotExist()
    }

    @Test fun aNoticeItsOwnerWithdrawsGoesAtOnce() {
        var notice by mutableStateOf<AndroidSkipNotice?>(AndroidSkipNotice(1, "Unplayable Probe", now))
        compose.setContent { MaterialTheme { region(notice) } }
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertExists()
        notice = null
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertDoesNotExist()
    }

    @Test fun aSkipThatHappenedOutOfSightIsNotShownLater() {
        compose.setContent { MaterialTheme { region(AndroidSkipNotice(1, "Unplayable Probe", now - 4_500)) } }
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertDoesNotExist()
    }

    @Test fun aSurfaceNotInFrontShowsNothing() {
        compose.setContent { MaterialTheme { region(AndroidSkipNotice(1, "Unplayable Probe", now), visible = false) } }
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertDoesNotExist()
    }

    @Test fun anUnknownTitleIsATrack() {
        compose.setContent { MaterialTheme { region(AndroidSkipNotice(1, null, now)) } }
        assertEquals("Couldn’t play a track. Skipped.", announced())
        assertEquals("Couldn’t play a track. Skipped.", drawn())
    }

    @Test fun theShorterSentenceStandsInWhenTheNamedOneWouldTakeMoreThanAThird() {
        val long = "Unplayable Long Probe -- Concerto for Two Violins in D minor, BWV 1043"
        var height by mutableStateOf(900)
        compose.setContent {
            MaterialTheme { region(AndroidSkipNotice(1, long, now), modifier = Modifier.fillMaxWidth().height(height.dp)) }
        }
        for ((tall, expected) in listOf(900 to "Couldn’t play “$long”. Skipped.", 150 to "Couldn’t play a track. Skipped.")) {
            height = tall
            compose.waitForIdle()
            assertEquals(expected, drawn(), "In a region $tall dp tall")
            assertEquals("Couldn’t play “$long”. Skipped.", announced(), "TalkBack still hears the track's name")
        }
    }

    @Test fun tapsPassThroughTheNoticeToWhatIsBeneath() {
        var tapped = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp).testTag("beneath")
                        .clickable { tapped++ })
                    region(AndroidSkipNotice(1, "Unplayable Probe", now))
                }
            }
        }
        compose.onNodeWithTag(SKIP_NOTICE_TAG).performTouchInput { click(center) }
        assertEquals(1, tapped, "A tap on the notice must reach the control beneath it")
    }

    @Test fun thePhoneSurfaceShowsTheControllersNotice() {
        val state = AndroidPlaybackState(skipNotice = AndroidSkipNotice(3, "Unplayable Probe", SystemClock.elapsedRealtime()))
        compose.setContent { MaterialTheme { PhoneSkipNotice(state, visible = true) } }
        assertEquals("Couldn’t play “Unplayable Probe”. Skipped.", announced())
    }
}
