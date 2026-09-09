package com.legitimateapps.dulcet.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Opt-in, value-free phase evidence. Conformance controls install this on every run. */
public class AccountConnectionDiagnostics(private val label: String) : LogSink {
    private val started = TimeSource.Monotonic.markNow()
    private data class Phase(val name: String, val since: Long)
    private val phases = MutableStateFlow<List<Phase>>(emptyList())
    private val requests = MutableStateFlow<List<Job>>(emptyList())

    private fun elapsed(): Long = started.elapsedNow().inWholeMilliseconds

    // RequestTrace messages contain URLs; this instrument deliberately accepts only fixed phases.
    override fun write(message: String) {
        if (!message.startsWith("account.phase ")) return
        val now = elapsed()
        val wallTimeMillis = Clock.System.now().toEpochMilliseconds()
        val event = message.removePrefix("account.phase ")
        var duration: Long? = null
        when {
            event.startsWith("begin ") -> phases.update {
                it + Phase(event.removePrefix("begin "), now)
            }
            event.startsWith("end ") -> phases.update { current ->
                val name = event.removePrefix("end ")
                duration = current.lastOrNull { it.name == name }?.let { now - it.since }
                current.filterNot { it.name == name }
            }
        }
        println("CONFORMANCE_PHASE $label elapsedMs=$now $event durationMs=$duration wallTimeMillis=$wallTimeMillis")
    }

    internal fun observeRequest(job: Job) {
        requests.update { it + job }
        val number = requests.value.size
        write("account.phase event engine-send request=$number")
        job.invokeOnCompletion { cause ->
            // Exception messages may contain credentials; only the type is recorded.
            write("account.phase event request-complete request=$number cause=${cause?.let { it::class.simpleName }}")
        }
    }

    /** Safe to call from a watchdog on another dispatcher while connect is suspended. */
    public fun snapshot(): String {
        val now = elapsed()
        val wallTimeMillis = Clock.System.now().toEpochMilliseconds()
        val pending = phases.value.joinToString { "${it.name}:${now - it.since}ms" }
        val jobs = requests.value.mapIndexed { index, job ->
            "${index + 1}:active=${job.isActive},cancelled=${job.isCancelled},completed=${job.isCompleted}"
        }.joinToString(";")
        return "CONFORMANCE_SNAPSHOT $label elapsedMs=$now pending=[$pending] requestJobs=[$jobs] wallTimeMillis=$wallTimeMillis"
    }
}

internal suspend inline fun <T> LogSink?.accountPhase(name: String, block: () -> T): T {
    this?.write("account.phase begin $name")
    try {
        return block()
    } finally {
        this?.write("account.phase end $name")
    }
}
