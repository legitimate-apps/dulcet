package com.legitimateapps.dulcet

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.PlaybackObserver
import com.legitimateapps.dulcet.emulator.await
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import com.legitimateapps.dulcet.emulator.requireExactlyOneServerPlay
import com.legitimateapps.dulcet.emulator.requireMediaTimeAdvances
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone app, a real emulator and the disposable server, end to end: the production play entry
 * starts the production Media3 path, media time advances, the app is sent Home, playback keeps
 * advancing under a foreground media notification, and the server records exactly one play.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorPlaybackConformanceTest {
    @Test fun productionEntryPlaysThroughMedia3AndTheServerRecordsExactlyOnePlay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val rawId = probe.songId(DisposableServerProbe.CANARY_TITLE)
        val before = probe.playCount(rawId)
        connectSavedAccount(context, probe)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        PlaybackObserver(context).use { observer ->
            val intent = PlaybackIntents.playTrack(context, account.id, rawId, DisposableServerProbe.CANARY_TITLE)
                .setClassName(context, PLAYBACK_ENTRY_ALIAS)
            ActivityScenario.launch<MainActivity>(intent).use { activity ->
                observer.bind()
                val foreground = requireMediaTimeAdvances(observer, "foreground")
                await("a foreground media notification") { observer.foregroundNotificationPosted() }
                observer.unbind()
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                await("the activity to be backgrounded") { activity.state == Lifecycle.State.CREATED }
                val background = requireMediaTimeAdvances(observer, "background")
                observer.requireForegroundNotification("background")
                requireExactlyOneServerPlay(probe, rawId, before, observer)
                println("ANDROID EMULATOR PLAYBACK OBSERVED entry=production media3=true " +
                    "foreground-ms=$foreground background-ms=$background server-plays=${before}->${before + 1}")
            }
        }
    }
}
