package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidQueueSource
import com.legitimateapps.dulcet.core.AndroidTrack
import com.legitimateapps.dulcet.core.AudioContainer
import com.legitimateapps.dulcet.library.QueueSourceOf
import com.legitimateapps.dulcet.library.canBeQueued
import com.legitimateapps.dulcet.library.queueSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.legitimateapps.dulcet.shared.R as SharedR

/**
 * Queue editing on the phone (spec §14.1): Play Next and Add to Queue on a track row's menu, and in
 * the Up Next sheet Move Up, Move Down and Remove for the entries after the playing one -- in a menu
 * and as accessibility actions -- and Clear. A refused edit is said.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneQueueEditingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val resources = RuntimeEnvironment.getApplication().resources
    private val actions = Recording()

    @Test fun aTrackRowsMenuPlaysNextAndAddsToQueue() {
        val calls = mutableListOf<String>()
        row(track("t1"), TrackQueue({ calls += "next" }, { calls += "last" }))
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onNodeWithTag("album.track.0.menu.playNext").performClick()
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onNodeWithTag("album.track.0.menu.addToQueue").performClick()
        assertEquals(listOf("next", "last"), calls)
        // The download item #171 put there is still beside them.
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onNodeWithTag("album.track.0.menu.download").assertExists()
    }

    /** What is added is what a play would queue: a track that cannot play now offers neither. */
    @Test fun aTrackThatCannotPlayNowOffersNoQueueItems() {
        val offline = track("t1", AndroidLibraryPlayability.UnavailableOffline)
        assertFalse(offline.canBeQueued())
        assertFalse(track("t2").copy(title = " ").canBeQueued(), "a track with no title to show cannot be queued")
        assertTrue(track("t3").canBeQueued())
        row(offline, TrackQueue({ error("not offered") }, { error("not offered") }))
        compose.onNodeWithTag("album.track.0.menu").performClick()
        compose.onAllNodesWithTag("album.track.0.menu.playNext").assertCountEquals(0)
        compose.onAllNodesWithTag("album.track.0.menu.addToQueue").assertCountEquals(0)
    }

    /** A track added on its own says it came from its album, as the Apple shells attribute it, or else the library. */
    @Test fun anAddedTrackComesFromItsAlbumOrElseTheLibrary() {
        assertEquals(QueueSourceOf(AndroidQueueSource.Album, "Album", "album"), track("t1").queueSource(null, "Library"))
        val loose = track("t1").copy(albumRawId = null, albumTitle = null)
        assertEquals(QueueSourceOf(AndroidQueueSource.Library, "Library", null), loose.queueSource(null, "Library"))
        val shownOn = AndroidLibraryItem.Album(rawId = "shown", title = "Shown On", artistName = null, artistRawId = null, year = null,
            genre = null, durationMilliseconds = null, songCount = null, artworkKey = null, favourite = null, rating = null,
            playCount = null, detailComplete = true)
        assertEquals(QueueSourceOf(AndroidQueueSource.Album, "Shown On", "shown"), loose.queueSource(shownOn, "Library"))
    }

    /**
     * Only entries after the playing one are edited: the playing entry, which the queue will not
     * remove, and those before it have no menu. The first of Up Next cannot move up past the playing
     * entry and the last cannot move down. Moves name the entry and its place in the whole queue.
     */
    @Test fun upNextEntriesMoveAndAreRemovedFromTheirMenus() {
        sheet(fourEntries())
        compose.onAllNodesWithTag("player.upnext.0.menu").assertCountEquals(0)
        compose.onAllNodesWithTag("player.upnext.1.menu").assertCountEquals(0)

        compose.onNodeWithTag("player.upnext.2.menu").performClick()
        compose.onAllNodesWithTag("player.upnext.2.menu.moveUp").assertCountEquals(0)
        compose.onNodeWithTag("player.upnext.2.menu.moveDown").performClick()
        compose.onNodeWithTag("player.upnext.3.menu").performClick()
        compose.onAllNodesWithTag("player.upnext.3.menu.moveDown").assertCountEquals(0)
        compose.onNodeWithTag("player.upnext.3.menu.moveUp").performClick()
        compose.onNodeWithTag("player.upnext.3.menu").performClick()
        compose.onNodeWithTag("player.upnext.3.menu.remove").performClick()
        assertEquals(listOf("move=e3>3", "move=e4>2", "remove=e4"), actions.calls)
    }

    /** TalkBack cannot drag and a menu is one more step: the same edits are actions on the row. */
    @Test fun upNextEditsAreAccessibilityActionsOnTheRow() {
        sheet(fourEntries())
        assertEquals(listOf(string(SharedR.string.queue_move_down), string(SharedR.string.queue_remove)), actionLabels(2))
        assertEquals(listOf(string(SharedR.string.queue_move_up), string(SharedR.string.queue_remove)), actionLabels(3))
        assertEquals(emptyList(), actionLabels(1), "the playing entry has no edits")
        customAction(3, string(SharedR.string.queue_move_up))
        customAction(2, string(SharedR.string.queue_remove))
        assertEquals(listOf("move=e4>2", "remove=e3"), actions.calls)
    }

    /** With no playing entry the whole queue is Up Next, as the core counts it. */
    @Test fun withNothingPlayingEveryEntryIsUpNext() {
        sheet(fourEntries().copy(currentIndex = null))
        compose.onNodeWithTag("player.upnext.0.menu").performClick()
        compose.onAllNodesWithTag("player.upnext.0.menu.moveUp").assertCountEquals(0)
        compose.onNodeWithTag("player.upnext.0.menu.moveDown").performClick()
        assertEquals(listOf("move=e1>1"), actions.calls)
    }

    @Test fun clearRemovesUpNextAndARefusalIsSaid() {
        actions.accepts = false
        sheet(fourEntries())
        compose.onNodeWithTag("player.upnext.clear").performClick()
        assertEquals(listOf("clear"), actions.calls)
        assertEquals(string(SharedR.string.queue_edit_refused), ShadowToast.getTextOfLatestToast())
    }

    @Test fun clearIsNotOfferedWhenNothingFollowsThePlayingEntry() {
        sheet(fourEntries().copy(currentIndex = 3))
        compose.onAllNodesWithTag("player.upnext.clear").assertCountEquals(0)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    private fun string(id: Int) = resources.getString(id)

    private fun actionLabels(position: Int): List<String> =
        compose.onNodeWithTag("player.upnext.$position").fetchSemanticsNode().config
            .getOrElse(SemanticsActions.CustomActions) { emptyList() }.map { it.label }

    private fun customAction(position: Int, label: String) {
        val action = compose.onNodeWithTag("player.upnext.$position").fetchSemanticsNode().config
            .getOrElse(SemanticsActions.CustomActions) { emptyList() }.single { it.label == label }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    private fun sheet(state: AndroidPlaybackState) {
        compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column { UpNextList(null, state, actions) } } }
        compose.waitForIdle()
    }

    private fun fourEntries() = AndroidPlaybackState(
        title = "Track 2", phase = "Playing", playbackSessionId = "s", playWhenReady = true,
        queue = (1..4).map { AndroidQueueEntry("e$it", AndroidTrack("p", "t$it", "Track $it", "Artist", durationMilliseconds = 60_000)) },
        currentIndex = 1,
    )

    private fun row(track: AndroidLibraryItem.Track, queue: TrackQueue) {
        compose.setContent {
            MaterialTheme {
                TrackRow(track, 1, false, Modifier.testTag("album.track.0"), onUnavailable = {},
                    onFavourite = {}, favouriteTag = "album.track.0.favourite", onAddToPlaylist = {},
                    download = TrackDownload(null, onDownload = {}, onRemove = {}), queue = queue) {}
            }
        }
    }

    private fun track(rawId: String, playability: AndroidLibraryPlayability = AndroidLibraryPlayability.Streamable) =
        AndroidLibraryItem.Track(
            rawId = rawId, title = "Title $rawId", albumRawId = "album", albumTitle = "Album", artistName = "Artist",
            artistRawId = null, discNumber = 1, trackNumber = 1, durationMilliseconds = 30_000, sourceContainer = AudioContainer.Flac,
            artworkKey = null, favourite = false, rating = null, playCount = null,
            playability = playability, metadataMissing = false,
        )

    private class Recording : UpNextActions {
        val calls = mutableListOf<String>()
        var accepts = true
        override fun jumpTo(queueEntryId: String) { calls += "jump=$queueEntryId" }
        override fun moveEntry(queueEntryId: String, toIndex: Int): Boolean { calls += "move=$queueEntryId>$toIndex"; return accepts }
        override fun removeEntry(queueEntryId: String): Boolean { calls += "remove=$queueEntryId"; return accepts }
        override fun clearUpcoming(): Boolean { calls += "clear"; return accepts }
    }
}
