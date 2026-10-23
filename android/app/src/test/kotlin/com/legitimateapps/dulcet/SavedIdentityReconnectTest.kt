package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.search.conformance.SavedIdentityReconnectScenarios
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class SavedIdentityReconnectTest {
    @Test fun originalServerReturnsAndReconnectReopensContactAndFlushesPendingChanges() {
        SavedIdentityReconnectScenarios().use { it.originalServerReturnsAndReconnectReopensContactAndFlushesPendingChanges() }
    }

    @Test fun replacementRemainsAndReconnectKeepsContactRejectedAndChangesPending() {
        SavedIdentityReconnectScenarios().use { it.replacementRemainsAndReconnectKeepsContactRejectedAndChangesPending() }
    }
}
