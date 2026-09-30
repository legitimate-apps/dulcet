package com.legitimateapps.dulcet.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A downloaded item prepared for the Android engine: the queue's identities for this attempt and the
 * core's local plan for the promoted file (spec §14.5). The engine plays it through the same
 * validating data source as a remote plan, over [AndroidLocalFilePlaybackResource], so the bytes the
 * player reads from the file are signature-checked exactly as streamed bytes are (spec §12.4) — a
 * file damaged since its promotion fails as an unexpected binary instead of playing noise.
 */
public class AndroidLocalPlaybackPlan internal constructor(
    internal val playbackSessionId: PlaybackSessionId,
    internal val attemptId: AttemptId,
    internal val itemId: ProviderItemId,
    internal val local: LocalPlaybackPlan,
) : PlaybackPlan {
    override fun toString(): String = "AndroidLocalPlaybackPlan(<local-file>)"
}

/** The session and attempt a plan belongs to, whichever kind of plan the Android engine holds. */
internal val PlaybackPlan.androidAttemptId: AttemptId?
    get() = when (this) {
        is RemotePlaybackWirePlan -> attemptId
        is AndroidLocalPlaybackPlan -> attemptId
        else -> null
    }

internal val PlaybackPlan.androidSessionId: PlaybackSessionId?
    get() = when (this) {
        is RemotePlaybackWirePlan -> playbackSessionId
        is AndroidLocalPlaybackPlan -> playbackSessionId
        else -> null
    }

internal val PlaybackPlan.androidItemId: ProviderItemId?
    get() = when (this) {
        is RemotePlaybackWirePlan -> itemId
        is AndroidLocalPlaybackPlan -> itemId
        else -> null
    }

internal val PlaybackPlan.androidExpectedContainer: AudioContainer?
    get() = when (this) {
        is RemotePlaybackWirePlan -> expectedContainer
        is AndroidLocalPlaybackPlan -> local.container
        else -> null
    }

/**
 * Range reads of one promoted file, answered as an HTTP resource would answer them: the whole file
 * as a 200 with its exact length, any other range as a 206 whose `Content-Range` states it. The
 * file's length must be the exact length its row recorded at promotion; any other length is a file
 * changed since, and is refused rather than played.
 */
internal class AndroidLocalFilePlaybackResource(
    private val file: File,
    private val exactByteLength: Long,
) : AndroidPlaybackResource {
    override fun open(position: Long, length: Long): AndroidPlaybackResponse {
        val handle = try {
            RandomAccessFile(file, "r")
        } catch (_: IOException) {
            throw AndroidPlaybackIOException(DomainError.Playback.NoPlayableSource)
        }
        try {
            if (handle.length() != exactByteLength || position < 0 || position >= exactByteLength) {
                throw AndroidPlaybackIOException(DomainError.Protocol.UnexpectedBinary)
            }
            val end = if (length >= 0) minOf(exactByteLength - 1, position + length - 1) else exactByteLength - 1
            val whole = position == 0L && end == exactByteLength - 1
            handle.seek(position)
            val count = end - position + 1
            val input = object : InputStream() {
                private var remaining = count
                override fun read(): Int {
                    if (remaining <= 0) return -1
                    return handle.read().also { if (it >= 0) remaining-- }
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining <= 0) return -1
                    val read = handle.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                    if (read > 0) remaining -= read
                    return read
                }
            }
            val headers = AuthenticatedEndpointResponseHeaders(
                contentType = "application/octet-stream",
                contentLength = PlaybackContentLength.Exact(count),
                retryAfter = null,
                acceptRanges = "bytes",
                contentRange = if (whole) null else "bytes $position-$end/$exactByteLength",
            )
            return AndroidPlaybackResponse(if (whole) 200 else 206, headers, input) { handle.close() }
        } catch (failure: Throwable) {
            handle.close()
            throw failure
        }
    }
}
