package com.legitimateapps.dulcet

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import com.legitimateapps.dulcet.search.conformance.LyricsPanelScenarios
import com.legitimateapps.dulcet.search.conformance.ProductionLibraryEnvironment
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CONF-42 through the phone's production lyrics panel — the one the player's lyrics sheet shows —
 * with its `LibrarySession` and the process's reader against the disposable server (spec §18.4).
 * Opening the sheet from the player is [AndroidProductionLibraryReaderAppConformanceTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w420dp-h900dp")
class AndroidLyricsProductionLibraryReaderAppConformanceTest {
    private val environment = ProductionLibraryEnvironment()
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)

    private val scenarios = LyricsPanelScenarios(compose, environment, tags = "lyrics", platform = "android") { session, state ->
        LyricsPanel(session, state) {}
    }

    @After fun closeSessions() = scenarios.close()

    @Test fun conf42TheLayerShownFollowsTheDevicesLanguageAndSyncedBeatsUnsynced() =
        scenarios.conf42TheLayerShownFollowsTheDevicesLanguageAndSyncedBeatsUnsynced()

    @Test fun conf42SeenLyricsShowOfflineWithNoRequestAndUnseenOnesSaySo() =
        scenarios.conf42SeenLyricsShowOfflineWithNoRequestAndUnseenOnesSaySo()

    @Test fun conf42WithoutTheExtensionAdvertisedTheLyricsAreReadWithGetLyrics() =
        scenarios.conf42WithoutTheExtensionAdvertisedTheLyricsAreReadWithGetLyrics()
}
