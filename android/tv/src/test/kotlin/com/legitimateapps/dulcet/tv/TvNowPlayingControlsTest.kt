package com.legitimateapps.dulcet.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.core.AndroidRepeatMode
import com.legitimateapps.dulcet.core.AndroidTrack
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The lean-back player's controls, driven with D-pad keys only, as a remote does: focus lands on
 * Play/Pause, LEFT and RIGHT reach Shuffle and Repeat, UP reaches the scrubber, whose LEFT and RIGHT
 * seek, and every label is the resource's, in the state it names. Up Next is a destination of its
 * own from the transport's Queue button, and the rating lives behind More.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvNowPlayingControlsTest {
    @get:Rule val compose = createComposeRule()
    private val resources = RuntimeEnvironment.getApplication().resources
    private val actions = Recording()

    @Test fun shuffleAndRepeatAreReachedFromPlayAndSayTheirState() {
        var state by mutableStateOf(playing())
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, state, actions) } }
        compose.waitForIdle()
        assertTrue(focused("tv.player.playpause"), "setup: focus lands on Play/Pause")
        assertEquals(resources.getString(R.string.tv_player_shuffle_off), description("tv.player.shuffle"))
        assertEquals(resources.getString(R.string.tv_player_repeat_off), description("tv.player.repeat"))
        assertFalse(selected("tv.player.shuffle"))

        key("tv.player.playpause", Key.DirectionLeft)
        assertTrue(focused("tv.player.previous"))
        key("tv.player.previous", Key.DirectionLeft)
        assertTrue(focused("tv.player.shuffle"), "LEFT twice from Play/Pause reaches Shuffle")
        key("tv.player.shuffle", Key.DirectionCenter)
        assertEquals(listOf("shuffle=true"), actions.calls)

        key("tv.player.shuffle", Key.DirectionRight)
        key("tv.player.previous", Key.DirectionRight)
        key("tv.player.playpause", Key.DirectionRight)
        key("tv.player.next", Key.DirectionRight)
        assertTrue(focused("tv.player.repeat"), "RIGHT twice from Play/Pause reaches Repeat")
        key("tv.player.repeat", Key.DirectionCenter)
        assertEquals(listOf("shuffle=true", "repeat"), actions.calls)

        // The controller publishes the new modes; the controls say them.
        state = state.copy(shuffle = true, repeatMode = AndroidRepeatMode.One)
        compose.waitForIdle()
        assertEquals(resources.getString(R.string.tv_player_shuffle_on), description("tv.player.shuffle"))
        assertEquals(resources.getString(R.string.tv_player_repeat_one), description("tv.player.repeat"))
        assertTrue(selected("tv.player.shuffle"))
        assertTrue(selected("tv.player.repeat"))
    }

    /** Presses add up from the last target, not from a position the player has not published yet. */
    @Test fun theScrubberIsAboveTheTransportAndItsLeftAndRightSeek() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, playing(), actions) } }
        compose.waitForIdle()
        key("tv.player.playpause", Key.DirectionUp)
        assertTrue(focused("tv.player.scrubber"), "UP from Play/Pause reaches the scrubber")
        key("tv.player.scrubber", Key.DirectionRight)
        key("tv.player.scrubber", Key.DirectionRight)
        key("tv.player.scrubber", Key.DirectionLeft)
        assertEquals(listOf("seek=70000", "seek=80000", "seek=70000"), actions.calls)
        assertTrue(focused("tv.player.scrubber"), "seeking keeps focus on the scrubber")
        // Clamped to the track.
        repeat(20) { key("tv.player.scrubber", Key.DirectionRight) }
        assertEquals("seek=200000", actions.calls.last())
    }

    @Test fun aTrackThatCannotSeekHasNoScrubberStop() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, playing().copy(seekable = false), actions) }
        }
        compose.waitForIdle()
        key("tv.player.playpause", Key.DirectionUp)
        assertFalse(focused("tv.player.scrubber"), "a scrubber that cannot seek takes no focus")
        assertTrue(actions.calls.isEmpty())
    }

    /**
     * Up Next: the Queue button ends the transport row, and opens a full-height panel listing only
     * what follows the playing entry — which is the summary at its top, never a row. A row's centre
     * key plays it; Back closes the panel with focus back on the Queue button.
     */
    @Test fun anUpNextEntrySelectedPlaysThatEntry() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, fourEntries(), actions) } }
        compose.waitForIdle()
        key("tv.player.playpause", Key.DirectionRight)
        key("tv.player.next", Key.DirectionRight)
        key("tv.player.repeat", Key.DirectionRight)
        assertTrue(focused("tv.player.queue"), "RIGHT three times from Play/Pause reaches Queue")
        key("tv.player.queue", Key.DirectionCenter)
        assertTrue(focused("tv.player.upnext.0"), "the panel opens on the first upcoming row")
        assertFalse(exists("tv.player.upnext.2"), "a four-entry queue has two rows up next")
        compose.onNode(hasText("Track 3") and hasAnyAncestor(hasTestTag("tv.player.upnext.0")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Track 4") and hasAnyAncestor(hasTestTag("tv.player.upnext.1")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Track 2") and hasAnyAncestor(hasTestTag("tv.player.upnext.nowPlaying")), useUnmergedTree = true).assertExists()
        // The playing entry is the summary, never again a row.
        compose.onNode(hasText("Track 2") and hasAnyAncestor(hasTestTag("tv.player.upnext.0")), useUnmergedTree = true).assertDoesNotExist()
        key("tv.player.upnext.0", Key.DirectionDown)
        assertTrue(focused("tv.player.upnext.1"), "DOWN moves between the rows")
        key("tv.player.upnext.1", Key.DirectionCenter)
        assertEquals(listOf("jump=e4"), actions.calls)
        key("tv.player.upnext.1", Key.Back)
        assertFalse(exists("tv.player.upnext.0"), "Back closes Up Next")
        assertTrue(focused("tv.player.queue"), "Back returns focus to the Queue button")
    }

    /**
     * An Up Next entry is edited with a held centre key (long-Select): the dialog opens with its
     * first non-destructive choice focused, a choice closes it with focus back on the row, and the
     * first entry cannot move above the playing one, nor the last below the queue's end.
     */
    @Test fun anUpNextEntryIsMovedAndRemovedWithAHeldCentreKey() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, fourEntries(), actions) } }
        compose.waitForIdle()
        openQueuePanel()
        longCenter("tv.player.upnext.0")
        assertTrue(exists("tv.player.upnext.edit"), "a held centre key opens the entry's edits")
        assertFalse(exists("tv.player.upnext.edit.moveUp"), "the first Up Next entry cannot move above the playing one")
        assertTrue(focused("tv.player.upnext.edit.moveDown"), "the first non-destructive choice takes focus")
        key("tv.player.upnext.edit.moveDown", Key.DirectionCenter)
        assertEquals(listOf("move=e3>3"), actions.calls, "Move Down names the entry and its new place in the whole queue")
        assertFalse(exists("tv.player.upnext.edit"), "a choice closes the dialog")
        assertTrue(focused("tv.player.upnext.0"), "focus returns to the row that opened it")

        key("tv.player.upnext.0", Key.DirectionDown)
        assertTrue(focused("tv.player.upnext.1"), "DOWN moves between the rows")
        longCenter("tv.player.upnext.1")
        assertTrue(exists("tv.player.upnext.edit.moveUp"))
        assertFalse(exists("tv.player.upnext.edit.moveDown"), "the last entry cannot move down")
        key("tv.player.upnext.edit.moveUp", Key.DirectionDown)
        key("tv.player.upnext.edit.remove", Key.DirectionCenter)
        assertEquals(listOf("move=e3>3", "remove=e4"), actions.calls)
    }

    /** Only upcoming entries can be edited; the playing summary and played tracks offer no edits. */
    @Test fun thePlayingEntryAndThosePlayedHaveNoOptions() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, fourEntries(), actions) } }
        compose.waitForIdle()
        openQueuePanel()
        val summary = compose.onNodeWithTag("tv.player.upnext.nowPlaying").fetchSemanticsNode()
        assertFalse(summary.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick),
            "the playing entry cannot be removed")
        compose.onNode(hasText("Track 1") and hasAnyAncestor(hasTestTag("tv.player.upnext")), useUnmergedTree = true).assertDoesNotExist()
        assertFalse(exists("tv.player.upnext.2"), "only the two upcoming entries are rows")
        longCenter("tv.player.upnext.0")
        assertTrue(exists("tv.player.upnext.edit"), "an upcoming entry has options")
    }

    /** Clear Up Next is the last of an entry's edits; a refusal from the queue is said. */
    @Test fun clearRemovesUpNextAndARefusedEditIsSaid() {
        actions.accepts = false
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, fourEntries(), actions) } }
        compose.waitForIdle()
        openQueuePanel()
        longCenter("tv.player.upnext.0")
        key("tv.player.upnext.edit.moveDown", Key.DirectionDown)
        key("tv.player.upnext.edit.remove", Key.DirectionDown)
        assertTrue(focused("tv.player.upnext.edit.clear"), "DOWN through the edits reaches Clear Up Next")
        key("tv.player.upnext.edit.clear", Key.DirectionCenter)
        assertEquals(listOf("clear"), actions.calls)
        assertEquals(sharedString(com.legitimateapps.dulcet.shared.R.string.queue_edit_refused),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast(), "a refused edit is said")
    }

    /**
     * The heart leads the secondary row under the transport: its state is its glyph — filled or not —
     * on a neutral container; the centre key toggles it; a change that needs words says them beneath
     * the title.
     */
    @Test fun theHeartIsRightOfRepeatAndSaysItsStateAndAnyOutcome() {
        var favourite by mutableStateOf(false)
        var line by mutableStateOf<String?>(null)
        val toggles = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvNowPlayingScreen(null, playing(), actions, TvPlayerFavourite(favourite, line) { toggles += !favourite; favourite = !favourite })
            }
        }
        compose.waitForIdle()
        key("tv.player.playpause", Key.DirectionDown)
        assertTrue(focused("tv.player.favourite"), "DOWN from the transport reaches the heart")
        assertFalse(selected("tv.player.favourite"))
        assertEquals(sharedString(com.legitimateapps.dulcet.shared.R.string.library_favourite_add), description("tv.player.favourite"))
        key("tv.player.favourite", Key.DirectionCenter)
        assertEquals(listOf(true), toggles)
        assertTrue(selected("tv.player.favourite"), "the heart fills with the press")
        assertEquals(sharedString(com.legitimateapps.dulcet.shared.R.string.library_favourite_remove), description("tv.player.favourite"))
        assertTrue(focused("tv.player.favourite"), "focus stays on the heart")
        assertEquals(emptyList(), actions.calls, "the heart changes the track, not playback")
        assertFalse(exists("tv.player.favourite.outcome"))
        line = "Couldn't save that change — the server"
        compose.waitForIdle()
        assertEquals(line, compose.onNodeWithTag("tv.player.favourite.outcome").fetchSemanticsNode()
            .config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString())
    }

    /**
     * No stars on the player itself: the rating is a row of the listening options panel behind the
     * More button, where each star is a focus stop RIGHT of the last and the centre key on one rates
     * the track that many stars — on the star already shown, removes it. For accessibility services
     * the row is one adjustable control, 0 to 5 in whole stars.
     */
    @Test fun theStarsAreUpFromTheScrubberAndTheCentreKeyRates() {
        var rating by mutableStateOf(0)
        val rated = mutableListOf<Int>()
        val state = playing()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvNowPlayingScreen(null, state, actions,
                    listening = { TvListeningSessionButton(state, {}, {}, {}, TvPlayerRating(rating) { rated += it; rating = it }) })
            }
        }
        compose.waitForIdle()
        assertFalse(exists("tv.player.rating"), "no star strip on the player itself")
        openMorePanel()
        val first = (1..5).firstOrNull { focused("tv.player.rating.$it") }
        assertTrue(first != null, "DOWN through the panel reaches the stars")
        var at = first!!
        while (at < 3) { key("tv.player.rating.$at", Key.DirectionRight); at++ }
        while (at > 3) { key("tv.player.rating.$at", Key.DirectionLeft); at-- }
        assertTrue(focused("tv.player.rating.3"), "LEFT and RIGHT move across the stars")
        assertEquals(sharedString(com.legitimateapps.dulcet.shared.R.string.library_rating_set, 3), description("tv.player.rating.3"))
        key("tv.player.rating.3", Key.DirectionCenter)
        assertEquals(listOf(3), rated)
        assertTrue((1..3).all { selected("tv.player.rating.$it") } && (4..5).none { selected("tv.player.rating.$it") },
            "three stars fill with the press")
        assertTrue(focused("tv.player.rating.3"), "focus stays on the star")
        assertEquals(resources.getQuantityString(com.legitimateapps.dulcet.shared.R.plurals.library_rating_clear, 3, 3), description("tv.player.rating.3"),
            "the shown star says it removes the rating")
        key("tv.player.rating.3", Key.DirectionCenter)
        assertEquals(listOf(3, 0), rated, "the centre key on the shown star removes the rating")
        assertEquals(emptyList(), actions.calls, "rating changes the track, not playback")

        val node = compose.onNodeWithTag("tv.player.rating").fetchSemanticsNode()
        val range = node.config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0f..5f, range.range)
        assertEquals(4, range.steps)
        compose.onNodeWithTag("tv.player.rating").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.waitForIdle()
        assertEquals(listOf(3, 0, 4), rated, "an accessibility service sets the rating through setProgress")
    }

    /**
     * A rating this device does not know — a track only ever seen in a queue, before any change — is
     * no star selected and said "Rating unknown", never "Not rated". There is no value to step from,
     * so the row offers no range and no setProgress (one step up would send 1 over whatever the
     * server holds), only absolute actions; a star still sets exactly its own rating.
     */
    @Test fun anUnknownRatingIsSaidAsUnknownAndOffersOnlyAbsoluteSets() {
        var rating by mutableStateOf<Int?>(null)
        val rated = mutableListOf<Int>()
        val state = playing()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                TvNowPlayingScreen(null, state, actions,
                    listening = { TvListeningSessionButton(state, {}, {}, {}, TvPlayerRating(rating) { rated += it; rating = it }) })
            }
        }
        compose.waitForIdle()
        openMorePanel()
        val node = compose.onNodeWithTag("tv.player.rating").fetchSemanticsNode()
        assertEquals("Rating unknown", node.config[SemanticsProperties.StateDescription])
        assertTrue((1..5).none { selected("tv.player.rating.$it") }, "no star is selected while the rating is unknown")
        assertEquals(com.legitimateapps.dulcet.ui.UNKNOWN_RATING_ALPHA, starAlpha("tv.player.rating"),
            "an unknown rating's stars are dimmed, so they do not look like a known 0")
        assertTrue(SemanticsProperties.ProgressBarRangeInfo !in node.config, "an unknown rating has no range to step through")
        assertTrue(SemanticsActions.SetProgress !in node.config, "and no relative adjust")
        val offered = node.config[SemanticsActions.CustomActions]
        assertEquals((1..5).map { "Rate $it of 5 stars" }, offered.map { it.label })
        compose.runOnIdle { offered[1].action() }
        compose.waitForIdle()
        assertEquals(listOf(2), rated, "an absolute action sets exactly its rating")
        assertEquals(1f, starAlpha("tv.player.rating"), "a known rating's stars are not dimmed")

        rating = null
        compose.waitForIdle()
        assertEquals(sharedString(com.legitimateapps.dulcet.shared.R.string.library_rating_set, 1), description("tv.player.rating.1"),
            "from unknown, the first star rates — it does not remove")
        var at = (1..5).first { focused("tv.player.rating.$it") }
        while (at > 1) { key("tv.player.rating.$at", Key.DirectionLeft); at-- }
        key("tv.player.rating.1", Key.DirectionCenter)
        assertEquals(listOf(2, 1), rated)
        assertEquals("Remove the rating of 1 star", description("tv.player.rating.1"), "one star, singular")
    }

    /** With no playback session the transport and scrubber are gone; one focused action leads on. */
    @Test fun withoutASessionThePlayerSaysNothingIsPlayingAndOffersOneWayOn() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(null, AndroidPlaybackState(), null) }
        }
        compose.waitForIdle()
        assertTrue(exists("tv.player.empty"))
        assertFalse(exists("tv.player.playpause"), "no disabled transport without a session")
        assertFalse(exists("tv.player.scrubber"), "and no scrubber")
        assertFalse(exists("tv.player.rating"))
        assertTrue(focused("tv.player.connect"), "Connect account takes the initial focus")
        compose.onNodeWithTag("tv.player.connect").fetchSemanticsNode()
    }

    /** Signed in with nothing playing, the way on is the library. */
    @Test fun withoutASessionButSignedInThePlayerOffersTheLibrary() {
        val account = com.legitimateapps.dulcet.search.SearchAccount("provider:none", "http://127.0.0.1:9", "u", "p", true)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { TvNowPlayingScreen(account, AndroidPlaybackState(), null) }
        }
        compose.waitForIdle()
        assertTrue(exists("tv.player.empty"))
        assertTrue(focused("tv.player.browse"), "Browse library takes the initial focus")
    }

    /** From Play, DOWN to the secondary row and the centre key on More opens the panel; DOWN walks to the stars. */
    private fun openMorePanel() {
        key("tv.player.playpause", Key.DirectionDown)
        assertTrue(focused("tv.listening.open"), "DOWN from the transport reaches More")
        key("tv.listening.open", Key.DirectionCenter)
        assertTrue(focused("tv.listening.autoplay"), "the panel opens on Autoplay")
        key("tv.listening.autoplay", Key.DirectionDown)
        key("tv.listening.sleep", Key.DirectionDown)
        key("tv.listening.crossfade", Key.DirectionDown)
    }

    /** Opens the queue panel from the transport and asserts its first row took focus. */
    private fun openQueuePanel() {
        key("tv.player.playpause", Key.DirectionRight)
        key("tv.player.next", Key.DirectionRight)
        key("tv.player.repeat", Key.DirectionRight)
        assertTrue(focused("tv.player.queue"), "setup: RIGHT three times from Play/Pause reaches Queue")
        key("tv.player.queue", Key.DirectionCenter)
        assertTrue(focused("tv.player.upnext.0"), "setup: the panel opens on the first upcoming row")
    }

    /** A held centre key (long-Select), through the row's semantics as the fleet's card tests drive it. */
    private fun longCenter(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
    }

    /**
     * The opacity the stars tagged [tag] are drawn at: `Modifier.alpha` adds a graphics layer only
     * below 1, so no layer is fully opaque.
     */
    private fun starAlpha(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.getModifierInfo().firstNotNullOfOrNull { info ->
            (info.modifier as? androidx.compose.ui.platform.InspectableValue)?.inspectableElements
                ?.firstOrNull { it.name == "alpha" }?.value as? Float
        } ?: 1f

    private fun sharedString(id: Int, vararg args: Any): String = resources.getString(id, *args)

    private fun exists(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun playing() = AndroidPlaybackState(
        title = "Track 2", phase = "Playing", playbackSessionId = "s", playWhenReady = true,
        positionMilliseconds = 60_000, durationMilliseconds = 200_000, seekable = true,
        queue = (1..3).map { AndroidQueueEntry("e$it", AndroidTrack("p", "t$it", "Track $it", "Artist")) },
        currentIndex = 1, canGoNext = true, canGoPrevious = true,
    )

    private fun fourEntries() = playing().copy(
        queue = (1..4).map { AndroidQueueEntry("e$it", AndroidTrack("p", "t$it", "Track $it", "Artist")) })

    private fun key(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focused(tag: String): Boolean = runCatching {
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
    }.getOrDefault(false)

    private fun description(tag: String): String? =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.firstOrNull()

    private fun selected(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }

    private class Recording : TvPlayerActions {
        val calls = mutableListOf<String>()
        override fun togglePlayPause() { calls += "toggle" }
        override fun previous() { calls += "previous" }
        override fun next() { calls += "next" }
        override fun setShuffle(enabled: Boolean) { calls += "shuffle=$enabled" }
        override fun cycleRepeatMode() { calls += "repeat" }
        override fun seek(positionMilliseconds: Long) { calls += "seek=$positionMilliseconds" }
        override fun jumpTo(queueEntryId: String) { calls += "jump=$queueEntryId" }
        /** What the queue answers to an edit. */
        var accepts = true
        override fun moveEntry(queueEntryId: String, toIndex: Int): Boolean { calls += "move=$queueEntryId>$toIndex"; return accepts }
        override fun removeEntry(queueEntryId: String): Boolean { calls += "remove=$queueEntryId"; return accepts }
        override fun clearUpcoming(): Boolean { calls += "clear"; return accepts }
    }
}
