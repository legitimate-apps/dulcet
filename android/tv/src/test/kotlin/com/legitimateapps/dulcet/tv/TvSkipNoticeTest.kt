package com.legitimateapps.dulcet.tv

import android.os.SystemClock
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidSkipNotice
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.ui.SKIP_NOTICE_TAG
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** The TV player shows the skip notice along its own bottom edge (spec §12.12 rule 5). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvSkipNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theTvPlayerAnnouncesTheSkippedTrackAndShowsNoFailureLine() {
        val state = AndroidPlaybackState(title = "Playable After Skip", phase = "Preparing", playbackSessionId = "s",
            skipNotice = AndroidSkipNotice(1, "Unplayable Probe", SystemClock.elapsedRealtime()))
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlaying(null, state, null) } }
        val node = compose.onNodeWithTag(SKIP_NOTICE_TAG).fetchSemanticsNode()
        assertEquals(listOf("Couldn’t play “Unplayable Probe”. Skipped."), node.config[SemanticsProperties.ContentDescription])
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
        compose.onNodeWithTag("tv.player.error").assertDoesNotExist()
    }

    @Test fun aConnectionFailureStillShowsTheFailureLineAndNoNotice() {
        val state = AndroidPlaybackState(title = "Song", phase = "Failed", playbackSessionId = "s",
            error = DomainError.Transport.Unreachable)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlaying(null, state, null) } }
        compose.onNodeWithTag("tv.player.error").assertExists()
        compose.onNodeWithTag(SKIP_NOTICE_TAG).assertDoesNotExist()
    }
}
