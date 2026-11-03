package com.legitimateapps.dulcet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.SleepTimerChoice
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.sleepTimerLabel

/*
 * The listening-session choices (spec §12.13). Sleep timer and Crossfade live in Now Playing's
 * More sheet as rows showing their current value, each opening a radio-choice sheet whose selected
 * value is marked. The Autoplay switch stays with the queue: it is the Autoplay section header of
 * the Up Next sheet. An active timer is also visible on the player itself (the moon countdown chip
 * in PhoneNowPlaying.kt). The service owns every choice's lifetime.
 */

/** The moon that marks an active sleep timer. */
internal val BedtimeIcon: ImageVector = ImageVector.Builder("Bedtime", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M12.34 2.02C6.59 1.82 2 6.42 2 12c0 5.52 4.48 10 10 10 3.71 0 6.93-2.02 " +
        "8.66-5.02-7.51-.25-12.09-8.42-8.32-14.97z"), fill = SolidColor(Color.Black))
    .build()

/** The sleep timer row's value: the countdown while one runs, the choice's name otherwise, "Off" when unset. */
@Composable
internal fun sleepTimerValue(state: AndroidPlaybackState): String {
    val choice = state.sleepTimer ?: return stringResource(SharedR.string.listening_off)
    return if (state.sleepRemainingMilliseconds != null) sleepTimerLabel(choice, state.sleepRemainingMilliseconds)
        else sleepChoiceLabel(choice)
}

/** A sleep choice's name: "Sleep after this song" for the end of the track. */
@Composable
internal fun sleepChoiceLabel(choice: SleepTimerChoice): String = when (choice) {
    SleepTimerChoice.EndOfTrack -> stringResource(R.string.sleep_after_this_song)
    SleepTimerChoice.EndOfQueue -> stringResource(SharedR.string.listening_end_queue)
    else -> stringResource(SharedR.string.listening_minutes, choice.minutes ?: 0)
}

/** A crossfade value's name: "Off" for zero, plural-correct seconds otherwise. */
@Composable
internal fun crossfadeValue(seconds: Int): String =
    if (seconds <= 0) stringResource(SharedR.string.listening_off)
    else pluralStringResource(R.plurals.crossfade_seconds, seconds, seconds)

/** The Sleep timer and Crossfade rows in Now Playing's More sheet; each opens its radio sheet. */
@Composable
internal fun PhoneListeningSession(
    state: AndroidPlaybackState,
    onSleep: () -> Unit,
    onCrossfade: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(SharedR.string.listening_sleep)) },
            supportingContent = { Text(sleepTimerValue(state)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.testTag("listening.sleep").clickable(onClick = onSleep),
        )
        ListItem(
            headlineContent = { Text(stringResource(SharedR.string.listening_crossfade)) },
            supportingContent = { Text(crossfadeValue(state.crossfadeSeconds)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.testTag("listening.crossfade").clickable(onClick = onCrossfade),
        )
    }
}

/**
 * The sleep timer's choices: 15/30/45/60 minutes, "Sleep after this song", End of queue, with the
 * running choice's radio marked, and "Turn off timer" while one is set. A tap applies and dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepTimerSheet(
    state: AndroidPlaybackState,
    onChoice: (SleepTimerChoice?) -> Unit,
    dismiss: () -> Unit,
) {
    PhoneModalBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("listening.sleep.sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(stringResource(SharedR.string.listening_sleep),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SleepTimerChoice.entries.forEach { choice ->
                ChoiceRow(sleepChoiceLabel(choice), state.sleepTimer == choice,
                    "listening.sleep.${choice.wireName}") { onChoice(choice); dismiss() }
            }
            if (state.sleepTimer != null) {
                ChoiceRow(stringResource(R.string.sleep_turn_off), false, "listening.sleep.cancel") {
                    onChoice(null); dismiss()
                }
            }
        }
    }
}

/**
 * Crossfade's presets: Off first, then a few seconds, with the current value's radio marked (and
 * listed, when it is not one of the presets). A tap applies and dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrossfadeSheet(
    state: AndroidPlaybackState,
    onSelect: (Int) -> Unit,
    dismiss: () -> Unit,
) {
    PhoneModalBottomSheet(onDismissRequest = dismiss, modifier = Modifier.testTag("listening.crossfade.sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(stringResource(SharedR.string.listening_crossfade),
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val presets = listOf(0, 2, 4, 6, 8, 12)
            val choices = if (state.crossfadeSeconds in presets) presets else (presets + state.crossfadeSeconds).sorted()
            choices.forEach { seconds ->
                ChoiceRow(crossfadeValue(seconds), state.crossfadeSeconds == seconds,
                    "listening.crossfade.$seconds") { onSelect(seconds); dismiss() }
            }
        }
    }
}

/** One radio row of a listening choice sheet: the selection is the radio's, not the row's fill. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    )
}
