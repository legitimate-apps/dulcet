package com.legitimateapps.dulcet.emulator

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule

/**
 * Queue effects on Compose's UI test scheduler. The legacy unconfined rule can resume a scroll
 * inside subcomposition measurement or on a worker delivering a result, forcing layout there.
 * Both Home entry points and the regression controls use this same rule.
 */
fun createHomeProofComposeRule(): ComposeTestRule = createEmptyComposeRule()
