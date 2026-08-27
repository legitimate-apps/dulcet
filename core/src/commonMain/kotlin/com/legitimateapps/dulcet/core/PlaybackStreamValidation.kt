package com.legitimateapps.dulcet.core

import kotlin.time.Duration.Companion.seconds

public enum class AudioContainer {
    Mp3,
    Mp4,
    Wav,
    Flac,
    Ogg,
    AdtsAac,
}

public enum class ObservedPlaybackContentType {
    Missing,
    AudioMpeg,
    AudioMp4,
    AudioM4a,
    AudioWav,
    AudioFlac,
    AudioOgg,
    ApplicationOgg,
    AudioAac,
    ApplicationOctetStream,
    Other,
}

internal enum class PlaybackErrorResponseShape {
    EnvelopeAtSuccess,
    BareHttpError,
    EnvelopeAtHttpError,
    MalformedEnvelope,
    UnexpectedSuccessfulPayload,
}

internal sealed interface PlaybackStreamValidationResult {
    data class Audio(
        val container: AudioContainer,
        val contentLength: Long?,
        val supportsByteRanges: Boolean,
    ) : PlaybackStreamValidationResult

    data class Failure(
        /** Status-derived when status carries retry semantics; otherwise the presentation error. */
        val error: DomainError,
        /** Envelope-derived when available, so status and envelope are never collapsed. */
        val presentationError: DomainError,
        val shape: PlaybackErrorResponseShape,
        val statusCode: Int,
        val envelopeCode: Int?,
    ) : PlaybackStreamValidationResult
}

internal object PlaybackStreamValidator {
    fun validate(
        response: AuthenticatedEndpointResponse,
        expectedContainer: AudioContainer,
    ): PlaybackStreamValidationResult {
        val envelope = response.body.inspectSubsonicBinaryEnvelope()
        val statusIsSuccess = response.statusCode in 200..299
        if (envelope is SubsonicBinaryEnvelopeInspection.Error) {
            val presentationError = AccountConnectionContract.mapSubsonicError(
                code = envelope.code,
                message = "",
                requestUrl = response.redactedUrl,
            )
            val decisionError = if (response.statusCode == TOO_MANY_REQUESTS) {
                DomainError.Server.Busy(parseRetryAfterSeconds(response.headers.retryAfter))
            } else {
                presentationError
            }
            return PlaybackStreamValidationResult.Failure(
                error = decisionError,
                presentationError = presentationError,
                shape = if (statusIsSuccess) {
                    PlaybackErrorResponseShape.EnvelopeAtSuccess
                } else {
                    PlaybackErrorResponseShape.EnvelopeAtHttpError
                },
                statusCode = response.statusCode,
                envelopeCode = envelope.code,
            )
        }
        if (envelope is SubsonicBinaryEnvelopeInspection.Malformed) {
            val error = statusError(response) ?: DomainError.Protocol.MalformedEnvelope
            return PlaybackStreamValidationResult.Failure(
                error = error,
                presentationError = DomainError.Protocol.MalformedEnvelope,
                shape = PlaybackErrorResponseShape.MalformedEnvelope,
                statusCode = response.statusCode,
                envelopeCode = null,
            )
        }
        if (!statusIsSuccess) {
            val error = statusError(response) ?: DomainError.Server.Unknown(response.statusCode)
            return PlaybackStreamValidationResult.Failure(
                error = error,
                presentationError = error,
                shape = PlaybackErrorResponseShape.BareHttpError,
                statusCode = response.statusCode,
                envelopeCode = null,
            )
        }

        val rule = AUDIO_SIGNATURE_RULES.getValue(expectedContainer)
        val observedContentType = response.headers.contentType.toObservedPlaybackContentType()
        if (observedContentType !in rule.acceptedContentTypes) {
            val error = DomainError.Protocol.UnexpectedContentType(
                actual = observedContentType,
                expected = expectedContainer,
            )
            return unexpectedSuccessfulPayload(response, error)
        }
        if (response.body.size < rule.minimumBytes || !rule.signatureMatches(response.body)) {
            return unexpectedSuccessfulPayload(response, DomainError.Protocol.UnexpectedBinary)
        }
        return PlaybackStreamValidationResult.Audio(
            container = expectedContainer,
            contentLength = response.headers.contentLength,
            supportsByteRanges = response.statusCode == PARTIAL_CONTENT ||
                response.headers.acceptRanges.equals("bytes", ignoreCase = true) ||
                response.headers.contentRange?.startsWith("bytes ", ignoreCase = true) == true,
        )
    }

    private fun unexpectedSuccessfulPayload(
        response: AuthenticatedEndpointResponse,
        error: DomainError,
    ): PlaybackStreamValidationResult.Failure = PlaybackStreamValidationResult.Failure(
        error = error,
        presentationError = error,
        shape = PlaybackErrorResponseShape.UnexpectedSuccessfulPayload,
        statusCode = response.statusCode,
        envelopeCode = null,
    )

    private fun statusError(response: AuthenticatedEndpointResponse): DomainError? = when (
        response.statusCode
    ) {
        TOO_MANY_REQUESTS -> DomainError.Server.Busy(parseRetryAfterSeconds(response.headers.retryAfter))
        401 -> DomainError.Auth.InvalidCredentials
        403 -> DomainError.Auth.Forbidden
        else -> null
    }

    private const val PARTIAL_CONTENT = 206
    private const val TOO_MANY_REQUESTS = 429
}

internal fun parseRetryAfterSeconds(value: String?) = value
    ?.trim()
    ?.toLongOrNull()
    ?.takeIf { it >= 0 }
    ?.seconds

private data class AudioSignatureRule(
    val acceptedContentTypes: Set<ObservedPlaybackContentType>,
    val minimumBytes: Int,
    val signatureMatches: (ByteArray) -> Boolean,
)

private val BINARY_COMPATIBLE = setOf(ObservedPlaybackContentType.ApplicationOctetStream)
private val AUDIO_SIGNATURE_RULES = mapOf(
    AudioContainer.Mp3 to AudioSignatureRule(
        acceptedContentTypes = BINARY_COMPATIBLE + ObservedPlaybackContentType.AudioMpeg,
        minimumBytes = 3,
        signatureMatches = ByteArray::hasMp3Signature,
    ),
    AudioContainer.Mp4 to AudioSignatureRule(
        acceptedContentTypes = BINARY_COMPATIBLE + setOf(
            ObservedPlaybackContentType.AudioMp4,
            ObservedPlaybackContentType.AudioM4a,
        ),
        minimumBytes = 8,
        signatureMatches = { it.matchesAscii(4, "ftyp") },
    ),
    AudioContainer.Wav to AudioSignatureRule(
        acceptedContentTypes = BINARY_COMPATIBLE + ObservedPlaybackContentType.AudioWav,
        minimumBytes = 12,
        signatureMatches = { it.matchesAscii(0, "RIFF") && it.matchesAscii(8, "WAVE") },
    ),
    AudioContainer.Flac to AudioSignatureRule(
        acceptedContentTypes = BINARY_COMPATIBLE + ObservedPlaybackContentType.AudioFlac,
        minimumBytes = 4,
        signatureMatches = ByteArray::hasFlacSignature,
    ),
    AudioContainer.Ogg to AudioSignatureRule(
        acceptedContentTypes = BINARY_COMPATIBLE + setOf(
            ObservedPlaybackContentType.AudioOgg,
            ObservedPlaybackContentType.ApplicationOgg,
        ),
        minimumBytes = 4,
        signatureMatches = { it.matchesAscii(0, "OggS") },
    ),
    AudioContainer.AdtsAac to AudioSignatureRule(
        acceptedContentTypes = BINARY_COMPATIBLE + ObservedPlaybackContentType.AudioAac,
        minimumBytes = 2,
        signatureMatches = ByteArray::hasAdtsSignature,
    ),
)

private fun ByteArray.hasMp3Signature(): Boolean {
    if (matchesAscii(0, "ID3")) return true
    if (size < 4) return false
    val header = ((this[0].toInt() and 0xFF) shl 24) or
        ((this[1].toInt() and 0xFF) shl 16) or
        ((this[2].toInt() and 0xFF) shl 8) or
        (this[3].toInt() and 0xFF)
    val syncMatches = header and 0xFFE00000.toInt() == 0xFFE00000.toInt()
    val versionIsValid = header and 0x00180000 != 0x00080000
    val layerIsValid = header and 0x00060000 != 0
    val bitrateIndex = header ushr 12 and 0xF
    val sampleRateIndex = header ushr 10 and 0x3
    return syncMatches && versionIsValid && layerIsValid &&
        bitrateIndex !in setOf(0, 15) && sampleRateIndex != 3
}

private fun ByteArray.hasFlacSignature(): Boolean {
    if (matchesAscii(0, "fLaC")) return true
    val flacOffset = id3v2BlockLengthOrNull() ?: return false
    return matchesAscii(flacOffset, "fLaC")
}

private fun ByteArray.id3v2BlockLengthOrNull(): Int? {
    if (!matchesAscii(0, "ID3") || size < ID3_HEADER_LENGTH) return null
    val sizeBytes = (6..9).map { this[it].toInt() and 0xFF }
    if (sizeBytes.any { it and 0x80 != 0 }) return null
    val payloadLength = sizeBytes.fold(0) { total, byte -> (total shl 7) or byte }
    val footerLength = if (this[5].toInt() and ID3_FOOTER_FLAG != 0) ID3_FOOTER_LENGTH else 0
    val blockLength = ID3_HEADER_LENGTH + payloadLength + footerLength
    return blockLength.takeIf { it <= size }
}

private fun ByteArray.hasAdtsSignature(): Boolean {
    if (size < 2) return false
    val word = ((this[0].toInt() and 0xFF) shl 8) or (this[1].toInt() and 0xFF)
    return word and 0xFFF6 == 0xFFF0
}

private fun String?.toObservedPlaybackContentType(): ObservedPlaybackContentType {
    val mediaType = this?.substringBefore(';')?.trim()?.lowercase()
    return when (mediaType) {
        null, "" -> ObservedPlaybackContentType.Missing
        "audio/mpeg", "audio/mp3", "audio/x-mp3" -> ObservedPlaybackContentType.AudioMpeg
        "audio/mp4", "application/mp4" -> ObservedPlaybackContentType.AudioMp4
        "audio/m4a", "audio/x-m4a" -> ObservedPlaybackContentType.AudioM4a
        "audio/wav", "audio/wave", "audio/x-wav" -> ObservedPlaybackContentType.AudioWav
        "audio/flac", "audio/x-flac" -> ObservedPlaybackContentType.AudioFlac
        "audio/ogg" -> ObservedPlaybackContentType.AudioOgg
        "application/ogg" -> ObservedPlaybackContentType.ApplicationOgg
        "audio/aac", "audio/aacp", "audio/x-aac" -> ObservedPlaybackContentType.AudioAac
        "application/octet-stream" -> ObservedPlaybackContentType.ApplicationOctetStream
        else -> ObservedPlaybackContentType.Other
    }
}

private const val ID3_HEADER_LENGTH = 10
private const val ID3_FOOTER_LENGTH = 10
private const val ID3_FOOTER_FLAG = 0x10
