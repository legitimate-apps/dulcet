package com.legitimateapps.dulcet

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legitimateapps.dulcet.core.SleepTimerChoice
import com.legitimateapps.dulcet.emulator.*
import com.legitimateapps.dulcet.playback.PlaybackIntents
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production UI proof; requires a disposable Navidrome and a clean installation. */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorListeningSessionProofTest {
    @Test fun autoplayAndSleepTimerRunThroughThePhoneUiAndSurviveHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = DisposableServerProbe.fromInstrumentation()
        awaitQueuedBroadcastsDelivered()
        context.getSharedPreferences("listening-session", Context.MODE_PRIVATE).edit().clear().commit()
        connectSavedAccount(context, probe)
        val account = checkNotNull(AndroidAccountCredentialStore(context).load())
        val rawId = probe.songId(DisposableServerProbe.CANARY_TITLE)
        val launch = PlaybackIntents.playTrack(context, account.id, rawId, DisposableServerProbe.CANARY_TITLE)
            .setClassName(context, PLAYBACK_ENTRY_ALIAS)
        PlaybackObserver(context).use { observer ->
            ActivityScenario.launch<MainActivity>(launch).use {
                observer.bind()
                requireMediaTimeAdvances(observer, "listening-session")
                await("AutoPlay server candidates after the user's entry") { observer.state().queue.any { it.isAutoplay } }
                val initial = observer.state()
                check(initial.queue.first().track.rawId == rawId && !initial.queue.first().isAutoplay)
                check(initial.queue.drop(1).all { it.isAutoplay })
                tapLabel("Up Next")
                await("AutoPlay heading in Up Next") { nodes().count { it.text?.toString() == "AutoPlay" } >= 2 }
                evidence("AUTOPLAY section", observer)
                tapSwitch()
                await("AutoPlay off removes upcoming entries") {
                    observer.state().let { !it.autoplayEnabled && it.queue.none { entry -> entry.isAutoplay } }
                }
                println("AUTOPLAY OBSERVED ui-switch=off user-entries=${observer.state().queue.size} upcoming-autoplay=0")
                tapSwitch()
                await("AutoPlay on refills after user entries") { observer.state().autoplayEnabled && observer.state().queue.any { it.isAutoplay } }
                tapLabel("Sleep timer")
                tapLabel("15 minutes")
                await("Timed sleep choice reaches service") { observer.state().sleepTimer == SleepTimerChoice.Minutes15 }
                val beforeHome = checkNotNull(observer.state().sleepRemainingMilliseconds)
                evidence("SLEEP TIMER selected", observer)
                observer.requireForegroundNotification("sleep before Home")
                observer.unbind()
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
                SystemClock.sleep(1_500)
                val background = observer.state()
                check(background.sleepTimer == SleepTimerChoice.Minutes15 && background.playWhenReady)
                check(checkNotNull(background.sleepRemainingMilliseconds) < beforeHome - 1_000)
                println("SLEEP TIMER OBSERVED background=true remaining-before=$beforeHome remaining-after=${background.sleepRemainingMilliseconds} playback=${background.phase}")
            }
            // Each scenario owns one launch intent. The service survives the original activity;
            // reopen through the production Now Playing entry without changing a scenario's intent.
            val show = PlaybackIntents.showNowPlaying(context).setClassName(context, PLAYBACK_ENTRY_ALIAS)
            ActivityScenario.launch<MainActivity>(show).use {
                tapLabel("Up Next")
                await("Sleep countdown visible again") { nodes().any { it.text?.toString()?.startsWith("Sleep in ") == true } }
                tapLabelStarting("Sleep in ")
                tapLabel("Cancel sleep timer")
                await("Cancel clears service timer") { observer.state().sleepTimer == null }
                tapLabel("Sleep timer")
                tapLabel("End of track")
                await("End of track reaches service") { observer.state().sleepTimer == SleepTimerChoice.EndOfTrack }
                evidence("SLEEP TIMER stop point selected", observer)
                observer.onController { controller ->
                    // The real timed choice was driven above; seeking shortens only the natural-end wait.
                    val duration = checkNotNull(controller.state.value.durationMilliseconds)
                    check(controller.state.value.seekable)
                    controller.seek((duration - 2_000).coerceAtLeast(0))
                }
                await("Natural boundary spends sleep timer and pauses the next selected entry", 45_000) {
                    observer.state().let { it.sleepTimer == null && !it.playWhenReady && it.currentIndex == 1 }
                }
                evidence("SLEEP TIMER natural end", observer)
                check(observer.state().error == null)
                println("LISTENING SESSION OBSERVED sleep=endOfTrack next-selected=1 paused=true autoplay=server-candidates cancel=encountered background=countdown-continued")
            }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { all += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        visit(root)
        return all
    }
    private fun tapLabel(label: String) = tap { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun tapLabelStarting(prefix: String) = tap { it.text?.toString()?.startsWith(prefix) == true }
    private fun tapSwitch() = tap { it.isCheckable }
    private fun tap(matches: (AccessibilityNodeInfo) -> Boolean) {
        var selected: AccessibilityNodeInfo? = null
        await("UI control to appear") { selected = nodes().firstOrNull(matches); selected != null }
        var node = checkNotNull(selected)
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "The UI control refused its click" }
        SystemClock.sleep(300)
    }
    private fun evidence(label: String, observer: PlaybackObserver) {
        val lines = nodes().mapNotNull { node ->
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: return@mapNotNull null
            if (text !in listOf("AutoPlay", "Sleep timer", "End of track", "Up Next") && !text.startsWith("Sleep in ")) return@mapNotNull null
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            "text=$text bounds=$bounds"
        }
        println("AX $label ${lines.joinToString(" | ")} phase=${observer.state().phase} wanted=${observer.state().playWhenReady}")
    }
}
