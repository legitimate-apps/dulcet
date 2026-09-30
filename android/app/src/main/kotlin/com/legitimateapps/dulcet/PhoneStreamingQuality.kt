package com.legitimateapps.dulcet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.legitimateapps.dulcet.core.StreamingQuality
import com.legitimateapps.dulcet.playback.StreamingQualitySettings
import com.legitimateapps.dulcet.playback.label
import com.legitimateapps.dulcet.shared.R
import com.legitimateapps.dulcet.ui.DulcetIcons

/**
 * The phone's streaming-quality choice (spec §12.5), in the account dialog: one value for Wi-Fi,
 * one for cellular. A choice is saved at once and applies from the next song; nothing playing
 * restarts for it.
 */
@Composable
internal fun StreamingQualitySection(settings: StreamingQualitySettings = StreamingQualitySettings.get(LocalContext.current)) {
    val preference by settings.preference.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().testTag("quality.section"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.streaming_quality_title), style = MaterialTheme.typography.titleMedium)
        QualityChoice(stringResource(R.string.streaming_quality_unmetered), "quality.unmetered", preference.unmetered) {
            settings.set(preference.copy(unmetered = it))
        }
        QualityChoice(stringResource(R.string.streaming_quality_metered), "quality.metered", preference.metered) {
            settings.set(preference.copy(metered = it))
        }
        Text(stringResource(R.string.streaming_quality_metered_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.streaming_quality_body), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun QualityChoice(title: String, tag: String, selected: StreamingQuality, choose: (StreamingQuality) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
                Text(selected.label(context))
                Icon(DulcetIcons.ExpandMore, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                StreamingQuality.entries.forEach { quality ->
                    DropdownMenuItem(
                        text = { Text(quality.label(context)) },
                        onClick = { open = false; choose(quality) },
                        leadingIcon = if (quality == selected) { { Icon(DulcetIcons.Check, null) } } else null,
                        modifier = Modifier.testTag("$tag.${quality.wireName}"),
                    )
                }
            }
        }
    }
}
