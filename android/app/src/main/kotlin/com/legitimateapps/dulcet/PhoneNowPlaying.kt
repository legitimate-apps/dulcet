package com.legitimateapps.dulcet

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.AndroidQueueEntry
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.libraryResources
import com.legitimateapps.dulcet.library.connectionLine
import com.legitimateapps.dulcet.library.savedAccountLine
import com.legitimateapps.dulcet.library.outcomeLines
import com.legitimateapps.dulcet.library.rememberWatchedFavourite
import com.legitimateapps.dulcet.library.rememberWatchedRating
import com.legitimateapps.dulcet.library.rememberNowPlayingLyrics
import com.legitimateapps.dulcet.ui.RatingStars

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.legitimateapps.dulcet.core.AndroidLyricsState
import com.legitimateapps.dulcet.core.AndroidPlaybackController
import com.legitimateapps.dulcet.core.AndroidPlaybackState
import com.legitimateapps.dulcet.core.AndroidRepeatMode
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.ui.ArtworkImages
import com.legitimateapps.dulcet.ui.DulcetIcons
import com.legitimateapps.dulcet.ui.SkipNoticeRegion
import com.legitimateapps.dulcet.ui.queueEditRefused
import com.legitimateapps.dulcet.ui.rememberSkipNoticeCard
import com.legitimateapps.dulcet.shared.R as SharedR
import kotlin.math.roundToInt

/** The drag handle's glyph: two grip lines. */
private val DragHandleIcon: ImageVector = ImageVector.Builder("DragHandle", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M20 9H4v2h16V9zM4 15h16v-2H4v-2z"), fill = SolidColor(Color.Black))
    .build()

/**
 * The mini player: one tonal playback surface above the tab bar, separated from it by margins and
 * rounded corners, with one progress edge in either play state and 48 dp targets.
 */
@Composable
internal fun MiniPlayer(
    account: SearchAccount,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController?,
    open: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
            .testTag("player.mini")
            .clip(shape)
            .clickable(onClick = open),
    ) {
        Column {
            val duration = state.durationMilliseconds?.takeIf { it > 0 }
            // One progress edge along the top of the surface, playing or paused.
            LinearProgressIndicator(
                progress = { if (duration == null) 0f else (state.positionMilliseconds.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(account, state.artworkKey, state.title, 44.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(state.title.ifBlank { stringResource(R.string.now_playing_loading) },
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("player.mini.title"))
                    // A Play that needs the server of a saved account (§13.1) says so where it was pressed.
                    if (state.needsReconnect) {
                        Text(stringResource(R.string.mini_player_needs_reconnect), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("player.mini.needsReconnect"))
                    } else state.artist?.let {
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

/**
 * Play/Pause. [filled] is the full player's primary button: filled, and its shape morphs — a circle
 * while playing, a rounded square paused — so the state reads from more than the glyph. Unfilled is
 * the mini player's tonal button. While the track prepares or buffers, the slot shows a progress
 * indicator instead of the glyph; the button still toggles.
 */
@Composable
private fun PlayPauseButton(state: AndroidPlaybackState, playback: AndroidPlaybackController?, filled: Boolean, size: Int) {
    val playing = state.playWhenReady
    val label = stringResource(if (playing) R.string.action_pause else R.string.action_play)
    val buffering = playing && (state.phase == "Preparing" || state.phase == "Buffering")
    val modifier = Modifier.size(size.dp)
        .semantics { contentDescription = label }
        .testTag(if (filled) "player.playpause" else "player.mini.playpause")
    val content: @Composable () -> Unit = {
        if (buffering) CircularProgressIndicator(Modifier.size((size * 0.4f).dp), color = LocalContentColor.current)
        else Icon(if (playing) DulcetIcons.Pause else DulcetIcons.Play, null, Modifier.size((size * 0.45f).dp))
    }
    if (filled) {
        val corner by animateFloatAsState(if (playing) 50f else 22f, label = "playpause-shape")
        FilledIconButton(onClick = { playback?.togglePlayPause() }, shape = RoundedCornerShape(corner.toInt()),
            modifier = modifier, content = content)
    } else {
        FilledTonalIconButton(onClick = { playback?.togglePlayPause() }, modifier = modifier, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NowPlayingScreen(
    account: SearchAccount,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController,
    library: LibrarySession? = null,
    /** Opens the playing track's album page; the row hides until the shell wires navigation here. */
    openAlbum: ((String) -> Unit)? = null,
    close: () -> Unit,
) {
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    val track = state.queue.getOrNull(state.currentIndex ?: -1)
    // The lyrics are read as the track changes, so a track without any disables the toggle (with a
    // tooltip saying so) instead of opening an empty sheet. The sheet reads the same publication.
    val nowPlayingLyrics = library?.let { rememberNowPlayingLyrics(it, state) }
    val lyricsAvailable = nowPlayingLyrics?.publication?.state != AndroidLyricsState.None
    // The header's library control: the heart, trailing the title. The stars live in the More sheet.
    val heart: (@Composable () -> Unit)? = library?.let { session -> { NowPlayingFavourite(session, state) } }
    // A saved account not yet connected (§13.1): what the player can do without the server, and Reconnect.
    val saved: (@Composable () -> Unit)? = library?.let { session -> { NowPlayingSavedNotice(session, account, state) } }
    val surface = MaterialTheme.colorScheme.surface
    val accent = state.artworkKey?.let { ArtworkImages.accent(account, it) }
    // The artwork's accent tints the scheme's container, so container/content contrast stays the theme's.
    val top by animateColorAsState(
        accent?.copy(alpha = 0.32f)?.compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
            ?: MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "now-playing-background")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, surface))).testTag("player.full")
        // The player covers the pages beneath it; touches must not fall through to them. A pointer
        // handler that consumes nothing, not `clickable`: a clickable merges everything inside it
        // into one node, so a screen reader read the whole player, the skip notice included, as
        // one label, and could not reach the notice on its own. The frame hides the pages beneath
        // from a screen reader while the player is open (`PhoneFrame`).
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        // The player paints its own gradient, not a Surface, so the content colour is set for it —
        // the default is black, unreadable on the dark gradient.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            // The app locks no orientation and runs in split screen, so the player lays itself out for
            // the window it is given (`PlayerLayout`).
            PlayerLayout(account, state, playback, close,
                openQueue = { showQueue = true },
                openMore = { showMore = true },
                openSleep = { showSleep = true },
                showLyricsButton = library != null && track != null,
                lyricsChecked = showLyrics,
                lyricsEnabled = lyricsAvailable,
                onLyrics = { showLyrics = it },
                heart = heart, saved = saved,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().displayCutoutPadding())
        }
    }
    if (showQueue) UpNextSheet(account, state, playback, top) { showQueue = false }
    if (showMore) NowPlayingMoreSheet(account, state, playback, library, openAlbum, top) { showMore = false }
    if (showSleep) SleepTimerSheet(state, playback::setSleepTimer) { showSleep = false }
    if (showLyrics && library != null) LyricsSheet(account, library, state, playback, top) { showLyrics = false }
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

private enum class PlayerSlot { Header, Banner, Cover, Info, Error, Scrubber, Transport, Actions }

/** How the player is arranged: stacked, the cover beside the controls, or the cover beside the title with the controls beneath both. */
internal enum class PlayerArrangement { Stacked, Beside, BesideAbove }

private class PlayerPlan(
    val arrangement: PlayerArrangement,
    val compact: Boolean,
    val tight: Boolean,
    val cover: Int, val coverX: Int, val coverY: Int,
    val infoWidth: Int, val infoHeight: Int, val infoX: Int, val infoY: Int, val errorHeight: Int,
    val controlsWidth: Int, val controlsX: Int, val scrubberY: Int, val transportY: Int, val actionsY: Int,
)

/**
 * The full player, laid out for the window it is given (spec §12.12 rule 8). Height goes, in
 * order, to: the header and the controls -- scrubber, transport and the action row beneath it,
 * which never shrink; the skip notice's banner, when the notice is not on the cover, for the
 * seconds it shows; the title's row (the heart included); the error card, up
 * to [ERROR_FIRST]; the cover, up to [COVER_FLOOR], or none at all when less than [COVER_MIN] is
 * left; the rest of the error card; the artist; and then the cover again, up to the width it has. The
 * title and artist, and the error card, each scroll in their own region when cut short. A window
 * taller than wide stacks them; a wider one puts the cover beside them, and when the side beside
 * the cover is too narrow for the transport, or too short for the controls, the scrubber,
 * transport and action row span the window beneath both. The cover keeps one size across tracks and
 * play states — nothing shrinks it for a pause.
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
    openMore: () -> Unit,
    openSleep: () -> Unit,
    showLyricsButton: Boolean,
    lyricsChecked: Boolean,
    lyricsEnabled: Boolean,
    onLyrics: (Boolean) -> Unit,
    heart: (@Composable () -> Unit)?,
    saved: (@Composable () -> Unit)?,
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
        val pad = if (wide) 24.dp.roundToPx() else 12.dp.roundToPx()
        val inner = (width - pad * 2).coerceAtLeast(0)
        // A window too short for the header, the controls and the action row at once folds the
        // row's buttons into the header; the row remains for the sleep chip. Computable here,
        // before the header is subcomposed, so the header's content can depend on it.
        val foldActions = short && height < 400.dp.roundToPx()
        val header = subcompose(PlayerSlot.Header) {
            PlayerHeader(state, close, openMore, vertical = if (short) 0.dp else if (wide) 4.dp else 8.dp,
                twoLine = !short, queue = if (foldActions) openQueue else null)
        }.single().measure(Constraints(maxWidth = inner))
        val info = subcompose(PlayerSlot.Info) { PlayerInfo(state, heart) }.single()
        val error = subcompose(PlayerSlot.Error) { PlayerError(state, saved) }.single()
        val scrubber = subcompose(PlayerSlot.Scrubber) { Column(Modifier.fillMaxWidth()) { Scrubber(state, playback) } }.single()
        // The action row's height is width-independent (fixed buttons, a one-line chip), so its
        // intrinsic height is its height; the single measurement happens at the plan's width below.
        // With the buttons folded into the header the row may be empty, and then it costs no gap.
        val actions = subcompose(PlayerSlot.Actions) {
            PlayerActions(state, showLyricsButton, lyricsChecked, lyricsEnabled, onLyrics, openQueue, openSleep, foldActions)
        }.single()
        val actionsHeight = actions.maxIntrinsicHeight(inner)
        val titleLine = measurer.measure("Ag", titleStyle, density = this).size.height
        // The title's row holds the heart as well: the first share of height is the taller of the two.
        val titleRow = maxOf(titleLine, if (heart != null) TOUCH_TARGET.roundToPx() else 0)
        val full = TRANSPORT_FULL_WIDTH.roundToPx()
        val compactWidth = TRANSPORT_COMPACT_WIDTH.roundToPx()
        fun compact(controls: Int) = controls < full || short
        fun transportHeight(compact: Boolean, tight: Boolean) =
            (if (tight) 48.dp else if (compact) 64.dp else 80.dp).roundToPx()
        val floor = COVER_FLOOR.roundToPx()
        val tight4 = 4.dp.roundToPx()
        val errorGap = 8.dp.roundToPx()

        /** Shares [room] among the title and artist (width [infoWidth]), the error card, and the cover (at most [coverMax]). */
        class Shares(val info: Int, val error: Int, val cover: Int, val spare: Int)
        fun share(room: Int, infoWidth: Int, coverMax: Int, coverFirst: Boolean): Shares {
            var left = room.coerceAtLeast(0)
            val infoNatural = info.maxIntrinsicHeight(infoWidth)
            val errorNatural = error.maxIntrinsicHeight(infoWidth).let { if (it > 0) it + errorGap else 0 }
            val title = minOf(titleRow, infoNatural, left); left -= title
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
            var tight = false
            var tr = transportHeight(compact, tight)
            val sc = scrubber.minIntrinsicHeight(inner)
            var infoGap = 20.dp.roundToPx()
            var controlsGap = 12.dp.roundToPx()
            var actionsGap = if (actionsHeight == 0) 0 else 8.dp.roundToPx()
            var bottom = 8.dp.roundToPx()
            val top = header.height + banner
            fun room() = height - top - infoGap - sc - controlsGap - tr - actionsGap - actionsHeight - bottom
            if (room() < titleRow + floor) {
                infoGap = tight4; controlsGap = tight4; actionsGap = if (actionsHeight == 0) 0 else tight4; bottom = 0
                if (room() < titleRow) { tight = true; tr = transportHeight(compact, tight) }
            }
            val shares = share(room(), inner, inner, coverFirst = true)
            // Spare height goes half above the cover, half below it, and 0.4 of those shares under the transport.
            val above = (shares.spare * 0.5f / 1.4f).roundToInt()
            val coverY = top + above
            val infoY = coverY + shares.cover + above
            val scrubberY = infoY + shares.info + shares.error + infoGap
            val transportY = scrubberY + sc + controlsGap
            return PlayerPlan(PlayerArrangement.Stacked, compact, tight, shares.cover, pad + (inner - shares.cover) / 2, coverY,
                inner, shares.info, pad, infoY, shares.error, inner, pad, scrubberY, transportY, transportY + tr + actionsGap)
        }

        fun sideways(banner: Int): PlayerPlan {
            val gap = 24.dp.roundToPx()
            val top = header.height + banner
            val body = (height - top - 12.dp.roundToPx()).coerceAtLeast(0)
            val controlsGap = 12.dp.roundToPx()
            val infoGap = 12.dp.roundToPx()
            val actionsGap = if (actionsHeight == 0) 0 else 8.dp.roundToPx()
            // Half the window for the cover, less if the side beside it would be too narrow for the transport.
            var side = minOf(body, (inner - gap) / 2)
            if (inner - gap - side < compactWidth) side = inner - gap - compactWidth
            val pane = inner - gap - side
            if (side >= floor) {
                val compact = compact(pane)
                val tr = transportHeight(compact, tight = false)
                val sc = scrubber.minIntrinsicHeight(pane)
                val fixed = infoGap + sc + controlsGap + tr + actionsGap + actionsHeight
                if (body - fixed >= minOf(titleRow, info.maxIntrinsicHeight(pane))) {
                    val shares = share(body - fixed, pane, 0, coverFirst = false)
                    val paneY = top + (body - fixed - shares.info - shares.error) / 2
                    val x = pad + side + gap
                    val scrubberY = paneY + shares.info + shares.error + infoGap
                    val transportY = scrubberY + sc + controlsGap
                    return PlayerPlan(PlayerArrangement.Beside, compact, false, side, pad, top + (body - side) / 2,
                        pane, shares.info, x, paneY, shares.error, pane, x, scrubberY, transportY, transportY + tr + actionsGap)
                }
            }
            // Too narrow or short for the controls beside the cover: they span the window beneath.
            val compact = compact(inner)
            var tight = false
            var tr = transportHeight(compact, tight)
            val sc = scrubber.minIntrinsicHeight(inner)
            var infoGap2 = 8.dp.roundToPx()
            var controlsGap2 = controlsGap
            var actionsGap2 = actionsGap
            if (body - infoGap2 - sc - controlsGap2 - tr - actionsGap2 - actionsHeight < titleRow) {
                infoGap2 = tight4; controlsGap2 = tight4; actionsGap2 = if (actionsHeight == 0) 0 else tight4
            }
            if (body - infoGap2 - sc - controlsGap2 - tr - actionsGap2 - actionsHeight < titleRow) {
                tight = true; tr = transportHeight(compact, tight)
            }
            val row = (body - infoGap2 - sc - controlsGap2 - tr - actionsGap2 - actionsHeight).coerceAtLeast(0)
            val beside = 16.dp.roundToPx()
            // As stacked: a cover under COVER_MIN is not drawn, and the title takes the row's width.
            val cover = minOf(row, (inner - beside) / 2).let { if (it < COVER_MIN.roundToPx()) 0 else it }
            val infoWidth = if (cover > 0) inner - cover - beside else inner
            val shares = share(row, infoWidth, 0, coverFirst = false)
            val scrubberY = top + row + infoGap2
            val transportY = scrubberY + sc + controlsGap2
            return PlayerPlan(PlayerArrangement.BesideAbove, compact, tight, cover, pad, top + (row - cover) / 2,
                infoWidth, shares.info, pad + inner - infoWidth, top + (row - shares.info - shares.error) / 2, shares.error,
                inner, pad, scrubberY, transportY, transportY + tr + actionsGap2)
        }

        fun plan(banner: Int) = if (wide) sideways(banner) else stacked(banner)
        // Where the notice goes is decided on the layout without a banner, so the banner taking its
        // height from the cover cannot move it back: the cover it would have had is too small.
        val withoutBanner = plan(0)
        val sleeve = withoutBanner.cover
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
        val transport = subcompose(PlayerSlot.Transport) { Transport(state, playback, plan.compact, plan.tight) }.single()
            .measure(Constraints.fixedWidth(plan.controlsWidth))
        val actionsPlaced = actions.measure(Constraints.fixedWidth(plan.controlsWidth))
        layout(width, height) {
            header.place(pad, 0)
            banner?.place(pad, header.height)
            cover.place(plan.coverX, plan.coverY)
            infoPlaced.place(plan.infoX, plan.infoY)
            errorPlaced.place(plan.infoX, plan.infoY + plan.infoHeight + gapAbove)
            scrubberPlaced.place(plan.controlsX, plan.scrubberY)
            transport.place(plan.controlsX, plan.transportY)
            actionsPlaced.place(plan.controlsX, plan.actionsY)
        }
    }
}

/** Every control's touch target is at least this, whatever its glyph's size. */
internal val TOUCH_TARGET = 48.dp

/** The collapse chevron, a small "Playing from / album" label, and More — nothing else. In a window too short for the action row, the queue button folds up here. */
@Composable
private fun PlayerHeader(state: AndroidPlaybackState, close: () -> Unit, openMore: () -> Unit, vertical: Dp, twoLine: Boolean,
                         queue: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = vertical), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = close, modifier = Modifier.size(TOUCH_TARGET).testTag("player.close")) {
            Icon(DulcetIcons.ExpandMore, stringResource(R.string.action_close_player))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (twoLine && state.album != null) {
                Text(stringResource(R.string.player_playing_from), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(state.album.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            } else {
                Text(state.album ?: stringResource(R.string.player_now_playing),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
            }
        }
        if (queue != null) {
            IconButton(onClick = queue, modifier = Modifier.size(TOUCH_TARGET).testTag("player.queue")) {
                Icon(DulcetIcons.QueueMusic, stringResource(R.string.action_up_next))
            }
        }
        IconButton(onClick = openMore, modifier = Modifier.size(TOUCH_TARGET).testTag("player.more")) {
            Icon(DulcetIcons.MoreVert, stringResource(R.string.player_more_options))
        }
    }
}

/** The cover, with the skip notice along its bottom when [notice]: the cover takes no input, so the notice covers no control. */
@Composable
private fun PlayerCover(account: SearchAccount, state: AndroidPlaybackState, notice: Boolean) {
    Box(Modifier.testTag("player.artwork")) {
        Artwork(account, state.artworkKey, state.title, null,
            Modifier.fillMaxSize().shadow(24.dp, RoundedCornerShape(12.dp)))
        if (notice) PhoneSkipNotice(state, visible = true, Modifier.align(Alignment.Center).fillMaxSize())
    }
}

/** The title and artist, scrolling in their own region when cut short; the heart trails them. */
@Composable
private fun PlayerInfo(state: AndroidPlaybackState, heart: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.title.ifBlank {
                    stringResource(if (state.hasSession) R.string.now_playing_loading else R.string.now_playing_nothing)
                },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.testTag("player.title").basicMarquee())
            Text(state.artist.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
        }
        heart?.invoke()
    }
}

/** The failure line, when there is one, scrolling in its own region when cut short. */
@Composable
private fun PlayerError(state: AndroidPlaybackState, saved: (@Composable () -> Unit)?) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        saved?.invoke()
        if (state.error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().testTag("player.error")) {
                Text(stringResource(R.string.now_playing_failed), Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/**
 * One restored-player connection notice, including authentication and trust failures, with Reconnect:
 * the shared one-line status style, not a prose card.
 */
@Composable
private fun NowPlayingSavedNotice(session: LibrarySession, account: SearchAccount, state: AndroidPlaybackState) {
    val connection by session.connection.collectAsState()
    val resources = libraryResources()
    val line = if (state.needsReconnect) resources.getString(SharedR.string.now_playing_needs_reconnect)
        else resources.savedAccountLine(connection, account) ?: resources.connectionLine(connection) ?: return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .testTag("player.saved"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(line, Modifier.weight(1f).testTag(if (state.needsReconnect) "player.needsReconnect" else "player.savedLine"),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = session::connectSavedAccount, modifier = Modifier.testTag("player.reconnect")) {
            Text(resources.getString(SharedR.string.library_reconnect))
        }
    }
}

/**
 * Shuffle, Previous, Play/Pause, Next and Repeat. The toggles are smaller and toggle-styled,
 * Previous/Next larger, and Play the filled primary. [compact] when the row has less than its full
 * width; [tight] when the window is short.
 */
@Composable
private fun Transport(state: AndroidPlaybackState, playback: AndroidPlaybackController, compact: Boolean, tight: Boolean) {
    val skip = if (tight || compact) 48 else 64
    val glyph = if (tight) 24 else if (compact) 32 else 40
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        ToggleIcon(DulcetIcons.Shuffle, stringResource(R.string.action_shuffle), state.shuffle, "player.shuffle") {
            playback.setShuffle(!state.shuffle)
        }
        IconButton(onClick = playback::skipToPrevious, enabled = state.canGoPrevious || state.canRestart,
            modifier = Modifier.size(skip.dp).testTag("player.previous")) {
            Icon(DulcetIcons.SkipPrevious, stringResource(R.string.action_previous), Modifier.size(glyph.dp))
        }
        PlayPauseButton(state, playback, filled = true, size = if (tight) 48 else if (compact) 64 else 80)
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

/**
 * The row under the transport: an active sleep timer's moon-and-countdown chip (it opens the timer's
 * sheet), then lyrics and queue. Lyrics is a toggle, disabled with its reason when the track has none.
 * [foldButtons]: the window is too short for this row — its buttons are in the header instead, and
 * only the chip stays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerActions(
    state: AndroidPlaybackState,
    showLyricsButton: Boolean,
    lyricsChecked: Boolean,
    lyricsEnabled: Boolean,
    onLyrics: (Boolean) -> Unit,
    openQueue: () -> Unit,
    openSleep: () -> Unit,
    foldButtons: Boolean,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.sleepTimer != null) {
            AssistChip(
                onClick = openSleep,
                leadingIcon = { Icon(BedtimeIcon, null, Modifier.size(18.dp)) },
                label = { Text(sleepTimerValue(state), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag("listening.sleep.active"),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (!foldButtons) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                if (showLyricsButton) {
                    val button = @Composable {
                        IconToggleButton(checked = lyricsChecked, onCheckedChange = onLyrics, enabled = lyricsEnabled,
                            modifier = Modifier.size(TOUCH_TARGET).testTag("player.lyrics")) {
                            Icon(DulcetIcons.Lyrics, stringResource(R.string.action_lyrics))
                        }
                    }
                    if (lyricsEnabled) button()
                    else TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(stringResource(SharedR.string.lyrics_none)) } },
                        state = rememberTooltipState(),
                    ) { Box { button() } }
                }
                IconButton(onClick = openQueue, modifier = Modifier.size(TOUCH_TARGET).testTag("player.queue")) {
                    Icon(DulcetIcons.QueueMusic, stringResource(R.string.action_up_next))
                }
            }
        }
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
        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
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

/** A transport toggle: a tonal button whose container shows the on state, not just its glyph's colour. */
@Composable
private fun ToggleIcon(icon: ImageVector, label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val onOff = stringResource(if (active) R.string.state_on else R.string.state_off)
    FilledTonalIconToggleButton(checked = active, onCheckedChange = { onClick() },
        modifier = Modifier.size(TOUCH_TARGET).testTag(tag).semantics { stateDescription = onOff }) {
        Icon(icon, label, Modifier.size(20.dp))
    }
}

/**
 * What the Up Next sheet can do to the queue. The controller in the app; a recording in a test. Each
 * edit answers whether the queue accepted it, and a refusal is said (spec §14.1).
 */
internal interface UpNextActions {
    fun jumpTo(queueEntryId: String)
    fun moveEntry(queueEntryId: String, toIndex: Int): Boolean
    fun removeEntry(queueEntryId: String): Boolean
    fun clearUpcoming(): Boolean
}

private class ControllerUpNextActions(private val controller: AndroidPlaybackController) : UpNextActions {
    override fun jumpTo(queueEntryId: String) = controller.jumpTo(queueEntryId)
    override fun moveEntry(queueEntryId: String, toIndex: Int) = controller.moveEntry(queueEntryId, toIndex)
    override fun removeEntry(queueEntryId: String) = controller.removeEntry(queueEntryId)
    override fun clearUpcoming() = controller.clearUpcoming()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpNextSheet(account: SearchAccount, state: AndroidPlaybackState, playback: AndroidPlaybackController, tint: Color, dismiss: () -> Unit) {
    // At the largest fonts the sheet opens expanded: at a half peek it would show a single row.
    val expanded = LocalDensity.current.fontScale >= 1.5f
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = expanded)
    PhoneModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet, containerColor = tint,
        modifier = Modifier.testTag("player.upnext")) {
        UpNextList(account, state, remember(playback) { ControllerUpNextActions(playback) }, autoplay = playback::setAutoplay)
    }
}

/** The live drag of an Up Next row: the entry dragged, how far, and the queue position it holds now. */
private class UpNextDragState {
    var activeId by mutableStateOf<String?>(null)
    var offset by mutableStateOf(0f)
    var position by mutableStateOf(-1)
    var rowHeightPx by mutableStateOf(0f)
    /** The range a row may be dragged into: [firstUpcoming]..[ids]' end. Refreshed every composition. */
    var firstUpcoming = 0
    var ids: List<String> = emptyList()
    val lastIndex get() = ids.lastIndex
    fun begin(id: String) { activeId = id; position = ids.indexOf(id); offset = 0f }
    fun end() { activeId = null; offset = 0f }
}

/**
 * The queue: what played, the playing entry above the Up Next header, then Up Next itself. A tap
 * plays an entry. Each entry after the playing one reorders from its drag handle, and has a menu
 * that moves it up or down within Up Next or removes it — the same three are accessibility actions
 * on the row, since a menu behind a button is one more step for TalkBack. The playing entry and
 * those before it offer no edits: removing the playing one is refused by the queue (spec §14.1),
 * and the Apple shells edit Up Next only. Clear removes all of Up Next, behind a confirmation.
 * Autoplay is the section after the queue, its switch on the section header — the one place it lives.
 */
@Composable
internal fun UpNextList(
    account: SearchAccount?,
    state: AndroidPlaybackState,
    actions: UpNextActions,
    autoplay: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    fun answered(accepted: Boolean) { if (!accepted) queueEditRefused(context) }
    // With no playing entry, the whole queue is Up Next.
    val firstUpcoming = state.currentIndex?.plus(1) ?: 0
    var confirmClear by remember { mutableStateOf(false) }
    if (state.queue.isEmpty()) {
        UpNextHeader(showClear = false) {}
        Text(stringResource(R.string.up_next_empty), Modifier.padding(24.dp))
        if (autoplay != null) AutoplayHeader(state, autoplay)
        return
    }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { state.currentIndex?.let { list.scrollToItem(it) } }
    val moveUp = stringResource(SharedR.string.queue_move_up)
    val moveDown = stringResource(SharedR.string.queue_move_down)
    val remove = stringResource(SharedR.string.queue_remove)
    val drag = remember { UpNextDragState() }
    drag.firstUpcoming = firstUpcoming
    drag.ids = state.queue.map { it.queueEntryId }
    val hasAutoplayRows = state.queue.any { it.isAutoplay }
    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        itemsIndexed(state.queue, key = { _, entry -> entry.queueEntryId }) { position, entry ->
            val current = position == state.currentIndex
            val past = state.currentIndex?.let { position < it } ?: false
            val upcoming = position >= firstUpcoming
            val canMoveUp = upcoming && position > firstUpcoming
            val canMoveDown = upcoming && position < state.queue.lastIndex
            val id = entry.queueEntryId
            if (position == firstUpcoming) {
                UpNextHeader(showClear = true) { confirmClear = true }
            }
            if (entry.isAutoplay && (position == 0 || !state.queue[position - 1].isAutoplay)) {
                AutoplayHeader(state, autoplay)
            }
            if (current) NowPlayingQueueRow(account, state, entry, position)
            else QueueEntryRow(account, entry, position, past, upcoming, canMoveUp, canMoveDown, drag, actions,
                moveUp, moveDown, remove) { answered(it) }
        }
        if (firstUpcoming >= state.queue.size) {
            item { UpNextHeader(showClear = false) {} }
        }
        if (!hasAutoplayRows && autoplay != null) {
            item { AutoplayHeader(state, autoplay) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.queue_clear_title)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; answered(actions.clearUpcoming()) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("player.upnext.clear.confirm")) {
                    Text(stringResource(SharedR.string.queue_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }, modifier = Modifier.testTag("player.upnext.clear.cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** The sheet's title row: "Up Next", with Clear centred against it while there is anything to clear. */
@Composable
private fun UpNextHeader(showClear: Boolean, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.action_up_next), Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (showClear) {
            TextButton(onClick = onClear, modifier = Modifier.testTag("player.upnext.clear").heightIn(min = TOUCH_TARGET)) {
                Text(stringResource(SharedR.string.queue_clear))
            }
        }
    }
}

/** The Autoplay section header: its title and, on it, the one Autoplay switch. */
@Composable
private fun AutoplayHeader(state: AndroidPlaybackState, autoplay: ((Boolean) -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(SharedR.string.listening_autoplay), style = MaterialTheme.typography.titleMedium)
            if (state.autoplayEnabled && !state.autoplayActive) {
                Text(stringResource(SharedR.string.listening_autoplay_inactive), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (autoplay != null) {
            Switch(checked = state.autoplayEnabled, onCheckedChange = autoplay, modifier = Modifier.testTag("listening.autoplay"))
        }
    }
}

/** The playing entry: primary text and the equaliser glyph instead of an edit affordance. */
@Composable
private fun NowPlayingQueueRow(account: SearchAccount?, state: AndroidPlaybackState, entry: AndroidQueueEntry, position: Int) {
    ListItem(
        headlineContent = {
            Text(entry.track.title.ifBlank { stringResource(R.string.now_playing_loading) }, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        },
        supportingContent = entry.track.artist?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { if (account != null) Artwork(account, entry.track.artworkKey, entry.track.title, 44.dp) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.track.durationMilliseconds?.let {
                    Text(formatDuration(it), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                }
                NowPlayingIndicator(state.playWhenReady)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.testTag("player.upnext.$position"),
    )
}

/** A queue entry away from the playing one: past ones are dimmed; upcoming ones reorder and remove. */
@Composable
private fun QueueEntryRow(
    account: SearchAccount?,
    entry: AndroidQueueEntry,
    position: Int,
    past: Boolean,
    upcoming: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    drag: UpNextDragState,
    actions: UpNextActions,
    moveUp: String,
    moveDown: String,
    remove: String,
    answered: (Boolean) -> Unit,
) {
    val id = entry.queueEntryId
    ListItem(
        headlineContent = {
            Text(entry.track.title.ifBlank { stringResource(R.string.now_playing_loading) }, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        },
        supportingContent = entry.track.artist?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { if (account != null) Artwork(account, entry.track.artworkKey, entry.track.title, 44.dp) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.track.durationMilliseconds?.let {
                    Text(formatDuration(it), style = MaterialTheme.typography.bodySmall)
                }
                if (upcoming) {
                    QueueDragHandle(id, position, drag, actions)
                    UpNextEntryMenu("player.upnext.$position",
                        onMoveUp = if (canMoveUp) { { answered(actions.moveEntry(id, position - 1)) } } else null,
                        onMoveDown = if (canMoveDown) { { answered(actions.moveEntry(id, position + 1)) } } else null,
                        onRemove = { answered(actions.removeEntry(id)) })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { actions.jumpTo(id) }
            .onGloballyPositioned { if (it.size.height > 0) drag.rowHeightPx = it.size.height.toFloat() }
            .zIndex(if (drag.activeId == id) 1f else 0f)
            .graphicsLayer { translationY = if (drag.activeId == id) drag.offset else 0f }
            .semantics {
                if (upcoming) customActions = listOfNotNull(
                    if (canMoveUp) CustomAccessibilityAction(moveUp) { answered(actions.moveEntry(id, position - 1)); true } else null,
                    if (canMoveDown) CustomAccessibilityAction(moveDown) { answered(actions.moveEntry(id, position + 1)); true } else null,
                    CustomAccessibilityAction(remove) { answered(actions.removeEntry(id)); true },
                )
            }
            .alpha(if (past) 0.55f else 1f)
            .testTag("player.upnext.$position"),
    )
}

/** A row's grip: a long press and drag moves the entry within Up Next. */
@Composable
private fun QueueDragHandle(id: String, position: Int, drag: UpNextDragState, actions: UpNextActions) {
    Box(
        Modifier.size(TOUCH_TARGET)
            .testTag("player.upnext.$position.drag")
            .pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { drag.begin(id) },
                    onDragEnd = { drag.end() },
                    onDragCancel = { drag.end() },
                ) { change, dragAmount ->
                    change.consume()
                    val row = drag.rowHeightPx
                    if (row <= 0f || drag.position < 0) return@detectDragGesturesAfterLongPress
                    drag.offset += dragAmount.y
                    while (drag.offset > row / 2 && drag.position < drag.lastIndex) {
                        // A refusal here means the queue changed mid-drag; the row settles back.
                        if (!actions.moveEntry(id, drag.position + 1)) { drag.offset = 0f; break }
                        drag.offset -= row
                        drag.position += 1
                    }
                    while (drag.offset < -row / 2 && drag.position > drag.firstUpcoming) {
                        if (!actions.moveEntry(id, drag.position - 1)) { drag.offset = 0f; break }
                        drag.offset += row
                        drag.position -= 1
                    }
                    // The row follows the finger, but never past the range it may land in.
                    drag.offset = drag.offset.coerceIn(
                        -(drag.position - drag.firstUpcoming) * row - row / 2,
                        (drag.lastIndex - drag.position) * row + row / 2)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(DragHandleIcon, stringResource(R.string.queue_drag_handle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The playing entry's equaliser: animated while playing, at rest while paused. */
@Composable
private fun NowPlayingIndicator(playing: Boolean) {
    val playingLabel = stringResource(R.string.up_next_playing)
    val levels = if (playing) {
        val transition = rememberInfiniteTransition(label = "eq")
        List(3) { i ->
            transition.animateFloat(0.25f, 1f,
                infiniteRepeatable(tween(420 + i * 130, easing = LinearEasing), RepeatMode.Reverse),
                label = "eq$i")
        }
    } else null
    Row(Modifier.height(14.dp).semantics { contentDescription = playingLabel },
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0..2) {
            val level = levels?.get(i)?.value ?: (0.3f + i * 0.15f)
            Box(Modifier.width(3.dp).fillMaxHeight(level)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
        }
    }
}

/** An Up Next entry's menu: Move Up and Move Down where the entry can go that way, and Remove. */
@Composable
private fun UpNextEntryMenu(tag: String, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("$tag.menu")) {
            Icon(DulcetIcons.MoreVert, stringResource(SharedR.string.queue_entry_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onMoveUp != null) DropdownMenuItem(
                text = { Text(stringResource(SharedR.string.queue_move_up)) },
                leadingIcon = { Icon(DulcetIcons.ArrowUp, null) },
                onClick = { open = false; onMoveUp() },
                modifier = Modifier.testTag("$tag.menu.moveUp"),
            )
            if (onMoveDown != null) DropdownMenuItem(
                text = { Text(stringResource(SharedR.string.queue_move_down)) },
                leadingIcon = { Icon(DulcetIcons.ArrowDown, null) },
                onClick = { open = false; onMoveDown() },
                modifier = Modifier.testTag("$tag.menu.moveDown"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(SharedR.string.queue_remove)) },
                leadingIcon = { Icon(DulcetIcons.Remove, null) },
                onClick = { open = false; onRemove() },
                modifier = Modifier.testTag("$tag.menu.remove"),
            )
        }
    }
}

/**
 * The playing track's rating, in the More sheet (§16.20): five stars at full touch size, the rating
 * this device knows shown with the tap before any request, and Clear while one is set. Its outcome
 * is said by the heart's message ([NowPlayingFavourite]), which reports every change to the playing
 * track, a rating's included — saying it here too would say it twice.
 */
@Composable
private fun NowPlayingRating(session: LibrarySession, state: AndroidPlaybackState, modifier: Modifier = Modifier) {
    val rawId = state.queue.getOrNull(state.currentIndex ?: -1)?.track?.rawId ?: return
    val target = remember(rawId) { AndroidLibraryEntity(AndroidLibraryEntityKind.Track, rawId) }
    // Null — unknown — stays unknown: shown as no stars said "Rating unknown", never as a 0.
    val rating = rememberWatchedRating(session, target)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RatingStars(rating, { session.setRating(target, it) }, "player.rating",
            onColor = MaterialTheme.colorScheme.primary, offColor = MaterialTheme.colorScheme.onSurfaceVariant,
            starSize = 22.dp, touchSize = TOUCH_TARGET)
        Spacer(Modifier.weight(1f))
        if (rating != null && rating > 0) {
            TextButton(onClick = { session.setRating(target, 0) }, modifier = Modifier.testTag("player.rating.clear")) {
                Text(stringResource(SharedR.string.queue_clear))
            }
        }
    }
}

/** The playing track's More sheet: what it is, its rating, where it comes from, and the listening choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingMoreSheet(
    account: SearchAccount,
    state: AndroidPlaybackState,
    playback: AndroidPlaybackController,
    library: LibrarySession?,
    openAlbum: ((String) -> Unit)?,
    tint: Color,
    dismiss: () -> Unit,
) {
    val track = state.queue.getOrNull(state.currentIndex ?: -1)?.track
    var sleepOpen by remember { mutableStateOf(false) }
    var fadeOpen by remember { mutableStateOf(false) }
    PhoneModalBottomSheet(onDismissRequest = dismiss, containerColor = tint,
        modifier = Modifier.testTag("player.more.sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            ListItem(
                leadingContent = { Artwork(account, state.artworkKey, state.title, 56.dp) },
                headlineContent = {
                    Text(state.title.ifBlank { stringResource(R.string.now_playing_loading) },
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = state.artist?.let { artist -> { Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (library != null && track != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                    Text(stringResource(SharedR.string.library_rating), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    NowPlayingRating(library, state, Modifier.fillMaxWidth())
                }
            }
            if (openAlbum != null && track?.albumRawId != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.player_go_to_album)) },
                    leadingContent = { Icon(DulcetIcons.Album, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.testTag("player.more.album")
                        .clickable { dismiss(); openAlbum(track.albumRawId!!) },
                )
            }
            PhoneListeningSession(state, { sleepOpen = true }, { fadeOpen = true })
            Spacer(Modifier.height(16.dp))
        }
    }
    if (sleepOpen) SleepTimerSheet(state, playback::setSleepTimer) { sleepOpen = false }
    if (fadeOpen) CrossfadeSheet(state, playback::setCrossfade) { fadeOpen = false }
}

/**
 * The playing track's heart (§16.20): the state this device knows for it, the change shown with the
 * tap, and — the player has no room for a line — a short message when a change to it needs words,
 * the same words the library screens use. Nothing while no track is current.
 */
@Composable
private fun NowPlayingFavourite(session: LibrarySession, state: AndroidPlaybackState) {
    val rawId = state.queue.getOrNull(state.currentIndex ?: -1)?.track?.rawId ?: return
    val target = remember(rawId) { AndroidLibraryEntity(AndroidLibraryEntityKind.Track, rawId) }
    val favourite = rememberWatchedFavourite(session, target) == true
    val outcomes by session.outcomes.collectAsState()
    val line = libraryResources().outcomeLines(outcomes, target)
    val context = LocalContext.current
    LaunchedEffect(line) {
        if (line != null) {
            Toast.makeText(context, line, Toast.LENGTH_LONG).show()
            session.dismissOutcome(target)
        }
    }
    FavouriteButton(favourite, "player.favourite", Modifier.size(TOUCH_TARGET)) { session.setFavourite(target, !favourite) }
}
