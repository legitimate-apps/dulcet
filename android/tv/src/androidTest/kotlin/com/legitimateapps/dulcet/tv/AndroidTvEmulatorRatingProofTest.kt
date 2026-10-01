package com.legitimateapps.dulcet.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.PlaybackObserver
import com.legitimateapps.dulcet.emulator.RATED_TITLE
import com.legitimateapps.dulcet.emulator.awaitCurrent
import com.legitimateapps.dulcet.emulator.await
import com.legitimateapps.dulcet.emulator.awaitQueuedBroadcastsDelivered
import com.legitimateapps.dulcet.emulator.clearRating
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import com.legitimateapps.dulcet.emulator.userRating
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CONF-84's rating on an Android TV emulator against the disposable server (spec §16.20), with the
 * remote alone: the production play entry opens the lean-back player with focus on Play/Pause, UP
 * reaches the scrubber, UP the stars, RIGHT and LEFT move across them, and the centre key on the
 * fourth rates the playing song — read back from the server's own `userRating`. The centre key again
 * on the shown star sends 0, which the server takes as no rating. Every action is a key event
 * delivered to the focused window, as a remote's is; the semantics only say where focus is.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorRatingProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun theRemoteRatesThePlayingSongOnTheServerAndTheShownStarClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        val probe = DisposableServerProbe.fromInstrumentation()
        val rawId = probe.songId(RATED_TITLE)
        probe.clearRating(rawId)
        awaitQueuedBroadcastsDelivered()
        connectSavedAccount(context, probe)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        PlaybackObserver(context).use { observer ->
            val intent = PlaybackIntents.playTrack(context, account.id, rawId, RATED_TITLE)
                .setClassName(context, TvPlaybackActivity::class.java.name)
            ActivityScenario.launch<TvPlaybackActivity>(intent).use {
                observer.bind()
                observer.awaitCurrent(rawId)
                awaitNode("the player's own first focus on Play/Pause") { focused("tv.player.playpause") }
                check((1..5).none { selected(it) }) { "A song this device has never seen must show no stars" }
                remote(KeyEvent.KEYCODE_DPAD_UP)
                awaitNode("UP from Play/Pause reaches the scrubber") { focused("tv.player.scrubber") }
                remote(KeyEvent.KEYCODE_DPAD_UP)
                awaitNode("UP from the scrubber reaches the stars") { (1..5).any { focused("tv.player.rating.$it") } }
                var at = (1..5).first { focused("tv.player.rating.$it") }
                while (at < 4) { remote(KeyEvent.KEYCODE_DPAD_RIGHT); at++ }
                while (at > 4) { remote(KeyEvent.KEYCODE_DPAD_LEFT); at-- }
                awaitNode("the fourth star focused") { focused("tv.player.rating.4") }

                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitNode("four stars selected") { (1..4).all { selected(it) } && !selected(5) }
                await("the server to hold the rating") { probe.userRating(rawId) == 4 }

                check(focused("tv.player.rating.4")) { "Focus must stay on the star the centre key pressed" }
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitNode("the shown star, pressed again, to take the rating away") { (1..5).none { selected(it) } }
                await("the server to let it go") { probe.userRating(rawId) == 0 }
                observer.stopPlayback()
                println("ANDROID TV EMULATOR RATING OBSERVED leanback=true dpad=up,up,right/left,centre rated=4 " +
                    "server-userRating=4 cleared-by-zero=true")
            }
        }
    }

    private fun remote(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun focused(tag: String) = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag),
        useUnmergedTree = true).fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun selected(star: Int) = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "tv.player.rating.$star"))
        .fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Selected) { false } }

    private fun awaitNode(what: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) =
        compose.waitUntil(what, timeoutMillis) { runCatching(condition).getOrDefault(false) }
}
