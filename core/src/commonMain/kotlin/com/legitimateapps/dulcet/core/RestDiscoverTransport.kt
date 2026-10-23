package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

/** REST adapters share reader admission, issue order, credentials and endpoint health. */
internal class RestDiscoverTransport(
    private val reader: LibraryReader,
    private val wall: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : DiscoverTransport {
    private val wire = DiscoverRestWire(reader, wall)
    override suspend fun search(kind: DiscoverKind, query: String, cursor: String?, limit: Int) = wire.result {
        page(wire.read("searchDulcetDiscover", pageParameters(cursor, limit) + mapOf("kind" to kind.wire, "query" to query)).discoverObj("dulcetDiscover"), "item")
    }
    override suspend fun artist(mbid: String) = wire.result {
        val body = wire.read("getDulcetDiscoverArtist", mapOf("mbid" to mbid)).discoverObj("dulcetDiscoverArtist")
        DiscoverArtist(item(body.discoverObj("item")), page(body.discoverObj("albums"), "album"))
    }
    override suspend fun artistAlbums(mbid: String, cursor: String?, limit: Int) = wire.result {
        page(wire.read("getDulcetDiscoverArtistAlbums", pageParameters(cursor, limit) + ("mbid" to mbid)).discoverObj("dulcetDiscover"), "album")
    }
    override suspend fun album(mbid: String, releaseId: String?) = wire.result {
        val body = wire.read("getDulcetDiscoverAlbum", mapOf("mbid" to mbid) + discoverOptional("releaseId", releaseId)).discoverObj("dulcetDiscoverAlbum")
        DiscoverAlbum(item(body.discoverObj("item")), body.discoverObj("editions").discoverRows("edition").map {
            DiscoverEdition(it.discoverRequired("releaseId"), it.discoverText("releaseDate"), it.discoverInteger("trackCount"), discoverLinks(it))
        }, body.discoverText("selectedReleaseId"), page(body.discoverObj("tracks"), "song"))
    }
    override suspend fun albumTracks(mbid: String, releaseId: String, cursor: String?, limit: Int) = wire.result {
        page(wire.read("getDulcetDiscoverAlbumTracks", pageParameters(cursor, limit) + mapOf("mbid" to mbid, "releaseId" to releaseId)).discoverObj("dulcetDiscover"), "song")
    }
    override suspend fun artwork(artworkId: String): DiscoverResult<ByteArray> = wire.result {
        val response = reader.sendBinary("getDulcetDiscoverCoverArt", mapOf("id" to artworkId), DISCOVER_ART_LIMIT)
        val bytes = response.body
        if (response.statusCode == 429) wire.checkStatus(response.statusCode, response.headers.retryAfter)
        when (val envelope = bytes.inspectSubsonicBinaryEnvelope()) {
            is SubsonicBinaryEnvelopeInspection.Error -> throw DiscoverWireFailure(discoverCode(envelope.code))
            SubsonicBinaryEnvelopeInspection.Malformed -> invalidDiscover()
            else -> Unit
        }
        wire.checkStatus(response.statusCode, response.headers.retryAfter)
        if (bytes.size > DISCOVER_ART_LIMIT) invalidDiscover()
        val image = bytes.matchesBytes(0, 0xff, 0xd8, 0xff) || bytes.matchesBytes(0, 137, 80, 78, 71, 13, 10, 26, 10) ||
            bytes.matchesAscii(0, "GIF87a") || bytes.matchesAscii(0, "GIF89a") ||
            (bytes.matchesAscii(0, "RIFF") && bytes.matchesAscii(8, "WEBP"))
        if (!image) {
            if (bytes.decodeToString().trimStart('\uFEFF', ' ', '\n', '\r', '\t').let { it.startsWith("<svg") || (it.startsWith("<?xml") && "<svg" in it) })
                throw DiscoverWireFailure(DiscoverFailure("missing-art", null, false))
            invalidDiscover()
        }
        bytes
    }
    private fun item(body: JsonObject) = parseDiscoverItem(body, reader.cache.serverId)
    private fun page(body: JsonObject, row: String) = DiscoverPage(body.discoverRows(row).map(::item), body.discoverText("nextCursor"),
        DiscoverAge(body.discoverTime("fetchedAt"), body.discoverLong("ageSeconds"), wall(), body.discoverFlag("stale") ?: false, false))
}

internal const val DISCOVER_ART_LIMIT = 5 * 1024 * 1024
internal val DiscoverKind.wire: String get() = name.lowercase()
internal fun discoverOptional(key: String, value: String?) = if (value == null) emptyMap() else mapOf(key to value)
private fun pageParameters(cursor: String?, limit: Int): Map<String, String> {
    if (limit !in 1..100) invalidDiscover()
    return mapOf("size" to limit.toString()) + discoverOptional("cursor", cursor)
}
internal class DiscoverWireFailure(val failure: DiscoverFailure) : Exception(failure.code)
internal fun invalidDiscover(): Nothing = throw DiscoverWireFailure(DiscoverFailure("invalid-response", null, false))
internal fun discoverCode(code: Int): DiscoverFailure = when (code) {
    40 -> DiscoverFailure("unauthorized", null, false)
    50 -> DiscoverFailure("forbidden", null, false)
    70 -> DiscoverFailure("unsupported", null, false)
    else -> DiscoverFailure("invalid-response", null, false)
}
internal fun discoverFailure(error: DomainError): DiscoverFailure = when (error) {
    DomainError.Transport.Cancelled -> DiscoverFailure("cancelled", null, false)
    DomainError.Transport.Unreachable, DomainError.Transport.Timeout -> DiscoverFailure("offline", null, true)
    DomainError.Protocol.ServerIdentityChanged -> DiscoverFailure("identity-changed", null, false)
    is DomainError.Auth -> DiscoverFailure(if (error == DomainError.Auth.Forbidden) "forbidden" else "unauthorized", null, false)
    is DomainError.Server.Known -> discoverCode(error.code)
    is DomainError.Server.Busy -> DiscoverFailure("rate-limited", null, true)
    is DomainError.Server.HttpStatus -> when (error.status) {
        401 -> DiscoverFailure("unauthorized", null, false)
        403 -> DiscoverFailure("forbidden", null, false)
        in 500..599 -> DiscoverFailure("upstream-unavailable", null, true)
        else -> DiscoverFailure("invalid-response", null, false)
    }
    else -> DiscoverFailure("invalid-response", null, false)
}
internal class DiscoverRestWire(private val reader: LibraryReader, private val wall: () -> Long) {
    suspend fun <T> result(block: suspend () -> T): DiscoverResult<T> = try { DiscoverResult.Value(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DiscoverWireFailure) { DiscoverResult.Failed(failure.failure) }
        catch (failure: LibraryRequestFailure) { DiscoverResult.Failed(if (failure is ReaderSendRefused) DiscoverFailure("offline",null,true) else discoverFailure(failure.error)) }
        catch (_: Exception) { DiscoverResult.Failed(DiscoverFailure("invalid-response", null, false)) }

    suspend fun read(endpoint: String, parameters: Map<String, String>): JsonObject {
        val sent = reader.send(endpoint, parameters)
        val response = sent.response
        checkStatus(response.statusCode, response.retryAfter)
        val body = (Json.parseToJsonElement(response.body) as? JsonObject)?.get("subsonic-response") as? JsonObject ?: invalidDiscover()
        if (body.discoverText("status") == "failed") {
            val error = body.discoverObj("error")
            throw DiscoverWireFailure(when (error.discoverText("reason")) {
                "cursor-expired" -> DiscoverFailure("cursor-expired", null, true)
                "upstream-unavailable" -> DiscoverFailure("upstream-unavailable", discoverRetryAt(error.discoverText("retryAfterSeconds"), wall()), true)
                "operation-conflict" -> DiscoverFailure("conflict", null, false)
                else -> discoverCode(error.discoverInteger("code") ?: 0)
            })
        }
        // Preserve standard envelope/status validation after inspecting extension reason metadata.
        sent.requireOk(endpoint, parameters)
        return body
    }
    fun checkStatus(status: Int, retry: String?) {
        if (status == 429) throw DiscoverWireFailure(DiscoverFailure("rate-limited", discoverRetryAt(retry, wall()), true))
        if (status !in 200..299) throw DiscoverWireFailure(discoverFailure(DomainError.Server.HttpStatus(status)))
    }
}
/** HTTP delta seconds or IMF-fixdate, measured later against a monotonic deadline. */
internal fun discoverRetryAt(value: String?, now: Long): Long {
    value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return now + it.coerceAtMost((Long.MAX_VALUE - now.coerceAtLeast(0)) / 1000) * 1000 }
    val match = value?.let { Regex("[A-Za-z]{3}, (\\d{2}) ([A-Za-z]{3}) (\\d{4}) (\\d{2}:\\d{2}:\\d{2}) GMT").matchEntire(it) }
    if (match != null) {
        val month = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec").indexOf(match.groupValues[2]) + 1
        val date = runCatching { Instant.parse("${match.groupValues[3]}-${month.toString().padStart(2,'0')}-${match.groupValues[1]}T${match.groupValues[4]}Z").toEpochMilliseconds() }.getOrNull()
        if (date != null) return maxOf(now, date)
    }
    return now + 60_000
}
internal fun JsonObject.discoverObj(key: String): JsonObject = this[key] as? JsonObject ?: invalidDiscover()
internal fun JsonObject.discoverText(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.discoverRequired(key: String): String = discoverText(key) ?: invalidDiscover()
internal fun JsonObject.discoverInteger(key: String): Int? = this[key]?.let { if (it == JsonNull) null else (it as? JsonPrimitive)?.intOrNull ?: invalidDiscover() }
internal fun JsonObject.discoverLong(key: String): Long? = this[key]?.let { if (it == JsonNull) null else (it as? JsonPrimitive)?.longOrNull ?: invalidDiscover() }
internal fun JsonObject.discoverFlag(key: String): Boolean? = this[key]?.let { if (it == JsonNull) null else (it as? JsonPrimitive)?.booleanOrNull ?: invalidDiscover() }
internal fun JsonObject.discoverTime(key: String): Long? = discoverText(key)?.let { Instant.parse(it).toEpochMilliseconds() }
internal fun JsonObject.discoverRows(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map { it as? JsonObject ?: invalidDiscover() } ?: invalidDiscover()
internal fun discoverLinks(body: JsonObject): List<DiscoverLink> = body.discoverObj("links").discoverRows("link").map { DiscoverLink(it.discoverRequired("relation"), it.discoverRequired("url")) }
internal fun parseDiscoverTarget(body: JsonObject, kind: String): DiscoverTarget? {
    val type = DiscoverKind.entries.firstOrNull { it.wire == kind } ?: return null
    val key = when(type) { DiscoverKind.Artist -> "musicbrainzArtistId"; DiscoverKind.Album -> "musicbrainzReleaseGroupId"; DiscoverKind.Song -> "musicbrainzRecordingId" }
    return (body["externalIds"] as? JsonObject)?.discoverText(key)?.takeIf { it.isNotBlank() }?.let { DiscoverTarget(type,it) }
}
internal fun parseDiscoverRef(body: JsonObject, provider: String) = DiscoverLibraryRef(provider,body.discoverRequired("type"),body.discoverRequired("id"))
internal fun parseDiscoverAvailability(body: JsonObject, provider: String) = DiscoverAvailability(
    body.discoverRequired("status"), body.discoverRequired("completeness"), body.discoverText("matchedBy"), body.discoverInteger("matchedTrackCount"), body.discoverInteger("totalTrackCount"),
    body.discoverText("editionReleaseId"), body.discoverObj("libraryItems").discoverRows("item").map { parseDiscoverRef(it,provider) })
internal fun parseDiscoverItem(body: JsonObject, provider: String): DiscoverItem {
    val kind = body.discoverRequired("kind")
    val target = parseDiscoverTarget(body,kind)
    return DiscoverItem(body.discoverRequired("id"),kind,target,body.discoverRequired("title"),body.discoverText("artist"),body.discoverInteger("year"),body.discoverText("releaseDate"),
        body.discoverText("artworkId"),body.discoverInteger("disc"),body.discoverInteger("position"),body.discoverLong("listenCount"),body.discoverText("primaryType"),discoverLinks(body),
        body.discoverFlag("wishable") == true && target != null,parseDiscoverAvailability(body.discoverObj("availability"),provider))
}
