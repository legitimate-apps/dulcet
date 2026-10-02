package com.legitimateapps.dulcet.emulator

import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.StreamingQuality
import com.legitimateapps.dulcet.core.StreamingQualityPreference
import com.legitimateapps.dulcet.playback.StreamingQualitySettings

/**
 * The streaming-quality device proof (spec §12.5, CONF-92), shared by the phone and TV: the app plays
 * a FLAC source above the cap through a [ServerRelay], and what the server answered for the audio is
 * read from the relay's bytes — an encoding other than the FLAC original with the cap set, the FLAC
 * original itself without it. That is the server's transcode observed on the wire the app read, not
 * the app's claim about its request.
 */
object StreamingQualityProof {
    /** The fixture's FLAC source above the cap (about 123 kbps, two seconds): a cap applies to it. */
    const val SOURCE_TITLE = "Dulcet Health Probe"

    /** Below the source's bitrate on both network kinds, so the cap applies whatever the emulator's network is. */
    val CAPPED = StreamingQualityPreference(StreamingQuality.Kbps96, StreamingQuality.Kbps96)

    /**
     * The proof: [entry] is the surface's production play entry for a song, and [launch] starts it.
     * The song is played capped, then — the choice set back to Original, which the controller
     * applies to the next resolve only — played again as the control. Returns the line to print.
     */
    fun run(surface: String, entry: (Context, String, String) -> Intent, launch: (Intent) -> AutoCloseable): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val rawId = probe.songId(SOURCE_TITLE)
        val source = probe.call("getSong", mapOf("id" to rawId)).getJSONObject("song")
        check(source.getString("suffix") == "flac") { "setup: the source must be the FLAC fixture" }
        val sourceKbps = source.getInt("bitRate")
        check(sourceKbps > CAPPED.metered.maxBitRateKbps!!) { "setup: the source ($sourceKbps kbps) must be above the cap" }
        awaitQueuedBroadcastsDelivered()
        ServerRelay(probe.baseUrl).use { relay ->
            connectSavedAccount(context, probe, relay.url)
            val account = checkNotNull(AndroidAccountCredentialStore(context).load())
            try {
                PlaybackObserver(context).use { observer ->
                    choose(context, CAPPED)
                    val capped = play(relay, observer, rawId) { launch(entry(context, account.id, rawId)) }
                    check(capped.answers.none { it.flac }) { "$surface: with the cap the server sent the FLAC original: ${capped.answers}" }
                    check(capped.answers.any { it.signature() in TRANSCODED }) {
                        "$surface: with the cap no answer was audio in another encoding: ${capped.answers}"
                    }
                    check(!capped.seekable) { "$surface: a stream the server transcodes is not seekable on Android (spec 12.5)" }

                    choose(context, StreamingQualityPreference.Default)
                    val original = play(relay, observer, rawId) { launch(entry(context, account.id, rawId)) }
                    check(original.answers.any { it.flac }) { "$surface: the control must stream the FLAC original: ${original.answers}" }
                    return "ANDROID EMULATOR STREAMING QUALITY OBSERVED surface=$surface source=flac@${sourceKbps}kbps " +
                        "cap=${CAPPED.metered.maxBitRateKbps}kbps capped=${capped.answers.map { it.signature() }} " +
                        "capped-seekable=${capped.seekable} original=${original.answers.map { it.signature() }} " +
                        "original-seekable=${original.seekable}"
                }
            } finally {
                StreamingQualitySettings.get(context).set(StreamingQualityPreference.Default)
            }
        }
    }

    private class Played(val answers: List<Answer>, val seekable: Boolean)

    /** Plays [rawId] once from [start], and keeps the audio the server answered and whether the player could seek. */
    private fun play(relay: ServerRelay, observer: PlaybackObserver, rawId: String, start: () -> AutoCloseable): Played {
        // A connection kept alive from the last play may carry this one's requests, so answers are
        // counted from where each connection stood now, not by which connections are new.
        val mark = relay.connections().associateWith { it.answered().size }
        start().use {
            observer.bind()
            var seekable = false
            await("the source playing") {
                val state = observer.state()
                val playing = state.queue.getOrNull(state.currentIndex ?: -1)?.track?.rawId == rawId && state.phase == "Progressing"
                if (playing) seekable = state.seekable
                playing
            }
            await("the server's audio answer") { answers(relay, mark).any { it.audio && it.status in 200..299 } }
            observer.stopPlayback()
            return Played(answers(relay, mark).filter { it.audio && it.status in 200..299 }, seekable)
        }
    }

    /** The settings store the app's quality controls write and its playback service reads. */
    fun choose(context: Context, preference: StreamingQualityPreference) {
        val settings = StreamingQualitySettings.get(context)
        settings.set(preference)
        check(settings.preference.value == preference) { "The quality choice was not kept: ${settings.preference.value}" }
    }

    /** One HTTP answer the server gave the app: the endpoint asked, the status, its type and the body's first bytes. */
    data class Answer(val endpoint: String, val status: Int, val contentType: String?, val head: ByteArray) {
        val flac: Boolean get() = head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC"
        val audio: Boolean get() = contentType?.startsWith("audio/") == true || endpoint in AUDIO_ENDPOINTS
        override fun toString(): String = "$endpoint:$status:${contentType ?: "-"}:${signature()}"

        /** A printable name for the body's first bytes; never the bytes themselves. */
        fun signature(): String = when {
            flac -> "flac"
            head.size >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> "mp3-id3"
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mpeg-frame"
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "OggS" -> "ogg"
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> "mp4"
            head.isEmpty() -> "empty"
            else -> "other"
        }
    }

    /**
     * Every answer the relay carried after [mark] (each connection's answered length then; a
     * connection absent from it is new), paired with its request by order on its connection
     * (HTTP/1.1 answers in request order). Only endpoint names leave the parse: request lines carry
     * credentials.
     */
    fun answers(relay: ServerRelay, mark: Map<ServerRelay.Tapped, Int>): List<Answer> = relay.connections().flatMap { connection ->
        val from = mark[connection] ?: 0
        val requests = REQUEST_LINE.findAll(String(connection.sent(), Charsets.ISO_8859_1))
            .map { it.groupValues[1] to endpointOf(it.groupValues[2]) }.toList()
        val bytes = connection.answered()
        val answers = mutableListOf<Answer>()
        var at = 0
        var index = 0
        while (at < bytes.size) {
            val headerEnd = indexOf(bytes, CRLFCRLF, at)
            if (headerEnd < 0) break
            val header = String(bytes, at, headerEnd - at, Charsets.ISO_8859_1)
            val lines = header.split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: break
            val fields = lines.drop(1).mapNotNull { line ->
                line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() }
            }.toMap()
            val (method, endpoint) = requests.getOrElse(index) { "GET" to "?" }
            val bodyStart = headerEnd + CRLFCRLF.size
            val (head, next) = when {
                method == "HEAD" || status == 204 || status == 304 -> ByteArray(0) to bodyStart
                fields["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> chunked(bytes, bodyStart)
                fields["content-length"] != null -> {
                    val length = fields.getValue("content-length").toLong()
                    val end = (bodyStart + length).coerceAtMost(bytes.size.toLong()).toInt()
                    bytes.copyOfRange(bodyStart, minOf(end, bodyStart + HEAD_BYTES)) to end
                }
                else -> bytes.copyOfRange(bodyStart, minOf(bytes.size, bodyStart + HEAD_BYTES)) to bytes.size
            }
            if (at >= from) answers += Answer(endpoint, status, fields["content-type"], head)
            index++
            at = next
        }
        answers
    }

    /** The first bytes of a chunked body, and where the next message starts. */
    private fun chunked(bytes: ByteArray, start: Int): Pair<ByteArray, Int> {
        val head = java.io.ByteArrayOutputStream()
        var at = start
        while (at < bytes.size) {
            val lineEnd = indexOf(bytes, CRLF, at)
            if (lineEnd < 0) return head.toByteArray() to bytes.size
            val size = String(bytes, at, lineEnd - at, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16)
                ?: return head.toByteArray() to bytes.size
            at = lineEnd + CRLF.size
            if (size == 0) {
                val trailerEnd = indexOf(bytes, CRLF, at)
                return head.toByteArray() to (if (trailerEnd < 0) bytes.size else trailerEnd + CRLF.size)
            }
            val take = minOf(size, bytes.size - at, HEAD_BYTES - head.size()).coerceAtLeast(0)
            head.write(bytes, at, take)
            at += size + CRLF.size
        }
        return head.toByteArray() to bytes.size
    }

    private fun endpointOf(path: String): String =
        path.substringBefore('?').substringAfterLast('/').removeSuffix(".view")

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
        var index = from
        while (index <= bytes.size - pattern.size) {
            if ((pattern.indices).all { bytes[index + it] == pattern[it] }) return index
            index++
        }
        return -1
    }

    /** Java's multiline `$` matches before a whole `\r\n`, so the line ends at the version. */
    private val REQUEST_LINE = Regex("""(?m)^(GET|POST|HEAD) (\S+) HTTP/1\.[01]$""")
    private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
    private val CRLFCRLF = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    private const val HEAD_BYTES = 16

    /** Audio a server sends when it re-encodes: what a transcode to a lossy format starts with. */
    private val TRANSCODED = setOf("mp3-id3", "mpeg-frame", "ogg", "mp4")
    private val AUDIO_ENDPOINTS = setOf("stream", "download", "getTranscodeStream")
}
