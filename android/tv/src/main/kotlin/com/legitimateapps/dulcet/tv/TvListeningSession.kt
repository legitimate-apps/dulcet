package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.SleepTimerChoice
import com.legitimateapps.dulcet.ui.sleepTimerLabel
import com.legitimateapps.dulcet.shared.R as SharedR

/** Named, focused remote choices replace the phone's dropdowns and switch. */
@Composable
internal fun TvListeningSession(state: AndroidPlaybackState, autoplay: (Boolean) -> Unit,
    sleep: (SleepTimerChoice?) -> Unit, crossfade: (Int) -> Unit) {
    var menu by remember { mutableStateOf<String?>(null) }
    val autoplayFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { autoplayFocus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { autoplay(!state.autoplayEnabled) }, modifier = Modifier.testTag("tv.listening.autoplay").focusRequester(autoplayFocus)) {
            Text(stringResource(if (state.autoplayEnabled) SharedR.string.listening_autoplay_on else SharedR.string.listening_autoplay_off))
        }
        Button(onClick = { menu = "sleep" }, modifier = Modifier.testTag("tv.listening.sleep")) {
            Text(sleepTimerLabel(state.sleepTimer, state.sleepRemainingMilliseconds))
        }
        Button(onClick = { menu = "crossfade" }, modifier = Modifier.testTag("tv.listening.crossfade")) {
            Text(stringResource(SharedR.string.listening_crossfade_value, state.crossfadeSeconds))
        }
    }
    if (state.autoplayEnabled && !state.autoplayActive) Text(stringResource(SharedR.string.listening_autoplay_inactive),
        style = MaterialTheme.typography.bodySmall)
    val shown = menu ?: return
    val choices = if (shown == "sleep") SleepTimerChoice.entries.map { choice ->
        Triple(choice.wireName, sleepTimerLabel(choice, null), { sleep(choice) })
    } + if (state.sleepTimer != null) listOf(Triple("cancel", stringResource(SharedR.string.listening_sleep_cancel), { sleep(null) })) else emptyList()
    else (0..12).map { seconds -> Triple("$seconds", if (seconds == 0) stringResource(SharedR.string.listening_off)
        else stringResource(SharedR.string.listening_seconds, seconds), { crossfade(seconds) }) }
    Dialog(onDismissRequest = { menu = null }) {
        Surface(Modifier.width(480.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(if (shown == "sleep") SharedR.string.listening_sleep else SharedR.string.listening_crossfade),
                    style = MaterialTheme.typography.headlineSmall)
                val first = remember { FocusRequester() }
                LaunchedEffect(shown) { first.requestFocus() }
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(choices, key = { it.first }) { (key, label, action) ->
                        Button(onClick = { menu = null; action() }, modifier = Modifier.fillMaxWidth()
                            .testTag("tv.listening.$shown.$key").let { if (key == choices.first().first) it.focusRequester(first) else it }) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

/** Kept below the cover, separate from the title/queue column's variable height. */
@Composable
internal fun TvListeningSessionButton(state: AndroidPlaybackState, autoplay: (Boolean) -> Unit,
    sleep: (SleepTimerChoice?) -> Unit, crossfade: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Button(onClick = { open = true }, modifier = Modifier.padding(top = 12.dp).testTag("tv.listening.open")) {
        Text(stringResource(SharedR.string.listening_options))
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        Surface(Modifier.width(440.dp)) {
            Column(Modifier.padding(24.dp)) {
                TvListeningSession(state, autoplay, sleep, crossfade)
            }
        }
    }
}
