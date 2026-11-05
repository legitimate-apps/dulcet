package com.legitimateapps.dulcet.tv

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.tv.material3.MaterialTheme
import com.legitimateapps.dulcet.core.AndroidLibraryReader
import com.legitimateapps.dulcet.library.LibrarySession
import com.legitimateapps.dulcet.library.LibraryLifecycle
import com.legitimateapps.dulcet.search.SearchAccount
import com.legitimateapps.dulcet.search.conformance.LoopbackLibraryServer
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An album's notice is the page's: above both columns, where a remote resting on Play cannot scroll it under the tabs. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-television")
class TvAlbumNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = RuntimeEnvironment.getApplication()
    private val server = LoopbackLibraryServer().apply { refuseCredentials = true }
    private val account = SearchAccount("tv-album-notice", server.url, "u", "p", true)
    private lateinit var session: LibrarySession

    @After fun close() {
        if (::session.isInitialized) session.close()
        var closed = false
        AndroidLibraryReader.closeCurrent { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        server.close()
        context.deleteDatabase("dulcet.db")
    }

    @Test fun aRefusedPasswordIsSaidAcrossThePageAboveItsColumns() {
        compose.setContent {
            MaterialTheme {
                session = remember { LibrarySession(context, account, true) }
                CompositionLocalProvider(LocalTvRouteFocus provides remember { TvRouteFocus() }) {
                    TvAlbumScreen(account, session, null, null, "album-1", TvNavigator({}, {}))
                }
                LibraryLifecycle(session)
            }
        }
        compose.waitUntil(60_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(hasTestTag("library.connection"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val line = compose.onNodeWithTag("library.connection", useUnmergedTree = true).getBoundsInRoot()
        val page = compose.onNodeWithTag("album.surface").getBoundsInRoot()
        assertTrue(line.width > 440.dp, "the notice runs across the page; its line is ${line.width} wide")
        assertTrue(page.top >= line.bottom, "the columns start at ${page.top}, under the notice ending at ${line.bottom}")
    }
}
