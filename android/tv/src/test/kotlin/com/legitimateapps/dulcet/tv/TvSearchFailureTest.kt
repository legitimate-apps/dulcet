package com.legitimateapps.dulcet.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibrarySearchPublication
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRow
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.AndroidLibrarySeenCounts
import com.legitimateapps.dulcet.core.DomainError
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
 * What the TV's search says when the core's scope is a failure or offline and there is no row
 * (spec §16.15), with the phone's words and Apple's recovery: Try Again, reached with the D-pad.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvSearchFailureTest {
    @get:Rule val compose = createComposeRule()

    private val seen = AndroidLibrarySeenCounts(artists = 3, albums = 4, tracks = 5)

    @Test fun aTimedOutServerSearchWithNothingOnTheDeviceIsAFailureWithTryAgain() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "thirty one seconds", AndroidLibrarySearchScope.DeviceServerFailed(DomainError.Transport.Timeout, seen))

        compose.onNodeWithTag("search.failed").assertIsDisplayed()
        compose.onNodeWithTag("search.failed.body")
            .assertTextEquals("Nothing on this device matches, and your server didn't answer in time.")
        compose.onNodeWithTag("search.retry").assertIsDisplayed().assertTextContains("Try again")
        compose.onNodeWithTag("search.empty").assertDoesNotExist()
        compose.onNodeWithTag("search.scope").assertDoesNotExist()
        presenter.close()
    }

    /** DOWN from the field reaches Try Again, the centre key runs the search again, and focus is not lost. */
    @Test fun tryAgainIsReachedAndRunWithTheDpad() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "thirty one seconds", AndroidLibrarySearchScope.DeviceServerFailed(DomainError.Transport.Timeout, seen))
        compose.onNodeWithTag("search.query").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("search.query").assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.retry").assertIsFocused()
        assertEquals(0, source.refreshes)

        compose.onNodeWithTag("search.retry").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(1, source.refreshes, "Try Again goes through SearchSourceHandle.refresh")
        compose.onNodeWithTag("search.failed").assertDoesNotExist()
        compose.onNodeWithTag("search.loading").assertIsDisplayed()
        compose.onNodeWithTag("search.query").assertIsFocused()
        presenter.close()
    }

    /** A reader that failed is a failure too: Try Again is reached with the D-pad and reaches the reader. */
    @Test fun aReaderFailureOffersTryAgainReachedAndRunWithTheDpad() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "echo", AndroidLibrarySearchScope.ReaderFailed)
        compose.onNodeWithTag("search.failed.body")
            .assertTextEquals("Nothing on this device matches, and something went wrong on this device.")
        compose.onNodeWithTag("search.scope").assertDoesNotExist()
        compose.onNodeWithTag("search.query").performSemanticsAction(SemanticsActions.RequestFocus)

        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("search.retry").assertIsFocused()
        compose.onNodeWithTag("search.retry").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()

        assertEquals(1, source.refreshes, "Try Again goes through SearchSourceHandle.refresh, which retries the reader's setup")
        compose.onNodeWithTag("search.query").assertIsFocused()
        presenter.close()
    }

    @Test fun anOfflineSearchWithNothingOnTheDeviceSaysThisDeviceHasNoMatchesNotThatMusicIsMissing() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "echo", AndroidLibrarySearchScope.DeviceOffline(seen))

        compose.onNodeWithTag("search.empty.offline").assertTextEquals("No matches on this device")
        compose.onNodeWithTag("search.empty").assertDoesNotExist()
        compose.onNodeWithTag("search.failed").assertDoesNotExist()
        compose.onNodeWithTag("search.scope")
            .assertTextEquals("Searching what's available offline — 4 albums and 5 tracks on this device")
        presenter.close()
    }

    @Test fun onlyAnAnsweredServerSearchSaysNoMatchingMusic() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "echo", AndroidLibrarySearchScope.ServerAndDevice)

        compose.onNodeWithTag("search.empty").assertTextEquals("No matching music")
        compose.onNodeWithTag("search.failed").assertDoesNotExist()
        presenter.close()
    }

    @Test fun deviceRowsAndTheScopeLineStayWhenTheServerFailed() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "echo", AndroidLibrarySearchScope.DeviceServerFailed(DomainError.Transport.Timeout, seen), rows = 2)

        compose.onNodeWithTag("search.result.0").assertTextContains("Echo 0")
        compose.onNodeWithTag("search.scope").assertTextEquals("On this device — your server didn't answer in time")
        compose.onNodeWithTag("search.failed").assertDoesNotExist()
        compose.onNodeWithTag("search.empty").assertDoesNotExist()
        presenter.close()
    }

    private fun open(source: ScriptedSource): SearchPresenter {
        val presenter = SearchPresenter(SearchAccount("provider::opaque", "https://music.example.invalid", "u", "p", false), source)
        compose.setContent { MaterialTheme { TvSearchScreen(presenter) {} } }
        compose.waitForIdle()
        return presenter
    }

    private fun type(presenter: SearchPresenter, source: ScriptedSource, query: String, scope: AndroidLibrarySearchScope, rows: Int = 0) {
        compose.runOnUiThread {
            presenter.updateQuery(query)
            source.publish(query, scope, rows)
        }
        compose.waitForIdle()
    }

    /** A source the test drives: what the core would publish, and a refresh that starts the wait again. */
    private class ScriptedSource : SearchSource {
        private var listener: ((AndroidLibrarySearchPublication) -> Unit)? = null
        private var sequence = 0
        private var query = ""
        var refreshes = 0

        fun publish(query: String, scope: AndroidLibrarySearchScope, count: Int, serverPending: Boolean = false) {
            this.query = query
            val rows = (0 until count).map { index ->
                AndroidLibrarySearchRow(
                    SearchResultItem(
                        id = ProviderItemId("provider::opaque", "item-$index"), type = SearchResultType.Album, title = "Echo $index",
                        credits = emptyList(), albumTitle = null, year = null, duration = null, discNumber = null,
                        trackNumber = null, sourceContainer = null, mediaSourceId = null, artworkKey = null,
                    ),
                    AndroidLibrarySearchRowSource.Device, null, null, null,
                )
            }
            listener?.invoke(AndroidLibrarySearchPublication(query, ++sequence, scope, rows, serverPending))
        }

        override fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle {
            this.listener = listener
            return object : SearchSourceHandle {
                override fun updateQuery(text: String) = Unit
                override fun refresh() {
                    refreshes++
                    publish(query, AndroidLibrarySearchScope.DeviceWhileServerPending, 0, serverPending = true)
                }
                override fun close() = Unit
            }
        }
    }
}
