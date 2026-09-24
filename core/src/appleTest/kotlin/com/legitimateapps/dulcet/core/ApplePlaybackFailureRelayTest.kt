package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Spec §12.12 classifies a failure from the DomainError alone, but on Apple the error crosses the
 * shell twice: the core names it for Swift, and Swift names it back when the engine reports it.
 * Whose failure it is must survive that round trip, or a server's refusal is skipped past as the
 * track's own -- which is exactly what the collapsed names did before.
 */
class ApplePlaybackFailureRelayTest {
    /**
     * The Swift half of the round trip, `DulcetPlaybackFailure(coreKind:).coreName`. The Swift
     * test `PlaybackFailureRelayTests` asserts the same table from its side, so the two halves
     * are pinned to one statement of it.
     */
    private fun swiftCoreName(kind: String): String = when (kind) {
        "protocol", "security" -> "protocolViolation"
        "tlsUntrusted", "cancelled" -> "transport"
        else -> kind
    }

    @Test
    fun whoseFailureItIsSurvivesTheRoundTripThroughTheShell() {
        val wrong = RELAYED_ERRORS.mapNotNull { error ->
            val kind = error.applePlaybackErrorKind()
            val back = swiftCoreName(kind).toClosedPlaybackDomainError()
            if (playbackFailureOwner(back) == playbackFailureOwner(error)) {
                null
            } else {
                "$error -> $kind -> $back"
            }
        }
        assertEquals(emptyList(), wrong)
    }

    /** Where the class depends on the value -- a Server code, the content seen -- the value survives too. */
    @Test
    fun theValuesTheClassificationReadsComeBackExactly() {
        for (error in listOf(
            DomainError.Server.Known(70),
            DomainError.Server.Known(0),
            DomainError.Server.Known(10),
            DomainError.Server.Unknown(404),
            DomainError.Server.Unknown(70),
            DomainError.Protocol.UnexpectedBinary,
            DomainError.Protocol.UnexpectedContentType(ObservedPlaybackContentType.AudioMpeg, AudioContainer.Flac),
            DomainError.CapabilityUnsupported(CapabilityFeature.entries.last()),
        )) {
            assertEquals(error, swiftCoreName(error.applePlaybackErrorKind()).toClosedPlaybackDomainError())
        }
    }

    /** Before this, every Server code and an unsupported capability read as "no playable source". */
    @Test
    fun aServerRefusalIsNoLongerRelayedAsTheTracksOwnFailure() {
        val refusal = DomainError.Server.Known(10)
        assertEquals(PlaybackFailureOwner.Connection, playbackFailureOwner(refusal))
        assertEquals("serverKnown:10", refusal.applePlaybackErrorKind())
        assertEquals(
            PlaybackFailureOwner.Connection,
            playbackFailureOwner(
                DomainError.CapabilityUnsupported(CapabilityFeature.entries.first())
                    .applePlaybackErrorKind()
                    .toClosedPlaybackDomainError(),
            ),
        )
    }

    /**
     * The engine's names: `undecodable` is AVFoundation failing to decode THIS item, the track's
     * own; `engine` is the engine itself (its audio session would not activate), which is not.
     */
    @Test
    fun theEngineNamesClassifyAsTheSpecSays() {
        assertEquals(DomainError.Playback.NoPlayableSource, "undecodable".toClosedPlaybackDomainError())
        assertEquals(PlaybackFailureOwner.Track, playbackFailureOwner("undecodable".toClosedPlaybackDomainError()))
        assertEquals(PlaybackFailureOwner.Connection, playbackFailureOwner("engine".toClosedPlaybackDomainError()))
        assertEquals(PlaybackFailureOwner.Track, playbackFailureOwner("sourceUnavailable".toClosedPlaybackDomainError()))
        assertEquals(PlaybackFailureOwner.Connection, playbackFailureOwner("transport".toClosedPlaybackDomainError()))
    }

    @Test
    fun aMalformedNameIsRefusedRatherThanGuessed() {
        for (kind in listOf("serverKnown", "serverKnown:x", "serverKnown:1:2", "unexpectedContentType:Other",
            "unexpectedContentType:Nope:Flac", "capabilityUnsupported:Nope", "nonsense")) {
            val refused = runCatching { kind.toClosedPlaybackDomainError() }.isFailure
            assertTrue(refused, "$kind must not become a DomainError")
        }
    }

    /** The facade carries the skip and the Skip predicate to Swift (spec §12.12). */
    @Test
    fun theFacadeReportsTheSkipAndTheSkipPredicate() {
        val driver = createTestDriver()
        val database = DulcetDatabaseStore.open(driver).database
        var identity = 0
        val client = ApplePlaybackQueueClient(
            PlaybackQueueController(
                queues = PersistentQueueStore(database),
                resumePositions = PersistentResumePositionStore(database),
                identities = PlaybackIdentitySource { prefix -> "$prefix:${identity++}" },
            ),
        )
        val started = client.replaceAndStart(
            ApplePlaybackQueueRequestDto(
                items = listOf("a", "b").map { ApplePlaybackQueueItemDto("server", it, 180_000) },
                sourceKind = "album",
                sourceRawId = "album-a",
                sourceDisplayName = "Album A",
                startIndex = 0,
                shuffle = false,
            ),
        )
        val a = assertNotNull(started.startDirective)
        assertTrue(assertNotNull(started.snapshot).canSkipPastCurrent)

        val stopped = client.recordFailedBeforeStart(a.attemptId, "serverKnown:10")
        assertNull(stopped.startDirective, "a server refusal stops")
        assertNull(stopped.skippedAfterFailureQueueEntryId)
        val retried = assertNotNull(client.retryCurrent().startDirective)

        val skipped = client.recordFailedBeforeStart(retried.attemptId, "undecodable")
        val b = assertNotNull(skipped.startDirective, "an undecodable item is skipped past")
        assertEquals("b", b.rawId)
        assertEquals(a.queueEntryId, skipped.skippedAfterFailureQueueEntryId)
        assertFalse(assertNotNull(skipped.snapshot).canSkipPastCurrent, "nothing follows b")
        client.close()
        driver.close()
    }

    private companion object {
        val RELAYED_ERRORS: List<DomainError> = listOf(
            DomainError.Input.InvalidServerUrl(InvalidServerUrlReason.entries.first()),
            DomainError.Transport.Unreachable,
            DomainError.Transport.Timeout,
            // Transport.Cancelled is left out on purpose: the shell never records a withdrawn
            // request as a failure (spec §12.12), so it never makes this trip.
            DomainError.Security.TlsUntrusted(TlsTrustFailure.entries.first()),
            DomainError.Security.LocalExceptionViolated,
            DomainError.Security.RedirectRejected(RedirectRejectionReason.entries.first()),
            DomainError.Protocol.MalformedEnvelope,
            DomainError.Protocol.UnexpectedContentType(ObservedPlaybackContentType.Other, AudioContainer.Mp3),
            DomainError.Protocol.UnexpectedBinary,
            DomainError.Protocol.Incompatible(ProtocolVersionLevel(1, 16), null),
            DomainError.Protocol.NotASubsonicServer,
            DomainError.Server.Busy(5.seconds),
            DomainError.Server.Known(0),
            DomainError.Server.Known(10),
            DomainError.Server.Known(70),
            DomainError.Server.Unknown(0),
            DomainError.Server.Unknown(404),
            DomainError.Auth.InvalidCredentials,
            DomainError.Auth.TokenAuthUnsupported,
            DomainError.Auth.Forbidden,
            DomainError.Auth.UnsupportedAuthenticationChallenge,
            DomainError.Auth.CrossOriginRedirectRejected(RedirectTargetHost("example.test")),
            DomainError.Playback.NoPlayableSource,
            DomainError.CapabilityUnsupported(CapabilityFeature.entries.first()),
        )
    }
}
