package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CatalogEpochTest {
    @Test
    fun theFirstScanSentinelAndAnAbsentStampAreNoEpochWhateverScanningSays() {
        assertNull(CatalogEpoch("0001-01-01T00:00:00Z", setOf("1"), scanning = true).key)
        assertNull(CatalogEpoch("0001-01-01T00:00:00Z", setOf("1"), scanning = false).key)
        assertNull(CatalogEpoch(null, setOf("1"), scanning = false).key)
        val sentinel = ScanStatusReading("0001-01-01T00:00:00Z", false)
        // A window with no stamp, bracketed by two sentinel readings: no epoch, never "guarded".
        assertEquals(PageCheck.NoEpoch, checkPage(null, sentinel, sentinel))
        assertEquals(PageCheck.NoEpoch, checkPage(null, ScanStatusReading(null, false), sentinel))
        // A window that HAD a stamp, now answered with the sentinel: the stamp moved (a wiped DB).
        assertEquals(PageCheck.Fired, checkPage("2026-09-22T10:00:00Z", sentinel, sentinel))
        // A failed reading on either side concludes nothing (review S5).
        assertEquals(PageCheck.Unread, checkPage("2026-09-22T10:00:00Z", null, sentinel))
        assertEquals(PageCheck.Unread, checkPage("2026-09-22T10:00:00Z", sentinel, null))
    }

    @Test
    fun stampsCompareAsRawStringsAndFolderSetsAsSets() {
        // The same instant spelled two ways is a DIFFERENT stamp: a needless revalidation, never a
        // missed change (spec §16.11: never parsed into an instant that "knows" they are equal).
        assertNotEquals(
            CatalogEpoch("2026-09-22T10:00:00.5+02:00", setOf("1"), false).key,
            CatalogEpoch("2026-09-22T08:00:00.500Z", setOf("1"), false).key,
        )
        assertEquals(
            CatalogEpoch("s", setOf("b", "a"), false).key,
            CatalogEpoch("s", setOf("a", "b"), false).key,
        )
        assertNotEquals(CatalogEpoch("s", setOf("a"), false).key, CatalogEpoch("s", setOf("a", "b"), false).key)
    }

    @Test
    fun aPageCheckGuardsOnlyWhenBothReadingsAreIdleWithTheWindowStamp() {
        fun r(stamp: String?, scanning: Boolean = false) = ScanStatusReading(stamp, scanning)
        assertEquals(PageCheck.Guarded, checkPage("s1", r("s1"), r("s1")))
        assertEquals(PageCheck.Scanning, checkPage("s1", r("s1"), r("s1", true)))
        assertEquals(PageCheck.Scanning, checkPage("s1", r("s1"), r("s2", true)))
        assertEquals(PageCheck.Fired, checkPage("s1", r("s1"), r("s2")))
        assertEquals(PageCheck.Fired, checkPage(null, r("s2"), r("s2")))
        // The interleaving an after-only check accepts: the stamp moved from s1 to s2 while the
        // request was out, and the after reading happens to match the window. Only the before
        // reading exposes that the page may have been served under s1.
        assertEquals(PageCheck.Fired, checkPage("s2", r("s1"), r("s2")))
        // A scan that ended during the request: the page may hold pre-scan rows.
        assertEquals(PageCheck.ScanEnded, checkPage("s1", r("s1", true), r("s1")))
    }

    @Test
    fun scanStatusParsing() {
        val body = """{"subsonic-response":{"status":"ok","scanStatus":{"scanning":false,"count":3,"lastScan":"2026-09-22T20:49:36.441652-04:00"}}}"""
        assertEquals(ScanStatusReading("2026-09-22T20:49:36.441652-04:00", false), parseScanStatus(body))
        assertEquals(ScanStatusReading(null, true), parseScanStatus("""{"subsonic-response":{"status":"ok","scanStatus":{"scanning":true}}}"""))
        assertFailsWith<LibraryRequestFailure> {
            parseScanStatus("""{"subsonic-response":{"status":"ok","scanStatus":{"lastScan":"x"}}}""")
        }
    }
}
