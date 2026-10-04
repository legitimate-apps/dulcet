package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A saved position resumes the interrupted session -- a relaunch, Try Again, Play after Stop --
 * and nothing else (§15.5). Every other start of an item plays it from the top.
 *
 * The shape this guards came from CI: three listens of a 29-second track, each paused after a few
 * seconds (9 s, then 12 s, then 7.7 s), each resumed where the last had paused, so the fourth
 * press of the album's Play began the track 28.7 s in and the queue moved past it a moment later.
 */
class PlaybackFreshStartTest {
    @Test
    fun playingTheAlbumAgainStartsItsFirstTrackFromTheTopHoweverFarEarlierListensReached() {
        val rig = Rig()
        var position = Duration.ZERO
        // Each listen begins where the last paused only if a saved position carries over.
        listOf(9.seconds, 12.seconds, 7_700.milliseconds).forEach { heard ->
            val pressed = rig.controller.replaceAndStart(rig.album)
            rig.apply(pressed.effects)
            val started = assertNotNull(pressed.startDirective)
            assertEquals("twenty-nine", started.itemId.rawId)
            assertNull(started.resumePosition, "the album's Play starts its first track from the top")
            position = heard
            rig.listen(started.attemptId, from = Duration.ZERO, pausedAt = position)
            // A relaunch between listens, as the CI phases did: the paused session is restored
            // where it paused -- the one start that does resume.
            rig.relaunch()
            val restored = assertNotNull(rig.controller.restoreCurrentPaused().startDirective)
            assertEquals(position, restored.resumePosition, "a relaunch restores the paused position")
        }

        val pressed = rig.controller.replaceAndStart(rig.album)
        rig.apply(pressed.effects)
        val directive = assertNotNull(pressed.startDirective)
        assertEquals("twenty-nine", directive.itemId.rawId)
        assertNull(directive.resumePosition, "not ${position.inWholeMilliseconds} ms in, a moment from its end")
        rig.close()
    }

    @Test
    fun aFreshStartClearsTheItemsSavedPositionSoALaterRelaunchCannotRestoreIt() {
        val rig = Rig()
        rig.store.save(rig.item("twenty-nine"), 28_700.milliseconds)

        val started = rig.controller.replaceAndStart(rig.album)
        rig.apply(started.effects)
        assertNull(assertNotNull(started.startDirective).resumePosition)
        // Killed before the new session saved anything of its own.
        assertNull(rig.store.restore(rig.item("twenty-nine")), "the stale position is gone")
        rig.relaunch()

        val restored = assertNotNull(rig.controller.restoreCurrentPaused().startDirective)
        assertEquals("twenty-nine", restored.itemId.rawId)
        assertNull(restored.resumePosition, "the relaunch restores the fresh session, not the old one")
        rig.close()
    }

    @Test
    fun nextJumpAndAutomaticAdvanceStartAnItemFromTheTopWhateverItsSavedPosition() {
        val rig = Rig()
        rig.store.save(rig.item("thirty-one"), 20.seconds)
        rig.store.save(rig.item("canary"), 15.seconds)
        val first = assertNotNull(rig.controller.replaceAndStart(rig.album).startDirective)

        val next = rig.controller.next()
        rig.apply(next.effects)
        assertEquals("thirty-one", assertNotNull(next.startDirective).itemId.rawId)
        assertNull(next.startDirective?.resumePosition, "Next")

        val canary = rig.controller.snapshot().entries.single { it.itemId.rawId == "canary" }
        val jumped = assertNotNull(rig.controller.jumpTo(canary.queueEntryId).startDirective)
        assertNull(jumped.resumePosition, "a tap on an Up Next row")

        rig.store.save(rig.item("thirty-one"), 20.seconds)
        val restarted = assertNotNull(rig.controller.replaceAndStart(rig.album).startDirective)
        rig.controller.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(restarted.attemptId, 29.seconds, PlaybackSeekability.Seekable),
        )
        rig.controller.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(restarted.attemptId, WALL_CLOCK, Duration.ZERO),
        )
        val advanced = rig.controller.recordPlaybackEvent(
            PlaybackEngineEvent.EndedNaturally(restarted.attemptId, 29.seconds),
        )
        val following = assertNotNull(advanced.startDirective, "the natural end starts the next entry")
        assertEquals("thirty-one", following.itemId.rawId)
        assertNull(following.resumePosition, "an automatic advance")
        assertEquals("twenty-nine", first.itemId.rawId)
        rig.close()
    }

    @Test
    fun playAfterAStopStillResumesWhereTheStopLeftTheSelectedEntry() {
        val rig = Rig()
        val started = assertNotNull(rig.controller.replaceAndStart(rig.album).startDirective)
        rig.controller.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(started.attemptId, 29.seconds, PlaybackSeekability.Seekable),
        )
        rig.controller.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(started.attemptId, WALL_CLOCK, Duration.ZERO),
        )
        rig.apply(
            rig.controller.recordPlaybackEvent(
                PlaybackEngineEvent.Skipped(started.attemptId, 11.seconds, PlaybackSkipReason.User),
            ).effects,
        )

        val restarted = rig.controller.restartAfterStop()
        rig.apply(restarted.effects)
        assertEquals(11.seconds, assertNotNull(restarted.startDirective).resumePosition)
        rig.close()
    }

    /**
     * An engine teardown (the system reclaiming the player, say) ends the session but not the
     * listen: the entry stays selected with no session, and Play there goes through
     * `startCurrent`. It is the same listen picked up again, so it resumes where it was cut off.
     */
    @Test
    fun playAfterAnEngineTeardownResumesTheListenItInterrupted() {
        val rig = Rig()
        val started = rig.controller.replaceAndStart(rig.album)
        rig.apply(started.effects)
        val attempt = assertNotNull(started.startDirective).attemptId
        rig.listen(attempt, from = Duration.ZERO, pausedAt = 9.seconds)
        rig.apply(
            rig.controller.recordPlaybackEvent(
                PlaybackEngineEvent.EngineTornDown(attempt, PlaybackEngineTeardownReason.SystemReclaimed),
            ).effects,
        )
        assertNull(rig.controller.snapshot().currentSession, "the control requires the teardown to end the session")
        assertEquals(9.seconds, rig.store.restore(rig.item("twenty-nine")), "the control requires the teardown to keep 9 s")

        val played = rig.controller.startCurrent()
        rig.apply(played.effects)
        val directive = assertNotNull(played.startDirective)
        assertEquals("twenty-nine", directive.itemId.rawId)
        assertEquals(9.seconds, directive.resumePosition, "Play resumes the interrupted listen")
        assertEquals(9.seconds, rig.store.restore(rig.item("twenty-nine")), "and keeps its saved position")
        rig.close()
    }

    /** A queue that ran out keeps its last entry selected; Play replays it from the top (§14.3). */
    @Test
    fun playAfterTheQueueRanOutStartsTheLastTrackFromTheTop() {
        val rig = Rig()
        val started = rig.controller.replaceAndStart(rig.album.copy(startIndex = 2))
        rig.apply(started.effects)
        val attempt = assertNotNull(started.startDirective).attemptId
        rig.listen(attempt, from = Duration.ZERO, pausedAt = 12.seconds, rawId = "canary")
        rig.controller.recordPlaybackEvent(PlaybackEngineEvent.Resumed(attempt, 12.seconds)).also { rig.apply(it.effects) }
        val ended = rig.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(attempt, 30.seconds))
        rig.apply(ended.effects)
        assertNull(ended.startDirective, "the control requires the queue to have run out")
        assertNull(ended.snapshot.currentSession)

        val played = rig.controller.startCurrent()
        rig.apply(played.effects)
        val directive = assertNotNull(played.startDirective)
        assertEquals("canary", directive.itemId.rawId)
        assertNull(directive.resumePosition, "Play after the last track replays it from the start")
        assertNull(rig.store.restore(rig.item("canary")))
        rig.close()
    }

    /**
     * Next past the last entry and Previous before the first one finish the queue too, with the
     * listen cut off part way. The person moved on from it, so Play there replays the entry from
     * the top -- as after any other finished queue -- and a relaunch cannot bring the position back.
     */
    @Test
    fun playAfterNextOrPreviousRanOffTheQueueStartsTheEntryFromTheTop() {
        for (step in listOf("next", "previous")) {
            val rig = Rig()
            val startIndex = if (step == "next") 2 else 0
            val rawId = if (step == "next") "canary" else "twenty-nine"
            val started = rig.controller.replaceAndStart(rig.album.copy(startIndex = startIndex))
            rig.apply(started.effects)
            rig.listen(assertNotNull(started.startDirective).attemptId, Duration.ZERO, 12.seconds, rawId)

            val finished = if (step == "next") rig.controller.next() else rig.controller.previous()
            rig.apply(finished.effects)
            assertNull(finished.startDirective, "$step: the control requires the queue to have finished")
            assertNull(finished.snapshot.currentSession, "$step: the control requires no session")
            assertNull(rig.store.restore(rig.item(rawId)), "$step: the finished queue keeps no position")

            val played = rig.controller.startCurrent()
            rig.apply(played.effects)
            val directive = assertNotNull(played.startDirective)
            assertEquals(rawId, directive.itemId.rawId)
            assertNull(directive.resumePosition, "$step: Play replays the entry from the top")
            rig.close()
        }
    }

    /**
     * The outgoing session writes its position first, and the fresh start clears it after: a live
     * paused session on an item, then a new queue that starts with that same item, leaves nothing
     * saved. In the other order the outgoing write would land last and survive the fresh start.
     */
    @Test
    fun aFreshStartOfTheItemAlreadyPlayingClearsAfterTheOutgoingSessionsOwnWrite() {
        val rig = Rig()
        val first = rig.controller.replaceAndStart(rig.album)
        rig.apply(first.effects)
        rig.listen(assertNotNull(first.startDirective).attemptId, from = Duration.ZERO, pausedAt = 9.seconds)

        val again = rig.controller.replaceAndStart(rig.album)
        val twentyNine = rig.item("twenty-nine")
        assertEquals(
            listOf<PlaybackCoreEffect>(
                PlaybackCoreEffect.PersistResumePosition(twentyNine, 9.seconds),
                PlaybackCoreEffect.ClearResumePosition(twentyNine),
            ),
            again.effects.filter {
                it is PlaybackCoreEffect.PersistResumePosition || it is PlaybackCoreEffect.ClearResumePosition
            },
            "the outgoing session's write, then the fresh start's clear",
        )
        rig.apply(again.effects)
        assertNull(assertNotNull(again.startDirective).resumePosition)
        assertNull(rig.store.restore(twentyNine), "nothing of the outgoing listen survives the fresh start")
        rig.relaunch()
        assertNull(assertNotNull(rig.controller.restoreCurrentPaused().startDirective).resumePosition)
        rig.close()
    }

    /** Try Again after a failure part way through resumes from where the failure stopped (§12.1). */
    @Test
    fun tryAgainAfterAFailurePartWayThroughResumesWhereItFailed() {
        val rig = Rig()
        val started = rig.controller.replaceAndStart(rig.album)
        rig.apply(started.effects)
        val attempt = assertNotNull(started.startDirective).attemptId
        rig.controller.recordPlaybackEvent(
            PlaybackEngineEvent.Ready(attempt, 29.seconds, PlaybackSeekability.Seekable),
        ).also { rig.apply(it.effects) }
        rig.controller.recordPlaybackEvent(
            PlaybackEngineEvent.PlaybackProgressBegan(attempt, WALL_CLOCK, Duration.ZERO),
        ).also { rig.apply(it.effects) }
        val failed = rig.controller.recordPlaybackEvent(
            PlaybackEngineEvent.FailedAfterPartial(attempt, 10.seconds, DomainError.Transport.Unreachable),
        )
        rig.apply(failed.effects)
        assertNull(failed.startDirective, "the control requires a connection failure to stay on the entry")

        val retried = rig.controller.retryCurrent()
        rig.apply(retried.effects)
        val directive = assertNotNull(retried.startDirective)
        assertEquals("twenty-nine", directive.itemId.rawId)
        assertEquals(10.seconds, directive.resumePosition, "Try Again resumes the listen the failure cut off")
        rig.close()
    }

    private class Rig {
        private val driver: SqlDriver = createTestDriver()
        private val database = DulcetDatabaseStore.open(driver).database
        val store = PersistentResumePositionStore(database)
        private var identity = 0
        private var launches = 0
        var controller = newController()
            private set

        val album = PlaybackQueueRequest(
            items = listOf("twenty-nine" to 29.seconds, "thirty-one" to 31.seconds, "canary" to 30.seconds)
                .map { (rawId, duration) -> PlaybackQueueItem(item(rawId), duration) },
            sourceContext = QueueSourceContext(
                kind = QueueSourceKind.Album,
                sourceId = ProviderItemId(SERVER, "threshold-boundary"),
                displayName = "Threshold Boundary",
            ),
            startIndex = 0,
            shuffle = false,
        )

        fun item(rawId: String) = ProviderItemId(SERVER, rawId)

        private fun newController(): PlaybackQueueController {
            val launch = launches++
            return PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = store,
                identities = PlaybackIdentitySource { prefix -> "$prefix:$launch:${identity++}" },
            )
        }

        /** The process ends, as a relaunch's terminate does: no session survives it. */
        fun relaunch() {
            controller = newController()
        }

        fun listen(attemptId: AttemptId, from: Duration, pausedAt: Duration, rawId: String = "twenty-nine") {
            controller.recordPlaybackEvent(
                PlaybackEngineEvent.Ready(attemptId, 29.seconds, PlaybackSeekability.Seekable),
            ).also { apply(it.effects) }
            controller.recordPlaybackEvent(
                PlaybackEngineEvent.PlaybackProgressBegan(attemptId, WALL_CLOCK, from),
            ).also { apply(it.effects) }
            controller.recordPlaybackEvent(PlaybackEngineEvent.Paused(attemptId, pausedAt))
                .also { apply(it.effects) }
            assertEquals(pausedAt, store.restore(item(rawId)), "the pause saved its position")
        }

        /** The resume-position half of what an owner does with a transition's effects, in order. */
        fun apply(effects: List<PlaybackCoreEffect>) = effects.forEach { effect ->
            when (effect) {
                is PlaybackCoreEffect.PersistResumePosition -> store.save(effect.itemId, effect.position)
                is PlaybackCoreEffect.ClearResumePosition -> store.clear(effect.itemId)
                else -> Unit
            }
        }

        fun close() = driver.close()
    }

    private companion object {
        const val SERVER = "server:fresh-start"
        val WALL_CLOCK = PlaybackWallClockTime(1_788_000_000_000)
    }
}
