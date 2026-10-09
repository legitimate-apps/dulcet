package com.legitimateapps.dulcet

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
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
 * The phone app's library on the reader, end to end: the production activity, `LibrarySession` and
 * `AndroidLibraryReader` against the disposable server (spec §16.14–§16.20). One test per CONF id,
 * then the session's own reachability handling and album play, one test each.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [HostCredentialCipher::class],
    instrumentedPackages = ["com.legitimateapps.dulcet"], qualifiers = "w420dp-h900dp")
class AndroidProductionLibraryReaderAppConformanceTest {
    private val environment = ProductionLibraryEnvironment()
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)

    private val scenarios by lazy {
        LibraryReaderScenarios(compose, environment, object : ReaderAppUi {
            override fun openLibrary() {
                compose.onNodeWithTag("library.open").performClick()
                compose.waitForIdle()
            }

            override fun openSearch() {
                compose.onNodeWithTag("search.open").performClick()
                compose.waitForIdle()
            }

            override fun backFromAlbum() {
                compose.onNodeWithTag("album.back").performClick()
                compose.waitForIdle()
            }

            override fun select(tag: String, from: String) {
                compose.onNodeWithTag(tag).performClick()
                compose.waitForIdle()
            }

            override fun leaveBrowseView() {
                compose.onNodeWithTag("library.view.home").performClick()
                compose.waitForIdle()
            }

            override fun showListStatus() {
        compose.onNodeWithTag("library.albums").performScrollToIndex(0)
        compose.waitForIdle()
    }

    override fun showAlbum(index: Int) {
                // A finger's scroll: the grid's first item is its header, so card [index] is item index + 1.
                compose.onNodeWithTag("library.albums").performScrollToIndex(index + 1)
                compose.waitForIdle()
            }
        }, platform = "android")
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

    @Test fun aSongsHeartReachesTheServerAndTheFavouritesScreenReadsItBack() =
        scenarios.aSongsHeartReachesTheServerAndTheFavouritesScreenReadsItBack()

    @Test fun aCompletedHomeFavouritesReadIsSharedWithTheFavouritesScreen() =
        scenarios.aSongsHeartReachesTheServerAndTheFavouritesScreenReadsItBack(completeHomeRead = true)

    /** The rating from the song's row menu: Rate…, then a tap on a star, as a finger does. */
    @Test fun conf84RatingShowsWithTheTapReachesTheServerAndZeroClearsIt() =
        scenarios.conf84RatingShowsWithTheTapReachesTheServerAndZeroClearsIt { row, star ->
            compose.onNodeWithTag("$row.menu").performClick()
            compose.onNodeWithTag("$row.menu.rate").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("$row.rating").performTouchInput { click(Offset(width * (star - 0.5f) / 5f, height / 2f)) }
            compose.waitForIdle()
            compose.onNodeWithTag("$row.rating.done").performClick()
            compose.waitForIdle()
        }

    @Test fun conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive() =
        scenarios.conf86HomeRowsPublishIndependentlyAndOneFailureLeavesTheOthersLive()

    @Test fun conf87LookAheadIsBoundedSkipsAConstrainedNetworkAndOpensALookedAheadAlbumWithNoRequest() =
        scenarios.conf87LookAheadIsBoundedSkipsAConstrainedNetworkAndOpensALookedAheadAlbumWithNoRequest()

    @Test fun conf82AScanDuringAPageReadTearsTheWindowAndOnlyTheViewportPagesAreReRead() =
        scenarios.conf82AScanDuringAPageReadTearsTheWindowAndOnlyTheViewportPagesAreReRead()
    @Test fun conf82AWindowSeenUnderAnotherEpochIsRebasedAtItsFirstLiveReadAndNeverExtended() =
        scenarios.conf82AWindowSeenUnderAnotherEpochIsRebasedAtItsFirstLiveReadAndNeverExtended()
    @Test fun conf82WhileTheServerScansPagesAppendUnguardedAndTheScanEndRebasesUnderAnUnchangedStamp() =
        scenarios.conf82WhileTheServerScansPagesAppendUnguardedAndTheScanEndRebasesUnderAnUnchangedStamp()
    @Test fun conf82TheSentinelAndAnAbsentStampAreNoEpochWhateverScanningSaysAndNeverUnchanged() =
        scenarios.conf82TheSentinelAndAnAbsentStampAreNoEpochWhateverScanningSaysAndNeverUnchanged()
    @Test fun conf82AFailedStatusReadIsUnreadAndTheWindowKeepsItsPagesAndLabel() =
        scenarios.conf82AFailedStatusReadIsUnreadAndTheWindowKeepsItsPagesAndLabel()
    @Test fun conf82AWindowWithoutXTotalCountHasAnUnknownTotalAndConfirmsItsEnd() =
        scenarios.conf82AWindowWithoutXTotalCountHasAnUnknownTotalAndConfirmsItsEnd()

    @Test fun aReconnectAnsweredAfterTheNetworkWentAwayLeavesTheLibraryOffline() =
        scenarios.aReconnectAnsweredAfterTheNetworkWentAwayLeavesTheLibraryOffline()

    @Test fun anUnreachableServerTakesTheReaderOfflineAndTryingAgainTellsItTheNetworkIsBack() =
        scenarios.anUnreachableServerTakesTheReaderOfflineAndTryingAgainTellsItTheNetworkIsBack()

    @Test fun aTransientReconnectFailureIsRetriedInTheForegroundOnly() =
        scenarios.aTransientReconnectFailureIsRetriedInTheForegroundOnly()

    @Test fun anUnreachableAnswerArrivingInTheBackgroundStartsNoRead() =
        scenarios.anUnreachableAnswerArrivingInTheBackgroundStartsNoRead()

    @Test fun aTrackTappedPlaysTheAlbumFromThatTrack() = scenarios.aTrackSelectedPlaysTheAlbumFromThatTrack {}

    // ---- Playlists (§18.6) and lyrics (§18.4) --------------------------------------------------------

    @Test fun aPlaylistIsMadeFilledReorderedTrimmedRenamedAndDeletedOnTheServer() =
        scenarios.aPlaylistIsMadeFilledReorderedTrimmedRenamedAndDeletedOnTheServer()

    @Test fun conf90ARemovalOnAViewChangedElsewhereIsRefusedUnwrittenAndTheControlRemovesTheIntendedSong() =
        scenarios.conf90ARemovalOnAViewChangedElsewhereIsRefusedUnwrittenAndTheControlRemovesTheIntendedSong()

    @Test fun conf91AnotherUsersPlaylistIsReadOnlyInTheAppAndTheOwnPlaylistIsTheControl() =
        scenarios.conf91AnotherUsersPlaylistIsReadOnlyInTheAppAndTheOwnPlaylistIsTheControl()

    @Test fun aPlaylistOpensAndPlaysInItsOwnOrderFromTheEntryTapped() =
        scenarios.aPlaylistOpensAndPlaysInItsOwnOrderFromTheEntrySelected {}

    @Test fun theLyricsOfThePlayingTrackAreReadLiveAndShownSynced() =
        scenarios.theLyricsOfThePlayingTrackAreReadLiveAndShownSynced {
            compose.onNodeWithTag("player.mini").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("player.lyrics").performClick()
            compose.waitForIdle()
        }
}
