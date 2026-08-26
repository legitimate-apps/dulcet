package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal enum class ArtworkSizeBucket(val pixels: Int) {
    Px96(96),
    Px256(256),
    Px512(512),
    Px1024(1024),
}

internal data class ArtworkFetchRequest(
    val providerInstanceId: String,
    val artworkKey: String,
    val sizeBucket: ArtworkSizeBucket,
    val normalizedBaseUrl: String,
    val username: String,
    val password: String,
    val allowLocalHttp: Boolean,
) {
    init {
        require(providerInstanceId.isNotBlank())
        require(artworkKey.isNotBlank())
    }

    override fun toString(): String = "ArtworkFetchRequest(<redacted>)"
}

internal sealed interface ArtworkFetchResult {
    data class Loaded(val bytes: ByteArray) : ArtworkFetchResult {
        override fun equals(other: Any?): Boolean =
            other is Loaded && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object Unavailable : ArtworkFetchResult
    data class Failed(val error: DomainError) : ArtworkFetchResult
}

internal data class ArtworkEndpointResponse(
    val statusCode: Int,
    val body: ByteArray,
    val redactedUrl: String,
)

internal fun interface ArtworkEndpointTransport {
    suspend fun request(parameters: Map<String, String>): ArtworkEndpointResponse
}

internal class ArtworkFetcher private constructor(
    private val transportFactory: (ArtworkFetchRequest) -> ArtworkEndpointTransport,
) {
    constructor(
        saltSource: SaltSource? = null,
        logSink: LogSink? = null,
        hostResolver: HostResolver = systemHostResolver(),
    ) : this(
        transportFactory = { request ->
            KtorArtworkEndpointTransport(request, saltSource, logSink, hostResolver)
        },
    )

    internal constructor(transport: ArtworkEndpointTransport) : this({ transport })

    suspend fun fetch(request: ArtworkFetchRequest): ArtworkFetchResult {
        val transport = transportFactory(request)
        return try {
            val response = transport.request(
                mapOf(
                    "id" to request.artworkKey,
                    "size" to request.sizeBucket.pixels.toString(),
                ),
            )
            when (val envelope = response.body.inspectArtworkEnvelope()) {
                is ArtworkEnvelopeInspection.Error -> {
                    if (envelope.code == ARTWORK_NOT_FOUND_ERROR_CODE) {
                        ArtworkFetchResult.Unavailable
                    } else {
                        ArtworkFetchResult.Failed(
                            AccountConnectionContract.mapSubsonicError(
                                envelope.code,
                                message = "",
                                requestUrl = response.redactedUrl,
                            ),
                        )
                    }
                }
                ArtworkEnvelopeInspection.Malformed -> ArtworkFetchResult.Failed(
                    DomainError.Protocol.MalformedEnvelope,
                )
                ArtworkEnvelopeInspection.NotEnvelope -> when {
                    response.statusCode == 404 -> ArtworkFetchResult.Unavailable
                    response.statusCode !in 200..299 -> ArtworkFetchResult.Failed(
                        DomainError.Server.Unknown(response.statusCode),
                    )
                    response.body.hasRecognizedArtworkSignature() -> {
                        ArtworkFetchResult.Loaded(response.body)
                    }
                    else -> ArtworkFetchResult.Failed(DomainError.Protocol.MalformedEnvelope)
                }
            }
        } catch (_: CancellationException) {
            ArtworkFetchResult.Failed(DomainError.Transport.Cancelled)
        } catch (failure: AuthenticatedEndpointFailure) {
            ArtworkFetchResult.Failed(failure.error)
        } catch (failure: Throwable) {
            ArtworkFetchResult.Failed(mapAccountConnectionFailure(failure))
        } finally {
            (transport as? AutoCloseableArtworkTransport)?.close()
        }
    }
}

private interface AutoCloseableArtworkTransport {
    fun close()
}

private class KtorArtworkEndpointTransport(
    request: ArtworkFetchRequest,
    saltSource: SaltSource?,
    logSink: LogSink?,
    hostResolver: HostResolver,
) : ArtworkEndpointTransport, AutoCloseableArtworkTransport {
    private val client = AuthenticatedEndpointClient(
        credentials = AuthenticatedEndpointCredentials(
            normalizedBaseUrl = request.normalizedBaseUrl,
            username = request.username,
            password = request.password,
            allowLocalHttp = request.allowLocalHttp,
        ),
        operationName = "artwork.fetch",
        saltSource = saltSource,
        logSink = logSink,
        hostResolver = hostResolver,
    )

    override suspend fun request(parameters: Map<String, String>): ArtworkEndpointResponse {
        val response = client.request("getCoverArt", parameters)
        return ArtworkEndpointResponse(
            statusCode = response.statusCode,
            body = response.body,
            redactedUrl = response.redactedUrl,
        )
    }

    override fun close() {
        client.close()
    }
}

private const val ARTWORK_NOT_FOUND_ERROR_CODE = 70
private val ARTWORK_ERROR_JSON = Json { ignoreUnknownKeys = true }
private val XML_SUBSONIC_ROOT = Regex(
    """<(?:(?:[A-Za-z_][A-Za-z0-9_.-]*):)?subsonic-response\b""",
)
private val XML_ERROR_CODE = Regex(
    """<(?:(?:[A-Za-z_][A-Za-z0-9_.-]*):)?error\b[^>]*\bcode\s*=\s*["'](-?\d+)["']""",
)
private val HEIF_ARTWORK_BRANDS = setOf(
    "avif",
    "avis",
    "heic",
    "heix",
    "hevc",
    "hevx",
    "mif1",
    "msf1",
)

private sealed interface ArtworkEnvelopeInspection {
    data object NotEnvelope : ArtworkEnvelopeInspection
    data object Malformed : ArtworkEnvelopeInspection
    data class Error(val code: Int) : ArtworkEnvelopeInspection
}

private fun ByteArray.inspectArtworkEnvelope(): ArtworkEnvelopeInspection {
    val start = contentStartIndex()
    if (start >= size) return ArtworkEnvelopeInspection.NotEnvelope
    return when (this[start].toInt().toChar()) {
        '{' -> inspectJsonArtworkEnvelope(start)
        '<' -> inspectXmlArtworkEnvelope(start)
        else -> ArtworkEnvelopeInspection.NotEnvelope
    }
}

private fun ByteArray.inspectJsonArtworkEnvelope(start: Int): ArtworkEnvelopeInspection = try {
    val root = ARTWORK_ERROR_JSON.parseToJsonElement(
        copyOfRange(start, size).decodeToString(),
    ) as? JsonObject ?: return ArtworkEnvelopeInspection.Malformed
    val payload = root["subsonic-response"] as? JsonObject
        ?: return ArtworkEnvelopeInspection.Malformed
    val error = payload["error"] as? JsonObject
        ?: return ArtworkEnvelopeInspection.Malformed
    val code = (error["code"] as? JsonPrimitive)?.intOrNull
        ?: return ArtworkEnvelopeInspection.Malformed
    ArtworkEnvelopeInspection.Error(code)
} catch (_: IllegalArgumentException) {
    ArtworkEnvelopeInspection.Malformed
}

private fun ByteArray.inspectXmlArtworkEnvelope(start: Int): ArtworkEnvelopeInspection {
    val xml = copyOfRange(start, size).decodeToString()
    if (!XML_SUBSONIC_ROOT.containsMatchIn(xml)) return ArtworkEnvelopeInspection.Malformed
    val code = XML_ERROR_CODE.find(xml)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: return ArtworkEnvelopeInspection.Malformed
    return ArtworkEnvelopeInspection.Error(code)
}

private fun ByteArray.contentStartIndex(): Int {
    var index = 0
    while (index < size && this[index].isEnvelopeWhitespace()) index += 1
    if (matchesBytes(index, 0xEF, 0xBB, 0xBF)) index += 3
    while (index < size && this[index].isEnvelopeWhitespace()) index += 1
    return index
}

private fun Byte.isEnvelopeWhitespace(): Boolean = when (toInt() and 0xFF) {
    0x09, 0x0A, 0x0D, 0x20 -> true
    else -> false
}

private fun ByteArray.hasRecognizedArtworkSignature(): Boolean =
    size >= 3 && matchesBytes(0, 0xFF, 0xD8, 0xFF) ||
        matchesBytes(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
        matchesAscii(0, "GIF87a") ||
        matchesAscii(0, "GIF89a") ||
        (matchesAscii(0, "RIFF") && matchesAscii(8, "WEBP")) ||
        (size >= 8 && matchesBytes(0, 0x49, 0x49, 0x2A, 0x00)) ||
        (size >= 8 && matchesBytes(0, 0x4D, 0x4D, 0x00, 0x2A)) ||
        (size >= 14 && matchesAscii(0, "BM")) ||
        (size >= 6 && matchesBytes(0, 0x00, 0x00, 0x01, 0x00)) ||
        hasRecognizedHeifBrand()

private fun ByteArray.hasRecognizedHeifBrand(): Boolean {
    if (!matchesAscii(4, "ftyp") || size < 12) return false
    val brand = buildString(4) {
        for (index in 8 until 12) append(this@hasRecognizedHeifBrand[index].toInt().toChar())
    }
    return brand in HEIF_ARTWORK_BRANDS
}

private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
    value.indices.all { index ->
        offset + index < size && this[offset + index].toInt() and 0xFF == value[index].code
    }

private fun ByteArray.matchesBytes(offset: Int, vararg expected: Int): Boolean =
    expected.indices.all { index ->
        offset + index < size && this[offset + index].toInt() and 0xFF == expected[index]
    }
