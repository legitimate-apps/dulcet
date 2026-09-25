package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppleSearchFacadeTest {
    /**
     * Each per-kind count reaches the Objective-C DTO under its own kind.
     *
     * Every value is distinct, so a mapping that crossed any two kinds -- or a result count with a
     * consumed-row count -- reads back a different number here. The Swift caller advances each
     * kind's cursor by the consumed-row count this DTO carries, so a crossed count there would send
     * one kind's offset to another kind.
     */
    @Test
    fun everyPerKindCountCrossesToTheAppleDtoUnderItsOwnKind() {
        val page = SearchPage(
            results = emptyList(),
            artistResultCount = 1,
            albumResultCount = 2,
            trackResultCount = 3,
            artistConsumedRowCount = 20,
            albumConsumedRowCount = 15,
            trackConsumedRowCount = 7,
            artistHasMore = true,
            albumHasMore = false,
            trackHasMore = true,
        )

        val outcome = SearchPageResult.Loaded(page).toAppleOutcome()

        assertNull(outcome.error)
        val dto = assertNotNull(outcome.page)
        assertEquals(
            listOf(1, 2, 3),
            listOf(dto.artistResultCount, dto.albumResultCount, dto.trackResultCount),
            "result counts",
        )
        assertEquals(
            listOf(20, 15, 7),
            listOf(dto.artistConsumedRowCount, dto.albumConsumedRowCount, dto.trackConsumedRowCount),
            "consumed-row counts",
        )
        assertEquals(
            listOf(true, false, true),
            listOf(dto.artistHasMore, dto.albumHasMore, dto.trackHasMore),
            "hasMore",
        )
    }
}
