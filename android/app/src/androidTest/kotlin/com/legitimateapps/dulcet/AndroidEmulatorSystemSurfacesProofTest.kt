package com.legitimateapps.dulcet

import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.legitimateapps.dulcet.emulator.DisposableServerProbe
import com.legitimateapps.dulcet.emulator.PlaybackObserver
import com.legitimateapps.dulcet.emulator.await
import com.legitimateapps.dulcet.emulator.awaitQueuedBroadcastsDelivered
import com.legitimateapps.dulcet.emulator.connectSavedAccount
import com.legitimateapps.dulcet.emulator.requireMediaTimeAdvances
import com.legitimateapps.dulcet.playback.PlaybackService
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

/** Real Binder/Media3 browse and selection, against a disposable server on an emulator. */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorSystemSurfacesProofTest {
    @Test fun mediaBrowserSearchSelectionStartsTheExistingPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val rawId = probe.songId(DisposableServerProbe.CANARY_TITLE)
        awaitQueuedBroadcastsDelivered()
        connectSavedAccount(context, probe)
        fun <T> main(block: () -> T): T {
            var result: T? = null
            instrumentation.runOnMainSync { result = block() }
            @Suppress("UNCHECKED_CAST") return result as T
        }
        fun <T> result(block: () -> ListenableFuture<T>): T = main(block).get(30, TimeUnit.SECONDS)
        val activityIntent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(activityIntent).use {
            PlaybackObserver(context).use { observer ->
                observer.bind()
                val future = main {
                    MediaBrowser.Builder(context, SessionToken(context,
                        ComponentName(context, PlaybackService::class.java))).buildAsync()
                }
                val browser = future.get(30, TimeUnit.SECONDS)
                try {
                    val root = result { browser.getLibraryRoot(null) }
                    check(root.resultCode == LibraryResult.RESULT_SUCCESS)
                    val children = result { browser.getChildren(checkNotNull(root.value).mediaId, 0, 100, null) }
                    check(children.resultCode == LibraryResult.RESULT_SUCCESS)
                    check(checkNotNull(children.value).map { it.mediaMetadata.title.toString() } ==
                        listOf("Library", "Playlists", "Recently Played", "Favourites"))
                    for (folder in checkNotNull(children.value)) {
                        check(result { browser.subscribe(folder.mediaId, null) }.resultCode == LibraryResult.RESULT_SUCCESS)
                    }
                    check(result { browser.search(DisposableServerProbe.CANARY_TITLE, null) }.resultCode == LibraryResult.RESULT_SUCCESS)
                    val search = result { browser.getSearchResult(DisposableServerProbe.CANARY_TITLE, 0, 100, null) }
                    check(search.resultCode == LibraryResult.RESULT_SUCCESS)
                    val selected = checkNotNull(search.value).single {
                        it.mediaMetadata.title.toString() == DisposableServerProbe.CANARY_TITLE
                    }
                    check(selected.mediaMetadata.isPlayable == true)
                    main { browser.setMediaItem(MediaItem.Builder().setMediaId(selected.mediaId).build()); browser.prepare(); browser.play() }
                    await("browser selection to reach the existing queue") { observer.state().let { state -> state.currentIndex?.let { state.queue.getOrNull(it)?.track?.rawId } == rawId } }
                    val position = requireMediaTimeAdvances(observer, "MediaBrowser selection")
                    main { browser.pause() }
                    await("browser pause to reach the player") { !observer.state().playWhenReady }
                    main { browser.play() }
                    requireMediaTimeAdvances(observer, "MediaBrowser resume")
                    println("ANDROID SYSTEM SURFACES OBSERVED binder-browser=true folders=4 subscriptions=4 " +
                        "search-selection=true raw-id-match=true advancing-ms=$position pause-resume=true")
                } finally { main { browser.release() } }
            }
        }
    }
}
