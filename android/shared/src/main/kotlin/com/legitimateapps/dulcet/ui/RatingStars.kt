package com.legitimateapps.dulcet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.shared.R
import kotlin.math.roundToInt

/** The highest rating; `0` is unrated (`setRating` with 0 removes a rating). */
public const val MAX_RATING: Int = 5

/**
 * What a tap on star [star] (1–5) sets, given the rating shown: that rating, or `0` — no rating —
 * when it is the rating already shown, so the stars alone can take a rating away.
 */
public fun ratingForTap(shown: Int?, star: Int): Int = if (star == shown) 0 else star.coerceIn(1, MAX_RATING)

/**
 * How opaque the stars are drawn: dimmed while the rating is unknown (null), so an unknown rating
 * and a known 0 look different as well as sound different — both fill no star (§16.20).
 */
public fun ratingAlpha(rating: Int?): Float = if (rating == null) UNKNOWN_RATING_ALPHA else 1f

/** The opacity of stars whose rating this device does not know. */
public const val UNKNOWN_RATING_ALPHA: Float = 0.45f

/** The rating an accessibility service's `setProgress` asks for, as a whole number of stars. */
public fun ratingForProgress(value: Float): Int = value.roundToInt().coerceIn(0, MAX_RATING)

/**
 * The words a screen reader says for the rating shown: `Rating unknown` while this device does not
 * know it (null), `Not rated` for a known 0, or `3 of 5 stars`.
 */
public fun android.content.res.Resources.ratingState(rating: Int?): String = when {
    rating == null -> getString(R.string.library_rating_unknown)
    rating <= 0 -> getString(R.string.library_rating_none)
    else -> getString(R.string.library_rating_value, rating)
}

/** What pressing star [star] does, given the rating shown (null unknown): rates it, or removes the rating. */
public fun android.content.res.Resources.ratingStarAction(shown: Int?, star: Int): String =
    if (star == shown) getQuantityString(R.plurals.library_rating_clear, star, star)
    else getString(R.string.library_rating_set, star)

/**
 * The stars as ONE adjustable control for a screen reader: named `Rating`, its state the rating's
 * words, its range 0–5 in whole stars, and `setProgress` sets it — so TalkBack's adjust gestures and
 * volume keys change it one star at a time.
 *
 * While the rating is unknown (null) there is no value to step from: one step up from an unknown
 * rating would send `1` over whatever the server holds. So an unknown rating has no range and no
 * `setProgress`, only absolute actions — `Rate 1 of 5 stars` … `Rate 5 of 5 stars` — each of which
 * sets exactly what it says. [resources] words them.
 */
public fun SemanticsPropertyReceiver.adjustableRating(
    label: String,
    state: String,
    rating: Int?,
    resources: android.content.res.Resources,
    onRate: (Int) -> Unit,
) {
    contentDescription = label
    stateDescription = state
    if (rating == null) {
        customActions = (1..MAX_RATING).map { star ->
            CustomAccessibilityAction(resources.ratingStarAction(null, star)) { onRate(star); true }
        }
        return
    }
    progressBarRangeInfo = ProgressBarRangeInfo(rating.coerceIn(0, MAX_RATING).toFloat(), 0f..MAX_RATING.toFloat(), steps = MAX_RATING - 1)
    setProgress { value -> onRate(ratingForProgress(value)); true }
}

/**
 * Five stars for a 0–5 rating (§16.20), or for an unknown one (null: none filled and dimmed, said as
 * unknown, never as "not rated"): filled up to [rating], which already carries any change made
 * here, sent or not — offline, a rating is a rating and is labelled nowhere, as a heart is; only an
 * outcome that needs words is said, by the screen's outcome line. A tap on a star sets that rating, a
 * tap on the star already shown removes it ([ratingForTap]). For a screen reader it is one adjustable
 * element ([adjustableRating]) tagged [tag]; touch reaches each star.
 */
@Composable
public fun RatingStars(
    rating: Int?,
    onRate: (Int) -> Unit,
    tag: String,
    onColor: Color,
    offColor: Color,
    modifier: Modifier = Modifier,
    starSize: Dp = 24.dp,
    touchSize: Dp = 48.dp,
) {
    val resources = LocalContext.current.resources
    val shown = rating?.coerceIn(0, MAX_RATING)
    val filled = shown ?: 0
    val label = resources.getString(R.string.library_rating)
    val state = resources.ratingState(shown)
    Row(
        modifier.alpha(ratingAlpha(shown)).clearAndSetSemantics {
            testTag = tag
            adjustableRating(label, state, shown, resources, onRate)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (star in 1..MAX_RATING) {
            Box(Modifier.size(touchSize).clickable { onRate(ratingForTap(shown, star)) }, contentAlignment = Alignment.Center) {
                Image(
                    if (star <= filled) DulcetIcons.Star else DulcetIcons.StarBorder, null,
                    Modifier.size(starSize),
                    colorFilter = ColorFilter.tint(if (star <= filled) onColor else offColor),
                )
            }
        }
    }
}
