package com.legitimateapps.dulcet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the phone's search says when the core's scope is a failure or offline and there is no row
 * (spec §16.15), with the same words and the same recovery as Apple. A search the server never
 * answered is never "No matching music", and a failed one can be run again.
 */
@RunWith(RobolectricTestRunner::class)
class MobileSearchFailureTest {
    @get:Rule val compose = createComposeRule()

    private val seen = AndroidLibrarySeenCounts(artists = 3, albums = 4, tracks = 5)

    @Test fun aTimedOutServerSearchWithNothingOnTheDeviceIsAFailureWithTryAgain() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "thirty one seconds", AndroidLibrarySearchScope.DeviceServerFailed(DomainError.Transport.Timeout, seen))

        compose.onNodeWithTag("search.failed").assertIsDisplayed()
        compose.onNodeWithText("Search could not be completed").assertIsDisplayed()
        compose.onNodeWithTag("search.failed.body")
            .assertTextEquals("Nothing on this device matches, and your server didn't answer in time.")
        compose.onNodeWithTag("search.retry").assertIsDisplayed().assertTextContains("Try again")
        compose.onNodeWithTag("search.empty").assertDoesNotExist()
        // The failure says it all; the scope line would repeat it.
        compose.onNodeWithTag("search.scope").assertDoesNotExist()
        presenter.close()
    }

    @Test fun tryAgainRunsTheSearchAgainThroughThePresenterAndTheFailureGivesWayToTheWait() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "thirty one seconds", AndroidLibrarySearchScope.DeviceServerFailed(DomainError.Transport.Timeout, seen))
        assertEquals(0, source.refreshes)

        compose.onNodeWithTag("search.retry").performClick()
        compose.waitForIdle()

        assertEquals(1, source.refreshes, "Try Again goes through SearchSourceHandle.refresh")
        compose.onNodeWithTag("search.failed").assertDoesNotExist()
        compose.onNodeWithTag("search.loading").assertIsDisplayed()
        compose.onNodeWithTag("search.empty").assertDoesNotExist()
        presenter.close()
    }

    @Test fun aServerFailureOfAnotherKindNamesItsKind() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "echo", AndroidLibrarySearchScope.DeviceServerFailed(DomainError.Auth.InvalidCredentials, seen))
        compose.onNodeWithTag("search.failed.body")
            .assertTextEquals("Nothing on this device matches, and your server didn't accept your sign-in.")
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
        compose.onNodeWithTag("search.empty.offline").assertDoesNotExist()
        presenter.close()
    }

    @Test fun beforeAnyPublicationATypedQueryClaimsNothing() {
        val source = ScriptedSource()
        val presenter = open(source)
        compose.runOnUiThread { presenter.updateQuery("echo") }
        compose.waitForIdle()

        compose.onNodeWithTag("search.empty").assertDoesNotExist()
        compose.onNodeWithTag("search.failed").assertDoesNotExist()
        presenter.close()
    }

    /** A reader that failed (its setup, say) is a failure with a Try Again that reaches the reader. */
    @Test fun aReaderFailureWithNothingOnTheDeviceIsAFailureWithTryAgain() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "echo", AndroidLibrarySearchScope.ReaderFailed)

        compose.onNodeWithTag("search.empty").assertDoesNotExist()
        compose.onNodeWithTag("search.failed").assertIsDisplayed()
        compose.onNodeWithTag("search.failed.body")
            .assertTextEquals("Nothing on this device matches, and something went wrong on this device.")
        compose.onNodeWithTag("search.scope").assertDoesNotExist()

        compose.onNodeWithTag("search.retry").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(1, source.refreshes, "Try Again goes through SearchSourceHandle.refresh, which retries the reader's setup")
        presenter.close()
    }

    @Test fun aReaderFailureBesideTheDevicesRowsKeepsThemUnderTheScopeLine() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "echo", AndroidLibrarySearchScope.ReaderFailed, rows = 1)

        compose.onNodeWithTag("search.result.0").assertTextContains("Echo 0")
        compose.onNodeWithTag("search.scope").assertTextEquals("On this device — something went wrong on this device")
        compose.onNodeWithTag("search.failed").assertDoesNotExist()
        presenter.close()
    }

    @Test fun deviceRowsAndTheScopeLineStayWhenTheServerFailed() {
        val source = ScriptedSource()
        val presenter = open(source)
        type(presenter, source, "echo", AndroidLibrarySearchScope.DeviceServerFailed(DomainError.Transport.Timeout, seen), rows = 2)

        compose.onNodeWithTag("search.result.0").assertTextContains("Echo 0")
        compose.onNodeWithTag("search.result.1").assertTextContains("Echo 1")
        compose.onNodeWithTag("search.scope").assertTextEquals("On this device — your server didn't answer in time")
        compose.onNodeWithTag("search.failed").assertDoesNotExist()
        compose.onNodeWithTag("search.empty").assertDoesNotExist()
        presenter.close()
    }

    private fun open(source: ScriptedSource): SearchPresenter {
        val presenter = SearchPresenter(SearchAccount("provider::opaque", "https://music.example.invalid", "u", "p", false), source)
        compose.setContent { MobileSearchScreen(presenter, {}) }
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
