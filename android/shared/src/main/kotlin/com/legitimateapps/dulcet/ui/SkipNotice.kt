package com.legitimateapps.dulcet.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidSkipNotice
import com.legitimateapps.dulcet.shared.R
import kotlinx.coroutines.delay

/** How long a skip notice stays, before the system's accessibility timeout preference. */
public const val SKIP_NOTICE_MILLIS: Long = 4_000

/** The notice's text stops growing here: it is a transient status line (spec §12.12 rule 5). */
private const val MAX_NOTICE_FONT_SCALE = 1.5f

/**
 * The brief notice for a track the queue skipped because it could not play (spec §12.12 rule 5):
 * "Couldn't play “title”. Skipped." It is drawn at the bottom of the region this is given, which
 * the caller chooses so that the notice covers no control: a page's content region, ending above
 * its now-playing bar and navigation, or a player's cover art, which takes no input. It never takes
 * more than a third of that region's height: when the sentence naming the title would, it says "Couldn't
 * play a track. Skipped." instead. It is never truncated. In a region narrower than 240 dp it keeps
 * its width for the words: tighter margins, no glyph, and 12/14 of its text size, and so does a
 * [dense] region at any width. It draws no
 * pointer handling, so taps
 * pass through it to whatever is beneath. TalkBack announces it once as it appears, as one whole
 * sentence naming the track, whichever sentence is drawn -- provided no ancestor merges its
 * descendants' semantics (a `clickable` does), which would fold it into that ancestor's label.
 *
 * It shows for [SKIP_NOTICE_MILLIS] (longer when the person has asked Android for more time to
 * read), measured from when the skip happened, so a surface that appears later shows only what is
 * left, and one that appears after that shows nothing. [visible] false hides it on a surface that
 * is not in front without restarting its time.
 */
@Composable
public fun SkipNoticeRegion(
    notice: AndroidSkipNotice?,
    visible: Boolean,
    containerColor: Color,
    contentColor: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    maxCardWidth: Dp = Dp.Infinity,
    dense: Boolean = false,
    elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    val accessibility = LocalAccessibilityManager.current
    val lifetime = remember(accessibility) {
        accessibility?.calculateRecommendedTimeoutMillis(SKIP_NOTICE_MILLIS, containsIcons = true,
            containsText = true, containsControls = false) ?: SKIP_NOTICE_MILLIS
    }
    var expired by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(notice?.sequence, lifetime) {
        val current = notice ?: return@LaunchedEffect
        val remaining = lifetime - (elapsedMillis() - current.postedAtElapsedMillis)
        if (remaining > 0) delay(remaining)
        expired = current.sequence
    }
    val fresh = notice != null && expired != notice.sequence &&
        elapsedMillis() - notice.postedAtElapsedMillis < lifetime
    val card = rememberSkipNoticeCard(notice, textStyle)
    CompositionLocalProvider(LocalDensity provides card.density) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
            val geometry = card.geometry(maxWidth, dense)
            val sentence = card.measure(constraints.maxWidth,
                if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity, maxCardWidth, dense).sentence
            AnimatedVisibility(visible && fresh, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                Row(
                    Modifier.padding(start = geometry.outer, end = geometry.outer, bottom = geometry.outer)
                        .widthIn(max = maxCardWidth).fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp)).background(containerColor)
                        // One node, one sentence: the whole one naming the track even when the
                        // shorter one is drawn, announced once as the notice appears.
                        .clearAndSetSemantics {
                            contentDescription = card.named ?: card.anonymous
                            liveRegion = LiveRegionMode.Polite
                            testTag = SKIP_NOTICE_TAG
                            skipNoticeDrawnSentence = sentence
                        }
                        .padding(horizontal = geometry.inner, vertical = geometry.vertical),
                    horizontalArrangement = Arrangement.spacedBy(geometry.gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!geometry.narrow) Image(DulcetIcons.SkipNext, null, Modifier.size(geometry.glyph),
                        colorFilter = ColorFilter.tint(contentColor))
                    BasicText(sentence, style = geometry.style.copy(color = contentColor))
                }
            }
        }
    }
}

/** What a [SkipNoticeCard] draws in a region: the sentence, the card's height, and the margin beneath it, in pixels. */
public class SkipNoticeCardSize internal constructor(
    public val sentence: String,
    public val cardHeightPx: Int,
    public val marginPx: Int,
)

/**
 * The skip notice's card as a region of a given size draws it: which sentence, and how tall. The
 * region measures itself with this, and so does a layout deciding where to put the region -- a
 * player choosing between its cover and a banner -- so the two cannot disagree.
 */
@Stable
public class SkipNoticeCard internal constructor(
    private val measurer: TextMeasurer,
    private val textStyle: TextStyle,
    /** The caller's density with its font scale capped at the notice's own limit. */
    public val density: Density,
    /** The sentence naming the track, or null when the notice has no title. */
    public val named: String?,
    /** "Couldn't play a track. Skipped." */
    public val anonymous: String,
) {
    internal class Geometry(val narrow: Boolean, val style: TextStyle, val outer: Dp, val inner: Dp,
        val vertical: Dp, val glyph: Dp, val gap: Dp)

    // A narrow region -- a cover in a small or split-screen window -- keeps its width for the
    // words: tighter margins, no glyph, and the next size of body text down. A [dense] region --
    // a banner in a window with little height to spare -- draws the same way at any width.
    internal fun geometry(regionWidth: Dp, dense: Boolean = false): Geometry {
        val narrow = dense || regionWidth < NARROW_REGION
        val style = if (!narrow) textStyle
            else textStyle.copy(fontSize = textStyle.fontSize * NARROW_TEXT, lineHeight = textStyle.lineHeight * NARROW_TEXT)
        return Geometry(narrow, style, outer = if (narrow) 8.dp else 16.dp, inner = if (narrow) 12.dp else 16.dp,
            vertical = 10.dp, glyph = if (narrow) 0.dp else 20.dp, gap = if (narrow) 0.dp else 12.dp)
    }

    /**
     * The card in a region [widthPx] wide and [heightPx] tall ([Constraints.Infinity] when its
     * height is unbounded). The sentence naming the track is drawn when its card and the margin
     * beneath fit in a third of the region's height; otherwise the shorter one.
     */
    public fun measure(widthPx: Int, heightPx: Int, maxCardWidth: Dp = Dp.Infinity, dense: Boolean = false): SkipNoticeCardSize = with(density) {
        val g = geometry(widthPx.toDp(), dense)
        val region = widthPx.toDp() - g.outer * 2
        val cardWidth = if (maxCardWidth < region) maxCardWidth else region
        val textWidth = (cardWidth - g.inner * 2 - g.glyph - g.gap).roundToPx().coerceAtLeast(1)
        fun cardHeight(text: String) = maxOf(g.glyph.roundToPx(),
            measurer.measure(text, g.style, constraints = Constraints(maxWidth = textWidth), density = this).size.height) +
            (g.vertical * 2).roundToPx()
        val margin = g.outer.roundToPx()
        val share = if (heightPx == Constraints.Infinity) Int.MAX_VALUE else heightPx / 3
        val namedHeight = named?.let { cardHeight(it) }
        if (named != null && namedHeight != null && namedHeight + margin <= share) SkipNoticeCardSize(named, namedHeight, margin)
        else SkipNoticeCardSize(anonymous, cardHeight(anonymous), margin)
    }
}

/** The card for [notice] in [textStyle], at the caller's density with the notice's font-scale cap. */
@Composable
public fun rememberSkipNoticeCard(notice: AndroidSkipNotice?, textStyle: TextStyle): SkipNoticeCard {
    val named = notice?.title?.let { stringResource(R.string.playback_skipped_track, it) }
    val anonymous = stringResource(R.string.playback_skipped)
    val base = LocalDensity.current
    val density = if (base.fontScale <= MAX_NOTICE_FONT_SCALE) base else Density(base.density, MAX_NOTICE_FONT_SCALE)
    val measurer = rememberTextMeasurer()
    return remember(measurer, textStyle, density, named, anonymous) {
        SkipNoticeCard(measurer, textStyle, density, named, anonymous)
    }
}

/** Below this width the notice draws with tighter margins, without its glyph, and at [NARROW_TEXT] of its text size. */
private val NARROW_REGION = 240.dp
private const val NARROW_TEXT = 12f / 14f

public const val SKIP_NOTICE_TAG: String = "playback.skip-notice"

/** The sentence drawn on the card, which can be the shorter one; for tests, never read aloud. */
public val SkipNoticeDrawnSentence: SemanticsPropertyKey<String> = SemanticsPropertyKey("SkipNoticeDrawnSentence")
private var SemanticsPropertyReceiver.skipNoticeDrawnSentence by SkipNoticeDrawnSentence
