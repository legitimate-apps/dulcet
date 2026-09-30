package com.legitimateapps.dulcet.tv

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.DownloadProof
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV app on an Android TV emulator: CONF-51 and CONF-52 as on the phone ([DownloadProof]),
 * played through the lean-back player's production entry.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorDownloadConformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun conf51ADownloadIsValidatedAndPromotedAtomicallyThroughAWorkManagerTask() {
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        DownloadProof(context, DisposableServerProbe.fromInstrumentation()).conf51()
    }

    @Test fun conf52ADownloadedSongPlaysTheIdenticalFileWithEveryNetworkDown() {
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        DownloadProof(context, DisposableServerProbe.fromInstrumentation()).conf52 { account, rawId ->
            val intent = PlaybackIntents.playTrack(context, account.id, rawId, DisposableServerProbe.CANARY_TITLE)
                .setClassName(context, TvPlaybackActivity::class.java.name)
            ActivityScenario.launch<TvPlaybackActivity>(intent)
        }
    }
}
