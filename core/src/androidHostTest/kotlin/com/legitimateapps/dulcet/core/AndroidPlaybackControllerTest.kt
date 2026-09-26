package com.legitimateapps.dulcet.core

import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidPlaybackControllerTest {
    @Before fun dispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun lateMetadataAfterNewSelectionCannotReplaceTheNewQueue() {
        var pending: Continuation<AuthenticatedEndpointResponse>? = null
        var lateReturns = 0
        Fixture(loadSong = { id ->
            if (id == "old") suspendCoroutine<AuthenticatedEndpointResponse> { pending = it }.also { lateReturns++ }
            else song(id)
        }).use { f ->
            f.controller.playSong(OWNER, "old", "Old")
            val old = assertNotNull(pending)
            f.controller.playSong(OWNER, "new", "New")
            assertEquals(listOf("new"), f.prepared.map { it.itemId.rawId })
            old.resume(song("old"))
            assertEquals(1, lateReturns, "The cancelled operation must actually return late")
            assertEquals(listOf("new"), f.prepared.map { it.itemId.rawId })
            assertEquals("New", f.controller.state.value.title)
        }
    }

    @Test fun latePlanAfterStopCannotPrepareOrPlay() {
        var pending: Continuation<PlaybackResolutionResult>? = null
        var request: PlaybackResolveRequest? = null
        var lateReturns = 0
        Fixture(resolve = { value ->
            request = value
            suspendCoroutine<PlaybackResolutionResult> { pending = it }.also { lateReturns++ }
        }).use { f ->
            f.controller.playSong(OWNER, "song", "Song")
            val delayed = assertNotNull(pending)
            f.controller.stop()
            delayed.resume(resolved(assertNotNull(request)))
            assertEquals(1, lateReturns, "The stopped resolution must actually return")
            assertTrue(f.prepared.isEmpty())
            assertFalse(f.probe.requested)
        }
    }

    @Test fun pauseWhileMetadataLoadsAfterStopOverridesThePreviousPlayIntent() {
        var pending: Continuation<AuthenticatedEndpointResponse>? = null
        Fixture(loadSong = { id ->
            if (id == "second") suspendCoroutine { pending = it } else song(id)
        }).use { f ->
            f.controller.playSong(OWNER, "first", "First")
            assertTrue(f.probe.requested)
            f.controller.stop()
            f.controller.playSong(OWNER, "second", "Second")
            val delayed = assertNotNull(pending)
            f.controller.pause()
            delayed.resume(song("second"))
            assertEquals(listOf("first", "second"), f.prepared.map { it.itemId.rawId })
            assertFalse(f.probe.requested, "Preparing the second selection must honor Pause")
            f.controller.play()
            assertTrue(f.probe.requested)
        }
    }

    @Test fun submittedPlayIsInTheRealOutboxBeforeDeliveryHandoff() {
        var handoffs = 0
        Fixture(onDelivery = { f, event ->
            if (event is RecordedPlaybackEvent.SubmittedPlay) {
                handoffs++
                val pending = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { 1L }).pending(ServerId(OWNER))
                assertEquals(1, pending.size, "Delivery was scheduled before durable persistence")
                assertEquals(event.itemId.rawId, pending.single().rawId)
                assertEquals(event.sessionStartWallClock, pending.single().sessionStartWallClock)
            }
        }).use { f ->
            f.controller.playSong(OWNER, "song", "Song")
            assertEquals(1, f.prepared.size)
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            repeat(46) {
                f.probe.position += 500
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            }
            assertEquals(1, handoffs, "The controller must cross the threshold and hand off a submitted play")
        }
    }

    @Test fun restoredQueuePreparesPausedOnlyForItsOwningAccount() {
        for (savedOwner in listOf(OWNER, "different-account")) {
            val loaded = mutableListOf<String>()
            Fixture(savedOwner = savedOwner, loadSong = { id -> loaded += id; song(id) }).use { f ->
                if (savedOwner == OWNER) {
                    assertEquals(listOf("saved-song"), loaded)
                    assertEquals(listOf("saved-song"), f.prepared.map { it.itemId.rawId })
                    assertFalse(f.probe.requested, "Restoration must not auto-play")
                    assertEquals(OWNER, f.prepared.single().itemId.providerInstanceId)
                } else {
                    assertTrue(loaded.isEmpty(), "Another account's queue must not even load metadata")
                    assertTrue(f.prepared.isEmpty())
                    assertFalse(f.probe.requested)
                }
            }
        }
    }

    @Test fun stopThenPlayPreservesRestoredQueueAndRestartsTheCurrentSelection() {
        Fixture(savedOwner = OWNER, savedSongs = listOf("A", "B", "C")).use { f ->
            val queues = PersistentQueueStore(f.store.database)
            val original = queues.load(ServerId(OWNER))
            assertEquals(listOf("A", "B", "C"), original.entries.map { it.providerItemId.rawId })
            assertEquals(3, original.entries.map { it.queueEntryId }.toSet().size)
            for (index in 0..1) {
                if (index > 0) f.controller.next()
                val before = queues.load(ServerId(OWNER))
                val session = f.controller.state.value.playbackSessionId
                assertEquals(index, before.currentIndex)
                f.controller.stop()
                assertEquals(before, queues.load(ServerId(OWNER)), "Stop must not edit persistence")
                f.controller.play()
                assertEquals(before, queues.load(ServerId(OWNER)), "Play must retain entries, IDs, order and selection")
                assertEquals(original.entries, queues.load(ServerId(OWNER)).entries)
                assertEquals(before.entries[index].providerItemId, f.prepared.last().itemId)
                assertEquals(before.entries[index].queueEntryId.value, f.controller.state.value.queueEntryId)
                assertNotEquals(session, f.controller.state.value.playbackSessionId, "Restart begins a fresh session")
                assertTrue(f.probe.requested, "Transport Play must actually request playback")
            }
        }
    }

    @Test fun albumQueuePublishesUpNextAndJumpStartsTheNamedEntry() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2", "t3"), 1, AndroidQueueSource.Album, "Album", "album-id")
            val started = f.controller.state.value
            assertEquals(listOf("t1", "t2", "t3"), started.queue.map { it.track.rawId })
            assertEquals(1, started.currentIndex)
            assertEquals("Title t2", started.title)
            assertEquals("Artist", started.artist)
            assertEquals("cover-t2", started.artworkKey)
            assertTrue(started.playWhenReady)
            assertEquals(listOf("t2"), f.prepared.map { it.itemId.rawId })
            assertTrue(f.probe.requested)

            val third = started.queue[2].queueEntryId
            f.controller.jumpTo(third)
            val jumped = f.controller.state.value
            assertEquals(listOf("t2", "t3"), f.prepared.map { it.itemId.rawId })
            assertEquals(third, jumped.queueEntryId)
            assertEquals(started.queue.map { it.queueEntryId }, jumped.queue.map { it.queueEntryId },
                "A jump must keep every queue entry identity")
            assertNotEquals(started.playbackSessionId, jumped.playbackSessionId)
        }
    }

    @Test fun naturalCompletionAdvancesToTheNextAlbumTrack() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.state = androidx.media3.common.Player.STATE_ENDED
            f.probe.events()
            assertEquals(listOf("t1", "t2"), f.prepared.map { it.itemId.rawId })
            assertEquals(1, f.controller.state.value.currentIndex)
            assertEquals("Title t2", f.controller.state.value.title)
        }
    }

    @Test fun anEndedQueueReportsNoSessionAndNoStalePosition() {
        Fixture().use { f ->
            f.controller.playQueue(album("only"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 39_000
            f.probe.state = androidx.media3.common.Player.STATE_ENDED
            f.probe.events()
            val ended = f.controller.state.value
            assertEquals(null, ended.playbackSessionId, "The control requires the queue to have ended")
            assertEquals(0, ended.positionMilliseconds, "A finished queue must not report the last song's position")
            assertNull(ended.durationMilliseconds)
            assertFalse(ended.playWhenReady)
            assertEquals("", ended.title)
        }
    }

    @Test fun refusedTransportVerbIsNotReportedAsAPlaybackFailure() {
        Fixture().use { f ->
            // Nothing is prepared, so the engine refuses both seeks. That must not paint an error.
            f.controller.seek(10_000)
            f.controller.sessionPlayer.seekTo(5_000)
            assertTrue(f.probe.seekCommands.isEmpty(), "The control requires both seeks to have been refused")
            assertNull(f.controller.state.value.error)
        }
    }

    @Test fun foreignAccountTrackIsRefusedBeforeAnyRequest() {
        val loaded = mutableListOf<String>()
        Fixture(loadSong = { loaded += it; song(it) }).use { f ->
            f.controller.playQueue(listOf(AndroidTrack(OWNER, "a", "A"), AndroidTrack("provider:other", "b", "B")),
                0, AndroidQueueSource.Album, "Album", "album-id")
            assertEquals(DomainError.Auth.Forbidden, f.controller.state.value.error)
            assertTrue(loaded.isEmpty(), "No request may be made for a queue holding another account's song")
            assertTrue(f.prepared.isEmpty())
        }
    }

    @Test fun previousRestartsASongPastThreeSecondsAndOtherwiseMovesBack() {
        Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 1, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 10_000
            f.controller.skipToPrevious()
            assertEquals(listOf(0L), f.probe.seekCommands, "Past the threshold, Previous restarts the song")
            assertEquals(listOf("b"), f.prepared.map { it.itemId.rawId })
            f.controller.skipToPrevious()
            assertEquals(listOf("b", "a"), f.prepared.map { it.itemId.rawId }, "At the start, Previous moves back")
        }
    }

    @Test fun sessionAdvertisesQueueSkipsThatTheOneItemTimelineCannot() {
        Fixture().use { f ->
            val session = f.controller.sessionPlayer
            assertTrue(session.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT))
            assertTrue(session.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS))
            assertFalse(session.isCommandAvailable(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS),
                "System controllers must not edit the core-owned queue")
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            assertTrue(session.hasNextMediaItem())
            session.seekToNext()
            assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId })
            assertFalse(session.hasNextMediaItem())
        }
    }

    @Test fun previousAtTheFirstSongRestartsItAndNeverClearsTheQueue() {
        Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 1_000
            val before = f.controller.state.value
            assertFalse(before.canGoPrevious, "There is no previous song at the start without repeat-all")
            assertFalse(before.canRestart, "One second in, Previous would do nothing worth a button")
            assertTrue(before.canGoNext)
            f.controller.previous()
            f.controller.sessionPlayer.seekToPreviousMediaItem()
            val after = f.controller.state.value
            assertEquals(0, after.currentIndex, "Previous must not clear the queue")
            assertEquals(before.queue.map { it.queueEntryId }, after.queue.map { it.queueEntryId })
            assertEquals(before.playbackSessionId, after.playbackSessionId)
            assertEquals(listOf(0L, 0L), f.probe.seekCommands, "At the first song, Previous restarts it")
            assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
            f.controller.cycleRepeatMode() // repeat-all wraps, so Previous now has somewhere to go
            assertTrue(f.controller.state.value.canGoPrevious)
        }
    }

    @Test fun nextAtTheLastSongDoesNothing() {
        Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 1, AndroidQueueSource.Album, "Album", "album-id")
            assertFalse(f.controller.state.value.canGoNext)
            val session = f.controller.state.value.playbackSessionId
            f.controller.next()
            f.controller.sessionPlayer.seekToNext()
            assertEquals(1, f.controller.state.value.currentIndex)
            assertEquals(session, f.controller.state.value.playbackSessionId)
            assertEquals(listOf("b"), f.prepared.map { it.itemId.rawId })
        }
    }

    @Test fun aSystemPauseBecomesTheRequestedStateSoNextDoesNotResumeAloud() {
        for (reason in listOf(androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)) Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            assertTrue(f.controller.state.value.playWhenReady, "The control requires playback to have been requested")
            f.probe.systemPause(reason)
            assertFalse(f.controller.state.value.playWhenReady, "The UI must offer Play once the system paused")
            f.controller.next()
            assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId })
            assertFalse(f.probe.requested, "Next after an unplug must not start playing through the speaker")
            f.controller.togglePlayPause()
            assertTrue(f.probe.requested, "The first tap after a system pause must play, not be a no-op")
        }
    }

    @Test fun anUnplayableFormatIsTranscodedAndItsStreamIsNotSeekable() {
        Fixture(loadSong = { id -> song(id, suffix = "wma") }).use { f ->
            f.controller.playSong(OWNER, "wma-song", "Legacy format")
            assertNull(f.controller.state.value.error, "A format outside the direct-play list must not be refused")
            val plan = f.prepared.single()
            assertEquals(AudioContainer.Mp3, plan.expectedContainer)
            assertEquals("mp3", plan.parameters["format"], "The profile's transcoding target must be requested")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            assertFalse(f.controller.state.value.seekable, "A transcode is not seekable until server offsets exist")
            f.controller.seek(10_000)
            assertTrue(f.probe.seekCommands.isEmpty())
        }
        // The control: a direct format through the same path is seekable and requests no format.
        Fixture(loadSong = { id -> song(id, suffix = "flac") }).use { f ->
            f.controller.playSong(OWNER, "flac-song", "Direct format")
            assertNull(f.prepared.single().parameters["format"])
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            assertTrue(f.controller.state.value.seekable)
        }
    }

    @Test fun anotherAccountsQueueIsRefusedAtEveryEntryPointBeforeAnyRequest() {
        val loaded = mutableListOf<String>()
        Fixture(savedOwner = "different-account", savedSongs = listOf("x", "y", "z"),
            loadSong = { id -> loaded += id; song(id) }).use { f ->
            val foreign = PersistentQueueStore(f.store.database).load(ServerId("different-account"))
            val entry = foreign.entries[1].queueEntryId.value
            f.controller.play(); f.controller.next(); f.controller.previous(); f.controller.skipToPrevious()
            f.controller.jumpTo(entry); f.controller.setShuffle(true); f.controller.cycleRepeatMode()
            f.controller.sessionPlayer.seekToNext(); f.controller.sessionPlayer.play()
            assertTrue(loaded.isEmpty(), "No request may be made for another account's queue")
            assertTrue(f.prepared.isEmpty())
            assertFalse(f.probe.requested)
            assertEquals(DomainError.Auth.Forbidden, f.controller.state.value.error)
            assertNull(f.controller.state.value.playbackSessionId, "The foreign queue must not become a session")
            assertEquals(foreign, PersistentQueueStore(f.store.database).load(ServerId("different-account")),
                "The other account's queue must be left exactly as it was")
        }
    }

    @Test fun aClosedControllerIgnoresEveryVerbInsteadOfCrashing() {
        Fixture().use { f ->
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.controller.close()
            f.controller.play(); f.controller.pause(); f.controller.togglePlayPause(); f.controller.next()
            f.controller.previous(); f.controller.skipToPrevious(); f.controller.seek(1); f.controller.stop()
            f.controller.jumpTo("anything"); f.controller.setShuffle(true); f.controller.cycleRepeatMode()
            f.controller.playSong(OWNER, "a", "A"); f.controller.rememberTracks(album("c"))
            f.controller.sessionPlayer.play(); f.controller.sessionPlayer.seekForward(); f.controller.sessionPlayer.setVolume(0.5f)
            assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
        }
    }

    @Test fun aTrackThatCannotBeDecodedIsSkippedWithANoticeAndLeavesNoFailureLine() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2", "t3"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            // Every publication, not only the last: a surface draws each one it is handed, and the
            // one carrying the notice is published before the next entry starts.
            val published = mutableListOf<AndroidPlaybackState>()
            val watcher = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                f.controller.state.collect { published += it }
            }
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            watcher.cancel()
            assertTrue(published.any { it.skipNotice != null }, "The control requires the watcher to see the notice published")
            assertEquals(emptyList(), published.filter { it.skipNotice != null && it.error != null }.map { it.error },
                "No publication may show the notice and the failure line together")
            val skipped = f.controller.state.value
            assertEquals(listOf("t1", "t2"), f.prepared.map { it.itemId.rawId }, "The queue must move on past the track")
            assertEquals(1, skipped.currentIndex)
            assertEquals("Title t2", skipped.title)
            assertNull(skipped.error, "The failure line must not stay on screen for a track that is not playing")
            val notice = assertNotNull(skipped.skipNotice, "The person must be told which track was skipped")
            assertEquals("Title t1", notice.title)
            assertTrue(skipped.playWhenReady)
            assertTrue(f.probe.requested, "The next entry must be asked to play, not left paused")

            // A second skip is a second notice, even of a different track; the first is replaced.
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            val again = assertNotNull(f.controller.state.value.skipNotice)
            assertEquals("Title t2", again.title)
            assertTrue(again.sequence > notice.sequence)
            assertEquals(listOf("t1", "t2", "t3"), f.prepared.map { it.itemId.rawId })

            // Closing the controller withdraws the notice with the queue it names.
            f.controller.close()
            assertNull(f.controller.state.value.skipNotice, "A closed controller must not keep a notice")
        }
    }

    @Test fun aConnectionFailureStillStopsAndPresentsTheFailureLine() {
        Fixture().use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            val stopped = f.controller.state.value
            assertEquals(listOf("t1"), f.prepared.map { it.itemId.rawId }, "Skipping would only repeat the failure")
            assertEquals(0, stopped.currentIndex)
            assertEquals(DomainError.Transport.Unreachable, stopped.error)
            assertNull(stopped.skipNotice)

            // The person moves on, and the line belongs to an attempt that is over (rule 5).
            f.controller.next()
            assertEquals(listOf("t1", "t2"), f.prepared.map { it.itemId.rawId }, "The control requires Next to start t2")
            assertNull(f.controller.state.value.error, "The failure line must not stay for a track that is not playing")
        }
    }

    /**
     * A connection failure stops on the entry; the attempt is over. Nothing seeks it, the app and
     * the system both offer Play, and Play -- the app's, or the system's through Media3's
     * play-button handling -- is Try Again (spec §12.1): a further attempt of the same play, in the
     * same session, instead of leaving the player failed.
     */
    @Test fun afterAConnectionFailureNothingSeeksItAndPlayRetriesTheEntry() {
        for (path in listOf("app", "system")) {
            Fixture().use { f ->
                f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                assertTrue(f.controller.state.value.playWhenReady, "$path: the control requires the entry asked to play")
                f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                val failed = f.controller.state.value
                assertEquals(DomainError.Transport.Unreachable, failed.error, "$path: the control requires the failure line")
                assertEquals(listOf("t1"), f.prepared.map { it.itemId.rawId }, "$path: the control requires no skip")
                assertFalse(failed.playWhenReady, "$path: the app must offer Play, not Pause, once the attempt has failed")
                assertFalse(failed.canRestart, "$path: a failed attempt has nothing to restart")
                f.controller.seek(1_000)
                f.controller.sessionPlayer.seekTo(2_000)
                f.controller.sessionPlayer.seekToDefaultPosition()
                assertEquals(emptyList(), f.probe.seekCommands, "$path: no seek may reach the failed attempt")
                if (path == "app") f.controller.togglePlayPause()
                else {
                    assertTrue(androidx.media3.common.util.Util.shouldShowPlayButton(f.controller.sessionPlayer),
                        "$path: the control requires the system to offer Play")
                    assertTrue(androidx.media3.common.util.Util.handlePlayButtonAction(f.controller.sessionPlayer),
                        "$path: the control requires Media3 to have acted on Play")
                }
                assertEquals(listOf("t1", "t1"), f.prepared.map { it.itemId.rawId }, "$path: Play begins a new attempt for the entry")
                assertNotEquals(f.prepared[0].attemptId, f.prepared[1].attemptId, "$path: a new attempt, not the failed one")
                assertEquals(f.prepared[0].playbackSessionId, f.prepared[1].playbackSessionId,
                    "$path: Try Again is a further attempt of the same play, in the same session (spec §12.1)")
                val retried = f.controller.state.value
                assertNull(retried.error, "$path: the player must not stay failed")
                assertTrue(retried.playWhenReady, "$path: the retry plays")
                assertEquals(0, retried.currentIndex, "$path: the same entry")
                assertEquals(emptyList(), f.probe.seekCommands, "$path: a failure before any progress saved no position to resume")
            }
        }
    }

    /**
     * A connection failure while the entry is still being resolved, before the engine has it, stops
     * on the entry as an engine's would: the app offers Play, and Play resolves the entry again.
     */
    @Test fun aConnectionFailureBeforeTheEngineHasTheEntryOffersPlayAndPlayRetriesIt() {
        var attempts = 0
        Fixture(resolve = { r ->
            if (r.itemId.rawId == "t1" && attempts++ == 0) PlaybackResolutionResult.Failed(DomainError.Transport.Unreachable)
            else resolved(r)
        }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            val failed = f.controller.state.value
            assertEquals(1, attempts, "The control requires the one failed resolution")
            assertEquals(DomainError.Transport.Unreachable, failed.error, "The control requires the failure line")
            assertEquals(emptyList(), f.prepared.map { it.itemId.rawId }, "The control requires nothing prepared and no skip")
            assertEquals(0, failed.currentIndex)
            assertFalse(failed.playWhenReady, "The app must offer Play, not Pause, once the start has failed")
            f.controller.togglePlayPause()
            assertEquals(2, attempts, "Play resolves the entry again")
            assertEquals(listOf("t1"), f.prepared.map { it.itemId.rawId }, "Play begins a new attempt for the same entry")
            assertNull(f.controller.state.value.error, "The player must not stay failed")
            assertTrue(f.controller.state.value.playWhenReady, "The retry plays")
            assertTrue(f.probe.requested, "The engine is told to play the retry")
        }
    }

    /**
     * A connection failure part way through, past the scrobble threshold, and then Play: the retry
     * resumes where the failure saved its position, in the same session, and the listen -- heard
     * to its end across the failure -- is one play, not two (spec §12.1, §28 item 7).
     */
    @Test fun playAfterAFailurePartWayThroughResumesTheSamePlayAndScrobblesItOnce() {
        val submitted = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
        Fixture(onDelivery = { _, event -> if (event is RecordedPlaybackEvent.SubmittedPlay) submitted += event }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            repeat(25) { f.probe.position += 1_000; shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000)) }
            assertEquals(1, submitted.size, "The control requires 25 s of a 40 s track to have crossed the threshold")
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(DomainError.Transport.Unreachable, f.controller.state.value.error, "The control requires the failure line")
            f.controller.togglePlayPause()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("t1", "t1"), f.prepared.map { it.itemId.rawId }, "Play begins a further attempt for the entry")
            assertEquals(f.prepared[0].playbackSessionId, f.prepared[1].playbackSessionId, "The retry is the same play, in the same session")
            // The engine loads the retried item from its start; the controller moves it to the saved position.
            f.probe.position = 0
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(25_000L), f.probe.seekCommands, "The retry resumes at the position the failure saved")
            assertTrue(f.probe.requested, "The engine is told to play the retry")
            while (f.probe.position < 40_000) {
                f.probe.position += 1_000
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
            }
            assertEquals(listOf("t1"), submitted.map { it.itemId.rawId }, "One listen interrupted by a failure is one play: $submitted")
        }
    }

    /**
     * The test above cannot tell "the accumulator carried across a retry" from "the accumulator
     * reset on retry": after its resume, the media left is enough for a play on its own. This one
     * can. Two connection failures, each after less than the scrobble threshold of progress; each
     * retried with Play. The total heard crosses the threshold only when the pieces are summed, so
     * exactly one play is submitted, in one session; a reset accumulator would submit none.
     */
    @Test fun retryCarriesTheAccumulatorAcrossAFailureSoTwoPiecesBelowTheThresholdCountOnce() {
        val submitted = mutableListOf<RecordedPlaybackEvent.SubmittedPlay>()
        Fixture(onDelivery = { _, event -> if (event is RecordedPlaybackEvent.SubmittedPlay) submitted += event }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            val duration = f.controller.state.value.durationMilliseconds
                ?: error("control: the fixture must publish the track's known duration")
            val threshold = (ScrobbleAccumulator.thresholdFor(duration.milliseconds)
                ?: error("control: the fixture track must be long enough to be eligible")).inWholeMilliseconds
            val first = threshold * 3 / 4
            val second = threshold / 2
            assertTrue(first < threshold && second < threshold, "control: each piece must be below the threshold alone")
            assertEquals(0, submitted.size, "control: nothing has played yet")
            ready(f)
            progress(f, 0, first)
            failConnection(f)
            play(f)
            ready(f)
            progress(f, first, first + second)
            failConnection(f)
            play(f)
            ready(f)
            assertEquals(3, f.prepared.size, "two failures, each retried: one attempt plus two retries")
            assertEquals(1, f.prepared.map { it.playbackSessionId }.toSet().size,
                "both retries stay inside the one session (spec §12.1)")
            assertEquals(1, submitted.size,
                "the pieces sum past the threshold only together: one play, not two and not none")
            assertEquals("t1", submitted.single().itemId.rawId)
        }
    }

    private fun ready(f: Fixture) {
        f.probe.position = 0
        f.probe.state = androidx.media3.common.Player.STATE_READY
        f.probe.events()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun progress(f: Fixture, from: Long, to: Long) {
        f.probe.position = from
        while (f.probe.position < to) {
            f.probe.position += 1_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))
        }
    }

    private fun failConnection(f: Fixture) {
        f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun play(f: Fixture) {
        f.controller.togglePlayPause()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun aResolutionFailureThatIsTheTracksOwnIsSkippedToo() {
        Fixture(resolve = { r ->
            if (r.itemId.rawId == "t1") PlaybackResolutionResult.Failed(DomainError.Server.Known(70)) else resolved(r)
        }).use { f ->
            f.controller.playQueue(album("t1", "t2"), 0, AndroidQueueSource.Album, "Album", "album-id")
            val state = f.controller.state.value
            assertEquals(listOf("t2"), f.prepared.map { it.itemId.rawId }, "The core must hear a failure before the engine had it")
            assertEquals(1, state.currentIndex)
            assertNull(state.error)
            assertEquals("Title t1", state.skipNotice?.title)
        }
    }

    @Test fun aSongTheServerNoLongerHasIsTheTracksOwnFailure() {
        Fixture(loadSong = { id -> if (id == "t2") missingSong() else song(id) }).use { f ->
            f.controller.playQueue(album("t1", "t2", "t3"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.state = androidx.media3.common.Player.STATE_ENDED
            f.probe.events()
            val state = f.controller.state.value
            assertEquals(listOf("t1", "t3"), f.prepared.map { it.itemId.rawId }, "Code 70 names the song, not the server")
            assertEquals(2, state.currentIndex)
            assertNull(state.error)
            assertEquals("Title t2", state.skipNotice?.title)
        }
    }

    @Test fun anAutomaticSkipGetsOnePassUnlessThePersonPresses() {
        // Play reaches the core on both of its paths: with the engine holding the entry, and after
        // Stop, which leaves the engine nothing, so Play restarts the entry. Both begin a new pass.
        for (press in listOf("nothing", "pause-then-play", "stop-then-play")) Fixture().use { f ->
            val pressesPlay = press != "nothing"
            f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.controller.cycleRepeatMode()
            assertEquals(AndroidRepeatMode.All, f.controller.state.value.repeatMode)
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId }, "The control requires a skip past a")
            // b plays a moment, which ends the chain but not the pass (spec §12.12 rule 3).
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 1_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertEquals("Progressing", f.controller.state.value.phase, "The control requires b to have progressed")
            when (press) {
                "pause-then-play" -> { f.controller.pause(); f.controller.play() }
                "stop-then-play" -> {
                    f.controller.stop()
                    f.controller.play()
                    assertEquals(listOf("a", "b", "b"), f.prepared.map { it.itemId.rawId }, "Play after Stop starts b again")
                    f.probe.state = androidx.media3.common.Player.STATE_READY
                    f.probe.events()
                }
            }
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            val state = f.controller.state.value
            if (pressesPlay) {
                assertEquals(if (press == "stop-then-play") listOf("a", "b", "b", "a") else listOf("a", "b", "a"),
                    f.prepared.map { it.itemId.rawId }, "The person's Play ($press) begins a new pass, so the skip may reach a again")
                assertNull(state.error)
                assertEquals("Title b", state.skipNotice?.title)
            } else {
                assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId },
                    "a was skipped past in this pass, so the queue stops on b instead of looping")
                assertEquals(DomainError.Playback.NoPlayableSource, state.error)
                assertEquals("Title a", state.skipNotice?.title, "The earlier notice is not replaced by a stop")
            }
        }
    }

    @Test fun aPlayPressedWhileTheNextEntryIsStillResolvingBeginsANewPass() {
        for (pressesPlay in listOf(false, true)) {
            var pending: Continuation<PlaybackResolutionResult>? = null
            var request: PlaybackResolveRequest? = null
            Fixture(resolve = { r ->
                if (r.itemId.rawId == "b" && request == null) { request = r; suspendCoroutine { pending = it } } else resolved(r)
            }).use { f ->
                f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
                f.controller.cycleRepeatMode()
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                val held = assertNotNull(pending, "The control requires the skip to b to be resolving")
                assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId })
                if (pressesPlay) f.controller.play()
                held.resume(resolved(assertNotNull(request)))
                assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId }, "The control requires b to reach the engine")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                f.probe.position = 1_000
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
                assertEquals("Progressing", f.controller.state.value.phase, "The control requires b to have progressed")
                f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                assertEquals(if (pressesPlay) listOf("a", "b", "a") else listOf("a", "b"), f.prepared.map { it.itemId.rawId },
                    "A Play pressed while b was still resolving begins a new pass; without it a stays skipped")
            }
        }
    }

    /**
     * `play()`'s branch for an entry that is still being resolved, with a pass that is not empty.
     * a fails before the engine has it, so the engine holds nothing while the skip resolves b and
     * Play takes that branch. The person's Play there must begin a new pass, or a later failure of
     * b stops the queue instead of reaching a again under repeat-all (spec §12.12 rule 3).
     */
    @Test fun pauseThenPlayWhileTheEntrySkippedToIsResolvingBeginsANewPass() {
        for (pressesPlay in listOf(false, true)) {
            var pending: Continuation<PlaybackResolutionResult>? = null
            var request: PlaybackResolveRequest? = null
            val resolvedIds = mutableListOf<String>()
            Fixture(resolve = { r ->
                resolvedIds += r.itemId.rawId
                when {
                    r.itemId.rawId == "a" -> PlaybackResolutionResult.Failed(DomainError.Server.Known(70))
                    r.itemId.rawId == "b" && request == null -> { request = r; suspendCoroutine { pending = it } }
                    else -> resolved(r)
                }
            }).use { f ->
                // Repeat-all is set on an earlier queue, so that setting it does not begin the pass
                // this test needs to be non-empty.
                f.controller.playQueue(album("x"), 0, AndroidQueueSource.Album, "Album", "album-x")
                f.controller.cycleRepeatMode()
                assertEquals(AndroidRepeatMode.All, f.controller.state.value.repeatMode)
                f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
                val held = assertNotNull(pending, "The control requires the skip to b to be resolving")
                assertEquals("Title a", f.controller.state.value.skipNotice?.title, "The control requires a skipped")
                assertEquals(listOf("x"), f.prepared.map { it.itemId.rawId },
                    "The control requires the engine to hold nothing of this queue, so Play takes the resolving branch")
                assertEquals(AndroidRepeatMode.All, f.controller.state.value.repeatMode, "The control requires repeat-all")
                if (pressesPlay) { f.controller.pause(); f.controller.play() }
                held.resume(resolved(assertNotNull(request)))
                assertEquals(listOf("x", "b"), f.prepared.map { it.itemId.rawId }, "The control requires b to reach the engine")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                f.probe.position = 1_000
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
                assertEquals("Progressing", f.controller.state.value.phase, "The control requires b to have progressed")
                f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                assertEquals(if (pressesPlay) 2 else 1, resolvedIds.count { it == "a" },
                    "pressesPlay=$pressesPlay: a Play while b resolved begins a new pass, so the skip may reach a again")
            }
        }
    }

    /**
     * Plays a to [position] and ends its attempt [how] -- a skip past its failure, or a natural end
     * -- with b held resolving, so the engine still holds a's attempt, which is over. Returns the
     * continuation that lets b resolve.
     */
    private fun endAWhileBResolves(f: Fixture, how: String, position: Long, held: () -> Continuation<PlaybackResolutionResult>?):
        Continuation<PlaybackResolutionResult> {
        f.controller.playQueue(album("a", "b"), 0, AndroidQueueSource.Album, "Album", "album-id")
        f.probe.state = androidx.media3.common.Player.STATE_READY
        f.probe.events()
        f.probe.position = position
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertTrue(f.controller.state.value.canRestart, "$how: the control requires a restart offered while a plays")
        when (how) {
            "skip" -> f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            "natural end" -> { f.probe.state = androidx.media3.common.Player.STATE_ENDED; f.probe.events() }
            else -> error(how)
        }
        val pending = assertNotNull(held(), "$how: the control requires b to be resolving")
        assertEquals(1, f.controller.state.value.currentIndex, "$how: the control requires the queue on b")
        assertEquals(listOf("a"), f.prepared.map { it.itemId.rawId }, "$how: the control requires the engine to hold a")
        return pending
    }

    /**
     * Once the core has moved past the engine's attempt -- a skip past its failure, or a natural end
     * -- nothing seeks or restarts that attempt while the next entry resolves: restart is not
     * offered, a seek from the app or the media session reaches nothing, and Previous moves to the
     * entry before instead of seeking a track that is over.
     */
    @Test fun whileTheNextEntryResolvesNothingSeeksOrRestartsTheAttemptThatIsOver() {
        for (how in listOf("skip", "natural end")) {
            var pending: Continuation<PlaybackResolutionResult>? = null
            var request: PlaybackResolveRequest? = null
            Fixture(resolve = { r ->
                if (r.itemId.rawId == "b" && request == null) { request = r; suspendCoroutine { pending = it } } else resolved(r)
            }).use { f ->
                val held = endAWhileBResolves(f, how, if (how == "skip") 4_000 else 39_000) { pending }
                assertFalse(f.controller.state.value.canRestart, "$how: a is over, so there is nothing to restart")
                f.controller.seek(1_000)
                f.controller.sessionPlayer.seekTo(2_000)
                f.controller.sessionPlayer.seekToDefaultPosition()
                assertEquals(emptyList(), f.probe.seekCommands, "$how: no seek may reach the attempt that is over")
                f.controller.skipToPrevious()
                assertEquals(emptyList(), f.probe.seekCommands, "$how: Previous must not seek the attempt that is over")
                assertEquals(0, f.controller.state.value.currentIndex, "$how: Previous moves to the entry before")
                held.resume(resolved(assertNotNull(request)))
                assertEquals(listOf("a", "a"), f.prepared.map { it.itemId.rawId }, "$how: a starts again, and the abandoned b does not")
            }
        }
    }

    /**
     * Pause while the next entry resolves reaches the engine at once, so the media session stops
     * reporting that it is asked to play, and the entry that resolves then starts paused.
     */
    @Test fun pauseWhileTheNextEntryResolvesTellsTheEngineAtOnce() {
        for (how in listOf("skip", "natural end")) {
            var pending: Continuation<PlaybackResolutionResult>? = null
            var request: PlaybackResolveRequest? = null
            Fixture(resolve = { r ->
                if (r.itemId.rawId == "b" && request == null) { request = r; suspendCoroutine { pending = it } } else resolved(r)
            }).use { f ->
                f.controller.play()
                val held = endAWhileBResolves(f, how, 4_000) { pending }
                assertTrue(f.probe.requested, "$how: the control requires the engine asked to play")
                f.controller.pause()
                assertFalse(f.probe.requested, "$how: Pause must reach the engine while b resolves")
                assertFalse(f.controller.sessionPlayer.playWhenReady, "$how: the media session must not report playing")
                assertFalse(f.controller.state.value.playWhenReady, "$how: the app must show Play")
                assertNull(f.controller.state.value.error, "$how: a Pause the engine refuses is not a failure")
                held.resume(resolved(assertNotNull(request)))
                assertEquals(listOf("a", "b"), f.prepared.map { it.itemId.rawId }, "$how: the control requires b prepared")
                assertFalse(f.probe.requested, "$how: b starts paused")
            }
        }
    }

    /**
     * After the queue ends, the system's Play -- the notification's, the lock screen's, a headset's
     * -- goes through Media3's play-button handling: on an ended player it seeks to the default
     * position and then plays. Neither may replay the attempt that is over; Play begins a new one
     * through the core.
     */
    @Test fun theSystemsPlayAfterTheQueueEndsBeginsANewAttemptAndReplaysNothing() {
        Fixture().use { f ->
            f.controller.playQueue(album("only"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 39_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            val first = f.controller.state.value.playbackSessionId
            f.probe.state = androidx.media3.common.Player.STATE_ENDED
            f.probe.events()
            assertNull(f.controller.state.value.playbackSessionId, "The control requires the queue to have ended")
            // Media3 resumes a session through its media-item commands only when both are offered;
            // with neither, its Play is the play-button handling below and nothing else.
            val commands = f.controller.sessionPlayer.availableCommands
            assertFalse(commands.contains(androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM))
            assertFalse(commands.contains(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS))
            assertTrue(androidx.media3.common.util.Util.shouldShowPlayButton(f.controller.sessionPlayer),
                "The control requires the system to offer Play")
            assertTrue(androidx.media3.common.util.Util.handlePlayButtonAction(f.controller.sessionPlayer),
                "The control requires Media3 to have acted on Play")
            assertEquals(emptyList(), f.probe.seekCommands, "The ended attempt must not be sought back to its start")
            assertEquals(listOf("only", "only"), f.prepared.map { it.itemId.rawId }, "Play begins a new attempt")
            val second = assertNotNull(f.controller.state.value.playbackSessionId, "Play begins a new session")
            assertNotEquals(first, second)
            assertTrue(f.controller.state.value.playWhenReady)
        }
    }

    /**
     * Previous on the first entry of a stream that cannot seek restarts it as a new session
     * (`restartCurrent`, which begins no pass itself). The person pressed Previous, so a new pass
     * begins there as it does wherever Previous moves (spec §12.12 rule 3).
     */
    @Test fun previousRestartingAnUnseekableFirstEntryBeginsANewPass() {
        for (pressesPrevious in listOf(false, true)) Fixture().use { f ->
            f.controller.playQueue(album("a", "b", "c"), 2, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.controller.previous()
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            assertEquals(listOf("c", "b", "a"), f.prepared.map { it.itemId.rawId },
                "The control requires b reached by Previous, then skipped backward to a")
            f.probe.seekable = false
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 1_000
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertEquals("Progressing", f.controller.state.value.phase, "The control requires a to have progressed")
            assertFalse(f.controller.state.value.seekable, "The control requires a stream that cannot seek")
            if (pressesPrevious) {
                f.controller.skipToPrevious()
                assertEquals(listOf("c", "b", "a", "a"), f.prepared.map { it.itemId.rawId },
                    "The control requires Previous to restart a as a new session")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
            }
            f.probe.fail(androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
            val state = f.controller.state.value
            if (pressesPrevious) {
                assertEquals("b", f.prepared.last().itemId.rawId, "Previous began a new pass, so the skip may reach b again")
                assertNull(state.error)
            } else {
                assertEquals("a", f.prepared.last().itemId.rawId, "b was skipped past in this pass, so the queue stops on a")
                assertEquals(DomainError.Playback.NoPlayableSource, state.error)
            }
        }
    }

    @Test fun everySystemSeekVerbGoesThroughTheController() {
        Fixture().use { f ->
            f.controller.playQueue(album("a"), 0, AndroidQueueSource.Album, "Album", "album-id")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            f.probe.position = 10_000
            val session = f.controller.sessionPlayer
            session.seekForward()                // 10 s + 15 s
            session.seekBack()                   // 25 s - 5 s
            session.seekToDefaultPosition()      // 0
            session.seekTo(3, 7_000)             // index 3 names nothing in a one-item timeline
            session.seekToDefaultPosition(2)
            assertEquals(listOf(25_000L, 20_000L, 0L), f.probe.seekCommands)
        }
    }

    @Test fun restoredTitlesComeFromLocalRowsAndAreNeverRereadPerPublication() {
        val songs = (1..60).map { "s$it" }
        val loaded = mutableListOf<String>()
        Fixture(savedOwner = OWNER, savedSongs = songs, loadSong = { id -> loaded += id; song(id) }).use { f ->
            val afterRestore = loaded.size
            assertTrue(afterRestore in 2..26, "Restoration reads a bounded window, not the queue: $afterRestore")
            f.probe.state = androidx.media3.common.Player.STATE_READY
            f.probe.events()
            repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500)); f.probe.events() }
            assertEquals(afterRestore, loaded.size, "Publications must not issue further metadata requests")
        }
        loaded.clear()
        Fixture(savedOwner = OWNER, savedSongs = songs, loadSong = { id -> loaded += id; song(id) }).use { f ->
            val before = loaded.size
            f.controller.rememberTracks(songs.map { AndroidTrack(OWNER, it, "Local $it") })
            f.controller.next()
            assertEquals("Local s2", f.controller.state.value.queue[1].track.title)
            assertTrue(loaded.size - before <= 1, "Titles already held locally must not be requested again")
        }
    }

    @Test fun productionPlayerConsumesAuthenticatedValidatedBytes() {
        val audio = pcmWave()
        PlaybackResourceReceiver(audio).use { receiver ->
            Fixture(realPlayer = true, baseUrl = receiver.url, resolve = { resolved(it) }).use { f ->
                assertEquals(0, f.controller.sessionPlayer.mediaItemCount)
                f.controller.playSong(OWNER, "source-song", "Source song")
                val player = f.controller.sessionPlayer
                assertEquals(1, player.mediaItemCount)
                val item = player.getMediaItemAt(0)
                assertEquals(f.controller.state.value.attemptId, item.mediaId)
                assertEquals("Source song", item.mediaMetadata.title.toString())
                assertEquals("dulcet://resource", item.localConfiguration?.uri.toString())
                assertTrue(player.playWhenReady)
                awaitPlayer { f.controller.state.value.validatedBytesConsumed >= audio.size }
                assertTrue(f.controller.state.value.validatedBytesConsumed >= audio.size,
                    "The real player must consume the HTTP audio through the validating source and its byte counter")
                receiver.assertAuthenticatedSong("source-song")
                println("PRODUCTION SOURCE OBSERVED authenticated-http=true validated-bytes=${f.controller.state.value.validatedBytesConsumed} fixture-bytes=${audio.size} decoder-progression-not-claimed=true")
            }
        }
    }

    @Test fun productionPlayerRejectsAnHttpEnvelopeBeforeConsumingMediaBytes() {
        val envelope = "<subsonic-response><error code=\"40\"/></subsonic-response>".toByteArray()
        PlaybackResourceReceiver(envelope).use { receiver ->
            Fixture(realPlayer = true, baseUrl = receiver.url, resolve = { resolved(it) }).use { f ->
                f.controller.playSong(OWNER, "rejected-song", "Rejected song")
                awaitPlayer { f.controller.state.value.error != null }
                receiver.assertAuthenticatedSong("rejected-song")
                assertEquals(DomainError.Auth.InvalidCredentials, f.controller.state.value.error,
                    "The production player must surface the validator's envelope error")
                assertEquals(0L, f.controller.state.value.validatedBytesConsumed)
            }
        }
    }

    private fun awaitPlayer(done: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(10)
        }
    }

    private fun pcmWave(): ByteArray = java.nio.ByteBuffer.allocate(44 + 16000)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 16000); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(8000)
            putShort(1); putShort(8); put("data".toByteArray()); putInt(16000)
            put(ByteArray(16000) { 128.toByte() })
        }.array()

    /** Supplies only the receiving socket. The controller builds its real HTTP resource and validator. */
    private class PlaybackResourceReceiver(private val body: ByteArray) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        private val requests = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
        private val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            client.soTimeout = 5000
                            val reader = client.getInputStream().bufferedReader()
                            val line = assertNotNull(reader.readLine()).split(' ')
                            val uri = java.net.URI(line[1])
                            assertEquals("GET", line[0])
                            assertEquals("/rest/stream.view", uri.path)
                            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                            val query = uri.rawQuery.split('&').associate {
                                val pair = it.split('=', limit = 2)
                                java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
                            }
                            requests += query
                            val range = headers.firstOrNull { it.startsWith("Range:", true) }?.substringAfter("bytes=")
                            val start = range?.substringBefore('-')?.toInt() ?: 0
                            val end = range?.substringAfter('-')?.toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
                            val bytes = body.copyOfRange(start, end + 1)
                            val response = if (range == null) "200 OK" else "206 Partial Content"
                            val rangeHeader = if (range == null) "" else "Content-Range: bytes $start-$end/${body.size}\r\n"
                            client.getOutputStream().apply {
                                write(("HTTP/1.1 $response\r\nContent-Type: application/octet-stream\r\nContent-Length: ${bytes.size}\r\n$rangeHeader" +
                                    "Connection: close\r\n\r\n").toByteArray())
                                write(bytes); flush()
                            }
                        }
                    } catch (error: Throwable) { if (!socket.isClosed) failures += error }
                }
            }
        }
        fun assertAuthenticatedSong(id: String) {
            assertTrue(requests.isNotEmpty(), "The production source must reach the receiving HTTP socket")
            for (query in requests) {
                assertEquals(id, query["id"])
                assertEquals("controller-canary", query["u"])
                val salt = assertNotNull(query["s"])
                assertEquals(AccountConnectionContract.saltedToken("controller-password-canary", salt), query["t"])
            }
        }
        override fun close() {
            socket.close(); executor.shutdown()
            assertTrue(executor.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "Playback receiving fixture failed: $failures")
        }
    }

    @Test fun progressionSendsSubmittedScrobbleThroughTheLiveConsumerWorkerAndSender() {
        ScrobbleReceiver().use { receiver ->
            Fixture(baseUrl = receiver.url, onDelivery = null, resolve = { resolved(it) }).use { f ->
                f.controller.playSong(OWNER, "live-song", "Live song")
                f.probe.state = androidx.media3.common.Player.STATE_READY
                f.probe.events()
                repeat(46) {
                    f.probe.position += 500
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
                }
                val outbox = PersistentScrobbleOutbox(f.store.database, OutboxWallClock { System.currentTimeMillis() })
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while ((receiver.requests.none { it["submission"] == "true" } || outbox.pending(ServerId(OWNER)).isNotEmpty()) &&
                    System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                val submitted = receiver.requests.filter { it["submission"] == "true" }
                assertEquals(1, submitted.size, "The receiving socket must observe a submitted scrobble")
                assertEquals("live-song", submitted.single()["id"])
                assertTrue(assertNotNull(submitted.single()["time"]).toLong() > 0)
                assertTrue(receiver.requests.any { it["submission"] == "false" && it["id"] == "live-song" },
                    "The same live consumer must send now-playing")
                assertTrue(outbox.pending(ServerId(OWNER)).isEmpty(), "A successful server response must acknowledge the outbox row")
            }
        }
    }

    /** Receives real HTTP from the production sender. No controller handoff or sender is replaced. */
    private class ScrobbleReceiver : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}"
        val requests = java.util.concurrent.CopyOnWriteArrayList<Map<String, String>>()
        private val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        init {
            executor.submit {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client ->
                            client.soTimeout = 5000
                            val reader = client.getInputStream().bufferedReader()
                            val line = assertNotNull(reader.readLine()).split(' ')
                            val uri = java.net.URI(line[1])
                            assertEquals("GET", line[0])
                            assertEquals("/rest/scrobble.view", uri.path)
                            while (!reader.readLine().isNullOrEmpty()) { /* drain request headers */ }
                            val query = uri.rawQuery.split('&').associate {
                                val pair = it.split('=', limit = 2)
                                java.net.URLDecoder.decode(pair[0], "UTF-8") to java.net.URLDecoder.decode(pair[1], "UTF-8")
                            }
                            requests += query
                            val body = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""".toByteArray()
                            client.getOutputStream().apply {
                                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(body); flush()
                            }
                        }
                    } catch (error: Throwable) { if (!socket.isClosed) failures += error }
                }
            }
        }
        override fun close() {
            socket.close(); executor.shutdown()
            assertTrue(executor.awaitTermination(6, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), "Scrobble receiving fixture failed: $failures")
        }
    }

    private class Fixture(
        savedOwner: String? = null,
        savedSongs: List<String> = listOf("saved-song"),
        loadSong: suspend (String) -> AuthenticatedEndpointResponse = { song(it) },
        resolve: (suspend (PlaybackResolveRequest) -> PlaybackResolutionResult)? = null,
        onDelivery: ((Fixture, RecordedPlaybackEvent) -> Unit)? = { _, _ -> },
        realPlayer: Boolean = false,
        baseUrl: String = "http://127.0.0.1:4533",
    ) : AutoCloseable {
        private val context = RuntimeEnvironment.getApplication()
        private val databaseName = "playback-controller-${java.util.UUID.randomUUID()}.db"
        // JDBC's process-wide DriverManager retains sandbox-loaded drivers, which are invisible
        // to ordinary host tests. Use the production Android driver in this Android sandbox.
        val store = DulcetDatabaseStore.open(DulcetDriverFactory(context, databaseName).createDriver())
        val probe = PlayerProbe()
        val prepared = mutableListOf<RemotePlaybackWirePlan>()
        val controller: AndroidPlaybackController
        init {
            if (savedOwner != null) {
                PlaybackQueueController(PersistentQueueStore(store.database), PersistentResumePositionStore(store.database),
                    PlaybackIdentitySource { "$it:${java.util.UUID.randomUUID()}" }).replaceAndStart(PlaybackQueueRequest(
                    savedSongs.map { PlaybackQueueItem(ProviderItemId(savedOwner, it), 40.seconds) },
                    QueueSourceContext(QueueSourceKind.Search, null, "Search"), 0, false))
            }
            controller = AndroidPlaybackController(RuntimeEnvironment.getApplication(),
                PlaybackEndpointAccount(OWNER, baseUrl, "controller-canary", "controller-password-canary", true),
                AndroidPlaybackControllerBoundaries(store, if (realPlayer) null else probe.player,
                    if (realPlayer) null else { plan -> prepared += plan }, loadSong, resolve,
                    onDelivery?.let { callback -> { event -> callback(this, event) } }))
        }
        override fun close() {
            try {
                controller.close()
                assertNull(probe.listener, "Controller shutdown must detach the Media3 listener")
                controller.close() // Service teardown may close the owner more than once.
            } finally {
                store.close()
                assertTrue(context.deleteDatabase(databaseName), "The fixture database must be deleted")
                assertFalse(context.getDatabasePath(databaseName).exists())
            }
        }
    }

    companion object {
        private const val OWNER = "provider:owner"
        private fun album(vararg ids: String) = ids.map {
            AndroidTrack(OWNER, it, "Title $it", "Artist", "Album", 40_000, "cover-$it")
        }
        private fun resolved(r: PlaybackResolveRequest) = PlaybackResolutionResult.Resolved(RemotePlaybackWirePlan(
            r.playbackSessionId, r.attemptId, r.itemId, PlaybackDeliveryPath.Legacy, PlaybackDeliveryProtocol.HttpProgressive,
            r.sourceContainer, PlaybackWireTranscodeDecision.LegacyHint(null, null), endpoint = "stream",
            parameters = mapOf("id" to r.itemId.rawId), resolutionRequest = r))
        private fun missingSong() = AuthenticatedEndpointResponse(200,
            """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"data not found"}}}""".toByteArray(),
            "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
            RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
                emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))
        private fun song(id: String, suffix: String = "wav") = AuthenticatedEndpointResponse(200,
            """{"subsonic-response":{"status":"ok","song":{"id":"$id","suffix":"$suffix","duration":40}}}""".toByteArray(),
            "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
            RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
                emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))
    }
}
