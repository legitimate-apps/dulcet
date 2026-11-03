package com.legitimateapps.dulcet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Shared phone sheet, retaining Material's layout, anchors and appearance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier,
        sheetState = sheetState, containerColor = containerColor) {
        // Material 3 1.4 collapses Expanded to PartiallyExpanded on Back. A phone modal is
        // transient: Back closes it, preserving the player/page underneath. Register inside
        // the sheet so this callback belongs to its dialog window, above the activity's Back.
        BackHandler {
            scope.launch {
                sheetState.hide()
                if (!sheetState.isVisible) onDismissRequest()
            }
        }
        content()
    }
}
