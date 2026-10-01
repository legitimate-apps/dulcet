package com.legitimateapps.dulcet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * CONF-84's rating on a phone emulator against the disposable server (spec §16.20): the production
 * play entry plays a song, the full player's stars show its rating as this device knows it —
 * unknown, for a song never seen before — and a touch on the fourth star reaches the server, read
 * back from the server's own `userRating`. The same star touched again sends 0, which the server
 * takes as no rating, so the proof ends with the fixture as it found it.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorRatingProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun thePlayersStarsRateThePlayingSongOnTheServerAndTheShownStarClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val rawId = probe.songId(RATED_TITLE)
        probe.clearRating(rawId)
        awaitQueuedBroadcastsDelivered()
        connectSavedAccount(context, probe)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        PlaybackObserver(context).use { observer ->
            val intent = PlaybackIntents.playTrack(context, account.id, rawId, RATED_TITLE)
                .setClassName(context, PLAYBACK_ENTRY_ALIAS)
            ActivityScenario.launch<MainActivity>(intent).use {
                observer.bind()
                observer.awaitCurrent(rawId) { compose.mainClock.advanceTimeByFrame() }
                awaitNode("the full player or the now-playing bar") { exists("player.rating") || exists("player.mini") }
                if (!exists("player.rating")) compose.onNodeWithTag("player.mini").performClick()
                awaitNode("the player's stars") { exists("player.rating") }
                check(state() == "Rating unknown") { "A song this device has never seen must show an unknown rating, not ${state()}" }

                tapStar(4)
                awaitNode("four stars shown") { state() == "4 of 5 stars" }
                await("the server to hold the rating") { probe.userRating(rawId) == 4 }
                check(state() == "4 of 5 stars") { "The saved rating must stay: ${state()}" }

                tapStar(4)
                awaitNode("the shown star, touched again, to take the rating away") { state() == "Not rated" }
                await("the server to let it go") { probe.userRating(rawId) == 0 }
                observer.stopPlayback()
                println("ANDROID EMULATOR RATING OBSERVED surface=phone touched=4 server-userRating=4 cleared-by-zero=true")
            }
        }
    }

    /** A touch on star [star] of five, where a finger lands on it. */
    private fun tapStar(star: Int) {
        compose.onNodeWithTag("player.rating").performTouchInput { click(Offset(width * (star - 0.5f) / 5f, height / 2f)) }
        compose.waitForIdle()
    }

    private fun state(): String? = runCatching<String?> {
        val config = compose.onNodeWithTag("player.rating").fetchSemanticsNode().config
        if (SemanticsProperties.StateDescription in config) config[SemanticsProperties.StateDescription] else null
    }.getOrNull()

    private fun exists(tag: String) = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag))
        .fetchSemanticsNodes().isNotEmpty()

    private fun awaitNode(what: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) =
        compose.waitUntil(what, timeoutMillis) { runCatching(condition).getOrDefault(false) }
}
