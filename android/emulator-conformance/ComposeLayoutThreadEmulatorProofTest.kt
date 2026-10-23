package com.legitimateapps.dulcet.emulator

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Issue #366: a worker-completed effect must not synchronously measure a lazy list on that worker.
 * The old unconfined rule fails the measure-thread check; the shared Home rule must confine it.
 */
@RunWith(AndroidJUnit4::class)
class ComposeLayoutThreadEmulatorProofTest {
    @get:Rule val compose = createHomeProofComposeRule()

    @Test fun subcomposedScrollStartsAfterMeasureReturns() {
        var measuring = false
        var completed = false
        var resumedDuringMeasure = false
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            activity.onActivity { host ->
                host.setContent {
                    val list = rememberLazyListState()
                    // Scaffold and lazy content subcompose while measuring. An eager effect
                    // that scrolls an already measured list must not re-enter its owner here.
                    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
                        measuring = true
                        try {
                            val child = subcompose("list") {
                                LazyColumn(Modifier.fillMaxSize(), state = list) {
                                    items(30) { Box(Modifier.height(100.dp)) }
                                }
                            }.single().measure(constraints)
                            subcompose("scroll") {
                                LaunchedEffect(Unit) {
                                    resumedDuringMeasure = measuring
                                    list.scrollToItem(2)
                                    completed = true
                                }
                            }
                            layout(child.width, child.height) { child.place(0, 0) }
                        } finally {
                            measuring = false
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                check(completed) { "The subcomposed scroll never completed" }
                check(!resumedDuringMeasure) { "The Home proof resumed its scroll inside measurement" }
            }
            println("HOME LAYOUT REENTRY CONTROL subcomposed-scroll=true resumed-during-measure=false")
        }
    }

    @Test fun workerResumedScrollMeasuresOnlyOnUiThread() {
        val release = CompletableDeferred<Unit>()
        val suspended = CountDownLatch(1)
        var completed = false
        ActivityScenario.launch(ComponentActivity::class.java).use { activity ->
            activity.onActivity { host ->
                host.setContent {
                    val list = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize().layout { measurable, constraints ->
                        check(Looper.myLooper() == Looper.getMainLooper()) {
                            "Home proof measured off the UI thread after worker-resumed scroll: thread=${Thread.currentThread().name}"
                        }
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) { child.place(0, 0) }
                    }, state = list) {
                        items(30) { Box(Modifier.height(100.dp)) }
                    }
                    LaunchedEffect(Unit) {
                        suspended.countDown()
                        release.await()
                        list.scrollToItem(2)
                        check(Looper.myLooper() == Looper.getMainLooper()) {
                            "Home proof scroll resumed off the UI thread: thread=${Thread.currentThread().name}"
                        }
                        completed = true
                    }
                }
            }
            compose.waitForIdle()
            check(suspended.await(10, TimeUnit.SECONDS)) { "The control effect never awaited its worker result" }
            val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "home-proof-result") }
            try {
                worker.submit { release.complete(Unit) }.get(10, TimeUnit.SECONDS)
                compose.waitForIdle()
                compose.runOnIdle { check(completed) { "The worker-resumed scroll never completed" } }
                println("HOME LAYOUT THREAD CONTROL worker-result=true scroll-completed=true measure-thread=main")
            } finally {
                worker.shutdownNow()
            }
        }
    }
}
