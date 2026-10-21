package com.legitimateapps.dulcet.core

import java.io.InputStream

/** One playback resource's cache write; common policy validates complete coverage and bytes. */
public interface AndroidStreamCacheWrite {
    public fun append(bytes: ByteArray, offset: Int, length: Int)
    public fun appendAt(bytes: ByteArray, offset: Int, length: Int, resourceOffset: Long) { append(bytes, offset, length) }
    public fun isComplete(): Boolean = false
    public fun finish()
    public fun abort()
}

/**
 * Tees only bytes Media3 consumes, including its validation prefix. The sink follows an immutable
 * playback resource across its range opens, so MP4 tail probes and seeks can fill holes without
 * another HTTP fetch. Only full coverage promotes; track teardown discards incomplete writes.
 */
internal class AndroidCachingPlaybackResource(
    private val upstream: AndroidPlaybackResource,
    private val plan: RemotePlaybackWirePlan,
    private val source: AndroidLocalPlaybackSource?,
) : AndroidPlaybackResource, AutoCloseable {
    private val lock = Any()
    private var writer: AndroidStreamCacheWrite? = null
    private var total: Long? = null
    private var begun = false
    private var terminal = false

    override fun open(position: Long, length: Long): AndroidPlaybackResponse {
        val response = upstream.open(position, length)
        val bodyLength = (response.headers.contentLength as? PlaybackContentLength.Exact)?.byteCount
        val range = if (response.status == 206) Regex("bytes (\\d+)-(\\d+)/(\\d+)")
            .matchEntire(response.headers.contentRange.orEmpty().trim())?.groupValues?.drop(1)?.map { it.toLongOrNull() } else null
        val fullLength = if (response.status == 200) bodyLength else range?.getOrNull(2)
        val valid = when (response.status) {
            200 -> position == 0L && length < 0
            206 -> range?.size == 3 && range.all { it != null } && range[0] == position &&
                range[1]!! >= position && range[1]!! < range[2]!! &&
                (bodyLength == null || bodyLength == range[1]!! - position + 1)
            else -> false
        }
        synchronized(lock) {
            if (!valid || (begun && total != fullLength)) abortLocked()
            if (valid && !begun && !terminal) {
                begun = true; total = fullLength
                writer = try { source?.beginStreamCacheWrite(plan, response.status,
                    response.headers.contentType, fullLength, position) } catch (_: Exception) { null }
            }
        }
        var readBytes = 0L
        val input = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = try { response.input.read(buffer, offset, length) }
                    catch (failure: Exception) { this@AndroidCachingPlaybackResource.close(); throw failure }
                synchronized(lock) {
                    if (count > 0) {
                        val resourceOffset = position + readBytes
                        readBytes += count
                        if (bodyLength != null && readBytes > bodyLength) abortLocked()
                        else try {
                            writer?.appendAt(buffer, offset, count, resourceOffset)
                            if (writer?.isComplete() == true) finishLocked()
                        } catch (_: Exception) { abortLocked() }
                    } else if (count < 0) {
                        if (bodyLength != null && readBytes != bodyLength) abortLocked()
                        else if (response.status == 200 && fullLength == null) finishLocked()
                        // Known-length writes with a hole survive this response's EOF.
                    }
                }
                return count
            }
        }
        return AndroidPlaybackResponse(response.status, response.headers, input) {
            synchronized(lock) {
                // Legacy sequential sinks need no extra EOF read after an exact complete200.
                if (response.status == 200 && bodyLength != null && readBytes == bodyLength) finishLocked()
                else if (bodyLength != null && readBytes < bodyLength && response.status == 200) abortLocked()
            }
            response.close()
        }
    }

    private fun finishLocked() {
        if (terminal) return
        terminal = true
        val held = writer; writer = null
        try { held?.finish() } catch (_: Exception) { try { held?.abort() } catch (_: Exception) {} }
    }
    private fun abortLocked() {
        terminal = true
        val held = writer; writer = null
        try { held?.abort() } catch (_: Exception) {}
    }
    override fun close() { synchronized(lock) { if (!terminal) abortLocked() } }
}
