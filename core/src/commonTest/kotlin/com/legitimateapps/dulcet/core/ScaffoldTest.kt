package com.legitimateapps.dulcet.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ScaffoldTest {
    @Test
    fun coreLinksOnTheCurrentTarget() {
        assertEquals("Dulcet", scaffoldName())
    }
}
