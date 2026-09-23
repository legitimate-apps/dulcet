package com.legitimateapps.dulcet.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The catalog epoch of spec §16.11: *(`getScanStatus.lastScan`, the `getMusicFolders` id set)*.
 * It answers one question — could the CATALOG have changed since a cached read? — and never says
 * anything about user state (stars, ratings, plays), which no scan stamp sees.
 *
 * `lastScan` is compared as a raw string for equality only. Its fraction is variable-width and its
 * offset is the server's local one, so it is never ordered and never parsed into an instant that
 * "knows" two spellings are equal (OBSERVED 2026-09-11). Its failure direction is a needless
 * revalidation, never a missed change.
 */
internal data class CatalogEpoch(
    val lastScan: String?,
    val folderIds: Set<String>,
    val scanning: Boolean,
) {
    /**
     * The scan stamp, or null for "no epoch": an absent `lastScan`, or the first-scan sentinel
     * `0001-01-01T00:00:00Z`, recognised by its value alone (OBSERVED 2026-09-22 beside both
     * `scanning` values). A sentinel is never "unchanged" — it is not a stamp at all.
     */
    val stamp: String? get() = lastScan?.takeUnless(::isFirstScanSentinel)

    /**
     * What cached rows and windows are compared against. Two epochs are the same catalog exactly
     * when their stamps are byte-identical and their folder sets are equal as sets. Null when there
     * is no epoch, and nothing is ever catalog-current under a null key.
     */
    val key: String? get() = stamp?.let { catalogEpochKey(it, folderIds) }

    companion object {
        fun fromStored(stored: StoredCatalogEpoch) = CatalogEpoch(stored.lastScan, stored.folderIds, stored.scanning)
    }
}

internal fun catalogEpochKey(stamp: String, folderIds: Set<String>): String =
    JsonArray(listOf(JsonPrimitive(stamp), JsonArray(folderIds.sorted().map(::JsonPrimitive)))).toString()

/** Year 1 is not a date a real scan finished; any spelling of it is the sentinel. */
internal fun isFirstScanSentinel(lastScan: String): Boolean = lastScan.startsWith("0001-01-01T00:00:00")

/**
 * Reads the epoch. [readFull] is the two-request reading of connect, foreground and reconnect;
 * [readScanStatus] is the one request that ends every page read (the *after* of §16.12).
 */
internal class CatalogEpochReader(private val transport: LibraryEndpointTransport) {
    suspend fun readFull(): CatalogEpoch {
        val status = readScanStatus()
        val folders = parseReaderMusicFolderIds(transport.checkedRequest("getMusicFolders"))
        return CatalogEpoch(status.lastScan, folders, status.scanning)
    }

    suspend fun readScanStatus(): ScanStatusReading = parseScanStatus(transport.checkedRequest("getScanStatus"))
}

/**
 * The outcome of comparing a page's *after* reading with its window (spec §16.12). A page joins a
 * guarded window only as [Guarded].
 */
internal enum class PageCheck {
    /** `scanning == false` and the stamp equals the window epoch's. */
    Guarded,

    /** The server was scanning: the page appends, unguarded, and the window says so. */
    Scanning,

    /** The stamp moved: the window is torn and is rebased, never extended. */
    Fired,

    /** No stamp (absent, sentinel, or the reading failed): deduplicated and otherwise unguarded. */
    NoEpoch,
}

internal fun checkPage(windowStamp: String?, after: ScanStatusReading?): PageCheck {
    if (after == null) return PageCheck.NoEpoch
    val afterStamp = after.lastScan?.takeUnless(::isFirstScanSentinel)
    return when {
        after.scanning -> PageCheck.Scanning
        afterStamp == null -> PageCheck.NoEpoch
        windowStamp == null -> PageCheck.Fired
        afterStamp != windowStamp -> PageCheck.Fired
        else -> PageCheck.Guarded
    }
}
