package com.legitimateapps.dulcet.emulator

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule

/** Shared by both Home entry points and the layout-thread regression control. */
fun createHomeProofComposeRule(): ComposeTestRule = createEmptyComposeRule()
