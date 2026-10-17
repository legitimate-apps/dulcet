package com.legitimateapps.dulcet.tv

import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.search.*
import com.legitimateapps.dulcet.search.conformance.LibraryDepthSearchFixture
import kotlin.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TvGroupedSearchTest {
    @get:Rule val compose = createComposeRule()

    private fun activate(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag(tag).performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun recentsAndSeeAllUseTheSharedPresenterAndPreserveOpaqueIds() {
        val source = LibraryDepthSearchFixture()
        val presenter = SearchPresenter(SearchAccount("depth-search", "https://music.example.invalid", "u", "p", false), source)
        val activated = mutableListOf<SearchResultItem>()
        compose.setContent { MaterialTheme { TvSearchScreen(presenter, onActivate = { activated += it }) } }
        activate("search.recent.0")
        compose.waitForIdle()
        assertEquals("Previous search", presenter.state.value.query)
        compose.onNodeWithTag("search.result.14").assertDoesNotExist()
        activate("search.seeAll.artist")
        compose.onNodeWithTag("search.results").performScrollToNode(hasTestTag("search.result.14"))
        activate("search.result.14")
        assertEquals("Artist-4", activated.single().id.rawId)
        assertEquals(listOf("Previous search", "Previous search"), source.committed)
        presenter.close()
    }
    @Test fun editingANewQueryAfterScrollingRestoresTheFirstGroupForRemoteDown() {
        val source = LibraryDepthSearchFixture()
        val presenter = SearchPresenter(SearchAccount("depth-search", "https://music.example.invalid", "u", "p", false), source)
        compose.setContent { MaterialTheme { TvSearchScreen(presenter, onActivate = {}) } }
        activate("search.recent.0")
        compose.onNodeWithTag("search.results").performScrollToNode(hasTestTag("search.result.3"))
        compose.onNodeWithTag("search.result.10").assertDoesNotExist()
        activate("search.query")
        compose.onNodeWithTag("search.query").performTextReplacement("Next query")
        compose.waitForIdle()
        assertEquals("Next query", presenter.state.value.query)
        compose.onNodeWithTag("search.query").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.result.10").assertIsFocused()
        presenter.close()
    }

}
