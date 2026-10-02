package com.legitimateapps.dulcet.tv

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.StreamingQualityProof
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A streaming-quality cap on an Android TV emulator against the disposable server (spec §12.5,
 * CONF-92): with a 96 kbps cap chosen, the TV's production play entry plays the FLAC fixture and the
 * server's audio answer, read at a relay in front of it, is not the FLAC original, and the player
 * cannot seek it; with Original chosen again, the next play streams the FLAC original. The choice is
 * made in the settings store the TV's Account screen writes (TvAccountEntryFocusTest drives that
 * screen with the remote). See [StreamingQualityProof].
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorStreamingQualityProofTest {
    @Test fun aCapChosenMakesTheServerTranscodeTheNextSongAndOriginalStreamsTheSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        println(StreamingQualityProof.run("tv", { target, accountId, rawId ->
            PlaybackIntents.playTrack(target, accountId, rawId, StreamingQualityProof.SOURCE_TITLE)
                .setClassName(target, TvPlaybackActivity::class.java.name)
        }) { intent -> ActivityScenario.launch<TvPlaybackActivity>(intent) })
    }
}
