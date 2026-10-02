package com.legitimateapps.dulcet

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.DownloadProof
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone app, a real emulator and the disposable server: a download through the production
 * controller and WorkManager is validated and atomically promoted (CONF-51), and with the server
 * unreachable to the app the production play entry plays the identical local bytes (CONF-52). See
 * [DownloadProof].
 */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorDownloadConformanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun conf51ADownloadIsValidatedAndPromotedAtomicallyThroughAWorkManagerTask() {
        DownloadProof(context, DisposableServerProbe.fromInstrumentation()).conf51()
    }

    @Test fun conf52ADownloadedSongPlaysTheIdenticalFileWithTheServerUnreachable() {
        DownloadProof(context, DisposableServerProbe.fromInstrumentation()).conf52 { account, rawId ->
            val intent = PlaybackIntents.playTrack(context, account.id, rawId, "")
                .setClassName(context, PLAYBACK_ENTRY_ALIAS)
            ActivityScenario.launch<MainActivity>(intent)
        }
    }
}
