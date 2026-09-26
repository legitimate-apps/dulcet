package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Spec §12.12: a failure that belongs to the track moves the queue past it; a failure that
 * belongs to the connection or the account stops and is presented. Driven through the real queue
 * controller over a real store, with the resume-position effects executed as the facades do.
 */
class PlaybackAutoSkipTest {
    // ---- classification --------------------------------------------------------------------

    /**
     * Every DomainError case, with the Server codes that matter. The `when` in the classifier is
     * exhaustive, so a new case fails to compile there; this table is what says what each one IS.
     */
    @Test
    fun everyDomainErrorIsClassifiedAsTheSpecDecides() {
        val expected = listOf(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.entries.first()) to Owner.Connection,
            DomainError.Transport.Unreachable to Owner.Connection,
            DomainError.Transport.Timeout to Owner.Connection,
            DomainError.Transport.Cancelled to Owner.NotAFailure,
            DomainError.Security.TlsUntrusted(TlsTrustFailure.entries.first()) to Owner.Connection,
            DomainError.Security.LocalExceptionViolated to Owner.Connection,
            DomainError.Security.RedirectRejected(RedirectRejectionReason.entries.first()) to Owner.Connection,
            DomainError.Protocol.MalformedEnvelope to Owner.Connection,
            DomainError.Protocol.UnexpectedContentType(
                ObservedPlaybackContentType.Other,
                AudioContainer.Flac,
            ) to Owner.Track,
            DomainError.Protocol.UnexpectedBinary to Owner.Track,
            DomainError.Protocol.Incompatible(ProtocolVersionLevel(1, 16), null) to Owner.Connection,
            DomainError.Protocol.NotASubsonicServer to Owner.Connection,
            DomainError.Server.Busy(null) to Owner.Connection,
            DomainError.Server.Busy(5.seconds) to Owner.Connection,
            DomainError.Server.Known(70) to Owner.Track,
            DomainError.Server.Known(0) to Owner.Track,
            DomainError.Server.Known(10) to Owner.Connection,
            DomainError.Server.Known(20) to Owner.Connection,
            DomainError.Server.Known(30) to Owner.Connection,
            DomainError.Server.Known(60) to Owner.Connection,
            DomainError.Server.Unknown(0) to Owner.Connection,
            DomainError.Server.Unknown(70) to Owner.Connection,
            DomainError.Server.Unknown(404) to Owner.Connection,
            DomainError.Server.Unknown(502) to Owner.Connection,
            DomainError.Auth.InvalidCredentials to Owner.Connection,
            DomainError.Auth.TokenAuthUnsupported to Owner.Connection,
            DomainError.Auth.Forbidden to Owner.Connection,
            DomainError.Auth.UnsupportedAuthenticationChallenge to Owner.Connection,
            DomainError.Auth.CrossOriginRedirectRejected(RedirectTargetHost("example.test")) to Owner.Connection,
            DomainError.Playback.NoPlayableSource to Owner.Track,
            DomainError.CapabilityUnsupported(CapabilityFeature.entries.first()) to Owner.Connection,
        )
        val wrong = expected.filter { (error, owner) -> playbackFailureOwner(error) != owner }
            .map { (error, owner) -> "$error: expected $owner, got ${playbackFailureOwner(error)}" }
        assertEquals(emptyList(), wrong)
        // The positive control for the table itself: all three answers occur in it.
        assertEquals(Owner.entries.toSet(), expected.map { it.second }.toSet())
    }

    // ---- skip or stop ------------------------------------------------------------------------

    @Test
    fun aTrackFailureBeforeStartSkipsToTheNextEntryAsANewSession() = rig(listOf("a", "b", "c")) { rig ->
        val a = rig.start()
        val skip = rig.fail(a, DomainError.Playback.NoPlayableSource)

        val b = assertNotNull(skip.startDirective, "the queue moves past the track's own failure")
        assertEquals("b", b.itemId.rawId)
        assertEquals(a.queueEntryId, skip.skippedAfterFailure, "the transition names what it skipped")
        assertEquals(1, skip.snapshot.currentIndex)
        assertNotEquals(a.queueEntryId, b.queueEntryId)
        assertNotEquals(a.playbackSessionId, b.playbackSessionId, "a next-item advance: new session")
        assertNotEquals(a.attemptId, b.attemptId, "and a new attempt")
        assertEquals(b.playbackSessionId, skip.snapshot.currentSession?.playbackSessionId)
        assertFalse(rig.controller.acceptsCommand(a.playbackSessionId), "the failed session ended")
        assertTrue(b.shouldAutoPlay)
    }

    /** The phase is irrelevant: the same error after partial playback is classified alike. */
    @Test
    fun aTrackFailureAfterPartialPlaybackSkipsTheSameWay() = rig(listOf("a", "b")) { rig ->
        val a = rig.start()
        rig.play(a, from = 0, to = 10)
        val skip = rig.failAfterPartial(a, at = 10, DomainError.Server.Known(70))

        assertEquals("b", skip.startDirective?.itemId?.rawId)
        assertEquals(a.queueEntryId, skip.skippedAfterFailure)
    }

    @Test
    fun everyTrackFailureSkipsAndEveryOtherFailureStops() {
        for (error in TRACK_ERRORS) {
            rig(listOf("a", "b")) { rig ->
                val a = rig.start()
                val transition = rig.fail(a, error)
                assertEquals("b", transition.startDirective?.itemId?.rawId, "$error skips")
                assertEquals(a.queueEntryId, transition.skippedAfterFailure, "$error names the skipped entry")
            }
        }
        for (error in STOPPING_ERRORS) {
            rig(listOf("a", "b")) { rig ->
                val a = rig.start()
                val transition = rig.fail(a, error)
                assertNull(transition.startDirective, "$error stops")
                assertNull(transition.skippedAfterFailure, "$error skips nothing")
                assertEquals(0, transition.snapshot.currentIndex, "$error keeps the failed entry selected")
                assertEquals(PlaybackAttemptPhase.Failed, transition.snapshot.currentSession?.currentAttempt?.phase)
                assertEquals(a.playbackSessionId, transition.snapshot.currentSession?.playbackSessionId)
            }
        }
    }

    // ---- direction ---------------------------------------------------------------------------

    @Test
    fun aSkipAfterPreviousGoesToTheEntryBefore() = rig(listOf("a", "b", "c")) { rig ->
        rig.start(startIndex = 2)
        val b = assertNotNull(rig.apply(rig.controller.previous()).startDirective)
        assertEquals("b", b.itemId.rawId)

        val skip = rig.fail(b, DomainError.Playback.NoPlayableSource)

        assertEquals("a", skip.startDirective?.itemId?.rawId, "Previous keeps travelling backward")
        assertEquals(0, skip.snapshot.currentIndex)
    }

    @Test
    fun aSkipAfterNextATapOrANaturalEndGoesToTheEntryAfter() {
        rig(listOf("a", "b", "c")) { rig ->
            rig.start()
            val b = assertNotNull(rig.apply(rig.controller.next()).startDirective)
            assertEquals("c", rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective?.itemId?.rawId)
        }
        rig(listOf("a", "b", "c", "d")) { rig ->
            val started = rig.start(startIndex = 3)
            val bEntry = rig.controller.snapshot().entries[1].queueEntryId
            // A previous Previous must not leak into a later jump: the jump reaches b going forward.
            val c = assertNotNull(rig.apply(rig.controller.previous()).startDirective)
            assertEquals("c", c.itemId.rawId)
            val b = assertNotNull(rig.apply(rig.controller.jumpTo(bEntry)).startDirective)
            assertNotEquals(started.queueEntryId, b.queueEntryId)
            assertEquals("c", rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective?.itemId?.rawId)
        }
        rig(listOf("a", "b", "c")) { rig ->
            val a = rig.start()
            rig.play(a, from = 0, to = 180)
            val b = assertNotNull(
                rig.apply(rig.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(a.attemptId, DURATION)))
                    .startDirective,
            )
            assertEquals("c", rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective?.itemId?.rawId)
        }
    }

    @Test
    fun withNowhereToGoTheFailureStopsExactlyAsToday() {
        // The last entry without repeat.
        rig(listOf("a", "b")) { rig ->
            rig.start()
            val b = assertNotNull(rig.apply(rig.controller.next()).startDirective)
            val stop = rig.fail(b, DomainError.Playback.NoPlayableSource)
            assertNull(stop.startDirective)
            assertNull(stop.skippedAfterFailure)
            assertEquals(1, stop.snapshot.currentIndex)
            assertEquals(PlaybackAttemptPhase.Failed, stop.snapshot.currentSession?.currentAttempt?.phase)
            assertFalse(stop.snapshot.canSkipPastCurrent)
        }
        // The first entry, reached by Previous.
        rig(listOf("a", "b")) { rig ->
            rig.start(startIndex = 1)
            val a = assertNotNull(rig.apply(rig.controller.previous()).startDirective)
            val stop = rig.fail(a, DomainError.Playback.NoPlayableSource)
            assertNull(stop.startDirective, "Previous has nowhere before the first entry")
            assertEquals(0, stop.snapshot.currentIndex)
        }
        // A one-track queue under repeat-all: the only other entry is itself.
        rig(listOf("a")) { rig ->
            rig.repeat(QueueRepeatMode.All)
            val a = rig.start()
            val stop = rig.fail(a, DomainError.Playback.NoPlayableSource)
            assertNull(stop.startDirective, "a skip never restarts the entry that failed")
            assertFalse(stop.snapshot.canSkipPastCurrent)
        }
    }

    @Test
    fun theSkipFollowsTheRepeatModeExactlyAsNextAndPreviousDo() {
        // Repeat-all wraps forward from the last entry...
        rig(listOf("a", "b", "c")) { rig ->
            rig.repeat(QueueRepeatMode.All)
            rig.start(startIndex = 1)
            val c = assertNotNull(rig.apply(rig.controller.next()).startDirective)
            assertEquals("a", rig.fail(c, DomainError.Playback.NoPlayableSource).startDirective?.itemId?.rawId)
        }
        // ...and backward from the first.
        rig(listOf("a", "b", "c")) { rig ->
            rig.repeat(QueueRepeatMode.All)
            rig.start(startIndex = 1)
            val a = assertNotNull(rig.apply(rig.controller.previous()).startDirective)
            assertEquals("c", rig.fail(a, DomainError.Playback.NoPlayableSource).startDirective?.itemId?.rawId)
        }
        // Repeat-one is not a reason to start the failed track again: Next ignores it, so does this.
        rig(listOf("a", "b")) { rig ->
            rig.repeat(QueueRepeatMode.One)
            val a = rig.start()
            assertEquals("b", rig.fail(a, DomainError.Playback.NoPlayableSource).startDirective?.itemId?.rawId)
        }
    }

    /**
     * Skip's availability and a forward automatic skip agree: wherever the snapshot says Skip can
     * reach another entry, a track failure reached travelling forward skips, and wherever it says
     * not, it stops. (Reached by Previous, the automatic skip asks backward, and they can differ.)
     */
    @Test
    fun skipAvailabilityAndTheForwardSkipAgreeInEveryShape() {
        for (count in 1..3) {
            for (mode in QueueRepeatMode.entries) {
                for (index in 0 until count) {
                    rig(listOf("a", "b", "c").take(count)) { rig ->
                        rig.repeat(mode)
                        val started = rig.start(startIndex = index)
                        val canSkip = rig.controller.snapshot().canSkipPastCurrent
                        val skipped = rig.fail(started, DomainError.Playback.NoPlayableSource).startDirective != null
                        assertEquals(canSkip, skipped, "count=$count mode=$mode index=$index")
                    }
                }
            }
        }
    }

    // ---- guard -------------------------------------------------------------------------------

    @Test
    fun aChainStopsBeforeReachingAnEntryThatAlreadyFailedInIt() = rig(listOf("a", "b", "c")) { rig ->
        rig.repeat(QueueRepeatMode.All)
        val a = rig.start()
        val b = assertNotNull(rig.fail(a, DomainError.Playback.NoPlayableSource).startDirective)
        val c = assertNotNull(rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective)
        assertEquals("c", c.itemId.rawId)

        val stop = rig.fail(c, DomainError.Playback.NoPlayableSource)

        assertNull(stop.startDirective, "the next entry, a, already failed in this chain")
        assertNull(stop.skippedAfterFailure)
        assertEquals(2, stop.snapshot.currentIndex, "the failure presented is the stopping entry's")
        assertEquals(c.playbackSessionId, stop.snapshot.currentSession?.playbackSessionId)
        assertEquals(PlaybackAttemptPhase.Failed, stop.snapshot.currentSession?.currentAttempt?.phase)
        assertTrue(stop.snapshot.canSkipPastCurrent, "Skip still works as today: it is the person's call")
    }

    @Test
    fun aChainStopsAtTheFifthConsecutiveFailedEntry() =
        rig(listOf("a", "b", "c", "d", "e", "f", "g")) { rig ->
            var current = rig.start()
            repeat(4) { skips ->
                val transition = rig.fail(current, DomainError.Playback.NoPlayableSource)
                current = assertNotNull(transition.startDirective, "skip ${skips + 1} of 4")
            }
            assertEquals("e", current.itemId.rawId)

            val stop = rig.fail(current, DomainError.Playback.NoPlayableSource)

            assertNull(stop.startDirective, "five failed entries in a row stop the chain")
            assertEquals(4, stop.snapshot.currentIndex)
            assertEquals(current.playbackSessionId, stop.snapshot.currentSession?.playbackSessionId)
            assertEquals(PlaybackAttemptPhase.Failed, stop.snapshot.currentSession?.currentAttempt?.phase)
        }

    @Test
    fun anyProgressEndsTheChain() = rig(listOf("a", "b", "c", "d", "e", "f", "g", "h")) { rig ->
        var current = rig.start()
        repeat(4) { current = assertNotNull(rig.fail(current, DomainError.Playback.NoPlayableSource).startDirective) }
        assertEquals("e", current.itemId.rawId)
        // e plays for a moment and then fails as its own: the chain began again at e.
        rig.play(current, from = 0, to = 5)
        val skip = rig.failAfterPartial(current, at = 5, DomainError.Protocol.UnexpectedBinary)
        assertEquals("f", skip.startDirective?.itemId?.rawId, "progress ended the earlier chain")
    }

    /**
     * Progress ends the chain, but not the pass (rule 3): an entry that failed and was skipped
     * past since the person last acted is not reached again automatically, however long the
     * entries between played.
     */
    @Test
    fun progressEndsTheChainButNotThePass() = rig(listOf("a", "b")) { rig ->
        rig.repeat(QueueRepeatMode.All)
        val a = rig.start()
        val b = assertNotNull(rig.fail(a, DomainError.Playback.NoPlayableSource).startDirective)
        rig.play(b, from = 0, to = 5)

        val stop = rig.failAfterPartial(b, at = 5, DomainError.Playback.NoPlayableSource)

        assertNull(stop.startDirective, "a failed and was skipped past in this pass")
        assertNull(stop.skippedAfterFailure)
        assertEquals(1, stop.snapshot.currentIndex)
        assertEquals(b.playbackSessionId, stop.snapshot.currentSession?.playbackSessionId)
        assertEquals(PlaybackAttemptPhase.Failed, stop.snapshot.currentSession?.currentAttempt?.phase)
    }

    /**
     * The review's probe: under repeat-all, every entry plays a second and then fails as its own.
     * Progress forgives each failure, so only the pass bounds it -- it stops within one pass,
     * where before it skipped all 50 times it was asked.
     */
    @Test
    fun aQueueWhoseEveryEntryFailsAfterPlayingStopsWithinOnePass() {
        for (rawIds in listOf(listOf("a", "b"), listOf("a", "b", "c", "d", "e"))) {
            rig(rawIds) { rig ->
                rig.repeat(QueueRepeatMode.All)
                var current = rig.start()
                var skips = 0
                var last: PlaybackQueueTransition? = null
                for (iteration in 1..50) {
                    rig.play(current, from = 0, to = 1)
                    val transition = rig.failAfterPartial(current, at = 1, DomainError.Protocol.UnexpectedBinary)
                    last = transition
                    current = transition.startDirective ?: break
                    skips++
                }
                val stop = assertNotNull(last)
                assertEquals(rawIds.size - 1, skips, "one pass over $rawIds, then stop")
                assertNull(stop.startDirective)
                assertNull(stop.skippedAfterFailure)
                assertEquals(rawIds.size - 1, stop.snapshot.currentIndex, "the failure presented is the last entry's")
                assertEquals(PlaybackAttemptPhase.Failed, stop.snapshot.currentSession?.currentAttempt?.phase)
            }
        }
    }

    /**
     * The person's own action begins a new pass: after the stop, Skip reaches a failed entry, and
     * that entry's failure skips past it again instead of stopping on the earlier pass.
     */
    @Test
    fun thePersonsActionBeginsANewPass() = rig(listOf("a", "b", "c")) { rig ->
        rig.repeat(QueueRepeatMode.All)
        var current = rig.start()
        repeat(2) {
            rig.play(current, from = 0, to = 5)
            current = assertNotNull(
                rig.failAfterPartial(current, at = 5, DomainError.Playback.NoPlayableSource).startDirective,
            )
        }
        assertEquals("c", current.itemId.rawId)
        rig.play(current, from = 0, to = 5)
        assertNull(rig.failAfterPartial(current, at = 5, DomainError.Playback.NoPlayableSource).startDirective)

        val a = assertNotNull(rig.apply(rig.controller.next()).startDirective, "Skip is the person's call")
        assertEquals("a", a.itemId.rawId)
        rig.play(a, from = 0, to = 5)
        val skip = rig.failAfterPartial(a, at = 5, DomainError.Playback.NoPlayableSource)

        assertEquals("b", skip.startDirective?.itemId?.rawId, "the person's Skip began a new pass")
    }

    /**
     * A healthy repeat-all queue plays on lap after lap (rule 3). Over a, b, c, d with b and c
     * broken, lap 1 skips both, and a and d play to their end. On every later lap b fails again
     * and the queue moves on to c and then d, exactly as on lap 1: the natural ends in between
     * began a new pass. This is the review's second-lap probe, which stopped at b.
     */
    @Test
    fun aRepeatAllQueueWithTwoBrokenEntriesSkipsThemOnEveryLap() = rig(listOf("a", "b", "c", "d")) { rig ->
        rig.repeat(QueueRepeatMode.All)
        val a = rig.start()
        val b = rig.started(rig.playToTheEnd(a))
        val c = assertNotNull(rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective)
        var current = assertNotNull(rig.fail(c, DomainError.Playback.NoPlayableSource).startDirective)
        assertEquals("d", current.itemId.rawId)

        for (lap in 2..3) {
            val a2 = rig.started(rig.playToTheEnd(current))
            assertEquals("a", a2.itemId.rawId)
            val b2 = rig.started(rig.playToTheEnd(a2))
            assertEquals("b", b2.itemId.rawId)
            val skipB = rig.fail(b2, DomainError.Playback.NoPlayableSource)
            assertEquals("c", skipB.startDirective?.itemId?.rawId, "lap $lap: a played to its end, so b's failure skips")
            assertEquals(b2.queueEntryId, skipB.skippedAfterFailure)
            val skipC = rig.fail(assertNotNull(skipB.startDirective), DomainError.Playback.NoPlayableSource)
            current = assertNotNull(skipC.startDirective, "lap $lap: and c's failure skips too")
            assertEquals("d", current.itemId.rawId)
        }
    }

    /**
     * The same laps with every boundary gapless: the engine hands over to the preloaded entry and
     * reports `AdvancedToPreloaded` alone, which the contract permits without an `EndedNaturally`
     * first. The handover is a natural end, so it begins a new pass too.
     */
    @Test
    fun theSameLapsWithGaplessHandoversSkipTheBrokenEntriesOnEveryLap() = rig(listOf("a", "b", "c", "d")) { rig ->
        rig.repeat(QueueRepeatMode.All)
        val a = rig.start()
        val b = rig.handOverGaplessly(a)
        assertEquals("b", b.itemId.rawId)
        val c = assertNotNull(rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective)
        var current = assertNotNull(rig.fail(c, DomainError.Playback.NoPlayableSource).startDirective)
        assertEquals("d", current.itemId.rawId)

        for (lap in 2..3) {
            val a2 = rig.handOverGaplessly(current)
            assertEquals("a", a2.itemId.rawId)
            val b2 = rig.handOverGaplessly(a2)
            assertEquals("b", b2.itemId.rawId)
            val skipB = rig.fail(b2, DomainError.Playback.NoPlayableSource)
            assertEquals("c", skipB.startDirective?.itemId?.rawId, "lap $lap: a handed over, so b's failure skips")
            val skipC = rig.fail(assertNotNull(skipB.startDirective), DomainError.Playback.NoPlayableSource)
            current = assertNotNull(skipC.startDirective, "lap $lap: and c's failure skips too")
            assertEquals("d", current.itemId.rawId)
        }
    }

    /**
     * Every action that begins a new pass, one row each (rule 3). Each row starts from the same
     * state -- a and b failed and were skipped past, c is current -- performs its action, then lets
     * each entry reached play a moment and fail, until a skip either reaches a or stops. Reaching
     * a is the proof: a was in the pass the action ended. The control row performs nothing and
     * stops, so the tail can tell the two apart.
     */
    @Test
    fun everyActionThatBeginsANewPassLetsASkipReachAnEntryTheOldPassSkipped() {
        val outcomes = resetRows.associate { row ->
            row.name to rigResult(listOf("a", "b", "c", "d")) { rig ->
                rig.repeat(QueueRepeatMode.All)
                val a = rig.start()
                val b = assertNotNull(rig.fail(a, DomainError.Playback.NoPlayableSource).startDirective)
                rig.play(b, from = 0, to = 5)
                val c = assertNotNull(
                    rig.failAfterPartial(b, at = 5, DomainError.Playback.NoPlayableSource).startDirective,
                )
                assertEquals("c", c.itemId.rawId, "${row.name}: the setup")
                rig.failUntilASkipReachesA(row.act(rig, c))
            }
        }
        assertEquals(resetRows.associate { it.name to if (it.resets) REACHED_A else STOPPED }, outcomes)
    }

    private class ResetRow(
        val name: String,
        val resets: Boolean = true,
        /** Performs the action with c current, not yet played, and returns the directive now current. */
        val act: (Rig, PlaybackQueueStartDirective) -> PlaybackQueueStartDirective,
    )

    private val resetRows: List<ResetRow> = listOf(
        ResetRow("control: no action", resets = false) { _, c -> c },
        ResetRow("Play") { rig, c ->
            rig.apply(rig.controller.recordPlayRequested(c.playbackSessionId))
            c
        },
        ResetRow("Skip") { rig, _ -> rig.started(rig.controller.next()) },
        ResetRow("Previous") { rig, _ -> rig.started(rig.controller.previous()) },
        ResetRow("Try Again after the stop") { rig, c ->
            rig.play(c, from = 0, to = 5)
            val d = assertNotNull(rig.failAfterPartial(c, at = 5, DomainError.Playback.NoPlayableSource).startDirective)
            rig.play(d, from = 0, to = 5)
            assertNull(
                rig.failAfterPartial(d, at = 5, DomainError.Playback.NoPlayableSource).startDirective,
                "the pass stops before a",
            )
            rig.retry()
        },
        ResetRow("jump to an entry") { rig, _ -> rig.started(rig.controller.jumpTo(rig.entry("d"))) },
        ResetRow("enqueue, Append") { rig, c ->
            rig.apply(rig.controller.enqueue(insertion("e", QueueInsertionMode.Append)))
            assertEquals(listOf("a", "b", "c", "d", "e"), rig.order())
            c
        },
        ResetRow("enqueue, Play Next") { rig, c ->
            rig.apply(rig.controller.enqueue(insertion("e", QueueInsertionMode.PlayNext)))
            assertEquals(listOf("a", "b", "c", "e", "d"), rig.order())
            c
        },
        ResetRow("move") { rig, c ->
            rig.apply(rig.controller.move(rig.entry("d"), 1))
            assertEquals(listOf("a", "d", "b", "c"), rig.order())
            c
        },
        ResetRow("remove") { rig, c ->
            rig.apply(rig.controller.remove(rig.entry("d")))
            assertEquals(listOf("a", "b", "c"), rig.order())
            c
        },
        ResetRow("clear upcoming") { rig, c ->
            rig.apply(rig.controller.clearUpcoming())
            assertEquals(listOf("a", "b", "c"), rig.order())
            c
        },
        // Whatever order shuffle deals, a is reached before any entry comes round a second time.
        ResetRow("shuffle") { rig, c ->
            rig.apply(rig.controller.setShuffle(true))
            c
        },
        ResetRow("repeat") { rig, c ->
            repeat(3) { rig.apply(rig.controller.cycleRepeatMode()) }
            assertEquals(QueueRepeatMode.All, rig.controller.snapshot().repeatMode)
            c
        },
        ResetRow("natural end") { rig, c -> rig.started(rig.playToTheEnd(c)) },
        // The end is held for the preload, which is then discarded: the discard starts d, and the
        // pass the natural end began must already be in place.
        ResetRow("natural end held for a preload that is discarded") { rig, c ->
            rig.play(c, from = 0, to = 180)
            val preload = assertNotNull(rig.apply(rig.controller.preloadNext(c.playbackSessionId)).preloadDirective)
            val held = rig.apply(rig.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(c.attemptId, DURATION)))
            assertNull(held.startDirective, "the end is held for the preload")
            rig.started(rig.controller.discardPreload(preload.attemptId))
        },
        ResetRow("gapless handover") { rig, c -> rig.handOverGaplessly(c) },
        // An engine teardown ends the session in the same process, and ends neither the queue nor
        // the pass; Play and a catalog restore then find no session.
        ResetRow("Play after an engine teardown") { rig, c ->
            rig.tearDownTheEngine(c)
            rig.started(rig.controller.startCurrent())
        },
        ResetRow("restore after an engine teardown") { rig, c ->
            rig.tearDownTheEngine(c)
            rig.started(rig.controller.restoreCurrentPaused())
        },
        // These two pass with or without their clear: a relaunch builds a new controller, and a new
        // queue's entries have new identities, which no earlier pass can hold.
        ResetRow("restore after a relaunch") { rig, _ ->
            rig.relaunch()
            rig.started(rig.controller.restoreCurrentPaused())
        },
        ResetRow("replace the queue") { rig, _ -> rig.started(rig.controller.replaceAndStart(rig.request(2))) },
    )

    private fun Rig.tearDownTheEngine(directive: PlaybackQueueStartDirective) {
        apply(
            controller.recordPlaybackEvent(
                PlaybackEngineEvent.EngineTornDown(directive.attemptId, PlaybackEngineTeardownReason.SystemReclaimed),
            ),
        )
        val after = controller.snapshot()
        assertNull(after.currentSession, "the teardown ends the session")
        val selected = assertNotNull(after.currentIndex, "and keeps the queue's selection")
        assertEquals(directive.queueEntryId, after.entries[selected].queueEntryId)
    }

    private fun Rig.started(transition: PlaybackQueueTransition): PlaybackQueueStartDirective =
        assertNotNull(apply(transition).startDirective, "the action must start an entry")

    private fun Rig.playToTheEnd(directive: PlaybackQueueStartDirective): PlaybackQueueTransition {
        play(directive, from = 0, to = 180)
        return apply(controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(directive.attemptId, DURATION)))
    }

    /** Plays [directive] to its end and hands over to the preloaded next entry, gaplessly. */
    private fun Rig.handOverGaplessly(directive: PlaybackQueueStartDirective): PlaybackQueueStartDirective {
        play(directive, from = 0, to = 180)
        val next = assertNotNull(
            apply(controller.preloadNext(directive.playbackSessionId)).preloadDirective,
            "the next entry preloads",
        )
        val advanced = apply(
            controller.recordPlaybackEvent(PlaybackEngineEvent.AdvancedToPreloaded(directive.attemptId, next.attemptId)),
        )
        assertEquals(next.playbackSessionId, advanced.snapshot.currentSession?.playbackSessionId)
        return next
    }

    private fun Rig.failUntilASkipReachesA(from: PlaybackQueueStartDirective): String {
        var current = from
        repeat(8) {
            play(current, from = 0, to = 5)
            val next = failAfterPartial(current, at = 5, DomainError.Playback.NoPlayableSource).startDirective
                ?: return STOPPED
            if (next.itemId.rawId == "a") return REACHED_A
            current = next
        }
        return "never settled"
    }

    private fun Rig.entry(rawId: String): QueueEntryId =
        controller.snapshot().entries.single { it.itemId.rawId == rawId }.queueEntryId

    private fun Rig.order(): List<String> = controller.snapshot().entries.map { it.itemId.rawId }

    private fun insertion(rawId: String, mode: QueueInsertionMode) = PlaybackQueueInsertion(
        items = listOf(PlaybackQueueItem(ProviderItemId(SERVER.value, rawId), DURATION)),
        sourceContext = QueueSourceContext(QueueSourceKind.Album, ProviderItemId(SERVER.value, "album"), "Album"),
        mode = mode,
    )

    // ---- identity ----------------------------------------------------------------------------

    @Test
    fun aSkippedEntryIsNeverScrobbled() {
        rig(listOf("a", "b")) { rig ->
            val a = rig.start()
            val skip = rig.fail(a, DomainError.Playback.NoPlayableSource)
            assertEquals(emptyList(), skip.recordedEventsFor("a"), "the skip records nothing for a")
            assertEquals(0, rig.submittedPlaysOf("a"))
        }
        rig(listOf("a", "b")) { rig ->
            val a = rig.start()
            // A minute of a three-minute track: under the threshold, so no play is owed.
            rig.play(a, from = 0, to = 60)
            val skip = rig.failAfterPartial(a, at = 60, DomainError.Server.Known(70))
            assertNotNull(skip.startDirective)
            assertEquals(emptyList(), skip.recordedEventsFor("a").filterIsInstance<RecordedPlaybackEvent.SubmittedPlay>())
            assertEquals(0, rig.submittedPlaysOf("a"))
            // The instrument's positive control: b, heard in full, is counted.
            val b = assertNotNull(skip.startDirective)
            rig.play(b, from = 0, to = 180)
            rig.apply(rig.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(b.attemptId, DURATION)))
            assertEquals(1, rig.submittedPlaysOf("b"))
        }
    }

    /**
     * A listen that crossed the threshold before its own failure was heard, and §15.2 evaluates it
     * at `FailedAfterPartial` whether or not the queue then moves on. The skip adds nothing to that
     * evaluation: one play, as with Try Again or a stop -- never a second one for the skip, and
     * never none because the queue moved.
     */
    @Test
    fun aSkipAddsNothingToWhatTheFailedListenAlreadyEarned() =
        rig(listOf("a", "b")) { rig ->
            val a = rig.start()
            rig.play(a, from = 0, to = 150)
            val skip = rig.failAfterPartial(a, at = 150, DomainError.Server.Known(70))
            assertNotNull(skip.startDirective, "the track's own failure moves the queue on")
            assertEquals(1, rig.submittedPlaysOf("a"))
            val b = assertNotNull(skip.startDirective)
            rig.fail(b, DomainError.Transport.Unreachable)
            assertEquals(1, rig.submittedPlaysOf("a"), "nothing later adds to a")
        }

    /** Try Again at the stopping entry keeps its session and its accumulator, as before. */
    @Test
    fun tryAgainAfterTheGuardStopsKeepsTheSessionAndCountsOneListen() =
        rig(listOf("a", "b", "c")) { rig ->
            rig.repeat(QueueRepeatMode.All)
            var current = rig.start()
            repeat(2) { current = assertNotNull(rig.fail(current, DomainError.Playback.NoPlayableSource).startDirective) }
            val stop = rig.fail(current, DomainError.Playback.NoPlayableSource)
            assertNull(stop.startDirective)

            val retried = rig.retry()

            assertEquals(current.queueEntryId, retried.queueEntryId)
            assertEquals(current.playbackSessionId, retried.playbackSessionId, "Try Again keeps the session")
            assertNotEquals(current.attemptId, retried.attemptId)
            rig.play(retried, from = 0, to = 180)
            rig.apply(
                rig.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(retried.attemptId, DURATION)),
            )
            assertEquals(1, rig.submittedPlaysOf("c"))
        }

    /** A retried attempt that fails as the track's own is still in the chain: nothing is re-counted. */
    @Test
    fun tryAgainThatFailsAgainAtTheGuardStaysStopped() =
        rig(listOf("a", "b", "c", "d", "e", "f")) { rig ->
            var current = rig.start()
            repeat(4) { current = assertNotNull(rig.fail(current, DomainError.Playback.NoPlayableSource).startDirective) }
            assertNull(rig.fail(current, DomainError.Playback.NoPlayableSource).startDirective)
            val retried = rig.retry()
            assertEquals(current.playbackSessionId, retried.playbackSessionId)
            assertNull(rig.fail(retried, DomainError.Playback.NoPlayableSource).startDirective)
        }

    // ---- preload -----------------------------------------------------------------------------

    /**
     * A preload failing never moves the queue: its entry has not been reached (§12.8). When the
     * queue reaches it with a fresh start, the same rule applies to that start's failure.
     */
    @Test
    fun aPreloadFailureSkipsNothingUntilTheQueueReachesThatEntry() = rig(listOf("a", "b", "c")) { rig ->
        val a = rig.start()
        rig.play(a, from = 0, to = 100)
        val preload = assertNotNull(rig.controller.preloadNext(a.playbackSessionId).preloadDirective)
        assertEquals("b", preload.itemId.rawId)

        val preloadFailed = rig.apply(
            rig.controller.recordPlaybackEvent(
                PlaybackEngineEvent.FailedBeforeStart(preload.attemptId, DomainError.Playback.NoPlayableSource),
            ),
        )

        assertNull(preloadFailed.startDirective, "the person has not reached b")
        assertNull(preloadFailed.skippedAfterFailure)
        assertEquals(0, preloadFailed.snapshot.currentIndex)
        assertEquals(a.playbackSessionId, preloadFailed.snapshot.currentSession?.playbackSessionId)
        assertTrue(rig.controller.acceptsCommand(a.playbackSessionId), "a keeps playing")

        // The owner drops the failed preload; the natural end then starts b afresh.
        rig.apply(rig.controller.discardPreload(preload.attemptId))
        rig.play(a, from = 100, to = 180, continuing = true)
        val b = assertNotNull(
            rig.apply(rig.controller.recordPlaybackEvent(PlaybackEngineEvent.EndedNaturally(a.attemptId, DURATION)))
                .startDirective,
        )
        assertEquals("b", b.itemId.rawId)
        assertNotEquals(preload.attemptId, b.attemptId)
        val skip = rig.fail(b, DomainError.Playback.NoPlayableSource)
        assertEquals("c", skip.startDirective?.itemId?.rawId, "reached, b's own failure skips it")
    }

    // ---- restoration -------------------------------------------------------------------------

    @Test
    fun aQueueRestoredPausedMovesPastATrackFailureWithoutStartingSound() = rig(listOf("a", "b", "c")) { rig ->
        rig.start()
        rig.relaunch()
        val restored = assertNotNull(rig.apply(rig.controller.restoreCurrentPaused()).startDirective)
        assertFalse(restored.shouldAutoPlay)

        val skip = rig.fail(restored, DomainError.Playback.NoPlayableSource)
        val b = assertNotNull(skip.startDirective)
        assertEquals("b", b.itemId.rawId)
        assertFalse(b.shouldAutoPlay, "a restored queue never starts sound on its own")
        val c = assertNotNull(rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective)
        assertFalse(c.shouldAutoPlay, "nor does the chain it starts")
    }

    /**
     * A restore starts no sound; once the person plays, the queue plays on past a failed entry
     * (rule 4). Play goes straight to the engine, so the core learns of it from the engine -- here
     * from progress, the review's sequence: a restored track played for a minute, then failing as
     * its own, must not leave the next one silent.
     */
    @Test
    fun aRestoredQueueThePersonPlaysKeepsPlayingPastATrackFailure() = rig(listOf("a", "b", "c")) { rig ->
        rig.start()
        rig.relaunch()
        val restored = assertNotNull(rig.apply(rig.controller.restoreCurrentPaused()).startDirective)
        assertFalse(restored.shouldAutoPlay, "the restore itself starts no sound")
        rig.play(restored, from = 0, to = 60)

        val skip = rig.failAfterPartial(restored, at = 60, DomainError.Playback.NoPlayableSource)

        val b = assertNotNull(skip.startDirective)
        assertEquals("b", b.itemId.rawId)
        assertTrue(b.shouldAutoPlay, "the person was listening, so the next entry plays")
        val c = assertNotNull(rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective)
        assertTrue(c.shouldAutoPlay, "and so does the rest of the chain")
    }

    /**
     * The same, learned from the engine's `Resumed`: the person pressed Play on the restored entry
     * and it failed before it progressed. The Apple engine reports that play as `Resumed`.
     */
    @Test
    fun aRestoredEntryThePersonStartedPlaysOnEvenIfItNeverProgressed() = rig(listOf("a", "b")) { rig ->
        rig.start()
        rig.relaunch()
        val restored = assertNotNull(rig.apply(rig.controller.restoreCurrentPaused()).startDirective)
        rig.apply(
            rig.controller.recordPlaybackEvent(
                PlaybackEngineEvent.Ready(restored.attemptId, DURATION, PlaybackSeekability.Seekable),
            ),
        )
        rig.apply(rig.controller.recordPlaybackEvent(PlaybackEngineEvent.Resumed(restored.attemptId, 0.seconds)))

        val skip = rig.failAfterPartial(restored, at = 0, DomainError.Playback.NoPlayableSource)

        val b = assertNotNull(skip.startDirective, "the track's own failure still moves the queue")
        assertTrue(b.shouldAutoPlay, "the person asked for sound")
    }

    /**
     * The person's Play reaches the core as they press it, whatever the engine's readiness (rule
     * 4). Pressed while the restored entry is still preparing, it reaches the engine only, which
     * reports no `Resumed` before Ready -- and a decode failure can come before that. The review's
     * sequence: restored, preparing, Play, Ready, failure. The skipped-to entry must play.
     */
    @Test
    fun aPlayPressedBeforeTheRestoredEntryIsReadyPlaysOnPastItsFailure() = rig(listOf("a", "b", "c")) { rig ->
        rig.start()
        rig.relaunch()
        val restored = assertNotNull(rig.apply(rig.controller.restoreCurrentPaused()).startDirective)
        rig.apply(rig.controller.recordPlaybackEvent(PlaybackEngineEvent.Preparing(restored.attemptId)))
        rig.apply(rig.controller.recordPlayRequested(restored.playbackSessionId))
        rig.apply(
            rig.controller.recordPlaybackEvent(
                PlaybackEngineEvent.Ready(restored.attemptId, DURATION, PlaybackSeekability.Seekable),
            ),
        )

        val b = assertNotNull(rig.fail(restored, DomainError.Playback.NoPlayableSource).startDirective)

        assertEquals("b", b.itemId.rawId)
        assertTrue(b.shouldAutoPlay, "the person pressed Play, so the next entry starts playing")
        val c = assertNotNull(rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective)
        assertTrue(c.shouldAutoPlay, "and so does the rest of the chain")
    }

    /** The control for the test above: the same sequence with no Play pressed stays paused. */
    @Test
    fun theSameRestoredSequenceWithNoPlayPressedMovesOnStillPaused() = rig(listOf("a", "b")) { rig ->
        rig.start()
        rig.relaunch()
        val restored = assertNotNull(rig.apply(rig.controller.restoreCurrentPaused()).startDirective)
        rig.apply(rig.controller.recordPlaybackEvent(PlaybackEngineEvent.Preparing(restored.attemptId)))
        rig.apply(
            rig.controller.recordPlaybackEvent(
                PlaybackEngineEvent.Ready(restored.attemptId, DURATION, PlaybackSeekability.Seekable),
            ),
        )

        val b = assertNotNull(rig.fail(restored, DomainError.Playback.NoPlayableSource).startDirective)

        assertEquals("b", b.itemId.rawId)
        assertFalse(b.shouldAutoPlay, "nobody pressed Play")
    }

    /** A Play for a session that is no longer current says nothing about the one that is. */
    @Test
    fun aPlayForAStaleSessionLeavesTheRestoredEntryPaused() = rig(listOf("a", "b")) { rig ->
        val earlier = rig.start()
        rig.relaunch()
        val restored = assertNotNull(rig.apply(rig.controller.restoreCurrentPaused()).startDirective)
        assertNotEquals(earlier.playbackSessionId, restored.playbackSessionId)
        rig.apply(rig.controller.recordPlayRequested(earlier.playbackSessionId))

        val b = assertNotNull(rig.fail(restored, DomainError.Playback.NoPlayableSource).startDirective)

        assertFalse(b.shouldAutoPlay, "that Play was for a session the relaunch ended")
    }

    /** Prepared is not played: `Ready` alone is not the person asking for sound. */
    @Test
    fun aRestoredEntryThatWasOnlyPreparedMovesOnStillPaused() = rig(listOf("a", "b")) { rig ->
        rig.start()
        rig.relaunch()
        val restored = assertNotNull(rig.apply(rig.controller.restoreCurrentPaused()).startDirective)
        rig.apply(
            rig.controller.recordPlaybackEvent(
                PlaybackEngineEvent.Ready(restored.attemptId, DURATION, PlaybackSeekability.Seekable),
            ),
        )

        val b = assertNotNull(rig.fail(restored, DomainError.Playback.NoPlayableSource).startDirective)

        assertFalse(b.shouldAutoPlay, "nobody pressed Play")
    }

    // ---- what the chain counts ---------------------------------------------------------------

    /**
     * Rule 3 counts every entry that failed, whoever the failure belonged to: four entries stopped
     * on a connection failure and stepped past by hand, then a fifth entry's own failure is
     * presented rather than skipped.
     */
    @Test
    fun connectionFailuresCountTowardTheGuard() = rig(listOf("a", "b", "c", "d", "e", "f", "g")) { rig ->
        var current = rig.start()
        repeat(4) {
            assertNull(rig.fail(current, DomainError.Transport.Timeout).startDirective, "a connection failure stops")
            current = assertNotNull(rig.apply(rig.controller.next()).startDirective)
        }
        assertEquals("e", current.itemId.rawId)

        val fifth = rig.fail(current, DomainError.Playback.NoPlayableSource)

        assertNull(fifth.startDirective, "the fifth consecutive failed entry is presented")
        assertNull(fifth.skippedAfterFailure)
        assertEquals(PlaybackAttemptPhase.Failed, fifth.snapshot.currentSession?.currentAttempt?.phase)
    }

    /** A withdrawn request is not a failure (rule 1), so it is not in the chain a cycle stops on. */
    @Test
    fun aWithdrawnRequestIsNotPartOfTheChain() = rig(listOf("a", "b", "c")) { rig ->
        rig.repeat(QueueRepeatMode.All)
        val a = rig.start()
        val withdrawn = rig.fail(a, DomainError.Transport.Cancelled)
        assertNull(withdrawn.startDirective)
        assertNull(withdrawn.skippedAfterFailure)
        val b = assertNotNull(rig.apply(rig.controller.next()).startDirective)
        val c = assertNotNull(rig.fail(b, DomainError.Playback.NoPlayableSource).startDirective)

        val wrapped = rig.fail(c, DomainError.Playback.NoPlayableSource)

        assertEquals("a", wrapped.startDirective?.itemId?.rawId, "a was withdrawn, never failed")
    }

    // ---- direction, kept and reset -----------------------------------------------------------

    /** Try Again keeps the direction the entry was reached in (rule 2). */
    @Test
    fun tryAgainKeepsTheDirectionThatReachedTheEntry() = rig(listOf("a", "b", "c")) { rig ->
        rig.start(startIndex = 2)
        val b = assertNotNull(rig.apply(rig.controller.previous()).startDirective)
        assertNull(rig.fail(b, DomainError.Transport.Timeout).startDirective)
        val retried = rig.retry()

        val skip = rig.fail(retried, DomainError.Playback.NoPlayableSource)

        assertEquals("a", skip.startDirective?.itemId?.rawId, "Previous reached b; Try Again does not change that")
    }

    /** A transport restart of the current entry counts as reaching it forward (rule 2). */
    @Test
    fun aTransportRestartCountsAsReachingTheEntryForward() = rig(listOf("a", "b", "c")) { rig ->
        rig.start(startIndex = 2)
        val b = assertNotNull(rig.apply(rig.controller.previous()).startDirective)
        assertEquals("b", b.itemId.rawId)
        val restarted = assertNotNull(rig.apply(rig.controller.restartCurrent(SERVER)).startDirective)
        assertEquals("b", restarted.itemId.rawId)

        val skip = rig.fail(restarted, DomainError.Playback.NoPlayableSource)

        assertEquals("c", skip.startDirective?.itemId?.rawId)
    }

    // ---- rig ---------------------------------------------------------------------------------

    private class Rig(rawIds: List<String>) {
        val driver = createTestDriver()
        private val database = DulcetDatabaseStore.open(driver).database
        private val resumePositions = PersistentResumePositionStore(database)
        private val queues = PersistentQueueStore(database)
        private var identity = 0
        private var monotonicSeconds = 0L
        var controller = PlaybackQueueController(
            queues = queues,
            resumePositions = resumePositions,
            identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
        )
        private val effects = mutableListOf<PlaybackCoreEffect>()
        private val rawIds = rawIds

        fun request(startIndex: Int) = PlaybackQueueRequest(
            items = rawIds.map { PlaybackQueueItem(ProviderItemId(SERVER.value, it), DURATION) },
            sourceContext = QueueSourceContext(
                kind = QueueSourceKind.Album,
                sourceId = ProviderItemId(SERVER.value, "album"),
                displayName = "Album",
            ),
            startIndex = startIndex,
            shuffle = false,
        )

        fun start(startIndex: Int = 0): PlaybackQueueStartDirective {
            val started = assertNotNull(apply(controller.replaceAndStart(request(startIndex))).startDirective)
            while (controller.snapshot().repeatMode != repeatMode) apply(controller.cycleRepeatMode())
            return started
        }

        private var repeatMode = QueueRepeatMode.Off

        /** The repeat mode [start] leaves the queue in, set through the real control. */
        fun repeat(mode: QueueRepeatMode) {
            repeatMode = mode
        }

        /** A relaunch: a new controller over the same database, with no session. */
        fun relaunch() {
            controller = PlaybackQueueController(
                queues = queues,
                resumePositions = resumePositions,
                identities = PlaybackIdentitySource { prefix -> "$prefix:relaunch:${identity++}" },
            )
        }

        fun apply(transition: PlaybackQueueTransition): PlaybackQueueTransition {
            effects += transition.effects
            transition.effects.forEach { effect ->
                when (effect) {
                    is PlaybackCoreEffect.PersistResumePosition ->
                        resumePositions.save(effect.itemId, effect.position)
                    is PlaybackCoreEffect.ClearResumePosition -> resumePositions.clear(effect.itemId)
                    else -> Unit
                }
            }
            return transition
        }

        fun fail(directive: PlaybackQueueStartDirective, error: DomainError) = apply(
            controller.recordPlaybackEvent(PlaybackEngineEvent.FailedBeforeStart(directive.attemptId, error)),
        )

        fun failAfterPartial(directive: PlaybackQueueStartDirective, at: Int, error: DomainError) = apply(
            controller.recordPlaybackEvent(
                PlaybackEngineEvent.FailedAfterPartial(directive.attemptId, at.seconds, error),
            ),
        )

        fun retry(): PlaybackQueueStartDirective =
            assertNotNull(apply(controller.retryCurrent()).startDirective, "Try Again must start something")

        /**
         * Plays [directive] from [from] to [to] seconds, one position sample per second. The
         * first call for an attempt makes it ready and begins progress; [continuing] does not.
         */
        fun play(directive: PlaybackQueueStartDirective, from: Int, to: Int, continuing: Boolean = false) {
            val attemptId = directive.attemptId
            if (!continuing) {
                apply(controller.recordPlaybackEvent(PlaybackEngineEvent.Ready(attemptId, DURATION, PlaybackSeekability.Seekable)))
                apply(
                    controller.recordPlaybackEvent(
                        PlaybackEngineEvent.PlaybackProgressBegan(
                            attemptId,
                            PlaybackWallClockTime(1_788_000_000_000 + monotonicSeconds * 1_000L),
                            from.seconds,
                        ),
                    ),
                )
            }
            for (second in (from + 1)..to) {
                apply(
                    controller.recordPlaybackEvent(
                        PlaybackEngineEvent.PositionChanged(
                            attemptId,
                            second.seconds,
                            PlaybackMonotonicTime((monotonicSeconds++).seconds),
                        ),
                    ),
                )
            }
        }

        fun submittedPlaysOf(rawId: String): Int = effects.count { effect ->
            val event = (effect as? PlaybackCoreEffect.RecordPlaybackEvent)?.event
            event is RecordedPlaybackEvent.SubmittedPlay && event.itemId.rawId == rawId
        }
    }

    private inline fun rig(rawIds: List<String>, body: (Rig) -> Unit) {
        val rig = Rig(rawIds)
        try {
            body(rig)
        } finally {
            rig.driver.close()
        }
    }

    /** [rig], for a body that returns a value. Kept apart so that no `@Test` returns one. */
    private inline fun <T> rigResult(rawIds: List<String>, body: (Rig) -> T): T {
        val rig = Rig(rawIds)
        try {
            return body(rig)
        } finally {
            rig.driver.close()
        }
    }

    private fun PlaybackQueueTransition.recordedEventsFor(rawId: String): List<RecordedPlaybackEvent> =
        effects.mapNotNull { (it as? PlaybackCoreEffect.RecordPlaybackEvent)?.event }
            .filter { it.itemId.rawId == rawId }

    private companion object {
        val SERVER = ServerId("server:auto-skip")
        const val REACHED_A = "reached a"
        const val STOPPED = "stopped"
        val DURATION: Duration = 180.seconds

        val TRACK_ERRORS: List<DomainError> = listOf(
            DomainError.Playback.NoPlayableSource,
            DomainError.Protocol.UnexpectedContentType(ObservedPlaybackContentType.Other, AudioContainer.Mp3),
            DomainError.Protocol.UnexpectedBinary,
            DomainError.Server.Known(70),
            DomainError.Server.Known(0),
        )

        val STOPPING_ERRORS: List<DomainError> = listOf(
            DomainError.Transport.Unreachable,
            DomainError.Transport.Timeout,
            DomainError.Transport.Cancelled,
            DomainError.Security.TlsUntrusted(TlsTrustFailure.entries.first()),
            DomainError.Security.LocalExceptionViolated,
            DomainError.Auth.InvalidCredentials,
            DomainError.Auth.Forbidden,
            DomainError.Server.Busy(null),
            DomainError.Server.Known(10),
            DomainError.Server.Unknown(404),
            DomainError.Protocol.MalformedEnvelope,
            DomainError.Protocol.NotASubsonicServer,
            DomainError.CapabilityUnsupported(CapabilityFeature.entries.first()),
        )
    }
}

private typealias Owner = PlaybackFailureOwner
