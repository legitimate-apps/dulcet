package com.legitimateapps.dulcet.tv

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidTrack
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
 * `TvNowPlaying`, session and reader: the TV rates the playing track from its Now Playing stars, with
 * the remote. The library screen's session is started beside it, as the TV app's library activity
 * does, so the reader the player sends through is online. The server's own `userRating` is read back.
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

    @After fun closeLibrary() {
        library?.close()
    }

    private fun account(): SearchAccount {
        val stored = checkNotNull(AndroidAccountCredentialStore(RuntimeEnvironment.getApplication()).load()) { "setup: no account" }
        return SearchAccount(stored.id, stored.serverUrl, stored.username, stored.password, stored.allowLocalHttp)
    }

    private fun playing(rawId: String, title: String) = AndroidPlaybackState(
        title = title, phase = "Playing", positionMilliseconds = 0, durationMilliseconds = 31_000,
        artist = "Dulcet Fixtures", queueEntryId = "q1",
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

    /** Star [star], reached with the remote: focus on it and the centre key. */
    private fun pressStar(star: Int) {
        compose.onNodeWithTag("tv.player.rating.$star").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag("tv.player.rating.$star").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    @Test fun conf84TheTvRatesThePlayingTrackWithTheRemoteAndZeroClearsIt() {
        val song = environment.server.songId(RATED_TRACK)
        assertEquals(0, environment.server.songRating(song), "setup: the run starts with the song unrated")
        startLibrary()
        compose.setContent { TvNowPlaying(account(), playing(song, RATED_TRACK), null) }
        await("the stars shown") { compose.onAllNodesWithTag("tv.player.rating.4").fetchSemanticsNodes().isNotEmpty() }
        val mark = environment.proxy.log().size
        pressStar(4)
        await("four stars to fill with the press") { (1..4).all { selected("tv.player.rating.$it") } && !selected("tv.player.rating.5") }
        await("the server to hold the rating") { environment.server.songRating(song) == 4 }
        val sends = environment.proxy.since(mark).filter { it.endpoint == "setRating" }
        assertEquals(listOf(song to "4"), sends.map { it.parameters["id"] to it.parameters["rating"] }, "one setRating, for that song")
        assertTrue((1..4).all { selected("tv.player.rating.$it") }, "the saved rating stays")

        pressStar(4)
        await("the shown star, pressed again, to take the rating away") { (1..5).none { selected("tv.player.rating.$it") } }
        await("the server to let it go") { environment.server.songRating(song) == 0 }
        assertEquals("0", environment.proxy.since(mark).last { it.endpoint == "setRating" }.parameters["rating"])
        println("CONF-84 RATING OBSERVED androidtv remote-centre=4 server-userRating=4 cleared-by-zero=true")
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
