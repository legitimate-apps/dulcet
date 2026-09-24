package com.legitimateapps.dulcet.conformance

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.BeforeTest

/**
 * The common playback and scrobble conformance suite, inherited unchanged and executed on an
 * Android phone emulator. A distinct class name gives each declared CONF id a test identity that
 * only this device run can produce; the host run of the same methods reports the parent class.
 */
class PlaybackScrobbleAndroidPhoneConformanceTest : PlaybackScrobbleConformanceTest() {
    @BeforeTest fun requirePhone() = requireLeanback(expected = false)
}

/** The same suite on an Android TV emulator, under its own identity. */
class PlaybackScrobbleAndroidTvConformanceTest : PlaybackScrobbleConformanceTest() {
    @BeforeTest fun requireTv() = requireLeanback(expected = true)
}

/** Fails a run on the wrong device class instead of letting it evidence the other platform. */
private fun requireLeanback(expected: Boolean) {
    val leanback = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        .hasSystemFeature("android.software.leanback")
    check(leanback == expected) {
        "This identity evidences ${if (expected) "Android TV" else "an Android phone"}, but the device " +
            "reports leanback=$leanback"
    }
}
