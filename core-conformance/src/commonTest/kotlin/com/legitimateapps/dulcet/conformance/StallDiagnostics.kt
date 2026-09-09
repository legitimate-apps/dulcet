package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.AccountConnectionDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** Uses real time; neither changes runTest's deadline nor consumes its virtual clock. */
internal suspend fun TestScope.withStallDiagnostics(
    label: String,
    pulseIntervalMillis: Long = 5_000,
    watchdogIntervalMillis: Long = 10_000,
    block: suspend (AccountConnectionDiagnostics) -> Unit,
) {
    val diagnostics = AccountConnectionDiagnostics(label)
    val started = TimeSource.Monotonic.markNow()
    val testPulse = MutableStateFlow(0L)
    val pulse = backgroundScope.launch {
        while (isActive) {
            withContext(Dispatchers.Default) { delay(pulseIntervalMillis) }
            testPulse.value = started.elapsedNow().inWholeMilliseconds
        }
    }
    val watchdog = backgroundScope.launch(Dispatchers.Default) {
        var previous = 0L
        while (isActive) {
            delay(watchdogIntervalMillis)
            val now = started.elapsedNow().inWholeMilliseconds
            println(
                diagnostics.snapshot() +
                    " watchdogGapMs=${now - previous} testDispatcherPulseAgeMs=${now - testPulse.value}",
            )
            previous = now
        }
    }
    try {
        diagnostics.phase("test-body") { block(diagnostics) }
    } finally {
        println(diagnostics.snapshot() + " test-body-exited")
        pulse.cancel()
        watchdog.cancel()
    }
}

internal suspend inline fun <T> AccountConnectionDiagnostics.phase(name: String, block: () -> T): T {
    write("account.phase begin $name")
    try {
        return block()
    } finally {
        write("account.phase end $name")
    }
}
