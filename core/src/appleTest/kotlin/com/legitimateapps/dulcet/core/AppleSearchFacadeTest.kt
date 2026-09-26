package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppleSearchFacadeTest {
    /**
     * Each per-kind count reaches the Objective-C DTO under its own kind.
     *
     * Every count is distinct, so a mapping that crossed any two kinds -- or a result count with a
     * consumed-row count -- reads back a different number here. The Swift caller advances each
     * kind's cursor by the consumed-row count this DTO carries, so a crossed count there would send
     * one kind's offset to another kind. Three booleans cannot all differ, so `hasMore` is checked
     * once per kind with only that kind set: any two crossed kinds then move the one `true` in at
     * least one of the three pages.
     */
    @Test
    fun everyPerKindCountCrossesToTheAppleDtoUnderItsOwnKind() {
        for (kind in 0..2) {
            val page = SearchPage(
                results = emptyList(),
                artistResultCount = 1,
                albumResultCount = 2,
                trackResultCount = 3,
                artistConsumedRowCount = 20,
                albumConsumedRowCount = 15,
                trackConsumedRowCount = 7,
                artistHasMore = kind == 0,
                albumHasMore = kind == 1,
                trackHasMore = kind == 2,
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
                List(3) { it == kind },
                listOf(dto.artistHasMore, dto.albumHasMore, dto.trackHasMore),
                "hasMore with only kind $kind set",
            )
        }
    }
}
