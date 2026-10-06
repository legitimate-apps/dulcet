package com.legitimateapps.dulcet

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
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
 * The phone's Now Playing names a song downloaded by its id alone, after a relaunch with no network
 * (spec §14.5, §16.13): the title was read with the download and kept with it. [DownloadedTitleScenario]
 * drives the production registry, worker, playback service and controller; only the server and the
 * Keystore are stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w411dp-h891dp-port")
class DownloadedTitleOfflineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scenario = DownloadedTitleScenario(compose, "player.title") { account, state, playback ->
        MaterialTheme { NowPlayingScreen(account, state, playback) {} }
    }

    @Before fun setUp() = scenario.setUp()
    @After fun tearDown() = scenario.tearDown()

    @Test fun aSongDownloadedByIdAloneIsNamedOnNowPlayingAfterAnOfflineRelaunch() = scenario.run()
}
