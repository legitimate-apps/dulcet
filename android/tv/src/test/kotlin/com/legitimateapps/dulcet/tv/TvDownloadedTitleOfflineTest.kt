package com.legitimateapps.dulcet.tv

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.search.conformance.DownloadedTitleScenario
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lean-back player names a song downloaded by its id alone, after a relaunch with no network
 * (spec §14.5, §16.13). The TV app fills no restored title from the library, so the title can come
 * only from what the download kept. [DownloadedTitleScenario] drives the production registry,
 * worker, playback service and controller; only the server and the Keystore are stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w960dp-h540dp-land-television")
class TvDownloadedTitleOfflineTest {
    @get:Rule val compose = createComposeRule()
    private val scenario = DownloadedTitleScenario(compose, "tv.player.title") { account, state, playback ->
        MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(account, state, Actions(playback)) }
    }

    @Before fun setUp() = scenario.setUp()
    @After fun tearDown() = scenario.tearDown()

    @Test fun aSongDownloadedByIdAloneIsNamedOnTheTvPlayerAfterAnOfflineRelaunch() = scenario.run()

    /** The controller's verbs, as the activity hands them to the player. */
    private class Actions(private val controller: AndroidPlaybackController) : TvPlayerActions {
        override fun togglePlayPause() = controller.togglePlayPause()
        override fun previous() = controller.skipToPrevious()
        override fun next() = controller.next()
        override fun setShuffle(enabled: Boolean) = controller.setShuffle(enabled)
        override fun cycleRepeatMode() = controller.cycleRepeatMode()
        override fun seek(positionMilliseconds: Long) = controller.seek(positionMilliseconds)
        override fun jumpTo(queueEntryId: String) = controller.jumpTo(queueEntryId)
        override fun moveEntry(queueEntryId: String, toIndex: Int) = controller.moveEntry(queueEntryId, toIndex)
        override fun removeEntry(queueEntryId: String) = controller.removeEntry(queueEntryId)
        override fun clearUpcoming() = controller.clearUpcoming()
    }
}
