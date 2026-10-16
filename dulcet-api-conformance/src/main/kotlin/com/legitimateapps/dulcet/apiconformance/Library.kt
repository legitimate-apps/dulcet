package com.legitimateapps.dulcet.apiconformance

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The default mutation adapter from CONFORMANCE.md "Shape": file operations on the library
 * directory the server scans, then `POST /admin/scans`. The suite writes its own small MP3 files
 * (silent MPEG-1 Layer III frames behind an ID3v2.3 tag) into a folder of its own, and removes the
 * folder at the end of the run.
 */
class LibraryFixture(private val root: Path, runId: String) {
    val dir: Path = root.resolve("dulcet-conformance-$runId")

    fun writeTrack(fileName: String, title: String, artist: String, album: String, trackNumber: Int, releaseId: String? = null): Path {
        Files.createDirectories(dir)
        val path = dir.resolve(fileName)
        Files.write(path, mp3(title, artist, album, trackNumber, releaseId))
        return path
    }

    fun retag(path: Path, title: String, artist: String, album: String, trackNumber: Int, releaseId: String? = null) {
        Files.write(path, mp3(title, artist, album, trackNumber, releaseId))
    }

    fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    fun removeAll() {
        if (!Files.exists(dir)) return
        Files.list(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(dir)
    }

    /** Starts a quick scan as admin and waits for it to finish. */
    fun scan(ctx: Ctx, timeoutSeconds: Int) {
        val admin = ctx.adminToken
        val start = ctx.api("POST", "startScan", "/admin/scans", token = admin, body = mapOf("kind" to "quick"))
        ctx.expectStatus(start, 202, what = "starting a scan")
        val id = ctx.body(start).str("id") ?: ctx.fail("the scan response has no id")
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val ex = ctx.api("GET", "getScan", "/admin/scans/${ctx.id(id)}", token = admin)
            ctx.expectStatus(ex, 200, what = "polling the scan")
            when (ctx.body(ex).str("state")) {
                "completed" -> return
                "failed" -> ctx.fail("the scan failed: ${ctx.body(ex).str("error")}")
            }
            Thread.sleep(250)
        }
        ctx.fail("the scan did not finish within $timeoutSeconds s")
    }

    companion object {
        /** A valid MP3: an ID3v2.3 tag (UTF-16 text frames) and 40 silent 128 kb/s 44.1 kHz mono frames, about one second. */
        /** The TXXX description Picard and other taggers use for the MusicBrainz release id. */
        const val MUSICBRAINZ_ALBUM_ID = "MusicBrainz Album Id"

        fun mp3(title: String, artist: String, album: String, trackNumber: Int, releaseId: String? = null): ByteArray {
            val frames = ByteArrayOutputStream()
            fun textFrame(id: String, value: String) {
                val text = ByteArrayOutputStream()
                text.write(0x01)
                text.write(0xFF); text.write(0xFE)
                text.write(value.toByteArray(Charsets.UTF_16LE))
                val body = text.toByteArray()
                frames.write(id.toByteArray(Charsets.US_ASCII))
                frames.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
                frames.write(byteArrayOf(0, 0))
                frames.write(body)
            }
            textFrame("TIT2", title)
            textFrame("TPE1", artist)
            textFrame("TPE2", artist)
            textFrame("TALB", album)
            textFrame("TRCK", trackNumber.toString())
            if (releaseId != null) {
                val body = byteArrayOf(0) + MUSICBRAINZ_ALBUM_ID.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + releaseId.toByteArray(Charsets.ISO_8859_1)
                frames.write("TXXX".toByteArray(Charsets.US_ASCII))
                frames.write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()))
                frames.write(byteArrayOf(0, 0))
                frames.write(body)
            }
            val tag = frames.toByteArray()
            val size = tag.size
            val out = ByteArrayOutputStream()
            out.write("ID3".toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(3, 0, 0))
            out.write(byteArrayOf(((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(), ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte()))
            out.write(tag)
            val frame = ByteArray(417)
            frame[0] = 0xFF.toByte(); frame[1] = 0xFB.toByte(); frame[2] = 0x90.toByte(); frame[3] = 0xC4.toByte()
            repeat(40) { out.write(frame) }
            return out.toByteArray()
        }

        /** Reads the text frames of an ID3v2.3 tag written by [mp3] (or any ISO-8859-1 / UTF-16 frames). */
        fun readTags(bytes: ByteArray): Map<String, String> {
            if (bytes.size < 10 || String(bytes, 0, 3, Charsets.US_ASCII) != "ID3") return emptyMap()
            val size = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
            val tags = HashMap<String, String>()
            var i = 10
            while (i + 10 <= 10 + size && i + 10 <= bytes.size) {
                val id = String(bytes, i, 4, Charsets.US_ASCII)
                if (id[0] == '\u0000') break
                val len = ((bytes[i + 4].toInt() and 0xFF) shl 24) or ((bytes[i + 5].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 6].toInt() and 0xFF) shl 8) or (bytes[i + 7].toInt() and 0xFF)
                val start = i + 10
                if (len <= 0 || start + len > bytes.size) break
                if (id == "TXXX" && bytes[start].toInt() == 0) {
                    val raw = String(bytes.copyOfRange(start + 1, start + len), Charsets.ISO_8859_1)
                    tags["TXXX:" + raw.substringBefore('\u0000')] = raw.substringAfter('\u0000').trimEnd('\u0000')
                } else if (id.startsWith("T")) {
                    val enc = bytes[start].toInt()
                    val raw = bytes.copyOfRange(start + 1, start + len)
                    tags[id] = when (enc) {
                        1 -> String(raw, Charsets.UTF_16)
                        3 -> String(raw, Charsets.UTF_8)
                        else -> String(raw, Charsets.ISO_8859_1)
                    }.trimEnd('\u0000')
                }
                i = start + len
            }
            return tags
        }
    }
}
