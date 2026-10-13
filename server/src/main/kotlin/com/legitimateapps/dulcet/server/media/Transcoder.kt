package com.legitimateapps.dulcet.server.media

import java.nio.file.Path
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One output format the server can encode to: the container, the codec and its MIME type. */
data class OutputFormat(val name: String, val container: String, val codec: String, val contentType: String, val suffix: String) {
    fun ffmpegArgs(bitrateKbps: Int, channels: Int?): List<String> = buildList {
        add("-map"); add("0:a:0")
        add("-vn"); add("-sn"); add("-dn")
        if (channels != null && channels > 0) { add("-ac"); add(channels.toString()) }
        when (codec) {
            "mp3" -> { add("-c:a"); add("libmp3lame"); add("-b:a"); add("${bitrateKbps}k"); add("-f"); add("mp3") }
            "opus" -> { add("-c:a"); add("libopus"); add("-b:a"); add("${bitrateKbps}k"); add("-f"); add("ogg") }
            "aac" -> {
                add("-c:a"); add("aac"); add("-b:a"); add("${bitrateKbps}k")
                if (container == "mp4") { add("-movflags"); add("frag_keyframe+empty_moov+default_base_moof"); add("-f"); add("mp4") } else { add("-f"); add("adts") }
            }
            "flac" -> { add("-c:a"); add("flac"); add("-f"); add("flac") }
            else -> error("unsupported codec $codec")
        }
    }

    companion object {
        val MP3 = OutputFormat("mp3", "mp3", "mp3", "audio/mpeg", "mp3")
        val OPUS = OutputFormat("opus", "ogg", "opus", "audio/ogg", "opus")
        val AAC = OutputFormat("aac", "aac", "aac", "audio/aac", "aac")
        val M4A = OutputFormat("m4a", "mp4", "aac", "audio/mp4", "m4a")
        val FLAC = OutputFormat("flac", "flac", "flac", "audio/flac", "flac")
        val ALL = listOf(MP3, OPUS, AAC, M4A, FLAC)

        /** The legacy `stream?format=` names. */
        fun byLegacyName(name: String): OutputFormat? = when (name.lowercase()) {
            "mp3" -> MP3
            "opus", "ogg", "oga" -> OPUS
            "aac", "adts" -> AAC
            "m4a", "mp4" -> M4A
            "flac" -> FLAC
            else -> null
        }

        /** A transcoding profile's (container, codec) pair. */
        fun forProfile(container: String, codec: String?): OutputFormat? {
            val c = container.lowercase()
            val k = codec?.lowercase()?.takeIf(String::isNotEmpty)
            return ALL.firstOrNull { f ->
                (f.container == c || (c == "m4a" && f.container == "mp4") || (c == "adts" && f.container == "aac") || (c == "oga" && f.container == "ogg")) &&
                    (k == null || f.codec == k)
            }
        }
    }
}

/** Runs ffmpeg for one transcode, writing to its standard output. */
class TranscodeBusyException : Exception("Transcode capacity reached")

class Transcoder(private val ffmpeg: String) {
    private val slots = java.util.concurrent.Semaphore(4)
    fun start(source: Path, format: OutputFormat, bitrateKbps: Int, channels: Int?, offsetSeconds: Int): Process {
        val command = buildList {
            add(ffmpeg); add("-hide_banner"); add("-loglevel"); add("error"); add("-nostdin")
            if (offsetSeconds > 0) { add("-ss"); add(offsetSeconds.toString()) }
            addAll(AUDIO_INPUT_ARGS)
            add("-i"); add(source.toString())
            addAll(format.ffmpegArgs(bitrateKbps, channels))
            add("-")
        }
        if (!slots.tryAcquire()) throw TranscodeBusyException()
        return try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start().also { process ->
                process.onExit().whenComplete { _, _ -> slots.release() }
            }
        } catch (error: Exception) { slots.release(); throw error }
    }
}

/**
 * The opaque `transcodeParams` a decision hands out: the track, the output format, bitrate and
 * channels, signed with the server key so a client cannot ask for an arbitrary ffmpeg command.
 */
class TranscodeParams(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "HmacSHA256")

    data class Decoded(val trackId: String, val format: OutputFormat, val bitrateKbps: Int, val channels: Int?)

    fun encode(trackId: String, format: OutputFormat, bitrateKbps: Int, channels: Int?): String {
        val payload = listOf("v1", trackId, format.name, bitrateKbps.toString(), (channels ?: 0).toString()).joinToString("|")
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return encoder.encodeToString(payload.toByteArray()) + "." + encoder.encodeToString(sign(payload)).take(22)
    }

    fun decode(token: String): Decoded? {
        val dot = token.indexOf('.')
        if (dot <= 0) return null
        val payload = runCatching { String(Base64.getUrlDecoder().decode(token.substring(0, dot))) }.getOrNull() ?: return null
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload)).take(22)
        if (!java.security.MessageDigest.isEqual(expected.toByteArray(), token.substring(dot + 1).toByteArray())) return null
        val parts = payload.split('|')
        if (parts.size != 5 || parts[0] != "v1") return null
        val format = OutputFormat.ALL.firstOrNull { it.name == parts[2] } ?: return null
        return Decoded(parts[1], format, parts[3].toIntOrNull() ?: return null, parts[4].toIntOrNull()?.takeIf { it > 0 })
    }

    private fun sign(payload: String): ByteArray = Mac.getInstance("HmacSHA256").apply { init(keySpec) }.doFinal(payload.toByteArray())
}
