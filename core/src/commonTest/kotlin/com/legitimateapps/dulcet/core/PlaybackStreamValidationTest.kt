package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class PlaybackStreamValidationTest {
    @Test
    fun everyNormativeAudioSignatureHasAPositiveFixture() {
        val fixtures = listOf(
            AudioFixture(AudioContainer.Mp3, "audio/mpeg", ascii("ID3\u0004")),
            AudioFixture(
                AudioContainer.Mp3,
                "audio/mpeg",
                bytes(0xFF, 0xFB, 0x90, 0x64),
            ),
            AudioFixture(
                AudioContainer.Mp4,
                "audio/mp4; codecs=mp4a.40.2",
                bytes(0, 0, 0, 24) + ascii("ftyp"),
            ),
            AudioFixture(
                AudioContainer.Wav,
                "audio/wav",
                ascii("RIFF") + bytes(4, 0, 0, 0) + ascii("WAVE"),
            ),
            AudioFixture(AudioContainer.Flac, "audio/flac", ascii("fLaC")),
            AudioFixture(
                AudioContainer.Flac,
                "audio/flac",
                ascii("ID3") + bytes(4, 0, 0, 0, 0, 0, 0) + ascii("fLaC"),
            ),
            AudioFixture(AudioContainer.Ogg, "audio/ogg", ascii("OggS")),
            AudioFixture(AudioContainer.AdtsAac, "audio/aac", bytes(0xFF, 0xF1)),
        )

        fixtures.forEach { fixture ->
            val result = PlaybackStreamValidator.validate(
                response(body = fixture.body, contentType = fixture.contentType),
                fixture.container,
            )
            assertEquals(
                fixture.container,
                assertIs<PlaybackStreamValidationResult.Audio>(result).container,
                fixture.container.name,
            )
        }
    }

    @Test
    fun offsetsMasksAndCompoundSignaturesRejectFalsePositives() {
        val falsePositives = listOf(
            AudioFixture(AudioContainer.Mp4, "audio/mp4", ascii("ftyp") + bytes(0, 0, 0, 0)),
            AudioFixture(
                AudioContainer.Wav,
                "audio/wav",
                ascii("RIFF") + bytes(4, 0, 0, 0) + ascii("NOPE"),
            ),
            AudioFixture(AudioContainer.Mp3, "audio/mpeg", ascii("FFE0")),
            AudioFixture(AudioContainer.Mp3, "audio/mpeg", bytes(0xFF, 0xF1, 0x00, 0x00)),
            AudioFixture(
                AudioContainer.Flac,
                "audio/flac",
                ascii("ID3") + bytes(4, 0, 0, 0, 0, 0, 4) + ascii("fLaC"),
            ),
            AudioFixture(AudioContainer.Ogg, "audio/ogg", ascii("Ogg")),
            AudioFixture(AudioContainer.AdtsAac, "audio/aac", bytes(0xFF)),
        )

        falsePositives.forEach { fixture ->
            val failure = assertIs<PlaybackStreamValidationResult.Failure>(
                PlaybackStreamValidator.validate(
                    response(body = fixture.body, contentType = fixture.contentType),
                    fixture.container,
                ),
                fixture.container.name,
            )
            assertEquals(DomainError.Protocol.UnexpectedBinary, failure.error)
            assertEquals(
                PlaybackErrorResponseShape.UnexpectedSuccessfulPayload,
                failure.shape,
            )
        }
    }

    @Test
    fun envelopeAtHttp200PrecedesAnAudioContentTypeAndSignatureExpectation() {
        val json = """
            {"subsonic-response":{"status":"failed","error":{"code":70,"message":"missing"}}}
        """.trimIndent()
        val body = bytes(0x20, 0xEF, 0xBB, 0xBF, 0x0A) + json.encodeToByteArray()

        val failure = assertIs<PlaybackStreamValidationResult.Failure>(
            PlaybackStreamValidator.validate(
                response(body = body, contentType = "audio/mpeg"),
                AudioContainer.Mp3,
            ),
        )

        assertEquals(PlaybackErrorResponseShape.EnvelopeAtSuccess, failure.shape)
        assertEquals(70, failure.envelopeCode)
        assertEquals(DomainError.Server.Known(70), failure.error)
    }

    @Test
    fun statusDrivesBusyWhileEnvelopeDrivesPresentationAtNonSuccess() {
        val xml = """
            <?xml version="1.0"?>
            <subsonic-response status="failed"><error code="0" message="generic"/></subsonic-response>
        """.trimIndent().encodeToByteArray()

        val failure = assertIs<PlaybackStreamValidationResult.Failure>(
            PlaybackStreamValidator.validate(
                response(
                    statusCode = 429,
                    body = bytes(0xEF, 0xBB, 0xBF, 0x20) + xml,
                    contentType = "application/xml",
                    retryAfter = "5",
                ),
                AudioContainer.Mp3,
            ),
        )

        assertEquals(PlaybackErrorResponseShape.EnvelopeAtHttpError, failure.shape)
        assertEquals(DomainError.Server.Busy(5.seconds), failure.error)
        assertEquals(DomainError.Server.Known(0), failure.presentationError)
        assertEquals(0, failure.envelopeCode)
    }

    @Test
    fun bareHttpErrorIsNotMistakenForAudio() {
        val failure = assertIs<PlaybackStreamValidationResult.Failure>(
            PlaybackStreamValidator.validate(
                response(
                    statusCode = 400,
                    body = "Bad Request\n".encodeToByteArray(),
                    contentType = "text/plain",
                ),
                AudioContainer.Mp3,
            ),
        )

        assertEquals(PlaybackErrorResponseShape.BareHttpError, failure.shape)
        assertEquals(DomainError.Server.Unknown(400), failure.error)
        assertEquals(null, failure.envelopeCode)
    }

    @Test
    fun plausibleContentTypeIsRequiredInAdditionToTheSignature() {
        val failure = assertIs<PlaybackStreamValidationResult.Failure>(
            PlaybackStreamValidator.validate(
                response(body = ascii("ID3\u0004"), contentType = "application/json"),
                AudioContainer.Mp3,
            ),
        )

        assertEquals(
            DomainError.Protocol.UnexpectedContentType(
                ObservedPlaybackContentType.Other,
                AudioContainer.Mp3,
            ),
            failure.error,
        )
    }

    @Test
    fun continuationRangesSkipOnlyTheLeadingSignatureCheck() {
        val continuation = assertIs<PlaybackStreamValidationResult.Audio>(
            PlaybackStreamValidator.validate(
                response(body = ascii("continuation bytes"), contentType = "audio/mpeg"),
                AudioContainer.Mp3,
                requiresAudioSignature = false,
            ),
        )
        assertEquals(AudioContainer.Mp3, continuation.container)

        val delimiterAlignedAudio = assertIs<PlaybackStreamValidationResult.Audio>(
            PlaybackStreamValidator.validate(
                response(
                    body = bytes(0x3C, 0xD8, 0xDD, 0x6D),
                    contentType = "audio/mpeg",
                ),
                AudioContainer.Mp3,
                requiresAudioSignature = false,
            ),
        )
        assertEquals(AudioContainer.Mp3, delimiterAlignedAudio.container)

        val envelope = assertIs<PlaybackStreamValidationResult.Failure>(
            PlaybackStreamValidator.validate(
                response(
                    body = ascii(
                        """{"subsonic-response":{"status":"failed","error":{"code":40}}}""",
                    ),
                    contentType = "audio/mpeg",
                ),
                AudioContainer.Mp3,
                requiresAudioSignature = false,
            ),
        )
        assertEquals(PlaybackErrorResponseShape.EnvelopeAtSuccess, envelope.shape)
        assertEquals(DomainError.Auth.InvalidCredentials, envelope.error)

        val malformedEnvelope = assertIs<PlaybackStreamValidationResult.Failure>(
            PlaybackStreamValidator.validate(
                response(
                    body = ascii("""<subsonic-response status="failed"/>"""),
                    contentType = "audio/mpeg",
                ),
                AudioContainer.Mp3,
                requiresAudioSignature = false,
            ),
        )
        assertEquals(PlaybackErrorResponseShape.MalformedEnvelope, malformedEnvelope.shape)
    }

    private fun response(
        statusCode: Int = 200,
        body: ByteArray,
        contentType: String?,
        retryAfter: String? = null,
    ): AuthenticatedEndpointResponse = AuthenticatedEndpointResponse(
        statusCode = statusCode,
        body = body,
        redactedUrl = "https://music.invalid:443/rest/stream.view?<redacted>",
        headers = AuthenticatedEndpointResponseHeaders(
            contentType = contentType,
            contentLength = body.size.toLong(),
            retryAfter = retryAfter,
            acceptRanges = null,
            contentRange = null,
        ),
        requestTrace = RequestTrace.observed(
            endpoint = "stream",
            method = "GET",
            redactedUrl = "https://music.invalid:443/rest/stream.view?<redacted>",
            authenticationLocation = AuthenticationLocation.Query,
            queryAuthenticationParameters = setOf(
                AuthenticationParameter.Username,
                AuthenticationParameter.SaltedToken,
                AuthenticationParameter.Salt,
            ),
            formAuthenticationParameters = emptySet(),
            channels = emptySet(),
            requestedProtocolVersion = "1.16.1",
            saltFingerprint = "fixture",
        ),
    )

    private data class AudioFixture(
        val container: AudioContainer,
        val contentType: String,
        val body: ByteArray,
    )

    private fun ascii(value: String): ByteArray = value.encodeToByteArray()
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
