package com.legitimateapps.dulcet.tv

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.PlaybackObserver
import com.legitimateapps.dulcet.emulator.await
import com.legitimateapps.dulcet.emulator.awaitQueuedBroadcastsDelivered
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import com.legitimateapps.dulcet.emulator.requireMediaTimeAdvances
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV app's library on an Android TV emulator, driven with remote keys: the app opens on the
 * library, with focus on the bar's Library tab or the first card and no on-screen keyboard; in
 * search, the field is reached with DOWN and brings up the keyboard only when selected; RIGHT from
 * Search reaches Library; the albums screen opens; an album with several tracks opens with focus on Play; the centre key plays it. Required:
 * the production service queues
 * every track of the album in the server's order, media time advances on the first, and the TV's
 * Now Playing is in front. The screen's semantics only say where things are; every action is a key
 * event delivered to the focused window, as a remote's is.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTvEmulatorLibraryAlbumPlayProofTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun anAlbumOpenedFromTheLibraryWithTheRemotePlaysAsAMultiTrackQueue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageManager.hasSystemFeature("android.software.leanback")) {
            "This proof must run on an Android TV device or emulator"
        }
        val probe = DisposableServerProbe.fromInstrumentation()
        val expected = probe.albumSongIds(ALBUM)
        check(expected.size >= 2) { "The fixture album $ALBUM must hold several tracks, found ${expected.size}" }
        awaitQueuedBroadcastsDelivered()
        connectSavedAccount(context, probe)
        PlaybackObserver(context).use { observer ->
            val launch = Intent(Intent.ACTION_MAIN).setClassName(context, TvSearchActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<TvSearchActivity>(launch).use { scenario ->
                // Launch: the library, with the remote on the bar's Library tab or, once the home
                // rows arrive, their first card -- never on Sign out -- and no on-screen keyboard,
                // which would take the D-pad until Back closed it.
                awaitNode("the library at launch, the remote on it") {
                    exists("library.surface") && (focused("library.open") || focused("library.home.0.item.0"))
                }
                check(!exists("search.query")) { "The app must not open on search" }
                check(!keyboardShown(scenario)) { "No on-screen keyboard at launch" }
                // Search, from the bar: the remote rests on its tab, not in the field.
                focus("search.open")
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitNode("search open, the remote on its tab") { exists("search.query") && focused("search.open") }
                check(!focused("search.query")) { "The search field must not take focus when search opens" }
                check(!keyboardShown(scenario)) { "No on-screen keyboard when search opens" }
                // DOWN reaches the field without the keyboard; selecting it with the centre key asks
                // for the keyboard, and Back closes it and leaves the field focused.
                remote(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitNode("DOWN reaches the search field") { focused("search.query") }
                check(!keyboardShown(scenario)) { "Moving onto the field must not bring up the keyboard" }
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitNode("the centre key on the field brings up the keyboard") { keyboardShown(scenario) }
                remote(KeyEvent.KEYCODE_BACK)
                awaitNode("Back closes the keyboard and stays on the field") {
                    !keyboardShown(scenario) && focused("search.query")
                }
                remote(KeyEvent.KEYCODE_DPAD_UP)
                awaitNode("UP from the field returns to Search in the navigation row") { focused("search.open") }
                remote(KeyEvent.KEYCODE_DPAD_RIGHT)
                awaitNode("RIGHT reaches Library") { focused("library.open") }
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitNode("the library") { exists("library.view.albums") }

                focus("library.view.albums")
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitNode("the albums screen") { exists("library.albums.item.0") }
                compose.onNodeWithTag("library.albums").performScrollToNode(hasText(ALBUM))
                focus(cardTitled(ALBUM))
                remote(KeyEvent.KEYCODE_DPAD_CENTER)
                awaitNode("$ALBUM open with focus on Play", 60_000) { focused("album.play") }
                remote(KeyEvent.KEYCODE_DPAD_CENTER)

                observer.bind()
                await("the album queued") { observer.state().queue.size == expected.size }
                val state = observer.state()
                check(state.queue.map { it.track.rawId } == expected) {
                    "The queue must be $ALBUM in the server's order: ${state.queue.map { it.track.rawId }} vs $expected"
                }
                check(state.currentIndex == 0) { "Play starts at the first track, not ${state.currentIndex}" }
                val position = requireMediaTimeAdvances(observer, "album play")
                await("the TV's Now Playing in front") { resumed() == TvPlaybackActivity::class.java.name }
                println("ANDROID TV EMULATOR LIBRARY OBSERVED leanback=true album=$ALBUM queue=${state.queue.size} " +
                    "current=${state.currentIndex} media-ms=$position front=${resumed()}")
            }
        }
    }

    private fun remote(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        compose.waitForIdle()
    }

    private fun focus(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        check(focused(tag)) { "Could not focus $tag" }
    }

    private fun exists(tag: String) = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag))
        .fetchSemanticsNodes().isNotEmpty()

    private fun focused(tag: String) = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag),
        useUnmergedTree = true).fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun cardTitled(title: String): String = compose.onAllNodes(hasText(title) and SemanticsMatcher("an album card") {
        it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("library.albums.item.")
    }).fetchSemanticsNodes().firstOrNull()?.config?.get(SemanticsProperties.TestTag) ?: error("No album card titled $title")

    private fun awaitNode(what: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) =
        compose.waitUntil(what, timeoutMillis) { runCatching(condition).getOrDefault(false) }

    private fun keyboardShown(scenario: ActivityScenario<TvSearchActivity>): Boolean {
        var shown = false
        scenario.onActivity { activity ->
            shown = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private fun resumed(): String? {
        var name: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            name = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()?.javaClass?.name
        }
        return name
    }

    private companion object {
        /** Three tracks of about thirty seconds each in the fixture corpus: long enough for media time to advance on the first. */
        const val ALBUM = "Threshold Boundary"
    }
}

/** The ids of the album titled [name]'s songs, in the server's order. */
private fun DisposableServerProbe.albumSongIds(name: String): List<String> {
    val albums = call("getAlbumList2", mapOf("type" to "alphabeticalByName", "size" to "500"))
        .getJSONObject("albumList2").getJSONArray("album")
    val id = (0 until albums.length()).map(albums::getJSONObject).single { it.getString("name") == name }.getString("id")
    val songs = call("getAlbum", mapOf("id" to id)).getJSONObject("album").getJSONArray("song")
    return (0 until songs.length()).map { songs.getJSONObject(it).getString("id") }
}
