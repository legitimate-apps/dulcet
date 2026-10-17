package com.legitimateapps.dulcet.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.legitimateapps.dulcet.core.SleepTimerChoice
import com.legitimateapps.dulcet.shared.R

@Composable
public fun sleepTimerLabel(choice: SleepTimerChoice?, remainingMilliseconds: Long?): String = when {
    choice == null -> stringResource(R.string.listening_sleep)
    remainingMilliseconds != null -> {
        val seconds = (remainingMilliseconds + 999) / 1000
        stringResource(R.string.listening_sleep_remaining, "%d:%02d".format(seconds / 60, seconds % 60))
    }
    choice.minutes != null -> stringResource(R.string.listening_minutes, choice.minutes!!)
    choice == SleepTimerChoice.EndOfTrack -> stringResource(R.string.listening_end_track)
    else -> stringResource(R.string.listening_end_queue)
}
