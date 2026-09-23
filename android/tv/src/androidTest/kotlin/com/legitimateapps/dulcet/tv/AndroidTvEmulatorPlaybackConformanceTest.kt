package com.legitimateapps.dulcet.tv

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
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
 * The TV app on an Android TV emulator: the production play entry opens the lean-back player,
 * the remote's pause and play keys control it, playback survives Home, and the server records
 * exactly one play.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorPlaybackConformanceTest {
    @Test fun remoteControlledPlaybackSurvivesHomeAndTheServerRecordsExactlyOnePlay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        val probe = DisposableServerProbe.fromInstrumentation()
        val rawId = probe.songId(DisposableServerProbe.CANARY_TITLE)
        val before = probe.playCount(rawId)
        connectSavedAccount(context, probe)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        PlaybackObserver(context).use { observer ->
            val intent = PlaybackIntents.playTrack(context, account.id, rawId, DisposableServerProbe.CANARY_TITLE)
                .setClassName(context, TvPlaybackActivity::class.java.name)
            ActivityScenario.launch<TvPlaybackActivity>(intent).use { activity ->
                observer.bind()
                requireMediaTimeAdvances(observer, "foreground")

                // Remote control: the pause key must stop media time, and play must restart it.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                // Paused means the same session, mid-song, within moments of the key. A song that
                // simply ran out also stops being "wanted", and must not pass for a working pause.
                await("the remote's pause to take effect", timeoutMillis = 5_000) {
                    observer.state().let { !it.playWhenReady && it.hasSession && it.phase == "Paused" }
                }
                val paused = observer.state().positionMilliseconds
                check(paused < 20_000) { "Pause observed too late to be the remote's: ${paused}ms into a 31s song" }
                Thread.sleep(2_500)
                val stillPaused = observer.state().positionMilliseconds
                check(stillPaused - paused < 300) { "Media time advanced while paused: $paused -> $stillPaused" }
                check(observer.state().hasSession) { "The session ended while paused" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
                val resumed = requireMediaTimeAdvances(observer, "after remote play")

                observer.unbind()
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                await("the player to be backgrounded") { activity.state == Lifecycle.State.CREATED }
                val background = requireMediaTimeAdvances(observer, "background")
                check(observer.foregroundNotificationPosted()) { "Background playback lost its foreground service" }
                requireExactlyOneServerPlay(probe, rawId, before, observer)
                println("ANDROID TV EMULATOR PLAYBACK OBSERVED leanback=true remote-pause-held-ms=${stillPaused - paused} " +
                    "resumed-ms=$resumed background-ms=$background server-plays=${before}->${before + 1}")
            }
        }
    }
}
