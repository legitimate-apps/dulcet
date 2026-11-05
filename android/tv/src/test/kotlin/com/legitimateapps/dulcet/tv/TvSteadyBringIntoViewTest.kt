package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a focused element comes to rest: a page that fits on the screen does not move at all. */
@OptIn(ExperimentalFoundationApi::class)
class TvSteadyBringIntoViewTest {
    /** The platform's TV default: every element 30% down its container. */
    private val pivot = object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - containerSize * 0.3f
    }
    private val spec = TvSteadyBringIntoView(pivot, bottomRoomPx = 144f)

    @Test fun anElementInViewMovesNothingWhereThePlatformWouldLiftThePage() {
        assertEquals(0f, spec.calculateScrollDistance(580f, 80f, 880f))
        assertEquals(316f, pivot.calculateScrollDistance(580f, 80f, 880f), "control: the default moves this page")
    }

    @Test fun anElementLeavingTheBottomStopsARowShortOfTheEdge() {
        assertEquals(144f, spec.calculateScrollDistance(800f, 80f, 880f))
    }

    @Test fun anElementGoingUpKeepsTheRoomAboveItSoTheHeaderComesBack() {
        assertEquals(-40f - 264f, spec.calculateScrollDistance(-40f, 80f, 880f))
    }

    @Test fun anElementTooTallForTheRoomTakesTheDefault() {
        assertEquals(pivot.calculateScrollDistance(100f, 600f, 880f), spec.calculateScrollDistance(100f, 600f, 880f))
    }

    /** A menu: the edge is covered by a 112 px fade, and the next row is 50 px below this one. */
    private val menu = TvSteadyBringIntoView(pivot, bottomRoomPx = 112f, topCoverPx = 112f, nextRowGapPx = 50f)

    @Test fun aMenuRowStopsWithTheNextRowClearOfTheFade() {
        // Container 960: a 96 px row at 700 ends at 796; the next row would end at 942, under the fade from 848.
        assertEquals(942f - 848f, menu.calculateScrollDistance(700f, 96f, 960f))
    }

    @Test fun aMenuRowWhoseNextRowIsAlreadyClearStaysPut() {
        assertEquals(0f, menu.calculateScrollDistance(400f, 96f, 960f))
    }

    @Test fun aRowGoingUpUnderTheMenuFadeComesBackOut() {
        assertEquals(50f - 288f, menu.calculateScrollDistance(50f, 96f, 960f))
    }

    @Test fun aPageWithNoCoverLeavesARowAtTheVeryTopAlone() {
        assertEquals(0f, spec.calculateScrollDistance(0f, 80f, 880f))
    }
}
