package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibrarySearchPublication
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRow
import com.legitimateapps.dulcet.core.AndroidLibrarySearchRowSource
import com.legitimateapps.dulcet.core.AndroidLibrarySearchScope
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.core.PlaybackEndpointAccount
import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.SearchPresenter
import com.legitimateapps.dulcet.search.SearchSource
import com.legitimateapps.dulcet.search.SearchSourceHandle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Play Next and Add to Queue from a TV search track result (spec §14.1). The row's queue button is the
 * same one a library row offers and opens the same dialog, whose choices queue through the search
 * addition; a result that is not a track, or a track that cannot play now, offers nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvSearchQueueAdditionTest {
    @get:Rule val compose = createComposeRule()

    private val controller = AndroidPlaybackController(RuntimeEnvironment.getApplication(),
        PlaybackEndpointAccount("provider:search", "http://127.0.0.1:9", "u", "p", true))

    @After fun close() { controller.close() }

    /** Only a playable track result offers the queue button; the album row above it does not. */
    @Test fun onlyAPlayableTrackResultOffersTheQueueButton() {
        val account = SearchAccount("provider:search", "http://127.0.0.1:9", "u", "p", true)
        val presenter = SearchPresenter(account, TrackResults())
        compose.setContent {
            MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme()) {
                TvSearchScreen(presenter, account = account, playback = controller) {}
            }
        }
        presenter.updateQuery("echo")
        compose.waitUntil(5_000) { presenter.state.value.results.size == 2 }
        compose.onAllNodesWithTag("search.result.0.queue").assertCountEquals(0)
        compose.onAllNodesWithTag("search.result.1.queue").assertCountEquals(1)
    }

    /** A track that cannot play now offers no queue button, as a library row does (§16.14). */
    @Test fun aTrackThatCannotPlayNowOffersNoQueueButton() {
        val account = SearchAccount("provider:search", "http://127.0.0.1:9", "u", "p", true)
        val presenter = SearchPresenter(account, OfflineTrackResults())
        compose.setContent {
            MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme()) {
                TvSearchScreen(presenter, account = account, playback = controller) {}
            }
        }
        presenter.updateQuery("echo")
        compose.waitUntil(5_000) { presenter.state.value.results.size == 1 }
        compose.onAllNodesWithTag("search.result.0.queue").assertCountEquals(0)
    }

    /** The addition is offered only for a playable track while the playback service is bound (spec §14.1). */
    @Test fun theAdditionIsOnlyOfferedForAPlayableTrackWhileTheServiceIsBound() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(tvSearchAddition(context, null, "p", trackResult(), AndroidLibraryPlayability.Streamable),
            "no playback service bound")
        assertNull(tvSearchAddition(context, controller, null, trackResult(), AndroidLibraryPlayability.Streamable),
            "no provider")
        assertNull(tvSearchAddition(context, controller, "p", albumResult(), null), "an album is not a track")
        assertNull(tvSearchAddition(context, controller, "p", trackResult(), AndroidLibraryPlayability.UnavailableOffline),
            "offline, it cannot play")
        assertNotNull(tvSearchAddition(context, controller, "p", trackResult(), AndroidLibraryPlayability.Streamable))
    }

    /** The search row's queue button opens the same Play Next and Add to Queue dialog a library row's does. */
    @Test fun theQueueButtonOpensTheSameChoicesAndTheyQueueThroughTheAddition() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = androidx.tv.material3.darkColorScheme()) {
                TvSearchResult(
                    result = trackResult(),
                    note = null,
                    index = 0,
                    account = null,
                    addition = TvQueueAddition("Echo", { calls += "next" }, { calls += "last" }),
                    focusRequester = remember { FocusRequester() },
                    queryFocusRequester = null,
                    onActivate = {},
                )
            }
        }
        compose.onNodeWithTag("search.result.0.queue").performSemanticsAction(SemanticsActions.RequestFocus)
        key("search.result.0.queue", Key.DirectionCenter)
        assertTrue(focused("queue.add.playNext"), "the centre key opens Play Next and Add to Queue, the first focused")
        key("queue.add.playNext", Key.DirectionCenter)
        assertEquals(listOf("next"), calls)
        compose.onAllNodesWithTag("queue.add").assertCountEquals(0)
        assertTrue(focused("search.result.0.queue"), "focus returns to the button")

        key("search.result.0.queue", Key.DirectionCenter)
        key("queue.add.playNext", Key.DirectionDown)
        key("queue.add.addToQueue", Key.DirectionCenter)
        assertEquals(listOf("next", "last"), calls)
    }

    private fun key(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focused(tag: String): Boolean = runCatching {
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
    }.getOrDefault(false)

    private fun trackResult() = SearchResultItem(
        id = ProviderItemId("provider:search", "track::a9-opaque"),
        type = SearchResultType.Track,
        title = "Echo track",
        credits = emptyList(),
        albumTitle = null,
        year = null,
        duration = 180.seconds,
        discNumber = null,
        trackNumber = null,
        sourceContainer = AudioContainer.Mp3,
        mediaSourceId = null,
        artworkKey = null,
    )

    private fun albumResult() = SearchResultItem(
        id = ProviderItemId("provider:search", "album::7f-opaque"),
        type = SearchResultType.Album,
        title = "Echo album",
        credits = emptyList(),
        albumTitle = null,
        year = null,
        duration = null,
        discNumber = null,
        trackNumber = null,
        sourceContainer = null,
        mediaSourceId = null,
        artworkKey = null,
    )

    private class TrackResults : SearchSource {
        private val rows = listOf(
            row("album::7f-opaque", SearchResultType.Album, null),
            row("track::a9-opaque", SearchResultType.Track, AndroidLibraryPlayability.Streamable),
        )

        override fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle = object : SearchSourceHandle {
            private var sequence = 0
            override fun updateQuery(text: String) {
                listener(AndroidLibrarySearchPublication(text, ++sequence, AndroidLibrarySearchScope.ServerAndDevice, rows))
            }
            override fun refresh() = Unit
            override fun close() = Unit
        }
    }

    private class OfflineTrackResults : SearchSource {
        private val rows = listOf(row("track::off-opaque", SearchResultType.Track, AndroidLibraryPlayability.UnavailableOffline))

        override fun open(listener: (AndroidLibrarySearchPublication) -> Unit): SearchSourceHandle = object : SearchSourceHandle {
            private var sequence = 0
            override fun updateQuery(text: String) {
                listener(AndroidLibrarySearchPublication(text, ++sequence, AndroidLibrarySearchScope.ServerAndDevice, rows))
            }
            override fun refresh() = Unit
            override fun close() = Unit
        }
    }

    private companion object {
        fun row(rawId: String, type: SearchResultType, playability: AndroidLibraryPlayability?): AndroidLibrarySearchRow =
            AndroidLibrarySearchRow(
                SearchResultItem(
                    id = ProviderItemId("provider:search", rawId),
                    type = type,
                    title = "Echo $rawId",
                    credits = emptyList(),
                    albumTitle = null,
                    year = null,
                    duration = if (type == SearchResultType.Track) 180.seconds else null,
                    discNumber = null,
                    trackNumber = null,
                    sourceContainer = if (type == SearchResultType.Track) AudioContainer.Mp3 else null,
                    mediaSourceId = null,
                    artworkKey = null,
                ),
                AndroidLibrarySearchRowSource.Server,
                null,
                null,
                playability,
            )
    }
}
