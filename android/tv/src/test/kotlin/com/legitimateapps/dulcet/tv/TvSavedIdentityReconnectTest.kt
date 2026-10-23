package com.legitimateapps.dulcet.tv

import com.legitimateapps.dulcet.search.conformance.SavedIdentityReconnectScenarios
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television")
class TvSavedIdentityReconnectTest {
    @Test fun originalServerReturnsAndReconnectReopensContactAndFlushesPendingChanges() {
        SavedIdentityReconnectScenarios().use { it.originalServerReturnsAndReconnectReopensContactAndFlushesPendingChanges() }
    }

    @Test fun replacementRemainsAndReconnectKeepsContactRejectedAndChangesPending() {
        SavedIdentityReconnectScenarios().use { it.replacementRemainsAndReconnectKeepsContactRejectedAndChangesPending() }
    }
}
