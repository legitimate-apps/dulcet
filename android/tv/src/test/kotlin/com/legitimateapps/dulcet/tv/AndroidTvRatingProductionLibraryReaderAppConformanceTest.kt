package com.legitimateapps.dulcet.tv

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.library.LibraryObservation
import com.legitimateapps.dulcet.library.LibraryObservationState
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import com.legitimateapps.dulcet.search.conformance.ProductionLibraryEnvironment
import com.legitimateapps.dulcet.search.conformance.songId
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CONF-84's rating on the TV (spec §16.20), against the disposable server, through the production
 * `TvNowPlaying`, session and reader: the TV rates the playing track from its More panel's stars with
 * the remote's D-pad alone — from the focus the player itself gives Play/Pause, UP to the scrubber,
 * DOWN to the secondary row's More button, and DOWN the panel to the stars, LEFT/RIGHT across them
 * and the centre key; no focus is placed by the test. The
 * player has a playback controller (at an unreachable endpoint: nothing here plays) because the
 * transport, and so the player's own first focus, exists only with one. The library screen's
 * session is started beside it, as the TV app's library activity does, so the reader the player
 * sends through is online. The server's own `userRating` is read back.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w960dp-h540dp")
class AndroidTvRatingProductionLibraryReaderAppConformanceTest {
    private val environment = ProductionLibraryEnvironment()
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)

    private var library: LibrarySession? = null
    private var controller: AndroidPlaybackController? = null

    @After fun closeLibrary() {
        library?.close()
        controller?.close()
    }

    private fun account(): SearchAccount {
        val stored = checkNotNull(AndroidAccountCredentialStore(RuntimeEnvironment.getApplication()).load()) { "setup: no account" }
        return SearchAccount(stored.id, stored.serverUrl, stored.username, stored.password, stored.allowLocalHttp)
    }

    private fun playing(rawId: String, title: String) = AndroidPlaybackState(
        title = title, phase = "Playing", positionMilliseconds = 0, durationMilliseconds = 31_000, seekable = true,
        artist = "Dulcet Fixtures", queueEntryId = "q1", playbackSessionId = "s",
        queue = listOf(AndroidQueueEntry("q1", AndroidTrack(account().providerInstanceId, rawId, title, "Dulcet Fixtures"))),
        currentIndex = 0,
    )

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(60_000) {
                shadowOf(Looper.getMainLooper()).idle()
                runCatching(condition).getOrDefault(false)
            }
        } catch (timeout: Throwable) {
            throw AssertionError("Timed out waiting for $what; requests=${environment.proxy.log().map { it.endpoint }}", timeout)
        }
    }

    private fun selected(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private fun focused(tag: String): Boolean = runCatching {
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
    }.getOrDefault(false)

    /** One press of a remote key, delivered to whatever holds focus. */
    private fun remote(key: Key) {
        // More opens a second window; dispatch to the control whose focus the remote is following.
        val tag = listOf("tv.listening.autoplay", "tv.listening.sleep", "tv.listening.crossfade")
            .plus((1..5).map { "tv.player.rating.$it" })
            .plus(listOf("tv.player.scrubber", "tv.player.shuffle", "tv.player.previous", "tv.player.playpause",
                "tv.player.next", "tv.player.repeat", "tv.player.queue", "tv.player.favourite", "tv.player.lyrics", "tv.listening.open"))
            .firstOrNull(::focused) ?: error("No player or More-panel control holds focus")
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /**
     * Star [star], reached with the D-pad from the player's own first focus: Play/Pause, UP to the
     * scrubber, back DOWN to the transport and the secondary row's More button, whose panel holds the
     * stars, then LEFT/RIGHT to [star].
     */
    private fun dpadToStar(star: Int) {
        await("the player's own first focus on Play/Pause") { focused("tv.player.playpause") }
        remote(Key.DirectionUp)
        assertTrue(focused("tv.player.scrubber"),
            "UP from Play/Pause reaches the scrubber; reconnect-focused=${focused("tv.player.reconnect")}")
        remote(Key.DirectionDown)
        assertTrue(
            listOf("tv.player.shuffle", "tv.player.previous", "tv.player.playpause",
                "tv.player.next", "tv.player.repeat", "tv.player.queue").any(::focused),
            "DOWN from the scrubber returns to the transport")
        remote(Key.DirectionDown)
        var guard = 0
        while (!focused("tv.listening.open") && guard++ < 4) remote(Key.DirectionRight)
        assertTrue(focused("tv.listening.open"), "the secondary row ends on More")
        remote(Key.DirectionCenter)
        await("the listening options panel on Autoplay") { focused("tv.listening.autoplay") }
        guard = 0
        while ((1..5).none { focused("tv.player.rating.$it") } && guard++ < 6) remote(Key.DirectionUp)
        var at = (1..5).firstOrNull { focused("tv.player.rating.$it") }
        assertTrue(at != null, "UP from Autoplay reaches the stars")
        while (at!! < star) { remote(Key.DirectionRight); at++ }
        while (at > star) { remote(Key.DirectionLeft); at-- }
        compose.onNodeWithTag("tv.player.rating.$star").assertIsFocused()
    }

    @Test fun conf84TheTvRatesThePlayingTrackWithTheRemoteAndZeroClearsIt() {
        val song = environment.server.songId(RATED_TRACK)
        assertEquals(0, environment.server.songRating(song), "setup: the run starts with the song unrated")
        // Keep startup in Connecting until the player has painted that state. The notice adds a
        // real D-pad stop between Play/Pause and the scrubber; stars and first focus appear before
        // the reader's reconnect finishes, so neither alone means the connected layout is ready.
        environment.proxy.hold { it.endpoint == "getScanStatus" }
        startLibrary()
        val player = AndroidPlaybackController(RuntimeEnvironment.getApplication(),
            PlaybackEndpointAccount("provider:tv-rating", "http://127.0.0.1:9", "u", "p", true))
        controller = player
        compose.setContent { TvNowPlaying(account(), playing(song, RATED_TRACK), player) }
        await("the player's More button shown") { compose.onAllNodesWithTag("tv.listening.open").fetchSemanticsNodes().isNotEmpty() }
        await("the player's connecting notice") {
            compose.onAllNodesWithTag("tv.player.reconnect").fetchSemanticsNodes().isNotEmpty()
        }
        println("CONF-84 SETUP OBSERVED connecting-reconnect-shown=true")
        environment.proxy.release()
        await("the player's connected layout, with no Reconnect stop above Play/Pause") {
            compose.onAllNodesWithTag("tv.player.reconnect").fetchSemanticsNodes().isEmpty()
        }
        val mark = environment.proxy.log().size
        dpadToStar(4)
        remote(Key.DirectionCenter)
        await("four stars to fill with the press") { (1..4).all { selected("tv.player.rating.$it") } && !selected("tv.player.rating.5") }
        await("the server to hold the rating") { environment.server.songRating(song) == 4 }
        val sends = environment.proxy.since(mark).filter { it.endpoint == "setRating" }
        assertEquals(listOf(song to "4"), sends.map { it.parameters["id"] to it.parameters["rating"] }, "one setRating, for that song")
        assertTrue((1..4).all { selected("tv.player.rating.$it") }, "the saved rating stays")

        assertTrue(focused("tv.player.rating.4"), "focus stays on the star the centre key pressed")
        remote(Key.DirectionCenter)
        await("the shown star, pressed again, to take the rating away") { (1..5).none { selected("tv.player.rating.$it") } }
        await("the server to let it go") { environment.server.songRating(song) == 0 }
        assertEquals("0", environment.proxy.since(mark).last { it.endpoint == "setRating" }.parameters["rating"])
        println("CONF-84 RATING OBSERVED androidtv dpad=up,down,down,more,down,left/right,centre rated=4 server-userRating=4 cleared-by-zero=true")
    }

    private fun startLibrary(): LibrarySession {
        val session = LibrarySession(RuntimeEnvironment.getApplication(), account(), foreground = true)
        library = session
        session.start()
        return session
    }

    private companion object {
        const val RATED_TRACK = "Thirty One Seconds"
    }
}
