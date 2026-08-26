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
            when {
                response.statusCode == 404 -> ArtworkFetchResult.Unavailable
                response.statusCode !in 200..299 -> ArtworkFetchResult.Failed(
                    response.closedErrorOrNull()
                        ?: DomainError.Server.Unknown(response.statusCode),
                )
                response.body.isEmpty() -> ArtworkFetchResult.Failed(
                    DomainError.Protocol.MalformedEnvelope,
                )
                else -> ArtworkFetchResult.Loaded(response.body)
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

private val ARTWORK_ERROR_JSON = Json { ignoreUnknownKeys = true }

private fun ArtworkEndpointResponse.closedErrorOrNull(): DomainError? = try {
    val root = ARTWORK_ERROR_JSON.parseToJsonElement(body.decodeToString()) as? JsonObject
        ?: return null
    val payload = root["subsonic-response"] as? JsonObject ?: return null
    val error = payload["error"] as? JsonObject ?: return null
    val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: return null
    val message = (error["message"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        .orEmpty()
    AccountConnectionContract.mapSubsonicError(code, message, redactedUrl)
} catch (_: IllegalArgumentException) {
    null
}
