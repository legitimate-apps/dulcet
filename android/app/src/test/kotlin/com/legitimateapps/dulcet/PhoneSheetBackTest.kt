package com.legitimateapps.dulcet

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import kotlin.test.assertEquals

/** Back belongs to the covering sheet, at either height, and must not close the player below it. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class PhoneSheetBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backDismissesTheExpandedSheetInsteadOfCollapsingOrClosingThePlayer() = checkBack(expand = true)
    @Test fun backDismissesThePartialSheetWithoutClosingThePlayer() = checkBack(expand = false)

    private fun checkBack(expand: Boolean) {
        var playerBacks = 0
        var dismissals = 0
        compose.setContent { MaterialTheme {
            var open by remember { mutableStateOf(true) }
            BackHandler { playerBacks++ }
            Text("Player", Modifier.testTag("underlying.player"))
            if (open) {
                val sheet = rememberModalBottomSheetState()
                val scope = rememberCoroutineScope()
                PhoneModalBottomSheet(onDismissRequest = { dismissals++; open = false },
                    sheetState = sheet, modifier = Modifier.testTag("covering.sheet")) {
                    Box(Modifier.fillMaxWidth().height(800.dp)) {
                        Button(onClick = { scope.launch { sheet.expand() } },
                            modifier = Modifier.testTag("expand.sheet")) { Text("Expand") }
                    }
                }
                Text(sheet.currentValue.name, Modifier.testTag("sheet.state"))
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("sheet.state").assertTextEquals("PartiallyExpanded")
        if (expand) {
            compose.onNodeWithTag("expand.sheet").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("sheet.state").assertTextEquals("Expanded")
        }
        compose.runOnIdle {
            // This is the dialog window's dispatcher, the destination of the system Back action.
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("covering.sheet").assertDoesNotExist()
        compose.onNodeWithTag("underlying.player").assertExists()
        assertEquals(1, dismissals)
        assertEquals(0, playerBacks)
    }
}
