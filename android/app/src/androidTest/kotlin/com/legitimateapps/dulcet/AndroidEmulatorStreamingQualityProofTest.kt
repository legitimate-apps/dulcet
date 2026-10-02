package com.legitimateapps.dulcet

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.legitimateapps.dulcet.emulator.StreamingQualityProof
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A streaming-quality cap on a phone emulator against the disposable server (spec §12.5, CONF-92):
 * with a 96 kbps cap chosen, the production play entry plays the FLAC fixture and the server's audio
 * answer, read at a relay in front of it, is not the FLAC original, and the player cannot seek it;
 * with Original chosen again, the next play streams the FLAC original. See [StreamingQualityProof].
 */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorStreamingQualityProofTest {
    @Test fun aCapChosenMakesTheServerTranscodeTheNextSongAndOriginalStreamsTheSource() {
        println(StreamingQualityProof.run("phone", { context, accountId, rawId ->
            PlaybackIntents.playTrack(context, accountId, rawId, StreamingQualityProof.SOURCE_TITLE)
                .setClassName(context, PLAYBACK_ENTRY_ALIAS)
        }) { intent -> ActivityScenario.launch<MainActivity>(intent) })
    }
}
