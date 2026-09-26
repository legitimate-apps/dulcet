package com.legitimateapps.dulcet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidRepeatMode
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.ArtworkImages
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.SkipNoticeRegion
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.rememberTextMeasurer
import com.legitimateapps.dulcet.ui.rememberSkipNoticeCard

/** The cover's drawn size, as a fraction of its box, while playback is paused. */
internal const val PAUSED_COVER_SCALE = 0.86f

/** Persistent transport above the tab bar whenever a session exists. */
@Composable
internal fun MiniPlayer(
    account: SearchAccount,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController?,
    open: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().testTag("player.mini").clickable(onClick = open)) {
        Column {
            val duration = state.durationMilliseconds?.takeIf { it > 0 }
            LinearProgressIndicator(
                progress = { if (duration == null) 0f else (state.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {},
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(account, state.artworkKey, state.title, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(state.title.ifBlank { stringResource(R.string.now_playing_loading) },
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("player.mini.title"))
                    state.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PlayPauseButton(state, playback, filled = false, size = 48)
                IconButton(onClick = { playback?.next() }, enabled = state.canGoNext, modifier = Modifier.testTag("player.mini.next")) {
                    Icon(DulcetIcons.SkipNext, stringResource(R.string.action_next))
                }
            }
        }
    }
}

@Composable
private fun PlayPauseButton(state: AndroidPlaybackState, playback: AndroidPlaybackController?, filled: Boolean, size: Int) {
    val playing = state.playWhenReady
    val label = stringResource(if (playing) R.string.action_pause else R.string.action_play)
    val icon = if (playing) DulcetIcons.Pause else DulcetIcons.Play
    val modifier = Modifier.size(size.dp).testTag(if (filled) "player.playpause" else "player.mini.playpause")
    if (filled) FilledIconButton(onClick = { playback?.togglePlayPause() }, modifier = modifier) {
        Icon(icon, label, Modifier.size((size * 0.45f).dp))
    } else IconButton(onClick = { playback?.togglePlayPause() }, modifier = modifier) { Icon(icon, label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingScreen(
    account: SearchAccount,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController,
    close: () -> Unit,
) {
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val surface = MaterialTheme.colorScheme.surface
    val accent = state.artworkKey?.let { ArtworkImages.accent(account, it) }
    val top by animateColorAsState(accent?.copy(alpha = 0.55f)?.compositeOver(surface) ?: MaterialTheme.colorScheme.primaryContainer,
        label = "now-playing-background")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, surface))).testTag("player.full")
        // The player covers the pages beneath it; touches must not fall through to them. A pointer
        // handler that consumes nothing, not `clickable`: a clickable merges everything inside it
        // into one node, so a screen reader read the whole player, the skip notice included, as
        // one label, and could not reach the notice on its own. The frame hides the pages beneath
        // from a screen reader while the player is open (`PhoneFrame`).
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        // The app locks no orientation and runs in split screen, so the player lays itself out for
        // the window it is given (`PlayerLayout`).
        PlayerLayout(account, state, playback, close, { showQueue = true },
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding())
    }
    if (showQueue) UpNextSheet(account, state, playback) { showQueue = false }
}

/** The transport row's width with every button at its full size, and at its compact size. */
private val TRANSPORT_FULL_WIDTH = 304.dp
private val TRANSPORT_COMPACT_WIDTH = 256.dp

/**
 * The smallest cover that hosts the skip notice. On a smaller one, or one where the card would take
 * more than half the cover as drawn, the notice is a banner beneath the header instead.
 */
internal val NOTICE_MIN_COVER = 160.dp
internal const val PLAYER_NOTICE_BANNER_TAG = "player.notice.banner"

/** The artist and the rest of the error card give way before the cover shrinks below this; then the cover gives way. */
private val COVER_FLOOR = 96.dp

/** A cover smaller than this is not drawn, and its height goes to the error card and the artist. */
private val COVER_MIN = 48.dp

/** The error card's first share: enough to show it is there and its first line, before the cover's. */
private val ERROR_FIRST = 72.dp

/** A window shorter than this uses the compact transport and a header without vertical padding. */
private val SHORT_WINDOW = 480.dp

private enum class PlayerSlot { Header, Banner, Cover, Info, Error, Scrubber, Transport }

/** How the player is arranged: stacked, the cover beside the controls, or the cover beside the title with the controls beneath both. */
internal enum class PlayerArrangement { Stacked, Beside, BesideAbove }

private class PlayerPlan(
    val arrangement: PlayerArrangement,
    val compact: Boolean,
    val cover: Int, val coverX: Int, val coverY: Int,
    val infoWidth: Int, val infoHeight: Int, val infoX: Int, val infoY: Int, val errorHeight: Int,
    val controlsWidth: Int, val controlsX: Int, val scrubberY: Int, val transportY: Int,
)

/**
 * The full player, laid out for the window it is given (spec §12.12 rule 8). Height goes, in
 * order, to: the header and the controls -- scrubber and transport, which never shrink; the
 * title's line; the skip notice's banner, when the notice is not on the cover; the error card, up
 * to [ERROR_FIRST]; the cover, up to [COVER_FLOOR], or none at all when less than [COVER_MIN] is
 * left; the rest of the error card; the artist; and then the cover again, up to the width it has. The
 * title and artist, and the error card, each scroll in their own region when cut short. A window
 * taller than wide stacks them; a wider one puts the cover beside them, and when the side beside
 * the cover is too narrow for the transport, or too short for the controls, the scrubber and
 * transport span the window beneath both.
 *
 * The notice lies on the cover, which takes no input, when the cover is at least
 * [NOTICE_MIN_COVER] and the card, with the margin beneath it, takes at most the lower half of it as
 * drawn. Otherwise it is a full-width
 * banner beneath the header and above the title, drawn dense -- no glyph, tighter margins, 12/14 of
 * the text size -- taking its height from the cover and never covering a control; the cover then
 * carries no notice.
 */
@Composable
private fun PlayerLayout(
    account: SearchAccount,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController,
    close: () -> Unit,
    openQueue: () -> Unit,
    modifier: Modifier,
) {
    val card = rememberSkipNoticeCard(state.skipNotice, MaterialTheme.typography.bodyMedium)
    val titleStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    SubcomposeLayout(modifier) { constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val wide = width > height
        val short = height < SHORT_WINDOW.roundToPx()
        val pad = if (wide) 24.dp.roundToPx()
            else ((width - TRANSPORT_COMPACT_WIDTH.roundToPx()) / 2).coerceIn(8.dp.roundToPx(), 28.dp.roundToPx())
        val inner = (width - pad * 2).coerceAtLeast(0)
        val header = subcompose(PlayerSlot.Header) {
            PlayerHeader(state, close, openQueue, vertical = if (short) 0.dp else if (wide) 4.dp else 8.dp)
        }.single().measure(Constraints(maxWidth = inner))
        val info = subcompose(PlayerSlot.Info) { PlayerInfo(state) }.single()
        val error = subcompose(PlayerSlot.Error) { PlayerError(state) }.single()
        val scrubber = subcompose(PlayerSlot.Scrubber) { Column(Modifier.fillMaxWidth()) { Scrubber(state, playback) } }.single()
        val titleLine = measurer.measure("Ag", titleStyle, density = this).size.height
        val full = TRANSPORT_FULL_WIDTH.roundToPx()
        val compactWidth = TRANSPORT_COMPACT_WIDTH.roundToPx()
        fun compact(controls: Int) = controls < full || short
        fun transportHeight(compact: Boolean) = (if (compact) 64.dp else 80.dp).roundToPx()
        val floor = COVER_FLOOR.roundToPx()
        val tight = 4.dp.roundToPx()
        val errorGap = 8.dp.roundToPx()

        /** Shares [room] among the title and artist (width [infoWidth]), the error card, and the cover (at most [coverMax]). */
        class Shares(val info: Int, val error: Int, val cover: Int, val spare: Int)
        fun share(room: Int, infoWidth: Int, coverMax: Int, coverFirst: Boolean): Shares {
            var left = room.coerceAtLeast(0)
            val infoNatural = info.maxIntrinsicHeight(infoWidth)
            val errorNatural = error.maxIntrinsicHeight(infoWidth).let { if (it > 0) it + errorGap else 0 }
            val title = minOf(titleLine, infoNatural, left); left -= title
            var err = minOf(errorNatural, ERROR_FIRST.roundToPx(), left); left -= err
            var cover = if (coverFirst) minOf(left, floor, coverMax) else 0; left -= cover
            if (cover < COVER_MIN.roundToPx()) { left += cover; cover = 0 }
            val errRest = minOf(left, errorNatural - err); err += errRest; left -= errRest
            val rest = minOf(left, infoNatural - title); left -= rest
            val grow = if (coverFirst && cover > 0) minOf(left, coverMax - cover) else 0; cover += grow; left -= grow
            return Shares(title + rest, err, cover, left)
        }

        fun stacked(banner: Int): PlayerPlan {
            val compact = compact(inner)
            val tr = transportHeight(compact)
            val sc = scrubber.minIntrinsicHeight(inner)
            var infoGap = 20.dp.roundToPx()
            var controlsGap = 12.dp.roundToPx()
            val bottom = 8.dp.roundToPx()
            val top = header.height + banner
            fun room() = height - top - infoGap - sc - controlsGap - tr - bottom
            if (room() < titleLine + floor) { infoGap = tight; controlsGap = tight }
            val shares = share(room(), inner, inner, coverFirst = true)
            // Spare height goes half above the cover, half below it, and 0.4 of those shares under the transport.
            val above = (shares.spare * 0.5f / 1.4f).roundToInt()
            val coverY = top + above
            val infoY = coverY + shares.cover + above
            val scrubberY = infoY + shares.info + shares.error + infoGap
            return PlayerPlan(PlayerArrangement.Stacked, compact, shares.cover, pad + (inner - shares.cover) / 2, coverY,
                inner, shares.info, pad, infoY, shares.error, inner, pad, scrubberY, scrubberY + sc + controlsGap)
        }

        fun sideways(banner: Int): PlayerPlan {
            val gap = 24.dp.roundToPx()
            val top = header.height + banner
            val body = (height - top - 12.dp.roundToPx()).coerceAtLeast(0)
            val controlsGap = 12.dp.roundToPx()
            val infoGap = 12.dp.roundToPx()
            // Half the window for the cover, less if the side beside it would be too narrow for the transport.
            var side = minOf(body, (inner - gap) / 2)
            if (inner - gap - side < compactWidth) side = inner - gap - compactWidth
            val pane = inner - gap - side
            if (side >= floor) {
                val compact = compact(pane)
                val tr = transportHeight(compact)
                val sc = scrubber.minIntrinsicHeight(pane)
                val fixed = infoGap + sc + controlsGap + tr
                if (body - fixed >= minOf(titleLine, info.maxIntrinsicHeight(pane))) {
                    val shares = share(body - fixed, pane, 0, coverFirst = false)
                    val paneY = top + (body - fixed - shares.info - shares.error) / 2
                    val x = pad + side + gap
                    val scrubberY = paneY + shares.info + shares.error + infoGap
                    return PlayerPlan(PlayerArrangement.Beside, compact, side, pad, top + (body - side) / 2,
                        pane, shares.info, x, paneY, shares.error, pane, x, scrubberY, scrubberY + sc + controlsGap)
                }
            }
            // Too narrow or short for the controls beside the cover: they span the window beneath.
            val compact = compact(inner)
            val tr = transportHeight(compact)
            val sc = scrubber.minIntrinsicHeight(inner)
            var infoGap2 = 8.dp.roundToPx()
            var controlsGap2 = controlsGap
            if (body - infoGap2 - sc - controlsGap2 - tr < titleLine) { infoGap2 = tight; controlsGap2 = tight }
            val row = (body - infoGap2 - sc - controlsGap2 - tr).coerceAtLeast(0)
            val beside = 16.dp.roundToPx()
            val cover = minOf(row, (inner - beside) / 2)
            val infoWidth = inner - cover - beside
            val shares = share(row, infoWidth, 0, coverFirst = false)
            val scrubberY = top + row + infoGap2
            return PlayerPlan(PlayerArrangement.BesideAbove, compact, cover, pad, top + (row - cover) / 2,
                infoWidth, shares.info, pad + cover + beside, top + (row - shares.info - shares.error) / 2, shares.error,
                inner, pad, scrubberY, scrubberY + sc + controlsGap2)
        }

        fun plan(banner: Int) = if (wide) sideways(banner) else stacked(banner)
        // Where the notice goes is decided on the layout without a banner, so the banner taking its
        // height from the cover cannot move it back: the cover it would have had is too small.
        val withoutBanner = plan(0)
        val sleeve = (withoutBanner.cover * PAUSED_COVER_SCALE).toInt()
        val onCover = withoutBanner.cover >= NOTICE_MIN_COVER.roundToPx() &&
            card.measure(sleeve, sleeve).let { it.cardHeightPx + it.marginPx <= sleeve / 2 }
        // The banner names the track when that takes no more lines than the shorter sentence: the
        // region names it when its card fits in a third of the height it is given.
        val banner = if (onCover) null else subcompose(PlayerSlot.Banner) {
            PhoneSkipNotice(state, visible = true, Modifier.testTag(PLAYER_NOTICE_BANNER_TAG), fill = false, dense = true)
        }.single().measure(Constraints(maxWidth = inner,
            maxHeight = card.measure(inner, 0, dense = true).let { (it.cardHeightPx + it.marginPx) * 3 }))
        val plan = if (banner == null || banner.height == 0) withoutBanner else plan(banner.height)
        val cover = subcompose(PlayerSlot.Cover) { PlayerCover(account, state, onCover) }.single()
            .measure(Constraints.fixed(plan.cover, plan.cover))
        val infoPlaced = info.measure(Constraints(plan.infoWidth, plan.infoWidth, 0, plan.infoHeight))
        // The error's share includes the gap above the card, dropped when the share is too small to keep both.
        val gapAbove = if (plan.errorHeight >= errorGap * 2) errorGap else 0
        val errorPlaced = error.measure(Constraints(plan.infoWidth, plan.infoWidth, 0, plan.errorHeight - gapAbove))
        val scrubberPlaced = scrubber.measure(Constraints.fixedWidth(plan.controlsWidth))
        val transport = subcompose(PlayerSlot.Transport) { Transport(state, playback, plan.compact) }.single()
            .measure(Constraints.fixedWidth(plan.controlsWidth))
        layout(width, height) {
            header.place(pad, 0)
            banner?.place(pad, header.height)
            cover.place(plan.coverX, plan.coverY)
            infoPlaced.place(plan.infoX, plan.infoY)
            errorPlaced.place(plan.infoX, plan.infoY + plan.infoHeight + gapAbove)
            scrubberPlaced.place(plan.controlsX, plan.scrubberY)
            transport.place(plan.controlsX, plan.transportY)
        }
    }
}

/** Every control's touch target is at least this, whatever its glyph's size. */
internal val TOUCH_TARGET = 48.dp

@Composable
private fun PlayerHeader(state: AndroidPlaybackState, close: () -> Unit, openQueue: () -> Unit, vertical: Dp) {
    Row(Modifier.fillMaxWidth().padding(vertical = vertical), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = close, modifier = Modifier.size(TOUCH_TARGET).testTag("player.close")) {
            Icon(DulcetIcons.ExpandMore, stringResource(R.string.action_close_player))
        }
        Text(state.album ?: stringResource(R.string.now_playing), Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = openQueue, modifier = Modifier.size(TOUCH_TARGET).testTag("player.queue")) {
            Icon(DulcetIcons.QueueMusic, stringResource(R.string.action_up_next))
        }
    }
}

/** The cover, with the skip notice along its bottom when [notice]: the cover takes no input, so the notice covers no control. */
@Composable
private fun PlayerCover(account: SearchAccount, state: AndroidPlaybackState, notice: Boolean) {
    // The cover settles back when paused, as a physical sleeve would.
    val scale by animateFloatAsState(if (state.playWhenReady) 1f else PAUSED_COVER_SCALE, spring(dampingRatio = 0.6f), label = "cover")
    Box(Modifier.testTag("player.artwork")) {
        Artwork(account, state.artworkKey, state.title, null,
            Modifier.fillMaxSize().scale(scale).shadow(24.dp, RoundedCornerShape(12.dp)))
        // The region is the cover at its paused size, so the notice lies on the cover as drawn
        // whether it is playing or settled back (spec §12.12 rule 8).
        if (notice) PhoneSkipNotice(state, visible = true, Modifier.align(Alignment.Center).fillMaxSize(PAUSED_COVER_SCALE))
    }
}

/** The title and artist, scrolling in their own region when cut short. */
@Composable
private fun PlayerInfo(state: AndroidPlaybackState) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(state.title.ifBlank {
                stringResource(if (state.hasSession) R.string.now_playing_loading else R.string.now_playing_nothing)
            },
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.testTag("player.title").basicMarquee())
        Text(state.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
    }
}

/** The failure line, when there is one, scrolling in its own region when cut short. */
@Composable
private fun PlayerError(state: AndroidPlaybackState) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (state.error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().testTag("player.error")) {
                Text(stringResource(R.string.now_playing_failed), Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** Shuffle, Previous, Play/Pause, Next and Repeat; [compact] when the row has less than its full width. */
@Composable
private fun Transport(state: AndroidPlaybackState, playback: AndroidPlaybackController, compact: Boolean) {
    val skip = if (compact) 48 else 64
    val glyph = if (compact) 32 else 40
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        ToggleIcon(DulcetIcons.Shuffle, stringResource(R.string.action_shuffle), state.shuffle, "player.shuffle") {
            playback.setShuffle(!state.shuffle)
        }
        IconButton(onClick = playback::skipToPrevious, enabled = state.canGoPrevious || state.canRestart,
            modifier = Modifier.size(skip.dp).testTag("player.previous")) {
            Icon(DulcetIcons.SkipPrevious, stringResource(R.string.action_previous), Modifier.size(glyph.dp))
        }
        PlayPauseButton(state, playback, filled = true, size = if (compact) 64 else 80)
        IconButton(onClick = playback::next, enabled = state.canGoNext, modifier = Modifier.size(skip.dp).testTag("player.next")) {
            Icon(DulcetIcons.SkipNext, stringResource(R.string.action_next), Modifier.size(glyph.dp))
        }
        val repeatLabel = stringResource(when (state.repeatMode) {
            AndroidRepeatMode.Off -> R.string.action_repeat_off
            AndroidRepeatMode.All -> R.string.action_repeat_all
            AndroidRepeatMode.One -> R.string.action_repeat_one
        })
        ToggleIcon(if (state.repeatMode == AndroidRepeatMode.One) DulcetIcons.RepeatOne else DulcetIcons.Repeat,
            repeatLabel, state.repeatMode != AndroidRepeatMode.Off, "player.repeat", playback::cycleRepeatMode)
    }
}

/** The skip notice (spec §12.12 rule 5) in the phone's colours, at the bottom of the region it fills, or as tall as its card when not [fill]. */
@Composable
internal fun PhoneSkipNotice(
    state: AndroidPlaybackState,
    visible: Boolean,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    dense: Boolean = false,
) {
    SkipNoticeRegion(state.skipNotice, visible, MaterialTheme.colorScheme.inverseSurface,
        MaterialTheme.colorScheme.inverseOnSurface, MaterialTheme.typography.bodyMedium,
        if (fill) modifier.fillMaxSize() else modifier.fillMaxWidth(), dense = dense)
}

@Composable
private fun Scrubber(state: AndroidPlaybackState, playback: AndroidPlaybackController) {
    val duration = state.durationMilliseconds?.takeIf { it > 0 }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration == null) 0f else (state.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            val target = dragging
            dragging = null
            if (target != null && duration != null) playback.seek((target * duration).toLong())
        },
        enabled = state.seekable && duration != null,
        modifier = Modifier.fillMaxWidth().testTag("player.scrubber"),
    )
    Row(Modifier.fillMaxWidth()) {
        val shown = dragging?.let { (it * (duration ?: 0)).toLong() } ?: state.positionMilliseconds
        Text(formatDuration(shown), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("player.position"))
        Spacer(Modifier.weight(1f))
        Text(duration?.let { "-" + formatDuration(it - shown) } ?: "", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val onOff = stringResource(if (active) R.string.state_on else R.string.state_off)
    IconButton(onClick = onClick, modifier = Modifier.size(TOUCH_TARGET).testTag(tag).semantics {
        stateDescription = onOff
    }, colors = IconButtonDefaults.iconButtonColors(
        contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(icon, label)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpNextSheet(account: SearchAccount, state: AndroidPlaybackState, playback: AndroidPlaybackController, dismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, modifier = Modifier.testTag("player.upnext")) {
        Text(stringResource(R.string.action_up_next), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (state.queue.isEmpty()) {
            Text(stringResource(R.string.up_next_empty), Modifier.padding(24.dp))
            return@ModalBottomSheet
        }
        val list = rememberLazyListState()
        LaunchedEffect(Unit) { state.currentIndex?.let { list.scrollToItem(it) } }
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(state.queue, key = { _, entry -> entry.queueEntryId }) { position, entry ->
                val current = position == state.currentIndex
                val past = state.currentIndex?.let { position < it } ?: false
                ListItem(
                    headlineContent = {
                        Text(entry.track.title.ifBlank { stringResource(R.string.now_playing_loading) }, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            fontWeight = if (current) FontWeight.SemiBold else null)
                    },
                    supportingContent = entry.track.artist?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = { Artwork(account, entry.track.artworkKey, entry.track.title, 44.dp) },
                    trailingContent = when {
                        current -> { { Icon(DulcetIcons.MusicNote, stringResource(R.string.up_next_playing),
                            tint = MaterialTheme.colorScheme.primary) } }
                        entry.track.durationMilliseconds != null -> { { Text(formatDuration(entry.track.durationMilliseconds!!),
                            style = MaterialTheme.typography.bodySmall) } }
                        else -> null
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(enabled = !current) { playback.jumpTo(entry.queueEntryId) }
                        .alpha(if (past) 0.55f else 1f)
                        .testTag("player.upnext.$position"),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
