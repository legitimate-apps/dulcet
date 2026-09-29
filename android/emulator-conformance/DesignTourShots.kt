package com.legitimateapps.dulcet.emulator

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * The camera for `tools/design-tour-android`: a screen tour for design review, not a proof. Each
 * shot is the whole display as the person sees it, scaled to about 1000 px on its long edge and
 * written as a JPEG to the app's external files directory, `design-tour/<label>-<nn>-<screen>.jpg`,
 * from where the tool pulls it. A screen the tour could not reach is still photographed, named
 * `<nn>-<screen>-UNREACHED`, so a shot is never of a spinner in place of the screen it names.
 *
 * Runs only when the runner passes `dulcetDesignTour=true`: an ordinary instrumented run of the
 * target returns from the tour at once and photographs nothing.
 */
class DesignTourShots(private val label: String) {
    private val directory = File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "design-tour",
    ).also { it.mkdirs() }
    private var number = 0
    val unreached = mutableListOf<String>()

    init {
        directory.listFiles { file -> file.name.startsWith("$label-") }?.forEach(File::delete)
    }

    /** Reaches one screen, then photographs it whether or not it was reached. */
    fun step(screen: String, reach: () -> Boolean) {
        val reached = runCatching(reach).onFailure { println("DESIGN TOUR $screen: ${it.message}") }.getOrDefault(false)
        if (!reached) unreached += screen
        shoot(if (reached) screen else "$screen-UNREACHED")
        println("DESIGN TOUR screen=$screen reached=$reached")
    }

    fun shoot(screen: String) {
        number += 1
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("The display could not be captured")
        val scale = LONG_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        File(directory, "%s-%02d-%s.jpg".format(label, number, screen)).outputStream().use {
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
        }
    }

    /** Lets artwork and animations land before a shot; asserts nothing. */
    fun settle(millis: Long = 1_200): Boolean {
        Thread.sleep(millis)
        return true
    }

    companion object {
        private const val LONG_EDGE = 1000
        private const val QUALITY = 72

        fun requested(): Boolean = InstrumentationRegistry.getArguments().getString("dulcetDesignTour") == "true"
    }
}
