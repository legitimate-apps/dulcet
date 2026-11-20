package com.legitimateapps.dulcet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.search.*
import com.legitimateapps.dulcet.search.conformance.LibraryDepthSearchFixture
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class MobileGroupedSearchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedRecentSearchWriteIsVisibleAndASuccessfulRetryClearsIt() {
        val source = LibraryDepthSearchFixture().apply { saveFails = true }
        val presenter = SearchPresenter(SearchAccount("depth-search", "https://music.example.invalid", "u", "p", false), source)
        compose.setContent { MobileSearchScreen(presenter, {}) }
        compose.onNodeWithTag("search.recent.0").performClick()
        compose.onNodeWithTag("search.recents.error").assertExists()
        assertTrue(source.committed.isEmpty())
        compose.runOnIdle { source.saveFails = false; presenter.commitQuery() }
        compose.onNodeWithTag("search.recents.error").assertDoesNotExist()
        assertEquals(listOf("Previous search"), source.committed)
        presenter.close()
    }

    @Test fun recentsReplayAndClearAndGroupExpansionKeepsResultIdentity() {
        val source = LibraryDepthSearchFixture()
        val presenter = SearchPresenter(SearchAccount("depth-search", "https://music.example.invalid", "u", "p", false), source)
        val activated = mutableListOf<SearchResultItem>()
        compose.setContent { MobileSearchScreen(presenter, { activated += it }) }
        compose.onNodeWithTag("search.recent.0").performClick()
        compose.waitForIdle()
        assertEquals("Previous search", presenter.state.value.query)
        assertEquals(listOf("Previous search"), source.committed)
        compose.onNodeWithTag("search.result.14").assertDoesNotExist()
        compose.onNodeWithTag("search.seeAll.artist").performClick()
        compose.onNodeWithTag("search.results").performScrollToNode(hasTestTag("search.result.14"))
        compose.onNodeWithTag("search.result.14").performClick()
        assertEquals("Artist-4", activated.single().id.rawId)
        compose.runOnIdle { presenter.updateQuery("") }
        compose.onNodeWithTag("search.recents.clear").performClick()
        compose.onNodeWithTag("search.recent.0").assertDoesNotExist()
        assertTrue(source.cleared)
        presenter.close()
    }

    @Test fun songMenuKeepsItsTouchTargetAndAlignsItsGlyphToThePageGutter() {
        compose.setContent {
            DulcetTheme {
                MobileSearchResult(
                    result = SearchResultItem(
                        id = ProviderItemId("provider", "song"), type = SearchResultType.Track, title = "A song",
                        credits = emptyList(), albumTitle = null, year = null, duration = null, discNumber = null,
                        trackNumber = null, sourceContainer = null, mediaSourceId = null, artworkKey = null,
                    ),
                    index = 0, account = null, deviceOnly = false, unavailableOffline = false,
                    onActivate = {}, onPlay = {}, onQueue = {},
                )
            }
        }
        val row = compose.onNodeWithTag("search.result.0").getUnclippedBoundsInRoot()
        val menu = compose.onNodeWithTag("search.queue.0")
        val visual = menu.getUnclippedBoundsInRoot()
        val glyphEnd = (visual.left + visual.right + DulcetSizes.icon) / 2
        assertEquals(DulcetSpacing.pageGutter.value, (row.right - glyphEnd).value, 1f)
        menu.assertTouchWidthIsEqualTo(DulcetSizes.touchTarget)
            .assertTouchHeightIsEqualTo(DulcetSizes.touchTarget)
            .performClick()
        compose.onNodeWithTag("search.queue.0.menu.playNext").assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun recentSlotsCenterOnTheFirstLineAndKeepTheInsertTouchTargetAtLargeText() {
        val shortQuery = "Song"
        val longQuery = "The complete Threshold Boundary album by Dulcet Fixtures"
        val source = LibraryDepthSearchFixture().apply { committed += listOf(longQuery, shortQuery) }
        val presenter = SearchPresenter(SearchAccount("depth-search", "https://music.example.invalid", "u", "p", false), source)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                DulcetTheme { MobileSearchScreen(presenter, {}) }
            }
        }
        fun centerY(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot().let { (it.top.value + it.bottom.value) / 2 }
        fun firstLineCenterY(text: String, expectedToWrap: Boolean): Float {
            val title = compose.onNodeWithText(text, useUnmergedTree = true)
            val results = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val layout = results.single()
            assertEquals(expectedToWrap, layout.lineCount > 1, "Line wrapping for $text")
            val centerOffset = (layout.getLineTop(0) + layout.getLineBottom(0)) / (2 * layout.layoutInput.density.density)
            return title.getUnclippedBoundsInRoot().top.value + centerOffset
        }

        compose.onNodeWithTag("search.results").performScrollToNode(hasTestTag("search.recent.1"))
        val singleLineCenter = firstLineCenterY(shortQuery, expectedToWrap = false)
        assertEquals(singleLineCenter, centerY("search.recent.history.1"), 1f)
        assertEquals(singleLineCenter, centerY("search.recent.insert.1"), 1f)
        compose.onNodeWithTag("search.results").performScrollToNode(hasTestTag("search.recent.2"))
        val wrappedFirstLineCenter = firstLineCenterY(longQuery, expectedToWrap = true)
        assertEquals(wrappedFirstLineCenter, centerY("search.recent.history.2"), 1f)
        assertEquals(wrappedFirstLineCenter, centerY("search.recent.insert.2"), 1f)
        compose.onNodeWithTag("search.recent.insert.2")
            .assertTouchWidthIsEqualTo(DulcetSizes.touchTarget)
            .assertTouchHeightIsEqualTo(DulcetSizes.touchTarget)
        presenter.close()
    }
}
