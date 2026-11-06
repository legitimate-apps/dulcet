package com.legitimateapps.dulcet.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.RadioButtonDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.SleepTimerChoice
import com.legitimateapps.dulcet.shared.R as SharedR
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.MAX_RATING
import com.legitimateapps.dulcet.ui.adjustableRating
import com.legitimateapps.dulcet.ui.ratingAlpha
import com.legitimateapps.dulcet.ui.ratingForTap
import com.legitimateapps.dulcet.ui.ratingStarAction
import com.legitimateapps.dulcet.ui.ratingState

/*
 * Listening options on a remote (A42): a titled right-edge side panel of ListItems reached from the
 * transport's More button, not pills under the cover. Autoplay is a switch row; Sleep timer and
 * Crossfade name their value as supporting text (accent while active, "Off" for zero) and open a
 * choice list whose radio shows the selection apart from focus, whose first row turns the setting
 * off, and which opens with focus on the CURRENT value. Back cancels: the dialog's own dismiss, and
 * focus returns to the row or button that opened it.
 */

/**
 * One row of a listening choice list: [label] beside a radio lit when [selected]; [tag] keeps the
 * section's existing tags (`tv.listening.sleep.15m`, `tv.listening.crossfade.3`, …); [action] runs
 * on selection, after the list closes.
 */
internal class TvListeningChoice(val tag: String, val label: String, val selected: Boolean, val action: () -> Unit)

/** The More (⋮) button in the player's secondary row; opens the listening options side panel. */
@Composable
internal fun TvListeningSessionButton(state: AndroidPlaybackState, autoplay: (Boolean) -> Unit,
    sleep: (SleepTimerChoice?) -> Unit, crossfade: (Int) -> Unit, rating: TvPlayerRating? = null) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("tv.listening.open")) {
        Icon(DulcetIcons.MoreVert, stringResource(SharedR.string.listening_options))
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)), contentAlignment = Alignment.CenterEnd) {
            Surface(Modifier.width(440.dp).fillMaxHeight(),
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 32.dp)) {
                    Text(stringResource(SharedR.string.listening_options), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    TvListeningSession(state, autoplay, sleep, crossfade, rating)
                }
            }
        }
    }
}

/** The panel's rows: the rating, then Autoplay with its switch, Sleep timer and Crossfade with their values. */
@Composable
internal fun TvListeningSession(state: AndroidPlaybackState, autoplay: (Boolean) -> Unit,
    sleep: (SleepTimerChoice?) -> Unit, crossfade: (Int) -> Unit, rating: TvPlayerRating? = null) {
    var menu by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val firstFocus = rememberFirstFocus()
    LaunchedEffect(Unit) { firstFocus.give(first) }
    // tv-material buttons can keep an onClick from an earlier composition: read the modes live.
    val current by rememberUpdatedState(state)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The song's own rating leads, as on the phone; the panel still opens on the session's first row below it.
        if (rating != null) {
            Text(stringResource(SharedR.string.library_rating), Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.titleMedium)
            TvRatingStars(rating)
            Spacer(Modifier.height(12.dp))
        }
        ListItem(
            scale = TvRowScale,
            selected = false,
            onClick = { autoplay(!current.autoplayEnabled) },
            headlineContent = { Text(stringResource(R.string.tv_autoplay)) },
            supportingContent = {
                Text(stringResource(if (state.autoplayEnabled && !state.autoplayActive) SharedR.string.listening_autoplay_inactive
                    else SharedR.string.listening_autoplay_hint), style = MaterialTheme.typography.bodyMedium, color = tvRowLineColor())
            },
            trailingContent = { Switch(checked = state.autoplayEnabled, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().testTag("tv.listening.autoplay").focusRequester(first)
                .semantics { toggleableState = ToggleableState(state.autoplayEnabled) },
        )
        ListItem(
            scale = TvRowScale,
            selected = false,
            onClick = { menu = "sleep" },
            headlineContent = { Text(stringResource(SharedR.string.listening_sleep)) },
            supportingContent = { Text(sleepValue(state), style = MaterialTheme.typography.bodyMedium, color = tvRowLineColor()) },
            modifier = Modifier.fillMaxWidth().testTag("tv.listening.sleep"),
        )
        ListItem(
            scale = TvRowScale,
            selected = false,
            onClick = { menu = "crossfade" },
            headlineContent = { Text(stringResource(SharedR.string.listening_crossfade)) },
            supportingContent = { Text(crossfadeValue(state), style = MaterialTheme.typography.bodyMedium, color = tvRowLineColor()) },
            modifier = Modifier.fillMaxWidth().testTag("tv.listening.crossfade"),
        )
    }
    when (menu) {
        "sleep" -> TvListeningChoices(stringResource(SharedR.string.listening_sleep), sleepChoices(state, sleep)) { menu = null }
        "crossfade" -> TvListeningChoices(stringResource(SharedR.string.listening_crossfade), crossfadeChoices(state, crossfade)) { menu = null }
    }
}

/** The Off/turn-off row first, then 15/30/45/60 minutes and the two end-of-listening choices. */
@Composable
private fun sleepChoices(state: AndroidPlaybackState, sleep: (SleepTimerChoice?) -> Unit): List<TvListeningChoice> = buildList {
    add(TvListeningChoice("tv.listening.sleep.cancel",
        stringResource(if (state.sleepTimer != null) R.string.tv_sleep_turn_off else SharedR.string.listening_off),
        selected = state.sleepTimer == null) { sleep(null) })
    SleepTimerChoice.entries.forEach { choice ->
        val label = when {
            choice.minutes != null -> pluralStringResource(R.plurals.tv_sleep_minutes_full, choice.minutes!!, choice.minutes!!)
            choice == SleepTimerChoice.EndOfTrack -> stringResource(R.string.tv_sleep_after_song)
            else -> stringResource(R.string.tv_sleep_after_queue)
        }
        add(TvListeningChoice("tv.listening.sleep.${choice.wireName}", label, selected = state.sleepTimer == choice) { sleep(choice) })
    }
}

/** Off plus a handful of presets; a stored value outside them is listed too, so it stays visible. */
@Composable
private fun crossfadeChoices(state: AndroidPlaybackState, crossfade: (Int) -> Unit): List<TvListeningChoice> {
    val presets = listOf(0, 2, 3, 4, 5, 6, 8, 12)
    val values = if (state.crossfadeSeconds in presets) presets else (presets + state.crossfadeSeconds).sorted()
    return values.map { seconds ->
        TvListeningChoice("tv.listening.crossfade.$seconds",
            if (seconds == 0) stringResource(SharedR.string.listening_off)
            else pluralStringResource(R.plurals.tv_crossfade_seconds, seconds, seconds),
            selected = state.crossfadeSeconds == seconds) { crossfade(seconds) }
    }
}

/** The sleep row's value: "Off", the countdown while one runs, or the wait it was set for. */
@Composable
private fun sleepValue(state: AndroidPlaybackState): String {
    val timer = state.sleepTimer ?: return stringResource(SharedR.string.listening_off)
    val minutes = timer.minutes
    return when {
        minutes != null -> state.sleepRemainingMilliseconds?.let {
            // Rounded up, as the player's own countdown is, so the two never read a second apart.
            stringResource(SharedR.string.listening_sleep_remaining, clock(it + 999))
        } ?: stringResource(R.string.tv_sleep_value_minutes, minutes)
        timer == SleepTimerChoice.EndOfTrack -> stringResource(R.string.tv_sleep_after_song)
        else -> stringResource(R.string.tv_sleep_after_queue)
    }
}

/** The crossfade row's value: "Off", or the overlap in whole seconds, plural-correct. */
@Composable
private fun crossfadeValue(state: AndroidPlaybackState): String =
    if (state.crossfadeSeconds <= 0) stringResource(SharedR.string.listening_off)
    else pluralStringResource(R.plurals.tv_crossfade_seconds, state.crossfadeSeconds, state.crossfadeSeconds)

/**
 * The choice list itself: a [TvDialogShell], one radio-lit ListItem per option,
 * focus on the current value (the first row when none is), and selection distinct from focus.
 * Choosing closes it; Back cancels it.
 */
@Composable
private fun TvListeningChoices(title: String, options: List<TvListeningChoice>, onDismiss: () -> Unit) {
    TvDialogShell("listening.choices", onDismiss, width = 480) {
        Column(Modifier.padding(horizontal = 32.dp, vertical = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            val focus = remember { FocusRequester() }
            val focusTag = options.firstOrNull { it.selected }?.tag ?: options.firstOrNull()?.tag
            // Open scrolled to the current value: a lazy row past the first screenful is not
            // composed, and a focus request on it would land nowhere.
            val list = rememberLazyListState(initialFirstVisibleItemIndex = options.indexOfFirst { it.tag == focusTag }.coerceAtLeast(0))
            val firstFocus = rememberFirstFocus()
            LaunchedEffect(focusTag) { if (focusTag != null) firstFocus.give(focus) }
            LazyColumn(Modifier.heightIn(max = 400.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(options, key = { it.tag }) { option ->
                    ListItem(
                        scale = TvRowScale,
                        selected = false,
                        onClick = { onDismiss(); option.action() },
                        headlineContent = { Text(option.label) },
                        trailingContent = { RadioButton(selected = option.selected, onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = LocalContentColor.current,
                                unselectedColor = LocalContentColor.current)) },
                        modifier = Modifier.fillMaxWidth().testTag(option.tag)
                            .let { if (option.tag == focusTag) it.focusRequester(focus) else it }
                            .semantics { selected = option.selected },
                    )
                }
            }
        }
    }
}

/**
 * The playing track's stars, inside the listening options panel (ratings live behind More on TV):
 * each star its own focus target, the centre key on a star sets that rating — on the star already
 * shown, removes it ([ratingForTap]). Each says what pressing it does and is selected up to the
 * rating. For accessibility services the row is also one adjustable control ([adjustableRating]).
 */
@Composable
internal fun TvRatingStars(rating: TvPlayerRating) {
    val latest by rememberUpdatedState(rating)
    val resources = LocalContext.current.resources
    // Null is unknown: no star selected, dimmed, said "Rating unknown", and no relative adjust (adjustableRating).
    val shown = rating.rating?.coerceIn(0, MAX_RATING)
    val filled = shown ?: 0
    val label = stringResource(SharedR.string.library_rating)
    Row(Modifier.padding(top = 4.dp).alpha(ratingAlpha(shown))
        .testTag("tv.player.rating").semantics {
        adjustableRating(label, resources.ratingState(shown), shown, resources) { latest.rate(it) }
    }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (star in 1..MAX_RATING) {
            IconButton(onClick = { latest.rate(ratingForTap(latest.rating, star)) },
                modifier = Modifier.size(40.dp).testTag("tv.player.rating.$star").semantics { selected = star <= filled }) {
                Icon(if (star <= filled) DulcetIcons.Star else DulcetIcons.StarBorder,
                    resources.ratingStarAction(shown, star), Modifier.size(22.dp))
            }
        }
    }
}
