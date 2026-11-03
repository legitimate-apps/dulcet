package com.legitimateapps.dulcet

import android.accessibilityservice.AccessibilityService
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
import org.junit.Rule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import org.junit.runner.RunWith

/** Opt-in production UI proof; requires a disposable Navidrome and a clean installation. */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorListeningSessionProofTest {
    @get:Rule val compose = createEmptyComposeRule()
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
                await("the activity consumes its launch request") { observer.state().hasSession }
                requireMediaTimeAdvances(observer, "listening-session")
                // Navigation can outlast this 31-second fixture on a busy emulator. Hold
                // the prepared entry while choosing options; only the Home observation
                // and natural-end observation below need it to advance.
                tapLabel("Pause")
                await("The canary is paused before navigation") { !observer.state().playWhenReady }
                await("AutoPlay server candidates after the user's entry") { observer.state().queue.any { it.isAutoplay } }
                val initial = observer.state()
                check(initial.queue.first().track.rawId == rawId && !initial.queue.first().isAutoplay)
                check(initial.queue.drop(1).all { it.isAutoplay })
                // Deliberately exceed the canary's entire duration. The proof must still
                // own this entry when navigation is slower than a natural track boundary.
                SystemClock.sleep(checkNotNull(initial.durationMilliseconds) + 1_000)
                check(observer.state().let { it.currentIndex == 0 && !it.playWhenReady }) {
                    "The canary advanced during paused navigation"
                }
                tapLabel("Up Next")
                // The one Autoplay: the queue sheet's Autoplay section header carries the switch.
                await("Autoplay heading in Up Next") { nodes().count { it.text?.toString() == "Autoplay" } == 1 }
                evidence("AUTOPLAY section", observer)
                tapSwitch()
                await("Autoplay off removes upcoming entries") {
                    observer.state().let { !it.autoplayEnabled && it.queue.none { entry -> entry.isAutoplay } }
                }
                println("AUTOPLAY OBSERVED ui-switch=off user-entries=${observer.state().queue.size} upcoming-autoplay=0")
                tapSwitch()
                await("Autoplay on refills after user entries") { observer.state().autoplayEnabled && observer.state().queue.any { it.isAutoplay } }
                // The sleep timer lives in the player's More sheet, off the queue sheet.
                systemBackCloses("player.upnext", "player.full")
                println("BACK OBSERVED route=global-action-back up-next=absent player=visible")
                tapLabel("More options")
                systemBackCloses("player.more.sheet", "player.full")
                tapLabel("More options")
                tapControl("listening.sleep")
                systemBackCloses("listening.sleep.sheet", "player.more.sheet")
                tapControl("listening.crossfade")
                systemBackCloses("listening.crossfade.sheet", "player.more.sheet")
                tapControl("listening.sleep")
                tapLabel("15 minutes")
                await("Timed sleep choice reaches service") { observer.state().sleepTimer == SleepTimerChoice.Minutes15 }
                observer.onController { it.play() }
                requireMediaTimeAdvances(observer, "sleep timer before Home")
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
                observer.onController { it.pause() }
            }
            // Each scenario owns one launch intent. The service survives the original activity;
            // reopen through the production Now Playing entry without changing a scenario's intent.
            val show = PlaybackIntents.showNowPlaying(context).setClassName(context, PLAYBACK_ENTRY_ALIAS)
            ActivityScenario.launch<MainActivity>(show).use {
                // The active timer shows on the player itself: the moon-and-countdown chip opens its sheet.
                await("Sleep countdown visible again") { nodes().any { it.text?.toString()?.startsWith("Sleep in ") == true } }
                tapLabelStarting("Sleep in ")
                tapLabel("Turn off timer")
                await("Cancel clears service timer") { observer.state().sleepTimer == null }
                tapLabel("More options")
                tapControl("listening.sleep")
                tapLabel("Sleep after this song")
                await("End of track reaches service") { observer.state().sleepTimer == SleepTimerChoice.EndOfTrack }
                evidence("SLEEP TIMER stop point selected", observer)
                observer.onController { controller ->
                    // The real timed choice was driven above; seeking shortens only the natural-end wait.
                    val state = controller.state.value
                    check(state.currentIndex == 0 && state.queue.first().track.rawId == rawId) {
                        "The paused canary must still own the stop point: index=${state.currentIndex}, phase=${state.phase}"
                    }
                    val duration = checkNotNull(state.durationMilliseconds)
                    check(state.seekable) { "The prepared direct canary must be seekable: phase=${state.phase}, duration=$duration" }
                    controller.seek((duration - 2_000).coerceAtLeast(0))
                    controller.play()
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

    // The v2 Compose rule queues UI effects on its test scheduler. AX/service polls must advance
    // that scheduler too, including the launch-request effect before the first media observation.
    private fun await(what: String, timeoutMillis: Long = 60_000, condition: () -> Boolean) =
        com.legitimateapps.dulcet.emulator.await(what, timeoutMillis) {
            compose.waitForIdle()
            condition()
        }

    private fun systemBackCloses(sheet: String, underneath: String) {
        check(compose.onAllNodesWithTag(sheet).fetchSemanticsNodes().isNotEmpty()) { "$sheet must be open before Back" }
        check(InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) { "Android refused system Back" }
        await("system Back dismisses $sheet and preserves $underneath") {
            compose.onAllNodesWithTag(sheet).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(underneath).fetchSemanticsNodes().isNotEmpty()
        }
        println("SHEET BACK OBSERVED route=global-action-back sheet=$sheet absent=true underneath=$underneath preserved=true")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        val all = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { all += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        visit(root)
        return all
    }
    private fun tapLabel(label: String) = tap(label) { it.text?.toString() == label || it.contentDescription?.toString() == label }
    private fun tapLabelStarting(prefix: String) = tap(prefix) { it.text?.toString()?.startsWith(prefix) == true }
    private fun tapControl(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun tapSwitch() {
        // The queue owns this switch. A generic checkable-node search can select a stale or
        // merged accessibility descendant; use its stable UI identity and real touch action.
        compose.onNodeWithTag("listening.autoplay").performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun tap(label: String, matches: (AccessibilityNodeInfo) -> Boolean) {
        var selected: AccessibilityNodeInfo? = null
        try {
            await("UI control '$label' to appear") { selected = nodes().firstOrNull(matches); selected != null }
        } catch (timeout: IllegalStateException) {
            val visible = nodes().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
            error("${timeout.message}; visible controls=${visible.joinToString(" | ")}")
        }
        var node = checkNotNull(selected)
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "The UI control refused its click" }
        SystemClock.sleep(300)
    }
    private fun evidence(label: String, observer: PlaybackObserver) {
        val lines = nodes().mapNotNull { node ->
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: return@mapNotNull null
            if (text !in listOf("Autoplay", "Sleep timer", "Sleep after this song", "Up Next", "More options", "Turn off timer") && !text.startsWith("Sleep in ")) return@mapNotNull null
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            "text=$text bounds=$bounds"
        }
        val state = observer.state()
        println("AX $label ${lines.joinToString(" | ")} phase=${state.phase} wanted=${state.playWhenReady} " +
            "index=${state.currentIndex} position=${state.positionMilliseconds} duration=${state.durationMilliseconds} seekable=${state.seekable}")
    }
}
