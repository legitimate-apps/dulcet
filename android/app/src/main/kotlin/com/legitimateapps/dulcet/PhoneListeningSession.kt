package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.SleepTimerChoice
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.sleepTimerLabel

/** Listening choices beside Up Next; the service owns their lifetime. */
@Composable
internal fun PhoneListeningSession(
    state: AndroidPlaybackState,
    autoplay: (Boolean) -> Unit,
    sleep: (SleepTimerChoice?) -> Unit,
    crossfade: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(SharedR.string.listening_autoplay), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (state.autoplayActive) SharedR.string.listening_autoplay_hint
                    else SharedR.string.listening_autoplay_inactive), style = MaterialTheme.typography.bodySmall)
            }
            Switch(state.autoplayEnabled, autoplay, Modifier.testTag("listening.autoplay"))
        }
        var timerOpen by remember { mutableStateOf(false) }
        var fadeOpen by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                TextButton(onClick = { timerOpen = true }, modifier = Modifier.testTag("listening.sleep")) {
                    Text(sleepTimerLabel(state.sleepTimer, state.sleepRemainingMilliseconds))
                }
                DropdownMenu(timerOpen, { timerOpen = false }) {
                    SleepTimerChoice.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(sleepTimerLabel(choice, null)) },
                            modifier = Modifier.testTag("listening.sleep.${choice.wireName}"),
                            onClick = { timerOpen = false; sleep(choice) })
                    }
                    if (state.sleepTimer != null) DropdownMenuItem(text = { Text(stringResource(SharedR.string.listening_sleep_cancel)) },
                        modifier = Modifier.testTag("listening.sleep.cancel"), onClick = { timerOpen = false; sleep(null) })
                }
            }
            Box {
                TextButton(onClick = { fadeOpen = true }, modifier = Modifier.testTag("listening.crossfade")) {
                    Text(stringResource(SharedR.string.listening_crossfade_value, state.crossfadeSeconds))
                }
                DropdownMenu(fadeOpen, { fadeOpen = false }) {
                    (0..12).forEach { seconds ->
                        DropdownMenuItem(text = { Text(if (seconds == 0) stringResource(SharedR.string.listening_off)
                            else stringResource(SharedR.string.listening_seconds, seconds)) },
                            modifier = Modifier.testTag("listening.crossfade.$seconds"),
                            onClick = { fadeOpen = false; crossfade(seconds) })
                    }
                }
            }
        }
    }
}
