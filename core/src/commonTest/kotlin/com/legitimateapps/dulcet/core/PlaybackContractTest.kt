package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PlaybackContractTest {
    @Test
    fun requiredCommandVocabularyCarriesCorrelationIdAndHasOneSealedOutcome() {
        val id = PlaybackCommandId("command:opaque")
        val attempt = AttemptId("attempt:opaque")
        val plan = object : PlaybackPlan {}
        val commands = listOf(
            PlaybackCommand.Prepare(id, attempt, plan),
            PlaybackCommand.Play(id),
            PlaybackCommand.Pause(id),
            PlaybackCommand.Stop(id),
            PlaybackCommand.Seek(id, 1.seconds),
            PlaybackCommand.SetVolume(id, 0.5),
            PlaybackCommand.SetRate(id, 1.25),
            PlaybackCommand.ReplaceCurrent(id, attempt, plan),
            PlaybackCommand.PreloadNext(id, attempt, plan),
            PlaybackCommand.Release(id),
        )
        assertEquals(10, commands.size)
        assertTrue(commands.all { it.commandId == id })

        val outcomes: List<PlaybackCommandOutcome> = listOf(
            PlaybackCommandOutcome.CommandAccepted(id),
            PlaybackCommandOutcome.CommandRejected(
                id,
                PlaybackCommandRejectionReason.InvalidState,
            ),
            PlaybackCommandOutcome.CommandCompleted(id, PlaybackCommandCompletedWithoutData),
        )
        assertEquals(3, outcomes.size)
        assertTrue(outcomes.all { it.commandId == id })
    }

    @Test
    fun fullRequiredMinimumEventVocabularyCarriesAnAttemptIdentity() {
        val attempt = AttemptId("attempt:all-events")
        val replacement = AttemptId("attempt:replacement")
        val events = listOf<PlaybackEngineEvent>(
            PlaybackEngineEvent.Preparing(attempt),
            PlaybackEngineEvent.Ready(attempt, null, PlaybackSeekability.Unknown),
            PlaybackEngineEvent.PlaybackProgressBegan(attempt, PlaybackWallClockTime(1), 0.seconds),
            PlaybackEngineEvent.Buffering(attempt, 0.seconds),
            PlaybackEngineEvent.BufferingEnded(attempt, 0.seconds),
            PlaybackEngineEvent.Paused(attempt, 0.seconds),
            PlaybackEngineEvent.Resumed(attempt, 0.seconds),
            PlaybackEngineEvent.PositionChanged(attempt, 0.seconds, PlaybackMonotonicTime(0.seconds)),
            PlaybackEngineEvent.DurationChanged(attempt, 1.seconds),
            PlaybackEngineEvent.SeekCompleted(attempt, 0.seconds, 1.seconds),
            PlaybackEngineEvent.SeekFailed(attempt, 0.seconds, 1.seconds),
            PlaybackEngineEvent.EndedNaturally(attempt, 1.seconds),
            PlaybackEngineEvent.Skipped(attempt, 0.seconds, PlaybackSkipReason.User),
            PlaybackEngineEvent.FailedBeforeStart(attempt, DomainError.Transport.Unreachable),
            PlaybackEngineEvent.FailedAfterPartial(attempt, 1.seconds, DomainError.Transport.Unreachable),
            PlaybackEngineEvent.RouteChanged(
                attempt,
                PlaybackRouteKind.BuiltIn,
                PlaybackRouteKind.Bluetooth,
                didPause = true,
            ),
            PlaybackEngineEvent.InterruptionBegan(attempt, shouldResume = true),
            PlaybackEngineEvent.InterruptionEnded(attempt, shouldResume = true),
            PlaybackEngineEvent.AttemptReplaced(attempt, replacement),
            PlaybackEngineEvent.AdvancedToPreloaded(attempt, replacement),
            PlaybackEngineEvent.RateChanged(attempt, 2.0),
            PlaybackEngineEvent.EngineTornDown(attempt, PlaybackEngineTeardownReason.SystemReclaimed),
            PlaybackEngineEvent.SourceRefreshRequired(attempt, PlaybackSourceRefreshReason.Expired),
            PlaybackEngineEvent.ObservationResynced(
                attempt,
                PlaybackObservationSnapshot(
                    PlaybackObservationStatus.Ready,
                    0.seconds,
                    1.seconds,
                    PlaybackSeekability.Seekable,
                    1.0,
                ),
            ),
        )
        assertEquals(24, events.size)
        assertTrue(events.all { it.attemptId == attempt })
    }

    @Test
    fun serverBusyRetryAfterIsAFloorWithJitterOnTopAndSixtySecondTotalCap() {
        val ordinary = PlaybackRetryPolicy.decide(
            error = DomainError.Server.Busy(5.seconds),
            scheduledBackoff = 1.seconds,
            jitter = 2.seconds,
            totalWaited = 10.seconds,
        )
        assertEquals(7.seconds, assertIs<PlaybackRetryDecision.RetryAfter>(ordinary).delay)

        val capped = PlaybackRetryPolicy.decide(
            error = DomainError.Server.Busy(45.seconds),
            scheduledBackoff = 1.seconds,
            jitter = 10.seconds,
            totalWaited = 10.seconds,
        )
        assertEquals(50.seconds, assertIs<PlaybackRetryDecision.RetryAfter>(capped).delay)

        assertIs<PlaybackRetryDecision.SurfaceFailure>(
            PlaybackRetryPolicy.decide(
                error = DomainError.Server.Busy(51.seconds),
                scheduledBackoff = 1.seconds,
                jitter = 0.seconds,
                totalWaited = 10.seconds,
            ),
        )
    }

    @Test
    fun retryPolicyUsesCoreBackoffWhenBusyHasNoRetryAfter() {
        val decision = PlaybackRetryPolicy.decide(
            error = DomainError.Server.Busy(null),
            scheduledBackoff = 3.seconds,
            jitter = 1.seconds,
            totalWaited = 0.seconds,
        )
        assertEquals(4.seconds, assertIs<PlaybackRetryDecision.RetryAfter>(decision).delay)
        assertIs<PlaybackRetryDecision.SurfaceFailure>(
            PlaybackRetryPolicy.decide(
                error = DomainError.Auth.InvalidCredentials,
                scheduledBackoff = 3.seconds,
                jitter = 1.seconds,
                totalWaited = 0.seconds,
            ),
        )
    }
}
