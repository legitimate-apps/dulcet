package com.legitimateapps.dulcet

import android.content.ComponentName
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
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
                    // Deliver through Android's actual receiver boundary even in the regular
                    // CI path. Passing a ReceiverRestrictedContext to MediaController used to
                    // crash here; an in-process onReceive call would miss that regression.
                    context.sendBroadcast(Intent(context, DulcetPlaybackWidget::class.java)
                        .setAction(DulcetPlaybackWidget.TOGGLE))
                    await("widget receiver pause to reach the existing player") { !observer.state().playWhenReady }
                    main { browser.play() }
                    requireMediaTimeAdvances(observer, "resume after widget receiver")
                    println("ANDROID SYSTEM SURFACES OBSERVED binder-browser=true folders=4 subscriptions=4 " +
                        "search-selection=true raw-id-match=true advancing-ms=$position pause-resume=true widget-receiver=true")

                    // The launcher owns the host and renders the production RemoteViews. The
                    // platform pin request keeps this independent of launcher grid coordinates.
                    if (InstrumentationRegistry.getArguments().getString("dulcetSystemWidgetProof") == "true") {
                        main { browser.seekTo(0) }
                        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        }
                        val widgets = AppWidgetManager.getInstance(context)
                        check(widgets.isRequestPinAppWidgetSupported) { "This emulator launcher does not support pinned widgets" }
                        val provider = ComponentName(context, DulcetPlaybackWidget::class.java)
                        val oldIds = widgets.getAppWidgetIds(provider).toSet()
                        check(main { widgets.requestPinAppWidget(provider, null, null) })
                        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                            if (node == null) return null
                            if (predicate(node)) return node
                            for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
                            return null
                        }
                        fun click(node: AccessibilityNodeInfo): Boolean {
                            var candidate: AccessibilityNodeInfo? = node
                            while (candidate != null) {
                                if (candidate.isClickable) return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                candidate = candidate.parent
                            }
                            return false
                        }
                        await("launcher widget pin confirmation") {
                            val root = instrumentation.uiAutomation.rootInActiveWindow
                            val add = find(root) { node ->
                                node.packageName?.toString()?.let { it.contains("launcher") } == true &&
                                    node.text?.toString() in listOf("Add", "Add automatically", "Add to home screen")
                            }
                            add != null && click(add)
                        }
                        await("launcher to bind the production widget") { widgets.getAppWidgetIds(provider).any { it !in oldIds } }
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
                        ).use { it.readBytes() }
                        await("home widget to render the real current title") {
                            find(instrumentation.uiAutomation.rootInActiveWindow) {
                                it.isVisibleToUser && it.text?.toString() == DisposableServerProbe.CANARY_TITLE &&
                                    it.viewIdResourceName?.endsWith("/widget_title") == true
                            } != null
                        }
                        // Pinning may animate to a new launcher page after its AX title appears.
                        // Retain the settled Home host, rather than an intermediate transition.
                        instrumentation.uiAutomation.waitForIdle(500, 10_000)
                        check(find(instrumentation.uiAutomation.rootInActiveWindow) {
                            it.isVisibleToUser && it.text?.toString() == DisposableServerProbe.CANARY_TITLE &&
                                it.viewIdResourceName?.endsWith("/widget_title") == true
                        } != null) { "The current Home host must still show the canary after settling" }
                        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        val scale = 800.0 / maxOf(screenshot.width, screenshot.height)
                        val preview = android.graphics.Bitmap.createScaledBitmap(screenshot,
                            (screenshot.width * scale).toInt(), (screenshot.height * scale).toInt(), true)
                        java.io.FileOutputStream(java.io.File(context.getExternalFilesDir(null), "system-surfaces-widget.jpg")).use {
                            check(preview.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, it))
                        }
                        if (preview !== screenshot) preview.recycle()
                        screenshot.recycle()
                        await("home widget Pause button") {
                            val pause = find(instrumentation.uiAutomation.rootInActiveWindow) {
                                it.isVisibleToUser && it.text?.toString() == "Pause" && it.viewIdResourceName?.endsWith("/widget_toggle") == true
                            }
                            pause != null && click(pause)
                        }
                        await("widget pause to reach the existing player") { !observer.state().playWhenReady }
                        println("ANDROID SYSTEM SURFACES OBSERVED launcher-widget=true current-title=true widget-pause=true")
                    }
                } finally { main { browser.release() } }
            }
        }
    }
}
