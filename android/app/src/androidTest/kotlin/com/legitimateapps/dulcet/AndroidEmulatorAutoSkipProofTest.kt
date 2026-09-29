package com.legitimateapps.dulcet

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.SkipProbeProof
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone app skips a track that holds no audio (spec §12.12): the notice names it above the
 * now-playing bar and the tabs, the next track plays with no failure line, and the server counts
 * the next track's play and not the skipped one's. Opt-in; see [SkipProbeProof].
 */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorAutoSkipProofTest {
    @Test fun aTrackWithNoAudioIsSkippedWithANoticeAboveTheBarAndTheNextOnePlays() {
        SkipProbeProof.requireRequested()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SkipProbeProof.run("phone", { ActivityScenario.launch<MainActivity>(
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!) }) { notice, root ->
            // The now-playing bar names the current track; the tabs name themselves.
            val bar = SkipProbeProof.boundsOf(root, SkipProbeProof.PLAYABLE)
                .ifEmpty { SkipProbeProof.boundsOf(root, context.getString(R.string.now_playing_loading)) }
            // The page title can repeat a tab's label, so each tab is the lowest node carrying it.
            val tabs = listOf(R.string.tab_library, R.string.tab_search)
                .mapNotNull { label -> SkipProbeProof.boundsOf(root, context.getString(label)).maxByOrNull { it.top } }
            check(bar.isNotEmpty() && tabs.size == 2) { "The control requires the bar and both tabs on screen: bar=$bar tabs=$tabs" }
            val barTop = bar.maxOf { it.top }
            check(notice.bottom <= barTop) { "The notice $notice must end above the now-playing bar at $barTop" }
            check(tabs.all { notice.bottom <= it.top }) { "The notice $notice must not cover the tabs $tabs" }
            val width = context.resources.displayMetrics.widthPixels
            check(notice.left > 0 && notice.right < width) { "The notice $notice must keep a margin from both sides of $width" }
            "bar-top=$barTop tabs-top=${tabs.minOf { it.top }} screen-width=$width"
        }
    }
}
