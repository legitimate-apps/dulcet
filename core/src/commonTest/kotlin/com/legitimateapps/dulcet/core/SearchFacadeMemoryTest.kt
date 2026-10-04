package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** The facade's own failures answer the text in the field (§16.15). */
class SearchFacadeMemoryTest {
    @Test
    fun aFailureBeforeAnythingWasTypedOrShownNamesNoQuery() {
        val memory = SearchFacadeMemory<String>()
        assertEquals("", memory.failureQuery)
        assertEquals(emptyList(), memory.failureRows)
    }

    @Test
    fun aQueryTypedWithNothingShownIsTheOneAFailureNames() {
        val memory = SearchFacadeMemory<String>()
        memory.type("abc")
        assertEquals("abc", memory.failureQuery, "a failure for a query typed with no session must name it, or the shell discards it")
        assertEquals(emptyList(), memory.failureRows)
    }

    @Test
    fun theRowsShownStayUnderAFailureForTheirOwnQuery() {
        val memory = SearchFacadeMemory<String>()
        memory.type("echo")
        memory.shown("echo", listOf("row-1", "row-2"))
        assertEquals("echo", memory.failureQuery)
        assertEquals(listOf("row-1", "row-2"), memory.failureRows)
    }

    @Test
    fun aNewerKeystrokeDropsAnOlderQuerysRowsFromAFailure() {
        val memory = SearchFacadeMemory<String>()
        memory.type("ec")
        memory.shown("ec", listOf("row-1"))
        memory.type("echo")
        assertEquals("echo", memory.failureQuery)
        assertEquals(emptyList(), memory.failureRows, "the older query's rows drawn under the newer text")
    }

    @Test
    fun withNothingTypedAFailureNamesWhatWasLastShown() {
        val memory = SearchFacadeMemory<String>()
        memory.shown("echo", listOf("row-1"))
        assertEquals("echo", memory.failureQuery)
        assertEquals(listOf("row-1"), memory.failureRows)
    }
}
