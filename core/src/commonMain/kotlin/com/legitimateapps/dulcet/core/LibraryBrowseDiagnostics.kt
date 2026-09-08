package com.legitimateapps.dulcet.core

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.util.AttributeKey
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/** Opt-in phase evidence. Never accepts URLs, parameters, response text or exceptions. */
@OptIn(ExperimentalAtomicApi::class)
internal class LibraryBrowseDiagnostics(private val observer: (String) -> Unit) {
    private val started = TimeSource.Monotonic.markNow()
    private val sequence = AtomicInt(0)

    fun mark(phase: String) {
        try {
            observer("elapsed=${started.elapsedNow()} phase=$phase")
        } catch (_: Throwable) {
            // Diagnostic callbacks cannot cancel requests, skip cleanup/completion, or throw across
            // the Objective-C boundary. Never log or retain an observer's untrusted exception.
        }
    }

    fun request(endpoint: String): (String) -> Unit {
        val id = sequence.fetchAndAdd(1)
        val name = when (endpoint) {
            "getMusicFolders", "getArtists", "getAlbumList2", "getAlbum" -> endpoint
            else -> "other"
        }
        return { phase -> mark("request=$id endpoint=$name $phase") }
    }
}

internal val LibraryRequestPhase = AttributeKey<(String) -> Unit>("DulcetLibraryRequestPhase")

// Ordinary get() saves the response body before returning. This hook observes headers BEFORE
// that save (and before onResponse/State), so a partial-body stall cannot be mislabeled
// as waiting for headers. The withheld-body socket control pins this ordering.
internal val LibraryResponsePhasePlugin = createClientPlugin("DulcetLibraryResponsePhase") {
    val headersPhase = PipelinePhase("DulcetLibraryHeaders")
    client.receivePipeline.insertPhaseBefore(HttpReceivePipeline.Before, headersPhase)
    client.receivePipeline.intercept(headersPhase) {
        subject.call.request.attributes.getOrNull(LibraryRequestPhase)?.invoke("headers-received")
    }
}
