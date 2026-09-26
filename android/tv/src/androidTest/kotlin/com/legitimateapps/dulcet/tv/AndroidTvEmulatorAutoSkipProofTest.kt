package com.legitimateapps.dulcet.tv

import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.SkipProbeProof
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lean-back player skips a track that holds no audio (spec §12.12), with the notice along
 * its bottom edge and no failure line, and the next track plays. Opt-in; see [SkipProbeProof].
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorAutoSkipProofTest {
    @Test fun aTrackWithNoAudioIsSkippedWithANoticeAndTheNextOnePlays() {
        SkipProbeProof.requireRequested()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        SkipProbeProof.run("tv", {
            ActivityScenario.launch<TvPlaybackActivity>(PlaybackIntents.showNowPlaying(context)
                .setClassName(context, TvPlaybackActivity::class.java.name))
        }) { notice, root ->
            val metrics = context.resources.displayMetrics
            check(notice.top > metrics.heightPixels / 2 && notice.bottom < metrics.heightPixels) {
                "The notice $notice must sit along the bottom edge of ${metrics.heightPixels}"
            }
            val title = SkipProbeProof.boundsOf(root, SkipProbeProof.PLAYABLE)
            check(title.all { !Rect.intersects(it, notice) }) { "The notice $notice covers the title $title" }
            "screen=${metrics.widthPixels}x${metrics.heightPixels} title=${title.map { it.toShortString() }}"
        }
    }
}
