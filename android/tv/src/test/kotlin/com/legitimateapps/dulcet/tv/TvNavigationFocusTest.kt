package com.legitimateapps.dulcet.tv

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Focus the platform drops on the bar goes back to the showing tab; a person's own moves never do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TvNavigationFocusTest {
    private var strays = 0
    private val bar = TvNavigationFocus().apply { currentTag = "listenNow.open"; onStrayFocus = { strays += 1 } }

    @Test fun aBarControlGainingFocusBeforeAnyoneChoseIsSentBack() {
        bar.focusChanged("tv.account.open", true)
        assertEquals(1, strays)
    }

    @Test fun theShowingTabIsNeverStray() {
        bar.focusChanged("listenNow.open", true)
        assertEquals(0, strays)
    }

    @Test fun afterTheShowingTabAMoveAlongTheBarStays() {
        bar.focusChanged("listenNow.open", true)
        bar.focusChanged("listenNow.open", false)
        bar.focusChanged("tv.account.open", true)
        assertEquals(0, strays)
    }

    @Test fun afterAKeyPressTheBarIsThePersons() {
        bar.keyPressed()
        bar.focusChanged("tv.account.open", true)
        assertEquals(0, strays)
    }

    @Test fun afterFocusInTheScreenBelowTheBarIsThePersons() {
        bar.contentFocused = true
        bar.focusChanged("tv.account.open", true)
        assertEquals(0, strays)
    }
}
