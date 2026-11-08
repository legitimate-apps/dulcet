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

    /**
     * On a device the dialog's window composes a frame after the dialog is asked for, so a focus request made from the
     * caller's composition finds no field: Compose prints this warning and the dialog opens with no focus and no keyboard
     * (tour logcat, round 17). The request belongs to the dialog's own composition, where the field already exists.
     */
    @Test fun theFocusRequestNeverRunsBeforeTheFieldExists() {
        val printed = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(printed, true))
        try {
            compose.setContent { PlaylistNameDialog(R.string.playlist_name, R.string.playlist_name, "Mix", "dialog.name", requireChange = true, dismiss = {}, done = {}) }
            compose.waitForIdle()
        } finally { System.setOut(original) }
        kotlin.test.assertFalse(printed.toString().contains("FocusRequester is not initialized"), printed.toString())
        compose.onNodeWithTag("dialog.name").assertIsFocused()
    }
}
