package com.legitimateapps.dulcet.tv

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/** Quiet destination progress; content already on screen remains in place while revalidating. */
@Composable
internal fun TvLoadingProgress(tag: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "destination progress")
    val position by transition.animateFloat(-0.3f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "progress position")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val indicator = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(4.dp).testTag(tag)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        drawRect(track)
        val left = (size.width * position).coerceAtLeast(0f)
        val right = (size.width * (position + 0.3f)).coerceAtMost(size.width)
        if (right > left) drawRect(indicator, Offset(left, 0f), Size(right - left, size.height))
    }
}

/** Shelf-sized loading placeholders preserve the card geometry and next-card peek. */
@Composable
internal fun TvShelfSkeleton(tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvLoadingProgress("$tag.progress")
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.testTag(tag)) {
            items(5) {
                Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(200.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)))
                    Box(Modifier.fillMaxWidth(0.8f).height(20.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)))
                    Box(Modifier.fillMaxWidth(0.55f).height(16.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
