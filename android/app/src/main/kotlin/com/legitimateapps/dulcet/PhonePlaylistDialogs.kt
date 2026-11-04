package com.legitimateapps.dulcet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.unit.dp

/* Dialogs and small shared pieces for the phone's playlist pages (A18/A20) and their icons. */

/** A playlist's name, asked in a dialog: confirm stays disabled while the name is blank — and, on a
 * rename ([requireChange]), while it is unchanged. The keyboard opens with the dialog, the field's
 * action submits, and an [initial] name comes pre-selected so typing replaces it. */
@Composable
internal fun PlaylistNameDialog(
    title: Int,
    confirm: Int,
    initial: String,
    tag: String,
    requireChange: Boolean,
    dismiss: () -> Unit,
    done: (String) -> Unit,
) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val valid = name.text.isNotBlank() && (!requireChange || name.text.trim() != initial.trim())
    fun submit() { if (valid) done(name.text.trim()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A stable full-window dialog root avoids a focused field repeatedly resizing the window.
    Dialog(onDismissRequest = dismiss, // decorFitsSystemWindows off is what hands the keyboard inset to imePadding here.
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.playlist_name)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().testTag(tag).focusRequester(focusRequester))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dismiss, modifier = Modifier.testTag("$tag.cancel")) { Text(stringResource(R.string.playlist_cancel)) }
                        TextButton(onClick = ::submit, enabled = valid, modifier = Modifier.testTag("$tag.confirm")) { Text(stringResource(confirm)) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

/** Deleting a playlist asks once, naming it; the destructive action is the only error-coloured one. */
@Composable
internal fun PlaylistDeleteDialog(name: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.playlist_delete_title)) },
        text = { Text(stringResource(R.string.playlist_delete_body, name)) },
        confirmButton = {
            TextButton(onClick = confirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("playlist.delete.confirm")) {
                Text(stringResource(R.string.playlist_delete_action))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.testTag("playlist.delete.cancel")) {
                Text(stringResource(R.string.playlist_cancel))
            }
        },
    )
}

/**
 * Playlist sort choices use the same radio sheet as the Library collections. The account stores
 * the selected order.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistSortSheet(current: String, sorts: List<Pair<String, String>>, tag: String, choose: (String) -> Unit, dismiss: () -> Unit) {
    LibrarySortSheet(sorts.map { (key, title) -> LibrarySortChoice(key, title) }, current, choose, "$tag.sort", dismiss)
}

/* The set DulcetIcons does not carry, built the same way it is. */
internal val DulcetClose: ImageVector = ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"),
        fill = SolidColor(Color.Black))
    .build()

internal val DulcetRemoveCircleOutline: ImageVector = ImageVector.Builder("RemoveCircleOutline", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M7 11v2h10v-2H7zm5-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"),
        fill = SolidColor(Color.Black))
    .build()

internal val DulcetCloudOff: ImageVector = ImageVector.Builder("CloudOff", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M19.35 10.04C18.67 6.59 15.64 4 12 4c-1.48 0-2.85.43-4.01 1.17l1.46 1.46C10.21 6.23 11.08 6 12 6c3.04 0 5.5 2.46 5.5 5.5v.5H19c1.66 0 3 1.34 3 3 0 1.13-.64 2.11-1.56 2.62l1.45 1.45C23.16 17.81 24 16.01 24 14.5c0-2.64-2.05-4.78-4.65-4.96zM3 5.27l2.75 2.74C2.56 8.15 0 10.77 0 14c0 3.31 2.69 6 6 6h11.73l2 2L21 20.73 4.27 4 3 5.27zM7.73 10l8 8H6c-2.21 0-4-1.79-4-4s1.79-4 4-4h1.73z"),
        fill = SolidColor(Color.Black))
    .build()

internal val DulcetSync: ImageVector = ImageVector.Builder("Sync", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"),
        fill = SolidColor(Color.Black))
    .build()

internal val DulcetErrorOutline: ImageVector = ImageVector.Builder("ErrorOutline", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M11 15h2v2h-2v-2zm0-8h2v6h-2V7zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"),
        fill = SolidColor(Color.Black))
    .build()

internal val DulcetDragHandle: ImageVector = ImageVector.Builder("DragHandle", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M20 9H4v2h16V9zM4 15h16v-2H4v-2z"), fill = SolidColor(Color.Black))
    .build()
