package com.legitimateapps.dulcet.tv

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibrarySearchPublication
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRow
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.SearchSource
import com.legitimateapps.dulcet.search.SearchSourceHandle
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Results that arrive after the search screen has composed and before its list is measured are
 * drawn, not a crash. The list's items are read when the list measures, which can be later than the
 * screen's own composition: a publication landing in between (on a device, the reader's answer to
 * the query) once met focus requesters sized for the previous, empty publication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvSearchResultsArrivalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resultsPublishedBetweenCompositionAndMeasureAreDrawn() {
        val source = ManualSource()
        val presenter = SearchPresenter(SearchAccount("provider::opaque", "https://music.example.invalid", "listener", "pw", false), source)
        var published = 0
        compose.setContent {
            MaterialTheme {
                TvSearchScreen(presenter) {}
            }
        }
        compose.waitForIdle()
        // Frames are held: the publication reaches the screen's state, and the next layout pass
        // measures the list, with no recomposition of the screen in between.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { source.publish("echo", RESULTS); published++ }
        compose.waitForIdle()
        compose.onNodeWithTag("search.results").fetchSemanticsNode()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(1, published, "The publication under test was delivered once")
        assertEquals(RESULTS, presenter.state.value.rows.size)
        compose.onNodeWithTag("search.result.0").assertTextContains("Echo 0")
    }

    private class ManualSource : SearchSource {
        private var listener: ((AndroidLibrarySearchPublication) -> Unit)? = null
        private var sequence = 0

        fun publish(query: String, count: Int) {
            val rows = (0 until count).map { index ->
                AndroidLibrarySearchRow(
                    SearchResultItem(
                        id = ProviderItemId("provider::opaque", "item-$index"), type = SearchResultType.Album, title = "Echo $index",
                        credits = emptyList(), albumTitle = null, year = null, duration = null, discNumber = null,
                        trackNumber = null, sourceContainer = null, mediaSourceId = null, artworkKey = null,
                    ),
                    AndroidLibrarySearchRowSource.Server, null, null, null,
                )
            }
            listener?.invoke(AndroidLibrarySearchPublication(query, ++sequence, AndroidLibrarySearchScope.ServerAndDevice, rows))
        }

        override fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle {
            this.listener = listener
            return object : SearchSourceHandle {
                override fun updateQuery(text: String) = Unit
                override fun refresh() = Unit
                override fun close() = Unit
            }
        }
    }

    private companion object {
        const val RESULTS = 3
    }
}
