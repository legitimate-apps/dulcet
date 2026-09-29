package com.legitimateapps.dulcet.emulator

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.AndroidAccountCredentialStore
import com.legitimateapps.dulcet.core.AndroidQueueSource
import com.legitimateapps.dulcet.core.AndroidTrack
import org.junit.Assume.assumeTrue

/**
 * The automatic-skip proof (spec §12.12) on an emulator, shared by the phone and TV apps.
 *
 * It needs the opt-in "No Audio Skip Probe" album, which `tools/seed-skip-probe` adds to a
 * disposable server that has already passed its health check: an MP3 file holding a tag and no
 * audio frame, which Media3's extractors cannot recognise, then a 40-second tone. Not the "Skip Probe" album the Apple proof uses: Android's software MP3 decoder
 * plays that album's undecodable frames as sound and reports no error, so nothing there is skipped.
 * The album is not part of the default corpus and core-ci does not add it yet, so
 * the proof runs only when the runner passes `dulcetSkipProbe=true`; otherwise it is reported as
 * SKIPPED, never as passed. Once requested, a missing album or server is a failure.
 */
object SkipProbeProof {
    const val ALBUM = "No Audio Skip Probe"
    const val UNPLAYABLE = "No Audio Probe"
    const val PLAYABLE = "Playable After No Audio"
    const val SENTENCE = "Couldn’t play “No Audio Probe”. Skipped."

    fun requireRequested() {
        assumeTrue("Opt-in: run tools/seed-skip-probe, then pass dulcetSkipProbe=true",
            InstrumentationRegistry.getArguments().getString("dulcetSkipProbe") == "true")
    }

    /**
     * Connects, starts the album from its first track, which holds no audio, through the production
     * controller -- the call an album row makes -- and requires: the notice naming that track in the
     * accessibility tree as one polite live region; the queue on the next track with no failure
     * line; media time advancing on it; the notice gone within its time; and the server recording
     * a play for the next track and none for the skipped one. [placement] checks where the notice
     * sits on this surface. Returns the evidence line it printed.
     */
    fun run(surface: String, launch: () -> AutoCloseable, placement: (notice: Rect, root: AccessibilityNodeInfo) -> String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        val unplayable = probe.songId(UNPLAYABLE)
        val playable = probe.songId(PLAYABLE)
        val albumId = probe.call("getSong", mapOf("id" to unplayable)).getJSONObject("song").getString("albumId")
        val songs = probe.call("getAlbum", mapOf("id" to albumId)).getJSONObject("album").getJSONArray("song")
        val order = (0 until songs.length()).map { songs.getJSONObject(it).getString("id") }
        check(order == listOf(unplayable, playable)) { "The $ALBUM album must hold exactly its two tracks in order: $order" }
        val skippedBefore = probe.playCount(unplayable)
        val nextBefore = probe.playCount(playable)
        awaitQueuedBroadcastsDelivered()
        connectSavedAccount(context, probe)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        return PlaybackObserver(context).use { observer ->
            launch().use {
                observer.bind()
                observer.onController { playback ->
                    playback.playQueue(listOf(AndroidTrack(account.id, unplayable, UNPLAYABLE),
                        AndroidTrack(account.id, playable, PLAYABLE)), 0, AndroidQueueSource.Album, ALBUM, albumId)
                }
                val requested = SystemClock.elapsedRealtime()
                var bounds: Rect? = null
                var where = ""
                var activePackage: CharSequence? = null
                try { await("the skip notice in the accessibility tree", timeoutMillis = 30_000) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                    activePackage = root.packageName
                    val node = find(root) { it.contentDescription?.toString() == SENTENCE } ?: return@await false
                    check(node.liveRegion == android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE) {
                        "The notice must be a polite live region, so TalkBack announces it; was ${node.liveRegion}"
                    }
                    check(node.childCount == 0) { "The notice must be read as one sentence, not ${node.childCount} parts" }
                    bounds = Rect().also(node::getBoundsInScreen)
                    where = placement(bounds!!, root)
                    true
                } } catch (timeout: IllegalStateException) {
                    // Another process's dialog (a system "not responding" prompt on a loaded
                    // emulator) takes the active window and hides the app's tree: say so.
                    throw IllegalStateException("${timeout.message}; the active window last belonged to $activePackage", timeout)
                }
                val shown = SystemClock.elapsedRealtime()
                val state = observer.state()
                // The handler's own marker: only the skip path writes a notice (trap 31).
                check(state.skipNotice?.title == UNPLAYABLE) { "No skip notice for the unplayable track: ${state.skipNotice}" }
                check(state.error == null) { "The failure line stayed for a track that is not playing: ${state.error}" }
                check(state.currentIndex == 1) { "The queue did not move on: index ${state.currentIndex}" }
                await("the next track to be current") { observer.state().title == PLAYABLE }
                val media = requireMediaTimeAdvances(observer, "$surface after the skip")
                await("the notice to go", timeoutMillis = 20_000) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                    find(root) { it.contentDescription?.toString() == SENTENCE } == null
                }
                val gone = SystemClock.elapsedRealtime()
                check(observer.state().error == null) { "A failure appeared after the skip: ${observer.state().error}" }
                await("the next track's play on the server", timeoutMillis = 120_000) { probe.playCount(playable) >= nextBefore + 1 }
                val skippedAfter = probe.playCount(unplayable)
                check(skippedAfter == skippedBefore) { "The skipped track gained a play: $skippedBefore -> $skippedAfter" }
                val line = "ANDROID SKIP PROOF OBSERVED surface=$surface notice-after-ms=${shown - requested} " +
                    "notice-visible-ms~=${gone - shown} notice-bounds=${bounds!!.toShortString()} $where media-ms=$media " +
                    "skipped-plays=$skippedBefore->$skippedAfter next-plays=$nextBefore->${probe.playCount(playable)}"
                println(line)
                line
            }
        }
    }

    fun find(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (match(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return null
    }

    fun boundsOf(root: AccessibilityNodeInfo, text: String): List<Rect> {
        val found = mutableListOf<Rect>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            // A merged node (a clickable bar, a tab) carries its children's text joined together.
            if (node.text?.toString()?.contains(text) == true) found += Rect().also(node::getBoundsInScreen)
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return found
    }
}
