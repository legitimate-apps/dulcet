package com.legitimateapps.dulcet.server.lyrics

import com.legitimateapps.dulcet.server.db.Db
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** One lyrics text: synced lines carry a start in milliseconds; unsynced lines carry none. */
data class LyricLine(val startMillis: Long?, val text: String)

data class LyricsText(
    val language: String,
    val synced: Boolean,
    val offsetMillis: Long,
    val lines: List<LyricLine>,
    val source: String,
)

object Lrc {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metadata = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")

    /** Parses LRC text. Returns synced lines when any line has a timestamp, else the text as plain lines. */
    fun parse(text: String, language: String, source: String): LyricsText? {
        val clean = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        var offset = 0L
        val synced = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        for (raw in clean.split('\n')) {
            val line = raw.trimEnd()
            val stamps = timestamp.findAll(line).toList()
            if (stamps.isNotEmpty() && line.startsWith("[")) {
                // Only leading timestamps count; text after them is the line.
                var cursor = 0
                val starts = ArrayList<Long>()
                for (match in stamps) {
                    if (match.range.first != cursor) break
                    starts.add(millis(match))
                    cursor = match.range.last + 1
                }
                if (starts.isNotEmpty()) {
                    val body = line.substring(cursor).trim()
                    starts.forEach { synced.add(LyricLine(it, body)) }
                    continue
                }
            }
            val meta = metadata.matchEntire(line)
            if (meta != null) {
                if (meta.groupValues[1].equals("offset", true)) offset = meta.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0
                continue
            }
            plain.add(line)
        }
        if (synced.isNotEmpty()) return LyricsText(language, true, offset, synced.sortedBy { it.startMillis }, source)
        val body = plain.dropWhile(String::isBlank).dropLastWhile(String::isBlank)
        if (body.isEmpty()) return null
        return LyricsText(language, false, 0, body.map { LyricLine(null, it) }, source)
    }

    private fun millis(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val fractionMillis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return (minutes * 60 + seconds) * 1000 + fractionMillis
    }
}

/** What LRCLIB answered for a track: found lyrics, a definite "none", or a temporary failure. */
sealed interface RemoteLyrics {
    data class Found(val synced: String?, val plain: String?, val instrumental: Boolean) : RemoteLyrics
    data object None : RemoteLyrics
    data class Unavailable(val retryAfterMillis: Long?) : RemoteLyrics
}

interface LyricsProvider {
    val name: String
    fun lookup(artist: String, title: String, album: String?, durationSeconds: Int?): RemoteLyrics
}

/**
 * LRCLIB (https://lrclib.net/docs). Identifies Dulcet Server in the User-Agent, sends requests one at
 * a time with a gap, and backs off for the whole server after a 429 for as long as Retry-After says.
 */
class LrcLibProvider(
    private val baseUrl: String,
    private val userAgent: String,
    private val minGapMillis: Long = 250,
    private val clock: () -> Long = System::currentTimeMillis,
) : LyricsProvider {
    override val name = "lrclib"
    private val log = LoggerFactory.getLogger(LrcLibProvider::class.java)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val blockedUntil = AtomicLong(0)
    private val gate = Any()
    private var lastRequest = 0L

    override fun lookup(artist: String, title: String, album: String?, durationSeconds: Int?): RemoteLyrics = synchronized(gate) {
        val now = clock()
        if (now < blockedUntil.get()) return RemoteLyrics.Unavailable(blockedUntil.get() - now)
        val wait = lastRequest + minGapMillis - now
        if (wait > 0) Thread.sleep(wait)
        lastRequest = clock()
        fun q(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
        val query = buildString {
            append("artist_name=").append(q(artist)).append("&track_name=").append(q(title))
            if (!album.isNullOrBlank()) append("&album_name=").append(q(album))
            if (durationSeconds != null && durationSeconds > 0) append("&duration=").append(durationSeconds)
        }
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/api/get?" + query))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            log.warn("LRCLIB request failed: {}", error.javaClass.simpleName)
            return RemoteLyrics.Unavailable(null)
        }
        return when (response.statusCode()) {
            200 -> parseFound(response.body())
            404 -> RemoteLyrics.None
            429 -> {
                val retry = response.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()?.times(1000) ?: 60_000L
                blockedUntil.set(clock() + retry)
                RemoteLyrics.Unavailable(retry)
            }
            else -> RemoteLyrics.Unavailable(null)
        }
    }

    private fun parseFound(body: String): RemoteLyrics = try {
        val json = Json.parseToJsonElement(body).jsonObject
        fun field(name: String) = (json[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        val instrumental = (json["instrumental"] as? JsonPrimitive)?.booleanOrNull ?: false
        val synced = field("syncedLyrics")
        val plain = field("plainLyrics")
        if (synced == null && plain == null && !instrumental) RemoteLyrics.None else RemoteLyrics.Found(synced, plain, instrumental)
    } catch (error: Exception) {
        RemoteLyrics.Unavailable(null)
    }
}

/**
 * Lyrics for a track, in order: the sidecar file (.lrc, then .txt), then lyrics embedded in the file,
 * then — when a provider is configured — a remote lookup made on first request and cached in the
 * database, including a definite "none" so the provider is asked once per track. Temporary failures
 * are not cached, so a later request asks again.
 */
class LyricsService(
    private val db: Db,
    private val musicFolder: Path,
    private val provider: LyricsProvider?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val noneRetryMillis: Long = 30L * 24 * 3600 * 1000,
) {
    private data class TrackRow(
        val path: String, val sidecar: String?, val embedded: String?, val artist: String, val title: String, val album: String, val duration: Double,
    )

    fun forTrack(trackId: String): List<LyricsText>? {
        val row = db.read {
            single("SELECT path, lyrics_sidecar, embedded_lyrics, artist, title, album, duration FROM track WHERE id = ?", trackId) {
                TrackRow(it.string("path"), it.stringOrNull("lyrics_sidecar"), it.stringOrNull("embedded_lyrics"), it.string("artist"), it.string("title"), it.string("album"), it.double("duration"))
            }
        } ?: return null
        local(row).takeIf { it.isNotEmpty() }?.let { return it }
        return remote(trackId, row)
    }

    private fun local(row: TrackRow): List<LyricsText> {
        val result = ArrayList<LyricsText>()
        row.sidecar?.let { relative ->
            val file = musicFolder.resolve(relative)
            val text = runCatching { Files.readString(file) }.getOrNull()
            if (text != null) {
                val parsed = if (relative.lowercase().endsWith(".txt")) Lrc.parse(text, "xxx", "sidecar")?.let { it.copy(synced = false, lines = it.lines.map { line -> line.copy(startMillis = null) }) }
                else Lrc.parse(text, "xxx", "sidecar")
                parsed?.let(result::add)
            }
        }
        row.embedded?.let { json ->
            runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()?.forEach { (language, value) ->
                val text = value.jsonPrimitive.contentOrNull ?: return@forEach
                Lrc.parse(text, language.lowercase(), "embedded")?.let(result::add)
            }
        }
        return result
    }

    private fun remote(trackId: String, row: TrackRow): List<LyricsText> {
        val cached = db.read {
            single("SELECT status, synced_lrc, plain, fetched FROM lyrics_cache WHERE track_id = ?", trackId) {
                listOf(it.string("status"), it.stringOrNull("synced_lrc"), it.stringOrNull("plain"), it.long("fetched").toString())
            }
        }
        if (cached != null) {
            val (status, synced, plain, fetched) = cached
            if (status == "found") return fromRemote(synced, plain)
            if (status == "none" && clock() - fetched!!.toLong() < noneRetryMillis) return emptyList()
        }
        val provider = provider ?: return emptyList()
        if (row.artist.startsWith("[Unknown") || row.title.isBlank()) return emptyList()
        val album = row.album.takeUnless { it.startsWith("[Unknown") }
        return when (val answer = provider.lookup(row.artist, row.title, album, row.duration.toInt())) {
            is RemoteLyrics.Found -> {
                store(trackId, provider.name, "found", answer.synced, answer.plain)
                fromRemote(answer.synced, answer.plain)
            }
            RemoteLyrics.None -> {
                store(trackId, provider.name, "none", null, null)
                emptyList()
            }
            is RemoteLyrics.Unavailable -> emptyList()
        }
    }

    private fun store(trackId: String, source: String, status: String, synced: String?, plain: String?) = db.write {
        execute(
            "INSERT INTO lyrics_cache(track_id, source, status, synced_lrc, plain, fetched) VALUES (?,?,?,?,?,?) " +
                "ON CONFLICT(track_id) DO UPDATE SET source = excluded.source, status = excluded.status, synced_lrc = excluded.synced_lrc, plain = excluded.plain, fetched = excluded.fetched",
            trackId, source, status, synced, plain, clock(),
        )
    }

    private fun fromRemote(synced: String?, plain: String?): List<LyricsText> =
        listOfNotNull((synced?.let { Lrc.parse(it, "xxx", "lrclib") } ?: plain?.let { Lrc.parse(it, "xxx", "lrclib") }))

}
