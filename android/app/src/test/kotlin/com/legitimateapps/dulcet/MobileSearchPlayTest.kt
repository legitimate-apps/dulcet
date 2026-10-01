package com.legitimateapps.dulcet

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertCountEquals
import com.legitimateapps.dulcet.core.AndroidQueueInsertion
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.search.SearchActivation
import com.legitimateapps.dulcet.search.SearchPresenter
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class MobileSearchPlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aTrackResultPlaysFromItsButtonAndFromItsRow() {
        val app = RuntimeEnvironment.getApplication()
        val account = SearchAccount("provider::opaque", "https://music.example.invalid", "u", "p", false)
        val presenter = SearchPresenter(account, RankedMergedFixtureSearchSource())
        val played = mutableListOf<SearchResultItem>()
        val activation = SearchActivation(app, openAlbum = {}, openArtist = {})
        compose.setContent { MobileSearchScreen(presenter, activation::activate, account, onPlay = { played += it }) }
        compose.onNodeWithTag("search.query").performTextInput("echo")
        compose.waitUntil(5_000) { presenter.state.value.results.size == 3 }

        // Rank 2 is the track; the album and artist above it offer no Play button.
        compose.onNodeWithTag("search.play.0").assertDoesNotExist()
        compose.onNodeWithTag("search.play.1").assertDoesNotExist()
        compose.onNodeWithTag("search.play.2").performClick()
        assertEquals(listOf("track::a9-opaque"), played.map { it.id.rawId })
        assertNull(shadowOf(app).nextStartedActivity, "Play must not also navigate")

        // The row itself plays too, through the app's own player entry, which also opens the player.
        compose.onNodeWithTag("search.result.2").performClick()
        val routed = shadowOf(app).nextStartedActivity
        assertEquals(PlaybackIntents.ACTION_PLAY_TRACK, routed.action)
        assertEquals("track::a9-opaque", routed.getStringExtra(PlaybackIntents.SONG))
        assertEquals(1, played.size, "The row goes through the player entry, not the button's callback")
        presenter.close()
    }

    /** A track result offers Play Next and Add to Queue beside Play; only the track row does (spec §14.1). */
    @Test fun aTrackResultPlaysNextAndAddsToQueue() {
        val account = SearchAccount("provider::opaque", "https://music.example.invalid", "u", "p", false)
        val presenter = SearchPresenter(account, RankedMergedFixtureSearchSource())
        val queued = mutableListOf<Pair<String, AndroidQueueInsertion>>()
        compose.setContent {
            MobileSearchScreen(presenter, {}, account, onQueue = { result, insertion -> queued += result.id.rawId to insertion })
        }
        compose.onNodeWithTag("search.query").performTextInput("echo")
        compose.waitUntil(5_000) { presenter.state.value.results.size == 3 }

        // Only the track row (rank 2) offers the queue menu.
        compose.onAllNodesWithTag("search.queue.0").assertCountEquals(0)
        compose.onAllNodesWithTag("search.queue.1").assertCountEquals(0)
        compose.onNodeWithTag("search.queue.2").performClick()
        compose.onNodeWithTag("search.queue.2.menu.playNext").performClick()
        compose.onNodeWithTag("search.queue.2").performClick()
        compose.onNodeWithTag("search.queue.2.menu.addToQueue").performClick()
        assertEquals(listOf("track::a9-opaque" to AndroidQueueInsertion.PlayNext, "track::a9-opaque" to AndroidQueueInsertion.AddToQueue), queued)
        presenter.close()
    }

    /** Without a queue callback a track result offers no queue menu. */
    @Test fun withoutAQueueCallbackNoQueueMenuIsOffered() {
        val account = SearchAccount("provider::opaque", "https://music.example.invalid", "u", "p", false)
        val presenter = SearchPresenter(account, RankedMergedFixtureSearchSource())
        compose.setContent { MobileSearchScreen(presenter, {}, account) }
        compose.onNodeWithTag("search.query").performTextInput("echo")
        compose.waitUntil(5_000) { presenter.state.value.results.size == 3 }
        compose.onAllNodesWithTag("search.queue.2").assertCountEquals(0)
        presenter.close()
    }
}
