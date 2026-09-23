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
        assertEquals(PageCheck.NoEpoch, checkPage("2026-09-22T10:00:00Z", ScanStatusReading("0001-01-01T00:00:00Z", false)))
        assertEquals(PageCheck.NoEpoch, checkPage("2026-09-22T10:00:00Z", null))
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
    fun aPageCheckGuardsOnlyAnUnchangedStampWithNoScanRunning() {
        assertEquals(PageCheck.Guarded, checkPage("s1", ScanStatusReading("s1", false)))
        assertEquals(PageCheck.Scanning, checkPage("s1", ScanStatusReading("s1", true)))
        assertEquals(PageCheck.Scanning, checkPage("s1", ScanStatusReading("s2", true)))
        assertEquals(PageCheck.Fired, checkPage("s1", ScanStatusReading("s2", false)))
        assertEquals(PageCheck.Fired, checkPage(null, ScanStatusReading("s2", false)))
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
