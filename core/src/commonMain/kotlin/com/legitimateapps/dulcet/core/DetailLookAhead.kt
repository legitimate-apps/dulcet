package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

/**
 * Detail look-ahead (spec §16.13, CONF-87), so the first tap on an album opens instantly.
 *
 * When an album grid has been still for [LibraryReaderConfig.lookAheadSettleMillis], the reader
 * fetches `getAlbum` for the albums in the viewport and the next viewport-height beyond it,
 * most-central first, subject to all of:
 *
 * - at most [LibraryReaderConfig.lookAheadMaxPerViewport] per settled viewport and
 *   [LibraryReaderConfig.lookAheadInFlight] in flight, inside the reader's per-server bound;
 * - skipped for an album already `detail_complete` under the current epoch;
 * - online only, and never on a constrained network;
 * - cancelled as soon as its album leaves the look-ahead region.
 *
 * It is bounded by COUNT, not by scrolling speed: a fling that never settles fetches nothing,
 * because every viewport change cancels the pending settle before it fires.
 */
internal class DetailLookAhead(private val reader: LibraryReader) {
    private val permits = Semaphore(reader.config.lookAheadInFlight)
    private var settle: Job? = null
    private var owner: ReaderHandle? = null
    private var region: Set<String> = emptySet()
    private val running = mutableMapOf<String, Job>()

    fun viewportChanged(window: ReaderHandle, items: List<LibraryItem>, firstIndex: Int, lastIndex: Int) {
        settle?.cancel()
        settle = null
        owner = window
        if (items.isEmpty()) {
            leaveRegion(emptySet())
            return
        }
        val height = lastIndex - firstIndex + 1
        val end = minOf(items.lastIndex, lastIndex + height)
        val center = (firstIndex + lastIndex) / 2.0
        val ordered = (firstIndex..end)
            .filter { items[it] is LibraryItem.Album }
            .sortedWith(compareBy({ abs(it - center) }, { it }))
            .map { items[it].rawId }
        leaveRegion(ordered.toSet())
        if (!reader.online || reader.networkConstrained) return
        settle = reader.scope.launch {
            delay(reader.config.lookAheadSettleMillis)
            if (!reader.online || reader.networkConstrained) return@launch
            val epochKey = reader.sessionEpoch?.key
            ordered
                .filter { it !in running && needsDetail(it, epochKey) }
                .take(reader.config.lookAheadMaxPerViewport)
                .forEach(::fetch)
        }
    }

    private fun needsDetail(albumRawId: String, epochKey: String?): Boolean {
        val album = reader.cache.album(albumRawId) ?: return true
        if (album.row.gone) return false
        return !(album.detailComplete && epochKey != null && album.row.fetchedEpoch == epochKey)
    }

    private fun fetch(albumRawId: String) {
        val job = reader.scope.launch {
            permits.withPermit {
                if (albumRawId in region) reader.readAlbumDetail(albumRawId)
            }
        }
        running[albumRawId] = job
        job.invokeOnCompletion { if (running[albumRawId] === job) running.remove(albumRawId) }
    }

    /** Cancels every look-ahead read whose album is no longer in [next]. */
    private fun leaveRegion(next: Set<String>) {
        region = next
        running.filterKeys { it !in next }.values.toList().forEach(Job::cancel)
    }

    /** An album screen is about to read this album itself. */
    fun cancel(albumRawId: String) {
        running.remove(albumRawId)?.cancel()
    }

    fun cancelAll() {
        settle?.cancel()
        settle = null
        leaveRegion(emptySet())
    }

    fun windowClosed(window: ReaderHandle) {
        if (owner === window) {
            owner = null
            cancelAll()
        }
    }
}
