package com.legitimateapps.dulcet.core

import android.os.Looper
import kotlinx.coroutines.Dispatchers
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
        private fun song(id: String, suffix: String = "wav") = AuthenticatedEndpointResponse(200,
            """{"subsonic-response":{"status":"ok","song":{"id":"$id","suffix":"$suffix","duration":40}}}""".toByteArray(),
            "<redacted-url>", AuthenticatedEndpointResponseHeaders("application/json", null, null, null, null),
            RequestTrace.observed("getSong", "GET", "<redacted-url>", AuthenticationLocation.None,
                emptySet(), emptySet(), emptySet(), AccountConnectionContract.protocolVersion, null))
    }
}
