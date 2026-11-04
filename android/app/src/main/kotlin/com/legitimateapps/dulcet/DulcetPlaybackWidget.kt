package com.legitimateapps.dulcet

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.legitimateapps.dulcet.playback.PlaybackIntents
import com.legitimateapps.dulcet.playback.PlaybackService
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** User-initiated controls connect to the existing authenticated media session. */
class DulcetPlaybackWidget : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DulcetGlanceWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // A session still running from the last update has already read the saved state.
        DulcetGlanceWidget.revision.update { it + 1 }
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != TOGGLE) return
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())
        // onReceive receives a ReceiverRestrictedContext, which cannot bind services.
        // The async controller connection must outlive that callback on the app context.
        val appContext = context.applicationContext
        val future = MediaController.Builder(appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))).buildAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) { MediaController.releaseFuture(future); pending.finish() } }
        main.postDelayed({ finish() }, 8000)
        future.addListener({
            runCatching {
                val controller = future.get()
                if (controller.playWhenReady) controller.pause() else controller.play()
            }
            // Media3 takes foreground ownership asynchronously as playback starts.
            main.postDelayed({ finish() }, 2000)
        }, Executor { main.post(it) })
    }

    companion object {
        const val TOGGLE = "com.legitimateapps.dulcet.widget.TOGGLE"
    }
}

/** What the widget shows, read from what the playback session last saved for it. */
internal data class DulcetWidgetSnapshot(
    val hasSession: Boolean,
    val reconnect: Boolean,
    val playing: Boolean,
    val title: String,
    val subtitle: String,
    val art: Bitmap?,
) {
    companion object {
        fun read(context: Context): DulcetWidgetSnapshot {
            val state = context.getSharedPreferences("dulcet.widget", Context.MODE_PRIVATE)
            val hasSession = state.getBoolean("hasSession", false)
            val reconnect = state.getBoolean("needsReconnect", false)
            return DulcetWidgetSnapshot(
                hasSession = hasSession,
                reconnect = reconnect,
                playing = state.getBoolean("playing", false),
                title = (if (hasSession) state.getString("title", null)?.takeIf { it.isNotBlank() } else null)
                    ?: context.getString(R.string.app_name),
                subtitle = when {
                    !hasSession -> context.getString(R.string.widget_open_library)
                    reconnect -> context.getString(R.string.widget_reconnect)
                    else -> state.getString("subtitle", "").orEmpty()
                },
                art = if (hasSession) runCatching {
                    BitmapFactory.decodeFile(File(context.filesDir, "dulcet-widget-art.jpg").path)
                }.getOrNull() else null,
            )
        }
    }
}

internal class DulcetGlanceWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(SIZES)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        provideContent {
            val current by revision.collectAsState()
            val snapshot = remember(current) { DulcetWidgetSnapshot.read(appContext) }
            val open = appContext.let {
                actionStartActivity(if (snapshot.hasSession) PlaybackIntents.showNowPlaying(it)
                    else it.packageManager.getLaunchIntentForPackage(it.packageName) ?: Intent(it, MainActivity::class.java))
            }
            val toggle = actionSendBroadcast(Intent(appContext, DulcetPlaybackWidget::class.java)
                .setAction(DulcetPlaybackWidget.TOGGLE))
            GlanceTheme { DulcetWidgetContent(snapshot, open, toggle) }
        }
    }

    companion object {
        val COMPACT = DpSize(180.dp, 96.dp)
        val WIDE = DpSize(250.dp, 96.dp)
        val TALL = DpSize(250.dp, 180.dp)
        private val SIZES = setOf(COMPACT, WIDE, TALL)
        val revision = MutableStateFlow(0)
    }
}

private val WIDGET_PADDING = 12.dp
private val WIDGET_LINE_GAP = 4.dp
private val WIDGET_TALL_ART = 64.dp
private val WIDGET_STACK_GAP = 8.dp
/** Line heights at font scale 1, generous enough that an estimate never clips a word. */
private const val WIDGET_TITLE_LINE = 22f
private const val WIDGET_SUBTITLE_LINE = 20f

/** How many lines of title and subtitle fit; the title always wins the room it needs. */
internal fun dulcetWidgetLines(textHeight: Dp, fontScale: Float, shortTitle: Boolean): Pair<Int, Int> {
    val titleLine = WIDGET_TITLE_LINE * fontScale
    val subtitleLine = WIDGET_SUBTITLE_LINE * fontScale
    val title = if (shortTitle) 1 else (textHeight.value / titleLine).toInt().coerceIn(1, 2)
    val left = textHeight.value - title * titleLine - WIDGET_LINE_GAP.value
    val subtitle = (left / subtitleLine).toInt().coerceIn(if (shortTitle) 1 else 0, 2)
    return title to subtitle
}

/**
 * A short host is one row: a wide one adds the art, at the height it has; a two-cell one keeps the
 * text and the 48 dp control. A tall host stacks instead, so the text takes the whole width and
 * every edge has the same inset: art and control on top, the text at the bottom.
 */
@Composable
internal fun DulcetWidgetContent(snapshot: DulcetWidgetSnapshot, open: Action, toggle: Action) {
    val size = LocalSize.current
    val colors = GlanceTheme.colors
    val fontScale = LocalContext.current.resources.configuration.fontScale
    val shortTitle = !snapshot.hasSession || snapshot.reconnect
    val height = size.height - WIDGET_PADDING * 2
    val root = GlanceModifier.fillMaxSize().appWidgetBackground().background(colors.widgetBackground).cornerRadius(24.dp)
        .padding(WIDGET_PADDING).clickable(open)
    if (size.height >= DulcetGlanceWidget.TALL.height) {
        Column(root) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DulcetWidgetArt(snapshot, WIDGET_TALL_ART)
                Spacer(GlanceModifier.defaultWeight())
                DulcetWidgetToggle(snapshot, toggle)
            }
            Spacer(GlanceModifier.defaultWeight())
            val (titleLines, subtitleLines) = dulcetWidgetLines(height - WIDGET_TALL_ART - WIDGET_STACK_GAP, fontScale, shortTitle)
            DulcetWidgetText(snapshot, titleLines, subtitleLines)
        }
    } else {
        val (titleLines, subtitleLines) = dulcetWidgetLines(height, fontScale, shortTitle)
        Row(root, verticalAlignment = Alignment.CenterVertically) {
            if (size.width >= DulcetGlanceWidget.WIDE.width) {
                DulcetWidgetArt(snapshot, height.coerceAtLeast(56.dp))
                Spacer(GlanceModifier.width(12.dp))
            }
            Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                DulcetWidgetText(snapshot, titleLines, subtitleLines)
            }
            if (snapshot.hasSession) Spacer(GlanceModifier.width(8.dp))
            DulcetWidgetToggle(snapshot, toggle)
        }
    }
}

@Composable
private fun DulcetWidgetArt(snapshot: DulcetWidgetSnapshot, art: Dp) {
    val colors = GlanceTheme.colors
    val frame = GlanceModifier.size(art).cornerRadius(12.dp).semantics { testTag = "widget.art" }
    if (snapshot.art != null) {
        Image(ImageProvider(snapshot.art), null, frame, contentScale = ContentScale.Crop)
    } else {
        Box(frame.background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
            Image(ImageProvider(R.drawable.widget_artwork), null, GlanceModifier.size(art / 2),
                colorFilter = ColorFilter.tint(colors.onSecondaryContainer))
        }
    }
}

@Composable
private fun DulcetWidgetText(snapshot: DulcetWidgetSnapshot, titleLines: Int, subtitleLines: Int) {
    val colors = GlanceTheme.colors
    Text(snapshot.title, GlanceModifier.semantics { testTag = "widget.title" }, maxLines = titleLines,
        style = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium))
    if (subtitleLines > 0 && snapshot.subtitle.isNotEmpty()) {
        Spacer(GlanceModifier.height(WIDGET_LINE_GAP))
        Text(snapshot.subtitle, GlanceModifier.semantics { testTag = "widget.subtitle" }, maxLines = subtitleLines,
            style = TextStyle(color = colors.onSurfaceVariant, fontSize = 14.sp))
    }
}

@Composable
private fun DulcetWidgetToggle(snapshot: DulcetWidgetSnapshot, toggle: Action) {
    if (!snapshot.hasSession) return
    val colors = GlanceTheme.colors
    val label = LocalContext.current.getString(if (snapshot.playing) R.string.widget_pause else R.string.widget_play)
    // The label sits on the clickable view itself, so a screen reader announces and presses one node.
    val button = GlanceModifier.size(48.dp).background(colors.secondaryContainer).cornerRadius(24.dp).padding(12.dp)
        .semantics { testTag = "widget.toggle" }
    Image(ImageProvider(if (snapshot.playing) R.drawable.widget_pause else R.drawable.widget_play), label,
        if (snapshot.reconnect) button else button.clickable(toggle),
        colorFilter = ColorFilter.tint(if (snapshot.reconnect) colors.onSurfaceVariant else colors.onSecondaryContainer))
}
