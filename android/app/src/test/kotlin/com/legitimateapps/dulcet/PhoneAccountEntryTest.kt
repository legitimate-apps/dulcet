package com.legitimateapps.dulcet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

/**
 * The phone app, launched signed in, carries the account entry in its navigation bar, and the entry
 * reaches Sign out (spec §14.7). SignOutSheetTest renders the entry alone; this proves the shell
 * shows it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = AndroidSearchTestApplication::class)
class PhoneAccountEntryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun theSignedInPhoneAppOffersTheAccountAndItsSignOut() {
        compose.onNodeWithTag("search.open").assertIsDisplayed()
        compose.onNodeWithTag("account.open").performClick()
        compose.onNodeWithText("Signed in to music.example.invalid as credential-user-canary.").assertIsDisplayed()
        compose.onNodeWithTag("account.signout").assertIsDisplayed()
    }
}
