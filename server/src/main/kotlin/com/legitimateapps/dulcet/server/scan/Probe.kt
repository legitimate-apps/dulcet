package com.legitimateapps.dulcet.server.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Everything the scanner needs from one audio file, read with ffprobe. */
data class ProbedTags(
    val formatName: String,
    val duration: Double,
    val bitRateKbps: Int,
    val codec: String?,
    val sampleRate: Int?,
    val channels: Int?,
    val bitDepth: Int?,
    val hasEmbeddedArt: Boolean,
    /** Tag keys lowercased with every non-alphanumeric character removed; first value wins. */
    val tags: Map<String, String>,
    /** Embedded lyrics by language ("xxx" when the tag carries none). */
    val lyrics: Map<String, String>,
) {
    fun tag(vararg keys: String): String? = keys.firstNotNullOfOrNull { tags[it]?.trim()?.takeIf(String::isNotEmpty) }
}

class FfprobeException(message: String) : Exception(message)

/**
 * ffprobe is the tag reader: it understands every container the transcoder can play, and the
 * server needs ffmpeg anyway. One process per changed file; unchanged files are never probed.
 */
class Probe(private val ffprobe: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun probe(file: Path): ProbedTags {
        val process = ProcessBuilder(
            *(listOf(ffprobe, "-v", "error", "-hide_banner") + com.legitimateapps.dulcet.server.media.AUDIO_INPUT_ARGS +
                listOf("-print_format", "json", "-show_format", "-show_streams", file.toAbsolutePath().toString())).toTypedArray(),
        ).redirectErrorStream(false).start()
        val output = process.inputStream.readBytes().decodeToString()
        val errors = process.errorStream.readBytes().decodeToString()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw FfprobeException("ffprobe timed out")
        }
        if (process.exitValue() != 0) throw FfprobeException("ffprobe failed: ${errors.take(300)}")
        return parse(output, file)
    }

    fun parse(output: String, file: Path? = null): ProbedTags {
        val root = json.parseToJsonElement(output) as? JsonObject ?: throw FfprobeException("ffprobe printed no object")
        val format = root["format"] as? JsonObject ?: throw FfprobeException("ffprobe printed no format")
        val streams = (root["streams"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val audio = streams.firstOrNull { it.str("codec_type") == "audio" } ?: throw FfprobeException("no audio stream")
        val art = streams.any { stream ->
            stream.str("codec_type") == "video" &&
                ((stream["disposition"] as? JsonObject)?.get("attached_pic") as? JsonPrimitive)?.intOrNull == 1
        }
        val tags = LinkedHashMap<String, String>()
        val lyrics = LinkedHashMap<String, String>()
        for (container in listOf(format, audio) + streams) {
            val containerTags = container["tags"] as? JsonObject ?: continue
            for ((key, value) in containerTags) {
                val text = (value as? JsonPrimitive)?.contentOrNull ?: continue
                val lyricsLanguage = lyricsLanguage(key)
                if (lyricsLanguage != null) {
                    lyrics.putIfAbsent(lyricsLanguage, text)
                    continue
                }
                tags.putIfAbsent(normalizeKey(key), text)
            }
        }
        val duration = format.str("duration")?.toDoubleOrNull() ?: audio.str("duration")?.toDoubleOrNull() ?: 0.0
        // The audio stream's rate when ffprobe knows it. FLAC streams carry none, and the container
        // rate counts metadata (pictures, padding), so for FLAC count only the audio frames.
        val streamBits = audio.str("bit_rate")?.toLongOrNull()
            ?: file?.let(::flacAudioBytes)?.takeIf { duration > 0 }?.let { (it * 8 / duration).toLong() }
        val bitRate = (streamBits ?: format.str("bit_rate")?.toLongOrNull() ?: 0L) / 1000
        return ProbedTags(
            formatName = format.str("format_name") ?: "",
            duration = duration,
            bitRateKbps = bitRate.toInt(),
            codec = audio.str("codec_name"),
            sampleRate = audio.str("sample_rate")?.toIntOrNull(),
            channels = (audio["channels"] as? JsonPrimitive)?.intOrNull,
            bitDepth = audio.str("bits_per_raw_sample")?.toIntOrNull()?.takeIf { it > 0 }
                ?: (audio["bits_per_sample"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 },
            hasEmbeddedArt = art,
            tags = tags,
            lyrics = lyrics,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        fun normalizeKey(key: String): String = key.lowercase().filter { it.isLetterOrDigit() }

        private val LYRICS_KEY = Regex("^(?:lyrics|unsyncedlyrics|unsynced lyrics|uslt)(?:[-:_ ]([A-Za-z]{3}))?$", RegexOption.IGNORE_CASE)

        fun lyricsLanguage(key: String): String? {
            val match = LYRICS_KEY.matchEntire(key.trim()) ?: return null
            return match.groupValues[1].lowercase().ifEmpty { "xxx" }
        }
    }
}

/** Bytes after a FLAC file's metadata blocks, or null when the file is not FLAC. */
internal fun flacAudioBytes(file: Path): Long? = runCatching {
    java.io.RandomAccessFile(file.toFile(), "r").use { input ->
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        while (true) {
            val header = input.readInt()
            val last = header ushr 31 == 1
            input.seek(input.filePointer + (header and 0xFFFFFF))
            if (last) break
        }
        input.length() - input.filePointer
    }
}.getOrNull()
