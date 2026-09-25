package com.legitimateapps.dulcet.tv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
@OptIn(ExperimentalTestApi::class)
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

            /**
             * As a remote does it: focus on [from], one step down with the D-pad, then the centre key.
             * Where the step lands is the app's focus order, so a row that takes two steps to reach
             * or cannot be selected from where it lands fails here. A track row is also left for
             * the next one, which must exist, and re-entered, one step each way (focus order
             * between rows), and must still hold focus after the centre key: the row handles only
             * the key's release, so its press reaches the platform, which moves focus into any
             * focus target inside the row. A row that is two focus targets, one inside the other,
             * therefore loses focus there.
             */
            override fun select(tag: String, from: String) {
                compose.onNodeWithTag(from).performSemanticsAction(SemanticsActions.RequestFocus)
                compose.waitForIdle()
                compose.onNodeWithTag(from).assertIsFocused()
                compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                compose.waitForIdle()
                compose.onNodeWithTag(tag).assertIsFocused()
                val track = TRACK_ROW.matchEntire(tag)
                val next = track?.let { "album.track.${it.groupValues[1].toInt() + 1}" }
                if (next != null) {
                    check(compose.onAllNodesWithTag(next).fetchSemanticsNodes().isNotEmpty()) {
                        "No $next to leave $tag for: the walk between rows needs an album with a track after it"
                    }
                    compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
                    compose.waitForIdle()
                    compose.onNodeWithTag(next).assertIsFocused()
                    compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
                    compose.waitForIdle()
                    compose.onNodeWithTag(tag).assertIsFocused()
                }
                compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
                compose.waitForIdle()
                if (track != null) compose.onNodeWithTag(tag).assertIsFocused()
            }
        }, platform = "androidtv")
    }

    private companion object {
        val TRACK_ROW = Regex("album\\.track\\.(\\d+)")
    }

    @Test fun conf76RelaunchPaintsTheCacheBeforeAnyAnswerAndANeverOpenedAlbumSaysUnavailableOffline() =
        scenarios.conf76CachedPaintBeforeAnyRequestAndOfflineUnavailable()

    @Test fun conf77ReconnectFlushesReadsTheEpochRevalidatesTheVisibleScreenAndNothingElse() =
        scenarios.conf77ReconnectFlushesReadsTheEpochRevalidatesAndNothingElse()

    @Test fun conf77ForegroundReturnReadsTheEpochAndTheVisibleScreenOnly() =
        scenarios.conf77ForegroundReturnReadsTheEpochAndTheVisibleScreenOnly()

    @Test fun conf77EpochCadenceRunsInTheForegroundOnly() =
        scenarios.conf77EpochCadenceRunsInTheForegroundOnly()

    @Test fun conf79SearchPublishesEachScopeWithSeenCountsOffline() =
        scenarios.conf79SearchPublishesEachScopeWithSeenCountsOffline()

    @Test fun conf84StarShowsWithTheTapSurvivesARevalidationCompactsAndAdoptsTheEcho() =
        scenarios.conf84StarShowsWithTheTapSurvivesARevalidationAndAdoptsTheEcho()

    @Test fun conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive() =
        scenarios.conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive()
}
