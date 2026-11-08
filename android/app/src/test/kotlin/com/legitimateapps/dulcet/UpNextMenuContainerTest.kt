package com.legitimateapps.dulcet

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The queue sheet's row menu is the one menu that opens on a sheet, whose tone is one step from a menu's own. */
class UpNextMenuContainerTest {
    private fun step(one: Color, other: Color) = abs(one.red - other.red) * 255

    @Test fun onADarkSheetTheMenuStandsApartFromIt() {
        val scheme = darkColorScheme()
        val step = step(upNextMenuContainer(scheme), scheme.surfaceContainerLow)
        assertTrue(step >= 10f, "The menu is only $step levels from the sheet beneath it")
    }

    @Test fun onALightSheetTheMenuKeepsTheToneEveryOtherMenuHas() {
        val scheme = lightColorScheme()
        assertEquals(scheme.surfaceContainer, upNextMenuContainer(scheme))
    }
}
