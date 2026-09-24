package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Try Again after any failure keeps the session (§12.1), so one listen is one play however many
 * attempts it took (§15.2, §15.3). Driven through the queue controller with the resume-position
 * effects executed as the platform facades execute them, counting the plays the core submits.
 */
class PlaybackRetryScrobbleTest {
    private class Rig(duration: Duration, rawIds: List<String> = listOf("a", "b")) {
        val driver = createTestDriver()
        private val database = DulcetDatabaseStore.open(driver).database
        private val resumePositions = PersistentResumePositionStore(database)
        private var identity = 0
        private var monotonicSeconds = 0L
        val controller = PlaybackQueueController(
            queues = PersistentQueueStore(database),
            resumePositions = resumePositions,
            identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
        )
        private val effects = mutableListOf<PlaybackCoreEffect>()
        val request = PlaybackQueueRequest(
            items = rawIds.map { PlaybackQueueItem(ProviderItemId(SERVER.value, it), duration) },
            sourceContext = QueueSourceContext(
                kind = QueueSourceKind.Album,
                sourceId = ProviderItemId(SERVER.value, "album"),
                displayName = "Album",
            ),
            startIndex = 0,
            shuffle = false,
        )

        fun apply(emitted: List<PlaybackCoreEffect>) {
            effects += emitted
            emitted.forEach { effect ->
                when (effect) {
                    is PlaybackCoreEffect.PersistResumePosition ->
                        resumePositions.save(effect.itemId, effect.position)
                    is PlaybackCoreEffect.ClearResumePosition -> resumePositions.clear(effect.itemId)
                    else -> Unit
                }
            }
        }

        fun event(event: PlaybackEngineEvent) = apply(controller.recordPlaybackEvent(event).effects)

        fun retry(): PlaybackQueueStartDirective {
            val transition = controller.retryCurrent()
            apply(transition.effects)
            return assertNotNull(transition.startDirective, "Try Again must start something")
        }

        /** Plays [attemptId] from [from] to [to] seconds, one position sample per second. */
        fun play(attemptId: AttemptId, duration: Duration, from: Int, to: Int) {
            event(PlaybackEngineEvent.Ready(attemptId, duration, PlaybackSeekability.Seekable))
            event(
                PlaybackEngineEvent.PlaybackProgressBegan(
                    attemptId,
                    PlaybackWallClockTime(1_788_000_000_000 + monotonicSeconds * 1_000L),
                    from.seconds,
                ),
            )
            for (second in (from + 1)..to) {
                event(
                    PlaybackEngineEvent.PositionChanged(
                        attemptId,
                        second.seconds,
                        PlaybackMonotonicTime((monotonicSeconds++).seconds),
                    ),
                )
            }
        }

        val submittedPlays: Int
            get() = effects.count { effect ->
                (effect as? PlaybackCoreEffect.RecordPlaybackEvent)?.event is RecordedPlaybackEvent.SubmittedPlay
            }
    }

    private inline fun rig(duration: Duration, rawIds: List<String> = listOf("a", "b"), body: (Rig) -> Unit) {
        val rig = Rig(duration, rawIds)
        try {
            body(rig)
        } finally {
            rig.driver.close()
        }
    }

    /** The instrument's positive control: an uninterrupted listen is counted, exactly once. */
    @Test
    fun oneUninterruptedListenSubmitsOnePlay() = rig(600.seconds) { rig ->
        val start = assertNotNull(rig.controller.replaceAndStart(rig.request).startDirective)
        rig.play(start.attemptId, 600.seconds, 0, 600)
        rig.event(PlaybackEngineEvent.EndedNaturally(start.attemptId, 600.seconds))
        assertEquals(1, rig.submittedPlays)
    }

    /** Retried in a new session, the second half submitted a second play of the same listen. */
    @Test
    fun aTenMinuteTrackThatFailsAtFiveMinutesAndIsRetriedSubmitsOnePlay() = rig(600.seconds) { rig ->
        val start = assertNotNull(rig.controller.replaceAndStart(rig.request).startDirective)
        rig.play(start.attemptId, 600.seconds, 0, 300)
        // The case needs the first half to have crossed the four-minute threshold already.
        assertEquals(1, rig.submittedPlays, "the first half must already count as a play")
        rig.event(
            PlaybackEngineEvent.FailedAfterPartial(start.attemptId, 300.seconds, DomainError.Transport.Unreachable),
        )

        val retried = rig.retry()
        assertEquals(start.playbackSessionId, retried.playbackSessionId, "same session")
        assertNotEquals(start.attemptId, retried.attemptId, "new attempt")
        assertEquals(300.seconds, retried.resumePosition, "from where it stopped")
        rig.play(retried.attemptId, 600.seconds, 300, 600)
        rig.event(PlaybackEngineEvent.EndedNaturally(retried.attemptId, 600.seconds))

        assertEquals(1, rig.submittedPlays, "one listen, one play")
    }

    /** Split across three sessions, no one session reached the threshold, so nothing counted. */
    @Test
    fun aFiveMinuteTrackHeardInFullAcrossTwoPartialFailuresSubmitsOnePlay() = rig(300.seconds) { rig ->
        val start = assertNotNull(rig.controller.replaceAndStart(rig.request).startDirective)
        rig.play(start.attemptId, 300.seconds, 0, 100)
        rig.event(
            PlaybackEngineEvent.FailedAfterPartial(start.attemptId, 100.seconds, DomainError.Transport.Unreachable),
        )
        // Each piece is under the threshold (half of five minutes): the case is only interesting
        // because no single attempt reaches it.
        assertEquals(0, rig.submittedPlays)

        val first = rig.retry()
        assertEquals(start.playbackSessionId, first.playbackSessionId)
        assertEquals(100.seconds, first.resumePosition)
        rig.play(first.attemptId, 300.seconds, 100, 200)
        rig.event(
            PlaybackEngineEvent.FailedAfterPartial(first.attemptId, 200.seconds, DomainError.Transport.Unreachable),
        )

        val second = rig.retry()
        assertEquals(start.playbackSessionId, second.playbackSessionId)
        assertEquals(200.seconds, second.resumePosition)
        rig.play(second.attemptId, 300.seconds, 200, 300)
        rig.event(PlaybackEngineEvent.EndedNaturally(second.attemptId, 300.seconds))

        assertEquals(1, rig.submittedPlays, "the accumulator carries across the attempts")
    }

    @Test
    fun tryingAgainTwiceBeforeStartStaysInOneSession() = rig(180.seconds) { rig ->
        val start = assertNotNull(rig.controller.replaceAndStart(rig.request).startDirective)
        rig.event(PlaybackEngineEvent.FailedBeforeStart(start.attemptId, DomainError.Transport.Unreachable))
        val first = rig.retry()
        assertEquals(start.playbackSessionId, first.playbackSessionId)
        rig.event(PlaybackEngineEvent.FailedBeforeStart(first.attemptId, DomainError.Transport.Unreachable))
        val second = rig.retry()
        assertEquals(start.playbackSessionId, second.playbackSessionId)
        assertNotEquals(first.attemptId, second.attemptId)
        rig.play(second.attemptId, 180.seconds, 0, 180)
        rig.event(PlaybackEngineEvent.EndedNaturally(second.attemptId, 180.seconds))
        assertEquals(1, rig.submittedPlays)
    }

    /** Repeat-one is a next-item boundary (§12.1), but Try Again is not: it stays on the entry. */
    @Test
    fun tryingAgainUnderRepeatOneAfterAPartialFailureResumesTheSameEntry() =
        rig(180.seconds, listOf("a")) { rig ->
            val start = assertNotNull(rig.controller.replaceAndStart(rig.request).startDirective)
            rig.controller.cycleRepeatMode()
            rig.controller.cycleRepeatMode()
            assertEquals(QueueRepeatMode.One, rig.controller.snapshot().repeatMode)
            rig.play(start.attemptId, 180.seconds, 0, 60)
            rig.event(
                PlaybackEngineEvent.FailedAfterPartial(start.attemptId, 60.seconds, DomainError.Transport.Unreachable),
            )

            val retried = rig.retry()
            assertEquals(start.queueEntryId, retried.queueEntryId)
            assertEquals(start.playbackSessionId, retried.playbackSessionId)
            assertEquals(60.seconds, retried.resumePosition)
            rig.play(retried.attemptId, 180.seconds, 60, 180)
            rig.event(PlaybackEngineEvent.EndedNaturally(retried.attemptId, 180.seconds))
            assertEquals(1, rig.submittedPlays)
        }

    private companion object {
        val SERVER = ServerId("server:retry-scrobble")
    }
}
