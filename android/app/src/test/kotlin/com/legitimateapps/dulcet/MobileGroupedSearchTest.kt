package com.legitimateapps.dulcet

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.search.*
import com.legitimateapps.dulcet.search.conformance.LibraryDepthSearchFixture
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
