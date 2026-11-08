package com.legitimateapps.dulcet

import kotlin.test.*
import org.junit.Test

class ListenNowShelfPolicyTest {
    @Test fun aSuppressedShelfCannotHideTheNextShelf() {
        val seen = mutableSetOf("a", "b", "c", "d")
        assertFalse(keepListenNowShelf(listOf("a", "b", "c", "x"), seen))
        assertEquals(setOf("a", "b", "c", "d"), seen)
        assertTrue(keepListenNowShelf(listOf("x", "y", "z"), seen))
        assertEquals(setOf("a", "b", "c", "d", "x", "y", "z"), seen)
    }
    @Test fun exactlyHalfDuplicateRemainsVisible() {
        assertTrue(keepListenNowShelf(listOf("a", "b", "c", "d"), mutableSetOf("a", "b")))
    }
    @Test fun fewerThanThreeItemsDoNotMakeAShelf() {
        assertFalse(keepListenNowShelf(listOf("a", "b"), mutableSetOf()))
    }
}
