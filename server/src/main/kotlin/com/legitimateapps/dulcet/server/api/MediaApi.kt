package com.legitimateapps.dulcet.server.api

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.fold
import com.legitimateapps.dulcet.server.lyrics.LyricsText
import com.legitimateapps.dulcet.server.media.OutputFormat
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private data class Media(val id: String, val path: Path, val suffix: String, val contentType: String, val bitrate: Int, val codec: String?, val channels: Int?, val sampleRate: Int?, val bitDepth: Int?)

/** stream, download, getCoverArt, getTranscodeDecision, getTranscodeStream, getLyrics, getLyricsBySongId. */
fun mediaHandlers(ctx: ServerContext): Map<String, Handler> {

    fun media(id: String): Media? = ctx.db.read {
        single("SELECT id, path, suffix, content_type, bitrate, codec, channels, sample_rate, bit_depth FROM track WHERE id = ?", id) {
            Media(
                it.string("id"), ctx.config.musicFolder.resolve(it.string("path")), it.string("suffix"), it.string("content_type"),
                it.intOrNull("bitrate") ?: 0, it.stringOrNull("codec"), it.intOrNull("channels"), it.intOrNull("sample_rate"), it.intOrNull("bit_depth"),
            )
        }
    }?.let { media -> com.legitimateapps.dulcet.server.musicFile(ctx.config.musicFolder, media.path)?.let { media.copy(path = it) } }

    val cacheRoot = ctx.config.dataFolder.resolve("cache").resolve("transcode")

    /**
     * A transcode from the start is cached once complete, so a repeat request is served from disk
     * with an exact length and byte ranges. A first request that asks for a range waits for the
     * whole transcode; one without a range streams live while the cache file is written beside it.
     */
    suspend fun transcode(call: Call, media: Media, format: OutputFormat, bitrate: Int, channels: Int?, offset: Int) {
        val type = ContentType.parse(format.contentType)
        if (offset > 0) {
            val process = withContext(Dispatchers.IO) { ctx.transcoder.start(media.path, format, bitrate, channels, offset) }
            call.ktor.respond(ProcessContent(process, type, null, null))
            return
        }
        val stamp = "${media.path}|${withContext(Dispatchers.IO) { Files.getLastModifiedTime(media.path).toMillis() }}|${format.name}|$bitrate|${channels ?: 0}"
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        val cached = cacheRoot.resolve(key.take(2)).resolve("$key.${format.suffix}")
        if (!Files.isRegularFile(cached) && call.ktor.request.headers[HttpHeaders.Range] != null) {
            withContext(Dispatchers.IO) {
                Files.createDirectories(cached.parent)
                val temporary = Files.createTempFile(cached.parent, key, ".part")
                var process: Process? = null
                try {
                    process = ctx.transcoder.start(media.path, format, bitrate, channels, 0)
                    Files.newOutputStream(temporary).use { out -> process.inputStream.copyTo(out) }
                    if (process.waitFor() == 0) Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    process?.destroyForcibly()
                    Files.deleteIfExists(temporary)
                }
            }
        }
        if (Files.isRegularFile(cached)) {
            serveFile(call, cached, type)
            return
        }
        withContext(Dispatchers.IO) { Files.createDirectories(cached.parent) }
        val temporary = withContext(Dispatchers.IO) { Files.createTempFile(cached.parent, key, ".part") }
        val process = try {
            withContext(Dispatchers.IO) { ctx.transcoder.start(media.path, format, bitrate, channels, 0) }
        } catch (error: Exception) { Files.deleteIfExists(temporary); throw error }
        try { call.ktor.respond(ProcessContent(process, type, temporary, cached)) }
        catch (error: Exception) { process.destroyForcibly(); Files.deleteIfExists(temporary); throw error }
    }

    return mapOf(
        "stream" to stream@{ call ->
            val media = media(call.required("id")) ?: throw SubsonicError.notFound("Song")
            val format = call.param("format")?.takeIf(String::isNotEmpty)
            val maxBitRate = call.int("maxBitRate", 0)
            val offset = call.int("timeOffset", 0)
            val target = when {
                format == "raw" -> null
                format != null -> OutputFormat.byLegacyName(format) ?: if (format.equals(media.suffix, true)) null else OutputFormat.MP3
                maxBitRate > 0 && (media.bitrate == 0 || media.bitrate > maxBitRate) -> OutputFormat.MP3
                offset > 0 -> OutputFormat.MP3
                else -> null
            }
            if (target == null || (target.suffix.equals(media.suffix, true) && maxBitRate == 0 && offset == 0)) {
                serveFile(call, media.path, ContentType.parse(media.contentType))
            } else {
                val bitrate = if (maxBitRate > 0) maxBitRate else defaultBitrate(target)
                transcode(call, media, target, bitrate, null, offset)
            }
            Reply.Handled
        },
        "download" to { call ->
            val media = media(call.required("id")) ?: throw SubsonicError.notFound("Song")
            serveFile(call, media.path, ContentType.parse(media.contentType), attachment = true)
            Reply.Handled
        },
        "getCoverArt" to { call ->
            val id = call.required("id")
            val image = withContext(Dispatchers.IO) { ctx.artwork.image(id, call.intOrNull("size"), call.user) } ?: throw SubsonicError.notFound("Artwork")
            call.ktor.response.headers.append(HttpHeaders.CacheControl, "private, max-age=315360000")
            call.ktor.respond(ByteContent(image.bytes, ContentType.parse(image.contentType)))
            Reply.Handled
        },
        "getTranscodeDecision" to { call ->
            val mediaId = call.required("mediaId")
            val mediaType = call.required("mediaType")
            if (mediaType != "song") throw SubsonicError(0, "Unsupported mediaType: $mediaType")
            val media = media(mediaId) ?: throw SubsonicError.notFound("Song")
            val profile = runCatching { Json.parseToJsonElement(call.body ?: "{}").jsonObject }.getOrNull()
                ?: throw SubsonicError(10, "Invalid client profile")
            ok { put(decide(ctx, media, profile)) }
        },
        "getTranscodeStream" to stream@{ call ->
            val mediaId = call.param("mediaId")
            val token = call.param("transcodeParams")
            if (mediaId.isNullOrEmpty() || token.isNullOrEmpty() || call.param("mediaType").isNullOrEmpty()) {
                call.ktor.respondText("missing required parameter", status = HttpStatusCode.BadRequest)
                return@stream Reply.Handled
            }
            val media = media(mediaId)
            if (media == null) {
                call.ktor.respondText("Not Found\n", status = HttpStatusCode.NotFound)
                return@stream Reply.Handled
            }
            val decoded = ctx.transcodeParams.decode(token)
            if (decoded == null || decoded.trackId != mediaId) {
                call.ktor.respondText("Bad Request\n", status = HttpStatusCode.BadRequest)
                return@stream Reply.Handled
            }
            transcode(call, media, decoded.format, decoded.bitrateKbps, decoded.channels, call.int("offset", 0))
            Reply.Handled
        },
        "getLyricsBySongId" to { call ->
            val id = call.required("id")
            val song = ctx.db.read { single("SELECT artist, title FROM track WHERE id = ?", id) { it.string("artist") to it.string("title") } }
                ?: throw SubsonicError.notFound("Song")
            val texts = withContext(Dispatchers.IO) { ctx.lyrics.forTrack(id) }.orEmpty()
            ok {
                child("lyricsList") {
                    texts.forEach { text -> add(structured(text, song.first, song.second)) }
                }
            }
        },
        "getLyrics" to { call ->
            val artist = call.param("artist").orEmpty()
            val title = call.param("title").orEmpty()
            val id = ctx.db.read {
                single(
                    "SELECT t.id FROM track t WHERE lower(t.title) = lower(?) AND (? = '' OR t.id IN (SELECT track_id FROM track_artist WHERE artist_id IN (SELECT id FROM artist WHERE search = ?))) LIMIT 1",
                    title, artist, fold(artist),
                ) { it.string("id") }
            }
            val text = id?.let { withContext(Dispatchers.IO) { ctx.lyrics.forTrack(it) } }?.firstOrNull()
            ok {
                child("lyrics") {
                    if (text != null) {
                        this["artist"] = artist
                        this["title"] = title
                        value = text.lines.joinToString("\n") { it.text }
                    }
                }
            }
        },
    )
}

private fun defaultBitrate(format: OutputFormat): Int = when (format.codec) {
    "opus" -> 128
    "flac" -> 0
    else -> 192
}

private fun structured(text: LyricsText, artist: String, title: String): Node = Node("structuredLyrics").apply {
    this["displayArtist"] = artist
    this["displayTitle"] = title
    this["kind"] = "main"
    this["lang"] = text.language
    this["synced"] = text.synced
    if (text.synced && text.offsetMillis != 0L) this["offset"] = text.offsetMillis
    text.lines.forEach { line ->
        add("line") {
            line.startMillis?.let { this["start"] = it }
            this["value"] = line.text
        }
    }
}

/** Direct play or a transcode, from the flat client profile Dulcet sends (OpenSubsonic `transcoding`). */
private fun decide(ctx: ServerContext, media: Media, profile: JsonObject): Node {
    fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.orEmpty()
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    val sourceContainer = containerOf(media.suffix)
    val sourceCodec = (media.codec ?: media.suffix).lowercase()
    // The profile's bitrates are bits per second (OpenSubsonic transcoding); the catalog's are kbps.
    val maxBitrate = profile.int("maxAudioBitrate")?.takeIf { it > 0 }?.let { it / 1000 }
    val maxTranscodeBitrate = profile.int("maxTranscodingAudioBitrate")?.takeIf { it > 0 }?.let { it / 1000 }
    val reasons = ArrayList<String>()
    val direct = (profile["directPlayProfiles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    var canDirect = false
    for (candidate in direct) {
        val containers = candidate.strings("containers")
        val codecs = candidate.strings("audioCodecs")
        val protocols = candidate.strings("protocols")
        val maxChannels = candidate.int("maxAudioChannels")
        if (containers.isNotEmpty() && sourceContainer !in containers && media.suffix.lowercase() !in containers) continue
        if (codecs.isNotEmpty() && sourceCodec !in codecs) continue
        if (protocols.isNotEmpty() && "http" !in protocols) continue
        if (maxChannels != null && maxChannels > 0 && (media.channels ?: 2) > maxChannels) continue
        canDirect = true
        break
    }
    if (!canDirect) reasons.add(if (direct.isEmpty()) "no direct play profile" else "container or codec not supported")
    if (canDirect && maxBitrate != null && media.bitrate > maxBitrate) {
        canDirect = false
        reasons.add("audio bitrate not supported")
    }
    var transcode: Triple<OutputFormat, Int, Int?>? = null
    if (!canDirect) {
        for (candidate in (profile["transcodingProfiles"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
            val protocol = (candidate["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            if (protocol != null && protocol != "http") continue
            val container = (candidate["container"] as? JsonPrimitive)?.contentOrNull ?: continue
            val format = OutputFormat.forProfile(container, (candidate["audioCodec"] as? JsonPrimitive)?.contentOrNull) ?: continue
            val cap = listOfNotNull(maxTranscodeBitrate, maxBitrate).minOrNull()
            val bitrate = if (format.codec == "flac") 0 else minOf(cap ?: defaultBitrate(format), if (media.bitrate > 0) maxOf(media.bitrate, 32) else Int.MAX_VALUE).coerceAtLeast(32)
            val channels = candidate.int("maxAudioChannels")?.takeIf { it > 0 && (media.channels ?: 2) > it }
            transcode = Triple(format, bitrate, channels)
            break
        }
    }
    return Node("transcodeDecision").apply {
        this["canDirectPlay"] = canDirect
        this["canTranscode"] = transcode != null
        if (!canDirect) strings("transcodeReason", reasons)
        if (!canDirect && transcode == null) this["errorReason"] = "no compatible transcoding profile"
        transcode?.let { (format, bitrate, channels) -> this["transcodeParams"] = ctx.transcodeParams.encode(media.id, format, bitrate, channels) }
        child("sourceStream") {
            this["protocol"] = "http"
            this["container"] = sourceContainer
            this["codec"] = sourceCodec
            this["audioChannels"] = media.channels ?: 2
            this["audioBitrate"] = media.bitrate * 1000
            media.sampleRate?.let { this["audioSamplerate"] = it }
            media.bitDepth?.takeIf { it > 0 }?.let { this["audioBitdepth"] = it }
        }
        transcode?.let { (format, bitrate, channels) ->
            child("transcodeStream") {
                this["protocol"] = "http"
                this["container"] = format.container
                this["codec"] = format.codec
                this["audioChannels"] = channels ?: media.channels ?: 2
                this["audioBitrate"] = bitrate * 1000
                media.sampleRate?.let { this["audioSamplerate"] = if (format.codec == "opus") 48000 else it }
            }
        }
    }
}

private fun containerOf(suffix: String): String = when (suffix.lowercase()) {
    "m4a", "m4b", "mp4", "alac" -> "mp4"
    "oga", "opus", "ogg" -> "ogg"
    "aac" -> "aac"
    else -> suffix.lowercase()
}

/** Serves a file with single-range support: 200 with the whole file, 206 for a range, 416 when unsatisfiable. */
private suspend fun serveFile(call: Call, path: Path, type: ContentType, attachment: Boolean = false) {
    val length = withContext(Dispatchers.IO) { Files.size(path) }
    val range = call.ktor.request.headers[HttpHeaders.Range]?.let { parseRange(it, length) }
    val extra = ArrayList<Pair<String, String>>()
    extra.add(HttpHeaders.AcceptRanges to "bytes")
    if (attachment) extra.add(HttpHeaders.ContentDisposition to "attachment; filename=\"${path.fileName.toString().replace("\"", "")}\"")
    when {
        range == null -> call.ktor.respond(FileContent(path, 0, length, type, HttpStatusCode.OK, extra))
        range.first < 0 -> {
            call.ktor.response.headers.append(HttpHeaders.ContentRange, "bytes */$length")
            call.ktor.respond(HttpStatusCode.RequestedRangeNotSatisfiable, "")
        }
        else -> {
            extra.add(HttpHeaders.ContentRange to "bytes ${range.first}-${range.last}/$length")
            call.ktor.respond(FileContent(path, range.first, range.last - range.first + 1, type, HttpStatusCode.PartialContent, extra))
        }
    }
}

/** null: no usable range (serve whole); first = -1: unsatisfiable. */
internal fun parseRange(header: String, length: Long): LongRange? {
    if (!header.startsWith("bytes=")) return null
    val spec = header.removePrefix("bytes=").trim()
    if (spec.contains(',')) return null
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()
    if (startText.isEmpty()) {
        val suffix = endText.toLongOrNull() ?: return null
        if (suffix <= 0) return -1L..-1L
        return maxOf(0, length - suffix) until length
    }
    val start = startText.toLongOrNull() ?: return null
    if (start >= length) return -1L..-1L
    val end = endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
    if (end < start) return null
    return start..end
}

private class FileContent(
    private val path: Path,
    private val start: Long,
    private val length: Long,
    override val contentType: ContentType,
    override val status: HttpStatusCode,
    extra: List<Pair<String, String>>,
) : OutgoingContent.WriteChannelContent() {
    override val contentLength: Long = length
    override val headers: Headers = headersOf(*extra.map { it.first to listOf(it.second) }.toTypedArray())

    override suspend fun writeTo(channel: ByteWriteChannel) {
        withContext(Dispatchers.IO) {
            RandomAccessFile(path.toFile(), "r").use { file ->
                file.seek(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    channel.writeFully(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }
}

/** Streams a transcode as it is produced; with [temporary]/[cached] it also keeps a copy, kept only when ffmpeg finished cleanly. */
private class ProcessContent(
    private val process: Process,
    override val contentType: ContentType,
    private val temporary: Path?,
    private val cached: Path?,
) : OutgoingContent.WriteChannelContent() {
    override val status: HttpStatusCode = HttpStatusCode.OK
    override suspend fun writeTo(channel: ByteWriteChannel) {
        var complete = false
        val copy = temporary?.let { Files.newOutputStream(it) }
        try {
            withContext(Dispatchers.IO) {
                val input = process.inputStream
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copy?.write(buffer, 0, read)
                    channel.writeFully(buffer, 0, read)
                    channel.flush()
                }
                complete = process.waitFor() == 0
            }
        } finally {
            process.destroy()
            copy?.close()
            if (temporary != null) {
                if (complete && cached != null) runCatching { Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                Files.deleteIfExists(temporary)
            }
        }
    }
}

private class ByteContent(private val bytes: ByteArray, override val contentType: ContentType) : OutgoingContent.ByteArrayContent() {
    override val contentLength: Long = bytes.size.toLong()
    override fun bytes(): ByteArray = bytes
}
