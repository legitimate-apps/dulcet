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
 * The TV player's lyrics panel (spec §18.4) against the disposable server, through the production
 * `TvNowPlaying`, session and reader. The library screen's session is started beside it, as the TV
 * app's library activity does: Now Playing's own session is never started (it would report the
 * foreground), so the reader it reads through is the one that session brought online.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w960dp-h540dp")
class AndroidTvLyricsProductionLibraryReaderAppConformanceTest {
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

    private fun playing(rawId: String, title: String, position: Long) = AndroidPlaybackState(
        title = title, phase = "Playing", positionMilliseconds = position, durationMilliseconds = 29_000,
        artist = "Dulcet Fixtures", queueEntryId = "q1",
        queue = listOf(AndroidQueueEntry("q1", AndroidTrack(account().providerInstanceId, rawId, title, "Dulcet Fixtures"))),
        currentIndex = 0,
    )

    private fun observed(): LibraryObservationState =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(LibraryObservation)).fetchSemanticsNodes()
            .firstOrNull()?.config?.get(LibraryObservation) ?: LibraryObservationState()

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

    /** The lyrics toggle, reached with the remote: focus on it and the centre key. */
    private fun openLyricsWithTheRemote() {
        compose.onNodeWithTag("tv.player.lyrics").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag("tv.player.lyrics").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun startLibrary(): LibrarySession {
        val session = LibrarySession(RuntimeEnvironment.getApplication(), account(), foreground = true)
        library = session
        session.start()
        return session
    }

    @Test fun theTvPanelShowsThePlayingTracksSyncedLyricsAndLightsTheLineAtMediaTime() {
        val song = environment.server.songId(LYRICS_TRACK)
        startLibrary()
        // Past the first English line (2 s) and before the second (7 s), paused: no interpolation.
        compose.setContent { TvNowPlaying(account(), playing(song, LYRICS_TRACK, 3_000), null) }
        compose.waitForIdle()
        val mark = environment.proxy.log().size
        openLyricsWithTheRemote()
        await("the lyrics read live") {
            observed().lyrics.lastOrNull()?.let { it.trackRawId == song && it.freshness == AndroidLibraryFreshness.Live } == true
        }
        val shown = observed().lyrics.last()
        assertEquals(AndroidLyricsState.Lyrics, shown.state)
        assertTrue(shown.synced, "the synced layer")
        assertEquals("eng", shown.language, "the English layer, for this device's language")
        await("the lines drawn") { compose.onAllNodesWithTag("tv.player.lyrics.line.0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tv.player.lyrics.line.0").assertTextEquals("Dulcet English line one")
        fun lit(index: Int) = compose.onNodeWithTag("tv.player.lyrics.line.$index").fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.Selected) { false }
        assertTrue(lit(0), "the line whose time has come is lit")
        assertTrue(!lit(1), "the next line is not lit before its time")
        val reads = environment.proxy.since(mark).map { it.endpoint to it.parameters["id"] }
        assertTrue(("getLyricsBySongId" to song) in reads, "read through the advertised extension: $reads")
        println("LYRICS OBSERVED androidtv synced=${shown.synced} language=${shown.language} lines=${shown.lineCount} lit=0-at-3000ms")
    }

    @Test fun aTrackWithNoLyricsSaysSoOnTheTv() {
        val song = environment.server.songId(NO_LYRICS_TRACK)
        startLibrary()
        compose.setContent { TvNowPlaying(account(), playing(song, NO_LYRICS_TRACK, 0), null) }
        compose.waitForIdle()
        openLyricsWithTheRemote()
        await("the answer read live") {
            observed().lyrics.lastOrNull()?.let { it.trackRawId == song && it.freshness == AndroidLibraryFreshness.Live } == true
        }
        assertEquals(AndroidLyricsState.None, observed().lyrics.last().state)
        compose.onNodeWithTag("tv.player.lyrics.statement").assertTextEquals("No lyrics for this song.")
        assertTrue(compose.onAllNodesWithTag("tv.player.lyrics.loading").fetchSemanticsNodes().isEmpty(), "no spinner for a song with none")
        println("LYRICS OBSERVED androidtv none=statement")
    }

    private companion object {
        const val LYRICS_TRACK = "Twenty Nine Seconds"
        const val NO_LYRICS_TRACK = "Dulcet Health Probe"
    }
}
