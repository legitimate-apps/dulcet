package com.legitimateapps.dulcet.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey

/**
 * Selects a TV text field as the remote does: focus on it, then the centre key. A TV field is
 * read-only until it is selected, so the remote can pass over it without bringing up the keyboard;
 * only a selected field takes typed text.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.selectWithRemote(): SemanticsNodeInteraction = apply {
    performSemanticsAction(SemanticsActions.RequestFocus)
    performKeyInput { pressKey(Key.DirectionCenter) }
}
