package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A name dialog is for typing a name, so its field has focus the moment it opens. */
@RunWith(RobolectricTestRunner::class)
class PlaylistNameDialogFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theFieldIsFocusedWhenTheDialogOpens() {
        compose.setContent { PlaylistNameDialog(R.string.playlist_name, R.string.playlist_name, "", "dialog.name", requireChange = false, dismiss = {}, done = {}) }
        compose.waitForIdle()
        compose.onNodeWithTag("dialog.name").assertIsFocused()
    }
}
