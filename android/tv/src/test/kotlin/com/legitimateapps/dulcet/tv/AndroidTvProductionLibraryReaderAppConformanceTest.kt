package com.legitimateapps.dulcet.tv

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.search.conformance.HostCredentialCipher
import com.legitimateapps.dulcet.search.conformance.LibraryReaderScenarios
import com.legitimateapps.dulcet.search.conformance.ProductionLibraryEnvironment
import com.legitimateapps.dulcet.search.conformance.ReaderAppUi
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Android TV app's library on the reader, end to end: the production activity, `LibraryEntry`'s
 * `LibrarySession` and `AndroidLibraryReader` against the disposable server. The same scenarios as
 * the phone's, driven through the TV's own screens; one test per CONF id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w960dp-h540dp")
class AndroidTvProductionLibraryReaderAppConformanceTest {
    private val environment = ProductionLibraryEnvironment()
    private val compose = createAndroidComposeRule<TvSearchActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)

    /** The TV's one toggle between search and the library, labelled with where it goes. */
    private fun toggleTo(label: String) {
        val toggle = compose.onNodeWithTag("library.open")
        if (hasText(label).matches(toggle.fetchSemanticsNode())) toggle.performClick()
        compose.waitForIdle()
    }

    private val scenarios by lazy {
        LibraryReaderScenarios(compose, environment, object : ReaderAppUi {
            override fun openLibrary() = toggleTo("Library")

            override fun openSearch() = toggleTo("Search")

            override fun backFromAlbum() {
                compose.onNodeWithTag("album.back").performClick()
                compose.waitForIdle()
            }
        }, platform = "androidtv")
    }

    @Test fun conf76RelaunchPaintsTheCacheBeforeAnyAnswerAndANeverOpenedAlbumSaysUnavailableOffline() =
        scenarios.conf76CachedPaintBeforeAnyRequestAndOfflineUnavailable()

    @Test fun conf77ReconnectFlushesReadsTheEpochRevalidatesTheVisibleScreenAndNothingElse() =
        scenarios.conf77ReconnectFlushesReadsTheEpochRevalidatesAndNothingElse()

    @Test fun conf77ForegroundReturnReadsTheEpochAndTheVisibleScreenOnly() =
        scenarios.conf77ForegroundReturnReadsTheEpochAndTheVisibleScreenOnly()

    @Test fun conf79SearchPublishesEachScopeWithSeenCountsOffline() =
        scenarios.conf79SearchPublishesEachScopeWithSeenCountsOffline()

    @Test fun conf84StarShowsWithTheTapSurvivesARevalidationAndAdoptsTheEcho() =
        scenarios.conf84StarShowsWithTheTapSurvivesARevalidationAndAdoptsTheEcho()

    @Test fun conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive() =
        scenarios.conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive()
}
