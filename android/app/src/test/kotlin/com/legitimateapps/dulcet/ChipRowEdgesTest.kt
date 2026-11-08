package com.legitimateapps.dulcet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * At the largest font the filter row is wider than the screen, and a chip meets the window edge cut mid-letter ("Dov",
 * "rtist (A–Z)"). The row keeps its scroll peek but fades each edge it can scroll past, so the cut chip trails off
 * instead of ending in a hard edge. Measured as ink: how far a column of pixels over the cut chip strays from the page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class ChipRowEdgesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val page = Color.White

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().let {
        Rect(it.left.value, it.top.value, it.right.value, it.bottom.value)
    }

    private fun host(filter: String) {
        compose.setContent {
            val outer = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(outer.density, 2f)) {
                MaterialTheme(colorScheme = lightColorScheme(background = page, surface = page)) {
                    Box(Modifier.requiredWidth(300.dp).background(page).testTag("page")) {
                        LibraryFilterChips(filter, {}, "albums", Modifier.testTag("chips"))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The most any pixel in the chip's column at [xDp] (root dp) strays from the page, 0 to 765. Drawn in software from the root. */
    private fun ink(xDp: Float, chip: Rect): Int {
        val root = (compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0)
        val image = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { root.draw(android.graphics.Canvas(image)) }
        val scale = compose.activity.resources.displayMetrics.density
        val x = (xDp * scale).roundToInt().coerceIn(0, image.width - 1)
        return ((chip.top * scale).roundToInt() until (chip.bottom * scale).roundToInt()).maxOf { y ->
            val c = image.getPixel(x, y.coerceIn(0, image.height - 1))
            val pr = (page.red * 255).roundToInt(); val pg = (page.green * 255).roundToInt(); val pb = (page.blue * 255).roundToInt()
            abs(android.graphics.Color.red(c) - pr) + abs(android.graphics.Color.green(c) - pg) + abs(android.graphics.Color.blue(c) - pb)
        }
    }

    @Test fun aChipCutAtTheTrailingEdgeFadesOutInsteadOfEndingInAHardCut() {
        host("downloaded")   // the last chip is selected, so it is filled where the edge cuts it
        val row = bounds("chips"); val chip = bounds("library.albums.filter.downloaded")
        assertTrue(chip.right > row.right + 4f && chip.left < row.right - 48f, "the last chip is not cut at the edge: chip $chip, row $row")
        val edge = ink(row.right - 2f, chip); val inside = ink(row.right - 44f, chip)
        assertTrue(inside > 60, "the reference column has no ink: $inside")
        assertTrue(edge * 4 < inside, "the edge column keeps its ink ($edge against $inside inside)")
    }

    @Test fun aChipScrolledPastTheStartFadesOutToo() {
        host("favorites")
        compose.onNodeWithTag("library.albums.filter.downloaded", useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        val row = bounds("chips"); val chip = bounds("library.albums.filter.favorites")
        assertTrue(chip.left < row.left - 4f && chip.right > row.left + 48f, "the first chip is not cut at the start: chip $chip, row $row")
        val edge = ink(row.left + 2f, chip); val inside = ink(row.left + 44f, chip)
        assertTrue(inside > 60, "the reference column has no ink: $inside")
        assertTrue(edge * 4 < inside, "the start column keeps its ink ($edge against $inside inside)")
    }

    @Test fun aRowThatFitsHasNoFade() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = page, surface = page)) {
                Box(Modifier.requiredWidth(411.dp).background(page).testTag("page")) {
                    LibraryFilterChips("favorites", {}, "albums", Modifier.testTag("chips"), showDownloaded = false)
                }
            }
        }
        compose.waitForIdle()
        val chip = bounds("library.albums.filter.favorites")
        assertTrue(ink(chip.left + 2f, chip) > 60, "the only chip lost its ink at its own edge")
    }
}
