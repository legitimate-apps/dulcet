package com.legitimateapps.dulcet

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The chip row is where the finger is: an empty list under it has nothing to scroll, so the row cannot hop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port")
class PhoneAlbumsEmptyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply { albumListSize = 0 }
    private val account = SearchAccount("albums-empty-phone", server.url, "u", "p", true)
    private var session: LibrarySession? = null
    private val actions = PhoneActions(openAlbum = {}, openArtist = {}, back = {},
        playAlbum = { _, _, _ -> }, playArtist = { _, _, _ -> null })

    @After fun close() {
        session?.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    @Test fun aChipRowHoldsItsPlaceOverAnEmptyList() {
        var page by mutableStateOf(2000.dp)
        compose.setContent {
            MaterialTheme {
                val current = remember { LibrarySession(context, account, foreground = true) }.also { session = it }
                Box(Modifier.height(page)) { AlbumsGrid(account, current, actions) }
                LibraryLifecycle(current)
            }
        }
        compose.waitUntil(60_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasTestTag("library.albums.empty"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        // A Pixel 6's page: measured when the grid had a 16 dp top padding, it ended 6.5 dp short of where 0.75 of the
        // window took the statement. The grid has no top padding now (the chip row starts under the bar, as on Artists), so
        // the statement starts 16 dp higher on the same page. Nothing past the page may make the empty list scroll.
        val statementTop = compose.onNodeWithTag("library.albums.empty", useUnmergedTree = true).getBoundsInRoot().top
        page = statementTop + 16.dp + (914 * 0.75f).dp - 6.5.dp
        compose.waitForIdle()
        val before = compose.onNodeWithTag("library.albums.filter.all").getBoundsInRoot().top
        compose.onNodeWithTag("library.albums").performScrollToNode(hasTestTag("library.albums.empty"))
        compose.onNodeWithTag("library.albums").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("library.albums.filter.all").getBoundsInRoot().top)
        val range = compose.onNodeWithTag("library.albums").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, range.maxValue(), "an empty list has nothing to scroll")
    }
}
