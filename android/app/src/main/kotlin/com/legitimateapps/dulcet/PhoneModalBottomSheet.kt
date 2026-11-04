package com.legitimateapps.dulcet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Material 3's bottom sheet top margin: the highest a modal sheet's top edge may rise. */
private val SheetTopMargin = 72.dp

/**
 * Shared phone sheet. It keeps Material's shape and handle in every state, and never rises above
 * the top margin, so no sheet has square corners or a handle under the status bar. The margin is
 * outer padding: Material measures its anchors inside it, so a short sheet still rests on the
 * bottom edge and a tall one stops at the margin. A sheet opens at its content's height, up to
 * that margin, so a choice list does not open cut off at half height; [sheetState] with a partial
 * state is for a long list that gains from a peek. Content taller than the sheet scrolls itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val statusBar = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    ModalBottomSheet(onDismissRequest = onDismissRequest,
        modifier = Modifier.padding(top = maxOf(SheetTopMargin, statusBar)).then(modifier),
        sheetState = sheetState, containerColor = containerColor,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }) {
        // Material 3 1.4 collapses Expanded to PartiallyExpanded on Back. A phone modal is
        // transient: Back closes it, preserving the player/page underneath. Register inside
        // the sheet so this callback belongs to its dialog window, above the activity's Back.
        BackHandler {
            scope.launch {
                sheetState.hide()
                if (!sheetState.isVisible) onDismissRequest()
            }
        }
        Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 16.dp), content = content)
    }
}
