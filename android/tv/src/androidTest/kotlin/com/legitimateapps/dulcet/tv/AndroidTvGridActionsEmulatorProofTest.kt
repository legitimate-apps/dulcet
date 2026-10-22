package com.legitimateapps.dulcet.tv

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.emulator.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Remote-only full-grid options, actual membership write and queue action on the running TV app. */
@RunWith(AndroidJUnit4::class)
class AndroidTvGridActionsEmulatorProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun gridMoreOptionsAddsRemovesAndQueuesTheActualAlbum() {
        val probe = DisposableServerProbe.fromInstrumentation()
        val extensions = probe.call("getOpenSubsonicExtensions").getJSONArray("openSubsonicExtensions")
        assumeTrue("Grid membership proof requires advertised dulcetLibrary v1", (0 until extensions.length()).any { index ->
            val extension = extensions.getJSONObject(index)
            extension.getString("name") == "dulcetLibrary" && (0 until extension.getJSONArray("versions").length())
                .any { version -> extension.getJSONArray("versions").getInt(version) == 1 }
        })
        AndroidTvGridActionsProof(compose).run()
    }
}

/** The required Dulcet My Library proof also exercises these full-grid actions. */
class AndroidTvGridActionsProof(private val compose: ComposeTestRule) {
    fun run() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback"))
        val probe = DisposableServerProbe.fromInstrumentation()
        val albums = probe.call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500"))
            .getJSONObject("albumList2").getJSONArray("album")
        val album = (0 until albums.length()).map(albums::getJSONObject).first { it.getString("name") == "Threshold Boundary" }
        val id = album.getString("id")
        val expectedSongs = probe.call("getAlbum", mapOf("id" to id)).getJSONObject("album").getJSONArray("song")
            .let { songs -> (0 until songs.length()).map { songs.getJSONObject(it).getString("id") } }
        fun member(): Boolean = probe.call("getDulcetLibrary", mapOf("type" to "album"))
            .getJSONObject("dulcetLibrary").optJSONArray("album")?.let { inLibrary ->
                (0 until inLibrary.length()).any { inLibrary.getJSONObject(it).getString("id") == id }
            } == true
        // Own this one fixture membership; leave the shared account as it was.
        val wasMember = member()
        if (wasMember) probe.call("removeFromDulcetLibrary", mapOf("id" to id))
        try {
            PlaybackObserver(context).use { observer ->
                awaitQueuedBroadcastsDelivered()
                connectSavedAccount(context, probe)
                val intent = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)!!
                ActivityScenario.launch<Activity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
                    awaitTag("listenNow.surface")
                    select("browse.open")
                    select("library.view.albums")
                    awaitTag("library.albums.item.0")
                    observer.bind()
                    compose.onNodeWithTag("library.albums").performScrollToNode(hasText("Threshold Boundary"))
                    val card = compose.onNode(hasText("Threshold Boundary") and SemanticsMatcher("album card") {
                        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.matches(Regex("library\\.albums\\.item\\.[0-9]+"))
                    })
                    val tag = card.fetchSemanticsNode().config[SemanticsProperties.TestTag]
                    card.performSemanticsAction(SemanticsActions.RequestFocus)
                    compose.waitForIdle()
                    // The More target lies directly below the focused cover, inside its grid cell.
                    remote(KeyEvent.KEYCODE_DPAD_DOWN)
                    compose.onNodeWithTag("$tag.more").assertIsFocused()
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    compose.onNodeWithTag("$tag.actions.membership").assertIsFocused()
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("real server full-grid Add to Library") { member() }
                    compose.onNodeWithTag("$tag.more").assertIsFocused()
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    compose.onNodeWithTag("$tag.actions.membership").assertTextEquals("Remove from Library")
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    await("real server full-grid Remove from Library") { !member() }
                    compose.onNodeWithTag("$tag.more").assertIsFocused()
                    remote(KeyEvent.KEYCODE_DPAD_CENTER)
                    select("$tag.actions.playNext")
                    await("actual album queued from full grid") { observer.state().queue.map { it.track.rawId } == expectedSongs }
                    check(observer.state().queue.isNotEmpty())
                    observer.stopPlayback()
                    println("TV GRID ACTIONS OBSERVED remote-card-down-more=true menu-open=true add-server=true remove-server=true queue-album=true focus-restored=true")
                }
            }
        } finally {
            if (wasMember) probe.call("addToDulcetLibrary", mapOf("id" to id))
            else probe.call("removeFromDulcetLibrary", mapOf("id" to id))
        }
    }
    private fun awaitTag(tag: String) = compose.waitUntil("$tag exists", 30_000) {
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun select(tag: String) {
        awaitTag(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
        remote(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun remote(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }
}
