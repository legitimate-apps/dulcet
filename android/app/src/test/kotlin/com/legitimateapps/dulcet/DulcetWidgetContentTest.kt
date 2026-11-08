package com.legitimateapps.dulcet

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.assertHasClickAction
import androidx.glance.testing.unit.assertHasContentDescription
import androidx.glance.testing.unit.assertHasNoClickAction
import androidx.glance.testing.unit.assertHasText
import androidx.glance.testing.unit.hasTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DulcetWidgetContentTest {
    private val empty = DulcetWidgetSnapshot(false, false, false, "Dulcet", "Open your library", null)
    private val playing = DulcetWidgetSnapshot(true, false, true, "A song", "An artist",
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))

    private fun render(snapshot: DulcetWidgetSnapshot, size: DpSize, check: androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest(timeout = 30.seconds) {
            setContext(RuntimeEnvironment.getApplication())
            setAppWidgetSize(size)
            provideComposable {
                DulcetWidgetContent(snapshot, actionStartActivity(Intent()), actionSendBroadcast(Intent()))
            }
            check()
        }

    @Test fun emptyAndPlayingShareOneArtFrameAtAWideSize() {
        render(empty, DulcetGlanceWidget.WIDE) {
            onNode(hasTestTag("widget.art")).assertExists()
            onNode(hasTestTag("widget.title")).assertHasText("Dulcet")
            onNode(hasTestTag("widget.subtitle")).assertHasText("Open your library")
            onNode(hasTestTag("widget.toggle")).assertDoesNotExist()
        }
        render(playing, DulcetGlanceWidget.WIDE) {
            onNode(hasTestTag("widget.art")).assertExists()
            onNode(hasTestTag("widget.title")).assertHasText("A song")
            onNode(hasTestTag("widget.toggle")).assertHasContentDescription("Pause").assertHasClickAction()
        }
    }

    @Test fun aTwoCellHostKeepsTitleAndControlWithoutArt() {
        render(playing, DulcetGlanceWidget.COMPACT) {
            onNode(hasTestTag("widget.art")).assertDoesNotExist()
            onNode(hasTestTag("widget.title")).assertHasText("A song")
            onNode(hasTestTag("widget.toggle")).assertExists()
        }
    }

    @Test fun whileReconnectingTheControlIsVisibleButDoesNothing() {
        render(playing.copy(reconnect = true, subtitle = "Open Dulcet to reconnect"), DulcetGlanceWidget.WIDE) {
            onNode(hasTestTag("widget.subtitle")).assertHasText("Open Dulcet to reconnect")
            onNode(hasTestTag("widget.toggle")).assertHasNoClickAction()
        }
    }

    @Test fun theTitleKeepsItsRoomAsTheFontGrows() {
        // 96 dp tall, 72 dp of text: at font scale 1 both fit; at scale 2 the subtitle gives way first.
        assertEquals(2 to 1, dulcetWidgetLines(72.dp, 1f, shortTitle = false))
        assertEquals(1 to 0, dulcetWidgetLines(72.dp, 2f, shortTitle = false))
        // A short title (empty, reconnect) needs the subtitle, which is the message.
        assertEquals(1 to 1, dulcetWidgetLines(72.dp, 2f, shortTitle = true))
        assertEquals(2 to 2, dulcetWidgetLines(136.dp, 1f, shortTitle = false))
        // The tall widget at scale 2: a title's second line never costs the artist their only line.
        assertEquals(1 to 1, dulcetWidgetLines(130.dp, 2f, shortTitle = false))
        // The tall bucket leaves 84 dp of text at scale 2, which the estimate calls too little for an artist.
        assertEquals(1 to 0, dulcetWidgetLines(84.dp, 2f, shortTitle = false))
        assertEquals(1 to 1, dulcetTallWidgetLines(84.dp, 2f, shortTitle = false))
        assertEquals(2 to 1, dulcetTallWidgetLines(84.dp, 1f, shortTitle = false))
    }
}
