package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.AndroidLibraryItem
import com.legitimateapps.dulcet.core.AndroidLibraryItemsOrder
import com.legitimateapps.dulcet.core.AndroidLibraryItemsState
import com.legitimateapps.dulcet.core.AndroidLibraryPlayability
import com.legitimateapps.dulcet.core.AndroidLibraryPublication
import com.legitimateapps.dulcet.core.AndroidLibraryUnavailableReason
import com.legitimateapps.dulcet.core.AndroidLyricsLine
import com.legitimateapps.dulcet.core.AndroidLyricsPublication
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidOperationFailure
import com.legitimateapps.dulcet.core.AndroidPlaylistChange
import com.legitimateapps.dulcet.core.AndroidPlaylistEditRecord
import com.legitimateapps.dulcet.core.AndroidPlaylistEditResult
import com.legitimateapps.dulcet.core.AndroidPlaylistOutcome
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.core.LyricsLayer
import com.legitimateapps.dulcet.core.LyricsLine
import com.legitimateapps.dulcet.library.LatestAnswer
import com.legitimateapps.dulcet.library.LyricsHighlight
import com.legitimateapps.dulcet.library.highlightAt
import com.legitimateapps.dulcet.library.interpolatedPosition
import com.legitimateapps.dulcet.library.lyricsStatement
import com.legitimateapps.dulcet.library.playlistEditLine
import com.legitimateapps.dulcet.library.playlistOutcomeLine
import com.legitimateapps.dulcet.library.playlistOwnerLine
import com.legitimateapps.dulcet.library.playlistPendingLine
import com.legitimateapps.dulcet.library.playlistQueue
import com.legitimateapps.dulcet.library.playlistQuestionLine
import com.legitimateapps.dulcet.library.PlaylistQuestion
import com.legitimateapps.dulcet.library.playlistView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shared playlist and lyrics presentation (spec §18.6, §18.4): what a playlist page offers and
 * plays, what each edit answer and outcome says, and which lyric line is lit when. Every input is a
 * value the core publishes; these pin the shells' reading of them.
 */
@RunWith(RobolectricTestRunner::class)
class PlaylistAndLyricsPresentationTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    private fun track(id: String, title: String? = id, missing: Boolean = false,
                      playability: AndroidLibraryPlayability = AndroidLibraryPlayability.Streamable) =
        AndroidLibraryItem.Track(id, title, null, null, null, null, null, null, 1_000, null, null, null, null, null, playability, missing)

    private fun playlist(editable: Boolean = true, owner: String? = "listener", pending: Boolean = false, local: Boolean = false) =
        AndroidLibraryItem.Playlist("pl-1", "Mix", 4, null, owner, null, editable = editable, pendingChanges = pending, local = local)

    private fun publication(items: List<AndroidLibraryItem>, state: AndroidLibraryItemsState = AndroidLibraryItemsState.Present) =
        AndroidLibraryPublication(1, AndroidLibraryFreshness.Live, null, items.size, 0, playlist(), items, state,
            AndroidLibraryItemsOrder.Server, null, null)

    // ---- Playlists -----------------------------------------------------------------------------------

    @Test
    fun aPlaylistPlaysInItsOwnOrderFromTheEntryChosenByPositionNotId() {
        // The same song twice: choosing the SECOND starts there, not at the first with that id.
        val items = listOf(track("a"), track("b"), track("a"), track("c"))
        val queue = assertNotNull(publication(items).playlistQueue("provider", 2))
        assertEquals(listOf("a", "b", "a", "c"), queue.tracks.map { it.rawId }, "duplicates kept, in the playlist's order")
        assertEquals(2, queue.start)
        assertEquals("Mix", queue.name)
    }

    @Test
    fun entriesThatCannotPlayAreLeftOutAndAStartOnOneBeginsAtTheNext() {
        val items = listOf(track("a"), track("gone", missing = true), track("off", playability = AndroidLibraryPlayability.UnavailableOffline),
            track("untitled", title = null), track("d"))
        val queue = assertNotNull(publication(items).playlistQueue("provider", 1))
        assertEquals(listOf("a", "d"), queue.tracks.map { it.rawId })
        assertEquals(1, queue.start, "a start on an entry that cannot play begins at the next that can")
        assertNull(publication(listOf(track("gone", missing = true))).playlistQueue("provider", 0), "nothing playable plays nothing")
        assertNull(publication(items, AndroidLibraryItemsState.Loading).playlistQueue("provider", 0), "entries not present play nothing")
    }

    @Test
    fun aPositionalEditIsOfferedOnlyOnTheWholePlaylistAndNamesItsEntriesInOrder() {
        val items = listOf(track("a"), track("b"), track("a"))
        assertEquals(listOf("a", "b", "a"), publication(items).playlistView())
        assertNull(publication(items, AndroidLibraryItemsState.Loading).playlistView())
        assertNull(publication(emptyList(), AndroidLibraryItemsState.Unavailable).playlistView())
    }

    @Test
    fun anotherUsersPlaylistSaysWhoseItIsAndThatItIsReadOnly() {
        assertEquals("sam's playlist · read-only", resources.playlistOwnerLine(playlist(editable = false, owner = "sam")))
        assertEquals("Read-only", resources.playlistOwnerLine(playlist(editable = false, owner = null)))
        assertNull(resources.playlistOwnerLine(playlist()))
        assertEquals("Made on this device — not on your server yet", resources.playlistOwnerLine(playlist(local = true)))
        assertEquals("Not saved to your server yet", resources.playlistPendingLine(playlist(pending = true)))
        assertNull(resources.playlistPendingLine(playlist()))
    }

    @Test
    fun anEditAnswerIsSaidOnlyWhenItDidNotSimplyShow() {
        fun line(record: AndroidPlaylistEditRecord) = resources.playlistEditLine(AndroidPlaylistEditResult(record, null, null))
        assertNull(line(AndroidPlaylistEditRecord.Pending))
        assertNull(line(AndroidPlaylistEditRecord.CompactedAway))
        assertNull(line(AndroidPlaylistEditRecord.Unchanged))
        assertEquals("The playlist changed while you were looking at it. Here it is now — try again.",
            line(AndroidPlaylistEditRecord.StaleView))
        assertEquals("You can't change this playlist.", line(AndroidPlaylistEditRecord.NotEditable))
        // Every other record says something.
        for (record in AndroidPlaylistEditRecord.entries - setOf(AndroidPlaylistEditRecord.Pending,
            AndroidPlaylistEditRecord.CompactedAway, AndroidPlaylistEditRecord.Unchanged)) {
            assertNotNull(line(record), "$record")
        }
        assertEquals("This screen has closed.",
            resources.playlistEditLine(AndroidPlaylistEditResult(null, null, AndroidOperationFailure.Closed)))
    }

    @Test
    fun aQueuedChangeThatDidNotLandSaysSoAndASavedOneSaysNothing() {
        assertNull(resources.playlistOutcomeLine(AndroidPlaylistOutcome.Saved("pl-1", AndroidPlaylistChange.Entries)))
        assertNull(resources.playlistOutcomeLine(AndroidPlaylistOutcome.Created("local-1", "pl-9")))
        assertEquals("This playlist changed on another device, so your change wasn't sent. Showing your server's version.",
            resources.playlistOutcomeLine(AndroidPlaylistOutcome.ChangedElsewhere("pl-1", listOf("a"))))
        assertEquals("Couldn't save a change to this playlist — your server couldn't answer. Showing your server's version.",
            resources.playlistOutcomeLine(AndroidPlaylistOutcome.NotSaved("pl-1", AndroidPlaylistChange.Details, DomainError.Server.Known(0))))
        assertEquals("That change isn't sent yet — your server is busy. It will be sent later.",
            resources.playlistOutcomeLine(AndroidPlaylistOutcome.Held("pl-1", AndroidPlaylistChange.Entries, DomainError.Server.Busy(null))))
        assertEquals("“Road” may already be on your server. Is the one there yours?",
            resources.playlistOutcomeLine(AndroidPlaylistOutcome.PossibleDuplicate("local-1", "Road", listOf("pl-2"))))
        // Several namesakes cannot be told apart here: the words never ask which is theirs.
        assertEquals("2 playlists named “Road” are on your server, and this one may be one of them. " +
            "They can't be told apart here — look in Playlists before you decide.",
            resources.playlistOutcomeLine(AndroidPlaylistOutcome.PossibleDuplicate("local-1", "Road", listOf("pl-2", "pl-3"))))
        assertEquals("This playlist may already be on your server. Is the one there yours?",
            resources.playlistQuestionLine(PlaylistQuestion(PlaylistQuestion.Kind.WhichIsYours, "local-1", null, listOf("pl-2")), null))
    }

    // ---- Lyrics --------------------------------------------------------------------------------------

    private val synced = LyricsLayer("en", true, listOf(LyricsLine("One", 2_000), LyricsLine("Uno", 2_000),
        LyricsLine("Two", 7_000)), 0)

    private fun lyrics(layer: LyricsLayer?, state: AndroidLyricsState = AndroidLyricsState.Lyrics,
                       freshness: AndroidLibraryFreshness = AndroidLibraryFreshness.Live, breakerOpen: Boolean = false) =
        AndroidLyricsPublication("song-1", state, freshness, layer?.synced == true,
            layer?.lines.orEmpty().map { AndroidLyricsLine(it.text, it.startMilliseconds) }, layer?.language, layer, false, breakerOpen, null)

    @Test
    fun theLitLinesAreTheCoresCursorAndTheirGroupAtEveryPosition() {
        val published = lyrics(synced)
        assertEquals(LyricsHighlight(-1, -1, true), published.highlightAt(0), "before the first line nothing is lit")
        assertEquals(LyricsHighlight(0, 1, false), published.highlightAt(2_000), "a line and its translation light together")
        assertEquals(LyricsHighlight(0, 1, false), published.highlightAt(6_999))
        assertEquals(LyricsHighlight(2, 2, false), published.highlightAt(7_000))
        assertEquals(false, LyricsHighlight(-1, -1, true).lights(0))
        assertEquals(true, LyricsHighlight(0, 1, false).lights(1))
        // A seek backwards needs nothing but the new position.
        assertEquals(LyricsHighlight(0, 1, false), published.highlightAt(3_000))
    }

    @Test
    fun plainLyricsLightNothing() {
        val plain = LyricsLayer("en", false, listOf(LyricsLine("Words", null)), 0)
        assertNull(lyrics(plain).highlightAt(5_000))
        assertNull(lyrics(null, AndroidLyricsState.None).highlightAt(5_000))
    }

    @Test
    fun mediaTimeAdvancesBetweenSamplesOnlyWhileProgressingAndNeverPastTheEnd() {
        assertEquals(1_300, interpolatedPosition(1_000, 10_000, 10_300, progressing = true, durationMilliseconds = 60_000))
        assertEquals(1_000, interpolatedPosition(1_000, 10_000, 10_300, progressing = false, durationMilliseconds = 60_000))
        assertEquals(2_000, interpolatedPosition(1_000, 10_000, 15_000, progressing = true, durationMilliseconds = 60_000),
            "a guess never runs more than a second ahead of the last sample")
        assertEquals(1_100, interpolatedPosition(1_000, 10_000, 10_500, progressing = true, durationMilliseconds = 1_100))
    }

    @Test
    fun aTrackWithoutLyricsSaysSoAndNeverSpins() {
        assertNull(resources.lyricsStatement(null), "nothing published yet: the panel's loading state")
        assertNull(resources.lyricsStatement(lyrics(synced)))
        assertEquals("No lyrics for this song.", resources.lyricsStatement(lyrics(null, AndroidLyricsState.None)))
        assertEquals("You haven't seen this song's lyrics on this device. Connect to your server to see them.",
            resources.lyricsStatement(lyrics(null, AndroidLyricsState.Unavailable,
                AndroidLibraryFreshness.Unavailable(AndroidLibraryUnavailableReason.NotCachedOffline))))
        assertEquals("Couldn't load this — your server didn't answer in time.",
            resources.lyricsStatement(lyrics(null, AndroidLyricsState.Unavailable,
                AndroidLibraryFreshness.Unavailable(AndroidLibraryUnavailableReason.Failed(DomainError.Transport.Timeout)))))
        assertEquals("Your server isn't answering for lyrics right now.",
            resources.lyricsStatement(lyrics(null, AndroidLyricsState.Unavailable, breakerOpen = true)))
    }

    @Test
    fun anAnswerToAnOlderRequestNeverReplacesOneToANewer() {
        val order = LatestAnswer()
        val stored = order.next()
        val live = order.next()
        assertEquals(true, order.accept(live), "the live read answered first")
        assertEquals(false, order.accept(stored), "the stored document, answering after it, is not shown over it")
        // A read begun before a reconnect, answering after the newer read's live lyrics (§18.4).
        val beforeReconnect = order.next()
        val afterReconnect = order.next()
        assertEquals(true, order.accept(afterReconnect))
        assertEquals(false, order.accept(beforeReconnect), "a late failure never repaints over live lyrics")
        // In order, each is shown.
        val a = order.next(); val b = order.next()
        assertEquals(true, order.accept(a)); assertEquals(true, order.accept(b))
        // A new track forgets every earlier request.
        val previousTrack = order.next()
        order.reset()
        assertEquals(false, order.accept(previousTrack), "nothing asked about the previous track is shown")
        assertEquals(true, order.accept(order.next()))
    }
}
